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
data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val thinkingEffort: String = "high",
    /** 联网搜索配置：整轮不可变快照，不再有全局 engine / enabled */
    val searchConfig: WebSearch.Config = WebSearch.Config(),
    /**
     * v2.3.3「允许自定义 Agent」：当前 Agent 能用的本机工具白名单。
     *
     * **空集合 = 全开**（见 [AgentScope]）。放在 ApiConfig 上而不是另开参数，
     * 是因为请求体组装、工具执行、以及"要不要给这个工具"三处判断都必须看到同一份快照 ——
     * 中途用户切了 Agent，这一轮也不该半路换权限。
     */
    val agentScope: Set<String> = AgentScope.ALL,
    /**
     * v2.4 模块门控：这一轮用户关掉的功能 id 集合（[Prefs.disabledFeatures] 的快照）。
     *
     * 和 [agentScope] 同理由放在这里：请求体（发哪些工具）、执行（收不收）两处必须看到同一份，
     * 否则用户在中途去「模块管理」关掉一个模块，这一轮就会出现「请求里没有、执行却能跑」的口子。
     * 默认空集合 = 一个都没关，纯 JVM 测试与老调用方行为不变。
     */
    val disabledFeatures: Set<String> = emptySet(),
    /**
     * v2.4：这个 Agent 的温度。**null = 不发送该字段**，用服务端默认。
     *
     * 用 null 而不是给个 0.7：一旦显式发出去，就等于替所有厂商定死了一个值，
     * 而各家的默认温度差别很大（有的 1.0，有的 0.6）。不发 = 各用各的默认，行为与升级前一致。
     */
    val temperature: Float? = null,
) {
    val base: String get() = baseUrl.trim().trimEnd('/')
    /** DeepSeek 专有的思考参数只对 deepseek 端点带 —— 别的厂商不认（OpenAI 会直接 400） */
    val isDeepSeek: Boolean get() = Regex("deepseek", RegexOption.IGNORE_CASE).containsMatchIn(baseUrl) ||
        Regex("^deepseek", RegexOption.IGNORE_CASE).containsMatchIn(model.trim())
}

