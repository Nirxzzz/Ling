package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.70：BrandHints 的品牌识别 + 导航文案。全是纯逻辑，不碰 Context，JVM 上直接跑。 */
class BrandHintsTest {

    @Test fun detectsMajorVendors() {
        assertEquals(BrandHints.Vendor.XIAOMI, BrandHints.detect("Xiaomi"))
        assertEquals(BrandHints.Vendor.HUAWEI, BrandHints.detect("HUAWEI"))
        assertEquals(BrandHints.Vendor.OPPO, BrandHints.detect("OPPO"))
        assertEquals(BrandHints.Vendor.VIVO, BrandHints.detect("vivo"))
        assertEquals(BrandHints.Vendor.SAMSUNG, BrandHints.detect("samsung"))
        assertEquals(BrandHints.Vendor.GOOGLE, BrandHints.detect("Google"))
        assertEquals(BrandHints.Vendor.OTHER, BrandHints.detect(""))
        assertEquals(BrandHints.Vendor.OTHER, BrandHints.detect("Unknown"))
    }

    /** 红米：MANUFACTURER 是 Xiaomi、BRAND 是 Redmi —— 看 manufacturer 也能认出来 */
    @Test fun redmiAndPocoAreXiaomi() {
        assertEquals(BrandHints.Vendor.XIAOMI, BrandHints.detect("Xiaomi", "Redmi"))
        assertEquals(BrandHints.Vendor.XIAOMI, BrandHints.detect("Xiaomi", "POCO"))
    }

    /** 一加 / realme 千万别掉进 OTHER —— 它们的厂商页跟 OPPO 不是同一个包 */
    @Test fun oneplusRealmeAreSeparateVendors() {
        assertEquals(BrandHints.Vendor.ONEPLUS, BrandHints.detect("OnePlus"))
        assertEquals(BrandHints.Vendor.REALME, BrandHints.detect("realme"))
        assertEquals(BrandHints.Vendor.IQOO, BrandHints.detect("iQOO"))
        // 认出来了，但组件包名不能和 OPPO 混用
        assertNotNull(BrandHints.spec(BrandHints.Vendor.ONEPLUS).autostart)
        assertEquals("com.oplus.safecenter", BrandHints.spec(BrandHints.Vendor.ONEPLUS).autostart.first().pkg)
        assertEquals("com.coloros.safecenter", BrandHints.spec(BrandHints.Vendor.REALME).autostart.first().pkg)
    }

    @Test fun honorIsNotHuawei() {
        assertEquals(BrandHints.Vendor.HONOR, BrandHints.detect("HONOR"))
        assertEquals("com.hihonor.systemmanager", BrandHints.spec(BrandHints.Vendor.HONOR).autostart.first().pkg)
        assertEquals("com.huawei.systemmanager", BrandHints.spec(BrandHints.Vendor.HUAWEI).autostart.first().pkg)
    }

    /** 大小写 / 空格不敏感 —— Build 值在不同厂商定制 ROM 上大小写不一致 */
    @Test fun detectIsCaseAndSpaceInsensitive() {
        assertEquals(BrandHints.Vendor.XIAOMI, BrandHints.detect("XIAOMI"))
        assertEquals(BrandHints.Vendor.VIVO, BrandHints.detect(" vivo "))
        assertEquals(BrandHints.Vendor.HUAWEI, BrandHints.detect("  Huawei  "))
    }

    /** 每家都要有悬浮窗 / 电池 / 自启动三条导航文案，且里面要带上 App 名（用户照着点才找得到） */
    @Test fun everyVendorHasThreeNavLines() {
        for (v in BrandHints.Vendor.entries) {
            val s = BrandHints.spec(v)
            for (line in listOf(s.overlayNav, s.batteryNav, s.autostartNav)) {
                assertTrue("${v.label} 导航文案为空", line.isNotBlank())
                assertTrue("${v.label} 导航文案里没有 App 名：$line", line.contains(Preset.appName))
            }
        }
    }

