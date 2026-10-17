package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KnowledgePopupPolicy] 的纯逻辑单测（JVM 直跑，不碰 Android）：
 * 选卡口径（due-first / new / 不弹未来到期 / 不立刻重复）与放行门槛。
 */
class KnowledgePopupPolicyTest {

    private val T = 1_000_000_000L

    /** 新卡（未学）：state=New、due 无意义。 */
    private fun fresh(id: Long, deck: String = "d", created: Long = T) =
        KnowledgeCard.KCData(id, deck, "q$id", "a$id", "import", created, Fsrs.Card())

    /** 已学卡：state=Review、给定到期时刻。 */
    private fun learned(id: Long, due: Long, deck: String = "d", created: Long = T) =
        KnowledgeCard.KCData(id, deck, "q$id", "a$id", "import", created,
            Fsrs.Card(state = Fsrs.State.Review, due = due))

    // ---------------- 选卡 ----------------

    @Test fun emptyReturnsNull() {
        assertNull(KnowledgePopupPolicy.pick(emptyList(), emptyMap(), T))
    }

    @Test fun prefersDueOverNew() {
        val cards = listOf(fresh(1), learned(2, due = T - 1))
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, emptyMap(), T)!!.id)
    }

    @Test fun picksMostOverdueFirst() {
        val cards = listOf(learned(1, due = T - 5), learned(2, due = T - 60))
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, emptyMap(), T)!!.id)
    }

    @Test fun newCardsChosenOldestCreatedFirst() {
        val cards = listOf(fresh(1, created = T + 10), fresh(2, created = T - 10))
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, emptyMap(), T)!!.id)
    }

    @Test fun neverPicksFutureDueCard() {
        // 唯一一张是「已学但还没到期」→ 不弹将来才该复习的卡
        val cards = listOf(learned(1, due = T + Fsrs.DAY_MS))
        assertNull(KnowledgePopupPolicy.pick(cards, emptyMap(), T))
    }

    @Test fun futureDueIgnoredWhenOtherEligible() {
        val cards = listOf(learned(1, due = T + Fsrs.DAY_MS), fresh(2))
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, emptyMap(), T)!!.id)
    }

    @Test fun dueExactlyNowCountsAsDue() {
        val cards = listOf(fresh(1), learned(2, due = T))
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, emptyMap(), T)!!.id)
    }

    // ---------------- 不立刻重复 ----------------

    @Test fun skipsRecentlyShownInFavorOfFresh() {
        val cards = listOf(learned(1, due = T - 10), learned(2, due = T - 1))
        val recent = mapOf(1L to T)   // id=1 刚弹过
        assertEquals(2L, KnowledgePopupPolicy.pick(cards, recent, T)!!.id)
    }

    @Test fun allRecentReturnsNullInsteadOfRepeating() {
        val cards = listOf(fresh(1), fresh(2))
        val recent = mapOf(1L to T - 1000, 2L to T)   // 两张都在窗口内
        // 全被「刚弹过」排空 → 不弹（宁可空一轮，也不在窗口内重复同一张）
        assertNull(KnowledgePopupPolicy.pick(cards, recent, T))
    }

    @Test fun singletonRecentlyShownStaysSilentUntilWindowExpires() {
        val cards = listOf(fresh(1))
        assertNull("只有一张、刚弹过 → 不能立刻再弹", KnowledgePopupPolicy.pick(cards, mapOf(1L to T), T))
        // 窗口还差 1ms 没到 → 仍不弹
        assertNull(KnowledgePopupPolicy.pick(cards, mapOf(1L to T - KnowledgePopupPolicy.WINDOW_MS + 1), T))
        // 窗口已过 → 可以再弹
        assertEquals(1L, KnowledgePopupPolicy.pick(cards, mapOf(1L to T - KnowledgePopupPolicy.WINDOW_MS), T)!!.id)
    }

    @Test fun allNewCardsRecentlyShownReturnNull() {
        val cards = listOf(fresh(1), fresh(2), fresh(3))
        val recent = mapOf(1L to T, 2L to T - 1, 3L to T - 2)
        assertNull(KnowledgePopupPolicy.pick(cards, recent, T))
    }

    @Test fun recentWindowExpires() {
        val cards = listOf(fresh(1))
        // 窗口之外 → 不算「刚弹过」，可再弹
        val recent = mapOf(1L to T - KnowledgePopupPolicy.WINDOW_MS)
        assertFalse(KnowledgePopupPolicy.isRecent(recent, 1L, T))
        assertEquals(1L, KnowledgePopupPolicy.pick(cards, recent, T)!!.id)
    }

    @Test fun markPrunesExpiredEntries() {
        val recent = mutableMapOf(1L to T - KnowledgePopupPolicy.WINDOW_MS - 1, 2L to T)
        KnowledgePopupPolicy.mark(recent, 3L, T)
        assertFalse(recent.containsKey(1L))
        assertTrue(recent.containsKey(2L))
        assertEquals(T, recent[3L])
    }

    // ---------------- 放行门槛 ----------------

    private fun gate(
        on: Boolean = true, orbWanted: Boolean = true, muted: Boolean = false, orbHidden: Boolean = false,
        busy: Boolean = false, panelOpen: Boolean = false, menuOpen: Boolean = false,
        interactive: Boolean = true, quiet: Boolean = false,
    ) = KnowledgePopupPolicy.Gate(on, orbWanted, muted, orbHidden, busy, panelOpen, menuOpen, interactive, quiet)

    @Test fun gatePassesWhenAllClear() {
        assertTrue(KnowledgePopupPolicy.shouldPop(gate()))
    }

    @Test fun gateBlocksOnAnySuppressor() {
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(on = false)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(orbWanted = false)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(muted = true)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(orbHidden = true)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(busy = true)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(panelOpen = true)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(menuOpen = true)))
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(interactive = false)))   // 锁屏 / 息屏
        assertFalse(KnowledgePopupPolicy.shouldPop(gate(quiet = true)))
    }

    @Test fun quietNowFollowsPopupPolicy() {
        assertFalse(KnowledgePopupPolicy.quietNow(23 * 60, quietOn = false, start = 22 * 60, end = 7 * 60))
        assertTrue(KnowledgePopupPolicy.quietNow(23 * 60, quietOn = true, start = 22 * 60, end = 7 * 60))
        assertFalse(KnowledgePopupPolicy.quietNow(12 * 60, quietOn = true, start = 22 * 60, end = 7 * 60))
    }

    // ---------------- 弹窗间隔常量边界（防止改常量改出矛盾区间）----------------

    @Test fun knowledgeIntervalConstantsFormAValidRange() {
        assertTrue(Prefs.KNOWLEDGE_EVERY_SEC_MIN < Prefs.KNOWLEDGE_EVERY_SEC_DEF)
        assertTrue(Prefs.KNOWLEDGE_EVERY_SEC_DEF < Prefs.KNOWLEDGE_EVERY_SEC_MAX)
        assertTrue(Prefs.KNOWLEDGE_EVERY_SEC_DEF in Prefs.KNOWLEDGE_EVERY_SEC_MIN..Prefs.KNOWLEDGE_EVERY_SEC_MAX)
        assertEquals(300, Prefs.KNOWLEDGE_EVERY_SEC_DEF)   // 冻结默认值：5 分钟
    }
}
