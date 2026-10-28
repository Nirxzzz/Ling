package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaoyanBack.layout] 的独立验收用例。
 *
 * 纪律：切点只由下标决定，**字符不增不减**，纯函数且幂等。本文件的五段样本全部自撰，
 * 不含任何第三方词书原文。
 *
 * 钉死五个契约：
 *  1. 双向断行：中文在前、英文紧跟要断；`平整,齐整` 这种中文+ASCII逗号+中文不许断。
 *  2. 锚点跳标点：`…Lane.)穿过` 这种「英文尾 + ASCII 标点 + 汉字」要能断。
 *  3. 悬挂缩进归属：带 ①② 的正文，① 项的续行 indent == 1；无序号段落每行 indent == 0。
 *  4. 内嵌【】不撕：`详见【注】` 不断；`【词义】…` 断在它前面。
 *  5. 禁则 + 软断点：断点后不得以收尾标点开头；单行长度有上限（设计 56，本文件放宽到 ≤ 70）。
 */
class WrapLayoutTest {

    // ---------------- 五段风格迥异的自撰正文 ----------------

    /** 样本 1：纯中文 + 中文标点 + 圈号 ①②③ */
    private val sampleCircled =
        "【词义】①平整,齐整②有序,规整③整齐,端正"

    /** 样本 2：中英混排（英文例句与中文译文挤在一行）+ ①② */
    private val sampleMixed =
        "① 平整,齐整(u): The path along the river stays level all winter.这条路沿着河，整个冬天都保持平整。" +
            "②提灯(c): She carried a small lantern through the orchard.她提着一盏小灯穿过果园。"

    /** 样本 3：多标签块（音标行 / 助记 / 词组），自带换行 */
    private val sampleTags =
        "['læntən] n.\n【助记】 lant(词根)+ ern(名词后缀)→提灯\n" +
            "【词组】① keep the lantern lit 让灯一直亮着"

    /** 样本 4：锚点跳标点（英文尾 + ASCII 标点 + 汉字）+ 书名号 */
    private val sampleAnchorJump =
        "《果园手记》(Orchard Lane.)村里人至今还留着那面旧砖墙。"

    /** 样本 5：换页残句 `·` + 中英混排 */
    private val sampleFragment =
        "· 由于缺人手,这户人家放弃了那片果园。The family gave up the orchard that year."

    private val samples = listOf(
        sampleCircled, sampleMixed, sampleTags, sampleAnchorJump, sampleFragment,
    )

    /** ① 项有续行的段落（契约 3 正例） */
    private val sensesWithCircled =
        "① 平整,齐整(u): The path along the river stays level all winter.这条路沿着河，整个冬天都保持平整。" +
            "②提灯(c): She carried a small lantern through the orchard.她提着一盏小灯穿过果园。"

    /** 200+ 字、没有任何标点的纯中文长句（契约 5 软断点） */
    private val longUnpunctuated =
        "花坛边缘的砖块应当码放得平整而有序这是最基本的做法".repeat(8)

    /** 标点密集的中文段落（契约 5 禁则） */
    private val punctuatedProse =
        "在样例阅读里，长句的切分往往决定了理解的成败；一个标点处理不当，" +
            "整句话的逻辑就会崩塌，甚至连词性判断都会出错。因此，我们必须在句号、逗号、" +
            "顿号、分号、冒号、叹号、问号以及各种右括号之后，谨慎地寻找可以断行的位置，" +
            "既不破坏原文，也不割裂语义，让每一行都恰好容纳一个可读的意群。"

    /** 禁则：这些收尾标点不许出现在「断点后」的行首 */
    private val forbiddenLead = "。，、；：！？）】」』…"

    // ---------------- 工具 ----------------

    private fun lines(text: String): List<KaoyanBack.Line> = KaoyanBack.layout(text)

    private fun rejoin(ls: List<KaoyanBack.Line>): String = ls.joinToString("") { it.text }

    private fun assertInvariant(src: String) {
        val ls = lines(src)
        assertEquals("拼回必须一字不差（不增不删）：$src", src, rejoin(ls))
        assertEquals("字符总数必须一致：$src", src.length, ls.sumOf { it.text.length })
    }

    // ---------------- 不变量 1：不增删任何字符 ----------------

    @Test fun layoutKeepsEveryCharacterForFiveRealBodies() {
        assertEquals("样本数固定为 5 段", 5, samples.size)
        for (src in samples) {
            val ls = lines(src)
            assertEquals("拼回必须一字不差：$src", src, rejoin(ls))
            assertEquals("字符总数必须一致：$src", src.length, ls.sumOf { it.text.length })
            assertTrue("输出不能为空", ls.isNotEmpty())
        }
    }

    @Test fun layoutKeepsInvariantOnBlankAndSingleLineInputs() {
        for (src in listOf("", "   ", "\n\n", "中华人民共和国万岁")) {
            assertInvariant(src)
        }
    }

    // ---------------- 不变量 2：幂等 ----------------

    @Test fun layoutIsIdempotent() {
        for (src in samples) {
            val once = lines(src)
            assertEquals("同一输入两次调用结果必须相同：$src", once, lines(src))
            assertEquals("对拼回文本再 layout 结果不变：$src", once, lines(rejoin(once)))
        }
    }

    // ---------------- 契约 1：双向断行 ----------------

    @Test fun layoutBreaksChineseFollowedByEnglish() {
        val src = "这就是平整level means even."
        val ls = lines(src)
        assertTrue("中文在前、英文紧跟必须断成多行：$ls", ls.size >= 2)
        assertTrue("断点应落在中→英交界处（英文另起一行）", ls.any { it.text.trimStart().startsWith("level") })
        assertTrue("中文那半要完整保留", ls.any { it.text.contains("这就是平整") })
        assertEquals(src, rejoin(ls))
    }

