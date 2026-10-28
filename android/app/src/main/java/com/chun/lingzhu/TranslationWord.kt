package com.chun.lingzhu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 翻译窗点词（v2.9）：对齐电脑版的「悬停查词」，手机上换成点一下。
 *
 * 电脑版（`snapshots/ling-desktop-v1.63.0-base/`）的实际做法：
 *  - `translate.html:71` 的 `WORD_RE = /[A-Za-z\u00c0-\u024f][A-Za-z\u00c0-\u024f'’\-]+/g` 把译文里每个拉丁词包成
 *    `<span class="w">`，鼠标停上去（`translate.html:98-250`）弹一张小卡；
 *  - 查词走 `main.js:3076-3098` 的 `trans:word`：非流式、**关思考**、`max_tokens 700`，
 *    提示词是那份 90 字的 `wordPrompt`（释义 2~4 条 + 一句例句）；
 *  - 缓存按词存 300 条 LRU，没取到的 60 秒内不再重打。
 *
 * 这里跟电脑版有意不同的两处（需求要的更多，不是抄少了）：
 *  1. **提示词更全**：电脑版悬停卡只有「音标 + 2~4 释义 + 1 例句」，用户要的是
 *     「译义 / 解析 / 双语例句 / 常见搭配及用法」，所以内容骨架对齐电脑版**整段解析**里的
 *     `WORD_EXPLAIN_ITEMS`（`prompts.js:133-140`：音标、词性与词形、常见义项、常见搭配、易混辨析、两句例句），
 *     而不是那份 90 字悬停卡；预算随之放宽到 [CARD_MAX_TOKENS]（仍是一个词的小活）。
 *     兜底那句短译仍用 [WordDict.fallbackPrompt]（与电脑版逐字一致）。
 *  2. **缓存带语境**：电脑版只按词存（换模型 / 换目标语言会拿到旧义项 —— 那边注释自己也认了这是坑）。
 *     这里键 = 词 + 目标语言 + 接口/模型 + 提示词版本 + **点中这个词的那句原文**，同一个词在不同语境里
 *     不会串义项（[cacheKey]）。
 *
 * 缓存走 [Cache.translateStore]（30 天，与译文同一个库），键由 [cacheKey] 算：词 + 目标语言 + 接口/模型 +
 * 提示词版本 + 语境句。**不**复用 [WordDict.Store] —— 那份只按词存，塞不进语境，会把「河岸」当成「银行」。
 *
 * 这个文件不碰 Android 界面（纯逻辑 + 一次 HTTP），单测直接跑；界面在 WordTapMovement.kt。
 */
object TranslationWord {

    /** 与电脑版 `translate.html:71` 的 WORD_RE 逐字对齐：拉丁字母开头，后面可以有字母 / 撇号 / 连字符 */
    val WORD = Regex("[A-Za-z\\u00c0-\\u024f][A-Za-z\\u00c0-\\u024f'’\\-]+")

    /** 文本里可点的一个词：[start]~[end] 是它在原文里的区间（end 不含） */
    data class Hit(val start: Int, val end: Int, val word: String)

    /** 词卡提示词版本：改 [CARD_HEAD] 就 +1，旧词卡不再命中（见 [cacheKey]） */
    const val CARD_VERSION = 1

    /**
     * 词卡的输出预算。电脑版悬停卡 700 token 够 90 字；这里要 4 类内容（义项 / 词形 / 搭配 / 两句例句），
     * 700 会被拦腰截断，所以放宽到 1200 —— 仍是「一个词」的小活，不是整段富解析。
     */
    const val CARD_MAX_TOKENS = 1200