open class ApiException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * 取消标记 + 当前连接（v1.63）。卡在建连 / 等首字节时关面板，请求照样挂满 90 秒，
 * abort() 直接掐断连接，阻塞的读马上抛异常返回。搜索请求也复用这个连接槽。
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
    /**
     * finish = 该块带的 finish_reason（"length" = 到了 max_tokens；"tool_calls" = 模型要调工具）；
     * usage = 真实 token 用量；tools = 这一块里的 tool_calls 分片（可能并行多个）。
     */
    data class Delta(
        val content: String,
        val reasoning: String,
        val done: Boolean,
        val finish: String = "",
        val usage: Pair<Int, Int>? = null,
        val tools: List<Chunk> = emptyList(),
    )

    /**
     * tool_calls 的一块碎片。OpenAI 的流式形状：id 和 function.name 只在第一块来，
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
            val u = j.optJSONObject("usage")?.let {
                val p = it.optInt("prompt_tokens", -1); val c = it.optInt("completion_tokens", -1)
                if (p >= 0 && c >= 0) p to c else null
            }
            val choices = j.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return Delta("", "", false, usage = u)
            val c0 = choices.getJSONObject(0)
            val finish = optStr(c0, "finish_reason")
            val d = c0.optJSONObject("delta") ?: c0.optJSONObject("message") ?: return Delta("", "", false, finish, u)
            Delta(optStr(d, "content"), optStr(d, "reasoning_content"), false, finish, u, toolChunks(d))
        } catch (e: Exception) {
            Delta("", "", false)
        }
    }

    /** 一块 delta 里的所有 tool_calls（并行调多个工具时按 index 分别攒，不能只留第一个） */
    private fun toolChunks(d: JSONObject): List<Chunk> {
        val arr = d.optJSONArray("tool_calls") ?: return emptyList()
        if (arr.length() == 0) return emptyList()
        val out = ArrayList<Chunk>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val f = o.optJSONObject("function")
            out.add(Chunk(
                o.optInt("index", i),
                optStr(o, "id"),
                if (f == null) "" else optStr(f, "name"),
                if (f == null) "" else optStr(f, "arguments"),
            ))
        }
        return out
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

    /**
     * 请求体挂工具表。
     *
     * v2.3.1 起不再是「搜索开了才挂」：[AgentTools] 的本机工具（建卡 / 提醒 / 查记忆 / 报账）
     * 与联网开关无关，任何时候都该在。否则用户关掉联网，整条工具链就跟着消失了 ——
     * 他跟 AI 说「帮我记一张卡」，AI 只能回一句「好的我记住了」，其实什么都没发生。
     *
     * 联网工具才跟开关走：session 不活跃（没配引擎 / 关着）时不追加 web_search / web_fetch；
     * canFetch 为 false 时只给 web_search，和原来一样。
     */
    fun tools(cfg: ApiConfig, into: JSONObject) {
        tools(cfg, into, local = true)
    }

    /**
     * [local] = 要不要挂 AgentTools 的本机工具。默认挂；[Bodies.vision] 这条**识屏**路径不挂。
     *
     * 为什么识屏不挂：识屏的主线是"看懂这道题并作答"，那一轮的输出预算全给了推理与正文，
     * 首轮再逼它 required 调一次工具，很容易只吐一句"我先记下来"就把答案挤掉；
     * 建卡 / 提醒这类动作本来也是用户在聊天里发起的，识屏页只需要联网搜索。
     * 真要给识屏加本机工具，改这里一处并在页面明确提示即可。
     */
    fun tools(cfg: ApiConfig, into: JSONObject, local: Boolean) {
        val arr = JSONArray()
        if (local) {
            // 按当前 Agent 的白名单 + 用户关掉的模块过滤（空白名单 = 全开，老行为不变）
            val mine = JSONArray(AgentTools.toolsJson(cfg.agentScope, cfg.disabledFeatures))
            for (i in 0 until mine.length()) arr.put(mine.get(i))
        }
        val session = WebSearch.session(cfg.searchConfig, cfg)
        if (session.active) {
            val web = JSONArray(WebSearch.toolsJson(session.canFetch))
            for (i in 0 until web.length()) arr.put(web.get(i))
        }
        // 空工具表就不写这个字段：不少 OpenAI 兼容网关见到 "tools": [] 会直接 400
        if (arr.length() > 0) into.put("tools", arr)
    }

    /**
     * tool_choice。
     *
     * 默认 auto：挂着的工具是「有能力动手」而不是「必须动手」，用户随口聊两句不该被逼着建卡。
     * Agent 模式的**首轮**用 required（见 [streamWithSession] 的 agentMode 参数）：
     * 那一轮用户就是冲着「帮我办件事」来的，模型很容易回一句「好的，我来帮你安排」的空话就收尾 ——
     * required 逼它先动一次手，之后各轮再回到 auto（见 [streamWithSession] 的工具循环），它就能自然地收尾。
     *
     * **思考模式开着时永远不发 required**：[thinkingEnabled] 为真就只写 auto。
     * DeepSeek 的规定是「思考模式下 required / 指定工具都不支持，直接 400」
     * （`Thinking mode does not support this tool_choice`）。这个组合以前正是聊天首轮的默认态
     * （Prefs 思考默认 high + 聊天恒挂 tools + agentMode=true），用户每说一句就被 400 打回。
     * 取舍是「保留工具 + 保留用户的思考强度」而不是二选一：auto 下模型照样能自己点工具，
     * 只是不再被逼第一句就动手 —— 这比关掉思考（丢掉推理质量）或去掉工具（丢掉建卡 / 提醒 / 联网）都好。
     *
     * 只有真的挂了工具才写这字段：没工具却带 tool_choice，有些实现会直接报参数错。
     */
    fun toolChoice(into: JSONObject, force: Boolean = false) {
        if (!into.has("tools")) return
        into.put("tool_choice", if (force && !thinkingEnabled(into)) "required" else "auto")
    }

    /** 这个请求体是不是真的开着思考 —— 以**体里实际写下的** `thinking.type` 为准，不看配置推测。 */
    fun thinkingEnabled(into: JSONObject): Boolean =
        into.optJSONObject("thinking")?.optString("type") == "enabled"

    /**
     * 在请求体**字符串**上设 tool_choice —— Agent 模式首轮要 required，而 body 是调用方拼好传进来的。
     * 解析不了就原样返回：宁可少这一次 required，也不要拿坏体去发（同 withoutTools 的取舍）。
     */
    fun withToolChoice(body: String, force: Boolean): String = try {
        val o = JSONObject(body)
        toolChoice(o, force)
        if (o.has("tool_choice")) o.toString() else body
    } catch (_: Exception) { body }

    /**
     * 去掉请求体里的工具（v2.2.1）：搜索/抓取额度或工具轮次已经用尽时，下一轮不再给工具，
     * 逼模型用现有资料收尾。以前是不去工具、模型再点一次就整题报错。
     * 解析失败就原样返回（宁可多一轮工具，也不要拿坏请求体去发）。
     */
    fun withoutTools(body: String): String = try {
        val o = JSONObject(body)
        o.remove("tools")
        o.remove("tool_choice")
        o.toString()
    } catch (_: Exception) { body }

    /**
     * 工具续轮：把「assistant 认下这次调用」和每条 tool 结果接在原请求体后面。
     *
     * DeepSeek 思考模式 + tools 的硬要求：历史轮次的 `reasoning_content` 必须原样回传，
     * 否则模型会丢失上一轮的思考上下文；assistant 消息里的 `tool_calls` 也必须**每条**都带上，
     * 并给每个 id 一条对应的 tool 消息。`content` 调工具那一轮没正文时用 null 而不是 ""。
     *
     * 原 body 解析不了就原样返回（宁可让这一轮按没搜到走，也不要拿一个坏体去请求）。
     */
    fun withToolRound(
        body: String,
        reasoning: String,
        assistantText: String,
        calls: List<ToolCall>,
        results: List<String>,
        includeReasoning: Boolean = true,
    ): String = try {
        val o = JSONObject(body)
        val msgs = o.optJSONArray("messages") ?: JSONArray()
        val ids = calls.mapIndexed { i, c -> c.id.ifBlank { "call_$i" } }

        val a = JSONObject()
            .put("role", "assistant")
            .put("content", if (assistantText.isBlank()) JSONObject.NULL else assistantText)
        if (includeReasoning && reasoning.isNotBlank()) a.put("reasoning_content", reasoning)
        val tc = JSONArray()
        for ((i, call) in calls.withIndex()) {
            tc.put(JSONObject()
                .put("id", ids[i])
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", call.name)
                    .put("arguments", call.args.ifBlank { "{}" })))
        }
        a.put("tool_calls", tc)
        msgs.put(a)

        for (i in calls.indices) {
            msgs.put(JSONObject()
                .put("role", "tool")
                .put("tool_call_id", ids[i])
                .put("content", results.getOrElse(i) { "" }))
        }
        o.put("messages", msgs).toString()
    } catch (e: Exception) { body }

    /**
     * 识别：系统提示 + 截图放在同一条 user 消息里（同桌面 chatBody）。
     * turns（v1.57 追问）：接在后面的多轮对话 (role, 文字)，assistant / user 交替，最后一条是这次的追问。
     */
    fun vision(
        cfg: ApiConfig,
        prompt: String,
        jpegB64: List<String>,
        maxTokens: Int,
        thinkingOff: Boolean,
        turns: List<Pair<String, String>> = emptyList(),
        withTools: Boolean = false,
    ): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        for (b in jpegB64) {
            // detail=high：识别质量直接吃这一口。不写的话不少 OpenAI 兼容实现按 auto/low 处理，
            // 会把整屏缩到小图再认 —— 题目里的小字号数字、上下标、公式一缩就糊。
            content.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$b").put("detail", "high")))
        }
        val o = JSONObject()
            .put("model", cfg.model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)).apply {
                for ((role, t) in turns) put(JSONObject().put("role", role).put("content", t))
            })
        thinking(cfg, thinkingOff, o)
        temp(cfg, o)
        // 识屏只给联网工具：本机工具（建卡 / 提醒）是聊天里发起的事，见 tools(local) 的注释
        if (withTools) { tools(cfg, o, local = false); toolChoice(o) }
        return o.toString()
    }

    fun text(
        cfg: ApiConfig,
        prompt: String,
        maxTokens: Int,
        thinkingOff: Boolean,
        stream: Boolean = true,
        withTools: Boolean = false,
    ): String {
        val o = JSONObject()
            .put("model", cfg.model)
            .put("stream", stream)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        thinking(cfg, thinkingOff, o)
        temp(cfg, o)
        if (withTools) { tools(cfg, o); toolChoice(o) }
        return o.toString()
    }

    /**
     * v2.4：温度只在 Agent 单独设过时才发。
     *
     * 不发 ≠ 发 0：省略这个字段，各家服务端用自己的默认值；发 0 会让模型变成复读机。
     * 所以这里必须是「有值才写」，不能用 cfg.temperature ?: 0.7 这种兜底。
     */
    fun temp(cfg: ApiConfig, into: JSONObject) {
        cfg.temperature?.let { into.put("temperature", it.toDouble()) }
    }
}

