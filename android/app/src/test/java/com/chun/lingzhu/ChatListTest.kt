package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [ChatList]：会话列表的纯逻辑。时间期望值一律用本机 Calendar 现算（RemindTest 口径，不写死 UTC）。
 */
class ChatListTest {
    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    private fun s(pid: String, at: Long, pinned: Boolean = false, order: Int = 0, unread: Int = 0, name: String = pid) =
        ChatList.Session(pid, name, "", at, unread, pinned, order)

    // ---------------- 时间标签 ----------------

    /** 今天只给钟点，补零（微信口径：今天的消息不写日期） */
    @Test fun todayShowsClockOnly() {
        val now = cal(2026, 10, 19, 23, 0)
        assertEquals("08:05", ChatList.timeLabel(cal(2026, 10, 19, 8, 5), now))
        assertEquals("00:00", ChatList.timeLabel(cal(2026, 10, 19, 0, 0), now))
    }

    /** 昨天（含跨零点那一分钟）只写「昨天」，不带钟点 —— 列表右上角放不下两段 */
    @Test fun yesterdayIsWordOnly() {
        val now = cal(2026, 10, 19, 0, 1)
        assertEquals("昨天", ChatList.timeLabel(cal(2026, 10, 18, 23, 59), now))
        assertEquals("昨天", ChatList.timeLabel(cal(2026, 10, 18, 0, 0), now))
    }

    /** 前天及更早：今年写「M月d日」，跨年写「yyyy/M/d」；跨年的「昨天」仍是昨天 */
    @Test fun olderShowsDate() {
        val now = cal(2026, 10, 19, 12, 0)
        assertEquals("10月17日", ChatList.timeLabel(cal(2026, 10, 17, 23, 59), now))
        assertEquals("2025/12/30", ChatList.timeLabel(cal(2025, 12, 30, 9, 0), now))
        assertEquals("昨天", ChatList.timeLabel(cal(2025, 12, 31, 22, 0), cal(2026, 1, 1, 8, 0)))
    }

    /** 没聊过（at = 0）不显示时间；设备时钟往回拨导致消息「在未来」时按钟点显示，不写成日期吓人 */
    @Test fun noMessageAndFutureEdge() {
        val now = cal(2026, 10, 19, 12, 0)
        assertEquals("", ChatList.timeLabel(0L, now))
        assertEquals("09:00", ChatList.timeLabel(cal(2026, 10, 20, 9, 0), now))
    }

    // ---------------- 排序 ----------------

    /** 置顶永远在前；同组内新→旧；都没聊过的沉底且按人格原顺序 */
    @Test fun sortPinnedThenRecentThenNeverChatted() {
        val list = listOf(
            s("p0", 100, pinned = true, order = 0),
            s("p1", 500, order = 1),
            s("p2", 0, order = 2),
            s("p3", 900, order = 3),
            s("p4", 0, order = 4),
        )
        assertEquals(listOf("p0", "p3", "p1", "p2", "p4"), ChatList.sort(list).map { it.pid })
    }

    /** 取消小Ling置顶后它就按时间和别人一起排 —— 置顶是用户可改的，不是写死 p0 第一 */
    @Test fun unpinnedP0SortsByTime() {
        val list = listOf(s("p0", 100, order = 0), s("p1", 500, order = 1))
        assertEquals(listOf("p1", "p0"), ChatList.sort(list).map { it.pid })
    }

    /** 同一毫秒按人格顺序：结果确定，轮询重画不会让两行来回跳 */
    @Test fun tieBreaksByOrder() {
        val list = listOf(s("b", 300, order = 2), s("a", 300, order = 1))
        assertEquals(listOf("a", "b"), ChatList.sort(list).map { it.pid })
    }

    // ---------------- 未读 ----------------

