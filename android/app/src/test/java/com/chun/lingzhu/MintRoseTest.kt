package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5 薄荷玫瑰 · Mint Rose Atelier。
 *
 * 这组用例守的是「视觉重做的硬要求」，每一条都能在 CI 里跑，不靠人眼：
 * 1. **分层圆角** —— 列表 / 按钮 / 标签 / 弹窗必须是四档不同的圆角，
 *    不能像瑞士那样四角统一，否则又退回「一套皮肤到处套」。
 * 2. **深色不是反转** —— 深色是深森林绿（偏绿、不是纯灰黑），而且它的底色
 *    不等于把浅色底取反。反转出来的深色看起来是「关了灯的同一套界面」。
 * 3. **用色比例 60/25/10/5** —— 大面积必须是纸底/卡片这种中性色，
 *    薄荷绿只占据「次大面积」，玫瑰金只能是小面积点缀。玫瑰金一旦铺开就成了粉色。
 * 4. **老用户外观不变** —— 新装默认薄荷玫瑰，但存过别的配色的人不能被悄悄换皮肤。
 */
class MintRoseTest {

    // ── 分层圆角 ─────────────────────────────────────────

    @Test fun atelierShapeUsesFourDistinctCorners() {
        val s = Shape.ATELIER
        val corners = listOf(s.cardCornerDp, s.buttonCornerDp, s.chipCornerDp, s.sheetCornerDp)
        assertEquals("四档圆角必须互不相同", 4, corners.toSet().size)
        assertTrue("标签是胶囊", s.chipCornerDp >= 100f)
        assertTrue("弹窗圆角应该比卡片大", s.sheetCornerDp > s.cardCornerDp)
        assertTrue("按钮圆角应该比卡片小", s.buttonCornerDp < s.cardCornerDp)
    }

    @Test fun atelierShapeReportsItselfAsLayered() {
        assertTrue("ATELIER 必须被识别为分层圆角", Shape.ATELIER.isLayered)
        assertFalse("BRUT 不能是分层圆角（历史值一个都不能变）", Shape.BRUT.isLayered)
        assertFalse("SWISS 不能是分层圆角", Shape.SWISS.isLayered)
    }

    @Test fun defaultShapeUnchangedForOldUsers() {
        // 新皮肤不能顺手改掉老形状的数值
        assertEquals(6f, Shape.BRUT.cornerDp, 0f)
        assertEquals(2f, Shape.BRUT.strokeDp, 0f)
        assertEquals(4f, Shape.BRUT.shadowDp, 0f)
    }

    // ── 配色注册 ─────────────────────────────────────────

    @Test fun presetsAreRegistered() {
        assertEquals(Theme.MINT_ROSE, Theme.preset("mint_rose"))
        assertEquals(Theme.MINT_ROSE_DARK, Theme.preset("mint_rose_dark"))
    }

    @Test fun lightVariantIsInGalleryAndDarkIsItsCompanion() {
        // 画廊按项目既有约定只放浅色版：深色变体不进画廊，只在系统切深色时自动顶上来
        assertTrue("mint_rose 要在画廊里", "mint_rose" in Theme.HOT_IDS)
        assertFalse("深色变体不进画廊（跟随系统时才用）", "mint_rose_dark" in Theme.HOT_IDS)
        assertTrue("但深色变体必须注册成预设", Theme.preset("mint_rose_dark") != null)
    }

    @Test fun darkCompanionPointsToDeepForest() {
        assertEquals("mint_rose_dark", Theme.DARK_COMPANION["mint_rose"])
    }

    @Test fun isAtelierRecognizesBoth() {
        assertTrue(Theme.isAtelier("mint_rose"))
        assertTrue(Theme.isAtelier("mint_rose_dark"))
        assertFalse(Theme.isAtelier("swiss"))
        assertFalse(Theme.isAtelier(null))
    }

    @Test fun presetIdsSurviveNormalization() {
        // 存进去再读出来不能被打回默认（normId 有迁移逻辑，新 id 别被误伤）
        assertEquals("mint_rose", Theme.normId("mint_rose"))
        assertEquals("mint_rose_dark", Theme.normId("mint_rose_dark"))
    }

    // ── 深色不是反转 ─────────────────────────────────────

    @Test fun darkIsDeepForestNotInvertedLight() {
        val light = Theme.MINT_ROSE
        val dark = Theme.MINT_ROSE_DARK
        // 反转的话深色底 ≈ 0xFFFFFF - 0xF7FAF7 ≈ 0x080508（近黑无彩）
        val inverted = 0xFFFFFF xor light.bg
        assertNotEquals("深色底不能是浅色底的反转", inverted, dark.bg)
        // 深森林绿：G 通道必须是三者里最大的（绿多过红、多过蓝）
        val r = (dark.bg shr 16) and 0xFF
        val g = (dark.bg shr 8) and 0xFF
        val b = dark.bg and 0xFF
        assertTrue("深色底要偏绿：G($g) 应大于 R($r)", g > r)
        assertTrue("深色底要偏绿：G($g) 应大于 B($b)", g > b)
        assertTrue("深色底要明显暗", Theme.brightness(dark.bg) < 20)
    }

