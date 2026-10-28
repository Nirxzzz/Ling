package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test

class StudyPlanTest {
    @Test fun chosenDecksExcludeOtherSourcesAfterPersistence() {
        val p = StudyPlan.fromJson(StudyPlan(words = false, cardDecks = setOf("数学"), wordDecks = setOf("english")).toJson())
        assertTrue(p.acceptsCard("数学"))
        assertFalse(p.acceptsCard("物理"))
        assertFalse(p.acceptsWord("english"))
        assertTrue(StudyPlan().acceptsWord("any"))
    }

    @Test fun learningStepChangesActualNextDueForWords() {
        val now = 1_000_000L
        val plan = StudyPlan(learning = "3,17")
        val next = WordSrs.apply(emptyMap(), "deck\u0000word", SwipeJudge.Grade.UNKNOWN, now, plan.params()).getValue("deck\u0000word")
        assertEquals(now + 3 * 60_000L, next.due)
        assertEquals(Fsrs.State.Learning, next.state)
    }

    @Test fun retentionAndMaximumIntervalChangeReviewSchedule() {
        val now = 1_000_000L
        val card = Fsrs.Card(state = Fsrs.State.Review, stability = 40.0, difficulty = 5.0, lastReview = now, due = now + 40 * Fsrs.DAY_MS)
        val whenDue = card.due
        val loose = Fsrs.review(card, Fsrs.Rating.Good, whenDue, StudyPlan(retention = 75).params())
        val strict = Fsrs.review(card, Fsrs.Rating.Good, whenDue, StudyPlan(retention = 97).params())
        assertTrue(strict.due < loose.due)
        val capped = Fsrs.review(card, Fsrs.Rating.Easy, whenDue, StudyPlan(maxDays = 2).params())
        assertTrue(capped.due <= whenDue + 2 * Fsrs.DAY_MS)
    }

    @Test fun invalidStepOrdersAreRejectedAndEmptyEnablesAutomaticScheduling() {
        for (text in listOf("0,10", "10,1", "5,5", "1441", "x")) {
            try { StudyPlan.steps(text); fail("Accepted $text") } catch (_: IllegalArgumentException) { }
        }
        assertArrayEquals(intArrayOf(2, 15), StudyPlan.steps("2，15"))
        val c = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L, StudyPlan(learning = "").params())
        assertEquals(Fsrs.State.Review, c.state)
    }

}
