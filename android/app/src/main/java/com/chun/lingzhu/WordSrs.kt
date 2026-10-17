package com.chun.lingzhu

import org.json.JSONObject
import kotlin.random.Random

/**
 * 把词卡的滑出判词接进 FSRS（v1.85）。
 *
 * 以前词卡的「认识 / 不认识」只喂给 [Words] 那套「连续认识 N 次算掌握」的简易加权，
 * 复习节奏是拍的；现在同一划也写进真正的间隔重复算法 [Fsrs]，下次该弹谁由算法定。
 *
 * 三档映射（[SwipeJudge.Grade] → [Fsrs.Rating]）：
 *  - 不认识（左滑）→ [Fsrs.Rating.Again]
 *  - 模糊（下滑）  → [Fsrs.Rating.Hard]
 *  - 认识（右滑）  → [Fsrs.Rating.Good]
 *  - 关闭（上滑）  → **不写**（返回 null）；[Fsrs.Rating.Easy] 不出现（没有「太简单」这一档）。
 *
 * 存储刻意**不走** [KnowledgeCard.Store]（那是用户手抄的知识卡），而是自己一个 prefs 原始键
 * [KEY]，经 [Prefs.rawGet] / [Prefs.rawPut] 读写（`Prefs.kt` 不动）。键用 [Words.key]（词库 + 正面）。
 *
 * 纯逻辑（[rating] / [dueKeys] / [apply] / [pickDue]）不碰 Android / Prefs，可直接单测（见 [WordSrsTest]）。
 */
object WordSrs {

    /** 自己的 prefs 原始键。放进 [Prefs] 的 raw 存取（`rawGet` / `rawPut`），不新增具名字段。 */
    const val KEY = "wordSrs"

    // ───────────────────────── 纯逻辑（可单测，不碰 Android） ─────────────────────────

    /**
     * 评分 → FSRS 评分。上滑（[SwipeJudge.Grade.CLOSE]）不评，返回 null。
     * Easy 不在映射里：词卡只有「认识 / 模糊 / 不认识」三档。
     */
    fun rating(grade: SwipeJudge.Grade): Fsrs.Rating? = when (grade) {
        SwipeJudge.Grade.UNKNOWN -> Fsrs.Rating.Again
        SwipeJudge.Grade.FUZZY -> Fsrs.Rating.Hard
        SwipeJudge.Grade.KNOWN -> Fsrs.Rating.Good
        SwipeJudge.Grade.CLOSE -> null
    }

    /**
     * 哪些 key 到期（纯函数，不改入参）：值交给 [Fsrs.isDue]。
     * 注意这是「已有记录里」的到期集合 —— 新词不进这张表（见 [pickDue] 的口径说明）。
     */
    fun dueKeys(cards: Map<String, Fsrs.Card>, now: Long): Set<String> =
        cards.filterValues { Fsrs.isDue(it, now) }.keys

    /** 到期数（纯函数）：已有记录里 [Fsrs.isDue] 为真的条数。 */
    fun dueCount(cards: Map<String, Fsrs.Card>, now: Long): Int =
        cards.count { Fsrs.isDue(it.value, now) }

    /** 这张卡到期没（纯函数）。没有记录时按新卡处理（[Fsrs.Card] 默认 New，恒到期）。 */
    fun isDue(cards: Map<String, Fsrs.Card>, key: String, now: Long): Boolean =
        Fsrs.isDue(cards[key] ?: Fsrs.Card(), now)

    /** 记一次评分（纯函数）：[SwipeJudge.Grade.CLOSE] 不写、原样返回；其余走 [Fsrs.review]。 */
    fun apply(cards: Map<String, Fsrs.Card>, key: String, grade: SwipeJudge.Grade, now: Long, params: Fsrs.Params = Fsrs.Params.DEFAULT): Map<String, Fsrs.Card> {
        val r = rating(grade) ?: return cards
        return cards + (key to Fsrs.review(cards[key] ?: Fsrs.Card(), r, now, params))
    }

    /**
     * 从候选里挑一张「到期的」；没有到期的返回 null（调用方退回 [Words.pick] 加权）。
     *
     * **口径**：「到期」= 在 [WordSrs] 里**已经有记录**、且 [Fsrs.isDue] 为真。新词（还没评过分、
     * 没有记录）**不算到期** —— 否则红宝书几千个新词在 FSRS 眼里全是 New、永远「到期」，
     * 选卡会退化成轮流弹新词、加权那套彻底失效。挑中的具体哪张仍按 [Words.pick] 加权随机，
     * 只是把候选缩到「到期」这一撮。
     */
    fun pickDue(
        cards: List<Pair<String, Words.Card>>,
        srs: Map<String, Fsrs.Card>,
        stats: Map<String, Words.Stat>,
        now: Long,
        rnd: Random = Random.Default,
    ): Pair<String, Words.Card>? {
        val due = cards.filter { srs[Words.key(it.first, it.second)]?.let { c -> Fsrs.isDue(c, now) } == true }
        if (due.isEmpty()) return null
        return Words.pick(due, stats, now, rnd)
    }

    // ───────────────────────── JSON 存取 ─────────────────────────

    /** `{key: Fsrs.Card-JSON}` —— 每张卡走 [Fsrs.toJson] / [Fsrs.cardFromJson]，坏元素跳过。 */
    fun toJson(cards: Map<String, Fsrs.Card>): String = JSONObject().apply {
        cards.forEach { (k, v) -> put(k, JSONObject(Fsrs.toJson(v))) }
    }.toString()

    fun fromJson(s: String?): Map<String, Fsrs.Card> = try {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence()
            .mapNotNull { k -> o.optJSONObject(k)?.toString()?.let { j -> Fsrs.cardFromJson(j)?.let { k to it } } }
            .toMap()
    } catch (_: Exception) { emptyMap() }

    // ───────────────────────── Prefs 存取（自己的键） ─────────────────────────

    /** 读回全部记录。坏 / 缺值都当空表。 */
    fun all(prefs: Prefs): Map<String, Fsrs.Card> = fromJson(prefs.rawGet(KEY))

    /** 今天有几张词卡到期。 */
    fun dueCount(prefs: Prefs, now: Long): Int = dueCount(all(prefs), now)

    /** 这张卡到期没（没有记录 = 新词，算到期）。 */
    fun isDue(prefs: Prefs, key: String, now: Long): Boolean = isDue(all(prefs), key, now)

    /**
     * 记一次评分并落盘。[SwipeJudge.Grade.CLOSE] 不写（上滑只关闭）。
     * 读-改-写一把：词卡是单线程（主线程）弹的，不需要额外加锁。
     */
    fun grade(prefs: Prefs, key: String, grade: SwipeJudge.Grade, now: Long) {
        if (rating(grade) == null) return
        prefs.rawPut(KEY, toJson(apply(all(prefs), key, grade, now, prefs.studyPlan.params())))
    }
}
