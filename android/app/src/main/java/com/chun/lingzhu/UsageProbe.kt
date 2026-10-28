package com.chun.lingzhu

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * 「指定学习应用」模式的取证（v2.3.4 手机版）。
 *
 * ## 它回答什么问题
 * 只知道**前台是哪个包**，不知道屏幕上是什么内容 —— 这正是这个模式存在的理由：
 * 用户选了「只看应用」，就不再碰屏幕像素。代价是判据只能是黑名单式的：「不在白名单里的前台应用」
 * 才算离开。所以 [UNSURE][FocusGuard.Verdict.UNSURE] 必须给得很勤：
 *
 *  - **没有「使用情况访问」授权** → UNSURE（不是「你在玩」，是「我看不到」）；
 *  - **锁屏 / 息屏** → 直接 null（连查都不查）；
 *  - **查不到 / 查询抛异常** → UNSURE 并清掉缓存（不许拿上一次的结果顶替）；
 *  - **系统应用 / 桌面 / 解析不出的包** → UNSURE（桌面上待着不是分心）。
 *
 * ## 为什么要有内存缓存
 * Android 的用法事件只在**变化那一刻**产生：用户在前台一直看同一页，可能十几分钟没有一条新事件。
 * 如果每次都只查「最近 N 秒的事件」，那么 60 秒之后就会查空 → 判成 UNSURE → 计时断掉。
 * 所以 [foregroundPackage] 把「最后复位的那个包」留在内存里（[lastPkg]），只查**增量**窗口，
 * 查不到新事件就沿用缓存值。清掉缓存的只有三件事：锁屏 / 息屏、查询失败、显式 [clear]。
 *
 * ## 线程
 * [foregroundPackage] 会走 Binder（queryEvents），**不要在主线程上每 12 秒调一次**。调用方（[OrbService]）
 * 在自己的 worker 上采样、回到主线程改状态。本对象内部状态用 [lock] 保护，纯判据（[reduce] / [classify]）
 * 不碰任何 Android API，JVM 单测直接跑。
 */
object UsageProbe {

    /** 一次用法事件的方向：[RESUMED] = 这个包到了前台，[PAUSED] = 它离开了前台 */
    const val RESUMED = 1
    const val PAUSED = 2

    /** 最小形状的用法事件（不带 Android 类型，便于单测直接构造） */
    data class Ev(val at: Long, val pkg: String, val kind: Int)

    /** 一个包在系统里是什么性质（见 [kind]）。混成 Boolean 会把「桌面」和「已卸载」搅成同一种不该提醒 */
    enum class Kind { USER, SYSTEM, MISSING }

    /** 没有缓存游标时往回查多久：两分钟足够覆盖一次 12 秒轮询之间的所有切换，又不至于捞一堆历史 */
    const val LOOKBACK_MS = 2 * 60_000L

    /** 相邻两次查询重叠多少：游标精度不足时宁可重复读一秒，也不能漏掉切换那一瞬 */
    const val OVERLAP_MS = 1_000L

    private val lock = Any()

    /** 最后停在的那个包（内存缓存，见文件头）。null = 不知道 */
    private var lastPkg: String? = null

    /** 上次查询到的时刻（墙上时钟）。0 = 还没查过，要用 [LOOKBACK_MS] 起头 */
    private var cursor = 0L

