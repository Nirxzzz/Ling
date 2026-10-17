package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI 兼容接口（/chat/completions，SSE 流式）。
 * 只用 JDK + org.json —— 不碰 android.* ，这样 JVM 单元测试能直接拿本地假服务器测整条链路。
 */
data class ApiConfig(val baseUrl: String, val apiKey: String, val model: String, val thinkingEffort: String = "high") {
    val base: String get() = baseUrl.trim().trimEnd('/')
    /** DeepSeek 专有的思考参数只对 deepseek 端点带 —— 别的厂商不认（OpenAI 会直接 400） */
    val isDeepSeek: Boolean get() = Regex("deepseek", RegexOption.IGNORE_CASE).containsMatchIn(baseUrl) ||
        Regex("^deepseek", RegexOption.IGNORE_CASE).containsMatchIn(model.trim())
}

open class ApiException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * 取消标记 + 当前连接（v1.63）。以前只在两行流数据之间看一眼标记：卡在建连 / 等首字节时关面板，
 * 请求照样挂满 90 秒，期间双击小球没反应。abort() 直接掐断连接，阻塞的读马上抛异常返回。
 */
class Cancel : AtomicBoolean(false) {
    @Volatile internal var conn: HttpURLConnection? = null
    fun abort() {
        set(true)
        val c = conn ?: return
        Thread { try { c.disconnect() } catch (_: Exception) {} }.start()   // 断连接可能碰网络，别在主线程
    }
}

