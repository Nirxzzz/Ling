package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.67 陪伴 M1 · 记忆卡：小珠在本机记住的「他是谁、在搞什么」。
 *
 * 分工（照 History / Words 的老规矩）：
 *  - 纯规则在 [MemoryCard]（截断、排序、序列化、去重）—— 纯 JVM，单元测试直接测，不碰 Context。
 *  - 落盘在 [MemoryCard.Store]（filesDir/cards/<pid>.json），复用 [FileMemo]：进程内副本 + 后台写，按路径共享。
 *
 * 硬上限（写盘前在 [MemoryCard.norm] 一次收干净）：2KB / 5 条话题 / 每条 16 字 / 称呼 8 字 / 目标 40 字。
 * 不存截图、不存识别原文 —— 那些已经在 history.json，卡里只留「结论级记忆」。
 *
 * 更新方式只有两种：面板上点「记住这题」（主），设置页手填称呼和目标（辅）。
 * **不做模型自动抽取** —— 那要 tool_calls 通道，且模型不守格式是常态，误记率不为零。
 */
data class CardTopic(val text: String, val at: Long)

data class Card(
    val name: String = "",
    val goal: String = "",
    val topics: List<CardTopic> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = name.isBlank() && goal.isBlank() && topics.isEmpty()
    /** 最近记的那条（每日一句的「昨天在搞 X」取它） */
    val latest: CardTopic? get() = topics.maxByOrNull { it.at }
}

object MemoryCard {
    const val MAX_TOPICS = 5
    const val TOPIC_CHARS = 16
    const val NAME_CHARS = 8
    const val GOAL_CHARS = 40
    /** 整个文件的硬上限（实测约 400 汉字；超了按最老的话题往下丢） */
    const val MAX_BYTES = 2048

    /**
     * 所有写盘 / 注入提示词之前都过这一遍：称呼目标截字，话题按时间倒序、去重、截到 5 条。
     * 解析外部文件时也走它 —— 文件被外部改过 / 旧版本写大了，这里顺手收回来。
     */
    fun norm(c: Card): Card {
        val seen = LinkedHashSet<String>()
        val topics = c.topics.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedByDescending { it.at }
            .map { it.copy(text = it.text.trim().take(TOPIC_CHARS)) }
            .filter { seen.add(it.text) }
            .take(MAX_TOPICS)
            .toList()
        return c.copy(
            name = c.name.trim().take(NAME_CHARS),
            goal = c.goal.trim().take(GOAL_CHARS),
            topics = topics,
            updatedAt = c.updatedAt,
        )
    }

    /** 空串 / 坏 JSON 一律当空卡：不因为一张卡坏了就让整个识别流程挂掉 */
    fun parse(s: String?): Card = try {
        if (s.isNullOrBlank()) Card() else norm(fromJson(JSONObject(s)))
    } catch (_: Exception) {
        Card()
    }

    private fun fromJson(o: JSONObject): Card {
        val arr = o.optJSONArray("topics")
        val topics = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val t = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val text = t.optString("text", "").trim()
            if (text.isEmpty()) null else CardTopic(text.take(TOPIC_CHARS), t.optLong("at", 0L))
        }
        return Card(
            name = o.optString("name", ""),
            goal = o.optString("goal", ""),
            topics = topics,
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }

    /** 序列化。utf8 超 2KB 就从最老的话题开始丢 —— 正常路径根本走不到（截断后约 400 字），留着是硬保险 */
    fun toJson(c0: Card): String {
        var c = norm(c0)
        while (true) {
            val s = rawJson(c)
            if (s.toByteArray(Charsets.UTF_8).size <= MAX_BYTES || c.topics.isEmpty()) return s
            c = c.copy(topics = c.topics.dropLast(1))   // 还超：从最老的话题开始丢
        }
    }

    private fun rawJson(c: Card): String = JSONObject().apply {
        put("name", c.name)
        put("goal", c.goal)
        put("updatedAt", c.updatedAt)
        put("topics", JSONArray().apply {
            c.topics.forEach { put(JSONObject().put("text", it.text).put("at", it.at)) }
        })
    }.toString()

    /**
     * 记一条话题。同一条再点一次 = 顶到最前（时间戳刷新），不新增 —— 面板上连点 10 次卡里也只有一条。
     * 空文本 / 截断后变空的文本不记。
     */
    fun addTopic(c: Card, text: String, now: Long): Card {
        val t = text.trim().take(TOPIC_CHARS)
        if (t.isEmpty()) return norm(c)
        return norm(c.copy(topics = listOf(CardTopic(t, now)) + c.topics.filter { it.text != t }, updatedAt = now))
    }

