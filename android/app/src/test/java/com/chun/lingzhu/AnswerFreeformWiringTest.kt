package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.72 追问「随便说」的**接线**回归测试。
 *
 * [FreePromptTest] 测的是「认不认得出」；这里测的是「认出来之后有没有真的生效」。
 *
 * 修的缺陷：风格段早先被并进 question 一起传下去，落在提示词**中间**，后面紧跟着两条
 * 默认约定把它压掉 ——
 *   「英文回我」→ 末尾「用中文，直接输出。」是最后一条指令，模型照它办；
 *   「讲细一点」→ 紧跟着「不超过 300 字」，正好顶死。
 * 于是 FreePrompt 认出的 lang / length 都被丢掉，等于白认。
 *
 * 现在 directive / lang 单独传进 Prompts，由它拼在**最后**，并且那两条默认约定会让位。
 */
class AnswerFreeformWiringTest {

    private fun styleOf(input: String) = FreePrompt.parse(input)

    /** 三条追问路径（带图 / 省图 / 续图）都要把覆盖层放到最后 —— 少一条就有一条路走回老毛病 */
    @Test fun directiveLandsLastOnEveryFollowUpPath() {
        val d = FreePrompt.funtoPrompt(styleOf("分步骤，用英文"))
        assertTrue(d.isNotEmpty())
        val paths = listOf(
            Prompts.followUp("第二步怎么来的", false, d, "en"),
            Prompts.followUpNoShot("第二步怎么来的", false, d, "en"),
            Prompts.followUpWithShot("现在呢", false, d, "en"),
        )
        for (p in paths) {
            assertTrue("覆盖层应出现在提示词里：$p", p.contains(d))
            assertTrue(
                "覆盖层必须是**最后**一段（用户的话得压过默认约定），实际结尾：..." + p.takeLast(80),
                p.trimEnd().endsWith(d.trimEnd()),
            )
        }
    }

    /** 「英文回我」：末尾不能再出现「用中文，直接输出。」—— 那条正好压在覆盖层前面把它废掉 */
    @Test fun langOverrideReplacesTheDefaultChineseRule() {
        for (p in listOf(
            Prompts.followUp("为什么", false, FreePrompt.funtoPrompt(styleOf("英文回我")), "en"),
            Prompts.followUpNoShot("为什么", false, FreePrompt.funtoPrompt(styleOf("英文回我")), "en"),
            Prompts.followUpWithShot("为什么", false, FreePrompt.funtoPrompt(styleOf("英文回我")), "en"),
        )) {
            assertFalse("识别出英文就不该再命令用中文：$p", p.contains("用中文，直接输出"))
            assertTrue("语言行应被换成英文：$p", p.contains("这一轮用英文"))
        }
    }

    /** 没指定语言时行为一个字都不变（v1.67/1.68 的老路径不能被这次改动带偏） */
    @Test fun noLangKeepsChineseDefault() {
        assertTrue(Prompts.followUp("为什么", false).contains("用中文，直接输出"))
        assertTrue(Prompts.followUpNoShot("为什么", false).contains("用中文，直接输出"))
        assertTrue(Prompts.followUpWithShot("为什么", false).contains("用中文，直接输出"))
    }

    /** 「讲细一点」：默认的「不超过 300 字」必须让位，否则详细要求自相矛盾 */
    @Test fun detailedDirectiveDisplacesTheLengthCap() {
        val d = FreePrompt.funtoPrompt(styleOf("讲细一点"))
        for (p in listOf(
            Prompts.followUp("为什么", false, d),
            Prompts.followUpNoShot("为什么", false, d),
            Prompts.followUpWithShot("为什么", false, d),
        )) {
            assertFalse("要了详细就不该再压字数上限：$p", p.contains("${Prompts.CHARS_FOLLOWUP} 字"))
            assertTrue("应保留一条让位给用户指令的说明：$p", p.contains("用户自己指定的要求"))
        }
    }

    /** 反过来：没有指令时字数上限照旧（省 token 的默认不能被无故去掉） */
    @Test fun noDirectiveKeepsLengthCap() {
        assertTrue(Prompts.followUp("为什么", false).contains("${Prompts.CHARS_FOLLOWUP} 字"))
        assertTrue(Prompts.followUpNoShot("为什么", false).contains("${Prompts.CHARS_FOLLOWUP} 字"))
        assertTrue(Prompts.followUpWithShot("为什么", false).contains("${Prompts.CHARS_FOLLOWUP} 字"))
    }

