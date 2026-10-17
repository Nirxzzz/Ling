package com.chun.lingzhu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台保活 + 截屏授权的各个系统入口（v1.62）。设置页的「后台保活」清单、答案面板的提示、通知都走这里。
 * 保活靠三层：① 前台通知（OrbService，无障碍模式用 specialUse 类型）② 被杀后 START_STICKY 重启
 * ③ 无障碍服务当守护（系统会重新绑定它，连上就把小球带回来，之后每分钟查一次）；再加开机自启、忽略电池优化、厂商自启动。
 */
object KeepAlive {
    private fun go(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** 电池优化白名单：系统自带的一步确认框 */
    fun ignoringBattery(ctx: Context): Boolean = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun askBattery(ctx: Context) {
        if (ignoringBattery(ctx)) { toast(ctx, "已经允许后台运行了 ✓"); return }
        @Suppress("BatteryLife")
        if (!go(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))))
            go(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 应用信息页（「允许受限制的设置」、通知、省电策略都在这里） */
    fun openAppInfo(ctx: Context) { go(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }

    /** 悬浮窗权限（小球显示靠它；被收走了小球就起不来） */
    fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
    fun openOverlay(ctx: Context) {
        if (!go(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))) openAppInfo(ctx)
    }

    /** 通知权限（前台通知靠它显示；没开时系统照样保活，只是看不到通知按钮） */
    fun openNotifications(ctx: Context) {
        if (!go(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))) openAppInfo(ctx)
    }
    fun notificationsOn(ctx: Context): Boolean = ctx.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    /** 厂商把「自启动 / 后台运行」藏在自家管家里：按品牌试几条已知入口，都不行就打开应用信息页 */
    val VENDOR_PAGES = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",          // 小米 / 红米
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // 华为
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity", // 荣耀
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",      // OPPO / 一加
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",       // vivo / iQOO
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",                                    // 魅族
    )
    fun openVendorAutostart(ctx: Context) {
        for ((pkg, cls) in VENDOR_PAGES) {
            if (go(ctx, Intent().setComponent(ComponentName(pkg, cls)))) { toast(ctx, "找到「${Preset.appName}」，打开「自启动 / 允许后台运行」"); return }
        }
        openAppInfo(ctx)
        toast(ctx, "这台手机没有单独的自启动页：在应用信息里把「省电策略 / 电池」改成「无限制」")
    }

    /**
     * v1.86 一键开权限：采一份「这台机器此刻的权限快照」喂给 [PermissionFlow]。
     * 判断（哪项要做、下一项是谁、文案怎么写）全在 PermissionFlow 那个纯函数层，这里只碰 android API。
     * [PermissionActivity] 和设置页那一行都用它 —— 口径只有一处。
     *
     * 无障碍两项分开采：`a11yOn` = 系统里**已授权**（[A11yService.enabledInSystem]），
     * `a11yConnected` = 服务**已连上**（[A11yService.available]）。混为一体正是过去「已授权却当成能用」的根源。
     */
    fun flowState(ctx: Context, skipped: Set<String> = emptySet()) = PermissionFlow.State(
        sdk = Build.VERSION.SDK_INT,
        a11ySupported = A11yService.supported,
        overlay = canDrawOverlays(ctx),
        a11yOn = A11yService.enabledInSystem(ctx),
        a11yConnected = A11yService.available,
        notify = notificationsOn(ctx),
        battery = ignoringBattery(ctx),
        exact = RemindExact.exactGranted(ctx),
        skipped = skipped,
    )

    /**
     * 盯着无障碍的「授权 / 连接」状态：系统开关变了、或服务连上 / 断开时，在主线程回调 [onChange] 一次。
     * 用途：权限页、设置页回前台挂上、离开时 [java.io.Closeable.close] 掉，界面就能实时反映「已授权 · 服务未连接」。
     *
     * 两条来源：
     *  ① 系统设置的 ContentObserver —— 覆盖 `ACCESSIBILITY_ENABLED` 与 `ENABLED_ACCESSIBILITY_SERVICES`（开关被拨动）；
     *  ② [A11yService] 的连接状态通知 —— 覆盖服务连上 / 解绑 / 销毁（开关没动但连接变了）。
     *
     * 不持有 Activity：ContentResolver 与监听都挂在 [Context.getApplicationContext] 上。close() 可靠注销两条来源，
     * 之后不再回调（即使已排队的回调也会被丢弃）。
     */
    fun observeA11y(ctx: Context, onChange: () -> Unit): java.io.Closeable {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val fire: () -> Unit = {
            if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed.get()) onChange() }
            else main.post { if (!closed.get()) onChange() }
        }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { fire() }
        }
        val resolver = app.contentResolver
        try {
            resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        } catch (_: Exception) {}
        val listener: () -> Unit = { fire() }
        A11yService.addConnectionListener(listener)
        return object : java.io.Closeable {
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try { resolver.unregisterContentObserver(observer) } catch (_: Exception) {}
                A11yService.removeConnectionListener(listener)
            }
        }
    }

    /** 这台手机大概是哪家的（设置页提示用）。v1.65：锁卡步骤各品牌通用又最容易被忽略，放最前面说 */
    fun brandTip(): String {
        val lock = "先做这一步（各品牌都一样、最容易漏）：最近任务里按住 ${Preset.appName} 的卡片 → 「锁定」—— 锁了卡片，一键清理 / 划掉别的卡才不会带上它（不少手机上不锁就等于「强行停止」，闹钟和自动恢复会全部失效）。\n"
        val brand = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> "小米：自启动打开；省电策略选「无限制」"
            "huawei", "honor" -> "华为 / 荣耀：应用启动管理里把 ${Preset.appName} 改成「手动管理」，三个开关全开"
            "oppo", "oneplus", "realme" -> "OPPO / 一加 / realme：自启动打开；耗电管理里允许后台运行"
            "vivo", "iqoo" -> "vivo / iQOO：后台高耗电里允许；自启动打开"
            "samsung" -> "三星：电池 → 后台使用限制 → 把 ${Preset.appName} 加到「永不休眠的应用」"
            else -> "在系统的电池 / 应用管理里，把 ${Preset.appName} 设成「无限制 / 允许后台运行」"
        }
        return lock + brand
    }
}