    /** 只数已读位置之后 **Agent** 说的；用户自己发的不算未读 */
    @Test fun unreadCountsOnlyOrbAfterMark() {
        val l = listOf(
            ChatStore.user("q", 10), ChatStore.orb("a", 11),
            ChatStore.user("q2", 20), ChatStore.orb("a2", 21), ChatStore.orb("a3", 22),
        )
        assertEquals(2, ChatList.unread(l, 20))
        assertEquals(0, ChatList.unread(l, 22))
        assertEquals(3, ChatList.unread(l, 0))
    }

    /** 从没记过已读（老用户升级上来）= 0，绝不一升级就挂 200 条未读 */
    @Test fun noMarkMeansZero() {
        assertEquals(0, ChatList.unread(listOf(ChatStore.orb("a", 11)), null))
    }

    @Test fun badgeAndTitle() {
        assertEquals("", ChatList.badge(0))
        assertEquals("7", ChatList.badge(7))
        assertEquals("99", ChatList.badge(99))
        assertEquals("99+", ChatList.badge(100))
        assertEquals("聊天", ChatList.title(0))
        assertEquals("聊天(3)", ChatList.title(3))
        assertEquals(3, ChatList.totalUnread(listOf(s("a", 1, unread = 1), s("b", 1, unread = 2))))
    }

    // ---------------- 摘要 ----------------

    /** 多行 / Markdown 压成一行；只有图写「[图片]」；图 + 字两样都给 */
    @Test fun summaryFlattens() {
        assertEquals("标题 第一行 加粗", ChatList.summary(ChatStore.orb("## 标题\n\n第一行\n**加粗**", 1)))
        assertEquals("[图片]", ChatList.summary(ChatStore.user("", 1, img = "x.jpg")))
        assertEquals("[图片] 看这个", ChatList.summary(ChatStore.user("看这个", 1, img = "x.jpg")))
        assertEquals("", ChatList.summary(null))
        val long = "长".repeat(500)
        assertEquals(ChatList.SUMMARY_CHARS, ChatList.summary(ChatStore.orb(long, 1)).length)
    }

    // ---------------- 搜索 ----------------

    /** 命中消息时摘要和时间换成「最近一条命中的」；只命中名字的保持原样；都不中的不出现 */
    @Test fun searchByNameOrContent() {
        val list = listOf(s("p0", 30, name = "小Ling"), s("p1", 40, name = "小舟"), s("p2", 50, name = "小码"))
        val msgs = mapOf(
            "p0" to listOf(ChatStore.user("背单词 abandon", 10), ChatStore.orb("好", 30)),
            "p1" to listOf(ChatStore.orb("今天学数学", 40)),
            "p2" to listOf(ChatStore.orb("看报错", 50)),
        )
        val r = ChatList.search(list, msgs, "ABANDON")
        assertEquals(listOf("p0"), r.map { it.pid })
        assertEquals("背单词 abandon", r[0].summary)
        assertEquals(10L, r[0].lastAt)
        assertEquals(listOf("p1"), ChatList.search(list, msgs, "小舟").map { it.pid })
        assertTrue(ChatList.search(list, msgs, "没有这个词").isEmpty())
        assertEquals(list, ChatList.search(list, msgs, "  "))
    }

    // ---------------- 提示条 / 首字 ----------------

    @Test fun setupHintMatchesOldChatCopy() {
        assertNull(ChatList.setupHint(noOverlay = false, noApi = false))
        assertEquals("还差 2 项：接口地址、悬浮窗权限 · 点这里去设置", ChatList.setupHint(true, true))
        assertEquals("还没连接模型 —— 填上接口地址才能聊 · 点这里去设置", ChatList.setupHint(false, true))
        assertEquals("悬浮球未授权（识别屏幕 / 弹单词要用）· 点这里去开启", ChatList.setupHint(true, false))
    }

    @Test fun initialTakesFirstCodePoint() {
        assertEquals("新", ChatList.initial("新 Agent"))
        assertEquals("A", ChatList.initial("agent"))
        assertEquals("A", ChatList.initial("  "))
        assertFalse(ChatList.initial("𠀀字").length == 1)   // 补充平面汉字是两个 char，不能劈成半个
    }
}
