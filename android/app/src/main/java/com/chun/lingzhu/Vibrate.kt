package com.chun.lingzhu

import android.content.Context

/**
 * v1.86 震动：全 App 只有这一个出口，强度可在设置里改（**关 / 轻 / 中 / 重**）。
 *
 * 两种用法，别混：
 *  - [tick]   —— UI 反馈（菜单弹出、面板弹出、划词判定过临界）：**单下极短**，跟着强度档走；
 *  - [remind] —— 提醒到点（试弹卡）：成对 / 三下的节拍，「拍一下肩膀」的量级。
 *
 * 关档（[OFF]）什么都不震 —— 这两个函数直接返回，调用方不用自己判。
 * 任何异常都静默吞掉：震不成不该把提醒本身也带没了。
 *
 * 纯逻辑那一半（[norm] / [label] / [tickMs] / [tickAmp] / [pattern] / [amplitudes]）不碰 android，JVM 直测。
 */
object Vibrate {
    const val OFF = 0
    const val LIGHT = 1
    const val MID = 2
    const val STRONG = 3

    val LEVELS = listOf(OFF, LIGHT, MID, STRONG)

    /** 默认档：和 v1.80 试弹、v1.73 弹出反馈原来的手感一致（两下短震） */
    const val DEFAULT = MID

    fun norm(l: Int): Int = if (l in LEVELS) l else DEFAULT

    fun label(l: Int): String = when (norm(l)) {
        OFF -> "关"
        LIGHT -> "轻"
        MID -> "中"
        else -> "重"
    }

    /** 说明行（设置页 / 记期页共用一句人话） */
    fun describe(l: Int): String = when (norm(l)) {
        OFF -> "不震（提醒只弹卡片和通知）"
        LIGHT -> "一下很轻的哒"
        MID -> "两下轻震"
        else -> "三下、力度更大"
    }

    /** UI 反馈：单下短震的时长（ms）。关 → 0。 */
    fun tickMs(l: Int): Long = when (norm(l)) {
        OFF -> 0L
        LIGHT -> 8L
        MID -> 14L
        else -> 22L
    }

    /** UI 反馈的振幅（1~255）。系统不支持振幅控制时会被忽略，退回默认力度。 */
    fun tickAmp(l: Int): Int = when (norm(l)) {
        OFF -> 0
        LIGHT -> 70
        MID -> 130
        else -> 220
    }

    /**
     * 提醒节拍（ms，停/震交替，第一个是启动等待；奇数位才是「震」）。
     * 中档就是原来「试弹」那两下 —— 老手感不变。关 → 空数组。
     */
    fun pattern(l: Int): LongArray = when (norm(l)) {
        OFF -> longArrayOf()
        LIGHT -> longArrayOf(0, 14)
        MID -> longArrayOf(0, 26, 110, 26)
        else -> longArrayOf(0, 40, 70, 40, 70, 40)
    }

    /** 提醒节拍的振幅，和 [pattern] 一一对应；null = 不指定（交给系统默认力度）。 */
    fun amplitudes(l: Int): IntArray? = when (norm(l)) {
        OFF -> null
        LIGHT -> intArrayOf(0, 90)
        MID -> intArrayOf(0, 150, 0, 150)
        else -> intArrayOf(0, 255, 0, 255, 0, 255)
    }

    /** 总时长（ms），给单测和「这档会不会太长」的判断用 */
    fun patternMs(l: Int): Long = pattern(l).sum()

    // ---------------- Android 那层 ----------------

    /**
     * 提醒振动的 usage：明确标成「通知」，才符合提醒语义 —— 系统的免打扰 / 勿扰规则按 usage 判，
     * 不给 attributes 时默认是 USAGE_UNKNOWN，有些系统会把提醒当成普通触感、该响的不响。
     */
    private val REMIND_ATTRS: android.media.AudioAttributes by lazy {
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()
    }

    private fun vib(ctx: Context): android.os.Vibrator? = try {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (v != null && v.hasVibrator()) v else null
    } catch (_: Exception) { null }

    /** UI 反馈：单下短震。关档 / 没震动器 / 出错 → 什么都不做。 */
    fun tick(ctx: Context, level: Int) {
        val l = norm(level)
        if (l == OFF) return
        try {
            val vm = vib(ctx) ?: return
            vm.vibrate(android.os.VibrationEffect.createOneShot(tickMs(l), tickAmp(l).coerceAtLeast(1)))
        } catch (_: Exception) {}
    }

    /** 提醒到点：按档震一段节拍。关档 → 不震（但提醒卡和通知照常）。 */
    fun remind(ctx: Context, level: Int) {
        val l = norm(level)
        if (l == OFF) return
        try {
            val vm = vib(ctx) ?: return
            val p = pattern(l)
            if (p.isEmpty()) return
            val a = amplitudes(l)
            val e = if (a != null) android.os.VibrationEffect.createWaveform(p, a, -1)
                    else android.os.VibrationEffect.createWaveform(p, -1)
            // 带上 USAGE_NOTIFICATION 的 attributes（见 [REMIND_ATTRS]）—— 提醒要按「通知」计入勿扰
            vm.vibrate(e, REMIND_ATTRS)
        } catch (_: Exception) {}
    }

    // ---- 读设置的两个便捷入口（调用点最多，省得到处 new Prefs） ----

    fun tickPref(ctx: Context) = tick(ctx, Prefs(ctx).vibrateLevel)
    fun remindPref(ctx: Context) = remind(ctx, Prefs(ctx).vibrateLevel)

    /** [level] < 0（[Schedule.VIBE_GLOBAL]）= 跟全局设置；否则用这一条自己的档 */
    fun remindAt(ctx: Context, level: Int) = if (level < 0) remindPref(ctx) else remind(ctx, level)
}
