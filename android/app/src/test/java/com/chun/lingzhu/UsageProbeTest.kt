package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APP 模式取证（[UsageProbe]）的**纯逻辑**边界测试。
 *
 * 只测两件不依赖 Android 的事：
 *  1. [UsageProbe.reduce]：用法事件 → 现在前台的包。重点覆盖「事件重置」这一类边界
 *     （查询窗口查不到新事件、A 暂停 B 恢复、锁屏 / 权限丢失后的清缓存、桌面 / 已卸载包）
 *  2. [UsageProbe.classify]：前台包 → 三态。**判不出来必须是不算**（UNSURE），
 *     绝不能把「看不到」说成「在玩」。
 *
 * 真正去系统查 UsageStats 的那一半（[UsageProbe.foregroundPackage]）在 JVM 上跑不了，
 * 留给设备上的手工验收；这里不假装测过它。
 */
class UsageProbeTest {

    private val T = 1_700_000_000_000L   // 固定基准时刻

    private fun at(sec: Int, pkg: String, kind: Int) = UsageProbe.Ev(T + sec * 1000L, pkg, kind)

    private fun resumed(sec: Int, pkg: String) = at(sec, pkg, UsageProbe.RESUMED)
    private fun paused(sec: Int, pkg: String) = at(sec, pkg, UsageProbe.PAUSED)

    // ---------------- reduce：事件 -> 前台包 ----------------

    @Test
    fun firstForegroundEventSetsPackage() {
        assertEquals("a.reader", UsageProbe.reduce(null, listOf(resumed(0, "a.reader"))))
    }

    @Test
    fun switchBetweenAppsLandsOnTheNewOne() {
        // A 到前台 -> A 离开 + B 到前台 -> 结果必须是 B（不能先被清成 null 再设成 B，虽然最终也一样，
        // 但「A 暂停但 B 还没恢复」这一瞬不允许把 A 算成离开了）
        val evs = listOf(resumed(0, "x"), paused(5, "x"), resumed(5, "y"))
        assertEquals("y", UsageProbe.reduce(null, evs))
    }

    @Test
    fun pauseOfAnotherPackageDoesNotClearCurrent() {
        // 后台有个通知弹了一下就没了（别的包 PAUSED）：不该把当前前台包清掉
        assertEquals("reader", UsageProbe.reduce("reader", listOf(paused(1, "notifier"))))
    }

    @Test
    fun noNewEventsKeepsCachedPackage() {
        // 关键边界：同一页停留十分钟，查询窗口里一条新事件都没有 → 必须沿用缓存，
        // 否则 60 秒后就查空、判 UNSURE，专注计时会被系统性地打断。
        assertEquals("reader", UsageProbe.reduce("reader", emptyList()))
    }

    @Test
    fun screenOffStyleReset() {
        // 锁屏 / 权限丢失时调用方走 clear()，下次查询从空开始；模拟「清完之后只拿到一条 PAUSED」
        assertEquals(null, UsageProbe.reduce(null, listOf(paused(1, "reader"))))
    }

    @Test
    fun unknownPackagesAreSkipped() {
        // 包名为空的事件（系统内部 / 权限不足时字段缺失）不能当成「切到了某个未知应用」
        assertEquals("reader", UsageProbe.reduce("reader", listOf(resumed(1, ""), resumed(2, "   "))))
        assertNull(UsageProbe.reduce(null, listOf(resumed(1, ""))))
    }

    @Test
    fun eventsAreSortedByTime() {
        // 厂商 ROM 不保证吐出来有序：乱序时按时间排，最后一条（最新）说了算
        val evs = listOf(resumed(9, "late"), resumed(1, "early"))
        assertEquals("late", UsageProbe.reduce(null, evs))
    }

    @Test
    fun unknownEventKindIsIgnored() {
        assertEquals("reader", UsageProbe.reduce("reader", listOf(at(1, "weird", 0))))
    }

    @Test
    fun pauseThenResumeSamePackageStaysForeground() {
        // 同一个包短暂 pause / resume（权限对话框、转 Activity）：最终仍在前台
        val evs = listOf(resumed(0, "reader"), paused(2, "reader"), resumed(4, "reader"))
        assertEquals("reader", UsageProbe.reduce(null, evs))
    }

    // ---------------- classify：前台包 -> 三态 ----------------

    /** 测试用的包性质表：桌面 / 系统界面 / 系统自带词典是 SYSTEM，普通 App 是 USER，已卸载是 MISSING */
    private fun kindOf(pkg: String): UsageProbe.Kind = when {
        pkg == "com.android.launcher" || pkg.startsWith("com.android.systemui") -> UsageProbe.Kind.SYSTEM
        pkg == "com.android.dictionary" -> UsageProbe.Kind.SYSTEM
        pkg == "com.gone.app" -> UsageProbe.Kind.MISSING
        else -> UsageProbe.Kind.USER
    }

