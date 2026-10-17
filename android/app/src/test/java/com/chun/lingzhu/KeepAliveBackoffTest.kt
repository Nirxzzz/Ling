package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeepAliveBackoff：看门狗指数退避状态机 */
class KeepAliveBackoffTest {

    @Test fun startsAtShortestInterval() {
        assertEquals(KeepAliveBackoff.INTERVALS_MS[0], KeepAliveBackoff.intervalMs(0))
        assertEquals(15 * 60 * 1000L, KeepAliveBackoff.intervalMs(0))
    }

    @Test fun idleBelowThresholdStaysSameTier() {
        // 连着 2 次没事做（不足 3）→ 还是最短档
        assertEquals(0, KeepAliveBackoff.onIdle(0, 0))
        assertEquals(0, KeepAliveBackoff.onIdle(1, 0))
    }

    @Test fun idleReachingThresholdStepsUpOneTier() {
        // 第 3 次没事做 → 升一档
        assertEquals(1, KeepAliveBackoff.onIdle(2, 0))
    }

    @Test fun steppingUpResetsStreak() {
        // 升档那一刻连击清零，重新数
        assertEquals(0, KeepAliveBackoff.nextStreak(2, 0, 1))
        assertEquals(3, KeepAliveBackoff.nextStreak(2, 1, 1))
    }

    @Test fun tiersCapAtSixtyMinutes() {
        var idx = 0
        repeat(10) { idx = KeepAliveBackoff.onIdle(KeepAliveBackoff.STREAK_TO_BACKOFF - 1, idx) }
        assertEquals(KeepAliveBackoff.INTERVALS_MS.lastIndex, idx)
        assertEquals(60 * 60 * 1000L, KeepAliveBackoff.intervalMs(idx))
    }

    @Test fun reviveResetsToShortest() {
        assertEquals(0, KeepAliveBackoff.onRevive())
        assertEquals(0, KeepAliveBackoff.onRevive())
    }

    @Test fun intervalIdxAlwaysInBounds() {
        for (i in -2..5) {
            val ms = KeepAliveBackoff.intervalMs(i)
            assertTrue(ms in KeepAliveBackoff.INTERVALS_MS)
        }
    }

    @Test fun fullCycleReviveThenBackoffThenRevive() {
        var idx = KeepAliveBackoff.onRevive()
        var streak = 0
        repeat(3) { streak = KeepAliveBackoff.nextStreak(streak, idx, KeepAliveBackoff.onIdle(streak, idx).also { idx = it }) }
        assertEquals(1, idx)                       // 3 次闲置 → 30m 档
        idx = KeepAliveBackoff.onRevive()          // 球掉了被拉起 → 回 15m
        assertEquals(0, idx)
        streak = 0
    }
}
