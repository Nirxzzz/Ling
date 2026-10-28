package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3.1 学习者数字孪生的单测。
 *
 * ## 这份测试最想钉死的一件事
 * 「数据不足」和「值为 0」是两个完全不同的事实，但界面上只差一个字符。
 * 一旦代码里把 UNKNOWN 退化成了 0.0，刚装 App 的新用户就会看到
 * 「掌握度 0% / 保持率 0%」——读起来是「你已经学废了」，而他只是还没开始。
 * 所以每个维度都同时断言两件事：
 *   1) 没数据时 Dim.v 必须是 null（不是 0.0）；
 *   2) 那个 null 必须在 Twin.gaps 里有一条对应的「还差什么」说明。
 * 只断言第一条不够：null 不给理由，用户还是不知道为什么空。
 *
 * ## 为什么全部用固定时钟
 * 每个用例都传常量 NOW，所有时间计算相对它。这样「连续 30 天」「近 7 天」
 * 这些口径变没变、有没有被时区影响，跑一次就知道。
 */
class LearnerTwinTest {
    private companion object {
        /** 固定基准时刻，避免「今天是哪天」影响断言 */
        const val NOW = 1_760_000_000_000L
        const val DAY = Fsrs.DAY_MS

        /** 一条答题记录。knowledge 传空串表示没识别出考点，归到未分类 */
        fun q(at: Long, knowledge: String, correct: Boolean): WrongLog.Q =
            WrongLog.Q(id = at, question = "Q" + at, knowledge = knowledge,
                answer = "", correct = correct, at = at)

        fun rv(at: Long, rating: Int = 3): ReviewLog.R =
            ReviewLog.R(at = at, kind = ReviewLog.Kind.KNOWLEDGE, key = "k" + at, rating = rating)

        /** 一张已复习过、不会被当成新卡的卡 */
        fun card(stability: Double = 10.0, last: Long = NOW - DAY): Fsrs.Card =
            Fsrs.Card(stability = stability, difficulty = 5.0, due = NOW + DAY,
                lastReview = last, reps = 3, lapses = 0, state = Fsrs.State.Review)

        fun twin(input: LearnerTwin.Inputs): LearnerTwin.Twin = LearnerTwin.build(input, NOW)
    }

    // ───────────── 一、UNKNOWN 不是 0 ─────────────

    /** 空输入：所有维度 UNKNOWN，v 全是 null，且每个空维度在 gaps 里有说明 */
    @Test fun emptyInputAllUnknownNotZero() {
        val t = twin(LearnerTwin.Inputs())
        assertFalse("空输入时不该有任何维度能说话", t.hasAny)
        for (f in t.dims) {
            assertTrue(f.name + " 必须是 UNKNOWN", f.dim.unknown)
            assertNull(f.name + " 的 v 必须是 null 而不是 0.0", f.dim.v)
        }
        assertTrue("空输入必须给出缺口清单", t.gaps.isNotEmpty())
    }

    /** 每个空维度都要说清「为什么空」和「怎么补」，只给一个维度名不算 */
    @Test fun everyGapStatesWhyAndHow() {
        val t = twin(LearnerTwin.Inputs())
        for (g in t.gaps) {
            assertTrue(g.dim + " 的缺口名不能为空", g.dim.isNotBlank())
            assertTrue(g.dim + " 必须写明原因", g.why.isNotBlank())
            assertTrue(g.dim + " 必须写明怎么补", g.need.isNotBlank())
        }
    }

    /** Dim.missing 造出的值一定是 null + UNKNOWN，且保留原因 */
    @Test fun missingKeepsReasonAndNeverZero() {
        val d = LearnerTwin.Dim.missing("ReviewLog：还没有流水")
        assertNull(d.v)
        assertEquals(LearnerTwin.Conf.UNKNOWN, d.conf)
        assertTrue("原因必须带出来，否则用户不知道补什么", d.src.contains("ReviewLog"))
    }

    /** 0 是合法观测值：有台账、确实没花钱，这时不许说「数据不足」 */
    @Test fun zeroIsAValueNotAGap() {
        val d = LearnerTwin.monthDim(0L)
        assertFalse("0 元是观测值，不是缺失", d.unknown)
        assertEquals(0.0, d.v!!, 1e-9)
    }

