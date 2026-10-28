package com.chun.lingzhu

/**
 * v1.68 新手友好（纯 JVM，可单测）：判断新手卡在哪、给一句人话解法。
 * 设置页「上手清单」与各空态页面共用这里的文案，改口径只改这一处。
 *
 * v1.91：无障碍这一条也区分「系统里已授权」（a11yOn）与「此刻已连上」（a11yConnected）——
 * 已授权但没连上时不算「没开」、也不假称系统关了，只提示去看一眼系统开关 / 重新连上。
 */
object Novice {

    /** 一条卡点提示：现状 + 人话解法 */
    data class Hint(val done: Boolean, val text: String, val action: String)

    /**
     * 按当前状态给按序排列的卡点提示（做完的排前面打勾，第一个没做的就是要做的事）。
     * @param everSolved 是否成功识别过（历史里有识别记录）
     * @param a11yOn 系统里已授权（无障碍开关已打开）
     * @param a11yConnected 此刻无障碍服务是否已连上 —— 必填，调用方按 [A11yService.available] 实采；
     *   不给默认值：过去默认按 a11yOn 推断只是迁就测试的 shim，会把「已授权但没连上」误当成可用
     */
    fun stallHints(
        hasOverlay: Boolean,
        hasApi: Boolean,
        a11ySupported: Boolean,
        a11yOn: Boolean,
        orbRunning: Boolean,
        everSolved: Boolean,
        a11yConnected: Boolean,
    ): List<Hint> = listOf(
        Hint(hasOverlay, "悬浮窗权限：小球能显示在屏幕上的前提", if (hasOverlay) "" else "设置 → 应用 → ${Preset.appName} → 显示在其他应用上层"),
        Hint(hasApi, "接口：识别靠它" + "，要自己填",
            if (hasApi) "" else "设置 → 接口 → 填地址 / Key / 模型"),
        Hint(!a11ySupported || a11yOn, "无障碍截屏：在系统里授权一次，之后识别不用每次点录屏框",
            when {
                !a11ySupported -> ""
                a11yConnected -> ""
                a11yOn -> "系统里已开、当前没连上 → 去「权限」看系统开关"
                else -> "设置 → 权限 → 开 ${Preset.appName} 的无障碍截屏"
            }),
        Hint(everSolved, "双击小球，截一下当前屏幕试试识别", "双击悬浮球"),
        Hint(orbRunning, "小球在后台常驻（被杀后会尽力自动拉回，不保证）", if (orbRunning) "" else "点「启动小球」"),
    )

    /** 空态文案：一句现状 + 一个下一步。kind = history / chat / todo / search */
    fun emptyState(kind: String): Pair<String, String> = when (kind) {
        "history" -> "还没有识别记录" to "回桌面双击小球，截个屏幕试试"
        "chat" -> "还没和小Ling聊过" to "随便说点什么，她记得住上下文"
        "todo" -> "没有待办" to "在状态栏的「随手记」通知里写一句，或点下面的 +"
        "search" -> "联网搜索还没配" to "设置 → 联网搜索 → 选一个引擎填 Key（不用搜索不影响其他功能）"
        else -> "这里还是空的" to "去主界面看看能做什么"
    }

    /** 上手清单是否可以整卡收起：核心四项全过 + 至少成功识别过一次 */
    fun checklistDone(hasOverlay: Boolean, hasApi: Boolean, a11yOk: Boolean, orbRunning: Boolean, everSolved: Boolean): Boolean =
        hasOverlay && hasApi && a11yOk && orbRunning && everSolved
}
