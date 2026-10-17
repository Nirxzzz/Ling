package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 随机弹词（v1.62）：背单词 / 自定义词库。纯规则在这里（单元测试直接测），弹卡片在 WordCard，定时在 OrbService。
 *  - 词库 = 一组卡片（正面 / 背面）。内置「英语常用词」；自定义词库每行一条，「单词 释义」「正面 | 背面」「正面：背面」都认，
 *    所以古诗、公式、知识点也能放。只有正面没有背面的，卡片上点「查」走翻译。
 *  - 挑卡：不认识的权重大、连续认识 3 次算掌握（很少再出）、刚出过的不马上重复。
 */
object Words {
    const val BUILTIN_EN = "en"
    /** 自动词库：所有词库里「上次点了不认识」的（v1.63） */
    const val MISTAKES = "mistakes"
    const val MASTER_STREAK = 3

    /**
     * 富文本词书（v1.81）的魔数：正文第一行精确等于它，就按「记录式」解析（[parseRich]），
     * 不再走逐行 [parseLine]。考研红宝书三级词库用的就是这个格式 —— 卡片正面一个单词、
     * 背面是书上原文（音标 + 【词义】【助记】【反义】…多行），靠空行分隔记录。
     */
    const val RICH_MAGIC = "#LING-RICH v1"

    // 「英文词 + 空格 + 词性缩写 + 汉字释义」：一次解析近万行时会走到每行一次，编译一次正则的成本很显眼
    private val EN_ZH_RE = Regex("^([A-Za-z][A-Za-z'\\- ]*?)\\s+((?:[a-z]{1,5}\\.[/&]?\\s*)*\\p{IsHan}.*)$")

    fun unknown(s: Stat?) = s != null && s.seen > 0 && s.streak == 0

    /** 英文单词 / 短语才显示发音按钮 */
    fun speakable(front: String) = Regex("^[A-Za-z][A-Za-z'\\- ]{0,40}$").matches(front.trim())

    /**
     * 今天的计数（v1.63）：day = yyyyMMdd；换天清零。
     * v1.85 加 [fuzzy] = 今天「模糊」的条数。口径 —— 模糊**计入 [done]**（今天确实过了一遍），
     * **不计入 [known]**（known 只认「认识」；模糊是没记住，不该算掌握）。
     */
    data class Today(val day: Int = 0, val done: Int = 0, val known: Int = 0, val fuzzy: Int = 0)

    /**
     * 三档计数（v1.85）：CLOSE 只关闭不计；模糊只加 done+fuzzy；不认识只加 done；认识加 done+known。
     * 换天照旧先清零再计。
     */
    fun countToday(t: Today, today: Int, grade: SwipeJudge.Grade): Today {
        val base = if (t.day != today) Today(today) else t
        return when (grade) {
            SwipeJudge.Grade.CLOSE -> base
            SwipeJudge.Grade.KNOWN -> base.copy(done = base.done + 1, known = base.known + 1)
            SwipeJudge.Grade.FUZZY -> base.copy(done = base.done + 1, fuzzy = base.fuzzy + 1)
            SwipeJudge.Grade.UNKNOWN -> base.copy(done = base.done + 1)
        }
    }
    /** 旧的一档口径包装（true → 认识 / false → 不认识），其它调用点一个字节都不用改。 */
    fun countToday(t: Today, today: Int, known: Boolean): Today =
        countToday(t, today, if (known) SwipeJudge.Grade.KNOWN else SwipeJudge.Grade.UNKNOWN)
    fun todayOf(t: Today, today: Int): Today = if (t.day == today) t else Today(today)

    /**
     * 一张卡：正面（单词）/ 背面（释义）/ 例句（v1.80 新增，可空）。
     *
     * 为什么把例句放进卡里：**词书自己的例句比模型现编的好**。项目里常见的词书
     * （四六级 / 雅思那类）本来就带例句，导入时第三个字段直接落到这儿，弹卡就有一条真句子，
     * 不用为了例句再花一次模型调用。没有例句的词书照旧（example 空串），想要就点「查」补。
     */
    data class Card(val front: String, val back: String, val example: String = "")
    data class Deck(val id: String, val name: String, val text: String)
    /**
     * 一张卡的记录：连续认识次数、出现次数、上次出现时刻。
     * v1.85 加 [fuzzy] = 累计「模糊」次数（默认 0；老数据没有这一位，反序列化时当 0）。
     */
    data class Stat(val streak: Int = 0, val seen: Int = 0, val last: Long = 0, val fuzzy: Int = 0)

