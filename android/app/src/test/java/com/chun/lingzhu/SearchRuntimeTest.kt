package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 搜索工具链的本地回归（无外网、无计费请求）：
 *  - DeepSeek 思考模式续轮必须回传 reasoning_content + 全部 assistant tool_calls；
 *  - 工具轮的「我先搜一下」不能拼进最终答案；
 *  - 搜索失败 / 没结果 / 取消 / 轮次上限都有可预期的收口；
 *  - Tavily 增强只在 key + enabled 同时满足时发生；
 *  - web_fetch 只能抓本轮搜索真正返回过的网址（自动补正文也一样）。
 */
class SearchRuntimeTest {
    private lateinit var server: MiniHttp
    private val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var script: (String, String) -> Resp = { _, _ -> Resp(404, "{}") }

    @Before fun up() {
        bodies.clear(); paths.clear()
        script = ::defaultScript
        server = MiniHttp { path, body ->
            paths += path
            val r = script(path, body)
            if (r.delayMs > 0) Thread.sleep(r.delayMs)
            r.code to r.body
        }
    }

    @After fun down() = server.stop()

    private fun defaultScript(path: String, body: String): Resp = when (path) {
        "/v1/chat/completions" -> {
            val b = JSONObject(body)
            bodies += b
            chatReply(bodies.size)
        }
        "/v1/tools/search" -> Resp(200,
            """{"results":[{"title":"示例","url":"https://example.com/a","summary":"摘要","content":"正文"}]}""")
        "/v1/tools/fetch" -> {
            val url = try { JSONObject(body).optString("url") } catch (_: Exception) { "" }
            if (url == "https://example.com/a") Resp(200, """{"title":"文章","content":"网页正文"}""")
            else Resp(403, """{"error":{"message":"not a returned search url"}}""")
        }
        "/tavily/search" -> Resp(200,
            """{"results":[{"title":"增强","url":"https://tavily.example/b","content":"${"长".repeat(200)}"}]}""")
        else -> Resp(404, "{}")
    }

    /** 第一轮：思考 + 前言 + 两个并行工具调用（搜索参数分两段到）；第二轮：最终答案。 */
    private fun chatReply(round: Int): Resp {
        if (round == 1) return Resp(200, sse(
            delta(reasoning = "需要先查"),
            delta(content = "我先查一下。"),
            toolChunk(0, "call_s", "web_search", "{\"query\":\"天"),
            toolChunk(0, "call_s", "web_search", "气\"}", finish = "tool_calls"),
            toolChunk(1, "call_f", "web_fetch", "{\"url\":\"https://example.com/a\"}"),
        ))
        return Resp(200, sse(
            delta(reasoning = "资料够了"),
            delta(content = "最终答案 42"),
        ))
    }

    private fun cfg(search: WebSearch.Config = WebSearch.Config(
        enabled = true, provider = WebSearch.DEEPSEEK, deepseekKey = "sk-search",
    )) = ApiConfig(fakeBase() + "/v1", "sk-model", "deepseek-chat", "high", search)

    private fun fakeBase() = "http://127.0.0.1:${server.port}"

    private fun session(api: ApiConfig = cfg(), tavily: String = fakeBase() + "/tavily") =
        WebSearch.session(
            api.searchConfig, api,
            searchEndpoint = fakeBase() + "/v1/tools/search",
            fetchEndpoint = fakeBase() + "/v1/tools/fetch",
            tavilyEndpoint = tavily,
        )

    private fun body(api: ApiConfig = cfg()) = Bodies.vision(api, "看题", listOf("QUJD"), 2000, false, withTools = true)

    // ---------------- 主链路 ----------------

