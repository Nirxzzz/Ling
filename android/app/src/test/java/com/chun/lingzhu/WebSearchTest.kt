package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 联网搜索：请求体/地址形状、两个 Provider 的解析（正常/缺字段/畸形）、去重截断 */
class WebSearchTest {
    private lateinit var server: MiniHttp
    @Volatile private var resp: Pair<Int, String> = 200 to "{}"
    @Volatile private var seen: Pair<String, String> = "/" to ""      // path, body
    @Volatile private var auth: String? = null

    @Before fun up() {
        server = MiniHttp { path, body ->
            seen = path to body
            resp
        }
    }

    @After fun down() = server.stop()

    private fun base() = "http://127.0.0.1:${server.port}"

    // ---------------- 请求体 / 地址 ----------------

    @Test fun tavilyBodyShape() {
        val b = JSONObject(WebSearch.tavilyBody("北京天气", 5))
        assertEquals("北京天气", b.getString("query"))
        assertEquals(5, b.getInt("max_results"))
        assertEquals("basic", b.getString("search_depth"))
        assertTrue("不要答案", !b.getBoolean("include_answer"))
        assertTrue("不要图片", !b.getBoolean("include_images"))
        assertTrue("Key 不落在 body 里", !b.has("api_key"))
        assertEquals("advanced", JSONObject(WebSearch.tavilyBody("x", 3, "advanced")).getString("search_depth"))
        assertEquals("深度乱写退回 basic", "basic", JSONObject(WebSearch.tavilyBody("x", 3, "胡说")).getString("search_depth"))
        assertEquals("条数下限 1", 1, JSONObject(WebSearch.tavilyBody("x", 0)).getInt("max_results"))
        assertEquals("条数上限 20", 20, JSONObject(WebSearch.tavilyBody("x", 99)).getInt("max_results"))
    }

    @Test fun searxngUrlShape() {
        assertEquals("http://x/search?format=json&q=%E5%8C%97%E4%BA%AC", WebSearch.searxngUrl("http://x", "北京"))
        assertEquals("https://s.io/search?format=json&q=a%2Bb", WebSearch.searxngUrl("https://s.io", "a+b"))
        assertEquals("结尾斜杠也认", "https://s.io/search?format=json&q=x", WebSearch.searxngUrl("https://s.io/", "x"))
        // 用户把完整地址粘进设置里了
        assertEquals("https://s.io/search?format=json&q=x", WebSearch.searxngUrl("https://s.io/search", "x"))
        assertEquals("带语言", "https://s.io/search?format=json&q=x&language=zh-CN", WebSearch.searxngUrl("https://s.io", "x", "zh-CN"))
    }

    // ---------------- Tavily 解析 ----------------

    @Test fun tavilyParsesFiveResults() {
        val body = JSONObject().put("results", JSONArray().apply {
            for (i in 1..7) put(JSONObject().put("title", "标题$i").put("content", "摘要$i").put("url", "https://a.com/$i"))
        }).toString()
        val hits = WebSearch.parseTavily(body)
        assertEquals(5, hits.size)                                   // 只取前 5 条
        assertEquals("标题1", hits[0].title)
        assertEquals("摘要1", hits[0].snippet)
        assertEquals("https://a.com/1", hits[0].url)
        assertEquals(3, WebSearch.parseTavily(body, 3).size)        // limit 生效
    }

    @Test fun tavilyToleratesMissingFields() {
        // content 缺失（老版本叫 snippet）、title 缺失、url 非 http、数组里混了字符串
        val body = """{"results":[
            {"snippet":"只有 snippet","url":"https://a.com/1"},
            {"title":"只有标题","content":"有摘要","url":"https://a.com/2"},
            {"title":"没有网址","content":"正文"},
            {"title":"危险协议","content":"x","url":"javascript:alert(1)"},
            "我不是对象",
            {"title":"","content":"","url":"https://a.com/empty"}
        ]}"""
        val hits = WebSearch.parseTavily(body)
        assertEquals(2, hits.size)                                  // 没网址 / 非 http / 混串 / 全空 → 都不收
        assertEquals("只有 snippet", hits[0].snippet)
        assertEquals("", hits[0].title)
        assertEquals("有摘要", hits[1].snippet)
        assertEquals(2, hits.size)                                  // 两行没进表
        assertEquals(listOf("https://a.com/1", "https://a.com/2"), hits.map { it.url })
    }

    @Test fun malformedNeverThrows() {
        assertTrue(WebSearch.parseTavily(null).isEmpty())
        assertTrue(WebSearch.parseTavily("").isEmpty())
        assertTrue(WebSearch.parseTavily("   ").isEmpty())
        assertTrue(WebSearch.parseTavily("not json at all").isEmpty())          // 纯文本
        assertTrue(WebSearch.parseTavily("<html>403</html>").isEmpty())         // 拿到 HTML
        assertTrue(WebSearch.parseTavily("""{"results":"not an array"}""").isEmpty())
        assertTrue(WebSearch.parseTavily("""{"results":[1,2,3]}""").isEmpty())  // 元素不是对象
        assertTrue(WebSearch.parseTavily("""{"results":{}}""").isEmpty())
        assertTrue(WebSearch.parseSearxng("garbage").isEmpty())
        // 顶层直接是数组（有的实例忽略 format）也认
        assertEquals(1, WebSearch.parseSearxng("""[{"title":"t","content":"c","url":"https://a.com"}]""").size)
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
        assertEquals("https://a.com/3", hits[2].url)                // 末尾标点剥掉
    }