/** 网络异常 → 大白话（v1.63：以前直接甩「Unable to resolve host …」） */
object NetErrors {
    fun friendly(e: Throwable, base: String = ""): String {
        val host = try { java.net.URI(base).host.orEmpty() } catch (_: Exception) { "" }
        return when (e) {
            is ApiException -> e.message.orEmpty()
            is java.net.UnknownHostException -> "连不上网：找不到接口服务器${if (host.isNotEmpty()) "（$host）" else ""} —— 看看手机有没有网，或接口地址有没有写错"
            is java.net.SocketTimeoutException -> "接口太久没回应（超时）—— 网络慢或服务器忙，稍后再双击小球识别一次"
            is java.net.ConnectException, is java.net.NoRouteToHostException -> "连不上接口服务器 —— 检查网络 / 代理，或接口地址端口是否正确"
            is javax.net.ssl.SSLException -> "HTTPS 加密握手失败 —— 检查接口地址是否正确、手机时间是否准确"
            is java.io.IOException -> "网络中断了（${e.message ?: e.javaClass.simpleName}）—— 稍后再试"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
    fun retryable(e: Throwable): Boolean = when (e) {
        is ApiException -> e.retryable
        is java.net.UnknownHostException, is javax.net.ssl.SSLException -> false   // 重试也没用
        is java.io.IOException -> true
        else -> false
    }
}

object Sse {
    /** finish = 该块带的 finish_reason（多数块为空；"length" = 到了 max_tokens 被截断；"tool_calls" = 模型要调工具）；usage = 真实 token 用量（多数接口在最后一个块带上） */
    data class Delta(val content: String, val reasoning: String, val done: Boolean, val finish: String = "", val usage: Pair<Int, Int>? = null, val tool: Chunk? = null)

    /**
     * [Delta] 里 tool_calls 的一块碎片。OpenAI 的流式形状：id 和 function.name 只在第一块来，
     * function.arguments 一段段往后追加（到 finish_reason=tool_calls 那一块才拼得完）—— 所以要按 index 攒。
     */
    data class Chunk(val index: Int, val id: String, val name: String, val args: String)

    /** 解析一行 SSE。不是 data 行返回 null；[DONE] 返回 done=true；坏 JSON 当空增量忽略 */
    fun parseLine(line: String): Delta? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        val payload = t.substring(5).trim()
        if (payload == "[DONE]") return Delta("", "", true)
        if (payload.isEmpty()) return null
        return try {
            val j = JSONObject(payload)
            // v1.65 用量：OpenAI 兼容接口在最后一个块（choices 空或不空）带 usage；有的空 choices 块只为了送 usage
            val u = j.optJSONObject("usage")?.let {
                val p = it.optInt("prompt_tokens", -1); val c = it.optInt("completion_tokens", -1)
                if (p >= 0 && c >= 0) p to c else null
            }
            val choices = j.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return Delta("", "", false, usage = u)
            val c0 = choices.getJSONObject(0)
            val finish = optStr(c0, "finish_reason")
            val d = c0.optJSONObject("delta") ?: c0.optJSONObject("message") ?: return Delta("", "", false, finish, u)
            Delta(optStr(d, "content"), optStr(d, "reasoning_content"), false, finish, u, toolChunk(d))
        } catch (e: Exception) {
            Delta("", "", false)
        }
    }

    /** 工具调用碎片：只认第一个（并行调多个工具以后再说，现在只有 web_search 一个）；不是对象就当没有 */
    private fun toolChunk(d: JSONObject): Chunk? {
        val arr = d.optJSONArray("tool_calls") ?: return null
        if (arr.length() == 0) return null
        val o = arr.optJSONObject(0) ?: return null
        val f = o.optJSONObject("function")
        return Chunk(o.optInt("index", 0), optStr(o, "id"), if (f == null) "" else optStr(f, "name"), if (f == null) "" else optStr(f, "arguments"))
    }

    private fun optStr(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")
}

/** 模型点名的工具调用（把 [Sse.Chunk] 按 index 拼好之后的样子）。id 要原样回填给 tool 消息，模型自己认账 */
data class ToolCall(val id: String, val name: String, val args: String)

object Bodies {
    fun thinking(cfg: ApiConfig, forceOff: Boolean, into: JSONObject) {
        if (!cfg.isDeepSeek) return
        val eff = if (forceOff) "off" else cfg.thinkingEffort.takeIf { it in Prompts.THINK_EFFORTS } ?: "high"
        if (eff == "off") {
            into.put("thinking", JSONObject().put("type", "disabled"))
        } else {
            into.put("thinking", JSONObject().put("type", "enabled"))
            into.put("reasoning_effort", eff)
        }
    }

    /** [WebSearch] 请求体挂上 web_search 工具（OpenAI tools 格式）。**开关关 / 引擎没配 → 什么都不加**，请求体和 v1.67 一模一样 */
    fun tools(into: JSONObject) {
        if (!WebSearch.live()) return
        into.put("tools", JSONArray().put(JSONObject(WebSearch.toolsJson())))
    }

    /**
     * [WebSearch] 第二轮：把「模型要调 web_search」和「搜到的结果」两条消息接在原请求体后面。
     *
     * 走的是标准两段式：assistant（认下这次调用，带 tool_calls）+ tool（tool_call_id 与上面 id 一致）。
     * **tools 字段原样留着** —— 有些模型第二轮还需要工具表，不留会当成不支持工具直接报错。
     * 原 body 解析不了就原样返回（宁可让这一轮按没搜到走，也不要拿一个坏体去请求）。
     */
    fun withToolTurns(body: String, call: ToolCall, result: String): String = try {
        val o = JSONObject(body)
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        msgs.put(JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)          // 调工具那一轮 assistant 没正文，OpenAI 兼容实现要求 content 为 null 而不是 ""
            .put("tool_calls", JSONArray().put(JSONObject()
                .put("id", call.id)
                .put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.args.ifBlank { "{}" })))))
        msgs.put(JSONObject()
            .put("role", "tool")
            .put("tool_call_id", call.id)
            .put("content", result))
        o.put("messages", msgs).toString()
    } catch (e: Exception) { body }

    /**
     * 识别：系统提示 + 截图放在同一条 user 消息里（同桌面 chatBody）。
     * turns（v1.57 追问）：接在后面的多轮对话 (role, 文字)，assistant / user 交替，最后一条是这次的追问。
     */
    fun vision(cfg: ApiConfig, prompt: String, jpegB64: List<String>, maxTokens: Int, thinkingOff: Boolean, turns: List<Pair<String, String>> = emptyList(), withTools: Boolean = false): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (b in jpegB64) {
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b")))
        }
        val o = JSONObject()
            .put("model", cfg.model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)).apply {
                for ((role, t) in turns) put(JSONObject().put("role", role).put("content", t))
            })
        thinking(cfg, thinkingOff, o)
        if (withTools) tools(o)   // [WebSearch] 开关关时 tools() 自己什么都不加
        return o.toString()
    }

    fun text(cfg: ApiConfig, prompt: String, maxTokens: Int, thinkingOff: Boolean, stream: Boolean = true, withTools: Boolean = false): String {
        val o = JSONObject()
            .put("model", cfg.model)
            .put("stream", stream)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        thinking(cfg, thinkingOff, o)
        if (withTools) tools(o)   // [WebSearch]
        return o.toString()
    }
}

