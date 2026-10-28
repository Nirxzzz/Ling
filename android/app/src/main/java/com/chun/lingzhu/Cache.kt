package com.chun.lingzhu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 结果缓存（v1.58）：同一个画面、同样的设置再识别一次，或者同一段文字再翻一次，直接用上次的结果，不再调接口 ——
 * 省额度、秒出；自动扫描时画面没变也就不花钱了。
 *
 * - 识别：键 = 画面指纹 + 模式/强度/小白/自定义提示词 + 接口/模型/思考强度。画面指纹是去掉状态栏（时间、电量会变）后
 *   缩小成灰度再粗量化的哈希 —— 只认「一模一样的画面」，宁可没命中也不拿错答案（感知哈希那种「差不多就算」会把同一个
 *   App 里的两道不同题当成一道）。
 * - 翻译：键 = 引擎 + 接口/模型 + 目标语言 + 原文。
 * - 最近用过的在前，识别留 SOLVE_MAX 条、翻译留 TRANSLATE_MAX 条，超过 TTL 的丢掉；要重新问就在设置里清空缓存（v1.66 起面板上没有「重识」）。
 * - TTL 按内容时效分档（[TTL_SOLVE_MS] 7 天 / [TTL_TRANSLATE_MS] 30 天 / [TTL_SEARCH_MS] 6 小时），每个 store 自带一档。
 * - 另外 DeepSeek 等接口在服务端还有「前缀缓存」（同样开头的提示词便宜很多），Ling 的系统提示词固定在最前面，自动受益。
 * 列表逻辑是纯函数（单元测试直接测），文件读写在 Store。
 */
object Cache {
    const val SOLVE_MAX = 100
    const val TRANSLATE_MAX = 300

    /**
     * TTL 按**内容的时效**分档，不是一个值打天下：
     *
     * - [TTL_SOLVE_MS] 识别 / 追问 = 7 天。答题内容有过期风险（同一道题隔一周再做，答案可能是旧的），宁可多花一次钱。
     *   **联网搜索开着时不要用这一档**：搜到的答案有时效，调用方整段绕过识别 / 追问缓存（OrbService 已按 searchConfig.enabled 判断）。
     * - [TTL_TRANSLATE_MS] 翻译 / 弹词预翻译 = 30 天。词典、释义、句子译文这类东西基本不变，
     *   7 天太短 —— 单词书里同一个词反复翻，每次都白花一次调用。
     * - [TTL_SEARCH_MS] 联网搜索 = 6 小时。「现在多少钱」这类半天内就会变。
     *
     * 容量不变（识别 [SOLVE_MAX]、翻译 [TRANSLATE_MAX]）：延的是命中的寿命，不是能存多少条。
     */
    const val TTL_SOLVE_MS = 7L * 24 * 3600 * 1000
    const val TTL_TRANSLATE_MS = 30L * 24 * 3600 * 1000
    const val TTL_SEARCH_MS = 6L * 3600 * 1000

    /** 纯函数 [get] / [put] 的默认档 = 识别档（要别的档显式传，见上面三个常量） */
    const val TTL_MS = TTL_SOLVE_MS

    /** created = 第一次存进来的时刻（给「X 分钟前的结果」用）；used = 最近一次命中 */
    data class Item(val k: String, val v: String, val created: Long, val used: Long)

