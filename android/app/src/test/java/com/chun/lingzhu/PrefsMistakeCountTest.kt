package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 「不认识的词」条数的口径钉子：**从没背过（无 stat / seen=0）的词不算错词**，
 * 只有「见过且 streak=0」才算 —— 判据就是 [Words.unknown]，不许另立一套。
 *
 * 实现是「先把 [Words.unknown] 为真的牌面按词库归组，再遍历卡片数命中」，与逐卡
 * `Words.unknown(stats[Words.key(deck, card)])` 必须逐条相等。下面用**真的** [Words.unknown]
 * 逐卡数一遍作对照，不复制一份判据进测试（复制出来的判据一起写错就测不出来）。
 */
class PrefsMistakeCountTest {

    private fun key(deck: String, front: String) = Words.key(deck, Words.Card(front, "x"))

    /** 对照口径：逐卡查 [Words.unknown]（慢，但就是用户要的语义） */
    private fun perCardMistakes(decks: List<Pair<String, List<Words.Card>>>, st: Map<String, Words.Stat>): Int {
        var total = 0
        for ((id, cards) in decks) total += cards.count { Words.unknown(st[Words.key(id, it)]) }
        return total
    }

    private fun mistakeCount(decks: List<Pair<String, List<Words.Card>>>, st: Map<String, Words.Stat>): Int =
        Prefs.countMistakes(decks, st, Prefs::decodeKey)

    private fun deck(id: String, n: Int): Pair<String, List<Words.Card>> =
        id to (1..n).map { Words.Card("w-$id-$it", "释义 $it") }

    // ── 行为：没见过 ≠ 不认识 ────────────────────────────

    @Test
    fun emptyStatsCountsNothing() {
        val decks = listOf(deck("a", 3), deck("b", 5))
        assertEquals(0, mistakeCount(decks, emptyMap()))
        assertEquals(0, Prefs.countMistakes(emptyList(), emptyMap(), Prefs::decodeKey))
    }

    @Test
    fun unseenDeckIsNotCounted() {
        val decks = listOf(deck("touched", 5), deck("untouched", 7))
        val st = mapOf(
            key("touched", "w-touched-1") to Words.Stat(streak = 0, seen = 3),   // 见过、没连对 → 错词
            key("touched", "w-touched-2") to Words.Stat(streak = 2, seen = 2),   // 认识
        )
        assertEquals(1, mistakeCount(decks, st))
    }

    @Test
    fun seenZeroStatsAreNotMistakes() {
        // seen=0（弹过没评 / 老数据）与「从没背过」同义，不能算错词
        val decks = listOf(deck("a", 4))
        val st = mapOf(
            key("a", "w-a-1") to Words.Stat(streak = 0, seen = 0),
            key("a", "w-a-2") to Words.Stat(streak = 0, seen = 0),
            key("a", "w-a-3") to Words.Stat(streak = 0, seen = 1),
        )
        assertEquals(1, mistakeCount(decks, st))
    }

    @Test
    fun mixedDeckWithSeenKnownUnseenAndSeenZero() {
        // 同一本里混着四类卡：不许只把「整本没 stat」的分支改掉，而漏掉混合词库里的 seen=0 卡
        val decks = listOf(deck("a", 6))
        val st = mapOf(
            key("a", "w-a-1") to Words.Stat(streak = 0, seen = 4),   // 错词
            key("a", "w-a-2") to Words.Stat(streak = 1, seen = 3),   // 认识过但没连对 → 不是 unknown
            key("a", "w-a-3") to Words.Stat(streak = 3, seen = 3),   // 掌握
            key("a", "w-a-4") to Words.Stat(streak = 0, seen = 0),   // 没背过 → 不算
            // w-a-5 / w-a-6 完全没有 stat → 不算
        )
        assertEquals(1, mistakeCount(decks, st))
    }

