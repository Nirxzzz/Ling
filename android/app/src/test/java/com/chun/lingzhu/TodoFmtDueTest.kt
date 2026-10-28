package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Todo.fmtDue` 的显示格式。
 *
 * 这里锁的是一个真实线上 bug：待办列表里所有条目都显示成「今天 00:00」，
 * 不管用户写的是「1分钟后吃饭」还是「明天 15:30 交周报」。
 *
 * 原因是调用方传了 `Todo.dayStart(t.dueAt)` —— 时刻被截断成当天零点，
 * 而 fmtDue 内部用同一个值调 fmt() 取时:分，于是永远是 00:00。
 * fmtDue 的注释声称「传原始毫秒或 dayStart 对齐毫秒都正确」，那句是错的。
 */
class TodoFmtDueTest {

    private fun hm(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = java.util.Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    @Test fun todayShowsRealHourMinute() {
        val now = at(2026, 10, 21, 13, 17)
        val due = at(2026, 10, 21, 13, 18)
        // 传原始 dueAt：应当显示 13:18
        assertEquals("今天 13:18", Todo.fmtDue(due, now, ::hm))
    }

    @Test fun dayStartInputLosesTimeOfDay() {
        // 这是 bug 的形态：调用方传 dayStart(due) 时，时:分变成 00:00。
        // 把它写成一个显式断言，说明「这样调是错的」，防止有人再退回去。
        val now = at(2026, 10, 21, 13, 17)
        val due = at(2026, 10, 21, 13, 18)
        val truncated = Todo.fmtDue(Todo.dayStart(due), now, ::hm)
        assertEquals("今天 00:00", truncated)
    }

    @Test fun tomorrowShowsRealTime() {
        val now = at(2026, 10, 21, 13, 17)
        val due = at(2026, 10, 22, 9, 30)
        assertEquals("明天 09:30", Todo.fmtDue(due, now, ::hm))
    }

    @Test fun weekdayLaterShowsDayCount() {
        val now = at(2026, 10, 21, 13, 17)
        val due = at(2026, 10, 24, 15, 0)
        assertEquals("3 天后", Todo.fmtDue(due, now, ::hm))
    }

    @Test fun yesterday() {
        val now = at(2026, 10, 21, 13, 17)
        val due = at(2026, 10, 20, 8, 0)
        assertEquals("昨天 08:00", Todo.fmtDue(due, now, ::hm))
    }
}
