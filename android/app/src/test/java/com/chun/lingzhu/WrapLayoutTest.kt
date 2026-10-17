package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaoyanBack.layout] 的**独立验收用例**（TDD 先行的「失败测试」那一半）。
 *
 * 背景：灵珠旧的 `KaoyanBack.wrap` 是靠**往文本里插 `\n`** 来分行的；这次要把 nirx 的
 * 「严格下标对齐切分」纪律搬过来 —— 切点只由下标决定，**字符不增不减**，纯函数且幂等。
 *
 * 被测接口（已冻结）：
 * ```
 * data class Line(val text: String, val indent: Int = 0)   // KaoyanBack 的嵌套类
 * fun layout(text: String): List<Line>
 * ```
 *
 * 本文件钉死下面 5 个短板（每条含正例与反例），外加两条不变量：
 *  1. **双向断行**：中文在前、英文紧跟（`…公平justice…`）也要断；
 *     反例 `公平,公正`（中文 + 中文标点 + 中文）不许断。
 *  2. **锚点跳标点**：`…Gump.)汤姆` 这种「英文尾 + ASCII 标点 + 汉字」要能断。
 *  3. **悬挂缩进归属**：带 ①② 的正文，第 ① 项的**续行** `indent == 1`（不是 0）；
 *     反例：不带序号的普通段落每行 `indent == 0`。
 *  4. **内嵌【】不撕**：`详见【注】` 这种内嵌方括号不许断；`【词义】…` 这种标签块要断在它前面。
 *  5. **禁则 + 软断点**：断点后不得以 `。，、；：！？）】」』…` 开头；单行长度有上限
 *     （设计目标 56，本用例**故意放宽**到 ≤ 70 留余量）。
 *
 * 另钉两条不变量：拼回原文一字不差（`joinToString("") == x`）与幂等；
 * 以及「不许把【标签】内部切开」的回归防线。
 *
 * 说明：只断言**行为**，不锁死具体断点位置；有歧义处一律放宽（见各用例注释）。
 */
class WrapLayoutTest {

    // ---------------- 五段风格迥异的真实正文（红宝书背面） ----------------

    /** 样本 1：纯中文 + 中文标点 + 圈号 ①②③ */
    private val sampleCircled =
        "【词义】①公平,公正,合理②审判,司法制度,法律制裁③法官,审判员,司法人员"

    /** 样本 2：中英混排（英文例句与中文译文挤在一行）+ ①② */
    private val sampleMixed =
        "① 公平,公正,合理(u): All men should be treated with justice.人人都应当受到公平的待遇。" +
            "②审判,司法(制度): The courts ensure that justice is achieved.法庭确保审判公正。"

    /** 样本 3：多标签块（音标行 / 助记 / 词组），自带换行 */
    private val sampleTags =
        "['dʒʌstɪs] n.\n【助记】 just(公正的)+ ice(名词后缀)→公正\n" +
            "【词组】① in justice to 为了对······公平起见"

    /** 样本 4：锚点跳标点（英文尾 + ASCII 标点 + 汉字）+ 书名号 */
    private val sampleAnchorJump =
        "《阿甘正传》(Forrest Gump.)汤姆·汉克斯饰演阿甘,该片于1994年上映。"

    /** 样本 5：换页残句 `·` + 中英混排 */
    private val sampleFragment =
        "· 由于缺乏资金,这位科学家放弃了他的研究工作。The scientist abandoned his research."

    private val samples = listOf(
        sampleCircled, sampleMixed, sampleTags, sampleAnchorJump, sampleFragment,
    )

    /** ① 项有续行的段落（短板 3 正例） */
    private val sensesWithCircled =
        "① 公平,公正,合理(u): All men should be treated with justice.人人都应当受到公平的待遇。" +
            "②审判,司法(制度): The courts ensure that justice is achieved.法庭确保审判公正。"

    /** 200+ 字、没有任何标点的纯中文长句（短板 5 软断点） */
    private val longUnpunctuated =
        "司法公正是维护社会公平正义的最后一道防线我们必须毫不动摇地坚守住它".repeat(8)

    /** 标点密集的中文段落（短板 5 禁则） */
    private val punctuatedProse =
        "在考研英语阅读中，长难句的切分往往决定了理解的成败；一个标点处理不当，" +
            "整句话的逻辑就会崩塌，甚至连词性判断都会出错。因此，我们必须在句号、逗号、" +
            "顿号、分号、冒号、叹号、问号以及各种右括号之后，谨慎地寻找可以断行的位置，" +
            "既不破坏原文，也不割裂语义，让每一行都恰好容纳一个可读的意群。"

    /** 禁则：这些收尾标点不许出现在「断点后」的行首 */
    private val forbiddenLead = "。，、；：！？）】」』…"

    // ---------------- 工具 ----------------