    // ───────────── 二、趋势方向 ─────────────

    /** 任一侧为 null 时只能给 NA，不能猜成 FLAT */
    @Test fun dirNaWhenEitherSideMissing() {
        assertEquals(LearnerTwin.Dir.NA, LearnerTwin.Dir.of(null, 0.5))
        assertEquals(LearnerTwin.Dir.NA, LearnerTwin.Dir.of(0.5, null))
        assertEquals(LearnerTwin.Dir.NA, LearnerTwin.Dir.of(null, null))
    }

    /**
     * 变化小于容差算持平；超过容差才算上下。
     *
     * after 变小是「在变差」——方向不能反，否则画像上的箭头会指向反面。
     */
    @Test fun dirUsesTolerance() {
        assertEquals(LearnerTwin.Dir.FLAT, LearnerTwin.Dir.of(0.90, 0.88))
        assertEquals(LearnerTwin.Dir.DOWN, LearnerTwin.Dir.of(0.70, 0.55))
        assertEquals(LearnerTwin.Dir.UP, LearnerTwin.Dir.of(0.40, 0.60))
    }

    // ───────────── 三、掌握度 ─────────────

    /** 掌握度 = 答对次数 ÷ 总次数，不做收缩，用户能自己验算 */
    @Test fun masteryPerKnowledgePoint() {
        val wrong = listOf(
            q(NOW - DAY, "函数", true),
            q(NOW - 2 * DAY, "函数", false),
            q(NOW - 3 * DAY, "导数", false),
        )
        val m = LearnerTwin.masteryMap(WeakPoints.report(wrong, NOW))
        assertEquals(0.5, m["函数"]!!, 1e-9)
        assertEquals("错一次就是 0%，不许给贝叶斯平滑后的 33%", 0.0, m["导数"]!!, 1e-9)
    }

    /** 只有错题时：掌握度有数，其余维度各自独立地保持 UNKNOWN */
    @Test fun wrongOnlyMasteryKnownRestUnknown() {
        val wrong = listOf(
            q(NOW - DAY, "函数", true),
            q(NOW - 2 * DAY, "函数", false),
            q(NOW - 3 * DAY, "函数", true),
        )
        val t = twin(LearnerTwin.Inputs(wrong = wrong))
        assertEquals(0.6667, t.mastery["函数"]!!, 0.001)
        assertTrue("没有复习流水，节奏必须 UNKNOWN", t.pacePerDay.unknown)
        assertTrue("没有流水，连续天数必须 UNKNOWN", t.streak.unknown)
        assertTrue("没有卡片，预测保持率必须 UNKNOWN", t.retentionPredicted.unknown)
    }

    /** 没识别出考点的记录归到「未分类」，不能被丢掉 */
    @Test fun unclassifiedIsKept() {
        val wrong = listOf(q(NOW - DAY, "", false))
        val m = LearnerTwin.masteryMap(WeakPoints.report(wrong, NOW))
        assertTrue("空考点要归到未分类", m.containsKey(WeakPoints.UNKNOWN))
    }

    // ───────────── 四、薄弱榜可追溯 ─────────────

    /** 薄弱榜必须带够家底：只给百分比，用户没法核对「凭什么说我这块弱」 */
    @Test fun weakNodesCarryRawCounts() {
        val wrong = listOf(
            q(NOW - DAY, "函数", false),
            q(NOW - 2 * DAY, "函数", false),
            q(NOW - 3 * DAY, "函数", true),
            q(NOW - 4 * DAY, "导数", true),
        )
        val t = twin(LearnerTwin.Inputs(wrong = wrong))
        val fn = t.weak.first { it.name == "函数" }
        assertEquals("总次数必须带出来", 3, fn.total)
        assertEquals("错题数必须带出来", 2, fn.wrong)
        assertEquals("对题数算出来", 1, fn.correct)
        assertEquals(1.0 / 3.0, fn.mastery, 1e-9)
        // 掌握度和次数必须自洽，否则页面上「自己算一遍」会算出不一样的数
        assertEquals(fn.mastery, fn.correct.toDouble() / fn.total, 1e-9)
    }

