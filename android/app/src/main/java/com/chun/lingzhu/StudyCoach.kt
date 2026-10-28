package com.chun.lingzhu

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v1.71 学习教练：把「今天有多少张卡到期、哪个牌组积压、每天该学几个新词」
 * 算成一段能直接说给人听、也能当模型工具返回值的话。
 *
 * AgentTools 的 study_plan 工具直接吐 [Advice.summary]，所以这里口径必须和
 * StudyActivity / ReviewActivity 看到的一致：同一个 StudyPlan、同一个 FSRS 参数。
 * 口径不一致的工具是负资产——用户会照着「今天复习 300 张」去学习，打开却只有 20 张。
 */
object StudyCoach {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** 积压榜长度。 */
    const val TOP_DECKS = 5

    /**
     * 一个牌组的积压情况。[overdue] = 已经过了到期日但还没复习的（真正的债）；
     * [dueToday] = 今天到期（不含逾期）。分开是因为逾期多说明在拖，到期多说明正常推进。
     */
    data class Deck(val name: String, val dueToday: Int, val overdue: Int, val newCards: Int, val total: Int) {
        /** 排序用的积压总量：逾期优先（逾期是债，不还越滚越大）。 */
        val backlog: Int get() = overdue * 2 + dueToday
    }

    /**
     * 建议。[todayReview] 是今天实际该复习的张数（受 plan.reviewLimit 截断），
     * [backlog] 是截断前的真实到期量——两者不等就说明一轮复习做不完，要说给用户听。
     * [newPerDay] 是建议的每日新学量，[summary] 是那句人话。
     */
    data class Advice(
        val todayReview: Int,
        val backlog: Int,
        val newDue: Int,
        val newPerDay: Int,
        val decks: List<Deck>,
        val summary: String,
    )

    /**
     * 算建议。[cards] 是知识卡，[words] 是词卡的 FSRS 状态表，[plan] 是用户当前的学习计划。
     *
     * 「今天该复习多少」的口径和 StudyActivity 一致：只算**已经在 FSRS 里、且今天到期**的卡；
     * 从没复习过的（isNew 或 neverReviewed）算「未学」，走新学额度，不进复习数——
     * 否则书页式词库几千个新词一夜之间全算「到期」，这个数字就彻底失去意义了。
     *
     * 每日新学量走 [newBudget]：积压越多给得越少，积压超过一轮复习额度时直接给 0。
     */
    fun suggest(cards: List<KnowledgeCard.KCData>, words: Map<String, Fsrs.Card>, plan: StudyPlan, now: Long): Advice {
        val dayStart = WeakPoints.dayStart(now)

        // ── 知识卡：按牌组统计「今天到期 / 逾期 / 新卡」
        val stat = LinkedHashMap<String, IntArray>()   // [dueToday, overdue, new, total]
        var dueToday = 0
        var overdue = 0
        var newDue = 0
        for (c in cards) {
            if (!plan.acceptsCard(c.deck)) continue
            val s = stat.getOrPut(c.deck) { IntArray(4) }
            s[3]++
            val f = c.fsrs
            // 没复习过的一律算新卡：它今天当然「到期」，但该走「学新卡」的额度，不该占复习额度
            if (f.isNew || f.neverReviewed) { s[2]++; newDue++; continue }
            if (f.due <= dayStart) { s[1]++; overdue++; dueToday++ } else if (f.due <= now) { s[0]++; dueToday++ }
        }

        // ── 词卡：口径照 StudyActivity / ReviewActivity——表里没有记录的词算「未学过」，不计到期
        if (plan.words) {
            for ((key, card) in words) {
                if (!plan.acceptsWord(wordDeck(key))) continue
                if (card.isNew || card.neverReviewed) { newDue++ } else if (Fsrs.isDue(card, now, dayStart)) dueToday++
                // 已经答过、只是今天还没到期的词不算任何一边——它今天确实没事可做
            }
        }

        val backlog = dueToday
        val todayReview = min(dueToday, max(plan.reviewLimit, 0))
        val newPerDay = newBudget(plan, backlog)
        // 只留还有活的牌组：全学完的牌组挂在积压榜上只会让人误以为欠着东西
        val decks = stat.map { e -> Deck(e.key, e.value[0] + e.value[1], e.value[1], e.value[2], e.value[3]) }
            .filter { it.dueToday + it.newCards > 0 }
            .sortedWith(compareByDescending<Deck> { it.backlog }.thenByDescending { it.newCards }.thenBy { it.name })
            .take(TOP_DECKS)

        val advice = Advice(todayReview, backlog, newDue, newPerDay, decks, "")
        return advice.copy(summary = summary(advice))
    }

