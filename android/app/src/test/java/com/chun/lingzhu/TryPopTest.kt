package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.80 试弹（[TryPop]）的纯逻辑：到点时刻怎么说、卡片的尺寸与节拍是否合理。
 *
 * 卡片本身（浮在球边上那张）碰 Android，不在这儿；这里钉的是「文案不会说错时间」这件事 ——
 * 提醒最忌讳的就是时间说错（把「就现在」说成「明天 15:30」，用户就不敢信它了）。
 */
class TryPopTest {

    /** 用 Calendar 造一个确定的「现在」，避免测试依赖运行时刻（跨天跑也不会红） */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, y); set(Calendar.MONTH, mo - 1); set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val now = at(2026, 10, 10, 10, 0, 0)

    // ---------------- 到点时刻的说法 ----------------

    /** 刚好到点 / 已经过期一点点：都算「就现在」（闹钟多半就在这一刻响） */
    @Test fun justDueIsNow() {
        assertEquals("就现在", TryPop.whenLabel(now, now))
        assertEquals("就现在", TryPop.whenLabel(now - 5_000, now))
        assertEquals("就现在", TryPop.whenLabel(now + 60_000, now))
    }

    /** 一小时内：分钟数往上取整到「N 分钟后」 */
    @Test fun withinAnHourUsesMinutes() {
        assertEquals("1 分钟后", TryPop.whenLabel(now + 60_001, now))
        assertEquals("3 分钟后", TryPop.whenLabel(now + 200_000, now))
        assertEquals("59 分钟后", TryPop.whenLabel(now + 59 * 60_000L, now))
    }

    /** 今天晚些时候（一小时以后）：给钟点，不给「N 分钟后」 */
    @Test fun laterTodayUsesClock() {
        assertEquals("今天 15:30", TryPop.whenLabel(at(2026, 10, 10, 15, 30), now))
        assertEquals("今天 12:00", TryPop.whenLabel(at(2026, 10, 10, 12, 0), now))
    }

    /** 明天同一时刻：写「明天」而不是日期 */
    @Test fun tomorrowSaysTomorrow() {
        assertEquals("明天 09:00", TryPop.whenLabel(at(2026, 10, 11, 9, 0), now))
    }

    /** 再远就写日期（跨月、跨天都走这条） */
    @Test fun furtherOutUsesDate() {
        assertEquals("10月13日 08:00", TryPop.whenLabel(at(2026, 10, 13, 8, 0), now))
        assertEquals("11月1日 07:30", TryPop.whenLabel(at(2026, 11, 1, 7, 30), now))
    }

    /** 抬头固定带「提醒 · 」前缀（卡上第一行） */
    @Test fun headingCarriesThePrefix() {
        assertTrue(TryPop.heading(now, now).startsWith("提醒 · "))
        assertEquals("提醒 · 就现在", TryPop.heading(now, now))
    }

    // ---------------- 卡片规格 ----------------

    /** 停留时间要有意义：太短看不清，太长等于占着屏幕不走 */
    @Test fun stayDurationIsInTheSaneWindow() {
        assertTrue("试弹停留应该够看清又不过夜：${TryPop.STAY_MS}", TryPop.STAY_MS in 3_000..15_000)
    }

    /**
     * 震动必须**短**：波形是「停、震、停、震」成对出现，总时长压在半秒内。
     * 这是「试弹」和「骚扰」的分界线 —— 长震/循环震属于电话级提醒，不是拍一下肩膀。
     */
    @Test fun vibrationIsTwoShortTicks() {
        val p = TryPop.PATTERN
        assertEquals("节拍应该是成对的（停、震、停、震）", 0, p.size % 2)
        assertEquals("开头不能是先震（要给系统一点启动时间）", 0L, p[0])
        assertTrue("总时长要压在半秒内：${p.sum()}", p.sum() < 500)
        assertEquals("只震两下", 2, p.filterIndexed { i, _ -> i % 2 == 1 }.size)
        assertTrue("单次震动要短（<60ms）", p.filterIndexed { i, _ -> i % 2 == 1 }.all { it < 60 })
    }

    /** 卡片宽度是个「小卡」的宽度：比球宽，但远不到半屏 */
    @Test fun cardIsSmallButReadable() {
        assertTrue("卡片太窄放不下两行字：${TryPop.CARD_W_DP}", TryPop.CARD_W_DP >= 200f)
        assertTrue("卡片不该占半屏：${TryPop.CARD_W_DP}", TryPop.CARD_W_DP <= 300f)
    }
}
