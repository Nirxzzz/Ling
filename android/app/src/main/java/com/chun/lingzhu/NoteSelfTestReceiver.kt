package com.chun.lingzhu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * v2.4 记事本自检 / 速记的广播入口。
 *
 *   adb shell am broadcast -a com.chun.lingzhu.NOTE_SELFTEST   → 跑数据层自检，结果在 logcat
 *   adb shell am broadcast -a com.chun.lingzhu.NOTE_QUICK      → 直接弹速记浮层
 *
 * ## 为什么要有这个
 * 悬浮球平时是缩边状态，屏幕上只露出约 26px 的一角，3 秒后又缩回去；
 * 自动化点它时坐标稍微偏一点就落到桌面上了。要验证「速记浮层本身对不对」，
 * 不该把「我能不能点中那个角」也混进来 —— 那是两件事，混在一起失败时说不清是谁的错。
 * 所以留一条直达入口，让验证只测功能，不测手指。
 */
class NoteSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_QUICK -> {
                // 要先有小球（服务）在跑，截图通道才活着；没跑就先不起，直接告诉用户
                if (!Prefs(ctx).orbWanted) {
                    toast(ctx, "悬浮球没开，先启动悬浮球")
                    return
                }
                val i = Intent(ctx, OrbService::class.java).setAction(OrbService.ACTION_NOTE)
                try {
                    ctx.startForegroundService(i)
                } catch (e: Exception) {
                    try { ctx.startService(i) } catch (e2: Exception) { toast(ctx, "起不来速记") }
                }
            }
            else -> {
                val (pass, fails) = NoteSelfTest.run(ctx)
                val msg = if (fails.isEmpty()) "自检通过 " + pass + " 项"
                          else "自检 " + pass + " 通过 / " + fails.size + " 失败"
                android.util.Log.i("NoteSelfTest", msg)
                toast(ctx, msg)
            }
        }
    }

    private fun toast(ctx: Context, s: String) {
        try { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_SELFTEST = "com.chun.lingzhu.NOTE_SELFTEST"
        const val ACTION_QUICK = "com.chun.lingzhu.NOTE_QUICK"
    }
}
