package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * [ChatStore.dividerText]：时间分隔行。期望值全部用本机 Calendar 现算（RemindTest 的口径，不写死时区）。
 */
class ChatDividerTest {
    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }

    @Test
    fun closeMessagesGetNoDivider() {
        val base = cal(2026, 10, 12, 8, 30).timeInMillis
        assertNull("间隔 9 分 59 秒不插", ChatStore.dividerText(base, base + ChatStore.DIVIDER_GAP_MS - 1, base + ChatStore.DIVIDER_GAP_MS))
        assertNull("同毫秒不插", ChatStore.dividerText(base, base, base))
    }

    @Test
    fun firstMessageOfSessionAlwaysGetsDivider() {
        val at = cal(2026, 10, 12, 8, 30).timeInMillis
        // prevAt = null（会话开头）无条件给时间 —— 用户打开聊天第一眼就该知道「这是几点说的」
        assertEquals("08:30", ChatStore.dividerText(null, at, at))
    }

    @Test
    fun gapPastThresholdFormatsAsTodayClock() {
        val base = cal(2026, 10, 12, 8, 30).timeInMillis
        val later = base + ChatStore.DIVIDER_GAP_MS
        assertEquals("08:40", ChatStore.dividerText(base, later, later))
    }

    @Test
    fun yesterdayGetsPrefix() {
        val now = cal(2026, 10, 12, 9, 0).timeInMillis
        val yest = cal(2026, 10, 11, 23, 5).timeInMillis
        assertEquals("昨天 23:05", ChatStore.dividerText(null, yest, now))
    }

    @Test
    fun sameYearOtherDayGetsMonthDay() {
        val now = cal(2026, 10, 12, 9, 0).timeInMillis
        val old = cal(2026, 3, 2, 14, 45).timeInMillis
        assertEquals("3月2日 14:45", ChatStore.dividerText(null, old, now))
    }

    @Test
    fun crossYearGetsYear() {
        val now = cal(2026, 10, 12, 9, 0).timeInMillis
        val old = cal(2025, 12, 31, 22, 0).timeInMillis
        assertEquals("2025年12月31日 22:00", ChatStore.dividerText(null, old, now))
    }

    @Test
    fun nowZeroMeansTreatMessageDayAsToday() {
        // 老调用方不传 now（0）：以消息自己的日子当「今天」，只出 HH:mm
        val at = cal(2026, 10, 12, 8, 30).timeInMillis
        assertEquals("08:30", ChatStore.dividerText(null, at, 0))
    }
}
