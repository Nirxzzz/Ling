package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 联网搜索：配置契约、请求体/地址形状、三个 Provider 的解析（正常/缺字段/畸形）、去重、URL 安全 */
class WebSearchTest {
    private lateinit var server: MiniHttp
    @Volatile private var resp: Pair<Int, String> = 200 to "{}"
    @Volatile private var seen: Pair<String, String> = "/" to ""      // path, body

    @Before fun up() {
        server = MiniHttp { path, body ->
            seen = path to body
            resp
        }
    }

    @After fun down() = server.stop()

    private fun base() = "http://127.0.0.1:${server.port}"

    // ---------------- 配置契约 ----------------

    @Test fun configDefaultsFollowSharedContract() {
        val c = WebSearch.Config()
        assertFalse("默认关", c.enabled)
        assertEquals(WebSearch.DEEPSEEK, c.provider)
        assertEquals("", c.deepseekKey)
        assertEquals("", c.tavilyKey)
        assertFalse("增强默认关", c.enhance)
        assertEquals("", c.searxngBase)
        assertEquals("basic", c.depth)
        assertEquals(WebSearch.DEEPSEEK, WebSearch.Config(provider = "胡说").providerId)
        assertEquals("advanced", WebSearch.Config(depth = "advanced").tavilyDepth)
        assertEquals("basic", WebSearch.Config(depth = "胡说").tavilyDepth)
    }

    @Test fun configToStringNeverPrintsKeys() {
        val s = WebSearch.Config(deepseekKey = "sk-deep-secret", tavilyKey = "tvly-secret").toString()
        assertFalse(s, s.contains("sk-deep-secret"))
        assertFalse(s, s.contains("tvly-secret"))
        assertTrue(s, s.contains("deepseekKey=set"))
        assertTrue(s, s.contains("tavilyKey=set"))
        assertTrue(WebSearch.Config().toString().contains("deepseekKey=none"))
    }

