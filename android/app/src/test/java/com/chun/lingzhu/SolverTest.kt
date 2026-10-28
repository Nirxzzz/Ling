package com.chun.lingzhu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Solver 与搜索配置的边界（本地假接口，无外网）：
 *  - 截图识别没有文字也照样把工具表给模型（不再要求 searchText 非空）；
 *  - 数学题由模型自己决定不搜，不能强制多打一次网络；
 *  - 搜索关着时请求体一个 tools 字段都不带。
 */
class SolverTest {
    private lateinit var server: MiniHttp
    private val bodies = mutableListOf<JSONObject>()

    @Before fun up() {
        server = MiniHttp { path, body ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            synchronized(bodies) { bodies.add(JSONObject(body)) }
            200 to "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                .put("delta", JSONObject().put("content", "答案 B")))).toString() + "\n\ndata: [DONE]\n\n"
        }
    }

    @After fun down() = server.stop()

    private fun cfg(search: WebSearch.Config = WebSearch.Config()) =
        ApiConfig("http://127.0.0.1:${server.port}/v1", "sk-test", "glm-4v", "high", search)

    private class Rec : Solver.Callbacks {
        val text = StringBuilder(); val phases = mutableListOf<String>()
        override fun onPhase(text: String) { phases.add(text) }
        override fun onText(text: String) { this.text.append(text) }
    }

    private fun searxng() = WebSearch.Config(
        enabled = true, provider = WebSearch.SEARXNG, searxngBase = "http://127.0.0.1:1",
    )

