package com.chun.lingzhu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 单词推送的主时钟（2026-10-14 架构移植）：以前靠 OrbService 里的 Handler.postDelayed 自续期，
 * 服务被杀 / Doze 下直接停摆且错过不补。现在改成仓库里已验证的 RemindExact 一次性精确闹钟
 * （与待办 / 简报 / 复习同型：AlarmManager + Receiver 自续订），每一拍把服务拉起来弹词、再排下一拍。
 */
class WordReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // 弹词语义与旧 postDelayed 一致：wordOn 且用户没点「退出」、球在跑才弹（弹词本身不复活服务）
        if (Prefs(ctx).wordOn && Prefs(ctx).orbWanted) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, OrbService::class.java).setAction(OrbService.ACTION_WORD_TICK)
                )
            } catch (_: Exception) {
            }
        }
        // 无论这一拍弹没弹（被抑制也照排下一拍），时钟都自我续订
        WordAlarm.schedule(ctx)
    }
}

object WordAlarm {
    /** 每拍到点先叫醒这里，再由它拉起服务 —— PendingIntent.getBroadcast 的显式广播 */
    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0x77, Intent(ctx, WordReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 排下一拍。服务不在（!wordOn 或 !OrbService.running）就 cancel 闹钟返回：
     * 弹词只是悬浮窗展示，用户点过「退出」（orbWanted=false / 球没了）绝不因弹词把服务拉回来，
     * 同旧 postDelayed 语义（旧实现里球没了 Runnable 也就不再挂）。闹钟不跨重启：
     * AlarmManager 的一次性闹钟重启即失，服务重建路径（BootReceiver → Watchdog/START，
     * 以及 onStartCommand → scheduleWord）都会走到这里重挂，不会漏拍。
     */
    fun schedule(ctx: Context) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        if (!p.wordOn || !OrbService.running) {
            am.cancel(pi(ctx))
            return
        }
        RemindExact.set(ctx, System.currentTimeMillis() + p.wordEverySec * 1000L, pi(ctx))
    }
}
