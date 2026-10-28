package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v2.3.1 专注报告：把 [FocusGuard] 攒下来的「学习 / 不像学习」标签换算成
 * 「今天学了多久、离开了几次、最长一次专注多久」。
 *
 * ## 口径（这一节是这个文件存在的理由，改算法前先改这里）
 *
 * 1. **学习时长 = 相邻两次「在学习」采样之间的间隔之和**，单段封顶 [GAP_CAP_MS]（默认 3 分钟）。
 *    和 [ReviewLog.MAX_GAP_MS] 是同一个思路：不用前台计时器（切后台 / 息屏 / 被系统杀掉都不用处理），
 *    所以永远不会出现「睡前忘了关，第二天记成学了 8 小时」。超过封顶的间隔说明人已经走了，
 *    那段不算学习 —— 宁可少算，也不给一个好看但假的数字。
 *
 * 2. **离开 = 从第一个「不像学习」采样，到下一个「在学习」采样**。中间夹的「判不出来」
 *    采样本身不打断一段离开（人也确实还没回来），但「判不出来」会在时间线上留一个边界
 *    （见 6），所以真实跑起来时它后面的人是不是回来，报告不猜；但如果这段离开里
 *    **一个「像学习」的采样都没有**，它就不单独算一次离开 —— 那说明这段时间根本没在学
 *    （下课了、吃饭去了），把它算进「离开次数」会让简报变成「你今天摸鱼 40 次」，那是假的。
 *
 * 3. **最长连续专注 = 相邻采样间隔都在封顶以内、且中间没有边界的最长「在学习」连段**。断一次就重来。
 *
 * 4. **离开时段分布**按采样时刻落到 24 个小时格子（本地时区）。某个格子没数据就是 0，不是「没离开」。
 * 5. **只报有数据的段**：一天里一个采样都没有就说「没有记录」，不写 0 分钟、不画一条平线冒充趋势。
 *
 * 6. **时间线会在边界处断开**（[FocusSample.cut]）：锁屏、服务停启、关开关 / 改模式、判不出来、
 *    没有权限或截屏通道之后，前后两段采样**不算相连** —— 既不加学习间隔，也不连成一段连续专注，
 *    也不给「离开」收口。那段时间到底在做什么没人知道，报告宁可不记，也不替用户猜一个标签。
 *
 * ## 隐私
 * [FocusSample] 只有「时刻 + 这一段待了多久 + 三态标签 + 画面指纹」。**没有正文**，
 * 连字段都没有 —— 想存也存不进去。落盘只有这几个字段，[toJson] 里能看到的就这些。
 */
object FocusReport {

    /** 落盘文件名（filesDir 下） */
    const val FILE = "focuslog.json"

    /** 留最近多少天：报告最长看「近 7 天」，多留几天做边界缓冲 */
    const val KEEP_DAYS = 30

    /** 条数硬上限。自动扫描 12 秒一轮，一天最多 7200 轮；这里按「去重后」留 6000 —— 够一周的量级 */
    const val MAX = 6000

    /**
     * 一段学习间隔最多算多久（默认 3 分钟）。
     * 比 [ReviewLog.MAX_GAP_MS] 的 5 分钟短一点，因为这里是 12 秒一轮的密集采样：
     * 出现 3 分钟的空档，基本就是人走了或者屏幕黑了。
     */
    const val GAP_CAP_MS = 3 * 60_000L

