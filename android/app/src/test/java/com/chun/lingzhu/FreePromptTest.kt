package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.68 追问输入框「自由指令」：[FreePrompt.parse] 认得出长度 / 格式 / 语言，认不出的原样透传；
 * [FreePrompt.funtoPrompt] 把风格参数拼成追加到追问请求末尾的覆盖层。
 */
class FreePromptTest {

    // ---------------- 长度 ----------------

    @Test fun briefChinese() {
        val s = FreePrompt.parse("只给答案")
        assertEquals(FreePrompt.LENGTH_BRIEF, s.length)
        assertTrue(s.directiveOnly)
    }

    @Test fun briefVariants() {
        for (w in listOf("只要答案", "只说答案", "别解释", "简洁一点", "别啰嗦", "越短越好", "一句话")) {
            assertEquals("「$w」应认成 brief", FreePrompt.LENGTH_BRIEF, FreePrompt.parse(w).length)
        }
    }

    @Test fun briefEnglish() {
        val s = FreePrompt.parse("just the answer")
        assertEquals(FreePrompt.LENGTH_BRIEF, s.length)
    }

    @Test fun detailedChinese() {
        val s = FreePrompt.parse("讲细一点")
        assertEquals(FreePrompt.LENGTH_DETAILED, s.length)
    }

    @Test fun detailedVariants() {
        for (w in listOf("详细", "展开讲讲", "多写点", "写清楚", "讲透", "说透")) {
            assertEquals("「$w」应认成 detailed", FreePrompt.LENGTH_DETAILED, FreePrompt.parse(w).length)
        }
    }

    @Test fun detailedEnglish() {
        assertEquals(FreePrompt.LENGTH_DETAILED, FreePrompt.parse("explain in detail").length)
    }

    /** brief / detailed 都出现时取词表里先命中的那个，不许崩、不许两个都认 */
    @Test fun lengthConflictTakesFirst() {
        val s = FreePrompt.parse("详细一点，别太啰嗦")
        assertTrue("应只认出一个篇幅档", s.length == FreePrompt.LENGTH_BRIEF || s.length == FreePrompt.LENGTH_DETAILED)
    }

    // ---------------- 格式 ----------------

    @Test fun formatSteps() {
        assertEquals(FreePrompt.FORMAT_STEPS, FreePrompt.parse("分步骤讲").format)
    }

    @Test fun formatTable() {
        assertEquals(FreePrompt.FORMAT_TABLE, FreePrompt.parse("列个表").format)
    }

    @Test fun formatList() {
        assertEquals(FreePrompt.FORMAT_LIST, FreePrompt.parse("要点式").format)
    }

    @Test fun formatEnglish() {
        assertEquals(FreePrompt.FORMAT_STEPS, FreePrompt.parse("step by step please").format)
    }

    // ---------------- 语言 ----------------

    @Test fun langEnglish() {
        val s = FreePrompt.parse("用英文回我")
        assertEquals(FreePrompt.LANG_EN, s.lang)
        assertTrue(s.directiveOnly)
    }

    @Test fun langChinese() {
        assertEquals(FreePrompt.LANG_ZH, FreePrompt.parse("说中文").lang)
    }

    @Test fun langEnglishVariants() {
        assertEquals(FreePrompt.LANG_EN, FreePrompt.parse("answer in English").lang)
    }

    // ---------------- 抽指令后剩下真问题 ----------------

    @Test fun keepsQuestionAlongsideDirective() {
        val s = FreePrompt.parse("这个函数为什么报错？简短一点")
        assertEquals(FreePrompt.LENGTH_BRIEF, s.length)
        assertTrue("应剩下真问题：${s.question}", s.question.contains("为什么报错"))
    }

    /** 中英混写：结构化参数认得出，剩下的英文原样留着当问题 */
    @Test fun mixedChineseEnglish() {
        val s = FreePrompt.parse("why does it crash? 用中文")
        assertEquals(FreePrompt.LANG_ZH, s.lang)
        assertTrue(s.question.contains("crash"))
    }

    @Test fun mixedDirectiveAndQuestion() {
        val s = FreePrompt.parse("分步骤 explain how TCP works")
        assertEquals(FreePrompt.FORMAT_STEPS, s.format)
        assertTrue(s.question.contains("TCP"))
    }

