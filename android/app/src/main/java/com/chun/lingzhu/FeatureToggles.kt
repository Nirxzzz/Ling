package com.chun.lingzhu

/**
 * v2.4 C 档：**功能本身可组装** —— 用户决定 App 里有哪些模块。
 *
 * ## 为什么单独一层，而不是直接改 [Features.all]
 * [Features.all] 是**编译期**的清单：一个功能存在与否是代码决定的。
 * 用户开关是**运行期**的：同一个 App，A 用户只有学习+待办，B 用户全开。
 * 两者分开，才能做到「关掉一个功能」不等于「删掉它的数据」——
 * 关掉番茄钟，你的专注记录一条不少；重新打开还在。
 *
 * ## 三条硬规则
 * 1. **核心功能不可关**（[PINNED]）：设置页、聊天、导入这些关掉了，
 *    用户就再也找不到开关把别的打开 —— 和长按菜单里「设置」不能隐藏是同一个道理。
 * 2. **关掉的功能数据保留**：只影响入口和菜单项，不碰任何存储。
 * 3. **未知 id 一律忽略**：老版本存过一个后来删掉的功能 id，不能因此把 App 弄坏。
 */
object FeatureToggles {

    /**
     * 永远不能关的功能 id。
     *
     * 判断标准是「关掉之后用户会失去抢救能力」：
     *  - 学习没了 → 这是主线的根，知识卡 / 复习 / 计划都挂在它下面，
     *    关掉它等于把 App 的主用途卸掉，用户第二天打开会以为数据丢了
     */
    // 说明：chat / settings 不在这张表里 —— 它们从来就不是「功能包」，
    // 而是主壳本身（主界面和设置页）。它们没有开关这回事，也就谈不上「不能关」。
    // 这里列的必须是 Features.all 里真实存在的 id，否则规则永远不生效（单测会抓）。
    val PINNED: Set<String> = setOf("study")

    /** 这一项能不能被用户关掉 */
    fun canDisable(id: String): Boolean = id !in PINNED

    /**
     * 在给定「用户关掉的集合」下，哪些功能是开着的。
     *
     * 顺序沿用 [Features.sorted]，不在这里重排 —— 排序规则只有一处，改起来不会两处打架。
     */
    fun enabled(disabled: Set<String>): List<Feature> =
        Features.sorted().filter { isOn(it.id, disabled) }

    /** 某个功能此刻是否开着的（未知 id 当开着的处理：拿不到信息时不要藏东西） */
    fun isOn(id: String, disabled: Set<String>): Boolean = id in PINNED || id !in disabled

    /**
     * 清洗用户存的「关掉」集合。
     *
     *  - 不认识的 id 丢掉（旧版本删掉的功能）
     *  - **核心功能强制移出**：万一配置文件被改坏、或者老版本允许关掉它们，
     *    这里再兜一次底，保证用户永远找回得到设置。
     */
    fun normalize(disabled: Set<String>?): Set<String> {
        if (disabled.isNullOrEmpty()) return emptySet()
        val known = Features.all.map { it.id }.toSet()
        return disabled.filter { it in known && canDisable(it) }.toSet()
    }
}