class ApiClient(private val cfg: ApiConfig) {
    companion object {
        var RETRY_WAIT_MS = 1500   // 单测里调短
        /**
         * 一整轮对话最多跑几轮工具。
         *
         * 取 [WebSearch.TOOL_ROUNDS_MAX]（搜索 + 抓取额度之和）：这条循环本来就是为联网工具定的轮次，
         * 本机工具（建卡 / 提醒 / 查记忆 / 报账）只是在同一个循环里搭车，**不该放宽联网的轮次**，
         * 否则一条"搜一次就够"的问题会被允许多跑几轮，白花钱。
         * 把名字提到这里，只是让 AgentTools 不必反向依赖 WebSearch 的额度常量。
         */
        const val TOOL_ROUNDS_MAX = WebSearch.TOOL_ROUNDS_MAX
        /** 工具轮正文先攒到这么多字再往外放（见 [shouldFlushRound]） */
        const val ROUND_FLUSH_CHARS = 24
        /** 32×32 灰度 PNG，黑色的「7」 */
        const val PROBE_PNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAAAAABWESUoAAAAP0lEQVR42mP4TwAwDCkFDOhgABSguoxSBUjy5ClAlidLAYo8OQpQ5clQgCZPugJ0eZIVYMiTqgBTnlQFA5OzAGgvcLoLH2d6AAAAAElFTkSuQmCC"

        /**
         * 这个 400 是不是「思考模式下不接受强制 tool_choice」。DeepSeek 的原话是
         * `Thinking mode does not support this tool_choice`。
         *
         * 单独一个精确判定而不是复用 [Prompts.isToolsUnsupported]：后者连普通的「不认 tools」也命中，
         * 命中的处理是**去掉工具**重答 —— 那是识屏那条线的正确取舍，放这里等于把建卡 / 提醒 / 联网砍掉。
         * 这个判定只用来把 400 的文案说准；真正的修法是 [Bodies.toolChoice] 根本不在思考模式下发 required。
         */
        fun isThinkingToolChoiceConflict(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            val m = message.lowercase()
            val thinking = m.contains("thinking") || m.contains("reasoning") || message.contains("思考")
            return thinking && (m.contains("tool_choice") || m.contains("tool choice") || message.contains("工具选择"))
        }
    }

