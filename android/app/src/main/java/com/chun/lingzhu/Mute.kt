package com.chun.lingzhu

/**
 * 「免打扰」的纯逻辑层（v1.85，菜单里那个「免打扰 30 分」）。
 *
 * 用户按下后**全部静音**：弹单词、待办到点弹卡/通知、双击识别的答案面板，都不再挡屏；
 * 识别照常跑、结果照常进历史（「只更新历史，不挡屏」）。免打扰自己到点就结束。
 *
 * 纯函数、不碰 Android 也不碰 SharedPreferences —— 单测直接盯 [MuteTest]。
 * 时间统一用 `System.currentTimeMillis()` 的毫秒口径，方便在设置/菜单里算「还剩几分钟」。
 */
object Mute {
    /** 一按下去免打扰多久（分钟）—— 用户拍板的 30 分。 */
    const val MINUTES = 30

    /** 从 [nowMs] 起免打扰 [minutes] 分钟的结束时刻。 */
    fun until(nowMs: Long, minutes: Int = MINUTES): Long =
        nowMs + minutes.coerceAtLeast(1) * 60_000L

    /** [untilMs] 这一刻还静音着吗（0 = 没开过 → 不静音）。 */
    fun active(nowMs: Long, untilMs: Long): Boolean = untilMs > nowMs

    /** 还剩几分钟（向上取整；已结束返回 0）。用于菜单右侧的说明。 */
    fun remainMin(nowMs: Long, untilMs: Long): Int {
        if (!active(nowMs, untilMs)) return 0
        return (((untilMs - nowMs) + 59_999L) / 60_000L).toInt()
    }

    /** 菜单里那一行的说明文字。 */
    fun label(nowMs: Long, untilMs: Long): String {
        val m = remainMin(nowMs, untilMs)
        return if (m <= 0) "关" else "已开 · $m 分后结束"
    }
}
