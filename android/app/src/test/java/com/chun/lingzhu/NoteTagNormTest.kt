package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

/**
 * `NoteSyntax.normTag` 的契约。
 *
 * 它决定了「用户打的标签」变成「库里存的标签」的全部规则，
 * 却一直没有测试 —— 我在真机上就因为这个误判过一次：
 * 写了 `#A1` 然后断言取回的是 `A1`，实际是 `a1`，差点把正常行为报成 bug。
 *
 * 规则有五条：去首尾空白 → 折叠连续斜杠 → 去首尾斜杠 → 截断 → 转小写。
 * 顺序有讲究：先折叠再截断，不然截断可能正好切在斜杠上。
 */
class NoteTagNormTest {

    @Test fun tag_is_lowercased() {
        // 这条最容易踩：标签一律小写，写 #Math 和 #math 是同一个
        assertEquals("math", NoteSyntax.normTag("Math"));
        assertEquals("数学", NoteSyntax.normTag("数学"));
        assertEquals("abc", NoteSyntax.normTag("ABC"));
    }

    @Test fun surrounding_space_is_trimmed() {
        assertEquals("tag", NoteSyntax.normTag("  tag  "));
        assertEquals("tag", NoteSyntax.normTag("\ttag\n"));
    }

    @Test fun slashes_are_collapsed_and_trimmed() {
        // 层级标签用 / 分隔，但用户可能手滑打出 a//b 或 /a/
        assertEquals("a/b", NoteSyntax.normTag("a//b"));
        assertEquals("a/b", NoteSyntax.normTag("a///b"));
        assertEquals("a/b", NoteSyntax.normTag("/a/b/"));
        assertEquals("a/b", NoteSyntax.normTag("//a/b//"));
    }

    @Test fun overlong_tag_is_truncated() {
        val long = "x".repeat(NoteSyntax.MAX_TAG + 50)
        assertEquals(NoteSyntax.MAX_TAG, NoteSyntax.normTag(long).length);
    }

    @Test fun empty_stays_empty() {
        assertEquals("", NoteSyntax.normTag(""));
        assertEquals("", NoteSyntax.normTag("   "));
        assertEquals("", NoteSyntax.normTag("/"));
        assertEquals("", NoteSyntax.normTag("///"));
    }

    @Test fun norm_is_idempotent() {
        // 规范化两次 == 规范化一次。少了这条，写库和读库可能对不上。
        for (raw in listOf("Math", "/a//b/", "  Tag  ", "x".repeat(200), "数学/导数")) {
            val once = NoteSyntax.normTag(raw)
            assertEquals("二次规范化改动了「" + raw + "」", once, NoteSyntax.normTag(once))
        }
    }
}
