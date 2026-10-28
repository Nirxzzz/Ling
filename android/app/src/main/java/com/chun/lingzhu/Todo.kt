package com.chun.lingzhu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 待办（M3）：本地触发（AlarmManager，和看门狗同一套思路），到期发一条通知 → 点「写进日历」才进系统日历。
 *
 * ⚠️ 铁律：不静默写系统日历。[TodoActivity] 必须先弹确认卡（标题 / 时间 / 目标日历），
 * 用户点了确认才调 CalendarContract；写成功把 event id 记在条目上，撤销只删自己建的那一条。
 *
 * 列表 / 排序 / 到期判断 / 简报文案都是纯函数，Android 那层在 [Store] 和 [TodoReceiver]。
 */
object Todo {
    const val MAX = 200
    const val FILE = "todo.json"
    const val CHANNEL = "todo"
    const val NOTI_ID = 50

    /**
     * @param dueAt    什么时候提醒（毫秒）
     * @param done     做完了
     * @param calId    已写进系统日历的事件 id（0 = 没写）；只用来撤销，别人的条目碰不到
     * @param calendar 写进了哪个日历的名字（设置页显示用）
     * @param ruleId   v1.86：这条是**记期规则生成的**（[Schedule.Rule.id]，0 = 手加的）。
     *                 规则改了 / 删了 / 过期了，[Schedule.sync] 靠它把没做完的生成项收回去；做完的留作痕迹。
     * @param vibe     v1.86：这条自己的**提醒强度**（[Vibrate] 档位，-1 = 跟全局设置）。
     *                 影响「试弹」及通知提醒震动强度。
     */
    data class Item(
        val id: Long,
        val text: String,
        val dueAt: Long,
        val createdAt: Long = 0L,
        val done: Boolean = false,
        val note: String = "",
        val calId: Long = 0L,
        val calendar: String = "",
        val ruleId: Long = 0L,
        val vibe: Int = -1,
    )

