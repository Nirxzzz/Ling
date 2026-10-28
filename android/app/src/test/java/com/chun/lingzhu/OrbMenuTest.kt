package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 长按菜单可自定义排列。
 *
 * 这组用例守的是「菜单不能把用户关在门外」：
 * 无论用户存了什么鬼东西，解析出来的顺序都必须包含「设置」和「停止小球」，
 * 否则他既找不到设置、又关不掉悬浮球。
 */
class OrbMenuTest {

    // ── 默认 ─────────────────────────────────────────────

    @Test fun defaultOrderMatchesAll() {
        assertEquals(OrbMenu.ALL.map { it.id }, OrbMenu.DEFAULT_ORDER)
    }

    @Test fun emptySavedGivesDefault() {
        assertEquals(OrbMenu.DEFAULT_ORDER, OrbMenu.resolve(null))
        assertEquals(OrbMenu.DEFAULT_ORDER, OrbMenu.resolve(emptyList()))
    }

    // ── 用户真的能改顺序 ─────────────────────────────────

    @Test fun userOrderIsRespected() {
        // 全部项都在、顺序与用户一致时必须原样返回。
        // 期望值从 DEFAULT_ORDER 派生（首项聊天不动、其余倒序），不再写死一份列表 ——
        // 那样每加一个菜单项都要跟着改这条用例，测的其实是「列表抄全了没」，不是「顺序被尊重了」。
        val custom = listOf(OrbMenu.ID_CHAT) + OrbMenu.DEFAULT_ORDER.drop(1).reversed()
        assertEquals("项齐全时顺序应原样保留", custom, OrbMenu.resolve(custom))
    }

    @Test fun newItemIsInsertedNextToItsNeighbour() {
        // v2.4 老用户存的顺序里没有 note。它不该被追加到尾部 ——
        // 「截图速记」是核心功能，排在一堆开关后面等于加了没人用得到。
        // 规则：插到默认顺序里它前一个邻居（chat）的后面。
        val old = listOf("chat", "translate", "history", "region", "autoscan", "word", "settings", "mute", "stop")
        val got = OrbMenu.resolve(old)
        assertEquals("note 要跟在 chat 后面", "note", got[1])
        assertEquals("chat 仍在最前", "chat", got[0])
    }

    @Test fun userCanHideOptionalItems() {
        // 用户主动隐藏了历史和免打扰 —— 隐藏必须真的生效，不能一保存又长回来
        val got = OrbMenu.resolve(listOf("chat", "translate", "settings", "stop"), setOf("history", "mute"))
        assertFalse("历史记录应被隐藏", "history" in got)
        assertFalse("免打扰应被隐藏", "mute" in got)
        assertTrue("没隐藏的翻译要留着", "translate" in got)
    }

    @Test fun hiddenItemsDoNotComeBackOnResave() {
        // 这条守的是第一版的 bug：删掉的项被当成「老数据缺失」自动补回来了
        val once = OrbMenu.resolve(listOf("chat", "translate", "settings", "stop"), setOf("history", "mute"))
        val twice = OrbMenu.resolve(once, setOf("history", "mute"))
        assertEquals("隐藏项在反复保存后不能复活", once, twice)
    }

    // ── 常驻项永远在 ─────────────────────────────────────

    @Test fun pinnedItemsCannotBeRemoved() {
        val got = OrbMenu.resolve(listOf("translate"))
        assertTrue("聊天入口必须留着", "chat" in got)
        assertTrue("设置必须留着", "settings" in got)
        assertTrue("停止小球必须留着", "stop" in got)
    }

    @Test fun chatIsAlwaysFirst() {
        val got = OrbMenu.resolve(listOf("stop", "settings", "mute", "word", "chat"))
        assertEquals("chat", got.first())
    }

    @Test fun settingsAndStopAlwaysExist() {
        // 只保证存在，不规定位置：用户可能把「停止小球」放在「设置」前面
        val got = OrbMenu.resolve(listOf("stop", "chat", "settings", "word"))
        assertTrue("settings 必须留着", "settings" in got)
        assertTrue("stop 必须留着", "stop" in got)
    }

    @Test fun settingsAndStopRelativeOrderIsPreserved() {
        // 这条守的是第二版的 bug：强行把 settings/stop 排成固定顺序，用户调好的相对顺序被打乱
        val got = OrbMenu.resolve(listOf("chat", "mute", "stop", "settings"))
        assertTrue("stop 应排在 settings 之前（用户就这么排的）", got.indexOf("stop") < got.indexOf("settings"))
    }

    @Test fun filledInItemsDoNotJumpAheadOfUserOrder() {
        // 补进来的项插在各自邻居后面（不是无脑追加尾部，见 newItemIsInsertedNextToItsNeighbour），
        // 但必须落在用户已排好的项后面 —— 不能因为补齐就把用户排过的次序顶乱。
        val got = OrbMenu.resolve(listOf("chat", "translate", "word", "settings", "stop"))
        assertEquals("用户排过的项相对次序不能变",
            listOf("chat", "translate", "word", "settings", "stop"),
            got.filter { it in setOf("chat", "translate", "word", "settings", "stop") })
        assertTrue("补进来的项在 word 之后", got.indexOf("history") > got.indexOf("word"))
    }

