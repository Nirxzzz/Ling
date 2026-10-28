package com.chun.lingzhu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读位置的纯判定（v1.88 修补 + v2.7.2）：发送追问时「要不要继续跟到底」（[AnswerPanel.followOnSend]）、
 * 新一轮请求开始「要不要跟」（[AnswerPanel.followOnRequestStart]，修「识别完成，答案默认在底部」）、
 * 「同一答案重开保不保读位」（[AnswerPanel.keepReadPosOnShow]）。判定都从 View 里提出来，JVM 直接测；
 * View 侧的「此刻是否真在底部」由 `AnswerPanel.atTrueBottom()` 读一次 `canScrollVertically` + 1dp 容差。
 * 这里只锁判定语义，不碰 View。
 */
class AnswerFollowSendTest {

    /** bug 场景：用户正停在底部看流式，但 follow 因流式增量 / settle 竞态停在 false —— 发送时必须恢复跟随 */
    @Test fun bottomRestoresFollowEvenWhenFlagWasFalse() {
        assertTrue("停在真底 → 恢复跟随（新内容继续贴底）",
            AnswerPanel.followOnSend(wasFollowing = false, atTrueBottom = true))
    }

    /** 原场景复测：用户在中部往上读（不在底部）→ 发送追问别抢他的位置 */
    @Test fun aboveBottomKeepsPosition() {
        assertFalse("不在底部 → 不打开跟随",
            AnswerPanel.followOnSend(wasFollowing = false, atTrueBottom = false))
    }

    /** 已经在跟随（follow 已真）→ 保持跟随 */
    @Test fun alreadyFollowingStaysFollowing() {
        assertTrue("已在跟随 → 继续跟随",
            AnswerPanel.followOnSend(wasFollowing = true, atTrueBottom = false))
        assertTrue(AnswerPanel.followOnSend(wasFollowing = true, atTrueBottom = true))
    }

    /**
     * v2.7.2 修（识别完成，答案默认在底部）：新一轮请求开始的跟随判定。
     * 新答案（reset 后正文为空）一律不跟 —— 从开头读，流式增量不许把还没主动到底的用户拽到末尾。
     */
    @Test fun newAnswerNeverOpensFollow() {
        assertFalse("空正文 = 新答案 → 不打开跟随（哪怕位置读数假报「在底部」）",
            AnswerPanel.followOnRequestStart(hasBody = false, wasFollowing = false, atTrueBottom = true))
        assertFalse("上一轮遗留的 follow 也不许带进新答案",
            AnswerPanel.followOnRequestStart(hasBody = false, wasFollowing = true, atTrueBottom = true))
    }

    /** 追加（追问 / 自动扫描重扫旧文还挂着）：维持发送时的追加阅读策略，只在真底恢复跟随 */
    @Test fun appendRoundKeepsSendTimeStrategy() {
        assertTrue("正文还在 + 停在真底 → 恢复跟随",
            AnswerPanel.followOnRequestStart(hasBody = true, wasFollowing = false, atTrueBottom = true))
        assertFalse("正文还在 + 在上方 → 原地不动",
            AnswerPanel.followOnRequestStart(hasBody = true, wasFollowing = false, atTrueBottom = false))
        assertTrue("已跟随 → 继续跟",
            AnswerPanel.followOnRequestStart(hasBody = true, wasFollowing = true, atTrueBottom = false))
    }

    /** 同一份答案重开保持读位；换了内容 / 被动层「只抽答案」切回全文 = 首次展示 → 从开头读 */
    @Test fun sameAnswerReopenKeepsReadPos() {
        assertTrue("同文同形态重开 → 保持已有阅读位置",
            AnswerPanel.keepReadPosOnShow(sameText = true, bodyShowsSame = true))
        assertFalse("内容换了 → 新答案从开头读",
            AnswerPanel.keepReadPosOnShow(sameText = false, bodyShowsSame = true))
        assertFalse("同文但正文形态变了（被动层切回全文）→ 首次展示从开头读",
            AnswerPanel.keepReadPosOnShow(sameText = true, bodyShowsSame = false))
    }
}
