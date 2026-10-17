package com.chun.lingzhu

/**
 * 首次配置引导（v1.66）的判断逻辑 —— 纯 Kotlin，不碰 Android 类，单元测试直接测。
 * 界面在 MainActivity.buildSetup()：一步一页，左右滑动切换；这一步没做完不能往后滑，可以往回滑。
 *
 * 步骤以 v1.63「上手清单」和「后台保活」里实际需要的为准：
 *  - 必需（做完才能往后）：悬浮窗权限、接口；
 *  - 推荐（开了，或者明确点「先不开」才能往后）：无障碍截屏（系统不支持时自动算完成）、通知、电池优化；
 *  - 欢迎页 / 最后的「开始使用」页没有前置条件。
 * 以后要加页（比如并入灵伴的设置），往 Step 里加一项、在 steps() 里排好位置、在 done() 里写完成条件即可。
 */
object Onboarding {
    enum class Step(val id: String, val title: String, val required: Boolean) {
        WELCOME("welcome", "欢迎", false),
        OVERLAY("overlay", "悬浮窗权限", true),
        API("api", "填接口", true),
        CAPTURE("capture", "无障碍截屏", false),
        NOTIFY("notify", "通知", false),
        BATTERY("battery", "电池优化", false),
        FINISH("finish", "开始使用", false),
    }

    /** 引导时的系统 / 配置状态（MainActivity 每次回到页面时重新采一遍） */
    data class State(
        val overlay: Boolean,
        /** 接口地址和模型都填了 */
        val apiReady: Boolean,
        val a11ySupported: Boolean,
        val a11yOn: Boolean,
        val notifications: Boolean,
        val battery: Boolean,
        /** 用户在推荐步骤上点了「先不开」（Step.id） */
        val skipped: Set<String> = emptySet(),
    )

    /** 要走的步骤（开源副本没有内置接口，接口步骤始终保留） */
    fun steps(s: State): List<Step> = Step.values().toList()

    /** 这一步做完了没有 */
    fun done(step: Step, s: State): Boolean = when (step) {
        Step.WELCOME, Step.FINISH -> true
        Step.OVERLAY -> s.overlay
        Step.API -> s.apiReady
        Step.CAPTURE -> !s.a11ySupported || s.a11yOn || step.id in s.skipped
        Step.NOTIFY -> s.notifications || step.id in s.skipped
        Step.BATTERY -> s.battery || step.id in s.skipped
    }

    /** 推荐步骤才能「先不开」；必需步骤不能跳 */
    fun canSkip(step: Step): Boolean = !step.required && step != Step.WELCOME && step != Step.FINISH

    /** 停在第 from 页时能不能去第 to 页：往回随便；往前只能一页一页走，而且要途经的每一页都做完 */
    fun canGo(steps: List<Step>, from: Int, to: Int, s: State): Boolean {
        if (to < 0 || to >= steps.size) return false
        if (to <= from) return true
        return (from until to).all { done(steps[it], s) }
    }

    /** 现在最远能走到第几页（第一个没做完的那页；都做完了就是最后一页） */
    fun reachable(steps: List<Step>, s: State): Int {
        val i = steps.indexOfFirst { !done(it, s) }
        return if (i < 0) steps.size - 1 else i
    }

    /** 没做完时的一句提示（拦住往后滑时显示） */
    fun blockedHint(step: Step): String = when (step) {
        Step.OVERLAY -> "先打开「显示在其他应用上层」—— 没有它小球显示不出来"
        Step.API -> "先填好接口地址和模型（Key 按你的接口要求填）"
        Step.CAPTURE -> "先打开无障碍截屏，或者点「先不开，用录屏授权」"
        Step.NOTIFY -> "先打开通知，或者点「先不开」"
        Step.BATTERY -> "先允许后台运行，或者点「先不开」"
        else -> ""
    }

    /**
     * 老用户判断：v1.66 之前就用过（存过接口、启动过小球、开过无障碍、摆过小球、换过模式 / 配色……），
     * 升级后不再被拦去走引导。只看键在不在，不看值。
     */
    val LEGACY_KEYS = setOf("baseUrl", "apiKey", "model", "presetId", "orbWanted", "orbX", "orbY", "a11yEverOn",
        "mode", "themeId", "panelPos", "reviveCount", "customModes", "detail", "novice")

    fun legacyConfigured(keys: Set<String>): Boolean = keys.any { it in LEGACY_KEYS }

    /**
     * 要不要显示引导：走完过 → 不显示；开始过（中途去系统设置开权限、进程被杀再回来）→ 接着显示；
     * 没开始过但已经是配置过的老用户 → 不显示（调用方顺手把 onboarded 记成 true）。
     */
    fun shouldShow(onboarded: Boolean, started: Boolean, keys: Set<String>): Boolean =
        !onboarded && (started || !legacyConfigured(keys))
}
