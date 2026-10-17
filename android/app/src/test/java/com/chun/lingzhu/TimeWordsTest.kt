package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * v1.86 口语时间词：[Todo.minuteOfDay] / [Todo.numOf] / [Todo.parseWhen] 的新增部分。
 * 用户口语里「晚八点」「下午三点」比「20:00」更常写，但「一点忙」不是时刻 —— 这条边界要有单测守着。
 */
class TimeWordsTest {
    private val now: Long = at(2026, Calendar.OCTOBER, 10, 12, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------- numOf ----------------

    @Test fun arabicAndChineseNumerals() {
        assertEquals(3, Todo.numOf("3"))
        assertEquals(15, Todo.numOf("15"))
        assertEquals(8, Todo.numOf("八"))
        assertEquals(2, Todo.numOf("两"))
        assertEquals(10, Todo.numOf("十"))
        assertEquals(11, Todo.numOf("十一"))
        assertEquals(12, Todo.numOf("十二"))
        assertEquals(23, Todo.numOf("二十三"))
        assertNull(Todo.numOf(""))
        assertNull(Todo.numOf("abc"))
    }

    // ---------------- minuteOfDay ----------------

    @Test fun digitalAndChineseTimes() {
        assertEquals(15 * 60 + 30, Todo.minuteOfDay("15:30 开会"))
        assertEquals(15 * 60 + 30, Todo.minuteOfDay("15点30分"))
        assertEquals(9 * 60, Todo.minuteOfDay("9点"))
        assertEquals(20 * 60, Todo.minuteOfDay("晚八点"))
        assertEquals(20 * 60, Todo.minuteOfDay("晚上8点"))
        assertEquals(15 * 60, Todo.minuteOfDay("下午3点"))
        assertEquals(10 * 60, Todo.minuteOfDay("上午十点"))
        assertEquals(13 * 60, Todo.minuteOfDay("中午一点"))
    }

    /** 「一点」「两点」在口语里常常不是时刻（「一点忙」「两点建议」）—— 裸着写的别认 */
    @Test fun bareLowChineseHoursAreNotTimes() {
        assertNull(Todo.minuteOfDay("帮我一点忙"))
        assertNull(Todo.minuteOfDay("有两点想说的"))
        // 「三点」及以上无歧义，照认 —— 「三点意见」这种少数派会被当成 3:00，这个代价是明知接受的
        assertEquals(3 * 60, Todo.minuteOfDay("他提了三点意见"))
    }

    @Test fun notATimeAtAll() {
        assertNull(Todo.minuteOfDay("3小时后"))
        assertNull(Todo.minuteOfDay("交周报"))
        assertNull(Todo.minuteOfDay(""))
    }

    // ---------------- parseWhen ----------------

    @Test fun parseWhenUnderstandsAfternoonAndChineseHour() {
        val p = Todo.parseWhen("明天下午3点交材料", now)
        assertEquals("交材料", p.title)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 15, 0), p.dueAt)

        val q = Todo.parseWhen("晚八点提醒我背单词", now)
        assertEquals("提醒我背单词", q.title)
        assertEquals(at(2026, Calendar.OCTOBER, 10, 20, 0), q.dueAt)
    }

    @Test fun parseWhenKeepsOldBehavior() {
        // v1.85 那几条一个都不许变
        assertEquals(now + 20 * 60_000L, Todo.parseWhen("20分钟后喝水", now).dueAt)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 8, 0), Todo.parseWhen("明天8点叫我起床", now).dueAt)
        assertEquals(at(2026, Calendar.OCTOBER, 10, 15, 0), Todo.parseWhen("15点给妈打电话", now).dueAt)
        assertEquals(at(2026, Calendar.OCTOBER, 12, 9, 0), Todo.parseWhen("后天交材料", now).dueAt)
        // 认不出时间 → 默认一小时后，整句当标题
        val p = Todo.parseWhen("买牛奶", now)
        assertEquals("买牛奶", p.title)
        assertEquals(now + 3_600_000L, p.dueAt)
    }

    @Test fun timeAlreadyPassedRollsToTomorrow() {
        // 12:00 说「早上9点」→ 那是明天早上 9 点
        assertEquals(at(2026, Calendar.OCTOBER, 11, 9, 0), Todo.parseWhen("早上9点交作业", now).dueAt)
    }
}
