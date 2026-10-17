package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.67 降成本策略（CostPolicy）：
 *  - max_tokens 分级：通用 2048 / 做题 8192，纯小白 ×1.5；detail 不参与定档
 *  - 追问省图：关 → 照旧发首轮截图；开 → 不重发，但「刚截的新屏」永远要发
 *  - 对话裁剪：首轮回答永远留着，只带最近几轮，且必须从 user 那句接（消息要交替）
 */
class CostPolicyTest {

    // ================= 2) max_tokens 分级 =================

    @Test fun solveBudgetGeneral() {
        assertEquals(2048, CostPolicy.solveBudget(Prompts.MODE_GENERAL, "brief", false))
        assertEquals(CostPolicy.BUDGET_GENERAL, CostPolicy.solveBudget(Prompts.MODE_GENERAL, "standard", false))
    }

    @Test fun solveBudgetSolveAndCustomBothUseSolveTier() {
        // 非 general 一律走做题档：自定义提示词同样要编号分步 + 逐项分析
        assertEquals(8192, CostPolicy.solveBudget(Prompts.MODE_EXAM, "brief", false))
        assertEquals(8192, CostPolicy.solveBudget(Prompts.MODE_CUSTOM, "brief", false))
        assertEquals(CostPolicy.BUDGET_SOLVE, CostPolicy.solveBudget(Prompts.MODE_EXAM, "standard", false))
    }

    @Test fun solveBudgetNoviceGets15x() {
        assertEquals(3072, CostPolicy.solveBudget(Prompts.MODE_GENERAL, "brief", true))   // 2048 × 1.5
        assertEquals(12288, CostPolicy.solveBudget(Prompts.MODE_EXAM, "brief", true))     // 8192 × 1.5
    }

    @Test fun solveBudgetDetailDoesNotChangeTier() {
        // detail 只影响字数要求（简略 150 / 标准 400），不再额外抬 max_tokens
        for (mode in Prompts.MODES) {
            assertEquals(CostPolicy.solveBudget(mode, "brief", false), CostPolicy.solveBudget(mode, "standard", false))
            assertEquals(CostPolicy.solveBudget(mode, "brief", true), CostPolicy.solveBudget(mode, "standard", true))
        }
        assertEquals(8192, CostPolicy.solveBudget(Prompts.MODE_EXAM, "详细", false))
    }

    @Test fun unknownModeFallsBackToSolveTier() {
        // 认不出的模式不能白捡便宜：按最贵的档给
        assertEquals(8192, CostPolicy.solveBudget("", "brief", false))
        assertEquals(8192, CostPolicy.solveBudget("不存在的模式", "brief", false))
    }

    @Test fun solveBudgetNeverBelowDeclaredFloor() {
        for (mode in Prompts.MODES) for (novice in listOf(false, true)) {
            val b = CostPolicy.solveBudget(mode, "standard", novice)
            assertTrue("$mode novice=$novice -> $b", b >= CostPolicy.BUDGET_GENERAL)
            if (mode != Prompts.MODE_GENERAL) assertTrue("$mode novice=$novice -> $b", b >= CostPolicy.BUDGET_SOLVE)
        }
    }

    @Test fun followBudgetGeneralIgnoresNovice() {
        // 追问跟着本轮模式走；通用模式追问短，不因纯小白再抬 —— 跟首轮的不对称是有意的
        assertEquals(2048, CostPolicy.followBudget(Prompts.MODE_GENERAL, false))
        assertEquals(2048, CostPolicy.followBudget(Prompts.MODE_GENERAL, true))
    }

    @Test fun followBudgetSolveTier() {
        assertEquals(8192, CostPolicy.followBudget(Prompts.MODE_EXAM, false))
        assertEquals(8192, CostPolicy.followBudget(Prompts.MODE_CUSTOM, false))
        assertEquals(12288, CostPolicy.followBudget(Prompts.MODE_EXAM, true))
        assertEquals(12288, CostPolicy.followBudget(Prompts.MODE_CUSTOM, true))
    }