    /**
     * 一行 → 卡片：先找明确的分隔符（Tab、|、：、:、—、 - ），没有的话「英文词/短语 + 空格 + 含中文的释义」也拆开。
     *
     * **一行最多认三栏**（v1.80）：`word | 释义 | 例句`。多出来的分隔符留在最后一栏里（limit = 3），
     * 所以释义里带冒号 / 例句里带破折号都不会被切坏。只给两栏的词书照旧只有释义。
     * 没有明确分隔符的那种「abandon v. 放弃」按老路子拆两栏，不猜例句。
     */
    fun parseLine(raw: String): Card? {
        // 超长行先截断：EN_ZH_RE 对「几万字符还等不到汉字」的输入会正则深递归直接 StackOverflow
        // （edge 探针实测 5 万字符即崩，且剪贴板导入的 Words.parse 不在 try 里 = 真崩 App）。
        // 2000 字符以内截断无感，超过本来就是脏数据。
        if (raw.length > 2_000) return parseLine(raw.take(2_000))
        val line = raw.trim().trimStart('-', '*', '•', '·').trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        for (sep in listOf("\t", " | ", "|", "：", " — ", "——", " - ", ": ")) {
            val i = line.indexOf(sep)
            if (i > 0) {
                val parts = line.split(sep, limit = 3).map { it.trim() }
                val f = parts.getOrElse(0) { "" }
                if (f.isNotEmpty()) {
                    return Card(
                        f,
                        parts.getOrElse(1) { "" },
                        // 第三栏才是例句；只有两栏时不硬把释义当例句（释义里有空格很常见）
                        if (parts.size >= 3) parts[2] else "",
                    )
                }
            }
        }
        // 「abandon v. 放弃」「look forward to 期待」：从第一个汉字 / 词性缩写处拆
        val m = EN_ZH_RE.find(line)
        if (m != null) return Card(m.groupValues[1].trim(), m.groupValues[2].trim())
        return Card(line, "")
    }

    fun parse(text: String): List<Card> {
        // 富文本词书（v1.81）：去 BOM / 前后空白后的第一行等于 [RICH_MAGIC] → 走记录式解析。
        // 非富文本一律走老路径，用的是原始 text（不打 BOM / trim），保证既有词书一个字节都别变。
        // v1.85.12：老路径的**首行**也要去 BOM —— Windows 记事本存的 UTF-8-BOM 词库，
        // BOM 会粘在第一个词的词头上（edge 轮 2 实测抓到），搜索/匹配全都对不上。
        val head = text.removePrefix("\uFEFF").trim()
        if (head.lineSequence().firstOrNull()?.trim() == RICH_MAGIC) return parseRich(head)
        val body = if (text.startsWith("\uFEFF")) text.removePrefix("\uFEFF") else text
        return body.lines().mapNotNull { parseLine(it) }.distinctBy { it.front }
    }

    /**
     * 富文本词书 → 卡片（v1.81，红宝书考研三级）：
     *  - 记录之间恰好一个空行；记录内部没有空行；
     *  - 每条记录第一个非空行 = 正面（[String.trim] 后），其余行按 `\n` 原样拼成背面；
     *  - `#` 开头的行（注释 / 魔数行）整行丢掉；
     *  - 正面为空的记录跳过；同正面去重（留第一条）；
     *  - [Card.example] 一律空串 —— 例句在红宝书里是背面的【例句】段，属于原文，不往这个字段挪。
     *
     * **背面不许截断、不许重排、不许改写**：那是书上的排版，交给 [prettify] 时也要整体跳过 WordSense 排版。
     */
    fun parseRich(text: String): List<Card> {
        val out = ArrayList<Card>()
        val back = ArrayList<String>()
        var front = ""
        // 一条记录收尾：正面非空才落卡片，背面行原样拼回（内部换行保留）
        fun flush() {
            val f = front.trim()
            if (f.isNotEmpty()) out.add(Card(f, back.joinToString("\n"), ""))
            front = ""
            back.clear()
        }
        for (line in text.lines()) {
            if (line.startsWith("#")) continue          // 注释行 / 魔数行
            if (line.isBlank()) { flush(); continue }   // 空行 = 记录分隔
            if (front.isEmpty()) front = line else back.add(line)
        }
        flush()
        return out.distinctBy { it.front }
    }

