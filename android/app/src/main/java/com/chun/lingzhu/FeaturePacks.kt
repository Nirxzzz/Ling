package com.chun.lingzhu

import android.content.Context
import android.content.Intent

/**
 * 功能包的具体实现。全部集中在这一个文件里，
 * 这样“加一个功能”是加一个 object + 往 Features.all 加一行，而不是到处开文件。
 *
 * 每个 object 都很薄：只有元数据 + 一个 intent()。真正的逻辑还在原来的 Activity 里，
 * 这里不做任何搬迁 —— 搬迁的风险留到单独一轮，一次只搬一个，每次都能编译能测。
 */

// ── 学习 ────────────────────────────────────────────────

object StudyFeature : Feature {
    override val id = "study"
    override val title = "学习与复习"
    override val subtitle = "学新 / 复习 / 数据 / 画像"
    override val order = 100
    override val toolNames = listOf(
        AgentTools.NAME_ADD_CARD,
        AgentTools.NAME_STUDY_PLAN,
    )
    override fun intent(ctx: Context): Intent = Intent(ctx, StudyActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

object KnowledgeFeature : Feature {
    override val id = "knowledge"
    override val title = "知识卡库"
    override val subtitle = "新建 / 编辑 / 检索"
    override val order = 110
    override val showsInSettings = false   // 入口在「学习与复习」页里，设置页不再重复一遍
    override fun intent(ctx: Context): Intent = Intent(ctx, KnowledgeListActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

object GraphFeature : Feature {
    override val id = "graph"
    override val title = "知识图谱"
    override val subtitle = "概念之间的关系网"
    override val order = 120
    override val showsInSettings = false
    override fun intent(ctx: Context): Intent = Intent(ctx, GraphActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

object TwinFeature : Feature {
    override val id = "twin"
    override val title = "我的学习画像"
    override val subtitle = "五个维度 + Agent 反思"
    override val order = 130
    override val showsInSettings = false
    override fun intent(ctx: Context): Intent = Intent(ctx, TwinActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

// ── 记事 ────────────────────────────────────────────────

object TodoFeature : Feature {
    override val id = "todo"
    override val title = "提醒"
    override val subtitle = "时间写在句子里就行"
    override val order = 200
    override val toolNames = listOf(AgentTools.NAME_ADD_REMINDER)
    override fun intent(ctx: Context): Intent = TodoListActivity.intent(ctx)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

// ── 数据 ────────────────────────────────────────────────

object StatsFeature : Feature {
    override val id = "stats"
    override val title = "学习数据面板"
    override val subtitle = "保持率 / 复习量 / 时长 / 成本"
    override val order = 140
    override val showsInSettings = false
    override val toolNames = listOf(AgentTools.NAME_COST_REPORT, AgentTools.NAME_WEAK_POINTS)
    override fun intent(ctx: Context): Intent = Intent(ctx, LearningPanelActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

// ── 记忆 ────────────────────────────────────────────────

object MemoryAidFeature : Feature {
    override val id = "memory-aid"
    override val title = "记忆辅助"
    override val subtitle = "本地生成，不上传"
    override val order = 300
    override val toolNames = listOf(AgentTools.NAME_SEARCH_MEMORY)
    override fun intent(ctx: Context): Intent = Intent(ctx, MemoryAidActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    override fun open(ctx: Context): Boolean = run(ctx, intent(ctx))
}

/**
 * 统一的 startActivity 兜底。
 *
 * ActivityNotFoundException 在“清单里漏注册”时才会出现 —— 真出现过一次（新拆的
 * StudyPlanActivity 忘了写进 manifest），表现是点击后毫无反应、也不崩，极难排查。
 * 这里捕获它并返回 false，让调用方显示一句人话，而不是静默失败。
 */
private fun run(ctx: Context, i: Intent?): Boolean {
    if (i == null) return false
    return try {
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
