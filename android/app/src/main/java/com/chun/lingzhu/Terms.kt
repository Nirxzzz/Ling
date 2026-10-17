package com.chun.lingzhu

/**
 * 关键词清单的解析（纯 JVM，可单测）。v1.65 通识模式引入，v1.66 起所有模式都带（设置「识别关键词」）。
 * 模型按 Prompts.TERMS_RULE 在回答末尾输出一行「[可点击名词] a｜b｜c」，
 * 面板把这一行剥出正文、拆成词块。模型偶尔不守格式：分隔符用半角 | 或顿号、
 * 词带「1. 」编号前缀、行首加 Markdown 粗体 / 列表符号、词套了引号 —— 都在这里兜住；
 * 完全没有任何标记行时原样返回（面板不显示词块行）；有标记行但解析不出词，只剥标记、不显示词块。
 *
 * v1.66：型号 / 规格词（R7250、P3 广色域、Wi-Fi 7、LPDDR5X、2.5K 屏）原样保留 ——
 * 不按「纯字母数字」「含空格」过滤，编号前缀只剥「1. 」这种后面不跟数字的，长度上限放到 32。
 *
 * v1.89 流式安全：标记行（含只吐到一半的「[可点」和只有标记没写词的）一律从正文剥掉，
 * 免得流式渲染的那一瞬间把元数据漏给用户；词块功能不变（解析出词才有词块）。
 */
object Terms {
    /** 单个词的长度上限：比提示词的 20 宽一截，只挡模型把整句话塞进来 */
    const val MAX_LEN = 32
    const val MAX_COUNT = 10

    private val LEAD = Regex("^[\\s*_>#-]+")
    private val NUMBERED = Regex("^[0-9]{1,2}[.、)）](?![0-9])\\s*")
    private val QUOTES = "「」『』“”\"'`*_【】[]"

    private fun headLine(line: String): String? {
        val t = LEAD.replace(line.trim(), "")
        if (!t.startsWith(Prompts.TERMS_HEAD)) return null
        return t.removePrefix(Prompts.TERMS_HEAD).trim().trimStart(':', '：', '*').trimEnd('*', '。', '.')
    }

    /**
     * v1.89 流式安全：模型正在一个字一个字吐清单行时，可能只吐到半个标记（「[可点」「[」）或只吐了标记还没写词，
     * 这些半截元数据不能在流式渲染时漏进正文。判据：**最后一行**以 '[' 开头、且是 [Prompts.TERMS_HEAD] 的真前缀。
     * 只认最后一行 —— 正文中间真出现「[」这种行不该被误删。整行等于标记的情形 [headLine] 已经处理。
     */
    private fun partialMetaLine(line: String, isLast: Boolean): Boolean {
        if (!isLast) return false
        val t = LEAD.replace(line.trim(), "")
        return t.isNotEmpty() && t.startsWith("[") && t != Prompts.TERMS_HEAD && Prompts.TERMS_HEAD.startsWith(t)
    }

    /**
     * 返回 (剥掉清单行的正文, 名词列表)。有好几行清单（追问里模型又列了一次）：词取第一行，所有清单行都从正文剥掉。
     * 标记行本身（哪怕后面还没写词、或只写了一半）一律从正文剥掉 —— 元数据不往正文里漏。
     */
    fun parse(t: String): Pair<String, List<String>> {
        val lines = t.lines()
        val metaAt = lines.indices.filter { headLine(lines[it]) != null || partialMetaLine(lines[it], it == lines.lastIndex) }
        if (metaAt.isEmpty()) return t to emptyList()
        val body = headLine(lines[metaAt.first()])
        val terms = body?.split('｜', '|', '、')
            ?.map { cleanTerm(it) }
            ?.map { shortenEnglish(it) }
            ?.filter { it.isNotEmpty() && it.length <= MAX_LEN }
            ?.distinct()
            ?.take(MAX_COUNT)
            ?: emptyList()
        val rest = lines.filterIndexed { i, _ -> i !in metaAt }.joinToString("\n")
        return rest.trimEnd('\n', ' ') to terms
    }

    fun cleanTerm(raw: String): String =
        NUMBERED.replace(raw.trim(), "").trim().trim { it in QUOTES || it.isWhitespace() }

    /**
     * 纯英文长词组（4 个单词以上）只留前 3 个（模型偶尔把一句英文当名词塞进来）。
     * 1~3 个单词的（Material Design、Gorilla Glass、Wi-Fi 7）、带数字的、中英混排的都原样保留。
     */
    fun shortenEnglish(t: String): String {
        if (!Regex("^[A-Za-z][A-Za-z' -]*$").matches(t)) return t
        val words = t.split(' ').filter { it.isNotEmpty() }
        return if (words.size > 3) words.take(3).joinToString(" ") else t
    }
}