    // ================= 1) 追问省图：发哪几张图 =================

    @Test fun followImagesKeepsFirstShotWhenNoImageOff() {
        // 关掉省图（默认老行为）：首轮截图照发
        assertEquals(listOf("shot1"), CostPolicy.followImages("shot1", emptyList(), null, false))
    }

    @Test fun followImagesKeepsFirstShotEvenWithExtras() {
        assertEquals(listOf("shot1", "shot2", "shot3"), CostPolicy.followImages("shot1", listOf("shot2", "shot3"), null, false))
    }

    @Test fun followImagesNoImageOnDropsOldShots() {
        // 开启省图：首轮截图 + 历史附图都不重发 —— 一张 1080×2400 截图折合上千 prompt token
        assertEquals(emptyList<String>(), CostPolicy.followImages("shot1", listOf("shot2", "shot3"), null, true))
    }

    @Test fun followImagesNoImageOnStillSendsNewShot() {
        // 续图追问的新截图就是为这次问题存在的，省图模式下也一定要发，且排在最后
        assertEquals(listOf("new"), CostPolicy.followImages("shot1", listOf("shot2"), "new", true))
        assertEquals(listOf("new"), CostPolicy.followImages(null, emptyList(), "new", true))
    }

    @Test fun followImagesNewShotAppendedLastWhenNoImageOff() {
        assertEquals(listOf("shot1", "shot2", "new"), CostPolicy.followImages("shot1", listOf("shot2"), "new", false))
    }

    @Test fun followImagesDeduplicates() {
        // 新屏和首轮截图内容相同（同一屏再截一次）→ 只发一张，别白花两份视觉 token
        assertEquals(listOf("same"), CostPolicy.followImages("same", listOf("same"), "same", false))
        // extraShots 内部重复也只留一份
        assertEquals(listOf("a", "b"), CostPolicy.followImages("a", listOf("b", "a", "b"), null, false))
    }

    @Test fun followImagesSkipsEmptyNewShot() {
        // 新屏 jpeg 落盘失败留空串 → 不能把空串当图片发出去
        assertEquals(listOf("shot1"), CostPolicy.followImages("shot1", emptyList(), "", false))
        assertEquals(emptyList<String>(), CostPolicy.followImages("", emptyList(), "", false))
        assertEquals(emptyList<String>(), CostPolicy.followImages(null, emptyList(), "", true))   // noImage=true 一张都不发
    }

    @Test fun followImagesExtraShotsFilterEmptyString_Fixed() {
        // 已修复（主脑整合期）：extraShots 现在与 firstShot / newShot 一样做 isNullOrEmpty 过滤，
        // 截图落盘失败的空串不再混进请求体。
        assertEquals(listOf("real"), CostPolicy.followImages("", listOf("", "real"), null, false))
    }

    @Test fun followImagesNoImageOnWithNoShotsAtAll() {
        // 省图 + 没有新屏 = 纯文本追问（几百 token），这正是设计目标
        assertTrue(CostPolicy.followImages(null, emptyList(), null, true).isEmpty())
        assertTrue(CostPolicy.followImages("", emptyList(), null, true).isEmpty())
    }

    // ================= 1) 追问省图：对话裁剪 =================

    /** 造一段真实形状的对话：第 0 条是首轮回答（assistant），之后 user/assistant 交替 */
    private fun conv(n: Int): List<Pair<String, String>> = (0 until n).map {
        if (it % 2 == 0) "assistant" to "答$it" else "user" to "问$it"
    }

