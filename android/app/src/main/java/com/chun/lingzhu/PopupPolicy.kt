package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 弹出纪律（M2）：决定「这件事现在弹不弹、怎么弹」。
 *
 * 为什么要这么严：能弹的地方越多，越容易变成噪音。这个类把纪律写成纯函数（不碰 Android API），
 * 单元测试直接测状态迁移，Android 那层只在 [PopupCenter] 里负责发通知 / 收动作。
 *
 * 六条纪律：
 *  1. 每日全局上限（默认 8，0~20 可调；0 = 全天不弹）；超了只记历史不弹。
 *  2. 按类别软上限（学习 4 / 生活 4，类别可扩展 —— 加一个 [CAT_*] 常量或在设置里单独配）。
 *  3. 「稍后」固定 30 分钟后重弹（可换 10 分钟 / 1 小时 / 晚上），同一事件最多重弹 2 次，之后只记历史。
 *  4. 「这类别再弹」= 类别静音 7 天（记到期时间戳），静音期间事件照常入库、不弹。
 *  5. 自适应：每类别一个档位（0 汇总 / 1 常规 / 2 详细）。点开处理过 → 升一档；连续忽略 3 次 → 降一档并改汇总式。
 *  6. 免打扰时段（默认 22:30~7:30）期间事件排队，时段结束合并成一条摘要通知。
 */
object PopupPolicy {
    // ---- 上限 ----
    const val DAILY_CAP_DEFAULT = 8
    const val DAILY_CAP_MIN = 0
    const val DAILY_CAP_MAX = 20
    const val CAT_CAP_DEFAULT = 4

    /** 类别 id。软上限默认都是 [CAT_CAP_DEFAULT]，也可以在设置里按 id 单独配（见 [Config.catCaps]）。 */
    const val CAT_STUDY = "study"
    const val CAT_LIFE = "life"
    val CATS = listOf(CAT_STUDY, CAT_LIFE)
    fun label(cat: String): String = when (cat) {
        CAT_STUDY -> "学习"
        CAT_LIFE -> "生活"
        else -> "其它"
    }

    // ---- 稍后 ----
    /** 同一事件最多重弹这么多次；用完还点「稍后」也只记历史 */
    const val SNOOZE_MAX = 2
    const val SNOOZE_DEFAULT_MIN = 30
    /** 备选项：10 分钟 / 30 分钟（默认）/ 1 小时 / 晚上（0 = 免打扰结束那一刻） */
    val SNOOZE_CHOICES = listOf(10, 30, 60, 0)
    fun snoozeLabel(min: Int): String = when (min) {
        0 -> "晚上"
        60 -> "1 小时后"
        else -> "$min 分钟后"
    }

    // ---- 类别静音 ----
    const val MUTE_DAYS = 7
    const val MUTE_MS = MUTE_DAYS * 24L * 3600 * 1000

    // ---- 免打扰 ----
    const val QUIET_START_DEFAULT = 22 * 60 + 30      // 22:30
    const val QUIET_END_DEFAULT = 7 * 60 + 30         // 7:30
    /** 合并摘要里最多列出几条标题 */
    const val SUMMARY_TITLES = 5
    /** 排队 / 待重弹最多留多少条（免打扰一整夜也不会无限涨） */
    const val MAX_PENDING = 50

    // ---- 自适应 ----
    const val LEVEL_MIN = 0        // 汇总式：只给一行「今天攒了 N 件事」
    const val LEVEL_NORMAL = 1
    const val LEVEL_MAX = 2        // 详细：正文也给
    const val IGNORE_RUN_LIMIT = 3  // 连续忽略这么多次 → 降档