class ApiClient(private val cfg: ApiConfig) {
    companion object {
        var RETRY_WAIT_MS = 1500   // 单测里调短
        /** 32×32 灰度 PNG，黑色的「7」 */
        const val PROBE_PNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAAAAABWESUoAAAAP0lEQVR42mP4TwAwDCkFDOhgABSguoxSBUjy5ClAlidLAYo8OQpQ5clQgCZPugJ0eZIVYMiTqgBTnlQFA5OzAGgvcLoLH2d6AAAAAElFTkSuQmCC"
    }

    interface Listener {
        fun onThinking(chars: Int) {}
        fun onText(text: String)
        /** v1.65：接口上报的真实 token 用量（prompt to completion）；流里没有就不回调 */
        fun onUsage(prompt: Int, completion: Int) {}
    }

    /**
     * 发一次流式请求，逐段回调正文。返回 (正文, 思考字数)。
     * cancel 置位后尽快断开（关面板 / 退出）。
     */
    /** 上一次 stream 是不是因为到了 max_tokens 被截断（finish_reason=length） */
    var truncated = false
        private set

    /**
     * [WebSearch] 上一次 stream 结束时模型点的工具（finish_reason=tool_calls）。没点就是 null。
     * 每次 [stream] 开头重置 —— 别把上一轮的调用带进这一轮。
     */
    var toolCall: ToolCall? = null
        private set

    /** [WebSearch] 这一轮流里攒到的 tool_calls 分片，按 index 归并（id / name 只在第一块，arguments 分片追加） */
    private val chunks = LinkedHashMap<Int, StringBuilder>()
    private var chunkId = ""
    private var chunkName = ""

    fun stream(body: String, listener: Listener, cancel: AtomicBoolean = AtomicBoolean(false), readTimeoutMs: Int = 90_000): Pair<String, Int> {
        toolCall = null   // [WebSearch]
        chunks.clear(); chunkId = ""; chunkName = ""
        // v1.63：还没吐出任何字时遇到网络抖动 / 429 / 5xx，隔 1.5 秒自动再试一次（已经出字了就不重试，免得重复）
        var emitted = false
        val l = object : Listener {
            override fun onThinking(chars: Int) { emitted = true; listener.onThinking(chars) }
            override fun onText(text: String) { emitted = true; listener.onText(text) }
            override fun onUsage(prompt: Int, completion: Int) { listener.onUsage(prompt, completion) }
        }
        for (attempt in 0..1) {
            try {
                return streamOnce(body, l, cancel, readTimeoutMs)
            } catch (e: Exception) {
                if (cancel.get()) return "" to 0
                if (attempt == 0 && !emitted && NetErrors.retryable(e)) {
                    var waited = 0
                    while (waited < RETRY_WAIT_MS && !cancel.get()) { Thread.sleep(100); waited += 100 }
                    if (cancel.get()) return "" to 0
                    continue
                }
                if (e is ApiException) throw e
                throw ApiException(NetErrors.friendly(e, cfg.base))
            }
        }
        throw ApiException("请求失败")
    }

