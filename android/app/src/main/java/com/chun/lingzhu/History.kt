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

    /**
     * 结构严格解析：只给同步判断文件健康用。坏元素不再被 `mapNotNull` 悄悄丢掉
     * （丢掉后同步会把它们当删除发墓碑），而是直接抛错让同步阻断。
     */
    internal fun parseStrict(json: String?): List<Entry> {
        if (json == null) return emptyList()         // 文件不存在 = 合法空库
        if (json.isBlank()) throw IllegalArgumentException("history.json 为空文件")
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
                ?: throw IllegalArgumentException("history[$i] 不是对象")
            val id = integral(o.opt("id"))
                ?: throw IllegalArgumentException("history[$i].id 不是整数")
            if (id <= 0L) throw IllegalArgumentException("history[$i].id 非正数: $id")
            val text = o.opt("text")
            if (text !is String || text.isBlank()) {
                throw IllegalArgumentException("history[$i].text 缺失或为空白")
            }
            for (k in listOf("kind", "label", "src")) {
                val v = o.opt(k)
                if (v != null && v != JSONObject.NULL && v !is String) {
                    throw IllegalArgumentException("history[$i].$k 不是字符串")
                }
            }
            val star = o.opt("star")
            if (star != null && star != JSONObject.NULL && star !is Boolean) {
                throw IllegalArgumentException("history[$i].star 不是布尔值")
            }
        }
        return parse(json)
    }

    private fun integral(v: Any?): Long? {
        if (v !is Number) return null
        val d = v.toDouble()
        if (!d.isFinite()) return null
        val l = v.toLong()
        return if (l.toDouble() == d) l else null
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
        private val app = ctx.applicationContext
        private val file = File(app.filesDir, "history.json")
        private val prefs = Prefs(ctx)

        /** 与 ChatStore 相反：最新的条目在**最前**（ChatStore 是末尾）——两个类都为此加过提醒，别搞混 */
        fun list(): List<Entry> = synchronized(FileMemo) {
            LanSyncStore.recover(app)   // 先重放未完成的同步事务，避免旧缓存回写
            current()
        }

        /**
         * 带健康状态的读取：解析 / IO 失败时 UI 仍按旧口径显示，但错误会留给
         * [readForSync] 阻断同步，不会把「读不出来」当成空库发墓碑。
         */
        private fun current(): List<Entry> =
            FileMemo.getChecked(file, { parse(it) }, { parseStrict(it) }).value ?: emptyList()
        private fun save(l: List<Entry>) = FileMemo.put(file, l) { toJson(it) }

        /** 返回这条的 id（关了保存时返回 -1，什么都不写） */
        fun add(kind: String, label: String, text: String, src: String = ""): Long = synchronized(FileMemo) {
            if (!prefs.saveHistory || text.isBlank()) return@synchronized -1L
            val l = add(list(), Entry(System.currentTimeMillis(), kind, label, text, src))
            save(l)
            l.first().id
        }
        fun update(id: Long, text: String) = synchronized(FileMemo) {
            if (id >= 0 && text.isNotBlank()) save(update(list(), id, text))
        }
        fun delete(id: Long) = synchronized(FileMemo) { save(list().filter { it.id != id }) }
        fun keepStarred() = synchronized(FileMemo) { save(list().filter { it.star }) }
        fun setStar(id: Long, on: Boolean) = synchronized(FileMemo) {
            if (id >= 0) save(setStar(list(), id, on))
        }
        fun get(id: Long): Entry? = synchronized(FileMemo) { list().firstOrNull { it.id == id } }
        fun clear() = synchronized(FileMemo) {
            LanSyncStore.recover(app)
            FileMemo.clear(file, emptyList<Entry>())   // 与 P0-2 同款：typed clear，不留 Any 擦除隐患
        }

        /**
         * 供 LanSyncStore 的同步事务使用：[FileMemo.put] 立即更新内存副本并进写队列（有序、不直接 writeText）。
         * 调用方必须已经持有 `synchronized(FileMemo)`，并在事务末尾用 `FileMemo.flush()` 做落盘屏障。
         */
        internal fun replaceFromSync(list: List<Entry>) {
            FileMemo.put(file, list) { toJson(it) }
        }

        /**
         * 供 LanSyncStore 的恢复路径使用：整表替换缓存并**同步**落盘（落盘后才返回，失败抛异常）。
         * 调用方必须已经持有 `synchronized(FileMemo)`。
         */
        internal fun putFromSync(list: List<Entry>) {
            FileMemo.putAndWait(file, list) { toJson(it) }
            // 权威恢复：整表已由 journal 覆盖，旧的加载失败不再适用。
            FileMemo.clearLoadError(file)
        }

        /** 同步专用严格读取：返回健康状态而不是把坏文件当空库。调用方必须先恢复并持有 FileMemo 锁。 */
        internal fun readForSync(): FileMemo.Loaded<List<Entry>> =
            FileMemo.getChecked(file, { parse(it) }, { parseStrict(it) })
    }
}
