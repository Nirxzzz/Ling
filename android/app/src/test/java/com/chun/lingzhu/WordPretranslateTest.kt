package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPretranslateTest {
    private val day = 24L * 3600 * 1000

    @Test fun cacheRoundTripAndKey() {
        val m = WordPretranslate.put(emptyMap(), WordPretranslate.key("en", "Abandon"), "放弃", 1000)
        assertEquals("放弃", WordPretranslate.get(m, WordPretranslate.key("en", "abandon"), 2000))   // 大小写归一
        assertNull(WordPretranslate.get(m, WordPretranslate.key("mistakes", "abandon"), 2000))       // 键带词库
        assertEquals(m, WordPretranslate.parse(WordPretranslate.toJson(m)))
        assertEquals(emptyMap<String, WordPretranslate.Entry>(), WordPretranslate.parse("坏"))
        assertEquals(emptyMap<String, WordPretranslate.Entry>(), WordPretranslate.parse(null))
    }

    @Test fun expiredEntriesMissAndDrop() {
        var m = WordPretranslate.put(emptyMap(), "k1", "旧译文", 0)
        val ttl = WordPretranslate.TTL_MS
        assertNull(WordPretranslate.get(m, "k1", ttl))                 // 整 30 天 = 过期
        assertEquals("旧译文", WordPretranslate.get(m, "k1", ttl - 1)) // 还差 1ms 不过期
        // put 的时候过期条目顺手清掉
        m = WordPretranslate.put(m, "k2", "新译文", ttl + 1)
        assertNull(WordPretranslate.get(m, "k1", ttl + 1))
        assertEquals("新译文", WordPretranslate.get(m, "k2", ttl + 1))
    }

    /** 单词释义稳定 → 与翻译同档（30 天）。7 天就过期的话，单词书里同一个词反复翻全是白花钱 */
    @Test fun ttlFollowsTranslateTier() {
        assertEquals(Cache.TTL_TRANSLATE_MS, WordPretranslate.TTL_MS)
        assertEquals(30L * 24 * 3600 * 1000, WordPretranslate.TTL_MS)
        val m = WordPretranslate.put(emptyMap(), "k", "释义", 0)
        assertNotNull(WordPretranslate.get(m, "k", 7 * day))          // 7 天档早该过期，这里还在
        assertNotNull(WordPretranslate.get(m, "k", 29 * day))
        assertNull(WordPretranslate.get(m, "k", 30 * day))
        assertNull(WordPretranslate.get(m, "k", 30 * day, Cache.TTL_SOLVE_MS))   // 显式给识别档就按 7 天算
    }

    @Test fun pendingPicksMissingAndCapsAt20() {
        var m = emptyMap<String, WordPretranslate.Entry>()
        val cards = (1..30).map { "en" to Words.Card("w$it", "") }.map { it.first to it.second.front }
        val batch = WordPretranslate.pending(m, cards, 1000)
        assertEquals(20, batch.size)                                        // 一轮最多 20 个
        assertEquals("w1", batch.first().second); assertEquals("w20", batch.last().second)
        // 命中缓存的不再进批次
        m = WordPretranslate.put(m, WordPretranslate.key("en", "w1"), "译文1", 900)
        val b2 = WordPretranslate.pending(m, cards, 1000)
        assertFalse(b2.any { it.second == "w1" })
        assertEquals(20, b2.size)
        // 全部命中 → 空批次
        val full = cards.fold(m) { acc, (_, w) -> WordPretranslate.put(acc, WordPretranslate.key("en", w), "v", 900) }
        assertTrue(WordPretranslate.pending(full, cards, 1000).isEmpty())
        // 空词与重复词跳过
        assertTrue(WordPretranslate.pending(m, listOf("en" to ""), 1000).isEmpty())
        assertEquals(1, WordPretranslate.pending(m, listOf("en" to "w2", "en" to "w2"), 1000).size)
    }

    @Test fun canFetchOfflineAndCooldown() {
        val now = 5_000_000L
        assertTrue(WordPretranslate.canFetch(isOnline = true, lastFailAt = 0, now = now))
        assertFalse(WordPretranslate.canFetch(isOnline = false, lastFailAt = 0, now = now))              // 无网不试
        assertFalse(WordPretranslate.canFetch(true, now - 1000, now))                                     // 冷却期内
        assertTrue(WordPretranslate.canFetch(true, now - WordPretranslate.FAIL_COOLDOWN_MS, now))         // 冷却已过
    }

    @Test fun fetchAllSilentlySkipsFailures() {
        // 两个成一个败：成功的照常返回，不抛
        val (ok, failed) = WordPretranslate.fetchAll(listOf("en" to "good", "en" to "bad")) { w ->
            if (w == "bad") throw RuntimeException("DeepL 挂了")
            "译-" + w
        }
        assertFalse(failed)
        assertEquals("译-good", ok[WordPretranslate.key("en", "good")])
        assertFalse(ok.containsKey(WordPretranslate.key("en", "bad")))
        // 全败 = 整体失败（外面记冷却）
        val (ok2, failed2) = WordPretranslate.fetchAll(listOf("en" to "x")) { throw RuntimeException() }
        assertTrue(failed2); assertTrue(ok2.isEmpty())
        // 空批次：不发请求也不算失败
        val (ok3, failed3) = WordPretranslate.fetchAll(emptyList()) { throw RuntimeException() }
        assertFalse(failed3); assertTrue(ok3.isEmpty())
    }

    /**
     * v1.78 回归：fetchAll 写下来的键必须能被 lookup 读到。
     * 以前这里写的是 key("", word)、读缓存一律走 key(deck, word) —— 写进去的永远读不出来，预翻译白翻。
     */
    @Test fun fetchAllKeysAreReadableByLookup() {
        val (ok, failed) = WordPretranslate.fetchAll(listOf("wb_junior" to "sixth")) { "第六" }
        assertFalse(failed)
        var m = emptyMap<String, WordPretranslate.Entry>()
        for ((k, v) in ok) m = WordPretranslate.put(m, k, v, 1000)
        assertEquals("第六", WordPretranslate.get(m, WordPretranslate.key("wb_junior", "sixth"), 2000))
        assertNull(WordPretranslate.get(m, WordPretranslate.key("", "sixth"), 2000))          // 不再写不带词库的键
        assertNull(WordPretranslate.get(m, WordPretranslate.key("wb_cet4", "sixth"), 2000))   // 也不串到别的词库
    }

    @Test fun storeCapsAtMaxEntries() {
        var m = emptyMap<String, WordPretranslate.Entry>()
        for (i in 1..(WordPretranslate.STORE_MAX + 10)) m = WordPretranslate.put(m, "k$i", "v$i", i.toLong())
        assertEquals(WordPretranslate.STORE_MAX, m.size)
        assertFalse(m.containsKey("k1"))                 // 最旧的被挤掉
        assertTrue(m.containsKey("k${WordPretranslate.STORE_MAX + 10}"))
        // 同键覆盖不涨
        m = WordPretranslate.put(m, "k${WordPretranslate.STORE_MAX + 10}", "新", 99999)
        assertEquals(WordPretranslate.STORE_MAX, m.size)
    }

    /** 导入词库后的全量预翻译：一条假翻译线，验证 runBatches 的三种停止语义 */
    @Test fun runBatchesStopsOnEmptyBatch() {
        var calls = 0
        // 第 3 批返回 0（没词了 / 无网 / 整批失败）就该收手，不能再发第 4 次
        val total = WordPretranslate.runBatches(50) { calls++; if (calls <= 3) 20 else 0 }
        assertEquals(60, total)
        assertEquals(4, calls)
        // 一开始就 0：只探一次，什么都不做
        calls = 0
        assertEquals(0, WordPretranslate.runBatches(50) { calls++; 0 })
        assertEquals(1, calls)
    }

    @Test fun runBatchesStopsAtMaxBatches() {
        var calls = 0
        val total = WordPretranslate.runBatches(5) { calls++; 20 }
        assertEquals(100, total)
        assertEquals(5, calls)                              // 额度保险丝：50 批 = 1000 词
        assertEquals(1000, WordPretranslate.PREFETCH_ALL_MAX_BATCHES * WordPretranslate.BATCH_MAX)
        assertEquals(0, WordPretranslate.runBatches(0) { 20 })   // 上限 0 = 一次都不发
    }

    @Test fun runBatchesStopsOnFailureAndNegative() {
        var calls = 0
        // 前两批成功、第三批失败（prefetch 在整批失败时返回 0 并记冷却）：到 3 为止
        val total = WordPretranslate.runBatches(50) { calls++; if (calls <= 2) 20 else 0 }
        assertEquals(40, total); assertEquals(3, calls)
        // 负数（内部异常）同样视为收手，且不计入总数
        calls = 0
        val t2 = WordPretranslate.runBatches(50) { calls++; if (calls == 1) 20 else -1 }
        assertEquals(20, t2); assertEquals(2, calls)
    }
}
