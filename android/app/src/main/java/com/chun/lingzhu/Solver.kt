package com.chun.lingzhu

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一次识别 / 翻译的流程（纯 JVM，可单测）：
 *  - 思考把输出预算吃光、没吐出正文 → 关掉思考自动重试一次（同桌面 streamChat）
 *  - 开头的「[无题目]」标记剥掉（只在识别时）
 */
class Solver(private val cfg: ApiConfig) {

    interface Callbacks {
        fun onPhase(text: String)
        fun onText(text: String)
        /** v1.65：接口上报的真实 token 用量（截图折在 prompt_tokens 里）；接口不给就不回调 */
        fun onUsage(prompt: Int, completion: Int) {}
    }

    /**
     * terms = 设置「识别关键词」（v1.66，默认开）：提示词末尾带关键词清单要求
     * noShot = 设置「追问省图」（v1.67，默认开）：追问时不重发首轮截图，只带首轮回答文本 + 对话。
     * 默认 false —— 库里直接 new 出来的调用（单测）保持「照旧发图」的老行为，产品默认值由 Prefs 传进来。
     * search = 设置「联网搜索」（v1.68，**默认关**）：关着时请求体里一个 tools 字段都不带，和 v1.67 完全一致。
     */
    data class Options(val mode: String, val detail: String, val novice: Boolean, val customPrompt: String = "", val terms: Boolean = Prefs.KEYWORDS_DEFAULT, val noShot: Boolean = false, val search: Boolean = WebSearch.ENABLED_DEFAULT)

