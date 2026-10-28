package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 瑞士国际主义风格的**消费侧**回归。
 *
 * 这里刻意不含「圆角是 0 / 描边是 1」这类常量复述，也不含「某个 id 在画廊里」的接线复述 ——
 * 那些是实现的另一种写法，实现改了它们只会一起改，守不住任何东西。
 * 留下的是「用户会真的踩到」的三类：
 *  1. 存过的瑞士 id 能被重新解析（老数据不会被打回默认）；
 *  2. 文字（正文 / 次级 / hint / 按钮字）在纸底上读得清 —— 瑞士没有边框帮忙分隔，这一条格外要紧；
 *  3. 跟随系统深色时瑞士仍是瑞士（颜色换成深色版，形状也得跟着留在瑞士，不能落进别人的兜底）。
 */
class SwissThemeTest {

    @Test fun swissIdSurvivesNormalization() {
        // 存进去再读出来不能被打回默认（normId 有迁移逻辑，新 id 别被误伤）
        assertEquals("swiss", Theme.normId("swiss"))
        assertEquals("swiss_dark", Theme.normId("swiss_dark"))
    }

    @Test fun swissTextContrastIsReadable() {
        // 直角细线少了视觉分隔，正文对比度必须够（AA 正文标准 4.5）
        assertTrue("瑞士浅色正文对比度不足：" + Theme.SWISS.minContrast(), Theme.SWISS.minContrast() >= 4.5)
        assertTrue("瑞士深色正文对比度不足：" + Theme.SWISS_DARK.minContrast(), Theme.SWISS_DARK.minContrast() >= 4.5)
    }

    @Test fun swissDerivedContrastIsReadable() {
        // 提示色 / 分隔线也要能看清 —— 瑞士风格没有边框帮忙分隔，这一条更要紧
        assertTrue("瑞士派生色对比度不足：" + Theme.SWISS.derivedContrast(), Theme.SWISS.derivedContrast() >= 3.0)
        assertTrue("瑞士深色派生色对比度不足：" + Theme.SWISS_DARK.derivedContrast(), Theme.SWISS_DARK.derivedContrast() >= 3.0)
    }

    @Test fun swissHintLabelsAreReadableOnPaper() {
        // hint 是 Brut.label / 输入框 hint / 状态行唯一的那一档灰，量和用是同一件事：
        // 11sp 小字要过 4.5（浅色版原来 0.55 混合只有 4.06，被这条盯过）。
        listOf("swiss" to Theme.SWISS, "swiss_dark" to Theme.SWISS_DARK).forEach { (id, t) ->
            val pal = t.palette!!
            val c = Theme.contrast(pal.hint, pal.paper)
            assertTrue("$id 的 hint 在纸底上读不清：$c", c >= 4.5)
        }
    }

    @Test fun swissButtonColorIsReadable() {
        // 主按钮是信号红实心，上面压白字 —— 只测瑞士自己的两套。
        // （不遍历全部配色：candy_pop 那套主色的白字对比度本来就偏低，
        //   那是上一轮就存在的情况，不属于本轮要守的范围，改成遍历会变成一个与本轮无关的失败。）
        listOf("swiss" to Theme.SWISS, "swiss_dark" to Theme.SWISS_DARK).forEach { (id, t) ->
            val pal = t.palette!!
            val c = Theme.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt() or pal.primary)
            assertTrue("$id 的主色上白字对比度不够：" + c, c >= 3.0)
        }
    }

    /**
     * 跟随系统深色时瑞士必须**还是瑞士**。
     *
     * 这条守的是一个真实故障：浅色预设靠 [Theme.DARK_COMPANION] 找深色伴生，表里漏了 swiss 时
     * 会落进 [Theme.DARK_FALLBACK]（赛博蓝）—— 颜色变了，而 Prefs.effectiveShape 是拿**生效 id**
     * 去问 Theme.isSwiss 的，于是形状一起失守，页面变成「赛博蓝 + 6dp 圆角」。
     * 断言落在消费链上：生效 id → 预设 → 仍判为瑞士 + 确实是深色，而不是复述映射表本身。
     */
    @Test fun followSystemDarkKeepsSwissSemantics() {
        val eff = Theme.effectiveId("swiss", systemDark = true, follow = true)
        assertNotEquals("瑞士的深色伴生不能是别的配色的兜底", Theme.DARK_FALLBACK, eff)
        assertTrue("生效 id 必须仍被判成瑞士（形状才能留在直角无投影）：$eff", Theme.isSwiss(eff))
        val t = Theme.preset(eff)
        assertTrue("瑞士深色版必须已注册：$eff", t != null)
        assertTrue("$eff 必须真的是深色", t!!.isDark)
        assertNotEquals("深色版不能是浅色版自己（那就没有换肤色）", "swiss", eff)
        // 关掉跟随 / 浅色系统时原样不动
        assertEquals("swiss", Theme.effectiveId("swiss", systemDark = true, follow = false))
        assertEquals("swiss", Theme.effectiveId("swiss", systemDark = false, follow = true))
    }
}