    interface Listener {
        fun onThinking(chars: Int) {}
        fun onText(text: String)
        /**
         * 真实的思考增量（`reasoning_content` 的**原文**，不是字数）。
         *
         * 默认空实现：不显示思考过程的调用方（聊天、翻译、单测）一行都不用改。
         * 与 [onThinking] 并存 —— 那个只报累计字数（老口径），这个给界面做真流式。
         * **绝不进正文**：答案只走 [onText]，思考文本不会混进 raw / 历史 / 缓存。
         */
        fun onReasoning(delta: String) {}
        /** 接口上报的真实 token 用量（prompt to completion）；流里没有就不回调 */
        fun onUsage(prompt: Int, completion: Int) {}
        /**
         * 这一轮模型开始吐 tool_calls —— 也就是说这一轮已经出去的正文都只是「我先查一下」这类前言，
         * 不是答案（v2.2.1，搜索开着时必须区分）。
         */
        fun onToolsStarted() {}
        /**
         * 撤回：之前已经外放的正文作废，界面上要清掉（v2.2.1）。
         * 只有「正文已经放出去之后才发现这是工具轮」才会触发 —— 没放出去的前言直接丢，不需要撤回。
         */
        fun onDiscard() {}
    }

    /** 上一次 stream 是不是因为到了 max_tokens 被截断（finish_reason=length） */
    var truncated = false
        private set

    /** 上一次 stream 结束时模型点的工具（finish_reason=tool_calls）。没点就是空表。每次 [stream] 开头重置 */
    var toolCalls: List<ToolCall> = emptyList()
        private set

    /** 上一次 stream 的完整 reasoning_content —— 思考模式续轮必须原样回传 */
    var lastReasoning: String = ""
        private set

    /** 这一轮流里攒到的 tool_calls 分片，按 index 归并（id / name 只在第一块，arguments 分片追加） */
    private val chunks = LinkedHashMap<Int, StringBuilder>()
    private val chunkIds = HashMap<Int, String>()
    private val chunkNames = HashMap<Int, String>()

