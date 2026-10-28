package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 记事本：数据模型与旧随手记迁移。
 *
 * 迁移是**一次性且不可逆**的操作，出错的代价是用户历史丢失。
 * 所以幂等性在这里是最重要的一条。
 */
class NoteTest {

    // ── 基本模型 ─────────────────────────────────────────

    @Test fun createSetsTimestamps() {
        val n = Note.create("内容", 1000L)
        assertEquals(1000L, n.createdAt)
        assertEquals(1000L, n.updatedAt)
        assertEquals(1, n.rev)
    }

    @Test fun uidIsUnique() {
        val a = Note.create("x", 1L)
        val b = Note.create("x", 1L)
        assertNotEquals("同一毫秒建两条也不能撞 uid", a.uid, b.uid)
    }

    @Test fun bodyIsTruncatedNotRejected() {
        // 打了半天的字，宁可少存一点也不要「太长无法保存」
        val huge = "字".repeat(Note.MAX_BODY + 500)
        val n = Note.create(huge, 1L)
        assertEquals(Note.MAX_BODY, n.body.length)
    }

    @Test fun displayTitleFallsBackToFirstLine() {
        val n = Note.create("\n\n第一行正文\n第二行", 1L)
        assertEquals("第一行正文", n.displayTitle)
    }

    @Test fun displayTitlePrefersExplicitTitle() {
        val n = Note.create("正文", 1L).copy(title = "我起的标题")
        assertEquals("我起的标题", n.displayTitle)
    }

    @Test fun emptyBodyHasPlaceholderTitle() {
        assertEquals("（空笔记）", Note.create("", 1L).displayTitle)
    }

    @Test fun longFirstLineIsEllipsized() {
        val n = Note.create("字".repeat(200), 1L)
        assertTrue("要截断", n.displayTitle.length <= Note.MAX_TITLE)
        assertTrue("截断要有省略号", n.displayTitle.endsWith("…"))
    }

    // ── 回收站 ───────────────────────────────────────────

    @Test fun trashExpiresAfter30Days() {
        val day = 24L * 3600L * 1000L
        assertFalse(Note.trashExpired(1000L, 1000L + 29 * day))
        assertTrue(Note.trashExpired(1000L, 1000L + 30 * day))
    }

    @Test fun notDeletedIsNeverExpired() {
        assertFalse(Note.trashExpired(0L, Long.MAX_VALUE / 2))
    }

    // ── 迁移 ─────────────────────────────────────────────

    private fun memo(id: Long, text: String, at: Long = 100L, todoId: Long = 0L) =
        Memo.Item(id, text, at, todoId)

    @Test fun migrateMapsFields() {
        val out = Note.migrateMemos(listOf(memo(7L, "旧随手记")), emptySet())
        assertEquals(1, out.size)
        assertEquals("旧随手记", out[0].body)
        assertEquals("memo", out[0].source)
        assertEquals(Note.LEGACY_MEMO + 7L, out[0].uid)
    }

    @Test fun migrateIsIdempotent() {
        // 方案明确要求：迁移跑两遍结果必须一样
        val memos = listOf(memo(1L, "a"), memo(2L, "b"))
        val first = Note.migrateMemos(memos, emptySet())
        val origins = first.map { it.origin }.toSet()
        val second = Note.migrateMemos(memos, origins)
        assertTrue("第二遍不该再导入任何东西", second.isEmpty())
    }

    @Test fun migrateKeepsTodoLink() {
        val out = Note.migrateMemos(listOf(memo(3L, "已转待办", todoId = 99L)), emptySet())
        assertEquals(99L, out[0].todoId)
    }

    @Test fun migrateSkipsBlankMemos() {
        val out = Note.migrateMemos(listOf(memo(1L, ""), memo(2L, "  "), memo(3L, "有内容")), emptySet())
        assertEquals(1, out.size)
    }

    @Test fun migratePreservesTextVerbatim() {
        // 旧代码截到 200 字是存储限制，不是用户意图 —— 迁移不能跟着截
        val long = "很长的内容".repeat(80)
        val out = Note.migrateMemos(listOf(memo(1L, long)), emptySet())
        assertEquals(long, out[0].body)
    }

    @Test fun migrateKeepsOriginalTime() {
        val out = Note.migrateMemos(listOf(memo(1L, "x", at = 1729500000000L)), emptySet())
        assertEquals(1729500000000L, out[0].createdAt)
    }

    @Test fun migrateEmptyList() {
        assertTrue(Note.migrateMemos(emptyList(), emptySet()).isEmpty())
    }
}
