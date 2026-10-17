package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentMemory] 纯函数的断言。每条都在钉一个决定，注释写清为什么这个边界重要。
 */
class AgentMemoryTest {

    private val DAY = 86_400_000L
    private fun mem(
        content: String, category: String = "FACT", importance: Double = 0.5,
        at: Long = 0L, uses: Int = 1, lastUsed: Long = 0L,
    ) = AgentMemory.Mem(content, category, importance, at, uses, lastUsed)

    // ---------------- bigrams ----------------

    @Test
    fun bigramsDropStopwordsSoMyCatAndYourCatShareLess() {
        val a = AgentMemory.bigrams("我的猫很粘人")
        val b = AgentMemory.bigrams("你的猫呢")
        // 「的」是停用字：剥掉之后两边只剩「猫」相关，交集不该把整句拉满
        assertTrue(AgentMemory.similarity(a, b) < 0.5)
    }

    @Test
    fun bigramsKeepLatinWordsWhole() {
        // 拉丁词要整词匹配：「java」不能被切成 ja/av/va，否则「javascript」会撞上「java」
        val a = AgentMemory.bigrams("我在学 java")
        val b = AgentMemory.bigrams("java 入门")
        assertTrue(AgentMemory.similarity(a, b) > 0.3)
        val c = AgentMemory.bigrams("javascript 入门")
        assertTrue("整词不匹配子串", AgentMemory.similarity(b, c) < AgentMemory.similarity(a, b))
    }

    @Test
    fun emptyQueryNeverScores() {
        assertEquals(0.0, AgentMemory.score(AgentMemory.bigrams(""), AgentMemory.unigrams(""), 0, mem("有内容")), 0.0)
        assertEquals(0.0, AgentMemory.similarity(emptySet(), setOf("a")), 0.0)
    }

    // ---------------- score / top ----------------

    @Test
    fun recencyDecayHalvesAtHalfLife() {
        val now = 100L * DAY
        val fresh = mem("用户养了一只橘猫", at = now - DAY, lastUsed = now - DAY)
        val stale = mem("用户养了一只橘猫", at = 0, lastUsed = 0)   // 100 天前
        val sFresh = AgentMemory.score(AgentMemory.bigrams("橘猫"), AgentMemory.unigrams("橘猫"), now, fresh)
        val sStale = AgentMemory.score(AgentMemory.bigrams("橘猫"), AgentMemory.unigrams("橘猫"), now, stale)
        assertTrue("14 天半衰期：100 天的衰减应明显压低分数", sStale < sFresh * 0.25)
    }

    @Test
    fun importanceWeightsButSimilarityDominates() {
        val now = 10L * DAY
        val q = AgentMemory.bigrams("他喜欢什么动物")
        val onTopicLow = mem("用户最喜欢的动物是猫", importance = 0.2)
        val offTopicHigh = mem("用户住在杭州", importance = 1.0)
        assertTrue("不沾边的满分记忆也不该盖过沾边的低分记忆",
            AgentMemory.score(q, AgentMemory.unigrams("他喜欢什么动物"), now, offTopicHigh) <
            AgentMemory.score(q, AgentMemory.unigrams("他喜欢什么动物"), now, onTopicLow))
    }

    @Test
    fun topReturnsHitsAndBumpsUsage() {
        val now = 5L * DAY
        val lib = listOf(
            mem("用户养了一只叫年糕的橘猫", at = now),
            mem("用户在准备考研", at = now),
            mem("今天天气不错", at = now),   // 凑数：和查询无关
        )
        val qBi = AgentMemory.bigrams("年糕是不是生病了")
        val qUni = AgentMemory.unigrams("年糕是不是生病了")
        for (m in lib) println("SCORE " + m.content + " = " + AgentMemory.score(qBi, qUni, now, m))
        println("QBI=" + qBi + " QUNI=" + qUni)
        println("CBI=" + AgentMemory.bigrams("今天天气不错") + " CUNI=" + AgentMemory.unigrams("今天天气不错"))
        val (hits, bumped) = AgentMemory.top("年糕是不是生病了", lib, now)
        assertEquals("hits=" + hits.map { it.content }, 1, hits.size)
        assertEquals("用户养了一只叫年糕的橘猫", hits[0].content)
        assertEquals("命中的那条使用计数要 +1", 2, bumped.first { it.content == hits[0].content }.uses)
        // 没命中的原样保留
        assertEquals(lib.first { it.content == "用户在准备考研" }, bumped.first { it.content == "用户在准备考研" })
    }

    @Test
    fun topOnEmptyLibraryReturnsEmptyWithoutCrash() {
        val (hits, bumped) = AgentMemory.top("任何话", emptyList(), 0)
        assertTrue(hits.isEmpty()); assertTrue(bumped.isEmpty())
    }

    @Test
    fun unigramLayerRescuesMisalignedSingleChar() {
        // 真机实测踩到的核心场景：问「我的猫叫什么」vs 记忆「养了一只橘猫」——
        // bigram 零重叠（猫在词尾 vs 词首），单字层必须把这条捞回来
        val mem = mem("用户养了一只名叫年糕的橘猫，性格非常黏人。", importance = 0.7)
        val s = AgentMemory.score(
            AgentMemory.bigrams("我的猫叫什么名字"), AgentMemory.unigrams("我的猫叫什么名字"), 5L * DAY, mem)
        assertTrue("单字层命中：分数要过检索线（0.08），实际=" + s, s >= AgentMemory.MIN_SCORE)
    }

