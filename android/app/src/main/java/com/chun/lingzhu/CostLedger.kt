package com.chun.lingzhu

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * v1.71 成本台账（纯 JVM，可单测）：把每一次真实请求折成钱记下来，按天 / 按模型 / 按功能（识别·追问·翻译·搜索·聊天）
 * 三个维度聚合，落在 filesDir/ledger.json，按月滚动、只留 [KEEP_MONTHS] 个月。
 *
 * 为什么要有台账（Usage.kt 只有 token，看不出「花在哪」）：
 *  - Usage 记的是「用掉多少 token」，台账记的是「这些 token 值多少钱、哪个功能花的、缓存替我省了多少」。
 *    同样 3000 token，识别（图片折算）比翻译贵一个量级，只看 token 会误判「翻译最费」。
 *  - 省钱要看得见才有动力去开缓存：每次命中都按「同 prompt 本来要花多少」记一笔节省，
 *    设置页能写「本月省 ¥1.83」。没有这个数，用户凭感觉判断，只会觉得「好像没快」。
 *  - 预算护栏（CostPolicy.Budget）要一个可信的当日累计数，直接从台账的 cost 字段读，不再另算一套。
 *
 * 金额一律用**微元**（1 元 = 1_000_000 微元）整数存，不用浮点 —— 一天几百次请求，浮点累加会漂；
 * 整数也方便断言（省下来的是不是正好等于该省的）。
 *
 * 价格表（[priceOf]）按模型名匹配：DeepSeek 官方 ¥0.5/M 输入、¥2/M 输出；MiMo 便宜，取的是宣传降幅里的保守值。
 * ⚠️ 价格会变、也会随厂商活动波动：**这里只是估算，用于「今天大概花了多少」和预算护栏**，
 * 真实账单以厂商后台为准。改价只改 [PRICE_DEEPSEEK] / [PRICE_MIMO] 两行。
 */
object CostLedger {

    // ---------------- 存多久 ----------------

    /** 台账按月滚动：只留最近 3 个月（当月 + 前两个月）。比 Usage 的 30 天宽，因为账单是按月看的 */
    const val KEEP_MONTHS = 3

    /** 落盘文件名（filesDir 下） */
    const val FILE = "ledger.json"

    // ---------------- 价格表 ----------------

    /**
     * 某模型的单价，单位**微元 / 百万 token**（这样 cost 那边全是整数运算，不用浮点）。
     * cost(pin, pout) = (pin * inMicroPerM + pout * outMicroPerM) / 1_000_000
     */
    data class Price(val inMicroPerM: Long, val outMicroPerM: Long) {
        fun costMicro(pin: Long, pout: Long): Long =
            if (pin <= 0 && pout <= 0) 0L else (pin * inMicroPerM + pout * outMicroPerM) / 1_000_000L
    }

    /** DeepSeek 官方价：输入 ¥0.5/M、输出 ¥2/M */
    val PRICE_DEEPSEEK = Price(500_000L, 2_000_000L)

    /**
     * MiMo 用的保守估值（¥0.1/M 输入、¥0.4/M 输出，约 DeepSeek 的 1/5）。
     * ⚠️ MiMo 官方价格随版本和活动反复调整过（这里取的是宣传降幅里的低档），
     *    估便宜了只会让「省了多少钱」显得多，不会让用户多花钱 —— 方向是安全的。
     */
    val PRICE_MIMO = Price(100_000L, 400_000L)

    /** 认不出来的模型按 DeepSeek 算：宁可高估，别让人以为「这个模型便宜随便刷」 */
    val PRICE_FALLBACK = PRICE_DEEPSEEK

    /** 按模型名取价（大小写不敏感；模型名里带 deepseek / mimo 就走对应价） */
    fun priceOf(model: String): Price {
        val m = model.lowercase()
        return when {
            m.contains("mimo") -> PRICE_MIMO
            m.contains("deepseek") -> PRICE_DEEPSEEK
            else -> PRICE_FALLBACK
        }
    }

