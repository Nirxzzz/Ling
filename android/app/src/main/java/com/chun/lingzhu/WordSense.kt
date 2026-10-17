package com.chun.lingzhu

/**
 * 单词释义排版（纯函数，单元测试直接测，不碰 UI）：
 * 把词库背面那行「词性 释义」或翻译回来的结果，排成详细卡片文本：
 *  - 义项排序：英美日常地道用法在前，生僻 / 罕用义在后；输入里带序号（1. 2. …）就完全尊重原顺序，不再重排。
 *  - 每个义项 = 词性 + 中文释义 + 一个简单情景例句（例句不超过 12 个词，生活化）。
 *  - 输出纯文字（换行分段，不用 HTML）；front / back 沿用 Memo.clip 截断（200 / 500）。
 * 输入字符串、输出字符串，WordCard / OrbService 那层只需要一行 Words.prettify(card)。
 */
object WordSense {
    /** 例句词数上限 */
    const val EXAMPLE_MAX_WORDS = 12
    const val FRONT_MAX = 200
    const val BACK_MAX = 500

    /** 一个义项：pos = 词性（如 "v."），meaning = 中文释义，example = 情景例句，index = 输入里的序号（0 = 没写序号） */
    data class Sense(val pos: String, val meaning: String, val example: String = "", val index: Int = 0) {
        /** 生僻 / 罕用义：排到后面 */
        val rare: Boolean =
            listOf("生僻", "罕", "古语", "旧时", "【古】", " Rare", "rare").any { meaning.contains(it) || pos.contains(it) }
    }

    /** 一行「v. 放弃；抛弃」或「1. n. 摘要」→ 义项；认不出词性就整行当释义 */
    fun parseSense(line: String): Sense? {
        val s = line.trim().trimStart('-', '*', '·').trim()
        if (s.isEmpty() || s.startsWith("#")) return null
        // 序号：1. / 1、 / 1）
        var index = 0
        var body = s
        val num = Regex("^(\\d{1,2})[.、）)]\\s*(.+)$").find(body)
        if (num != null) { index = num.groupValues[1].toInt(); body = num.groupValues[2].trim() }
        // 词性缩写：v. / n. / adj. / adv. / prep. / conj. / pron. / vt. / vi. / phr.（可连写如 v./n.）
        val m = Regex("^((?:[a-z]{1,4}\\.[/&、]?\\s*)+)(\\p{IsHan}.*)$").find(body)
        if (m != null) return Sense(m.groupValues[1].trim(), m.groupValues[2].trim(), index = index)
        return Sense("", body, index = index)
    }

    /** 背面文本 → 义项列表：按行拆；一行里还带多个序号义项（「1. n. 摘要 2. v. 摘录」）也拆开 */
    fun parseSenses(back: String): List<Sense> {
        val out = ArrayList<Sense>()
        for (line in back.lines()) {
            val chunks = splitNumbered(line)
            for (c in chunks) parseSense(c)?.let { out.add(it) }
        }
        return out
    }