    @Test
    fun unigramLayerStillRejectsUnrelatedQuery() {
        // 「天气怎么样」和「养猫」单字层也不该凑出命中
        val mem = mem("用户养了一只名叫年糕的橘猫。")
        val s = AgentMemory.score(
            AgentMemory.bigrams("今天天气怎么样"), AgentMemory.unigrams("今天天气怎么样"), 0, mem)
        assertTrue("无关查询不得过线，实际=" + s, s < AgentMemory.MIN_SCORE)
    }

    // ---------------- contextBlock ----------------

    @Test
    fun contextBlockEmptyStaysEmptySoRequestBodyIsUntouched() {
        // 空 = 调用方不拼 suffix：请求体必须和没这功能时逐字一致（前缀缓存纪律）
        assertEquals("", AgentMemory.contextBlock(emptyList()))
    }

    @Test
    fun contextBlockFormatsCategoryLinesAndFlattensNewlines() {
        val s = AgentMemory.contextBlock(listOf(mem("养了\n两只猫", category = "FACT")))
        assertTrue(s.startsWith(AgentMemory.MEM_HEAD))
        assertTrue(s.contains("[FACT] 养了 两只猫"))
        assertTrue("一条记忆不许把块撑成多段", !s.contains("\n两"))
    }

    // ---------------- parseExtraction ----------------

    @Test
    fun parseExtractionToleratesChatterAroundJson() {
        val reply = "好的，以下是提取结果：\n" +
            """[{"content":"用户养了一只橘猫","category":"FACT","importance":0.8}]""" + "\n希望有帮助"
        val mems = AgentMemory.parseExtraction(reply)
        assertEquals(1, mems.size)
        assertEquals("用户养了一只橘猫", mems[0].content)
        assertEquals(0.8, mems[0].importance, 1e-9)
    }

    @Test
    fun parseExtractionUnknownCategoryFallsBackToFact() {
        val mems = AgentMemory.parseExtraction("""[{"content":"x","category":"秘密","importance":5}]""")
        assertEquals("FACT", mems[0].category)
        assertTrue("importance 越界要收回 0~1", mems[0].importance <= 1.0)
    }

    @Test
    fun parseExtractionGarbageReturnsEmptyNeverThrows() {
        assertTrue(AgentMemory.parseExtraction("模型没守格式，说了一大段话").isEmpty())
        assertTrue(AgentMemory.parseExtraction("[{坏了").isEmpty())
        assertTrue(AgentMemory.parseExtraction("").isEmpty())
    }

    // ---------------- merge ----------------

    @Test
    fun mergeDeduplicatesSameFactInsteadOfPilingUp() {
        val now = 10L * DAY
        val old = mem("用户养了一只叫年糕的橘猫", importance = 0.6, at = 0, lastUsed = 0)
        val cand = mem("用户养了一只叫年糕的橘猫，很粘人", importance = 0.8)
        val merged = AgentMemory.merge(listOf(old), listOf(cand), now)
        assertEquals("同一件事只留一条", 1, merged.size)
        assertEquals("重要度取大", 0.8, merged[0].importance, 1e-9)
    }

    @Test
    fun mergeCapDropsLowestValue() {
        val now = 10L * DAY
        val lib = (0 until AgentMemory.MAX).map { mem("事实条目编号${'$'}it 很长的一段内容撑一撑体积 $it", importance = 0.9, at = now, lastUsed = now) }
        val junk = mem("完全无关的凑数条目 xyzq", importance = 0.1, at = 0, lastUsed = 0)
        val merged = AgentMemory.merge(lib, listOf(junk), now)
        assertEquals("库容不涨破上限", AgentMemory.MAX, merged.size)
        assertTrue("丢的是最不值钱的，不是最新的", merged.none { it.content == junk.content })
    }

    // ---------------- 序列化 ----------------

    @Test
    fun jsonRoundTripPreservesFields() {
        val mems = listOf(mem("内容", "PREFERENCE", 0.7, at = 123L, uses = 4, lastUsed = 456L))
        val back = AgentMemory.parse(AgentMemory.toJson(mems))
        assertEquals(mems, back)
    }

    @Test
    fun parseBadJsonGivesEmptyList() {
        assertTrue(AgentMemory.parse(null).isEmpty())
        assertTrue(AgentMemory.parse("not json").isEmpty())
    }

    // ---------------- Extractor 请求体 ----------------

    @Test
    fun extractorBodyIsNonStreamSingleTurn() {
        val cfg = ApiConfig("https://api.example.com/v1", "sk-test", "test-model")
        val o = org.json.JSONObject(AgentMemory.Extractor(cfg).body("你好", "你好呀"))
        assertEquals(false, o.getBoolean("stream"))
        assertEquals(512, o.getInt("max_tokens"))
        val msgs = o.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(1).getString("content").contains("你好"))
    }
}
