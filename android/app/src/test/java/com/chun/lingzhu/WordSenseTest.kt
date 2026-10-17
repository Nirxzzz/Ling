package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSenseTest {
    @Test fun parsesPosAndMeaning() {
        assertEquals(WordSense.Sense("v.", "放弃；抛弃"), WordSense.parseSense("v. 放弃；抛弃"))
        assertEquals(WordSense.Sense("adj.", "抽象的"), WordSense.parseSense("adj. 抽象的"))
        assertEquals(WordSense.Sense("", "意外发现"), WordSense.parseSense("意外发现"))
        assertNull(WordSense.parseSense("   "))
        assertNull(WordSense.parseSense("# 注释行"))
    }

    @Test fun parsesNumberedMultiSenseLine() {
        val ss = WordSense.parseSenses("1. n. 摘要 2. v. 摘录")
        assertEquals(2, ss.size)
        assertEquals("n.", ss[0].pos); assertEquals("摘要", ss[0].meaning); assertEquals(1, ss[0].index)
        assertEquals("v.", ss[1].pos); assertEquals("摘录", ss[1].meaning); assertEquals(2, ss[1].index)
    }

    @Test fun numberedInputKeepsOriginalOrder() {
        // 输入里带了序号：完全尊重原顺序，即使第 1 条标了生僻也不挪
        val ss = listOf(
            WordSense.Sense("n.", "生僻的旧义", index = 1),
            WordSense.Sense("v.", "常用义", index = 2),
        )
        assertEquals(listOf("生僻的旧义", "常用义"), WordSense.ordered(ss).map { it.meaning })
    }

    @Test fun rareMeaningsSortLast() {
        val ss = WordSense.ordered(
            WordSense.parseSenses("n. 生僻的古义\nv. 常用义")
        )
        assertEquals("常用义", ss[0].meaning)
        assertEquals("生僻的古义", ss[1].meaning)
        assertTrue(ss[1].rare); assertFalse(ss[0].rare)
    }

    @Test fun clipWordsLimitsWordCount() {
        val s = "a b c d e f g h i j k l m n"
        assertEquals(12, WordSense.clipWords(s, 12).split(" ").size)
        assertEquals("a b", WordSense.clipWords("  a   b  ", 12))
        assertEquals("", WordSense.clipWords("  ", 5))
    }

    @Test fun formatBuildsPlainTextCard() {
        val ss = WordSense.parseSenses("v. 放弃；抛弃\nn. 放纵").map { s ->
            if (s.example.isEmpty()) s.copy(example = "They had to abandon the car.") else s
        }
        val text = WordSense.format(ss)
        assertFalse(text.contains("<")); assertFalse(text.contains("&"))   // 纯文字，没有 HTML
        assertTrue(text.startsWith("1. v. 放弃；抛弃"))
        assertTrue(text.contains("例："))
        assertTrue(text.contains("2. n. 放纵"))
    }

    @Test fun formatTruncatesToBackMax() {
        val long = (1..30).joinToString("\n") { i -> "${i}. n. 这是一个很长很长很长的释义第${i}条" }
        val text = WordSense.format(WordSense.parseSenses(long), backMax = 500)
        assertTrue(text.length <= 500)
        assertTrue(text.endsWith("…"))
    }

    @Test fun emptyInputGivesEmptyOutput() {
        assertEquals("", WordSense.format(emptyList()))
        assertEquals("", WordSense.formatBack(""))
        assertEquals("", WordSense.formatBack("   \n  "))
        assertEquals("", WordSense.formatTranslation("word", "  "))
    }

    /**
     * v1.80：一句裸译文只排释义，**不再编例句**。
     * 以前这里会塞一句模板拼的「They often serendipity together after work.」——
     * 假句子比没有句子更糟，用户会当真例句背。
     */
    @Test fun formatTranslationDoesNotFabricateAnExample() {
        val text = WordSense.formatTranslation("serendipity", "意外发现的运气")
        assertTrue(text.contains("意外发现的运气"))
        assertFalse("不该再出现编出来的例句行", text.contains("例："))
        assertTrue(text.length <= WordSense.BACK_MAX)
    }

    // ---------------- v1.80：词书例句 + 点「查」补例句 ----------------

    /** 词书自带例句：跟在释义后面单独一行，写「例：」 */
    @Test fun cardBackCarriesTheBookExample() {
        assertEquals("放弃；抛弃\n例：They had to abandon the car.", WordSense.cardBack("放弃；抛弃", "They had to abandon the car."))
        assertEquals("放弃；抛弃", WordSense.cardBack("放弃；抛弃", ""))
        assertEquals("例：They had to abandon the car.", WordSense.cardBack("", "They had to abandon the car."))
    }

    /** 点「查」给词书卡补音标 + 例句，**不覆盖词书的释义**，而且重复点不会叠两遍 */
    @Test fun appendDictAddsPhoneticAndExampleOnly() {
        val dict = "/əˈbændən/ v.\n放弃；抛弃\nabandon a plan 放弃计划\n— They had to abandon the car. 他们只好把车丢下。"
        val once = WordSense.appendDict("放弃（词典释义：不再管）", dict)
        assertTrue("词书自己的释义要留着", once.startsWith("放弃（词典释义：不再管）"))
        assertTrue("要补上音标行", once.contains("/əˈbændən/ v."))
        assertTrue("要补上例句", once.contains("例：They had to abandon the car."))
        assertFalse("不该把词典的释义段也贴上来", once.contains("abandon a plan"))
        assertEquals("再点一次不该叠两遍", once, WordSense.appendDict(once, dict))
    }

    /** 模型只回了一句短译（没音标也没例句）→ 整段贴上，总比点了「查」什么都没发生强 */
    @Test fun appendDictFallsBackToWholeText() {
        val out = WordSense.appendDict("放弃", "抛弃；遗弃")
        assertTrue(out.startsWith("放弃"))
        assertTrue(out.contains("抛弃；遗弃"))
    }

    @Test fun prettifyClipsFrontAndFormatsBack() {
        val c = Words.prettify(Words.Card("abandon", "v. 放弃；抛弃"))
        assertEquals("abandon", c.front)
        assertTrue(c.back.startsWith("1. v. 放弃；抛弃"))
        assertTrue(c.back.length <= 500)
        // 没有背面的卡原样（不编造释义）
        val e = Words.prettify(Words.Card("ephemeral", ""))
        assertEquals("", e.back)
        val longFront = "x".repeat(300)
        assertTrue(Words.prettify(Words.Card(longFront, "n. 释义")).front.length <= 200)
    }
}
