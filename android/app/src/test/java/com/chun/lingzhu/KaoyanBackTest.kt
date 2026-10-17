package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaoyanBack]：考研红宝书式书页背面的解析（v1.81）。纯逻辑，不碰 UI。
 */
class KaoyanBackTest {

    // ---------------- isRich ----------------

    @Test fun isRichTrueForBracketedOrPhoneticBack() {
        assertTrue("含【标签】就是富卡", KaoyanBack.isRich("【词义】公平,公正"))
        assertTrue("首行音标行（[开头）", KaoyanBack.isRich("['dʒʌstɪs] n.\n【词义】公平"))
        assertTrue("首行只有词性缩写也算音标行", KaoyanBack.isRich("vt."))
        assertTrue("前后有空白也能识别", KaoyanBack.isRich("  [ə'bændən] v.\n放弃"))
    }

    @Test fun isRichFalseForPlainBack() {
        assertFalse("空输入", KaoyanBack.isRich(""))
        assertFalse("全空白", KaoyanBack.isRich("   \n  \n"))
        assertFalse("词性 + 释义的普通一行", KaoyanBack.isRich("v. 放弃；抛弃"))
        assertFalse("纯中文释义", KaoyanBack.isRich("放弃；抛弃"))
    }

    // ---------------- isExamTag ----------------

    @Test fun isExamTagTrueForAnyTagContainingZhenti() {
        assertTrue("真题例句", KaoyanBack.isExamTag("真题例句"))
        assertTrue("真题", KaoyanBack.isExamTag("真题"))
        assertTrue("典型真题", KaoyanBack.isExamTag("典型真题"))
    }

    @Test fun isExamTagFalseForOtherOrEmptyTags() {
        assertFalse("词义", KaoyanBack.isExamTag("词义"))
        assertFalse("词组", KaoyanBack.isExamTag("词组"))
        assertFalse("辨析", KaoyanBack.isExamTag("辨析"))
        assertFalse("空标签", KaoyanBack.isExamTag(""))
    }

    // ---------------- parse ----------------

    @Test fun emptyInputGivesEmptyList() {
        assertEquals(emptyList<Block>(), KaoyanBack.parse(""))
        assertEquals(emptyList<Block>(), KaoyanBack.parse("   "))
        assertEquals(emptyList<Block>(), KaoyanBack.parse("\n\n  \n"))
    }

    @Test fun firstPhoneticLineBecomesTaglessBlock() {
        val bs = KaoyanBack.parse("['dʒʌstɪs] n.\n【词义】公平")
        assertEquals(2, bs.size)
        assertEquals("", bs[0].tag)
        assertEquals("['dʒʌstɪs] n.", bs[0].text)
        assertFalse(bs[0].frag)
        assertEquals("词义", bs[1].tag)
        assertEquals("公平", bs[1].text)
    }

    @Test fun bracketedTagSplitsTagAndBody() {
        val bs = KaoyanBack.parse("【助记】 just(公正的)+ ice(名词后缀)→公正")
        assertEquals(1, bs.size)
        assertEquals("助记", bs[0].tag)
        assertEquals("just(公正的)+ ice(名词后缀)→公正", bs[0].text)
    }

    @Test fun emptyTagBodyIsFilledByFollowingUntaggedLines() {
        // 【词义】后面没字 → 紧接着的无标签行都归它
        val bs = KaoyanBack.parse("【词义】\n公平,公正,合理\n审判,司法")
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("公平,公正,合理\n审判,司法", bs[0].text)
    }

