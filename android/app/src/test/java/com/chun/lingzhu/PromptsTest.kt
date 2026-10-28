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
 *  - 纯小白开关真的换一套格式（小白做题格式 vs 普通三段式）；
 *  - 三段式标题 `## 答案 / ## 解析 / ## 考点` 与多题标记 `### 第 N 题`（面板排版靠它）；
 *  - 关键词清单开关：开着才带 TERMS_RULE，且固定在输出最后一行；
 *  - directive 覆盖层：压在 TERMS_RULE 之前；空则不加；
 *  - 记忆卡位置；what/why 开关拼装；预算 / 错误识别 / 翻译 / 去标记等纯函数行为。
 */
class PromptsTest {

    // ---------- 纯小白：决定走哪套格式 ----------

    @Test fun noviceSelectsIndependentFormat() {
        val nvExam = Prompts.system(Prompts.MODE_EXAM, "standard", true)
        assertTrue("小白做题走独立格式", nvExam.contains("## 一步步做"))
        assertFalse("小白格式不和普通三段式混", nvExam.contains("## 解析"))
        assertTrue("非小白做题走三段式", Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("## 解析"))

        assertFalse("通用模式非小白不补通俗要求",
            Prompts.system(Prompts.MODE_GENERAL, "standard", false) == Prompts.system(Prompts.MODE_GENERAL, "standard", true))
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
        assertTrue("用户原文照进（只做 trim）", p.contains("把画面里的英文翻成中文"))
        assertFalse("用了用户提示就不该再塞内置示例 / 三段式", p.contains(Prompts.CUSTOM_EXAMPLE) || p.contains("## 解析"))
        assertTrue("留空回落内置示例", Prompts.system(Prompts.MODE_CUSTOM, "standard", false, " \n").contains(Prompts.CUSTOM_EXAMPLE))
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
        assertTrue("但不能把关键词清单砍掉（面板靠它拆词块）", p.indexOf("只给答案，别展开") < p.indexOf(Prompts.TERMS_RULE))
        assertEquals("空 directive 不加任何东西",
            Prompts.system(Prompts.MODE_EXAM, "standard", false),
            Prompts.system(Prompts.MODE_EXAM, "standard", false, "", terms = true, directive = "   "))
    }

    // ---------- 记忆卡位置 ----------

    @Test fun cardPlacement() {
        Prompts.card = null
        assertFalse("空卡不拼", Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("关于用户"))
        Prompts.card = Card()
        assertFalse("空 Card 也不拼", Prompts.system(Prompts.MODE_EXAM, "standard", false).contains("关于用户"))
        Prompts.card = Card(name = "阿哲", goal = "考研数学", topics = listOf(CardTopic("傅里叶变换", 1L)))
        val with = Prompts.system(Prompts.MODE_EXAM, "standard", false)
        assertTrue(with.contains("他叫：阿哲") && with.contains("他正在做：考研数学"))
        assertTrue("记忆卡在 TERMS_RULE 之前", with.indexOf("关于用户") < with.indexOf(Prompts.TERMS_RULE))
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
    }

    // ---------- 单词翻译模式：可见性与关键词判据（提示词措辞质量交给真模型验，不在这里钉） ----------

    /** 标签与可选列表：单词翻译是模式名，但不进设置 / 抽屉的可选列表（只有双击能进） */
    @Test fun wordModeLabelAndVisibility() {
        assertEquals("单词翻译", Prompts.MODE_LABEL.getValue(Prompts.MODE_WORD))
        assertFalse(Prompts.MODES.contains(Prompts.MODE_WORD))
        assertEquals("单词翻译", Modes.label(Prompts.MODE_WORD, emptyList()))
    }

