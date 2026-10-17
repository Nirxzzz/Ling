package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 「解读词」（v1.80）：模仿电脑版的悬停词典 —— 查一个词不再只给一句干译文，而是给一份小词典：
 * **音标 / 词性 → 2~4 条核心释义 → 一句真例句**。
 *
 * 方法照搬电脑版 `main.js` 的 `wordPrompt`（**逐字对齐，提示词是双端契约，改一处必须改两处**）：
 *  - 只认「一个词 / 短语」（[isWord]），认不出来就不查（省一次请求，也省一次看不懂的回答）；
 *  - 长提示词没结果 → 用最短的一句「只要中文短译」再兜一次（[fallbackPrompt]），很多模型对长提示词不买账；
 *  - 查到的存起来（上限 [CACHE_MAX]），**没查到的 60 秒内不再重打**（[MISS_TTL_MS]）——
 *    上下划词反复触发时不会一直转圈，也不会把额度烧在一个取不到的词上；
 *  - 关思考：思考模式会把输出预算吃光，正文一个字不剩（电脑版踩过这个坑，见那边的注释）。
 *
 * 纯逻辑（提示词 / 判定 / 排版识别 / [Memo] / 落盘格式）都在这个文件里、单测直接跑；
 * 网络在 [OrbService]，装配在 [WordCard]。
 */
object WordDict {
    /** 词典提示词（电脑版 wordPrompt 的前半段，逐字） */
    const val PROMPT_HEAD = "你是双语词典。对用户给的单词或短语，用中文输出，不要寒暄、不要重复原词：\n" +
        "第1行：音标（仅英文词给，用 /.../ 包住）+ 词性缩写\n" +
        "第2~4行：核心释义，每行一条（最重要的在前，可带简短搭配）\n" +
        "最后1行：一个简短例句（原语言 + 中文翻译，用 — 分隔）\n" +
        "总长不超过 90 字。查询："

    /** 兜底提示词（电脑版那两句，逐字）：模型不按格式来时只求一句中文短译 */
    const val FALLBACK_HEAD = "把下面这个词译成中文，只输出中文译文，不要解释、不要标点：\n"

    /** 没查到的词多久内不再重打（电脑版 60s） */
    const val MISS_TTL_MS = 60_000L
    /** 缓存上限（电脑版 300 条 LRU） */
    const val CACHE_MAX = 300
    /** 词典式提问的 max_tokens（电脑版 700；兜底那次 220） */
    const val MAX_TOKENS = 700
    const val MAX_TOKENS_FALLBACK = 220
    /** 单词最长这么多字符（电脑版 40，超了截断） */
    const val MAX_LEN = 40
    /** 电脑版给的总长上限，超了只当「没按格式来」，照显 */
    const val SOFT_MAX_CHARS = 90
    /** 落盘文件（files/worddict.json） */
    const val FILE = "worddict.json"

    fun prompt(word: String) = PROMPT_HEAD + word
    fun fallbackPrompt(word: String) = FALLBACK_HEAD + word

    /** 词条归一：去空白 + 小写 + 截到 [MAX_LEN]（电脑版 `trans:word` 同款） */
    fun normalize(raw: String) = raw.trim().lowercase().take(MAX_LEN)

    /**
     * 是不是「一个词 / 短语」：字母开头、只由字母与 `' -` 组成、2~40 字。
     * **中文词不算** —— 这条线是英汉词典（电脑版那条正则一样只认拉丁字母）。
     *
     * 比电脑版严一点：电脑版先把输入截到 40 字再套正则（于是 41 个字母的长串也会被当成一个词、
     * 截断后拿去查）；安卓这边的词来自卡片正面，可能是一整句甚至一段贴进来的东西，
     * 直接截断去查更糟 —— 超过 40 字就当它不是词，不查。
     */
    private val WORD_RE = Regex("^[a-z\\u00c0-\\u024f][a-z\\u00c0-\\u024f'\\u2019\\-]{1,39}$")
    fun isWord(raw: String): Boolean {
        val w = raw.trim().lowercase()
        if (w.length !in 2..MAX_LEN) return false
        return WORD_RE.matches(w)
    }

