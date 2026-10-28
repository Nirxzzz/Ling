package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WeakPoints：按知识点聚合、近因加权排名、7/30 天趋势、空数据、坏 JSON 容错。 */
class WeakPointsTest {

    private val now = 1_700_000_000_000L
    private val day = WeakPoints.DAY_MS

    /** [daysAgo] 天前的一条记录。 */
    private fun q(knowledge: String, correct: Boolean, daysAgo: Double, id: Long = 0L) =
        WrongLog.Q(id, "题" + id, knowledge, "", correct, now - (daysAgo * day).toLong(), "answer")

    // ---------------- 聚合与正确率 ----------------

    @Test fun aggregatesAccuracyPerKnowledge() {
        val log = listOf(
            q("导数", true, 0.0, 1), q("导数", false, 0.0, 2), q("导数", true, 0.0, 3),
            q("极限", false, 0.0, 4), q("极限", false, 0.0, 5),
        )
        val r = WeakPoints.report(log, now)
        assertEquals(5, r.total)
        assertEquals(3, r.wrong)
        val byName = r.byKnowledge.associateBy { it.name }
        assertEquals(3, byName.getValue("导数").total)
        assertEquals(1, byName.getValue("导数").wrong)
        assertEquals(2.0 / 3.0, byName.getValue("导数").accuracy, 1e-9)
        assertEquals(0.0, byName.getValue("极限").accuracy, 1e-9)
    }

    @Test fun emptyKnowledgeGoesToUnknownBucket() {
        // 识别不出考点的记录仍然是「错了」，丢掉会让正确率虚高
        val r = WeakPoints.report(listOf(q("", false, 0.0, 1)), now)
        assertEquals(WeakPoints.UNKNOWN, r.byKnowledge.single().name)
        assertEquals(1, r.wrong)
    }

    @Test fun byKnowledgeIsSortedByName() {
        val r = WeakPoints.report(listOf(q("丙", true, 0.0, 1), q("甲", true, 0.0, 2), q("乙", true, 0.0, 3)), now)
        val names = r.byKnowledge.map { it.name }
        assertEquals(names.sorted(), names)
    }

    // ---------------- 近因加权 ----------------

    @Test fun recencyHalvesAtHalfLife() {
        assertEquals(1.0, WeakPoints.recency(0.0), 1e-9)
        assertEquals(0.5, WeakPoints.recency(WeakPoints.HALF_LIFE_DAYS), 1e-9)
        assertEquals(0.25, WeakPoints.recency(WeakPoints.HALF_LIFE_DAYS * 2), 1e-9)
    }

    @Test fun recentMistakeOutranksOldMistakeWithSameCount() {
        // 都是错 2 次，但「昨天还在错」比「两个月前错」更该补
        val log = listOf(
            q("极限", false, 1.0, 1), q("极限", false, 2.0, 2),
            q("导数", false, 60.0, 3), q("导数", false, 61.0, 4),
        )
        val r = WeakPoints.report(log, now)
        assertEquals("极限", r.topWeak.first().name)
    }

    @Test fun recentMistakeOutranksMoreOldMistakes() {
        // 近因权重必须真的起作用：5 次陈旧错误 vs 1 次昨天的错误
        val old = (1..5).map { q("陈旧", false, 200.0 + it, it.toLong()) }
        val fresh = listOf(q("新鲜", false, 1.0, 99))
        val r = WeakPoints.report(old + fresh, now)
        assertEquals("新鲜", r.topWeak.first().name)
    }

    @Test fun futureTimestampsGetNoBonusWeight() {
        // 时钟回拨 / 测试写死未来时间：按 0 天算，不能超过「今天刚错」
        val r = WeakPoints.report(listOf(q("甲", false, -5.0, 1), q("乙", false, 0.0, 2)), now)
        assertEquals(WeakPoints.recency(0.0), r.byKnowledge.first { it.name == "甲" }.weight, 1e-9)
        assertEquals(2, r.topWeak.size)
    }

    @Test fun topWeakOnlyWrongPointsAndCapped() {
        val log = (1..8).map { q("点" + it, false, it.toDouble(), it.toLong()) } + q("全对", true, 1.0, 99)
        val r = WeakPoints.report(log, now)
        assertEquals(WeakPoints.TOP, r.topWeak.size)
        assertTrue(r.topWeak.none { it.name == "全对" })
        assertTrue(r.byKnowledge.any { it.name == "全对" })
    }

    @Test fun correctOnlyKnowledgeHasNoRecentWrongTime() {
        val r = WeakPoints.report(listOf(q("导数", true, 0.0, 1)), now)
        assertEquals(0L, r.byKnowledge.single().recentWrongAt)
    }

    // ---------------- 趋势 ----------------

