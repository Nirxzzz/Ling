package com.chun.lingzhu

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 知识卡定时弹窗的主时钟 —— 与单词推送（[WordAlarm]）**完全独立**：各自一个开关、各自一个间隔、
 * 各自一条一次性精确闹钟（[RemindExact]），到点把服务拉起来弹一张知识卡、再排下一拍。
 *
 * 语义刻意对齐 [WordAlarm]（已实测的那条链）：
 *  - 弹卡只是「悬浮窗展示」，**不复活服务**：`!knowledgePopupOn` 或用户点过退出（`orbWanted=false`）
 *    或球没在跑，就撤闹钟返回；
 *  - 闹钟不跨重启：AlarmManager 的一次性闹钟重启即失，服务重建路径（BootReceiver → Watchdog / START，
 *    以及 onStartCommand → scheduleKnowledge）都会走到 [schedule] 重挂，不会漏拍；
 *  - 被抑制的那一拍也照排下一拍（见 [KnowledgeReceiver]），只是那一拍不弹。
 */
class KnowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        // 到点：开着、用户没点退出、球在跑 → 叫醒服务弹卡（弹卡本身不复活服务）。
        if (p.knowledgePopupOn && p.orbWanted) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, OrbService::class.java).setAction(OrbService.ACTION_KNOWLEDGE_TICK)
                )
            } catch (_: Exception) {
            }
        }
        // 无论这一拍弹没弹（被抑制也照排下一拍），时钟都自我续订
        KnowledgeAlarm.schedule(ctx)
    }
}

object KnowledgeAlarm {
    /** 「稍后」把下一拍提前到多久之后（毫秒）。只影响下一次 [scheduleIn]，不改变常规间隔设置。 */
    const val SNOOZE_MS = 60_000L

    /** 知识卡闹钟自己的请求码（[WordAlarm] 用 0x77，这里分 0x78，两条 PendingIntent 互不覆盖）。 */
    private const val REQ = 0x78

    private fun pi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, KnowledgeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 排下一拍（按设置里的间隔）。服务不在 / 开关关 / 用户点过退出 → 撤闹钟，不排。
     * 幂等：同一条 PendingIntent `FLAG_UPDATE_CURRENT` 覆盖，重复调用只留最近一拍。
     */
    fun schedule(ctx: Context) = scheduleIn(ctx, Prefs(ctx).knowledgePopupEverySec * 1000L)

    /** 排一拍「[delayMs] 之后」（[SNOOZE_MS] 用于「稍后」）。门槛与 [schedule] 相同。 */
    fun scheduleIn(ctx: Context, delayMs: Long) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        if (!p.knowledgePopupOn || !OrbService.running) {
            am.cancel(pi(ctx))
            return
        }
        RemindExact.set(ctx, System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L), pi(ctx))
    }

    /** 撤闹钟（用户明确停球 / 关开关 / 服务销毁且不要球时）。 */
    fun cancel(ctx: Context) {
        try { ctx.getSystemService(android.app.AlarmManager::class.java)?.cancel(pi(ctx)) } catch (_: Exception) {}
    }
}
