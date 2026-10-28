package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器保存决策。
 *
 * 这些规则在上一轮才加进 NoteEditActivity，而 Activity 跑不了 JVM 单测 ——
 * 所以逻辑抽到了 NoteEditPolicy。这些用例是它唯一的自动化保护。
 */
class NoteEditPolicyTest {

    @Test fun blankBodyIsRejected() {
        assertEquals(NoteEditPolicy.Action.RejectEmpty,
            NoteEditPolicy.decide("", false, ""))
    }

    @Test fun whitespaceOnlyIsRejected() {
        // 存一条只有空格 / 换行的笔记没有意义：
        // 列表里它会显示成「（空笔记）」，用户看到会困惑
        for (blank in listOf(" ", "   ", "\n", "\t", " \n \t ")) {
            assertEquals("输入「" + blank + "」应被判为空",
                NoteEditPolicy.Action.RejectEmpty,
                NoteEditPolicy.decide(blank, false, ""))
        }
    }

    @Test fun createWhenNewAndHasContent() {
        val a = NoteEditPolicy.decide("今天学了导数", false, "")
        assertTrue(a is NoteEditPolicy.Action.Create)
        assertEquals("今天学了导数", (a as NoteEditPolicy.Action.Create).body)
    }

    @Test fun editUnchangedWritesNothing() {
        // 写在库里会把 updated_at 推到现在，而列表按 created_at 排序，
        // 用户会看到这条笔记莫名其妙换了位置
        assertEquals(NoteEditPolicy.Action.CloseWithoutWrite,
            NoteEditPolicy.decide("原文", true, "原文"))
    }

    @Test fun editChangedUpdates() {
        val a = NoteEditPolicy.decide("原文加了一行", true, "原文")
        assertTrue(a is NoteEditPolicy.Action.Update)
        assertEquals("原文加了一行", (a as NoteEditPolicy.Action.Update).body)
    }

    @Test fun editClearedIsRejected() {
        // 用户把内容全删了再点保存：应当是「还没写内容」，
        // 而不是把笔记更新成空
        assertEquals(NoteEditPolicy.Action.RejectEmpty,
            NoteEditPolicy.decide("", true, "原文"))
    }

    @Test fun bodyKeepsUserIndent() {
        // 用户可能故意用缩进排版。只在判空时看 isBlank，
        // 真正落库的必须是原样内容
        val raw = "  第一行缩进\n    第二行更深"
        val a = NoteEditPolicy.decide(raw, false, "")
        assertEquals(raw, (a as NoteEditPolicy.Action.Create).body)
    }

    @Test fun newNoteWithEmptyOriginalStillCreates() {
        // 新建一条空 original 的笔记，若误用 isEdit=true 会走成 CloseWithoutWrite，
        // 用户点了保存却什么也没发生 —— 这是最容易犯的错
        val a = NoteEditPolicy.decide("新内容", false, "")
        assertTrue("新建必须走 Create", a is NoteEditPolicy.Action.Create)
    }

    @Test fun longBodyTruncatedByNoteCreate() {
        // 策略层不做长度限制 —— 截断是 Note.create 的职责，
        // 两处都截会让人分不清哪层负责
        val long = "a".repeat(200_000)
        val a = NoteEditPolicy.decide(long, false, "")
        assertEquals(long.length, (a as NoteEditPolicy.Action.Create).body.length)
        // 而 Note.create 会截到 MAX_BODY
        assertEquals(Note.MAX_BODY, Note.create(long, 0L).body.length)
    }

    // ------------------------------------------------------------------
    // isDirty：退出前要不要问「不保存就退出？」
    //
    // 2026-10-22 真机实测发现的问题：编辑页输入内容后按返回键，
    // 页面不动也不提示，点「取消」则静默丢内容。补了脏检查之后，
    // 「怎么算脏」这件事必须能测 —— 判错了要么白弹窗，要么丢数据。
    // ------------------------------------------------------------------

