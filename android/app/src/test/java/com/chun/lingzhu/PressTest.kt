package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一按压反馈（v1.84）的单测。
 *
 * 立这个文件的原因：按压反馈原来是三套各写各的，强度还都偏弱（缩放 4%、底色只暗约 12%、60ms 线性），
 * 用户反馈「点了像没反应」。现在强度收进 [Anim.Press] 一份，这里把它**钉死在下限上** ——
 * 以后谁再想调回「看不出来」的强度，测试先红。
 *
 * 纯逻辑，JVM 直跑（不碰 View / Animator）。
 */
class PressTest {

    private val dt60 = 1f / 60f

    /** 强度硬指标：缩小够狠、下沉够深、底色够暗（比旧的 ×0.88 更狠） */
    @Test fun pressStrengthIsPerceptible() {
        assertTrue("按下缩放太弱（要求 ≤0.93）：${Anim.Press.SCALE}", Anim.Press.SCALE <= 0.93f)
        assertTrue("按下没下沉（要求 ≥2dp）：${Anim.Press.SINK_DP}dp", Anim.Press.SINK_DP >= 2f)
        assertTrue("底色压得不够狠（要比 0.88 更狠）：${Anim.Press.FILL}", Anim.Press.FILL < 0.88f)
        assertTrue("把底色压没了：${Anim.Press.FILL}", Anim.Press.FILL > 0.5f)
    }

    /** 按下态：缩小 + 下沉**同时**在一个 [Anim.Visual] 里（交给同一个弹簧一起推，不会「先缩后沉」） */
    @Test fun pressDownSinksAndShrinksTogether() {
        val d = Anim.Press.down(2.75f)
        assertTrue("没缩小：${d.scale}", d.scale <= 0.93f)
        assertTrue("没往下沉（ty 必须为正）：${d.ty}", d.ty >= 2f * 2.75f)
        assertEquals("按压不该改不透明度", 1f, d.alpha, 0f)
        assertEquals("横向不该有位移", 0f, d.tx, 0f)
        // density 换算：1x 时下沉量精确等于 SINK_DP
        assertEquals(Anim.Press.SINK_DP, Anim.Press.down(1f).ty, 1e-6f)
        // 抬起回到标准显示态
        assertEquals(Anim.Visual.SHOW, Anim.Press.up)
    }

    /** 时序硬指标：按下弹簧收敛 **< 0.12s**，且明显快于抬起回弹 */
    @Test fun pressIsQuickerThanRelease() {
        val down = Anim.Press.downSpring.settleTime()
        val up = Anim.Press.upSpring.settleTime()
        assertTrue("按下弹簧不够快（要 <0.12s）：$down", down < 0.12f)
        assertTrue("抬起回弹没比按下慢一截：down=$down up=$up", up > down * 1.5f)
        assertTrue("抬起回弹快到看不出过程：$up", up >= 0.16f)
        assertTrue("抬起回弹拖沓：$up", up <= 0.26f)
    }

    /** 手感：按下干脆不过冲；抬起要「弹一下」（一点过冲），但不能夸张 */
    @Test fun pressDownHoldsStillAndReleaseBouncesALittle() {
        assertTrue("按下弹簧不该弹（ζ≥1）：ζ=${Anim.Press.downSpring.zeta}", Anim.Press.downSpring.zeta >= 1f)
        assertTrue("抬起弹簧要弹（ζ<1）：ζ=${Anim.Press.upSpring.zeta}", Anim.Press.upSpring.zeta < 1f)
        // 逐帧跑一遍抬起弹簧，量过冲（ζ=0.70 理论约 4.6%）
        var st = Anim.State(1f, 0f)
        var min = 0f
        repeat(400) { st = Anim.Press.upSpring.step(st, dt60); if (st.value < min) min = st.value }
        val overshoot = -min
        assertTrue("抬起没有过冲，回弹感没了：$overshoot", overshoot > 0.02f)
        assertTrue("抬起过冲太大：$overshoot", overshoot < 0.15f)
        // 按下弹簧从起点跑到参考时长不得越界（不过冲）
        var sd = Anim.State(1f, 0f)
        repeat(400) { sd = Anim.Press.downSpring.step(sd, dt60); assertTrue("按下弹簧过冲了：${sd.value}", sd.value >= -1e-4f) }
    }

    /** 底色变深：白底各通道**至少降 8%**（要一眼看得出），且 **alpha 原样保留** */
    @Test fun darkenCutsLuminanceOnWhiteAndKeepsAlpha() {
        val white = 0xFFFFFFFF.toInt()
        val pressed = Anim.Press.fill(white)
        assertEquals("alpha 不该被改", 0xFF, pressed ushr 24)
        for (shift in listOf(16, 8, 0)) {
            val src = white shr shift and 0xFF          // 255
            val dst = pressed shr shift and 0xFF
            assertTrue("通道 $shift 变深不足：$src → $dst", dst <= src * 92 / 100)
        }
        // 纯黑压不出负数 / 溢出
        assertEquals(0xFF000000.toInt(), Anim.Press.fill(0xFF000000.toInt()))
        // 半透明底色：alpha 必须原样保留（旧实现会强制写成不透明）
        assertEquals(0x80, Anim.Press.fill(0x80FFFFFF.toInt()) ushr 24)
        // 老入口 [Ui.darken] 只是转发，行为必须和规格一致
        assertEquals(pressed, Ui.darken(white))
    }
}
