package com.chun.lingzhu

import org.json.JSONObject

/**
 * v1.65 内置更新通道（纯 JVM，可单测）：
 * 设置页可填一个「更新清单地址」（任何静态托管的 JSON），App 每 24 小时自动检查一次（也可手动点）。
 * 清单格式：
 * {
 *   "versionCode": 166,
 *   "versionName": "1.66.0",
 *   "url": "https://…/LingZhu-1.66.0-公测版.apk",
 *   "notes": "更新了什么（可空）"
 * }
 * 只做「发现新版 → 用户点按钮后跳系统 DownloadManager 下载」，下载这一步一定要用户点一下才发生，
 * 打开页面时不自动开下载（免得每次开页面就跳出「开始下载」，看着莫名其妙）；
 * 也不做静默安装（Android 对后台装 APK 层层设卡，用户自己点通知装是最顺的路）。
 * 不检查时零请求、零权限（INTERNET 已有）。
 */
object Update {
    /** 默认清单地址：本开源副本为空。
     *
     *  公开 fork 不应把用户导向维护者的官方更新通道（否则可能下载到与 fork 不同签名 /
     *  不同 applicationId 的官方包）。用户在设置页填自己的清单地址后才启用检查；
     *  留空 = 不自动检查。 */
    val DEFAULT_MANIFEST_URL = BuildConfig.UPDATE_MANIFEST_URL

    const val CHECK_INTERVAL_MS = 24L * 3600 * 1000

    /** 手填的源读不到、内置源顶上时给用户的那句说明（不静默替换，也不把手填的失败当成整体失败）。 */
    const val FALLBACK_NOTE = "（你填的地址读不到，已改用内置源检查）"

    /**
     * 依次要试的清单地址：用户填的（非空）在前，内置源兜底；两者相同 / 为空时自动去重（纯函数，可单测）。
     *
     * - 手填有效值 → [手填]（本开源副本内置源为空，因此不会出现第二个地址）；
     * - 手填为 null / 空串 / 纯空白 → 只回内置源；内置源也为空时返回空列表 = 不检查更新；
     * - 手填 trim 后与内置源相同 → 只留一个；
     * - 手填带前后空格 → 用 trim 后的值。
     */
    fun sources(custom: String?, builtIn: String = DEFAULT_MANIFEST_URL): List<String> =
        listOfNotNull(
            custom?.trim()?.takeIf { it.isNotBlank() },
            builtIn.trim().takeIf { it.isNotBlank() },
        ).distinct()

    data class Manifest(val versionCode: Int, val versionName: String, val url: String, val notes: String)

    fun parse(json: String?): Manifest? = try {
        val o = JSONObject(json ?: return null)
        val vc = o.optInt("versionCode", 0)
        val url = o.optString("url").trim()
        if (vc <= 0 || !url.startsWith("http")) null
        else Manifest(vc, o.optString("versionName").ifBlank { vc.toString() }, url, o.optString("notes"))
    } catch (_: Exception) { null }

    /** 远端比本机新才算更新 */
    fun isNewer(m: Manifest, localCode: Int): Boolean = m.versionCode > localCode

    /** 自动检查节流：距上次检查不足间隔就不查（手动点不受限） */
    fun shouldAutoCheck(lastCheckMs: Long, now: Long, url: String, interval: Long = CHECK_INTERVAL_MS): Boolean =
        url.isNotBlank() && (lastCheckMs <= 0 || now - lastCheckMs >= interval)

    /**
     * 清单 url → DownloadManager 吃得下的 url。清单里写的文件名**允许中文**（Ling-1.85.2-公测版.apk），
     * 但 [android.app.DownloadManager.Request] 内部走 `new URL`，路径里的非 ASCII 字符会直接
     * `IllegalArgumentException: Illegal character in path` —— 表现就是点「下载更新」立刻「下载起不来」
     * （v1.85.2 真机实测抓到：清单能读、检查正常，一到下载就死）。
     * 这里把 **path 段**按 RFC 3986 百分号编码（'/' 保留），scheme/host/query/ref 原样；
     * 已经是纯 ASCII 的 url 原样返回（逐字不变，老通道零影响）。解析失败原样返回 —— 宁可交给
     * DownloadManager 自己报，也不在修 URL 的路上把好 url 改坏。
     */
    fun safeUrl(raw: String): String {
        val needsFix = raw.any { it.code > 127 }
        if (!needsFix) return raw
        return try {
            val u = java.net.URL(raw)
            // URLEncoder 是表单编码（空格→+），路径里要 %20；'/' 按段处理不参与编码
            fun enc(seg: String) = java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            val path = u.path?.trim('/')?.split('/')?.filter { it.isNotEmpty() }?.joinToString("/", prefix = "/") { enc(it) } ?: ""
            val port = if (u.port != -1) ":" + u.port else ""
            val query = u.query?.let { "?" + it } ?: ""
            val ref = u.ref?.let { "#" + it } ?: ""
            u.protocol + "://" + u.host + port + path + query + ref
        } catch (_: Exception) { raw }
    }

    /** 给人看的一行：发现新版 1.66.0（166） */
    fun line(m: Manifest): String = "发现新版 ${m.versionName}（${m.versionCode}）"

    /**
     * 一次检查该在设置页留下什么（纯函数，UI 只负责照着渲染；这样 manual/auto 的分流能单测）。
     * 关键规矩：**自动检查永远不自己开始下载** —— 发现新版也只是写文案 + 亮一个下载按钮，等用户点。
     * 免得人一打开页面就被弹「开始下载」，行为看着莫名失控。
     */
    sealed class CheckResult {
        /** 没什么可说的：自动检查时保持页面空白，不弹任何东西（已是最新 / 清单读不到都走这里） */
        object Silent : CheckResult()

        /** 发现新版：写文案 + 亮下载按钮，等用户点；绝不自动 enqueue */
        data class NewVersion(val manifest: Manifest, val text: String, val button: String) : CheckResult()

        /** 手动检查：已是最新 */
        data class UpToDate(val text: String) : CheckResult()

        /** 手动检查：清单读不到 */
        data class Failed(val text: String) : CheckResult()
    }

    fun check(m: Manifest?, localCode: Int, localName: String, manual: Boolean): CheckResult = when {
        m == null && !manual -> CheckResult.Silent
        m == null -> CheckResult.Failed("✗ 清单读不到（地址写对了吗？内容要是 JSON：versionCode / url）")
        !isNewer(m, localCode) && !manual -> CheckResult.Silent
        !isNewer(m, localCode) -> CheckResult.UpToDate("✓ 已是最新（$localName）")
        else -> CheckResult.NewVersion(
            m,
            "↑ " + line(m) + (if (m.notes.isNotBlank()) "\n" + m.notes.take(200) else ""),
            "下载更新（${m.versionName}）",
        )
    }
}
