package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * 联网搜索（v1.68）：拿搜索结果当上下文喂给模型，回答里带来源链接。
 *
 * 分工照本项目的老规矩：
 *  - 纯逻辑在 [WebSearch]（请求体/地址拼装、解析、去重、缓存键、上下文块拼装）—— 纯 JVM，不碰 Android，单元测试直接测。
 *  - 发请求的只有两个薄壳 [Tavily] / [Searxng]，都实现 [WebSearch.Provider]；再发别的引擎就照这两个的样子加一个。
 *
 * 两个引擎的差别只在「要不要 Key、POST 还是 GET、JSON 长什么样」，结果统一收敛成 [WebSearch.Hit]（标题/摘要/网址），
 * 上层（触发、注入、缓存）完全不关心是谁搜的。
 *
 * **解析一律不抛** —— 联网是锦上添花，搜索源抽风、限流、改版换了字段都不能连带把正常回答搞崩：
 * 畸形响应、非 200、空结果统统当「没搜到」（返回空表），由调用方走降级提示。
 */
object WebSearch {

    // ---- Provider 常量（设置页单选、缓存键、模型 tool 定义都用这几个字符串）----
    const val TAVILY = "tavily"
    const val SEARXNG = "searxng"
    val PROVIDERS = listOf(TAVILY, SEARXNG)
    val LABEL = mapOf(
        TAVILY to "Tavily（官方云服务，要 API Key）",
        SEARXNG to "SearXNG（自建 / 公共实例，免 Key）",
    )
    /** 一次最多取几条 */
    const val MAX_RESULTS = 5
    /** 缓存有效期 6 小时（= [Cache.TTL_SEARCH_MS]，搜索是时效档）—— 「现在多少钱」这类问题半天内不会变，变了也该由用户自己再问一次 */
    const val TTL_MS = Cache.TTL_SEARCH_MS
    const val MAX_CACHE = 60
    const val TAVILY_URL = "https://api.tavily.com/search"
    /** 单条摘要最长留这么多字：太长的原文会把提示词撑爆，也会挤掉模型的思考预算 */
    const val SNIPPET_MAX = 300
    /** Tavily 的 search_depth：basic 便宜快，advanced 更准但慢一倍 */
    val TAVILY_DEPTHS = listOf("basic", "advanced")
    const val TAVILY_DEPTH_DEFAULT = "basic"

    /** 一条搜索结果。url 保证是 http(s)（别的协议不当来源，见 [okUrl]） */
    data class Hit(val title: String, val snippet: String, val url: String) {
        /** 拼进上下文块的一行：`1. [标题](网址)：摘要`（链接能被 Links 认出来、可点） */
        fun md(i: Int): String = "$i. [${title.ifBlank { url }}]($url)" + (if (snippet.isBlank()) "" else "：$snippet")
    }

    /** 搜索源。search 抛异常表示「这次搜不了」（网络/鉴权/超时），返回空表表示「搜到了但没结果」 */
    interface Provider {
        val id: String
        fun search(query: String, limit: Int = MAX_RESULTS): List<Hit>
    }

    class SearchException(message: String, retryable: Boolean = false) : ApiException(message, retryable)

    // ---------------- 地址 / 请求体（纯函数，测试直接断言形状）----------------

    /**
     * Tavily 请求体：**不放 api_key** —— Key 走 `Authorization: Bearer` 头（官方两种都认，头里放不落在日志/抓包里更安全）。
     * include_answer / include_images 关掉：只要那几条结果，答案和图片对喂给模型没用，白花钱。
     */
    fun tavilyBody(query: String, limit: Int = MAX_RESULTS, depth: String = TAVILY_DEPTH_DEFAULT): String =
        JSONObject()
            .put("query", query)
            .put("max_results", limit.coerceIn(1, 20))
            .put("search_depth", depth.takeIf { it in TAVILY_DEPTHS } ?: TAVILY_DEPTH_DEFAULT)
            .put("include_answer", false)
            .put("include_images", false)
            .toString()

    /** Tavily 地址（测试换成本地假服务器：provider(id=Tavily, endpoint="http://127.0.0.1:port")） */
    fun tavilyUrl(endpoint: String = TAVILY_URL): String {
        val b = endpoint.trim().trimEnd('/').ifEmpty { TAVILY_URL }
        return if (b.endsWith("/search")) b else "$b/search"
    }

