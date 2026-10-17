package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.82：弹词间隔 / 卡片停留都按「秒」自定义。这里测两件纯逻辑，JVM 直跑、不碰 SharedPreferences：
 *  1. [Prefs.fmtInterval] 的三档显示（秒 / 整数分钟 / 小数分钟）在边界上都不出岔子；
 *  2. 两对新常量的区间自检（MIN < DEF < MAX）—— 防止以后改常量改出矛盾区间。
 */
class PrefsIntervalTest {

    @Test fun fmtIntervalUnderAMinuteStaysSeconds() {
        // < 60 秒：直接报秒，不做任何折算
        assertEquals("5 秒", Prefs.fmtInterval(5))
        assertEquals("15 秒", Prefs.fmtInterval(15))
        assertEquals("59 秒", Prefs.fmtInterval(59))
    }

    @Test fun fmtIntervalWholeMinutes() {
        // 60 的整数倍：报「N 分钟」，不出现小数点
        assertEquals("1 分钟", Prefs.fmtInterval(60))
        assertEquals("15 分钟", Prefs.fmtInterval(900))      // 默认档 = 15 分钟
        assertEquals("60 分钟", Prefs.fmtInterval(3600))     // 1 小时
        assertEquals("1440 分钟", Prefs.fmtInterval(86400))  // 24 小时 = 上限
    }

    @Test fun fmtIntervalFractionalMinutes() {
        // 非整分钟：保留一位小数
        assertEquals("1.5 分钟", Prefs.fmtInterval(90))
        assertEquals("2.5 分钟", Prefs.fmtInterval(150))
    }

    @Test fun fmtIntervalCoversEveryTierBoundary() {
        // 三档在切换点各测一次，确认不会把 59/60/61 归错档
        assertEquals("59 秒", Prefs.fmtInterval(59))
        assertEquals("1 分钟", Prefs.fmtInterval(60))
        assertEquals("1.0 分钟", Prefs.fmtInterval(61))
    }

    @Test fun intervalConstantsFormAValidRange() {
        // 弹词间隔：5 秒 < 默认 900 秒 < 上限 86400 秒
        assertTrue(Prefs.WORD_EVERY_SEC_MIN < Prefs.WORD_EVERY_SEC_DEF)
        assertTrue(Prefs.WORD_EVERY_SEC_DEF < Prefs.WORD_EVERY_SEC_MAX)
        // 卡片停留：3 秒 < 默认 20 秒 < 上限 600 秒
        assertTrue(Prefs.WORD_STAY_SEC_MIN < Prefs.WORD_STAY_SEC_DEF)
        assertTrue(Prefs.WORD_STAY_SEC_DEF < Prefs.WORD_STAY_SEC_MAX)
    }

    @Test fun defaultIntervalsAreInsideTheirRanges() {
        // 默认值本身必须落在区间里，否则 clamp 会把它改掉，读出来就不是「默认」了
        assertTrue(Prefs.WORD_EVERY_SEC_DEF in Prefs.WORD_EVERY_SEC_MIN..Prefs.WORD_EVERY_SEC_MAX)
        assertTrue(Prefs.WORD_STAY_SEC_DEF in Prefs.WORD_STAY_SEC_MIN..Prefs.WORD_STAY_SEC_MAX)
    }
}