    @Test fun screenshotOffersToolsWithoutAnyText() {
        val rec = Rec()
        val out = Solver(cfg(searxng())).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))
        assertEquals("答案 B", out)
        assertEquals("答案 B", rec.text.toString())
        assertEquals("只发一次识别请求，不额外做搜索词提取", 1, bodies.size)

        val sent = bodies.single()
        // v2.3.1 起 tools 里常驻 AgentTools 的本机工具（建卡 / 提醒 / 查记忆…），所以不能再断言"总共 1 个"。
        val names = sent.getJSONArray("tools").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertTrue("联网工具必须有 web_search", names.contains(WebSearch.TOOL_SEARCH))
        // v2.3.3：web_fetch 以前只挂在 DeepSeek Key 上；现在 Tavily / SearXNG 也能抓正文，
        // 只要**当前供应商配好了**就该给。这条用的是 SearXNG（用户自填地址，无需 Key），所以必须给。
        assertTrue("SearXNG 配好了 → 也该给 web_fetch", names.contains(WebSearch.TOOL_FETCH))
        val prompt = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(prompt, prompt.contains("数学题"))
        assertTrue(prompt, prompt.contains("不要为了保险先搜"))
        assertTrue("模型不搜就正常作答", rec.phases.none { it.contains("搜索") })
    }

    /**
     * v2.3.3：供应商**没配好**时，仍然只给 web_search（不给 web_fetch）。
     *
     * 新能力只在「当前供应商真能抓」时才暴露 —— SearXNG 地址非法 / 空，等于这家根本用不了；
     * 这时还挂一个必然失败的 web_fetch，模型会照着去调、然后拿到一条错误，
     * 用户看到的是一轮白跑的搜索。宁可一开始就不给。
     */
    @Test fun unconfiguredProviderGetsNoFetchTool() {
        val rec = Rec()
        val bad = WebSearch.Config(enabled = true, provider = WebSearch.SEARXNG, searxngBase = "")
        Solver(cfg(bad)).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))
        val sent = bodies.single()
        // 地址为空 = 这家根本用不了 → 连 tools 字段整个都不发（比只摘掉 web_fetch 更彻底）。
        // 断言整个字段缺失，而不是"有名无 fetch"：后者会让模型以为还能搜，白调一轮。
        assertFalse("供应商没配好时不该发 tools", sent.has("tools"))
    }

    @Test fun searchOffSendsNoTools() {
        val opts = Solver.Options(Prompts.MODE_GENERAL, "standard", false)
        val out = Solver(cfg()).solve("QUJD", opts, Rec(), AtomicBoolean(false))
        assertFalse(bodies.single().has("tools"))
        val prompt = bodies.single().getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse("关着时提示词里也不提工具", prompt.contains("【工具使用】"))
        assertFalse("关着时没有「没联网」提示", out.contains("没有联网"))

        val followed = Solver(cfg()).followUp(Solver.Conversation("QUJD", opts, out), "为什么", Rec(), AtomicBoolean(false))
        assertFalse("追问关着时同样不提示", followed.contains("没有联网"))
        assertFalse(bodies[1].has("tools"))
    }

    /** 开关开着但缺 Key：仍正常作答，但必须可见地说明这一轮没联网，且不能把工具塞给模型。 */
    @Test fun enabledButUnconfiguredSearchGivesVisibleNotice() {
        val missing = WebSearch.Config(enabled = true, provider = WebSearch.TAVILY)
        val opts = Solver.Options(Prompts.MODE_GENERAL, "standard", false)

        val rec = Rec()
        val out = Solver(cfg(missing)).solve("QUJD", opts, rec, AtomicBoolean(false))
        assertTrue("先正常作答", out.startsWith("答案 B"))
        assertTrue(out, out.contains("没有联网"))
        assertTrue("要说清未核实", out.contains("未核实"))
        assertTrue("提示要真的显示出来", rec.text.toString().contains("没有联网"))
        assertFalse("不能假称已经联网", out.contains("已联网搜索"))
        val sent = bodies.single()
        assertFalse("没配好就不该带 tools", sent.has("tools"))
        val prompt = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertFalse("提示词不能声称有工具", prompt.contains("【工具使用】"))

        val conv = Solver.Conversation("QUJD", opts, "答案 A")
        val rec2 = Rec()
        val followed = Solver(cfg(missing)).followUp(conv, "为什么", rec2, AtomicBoolean(false))
        assertTrue(followed, followed.contains("没有联网"))
        assertTrue(rec2.text.toString().contains("没有联网"))
        assertFalse("追问同样不能带 tools", bodies[1].has("tools"))
        assertEquals("对话记录只存原始回答，不把提示塞进上下文", "答案 B", conv.turns.last().second)
    }

    /** max_tokens 被拒后整轮重发一次：提示只能出现一遍，别叠两遍。 */
    @Test fun noticeIsNotDuplicatedOnMaxTokenRetry() {
        server.stop()
        server = MiniHttp { path, body ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            val b = JSONObject(body)
            synchronized(bodies) { bodies.add(b) }
            if (b.getInt("max_tokens") > 4096) {
                400 to """{"error":{"message":"Range of max_tokens should be [1, 8192]"}}"""
            } else {
                200 to "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
                    .put("delta", JSONObject().put("content", "答案 B")))).toString() + "\n\ndata: [DONE]\n\n"
            }
        }
        val missing = WebSearch.Config(enabled = true, provider = WebSearch.TAVILY)
        val out = Solver(cfg(missing)).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), Rec(), AtomicBoolean(false))
        assertTrue(out, out.contains("答案 B"))
        assertEquals("重试不会重复提示", 1, Regex("这一轮没有联网").findAll(out).count())
        assertEquals(2, bodies.size)
    }

    @Test fun followUpSlashTriggerForcesSearchHintButStripsCommand() {
        val conv = Solver.Conversation("QUJD", Solver.Options(Prompts.MODE_GENERAL, "standard", false), "答案 A")
        val out = Solver(cfg(searxng())).followUp(conv, "/s 最新天气", Rec(), AtomicBoolean(false))
        assertEquals("答案 B", out)
        val sent = bodies.single()
        assertTrue(sent.has("tools"))
        val prompt = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(prompt, prompt.contains("用户已明确要求联网搜索"))
        val q = sent.getJSONArray("messages").getJSONObject(2).getString("content")
        assertFalse("/s", q.contains("/s"))
        assertTrue(q, q.contains("最新天气"))
    }

    // ---------------- v2.2.1：tools 兜底 / 重试拿新搜索快照 ----------------

    private fun deepseek() = WebSearch.Config(enabled = true, provider = WebSearch.DEEPSEEK, deepseekKey = "sk-search")

    private fun api(base: String, search: WebSearch.Config) =
        ApiConfig(base, "sk-model", "deepseek-chat", "high", search)

    /** 搜索端点也指向假服务，测试不出网（a.base 形如 http://127.0.0.1:port/v1，端点要用它的根） */
    private fun solver(a: ApiConfig) = Solver(a, newSession = {
        val root = a.base.removeSuffix("/v1")
        WebSearch.session(
            a.searchConfig, a,
            searchEndpoint = root + "/v1/tools/search",
            fetchEndpoint = root + "/v1/tools/fetch",
        )
    })

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun delta(content: String? = null, reasoning: String? = null): String =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().apply {
            if (content != null) put("content", content)
            if (reasoning != null) put("reasoning_content", reasoning)
        }))).toString()

    private fun toolChunk(index: Int, id: String, name: String, args: String, finish: String? = null): String {
        val choice = JSONObject().put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(JSONObject()
            .put("index", index).put("id", id)
            .put("function", JSONObject().put("name", name).put("arguments", args)))))
        if (finish != null) choice.put("finish_reason", finish)
        return JSONObject().put("choices", org.json.JSONArray().put(choice)).toString()
    }

    /**
     * P1-2：接口 / 模型不认 function calling 时，带 tools 会直接 400。
     * 以前整题失败、还显示成「模型名对吗」；现在去掉工具再答一次，并在答案末尾说清「本次没联网」。
     */
    @Test fun toolsUnsupportedFallsBackWithoutToolsAndSaysSo() {
        val seen = mutableListOf<JSONObject>()
        server.stop()
        server = MiniHttp { path, body ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            val b = JSONObject(body)
            synchronized(seen) { seen.add(b) }
            if (b.has("tools")) 400 to """{"error":{"message":"tools is not supported by this model"}}"""
            else 200 to sse(delta(content = "答案 B"))
        }
        val a = api("http://127.0.0.1:${server.port}/v1", deepseek())
        val rec = Rec()
        val out = solver(a).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))

        assertTrue(out, out.startsWith("答案 B"))
        assertTrue("必须明确告诉用户这次没联网", out.contains("不支持联网工具调用"))
        assertEquals("带工具一次 + 去掉工具一次", 2, seen.size)
        assertTrue("第一次要带 tools（否则测不到回退）", seen[0].has("tools"))
        assertFalse("第二次必须不带 tools", seen[1].has("tools"))
        assertTrue("用户看得见那句说明", rec.text.toString().contains("不支持联网工具调用"))
    }

    /** 不相关的 400（比如模型名错）不能被当成「不支持 tools」去重试。 */
    @Test fun unrelated400DoesNotFallBack() {
        var hits = 0
        server.stop()
        server = MiniHttp { path, _ ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            hits++
            400 to """{"error":{"message":"model not found"}}"""
        }
        val a = api("http://127.0.0.1:${server.port}/v1", deepseek())
        var msg = ""
        try {
            solver(a).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), Rec(), AtomicBoolean(false))
            throw AssertionError("必须报错")
        } catch (e: ApiException) { msg = e.message.orEmpty() }
        assertTrue(msg, msg.contains("400"))
        assertEquals("只发一次，不重试", 1, hits)
    }

    /**
     * P2-3：重试（这里是被「思考吃光预算、没吐正文」触发的关思考重试）必须拿到**新的**搜索快照。
     * 以前三次调用共用一份 session，搜索次数早记满 → 重试那轮其实没联网，却没有任何提示。
     * 断言：三段脚本各搜一次，搜索端点应该被真的打到 3 次（旧行为只会有 2 次）。
     */
    @Test fun retryGetsAFreshSearchBudget() {
        var searchHits = 0
        server.stop()
        server = MiniHttp { path, body ->
            when (path) {
                "/v1/chat/completions" -> {
                    val b = JSONObject(body)
                    val round = synchronized(bodies) { bodies.add(b); bodies.size }
                    when (round) {
                        1 -> 200 to sse(delta(reasoning = "想"), toolChunk(0, "c1", "web_search", "{\"query\":\"甲\"}", finish = "tool_calls"))
                        2 -> 200 to sse(delta(content = ""), toolChunk(0, "c2", "web_search", "{\"query\":\"乙\"}", finish = "tool_calls"))
                        3 -> 200 to sse(delta(reasoning = "还没想完"))
                        4 -> 200 to sse(delta(reasoning = "再来"), toolChunk(0, "c3", "web_search", "{\"query\":\"丙\"}", finish = "tool_calls"))
                        else -> 200 to sse(delta(content = "答案 9"))
                    }
                }
                "/v1/tools/search" -> { searchHits++; 200 to """{"results":[{"title":"t","url":"https://a.example/1","summary":"s"}]}""" }
                else -> 404 to "{}"
            }
        }
        val a = api("http://127.0.0.1:${server.port}/v1", deepseek())
        val out = solver(a).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), Rec(), AtomicBoolean(false))
        assertTrue(out, out.contains("答案 9"))
        assertEquals("重试要重新拿到完整搜索额度（2 + 1 次）", 3, searchHits)
    }
}
