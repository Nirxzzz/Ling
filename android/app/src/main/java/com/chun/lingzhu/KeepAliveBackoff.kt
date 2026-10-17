package com.chun.lingzhu

/**
 * v1.68 保活第五层：看门狗指数退避（纯 JVM，可单测）。
 *
 * 为什么：看门狗固定 15 分钟一轮，球稳稳跑着时大部分检查是白醒（费电）。
 * 规则：连续【无事可做】达到 [STREAK_TO_BACKOFF] 次 → 间隔翻一档（15m→30m→60m 封顶）；
 * 一旦「球掉了」被拉起（真正干活）→ 立刻回最短档。状态由 WatchdogReceiver 存取
 * （SharedPreferences 两键：kaStreak / kaIntervalIdx），本类只管算。
 */
object KeepAliveBackoff {
    val INTERVALS_MS = longArrayOf(
        15 * 60 * 1000L,
        30 * 60 * 1000L,
        60 * 60 * 1000L,
    )
    const val STREAK_TO_BACKOFF = 3          // 连续这么多次「醒来没事做」才升档
    const val DEFAULT_IDX = 0

    /** 醒来时球在跑（没事做）→ 连击 +1；连击够数就升一档（封顶）。返回新档位下标 */
    fun onIdle(streak: Int, idx: Int): Int {
        val s = streak + 1
        val next = if (s >= STREAK_TO_BACKOFF) (idx + 1).coerceAtMost(INTERVALS_MS.lastIndex) else idx
        return next
    }

    /** 醒来时球掉了并成功拉起 → 回最短档，连击清零 */
    fun onRevive(): Int = DEFAULT_IDX

    /** 连击计数（升档后清零重新数） */
    fun nextStreak(streak: Int, oldIdx: Int, newIdx: Int): Int = if (newIdx != oldIdx) 0 else streak + 1

    fun intervalMs(idx: Int): Long = INTERVALS_MS[idx.coerceIn(INTERVALS_MS.indices)]
}