    /** SearXNG 地址：`{base}/search?format=json&q=…`（免 Key；base 允许带不带结尾斜杠、带不带 /search） */
    fun searxngUrl(base: String, query: String, lang: String = ""): String {
        var b = base.trim().trimEnd('/')
        if (b.endsWith("/search")) b = b.dropLast("/search".length)   // 用户把完整地址粘进来了也认
        val l = if (lang.isBlank()) "" else "&language=" + enc(lang)
        return "$b/search?format=json&q=" + enc(query) + l
    }

    fun enc(s: String): String = try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { "" }


    // ---------------- 用户自填地址体检（P2-6/7）----------------

    /**
     * 校验用户填的搜索地址，返回错误信息（null = 通过）。
     * 口径：局域网自建实例（192.168./10./172.16-31.）是合法场景，放行；
     * 拦链路本地（169.254.，含云元数据 169.254.169.254）与非 http(s) 协议；回环与局域网放行（本地调试/自建都是合法场景）。
     */
    fun userUrlIssue(raw: String): String? {
        val u = raw.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return "地址要以 http:// 或 https:// 开头"
        val host = try { java.net.URI(u).host?.lowercase() } catch (_: Exception) { null } ?: return "地址里认不出主机名"
        if (host.startsWith("169.254.")) return "链路本地地址（169.254.*，含云元数据）不允许"   // 回环放行：本地假服务器/调试实例是合法场景
        return null
    }

    // ---------------- 解析（永不抛）----------------

    /**
     * Tavily：`{"results":[{"title":…,"content":…,"url":…}]}`。
     * content 偶尔叫 snippet（老版本 / 代理改写），两个都读；url 不合格、标题和摘要都空的条目丢掉。
     */
    fun parseTavily(body: String?, limit: Int = MAX_RESULTS): List<Hit> = parseResults(body, "results", "content", "snippet", limit)

    /**
     * SearXNG：`{"results":[{"title":…,"content":…,"url":…}]}` —— 字段名和 Tavily 一样，分开写是为了以后两边分叉不用动调用方。
     * （真机验证：公共实例的 results 里就是 url/title/content，另带 engine/score 等字段，忽略即可。）
     */
    fun parseSearxng(body: String?, limit: Int = MAX_RESULTS): List<Hit> = parseResults(body, "results", "content", "snippet", limit)

    /** 两个引擎共用的解析：拿到数组 → 逐条挑字段 → 去重 → 截断到 limit */
    private fun parseResults(body: String?, arrKey: String, snipKey: String, snipAlt: String, limit: Int): List<Hit> {
        val txt = body?.trim().orEmpty()
        if (txt.isEmpty()) return emptyList()
        val arr: JSONArray = try {
            val o = JSONObject(txt)
            // 顶层不是对象、或 results 不是数组（有的实例直接返回数组）→ 空
            o.optJSONArray(arrKey) ?: return emptyList()
        } catch (e: Exception) {
            // 有实例在 format 被忽略时返回 HTML —— 这时按数组再试一次，失败就当没搜到
            return try {
                val a = JSONArray(txt)
                if (a.length() == 0) emptyList() else collect(a, snipKey, snipAlt, limit)
            } catch (e2: Exception) { emptyList() }
        }
        return collect(arr, snipKey, snipAlt, limit)
    }