    @Test fun dailyCurveCoversWindowEndingToday() {
        val log = listOf(q("导数", true, 0.0, 1), q("导数", false, 0.0, 2), q("导数", true, 3.0, 3))
        val days = WeakPoints.daily(log, 7, now)
        assertEquals(7, days.size)
        assertEquals(WeakPoints.dayStart(now), days.last().day)
        assertEquals(2, days.last().total)
        assertEquals(1, days.last().correct)
        assertEquals(1, days[3].total)
        assertEquals(0, days[5].total)
    }

    @Test fun recordsOutsideWindowIgnored() {
        assertEquals(0, WeakPoints.daily(listOf(q("导数", false, 40.0, 1)), 30, now).sumOf { it.total })
    }

    @Test fun windowAccuracyUsesCountsNotDayAverage() {
        // 「一天 9 题全对 + 一天 1 题全错」必须算 90%，不能按天平均算 50%
        val days = listOf(WeakPoints.Day(0L, 9, 9), WeakPoints.Day(day, 1, 0))
        assertEquals(0.9, WeakPoints.windowAcc(days), 1e-9)
        assertEquals(-1.0, WeakPoints.windowAcc(emptyList()), 1e-9)
    }

    @Test fun trendBothWindowsFilled() {
        val t = WeakPoints.curves(listOf(q("导数", false, 1.0, 1), q("导数", true, 1.0, 2)), now)
        assertEquals(7, t.week.size)
        assertEquals(30, t.month.size)
        assertEquals(0.5, t.weekAcc, 1e-9)
        assertEquals(0.5, t.monthAcc, 1e-9)
    }

    @Test fun knownAccCarriesLastKnownValue() {
        val days = listOf(WeakPoints.Day(0L, 0, 0), WeakPoints.Day(day, 2, 1), WeakPoints.Day(day * 2, 0, 0))
        val acc = WeakPoints.knownAcc(days)
        assertNull(acc[0])
        assertEquals(0.5, acc[1]!!, 1e-9)
        assertEquals(0.5, acc[2]!!, 1e-9)
    }

    // ---------------- 空数据 ----------------

    @Test fun emptyLogYieldsEmptyReport() {
        val r = WeakPoints.report(emptyList(), now)
        assertEquals(0, r.total)
        assertEquals(0, r.wrong)
        assertEquals(-1.0, r.accuracy, 1e-9)
        assertTrue(r.byKnowledge.isEmpty())
        assertTrue(r.topWeak.isEmpty())
        assertEquals(7, r.trend.week.size)
        assertEquals(-1.0, r.trend.weekAcc, 1e-9)
    }

    @Test fun buildIsReportAlias() {
        val log = listOf(q("导数", false, 0.0, 1))
        assertEquals(WeakPoints.report(log, now), WeakPoints.build(log, now))
    }

    // ---------------- 摘要 ----------------

    @Test fun textSaysNoDataWhenEmpty() {
        assertTrue(WeakPoints.text(WeakPoints.report(emptyList(), now)).contains("还没有答题记录"))
    }

    @Test fun textMentionsTopWeakWithNumbers() {
        val log = listOf(
            q("极限", false, 1.0, 1), q("极限", true, 1.0, 2), q("极限", false, 1.0, 3),
            q("导数", true, 1.0, 4),
        )
        val txt = WeakPoints.text(WeakPoints.report(log, now))
        assertTrue(txt.contains("极限"))
        assertTrue(txt, txt.contains("错 2/3"))
        assertTrue(txt, txt.contains("33%") || txt.contains("34%"))
        assertTrue(txt, txt.contains("昨天"))
    }

    @Test fun textReportsNoWeakPointWhenAllCorrect() {
        assertTrue(WeakPoints.text(WeakPoints.report(listOf(q("导数", true, 1.0, 1)), now)).contains("没有错题"))
    }

    // ---------------- 缓存 JSON ----------------

    @Test fun jsonRoundTripPreservesReport() {
        val log = listOf(
            q("极限", false, 1.0, 1), q("极限", true, 8.0, 2),
            q("导数", false, 3.0, 3), q("导数", true, 20.0, 4),
        )
        val r = WeakPoints.report(log, now)
        val back = WeakPoints.fromJson(WeakPoints.toJson(r))
        assertEquals(r.total, back?.total)
        assertEquals(r.wrong, back?.wrong)
        assertEquals(r.byKnowledge, back?.byKnowledge)
        assertEquals(r.topWeak, back?.topWeak)
        assertEquals(r.trend, back?.trend)
    }

    @Test fun fromJsonRejectsGarbageInsteadOfEmptyReport() {
        // 返回 null 让调用方重算；返回「空报告」会被误当成「真的没答过题」
        assertNull(WeakPoints.fromJson(null))
        assertNull(WeakPoints.fromJson(""))
        assertNull(WeakPoints.fromJson("not json"))
    }
}
