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

    fun toJson(): String = JSONObject().apply {
        put("words", words); put("cards", cards)
        put("wordDecks", JSONArray(wordDecks.toList())); put("cardDecks", JSONArray(cardDecks.toList()))
        put("newLimit", newLimit); put("reviewLimit", reviewLimit); put("retention", retention)
        put("maxDays", maxDays); put("learning", learning); put("relearning", relearning)
    }.toString()

    companion object {
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
