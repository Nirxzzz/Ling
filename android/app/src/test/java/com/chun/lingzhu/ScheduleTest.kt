package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.86 记期：规则纯逻辑（掩码 / 到点时刻 / 物化 / 自然语言）。全 JVM，不碰 android。
 *
 * 时区口径：所有期望值都用本机默认时区的 [Calendar] 现算，和实现一致 —— 不写死 UTC，换时区跑也不会假红。
 */
class ScheduleTest {
    /** 基准：本机时区 2026-10-10 12:00（正午，离跨天边界远） */
    private val now: Long = at(2026, Calendar.OCTOBER, 10, 12, 0)
    private val hour = 3600_000L
    private val minute = 60_000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun rule(
        text: String = "背单词",
        weekly: Boolean = true,
        mask: Int = Schedule.maskOf(listOf(1, 3, 5)),
        dateMs: Long = 0L,
        minuteOfDay: Int = 20 * 60,
        times: Int = 1,
        everyMin: Int = 10,
        enabled: Boolean = true,
        id: Long = 1L,
    ) = Schedule.Rule(id, text, weekly, mask, dateMs, minuteOfDay, times, everyMin, Schedule.VIBE_GLOBAL, enabled, 0L)

    // ---------------- 掩码 / 文案 ----------------

    @Test fun maskRoundTrip() {
        val m = Schedule.maskOf(listOf(1, 3, 5))
        assertEquals(listOf(1, 3, 5), Schedule.daysOf(m))
        assertEquals("每周一、三、五", Schedule.weekdayLabel(m))
        assertTrue(Schedule.hasDay(m, 3))
        assertFalse(Schedule.hasDay(m, 2))
        assertEquals("每天", Schedule.weekdayLabel(Schedule.maskOf(1..7)))
        assertEquals("每周（还没选日子）", Schedule.weekdayLabel(0))
    }

    @Test fun maskIgnoresOutOfRange() {
        assertEquals(Schedule.maskOf(listOf(0, 8, 3)), Schedule.maskOf(listOf(3)))
        assertFalse(Schedule.hasDay(0b1111111, 0))
        assertFalse(Schedule.hasDay(0b1111111, 8))
    }