    // ---------------- 功能分类 ----------------

    /**
     * 一次请求干的活是什么。和 CostPolicy.Task（决定路由）是两回事：Task 是「能不能走便宜接口」，
     * 这里是「这笔钱记到哪一栏」，用户看账单时认的是这一套。
     */
    enum class Feature(val code: String, val label: String) {
        SOLVE("solve", "识别"),
        FOLLOW("follow", "追问"),
        TRANSLATE("translate", "翻译"),
        SEARCH("search", "搜索"),
        CHAT("chat", "聊天");

        companion object {
            /** 台账里读到不认识的 code（老版本写的、代码手改了）时兜到识别 —— 宁可归错栏，也别把这一笔记丢了 */
            fun of(code: String?): Feature = values().firstOrNull { it.code == code } ?: SOLVE
        }
    }

    // ---------------- 计数器 ----------------

    /** 一格累加器：请求数 / 输入 token / 输出 token / 花了多少微元 / 缓存省了多少微元 */
    data class Bucket(
        var req: Int = 0,
        var pin: Long = 0,
        var pout: Long = 0,
        var cost: Long = 0,
        var saved: Long = 0,
    ) {
        /** 就地加（三个维度共用同一套算法，少写三遍） */
        fun add(o: Bucket) {
            req += o.req; pin += o.pin; pout += o.pout; cost += o.cost; saved += o.saved
        }

        fun toJson(): JSONObject =
            JSONObject().put("req", req).put("pin", pin).put("pout", pout).put("cost", cost).put("saved", saved)

        companion object {
            fun from(o: JSONObject?): Bucket {
                if (o == null) return Bucket()
                return Bucket(o.optInt("req"), o.optLong("pin"), o.optLong("pout"), o.optLong("cost"), o.optLong("saved"))
            }
        }
    }

    /**
     * 一个月的台账。三张表（按天 / 按模型 / 按功能）都存下来，查询直接取，不用现算 ——
     * 一个月最多几万行请求，现算会让设置页每次打开都扫全量。总量（total）不存，
     * 读取时从 days 现算：这样三张表永远不可能对不上（存第四份就多一处能写歪的地方）。
     */
    class Month {
        val days = LinkedHashMap<String, Bucket>()
        val models = LinkedHashMap<String, Bucket>()
        val features = LinkedHashMap<String, Bucket>()

        /** 这个月所有请求的合计（从 days 现算） */
        fun total(): Bucket {
            val t = Bucket()
            for (b in days.values) t.add(b)
            return t
        }

        fun day(date: String): Bucket = days[date] ?: Bucket()

        fun byFeature(f: Feature): Bucket = features[f.code] ?: Bucket()

        fun byModel(model: String): Bucket = models[model] ?: Bucket()

        /** 记一笔：同时落三张表 */
        fun record(date: String, model: String, f: Feature, pin: Long, pout: Long, cost: Long, saved: Long) {
            val b = Bucket(1, pin, pout, cost, saved)
            days.getOrPut(date) { Bucket() }.add(b)
            models.getOrPut(model) { Bucket() }.add(b)
            features.getOrPut(f.code) { Bucket() }.add(b)
        }

        fun toJson(): JSONObject {
            val d = JSONObject(); for ((k, v) in days) d.put(k, v.toJson())
            val m = JSONObject(); for ((k, v) in models) m.put(k, v.toJson())
            val f = JSONObject(); for ((k, v) in features) f.put(k, v.toJson())
            return JSONObject().put("d", d).put("m", m).put("f", f)
        }

        companion object {
            fun from(o: JSONObject?): Month {
                val out = Month()
                if (o == null) return out
                for ((dst, src) in listOf(out.days to o.optJSONObject("d"), out.models to o.optJSONObject("m"), out.features to o.optJSONObject("f"))) {
                    val j = src ?: continue
                    val ks = j.keys()
                    while (ks.hasNext()) { val k = ks.next(); dst[k] = Bucket.from(j.optJSONObject(k)) }
                }
                return out
            }
        }
    }

