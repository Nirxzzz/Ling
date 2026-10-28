package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

/**
 * `Todo.parseWhen` 承诺的输入 vs 实际认识的输入。
 *
 * 待办页的提示语写着：
 *   「时间写在句子里就行：明天 / 今晚 / 后天 / 3天后 / 15:30 / 「周五」」
 * 这份测试逐条核对提示里点名的写法，看代码是否真的认。
 */
class TodoParsePromiseTest {

    // 2026-10-21 是周三
    private val now = 1792559820000L

    private fun p(raw: String) = Todo.parseWhen(raw, now)

    private fun dayOf(ms: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    @Test fun promiseMingtian() {
        assertEquals(22, dayOf(p("明天 15:30 交周报").dueAt))
    }

    @Test fun promiseJinwan() {
        // 「今晚8点」= 今天 20:00
        val c = java.util.Calendar.getInstance().apply { timeInMillis = p("今晚8点 买菜").dueAt }
        assertEquals(21, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(20, c.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test fun promiseHoutian() {
        assertEquals(23, dayOf(p("后天 8点 体检").dueAt))
    }

    @Test fun promiseDaysLater() {
        assertEquals(24, dayOf(p("3天后 交表").dueAt))
    }

    @Test fun promiseClock() {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = p("18:00 健身").dueAt }
        assertEquals(18, c.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(java.util.Calendar.MINUTE))
    }

    @Test fun promiseFriday() {
        // 提示里最后一个例子。2026-10-21 是周三，周五应当是 10-23。
        val d = p("周五 交报告").dueAt
        val c = java.util.Calendar.getInstance().apply { timeInMillis = d }
        assertEquals("「周五」应当解析到 10 月 23 日，实际 " +
            (c.get(java.util.Calendar.MONTH) + 1) + "/" + c.get(java.util.Calendar.DAY_OF_MONTH),
            23, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals("周五 应当是星期五", 6, c.get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals("没说钟点时默认 9:00", 9, c.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test fun weekdayVariantsAllWork() {
        // 「星期五 / 礼拜五 / 周5」都要认（周5 不在提示里，但用户会这么写）
        listOf("周五", "星期五", "礼拜五").forEach { w ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = p(w + " 交报告").dueAt }
            assertEquals("「" + w + "」应当落在星期五", 6, c.get(java.util.Calendar.DAY_OF_WEEK))
            assertEquals("「" + w + "」应当是 10-23", 23, c.get(java.util.Calendar.DAY_OF_MONTH))
        }
    }

    @Test fun weekdaySunday() {
        // 周日/周天/周七 都指星期天（2026-10-25）
        listOf("周日", "周天", "周七").forEach { w ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = p(w + " 休息").dueAt }
            assertEquals("「" + w + "」应当落在星期日", 1, c.get(java.util.Calendar.DAY_OF_WEEK))
            assertEquals("「" + w + "」应当是 10-25", 25, c.get(java.util.Calendar.DAY_OF_MONTH))
        }
    }

    @Test fun sameWeekdayMeansNextWeek() {
        // 今天（10-21）就是周三，说「周三」应当指下周三 10-28，不是今天。
        // 口语里「周三交」绝不会是「今天交」。
        val c = java.util.Calendar.getInstance().apply { timeInMillis = p("周三 交报告").dueAt }
        assertEquals(28, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    @Test fun weekdayWithClock() {
        // 「周五下午3点」= 10-23 15:00
        val c = java.util.Calendar.getInstance().apply { timeInMillis = p("周五下午3点 开会").dueAt }
        assertEquals(23, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(15, c.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test fun weekdayStrippedFromTitle() {
        assertEquals("交报告", p("周五 交报告").title)
    }
}
