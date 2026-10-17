package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.73 弹出时间自定义：「统一弹出时刻」开关 + 三类主动弹出（早简报 / 晚简报 / 知识卡复习提醒）
 * 的 effective 时刻，以及排闹钟真正用的值。
 *
 * 这里锁住的是**排闹钟链路**：
 * [PopupTimes.effective]（统一开着 → 统一时刻，关着 → 各自独立）
 * → [Prefs.briefMorningEffectiveMin] / [Prefs.briefNightEffectiveMin] / [Prefs.reviewEffectiveMin]（包装 effective）
 * → [Briefing.scheduleAt]（该排哪几个闹钟、排在几点）。
 * 设置页任何一行改完调 rescheduleAllPopups()，落到闹钟上的就是这里的值。
 */
class PopupTimesTest {

    // ---------------- effective：开关 + 三类时刻 ----------------

    @Test fun unifiedOnUsesUnifiedMinForAllThree() {
        assertEquals(20 * 60, PopupTimes.effective(true, 20 * 60, 7 * 60 + 30))
        assertEquals(20 * 60, PopupTimes.effective(true, 20 * 60, 22 * 60))
        assertEquals(20 * 60, PopupTimes.effective(true, 20 * 60, 20 * 60))
    }

    @Test fun unifiedOffEachUsesOwnMin() {
        assertEquals(7 * 60 + 30, PopupTimes.effective(false, 20 * 60, 7 * 60 + 30))
        assertEquals(22 * 60, PopupTimes.effective(false, 20 * 60, 22 * 60))
        assertEquals(6 * 60, PopupTimes.effective(false, 20 * 60, 6 * 60))
    }

    @Test fun unifiedMinChangePropagatesOnlyWhenOn() {
        // 统一开着：改统一时刻 = 三类全跟着变（设置页「弹出时刻」行就是干这个）
        assertEquals(6 * 60 + 50, PopupTimes.effective(true, 6 * 60 + 50, 7 * 60 + 30))
        assertEquals(6 * 60 + 50, PopupTimes.effective(true, 6 * 60 + 50, 22 * 60))
        assertEquals(6 * 60 + 50, PopupTimes.effective(true, 6 * 60 + 50, 20 * 60))
        // 统一关着：改统一时刻不影响任何一类
        assertEquals(7 * 60 + 30, PopupTimes.effective(false, 6 * 60 + 50, 7 * 60 + 30))
    }

    @Test fun turningUnifiedOffRestoresOwnTimes() {
        // 开着设了 6:50，关掉后三类各自回到自己的值（向后兼容，独立时刻没被覆盖）
        assertEquals(6 * 60 + 50, PopupTimes.effective(true, 6 * 60 + 50, 7 * 60 + 30))
        assertEquals(7 * 60 + 30, PopupTimes.effective(false, 6 * 60 + 50, 7 * 60 + 30))
        assertEquals(22 * 60, PopupTimes.effective(false, 6 * 60 + 50, 22 * 60))
        assertEquals(20 * 60, PopupTimes.effective(false, 6 * 60 + 50, 20 * 60))
    }

    @Test fun edgeMinutesSurvive() {
        assertEquals(0, PopupTimes.effective(true, 0, 100))
        assertEquals(1439, PopupTimes.effective(true, 1439, 100))
        assertEquals(0, PopupTimes.effective(false, 100, 0))
        assertEquals(1439, PopupTimes.effective(false, 100, 1439))
    }

    // ---------------- 排闹钟真正用的值：scheduleAt ----------------

    @Test fun scheduleAtUsesEffectiveMinUnifiedOn() {
        // 统一开着：早/晚简报都排在统一时刻（复习提醒由 ReviewNotify 排，走同一个 effective）
        val unified = 20 * 60
        val at = Briefing.scheduleAt(
            true, PopupTimes.effective(true, unified, 7 * 60 + 30),
            true, PopupTimes.effective(true, unified, 22 * 60),
        )
        assertEquals(
            listOf(Briefing.ACTION_MORNING to 20 * 60, Briefing.ACTION_NIGHT to 20 * 60),
            at,
        )
    }

    @Test fun scheduleAtUsesOwnMinUnifiedOff() {
        val unified = 20 * 60
        val at = Briefing.scheduleAt(
            true, PopupTimes.effective(false, unified, 7 * 60 + 30),
            true, PopupTimes.effective(false, unified, 22 * 60),
        )
        assertEquals(
            listOf(Briefing.ACTION_MORNING to 7 * 60 + 30, Briefing.ACTION_NIGHT to 22 * 60),
            at,
        )
    }

    @Test fun scheduleAtRespectsSwitchesAndNormalizes() {
        // 开关决定排不排：只开早简报就只排一个，一个都不开就一个不排（不排 ≠ 排了不响）
        assertEquals(
            listOf(Briefing.ACTION_MORNING to 6 * 60 + 50),
            Briefing.scheduleAt(true, 6 * 60 + 50, false, 22 * 60),
        )
        assertEquals(
            listOf(Briefing.ACTION_NIGHT to 22 * 60),
            Briefing.scheduleAt(false, 7 * 60 + 30, true, 22 * 60),
        )
        assertEquals(emptyList<Pair<String, Int>>(), Briefing.scheduleAt(false, 7 * 60 + 30, false, 22 * 60))
        // 时刻越界照样归一到 0~1439（TimePicker 不会给出越界值，但闹钟链路自己也兜住）
        assertEquals(listOf(Briefing.ACTION_MORNING to 0), Briefing.scheduleAt(true, 1440, false, 0))
        assertEquals(listOf(Briefing.ACTION_NIGHT to 1439), Briefing.scheduleAt(false, 0, true, -1))
    }

    @Test fun scheduleAtNewTimeAlwaysLandsOnRequestedMinute() {
        // 验收点 4：改完时刻，下次真按新时刻弹 —— 排闹钟目标分钟 = effective 分钟
        for (newMin in listOf(0, 6 * 60 + 50, 12 * 60, 23 * 60 + 59)) {
            val at = Briefing.scheduleAt(
                true, PopupTimes.effective(true, newMin, 7 * 60 + 30),
                true, PopupTimes.effective(true, newMin, 22 * 60),
            )
            assertEquals(newMin, at.first().second)
            assertEquals(newMin, at.last().second)
        }
    }

    @Test fun reviewAlarmTargetIsEffectiveToo() {
        // 复习提醒：ReviewNotify.schedule 的 targetMin 就是 reviewEffectiveMin（reschedule 里传的）
        // 这里锁住 effective 语义：统一开着跟统一走，关着跟 reviewNotiMin 走
        val unified = 21 * 60 + 10
        assertEquals(unified, PopupTimes.effective(true, unified, ReviewNotify.DEFAULT_MIN))
        assertEquals(ReviewNotify.DEFAULT_MIN, PopupTimes.effective(false, unified, ReviewNotify.DEFAULT_MIN))
        // 统一开着时下次检查时刻按统一时刻算（8:00 现在 → 21:10）
        val t0 = 1_760_000_000_000L
        val at = ReviewNotify.nextCheckAt(t0, 8 * 60, PopupTimes.effective(true, unified, ReviewNotify.DEFAULT_MIN))
        assertEquals(t0 + 13 * 60 * 60_000L + 10 * 60_000L, at)
    }
}
