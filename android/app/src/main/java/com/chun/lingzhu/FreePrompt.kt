package com.chun.lingzhu

/**
 * 追问输入框的「自由指令」（v1.68 回答自由度）：用户不用切开关，直接用大白话命令它怎么回答 ——
 * 「只给答案」「讲细一点」「分步骤」「列个表」「要点式」「用英文回我」「说中文」……
 *
 * 做法是**半白名单**：长度 / 格式 / 语言三类常见说法映射成结构化参数，拼提示词时写得更明确；
 * 识别不了的部分（整句、或抽掉已知指令后剩下的口语话）**原样透传**给模型自己理解 —— 用户的话
 * 往往还带着我们没枚举到的要求（"别整那些花活"、"讲人话"、"假设我高一"），规则表追不上，模型追得上。
 *
 * 纯 Kotlin + 正则，不碰 Android，可 JVM 单元测试。面板只调 [parse] 和 [funtoPrompt]。
 */
object FreePrompt {

    /** 用户输入最长认这么多字 —— 超了截断。再长对「怎么回答」也没额外信息量，却会白占 token */
    const val MAX_INPUT = 200

    // 参数 id 和 Prompts.DETAILS 对齐，模型看得懂
    const val LENGTH_BRIEF = "brief"
    const val LENGTH_DETAILED = "detailed"
    const val FORMAT_STEPS = "steps"
    const val FORMAT_TABLE = "table"
    const val FORMAT_LIST = "list"
    const val LANG_EN = "en"
    const val LANG_ZH = "zh"

    /**
     * 解析结果。
     * - [question] 抽掉已知指令词后剩下的真问题（可能为空 = 这句整句都是指令）；
     * - [length]/[format]/[lang] 识别出的结构化参数，没有就是 null；
     * - [free] 识别不了、原样透传的那部分（null = 没有需要透传的话）。
     */
    data class Style(
        val raw: String,
        val question: String = "",
        val length: String? = null,
        val format: String? = null,
        val lang: String? = null,
        val free: String? = null,
    ) {
        /** 什么都没输入 */
        val empty: Boolean get() = raw.isBlank()

        /** 这句话里有「怎么回答」的指令 —— 结构化的或透传的都算 */
        val hasDirective: Boolean get() = length != null || format != null || lang != null || free != null

        /** 整句都是指令，没有可追问的问题（「只给答案」「英文回我」） */
        val directiveOnly: Boolean get() = hasDirective && question.isBlank()
    }

    // ---------------- 词表 ----------------
    // 中英混写都收；口语、错别字常见的说法尽量列全。识别不出的一律走 free 透传，不猜。

    private val LENGTH_WORDS = mapOf(
        LENGTH_BRIEF to listOf(
            "只给答案", "只要答案", "只给结论", "只要结论", "只说答案", "只给结果", "只要结果", "给个答案",
            "别解释", "不要解释", "不用解释", "少说点", "少说", "简短", "简洁", "精简", "一句话",
            "别啰嗦", "别废话", "别太长", "不要太长", "太长", "越短越好", "别展开", "不用展开",
            "三句话以内", "简略", "直接说结论", "说结论就行",
            "just the answer", "only the answer", "answer only", "just answer", "short", "brief",
            "concise", "tl;dr", "keep it short", "one sentence", "in one line",
        ),
        LENGTH_DETAILED to listOf(
            "详细", "讲细", "细一点", "详细点", "详细一点", "更详细", "展开", "展开讲", "展开说说",
            "多写", "写详细", "写清楚", "讲清楚", "说清楚", "详细说说", "推导一下", "讲透", "说透",
            "longer", "detailed", "in detail", "more detail", "more details", "elaborate",
            "explain thoroughly", "go into detail",
        ),
    )

    private val FORMAT_WORDS = mapOf(
        FORMAT_STEPS to listOf(
            "分步骤", "分步", "一步步", "一步一步", "按步骤", "列步骤", "编号", "一步步来", "step by step", "steps",
        ),
        FORMAT_TABLE to listOf(
            "列个表", "做个表", "做张表", "表格", "制成表", "用表格", "tabulate", "table", "tabular",
        ),
        FORMAT_LIST to listOf(
            "要点式", "要点形式", "列成要点", "列个要点", "要点", "列一下", "列点", "罗列", "bullet points",
            "bullet list", "as bullets", "in bullet form",
        ),
    )

    private val LANG_WORDS = mapOf(
        LANG_EN to listOf(
            "用英文", "说英文", "讲英文", "英文回我", "英文回答", "用英语", "说英语", "英文说",
            "in english", "in english please", "answer in english", "reply in english", "english please",
        ),
        LANG_ZH to listOf(
            "用中文", "说中文", "讲中文", "中文回我", "中文回答", "用汉语", "说汉语", "中文说",
            "in chinese", "answer in chinese", "reply in chinese", "chinese please", "in mandarin",
        ),
    )