    @Test
    fun selfAppCountsAsStudy() {
        assertEquals(
            FocusGuard.Verdict.STUDY,
            UsageProbe.classify("com.chun.lingzhu", "com.chun.lingzhu", emptySet(), ::kindOf)
        )
    }

    @Test
    fun whitelistedAppCountsAsStudy() {
        assertEquals(
            FocusGuard.Verdict.STUDY,
            UsageProbe.classify("com.duolingo", "com.chun.lingzhu", setOf("com.duolingo"), ::kindOf)
        )
    }

    @Test
    fun otherUserAppIsNotStudy() {
        assertEquals(
            FocusGuard.Verdict.NOT_STUDY,
            UsageProbe.classify("com.tiktok", "com.chun.lingzhu", setOf("com.duolingo"), ::kindOf)
        )
    }

    @Test
    fun emptyWhitelistMeansNothingIsStudy() {
        // 白名单空 = 一个都不算（契约里的默认）。除了自家 App，其它用户应用都按「不在学习」算，
        // 但总时长阈值 5 分钟还在，不会立刻提醒。
        assertEquals(
            FocusGuard.Verdict.NOT_STUDY,
            UsageProbe.classify("com.duolingo", "com.chun.lingzhu", emptySet(), ::kindOf)
        )
    }

    @Test
    fun unknownPackageIsUnsureNotPlay() {
        // 权限丢失 / 查不到 / 锁屏 → 调用方传 null。**必须 UNSURE**：把「看不到」记成「在玩」是冤枉人
        assertEquals(FocusGuard.Verdict.UNSURE, UsageProbe.classify(null, "com.chun.lingzhu", setOf("a"), ::kindOf))
        assertEquals(FocusGuard.Verdict.UNSURE, UsageProbe.classify("", "com.chun.lingzhu", setOf("a"), ::kindOf))
        assertEquals(FocusGuard.Verdict.UNSURE, UsageProbe.classify("  ", "com.chun.lingzhu", setOf("a"), ::kindOf))
    }

    @Test
    fun launcherAndSystemUiAreUnsure() {
        // 在桌面上待着不是分心：桌面 / 系统界面都是「不好判」，不是 NOT_STUDY
        assertEquals(
            FocusGuard.Verdict.UNSURE,
            UsageProbe.classify("com.android.launcher", "com.chun.lingzhu", setOf("a"), ::kindOf)
        )
        assertEquals(
            FocusGuard.Verdict.UNSURE,
            UsageProbe.classify("com.android.systemui", "com.chun.lingzhu", setOf("a"), ::kindOf)
        )
    }

    @Test
    fun uninstalledPackageIsUnsure() {
        // 白名单里留着一个已经卸载的包：解析失败 → 不好判（不能算「在学习」，也不能算「在玩」）。
        // 这条正是 Kind 三态存在的理由：如果「已卸载」并进「系统应用」，白名单会先命中，白记一笔学习。
        assertEquals(
            FocusGuard.Verdict.UNSURE,
            UsageProbe.classify("com.gone.app", "com.chun.lingzhu", setOf("com.gone.app"), ::kindOf)
        )
    }

    @Test
    fun whitelistWinsOverSystemFlag() {
        // 万一用户把一个系统应用勾进白名单（比如系统自带的词典）：按 STUDY，别被系统标记盖掉
        assertEquals(
            FocusGuard.Verdict.STUDY,
            UsageProbe.classify("com.android.dictionary", "com.chun.lingzhu", setOf("com.android.dictionary"), ::kindOf)
        )
    }

    @Test
    fun reduceThenClassifyChain() {
        // 端到端：一串事件 -> 前台包 -> 三态。用户在刷视频，之后一直停在那屏（这一窗口没有新事件）
        val pkg = UsageProbe.reduce(null, listOf(resumed(0, "com.tiktok")))
        assertEquals(
            FocusGuard.Verdict.NOT_STUDY,
            UsageProbe.classify(pkg, "com.chun.lingzhu", setOf("com.duolingo"), ::kindOf)
        )
        // 再折一次（游标往后走、查不到新事件）：结论必须不变 —— 这就是「不因查空而丢当前包」的端到端体现
        assertEquals(
            FocusGuard.Verdict.NOT_STUDY,
            UsageProbe.classify(UsageProbe.reduce(pkg, emptyList()), "com.chun.lingzhu", setOf("com.duolingo"), ::kindOf)
        )
    }
}
