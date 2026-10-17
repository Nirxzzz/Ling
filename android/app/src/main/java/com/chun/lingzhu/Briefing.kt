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
 * 早晚简报（M3）：一条通知把「今天有什么事」讲清楚 —— 早上说今天要做的，晚上说今天做得怎么样。
 * 默认全开、零配置：7:30 早报、22:00 晚报（两个时刻都能在设置页调，也能各自关掉）。
 *
 * 为什么要它：[Todo] 和 [Memo] 各自都入库，但「攒了什么」没人主动讲 —— 这一条就是那个汇总的口子。
 * 「主动提醒」纪律（[PopupPolicy]）管的是「临时想起一件事要不要弹」，简报管的是「今天整体什么状况」，
 * 两者不冲突：简报每天固定两条，是用户自己约定好的，不占那 8 次弹出额度。
 *
 * 正文生成是纯函数（[morning] / [night]，单测直接测），Android 那层只在 [Receiver] / [post] 里排闹钟、发通知。
 * **没什么可说就不发**（返回 null）—— 宁可少一条，也不要每天早上收到一句「今天没有待办」。
 */
object Briefing {
    const val CHANNEL = "briefing"
    const val NOTI_ID = 60
    const val MORNING_DEFAULT = 7 * 60 + 30      // 7:30
    const val NIGHT_DEFAULT = 22 * 60            // 22:00
    /** 简报里最多列几条待办 / 随手记（再多通知就被撑爆了，剩下的写「等 N 件」） */
    const val MAX_TITLES = 3

    const val ACTION_MORNING = "com.chun.lingzhu.BRIEF_MORNING"
    const val ACTION_NIGHT = "com.chun.lingzhu.BRIEF_NIGHT"
    private const val REQ_MORNING = 2401
    private const val REQ_NIGHT = 2402

    /** 一条简报 = 标题 + 正文 */
    data class Brief(val title: String, val body: String)

    fun fmtMin(minute: Int): String = PopupPolicy.fmtMin(minute)
    fun normMin(minute: Int): Int = PopupPolicy.normMin(minute)

    /**
     * 早报：今天到点还没做的待办 + 还没转成待办的随手记。
     *
     * 随手记这一项是故意的：随手记最大的问题不是「记不下来」，是「记完就沉在通知栏里再也不看」。
     * 早上把它捞回来一次，那些「昨天想到但没做」的就有机会变成真的待办。
     * 两样都空 → null（今天确实没事，不打扰）。
     */
    fun morning(todos: List<Todo.Item>, looseMemos: List<Memo.Item>): Brief? {
        val t = todos.filter { !it.done }
        val m = looseMemos.filter { it.todoId <= 0L }
        if (t.isEmpty() && m.isEmpty()) return null
        val lines = ArrayList<String>(2)
        if (t.isNotEmpty()) lines.add(todoLine(t))
        if (m.isNotEmpty()) lines.add(memoLine(m))
        return Brief("早 · 今天这些事", lines.joinToString("\n"))
    }

    /**
     * 晚报：今天做完了几件、还剩几件、今天记了几条。
     * 一件都没做完也不要报 —— 晚上催「你今天一件都没做完」除了让人烦没别的用，只报事实。
     */
    fun night(todos: List<Todo.Item>, todayMemos: List<Memo.Item>): Brief? {
        val done = todos.count { it.done }
        val left = todos.count { !it.done }
        if (done == 0 && left == 0 && todayMemos.isEmpty()) return null
        val lines = ArrayList<String>(3)
        lines.add((if (done > 0) "做完 $done 件" else "今天还没做完一件") + if (left > 0) "，还剩 $left 件" else "，清空了 ✓")
        if (todayMemos.isNotEmpty()) lines.add(memoLine(todayMemos))
        if (done > 0 && left == 0) lines.add("今天的事都结了，歇着吧")
        return Brief("晚 · 今天怎么样", lines.joinToString("\n"))
    }

    /** 「要做 3 件：交周报、买菜、给妈回电话 等 3 件」 */
    fun todoLine(items: List<Todo.Item>, max: Int = MAX_TITLES): String {
        val t = items.joinToString("、") { it.text.trim().lineSequence().first().take(16) }
        val tail = if (items.size > max) " 等 ${items.size} 件" else ""
        return "要做 ${items.size} 件：$t$tail"
    }