    /**
     * 「只翻译」提示词版本（v2.9）。改 [plainPrompt] 就 +1：翻译缓存键带它，
     * 否则旧提示词下的译文会继续命中（见 TranslateActivity.trKey）。
     *
     * 为什么放在这个新文件而不是 [Prompts]：这是这一轮新开的一条口径（只给译文），
     * 和已有的 [Prompts.translate]（译文 + 完整解析）、[Prompts.translateEnrich]（只补解析）都不冲突；
     * 放在这里，共享提示词与 `TRANSLATE_PROMPT_VERSION` 一个字都不用动。
     */
    const val PLAIN_VERSION = 1

    /**
     * 只给译文的提示词（AI 当翻译引擎、以及机器翻译都失败后的 AI 兜底走这条）。
     *
     * 以前走 [Prompts.translate]：那份提示词要求「词性 / 义项 / 搭配 / 两句例句 / ## 要点」，
     * 用户点「翻译」默认拿到一大段解析 —— 需求点名要改掉这种「伪装成只翻译」。
     * 解析还在，只是改由用户点「AI 解析」主动触发（[Prompts.translateEnrich]）。
     *
     * 目标语言的规则仍复用 [Prompts.LANGS]（语言按钮 / 设置里那套），不另写一份。
     */
    fun plainPrompt(text: String, langId: String): String =
        "你是专业翻译。翻译方向：" + Prompts.lang(langId).rule + "。\n" +
            "只输出译文：不要解析、不要列义项、不要标词性、不要给例句或搭配，也不要写「## 要点」这类段落；\n" +
            "译文放在最前面，不要「译文：」这类前缀，不要寒暄；不编原文没有的信息。\n\n原文：\n" + text

    /**
     * 点词卡的提示词。四类内容按用户要的顺序排：译义 → 解析（词性/词形/音标）→ 搭配与用法 → 双语例句；
     * 语境（点中这个词的那句原文）必须带上，模型才会给**这一句里**的义项，而不是把词典全文照抄一遍。
     */
    const val CARD_HEAD = "你是双语词典，也负责讲清一个词怎么用。用户点了译文里的一个词，用中文输出，不要寒暄、不要重复原词：\n" +
        "① 译义：这个词在当前语境下最贴切的中文意思，1~2 条；确实还有常用义项再补一行。\n" +
        "② 解析：词性（n. / v. / adj. …）和词形变化（复数、过去式、比较级等，有才写）；原文是英文且有把握时才给 /音标/，拿不准就不给。\n" +
        "③ 搭配与用法：2~4 个高频搭配或固定说法（原词 + 中文），再用一句话说清它在这个句子里怎么用。\n" +
        "④ 例句：两句，每行一句，写成「原语言 —— 中文」，要短、贴近日常。\n" +
        "每个小点各占一行，不要写成一大段；没有的小点直接省掉，但译义和两句例句必须有。不编音标、词源、数据，不写与这个词无关的段落。\n"

    /** 拼一份词卡提示词；语境为空时不硬塞一行空标题 */
    fun prompt(word: String, context: String): String {
        val c = context.trim().take(400)
        return CARD_HEAD + (if (c.isEmpty()) "" else "语境（原文）：\n" + c + "\n\n") + "查询：" + word
    }

    /**
     * 文本里所有可点的词（纯函数）。[blocked] 是不能再加词 span 的区间 —— 链接、行内代码、公式
     * （界面从已经排好的 Spannable 上收出来，见 WordTap.mark）。
     * 超过 [WordDict.MAX_LEN] 的长串（base64、哈希、URL 残片）不当词；行尾被断开的 `well-` 只查 `well`。
     */
    fun hits(text: String, blocked: List<IntRange> = emptyList()): List<Hit> {
        val out = ArrayList<Hit>()
        for (m in WORD.findAll(text)) {
            val raw = m.value.trimEnd('-', '\'', '’')
            if (raw.length < 2 || raw.length > WordDict.MAX_LEN) continue
            val start = m.range.first
            val end = start + raw.length
            if (blocked.any { b -> start <= b.last && b.first <= end - 1 }) continue
            out.add(Hit(start, end, raw))
        }
        return out
    }