    @Test
    fun equalToPerCardWordsUnknownOnMixedDecks() {
        // 部分已见错词 + 未学 + seen0 + 掌握混在一起，逐卡判据必须与实现逐条相等
        val decks = listOf(deck("a", 40), deck("b", 25), deck("c", 13), deck("never", 9))
        val st = HashMap<String, Words.Stat>()
        var i = 0
        for ((id, cards) in decks) for (c in cards) {
            i++
            when (i % 6) {
                0 -> st[key(id, c.front)] = Words.Stat(streak = 0, seen = 2)   // 错词
                1 -> st[key(id, c.front)] = Words.Stat(streak = 0, seen = 1)   // 错词
                2 -> st[key(id, c.front)] = Words.Stat(streak = 1, seen = 1)
                3 -> st[key(id, c.front)] = Words.Stat(streak = 3, seen = 3)
                4 -> st[key(id, c.front)] = Words.Stat(streak = 0, seen = 0)   // 没背过
                // 5 → 无 stat
            }
        }
        val expected = perCardMistakes(decks, st)
        assertEquals(expected, mistakeCount(decks, st))
        // 顺手钉住对照值不是恒等式：i%6 为 0、1 的卡（每本末位可能不足 6 张，按实际数）才是错词
        var bad = 0
        var j = 0
        for ((_, cards) in decks) for (c in cards) { j++; if (j % 6 == 0 || j % 6 == 1) bad++ }
        assertEquals(bad, expected)
        assertTrue("错词应当既不是 0 也不是全部", expected in 1 until decks.sumOf { it.second.size })
    }

    @Test
    fun randomStatsAlwaysMatchPerCardJudge() {
        val rnd = Random(20261020)
        repeat(50) { round ->
            val decks = listOf(deck("d1", rnd.nextInt(0, 30)), deck("d2", rnd.nextInt(0, 30)), deck("d3", rnd.nextInt(0, 10)))
            val st = HashMap<String, Words.Stat>()
            for ((id, cards) in decks) for (c in cards) {
                when (rnd.nextInt(5)) {
                    0 -> st[key(id, c.front)] = Words.Stat(streak = 0, seen = rnd.nextInt(1, 5))
                    1 -> st[key(id, c.front)] = Words.Stat(streak = rnd.nextInt(1, 4), seen = rnd.nextInt(1, 5))
                    2 -> st[key(id, c.front)] = Words.Stat(streak = 0, seen = 0)
                    // 3、4 → 无 stat
                }
            }
            assertEquals("round $round", perCardMistakes(decks, st), mistakeCount(decks, st))
        }
    }

    // ── 不回退：老脏 key 既不进错词，也不串到别的词库 ─────────

    @Test
    fun badStatKeysAreIgnoredNotCountedAsDeck() {
        val decks = listOf(deck("a", 3))
        val st = mapOf(
            "noNulSeparator" to Words.Stat(streak = 0, seen = 3),                  // 切不出词库前缀
            "\u0000leading" to Words.Stat(streak = 0, seen = 3),                   // 前缀为空
            "a" to Words.Stat(streak = 0, seen = 3),                               // 没有分隔符
            key("a", "w-a-1") to Words.Stat(streak = 0, seen = 2),                 // 唯一一条真错词
            key("a", "w-a-2") to Words.Stat(streak = 1, seen = 2),
        )
        assertEquals(1, mistakeCount(decks, st))
        assertEquals(perCardMistakes(decks, st), mistakeCount(decks, st))
    }

    @Test
    fun frontContainingSeparatorIsStillMatched() {
        // front 里带 '\u0000' 是脏数据：decodeKey 只切第一个分隔符，卡片正面必须整段相等才算命中
        val cards = listOf(Words.Card("a\u0000b", "x"), Words.Card("a", "y"))
        val decks = listOf("d" to cards)
        val st = mapOf("d\u0000a\u0000b" to Words.Stat(streak = 0, seen = 1))
        assertEquals(1, mistakeCount(decks, st))
        assertEquals(perCardMistakes(decks, st), mistakeCount(decks, st))
    }

    // ── countMistakesInDeck：没有错词集合 = 0 ────────────────

    @Test
    fun countMistakesInDeckWithoutBadSetIsZero() {
        val cards = listOf(Words.Card("a", "1"), Words.Card("b", "2"))
        assertEquals(0, Prefs.countMistakesInDeck(cards, null))
        assertEquals(0, Prefs.countMistakesInDeck(cards, emptySet()))
        assertEquals(2, Prefs.countMistakesInDeck(cards, setOf("a", "b")))
        assertEquals(1, Prefs.countMistakesInDeck(cards, setOf("b")))
    }
}
