package com.chun.lingzhu

import org.json.JSONArray

/**
 * 回答自由度：用户拿自然语言直接命令它怎么回答 ——「只给答案」「讲细一点」「分步骤」
 * 「用初中生的说法」「英文回我」「别用公式，编成程序」……
 *
 * 做法是**不做白名单**：任何话都收。常见句映射成结构化参数（篇幅档位 / 组织方式 / 语言 / 语气），
 * 拼提示词时写得更明确；映射不了就把原话原样传过去，模型自己理解（见 Prompts 的用户指令覆盖层）。
 *
 * 纯 Kotlin + org.json，不碰 Android，方便单元测试。
 */
object Freeform {

    /** 用户输入最长认这么多字 —— 超了截断（再长对「怎么回答」也没额外信息量，却会白占 token） */
    const val MAX_INPUT = 200
    /** 快捷短语最多几条（面板长按发送键弹出） */
    const val MAX_PHRASES = 5
    /** 单条快捷短语最长多少字 */
    const val PHRASE_MAX = 20

    /** 篇幅档位，和 Prompts.DETAILS 同一套 id */
    val LENGTHS = listOf("brief", "standard", "detailed")
    val FORMAT_LABEL = mapOf(
        "steps" to "分成 1. 2. 3. 步骤，一条一步",
        "table" to "用表格或列表呈现，能列就列，一行一条",
        "list" to "列成要点，一条一行，前面加个短横线",
        "example" to "配一个贴切的例子或类比来说明",
    )
    val TONE_LABEL = mapOf(
        "kid" to "用初中生能听懂的说法：句子短、一句一件事、不堆术语，非用不可的术语第一次出现就在括号里用大白话解释",
        "plain" to "用大白话讲，别堆术语，别用「显然」「易得」「同理」这种词",
    )

    /** 解析结果：question = 抽掉指令词后剩下的真问题（可能为空 = 这句纯是指令）；其余是识别出的结构化参数 */
    data class Parsed(
        val raw: String,
        val question: String,
        val length: String? = null,
        val format: String? = null,
        val lang: String? = null,
        val tone: String? = null,
    ) {
        /** 至少认出一个参数 —— 这句话里有「怎么回答」的指令 */
        val hasDirective: Boolean get() = length != null || format != null || lang != null || tone != null
        /** 整句都是指令，没有问题（「只给答案」「英文回我」） */
        val directiveOnly: Boolean get() = hasDirective && question.isBlank()
        /** 什么都没输入 */
        val empty: Boolean get() = raw.isBlank()
    }

    // ---------------- 词表 ----------------
    // 顺序无关（都扫一遍），但同一格里长词先匹配不短词：「详细一点」和「详细」都在，掩码去重后没问题。

    private val LENGTH_WORDS = mapOf(
        "brief" to listOf(
            "只给答案", "只要答案", "只给结论", "只要结论", "只说答案", "给个答案", "只给结果", "只要结果",
            "别解释", "不要解释", "不用解释", "少说", "简短", "简洁", "精简", "一句话", "别啰嗦", "别废话",
            "不要太长", "别太长", "太长", "越短越好", "别讲太复杂", "别复杂",
            "简单点", "简单一点", "简单些", "说简单点",
            "only the answer", "just the answer", "answer only", "short", "brief", "concise", "tl;dr",
        ),
        "detailed" to listOf(
            "详细", "讲细", "细一点", "详细一点", "更详细", "展开", "展开讲", "展开说说", "多写", "写详细",
            "写清楚", "讲清楚", "推导一下",
            "detailed", "in detail", "more detail", "more details", "elaborate",
        ),
    )

    private val FORMAT_WORDS = mapOf(
        "table" to listOf("表格", "列个表", "做个表", "制表", "table", "tabulation"),
        "list" to listOf("列一下", "列成点", "列点", "要点", "罗列", "列表", "bullet", "bullet points", "list"),
        "steps" to listOf("分步骤", "分步", "一步步", "一步一步", "按步骤", "列步骤", "steps", "step by step"),
        "example" to listOf("举个例子", "举个例", "举例", "例子", "example", "examples"),
    )

    private val TONE_WORDS = mapOf(
        "kid" to listOf("初中生", "小学生", "中学生", "高中生", "小朋友", "小孩", "孩子", "小学生能懂", "给我妈看"),
        "plain" to listOf("大白话", "说人话", "通俗", "别用术语", "不用术语", "少用术语", "别整那些词", "plain language"),
    )

    private val LANG_WORDS = mapOf(
        "zh" to listOf("中文", "汉语", "简体", "chinese", "in chinese", "mandarin"),
        "en" to listOf("英文", "英语", "english", "in english"),
        "ja" to listOf("日文", "日语", "japanese"),
        "ko" to listOf("韩文", "韩语", "korean"),
        "ru" to listOf("俄文", "俄语", "russian"),
        "fr" to listOf("法文", "法语", "french"),
        "de" to listOf("德文", "德语", "german"),
    )

