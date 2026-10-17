package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.85.5：「一分钟后」这类**中文数字相对时间**必须走相对分支（用户实测：早8点说 → 被记成 23:24）。
 */
class CnRelativeWhenTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    @Test
    fun chineseNumeralMinutesGoRelative() {
        val now = at(2026, 10, 12, 8, 37)
        val p = Todo.parseWhen("一分钟后提醒我", now)
        val diff = p.dueAt - now
        assertTrue("「一分钟后」= now+60s（±5s），实际偏移 $diff", diff in 55_000..65_000)
        assertEquals("提醒我", p.title)
    }

    @Test
    fun chineseNumeralHoursAndDays() {
        val now = at(2026, 10, 12, 8, 37)
        val h = Todo.parseWhen("三小时后开会", now)
        assertTrue("三小时后 = +3h", (h.dueAt - now) in (3 * 3600_000L - 5000)..(3 * 3600_000L + 5000))
        assertEquals("开会", h.title)
        val d = Todo.parseWhen("两天后交报告", now)
        assertTrue("两天后 = +2d", (d.dueAt - now) in (2 * 86_400_000L - 5000)..(2 * 86_400_000L + 5000))
        assertEquals("交报告", d.title)
    }

    @Test
    fun arabicNumeralsStillWorkVerbatim() {
        val now = at(2026, 10, 12, 8, 37)
        val p = Todo.parseWhen("20分钟后喝水", now)
        assertEquals("喝水", p.title)
        assertTrue((p.dueAt - now) in (20 * 60_000L - 5000)..(20 * 60_000L + 5000))
    }

    @Test
    fun bareMinuteWordIsNotAClockFallback() {
        // 修复前：中文分支不命中 → minuteOfDay 把「一分」乱算成 9:37 这类错时刻
        val now = at(2026, 10, 12, 8, 37)
        val p = Todo.parseWhen("两分钟后叫我", now)
        val diff = p.dueAt - now
        assertTrue(diff in 115_000..125_000)
    }
}
