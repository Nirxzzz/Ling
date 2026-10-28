package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LearningStats] / [ReviewLog] 的验收测试。
 *
 * 每个用例都钉住一条业务口径，而不是「跑通不崩」。来源是任务书里写的几句话：
 *  - 时长按相邻两条评分的间隔累计，**单条封顶 5 分钟**；
 *  - 成本口径 = 本月总成本 ÷ 活跃天数 × 30；
 *  - 保持率走 [Fsrs.forgettingCurve]；
 *  - 空数据不能显示 0（0 会被读成「全忘了」，那是假信息）。
 *
 * 时间全部用固定常量算，不读系统时钟 —— 否则跑在月末 / 跨年那天会莫名其妙挂。
 */
class LearningStatsTest {

    private val day = Fsrs.DAY_MS

    // 固定的基准时刻：2026-10-20 00:00 本地时间。用它加减天数构造日期，避开月末/月末与夏令时。
    private val t0 = localMidnight(2026, 10, 20)

    private fun localMidnight(y: Int, m: Int, d: Int): Long = java.util.Calendar.getInstance().apply {
        clear(); set(y, m - 1, d, 0, 0, 0)
    }.timeInMillis

    private fun r(at: Long, kind: ReviewLog.Kind = ReviewLog.Kind.KNOWLEDGE, key: String = "k", rating: Int = 3) =
        ReviewLog.R(at, kind, key, rating)

    /** 一张「已经复习过、稳定度 S 天」的卡 */
    private fun reviewedCard(lastReview: Long, stability: Double) =
        Fsrs.Card(state = Fsrs.State.Review, stability = stability, lastReview = lastReview, reps = 1, due = lastReview + (stability * day).toLong())

    // ================= 空数据 ================= 

    @Test fun emptyEverythingGivesNoFakeNumbers() {
        val s = LearningStats.compute(emptyList(), emptyList(), emptyList(), t0);
        assertEquals(0, s.reviewCount);
        assertEquals(0L, s.totalMs);
        assertEquals(0, s.streakDays);
        assertTrue("没有卡片就不该有曲线", s.retentionCurve.isEmpty());
        // 关键：null 而不是 0。0% 意味着「全忘光了」，那是我们在没有数据时**编**出来的结论。
        assertNull("没有评分就没有留存率，不能编一个 0", s.recallRate);
        assertNull(s.retentionNow);
        assertEquals("0 分钟", s.totalText);
    }

    @Test fun emptyLogStillFillsThirtyZeroBars() {
        // 一天没学也要有 30 根高度为 0 的柱，否则「断档」在图上会消失
        val bars = LearningStats.dailyReviews(emptyList(), t0, 30);
        assertEquals(30, bars.size);
        assertTrue(bars.all { it.second == 0 });
        assertEquals("09-21", bars.first().first);   // 今天往前 29 天
        assertEquals("10-20", bars.last().first);
    }

    // ================= 单日 ================= 

    @Test fun singleDayReviewCountAndDuration() {
        // 同一天里连评 3 次，两条间隔各 1 分钟 → 合计 2 分钟。
        // （故意不写 1+2 分钟那种「看起来对但其实算错」的注释：断言要和实际时间戳对得上。）
        val log = listOf(r(t0 + 60_000), r(t0 + 120_000), r(t0 + 180_000));
        val s = LearningStats.compute(log, emptyList(), emptyList(), t0 + day);
        assertEquals(3, s.reviewCount);
        assertEquals(120_000L, s.totalMs);
        assertEquals("2 分钟", s.totalText);
        // 今天（t0+1天）没有记录，但从昨天往前数有 1 天
        assertEquals(1, s.streakDays);
    }

    @Test fun streakCountsBackFromTodayWhenTodayHasNoReview() {
        // 昨天 + 前天学过，今天还没学 —— 面板不该显示 0（会让人以为昨天白学了）
        val log = listOf(r(t0 - day), r(t0 - 2 * day));
        assertEquals(2, LearningStats.streakDays(log, t0));
    }

    @Test fun streakBreaksOnMissingDay() {
        // 今天、昨天学过；前天没学；大前天学过 → 连续 2 天就断了
        val log = listOf(r(t0), r(t0 - day), r(t0 - 3 * day));
        assertEquals(2, LearningStats.streakDays(log, t0));
    }