    fun parse(json: String?): List<Item> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) null
                else Item(o.optLong("id"), text, o.optLong("dueAt"), o.optLong("createdAt"), o.optBoolean("done"),
                    o.optString("note"), o.optLong("calId"), o.optString("calendar"),
                    o.optLong("ruleId"), if (o.has("vibe")) o.optInt("vibe", -1) else -1)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Item>): String = JSONArray().apply {
        for (t in list) put(JSONObject().put("id", t.id).put("text", t.text).put("dueAt", t.dueAt).put("createdAt", t.createdAt)
            .put("done", t.done).put("note", t.note).put("calId", t.calId).put("calendar", t.calendar)
            .put("ruleId", t.ruleId).put("vibe", t.vibe))
    }.toString()

    /** 加到最前；和最新一条文字 + 时间都一样就不重复记 */
    fun add(list: List<Item>, t: Item, max: Int = MAX): List<Item> {
        if (t.text.isBlank()) return list
        val first = list.firstOrNull()
        if (first != null && first.text == t.text && first.dueAt == t.dueAt) return list
        val id = if (list.any { it.id == t.id }) (list.maxOfOrNull { it.id } ?: 0L) + 1 else t.id
        return trim(listOf(t.copy(id = id)) + list, max)
    }

    /**
     * 「稍后 30 分钟」要新建的那一条（纯函数，可单测）：文案照抄 +「（稍后）」，时间 = [now] + [ms]，
     * **震动档跟着原条目走** —— 用户给这条单独设过强度（记期页逐条设），稍后那一版不该突然变回全局。
     *
     * 有意**不继承 [Item.ruleId]**：规则生成的项靠 ruleId 被 [Schedule.sync] 按「期望集合」收口，
     * 而「now + 30 分钟」这个点不在任何规则的期望集合里 —— 带上 ruleId，下一趟 sync（[TodoReceiver] 响完就调）
     * 立刻会把它当闲置生成项收走，等于「稍后」白点。所以只带走用户看得见 / 摸得着的 provenance（vibe），
     * 规则 provenance 留给原条目自己留着。30 分钟语义（[TodoReceiver.SNOOZE_MS]）不变。
     */
    fun snoozeItem(t: Item, now: Long, ms: Long): Item =
        Item(now, "${t.text}（稍后）", now + ms, now, vibe = t.vibe)

    /** 超上限先删最老的已完成 / 已写进日历的（还没处理的不能被挤掉） */
    fun trim(list: List<Item>, max: Int): List<Item> {
        if (list.size <= max) return list
        val out = list.toMutableList()
        var i = out.size - 1
        while (out.size > max && i >= 0) {
            val t = out[i]
            if (t.done || t.calId > 0) out.removeAt(i)
            i--
        }
        return out
    }

    fun remove(list: List<Item>, id: Long): List<Item> = list.filter { it.id != id }

    fun setDone(list: List<Item>, id: Long, done: Boolean): List<Item> = list.map { if (it.id == id) it.copy(done = done) else it }

    /** 改提醒时间（设置页 / 确认卡里改完立刻生效，会重排闹钟） */
    fun setDue(list: List<Item>, id: Long, dueAt: Long): List<Item> = list.map { if (it.id == id) it.copy(dueAt = dueAt) else it }

    fun setNote(list: List<Item>, id: Long, note: String): List<Item> = list.map { if (it.id == id) it.copy(note = Memo.clip(note, 200)) else it }

    /** 记下写进系统日历的结果（确认卡点了确认 → 回填 event id；撤销 → 清 0） */
    fun setCalendar(list: List<Item>, id: Long, calId: Long, calendar: String): List<Item> =
        list.map { if (it.id == id) it.copy(calId = calId, calendar = calendar) else it }

    /** 按到期时间排（同时间按录入顺序） */
    fun sorted(list: List<Item>): List<Item> = list.sortedWith(compareBy({ it.done }, { it.dueAt }, { it.createdAt }))

    fun pending(list: List<Item>): List<Item> = list.filter { !it.done }

    /** 还没到点的 */
    fun upcoming(list: List<Item>, now: Long): List<Item> = pending(list).filter { it.dueAt > now }

    /** 到点了还没处理 / 已经过点的 */
    fun due(list: List<Item>, now: Long): List<Item> = pending(list).filter { it.dueAt <= now }

    /** 某一天的（[dayStart] / [dayEnd] 是那天的毫秒区间） */
    fun onDay(list: List<Item>, dayStart: Long, dayEnd: Long): List<Item> = list.filter { it.dueAt in dayStart until dayEnd }

    fun get(list: List<Item>, id: Long): Item? = list.firstOrNull { it.id == id }

    /** 待办 → 随手记（反向：待办也能当记录看） */
    fun memoText(t: Item): String = Memo.clip(t.text)

    /** 一天的待办摘要（早晚简报用）：「2 件：交周报、买菜」/「今天没有待办」 */
    fun digest(list: List<Item>, max: Int = 3): String {
        val l = list.sortedWith(compareBy({ it.done }, { it.dueAt }))
        if (l.isEmpty()) return "今天没有待办"
        val undone = l.filter { !it.done }
        val t = undone.take(max).joinToString("、") { it.text.take(16) }
        val tail = if (undone.size > max) " 等 ${undone.size} 件" else ""
        return if (undone.isEmpty()) "${l.size} 件都做完了 ✓" else "${undone.size} 件：$t$tail"
    }

    /** 提醒正文：标题 + 什么时候 */
    fun notiBody(t: Item, now: Long): String {
        val overdue = now - t.dueAt
        val when_ = when {
            overdue < 60_000 -> "刚刚到期"
            overdue < 3600_000 -> "已过期 ${overdue / 60_000} 分钟"
            overdue < 86400_000 -> "已过期 ${overdue / 3600_000} 小时"
            else -> "已过期 ${overdue / 86400_000} 天"
        }
        return if (t.note.isBlank()) when_ else "$when_ · ${Memo.clip(t.note, 60)}"
    }

    /** 「今天 15:30」「明天 08:00」「3 天后」—— 纯算，不碰系统 API（测试里能跑） */

    /**
     * 时间词一整个片段（v1.86 扩）：`下午3点30分` / `晚八点` / `8:30` / `15点` / `上午十点`。
     * 三个捕获组：1 = 上午/下午/晚上…（可空），2 = 时，3 = 分（可空）。
     * 中文数字和阿拉伯数字都认（见 [numOf]）—— 用户口语里「晚八点」比「20 点」更常写。
     */
    internal val TIME_RE = Regex(
        "(凌晨|早上|早晨|清晨|上午|中午|下午|傍晚|晚上|夜里|晚)?" +
            "\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\\s*[点:：时]\\s*([0-9]{1,2}|[一二两三四五六七八九十]{1,2})?\\s*分?"
    )

    /** 只有阿拉伯数字的老写法（[minuteOfDay] 没命中时，strip 用它兜底，行为和 v1.85 一致） */
    private val DIGIT_TIME = Regex("\\d{1,2}[点:：时]\\d{1,2}分?|\\d{1,2}[点:：时]")

    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 「3」/「八」/「十一」/「二十三」→ 数字。认不出 → null。 */
    fun numOf(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.all { it in '0'..'9' }) return t.toIntOrNull()
        val i = t.indexOf('十')
        if (i >= 0) {
            val hi = if (i == 0) 1 else (CN_DIGIT[t[0]] ?: return null)
            val lo = if (i == t.lastIndex) 0 else (CN_DIGIT[t[i + 1]] ?: return null)
            return hi * 10 + lo
        }
        return t.singleOrNull()?.let { CN_DIGIT[it] }
    }

    private val PM = listOf("下午", "傍晚", "晚上", "夜里", "晚")

    /**
     * 从句子里抽出「这一天的第几分钟」（0..1439）；抽不到 → null。
     * 认得 `15:30` / `15点30分` / `下午3点` / `晚八点` / `上午十点`；会把「下午/晚上」折成 24 小时制。
     *
     * 中文数字的**保守规则**：裸的「一点」「两点」不当时刻（口语里是「一点忙」「两点建议」），
     * 要么带上午/下午这类前缀、要么带分钟、要么是三点及以上，才认。阿拉伯数字不受这个限制。
     */
    fun minuteOfDay(raw: String): Int? {
        val m = TIME_RE.find(raw) ?: return null
        val part = m.groupValues[1]
        val hs = m.groupValues[2]
        val h0 = numOf(hs) ?: return null
        val mi = numOf(m.groupValues[3]) ?: 0
        val cnHour = hs.any { it !in '0'..'9' }
        if (cnHour && part.isEmpty() && m.groupValues[3].isEmpty() && h0 < 3) return null   // 「一点」不当 1:00
        var h = h0
        when {
            part in PM -> if (h < 12) h += 12
            part == "中午" -> if (h in 1..5) h += 12
            part == "凌晨" -> if (h == 12) h = 0
        }
        if (h !in 0..23 || mi !in 0..59) return null
        return h * 60 + mi
    }

    /**
     * 星期几的中文写法 → Calendar 的 DAY_OF_WEEK（周日=1 … 周六=7）。
     *
     * 支持「周五 / 星期五 / 礼拜五 / 周天 / 周七」这些常见写法。
     * 返回 null 表示不是星期词。
     */
    fun weekdayOf(raw: String): Pair<Int, IntRange>? {
        val m = WEEKDAY_RE.find(raw) ?: return null
        val ch = m.groupValues[1]
        val dow = when (ch) {
            "一" -> 2; "二" -> 3; "三" -> 4; "四" -> 5; "五" -> 6; "六" -> 7
            "日", "天", "七" -> 1
            else -> return null
        }
        return dow to m.range
    }

    internal val WEEKDAY_RE = Regex("(?:周|星期|礼拜)([一二三四五六日天七])")

    /** 星期几的中文名，给「下周五」这类显示用 */
    fun weekdayName(dow: Int): String = when (dow) {
        1 -> "周日"; 2 -> "周一"; 3 -> "周二"; 4 -> "周三"
        5 -> "周四"; 6 -> "周五"; 7 -> "周六"; else -> ""
    }

    /** 自然语言时间兜底解析：「明天下午3点 / 3小时后 / 20:00 / 晚八点」认不出的部分当标题。纯函数可单测 */
    fun parseWhen(raw: String, now: Long = System.currentTimeMillis()): ParsedWhen {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        var title = raw.trim()
        var due = 0L
        // v1.85.5：中文数字也要认 —— 「一分钟后」原先掉进口语钟点分支算出错误时刻（用户实测：早8点说 → 记23:24）
        val after = Regex("(\\d+|[一二两三四五六七八九十]+)(分钟|小时|天)后").find(title)
        val day = Regex("(今天|明天|后天)").find(title)
        val wk = if (after == null && day == null) weekdayOf(title) else null
        val min = if (after == null) minuteOfDay(title) else null   // 「3 小时后」里的“时”不是钟点
        if (after != null) {
            val n = (numOf(after.groupValues[1]) ?: 1L).toLong().coerceAtLeast(1L)
            due = now + n * 60_000L * when (after.groupValues[2]) { "分钟" -> 1L; "小时" -> 60L; else -> 1440L }
        } else {
            day?.let { cal.add(java.util.Calendar.DAY_OF_YEAR, when (it.value) { "明天" -> 1; "后天" -> 2; else -> 0 }) }
            // 「周五」：算到**下一个**周五。今天就是周五时算下周 —— 说「周五交」
            // 不会是「今天交」，口语里一定指接下来那个周五。
            wk?.let { (dow, _) ->
                val cur = cal.get(java.util.Calendar.DAY_OF_WEEK)
                var delta = (dow - cur + 7) % 7
                if (delta == 0) delta = 7
                cal.add(java.util.Calendar.DAY_OF_YEAR, delta)
            }
            if (min != null) {
                cal.set(java.util.Calendar.HOUR_OF_DAY, min / 60)
                cal.set(java.util.Calendar.MINUTE, min % 60)
                cal.set(java.util.Calendar.SECOND, 0)
                due = cal.timeInMillis
                if (due < now && day == null && wk == null) due += 86_400_000L   // 没说哪天、时间已过 → 算明天
            } else if (day != null || wk != null) {
                cal.set(java.util.Calendar.HOUR_OF_DAY, 9); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0)
                due = cal.timeInMillis
            }
        }
        if (due > 0) {
            // 各分支必含字符，杜绝「全可选分组零长匹配」在每字之间插空格（P0：曾把「交周报」拆成「交 周 报」）
            title = title
                .replace(Regex("(今天|明天|后天)"), " ")
                .replace(WEEKDAY_RE, " ")
                .replace(Regex("(?:\\d+|[一二两三四五六七八九十]+)(分钟|小时|天)后"), " ")
                .let { s -> if (min != null) TIME_RE.replace(s, " ") else DIGIT_TIME.replace(s, " ") }
                .replace(Regex("\\s+"), " ").trim()
        }
        if (title.isEmpty()) title = "提醒"
        return ParsedWhen(title, if (due > 0) due else now + 3_600_000L)
    }
    data class ParsedWhen(val title: String, val dueAt: Long)

    /** 「M/d HH:mm」 */
    fun fmtHM(ms: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.DAY_OF_MONTH)} " +
            String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    /**
     * 「今天 13:18 / 明天 09:30 / 3 天后」这种相对日期说法。
     *
     * ## 必须按日历天算，不能按毫秒差除以 86400000
     * 曾经的写法是 (dueAt - now) / 86_400_000L，也就是「不足 24 小时就算 0 天」。
     * 今天 13:17 存一条明天 09:30 的待办，只差 20 小时，除法得 0，显示成**今天 09:30**。
     * 用户看到的日期直接错了一天。跨夏令时、跨时区时这种算法还会再错一次。
     *
     * 正确做法：两边都对齐到各自当天的 0 点，再相减。
     *
     * ## 时:分用原始 dueAt
     * 调用方如果传的是 dayStart(dueAt)，fmt(dueAt) 会得到 00:00 ——
     * 待办列表曾经就是这样，所有条目全显示「今天 00:00」，因为它们在调用处就把时间截掉了。
     * 所以这里不再相信调用方传的是哪种口径：天数差按日历天算，时:分用传进来的值取。
     */
    fun fmtDue(dueAt: Long, now: Long, fmt: (Long) -> String): String {
        val days = daysBetween(dayStart(now), dayStart(dueAt)) / 86_400_000L
        val hm = fmt(dueAt)
        return when {
            days == 0L -> "今天 $hm"
            days == 1L -> "明天 $hm"
            days == -1L -> "昨天 $hm"
            days > 1 -> "$days 天后"
            else -> "${-days} 天前"
        }
    }

    /** 两个时刻差几天（按自然日算，只取日期部分；用运行时算好的日历偏移，纯函数可测） */
    fun daysBetween(nowDayIndex: Long, dueDayIndex: Long): Long = dueDayIndex - nowDayIndex

    /** 今天的 0 点（毫秒，本机时区） */
    fun dayStart(at: Long): Long = Calendar.getInstance().apply { timeInMillis = at }.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 一天的毫秒区间：当天 0 点 → 次天 0 点 */
    fun dayRange(at: Long): Pair<Long, Long> {
        val s = dayStart(at)
        val c = Calendar.getInstance().apply { timeInMillis = s; add(Calendar.DAY_OF_YEAR, 1) }
        return s to c.timeInMillis
    }

    /**
     * 锁约定：`@Synchronized` 只锁**本实例**，而全 App 到处 `Todo.Store(ctx)` 现 new 一个，
     * 所以实例锁保护不了 todo.json。所有「读 → 改 → 写」一律走 [FileMemo.update]（锁在进程内唯一的 FileMemo 上），
     * 否则用户新增 / 完成 / 删除会被另一路拿着旧快照的整表写入覆盖掉。
     * [list] / [get] / [clear] 只读或由 FileMemo 自己加锁，不需要再加实例锁。
     */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)
        fun list(): List<Item> = FileMemo.get(file) { parse(it) }
        private fun update(f: (List<Item>) -> List<Item>) = FileMemo.update(file, { parse(it) }, { toJson(it) }) { f(it) }

        fun add(text: String, dueAt: Long, note: String = ""): Long {
            if (text.isBlank()) return -1L
            val now = System.currentTimeMillis()
            var id = -1L
            update { l ->
                val next = add(l, Item(now, Memo.clip(text, 120), dueAt, now, note = note))
                id = next.firstOrNull()?.id ?: -1L
                next
            }
            return id
        }
        fun remove(id: Long) = update { remove(it, id) }
        fun setDone(id: Long, done: Boolean) = update { setDone(it, id, done) }
        fun setDue(id: Long, dueAt: Long) = update { setDue(it, id, dueAt) }
        fun setNote(id: Long, note: String) = update { setNote(it, id, note) }
        fun setCalendar(id: Long, calId: Long, calendar: String) = update { setCalendar(it, id, calId, calendar) }
        /** 稍后 30 分钟：新建一条带「（稍后）」的副本（震动档跟着原条目，见 [snoozeItem]），原条目不动 */
        fun snooze(id: Long, ms: Long): Long {
            val now = System.currentTimeMillis()
            var newId = -1L
            update { l ->
                val t = get(l, id) ?: return@update l
                val next = add(l, snoozeItem(t, now, ms))
                newId = next.firstOrNull()?.id ?: -1L
                next
            }
            return newId
        }
        /**
         * v1.86：[Schedule.sync] 物化规则时的**整表重排**。必须与 [sync] 的读取在同一把锁里 ——
         * sync 先 list() 算「保留 + 补齐」，这里再整表覆盖；中间若插进用户的新增 / 完成 / 删除，
         * 就会被旧快照吃掉。sync 在 `synchronized(FileMemo)` 内调用，[update] 可重入同一把锁。
         * 保留 [trim] 上限语义（删掉的是已完成的，不影响未处理的条目）。
         */
        fun replaceAll(l: List<Item>) = update { trim(l, MAX) }
        fun clear() = FileMemo.clear(file, emptyList<Item>())
        fun get(id: Long): Item? = get(list(), id)
    }
}

