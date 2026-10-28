package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 C 档：功能开关。
 *
 * 守的核心是**用户不能被自己关在门外**：核心功能无论存了什么都要能打开。
 */
class FeatureTogglesTest {

    @Test fun allEnabledByDefault() {
        assertEquals(Features.sorted().size, FeatureToggles.enabled(emptySet()).size)
    }

    @Test fun disabledFeatureDisappears() {
        val on = FeatureToggles.enabled(setOf("graph"))
        assertFalse("关掉的功能不该出现", on.any { it.id == "graph" })
        assertTrue("别的功能还在", on.any { it.id == "todo" })
    }

    @Test fun pinnedCannotBeDisabled() {
        FeatureToggles.PINNED.forEach { id ->
            assertFalse(id + " 不该允许关闭", FeatureToggles.canDisable(id))
            assertTrue(id + " 必须始终开着", FeatureToggles.isOn(id, setOf(id)))
        }
    }
    
    @Test fun pinnedSurvivesNormalize() {
        // 配置文件被改成「关掉设置」时，normalize 必须把它救回来
        val got = FeatureToggles.normalize(setOf("settings", "chat", "study"))
        assertTrue("核心功能必须被移出关闭集合", got.isEmpty())
    }

    @Test fun normalizeDropsUnknownIds() {
        val got = FeatureToggles.normalize(setOf("graph", "这个功能不存在"))
        assertEquals(setOf("graph"), got)
    }

    @Test fun normalizeHandlesNullAndEmpty() {
        assertTrue(FeatureToggles.normalize(null).isEmpty())
        assertTrue(FeatureToggles.normalize(emptySet()).isEmpty())
    }

    @Test fun enabledNeverEmpty() {
        // 最坏情况：把能关的全关了，也不能一个功能都不剩
        val allDisableable = Features.all.map { it.id }.toSet()
        assertTrue(FeatureToggles.enabled(allDisableable).isNotEmpty())
    }

    @Test fun enabledKeepsSortedOrder() {
        // 过滤不能打乱排序规则（排序只在一处定义）
        val on = FeatureToggles.enabled(setOf("graph"))
        val expected = Features.sorted().filter { it.id != "graph" }
        assertEquals(expected.map { it.id }, on.map { it.id })
    }

    @Test fun unknownIdIsNotCountedAsDisabled() {
        // 未知 id 进不了「关掉的」集合（normalize 会丢掉它），所以它对任何功能都不起作用。
        // 这条守的是：用户配置文件里残留一个旧功能 id，不会影响现存功能的显隐。
        assertTrue(FeatureToggles.normalize(setOf("从未见过的功能")).isEmpty())
        assertTrue(FeatureToggles.isOn("graph", FeatureToggles.normalize(setOf("从未见过的功能"))))
    }

    @Test fun pinnedIdsExistInRegistry() {
        // 常量写错一个字母就永远不会生效 —— 这条会立刻发现
        FeatureToggles.PINNED.forEach { id ->
            assertTrue("PINNED 里的 " + id + " 在 Features 里不存在", Features.byId(id) != null)
        }
    }
}
