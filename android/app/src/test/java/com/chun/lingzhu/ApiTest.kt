package com.chun.lingzhu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** 本地起一个假 OpenAI 兼容服务，走真 HTTP 把整条流式链路测一遍 */
class ApiTest {
    private lateinit var server: MiniHttp
    private val bodies = mutableListOf<JSONObject>()
    @Volatile private var script: (JSONObject) -> Pair<Int, List<String>> = { 200 to emptyList() }

    @Before fun up() {
        server = MiniHttp { path, body ->
            if (path != "/v1/chat/completions") return@MiniHttp 404 to "{}"
            val b = JSONObject(body)
            synchronized(bodies) { bodies.add(b) }
            val (code, lines) = script(b)
            code to (if (code == 200) lines.joinToString("") { "data: $it\n\n" } else lines.firstOrNull() ?: "{\"error\":{\"message\":\"bad key\"}}")
        }
    }

    @After fun down() = server.stop()

    private fun cfg(model: String = "glm-4v", think: String = "high") = ApiConfig("http://127.0.0.1:${server.port}/v1/", "sk-test", model, think)
    private fun delta(content: String? = null, reasoning: String? = null) =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().apply {
            if (content != null) put("content", content); if (reasoning != null) put("reasoning_content", reasoning)
        }))).toString()

    private class Rec : Solver.Callbacks {
        val text = StringBuilder(); val phases = mutableListOf<String>()
        /** 真收到的思考增量（按到达顺序拼起来） */
        val think = StringBuilder()
        override fun onPhase(text: String) { phases.add(text) }
        override fun onText(text: String) { this.text.append(text) }
        override fun onReasoning(delta: String) { think.append(delta) }
    }

    @Test fun sseParser() {
        assertNull(Sse.parseLine(": keep-alive"))
        assertTrue(Sse.parseLine("data: [DONE]")!!.done)
        assertEquals("你好", Sse.parseLine("data: " + delta("你好"))!!.content)
        assertEquals("嗯", Sse.parseLine("data: " + delta(reasoning = "嗯"))!!.reasoning)
        assertEquals("", Sse.parseLine("data: {oops")!!.content)
        assertEquals("", Sse.parseLine("data: {\"choices\":[]}")!!.content)
        assertEquals("", Sse.parseLine("data: {\"choices\":[{\"delta\":{\"content\":null}}]}")!!.content)
    }

    /** 并行工具调用：一块 delta 里多个 tool_calls 都要解析出来（不能只留第一个） */
    @Test fun sseParserKeepsAllParallelToolCalls() {
        val line = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject()
            .put("tool_calls", org.json.JSONArray()
                .put(JSONObject().put("index", 0).put("id", "call_a").put("function", JSONObject().put("name", "web_search").put("arguments", "{\"query\":")))
                .put(JSONObject().put("index", 1).put("id", "call_b").put("function", JSONObject().put("name", "web_fetch").put("arguments", "{\"url\":")))
            )))).toString()
        val tools = Sse.parseLine("data: $line")!!.tools
        assertEquals(2, tools.size)
        assertEquals("call_a", tools[0].id)
        assertEquals("web_search", tools[0].name)
        assertEquals("call_b", tools[1].id)
        assertEquals("web_fetch", tools[1].name)
    }

    /** 默认搜索配置是关的：请求体里不能出现 tools 字段 */
    @Test fun defaultSearchConfigSendsNoTools() {
        val b = JSONObject(Bodies.vision(cfg(), "提示", listOf("QUJD"), 6144, false, withTools = true))
        assertFalse(b.has("tools"))
        assertFalse(ApiConfig("https://x/v1", "k", "m").searchConfig.enabled)
        assertEquals(WebSearch.DEEPSEEK, ApiConfig("https://x/v1", "k", "m").searchConfig.provider)
    }

    /** 工具续轮：assistant 消息带 reasoning_content 和全部 tool_calls，每条调用都有对应 tool 消息 */
    @Test fun withToolRoundCarriesReasoningAndAllCalls() {
        val body = Bodies.vision(cfg(), "提示", listOf("QUJD"), 100, false)
        val calls = listOf(
            ToolCall("call_a", "web_search", "{\"query\":\"天气\"}"),
            ToolCall("call_b", "web_fetch", "{\"url\":\"https://example.com\"}"),
        )
        val next = Bodies.withToolRound(body, "我需要先查一下", "我去查。", calls, listOf("搜索结果", "网页正文"))
        val msgs = JSONObject(next).getJSONArray("messages")
        assertEquals(4, msgs.length())
        val assistant = msgs.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("我需要先查一下", assistant.getString("reasoning_content"))
        assertEquals("我去查。", assistant.getString("content"))
        val tc = assistant.getJSONArray("tool_calls")
        assertEquals(2, tc.length())
        assertEquals("call_a", tc.getJSONObject(0).getString("id"))
        assertEquals("web_fetch", tc.getJSONObject(1).getJSONObject("function").getString("name"))
        assertEquals("tool", msgs.getJSONObject(2).getString("role"))
        assertEquals("call_a", msgs.getJSONObject(2).getString("tool_call_id"))
        assertEquals("搜索结果", msgs.getJSONObject(2).getString("content"))
        assertEquals("call_b", msgs.getJSONObject(3).getString("tool_call_id"))
        assertEquals("网页正文", msgs.getJSONObject(3).getString("content"))
    }

    /** 调工具那一轮没有正文时 content 必须是 null，不能是空串 */
    @Test fun withToolRoundUsesNullContentWhenNoPreamble() {
        val next = Bodies.withToolRound(Bodies.text(cfg(), "x", 10, false), "", "", listOf(ToolCall("id1", "web_search", "{}")), listOf("r"))
        val a = JSONObject(next).getJSONArray("messages").getJSONObject(1)
        assertTrue(a.isNull("content"))
        assertFalse("没有思考就不硬塞空 reasoning_content", a.has("reasoning_content"))
    }

    @Test fun visionBodyShape() {
        val b = JSONObject(Bodies.vision(cfg(), "提示", listOf("QUJD"), 6144, false))
        assertEquals(6144, b.getInt("max_tokens"))
        assertTrue(b.getBoolean("stream"))
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("提示", content.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,QUJD", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        // v2.1：识别质量 —— 截图明确要求高分辨率档位，不让兼容实现按 auto/low 缩小了认题
        assertEquals("high", content.getJSONObject(1).getJSONObject("image_url").getString("detail"))
        assertFalse("非 deepseek 不带思考参数", b.has("thinking"))
    }

    @Test fun thinkingParamsOnlyForDeepSeek() {
        val on = JSONObject(Bodies.text(cfg("deepseek-chat", "max"), "x", 10, false))
        assertEquals("enabled", on.getJSONObject("thinking").getString("type"))
        assertEquals("max", on.getString("reasoning_effort"))
        val off = JSONObject(Bodies.text(cfg("deepseek-chat", "off"), "x", 10, false))
        assertEquals("disabled", off.getJSONObject("thinking").getString("type"))
        assertFalse(off.has("reasoning_effort"))
        val forced = JSONObject(Bodies.text(cfg("deepseek-chat", "high"), "x", 10, true))
        assertEquals("disabled", forced.getJSONObject("thinking").getString("type"))
    }

    /**
     * 用户实测的 HTTP 400：`Thinking mode does not support this tool_choice`。
     *
     * DeepSeek 禁止思考模式 + required / 指定工具。聊天路径恒是「思考 enabled + 全部工具 + agentMode」，
     * 所以这个组合必须是 auto：工具照样挂着、用户的思考强度照样生效，不靠关思考或删工具绕开。
     */
    @Test fun thinkingModeNeverSendsRequiredToolChoice() {
        val thinking = JSONObject(Bodies.text(cfg("deepseek-chat", "high"), "x", 10, false, withTools = true))
        assertEquals("enabled", thinking.getJSONObject("thinking").getString("type"))
        assertTrue("本机工具必须还在", thinking.getJSONArray("tools").length() > 0)
        assertEquals("auto", thinking.getString("tool_choice"))

        // Agent 模式首轮会 force=true —— 思考开着时也不能越过这条限制
        val forced = JSONObject(Bodies.withToolChoice(thinking.toString(), force = true))
        assertEquals("auto", forced.getString("tool_choice"))
        assertEquals("思考不能被关掉", "enabled", forced.getJSONObject("thinking").getString("type"))
        assertTrue("工具也不能被摘掉", forced.getJSONArray("tools").length() > 0)

        // 不思考的路径：首轮仍是 required（逼模型真的动手），续轮回到 auto
        val plain = Bodies.text(cfg("glm-4v"), "x", 10, false, withTools = true)
        assertEquals("auto", JSONObject(plain).getString("tool_choice"))
        assertEquals("required", JSONObject(Bodies.withToolChoice(plain, force = true)).getString("tool_choice"))
        assertEquals("auto", JSONObject(Bodies.withToolChoice(plain, force = false)).getString("tool_choice"))
    }

    @Test fun streamsAnswerAndSendsPromptAndImage() {
        script = { 200 to listOf(delta(reasoning = "想"), delta("## 答案\n"), delta("B. 鲸鱼"), "[DONE]") }
        val rec = Rec()
        val out = Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "detailed", true), rec, AtomicBoolean(false))
        assertEquals("## 答案\nB. 鲸鱼", out)
        assertEquals(out, rec.text.toString())
        val sent = bodies.single()
        assertEquals(12288, sent.getInt("max_tokens"))
        val prompt = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("## 举一反三") && prompt.contains("## 一步步做"))
    }

    /**
     * 思考增量：真 SSE 的 `reasoning_content` 一路传到 `Solver.Callbacks.onReasoning`，
     * 而**正文 / 最终答案 / 历史用的返回值里一个字都不带**思考内容。
     *
     * 这是「展示真实思考」这条需求的硬约束：思考只用来显示，绝不污染答案。
     */
    @Test fun reasoningStreamsSeparatelyAndNeverPollutesAnswer() {
        script = { 200 to listOf(
            delta(reasoning = "先看题"), delta(reasoning = "：两个数相加"),
            delta("## 答案\n"), delta("42"), "[DONE]",
        ) }
        val rec = Rec()
        val out = Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))
        assertEquals("## 答案\n42", out)
        assertEquals("## 答案\n42", rec.text.toString())
        assertEquals("先看题：两个数相加", rec.think.toString())
        assertFalse("思考不许进正文", rec.text.contains("先看题"))
        assertFalse("思考不许进最终答案", out.contains("两个数相加"))
    }

    /** 取消之后不再有思考增量外放（关面板 / 划走时不能继续往一个已经消失的面板上写） */
    @Test fun cancelledSolveEmitsNoReasoning() {
        script = { 200 to listOf(delta(reasoning = "想了一半"), delta("正文"), "[DONE]") }
        val rec = Rec()
        Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(true))
        assertEquals("", rec.think.toString())
        assertEquals("", rec.text.toString())
    }

    @Test fun stripsNoQuestionMarkerAcrossChunks() {
        script = { 200 to listOf(delta("[无"), delta("题目] "), delta("这是设置页面"), "[DONE]") }
        val rec = Rec()
        Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_GENERAL, "standard", false), rec, AtomicBoolean(false))
        assertEquals("这是设置页面", rec.text.toString())
    }

    @Test fun retriesWithoutThinkingWhenBudgetEaten() {
        script = { b -> if (b.optJSONObject("thinking")?.optString("type") == "disabled") 200 to listOf(delta("答案 A"), "[DONE]") else 200 to listOf(delta(reasoning = "想了很久"), "[DONE]") }
        val rec = Rec()
        val out = Solver(cfg("deepseek-chat")).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))
        assertEquals("答案 A", out)      // 关掉思考重试那次拿到的正文就是最终结果
        assertEquals(2, bodies.size)     // 确实发了两轮请求（第一轮被思考吃光预算）
    }

    /** v1.54：到了 max_tokens（finish_reason=length）要在正文末尾明说被截断，不能静悄悄停住 */
    @Test fun truncatedAnswerIsFlagged() {
        val last = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "3. 所以选")).put("finish_reason", "length"))).toString()
        script = { 200 to listOf(delta("## 答案\nB\n## 一步步做\n1. 先看"), last, "[DONE]") }
        val rec = Rec()
        val out = Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), rec, AtomicBoolean(false))
        assertTrue(out.startsWith("## 答案\nB\n## 一步步做\n1. 先看3. 所以选"))
        assertTrue(out.contains("被截断"))
        assertEquals(out, rec.text.toString())
        assertEquals("length", Sse.parseLine("data: $last")!!.finish)
        // 正常结束不提示
        script = { 200 to listOf(delta("答案 A"), "[DONE]") }
        assertFalse(Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_GENERAL, "standard", false), Rec(), AtomicBoolean(false)).contains("截断"))
    }

    /** 接口输出上限比预算小会 400 —— 退回 4096 重发一次 */
    @Test fun fallsBackWhenMaxTokensRejected() {
        script = { b -> if (b.getInt("max_tokens") > 4096) 400 to listOf("{\"error\":{\"message\":\"Range of max_tokens should be [1, 8192]\"}}") else 200 to listOf(delta("答案 A"), "[DONE]") }
        val out = Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "standard", false), Rec(), AtomicBoolean(false))
        assertEquals("答案 A", out)
        assertEquals(listOf(8192, 4096), bodies.map { it.getInt("max_tokens") })
    }

    /** v1.57 追问：同一张图 + 第一轮提示词 + 前面的问答原样重发，最后一条是追问；第二次追问带上第一次的 */
    @Test fun followUpSendsWholeConversation() {
        script = { b -> 200 to listOf(delta(if (b.getJSONArray("messages").length() == 3) "因为合力为零" else "比如推箱子"), "[DONE]") }
        val opts = Solver.Options(Prompts.MODE_EXAM, "standard", false)
        val conv = Solver.Conversation("QUJD", opts, "## 答案\nB")
        val rec = Rec()
        assertEquals("因为合力为零", Solver(cfg()).followUp(conv, "为什么", rec, AtomicBoolean(false)))
        assertEquals("因为合力为零", rec.text.toString())
        val m = bodies[0].getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
        val first = m.getJSONObject(0).getJSONArray("content")
        assertEquals(Prompts.system(Prompts.MODE_EXAM, "standard", false), first.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,QUJD", first.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("## 答案\nB", m.getJSONObject(1).getString("content"))
        assertTrue(m.getJSONObject(2).getString("content").startsWith("追问：为什么"))
        assertEquals(8192, bodies[0].getInt("max_tokens"))

        assertEquals("比如推箱子", Solver(cfg()).followUp(conv, "举个例子", Rec(), AtomicBoolean(false)))
        val m2 = bodies[1].getJSONArray("messages")
        assertEquals(5, m2.length())
        assertEquals("因为合力为零", m2.getJSONObject(3).getString("content"))
        assertTrue(m2.getJSONObject(4).getString("content").startsWith("追问：举个例子"))
        assertEquals(5, conv.turns.size)
    }

    /** 追问被取消（关面板）就不记进对话，下次追问不带半截回答 */
    @Test fun cancelledFollowUpIsNotRecorded() {
        script = { 200 to listOf(delta("半截"), "[DONE]") }
        val conv = Solver.Conversation("QUJD", Solver.Options(Prompts.MODE_GENERAL, "standard", false), "答案 A")
        Solver(cfg()).followUp(conv, "为什么", Rec(), AtomicBoolean(true))
        assertEquals(1, conv.turns.size)
    }

    @Test fun customPromptIsSent() {
        script = { 200 to listOf(delta("好的"), "[DONE]") }
        Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_CUSTOM, "standard", false, "只说画面里有几个人"), Rec(), AtomicBoolean(false))
        val prompt = bodies.single().getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("只说画面里有几个人"))
    }

    @Test fun httpErrorIsReadable() {
        script = { 401 to emptyList() }
        try {
            Solver(cfg()).translate("hi", "auto", Rec(), AtomicBoolean(false)); fail("应抛错")
        } catch (e: ApiException) {
            assertTrue(e.message!!, e.message!!.startsWith("HTTP 401（Key 不对或没权限）"))
        }
    }

    @Test fun emptyAnswerIsAnError() {
        script = { 200 to listOf("[DONE]") }
        try { Solver(cfg()).translate("hi", "auto", Rec(), AtomicBoolean(false)); fail("应抛错") } catch (e: ApiException) { assertTrue(e.message!!.contains("未返回内容")) }
    }

    @Test fun missingBaseUrlIsExplained() {
        try { ApiClient(ApiConfig("", "k", "m")).ping(); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("接口地址")) }
    }
}
