package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠组展开 / 收起那层纯逻辑（[MainActivity.Sections]）。
 *
 * v1.90 起设置页改成「分类首页 + 7 个分类子页」：页序、分类归属、组名、哪几组默认展开，
 * 都会随版本调整，在这里钉死只会每次改版都红 —— 所以页序 / 分组文案 / 默认展开清单那些断言都删了，
 * 只留下真正会算错、且不随版式变的东西：展开集合怎么切、「上手与授权」什么时候锁。
 */
class SettingsPagesTest {

    /** 存着的展开态就是展开态；不在里面就是收着 */
    @Test fun isOpenFollowsTheStoredSet() {
        assertTrue(MainActivity.Sections.isOpen("g1", setOf("g1")))
        assertFalse(MainActivity.Sections.isOpen("g2", setOf("g1")))
        assertFalse(MainActivity.Sections.isOpen("g1", emptySet()))
    }

    /** 锁着的时候恒展开，且点了不动 —— 授权没做完不许把「该做的事」藏起来 */
    @Test fun lockedGroupStaysOpenAndIgnoresToggles() {
        val open = setOf(MainActivity.G_KEEP)
        assertTrue(MainActivity.Sections.isOpen(MainActivity.G_AUTH, open, locked = true))
        assertEquals(
            "锁着的组点了不该改变展开集合",
            open,
            MainActivity.Sections.toggled(MainActivity.G_AUTH, open, locked = true)
        )
    }

    /** 没锁的组：点一下在展开 / 收起之间切，返回新的集合 */
    @Test fun unlockedGroupTogglesBothWays() {
        assertEquals(
            setOf(MainActivity.G_KEEP, MainActivity.G_API),
            MainActivity.Sections.toggled(MainActivity.G_API, setOf(MainActivity.G_KEEP))
        )
        assertEquals(
            setOf(MainActivity.G_KEEP),
            MainActivity.Sections.toggled(MainActivity.G_API, setOf(MainActivity.G_KEEP, MainActivity.G_API))
        )
    }

    /**
     * 只有「上手与授权」会锁，且锁不锁只由「悬浮窗权限 + 接口有没有」决定 ——
     * 授权做完（两项都齐）就立刻能收了。
     */
    @Test fun onlyAuthGroupLocksAndItUnlocksOnceConfigured() {
        assertTrue(MainActivity.Sections.locked(MainActivity.G_AUTH, overlay = false, apiReady = true))
        assertTrue(MainActivity.Sections.locked(MainActivity.G_AUTH, overlay = true, apiReady = false))
        assertFalse(MainActivity.Sections.locked(MainActivity.G_AUTH, overlay = true, apiReady = true))
        // 别的组永远不锁（保活没做完也照样能收起来）
        for (g in MainActivity.GROUPS) {
            if (g.id == MainActivity.G_AUTH) continue
            assertFalse("组 ${g.id} 不该锁", MainActivity.Sections.locked(g.id, overlay = false, apiReady = false))
        }
    }

    /** 锁态下渲染出来的「上手与授权」一定是展开的，哪怕存档里没有它（老用户存档可能缺这一项） */
    @Test fun authGroupRendersOpenEvenWhenNotStored() {
        assertTrue(MainActivity.Sections.isOpen(MainActivity.G_AUTH, emptySet(), locked = true))
    }
}
