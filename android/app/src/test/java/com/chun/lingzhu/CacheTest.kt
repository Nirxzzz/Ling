package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheTest {
    @Test fun getBumpsAndExpires() {
        var l = Cache.put(emptyList(), "a", "A", 1000, 3)
        l = Cache.put(l, "b", "B", 2000, 3)
        val (hit, l2) = Cache.get(l, "a", 3000)
        assertEquals("A", hit!!.v); assertEquals(1000, hit.created); assertEquals(3000, hit.used)
        assertEquals(listOf("a", "b"), l2.map { it.k })                         // 命中的挪到最前
        assertNull(Cache.get(l, "zz", 3000).first)
        assertNull(Cache.get(l, "a", 1000 + Cache.TTL_MS).first)                 // 过期
        assertEquals(listOf("b"), Cache.get(l, "b", 1000 + Cache.TTL_MS).second.map { it.k })
    }

    @Test fun putReplacesAndCaps() {
        var l = emptyList<Cache.Item>()
        for (i in 1..5) l = Cache.put(l, "k$i", "v$i", i.toLong(), 3)
        assertEquals(listOf("k5", "k4", "k3"), l.map { it.k })
        l = Cache.put(l, "k3", "new", 9, 3)
        assertEquals(listOf("k3", "k5", "k4"), l.map { it.k }); assertEquals("new", l[0].v)
        assertEquals(l, Cache.parse(Cache.toJson(l)))
        assertEquals(emptyList<Cache.Item>(), Cache.parse("坏"))
    }

    @Test fun keysAndFingerprint() {
        assertNotEquals(Cache.key("ab", "c"), Cache.key("a", "bc"))
        val w = 10; val h = 50
        val img = IntArray(w * h) { (it * 7) % 256 }
        val base = Cache.fingerprint(img, w, h)
        // 状态栏（最上面 6%）变了：指纹不变
        val clock = img.copyOf().also { for (x in 0 until w) it[x] = 255 - it[x] }
        assertEquals(base, Cache.fingerprint(clock, w, h))
        // 细微噪声（同一个 16 级灰度内）：不变
        val noise = img.copyOf().also { for (i in it.indices) it[i] = (it[i] and 0xF0) or 0x03 }
        assertEquals(Cache.fingerprint(noise, w, h), Cache.fingerprint(img.copyOf().also { for (i in it.indices) it[i] = (it[i] and 0xF0) or 0x0C }, w, h))
        // 正文里一个像素变了：指纹变（宁可不命中）
        val changed = img.copyOf().also { it[30 * w + 5] = (it[30 * w + 5] + 128) % 256 }
        assertNotEquals(base, Cache.fingerprint(changed, w, h))
    }

    @Test fun solveKeyDependsOnSettings() {
        val api = ApiConfig("https://a/v1/", "k", "m")
        val k1 = Cache.solveKey("fp", Solver.Options("exam", "standard", false), api)
        assertEquals(k1, Cache.solveKey("fp", Solver.Options("exam", "standard", false), ApiConfig("https://a/v1", "other-key", "m")))   // Key 不同不影响（同一个接口）
        assertNotEquals(k1, Cache.solveKey("fp", Solver.Options("exam", "detailed", false), api))
        assertNotEquals(k1, Cache.solveKey("fp", Solver.Options("exam", "standard", true), api))
        assertNotEquals(k1, Cache.solveKey("fp", Solver.Options("exam", "standard", false), ApiConfig("https://a/v1", "k", "m2")))
        assertNotEquals(k1, Cache.solveKey("fp2", Solver.Options("exam", "standard", false), api))
        assertEquals("刚刚", Cache.ago(0, 30_000)); assertEquals("5 分钟前", Cache.ago(0, 300_000)); assertEquals("2 小时前", Cache.ago(0, 7_200_000))
    }

    /** v1.66：「识别关键词」开关进缓存键 —— 开着时的键和关着 / 老版本（没有关键词行）的不同 */
    @Test fun solveKeyIncludesKeywordsSwitch() {
        val api = ApiConfig("https://a/v1", "k", "m")
        val on = Cache.solveKey("fp", Solver.Options("general", "standard", false), api)   // 默认开
        assertEquals(on, Cache.solveKey("fp", Solver.Options("general", "standard", false, "", true), api))
        assertNotEquals(on, Cache.solveKey("fp", Solver.Options("general", "standard", false, "", false), api))
    }

    /** TTL 分档：识别/追问 7 天、翻译 30 天、搜索 6 小时 —— 各自取到该取的档 */
    @Test fun ttlTiers() {
        val day = 24L * 3600 * 1000
        assertEquals(7L * day, Cache.TTL_SOLVE_MS)
        assertEquals(30L * day, Cache.TTL_TRANSLATE_MS)
        assertEquals(6L * 3600 * 1000, Cache.TTL_SEARCH_MS)
        assertEquals(Cache.TTL_SOLVE_MS, Cache.TTL_MS)          // 纯函数默认档仍是识别档
        assertEquals(Cache.TTL_SEARCH_MS, WebSearch.TTL_MS)     // 搜索那档从 Cache 取，两边不会漂
    }

    /** 30 天档：翻译条目放满 30 天都还在（7 天档会早就过期）；识别档的判定不受影响 */
    @Test fun translateTtlOutlivesSolveTtl() {
        val day = 24L * 3600 * 1000
        val l = Cache.put(emptyList(), "t", "译文", 0, 10, Cache.TTL_TRANSLATE_MS)
        assertEquals("译文", Cache.get(l, "t", 29 * day, Cache.TTL_TRANSLATE_MS).first!!.v)
        assertNull(Cache.get(l, "t", 30 * day, Cache.TTL_TRANSLATE_MS).first)
        // 同一条目按识别档看：7 天就过期了（各档独立，不互相污染）
        assertNull(Cache.get(l, "t", 7 * day, Cache.TTL_SOLVE_MS).first)
        assertEquals("译文", Cache.get(l, "t", 7 * day - 1, Cache.TTL_SOLVE_MS).first!!.v)
        // put 时也按档清：识别档下 10 天前的老条目在翻译档里要留着
        val kept = Cache.put(l, "u", "新译文", 10 * day, 10, Cache.TTL_TRANSLATE_MS)
        assertEquals(setOf("u", "t"), kept.map { it.k }.toSet())
        val dropped = Cache.put(l, "u", "新译文", 10 * day, 10, Cache.TTL_SOLVE_MS)
        assertEquals(listOf("u"), dropped.map { it.k })
    }
}
