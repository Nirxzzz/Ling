package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.76：这里只锁**消费方行为契约**，不再逐字断言提示词文案 ——
 * 旧测试把「只写 1~2 步」「每步一句话」「不超过 150/400 字」这些实现文案和固定字数钉死，
 * 一改提示词就误报，也没能证明质量。质量由父级用真实模型跑样题 smoke 验证，不是字符串断言。
 *
 * 仍然锁住的契约（这些一坏，面板 / 设置就出问题）：
 *  - 纯小白开关决定走哪套格式（小白独立格式 vs 普通三段式）；
 *  - 三段式标题 `## 答案 / ## 解析 / ## 考点` 与多题标记 `### 第 N 题`（面板排版靠它）；
 *  - 关键词清单开关：开着才带 TERMS_RULE，且固定在输出最后一行；
 *  - directive 覆盖层：压在 persona 之后、TERMS_RULE 之前；空则不加；
 *  - 记忆卡位置；what/why 开关拼装；预算 / 错误识别 / 翻译 / 去标记等纯函数行为。
 */
class PromptsTest {

    // ---------- 纯小白：决定走哪套格式 ----------

    @Test fun noviceSelectsIndependentFormat() {
        val nvExam = Prompts.system(Prompts.MODE_EXAM, "standard", true)
        assertTrue("小白做法走独立格式", nvExam.contains("纯小白") && nvExam.contains("## 一步步做"))
        assertFalse("小白格式不和普通三段式混", nvExam.contains("## 解析"))

        assertFalse(Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("纯小白"))
        assertFalse("通用模式非小白不带小白写法", Prompts.system(Prompts.MODE_GENERAL, "standard", false).contains("纯小白"))
        assertTrue("通用模式开小白要带上", Prompts.system(Prompts.MODE_GENERAL, "standard", true).contains("纯小白"))
    }

    // ---------- 面板排版契约：三段式标题 + 多题标记 ----------

    @Test fun examTitlesAndMultiQuestionStayForPanelRender() {
        for (nv in listOf(false, true)) for (d in Prompts.DETAILS) {
            val p = Prompts.system(Prompts.MODE_EXAM, d, nv)
            assertTrue("$d/novice=$nv 缺 「## 答案」（面板顶部答案行靠它）", p.contains("## 答案"))
            assertTrue("$d/novice=$nv 缺 多题标记（面板靠它区分第几题）", p.contains("### 第 N 题"))
        }
        val plain = Prompts.system(Prompts.MODE_EXAM, "standard", false)
        assertTrue(plain.contains("## 解析") && plain.contains("## 考点"))
        assertTrue("同题小问不拆成多题的约定还在", plain.contains("小问"))
    }

    // ---------- 自定义模式：用户提示词原样进、空则用示例 ----------

    @Test fun customModeUsesUserPromptVerbatim() {
        val p = Prompts.system(Prompts.MODE_CUSTOM, "standard", false, "  把画面里的英文翻成中文  ")
        assertTrue("用户原文照进（只做 trim）", p.contains("把画面里的英文翻成中文\n"))
        assertFalse("用了用户提示就不该再塞内置示例 / 三段式", p.contains(Prompts.CUSTOM_EXAMPLE) || p.contains("## 解析"))
        assertTrue("留空回落内置示例", Prompts.system(Prompts.MODE_CUSTOM, "standard", false, " \n").contains(Prompts.CUSTOM_EXAMPLE))
        assertTrue("小白开着补小白写法", Prompts.system(Prompts.MODE_CUSTOM, "brief", true, "x").contains("纯小白"))
    }

    // ---------- 关键词开关：开着才带，且固定在最后一行 ----------