    private fun collect(arr: JSONArray, snipKey: String, snipAlt: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>(minOf(arr.length(), limit))
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            if (out.size >= limit.coerceAtLeast(0)) break
            val o = arr.optJSONObject(i) ?: continue          // 数组里混了字符串 / null → 跳过
            val url = okUrl(o.optString("url"))
            if (url.isEmpty()) continue                       // 没有可用网址 → 给不了来源链接，不收
            if (!seen.add(url)) continue                      // 同一网址出现两次只留一条
            val title = clean(o.optString("title"), 120)
            val snippet = clean(o.optString(snipKey).ifBlank { o.optString(snipAlt) }, SNIPPET_MAX)
            if (title.isBlank() && snippet.isBlank()) continue // 只有网址的裸条目：没信息，不要
            out.add(Hit(title, snippet, url))
        }
        return out
    }

    /**
     * 只认 http(s)，再把末尾粘上的标点去掉（摘要里抄出来的网址常带着句号）。
     * 不用 [Links.trimUrl]：它只剥 ASCII 标点，这里的搜索结果里全角「。、）」同样得剥。
     */
    fun okUrl(u: String?): String {
        var t = u?.trim().orEmpty()
        if (t.isEmpty()) return ""
        val lower = t.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return ""
        while (t.isNotEmpty() && (t.last() in TRAILING_JUNK || (t.last() == ')' && t.count { it == '(' } < t.count { it == ')' }))) t = t.dropLast(1)
        return t
    }

    /** 网址末尾这些字符都是紧跟正文粘上来的，不算地址的一部分（全角标点也在内） */
    private const val TRAILING_JUNK = ".,;:!?'\"、。；！？）】》」』"

    /** 压掉空白（网页摘要常有换行和连续空格，塞进提示词会把版面撑乱），再截断加省略号 */
    fun clean(s: String?, max: Int): String {
        val t = s?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.length <= max) return t
        return t.take(max).trimEnd() + "…"
    }

    // ---------------- Provider 工厂 / 配置状态 ----------------

    /** 设置页单选 → Provider 实例；配不全（缺 Key / 缺地址）返回 null（上层静默降级，不弹错） */
    fun provider(id: String, key: String = "", base: String = "", depth: String = TAVILY_DEPTH_DEFAULT): Provider? = when (id) {
        TAVILY -> key.takeIf { it.isNotBlank() }?.let { Tavily(it, depth = depth) }
        SEARXNG -> base.takeIf { it.isNotBlank() }?.let { Searxng(it) }
        else -> null
    }

    /** 这个配置能不能真的发起搜索（设置页用来决定是否提示「还差什么」） */
    fun configured(id: String, key: String = "", base: String = ""): Boolean = when (id) {
        TAVILY -> key.isNotBlank()
        SEARXNG -> base.trim().startsWith("http://") || base.trim().startsWith("https://")
        else -> false
    }

    /** 设置页给人看的缺项提示；配好了返回空串 */
    fun missing(id: String, key: String = "", base: String = ""): String = when {
        !configured(id, key, base) && id == TAVILY -> "还差 Tavily API Key"
        !configured(id, key, base) && id == SEARXNG -> "还差 SearXNG 地址（要带 http:// 或 https://）"
        else -> ""
    }

    /** 设置页一行状态：开着的引擎叫什么、配了没有 */
    fun statusLine(enabled: Boolean, id: String, key: String = "", base: String = ""): String = when {
        !enabled -> "关（回答只靠模型自己知道的事）"
        !configured(id, key, base) -> "开着 · " + LABEL[id] + " · " + missing(id, key, base)
        else -> "开着 · " + LABEL[id]
    }

    // ---------------- 缓存键 ----------------

    /**
     * 缓存键 = 引擎 + query（query 去首尾空白、忽略大小写 —— 「北京天气」和「北京天气 」是同一次搜索）。
     * 用 [Cache.key] 走 sha256：键里含 query 明文的话，设置页/日志里容易漏出用户搜了什么。
     */
    fun cacheKey(provider: String, query: String): String = Cache.key("search", provider.trim(), query.trim().lowercase())

    // ---------------- 上下文块（喂给模型的那一段）----------------

    /** 提示词里对来源的要求：回答末尾必须带「来源：」行，且只能引这次搜到的网址 */
    const val SOURCE_RULE = "这些资料是刚从网上搜到的，可能已经过时，也可能和你看到的画面不一致 —— 以画面为准，资料只用来补充。\n" +
        "引用时在回答的最后单独起一行，写「来源：」，后面列出你真正用到的网址（照抄上面给的，不要自己编、不要改写）。一条都没用到就不写这一行。"

    const val HEAD = "【联网搜索结果】"

    /** 一次搜索的完整上下文块；没结果返回空串（不拼空标题进提示词） */
    fun contextBlock(query: String, hits: List<Hit>, providerLabel: String = ""): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append(HEAD).append("关于「").append(clean(query, 60)).append("」").append(if (providerLabel.isBlank()) "" else "（$providerLabel）").append("：\n")
        hits.forEachIndexed { i, h -> sb.append(h.md(i + 1)).append('\n') }
        sb.append('\n').append(SOURCE_RULE)
        return sb.toString()
    }

    /** 拼提示词：有搜索结果就插在模式提示词之后（离正文最近，约束最不容易被冲淡） */
    fun inject(prompt: String, query: String, hits: List<Hit>, providerLabel: String = ""): String {
        val block = contextBlock(query, hits, providerLabel)
        return if (block.isEmpty()) prompt else prompt + "\n\n" + block
    }

    // ---------------- 发请求的公共部分 ----------------

    /** HTTP 状态 → (大白话, 值不值得自动重试一次)。照 DeepL 的表：4xx 基本是配错了，重试没用 */
    private val ERRORS = mapOf(
        400 to ("请求有误（Key 或参数不对）" to false),
        401 to ("Key 不对或没权限" to false),
        403 to ("Key 不对或没权限" to false),
        404 to ("地址不对（Key 填错了，或服务没开这个接口）" to false),
        422 to ("请求不被接受（Key 过期或额度用完）" to false),
        429 to ("请求太频繁" to true),
        500 to ("搜索服务出错" to true),
        502 to ("搜索服务出错" to true),
        503 to ("搜索服务暂时不可用" to true),
        504 to ("搜索服务超时" to true),
    )

    /** 已知状态码给一句人话；没收录的就截一小段响应体，够排查了 */
    fun httpError(status: Int, body: String, who: String): SearchException {
        val e = ERRORS[status]
        return SearchException("$who 出错（$status）：" + (e?.first ?: body.take(120)), e?.second ?: (status >= 500))
    }

    /** 统一发一次请求、读回响应体（不解析）：非 2xx 抛 [SearchException] */
    fun request(url: String, method: String, headers: Map<String, String>, body: String?, timeoutMs: Int = 20_000): String {
        val c = try { URL(url).openConnection() as HttpURLConnection }
        catch (e: Exception) { throw SearchException("搜索地址格式不对：" + url.take(80)) }
        try {
            c.connectTimeout = 12_000
            c.readTimeout = timeoutMs
            c.requestMethod = method
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw httpError(code, txt, "搜索")
            return txt
        } catch (e: SearchException) {
            throw e
        } catch (e: IOException) {
            throw SearchException("连不上搜索服务（" + (e.message ?: e.javaClass.simpleName) + "）", retryable = true)
        } finally {
            c.disconnect()
        }
    }

    // ================= [WebSearch] 接进请求链路用的那几件（v1.68） =================
    // 上面是「怎么搜」，下面是「什么时候搜、搜完怎么喂回去」—— 纯函数 + 两个开关字段，一样不碰 Android。

    /** OpenAI tools 里的工具名。模型回调时 [com.chun.lingzhu.ToolCall.name] 必须和它一模一样才算数 */
    const val TOOL_NAME = "web_search"

    /** 「联网搜索」开关默认值：**默认关**。联网要把用户输的字和搜到的内容发到第三方，得多说一句由用户决定。 */
    const val ENABLED_DEFAULT = false

    /**
     * 运行时开关与引擎，由 Prefs 每次 [Prefs.api] 时同步（同 [CostPolicy.liteEnabled] 的做法：
     * 请求路径上只认这里，不回头读 SharedPreferences）。开关关 → 请求侧压根不带 tools。
     */
    @Volatile var enabled: Boolean = ENABLED_DEFAULT

    /** 配好的引擎；null = 缺 Key / 缺地址（这时开关开着也当没开，静默不联网） */
    @Volatile var engine: Provider? = null

    /** 现在能不能真的联网：开关开着 **且** 引擎配好了 */
    fun live(): Boolean = enabled && engine != null

    /**
     * OpenAI tools 格式（目前只给一个 function）：
     * `[{"type":"function","function":{"name":…,"description":…,"parameters":{…}}}]`
     */
    fun toolsJson(): String = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL_NAME)
        .put("description", "联网搜索。查实时信息用：最新版本、今天/现在的行情与新闻、价格、天气、汇率、赛程、人物近况。" +
            "考题、公式、概念翻译这类模型自己知道的别搜。搜不到就照自己知道的答，并说明是凭记忆作答，别编。")
        .put("parameters", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("query", JSONObject().put("type", "string")
                .put("description", "要搜的关键词，中文短语，一般 4~15 字")))
            .put("required", JSONArray().put("query")))).toString()

    // ---- 什么时候值得搜 ----

    /** 斜杠命令：`/s`、`/搜`、`/搜索` 开头（后面跟的就是搜索词，带冒号逗号都认） */
    private val SLASH_S = Regex("^\\s*/(?:s|搜|搜索)\\s*[：:，,、]?\\s*", RegexOption.IGNORE_CASE)

    /** 实时性关键词：命中就搜。这词表刻意保守 —— 宁可漏搜（模型照旧凭知识答），也别把纯题目拖进一次网络往返 */
    private val LIVE_WORDS = listOf(
        "最新", "最新版", "更新了", "现在", "目前", "当前", "今天", "今日", "今年", "最近", "近期", "刚刚",
        "新闻", "消息", "政策", "新规", "规定", "版本", "上市", "发售", "股价", "涨跌", "汇率", "天气",
        "多少钱", "价格", "报价", "收费", "房价", "机票", "赛程", "战报", "比分", "排名",
    )

    /** 输入超过这个字数就不做意图判定：一大段粘贴/长题干不是「问一句」，判了也容易误判 */
    const val MAX_INTENT_CHARS = 60

    /**
     * 这次输入要不要联网：斜杠命令永远算；否则输入够短、且命中实时性关键词才算。
     * 截图识别（[Solver.solve]）没有文字输入，判定不了 —— 那边只能靠开关，见报告的遗留项。
     */
    fun intentOf(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        if (SLASH_S.containsMatchIn(t)) return true
        if (t.length > MAX_INTENT_CHARS) return false
        return LIVE_WORDS.any { t.contains(it) }
    }

    /** 斜杠命令剥掉 —— 剩下的话才是给模型看的正文（模型看见个没定义的「/s」只会当成噪音） */
    fun stripTrigger(input: String): String = SLASH_S.replace(input.trim(), "").trim()

    /** 拿去搜的词：斜杠命令后面的话；没有命令就用整句（压空白、截短） */
    fun queryOf(input: String): String = clean(stripTrigger(input), 80)

    /** 没搜到时回填给 tool 角色的固定话术 —— 别回空串，模型会以为工具坏了然后开始编 */
    fun noHitsText(query: String): String =
        "（没搜到「${clean(query, 40)}」的结果。照你自己知道的回答，并说明这是凭记忆作答，不要编造来源。）"

    /**
     * 模型给的 arguments（`{"query":"…"}`）→ 搜索词。解析不了 / 不是对象 / 没 query → 回退到 [queryOf] 那句话本身，
     * 保证这一步永远拿得到一个非空的 query（空 query 的搜索是白跑一趟网络）。
     */
    fun queryFromArgs(args: String, fallback: String): String {
        val raw = try {
            val t = args.trim()
            if (t.isEmpty()) "" else JSONObject(t).optString("query", "")
        } catch (e: Exception) { "" }        // 有的模型把 arguments 写成带引号的散文，不是合法 JSON
        val q = raw.trim().ifBlank { fallback }
        return clean(q, 80)
    }

    /**
     * 搜索结果 → 回填给 tool 角色的正文。有结果就是 [contextBlock]（自带「来源：」规矩），
     * 没结果走 [noHitsText]。
     */
    fun toolResultText(query: String, hits: List<Hit>): String =
        contextBlock(query, hits, LABEL[engine?.id].orEmpty()).ifEmpty { noHitsText(query) }
}

