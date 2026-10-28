package com.chun.lingzhu

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.85.7 更新下载的**兜底通道**：不走系统 DownloadManager，App 自己流式下载到
 * 「公共下载目录」（MediaStore.Downloads，API 29+ 官方 API，写自己贡献的文件不需要任何存储权限，
 * 也没有引入任何第三方库），完成后返回 content:// uri 直接拉系统安装器。
 *
 * 为什么要有它：真机实测（用户设备）DownloadManager 对更新直链「enqueue 成功但字节 0 卡死」，
 * 且部分精简 ROM 连 DownloadManager 服务都没有（模拟器镜像就是）——系统下载器是单点，
 * 必须有一条不依赖它的路。下载仍然流式 + 进度回调，失败清理半截文件。
 *
 * 纪律：网络在调用方线程（后台 thread）；本类只管「给 url → 拿装得了的 uri」，UI 文案一概不碰。
 */
object UpdateDirect {
    /** 系统下载器「字节 0 卡住」的判定阈值：12 秒一个字节都没有就别等了 */
    const val STUCK_MS = 12_000L

    fun isStuck(startedMs: Long, soFar: Long, now: Long): Boolean =
        soFar <= 0L && startedMs > 0L && now - startedMs >= STUCK_MS

    fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * 流式下载到 MediaStore.Downloads，返回安装可用的 uri；失败删除半截文件并返回 null。
     * [onProgress]（已下字节，总字节；总字节未知时 -1）按 ≥300ms 节流回调。
     */
    fun download(ctx: Context, url: String, fileName: String, onProgress: (Long, Long) -> Unit): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null   // MediaStore.Downloads 是 Q 起的 API
        val safe = Update.safeUrl(url)
        val resolver = ctx.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: return null
        try {
            val conn = URL(safe).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/vnd.android.package-archive, */*")
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var got = 0L
                var lastTick = 0L
                conn.inputStream.use { input ->
                    resolver.openOutputStream(uri)?.use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            // 连接被中途掐断时部分实现会返回 0 而不是 -1：0 字节不前进就按失败处理，
                            // 否则这里会空转成死循环，半截包也永远留在 Downloads 里。
                            if (r < 0) break
                            if (r == 0) throw java.io.IOException("下载中断：读取无进展")
                            out.write(buf, 0, r)
                            got += r
                            if (total >= 0 && got > total) throw java.io.IOException("下载异常：读取字节超过声明的 ${total}")
                            val t = System.currentTimeMillis()
                            if (t - lastTick > 300) { onProgress(got, total); lastTick = t }
                        }
                        out.flush()
                    } ?: throw java.io.IOException("输出流打不开")
                }
                // 声明了 Content-Length 就必须一个字节不少：少了就是连接提前结束，宁可失败重试，
                // 也不能把半截 APK 交给安装器（安装器只会报「解析失败」）。
                if (total >= 0 && got != total) {
                    throw java.io.IOException("下载不完整：只收到 $got / $total 字节")
                }
                onProgress(got, total)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return uri
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }

    /** 下载清单（任何静态托管的 JSON）——检查更新那条线在部分 ROM 上也走本类（DownloadManager 无关） */
    fun fetchManifestText(url: String): String? = try {
        val conn = URL(Update.safeUrl(url)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) null
            else BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }

    /** 下载完成的文件名（MediaStore 里好认）：Ling-1.85.7-内测版.apk 这种 */
    fun fileNameFor(versionName: String, internal: Boolean): String {
        val safe = versionName.replace("/", "_")
        return "Ling-$safe" + if (internal) "-internal.apk" else "-public.apk"
    }

    /** 从清单 JSON 里拿 url（和 [Update.parse] 同构，避免 UI 层反复解析） */
    fun urlOf(manifestJson: String): String? = try {
        JSONObject(manifestJson).optString("url").trim().takeIf { it.startsWith("http") }
    } catch (_: Exception) { null }

    /** 兜底：外部下载目录（老设备 MediaStore 不可用时其实用不上 —— API29 门槛外仍走系统下载器） */
    fun downloadsDirName(): String = Environment.DIRECTORY_DOWNLOADS
}
