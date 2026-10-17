package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K3 复习页 + 到期提醒：到期数、四档评分后的 due 推进、空队列、通知文案、时刻计算。
 *
 * 全部走纯函数（[ReviewNotify] 的 companion + [Fsrs] / [KnowledgeCard]），
 * 不碰 Android 那层 —— 所以这批测试在 JVM 上跑，不需要设备。
 */
class ReviewTest {

    private val T0 = 1_700_000_000_000L   // 固定基准时刻，避免测试跟着真实时钟漂

    /** 一张「N 天后到期」的卡（走一遍 Good 首刷，改due 模拟已排程） */
    private fun kc(id: Long, dueOffsetDays: Long = 3L, deck: String = "默认"): KnowledgeCard.KCData {
        val c = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, T0)
        return KnowledgeCard.KCData(id, deck, "正面$id", "背面$id", "manual", T0,
            c.copy(due = T0 + dueOffsetDays * Fsrs.DAY_MS))
    }

    /** 一张已经过期的卡 */
    private fun overdue(id: Long) = kc(id, dueOffsetDays = -1L)

    // ---------------- 到期数 ----------------

    @Test fun dueCountCountsOnlyDueCards() {
        val l = listOf(overdue(1), kc(2, 3L), kc(3, 10L))
        assertEquals(1, ReviewNotify.dueCount(l, T0))
    }

    @Test fun dueCountZeroOnEmptyQueue() {
        assertEquals(0, ReviewNotify.dueCount(emptyList(), T0))
    }

    @Test fun dueCountCountsEveryOverdueCard() {
        val l = (1L..5L).map { overdue(it) }
        assertEquals(5, ReviewNotify.dueCount(l, T0))
    }

    @Test fun newCardCountsAsDue() {
        // 刚存进去的卡就是 New 态，FSRS 判它恒到期 —— 存完就该能复习到
        val fresh = KnowledgeCard.KCData(9, "默认", "新卡", "答案", "manual", T0, Fsrs.Card())
        assertTrue(ReviewNotify.dueCount(listOf(fresh), T0) == 1)
    }

    // ---------------- 四档评分 → due 推进 ----------------

    @Test fun allFourRatingsPushDueForward() {
        // 到期的卡评完「良好」之后，due 必须离 T0 更远 —— 不管按哪一档都不该原地不动
        val base = overdue(1)
        for (r in ReviewNotify.RATINGS) {
            val after = Fsrs.review(base.fsrs, r, T0)
            assertTrue("$r 没推进 due", after.due > T0)
        }
    }

    @Test fun ratingOrderAgainHardGoodEasy() {
        // 四档的标签顺序 = FSRS 的 Again/Hard/Good/Easy（编码 1/2/3/4）
        assertEquals(listOf(Fsrs.Rating.Again, Fsrs.Rating.Hard, Fsrs.Rating.Good, Fsrs.Rating.Easy), ReviewNotify.RATINGS)
        assertEquals(listOf(1, 2, 3, 4), ReviewNotify.RATINGS.map { it.code })
    }

    @Test fun easySchedulesFurthestOut() {
        // 同一张卡同一时刻：简单应该排得比忘记远（这是 FSRS 的基本性质，页面照搬即可）
        val base = overdue(1)
        val again = Fsrs.review(base.fsrs, Fsrs.Rating.Again, T0).due
        val easy = Fsrs.review(base.fsrs, Fsrs.Rating.Easy, T0).due
        assertTrue("简单($easy) 应该比忘记($again) 远", easy > again)
    }

    @Test fun answeredCardNoLongerDue() {
        // 评完这一张之后，它自己不该再出现在今天的到期队列里（否则会无限重复）
        val base = overdue(1)
        val after = Fsrs.review(base.fsrs, Fsrs.Rating.Good, T0)
        assertFalse(Fsrs.isDue(after, T0))
    }

    @Test fun queueShrinksByOneAfterEachAnswer() {
        val l = listOf(overdue(1), overdue(2), overdue(3))
        val now = T0
        val before = ReviewNotify.dueCount(l, now)
        val answered = l.map { if (it.id == 1L) it.copy(fsrs = Fsrs.review(it.fsrs, Fsrs.Rating.Good, now)) else it }
        assertEquals(before - 1, ReviewNotify.dueCount(answered, now))
    }

    @Test fun dueFirstPutsOverdueBeforeFuture() {
        val l = listOf(kc(1, 30L), overdue(2), kc(3, 7L))
        val sorted = KnowledgeCard.dueFirst(l, T0).filter { it.isDue(T0) }
        assertEquals(listOf(2L), sorted.map { it.id })
    }

    // ---------------- 空队列 ----------------

    @Test fun emptyQueueShowsDoneHeader() {
        assertEquals(ReviewNotify.HEADER_EMPTY, ReviewNotify.header(0))
        assertEquals(ReviewNotify.HEADER_EMPTY, ReviewNotify.header(-1))   // 负数也不该拼出「到期 -1 张」
    }


    // ---------------- 通知文案 ----------------

    @Test fun notificationTitleCarriesDueCount() {
        // v1.85：标题从「知识卡复习 · N 张」改成「复习 · N 项」—— 复习页现在也过到期的单词
        assertEquals("复习 · 3 项到期", ReviewNotify.title(3))
        assertEquals("复习 · 1 项到期", ReviewNotify.title(1))
    }

    @Test fun notificationBodyEmptyWhenNothingDue() {
        // 没到期卡就一条都不发，正文也不该有内容
        assertEquals("", ReviewNotify.body(0))
    }

    @Test fun shouldNotifyNeedsBothSwitchAndDue() {
        assertTrue(ReviewNotify.shouldNotify(true, 1))
        assertFalse(ReviewNotify.shouldNotify(true, 0))   // 没到期卡 → 不发
        assertFalse(ReviewNotify.shouldNotify(false, 5))  // 开关关了 → 不发
    }

    @Test fun channelAndRequestIdsAreDistinct() {
        // REQ 撞了会让 PendingIntent 互相覆盖（简报用的是 2401/2402）
        assertTrue(ReviewNotify.NOTI_ID != 60)
        assertTrue(ReviewNotify.CHANNEL != Briefing.CHANNEL)
        assertEquals("复习提醒", ReviewNotify.CHANNEL_NAME)
    }

    // ---------------- 时刻计算 ----------------

    @Test fun defaultCheckTimeIsEightPm() {
        assertEquals(20 * 60, ReviewNotify.DEFAULT_MIN)
        assertEquals("20:00", ReviewNotify.fmtMin(ReviewNotify.DEFAULT_MIN))
    }

    @Test fun delayToCountsForwardWithinSameDay() {
        // 早上 8 点排晚上 8 点那一轮 → 还有 12 小时
        assertEquals(12 * 60, ReviewNotify.delayTo(8 * 60, 20 * 60))
    }

    @Test fun delayToRollsToTomorrowWhenPassed() {
        // 已经过了 8 点（22:00）→ 明天 8 点，还有 10 小时
        assertEquals(10 * 60, ReviewNotify.delayTo(22 * 60, 8 * 60))
    }

    @Test fun delayToOnExactMinuteGoesToTomorrow() {
        // 刚好等于目标时刻按「今天已经过了」算 —— 否则 set() 立刻响，然后每分钟死循环
        assertEquals(1440, ReviewNotify.delayTo(20 * 60, 20 * 60))
    }

    @Test fun nextCheckAtIsNowPlusDelay() {
        val at = ReviewNotify.nextCheckAt(T0, 8 * 60, 20 * 60)
        assertEquals(T0 + 12 * 60 * 60_000L, at)
    }

    @Test fun normMinWrapsAroundMidnight() {
        assertEquals(0, ReviewNotify.normMin(1440))
        assertEquals(1439, ReviewNotify.normMin(-1))
    }
}