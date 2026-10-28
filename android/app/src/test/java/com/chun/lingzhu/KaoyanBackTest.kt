package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaoyanBack]：书页式词库背面的解析与排版。纯逻辑，不碰 UI。
 *
 * 本文件随开源副本提供，样本全部自撰（见各 `private val` 注释），不包含任何第三方词书的原文。
 * 覆盖的契约与产品一致：标签分块、序号归并、残句保留、真题后置、wrap 断行、layout 不增删字符。
 */
class KaoyanBackTest {

    // ---------------- isRich ----------------

    @Test fun isRichTrueForBracketedOrPhoneticBack() {
        assertTrue("含【标签】就是富卡", KaoyanBack.isRich("【词义】平整,齐整"))
        assertTrue("首行音标行（[开头）", KaoyanBack.isRich("['læntən] n.\n【词义】提灯"))
        assertTrue("首行只有词性缩写也算音标行", KaoyanBack.isRich("vt."))
        assertTrue("前后有空白也能识别", KaoyanBack.isRich("  ['brɪk] v.\n砌砖"))
    }

    @Test fun isRichFalseForPlainBack() {
        assertFalse("空输入", KaoyanBack.isRich(""))
        assertFalse("全空白", KaoyanBack.isRich("   \n  \n"))
        assertFalse("词性 + 释义的普通一行", KaoyanBack.isRich("v. 堆放；码放"))
        assertFalse("纯中文释义", KaoyanBack.isRich("堆放；码放"))
    }

    // ---------------- isExamTag ----------------

    @Test fun isExamTagTrueForAnyTagContainingZhenti() {
        assertTrue(KaoyanBack.isExamTag("真题例句"))
        assertTrue(KaoyanBack.isExamTag("真题"))
        assertTrue(KaoyanBack.isExamTag("典型真题"))
    }

    @Test fun isExamTagFalseForOtherOrEmptyTags() {
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
        val bs = KaoyanBack.parse("['læntən] n.\n【词义】提灯")
        assertEquals(2, bs.size)
        assertEquals("", bs[0].tag)
        assertEquals("['læntən] n.", bs[0].text)
        assertFalse(bs[0].frag)
        assertEquals("词义", bs[1].tag)
        assertEquals("提灯", bs[1].text)
    }

    @Test fun bracketedTagSplitsTagAndBody() {
        val bs = KaoyanBack.parse("【助记】lant(词根)+ ern(名词后缀)→提灯")
        assertEquals(1, bs.size)
        assertEquals("助记", bs[0].tag)
        assertEquals("lant(词根)+ ern(名词后缀)→提灯", bs[0].text)
    }

    @Test fun emptyTagBodyIsFilledByFollowingUntaggedLines() {
        // 【词义】后面没字 → 紧接着的无标签行都归它
        val bs = KaoyanBack.parse("【词义】\n平整,齐整\n有秩序的")
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("平整,齐整\n有秩序的", bs[0].text)
    }

