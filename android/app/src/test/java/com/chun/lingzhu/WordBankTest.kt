package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordBankTest {
    /** v1.77 起内置词库不再导入，正文原样进 [WordBank.decksOf]，解析走 [Words.parseMemoized]；
     *  这里测的就是这条真实路径：[WordBank.stripBom] + [Words.parse] */
    private fun cards(text: String) = Words.parse(WordBank.stripBom(text))

    @Test fun levelListHasAtLeastFourEntries() {
        assertTrue("分级至少 4 个，实际 ${WordBank.LEVELS.size}", WordBank.LEVELS.size >= 4)
        assertTrue(WordBank.LEVELS.all { it.id.isNotBlank() && it.name.isNotBlank() && it.file.endsWith(".txt") })
        assertTrue(WordBank.LEVELS.map { it.id }.toSet().size == WordBank.LEVELS.size)
        assertTrue(WordBank.levelById("cet4") != null)
        assertTrue(WordBank.levelById("nope") == null)
    }

    @Test fun parsesWordOnlyAndAnnotatedLines() {
        assertEquals(listOf(Words.Card("abandon", ""), Words.Card("ability", "能力")), cards("abandon\nability\t能力\n# 注释\n\n"))
    }

    @Test fun stripsBomAndDedupes() {
        assertEquals(listOf(Words.Card("word", ""), Words.Card("other", "其他")), cards("\uFEFFword\r\nword\nword\nother | 其他"))
    }

    @Test fun deckIdMatchesLevel() {
        WordBank.LEVELS.forEach { assertEquals(WordBank.DECK_PREFIX + it.id, WordBank.deckId(it)) }
    }

    @Test fun emptyBankParsesToEmptyList() {
        assertTrue(cards("").isEmpty())
        assertTrue(cards("\uFEFF\n# 只有注释\n\r\n").isEmpty())
    }

    @Test fun largeBankDedupesAndKeepsCount() {
        // 模拟真实词表规模（约 2000 行）+ 重复行 + 混入 BOM/CRLF：摘要条数必须等于去重后的词数
        val words = (0 until 2000).map { "w$it" }
        val cs = cards("\uFEFF" + (words + words.take(500)).joinToString("\r\n"))
        assertEquals(2000, cs.size)
        assertEquals(words.first(), cs.first().front)
        assertTrue(cs.all { it.back.isEmpty() })
    }
}
