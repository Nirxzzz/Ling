package com.chun.lingzhu

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * 弹出纪律的 Android 那层：纪律本身在 [PopupPolicy]（纯函数、可单测），这里只负责
 * 「发通知 / 收动作 / 排闹钟」。
 *
 * 一次弹窗 = 一条通知（「主动提醒」渠道），四个动作：
 *  ✓ 处理（升一档）· 稍后 30 分钟（同一事件最多 2 次）· 这类别再弹（静音 7 天）· 忽略（连 3 次降档改汇总）。
 * 被纪律挡下的（弹满 / 静音 / 稍后用完 / 总开关关）一律只写 History，不发通知 —— 「不弹」是这个功能的核心。
 */
object PopupCenter {
    const val CHANNEL = "popup"
    const val NOTI_ID = 30
    const val NOTI_SUMMARY_ID = 31
    /** 写进 History 的 kind，区别于「识别 / 翻译」两类 */
    const val KIND_EVENT = "event"

    // 通知上的动作
    const val A_HANDLE = "handled"
    const val A_SNOOZE = "snooze"
    const val A_MUTE = "mute"
    const val A_IGNORE = "ignore"

    // 闹钟动作
    const val A_ALARM_SNOOZE = "com.chun.lingzhu.POPUP_SNOOZE"
    const val A_ALARM_DRAIN = "com.chun.lingzhu.POPUP_DRAIN"
    private const val REQ_SNOOZE = 2001
    private const val REQ_DRAIN = 2002

    fun cfg(p: Prefs) = PopupPolicy.Config(
        on = p.popupOn,
        dailyCap = p.popupDailyCap,
        catCaps = PopupPolicy.CATS.associateWith { p.popupCatCap },
        quietOn = p.popupQuietOn,
        quietStart = p.popupQuietStart,
        quietEnd = p.popupQuietEnd,
    )

    /**
     * 只要**判定 + 计数**这一层，自己不发通知、也不记历史 —— 给「到点弹卡」那条链用。
     *
     * 为什么要单独开一个口：到点弹卡（[TodoNotify.fire]）的「弹」是另一套（球边浮卡 + 震动），
     * 但它必须和「主动提醒」**共用同一个池子**（每日上限 / 免打扰 / 类别静音 / 连续忽略降频）。
     * [offer] 会自己发一条通知 + 写 History，跟弹卡叠在一起就变成「弹一次卡 + 又发一条主动提醒」；
     * 所以这里只用 [PopupPolicy.decide] 那层：放行（SHOW / SHOW_SUMMARY）就顺手 [PopupPolicy.onShown]
     * 计入额度并写回 [Prefs.popupState]，返回 true；其余判定原样返回 false，由调用方退成常规通知。
     */
    fun allow(ctx: Context, ev: PopupPolicy.Event, now: Long = System.currentTimeMillis()): Boolean {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val c = cfg(p)
        val today = p.todayInt()
        val s = PopupPolicy.parse(p.popupState)
        val v = PopupPolicy.decide(s, ev, now, c, PopupPolicy.minuteOfDay(now), today)
        return when (v) {
            PopupPolicy.Verdict.SHOW, PopupPolicy.Verdict.SHOW_SUMMARY -> {
                p.popupState = PopupPolicy.toJson(PopupPolicy.onShown(s, ev, today))
                true
            }
            else -> false
        }
    }

    /**
     * 决定 + 执行：弹了就发通知，被挡就只记历史。返回实际判定，设置页和调试用。
     * 事件一律入库 —— 静音和弹满期间也要能回看「今天本来该提醒我什么」。
     */
    fun offer(ctx: Context, ev: PopupPolicy.Event, now: Long = System.currentTimeMillis()): PopupPolicy.Verdict {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val c = cfg(p)
        var s = PopupPolicy.parse(p.popupState)
        val today = p.todayInt()
        val minute = PopupPolicy.minuteOfDay(now)
        val v = PopupPolicy.decide(s, ev, now, c, minute, today)
        when (v) {
            PopupPolicy.Verdict.SHOW, PopupPolicy.Verdict.SHOW_SUMMARY -> {
                s = PopupPolicy.onShown(s, ev, today)
                postEvent(app, ev, v == PopupPolicy.Verdict.SHOW_SUMMARY, s, c)
            }
            PopupPolicy.Verdict.QUEUED -> {
                s = PopupPolicy.enqueue(s, ev)
                scheduleDrain(app, c, now)
            }
            // SNOOZED：已经排过闹钟了，等它响；其余判定什么都不做（只记历史）
            else -> Unit
        }
        History.Store(app).add(KIND_EVENT, PopupPolicy.label(ev.cat), ev.title)
        p.popupState = PopupPolicy.toJson(s)
        return v
    }

    /** 弹一条事件通知（正常式 or 汇总式） */
    fun postEvent(app: Context, ev: PopupPolicy.Event, summary: Boolean, s: PopupPolicy.State, c: PopupPolicy.Config) {
        val title = (if (summary) "顺手看一眼 · " else "") + ev.title
        val body = if (summary) PopupPolicy.summaryBody(s, ev, c) else "${PopupPolicy.label(ev.cat)} · 今天已弹 ${s.shown}/${c.dailyCap} 次"
        notify(app, NOTI_ID, title, body) {
            addAction(act(app, "✓ 处理", A_HANDLE, ev))
            addAction(act(app, "稍后 " + PopupPolicy.snoozeLabel(Prefs(app).popupSnoozeMin), A_SNOOZE, ev))
            addAction(act(app, "这类别再弹", A_MUTE, ev))
            addAction(act(app, "忽略", A_IGNORE, ev))
        }
    }

