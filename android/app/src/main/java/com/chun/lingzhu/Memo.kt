package com.chun.lingzhu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 随手记（M3）：常驻通知上直接打字记下来，不用开 App。
 * 落 filesDir/memo.json（同 History 的模式），可以在设置页看、可以一键转成待办（[Todo]）。
 * 列表 / 增删 / 转待办都是纯函数，Android 那层只在 [Store] 和 [MemoReceiver] 里。
 */
object Memo {
    const val MAX = 300
    const val FILE = "memo.json"
    const val CHANNEL = "memo"
    const val NOTI_ID = 40
    /** 通知里 inline 输入框的 key —— 也用在 RemoteInput.getResultsFromIntent() 取值 */
    const val EXTRA_TEXT = "memo_text"

    /** text = 记的内容；todoId > 0 = 已转成那条待办（点待办时跳过去） */
    data class Item(val id: Long, val text: String, val at: Long, val todoId: Long = 0L)

    fun parse(json: String?): List<Item> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) null
                else Item(o.optLong("id"), text, o.optLong("at"), o.optLong("todoId"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Item>): String = JSONArray().apply {
        for (m in list) put(JSONObject().put("id", m.id).put("text", m.text).put("at", m.at).put("todoId", m.todoId))
    }.toString()

    /** 加到最前；和最新一条一样就不重复记（连着两次输入同一句多半是手滑）；超上限先丢最老的 */
    fun add(list: List<Item>, m: Item, max: Int = MAX): List<Item> {
        if (m.text.isBlank()) return list
        if (list.firstOrNull()?.text == m.text) return list
        val id = if (list.any { it.id == m.id }) (list.maxOfOrNull { it.id } ?: 0L) + 1 else m.id
        return trim(listOf(m.copy(id = id)) + list, max)
    }

    fun trim(list: List<Item>, max: Int): List<Item> = if (list.size <= max) list else list.take(max)

    fun remove(list: List<Item>, id: Long): List<Item> = list.filter { it.id != id }

    /** 标记「已转待办」；同时只留一条待办 id，改一次不会生出两条 */
    fun linked(list: List<Item>, id: Long, todoId: Long): List<Item> = list.map { if (it.id == id) it.copy(todoId = todoId) else it }

    /** 还没转成待办的 */
    fun loose(list: List<Item>): List<Item> = list.filter { it.todoId <= 0L }

    /** 某一天记的（[dayStart] / [dayEnd] 是那天的毫秒区间），新的在前 */
    fun onDay(list: List<Item>, dayStart: Long, dayEnd: Long): List<Item> = list.filter { it.at in dayStart until dayEnd }

    /** 一天的随手记摘要（早晚简报用）：「记了 3 条：交周报、买菜、给妈回电话」 */
    fun digest(list: List<Item>, max: Int = 3): String {
        if (list.isEmpty()) return "没有随手记"
        val t = list.take(max).joinToString("、") { it.text.trim().lineSequence().first().take(20) }
        return "记了 ${list.size} 条：$t" + if (list.size > max) " 等" else ""
    }

    /** 「M月d日 HH:mm」（列表/转待办展示用） */
    fun fmtAt(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
            String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    fun clip(text: String, n: Int = 200): String = text.trim().let { if (it.length <= n) it else it.take(n) + "…" }

    /** 读写文件（内存副本 + 后台落盘，同 History.Store） */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)
        fun list(): List<Item> = FileMemo.get(file) { parse(it) }
        private fun save(l: List<Item>) = FileMemo.put(file, l) { toJson(it) }

        /** 返回这条的 id；空文本返回 -1 */
        @Synchronized fun add(text: String, at: Long = System.currentTimeMillis()): Long {
            if (text.isBlank()) return -1L
            val l = add(list(), Item(at, clip(text), at))
            save(l)
            return l.first().id
        }
        @Synchronized fun remove(id: Long) = save(remove(list(), id))
        @Synchronized fun link(id: Long, todoId: Long) = save(linked(list(), id, todoId))
        @Synchronized fun clear() = FileMemo.clear(file, emptyList<Item>())
        fun get(id: Long): Item? = list().firstOrNull { it.id == id }
    }
}

/** 通知里 inline 输入的文本 → 落盘；也处理「转成待办」那个动作 */
class MemoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            MemoReceiver.A_ADD -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Memo.EXTRA_TEXT)?.toString().orEmpty()
                if (text.isNotBlank()) {
                    Memo.Store(app).add(text)
                    MemoNotify.refresh(app)
                    try { android.widget.Toast.makeText(app, "记下了", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
            }
            MemoReceiver.A_LIST -> try { app.startActivity(Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_MEMO, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    companion object {
        const val A_ADD = "com.chun.lingzhu.MEMO_ADD"
        const val A_LIST = "com.chun.lingzhu.MEMO_LIST"
        const val REQ = 2101
    }
}

/**
 * 随手记的通知：一条常驻通知，右侧 inline 输入框直接打字，下面是「看全部」。
 * 单独一个渠道（低优先级、不打扰），用户不点就不响。
 */
object MemoNotify {
    fun build(app: Context, count: Int): Notification {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(Memo.CHANNEL, "随手记", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val icon = Icon.createWithResource(app, R.drawable.ic_orb)
        val rmi = RemoteInput.Builder(Memo.EXTRA_TEXT).setLabel("想到什么就写一句…").build()
        val add = PendingIntent.getBroadcast(app, MemoReceiver.REQ, Intent(app, MemoReceiver::class.java).setAction(MemoReceiver.A_ADD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // inline 输入必须可写，否则系统不给键盘
        val list = PendingIntent.getBroadcast(app, MemoReceiver.REQ + 1, Intent(app, MemoReceiver::class.java).setAction(MemoReceiver.A_LIST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val last = Memo.Store(app).list().firstOrNull()?.text?.let { "\n最近：${Memo.clip(it, 40)}" } ?: "\n还没有记过"
        return Notification.Builder(app, Memo.CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle("随手记${if (count > 0) "（$count）" else ""}")
            .setContentText("在下面输入框写一句就存下来了$last")
            .setStyle(Notification.BigTextStyle().bigText("在下面输入框写一句就存下来了，共 $count 条。$last"))
            .setContentIntent(PendingIntent.getActivity(app, 61, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_MEMO, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(icon, "随手记", add).addRemoteInput(rmi).setAllowGeneratedReplies(false).build())
            .addAction(Notification.Action.Builder(icon, "看全部", list).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 重新发一遍（记完一条、设置页改动之后调） */
    fun refresh(app: Context) {
        try { app.getSystemService(NotificationManager::class.java)?.notify(Memo.NOTI_ID, build(app, Memo.Store(app).list().size)) } catch (_: Exception) {}
    }

    fun post(app: Context) = refresh(app)
    fun cancel(app: Context) {
        try { app.getSystemService(NotificationManager::class.java)?.cancel(Memo.NOTI_ID) } catch (_: Exception) {}
    }
}