    @Test fun toolContinuationPreservesReasoningAllCallsAndFinalOnly() {
        val api = cfg()
        val s = session(api)
        val rec = object : ApiClient.Listener {
            val text = StringBuilder()
            override fun onText(text: String) { this.text.append(text) }
        }
        var phase = ""
        val out = ApiClient(api).streamWithSession(
            body(api), rec, AtomicBoolean(false), session = s,
            onPhase = { phase = it },
        ).first

        assertEquals("最终答案 42", out)
        assertEquals("最终答案 42", rec.text.toString())
        assertFalse("工具轮前言不能进最终答案", out.contains("我先查一下"))
        assertFalse(rec.text.toString().contains("我先查一下"))
        assertEquals(1, s.searches)
        assertEquals(1, s.fetches)

        assertEquals(2, bodies.size)
        val second = bodies[1].getJSONArray("messages")
        assertEquals(4, second.length())
        val assistant = second.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("需要先查", assistant.getString("reasoning_content"))
        assertEquals("我先查一下。", assistant.getString("content"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("call_s", calls.getJSONObject(0).getString("id"))
        assertEquals("web_search", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("call_f", calls.getJSONObject(1).getString("id"))
        assertEquals("web_fetch", calls.getJSONObject(1).getJSONObject("function").getString("name"))
        assertEquals("tool", second.getJSONObject(2).getString("role"))
        assertEquals("call_s", second.getJSONObject(2).getString("tool_call_id"))
        assertTrue("分段的 arguments 要攒全", second.getJSONObject(2).getString("content").contains("天气"))
        assertEquals("call_f", second.getJSONObject(3).getString("tool_call_id"))
        assertTrue(second.getJSONObject(3).getString("content").contains("网页正文"))
        assertTrue("阶段里要能看到搜索", phase.contains("搜索") || phase.contains("网页"))
    }

    // ---------------- 思考模式 × tool_choice（用户实测 HTTP 400 的回归） ----------------

    /**
     * DeepSeek 思考模式 + Agent 首轮（真人聊天路径的默认组合）：以前首轮就发 `tool_choice:"required"`，
     * 接口直接 400 `Thinking mode does not support this tool_choice`。
     * 这里捕获真实请求体，验三件事：首轮是 auto、思考没被关、工具没被摘，且整轮能真的跑完工具并收尾。
     */
    @Test fun thinkingAgentModeFirstRoundIsAutoAndStillRunsTools() {
        val api = cfg()
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        val out = ApiClient(api).streamWithSession(
            body(api), rec, AtomicBoolean(false), session = session(api), agentMode = true,
        ).first

        assertEquals("最终答案 42", out)
        assertEquals("工具轮前言不能进最终答案", "最终答案 42", rec.text.toString())
        assertEquals("搜索工具要真的执行了", 1, paths.count { it == "/v1/tools/search" })
        assertEquals(2, bodies.size)

        val first = bodies[0]
        assertEquals("思考必须保持开启（不能靠关思考绕开）", "enabled", first.getJSONObject("thinking").getString("type"))
        assertTrue("工具不能被摘掉", first.getJSONArray("tools").length() > 0)
        assertEquals("思考模式下首轮只能是 auto", "auto", first.getString("tool_choice"))

        val second = bodies[1]
        assertEquals("续轮也要 auto", "auto", second.getString("tool_choice"))
        assertTrue("续轮工具还在", second.getJSONArray("tools").length() > 0)
        assertEquals("enabled", second.getJSONObject("thinking").getString("type"))
    }

    /** 不思考的路径保持原设计：Agent 首轮 required 逼模型真动手，续轮回到 auto，tools 全程在。 */
    @Test fun nonThinkingAgentFirstRoundRequiresThenReturnsToAuto() {
        val api = ApiConfig(fakeBase() + "/v1", "sk-model", "qwen-max", "high", cfg().searchConfig)
        val out = ApiClient(api).streamWithSession(
            body(api), object : ApiClient.Listener {
                override fun onText(text: String) {}
            }, AtomicBoolean(false), session = session(api), agentMode = true,
        ).first

        assertEquals("最终答案 42", out)
        assertEquals(2, bodies.size)
        assertFalse("非 deepseek 不带思考参数", bodies[0].has("thinking"))
        assertEquals("required", bodies[0].getString("tool_choice"))
        assertTrue(bodies[0].getJSONArray("tools").length() > 0)
        assertEquals("续轮必须回到 auto（以前会一直 required）", "auto", bodies[1].getString("tool_choice"))
        assertTrue("续轮工具还在", bodies[1].getJSONArray("tools").length() > 0)
    }

    @Test fun searchFailureGetsDistinctVisibleMemoryNote() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    if (bodies.size == 1) chatReply(1)
                    else Resp(200, sse(delta(content = "凭记忆答 7")))
                }
                "/v1/tools/search" -> Resp(500, """{"error":{"message":"boom"}}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        val out = ApiClient(api).streamWithSession(body(api), rec, AtomicBoolean(false), session = session(api)).first

        assertTrue(out.startsWith("凭记忆答 7"))
        assertTrue(out, out.contains("联网搜索失败"))
        assertTrue(out, out.contains("未核实"))
        assertFalse(out, out.contains("已核实"))
        assertTrue(out, out.contains("未使用联网结果"))
        assertTrue("最终说明也要显示出来", rec.text.toString().contains("联网搜索失败"))
        val toolMsg = bodies[1].getJSONArray("messages").getJSONObject(2).getString("content")
        assertTrue(toolMsg, toolMsg.contains("失败"))
    }

    @Test fun noResultsGetsDifferentNoteThanFailure() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    if (bodies.size == 1) chatReply(1) else Resp(200, sse(delta(content = "答案 X")))
                }
                "/v1/tools/search" -> Resp(200, """{"results":[]}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val out = ApiClient(api).streamWithSession(body(api), object : ApiClient.Listener {
            override fun onText(text: String) {}
        }, AtomicBoolean(false), session = session(api)).first

        assertTrue(out, out.contains("没有找到结果"))
        assertFalse(out, out.contains("联网搜索失败"))
    }

    @Test fun cancelStopsTheLoopAndDoesNotAnswerFromStaleTools() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b; chatReply(1)
                }
                "/v1/tools/search" -> Resp(200, """{"results":[]}""", delayMs = 2000)
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val cancel = Cancel()
        val done = AtomicBoolean(false)
        var result: String? = null
        val t = Thread {
            try {
                result = ApiClient(api).streamWithSession(body(api), object : ApiClient.Listener {
                    override fun onText(text: String) {}
                }, cancel, session = session(api)).first
            } finally { done.set(true) }
        }
        t.start()
        // 等第一个 chat 请求 + 搜索请求发出去
        val start = System.currentTimeMillis()
        while (paths.none { it == "/v1/tools/search" } && System.currentTimeMillis() - start < 2000) Thread.sleep(20)
        Thread.sleep(80)
        val abortAt = System.currentTimeMillis()
        cancel.abort()
        t.join(4000)
        assertTrue("必须真的返回", done.get())
        val elapsed = System.currentTimeMillis() - abortAt
        assertTrue("取消后要很快返回（实际 ${elapsed}ms）", elapsed < 1500)
        assertEquals("", result.orEmpty())
        assertEquals("取消后不能再发第三轮", 1, bodies.size)
    }

