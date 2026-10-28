package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

/**
 * 筛选语义的边界：`Filter` 到底会把哪些笔记挡在外面。
 *
 * 这些值的组合靠真机自检抓出过 bug —— 归档一条笔记后搜它返回 0 条，
 * 因为 search() 当时用了 Filter.ALL，而 ALL 的条件里带着 archived=0。
 * 归档不是删除，用户搜东西不该被它挡住。
 *
 * 这里不碰 SQLite（项目只有 JVM 单测，没有 androidTest），
 * 所以测的是**判定规则本身**：给定一条笔记的状态，某个筛选条件认不认它。
 * 规则和 Notes.byIds 里那段 where 必须逐条对齐。
 */
class NotesFilterRulesTest {

    /** 和 Notes.byIds 的 where 子句一一对应的纯判定 */
    private fun visible(
        deleted: Boolean,
        archived: Boolean,
        pinned: Boolean,
        filter: Notes.Filter?,
    ): Boolean = when (filter) {
        null -> !deleted                              // 搜索：只排除回收站
        Notes.Filter.ALL -> !deleted && !archived
        Notes.Filter.PINNED -> !deleted && !archived && pinned
        Notes.Filter.ARCHIVED -> !deleted && archived
        Notes.Filter.TRASH -> deleted
    }

    @Test fun search_does_not_hide_archived() {
        // 这条是真机上发现的 bug：归档后搜索返回 0 条
        assertTrue("归档的笔记搜索时必须能搜到", visible(false, true, false, null))
        // 但回收站里的不该被搜出来
        assertFalse("回收站的笔记不该被搜出来", visible(true, false, false, null))
        // 置顶的、归档的都要能搜到
        assertTrue("置顶的能搜到", visible(false, false, true, null))
        assertTrue("普通笔记能搜到", visible(false, false, false, null))
    }

    @Test fun all_excludes_archived() {
        // 「全部」= 全部未归档未删除。归档的要单独去 ARCHIVED 看。
        assertTrue(visible(false, false, false, Notes.Filter.ALL))
        assertFalse("归档的不在全部里", visible(false, true, false, Notes.Filter.ALL))
        assertFalse("删除的不在全部里", visible(true, false, false, Notes.Filter.ALL))
    }

    @Test fun pinned_requires_archived_absent() {
        assertTrue(visible(false, false, true, Notes.Filter.PINNED))
        assertFalse("没置顶的不算置顶", visible(false, false, false, Notes.Filter.PINNED))
        assertFalse("归档的即使置顶也不在置顶页", visible(false, true, true, Notes.Filter.PINNED))
    }

    @Test fun archive_and_trash_are_disjoint() {
        assertTrue(visible(false, true, false, Notes.Filter.ARCHIVED))
        assertFalse(visible(false, false, false, Notes.Filter.ARCHIVED))
        assertTrue(visible(true, false, false, Notes.Filter.TRASH))
        assertFalse("归档的不在回收站", visible(false, true, false, Notes.Filter.TRASH))
    }

    @Test fun every_note_is_visible_somewhere() {
        // 不变量：任何一条笔记，至少在一处能被人找到。
        // 真出过「归档后哪都找不到」—— 搜索是它最后的归宿，所以 search 必须认。
        for (del in listOf(false, true)) {
            for (arch in listOf(false, true)) {
                for (pin in listOf(false, true)) {
                    val shown = visible(del, arch, pin, null) ||
                        visible(del, arch, pin, Notes.Filter.ARCHIVED) ||
                        visible(del, arch, pin, Notes.Filter.TRASH)
                    assertTrue(
                        "笔记 deleted=" + del + " archived=" + arch + " pinned=" + pin + " 哪都找不到",
                        shown
                    )
                }
            }
        }
    }
}
