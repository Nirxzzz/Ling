package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v2.3.1 复习流水：每评一次分记一行（什么时候、哪一类、哪张卡、评了几档）。
 *
 * ## 为什么要有这张表
 * 学习面板要回答「记住多少、练了多少、练了多久、连着练了几天」这四个问题，
 * 但知识卡和单词的 FSRS 状态都只存**下一次该复习的时间**（[Fsrs.Card.due]），
 * 是「未来」的投影。想回看过去，就得有一份只追加的流水 —— 现有数据里一条都找不到，
 * 硬从 FSRS 状态反推只能得到当下的快照，画不出任何一条按天的曲线。
 *
 * ## 为什么记「时刻」而不是「时长」
 * 学习时长按「相邻两条评分的间隔」累计（见 [LearningStats.totalMs]）。
 * 这样不用在前台开计时器、不用处理「切后台 / 息屏 / 被系统杀掉」，
 * 也就不会出现「睡前忘了关，第二天记成学了 8 小时」这种假数据。
 * 代价是一条间隔可能横跨「复习到一半去回消息」，所以单条在统计侧封顶 [MAX_GAP_MS]（5 分钟）。
 *
 * ## 存多久
 * 只留最近 [KEEP_DAYS] 天、最多 [MAX] 条：面板最长的曲线是「近 90 天」，
 * 更早的明细对判断学习效果已经没有意义，留着只会让备份越拖越大。
 *
 * 落盘 filesDir/reviewlog.json，走 [FileMemo]（进程内一份副本 + 后台顺序写），
 * 和 [CostLedger.Store] / [KnowledgeCard.Store] 同一套：复习页和面板页各 new 一个 Store 看到的是同一份。
 *
 * 解析 / 裁剪 / 日期计算全是纯函数（[parse] / [toJson] / [trim] / [recentDates]），单测直接跑，不碰 Android。
 */
object ReviewLog {

    /** 落盘文件名（filesDir 下） */
    const val FILE = "reviewlog.json"

    /** 留最近多少天的明细（面板曲线最长 90 天，只多留一天做边界缓冲） */
    const val KEEP_DAYS = 90

    /** 条数硬上限。一天 200 条已经很猛，90 天远到不了；真撞上了说明在刷，裁掉最老的 */
    const val MAX = 20_000

    /** 一条间隔最多算多少毫秒（5 分钟）。超过这个数说明人不在「学习」，见文件头说明 */
    const val MAX_GAP_MS = 5L * 60 * 1000

    /**
     * 复习对象的两类。分开记是因为这两条学习路径的代价差得远：
     * 知识卡是「正面 → 翻面 → 四档」，单词是「滑一下就过」，
     * 混在一起算平均停顿会把单词的时长抬得离谱。
     */
    enum class Kind(val code: String, val label: String) {
        KNOWLEDGE("card", "知识卡"),
        WORD("word", "单词");

        companion object {
            /** 认不出的 code（老版本写的、文件被手改过）兜到知识卡：宁可归错栏，也别把这条流水丢了 */
            fun of(code: String?): Kind = entries.firstOrNull { it.code == code } ?: KNOWLEDGE
        }
    }

    /**
     * 一条流水。
     *
     * @param at     评分时刻（毫秒，[System.currentTimeMillis] 口径）
     * @param kind   评的是知识卡还是单词
     * @param key    卡片标识：知识卡是 id 字符串，单词是 [Words.key]（词库 + 正面）
     * @param rating 评分档位，1..4 与 [Fsrs.Rating.code] 一致（Again / Hard / Good / Easy）
     */
    data class R(val at: Long, val kind: Kind, val key: String, val rating: Int) {

        /**
         * 这一档算「记住了」吗：评分 ≥ Good（3）。
         * 这是给「到期日实际留存率」用的近似 —— FSRS 的 Good 意思是「想起来了，但有点费劲」，
         * 已经过了「没想起来」这条线；Hard 和 Again 都算没记住。
         */
        val remembered: Boolean get() = rating >= 3

