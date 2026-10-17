package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 弹出纪律：六条纪律的状态迁移 + 时间/文案小工具。
 * 全部纯函数，唯一入口是 [PopupPolicy.decide]（显式传 minute / today，不读系统时钟）。
 */
class PopupPolicyTest {

    private val now = 1_760_000_000_000L
    private val today = 20261007
    private val day = 24L * 3600 * 1000

    private fun ev(key: String = "k1", cat: String = PopupPolicy.CAT_STUDY, title: String = "交周报") =
        PopupPolicy.Event(key, cat, title)

    private fun cfg(
        on: Boolean = true,
        dailyCap: Int = PopupPolicy.DAILY_CAP_DEFAULT,
        catCaps: Map<String, Int> = emptyMap(),
        quietOn: Boolean = false,
        quietStart: Int = PopupPolicy.QUIET_START_DEFAULT,
        quietEnd: Int = PopupPolicy.QUIET_END_DEFAULT,
    ) = PopupPolicy.Config(on, dailyCap, catCaps, quietOn, quietStart, quietEnd)

    /** 默认时段之外的分钟（10:00），免打扰关着 —— 大多数判定测试用它当「能弹」 */
    private val day10 = 10 * 60

    // ---------------- 上限判定：边界 0 / 8 / 20 ----------------

    @Test fun dailyCapZeroNeverShows() {
        assertEquals(PopupPolicy.Verdict.CAPPED_DAILY, PopupPolicy.decide(PopupPolicy.State(), ev(), now, cfg(dailyCap = 0), day10, today))
    }

