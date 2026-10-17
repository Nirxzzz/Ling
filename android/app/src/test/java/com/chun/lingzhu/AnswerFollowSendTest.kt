package com.chun.lingzhu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * follow-from-bottom 修复（v1.88 修补）：发送追问那一刻「要不要继续跟到底」的判定。
 *
 * 纯逻辑在 [AnswerPanel.followOnSend]（把判定从 View 里提出来，JVM 直接测）；View 侧的
 * 「此刻是否真在底部」由 `AnswerPanel.atTrueBottom()` 读一次 `canScrollVertically` + 1dp 容差。
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
}