    /** 寒暄 / 客套：这些不是「怎么回答」的指令，别当覆盖层塞进提示词（塞了模型会莫名其妙地照做） */
    private val CHITCHAT = listOf(
        "你好", "您好", "哈喽", "hi", "hello", "hey", "谢谢", "多谢", "thanks", "thank you",
        "辛苦了", "麻烦了", "拜托了", "good", "ok", "okay", "nice", "赞", "厉害", "牛",
    )

    /** 句里有这些 = 这句话同时在问问题，不该当成纯命令 */
    private val QUESTIONISH = listOf(
        "吗", "呢", "?", "？", "为什么", "为啥", "怎么", "如何", "什么", "哪", "谁", "多少", "是不是", "能不能", "可以吗",
        "why", "what", "how", "when", "where", "which", "who", "?",
    )

    /**
     * 抽完指令词后，边界上这些是客套话 / 语气词，抹掉（只抹两端，用户自己写的句子不动）。
     * 刻意不收「一点 / 一些 / 一下」—— 「为什么差一点」「差一点速度」会被削成「为什么差」；
     * 这类残留交给 [fillerOnly] 判（整句都是语气词才算空）。
     */
    private val EDGE_NOISE = listOf(
        "请", "麻烦", "帮我", "谢谢", "thanks", "thank you", "please", "pls",
        "然后", "接着", "再", "给我", "我想看", "我想要", "我要", "看一下", "看看",
        "吧", "呗", "呢", "啊", "哦", "嗯", "哈", "呀", "嘛", "了", "的话",
    )

    private val PUNCT = " \t　，。！？、；：,.!?;:~～…·「」『』（）()《》\"'“”‘’"

    /**
     * 抽掉指令词后剩下的纯语气 / 功能词 —— 不是问题、也不是新指令，直接当空的（「answer in English」抽出「用英文」后剩个 answer）。
     * 这里只认「整句都是这些词」的情况，不做边缘剥离 —— 后者会把「为什么差一点」削成「为什么差」。
     */
    private val FILLER = setOf(
        "answer", "reply", "response", "please", "pls", "ok", "okay", "it", "the", "a", "an",
        "me", "us", "and", "to", "for", "in", "on", "only", "just", "so", "then",
        "回答", "答", "答案", "回复", "答一下", "用", "说", "讲", "来", "一下", "一点", "一些", "就行", "吧",
    )
    private val SPLIT_RE = Regex("[\\s,，。.、;；:：!?！？~～\\-—·「」『』（）()\\[\\]【】]+")

    /** 整句都是语气词 / 功能词（或是空）→ 视作没剩下东西 */
    private fun fillerOnly(s: String): Boolean {
        if (s.isBlank()) return true
        val toks = SPLIT_RE.split(s).filter { it.isNotBlank() }
        return toks.isNotEmpty() && toks.all { it.lowercase() in FILLER }
    }

