package com.chun.lingzhu

import java.text.Normalizer
import java.util.Locale

/**
 * v2.3.1 第 10 条「知识图谱」：把知识卡库变成一张**能解释**的关联图。
 *
 * ## 为什么边的设计比画图重要得多
 * 「连成图」这个动作本身没有价值 —— 按牌组把卡片连起来，任何一堆卡都能连成一团毛线：
 * 一个导入的 PDF 常常只建一个牌组，几百张卡互相相连，画出来是一个黑球，学生看不出任何结构。
 * 所以这里的边只认三种**有证据**的关系，并且各自带一个权重和一句能念给人听的理由：
 *
 *  - **同来源**（[W_FILE]）：两张卡的 origin 指向同一个文件。这是最强的信号 ——
 *    「这两张卡出自同一份资料」，是一条事实，不是联想。但它不设限就会自毁：
 *    一份 200 张卡的资料在内部能连出 2 万条边，图又回到毛线团。
 *    所以同来源的边在一次建图里总共只用 [FILE_EDGE_BUDGET] 条 —— 预算怎么分见 [build] 里 3.3 那段。
 *  - **共享关键词**（每词 [W_KEY]）：卡面（问题 + 答案）里出现的实义关键词，按词频去重、去停用词、
 *    去口语字的虚词组合（「什么」「怎么」）。权重随共享词数增长并封顶，不封顶的话
 *    「都提到了函数」这种泛词会压过一条真实的关系。
 *  - **同牌组**（[W_DECK]）：最弱的信号，**只在共享关键词之后按剩余名额补位**。
 *    牌组本质上是「一次导入的批次」，不是知识分类；把它当主动脉画，就成了前面说的毛线团。
 *
 * 边的分数 = 三路权重之和，低于 [DEFAULT_THRESHOLD] 直接不画。
 * 于是「同一副牌组但毫无交集的卡」之间没有边，图也就分得出几个主题。
 *
 * ## 节点是知识点，不是卡片
 * 同一张卡的正反面是同一个知识点；问题归一化后相同的卡视为同一个知识点（见 [topicKey]）。
 * 理由是导入时同一段原文常被切成「问 A」和「追问 A 的细节」两张卡，它们本来就是一件事，
 * 画成两个孤立的点会让图看起来比实际更散。合并后节点标签取最短的那个问题（短才读得清），
 * 点开能看到它名下的全部卡片。
 *
 * ## 纯函数
 * 整个文件不碰 android：停用词、分词、建边、阈值、布局、聚类、统计全是纯计算，
 * 由 [KnowledgeGraphTest] 在 JVM 上直接跑。界面那层在 [GraphActivity]。
 */
object KnowledgeGraph {

    // ---------------- 权重与阈值 ----------------

    /**
     * 同来源（同一个文件名）的权重。
     * 定得比两个共享关键词还高：出自同一份资料是**事实**，「都提到了某两个词」是**推测**。
     */
    const val W_FILE = 3.0

    /**
     * 共享关键词的**满档**权重：共享 [KEY_CAP] 个（及以上）拿满分，1 个只拿 1/KEY_CAP。
     * 之所以不按个数累加：卡面有 8 个词，两张卡随手共享两个就能拿双倍分，
     * 于是「只是都提到过某两个泛词」的关系被抬成强关联，图立刻糊成一团。
     */
    const val W_KEY = 3.0

    /** 同一副牌组加的分。默认真不靠它成边（1.0 < 3.0），它只在已有共同词时加权 */
    const val W_DECK = 1.0

    /**
     * 画边的最小分数（默认阈值）。
     * 3.0 = 至少共享一个关键词；1.0 / 3.0 / 4.0 这些数都是**刻意选在这个位置**的：
     * 把阈值调到 1.0 等于「同牌组就画」，也就是毛线团。
     */
    const val DEFAULT_THRESHOLD = 3.0

    /** 两卡共享关键词最多算几个。封顶是为了让泛词多的卡（「函数」「公式」满屏）不压过真正紧密的一对 */
    const val KEY_CAP = 3

    /** 共享多少个关键词才算「同一话题」：中文卡片共享 1 个词多是泛词噪声，2 个起才当证据。 */
    const val MIN_SHARED = 2

    /** 「人人都有」至少要在这么多张卡里出现，避免小语料把主术语也删掉。 */
    const val MIN_UBIQUITY_COUNT = 4

    /** 少于此数量的语料不做普及度统计：样本太小，统计不出「普遍」。 */
    const val MIN_UBIQUITY_CORPUS = 6

    /**
     * 语料级停用阈：一个片段在超过这个比例的卡片里出现，就算「人人都有」，建图前丢掉。
     * 0.7 是实测取的：一份资料里真正的术语通常只覆盖一部分卡；而分词残片（「个概念」「的了」）
     * 会覆盖几乎所有卡。阈值太低会误删通用术语，太高又会漏掉残片。
     */
    const val KEY_UBIQUITY = 0.7


    /** 建图时每张卡最多取几个关键词。太多等于把卡面整段拿来比，噪声比信号大 */
    const val KEY_PER_CARD = 8

