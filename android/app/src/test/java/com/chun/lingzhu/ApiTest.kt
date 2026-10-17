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
        override fun onPhase(text: String) { phases.add(text) }
        override fun onText(text: String) { this.text.append(text) }
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

    @Test fun visionBodyShape() {
        val b = JSONObject(Bodies.vision(cfg(), "提示", listOf("QUJD"), 6144, false))
        assertEquals(6144, b.getInt("max_tokens"))
        assertTrue(b.getBoolean("stream"))
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("提示", content.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,QUJD", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
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

    @Test fun streamsAnswerAndSendsPromptAndImage() {
        script = { 200 to listOf(delta(reasoning = "想"), delta("## 答案\n"), delta("B. 鲸鱼"), "[DONE]") }
        val rec = Rec()
        val out = Solver(cfg()).solve("QUJD", Solver.Options(Prompts.MODE_EXAM, "detailed", true), rec, AtomicBoolean(false))
        assertEquals("## 答案\nB. 鲸鱼", out)
        assertEquals(out, rec.text.toString())
        val sent = bodies.single()
        assertEquals(12288, sent.getInt("max_tokens"))
        val prompt = sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("## 举一反三") && prompt.contains("纯小白"))
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