/** 到点提醒：发一条通知（完成 / 写进日历 / 稍后），写日历必须过 [TodoActivity] 的确认卡 */
class TodoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id <= 0) { reschedule(app); return }
        val store = Todo.Store(app)
        val t = store.get(id) ?: return
        when (intent.action) {
            ACTION_FIRE -> if (!t.done) TodoNotify.fire(app, t)
            ACTION_DONE -> store.setDone(id, true)
            ACTION_SNOOZE -> { val n = store.snooze(id, SNOOZE_MS); if (n > 0) schedule(app, n) }
            else -> return
        }
        // 每次响过都顺手把记期规则往前滚一趟（v1.86）：规则只物化未来两周的点，
        // 手机常年不重启的话，不滚就会「响完最后一个点就再也不响」。sync 自己会重排待办闹钟。
        try { Schedule.sync(app) } catch (_: Exception) { reschedule(app) }
    }

    companion object {
        const val ACTION_FIRE = "com.chun.lingzhu.TODO_FIRE"
        const val ACTION_SNOOZE = "com.chun.lingzhu.TODO_SNOOZE"
        const val ACTION_DONE = "com.chun.lingzhu.TODO_DONE"
        const val EXTRA_ID = "todoId"
        private const val REQ = 2201
        private const val REQ_SNOOZE = 2202
        const val SNOOZE_MS = 30 * 60_000L

        /** 排一条待办的提醒（inexact 唤醒，允许系统合并；不给它升级成精确闹钟） */
        fun schedule(ctx: Context, id: Long) {
            val t = Todo.Store(ctx).get(id) ?: return
            val delay = (t.dueAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
            set(ctx, (REQ + id.toInt().coerceAtMost(90_000)).toInt(), id, ACTION_FIRE, delay)
        }

        fun snooze(ctx: Context, id: Long) = set(ctx, REQ_SNOOZE, id, ACTION_SNOOZE, SNOOZE_MS)

        private fun set(ctx: Context, req: Int, id: Long, action: String, delayMs: Long) {
            // v1.85：走 RemindExact —— 能精确就精确（原先这里是普通 set，Doze 一合并就晚几十分钟甚至不响，
            // 这是用户报「提醒没生效」的直接原因之一）；没授权时它会退到近似档，不抛也不静默丢。
            try {
                RemindExact.set(ctx, System.currentTimeMillis() + delayMs,
                    PendingIntent.getBroadcast(ctx, req, Intent(ctx, TodoReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } catch (_: Exception) {}
        }

        /** 排下一条：最近的未完成待办 */
        fun reschedule(ctx: Context) {
            val now = System.currentTimeMillis()
            val next = Todo.Store(ctx).list().filter { !it.done && it.dueAt > now }.minByOrNull { it.dueAt } ?: return
            schedule(ctx, next.id)
        }

        /** 开机 / 升级后重排（AlarmManager 不持久）。简报也在这一趟排上 —— 两个都靠闹钟，不排就永远不响。 */
        fun kick(ctx: Context) {
            reschedule(ctx)
            BriefingReceiver.kick(ctx)
        }
    }
}

object TodoNotify {
    // ---------------- 通知通道（局部集中，不引抽象）----------------
    // Android 8 起通道建好就基本改不动 → 每个强度一条独立通道，id 按档位写死、同档永远选同一条。
    // 老 "todo" 通道震不震我们控制不了（当年没显式 enableVibration，系统默认不保证，用户也可能自己动过），
    // 所以关档 / 补通知不拿它当容器；判定「用户动没动过」只看重要性，不看振动（见 [Env] / [channelFor]）。
    const val NONE = ""                  // 哨兵：没有可发的通道（总开关关 / 老通道被禁）→ [notify] 直接返回
    const val CH_OFF = "todo_off"        // 关震动：仍是正常通知（有声），只是不震
    const val CH_OFF_LOW = "todo_off_low"// 关震动 + 低重要性：用户把老通道压到 <=LOW 时用，别拿 DEFAULT 顶上去
    const val CH_QUIET = "todo_quiet"    // 无声也无震：只在「卡已经震过、这是 6 秒后的补通知」时用
    const val CH_LIGHT = "todo_v1"
    const val CH_MID = "todo_v2"
    const val CH_STRONG = "todo_v3"

    // NotificationManager.IMPORTANCE_* 的数值（写死：纯选择逻辑侧不 import android，JVM 能直测）
    const val IMP_NONE = 0
    const val IMP_LOW = 2
    const val IMP_DEFAULT = 3

    private const val REQ_BROADCAST = 2301
    private const val REQ_CAL = 2302

    /**
     * 现采的系统通知状态；[channelFor] 靠它判断「能不能用新通道」，纯数据、JVM 能测。
     *
     * @param legacyImportance          老 "todo" 通道的当前重要级（[IMP_*]）。通道不存在时取 [IMP_DEFAULT]
     *                                  —— 没有通道就没有「用户设置」要尊重，等同于「没动过」。
     * @param legacyImportanceSetByUser API 29+ 的 [android.app.NotificationChannel.hasUserSetImportance]：
     *                                  用户是否亲手动过老通道重要性（调高 / 调低都算）。低版本拿不到，一律 false。
     *
     * 有意**不带「老通道震不震」**：没有可靠 API 能区分「用户关的振动」和「App 当初就没启用」，拿「不震」
     * 当「用户动过」的证据会把老用户全误判回老通道 —— 判定用户动没动只看重要性。
     */
    data class Env(
        val notificationsEnabled: Boolean,
        val legacyImportance: Int = TodoNotify.IMP_DEFAULT,
        val legacyImportanceSetByUser: Boolean = false,
    )

    /** 强度档 → 那条强度自己的通道（[Vibrate.OFF] → 关震动通道） */
    fun loudChannel(level: Int): String = when (Vibrate.norm(level)) {
        Vibrate.LIGHT -> CH_LIGHT
        Vibrate.MID -> CH_MID
        Vibrate.STRONG -> CH_STRONG
        else -> CH_OFF
    }

    /** 这条待办解析后的震动档：[Todo.Item.vibe] < 0 = 跟全局设置，否则用这条自己的（越界归默认档） */
    fun levelOf(vibe: Int, global: Int): Int = if (vibe < 0) Vibrate.norm(global) else Vibrate.norm(vibe)

    /**
     * 这条提醒该走哪条通道（纯函数，JVM 直测）。Android 那层只管 [ensure] + notify。
     *
     * @param level  解析后的震动档（[Vibrate] 档位；0 = 关）
     * @param silent true = 卡已经震过，这只是 6 秒后的补通知 → 一律走无声通道，不许再震
     * @param env    现采的系统通知状态
     *
     * 规则（顺序即优先级）：
     *  1. 通知总开关被关 / 老通道被用户禁掉（[IMP_NONE]）→ [NONE]，调用方直接不发（连 silent 都不许绕）。
     *  2. 补通知（silent）→ [CH_QUIET]（建的时候就没声没震，重要性已是最低档）。
     *  3. 关档 → [CH_OFF]；用户压过重要性（<= [IMP_LOW] 或 API29+ 设过）时退到同为不震、但低重要性的 [CH_OFF_LOW]。
     *     **绝不 fallback 老通道** —— 老通道震不震我们控制不了。
     *  4. 其余：用户动过重要性 → 回老 [Todo.CHANNEL]，不拿新分档通道绕过；否则 → 新的分档通道。
     *
     * 「不拿新通道绕过用户的可观察设置」只针对**重要性**（能可靠观察的只有它）；振动不参与判断，见 [Env]。
     */
    fun channelFor(level: Int, silent: Boolean, env: Env): String {
        if (!env.notificationsEnabled || env.legacyImportance == IMP_NONE) return NONE
        val capped = env.legacyImportance <= IMP_LOW || env.legacyImportanceSetByUser
        if (silent) return CH_QUIET
        if (Vibrate.norm(level) == Vibrate.OFF) return if (capped) CH_OFF_LOW else CH_OFF
        return if (capped) Todo.CHANNEL else loudChannel(level)
    }

    /** 通道在系统设置里显示的名字 */
    fun channelName(id: String): String = when (id) {
        CH_OFF -> "待办提醒（关震动）"
        CH_OFF_LOW -> "待办提醒（关震动·低）"
        CH_QUIET -> "待办提醒（静默补发）"
        CH_LIGHT -> "待办提醒（轻震）"
        CH_MID -> "待办提醒（中震）"
        CH_STRONG -> "待办提醒（重震）"
        else -> "待办"
    }

    private fun envOf(nm: android.app.NotificationManager): Env = try {
        val legacy = nm.getNotificationChannel(Todo.CHANNEL)
        Env(
            notificationsEnabled = nm.areNotificationsEnabled(),
            legacyImportance = legacy?.importance ?: IMP_DEFAULT,
            // hasUserSetImportance 只有 API 29+ 有；低版本读不到，保守当作「没动过」
            legacyImportanceSetByUser = legacy != null && android.os.Build.VERSION.SDK_INT >= 29 &&
                legacy.hasUserSetImportance(),
        )
    } catch (_: Exception) { Env(true) }

    /**
     * 只建**这次要用到的那一条**自有通道 —— 不一次建满 5 条（免得堆进系统设置添乱）。
     * **已存在就直接返回**：通道建好后重要性 / 振动由用户说了算，再 create 不该（也做不到）把用户关掉的通道重置。
     * **绝不碰老 [Todo.CHANNEL]** —— 它的现状正是 [channelFor] 判断「用户动没动过」的依据。
     */
    private fun ensure(nm: android.app.NotificationManager, id: String) {
        if (id == Todo.CHANNEL || id == NONE) return
        if (nm.getNotificationChannel(id) != null) return          // 已建过（含用户自己改过）→ 不重置
        val importance = if (id == CH_QUIET || id == CH_OFF_LOW) IMP_LOW else IMP_DEFAULT
        val c = android.app.NotificationChannel(id, channelName(id), importance)
        when (id) {
            CH_QUIET -> {
                // 静默补发：连声音都别有 —— 卡已经震过，这里再响就是第二次打扰
                c.enableVibration(false)
                c.setSound(null, null)
            }
            CH_OFF, CH_OFF_LOW -> c.enableVibration(false)   // 不震；CH_OFF_LOW 另加低重要性（不响），尊重用户的压制
            else -> {
                val l = when (id) { CH_LIGHT -> Vibrate.LIGHT; CH_MID -> Vibrate.MID; else -> Vibrate.STRONG }
                c.enableVibration(true)
                c.setVibrationPattern(Vibrate.pattern(l))   // 通道的振动节拍 = [Vibrate] 那一档，和试弹卡同一手感
            }
        }
        nm.createNotificationChannel(c)
    }

    /**
     * 待办到点了（v1.80）：先过一遍**弹出纪律**（每日上限 / 免打扰 / 该类别被静音 / 连续忽略降频），
     * 放行才试弹 —— 球在跑且有悬浮窗权限时浮一张小卡 + 轻震两下，6 秒没人接住由服务退成常规通知。
     *
     * ⚠️ 纪律用的是 [PopupCenter.allow]（decide 那层，只判定 + 记额度），**不是 [PopupCenter.offer]** ——
     * offer 会自己再发一条「主动提醒」通知，跟弹卡叠在一起就变成弹一次卡 + 又多一条通知。
     * 没被放行、或球没在跑 / 没权限弹不出来，一律走 [notify] 兜底 —— 提醒不能因为纪律被挡就消失。
     *
     * 免打扰（[Prefs.mutedNow]）在**最前面**就拦住：全部静音 = 不弹、不补、不震（待办仍在列表里，解除后照常）。
     */
    fun fire(app: Context, t: Todo.Item) {
        if (Prefs(app).mutedNow()) {
            android.util.Log.i("TodoNotify", "fire id=${t.id} muted → 全部静音（不弹不补，待办仍在）")
            return
        }
        // key 带 todo 前缀 + id：和「主动提醒」的其它事件不会撞（撞了稍后 / 去重会串）
        val ev = PopupPolicy.Event("todo-due-${t.id}", PopupPolicy.CAT_LIFE, Memo.clip(t.text, 24))
        val allowed = PopupCenter.allow(app, ev)
        android.util.Log.i("TodoNotify", "fire id=${t.id} allowed=$allowed text=${t.text}")
        if (allowed) {
            val popped = try { OrbService.tryPopTodo(app, t) } catch (e: Exception) {
                android.util.Log.e("TodoNotify", "tryPopTodo threw", e); false
            }
            android.util.Log.i("TodoNotify", "popped=$popped")
            if (popped) return
        }
        android.util.Log.i("TodoNotify", "fallback to notification")
        notify(app, t)
    }

    /**
     * 常规通知：试弹没人理时的退路，也是球没在跑时的唯一路径。震动靠选中的**通道**（后台不允许自己起
     * Vibrator，通道让系统在弹通知时顺带震）—— 所以这条通知的通道必须按这条待办的强度选，见 [channelFor]。
     *
     * @param silent true = 卡刚刚已经震过了（6 秒没人接住退下来的补通知）→ 走静默通道，绝不再震。
     *               免打扰期间两者都不补（[Prefs.mutedNow]；待办仍在列表里，解除后照常）。
     */
    fun notify(app: Context, t: Todo.Item, silent: Boolean = false) {
        try {
            if (Prefs(app).mutedNow()) {
                android.util.Log.i("TodoNotify", "notify id=${t.id} muted → 全部静音，不补")
                return
            }
            val nm = app.getSystemService(android.app.NotificationManager::class.java) ?: return
            val level = levelOf(t.vibe, Prefs(app).vibrateLevel)
            val env = envOf(nm)
            val ch = channelFor(level, silent, env)
            if (ch == NONE) {
                // 总开关被关 / 老通道被禁：没有可发的通道，直接不发（别往不存在的老通道发，也别去建空通道）
                android.util.Log.i("TodoNotify", "notify id=${t.id} 无可发通道 enabled=${env.notificationsEnabled} legacy=${env.legacyImportance} → 不发")
                return
            }
            ensure(nm, ch)   // 老通道不归我们建，也不该动（ensure 内部会跳过）
            val icon = android.graphics.drawable.Icon.createWithResource(app, R.drawable.ic_orb)
            // 动作 PendingIntent 的身份必须用 (todo id, 动作) 唯一：旧代码固定 requestCode，而
            // Intent.filterEquals **不看 extras**，FLAG_UPDATE_CURRENT 就把后一条待办的动作覆盖到
            // 前一条通知上（多条通知的「稍后 / 已完成」互相串项）。给 Intent 加一条唯一 data URI，
            // 身份就分开了（requestCode 固定也不会复用）。
            val later = broadcast(app, t.id, TodoReceiver.ACTION_SNOOZE)
            // ⚠️ 铁律：日历必须经过 TodoActivity 的确认卡，不能从通知直接写
            val cal = PendingIntent.getActivity(app, REQ_CAL,
                Intent(app, TodoActivity::class.java).setData(actionUri(t.id, "calendar")).putExtra(TodoReceiver.EXTRA_ID, t.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val done = broadcast(app, t.id, TodoReceiver.ACTION_DONE)
            nm.notify(Todo.NOTI_ID, android.app.Notification.Builder(app, ch)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle("待办 · ${t.text}")
                .setContentText(Todo.notiBody(t, System.currentTimeMillis()))
                .setContentIntent(cal)
                .setAutoCancel(true)
                .addAction(android.app.Notification.Action.Builder(icon, "稍后 30 分钟", later).build())
                .addAction(android.app.Notification.Action.Builder(icon, "写进日历", cal).build())
                .addAction(android.app.Notification.Action.Builder(icon, "已完成", done).build())
                .build())
            android.util.Log.i("TodoNotify", "notification posted ch=$ch silent=$silent level=$level")
        } catch (e: Exception) {
            android.util.Log.e("TodoNotify", "notify threw", e)
        }
    }

    private fun actionUri(id: Long, action: String): android.net.Uri =
        android.net.Uri.parse("lingzhu-todo://$action/$id")

    private fun broadcast(app: Context, id: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(app, REQ_BROADCAST,
            Intent(app, TodoReceiver::class.java).setAction(action).setData(actionUri(id, action)).putExtra(TodoReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}