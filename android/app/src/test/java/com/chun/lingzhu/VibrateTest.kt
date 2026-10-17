package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.86 震动：档位 / 节拍 / 振幅都是纯函数（不碰 android），全 JVM 直测。
 * Android 那层（[Vibrate.tick] / [Vibrate.remind]）不在这里 —— 它们只是把这里的数交给 Vibrator。
 */
class VibrateTest {

    @Test fun levelNormalizationFallsBackToDefault() {
        assertEquals(Vibrate.OFF, Vibrate.norm(Vibrate.OFF))
        assertEquals(Vibrate.STRONG, Vibrate.norm(Vibrate.STRONG))
        assertEquals(Vibrate.DEFAULT, Vibrate.norm(-99))
        assertEquals(Vibrate.DEFAULT, Vibrate.norm(99))
        assertEquals(Vibrate.MID, Vibrate.DEFAULT)
    }

    @Test fun labelsAreTheFourTiers() {
        assertEquals(listOf("关", "轻", "中", "重"), Vibrate.LEVELS.map { Vibrate.label(it) })
    }

    @Test fun offMeansSilentEverywhere() {
        assertEquals(0L, Vibrate.tickMs(Vibrate.OFF))
        assertEquals(0, Vibrate.tickAmp(Vibrate.OFF))
        assertTrue("关档不该还有节拍", Vibrate.pattern(Vibrate.OFF).isEmpty())
        assertEquals(0L, Vibrate.patternMs(Vibrate.OFF))
    }

    /** 中档必须还是 v1.80「试弹」那两下 —— 老用户升级后手感不变 */
    @Test fun midKeepsTheLegacyTryPopPattern() {
        assertEquals(TryPop.PATTERN.toList(), Vibrate.pattern(Vibrate.MID).toList())
    }

    @Test fun strongerTierIsLongerAndHarder() {
        val soft = Vibrate.patternMs(Vibrate.LIGHT)
        val mid = Vibrate.patternMs(Vibrate.MID)
        val hard = Vibrate.patternMs(Vibrate.STRONG)
        assertTrue("$soft < $mid", soft < mid)
        assertTrue("$mid < $hard", mid < hard)
        // 最强档也还是「拍一下肩膀」的量级：总时长压在半秒内
        assertTrue("最强档不该变成电话级长震：$hard", hard < 500)
    }

    @Test fun everyTierIsWellFormed() {
        for (l in Vibrate.LEVELS) {
            val p = Vibrate.pattern(l)
            if (l == Vibrate.OFF) continue
            assertEquals("节拍要成对（停、震、停、震）：$l", 0, p.size % 2)
            assertEquals("开头要先等系统一下：$l", 0L, p[0])
            assertTrue("除开头的等待之外每段都得是正数：$l", p.drop(1).all { it > 0 })
            // 振幅表要么没有，要么和节拍一样长（createWaveform 要求一一对应）
            Vibrate.amplitudes(l)?.let { assertEquals("振幅要和节拍一一对应：$l", p.size, it.size) }
        }
    }

    @Test fun tickIsAlwaysShort() {
        for (l in Vibrate.LEVELS) assertTrue("UI 反馈必须是极短的：$l=${Vibrate.tickMs(l)}", Vibrate.tickMs(l) <= 30)
    }

    @Test fun amplitudesAreABitMask() {
        for (l in Vibrate.LEVELS) Vibrate.amplitudes(l)?.forEach {
            assertTrue("振幅要么 0（静默段）要么 1..255：$it", it in 0..255)
        }
    }
}
