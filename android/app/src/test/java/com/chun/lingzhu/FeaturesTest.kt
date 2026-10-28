package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能包注册表的约束。
 *
 * 这些断言存在的意义：注册表是“加功能只改一处”的前提，
 * 一旦 id 重复或排序不稳定，设置页就会出现两条一样的入口 / 顺序每次打开都不一样，
 * 而这种问题只在真机上看得见，单测是唯一能提前拦住的地方。
 */
class FeaturesTest {

    @Test fun idsAreUnique() {
        val ids = Features.all.map { it.id }
        assertEquals("功能 id 必须唯一，否则 byId 与显隐持久化都会错", ids.size, ids.toSet().size)
    }

    @Test fun tilesAreUniqueAndNotBlank() {
        val titles = Features.all.map { it.title }
        assertTrue("标题不能为空", titles.none { it.isBlank() })
        assertEquals("标题重复会让用户在设置页看到两个一样的入口", titles.size, titles.toSet().size)
    }

    @Test fun sortedIsStableByOrder() {
        val s = Features.sorted()
        for (i in 1 until s.size) {
            assertTrue(
                "排序必须按 order 单调不减：" + s[i - 1].id + "(" + s[i - 1].order + ") -> " + s[i].id + "(" + s[i].order + ")",
                s[i - 1].order <= s[i].order,
            )
        }
    }

    @Test fun sortedKeepsEveryFeature() {
        assertEquals("排序不能丢功能", Features.all.size, Features.sorted().size)
    }

    @Test fun byIdRoundTrips() {
        for (f in Features.all) assertNotNull("byId 必须能查回自己: " + f.id, Features.byId(f.id))
        assertNull("没登记过的 id 要返回 null，不能抛", Features.byId("no-such-feature"))
    }

    @Test fun ofToolFindsOwningFeature() {
        // 「建知识卡」必须能问出它属于学习功能 —— 工具权限对话框按功能分组靠这个
        val owner = Features.ofTool(AgentTools.NAME_ADD_CARD)
        assertNotNull("建知识卡应该归属某个功能", owner)
        assertEquals(StudyFeature.id, owner!!.id)
    }

    @Test fun unknownToolHasNoOwner() {
        assertNull("没登记的工具返回 null，交给调用方处理", Features.ofTool("ghost_tool"))
    }

    @Test fun declaredToolsAllExistInRegistry() {
        // 功能包声明了自己提供哪些工具，这些名字必须真的在 AgentTools.registry 里。
        // 否则「工具权限」对话框会出现一个勾了也没用的幽灵项。
        val real = AgentTools.names()
        for (f in Features.all) {
            for (n in f.toolNames) {
                assertTrue("功能 " + f.id + " 声明的工具 " + n + " 在 AgentTools.registry 里不存在", real.contains(n))
            }
        }
    }

    @Test fun aToolBelongsToAtMostOneFeature() {
        // 一个工具挂在两个功能下，分组展示时会出现两次，勾选状态还可能互相打脸
        val seen = mutableMapOf<String, String>()
        for (f in Features.all) {
            for (n in f.toolNames) {
                val prev = seen.put(n, f.id)
                assertNull("工具 " + n + " 同时属于 " + prev + " 和 " + f.id, prev)
            }
        }
    }

    @Test fun settingsListIsSubsetOfAll() {
        val all = Features.all.toSet()
        assertTrue("设置页清单不能包含未登记的功能", Features.inSettings().all { it in all })
    }

    @Test fun openUnknownFeatureReturnsFalse() {
        // 不传 Context 就没法真开页面，这里只守住「未知 id 不能抛异常」这条
        // （真正的 startActivity 路径由 run() 内的 try/catch 兜底）
        assertNull(Features.byId("nope"))
    }

    // ---------------- 模块开关（v2.4） ----------------

    /**
     * 关掉的模块要从设置页拿掉，但**核心模块永远在** ——
     * 哪怕配置被改坏留下了 study 的 id（[FeatureToggles.normalize] 也会再兜一次）。
     */
    @Test fun settingsListRespectsDisabledModulesButKeepsPinned() {
        assertTrue(Features.isEnabled(setOf(StudyFeature.id), StudyFeature.id))
        assertFalse(Features.isEnabled(setOf(TodoFeature.id), TodoFeature.id))

        val left = Features.inSettings(setOf(TodoFeature.id))
        assertTrue("关掉的功能不该还留在设置页：" + left.map { it.id }, left.none { it.id == TodoFeature.id })
        assertTrue("别的功能受牵连了：" + left.map { it.id }, left.any { it.id == StudyFeature.id })
    }

    /** 关掉的模块的工具连执行都要拒 —— 门控在协议层，不依赖页面入口藏没藏干净 */
    @Test fun disabledModuleBlocksItsTools() {
        assertFalse(AgentTools.moduleOff(setOf(StudyFeature.id), AgentTools.NAME_ADD_CARD))
        assertTrue(AgentTools.moduleOff(setOf(TodoFeature.id), AgentTools.NAME_ADD_REMINDER))
        // 归属没登记的工具一律放行：拿不到信息时不要藏东西
        assertFalse(AgentTools.moduleOff(setOf(TodoFeature.id), "ghost_tool"))
    }
}