    /**
     * [WebSearch] 模型点了 web_search → 真搜一次 → 结果以 tool 角色回填 → 发第二轮。
     *
     * 只有 name=[WebSearch.TOOL_NAME] 的调用才走这条路；别的名字（以后加别的工具）当没这回事，
     * 第一轮的思考/正文照常用（不少模型会把工具说明先说出来再调）。
     * 搜索本身抛异常（限流 / 断网）**不把整轮带崩**：把失败写成一句 tool 结果，第二轮让模型自己凭知识答。
     * 只回填一轮：模型再点第二次工具就当没看见（防自己跟自己对话套娃，也省一次网络往返）。
     *
     * 返回 (两轮正文拼起来, 两轮思考字数之和)。没触发工具搜索时行为和 [stream] 完全一样。
     */
    fun streamWithSearch(body: String, listener: Listener, cancel: AtomicBoolean, readTimeoutMs: Int = 90_000, fallbackQuery: String = "", onSearch: (String) -> Unit = {}): Pair<String, Int> {
        val first = stream(body, listener, cancel, readTimeoutMs)
        val call = toolCall ?: return first
        if (call.name != WebSearch.TOOL_NAME) return first
        if (cancel.get()) return first

        val query = WebSearch.queryFromArgs(call.args, fallbackQuery)
        onSearch(query)   // 让面板上出「正在联网搜索…」—— 搜索要几秒，不出声用户以为卡死了
        val hits = try {
            WebSearch.engine?.search(query).orEmpty()
        } catch (e: Exception) {
            emptyList()   // 搜索源抽风不连带把正常回答搞崩（见 WebSearch 头部纪律）
        }
        val result = WebSearch.toolResultText(query, hits)
        val body2 = Bodies.withToolTurns(body, call, result)
        if (body2 === body) return first   // 请求体接不上（解析失败）→ 别拿原样重发一遍

        val client2 = ApiClient(cfg)
        val second = client2.stream(body2, object : Listener {
            override fun onThinking(chars: Int) { listener.onThinking(first.second + chars) }
            override fun onText(text: String) { listener.onText(text) }
            override fun onUsage(prompt: Int, completion: Int) { listener.onUsage(prompt, completion) }
        }, cancel, readTimeoutMs)
        if (client2.truncated) truncated = true
        return (first.first + second.first) to (first.second + second.second)
    }