    @Test fun configFingerprintIsDeterministicAndSecretFree() {
        val a = WebSearch.Config(enabled = true, deepseekKey = "sk-secret", tavilyKey = "tvly-secret")
        val b = WebSearch.Config(enabled = true, deepseekKey = "sk-secret", tavilyKey = "tvly-secret")
        assertEquals(a, b)
        assertEquals(WebSearch.configFingerprint(a), WebSearch.configFingerprint(b))
        val fp = WebSearch.configFingerprint(a)
        assertFalse("指纹里不能有明文 Key", fp.contains("sk-secret") || fp.contains("tvly-secret"))

        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(enabled = false)))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(provider = WebSearch.TAVILY)))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(depth = "advanced")))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(enhance = true)))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(deepseekKey = "sk-other")))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(tavilyKey = "tvly-other")))
        assertNotEquals(fp, WebSearch.configFingerprint(a.copy(searxngBase = "https://s.io")))
    }

    @Test fun officialDeepSeekKeyReuseRequiresExactHttpsHost() {
        val c = WebSearch.Config(provider = WebSearch.DEEPSEEK)
        assertEquals("sk-model", WebSearch.deepseekKey(c, "https://api.deepseek.com/v1", "sk-model"))
        assertEquals("sk-model", WebSearch.deepseekKey(c, "https://api.deepseek.com", "sk-model"))
        assertEquals("", WebSearch.deepseekKey(c, "http://api.deepseek.com/v1", "sk-model"))
        assertEquals("", WebSearch.deepseekKey(c, "https://api.deepseek.com.evil.com/v1", "sk-model"))
        assertEquals("", WebSearch.deepseekKey(c, "https://evil.com/api.deepseek.com", "sk-model"))
        assertEquals("", WebSearch.deepseekKey(c, "https://api.deepseek.com:8443/v1", "sk-model"))
        assertEquals("", WebSearch.deepseekKey(c, "https://user:pass@api.deepseek.com/v1", "sk-model"))
        assertEquals("sk-explicit", WebSearch.deepseekKey(c.copy(deepseekKey = "sk-explicit"), "https://example.com/v1", "sk-model"))
    }

    @Test fun usableAndStatusLine() {
        val tavily = WebSearch.Config(enabled = true, provider = WebSearch.TAVILY)
        assertFalse("没 Key 不算配好", WebSearch.usable(tavily, ApiConfig("https://x/v1", "k", "m")))
        assertTrue("有 Key 算配好", WebSearch.usable(tavily.copy(tavilyKey = "tk"), ApiConfig("https://x/v1", "k", "m")))
        assertTrue(WebSearch.missing(tavily, ApiConfig("https://x/v1", "k", "m")).contains("Tavily API Key"))

        val searxng = WebSearch.Config(enabled = true, provider = WebSearch.SEARXNG)
        assertFalse("地址得带协议", WebSearch.usable(searxng.copy(searxngBase = "s.io"), ApiConfig("https://x/v1", "k", "m")))
        assertTrue(WebSearch.usable(searxng.copy(searxngBase = "https://s.io"), ApiConfig("https://x/v1", "k", "m")))

        val deepseek = WebSearch.Config(enabled = true)
        assertFalse("非官方端点又不填 Key → 不可用", WebSearch.usable(deepseek, ApiConfig("https://x/v1", "k", "m")))
        assertTrue("官方端点可复用模型 Key", WebSearch.usable(deepseek, ApiConfig("https://api.deepseek.com/v1", "k", "m")))

        assertTrue(WebSearch.statusLine(WebSearch.Config(), ApiConfig("https://x/v1", "k", "m")).startsWith("关"))
        assertTrue(WebSearch.statusLine(tavily.copy(tavilyKey = "tk"), ApiConfig("https://x/v1", "k", "m")).contains("Tavily"))
    }

    // ---------------- 请求体 / 地址 ----------------

    @Test fun deepseekSearchBodyShape() {
        val b = JSONObject(WebSearch.deepseekSearchBody("北京天气", 5))
        assertEquals("北京天气", b.getString("query"))
        assertEquals(5, b.getInt("count"))
        assertTrue("要正文", b.getBoolean("need_content"))
        assertEquals(1, JSONObject(WebSearch.deepseekSearchBody("x", 0)).getInt("count"))
        assertEquals(50, JSONObject(WebSearch.deepseekSearchBody("x", 99)).getInt("count"))
        assertEquals("", JSONObject(WebSearch.deepseekSearchBody("   ")).getString("query"))
    }

    @Test fun queryIsBoundedByUnicodeCodePoints() {
        assertEquals("北京 天气", WebSearch.clipQuery("  北京\n 天气  "))
        val long = "字".repeat(200)
        val q = WebSearch.clipQuery(long)
        assertEquals(WebSearch.QUERY_MAX, q.codePointCount(0, q.length))
        assertEquals("", WebSearch.clipQuery(null))
    }

    @Test fun deepseekFetchBodyShape() {
        assertEquals("https://example.com/a", JSONObject(WebSearch.deepseekFetchBody(" https://example.com/a ")).getString("url"))
    }

    @Test fun tavilyBodyShape() {
        val b = JSONObject(WebSearch.tavilyBody("北京天气", 5))
        assertEquals("北京天气", b.getString("query"))
        assertEquals(5, b.getInt("max_results"))
        assertEquals("basic", b.getString("search_depth"))
        assertFalse("不要答案", b.getBoolean("include_answer"))
        assertFalse("不要图片", b.getBoolean("include_images"))
        assertFalse("默认不要正文", b.getBoolean("include_raw_content"))
        assertTrue("用户亲手开增强才要正文", JSONObject(WebSearch.tavilyBody("x", 5, "basic", true)).getBoolean("include_raw_content"))
        assertFalse("Key 不落在 body 里", b.has("api_key"))
        assertEquals("advanced", JSONObject(WebSearch.tavilyBody("x", 3, "advanced")).getString("search_depth"))
        assertEquals("深度乱写退回 basic", "basic", JSONObject(WebSearch.tavilyBody("x", 3, "胡说")).getString("search_depth"))
        assertEquals("条数下限 1", 1, JSONObject(WebSearch.tavilyBody("x", 0)).getInt("max_results"))
        assertEquals("条数上限 20", 20, JSONObject(WebSearch.tavilyBody("x", 99)).getInt("max_results"))
    }

    @Test fun searxngUrlShape() {
        assertEquals("http://x/search?format=json&q=%E5%8C%97%E4%BA%AC", WebSearch.searxngUrl("http://x", "北京"))
        assertEquals("https://s.io/search?format=json&q=a%2Bb", WebSearch.searxngUrl("https://s.io", "a+b"))
        assertEquals("结尾斜杠也认", "https://s.io/search?format=json&q=x", WebSearch.searxngUrl("https://s.io/", "x"))
        assertEquals("https://s.io/search?format=json&q=x", WebSearch.searxngUrl("https://s.io/search", "x"))
        assertEquals("带语言", "https://s.io/search?format=json&q=x&language=zh-CN", WebSearch.searxngUrl("https://s.io", "x", "zh-CN"))
    }

    // ---------------- 官方 Search / Fetch 解析 ----------------

    @Test fun deepseekParsesSummaryContentAndDedupes() {
        val body = """{"results":[
            {"title":"标题1","url":"https://a.com/1","summary":"长摘要1","snippet":"短1","content":"正文1"},
            {"title":"重复","url":"https://a.com/1","summary":"重复摘要"},
            {"title":"标题2","url":"https://a.com/2","snippet":"只有短摘要","content":""},
            {"title":"危险协议","url":"javascript:alert(1)","summary":"x"},
            {"title":"","url":"https://a.com/3","summary":"","snippet":"","content":""}
        ]}"""
        val hits = WebSearch.parseDeepSeek(body)
        assertEquals(2, hits.size)
        assertEquals("长摘要1", hits[0].snippet)
        assertEquals("正文1", hits[0].content)
        assertEquals("只有短摘要", hits[1].snippet)
        assertEquals(listOf("https://a.com/1", "https://a.com/2"), hits.map { it.url })
    }

    /**
     * v2.2.1：DeepSeek 的坏响应必须是**协议失败**，不能伪装成「没搜到」——
     * 网关 200 返回登录页 / HTML 时，用户以前看到的是「没有找到结果」，是假话。
     * 只有合法的 `results: []` 才算没结果。
     */
    @Test fun deepseekProtocolFailuresAreNotNoResults() {
        val bad = listOf(
            null, "", "not json", "<html>403</html>",
            """{"results":"not an array"}""", """{"results":{}}""", "{}",
        )
        for (body in bad) {
            try {
                WebSearch.parseDeepSeek(body)
                throw AssertionError("畸形响应不能当没结果：$body")
            } catch (e: WebSearch.SearchException) {
                assertTrue(e.message!!, e.message!!.isNotBlank())
            }
        }
        // 合法但空的结果照旧是「没搜到」，不是失败
        assertTrue(WebSearch.parseDeepSeek("""{"results":[]}""").isEmpty())
        // 元素不成形（缺 url / 全空）仍然只是被跳过，不算协议失败
        assertTrue(WebSearch.parseDeepSeek("""{"results":[1,2,3]}""").isEmpty())
    }

    @Test fun fetchParsesTitleAndContent() {
        val p = WebSearch.parseFetch("""{"title":"示例","content":"# 标题\n\n正文"}""")
        assertEquals("示例", p.title)
        assertEquals("# 标题 正文", p.content)
        assertEquals("", WebSearch.parseFetch("坏").content)
        assertEquals("", WebSearch.parseFetch(null).title)
    }

    // ---------------- Tavily / SearXNG 解析（回归）----------------

    @Test fun tavilyParsesFiveResults() {
        val body = JSONObject().put("results", JSONArray().apply {
            for (i in 1..7) put(JSONObject().put("title", "标题$i").put("content", "摘要$i").put("url", "https://a.com/$i"))
        }).toString()
        val hits = WebSearch.parseTavily(body)
        assertEquals(5, hits.size)
        assertEquals("标题1", hits[0].title)
        assertEquals("摘要1", hits[0].snippet)
        assertEquals("https://a.com/1", hits[0].url)
        assertEquals(3, WebSearch.parseTavily(body, 3).size)
    }

    @Test fun tavilyToleratesMissingFields() {
        val body = """{"results":[
            {"snippet":"只有 snippet","url":"https://a.com/1"},
            {"title":"只有标题","content":"有摘要","url":"https://a.com/2"},
            {"title":"没有网址","content":"正文"},
            {"title":"危险协议","content":"x","url":"javascript:alert(1)"},
            "我不是对象",
            {"title":"","content":"","url":"https://a.com/empty"}
        ]}"""
        val hits = WebSearch.parseTavily(body)
        assertEquals(2, hits.size)
        assertEquals("只有 snippet", hits[0].snippet)
        assertEquals("", hits[0].title)
        assertEquals("有摘要", hits[1].snippet)
        assertEquals(listOf("https://a.com/1", "https://a.com/2"), hits.map { it.url })
    }

    /** Tavily 同口径（v2.2.1）：协议失败抛异常，合法空 results 才是「没搜到」。 */
    @Test fun tavilyProtocolFailuresAreNotNoResults() {
        val bad = listOf(
            null, "", "   ", "not json at all", "<html>403</html>",
            """{"results":"not an array"}""", "{}",
        )
        for (body in bad) {
            try {
                WebSearch.parseTavily(body)
                throw AssertionError("畸形响应不能当没结果：$body")
            } catch (e: WebSearch.SearchException) {
                assertTrue(e.message!!, e.message!!.isNotBlank())
            }
        }
        assertTrue(WebSearch.parseTavily("""{"results":[]}""").isEmpty())
        assertTrue(WebSearch.parseTavily("""{"results":[1,2,3]}""").isEmpty())
    }

    /** SearXNG：协议失败（非 JSON / HTML / 缺 results / results 不是数组）不能伪装成「没结果」。 */
    @Test fun searxngProtocolFailuresAreNotNoResults() {
        val bad = listOf(
            "", "   ", "garbage", "<html>403</html>",
            "{}", """{"query":"x"}""", """{"results":"not an array"}""", """{"results":{}}""",
            """[{"title":"t","content":"c","url":"https://a.com"}]""",
        )
        for (body in bad) {
            try {
                WebSearch.parseSearxng(body)
                throw AssertionError("畸形响应不能当没结果：$body")
            } catch (e: WebSearch.SearchException) {
                assertTrue(e.message!!, e.message!!.isNotBlank())
            }
        }
        assertEquals("合法空 results 才是没结果", 0, WebSearch.parseSearxng("""{"query":"x","results":[]}""").size)
    }

    @Test fun dedupesAndCleans() {
        val body = """{"results":[
            {"title":"A","content":"x","url":"https://a.com/1"},
            {"title":"A 重复","content":"y","url":"https://a.com/1"},
            {"title":"B","content":"带   换行\n和  连续空格","url":"https://a.com/2"},
            {"title":"C","content":"尾巴带句号","url":"https://a.com/3。"}
        ]}"""
        val hits = WebSearch.parseTavily(body)
        assertEquals(3, hits.size)
        assertEquals("带 换行 和 连续空格", hits[1].snippet)
        assertEquals("https://a.com/3", hits[2].url)
    }

    @Test fun snippetIsTruncated() {
        val long = "字".repeat(500)
        val body = JSONObject().put("results", JSONArray().put(JSONObject().put("title", "t").put("content", long).put("url", "https://a.com/1"))).toString()
        val h = WebSearch.parseTavily(body)[0]
        assertEquals(WebSearch.SNIPPET_MAX + 1, h.snippet.length)
        assertTrue(h.snippet.endsWith("…"))
        assertEquals(500, WebSearch.clean(long, 5000).length)
    }

    @Test fun tavilyParsesRawContentWhenEnhanced() {
        val raw = "正文".repeat(1200)
        val body = JSONObject().put("results", JSONArray().put(JSONObject()
            .put("title", "t").put("content", "摘要").put("raw_content", raw).put("url", "https://a.com/1"))).toString()
        val h = WebSearch.parseTavily(body)[0]
        assertEquals("摘要", h.snippet)
        assertEquals(WebSearch.CONTENT_MAX + 1, h.content.length)
        assertTrue(h.content.endsWith("…"))
    }

    @Test fun searxngParsesLikeTavily() {
        val body = """{"query":"test","results":[
            {"title":"标题","content":"摘要","url":"https://b.com/1","engine":"bing","score":1.5}
        ]}"""
        val hits = WebSearch.parseSearxng(body)
        assertEquals(1, hits.size)
        assertEquals("标题", hits[0].title)
        assertEquals("https://b.com/1", hits[0].url)
    }

    // ---------------- 真实链路（本地假服务器跑完整 HTTP）----------------

    @Test fun deepseekSearchRoundTrip() {
        resp = 200 to """{"results":[{"title":"结果","url":"https://s.io/1","summary":"长摘要","content":"正文"}]}"""
        val p = DeepSeek("sk-test", base() + "/v1/tools/search")
        val hits = p.search("安卓 联网", WebSearch.MAX_RESULTS, null)
        assertEquals(1, hits.size)
        assertEquals("长摘要", hits[0].snippet)
        assertEquals("正文", hits[0].content)
        assertEquals("/v1/tools/search", seen.first)
        val sent = JSONObject(seen.second)
        assertEquals("安卓 联网", sent.getString("query"))
        assertEquals(5, sent.getInt("count"))
        assertTrue(sent.getBoolean("need_content"))
        assertEquals(WebSearch.DEEPSEEK, p.id)
    }

    @Test fun deepseekFetchRoundTrip() {
        resp = 200 to """{"title":"文章","content":"正文内容"}"""
        val p = DeepSeek("sk-test", base() + "/v1/tools/search", base() + "/v1/tools/fetch")
        val page = p.fetch("https://example.com/a")
        assertEquals("文章", page.title)
        assertEquals("正文内容", page.content)
        assertEquals("/v1/tools/fetch", seen.first)
        assertEquals("https://example.com/a", JSONObject(seen.second).getString("url"))
    }

    @Test fun tavilyRealRoundTrip() {
        resp = 200 to JSONObject().put("results", JSONArray()
            .put(JSONObject().put("title", "北京天气").put("content", "晴，25℃").put("url", "https://weather.example/beijing"))
        ).toString()
        val p = Tavily("tvly-test", base())
        val hits = p.search("北京天气")
        assertEquals(1, hits.size)
        assertEquals("北京天气", hits[0].title)
        assertEquals("/search", seen.first)
        val sent = JSONObject(seen.second)
        assertEquals("北京天气", sent.getString("query"))
        assertEquals(WebSearch.TAVILY, p.id)
    }

    @Test fun searxngRealRoundTrip() {
        resp = 200 to """{"results":[{"title":"结果","content":"摘要","url":"https://s.io/1"}]}"""
        val p = Searxng(base())
        val hits = p.search("安卓 联网")
        assertEquals(1, hits.size)
        assertEquals("https://s.io/1", hits[0].url)
        assertTrue("query 要 URL 编码", seen.first.contains("q=%E5%AE%89%E5%8D%93"))
        assertEquals("", seen.second)
        assertEquals(WebSearch.SEARXNG, p.id)
    }

    @Test fun blankQuerySkipsNetwork() {
        assertTrue(Tavily("k", base()).search("   ").isEmpty())
        assertTrue(Searxng(base()).search("").isEmpty())
        assertTrue(DeepSeek("k", base() + "/v1/tools/search").search("", WebSearch.MAX_RESULTS, null).isEmpty())
        assertEquals("/", seen.first)
    }

    @Test fun httpErrorsAreVisibleAndNotSwallowed() {
        resp = 401 to """{"error":{"message":"Unauthorized"}}"""
        try {
            Tavily("bad-key", base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("401"))
            assertTrue(e.message!!, e.message!!.contains("Key 不对"))
            assertEquals(false, e.retryable)
        }
        assertNotNull(WebSearch.LABEL[WebSearch.TAVILY])

        resp = 402 to """{"error":{"message":"Insufficient Balance"}}"""
        try {
            DeepSeek("k", base() + "/v1/tools/search").search("x", WebSearch.MAX_RESULTS, null); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("402"))
            assertTrue(e.message!!, e.message!!.contains("余额"))
        }

        resp = 403 to """{"error":{"message":"fetch blocked by policy"}}"""
        try {
            DeepSeek("k", base() + "/v1/tools/search").search("x", WebSearch.MAX_RESULTS, null); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("403"))
            assertTrue(e.message!!, e.message!!.contains("权限"))
        }
    }

    @Test fun rateLimitIsRetryable() {
        resp = 429 to "{}"
        try {
            Searxng(base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("太频繁"))
            assertEquals(true, e.retryable)
        }
    }

    @Test fun searxngHttpErrorText() {
        resp = 404 to "Not Found"
        try {
            Searxng(base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("地址不对"))
        }
    }

    // ---------------- 用户地址 / 抓取地址安全 ----------------

    @Test fun userUrlIssueRejectsBadSchemesMetadataAndCredentials() {
        assertTrue(WebSearch.userUrlIssue("ftp://x")!!.contains("http"))
        assertTrue(WebSearch.userUrlIssue("api.tavily.com")!!.contains("http"))
        assertTrue(WebSearch.userUrlIssue("http://169.254.169.254/latest")!!.contains("169.254"))
        assertTrue(WebSearch.userUrlIssue("https://user:pass@searx.example.com")!!.contains("用户名"))
    }

    @Test fun userUrlIssueAllowsLanLoopbackAndPublic() {
        assertNull(WebSearch.userUrlIssue("http://192.168.1.10:8080/search"))
        assertNull(WebSearch.userUrlIssue("http://127.0.0.1:8080"))
        assertNull(WebSearch.userUrlIssue("https://searx.example.com"))
    }

    @Test fun fetchRejectsPrivateAndCredentialUrls() {
        assertTrue(WebSearch.fetchUrlIssue("")!!.contains("空"))
        assertTrue(WebSearch.fetchUrlIssue("ftp://example.com/a")!!.contains("http"))
        assertTrue(WebSearch.fetchUrlIssue("https://user:pass@example.com/a")!!.contains("用户名"))
        assertTrue(WebSearch.fetchUrlIssue("http://localhost/a")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://127.0.0.1/a")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://10.0.0.1/a")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://192.168.1.1/a")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://172.16.0.1/a")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://169.254.169.254/latest")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://2130706433/a")!!.contains("公网"))
        assertNotNull(WebSearch.fetchUrlIssue("http://127.1/a"))
        assertNotNull(WebSearch.fetchUrlIssue("http://0177.0.0.1/a"))
        assertNotNull(WebSearch.fetchUrlIssue("http://1.2.3.4.5/a"))
        assertTrue(WebSearch.fetchUrlIssue("http://metadata.google.internal/computeMetadata/v1/")!!.contains("公网"))
        assertTrue(WebSearch.fetchUrlIssue("http://[::1]/a") != null)
        assertTrue(WebSearch.fetchUrlIssue("http://box.local/a")!!.contains("公网"))
        assertNull(WebSearch.fetchUrlIssue("https://example.com/article"))
        assertNull(WebSearch.fetchUrlIssue("https://www.example.com/article"))
        assertNull(WebSearch.fetchUrlIssue("http://93.184.216.34/a"))
        assertNull(WebSearch.fetchUrlIssue("http://8.8.8.8/a"))
    }

    // ---------------- v2.2.1：错误文案 / 重试 / 来源行 / 测试搜索 ----------------

    /**
     * 抓取失败不能再说成「地址不对，服务没开这个接口」。
     * 官方文档写明 Fetch 的 404 = 这个页面读不出内容，和 Search 的 404（接口路径不对）是两回事。
     */
    @Test fun fetch404TalksAboutThePageSearch404TalksAboutTheEndpoint() {
        val fetch = WebSearch.httpError(404, """{"error":{"message":"no readable content"}}""", "网页抓取")
        assertTrue(fetch.message!!, fetch.message!!.contains("网页") && fetch.message!!.contains("读不出"))
        assertFalse("抓取 404 不能说成地址不对", fetch.message!!.contains("地址不对"))
        val search = WebSearch.httpError(404, "{}", "搜索")
        assertTrue(search.message!!, search.message!!.contains("地址不对"))
        // 服务自己写的原因优先于我们猜的
        assertTrue(fetch.message!!.contains("no readable content"))
    }

    /** 408 / 413 / 429 / 5xx / Tavily 的 432·433 都要有可读文案，且该重试的标成 retryable。 */
    @Test fun statusCodeTextsCoverTimeoutTooLargeAndQuota() {
        assertTrue(WebSearch.httpError(408, "{}", "搜索").retryable)
        assertTrue(WebSearch.httpError(429, "{}", "搜索").retryable)
        assertEquals(false, WebSearch.httpError(413, "{}", "搜索").retryable)
        assertTrue(WebSearch.httpError(432, "{}", "搜索").message!!.contains("额度"))
        assertTrue(WebSearch.httpError(433, "{}", "搜索").message!!.contains("额度"))
        assertEquals(500, WebSearch.httpError(500, "{}", "搜索").status)
    }

    /** 5xx 自动重试一次：第一次失败、第二次成功 → 整体算成功（偶发限流不该直接算失败）。 */
    @Test fun serverErrorIsRetriedOnce() {
        var hits = 0
        server.stop()
        server = MiniHttp { _, _ ->
            hits++
            if (hits == 1) 500 to """{"error":{"message":"boom"}}""" else 200 to """{"ok":true}"""
        }
        val body = WebSearch.request("http://127.0.0.1:${server.port}/search", "POST", emptyMap(), "{}")
        assertTrue(body, body.contains("ok"))
        assertEquals("失败一次后要重试一次", 2, hits)
    }

    /** 401 这类重试也没用的错误只发一次。 */
    @Test fun authErrorIsNotRetried() {
        var hits = 0
        server.stop()
        server = MiniHttp { _, _ -> hits++; 401 to """{"error":{"message":"bad key"}}""" }
        try {
            WebSearch.request("http://127.0.0.1:${server.port}/search", "POST", emptyMap(), "{}")
            throw AssertionError("401 必须报错")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("Key"))
        }
        assertEquals("鉴权错不重试", 1, hits)
    }

    /** 来源行只列本轮真正返回过的网址，没搜过就一个字都不加。 */
    @Test fun sourcesLineListsOnlyReturnedUrls() {
        val api = ApiConfig(base(), "sk-model", "deepseek-chat", "high",
            WebSearch.Config(enabled = true, provider = WebSearch.DEEPSEEK, deepseekKey = "sk-search"))
        val s = WebSearch.session(
            api.searchConfig, api,
            searchEndpoint = base() + "/v1/tools/search",
            fetchEndpoint = base() + "/v1/tools/fetch",
        )
        assertEquals("没搜过就没有来源行", "", s.sourcesLine())

        resp = 200 to """{"results":[{"title":"甲","url":"https://a.example/1","summary":"摘要一"},{"title":"乙","url":"https://b.example/2","summary":"摘要二"}]}"""
        val o = s.search("查一下")
        assertTrue(o is WebSearch.Outcome.Ok)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), s.sources())
        val line = s.sourcesLine()
        assertTrue(line, line.contains("已联网搜索"))
        assertTrue(line, line.contains("2 条结果"))
        assertTrue(line, line.contains("https://a.example/1"))
        assertFalse("不能声称已验证", line.contains("已核实"))
    }

    /** 设置页「测试搜索」三句话必须分开：搜到 / 通了但没结果 / 失败。 */
    @Test fun probeResultSeparatesOkEmptyFailed() {
        val ok = WebSearch.probeResult(WebSearch.Outcome.Ok("q", listOf(
            WebSearch.Hit("标题甲", "摘要", "https://a.example/1")), "DeepSeek 官方搜索"))
        assertTrue(ok, ok.startsWith("✓") && ok.contains("1 条") && ok.contains("标题甲"))
        val empty = WebSearch.probeResult(WebSearch.Outcome.Empty("q"))
        assertTrue(empty, empty.startsWith("✓") && empty.contains("没搜到"))
        val failed = WebSearch.probeResult(WebSearch.Outcome.Failed("q", "Key 不对或没权限"))
        assertTrue(failed, failed.startsWith("✗") && failed.contains("Key 不对"))
    }

    /**
     * 设置页「搜索 Key 能不能复用」必须和运行时同口径：
     * 判断的是实际请求会用的地址 / Key（开源副本没有内置接口，只认用户自己填的），不是用户输入框里的空串。
     */
    @Test fun reusableSearchKeyFollowsTheRuntimeBase() {
        // 官方地址 + 有 Key（用户自己填了官方地址和 Key 就是这个形状）→ 可复用
        assertTrue(ApiProfiles.reusableSearchKey("https://api.deepseek.com", "sk-preset"))
        // 用户留空（旧代码就是拿这个空串去判断，于是永远说「还差 Key」）→ 不可复用
        assertFalse(ApiProfiles.reusableSearchKey("", "sk-preset"))
        assertFalse(ApiProfiles.reusableSearchKey("https://api.deepseek.com", ""))
        // 第三方地址 → 绝不复用
        assertFalse(ApiProfiles.reusableSearchKey("https://api.example.com", "sk-preset"))
        // 官方域名但不是 HTTPS → 不复用
        assertFalse(ApiProfiles.reusableSearchKey("http://api.deepseek.com", "sk-preset"))
    }

    /** 用户边界：已经取消时不再发请求。 */
    @Test fun cancelledRequestSendsNothing() {
        val c = Cancel()
        c.abort()
        try {
            WebSearch.request(base() + "/search", "POST", emptyMap(), "{}", cancel = c)
            throw AssertionError("应该取消")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("取消"))
        }
        assertEquals("取消后不能发请求", "/", seen.first)
    }
}
