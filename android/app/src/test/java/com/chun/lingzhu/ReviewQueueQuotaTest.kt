package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3.2 修「单词挤掉知识卡」的回归测试。
 *
 * 真机现象：卡库 1 张到期知识卡 + 内置词库 19 个新词（默认全选），点「开始复习 · 每轮最多 100 项」，
 * 结果是「单词 1/19」，那张到期卡一眼都看不到。根因是单词队列拿了
 * `总限额 - 知识卡数` = 99，把 19 个词全吃进去，再被 render() 的「先单词后知识卡」排到后面。
 *
 * 这里全部走纯函数（[StudyPlan.limitFor] / [ReviewActivity.pickDueWords] / [ReviewActivity.pickCards]），
 * 不需要 Activity、也不需要设备。
 */
class ReviewQueueQuotaTest {

    private val T0 = 1_700_000_000_000L

    /** 一张已复习过、已过期的知识卡（走一遍 Good 首刷再把 due 拨到过去）。 */
    private fun dueCard(id: Long, deck: String = "默认"): KnowledgeCard.KCData {
        val c = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, T0 - 10 * Fsrs.DAY_MS)
        return KnowledgeCard.KCData(id, deck, "正面" + id, "背面" + id, "manual", T0,
            c.copy(due = T0 - Fsrs.DAY_MS))
    }

    /** 一个有 [count] 个词、且这些词在 FSRS 里都已复习过并过期的词库。 */
    private fun dueWordDeck(id: String, count: Int): Pair<Words.Deck, Map<String, Fsrs.Card>> {
        val deck = Words.Deck(id, "英语常用词", (1..count).joinToString("\n") { "word$it\t释义$it" })
        val srs = (1..count).associate { i ->
            val key = id + "\u0000word$i"
            key to Fsrs.Card(state = Fsrs.State.Review, stability = 5.0, difficulty = 5.0,
                due = T0 - Fsrs.DAY_MS, lastReview = T0 - 10 * Fsrs.DAY_MS, reps = 2)
        }
        return deck to srs
    }

    // ---------------- 配额本身 ----------------

    @Test fun cardsKeepTheirFullLimit() {
        // 知识卡的每轮上限语义不变：newLimit / reviewLimit 仍是「知识卡每轮上限」
        val p = StudyPlan(newLimit = 20, reviewLimit = 100)
        assertEquals(20, p.limitFor(StudyPlan.Kind.CARDS, StudyPlan.Half.NEW))
        assertEquals(100, p.limitFor(StudyPlan.Kind.CARDS, StudyPlan.Half.REVIEW))
    }

    @Test fun wordsGetTheirOwnHalfShare() {
        val p = StudyPlan(newLimit = 20, reviewLimit = 100)
        assertEquals(10, p.limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.NEW))
        assertEquals(50, p.limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.REVIEW))
    }

    @Test fun wordQuotaNeverZeroWhileTheBaseIsPositive() {
        // 「0 个词」在界面上和「单词功能坏了」无法区分，所以给下限 1
        assertEquals(1, StudyPlan(newLimit = 1).limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.NEW))
        assertEquals(0, StudyPlan(newLimit = 0).limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.NEW))
        assertEquals(0, StudyPlan(newLimit = 0).wordCap(0))
    }

    @Test fun wordQuotaShrinksWithTheBase() {
        // 用户把上限从 100 调到 20，单词配额必须跟着缩，而不是固定占满。
        // wordCap 只依赖参数（实例方法但没有别的状态），所以这里直接传基准。
        assertTrue(StudyPlan().wordCap(20) < StudyPlan().wordCap(100))
    }

    // ---------------- 验收场景：1 张卡 + 19 个词 + 限额 100 ----------------

    @Test fun dueCardSurvivesNineteenWordsAtLimit100() {
        val plan = StudyPlan(newLimit = 20, reviewLimit = 100)
        val cards = listOf(dueCard(1))
        val (deck, srs) = dueWordDeck("builtin_en", 19)

        val limit = plan.limitFor(StudyPlan.Kind.CARDS, StudyPlan.Half.REVIEW)
        val queue = ReviewActivity.pickCards(cards, T0, limit, studyNew = false, plan = plan)
        val words = ReviewActivity.pickDueWords(listOf(deck), srs, T0,
            plan.limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.REVIEW), plan)

        assertEquals("那张到期知识卡必须在本轮队列里", 1, queue.size)
        assertEquals(1L, queue.single().id)
        // 单词只拿自己的配额（100/2 = 50，这里只有 19 个），不会把卡挤掉
        assertEquals(19, words.size)
    }

    @Test fun oldBugWouldHaveStuffedAll99WordSlots() {
        // 把旧公式写出来，确认它确实是「单词吃光、卡被挤到后面」的形态——
        // 这条用例是给后来人看的：去掉它之前先想清楚修复是不是被回退了。
        val plan = StudyPlan(reviewLimit = 100)
        val cardCount = 1
        val oldRemaining = (plan.reviewLimit - cardCount).coerceAtLeast(0)
        assertEquals(99, oldRemaining)
        // 现在单词的配额和知识卡数无关：卡有多少张都不影响它
        assertEquals(50, plan.limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.REVIEW))
    }

    @Test fun wordQuotaIsIndependentOfHowManyCardsAreDue() {
        val plan = StudyPlan(reviewLimit = 100)
        val wordLimit = plan.limitFor(StudyPlan.Kind.WORDS, StudyPlan.Half.REVIEW)
        for (n in listOf(0, 1, 40, 100)) {
            val (deck, srs) = dueWordDeck("d", 60)
            val words = ReviewActivity.pickDueWords(listOf(deck), srs, T0, wordLimit, plan)
            assertEquals("到期的卡有 $n 张时单词配额都该是 50", 50, words.size)
            val cards = (1..n).map { dueCard(it.toLong()) }
            ReviewActivity.pickCards(cards, T0, plan.limitFor(StudyPlan.Kind.CARDS, StudyPlan.Half.REVIEW), false, plan)
        }
    }

    // ---------------- 排队本身的边界 ----------------

    @Test fun newRoundOnlyTakesNewCards() {
        val plan = StudyPlan()
        val fresh = KnowledgeCard.KCData(2, "默认", "新卡", "答案", "manual", T0, Fsrs.Card())
        val picked = ReviewActivity.pickCards(listOf(dueCard(1), fresh), T0, 10, studyNew = true, plan = plan)
        assertEquals(listOf(2L), picked.map { it.id })
    }

    @Test fun reviewRoundExcludesNewCards() {
        val plan = StudyPlan()
        val fresh = KnowledgeCard.KCData(2, "默认", "新卡", "答案", "manual", T0, Fsrs.Card())
        val picked = ReviewActivity.pickCards(listOf(dueCard(1), fresh), T0, 10, studyNew = false, plan = plan)
        assertEquals(listOf(1L), picked.map { it.id })
    }

    @Test fun cardLimitStillCapsTheQueue() {
        val plan = StudyPlan(reviewLimit = 3)
        val cards = (1L..10L).map { dueCard(it) }
        assertEquals(3, ReviewActivity.pickCards(cards, T0, 3, false, plan).size)
    }

    @Test fun unselectedDeckIsSkipped() {
        val plan = StudyPlan(cardDecks = setOf("数学"))
        val cards = listOf(dueCard(1, "数学"), dueCard(2, "英语"))
        assertEquals(listOf(1L), ReviewActivity.pickCards(cards, T0, 10, false, plan).map { it.id })
    }

    @Test fun zeroLimitYieldsEmptyQueues() {
        val plan = StudyPlan(reviewLimit = 100)
        val (deck, srs) = dueWordDeck("d", 5)
        assertTrue(ReviewActivity.pickDueWords(listOf(deck), srs, T0, 0, plan).isEmpty())
        assertTrue(ReviewActivity.pickNewWords(listOf(deck), srs, 0, plan).isEmpty())
        assertTrue(ReviewActivity.pickCards(listOf(dueCard(1)), T0, 0, false, plan).isEmpty())
    }

    @Test fun wordDeckKeyUsesTheSharedSeparator() {
        // 键格式必须和 Words.key 一致，否则牌组过滤会静默失效（StudyCoach 那边踩过）
        assertEquals("d", ReviewActivity.deckKeyOf("d\u0000word"))
        assertEquals("裸词", ReviewActivity.deckKeyOf("裸词"))
    }
}