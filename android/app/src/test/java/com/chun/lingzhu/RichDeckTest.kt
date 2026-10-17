package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本词书（`#LING-RICH v1`）的解析与注册。
 *
 * 这里全部用字符串 fixture，不依赖真实 assets。开源副本只带 5 个 Wiktionary 词频表，
 * 不含原发行版的考研三级词库，因此注册相关断言按这 5 级校验。
 *
 * 盯死两件事：
 *  1. [Words.parse] 按魔数分流，**普通词书那条路一个字节都不能变**（拿 [WordsTest] 的既有例子做对照）；
 *  2. [Words.prettify] 对书页式背面（含 `【`）**原样返回、不截断** —— 走 WordSense 那条路会把
 *     长条目砍到 500 字并重排，书页式排版就毁了。普通背面仍要走 WordSense 排版（防回归）。
 */
class RichDeckTest {

    /** 一段合法的富文本词书：魔数 + 注释 + 3 条记录（其中一条重复正面） */
    private val richFixture = """
        #LING-RICH v1
        # 这是注释行，解析时整行丢掉
        abandon
        [ə'bændən] vt.
        【词义】①抛弃,遗弃,离弃
        【反义】retain vt. 保留,维持

        abide
        [ə'baId] v.(abided/ abode, abided/ abode)
        【助记】a + bide

        abandon
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

    /**
     * 魔数命中才走 [Words.parseRich]；对照 [WordsTest.parsesManyLineFormats] 的既有例子，
     * 普通文本这一路的输出必须**一字不差**。
     */
    @Test fun parseSplitsOnMagicAndKeepsPlainBehavior() {
        // 富文本：走记录式解析
        val rich = Words.parse("#LING-RICH v1\nabandon\n[ə'bændən] vt.\n【词义】①抛弃\n")
        assertEquals(1, rich.size)
        assertEquals("abandon", rich[0].front)
        assertEquals("[ə'bændən] vt.\n【词义】①抛弃", rich[0].back)
        assertEquals("", rich[0].example)
        // 与直接调 parseRich 一致（parse 只多一层 BOM/trim）
        assertEquals(Words.parseRich("#LING-RICH v1\nabandon\n[ə'bændən] vt.\n【词义】①抛弃".trim()), rich)

        // 普通文本：老路径，逐行 parseLine + 按正面去重（拿既有单测的例子做对照）
        assertEquals(listOf(Words.parseLine("abandon | v. 放弃")), Words.parse("abandon | v. 放弃"))
        assertEquals(
            listOf(Words.Card("abandon", ""), Words.Card("ability", "能力")),
            Words.parse("abandon\nability\t能力\n# 注释\n\n"),
        )
        assertEquals(
            Words.Card("abandon", "v. 放弃", "They had to abandon the car."),
            Words.parse("abandon | v. 放弃 | They had to abandon the car.")[0],
        )
    }

    /** 只有「精确等于魔数」才分流：v2 / 带别的前缀都照旧走逐行解析（# 行当注释丢掉） */
    @Test fun onlyExactMagicDiverts() {
        assertEquals(listOf(Words.Card("abandon", "")), Words.parse("#LING-RICH v2\nabandon"))
        assertEquals(listOf(Words.Card("abandon", "")), Words.parse("#LINGRICH v1\nabandon"))
        assertEquals(listOf(Words.Card("abandon", "")), Words.parse(" #LING-RICH v1x\nabandon"))
    }

    /** BOM 不影响分流（有些编辑器会加 \uFEFF） */
    @Test fun bomBeforeMagicStillRich() {
        val cards = Words.parse("\uFEFF#LING-RICH v1\r\nabandon\r\n【词义】①抛弃")
        assertEquals(1, cards.size)
        assertEquals("abandon", cards[0].front)
        assertEquals("【词义】①抛弃", cards[0].back)
    }

    // ---------------- 2. 多行记录解析 ----------------

    @Test fun parsesMultiLineRecords() {
        val cards = Words.parse(richFixture)
        assertEquals(2, cards.size)                                    // 第三条正面重复 → 丢掉

        assertEquals("abandon", cards[0].front)
        assertEquals("[ə'bændən] vt.\n【词义】①抛弃,遗弃,离弃\n【反义】retain vt. 保留,维持", cards[0].back)
        assertEquals("", cards[0].example)                             // 例句不往 example 挪

        assertEquals("abide", cards[1].front)
        assertEquals("[ə'baId] v.(abided/ abode, abided/ abode)\n【助记】a + bide", cards[1].back)
        assertEquals("", cards[1].example)
    }

    /** 注释行跳过、空行分隔生效、记录内部换行原样保留 */
    @Test fun commentsSkippedBlankLineSeparatesAndInnerNewlinesKept() {
        val cards = Words.parse(richFixture)
        assertTrue("注释行不该变成卡片", cards.none { it.front.startsWith("#") })
        assertEquals("记录内部的三行必须原样保留", 3, cards[0].back.split("\n").size)
        // 第二条记录的背面只有魔数行以外的正文，不含上一条的任何行
        assertTrue(cards[1].back.startsWith("[ə'baId]"))
        assertTrue(!cards[1].back.contains("【词义】"))
    }

    /** 同正面去重（留第一条），正面为空的记录跳过 */
    @Test fun dedupesByFrontAndSkipsEmptyFront() {
        val text = "${Words.RICH_MAGIC}\n\nword\n【词义】第一条\n\nword\n【词义】第二条\n"
        val cards = Words.parse(text)
        assertEquals(1, cards.size)
        assertEquals("【词义】第一条", cards[0].back)
    }

    // ---------------- 3. prettify 不截断（核心） ----------------

    /**
     * 书页式背面（含 `【`）必须**原样透过** [Words.prettify]：长度一致、内容一字不改。
     * 这里刻意用 2000 字的背面 —— 老路径 `WordSense.formatBack` 会砍到 500 字并重排。
     */
    @Test fun prettifyDoesNotTruncatePageStyleBack() {
        val back = "【词义】" + "甲".repeat(1996)              // 长度恰好 2000
        assertEquals(2000, back.length)
        val card = Words.Card("abandon", back)
        val shown = Words.prettify(card)
        assertEquals("背面长度必须原样（不许砍到 500）", 2000, shown.back.length)
        assertEquals(back, shown.back)                          // 内容未改写
        assertEquals("abandon", shown.front)
        // front 照旧走 clipFront
        assertTrue(Words.prettify(Words.Card("x".repeat(300), back)).front.length <= WordSense.FRONT_MAX)
    }

    /** 非书页式背面（普通词书）行为不变：仍走 [WordSense.formatBack] 排版 + 500 字上限（防回归） */
    @Test fun prettifyStillFormatsPlainBack() {
        val plain = Words.prettify(Words.Card("budget", "v. 放弃；抛弃\nn. 放任"))
        assertTrue("普通背面该排成义项列表：${plain.back}", plain.back.startsWith("1. v. 放弃；抛弃"))

        val long = "v. " + "啊".repeat(1200)
        val clipped = Words.prettify(Words.Card("w", long)).back
        assertTrue("普通长背面仍受 500 字上限：${clipped.length}", clipped.length <= WordSense.BACK_MAX)
        assertTrue(clipped.length < long.length)
    }

    /**
     * 超纲词那级背面**没有【标签**，只有首行音标行（`['æbdɪkeɪt] v.` + 释义）。
     * 它同样是书页式，必须原样透过 —— 否则这层会先把它改写成 `1. [音标] n. / 2. 释义`，
     * 卡片那边按 [KaoyanBack.isRich] 就认不出富排版了。
     */
    @Test fun prettifyKeepsPhoneticLinePageStyleBack() {
        val back = "['æbdɪkeɪt] v.\n退位,逊位;失(职),放弃(职责)"
        assertTrue("这条该被判成书页式：${KaoyanBack.isRich(back)}", KaoyanBack.isRich(back))
        val shown = Words.prettify(Words.Card("abdicate", back))
        assertEquals("背面必须原样（不许补 1. / 2. 序号）", back, shown.back)
        // 反面证据：老路径确实会把这一页拆成两个义项
        assertTrue(WordSense.formatBack(back).startsWith("1. ['æbdɪkeɪt] v.\n2. "))
    }

    // ---------------- 4. WordBank 注册 ----------------

    @Test fun fiveLevelsInOriginalOrder() {
        assertEquals(
            listOf("junior", "gaokao", "cet4", "cet6", "ielts"),
            WordBank.LEVELS.map { it.id },
        )
        assertEquals(
            listOf("junior.txt", "gaokao.txt", "cet4.txt", "cet6.txt", "ielts.txt"),
            WordBank.LEVELS.map { it.file },
        )
        // 考研三级因版权原因不在开源副本中
        assertTrue(WordBank.LEVELS.none { it.id.startsWith("kaoyan_") })
    }

    @Test fun everyLevelDeckIdIsRegistered() {
        for (l in WordBank.LEVELS) {
            assertEquals(WordBank.DECK_PREFIX + l.id, WordBank.deckId(l))
            assertTrue(WordBank.isLevelDeck(WordBank.DECK_PREFIX + l.id))
        }
        // 自定义词库不受影响
        assertTrue(!WordBank.isLevelDeck("custom_1"))
    }

    /**
     * 默认勾选必须指向实际存在的资产：开源副本没有考研三级，因此默认第一级（junior）。
     * 盯死三点：默认里不含 [Words.BUILTIN_EN]；id 是合法的内置分级词库；默认项在 [WordBank.LEVELS] 里。
     */
    @Test fun defaultSelectionIsAnAvailableLevel() {
        assertEquals(setOf("wb_junior"), WordBank.DEFAULT_ON)
        assertTrue("默认里不该有英语常用词", Words.BUILTIN_EN !in WordBank.DEFAULT_ON)
        for (id in WordBank.DEFAULT_ON) {
            assertTrue("$id 必须是内置分级词库", WordBank.isLevelDeck(id))
            assertTrue("$id 必须有对应资产", WordBank.levelById(id.removePrefix(WordBank.DECK_PREFIX)) != null)
        }
    }

    /** decksOf(注入假文本) → 走 [Words.parseMemoized]，条数等于 fixture 里的记录数 */
    @Test fun decksOfRichTextParsesToRecordCount() {
        val levels = WordBank.LEVELS.takeLast(2)
        val text = richDeck(3)
        val decks = WordBank.decksOf(levels) { text }
        assertEquals(2, decks.size)
        decks.forEach { d ->
            assertEquals(Words.parse(text).size, Words.parseMemoized(d.text).size)
            assertEquals(3, Words.parseMemoized(d.text).size)
        }
        // id 前缀
        assertEquals(listOf("wb_cet6", "wb_ielts"), decks.map { it.id })
    }
}
