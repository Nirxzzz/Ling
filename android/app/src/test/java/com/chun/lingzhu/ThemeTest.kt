package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun parseHex() {
        assertEquals(0x1C1C1E, Theme.parseHex("#1c1c1e"))
        assertEquals(0xFFFFFF, Theme.parseHex("FFF"))
        assertEquals(0x00FF00, Theme.parseHex(" ＃0f0 "))
        assertNull(Theme.parseHex("#12345"))
        assertNull(Theme.parseHex("#GGGGGG"))
        assertNull(Theme.parseHex(""))
    }

    @Test fun hexRoundTrip() {
        assertEquals("#1C1C1E", Theme.hex(0x1C1C1E))
        assertEquals("#000000", Theme.hex(0xFF000000.toInt()))
        for ((_, c) in Theme.NAMED) assertEquals(c, Theme.parseHex(Theme.hex(c)))
    }

    @Test fun contrastAndBrightness() {
        assertEquals(21.0, Theme.contrast(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, Theme.contrast(0x777777, 0x777777), 0.001)
        assertEquals(100, Theme.brightness(0xFFFFFF))
        assertEquals(0, Theme.brightness(0x000000))
        assertEquals("很清楚", Theme.contrastWord(8.0))
        assertEquals("太淡，看不清", Theme.contrastWord(1.5))
    }

    // 全表（默认护眼 + 六套青春配色 + 各自的深色变体）正文 / 答案 / 标题都要读得清（渐变底两端都算）
    @Test fun presetsAreReadable() {
        for ((id, label, t) in Theme.PRESETS) {
            assertEquals(t, Theme.preset(id))
            assertTrue(label.isNotBlank())
            for (fg in listOf(t.text, t.answer, t.heading)) for (bg in t.bgs)
                assertTrue("$id 对比度不足", Theme.contrast(fg, bg) >= 7.0)
        }
        assertNull(Theme.preset(Theme.CUSTOM))
    }

    @Test fun derivedColors() {
        val t = Theme.DARK
        assertEquals(242, t.surface ushr 24)   // 95% 不透明
        assertEquals(0xFF shl 24 or 0xF2F2F2, t.tx1)
        assertEquals(0x99, t.tx3 ushr 24)
        assertEquals(0x1C1C1E, t.surface and 0xFFFFFF)
        assertEquals(4, t.orbStops.size)
        assertEquals(0x80123456.toInt(), Theme.argb(0x80, 0x123456))
        // alpha 必须一起插值：只拼 RGB 会得到 0x00808080 —— 十六进制看着正常、画到屏幕上却是全透明。
        // v2.5 底栏未选中那三格整格消失就是这个（旧断言写的正是 0x808080，把 bug 固化成了"期望"）。
        assertEquals(0xFF808080.toInt(), Theme.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF shl 24, Theme.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f) and 0xFF000000.toInt())
        // 半透明两端 → 中间也不透明以外地保留 alpha，不能变成 0
        assertEquals(0xC0, (Theme.mix(0xC0000000.toInt(), 0xC0FFFFFF.toInt(), 0.5f) ushr 24) and 0xFF)
    }

    // 默认「护眼」行为一字不改：薄荷绿 → 粉渐变、小球薄荷 → 粉；没选过 / 未知 id 都落它
    @Test fun defaultThemeIsEyeCareMintToPinkGradient() {
        assertEquals("eye", Theme.DEFAULT)
        assertEquals(Theme.DEFAULT, Theme.normId(null))            // 没选过
        assertEquals(Theme.DEFAULT, Theme.normId("已删掉的配色"))     // 未知 id → 默认
        assertEquals(Theme.CUSTOM, Theme.normId(Theme.CUSTOM))
        val t = Theme.EYE
        assertEquals(t, Theme.preset(Theme.DEFAULT))
        assertNotNull("护眼是渐变底", t.bg2)
        fun g(c: Int) = (c shr 8) and 0xFF
        fun r(c: Int) = (c shr 16) and 0xFF
        assertTrue("左上偏薄荷绿（绿 > 红）", g(t.bg) > r(t.bg))
        assertTrue("右下偏粉（红 > 绿）", r(t.bg2!!) > g(t.bg2!!))
        assertEquals(0x5FDFB4, t.orbA); assertEquals(0xF29FB0, t.orbB)
        assertEquals(t.bg, t.surface and 0xFFFFFF); assertEquals(t.bg2, t.surfaceEnd and 0xFFFFFF)
    }

    // 默认配色 / 自定义仍走老的派生渲染：palette 必须是 null
    @Test fun defaultPalettesStayNull() {
        assertNull(Theme.EYE.palette)
        assertNull(Theme.preset(Theme.DEFAULT)!!.palette)
        assertNull(Theme.DARK.palette)
        assertNull(Theme.preset("night_mint")!!.palette)
    }

    // Theme.copy 必须保住 palette（跟随系统深浅色 / 模式不透明度都靠 copy 派生）
    @Test fun copyPreservesPalette() {
        val t = Theme.preset("candy_pop")!!
        assertNotNull(t.palette)
        assertEquals(t.palette, t.copy(opacity = 90).palette)
    }

    // v2.0.3 迁移：老用户存着的老 id 读取时按色系就近落到六套新配色（存的值不主动改写）
    @Test fun oldThemeIdsMigrateToClosestFamily() {
        assertEquals(Theme.DEFAULT, Theme.normId(Theme.DEFAULT))   // 默认「护眼」不变
        // 冷蓝紫系 → 赛博
        assertEquals("cyber_pop", Theme.normId("dark"))
        assertEquals("cyber_pop", Theme.normId("night_deep"))
        assertEquals("cyber_pop", Theme.normId("ocean_deep"))
        // 薄荷绿系 → 清新
        assertEquals("fresh_pop", Theme.normId("morandi_sage"))
        assertEquals("fresh_pop", Theme.normId("macaron_mint"))
        // 粉紫系 → 糖果
        assertEquals("candy_pop", Theme.normId("morandi_rose"))
        assertEquals("candy_pop", Theme.normId("dopa_grape"))
        // 暖橘粉系 → 多巴胺
        assertEquals("dopamine_pop", Theme.normId("wabi_ash"))
        assertEquals("dopamine_pop", Theme.normId("retro_night"))
        // 柔蓝紫系 → 柔彩
        assertEquals("soft_digital", Theme.normId("light"))
        assertEquals("soft_digital", Theme.normId("macaron_taro"))
        // 灰调 → 酸绿
        assertEquals("acid_green", Theme.normId("morandi_ink"))
        // 未知 id → 默认护眼；自定义原样
        assertEquals(Theme.DEFAULT, Theme.normId("从来没存在过的id"))
        assertEquals(Theme.CUSTOM, Theme.normId(Theme.CUSTOM))
    }

    // 内置 night_mint 是默认「护眼」的深色伴生，必须原样可解析（不能被抓去迁移）
    @Test fun nightMintStaysResolvableForDefaultDark() {
        assertEquals("night_mint", Theme.normId("night_mint"))
        assertNotNull(Theme.preset("night_mint"))
    }

    // 六套青春配色浅色带 palette，深色变体是同色相（primary/secondary/highlight 不变）
    @Test fun sixPalettesKeepHueAcrossDarkVariant() {
        for (id in listOf("candy_pop", "cyber_pop", "fresh_pop", "dopamine_pop", "acid_green", "soft_digital")) {
            val light = Theme.preset(id)!!
            val dark = Theme.preset(Theme.darkVariantOf(id))!!
            assertNotNull("$id 要有 palette", light.palette)
            assertNotNull("$id 的深色变体要有 palette", dark.palette)
            assertFalse("$id 是浅色", light.isDark)
            assertTrue("$id 的深色变体要是深色", dark.isDark)
            assertEquals(light.palette!!.primary, dark.palette!!.primary)
            assertEquals(light.palette!!.secondary, dark.palette!!.secondary)
            assertEquals(light.palette!!.highlight, dark.palette!!.highlight)
        }
    }

    // 跟随系统深色：默认仍换到原 night_mint；六套换到各自深色；关掉 / 浅色系统 / 自定义的行为
    @Test fun darkVariantPreservesDefaultAndSixHues() {
        assertEquals("night_mint", Theme.darkVariantOf(Theme.DEFAULT))
        assertEquals("night_mint", Theme.effectiveId(Theme.DEFAULT, true, true))
        // 迁移后的老 id 也走同一套深色
        assertEquals("cyber_pop_dark", Theme.darkVariantOf("night_deep"))
        // 关掉跟随 → 原样不动；浅色系统 → 原样不动
        assertEquals("candy_pop", Theme.effectiveId("candy_pop", true, false))
        assertEquals("candy_pop", Theme.effectiveId("candy_pop", false, true))
        // 自定义没有伴生 → 兜底深色
        assertEquals(Theme.DARK_FALLBACK, Theme.darkVariantOf(Theme.CUSTOM))
    }
}