    /** 抽完指令词后，边界上这些字词是客套话 / 语气词，抹掉（只抹两端，不动中间） */
    private val EDGE_NOISE = listOf(
        "请", "麻烦", "帮我", "谢谢", "thanks", "thank you", "please", "pls",
        "然后", "接着", "再", "给我", "我想看", "我想要", "我要", "看一下", "看看",
        "吧", "呗", "呢", "啊", "哦", "嗯", "哈", "呀", "嘛", "了", "一下", "一点", "一些",
    )

    private val PUNCT = " \t　，。！？、；：,.!?;:~～…·「」『』（）()《》\"'“”‘’"

    /**
     * 解析一句用户输入。**任何输入都不报错**：识别不了参数时 question 原样保留、hasDirective = false，
     * 调用方就当普通追问 / 普通闲聊发出去。
     */
    fun parse(text: String?): Parsed {
        val raw = (text ?: "").trim().let { if (it.length > MAX_INPUT) it.take(MAX_INPUT) else it }
        if (raw.isEmpty()) return Parsed("", "")
        val low = raw.lowercase()
        val mask = BooleanArray(raw.length)
        fun eat(words: List<String>) {
            for (w in words) {
                var i = low.indexOf(w)
                while (i >= 0) {
                    for (j in i until i + w.length) mask[j] = true
                    i = low.indexOf(w, i + w.length)
                }
            }
        }
        fun firstOf(table: Map<String, List<String>>): String? {
            for ((k, words) in table) {
                val hit = words.any { low.contains(it) }
                if (hit) { eat(words); return k }
            }
            return null
        }
        val length = firstOf(LENGTH_WORDS)
        val format = firstOf(FORMAT_WORDS)
        val tone = firstOf(TONE_WORDS)
        val lang = firstOf(LANG_WORDS)
        val has = length != null || format != null || tone != null || lang != null
        var q = StringBuilder()
        for (i in raw.indices) if (!mask[i]) q.append(raw[i])
        return Parsed(raw, if (has) clean(q.toString()) else raw.trim(), length, format, lang, tone)
    }

    /** 抹掉两端标点和客套 / 语气词；中间的一律不动（用户自己写的句子不能改） */
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
     * 提示词里的「用户指令」段（覆盖层，放在内部格式约定**之后**）：
     * 识别出参数就写明参数，同时永远带上用户原话 —— 模型对「别整那些花活」这类话的理解比规则表好。
     * 没有参数返回空串（调用方不要拼）。
     */
    fun directive(p: Parsed, followUp: Boolean = false): String {
        if (!p.hasDirective) return ""
        val sb = StringBuilder()
        sb.append("【用户自己指定的要求 —— 排在上面所有格式约定之后，优先级最高】\n")
        sb.append("用户原话：").append(p.raw).append('\n')
        p.length?.let { sb.append("· 篇幅：").append(lengthLabel(it)).append('\n') }
        p.format?.let { sb.append("· 组织方式：").append(FORMAT_LABEL.getValue(it)).append('\n') }
        p.tone?.let { sb.append("· 语气：").append(TONE_LABEL.getValue(it)).append('\n') }
        p.lang?.let { sb.append("· 语言：不管前面要求写什么语言，这一轮一律用").append(Prompts.lang(it).label).append('\n') }
        sb.append("怎么执行：说什么、说多长、怎么排版、什么语气，一律按上面用户的要求来；和前面的内部格式冲突时听用户的。")
        sb.append("只有解析必须的部分照旧：")
        sb.append(
            if (followUp) "LaTeX 公式写法照旧；本轮是追问，不要输出关键词清单行。"
            else "## / ### 标题行、末尾的关键词清单行、LaTeX 公式写法都照旧，格式一个字都不能改。"
        )
        return sb.toString()
    }

    private fun lengthLabel(id: String): String = when (id) {
        "brief" -> "只给最核心的结论，不展开（选择题给选项 + 一句理由就够）"
        "detailed" -> "详细展开，不跳步，中间过程和依据都写出来"
        else -> "中等篇幅，够讲清楚就行"
    }

    // ---------------- 快捷短语（存 Prefs，纯函数在这里做归一化）----------------

    /** 解析出来给面板 / 设置页用：去空、去重、截断，最多 [MAX_PHRASES] 条 */
    fun normPhrases(list: List<String>?): List<String> {
        val out = ArrayList<String>()
        for (raw in list.orEmpty()) {
            val s = raw.trim().let { if (it.length > PHRASE_MAX) it.take(PHRASE_MAX) else it }
            if (s.isEmpty() || s in out) continue
            out.add(s)
            if (out.size >= MAX_PHRASES) break
        }
        return out
    }

    fun parsePhrases(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val list = try {
            val a = JSONArray(json)
            (0 until a.length()).map { a.optString(it) }
        } catch (_: Exception) { emptyList() }
        return normPhrases(list)
    }

    fun toJson(list: List<String>): String = JSONArray().apply {
        for (s in normPhrases(list)) put(s)
    }.toString()
}