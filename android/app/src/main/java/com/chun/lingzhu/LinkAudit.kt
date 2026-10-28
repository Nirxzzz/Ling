package com.chun.lingzhu

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 手机侧 Link 指令审计（[LinkDispatcher] 每执行一条就留一行）。
 *
 * ## 记什么、不记什么
 * 只记**元数据**：时间、工具名、请求方名字、成败码、确认结果、一句人类可读的细节（参数个数 / 输出字节数 / 耗时）。
 * **绝不记**记忆正文、提醒文案、屏幕 OCR 文本、截图、配对密钥 —— 审计文件是「用户能在设置页翻看、
 * 也可能被导出给别人」的东西，正文进去就等于把隐私抄了一份。
 * 也因此 [detail] 由 dispatcher 用固定的短句式构造，不接受任意长文本（[record] 再兜一层截断与去换行）。
 *
 * ## 为什么是 JSONL
 * 追加写一行、不需要重写整个文件；坏了最多丢坏的那一行，`recent()` 解析失败的行直接跳过。
 * 单文件上限 [MAX_BYTES]，超了只保留最近 [KEEP_LINES] 行 —— 审计不是账单，翻得到最近几十条就够。
 *
 * ## 线程
 * 传输层在 `ling-link-exec` 线程写、设置页在 io 线程读，实例还是各 new 一个（LinkSettingsActivity 就 new 了新的），
 * 所以锁必须是**进程级**的（[LOCK]），不能是实例锁。
 */
class LinkAudit(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    /**
     * 记一行。参数全部会被截断/去换行：[tool] ≤ 40、[client] ≤ 40、[outcome] ≤ 24、[confirm] ≤ 12、[detail] ≤ 200。
     * 任何异常都吞掉 —— 审计写失败不该让一条本来能成功的指令变成失败。
     */
    fun record(tool: String, client: String, outcome: String, confirm: String, detail: String = "") {
        val row = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("tool", clean(tool, 40))
            .put("client", clean(client, 40))
            .put("outcome", clean(outcome, 24))
            .put("confirm", normalizeConfirm(confirm))
            .put("detail", clean(detail, 200))
        synchronized(LOCK) {
            try {
                file.parentFile?.mkdirs()
                append(row.toString())
                if (file.length() > MAX_BYTES) trim()
            } catch (_: Exception) {
                // 审计失败不影响业务：这一条丢了就丢了，不抛。
            }
        }
    }

    /** 最近 [limit] 条，**新的在前**（设置页直接按顺序往下画）。坏行跳过。 */
    fun recent(limit: Int = 50): List<JSONObject> {
        val n = limit.coerceIn(1, 500)
        val lines = synchronized(LOCK) {
            try {
                if (!file.exists()) emptyList() else file.readLines()
            } catch (_: Exception) {
                emptyList()
            }
        }
        val out = ArrayList<JSONObject>(n)
        for (i in lines.indices.reversed()) {
            if (out.size >= n) break
            val o = try { JSONObject(lines[i]) } catch (_: Exception) { continue }
            if (o.optLong("at", 0L) <= 0L) continue
            out.add(o)
        }
        return out
    }

    /** 导出（整段 JSONL 原文，给「复制 / 分享审计」用）。读不到给空串。 */
    fun export(): String = synchronized(LOCK) {
        try {
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    /** 清空（用户主动） */
    fun clear() = synchronized(LOCK) {
        try { file.delete() } catch (_: Exception) {}
    }

    // ---------------- 内部 ----------------

    private fun append(line: String) {
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** 超上限：保留最近 [KEEP_LINES] 行重写（一次性，之后又是纯追加） */
    private fun trim() {
        val keep = try { file.readLines().takeLast(KEEP_LINES) } catch (_: Exception) { emptyList() }
        val tmp = File(file.parentFile, "$FILE.tmp")
        try {
            tmp.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    companion object {
        const val FILE = "link-audit.jsonl"
        /** 单文件上限：约几千行，够翻；再大对排查没有增量价值，只是白占空间 */
        const val MAX_BYTES = 512L * 1024L
        const val KEEP_LINES = 3000

        /** 进程级锁：审计文件只有一个，实例可以有多个（见类注释） */
        private val LOCK = Any()

        /** 协议里只有这三个值有意义，其余一律 n/a（与 LingLink.normalizeConfirm 同口径） */
        fun normalizeConfirm(v: String?): String = when (v) {
            "approved", "denied", "timeout" -> v!!
            else -> "n/a"
        }

        /** 去换行 / 控制字符 + 截断：审计是一行一条，混进换行就把行结构毁了 */
        internal fun clean(s: String?, max: Int): String {
            if (s.isNullOrEmpty()) return ""
            val sb = StringBuilder(minOf(s.length, max))
            for (ch in s) {
                if (sb.length >= max) break
                sb.append(if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else if (ch.code < 0x20) ' ' else ch)
            }
            return sb.toString().trim()
        }
    }
}
