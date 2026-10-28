package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StudyCoach：今天该复习多少（口径与 StudyActivity 一致）、每日新学量预算、积压牌组排序、人话总结。
 * 全是纯函数，一个固定 now 就够，不用 mock 时钟。
 */
class StudyCoachTest {

    private val now = 1_700_000_000_000L

    /** 造一张知识卡：[dueInDays] 是相对 now 的到期偏移，[new] 为真造一张没复习过的新卡。 */
    private fun card(deck: String, dueInDays: Long, new: Boolean = false, id: Long = 0L): KnowledgeCard.KCData {
        val f = if (new) Fsrs.Card() else Fsrs.Card(
            state = Fsrs.State.Review,
            stability = 10.0,
            difficulty = 5.0,
            due = now + dueInDays * StudyCoach.DAY_MS,
            lastReview = now - StudyCoach.DAY_MS,
            reps = 2,
        )
        return KnowledgeCard.KCData(id, deck, "front" + id, "back", "manual", now, f)
    }

    private fun plan(newLimit: Int = 20, reviewLimit: Int = 100, words: Boolean = false, cards: Boolean = true) =
        StudyPlan(words = words, cards = cards, newLimit = newLimit, reviewLimit = reviewLimit)

    /** 一张已复习过、已到期的词卡；键格式跟 Words.key 一致："牌组\u0000单词"。 */
    /**
     * 一张已复习、已到期的词卡；键格式跟 Words.key 一致："牌组\u0000单词"。
     *
     * [dueInDays] 默认 -1（昨天）：suggest 按「今天 0 点」判到期，`due = now` 这种午后时刻
     * 其实还没过日界，会判成未到期——夹具必须造在昨天，才代表「今天该还的债」。
     */
    private fun dueWord(deck: String, word: String, dueInDays: Long = -1L): Pair<String, Fsrs.Card> =
        (deck + "\u0000" + word) to Fsrs.Card(
            state = Fsrs.State.Review, stability = 5.0, difficulty = 5.0,
            due = now + dueInDays * StudyCoach.DAY_MS, lastReview = now - StudyCoach.DAY_MS, reps = 1,
        )

    // ---------------- 今天该复习多少 ----------------

    @Test fun countsOnlyReviewedAndDueCards() {
        val cards = listOf(
            card("默认", -1, id = 1),             // 逾期
            card("默认", 0, id = 2),              // 今天到期
            card("默认", 5, id = 3),              // 未到期
            card("默认", 0, new = true, id = 4),  // 新卡：走新学额度，不算复习
        )
        val a = StudyCoach.suggest(cards, emptyMap(), plan(), now)
        assertEquals(2, a.backlog)
        assertEquals(2, a.todayReview)
        assertEquals(1, a.newDue)
    }

    @Test fun todayReviewCappedButBacklogHonest() {
        val cards = (1..10).map { card("默认", -1, id = it.toLong()) }
        val a = StudyCoach.suggest(cards, emptyMap(), plan(reviewLimit = 4), now)
        assertEquals(10, a.backlog)
        assertEquals(4, a.todayReview)
        assertTrue(a.summary.contains("做不完"))
    }

    @Test fun unseenWordsAreNewNotDue() {
        // 词库里几千个没学过的词不能一夜之间变成「今天到期」，否则这个数字毫无意义
        val words = mapOf(
            "分级词库\u0000abandon" to Fsrs.Card(),
            dueWord("分级词库", "ability", -1L),
        )
        val a = StudyCoach.suggest(emptyList(), words, plan(words = true, cards = false), now)
        assertEquals(1, a.backlog)
        assertEquals(1, a.newDue)
    }

    @Test fun planDeckFilterApplies() {
        val cards = listOf(card("数学", -1, id = 1), card("英语", -1, id = 2))
        val a = StudyCoach.suggest(cards, emptyMap(), plan().copy(cardDecks = setOf("数学")), now)
        assertEquals(1, a.backlog)
    }

    @Test fun cardsOffMeansZero() {
        val cards = listOf(card("默认", -1, id = 1))
        assertEquals(0, StudyCoach.suggest(cards, emptyMap(), plan(cards = false), now).backlog)
    }

    // ---------------- 每日新学量 ----------------

    @Test fun newBudgetZeroWhenBacklogExceedsReviewLimit() {
        assertEquals(0, StudyCoach.newBudget(plan(newLimit = 20, reviewLimit = 10), backlog = 11))
    }

    @Test fun newBudgetKeepsQuarterFloor() {
        val v = StudyCoach.newBudget(plan(newLimit = 20, reviewLimit = 100), backlog = 90)
        assertTrue("$v 应 >= 5（20 的四分之一保底）", v >= 5)
    }

    @Test fun newBudgetMoreWhenIdle() {
        val idle = StudyCoach.newBudget(plan(newLimit = 20, reviewLimit = 100), backlog = 0)
        val busy = StudyCoach.newBudget(plan(newLimit = 20, reviewLimit = 100), backlog = 60)
        assertTrue("空闲时配额应更多：$idle vs $busy", idle > busy)
        assertTrue("不超过 newLimit 两倍：$idle", idle <= 40)
    }

    @Test fun newBudgetZeroWhenNewCardsDisabled() {
        assertEquals(0, StudyCoach.newBudget(plan(newLimit = 0), backlog = 0))
    }

    // ---------------- 牌组积压排序 ----------------

    @Test fun overdueDeckRanksFirst() {
        val cards = listOf(
            card("甲", 0, id = 1), card("甲", 0, id = 2), card("甲", 0, id = 3),
            card("乙", -9, id = 4),
        )
        val a = StudyCoach.suggest(cards, emptyMap(), plan(), now)
        assertEquals("乙", a.decks.first().name)
        assertEquals(1, a.decks.first().overdue)
        assertEquals(3, a.decks.first { it.name == "甲" }.dueToday)
    }

    @Test fun finishedDecksAreHidden() {
        assertTrue(StudyCoach.suggest(listOf(card("学完了", 30, id = 1)), emptyMap(), plan(), now).decks.isEmpty())
    }

    @Test fun deckListCapped() {
        val cards = (1..12).map { card("D$it", -1, id = it.toLong()) }
        assertEquals(StudyCoach.TOP_DECKS, StudyCoach.suggest(cards, emptyMap(), plan(), now).decks.size)
    }

    // ---------------- 人话总结 ----------------

    @Test fun summarySaysDoneWhenNothingDue() {
        val a = StudyCoach.suggest(emptyList(), emptyMap(), plan(), now)
        assertNotNull(a.summary)
        assertTrue(a.summary.contains("没有到期"))
    }

    @Test fun summaryCarriesActionableNumbers() {
        val cards = (1..3).map { card("默认", -1, id = it.toLong()) }
        val a = StudyCoach.suggest(cards, emptyMap(), plan(newLimit = 10), now)
        assertTrue("应给出可执行数字：" + a.summary, a.summary.contains("3"))
        assertTrue(a.summary.contains("默认"))
    }

    @Test fun summaryStopsNewCardsWhenBacklogged() {
        val cards = (1..20).map { card("默认", -1, id = it.toLong()) }
        val a = StudyCoach.suggest(cards, emptyMap(), plan(newLimit = 20, reviewLimit = 10), now)
        assertEquals(0, a.newPerDay)
        assertTrue(a.summary.contains("先把积压"))
    }
}
