package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.71 薄弱点画像：把错题台账聚合成「哪块知识在漏、漏得多快」。
 *
 * 全部是对 WrongLog.Q 列表的纯函数——同一个 now 进去永远同一份 Report，
 * 单测不需要时钟也不需要文件，CLI / 预览脚本也能直接调。
 *
 * 两处刻意的取舍：
 * 1. **近因加权用半衰期**，不用滑动窗口。滑动窗口（只看最近 7 天）在用户一周没学习时
 *    会突然全空，界面上显示「薄弱点：无」是最误导人的一种；半衰期让旧记录衰减但不清零。
 * 2. **知识点为空的记录归到「未分类」，不丢掉**。识别不出考点时这条记录仍然是「用户错了」，
 *    丢掉会让整体正确率虚高，而这正是要拿给用户看的数字。
 */
object WeakPoints {
    /** 没识别出知识点的记录归到这里。 */
    const val UNKNOWN = "未分类"

    /** 近因半衰期（天）：14 天前的错误权重减半。选 14 是因为多数考试复习周期是两周一轮。 */
    const val HALF_LIFE_DAYS = 14.0

    /** 薄弱榜长度。5 个一屏能看完，再多用户就不看了。 */
    const val TOP = 5

    val WINDOWS = intArrayOf(7, 30)

    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 一个知识点的画像。
     * [recentWrongAt] 为 0 表示从没错过（只对过），它不是薄弱点。
     * [weight] 是近因加权错误分，只用于排序、不展示——这个单位对用户没有意义。
     */
    data class KP(
        val name: String,
        val total: Int,
        val wrong: Int,
        val recentWrongAt: Long,
        val weight: Double = 0.0,
    ) {
        val correct: Int get() = total - wrong
        /** 正确率；没记录过返回 -1，免得调用方把「0 次」当成「0% 正确」。 */
        val accuracy: Double get() = if (total <= 0) -1.0 else correct.toDouble() / total
    }

    /** 一天的正确率。[total] = 0 表示那天没答题。 */
    data class Day(val day: Long, val total: Int, val correct: Int) {
        val accuracy: Double get() = if (total <= 0) -1.0 else correct.toDouble() / total
    }

    /** 趋势：两个窗口各一条按天升序的曲线 + 窗口整体正确率（-1 = 窗口内没数据）。 */
    data class Trend(val week: List<Day>, val month: List<Day>, val weekAcc: Double, val monthAcc: Double)

    /**
     * 诊断结果。[byKnowledge] 按名字升序（稳定输出，单测好断言）。
     * [topWeak] 已按薄弱程度降序排好，只含「至少错过一次」的点——没错过的不该上榜。
     */
    data class Report(
        val total: Int,
        val wrong: Int,
        val byKnowledge: List<KP>,
        val topWeak: List<KP>,
        val trend: Trend,
        /** 生成时间。text() 里要用它算「最近一次错在几天前」，所以必须存。 */
        val at: Long,
    ) {
        val accuracy: Double get() = if (total <= 0) -1.0 else (total - wrong).toDouble() / total
    }

    /** 某天 0 点（毫秒）。用 UTC 日历日，和 Fsrs.daysBetween 同一套口径，跨时区不会多出半天偏移。 */
    fun dayStart(ms: Long): Long {
        val d = ms / 1000
        val day = if (d >= 0) d / 86400 else (d - 86399) / 86400
        return day * 86400L * 1000L
    }

    /**
     * 一条错题在 [daysAgo] 天前发生时的权重：0.5^(天数 / 半衰期)。
     * 只拿错题参与加权，同日错三次权重相加——「同一知识点反复错」正是要抓的信号。
     */
    fun recency(daysAgo: Double): Double = Math.pow(0.5, daysAgo / HALF_LIFE_DAYS)

    /** 按知识点聚合 + 近因加权，权重降序（同权重按名字，结果稳定）。 */
    fun aggregate(log: List<WrongLog.Q>, now: Long): List<KP> {
        val acc = LinkedHashMap<String, MutableList<WrongLog.Q>>()
        for (q in log) {
            val k = q.knowledge.trim().ifEmpty { UNKNOWN }
            acc.getOrPut(k) { ArrayList() }.add(q)
        }
        val list = acc.map { e ->
            var wrong = 0
            var recent = 0L
            var w = 0.0
            for (q in e.value) {
                if (q.correct) continue
                wrong++
                if (q.at > recent) recent = q.at
                // 时间在未来（时钟回拨 / 测试写死的时间）按 0 天算，不给额外加分
                val days = (now - q.at).coerceAtLeast(0L).toDouble() / DAY_MS
                w += recency(days)
            }
            KP(e.key, e.value.size, wrong, recent, w)
        }
        return list.sortedWith(compareByDescending<KP> { it.weight }.thenBy { it.name })
    }