    @Test fun darkTextIsNotPureWhite() {
        // 纯白在深底上会显得刺眼，浅一点的绿白才像「深森林」而不是「关灯的黑板」
        val t = Theme.MINT_ROSE_DARK
        assertNotEquals(0xFFFFFF, Theme.argb(0xFF, t.text))
        assertNotEquals(0xFFFFFF, Theme.argb(0xFF, t.palette!!.ink))
    }

    @Test fun darkHasNoLargeGradient() {
        // 大面积金属渐变是明确禁止的
        assertNull("浅色不靠大面积渐变", Theme.MINT_ROSE.bg2)
        assertNull("深色也不靠", Theme.MINT_ROSE_DARK.bg2)
    }

    // ── 用色比例 ─────────────────────────────────────────

    @Test fun neutralsDominateThePalette() {
        val pal = Theme.MINT_ROSE.palette!!
        // 60% 纸底：最亮、最中性
        assertTrue("纸底要够亮", Theme.luminance(pal.paper) > 0.85)
        // 25% 薄荷绿：次大面积，必须是主色且够亮（不能压成深墨绿）
        assertTrue("薄荷绿要亮", Theme.luminance(pal.primary) > 0.4)
        // 10% 玫瑰金：比薄荷绿暗一点、比墨色亮，属于中间档
        val rose = Theme.luminance(pal.roseDeep)
        assertTrue("玫瑰金要比薄荷绿深", rose < Theme.luminance(pal.primary))
        assertTrue("玫瑰金不能沉到墨色那个档", rose > Theme.luminance(pal.ink))
    }

    @Test fun roseGoldIsNotPink() {
        val rose = Theme.MINT_ROSE.palette!!.roseDeep
        val r = (rose shr 16) and 0xFF
        val g = (rose shr 8) and 0xFF
        val b = rose and 0xFF
        // 粉色是 R 高、G/B 都低且 B 明显低于 G；玫瑰金要求红绿蓝都起来，只是红略高
        assertTrue("红要最高", r > g && r > b)
        assertTrue("绿蓝不能塌到 0（那是正红/玫红）", g > 0x60 && b > 0x60)
        assertTrue("红不能比绿高太多（否则偏橘粉）", r - g < 0x50)
    }

    @Test fun accentColorsAreDistinct() {
        val pal = Theme.MINT_ROSE.palette!!
        val accents = listOf(pal.primary, pal.roseDeep, pal.lavender, pal.glacier, pal.coral, pal.butter)
        assertEquals("六个强调色不能撞色", accents.size, accents.toSet().size)
    }

    // ── 主题识别与默认 ───────────────────────────────────

    @Test fun paletteMarksItselfAsAtelier() {
        assertTrue("新色板的 surfaceAlt 必须和纸底不同，靠它区分新旧", Theme.MINT_ROSE.palette!!.isAtelier)
        assertFalse("旧色板必须仍然不是 atelier", Theme.EYE.palette?.isAtelier ?: false)
    }

    @Test fun oldPresetsStayUntouched() {
        assertEquals("eye", Theme.DEFAULT)
        assertEquals("没选过 → 默认（Prefs 层再决定新装给谁）", Theme.DEFAULT, Theme.normId(null))
        assertEquals(6f, Shape.BRUT.cornerDp, 0f)
    }

    // ── mistakeCount：不能逐卡拼 key ─────────────────────

    @Test fun mistakeCountCountsOnlySeenButUnstreaked() {
        val st = mapOf(
            "a\u0000one" to Words.Stat(streak = 0, seen = 3),    // 不认识
            "a\u0000two" to Words.Stat(streak = 2, seen = 2),    // 认识
            "a\u0000three" to Words.Stat(streak = 0, seen = 0),  // 没见过
        )
        assertTrue(Words.unknown(st["a\u0000one"]))
        assertFalse(Words.unknown(st["a\u0000two"]))
        assertFalse("没见过的不算错词", Words.unknown(st["a\u0000three"]))
        assertFalse(Words.unknown(null))
    }

    @Test fun keyPrefixSplitRoundTrips() {
        // mistakeCount 靠「切掉 deck + \u0000 前缀」来反查卡片，这条守住切法本身
        val k = Words.key("deck_x", Words.Card("hello", "你好"))
        val cut = k.indexOf('\u0000')
        assertEquals("deck_x", k.substring(0, cut))
        assertEquals("hello", k.substring(cut + 1))
    }
}
