package com.chun.lingzhu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提醒能不能用精确闹钟」的纯逻辑（v1.85）。只看 sdk 版本 + 两个授权状态，不碰 Android。
 * 背景：提醒不生效的两个根因之一就是这里 —— 不精确的闹钟会被 Doze 合并、晚几十分钟。
 */
class RemindExactTest {

    @Test fun oldSdkAlwaysExact() {
        // Android 12(31) 之前没有这个权限，装上就能精确
        assertTrue(RemindExact.exactUsable(26, exactGranted = false, notiGranted = false))
        assertTrue(RemindExact.exactUsable(30, exactGranted = false, notiGranted = false))
    }

    @Test fun sdk31NeedsExactGrant() {
        assertFalse(RemindExact.exactUsable(31, exactGranted = false, notiGranted = true))
        assertTrue(RemindExact.exactUsable(31, exactGranted = true, notiGranted = true))
    }

    @Test fun sdk33AlsoNeedsNotificationPermission() {
        // 33+ 没通知权限的话，「提醒」等于没发 —— 精确也白搭
        assertFalse(RemindExact.exactUsable(33, exactGranted = true, notiGranted = false))
        assertTrue(RemindExact.exactUsable(33, exactGranted = true, notiGranted = true))
        assertFalse(RemindExact.exactUsable(33, exactGranted = false, notiGranted = true))
    }

    @Test fun labelSaysWhatIsWrong() {
        assertTrue(RemindExact.label(true).startsWith("已授权"))
        assertTrue(RemindExact.label(false).contains("未授权"))
    }
}
