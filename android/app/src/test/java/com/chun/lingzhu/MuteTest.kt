package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「免打扰」（Mute）纯逻辑：全纯函数，不碰 Android / SharedPreferences。
 * 只钉几件事：按下去加 30 分、到点即失效、剩几分向上取整、菜单那一行文案，外加「至少 1 分」的兜底。
 */
class MuteTest {

    private val now = 1_000_000L
    private val min = 60_000L

    @Test fun untilAddsThirtyMinutes() {
        assertEquals(now + 30 * min, Mute.until(now))
    }

    @Test fun untilGuardsOneMinuteFloor() {
        // 传 0 / 负数不能一按就结束：夹到至少 1 分
        assertEquals(now + min, Mute.until(now, 0))
        assertEquals(now + min, Mute.until(now, -5))
    }

    @Test fun activeFalseAtAndAfterEnd() {
        assertTrue(Mute.active(now, now + 1))
        assertFalse(Mute.active(now, now))      // 正好到点即失效
        assertFalse(Mute.active(now, now - 1))
        assertFalse(Mute.active(now, 0L))       // 0 = 没开过
    }

    @Test fun remainMinRoundsUpAndZeroWhenExpired() {
        assertEquals(1, Mute.remainMin(now, now + 1))        // 还剩 1ms 也说「1 分」（向上取整）
        assertEquals(2, Mute.remainMin(now, now + min + 1))  // 多 1ms 就要进位到 2
        assertEquals(0, Mute.remainMin(now, now))            // 过期归零
    }

    @Test fun labelOffWhenIdleAndMentionsRemainingMinutesWhenOn() {
        assertEquals("关", Mute.label(now, 0L))
        assertTrue(Mute.label(now, Mute.until(now)).contains("30"))
    }
}