    @Test fun chatStaysFirstEvenIfUserPutsItLast() {
        val got = OrbMenu.resolve(listOf("stop", "mute", "chat", "settings"))
        assertEquals("chat", got.first())
    }

    // ── 坏数据 ───────────────────────────────────────────

    @Test fun unknownIdsAreDropped() {
        val got = OrbMenu.resolve(listOf("chat", "这不是菜单项", "settings"))
        assertFalse(got.any { it == "这不是菜单项" })
    }

    @Test fun duplicatesKeepFirstOccurrence() {
        val got = OrbMenu.resolve(listOf("chat", "word", "word", "settings"))
        assertEquals(1, got.count { it == "word" })
    }

    @Test fun allGarbageFallsBackToDefault() {
        assertEquals(OrbMenu.DEFAULT_ORDER, OrbMenu.resolve(listOf("x", "y", "z")))
    }

    @Test fun missingOptionalItemsAreRestored() {
        // 用户存的是老版本顺序（那时还没有 translate 这一项），新版要把新项补进来，不能让它消失
        val old = listOf("chat", "region", "autoscan", "word", "history", "settings", "mute", "stop")
        val got = OrbMenu.resolve(old)
        assertTrue("新增的菜单项要自动补上", "translate" in got)
    }

    @Test fun resolveNeverReturnsEmpty() {
        // 空菜单 = 用户再也开不了设置，这是最坏的结果，任何输入都不能产生它
        listOf(null, emptyList(), listOf(""), listOf("garbage"), listOf("chat")).forEach { input ->
            assertTrue("输入 " + input + " 得到了空菜单", OrbMenu.resolve(input).isNotEmpty())
        }
    }

    // ── 项本身 ───────────────────────────────────────────

    @Test fun idsAreUnique() {
        assertEquals(OrbMenu.ALL.size, OrbMenu.ALL.map { it.id }.toSet().size)
    }

    @Test fun labelsAreNotBlank() {
        OrbMenu.ALL.forEach { assertTrue(it.id + " 没标题", it.label.isNotBlank()) }
    }

    @Test fun chatSettingsStopArePinned() {
        assertTrue(OrbMenu.byId(OrbMenu.ID_CHAT)!!.pinned)
        assertTrue(OrbMenu.byId(OrbMenu.ID_SETTINGS)!!.pinned)
        assertTrue(OrbMenu.byId(OrbMenu.ID_STOP)!!.pinned)
    }

    @Test fun optionalItemsAreHideable() {
        // 只断言「可选项都能隐藏」这条规则本身：常驻项之外的每一项都该能藏起来
        OrbMenu.ALL.filter { it.pinned != true }.forEach {
            assertTrue(it.id + " 应该允许隐藏", OrbMenu.canHide(it.id))
        }
    }

    // ── 双击方式（v2.7）───────────────────────────────────

    @Test fun doubleTapModeDefaultsToAsk() {
        // 没有这个键（老用户）/ 存了看不懂的东西 → 一律「先问问题」，不能变成自作主张地做题
        assertEquals(OrbMenu.TAP_ASK, OrbMenu.normTap(null))
        assertEquals(OrbMenu.TAP_ASK, OrbMenu.normTap(""))
        assertEquals(OrbMenu.TAP_ASK, OrbMenu.normTap("做题"))
        assertEquals(OrbMenu.TAP_ASK, OrbMenu.normTap("general"))
    }

    @Test fun doubleTapModeCyclesThroughAllThree() {
        // 菜单里点一下就切下一档：先问问题 → 做题 → 单词翻译 → 回到先问问题
        var v = OrbMenu.TAP_ASK
        val seen = mutableListOf(v)
        repeat(3) { v = OrbMenu.nextTap(v); seen.add(v) }
        assertEquals(listOf(OrbMenu.TAP_ASK, OrbMenu.TAP_EXAM, OrbMenu.TAP_WORD, OrbMenu.TAP_ASK), seen)
        assertEquals("非法值要先归一化再切", OrbMenu.TAP_EXAM, OrbMenu.nextTap("garbage"))
    }

    @Test fun doubleTapModeLabelsMatchTheModesTheyUse() {
        assertEquals("先问问题", OrbMenu.tapLabel(OrbMenu.TAP_ASK))
        assertEquals(Prompts.MODE_LABEL.getValue(Prompts.MODE_EXAM), OrbMenu.tapLabel(OrbMenu.TAP_EXAM))
        assertEquals(Prompts.MODE_LABEL.getValue(Prompts.MODE_WORD), OrbMenu.tapLabel(OrbMenu.TAP_WORD))
    }

    @Test fun pinnedItemsAreNotHideable() {
        assertFalse(OrbMenu.canHide(OrbMenu.ID_CHAT))
        assertFalse(OrbMenu.canHide(OrbMenu.ID_SETTINGS))
        assertFalse(OrbMenu.canHide(OrbMenu.ID_STOP))
    }

    @Test fun resolveIsIdempotent() {
        // 解析结果再解析一次必须不变，否则「打开又保存」会让顺序慢慢漂移
        val once = OrbMenu.resolve(listOf("chat", "word", "translate", "settings", "stop"))
        assertEquals(once, OrbMenu.resolve(once))
    }
}
