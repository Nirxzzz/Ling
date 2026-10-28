package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerTextTest {
    @Test fun examFormat() {
        val (a, rest) = AnswerText.split("## 答案\nB. 木块受到的合力为零\n\n## 这题在问什么\n木块匀速走。\n\n## 记住这句\n合力为零。")
        assertEquals("B. 木块受到的合力为零", a)
        assertFalse(rest.contains("## 答案"))
        assertTrue(rest.startsWith("## 这题在问什么") && rest.contains("## 记住这句"))
    }

    @Test fun examAnswerWithSubQuestions() {
        val (a, _) = AnswerText.split("图里的指数看不清，我按 x^2 理解。\n\n## 答案\n（1）最小值是 -1；\n（2）当 x = 2 时取到最小值。\n\n## 一步步做\n1. 配方")
        assertEquals("（1）最小值是 -1；\n（2）当 x = 2 时取到最小值。", a)
    }

    @Test fun streamingPartialAnswer() {
        val (a, rest) = AnswerText.split("## 答案\nB. 木块")
        assertEquals("B. 木块", a)
        assertEquals("", rest)
    }

    @Test fun generalFormat() {
        val (a, rest) = AnswerText.split("**答案：** B. 木块受到的合力为零\n\n**为什么：**\n1. 先看匀速")
        assertEquals("B. 木块受到的合力为零", a)
        assertTrue(rest.startsWith("**为什么：**"))
        val (a2, _) = AnswerText.split("**答案：**\n（1）最小值是 −1\n（2）x = 2 时取到最小值\n\n**为什么：**\n1. 配方")
        assertEquals("（1）最小值是 −1\n（2）x = 2 时取到最小值", a2)
    }

    /** v1.54：「答案：」同一行有内容时，下一行起的理由不再被吞进顶部答案行 */
    @Test fun generalReasonStaysInBody() {
        val (a, rest) = AnswerText.split("**答案：** B. 合力为零\n理由：匀速说明速度不变，所以合力为零。\n\n**建议：** 记住牛顿第一定律")
        assertEquals("B. 合力为零", a)
        assertTrue(rest.startsWith("理由：匀速") && rest.contains("**建议：**"))
    }

    /** 答案太长、顶部三行放不下 → 正文保留完整原文，被省略号截掉的部分还能在正文里看到 */
    @Test fun longAnswerKeptInBody() {
        val short = "## 答案\nB. 鲸鱼\n\n## 一步步做\n1. 看"
        assertEquals("B. 鲸鱼" to "## 一步步做\n1. 看", AnswerText.forPanel(short))
        val long = "## 答案\n（1）a = 1\n（2）b = 2\n（3）c = 3\n（4）d = 4\n\n## 一步步做\n1. 看"
        val (a, body) = AnswerText.forPanel(long)
        assertTrue(a.startsWith("（1）a = 1"))
        assertEquals(long, body)
        val wide = "## 答案\n" + "很长的答案".repeat(20) + "\n## 一步步做\n1."
        assertEquals(wide, AnswerText.forPanel(wide).second)
        assertEquals("" to "没有答案段", AnswerText.forPanel("没有答案段"))
    }

    @Test fun multipleQuestionsNotSplit() {
        val src = "### 第 1 题\n## 答案\nA\n## 一步步做\n1.\n### 第 2 题\n## 答案\nC\n"
        assertEquals("" to src, AnswerText.split(src))
    }

    /**
     * v2.1 性能：panel() 一遍拆出 render 全部要的字段，forPanel 委托它 —— 两者必须一致（钉死不漂移）。
     * answerDuplicated = 答案长（正文保留了含答案段的完整第一轮，顶部行和正文必然重复）。
     */
    @Test fun panelMatchesForPanel() {
        val short = "## 答案\nB. 鲸鱼\n\n## 一步步做\n1. 看"
        val pShort = AnswerText.panel(short)
        assertEquals("B. 鲸鱼", pShort.ans)
        assertEquals("## 一步步做\n1. 看", pShort.body)
        assertFalse(pShort.answerDuplicated)
        assertEquals(AnswerText.forPanel(short), pShort.ans to pShort.body)

        val long = "## 答案\n（1）a = 1\n（2）b = 2\n（3）c = 3\n（4）d = 4\n\n## 一步步做\n1. 看"
        val pLong = AnswerText.panel(long)
        assertTrue(pLong.ans.startsWith("（1）a = 1"))
        assertEquals(long, pLong.body)
        assertTrue("长答案：顶部行和正文里那一段是重复的", pLong.answerDuplicated)
        assertEquals(AnswerText.forPanel(long), pLong.ans to pLong.body)

        val none = "没有答案段"
        val pNone = AnswerText.panel(none)
        assertEquals("", pNone.ans)
        assertEquals(none, pNone.body)
        assertFalse(pNone.answerDuplicated)
        assertEquals(AnswerText.forPanel(none), pNone.ans to pNone.body)
    }

    /** panel() 的第一轮 / 追问拆分和 splitFollowUps 一致：顶部答案永远来自第一轮 */
    @Test fun panelFirstRoundComesBeforeFollowUps() {
        val src = "## 答案\nB. 鲸鱼\n\n## 一步步做\n1. 看\n\n## 追问：为什么\n\n胎生。"
        val p = AnswerText.panel(src)
        assertEquals(AnswerText.splitFollowUps(src).first, p.firstRound)
        assertTrue(p.body.endsWith("胎生。"))
        assertFalse("短答案已从正文剥掉，顶部行不算重复", p.answerDuplicated)
    }

    @Test fun noAnswerSection() {
        val src = "这是微信的设置页面。\n可以改头像。"
        assertEquals("" to src, AnswerText.split(src))
        assertEquals("这是微信的设置页面。", AnswerText.firstLine("## 标题\n\n这是微信的设置页面。\n可以改头像。"))
    }

    /** v1.57：追问段不参与拆答案 —— 顶部仍是第一轮答案，追问接在正文后面，追问里的「## 答案」也不会让拆分失效 */
    @Test fun followUpsStayInBody() {
        val src = "## 答案\nB. 鲸鱼\n\n## 解析\n哺乳动物。\n\n## 追问：为什么\n\n## 答案\n因为胎生。\n\n## 追问：举个例子\n\n海豚也是。"
        val (a, rest) = AnswerText.forPanel(src)
        assertEquals("B. 鲸鱼", a)
        assertTrue(rest.startsWith("## 解析\n哺乳动物。\n\n## 追问：为什么"))
        assertTrue(rest.endsWith("## 追问：举个例子\n\n海豚也是。"))
        assertTrue(rest.contains("因为胎生。"))
        assertEquals("## 答案\nB. 鲸鱼\n\n## 解析\n哺乳动物。\n", AnswerText.splitFollowUps(src).first)
    }

    @Test fun followUpWithoutAnswerLine() {
        val src = "这是微信设置页。\n\n## 追问：怎么改头像\n\n点头像。"
        val (a, rest) = AnswerText.forPanel(src)
        assertEquals("", a)
        assertEquals(src, rest)
    }

    /** 自动扫描被动层：多道题里重复的「## 答案」要全部收下，解析不能混进来 */
    @Test fun scanAnswersKeepsEveryExamSection() {
        val src = "### 第 1 题\n## 答案\nA. 1\n\n## 解析\n第一题的推理过程。\n\n" +
                  "### 第 2 题\n## 答案\n（1）2\n（2）3\n\n## 解析\n第二题的推理过程。"
        assertEquals("A. 1\n\n（1）2\n（2）3", AnswerText.scanAnswers(src))
    }

    /** 旧的「**答案：**」格式同样支持多段，解析 / 理由留在外面 */
    @Test fun scanAnswersKeepsEveryLegacyBoldSection() {
        val src = "**答案：** B. 木块受到的合力为零\n理由：匀速说明合力为零。\n\n" +
                  "**答案：**\n（1）−1\n（2）x = 2\n\n**为什么：** 1. 配方"
        assertEquals("B. 木块受到的合力为零\n\n（1）−1\n（2）x = 2", AnswerText.scanAnswers(src))
    }

    /** 没有答案结构就返回空串 —— 调用方显示「点小球查看」，不得把整段解析当答案 */
    @Test fun scanAnswersRefusesProseFallback() {
        val src = "这是微信的设置页面。\n可以改头像，也可以改昵称。"
        assertEquals("", AnswerText.scanAnswers(src))
    }

    /** 答案超过旧的 72 字阈值也必须完整返回（被动层不做首段 / 字数截断） */
    @Test fun scanAnswersKeepsLongAnswerWhole() {
        val long = "A. " + "很长的答案".repeat(30)
        assertEquals(long, AnswerText.scanAnswers("## 答案\n$long\n\n## 一步步做\n1. 推导"))
    }

    /** 追问段不属于自动扫描这一轮的答案，被动层不展开 */
    @Test fun scanAnswersIgnoresFollowUps() {
        val src = "## 答案\nB. 鲸鱼\n\n## 解析\n哺乳动物。\n\n## 追问：为什么\n\n## 答案\n因为胎生。"
        assertEquals("B. 鲸鱼", AnswerText.scanAnswers(src))
    }
}

class SpeechTextTest {
    @org.junit.Test fun speechLanguage() {
        org.junit.Assert.assertTrue(AnswerText.isMostlyChinese("答案是 B because x"))
        org.junit.Assert.assertFalse(AnswerText.isMostlyChinese("The answer is B"))
    }
}
