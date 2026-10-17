package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.85：词卡三档评分接进 FSRS 的纯逻辑回归。纯 JUnit4，不碰 Android。
 * 盯四件事：评分 → [Fsrs.Rating] 的映射、评过之后不再到期、到期计数、以及 [Words.mark]
 * 「模糊不清零 streak」这条行为（顺带放在这里测）。
 */
class WordSrsTest {

    private val now = 1_700_000_000_000L   // 固定时刻，别用真实时钟

    @Test fun gradeMapsToFsrsRating() {
        // 左滑不认识 → Again；下滑模糊 → Hard；右滑认识 → Good；Easy 不出现
        assertEquals(Fsrs.Rating.Again, WordSrs.rating(SwipeJudge.Grade.UNKNOWN))
        assertEquals(Fsrs.Rating.Hard, WordSrs.rating(SwipeJudge.Grade.FUZZY))
        assertEquals(Fsrs.Rating.Good, WordSrs.rating(SwipeJudge.Grade.KNOWN))
        // 上滑只关闭、不评分
        assertNull(WordSrs.rating(SwipeJudge.Grade.CLOSE))
    }

    @Test fun gradedKeyIsNoLongerDue() {
        val card = Words.Card("abandon", "v. 放弃")
        val k = Words.key("en", card)
        // 还没有记录：按新卡算，到期
        assertTrue(WordSrs.isDue(emptyMap(), k, now))
        // 评「认识」一次：排到将来，不再到期
        val after = WordSrs.apply(emptyMap(), k, SwipeJudge.Grade.KNOWN, now)
        assertTrue(after.containsKey(k))
        assertFalse(WordSrs.isDue(after, k, now))
        assertEquals(0, WordSrs.dueCount(after, now))
    }

    @Test fun closeGradeWritesNothing() {
        val k = Words.key("en", Words.Card("abandon", ""))
        // 上滑（CLOSE）不写：返回的还是原来那张表（这里就是空表）
        assertTrue(WordSrs.apply(emptyMap(), k, SwipeJudge.Grade.CLOSE, now).isEmpty())
    }

    /**
     * 上滑跳过**已经有记录**的词：不新建、也不改动已有那条 —— FSRS 的 state / stability /
     * due / reps 全部原样，到期判定与到期数也不变。跳过既然不评分，就不该动复习节奏。
     */
    @Test fun closeGradeLeavesExistingSrsRecordUntouched() {
        val k = Words.key("en", Words.Card("give", "给"))
        // 先评一次「认识」：有了记录、且排到将来（不再到期）
        val graded = WordSrs.apply(emptyMap(), k, SwipeJudge.Grade.KNOWN, now)
        val before = graded.getValue(k)
        assertFalse(WordSrs.isDue(graded, k, now))
        // 再上滑跳过：整条记录逐字段不变
        val after = WordSrs.apply(graded, k, SwipeJudge.Grade.CLOSE, now)
        assertEquals(graded, after)
        assertEquals(before, after.getValue(k))
        assertFalse(WordSrs.isDue(after, k, now))
        assertEquals(WordSrs.dueCount(graded, now), WordSrs.dueCount(after, now))
    }

    @Test fun dueCountAndDueKeysOnlySeeDueRecords() {
        val past = Fsrs.Card(state = Fsrs.State.Review, stability = 5.0, difficulty = 5.0, due = now - 1_000)
        val future = Fsrs.Card(state = Fsrs.State.Review, stability = 5.0, difficulty = 5.0, due = now + 100_000)
        val m = mapOf("a" to past, "b" to future)
        assertEquals(1, WordSrs.dueCount(m, now))
        assertEquals(setOf("a"), WordSrs.dueKeys(m, now))
        assertTrue(WordSrs.isDue(m, "a", now))
        assertFalse(WordSrs.isDue(m, "b", now))
    }

