package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 速记：标签推荐。
 *
 * 推荐错不算致命，但「每次都推一样的」会让用户直接关掉这个功能。
 * 所以衰减和排序是这里的重点。
 */
class TagSuggestTest {

    private val day = 24L * 3600L * 1000L

    private fun u(name: String, count: Int, daysAgo: Double, now: Long) =
        TagSuggest.Usage(name, count, now - (daysAgo * day).toLong())

    // ── 排序与衰减 ───────────────────────────────────────

    @Test fun frequentTagsComeFirst() {
        val now = 1000L * day
        val list = listOf(u("a", 10, 1.0, now), u("b", 2, 1.0, now))
        assertEquals(listOf("a", "b"), TagSuggest.recent(list, now))
    }

    @Test fun oldTagsFade() {
        val now = 1000L * day
        // a 用得多但很旧（56 天 = 4 个半衰期 → 除以 16），b 少但新鲜
        val list = listOf(u("a", 8, 56.0, now), u("b", 1, 1.0, now))
        assertEquals("新鲜的小频次要赢过陈旧的大频次", listOf("b", "a"), TagSuggest.recent(list, now))
    }

    @Test fun halfLifeIs14Days() {
        val now = 1000L * day
        val fresh = TagSuggest.score(u("x", 1, 0.0, now), now)
        val oneHalf = TagSuggest.score(u("x", 1, 14.0, now), now)
        assertEquals("14 天正好减半", 0.5, oneHalf / fresh, 0.01)
    }

    @Test fun zeroCountIsIgnored() {
        val now = 1000L * day
        val list = listOf(u("never", 0, 0.0, now), u("used", 1, 0.0, now))
        assertEquals(listOf("used"), TagSuggest.recent(list, now))
    }

    @Test fun recentIsCapped() {
        val now = 1000L * day
        val many = (1..20).map { u("t" + it, 20 - it, 0.0, now) }
        assertTrue(TagSuggest.recent(many, now).size <= TagSuggest.MAX_RECENT)
    }

    // ── 会话延续 ─────────────────────────────────────────

    @Test fun sessionTagsGoFirst() {
        val now = 1000L * day
        val got = TagSuggest.withSession(now - 60_000L, listOf("数学"), listOf("英语", "数学"), now)
        assertEquals("会话标签排第一且不重复", listOf("数学", "英语"), got)
    }

    @Test fun staleSessionIsIgnored() {
        val now = 1000L * day
        // 11 分钟前 > 10 分钟窗口
        val got = TagSuggest.withSession(now - 11L * 60L * 1000L, listOf("数学"), listOf("英语"), now)
        assertEquals(listOf("英语"), got)
    }

    @Test fun noLastNoteIsFine() {
        val now = 1000L * day
        assertEquals(listOf("英语"), TagSuggest.withSession(0L, emptyList(), listOf("英语"), now))
    }

    // ── 本地相似 ─────────────────────────────────────────

    @Test fun matchesTagInDraft() {
        val now = 1000L * day
        val list = listOf(u("数学", 5, 1.0, now), u("英语", 5, 1.0, now))
        assertEquals(listOf("数学"), TagSuggest.similar("这题数学不会", list, now))
    }

    @Test fun matchesSubTagOfHierarchy() {
        val now = 1000L * day
        val list = listOf(u("数学/导数", 3, 1.0, now))
        // 命中子段「导数」也算
        assertEquals(listOf("数学/导数"), TagSuggest.similar("导数求导法则", list, now))
    }

    @Test fun longerMatchWins() {
        val now = 1000L * day
        // 「导数的定义」比「导数」命中更长，应该排前面
        val list = listOf(u("导数", 10, 1.0, now), u("导数的定义", 1, 1.0, now))
        assertEquals("导数的定义", TagSuggest.similar("导数的定义这题", list, now)[0])
    }

    @Test fun blankDraftSuggestsNothing() {
        val now = 1000L * day
        assertTrue(TagSuggest.similar("  ", listOf(u("数学", 5, 0.0, now)), now).isEmpty())
    }

    @Test fun similarIsCapped() {
        val now = 1000L * day
        val many = (1..10).map { u("数学" + it, 5, 0.0, now) }
        assertTrue(TagSuggest.similar("数学", many, now).size <= TagSuggest.MAX_SUGGEST)
    }
}