    /** 掌握度相同时，最近错的排前面：昨天错的比三个月前错的更该先补 */
    @Test fun tieBreaksByMostRecentWrong() {
        val wrong = listOf(
            q(NOW - 100 * DAY, "旧", false),
            q(NOW - DAY, "新", false),
        )
        val t = twin(LearnerTwin.Inputs(wrong = wrong))
        assertEquals("都是 0% 掌握时，最近的错排前", "新", t.weak.first().name)
    }

    /** masteryTop 是 weak 的投影，两者顺序必须完全一致 */
    @Test fun masteryTopMirrorsWeak() {
        val wrong = listOf(
            q(NOW - DAY, "甲", false),
            q(NOW - DAY, "乙", true),
            q(NOW - 2 * DAY, "乙", true),
        )
        val t = twin(LearnerTwin.Inputs(wrong = wrong))
        assertEquals(t.weak.map { it.name }, t.masteryTop.map { it.first })
        assertEquals(t.weak.map { it.mastery }, t.masteryTop.map { it.second })
    }

    /** 榜长不超过 TOP */
    @Test fun weakListIsCapped() {
        val wrong = (0 until 12).map { q(NOW - it * DAY, "KP" + it, false) }
        val t = twin(LearnerTwin.Inputs(wrong = wrong))
        assertTrue("榜长不能超过 TOP", t.weak.size <= LearnerTwin.TOP)
    }

    // ───────────── 五、节奏与连续天数 ─────────────

    /** 日均复习量按 30 天固定分母算：30 条流水 = 1.0 张/天 */
    @Test fun paceUsesCalendarDaysAsDenominator() {
        val reviews = (0 until 30).map { rv(NOW - it * 3600_000L, 3) }
        val d = LearnerTwin.paceDim(reviews, NOW)
        assertEquals(1.0, d.dim.v!!, 1e-9)
        assertTrue("来源必须写明分母", d.dim.src.contains("30"))
    }

    /** 窗口内一条流水都没有 ≠ 日均 0 张 */
    @Test fun paceUnknownWhenWindowEmpty() {
        val old = listOf(rv(NOW - 100 * DAY))
        val d = LearnerTwin.paceDim(old, NOW)
        assertTrue("最近 30 天没学，给 UNKNOWN 而不是 0.0", d.dim.unknown)
        assertNull(d.dim.v)
    }

    /** 有流水时连续天数的 0 是真值：今天没学，所以断了 */
    @Test fun streakZeroIsObservable() {
        val old = listOf(rv(NOW - 100 * DAY))
        val d = LearnerTwin.streakDim(old)
        assertFalse("有流水但断了，0 天是观测值", d.unknown)
        assertEquals(0.0, d.v!!, 1e-9)
    }

    /** 没有流水：连续天数无从谈起 */
    @Test fun streakUnknownWithoutLog() {
        assertTrue(LearnerTwin.streakDim(emptyList()).unknown)
    }

    /** 连续三天都有流水（含今天）→ 3 天 */
    @Test fun streakCountsConsecutiveDays() {
        val log = (0 until 3).map { rv(NOW - it * DAY) }
        assertEquals(3, LearnerTwin.countStreak(log, NOW))
    }

    /** 最后一天是前天 → 连续段已断，归 0 而不是「连续 N 天」 */
    @Test fun streakBrokenWhenGapExceedsOneDay() {
        val log = (2 until 6).map { rv(NOW - it * DAY) }
        assertEquals("前天就断了，不是连续 4 天", 0, LearnerTwin.countStreak(log, NOW))
    }

    // ───────────── 六、专注维度 ─────────────

    /** 有学习采样，专注时长有数；但没离开样本时最易分心时段必须 UNKNOWN */
    @Test fun focusKnownButWorstHourUnknown() {
        val samples = (0 until 20).map {
            FocusReport.FocusSample(NOW - it * 30_000L, 30_000L, FocusGuard.Verdict.STUDY.code)
        }
        val t = twin(LearnerTwin.Inputs(samples = samples))
        assertFalse("有学习采样，专注时长应该有数", t.focusAvgMs.unknown)
        assertTrue("没记录到离开，最易分心时段必须是 UNKNOWN", t.worstHour.unknown)
    }