    @Test fun toolRoundLimitRaisesInsteadOfAnsweringWithPreamble() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    Resp(200, sse(
                        delta(content = "我先查一下。"),
                        toolChunk(0, "call_${bodies.size}", "web_search", "{\"query\":\"q\"}", finish = "tool_calls"),
                    ))
                }
                "/v1/tools/search" -> Resp(200, """{"results":[]}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        var error = ""
        try {
            ApiClient(api).streamWithSession(body(api), rec, AtomicBoolean(false), session = session(api))
            throw AssertionError("轮次上限后还在要工具，必须明确报错，不能拿前言当答案")
        } catch (e: ApiException) {
            error = e.message.orEmpty()
        }

        assertTrue(error, error.contains("上限"))
        assertEquals("4 轮工具 + 最后一次请求", 5, bodies.size)
        assertEquals("搜索点两次后不能再真搜", 2, paths.count { it == "/v1/tools/search" })
        assertFalse("带 tool_calls 轮的前言不能当最终答案", rec.text.toString().contains("我先查一下"))
    }

    /** 请求体接不上（解析失败）时同样不能拿带 tool_calls 轮的前言当最终答案。 */
    @Test fun unjoinableToolRoundRaisesInsteadOfAnsweringWithPreamble() {
        script = { path, _ ->
            when (path) {
                "/v1/chat/completions" -> Resp(200, sse(
                    delta(content = "我先查一下。"),
                    toolChunk(0, "call_1", "web_search", "{\"query\":\"q\"}", finish = "tool_calls"),
                ))
                "/v1/tools/search" -> Resp(200, """{"results":[]}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        var error = ""
        try {
            ApiClient(api).streamWithSession("不是 JSON 的请求体", rec, AtomicBoolean(false), session = session(api))
            throw AssertionError("请求体接不上时必须明确报错")
        } catch (e: ApiException) {
            error = e.message.orEmpty()
        }

        assertTrue(error, error.contains("请求体"))
        assertFalse("带 tool_calls 轮的前言不能当最终答案", rec.text.toString().contains("我先查一下"))
    }

    /** 用户边界：还没发就取消 → 不建请求、不吐正文。 */
    @Test fun alreadyCancelledStreamSendsNoRequest() {
        val api = cfg()
        val cancel = Cancel()
        cancel.abort()
        var emitted = false
        val out = ApiClient(api).streamWithSession(body(api), object : ApiClient.Listener {
            override fun onText(text: String) { emitted = true }
        }, cancel, session = session(api)).first

        assertEquals("", out)
        assertFalse("取消后不能吐正文", emitted)
        assertEquals("取消后不能发任何请求", 0, paths.size)
    }

    // ---------------- v2.2.1：流式外放 / 撤回 / 额度收口 ----------------

    /**
     * P1-1 回归：搜索开着时正文也必须**边生成边出**，不能再整段攒到最后（用户看到的「一直正在思考、
     * 然后一次性蹦出来」）。这一轮模型先吐一段较长的前言再调工具：前言必须先外放出去、
     * 确认是工具轮后再撤回（[Listener.onDiscard]），最终答案里不能带前言。
     */
    @Test fun longPreambleIsFlushedThenWithdrawn() {
        val preamble = "我先查一下这个问题的相关资料，稍等一下，马上就好，不会太久的。"
        assertTrue("测试前提：前言要超过外放阈值 ${ApiClient.ROUND_FLUSH_CHARS}", preamble.length > ApiClient.ROUND_FLUSH_CHARS)
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    if (bodies.size == 1) Resp(200, sse(
                        delta(reasoning = "需要先查"),
                        delta(content = preamble),
                        toolChunk(0, "call_s", "web_search", "{\"query\":\"天气\"}", finish = "tool_calls"),
                    )) else Resp(200, sse(delta(content = "最终答案 42")))
                }
                "/v1/tools/search" -> Resp(200, """{"results":[{"title":"示例","url":"https://example.com/a","summary":"摘要"}]}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val rec = object : ApiClient.Listener {
            val text = StringBuilder()
            var discards = 0
            override fun onText(text: String) { this.text.append(text) }
            // 界面收到撤回就把已经显示的前言清掉（AnswerPanel.rewind 做的是同一件事）
            override fun onDiscard() { discards++; text.setLength(0) }
        }
        val out = ApiClient(api).streamWithSession(body(api), rec, AtomicBoolean(false), session = session(api)).first

        assertEquals("最终答案 42", out)
        assertEquals("最终答案 42", rec.text.toString())
        assertFalse("撤回后前言不能留在屏幕上", rec.text.toString().contains("我先查一下"))
        assertEquals("确认是工具轮后要撤回一次", 1, rec.discards)
    }

    /**
     * P2-2：搜索 / 抓取额度用尽后，下一轮请求体里**不再带 tools**，让模型拿现有资料收尾。
     * 以前是继续挂 tools，模型再点一次就整题报错「联网工具调用次数已达上限」。
     */
    @Test fun exhaustedToolBudgetSendsLastRoundWithoutTools() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    if (bodies.size <= 4) Resp(200, sse(
                        delta(content = "我先查一下。"),
                        toolChunk(0, "call_${bodies.size}", "web_search", "{\"query\":\"q\"}", finish = "tool_calls"),
                    )) else Resp(200, sse(delta(content = "按已有资料收尾的答案")))
                }
                "/v1/tools/search" -> Resp(200, """{"results":[]}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        val out = ApiClient(api).streamWithSession(body(api), rec, AtomicBoolean(false), session = session(api)).first

        assertTrue(out, out.startsWith("按已有资料收尾的答案"))
        assertEquals("4 轮工具 + 1 轮收尾", 5, bodies.size)
        assertTrue("前 4 轮带工具", bodies[0].has("tools"))
        assertFalse("额度用尽的最后一轮不能再带 tools", bodies[4].has("tools"))
        assertFalse("前言不能当答案", rec.text.toString().contains("我先查一下"))
    }

    // ---------------- 抓取白名单 ----------------

    /** 任意公网网址不在本轮搜索结果里 → 直接拒绝，不发请求；真正返回过的来源照旧可抓。 */
    @Test fun fetchGatesToReturnedSearchUrls() {
        val api = cfg()
        val s = session(api)

        val denied = s.fetch("https://other.example/not-returned")
        assertTrue(denied is WebSearch.FetchOutcome.Failed)
        val failure = denied as WebSearch.FetchOutcome.Failed
        assertTrue(failure.message, failure.message.contains("搜索结果"))
        assertEquals("被拒的网址不能发出请求", 0, paths.count { it == "/v1/tools/fetch" })
        assertEquals("被拒的网址不占抓取次数", 0, s.fetches)

        val searched = s.search("q")
        assertTrue(searched is WebSearch.Outcome.Ok)
        val url = (searched as WebSearch.Outcome.Ok).hits.single().url
        assertEquals("https://example.com/a", url)
        val page = s.fetch(url)
        assertTrue(page is WebSearch.FetchOutcome.Ok)
        assertEquals("网页正文", (page as WebSearch.FetchOutcome.Ok).page.content)
        assertEquals(1, s.fetches)
        assertEquals(1, paths.count { it == "/v1/tools/fetch" })
    }

    /** 搜索没返回任何结果时，直接点名一个公网网址也不能抓。 */
    @Test fun fetchAfterEmptySearchIsForbidden() {
        script = { path, _ ->
            when (path) {
                "/v1/tools/search" -> Resp(200, """{"results":[]}""")
                else -> Resp(404, "{}")
            }
        }
        val s = session(cfg())
        assertTrue(s.search("q") is WebSearch.Outcome.Empty)
        val denied = s.fetch("https://example.com/a")
        assertTrue(denied is WebSearch.FetchOutcome.Failed)
        assertEquals(0, s.fetches)
        assertEquals(0, paths.count { it == "/v1/tools/fetch" })
    }

    /** 自动补正文也只能抓 candidates 里的网址（白名单在 enrich 之前登记）。 */
    @Test fun autoEnrichFetchesReturnedCandidateOnly() {
        script = { path, _ ->
            when (path) {
                "/v1/tools/search" -> Resp(200,
                    """{"results":[{"title":"薄","url":"https://example.com/thin","summary":"短"}]}""")
                "/v1/tools/fetch" -> Resp(200, """{"title":"文章","content":"补到的正文"}""")
                else -> Resp(404, "{}")
            }
        }
        val s = session(cfg())
        val out = s.search("q")
        assertTrue(out is WebSearch.Outcome.Ok)
        val hit = (out as WebSearch.Outcome.Ok).hits.single()
        assertEquals("https://example.com/thin", hit.url)
        assertEquals("补到的正文", hit.content)
        assertEquals(1, s.fetches)
        assertEquals(1, paths.count { it == "/v1/tools/fetch" })
    }

    // ---------------- 空回答 ----------------

    /** 搜索失败 + 模型一个字都没吐：仍是空回答错误，不能拿 memoryNote 冒充回答。 */
    @Test fun emptyFinalWithSearchFailureKeepsEmptyAnswer() {
        script = { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body); bodies += b
                    if (bodies.size == 1) chatReply(1) else Resp(200, "data: [DONE]\n\n")
                }
                "/v1/tools/search" -> Resp(500, """{"error":{"message":"boom"}}""")
                else -> Resp(404, "{}")
            }
        }
        val api = cfg()
        val s = session(api)
        val rec = object : ApiClient.Listener { val text = StringBuilder(); override fun onText(text: String) { this.text.append(text) } }
        val out = ApiClient(api).streamWithSession(body(api), rec, AtomicBoolean(false), session = s).first

        assertEquals("空回答不能变成凭记忆作答", "", out)
        assertEquals("", rec.text.toString())
        assertTrue("失败说明本身仍然存在，只是不能当答案", s.memoryNote().contains("联网搜索失败"))
        assertTrue("给空回答错误补充失败上下文", s.failureContext().contains("500"))
    }

    // ---------------- 增强 / Provider 解析 ----------------

    @Test fun tavilyEnhanceOnlyWhenKeyAndEnabled() {
        val tavilyBodies = mutableListOf<JSONObject>()
        script = { path, body ->
            if (path == "/tavily/search") {
                tavilyBodies += JSONObject(body)
                Resp(200, """{"results":[{"title":"增强","url":"https://tavily.example/b","content":"${"长".repeat(200)}"}]}""")
            } else Resp(404, "{}")
        }
        fun run(search: WebSearch.Config): WebSearch.Outcome {
            tavilyBodies.clear()
            val api = cfg(search)
            return session(api).search("q")
        }

        val noKey = run(WebSearch.Config(enabled = true, provider = WebSearch.TAVILY, enhance = true))
        assertTrue("没 key 时引擎没配好，不能假称搜到", noKey is WebSearch.Outcome.Failed)
        assertEquals(0, tavilyBodies.size)

        val off = run(WebSearch.Config(enabled = true, provider = WebSearch.TAVILY, tavilyKey = "tk", enhance = false))
        assertTrue(off is WebSearch.Outcome.Ok)
        assertEquals(1, tavilyBodies.size)
        assertFalse(tavilyBodies[0].getBoolean("include_raw_content"))

        val on = run(WebSearch.Config(enabled = true, provider = WebSearch.TAVILY, tavilyKey = "tk", enhance = true))
        assertTrue(on is WebSearch.Outcome.Ok)
        assertEquals(1, tavilyBodies.size)
        assertTrue(tavilyBodies[0].getBoolean("include_raw_content"))
    }

    // ---------------- SSE 小工具 ----------------

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String? = null, reasoning: String? = null): String =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().apply {
            if (content != null) put("content", content)
            if (reasoning != null) put("reasoning_content", reasoning)
        }))).toString()

    private fun toolChunk(index: Int, id: String, name: String, args: String, finish: String? = null): String {
        val choice = JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("index", index)
            .put("id", id)
            .put("function", JSONObject().put("name", name).put("arguments", args)))))
        if (finish != null) choice.put("finish_reason", finish)
        return JSONObject().put("choices", JSONArray().put(choice)).toString()
    }

    /** 本地假服务的一行脚本：状态码 + 响应体，可延迟（取消测试用）。 */
    private data class Resp(val code: Int, val body: String, val delayMs: Long = 0)
}
