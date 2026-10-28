package com.chun.lingzhu

/**
 * v2.4 速记浮层：标签推荐（纯函数）。
 *
 * ## 为什么推荐要分三层
 * 方案要的是「几秒内完成」，所以**不能等网络**。三层按速度排：
 *  1. 会话延续（0 ms）—— 10 分钟内上一条笔记的标签排最前。
 *     学生在同一节课、同一套题里连续截图最多，这一层命中率极高且零成本。
 *  2. 本地相似（< 20 ms）—— 用留言和历史标签做词面匹配，不联网。
 *  3. AI 推荐（秒级）—— 只在开了「AI 整理」时才有，且**不放进浮层等待**，
 *     否则等待会破坏 5 秒目标。它出现在笔记详情里让用户一键采纳。
 *
 * 这里实现前两层，全部纯函数。
 */
object TagSuggest {

    /** 浮层里最多显示几个推荐（横向一排，再多要滑动，反而慢） */
    const val MAX_SUGGEST = 3

    /** 「最近常用」最多显示几个 */
    const val MAX_RECENT = 8

    /** 会话延续的时间窗：10 分钟。超过就不算「同一节课」了 */
    const val SESSION_MS = 10L * 60L * 1000L

    /** 一条标签使用记录 */
    data class Usage(val name: String, val count: Int, val lastUsed: Long)

    /**
     * 「最近常用」：按 `count × 0.5^(距上次使用天数 / 14)` 排序。
     *
     * 半衰期 14 天，和 AgentMemory 的取法同一口径 —— 两处用同一个衰减常数，
     * 用户不会觉得「这里推的标签和记忆那边不一样」。
     */
    fun recent(usages: List<Usage>, now: Long, max: Int = MAX_RECENT): List<String> =
        usages.asSequence()
            .filter { it.count > 0 }
            .sortedByDescending { score(it, now) }
            .take(max)
            .map { it.name }
            .toList()

    /** 打分：频次 × 时间衰减。公开出来是为了能单测，也方便以后调参 */
    fun score(u: Usage, now: Long): Double {
        val days = ((now - u.lastUsed).coerceAtLeast(0L)).toDouble() / (24.0 * 3600.0 * 1000.0)
        return u.count * Math.pow(0.5, days / 14.0)
    }

    /**
     * 会话延续：上一条笔记是 10 分钟内的，就把它的标签排最前。
     *
     * 返回排好序的完整列表：[会话标签] + [其余]。
     * 会话标签不会重复出现在后面。
     */
    fun withSession(lastNoteAt: Long, lastTags: List<String>, others: List<String>, now: Long): List<String> {
        if (lastNoteAt <= 0L || now - lastNoteAt > SESSION_MS || lastTags.isEmpty()) return others
        return (lastTags + others.filter { it !in lastTags }).distinct()
    }

    /**
     * 本地相似推荐：拿当前留言和历史标签做匹配。
     *
     * 匹配规则故意做得简单粗暴：**留言里出现过这个标签的任一段**就算命中。
     * 为什么不用 BM25：浮层里每打一个字就要重算，BM25 的代价不值得；
     * 而且标签通常就一两个字，词面包含已经够准。
     *
     * 排序按：命中长度（越长的命中越具体）+ 使用频次。
     */
    fun similar(draft: String, usages: List<Usage>, now: Long, max: Int = MAX_SUGGEST): List<String> {
        val text = draft.lowercase()
        if (text.isBlank()) return emptyList()
        return usages.asSequence()
            .mapNotNull { u ->
                val parts = u.name.lowercase().split('/')
                val hitLen = parts.filter { it.isNotEmpty() && text.contains(it) }.sumOf { it.length }
                if (hitLen <= 0) null else u to hitLen
            }
            .sortedWith(compareByDescending<Pair<Usage, Int>> { it.second }.thenByDescending { score(it.first, now) })
            .take(max)
            .map { it.first.name }
            .toList()
    }
}