    /** 用户有没有在系统里打开「使用情况访问」 */
    fun hasAccess(ctx: Context): Boolean = try {
        val app = ctx.applicationContext
        val ops = app.getSystemService(AppOpsManager::class.java)
        if (ops == null) false else {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
            else
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 去系统设置里给本应用开「使用情况访问」。
     * 缺权限时调用也安全：这里只**构造**一个 Intent，不弹、不检查、不启动。
     */
    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 现在前台的包名；不知道就返回 null（调用方必须按 UNSURE 处理，不许当成「在玩」）。
     * 会在 Binder 上查用法事件，**不要在主线程调**。
     */
    fun foregroundPackage(ctx: Context, now: Long): String? {
        if (!hasAccess(ctx)) { clear(); return null }
        if (!awake(ctx)) { clear(); return null }   // 锁屏 / 息屏：不查、不乱猜
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        if (usm == null) { clear(); return null }
        val from = synchronized(lock) {
            if (cursor in 1..now) minOf(maxOf(cursor - OVERLAP_MS, now - LOOKBACK_MS), now) else now - LOOKBACK_MS
        }
        val evs = ArrayList<Ev>(16)
        try {
            val it = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (it.hasNextEvent()) {
                it.getNextEvent(e)
                val kind = when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> RESUMED
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> PAUSED
                    UsageEvents.Event.ACTIVITY_STOPPED -> PAUSED
                    else -> 0
                }
                if (kind == 0) continue
                evs.add(Ev(e.timeStamp, e.packageName ?: "", kind))
            }
        } catch (_: Throwable) {
            // 查不到 ≠ 在玩：清缓存，这一轮按 UNSURE 走（下次重新用 LOOKBACK 起头）
            clear()
            return null
        }
        synchronized(lock) {
            lastPkg = reduce(lastPkg, evs)
            cursor = now
            return lastPkg
        }
    }

    /** 丢掉缓存（锁屏 / 息屏 / 换模式 / 采样失败时调）。下一次查询重新用 [LOOKBACK_MS] 起头 */
    fun clear() {
        synchronized(lock) {
            lastPkg = null
            cursor = 0L
        }
    }

    /**
     * 把一批用法事件折成「现在前台的包」。**纯函数**。
     *
     * 规则很朴素：前台事件记下来，离开事件只在它**正是当前那个包**时才清空
     * （A 暂停 + B 恢复的常见切换因此落在 B 上，不会先变 null 再变 B）。
     * 包名为空的事件直接跳过 —— 那是系统内部的东西，拿来判断只会冤枉人。
     *
     * 事件不保证一定有序（厂商 ROM 有按写盘顺序吐的），所以这里先按时刻稳定排序。
     */
    fun reduce(prev: String?, events: List<Ev>): String? {
        var cur = prev
        for (e in events.sortedBy { it.at }) {
            val pkg = e.pkg.trim()
            if (pkg.isEmpty()) continue
            when (e.kind) {
                RESUMED -> cur = pkg
                PAUSED -> if (pkg == cur) cur = null
            }
        }
        return cur
    }

    /**
     * 前台包 → 三态。**纯函数**。
     *
     * @param self 本应用包名：用户在自己 App 里看报告 / 设置，当然算在学习这边
     * @param apps 用户勾的「学习应用」
     * @param kind 这个包在系统里是什么（[Kind]）。**解析不出来必须是 MISSING**，
     *   不能并进 SYSTEM：白名单里留着一个已经卸载的包时，只有 MISSING 能挡住「它在学习」这个假结论。
     */
    fun classify(
        pkg: String?,
        self: String,
        apps: Set<String>,
        kind: (String) -> Kind,
    ): FocusGuard.Verdict {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return FocusGuard.Verdict.UNSURE      // 看不见 = 判不出来
        if (p == self) return FocusGuard.Verdict.STUDY
        val k = kind(p)
        // 读不到这个包（已卸载 / PackageManager 出错）：判断不了，连白名单也不该说了算
        if (k == Kind.MISSING) return FocusGuard.Verdict.UNSURE
        if (p in apps) return FocusGuard.Verdict.STUDY          // 用户亲自勾的一律算学习（含系统自带词典）
        return if (k == Kind.SYSTEM) FocusGuard.Verdict.UNSURE  // 桌面 / 系统界面：不是学习，也不是离开
        else FocusGuard.Verdict.NOT_STUDY
    }

    /** 一步到位：查前台包 → 判三态。**在 worker 线程调**（会走 Binder） */
    fun verdict(ctx: Context, apps: Set<String>, now: Long = System.currentTimeMillis()): FocusGuard.Verdict {
        val pkg = foregroundPackage(ctx, now)
        return classify(pkg, ctx.packageName, apps) { kind(ctx, it) }
    }

    /**
     * 这个包在系统里是什么性质。**预装的纯系统应用**才算 [Kind.SYSTEM]（桌面 / 系统界面 / 输入法…）；
     * 被商店升级过的（浏览器、视频这类）算普通应用 —— 在用户眼里它们就是「一个 App」。
     * 解析失败（已卸载 / PackageManager 出错）返回 [Kind.MISSING]：判断不了就不下结论。
     */
    fun kind(ctx: Context, pkg: String): Kind = try {
        val flags = ctx.applicationContext.packageManager.getApplicationInfo(pkg, 0).flags
        val sys = (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val upgraded = (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (sys && !upgraded) Kind.SYSTEM else Kind.USER
    } catch (_: Throwable) {
        Kind.MISSING
    }

    /** 亮屏且没锁屏：锁屏界面上「前台包」是锁屏自己，拿它判学习和分心都没有意义 */
    private fun awake(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return false
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) return false
        return true
    }
}