    fun solve(jpegB64: String, o: Options, cb: Callbacks, cancel: AtomicBoolean): String {
        val prompt = Prompts.system(o.mode, o.detail, o.novice, o.customPrompt, o.terms)
        val budget = CostPolicy.solveBudget(o.mode, o.detail, o.novice)
        return try {
            run({ off, withTools -> Bodies.vision(cfg, prompt, listOf(jpegB64), budget, off, emptyList(), withTools) }, cb, cancel, stripNoQ = true, searchText = null, allowSearch = o.search)
        } catch (e: ApiException) {
            // 有的接口输出上限比预算小，会直接 400（「max_tokens should be ≤ …」）→ 退回 v1.53 的 4096 再来一次
            if (!Prompts.isMaxTokensError(e.message)) throw e
            run({ off, withTools -> Bodies.vision(cfg, prompt, listOf(jpegB64), 4096, off, emptyList(), withTools) }, cb, cancel, stripNoQ = true, searchText = null, allowSearch = o.search)
        }
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
    fun followUp(conv: Conversation, question: String, cb: Callbacks, cancel: AtomicBoolean, newShot: String? = null,
                 directive: String = "", lang: String? = null): String {
        val o = conv.opts
        val noShot = o.noShot && newShot == null   // 续图追问要那张新屏，不能省
        // [K5] v1.70 知识库检索：问法里带了「我的卡片」之类意图词（或 /k）才检索，命中才把卡片块插进提示词。
        // 位置在 TERMS_RULE 之前（inject 自己找位置）；没命中 blockFor 返回空串 → 提示词一个字节都不变。
        val kBlock = KnowledgeSearch.blockFor(question)
        val prompt = KnowledgeSearch.inject(Prompts.system(o.mode, o.detail, o.novice, o.customPrompt, o.terms), kBlock)
        // [WebSearch] v1.68：这一轮要不要带 tools。三道条件缺一不可 —— 设置开关开着、引擎配好了、这次问法确实像要查实时信息。
        // 斜杠命令（/s 开头）在送进提示词前先剥掉：模型没被告知过 /s 是什么，看见只会当成噪音。
        // [K5] /k 同理剥掉，别让模型把「/k」当噪音。
        val ask = KnowledgeSearch.stripTrigger(WebSearch.stripTrigger(question))
        val searchText = ask.ifBlank { question }
        val allowSearch = o.search
        val qText = when {
            newShot != null -> Prompts.followUpWithShot(searchText, o.novice, directive, lang)
            // v1.67 省图：话术要改成「按你之前的回答」，不能再让模型去看截图（这次没发图）
            noShot -> Prompts.followUpNoShot(searchText, o.novice, directive, lang)
            else -> Prompts.followUp(searchText, o.novice, directive, lang)
        }
        val images = CostPolicy.followImages(conv.jpegB64, conv.extraShots, newShot, noShot)
        val turns = CostPolicy.trimTurns(conv.turns) + ("user" to qText)
        val budget = CostPolicy.followBudget(o.mode, o.novice)
        val text = try {
            run({ off, withTools -> Bodies.vision(cfg, prompt, images, budget, off, turns, withTools) }, cb, cancel, stripNoQ = false, searchText = searchText, allowSearch = allowSearch)
        } catch (e: ApiException) {
            if (!Prompts.isMaxTokensError(e.message)) throw e
            run({ off, withTools -> Bodies.vision(cfg, prompt, images, 4096, off, turns, withTools) }, cb, cancel, stripNoQ = false, searchText = searchText, allowSearch = allowSearch)
        }
        if (text.isNotBlank() && !cancel.get()) {
            if (newShot != null) conv.addExtraShot(newShot)
            conv.turns.add(turns.last()); conv.turns.add("assistant" to text)
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
     */
    private fun textRun(c: ApiConfig, prompt: String, budget: Int, cb: Callbacks, cancel: AtomicBoolean): String = try {
        run({ off, _ -> Bodies.text(c, prompt, budget, off) }, cb, cancel, stripNoQ = false)
    } catch (e: ApiException) {
        if (!Prompts.isMaxTokensError(e.message)) throw e
        run({ off, _ -> Bodies.text(c, prompt, 4096, off) }, cb, cancel, stripNoQ = false)
    }

    /** 关键词兜底（v1.65 通识起，v1.66 所有模式）：首轮没带清单行时纯文本补问一次（不带截图，几百 token，真实评估缺席率约 1/3） */
    fun termsLine(answer: String, cb: Callbacks, cancel: AtomicBoolean): String {
        val c = CostPolicy.route(CostPolicy.Task.TERMS_FALLBACK, cfg)
        return run({ off, _ -> Bodies.text(c, Prompts.termsOnly(answer), 400, off) }, cb, cancel, stripNoQ = false)
    }

    /**
     * [WebSearch] v1.68：这一轮带不带 tools。三道条件缺一不可 ——
     *  1) 设置里的「联网搜索」开着（[allowSearch]）；2) 引擎配好了（[WebSearch.live]，缺 Key / 缺地址时为 false）；
     *  3) 这次输入确实像要查实时信息（[searchText] 非空且过 [WebSearch.intentOf]）。
     * 全满足才带 —— 少任何一道都退回 v1.67 的请求体（一个字节都不多发）。
     */
    private fun withToolsNow(allowSearch: Boolean, searchText: String?): Boolean =
        allowSearch && WebSearch.live() && !searchText.isNullOrBlank() && WebSearch.intentOf(searchText)

    /** body 的第二个参数就是「这一轮带不带 tools」，由 [withToolsNow] 在每轮开头算一次 */
    private fun run(body: (Boolean, Boolean) -> String, cb: Callbacks, cancel: AtomicBoolean, stripNoQ: Boolean,
                    searchText: String? = null, allowSearch: Boolean = false): String {
        val client = ApiClient(cfg)
        val searchOn = withToolsNow(allowSearch, searchText)   // [WebSearch] 整轮算一次，重试时不变
        var thinkingOff = false
        for (attempt in 0..1) {
            var head = StringBuilder()
            var decided = !stripNoQ
            val sent = StringBuilder()
            var lastPhaseAt = 0L
            val (_, thinkChars) = client.streamWithSearch(body(thinkingOff, searchOn), object : ApiClient.Listener {
                override fun onThinking(chars: Int) {
                    val now = System.currentTimeMillis()
                    // v1.89：思考字数属于内部过程，不往外报（只留一句「正在思考…」）；节流照旧，别的阶段状态不受影响
                    if (now - lastPhaseAt > 250) { lastPhaseAt = now; cb.onPhase("正在思考…") }
                }

                override fun onText(text: String) {
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
            }, cancel, fallbackQuery = searchText.orEmpty()) { q ->
                // [WebSearch] 搜索要几秒，先在面板上出声
                cb.onPhase("正在联网搜索「$q」…")
            }
            if (!decided && head.isNotEmpty()) {
                val h = head.toString().replace(Prompts.NO_Q_HEAD, "")
                if (h.isNotEmpty()) { sent.append(h); cb.onText(h) }
            }
            if (cancel.get()) return sent.toString()
            if (sent.isNotBlank()) {
                if (client.truncated) {
                    // 思考和正文共用 max_tokens：想太久正文就被拦腰截断 —— 明说，别让人以为答案就这么多
                    val note = "\n\n（⚠ 回答到了输出上限被截断，后面没显示全。可在设置里把「思考强度」调低，或把「解析强度」调成简略后再双击小球识别一次）"
                    sent.append(note); cb.onText(note)
                }
                return sent.toString()
            }
            if (thinkChars > 0 && !thinkingOff) {
                // v1.89：思考字数属于内部过程，不往外报 —— 只如实说「思考完没吐正文、已关掉思考重试」这件事
                cb.onText("（模型思考完没吐出正文，已关掉思考重试一次）\n\n")
                thinkingOff = true
                continue
            }
            throw ApiException(if (thinkChars > 0) "模型 ${cfg.model} 思考完没吐出正文 —— 可在设置里把「思考强度」调低或关掉" else "模型 ${cfg.model} 未返回内容")
        }
        throw ApiException("识别请求失败")
    }
}
