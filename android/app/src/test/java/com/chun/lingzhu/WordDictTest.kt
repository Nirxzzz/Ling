package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.80 「解读词」（[WordDict]）：把电脑版悬停词典那套搬到安卓。
 *
 * 这里最要紧的一条是**提示词逐字对齐**：电脑版 `main.js` 的 `wordPrompt` 和这份常量是双端契约，
 * 改了一边另一边也得改，否则同一个词两端给出两种口吻的答案（项目里 PERSONA / TERMS_RULE 都是这么钉的）。
 * 所以下面那份 [DESKTOP_PROMPT]（手抄自 main.js）不是「大概像」—— 是一个字都不能差。
 */
class WordDictTest {

    /** 手抄自电脑版 main.js 的 wordPrompt（客户端拼的那部分） */
    private val DESKTOP_PROMPT = "你是双语词典。对用户给的单词或短语，用中文输出，不要寒暄、不要重复原词：\n" +
        "第1行：音标（仅英文词给，用 /.../ 包住）+ 词性缩写\n" +
        "第2~4行：核心释义，每行一条（最重要的在前，可带简短搭配）\n" +
        "最后1行：一个简短例句（原语言 + 中文翻译，用 — 分隔）\n" +
        "总长不超过 90 字。查询："

    /** 手抄自电脑版的兜底那句 */
    private val DESKTOP_FALLBACK = "把下面这个词译成中文，只输出中文译文，不要解释、不要标点：\n"

    // ---------------- 1. 提示词（双端契约） ----------------

    @Test fun dictPromptMatchesTheDesktopVerbatim() {
        assertEquals(DESKTOP_PROMPT, WordDict.PROMPT_HEAD)
        assertEquals(DESKTOP_PROMPT + "abandon", WordDict.prompt("abandon"))
    }

    @Test fun fallbackPromptMatchesTheDesktopVerbatim() {
        assertEquals(DESKTOP_FALLBACK, WordDict.FALLBACK_HEAD)
        assertEquals(DESKTOP_FALLBACK + "abandon", WordDict.fallbackPrompt("abandon"))
    }

    /** 电脑版的 token 预算：词典式 700、兜底 220（照抄，别顺手改） */
    @Test fun tokenBudgetsMatchTheDesktop() {
        assertEquals(700, WordDict.MAX_TOKENS)
        assertEquals(220, WordDict.MAX_TOKENS_FALLBACK)
    }

    // ---------------- 2. 什么样的东西才去查 ----------------

    @Test fun normalizeTrimsLowercasesAndClips() {
        assertEquals("abandon", WordDict.normalize("  Abandon "))
        assertEquals(WordDict.MAX_LEN, WordDict.normalize("x".repeat(200)).length)
    }

    @Test fun onlyLatinWordsAndPhrasesAreLookedUp() {
        assertTrue(WordDict.isWord("abandon"))
        assertTrue(WordDict.isWord("Abandon"))               // 归一后会小写
        assertTrue(WordDict.isWord("well-known"))            // 连字符的复合词算「一个词」
        assertTrue(WordDict.isWord("o'clock"))               // 撇号也一样
        assertFalse("带空格不算一个词（电脑版那条正则也不认）", WordDict.isWord("give up"))
        assertFalse("中文词不是英汉词典的活", WordDict.isWord("放弃"))
        assertFalse("带数字的型号不查词", WordDict.isWord("R7250"))
        assertFalse("一个字母太短", WordDict.isWord("a"))
        assertFalse("空串", WordDict.isWord("   "))
        assertFalse("太长", WordDict.isWord("x".repeat(41)))
    }

    // ---------------- 3. 拿到的东西要不要重新排版 ----------------

    /** 多行 / 音标斜杠 / 例句破折号 —— 中一条就按「已经是词典」原样上屏 */
    @Test fun formattedDictionaryIsRecognized() {
        assertTrue(WordDict.looksFormatted("/əˈbændən/ v.\n放弃；抛弃\n— They had to abandon the car."))
        assertTrue("单行但有音标也算", WordDict.looksFormatted("/əˈbændən/ v. 放弃"))
        assertTrue("有例句破折号也算", WordDict.looksFormatted("abandon — 放弃；抛弃"))
    }

    /** DeepL 那种一句干译文必须走排版（套成「1. …」+ 模板例句） */
    @Test fun bareTranslationIsNotTreatedAsDictionary() {
        assertFalse(WordDict.looksFormatted("放弃；抛弃"))
        assertFalse(WordDict.looksFormatted("放弃"))
        assertFalse(WordDict.looksFormatted(""))
    }

    // ---------------- 4. 擦掉模型多写的开头 ----------------

    @Test fun tidyDropsEchoedWordAndQueryLabel() {
        val dict = "/əˈbændən/ v.\n放弃；抛弃\n— They had to abandon the car."
        assertEquals(dict, WordDict.tidy("abandon\n$dict", "abandon"))
        assertEquals(dict, WordDict.tidy("查询：abandon\n$dict", "abandon"))
        assertEquals(dict, WordDict.tidy("Abandon\n$dict", "abandon"))   // 大小写不敏感
    }