    /** 统一入口：`Line` 是 KaoyanBack 的嵌套类 */
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
        // 空白/单行同样不能增删字符（不靠 trim 丢字符，也不靠插 \n 补字符）
        for (src in listOf("", "   ", "\n\n", "中华人民共和国万岁")) {
            assertInvariant(src)
        }
    }

    // ---------------- 不变量 2：幂等 ----------------

    @Test fun layoutIsIdempotent() {
        for (src in samples) {
            val once = lines(src)
            assertEquals("同一输入两次调用结果必须相同：$src", once, lines(src))
            // 把拼回的文本再 layout 一次，切点不得漂移
            assertEquals("对拼回文本再 layout 结果不变：$src", once, lines(rejoin(once)))
        }
    }

    // ---------------- 短板 1：双向断行 ----------------

    @Test fun layoutBreaksChineseFollowedByEnglish() {
        val src = "这就是公平justice means fairness."
        val ls = lines(src)
        assertTrue("中文在前、英文紧跟必须断成多行：$ls", ls.size >= 2)
        assertTrue("断点应落在中→英交界处（英文另起一行）", ls.any { it.text.trimStart().startsWith("justice") })
        assertTrue("中文那半要完整保留", ls.any { it.text.contains("这就是公平") })
        assertEquals(src, rejoin(ls))
    }

    @Test fun layoutKeepsChineseCommaChineseUnbroken() {
        // 反例：中文 + 中文标点（ASCII 逗号）+ 中文 —— 不许断
        for (src in listOf("公平,公正", "司法的公平,公正与合理")) {
            assertEquals("中文+中文标点+中文一律不许断：$src", 1, lines(src).size)
        }
    }

    // ---------------- 短板 2：锚点跳标点 ----------------

    @Test fun layoutBreaksAtEnglishTailThenAsciiPunctThenHan() {
        val src = "《阿甘正传》(Forrest Gump.)汤姆·汉克斯饰演阿甘。"
        val ls = lines(src)
        assertTrue("英文尾 + ASCII 标点 + 汉字必须能断：$ls", ls.size >= 2)
        assertTrue("断点应落在 .) 之后", ls.any { it.text.trimEnd().endsWith("Gump.)") })
        assertTrue("汉字那半另起一行", ls.any { it.text.trimStart().startsWith("汤姆") })
        assertEquals(src, rejoin(ls))
    }

    @Test fun layoutKeepsLatinAsciiPunctLatinUnbroken() {
        // 反例：英文尾 + ASCII 标点 + 英文（后面不是汉字）—— 不必断
        val src = "Forrest Gump.Tom Hanks"
        assertEquals("无汉字交界时不该断：$src", 1, lines(src).size)
    }

    // ---------------- 短板 3：悬挂缩进归属 ----------------

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
        val ls = lines("司法是社会公平正义的最后防线Law is the last defense of fairness.")
        assertTrue("中英交界处应断成多行", ls.size >= 2)
        for (l in ls) assertEquals("无序号段落每行 indent 都应为 0", 0, l.indent)
    }

    // ---------------- 短板 4：内嵌【】不撕，标签块才断 ----------------

    @Test fun layoutKeepsInlineBracketUnbroken() {
        // 反例：内嵌方括号（非块标签）不许断行
        assertEquals("内嵌【注】不许断行", 1, lines("具体规则详见【注】第二节的说明").size)
    }

    @Test fun layoutBreaksBeforeBlockTag() {
        val src = "前文说明【词义】公平,公正"
        val ls = lines(src)
        assertTrue("【词义】这种标签块要断在它前面：$ls", ls.size >= 2)
        assertTrue("【词义】应另起一行", ls.any { it.text.trimStart().startsWith("【词义】") })
        assertEquals(src, rejoin(ls))
    }

    // ---------------- 短板 5：禁则 + 软断点 ----------------

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
        // 没有任何标点、也没有空格时，只能靠长度软断：必须断、且不许超长
        assertTrue("200+ 字无标点长句必须断成多行", ls.size > 1)
        val longest = ls.maxOf { it.text.length }
        // 目标上限 56，这里故意留 14 字余量放到 70（不锁死具体断点）
        assertTrue("单行长度不得超过上限（放宽阈值 70），实得 $longest", longest <= 70)
        assertEquals(src, rejoin(ls))
    }

    // ---------------- 回归防线：不许切开【标签】 ----------------

    @Test fun layoutNeverCutsInsideBracketTag() {
        val cases = samples + listOf("例句【2019年考研阅读】", "【词义】① 公平\n② 审判")
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
        // 输入本身就不平衡（缺 】）：不许崩、也不许增删字符（此例不受「标签不切开」约束）
        val src = "【词义公平,公正"
        val ls = lines(src)
        assertTrue("不平衡输入也要产出结果", ls.isNotEmpty())
        assertEquals(src, rejoin(ls))
    }
}
