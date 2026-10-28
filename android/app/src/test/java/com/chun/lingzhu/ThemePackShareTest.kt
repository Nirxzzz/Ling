package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 主题包的**现实场景**：用户之间来回发一个包。
 *
 * ThemePackTest 测的是单次往返；这里测的是「真实用法」——
 * A 导出 → 聊天软件里转一手（可能被加空格/换行/转义）→ B 粘贴导入。
 */
class ThemePackShareTest {

    private fun pack() = ThemePack.Pack(
        name = "纸与红",
        colors = mapOf("bg" to 0xF2F2F0, "text" to 0x111111, "primary" to 0xE02B20),
        cornerDp = 0f, strokeDp = 1f, shadowDp = 0f, textSp = 15f, spacingDp = 12f,
    )

    @Test fun survivesLeadingAndTrailingWhitespace() {
        // 从聊天窗口复制，十有八九带前后空格或换行
        val code = ThemePack.encode(pack())
        assertNotNull(ThemePack.decode("  " + code + "\n"))
        assertNotNull(ThemePack.decode("\n\n" + code))
    }

    @Test fun survivesChatAppEscaping() {
        // 有些聊天软件会把引号转义成 \" —— 那就不认了（明确拒绝比猜坏）
        val code = ThemePack.encode(pack())
        assertNotNull(ThemePack.decode(code))
        val broken = code.replace("\"", "\\\"")
        assertNull("被转义坏了的包应当拒绝而不是猜", ThemePack.decode(broken))
    }

    @Test fun reExportAfterImportIsStable() {
        // A 导出 → B 导入 → B 再导出，两段文本必须一样。
        // 不一样就说明中间某处悄悄改了值（比如颜色精度、小数位），用户会发现「传了一圈变淡了」
        val first = ThemePack.encode(pack())
        val mid = ThemePack.decode(first)!!
        val second = ThemePack.encode(mid)
        assertEquals("转一圈之后必须一模一样", first, second)
    }

    @Test fun shapeIsPreservedExactly() {
        // 瑞士风格的关键就是 corner=0/shadow=0，任何一个没保住就变回圆角
        val swiss = ThemePack.Pack(name = "瑞士", cornerDp = 0f, strokeDp = 1f, shadowDp = 0f, textSp = 15f, spacingDp = 12f)
        val back = ThemePack.decode(ThemePack.encode(swiss))!!
        assertEquals(0f, back.cornerDp)
        assertEquals(1f, back.strokeDp)
        assertEquals(0f, back.shadowDp)
    }

    @Test fun brutShapeIsPreservedExactly() {
        // 反向也要过：粗野的 6/2/4 不能因为浮点误差变成 5.99
        val brut = ThemePack.Pack(name = "粗野", cornerDp = 6f, strokeDp = 2f, shadowDp = 4f)
        val back = ThemePack.decode(ThemePack.encode(brut))!!
        assertEquals(Shape.BRUT.cornerDp, back.cornerDp)
        assertEquals(Shape.BRUT.strokeDp, back.strokeDp)
        assertEquals(Shape.BRUT.shadowDp, back.shadowDp)
    }

    @Test fun longNameIsTruncated() {
        // 名字只用于显示，截短比撑破界面好
        val long = ThemePack.Pack(name = "名".repeat(100))
        val back = ThemePack.decode(ThemePack.encode(long))!!
        assertTrue("名字不能无限长", back.name.length <= 24)
    }

    @Test fun emptyThemeStillDecodes() {
        // 一个只有名字、什么颜色都没有的包：能导入，等于「不改颜色只改形状」
        val bare = ThemePack.Pack(name = "只改形状", cornerDp = 2f)
        val back = ThemePack.decode(ThemePack.encode(bare))
        assertNotNull(back)
        assertEquals(2f, back!!.cornerDp)
        assertTrue("没有颜色字段是合法的", back.colors.isEmpty())
    }
}
