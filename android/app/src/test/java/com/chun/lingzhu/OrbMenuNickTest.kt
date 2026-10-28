package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 菜单项昵称 + 「停止小球」文案瘦身。
 */
class OrbMenuNickTest {

    // ── 昵称优先级 ───────────────────────────────────────

    @Test fun noNickGivesDefaultLabel() {
        assertEquals("框选识别", OrbMenu.labelOf(OrbMenu.ID_REGION, emptyMap()))
    }

    @Test fun nickOverridesDefault() {
        assertEquals("圈一下", OrbMenu.labelOf(OrbMenu.ID_REGION, mapOf(OrbMenu.ID_REGION to "圈一下")))
    }

    @Test fun blankNickFallsBackToDefault() {
        // 存了空串等于没设 —— 不能显示成一行空白（那行就点不着了）
        assertEquals("框选识别", OrbMenu.labelOf(OrbMenu.ID_REGION, mapOf(OrbMenu.ID_REGION to "")))
        assertEquals("框选识别", OrbMenu.labelOf(OrbMenu.ID_REGION, mapOf(OrbMenu.ID_REGION to "   ")))
    }

    @Test fun nickIsTrimmed() {
        assertEquals("圈一下", OrbMenu.labelOf(OrbMenu.ID_REGION, mapOf(OrbMenu.ID_REGION to "  圈一下  ")))
    }

    // ── 长度上限 ─────────────────────────────────────────

    @Test fun nickIsCapped() {
        val long = "很长的名字很长的名字";
        val got = OrbMenu.normNick(long)
        assertEquals(OrbMenu.NICK_CHARS, got.length)
    }

    @Test fun cappingDoesNotSplitEmoji() {
        // 按 char 截会把 emoji 劈成半个，显示成方块 —— 必须按 code point 截
        val emojis = "🎯🎯🎯🎯🎯🎯🎯🎯🎯🎯"
        val got = OrbMenu.normNick(emojis)
        assertEquals(OrbMenu.NICK_CHARS, got.codePointCount(0, got.length))
        // 没有落单的代理对
        assertTrue("不能把 emoji 劈成两半", !got.endsWith(String(Character.toChars(0xD83C))))
    }

    @Test fun shortNickIsUntouched() {
        assertEquals("学", OrbMenu.normNick("学"))
    }

    @Test fun blankNormGivesEmpty() {
        assertEquals("", OrbMenu.normNick("   "))
        assertEquals("", OrbMenu.normNick(""))
    }

    // ── 默认文案 ─────────────────────────────────────────

    @Test fun stopItemHasNoParenthetical() {
        // 用户要求：菜单里「停止小球」后面那串说明去掉
        val stop = OrbMenu.byId(OrbMenu.ID_STOP)!!
        assertEquals("停止小球", stop.label)
        assertTrue("不该再有「保留授权」这种括号说明", !stop.label.contains("保留授权"))
        assertTrue("不该有全角括号", !stop.label.contains("（"))
    }

    @Test fun everyLabelIsShortEnough() {
        // 菜单宽 236dp，14sp 下大约放得下 10 个汉字 —— 超过这个数的标题一定被截断
        OrbMenu.ALL.forEach {
            assertTrue(it.id + " 的默认标题太长：" + it.label, it.label.length <= 9)
        }
    }

    // ── 昵称不影响功能 ───────────────────────────────────

    @Test fun nickDoesNotChangeOrder() {
        // 改名纯粹是显示层的事，不能把顺序弄乱
        val order = OrbMenu.DEFAULT_ORDER
        val nicks = mapOf(OrbMenu.ID_CHAT to "阿灵", OrbMenu.ID_STOP to "关掉")
        assertEquals(order, OrbMenu.resolve(order, emptySet()))
        assertEquals("阿灵", OrbMenu.labelOf(OrbMenu.ID_CHAT, nicks))
        assertEquals("关掉", OrbMenu.labelOf(OrbMenu.ID_STOP, nicks))
    }

    @Test fun pinnedItemsCanStillBeRenamed() {
        // 常驻 = 不能隐藏，不是「不能改名」
        listOf(OrbMenu.ID_CHAT, OrbMenu.ID_SETTINGS, OrbMenu.ID_STOP).forEach {
            assertTrue(it + " 应该能改名", OrbMenu.labelOf(it, mapOf(it to "新名")) == "新名")
        }
    }
}