    // v1.76.5：内置 5 级词库导入后合计近万词，设置页每次重建词库区块、弹词每次挑卡都会重新 parse 一遍
    // 同一份全文，纯 CPU 的重复解析足以把主线程卡住。按文本内容做 key 缓存一份，文本没变就直接复用。
    // 容量到顶就整体清空：miss 最多多解析一次，比维护 LRU 简单，也不会让内存无限涨。
    private val parseMemo = HashMap<String, List<Card>>()
    private val decksMemo = HashMap<String, List<Deck>>()

    @Synchronized
    fun parseMemoized(text: String): List<Card> = parseMemo.getOrPut(text) {
        if (parseMemo.size >= 16) parseMemo.clear()
        parse(text)
    }

    /** 词库摘要：设置页默认态一行就够（名字 / 条数 / 已掌握），不必为渲染把全文塞进输入框 */
    data class DeckSummary(val id: String, val name: String, val total: Int, val mastered: Int)

    /** 摘要口径：条数 = 按正面去重后的卡片数；已掌握 = 连续认识 [MASTER_STREAK] 次的卡片数。解析走缓存 */
    fun summaries(decks: List<Deck>, stats: Map<String, Stat>): List<DeckSummary> = decks.map { d ->
        val cards = parseMemoized(d.text)
        DeckSummary(d.id, d.name, cards.size, cards.count { mastered(stats[key(d.id, it)]) })
    }

    /**
     * 可编辑的词库：内置英语词库的正文写在代码里（[BUILTIN_EN_TEXT]），5 级内置词库的正文在 assets 里
     * （[WordBank]）—— 两者都没有可改的全文，所以不展开编辑区（v1.77 起 5 级也是直接勾选，不导入）。
     * 老用户导入过的 wb_xxx 副本 id 相同，一并落在这一侧之外；想改内容就复制一份去自定义词库。
     */
    fun editableDecks(decks: List<Deck>): List<Deck> = decks.filter { it.id != BUILTIN_EN && !WordBank.isLevelDeck(it.id) }

    /**
     * 哪些词库的编辑区正展开着（v1.76.5）。
     * 默认空 = 一个全文输入框都不建，这是设置页不再卡的关键；同一时间允许多个展开，不做互斥。
     * 放在 [summaries] 之外，保存/删除触发重建后才能保持展开状态。
     */
    class Expanded {
        private val ids = LinkedHashSet<String>()
        fun isOpen(id: String) = id in ids
        /** 返回展开后的新状态（true=展开、false=收起）；已在列表里则原样返回，天然幂等 */
        fun toggled(id: String): Boolean = if (ids.remove(id)) false else { ids.add(id); true }
        fun opened(id: String) { ids.add(id) }
        fun closed(id: String) { ids.remove(id) }
        /** 词库被删掉后顺手清掉它的展开态，免得 id 复用时莫名展开 */
        fun retainOnly(keep: Set<String>) { ids.retainAll(keep) }
        fun openIds(): Set<String> = ids.toSet()
    }

    // ---- 词库区块的增删改（v1.76.5：从点击回调里提到纯逻辑，设置页与单测共用一份）----