    // ---------------- 识别不了 → 原样透传 ----------------

    @Test fun unknownDirectivePassedThrough() {
        val s = FreePrompt.parse("别整那些花活")
        assertNull("认不出的不该硬塞进结构化参数", s.length)
        assertEquals("别整那些花活", s.free)
        assertTrue(s.hasDirective)
    }

    /** 认出了已知指令、剩下的口语要求也透传（不塞进问题里） */
    @Test fun residualUnknownPassedThrough() {
        val s = FreePrompt.parse("详细点，假设我高一刚学")
        assertEquals(FreePrompt.LENGTH_DETAILED, s.length)
        assertTrue(s.question.isEmpty())
        assertTrue("残留应透传：${s.free}", s.free!!.contains("高一"))
    }

    /** 闲聊不当指令 —— 塞进覆盖层模型会莫名其妙照做 */
    @Test fun chitchatNotDirective() {
        for (w in listOf("你好", "谢谢", "hi", "thanks")) {
            val s = FreePrompt.parse(w)
            assertFalse("「$w」不该有指令", s.hasDirective)
            assertEquals(w, s.question)
        }
    }

    @Test fun nullAndBlankSafe() {
        assertTrue(FreePrompt.parse(null).empty)
        assertTrue(FreePrompt.parse("").empty)
        assertTrue(FreePrompt.parse("   \n ").empty)
        assertEquals("", FreePrompt.funtoPrompt(FreePrompt.parse("")))
    }

    /** 超长输入：截断，不能抛、不能把整段塞进提示词 */
    @Test fun oversizedInputTruncated() {
        val long = "详细点，" + "啊".repeat(600)
        val s = FreePrompt.parse(long)
        assertTrue("应截断到 MAX_INPUT 附近：${s.raw.length}", s.raw.length <= FreePrompt.MAX_INPUT)
        assertEquals(FreePrompt.LENGTH_DETAILED, s.length)
    }

    @Test fun noDirectiveHasBlankPrompt() {
        assertEquals("", FreePrompt.funtoPrompt(FreePrompt.parse("为什么答案选 B")))
    }

    /** 抽出「用英文」后剩下的 answer 是语气词，不是新指令、更不是问题 */
    @Test fun fillerResidueDropped() {
        val s = FreePrompt.parse("answer in English")
        assertEquals(FreePrompt.LANG_EN, s.lang)
        assertEquals("", s.question)
        assertNull("纯语气词不该透传成指令", s.free)
    }

    /** 普通问句绝不能变成指令 —— 否则模型会把问题本身当排版要求执行 */
    @Test fun plainQuestionStaysPlain() {
        val s = FreePrompt.parse("这个函数为什么报错？")
        assertFalse(s.hasDirective)
        assertEquals("这个函数为什么报错？", s.question)
    }

    /** 「差一点」里的「一点」不是噪声词，不能被边缘剥离削掉 */
    @Test fun edgeNoiseDoesNotEatRealWords() {
        assertEquals("为什么差一点", FreePrompt.parse("讲细一点，为什么差一点").question)
    }

    // ---------------- funtoPrompt ----------------

    @Test fun promptContainsAllRecognisedParams() {
        val s = FreePrompt.parse("分步骤，用英文，详细点")
        val p = FreePrompt.funtoPrompt(s)
        assertTrue(p.contains("编号分步"))
        assertTrue(p.contains("一律用英文"))
        assertTrue(p.contains("详细展开"))
        assertTrue("应带上用户原话", p.contains(s.raw))
    }

    /** 覆盖层要声明「正文按用户指令、仅保留解析所需标记行」 */
    @Test fun promptDeclaresBodyFollowsUser() {
        val p = FreePrompt.funtoPrompt(FreePrompt.parse("只给答案"))
        assertTrue(p.contains("正文按用户指令"))
        assertTrue(p.contains("关键词清单行"))
    }

    /** 透传的话要写进提示词 */
    @Test fun promptCarriesFreeText() {
        val p = FreePrompt.funtoPrompt(FreePrompt.parse("别整那些花活"))
        assertTrue(p.contains("别整那些花活"))
    }
}