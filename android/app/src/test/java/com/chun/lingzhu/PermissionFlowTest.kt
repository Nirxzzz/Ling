package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键配置」的判断层：[PermissionFlow] 全是纯函数（不碰 android），JVM 直测。
 * 真去开权限那层在 [PermissionActivity]，不在这里。
 *
 * 这里盯的是**行为**（顺序 / 适用性 / 推进 / 跳过 / 授权与连接分离），不钉具体文案。
 */
class PermissionFlowTest {

    private fun st(
        sdk: Int = 34,
        a11ySupported: Boolean = true,
        overlay: Boolean = false,
        a11yOn: Boolean = false,
        a11yConnected: Boolean = false,
        notify: Boolean = false,
        battery: Boolean = false,
        exact: Boolean = false,
        skipped: Set<String> = emptySet(),
    ) = PermissionFlow.State(sdk, a11ySupported, overlay, a11yOn, a11yConnected, notify, battery, exact, skipped)

    private val allGranted = st(overlay = true, a11yOn = true, a11yConnected = true, notify = true, battery = true, exact = true)

    // ---------------- 顺序、适用性、一键配置范围 ----------------

    @Test fun oneKeyConfigHandlesSystemPagesBeforeDialogs() {
        // 缺项先补「要跳系统页」的悬浮窗 / 无障碍（最容易漏、又是小球的前提），再补「弹系统框」的两项
        assertEquals(listOf(PermissionFlow.Item.OVERLAY, PermissionFlow.Item.CAPTURE), PermissionFlow.AUTO.take(2))
        assertTrue(PermissionFlow.AUTO.take(2).all { it.how == PermissionFlow.How.PAGE })
        assertEquals(listOf(PermissionFlow.Item.NOTIFY, PermissionFlow.Item.BATTERY), PermissionFlow.AUTO.drop(2))
        // 悬浮窗是唯一的必需项（没它小球显示不出来）
        assertEquals(listOf(PermissionFlow.Item.OVERLAY), PermissionFlow.Item.values().filter { it.required })
    }

    @Test fun exactIsOptionalAndNotInTheOneKeyFlow() {
        // 精确闹钟仍留在清单里，但不进一键配置 —— 它只是「提醒准不准点」的可选项
        assertFalse(PermissionFlow.AUTO.contains(PermissionFlow.Item.EXACT))
        assertTrue(PermissionFlow.ORDER.contains(PermissionFlow.Item.EXACT))
        // 其它都开了、就差精确闹钟 → 一键配置已经算完，不挡启动
        val s = st(overlay = true, a11yOn = true, a11yConnected = true, notify = true, battery = true, exact = false)
        assertNull(PermissionFlow.next(s))
        assertTrue(PermissionFlow.ready(s))
        assertTrue(PermissionFlow.blocked(s).isEmpty())
    }

    @Test fun visibilityFollowsSdkAndSupport() {
        val fresh = st()
        assertEquals(5, PermissionFlow.visible(fresh).size)
        // Android 12 之前没有「精确闹钟」这个权限；13 之前通知装上就有
        val old = st(sdk = 26)
        assertEquals(listOf(PermissionFlow.Item.OVERLAY, PermissionFlow.Item.CAPTURE, PermissionFlow.Item.BATTERY),
            PermissionFlow.visible(old))
        // 系统太老没有无障碍截屏这条路 → 不拿它烦用户
        assertEquals(4, PermissionFlow.visible(st(a11ySupported = false)).size)
        assertFalse(PermissionFlow.applicable(PermissionFlow.Item.CAPTURE, st(a11ySupported = false)))
    }

    @Test fun notApplicableCountsAsDone() {
        // sdk 26 上没有通知 / 精确闹钟这两项，它们适不适用都不该影响 next
        val s = st(sdk = 26, battery = true, overlay = true, a11yOn = true)
        assertNull(PermissionFlow.next(s))
        assertTrue(PermissionFlow.ready(s))
        assertTrue(PermissionFlow.done(PermissionFlow.Item.NOTIFY, s))
        assertTrue(PermissionFlow.done(PermissionFlow.Item.EXACT, s))
    }

    // ---------------- 推进 ----------------

    @Test fun nextWalksTheOneKeyOrderAndSkipsDone() {
        assertEquals(PermissionFlow.Item.OVERLAY, PermissionFlow.next(st()))
        assertEquals(PermissionFlow.Item.CAPTURE, PermissionFlow.next(st(overlay = true)))
        assertEquals(PermissionFlow.Item.NOTIFY, PermissionFlow.next(st(overlay = true, a11yOn = true)))
        assertEquals(PermissionFlow.Item.BATTERY, PermissionFlow.next(st(overlay = true, a11yOn = true, notify = true)))
        assertNull(PermissionFlow.next(allGranted))
    }

    @Test fun nextSkipsWhatTheUserPutAside() {
        val s = st(skipped = setOf("overlay", "capture"))
        assertEquals(PermissionFlow.Item.NOTIFY, PermissionFlow.next(s))
        // 一键配置全都先不开 → 没有下一项，但「必需还没拿到」这件事要单独看得见
        val all = st(skipped = PermissionFlow.AUTO.map { it.id }.toSet())
        assertNull(PermissionFlow.next(all))
        assertTrue(PermissionFlow.ready(all))
        assertEquals(listOf(PermissionFlow.Item.OVERLAY), PermissionFlow.blocked(all))
    }