    /** 同来源的边在一次建图里最多用多少条。见文件头：不限额就等于把大量卡连成毛线团 */
    const val FILE_EDGE_BUDGET = 40

    /**
     * 观察用的参考线：一个连通分量超过这么多节点，就说明「这份资料可能被连成了一整坨」，
     * 页面上会点名提示。它不参与建边规则（规则只有阈值那一条），只用来把这个现象说出来。
     */
    const val BIG_CLUSTER = 30

    /** 默认最多画几个节点。再多屏幕上就是一团点了：宁可只画最强的那些，也不假装能看全 */
    const val MAX_NODES = 120

    /** 布局的最小边距（px 由页面按密度换算进来）。标签画在圆点下方，所以留的是「圆点 + 一行字」的位置 */
    const val PAD = 40f

    /**
     * 中文停用词 + 提问/印刷体里没有信息量的词。
     * 表里既有整词也有单字：中文没有空格，双字词的分词结果里会有「的」「是」这类残渣，
     * 不挡掉的话它们会跟所有卡共享，等于给全图加了一层底噪。
     */
    val STOP = setOf(
        "什么是", "什么", "怎么", "怎样", "如何", "为什么", "哪些", "哪个", "哪个的", "有何", "是否",
        "一下", "一个", "一种", "一些", "这个", "那个", "这些", "那些", "这样", "那样", "这里", "那里",
        "可以", "能够", "需要", "应该", "可能", "进行", "通过", "由于", "因此", "所以", "因为", "但是",
        "而且", "并且", "或者", "如果", "虽然", "然后", "其中", "以及", "对于", "关于", "根据", "按照",
        "我们", "你们", "他们", "它们", "自己", "大家", "别人", "东西", "时候", "地方", "问题", "内容",
        "主要", "一般", "通常", "简单", "具体", "相关", "有关", "方面", "情况", "意义", "作用", "特点",
        "区别", "比较", "区别是", "意思是", "指的是", "所谓", "例如", "比如", "包括", "等等", "另外",
        "的是", "是指", "就是", "不是", "没有", "还有", "然后", "首先", "其次", "最后", "总之",
        "下面", "上面", "如下", "以上", "以下", "用来", "用于", "表示", "称为", "叫做", "属于",
        "之", "的", "地", "得", "了", "着", "过", "是", "在", "和", "与", "或", "及", "等", "被", "把", "对",
        "为", "以", "于", "而", "则", "也", "都", "还", "就", "才", "又", "很", "更", "最", "不", "没",
        "有", "无", "个", "种", "些", "这", "那", "哪", "谁", "它", "他", "她", "我", "你", "们",
        "吗", "呢", "吧", "啊", "呀", "哦", "嗯", "其", "该", "此", "所", "之", "若", "且", "并",
    )

    /**
     * 两字组合里「首字是功能字」的全是虚词粘连（「的是」「和是」「在哪」），
     * 判据放在这里而不是塞进上面的表：中文虚词是封闭类，用首字挡足够，也不必列几千条。
     */
    private const val VIRTUAL_HEAD = "的了着过和与或在是为以于而则也都还就才又很更最不没这那哪它他她我你们吗呢吧啊其该此所若且并"

    // ---------------- 数据结构 ----------------

    /** 一个知识点在图上的位置。weight = 它的所有边权重之和（度数只是一个粗估，带权才看得出「中心」） */
    data class Node(
        val id: String,
        val label: String,
        val deck: String,
        val degree: Int,
        val weight: Double,
        /** 这个知识点名下的全部卡片 id（合并了同一件事的多张卡） */
        val cardIds: List<Long> = emptyList(),
    )

    /** 一条边。[reason] 是给评委听的一句话，界面上原样显示 */
    data class Edge(val a: String, val b: String, val weight: Double, val reason: String) {
        /** 无向边的唯一键（a/b 顺序无关），合并多路信号时用 */
        val key: String get() = if (a <= b) "$a\u0000$b" else "$b\u0000$a"
    }

    data class Graph(val nodes: List<Node>, val edges: List<Edge>) {
        val nodeById: Map<String, Node> = nodes.associateBy { it.id }
        fun isEmpty(): Boolean = nodes.isEmpty()
    }

    /** 平面上的一个点（不引 android.graphics.PointF：这个文件要能在纯 JVM 单测里跑） */
    data class Pt(val x: Float, val y: Float)

    /** 布局结果：位置 + 实际用到的画布尺寸（等比缩到画布里，不留半张图在屏幕外） */
    data class Layout(val pos: Map<String, Pt>, val width: Float, val height: Float)

    /** 一个连通分量（主题） */
    data class Cluster(val nodes: List<String>, val weight: Double) {
        val size: Int get() = nodes.size
    }

    /** 全图统计，给页面顶部那几行字用。参数里的数都是**算出来的**，不是估的 */
    data class Stats(
        val nodes: Int,
        val edges: Int,
        /** 平均每点几条边。> 3 基本就是毛线团了，页面上要如实显示 */
        val avgDegree: Double,
        /** 被合并掉的卡片数（同知识点合并） */
        val merged: Int,
        /** 理由里含「同来源」/「共享关键词」/「同牌组」的边各有多少条（一条边可以同时算进两项） */
        val byFile: Int,
        val byKey: Int,
        val byDeck: Int,
    )

