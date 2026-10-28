package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 主题包：序列化 / 反序列化 / 坏输入。
 *
 * 「分享」这个功能最怕的是**导入别人的包把 App 搞坏**。所以这组用例一半测正常路径，
 * 另一半测「粘进来一段垃圾会怎样」—— 答案必须是「拒绝 + 什么都不改」，不能崩。
 */
class ThemePackTest {

    private fun sample() = ThemePack.Pack(
        name = "深夜纸",
        colors = mapOf("bg" to 0x1C1C1C, "text" to 0xF2F2F0, "primary" to 0xE02B20),
        cornerDp = 0f, strokeDp = 1f, shadowDp = 0f, textSp = 16f, spacingDp = 10f,
    )

    @Test fun roundTripKeepsEverything() {
        val back = ThemePack.decode(ThemePack.encode(sample()));
        assertNotNull(back)
        assertEquals("深夜纸", back!!.name)
        assertEquals(0x1C1C1C, back.colors["bg"])
        assertEquals(0xF2F2F0, back.colors["text"])
        assertEquals(0xE02B20, back.colors["primary"])
        assertEquals(0f, back.cornerDp)
        assertEquals(1f, back.strokeDp)
        assertEquals(0f, back.shadowDp)
        assertEquals(16f, back.textSp)
        assertEquals(10f, back.spacingDp)
    }

    @Test fun encodeIsStable() {
        assertEquals(ThemePack.encode(sample()), ThemePack.encode(sample()))
    }

    @Test fun encodedHasPrefix() {
        assertTrue(ThemePack.encode(sample()).startsWith(ThemePack.PREFIX))
    }

    @Test fun rejectsEmptyAndNull() {
        assertNull(ThemePack.decode(null))
        assertNull(ThemePack.decode(""))
        assertNull(ThemePack.decode("   "))
    }

    @Test fun rejectsTextWithoutPrefix() {
        assertNull(ThemePack.decode("{\"v\":1,\"name\":\"x\"}"))
        assertNull(ThemePack.decode("随便一段话"))
        assertNull(ThemePack.decode("LINGTHEME:{}}"))
    }

    @Test fun rejectsBrokenJson() {
        assertNull(ThemePack.decode(ThemePack.PREFIX + "{not json"))
        assertNull(ThemePack.decode(ThemePack.PREFIX + "null"))
    }

    @Test fun rejectsOversizedInput() {
        val huge = ThemePack.PREFIX + "{\"name\":\"" + "x".repeat(ThemePack.MAX_LEN) + "\"}"
        assertNull(ThemePack.decode(huge))
    }

    @Test fun rejectsNewerVersion() {
        val future = ThemePack.PREFIX + "{\"v\":9,\"name\":\"来自未来\"}"
        assertNull(ThemePack.decode(future))
    }

    @Test fun missingColorsFallBackToDefaults() {
        val partial = ThemePack.PREFIX + "{\"v\":1,\"name\":\"只改了底\",\"colors\":{\"bg\":\"#123456\"}}"
        val got = ThemePack.decode(partial)
        assertNotNull(got)
        assertEquals(0x123456, got!!.colors["bg"])
        assertEquals("只改了底", got.name)
        assertEquals(Shape.BRUT.cornerDp, got.cornerDp)
    }

    @Test fun badColorValueIsDropped() {
        val bad = ThemePack.PREFIX + "{\"v\":1,\"colors\":{\"bg\":\"notacolor\",\"text\":\"#FFFFFF\"}}"
        val got = ThemePack.decode(bad)
        assertNotNull(got)
        assertNull("坏颜色应被丢掉", got!!.colors["bg"])
        assertEquals(0xFFFFFF, got.colors["text"])
    }

    @Test fun absurdShapeIsClamped() {
        val wild = ThemePack.PREFIX + "{\"v\":1,\"corner\":-99,\"stroke\":999,\"shadow\":999,\"textSp\":999}"
        val got = ThemePack.decode(wild)
        assertNotNull(got)
        assertTrue("圆角不能为负", got!!.cornerDp >= 0f)
        assertTrue("描边要有上限", got.strokeDp <= 8f)
        assertTrue("投影要有上限", got.shadowDp <= 12f)
        assertTrue("字号要有上限", got.textSp <= 26f)
    }

    @Test fun blankNameGivesPlaceholder() {
        val noName = ThemePack.PREFIX + "{\"v\":1,\"name\":\"  \"}"
        val got = ThemePack.decode(noName)
        assertNotNull(got)
        assertTrue("名字不能是空白", got!!.name.isNotBlank())
    }

    @Test fun fieldListIsUnique() {
        assertEquals(ThemePack.FIELDS.size, ThemePack.FIELDS.toSet().size)
    }

    @Test fun everyFieldSurvivesRoundTrip() {
        val all = ThemePack.FIELDS.mapIndexed { i, k -> k to (0x100000 + i * 0x010101) }.toMap()
        val pack = ThemePack.Pack(name = "全字段", colors = all)
        val back = ThemePack.decode(ThemePack.encode(pack))!!
        ThemePack.FIELDS.forEach { k ->
            assertEquals("字段 " + k + " 没能在往返后保留", all[k], back.colors[k])
        }
    }

    @Test fun hexFormatIsSixDigits() {
        assertEquals("#E02B20", ThemePack.hex(0xFFE02B20.toInt()))
        assertEquals("#000000", ThemePack.hex(0xFF000000.toInt()))
    }
}