    fun memoLine(items: List<Memo.Item>, max: Int = MAX_TITLES): String =
        Memo.digest(items, max)

    /**
     * 离下一个 [targetMin]（当天第几分钟）还有多少分钟。
     * 刚好等于目标时刻按「已经过了、等明天」算（差 0 分钟会让 set() 立刻响，然后进入每分钟一次的死循环）。
     */
    fun delayTo(nowMin: Int, targetMin: Int): Int {
        val d = normMin(targetMin) - normMin(nowMin)
        return if (d > 0) d else d + 1440
    }

    /** 现在该不该发早/晚报：这个开关开了没 */
    fun enabled(action: String, morningOn: Boolean, nightOn: Boolean): Boolean = when (action) {
        ACTION_MORNING -> morningOn
        ACTION_NIGHT -> nightOn
        else -> false
    }

    // ---------------- Android 那层 ----------------

    /** 排两个闹钟（各一次，响完由 [Receiver] 自我续订） */
    fun schedule(ctx: Context, p: Prefs, now: Long = System.currentTimeMillis()) {
        val minute = PopupPolicy.minuteOfDay(now)
        if (p.briefMorningOn) alarm(ctx, REQ_MORNING, ACTION_MORNING, p.briefMorningEffectiveMin, minute, now)   // v1.73：统一弹出时刻
        if (p.briefNightOn) alarm(ctx, REQ_NIGHT, ACTION_NIGHT, p.briefNightEffectiveMin, minute, now)
    }

    /**
     * v1.73 纯的「今天该排哪几个闹钟」：开关决定排不排，传进来的时刻决定排在几点
     * （调用方传 effective 值 = 统一开着就跟统一时刻走）。[schedule] 的 Android 层
     * 就是把这里的每一对 (action, atMin) 交给 AlarmManager —— 单测直接测它。
     */
    fun scheduleAt(
        morningOn: Boolean, morningMin: Int,
        nightOn: Boolean, nightMin: Int,
    ): List<Pair<String, Int>> = buildList {
        if (morningOn) add(ACTION_MORNING to normMin(morningMin))
        if (nightOn) add(ACTION_NIGHT to normMin(nightMin))
    }

    /** 设置页改完时刻 / 开关后重排 */
    fun reschedule(ctx: Context) = schedule(ctx, Prefs(ctx))

    private fun alarm(ctx: Context, req: Int, action: String, atMin: Int, nowMin: Int, now: Long) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC_WAKEUP,
                now + delayTo(nowMin, atMin) * 60_000L,
                PendingIntent.getBroadcast(ctx, req, Intent(ctx, BriefingReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } catch (_: Exception) {}
    }

    fun post(app: Context, b: Brief) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "早晚简报", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(NOTI_ID, Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(b.title)
                .setContentText(b.body)
                .setStyle(Notification.BigTextStyle().bigText(b.body))
                .setContentIntent(PendingIntent.getActivity(app, 60, Intent(app, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ASSIST, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }
}

/** 闹钟响 → 生成正文 → 发一条；同时给自己排下一次（set() 是一次性的，不续订就只响一次） */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val action = intent.action.orEmpty()
        val now = System.currentTimeMillis()
        val (ds, de) = Todo.dayRange(now)
        val brief = if (!Briefing.enabled(action, p.briefMorningOn, p.briefNightOn)) null else {
            val todos = Todo.Store(app).list().filter { it.dueAt in ds until de }
            val memos = Memo.Store(app).list()
            if (action == Briefing.ACTION_MORNING) Briefing.morning(todos, memos) else Briefing.night(todos, Memo.onDay(memos, ds, de))
        }
        if (brief != null) Briefing.post(app, brief)
        // 续订（set() 是一次性的）：响完把下一次排上；开关已经关掉的那个不续
        if (Briefing.enabled(action, p.briefMorningOn, p.briefNightOn)) Briefing.schedule(app, p, now)
    }

    companion object {
        /** 开机 / 服务起来后把两个闹钟排上 */
        fun kick(ctx: Context) = Briefing.schedule(ctx, Prefs(ctx))
    }
}