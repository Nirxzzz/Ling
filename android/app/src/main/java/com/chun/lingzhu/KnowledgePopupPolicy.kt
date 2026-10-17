package com.chun.lingzhu

/**
 * 知识卡定时弹窗的**纯逻辑**：挑哪一张 + 该不该弹。
 *
 * 刻意不 import `android.*` —— 与仓储里 [WordRecent] / [SwipeJudge] 同样的做法，JVM 直接单测
 * （见 [KnowledgePopupPolicyTest]）。Android 那层（[OrbService] 里读电量、悬浮窗、Prefs）只负责
 * 把系统状态算成布尔量喂进 [Gate]，副作用一概不在这里。
 */
object KnowledgePopupPolicy {

    /**
     * 「刚弹过」窗口：10 分钟，与单词卡的 [WordRecent.WINDOW_MS] 对齐 —— 表达同一件事：
     * 一张卡露过脸之后短时间内不要再挑它，免得「同一个问题连着弹」。只在进程内存里，弹卡时顺手清过期项。
     */
    const val WINDOW_MS = 10 * 60_000L

    /** 记一次「这张刚弹过」（纯函数，就地改 [recent] 并清过期项）。key = 知识卡 id（各牌组内唯一）。 */
    fun mark(recent: MutableMap<Long, Long>, id: Long, now: Long, windowMs: Long = WINDOW_MS) {
        val it = recent.entries.iterator()
        while (it.hasNext()) if (now - it.next().value >= windowMs) it.remove()
        recent[id] = now
    }

    /** 这张是不是刚弹过（纯函数，不改入参）。 */
    fun isRecent(recent: Map<Long, Long>, id: Long, now: Long, windowMs: Long = WINDOW_MS): Boolean {
        val at = recent[id] ?: return false
        return now - at < windowMs
    }

    /**
     * 从候选里挑下一张（纯函数，不改入参）。口径（用户指定 due-first / new / 不立刻重复 / 不弹未来到期）：
     *  1. 先滤掉**未来才到期**的（已学、`due > now`）—— 它们既非到期也非新卡，现在弹不合时宜；
     *  2. 再排掉 [WINDOW_MS] 内刚弹过的（避免立刻重复同一张）；
     *  3. 可弹的里优先**到期**的（按 `due` 升序，最逾期的最先）；
     *  4. 没有到期的取**新卡**（按 `createdAt` 升序，最早建的先 —— 行为确定、可测）；
     *  5. 若候选都被「刚弹过」排空 → 返回 null（宁可这一轮不弹，也绝不在窗口内重复同一张）。
     *
     * 没有任何可弹的（候选空 / 全是未来到期 / 全在 [WINDOW_MS] 内刚弹过）返回 null。
     */
    fun pick(
        cards: List<KnowledgeCard.KCData>,
        recent: Map<Long, Long>,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): KnowledgeCard.KCData? {
        if (cards.isEmpty()) return null
        val eligible = cards.filter { it.fsrs.isNew || it.fsrs.due <= now }   // 无未来到期卡
        if (eligible.isEmpty()) return null
        val fresh = eligible.filter { !isRecent(recent, it.id, now, windowMs) }
        if (fresh.isEmpty()) return null
        val due = fresh.filter { !it.fsrs.isNew }.minByOrNull { it.fsrs.due }
        if (due != null) return due
        return fresh.filter { it.fsrs.isNew }.minByOrNull { it.createdAt }
    }

    /**
     * 定时弹窗的放行门槛（纯函数，汇总调用方算好的布尔量）：
     * 关着 / 用户点过退出 / 屏幕没亮或锁着 / 免打扰 / 悬浮球隐藏 / 正忙（识别或自动扫描或词卡在屏）/
     * 答案面板开着 / 菜单开着 / 静音时段 —— 任一命中就不弹。
     */
    data class Gate(
        val on: Boolean,
        val orbWanted: Boolean,
        val muted: Boolean,
        val orbHidden: Boolean,
        val busy: Boolean,
        val panelOpen: Boolean,
        val menuOpen: Boolean,
        val interactive: Boolean,
        val quiet: Boolean,
    )

    fun shouldPop(g: Gate): Boolean =
        g.on && g.orbWanted && g.interactive &&
            !g.muted && !g.orbHidden && !g.busy && !g.panelOpen && !g.menuOpen && !g.quiet

    /** 静音时段（复用 [PopupPolicy.inQuiet] 的口径；`quietOn=false` 恒 false）。纯函数。 */
    fun quietNow(minute: Int, quietOn: Boolean, start: Int, end: Int): Boolean =
        quietOn && PopupPolicy.inQuiet(minute, start, end)
}
