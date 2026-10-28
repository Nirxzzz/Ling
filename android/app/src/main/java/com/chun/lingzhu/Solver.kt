package com.chun.lingzhu

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次识别 / 翻译的流程（纯 JVM，可单测）：
 *  - 思考把输出预算吃光、没吐出正文 → 关掉思考自动重试一次（同桌面 streamChat）
 *  - 开头的「[无题目]」标记剥掉（只在识别时）
 *
 * 联网搜索（v2.2）：配置在 [ApiConfig.searchConfig] 里，整轮只取一次快照。
 * 开着且配好时，**截图识别也带工具**（不再要求有文字才给搜索）—— 由模型自己判断
 * 「数学题直接答」还是「查最新信息」；不额外发一次视觉请求去提取搜索词。
 *
 * v2.2.1：
 *  - 搜索快照改成**每次尝试新建一份**（[newSession]）：重试以前复用旧 session，搜索次数早已记满，
 *    结果「重试那轮偷偷没联网」；
 *  - 接口不认 tools（400）时去掉工具再答一次，并在答案末尾说清「本次没联网」；
 *  - 答案末尾由 App 自己补一行「已联网 · N 条来源」，不再完全靠模型写来源行。
 */
class Solver(
    private val cfg: ApiConfig,
    /** 建一份整轮搜索快照。默认就是按当前配置建；测试注入假端点用 */
    private val newSession: () -> WebSearch.Session = { WebSearch.session(cfg.searchConfig, cfg) },
    /**
     * 本机工具的运行环境。null = 这条路径不给工具（老调用方 / 单测），行为和以前完全一样。
     *
     * Solver 本身是纯 JVM 类（没有 Context，也不该有 —— 它要能在单测里直跑），
     * 所以 Context 只能这样从外面传进来，而不是在类里 `new`。拿了别的 Context 会造成
     * 「识别一次就在别的文件夹下写一张卡」，必须让调用方明确指定是谁的工具。
     */
    private val toolCtx: ToolContext? = null,
) {

    interface Callbacks {
        fun onPhase(text: String)
        fun onText(text: String)
        /** 接口上报的真实 token 用量（截图折在 prompt_tokens 里）；接口不给就不回调 */
        fun onUsage(prompt: Int, completion: Int) {}
        /**
         * 撤回已经显示出去的前 [chars] 个字（v2.2.1）：搜索开着时，模型可能先吐一句
         * 「我先查一下」再决定调工具 —— 那句话不是答案，面板上要清掉。
         */
        fun onDiscard(chars: Int) {}

        /**
         * 真实的思考增量（provider 真吐了 `reasoning_content` 才有；没有就一次都不回调）。
         *
         * 默认空实现：不想显示思考过程的调用方（聊天 / 翻译 / 单测）不用改一行。
         * **面板只拿它做展示** —— 正文、历史、缓存、追问上下文依然只有 [onText] 一条路。
         * 时间戳由面板自己用单调钟量（从收到第一个 delta 起算），这一层只转文本、不报时间，
         * 免得两处时钟口径打架。
         */
        fun onReasoning(delta: String) {}

        /**
         * v2.3.3：本轮真正搜到的来源（含标题 / 摘要）。空表 = 这轮没联网。
         *
         * 单独开这个回调而不是让调用方去拼 `sourcesLine()`：那一行是给模型上下文和聊天落盘用的
         * 纯文本，把网址用全角空格串成一串；界面要的是「每行一条 + 可折叠」的结构化数据。
         * 默认空实现 —— 老调用方（单测 / 不显示来源的路径）一行都不用改。
         */
        fun onSources(hits: List<WebSearch.Hit>) {}
    }

    /**
     * terms = 设置「识别关键词」（v1.66，默认开）：提示词末尾带关键词清单要求
     * noShot = 设置「追问省图」（v1.67，默认开）：追问时不重发首轮截图，只带首轮回答文本 + 对话。
     * 联网搜索不再放在这里：它属于 [ApiConfig.searchConfig]，避免请求路径上出现第二份开关。
     */
    data class Options(
        val mode: String,
        val detail: String,
        val novice: Boolean,
        val customPrompt: String = "",
        val terms: Boolean = Prefs.KEYWORDS_DEFAULT,
        val noShot: Boolean = false,
    )

    fun solve(jpegB64: String, o: Options, cb: Callbacks, cancel: AtomicBoolean): String {
        // 探针快照只用来判断「这一轮有没有可用搜索」，不参与请求（真正请求时每次尝试各建一份）
        val tools = newSession().active
        val basePrompt = Prompts.system(o.mode, o.detail, o.novice, o.customPrompt, o.terms)
        val prompt = if (tools) basePrompt + WebSearch.toolPrompt() + nowLine() else basePrompt
        val budget = CostPolicy.solveBudget(o.mode, o.detail, o.novice)
        val ran = try {
            run(tools, { off, withTools -> Bodies.vision(cfg, prompt, listOf(jpegB64), budget, off, emptyList(), withTools) },
                cb, cancel, stripNoQ = true)
        } catch (e: ApiException) {
            // 有的接口输出上限比预算小，会直接 400（「max_tokens should be ≤ …」）→ 退回 4096 再来一次
            if (!Prompts.isMaxTokensError(e.message)) throw e
            run(tools, { off, withTools -> Bodies.vision(cfg, prompt, listOf(jpegB64), 4096, off, emptyList(), withTools) },
                cb, cancel, stripNoQ = true)
        }
        // 开关开着但 Key / 地址没配全：这一轮其实没联网。必须在答案里说清，不能假装搜过。
        // 真搜到了就补一行 App 自己生成的「已联网 · N 条来源」—— 不靠模型写，用户才看得出来搜过。
        val note = if (cancel.get()) "" else searchNotes(ran.session)
        // 来源一律上抛（即使正文为空也上抛）：界面靠它决定「来源」那一块显不显示
        if (!cancel.get()) cb.onSources(ran.session.sourceHits())
        if (note.isNotEmpty() && ran.text.isNotBlank()) {
            cb.onText(note)
            return ran.text + note
        }
        return ran.text
    }

    /**
     * 一次识别留下的对话（v1.57 追问用）：截图、当时的选项、之后每一轮 (role, 文字)。
     * 追问把整段重新发一遍 —— 模型接口本身不记上下文。
     * v1.65 续图追问：extraShots 是后续各轮附的新截图（原图 jpegB64 永远第一张，同桌面 lastB64s 语义）。
     */
    class Conversation(val jpegB64: String, val opts: Options, firstAnswer: String) {
        val turns = ArrayList<Pair<String, String>>().apply { add("assistant" to firstAnswer) }
        val extraShots = ArrayList<String>()
        companion object {
            /** 附加新图上限（同桌面 ASK_EXTRA_SHOTS_MAX=3）：超出的最旧的被挤掉，省 token 也防上下文爆 */
            const val EXTRA_SHOTS_MAX = 3
        }
        fun addExtraShot(b64: String) {
            extraShots.removeAll { it == b64 }
            extraShots.add(b64)
            while (extraShots.size > EXTRA_SHOTS_MAX) extraShots.removeAt(0)
        }
    }

    /**
     * newShot != null = 续图追问：最后追加一张刚截的屏，以它为准；turns 里记纯文本，图在发送时按 extraShots 拼。
     *
     * v1.72 [FreePrompt]：directive / lang 是用户这一轮自己打的招呼（追问输入框升级成「随便说」）。
     * 两者必须**单独**传进来、拼在提示词最后 —— 早先把风格段并进 question 传，结果它落在中间，
     * 后面紧跟着「不超过 N 字」「用中文」两条默认约定把它压掉，「英文回我」「讲细一点」等于没生效。
     */
    fun followUp(
        conv: Conversation,
        question: String,
        cb: Callbacks,
        cancel: AtomicBoolean,
        newShot: String? = null,
        directive: String = "",
        lang: String? = null,
    ): String {
        val o = conv.opts
        val noShot = o.noShot && newShot == null   // 续图追问要那张新屏，不能省
        // [K5] v1.70 知识库检索：问法里带了「我的卡片」之类意图词（或 /k）才检索，命中才把卡片块插进提示词。
        val kBlock = KnowledgeSearch.blockFor(question)
        val tools = newSession().active
        var prompt = KnowledgeSearch.inject(Prompts.system(o.mode, o.detail, o.novice, o.customPrompt, o.terms), kBlock)
        if (tools) prompt += WebSearch.toolPrompt() + WebSearch.triggerHint(question) + nowLine()
        // 斜杠命令（/s 开头）在送进提示词前先剥掉：模型没被告知过 /s 是什么，看见只会当成噪音。
        // [K5] /k 同理剥掉。
        val ask = KnowledgeSearch.stripTrigger(WebSearch.stripTrigger(question))
        val searchText = ask.ifBlank { question }
        val qText = when {
            newShot != null -> Prompts.followUpWithShot(searchText, o.novice, directive, lang)
            // v1.67 省图：话术要改成「按你之前的回答」，不能再让模型去看截图（这次没发图）
            noShot -> Prompts.followUpNoShot(searchText, o.novice, directive, lang)
            else -> Prompts.followUp(searchText, o.novice, directive, lang)
        }
        val images = CostPolicy.followImages(conv.jpegB64, conv.extraShots, newShot, noShot)
        val turns = CostPolicy.trimTurns(conv.turns) + ("user" to qText)
        val budget = CostPolicy.followBudget(o.mode, o.novice)
        val ran = try {
            run(tools, { off, withTools -> Bodies.vision(cfg, prompt, images, budget, off, turns, withTools) },
                cb, cancel, stripNoQ = false, fallbackQuery = searchText)
        } catch (e: ApiException) {
            if (!Prompts.isMaxTokensError(e.message)) throw e
            run(tools, { off, withTools -> Bodies.vision(cfg, prompt, images, 4096, off, turns, withTools) },
                cb, cancel, stripNoQ = false, fallbackQuery = searchText)
        }
        val text = ran.text
        if (text.isNotBlank() && !cancel.get()) {
            if (newShot != null) conv.addExtraShot(newShot)
            conv.turns.add(turns.last()); conv.turns.add("assistant" to text)
        }
        // 搜索开关开着但配不全：这一轮没联网，答案末尾同样要给出可见说明（对话里只记原始回答）。
        val note = if (cancel.get()) "" else searchNotes(ran.session)
        if (!cancel.get()) cb.onSources(ran.session.sourceHits())
        if (note.isNotEmpty() && text.isNotBlank()) {
            cb.onText(note)
            return text + note
        }
        return text
    }

    /**
     * 翻译走大模型时按成本策略挑接口：开关开着且配了小活接口就用便宜的（[CostPolicy.Task.TRANSLATE]）。
     * DeepL / 百度那两条路在 [Translate] 里，不经过这里。
     */
    fun translate(text: String, langId: String, cb: Callbacks, cancel: AtomicBoolean): String {
        val c = CostPolicy.route(CostPolicy.Task.TRANSLATE, cfg)
        return textRun(c, Prompts.translate(text, langId), Prompts.TRANSLATE_BUDGET, cb, cancel)
    }

    /**
     * v1.89 AI 语言点解析（配合显式选择的机器翻译）：译文已经由 DeepL / 百度给出，
     * [reference] 就是那份译文 —— 让 AI 在不改动它的前提下补词性 / 义项 / 例句等解析（见 [Prompts.translateEnrich]）。
     * 走同一条小活路由；失败就当没解析，由调用方如实标注，这里不吞异常。
     */
    fun translateEnrich(text: String, langId: String, reference: String, cb: Callbacks, cancel: AtomicBoolean): String {
        val c = CostPolicy.route(CostPolicy.Task.TRANSLATE, cfg)
        return textRun(c, Prompts.translateEnrich(text, langId, reference), Prompts.TRANSLATE_BUDGET, cb, cancel)
    }

    /**
     * 纯文本请求（翻译 / 解析）：预算用 [Prompts.TRANSLATE_BUDGET]。
     * 有的接口输出上限比预算小，会直接 400（「max_tokens should be ≤ …」）→ 退回 4096 再来一次（同 solve）。
     * 翻译不联网：`tools = false`，请求体里连 tools 字段都没有。
     */
    private fun textRun(c: ApiConfig, prompt: String, budget: Int, cb: Callbacks, cancel: AtomicBoolean): String = try {
        run(false, { off, _ -> Bodies.text(c, prompt, budget, off) }, cb, cancel, stripNoQ = false).text
    } catch (e: ApiException) {
        if (!Prompts.isMaxTokensError(e.message)) throw e
        run(false, { off, _ -> Bodies.text(c, prompt, 4096, off) }, cb, cancel, stripNoQ = false).text
    }

    /** 关键词兜底（v1.65 通识起，v1.66 所有模式）：首轮没带清单行时纯文本补问一次（不带截图，几百 token，真实评估缺席率约 1/3） */
    fun termsLine(answer: String, cb: Callbacks, cancel: AtomicBoolean): String {
        val c = CostPolicy.route(CostPolicy.Task.TERMS_FALLBACK, cfg)
        return run(false, { off, _ -> Bodies.text(c, Prompts.termsOnly(answer), 400, off) }, cb, cancel, stripNoQ = false).text
    }

    /**
     * 搜索相关的那两段可见说明（拼在一起，没内容的就是空串）：
     *  1) 开关开着但没配全 → 「这一轮没有联网」；
     *  2) 真搜到了 → App 自己生成的「已联网 · N 条来源」（不靠模型写，用户才看得出来搜过）。
     */
    private fun searchNotes(session: WebSearch.Session): String =
        unavailableSearchNote(session) + session.sourcesLine()

    /** 搜索开关开着、但本轮没有可用 Provider 时的可见说明；关着或配好时返回空串（不重复提示）。 */
    private fun unavailableSearchNote(session: WebSearch.Session): String {
        val c = cfg.searchConfig
        if (!c.enabled || session.active) return ""
        return "\n\n（已开启联网搜索，但" + WebSearch.missing(c, cfg) +
            "。这一轮没有联网，回答仅凭模型已有知识，未核实。）"
    }

    /**
     * 搜索开着时给提示词补一行「现在几点」。没有它，模型会把训练截止时间当成「现在」，
     * 判断不出「这件事该不该联网查」（聊天那边早有 [ChatStore.nowLine]，识屏这边一直没有）。
     */
    private fun nowLine(): String = "\n\n" + ChatStore.nowLine(System.currentTimeMillis())

    /** 一次尝试的结果：正文 + 这一轮真正用过的搜索快照（来源行 / 失败说明都从它取） */
    private class Ran(val text: String, val session: WebSearch.Session)

    /**
     * 带工具的调用。tools=false 时和以前完全一样；tools=true 时多一层「接口不认 tools」的兜底：
     * 第一轮就 400 且报错里提到 tools / function，就去掉工具重答一次，并明确说「本次没联网」。
     * 以前这种情况整题失败，用户还以为是自己的模型名填错了。
     */
    private fun run(
        tools: Boolean,
        body: (Boolean, Boolean) -> String,
        cb: Callbacks,
        cancel: AtomicBoolean,
        stripNoQ: Boolean,
        fallbackQuery: String = "",
    ): Ran {
        if (!tools) return runCore(false, body, cb, cancel, stripNoQ, fallbackQuery)
        return try {
            runCore(true, body, cb, cancel, stripNoQ, fallbackQuery)
        } catch (e: ApiException) {
            if (cancel.get() || !Prompts.isToolsUnsupported(e.message)) throw e
            val again = runCore(false, body, cb, cancel, stripNoQ, fallbackQuery)
            if (again.text.isBlank()) again
            else {
                cb.onText(WebSearch.TOOLS_UNSUPPORTED_NOTE)
                Ran(again.text + WebSearch.TOOLS_UNSUPPORTED_NOTE, again.session)
            }
        }
    }

    /**
     * @param tools   这一轮带不带工具（请求体里加不加 tools），由调用方在轮次开头算一次
     * @param fallbackQuery 模型给的搜索参数解析失败时的兜底搜索词；截图识别没有文字，传空
     *
     * 每次 attempt（含「关思考重试」和 max_tokens 回退）都**新建一份搜索快照**（v2.2.1）：
     * 以前几次调用共用一份 session，重试时搜索次数早记满了，模型只拿到「次数已达上限」，
     * 最终答案其实没用上联网资料，却没有任何提示。
     */
    private fun runCore(
        tools: Boolean,
        body: (Boolean, Boolean) -> String,
        cb: Callbacks,
        cancel: AtomicBoolean,
        stripNoQ: Boolean,
        fallbackQuery: String,
    ): Ran {
        val client = ApiClient(cfg)
        var thinkingOff = false
        for (attempt in 0..1) {
            val session = newSession()
            var head = StringBuilder()
            var decided = !stripNoQ
            val sent = StringBuilder()
            var lastPhaseAt = 0L
            // 思考增量的攒批：provider 的 SSE 常常一个字一块，逐块往主线程 post 会把 UI 线程打满。
            // 攒到 ~200 字或满 80ms 才下发一次；正文一来（思考其实已结束）与流结束各强制 flush 一次。
            var thinkBuf = StringBuilder()
            var lastThinkAt = 0L
            fun flushThink() {
                if (thinkBuf.isEmpty()) return
                val s = thinkBuf.toString()
                thinkBuf.setLength(0)
                lastThinkAt = System.currentTimeMillis()
                cb.onReasoning(s)
            }
            val listener = object : ApiClient.Listener {
                override fun onThinking(chars: Int) {
                    val now = System.currentTimeMillis()
                    // v1.89：思考字数属于内部过程，不往外报（只留一句「正在思考…」）；节流照旧
                    if (now - lastPhaseAt > 250) { lastPhaseAt = now; cb.onPhase("正在思考…") }
                }


                override fun onReasoning(delta: String) {
                    if (delta.isEmpty()) return
                    thinkBuf.append(delta)
                    val now = System.currentTimeMillis()
                    if (thinkBuf.length >= 200 || now - lastThinkAt >= 80) flushThink()
                }
                override fun onText(text: String) {
                    // 正文一来就说明思考结束了：尾巴先送出去，别把最后几个字吞掉
                    flushThink()
                    if (!decided) {
                        head.append(text)
                        if (head.trim().length < 5) return
                        decided = true
                        val h = head.toString().replace(Prompts.NO_Q_HEAD, "")
                        head = StringBuilder()
                        if (h.isNotEmpty()) { sent.append(h); cb.onText(h) }
                    } else {
                        sent.append(text); cb.onText(text)
                    }
                }

                override fun onUsage(prompt: Int, completion: Int) { cb.onUsage(prompt, completion) }

                override fun onDiscard() {
                    // 放出去的都是「我先查一下」这类前言：清掉，别让它留在面板上
                    val n = sent.length
                    sent.setLength(0)
                    head = StringBuilder()
                    if (n > 0) cb.onDiscard(n)
                }
            }
            // 有三条路，别再合并成 if/else：
            //  - 给了 ToolContext → 无论联网开不开都走工具循环（本机工具不需要网络）；
            //  - 没给 ToolContext 但联网开着 → 只走联网工具循环（老行为，识别仍然能用 web_search）；
            //  - 都没有 → 最朴素的单轮流式，和以前一模一样。
            val useLoop = tools || toolCtx != null
            val (_, thinkChars) = if (useLoop) {
                client.streamWithSession(
                    body(thinkingOff, true), listener, cancel,
                    fallbackQuery = fallbackQuery,
                    session = session,
                    onPhase = { phase -> cb.onPhase(phase) },
                    tools = toolCtx,
                )
            } else {
                client.stream(body(thinkingOff, false), listener, cancel)
            }
            // 流结束（正常 / 取消 / 工具轮）：把还没下发的思考尾巴送出去 —— 面板据此在**第一个正文之前**
            // 冻结思考耗时；取消时上层已把面板收口，多送这一批也不会被写进任何正文。
            flushThink()
            if (!decided && head.isNotEmpty()) {
                val h = head.toString().replace(Prompts.NO_Q_HEAD, "")
                if (h.isNotEmpty()) { sent.append(h); cb.onText(h) }
            }
            if (cancel.get()) return Ran(sent.toString(), session)
            if (sent.isNotBlank()) {
                if (client.truncated) {
                    // 思考和正文共用 max_tokens：想太久正文就被拦腰截断 —— 明说，别让人以为答案就这么多
                    val note = "\n\n（⚠ 回答到了输出上限被截断，后面没显示全。可在设置里把「思考强度」调低，或把「解析强度」调成简略后再双击小球识别一次）"
                    sent.append(note); cb.onText(note)
                }
                return Ran(sent.toString(), session)
            }
            if (thinkChars > 0 && !thinkingOff) {
                // v1.89：思考字数属于内部过程，不往外报 —— 只如实说「思考完没吐正文、已关掉思考重试」这件事
                cb.onText("（模型思考完没吐出正文，已关掉思考重试一次）\n\n")
                thinkingOff = true
                continue
            }
            val empty = if (thinkChars > 0) "模型 ${cfg.model} 思考完没吐出正文 —— 可在设置里把「思考强度」调低或关掉"
                else "模型 ${cfg.model} 未返回内容"
            // 空回答仍然是错误；如果这一轮搜索也失败过，把原因补进错误里，别让用户只看到「未返回内容」。
            val searchFail = if (tools) session.failureContext() else ""
            throw ApiException(empty + if (searchFail.isEmpty()) "" else "（本轮联网搜索也未成功：$searchFail）")
        }
        throw ApiException("识别请求失败")
    }
}
