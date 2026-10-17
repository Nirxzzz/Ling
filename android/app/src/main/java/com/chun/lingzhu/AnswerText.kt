package com.chun.lingzhu

/**
 * 从模型输出里拆出「答案」那一段，给面板顶部的「答案先行」一行用（纯函数，可单测）。
 *  - 做题格式：「## 答案」到下一个标题之间
 *  - 通用格式：「**答案：** …」这一行（以及紧跟的非空行，直到空行 / 下一个加粗小标题）
 *  - 有多道题（多个「## 答案」）就不拆 —— 顶部放不下，照原样在正文里看
 */
object AnswerText {
    private val EXAM_HEAD = Regex("^#{1,3}\\s*答案\\s*[:：]?\\s*$")
    private val ANY_HEAD = Regex("^#{1,4}\\s")
    private val GEN_HEAD = Regex("^\\s*(\\*\\*)?答案[:：](\\*\\*)?\\s*(.*)$")
    private val BOLD_LABEL = Regex("^\\s*\\*\\*[^*]{1,12}[:：]\\*\\*")

    fun split(src: String): Pair<String, String> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val examIdx = lines.indices.filter { EXAM_HEAD.matches(lines[it].trim()) }
        if (examIdx.size == 1) {
            val s = examIdx[0]
            var e = s + 1
            while (e < lines.size && !ANY_HEAD.containsMatchIn(lines[e].trim())) e++
            val ans = clean(lines.subList(s + 1, e).joinToString("\n"))
            val rest = (lines.subList(0, s) + lines.subList(e, lines.size)).joinToString("\n").trim('\n')
            return ans to rest
        }
        if (examIdx.size > 1) return "" to src
        val g = lines.indexOfFirst { GEN_HEAD.matches(it) }
        if (g >= 0 && lines.count { GEN_HEAD.matches(it) } == 1) {
            val first = GEN_HEAD.find(lines[g])!!.groupValues[3]
            var e = g + 1
            val parts = mutableListOf(first)
            // 「答案：」同一行已经有内容：答案就是这一行，下面紧跟的理由留在正文（v1.53 以前一直吞到空行，理由整段进了顶部答案行被截掉）
            if (first.isBlank()) while (e < lines.size && lines[e].isNotBlank() && !BOLD_LABEL.containsMatchIn(lines[e]) && !ANY_HEAD.containsMatchIn(lines[e].trim())) { parts.add(lines[e]); e++ }
            val ans = clean(parts.joinToString("\n"))
            val rest = (lines.subList(0, g) + lines.subList(e, lines.size)).joinToString("\n").trim('\n')
            return ans to rest
        }
        return "" to src
    }

    /** 顶部答案行最多显示几行；答案比这长时正文里保留完整原文，免得被截掉的部分哪儿都看不到 */
    const val PANEL_LINES = 3
    private const val PANEL_CHARS = 72

    /**
     * 面板用：(顶部答案行, 正文)。答案短 → 正文去掉答案段；答案长 → 正文照原样全留。
     * 追问（v1.57，「## 追问：…」起的段）不参与拆答案：顶部永远是第一轮的答案，追问和回答接在正文后面。
     */
    fun forPanel(src: String): Pair<String, String> {
        val (first, follow) = splitFollowUps(src)
        val (ans, rest) = split(first)
        if (ans.isEmpty()) return "" to src
        val long = ans.lines().size > PANEL_LINES || ans.length > PANEL_CHARS
        return ans to ((if (long) first else rest) + follow)
    }

    /** (第一轮回答, 从第一个「## 追问：」起的全部，含前导换行) */
    fun splitFollowUps(src: String): Pair<String, String> {
        val i = src.indexOf("\n" + Prompts.FOLLOW_HEAD)
        return if (i < 0) src to "" else src.substring(0, i) to "\n\n" + src.substring(i).trimStart('\n')
    }

    fun firstLine(src: String): String = clean(src.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !ANY_HEAD.containsMatchIn(it) } ?: "").take(80)

    private fun clean(s: String): String = s.replace("**", "").replace("`", "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    // ---------------- v1.67 陪伴 M1：「记住这题」动作行 ----------------

    /**
     * 动作行的固定写法（面板靠它把这一行从正文里摘出去，换成一个可点的 chip）。
     * 照 [Prompts.TERMS_HEAD] 的拆行套路 —— 模型偶尔不守格式，所以认得也认得宽松些：
     * 行首的列表符号 / 编号 / 粗体都剥掉，`[记住这题]` 和 `【记住这题】` 都认，后面跟了内容也认。
     */
    const val REMEMBER_HEAD = "[记住这题]"

    private val LEAD = Regex("^[\\s*_>#-]+")
    private val NUMBERED = Regex("^[0-9]{1,2}[.、)）]\\s*")
    private val POINT_HEAD = Regex("^#{1,4}\\s*考点\\s*[:：]?\\s*$")

    /**
     * 这一行是不是「记住这题」；返回冒号 / 括号后面跟的话题（没有就空串），不是这一行就 null。
     * 照 [Prompts.TERMS_HEAD] 的宽松度：行首列表符号 / 编号 / 粗体都剥掉，`[记住这题]` 和 `【记住这题】` 都认。
     */
    private fun rememberLine(line: String): String? {
        var t = LEAD.replace(line.trim(), "")
        t = NUMBERED.replace(t, "").trim().trim('*', '`').trim()
        if (t.startsWith(REMEMBER_HEAD)) t = t.removePrefix(REMEMBER_HEAD)
        else if (t.startsWith("【记住这题】")) t = t.removePrefix("【记住这题】")
        else return null
        return t.trim().trimStart(':', '：', '-', '—', ' ').trim().trim('*', '`', ' ')
    }

    /**
     * 拆出「记住这题」动作行。返回 (剥掉动作行的正文, 要记的话题)。
     *
     * 话题来自这一行后面跟的内容（「[记住这题] 傅里叶变换」），没跟就用 [rememberTopic] 从答案里兜底抽一条 ——
     * 兜底抽的是「## 考点」那一段的首句，不是让模型自己总结（模型自动抽取的误记率不为零，不做）。
     * 没有动作行就原样返回（面板不出 chip）。
     */
    fun splitRemember(src: String): Pair<String, String?> {
        val lines = src.replace("\r\n", "\n").split('\n')
        val idx = lines.indices.firstOrNull { rememberLine(lines[it]) != null } ?: return src to null
        val topic = rememberLine(lines[idx])!!.ifEmpty { rememberTopic(lines.subList(0, idx).joinToString("\n")) }
        val rest = (lines.subList(0, idx) + lines.subList(idx + 1, lines.size)).joinToString("\n").trim('\n')
        return rest to topic.ifEmpty { null }
    }

    /** 「## 考点」那一段的首句（截到 16 字）；没有这一段就取第一行正文。用来给「记住这题」兜底一个话题 */
    fun rememberTopic(src: String): String {
        val lines = src.replace("\r\n", "\n").split('\n')
        val i = lines.indexOfFirst { POINT_HEAD.matches(it.trim()) }
        val from = if (i >= 0) lines.subList(i + 1, lines.size) else lines
        val s = from.firstOrNull { it.isNotBlank() && !ANY_HEAD.containsMatchIn(it.trim()) } ?: return ""
        return clean(s).replace(Regex("^[0-9]+[.、)）]\\s*"), "").trim().take(MemoryCard.TOPIC_CHARS)
    }

    /** 念的时候用哪种语言：汉字占一成以上就按中文念 */
    fun isMostlyChinese(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        return letters > 0 && s.count { it in '\u4e00'..'\u9fff' } * 10 >= letters
    }
}