    /**
     * 一条采样。
     *
     * @param at   采样时刻（毫秒，System.currentTimeMillis 口径）
     * @param ms   这一段算多久（= 这一轮到下一轮的间隔，已被 [GAP_CAP_MS] 截断）；0 表示未知
     * @param tag  FocusGuard.Verdict.code：study / play / unsure
     * @param fp   画面轻量指纹（可空）。只用于「同一屏只记一条」的去重，不含任何内容
     * @param cut  这一条**之后**的时间线是断的（锁屏 / 停用 / 改设置 / 看不到画面）。
     *             只落盘在确实发生边界的那一条上，老文件没有这个字段就是 false（= 连续）
     */
    data class FocusSample(
        val at: Long,
        val ms: Long,
        val tag: String,
        val fp: String? = null,
        val cut: Boolean = false,
    ) {
        val verdict: FocusGuard.Verdict get() = FocusGuard.Verdict.of(tag)
        val study: Boolean get() = verdict == FocusGuard.Verdict.STUDY
        val play: Boolean get() = verdict == FocusGuard.Verdict.NOT_STUDY

        fun toJson(): JSONObject = JSONObject()
            .put("at", at).put("ms", ms).put("tag", tag)
            .apply {
                if (!fp.isNullOrEmpty()) put("fp", fp)
                if (cut) put("cut", true)
            }

        companion object {
            fun from(o: JSONObject?): FocusSample? {
                if (o == null) return null
                val at = o.optLong("at", 0L)
                if (at <= 0L) return null   // 没有时刻的采样归不到任何一天，当坏行扔掉
                return FocusSample(
                    at,
                    o.optLong("ms", 0L).coerceIn(0L, GAP_CAP_MS),
                    o.optString("tag"),
                    o.optString("fp").ifEmpty { null },
                    o.optBoolean("cut", false),
                )
            }
        }
    }

    // ---------------- 纯函数：序列化 / 裁剪 / 日期 ----------------

