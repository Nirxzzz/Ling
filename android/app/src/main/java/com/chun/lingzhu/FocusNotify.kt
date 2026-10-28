package com.chun.lingzhu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 专注守护的提醒通知（v2.3.2 第 9 条）。
 *
 * ## 为什么单独一个文件
 * 渠道、图标、PendingIntent 这些是 Android 那层的事，判定（[FocusGuard]）和报告（[FocusReport]）
 * 都是纯函数 —— 混在一起就没法在 JVM 单测里测判据了。这里只管「怎么把一句话送到通知栏」。
 *
 * ## 为什么是 IMPORTANCE_LOW
 * 这是**温和提醒**，不是报警：不响铃、不震动、不弹横幅，只在通知栏静静占一行，用户抬眼就能看见。
 * 用 DEFAULT 或 HIGH 的后果是每 10 分钟打断一次正在做事的人 —— 提醒变成了打扰，
 * 用户第一件事就是把开关关掉，这个功能就废了。
 *
 * ## 通知 ID 固定
 * 同一段「离开学习」里最多只留一条提醒：新的一条覆盖旧的，不堆成一列。
 * 用户点开报告页以后按「自动消失」（[Notification.Builder.setAutoCancel]）收掉，不用手动清理。
 */
object FocusNotify {

    /** 渠道 id：和其它模块（复习 review、简报、随手记）不撞 */
    const val CHANNEL = "focus"

    /** 渠道名，显示在系统设置里 */
    const val CHANNEL_NAME = "专注提醒"

    /** 固定通知 id：同一条提醒只留一条，新的覆盖旧的 */
    const val NOTI_ID = 69

    /** 打开报告页的 PendingIntent 请求码：68 被复习提醒占了，这里从 69 起 */
    const val REQ_OPEN = 69

    /**
     * 建渠道。Android 8+ 不建渠道 notify() 直接不显示，所以每次发之前都调一遍 ——
     * 重复建同一个渠道是幂等的（系统会忽略除名字/重要性以外的改动），不用担心刷屏。
     */
    fun channel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        } catch (_: Throwable) {
            // 渠道建不起来（极少见：厂商 ROM 抠掉了）也不该让守护逻辑崩 —— 面板那一行状态还是能写的
        }
    }

    /**
     * 发一条提醒。点了打开 [FocusReportActivity]（今天学了多久、离开了几次，都在那一页）。
     *
     * 任何异常都吞掉：这个方法在自动扫描的 worker 线程上被调用，
     * 一次通知失败（没权限 / 渠道被用户关了）不该让正在跑的识别流程断掉。
     */
    fun post(ctx: Context, title: String, text: String) {
        try {
            channel(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                ctx, REQ_OPEN, Intent(ctx, FocusReportActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                NOTI_ID,
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_orb)
                    .setContentTitle(title)
                    .setContentText(text)
                    // 文案可能略长（第三档会带「今天第 N 次」），展开时整段显示，不要被截成省略号
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Throwable) {
        }
    }

    /** 用户回来学习了：把还挂在通知栏的那条收掉，别让它一直占着 */
    fun cancel(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID)
        } catch (_: Throwable) {
        }
    }
}
