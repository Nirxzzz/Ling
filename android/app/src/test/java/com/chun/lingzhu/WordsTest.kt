package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WordsTest {
    @Test fun parsesManyLineFormats() {
        assertEquals(Words.Card("abandon", "v. 放弃"), Words.parseLine("abandon | v. 放弃"))
        assertEquals(Words.Card("床前明月光", "疑是地上霜"), Words.parseLine("床前明月光：疑是地上霜"))
        assertEquals(Words.Card("serendipity", "意外发现"), Words.parseLine("serendipity\t意外发现"))
        assertEquals(Words.Card("abandon", "v. 放弃"), Words.parseLine("abandon v. 放弃"))
        assertEquals(Words.Card("look forward to", "期待"), Words.parseLine("look forward to 期待"))
        assertEquals(Words.Card("ephemeral", ""), Words.parseLine("ephemeral"))
        assertNull(Words.parseLine("# 注释"))
        assertNull(Words.parseLine("   "))
    }

    @Test fun builtinDeckIsComplete() {
        val cards = Words.parse(Words.BUILTIN_EN_TEXT)
        assertTrue(cards.size >= 180)
        assertTrue(cards.all { it.back.isNotBlank() })
    }

    @Test fun weightsFavourUnknownAndSkipRecent() {
        val now = 10_000_000L
        assertEquals(3.0, Words.weight(null, now), 0.0)
        assertEquals(5.0, Words.weight(Words.Stat(0, 2, now - 3_600_000), now), 0.0)
        assertEquals(0.15, Words.weight(Words.Stat(3, 5, now - 3_600_000), now), 0.0)
        assertEquals(0.0, Words.weight(Words.Stat(0, 2, now - 60_000), now), 0.0)
    }

    @Test fun pickPrefersUnknownOverMastered() {
        val now = 100_000_000L
        val a = "en" to Words.Card("a", "1"); val b = "en" to Words.Card("b", "2")
        val st = mapOf(Words.key("en", a.second) to Words.Stat(0, 3, 0), Words.key("en", b.second) to Words.Stat(5, 9, 0))
        val rnd = Random(7)
        val n = (1..1000).count { Words.pick(listOf(a, b), st, now, rnd) == a }
        assertTrue("不认识的应占绝大多数：$n", n > 900)
    }

    @Test fun markTracksStreak() {
        var s: Words.Stat? = null
        s = Words.mark(s, true, 1); s = Words.mark(s, true, 2)
        assertEquals(2, s.streak); assertEquals(2, s.seen)
        s = Words.mark(s, false, 3)
        assertEquals(0, s.streak); assertEquals(3, s.seen)
    }

    @Test fun quietHoursAcrossMidnight() {
        assertTrue(Words.quiet(23, 23, 7)); assertTrue(Words.quiet(3, 23, 7))
        assertEquals(false, Words.quiet(7, 23, 7)); assertEquals(false, Words.quiet(12, 23, 7))
    }

    @Test fun jsonRoundTrip() {
        val d = listOf(Words.Deck("d1", "古诗", "床前明月光：疑是地上霜"))
        assertEquals(d, Words.decksFromJson(Words.decksToJson(d)))
        val m = mapOf("en\u0000a" to Words.Stat(2, 4, 99))
        assertEquals(m, Words.statsFromJson(Words.statsToJson(m)))
    }

    @Test fun speakableOnlyForEnglish() {
        assertTrue(Words.speakable("abandon")); assertTrue(Words.speakable("look forward to")); assertTrue(Words.speakable("don't"))
        assertEquals(false, Words.speakable("床前明月光")); assertEquals(false, Words.speakable("sin²x + cos²x"))
    }

    @Test fun todayCountResetsOnNewDay() {
        var t = Words.Today()
        t = Words.countToday(t, 20260928, true); t = Words.countToday(t, 20260928, false)
        assertEquals(Words.Today(20260928, 2, 1), t)
        assertEquals(Words.Today(20260929, 1, 1), Words.countToday(t, 20260929, true))
        assertEquals(Words.Today(20260929, 0, 0), Words.todayOf(t, 20260929))
    }

    @Test fun unknownMeansSeenAndNotKnown() {
        assertEquals(false, Words.unknown(null))
        assertEquals(false, Words.unknown(Words.Stat(0, 0, 5)))   // 只弹过没作答
        assertTrue(Words.unknown(Words.Stat(0, 2, 5)))
        assertEquals(false, Words.unknown(Words.Stat(1, 2, 5)))
    }

    /**
     * v1.78：卡片内容的来源。回归点是内置 5 级词库 —— 正文只有单词、背面是空的，
     * 而 prettify 对空背面是「原样返回」，所以以前预翻译好的译文根本进不了卡片（卡片上一个字都没有）。
     */
    @Test fun detailKeepsDeckBackAndFillsInPretranslation() {
        // 词库自带背面：详细排版，预翻译译文不参与
        val withBack = Words.detail(Words.Card("abandon", "v. 放弃；抛弃"), "放弃")
        assertEquals("abandon", withBack.front)
        assertTrue(withBack.back.startsWith("1. v. 放弃；抛弃"))

        // 只有单词（内置 5 级）：译文必须真的排进卡片
        val onlyWord = Words.detail(Words.Card("sixth", ""), "第六")
        assertEquals("sixth", onlyWord.front)
        assertTrue("译文没排进卡片：${onlyWord.back}", onlyWord.back.contains("第六"))
        // v1.80：不再编例句（以前这里会塞一句模板拼的英文句子）
        assertTrue("不该编例句", !onlyWord.back.contains("例："))
        assertTrue(onlyWord.back.length <= WordSense.BACK_MAX)

        // 还没预翻译过：原样返回（卡片上只留单词和「查」），不编造释义
        assertEquals(Words.Card("sixth", ""), Words.detail(Words.Card("sixth", ""), null))
        assertEquals(Words.Card("sixth", ""), Words.detail(Words.Card("sixth", ""), "   "))

        // 超长正面沿用截断
        assertTrue(Words.detail(Words.Card("x".repeat(300), ""), "释义").front.length <= 200)
    }

    // ---------------- v1.80：一行三栏（单词 | 释义 | 例句） ----------------

    /**
     * 词书常见格式是三栏（词 / 释义 / 例句）。第三栏落到 [Words.Card.example]，
     * 释义里带冒号、例句里带破折号都不会被切坏（split limit = 3）。
     */
    @Test fun parsesThreeColumnLines() {
        assertEquals(
            Words.Card("abandon", "v. 放弃；抛弃", "They had to abandon the car. 他们只好把车丢下。"),
            Words.parseLine("abandon | v. 放弃；抛弃 | They had to abandon the car. 他们只好把车丢下。")
        )
        assertEquals(
            Words.Card("budget", "n. 预算", "We're on a tight budget. 我们预算很紧。"),
            Words.parseLine("budget\t n. 预算\t We're on a tight budget. 我们预算很紧。")
        )
        // 例句里还有冒号/竖线也不该被切碎（多余的都留在第三栏）
        assertEquals(
            Words.Card("tip", "n. 提示", "Tip: leave a tip. 提示：给小费。"),
            Words.parseLine("tip | n. 提示 | Tip: leave a tip. 提示：给小费。")
        )
    }

    /** 两栏的行照旧只有释义，**不硬把释义当例句** */
    @Test fun twoColumnLinesHaveNoExample() {
        assertEquals(Words.Card("abandon", "v. 放弃", ""), Words.parseLine("abandon | v. 放弃"))
        assertEquals(Words.Card("abandon", "v. 放弃", ""), Words.parseLine("abandon v. 放弃"))
    }

    /** 整份词书文本 → 卡片：三栏那条的例句要带上，重复单词只留第一条 */
    @Test fun parseKeepsExamplesAndDedupesByFront() {
        val text = """
            # 我的词书
            abandon | v. 放弃 | They had to abandon the car.
            budget | n. 预算 | We're on a tight budget.
            abandon | v. 抛弃 | 重复的这条要丢掉
        """.trimIndent()
        val cards = Words.parse(text)
        assertEquals(2, cards.size)
        assertEquals("They had to abandon the car.", cards[0].example)
        assertEquals("We're on a tight budget.", cards[1].example)
    }

    /**
     * v1.80 回归：例句在 [Words.detail] / [Words.prettify] 这一路上不能被丢掉。
     * 实测踩过 —— prettify 只把 front/back 带过去，卡片上那条例句就没了（截图上是「1. n. 预算」孤零零一行）。
     */
    @Test fun exampleSurvivesPrettifyAndDetail() {
        val card = Words.Card("budget", "n. 预算", "We're on a tight budget.")
        assertEquals("We're on a tight budget.", Words.prettify(card).example)
        val shown = Words.detail(card, "预算")
        assertEquals("budget", shown.front)
        assertTrue("背面还是排好的详细版", shown.back.startsWith("1. n. 预算"))
        assertEquals("例句得跟着走到卡片上", "We're on a tight budget.", shown.example)
        // 卡片文案：释义 + 「例：」那一行
        assertTrue(WordSense.cardBack(shown.back, shown.example).contains("例：We're on a tight budget."))
    }
}
