package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * v1.86 记期：**能重复的提醒** —— 「每周一/三/五 20:00」「每天 15:00」，每条还能带
 * **提醒次数**（到点后再隔几分钟补几次）和**提醒强度**（轻/中/重 / 跟全局）。
 *
 * 为什么不做第二套闹钟：规则自己**不排闹钟**，而是把「未来 [HORIZON_DAYS] 天内的每一次」
 * **物化成普通 [Todo.Item]**（带 `ruleId`）—— 到点走的就是已经修好的那一条待办链
 * （试弹 → 通知 → 开机重挂）。规则改了 / 删了 / 过期了，[sync] 一趟把没做完的生成项按
 * 「期望集合」重排。这样全 App 只有一条闹钟管线，不会两套各响各的。
 *
 * 纯逻辑（[maskOf] / [daysOf] / [weekdayLabel] / [firesOn] / [plan] / [parse] / [plusDays]）
 * 不碰 android，JVM 直测；读盘 / 写盘在 [Store]，物化在 [sync]。
 */
object Schedule {
    const val FILE = "schedule.json"
    const val MAX = 60
    /** 物化到未来多少天：够看见「下周的」，再多会把待办列表刷屏 */
    const val HORIZON_DAYS = 14
    /** 一条规则最多物化多少个点（次数多的规则别把列表占满） */
    const val MAX_FIRES_PER_RULE = 24
    const val TIMES_MIN = 1
    const val TIMES_MAX = 5
    const val EVERY_MIN_MIN = 1
    const val EVERY_MIN_MAX = 120
    const val EVERY_MIN_DEF = 10
    /** 强度「跟全局设置」的取值（见 [Vibrate]） */
    const val VIBE_GLOBAL = -1

    /** 周一..周日（ISO 1..7）的中文单字；日历表头和「每周X」文案共用 */
    val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * 一条记期规则。
     * @param weekly  true = 每周循环（看 [mask]）；false = 只在那一天（[dateMs]，存的是那天 0 点）
     * @param mask    bit1..bit7 = 周一..周日（[maskOf]）
     * @param minute  一天内的第几分钟（0..1439）
     * @param times   提醒次数（[TIMES_MIN]..[TIMES_MAX]）：第一次到点后，每 [everyMin] 分钟再来一次
     * @param strength 提醒强度（[Vibrate] 档位；[VIBE_GLOBAL] = 跟全局设置）
     */
    data class Rule(
        val id: Long,
        val text: String,
        val weekly: Boolean,
        val mask: Int = 0,
        val dateMs: Long = 0L,
        val minute: Int = 0,
        val times: Int = 1,
        val everyMin: Int = EVERY_MIN_DEF,
        val strength: Int = VIBE_GLOBAL,
        val enabled: Boolean = true,
        val createdAt: Long = 0L,
    )

    /** 物化出来的一个点（还没变成待办） */
    data class Fire(val ruleId: Long, val text: String, val dueAt: Long, val strength: Int)

    // ---------------- 纯逻辑 ----------------

    /** Calendar 的 DAY_OF_WEEK（周日=1..周六=7）→ ISO（周一=1..周日=7） */
    fun isoDow(calDow: Int): Int = ((calDow + 5) % 7) + 1