    /**
     * 按点中的字符 offset 取词（需求「按点中 offset 取词」）：offset 必须落在词区间内。
     * 不做「词尾右侧也算」的放宽 —— 词后那个空格 / 标点的 offset 正好等于词尾，
     * 放宽之后在空白处点一下也会去查词（电脑版是 hover，没这个歧义；手机上宁可少查一次）。
     * 空白、标点、汉字一律 null。
     */
    fun wordAt(text: String, offset: Int): Hit? {
        if (offset < 0 || offset >= text.length) return null
        return hits(text).firstOrNull { offset >= it.start && offset < it.end }
    }

    /**
     * 点中的词所在的那一句（给提示词当语境，也进缓存键）：按句末标点切，取包含这个词的那段。
     * 一行里没有句末标点就用整行（常见的字幕 / 标题就是一行一句）。
     */
    fun sentenceAt(text: String, hit: Hit): String {
        if (text.isEmpty()) return ""
        val from = hit.start.coerceIn(0, text.length)
        var a = from
        while (a > 0 && !isBreak(text[a - 1])) a--
        var b = hit.end.coerceAtMost(text.length)
        while (b < text.length && !isBreak(text[b])) b++
        // 句末那个标点也算这一句的（语境看着才完整）；只多吃一个字符，不会吞掉下一句
        if (b < text.length && text[b] != '\n') b++
        return text.substring(a, b).trim()
    }

    private fun isBreak(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == ';' || c == '；' ||
            c == '\n' || c == '…' || c == ':' || c == '：'

    /**
     * 「只有最新一次请求算数」的票号。
     *
     * 点词是异步的：用户点了 A、又点 B，A 的请求晚回来 —— 不许盖掉 B 的画面；
     * 换语种重翻、关卡片、退出 Activity 同理。界面每次发起请求取一张票，
     * 回来时用 [isCurrent] 验票，过期的结果直接丢掉。纯逻辑，单测直接测。
     */
    class Latest {
        private var n = 0
        @Synchronized fun next(): Int { n += 1; return n }
        /** 作废所有在途请求（关卡片 / 换语种 / 退出） */
        @Synchronized fun invalidate() { n += 1 }
        @Synchronized fun isCurrent(ticket: Int): Boolean = ticket == n
    }

    /**
     * 词卡缓存键：词（归一后）+ 目标语言 + 接口/模型 + 提示词版本 + 语境。
     * 语境取点中这个词的整句原文（去掉多余空白、截 400 字）—— 同一个词在另一句里就是另一条，
     * 免得把 `bank` 的「河岸」拿去解释「银行」。走 [Cache.key]（sha256），不会留下长键。
     */
    fun cacheKey(word: String, lang: String, model: String, context: String): String = Cache.key(
        "word",
        "v" + CARD_VERSION,
        WordDict.normalize(word),
        lang,
        model,
        context.trim().replace(Regex("\\s+"), " ").take(400),
    )

    /**
     * 一次非流式取词（电脑版 `wordAsk` 同款）：关思考、拿 `choices[0].message.content`。
     *
     * 为什么不用 [ApiClient].stream：那是给 SSE 用的（[Sse.parseLine] 只认 `data:` 行），
     * 非流式响应体是一整块 JSON，读不出东西。所以这里照电脑版发一次 `stream:false` 的请求，
     * 请求体仍由 [Bodies.text] 组装（思考 / 温度这些开关与别处一致）。
     *
     * 取消 = [abort]：断掉正在等的连接，工作线程立刻回来 —— 换词、关卡片都不会让旧请求继续占着带宽。
     * 失败一律抛 [ApiException]，消息是**真原因**（状态码 + 接口原话 + 一句人话提示），
     * 界面据此给出可重试的提示，绝不编一个本地假解释。
     */
    class Call {
        private val cancel = AtomicBoolean(false)
        @Volatile private var conn: HttpURLConnection? = null

        fun abort() {
            cancel.set(true)
            val c = conn ?: return
            // 断连接可能碰网络，别在 UI 线程做
            Thread { try { c.disconnect() } catch (_: Exception) {} }.start()
        }

        fun aborted(): Boolean = cancel.get()

        fun ask(cfg: ApiConfig, prompt: String, maxTokens: Int): String {
            if (cancel.get()) return ""
            val routed = CostPolicy.route(CostPolicy.Task.TRANSLATE, cfg)
            if (routed.base.isEmpty()) throw ApiException("还没填接口地址（设置 → 接口地址）")
            val body = Bodies.text(routed, prompt, maxTokens, thinkingOff = true, stream = false)
            val url = try { URL(routed.base + "/chat/completions") } catch (_: Exception) { throw ApiException("接口地址格式不对：" + routed.base) }
            val c = url.openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Authorization", "Bearer " + routed.apiKey)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw ApiException("接口 $code" + hint(code) + "：" + snippet(text))
                val msg = try {
                    JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                } catch (_: Exception) {
                    throw ApiException("接口返回的不是 JSON：" + text.replace(Regex("\\s+"), " ").take(100))
                }
                val content = msg?.optString("content").orEmpty().trim()
                if (content.isEmpty() && !msg?.optString("reasoning_content").isNullOrBlank()) {
                    throw ApiException("模型只输出了思考内容（思考把输出预算吃光了），换个模型或稍后再点一次")
                }
                return content
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                if (aborted()) return ""
                throw ApiException(NetErrors.friendly(e, routed.base))
            } finally {
                conn = null
                c.disconnect()
            }
        }

        private fun hint(code: Int): String = when (code) {
            401, 403 -> "（Key 不对或没权限）"
            404 -> "（地址不对：一般要以 /v1 结尾）"
            429 -> "（请求太频繁或额度用完）"
            in 500..599 -> "（服务端出错，稍后再试）"
            else -> ""
        }

        private fun snippet(body: String): String {
            val m = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
            return (m.ifBlank { body }).replace(Regex("\\s+"), " ").take(120).ifBlank { "接口没返回内容" }
        }
    }

