package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9 翻译窗点词（[TranslationWord]）的纯逻辑。
 *
 * 这里钉的是行为，不是源码文本：词边界、按 offset 取词、语境句、缓存键的语境隔离、
 * 「后发的请求不许被先发的覆盖」（[TranslationWord.Latest]）。
 */
class TranslationWordTest {

    // ---------------- 1. 词边界（与电脑版 translate.html:71 同一条正则） ----------------

    @Test fun wordsFollowTheDesktopRegex() {
        val words = TranslationWord.hits("The well-known author’s o'clock plan is unworkable.").map { it.word }
        assertEquals(listOf("The", "well-known", "author’s", "o'clock", "plan", "is", "unworkable"), words)
    }

    @Test fun singleLettersDigitsAndCjkAreNotWords() {
        assertEquals(listOf("go", "home"), TranslationWord.hits("go a 1 home 中文").map { it.word })
        // 中文译文一格都不套（词典本来就是英汉的）
        assertTrue(TranslationWord.hits("这个方案还不太可行").isEmpty())
    }

    @Test fun longRunsAndTrailingHyphenAreHandled() {
        // base64 / 哈希那种长串不当词
        assertTrue(TranslationWord.hits("x".repeat(80)).isEmpty())
        // 行尾被断开的 well- 只查 well（不去查一个带连字符的假词）
        assertEquals(listOf("well"), TranslationWord.hits("a well-").map { it.word })
    }

    /** 链接 / 行内代码 / 公式里的词不套 span：由调用方给出不能点的区间 */
    @Test fun blockedRangesAreSkipped() {
        val text = "see https://a.com and frac here"
        val url = text.indexOf("https")
        val hits = TranslationWord.hits(text, listOf(url until url + "https://a.com".length))
        assertFalse(hits.any { it.word == "https" || it.word == "com" })
        assertTrue(hits.any { it.word == "here" })
    }

    // ---------------- 2. 按点中的 offset 取词 ----------------

    @Test fun wordAtPicksTheWordUnderTheOffset() {
        val s = "The plan is unworkable."
        assertEquals("The", TranslationWord.wordAt(s, 1)?.word)
        assertEquals("plan", TranslationWord.wordAt(s, 5)?.word)
        // 点在词的最后一个字符上仍然算这个词
        assertEquals("unworkable", TranslationWord.wordAt(s, 21)?.word)
        // 空白 / 标点 / 越界都不算（词后那个空格的 offset 正好是词尾，不能当成点中）
        assertNull(TranslationWord.wordAt(s, 3))
        assertNull(TranslationWord.wordAt(s, 22))
        assertNull(TranslationWord.wordAt(s, s.length))
        assertNull(TranslationWord.wordAt(s, -1))
    }

    @Test fun hyphenatedAndApostropheWordsAreOneToken() {
        val s = "A well-known o'clock habit."
        assertEquals("well-known", TranslationWord.wordAt(s, 4)?.word)
        assertEquals("o'clock", TranslationWord.wordAt(s, 16)?.word)
    }

    /** 点中第二个词时不能拿到第一个词（区间必须真的落在 offset 上） */
    @Test fun offsetSelectsExactlyOneWord() {
        val s = "bank bank"
        assertEquals(0, TranslationWord.wordAt(s, 1)?.start)
        assertEquals(5, TranslationWord.wordAt(s, 6)?.start)
    }

    // ---------------- 3. 语境句（进提示词，也进缓存键） ----------------

    @Test fun sentenceAtTakesTheSentenceAroundTheWord() {
        val s = "He sat on the river bank. Then he went to the bank."
        val first = TranslationWord.wordAt(s, 21)!!      // 第一个 bank（河岸）
        assertEquals("He sat on the river bank.", TranslationWord.sentenceAt(s, first))
        val second = TranslationWord.wordAt(s, 47)!!     // 第二个 bank（银行）
        assertEquals("Then he went to the bank.", TranslationWord.sentenceAt(s, second))
        assertFalse(
            "同一个词的两句语境必须不同，否则缓存会把「河岸」拿去解释「银行」",
            TranslationWord.sentenceAt(s, first) == TranslationWord.sentenceAt(s, second),
        )
    }

