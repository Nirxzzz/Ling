package com.chun.lingzhu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 开机 / 升级后自动拉起小球（v1.62）。只在用户上次没点「退出」时（orbWanted）。
 * 开机广播里允许起前台服务；服务起来后自己挂前台（无障碍模式 specialUse）。开着无障碍的话，系统绑定它时也会拉一次，重复无妨。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // ⚠️ v1.85：AlarmManager 的一次性闹钟**不跨重启**，开机必须重挂。而且这三样（待办提醒 / 简报 / 复习）
        // 都与「小球开没开、有没有悬浮窗权限」无关 —— 必须排在下面那条 guard **之前**。
        // 原先待办漏在这一趟之外（TodoReceiver.kick 全工程没人调），于是**重启一次，之前排好的提醒全部静默死掉**，
        // 这正是用户报的「提醒功能没生效」的主因。
        // v1.86：记期规则只物化「未来两周」的点，重启后要往前走一趟 —— 把新的点补上、过点的收掉。
        // 排在 TodoReceiver.kick 之前：sync 自己会把待办闹钟重排一次。
        try { Schedule.sync(ctx) } catch (_: Exception) {}
        try { TodoReceiver.kick(ctx) } catch (_: Exception) {}
        try { BriefingReceiver.kick(ctx) } catch (_: Exception) {}
        try { ReviewReceiver.kick(ctx) } catch (_: Exception) {}
        if (!Prefs(ctx).orbWanted || OrbService.running || !Settings.canDrawOverlays(ctx)) return
        WatchdogReceiver.kick(ctx)   // v1.65：开机就排上看门狗闹钟，进程被激进 ROM 清掉也有第四层兜底
        try { ctx.startForegroundService(Intent(ctx, OrbService::class.java).setAction(OrbService.ACTION_START).putExtra(OrbService.EXTRA_REVIVE, if (a == Intent.ACTION_BOOT_COMPLETED) "开机自启" else "升级后自启")) } catch (_: Exception) {}
    }
}
