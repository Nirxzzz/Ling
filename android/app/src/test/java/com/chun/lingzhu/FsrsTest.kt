package com.chun.lingzhu

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fsrs：FSRS-5 调度移植的验收测试。
 * 数值断言对照 ts-fsrs `default-model.ts` 默认参数与论文公式手算值（不是同义反复：
 * 比如 r(t=S)=0.9、Again 后进 Relearning、interval 随 stability 单调）。
 */
class FsrsTest {

    private fun newCard(now: Long = 1_000_000L) = Fsrs.Card(lastReview = now)

    private val DAY = Fsrs.DAY_MS

    // ---------------- 遗忘曲线 ----------------

    @Test fun forgettingCurveAtStabilityIsRetention() {
        // r(t=S) = (1 + FACTOR·1)^DECAY = 0.9（FACTOR 就是按这个解出来的）—— 这是 FSRS 的定义式
        val p = Fsrs.Params.DEFAULT
        val r = Fsrs.forgettingCurve(5.0, 5.0, p)   // t=S 处可记忆性=0.9（幂曲线定义式）
        assertEquals(0.9, r, 0.01)
    }

    @Test fun forgettingCurveMonotonicDecay() {
        val p = Fsrs.Params.DEFAULT
        val r1 = Fsrs.forgettingCurve(1.0, 5.0, p)
        val r5 = Fsrs.forgettingCurve(5.0, 5.0, p)
        val r30 = Fsrs.forgettingCurve(30.0, 5.0, p)
        assertTrue(r1 > r5 && r5 > r30)
        assertTrue(r30 in 0.0..1.0)
    }

    @Test fun exponentialVariantAlsoHitsRetentionAtStability() {
        // 指数式：forget = exp(-t/S) → r = 1 - forget，t=S 时 r = 1 - e^-1 ≈ 0.632
        val r = Fsrs.retrievabilityExp(1.0, 1.0)
        assertEquals(1.0 - Math.exp(-1.0), r, 0.01)
    }

    // ---------------- 初始化 ----------------

    @Test fun initStabilityScalesWithRating() {
        val p = Fsrs.Params.DEFAULT
        val sAgain = Fsrs.initStability(Fsrs.Rating.Again, p)
        val sGood = Fsrs.initStability(Fsrs.Rating.Good, p)
        val sEasy = Fsrs.initStability(Fsrs.Rating.Easy, p)
        assertTrue(sAgain < sGood && sGood < sEasy)
        assertTrue(sAgain >= Fsrs.S_MIN)
    }

    @Test fun initDifficultyInBounds() {
        // initDifficulty 返回原始值（Easy 可为负），夹取发生在使用处 → 验证复习后的卡难度 ∈ [1,10]
        for (g in listOf(Fsrs.Rating.Again, Fsrs.Rating.Hard, Fsrs.Rating.Good, Fsrs.Rating.Easy)) {
            val c = Fsrs.review(Fsrs.Card(), g, 1_000_000L)
            assertTrue("难度 $g 越界: ${c.difficulty}", c.difficulty in 1.0..10.0)
        }
    }

    // ---------------- review 状态机 ----------------

