package com.chun.lingzhu

import android.content.Context

/**
 * 功能包注册表：全应用唯一的「有哪些功能」清单。
 *
 * 加一个功能 = 往 [all] 里加一行，**不改主壳**。
 *
 * 排序规则见 [sorted]：先按 [Feature.order]，同 order 按注册顺序（List 天然保序）。
 * 这样「学习」这类核心功能给个小值排前面，边缘功能给大值自然沉底，
 * 不需要在别处再维护一份顺序。
 */
object Features {

    /**
     * 全部功能包。
     *
     * v2.4 现状：先把**已经有独立页面**的功能收进来，
     * 纯后台的（专注守护、KeepAlive）不进 —— 它们没有「点进去」这回事，
     * 硬套一个入口只会让设置页更长。
     */
    val all: List<Feature> = listOf(
        StudyFeature,
        KnowledgeFeature,
        GraphFeature,
        TwinFeature,
        StatsFeature,
        AnkiFeature,
        TodoFeature,
        MemoryAidFeature,
    )

    /** 按 order 排好序的清单（List.sortBy 是稳定排序，同 order 保序） */
    fun sorted(): List<Feature> = all.sortedBy { it.order }

    /** 设置页要显示的那些。不传 Context 的版本**不看模块开关**，老调用方行为不变 */
    fun inSettings(): List<Feature> = sorted().filter { it.showsInSettings }

    /** 设置页要显示、且用户没关掉的那些 */
    fun inSettings(ctx: Context): List<Feature> = inSettings(Prefs(ctx).disabledFeatures)

    /** 免 Context 版本：给已经拿到「关掉的集合」快照的调用方与纯 JVM 测试用 */
    fun inSettings(disabled: Set<String>): List<Feature> = inSettings().filter { isEnabled(disabled, it.id) }

    fun byId(id: String): Feature? = all.firstOrNull { it.id == id }

    /**
     * 按工具名反查属于哪个功能。
     *
     * 给「工具权限」对话框分组用：用户看到「建知识卡」时要知道它属于「学习」那一块，
     * 而不是孤零零一个名字。查不到归属的工具归调用方处理（可能还没登记）。
     */
    fun ofTool(name: String): Feature? = all.firstOrNull { name in it.toolNames }

    /**
     * 这个功能此刻是不是开着的。
     *
     * 读 [Prefs.disabledFeatures]（落盘时已被 [FeatureToggles.normalize] 清洗过），
     * 判断本身复用 [FeatureToggles.isOn] —— 「核心功能永远开着」这条规则只存在于那一处。
     */
    fun isEnabled(ctx: Context, id: String): Boolean = isEnabled(Prefs(ctx).disabledFeatures, id)

    /** 免 Context 版本：纯 JVM 测试与已拿到快照的调用方 */
    fun isEnabled(disabled: Set<String>, id: String): Boolean = FeatureToggles.isOn(id, disabled)

    /**
     * 打开一个功能。统一在这里兜住「打不开」的情况，避免每个调用点各写一遍。
     *
     * 关掉的模块**一律打不开**：入口藏起来只挡住了正常路径，深链、旧通知、最近任务恢复
     * 仍会走到这里 —— 不在这里拒绝，用户会看到一个本该关闭的页面。
     *
     * @return false = 没打开（功能不存在 / 已被用户关掉 / 它自己说打不开），调用方负责提示用户
     */
    fun open(ctx: Context, id: String): Boolean {
        if (!isEnabled(ctx, id)) return false
        return byId(id)?.open(ctx) ?: false
    }
}
