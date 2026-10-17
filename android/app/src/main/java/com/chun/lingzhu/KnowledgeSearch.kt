package com.chun.lingzhu

import kotlin.math.ln

/**
 * v1.70 K5 本地知识库检索：拿用户自己存的 [KnowledgeCard] 当上下文喂给模型（BM25 关键词检索）。
 *
 * 分工照 [WebSearch] 的老规矩：
 *  - 这里全是纯函数（分词 / 打分 / 排序 / 上下文块拼装 / 触发判定），纯 JVM，单元测试直接测；
 *  - 数据从哪来只有一扇门：[source]。Android 那层（[AnswerPanel] 持有 Context）注入一次，
 *    [Solver] 这条纯链路上就不用碰 Context —— 同 [WebSearch] 用 `@Volatile` 运行时字段的做法。
 *
 * 三个取舍写在下面，改的时候别丢：
 *  - **不上向量、不做 embedding**：零依赖零出网，手机上跑一次几千张卡的 BM25 是毫秒级，
 *    而 embedding 要么塞模型（首启多几十 MB）要么发出去（隐私）。关键词召回对「我学过的那道题」这种问法够用。
 *  - **BM25 的 IDF 需要语料**：单条 [bm25Score]（只有一个 doc）拿不到 df，给的是平铺 IDF 的
 *    单文档打分，只用来对照和自测；真排序走 [scoreAll] 的语料版 IDF（df 在这批卡里现算）。
 *  - **命中不了就不注入**：没命中返回空串，一个 token 都不多花（见 [blockFor]）。
 */
object KnowledgeSearch {

    /** 一次最多取几张卡 */
    const val TOP_K = 3
    /** 上下文块总长上限（超出截断加省略号，所以实际 ≤ CONTEXT_MAX + 1）—— 知识卡是补充，不该挤掉模型的思考预算 */
    const val CONTEXT_MAX = 1200
    /** 单卡正面留多少字 */
    const val FRONT_CLIP = 80
    /** 单卡背面留多少字（背面最长 500 字，全塞进去三张就爆了） */
    const val BACK_CLIP = 200

    /** BM25 参数：标准取值 */
    const val K1 = 1.5
    const val B = 0.75

    /** 平铺 IDF（单文档打分用；真实语料 IDF 见 [scoreAll]） */
    const val IDF_FLAT = 0.6931471805599453   // ln(2)
    /** 长度归一化参照长度（张）：中文 2-gram 化后一张普通卡约 100~200 个 token，取中值 */
    const val REF_DL = 120.0

    const val HEAD = "【知识库 · 命中的卡片】"

    /** 设置页「知识卡库」那行说明（没有开关，靠问法触发） */
    const val HINT = "问问题时带上「我的卡片」即可引用知识库"

    /** 提示词里对知识卡的用法要求（同 [WebSearch.SOURCE_RULE] 的位置：离正文最近） */
    const val USE_RULE = "这些是用户自己存下来的卡片，可能已经过时，也可能和画面不一致 —— 以画面为准，卡片只用来补充。\n" +
        "结合知识卡内容回答；用到哪张就在那句话后面写「（来自知识卡）」。卡片里没有的，照你自己知道的答，别硬套。"

    // ---------------- 数据源（Android 那层注入） ----------------

    /**
     * 知识卡列表的取法。同 [WebSearch] 的运行时字段：请求路径上只认这里，不回头碰 Context / 文件。
     * 没注入（单测、库里 new 出来直接调）→ 取不到卡 → 不注入上下文，行为跟 K5 之前完全一样。
     */
    @Volatile var source: (() -> List<KnowledgeCard.KCData>)? = null

    fun cards(): List<KnowledgeCard.KCData> = try { source?.invoke() ?: emptyList() } catch (_: Exception) { emptyList() }

    /**
     * 这次输入要不要检索 + 要注入的那一整块（[Solver] 追问链路上的唯一入口）。
     * 三道条件缺一不可：问法像在引用知识库（[shouldSearch]）、取得到卡、有命中。没命中返回空串 → 调用方原样发请求。
     */
    fun blockFor(query: String): String {
        if (!shouldSearch(query)) return ""
        return contextBlock(search(cards(), stripTrigger(query)))
    }

    // ---------------- 分词 ----------------

    /** 汉字（含扩展 A 区）算中文，其余 ASCII 字母数字算英文/型号 */
    private val CJK = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
    /** 一切非「中文、非字母数字」都是分隔符（标点、空格、换行、markdown 符号） */
    private val SPLIT = Regex("[^0-9a-z\\u4e00-\\u9fff\\u3400-\\u4dbf]+")