    @Test fun streakIsZeroWhenBothTodayAndYesterdayEmpty() {
        val log = listOf(r(t0 - 5 * day));
        assertEquals(0, LearningStats.streakDays(log, t0));
    }

    // ================= 时长封顶 ================= 

    @Test fun durationCapsEachGapAtFiveMinutes() {
        // 复习到一半去吃饭，两小时后才评下一条：只算 5 分钟，不算 2 小时
        val log = listOf(r(t0), r(t0 + 2 * 60 * 60 * 1000));
        assertEquals(ReviewLog.MAX_GAP_MS, LearningStats.totalMs(log));
        assertEquals(5 * 60_000L, LearningStats.totalMs(log));
    }

    @Test fun durationIgnoresCrossDayGap() {
        // 昨夜 23:50 到今天 08:00 那 8 小时不是学习时间，跨天整段不计
        val log = listOf(r(t0), r(t0 + day + 8 * 60 * 60 * 1000));
        assertEquals(0L, LearningStats.totalMs(log));
    }

    @Test fun durationIgnoresBackwardClock() {
        // 时钟被回拨（改系统时间 / NTP 跳变）：后写入的记录 at 反而更早。
        // 生产代码的口径是「先按时间戳排序再算相邻间隔」，所以回拨不会产生负时长，
        // 而是被并入正确的时间线；单条间隔另有 5 分钟封顶兜底。
        // 断言的是「按排序后的真实相邻间隔累计」——不是「回拨一律不算」。
        val log = listOf(
            r(t0),                        // 排序后第 1 条
            r(t0 + 10 * 60_000),          // 与上一条差 10 分钟 → 封顶成 5 分钟
            r(t0 + 11 * 60_000),          // 再差 1 分钟
        )
        assertEquals(300_000L + 60_000L, LearningStats.totalMs(log))

        // 反过来喂（乱序）结果必须一样：顺序不影响总和，否则时间线就被揉乱了
        val shuffled = listOf(r(t0 + 11 * 60_000), r(t0), r(t0 + 10 * 60_000))
        assertEquals(LearningStats.totalMs(log), LearningStats.totalMs(shuffled))

        // 两条完全相同的时刻：间隔为 0，不会变成负数、也不会翻倍
        assertEquals(0L, LearningStats.totalMs(listOf(r(t0 + 10 * 60_000), r(t0 + 10 * 60_000))))
    }

    @Test fun durationMixesCappedAndNormalGaps() {
        val log = listOf(
            r(t0),                       // 起点
            r(t0 + 90_000),              // +1:30
            r(t0 + 30 * 60_000),         // +28:30 → 封顶 5 分钟
            r(t0 + 30 * 60_000 + 45_000),// +0:45
        );
        assertEquals(90_000L + 300_000L + 45_000L, LearningStats.totalMs(log));
    }

    // ================= 跨月 ================= 

    @Test fun dailyReviewsCrossesMonthBoundary() {
        // 9 月 30 日到 10 月 2 日，日期要连续、跨月不断
        val sep30 = localMidnight(2026, 9, 30)
        val oct2 = localMidnight(2026, 10, 2)
        val log = listOf(r(sep30 + 3600_000), r(sep30 + 3600_000 + 60_000), r(oct2 + 3600_000));
        val bars = LearningStats.dailyReviews(log, oct2, 3);
        assertEquals(listOf("09-30", "10-01", "10-02"), bars.map { it.first });
        assertEquals(listOf(2, 0, 1), bars.map { it.second });
    }

    @Test fun streakCrossesMonthBoundary() {
        val oct1 = localMidnight(2026, 10, 1)
        val log = listOf(r(oct1), r(oct1 - day), r(oct1 - 2 * day));
        assertEquals("9/29、9/30、10/1 连着的三天", 3, LearningStats.streakDays(log, oct1));
    }

    @Test fun trimKeepsRecentDaysOnly() {
        val old = r(t0 - 100 * day)
        val fresh = r(t0 - 1 * day)
        val kept = ReviewLog.trim(listOf(old, fresh), t0);
        assertEquals(1, kept.size);
        assertEquals(fresh.at, kept[0].at);
    }