    data class Config(
        /** 总开关：关掉后所有事件只入库、不弹 */
        val on: Boolean = true,
        val dailyCap: Int = DAILY_CAP_DEFAULT,
        /** 类别 → 软上限；没配的用 [CAT_CAP_DEFAULT] */
        val catCaps: Map<String, Int> = emptyMap(),
        val quietOn: Boolean = true,
        /** 一天里的第几分钟（0..1439），允许跨零点（22:30 → 7:30） */
        val quietStart: Int = QUIET_START_DEFAULT,
        val quietEnd: Int = QUIET_END_DEFAULT,
    ) {
        fun capFor(cat: String): Int = (catCaps[cat] ?: CAT_CAP_DEFAULT).coerceIn(0, DAILY_CAP_MAX)
        fun start(): Int = normMin(quietStart)
        fun end(): Int = normMin(quietEnd)
    }

    /** 一次待弹的事：[key] 用来认「同一件事」（稍后重弹、去重都靠它） */
    data class Event(val key: String, val cat: String, val title: String)

    enum class Verdict(val why: String) {
        /** 正常弹 */
        SHOW("弹"),
        /** 弹，但用汇总式（这个类别被连续忽略过） */
        SHOW_SUMMARY("汇总弹"),
        /** 还没到重弹时间 */
        SNOOZED("稍后中"),
        /** 这个类别静音中 */
        MUTED("类别静音中"),
        /** 今日全局弹满了 */
        CAPPED_DAILY("今日弹满"),
        /** 这个类别今天弹满了 */
        CAPPED_CAT("本类弹满"),
        /** 稍后用完了，只记历史 */
        SNOOZED_OUT("稍后用完"),
        /** 免打扰时段，排进队列等合并 */
        QUEUED("免打扰排队"),
        /** 总开关关着 */
        OFF("已关"),
    }

    /** 一天里的所有计数都在这儿；[day] 用来判断要不要清零 */
    data class State(
        val day: Int = 0,
        val shown: Int = 0,
        val byCat: Map<String, Int> = emptyMap(),
        /** 类别 → 静音到期时间戳；<= now 就是没静音 */
        val mutes: Map<String, Long> = emptyMap(),
        /** 事件 key → 已经「稍后」过几次 */
        val snoozeCount: Map<String, Int> = emptyMap(),
        /** 事件 key → 该在什么时候重弹 */
        val snoozeAt: Map<String, Long> = emptyMap(),
        /** 类别 → 档位（0/1/2），没记过的按 [LEVEL_NORMAL] */
        val level: Map<String, Int> = emptyMap(),
        /** 类别 → 连续忽略次数 */
        val ignoreRun: Map<String, Int> = emptyMap(),
        /** 处在汇总式的类别 */
        val summary: Set<String> = emptySet(),
        /** 免打扰期间排队的标题（合并成一条摘要） */
        val queued: List<String> = emptyList(),
        /** 等着再弹一次的事（稍后到点 / 免打扰结束）：带完整信息，重弹时不用重新查 */
        val pending: List<Event> = emptyList(),
    )

    // ---------------- 存取（Prefs 里的 JSON 字符串）----------------

    fun parse(json: String?): State {
        if (json.isNullOrBlank()) return State()
        return try {
            val o = JSONObject(json)
            State(
                o.optInt("day"),
                o.optInt("shown"),
                strInt(o.optJSONObject("byCat")),
                strLong(o.optJSONObject("mutes")),
                strInt(o.optJSONObject("snoozeCount")),
                strLong(o.optJSONObject("snoozeAt")),
                // 档位要留 0（0 = 汇总式，是个有意义的档），其余的 0 是「没记过」可以丢
                strIntKeepZero(o.optJSONObject("level")),
                strInt(o.optJSONObject("ignoreRun")),
                (o.optJSONArray("summary")?.let { a -> (0 until a.length()).map { a.optString(it) } })?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                (o.optJSONArray("queued")?.let { a -> (0 until a.length()).map { a.optString(it) } })?.filter { it.isNotBlank() } ?: emptyList(),
                events(o.optJSONArray("pending")),
            )
        } catch (_: Exception) { State() }
    }