    @Test fun newNoteIsDirtyOnceAnythingTyped() {
        assertTrue("写了一个字就该问", NoteEditPolicy.isDirty("a", false, ""))
        assertTrue("写了中文就该问", NoteEditPolicy.isDirty("记点东西", false, ""))
    }

    @Test fun newNoteWithNothingTypedIsNotDirty() {
        assertTrue("什么都没写，退出不该弹窗", !NoteEditPolicy.isDirty("", false, ""))
    }

    @Test fun whitespaceCountsAsDirtyOnNewNote() {
        // 和 decide() 刻意不同：decide 把纯空白当 RejectEmpty（保存时拒绝），
        // 但退出时用户确实敲过键，直接丢掉太粗暴。
        // 两个判断标准不一致是**故意的**，这里锁住它，免得以后有人"统一"掉。
        assertTrue("打了空格也算动过", NoteEditPolicy.isDirty("   ", false, ""))
    }

    @Test fun editModeUnchangedIsNotDirty() {
        assertTrue("打开看一眼就退出，不该弹窗",
            !NoteEditPolicy.isDirty("原文内容", true, "原文内容"))
    }

    @Test fun editModeChangedIsDirty() {
        assertTrue("改了一个字必须问",
            NoteEditPolicy.isDirty("原文内容改", true, "原文内容"))
        assertTrue("全删了也要问", NoteEditPolicy.isDirty("", true, "原文内容"))
    }

    @Test fun editModeWhitespaceOnlyChangeIsDirty() {
        assertTrue("把正文字段清成空格也算改动",
            NoteEditPolicy.isDirty("  ", true, "原文内容"))
    }

    @Test fun dirtyCheckMatchesSaveDecisionForContentChanges() {
        // 两个函数关注点不同，但「内容真的变了」这件事上必须一致：
        // 如果 decide 认为要写库，isDirty 就不能认为没动过。
        val cases = listOf(
            Triple("新内容", true, "老内容"),
            Triple("内容", true, ""),
            Triple("a", false, ""),
        )
        for ((body, isEdit, original) in cases) {
            val willWrite = NoteEditPolicy.decide(body, isEdit, original) is NoteEditPolicy.Action.Update ||
                NoteEditPolicy.decide(body, isEdit, original) is NoteEditPolicy.Action.Create
            if (willWrite) {
                assertTrue("会落库的输入必须被判定为脏：$body",
                    NoteEditPolicy.isDirty(body, isEdit, original))
            }
        }
    }

    // ------------------------------------------------------------------
    // 翻页游标的顺序
    //
    // feed 按置顶 → 创建时间倒序 → id 倒序排。翻页时传上一页最后一条的位置，
    // 这里锁住「哪条算更旧」，免得改 SQL 或改比较时悄悄漏条 / 死循环翻同一页。
    // ------------------------------------------------------------------

    @Test fun cursorOrdersByPinnedThenTimeThenId() {
        val base = Notes.Cursor(false, 1000L, 10L)
        assertTrue("更早的笔记在后", Notes.Cursor(false, 999L, 99L).isAfter(base))
        assertTrue("更晚的笔记在前，不能算后", !Notes.Cursor(false, 1001L, 1L).isAfter(base))
        assertTrue("同一时刻 id 小的在后", Notes.Cursor(false, 1000L, 9L).isAfter(base))
        assertTrue("同一个位置不算在后（否则翻页会重复拿到同一条）", !base.isAfter(base))
    }

    @Test fun pinnedNotesAlwaysComeBeforeUnpinnedWhilePaging() {
        val pinnedOld = Notes.Cursor(true, 1L, 1L)
        val unpinnedNew = Notes.Cursor(false, Long.MAX_VALUE, Long.MAX_VALUE)
        assertTrue("置顶的笔记不管多旧都排在前面", !pinnedOld.isAfter(unpinnedNew))
        assertTrue("取消置顶一侧在置顶之后", unpinnedNew.isAfter(pinnedOld))
    }
}