/** Tavily：POST api.tavily.com/search，Key 走 `Authorization: Bearer`。endpoint 可换成别的网关 / 本地假服务器 */
class Tavily(private val key: String, private val endpoint: String = WebSearch.TAVILY_URL, private val depth: String = WebSearch.TAVILY_DEPTH_DEFAULT) : WebSearch.Provider {
    override val id = WebSearch.TAVILY

    override fun search(query: String, limit: Int): List<WebSearch.Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val txt = WebSearch.request(
            WebSearch.tavilyUrl(endpoint), "POST",
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer " + key.trim()),
            WebSearch.tavilyBody(q, limit, depth))
        return WebSearch.parseTavily(txt, limit)
    }
}

/** SearXNG：GET {base}/search?format=json&…，免 Key —— 自建或公共实例都行 */
class Searxng(private val base: String, private val lang: String = "") : WebSearch.Provider {
    override val id = WebSearch.SEARXNG

    override fun search(query: String, limit: Int): List<WebSearch.Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        WebSearch.userUrlIssue(base)?.let { throw WebSearch.SearchException(it) }   // P2-6/7：回环/元数据/协议不对，直接给明确错误
        val txt = WebSearch.request(WebSearch.searxngUrl(base, q, lang), "GET", emptyMap(), null)
        return WebSearch.parseSearxng(txt, limit)
    }
}