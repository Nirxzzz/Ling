package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * M4 助手主动发起提醒：[Remind] 里的纯逻辑。
 * 全 JVM（不碰 android.*）：意图判定 / 时间落点 / 标记解析与剥离 / 确认文案。
 *
 * 时区口径：所有期望值都用本机默认时区的 [Calendar] 算，和 [Remind] 内部一致 —— 不写死 UTC，
 * 换个时区跑也不会假红。
 */
class RemindTest {

    /** 基准时刻：本机时区 2026-10-10 12:00（正午，离跨天边界远） */
    private val now: Long = at(2026, Calendar.OCTOBER, 10, 12, 0)

    private val minute = 60_000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------- fromUser：命中 ----------------

    @Test fun remindInTwentyMinutes() {
        val a = Remind.fromUser("提醒我 20 分钟后喝水", now)!!
        assertEquals(now + 20 * minute, a.dueAt)
        assertEquals("喝水", a.text)
    }

    @Test fun tomorrowEightWakeUp() {
        val a = Remind.fromUser("明天8点叫我起床", now)!!
        assertEquals("起床", a.text)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 8, 0), a.dueAt)
    }

    @Test fun rememberFifteenOClock() {
        val a = Remind.fromUser("记得15点给妈打电话", now)!!
        assertEquals("给妈打电话", a.text)
        assertEquals(at(2026, Calendar.OCTOBER, 10, 15, 0), a.dueAt)
    }

    @Test fun bewareForgetDayAfterTomorrow() {
        val a = Remind.fromUser("别忘后天交材料", now)!!
        assertEquals("交材料", a.text)
        assertEquals(at(2026, Calendar.OCTOBER, 12, 9, 0), a.dueAt)   // 没说几点 → 当天 9:00
    }

    @Test fun spacesAroundTimeStillParse() {
        // 用户常在数字和时间词之间加空格：「20 分钟」「8 点」—— fromUser 会先去空白再交给 parseWhen
        assertEquals(now + 20 * minute, Remind.fromUser("提醒我 20 分钟后喝水", now)!!.dueAt)
        assertEquals(at(2026, Calendar.OCTOBER, 11, 8, 0), Remind.fromUser("明天 8 点叫我起床", now)!!.dueAt)
    }

    // ---------------- fromUser：不命中 ----------------

    @Test fun negationAndFeatureQuestionDoNotBuild() {
        assertNull(Remind.fromUser("我不需要提醒", now))
        assertNull(Remind.fromUser("提醒功能怎么开", now))
        assertNull(Remind.fromUser("不用提醒我明天的事", now))
        assertNull(Remind.fromUser("怎么关掉提醒设置", now))
    }

    @Test fun intentWithoutTimeDoesNotBuild() {
        assertNull(Remind.fromUser("提醒我", now))
        assertNull(Remind.fromUser("记得帮我看看", now))
        assertNull(Remind.fromUser("别忘带钥匙", now))
    }

    @Test fun timeWithoutIntentDoesNotBuild() {
        assertNull(Remind.fromUser("明天要考试", now))
        assertNull(Remind.fromUser("20 分钟后开会", now))
    }

    @Test fun blankAndGarbageDoNotThrow() {
        assertNull(Remind.fromUser("", now))
        assertNull(Remind.fromUser("   ", now))
        assertNull(Remind.fromUser("？", now))
        assertNull(Remind.fromUser("???", now))
    }

    // ---------------- fromReply：标记解析 + 剥离 ----------------

    @Test fun stripMarkerKeepBodyAndExtract() {
        val reply = "行，我记住这事了。\n[[提醒 2026-10-12T09:00|交论文]]\n到点我会提醒你。"
        val (body, asks) = Remind.fromReply(reply, now)
        assertFalse(body, body.contains("[["))
        assertFalse(body, body.contains(Remind.MARK_HEAD))
        assertTrue(body, body.contains("我记住这事了"))
        assertEquals(1, asks.size)
        assertEquals("交论文", asks[0].text)
        assertEquals(at(2026, Calendar.OCTOBER, 12, 9, 0), asks[0].dueAt)
    }

    @Test fun multipleMarkersExtractAllAndCollapse() {
        val reply = "好。\n[[提醒 2026-10-12T09:00|交论文]]\n[[提醒 2026-10-13T20:30|给妈打电话]]"
        val (body, asks) = Remind.fromReply(reply, now)
        assertEquals(listOf("交论文", "给妈打电话"), asks.map { it.text })
        assertEquals(at(2026, Calendar.OCTOBER, 13, 20, 30), asks[1].dueAt)
        assertEquals("好。", body)
    }

    @Test fun malformedMarkerStrippedButNotBuilt() {
        val reply = "好的。\n[[提醒 乱写的]]\n就这样。"
        val (body, asks) = Remind.fromReply(reply, now)
        assertTrue(asks.isEmpty())
        assertFalse(body, body.contains("[["))
        assertEquals("好的。\n就这样。", body)
    }

    @Test fun illegalDateStrippedNotBuilt() {
        val (body, asks) = Remind.fromReply("[[提醒 2026-13-99T99:99|假的]]", now)
        assertTrue(asks.isEmpty())
        assertEquals("", body)
    }

    @Test fun halfOpenMarkerIsStrippedNotLeaked() {
        val (body, asks) = Remind.fromReply("先这样\n[[提醒 2026-10-12T09:00|没闭合\n后一句", now)
        assertTrue(asks.isEmpty())
        assertFalse(body, body.contains("[["))
        assertFalse(body, body.contains(Remind.MARK_HEAD))
        assertTrue(body, body.contains("后一句"))
    }

    @Test fun noMarkerLeavesReplyUnchanged() {
        val (body, asks) = Remind.fromReply("今天天气不错。", now)
        assertEquals("今天天气不错。", body)
        assertTrue(asks.isEmpty())
    }

    @Test fun blankReplyIsEmptyPair() {
        assertEquals("" to emptyList<Remind.Ask>(), Remind.fromReply("", now))
        assertEquals("" to emptyList<Remind.Ask>(), Remind.fromReply("   ", now))
    }

    @Test fun markerOnlyLeavesEmptyBody() {
        val (body, asks) = Remind.fromReply("[[提醒 2026-10-12T09:00|交论文]]", now)
        assertEquals("", body)
        assertEquals(1, asks.size)
    }

    @Test fun emptyTextAfterPipeNotBuilt() {
        val (body, asks) = Remind.fromReply("[[提醒 2026-10-12T09:00|   ]]", now)
        assertTrue(asks.isEmpty())
        assertFalse(body, body.contains("[["))
    }

    // ---------------- whenText / confirm ----------------

    @Test fun whenTextPhases() {
        assertEquals("今天 08:00", Remind.whenText(at(2026, Calendar.OCTOBER, 10, 8, 0), now))
        assertEquals("明天 08:00", Remind.whenText(at(2026, Calendar.OCTOBER, 11, 8, 0), now))
        assertEquals("后天 09:00", Remind.whenText(at(2026, Calendar.OCTOBER, 12, 9, 0), now))
        assertEquals("3 天后 08:00", Remind.whenText(at(2026, Calendar.OCTOBER, 13, 8, 0), now))
    }

    @Test fun confirmTextSingleAndMultiple() {
        // v1.86 去掉了行首那个 ⏰ emoji（各家 ROM 字形/基线不一，用户明确说不要 emoji）
        assertEquals(
            "记下了：明天 08:00 提醒你「起床」",
            Remind.confirm(Remind.Ask("起床", at(2026, Calendar.OCTOBER, 11, 8, 0)), now),
        )
        assertEquals(
            "记下了：明天 08:00 提醒你「起床」\n记下了：后天 09:00 提醒你「交材料」",
            Remind.confirmAll(
                listOf(
                    Remind.Ask("起床", at(2026, Calendar.OCTOBER, 11, 8, 0)),
                    Remind.Ask("交材料", at(2026, Calendar.OCTOBER, 12, 9, 0)),
                ),
                now,
            ),
        )
        // 确认文案里不该再有任何 emoji（用户明确要求）
        assertFalse(Remind.confirm(Remind.Ask("起床", now + 3600_000), now), hasEmoji(Remind.confirm(Remind.Ask("起床", now + 3600_000), now)))
    }

    @Test fun markerHeadMatchesTheFormatWeWrite() {
        assertEquals("[[提醒", Remind.MARK_HEAD)
        assertTrue("[[提醒 2026-10-12T09:00|x]]".startsWith(Remind.MARK_HEAD))
    }
}
