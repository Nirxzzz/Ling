package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「N 分钟后」这类相对时间的解析。
 *
 * 这段逻辑 v1.85.5 改过一次（中文数字「一分钟后」曾掉进钟点分支，算出错误时刻），
 * 但一直**没有测试**。用户实测发现「1分钟后吃饭」显示成「今天 00:00」——
 * 也就是 due 被算成了当天零点，而不是 now + 60 秒。
 * 这里把各种写法都钉死。
 */
class TodoRelativeTimeTest {

    // 固定基准：2026-10-21 13:17:00 +08:00
    private val now = 1792559820000L

    private fun due(raw: String): Long = Todo.parseWhen(raw, now).dueAt
    private fun title(raw: String): String = Todo.parseWhen(raw, now).title

    @Test fun arabicMinutesLater() {
        assertEquals(now + 60_000L, due("1分钟后吃饭"))
    }

    @Test fun severalMinutesLater() {
        assertEquals(now + 20 * 60_000L, due("20分钟后喝水"))
    }

    @Test fun chineseOneMinuteLater() {
        // 中文数字：v1.85.5 修过的那格
        assertEquals(now + 60_000L, due("一分钟后吃饭"))
    }

    @Test fun chineseTenMinutesLater() {
        assertEquals(now + 10 * 60_000L, due("十分钟后出门"))
    }

    @Test fun hoursLater() {
        assertEquals(now + 3 * 3_600_000L, due("3小时后开会"))
    }

    @Test fun daysLater() {
        assertEquals(now + 2 * 86_400_000L, due("2天后交表"))
    }

    @Test fun titleKeepsTheRest() {
        // 时间词要从标题里去掉，只留事情本身
        assertEquals("吃饭", title("1分钟后吃饭"))
        assertEquals("喝水", title("20分钟后喝水"))
    }

    @Test fun relativeTimeNeverFallsBackToMidnight() {
        // 用户实测的那个 bug：解析失败会掉到「当天 00:00」。
        // 不管写哪种形式，只要带「X分钟后」，due 都必须 > now。
        listOf("1分钟后吃饭", "2分钟后", "五分钟后天提醒我", "30分钟后开会").forEach { raw ->
            val d = due(raw)
            assertTrue("「" + raw + "」算出的时间必须晚于现在，实际 " + d + " vs " + now, d > now)
        }
    }

    @Test fun relativeWithinReasonableRange() {
        // 「1分钟后」不该被算成几小时之后 —— 那是钟点分支才会有的结果
        val d = due("1分钟后吃饭")
        assertTrue("1分钟后应当在一分钟出头，实际差 " + (d - now) + "ms", d - now in 59_000L..61_000L)
    }
}
