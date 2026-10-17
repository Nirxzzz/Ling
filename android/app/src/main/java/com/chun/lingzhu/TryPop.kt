package com.chun.lingzhu

import java.util.Calendar

/**
 * 「试弹」（v1.80）：待办到点不再直接甩一条通知，而是**轻震两下 + 在球边上浮出一张小卡**试探一下。
 *
 * 三条纪律（和 [PopupPolicy] 的「不弹是常态」一脉相承）：
 *  - **轻**：两下短 tick（[PATTERN] 全程不到 200ms），不是长震嗡嗡；
 *  - **小**：只占球边上一小块（[CARD_W_DP]），不遮当前界面、不抢焦点；
 *  - **会自己收**：[STAY_MS] 到点无条件退场，并且**退成常规通知** —— 试弹没被接住不等于提醒取消。
 *
 * 用户碰了卡（完成 / 稍后 / 叉掉）就算「看见了」，不再补通知：补了就是重复打扰。
 * 纯函数那一半（[whenLabel] / [body]）单测直接跑，Android 那层在 [OrbService] 的 showTryPop 里。
 */
object TryPop {
    /** 卡片停留多久：6 秒 = 抬眼能看见，又不至于一直杵在那儿 */
    const val STAY_MS = 6000L

    /** 震动节拍：等 0ms → 震 26ms → 停 110ms → 震 26ms（「哒、哒」） */
    val PATTERN = longArrayOf(0, 26, 110, 26)

    /** 卡片宽度（dp）：跟长按菜单一个量级，不做成一整条 */
    const val CARD_W_DP = 248f

    /** 卡片抬头：「提醒 · 就现在」/「提醒 · 3 分钟后」/「提醒 · 今天 15:30」 */
    fun heading(dueAt: Long, now: Long): String = "提醒 · " + whenLabel(dueAt, now)

    /**
     * 到期时刻相对现在怎么说。试弹通常发生在「刚好到点」，所以「就现在」是最常见的一条；
     * 早弹（闹钟提前醒了 / 测试触发）才走后面几档。
     */
    fun whenLabel(dueAt: Long, now: Long): String {
        val d = dueAt - now
        return when {
            d <= 60_000L -> "就现在"
            d < 3_600_000L -> "${d / 60_000L} 分钟后"
            sameDay(dueAt, now) -> "今天 ${hhmm(dueAt)}"
            sameDay(dueAt, now + 86_400_000L) -> "明天 ${hhmm(dueAt)}"
            else -> "${month(dueAt)}月${day(dueAt)}日 ${hhmm(dueAt)}"
        }
    }

    /**
     * 卡片第二行的小字交给 [Todo.notiBody]（「刚刚到期」「已过期 10 分钟」+ 备注）——
     * 和通知正文同一份口径，不为这张卡另写一套时间说法。
     */

    // ---- 日历小工具（用 Calendar 而不是 java.time，和 ChatStore.ago 一个口径，minSdk 26 也稳） ----

    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }
    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = cal(a); val cb = cal(b)
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
    private fun hhmm(ms: Long) = cal(ms).let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
    private fun month(ms: Long) = cal(ms).get(Calendar.MONTH) + 1
    private fun day(ms: Long) = cal(ms).get(Calendar.DAY_OF_MONTH)
}