    /**
     * 这份文本是不是「已经排好的词典解读」。
     *
     * 是 → **原样上屏**：音标 / 义项 / 例句已经各占一行写好了，再套一遍 [WordSense.formatTranslation]
     * 只会把它裹成「1. 一整坨」，还多塞一个模板例句。
     * 不是（多半就是 DeepL 回来的一句译文）→ 才交给 [WordSense] 排。
     *
     * 判据取宽一点：有换行、有音标斜杠、有例句那根破折号，中一条就算 —— 宁可原样显示，
     * 也别把一份好词典重新排版排坏。
     */
    fun looksFormatted(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains('\n')) return true
        if (Regex("/[^/\\s]{2,}/").containsMatchIn(t)) return true
        return t.contains('—') || t.contains("--")
    }

    /**
     * 掐掉模型画蛇添足的开头：抄一遍原词、或写一行「查询：abandon」。
     * 电脑版不掐（它的卡是 hover 弹的，多一行也不碍事）；安卓这卡面积小，一行噪音就占掉一行的位置。
     * 只掐**第一行**、且只在它确实就是那个词 / 那行标签时才掐，别误伤音标行。
     */
    fun tidy(raw: String, word: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        val lines = text.lines()
        val first = lines.firstOrNull()?.trim().orEmpty()
        val bare = first.trimEnd('：', ':', '　', ' ').trim()
        val drop = bare.equals(word, ignoreCase = true) ||
            bare.replace(Regex("^查询\\s*[：:]?\\s*"), "").equals(word, ignoreCase = true) ||
            Regex("^查询\\s*[：:]\\s*$").matches(first)
        val out = if (drop) lines.drop(1).joinToString("\n").trim() else text
        // 全掐没了就退回原文（宁可多一行，也不能给一张空卡）
        return out.ifBlank { text }
    }

    /** 超没超「90 字」那条软线（设置页/调试用；不拦显示） */
    fun tooLong(text: String): Boolean = text.trim().length > SOFT_MAX_CHARS

    // ---------------- 从一份词典解读里挑行（点「查」给词书卡补音标/例句用） ----------------

    private val PHONETIC = Regex("/[^/\\s]{2,}/")
    private val POS_ONLY = Regex("^(?:[a-z]{1,4}\\.)(?:[/、&]?\\s*[a-z]{1,4}\\.)*$")

    /** 「音标 + 词性」那一行（没有就 null）：第一行里带 /…/，或整行就是词性缩写（v. / n. / adj.） */
    fun phoneticOf(dict: String): String? {
        val first = dict.trim().lines().firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return null
        return if (PHONETIC.containsMatchIn(first) || POS_ONLY.matches(first)) first else null
    }

    /**
     * 例句那一行（没有就 null）。
     * 电脑版约定「最后一行：原语言 + 中文翻译，用 — 分隔」，所以倒着找第一行带破折号的，
     * 找到就把开头那根破折号去掉（卡片上用「例：」当提示词，两根杠叠一起难看）。
     * 没有破折号时只认「最后一行是拉丁字母开头或写着例……的」—— 保守一点，
     * 宁可说「没例句」，也别把一行中文释义当例句贴到卡片上。
     */
    fun exampleOf(dict: String): String? {
        val lines = dict.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null                      // 只有一行 = 不是词典，别把释义当例句
        lines.lastOrNull { it.contains('—') || it.contains("--") }
            ?.let { return it.trimStart('—', '-', ' ', '　').trim().takeIf { s -> s.isNotEmpty() } }
        val last = lines.last()
        return if (last.startsWith("例") || Regex("^[A-Za-z]").containsMatchIn(last)) last else null
    }

    /**
     * 内存 LRU + 「没取到」时间表。纯逻辑，单测直接跑（不碰文件、不碰网络）。
     * 两件事分开记：命中的词进 [hit]（超 [max] 丢最老的），没取到的进 [miss]（[missTtl] 内不再重打）。
     */
    class Memo(private val max: Int = CACHE_MAX, private val missTtl: Long = MISS_TTL_MS) {
        private val hit = LinkedHashMap<String, String>()
        private val miss = LinkedHashMap<String, Long>()

        /** 命中返回文本，否则 null（空串不算命中） */
        fun get(word: String): String? = hit[word]?.takeIf { it.isNotBlank() }

        fun put(word: String, text: String) {
            hit[word] = text
            while (hit.size > max) hit.remove(hit.keys.first())
            miss.remove(word)                      // 这次取到了，撤掉「没取到」的记号
        }

        fun markMiss(word: String, now: Long) {
            miss[word] = now
            while (miss.size > max) miss.remove(miss.keys.first())
        }

        /** [missTtl] 内刚失败过 → 这次别再打接口了 */
        fun missedRecently(word: String, now: Long): Boolean = miss[word]?.let { now - it < missTtl } == true

        fun size(): Int = hit.size
    }

    // ---------------- 落盘（word → 解读文本；按插入顺序，超上限丢最老的） ----------------

    fun parse(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val row = a.optJSONArray(i) ?: return@mapNotNull null
                val w = row.optString(0); val t = row.optString(1)
                if (w.isBlank() || t.isBlank()) null else w to t
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(rows: List<Pair<String, String>>, max: Int = CACHE_MAX): String {
        val keep = if (rows.size <= max) rows else rows.takeLast(max)
        return JSONArray().apply { for ((w, t) in keep) put(JSONArray().put(w).put(t)) }.toString()
    }

    /**
     * 磁盘那层：内存 LRU + files/worddict.json（走 [FileMemo]，和别的存储一个路子）。
     * 为什么落盘：电脑版只放内存（进程一退就没了），但手机上一晚上被系统清掉很常见，
     * 一个词的解读要花一次模型调用，重开应用又要重查一遍不划算。
     */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)
        private val memo = Memo()

        fun get(word: String): String? {
            memo.get(word)?.let { return it }
            val hit = load().firstOrNull { it.first == word }?.second
            if (!hit.isNullOrBlank()) memo.put(word, hit)
            return hit
        }

        fun put(word: String, text: String) {
            memo.put(word, text)
            save(load().filter { it.first != word } + (word to text))
        }

        fun markMiss(word: String, now: Long) = memo.markMiss(word, now)
        fun missedRecently(word: String, now: Long) = memo.missedRecently(word, now)

        private fun load(): List<Pair<String, String>> = FileMemo.get(file) { parse(it) }
        private fun save(rows: List<Pair<String, String>>) = FileMemo.put(file, rows) { toJson(it) }
    }
}