    private fun streamOnce(body: String, listener: Listener, cancel: AtomicBoolean, readTimeoutMs: Int): Pair<String, Int> {
        if (cancel.get()) return "" to 0
        val conn = open("/chat/completions", readTimeoutMs)
        (cancel as? Cancel)?.conn = conn
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, conn), retryable = code == 429 || code in 500..599)
            val out = StringBuilder()
            var thinkChars = 0
            truncated = false
            toolCall = null      // [WebSearch] 每条流自己从头攒，别留上一轮的
            chunks.clear(); chunkId = ""; chunkName = ""
            try {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (!cancel.get()) {
                        val line = r.readLine() ?: break
                        val d = Sse.parseLine(line) ?: continue
                        if (d.done) break
                        if (d.finish == "length") truncated = true
                        d.usage?.let { listener.onUsage(it.first, it.second) }
                        if (d.reasoning.isNotEmpty()) {
                            thinkChars += d.reasoning.length
                            listener.onThinking(thinkChars)
                        }
                        if (d.content.isNotEmpty()) {
                            out.append(d.content)
                            listener.onText(d.content)
                        }
                        // [WebSearch] 顺序要紧：带 finish_reason=tool_calls 的那一块常常同时带着 arguments 的最后一段，
                        // 先把这块的分片并进去，再判断「攒齐了没有」，否则 arguments 尾巴会掉。
                        d.tool?.let { mergeToolChunk(it) }
                        if (d.finish == "tool_calls") toolCall = finishToolCall()
                    }
                }
            } catch (e: java.io.IOException) {
                if (cancel.get()) return out.toString() to thinkChars   // 被 abort() 掐断：正常收尾
                throw e
            }
            if (toolCall == null && chunks.isNotEmpty()) toolCall = finishToolCall()   // [WebSearch] 有些实现不给 tool_calls 收尾块
            return out.toString() to thinkChars
        } finally {
            (cancel as? Cancel)?.conn = null
            conn.disconnect()
        }
    }

    /** 检测连接：发一句极短的非流式请求，能拿到 200 就算通 */
    fun ping(): String = post(Bodies.text(cfg, "只回复两个字：在的", 24, thinkingOff = true, stream = false))
        .ifBlank { "（接口返回 200，但正文为空）" }

    // ---------------- [WebSearch] tool_calls 攒片 ----------------

    /**
     * 并一块 tool_calls 分片：arguments 按 index 往后追加，id / name 只在第一块来（后来的块这两个字段是空的，别覆盖）。
     * 只有 index 0（现在只支持一个工具）；并行调多个工具以后要按 index 分别存 id 和 name。
     */
    private fun mergeToolChunk(c: Sse.Chunk) {
        if (c.index != 0) return
        if (c.id.isNotBlank()) chunkId = c.id
        if (c.name.isNotBlank()) chunkName = c.name
        if (c.args.isNotEmpty()) chunks.getOrPut(0) { StringBuilder() }.append(c.args)
    }

    /** 攒齐了拼成一条 [ToolCall]；连工具名都没有（模型发了个空壳）→ null，交给上层当没这回事 */
    private fun finishToolCall(): ToolCall? {
        if (chunkName.isBlank()) return null
        return ToolCall(chunkId, chunkName, chunks[0]?.toString().orEmpty())
    }

    /**
     * v1.63 检测能不能看图：发一张 32×32 的「7」问是几。以前检测只发文字 —— 填了不能看图的模型也显示连通，
     * 第一次识别才报错；还有的模型收了图却当没看见，瞎答。返回 null = 能看图，否则是给人看的原因。
     */
    fun pingVision(): String? = try {
        val o = JSONObject(Bodies.vision(cfg, "图里是一个阿拉伯数字，只回复这个数字", emptyList(), 16, thinkingOff = true))
        o.put("stream", false)
        o.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$PROBE_PNG")))
        val a = post(o.toString())
        if ("7" in a) null else "模型收到了图但没认出来（回答「${a.trim().take(20)}」）—— 它可能不会看图，识别屏幕会乱答；换一个能看图的模型"
    } catch (e: Exception) {
        val m = NetErrors.friendly(e, cfg.base)
        "看图失败：" + if ("不支持看图" in m) m else "$m —— 这个模型可能不支持图片，识别屏幕用不了，换一个能看图的模型"
    }

    private fun post(body: String): String {
        val conn = open("/chat/completions", 30_000)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, conn))
            val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return try {
                JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
            } catch (e: Exception) { "" }
        } catch (e: ApiException) { throw e
        } catch (e: Exception) { throw ApiException(NetErrors.friendly(e, cfg.base))
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String, readTimeoutMs: Int): HttpURLConnection {
        if (cfg.base.isEmpty()) throw ApiException("还没填接口地址（设置 → 接口地址）")
        val url = try { URL(cfg.base + path) } catch (e: Exception) { throw ApiException("接口地址格式不对：" + cfg.base) }
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
        return c
    }

    private fun httpError(code: Int, conn: HttpURLConnection): String {
        val body = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty() } catch (e: Exception) { "" }
        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
        val hint = when (code) {
            401, 403 -> "（Key 不对或没权限）"
            400 -> if (Regex("image|vision|multimodal|多模态|图片|图像", RegexOption.IGNORE_CASE).containsMatchIn(body)) "（这个模型可能不支持看图 —— 换一个能看图的模型）" else "（请求参数不被接受：模型名对吗？）"
            404 -> "（地址不对：一般要以 /v1 结尾）"
            413 -> "（截图太大，接口拒收）"
            429 -> "（请求太频繁或额度用完）"
            in 500..599 -> "（服务端出错，稍后再试）"
            else -> ""
        }
        return "HTTP $code$hint " + (msg.ifBlank { body.take(160) })
    }
}
