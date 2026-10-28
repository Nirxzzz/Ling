package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/** 本机学习计划；空牌组集合表示全部，限额是每轮而不是每天。 */
data class StudyPlan(
    val words: Boolean = true,
    val cards: Boolean = true,
    val wordDecks: Set<String> = emptySet(),
    val cardDecks: Set<String> = emptySet(),
    val newLimit: Int = 20,
    val reviewLimit: Int = 100,
    val retention: Int = 90,
    val maxDays: Int = 36500,
    val learning: String = "1,10",
    val relearning: String = "10",
) {
    fun params(): Fsrs.Params = Fsrs.Params.of(
        requestRetention = retention / 100.0, maximumInterval = maxDays,
        learningSteps = steps(learning), relearningSteps = steps(relearning))

    fun acceptsWord(deck: String) = words && (wordDecks.isEmpty() || deck in wordDecks)
    fun acceptsCard(deck: String) = cards && (cardDecks.isEmpty() || deck in cardDecks)

    /** 这一轮是「学新的」还是「复习已学的」。放这里是为了让配额计算是纯函数，单测能直接测。 */
    enum class Half { NEW, REVIEW }

    /** 两类内容：各自有配额，不再共用一个池子。 */
    enum class Kind { CARDS, WORDS }

    /**
     * 一种内容的每轮条数上限。**故意不接受一个总数**：v2.3.1 之前 [newLimit] / [reviewLimit] 被当成
     * 「单词 + 知识卡共用的池子」，谁先填满谁独占——真机表现是「1 张到期知识卡 + 19 个新词、限额 100」
     * 时单词拿走全部 99，知识卡一张都轮不到。用户说的「每轮最多 100 项」不是「100 个词优先、卡无所谓」，
     * 而是「这一轮别超过 100 项」。于是拆成两个独立配额，各管各的。
     *
     * 知识卡沿用 [newLimit] / [reviewLimit] 原义（v2.3.2 之前只有知识卡一类，这两个字段本来就是
     * 「知识卡每轮上限」），旧配置不用迁移，语义也不会被偷偷改掉；单词按同一基准的一半另算。
     */
    fun limitFor(kind: Kind, half: Half): Int = when (kind) {
        Kind.CARDS -> if (half == Half.NEW) newLimit else reviewLimit
        Kind.WORDS -> wordCap(if (half == Half.NEW) newLimit else reviewLimit)
    }

    /**
     * 单词那一类的上限 = 基准 / [WORD_SHARE]，最少 1（基准 > 0 时）。
     *
     * 按比例而不是固定值：用户的「每轮上限」是他自己的时长预算（100 项约一晚上），固定值会在
     * 他把上限调到 20 时让单词仍占 100，反过来把预算撑爆。下限给 1 是因为「0 个词」在界面上
     * 和「单词功能坏了」无法区分——给一个用户没法照做的数字，比给个小数字更糟。
     */
    fun wordCap(base: Int): Int = if (base <= 0) 0 else (base / WORD_SHARE).coerceAtLeast(1)

    fun toJson(): String = JSONObject().apply {
        put("words", words); put("cards", cards)
        put("wordDecks", JSONArray(wordDecks.toList())); put("cardDecks", JSONArray(cardDecks.toList()))
        put("newLimit", newLimit); put("reviewLimit", reviewLimit); put("retention", retention)
        put("maxDays", maxDays); put("learning", learning); put("relearning", relearning)
    }.toString()

    companion object {
        /** 单词配额占基准的比例：一半。改它等于改「一轮里单词占多大」，所以立成常量而不是魔法数。 */
        const val WORD_SHARE = 2

        fun steps(text: String): IntArray = if (text.isBlank()) intArrayOf() else
            text.trim().split(Regex("[,，\\s]+" )).map { it.toInt() }.toIntArray().also { values ->
                require(values.size <= 10 && values.all { it in 1..1440 } && values.asList().zipWithNext().all { it.first < it.second }) {
                    "步骤须为递增的 1–1440 分钟，最多 10 步；留空交给 FSRS"
                }
            }

        fun fromJson(raw: String?): StudyPlan = try {
            val o = JSONObject(raw ?: "{}")
            fun strings(key: String): Set<String> = o.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: emptySet()
            StudyPlan(o.optBoolean("words", true), o.optBoolean("cards", true), strings("wordDecks"), strings("cardDecks"),
                o.optInt("newLimit", 20).coerceIn(0, 500), o.optInt("reviewLimit", 100).coerceIn(1, 1000),
                o.optInt("retention", 90).coerceIn(70, 99), o.optInt("maxDays", 36500).coerceIn(1, 36500),
                o.optString("learning", "1,10"), o.optString("relearning", "10")
            ).also { steps(it.learning); steps(it.relearning) }
        } catch (_: Exception) { StudyPlan() }
    }
}
