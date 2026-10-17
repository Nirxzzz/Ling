package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 历史记录（v1.58）：识别结果（含追问）和翻译，存在应用私有目录的 history.json，最新的在前，最多 MAX 条。
 * 以前 OrbService 只在内存里留 30 条给面板「单击重开」用，关了就没；现在落盘、能在「历史记录」页看。
 * 列表逻辑是纯函数（单元测试直接测），读写文件只在 Store 里。设置里能关掉「保存历史记录」。
 */
object History {
    const val MAX = 200
    const val KIND_SOLVE = "solve"
    const val KIND_TRANSLATE = "translate"

    /** id = 创建时刻（毫秒，同一毫秒冲突时 +1）；label = 模式名 / 翻译方向；src = 翻译原文 */
    data class Entry(val id: Long, val kind: String, val label: String, val text: String, val src: String = "", val star: Boolean = false)

    fun parse(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) null
                else Entry(o.optLong("id"), o.optString("kind", KIND_SOLVE), o.optString("label"), text, o.optString("src"), o.optBoolean("star"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        for (e in list) put(JSONObject().put("id", e.id).put("kind", e.kind).put("label", e.label).put("text", e.text).put("src", e.src).apply { if (e.star) put("star", true) })
    }.toString()

    /** 加到最前；和最新一条内容完全一样就不重复记（自动扫描画面没变时常见） */
    fun add(list: List<Entry>, e: Entry, max: Int = MAX): List<Entry> {
        val first = list.firstOrNull()
        if (first != null && first.kind == e.kind && first.text == e.text && first.src == e.src) return list
        val id = if (list.any { it.id == e.id }) (list.maxOf { it.id } + 1) else e.id
        return trim(listOf(e.copy(id = id)) + list, max)
    }

    /** 超过上限先删最老的没收藏的（v1.64：收藏的错题不会被新记录挤掉） */
    fun trim(list: List<Entry>, max: Int): List<Entry> {
        if (list.size <= max) return list
        val out = list.toMutableList()
        var i = out.size - 1
        while (out.size > max && i >= 0) { if (!out[i].star) out.removeAt(i); i-- }
        return out
    }

    fun setStar(list: List<Entry>, id: Long, on: Boolean): List<Entry> = list.map { if (it.id == id) it.copy(star = on) else it }

    /** 导出成 Markdown（v1.64）：每条一节，标题是时间 · 类型 · 模式，收藏的带 ★ */
    fun toMarkdown(list: List<Entry>, title: String, time: (Long) -> String): String = buildString {
        append("# ").append(title).append("\n\n")
        for (e in list) {
            append("## ").append(if (e.star) "★ " else "").append(time(e.id)).append(" · ").append(if (e.kind == KIND_TRANSLATE) "翻译" else "识别")
            if (e.label.isNotBlank()) append(" · ").append(e.label.trim().removePrefix("· ").trim())
            append("\n\n")
            if (e.kind == KIND_TRANSLATE && e.src.isNotBlank()) append("> ").append(e.src.trim().replace("\n", "\n> ")).append("\n\n")
            append(e.text.trim()).append("\n\n---\n\n")
        }
    }

    fun update(list: List<Entry>, id: Long, text: String): List<Entry> = list.map { if (it.id == id) it.copy(text = text) else it }

    /** 搜索：原文、回答、模式名里包含关键词（不分大小写） */
    fun filter(list: List<Entry>, q: String): List<Entry> {
        val k = q.trim()
        if (k.isEmpty()) return list
        return list.filter { it.text.contains(k, true) || it.src.contains(k, true) || it.label.contains(k, true) }
    }

    /** 列表里的两行预览：去掉 markdown 记号和公式定界符，压成一行 */
    fun preview(e: Entry, n: Int = 80): String {
        val raw = if (e.kind == KIND_TRANSLATE && e.src.isNotBlank()) e.src + " → " + e.text else e.text
        // 公式换成线性写法（\dfrac{1}{6} → 1/6），不然预览里是一串反斜杠
        val s = raw.lines().joinToString(" ") { line -> MathText.split(line).joinToString("") { if (it.math) MathText.toPlain(it.text) else it.text } }
            .replace(Regex("""(^|\s)#{1,4}\s+"""), " ").replace("**", "")
            .replace(Regex("""\s+"""), " ").trim()
        return if (s.length > n) s.take(n) + "…" else s
    }

    /** 读写文件：内存副本 + 后台落盘（FileMemo，v1.63 起不在调用线程读写文件） */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, "history.json")
        private val prefs = Prefs(ctx)

        /** 与 ChatStore 相反：最新的条目在**最前**（ChatStore 是末尾）——两个类都为此加过提醒，别搞混 */
        fun list(): List<Entry> = FileMemo.get(file) { parse(it) }
        private fun save(l: List<Entry>) = FileMemo.put(file, l) { toJson(it) }

        /** 返回这条的 id（关了保存时返回 -1，什么都不写） */
        @Synchronized fun add(kind: String, label: String, text: String, src: String = ""): Long {
            if (!prefs.saveHistory || text.isBlank()) return -1
            val l = add(list(), Entry(System.currentTimeMillis(), kind, label, text, src))
            save(l)
            return l.first().id
        }
        @Synchronized fun update(id: Long, text: String) { if (id >= 0 && text.isNotBlank()) save(update(list(), id, text)) }
        @Synchronized fun delete(id: Long) = save(list().filter { it.id != id })
        @Synchronized fun keepStarred() = save(list().filter { it.star })
        @Synchronized fun setStar(id: Long, on: Boolean) { if (id >= 0) save(setStar(list(), id, on)) }
        fun get(id: Long): Entry? = list().firstOrNull { it.id == id }
        @Synchronized fun clear() = FileMemo.clear(file, emptyList<Entry>())   // 与 P0-2 同款：typed clear，不留 Any 擦除隐患
    }
}