    @Test fun termsSwitchAppendsKeywordLineLast() {
        for (m in Prompts.MODES) for (nv in listOf(false, true)) for (d in Prompts.DETAILS) {
            val on = Prompts.system(m, d, nv, "x", terms = true)
            assertTrue("$m/$d/novice=$nv 开着应带关键词清单", on.contains(Prompts.TERMS_RULE))
            assertTrue("$m/$d/novice=$nv 清单必须在输出最后（面板按最后一行拆词块）", on.trimEnd().endsWith(Prompts.TERMS_RULE))
            assertFalse("$m/$d/novice=$nv 关掉后不应带", Prompts.system(m, d, nv, "x", terms = false).contains(Prompts.TERMS_RULE))
        }
    }

    // ---------- directive 覆盖层：位置与优先级 ----------

    @Test fun directiveOverridesButStaysBeforeTerms() {
        val p = Prompts.system(Prompts.MODE_EXAM, "standard", false, "", terms = true, directive = "只给答案，别展开")
        assertTrue(p.contains("只给答案，别展开"))
        assertTrue("覆盖层压过内部默认（在 persona 之后）", p.indexOf("只给答案，别展开") > p.indexOf(Prompts.PERSONA))
        assertTrue("但不能把关键词清单砍掉（面板靠它拆词块）", p.indexOf("只给答案，别展开") < p.indexOf(Prompts.TERMS_RULE))
        assertEquals("空 directive 不加任何东西",
            Prompts.system(Prompts.MODE_EXAM, "standard", false),
            Prompts.system(Prompts.MODE_EXAM, "standard", false, "", terms = true, directive = "   "))
    }

    // ---------- 人格与记忆卡位置 ----------

    @Test fun personaAndCardPlacement() {
        for (m in Prompts.MODES) for (d in Prompts.DETAILS) for (nv in listOf(false, true)) {
            assertTrue("$m/$d/novice=$nv 缺 PERSONA", Prompts.system(m, d, nv, "x").contains(Prompts.PERSONA))
        }
        Prompts.card = null
        assertFalse("空卡不拼", Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("关于用户"))
        Prompts.card = Card()
        assertFalse("空 Card 也不拼", Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("关于用户"))
        Prompts.card = Card(name = "阿哲", goal = "考研数学", topics = listOf(CardTopic("傅里叶变换", 1L)))
        val with = Prompts.system(Prompts.MODE_EXAM, "standard", false)
        assertTrue(with.contains("他叫：阿哲") && with.contains("他正在做：考研数学"))
        assertTrue("记忆卡在 persona 之后、TERMS_RULE 之前",
            with.indexOf(Prompts.PERSONA) < with.indexOf("关于用户") && with.indexOf("关于用户") < with.indexOf(Prompts.TERMS_RULE))
        Prompts.card = null
    }

    // ---------- what / why 开关拼装 ----------

    @Test fun whatWhyTogglesAssembly() {
        val what = Prompts.styledAsk("第二步怎么来的", what = true, why = false)
        assertTrue(what.startsWith("第二步怎么来的\n\n回答方式：") && what.contains(Prompts.ASK_WHAT) && !what.contains(Prompts.ASK_WHY))

        val both = Prompts.styledAsk("", what = true, why = true)
        assertTrue(both.startsWith(Prompts.ASK_WHAT_Q + " " + Prompts.ASK_WHY_Q))
        assertTrue("what 在 why 前", both.indexOf(Prompts.ASK_WHAT) < both.indexOf(Prompts.ASK_WHY))
        assertEquals("都关：问题原样", "第二步怎么来的", Prompts.styledAsk("第二步怎么来的", false, false))
        assertEquals("空问题 + 都关：不发", "", Prompts.styledAsk("   ", false, false))
        assertEquals("［what·why］" + Prompts.ASK_WHAT_Q + " " + Prompts.ASK_WHY_Q, Prompts.askShown("", true, true))

        // 拼好的问题交给 followUp 包一层，要求都还在（what/why 不能被 followUp 吞掉）
        assertTrue(Prompts.followUp(Prompts.styledAsk("x", true, true), false).contains(Prompts.ASK_WHY))
    }

    // ---------- 追问：标题前缀 + 不重复链式约束 ----------