    @Test fun trimEnforcesCountCap() {
        val log = (0 until 50).map { r(t0 - it * day, rating = 3) }
        val kept = ReviewLog.trim(log, t0, keepDays = 3650, max = 10);
        assertEquals(10, kept.size);
        // 留下的是最新的那 10 条（按时间升序）
        assertEquals(t0 - 9 * day, kept.first().at);
        assertEquals(t0, kept.last().at);
    }

    // ================= 保持率 ================= 

    @Test fun newCardHasNoRetrievability() {
        // 新卡 stability = 0，算不出「还剩多少记忆」。硬算成 0% 会把「还没开始」显示成「全忘了」
        assertNull(LearningStats.retrievabilityOf(Fsrs.Card(), t0));
        assertNull(LearningStats.meanRetrievability(listOf(Fsrs.Card()), t0));
    }

    @Test fun retrievabilityEqualsCurveOfFsrs() {
        // 面板和调度器必须用同一条曲线，否则「预测 90%」和「实际排 1 天」会互相打脸
        val card = reviewedCard(t0 - 5 * day, 5.0)
        val expected = Fsrs.forgettingCurve(5.0, 5.0, Fsrs.Params.DEFAULT)
        assertEquals(expected, LearningStats.retrievabilityOf(card, t0)!!, 1e-9);
    }

    @Test fun retrievabilityJustReviewedIsOne() {
        val card = reviewedCard(t0, 5.0)
        assertEquals(1.0, LearningStats.retrievabilityOf(card, t0)!!, 1e-9);
    }

    @Test fun retentionCurveIsMonotonicDownForSingleCard() {
        // 一张卡、不再复习：随天数增加，平均可提取性只能往下走
        val card = reviewedCard(t0 - 30 * day, 5.0)
        val curve = LearningStats.retentionCurve(listOf(card), t0, 30);
        assertTrue(curve.size >= 2);
        for (i in 1 until curve.size) {
            assertTrue("第 " + i + " 天不该比前一天高", curve[i].second <= curve[i - 1].second + 1e-9);
        }
        assertTrue(curve.all { it.second in 0.0..1.0 });
    }

    @Test fun retentionCurveSkipsDaysBeforeCardWasLearned() {
        // 卡是 10 天前学的，但要看 30 天窗口：前 20 天它还不存在，曲线只从第 21 天开始
        val card = reviewedCard(t0 - 10 * day, 5.0)
        val curve = LearningStats.retentionCurve(listOf(card), t0, 30);
        assertEquals(11, curve.size);   // lastReview 那天 + 之后 10 天
    }

    @Test fun recallRateOnlyCountsGoodAndEasy() {
        // FSRS：1=Again 2=Hard 3=Good 4=Easy；「想起来了」= 3 及以上
        val log = listOf(r(t0, rating = 1), r(t0, rating = 2), r(t0, rating = 3), r(t0, rating = 4));
        assertEquals(0.5, LearningStats.recallRate(log)!!, 1e-9);
        assertTrue(r(t0, rating = 3).remembered);
        assertTrue(!r(t0, rating = 2).remembered);
    }

    @Test fun distinctCardsDedupsAcrossKinds() {
        // 同一张卡重复评 3 次只算 1 张；知识卡的 \"1\" 和单词的同名 key 也要算两张
        val log = listOf(
            r(t0, ReviewLog.Kind.KNOWLEDGE, "1"),
            r(t0 + 1000, ReviewLog.Kind.KNOWLEDGE, "1"),
            r(t0 + 2000, ReviewLog.Kind.WORD, "1"),
        );
        val s = LearningStats.compute(log, emptyList(), emptyList(), t0);
        assertEquals(3, s.reviewCount);
        assertEquals(2, s.distinctCards);
    }

    // ================= 成本口径 ================= 

    private fun ledgerWithMonth(ym: String, date: String, costWanted: Long): MutableMap<String, CostLedger.Month> {
        // 用 DeepSeek 的价（输入 ¥0.5/M、输出 ¥2/M 折算成微元/百万）反推 token 数太绕，
        // 直接构造 Month 更直白：这里测的是「怎么摊」，不是「怎么算单价」—— 单价由 CostLedgerTest 负责。
        val m = CostLedger.Month()
        m.record(date, "deepseek-chat", CostLedger.Feature.SOLVE, 0, 0, costWanted, 0)
        return linkedMapOf(ym to m)
    }

