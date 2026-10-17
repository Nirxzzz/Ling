package com.chun.lingzhu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * v1.65 保活第四层：闹钟看门狗。v1.68 加第五层「指数退避」（KeepAliveBackoff）：
 * 连续几次醒来球都好好跑着 → 间隔 15m→30m→60m 逐档拉长（省电）；
 * 一旦真把掉了的球拉起来 → 立刻回 15m。状态存独立 prefs 文件「ka」，两键。
 *  - 自我续订：每次触发再定下一次（间隔可变，用一次性 set 而非 setInexactRepeating）；
 *  - 用 inexact set()：允许系统合并唤醒窗口，不卡精确闹钟权限；
 *  - 门槛与 BootReceiver / A11yService.revive 完全一致：orbWanted + 悬浮窗权限 + 没在跑。
 */
class WatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val REQ = 1001
        private const val KA_FILE = "ka"
        private const val KA_STREAK = "kaStreak"
        private const val KA_IDX = "kaIntervalIdx"

        private fun sp(ctx: Context) = ctx.getSharedPreferences(KA_FILE, Context.MODE_PRIVATE)

        /** 排下一次看门狗闹钟：intervalMs 后触发。kick（外部入口）默认回最短档 */
        private fun schedule(ctx: Context, intervalMs: Long) {
            try {
                val am = ctx.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    ctx, REQ, Intent(ctx, WatchdogReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + intervalMs, pi)   // 一次性 set（本身即 inexact 语义），间隔可变不用 setInexactRepeating
                if (intervalMs == KeepAliveBackoff.INTERVALS_MS[0]) {
                    Prefs(ctx.applicationContext).noteWatchdogKick()   // v1.65：设置页「看门狗」行用它显示已排上
                }
            } catch (_: Exception) {}
        }

        /** 外部入口（设置页改动 / 服务起来时）：回最短档重排 */
        fun kick(ctx: Context) {
            sp(ctx).edit().putInt(KA_STREAK, 0).putInt(KA_IDX, KeepAliveBackoff.DEFAULT_IDX).apply()
            schedule(ctx, KeepAliveBackoff.INTERVALS_MS[0])
        }

        /**
         * 用户明确停球（[OrbService.stopOrb]）：撤掉已排的看门狗闹钟 —— 球都不想要了，别再留一个
         * 没意义的闹钟空转（它醒来也只会拿到 orbWanted=false 空手回去）。
         * 只撤闹钟，**不动退避档位 / 计数**（[KeepAliveBackoff] 的 state 保持原样；下次 kick 会重置）。
         */
        fun cancel(ctx: Context) {
            try {
                val am = ctx.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    ctx, REQ, Intent(ctx, WatchdogReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                ) ?: return   // 本来就没排：不新建一个再取消
                am.cancel(pi)
            } catch (_: Exception) {}
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        // 用户点过「退出」就永远别回来；悬浮窗权限被收也没法救
        if (!Prefs(ctx).orbWanted || !Settings.canDrawOverlays(ctx)) return
        val sp = sp(ctx)
        val streak = sp.getInt(KA_STREAK, 0)
        val idx = sp.getInt(KA_IDX, KeepAliveBackoff.DEFAULT_IDX)
        if (OrbService.running) {
            // 没事做：连击 +1，够数升档；按（可能变长的）间隔续订
            val ni = KeepAliveBackoff.onIdle(streak, idx)
            sp.edit().putInt(KA_STREAK, KeepAliveBackoff.nextStreak(streak, idx, ni)).putInt(KA_IDX, ni).apply()
            schedule(ctx, KeepAliveBackoff.intervalMs(ni))
            return
        }
        try {
            // 球掉了：拉回来（闹钟唤醒属后台起点，必须 startForegroundService），并回最短档
            ctx.startForegroundService(
                Intent(ctx, OrbService::class.java).setAction(OrbService.ACTION_START)
                    .putExtra(OrbService.EXTRA_REVIVE, "看门狗")
            )
            sp.edit().putInt(KA_STREAK, 0).putInt(KA_IDX, KeepAliveBackoff.onRevive()).apply()
            schedule(ctx, KeepAliveBackoff.INTERVALS_MS[KeepAliveBackoff.DEFAULT_IDX])
        } catch (_: Exception) {
            // v1.65：Android 12+ 闹钟不在后台起前台的白名单里，激进 ROM 会拒 —— 记下来，设置页能看见第四层被拦了几次
            Prefs(ctx.applicationContext).noteWatchdogFail()
            schedule(ctx, KeepAliveBackoff.INTERVALS_MS[KeepAliveBackoff.DEFAULT_IDX])
        }
    }
}
