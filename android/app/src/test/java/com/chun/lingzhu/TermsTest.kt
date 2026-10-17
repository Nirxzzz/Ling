package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关键词清单解析（Terms.parse）+ 提示词约定（v1.65 通识引入，v1.66 所有模式） */
class TermsTest {

    @Test fun fullWidthSeparator() {
        val (rest, terms) = Terms.parse("讲解正文。\n\n[可点击名词] 光合作用｜叶绿体｜ATP")
        assertEquals("讲解正文。", rest)
        assertEquals(listOf("光合作用", "叶绿体", "ATP"), terms)
    }

    @Test fun halfWidthAndDunHaoSeparators() {
        val (_, terms) = Terms.parse("正文\n[可点击名词] 概率|均值、方差｜标准差")
        assertEquals(listOf("概率", "均值", "方差", "标准差"), terms)
    }

    @Test fun numberedPrefixStripped() {
        val (_, terms) = Terms.parse("x\n[可点击名词] 1. 导数｜2. 极限｜3) 积分")
        assertEquals(listOf("导数", "极限", "积分"), terms)
    }

    @Test fun noTermsLineReturnsUntouched() {
        val t = "普通回答，没有清单行。"
        val (rest, terms) = Terms.parse(t)
        assertEquals(t, rest)
        assertTrue(terms.isEmpty())
    }

    @Test fun emptyOrOverlongTermsFiltered() {
        val (_, terms) = Terms.parse("x\n[可点击名词] ｜ok｜" + "超".repeat(Terms.MAX_LEN + 1) + "｜" + "好".repeat(Terms.MAX_LEN))
        assertEquals(listOf("ok", "好".repeat(Terms.MAX_LEN)), terms)
    }

    @Test fun dedupAndCapTen() {
        val many = (1..15).joinToString("｜") { "词$it" }
        val (_, terms) = Terms.parse("x\n[可点击名词] A｜A｜$many")
        assertEquals(10, terms.size)
        assertEquals("A", terms[0])
        assertFalse(terms.contains("词15"))
    }

    @Test fun indentedLineStillMatched() {
        val (rest, terms) = Terms.parse("正文\n  [可点击名词] 折射｜反射")
        assertEquals(listOf("折射", "反射"), terms)
        assertFalse(rest.contains("可点击名词"))
    }

    @Test fun followUpAfterTermsLineKept() {
        val (rest, terms) = Terms.parse("正文\n[可点击名词] 电阻\n\n## 追问：再讲讲")
        assertEquals(listOf("电阻"), terms)
        assertTrue(rest.contains("## 追问：再讲讲"))
    }

    /** v1.66：关键词并进所有模式 —— 开关开着（默认）每个模式的提示词都带 TERMS_HEAD 清单要求，关着都不带 */
    @Test fun everyModeCarriesKeywords() {
        assertTrue(Prefs.KEYWORDS_DEFAULT)
        assertTrue(Solver.Options("general", "standard", false).terms)
        for (m in Prompts.MODES) for (novice in listOf(false, true)) for (d in Prompts.DETAILS) {
            val on = Prompts.system(m, d, novice, "x")
            assertTrue("$m/$d/$novice 应带关键词清单", on.contains(Prompts.TERMS_HEAD) && on.contains(Prompts.TERMS_RULE))
            assertFalse("$m 关掉后不应带", Prompts.system(m, d, novice, "x", terms = false).contains(Prompts.TERMS_HEAD))
        }
        // 追问不再重复列清单
        assertTrue(Prompts.followUp("为什么", false).contains("不要再输出「${Prompts.TERMS_HEAD}」"))
        assertTrue(Prompts.followUpWithShot("为什么", false).contains("不要再输出「${Prompts.TERMS_HEAD}」"))
    }

    /** v1.66 用户反馈：参数图里的芯片型号、规格词要优先提取 —— 提示词（含兜底补问）必须点名这类词 */
    @Test fun promptPrioritizesModelsAndSpecs() {
        for (p in listOf(Prompts.TERMS_RULE, Prompts.termsOnly("正文"))) {
            assertTrue(p.contains("型号")); assertTrue(p.contains("规格"))
            for (ex in listOf("R7250", "P3 广色域", "Wi-Fi 7", "LPDDR5X")) assertTrue("$ex 应作为例子", p.contains(ex))
        }
        assertTrue(Prompts.TERMS_RULE.contains("品牌")); assertTrue(Prompts.TERMS_RULE.contains("编号"))
    }

    /** v1.66 用户反馈：R7250、P3 广色域 这类不能被解析端过滤或截断 */
    @Test fun specTermsKeptVerbatim() {
        val specs = listOf("R7250", "P3 广色域", "Wi-Fi 7", "LPDDR5X", "2.5K 屏", "IP68", "Dolby Vision", "Snapdragon 8 Gen 3", "120Hz", "5G")
        val (_, terms) = Terms.parse("参数讲解\n\n[可点击名词] " + specs.joinToString("｜"))
        assertEquals(specs, terms)
    }

    @Test fun markdownAndQuotesTolerated() {
        val (rest, terms) = Terms.parse("正文\n\n**[可点击名词]** 「R7250」｜`P3 广色域`｜“UFS 4.0”")
        assertEquals(listOf("R7250", "P3 广色域", "UFS 4.0"), terms)
        assertEquals("正文", rest)
        assertEquals(listOf("A15"), Terms.parse("x\n- [可点击名词]：A15").second)
    }

    @Test fun repeatedListLinesAllStripped() {
        val (rest, terms) = Terms.parse("正文\n\n[可点击名词] R7250\n\n## 追问：再讲讲\n\n回答\n\n[可点击名词] P3")
        assertEquals(listOf("R7250"), terms)
        assertFalse(rest.contains("可点击名词"))
        assertTrue(rest.contains("回答"))
    }

    @Test fun longEnglishPhraseShortened() {
        val (_, terms) = Terms.parse("x\n[可点击名词] Material Design｜App info｜光合作用｜GPU 加速")
        assertEquals(listOf("Material Design", "App info", "光合作用", "GPU 加速"), terms)   // v1.66：≤3 个单词原样保留
        assertEquals("Newton Laws", Terms.shortenEnglish("Newton Laws"))
        assertEquals("the quick brown", Terms.shortenEnglish("the quick brown fox jumps"))   // 一句英文只留前 3 词
        assertEquals("匀速直线运动定律运动学", Terms.shortenEnglish("匀速直线运动定律运动学"))
        assertEquals("internationalization", Terms.shortenEnglish("internationalization"))
        assertEquals("Snapdragon 8 Gen 3", Terms.shortenEnglish("Snapdragon 8 Gen 3"))   // 带数字不截
    }
}