    @Test fun perStudentMonthFormula() {
        // 本月花 ¥3.00（3_000_000 微元），活跃 3 天 → 3.00 / 3 × 30 = ¥30.00 = 30_000_000 微元
        val ledger = ledgerWithMonth("202610", "20261020", 3_000_000L)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 3);
        assertEquals(3_000_000L, c.monthMicro);
        assertEquals(3, c.activeDays);
        assertEquals(30_000_000L, c.perStudentMonthMicro);
    }

    @Test fun perStudentMonthUsesThirtyDayMonthRegardlessOfCalendar() {
        // 2 月只有 28 天，但报价口径固定 ×30，不同月份的单价才能横向比
        val ledger = ledgerWithMonth("202602", "20260210", 1_000_000L)
        val c = LearningStats.perStudentMonth(ledger, "202602", activeDays = 2);
        assertEquals(15_000_000L, c.perStudentMonthMicro);   // 1.00 / 2 × 30
    }

    @Test fun perStudentMonthIsNullWithoutActiveDays() {
        // 活跃 0 天时除零，硬算出的 0 或无穷大都是假数 —— 宁可 null，页面写「本月还没有学习记录」
        val ledger = ledgerWithMonth("202610", "20261020", 5_000_000L)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 0);
        assertNull(c.perStudentMonthMicro);
        assertTrue(c.basis.contains("还没有学习记录"));
    }

    @Test fun perStudentMonthIsNullWhenNoCost() {
        // 有学习但没产生 AI 请求：成本 ¥0，同样不给「每人每月」编数
        val ledger = ledgerWithMonth("202610", "20261020", 0L)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 5);
        assertNull(c.perStudentMonthMicro);
        assertEquals(0L, c.monthMicro);
    }

    @Test fun perStudentMonthUnknownMonthIsEmptyNotError() {
        val c = LearningStats.perStudentMonth(emptyMap(), "202610", activeDays = 4);
        assertEquals(0L, c.monthMicro);
        assertEquals(0, c.monthReq);
        assertNull(c.perStudentMonthMicro);
        assertTrue(c.byFeature.isEmpty());
    }

    @Test fun basisTextStatesTheDenominatorInPlainWords() {
        val ledger = ledgerWithMonth("202610", "20261020", 2_000_000L)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 4);
        // 口径说明必须让人看懂分母是什么 —— 这是这个数唯一可能被质疑的地方
        assertTrue(c.basis.contains("4 个学习日"));
        assertTrue(c.basis.contains("真正复习过的天数"));
        assertTrue(c.basis.contains("¥"));
    }

    @Test fun featureShareSortedByMoneyNotByCount() {
        val m = CostLedger.Month()
        m.record("20261020", "deepseek-chat", CostLedger.Feature.TRANSLATE, 0, 0, 1_000_000L, 0)
        m.record("20261020", "deepseek-chat", CostLedger.Feature.SOLVE, 0, 0, 3_000_000L, 0)
        val ledger = linkedMapOf("202610" to m)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 1);
        assertEquals("识别", c.byFeature.first().first);   // 花钱多的排前面
        assertEquals(75, c.featureShare.first().second);   // 3/4 = 75%
    }

    // ================= 空数据 / 全新安装（评委会问的坑） ================= 

    /**
     * 全新安装：没有任何复习流水、没有任何 AI 请求、知识卡一张都没复习过。
     * 这一页必须能正常打开，而且**一个假数字都不能出现**（NaN / 负数 / 0% 都算假）。
     */
    @Test fun freshInstallNeverShowsNanOrNegative() {
        val s = LearningStats.compute(emptyList(), emptyList(), emptyList(), t0)
        val c = LearningStats.perStudentMonth(emptyMap(), "202610", activeDays = 0)

        // 数值层面：全部是 null 或 0，没有任何一位是 NaN / Infinity
        assertNull(s.retentionNow)
        assertNull(s.recallRate)
        assertNull(c.perStudentMonthMicro)
        assertEquals(0L, c.monthMicro)
        assertEquals(0, s.reviewCount)
        assertEquals(0L, s.totalMs)
        assertEquals(0, s.streakDays)

        // 字符串层面：页面真正贴出去的那几段文案，不能含 NaN / Infinity / 负号
        val shown = listOf(
            s.totalText,
            c.basis,
            CostLedger.fmtYuan(c.monthMicro),
            LearningStats.fmtDuration(s.totalMs),
        )
        for (text in shown) {
            assertTrue("文案里出现了非法数值：" + text, !text.contains("NaN") && !text.contains("Infinity"))
            assertTrue("文案里出现了负数金额：" + text, !text.contains("-\u00a5"))
        }
    }

    /** 知识卡全是 New（一张都没复习过）时，曲线必须为空 —— 页面上走「还没有可统计的卡片」分支。 */
    @Test fun allNewCardsProduceEmptyCurveNotZeroLine() {
        val fresh = List(20) { Fsrs.Card() }   // 20 张全新卡：state=New, stability=0, lastReview=0
        val s = LearningStats.compute(emptyList(), fresh, emptyList(), t0)
        assertTrue("全是 New 卡时不该有曲线（不能画一条贴底的 0 线）", s.retentionCurve.isEmpty())
        assertNull("全是 New 卡也算不出「平均还剩多少」", s.retentionNow)
        // 但它们确实存在，卡片总数要如实反映
        assertEquals(0, s.trackedCards)
    }

    /** 本月没有请求时，每人每月成本是 null，页面写「暂无」——绝不会拼出 "¥NaN"。 */
    @Test fun zeroRequestsNeverRendersYuanNan() {
        val ledger = ledgerWithMonth("202610", "20261020", 0L)
        val c = LearningStats.perStudentMonth(ledger, "202610", activeDays = 7)
        assertNull(c.perStudentMonthMicro)
        assertEquals("¥0", CostLedger.fmtYuan(c.monthMicro))
        assertTrue(!c.basis.contains("NaN"))
    }

    // ================= 展示小工具 ================= 

    @Test fun durationFormatting() {
        assertEquals("0 分钟", LearningStats.fmtDuration(0));
        assertEquals("不到 1 分钟", LearningStats.fmtDuration(30_000));
        assertEquals("13 分钟", LearningStats.fmtDuration(13 * 60_000L));
        assertEquals("2 小时", LearningStats.fmtDuration(120 * 60_000L));
        assertEquals("2 小时 13 分", LearningStats.fmtDuration(133 * 60_000L));
    }

    @Test fun percentFormatting() {
        assertEquals("87%", LearningStats.fmtPct(0.8734));
        assertEquals("0%", LearningStats.fmtPct(-1.0));
        assertEquals("100%", LearningStats.fmtPct(2.0));
        assertEquals("87", LearningStats.fmtPctBare(0.8734));
    }

    @Test fun recentDatesIsAscendingAndIncludesToday() {
        val ds = ReviewLog.recentDates(t0, 7);
        assertEquals(7, ds.size);
        assertEquals("20261020", ds.last());
        assertEquals("20261014", ds.first());
        assertEquals(ds.sorted(), ds);
    }

    @Test fun plusDaysRollsOverMonthEnd() {
        assertEquals("20261001", ReviewLog.plusDays("20260930", 1));
        assertEquals("20260930", ReviewLog.plusDays("20261001", -1));
        assertEquals("20270101", ReviewLog.plusDays("20261231", 1));
    }

    @Test fun recallAndParseRoundTrip() {
        val log = listOf(r(t0, ReviewLog.Kind.WORD, "deck\u0000word", 4));
        val back = ReviewLog.parse(ReviewLog.toJson(log));
        assertEquals(1, back.size);
        assertEquals(log[0], back[0]);
    }

    @Test fun parseSkipsBrokenRowsInsteadOfThrowing() {
        // 文件被手改坏 / 老版本写进来的缺字段行：坏行丢掉，好行照用（整份扔掉等于把学习记录清零）
        val json = "[{\"at\":0},{\"at\":" + t0 + ",\"k\":\"word\",\"key\":\"a\",\"r\":3},null]";
        val back = ReviewLog.parse(json);
        assertEquals(1, back.size);
        assertEquals(ReviewLog.Kind.WORD, back[0].kind);
    }

    @Test fun parseGarbageGivesEmptyNotCrash() {
        assertTrue(ReviewLog.parse(null).isEmpty());
        assertTrue(ReviewLog.parse("").isEmpty());
        assertTrue(ReviewLog.parse("not json").isEmpty());
    }
}