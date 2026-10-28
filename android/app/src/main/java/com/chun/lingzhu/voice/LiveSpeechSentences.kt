package com.chun.lingzhu.voice

/**
 * 把大模型**流式**吐出来的文字切成「能立刻读出去的句子」。
 *
 * 为什么需要这一层：语音对话要的是「第一句一成型就开始合成」，而不是等整段回答完。
 * 但边收边切有两个坑，这里一次写清：
 *  1. **不能在半个词上切**：英文句点既可能是句号也可能是小数点 / 缩写；中文的「。」才是硬边界。
 *     所以索引切分只看一批**确定的**边界符号，其余一律等下一段。
 *  2. **不能读出一堆符号**：markdown 的 `**`、列表 `- `、反引号、括号里的链接，合成出来要么是
 *     「星号星号」要么是一串停顿。这一层顺手把它们抹成空白，但**不改原文**（上下文仍用原文）。
 *
 * 纯 Kotlin、不碰 android.*、不联网：单测直接喂字符串。
 */
class LiveSpeechSentences {

    private val buf = StringBuilder()

    /** 一次最多攒多少字还没遇到边界就强制在逗号处切一刀（超过就没人愿意等第一句了）。 */
    private val softLimit = SOFT_LIMIT

    /**
     * 喂一段增量，返回**这次已经能读的完整句子**（0 条或多条，已经去掉首尾空白与纯符号句）。
     * 传入内容为 null / 空串时什么都不做。
     */
    fun feed(delta: String?): List<String> {
        if (delta.isNullOrEmpty()) return emptyList()
        buf.append(delta)
        val out = ArrayList<String>(2)
        while (true) {
            val cut = nextCut() ?: break
            val piece = buf.substring(0, cut)
            buf.delete(0, cut)
            speakable(piece)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 收尾：把剩下的半句也读出来（流结束 / 被截断时用）。
     * 读不出内容就返回空表 —— 绝不对空串发一次合成请求。
     */
    fun flush(): String? {
        val rest = buf.toString()
        buf.setLength(0)
        return speakable(rest)
    }

    /** 已经攒着、还没读出去的原文（界面上显示「正在生成」时用得到；不改内部状态）。 */
    fun pending(): String = buf.toString()

    fun reset() = buf.setLength(0)

    /**
     * 找下一个硬边界的**切点**（切点 = 不含该标点的下标）。没有就 null。
     *
     * 硬边界：。！？；… 换行，以及**后面紧跟空白 / 结尾 / 引号**的 `.` `!` `?` `;`。
     * 英文点号加上「后面不是数字」的判断：`3.5` 这种小数不能被切开。
     */
    private fun nextCut(): Int? {
        val s = buf.length
        for (i in 0 until s) {
            val c = buf[i]
            when (c) {
                '。', '！', '？', '；', '\n', '…' -> return i + 1
                '!', '?', ';' -> return i + 1
                '.' -> {
                    val next = if (i + 1 < s) buf[i + 1] else ' '
                    val prevDigit = i > 0 && buf[i - 1] in '0'..'9'
                    val nextDigit = next in '0'..'9'
                    if (!(prevDigit && nextDigit)) return i + 1
                }
            }
        }
        // 没有硬边界：超过软上限就退而求其次，在最靠后的逗号处切（中文 ，、英文 ,）
        if (s < softLimit) return null
        for (i in s - 1 downTo 1) {
            if (buf[i] == '，' || buf[i] == ',') return i + 1
        }
        return null
    }

    companion object {
        /**
         * 没遇到句号时最多攒这么多字就先读一截。
         * 取 32：中文一句话常常三四十字，32 正好是「一口气能说完」的长度；
         * 再长用户就开始等，再短会把一句话拆成两半读。
         */
        const val SOFT_LIMIT = 32

        /** 一句里至少要有一个「真的能读出来的字符」：汉字 / 字母 / 数字。 */
        private fun hasSpeakable(s: String): Boolean =
            s.any { it.isLetterOrDigit() || (it in '\u4E00'..'\u9FFF') }

        /**
         * 原文 → 能直接交给合成的文字。
         *
         * 抹掉的东西：markdown 记号（`*` `_` `#` `` ` `` `>` `~`）、列表前缀、网址、以及括号里的
         * 纯标记（`[文字](链接)` 保留文字）。**不做同义词替换、不改语气** —— 那是模型的活。
         */
        fun speakable(raw: String): String? {
            if (raw.isBlank()) return null
            var s = raw
            // 行内代码与加粗的记号：留着字，去掉符号（不然会被读成「星号」）
            s = s.replace("```", " ").replace("`", " ")
            s = s.replace("**", "").replace("__", "")
            // 标题 / 引用 / 列表记号在行首时去掉
            s = s.replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]\\s|\\d+[.)]\\s)"), "")
            // 链接：保留可见文字，丢掉地址（念一串 URL 没有意义）
            s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("https?://\\S+"), " ")
            // 表情 / 特殊符号里的「不可读」部分交给 hasSpeakable 判定，这里只压空白
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isEmpty() || !hasSpeakable(s)) return null
            return s
        }

        /** 归一化：只留汉字 / 字母 / 数字，全小写 —— 给「回声 / 重复」判断用（纯函数，单测直接对）。 */
        fun normalize(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                if (c.isLetterOrDigit() || c in '\u4E00'..'\u9FFF') sb.append(c.lowercaseChar())
            }
            return sb.toString()
        }
    }
}