    @Test fun runtimeBatchAsksOnceForEverything() {
        assertEquals(listOf(PermissionFlow.Item.NOTIFY), PermissionFlow.runtimeBatch(st()))
        assertTrue("已经给了就别再弹一次", PermissionFlow.runtimeBatch(st(notify = true)).isEmpty())
        assertTrue("用户先不开了也别再弹", PermissionFlow.runtimeBatch(st(skipped = setOf("notify"))).isEmpty())
        // 老系统上通知不是运行时权限 → 批次是空的（不能拿一个空数组去 requestPermissions）
        assertTrue(PermissionFlow.runtimeBatch(st(sdk = 26)).isEmpty())
    }

    /** 命门：不管用户怎么点，推进最多一键配置的项数趟就一定停（不会把人困在死循环里） */
    @Test fun advancingAlwaysTerminates() {
        var s = st()
        var steps = 0
        while (true) {
            val n = PermissionFlow.next(s) ?: break
            s = s.copy(skipped = s.skipped + n.id)   // 模拟「跳出去没拨就回来」
            steps++
            assertTrue("推进不该超过项数：$steps", steps <= PermissionFlow.AUTO.size)
        }
        assertEquals(4, steps)
        assertNull(PermissionFlow.next(s))
    }

    /** 跳到某一页没拨就返回：只记一次「先不开」，继续下一项，不会回头重来 */
    @Test fun skippingOnceMovesOnWithoutLooping() {
        val s0 = st(overlay = true)
        assertEquals(PermissionFlow.Item.CAPTURE, PermissionFlow.next(s0))
        val s1 = s0.copy(skipped = s0.skipped + "capture")
        assertEquals(PermissionFlow.Item.NOTIFY, PermissionFlow.next(s1))
        assertEquals(PermissionFlow.Item.BATTERY, PermissionFlow.next(s1.copy(notify = true)))
    }

    @Test fun requiredSkippedStillCountsAsNotGranted() {
        // 必需项被「先不开」：流程上算过了（不会再弹），但始终算「没拿到」→ blocked 抓得到
        val notGranted = st(skipped = setOf("overlay"))
        assertTrue(PermissionFlow.done(PermissionFlow.Item.OVERLAY, notGranted))
        assertFalse(PermissionFlow.granted(PermissionFlow.Item.OVERLAY, notGranted))
        assertEquals(listOf(PermissionFlow.Item.OVERLAY), PermissionFlow.blocked(notGranted))
    }

    // ---------------- 已授权 ≠ 已连接 ----------------

    @Test fun a11yAuthorizedAndConnectedAreSeparate() {
        val authorizedOnly = st(a11yOn = true, a11yConnected = false)
        // 授权了但服务没连上 → 明确标记「已授权 · 服务未连接」
        assertTrue(PermissionFlow.a11yAuthorizedNotConnected(authorizedOnly))
        // 「已授权」本身就算配置好了（用户能控制的就是开关），但连接与否是另一回事
        assertTrue(PermissionFlow.granted(PermissionFlow.Item.CAPTURE, authorizedOnly))
        assertTrue(PermissionFlow.done(PermissionFlow.Item.CAPTURE, authorizedOnly))

        val both = st(a11yOn = true, a11yConnected = true)
        assertFalse(PermissionFlow.a11yAuthorizedNotConnected(both))

        // 没授权就不能算已授权 —— 即使实例在（a11yConnected=true）也不许冒充
        val notAuthorized = st(a11yOn = false, a11yConnected = true)
        assertFalse(PermissionFlow.a11yAuthorizedNotConnected(notAuthorized))
        assertFalse(PermissionFlow.granted(PermissionFlow.Item.CAPTURE, notAuthorized))

        // 系统太老没有这条路
        assertFalse(PermissionFlow.a11yAuthorizedNotConnected(st(a11ySupported = false, a11yOn = true)))
    }

    /** 连接状态不该改变「已授权」的判定：granted(CAPTURE) 只看 a11yOn */
    @Test fun connectionFlagNeverChangesGrantedness() {
        for (on in listOf(false, true)) for (conn in listOf(false, true)) {
            assertEquals("a11yOn=$on a11yConnected=$conn", on,
                PermissionFlow.granted(PermissionFlow.Item.CAPTURE, st(a11yOn = on, a11yConnected = conn)))
        }
    }

    /** 已授权但服务未连接：不把它当成「没做」而卡在 CAPTURE，一键配置继续往下走 */
    @Test fun authorizedNotConnectedDoesNotStallTheFlow() {
        val s = st(overlay = true, a11yOn = true, a11yConnected = false, notify = true, battery = true)
        assertNull(PermissionFlow.next(s))
        assertTrue(PermissionFlow.ready(s))
        assertTrue(PermissionFlow.blocked(s).isEmpty())
        // 还没轮到电池时，下一项是电池（CAPTURE 已授权，不回头）
        assertEquals(PermissionFlow.Item.BATTERY,
            PermissionFlow.next(st(overlay = true, a11yOn = true, a11yConnected = false, notify = true)))
    }

    // ---------------- 结构 ----------------

    @Test fun grantedCountCountsOnlyOneKeyItems() {
        assertEquals(0, PermissionFlow.grantedCount(st()))
        assertEquals(2, PermissionFlow.grantedCount(st(overlay = true, notify = true)))
        // 精确闹钟不在一键配置里 → 不给它计数
        assertEquals(0, PermissionFlow.grantedCount(st(exact = true)))
    }

}
