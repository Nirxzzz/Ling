package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.86「一句话 → 自动排提醒」：[Remind.plan] / [Remind.fromReplyFull] 的纯逻辑。
 * 一次性走 [Remind.Ask]、重复的走 [Schedule.Rule]，标记一律从展示正文里剥干净。
 */
class RemindPlanTest {

    /** 基准：本机时区 2026-10-10 12:00 */
    private val now: Long = at(2026, Calendar.OCTOBER, 10, 12, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------- plan ----------------

    @Test fun oneOffStillGoesThroughTheOldPath() {
        val p = Remind.plan("提醒我 20 分钟后喝水", now)
        assertEquals(1, p.asks.size)
        assertTrue(p.rules.isEmpty())
        assertEquals("喝水", p.asks[0].text)
        assertEquals(now + 20 * 60_000L, p.asks[0].dueAt)
    }

    @Test fun weeklySentenceBecomesARule() {
        val p = Remind.plan("每周一三五晚上八点提醒我背单词", now)
        assertTrue("重复的不该建一次性", p.asks.isEmpty())
        assertEquals(1, p.rules.size)
        val r = p.rules[0]
        assertTrue(r.weekly)
        assertEquals(listOf(1, 3, 5), Schedule.daysOf(r.mask))
        assertEquals(20 * 60, r.minute)
        assertEquals("意图词和结构词都要剥掉", "背单词", r.text)
    }

    @Test fun everyDayWithAfternoonHour() {
        val p = Remind.plan("每天下午3点提醒我背50个词", now)
        assertEquals(1, p.rules.size)
        assertEquals(7, Schedule.daysOf(p.rules[0].mask).size)
        assertEquals(15 * 60, p.rules[0].minute)
        assertTrue(p.rules[0].text, p.rules[0].text.startsWith("背"))
    }

    @Test fun oneSentenceWithTwoThings() {
        val p = Remind.plan("提醒我明天8点起床，9点开会", now)
        assertEquals(listOf("起床", "开会"), p.asks.map { it.text })
        assertEquals(at(2026, Calendar.OCTOBER, 11, 8, 0), p.asks[0].dueAt)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 9, 0), p.asks[1].dueAt)
    }

    @Test fun nonReminderSentencesBuildNothing() {
        assertTrue(Remind.plan("我每天下午3点都有课", now).isEmpty)
        assertTrue(Remind.plan("明天要考试", now).isEmpty)
        assertTrue(Remind.plan("我不需要提醒", now).isEmpty)
        assertTrue(Remind.plan("提醒功能怎么开", now).isEmpty)
        assertTrue(Remind.plan("提醒我", now).isEmpty)
        assertTrue(Remind.plan("", now).isEmpty)
        assertTrue(Remind.plan("   ", now).isEmpty)
    }

    @Test fun inheritedIntentNeedsATime() {
        // 第二句没写「提醒我」，但第一句认出来了 → 只要它自带时间就也算一条
        val p = Remind.plan("提醒我明天8点起床。顺便买牛奶", now)
        assertEquals(1, p.asks.size)
        assertEquals("没时间的那半句不能当提醒", "起床", p.asks[0].text)
    }

    // ---------------- 模型回复里的「记期」标记 ----------------

    @Test fun ruleMarkerExtractedAndStripped() {
        val reply = "行，我给你定成每周的。\n[[记期 1,3,5 20:00|背单词]]\n到点叫你。"
        val (body, asks, rules) = Remind.fromReplyFull(reply, now)
        assertFalse(body, body.contains("[["))
        assertFalse(body, body.contains(Remind.RULE_HEAD))
        assertTrue(asks.isEmpty())
        assertEquals(1, rules.size)
        assertEquals(listOf(1, 3, 5), Schedule.daysOf(rules[0].mask))
        assertEquals(20 * 60, rules[0].minute)
        assertEquals("背单词", rules[0].text)
    }

    @Test fun malformedRuleMarkerIsStrippedNotBuilt() {
        val (body, _, rules) = Remind.fromReplyFull("好的。\n[[记期 乱写的]]\n就这样。", now)
        assertTrue(rules.isEmpty())
        assertFalse(body, body.contains("[["))
        assertEquals("好的。\n就这样。", body)
    }

    @Test fun bothMarkersInOneReply() {
        val reply = "[[提醒 2026-10-12T09:00|交论文]]\n[[记期 7 20:00|周报]]"
        val (body, asks, rules) = Remind.fromReplyFull(reply, now)
        assertEquals("", body)
        assertEquals(1, asks.size)
        assertEquals(1, rules.size)
        assertEquals(listOf(7), Schedule.daysOf(rules[0].mask))
    }

    @Test fun oldFromReplyStillWorks() {
        val (body, asks) = Remind.fromReply("好的。\n[[提醒 2026-10-12T09:00|交论文]]", now)
        assertEquals("好的。", body)
        assertEquals(1, asks.size)
    }

    // ---------------- 回执文案 ----------------

    @Test fun confirmRuleHasNoEmojiAndReadsWell() {
        val r = Schedule.Rule(1L, "背单词", weekly = true, mask = Schedule.maskOf(listOf(1, 3, 5)), minute = 20 * 60, times = 2)
        val s = Remind.confirmRule(r)
        assertFalse(s, hasEmoji(s))
        assertTrue(s, s.contains("每周一、三、五"))
        assertTrue(s, s.contains("20:00"))
        assertTrue(s, s.contains("背单词"))
        assertTrue(s, s.contains("2 次"))
    }

    @Test fun confirmPlanJoinsBothKinds() {
        val p = Remind.Plan(
            listOf(Remind.Ask("起床", at(2026, Calendar.OCTOBER, 11, 8, 0))),
            listOf(Schedule.Rule(1L, "背单词", weekly = true, mask = Schedule.maskOf(listOf(1)), minute = 20 * 60)),
        )
        val s = Remind.confirmPlan(p, now)
        assertTrue(s, s.contains("起床"))
        assertTrue(s, s.contains("背单词"))
        assertEquals(2, s.lines().size)
        assertFalse(s, hasEmoji(s))
    }
}
