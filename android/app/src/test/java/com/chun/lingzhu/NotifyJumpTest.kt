package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知点进来的落点映射（v1.78 修回归）。
 *
 * 早晚简报（[Briefing]）和随手记「看全部」（[Memo]）都发 PendingIntent 指向 [MainActivity]，
 * 靠一个 extra 说明「我该去哪一栏」。10 页并 5 页那次重构把处理器弄丢了，两个 extra 被静默忽略 ——
 * 点通知只打开设置首页，用户还得自己摸到「提醒」大区里的「待办与随手记」。
 *
 * 落点本身（[MainActivity.todoTabOf]）是纯函数，这里把它钉死：
 * 0 = 待办（简报说的就是「今天有什么事」），1 = 随手记（「看全部」要的就是刚记下的那条）。
 * 键名字面量不在这里钉 —— 它是 `const val`，编译期内联，断言两边都是字面量，钉了也是空转。
 */
class NotifyJumpTest {

    /** 早晚简报 → 记事页的「待办」栏 */
    @Test fun briefingNotificationLandsOnTodoTab() {
        assertEquals(0, MainActivity.todoTabOf(assist = true, memo = false))
    }

    /** 随手记「看全部」→ 记事页的「随手记」栏 */
    @Test fun memoNotificationLandsOnMemoTab() {
        assertEquals(1, MainActivity.todoTabOf(assist = false, memo = true))
    }

    /** 两个 extra 都没有 = 不是这俩通知来的，返回 -1，调用方据此放手走设置页自己的跳转 */
    @Test fun otherIntentsAreNotOurs() {
        assertEquals(-1, MainActivity.todoTabOf(assist = false, memo = false))
    }

    /** 万一两个都带：随手记优先 —— 它指的是「把刚记下的东西给我看」，比简报更具体 */
    @Test fun memoWinsWhenBothExtrasAreSet() {
        assertEquals(1, MainActivity.todoTabOf(assist = true, memo = true))
    }

    /** 落点下标必须是「记事」页真实存在的两栏之一（0 待办 / 1 随手记），不能越界到不存在的 tab */
    @Test fun tabIndexStaysWithinTheTwoRealColumns() {
        val valid = setOf(0, 1)
        assertTrue(MainActivity.todoTabOf(assist = true, memo = false) in valid)
        assertTrue(MainActivity.todoTabOf(assist = false, memo = true) in valid)
    }
}
