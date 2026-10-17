package com.chun.lingzhu

import android.content.Context
import org.json.JSONObject

/**
 * 背单词预翻译管线（纯规则在这里，单元测试直接测）：
 * 牌组加载 / 卡片弹出前，后台把待弹的单词先经 DeepL 翻成中文释义并缓存，弹卡命中就秒出；
 * 没命中的走老路现场查（TranslateActivity 那套不变）。
 *  - 缓存键 = deck + word（小写归一），30 天过期（同翻译档：单词释义稳定，过期太短只是白花钱重翻）；存 Prefs 一条 JSON（键 wordPre）。
 *  - 批量节制：一轮最多 BATCH_MAX = 20 个词；失败静默降级（不弹错、不重试同一个词）；
 *    无网（isOnline = false）不试；上一轮批量失败后冷却 FAIL_COOLDOWN_MS 内也不再试。
 * 网络（DeepL）与 Prefs 都从外面注入，纯函数只管选词、缓存、节流。
 */
object WordPretranslate {
    /** 一轮最多预取多少个词 */
    const val BATCH_MAX = 20
    /**
     * 一次「导入词库后立刻全量预翻译」最多连跑多少批：50 批 × 20 词 = 1000 词。
     * 保守上限是刻意的 —— DeepL 免费版按字符计费，一次性把几千词翻完既慢又容易撞额度上限（456）；
     * 超出的词不硬翻，下次弹卡时懒加载的 [WordPretranslateStore.prefetch] 会接着补齐。
     */
    const val PREFETCH_ALL_MAX_BATCHES = 50
    /** 单词释义稳定，够用翻译那一档（30 天）—— 与 [Cache.TTL_TRANSLATE_MS] 同档 */
    const val TTL_MS = Cache.TTL_TRANSLATE_MS
    /** 批量失败后的冷却期：期间不再发预取请求（静默降级） */
    const val FAIL_COOLDOWN_MS = 30L * 60 * 1000
    /** 缓存最多存多少条（超出丢最旧的） */
    const val STORE_MAX = 500
    const val PREF_KEY = "wordPre"

    data class Entry(val k: String, val v: String, val at: Long)

    fun key(deck: String, word: String) = deck + "\u0000" + word.trim().lowercase()

    fun parse(json: String?): Map<String, Entry> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().mapNotNull { k ->
                val e = o.optJSONObject(k) ?: return@mapNotNull null
                val v = e.optString("v")
                if (v.isEmpty()) null else Entry(k, v, e.optLong("at"))
            }.associate { it.k to it }
        } catch (_: Exception) { emptyMap() }
    }

    fun toJson(m: Map<String, Entry>): String = JSONObject().apply {
        m.forEach { (k, e) -> put(k, JSONObject().put("v", e.v).put("at", e.at)) }
    }.toString()

    /** 命中：没过期才算命中；过期当没有 */
    fun get(m: Map<String, Entry>, k: String, now: Long, ttl: Long = TTL_MS): String? =
        m[k]?.takeIf { now - it.at < ttl }?.v

    fun put(m: Map<String, Entry>, k: String, v: String, now: Long, max: Int = STORE_MAX, ttl: Long = TTL_MS): Map<String, Entry> =
        (mapOf(k to Entry(k, v, now)) + m.filter { it.key != k && now - it.value.at < ttl }).let { full ->
            if (full.size <= max) full
            else full.entries.sortedByDescending { it.value.at }.take(max).associate { it.toPair() }
        }

    /**
     * 这张卡要不要预翻译：**背面非空 = 词库自带释义，不用翻**（v1.81）。
     * 红宝书三级自带完整书页式背面，再送 DeepL 翻一遍就是白烧一次额度；内置 5 级词库背面是空串，
     * 照旧要翻。判断只看 back 是不是空白，不看内容长短。
     */
    fun needsTranslate(c: Words.Card): Boolean = c.back.isBlank()

    /** 本轮要预取的词：没缓存或已过期的，按传入顺序最多 max 个 */
    fun pending(
        m: Map<String, Entry>,
        deckWords: List<Pair<String, String>>,
        now: Long,
        ttl: Long = TTL_MS,
        max: Int = BATCH_MAX,
    ): List<Pair<String, String>> {
        if (max <= 0) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for ((deck, word) in deckWords) {
            val w = word.trim()
            if (w.isEmpty() || !seen.add(key(deck, w))) continue
            if (get(m, key(deck, w), now, ttl) != null) continue
            out.add(deck to w)
            if (out.size >= max) break
        }
        return out
    }

    /** 能不能发请求：无网不试；上次批量失败还在冷却期也不试 */
    fun canFetch(isOnline: Boolean, lastFailAt: Long, now: Long, cooldown: Long = FAIL_COOLDOWN_MS): Boolean =
        isOnline && now - lastFailAt >= cooldown

    /**
     * 批量预取：逐个调 translate（注入，方便测试与换引擎），失败静默跳过该词。
     * 返回 (成功的 词→译文, 本轮是否整体失败)：一个都没成并且确实发过请求 = 整体失败（记冷却）。
     *
     * 键用 [key]（带词库 id）—— 这是 v1.78 的修正：原来这里写的是 key("", word)，
     * 而读缓存一律走 lookup → key(deck, word)，等于**写进去的永远读不出来**（预翻译白翻）。
     */
    fun fetchAll(
        batch: List<Pair<String, String>>,
        translate: (String) -> String,
    ): Pair<Map<String, String>, Boolean> {
        if (batch.isEmpty()) return emptyMap<String, String>() to false
        val ok = LinkedHashMap<String, String>()
        var attempted = 0
        for ((deck, word) in batch) {
            try { ok[key(deck, word)] = translate(word); attempted++ } catch (_: Exception) { /* 静默降级 */ }
        }
        return ok to (attempted == 0)
    }

    /**
     * 循环跑 [fetchOne]（一次 = 一批翻译）直到该停为止，返回各批成功数之和。
     * 停止条件只有三个，都看「这一批成功几个」：
     *  - 跑满 [maxBatches] 批：额度保险丝，不让一次导入把 DeepL 额度烧光；
     *  - [fetchOne] 返回 0：没词要翻了 / 无网 / 冷却中 / 整批失败（失败时外面已记冷却，下次自然接着退避）；
     *  - [fetchOne] 返回负数：内部异常，视为收手（不把异常往外抛）。
     * 纯逻辑：具体每批干什么由调用方注入，单元测试直接用假实现验证这三条停止语义。
     */
    fun runBatches(maxBatches: Int, fetchOne: () -> Int): Int {
        if (maxBatches <= 0) return 0
        var total = 0
        for (batch in 0 until maxBatches) {
            val n = fetchOne()
            if (n <= 0) break
            total += n
        }
        return total
    }
}