    /** 单字停用词（中文） */
    private val STOP_ZH = setOf(
        "的", "了", "吗", "呢", "啊", "吧", "是", "在", "和", "与", "就", "都", "也", "很", "不", "有", "个",
        "上", "下", "中", "要", "会", "说", "把", "被", "给", "让", "对", "从", "到", "着", "过", "地", "得", "我", "你",
    )
    /**
     * 英文停用词（小写）。刻意只收虚词 —— 「how / what / do」这类疑问词留在词表里，
     * 「how do you spell」这种问法本身就是知识卡正面上的原话，砍掉就永远查不到那张卡。
     */
    private val STOP_EN = setOf(
        "the", "a", "an", "is", "are", "was", "were", "of", "to", "in", "on", "and", "or", "it", "this", "that",
        "for", "with", "as", "at", "be",
    )
    private val STOP = STOP_ZH + STOP_EN

    /** 2-gram 整体是停用词，或两个字都是单字停用词（「的了吗」这种全是虚词）→ 丢 */
    private fun isStopGram(g: String): Boolean =
        g in STOP || (g.length == 2 && g[0].toString() in STOP_ZH && g[1].toString() in STOP_ZH)

    /**
     * 分词：中文按 2-gram（单字成词时留那个单字），英文 / 型号按词切（`R7250`、`Wi-Fi` 这类照原样留，只压小写），
     * 停用词丢掉。返回的词表直接喂 [bm25Score] / [scoreAll]。
     */
    fun tokenize(q: String): List<String> {
        if (q.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (raw in SPLIT.split(q.lowercase())) {
            if (raw.isEmpty()) continue
            if (CJK.containsMatchIn(raw)) {
                if (raw.length == 1) { if (raw !in STOP) out.add(raw) }
                else for (i in 0 until raw.length - 1) {
                    val g = raw.substring(i, i + 2)
                    if (!isStopGram(g)) out.add(g)
                }
            } else if (raw.length >= 2 || raw[0].isDigit()) {
                if (raw !in STOP) out.add(raw)
            }
        }
        return out
    }

    // ---------------- BM25 ----------------

    /** 一张卡的检索文本 = 正面 + 背面（正面是问题、背面是答案，两头都该能被查到） */
    fun docText(c: KnowledgeCard.KCData): String = c.front + "\n" + c.back

    /**
     * 单文档 BM25（自测 / 对照用）：标准公式，IDF 取平铺的 [IDF_FLAT]、长度按 [REF_DL] 归一。
     * 只有一条 doc 时语料 IDF 无从算起，这样给的分数只反映「词频 + 长度归一」，够验证单调性。
     * 真排序用 [scoreAll]（df 在这批卡里现算）。
     */
    fun bm25Score(query: List<String>, doc: String): Double {
        if (query.isEmpty()) return 0.0
        return scoreTerms(query, tokenize(doc), REF_DL) { IDF_FLAT }
    }

    /** BM25 主公式：Σ idf(q) · tf·(k1+1) / (tf + k1·(1-b+b·dl/avgdl)) */
    private fun scoreTerms(query: List<String>, terms: List<String>, avgdl: Double, idf: (String) -> Double): Double {
        if (query.isEmpty() || terms.isEmpty()) return 0.0
        val tf = HashMap<String, Int>()
        for (t in terms) tf[t] = (tf[t] ?: 0) + 1
        val dl = terms.size.toDouble()
        val avg = if (avgdl > 0) avgdl else dl
        var s = 0.0
        for (q in query.distinct()) {
            val f = tf[q] ?: continue
            s += idf(q) * (f * (K1 + 1.0)) / (f + K1 * (1.0 - B + B * dl / avg))
        }
        return s
    }

    /**
     * 全库打分（语料版 IDF）：df 在这批卡里现算，avgdl 是这批卡的平均长度。
     * 零分（一个查询词都没命中）的卡不返回 —— 「勉强沾边」的知识卡比不给更坏，模型会顺着编。
     */
    fun scoreAll(cards: List<KnowledgeCard.KCData>, query: String): List<Pair<KnowledgeCard.KCData, Double>> {
        val q = tokenize(query)
        if (q.isEmpty() || cards.isEmpty()) return emptyList()
        val docs = cards.map { tokenize(docText(it)) }
        val n = docs.size
        val df = HashMap<String, Int>()
        // df 数的是**文档数**，不是出现次数：一张卡里「导数」出现 5 次也只算 1 篇。
        // 按次数累加会让 df 超过 N，IDF 变负数，把最相关的那张卡判成不相关。
        for (d in docs) for (t in d.toHashSet()) df[t] = (df[t] ?: 0) + 1
        val avgdl = docs.sumOf { it.size }.toDouble() / n
        val idf = { t: String -> val d = df[t] ?: 0; ln(1.0 + (n - d + 0.5) / (d + 0.5)) }
        return cards.indices.mapNotNull { i ->
            val s = scoreTerms(q, docs[i], avgdl, idf)
            if (s > 0.0) cards[i] to s else null
        }.sortedWith(compareByDescending<Pair<KnowledgeCard.KCData, Double>> { it.second }.thenBy { it.first.id })
    }

    /** 全库打分取前 [topK] 张（分数从高到低；零分不返回） */
    fun search(cards: List<KnowledgeCard.KCData>, query: String, topK: Int = TOP_K): List<KnowledgeCard.KCData> {
        if (topK <= 0) return emptyList()
        return scoreAll(cards, query).take(topK).map { it.first }
    }

    // ---------------- 上下文块 ----------------

    /** 压到 [max] 字以内，超了截断加省略号（总长 ≤ max + 1） */
    fun clip(s: String, max: Int): String {
        if (max <= 0) return ""
        if (s.length <= max) return s
        return s.take(max).trimEnd() + "…"
    }

    /**
     * 拼提示词里的知识卡块（风格照 [WebSearch.contextBlock]）：标题 + 编号条目 + 末尾的引用规矩。
     * 没命中返回空串 —— 不拼空标题进提示词。没命中就是没命中，不写「没找到相关卡片」那种话（模型会照着编一张）。
     */
    fun contextBlock(hits: List<KnowledgeCard.KCData>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(HEAD).append("（用户知识库里检索到的）：\n")
        sb.append("以下是用户知识库里相关的卡片：\n")
        hits.forEachIndexed { i, c ->
            sb.append(i + 1).append(". 【").append(c.deck.ifBlank { "默认" }).append("】")
                .append(clip(c.front.trim(), FRONT_CLIP)).append('\n')
            val back = clip(c.back.trim(), BACK_CLIP)
            if (back.isNotEmpty()) sb.append("   背面：").append(back).append('\n')
        }
        sb.append('\n').append(USE_RULE)
        return clip(sb.toString(), CONTEXT_MAX)
    }

    /**
     * 把知识卡块插进系统提示词，位置在 [Prompts.TERMS_RULE] **之前**（和 [WebSearch.inject] 同一个道理：
     * 约束离正文最近，不被后面的格式约定冲淡）。提示词里没有 TERMS_RULE（关键词识别关着）就追加到末尾。
     * 空块原样返回 —— 无命中不多发一个字节。
     */
    fun inject(prompt: String, block: String): String {
        if (block.isEmpty()) return prompt
        val i = prompt.indexOf(Prompts.TERMS_RULE)
        return if (i < 0) prompt + "\n\n" + block
        else prompt.substring(0, i).trimEnd() + "\n\n" + block + "\n\n" + prompt.substring(i)
    }

    // ---------------- 什么时候检索 ----------------

    /** 斜杠命令：`/k` 开头（后面跟的就是要找的东西，带冒号逗号都认） */
    private val SLASH_K = Regex("^\\s*/k\\s*[：:，,、]?\\s*", RegexOption.IGNORE_CASE)

    /**
     * 引用知识库的意图词。中文这批是用户真会打出来的说法（「我的卡片」是入口那句话里的原话）；
     * 刻意不含「卡片」这种泛词 —— 「这道题卡片怎么算」不是在问知识库。
     */
    private val INTENT_ZH = listOf(
        "我的卡片", "我的知识卡", "我的卡", "我学过", "我存的", "我收藏", "我记的", "我的笔记", "我的记录",
        "知识库", "知识卡", "卡片里", "卡片中", "之前存的", "之前保存", "之前记的", "以前存的", "存过的",
    )
    private val INTENT_EN = listOf("my card", "my cards", "my knowledge", "knowledge base", "knowledge card", "saved card")

    /** 这次输入要不要检索知识库：斜杠命令永远算；否则命中意图词就算 */
    fun shouldSearch(query: String): Boolean {
        val t = query.trim()
        if (t.isEmpty()) return false
        if (SLASH_K.containsMatchIn(t)) return true
        val low = t.lowercase()
        if (INTENT_EN.any { low.contains(it) }) return true
        return INTENT_ZH.any { t.contains(it) }
    }

    /** 斜杠命令剥掉 —— 剩下的话才是拿去检索 / 给模型看的正文（模型没被告知过 /k 是什么） */
    fun stripTrigger(input: String): String = SLASH_K.replace(input.trim(), "").trim()
}