    /** 薄弱榜：至少错过一次，按近因加权错误分排序取前 [top] 个。 */
    fun topWeak(byKnowledge: List<KP>, top: Int = TOP): List<KP> =
        byKnowledge.filter { it.wrong > 0 }
            .sortedWith(compareByDescending<KP> { it.weight }.thenBy { it.name })
            .take(top)

    /**
     * 按天正确率曲线：覆盖 [days] 天、末日是 now 所在那天、升序。
     * 没答题的天如实返回 total = 0，补值交给界面/模型看的那一层，这里保持纯。
     */
    fun daily(log: List<WrongLog.Q>, days: Int, now: Long): List<Day> {
        if (days <= 0) return emptyList()
        val end = dayStart(now)
        val start = end - (days - 1) * DAY_MS
        val total = IntArray(days)
        val correct = IntArray(days)
        for (q in log) {
            val d = dayStart(q.at)
            if (d < start || d > end) continue
            val i = ((d - start) / DAY_MS).toInt()
            total[i]++
            if (q.correct) correct[i]++
        }
        return (0 until days).map { Day(start + it * DAY_MS, total[it], correct[it]) }
    }

    /**
     * 把没答题的天渲染成 [Day(total = 0, correct = 0)]，并返回相邻的已知正确率用于画线。
     * 不直接把空白天改成有数据的 Day：那会让「这天到底有没有学」这个信息永久丢失，
     * 而学习连续性恰恰是最有用的信号之一。
     */
    fun knownAcc(days: List<Day>): List<Double?> {
        var last: Double? = null
        return days.map { d ->
            if (d.total > 0) { last = d.accuracy; d.accuracy } else last
        }
    }

    /** 窗口整体正确率：按真实条数算，不按天平均——每天题量不同，按天平均会让「1 题的日子」权重过大。 */
    fun windowAcc(days: List<Day>): Double {
        val t = days.sumOf { it.total }
        if (t <= 0) return -1.0
        return days.sumOf { it.correct }.toDouble() / t
    }

    /** 7 天 / 30 天两条曲线 + 两个窗口正确率。 */
    fun curves(log: List<WrongLog.Q>, now: Long): Trend {
        val week = daily(log, WINDOWS[0], now)
        val month = daily(log, WINDOWS[1], now)
        return Trend(week, month, windowAcc(week), windowAcc(month))
    }

    /** 完整画像。空台账返回全是 0 / 空列表的报告，不抛异常。 */
    fun report(log: List<WrongLog.Q>, now: Long): Report {
        val by = aggregate(log, now)
        return Report(
            total = log.size,
            wrong = log.count { !it.correct },
            byKnowledge = by.sortedBy { it.name },
            topWeak = topWeak(by),
            trend = curves(log, now),
            at = now,
        )
    }

    /** [report] 的别名：任务描述里 [report] / [build] 两个名字都出现过，留着免得调用方写哪个都能编过。 */
    fun build(log: List<WrongLog.Q>, now: Long): Report = report(log, now)

    // ---------------- 中文摘要 ----------------

    private fun pct(v: Double): String = if (v < 0) "—" else Math.round(v * 100).toString() + "%"

    private fun ago(now: Long, at: Long): String {
        if (at <= 0L) return "—"
        val d = (now - at).coerceAtLeast(0L) / DAY_MS
        return when {
            d <= 0L -> "今天"
            d == 1L -> "昨天"
            else -> d.toString() + " 天前"
        }
    }

