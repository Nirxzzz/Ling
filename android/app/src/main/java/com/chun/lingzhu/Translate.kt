package com.chun.lingzhu

/**
 * 翻译引擎顺序（v1.61，对齐电脑版 Ling）：纯规则，单元测试直接测。
 *  - auto：配了 AI 就 AI 优先，再 DeepL → 百度；前一个没配好就跳过、出错（额度用完 / Key 不对 / 连不上）就换下一个。
 *    ⚠️ [plan] 本身按 DeepL → 百度 → AI 排（保持既有语义、单测钉住了），「AI 优先」这层由调用方叠加（见 TranslateActivity.planFor）——
 *    所以 [AUTO] 的展示文案写 AI → DeepL → 百度（用户看到的实际首选顺序）。
 *  - deepl / baidu / ai：只用这一个，出错直接报错，不偷偷换。
 */
object Translate {
    const val AUTO = "auto"
    const val DEEPL = "deepl"
    const val BAIDU = "baidu"
    const val AI = "ai"
    val ENGINES = listOf(AUTO, DEEPL, BAIDU, AI)
    val LABEL = mapOf(AUTO to "自动：AI → DeepL → 百度", DEEPL to "DeepL", BAIDU to "百度翻译", AI to "AI 模型（上面的接口）")
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
     * 只查 [plan] 里的引擎，按 [plan] 自己的顺序。调用方传进来的 [plan] 已经是「这一轮可接受的缓存范围」：
     * AI 排最前（AI 优先）时只传 AI 那一档，显式钉了单个引擎时只传这个引擎，自动档没配 AI 时按配置顺序传。
     * 不跨引擎借用 —— 用户钉了 DeepL 就不能拿百度存的译文冒充，AI 优先时也不能让机器直译把富结果降级。
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
