package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FocusReport（v2.3.1 专注报告）的验收测试。
 *
 * 这里钉的是**口径**，不是「能算出数」：
 *  - 学习时长只能按相邻采样间隔累计，且封顶 [FocusReport.GAP_CAP_MS]（不许出现「今天学了 23 小时」）；
 *  - 没回来的离开不算一次离开（下课 / 吃饭不算摸鱼）；
 *  - 没数据就说没数据（不写 0 分钟假装统计过）。
 */
class FocusReportTest {

    /** 固定基准时刻：2026-10-20 09:00 本地时间，避免测试依赖「今天是哪天」 */
    private val BASE: Long = run {
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(2026, java.util.Calendar.OCTOBER, 20, 9, 0, 0)
        cal.timeInMillis
    }

    private val DAY = FocusReport.dateOf(BASE)
    private fun min(n: Long) = n * 60_000L
    private fun s(at: Long, verdict: FocusGuard.Verdict, fp: String? = null) =
        FocusReport.FocusSample(at, 0L, verdict.code, fp)

    private fun report(list: List<FocusReport.FocusSample>, now: Long = BASE + min(120)) =
        FocusReport.compute(list, now, listOf(DAY))

    // ================= 1) 没数据 =================

    @Test fun emptyIsNoDataNotZero() {
        val r = report(emptyList())
        assertFalse(r.hasData)
        assertEquals(0, r.samples)
        assertEquals(0L, r.studyMs)
        assertNull("没数据时不该编一个 0%", r.studyRatio)
        assertTrue(r.summary.contains("还没有记录"))
    }

    // ================= 2) 学习时长 = 相邻间隔累计 =================

    @Test fun studyTimeIsSumOfGaps() {
        // 10 条学习采样、间隔 12 秒：9 个间隔 x 12s = 108s（最后一条没有下一条，不计）
        val list = (0 until 10).map { s(BASE + it * 12_000L, FocusGuard.Verdict.STUDY) }
        val r = report(list)
        assertEquals(9 * 12_000L, r.studyMs)
        assertEquals(1, r.studySegments)
        assertEquals(9 * 12_000L, r.longestMs)
    }

    @Test fun longGapIsCappedSoNoFakeHours() {
        // 两条学习采样，中间隔 5 小时（睡前忘了关的那一晚）：只能算封顶的 3 分钟
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(300), FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertEquals(FocusReport.GAP_CAP_MS, r.studyMs)
        assertTrue("绝不能把 5 小时记成学习：" + r.studyMs, r.studyMs < min(10))
    }

