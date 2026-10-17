package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KnowledgeImport：剪藏 / Markdown / TXT → 知识卡（纯函数） */
class KnowledgeImportTest {

    // ---------------- 剪藏 ----------------

    @Test fun clipSplitsFirstSentence() {
        val (f, b) = KnowledgeImport.clipToCard("光合作用是植物把光能变成化学能。发生在叶绿体里，需要水和二氧化碳。")!!
        assertEquals("光合作用是植物把光能变成化学能。", f)
        assertTrue(b.contains("叶绿体"))
    }

    @Test fun clipFirstLineWhenNoPunctuation() {
        val (f, b) = KnowledgeImport.clipToCard("第一行是重点\n第二行是补充")!!
        assertEquals("第一行是重点", f)
        assertEquals("第二行是补充", b)
    }

    @Test fun clipBlankReturnsNull() {
        assertNull(KnowledgeImport.clipToCard("   \n  "))
        assertNull(KnowledgeImport.clipToCard(""))
    }

    @Test fun clipBackClippedAt500() {
        val (f, b) = KnowledgeImport.clipToCard("短问题。" + "长".repeat(600))!!
        assertEquals("短问题。", f)
        assertEquals(501, b.length)   // 500 + 省略号
    }

    // ---------------- Markdown ----------------

    @Test fun mdHeadingsBecomeCards() {
        val md = "# 什么是导数\n导数是变化率\n## 为什么需要导数\n为了求瞬时变化"
        val cards = KnowledgeImport.mdToCards(md)
        assertEquals(2, cards.size)
        assertEquals("什么是导数" to "导数是变化率", cards[0])
        assertEquals("为什么需要导数" to "为了求瞬时变化", cards[1])
    }

    @Test fun mdPipeLinesBecomeCards() {
        val cards = KnowledgeImport.mdToCards("什么是光合作用 | 植物把光能转化学能\n问 | 答")
        assertEquals(1, cards.size)              // 「问 | 答」是表头，跳过
        assertEquals("什么是光合作用", cards[0].first)
    }

    @Test fun mdPipeHeaderRowSkipped() {
        val cards = KnowledgeImport.mdToCards("问 | 答\n什么是酶 | 催化剂")
        assertEquals(1, cards.size)   // 表头「问 | 答」跳过
    }

    @Test fun mdMixedHeadingAndPipe() {
        // 规格：标题=正面、标题下正文=背面（到下一标题止）；管道行独立成卡
        val cards = KnowledgeImport.mdToCards("# 章节一\n正文不算卡\n概念A | 解释A")
        assertEquals(2, cards.size)
        assertEquals("章节一" to "正文不算卡", cards[0])
        assertEquals("概念A", cards[1].first)
    }

    // ---------------- TXT ----------------

    @Test fun txtTabSeparated() {
        val cards = KnowledgeImport.txtToCards("问1\t答1\n问2\t答2")
        assertEquals(2, cards.size)
        assertEquals("问1" to "答1", cards[0])
    }

    @Test fun txtSingleSegmentSelfTestCard() {
        val cards = KnowledgeImport.txtToCards("只有正面的自测卡")
        assertEquals(1, cards.size)
        assertEquals("只有正面的自测卡" to "只有正面的自测卡", cards[0])
    }

    @Test fun txtSkipsBlankAndCRLF() {
        val cards = KnowledgeImport.txtToCards("问1\r\n\r\n问2\t答2\r\n")
        assertEquals(2, cards.size)
        assertEquals("问1", cards[0].first)
    }

    @Test fun txtBOMStripped() {
        val cards = KnowledgeImport.txtToCards("\uFEFF问1\t答1")
        assertEquals("问1", cards[0].first)
    }

    @Test fun txtEmptyInputEmptyResult() {
        assertTrue(KnowledgeImport.txtToCards("").isEmpty())
        assertTrue(KnowledgeImport.txtToCards("  \n ").isEmpty())
    }
}