        fun toJson(): JSONObject = JSONObject()
            .put("at", at)
            .put("k", kind.code)
            .put("key", key)
            .put("r", rating)

        companion object {
            fun from(o: JSONObject?): R? {
                if (o == null) return null
                val at = o.optLong("at", 0L)
                if (at <= 0L) return null   // 没有时刻的流水归不到任何一天，当坏行扔掉
                return R(at, Kind.of(o.optString("k")), o.optString("key"), o.optInt("r", 0).coerceIn(0, 4))
            }
        }
    }

    // ---------------- 纯函数：序列化 / 裁剪 ----------------

    fun parse(json: String?): List<R> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i -> R.from(a.optJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<R>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    /**
     * 裁剪：先按时间窗（保留 [now] 往前 [keepDays] 天），再按条数上限，都按时间升序留下最新的。
     *
     * 为什么还要单独做条数上限：时间窗挡不住「同一天被脚本刷了十万条」，
     * 而这份文件每次落盘都要整份重写，一条超大流水会让每次记分都变慢。
     */
    fun trim(list: List<R>, now: Long, keepDays: Int = KEEP_DAYS, max: Int = MAX): List<R> {
        val cutoff = now - keepDays * Fsrs.DAY_MS
        val inWindow = list.filter { it.at > cutoff }.sortedBy { it.at }
        return if (inWindow.size <= max) inWindow else inWindow.takeLast(max)
    }

    // ---------------- 纯函数：日期 ----------------

    /**
     * 把毫秒归到「本地日历日」的 yyyyMMdd。
     *
     * 用设备默认时区而不是 UTC：学生看到的是「我昨天学了 30 张」，
     * 这里的「昨天」是手机上的昨天。[CostLedger] 那边按 UTC 拼日期串是给账单按日聚合用的，
     * 两套口径各自服务于自己的展示，不要混用。
     */
    fun dateOf(at: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(at))

    /**
     * [now] 往前 [days] 天（含今天）的日期串，升序。
     * 面板的「近 30 天复习量」柱状图直接拿它当横轴：没有复习的那天也要留一根高度为 0 的柱，
     * 否则「断了两天」会画成「一直连着」，那是最容易骗自己的图。
     */
    fun recentDates(now: Long, days: Int): List<String> {
        if (days <= 0) return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        // 先归零到当天 0 点再回退，避免夏令时那天 23:00 减 24h 落回前一天
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -(days - 1))
        return (0 until days).map {
            val s = fmt.format(java.util.Date(cal.timeInMillis))
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            s
        }
    }

    /** [d]（yyyyMMdd）往后 [n] 天；跨月靠 Calendar 自带进位，不手写月末判断 */
    fun plusDays(d: String, n: Int): String {
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(d.substring(0, 4).toInt(), d.substring(4, 6).toInt() - 1, d.substring(6, 8).toInt())
        }
        cal.add(java.util.Calendar.DAY_OF_MONTH, n)
        return String.format(
            java.util.Locale.US, "%04d%02d%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    // ---------------- 落盘 ----------------

    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)

        @Synchronized fun load(): List<R> = FileMemo.get(file) { parse(it) }

        private fun save(l: List<R>) = FileMemo.put(file, l) { toJson(it) }

        /**
         * 记一条并落盘。
         *
         * 读-改-写一把，写完顺手 [trim]：这张表只会变大，
         * 等面板打开时才裁的话，最坏是「一整个季度没整理，一打开先卡一下」。
         * 每次只多一条，按条数裁是 O(n) 复制，几千条的量级不值一提。
         */
        @Synchronized fun add(at: Long, kind: Kind, key: String, rating: Int) {
            val next = trim(load() + R(at, kind, key, rating), at)
            save(next)
        }

        @Synchronized fun all(): List<R> = load().sortedBy { it.at }

        @Synchronized fun clear() = FileMemo.clear(file, emptyList<R>())
    }
}
