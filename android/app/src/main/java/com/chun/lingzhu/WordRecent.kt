package com.chun.lingzhu

import kotlin.random.Random

/**
 * v1.85.7 修「点认识后短时间内同一个词反复弹出」。
 *
 * ## 病根（两层，缺一不可）
 *
 * 1. **选卡口径里没有「刚出过」这一档**。`Words.weight` 明明有一条「刚出过（[WINDOW_MS] 内）权重 0」，
 *    但只要候选池里**每张都是 0 权重**，[Words.pick] 就走 `total <= 0` 的退路 —— `cards[rnd.nextInt(size)]`
 *    **纯随机**。而 v1.85 的 [WordSrs.pickDue] 会先把候选缩到「FSRS 到期的那一小撮」，
 *    这一小撮恰好常常全是刚弹过的 → 权重全 0 → 退化成纯随机 → **刚评完的 give 立刻又被选出来**。
 *    用户体感就是「同一个词连着弹三次」。
 * 2. **评分落库在后台线程，和主线程的 [Words.shown] 写撞车**（v1.85.6 挪进 [OrbService] 的 `wordIo` 时引入）。
 *    `showWord` 在主线程读 `prefs.wordStats` 算完卡，紧接着在主线程整表写回（补 `shown`）；
 *    `onJudge` 在后台线程读-改-写同一张表（补 `streak`）。两边都是「读整表 → 改一个 key → 写整表」，
 *    没有跨线程串行 → **后写的把先写的覆盖掉**，评分可能整个丢失，卡权重永远降不下来。
 *
 * ## 修法
 *
 * - 本对象提供**选卡时排除最近弹过的词**这一层，不依赖任何写库时序（[mark] 只是改内存 map，纳秒级）。
 * - 排除后候选池空了（词库小 / 全都刚弹过）→ 退到 [stalest]：挑**最久没露脸**的那张，
 *   而不是纯随机。这样单词库只有 1~2 个词时也能正常轮换，且保证不会立刻重复同一张。
 * - 写库的竞争由 [OrbService] 侧把 `shown` 也挪进同一条 `wordIo` 串行队列解决（那边改）。
 *
 * 纯逻辑，不 import `android.*`，可直接 JVM 单测（见 `WordRecentTest`）。
 */
object WordRecent {

    /**
     * 「刚出过」的窗口：**10 分钟**，与 [Words.weight] 里「刚出过（10 分钟内）0 权重」那条**刻意对齐**。
     *
     * 不另造一个更短的数：原来那条 0 权重规则本来就想表达「10 分钟内别再弹我」，
     * 只是一旦整池都撞上它就被 `total <= 0` 的纯随机退路废掉了。这里把这条意图补完整。
     */
    const val WINDOW_MS = 10 * 60_000L

    /**
     * 记一次「这个 key 刚弹过」（纯函数，就地改 [recent]）。
     * 顺手清掉过期条目 —— 这个 map 只在进程内存里、只在弹卡时写，不清理会随使用无界增长。
     *
     * @param recent key（[Words.key]）→ 上次弹出的时刻。跨词库，所以按 key 存而不是按单词。
     */
    fun mark(
        recent: MutableMap<String, Long>,
        key: String,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ) {
        val it = recent.entries.iterator()
        while (it.hasNext()) if (now - it.next().value >= windowMs) it.remove()
        recent[key] = now
    }

    /** 这个 key 是不是刚弹过（纯函数，不改入参）。 */
    fun isRecent(
        recent: Map<String, Long>,
        key: String,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): Boolean {
        val at = recent[key] ?: return false
        return now - at < windowMs
    }

    /**
     * 滤掉窗口内弹过的卡（纯函数，不改入参）。**可能返回空表** —— 调用方要自己准备退路，
     * 别在这里偷偷把刚弹过的放回来（那正是这个 bug）。
     */
    fun withoutRecent(
        cards: List<Pair<String, Words.Card>>,
        recent: Map<String, Long>,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): List<Pair<String, Words.Card>> {
        if (recent.isEmpty()) return cards
        return cards.filter { !isRecent(recent, Words.key(it.first, it.second), now, windowMs) }
    }

    /**
     * 退路：候选全被排除时，挑**最久没露脸**的那张（[recent] 里时间戳最小的；从没弹过的排最前）。
     *
     * 比纯随机好在两点：不会立刻重复刚弹的那张；只有一个词时也能照常弹它（不能因为「排空了就
     * 不弹」，用户会以为功能坏了）。时间戳并列时 [minByOrNull] 取第一个，行为确定、可测。
     */
    fun stalest(cards: List<Pair<String, Words.Card>>, recent: Map<String, Long>): Pair<String, Words.Card>? =
        cards.minByOrNull { recent[Words.key(it.first, it.second)] ?: Long.MIN_VALUE }

    /**
     * 选下一张卡（纯函数）—— 词卡弹词的总入口。口径：
     *
     * 1. 先排掉 [WINDOW_MS] 内弹过的（**含刚评完分的**，所以与写库是否已完成无关）；
     * 2. 剩下的里优先 FSRS 到期（[WordSrs.pickDue]）；
     * 3. 没有到期的退回 [Words.pick] 加权随机；
     * 4. 剩下的一张都不剩（词库太小）→ [stalest] 挑最久没露的那张。
     *
     * @param recent 由 [mark] 维护的「key → 上次弹出时刻」。用 [Map] 接收、[MutableMap] 才能 [mark]。
     */
    fun pick(
        cards: List<Pair<String, Words.Card>>,
        srs: Map<String, Fsrs.Card>,
        stats: Map<String, Words.Stat>,
        recent: Map<String, Long>,
        now: Long,
        rnd: Random = Random.Default,
        windowMs: Long = WINDOW_MS,
    ): Pair<String, Words.Card>? {
        if (cards.isEmpty()) return null
        val fresh = withoutRecent(cards, recent, now, windowMs)
        if (fresh.isEmpty()) return stalest(cards, recent)
        return WordSrs.pickDue(fresh, srs, stats, now, rnd) ?: Words.pick(fresh, stats, now, rnd)
    }
}