    /** 保存：只动 id 命中的那一个；名字留空回落「我的词库」，正文原样存 */
    fun saveDeck(decks: List<Deck>, id: String, name: String, text: String): List<Deck> =
        decks.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { "我的词库" }, text = text) else it }

    /** 删除：正文和勾选都要一起走，别留下指向已删词库的 id */
    fun deleteDeck(decks: List<Deck>, on: Set<String>, id: String): Pair<List<Deck>, Set<String>> =
        decks.filter { it.id != id } to (on - id)

    /** 勾选启用/ 取消；id 不在列表里也能安全调用 */
    fun toggleDeck(on: Set<String>, id: String, checked: Boolean): Set<String> =
        if (checked) on + id else on - id

    /**
     * 详细排版（v1.67）：背面按 WordSense 规则排成多义项 + 例句的纯文字卡片；front 截到 200、back 截到 500。
     * v1.80：**例句原样带过去** —— 那是词书给的，排版这一步丢了它，卡片上就再也看不到例句了（实测踩过）。
     * v1.81：**书页式背面原样返回，不走 [WordSense.formatBack]**。那条路会
     * `format(..., backMax = WordSense.BACK_MAX = 500)`：把长条目砍到 500 字 + 按义项重排，
     * 红宝书那种「音标 + 【词义】【助记】【反义】…」的原文排版会被毁掉。
     *
     * 「书页式」= 含 `【` 标签，或者首行是音标 / 词性行（超纲词那级背面没有【标签，只有音标行）。
     * 判定复用 [KaoyanBack.isRich] —— 与卡片渲染认的是同一份：这层要先把背面改写成 `1. …`，
     * 卡片那边就认不出富排版了。普通词书的一行释义（「v. 放弃；抛弃」）两条都不命中，行为不变。
     */
    fun prettify(c: Card): Card = Card(
        WordSense.clipFront(c.front),
        when {
            c.back.isBlank() -> c.back
            c.back.contains('【') -> c.back              // 书页式：原样，不截断不重排
            KaoyanBack.isRich(c.back) -> c.back          // 无【但首行音标/词性的书页式背面
            else -> WordSense.formatBack(c.back)
        },
        c.example,
    )

    /**
     * 卡片上该显示什么（v1.78）：词库自带背面 → [prettify] 排详细版；
     * 只有单词、没有背面（内置 5 级词库就是这样，正文只有英文）→ 用预翻译译文排成单义项卡片；
     * 译文也还没有（没预翻译过）→ 原样返回，卡片上只留单词和「查」，等用户去要。
     *
     * 译文这一支**不再走 [prettify]**：译文本身已经是 [WordSense.formatTranslation] 排好的多行文本，
     * 再当背面解析一遍会把「例：…」那行也当成一个义项。
     */
    fun detail(c: Card, translation: String?): Card = when {
        c.back.isNotBlank() -> prettify(c)
        translation.isNullOrBlank() -> c
        else -> Card(WordSense.clipFront(c.front), WordSense.formatTranslation(c.front, translation), c.example)
    }

    fun key(deck: String, c: Card) = deck + "\u0000" + c.front

    /**
     * 权重：没见过 3；不认识（streak 0 且见过）5；认识 1~2 次 2 / 1；掌握 0.15；刚出过（10 分钟内）0。
     *
     * v1.85 三档口径：**有「模糊」记录、又还没掌握**（streak 1 ~ [MASTER_STREAK]-1）的词，
     * 在基准权重之上按模糊次数加成（每次 +40%，封顶翻倍）—— 它要比同 streak「一直认识」的词更早再弹：
     * 用户自己点了「模糊」= 没记牢，得再见面。整体形状不动：基准仍由 streak 决定，模糊只抬一档；
     * streak 0（不认识）本来就是最高的 5、掌握（0.15）本来就最低，两支都不叠加。
     */
    fun weight(s: Stat?, now: Long): Double {
        if (s == null || s.seen == 0) return 3.0
        if (now - s.last < 10 * 60_000L) return 0.0
        val base = when {
            s.streak >= MASTER_STREAK -> 0.15
            s.streak == 0 -> 5.0
            s.streak == 1 -> 2.0
            else -> 1.0
        }
        if (s.fuzzy > 0 && s.streak in 1 until MASTER_STREAK) return base * (1.0 + 0.4 * s.fuzzy).coerceAtMost(2.0)
        return base
    }

    /** 按权重随机挑一张；全都刚出过就退回纯随机 */
    fun pick(cards: List<Pair<String, Card>>, stats: Map<String, Stat>, now: Long, rnd: Random = Random.Default): Pair<String, Card>? {
        if (cards.isEmpty()) return null
        val w = cards.map { weight(stats[key(it.first, it.second)], now) }
        val total = w.sum()
        if (total <= 0.0) return cards[rnd.nextInt(cards.size)]
        var r = rnd.nextDouble() * total
        for (i in cards.indices) { r -= w[i]; if (r <= 0) return cards[i] }
        return cards.last()
    }

    /**
     * v1.85 三档记分。基准照旧 [seen] +1、[last] = now；连续次数按档走：
     *  - [SwipeJudge.Grade.KNOWN]   认识 → streak + 1
     *  - [SwipeJudge.Grade.FUZZY]   模糊 → **streak 保持不变**（没记住、但也没判死），fuzzy + 1
     *  - [SwipeJudge.Grade.UNKNOWN] 不认识 → streak 清零
     *  - [SwipeJudge.Grade.CLOSE]   上滑只关闭、不评分：**原样返回**（不该被调用，兜底）
     */
    fun mark(s: Stat?, grade: SwipeJudge.Grade, now: Long): Stat {
        val o = s ?: Stat()
        return when (grade) {
            SwipeJudge.Grade.CLOSE -> o
            SwipeJudge.Grade.KNOWN -> Stat(o.streak + 1, o.seen + 1, now, o.fuzzy)
            SwipeJudge.Grade.FUZZY -> Stat(o.streak, o.seen + 1, now, o.fuzzy + 1)
            SwipeJudge.Grade.UNKNOWN -> Stat(0, o.seen + 1, now, o.fuzzy)
        }
    }
    /** 旧的一档口径包装（true → 认识 / false → 不认识），其它调用点不用改。 */
    fun mark(s: Stat?, known: Boolean, now: Long): Stat =
        mark(s, if (known) SwipeJudge.Grade.KNOWN else SwipeJudge.Grade.UNKNOWN, now)
    fun shown(s: Stat?, now: Long): Stat = (s ?: Stat()).copy(last = now)

    fun mastered(s: Stat?) = s != null && s.streak >= MASTER_STREAK

    /** 夜里不弹：from ≤ 时 < to（跨午夜也行，比如 23 → 7） */
    fun quiet(hour: Int, from: Int, to: Int): Boolean = if (from == to) false else if (from < to) hour in from until to else hour >= from || hour < to

    // ---------- 存取（Prefs 里的 JSON 字符串）----------
    fun decksToJson(l: List<Deck>): String = JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) } }.toString()
    fun decksFromJson(s: String?): List<Deck> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Deck(it.optString("id"), it.optString("name", "我的词库"), it.optString("text")) }?.takeIf { it.id.isNotEmpty() } }
    } catch (_: Exception) { emptyList() }

    /** 同 [parseMemoized]：词库 JSON 动辄几十万字，而 customDecks 一次渲染要被读好几遍，别反复反序列化 */
    @Synchronized
    fun decksMemoized(s: String?): List<Deck> = decksMemo.getOrPut(s ?: "") {
        if (decksMemo.size >= 8) decksMemo.clear()
        decksFromJson(s)
    }

    /** v1.85：第 4 位是 fuzzy；老数据只有 3 位 → [JSONArray.optInt] 拿 0，等于没记过模糊。 */
    fun statsToJson(m: Map<String, Stat>): String = JSONObject().apply {
        m.forEach { (k, v) -> put(k, JSONArray().put(v.streak).put(v.seen).put(v.last).put(v.fuzzy)) }
    }.toString()
    fun statsFromJson(s: String?): Map<String, Stat> = try {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { Stat(it.optInt(0), it.optInt(1), it.optLong(2), it.optInt(3)) } }
    } catch (_: Exception) { emptyMap() }

    /** 内置：英语常用词（高考 / 四级高频），正面单词、背面「词性 释义」 */
    val BUILTIN_EN_TEXT = """
abandon | v. 放弃；抛弃
ability | n. 能力；才能
absorb | v. 吸收；使专心
abstract | adj. 抽象的 n. 摘要
academic | adj. 学术的
access | n. 通道；使用权 v. 进入
accompany | v. 陪伴；伴随
accomplish | v. 完成；实现
account | n. 账户；解释 v. 说明
accurate | adj. 准确的
achieve | v. 实现；达到
acknowledge | v. 承认；致谢
acquire | v. 获得；学到
adapt | v. 适应；改编
adequate | adj. 足够的；适当的
adjust | v. 调整；适应
admire | v. 钦佩；欣赏
adopt | v. 采用；收养
advantage | n. 优势；好处
affect | v. 影响
afford | v. 买得起；承担得起
aggressive | adj. 好斗的；积极进取的
alternative | n. 选择 adj. 可供替代的
amazing | adj. 令人惊奇的
ambition | n. 抱负；野心
analyze | v. 分析
anxious | adj. 焦虑的；渴望的
apparent | adj. 明显的；表面上的
appeal | v. 呼吁；吸引 n. 吸引力
appreciate | v. 欣赏；感激
approach | n. 方法 v. 接近
appropriate | adj. 适当的
approve | v. 批准；赞成
argue | v. 争论；主张
arrange | v. 安排；整理
assess | v. 评估
assist | v. 帮助
assume | v. 假定；承担
atmosphere | n. 大气；气氛
attach | v. 附上；依恋
attempt | v./n. 尝试
attitude | n. 态度
attract | v. 吸引
available | adj. 可获得的；有空的
avoid | v. 避免
aware | adj. 意识到的
balance | n. 平衡 v. 使平衡
barrier | n. 障碍
behavior | n. 行为
benefit | n. 好处 v. 受益
blame | v. 责备 n. 过错
budget | n. 预算
capable | adj. 有能力的
capacity | n. 容量；能力
challenge | n. 挑战 v. 质疑
character | n. 性格；角色；字符
circumstance | n. 情况；环境
claim | v. 声称；索要 n. 主张
colleague | n. 同事
commit | v. 犯（错）；承诺
communicate | v. 交流
compare | v. 比较
compete | v. 竞争
complex | adj. 复杂的
concentrate | v. 集中（注意力）
concern | n. 担心 v. 涉及；使担忧
conclude | v. 得出结论；结束
confident | adj. 自信的
confirm | v. 确认
conflict | n. 冲突
consequence | n. 后果
considerable | adj. 相当大的
consist | v. 由……组成（of）
constant | adj. 不断的；恒定的
contribute | v. 贡献；促成
convenient | adj. 方便的
convince | v. 说服；使确信
cooperate | v. 合作
crucial | adj. 至关重要的
curious | adj. 好奇的
decline | v. 下降；拒绝
dedicate | v. 奉献；致力于
define | v. 定义
delicate | adj. 精致的；微妙的
demand | n./v. 要求；需求
deny | v. 否认；拒绝
depend | v. 依靠；取决于（on）
deserve | v. 值得；应得
desire | n./v. 渴望
determine | v. 决定；查明
device | n. 设备；装置
devote | v. 献身于；把……用于
distinguish | v. 区分
efficient | adj. 高效的
eliminate | v. 消除
emerge | v. 出现；浮现
emphasize | v. 强调
encourage | v. 鼓励
enormous | adj. 巨大的
ensure | v. 确保
environment | n. 环境
essential | adj. 必要的；本质的
estimate | v./n. 估计
evaluate | v. 评价
evidence | n. 证据
evolve | v. 进化；发展
exaggerate | v. 夸大
expand | v. 扩大
expert | n. 专家
explore | v. 探索
extraordinary | adj. 非凡的
feature | n. 特征 v. 以……为特色
flexible | adj. 灵活的
focus | v. 集中 n. 焦点
generate | v. 产生
genuine | adj. 真正的；真诚的
guarantee | v./n. 保证
highlight | v. 强调 n. 最精彩的部分
identify | v. 识别；确认
ignore | v. 忽视
illustrate | v. 说明；图解
impact | n./v. 影响；冲击
imply | v. 暗示；意味着
impress | v. 给……留下深刻印象
improve | v. 改善；提高
indicate | v. 表明；指出
influence | n./v. 影响
initial | adj. 最初的
innovate | v. 创新
insist | v. 坚持
inspire | v. 激励；启发
intend | v. 打算
interpret | v. 解释；口译
involve | v. 涉及；使参与
maintain | v. 维持；主张
majority | n. 大多数
measure | n. 措施 v. 测量
mention | v./n. 提到
negative | adj. 消极的；负面的
obtain | v. 获得
obvious | adj. 明显的
occur | v. 发生；想到（to）
opportunity | n. 机会
option | n. 选择
participate | v. 参加（in）
patient | adj. 耐心的 n. 病人
perform | v. 表演；执行
persuade | v. 说服
potential | adj. 潜在的 n. 潜力
precious | adj. 珍贵的
predict | v. 预测
prefer | v. 更喜欢
preserve | v. 保护；保存
prevent | v. 阻止；预防
primary | adj. 主要的；初级的
priority | n. 优先事项
process | n. 过程 v. 处理
promote | v. 促进；提升
proper | adj. 适当的
propose | v. 提议；求婚
protect | v. 保护
pursue | v. 追求；从事
recognize | v. 认出；承认
recommend | v. 推荐；建议
reduce | v. 减少
reflect | v. 反映；反思
reject | v. 拒绝
relevant | adj. 相关的
reluctant | adj. 不情愿的
rely | v. 依赖（on）
remarkable | adj. 非凡的；值得注意的
require | v. 需要；要求
resource | n. 资源
respond | v. 回应
responsible | adj. 负责的
reveal | v. 揭示
significant | adj. 重要的；显著的
similar | adj. 相似的
strategy | n. 策略
struggle | v./n. 奋斗；挣扎
sufficient | adj. 充足的
suggest | v. 建议；暗示
survive | v. 幸存
sustain | v. 维持；支撑
tend | v. 倾向于（to）
tradition | n. 传统
transform | v. 转变
tremendous | adj. 巨大的；极好的
ultimate | adj. 最终的
unique | adj. 独特的
urgent | adj. 紧急的
valuable | adj. 有价值的
various | adj. 各种各样的
vital | adj. 至关重要的
willing | adj. 愿意的
""".trimIndent()
}
