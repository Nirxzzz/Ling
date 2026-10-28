package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentScope：Agent 的工具权限。
 *
 * 这组用例的重点不是「代码能跑」，而是守住三条**升级安全**的语义：
 *  1. 老数据（没有 tools 字段）读出来必须是全开 —— 否则升级后所有 Agent 变成废物；
 *  2. 解析失败必须退化成全开 —— 一个坏字节不该关掉用户全部能力；
 *  3. 「用户显式关光」与「用户没设置过」必须能区分 —— 否则用户关完了下次打开又全开。
 */
class AgentScopeTest {

    private val known = listOf("add_knowledge_card", "add_reminder", "search_memory")

    // ---------------- 升级安全 ----------------

    @Test fun missingFieldMeansEverythingEnabled() {
        assertEquals(AgentScope.ALL, AgentScope.parse(null))
        assertEquals(AgentScope.ALL, AgentScope.parse(""))
        assertEquals(AgentScope.ALL, AgentScope.parse("   "))
        assertTrue("没设置过 = 全开，不是全关", AgentScope.parse(null).isEmpty())
    }

    @Test fun brokenJsonFallsBackToEverythingNotNothing() {
        // 手改坏了文件 / 老格式：宁可全开也不要让 Agent 什么都干不了
        assertEquals(AgentScope.ALL, AgentScope.parse("{not an array}"))
        assertEquals(AgentScope.ALL, AgentScope.parse("[unclosed"))
    }

    @Test fun emptyArrayMeansEverythingEnabled() {
        assertEquals(AgentScope.ALL, AgentScope.parse("[]"))
    }

    // ---------------- 显式关光 vs 没设置过 ----------------

    @Test fun noneSentinelIsDistinguishableFromNeverConfigured() {
        assertTrue("存了 NONE 就是显式关光", AgentScope.isExplicitlyNone(AgentScope.encode(emptySet())))
        assertFalse("没设置过不算关光", AgentScope.isExplicitlyNone(null))
        assertFalse("空数组也不算关光", AgentScope.isExplicitlyNone("[]"))
        assertEquals(emptySet<String>(), AgentScope.parse(AgentScope.encode(emptySet())))
    }

    @Test fun encodeDecodeRoundTrip() {
        val set = setOf("add_reminder", "search_memory")
        assertEquals(set, AgentScope.parse(AgentScope.encode(set)))
    }

    // ---------------- 白名单行为 ----------------

    @Test fun whitelistKeepsOnlyListedTools() {
        val raw = AgentScope.encode(setOf("add_reminder"))
        assertEquals(setOf("add_reminder"), AgentScope.parse(raw))
        assertTrue(AgentScope.parse(raw).contains("add_reminder"))
        assertFalse(AgentScope.parse(raw).contains("add_knowledge_card"))
    }

    @Test fun labelsReadLikeSentences() {
        assertEquals("全部 3 个工具", AgentScope.label(null, 3))
        assertEquals("不启用任何工具", AgentScope.label(AgentScope.encode(emptySet()), 3))
        assertEquals("启用 1 / 3 个工具", AgentScope.label(AgentScope.encode(setOf("add_reminder")), 3))
    }

    // ---------------- 界面勾选态 ----------------

    @Test fun checkedShowsAllWhenNeverConfigured() {
        assertEquals(known.toSet(), AgentScope.checked(null, known))
    }

    @Test fun checkedShowsNoneWhenExplicitlyDisabled() {
        assertEquals(emptySet<String>(), AgentScope.checked(AgentScope.encode(emptySet()), known))
    }

    @Test fun checkedDropsNamesThatNoLongerExist() {
        // 老配置里存了一个后来被删掉的工具名：忽略它，不要因为它界面上多出一个幽灵项
        val raw = AgentScope.encode(setOf("add_reminder", "tool_that_was_removed"))
        assertEquals(setOf("add_reminder"), AgentScope.checked(raw, known))
    }

    // ---------------- 与 AgentTools 的契约 ----------------

    @Test fun emptySetMeansAllowEveryTool() {
        // 这是 AgentTools.allows 的语义，这里锁住：空 = 全开
        assertTrue(AgentTools.allows(emptySet(), "add_reminder"))
        assertTrue(AgentTools.allows(emptySet(), "anything_at_all"))
    }

    @Test fun nonEmptySetBlocksUnlistedTools() {
        val allowed = setOf("add_reminder")
        assertTrue(AgentTools.allows(allowed, "add_reminder"))
        assertFalse(AgentTools.allows(allowed, "add_knowledge_card"))
    }

    @Test fun registryForFiltersByWhitelist() {
        val all = AgentTools.registry()
        assertTrue("默认全开时一个不少", AgentTools.registryFor(emptySet()).size == all.size)
        val one = all.first().name
        val filtered = AgentTools.registryFor(setOf(one))
        assertEquals(1, filtered.size)
        assertEquals(one, filtered.first().name)
    }

    @Test fun explicitlyNoneBlocksEveryTool() {
        // 用户把工具全关了：存 NONE -> parse 出空集合，但 isExplicitlyNone 为真
        val raw = AgentScope.encode(emptySet())
        assertTrue(AgentScope.isExplicitlyNone(raw))
        // 注意：AgentTools.allows 收到空集合仍是「全开」，所以调用方必须先用 isExplicitlyNone 拦一道。
        // 这条断言把那个坑写下来：把 AgentScope.parse 的结果直接传给 allows 会全开。
        assertTrue("空集合在 allows 里是全开，调用方必须先查 isExplicitlyNone",
            AgentTools.allows(AgentScope.parse(raw), "add_reminder"))
    }
}