    /** 正常的词典文本一个字都不动（音标行不能被当成「抄原词」掐掉） */
    @Test fun tidyLeavesAGoodDictionaryAlone() {
        val dict = "/əˈbændən/ v.\n放弃；抛弃\n— They had to abandon the car."
        assertEquals(dict, WordDict.tidy(dict, "abandon"))
    }

    /** 全掐没了要退回原文：宁可多一行噪音，也不能给一张空卡 */
    @Test fun tidyFallsBackWhenEverythingWouldBeStripped() {
        assertEquals("abandon", WordDict.tidy("abandon", "abandon"))
    }

    @Test fun tooLongFollowsTheDesktopSoftLimit() {
        assertFalse(WordDict.tooLong("短".repeat(WordDict.SOFT_MAX_CHARS)))
        assertTrue(WordDict.tooLong("长".repeat(WordDict.SOFT_MAX_CHARS + 1)))
    }

    // ---------------- 5. 内存 LRU + 没取到的时间表 ----------------

    @Test fun memoCachesAndEvictsTheOldest() {
        val m = WordDict.Memo(max = 2)
        m.put("a", "1"); m.put("b", "2")
        assertEquals("1", m.get("a"))
        m.put("c", "3")                        // 挤掉最老的 a
        assertNull("最老的应该被挤掉", m.get("a"))
        assertEquals("2", m.get("b"))
        assertEquals("3", m.get("c"))
        assertEquals(2, m.size())
    }

    @Test fun memoNeverCachesBlankText() {
        val m = WordDict.Memo()
        m.put("a", "   ")
        assertNull(m.get("a"))
    }

    /** 没取到的 60 秒内不再重打；过了就允许再试；真取到了立刻撤掉记号 */
    @Test fun memoRemembersMissesForTheTtl() {
        val now = 1_760_000_000_000L
        val m = WordDict.Memo()
        m.markMiss("a", now)
        assertTrue(m.missedRecently("a", now))
        assertTrue(m.missedRecently("a", now + WordDict.MISS_TTL_MS - 1))
        assertFalse("过了 TTL 就该允许再试", m.missedRecently("a", now + WordDict.MISS_TTL_MS))
        assertFalse("没记过的词不算刚失败", m.missedRecently("b", now))

        m.put("a", "放弃")
        assertFalse("这次取到了，记号要撤掉", m.missedRecently("a", now))
    }

    // ---------------- 6. 落盘 ----------------

    @Test fun jsonRoundTripsAndKeepsInsertOrder() {
        val rows = listOf("a" to "1", "b" to "2")
        assertEquals(rows, WordDict.parse(WordDict.toJson(rows)))
        assertEquals(emptyList<Pair<String, String>>(), WordDict.parse(null))
        assertEquals(emptyList<Pair<String, String>>(), WordDict.parse("不是 json"))
    }

    // ---------------- 7. 从词典文本里挑音标 / 例句（给词书卡补料用） ----------------

    private val DICT = "/əˈbændən/ v.\n放弃；抛弃\nabandon a plan 放弃计划\n— They had to abandon the car. 他们只好把车丢下。"

    @Test fun phoneticLineIsPickedBySlashOrPos() {
        assertEquals("/əˈbændən/ v.", WordDict.phoneticOf(DICT))
        assertEquals("/əˈbændən/ v.", WordDict.phoneticOf("/əˈbændən/ v.\n放弃"))     // 单行词典也给
        assertEquals("v./n.", WordDict.phoneticOf("v./n.\n放弃"))                     // 只有词性也算
        assertNull("第一行是中文释义，不是音标行", WordDict.phoneticOf("放弃；抛弃\n抛弃"))
        assertNull(WordDict.phoneticOf(""))
    }

    @Test fun exampleLineIsPickedFromTheDashLine() {
        assertEquals("They had to abandon the car. 他们只好把车丢下。", WordDict.exampleOf(DICT))
        // 模型省了破折号，但末行确实是句子（拉丁字母开头）→ 认
        assertEquals("They had to leave. 他们只好离开。", WordDict.exampleOf("/əˈbændən/ v.\n放弃\nThey had to leave. 他们只好离开。"))
        assertNull("只有一行 = 不是词典，别把释义当例句", WordDict.exampleOf("放弃；抛弃"))
        assertNull("末行是中文释义就别当例句", WordDict.exampleOf("/əˈbændən/ v.\n放弃；抛弃"))
    }

    /** 音标 / 例句的行必须能跟 appendDict 对上：两样都挑得出来 */
    @Test fun phoneticAndExampleWorkTogetherForTheDeckCard() {
        val ph = WordDict.phoneticOf(DICT); val ex = WordDict.exampleOf(DICT)
        assertEquals("/əˈbændən/ v.", ph)
        assertTrue(ex!!.startsWith("They had to abandon the car."))
    }
}