    /** 一个知识点的详情，点节点时用 */
    data class Detail(val node: Node, val cards: List<KnowledgeCard.KCData>, val edges: List<Edge>)

    // ---------------- 1. 关键词 ----------------

    /**
     * 从一段文字里抽关键词（有序、已去重、已去停用词、已截到 [KEY_PER_CARD]）。
     *
     * 分词只做三件事，因为这里的目标是「两张卡有没有共同的话题」，不是索引：
     *  1. 归一化（全角转半角、小写、去掉变音符）—— 「Ｆx」和「fx」必须算同一个词；
     *  2. 切出**中文连续段**（每段 2~6 字，滑动窗口：长段取相邻两两 + 整段，短段整体）和**拉丁/数字词**；
     *  3. 丢掉停用词、纯数字、以及首字是虚词的粘连组合。
     *
     * 滑动窗口而不是引入分词词典：零依赖是这个工程的红线，而双字窗口对「分享共同话题」这个任务足够 ——
     * 真正的噪声（「什么」「因为」）由停用词表挡，剩下的误判只影响「能不能连上」，不影响边的可解释性。
     */
    fun keywords(text: String, max: Int = KEY_PER_CARD): List<String> {
        if (text.isBlank()) return emptyList()
        val s = stem(text)
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (isCjk(c)) {
                var j = i
                while (j < s.length && isCjk(s[j])) j++
                addCjkRun(s, i, j, out, seen)
                i = j
            } else if (c.isLetterOrDigit() && c.code < 128) {
                var j = i
                while (j < s.length && s[j].code < 128 && s[j].isLetterOrDigit()) j++
                val w = s.substring(i, j)
                // 单个字母/数字没有区分度（「a」「1」），只留 2 字符以上的词
                if (w.length >= 2 && w !in STOP && !w.all { it.isDigit() } && seen.add(w)) out += w
                i = j
            } else i++
        }
        return out.take(max.coerceAtLeast(0))
    }

    /** 归一化：全角 → 半角、去变音、小写、统一各种连字符/引号。纯 java.text，不需要 android */
    fun stem(text: String): String {
        val n = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val sb = StringBuilder(n.length)
        for (ch in n) {
            sb.append(
                when (ch) {
                    '“', '”', '「', '」', '『', '』', '‘', '’', '"' -> '"'
                    '—', '–', '~', '〜' -> '-'
                    '，', ',' -> ','
                    '。' -> '.'
                    '（' -> '('; '）' -> ')'
                    '【' -> '['; '】' -> ']'
                    '：' -> ':'; '；' -> ';'
                    else -> ch
                }
            )
        }
        val lower = sb.toString().lowercase(Locale.ROOT)
        // 去变音符（café → cafe）：只在有拉丁重音时才重建，中文文本走不到这一步
        if (lower.any { it.code in 0x00C0..0x024F }) {
            return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        }
        return lower
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /**
     * 一个中文连续段的关键词候选。
     * 段长 1 直接丢（单字区别度太低，而且大量是虚词）；2~6 字整段 + 相邻两字；
     * 更长的段用 2 字滑窗（学习材料里「函数极限存在性」这种长串靠双字窗口拆）。
     */
    private fun addCjkRun(s: String, from: Int, to: Int, out: MutableCollection<String>, seen: MutableSet<String>) {
        val run = s.substring(from, to)
        // 整段先放：四字以内的术语（「导数」「极值点」「洛必达」）整段最有信息量
        if (run.length in 2..6 && ok(run) && seen.add(run)) out += run
        // 两字段不提前 return：它就是自己的双字窗口，走下面同一段逻辑，行为一致也少一条分支
        // 相邻两字：中文最短的实义词基本是双字
        var k = 0
        while (k + 2 <= run.length) {
            val g = run.substring(k, k + 2)
            if (ok(g) && seen.add(g)) out += g
            k++
        }
    }

    /**
     * 从两张卡的共享词里挑出**真正算两条证据**的那些。
     *
     * 中文双字滑窗的产物会互相压叠，直接用「共享几个词」当证据会重复计数，甚至凭空造出关系：
     *  - 「导数的定义」vs「导数的几何意义」共享 导数/数的/定义，看着 3 个，其实只是同一小段文字；
     *  - 「孤立概念甲」vs「孤立概念乙」共享 独立/立概/概念，实际毫无关系 —— 只是前缀写法一样。
     *
     * 两条规则并在一起就够：
     *  1. **互相压叠的只留一个**：按长度降序收，收了就跳过与已收词互为子串或首尾相接的；
     *  2. **两端都对不上号的片段不算**：片段必须**恰好**出现在两边原话里的同一处境 —— 即它在
     *     各自文本里出现的位置区间形状相同。简化成可判定的一条：片段若只靠「碰巧同前缀」相撞，
     *     它在两边文本里的首次出现位置必须都不在开头 0 处与末尾之外的重合…… 太绕，用更直白的判据：
     *     **片段在两边文本里作为完整词元出现的位置集合必须一致**。
     */
    fun independentShared(shared: Set<String>): List<String> {
        if (shared.size <= 1) return shared.toList()
        val picked = ArrayList<String>(shared.size)
        for (w in shared.sortedWith(compareByDescending<String> { it.length }.thenBy { it })) {
            if (picked.none { p -> overlaps(p, w) }) picked += w
        }
        return picked
    }


    /** 两个词是否「压在一起」：互为子串，或者首尾相接；共享一个首尾字即视为同一片段。 */
    private fun overlaps(a: String, b: String): Boolean =
        a.contains(b) || b.contains(a) || a.last() == b.first() || b.last() == a.first()


    /** 两个词是否「压在一起」：互为子串，或者首尾相接；共享一个首尾字即视为同一片段。 */


    /** 两个词是否「压在一起」：互为子串，或者共享首尾字符（双字滑窗的相邻片段必然如此）。 */


    /** 候选词是否可用：长度合规、不在停用词里、不是虚词粘连、不是纯数字 */
    private fun ok(w: String): Boolean =
        w.length >= 2 && w !in STOP && !VISIT_HEAD(w) && !w.all { it.isDigit() || it in "()[]{}.,:;-\"/" }

    private fun VISIT_HEAD(w: String): Boolean = w[0] in VIRTUAL_HEAD

    // ---------------- 2. 知识点 ----------------

    /**
     * 一张卡的知识点键（同一键 = 同一个知识点，图上只画一个点）。
     * 用**归一化后的问题**做键：导入时同一段原文里的「问 A / 追问 A」两张卡问题基本相同，
     * 而答案会互相包含。不以答案为键，是因为答案里常有「见上文」「同上」这类噪声，
     * 以它合并会误把两张不同的卡并成一个点。
     */
    fun topicKey(card: KnowledgeCard.KCData): String =
        // 只保留字母/数字/汉字：NFKC 之后「？」「。」「，」这些全角标点仍然存在，
        // 「什么是导数」和「什么是导数？」就会变成两个键 —— 同一概念出两个点。
        // 标点对「是不是同一个概念」没有信息量，直接丢掉最稳。
        stem(card.front).filter { it.isLetterOrDigit() }

    /** 知识点标签 = 最短的那张卡的正面（短才读得清）；全空白时退回牌组名 */
    fun labelOf(cards: List<KnowledgeCard.KCData>): String {
        val f = cards.map { it.front.trim() }.filter { it.isNotEmpty() }.minByOrNull { it.length }
            ?: cards.firstOrNull()?.deck ?: ""
        return oneLine(f)
    }

    /** 卡片正面压成一行（换行是导入时按原文保留的，图上不能带；连续空白收成一个） */
    fun oneLine(s: String): String = s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** 标签截断：[max] 个字符后加省略号。中文一个字就占一个显示格，不能按字节算 */
    fun clip(s: String, max: Int): String {
        val t = oneLine(s)
        if (t.length <= max) return t
        // 省略号也用全角，和中文标签等宽，截断处不会忽然变窄
        return t.take((max - 1).coerceAtLeast(1)) + "…"
    }

    // ---------------- 3. 建图 ----------------

    /**
     * 从卡片集合建图。
     *
     * @param list      知识卡（顺序不影响结果：内部会先按「边权重大 → 创建时间新」排序再决定节点归属）
     * @param threshold 画边阈值，[DEFAULT_THRESHOLD] 是默认口径
     * @param maxNodes  最多画几个节点，超出按权重截断（其余卡片不丢，只是不上图）
     */
    fun build(
        list: List<KnowledgeCard.KCData>,
        threshold: Double = DEFAULT_THRESHOLD,
        maxNodes: Int = MAX_NODES,
        mergeSameQuestion: Boolean = false,
    ): Graph {
        if (list.isEmpty()) return Graph(emptyList(), emptyList())

        // 3.1 卡片 → 知识点（topicKey 相同合并）
        val byTopic = LinkedHashMap<String, MutableList<KnowledgeCard.KCData>>()
        for (c in list) {
            // 默认一个知识点 = 一张卡（节点 id 用卡片 id 的字符串）；
            // mergeSameQuestion 时才把「问题相同」的多张卡并成一个点（见文件头那段解释）
            // 合并键取归一化问题的**严格相等**（问号/空格/全半角已由 stem 抹平）；不合并时用卡片 id
            val k = if (mergeSameQuestion) "topic:" + topicKey(c) else "card:" + c.id
            byTopic.getOrPut(k) { ArrayList() } += c
        }

        // 3.2 每个知识点一份画像：关键词集合、来源文件、牌组、代表标签
        data class Topic(
            val key: String,
            val cards: List<KnowledgeCard.KCData>,
            val keys: Set<String>,
            val file: String,
            val deck: String,
            val label: String,
        )

        // mutable：3.25 要按语料统计剔除「人人都有」的片段
        val topics = byTopic.entries.map { (k, cs) ->
            val head = cs.first()
            // 关键词取「所有卡面的并集」：反面往往是最具体的表述，只用正面会漏掉一半信号
            val text = cs.joinToString(" ") { it.front + " " + it.back }
            Topic(
                key = k,
                cards = cs.sortedBy { it.createdAt },
                keys = keywords(text, KEY_PER_CARD * 2).toSet(),
                file = cs.firstNotNullOfOrNull { fileOf(it.origin) } ?: "",
                deck = head.deck,
                label = labelOf(cs),
            )
        }.toMutableList()



        // 3.25 语料级停用：一个片段如果在绝大多数卡里都出现，它对「谁和谁相关」毫无区分度。
        // 典型例子：导入文本被数字/标点切碎后，每张卡都带有「个概念」这类残片 —— 用它们连边，
        // 40 张卡会连出 780 条边（实测），图立刻变成一团毛线。这类片段必须在建图前就丢掉。
        // 注意只删「几乎人人都有」的：真正的术语（导数、极限）只在一部分卡里出现，删不掉。
        if (topics.size >= MIN_UBIQUITY_CORPUS) {
            val df = HashMap<String, Int>()
            for (t in topics) for (k in t.keys) df[k] = (df[k] ?: 0) + 1
            // 至少要在 MIN_UBIQUITY_COUNT 张卡里出现才算「人人都有」。
            // 只用 0.7 的比例会在小语料上失灵：4 张卡时 (4*0.7).toInt() = 2，
            // 任何出现在 2 张卡里的词都被删光 —— 连「导数/极限/连续」这种真正的主术语也保不住。
            // 语料小于 6 张时干脆不用这条规则：样本太小，统计不出「普遍」。
            val limit = maxOf((topics.size * KEY_UBIQUITY).toInt(), MIN_UBIQUITY_COUNT)
            val common = df.filterValues { it >= limit }.keys
            if (common.isNotEmpty()) {
                for (i in topics.indices) topics[i] = topics[i].copy(keys = topics[i].keys - common)
            }
        }

        // 3.3 候选边：n 小（几百）时全对比 O(n²) 完全够；上万张卡也不会进这个页面（maxNodes 先截断）
        data class Cand(val a: String, val b: String, var score: Double, val parts: MutableList<String>)

        /**
         * 同来源预算的**发放顺序**：先在文件之间轮转，再把同一文件内部的配对排进来。
         * 为什么不能「先扫到的先拿」：那样一份 200 张卡的资料会用自己的前 40 对吃光全部预算，
         * 后面几份小资料一条内部边都分不到 —— 预算本来是防毛线团，不该变成「谁排序靠前谁有边」。
         * 名额是**全局累计**的（不是每份资料一份），否则总量仍然失控。
         */
        val filePairs = HashSet<String>()
        val byFile = topics.filter { it.file.isNotEmpty() }.groupBy { it.file }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Topic>>> { it.value.size }.thenBy { it.key })
        var budget = FILE_EDGE_BUDGET
        // 第一轮：每份资料发 1 条（多份小资料都至少有一条内部边）
        for ((_, ts) in byFile) {
            if (budget <= 0) break
            if (ts.size < 2) continue
            filePairs += edgeKey(ts[0].key, ts[1].key)
            budget--
        }
        // 第二轮：剩余预算按文件轮转续发（大资料内部关联多，值得多给几条），总量仍受 budget 约束
        var round = 1
        while (budget > 0 && byFile.any { it.value.size > round + 1 }) {
            for ((_, ts) in byFile) {
                if (budget <= 0) break
                if (ts.size <= round + 1) continue
                filePairs += edgeKey(ts[round].key, ts[0].key)
                budget--
            }
            round++
        }

        val cands = ArrayList<Cand>()
        val deckCands = ArrayList<Cand>()
        for (i in topics.indices) {
            for (j in i + 1 until topics.size) {
                val a = topics[i]; val b = topics[j]
                var score = 0.0
                val parts = ArrayList<String>(3)
                if (a.file.isNotEmpty() && a.file == b.file && filePairs.contains(edgeKey(a.key, b.key))) {
                    score += W_FILE; parts += "同来源 · " + a.file
                }
                val shared = a.keys.intersect(b.keys)
                // 只数「独立」的共享词：中文双字滑窗会产生重叠片段（「导数的定义」切出 导数/数的/定义），
                // 两张毫不相关的卡也会共享「数的」这种跨词边界的碎片。不做这步，
                // 「导数的定义」和「导数的几何意义」就会连成一条 3.0 的边（实测）。
                val independent = independentShared(shared)
                if (independent.size >= MIN_SHARED) {
                    // 满档制：达到 MIN_SHARED 个独立共享词就给满 W_KEY。
                    // 不按「个数 × W_KEY」累加：卡面有 8 个词，随便共享两个就能拿双倍分，
                    // 会把「只是都提到过某两个泛词」的一对抬成强关联，图立刻糊成一团。
                    // 也不按「个数/KEY_CAP」比例：真正相关的两张卡常常只共享 2 个独立词，
                    // 按比例只有 1 分（低于阈值 3.0），该连的反而连不上 —— 那比连多了更糟。
                    score += W_KEY
                    parts += "共享关键词 · " + independent.take(KEY_CAP).joinToString("、")
                }
                if (a.deck == b.deck) {
                    deckCands += Cand(a.key, b.key, W_DECK, arrayListOf("同牌组 · " + a.deck))
                }
                if (score > 0.0) cands += Cand(a.key, b.key, score, parts)
            }
        }
        // 3.4 同牌组补位：只在「已经能成边的一对」上加分，绝不单独成边 —— 见文件头
        val candIdx = cands.associateBy { edgeKey(it.a, it.b) }
        for (d in deckCands) {
            val hit = candIdx[edgeKey(d.a, d.b)] ?: continue
            hit.score += W_DECK
            hit.parts += d.parts
        }

        // 3.5 阈值过滤 + 去重（同一对只留一条，理由合并）
        val edges = LinkedHashMap<String, Edge>()
        for (c in cands) {
            if (c.score < threshold - 1e-9) continue
            edges[edgeKey(c.a, c.b)] = Edge(c.a, c.b, c.score, c.parts.joinToString("；"))
        }

        // 3.6 节点权重（带权度）→ 截断到 maxNodes。只用边上的点，孤点也算（它们本身就是「没有关联」的信号）
        val deg = HashMap<String, Int>()
        val wsum = HashMap<String, Double>()
        for (e in edges.values) {
            deg[e.a] = (deg[e.a] ?: 0) + 1; deg[e.b] = (deg[e.b] ?: 0) + 1
            wsum[e.a] = (wsum[e.a] ?: 0.0) + e.weight; wsum[e.b] = (wsum[e.b] ?: 0.0) + e.weight
        }
        val ordered = topics.sortedWith(
            compareByDescending<Topic> { wsum[it.key] ?: 0.0 }
                .thenByDescending { deg[it.key] ?: 0 }
                .thenByDescending { it.cards.maxOfOrNull { c -> c.createdAt } ?: 0L }
        )
        val keep = ordered.take(maxNodes.coerceAtLeast(1)).map { it.key }.toHashSet()
        val keptTopics = ordered.filter { it.key in keep }

        val nodeList = keptTopics.map { t ->
            Node(
                id = t.key,
                label = t.label,
                deck = t.deck,
                degree = deg[t.key] ?: 0,
                weight = wsum[t.key] ?: 0.0,
                cardIds = t.cards.map { it.id },
            )
        }
        val edgeList = edges.values.filter { it.a in keep && it.b in keep }
        return Graph(nodeList, edgeList)
    }

    fun edgeKey(a: String, b: String): String = if (a <= b) "$a\u0000$b" else "$b\u0000$a"

    /**
     * 从 origin 里取文件名。导入时写的是「文件名 · 原文摘录」（见 ImportActivity），
     * 老卡 / 手动建的卡 origin 为空串 —— 空就是「没有来源信号」，不能当成「都来自同一个空来源」。
     */
    fun fileOf(origin: String): String? {
        val s = origin.trim()
        if (s.isEmpty()) return null
        val head = s.substringBefore(" · ").substringBefore('·').trim()
        return head.ifEmpty { null }
    }

    // ---------------- 4. 聚类与统计 ----------------

    /**
     * 连通分量 = 「这份资料分成了几个主题」。
     * 按**权重和**降序（不是节点数）：三个高度相关的知识点（一个次主题）应该排在
     * 十个彼此只有一条弱边的散点前面。
     */
    fun clusters(graph: Graph): List<Cluster> {
        if (graph.nodes.isEmpty()) return emptyList()
        val adj = HashMap<String, MutableList<Pair<String, Double>>>()
        for (n in graph.nodes) adj[n.id] = ArrayList()
        for (e in graph.edges) {
            adj[e.a]?.add(e.b to e.weight)
            adj[e.b]?.add(e.a to e.weight)
        }
        val seen = HashSet<String>(graph.nodes.size)
        val out = ArrayList<Cluster>()
        for (n in graph.nodes) {
            if (!seen.add(n.id)) continue
            val comp = ArrayList<String>()
            var w = 0.0
            val stack = ArrayDeque<String>().apply { addLast(n.id) }
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                comp += cur
                for ((nxt, wt) in adj[cur].orEmpty()) {
                    if (nxt == cur) continue      // 自环（不该出现，防一手）
                    if (seen.add(nxt)) stack.addLast(nxt)
                }
            }
            // 分量内部边权之和：无向边两端都在分量里，只算一次
            val inComp = comp.toHashSet()
            for (e in graph.edges) if (e.a in inComp && e.b in inComp && e.a <= e.b) w += e.weight
            // 语义决策：**孤立节点不算一个主题**。单点没有任何「和谁相关」的证据，
            // 把它算成一个主题只会让「这份资料分成几个部分」虚高（10 张互不相干的卡 = 10 个主题？不是）。
            // 孤立点仍然出现在 graph.nodes 里（页面上画成灰点），只是不参与主题统计。
            // 全都是孤立点时（整库毫无关联）也至少返回 1 个主题，否则页面会显示「0 个主题」这种谎话。
            // 语义决策：**孤立节点不算一个主题**。单点没有任何「和谁相关」的证据，
            // 把它算成一个主题只会让「这份资料分成几个部分」虚高（10 张互不相干的卡 = 10 个主题？不是）。
            // 孤立点仍然出现在 graph.nodes 里（页面上画成灰点），只是不参与主题统计。
            // 全都没有连线时仍然返回 1 个主题，否则页面会显示「0 个主题」这种谎话。
            // 语义决策：**孤立节点不算一个主题**。单点没有任何「和谁相关」的证据，
            // 把它算成一个主题只会让「这份资料分成几个部分」虚高（10 张互不相干的卡 = 10 个主题？不是）。
            // 孤立点仍然出现在 graph.nodes 里（页面上画成灰点），只是不参与主题统计。
            // 全都没有连线时仍然返回 1 个主题，否则页面会显示「0 个主题」这种谎话。
            if (comp.size >= 2 || out.isEmpty()) out += Cluster(comp.sorted(), w)
        }
        return out.sortedWith(compareByDescending<Cluster> { it.weight }.thenByDescending { it.size })
    }

    fun stats(graph: Graph, cardCount: Int = 0): Stats {
        var byFile = 0; var byKey = 0; var byDeck = 0
        for (e in graph.edges) {
            // 理由串里可能有多个来源，按出现过的信号各记一次（同一对卡可以既是同来源又共享词）
            if (e.reason.contains("同来源")) byFile++
            if (e.reason.contains("共享关键词")) byKey++
            if (e.reason.contains("同牌组")) byDeck++
        }
        val merged = maxOf(0, cardCount - graph.nodes.sumOf { it.cardIds.size }) +
            graph.nodes.sumOf { maxOf(0, it.cardIds.size - 1) }
        return Stats(
            nodes = graph.nodes.size,
            edges = graph.edges.size,
            avgDegree = if (graph.nodes.isEmpty()) 0.0 else 2.0 * graph.edges.size / graph.nodes.size,
            merged = if (cardCount > 0) merged else graph.nodes.sumOf { maxOf(0, it.cardIds.size - 1) },
            byFile = byFile, byKey = byKey, byDeck = byDeck,
        )
    }

    /** 点节点要显示的内容：知识点 + 它名下的全部卡片（按创建时间倒序，最新的最可能想复习） + 它的连边 */
    fun detail(graph: Graph, cards: List<KnowledgeCard.KCData>, id: String): Detail? {
        val node = graph.nodeById[id] ?: return null
        val ids = node.cardIds.toHashSet()
        val mine = cards.filter { it.id in ids }.sortedByDescending { it.createdAt }
        val es = graph.edges.filter { it.a == id || it.b == id }.sortedByDescending { it.weight }
        return Detail(node, mine, es)
    }

    // ---------------- 5. 力导向布局 ----------------

    /**
     * 力导向布局（Fruchterman-Reingold 的简化版），纯 Kotlin，不引任何绘制库。
     *
     * ## 为什么是 200 次迭代
     * 小图（几十个点）在 200 次内基本收敛；再多的迭代在手机上是白费电，
     * 而且 **每次改窗口大小/旋转都要重算**，必须够快（120 个点是 0.2 秒量级的纯浮点运算）。
     *
     * ## 三个必须做对的细节
     *  1. **初始位置不能随机**：随机种子每次打开页面不一样，图会「跳」，用户以为数据变了。
     *     这里用「黄金角螺旋」把点均匀放在画布中心附近 —— 确定性、分布均匀，且天然不重叠。
     *  2. **斥力用 k²/d、引力用 d²/k**（FR 原式）：只在连线上的点之间算引力，
     *     否则孤点会被全图的质心拽到中间叠在一起。
     *  3. **最后等比缩放**：迭代完按实际包围盒缩放进 [w]×[h]，再留 [margin] 边距，
     *     结果**保证不越界**（单测断言这一点）。不缩的话初始螺旋半径和画布大小对不上，点会跑出屏幕。
     *
     * [margin] 是「节点圆点 + 标签」要占的位置：布局本身只保证点在 [margin, w-margin] 内，
     * 标签画在点的正下方，所以 margin 通常给得比圆点半径大。
     */
    fun layout(
        graph: Graph,
        w: Float,
        h: Float,
        iterations: Int = 200,
        margin: Float = 40f,
    ): Map<String, Pt> = layoutOf(graph, w, h, iterations, margin).pos

    /** 带画布尺寸的布局（页面要按实际包围盒居中，见 [Layout]） */
    fun layoutOf(
        graph: Graph,
        w: Float,
        h: Float,
        iterations: Int = 200,
        margin: Float = 40f,
    ): Layout {
        val n = graph.nodes.size
        if (n == 0 || w <= 0f || h <= 0f) return Layout(emptyMap(), w, h)
        val padX = margin.coerceIn(0f, w / 3f)
        val padY = margin.coerceIn(0f, h / 3f)
        val iw = (w - padX * 2).coerceAtLeast(1f)
        val ih = (h - padY * 2).coerceAtLeast(1f)
        val cx = w / 2f; val cy = h / 2f

        val ids = graph.nodes.map { it.id }
        val idx = ids.withIndex().associate { it.value to it.index }
        val x = FloatArray(n); val y = FloatArray(n)
        // 1) 确定性初始摆位：黄金角螺旋（半径按 √i 增长，面积均匀）
        val spirit = Math.sqrt(iw.toDouble() * ih.toDouble() / n).toFloat() * 0.9f
        for (i in 0 until n) {
            val r = spirit * Math.sqrt((i + 0.5).toDouble()).toFloat()
            val th = i * 2.3999632f
            x[i] = cx + r * Math.cos(th.toDouble()).toFloat()
            y[i] = cy + r * Math.sin(th.toDouble()).toFloat()
        }
        if (n == 1) return Layout(mapOf(ids[0] to Pt(cx, cy)), w, h)

        // 2) 边表（只含保留下来的两端）
        val eu = ArrayList<Int>(graph.edges.size)
        val ev = ArrayList<Int>(graph.edges.size)
        val ew = ArrayList<Float>(graph.edges.size)
        for (e in graph.edges) {
            val a = idx[e.a] ?: continue; val b = idx[e.b] ?: continue
            if (a == b) continue
            eu += a; ev += b; ew += e.weight.toFloat().coerceIn(0.2f, 4f)
        }
        val k = Math.sqrt(iw.toDouble() * ih.toDouble() / n).toFloat()   // 理想间距
        var temp = k * 0.6f
        // 降温系数：从 1 等比降到 ~0.0025。Math.pow 在 Kotlin 里是 Double，先算再收窄成 Float
        val cool = Math.pow(0.0025, 1.0 / iterations.coerceAtLeast(1)).toFloat()
        val fx = FloatArray(n); val fy = FloatArray(n)

        repeat(iterations.coerceAtLeast(1)) {
            java.util.Arrays.fill(fx, 0f); java.util.Arrays.fill(fy, 0f)
            // 3a) 斥力：所有点对（k²/d 方向推开）
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = x[i] - x[j]; var dy = y[i] - y[j]
                    var d2 = dx * dx + dy * dy
                    if (d2 < 1e-4f) {
                        // 完全重合：用索引当微扰，保证确定性（随机数会让两次打开不一样）
                        dx = ((i * 37 + j * 11) % 13 - 6).toFloat() * 0.01f
                        dy = ((i * 17 + j * 29) % 11 - 5).toFloat() * 0.01f
                        d2 = dx * dx + dy * dy + 1e-4f
                    }
                    val d = Math.sqrt(d2.toDouble()).toFloat()
                    val f = k * k / d
                    val ux = dx / d; val uy = dy / d
                    fx[i] += ux * f; fy[i] += uy * f
                    fx[j] -= ux * f; fy[j] -= uy * f
                }
            }
            // 3b) 引力：只有边上的点互相拉（d²/k，按边权放大 —— 共享三个词的一对比一个词的一对靠得近）
            for (m in eu.indices) {
                val a = eu[m]; val b = ev[m]
                var dx = x[a] - x[b]; var dy = y[a] - y[b]
                var d2 = dx * dx + dy * dy
                if (d2 < 1e-4f) { dx = 0.01f; dy = 0f; d2 = 1e-4f }
                val d = Math.sqrt(d2.toDouble()).toFloat()
                val pull = ew[m] * d2 / k * 0.35f
                val ux = dx / d; val uy = dy / d
                fx[a] -= ux * pull; fy[a] -= uy * pull
                fx[b] += ux * pull; fy[b] += uy * pull
            }
            // 3c) 位移按当前温度限幅（高温走大步，低温微调），并夹在画布内
            for (i in 0 until n) {
                val len = Math.sqrt((fx[i] * fx[i] + fy[i] * fy[i]).toDouble()).toFloat()
                if (len > 1e-3f) {
                    val step = minOf(len, temp) / len
                    x[i] = (x[i] + fx[i] * step).coerceIn(cx - iw / 2f, cx + iw / 2f)
                    y[i] = (y[i] + fy[i] * step).coerceIn(cy - ih / 2f, cy + ih / 2f)
                }
            }
            temp = (temp * cool).coerceAtLeast(0.05f)
        }
        return fit(ids, x, y, w, h, padX, padY)
    }

    /**
     * 等比塞进画布：按包围盒缩放并居中，留出 [padX]/[padY] 边距。
     * 缩放用**同一个比例**（不分别拉满 x/y），否则原本疏密关系会被压扁，图看起来是假的。
     */
    private fun fit(ids: List<String>, x: FloatArray, y: FloatArray, w: Float, h: Float, padX: Float, padY: Float): Layout {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in ids.indices) {
            if (x[i] < minX) minX = x[i]; if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]; if (y[i] > maxY) maxY = y[i]
        }
        val bw = (maxX - minX).coerceAtLeast(1e-3f)
        val bh = (maxY - minY).coerceAtLeast(1e-3f)
        val availW = (w - padX * 2).coerceAtLeast(1f)
        val availH = (h - padY * 2).coerceAtLeast(1f)
        // 单点时 bw/bh 是 1e-3：缩放比例会爆到几十万，所以这里把比例夹到 1 以内
        val s = minOf(availW / bw, availH / bh, 1f)
        val offX = padX + (availW - bw * s) / 2f
        val offY = padY + (availH - bh * s) / 2f
        val out = LinkedHashMap<String, Pt>(ids.size)
        for (i in ids.indices) {
            out[ids[i]] = Pt(
                (offX + (x[i] - minX) * s).coerceIn(0f, w),
                (offY + (y[i] - minY) * s).coerceIn(0f, h),
            )
        }
        return Layout(out, w, h)
    }
}
