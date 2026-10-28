package com.chun.lingzhu

import org.json.JSONArray

/**
 * v2.3.3「允许自定义 Agent」：每个 Agent 能用哪些工具。
 *
 * ## 为什么单独一个文件
 * 前几轮把东西都往 Personas / AgentTools 里塞，那两个文件已经 400+ 行，
 * 每次改都要在长文件里定位，出错代价很高（本项目曾因一次误操作丢过整个文件尾部）。
 * 这里只有判断，没有 IO、没有 Android：纯 JVM 可单测，也方便被两边引用。
 *
 * ## 语义（这条最容易搞错，所以写在最前面）
 * - 空集合 = 全开，不是「全关」。
 *   老用户的 personas.json 里没有这个字段，读出来就是空集合；若把空当成全关，
 *   升级后所有人的 Agent 会突然什么工具都不会用 —— 这是最坏的一种「升级损坏」。
 * - 非空集合 = 白名单，只有列出来的能用。
 * - 用户关掉全部工具时存的是 NONE 这个哨兵，不是空集合。
 *   否则「我全关了」和「我没设置过」会存成一模一样的东西，语义就丢了。
 */
object AgentScope {

    /** 「一个工具都不要」的哨兵。存这个而不是空集合，见类注释 */
    const val NONE = "none"

    /** 默认全开。新建 Agent 时不写这个字段，读出来就是全开 */
    val ALL: Set<String> = emptySet()

    /**
     * 存储值 -> 白名单集合。
     *
     * - null / 空串 / 空数组 -> ALL（全开）
     * - 含 NONE 的那一项被剔除，由 [isExplicitlyNone] 判断「是不是显式关光」
     */
    fun parse(raw: String?): Set<String> {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return ALL
        return try {
            val a = JSONArray(t)
            (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }
                .filter { it != NONE }
                .toSet()
        } catch (_: Exception) {
            // 手改坏了 / 老格式：按「没设置过」处理，全开。
            // 不能按「全关」—— 一个解析失败不该让 Agent 变成废物。
            ALL
        }
    }

    /** 用户是否显式关掉了全部工具（存的是 NONE） */
    fun isExplicitlyNone(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return false
        return try {
            val a = JSONArray(t)
            (0 until a.length()).any { a.optString(it).trim() == NONE }
        } catch (_: Exception) { false }
    }

    /** 白名单集合 -> 存储值。空集合存成 NONE，见类注释 */
    fun encode(set: Set<String>): String =
        if (set.isEmpty()) JSONArray().put(NONE).toString()
        else JSONArray().apply { set.forEach { put(it) } }.toString()

    /**
     * 给界面看的勾选状态：把存储原值转成「每个已知工具勾没勾」。
     *
     * [known] 是当前所有工具名（来自 AgentTools.registry）。顺序照 known 走，
     * 这样界面上的工具次序和模型看到的一致，用户勾选时不会觉得两个列表对不上。
     */
    fun checked(raw: String?, known: List<String>): Set<String> =
        if (isExplicitlyNone(raw)) emptySet()
        else {
            val set = parse(raw)
            if (set.isEmpty()) known.toSet() else set.filter { it in known }.toSet()
        }

    /** 一句话概括这个 Agent 的权限，给列表页做副标题 */
    fun label(raw: String?, total: Int): String {
        if (isExplicitlyNone(raw)) return "不启用任何工具"
        val set = parse(raw)
        if (set.isEmpty()) return "全部 " + total + " 个工具"
        return "启用 " + set.size + " / " + total + " 个工具"
    }
}