    /** 把「1. n. 摘要 2. v. 摘录」切成两条；没有序号就整行返回 */
    private fun splitNumbered(line: String): List<String> {
        val ms = Regex("\\d{1,2}[.、）)]\\s*").findAll(line).toList()
        val starts = ms.filter { m ->
            val at = m.range.first
            at == 0 || line.getOrNull(at - 1)?.isWhitespace() == true
        }
        if (starts.size < 2) return listOf(line)
        val out = ArrayList<String>()
        for ((i, m) in starts.withIndex()) {
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else line.length
            val piece = line.substring(m.range.first, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
        }
        return out
    }

    /** 义项排序：全都带序号 → 原顺序（尊重输入）；否则非生僻在前、生僻在后（稳定排序） */
    fun ordered(senses: List<Sense>): List<Sense> =
        if (senses.isNotEmpty() && senses.all { it.index > 0 }) senses
        else senses.sortedBy { it.rare }

    /**
     * ⚠️ v1.80 删掉了「按词性套模板拼例句」那个 [exampleFor]：
     * 拼出来的句子经常是错的（名词套出「I read about abandon in the news.」），
     * 而**假句子比没有句子更糟** —— 用户会当成真例句背下去。
     * 例句现在只有两个来源：词书自带（[Words.Card.example]）或点「查」从词典拿（[WordDict.exampleOf]）。
     */

    /** 词数截断：只按空白数词，超了就砍到上限为止 */
    fun clipWords(s: String, max: Int): String {
        val ws = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (ws.size <= max) ws.joinToString(" ") else ws.take(max).joinToString(" ")
    }

    /** 单个义项 → 两行文字：词性 + 释义，下一行例句 */
    fun formatSense(n: Int, sense: Sense): String {
        val head = (if (sense.pos.isNotEmpty()) sense.pos + " " else "") + sense.meaning
        val ex = if (sense.example.isEmpty()) "" else "\n   例：" + sense.example
        return "$n. $head$ex"
    }

    /**
     * 排成完整背面文本（纯文字）：
     *   1. v. 放弃；抛弃
     *      例：They often abandon together after work.
     *   2. ...
     * 超过 500 字用 Memo.clip 截断（带省略号）。
     */
    fun format(senses: List<Sense>, backMax: Int = BACK_MAX): String {
        val os = ordered(senses)
        if (os.isEmpty()) return ""
        val text = os.mapIndexed { i, s -> formatSense(i + 1, s) }.joinToString("\n")
        return Memo.clip(text, backMax - 1)   // Memo.clip 截断后还会补一个省略号，这里预留 1 个字，保证总长不超过 backMax
    }

    /** 词典背面一行（或几行）→ 详细卡片文本 */
    fun formatBack(back: String): String = format(parseSenses(back))

    /** 翻译结果（DeepL 只有译文，没有词性）→ 单义项卡片。**不编例句**（v1.80：假句子不如没有） */
    fun formatTranslation(word: String, translation: String): String {
        val t = translation.trim()
        if (t.isEmpty()) return ""
        return format(listOf(Sense("", t)))
    }

    /**
     * 卡片背面最终文本（v1.80）：词书的释义 + 词书自带的例句。
     * 例句单独一行、前面写「例：」（和词库背面里内联例句一个口径）。
     * 没有释义只有例句也照显 —— 那是词书给的，别丢。
     */
    fun cardBack(back: String, example: String = ""): String {
        val b = back.trim()
        val e = example.trim()
        return when {
            e.isEmpty() -> b
            b.isEmpty() -> "例：$e"
            else -> "$b\n例：$e"
        }
    }

    /**
     * 词库背面 + 一份词典解读 → 卡片上的最终文本（v1.80，点「查」补例句用）。
     *
     * 有词库背面时**只往上补音标/词性和例句，绝不覆盖词库主人写的释义**。
     * 挑行而不是整段贴：词典那三段是「音标/词性 → 2~4 条释义 → 例句」，中间的释义段丢掉，
     * 首行（音标 + 词性）和末行（例句）才是词书上没有的东西。
     * 已经补过就不再补（重复点「查」不该把例句叠两遍）。
     */
    fun appendDict(back: String, dict: String): String {
        val base = back.trimEnd()
        if (dict.isBlank()) return base
        val ex = WordDict.exampleOf(dict)
        if (!ex.isNullOrBlank() && base.contains(ex.take(24))) return base      // 补过了
        val ph = WordDict.phoneticOf(dict)
        val added = listOfNotNull(ph, ex?.let { "例：$it" }).filter { it.isNotBlank() }
        // 抽不出音标/例句（模型只回了一句短译）→ 整段贴上去，总比点了「查」什么都没发生强
        if (added.isEmpty()) return if (base.isBlank()) dict.trim() else "$base\n${dict.trim()}"
        return (if (base.isBlank()) "" else "$base\n") + added.joinToString("\n")
    }

    /** front 截断，沿用 Memo.clip（预留 1 个字给省略号，保证总长不超过 200） */
    fun clipFront(front: String): String = Memo.clip(front, FRONT_MAX - 1)
}