    /** 三星和原生 Android 没有厂商自启动页 —— 必须落到系统页 + 文字，不能瞎跳一个不存在的组件 */
    @Test fun vendorsWithoutVendorPage() {
        for (v in listOf(BrandHints.Vendor.SAMSUNG, BrandHints.Vendor.GOOGLE, BrandHints.Vendor.OTHER)) {
            val s = BrandHints.spec(v)
            assertFalse("${v.label} 不该有厂商组件", s.vendorPage)
            assertTrue(s.autostart.isEmpty())
            assertTrue(s.battery.isEmpty())
        }
        // 有厂商页的必须真的有组件，别写个空壳进去
        for (v in listOf(BrandHints.Vendor.XIAOMI, BrandHints.Vendor.HUAWEI, BrandHints.Vendor.OPPO, BrandHints.Vendor.VIVO)) {
            val s = BrandHints.spec(v)
            assertTrue("${v.label} 标了有厂商页却没组件", s.autostart.isNotEmpty())
            assertTrue(s.autostart.all { it.pkg.isNotBlank() && it.cls.isNotBlank() })
        }
    }

    /** 每家的组件都不能重名（重了说明复制粘贴写错了），也不能出现包名为空 */
    @Test fun autostartTargetsAreUnique() {
        for (v in BrandHints.Vendor.entries) {
            val list = BrandHints.spec(v).autostart
            assertEquals("${v.label} 有重复的组件候选", list.size, list.distinct().size)
        }
        // 一加/realme 那两条候选必须真的是两个不同的类（老版本类名不一样）
        assertEquals(2, BrandHints.spec(BrandHints.Vendor.REALME).autostart.size)
    }

    /** 华为 / 荣耀 / vivo / iQOO 这四家后台清理最狠，第三层（无障碍守护）容易塌 —— 提示里必须说出来 */
    @Test fun aggressiveVendorsAreFlagged() {
        val aggressive = BrandHints.Vendor.entries.filter { BrandHints.spec(it).a11yKill == BrandHints.A11yKill.AGGRESSIVE }
        assertTrue(aggressive.containsAll(listOf(BrandHints.Vendor.HUAWEI, BrandHints.Vendor.OPPO, BrandHints.Vendor.VIVO, BrandHints.Vendor.IQOO)))
        for (v in aggressive) {
            assertTrue("${v.label} 提示里该提无障碍", BrandHints.tips(v).contains("无障碍"))
            assertTrue(BrandHints.riskLevel(v).startsWith("高"))
        }
        // Pixel 反而最安全
        assertEquals(BrandHints.A11yKill.REBIND, BrandHints.spec(BrandHints.Vendor.GOOGLE).a11yKill)
        assertTrue(BrandHints.riskLevel(BrandHints.Vendor.GOOGLE).startsWith("低"))
    }

    /** 三星默认把第三方通知静默 —— 常驻通知不弹出来用户会以为服务死了，提示要点破 */
    @Test fun samsungNotificationWarning() {
        assertEquals(BrandHints.NotifyDefault.SILENT, BrandHints.spec(BrandHints.Vendor.SAMSUNG).notify)
        assertTrue(BrandHints.tips(BrandHints.Vendor.SAMSUNG).contains("静默"))
        // 其余各家不静默，就别多嘴
        assertFalse(BrandHints.tips(BrandHints.Vendor.XIAOMI).contains("静默"))
    }

    /** 所有品牌的提示都以「锁卡片」开头 —— 这一步各品牌通用、最容易漏，代码替代不了 */
    @Test fun tipsLeadWithRecentsLock() {
        for (v in BrandHints.Vendor.entries) {
            val t = BrandHints.tips(v)
            assertTrue("${v.label} 提示没提锁卡片", t.contains("锁定"))
            assertTrue("${v.label} 提示里没有 App 名", t.contains(Preset.appName))
        }
        assertTrue(BrandHints.tips(BrandHints.Vendor.XIAOMI).startsWith("先做这一步"))
    }

    @Test fun vendorLabelsAreDistinct() {
        val labels = BrandHints.Vendor.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertEquals(BrandHints.Vendor.OTHER, BrandHints.Vendor.of("某不知名牌子"))
    }
}