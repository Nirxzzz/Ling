package com.chun.lingzhu

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * v1.64 更多入口：桌面图标长按的快捷方式、下拉快捷开关，都落到这个透明中转页 → 交给悬浮球。
 * 「识别屏幕 / 框选」要等本页和下拉栏收干净再截（延迟 450ms），不然会把它们截进去。小球没开着会顺带启动。
 */
class QuickActivity : Activity() {
    companion object {
        const val EXTRA_DO = "do"
        const val SOLVE = "solve"; const val REGION = "region"; const val PICK = "pick"; const val CLIP = "clip"

        fun intent(ctx: Context, what: String) = Intent(ctx, QuickActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_DO, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** 桌面图标长按出来的四个快捷方式（动态发布：内测 / 公测包名不同，静态 xml 写不了） */
        fun publishShortcuts(ctx: Context) {
            if (Build.VERSION.SDK_INT < 25) return
            try {
                val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
                val icon = Icon.createWithResource(ctx, R.mipmap.ic_launcher)   // ic_orb 是白色小图标，在桌面菜单里是白底白图看不见
                fun s(id: String, short: String, long: String, what: String) = ShortcutInfo.Builder(ctx, id)
                    .setShortLabel(short).setLongLabel(long).setIcon(icon).setIntent(intent(ctx, what)).build()
                val all = listOf(
                    s("solve", "识别屏幕", "识别当前屏幕", SOLVE),
                    s("region", "框选识别", "框出一块再识别", REGION),
                    s("pick", "从相册识图", "从相册选图识别", PICK),
                    s("clip", "翻译复制的", "翻译刚复制的文字", CLIP))
                sm.dynamicShortcuts = all.take(sm.maxShortcutCountPerActivity.coerceAtLeast(1))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent?.getStringExtra(EXTRA_DO)
        when (what) {
            CLIP -> startActivity(TranslateActivity.clipboardIntent(this))
            PICK -> startActivity(ShareActivity.pickIntent(this))
            SOLVE, REGION -> {
                if (!Settings.canDrawOverlays(this)) {
                    android.widget.Toast.makeText(this, "先打开「显示在其他应用上层」权限", android.widget.Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, MainActivity::class.java))
                } else try {
                    startService(Intent(this, OrbService::class.java)
                        .setAction(if (what == SOLVE) OrbService.ACTION_SOLVE else OrbService.ACTION_REGION)
                        .putExtra(OrbService.EXTRA_DELAY, 450L))
                } catch (e: Exception) { android.widget.Toast.makeText(this, "启动悬浮球失败：" + (e.message ?: ""), android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** 下拉快捷开关「识别屏幕」/「框选识别」（v1.64）：点一下收起下拉栏再识别 */
abstract class ActionTile(private val what: String, private val label: String) : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        t.label = label; t.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = Preset.appName
        t.updateTile()
    }
    override fun onClick() {
        val i = QuickActivity.intent(this, what)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, what.hashCode(), i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
class SolveTile : ActionTile(QuickActivity.SOLVE, "识别屏幕")
class RegionTile : ActionTile(QuickActivity.REGION, "框选识别")