    @Test fun pickDueUsesRecordedDueCardsOnly() {
        val a = "en" to Words.Card("a", "1")
        val b = "en" to Words.Card("b", "2")
        val cards = listOf(a, b)
        // 一条记录都没有 → 没有到期项 → null（调用方退回 Words.pick 加权）
        assertNull(WordSrs.pickDue(cards, emptyMap(), emptyMap(), now))
        // a 有一条已到期的记录 → 只从 a 里挑
        val due = mapOf(Words.key(a.first, a.second) to Fsrs.Card(state = Fsrs.State.Review, stability = 1.0, difficulty = 5.0, due = now - 1))
        assertEquals(a, WordSrs.pickDue(cards, due, emptyMap(), now, Random(1)))
        // b 的记录还没到期 → 没有到期项 → null
        val future = mapOf(Words.key(b.first, b.second) to Fsrs.Card(state = Fsrs.State.Review, stability = 1.0, difficulty = 5.0, due = now + 100_000))
        assertNull(WordSrs.pickDue(cards, future, emptyMap(), now))
    }

    @Test fun fuzzyKeepsStreakAndCountsSeen() {
        var s: Words.Stat? = null
        s = Words.mark(s, SwipeJudge.Grade.KNOWN, 1)
        s = Words.mark(s, SwipeJudge.Grade.KNOWN, 2)
        assertEquals(2, s.streak)
        // 模糊：streak 保持不变、fuzzy + 1，seen / last 照旧
        s = Words.mark(s, SwipeJudge.Grade.FUZZY, 3)
        assertEquals(2, s.streak)
        assertEquals(1, s.fuzzy)
        assertEquals(3, s.seen)
        assertEquals(3L, s.last)
        // 只有「不认识」才清零 streak（fuzzy 记着）
        s = Words.mark(s, SwipeJudge.Grade.UNKNOWN, 4)
        assertEquals(0, s.streak)
        assertEquals(4, s.seen)
        assertEquals(1, s.fuzzy)
        // 上滑（CLOSE）兜底：原样返回，一个字都不动
        assertEquals(s, Words.mark(s, SwipeJudge.Grade.CLOSE, 9))
    }

    @Test fun countTodayHandlesThreeGrades() {
        var t = Words.Today()
        t = Words.countToday(t, 20260928, SwipeJudge.Grade.KNOWN)
        t = Words.countToday(t, 20260928, SwipeJudge.Grade.FUZZY)
        t = Words.countToday(t, 20260928, SwipeJudge.Grade.UNKNOWN)
        assertEquals(3, t.done)    // 模糊计入 done
        assertEquals(1, t.known)   // 模糊不计入 known
        assertEquals(1, t.fuzzy)
        // 换天清零，再从头计
        assertEquals(Words.Today(20260929, 1, 0, 1), Words.countToday(t, 20260929, SwipeJudge.Grade.FUZZY))
    }

    /** 上滑跳过不计入当天的任何一项：done / known / fuzzy 一个都不加（也不会碰其他档）。 */
    @Test fun countTodayIgnoresClose() {
        val t = Words.Today(20260928, 3, 1, 1)
        assertEquals(t, Words.countToday(t, 20260928, SwipeJudge.Grade.CLOSE))
        // 换天也只是正常清零，CLOSE 本身不贡献任何一项
        assertEquals(Words.Today(20260929, 0, 0, 0), Words.countToday(t, 20260929, SwipeJudge.Grade.CLOSE))
    }

    @Test fun statJsonCarriesFuzzyAndDefaultsOldData() {
        val m = mapOf("k" to Words.Stat(2, 5, 99, 3))
        assertEquals(m, Words.statsFromJson(Words.statsToJson(m)))
        // 老数据只有 3 位 → fuzzy 当 0
        assertEquals(Words.Stat(1, 2, 9, 0), Words.statsFromJson("""{"k":[1,2,9]}""")["k"])
    }
}