    /**
     * 每日新学量。三档，按顺序判：
     *  1. 用户把新学关掉（newLimit = 0）就给 0，不越权替他决定；
     *  2. 积压已经超过一轮复习额度 → 0。这时候再塞新卡，等于让明天的债更大，
     *     而「学新卡」正反馈最强，是用户最抵抗不住的动作，必须替他踩这一脚；
     *  3. 否则按剩余复习余量线性给，**最低保底四分之一**——完全不学新东西会让人失去进展感，
     *     进而彻底放弃，这比「学得慢」更糟。
     * 上限是 newLimit × 2：用户可以调高计划，但不该被建议一次性上几十个新卡。
     */
    fun newBudget(plan: StudyPlan, backlog: Int): Int {
        if (plan.newLimit <= 0) return 0
        if (backlog > plan.reviewLimit) return 0
        val room = (plan.reviewLimit - backlog).toDouble() / max(plan.reviewLimit, 1)
        val scaled = (plan.newLimit * (0.25 + 0.75 * room)).roundToInt()
        return scaled.coerceIn(max(1, plan.newLimit / 4), plan.newLimit * 2)
    }

    /**
     * 从词卡键里取牌组。键的格式是 Words.key 产出的 "牌组\u0000单词"（见 ReviewActivity 里
     * key.substringBefore('\u0000') 的用法），没有分隔符的老数据当成一个「词语」牌组。
     * 必须和 ReviewActivity 用同一个口径，否则这里算出的「哪些词该复习」和真点进去看到的对不上。
     */
    fun wordDeck(key: String): String {
        val deck = key.substringBefore('\u0000')
        return if (deck.isEmpty() || deck == key) "词语" else deck
    }

    /**
     * 一句人话总结。刻意给出**可执行的数字**（今天该复习几张、新学几个），
     * 而不是「继续保持」这种没法照做的话；积压时把「做不完」的事实明说，
     * 否则用户会以为今天点开就清空了，第二天发现还剩一堆，从此不信这个数字。
     */
    fun summary(a: Advice): String {
        if (a.backlog == 0 && a.newDue == 0) return "今天没有到期的卡，学习任务已完成。想加练可以学几个新卡。"
        val sb = StringBuilder()
        if (a.backlog > 0) {
            sb.append("今天要复习 ").append(a.todayReview).append(" 张")
            if (a.backlog > a.todayReview) {
                sb.append("（实际到期 ").append(a.backlog).append(" 张，一轮做不完，建议分两轮）")
            }
            sb.append("。")
        } else {
            sb.append("今天没有需要复习的旧卡。")
        }
        if (a.newPerDay > 0) {
            sb.append("可以学 ").append(a.newPerDay).append(" 个新卡")
            if (a.newDue > 0) sb.append("（未学新卡共 ").append(a.newDue).append(" 张）")
            sb.append("。")
        } else {
            sb.append("先把积压的复习清完，再加新卡。")
        }
        val worst = a.decks.firstOrNull()
        if (worst != null && worst.backlog > 0) {
            sb.append("积压最多的是「").append(worst.name).append("」，还有 ").append(worst.overdue).append(" 张逾期。")
        }
        return sb.toString()
    }
}