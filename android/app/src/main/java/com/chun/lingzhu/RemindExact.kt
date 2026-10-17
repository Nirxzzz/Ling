package com.chun.lingzhu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 提醒「准点」的唯一入口（v1.85）。
 *
 * 为什么要有这一层：原先待办的闹钟直接 `AlarmManager.set(...)`（文档注释还写着「inexact，允许系统合并」），
 * 于是 Doze/省电一合并，**到点可能晚几十分钟、待机时干脆不响** —— 用户报的「提醒功能没生效」这是其一；
 * 其二在 [BootReceiver]（开机没重挂）。两者都修在这一版。
 *
 * 口径（用户拍板「要准点，引导授权」）：
 *  1. 能精确就 `setExactAndAllowWhileIdle`（23+）/`setExact`（更老）；
 *  2. 没授权（12+ 的系统权限，或 33+ 还没给通知权限）就退回 `setAndAllowWhileIdle` —— **不静默失败**，
 *     设置页那一行会显示「未授权」并可一键跳系统设置（[settingsIntent]）；
 *  3. 判定逻辑抽成纯函数 [exactUsable]，单测直接盯（[RemindExactTest]）。
 */
object RemindExact {

    /** Android 12 = API 31 起，精确闹钟需要 SCHEDULE_EXACT_ALARM 且用户手动授权。 */
    const val EXACT_MIN_SDK = 31
    /** Android 13 = API 33 起，通知要运行时权限；没它的话「提醒」等于没发。 */
    const val NOTI_MIN_SDK = 33

    /**
     * 现在到底能不能用精确闹钟。纯函数：只看 sdk 版本 + 两个授权状态，方便单测。
     *  - 31 之前没有这个权限，直接 true；
     *  - 33+ 还要通知权限（没通知权限时，闹钟准不准都没意义）；
     *  - 其余看精确闹钟授权。
     */
    fun exactUsable(sdk: Int, exactGranted: Boolean, notiGranted: Boolean): Boolean {
        if (sdk < EXACT_MIN_SDK) return true
        if (sdk >= NOTI_MIN_SDK && !notiGranted) return false
        return exactGranted
    }

    /** 只看「精确闹钟」这一个授权（不掺通知权限）—— 一键开权限的清单要分开显示两项 */
    fun exactGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < EXACT_MIN_SDK) return true
        return try {
            ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } catch (_: Exception) { false }
    }

    /** 通知权限（33 之前装上就有） */
    fun notiGranted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < NOTI_MIN_SDK ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 真机上的判定：读系统里这两个授权状态，喂给 [exactUsable]。 */
    fun canExact(ctx: Context): Boolean = exactUsable(Build.VERSION.SDK_INT, exactGranted(ctx), notiGranted(ctx))

    /** 排一个闹钟：能精确就精确，不能就退到「允许待机时唤醒」的近似档（不抛、不静默丢）。 */
    fun set(ctx: Context, triggerAtMs: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = triggerAtMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        try {
            if (canExact(ctx)) {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
                return
            }
        } catch (_: SecurityException) {
            // 授权在运行中被收回时会抛 —— 落到下面的近似档，别让提醒整条链断掉
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.set(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
        }
    }

    /** 用户点「去授权」跳哪：31+ 跳「精确闹钟」专用页，更老跳应用详情页。 */
    fun settingsIntent(ctx: Context): Intent = if (Build.VERSION.SDK_INT >= EXACT_MIN_SDK)
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))
    else
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    /** 设置页那一行右侧的说明。 */
    fun label(ok: Boolean): String =
        if (ok) "已授权 · 准点" else "未授权 · 会晚几分钟到几十分钟"
}