    @Test fun snippetIsTruncated() {
        val long = "字".repeat(500)
        val body = JSONObject().put("results", JSONArray().put(JSONObject().put("title", "t").put("content", long).put("url", "https://a.com/1"))).toString()
        val h = WebSearch.parseTavily(body)[0]
        assertEquals(WebSearch.SNIPPET_MAX + 1, h.snippet.length)   // 截断 + 省略号
        assertTrue(h.snippet.endsWith("…"))
        assertEquals(500, WebSearch.clean(long, 5000).length)   // 上限内的原样返回
    }

    // ---------------- SearXNG 解析 ----------------

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

    @Test fun tavilyRealRoundTrip() {
        resp = 200 to JSONObject().put("results", JSONArray()
            .put(JSONObject().put("title", "北京天气").put("content", "晴，25℃").put("url", "https://weather.example/beijing"))
        ).toString()
        val p = Tavily("tvly-test", base())
        val hits = p.search("北京天气")
        assertEquals(1, hits.size)
        assertEquals("北京天气", hits[0].title)
        assertEquals("/search", seen.first)                          // 打到了 /search
        assertEquals("POST", "POST")
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
        assertEquals("", seen.second)                                // GET 没有请求体
        assertEquals(WebSearch.SEARXNG, p.id)
    }

    @Test fun blankQuerySkipsNetwork() {
        assertTrue(Tavily("k", base()).search("   ").isEmpty())
        assertTrue(Searxng(base()).search("").isEmpty())
        assertEquals("/", seen.first)                               // 一次都没发
    }

    @Test fun httpErrorBecomesChinese() {
        resp = 401 to """{"error":{"message":"Unauthorized"}}"""
        try {
            Tavily("bad-key", base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("401"))
            assertTrue(e.message!!, e.message!!.contains("Key 不对"))
            assertEquals("401 重试没用", false, e.retryable)
        }
        assertNotNull(WebSearch.LABEL[WebSearch.TAVILY])
    }

    @Test fun rateLimitIsRetryable() {
        resp = 429 to "{}"
        try {
            Searxng(base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("太频繁"))
            assertEquals("429 值得重试", true, e.retryable)
        }
    }

    @Test fun providerFactoryAndConfigured() {
        assertEquals(WebSearch.TAVILY, WebSearch.provider(WebSearch.TAVILY, "k")!!.id)
        assertEquals(WebSearch.SEARXNG, WebSearch.provider(WebSearch.SEARXNG, "", "https://s.io")!!.id)
        assertTrue("没 Key 的 Tavily 不给建", WebSearch.provider(WebSearch.TAVILY, "") == null)
        assertTrue("没地址的 SearXNG 不给建", WebSearch.provider(WebSearch.SEARXNG, "", "") == null)
        assertTrue("没 Key 不算配好", !WebSearch.configured(WebSearch.TAVILY, ""))
        assertTrue("有 Key 算配好", WebSearch.configured(WebSearch.TAVILY, "k"))
        assertTrue("地址得带协议", !WebSearch.configured(WebSearch.SEARXNG, "", "s.io"))
        assertTrue(WebSearch.configured(WebSearch.SEARXNG, "", "https://s.io"))
        assertEquals("", WebSearch.missing(WebSearch.TAVILY, "k"))
        assertTrue(WebSearch.missing(WebSearch.TAVILY, "").contains("Tavily API Key"))
        assertTrue(WebSearch.missing(WebSearch.SEARXNG, "").contains("SearXNG 地址"))
        assertTrue(WebSearch.statusLine(false, WebSearch.TAVILY).startsWith("关"))
        assertTrue(WebSearch.statusLine(true, WebSearch.TAVILY, "k").contains("Tavily"))
    }

    @Test fun searxngHttpErrorText() {
        resp = 404 to "Not Found"
        try {
            Searxng(base()).search("x"); throw AssertionError("应该抛")
        } catch (e: WebSearch.SearchException) {
            assertTrue(e.message!!, e.message!!.contains("地址不对"))
        }
    }
    // ---------------- P2-6/7：用户地址体检 ----------------

    @Test fun userUrlIssueRejectsBadSchemesAndMetadata() {
        assertTrue(WebSearch.userUrlIssue("ftp://x")!!.contains("http"))
        assertTrue(WebSearch.userUrlIssue("api.tavily.com")!!.contains("http"))
        assertTrue(WebSearch.userUrlIssue("http://169.254.169.254/latest")!!.contains("169.254"))
    }

    @Test fun userUrlIssueAllowsLanLoopbackAndPublic() {
        assertEquals(null, WebSearch.userUrlIssue("http://192.168.1.10:8080/search"))   // 局域网自建合法
        assertEquals(null, WebSearch.userUrlIssue("http://127.0.0.1:8080"))             // 回环放行（本地调试）
        assertEquals(null, WebSearch.userUrlIssue("https://searx.example.com"))
    }
}