    /**
     * 解析一句用户输入。**任何输入都不报错**：
     * - 认出长度 / 格式 / 语言 → 填进对应参数，抽掉的部分余下的是真问题；
     * - 剩下的不是问题（是口语指令）→ 原样进 [Style.free] 透传；
     * - 一个参数都没认出来 → 是问句就当普通追问，是命令就整句进 [Style.free]。
     *
     * 超长输入按 [MAX_INPUT] 截断。
     */
    fun parse(input: String?): Style {
        val raw = (input ?: "").trim().let { if (it.length > MAX_INPUT) it.take(MAX_INPUT).trim() else it }
        if (raw.isEmpty()) return Style("")
        val low = raw.lowercase()
        val mask = BooleanArray(raw.length)

        /** 指令词后面常跟的程度副词，一起吞掉 —— 否则「简短一点」抽完只剩「一点」粘在问题上 */
        val DEGREE = listOf("一点儿", "一点", "一些", "点儿", "点", "下")

        fun eat(words: List<String>) {
            for (w in words) {
                var i = low.indexOf(w)
                while (i >= 0) {
                    val end = i + w.length
                    for (j in i until end) mask[j] = true
                    for (d in DEGREE) {
                        if (low.startsWith(d, end)) {
                            for (j in end until end + d.length) mask[j] = true
                            break
                        }
                    }
                    i = low.indexOf(w, end)
                }
            }
        }

        // 每类只取第一个命中的档位；命中就把该档所有说法抹掉，免得「详细一点」里的「一点」留在问题里
        fun firstOf(table: Map<String, List<String>>): String? {
            for ((k, words) in table) {
                if (words.any { low.contains(it) }) { eat(words); return k }
            }
            return null
        }

        val length = firstOf(LENGTH_WORDS)
        val format = firstOf(FORMAT_WORDS)
        val lang = firstOf(LANG_WORDS)
        val recognized = length != null || format != null || lang != null

        val sb = StringBuilder()
        for (i in raw.indices) if (!mask[i]) sb.append(raw[i])
        val residue = clean(sb.toString())
        val residual = if (fillerOnly(residue)) "" else residue
        val askable = QUESTIONISH.any { low.contains(it) }

        if (!recognized) {
            // 一个都没认出来。分两种：带问号的 → 就是个普通问题，老老实实当追问（绝大多数输入走这里）；
            // 不带问号的 → 是命令而不是问题（「别整那些花活」「讲人话」），整句原样当自由指令透传。
            // 注意别把普通问句也塞进覆盖层 —— 那会让模型把问题本身当成排版要求，照着执行。
            if (askable || CHITCHAT.any { low == it }) return Style(raw, question = raw)
            return Style(raw, question = "", free = raw)
        }

        // 认出来了一些
        if (residual.isEmpty()) return Style(raw, question = "", length = length, format = format, lang = lang)
        if (askable) return Style(raw, question = residual, length = length, format = format, lang = lang)
        // 剩下的是没枚举到的要求（「通俗点」「假设我高一」）→ 当自由指令透传，别硬塞进问题里
        return Style(raw, question = "", length = length, format = format, lang = lang, free = residual)
    }

    /** 抹掉两端标点和客套 / 语气词；中间一律不动 */
    private fun clean(s: String): String {
        var t = s
        var again = true
        while (again) {
            again = false
            val before = t
            t = t.trim { c -> PUNCT.indexOf(c) >= 0 }
            for (n in EDGE_NOISE) {
                if (t.startsWith(n) && t.length > n.length) { t = t.substring(n.length); again = true }
                if (t.endsWith(n) && t.length > n.length) { t = t.substring(0, t.length - n.length); again = true }
            }
            if (t != before) again = true
        }
        return t.trim()
    }

    /**
     * 拼成「用户风格段」—— 追加到追问请求的**最后**，也就是内部格式规则之后。
     *
     * 放最后的理由：它是覆盖层，得压过系统提示词里那些格式约定；又不能放在最前面（离正文太远，会被冲淡）。
     * 识别出参数就写明参数，同时永远带上用户原话 —— 模型对「别整那些花活」这类话的理解比规则表好。
     *
     * 一句都没有指令时返回空串（调用方别拼）。
     */
    fun funtoPrompt(style: Style): String {
        if (!style.hasDirective) return ""
        val sb = StringBuilder()
        sb.append("【用户指定的回答方式 —— 排在上面所有格式约定之后，优先级最高】\n")
        sb.append("用户原话：").append(style.raw).append('\n')
        style.length?.let { sb.append("· 篇幅：").append(lengthLabel(it)).append('\n') }
        style.format?.let { sb.append("· 排版：").append(formatLabel(it)).append('\n') }
        style.lang?.let { sb.append("· 语言：不管前面要求写什么语言，这一轮一律用").append(langLabel(it)).append('\n') }
        style.free?.let { sb.append("· 用户另外交代的：").append(it).append('\n') }
        sb.append("怎么执行：说多长、怎么排版、说什么语言、什么语气，一律按上面用户的要求来；和前面的内部格式约定冲突时听用户的。\n")
        sb.append("正文按用户指令写，只保留解析必须用到的标记行：追问这轮不要输出关键词清单行；LaTeX 公式写法照旧；")
        sb.append("确实需要分节时标题行照旧。")
        return sb.toString()
    }

    private fun lengthLabel(id: String): String = when (id) {
        LENGTH_BRIEF -> "只给最核心的结论，不展开（选择题给选项 + 一句理由就够）"
        LENGTH_DETAILED -> "详细展开，不跳步，中间过程和依据都写出来"
        else -> "中等篇幅，够讲清楚就行"
    }

    private fun formatLabel(id: String): String = when (id) {
        FORMAT_STEPS -> "分成 1. 2. 3. 编号分步，一条一步"
        FORMAT_TABLE -> "能用表格就用表格，一行一条"
        FORMAT_LIST -> "列成要点，一条一行，前面加个短横线"
        else -> "排版清楚，一件事一行"
    }

    private fun langLabel(id: String): String = when (id) {
        LANG_EN -> "英文"
        LANG_ZH -> "简体中文"
        else -> id
    }
}