    /**
     * AI 当翻译引擎时的一次**纯翻译**（v2.9）：只给译文，不自动附带词性 / 义项 / 例句。
     *
     * 以前这条路走 [Solver.translate] → [Prompts.translate]，提示词本身要求完整语言点解析，
     * 于是「只翻译」的默认档一上来就是一大段解析 —— 用户点名要改掉这种「伪装成只翻译」。
     * 整段解析改由用户点「AI 解析」显式触发（见 [Prompts.translateEnrich]）。
     *
     * 与 [Solver] 同一条取舍：预算 [Prompts.TRANSLATE_BUDGET]，接口上限更小时 400 会报 max_tokens，
     * 退回 4096 再来一次。
     */
    fun translate(cfg: ApiConfig, text: String, langId: String, cb: Solver.Callbacks, cancel: AtomicBoolean): String {
        val routed = CostPolicy.route(CostPolicy.Task.TRANSLATE, cfg)
        val prompt = plainPrompt(text, langId)
        fun once(budget: Int, thinkingOff: Boolean): String =
            ApiClient(routed).stream(Bodies.text(routed, prompt, budget, thinkingOff), listenerOf(cb), cancel).first
        return try {
            once(Prompts.TRANSLATE_BUDGET, false)
        } catch (e: ApiException) {
            if (!Prompts.isMaxTokensError(e.message)) throw e
            once(4096, true)
        }
    }

    private fun listenerOf(cb: Solver.Callbacks) = object : ApiClient.Listener {
        override fun onThinking(chars: Int) { cb.onPhase("正在思考…") }
        override fun onText(text: String) { cb.onText(text) }
        override fun onUsage(prompt: Int, completion: Int) { cb.onUsage(prompt, completion) }
    }
}