    fun parse(json: String?): List<Item> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Item(o.optString("k"), o.optString("v"), o.optLong("c"), o.optLong("u")).takeIf { it.k.isNotEmpty() && it.v.isNotEmpty() }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Item>): String = JSONArray().apply {
        for (e in list) put(JSONObject().put("k", e.k).put("v", e.v).put("c", e.created).put("u", e.used))
    }.toString()

    /** 命中：返回这条 + 把它挪到最前面（最近用过）的新列表；过期的顺手清掉 */
    fun get(list: List<Item>, k: String, now: Long, ttl: Long = TTL_MS): Pair<Item?, List<Item>> {
        val alive = list.filter { now - it.created < ttl }
        val hit = alive.firstOrNull { it.k == k } ?: return null to alive
        val bumped = hit.copy(used = now)
        return bumped to (listOf(bumped) + alive.filter { it.k != k })
    }

    fun put(list: List<Item>, k: String, v: String, now: Long, max: Int, ttl: Long = TTL_MS): List<Item> =
        (listOf(Item(k, v, now, now)) + list.filter { it.k != k && now - it.created < ttl }).take(max)

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 多段拼成一个键（段之间用不可能出现的字符隔开，免得 "ab"+"c" 和 "a"+"bc" 撞） */
    fun key(vararg parts: String): String = sha256(parts.joinToString("\u0000"))

    /**
     * 画面指纹：gray = 缩小后的灰度像素（行优先，w×h，0~255）。去掉最上面 skipTop 比例（状态栏），
     * 每个像素只留高 4 位（JPEG 细微噪声不影响），再整体哈希。
     */
    fun fingerprint(gray: IntArray, w: Int, h: Int, skipTop: Float = 0.06f): String {
        val top = (h * skipTop).toInt().coerceIn(0, h)
        val sb = StringBuilder((h - top) * w + 16).append(w).append('x').append(h - top).append(':')
        for (y in top until h) for (x in 0 until w) sb.append(('a'.code + (gray[y * w + x].coerceIn(0, 255) shr 4)).toChar())
        return sha256(sb.toString())
    }

    /** Android：截图（base64 JPEG）→ 指纹；解不出来返回 null（那就不走缓存） */
    fun screenFingerprint(b64: String): String? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: throw IllegalStateException()
        // v1.59：90 → 240 宽 —— 两道题只差一个数字时，90 宽可能把这点差别缩没了、拿错答案
        val w = 240; val h = (240f * src.height / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle(); src.recycle()
        val gray = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
        fingerprint(gray, w, h)
    } catch (_: Throwable) { null }

    /**
     * v1.70 变化预检用的**轻量**指纹：同样去掉状态栏，但只缩到 32 宽、灰度后按 8×16 网格取样（见 AutoScanPolicy）。
     * 比 screenFingerprint 便宜一个数量级 —— 自动扫描每轮都要算，用不着分辨「差一个数字的两道题」，只要认出
     * 「还是这一页没动」就够。解不出来返回 null（那轮就照常走，不当成没变化）。
     */
    fun screenLightFingerprint(b64: String): String? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val opt = BitmapFactory.Options().apply { inSampleSize = 4 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: throw IllegalStateException()
        val w = AutoScanPolicy.FP_WIDTH; val h = (AutoScanPolicy.FP_WIDTH.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle(); src.recycle()
        val gray = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
        AutoScanPolicy.lightFingerprint(gray, w, h).ifEmpty { null }
    } catch (_: Throwable) { null }

    /** 一个缓存文件（识别、翻译各一个）。设置里关了缓存就读写都跳过。ttl 由每个 store 自带 —— 各档寿命不一样 */
    class Store(private val ctx: Context, name: String, private val max: Int, private val ttl: Long = TTL_MS) {
        private val file = File(ctx.applicationContext.filesDir, "cache-$name.json")
        private val prefs = Prefs(ctx)

        // v1.63：内存副本 + 后台落盘（以前命中一次也要在主线程整文件重写）
        private fun load(): List<Item> = FileMemo.get(file) { parse(it) }
        private fun save(l: List<Item>) = FileMemo.put(file, l) { toJson(it) }

        @Synchronized fun get(k: String): Item? {
            if (!prefs.useCache) return null
            val (hit, l) = get(load(), k, System.currentTimeMillis(), ttl)
            if (hit != null) { save(l); prefs.noteCacheHit() }   // v1.65：命中可见化 —— 省了多少次调用要有数
            return hit
        }

        /**
         * v1.71：带记账的取。命中/未命中各记一次，省下的钱按「同 prompt 本来要花多少」估。
         *
         * 为什么不直接改上面那个 [get]：那个被识别、追问、翻译三处调用，签名一动就是三个文件的事；
         * 而「记账需要知道模型和 prompt 长度」这两个信息只有调用方有（键是哈希，反推不出 prompt）。
         * 所以老签名原样保留（内部转调这里、feature 记为 unknown），新代码用这个重载。
         */
        @Synchronized fun get(
            k: String,
            feature: String,
            model: String,
            promptChars: Int,
            images: Int = 1,
            date: String = todayStr(),
            stats: Stats? = null,
        ): Item? {
            if (!prefs.useCache) return null
            val (hit, l) = get(load(), k, System.currentTimeMillis(), ttl)
            val s = stats ?: StatsStore(ctx).load()
            if (hit != null) {
                save(l); prefs.noteCacheHit()
                // 省的估算用命中项自己的答案长度（同一个键 = 同一个 prompt，promptChars 由调用方给）
                noteHit(s, date, feature, CostLedger.savedOnHit(model, promptChars, hit.v.length, images))
                trimStats(s); StatsStore(ctx).save(s)
            } else {
                noteMiss(s, date, feature)
                trimStats(s); StatsStore(ctx).save(s)
            }
            return hit
        }
        @Synchronized fun put(k: String, v: String) { if (prefs.useCache && v.isNotBlank()) save(put(load(), k, v, System.currentTimeMillis(), max, ttl)) }
        @Synchronized fun count(): Int = load().size
        fun bytes(): Long = if (file.exists()) file.length() else 0L
        @Synchronized fun clear() = FileMemo.clear(file, emptyList<Item>())   // 与 P0-2 同款：typed clear
    }

    /** 今天（yyyyMMdd）。抽成函数是为了让 Store.get 的记账参数有默认值，测试里也能传固定日期 */
    fun todayStr(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))

    /** 命中统计的落盘（filesDir/cache-stats.json），走 FileMemo 与缓存本体共享副本 */
    class StatsStore(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, "cache-stats.json")
        fun load(): Stats = FileMemo.get(file) { Stats.parse(it) }
        fun save(s: Stats) = FileMemo.put(file, s) { it.toJson() }
        fun bytes(): Long = if (file.exists()) file.length() else 0L
        @Synchronized fun clear() = FileMemo.clear(file, Stats())
    }

    fun solveStore(ctx: Context) = Store(ctx, "solve", SOLVE_MAX, TTL_SOLVE_MS)
    fun translateStore(ctx: Context) = Store(ctx, "translate", TRANSLATE_MAX, TTL_TRANSLATE_MS)

    /** 追问缓存（v1.65）：同屏同问题直接用上次的回答 —— 「举个例子 / 为什么」这类问法重复率很高 */
    const val FOLLOW_MAX = 200
    fun followStore(ctx: Context) = Store(ctx, "follow", FOLLOW_MAX, TTL_SOLVE_MS)

    /**
     * 识别的键：画面 + 会影响回答的所有设置（v1.66 起含「识别关键词」开关：开着时加后缀 —— 老版本存的答案没有关键词行，不能命中）。
     * v2.2：搜索配置进键（开关 / 引擎 / 深度 / 增强 / 凭据身份只进非密指纹），换搜索配置不会命中旧答案。
     * 联网搜索开着时答案带时效，调用方应整段绕过 7 天识别缓存（见 [TTL_SOLVE_MS] 的说明）—— 这里只负责把配置隔离开。
     */
    fun solveKey(fp: String, o: Solver.Options, api: ApiConfig): String =
        key(
            // 屏幕提示词精简（去掉附加人设/风格，只留做题与功能）：命名空间升到 -2，老版本存的答案不再命中，旧数据自然过期
            "solve-screen-2", fp, o.mode, o.detail, o.novice.toString(), o.customPrompt + (if (o.terms) "\u0000terms" else ""),
            api.base, api.model, api.thinkingEffort, WebSearch.configFingerprint(api.searchConfig),
        )

    /** "刚刚" / "12 分钟前" / "3 小时前" / "2 天前" */
    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val m = (now - t) / 60000
        return when { m < 1 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 60 * 24 -> "${m / 60} 小时前"; else -> "${m / 60 / 24} 天前" }
    }

    // ================================================================
    // v1.71：命中率与节省金额
    // ================================================================

    /**
     * 缓存的效率账（一格累加器，三个维度各存一份：总量 / 按天 / 按功能）。
     *
     * 为什么要「按天」：命中率必须能按时间窗看。全生命周期 62% 听着不错，但昨天出了一次误命中导致用户
     * 清了缓存，今天的命中率其实只有 20% —— 混在一起算就成了自我安慰。按天存，摘要里给的是「今天 / 本周」。
     * 「按功能」同理：翻译命中率天然很高（同一段话反复翻），识别低得多；混算会让人误以为识别也在浪费。
     *
     * 命中/未命中各记一次，`saved` 只在命中时加 —— 未命中那笔是真花了钱（已经记进 CostLedger 的 cost），
     * 在这里再记一遍「省了 0」没有信息量。
     */
    data class Stats(
        var hit: Int = 0,
        var miss: Int = 0,
        var savedMicro: Long = 0L,
        val byDay: MutableMap<String, Stats> = LinkedHashMap(),
        val byFeature: MutableMap<String, Stats> = LinkedHashMap(),
    ) {
        val total: Int get() = hit + miss

        /** 命中率（0~1）。分母为 0 时返回 0 而不是 NaN —— 设置页要直接显示，NaN 会印出 "NaN%" */
        fun rate(): Double = if (total == 0) 0.0 else hit.toDouble() / total

        /** 就地加一个叶子（byDay / byFeature 里那些不带子表的就是叶子） */
        fun addLeaf(o: Stats) { hit += o.hit; miss += o.miss; savedMicro += o.savedMicro }

        fun toJson(): String = JSONArray().apply {
            put(JSONObject().put("h", hit).put("m", miss).put("s", savedMicro))
            put(JSONObject().put("k", "byDay").put("v", JSONArray(byDay.map { JSONObject().put("k", it.key).put("v", it.value.toJson()) })))
            put(JSONObject().put("k", "byFeature").put("v", JSONArray(byFeature.map { JSONObject().put("k", it.key).put("v", it.value.toJson()) })))
        }.toString()

        companion object {
            fun parse(json: String?): Stats {
                val s = Stats()
                if (json.isNullOrBlank()) return s
                try {
                    val a = JSONArray(json)
                    val head = a.optJSONObject(0) ?: return s
                    s.hit = head.optInt("h"); s.miss = head.optInt("m"); s.savedMicro = head.optLong("s")
                    for (i in 1 until a.length()) {
                        val o = a.optJSONObject(i) ?: continue
                        val sub = parse(o.optString("v"))
                        when (o.optString("k")) {
                            "byDay" -> for ((k, v) in sub.byDay) s.byDay[k] = v
                            "byFeature" -> for ((k, v) in sub.byFeature) s.byFeature[k] = v
                        }
                    }
                } catch (_: Exception) {}
                return s
            }
        }
    }

    /** 记一次命中：省下的钱按「同一个 prompt 本来要花多少」估（CostLedger.savedOnHit） */
    fun noteHit(s: Stats, date: String, feature: String, savedMicro: Long) {
        s.hit++; s.savedMicro += savedMicro
        s.byDay.getOrPut(date) { Stats() }.let { it.hit++; it.savedMicro += savedMicro }
        s.byFeature.getOrPut(feature) { Stats() }.let { it.hit++; it.savedMicro += savedMicro }
    }

    /** 记一次未命中（真调了接口，钱已经记进 CostLedger 的 cost） */
    fun noteMiss(s: Stats, date: String, feature: String) {
        s.miss++
        s.byDay.getOrPut(date) { Stats() }.miss++
        s.byFeature.getOrPut(feature) { Stats() }.miss++
    }

    /** 统计里留多少天（按天那张表）—— 与 Usage 的 30 天一致 */
    const val STATS_KEEP_DAYS = 30

    /** 按天表裁剪（天 key 形如 20261007，字典序 = 时间序） */
    fun trimStats(s: Stats, keepDays: Int = STATS_KEEP_DAYS) {
        while (s.byDay.size > keepDays) s.byDay.remove(s.byDay.keys.minOrNull() ?: return)
    }

    /** 「今天命中率 62%」/「本周省 ¥0.31」这类给人看的（设置页 / 简报） */
    fun statsLine(s: Stats, date: String): String {
        val d = s.byDay[date] ?: return "今天还没用过缓存"
        val pct = Math.round(d.rate() * 100)
        return "今天命中率 $pct%（${d.hit}/${d.total}）· 省 ${CostLedger.fmtYuan(d.savedMicro)}"
    }

    // ================================================================
    // v1.71：相似画面短路（保守版）
    // ================================================================

    /**
     * 变化像素比例的阈值：低于它就认为「还是这一屏」，直接复用上轮答案，**不调接口**。
     *
     * 默认 2% 的来历：手机截图缩到 64 宽后约 64×140≈9000 个采样点，2% ≈ 180 个点。
     * 光标闪烁是 2×20≈40 个点、视频/动画是局部一片、通知栏下滑几百个点 —— 都在阈值内。
     * 而「翻页」「换了一道题」动的是整屏几千个点，远在阈值外。
     *
     * ⚠️ **风险（必须知道再用）**：这个判定本质是「拿不准就赌它没变」，赌错的后果是**答案过期**
     * （用户看到的是上一屏的答案，而界面已经变了）。三个已知的踩雷场景：
     *   1) 答题 App 里**只改了一个数字/一个选项**：那可能只动了几十个点 → 判定「没变」→ 答案还是上题的。
     *      这是最危险的一类，因为用户完全看不出自己被短路了。
     *   2) 视频/直播页：整帧内容变但缩图后差异集中在局部，可能落在阈值内。
     *   3) 弹窗/键盘弹出会遮挡大片内容，缩图后差异被摊薄。
     * 缓解手段（都已实现）：a) 只在**自动扫描**里启用（用户手动点识别永远真调接口）；
     *      b) 3 分钟时间窗（见 [SIMILAR_WINDOW_MS]）—— 超过就当没变过，宁可多花钱；
     *      c) 阈值和像素容差都是可调常量，用户感到「答案不对，重识一下」时应调低 [SIMILAR_MAX_RATIO] 或关掉开关。
     * 之所以还留着它：省的是**整次识别的钱**（含图片折算的千余 prompt token，量级 ¥0.001~0.01/次），
     * 而连续自动扫描时这种情况很常见（用户停在同一页、光标闪、视频循环）。
     */
    const val SIMILAR_MAX_RATIO = 0.02f

    /**
     * 多久之内才认「同一屏」。默认 3 分钟。
     * 短于此：动画/光标造成的短暂一致不会误判；长于此：用户早就换了内容却还在复用旧答案。
     * 键盘弹出的输入框位置变化也在秒级 —— 键盘弹出会动几百个点，本来就超过 2% 阈值，不靠这个兜。
     */
    const val SIMILAR_WINDOW_MS = 3 * 60 * 1000L

    /**
     * 单个采样点的灰度差超过这个值才算「变了」（0~255）。
     * 12 的来历：JPEG 重新编码 + 屏幕缩放的噪声通常在 5 级以内；12 留了一点余量，
     * 又不至于把「一个数字从 3 变成 8」（差几十级）当成噪声吃掉。
     */
    const val SIMILAR_PIXEL_TOL = 12

    /** 相似度比对的缩图宽度（和 AutoScanPolicy 的轻量指纹一个量级，但这里要算差异、不只要哈希，得多留点信息） */
    const val SIMILAR_WIDTH = 64

    /**
     * 两个灰度图的「变化像素比例」（纯计算，0~1）。
     * 尺寸不一致 → 返回 1f（当全变了）。宁可判「变了」多调一次，也不要在尺寸不同的情况下瞎比。
     */
    fun changedRatio(prev: IntArray, cur: IntArray, tol: Int = SIMILAR_PIXEL_TOL): Float {
        if (prev.size != cur.size || prev.isEmpty()) return 1f
        var diff = 0
        for (i in prev.indices) if (kotlin.math.abs(cur[i] - prev[i]) > tol) diff++
        return diff.toFloat() / prev.size
    }

    /** 一次判定的结果（带原因，设置页/日志能说清「为什么这次没调接口」） */
    enum class SimilarVerdict(val reuse: Boolean, val why: String) {
        REUSE(true, "画面基本没变"),
        NO_PREV(false, "没有上一轮"),
        TOO_OLD(false, "上一轮太久之前"),
        TOO_DIFFERENT(false, "变化超过阈值"),
    }

    /**
     * 相似画面短路的判定（纯函数，单测直接测）。
     *
     * @param lastAt 上一轮**识别完成**的时刻（毫秒）
     * @param ratio  两个灰度图的变化比例（[changedRatio] 的结果）
     */
    fun judgeSimilar(lastAt: Long, now: Long, ratio: Float, maxRatio: Float = SIMILAR_MAX_RATIO, windowMs: Long = SIMILAR_WINDOW_MS): SimilarVerdict {
        if (lastAt <= 0L) return SimilarVerdict.NO_PREV
        // 时间倒流（用户改系统时间）也算太旧，别拿一个未来的 lastAt 无限复用
        if (now - lastAt > windowMs || now < lastAt) return SimilarVerdict.TOO_OLD
        if (ratio >= maxRatio) return SimilarVerdict.TOO_DIFFERENT
        return SimilarVerdict.REUSE
    }

    /** 上一轮识别结果的短期记忆（进程内即可：跨进程重启后重新调一次不心疼，也避免脏数据） */
    class Recent(private val windowMs: Long = SIMILAR_WINDOW_MS) {
        private var gray: IntArray? = null
        private var at: Long = 0L

        @Synchronized fun remember(g: IntArray, now: Long) { gray = g; at = now }

        /**
         * 这一轮要不要复用上一轮。ratio 已由调用方算好（算它要解码截图，放这里会拖慢判定）。
         * 复用后**不更新记忆**：连续复用时保留最初的画面和时间基准，
         * 这样 3 分钟窗口从第一次识别起算、不会因为一直复用而无限续命。
         */
        @Synchronized fun reuse(g: IntArray, now: Long, ratio: Float, maxRatio: Float = SIMILAR_MAX_RATIO): SimilarVerdict {
            val v = judgeSimilar(at, now, ratio, maxRatio, windowMs)
            return v
        }

        @Synchronized fun reset() { gray = null; at = 0L }

        @Synchronized fun has(): Boolean = gray != null
    }

    /**
     * Android：截图 → 用于相似度比对的灰度小图（SIMILAR_WIDTH 宽）。
     * 解不出来返回 null —— 调用方按「拿不到就真调接口」处理，不当成「没变化」。
     */
    fun screenGray(b64: String, width: Int = SIMILAR_WIDTH): IntArray? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: throw IllegalStateException()
        val w = width; val h = (width.toFloat() * src.height / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle(); src.recycle()
        IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 }
    } catch (_: Throwable) { null }
}
