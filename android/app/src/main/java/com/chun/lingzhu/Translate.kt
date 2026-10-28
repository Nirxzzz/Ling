package com.chun.lingzhu

/**
 * 翻译引擎顺序（v1.61，对齐电脑版 Ling）：纯规则，单元测试直接测。
 *  - auto：**DeepL → 百度 → AI**。前一个没配好就跳过，出错（额度用完 / Key 不对 / 连不上）就换下一个。
 *    v2.6.3 起 auto 取消「AI 优先」：配好 DeepL 就先拿 DeepL 给出的原文译文（机器直译保真、零 AI 成本），
 *    需要解析时再由 AI 在**不改动这份译文**的前提下补语言点解析（见 [Prompts.translateEnrich]）。
 *    AI 只在机器翻译都没配好 / 都失败时才自己整段翻译。展示顺序 [LABEL] 与 [plan] 完全一致，不再有第二套顺序。
 *  - deepl / baidu / ai：只用这一个，出错直接报错，不偷偷换。
 */
object Translate {
    const val AUTO = "auto"
    const val DEEPL = "deepl"
    const val BAIDU = "baidu"
    const val AI = "ai"
    val ENGINES = listOf(AUTO, DEEPL, BAIDU, AI)
    val LABEL = mapOf(AUTO to "自动：DeepL → 百度 → AI", DEEPL to "DeepL", BAIDU to "百度翻译", AI to "AI 模型（上面的接口）")
    /** 面板上写「来源：xx」用的短名 */
    val SHORT = mapOf(DEEPL to "DeepL", BAIDU to "百度", AI to "AI")

    fun plan(engine: String, hasDeepl: Boolean, hasBaidu: Boolean, hasAi: Boolean): List<String> = when (engine) {
        DEEPL, BAIDU, AI -> listOf(engine)
        else -> listOfNotNull(DEEPL.takeIf { hasDeepl }, BAIDU.takeIf { hasBaidu }, AI.takeIf { hasAi })
    }

    /** 「DeepL 出错（456）：DeepL 本月字符额度用完了」→「DeepL 本月字符额度用完了」，给「前面为什么没成功」用 */
    fun reason(msg: String?): String = (msg ?: "出错").replace(Regex("^(DeepL|百度翻译) ?出错（\\d+）："), "").take(40)

    // ================================================================
    // 首选引擎缓存查找（发请求之前，纯函数）
    // ================================================================

    /**
     * 一次缓存命中的结果：[engine] = 这份译文是哪个引擎存下来的，[item] = 缓存条目。
     */
    data class Pick(val engine: String, val item: Cache.Item)

    /**
     * 发请求**之前**的缓存查找：命中就零成本秒出，一个接口都不用调。
     *
     * 只查 [plan] 里的引擎，按 [plan] 自己的顺序。调用方传进来的 [plan] 就是这一轮真正的引擎顺序
     * （v2.6.3 起 auto = DeepL → 百度 → AI），所以 auto 下 AI 存过的富结果**不会**抢在 DeepL 前面命中；
     * 显式钉了单个引擎时只传这个引擎。不跨引擎借用 —— 用户钉了 DeepL 就不能拿百度存的译文冒充。
     *
     * [force] = 用户显式点了「重新翻译」：跳过查找，一定真发请求。
     *
     * ⚠️ 位置必须在发请求之前：命中缓存是「压根不用发请求」，和 plan 在网络失败时的降级链不是一回事，别混。
     *
     * @param probe 引擎 → 该引擎那个键上的缓存条目（没有就是 null）。抽成入参是为了单测能直接喂假数据。
     */
    fun pickCached(
        plan: List<String>,
        force: Boolean = false,
        probe: (String) -> Cache.Item?,
    ): Pick? {
        if (force) return null
        for (e in plan) { val it = probe(e); if (it != null) return Pick(e, it) }
        return null
    }
}