    fun toJson(s: State): String = JSONObject().apply {
        put("day", s.day)
        put("shown", s.shown)
        put("byCat", intJson(s.byCat))
        put("mutes", longJson(s.mutes))
        put("snoozeCount", intJson(s.snoozeCount))
        put("snoozeAt", longJson(s.snoozeAt))
        put("level", intJson(s.level))
        put("ignoreRun", intJson(s.ignoreRun))
        put("summary", JSONArray().apply { s.summary.forEach { put(it) } })
        put("queued", JSONArray().apply { s.queued.forEach { put(it) } })
        put("pending", JSONArray().apply { s.pending.forEach { put(JSONObject().put("key", it.key).put("cat", it.cat).put("title", it.title)) } })
    }.toString()

    private fun events(a: JSONArray?) = buildList {
        if (a == null) return@buildList
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val k = o.optString("key")
            if (k.isNotBlank()) add(Event(k, o.optString("cat").ifBlank { CAT_LIFE }, o.optString("title")))
        }
    }

    private fun intJson(m: Map<String, Int>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }
    private fun longJson(m: Map<String, Long>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }
    private fun strInt(o: JSONObject?) = buildMap {
        o?.keys()?.forEach { k -> o.optInt(k).takeIf { it != 0 }?.let { put(k, it) } }
    }
    /** 和 [strInt] 一样，但 0 也留着（档位 0 = 汇总式，不能当成「没记过」） */
    private fun strIntKeepZero(o: JSONObject?) = buildMap {
        o?.keys()?.forEach { k -> put(k, o.optInt(k)) }
    }
    private fun strLong(o: JSONObject?) = buildMap {
        o?.keys()?.forEach { k -> o.optLong(k).takeIf { it != 0L }?.let { put(k, it) } }
    }

    // ---------------- 时间小工具（纯算，不碰系统时区 API 之外的）----------------

    fun normMin(v: Int): Int = ((v % 1440) + 1440) % 1440

    /** 本机当前是当天第几分钟（0..1439） */
    fun minuteOfDay(now: Long): Int {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = now
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    /** 免打扰判定，支持跨零点。start == end 视为不启用（否则全天都算免打扰，没法用）。 */
    fun inQuiet(minute: Int, start: Int, end: Int): Boolean {
        val m = normMin(minute); val s = normMin(start); val e = normMin(end)
        if (s == e) return false
        return if (s < e) m in s until e else m >= s || m < e
    }

    /** 1439 → "23:59"（设置页里显示时段用） */
    fun fmtMin(minute: Int): String {
        val m = normMin(minute)
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    /** 「22:30 ~ 7:30」这种写法（跨零点会在中间标个「跨夜」） */
    fun fmtRange(start: Int, end: Int): String {
        val s = normMin(start); val e = normMin(end)
        val cross = if (s == e) "" else if (s > e) "（跨夜）" else ""
        return "${fmtMin(s)} ~ ${fmtMin(e)}$cross"
    }

    /** 设置页那一行状态：今天弹了几次、还剩几次、哪个类别在静音 */
    fun statusLine(s: State, cfg: Config, now: Long): String {
        if (!cfg.on) return "已关：所有事只入库、不弹"
        val parts = mutableListOf("今天已弹 ${s.shown}/${cfg.dailyCap} 次")
        val mutes = activeMutes(s, now)
        if (mutes.isNotEmpty()) parts.add(mutes.keys.joinToString("、") { "$it 静音到 " + fmtDay(mutes.getValue(it)) })
        if (s.queued.isNotEmpty()) parts.add("免打扰排队 ${s.queued.size} 条")
        return parts.joinToString(" · ")
    }

    /** 到期时间戳 → "10-13"（设置页里看静音到哪天） */
    fun fmtDay(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return String.format("%02d-%02d", c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** 档位的中文说明（设置页显示） */
    fun levelLabel(v: Int): String = when (v.coerceIn(LEVEL_MIN, LEVEL_MAX)) {
        LEVEL_MIN -> "汇总（只给一行）"
        LEVEL_NORMAL -> "常规"
        else -> "详细"
    }

    /** 这次「稍后」隔多少分钟：>0 就是那个分钟数；0（晚上）= 到下一个免打扰结束的那一刻 */
    fun snoozeDelay(choiceMin: Int, nowMin: Int, quietEnd: Int): Int {
        if (choiceMin > 0) return choiceMin
        val e = normMin(quietEnd)
        val gap = e - normMin(nowMin)
        return if (gap > 0) gap else gap + 1440
    }

    /**
     * 「晚上」这个选项在当前时段下到底等多久（设置页预览用）。
     * 免打扰关着就没「晚上」这个概念 —— 退化成默认的 [SNOOZE_DEFAULT_MIN] 分钟。
     */
    fun snoozeDelayFor(choiceMin: Int, nowMin: Int, cfg: Config): Int = when {
        choiceMin > 0 -> choiceMin
        !cfg.quietOn -> SNOOZE_DEFAULT_MIN
        else -> snoozeDelay(0, nowMin, cfg.end())
    }

    // ---------------- 状态迁移 ----------------

    /** 换天了：今日计数清零，其余（静音 / 稍后 / 档位）留着 */
    fun rollDay(s: State, today: Int): State = if (s.day == today) s else s.copy(day = today, shown = 0, byCat = emptyMap())

    fun levelOf(s: State, cat: String): Int = (s.level[cat] ?: LEVEL_NORMAL).coerceIn(LEVEL_MIN, LEVEL_MAX)
    fun isSummary(s: State, cat: String): Boolean = cat in s.summary || levelOf(s, cat) <= LEVEL_MIN

    fun isMuted(s: State, cat: String, now: Long): Boolean = (s.mutes[cat] ?: 0L) > now

    fun capLeft(s: State, cfg: Config, cat: String): Int = cfg.capFor(cat) - (s.byCat[cat] ?: 0)
    fun dailyLeft(s: State, cfg: Config): Int = cfg.dailyCap - s.shown

    /** 「这类别再弹」：静音 7 天 */
    fun mute(s: State, cat: String, now: Long): State = s.copy(mutes = s.mutes + (cat to now + MUTE_MS))

    /** 清掉已经过期的静音（设置页显示用） */
    fun activeMutes(s: State, now: Long): Map<String, Long> = s.mutes.filterValues { it > now }

    /** 真弹了一次：全局 +1、类别 +1，并清掉这条的稍后排期 / 队列位置（已经弹过了） */
    fun onShown(s: State, ev: Event, today: Int): State {
        val st = rollDay(s, today)
        return st.copy(
            shown = st.shown + 1,
            byCat = st.byCat + (ev.cat to (st.byCat[ev.cat] ?: 0) + 1),
            snoozeAt = st.snoozeAt - ev.key,
            pending = st.pending.filterNot { it.key == ev.key },
            queued = st.queued.filterNot { it == ev.title },
        )
    }

    /** 点「稍后」：次数 +1、排到 delay 分钟后；用满 [SNOOZE_MAX] 次就不再排了（之后只记历史） */
    fun onSnooze(s: State, ev: Event, now: Long, delayMin: Int): State {
        val n = (s.snoozeCount[ev.key] ?: 0) + 1
        val room = n <= SNOOZE_MAX          // 第 1、2 次稍后都排闹钟；第 3 次只记次数，不再排
        return s.copy(
            snoozeCount = s.snoozeCount + (ev.key to n),
            snoozeAt = if (room) s.snoozeAt + (ev.key to now + delayMin * 60_000L) else s.snoozeAt - ev.key,
            pending = if (room) addPending(s.pending, ev) else s.pending.filterNot { it.key == ev.key },
            queued = if (room) s.queued else s.queued.filterNot { it == ev.title },
        )
    }

    /** 稍后之后真的处理了 / 直接点开处理了：升一档（封顶 [LEVEL_MAX]），升满就不再汇总；连续忽略清零 */
    fun onHandled(s: State, cat: String): State {
        val lv = (levelOf(s, cat) + 1).coerceAtMost(LEVEL_MAX)
        return s.copy(
            level = s.level + (cat to lv),
            ignoreRun = s.ignoreRun - cat,
            summary = if (lv > LEVEL_MIN) s.summary - cat else s.summary,
        )
    }

    /** 忽略了：连续 [IGNORE_RUN_LIMIT] 次没理 → 降一档并改汇总式 */
    fun onIgnored(s: State, cat: String): State {
        val run = (s.ignoreRun[cat] ?: 0) + 1
        if (run < IGNORE_RUN_LIMIT) return s.copy(ignoreRun = s.ignoreRun + (cat to run))
        val lv = (levelOf(s, cat) - 1).coerceAtLeast(LEVEL_MIN)
        return s.copy(level = s.level + (cat to lv), ignoreRun = s.ignoreRun - cat, summary = s.summary + cat)
    }

    /** 免打扰期间：排队（同一个 key 只排一次） */
    fun enqueue(s: State, ev: Event): State = s.copy(
        queued = if (s.queued.contains(ev.title)) s.queued else s.queued + ev.title,
        pending = addPending(s.pending, ev),
    )

    /** 排队里加一条（按 key 去重） */
    fun addPending(list: List<Event>, ev: Event): List<Event> = if (list.any { it.key == ev.key }) list else list + ev

    /**
     * 现在该重弹的有哪些（到点的稍后 + 已出免打扰的排队），并把它们从 pending / 队列里摘掉。
     * 开机 / 服务重启后靠这个把丢掉的状态捡回来（AlarmManager 里的闹钟不会跨重启保留）。
     */
    fun takeDue(s: State, now: Long, minute: Int, cfg: Config): Pair<List<Event>, State> {
        val quiet = cfg.quietOn && inQuiet(minute, cfg.start(), cfg.end())
        val due = s.pending.filter { ev ->
            val at = s.snoozeAt[ev.key] ?: 0L
            if (at > 0) at <= now else !quiet          // 有排期的看排期；排队等时段的看时段出没出
        }
        return due to s.copy(
            pending = s.pending.filterNot { p -> due.any { it.key == p.key } },
            queued = s.queued.filterNot { t -> due.any { it.title == t } },
            snoozeAt = s.snoozeAt - due.map { it.key }.toSet(),
        )
    }

    /** 还没到点、但得重新排一次闹钟的（重启后 pending 还在、排期还在未来的那些） */
    fun stillScheduled(s: State, now: Long): List<Event> =
        s.pending.filter { ev -> (s.snoozeAt[ev.key] ?: 0L) > now }

    /**
     * 清掉过期 / 无主的状态条目：已过期的静音、7 天前就弹完的稍后计数、已经不在 pending 里的排期。
     * 不清的话这个 JSON 会随着用得越久越臃肿（每次写入都要整份读写）。
     */
    fun prune(s: State, now: Long): State {
        val live = s.pending.map { it.key }.toSet()
        return s.copy(
            mutes = s.mutes.filterValues { it > now },
            snoozeAt = s.snoozeAt.filter { (k, v) -> v > now && k in live },
            snoozeCount = s.snoozeCount.filter { (k, v) -> v > SNOOZE_MAX || k in live },
            ignoreRun = s.ignoreRun.filterValues { it > 0 },
            pending = s.pending.takeLast(MAX_PENDING),
            queued = s.queued.takeLast(MAX_PENDING),
        )
    }

    /** 稍后到点：重弹一次（重新走一遍 decide，额度/静音仍然管用） */
    fun resnooze(s: State, ev: Event, now: Long, cfg: Config, minute: Int, today: Int): Verdict {
        val st = rollDay(s, today).copy(
            snoozeAt = rollDay(s, today).snoozeAt - ev.key,
            pending = rollDay(s, today).pending.filterNot { it.key == ev.key },
        )
        return decide(st, ev, now, cfg, minute, today)
    }

    // ---------------- 决策 ----------------

    /** 本机时间版（自己取当前分钟） */
    fun decide(s: State, ev: Event, now: Long, cfg: Config): Verdict = decide(s, ev, now, cfg, minuteOfDay(now), todayInt(now))

    /**
     * 核心：这条现在弹不弹、怎么弹。
     * @param minute 本机当天第几分钟（测试里直接传）
     * @param today  今天的日期整数（跨天清零用）
     */
    fun decide(s: State, ev: Event, now: Long, cfg: Config, minute: Int, today: Int): Verdict {
        if (!cfg.on) return Verdict.OFF
        val st = rollDay(s, today)
        if (isMuted(st, ev.cat, now)) return Verdict.MUTED
        if ((st.snoozeAt[ev.key] ?: 0L) > now) return Verdict.SNOOZED
        // 「重弹≤2 次」= 最多重弹 SNOOZE_MAX 次，所以第 3 次点稍后之后才算用完
        if ((st.snoozeCount[ev.key] ?: 0) > SNOOZE_MAX) return Verdict.SNOOZED_OUT
        if (cfg.dailyCap <= 0) return Verdict.CAPPED_DAILY
        if (st.shown >= cfg.dailyCap) return Verdict.CAPPED_DAILY
        if ((st.byCat[ev.cat] ?: 0) >= cfg.capFor(ev.cat)) return Verdict.CAPPED_CAT
        if (cfg.quietOn && inQuiet(minute, cfg.start(), cfg.end())) return Verdict.QUEUED
        return if (isSummary(st, ev.cat)) Verdict.SHOW_SUMMARY else Verdict.SHOW
    }

    /** 免打扰结束：该把排队的合并成一条摘要通知了吗（还在时段内 / 队列空 → null） */
    fun drainQueue(s: State, now: Long, cfg: Config, minute: Int): Pair<String, State>? {
        if (s.queued.isEmpty()) return null
        if (cfg.quietOn && inQuiet(minute, cfg.start(), cfg.end())) return null
        val titles = s.queued
        return summaryText(titles) to s.copy(
            queued = emptyList(),
            pending = s.pending.filterNot { p -> titles.contains(p.title) },
            snoozeAt = s.snoozeAt.filterKeys { k -> s.pending.any { it.key == k && !titles.contains(it.title) } },
        )
    }

    /**
     * 合并摘要的纯逻辑版（不看时间、不看配置）：给「现在该发什么」用，也是单测的主入口。
     * 队列空 → null（没什么可说）。
     */
    fun mergeText(titles: List<String>): String? = if (titles.isEmpty()) null else summaryText(titles)

    /** 合并摘要文案：「免打扰期间攒了 3 件事：交周报、买菜、给妈回电话」 */
    fun summaryText(titles: List<String>): String {
        if (titles.isEmpty()) return ""
        val head = if (titles.size == 1) "有 1 件事：" else "攒了 ${titles.size} 件事："
        val shown = titles.take(SUMMARY_TITLES).joinToString("、")
        val more = if (titles.size > SUMMARY_TITLES) " 等 ${titles.size} 件" else ""
        return head + shown + more
    }

    /** 汇总式弹窗的正文（比正常弹更短） */
    fun summaryBody(s: State, ev: Event, cfg: Config): String {
        val left = cfg.dailyCap - s.shown
        return "${label(ev.cat)}·${ev.title}" + if (left > 0) "\n今日还剩 $left 次弹出额度" else "\n今日额度已用完"
    }

    private fun todayInt(now: Long): Int =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now)).toInt()
}