    fun stream(
        body: String,
        listener: Listener,
        cancel: AtomicBoolean = AtomicBoolean(false),
        readTimeoutMs: Int = 90_000,
    ): Pair<String, Int> {
        resetTools()
        // v1.63：还没吐出任何字时遇到网络抖动 / 429 / 5xx，隔 1.5 秒自动再试一次（已经出字了就不重试，免得重复）
        var emitted = false
        val l = object : Listener {
            override fun onThinking(chars: Int) { emitted = true; listener.onThinking(chars) }
            override fun onText(text: String) { emitted = true; listener.onText(text) }
            override fun onReasoning(delta: String) { listener.onReasoning(delta) }
            override fun onUsage(prompt: Int, completion: Int) { listener.onUsage(prompt, completion) }
            override fun onToolsStarted() { emitted = true; listener.onToolsStarted() }
            override fun onDiscard() { listener.onDiscard() }
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
     * 搜索开启时的完整一轮：模型可连续调 web_search / web_fetch，最多 [WebSearch.TOOL_ROUNDS_MAX] 轮工具，
     * 每类各 [WebSearch.SEARCHES_PER_SOLVE] / [WebSearch.FETCHES_PER_SOLVE] 次。
     *
     * 关键行为：
     *  - 整轮用同一个 [WebSearch.Session]（配置 / 计数 / 失败记录都不中途换）；
     *  - 工具轮的正文**不往外拼** —— 最终只返回最后那次回答，不把「我先搜一下」当答案；
     *  - 工具轮次用尽模型仍在要工具、或搜索接不回请求体：抛 [ApiException]，同样不把前言当答案；
     *  - 搜索失败和没结果分别给模型提示，并在最终凭记忆作答时追加可见说明；
     *  - 取消时立刻返回，搜索连接由 [Cancel] 直接掐断。
     */
    fun streamWithSearch(
        body: String,
        listener: Listener,
        cancel: AtomicBoolean,
        readTimeoutMs: Int = 90_000,
        fallbackQuery: String = "",
        onPhase: (String) -> Unit = {},
        tools: ToolContext? = null,
        agentMode: Boolean = false,
    ): Pair<String, Int> = streamWithSession(
        body, listener, cancel, readTimeoutMs, fallbackQuery,
        WebSearch.session(cfg.searchConfig, cfg), onPhase, tools, agentMode,
    )

    /** 可注入 [WebSearch.Session] 的版本（测试用本地假服务替换搜索端点） */
    fun streamWithSession(
        body: String,
        listener: Listener,
        cancel: AtomicBoolean,
        readTimeoutMs: Int = 90_000,
        fallbackQuery: String = "",
        session: WebSearch.Session,
        onPhase: (String) -> Unit = {},
        /** 本机工具的运行环境。null = 这一路不接工具（老的 [stream] 路径），请求体里就算挂了 tools 也不执行 */
        tools: ToolContext? = null,
        /**
         * 首轮用 tool_choice=required 逼模型真的动手，见 [Bodies.toolChoice]。
         * 思考开着时这一条**自动失效**（仍旧 auto）：接口不允许这个组合，详见 [Bodies.toolChoice] 的注释。
         */
        agentMode: Boolean = false,
    ): Pair<String, Int> {
        // 联网没开，但本机工具在：还是要走工具循环，否则「帮我记一张卡」永远不会真的建卡。
        // 只有两边都没有（老调用方、没开搜索、没给 ToolContext）才退回单轮 stream。
        if (!session.active && tools == null) return stream(body, listener, cancel, readTimeoutMs)

        // Agent 模式首轮用 required：这一轮用户是冲着「帮我办件事」来的，auto 下模型很容易
        // 回一段「好的，我来帮你安排」的空话就收尾。required 逼它先动一次手，后面几轮回到 auto。
        // 只有真挂了工具才写这个字段（toolChoice 内部会检查），没工具却带 tool_choice 有些实现会 400。
        // 请求体里思考是 enabled 时 required 会被 [Bodies.toolChoice] 自动降成 auto —— 接口不允许那个组合。
        var currentBody = if (agentMode) Bodies.withToolChoice(body, force = true) else body

        var totalThink = 0
        var finalText = ""
        var toolRounds = 0
        var usageP = 0
        var usageC = 0
        var hasUsage = false

        while (true) {
            if (cancel.get()) return "" to totalThink
            // v2.2.1 恢复流式：正文不再整段攒着等「这轮没工具」才放出去（那样首字要等整段生成完）。
            // 现在攒到 [ROUND_FLUSH_CHARS] 字或出现换行就先外放；万一之后才发现是工具轮，
            // 已经放出去的前言用 [Listener.onDiscard] 撤回，没放出去的直接丢。
            val pending = StringBuilder()
            var committed = false
            var toolStarted = false
            val round = stream(currentBody, object : Listener {
                override fun onThinking(chars: Int) {
                    // 第二轮起不再累加：工具轮会把上一轮的 reasoning_content 原样回传给模型，
                    // 模型通常把已经想过的东西再想一遍，累加出来就是「字数只涨不停、原地打转」。
                    if (toolRounds > 0) return
                    listener.onThinking(totalThink + chars)
                }
                // 工具轮里思考只往上透传一次；[onToolsStarted] 之后这一轮的思考已结束，上层自己收口。
                // 第 2 轮起整段不再外放：那些思考是上一轮的复述（原因同 [onThinking]），
                // 继续往下发只会让面板把同一段话接在上一段后面，看着像陷进循环。
                override fun onReasoning(delta: String) {
                    if (toolStarted || toolRounds > 0) return
                    listener.onReasoning(delta)
                }
                override fun onToolsStarted() {
                    toolStarted = true
                    if (committed) listener.onDiscard()
                    committed = false
                    pending.setLength(0)
                }
                override fun onText(text: String) {
                    if (toolStarted) return   // 工具轮之后的正文不是答案，等下一轮
                    pending.append(text)
                    if (committed || shouldFlushRound(pending)) {
                        committed = true
                        listener.onText(pending.toString())
                        pending.setLength(0)
                    }
                }
                override fun onUsage(prompt: Int, completion: Int) {
                    if (!hasUsage) { usageP = 0; usageC = 0; hasUsage = true }
                    usageP += prompt; usageC += completion
                }
            }, cancel, readTimeoutMs)
            totalThink += round.second
            if (cancel.get()) return "" to totalThink

            val calls = toolCalls
            if (calls.isEmpty()) {
                finalText = round.first
                // 收尾：短答案可能一直没到外放阈值，这里补放出去
                if (!toolStarted && pending.isNotEmpty()) {
                    listener.onText(pending.toString())
                    pending.setLength(0)
                }
                break
            }

            if (toolRounds >= TOOL_ROUNDS_MAX) {
                // 工具轮次已给足（最后一轮已经不带 tools），模型还在点名调工具：这一轮的正文只是
                // 「我先查一下」这类前言，不能当最终答案放出去。
                onPhase("工具调用已达上限")
                throw ApiException("工具调用次数已达上限（" + TOOL_ROUNDS_MAX + " 轮），但模型仍在请求调用工具，没有给出最终回答")
            }
            toolRounds++

            val results = ArrayList<String>(calls.size)
            for (call in calls) {
                when (call.name) {
                    WebSearch.TOOL_SEARCH -> {
                        val q = WebSearch.queryFromArgs(call.args, fallbackQuery)
                        if (session.searches >= WebSearch.SEARCHES_PER_SOLVE) {
                            results += WebSearch.budgetText("搜索")
                        } else {
                            onPhase(if (q.isBlank()) "正在联网搜索…" else "正在联网搜索「$q」…")
                            val out = session.search(q, cancel)
                            when (out) {
                                is WebSearch.Outcome.Failed -> onPhase("联网搜索失败：${out.message}")
                                is WebSearch.Outcome.Empty -> onPhase("联网搜索没有找到结果")
                                else -> {}
                            }
                            results += WebSearch.toolResultText(out)
                        }
                    }
                    WebSearch.TOOL_FETCH -> {
                        val url = WebSearch.urlFromArgs(call.args)
                        if (session.fetches >= WebSearch.FETCHES_PER_SOLVE) {
                            results += WebSearch.budgetText("抓取网页")
                        } else {
                            onPhase("正在读取网页…")
                            val out = session.fetch(url, cancel)
                            if (out is WebSearch.FetchOutcome.Failed) onPhase("网页抓取失败：${out.message}")
                            results += WebSearch.fetchResultText(out)
                        }
                    }
                    // 本机工具（建卡 / 提醒 / 查记忆 / 报账）。命中就先报一句可读进展再执行 ——
                    // 建卡要读一遍全库、报账要读整本台账，十几到上百毫秒，界面上没这句话就是干转圈。
                    else -> if (tools != null && AgentTools.hasTool(call.name)) {
                        val label = AgentTools.labelOf(call.name)
                        // v2.3.3：只发不执行等于「藏起来但还能用」—— 模型凭上下文硬调照样生效。
                        // 白名单与模块开关在 execute 里再判一次，这里只负责别把被禁工具的进展文案弹出来。
                        if (label.isNotEmpty() && AgentTools.enabled(cfg.agentScope, cfg.disabledFeatures, call.name)) {
                            onPhase(label)
                        }
                        results += AgentTools.execute(tools, call.name, call.args, cfg.agentScope, cfg.disabledFeatures)
                    } else {
                        // 两个都认不出才回「没有这个工具」，且把当前真正挂着的工具全列给模型 ——
                        // 光说「没有」它不知道还能用什么，只能放弃动手。
                        results += unknownTool(call.name)
                    }
                }
                if (cancel.get()) return "" to totalThink
            }

            val next = Bodies.withToolRound(currentBody, lastReasoning, round.first, calls, results, cfg.isDeepSeek)
            if (next === currentBody) {
                // 请求体接不上（解析失败）：这一轮带 tool_calls，正文只是前言，不能当最终答案；
                // 也不能拿原样请求体重发一遍。
                throw ApiException("联网搜索结果接不回请求（请求体解析失败），本轮没有最终回答")
            }
            // v2.2.1：额度或轮次已经用尽时，下一轮**不再给工具**，逼模型拿现有资料收尾。
            // 以前是把 tools 继续挂着，模型再点一次就整题报错（用户看到「联网工具调用次数已达上限」）。
            val outOfTools = toolRounds >= TOOL_ROUNDS_MAX ||
                (session.searches >= WebSearch.SEARCHES_PER_SOLVE && session.fetches >= WebSearch.FETCHES_PER_SOLVE)
            currentBody = if (outOfTools) {
                onPhase(if (session.searches >= WebSearch.SEARCHES_PER_SOLVE && session.fetches >= WebSearch.FETCHES_PER_SOLVE)
                    "联网额度已用完，正在整理答案…" else "工具轮次已用完，正在整理答案…")
                Bodies.withoutTools(next)
            } else {
                // 续轮必须真的回到 auto：以前 withToolRound 原样保留首轮的 required，模型第二句起
                // 还是被逼着再调一次工具（思考模式下更是每轮 400）。这里显式复位，才兑现
                // 「首轮 required、后面几轮 auto，让它自然收尾」的设计。
                Bodies.withToolChoice(next, force = false)
            }
        }

        if (hasUsage) listener.onUsage(usageP, usageC)
        val note = session.memoryNote()
        if (note.isNotEmpty() && finalText.isNotBlank()) {
            listener.onText(note)
            finalText += note
        }
        return finalText to totalThink
    }

    /**
     * 模型点名了一个谁都不认识的工具。
     *
     * 顺序要紧：先让 AgentTools 说话（它列的是本机能力，是这一版新增的主力），
     * 认不出再退回 WebSearch 的老话术。两个都认不出时把可用清单拼起来 ——
     * 只说「没有」模型不知道还能用什么，只能放弃动手。
     */
    private fun unknownTool(tool: String): String {
        if (AgentTools.hasTool(tool)) return AgentTools.unknownToolText(tool, cfg.disabledFeatures)
        val mine = AgentTools.registryFor(AgentScope.ALL, cfg.disabledFeatures).map { it.name }
        if (mine.isEmpty()) return WebSearch.unknownToolText(tool)
        return "（没有名为「" + tool.take(40) + "」的工具。本轮可用的工具：" + mine.joinToString("、") + "。）"
    }

    private fun resetTools() {
        toolCalls = emptyList()
        lastReasoning = ""
        chunks.clear(); chunkIds.clear(); chunkNames.clear()
    }

    /**
     * 这一轮正文攒到多少就先外放（v2.2.1）。
     * 太小 → 模型一开口「我先搜一下」就闪到屏幕上，然后又被撤回；太大 → 首字又变慢。
     * 24 字大约是第一句话的长度，配合「出现换行也放」足够让正常答案按句子流出。
     */
    private fun shouldFlushRound(pending: CharSequence): Boolean =
        pending.length >= ROUND_FLUSH_CHARS || pending.indexOf("\n") >= 0

    private fun streamOnce(body: String, listener: Listener, cancel: AtomicBoolean, readTimeoutMs: Int): Pair<String, Int> {
        if (cancel.get()) return "" to 0
        val conn = open("/chat/completions", readTimeoutMs)
        (cancel as? Cancel)?.conn = conn
        try {
            // 连接刚登记完、写请求体之前再确认一次取消：abort() 若已把连接掐掉，这里就不再往里写
            if (cancel.get()) throw ApiException("已取消")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(httpError(code, conn), retryable = code == 429 || code in 500..599)
            val out = StringBuilder()
            val reasoning = StringBuilder()
            var thinkChars = 0
            var toolsAnnounced = false
            truncated = false
            resetTools()
            try {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                    while (!cancel.get()) {
                        val line = r.readLine() ?: break
                        val d = Sse.parseLine(line) ?: continue
                        if (d.done) break
                        if (d.finish == "length") truncated = true
                        d.usage?.let { listener.onUsage(it.first, it.second) }
                        if (d.reasoning.isNotEmpty()) {
                            reasoning.append(d.reasoning)
                            thinkChars += d.reasoning.length
                            listener.onReasoning(d.reasoning)
                            listener.onThinking(thinkChars)
                        }
                        if (d.content.isNotEmpty()) {
                            out.append(d.content)
                            listener.onText(d.content)
                        }
                        // 顺序要紧：带 finish_reason=tool_calls 的那一块常常同时带着 arguments 的最后一段，
                        // 先把这块的分片并进去，再判断「攒齐了没有」，否则 arguments 尾巴会掉。
                        if (d.tools.isNotEmpty()) {
                            for (t in d.tools) mergeToolChunk(t)
                            // 第一片 tool_calls 就到了：告诉上层「这一轮是工具轮」——
                            // 上层据此停掉正文外放（已经放出去的前言会收到 onDiscard）
                            if (!toolsAnnounced) { toolsAnnounced = true; listener.onToolsStarted() }
                        }
                        if (d.finish == "tool_calls") toolCalls = finishToolCalls()
                    }
                }
            } catch (e: java.io.IOException) {
                if (cancel.get()) {
                    lastReasoning = reasoning.toString()
                    return out.toString() to thinkChars   // 被 abort() 掐断：正常收尾
                }
                throw e
            }
            // 有些实现不给 tool_calls 收尾块：流结束时按已攒的分片再收一次
            if (chunks.isNotEmpty()) {
                val all = finishToolCalls()
                if (all.isNotEmpty()) toolCalls = all
            }
            lastReasoning = reasoning.toString()
            return out.toString() to thinkChars
        } finally {
            (cancel as? Cancel)?.conn = null
            conn.disconnect()
        }
    }

    /** 检测连接：发一句极短的非流式请求，能拿到 200 就算通 */
    fun ping(): String = post(Bodies.text(cfg, "只回复两个字：在的", 24, thinkingOff = true, stream = false))
        .ifBlank { "（接口返回 200，但正文为空）" }

    // ---------------- tool_calls 攒片 ----------------

    /**
     * 并一块 tool_calls 分片：arguments 按 index 往后追加，id / name 只在第一块来（后来的块这两个字段是空的，别覆盖）。
     * 并行调多个工具时每个 index 各存一份。
     */
    private fun mergeToolChunk(c: Sse.Chunk) {
        if (c.id.isNotBlank()) chunkIds[c.index] = c.id
        if (c.name.isNotBlank()) chunkNames[c.index] = c.name
        if (c.args.isNotEmpty()) chunks.getOrPut(c.index) { StringBuilder() }.append(c.args)
    }

    /** 攒齐了拼成工具表；连工具名都没有（模型发了个空壳）→ 空表，交给上层当没这回事 */
    private fun finishToolCalls(): List<ToolCall> =
        chunkNames.keys.sorted().mapNotNull { i ->
            val name = chunkNames[i].orEmpty()
            if (name.isBlank()) null else ToolCall(chunkIds[i].orEmpty(), name, chunks[i]?.toString().orEmpty())
        }

    /**
     * v1.63 检测能不能看图：发一张 32×32 的「7」问是几。返回 null = 能看图，否则是给人看的原因。
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
            // 400 不能默认怪模型名：同一个 400 可能是思考×强制工具的组合非法、截图太大、参数超范围……
            // 认得出的那种就说准，认不出的只陈述事实，把接口原话放在后面让人自己判断。
            400 -> when {
                isThinkingToolChoiceConflict(body) ->
                    "（这个模型开着思考时不接受强制调用工具（tool_choice 只能 auto）—— 这是思考模式的限制，与模型名无关）"
                Regex("image|vision|multimodal|多模态|图片|图像", RegexOption.IGNORE_CASE).containsMatchIn(body) ->
                    "（这个模型可能不支持看图 —— 换一个能看图的模型）"
                else -> "（接口不接受这次请求的参数 —— 具体原因见后面的接口原话）"
            }
            404 -> "（地址不对：一般要以 /v1 结尾）"
            413 -> "（截图太大，接口拒收）"
            429 -> "（请求太频繁或额度用完）"
            in 500..599 -> "（服务端出错，稍后再试）"
            else -> ""
        }
        return "HTTP $code$hint " + (msg.ifBlank { body.take(160) })
    }
}