    fun isoOf(at: Long): Int = isoDow(Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_WEEK))

    fun maskOf(days: Iterable<Int>): Int = days.filter { it in 1..7 }.fold(0) { a, d -> a or (1 shl d) }

    fun daysOf(mask: Int): List<Int> = (1..7).filter { mask and (1 shl it) != 0 }

    fun hasDay(mask: Int, iso: Int): Boolean = iso in 1..7 && (mask and (1 shl iso)) != 0

    /** 「每天」/「每周一、三、五」/「每周（没选日子）」 */
    fun weekdayLabel(mask: Int): String {
        val d = daysOf(mask)
        return when {
            d.isEmpty() -> "每周（还没选日子）"
            d.size == 7 -> "每天"
            else -> "每周" + d.joinToString("、") { WEEK[it - 1] }
        }
    }

    /** 「周一」/「周三」；[iso] 是 ISO 1..7 */
    fun dayLabel(iso: Int): String = "周" + (WEEK.getOrNull(iso - 1) ?: "?")

    /** [dayStart] 往后 [days] 天的 0 点（用 Calendar 加，跨夏令时也不会偏一小时） */
    fun plusDays(dayStart: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, days)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 这条规则在「这一天」吗 */
    fun onDay(r: Rule, dayStart: Long): Boolean =
        if (r.weekly) hasDay(r.mask, isoOf(dayStart)) else Todo.dayStart(r.dateMs) == dayStart

    /** 一条规则在「这一天」的全部提醒时刻（含次数）；不在这一天 → 空 */
    fun firesOn(r: Rule, dayStart: Long): List<Long> {
        if (!onDay(r, dayStart)) return emptyList()
        val step = r.everyMin.coerceIn(EVERY_MIN_MIN, EVERY_MIN_MAX) * 60_000L
        val n = r.times.coerceIn(TIMES_MIN, TIMES_MAX)
        val base = dayStart + r.minute.coerceIn(0, 1439) * 60_000L
        return (0 until n).map { base + it * step }
    }

    /** 未来 [HORIZON_DAYS] 天内所有规则的所有点（已排掉早于 [now] 的），按时间排好 */
    fun plan(rules: List<Rule>, now: Long): List<Fire> {
        val today = Todo.dayStart(now)
        val out = ArrayList<Fire>()
        for (r in rules) {
            if (!r.enabled || r.text.isBlank()) continue
            var n = 0
            var d = 0
            while (d < HORIZON_DAYS && n < MAX_FIRES_PER_RULE) {
                for (t in firesOn(r, plusDays(today, d))) {
                    if (t < now) continue
                    out.add(Fire(r.id, r.text, t, r.strength))
                    n++
                    if (n >= MAX_FIRES_PER_RULE) break
                }
                d++
            }
        }
        return out.sortedWith(compareBy({ it.dueAt }, { it.ruleId }))
    }

    /** 下一次（>= [now]）响的时刻；没有 → null */
    fun nextFire(r: Rule, now: Long): Long? = plan(listOf(r), now).firstOrNull()?.dueAt

    // ---- 自然语言 → 规则（「每周一三五晚上八点」） ----

    private val WEEK_RE = Regex("(每星期|每周)\\s*([一二三四五六日天]+)")
    private val EVERY_DAY_RE = Regex("每天|每日|天天")
    private val TIMES_RE = Regex("([0-9]{1,2}|[一二三四五])\\s*[次遍]")

    /** 「一」「日」「天」… → ISO 1..7；认不出 → null */
    fun cnWeekday(c: Char): Int? = when (c) {
        '一' -> 1; '二' -> 2; '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; '日', '天' -> 7
        else -> null
    }

    /**
     * 从一句话里认出一条**每周 / 每天**的规则；不是重复的（只是某一天 / 几小时后）→ null，
     * 交给 [Todo.parseWhen] 那条一次性路径。
     *
     * 认不出的部分留给调用方继续剥（[Remind.plan] 会再抹掉意图词），这里只剥结构词：
     * 「每周X」/「每天」/ 时间片段 / 「N 次」。
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Rule? {
        val t = text.replace(Regex("[\\s\\u3000]+"), "")
        if (t.isEmpty()) return null
        val wm = WEEK_RE.find(t)
        val everyDay = EVERY_DAY_RE.containsMatchIn(t)
        val days = when {
            wm != null -> wm.groupValues[2].mapNotNull { cnWeekday(it) }.distinct()
            everyDay -> (1..7).toList()
            else -> return null
        }
        if (days.isEmpty()) return null
        val minute = Todo.minuteOfDay(t) ?: return null
        val times = TIMES_RE.find(t)?.groupValues?.get(1)?.let { Todo.numOf(it) }?.coerceIn(TIMES_MIN, TIMES_MAX) ?: 1
        var body = t
        wm?.let { body = body.replace(it.value, " ") }
        body = body.replace(EVERY_DAY_RE, " ").replace(Todo.TIME_RE, " ").replace(TIMES_RE, " ")
        body = body.replace(Regex("\\s+"), " ").trim()
        if (body.isEmpty()) return null
        return Rule(0L, body, weekly = true, mask = maskOf(days), minute = minute, times = times,
            strength = VIBE_GLOBAL, createdAt = now)
    }

    // ---------------- JSON ----------------
    // 名字叫 decode 不叫 parse：同名重载会和「自然语言 → 规则」的 [parse] 抢调用点
    // （`parse("...")` 会优先命中参数更具体的那个，读起来也更不容易看错）

    fun decode(json: String?): List<Rule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text")
                if (text.isBlank()) null else Rule(
                    o.optLong("id"), text, o.optBoolean("weekly"), o.optInt("mask"), o.optLong("dateMs"),
                    o.optInt("minute"), o.optInt("times", 1), o.optInt("everyMin", EVERY_MIN_DEF),
                    if (o.has("strength")) o.optInt("strength", VIBE_GLOBAL) else VIBE_GLOBAL,
                    if (o.has("enabled")) o.optBoolean("enabled", true) else true,
                    o.optLong("createdAt"),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Rule>): String = JSONArray().apply {
        for (r in list) put(JSONObject().put("id", r.id).put("text", r.text).put("weekly", r.weekly).put("mask", r.mask)
            .put("dateMs", r.dateMs).put("minute", r.minute).put("times", r.times).put("everyMin", r.everyMin)
            .put("strength", r.strength).put("enabled", r.enabled).put("createdAt", r.createdAt))
    }.toString()

    /** 加一条；同一个「日子 + 时间 + 内容」不重复记（返回已有那条的 id） */
    fun add(list: List<Rule>, r: Rule, max: Int = MAX): Pair<List<Rule>, Long> {
        if (r.text.isBlank()) return list to -1L
        val same = list.firstOrNull { it.text == r.text && it.weekly == r.weekly && it.mask == r.mask && it.dateMs == r.dateMs && it.minute == r.minute }
        if (same != null) return list to same.id
        // id 一律由这里发：传 0（新建）或传一个已经占用的 id（复制）都往后顺延一个
        val id = if (r.id > 0L && list.none { it.id == r.id }) r.id else (list.maxOfOrNull { it.id } ?: 0L) + 1
        val out = list + r.copy(id = id)
        return (if (out.size > max) out.takeLast(max) else out) to id
    }

    /** 按「每周的在前、再按时间」排一下，列表页好读 */
    fun sorted(list: List<Rule>): List<Rule> = list.sortedWith(compareBy({ it.minute }, { daysOf(it.mask).firstOrNull() ?: 0 }, { it.id }))

    /** 一句人话：「每周一、三、五 20:00」「每天 15:00 · 提醒 2 次 · 重」 */
    fun describe(r: Rule): String {
        val hm = String.format("%02d:%02d", r.minute / 60, r.minute % 60)
        val when_ = if (r.weekly) "${weekdayLabel(r.mask)} $hm" else "${Month.formatDate(r.dateMs)} $hm"
        val sb = StringBuilder(when_)
        if (r.times > 1) sb.append(" · 提醒 ${r.times} 次（每 ${r.everyMin} 分钟）")
        sb.append(" · ").append(if (r.strength == VIBE_GLOBAL) "震动跟设置" else Vibrate.label(r.strength))
        if (!r.enabled) sb.append(" · 已停")
        return sb.toString()
    }

    /** 日历页要用的「几月几日」（纯文本，不碰 android） */
    object Month {
        private val c get() = Calendar.getInstance()
        fun daysInMonth(year: Int, month0: Int): Int =
            Calendar.getInstance().apply { set(year, month0, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)

        /** 这个月 1 号是 ISO 周几（1=周一..7=周日） */
        fun firstIso(year: Int, month0: Int): Int =
            isoOf(Calendar.getInstance().apply {
                set(year, month0, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis)

        /** 某年某月第 d 天的 0 点 */
        fun dayStart(year: Int, month0: Int, d: Int): Long =
            Calendar.getInstance().apply {
                set(year, month0, d); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        fun formatDate(ms: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            return "${c.get(Calendar.MONTH) + 1} 月 ${c.get(Calendar.DAY_OF_MONTH)} 日"
        }

        fun ym(at: Long): Pair<Int, Int> {
            val c = Calendar.getInstance().apply { timeInMillis = at }
            return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
        }
    }

    // ---------------- 读盘 / 写盘（android） ----------------

    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)
        fun list(): List<Rule> = FileMemo.get(file) { decode(it) }
        private fun save(l: List<Rule>) = FileMemo.put(file, l) { toJson(it) }
        @Synchronized fun setAll(l: List<Rule>) = save(l)
        /** 加一条并返回它的 id（-1 = 建不了） */
        @Synchronized fun add(r: Rule): Long {
            val (l, id) = Schedule.add(list(), r.copy(id = 0L))
            save(l)
            return id
        }
        @Synchronized fun remove(id: Long) = save(list().filterNot { it.id == id })
        @Synchronized fun update(id: Long, f: (Rule) -> Rule) = save(list().map { if (it.id == id) f(it) else it })
        @Synchronized fun clear() = save(emptyList())
        fun get(id: Long): Rule? = list().firstOrNull { it.id == id }
    }

    /**
     * 把规则物化成待办（见文件头）。**规则改动 / 开机 / 每次提醒响过 / 从记期页回来**都要跑一趟。
     *
     * 三件事：① 收掉过期没用的生成项；② 补上缺的点；③ 让待办闹钟对齐到最近的一条。
     * 规则生成**且已完成**的条目留着当痕迹（用户做过的事不抹）。
     *
     * 收掉的条件（[keep]）写得很保守 —— 只收三种：规则删了 / 规则停用了 / **还没到点**但已不在期望集合里。
     * 已经到点（含刚响过、还没被处理）的一律留着，否则「响过之后自己从列表里消失」就成了新 bug。
     */
    fun sync(ctx: Context) {
        val todo = Todo.Store(ctx)
        val now = System.currentTimeMillis()
        val rules = Store(ctx).list()
        val want = plan(rules, now)
        val byId = rules.associateBy { it.id }
        val wantKeys = HashSet<Pair<Long, Long>>(want.size * 2)
        for (f in want) wantKeys.add(f.ruleId to f.dueAt)
        val cur = todo.list()
        val keep = cur.filterNot { it.ruleId > 0 && !it.done && !alive(it, byId, wantKeys, now) }
        val have = HashSet<Pair<Long, Long>>()
        for (t in keep) if (t.ruleId > 0) have.add(t.ruleId to t.dueAt)
        val next = ArrayList<Todo.Item>(keep.size + want.size)
        next.addAll(keep)
        var nid = (keep.maxOfOrNull { it.id } ?: 0L) + 1
        for (f in want) {
            if ((f.ruleId to f.dueAt) in have) continue
            next.add(Todo.Item(nid++, f.text, f.dueAt, now, ruleId = f.ruleId, vibe = f.strength))
        }
        todo.setAll(next)
        TodoReceiver.reschedule(ctx)
    }

    /** 这条规则生成的待办还该留着吗（[sync] 的收口条件） */
    private fun alive(t: Todo.Item, byId: Map<Long, Rule>, wantKeys: Set<Pair<Long, Long>>, now: Long): Boolean {
        val r = byId[t.ruleId] ?: return false          // 规则删了 → 收回
        if (!r.enabled) return false                     // 停用了 → 收回
        if (t.dueAt <= now) return true                  // 已经到点 / 过点的：留着给用户处理
        return (t.ruleId to t.dueAt) in wantKeys         // 未来的：只有还在期望集合里才留
    }
}