    /** 采样全在 1 分钟以内：样本不够，必须 UNKNOWN 而不是「专注 0 秒」 */
    @Test fun focusUnknownWhenLessThanOneMinute() {
        val samples = listOf(
            FocusReport.FocusSample(NOW - 40_000L, 30_000L, FocusGuard.Verdict.STUDY.code),
        )
        val t = twin(LearnerTwin.Inputs(samples = samples))
        assertTrue("不足 1 分钟不给数字", t.focusAvgMs.unknown)
        assertNull(t.focusAvgMs.v)
    }

    /** 完全没采样：专注与分心时段都 UNKNOWN */
    @Test fun focusUnknownWithoutSamples() {
        val t = twin(LearnerTwin.Inputs())
        assertTrue(t.focusAvgMs.unknown)
        assertTrue(t.leavesPerHour.unknown)
        assertTrue(t.worstHour.unknown)
    }

    // ───────────── 七、成本维度 ─────────────

    /** 没有台账：单知识点成本无从谈起 */
    @Test fun costPerNodeUnknownWithoutLedger() {
        val d = LearnerTwin.costPerNodeDim(0L, listOf(q(NOW, "函数", true)), emptyMap())
        assertTrue(d.unknown)
        assertNull(d.v)
    }

    /** 分母是「不同知识点数」，未分类也算一个 */
    @Test fun costPerNodeCountsUncategorized() {
        val wrong = listOf(
            q(NOW - DAY, "函数", false),
            q(NOW - 2 * DAY, "", false),
        )
        val ledger = mapOf("202610" to CostLedger.Month())
        val d = LearnerTwin.costPerNodeDim(2_000_000L, wrong, ledger)
        assertEquals("两个知识点各摊 1000000 微元", 1_000_000.0, d.v!!, 1.0)
    }

    /** 台账读数为负说明数据坏了：不展示负数 */
    @Test fun negativeMonthNotShown() {
        assertTrue(LearnerTwin.monthDim(-1L).unknown)
    }

    // ───────────── 八、可追溯性 ─────────────

    /** 每个有数的维度 src 必须点名数据来源，否则「凭什么」无法回答 */
    @Test fun everyKnownDimensionHasSource() {
        val wrong = listOf(q(NOW - DAY, "函数", false), q(NOW - 2 * DAY, "函数", true))
        val reviews = (0 until 10).map { rv(NOW - it * 3600_000L) }
        val samples = (0 until 40).map {
            FocusReport.FocusSample(NOW - it * 60_000L, 60_000L, FocusGuard.Verdict.STUDY.code)
        }
        val t = twin(LearnerTwin.Inputs(wrong = wrong, reviews = reviews, samples = samples))
        for (f in t.dims) {
            if (!f.dim.unknown) {
                assertTrue(f.name + " 必须带来源说明", f.dim.src.isNotBlank())
            }
        }
    }

    /** 纯函数：同一输入算两次结果一致 */
    @Test fun deterministic() {
        val wrong = listOf(q(NOW - DAY, "函数", true), q(NOW - 2 * DAY, "导数", false))
        val a = twin(LearnerTwin.Inputs(wrong = wrong))
        val b = twin(LearnerTwin.Inputs(wrong = wrong))
        assertEquals(a.mastery, b.mastery)
        assertEquals(a.weak.map { it.name }, b.weak.map { it.name })
    }

    /** 时刻为 0 的坏行必须被忽略，不能当成 1970 年的记录 */
    @Test fun badTimestampsIgnored() {
        val log = listOf(rv(0L), rv(NOW - DAY))
        assertEquals("只算昨天那一天", 1, LearnerTwin.countStreak(log, NOW))
    }

    /** DIMENSIONS 与 dims 顺序一致，页面才能按固定顺序遍历渲染 */
    @Test fun dimensionsMatchDims() {
        val t = twin(LearnerTwin.Inputs())
        assertEquals(LearnerTwin.DIMENSIONS, t.dims.map { it.name })
        assertNotNull(t.at)
    }
}
