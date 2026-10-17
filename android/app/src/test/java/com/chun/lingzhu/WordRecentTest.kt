package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v1.85.7：「点认识后同一个词短时间内反复弹出」的回归测试。纯 JUnit4，不碰 Android。
 *
 * 钉住两件事：
 *  1. **刚评完分的词不会被立刻再选**（[WordRecent.pick] 排掉 [WordRecent.WINDOW_MS] 内弹过的卡）；
 *  2. 排空之后不退化成纯随机，而是挑最久没露的那张（[WordRecent.stalest]）——
 *     这条正是原 bug 的病根：候选全被 0 权重 → `Words.pick` 的 `total <= 0` 退路纯随机 → 刚弹的又出来。
 */
class WordRecentTest {

    private val now = 1_700_000_000_000L   // 固定时刻，别用真实时钟

    private fun card(word: String) = Words.Card(word, "释义")

    /** 复现用户现场：give 被评了「认识」，短时间内又被选出来。 */
    @Test fun justJudgedWordIsNotPickedAgain() {
        val cards = listOf("en" to card("give"), "en" to card("take"), "en" to card("get"))
        val give = cards[0]
        val st = mapOf(Words.key("en", give.second) to Words.Stat(0, 0, 0))
        val recent = mutableMapOf<String, Long>()
        // 用户点了「认识」：WordSrs 已把它排到将来（不再是到期），但评分的 wordStats 还没落库
        WordRecent.mark(recent, Words.key("en", give.second), now)

        // 连选 500 次，give 一次都不该再出现
        val rnd = Random(7)
        repeat(500) {
            val got = WordRecent.pick(cards, emptyMap(), st, recent, now, rnd)
            assertTrue("刚评过分的 give 被立刻又选出来了", got != give)
        }
    }

    /** 同一个词连续弹三次的场景（用户实测 give 弹了三次）——逐次推进时间也不该立刻重复。 */
    @Test fun repeatedPicksNeverRepeatTheJustShownWord() {
        val cards = (1..4).map { "en" to card("w$it") }
        val recent = mutableMapOf<String, Long>()
        var t = now
        var last: Pair<String, Words.Card>? = null
        repeat(20) {
            val got = WordRecent.pick(cards, emptyMap(), emptyMap(), recent, t, Random(it))!!
            assertTrue("紧接着又弹了同一个词：${got.second.front}", got != last)
            WordRecent.mark(recent, Words.key(got.first, got.second), t)
            last = got
            t += 3_000L   // 每 3 秒一张，落在 10 分钟窗口内
        }
    }

    /** 窗口过了就该能再弹 —— 别把词永久藏起来。 */
    @Test fun wordReturnsAfterWindowExpires() {
        val cards = listOf("en" to card("give"))
        val recent = mutableMapOf<String, Long>()
        WordRecent.mark(recent, Words.key("en", cards[0].second), now)
        val after = WordRecent.WINDOW_MS + 1_000L
        assertEquals(cards[0], WordRecent.pick(cards, emptyMap(), emptyMap(), recent, now + after))
    }

    /**
     * 病根那条：候选全被排除时**不能**退回纯随机。
     * 只有一张卡、且刚弹过 → 就该再弹它（不能不弹），但库里有好几张时必须挑最久没露的那张。
     */
    @Test fun exhaustedPoolPicksStalestNotRandom() {
        val cards = listOf("en" to card("w1"), "en" to card("w2"), "en" to card("w3"))
        val recent = mutableMapOf<String, Long>()
        // w1 最早、w2 中间、w3 最新 —— 全部仍在窗口内
        cards.forEachIndexed { i, c -> recent[Words.key("en", c.second)] = now + i * 1_000L }
        // 全部弹过 → 取最久没露的 w1
        assertEquals(cards[0], WordRecent.pick(cards, emptyMap(), emptyMap(), recent, now + 10_000L))
        // 从没弹过的排最前（时间戳缺失 → Long.MIN_VALUE）
        val fresh = listOf("en" to card("new"), cards[0])
        assertEquals(fresh[0], WordRecent.pick(fresh, emptyMap(), emptyMap(), recent, now + 10_000L))
    }

    /** 库小到只剩一张刚弹过的：仍要能弹出来，不能因为「排空了」就不弹。 */
    @Test fun singleRecentCardStillShows() {
        val only = "en" to card("give")
        val recent = mutableMapOf(Words.key("en", only.second) to now)
        assertEquals(only, WordRecent.pick(listOf(only), emptyMap(), emptyMap(), recent, now))
        assertNull(WordRecent.pick(emptyList(), emptyMap(), emptyMap(), recent, now))
    }

    /** 记过的 key 会自己过期清理，map 不会随使用无界增长。 */
    @Test fun markPrunesExpiredEntries() {
        val recent = mutableMapOf<String, Long>()
        WordRecent.mark(recent, "k1", now)
        WordRecent.mark(recent, "k2", now)
        assertEquals(2, recent.size)
        WordRecent.mark(recent, "k3", now + WordRecent.WINDOW_MS + 1)
        assertEquals(setOf("k3"), recent.keys)
    }

    @Test fun isRecentTracksWindow() {
        val recent = mapOf("k" to now)
        assertTrue(WordRecent.isRecent(recent, "k", now))
        assertTrue(WordRecent.isRecent(recent, "k", now + WordRecent.WINDOW_MS - 1))
        assertFalse(WordRecent.isRecent(recent, "k", now + WordRecent.WINDOW_MS))
        assertFalse(WordRecent.isRecent(recent, "other", now))
    }

    /** 窗口默认与 Words.weight 的「刚出过 0 权重」同一条，别各写一个数。 */
    @Test fun windowMatchesWeightCooldown() {
        assertEquals(10 * 60_000L, WordRecent.WINDOW_MS)
    }

    /**
     * 排除最近弹过的之后，「FSRS 到期优先」这条口径仍然成立 —— 别把 v1.85 的复习节奏改坏。
     *
     * 三张卡：w1 刚弹过（该被排除）、w2 到期、w3 没到期也没弹过。期望稳定选 w2，
     * 而不是退回加权随机去挑 w3。
     */
    @Test fun dueCardsStillWinAfterExclusion() {
        val shown = "en" to card("w1")
        val dueC = "en" to card("w2")
        val plain = "en" to card("w3")
        val cards = listOf(shown, dueC, plain)
        // w2 已复习过、到期时间已过 → Fsrs 判它到期；w3 没有记录（不算到期，见 WordSrs.pickDue 口径）
        val srs = mapOf(Words.key("en", dueC.second) to Fsrs.Card(state = Fsrs.State.Review, due = now - 1_000L))
        val recent = mutableMapOf(Words.key("en", shown.second) to now)   // w1 刚弹过 → 排除
        repeat(50) { assertEquals(dueC, WordRecent.pick(cards, srs, emptyMap(), recent, now, Random(it))) }
        // 反过来：w2 刚弹过 → 排除掉它，就该在剩下里挑（不能因为排除而卡住不弹）
        val recent2 = mapOf(Words.key("en", dueC.second) to now)
        assertTrue(WordRecent.pick(cards, srs, emptyMap(), recent2, now) != dueC)
    }
}
