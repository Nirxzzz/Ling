package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Novice：新手卡点判定与空态文案 */
class NoviceTest {

    private val allGood = Novice.stallHints(
        hasOverlay = true, hasApi = true, a11ySupported = true, a11yOn = true,
        orbRunning = true, everSolved = true, a11yConnected = true,
    )

    @Test fun allDoneMeansAllHintsChecked() {
        assertTrue(allGood.all { it.done })
    }

    @Test fun fiveHintsCoverCorePath() {
        assertEquals(5, allGood.size)
    }

    @Test fun missingOverlayIsFirstUndone() {
        val hints = Novice.stallHints(
            hasOverlay = false, hasApi = true, a11ySupported = true, a11yOn = true,
            orbRunning = true, everSolved = true, a11yConnected = true,
        )
        assertTrue(hints[0].text.contains("悬浮窗"))
        assertTrue(!hints[0].done)
        assertTrue(hints.first { !it.done } === hints[0])
    }

    @Test fun missingApiHintTellsWhereToFill() {
        val hints = Novice.stallHints(
            hasOverlay = true, hasApi = false, a11ySupported = true, a11yOn = true,
            orbRunning = true, everSolved = true, a11yConnected = true,
        )
        val h = hints.first { !it.done }
        assertTrue(h.action.contains("接口"))
    }

    @Test fun a11yUnsupportedSkipsGracefully() {
        // 系统太老：无障碍这条直接算「做完」（不给不可完成的任务）
        val hints = Novice.stallHints(
            hasOverlay = true, hasApi = true, a11ySupported = false, a11yOn = false,
            orbRunning = true, everSolved = true, a11yConnected = false,
        )
        assertTrue(hints[2].done)
    }

    @Test fun a11ySupportedButOffIsUndone() {
        val hints = Novice.stallHints(
            hasOverlay = true, hasApi = true, a11ySupported = true, a11yOn = false,
            orbRunning = true, everSolved = true, a11yConnected = false,
        )
        assertTrue(!hints[2].done)
        assertTrue(hints[2].action.contains("无障碍"))
    }

    @Test fun checklistDoneRequiresEverything() {
        assertTrue(Novice.checklistDone(true, true, true, true, true))
        assertTrue(!Novice.checklistDone(false, true, true, true, true))
        assertTrue(!Novice.checklistDone(true, true, true, true, false))   // 没成功识别过 → 还没上手
    }
}