/**
 * 「这句话算不算用户在插话」的判断。**纯函数**，不碰音频、不碰网络。
 *
 * 为什么需要判断而不是「听到 partial 就掐断」：我们自己在放声音，麦克风一定会收到一部分，
 * 回声消除在真机上效果不等（见界面上的说明）。如果一有 partial 就掐，等于是「自己把自己打断」，
 * 对话永远说不出第二句。这里的做法是：
 *  - **太短的 partial 不算**（一两个字常常是识别器在猜，或者是我们自己那句话的尾巴）；
 *  - 正在出声时，partial 如果只是**我们正在念的内容的一部分**（归一化后是它的子串），判为回声，不算插话；
 *  - 其余情况算插话。
 *
 * 这是启发式，不是声学回声消除：AEC 生效时回声根本不会被识别出来，这条判断只是「AEC 没完全生效时的兜底」。
 * 所以界面文案不能说「保证不会自己打断自己」。
 */
object BargeInPolicy {

    /** partial 至少要有这么多「可读字符」才算一次真的插话（2 = 一个汉字 + 一个字，或两个字母）。 */
    const val MIN_CHARS = 2

    fun meaningful(partial: String): Boolean =
        LiveSpeechSentences.normalize(partial).length >= MIN_CHARS

    /**
     * partial 与我们**正在念 / 已经念过**的内容重合吗。
     * [spoken] 为空（还没出声）时谈不上回声，返回 false。
     */
    fun echoOf(partial: String, spoken: String): Boolean {
        val p = LiveSpeechSentences.normalize(partial)
        val s = LiveSpeechSentences.normalize(spoken)
        if (p.isEmpty() || s.isEmpty()) return false
        if (p.length > s.length) return false
        return s.contains(p)
    }

    /**
     * 正在生成 / 正在说话时，这一条 partial 要不要打断。
     *
     * [spoken] = 这一轮回答里**已经送去合成或正在合成**的原文（可以是整轮已生成的文字）。
     * [grace] = 刚提交完上一句用户语音后的宽限期（毫秒判断在上层，这里只收布尔，方便单测）。
     */
    fun shouldInterrupt(partial: String, spoken: String, grace: Boolean = false): Boolean {
        if (grace) return false
        if (!meaningful(partial)) return false
        return !echoOf(partial, spoken)
    }
}
