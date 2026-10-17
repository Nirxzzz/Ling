package com.chun.lingzhu

/**
 * v1.67 降成本策略（纯 JVM，可单测）：三件事都收在这里，Solver 只负责照着执行。
 *
 * 1) 追问省图（默认开）：以前每次追问都把首轮那张截图重发一遍 —— 一张 1080×2400 的手机截图在
 *    视觉模型里折合上千 prompt token，追问「举个例子 / 为什么」这种高频短问题，token 大头全在图上。
 *    现在改成：图不再发，用「首轮回答文本 + 裁剪后的对话」当上下文。
 *    首轮回答本身已经把画面内容说清楚了，够模型接话；真需要看图才能答的，提示词里让它明说。
 *    注意：只省掉「重发」的那几张，首轮截图本身不重发；**续图追问新截的图一定要发**（那就是为它存在的）。
 *
 * 2) max_tokens 分级：通用 / 关键词这类小活给 2048，做题详细才给 8192。
 *    max_tokens 只是上限、按实际用量计费，给小不会多花钱；思考和正文共用这个额度，给太大反而
 *    让「想太久正文被拦腰截断」更容易发生（Solver 里已有截断提示）。纯小白的 ×1.5 保留。
 *
 * 3) 模型分级路由（小活走第二套接口，**默认关**）：关键词补问、翻译这类活用便宜接口跑，
 *    省钱但答案质量差一点，所以默认不启用，由用户在设置里开。
 *    这里只放选择逻辑；真正的接口配置由 Prefs 写进 liteApi（复用 ApiProfiles 存的方案）。
 */
object CostPolicy {

    // ---------------- 1) 追问省图 ----------------

    /** 省图后除首轮回答外，最多再带几轮对话（首轮回答 + 最近几轮，够接话就行） */
    const val FOLLOW_TURNS_KEEP = 4

    /**
     * 裁剪追问要带的对话：首轮回答永远留着（省图后它就是唯一的「画面说明」），中间的老轮次丢掉，
     * 只保留最近 keepLast 条；若第 start 条不是 user 消息则向前找最近的 user 接上（消息要 user/assistant
     * 交替），所以实际返回最多 first + keepLast 条、最少可能只有 first（找得到 user 时从它接）。
     */
    fun trimTurns(turns: List<Pair<String, String>>, keepLast: Int = FOLLOW_TURNS_KEEP): List<Pair<String, String>> {
        if (turns.size <= keepLast + 1) return turns
        val first = turns.first()
        // 尾巴必须落在 assistant 上（Solver 裁完要接一句 user 的追问，交替才不破），
        // 开头必须落在 user 上（接在首轮 assistant 回答后面）。
        if (keepLast <= 0) return listOf(first)
        var e = turns.size - 1
        if (turns[e].first == "user") e--          // 未回答的 user 尾巴带上会破坏交替（追问也是 user）
        var start = (e - keepLast + 1).coerceAtLeast(1)
        while (start < e && turns[start].first != "user") start++   // 开头必须落在 user 上
        if (start >= e) return listOf(first)        // 窗口里找不着 user → 宁可只带首轮
        return listOf(first) + turns.subList(start, e + 1)
    }

    /**
     * 这次追问要发哪几张图。
     * noImage = 省图模式：不重发首轮截图、不重发历史附图；newShot（刚截的新屏）永远要发。
     */
    fun followImages(firstShot: String?, extraShots: List<String>, newShot: String?, noImage: Boolean): List<String> {
        val out = ArrayList<String>()
        if (!noImage) {
            if (!firstShot.isNullOrEmpty()) out.add(firstShot)
            for (b in extraShots) if (!b.isNullOrEmpty() && b !in out) out.add(b)
        }
        if (!newShot.isNullOrEmpty() && newShot !in out) out.add(newShot)
        return out
    }

    // ---------------- 2) max_tokens 分级 ----------------

    /** 通用模式 / 关键词补问这类小活 */
    const val BUDGET_GENERAL = 2048
    /** 做题、自定义：要编号分步 + 逐项分析，得留够 */
    const val BUDGET_SOLVE = 8192
    /** 纯小白正文会长一截（保留 v1.53 起的逻辑） */
    const val NOVICE_FACTOR = 1.5f

    /**
     * 识别的输出预算。通用 2048，做题 8192；纯小白 ×1.5。
     * （做题「详细」不再额外 ×1.5 —— 8192 已经和以前标准档的额度一样，
     *   纯小白的 12288 由 NOVICE_FACTOR 单独给足。）
     */
    fun solveBudget(mode: String, detail: String, novice: Boolean): Int {
        val base = if (mode == Prompts.MODE_GENERAL) BUDGET_GENERAL else BUDGET_SOLVE
        return if (novice) Math.round(base * NOVICE_FACTOR) else base
    }

    /** 追问跟着这一轮的模式走（同一个 Solver.Options，不另设开关） */
    fun followBudget(mode: String, novice: Boolean): Int =
        if (mode == Prompts.MODE_GENERAL) BUDGET_GENERAL else if (novice) Math.round(BUDGET_SOLVE * NOVICE_FACTOR) else BUDGET_SOLVE

    // ---------------- 3) 小活分级路由（默认关） ----------------

    /** 一次请求干的活是什么 —— 决定要不要走便宜接口 */
    enum class Task { SOLVE, FOLLOW_UP, TERMS_FALLBACK, TRANSLATE }

    /** 小活：不用看图、输出也短，答案质量要求低 —— 关键词补问、翻译 */
    fun isSmallTask(t: Task): Boolean = t == Task.TERMS_FALLBACK || t == Task.TRANSLATE

    /**
     * 第二套接口（小活专用）。默认 null = 没配小活接口，一律走主接口。
     * 由 Prefs 在开关打开、且 ApiProfiles 里选了一套方案时写进来。
     */
    @Volatile var liteApi: ApiConfig? = null

    /** 「小活走第二套接口」开关，默认关 —— 便宜接口讲不清楚复杂题，得用户自己愿意换 */
    const val LITE_DEFAULT = false

    /**
     * 开关本身（默认关）。和 liteApi 分开存：开关是用户的意愿，得能单独关掉而不动已选的方案；
     * 合并成一个字段的话，「关了但选的那套还在」这个状态就没法表达，测试也测不出「开关关着 → 走主接口」。
     */
    @Volatile var liteEnabled: Boolean = LITE_DEFAULT

    /**
     * 这次请求用哪套接口：小活 + 开关开着 + 配了第二套 → 第二套，否则主接口。
     * 三道条件缺一不可 —— 少任何一道都退回主接口（宁可多花点钱，也别让用户拿到没配过的接口的报错）。
     */
    fun route(t: Task, main: ApiConfig): ApiConfig {
        if (!liteEnabled) return main
        if (!isSmallTask(t)) return main
        val lite = liteApi ?: return main
        if (lite.baseUrl.isBlank()) return main
        // 方案没填模型名就用主接口的模型名 —— 半套配置比不路由更容易让人一头雾水
        return if (lite.model.isBlank()) lite.copy(model = main.model) else lite
    }
}