/**
 * 预翻译缓存的存取（Prefs 一条 JSON），并顺手提供「挑词 + 请求 + 回填」的一站式入口，
 * 牌组加载 / 弹卡前在后台线程调 [prefetch] 一行即可。UI 接线归 word-ui 同事（本文件不动 WordCard / OrbService）。
 */
class WordPretranslateStore(private val prefs: Prefs) {
    @Volatile private var lastFailAt: Long = 0

    fun load(): Map<String, WordPretranslate.Entry> = WordPretranslate.parse(prefs.rawGet(WordPretranslate.PREF_KEY))
    private fun save(m: Map<String, WordPretranslate.Entry>) = prefs.rawPut(WordPretranslate.PREF_KEY, WordPretranslate.toJson(m))

    fun lookup(deck: String, word: String, now: Long): String? =
        WordPretranslate.get(load(), WordPretranslate.key(deck, word), now)

    /**
     * 后台批量预取：cards = 全部待弹卡（deck, front）。无网 / 冷却期直接返回，一次最多 BATCH_MAX 个。
     * v1.81：**背面非空的卡直接跳过**（词库自带释义，再翻一遍白烧额度），只把「只有单词」的卡排进队列。
     * 返回本轮实际写入缓存的条数。
     */
    fun prefetch(cards: List<Pair<String, Words.Card>>, deepl: DeepL, isOnline: Boolean, now: Long): Int {
        val m = load().toMutableMap()
        val batch = WordPretranslate.pending(
            m,
            cards.filter { WordPretranslate.needsTranslate(it.second) }.map { it.first to it.second.front },
            now,
        )
        if (batch.isEmpty()) return 0
        if (!WordPretranslate.canFetch(isOnline, lastFailAt, now)) return 0
        val (ok, failed) = WordPretranslate.fetchAll(batch) { w -> deepl.translate(w, "zh") }
        if (failed) { lastFailAt = now; return 0 }
        for ((k, v) in ok) m[k] = WordPretranslate.Entry(k, v, now)
        save(m)
        return ok.size
    }

    /**
     * 导入词库后一次性把待弹的词都预翻译掉（后台线程调，懒加载的 [prefetch] 仍在弹卡时兜底）。
     * 循环调 [prefetch] 直到没有词可翻、某批失败（已记冷却）或跑满 [maxBatches] 批（额度保险丝）。
     * 每批各自 load/save 一遍，缓存落盘即时生效；不在内存里攒多份，避免与 [prefetch] 的写入互相覆盖。
     * 返回本次总共写入缓存的条数。
     */
    fun prefetchAll(
        cards: List<Pair<String, Words.Card>>,
        deepl: DeepL,
        isOnline: Boolean,
        now: Long,
        maxBatches: Int = WordPretranslate.PREFETCH_ALL_MAX_BATCHES,
    ): Int = WordPretranslate.runBatches(maxBatches) { prefetch(cards, deepl, isOnline, now) }
}

/**
 * 预翻译 / 卡片补翻前的联网判断（v1.78 从 MainActivity 与 OrbService 各一份的重复代码提出来）：
 *  - 系统说没有活动网络 → 真没网，不发请求；
 *  - 有活动网络 → 看它有没有 INTERNET 能力；
 *  - 拿不到（缺 ACCESS_NETWORK_STATE 权限 / 系统抛异常）→ **当有网**，照发一次。
 * 最后这条是刻意的：判断不了不该把整个预翻译悄悄关掉。v1.78 之前清单里就缺 ACCESS_NETWORK_STATE，
 * activeNetwork 每台机器上都抛 SecurityException、被 catch 吞成「没网」，预翻译因此一次请求都没发出去过。
 * 真发失败也没事：最后落到 [WordPretranslate.fetchAll] 的静默降级。
 */
fun Context.isOnlineForFetch(): Boolean {
    return try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val net = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
    } catch (_: Exception) { true }
}
