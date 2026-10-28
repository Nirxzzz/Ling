package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置首页 AI 模糊搜索：本地同义词落到正确目标、空 / 无关输入不出结果、
 * AI 协议只认精确白名单 ID（未知 / 重复 / 非法结构）、已合并项与首页块不进候选、提示词不含设置值。
 */
class SettingsSearchTest {
    private val S = SettingsSearch
    private val R = SettingsRegistry

    private fun ids(q: String) = S.local(q).map { it.itemId }
    private fun first(q: String) = S.local(q).firstOrNull()

    // ---------------- 本地：同义词 / 大小写 / 子串 ----------------

    @Test fun synonymVibrationHitsVibrationNotAllItems() {
        for (q in listOf("不想震动", "别震", "振动")) {
            val h = first(q)
            assertTrue("「$q」的首条应是震动：${ids(q)}", h != null && (h.itemId == R.S_POP_VIBE || h.itemId == "R3"))
        }
    }

    @Test fun synonymSmallTextHitsFontSize() {
        for (q in listOf("字太小", "字太小了", "字体大小")) {
            assertTrue("「$q」应命中面板正文字号：${ids(q)}", "L5" in ids(q))
        }
        assertEquals("L5", first("字太小")?.itemId)
    }

    @Test fun synonymGameHideOrbHitsCard() {
        for (q in listOf("游戏不想看见球", "打游戏时隐藏")) {
            assertTrue("「$q」应命中打游戏时隐藏小球：${ids(q)}", R.S_ORB_HIDE in ids(q))
        }
        assertEquals(R.S_ORB_HIDE, first("游戏不想看见球")?.sectionId)
        assertEquals(R.C_ORB, first("游戏不想看见球")?.cat)
    }

    @Test fun synonymNightDndHitsQuietHours() {
        for (q in listOf("晚上别弹", "免打扰")) {
            assertTrue("「$q」应命中免打扰时段 R7：${ids(q)}", "R7" in ids(q))
        }
    }

    @Test fun caseInsensitiveEnglish() {
        assertTrue("APIKEY 大写也应命中密钥：${ids("APIKEY")}", "P4" in ids("APIKEY"))
        assertTrue("DEepl 大小写混写应命中：${ids("DEepl")}", "T3" in ids("DEepl"))
        assertTrue("不分大小写的子串：${ids("TAVILY")}", ids("TAVILY").any { it == "S6" || it == "S8" })
        assertTrue("「api key」同义词表的 key：${ids("key")}", "P4" in ids("key"))
    }

    @Test fun chineseShortQueryStillHits() {
        assertTrue("「密钥」：${ids("密钥")}", "P4" in ids("密钥"))
        assertTrue("「搜索」：${ids("搜索")}", ids("搜索").any { it.startsWith("S") })
    }

    @Test fun substringInsideLongerWordStillHits() {
        // 连续子串：不要求从头匹配
        assertTrue("「同步」应命中局域网同步卡：${ids("同步")}", R.S_DATA_SYNC in ids("同步"))
        assertTrue("「更新」应命中检查更新：${ids("更新")}", "D8" in ids("更新") || "D9" in ids("更新"))
    }

    // ---------------- 本地：空 / 无关 / 质量 ----------------

    @Test fun blankQueryReturnsNothing() {
        assertEquals(emptyList<SettingsSearch.Hit>(), S.local(""))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.local("   "))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.local("\n\t"))
    }

    @Test fun irrelevantQueryReturnsNothing() {
        for (q in listOf("zzzzqqq", "天气预报", "你好世界", "@@@@")) {
            assertEquals("「$q」不该有结果", emptyList<SettingsSearch.Hit>(), S.local(q))
        }
    }


    // ---------------- AI 协议 ----------------

    @Test fun aiAcceptsExactIdsInOrder() {
        val hits = S.fromAi("[\"P4\",\"P3\"]")
        assertEquals(listOf("P4", "P3"), hits.map { it.itemId })
        assertEquals("密钥", hits[0].label)
        assertEquals(R.G_API, hits[0].sectionId)
        assertEquals(R.C_AI, hits[0].cat)
    }

    @Test fun aiDedupsAndTruncates() {
        assertEquals(listOf("P4", "P3"), S.fromAi("[\"P4\",\"P4\",\"P3\",\"P4\"]").map { it.itemId })
        val many = S.fromAi("[\"A1\",\"A2\",\"A5\",\"A6\",\"A7\",\"K1\",\"K2\",\"K3\"]")
        assertEquals(6, many.size)
        assertEquals(listOf("A1", "A2", "A5", "A6", "A7", "K1"), many.map { it.itemId })
        assertEquals(2, S.fromAi("[\"P4\",\"P3\"]", 2).size)
    }

    @Test fun aiRejectsUnknownMixedAndMalformed() {
        // 未知 ID 混在里面：整份作废，不挑好的用
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"P4\",\"ZZ9\"]"))
        // 已合并的项不在白名单
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"A4\"]"))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"P4\",\"P3\",\"ZZ9\"]", 2))
        // 小写 / 带空格不是「精确 ID」
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"p4\"]"))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\" P4\"]"))
        // 非数组 / 裸 ID / 自由文本里夹数组 / 类型不对 / 空串
        for (raw in listOf(
            "", "   ", "P4", "\"P4\"", "{}", "{\"ids\":[\"P4\"]}", "[\"P4\"", "P4 和 P3",
            "我建议选 [\"P4\"]", "[\"P4\"] 以上是结果", "[1,2]", "[null]", "[[\"P4\"]]", "[\"P4\",null]",
            "```\nP4\n```", "```json\n[\"P4\"\n```",
            "[\"P4\"][\"P3\"]",
        )) {
            assertEquals("「$raw」应被拒绝", emptyList<SettingsSearch.Hit>(), S.fromAi(raw))
        }
    }

    @Test fun aiEmptyArrayIsAcceptedButEmpty() {
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[]"))
    }

    @Test fun aiStripsWholeJsonFenceOnly() {
        assertEquals(listOf("P4", "P3"), S.fromAi("```json\n[\"P4\",\"P3\"]\n```").map { it.itemId })
        assertEquals(listOf("P4"), S.fromAi("```\n[\"P4\"]\n```").map { it.itemId })
        assertEquals(listOf("P4"), S.fromAi("  [\"P4\"]  ").map { it.itemId })
    }

    @Test fun mergedAndHubAreNotOffered() {
        // A4 已合并 → 本地和 AI 都不出现
        assertFalse("A4 已合并，不该出现", "A4" in ids("接口状态行"))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"A4\"]"))
        // 首页块不收录：hub 条目和 s.hub 都不是候选
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"H1\"]"))
        assertEquals(emptyList<SettingsSearch.Hit>(), S.fromAi("[\"${R.S_HUB}\"]"))
        for (h in S.local("常用")) assertFalse("首页块不该出现：${h.itemId}", h.itemId == R.S_HUB)
        // 版本卡（NEW_ITEMS）反而应在
        assertEquals(listOf("NEW1"), S.local("版本").map { it.itemId }.filter { it == "NEW1" })
    }

}