    @Test fun followUpCarriesQuestionAndSkipsChainRule() {
        val p = Prompts.followUp("  第二步怎么来的 ", false)
        assertTrue(p.startsWith("追问：第二步怎么来的\n"))
        assertTrue(p.contains(Prompts.MATH_RULE))
        assertFalse("追问是接话，不套重新解题的链式约束", p.contains(Prompts.CHAIN_RULE))
        assertTrue("小白追问用大白话", Prompts.followUp("为什么", true).contains("大白话"))
    }

    // ---------- 未知档回落 ----------

    @Test fun unknownDetailFallsBackToStandard() {
        assertEquals(Prompts.exam("standard", false), Prompts.exam("bogus", false))
        assertEquals(Prompts.personaBlock("standard"), Prompts.personaBlock("bogus"))
    }

    // ---------- 篇幅纪律按模式收敛（v1.77）----------

    /**
     * 「内容驱动篇幅」只对做题 / 遇到题目成立：自定义模式按用户自己写的要求、通用的非题保持简短，
     * 不能被做题型那一句覆盖掉。这里只锁**结构性行为**（三者互不相同、都带人格），不逐字钉新文案。
     */
    @Test fun personaLengthIsScopedByMode() {
        val exam = Prompts.personaBlock("standard", Prompts.MODE_EXAM)
        val custom = Prompts.personaBlock("standard", Prompts.MODE_CUSTOM)
        val general = Prompts.personaBlock("standard", Prompts.MODE_GENERAL)
        assertFalse("自定义不该被套上做题型篇幅", exam == custom)
        assertFalse("通用的标准档不该和纯做题同一句", exam == general)
        // brief / detailed 两档本就是「短 / 不设上限」，各模式同义，不受模式影响
        for (m in Prompts.MODES) {
            assertEquals(Prompts.personaBlock("brief", Prompts.MODE_EXAM), Prompts.personaBlock("brief", m))
            assertEquals(Prompts.personaBlock("detailed", Prompts.MODE_EXAM), Prompts.personaBlock("detailed", m))
        }
        for (m in Prompts.MODES) assertTrue("$m 缺 PERSONA", Prompts.personaBlock("standard", m).contains(Prompts.PERSONA))
    }

    // ---------- 纯函数行为 ----------

    @Test fun maxTokensErrorDetection() {
        assertTrue(Prompts.isMaxTokensError("HTTP 400 Range of max_tokens should be [1, 8192]"))
        assertTrue(Prompts.isMaxTokensError("HTTP 400 max_completion_tokens is too large"))
        assertFalse(Prompts.isMaxTokensError("HTTP 401（Key 不对或没权限）max_tokens"))
        assertFalse(Prompts.isMaxTokensError("HTTP 400 image too large"))
        assertFalse(Prompts.isMaxTokensError(null))
    }

    @Test fun budgetWidensForDetailedOrNovice() {
        assertEquals(4096, Prompts.budget(4096, "brief", false))
        assertEquals(4096, Prompts.budget(4096, "standard", false))
        assertEquals(6144, Prompts.budget(4096, "detailed", false))
        assertEquals(6144, Prompts.budget(4096, "brief", true))
    }

    @Test fun translateUsesLanguageRule() {
        assertTrue(Prompts.translate("hello", "ja").contains("一律译成自然的日文"))
        assertTrue(Prompts.translate("hello", "nope").contains("中英互译"))
        assertTrue(Prompts.translate("hello", "auto").endsWith("原文：\nhello"))
    }

    @Test fun noQuestionMarkerIsStripped() {
        assertEquals("画面是桌面", "[无题目] 画面是桌面".replace(Prompts.NO_Q_HEAD, ""))
        assertEquals("画面是桌面", "【无题目】画面是桌面".replace(Prompts.NO_Q_HEAD, ""))
        assertEquals("答案是 B", "答案是 B".replace(Prompts.NO_Q_HEAD, ""))
    }
}