    /**
     * `wantsTerms` 是「提示词末尾拼不拼关键词清单行」和「首轮没清单行要不要纯文本补问一次」的**同一个判据**：
     * 分开写时单词翻译模式没有清单行、兜底条件却恒真，每查一个词都会白发一次请求，还往卡片尾巴塞一行清单。
     * 这里只锁「判据与提示词实际产出一致」这一条行为，不钉任何提示词文案。
     */
    @Test fun wantsTermsMatchesActualPrompt() {
        for (m in Prompts.MODES + Prompts.MODE_WORD) for (terms in listOf(false, true)) {
            val p = Prompts.system(m, "standard", false, "x", terms = terms)
            assertEquals("$m/terms=$terms：判据与提示词必须一致", p.contains(Prompts.TERMS_RULE), Prompts.wantsTerms(m, terms))
        }
        assertFalse("单词翻译永不补问清单", Prompts.wantsTerms(Prompts.MODE_WORD, true))
        assertTrue("其它模式跟着开关", Prompts.wantsTerms(Prompts.MODE_GENERAL, true) && Prompts.wantsTerms(Prompts.MODE_EXAM, true))
        assertFalse("开关关着谁都不带", Prompts.wantsTerms(Prompts.MODE_EXAM, false))
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

    /**
     * 摘「中英互译」段绝不能吞掉后面的解析：模型的解析常用 `### 第 N 题` / `### 解析` 这类三级标题，
     * 摘段边界必须认任意级标题（只认 `## ` 会一路删到下一个二级标题或结尾）。
     */
    @Test fun sectionStopsAtAnyHeadingLevel() {
        val out = "知识就是力量。\n\n## AI 解析\n\n### 解析\n关键词：Knowledge 名词\n\n## 中英互译\n\n英文：Knowledge is power.\n中文：知识就是力量。"
        assertEquals("英文：Knowledge is power.\n中文：知识就是力量。", Prompts.section(out, Prompts.BILINGUAL_HEAD)?.trim())
        val kept = Prompts.withoutSection(out, Prompts.BILINGUAL_HEAD)
        assertTrue("三级标题的解析必须留下", kept.contains("### 解析") && kept.contains("关键词：Knowledge 名词"))
        assertFalse("互译段本身要摘掉", kept.contains(Prompts.BILINGUAL_HEAD))
        assertEquals("没有这一段就原样返回", "a\n\nb", Prompts.withoutSection("a\n\nb", Prompts.BILINGUAL_HEAD))
    }

    /**
     * 互译段必须是真对照：模型把一句抄成两行（只有标点 / 大小写 / 空格差别）、或两行语言对不上时一律不收，
     * 返回 null 让调用方如实说缺 —— 收下来就是把同一句话冒充成两种语言。
     */
    @Test fun bilingualSectionRejectsFakePair() {
        val ok = Prompts.bilingualSection("英文：Knowledge is power.\n中文：知识就是力量。")
        assertEquals(Prompts.BILINGUAL_HEAD + "\n英文：Knowledge is power.\n中文：知识就是力量。", ok)
        // 全角冒号 + 加粗 + 列表符：装饰要去掉，收进来的仍是这两句
        assertEquals(ok, Prompts.bilingualSection("- **英文**：Knowledge is power.\n- **中文**：知识就是力量。"))
        // 同一句抄两遍（大小写 / 空格 / 标点不同）不算互译
        assertTrue(Prompts.bilingualSection("英文：Knowledge is power\n中文：knowledge is power.") == null)
        assertTrue(Prompts.bilingualSection("英文：Knowledge is power.\n中文：Knowledge is power.") == null)
        // 语言对不上：英文那行其实是中文；中文那行没有汉字
        assertTrue(Prompts.bilingualSection("英文：知识就是力量。\n中文：知识就是力量。") == null)
        assertTrue(Prompts.bilingualSection("英文：Knowledge is power.\n中文：ok") == null)
        // 缺一条就没有对照
        assertTrue(Prompts.bilingualSection("英文：Knowledge is power.") == null)
        assertTrue(Prompts.bilingualSection("  ") == null)
    }

    /** 两个方向都能拼出对照；同语种 / 同一句 / 非中英一律不拼（缺的那一半只能由模型真翻）。 */
    @Test fun bilingualPairUsesExistingTextOnly() {
        assertEquals(Prompts.BILINGUAL_HEAD + "\n英文：Knowledge is power.\n中文：知识就是力量。",
            Prompts.bilingualPair("Knowledge is power.", "知识就是力量。"))
        assertEquals(Prompts.BILINGUAL_HEAD + "\n英文：Knowledge is power.\n中文：知识就是力量。",
            Prompts.bilingualPair("知识就是力量。", "Knowledge is power."))
        assertTrue("同语种不能拼", Prompts.bilingualPair("坚持每天学习。", "坚持每天学习。") == null)
        assertTrue("同一句不能拼", Prompts.bilingualPair("坚持每天学习。", "坚持每天学习") == null)
        assertTrue("非中英不拼", Prompts.bilingualPair("毎日勉強します。", "Every day I study.") == null)
    }

    @Test fun noQuestionMarkerIsStripped() {
        assertEquals("画面是桌面", "[无题目] 画面是桌面".replace(Prompts.NO_Q_HEAD, ""))
        assertEquals("画面是桌面", "【无题目】画面是桌面".replace(Prompts.NO_Q_HEAD, ""))
        assertEquals("答案是 B", "答案是 B".replace(Prompts.NO_Q_HEAD, ""))
    }
}