    @Test fun bareLabelWithoutBrackets() {
        val bs = KaoyanBack.parse("【词义】公平\n辨析 abandon, desert, discard, forsake")
        assertEquals(2, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("辨析", bs[1].tag)
        assertEquals("abandon, desert, discard, forsake", bs[1].text)
    }

    @Test fun circledLinesMergeIntoPreviousBlockKeepingNumbers() {
        val bs = KaoyanBack.parse("【词义】① 公平\n② 审判\n③ 法官")
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("① 公平\n② 审判\n③ 法官", bs[0].text)
    }

    @Test fun circledLineAfterBracketedBodyMergesToo() {
        val bs = KaoyanBack.parse("【词组】① in justice to 公平\n②bring sb. to justice使归案")
        assertEquals(1, bs.size)
        assertEquals("词组", bs[0].tag)
        // ② 那条的英文短语与中文释义再被 wrap 拆开（英文→中文交界）
        assertEquals("① in justice to 公平\n②bring sb. to justice\n使归案", bs[0].text)
    }

    @Test fun fragLineBecomesOwnBlockAndKeepsDot() {
        val bs = KaoyanBack.parse("【词义】公平\n· 由于缺乏资金，这位科学家放弃了他的研究工作。")
        assertEquals(2, bs.size)
        assertEquals("", bs[1].tag)
        assertTrue(bs[1].frag)
        assertTrue("行首的 · 要留着", bs[1].text.startsWith("·"))
        assertEquals("· 由于缺乏资金，这位科学家放弃了他的研究工作。", bs[1].text)
    }

    @Test fun untaggedLineMergesIntoPreviousOrStandsAlone() {
        // 前面没块 → 自成一块
        val solo = KaoyanBack.parse("意外发现的运气")
        assertEquals(1, solo.size)
        assertEquals("", solo[0].tag)
        assertEquals("意外发现的运气", solo[0].text)
        // 前面有块 → 并进去
        val merged = KaoyanBack.parse("【助记】x\n继续一行")
        assertEquals(1, merged.size)
        assertEquals("x\n继续一行", merged[0].text)
    }

    @Test fun examBlocksMovedToEndKeepingRelativeOrder() {
        val back = """
            【词义】① 公平,公正
            【真题例句】The rapid growth of AI...
            【词组】① in justice to 公平
            【真题】Second exam sentence.
        """.trimIndent()
        val bs = KaoyanBack.parse(back)
        assertEquals("块数", 4, bs.size)

        // 真题块一律排到最后，非真题块保持原相对顺序在前
        assertEquals(listOf("词义", "词组", "真题例句", "真题"), bs.map { it.tag })
        assertTrue("末尾两块都必须是真题块", bs.takeLast(2).all { KaoyanBack.isExamTag(it.tag) })
        // 两组各自的相对顺序都没乱
        assertEquals(listOf("词义", "词组"), bs.filter { !KaoyanBack.isExamTag(it.tag) }.map { it.tag })
        assertEquals(listOf("真题例句", "真题"), bs.filter { KaoyanBack.isExamTag(it.tag) }.map { it.tag })

        // 非真题块的正文没被改（除 wrap 分行外一字不动）
        assertEquals("① 公平,公正", bs[0].text)
        assertEquals("① in justice to 公平", bs[1].text)
        // 真题块正文同样一字不动，只是位置变了
        assertEquals("The rapid growth of AI...", bs[2].text)
        assertEquals("Second exam sentence.", bs[3].text)
    }

    // ---------------- wrap：把挤在一行的正文拆成可读单位 ----------------

    /** 用户给的两个真实例句（原文都挤在一行） */
    private val justiceSenses =
        "【词义】① 公平,公正,合理(u): All men should be treated with justice.人人都应当受到公平的待遇。" +
            "②审判,司法(制度): The courts ensure that justice is achieved.法庭确保审判公正。"
    private val roleSenses =
        "【词义】①角色: Tom Hanks played the role of Forrest in Forrest Gump.汤姆·汉克斯在《阿甘正传》中扮演阿甘的角色。" +
            "②作用,任务: the key role关键作用"

    private fun isHan(c: Char) = c in '\u4E00'..'\u9FFF'
    private fun hasAsciiLetter(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    @Test fun wrapSplitsSensesAndEnglishFromChinese() {
        val bs = KaoyanBack.parse(justiceSenses)
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        val lines = bs[0].text.split("\n")
        assertEquals("两个义项 + 各自英文/中文 = 4 行", 4, lines.size)

        // ① 义项：英文例句一整行，句子里不夹汉字
        assertTrue(lines[0].startsWith("①"))
        assertTrue(lines[0].endsWith("All men should be treated with justice."))
        assertTrue("英文例句那一段不该夹汉字", lines[0].substringAfter("All men").none { isHan(it) })
        // 中文译文单独一行、且不含 ASCII 字母
        assertEquals("人人都应当受到公平的待遇。", lines[1])
        assertFalse("译文行不该有 ASCII 字母", hasAsciiLetter(lines[1]))

        // ② 义项同样拆开
        assertTrue(lines[2].startsWith("②"))
        assertTrue(lines[2].endsWith("The courts ensure that justice is achieved."))
        assertEquals("法庭确保审判公正。", lines[3])
    }

    @Test fun wrapSplitsRoleExample() {
        val lines = KaoyanBack.parse(roleSenses)[0].text.split("\n")
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("①"))
        assertTrue(lines[0].endsWith("Forrest in Forrest Gump."))
        assertEquals("汤姆·汉克斯在《阿甘正传》中扮演阿甘的角色。", lines[1])
        assertFalse("中文译文不该含 ASCII 字母", hasAsciiLetter(lines[1]))
        assertTrue(lines[2].startsWith("②"))
        assertEquals("关键作用", lines[3])
    }

    @Test fun wrapBreaksMidLineCircledNumbers() {
        // ①②③ 挤在一行 → 各占一行
        assertEquals("① 公平\n② 公正\n③ 法官", KaoyanBack.parse("【词义】① 公平② 公正③ 法官")[0].text)
        // 实心记号 ❶❷❸ 同样算序号
        assertEquals("❶ 甲\n❷ 乙", KaoyanBack.wrap("❶ 甲❷ 乙"))
    }

    @Test fun wrapBreaksMidLineTag() {
        assertEquals("前文\n【词义】后文", KaoyanBack.wrap("前文【词义】后文"))
    }

    @Test fun wrapKeepsTagWithMarkAndPos() {
        // 【词性】❶n. 的标签 + 记号 + 词性保持一行（❶ 不当作新义项）
        assertEquals("【词性】❶n.", KaoyanBack.wrap("【词性】❶n."))
        assertEquals("x\n【词性】❶n.", KaoyanBack.wrap("x【词性】❶n."))
    }

    @Test fun wrapKeepsBracketLabelIntact() {
        // 【…】 内部整体不切：数字 + 汉字 也不断开
        assertEquals("例句\n【2019年考研阅读】", KaoyanBack.wrap("例句【2019年考研阅读】"))
    }

    @Test fun wrapDoesNotTouchPlainChineseOrEnglish() {
        assertEquals("中华人民共和国万岁", KaoyanBack.wrap("中华人民共和国万岁"))
        assertEquals("Hello, world! It works.", KaoyanBack.wrap("Hello, world! It works."))
        // 中文里的 ASCII 逗号不算交界（公平,公正 不拆）
        assertEquals("① 公平,公正,合理(u):", KaoyanBack.wrap("① 公平,公正,合理(u):"))
    }

    @Test fun wrapIsIdempotent() {
        for (s in listOf(justiceSenses, roleSenses, "【词组】① in justice to 公平\n②bring sb. to justice使归案")) {
            val once = KaoyanBack.wrap(KaoyanBack.parse(s).joinToString("\n") { it.text })
            assertEquals("wrap 应当幂等", once, KaoyanBack.wrap(once))
        }
        // parse 也稳：把解析出的块正文再当背面解析一遍，正文一致
        val bs = KaoyanBack.parse(justiceSenses)
        assertEquals(bs[0].text, KaoyanBack.parse(bs[0].text)[0].text)
    }

    @Test fun wrapBlankInputsAreSafe() {
        assertEquals("", KaoyanBack.wrap(""))
        assertEquals("", KaoyanBack.wrap("   "))
        assertEquals("", KaoyanBack.wrap("\n\n  \n"))
        assertTrue("不产出空行", KaoyanBack.wrap("a\n\n\nb").split("\n").none { it.isEmpty() })
    }

    // ---------------- 真实红宝书背面（整块结构） ----------------

    private val justice = """
        ['dʒʌstɪs] n.
        【助记】 just(公正的)+ ice(名词后缀)→公正
        【词义】① 公平,公正,合理(u): All men should be treated with justice.人人都应当受到公平的待遇。
        ② 审判,司法(制度),法律制裁(u): The courts ensure that justice is achieved.法庭确保审判公正。
        ③ 法官(c): Andrew will be stearn in today as a justice on the Supreme Court.安德鲁今天将宣誓就任最高法院法官。
        【词组】① in justice to 为了对······公平起见
        ②bring sb. to justice使(犯人)归案受审
        【真题例句】 Today the rapid growth of artificial intelligence...  【2019年考研阅读】
        · 由于缺乏资金，这位科学家放弃了他的研究工作。
    """.trimIndent()

    @Test fun realKaoyanBackParsesIntoOrderedBlocks() {
        val bs = KaoyanBack.parse(justice)
        assertEquals("块数", 6, bs.size)

        // 顺序与 tag：v1.83 起「真题例句」被稳定地挪到末尾（换页残句仍在它前面，非真题块相对顺序不变）
        assertEquals(listOf("", "助记", "词义", "词组", "", "真题例句"), bs.map { it.tag })

        // 音标行
        assertEquals("['dʒʌstɪs] n.", bs[0].text)

        // 助记
        assertEquals("just(公正的)+ ice(名词后缀)→公正", bs[1].text)

        // 词义：①~③ 三条义项各占一行；每条里英文例句与中文译文再各占一行 → 6 行
        val senses = bs[2].text.split("\n")
        assertEquals(6, senses.size)
        assertEquals("① 公平,公正,合理(u): All men should be treated with justice.", senses[0])
        assertEquals("人人都应当受到公平的待遇。", senses[1])
        assertTrue(senses[2].startsWith("② 审判,司法(制度),法律制裁"))
        assertEquals("法庭确保审判公正。", senses[3])
        assertTrue(senses[4].startsWith("③ 法官(c)"))
        assertEquals("安德鲁今天将宣誓就任最高法院法官。", senses[5])

        // 词组：① 中文释义在空格后、不当交界，② 的英文与中文分开 → 3 行
        val phrases = bs[3].text.split("\n")
        assertEquals(3, phrases.size)
        assertEquals("① in justice to 为了对······公平起见", phrases[0])
        assertEquals("②bring sb. to justice", phrases[1])
        assertEquals("使(犯人)归案受审", phrases[2])

        // 换页残句：保留、标 frag（非真题块，仍在真题块之前）
        assertEquals("", bs[4].tag)
        assertTrue(bs[4].frag)
        assertEquals("· 由于缺乏资金，这位科学家放弃了他的研究工作。", bs[4].text)

        // 真题例句：被排到最后（正文里的 【2019年考研阅读】 不该被再切一刀）
        assertEquals("真题例句", bs[5].tag)
        assertTrue(bs[5].text.contains("rapid growth of artificial intelligence"))
        assertTrue(bs[5].text.contains("【2019年考研阅读】"))
    }

    /** 同一段背面走缓存：解析结果一致，且重复调用拿到同一份（纯函数语义） */
    @Test fun parseMemoizedIsStable() {
        val a = KaoyanBack.parseMemoized(justice)
        val b = KaoyanBack.parseMemoized(justice)
        assertEquals(a, b)
        assertEquals(KaoyanBack.parse(justice), a)
    }

    /**
     * 背面块错峰淡入的节奏（v1.81）：第一块先等卡片入场落定，之后每块固定错开一步；
     * 超过上限的块不再继续往后排 —— 一屏只看得见 5~7 块，长条目不该等上一秒。
     */
    @Test fun staggerDelayIsMonotonicAndCapped() {
        assertEquals(KaoyanBack.STAGGER_START_MS, KaoyanBack.staggerDelayMs(0))
        assertEquals(
            KaoyanBack.STAGGER_START_MS + KaoyanBack.STAGGER_STEP_MS,
            KaoyanBack.staggerDelayMs(1),
        )
        assertTrue(KaoyanBack.STAGGER_STEP_MS > 0)
        // 前 N 块严格递增
        for (i in 1..KaoyanBack.STAGGER_MAX_BLOCKS) {
            assertTrue("第 $i 块应当比上一块晚", KaoyanBack.staggerDelayMs(i) > KaoyanBack.staggerDelayMs(i - 1))
        }
        // 到顶之后不再增加（一起出来），也不会因为负数下标跑到前面去
        val cap = KaoyanBack.staggerDelayMs(KaoyanBack.STAGGER_MAX_BLOCKS)
        assertEquals(cap, KaoyanBack.staggerDelayMs(KaoyanBack.STAGGER_MAX_BLOCKS + 1))
        assertEquals(cap, KaoyanBack.staggerDelayMs(999))
        assertEquals(KaoyanBack.STAGGER_START_MS, KaoyanBack.staggerDelayMs(-5))
        // 最坏情况：最后一块要等 350ms 左右，别失控
        assertTrue("错峰总时长应当在一秒内", cap < 1_000L)
    }

    // ---------------- layout（v1.84）：按下标切分，字符不增不减，纯函数且幂等 ----------------

    @Test fun layoutPreservesEveryCharacterAndIsIdempotent() {
        val cases = listOf(
            justiceSenses, roleSenses, justice,
            "① 公平,公正②审判", "详见【注】", "公平,公正", "【词性】❶n.",
            "人人都应当受到公平的待遇。The courts ensure justice.",
            "", "   ", "\n\n  \n",
        )
        for (s in cases) {
            val ls = KaoyanBack.layout(s)
            assertEquals("拼回必须一字不差：<$s>", s, ls.joinToString("") { it.text })
            assertEquals("字符总数一致：<$s>", s.length, ls.sumOf { it.text.length })
            assertEquals("幂等：<$s>", ls, KaoyanBack.layout(ls.joinToString("") { it.text }))
        }
    }

    @Test fun layoutBlankInputsGiveEmptyLineOrEmptyList() {
        // 空串 → 至多一个空 Line（拼回为空）；全空白按原样拼回（不 trim 丢字符）
        assertTrue("空串给一个空 Line", KaoyanBack.layout("").size <= 1)
        assertEquals("", KaoyanBack.layout("").joinToString("") { it.text })
        assertEquals("   ", KaoyanBack.layout("   ").joinToString("") { it.text })
        assertEquals("\n\n  \n", KaoyanBack.layout("\n\n  \n").joinToString("") { it.text })
    }

    // ---- R1 序号项 + 悬挂缩进归属 ----

    @Test fun layoutHangsContinuationLinesUnderNumberedItems() {
        val ls = KaoyanBack.layout("① 甲\n乙\n② 丙")
        assertEquals("序号行本身顶格", 0, ls.first { it.text.trimStart().startsWith("①") }.indent)
        assertEquals("① 的续行悬挂缩进", 1, ls.first { it.text.trim() == "乙" }.indent)
        assertEquals("下一个序号又顶格", 0, ls.first { it.text.trimStart().startsWith("②") }.indent)
    }

    @Test fun layoutIndentResetsAtBlockTagAndBlankLine() {
        val byTag = KaoyanBack.layout("① 甲\n【词义】乙")
        assertEquals("【tag】结束悬挂缩进", 0, byTag.first { it.text.trimStart().startsWith("【词义】") }.indent)
        val byBlank = KaoyanBack.layout("① 甲\n\n乙")
        assertEquals("空行结束悬挂缩进", 0, byBlank.first { it.text.trim() == "乙" }.indent)
    }

    @Test fun layoutHangsSensesContinuationFromParsedBody() {
        // 真实块正文（parse→wrap 过的）：① 项的续行必须 indent=1（修「续行顶格」）
        val body = KaoyanBack.parse(justiceSenses)[0].text
        val ls = KaoyanBack.layout(body)
        assertEquals(body, ls.joinToString("") { it.text })
        assertTrue("应有悬挂缩进的续行", ls.any { it.indent == 1 })
        assertTrue("续行就是 ① 项的中文译文", ls.any { it.indent == 1 && it.text.contains("人人都应当受到公平的待遇") })
    }

    // ---- R2 【tag】----

    @Test fun layoutKeepsInlineBracketButBreaksBlockTag() {
        assertEquals("内嵌【注】不许断", 1, KaoyanBack.layout("详见【注】").size)
        val ls = KaoyanBack.layout("前文说明【词义】公平,公正")
        assertTrue("块标签【词义】要断在它前面", ls.any { it.text.trimStart().startsWith("【词义】") })
        assertEquals("前文说明【词义】公平,公正", ls.joinToString("") { it.text })
        assertTrue("句末标点后的【】也断", KaoyanBack.layout("公平待遇。【注】").size >= 2)
    }

    // ---- R3 中↔英双向断行 + 锚点跳标点 ----

    @Test fun layoutBreaksBothDirectionsBetweenHanAndAscii() {
        val cn2en = KaoyanBack.layout("人人都应当受到公平的待遇。The courts ensure justice.")
        assertEquals("中文在前、英文紧跟也要断", 2, cn2en.size)
        assertTrue(cn2en[0].text.startsWith("人人都"))
        assertTrue(cn2en[1].text.startsWith("The courts"))
        // 反例：中文 + 中文标点（ASCII 逗号）+ 中文 不许断
        assertEquals(1, KaoyanBack.layout("公平,公正").size)
        assertEquals(1, KaoyanBack.layout("司法的公平,公正与合理").size)
    }

    @Test fun layoutSkipsAsciiPunctWhenFindingAnchor() {
        val ls = KaoyanBack.layout("Forrest Gump.)汤姆·汉克斯")
        assertTrue("Gump.)汤姆 应能断", ls.size >= 2)
        assertTrue("断点落在 .) 之后", ls[0].text.endsWith("Gump.)"))
        assertTrue("汉字另起一行", ls[1].text.startsWith("汤姆"))
    }

    // ---- R4 行首禁则 ----

    @Test fun layoutNeverStartsLineWithForbiddenPunctuation() {
        val src = "在考研英语阅读中，长难句的切分往往决定了理解的成败；一个标点处理不当，" +
            "整句话的逻辑就会崩塌。因此，我们必须在句号、逗号、顿号、分号、冒号之后谨慎寻找断点。"
        val forbidden = "。，、；：！？）】」』…—"
        for (l in KaoyanBack.layout(src)) {
            val c = l.text.trimStart().firstOrNull()
            assertTrue("行首不许是收尾标点「$c」", c == null || c !in forbidden)
        }
    }

    @Test fun layoutKeepsSoftPunctuationAtLineEnd() {
        val src = "苹果、香蕉、橘子、葡萄、西瓜、梨子".repeat(8)
        val ls = KaoyanBack.layout(src)
        assertTrue(ls.size > 1)
        for (l in ls) assertTrue("不以「、」开头", l.text.trim().firstOrNull() != '、')
        assertEquals(src, ls.joinToString("") { it.text })
    }

    // ---- R5 超长行软断点 ----

    @Test fun layoutBreaksOverlongUnpunctuatedLine() {
        val src = "司法公正是维护社会公平正义的最后一道防线".repeat(6)   // 无标点、无空格
        val ls = KaoyanBack.layout(src)
        assertTrue("超长无标点行必须断开", ls.size > 1)
        assertTrue("单行不得超上限", ls.maxOf { it.text.length } <= KaoyanBack.MAX_LINE)
        assertEquals(src, ls.joinToString("") { it.text })
    }

    // ---- R6 【…】 内部不切 ----

    @Test fun layoutNeverCutsInsideBracketTag() {
        assertEquals("标签+记号+词性保持一行", 1, KaoyanBack.layout("【词性】❶n.").size)
        for (l in KaoyanBack.layout("例句【2019年考研阅读】")) {
            val t = l.text.trimStart()
            if (t.startsWith("【")) assertTrue("以【开头的行内必须含】", l.text.contains("】"))
        }
        // 不平衡的【：不崩、不丢字符
        assertEquals("【词义公平,公正", KaoyanBack.layout("【词义公平,公正").joinToString("") { it.text })
    }

    // ---------------- v1.85 真机投诉：英文例句被 softBreak 从中间切开 ----------------

    /** 用户截图里那条 institute 的第一个义项：原文 82 字 > MAX_LINE，英文例句长 46 字 */
    private val instituteSense1 =
        "学会,协会,学院,研究院(u) : Many universities have institutes of education.许多大学都设有教育学院。"

    /**
     * 钉的决定（v1.85）：**R5 软断不许切在英文例句的词与词之间**。
     *
     * 旧行为（用户投诉原文：「那个词性和词义分开来…没想到它们是同一个内容」）之外第二条投诉：
     * `Many universities have institutes of education.` 被断成 `…institutes of` + `education.`，
     * 末词成了孤行 —— 根因是 R5 只回扫**空格**，而英文例句内部全是空格，空格是「英文内部的合法断点」，
     * 不是语义边界。现在 R5 先找「中文义项 → 英文例句」的交界，断在交界上，让整句英文占一行。
     *
     * 这条断言正是把用户看到的那个坏结果钉住：英文例句必须**完整**出现在某一行，且那一行是句号收尾。
     */
    @Test fun layoutKeepsEnglishExampleWholeInsteadOfCuttingAtSpace() {
        val ls = KaoyanBack.layout(instituteSense1)
        // v1.85.9 起行宽按显示宽度算：中文义项 + 这句英文整体塞得进预算 → 整行不断；
        // 塞不进的分支则必须断在交界上。两种形态都合法，共同硬要求是：英文例句一个词都不许断开。
        val joined = ls.joinToString("") { it.text }
        assertTrue("例句必须完整保留：$joined", joined.contains("Many universities have institutes of education."))
        for (k in 0 until ls.size - 1) {
            val cur = ls[k].text.trimEnd(); val nxt = ls[k + 1].text.trimStart()
            val midCut = cur.isNotEmpty() && nxt.isNotEmpty() &&
                cur.last().isLetter() && cur.last().code < 128 && !cur.endsWith("-") &&
                nxt[0].isLetter() && nxt[0].code < 128 && nxt[0].isLowerCase()
            assertTrue("英文词与词之间不许断：'$cur' ⏎ '$nxt'", !midCut)
        }
    }

    /** 同一条规则的第二个真实样本：词组带冒号引导的例句，英文也不许被切开 */
    @Test fun layoutKeepsEnglishExampleWholeAfterColonGlue() {
        val src = "①建立,设立,制定: The government instituted a consumer protection agency."
        val ls = KaoyanBack.layout(src)
        // v1.85.9：整体塞得进宽度预算 → 一行不断；跨行时也绝不许切在英文词之间
        val joined = ls.joinToString("") { it.text }
        assertTrue("例句必须完整保留：$joined", joined.contains("The government instituted a consumer protection agency."))
        assertTrue("宽度预算内应保持单行，实得 ${ls.map { it.text }}", ls.size == 1)
    }

    /**
     * 钉的**放弃条件**（保守边界）：只有「英文那半截自己塞得进一行」才值得为它放弃填满行宽。
     * 英文比 MAX_LINE 还长时，断在交界只会切出一个残行、下一行照样要再切 —— 那就退回原来的空格回扫，
     * 仍然不许丢字符、仍然不许无限断行。
     */
    @Test fun layoutGivesUpSemanticCutWhenEnglishAloneExceedsLimit() {
        // 32 字中文义项 + 一句 161 字的英文（英文自己就远超 MAX_LINE，保不住）
        val english = "The notion that fairness must always be balanced against every other competing value " +
            "has been debated by philosophers for centuries without any final resolution."
        val src = "①公平,公正,合理,客观,全面,系统,深入,透彻,严谨,准确: $english"
        assertTrue("样本的英文部分本身就超过上限，保不住", english.length > KaoyanBack.MAX_LINE)
        val ls = KaoyanBack.layout(src)
        // 放弃交界 → 退回软断点填充：按显示宽度不许超预算太多、必须断行、字符一字不丢
        assertTrue("超长行必须断成多行", ls.size > 1)
        assertEquals("字符不增不减（硬不变量）", src, ls.joinToString("") { it.text })
        val maxW = ls.maxOf { l -> dispW(l.text) }
        assertTrue("单行显示宽度不得失控：$maxW", maxW <= KaoyanBack.MAX_LINE * 2 + 28)
    }

    /** 纯中文长行（没有英文可保）不受新规则影响：仍然靠 MAX_LINE 软断填满，不产生残行 */
    @Test fun layoutStillFillsWidthForPureChineseLongLine() {
        val src = "司法公正是维护社会公平正义的最后一道防线".repeat(6)
        val ls = KaoyanBack.layout(src)
        assertEquals("字符不增不减", src, ls.joinToString("") { it.text })
        assertTrue("纯中文仍应填满行宽（不该为不存在的英文让出宽度）", ls.first().text.length >= KaoyanBack.MAX_LINE - 1)
    }

    /** 新规则也不能破坏「字符不增不减 + 幂等」这两条硬不变量（回归防线，覆盖真实红宝书正文） */
    @Test fun englishExampleFixKeepsInvariantsOnRealBodies() {
        val cases = listOf(instituteSense1, justiceSenses, roleSenses, justice)
        for (src in cases) {
            val ls = KaoyanBack.layout(src)
            assertEquals("拼回必须一字不差：<$src>", src, ls.joinToString("") { it.text })
            assertEquals("字符总数一致：<$src>", src.length, ls.sumOf { it.text.length })
            assertEquals("幂等：<$src>", ls, KaoyanBack.layout(ls.joinToString("") { it.text }))
        }
    }

    // ---------------- v1.85 真机投诉：词性与词义被拆成两块不同颜色的 chip ----------------

    /** 词性与词义成对出现两次的书页式背面样本 */
    private val institute = """
        ['ɪnstɪtjuːt]
        【词性】❶n.
        【词义】学会,协会,学院,研究院(u) : Many universities have institutes of education.许多大学都设有教育学院。
        【词性】❷ vt.
        【词义】①建立,设立,制定: The government instituted a consumer protection agency.政府设立了一个消费者保护机构。
        They instituted a new investigation.他们开始了新的调查。
    """.trimIndent()

    /**
     * 钉的决定（v1.85）：「【词性】+【词义】」是**同一个词性条目的两半**，UI 必须只发一个 chip。
     *
     * 用户原话：「那个词性和词义分开来，然后用不同颜色，让我以为是不同的两个内容，没想到它们是同一个内容。」
     * 根因：parse 把每个 `【标签】` 切成独立 Block，UI 按块发 chip，词义=薄荷、词性=纸白 → 视觉断成两块。
     * 修法：parse 阶段把紧邻的这一对合并成一块，由「词义」当 chip，词性文字作为正文首行接在前面。
     * 断言钉的是**块数从 5 降到 3**、只留一个 chip、且两半的内容都在。
     */
    @Test fun parseMergesAdjacentPosAndSenseIntoOneBlock() {
        val bs = KaoyanBack.parse(institute)
        // 音标行 + 合并后的义项块 ×2 —— 不再有独立的「词性」块
        assertEquals("块数：音标 + 两个合并块", 3, bs.size)
        assertEquals("标签序列不该再出现「词性」；多个词义块带序号（v1.85.9）", listOf("", "词义 1", "词义 2"), bs.map { it.tag })

        // 第一块：词性文字作为正文首行，词义正文紧跟其后，内容都在
        val b0 = bs[1].text
        assertTrue("词性文字应作为合并块的首行", b0.startsWith("❶n."))
        assertTrue("词义正文跟在词性后面", b0.contains("学会,协会,学院,研究院(u)"))
        assertTrue("例句不能因为合并而丢失", b0.contains("Many universities have institutes of education."))
        assertTrue("译文不能丢失", b0.contains("许多大学都设有教育学院。"))

        // 第二块同理（❷ + ① 那条），无标签续行仍并进来
        val b1 = bs[2].text
        assertTrue("第二块也以词性起头", b1.startsWith("❷ vt."))
        assertTrue("第二块的义项在", b1.contains("①建立,设立,制定"))
        assertTrue("无标签续行仍并入同一块", b1.contains("They instituted a new investigation."))
    }

    /**
     * 合并**只在「词性 + 词义」这一对上生效**：其他标签组合在书里本来就是并列的独立条目
     * （助记 / 词组 / 辨析…），合并没有语义依据。这条用例钉住「别顺手多合」。
     */
    @Test fun parseDoesNotMergeOtherTagPairs() {
        val bs = KaoyanBack.parse("【词性】❶n.\n【助记】词根 inst-\n【词义】学会,协会")
        // 词性只跟「紧随其后的」词义配对；中间的助记块挡住了 → 词性块保持独立
        assertEquals(listOf("词性", "助记", "词义"), bs.map { it.tag })
        assertEquals("❶n.", bs[0].text)
    }

    /** 词性块排到末尾（后面没有词义跟随时）不该被凭空吞掉 */
    @Test fun parseKeepsLonePosBlockWhenNoSenseFollows() {
        val bs = KaoyanBack.parse("【词义】公平\n【词性】❷ vt.")
        assertEquals(listOf("词义", "词性"), bs.map { it.tag })
        assertEquals("❷ vt.", bs[1].text)
    }

    /** 合并只改「谁带着谁」，正文字符一个都不能少：合并前后正文拼起来应当一致 */
    @Test fun posSenseMergeKeepsEveryCharacter() {
        val bs = KaoyanBack.parse(institute)
        val all = bs.joinToString("") { it.text }
        for (must in listOf("❶n.", "❷ vt.", "学会,协会", "研究院(u)", "建立,设立", "他们开始了新的调查。")) {
            assertTrue("合并后不许丢内容：$must", all.contains(must))
        }
    }

    /** 合并不得影响真题块的末尾排序与折叠（【真题例句】仍然排最后） */
    @Test fun posSenseMergeDoesNotDisturbExamOrdering() {
        val bs = KaoyanBack.parse("【词性】❶n.\n【词义】学会,协会\n【真题例句】Some past exam sentence.")
        assertEquals(listOf("词义", "真题例句"), bs.map { it.tag })
        assertTrue("真题块仍排最后", KaoyanBack.isExamTag(bs.last().tag))
    }

    /** 词性行（合并块的首行）由 [KaoyanBack.isPosLine] 识别 —— UI 据此加粗 */
    @Test fun isPosLineRecognizesPosRowsOnly() {
        assertTrue("❶n.", KaoyanBack.isPosLine("❶n."))
        assertTrue("带空白的记号行", KaoyanBack.isPosLine("  ❷ vt.  "))
        assertTrue("纯词性行", KaoyanBack.isPosLine("vt."))
        assertTrue("复合词性", KaoyanBack.isPosLine("adj./n."))
        assertFalse("义项正文不是词性行", KaoyanBack.isPosLine("学会,协会,学院"))
        assertFalse("英文例句不是词性行", KaoyanBack.isPosLine("Many universities have institutes of education."))
        assertFalse("空行", KaoyanBack.isPosLine("   "))
    }

    // ---------------- v1.85.13 排版审计：核对旧报告的两处「标签解析 / 中英分句」误判 ----------------

    /**
     * 旧报告「『答案B。』拆行」复核为真：R3b（汉字 → ASCII）会把紧贴汉字的**单个字母**当成英文词切掉，
     * `答案B。译文…` 被读成 `答案` ⏎ `B。译文…`。判据：单个字母/纯数字紧跟汉字或中文标点 = 嵌在汉语里的
     * 记号，不算中英交界。R4 仍保证断点后不是禁则标点。
     */
    @Test fun layoutKeepsInlineLatinLetterInsideChineseSentence() {
        val src = "答案B。译文：这位老太太使我想起已故的母亲。"
        val ls = KaoyanBack.layout(src)
        assertEquals("内嵌选项字母不许断行", 1, ls.size)
        assertEquals("字符不增不减", src, ls.joinToString("") { it.text })
    }

    /** 同类误判：内嵌**纯数字**（第3页 / 20世纪 / 5减去9）也不该被当作中英交界拆开。 */
    @Test fun layoutKeepsInlineNumbersInsideChineseSentence() {
        for (src in listOf(
            "我们在第3页讲到这件事。",
            "这本书讲的是20世纪30年代的大萧条。",
            "维生素C含量很高。",
        )) {
            val ls = KaoyanBack.layout(src)
            assertEquals("内嵌数字/单字母不许断行：<$src>", 1, ls.size)
            assertEquals("字符不增不减：<$src>", src, ls.joinToString("") { it.text })
        }
    }

    /** 正例：真正的英文词/句子仍要断 —— 新规则不能把英文也一起吞掉。 */
    @Test fun layoutStillBreaksRealEnglishFromChinese() {
        // 汉字 + 多字母英文词 → 断
        assertEquals(2, KaoyanBack.layout("人人都应当受到公平的待遇。The courts ensure justice.").size)
        // 单个字母的打头**但后面还跟着别的英文词**（英文句首 A / I）→ 仍断
        val article = KaoyanBack.layout("法庭确保公正。A judge must be fair.")
        assertEquals("英文句首的 A 后面跟着别的词，仍要断", 2, article.size)
        assertTrue(article[1].text.startsWith("A judge"))
    }

    /**
     * 旧报告未提、但真机截图暴露的一处「中英分句」缺口：源里英文例句与中文译文之间常夹一个空格
     * （`…structured. 这篇文章…`），旧 R3a 要求英文侧紧贴汉字，于是同一条背面里「有时分行、有时不分行」。
     * 现在「英文句末标点（可隔空格）→ 汉字」也断，英文例句与译文各占一行。
     */
    @Test fun layoutBreaksEnglishSentenceFromChineseAfterSpace() {
        val src = "系统安排,精心组织,使形成体系: The article was intelligently structured. 这篇文章布局巧妙。"
        val ls = KaoyanBack.layout(src)
        assertEquals("英文例句与译文各占一行（含空格）", 2, ls.size)
        assertTrue("英文例句整体在上一行：<${ls[0].text}>", ls[0].text.trimEnd().endsWith("structured."))
        assertTrue("中文译文另起一行：<${ls[1].text}>", ls[1].text.trimStart().startsWith("这篇"))
        assertEquals("字符不增不减", src, ls.joinToString("") { it.text })
    }

    /**
     * R3a 收紧（v1.85.14）：`n. 公正` / `adj. 公正的` / `vt. 放弃` 这类**词性缩写 + 释义**之间不许断行 ——
     * 旧 R3a 把词性缩写的句点当成英文例句的句末，隔着空格也要断，2000+ 处「词性与释义」会被拆开。
     * 判据**复用**已有词性识别（[KaoyanBack.isPosLine] 同源的 `POS_ONLY`），不逐条硬列数据。
     */
    @Test fun layoutKeepsPosAbbrevAndGlossOnOneLine() {
        for (src in listOf("n. 公正", "adj. 公正的,合理的", "vt. 放弃,抛弃", "adv. 迅速地", "v./n. 记录")) {
            val ls = KaoyanBack.layout(src)
            assertEquals("词性缩写 + 释义不许拆开：<$src>", 1, ls.size)
            assertEquals("字符不增不减：<$src>", src, ls.joinToString("") { it.text })
        }
    }

    /** 反例（回归防线）：真英文例句的句末标点 + 空格 + 汉字仍要断，收紧不能把英文例句一起吞掉。 */
    @Test fun layoutStillBreaksRealSentenceAfterSpace() {
        // `structured`（10 字母）不在词性缩写口径内 → 仍断
        val a = KaoyanBack.layout("系统安排: The article was intelligently structured. 这篇文章布局巧妙。")
        assertEquals("完整英文句子 structured. 仍分行", 2, a.size)
        assertTrue("英文句整体在上一行：<${a[0].text}>", a[0].text.trimEnd().endsWith("structured."))
        assertTrue("译文另起一行：<${a[1].text}>", a[1].text.trimStart().startsWith("这篇"))
        // 大写开头的真英文词（Gump）不能被截成小写尾巴（ump）而误判成缩写 → 仍断
        val b = KaoyanBack.layout("Forrest Gump. 汤姆·汉克斯")
        assertEquals(2, b.size)
        assertTrue("首行应以整个 Gump. 收尾：<${b[0].text}>", b[0].text.trimEnd().endsWith("Gump."))
        assertEquals("Forrest Gump. 汤姆·汉克斯", b.joinToString("") { it.text })
    }

    /**
     * 反例（v1.85.14 收紧）：恰好 1~5 个字母的**普通英文句末词**不再被当成词性缩写 —— 旧口径
     * 复用 `POS_ONLY`（`[a-z]{1,5}\.`）会把 `sun.` / `works.` / `grows.` 也当成缩写，吞掉真例句的句末断行。
     * 现在只认 KaoyanBack 内 POS_ABBREVS 里的已知词性/语法缩写，这些词不在表内 → 仍作文界，英文例句与译文各占一行。
     */
    @Test fun layoutStillBreaksShortEnglishSentenceEndFromChinese() {
        val cases = mapOf(
            "The sun. 太阳升起来了。" to "sun.",
            "The plan works. 这个方案奏效了。" to "works.",
            "The idea grows. 这个想法在发展。" to "grows.",
        )
        for ((src, tail) in cases) {
            val ls = KaoyanBack.layout(src)
            assertEquals("短英文句末必须仍作文界：<$src>", 2, ls.size)
            assertTrue("英文句整体在上一行：<${ls[0].text}>", ls[0].text.trimEnd().endsWith(tail))
            assertTrue("译文另起一行：<${ls[1].text}>", ls[1].text.trimStart().first().let { it >= '\u4e00' && it <= '\u9fff' })
            assertEquals("字符不增不减：<$src>", src, ls.joinToString("") { it.text })
        }
    }

    /** 正例：表内的常见词性 / 语法缩写（含 num / art / aux / pl / sing 等）仍与释义同行，不被切开。 */
    @Test fun layoutKeepsKnownPosAbbrevsWithGloss() {
        for (src in listOf(
            "pron. 代词", "prep. 介词", "conj. 连词", "num. 数字", "art. 冠词",
            "aux. 助动词", "pl. 复数", "sing. 单数", "interj. 感叹词", "vi. 不及物动词",
        )) {
            assertEquals("已知词性缩写 + 释义不许拆开：<$src>", 1, KaoyanBack.layout(src).size)
            assertEquals("字符不增不减：<$src>", src, KaoyanBack.layout(src).joinToString("") { it.text })
        }
    }

    /**
     * 多位数字「前半」回归（v1.85.14）：内嵌记号（纯数字 / 单个字母）在**两个方向**都不拆，且判定取整段 alnum 的
     * 起点 —— `第123页` 的 `123` 要整体识别，不能因为只看紧邻汉字的那一位（`3`）而漏掉前半（`1`）。
     */
    @Test fun layoutKeepsMultiDigitInlineNumbersWhole() {
        for (src in listOf(
            "我们在第123页讲到这件事。",
            "这本书讲的是20世纪30年代的大萧条。",
            "全书一共24章，共365页。",
        )) {
            val ls = KaoyanBack.layout(src)
            assertEquals("多位数字整体不拆：<$src>", 1, ls.size)
            assertEquals("字符不增不减：<$src>", src, ls.joinToString("") { it.text })
        }
    }

    /** 反例（回归防线）：英文不以句末标点结尾时，「短语 + 释义」即便隔着空格也不许被拆开。 */
    @Test fun layoutKeepsPhraseAndGlossTogetherAcrossSpace() {
        assertEquals("短语 + 释义仍是一行", 1, KaoyanBack.layout("① in justice to 为了对······公平起见").size)
    }

    /**
     * 旧报告「『辨析』行首冒号」复核为真：`辨析: ment, hire` 这类**裸标签 + 紧贴冒号**的写法，
     * 旧 [KaoyanBack.parse] 把分隔冒号留在了正文里 → 标签 chip 下面第一行是个孤零零的冒号。
     * 冒号在识别标签时就是分隔符，解析时就该吃掉。
     */
    @Test fun bareLabelConsumesSeparatorColon() {
        val bs = KaoyanBack.parse("辨析: ment, hire\n出租房屋用 rent。")
        assertEquals(1, bs.size)
        assertEquals("辨析", bs[0].tag)
        assertTrue("正文不该以冒号开头：<${bs[0].text}>",
            !bs[0].text.startsWith(":") && !bs[0].text.startsWith("："))
        assertTrue("正文内容保留", bs[0].text.startsWith("ment, hire"))
        val first = KaoyanBack.layout(bs[0].text).first().text.trim()
        assertTrue("渲染首行不该是孤零零的冒号：<$first>",
            !first.startsWith(":") && !first.startsWith("："))
    }

    /** 显示宽度：汉字/全角=2、其余=1（与 KaoyanBack 内部口径一致） */
    private fun dispW(s: String): Int = s.fold(0) { acc, c ->
        acc + if ((c >= '\u4e00' && c <= '\u9fff') || (c >= '\uFF01' && c <= '\uFF60')) 2 else 1
    }
}