    @Test fun trimTurnsEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), CostPolicy.trimTurns(emptyList()))
    }

    @Test fun trimTurnsKeepsShortConversationAsIs() {
        // size <= keepLast + 1 一条不动 —— 刚好 5 条（4+1）时全带，多一条才裁
        for (n in 1..5) assertEquals(conv(n), CostPolicy.trimTurns(conv(n)))
    }

    @Test fun trimTurnsJustOverThresholdDropsMiddle() {
        // 6 条 > 4+1 → 裁：留首轮回答 + 尾巴落在 assistant 的最近窗口（接追问才交替）
        val got = CostPolicy.trimTurns(conv(6))
        assertEquals(5, got.size)
        assertEquals("assistant" to "答0", got.first())
        assertEquals("user" to "问1", got[1])
        assertEquals("assistant" to "答4", got.last())   // 未回答的问5 带上会让追问连续两条 user
        assertTrue(got.none { it.second == "问5" })
    }

    @Test fun trimTurnsKeepsFirstAnswerAsOnlySceneDescription() {
        // 省图后首轮回答就是唯一的「画面说明」，任何长度都不能丢
        for (n in 1..40) {
            val got = CostPolicy.trimTurns(conv(n))
            assertEquals("assistant" to "答0", got.first())
        }
    }

    @Test fun trimTurnsSlidingWindowAtTwenty() {
        // 正好 20 条（多轮追问的常见长度）：start=16 是 assistant → 前移到 17 的 user
        val got = CostPolicy.trimTurns(conv(20))
        assertEquals(5, got.size)
        assertEquals("assistant" to "答0", got.first())
        assertEquals("user" to "问15", got[1])
        assertEquals("assistant" to "答18", got.last())
    }

    @Test fun trimTurnsVeryLongStaysBounded() {
        // 40 条：仍然只带首轮 + 最近 4 条，窗口不随对话变长而膨胀
        val got = CostPolicy.trimTurns(conv(40))
        assertEquals(5, got.size)
        assertEquals("user" to "问35", got[1])
        assertEquals("assistant" to "答38", got.last())
    }

    @Test fun trimTurnsAlwaysStartsWithUserAfterFirstAnswer() {
        // 接口按 user/assistant 交替解析，从 assistant 接会破坏结构
        for (n in 6..60) {
            val got = CostPolicy.trimTurns(conv(n))
            assertEquals("assistant", got.first().first)
            assertEquals("user", got[1].first)
            var prev = got.first().first
            for (i in 1 until got.size) {
                assertTrue("n=$n 第 $i 条 role=${got[i].first}", got[i].first != prev)
                prev = got[i].first
            }
        }
    }

    @Test fun trimTurnsFallsBackToFirstOnlyWhenNoUserFound() {
        // 尾部全是 assistant（异常形状）→ 找不着 user 接，宁可只带首轮回答也别发出坏结构
        val turns = listOf("assistant" to "首轮") + (0 until 10).map { "assistant" to "答$it" }
        assertEquals(listOf("assistant" to "首轮"), CostPolicy.trimTurns(turns))
    }

    @Test fun trimTurnsHonorsCustomKeepLast() {
        val got = CostPolicy.trimTurns(conv(20), keepLast = 2)
        assertEquals(3, got.size)                                // 首轮 + keepLast 条（尾巴落在 assistant）
        assertEquals("assistant" to "答0", got.first())
        assertEquals("user" to "问17", got[1])
        assertEquals("assistant" to "答18", got.last())
    }

    @Test fun trimTurnsKeepLastZeroKeepsFirstOnly() {
        assertEquals(listOf("assistant" to "答0"), CostPolicy.trimTurns(conv(10), keepLast = 0))
    }

    @Test fun trimTurnsPlusNewQuestionStaysAlternating() {
        // Solver.kt:69 的实际用法：裁完再接一句 user 的追问
        val got = CostPolicy.trimTurns(conv(30)) + ("user" to "讲简单点，举个例子")
        assertEquals("user", got.last().first)
        assertEquals("assistant", got[got.size - 2].first)
        assertEquals(CostPolicy.FOLLOW_TURNS_KEEP + 2, got.size)  // 首轮 + 4 条交替尾巴 + 本次提问
    }
}