    /** 免打扰时段结束：把排队的合并成一条摘要 */
    fun summary(app: Context, text: String) {
        notify(app, NOTI_SUMMARY_ID, "刚才攒下的事", text) {}
    }

    private fun notify(app: Context, id: Int, title: String, body: String, actions: Notification.Builder.() -> Unit) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "主动提醒", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(id, Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntent.getActivity(app, 62, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .apply(actions)
                .build())
        } catch (_: Exception) {}
    }

    private fun act(app: Context, label: String, action: String, ev: PopupPolicy.Event): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(app, R.drawable.ic_orb), label,
            PendingIntent.getBroadcast(app, action.hashCode(), Intent(app, PopupReceiver::class.java)
                .setAction(action).putExtra("key", ev.key).putExtra("cat", ev.cat).putExtra("title", ev.title),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ).build()

    // ---------------- 稍后 / 免打扰结束的闹钟 ----------------

    fun scheduleSnooze(ctx: Context, ev: PopupPolicy.Event, delayMin: Int, now: Long = System.currentTimeMillis()) = alarm(
        ctx, REQ_SNOOZE, A_ALARM_SNOOZE, now + delayMin * 60_000L,
        Intent(ctx, PopupReceiver::class.java).setAction(A_ALARM_SNOOZE)
            .putExtra("key", ev.key).putExtra("cat", ev.cat).putExtra("title", ev.title))

    /** 免打扰结束那一刻醒一次，把排队的合并成一条摘要 */
    fun scheduleDrain(ctx: Context, c: PopupPolicy.Config, now: Long = System.currentTimeMillis()) {
        if (!c.quietOn) return
        val gap = PopupPolicy.normMin(c.end()) - PopupPolicy.minuteOfDay(now)
        alarm(ctx, REQ_DRAIN, A_ALARM_DRAIN, now + (if (gap > 0) gap else gap + 1440) * 60_000L,
            Intent(ctx, PopupReceiver::class.java).setAction(A_ALARM_DRAIN))
    }

    private fun alarm(ctx: Context, req: Int, action: String, at: Long, i: Intent) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC_WAKEUP, at,
                PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } catch (_: Exception) {}
    }
}

/** 通知上的四个动作 + 稍后 / 合并闹钟，都进这里 */
class PopupReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val c = PopupCenter.cfg(p)
        val now = System.currentTimeMillis()
        val today = p.todayInt()
        val minute = PopupPolicy.minuteOfDay(now)
        val ev = PopupPolicy.Event(intent.getStringExtra("key").orEmpty(), intent.getStringExtra("cat").orEmpty(), intent.getStringExtra("title").orEmpty())
        var s = PopupPolicy.parse(p.popupState)

        when (intent.action) {
            PopupCenter.A_HANDLE -> {
                s = PopupPolicy.onHandled(s, ev.cat)
                p.popupState = PopupPolicy.toJson(s)
                try { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                return
            }
            PopupCenter.A_SNOOZE -> {
                val snoozeMin = p.popupSnoozeMin
                val delay = PopupPolicy.snoozeDelay(snoozeMin, minute, c.end())
                s = PopupPolicy.onSnooze(s, ev, now, delay)
                s = s.copy(pending = PopupPolicy.addPending(s.pending, ev))
                PopupCenter.scheduleSnooze(app, ev, delay, now)
            }
            PopupCenter.A_MUTE -> s = PopupPolicy.mute(s, ev.cat, now)
            PopupCenter.A_IGNORE -> s = PopupPolicy.onIgnored(s, ev.cat)
            PopupCenter.A_ALARM_SNOOZE -> {
                // 稍后到点：重新走一遍 decide —— 这期间额度满了 / 用户静音了，就不再弹
                val rolled = PopupPolicy.rollDay(s, today)
                when (PopupPolicy.resnooze(rolled, ev, now, c, minute, today)) {
                    PopupPolicy.Verdict.SHOW, PopupPolicy.Verdict.SHOW_SUMMARY -> {
                        s = PopupPolicy.onShown(rolled, ev, today)
                        p.popupState = PopupPolicy.toJson(s)
                        PopupCenter.postEvent(app, ev, PopupPolicy.isSummary(s, ev.cat), s, c)
                        return
                    }
                    PopupPolicy.Verdict.QUEUED -> { s = PopupPolicy.enqueue(rolled, ev); PopupCenter.scheduleDrain(app, c, now) }
                    else -> s = rolled
                }
            }
            PopupCenter.A_ALARM_DRAIN -> {
                val drained = PopupPolicy.drainQueue(s, now, c, minute) ?: return
                PopupCenter.summary(app, drained.first)
                s = drained.second
                if (s.queued.isNotEmpty()) PopupCenter.scheduleDrain(app, c, now)   // 时段里又攒了新的
            }
            else -> return
        }
        p.popupState = PopupPolicy.toJson(s)
    }
}