    /**
     * 给模型看的中文摘要（AgentTools 的 weak_points 工具直接返回这段）。
     *
     * 信息密度优先：先总量 + 趋势（判断「在变好还是变坏」），再薄弱榜（判断「该补哪块」），
     * 最后其余知识点（判断「还有没有暗坑」）。不写客套话——这段整个会成为模型上下文，
     * 每多一句废话就少一点注意力留给数据。
     */
    fun text(r: Report): String {
        if (r.total <= 0) return "还没有答题记录。多做几道带「知识点」标记的题，我就能看出你的薄弱点。"
        val sb = StringBuilder()
        sb.append("错题台账：共 ").append(r.total).append(" 条，错 ").append(r.wrong)
            .append(" 条，总正确率 ").append(pct(r.accuracy)).append("。\n")

        val w = r.trend.weekAcc
        val m = r.trend.monthAcc
        when {
            w >= 0 && m >= 0 -> sb.append("近期正确率：近 7 天 ").append(pct(w)).append("，近 30 天 ").append(pct(m)).append("。")
            w >= 0 -> sb.append("近期正确率：近 7 天 ").append(pct(w)).append("（30 天数据不足）。")
            m >= 0 -> sb.append("近期正确率：近 7 天没有答题，近 30 天 ").append(pct(m)).append("。")
            else -> sb.append("近期正确率：最近 30 天没有答题记录。")
        }
        if (w >= 0 && m >= 0) {
            val diff = (w - m) * 100
            when {
                diff >= 3 -> sb.append("比 30 天均值高 ").append(Math.round(diff)).append(" 个百分点，在好转。")
                diff <= -3 -> sb.append("比 30 天均值低 ").append(Math.round(-diff)).append(" 个百分点，在退步。")
                else -> sb.append("与 30 天均值基本持平。")
            }
        }
        sb.append('\n')

        if (r.topWeak.isEmpty()) {
            sb.append("薄弱点：没有错题，暂时看不出短板。\n")
        } else {
            sb.append("薄弱点（按近期错误加权排序）：\n")
            r.topWeak.forEachIndexed { i, k ->
                sb.append(i + 1).append(". ").append(k.name)
                    .append("：错 ").append(k.wrong).append('/').append(k.total)
                    .append("，正确率 ").append(pct(k.accuracy))
                    .append("，最近一次错在 ").append(ago(r.at, k.recentWrongAt))
                    .append("。\n")
            }
        }

        val topNames = r.topWeak.map { it.name }.toSet()
        val rest = r.byKnowledge.filter { it.name !in topNames }
        if (rest.isNotEmpty()) {
            sb.append("其它知识点：").append(rest.take(8).joinToString("；") { k ->
                k.name + " 正确率 " + pct(k.accuracy) + "（" + k.total + " 次）"
            })
            if (rest.size > 8) sb.append("；等共 ").append(r.byKnowledge.size).append(" 个")
            sb.append("。\n")
        }
        return sb.toString().trimEnd()
    }

    // ---------------- 落盘缓存 ----------------

    /**
     * Report 的缓存序列化。算一次是 O(记录数)，每次进学习页都重算没必要；
     * 但缓存只是加速——台账一变就必须重算，所以这里只存结果，不存推导过程。
     */
    fun toJson(r: Report): String = JSONObject().apply {
        put("total", r.total)
        put("wrong", r.wrong)
        put("at", r.at)
        put("byKnowledge", JSONArray().apply {
            r.byKnowledge.forEach { k ->
                put(JSONObject().apply {
                    put("n", k.name); put("t", k.total); put("w", k.wrong)
                    put("at", k.recentWrongAt); put("s", k.weight)
                })
            }
        })
        put("topWeak", JSONArray().apply { r.topWeak.forEach { put(it.name) } })
        put("week", daysJson(r.trend.week))
        put("month", daysJson(r.trend.month))
    }.toString()

    private fun daysJson(days: List<Day>): JSONArray = JSONArray().apply {
        days.forEach { d -> put(JSONArray().apply { put(d.day); put(d.total); put(d.correct) }) }
    }

    /** 容错解析：坏文件返回 null（调用方据此重算），而不是返回一个「看起来是空」的报告。 */
    fun fromJson(s: String?): Report? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            val by = ArrayList<KP>()
            val a = o.optJSONArray("byKnowledge") ?: JSONArray()
            for (i in 0 until a.length()) {
                val k = a.optJSONObject(i) ?: continue
                val name = k.optString("n")
                if (name.isEmpty()) continue
                by += KP(name, k.optInt("t", 0), k.optInt("w", 0), k.optLong("at", 0L), k.optDouble("s", 0.0))
            }
            val names = o.optJSONArray("topWeak")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
            val week = daysFrom(o.optJSONArray("week"))
            val month = daysFrom(o.optJSONArray("month"))
            Report(
                total = o.optInt("total", 0),
                wrong = o.optInt("wrong", 0),
                byKnowledge = by,
                topWeak = names.mapNotNull { n -> by.firstOrNull { it.name == n } },
                trend = Trend(week, month, windowAcc(week), windowAcc(month)),
                at = o.optLong("at", 0L),
            )
        } catch (_: Exception) { null }
    }

    private fun daysFrom(a: JSONArray?): List<Day> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONArray(i) ?: return@mapNotNull null
            Day(t.optLong(0, 0L), t.optInt(1, 0), t.optInt(2, 0))
        }
    }
}