    @Test fun unsureSamplesDoNotAddTime() {
        // 「在学习」+「判不出来」：后一条不算学习，但前一条的间隔照算（人还在）
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 12_000L, FocusGuard.Verdict.UNSURE),
            s(BASE + 24_000L, FocusGuard.Verdict.UNSURE),
        )
        val r = report(list)
        assertEquals(12_000L, r.studyMs)
    }

    // ================= 3) 离开 =================

    @Test fun leaveCountedOnlyWhenUserComesBack() {
        // 学习 -> 4 轮不像学习 -> 回来学习：算 1 次离开，时长 = 起点到回来那一点
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 60_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 72_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 84_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 96_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(9), FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertEquals(1, r.leaves)
        assertEquals(min(9) - 60_000L, r.awayMs)
    }

    @Test fun leaveWithoutReturnIsNotCounted() {
        // 学习完之后就一直不像学习：「下课了」不是「离开 40 次」
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 60_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 72_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 84_000L, FocusGuard.Verdict.NOT_STUDY),
        )
        val r = report(list)
        assertEquals(0, r.leaves)
        assertEquals(0L, r.awayMs)
    }

    @Test fun unsureInsideLeaveDoesNotSplitIt() {
        // 不像学习 -> 判不出来 -> 不像学习 -> 回来学习：仍然只有 1 次离开
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 60_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + 72_000L, FocusGuard.Verdict.UNSURE),
            s(BASE + 84_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(5), FocusGuard.Verdict.STUDY),
        )
        assertEquals(1, report(list).leaves)
    }

    @Test fun twoLeavesCountedSeparately() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 60_000L, FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(3), FocusGuard.Verdict.STUDY),
            s(BASE + min(4), FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(6), FocusGuard.Verdict.STUDY),
        )
        assertEquals(2, report(list).leaves)
    }

    // ================= 4) 最长连续专注 =================

    @Test fun longestRunBreaksOnUnsureBreak() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(10), FocusGuard.Verdict.UNSURE),
            s(BASE + min(20), FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        // 第一条的下一步是 10 分钟后的 UNSURE：间隔封顶到 3 分钟，算进第一条的学习时长
        assertEquals(FocusReport.GAP_CAP_MS, r.longestMs)
        assertEquals(2, r.studySegments)
    }

    @Test fun continuousStudyKeepsGrowing() {
        val list = (0 until 30).map { s(BASE + it * 12_000L, FocusGuard.Verdict.STUDY) }
        val r = report(list)
        assertEquals(29 * 12_000L, r.longestMs)
        assertEquals(1, r.studySegments)
    }

    // ================= 5) 分布 =================

    @Test fun awayHourUsesLeaveStartHour() {
        // 09:00 学习 -> 10:05 离开 -> 10:20 回来：离开落在 10 点那一格
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(65), FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(80), FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertEquals(24, r.awayHour.size)
        assertEquals(1, r.awayHour[10])
        assertEquals(0, r.awayHour[9])
        assertEquals("10:00–11:00", FocusReport.busiestAwayHour(r))
    }

    @Test fun busiestHourIsNullWhenNoLeave() {
        assertNull(FocusReport.busiestAwayHour(report(listOf(s(BASE, FocusGuard.Verdict.STUDY)))))
    }

    @Test fun studyHourBucketsByMinuteOfDay() {
        val list = (0 until 5).map { s(BASE + it * 12_000L, FocusGuard.Verdict.STUDY) }
        val r = report(list)
        assertEquals(4 * 12_000L, r.studyHour[9])
        assertEquals("09:00–10:00", FocusReport.busiestStudyHour(r))
    }

    // ================= 6) 人话总结 =================

    @Test fun summarySaysDurationAndLeavesInPlainWords() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(6), FocusGuard.Verdict.NOT_STUDY),
            s(BASE + min(9), FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertTrue(r.summary.contains("学了"))
        assertTrue(r.summary.contains("离开过 1 次"))
        for (bad in listOf("专注率", "效率", "优秀", "评分")) {
            assertFalse("总结不该像考核：" + r.summary, r.summary.contains(bad))
        }
    }

    @Test fun summarySaysNoLeaveWhenStudiedStraight() {
        val list = (0 until 10).map { s(BASE + it * 12_000L, FocusGuard.Verdict.STUDY) }
        assertTrue(report(list).summary.contains("中间没离开过"))
    }

    // ================= 7) 序列化 / 裁剪 / 坏数据 =================

    @Test fun jsonRoundTripKeepsTagsAndNoBody() {
        val list = listOf(
            FocusReport.FocusSample(BASE, 12_000L, "study", "fp1"),
            FocusReport.FocusSample(BASE + 12_000L, 0L, "play", null),
        )
        val back = FocusReport.parse(FocusReport.toJson(list))
        assertEquals(2, back.size)
        assertEquals("study", back[0].tag)
        assertEquals("fp1", back[0].fp)
        assertNull(back[1].fp)
        // 落盘字段里不能出现任何正文：这条断言看着傻，但它是隐私底线的一部分
        val json = FocusReport.toJson(list)
        for (bad in listOf("text", "ocr", "content")) assertFalse(json.contains("\"" + bad + "\""))
    }

    @Test fun badRowsAreDroppedNotCrashed() {
        assertTrue(FocusReport.parse("不是 json").isEmpty())
        assertTrue(FocusReport.parse("").isEmpty())
        // 没有 at 的行扔掉；ms 超上限的夹回上限
        val json = "[{\"at\":0,\"ms\":1,\"tag\":\"study\"},{\"at\":" + BASE + ",\"ms\":999999999,\"tag\":\"study\"}]"
        val back = FocusReport.parse(json)
        assertEquals(1, back.size)
        assertEquals(FocusReport.GAP_CAP_MS, back[0].ms)
    }

    @Test fun trimKeepsRecentByTimeThenCount() {
        val old = FocusReport.FocusSample(BASE - 40 * Fsrs.DAY_MS, 1000L, "study")
        val fresh = FocusReport.FocusSample(BASE, 1000L, "study")
        assertEquals(listOf(fresh), FocusReport.trim(listOf(old, fresh), BASE))
        val many = (0 until 10).map { FocusReport.FocusSample(BASE + it, 1L, "study") }
        val capped = FocusReport.trim(many, BASE + 100, keepDays = 30, max = 3)
        assertEquals(3, capped.size)
        assertEquals(BASE + 9, capped.last().at)
    }

    // ================= 8) 只统计窗口里的日子 =================

    @Test fun samplesOutsideWindowAreIgnored() {
        val other = FocusReport.FocusSample(BASE - Fsrs.DAY_MS, 0L, "study")
        val here = FocusReport.FocusSample(BASE, 0L, "study")
        assertEquals(1, FocusReport.compute(listOf(other, here), BASE, listOf(DAY)).samples)
    }

    // ================= 9) 重排间隔（迁移 / 单测用） =================

    @Test fun respaceUsesNextSampleGapWithCap() {
        val list = listOf(
            FocusReport.FocusSample(BASE, 0L, "study"),
            FocusReport.FocusSample(BASE + 12_000L, 0L, "study"),
            FocusReport.FocusSample(BASE + min(30), 0L, "study"),
        )
        val fixed = FocusReport.respace(list)
        assertEquals(12_000L, fixed[0].ms)
        assertEquals(FocusReport.GAP_CAP_MS, fixed[1].ms)   // 30 分钟被夹到 3 分钟
        assertEquals(0L, fixed[2].ms)                      // 最后一条没有下一条
    }

    // ================= 10) 日期文案 =================

    @Test fun dayLabelIsHumanReadable() {
        assertEquals("10 月 20 日", FocusReport.dayLabel("20261020"))
        assertEquals("2026", FocusReport.dayLabel("2026"))   // 不合法就原样返回，不炸
    }

    @Test fun hhmmPadsHour() {
        assertEquals("09:05", FocusReport.hhmm(9 * 60 + 5))
        assertEquals("00:00", FocusReport.hhmm(0))
    }

    // ================= 11) 隔夜与息屏：绝不能算出「学了 8 小时」 =================

    /**
     * 用户晚上 22:50 还在学，然后合上手机睡觉，第二天 07:30 又打开继续学。
     * 中间那一夜**一条采样都没有**（息屏不采样，见 [FocusGuard.onScreenOff]），
     * 报告必须把这段间隔夹到 [FocusReport.GAP_CAP_MS]，绝不能把 8 小时 40 分算成学习时长。
     *
     * 这是「专注守护」最容易造出假数据的地方：不用前台计时器就靠这个封顶兜住。
     */
    @Test fun overnightGapIsCappedNotEightHours() {
        val night = java.util.Calendar.getInstance().apply {
            clear(); set(2026, java.util.Calendar.OCTOBER, 20, 22, 50, 0)
        }.timeInMillis
        val morning = java.util.Calendar.getInstance().apply {
            clear(); set(2026, java.util.Calendar.OCTOBER, 21, 7, 30, 0)
        }.timeInMillis
        val list = listOf(
            s(night, FocusGuard.Verdict.STUDY),
            s(night + 12_000L, FocusGuard.Verdict.STUDY),
            // —— 息屏，整夜没有采样 ——
            s(morning, FocusGuard.Verdict.STUDY),
            s(morning + 12_000L, FocusGuard.Verdict.STUDY),
        )
        // 两天都算进窗口，确认跨日也不会把整夜吞进去
        val r = FocusReport.compute(list, morning + min(30), listOf(FocusReport.dateOf(night), FocusReport.dateOf(morning)))

        val cap = FocusReport.GAP_CAP_MS
        // 第二天那两条自成一段：morning -> morning+12s 算 12 秒，最后一条没有下一条算 0。
        // 睡前那两条同理：night -> night+12s = 12 秒。整夜那一跳**不计**（跨日，见 stepAt）。
        assertEquals("整夜那一跳不该被算成任何一段学习", 24_000L, r.studyMs)
        assertTrue("绝不能算出 8 小时：" + r.studyMs, r.studyMs < 60 * min(1))
        // 两天的学习不该被连成「一次连续专注」
        assertEquals("跨夜要把连段断开", 2, r.studySegments)
        assertEquals("最长连续专注只有 12 秒", 12_000L, r.longestMs)
    }

    /** 同一天里两段学习之间夹一次长息屏，也只按封顶补，不按真实间隔补 */
    @Test fun longScreenOffInsideOneDayIsCapped() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(300), FocusGuard.Verdict.STUDY),   // 5 小时后才又打开
        )
        val r = report(list, BASE + min(310))
        // 同一天里 5 小时的空档：封顶兜住，只算 3 分钟
        assertEquals(FocusReport.GAP_CAP_MS, r.studyMs)
        assertEquals("同样的间隔在没有跨日时仍按封顶算，连段不分断", 1, r.studySegments)
    }

    /**
     * 同一天里只有两条采样、中间空了 10 小时（比如中午合上手机、晚上才打开）：
     * 这段空档算**一个封顶**（3 分钟），不是 10 小时。
     */
    @Test fun twoSamplesSameDayGiveOneCap() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + min(600), FocusGuard.Verdict.STUDY),
        )
        val r = report(list, BASE + min(610))
        assertEquals(FocusReport.GAP_CAP_MS, r.studyMs)
    }

    /**
     * 跨日且两天的采样各只有一条：**两边都不该算出时长** ——
     * 一条孤立的采样没有「下一条」可比，本来就无从知道学了多久。
     * 这条用例锁住的是「不许拿隔夜那一跳当学习时长」这个承诺。
     */
    @Test fun samplesOnDifferentDaysGiveNoStudyTime() {
        // 真正的「第二天」：BASE 是 09:00，加 25 小时落在次日 10:00
        val nextDay = BASE + 25 * 60 * min(1)
        assertTrue("前提：两条采样必须在不同日历日：" + FocusReport.dateOf(BASE) + " vs " + FocusReport.dateOf(nextDay),
            FocusReport.dateOf(BASE) != FocusReport.dateOf(nextDay))
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(nextDay, FocusGuard.Verdict.STUDY),
        )
        val r = FocusReport.compute(list, nextDay + min(10),
            listOf(FocusReport.dateOf(BASE), FocusReport.dateOf(nextDay)))
        assertEquals("隔夜那一跳不能算出任何学习时长", 0L, r.studyMs)
    }

    // ================= 12) 边界：锁屏 / 不可用 / 改设置 =================

    /**
     * 锁屏（息屏 / 服务停启 / 关开关 / 改设置）之后，前后两段采样**不算相连**。
     *
     * 这里的两条采样只隔 12 秒，看着完全正常 —— 但中间那 12 秒是在锁屏里过的：
     * 一条学习采样带着 cut，就说明它之后的时间线断了。补时长、连成一段都是编数据。
     * 这正是真机 smoke 抓到的错账：锁屏 245 秒后回来，报告把锁屏那一段又补了一次。
     */
    @Test fun cutStopsStudyGapAndSegment() {
        val list = listOf(
            FocusReport.FocusSample(BASE, 0L, FocusGuard.Verdict.STUDY.code, cut = true),
            s(BASE + 12_000L, FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertEquals("断点之后那一跳不许补学习时长", 0L, r.studyMs)
        assertEquals("断点必须断开连段", 2, r.studySegments)
        assertEquals(0L, r.longestMs)
    }

    /** 断点前那一段本身照常算：只有**跨过**断点的那一跳不算 */
    @Test fun cutKeepsStudyBeforeBoundary() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 12_000L, FocusGuard.Verdict.STUDY),
            s(BASE + 24_000L, FocusGuard.Verdict.STUDY).copy(cut = true),
            s(BASE + 36_000L, FocusGuard.Verdict.STUDY),
        )
        val r = report(list)
        assertEquals(2 * 12_000L, r.studyMs)
        assertEquals(2, r.studySegments)
    }

    /** 判不出来 / 没权限 / 没通道之后：不许把「离开」跨过断点收到一起 */
    @Test fun cutStopsAwayFromBridging() {
        val list = listOf(
            s(BASE, FocusGuard.Verdict.STUDY),
            s(BASE + 60_000L, FocusGuard.Verdict.NOT_STUDY).copy(cut = true),
            // —— 这里锁屏 / 没有读数，整段没有采样 ——
            s(BASE + min(30), FocusGuard.Verdict.STUDY),
        )
        val r = report(list, BASE + min(40))
        assertEquals("跨过断点之后不算「回来了」", 0, r.leaves)
        assertEquals(0L, r.awayMs)
    }

    /** 改模式 / 关一次开关再开：连续专注必须在断点处重新起算，不能拼成一段 */
    @Test fun configChangeSplitsLongestRun() {
        val first = (0 until 5).map { s(BASE + it * 12_000L, FocusGuard.Verdict.STUDY) }
        val second = (0 until 5).map {
            s(BASE + min(1) + it * 12_000L, FocusGuard.Verdict.STUDY)
        }
        val list = first.dropLast(1) + first.last().copy(cut = true) + second
        val r = report(list)
        assertEquals(8 * 12_000L, r.studyMs)   // 两段各自 4 个间隔
        assertEquals(2, r.studySegments)
        assertEquals(4 * 12_000L, r.longestMs)
    }

    /** 边界只落盘真实发生的那一条，且老文件没有 cut 字段时按「连续」读（向后兼容） */
    @Test fun cutIsOptionalInJsonAndBackwardCompatible() {
        val list = listOf(
            FocusReport.FocusSample(BASE, 12_000L, "study"),
            FocusReport.FocusSample(BASE + 12_000L, 0L, "play", cut = true),
        )
        val back = FocusReport.parse(FocusReport.toJson(list))
        assertEquals(2, back.size)
        assertFalse(back[0].cut)
        assertTrue("断点要能读回来，否则重启后又会跨过去补时长", back[1].cut)
        // 老版本写出来的行（没有 cut 键）必须仍然解析成「连续」
        val legacy = FocusReport.parse("[{\"at\":" + BASE + ",\"ms\":12000,\"tag\":\"study\"}]")
        assertEquals(1, legacy.size)
        assertFalse(legacy[0].cut)
    }
}