    @Test fun sentenceAtFallsBackToTheWholeLine() {
        val s = "a short line without stops"
        val hit = TranslationWord.wordAt(s, 3)!!           // 落在 "short" 里
        assertEquals(s, TranslationWord.sentenceAt(s, hit))
    }

    // ---------------- 4. 缓存键：按词 + 语境，不串义项 ----------------

    @Test fun cacheKeySeparatesSensesByContext() {
        val a = TranslationWord.cacheKey("bank", "zh", "http://x|m", "He sat on the river bank.")
        val b = TranslationWord.cacheKey("bank", "zh", "http://x|m", "He went to the bank.")
        assertFalse("同一个词在不同语境里必须是两条缓存", a == b)
        // 同样的东西再来一次要稳定命中
        assertEquals(a, TranslationWord.cacheKey("bank", "zh", "http://x|m", "  He sat on the river bank.  "))
        // 归一化：大小写不影响
        assertEquals(a, TranslationWord.cacheKey("Bank", "zh", "http://x|m", "He sat on the river bank."))
    }

    @Test fun cacheKeySeparatesLanguageAndModel() {
        val base = TranslationWord.cacheKey("bank", "zh", "http://x|m", "the bank")
        assertFalse(base == TranslationWord.cacheKey("bank", "en", "http://x|m", "the bank"))
        assertFalse(base == TranslationWord.cacheKey("bank", "zh", "http://y|m", "the bank"))
    }

    // ---------------- 5. 请求竞态：只有最新一次算数 ----------------

    @Test fun onlyTheLatestTicketIsCurrent() {
        val latest = TranslationWord.Latest()
        val a = latest.next()
        assertTrue(latest.isCurrent(a))
        val b = latest.next()
        assertTrue(latest.isCurrent(b))
        assertFalse("点了别的词之后，前一个请求晚回来也不许上屏", latest.isCurrent(a))
    }

    @Test fun invalidateKillsInFlightRequests() {
        val latest = TranslationWord.Latest()
        val ticket = latest.next()
        latest.invalidate()                              // 关闭卡片 / 换语种 / Activity destroy
        assertFalse(latest.isCurrent(ticket))
        assertTrue(latest.isCurrent(latest.next()))
    }

    @Test fun staleTicketStaysStaleAcrossManyTaps() {
        val latest = TranslationWord.Latest()
        val tickets = (1..50).map { latest.next() }
        assertTrue(latest.isCurrent(tickets.last()))
        assertTrue(tickets.dropLast(1).none { latest.isCurrent(it) })
    }

    // ---------------- 6. 提示词 ----------------

    @Test fun wordPromptCarriesAllFourRequestedParts() {
        val p = TranslationWord.prompt("bank", "He sat on the river bank.")
        assertTrue("译义", p.contains("① 译义"))
        assertTrue("解析", p.contains("② 解析"))
        assertTrue("搭配与用法", p.contains("③ 搭配与用法"))
        assertTrue("双语例句", p.contains("④ 例句"))
        assertTrue("语境要带上，否则给的是词典全文", p.contains("He sat on the river bank."))
        assertTrue(p.endsWith("查询：bank"))
    }

    @Test fun wordPromptSkipsEmptyContext() {
        val p = TranslationWord.prompt("bank", "   ")
        assertFalse(p.contains("语境（原文）"))
        assertTrue(p.endsWith("查询：bank"))
    }

    /** 「只翻译」的提示词必须明确不要解析（否则又变回「伪装成只翻译」） */
    @Test fun plainPromptAsksForTranslationOnly() {
        val p = TranslationWord.plainPrompt("The plan is unworkable.", "zh")
        assertTrue(p.contains("只输出译文"))
        assertTrue(p.contains("不要解析"))
        assertTrue(p.contains(Prompts.lang("zh").rule))
        assertTrue("结尾契约：原文固定放在最后", p.endsWith("原文：\nThe plan is unworkable."))
        // 和完整解析那份提示词不是同一份（那份要词性 / 义项 / 例句）
        assertFalse(p == Prompts.translate("The plan is unworkable.", "zh"))
    }

    @Test fun plainPromptFollowsTheChosenLanguage() {
        assertTrue(TranslationWord.plainPrompt("你好", "en").contains(Prompts.lang("en").rule))
        assertTrue(TranslationWord.plainPrompt("你好", "ja").contains(Prompts.lang("ja").rule))
    }

}