    @Test fun bareLabelWithoutBrackets() {
        val bs = KaoyanBack.parse("【词义】平整\n辨析 tidy, neat, orderly, trim")
        assertEquals(2, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("辨析", bs[1].tag)
        assertEquals("tidy, neat, orderly, trim", bs[1].text)
    }

    @Test fun circledLinesMergeIntoPreviousBlockKeepingNumbers() {
        val bs = KaoyanBack.parse("【词义】① 平整\n② 有序\n③ 整洁")
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        assertEquals("① 平整\n② 有序\n③ 整洁", bs[0].text)
    }

    @Test fun circledLineAfterBracketedBodyMergesToo() {
        val bs = KaoyanBack.parse("【词组】① keep the lantern lit 让灯一直亮着\n②lantern slide 幻灯片")
        assertEquals(1, bs.size)
        assertEquals("词组", bs[0].tag)
        // ② 那条的英文短语与中文释义再被 wrap 拆开（英文→中文交界）
        assertEquals("① keep the lantern lit 让灯一直亮着\n②lantern slide\n幻灯片", bs[0].text)
    }

    @Test fun fragLineBecomesOwnBlockAndKeepsDot() {
        val bs = KaoyanBack.parse("【词义】平整\n· 那条河在冬夜里一直没有结冰。")
        assertEquals(2, bs.size)
        assertEquals("", bs[1].tag)
        assertTrue(bs[1].frag)
        assertTrue("行首的 · 要留着", bs[1].text.startsWith("·"))
        assertEquals("· 那条河在冬夜里一直没有结冰。", bs[1].text)
    }

    @Test fun untaggedLineMergesIntoPreviousOrStandsAlone() {
        // 前面没块 → 自成一块
        val solo = KaoyanBack.parse("随手记下的一行")
        assertEquals(1, solo.size)
        assertEquals("", solo[0].tag)
        assertEquals("随手记下的一行", solo[0].text)
        // 前面有块 → 并进去
        val merged = KaoyanBack.parse("【助记】x\n继续一行")
        assertEquals(1, merged.size)
        assertEquals("x\n继续一行", merged[0].text)
    }

    @Test fun examBlocksMovedToEndKeepingRelativeOrder() {
        val back = """
            【词义】① 平整,齐整
            【真题例句】The morning market opened early...
            【词组】① keep the lantern lit 让灯一直亮着
            【真题】Second sample sentence.
        """.trimIndent()
        val bs = KaoyanBack.parse(back)
        assertEquals("块数", 4, bs.size)

        // 真题块一律排到最后，非真题块保持原相对顺序在前
        assertEquals(listOf("词义", "词组", "真题例句", "真题"), bs.map { it.tag })
        assertTrue("末尾两块都必须是真题块", bs.takeLast(2).all { KaoyanBack.isExamTag(it.tag) })
        assertEquals(listOf("词义", "词组"), bs.filter { !KaoyanBack.isExamTag(it.tag) }.map { it.tag })
        assertEquals(listOf("真题例句", "真题"), bs.filter { KaoyanBack.isExamTag(it.tag) }.map { it.tag })

        // 非真题块的正文没被改（除 wrap 分行外一字不动）
        assertEquals("① 平整,齐整", bs[0].text)
        assertEquals("① keep the lantern lit 让灯一直亮着", bs[1].text)
        // 真题块正文同样一字不动，只是位置变了
        assertEquals("The morning market opened early...", bs[2].text)
        assertEquals("Second sample sentence.", bs[3].text)
    }

    // ---------------- wrap：把挤在一行的正文拆成可读单位 ----------------

    /** 自撰样本甲：两个义项，中英混排在同一个行内 */
    private val lanternSenses =
        "【词义】① 平整,齐整(u): The path along the river stays level all winter.这条路沿着河，整个冬天都保持平整。" +
            "② 提灯(c): She carried a small lantern through the orchard.她提着一盏小灯穿过果园。"
    /** 自撰样本乙：另一组两个义项，用于验证拆行规则不只对单一文本成立 */
    private val brickSenses =
        "【词义】①砖块: They stacked the bricks beside the wall.他们把砖码在墙边。" +
            "②堆叠,码放(v): Please brick the edge of the bed neatly.请把花坛边缘码放整齐。"

    private fun isHan(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    @Test fun wrapSplitsSensesAndEnglishFromChinese() {
        val bs = KaoyanBack.parse(lanternSenses)
        assertEquals(1, bs.size)
        assertEquals("词义", bs[0].tag)
        val lines = bs[0].text.split("\n")
        assertEquals("两个义项 + 各自英文/中文 = 4 行", 4, lines.size)

        // ① 义项：英文例句一整行，句子里不夹汉字
        assertTrue(lines[0].startsWith("①"))
        assertTrue(lines[0].endsWith("The path along the river stays level all winter."))
        assertTrue("英文例句那一段不该夹汉字", lines[0].substringAfter("level").none { !isHan(it) && it.code > 127 })
        // 中文译文单独一行、且不含 ASCII 字母
        assertEquals("这条路沿着河，整个冬天都保持平整。", lines[1])
        assertFalse("译文行不该有 ASCII 字母", isHan(lines[1].first()))

        // ② 义项同样拆开
        assertTrue(lines[2].startsWith("②"))
        assertTrue(lines[2].endsWith("She carried a small lantern through the orchard."))
        assertEquals("她提着一盏小灯穿过果园。", lines[3])
    }

    @Test fun wrapSplitsBrickExample() {
        val lines = KaoyanBack.parse(brickSenses)[0].text.split("\n")
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("①"))
        assertTrue(lines[0].endsWith("They stacked the bricks beside the wall."))
        assertEquals("他们把砖码在墙边。", lines[1])
        assertTrue(lines[2].startsWith("②"))
        assertEquals("请把花坛边缘码放整齐。", lines[3])
    }

    @Test fun wrapBreaksMidLineCircledNumbers() {
        // ①②③ 挤在一行 → 各占一行
        assertEquals("① 甲\n② 乙\n③ 丙", KaoyanBack.parse("【词义】① 甲② 乙③ 丙")[0].text)
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
        assertEquals("例句\n【2019年样例阅读】", KaoyanBack.wrap("例句【2019年样例阅读】"))
    }

    @Test fun wrapDoesNotTouchPlainChineseOrEnglish() {
        assertEquals("中华人民共和国万岁", KaoyanBack.wrap("中华人民共和国万岁"))
        assertEquals("Hello, world! It works.", KaoyanBack.wrap("Hello, world! It works."))
        // 中文里的 ASCII 逗号不算交界（公平,公正 不拆）
        assertEquals("① 平整,齐整,有序(u):", KaoyanBack.wrap("① 平整,齐整,有序(u):"))
    }

    @Test fun wrapIsIdempotent() {
        for (s in listOf(lanternSenses, brickSenses, "【词组】① keep the lantern lit 让灯亮着\n②lantern slide 幻灯片")) {
            val once = KaoyanBack.wrap(KaoyanBack.parse(s).joinToString("\n") { it.text })
            assertEquals("wrap 应当幂等", once, KaoyanBack.wrap(once))
        }
        // parse 也稳：把解析出的块正文再当背面解析一遍，正文一致
        val bs = KaoyanBack.parse(lanternSenses)
        assertEquals(bs[0].text, KaoyanBack.parse(bs[0].text)[0].text)
    }

    @Test fun wrapBlankInputsAreSafe() {
        assertEquals("", KaoyanBack.wrap(""))
        assertEquals("", KaoyanBack.wrap("   "))
        assertEquals("", KaoyanBack.wrap("\n\n  \n"))
        assertTrue("不产出空行", KaoyanBack.wrap("a\n\n\nb").split("\n").none { it.isEmpty() })
    }

    // ---------------- 完整书页式背面（整块结构）----------------

    private val lantern = """
        ['læntən] n.
        【助记】 lant(词根)+ ern(名词后缀)→提灯
        【词义】① 平整,齐整(u): The path along the river stays level all winter.这条路沿着河，整个冬天都保持平整。
        ② 提灯(c): She carried a small lantern through the orchard.她提着一盏小灯穿过果园。
        ③ 灯罩(c): The glass fit the lantern frame snugly.灯罩正正好好嵌进灯架。
        【词组】① keep the lantern lit 让灯一直亮着
        ②lantern slide 幻灯片
        【真题例句】 The morning market opened early...  【2019年样例阅读】
        · 那条河在冬夜里一直没有结冰。
    """.trimIndent()

    @Test fun realPageBackParsesIntoOrderedBlocks() {
        val bs = KaoyanBack.parse(lantern)
        // 音标 / 助记 / 词义 / 词组 / 真题例句 / 残句
        assertEquals(listOf("", "助记", "词义", "词组", "真题例句", ""), bs.map { it.tag })
        assertEquals("['læntən] n.", bs[0].text)
        assertEquals("lant(词根)+ ern(名词后缀)→提灯", bs[1].text)
        assertTrue("词义块保留三个义项", bs[2].text.split("\n").count { it.startsWith("①") || it.startsWith("②") || it.startsWith("③") } == 3)
        assertEquals("真题块排到倒数第二", "真题例句", bs[4].tag)
        assertEquals("残句排在最后一块并把 · 留着", true, bs[5].frag)
        assertTrue(bs[5].text.startsWith("·"))
    }

    @Test fun parseMemoizedIsStable() {
        val a = KaoyanBack.parseMemoized(lantern)
        val b = KaoyanBack.parseMemoized(lantern)
        assertEquals(a, b)
        assertEquals(KaoyanBack.parse(lantern), a)
    }

    // ---------------- 错峰淡入节奏 ----------------

    @Test fun staggerDelayIsMonotonicAndCapped() {
        assertEquals(KaoyanBack.STAGGER_START_MS, KaoyanBack.staggerDelayMs(0))
        assertEquals(
            KaoyanBack.STAGGER_START_MS + KaoyanBack.STAGGER_STEP_MS,
            KaoyanBack.staggerDelayMs(1),
        )
        assertTrue(KaoyanBack.STAGGER_STEP_MS > 0)
        for (i in 1..KaoyanBack.STAGGER_MAX_BLOCKS) {
            assertTrue("第 $i 块应当比上一块晚", KaoyanBack.staggerDelayMs(i) > KaoyanBack.staggerDelayMs(i - 1))
        }
        val cap = KaoyanBack.staggerDelayMs(KaoyanBack.STAGGER_MAX_BLOCKS)
        assertEquals(cap, KaoyanBack.staggerDelayMs(KaoyanBack.STAGGER_MAX_BLOCKS + 1))
        assertEquals(cap, KaoyanBack.staggerDelayMs(999))
        assertEquals(KaoyanBack.STAGGER_START_MS, KaoyanBack.staggerDelayMs(-5))
        assertTrue("错峰总时长应当在一秒内", cap < 1_000L)
    }

    // ---------------- layout：按下标切分，字符不增不减，纯函数且幂等 ----------------

    @Test fun layoutPreservesEveryCharacterAndIsIdempotent() {
        val cases = listOf(
            lanternSenses, brickSenses, lantern,
            "① 平整,齐整②提灯", "详见【注】", "平整,齐整", "【词性】❶n.",
            "这条路沿着河整个冬天都保持平整。She carried a small lantern.",
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
        // 块正文（parse→wrap 过的）：① 项的续行必须 indent=1
        val body = KaoyanBack.parse(lanternSenses)[0].text
        val ls = KaoyanBack.layout(body)
        assertEquals(body, ls.joinToString("") { it.text })
        assertTrue("应有悬挂缩进的续行", ls.any { it.indent == 1 })
        assertTrue("续行就是 ① 项的中文译文", ls.any { it.indent == 1 && it.text.contains("这条路沿着河") })
    }

    // ---- R2 【tag】----

    @Test fun layoutKeepsInlineBracketButBreaksBlockTag() {
        assertEquals("内嵌【注】不许断", 1, KaoyanBack.layout("详见【注】").size)
        val ls = KaoyanBack.layout("前文说明【词义】平整,齐整")
        assertTrue("块标签【词义】要断在它前面", ls.any { it.text.trimStart().startsWith("【词义】") })
        assertEquals("前文说明【词义】平整,齐整", ls.joinToString("") { it.text })
        assertTrue("句末标点后的【】也断", KaoyanBack.layout("平整有序。【注】").size >= 2)
    }

    // ---- R3 中↔英双向断行 + 锚点跳标点 ----

    @Test fun layoutBreaksBothDirectionsBetweenHanAndAscii() {
        val cn2en = KaoyanBack.layout("这条路沿着河整个冬天都保持平整。She carried a small lantern.")
        assertEquals("中文在前、英文紧跟也要断", 2, cn2en.size)
        assertTrue(cn2en[0].text.startsWith("这条路"))
        assertTrue(cn2en[1].text.startsWith("She carried"))
        // 反例：中文 + 中文标点（ASCII 逗号）+ 中文 不许断
        assertEquals(1, KaoyanBack.layout("平整,齐整").size)
        assertEquals(1, KaoyanBack.layout("花坛边缘的平整,齐整与有序").size)
    }

    @Test fun layoutSkipsAsciiPunctWhenFindingAnchor() {
        val ls = KaoyanBack.layout("Orchard Lane.)穿过果园")
        assertTrue("Lane.)穿过 应能断", ls.size >= 2)
        assertTrue("断点落在 .) 之后", ls[0].text.endsWith("Lane.)"))
        assertTrue("汉字另起一行", ls[1].text.startsWith("穿过"))
    }

    // ---- R4 行首禁则 ----

    @Test fun layoutNeverStartsLineWithForbiddenPunctuation() {
        val src = "在样例阅读里，长句的切分往往决定了理解的成败；一个标点处理不当，" +
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
        val src = "花坛边缘的砖块应当码放得平整而有序".repeat(6)   // 无标点、无空格
        val ls = KaoyanBack.layout(src)
        assertTrue("超长无标点行必须断开", ls.size > 1)
        assertTrue("单行不得超上限", ls.maxOf { it.text.length } <= KaoyanBack.MAX_LINE)
        assertEquals(src, ls.joinToString("") { it.text })
    }

    // ---- R6 【…】 内部不切 ----

    @Test fun layoutNeverCutsInsideBracketTag() {
        assertEquals("标签+记号+词性保持一行", 1, KaoyanBack.layout("【词性】❶n.").size)
        for (l in KaoyanBack.layout("例句【2019年样例阅读】")) {
            val t = l.text.trimStart()
            if (t.startsWith("【")) assertTrue("以【开头的行内必须含】", l.text.contains("】"))
        }
        // 不平衡的【：不崩、不丢字符
        assertEquals("【词义平整,齐整", KaoyanBack.layout("【词义平整,齐整").joinToString("") { it.text })
    }

    // ---------------- 英文例句不被 softBreak 从中间切开 ----------------

    /** 自撰：原文 80 字左右 > MAX_LINE，英文例句长 40+ 字 */
    private val orchardSense1 =
        "果园,果树园,果园里的树(u) : Many villages keep a small orchard behind the barn.许多村子都在谷仓后面留一小片果园。"

    @Test fun layoutKeepsEnglishExampleWholeInsteadOfCuttingAtSpace() {
        val ls = KaoyanBack.layout(orchardSense1)
        assertEquals(orchardSense1, ls.joinToString("") { it.text })
        assertTrue(
            "英文例句不能被空格切开：$ls",
            ls.any { it.text.contains("keep a small orchard behind the barn.") },
        )
    }

    @Test fun layoutKeepsEnglishExampleWholeAfterColonGlue() {
        val src = "①提灯: She carried a small lantern through the orchard.她提着一盏小灯穿过果园。"
        val ls = KaoyanBack.layout(src)
        assertEquals(src, ls.joinToString("") { it.text })
        assertTrue("冒号黏连后英文仍成整句", ls.any { it.text.contains("through the orchard.") })
    }

    @Test fun layoutEnglishExampleFixKeepsInvariantsOnRealBodies() {
        for (s in listOf(orchardSense1, lanternSenses, brickSenses)) {
            val ls = KaoyanBack.layout(s)
            assertEquals(s, ls.joinToString("") { it.text })
            assertEquals(ls, KaoyanBack.layout(ls.joinToString("") { it.text }))
        }
    }

    // ---------------- 【词性】+【词义】合并 ----------------

    private val merged = """
        ['brɪk] n.
        【词性】❶n.
        【词义】① 砖块
        【词性】❷vt.
        【词义】② 堆叠,码放
    """.trimIndent()

    @Test fun parseMergesAdjacentPosAndSenseIntoOneBlock() {
        val bs = KaoyanBack.parse(merged)
        // 音标 / 合并后的词义块（词性当首行）
        assertEquals(listOf("", "词义 1", "词义 2"), bs.map { it.tag })
        assertTrue("词性行并入词义块", bs[1].text.startsWith("❶n."))
        assertTrue("第二个词性也并进去", bs[2].text.startsWith("❷vt."))
    }

    @Test fun parseDoesNotMergeOtherTagPairs() {
        val bs = KaoyanBack.parse("【助记】a\n【词组】b")
        assertEquals(listOf("助记", "词组"), bs.map { it.tag })
    }

    @Test fun parseKeepsLonePosBlockWhenNoSenseFollows() {
        val bs = KaoyanBack.parse("【词性】n.")
        assertEquals(1, bs.size)
        assertEquals("词性", bs[0].tag)
    }

    @Test fun posSenseMergeKeepsEveryCharacter() {
        val back = "【词性】❶n.\n【词义】① 砖块"
        val mergedText = KaoyanBack.parse(back).joinToString("") { it.text }
        assertTrue("两个标签都被去掉后，正文一个字不少", mergedText.contains("❶n.") && mergedText.contains("① 砖块"))
        assertEquals(back, KaoyanBack.layout(back).joinToString("") { it.text })
    }

    @Test fun isPosLineRecognizesPosRowsOnly() {
        assertTrue(KaoyanBack.isPosLine("❶n."))
        assertTrue(KaoyanBack.isPosLine("vt."))
        assertTrue(KaoyanBack.isPosLine("adj."))
        assertFalse("释义行不是词性行", KaoyanBack.isPosLine("砖块"))
        assertFalse("长句不是词性行", KaoyanBack.isPosLine("a small brick wall"))
    }

    // ---------------- 内联拉丁字母 / 数字不误断 ----------------

    @Test fun layoutKeepsInlineLatinLetterInsideChineseSentence() {
        val src = "这座楼的编号是 B 座，走廊尽头就是果园。"
        val ls = KaoyanBack.layout(src)
        assertEquals(src, ls.joinToString("") { it.text })
        assertEquals("一句话里夹一个字母不该断", 1, ls.size)
    }

    @Test fun layoutKeepsInlineNumbersInsideChineseSentence() {
        val src = "花坛一共码了 24 块砖，剩下 6 块备用。"
        val ls = KaoyanBack.layout(src)
        assertEquals(src, ls.joinToString("") { it.text })
        assertEquals("一句话里夹数字不该断", 1, ls.size)
    }

    @Test fun layoutStillBreaksRealEnglishFromChinese() {
        val src = "① 砖块: They stacked the bricks beside the wall.他们把砖码在墙边。"
        val ls = KaoyanBack.layout(src)
        assertEquals(src, ls.joinToString("") { it.text })
        assertTrue("英文与中文之间仍要断", ls.size >= 2)
    }
}