    /**
     * 端到端（纯逻辑层）：面板 sendTyped 的拼法 + Prompts 的排版 = 最终发出去那句话。
     * 这一条等价于面板 sendTyped 与 Solver.followUp 的接线 —— 两边任一处忘了传 directive/lang 都会红。
     */
    @Test fun panelToPromptKeepsUserDirective() {
        for (input in listOf("只给答案", "分步骤", "用英文回我", "详细点，假设我高一刚学", "别整那些花活")) {
            val s = styleOf(input)
            val q = s.question.ifBlank { if (s.hasDirective) Prompts.ASK_FREE_Q else "" }
            val question = Prompts.styledAsk(q, false, false)
            val directive = FreePrompt.funtoPrompt(s)
            val full = Prompts.followUpNoShot(question, false, directive, s.lang)
            assertTrue("「$input」的风格段应真的进到最终请求里：$full", full.contains(directive))
            assertTrue("「$input」应带上用户原话", full.contains(s.raw))
        }
    }

    /** 纯闲聊不能被当成指令塞进覆盖层 —— 否则模型会莫名其妙照做（「谢谢」→ 强行要求某种排版） */
    @Test fun chitchatProducesNoDirectiveAnywhere() {
        for (w in listOf("你好", "谢谢", "hi")) {
            val s = styleOf(w)
            assertFalse("「$w」不该产生覆盖层", s.hasDirective)
            assertEquals("", FreePrompt.funtoPrompt(s))
            // 老问题照旧当追问发出去
            assertEquals(w, Prompts.styledAsk(s.question, false, false))
        }
    }

    /** 普通问句不得被降级成「怎么回答」的指令 —— 面板显示的还得是用户写的那句 */
    @Test fun plainQuestionIsPassedThroughVerbatim() {
        val s = styleOf("这个函数为什么报错？")
        assertFalse(s.hasDirective)
        assertEquals("这个函数为什么报错？", s.question)
        assertEquals("这个函数为什么报错？", Prompts.styledAsk(s.question, false, false))
    }

    /**
     * 追问缓存的键必须含 directive / lang：同一个问题「只给答案」和「讲细一点」答案完全不同。
     * OrbService.runAsk 里Cache.key(...) 少传一个就会把上次的简短答案当成这次的详细答案发出去 ——
     * 纯逻辑无法直接跑服务，这里钉住「键里带了这两个值」这个约定。
     */
    @Test fun cacheKeySeparatesDirectives() {
        val brief = FreePrompt.funtoPrompt(styleOf("只给答案"))
        val detail = FreePrompt.funtoPrompt(styleOf("讲细一点"))
        assertTrue("两种要求必须算出不同的缓存键，否则会串答案", brief != detail)
        val k1 = Cache.key("follow", "shot", "为什么", "exam", "standard", "false", "", "0", "1", brief, "zh")
        val k2 = Cache.key("follow", "shot", "为什么", "exam", "standard", "false", "", "0", "1", detail, "zh")
        assertTrue("同问题不同风格不能共用缓存键", k1 != k2)
    }

    /** what / why 开关 + 自由指令同时开：两条都得在，且用户指令仍然压在最上面 */
    @Test fun quickTogglesAndFreeDirectiveCompose() {
        val s = styleOf("用英文回我")
        val directive = FreePrompt.funtoPrompt(s)
        val question = Prompts.styledAsk(Prompts.ASK_FREE_Q, true, true)
        val full = Prompts.followUpNoShot(question, false, directive, s.lang)
        assertTrue(full.contains(Prompts.ASK_WHAT))
        assertTrue(full.contains(Prompts.ASK_WHY))
        assertTrue(full.contains(directive))
        assertTrue("用户指令仍应在最后", full.trimEnd().endsWith(directive.trimEnd()))
    }

    /** 纯指令句也要发得出去：没有真问题时用中性问法顶上，不能发空问题 */
    @Test fun directiveOnlyStillSendsANonEmptyQuestion() {
        val s = styleOf("只给答案")
        assertTrue(s.directiveOnly)
        val q = s.question.ifBlank { Prompts.ASK_FREE_Q }
        assertTrue(q.isNotBlank())
        assertTrue(Prompts.followUpNoShot(Prompts.styledAsk(q, false, false), false, FreePrompt.funtoPrompt(s), s.lang).isNotBlank())
    }
}