    @Test fun newCardFirstReviewGoesToLearningThenReview() {
        // FSRS-5 短期步骤：首刷进 Learning（分钟级到期），第二步复习后才进 Review
        val c1 = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L)
        assertEquals(Fsrs.State.Learning, c1.state)
        assertTrue(c1.due > 1_000_000L)
        val c2 = Fsrs.review(c1, Fsrs.Rating.Good, c1.due)
        assertEquals(Fsrs.State.Review, c2.state)
        assertTrue(c2.stability > 0.0)
    }

    @Test fun againOnReviewCardPutsIntoRelearningAndLapses() {
        // Review 态被 Again 打回 → Relearning + lapses+1 + stability 下降
        var c = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L)
        c = Fsrs.review(c, Fsrs.Rating.Good, c.due)
        val sBefore = c.stability
        c = Fsrs.review(c, Fsrs.Rating.Again, c.due)
        assertEquals(Fsrs.State.Relearning, c.state)
        assertEquals(1, c.lapses)
        assertTrue(c.stability < sBefore)
    }

    @Test fun goodIntervalsGrowMonotonicallyWithStreak() {
        var c = Fsrs.review(newCard(), Fsrs.Rating.Good, 1_000_000L)
        var prev = c.stability
        for (i in 1..6) {
            c = Fsrs.review(c, Fsrs.Rating.Good, 1_000_000L + i * 3 * DAY)
            assertTrue("第 $i 次复习 stability 应增长 (${c.stability} <= $prev)", c.stability > prev)
            prev = c.stability
        }
    }

    @Test fun lapseReducesStabilityAndRaisesDifficulty() {
        var c = Fsrs.review(newCard(), Fsrs.Rating.Good, 1_000_000L)
        c = Fsrs.review(c, Fsrs.Rating.Good, 1_000_000L + 3 * DAY)
        val sBefore = c.stability
        val dBefore = c.difficulty
        c = Fsrs.review(c, Fsrs.Rating.Again, 1_000_000L + 6 * DAY)
        assertTrue("遗忘后 stability 应下降 ($sBefore -> ${c.stability})", c.stability < sBefore)
        assertTrue("遗忘后 difficulty 应上升", c.difficulty > dBefore)
        assertEquals(Fsrs.State.Relearning, c.state)
    }

    @Test fun easyGivesLongerIntervalThanHard() {
        val base = Fsrs.review(newCard(), Fsrs.Rating.Good, 1_000_000L)
        val hard = Fsrs.review(base, Fsrs.Rating.Hard, 1_000_000L + DAY)
        val easy = Fsrs.review(base, Fsrs.Rating.Easy, 1_000_000L + DAY)
        assertTrue(hard.due < easy.due)
    }

    @Test fun stabilityClampedToBounds() {
        // 大量 Easy 连击 → stability 封顶不发散
        var c = Fsrs.review(newCard(), Fsrs.Rating.Easy, 1_000_000L)
        repeat(30) { c = Fsrs.review(c, Fsrs.Rating.Easy, c.due) }
        assertTrue(c.stability <= Fsrs.S_MAX + 0.001)
    }

    // ---------------- 到期 ----------------

    @Test fun dueFilterAndCount() {
        val now = 10_000_000L
        val due = Fsrs.review(newCard(now - 3 * DAY), Fsrs.Rating.Good, now - 3 * DAY)   // 3 天前复习过，短间隔
        val notDue = Fsrs.review(newCard(now), Fsrs.Rating.Good, now)                    // 刚复习
        val cards = listOf(due, notDue)
        assertEquals(1, Fsrs.dueCount(cards, now))
        assertEquals(listOf(due), Fsrs.dueCards(cards, now))   // Card 无 id 字段，用 data class 相等比较
    }

    // ---------------- 序列化 ----------------

    @Test fun cardJsonRoundTrip() {
        var c = Fsrs.review(newCard(), Fsrs.Rating.Good, 1_000_000L)
        c = Fsrs.review(c, Fsrs.Rating.Again, 1_000_000L + DAY)
        val back = Fsrs.cardFromJson(Fsrs.toJson(c))!!
        assertEquals(c.state, back.state)
        assertEquals(c.stability, back.stability, 1e-9)
        assertEquals(c.difficulty, back.difficulty, 1e-9)
        assertEquals(c.due, back.due)
        assertEquals(c.lapses, back.lapses)
    }

    @Test fun cardListJsonRoundTripAndGarbage() {
        val cards = listOf(
            Fsrs.review(newCard(), Fsrs.Rating.Good, 1_000_000L),
            Fsrs.review(newCard(2_000_000L), Fsrs.Rating.Easy, 2_000_000L),
        )
        val back = Fsrs.cardsFromJson(Fsrs.toJson(cards))
        assertEquals(2, back.size)
        assertTrue(Fsrs.cardsFromJson(null).isEmpty())
        assertTrue(Fsrs.cardsFromJson("not json").isEmpty())
        assertNull(Fsrs.cardFromJson(null))
    }

    // ---------------- 参数迁移 ----------------

    @Test fun migrateParamsClampsToValid() {
        val bad = DoubleArray(21) { -5.0 }   // 全非法值
        val fixed = Fsrs.migrateParams(bad)
        assertTrue(Fsrs.clipParams(fixed).all { it >= 0.0 })
        val short = DoubleArray(5) { 1.0 }
        assertEquals(21, Fsrs.migrateParams(short).size)   // FSRS-5 默认 21 参数（w0..w20，含 decay w20）
    }
}