    @Test fun layoutKeepsChineseCommaChineseUnbroken() {
        // 反例：中文 + 中文标点（ASCII 逗号）+ 中文 —— 不许断
        for (src in listOf("平整,齐整", "花坛边缘的平整,齐整与有序")) {
            assertEquals("中文+中文标点+中文一律不许断：$src", 1, lines(src).size)
        }
    }

    // ---------------- 契约 2：锚点跳标点 ----------------

    @Test fun layoutBreaksAtEnglishTailThenAsciiPunctThenHan() {
        val src = "《果园手记》(Orchard Lane.)村里人至今还留着那面旧砖墙。"
        val ls = lines(src)
        assertTrue("英文尾 + ASCII 标点 + 汉字必须能断：$ls", ls.size >= 2)
        assertTrue("断点应落在 .) 之后", ls.any { it.text.trimEnd().endsWith("Lane.)") })
        assertTrue("汉字那半另起一行", ls.any { it.text.trimStart().startsWith("村里人") })
        assertEquals(src, rejoin(ls))
    }

    @Test fun layoutKeepsLatinAsciiPunctLatinUnbroken() {
        // 反例：英文尾 + ASCII 标点 + 英文（后面不是汉字）—— 不必断
        val src = "Orchard Lane.The Brickside"
        assertEquals("无汉字交界时不该断：$src", 1, lines(src).size)
    }

    // ---------------- 契约 3：悬挂缩进归属 ----------------

    @Test fun layoutHangsContinuationLinesUnderCircledItem() {
        val ls = lines(sensesWithCircled)
        val i1 = ls.indexOfFirst { it.text.trimStart().startsWith("①") }
        val i2 = ls.indexOfFirst { it.text.trimStart().startsWith("②") }
        assertTrue("样本里必须能找到 ①②", i1 >= 0 && i2 > i1)
        assertTrue("① 与 ② 之间必须有续行，否则用例没有意义", i2 - i1 >= 2)
        assertEquals("序号行本身顶格", 0, ls[i1].indent)
        for (k in (i1 + 1) until i2) {
            assertEquals("① 项的续行应悬挂缩进 indent=1", 1, ls[k].indent)
        }
        for (k in (i2 + 1) until ls.size) {
            assertEquals("② 项的续行也应悬挂缩进", 1, ls[k].indent)
        }
    }

    @Test fun layoutGivesPlainParagraphNoIndent() {
        // 反例：不带序号的普通段落，每行都顶格
        val ls = lines("果园是社会生活的最后防线The orchard keeps the village together.")
        assertTrue("中英交界处应断成多行", ls.size >= 2)
        for (l in ls) assertEquals("无序号段落每行 indent 都应为 0", 0, l.indent)
    }

    // ---------------- 契约 4：内嵌【】不撕，标签块才断 ----------------

    @Test fun layoutKeepsInlineBracketUnbroken() {
        assertEquals("内嵌【注】不许断行", 1, lines("具体做法详见【注】第二节的说明").size)
    }

    @Test fun layoutBreaksBeforeBlockTag() {
        val src = "前文说明【词义】平整,齐整"
        val ls = lines(src)
        assertTrue("【词义】这种标签块要断在它前面：$ls", ls.size >= 2)
        assertTrue("【词义】应另起一行", ls.any { it.text.trimStart().startsWith("【词义】") })
        assertEquals(src, rejoin(ls))
    }

    // ---------------- 契约 5：禁则 + 软断点 ----------------

    @Test fun layoutNeverStartsALineWithClosingPunctuation() {
        val ls = lines(punctuatedProse)
        assertTrue("标点密集段落应被切成多行，否则禁则检查没意义", ls.size >= 2)
        for (l in ls) {
            val c = l.text.trimStart().firstOrNull()
            assertTrue(
                "断点后不许以收尾标点开头，实得「$c」：行=$l",
                c == null || c !in forbiddenLead,
            )
        }
    }

    @Test fun layoutWrapsLongUnpunctuatedChineseWithinLimit() {
        val src = longUnpunctuated
        assertTrue("样本要够长（>200 字）", src.length > 200)
        val ls = lines(src)
        assertTrue("200+ 字无标点长句必须断成多行", ls.size > 1)
        val longest = ls.maxOf { it.text.length }
        assertTrue("单行长度不得超过上限（放宽阈值 70），实得 $longest", longest <= 70)
        assertEquals(src, rejoin(ls))
    }

    // ---------------- 回归防线：不许切开【标签】 ----------------

    @Test fun layoutNeverCutsInsideBracketTag() {
        val cases = samples + listOf("例句【2019年样例阅读】", "【词义】① 平整\n② 提灯")
        for (src in cases) {
            for (l in lines(src)) {
                val t = l.text.trimStart()
                if (t.startsWith("【")) {
                    assertTrue("以【开头的行内必须能看到】才算没被切开：$t", l.text.contains("】"))
                }
            }
        }
    }

    @Test fun layoutToleratesUnbalancedBracketAndKeepsChars() {
        // 输入本身就不平衡（缺 】）：不许崩、也不许增删字符
        val src = "【词义平整,齐整"
        val ls = lines(src)
        assertTrue("不平衡输入也要产出结果", ls.isNotEmpty())
        assertEquals(src, rejoin(ls))
    }

    @Test fun layoutNeverStartsWithForbiddenLeadOnMixedSamples() {
        for (src in samples) {
            for (l in lines(src)) {
                val c = l.text.trimStart().firstOrNull()
                assertFalse(
                    "样本 $src 里出现以收尾标点开头的行：$l",
                    c != null && c in forbiddenLead,
                )
            }
        }
    }
}
