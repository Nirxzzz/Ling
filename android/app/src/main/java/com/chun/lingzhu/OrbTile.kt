package com.chun.lingzhu

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 状态栏快捷开关「Ling 小球」（v1.58）：打游戏时下拉状态栏点一下，小球和面板都收起来；再点一下恢复。
 * 不靠通知 —— 无障碍模式下用户可能没给通知权限。Ling 没在运行时点它 = 打开 Ling。
 * 开 / 关除了系统的亮 / 暗，副标题也写明「显示中 / 已隐藏」（不靠颜色区分）。
 */
class OrbTile : TileService() {
    companion object {
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, OrbTile::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onStartListening() { update() }

    override fun onClick() {
        val prefs = Prefs(this)
        if (!OrbService.running) {
            prefs.orbHidden = false
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        // 同一进程：改设置，OrbService 的设置监听立刻隐藏 / 恢复
        prefs.orbHidden = !prefs.orbHidden
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val hidden = Prefs(this).orbHidden
        t.label = Preset.appName + " 小球"
        t.state = when { !OrbService.running -> Tile.STATE_INACTIVE; hidden -> Tile.STATE_INACTIVE; else -> Tile.STATE_ACTIVE }
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = when { !OrbService.running -> "未启动"; hidden -> "已隐藏"; else -> "显示中" }
        t.updateTile()
    }
}