    fun removeTopic(c: Card, text: String, now: Long = System.currentTimeMillis()): Card =
        norm(c.copy(topics = c.topics.filter { it.text != text }, updatedAt = now))

    /** [MP] 旧文件（v1.67 单人格时代）。首次读 cards/<pid>.json 时把内容搬过去，然后删掉它 */
    const val FILE = "card.json"
    /** [MP] 人格化之后每个 pid 一份：filesDir/cards/<memoryFile>.json */
    fun file(pid: String) = "cards/${safeFile(pid)}.json"

    /** 相对时间，设置页的话题列表用（3 天前 / 刚刚） */
    fun ago(at: Long, now: Long): String {
        val d = now - at
        return when {
            d < 60_000L -> "刚刚"
            d < 3600_000L -> "${d / 60_000L} 分钟前"
            d < 86400_000L -> "${d / 3600_000L} 小时前"
            else -> "${d / 86400_000L} 天前"
        }
    }

    /**
     * [MP] 读写 filesDir/cards/<memoryFile>.json。按路径共享：设置页和悬浮球服务各自 new 的 Store 看到同一份。
     *
     * [pid] = 人格 id（默认 [Personas.P0]）。每个人格一张独立的卡 —— 教练记得的进度不会串到管家那里。
     *
     * 迁移：v1.67 的 card.json 在 filesDir 根下。第一次用 p0 打开时，若 cards/p0.json 不存在而旧文件在，
     * 就改名过去 —— 老用户升级后称呼和目标不丢。
     */
    class Store(ctx: Context, val pid: String = Personas.P0) {
        private val dir = ctx.applicationContext.filesDir
        private val f = File(dir, file(pid))

        init {
            f.parentFile?.mkdirs()
            if (pid == Personas.P0) migrate(dir)
        }

        /** 旧 card.json → cards/p0.json。改名成功就完事；失败退回复制一份，再删旧的 */
        private fun migrate(dir: File) {
            val old = File(dir, FILE)
            try {
                if (!f.exists() && old.exists()) {
                    if (!old.renameTo(f)) { f.writeText(old.readText()); old.delete() }
                }
            } catch (_: Exception) {}
        }

        fun get(): Card = FileMemo.get(f) { MemoryCard.parse(it) }

        /** 写（自动 norm + 更新时间戳），返回真正存进去的那张卡 */
        fun put(c: Card, now: Long = System.currentTimeMillis()): Card {
            val n = MemoryCard.norm(c.copy(updatedAt = now))
            FileMemo.put(f, n) { MemoryCard.toJson(it) }
            return n
        }

        fun addTopic(text: String, now: Long = System.currentTimeMillis()): Card = put(MemoryCard.addTopic(get(), text, now))
        fun removeTopic(text: String): Card = put(MemoryCard.removeTopic(get(), text))
        fun setName(v: String): Card = put(get().copy(name = v))
        fun setGoal(v: String): Card = put(get().copy(goal = v))

        /** 「忘记我」：先放空副本再删文件 —— 删之前再读也不会读回旧内容 */
        fun clear() = FileMemo.clear(f, Card())
    }
}

/**
 * v1.67 每日一句：面板每天第一次打开插一行「昨天在搞 X，今天要不要接着来？」。
 *
 * 三条约束（都是刻意选的，别自己加）：
 *  - **不发通知**：主动弹要面对「几点弹、弹几次、用户烦了怎么办」，那整套纪律是 M2 的。这里只用用户主动打开的面板。
 *  - **一天最多一次**：判定只靠 [Prefs.dailyLineDay] 记的 yyyyMMdd，同一天连识别 5 次也只出现一次。
 *  - **没话题就不插**：卡是空的硬凑一句反而假，有话题才显得「它在」。
 */
object DailyLine {
    fun shouldShow(enabled: Boolean, lastDay: Int, today: Int): Boolean = enabled && lastDay != today

    /** 卡里最近那条话题 → 整句话；没有话题返回空串（调用方据此不插这一行） */
    fun line(card: Card?): String {
        val x = card?.latest?.text?.trim().orEmpty()
        return if (x.isEmpty()) "" else "昨天在搞 $x，今天要不要接着来？"
    }

    /**
     * 面板打开那一刻算一次：该插哪一行（空串 = 不插）。
     * 判定和取句都在这，纯函数、不碰 Prefs —— 调用方拿到非空串再写 [Prefs.dailyLineDay]，
     * 写入是同步 commit（见 Prefs），所以同一天第二次进来 lastDay 已经等于今天，直接返回空串。
     */
    fun pick(enabled: Boolean, lastDay: Int, today: Int, card: Card?): String =
        if (shouldShow(enabled, lastDay, today)) line(card) else ""
}