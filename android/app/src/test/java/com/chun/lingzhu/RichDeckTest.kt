package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本词书（`#LING-RICH v1`）解析 / prettify / WordBank 注册。
 *
 * 样本自撰，不含第三方词书原文。开源副本只分发 5 套 Wiktionary 词频表，
 * 因此这里不再有考研三级的注册断言，也没有真读内置词表文件的用例。
 */
class RichDeckTest {

    /** 一段合法的富文本词书：魔数 + 注释 + 3 条记录（其中一条重复正面） */
    private val richFixture = """
        #LING-RICH v1
        # 这是注释行，解析时整行丢掉
        orchard
        ['ɔːtʃəd] n.
        【词义】①果园,果树园
        【反义】barn n. 谷仓,库房

        lantern
        ['læntən] n.
        【助记】lant+ern

        orchard
        这条正面重复，应当被丢掉
    """.trimIndent()

    /** 造一段 k 条记录的富文本（每条背面两行） */
    private fun richDeck(k: Int): String = buildString {
        appendLine(Words.RICH_MAGIC)
        appendLine("# fixture")
        for (i in 1..k) {
            appendLine("word$i")
            appendLine("[$i] v.")
            appendLine("【词义】第 $i 条")
            appendLine()
        }
    }

    // ---------------- 1. parse 按 magic 分流 ----------------

    @Test fun parseSplitsOnMagicAndKeepsPlainBehavior() {
        val rich = Words.parse("#LING-RICH v1\norchard\n['ɔːtʃəd] n.\n【词义】①果园\n")
        assertEquals(1, rich.size)
        assertEquals("orchard", rich[0].front)
        assertEquals("['ɔːtʃəd] n.\n【词义】①果园", rich[0].back)
    }

    @Test fun onlyExactMagicDiverts() {
        // 只有整行等于魔数才走富文本；前后有别的字就走普通解析
        val plain = Words.parse("x#LING-RICH v1\norchard | 果园")
        assertEquals("魔数不独占首行 → 两行各按普通词书解析", 2, plain.size)
        assertEquals(listOf("x#LING-RICH v1", "orchard"), plain.map { it.front })
        assertEquals("", plain[0].back)
        assertEquals("果园", plain[1].back)
    }

    @Test fun bomBeforeMagicStillRich() {
        val cards = Words.parse("\uFEFF" + richDeck(2))
        assertEquals(2, cards.size)
    }

    @Test fun parsesMultiLineRecords() {
        val cards = Words.parse(richFixture)
        assertEquals("重复正面去重后 2 条", 2, cards.size)
        assertEquals("orchard", cards[0].front)
        assertTrue("背面保留多行", cards[0].back.contains("\n"))
        assertEquals("lantern", cards[1].front)
    }

    @Test fun commentsSkippedBlankLineSeparatesAndInnerNewlinesKept() {
        val cards = Words.parse(richFixture)
        assertTrue("注释不当正面", cards.none { it.front.startsWith("#") })
        assertEquals("魔数行不进正面", "orchard", cards[0].front)
    }

    @Test fun dedupesByFrontAndSkipsEmptyFront() {
        val text = Words.RICH_MAGIC + "\n\norchard\nA\n\norchard\nB\n"
        val cards = Words.parse(text)
        assertEquals(1, cards.size)
        assertEquals("A", cards[0].back)
    }

    // ---------------- 2. prettify ----------------

    @Test fun prettifyDoesNotTruncatePageStyleBack() {
        val back = "【词义】" + "甲".repeat(1996)              // 长度恰好 2000
        assertEquals(2000, back.length)
        val card = Words.Card("orchard", back)
        val shown = Words.prettify(card)
        assertEquals("背面长度必须原样（不许砍到 500）", 2000, shown.back.length)
        assertEquals(back, shown.back)
        assertEquals("orchard", shown.front)
        assertTrue(Words.prettify(Words.Card("x".repeat(300), back)).front.length <= WordSense.FRONT_MAX)
    }

    @Test fun prettifyStillFormatsPlainBack() {
        val plain = Words.prettify(Words.Card("budget", "v. 堆放；码放\nn. 预算"))
        assertTrue("普通背面该排成义项列表：${plain.back}", plain.back.startsWith("1. v. 堆放；码放"))

        val long = "v. " + "啊".repeat(1200)
        val clipped = Words.prettify(Words.Card("w", long)).back
        assertTrue("普通长背面仍受 500 字上限：${clipped.length}", clipped.length <= WordSense.BACK_MAX)
        assertTrue(clipped.length < long.length)
    }

    @Test fun prettifyKeepsPhoneticLinePageStyleBack() {
        val back = "['brɪk] v.\n堆叠,码放;砌(墙)"
        assertTrue("这条该被判成书页式：${KaoyanBack.isRich(back)}", KaoyanBack.isRich(back))
        val shown = Words.prettify(Words.Card("brick", back))
        assertEquals("背面必须原样（不许补 1. / 2. 序号）", back, shown.back)
        assertTrue(WordSense.formatBack(back).startsWith("1. ['brɪk] v.\n2. "))
    }

    // ---------------- 3. WordBank 注册（开源副本只有 5 套词频表） ----------------

    @Test fun fiveFrequencyLevelsInOrder() {
        assertEquals(
            listOf("junior", "gaokao", "cet4", "cet6", "ielts"),
            WordBank.LEVELS.map { it.id },
        )
        assertTrue("每级都有文件名", WordBank.LEVELS.all { it.file.endsWith(".txt") })
    }

    @Test fun levelDeckIdsAreDerived() {
        for (l in WordBank.LEVELS) {
            assertEquals(WordBank.DECK_PREFIX + l.id, WordBank.deckId(l))
            assertTrue(WordBank.isLevelDeck(WordBank.deckId(l)))
        }
        assertFalse("自定义词库不算内置分级", WordBank.isLevelDeck("custom_1"))
    }

    @Test fun defaultSelectionIsACet4OrHigherLevel() {
        val level = WordBank.levelById("cet4")
        assertTrue("cet4 必须在分级里", level != null)
        assertEquals(setOf(WordBank.deckId(level!!)), WordBank.DEFAULT_ON)
        assertFalse("默认里不该有英语常用词", Words.BUILTIN_EN in WordBank.DEFAULT_ON)
        for (id in WordBank.DEFAULT_ON) assertTrue("$id 必须是内置分级词库", WordBank.isLevelDeck(id))
    }

    @Test fun decksOfRichTextParsesToRecordCount() {
        val deck = Words.parse(richDeck(3))
        assertEquals(3, deck.size)
        // parseRich 的契约是背面「不截断、不重排、不改写」：音标行与词义行都原样留着
        for ((i, c) in deck.withIndex()) {
            val n = i + 1
            assertEquals("word$n", c.front)
            assertEquals("[$n] v.\n【词义】第 $n 条", c.back)
        }
    }

    @Test fun richCardsArePrettifiedVerbatim() {
        val cards = Words.parse(richFixture)
        for (c in cards) {
            if (KaoyanBack.isRich(c.back)) {
                assertEquals("「${c.front}」的背面被 prettify 改写了", c.back, Words.prettify(c).back)
            }
        }
    }
}
