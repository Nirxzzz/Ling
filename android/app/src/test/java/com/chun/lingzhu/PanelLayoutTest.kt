package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    // 1080×2400、密度 2.75 的普通手机
    private val s = PanelLayout.Screen(1080, 2400, 2.75f, 66, 44)
    private fun dp(v: Float) = (v * 2.75f + 0.5f).toInt()

    @Test fun bottomDefaultSameAsBefore() {
        val sp = PanelLayout.compute(PanelLayout.Options(), s, null)
        assertEquals(PanelLayout.V.BOTTOM, sp.v)
        assertEquals(1080 - dp(12f), sp.width)
        assertEquals(44 + dp(6f), sp.y)
        assertEquals(2400 - sp.y - 66 - dp(8f), sp.maxHeight)
        assertEquals(false, sp.gripBottom)
        assertEquals(1, sp.enterY)
    }

    @Test fun topGrowsDownGripBelow() {
        val sp = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.TOP, offsetDp = 20), s, null)
        assertEquals(PanelLayout.V.TOP, sp.v)
        assertEquals(66 + dp(6f) + dp(20f), sp.y)
        assertTrue(sp.gripBottom)
        assertEquals(-1, sp.enterY)
    }

    @Test fun sidesSlideInFromTheirEdge() {
        val l = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.LEFT, sideWidthPct = 60), s, null)
        val r = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.RIGHT, sideWidthPct = 60), s, null)
        assertEquals(PanelLayout.H.START, l.h); assertEquals(-1, l.enterX)
        assertEquals(PanelLayout.H.END, r.h); assertEquals(1, r.enterX)
        assertEquals(((1080 - dp(12f)) * 0.6f).toInt(), l.width)
    }

    @Test fun awayPicksOppositeHalf() {
        val up = PanelLayout.Orb(1000, 300, dp(58f))
        val down = PanelLayout.Orb(1000, 1900, dp(58f))
        assertEquals(PanelLayout.BOTTOM, PanelLayout.resolve(PanelLayout.AWAY, s, up))
        assertEquals(PanelLayout.TOP, PanelLayout.resolve(PanelLayout.AWAY, s, down))
    }

    @Test fun orbPanelSitsBesideOrbWithoutCoveringIt() {
        val size = dp(58f)
        val right = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.ORB), s, PanelLayout.Orb(1080 - size - 11, 500, size))
        assertEquals(PanelLayout.H.END, right.h)
        assertEquals(PanelLayout.V.TOP, right.v)
        assertEquals(500, right.y)
        assertTrue("面板右边缘在小球左边", right.x >= size + dp(6f))
        val lowLeft = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.ORB), s, PanelLayout.Orb(11, 2000, size))
        assertEquals(PanelLayout.H.START, lowLeft.h)
        assertEquals(PanelLayout.V.BOTTOM, lowLeft.v)
        assertEquals(2400 - 44 - (2000 + size), lowLeft.y)   // 从导航条上沿往上量，面板底边对齐小球底边
    }

    @Test fun freeRoundTripsAndStaysOnScreen() {
        val (fx, fy) = PanelLayout.toFree(s, 216, 960)
        val sp = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.FREE, freeX = fx, freeY = fy, freeWidthPct = 50), s, null)
        assertEquals(216, sp.x); assertEquals(960, sp.y)
        // 记的位置太靠右下（比如横屏记的、竖屏再开）：拉回屏幕里
        val off = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.FREE, freeX = 990, freeY = 999, freeWidthPct = 80), s, null)
        assertTrue(off.x + off.width <= 1080)
        assertTrue(off.y + dp(120f) <= 2400 - 44)
    }

    @Test fun heightsKeepFullAboveHalf() {
        assertEquals(42 to 80, PanelLayout.heights(42, 80))
        assertEquals(70 to 80, PanelLayout.heights(90, 50))
        assertEquals(20 to 40, PanelLayout.heights(1, 1))
    }

    @Test fun unknownPositionFallsBackToBottom() {
        assertEquals(PanelLayout.BOTTOM, PanelLayout.norm("diagonal"))
        assertEquals(PanelLayout.BOTTOM, PanelLayout.norm(null))
        // v1.66：没选过位置时面板默认从左侧滑出（Prefs 的默认值）
        assertEquals(PanelLayout.LEFT, PanelLayout.DEFAULT)
        val left = PanelLayout.compute(PanelLayout.Options(pos = PanelLayout.DEFAULT), PanelLayout.Screen(1080, 2400, 2.75f, 128, 63), null)
        assertEquals(PanelLayout.H.START, left.h); assertEquals(-1, left.enterX)
    }

    // ---- v1.85：面板只剩「半屏 / 大屏」两档（用户说最小那一档没啥用，砍掉）----

    @Test fun toggleSwitchesBetweenHalfAndFullOnly() {
        assertEquals(PanelLayout.STATE_FULL, PanelLayout.toggle(PanelLayout.STATE_HALF))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.toggle(PanelLayout.STATE_FULL))
        // 收起档（0）/ 越界值 / 任何别的值 → 一律落到半屏，永远停不回最小那一档
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.toggle(0))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.toggle(-3))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.toggle(99))
    }

    @Test fun nextStateClampsToHalfAndFull() {
        // 往外拖（+1）到大屏就顶住；往回拖（-1）到半屏就顶住 —— 不会掉回收起档
        assertEquals(PanelLayout.STATE_FULL, PanelLayout.nextState(PanelLayout.STATE_HALF, 1))
        assertEquals(PanelLayout.STATE_FULL, PanelLayout.nextState(PanelLayout.STATE_FULL, 1))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.nextState(PanelLayout.STATE_FULL, -1))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.nextState(PanelLayout.STATE_HALF, -1))
        // 收起档（0）起步也一样：拖一下就到半屏，不会更小
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.nextState(0, 0))
        assertEquals(PanelLayout.STATE_HALF, PanelLayout.nextState(0, -1))
    }
}