    // ---------------- 序列化 ----------------

    fun parse(json: String?): MutableMap<String, Month> {
        val out = LinkedHashMap<String, Month>()
        if (json.isNullOrBlank()) return out
        try {
            val o = JSONObject(json).optJSONObject("months") ?: return out
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val m = Month.from(o.optJSONObject(k)) ?: continue
                if (k.length == 6 && k.all { it.isDigit() }) out[k] = m
            }
        } catch (_: Exception) {}
        return out
    }

    fun toJson(ledger: MutableMap<String, Month>): String {
        val months = JSONObject()
        for ((k, m) in ledger) months.put(k, m.toJson())
        return JSONObject().put("v", 1).put("months", months).toString()
    }

    // ---------------- 记账 ----------------

    /**
     * 记一笔请求（纯函数，就地改 ledger）。
     *
     * @param date   yyyyMMdd
     * @param model  模型名（决定单价）
     * @param saved  这一笔因为缓存/短路省下的钱（微元）；真调了接口就传 0
     */
    fun record(
        ledger: MutableMap<String, Month>,
        date: String,
        model: String,
        f: Feature,
        pin: Long,
        pout: Long,
        saved: Long = 0L,
    ): MutableMap<String, Month> {
        if (date.length < 6) return ledger
        val ym = date.substring(0, 6)
        val cost = priceOf(model).costMicro(pin, pout)
        ledger.getOrPut(ym) { Month() }.record(date, model, f, pin, pout, cost, saved)
        return ledger
    }

    /**
     * 月滚动：只留最近 [KEEP_MONTHS] 个月，ym 形如 202610（字典序 = 时间序，直接比字符串）。
     * 保留当期及前两个月 —— 趋势要看三个月，第四个月以前的明细对用户没有决策价值。
     */
    fun roll(ledger: MutableMap<String, Month>, nowYm: String, keep: Int = KEEP_MONTHS): MutableMap<String, Month> {
        if (keep <= 0) return ledger
        while (ledger.size > keep) {
            val oldest = ledger.keys.minOrNull() ?: break
            ledger.remove(oldest)
        }
        // 台账里存了比 nowYm 新的月份（用户改系统时间往前调 / 文件被手改过）→ 顺手裁掉，免得「本月」永远显示 0
        ledger.keys.filter { it > nowYm }.forEach { ledger.remove(it) }
        return ledger
    }

    // ---------------- 查询 ----------------

    fun monthOf(ledger: Map<String, Month>, ym: String): Month? = ledger[ym]

    fun today(ledger: Map<String, Month>, date: String): Bucket = monthOf(ledger, date.substring(0, 6))?.day(date) ?: Bucket()

    fun monthTotal(ledger: Map<String, Month>, ym: String): Bucket = monthOf(ledger, ym)?.total() ?: Bucket()

    /**
     * 若干天的合计（本周 / 本月 / 最近 N 天都走这里）。
     * 日期可能跨月（比如本周一在 9 月、周日在 10 月），所以按每天自己的 ym 去查，不假设整段在同一个月。
     */
    fun sumDays(ledger: Map<String, Month>, dates: List<String>): Bucket {
        val t = Bucket()
        for (d in dates) t.add(today(ledger, d))
        return t
    }

    /** 某月按功能的合计（用来算「识别占了 78%」这种占比） */
    fun byFeature(ledger: Map<String, Month>, ym: String): Map<Feature, Bucket> =
        Feature.values().associateWith { monthOf(ledger, ym)?.byFeature(it) ?: Bucket() }

    fun byModel(ledger: Map<String, Month>, ym: String): Map<String, Bucket> =
        monthOf(ledger, ym)?.models?.toMap() ?: emptyMap()

    // ---------------- 成本估算（缓存命中省多少 / 护栏预估） ----------------

    /**
     * 一张手机截图折算多少 prompt token。1080×2400 的屏摄在视觉模型里按 14×14 块算约 1300，
     * 取整 1100（宁可略低：估高了会让「省了多少」显得比实际多）。
     */
    const val IMAGE_TOKEN_COST = 1100L

    /** 估算这次请求「本该花多少」的输入 token：文字部分按中文 0.62 token/字（与 Usage 同系数）+ 每张图 IMAGE_TOKEN_COST */
    fun estimatePin(promptChars: Int, images: Int = 0): Long =
        if (promptChars <= 0 && images <= 0) 0L else
            Math.round(promptChars * Usage.ZH_TOKEN_PER_CHAR) + images * IMAGE_TOKEN_COST

    /** 估算输出 token：按回答字数（与 Usage.estimate 同一条路径，保证两个模块口径一致） */
    fun estimatePout(answerChars: Int): Long = if (answerChars <= 0) 0L else Usage.estimate(answerChars).toLong()

    /**
     * 缓存命中省了多少（微元）：**按「同一个 prompt 这次真发出去要花多少」估**。
     * 同 prompt = 同一个识别键的画面 + 同一套设置，所以 promptChars 也一样 —— 这正是缓存能命中的前提，
     * 拿一个「平均 prompt」去估会高估或低估，这里选择用命中项自己的输入长度。
     *
     * @param images 本来要发几张图（省图模式下追问是 0 张 —— 省下来的更多，别按老口径估）
     */
    fun savedOnHit(
        model: String,
        promptChars: Int,
        answerChars: Int,
        images: Int = 1,
    ): Long {
        val pin = estimatePin(promptChars, images)
        val pout = estimatePout(answerChars)
        return priceOf(model).costMicro(pin, pout)
    }

    // ---------------- 展示 ----------------

    /** 微元 → 「¥0.0123」这样的小数额（成本常在分级以下，硬凑两位小数会全变 0.00） */
    fun fmtYuan(micro: Long): String {
        val yuan = micro / 1_000_000.0
        return when {
            micro <= 0 -> "¥0"
            yuan < 0.01 -> String.format(java.util.Locale.US, "¥%.4f", yuan)
            yuan < 100 -> String.format(java.util.Locale.US, "¥%.2f", yuan)
            else -> String.format(java.util.Locale.US, "¥%.0f", yuan)
        }
    }

    /** 「1.2万」这种给 token 数用（沿用 Usage.fmt 的截短风格，两处显示才对得上） */
    fun fmtTokens(n: Long): String = when {
        n >= 100_000L -> String.format(java.util.Locale.US, "%.1f万", n / 10000.0)
        n >= 10_000L -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
        else -> n.toString()
    }

    /**
     * 成本摘要（多行，给设置页 / 简报直接显示）。
     * 顺序按「用户最想知道的」排：今天花了多少 → 本周 → 本月 → 缓存省了多少 → 钱花在哪个功能上。
     */
    fun summary(ledger: Map<String, Month>, date: String, datesOfThisWeek: List<String>, ym: String = date.substring(0, 6)): String {
        val d = today(ledger, date)
        val w = sumDays(ledger, datesOfThisWeek)
        val m = monthTotal(ledger, ym)
        val sb = StringBuilder()
        sb.append("今天 ${fmtYuan(d.cost)}（${d.req} 次）\n")
        sb.append("本周 ${fmtYuan(w.cost)}（${w.req} 次）\n")
        sb.append("本月 ${fmtYuan(m.cost)}（${m.req} 次 · ↑${fmtTokens(m.pin)} ↓${fmtTokens(m.pout)} tokens）\n")
        sb.append("缓存本月省下 ${fmtYuan(m.saved)}\n")
        val top = topFeatures(ledger, ym, 3)
        if (top.isNotEmpty()) sb.append("本月占比：" + top.joinToString("、") { "${it.first.label} ${it.second}%" })
        return sb.toString()
    }

    /** 一行摘要（面板/通知里塞不下多行时用） */
    fun summaryLine(ledger: Map<String, Month>, date: String, ym: String = date.substring(0, 6)): String {
        val d = today(ledger, date)
        val m = monthTotal(ledger, ym)
        return "今天 ${fmtYuan(d.cost)} · 本月 ${fmtYuan(m.cost)} · 缓存省 ${fmtYuan(m.saved)}"
    }

    /**
     * 功能占比（降序，只取前 n 个；四舍五入到整数百分点）。
     * 占比按**花了多少钱**算，不按次数 —— 识别一次的钱够翻译十次，只看次数会让人误判。
     */
    fun topFeatures(ledger: Map<String, Month>, ym: String, n: Int = 3): List<Pair<Feature, Int>> {
        val byF = byFeature(ledger, ym)
        val total = byF.values.sumOf { it.cost }
        if (total <= 0) return emptyList()
        return byF.entries
            .filter { it.value.cost > 0 }
            .sortedByDescending { it.value.cost }
            .take(n)
            .map { it.key to Math.round(it.value.cost * 100.0 / total).toInt() }
    }

    // ---------------- 日期工具（纯函数，方便单测） ----------------

    private fun cal(date: String) = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(date.substring(0, 4).toInt(), date.substring(4, 6).toInt() - 1, date.substring(6, 8).toInt())
    }

    private fun fmt(c: java.util.Calendar): String = String.format(
        java.util.Locale.US, "%04d%02d%02d",
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
    )

    /**
     * 一周七天（周一 ~ 周日）的 yyyyMMdd 列表。
     * 用 UTC 时区算，避开「这台手机在哪个时区 / 夏令时」的影响 —— 跨月那几天尤其重要
     * （本地时区差一天，日期就落到上个月，月表直接查空）。
     */
    fun weekDates(date: String): List<String> {
        val c = cal(date)
        // Calendar 的周一=2、周日=1；转成「周一=0 … 周日=6」再往前挪到本周一
        c.add(java.util.Calendar.DAY_OF_MONTH, -((c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7))
        val out = ArrayList<String>(7)
        for (i in 0..6) { out.add(fmt(c)); c.add(java.util.Calendar.DAY_OF_MONTH, 1) }
        return out
    }

    /** 一个月的全部日期（yyyyMMdd 升序）。做「本月逐日」用，不用再拼字符串。 */
    fun monthDates(ym: String): List<String> {
        val y = ym.substring(0, 4).toInt()
        val m = ym.substring(4, 6).toInt()
        val last = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, m - 1, 1)
        }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        return (1..last).map { String.format(java.util.Locale.US, "%s%02d", ym, it) }
    }

    // ---------------- 落盘 ----------------

    /**
     * 台账文件（filesDir/ledger.json）。走 FileMemo：进程内一份副本、后台顺序写，
     * 和 Cache.Store 同一套 —— 悬浮球服务、历史页、设置页各 new 一个 Ledger 时看到的是同一份数据，
     * 不会出现「设置页显示 ¥0.3、面板显示 ¥0.1」这种同屏不一致。
     */
    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)

        @Synchronized fun load(): MutableMap<String, Month> = FileMemo.get(file) { parse(it) }

        private fun save(l: MutableMap<String, Month>) = FileMemo.put(file, l) { toJson(it) }

        /** 记一笔并落盘。nowYm / date 由调用方传入（App 侧用 Prefs.todayInt()），单测里传固定值 */
        @Synchronized fun record(date: String, nowYm: String = date.substring(0, 6), model: String, f: Feature, pin: Long, pout: Long, saved: Long = 0L) {
            val l = load()
            record(l, date, model, f, pin, pout, saved)
            roll(l, nowYm)
            save(l)
        }

        @Synchronized fun snapshot(): MutableMap<String, Month> = load()

        fun bytes(): Long = if (file.exists()) file.length() else 0L
        @Synchronized fun clear() = FileMemo.clear(file, LinkedHashMap<String, Month>())
    }
}