    fun parse(json: String?): List<FocusSample> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> FocusSample.from(a.optJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<FocusSample>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    /**
     * 裁剪：先按时间窗，再按条数，都留最新的。
     * 时间窗挡不住「同一天被刷了几万条」，而这份文件每次落盘都要整份重写，所以两条都要有。
     */
    fun trim(list: List<FocusSample>, now: Long, keepDays: Int = KEEP_DAYS, max: Int = MAX): List<FocusSample> {
        val cutoff = now - keepDays * Fsrs.DAY_MS
        val inWindow = list.filter { it.at > cutoff }.sortedBy { it.at }
        return if (inWindow.size <= max) inWindow else inWindow.takeLast(max)
    }

    /** 毫秒 -> 本地日历日 yyyyMMdd。用设备时区：「我今天学了多久」的今天是手机上的今天 */
    fun dateOf(at: Long): String = ReviewLog.dateOf(at)

    /** 本地时钟的「当天第几分钟」（0..1439）。离开时段分布按它分格子 */
    fun minuteOfDay(at: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    /** 「08:30」这种时刻文案（离开最集中的时段要能说出是几点） */
    fun hhmm(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /**
     * 把一段采样序列的「每段算了多久」重算一遍。
     *
     * 为什么要有这个函数：ms 存下来之后如果按「前一条 -> 这一条」写，
     * 那么「后一条被去重跳过」时前一条的 ms 就停在旧值上了。所以持久化的 ms 口径定为
     * **这一条 -> 下一条**，而 [compute] 只认它；这里给单测和迁移用：
     * 给一串时刻，按同一条封顶规则重新分配 ms。
     */
    fun respace(list: List<FocusSample>, cap: Long = GAP_CAP_MS): List<FocusSample> {
        val sorted = list.sortedBy { it.at }
        return sorted.mapIndexed { i, s ->
            val next = sorted.getOrNull(i + 1)
            s.copy(ms = if (s.cut || next == null) 0L else (next.at - s.at).coerceIn(0L, cap))
        }
    }

    // ---------------- 核心：compute ----------------

    /** 一次离开：起止（毫秒）、持续多久、结束时人回来了没有（[returnedAt] 为 0 = 到窗口末尾都没回来） */
    data class Away(val from: Long, val to: Long, val ms: Long, val returnedAt: Long)

    /** 某一天（或某一周）的专注账。所有时长都是**毫秒**，显示交给 [FocusGuard.fmtDur] */
    data class Report(
        /** 口径：统计了哪几天（yyyyMMdd，升序，含今天）。周报就是 7 个 */
        val days: List<String>,
        /** 窗口里的采样条数。0 = 没记录，界面要说「还没有记录」而不是显示 0 */
        val samples: Int,
        /** 学习时长（按 [GAP_CAP_MS] 封顶累计） */
        val studyMs: Long,
        /** 「在学习」的连段数：中间断过一次就 +1。用来区分「一次坐了两小时」和「来回切了十次」 */
        val studySegments: Int,
        /** 最长一段连续专注 */
        val longestMs: Long,
        /** 离开次数（只数「离开之后又回来了」的，见口径 2） */
        val leaves: Int,
        /** 离开总时长（同样只算回来的那些） */
        val awayMs: Long,
        /** 24 格：每格落了几次离开（按离开的**起点**时刻归档） */
        val awayHour: List<Int>,
        /** 学习时长按 24 格分布（判断「是不是只在晚上学」） */
        val studyHour: List<Long>,
        /** 统计窗口里每天的学习时长（日报只有一天，周报 7 天） */
        val perDay: List<Pair<String, Long>>,
        /** 一句人话总结（不含任何术语） */
        val summary: String,
    ) {
        val hasData: Boolean get() = samples > 0

        /** 学习时长占「学习 + 离开」的比例。没有数据时是 null —— 不编一个 0% */
        val studyRatio: Double?
            get() {
                val total = studyMs + awayMs
                return if (total <= 0L) null else studyMs.toDouble() / total
            }
    }

    /**
     * 算一份报告。**纯函数**：不读盘、不看系统时钟，now 与采样都由调用方给。
     *
     * @param samples 窗口内的采样（不要求有序；内部排序并丢掉时刻 <= 0 的坏行）
     * @param now     现在（毫秒，墙上时钟）。目前只用于 [summary] 里「今天还没学过」这类判断
     * @param days    要统计哪几天（yyyyMMdd，升序）。日报传一天，周报传 7 天
     * @param cap     单段学习间隔的上限（调参用；默认 [GAP_CAP_MS]）
     */
    fun compute(samples: List<FocusSample>, now: Long, days: List<String>, cap: Long = GAP_CAP_MS): Report {
        val want = days.filter { it.length == 8 }.distinct().sorted()
        val set = want.toHashSet()
        val list = samples.filter { it.at > 0L && set.contains(dateOf(it.at)) }.sortedBy { it.at }
        val awayHour = IntArray(24)
        val studyHour = LongArray(24)
        if (list.isEmpty()) {
            return Report(want, 0, 0L, 0, 0L, 0, 0L, awayHour.toList(), studyHour.toList(),
                want.map { it to 0L }, noData())
        }

        // 1) 时长 / 连段：时长严格按「相邻两条的间隔」累计（最后一条没有下一条，不计）
        var studyMs = 0L
        var segments = 0
        var longest = 0L
        var cur = 0L
        var inRun = false
        for ((i, s) in list.withIndex()) {
            if (!s.study) { inRun = false; cur = 0L; continue }
            val step = stepAt(list, i, cap)
            // 跨日 / 边界那一跳 stepAt 已经返回 0（见它的注释）。但「段」也必须跟着断：
            // 否则 22:50 学一段、次日 07:30 学一段会被算成「一次连续专注」，
            // 报告就会说「最长连续专注 8 小时 40 分」—— 和 studyMs 的口径自相矛盾。
            // 判据与 stepAt 完全一致：跨了本地午夜，或前一条之后时间线断过，就断开。
            val prev = list.getOrNull(i - 1)
            val sameRun = inRun && prev != null && !prev.cut && dateOf(prev.at) == dateOf(s.at)
            studyHour[minuteOfDay(s.at) / 60] += step   // 按这一条所在的小时归档
            studyMs += step
            if (sameRun) cur += step else { inRun = true; cur = step; segments++ }
            if (cur > longest) longest = cur
        }

        // 2) 离开：一串连续的「不像学习」算一段，终点是它之后第一个「在学习」；没回来的不算一次离开。
        //    时间线在 cut 处断掉之后，后面那个人是不是回来没人知道 —— 这一段离开就不算数
        //    （宁可少算一次，也不把「锁屏 / 关掉守护那几小时」当成一次离开）。
        val aways = ArrayList<Away>()
        var i = 0
        while (i < list.size) {
            if (!list[i].play) { i++; continue }
            val from = list[i].at
            var j = i
            var back = 0L
            var end = from
            while (j < list.size) {
                val x = list[j]
                if (x.study) { back = x.at; end = x.at; break }
                end = x.at
                if (x.cut) break
                j++
            }
            aways.add(Away(from, end, (end - from).coerceAtLeast(0L), back))
            i = if (j < list.size && list[j].study) j else j + 1
        }
        var leaves = 0
        var awayMs = 0L
        for (a in aways) {
            if (a.returnedAt <= 0L || a.ms <= 0L) continue
            leaves++
            awayMs += a.ms
            awayHour[minuteOfDay(a.from) / 60] += 1
        }

        // 3) 每天各学了多久：跑同一个「相邻间隔」规则，只是把窗口切成单日
        val perDay = want.map { d -> d to studyMsOf(list.filter { dateOf(it.at) == d }, cap) }

        return Report(want, list.size, studyMs, segments, longest, leaves, awayMs,
            awayHour.toList(), studyHour.toList(), perDay,
            summary(list.size, studyMs, leaves, longest, perDay))
    }

    /**
     * 第 [i] 条到第 [i+1] 条的间隔（封顶）；最后一条没有下一条、或者这一条之后时间线断过，返回 0。
     *
     * ## 为什么要判「跨日」而不是只靠封顶
     * 用户 22:50 还在学，合上手机睡觉，次日 07:30 又打开继续学 —— 中间一夜没有任何采样
     * （息屏不采样，见 [FocusGuard.onScreenOff]）。
     * 只按封顶算，这段 8 小时 40 分的空档会变成一整段 3 分钟的「学习」，
     * 还要再叠上睡前 12 秒和醒后 12 秒 —— 也就是把**两天的学习算成同一次连续专注**。
     * 那会让「最长连续专注」和「学习时长」都多出一段并不存在的时间。
     *
     * 所以：间隔一旦跨过本地午夜，就**不计入** —— 那一夜不属于任何一天的学习。
     * 跨日但没隔多久的情况（23:59 学到 00:01）同样是跨日，也不计：
     * 一天的账只算这一天之内真实连续的部分，这是 [compute] 里「按天切窗口」的同一套口径。
     *
     * ## 为什么还要判 [FocusSample.cut]
     * 封顶只能挡住「间隔多大都不加时」，挡不住「锁屏 4 分钟再回来照样补 3 分钟」：
     * 那 3 分钟是在锁屏里过的，不是在学。所以锁屏 / 停用 / 改设置 / 判不出来 / 看不到画面
     * 都会在那一条上留下 cut，跨过去的一跳既不补时长，也不连段（见口径 6）。
     */
    private fun stepAt(list: List<FocusSample>, i: Int, cap: Long): Long {
        val cur = list[i]
        if (cur.cut) return 0L
        val next = list.getOrNull(i + 1) ?: return 0L
        // 跨日：这一条和下一条不在同一天 -> 那段间隔是「隔夜」，不是连续学习
        if (dateOf(cur.at) != dateOf(next.at)) return 0L
        return (next.at - cur.at).coerceIn(0L, cap)
    }

    /** 一段「在同一天里、且全都连续」的学习采样总共算了多久（口径和 [compute] 完全一致） */
    fun studyMsOf(daySamples: List<FocusSample>, cap: Long = GAP_CAP_MS): Long {
        val list = daySamples.filter { it.at > 0L }.sortedBy { it.at }
        var sum = 0L
        for ((i, s) in list.withIndex()) if (s.study) sum += stepAt(list, i, cap)
        return sum
    }

    // ---------------- 人话总结 ----------------

    /** 窗口里一条采样都没有：说得明白，不写 0 分钟 */
    fun noData(): String = "这段时间还没有记录。开着「专注守护」学一会儿，这里就会有数了。"

    /**
     * 一句人话总结。
     *
     * 刻意不写「专注率 87%」「效率优秀」这种：报告是给学习者自己看的，
     * 这些词听着像考核。这里只说三件事：学了多久、断了几次、最长一次撑了多久。
     */
    fun summary(samples: Int, studyMs: Long, leaves: Int, longestMs: Long, perDay: List<Pair<String, Long>>): String {
        if (samples <= 0) return noData()
        if (studyMs < FocusGuard.ONE_MIN) {
            return "这段时间还没攒够一分钟的学习记录。再多看几屏，这里才会有参考价值。"
        }
        val sb = StringBuilder()
        sb.append("一共学了 ").append(FocusGuard.fmtDur(studyMs))
        sb.append(if (longestMs >= FocusGuard.ONE_MIN) "，最长一次连着看了 " + FocusGuard.fmtDur(longestMs) else "")
        sb.append("。")
        sb.append(when {
            leaves == 0 -> "中间没离开过。"
            leaves <= 2 -> "中间离开过 " + leaves + " 次，都回来了。"
            else -> "中间离开过 " + leaves + " 次 —— 有点碎，但每次都回来了。"
        })
        // 多天窗口（周报）再补一句哪天最多，让「哪天状态好」一眼看得出来
        val best = perDay.maxByOrNull { it.second }
        if (perDay.size > 1 && best != null && best.second >= FocusGuard.FIVE_MIN) {
            sb.append("\n学得最多的是 ").append(dayLabel(best.first)).append("（").append(FocusGuard.fmtDur(best.second)).append("）。")
        }
        return sb.toString()
    }

    /** yyyyMMdd -> 「10 月 20 日」。周报里出现一串 20261020 没人看得懂 */
    fun dayLabel(d: String): String {
        if (d.length != 8) return d
        val m = d.substring(4, 6).toIntOrNull() ?: return d
        val day = d.substring(6, 8).toIntOrNull() ?: return d
        return m.toString() + " 月 " + day + " 日"
    }

    /** 一天里的学习 / 离开两行小结（日报页顶部那两句） */
    fun dayLine(r: Report): String {
        if (!r.hasData) return noData()
        return "学习 " + FocusGuard.fmtDur(r.studyMs) + " · 离开 " + r.leaves + " 次 · 最长专注 " + FocusGuard.fmtDur(r.longestMs)
    }

    /**
     * 离开最集中的那个小时（"20:00–21:00"）；一次都没离开过返回 null。
     *
     * 用小时而不是「时段名」（下午 / 傍晚）：一小时是个人能自己对上的粒度，
     * 「傍晚」这种词每个人理解都不一样。
     */
    fun busiestAwayHour(r: Report): String? {
        val max = r.awayHour.maxOrNull() ?: 0
        if (max <= 0) return null
        val h = r.awayHour.indexOfFirst { it == max }.coerceIn(0, 23)
        return hhmm(h * 60) + "–" + hhmm((h + 1) * 60)
    }

    /** 学习最集中的那个小时；没有学习采样返回 null */
    fun busiestStudyHour(r: Report): String? {
        val max = r.studyHour.maxOrNull() ?: 0L
        if (max <= 0L) return null
        val h = r.studyHour.indexOfFirst { it == max }.coerceIn(0, 23)
        return hhmm(h * 60) + "–" + hhmm((h + 1) * 60)
    }

    // ---------------- 落盘 ----------------

    /**
     * filesDir/focuslog.json 的读写，走 [FileMemo]（进程内一份副本 + 后台顺序写），
     * 和 [ReviewLog.Store] / [WrongLog.Store] 共用同一套约定。
     *
     * 写盘口径：每来一条新采样，先给「上一条」补上它到这一条的间隔，再追加这一条。
     * 这样文件里每条的 ms 永远是「这一条 -> 下一条」。
     *
     * 读改写一律走 [FileMemo.update]：[Store] 每次调用都可能 new 一个实例，
     * `@Synchronized` 只锁各自实例，护不住同一个文件；进程内唯一的那把锁在 [FileMemo] 里。
     * 时间线要断开时调 [cut]（只给上一条打 [FocusSample.cut]，不删历史、不补假标签）。
     * 边界从这次起才会落盘：老文件里的采样没有 cut，读回来按「连续」处理（口径不变，不猜哪段是锁屏）。
     */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)

        fun all(): List<FocusSample> = FileMemo.get(file) { parse(it) }.sortedBy { it.at }

        /** 只取窗口内的，省得把 30 天全捞出来再筛 */
        fun since(from: Long): List<FocusSample> = all().filter { it.at >= from }

        /**
         * 记一条。返回是否真的写入（UNSURE / 同一屏重复会返回 false）。
         *
         * 只追加、不覆盖历史条目 —— 间隔靠「追加那一刻顺带给上一条补 ms」来维护，
         * 封顶规则和 [compute] 里的一致，所以报告的数和文件里的数不会两套。
         *
         * 上一条已经带 [FocusSample.cut]（锁屏 / 停用 / 改设置 / 判不出来）时，这一条**不继承**那段间隔：
         * 断开之后人到没到、过多久才回来都不知道，补一个封顶值等于凭空造出几分钟学习。
         *
         * @param verdict 这一轮的判定
         * @param fp      这一轮的画面指纹
         */
        fun add(at: Long, verdict: FocusGuard.Verdict, fp: String?): Boolean {
            // 判不出来 = 时间线在这里断（UNSURE 不落采样，只给能断的那一条留边界）。
            // 不记一个「猜的」标签：那正是这份报告以前把午睡算成学习的原因。
            if (verdict == FocusGuard.Verdict.UNSURE) {
                cut(at)
                return false
            }
            var ok = false
            FileMemo.update(file, { parse(it) }, { toJson(it) }) { list ->
                val cur = list.sortedBy { it.at }
                val tail = cur.lastOrNull()
                if (!FocusGuard.keepForReport(FocusGuard.Sample(at, verdict, fp), tail?.fp)) return@update cur
                // 时钟回拨（at 不晚于最后一条）不补间隔：顺序都不知道，补了就是把两段拼成一段
                val ordered = tail != null && at > tail.at
                val gap = if (tail == null || tail.cut || !ordered) 0L
                else (at - tail.at).coerceIn(0L, GAP_CAP_MS)
                // 上一条的 ms 补成「它 -> 这一条」；其余条目原样保留
                val kept = if (ordered) cur.dropLast(1) + tail!!.copy(ms = gap) else cur
                ok = true
                trim(kept + FocusSample(at, 0L, verdict.code, fp), maxOf(at, tail?.at ?: at))
            }
            return ok
        }

        /**
         * 在时间线上留一个边界：给最后一条采样打上 [FocusSample.cut]，不写新标签、不删任何历史。
         *
         * 锁屏、服务停启、关开关 / 改模式、没有权限或截屏通道、判不出来 —— 调用方在**确实发生**这些事时调。
         * 之后的采样既不继承这段间隔（[stepAt] 返回 0），也不和它们连成一段连续专注。
         * 时钟回拨（[at] 早于最后一条）时不动：那一条不是这次的边界。
         *
         * 返回这次是否落了一个边界（没有采样可断、或已经断过时为 false，不重复写盘）。
         */
        fun cut(at: Long = System.currentTimeMillis()): Boolean {
            // 先看内存副本：长时间「判不出来」时每 12 秒都会来一次，不必每次整份重写
            if (!needsCut(FileMemo.get(file) { parse(it) }, at)) return false
            var hit = false
            FileMemo.update(file, { parse(it) }, { toJson(it) }) { list ->
                val next = markCut(list, at)
                hit = next != list
                next
            }
            return hit
        }

        private fun needsCut(list: List<FocusSample>, at: Long): Boolean {
            val tail = list.maxByOrNull { it.at } ?: return false
            return !tail.cut && tail.at <= at
        }

        private fun markCut(list: List<FocusSample>, at: Long): List<FocusSample> {
            val sorted = list.sortedBy { it.at }
            val tail = sorted.lastOrNull() ?: return sorted
            if (tail.cut || tail.at > at) return sorted
            // ms 记的是「这一条 -> 下一条」；断了之后没有下一条，留着旧值就是文件里自相矛盾
            return sorted.dropLast(1) + tail.copy(ms = 0L, cut = true)
        }

        /** 今天一天的报告 */
        fun today(now: Long): Report = compute(since(now - 2 * Fsrs.DAY_MS), now, listOf(dateOf(now)))

        /** 近 [days] 天（含今天）的报告 */
        fun window(now: Long, days: Int): Report =
            compute(since(now - (days + 1) * Fsrs.DAY_MS), now, ReviewLog.recentDates(now, days))

        fun clear() = FileMemo.clear(file, emptyList<FocusSample>())
    }
}