    /** Calendar 的 DAY_OF_WEEK（周日=1）→ ISO（周一=1、周日=7） */
    @Test fun isoDowMapping() {
        assertEquals(1, Schedule.isoDow(Calendar.MONDAY))
        assertEquals(7, Schedule.isoDow(Calendar.SUNDAY))
        assertEquals(6, Schedule.isoDow(Calendar.SATURDAY))
        assertEquals(Schedule.isoOf(now), Schedule.isoDow(Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_WEEK)))
    }

    // ---------------- 到点时刻 ----------------

    @Test fun firesOnOnlyOnMatchingWeekday() {
        val r = rule(mask = Schedule.maskOf(listOf(Schedule.isoOf(now))))
        val today = Todo.dayStart(now)
        assertEquals(listOf(today + 20 * hour), Schedule.firesOn(r, today))
        // 明天不是这一条的日子 → 空
        assertEquals(emptyList<Long>(), Schedule.firesOn(r, Schedule.plusDays(today, 1)))
    }

    @Test fun firesOnHonorsTimesAndEvery() {
        val r = rule(mask = Schedule.maskOf(listOf(Schedule.isoOf(now))), minuteOfDay = 15 * 60, times = 3, everyMin = 10)
        val today = Todo.dayStart(now)
        val f = Schedule.firesOn(r, today)
        assertEquals(3, f.size)
        assertEquals(today + 15 * hour, f[0])
        assertEquals(today + 15 * hour + 10 * minute, f[1])
        assertEquals(today + 15 * hour + 20 * minute, f[2])
    }

    @Test fun oneOffRuleOnlyFiresOnItsOwnDay() {
        val today = Todo.dayStart(now)
        val r = rule(weekly = false, dateMs = Schedule.plusDays(today, 2), minuteOfDay = 9 * 60)
        assertEquals(emptyList<Long>(), Schedule.firesOn(r, today))
        assertEquals(listOf(Schedule.plusDays(today, 2) + 9 * hour), Schedule.firesOn(r, Schedule.plusDays(today, 2)))
    }

    @Test fun plusDaysKeepsMidnight() {
        val s = Todo.dayStart(now)
        val p = Schedule.plusDays(s, 3)
        assertEquals(3, ((p - s) / 86_400_000L).toInt())
        assertEquals(p, Todo.dayStart(p))
    }

    // ---------------- 物化 ----------------

    @Test fun planSkipsPastPointsAndSorts() {
        val today = Todo.dayStart(now)
        val iso = Schedule.isoOf(now)
        val past = rule(id = 1, mask = Schedule.maskOf(listOf(iso)), minuteOfDay = 8 * 60)      // 今天 8:00 已过
        val future = rule(id = 2, mask = Schedule.maskOf(listOf(iso)), minuteOfDay = 13 * 60)    // 今天 13:00
        val f = Schedule.plan(listOf(past, future), now)
        // past：今天那次已过 → 只剩下周那天；future：今天 + 下周各一次 = 3 个点
        assertEquals(3, f.size)
        assertEquals(13 * hour, f[0].dueAt - today)
        assertEquals(2L, f[0].ruleId)
        assertTrue("一个已过的点都不该进来", f.all { it.dueAt >= now })
    }

    @Test fun planRespectsHorizonAndCap() {
        val r = rule(mask = Schedule.maskOf(1..7), times = 5, everyMin = 5)   // 每天 5 个点
        val f = Schedule.plan(listOf(r), now)
        assertTrue("点数要被上限压住：${f.size}", f.size <= Schedule.MAX_FIRES_PER_RULE)
        val today = Todo.dayStart(now)
        assertTrue("最远不该超出物化窗口", f.maxOf { it.dueAt } <= Schedule.plusDays(today, Schedule.HORIZON_DAYS))
    }

    @Test fun planSkipsDisabledAndBlank() {
        assertEquals(emptyList<Schedule.Fire>(), Schedule.plan(listOf(rule(enabled = false)), now))
        assertEquals(emptyList<Schedule.Fire>(), Schedule.plan(listOf(rule(text = "  ")), now))
    }

    @Test fun nextFireFindsTheSoonest() {
        val today = Todo.dayStart(now)
        val iso = Schedule.isoOf(now)
        val r = rule(mask = Schedule.maskOf(listOf(iso)), minuteOfDay = 18 * 60)
        assertEquals(today + 18 * hour, Schedule.nextFire(r, now))
        // 时间已过 → 顺延到下个同一天
        val late = rule(mask = Schedule.maskOf(listOf(iso)), minuteOfDay = 8 * 60)
        assertEquals(Schedule.plusDays(today, 7) + 8 * hour, Schedule.nextFire(late, now))
    }

    // ---------------- 自然语言 → 规则 ----------------

    @Test fun parseWeeklyDaysAndEveningHour() {
        val r = Schedule.parse("每周一三五晚上八点提醒我背单词", now)!!
        assertTrue(r.weekly)
        assertEquals(listOf(1, 3, 5), Schedule.daysOf(r.mask))
        assertEquals(20 * 60, r.minute)
        assertTrue(r.text, r.text.contains("背单词"))
        assertFalse("结构词要剥干净", r.text.contains("每周"))
        assertFalse(r.text, r.text.contains("八点"))
    }

    @Test fun parseEveryDayWithPmHour() {
        val r = Schedule.parse("每天下午3点提醒我背50个词", now)!!
        assertEquals(7, Schedule.daysOf(r.mask).size)
        assertEquals(15 * 60, r.minute)
        assertTrue(r.text, r.text.contains("背"))
    }

    @Test fun parseCountsTimes() {
        val r = Schedule.parse("每周二9点提醒我3次开会", now)!!
        assertEquals(listOf(2), Schedule.daysOf(r.mask))
        assertEquals(3, r.times)
    }

    @Test fun parseRejectsNonRecurring() {
        assertNull(Schedule.parse("提醒我明天8点起床", now))
        assertNull(Schedule.parse("20 分钟后开会", now))
        assertNull(Schedule.parse("每周一提醒我开会", now))      // 有周几但没钟点
        assertNull(Schedule.parse("", now))
        assertNull(Schedule.parse("   ", now))
    }

    @Test fun weekdayCharMapping() {
        assertEquals(1, Schedule.cnWeekday('一'))
        assertEquals(7, Schedule.cnWeekday('日'))
        assertEquals(7, Schedule.cnWeekday('天'))
        assertNull(Schedule.cnWeekday('x'))
    }

    // ---------------- 新增 / 去重 / 文案 ----------------

    @Test fun addAssignsIdAndDedupesSameSlot() {
        val (l1, id1) = Schedule.add(emptyList(), rule(text = "背单词", id = 0L))
        assertTrue(id1 > 0)
        val (l2, id2) = Schedule.add(l1, rule(text = "背单词", id = 0L))
        assertEquals("同一天同一时间同一内容 → 不重复记", 1, l2.size)
        assertEquals(id1, id2)
        val (l3, _) = Schedule.add(l1, rule(text = "背单词", id = 0L, minuteOfDay = 21 * 60))
        assertEquals("时间不同就是另一条", 2, l3.size)
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(
            rule(id = 1, text = "背单词", mask = Schedule.maskOf(listOf(1, 3, 5)), minuteOfDay = 20 * 60, times = 2, everyMin = 15),
            rule(id = 2, text = "开会", weekly = false, dateMs = at(2026, Calendar.OCTOBER, 20, 0, 0), minuteOfDay = 9 * 60, enabled = false),
        )
        assertEquals(l, Schedule.decode(Schedule.toJson(l)))
    }

    @Test fun parseToleratesGarbageAndOldFiles() {
        assertTrue(Schedule.decode(null).isEmpty())
        assertTrue(Schedule.decode("[坏").isEmpty())
        assertTrue(Schedule.decode("""[{"id":1,"text":"  "}]""").isEmpty())
        // 老文件没有 strength / enabled → 用默认（跟全局 / 开着）
        val r = Schedule.decode("""[{"id":1,"text":"x","weekly":true,"mask":2,"minute":600}]""").single()
        assertEquals(Schedule.VIBE_GLOBAL, r.strength)
        assertTrue(r.enabled)
        assertEquals(1, r.times)
    }

    @Test fun describeIsReadable() {
        val s = Schedule.describe(rule(text = "背单词", times = 2, everyMin = 10))
        assertTrue(s, s.contains("每周一、三、五"))
        assertTrue(s, s.contains("20:00"))
        assertTrue(s, s.contains("2 次"))
        assertTrue(Schedule.describe(rule(enabled = false)).contains("已停"))
    }

    // ---------------- 日历月份工具 ----------------

    @Test fun monthToolsAreConsistent() {
        assertEquals(31, Schedule.Month.daysInMonth(2026, Calendar.OCTOBER))
        assertEquals(28, Schedule.Month.daysInMonth(2026, Calendar.FEBRUARY))
        val first = Schedule.Month.dayStart(2026, Calendar.OCTOBER, 1)
        assertEquals(first, Todo.dayStart(first))
        assertEquals(2026 to Calendar.OCTOBER, Schedule.Month.ym(first))
        assertEquals("10 月 1 日", Schedule.Month.formatDate(first))
        val iso = Schedule.Month.firstIso(2026, Calendar.OCTOBER)
        assertTrue("2026-10-01 是周四", iso in 1..7)
        assertEquals(Schedule.isoOf(first), iso)
    }
}