    @Test fun dailyCapBoundaryLastSlotShowsThenCapped() {
        val base = PopupPolicy.State(day = today, shown = PopupPolicy.DAILY_CAP_DEFAULT - 1)
        assertEquals(PopupPolicy.DAILY_CAP_DEFAULT, 8)
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(base, ev(), now, cfg(), day10, today))
        val full = PopupPolicy.State(day = today, shown = PopupPolicy.DAILY_CAP_DEFAULT)
        assertEquals(PopupPolicy.Verdict.CAPPED_DAILY, PopupPolicy.decide(full, ev(), now, cfg(), day10, today))
    }

    @Test fun dailyCapMax20() {
        assertEquals(20, PopupPolicy.DAILY_CAP_MAX)
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(PopupPolicy.State(day = today, shown = 19), ev(), now, cfg(dailyCap = 20), day10, today))
        assertEquals(PopupPolicy.Verdict.CAPPED_DAILY, PopupPolicy.decide(PopupPolicy.State(day = today, shown = 20), ev(), now, cfg(dailyCap = 20), day10, today))
    }

    @Test fun catCapBoundaryLastSlotShowsThenCapped() {
        val c = cfg(catCaps = mapOf(PopupPolicy.CAT_STUDY to 2))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(PopupPolicy.State(day = today, byCat = mapOf(PopupPolicy.CAT_STUDY to 1)), ev(), now, c, day10, today))
        assertEquals(PopupPolicy.Verdict.CAPPED_CAT, PopupPolicy.decide(PopupPolicy.State(day = today, byCat = mapOf(PopupPolicy.CAT_STUDY to 2)), ev(), now, c, day10, today))
    }

    @Test fun catCapDefaultsToFourAndIsClamped() {
        val c = cfg(catCaps = mapOf(PopupPolicy.CAT_STUDY to 99, PopupPolicy.CAT_LIFE to -5))
        assertEquals(20, c.capFor(PopupPolicy.CAT_STUDY))
        assertEquals(0, c.capFor(PopupPolicy.CAT_LIFE))
        assertEquals(PopupPolicy.CAT_CAP_DEFAULT, c.capFor("没配过的类别"))
    }

    @Test fun catCapZeroBlocksThatCategoryOnly() {
        val c = cfg(catCaps = mapOf(PopupPolicy.CAT_STUDY to 0))
        assertEquals(PopupPolicy.Verdict.CAPPED_CAT, PopupPolicy.decide(PopupPolicy.State(), ev(cat = PopupPolicy.CAT_STUDY), now, c, day10, today))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(PopupPolicy.State(), ev(cat = PopupPolicy.CAT_LIFE), now, c, day10, today))
    }

    @Test fun rollDayClearsCountersAndUnblocks() {
        val s = PopupPolicy.State(day = today - 1, shown = 8, byCat = mapOf(PopupPolicy.CAT_STUDY to 4))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(s, ev(), now, cfg(), day10, today))   // 换天后计数清零
        val rolled = PopupPolicy.rollDay(s, today)
        assertEquals(0, rolled.shown); assertEquals(emptyMap<String, Int>(), rolled.byCat)
        assertTrue(rolled.mutes.isEmpty())   // 当天本来就没静音
    }

    @Test fun keepMutesAndLevelsAcrossRollDay() {
        val s = PopupPolicy.State(day = today - 1, shown = 8, mutes = mapOf(PopupPolicy.CAT_LIFE to now + day), level = mapOf(PopupPolicy.CAT_STUDY to 2))
        val rolled = PopupPolicy.rollDay(s, today)
        assertEquals(now + day, rolled.mutes[PopupPolicy.CAT_LIFE])
        assertEquals(2, rolled.level[PopupPolicy.CAT_STUDY])
    }

    // ---------------- 开关 / 静音 / 稍后 的优先级 ----------------

    @Test fun switchOffWinsOverEverything() {
        val s = PopupPolicy.State(day = today, mutes = mapOf(PopupPolicy.CAT_STUDY to now + day), snoozeAt = mapOf("k1" to now + day))
        assertEquals(PopupPolicy.Verdict.OFF, PopupPolicy.decide(s, ev(), now, cfg(on = false, quietOn = true), 23 * 60, today))
    }

    @Test fun muteExpiresExactlyOnDay7() {
        val s = PopupPolicy.mute(PopupPolicy.State(), PopupPolicy.CAT_STUDY, now)
        assertEquals(PopupPolicy.MUTE_DAYS, 7)
        assertEquals(now + PopupPolicy.MUTE_MS, s.mutes[PopupPolicy.CAT_STUDY])
        // 第 7 天到期：到期前 1 毫秒还静音，到点那一刻起恢复
        assertTrue(PopupPolicy.isMuted(s, PopupPolicy.CAT_STUDY, now + PopupPolicy.MUTE_MS - 1))
        assertFalse(PopupPolicy.isMuted(s, PopupPolicy.CAT_STUDY, now + PopupPolicy.MUTE_MS))
        assertEquals(1, PopupPolicy.activeMutes(s, now + PopupPolicy.MUTE_MS - 1).size)
        assertTrue(PopupPolicy.activeMutes(s, now + PopupPolicy.MUTE_MS).isEmpty())
        assertEquals(PopupPolicy.Verdict.MUTED, PopupPolicy.decide(s, ev(), now + PopupPolicy.MUTE_MS - 1, cfg(), day10, today))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(s, ev(), now + PopupPolicy.MUTE_MS, cfg(), day10, today))
    }

    @Test fun snoozedUntilItsTime() {
        val s = PopupPolicy.State(day = today, snoozeAt = mapOf("k1" to now + 30 * 60_000L), snoozeCount = mapOf("k1" to 1))
        assertEquals(PopupPolicy.Verdict.SNOOZED, PopupPolicy.decide(s, ev(), now, cfg(), day10, today))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(s, ev(), now + 30 * 60_000L, cfg(), day10, today))
    }

    @Test fun snoozeUsedUpAfterThirdSnooze() {
        // 用满 SNOOZE_MAX 次「稍后」之后，再点就只记历史
        var s = PopupPolicy.State(day = today)
        repeat(3) { s = PopupPolicy.onSnooze(s, ev(), now, 30) }
        assertEquals(3, s.snoozeCount["k1"])
        assertNull(s.snoozeAt["k1"])                                   // 第 3 次不再排闹钟
        assertTrue(s.pending.none { it.key == "k1" })
        assertEquals(PopupPolicy.Verdict.SNOOZED_OUT, PopupPolicy.decide(s, ev(), now, cfg(), day10, today))
        // 只稍后 2 次时额度还在（还能重弹 2 次）
        val two = PopupPolicy.State(day = today, snoozeCount = mapOf("k1" to PopupPolicy.SNOOZE_MAX), snoozeAt = mapOf("k1" to now - 1))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(two, ev(), now, cfg(), day10, today))
    }

    @Test fun onSnoozeSchedulesFirstTwoTimesOnly() {
        var s = PopupPolicy.State(day = today)
        s = PopupPolicy.onSnooze(s, ev(), now, 30)
        assertEquals(now + 30 * 60_000L, s.snoozeAt["k1"]); assertEquals(listOf("k1"), s.pending.map { it.key })
        s = PopupPolicy.onSnooze(s, ev(), now + 31 * 60_000L, 30)
        assertEquals(2, s.snoozeCount["k1"]); assertEquals(now + 61 * 60_000L, s.snoozeAt["k1"])
        s = PopupPolicy.onSnooze(s, ev(), now + 62 * 60_000L, 30)
        assertNull(s.snoozeAt["k1"]); assertTrue(s.pending.isEmpty())
    }

    @Test fun onShownCountsAndClearsSnoozeSlot() {
        val s = PopupPolicy.State(day = today, snoozeAt = mapOf("k1" to now + day), pending = listOf(ev()), queued = listOf("交周报", "买菜"))
        val after = PopupPolicy.onShown(s, ev(), today)
        assertEquals(1, after.shown)
        assertEquals(1, after.byCat[PopupPolicy.CAT_STUDY])
        assertNull(after.snoozeAt["k1"]); assertTrue(after.pending.isEmpty())
        assertEquals(listOf("买菜"), after.queued)      // 弹过的这条从队列里摘掉
    }

    @Test fun resnoozeRerunsDecideAndDropsSchedule() {
        val s = PopupPolicy.State(day = today, shown = 1, snoozeAt = mapOf("k1" to now - 1), snoozeCount = mapOf("k1" to 1), pending = listOf(ev()))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.resnooze(s, ev(), now, cfg(), day10, today))
        // 这期间总额度弹满了 → 重弹时就不再弹
        val full = s.copy(shown = PopupPolicy.DAILY_CAP_DEFAULT)
        assertEquals(PopupPolicy.Verdict.CAPPED_DAILY, PopupPolicy.resnooze(full, ev(), now, cfg(), day10, today))
    }

    // ---------------- 免打扰时段（跨午夜）----------------

    @Test fun quietWindowCrossesMidnight() {
        assertEquals(1350, PopupPolicy.QUIET_START_DEFAULT)   // 22:30
        assertEquals(450, PopupPolicy.QUIET_END_DEFAULT)      // 7:30
        assertTrue(PopupPolicy.inQuiet(22 * 60 + 29, 1350, 450).not())
        assertTrue(PopupPolicy.inQuiet(22 * 60 + 30, 1350, 450))
        assertTrue(PopupPolicy.inQuiet(23 * 60 + 59, 1350, 450))
        assertTrue(PopupPolicy.inQuiet(0, 1350, 450))         // 跨过零点
        assertTrue(PopupPolicy.inQuiet(7 * 60 + 29, 1350, 450))
        assertFalse(PopupPolicy.inQuiet(7 * 60 + 30, 1350, 450))
        assertFalse(PopupPolicy.inQuiet(12 * 60, 1350, 450))
    }

    @Test fun quietWindowSameDayAndDisabled() {
        assertTrue(PopupPolicy.inQuiet(13 * 60, 9 * 60, 18 * 60))
        assertFalse(PopupPolicy.inQuiet(8 * 60, 9 * 60, 18 * 60))
        assertFalse(PopupPolicy.inQuiet(20 * 60, 9 * 60, 18 * 60))
        assertFalse(PopupPolicy.inQuiet(3 * 60, 9 * 60, 9 * 60))   // start == end 视为不启用
        assertFalse(PopupPolicy.inQuiet(23 * 60, 22 * 60, 22 * 60))
    }

    @Test fun normMinWrapsAround() {
        assertEquals(0, PopupPolicy.normMin(1440))
        assertEquals(0, PopupPolicy.normMin(0))
        assertEquals(1439, PopupPolicy.normMin(-1))
        assertEquals(1, PopupPolicy.normMin(1441))
        assertEquals(60, PopupPolicy.normMin(-1380))
    }

    @Test fun quietOnQueuesInsteadOfShowing() {
        val c = cfg(quietOn = true)
        assertEquals(PopupPolicy.Verdict.QUEUED, PopupPolicy.decide(PopupPolicy.State(), ev(), now, c, 23 * 60, today))
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(PopupPolicy.State(), ev(), now, c, 10 * 60, today))
        // 静音 / 弹满 优先于排队：档满就只记历史，不占队列
        assertEquals(PopupPolicy.Verdict.CAPPED_DAILY, PopupPolicy.decide(PopupPolicy.State(day = today, shown = 8), ev(), now, c, 23 * 60, today))
        // 免打扰关着 → 时段内照弹
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(PopupPolicy.State(), ev(), now, cfg(quietOn = false), 23 * 60, today))
    }

    @Test fun fmtRangeAndMin() {
        assertEquals("22:30 ~ 07:30（跨夜）", PopupPolicy.fmtRange(1350, 450))
        assertEquals("09:00 ~ 18:00", PopupPolicy.fmtRange(540, 1080))
        assertEquals("08:05 ~ 08:05", PopupPolicy.fmtRange(485, 485))   // 相等 → 不标跨夜
        assertEquals("23:59", PopupPolicy.fmtMin(1439))
        assertEquals("00:00", PopupPolicy.fmtMin(1440))
    }

    @Test fun snoozeDelayForEveningOption() {
        // 「晚上」= 到下一个免打扰结束那一刻
        assertEquals(510, PopupPolicy.snoozeDelay(0, 23 * 60, 450))       // 23:00 → 7:30
        assertEquals(40, PopupPolicy.snoozeDelay(0, 6 * 60 + 50, 450))    // 6:50 → 7:30（40 分钟）
        assertEquals(30, PopupPolicy.snoozeDelay(30, 23 * 60, 450))        // 选具体分钟就照用
        assertEquals("晚上", PopupPolicy.snoozeLabel(0))
        assertEquals("1 小时后", PopupPolicy.snoozeLabel(60))
        assertEquals("30 分钟后", PopupPolicy.snoozeLabel(30))
        // 免打扰关着 → 「晚上」退化成默认 30 分钟
        assertEquals(PopupPolicy.SNOOZE_DEFAULT_MIN, PopupPolicy.snoozeDelayFor(0, 23 * 60, cfg(quietOn = false)))
        assertEquals(510, PopupPolicy.snoozeDelayFor(0, 23 * 60, cfg(quietOn = true)))
    }

    // ---------------- 事件排队与合并文案 ----------------

    @Test fun enqueueDedupesByKeyAndTitle() {
        var s = PopupPolicy.enqueue(PopupPolicy.State(), ev())
        s = PopupPolicy.enqueue(s, ev())
        assertEquals(listOf("交周报"), s.queued)
        assertEquals(1, s.pending.size)
        s = PopupPolicy.enqueue(s, ev(key = "k2", title = "买菜"))
        assertEquals(listOf("交周报", "买菜"), s.queued)
        assertEquals(2, s.pending.size)
    }

    @Test fun summaryTextHeadAndOverflow() {
        assertEquals("有 1 件事：交周报", PopupPolicy.summaryText(listOf("交周报")))
        assertEquals("攒了 2 件事：交周报、买菜", PopupPolicy.summaryText(listOf("交周报", "买菜")))
        assertEquals("", PopupPolicy.summaryText(emptyList()))
        val many = (1..7).map { "t$it" }
        assertEquals("攒了 7 件事：t1、t2、t3、t4、t5 等 7 件", PopupPolicy.summaryText(many))
        assertEquals(5, PopupPolicy.SUMMARY_TITLES)
    }

    @Test fun mergeTextNullWhenNothingQueued() {
        assertNull(PopupPolicy.mergeText(emptyList()))
        assertEquals("有 1 件事：交周报", PopupPolicy.mergeText(listOf("交周报")))
    }

    @Test fun drainQueueMergesAfterQuietEnds() {
        val s = PopupPolicy.State(
            day = today,
            queued = listOf("交周报", "买菜"),
            pending = listOf(ev("k1"), ev("k2", PopupPolicy.CAT_LIFE, "买菜")),
            snoozeAt = mapOf("k2" to now + day),
        )
        val c = cfg(quietOn = true)
        assertNull(PopupPolicy.drainQueue(s, now, c, 23 * 60))       // 还在时段里 → 先不发
        val (text, after) = PopupPolicy.drainQueue(s, now, c, 8 * 60)!!
        assertEquals("攒了 2 件事：交周报、买菜", text)
        assertTrue(after.queued.isEmpty())
        assertTrue(after.pending.isEmpty())
        assertTrue(after.snoozeAt.isEmpty())
        assertNull(PopupPolicy.drainQueue(after, now, c, 8 * 60))   // 队列空了 → 没东西可说
    }

    @Test fun drainQueueKeepsUnrelatedPending() {
        val s = PopupPolicy.State(day = today, queued = listOf("交周报"), pending = listOf(ev("k1"), ev("k9", PopupPolicy.CAT_LIFE, "健身")), snoozeAt = mapOf("k9" to now + day))
        val (_, after) = PopupPolicy.drainQueue(s, now, cfg(), day10)!!
        assertEquals(listOf("k9"), after.pending.map { it.key })
        assertEquals(now + day, after.snoozeAt["k9"])
    }

    @Test fun takeDuePicksUpAndDropsHandled() {
        val due = ev("k1")
        val waiting = ev("k2", PopupPolicy.CAT_LIFE, "买菜")
        val s = PopupPolicy.State(day = today, pending = listOf(due, waiting), queued = listOf("交周报", "买菜"), snoozeAt = mapOf("k1" to now - 1))
        // 免打扰时段里：没排期的排队项先不动
        val (none, kept) = PopupPolicy.takeDue(s, now, 23 * 60, cfg(quietOn = true))
        assertEquals(listOf("k1"), none.map { it.key })
        assertEquals(listOf("k2"), kept.pending.map { it.key })
        assertEquals(listOf("买菜"), kept.queued)
        // 时段外：排队项也一起到期
        val (both, cleared) = PopupPolicy.takeDue(s, now, 10 * 60, cfg(quietOn = true))
        assertEquals(listOf("k1", "k2"), both.map { it.key })
        assertTrue(cleared.pending.isEmpty()); assertTrue(cleared.queued.isEmpty()); assertTrue(cleared.snoozeAt.isEmpty())
    }

    @Test fun stillScheduledNeedsFutureAlarm() {
        val s = PopupPolicy.State(day = today, pending = listOf(ev("k1"), ev("k2", PopupPolicy.CAT_LIFE, "买菜")), snoozeAt = mapOf("k1" to now + day, "k2" to now - 1))
        assertEquals(listOf("k1"), PopupPolicy.stillScheduled(s, now).map { it.key })
    }

    @Test fun pruneDropsExpiredAndOwnerless() {
        val s = PopupPolicy.State(
            day = today,
            mutes = mapOf(PopupPolicy.CAT_STUDY to now + day, PopupPolicy.CAT_LIFE to now - day),
            snoozeAt = mapOf("live" to now + day, "dead" to now - day, "orphan" to now + day),
            snoozeCount = mapOf("live" to 1, "dead" to 1, "exhausted" to 3),
            ignoreRun = mapOf(PopupPolicy.CAT_STUDY to 1, PopupPolicy.CAT_LIFE to 0),
            pending = listOf(ev("live")),
        )
        val p = PopupPolicy.prune(s, now)
        assertEquals(setOf(PopupPolicy.CAT_STUDY), p.mutes.keys)                    // 过期静音清掉
        assertEquals(setOf("live"), p.snoozeAt.keys)                               // 过期 / 无主的排期清掉
        assertEquals(setOf("live", "exhausted"), p.snoozeCount.keys)               // 用完的计数留着（>2），没主的清掉
        assertEquals(setOf(PopupPolicy.CAT_STUDY), p.ignoreRun.keys)
    }

    @Test fun pruneCapsQueueLength() {
        val s = PopupPolicy.State(day = today, queued = (1..60).map { "q$it" }, pending = (1..60).map { ev("k$it") })
        val p = PopupPolicy.prune(s, now)
        assertEquals(PopupPolicy.MAX_PENDING, p.queued.size)
        assertEquals(PopupPolicy.MAX_PENDING, p.pending.size)
        assertEquals("q60", p.queued.last())      // 留最新的
    }

    // ---------------- 自适应档位 ----------------

    @Test fun handledRaisesLevelAndClearsIgnoreRun() {
        var s = PopupPolicy.State(day = today)
        s = PopupPolicy.onHandled(s, PopupPolicy.CAT_STUDY)
        assertEquals(PopupPolicy.LEVEL_NORMAL + 1, PopupPolicy.levelOf(s, PopupPolicy.CAT_STUDY))
        assertTrue(s.ignoreRun.isEmpty())
        s = PopupPolicy.onHandled(s, PopupPolicy.CAT_STUDY)
        assertEquals(PopupPolicy.LEVEL_MAX, PopupPolicy.levelOf(s, PopupPolicy.CAT_STUDY))   // 封顶
    }

    @Test fun threeIgnoresDropLevelAndSwitchToSummary() {
        var s = PopupPolicy.State(day = today)
        s = PopupPolicy.onIgnored(s, PopupPolicy.CAT_STUDY)
        assertEquals(1, s.ignoreRun[PopupPolicy.CAT_STUDY]); assertEquals(PopupPolicy.LEVEL_NORMAL, PopupPolicy.levelOf(s, PopupPolicy.CAT_STUDY))
        s = PopupPolicy.onIgnored(s, PopupPolicy.CAT_STUDY)
        assertEquals(PopupPolicy.IGNORE_RUN_LIMIT - 1, s.ignoreRun[PopupPolicy.CAT_STUDY])
        assertEquals(PopupPolicy.Verdict.SHOW, PopupPolicy.decide(s, ev(), now, cfg(), day10, today))
        s = PopupPolicy.onIgnored(s, PopupPolicy.CAT_STUDY)      // 第 3 次 → 降档 + 汇总式
        assertNull(s.ignoreRun[PopupPolicy.CAT_STUDY])
        assertEquals(PopupPolicy.LEVEL_MIN, PopupPolicy.levelOf(s, PopupPolicy.CAT_STUDY))
        assertTrue(PopupPolicy.isSummary(s, PopupPolicy.CAT_STUDY))
        assertEquals(PopupPolicy.Verdict.SHOW_SUMMARY, PopupPolicy.decide(s, ev(), now, cfg(), day10, today))
    }

    @Test fun summaryLevelOutlivesHandled() {
        var s = PopupPolicy.State(day = today, level = mapOf(PopupPolicy.CAT_STUDY to PopupPolicy.LEVEL_MIN), summary = setOf(PopupPolicy.CAT_STUDY))
        assertTrue(PopupPolicy.isSummary(s, PopupPolicy.CAT_STUDY))
        assertTrue(PopupPolicy.isSummary(PopupPolicy.State(), PopupPolicy.CAT_LIFE).not())   // 没记过 = 常规
        s = PopupPolicy.onHandled(s, PopupPolicy.CAT_STUDY)
        assertEquals(PopupPolicy.LEVEL_NORMAL, PopupPolicy.levelOf(s, PopupPolicy.CAT_STUDY))
        assertFalse(PopupPolicy.isSummary(s, PopupPolicy.CAT_STUDY))
    }

    @Test fun levelLabelAndClamp() {
        assertEquals("汇总（只给一行）", PopupPolicy.levelLabel(0))
        assertEquals("常规", PopupPolicy.levelLabel(1))
        assertEquals("详细", PopupPolicy.levelLabel(2))
        assertEquals("汇总（只给一行）", PopupPolicy.levelLabel(-3))
        assertEquals("详细", PopupPolicy.levelLabel(99))
    }

    // ---------------- 文案 / 状态行 ----------------

    @Test fun categoryLabel() {
        assertEquals("学习", PopupPolicy.label(PopupPolicy.CAT_STUDY))
        assertEquals("生活", PopupPolicy.label(PopupPolicy.CAT_LIFE))
        assertEquals("其它", PopupPolicy.label("别的"))
        assertEquals(listOf("study", "life"), PopupPolicy.CATS)
    }

    @Test fun summaryBodyShowsRemainingQuota() {
        val e = ev(cat = PopupPolicy.CAT_STUDY, title = "交周报")
        val c = cfg()
        assertEquals("学习·交周报\n今日还剩 6 次弹出额度", PopupPolicy.summaryBody(PopupPolicy.State(shown = 2), e, c))
        assertEquals("学习·交周报\n今日额度已用完", PopupPolicy.summaryBody(PopupPolicy.State(shown = 8), e, c))
    }

    @Test fun statusLineMentionsCountMutesAndQueue() {
        val c = cfg()
        assertEquals("今天已弹 0/8 次", PopupPolicy.statusLine(PopupPolicy.State(), c, now))
        val s = PopupPolicy.State(day = today, shown = 3, mutes = mapOf(PopupPolicy.CAT_STUDY to now + day), queued = listOf("a", "b"))
        val line = PopupPolicy.statusLine(s, c, now)
        assertTrue(line, line.startsWith("今天已弹 3/8 次"))
        assertTrue(line, line.contains("study 静音到 "))
        assertTrue(line, line.contains("免打扰排队 2 条"))
        assertEquals("已关：所有事只入库、不弹", PopupPolicy.statusLine(s, cfg(on = false), now))
    }

    @Test fun capLeftAndDailyLeft() {
        val c = cfg(catCaps = mapOf(PopupPolicy.CAT_STUDY to 4))
        val s = PopupPolicy.State(shown = 3, byCat = mapOf(PopupPolicy.CAT_STUDY to 1))
        assertEquals(3, PopupPolicy.capLeft(s, c, PopupPolicy.CAT_STUDY))
        assertEquals(4, PopupPolicy.capLeft(s, c, PopupPolicy.CAT_LIFE))    // 没记过 → 0，用满 4
        assertEquals(5, PopupPolicy.dailyLeft(s, c))
        assertEquals(-5, PopupPolicy.capLeft(PopupPolicy.State(byCat = mapOf(PopupPolicy.CAT_STUDY to 9)), c, PopupPolicy.CAT_STUDY))
    }

    @Test fun addPendingDedupesByKey() {
        val l = PopupPolicy.addPending(emptyList(), ev("k1"))
        assertEquals(1, PopupPolicy.addPending(l, ev("k1", title = "改了标题")).size)
        assertEquals(2, PopupPolicy.addPending(l, ev("k2")).size)
    }

    // ---------------- 存取 ----------------

    @Test fun jsonRoundTrip() {
        val s = PopupPolicy.State(
            day = today, shown = 3, byCat = mapOf(PopupPolicy.CAT_STUDY to 2),
            mutes = mapOf(PopupPolicy.CAT_LIFE to now + day),
            snoozeCount = mapOf("k1" to 1), snoozeAt = mapOf("k1" to now + 60_000),
            level = mapOf(PopupPolicy.CAT_STUDY to 2, PopupPolicy.CAT_LIFE to 0),
            ignoreRun = mapOf(PopupPolicy.CAT_STUDY to 2), summary = setOf(PopupPolicy.CAT_LIFE),
            queued = listOf("交周报"), pending = listOf(ev("k1"), ev("k2", PopupPolicy.CAT_LIFE, "买菜")),
        )
        assertEquals(s, PopupPolicy.parse(PopupPolicy.toJson(s)))
    }

    @Test fun levelZeroSurvivesRoundTrip() {
        // 档位 0 = 汇总式，是有意义的值，不能当「没记过」丢掉
        val s = PopupPolicy.State(day = today, level = mapOf(PopupPolicy.CAT_STUDY to PopupPolicy.LEVEL_MIN))
        val back = PopupPolicy.parse(PopupPolicy.toJson(s))
        assertEquals(PopupPolicy.LEVEL_MIN, back.level[PopupPolicy.CAT_STUDY])
        assertTrue(PopupPolicy.isSummary(back, PopupPolicy.CAT_STUDY))
    }

    @Test fun parseToleratesGarbageAndBlanks() {
        assertEquals(PopupPolicy.State(), PopupPolicy.parse(null))
        assertEquals(PopupPolicy.State(), PopupPolicy.parse(""))
        assertEquals(PopupPolicy.State(), PopupPolicy.parse("   "))
        assertEquals(PopupPolicy.State(), PopupPolicy.parse("{坏"))
        val s = PopupPolicy.parse("""{"day":20261007,"shown":2,"queued":["a","","b"],"summary":["  ","study"],"pending":[{"key":"k1","cat":"study","title":"t"},{"key":"","cat":"life","title":"丢"},{"cat":"life","title":"没key"}]}""")
        assertEquals(20261007, s.day); assertEquals(2, s.shown)
        assertEquals(listOf("a", "b"), s.queued)
        assertEquals(setOf("study"), s.summary)
        assertEquals(listOf(PopupPolicy.Event("k1", PopupPolicy.CAT_STUDY, "t")), s.pending)   // 没 key 的两条丢掉
    }

    @Test fun pendingEventWithoutCatDefaultsToLife() {
        val s = PopupPolicy.parse("""{"pending":[{"key":"k1","title":"买菜"}]}""")
        assertEquals(PopupPolicy.CAT_LIFE, s.pending.single().cat)
    }

    @Test fun minuteOfDayUsesLocalClock() {
        val c = java.util.Calendar.getInstance()
        c.set(2026, 9, 7, 14, 35, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(14 * 60 + 35, PopupPolicy.minuteOfDay(c.timeInMillis))
    }

    @Test fun fmtDayIsMonthDay() {
        val c = java.util.Calendar.getInstance()
        c.set(2026, 9, 13, 12, 0, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals("10-13", PopupPolicy.fmtDay(c.timeInMillis))
    }

    // ---------------- PopupCenter 的常量（Android 那层可测的部分）----------------

    @Test fun popupCenterConstantsAreDistinct() {
        assertEquals(30, PopupCenter.NOTI_ID)
        assertEquals(31, PopupCenter.NOTI_SUMMARY_ID)
        assertEquals("popup", PopupCenter.CHANNEL)
        assertEquals("event", PopupCenter.KIND_EVENT)
        val actions = listOf(PopupCenter.A_HANDLE, PopupCenter.A_SNOOZE, PopupCenter.A_MUTE, PopupCenter.A_IGNORE)
        assertEquals(4, actions.toSet().size)                       // 四个动作互不撞（撞了 PendingIntent 会串）
        assertEquals("com.chun.lingzhu.POPUP_SNOOZE", PopupCenter.A_ALARM_SNOOZE)
        assertEquals("com.chun.lingzhu.POPUP_DRAIN", PopupCenter.A_ALARM_DRAIN)
        assertFalse(actions.any { it == PopupCenter.A_ALARM_SNOOZE })
    }

    @Test fun drainAlarmGapMatchesQuietEnd() {
        // PopupCenter.scheduleDrain 用的就是这个算法：距免打扰结束还有几分钟（跨夜则 +1440）
        fun gap(minute: Int): Int {
            val g = PopupPolicy.QUIET_END_DEFAULT - minute
            return if (g > 0) g else g + 1440
        }
        assertEquals(510, gap(23 * 60))                   // 23:00 → 7:30
        assertEquals(1290, gap(10 * 60))                  // 10:00 → 7:30（跨夜）
        assertEquals(450, gap(0))                         // 0:00 → 7:30
    }

    /**
     * M4：到点弹卡那条链用 [PopupCenter.allow] —— 只有 [PopupPolicy.Verdict.SHOW] /
     * [PopupPolicy.Verdict.SHOW_SUMMARY] 放行（弹卡 + 计额度），其余判定一律退成常规通知。
     * 这里把「不放行」的每个分支都从 [PopupPolicy.decide] 真跑一遍，免得放行集合和纪律判定脱节。
     */
    @Test fun allowSeamOnlyPassesShowVerdicts() {
        val pass = setOf(PopupPolicy.Verdict.SHOW, PopupPolicy.Verdict.SHOW_SUMMARY)
        assertTrue(PopupPolicy.decide(PopupPolicy.State(), ev(), now, cfg(), day10, today) in pass)
        val blocked = listOf(
            PopupPolicy.decide(PopupPolicy.State(day = today, shown = 8), ev(), now, cfg(), day10, today),                        // 每日弹满
            PopupPolicy.decide(PopupPolicy.State(day = today, byCat = mapOf(PopupPolicy.CAT_STUDY to 4)), ev(), now, cfg(), day10, today),  // 本类弹满
            PopupPolicy.decide(PopupPolicy.State(day = today, mutes = mapOf(PopupPolicy.CAT_STUDY to now + day)), ev(), now, cfg(), day10, today), // 类别静音
            PopupPolicy.decide(PopupPolicy.State(), ev(), now, cfg(quietOn = true), 23 * 60, today),                              // 免打扰
            PopupPolicy.decide(PopupPolicy.State(), ev(), now, cfg(on = false), day10, today),                                    // 总开关关
            PopupPolicy.decide(PopupPolicy.State(day = today, snoozeAt = mapOf("k1" to now + day)), ev(), now, cfg(), day10, today),  // 稍后中
            PopupPolicy.decide(PopupPolicy.State(day = today, snoozeCount = mapOf("k1" to 3), snoozeAt = mapOf("k1" to now - 1), pending = listOf(ev())), ev(), now, cfg(), day10, today),  // 稍后用完
        )
        assertTrue(blocked.toString(), blocked.none { it in pass })
    }
}