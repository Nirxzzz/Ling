package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7.3 液态玻璃 · Liquid Glass —— 只写「用户会真的踩到」的消费侧行为：
 *  1. 跟随系统深浅色的生效 id 与归一化（配错表就会掉进别人的兜底配色）。
 *  2. 真实黑白底合成边界：玻璃没有实时模糊，可读性只能靠「卡面自身够不透明」——
 *     卡面 / 面板底的**渐变实际端点**（[Theme.glassTop] / [Theme.glassBottom] 的输出，不是卡面原色）
 *     分别合成到纯黑 / 纯白两极底色上量正文 / 答案 / 标题对比 >= 7，出货 alpha 与
 *     [Theme.glassFloorAlpha] 下限各算一遍。
 *  3. hint >= 4.5 与色相可读性（蓝 / 青主色、墨蓝深底，不是反转也不是默认薄荷）。
 */
class GlassThemeTest {

    /** 跟随系统深色的生效链：开关 / 深浅系统 / 深色自伴生 / 归一化不回默认 —— 一条消费行为串起来 */
    @Test fun glassFollowSystemDarkBehavior() {
        assertEquals("liquid_glass_dark", Theme.effectiveId("liquid_glass", systemDark = true, follow = true))
        assertEquals("liquid_glass", Theme.effectiveId("liquid_glass", systemDark = true, follow = false))
        assertEquals("liquid_glass", Theme.effectiveId("liquid_glass", systemDark = false, follow = true))
        // 深色版的伴生是它自己（已是深色，不该被换走）
        assertEquals("liquid_glass_dark", Theme.darkVariantOf("liquid_glass_dark"))
        // 归一化：新 id 存进去读出来不能被当「未知」打回默认护眼
        assertEquals("liquid_glass", Theme.normId("liquid_glass"))
        assertEquals("liquid_glass_dark", Theme.normId("liquid_glass_dark"))
        // 深色伴生只走跟随系统，不进画廊（与 mint_rose / swiss 同一约定）
        assertTrue("liquid_glass" in Theme.HOT_IDS)
        assertFalse("liquid_glass_dark" in Theme.HOT_IDS)
    }

    /** 把端点色按 alpha 合成到底色上（纯 Int，和 Theme.mix 同一套位运算） */
    private fun over(baseRgb: Int, alpha: Float, cardRgb: Int): Int = Theme.argb(0xFF, Theme.mix(baseRgb, cardRgb, alpha))

    @Test fun glassBodyTextSurvivesCompositeOverPureBlackAndWhite() {
        for ((id, t) in listOf("liquid_glass" to Theme.LIQUID_GLASS, "liquid_glass_dark" to Theme.LIQUID_GLASS_DARK)) {
            // 正文 / 答案 / 标题坐在两层玻璃底上：卡面和面板底（surface）都量
            for ((fillName, fill) in listOf("card" to t.card, "surface" to t.surface)) {
                val shipped = (fill ushr 24) and 0xFF
                // 卡面出货透明度不得低于可读性下限；面板底出货更淡、渲染时兜到下限 —— 两档 alpha 都量
                if (fillName == "card") assertTrue(
                    "$id 实际出货的卡面透明度($shipped)不得低于可读性下限(${Theme.glassFloorAlpha})",
                    shipped >= Theme.glassFloorAlpha,
                )
                for (a in listOf(shipped, Theme.glassFloorAlpha)) {
                    val layer = Theme.argb(a, fill)
                    // 端点取自生产函数，测试不抄派生算法
                    for (ep in listOf(t.glassTop(layer), t.glassBottom(layer))) {
                        for (base in listOf(0x000000, 0xFFFFFF)) {
                            val bg = over(base, ((ep ushr 24) and 0xFF) / 255f, ep and 0xFFFFFF)
                            for (fg in listOf(t.text, t.answer, t.heading)) {
                                val c = Theme.contrast(fg, bg)
                                assertTrue("$id $fillName 端点 ${Theme.hex(ep)}(a=$a)合成到 ${Theme.hex(base)} 底上 ${Theme.hex(fg)} 对比不足：$c", c >= 7.0)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun glassHintStaysReadableOnPaper() {
        listOf("liquid_glass" to Theme.LIQUID_GLASS, "liquid_glass_dark" to Theme.LIQUID_GLASS_DARK).forEach { (id, t) ->
            val c = Theme.contrast(t.palette!!.hint, t.palette.paper)
            assertTrue("$id 的 hint 在纸底上读不清：$c", c >= 4.5)
        }
    }

    @Test fun glassHueIsBlueCyanOnInkBlueNotMint() {
        val light = Theme.LIQUID_GLASS
        val dark = Theme.LIQUID_GLASS_DARK
        fun blueish(c: Int): Boolean {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return b > g && g > r
        }
        assertTrue("玻璃主色要偏蓝 / 青（蓝 > 绿 > 红）", blueish(light.palette!!.primary))
        assertNotEquals("玻璃主色不能和默认薄荷撞色", Theme.MINT_ROSE.palette!!.primary, light.palette.primary)
        assertTrue("深色底要是墨蓝（蓝 > 绿 > 红）", blueish(dark.bg))
        assertTrue("深色版必须真的是深色", dark.isDark)
        assertFalse("浅色版必须真的是浅色", light.isDark)
        assertNotEquals("深色底不能是浅色底的反转", 0xFFFFFF xor light.bg, dark.bg)
    }
}
