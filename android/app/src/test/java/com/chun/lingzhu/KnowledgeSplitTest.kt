package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** KnowledgeSplit：分段不丢尾巴 + 只留出处可验证的问答草稿（纯函数，边界行为） */
class KnowledgeSplitTest {

    // ---------------- chunks ----------------

    @Test fun blankTextHasNoChunks() {
        assertTrue(KnowledgeSplit.chunks("").isEmpty())
        assertTrue(KnowledgeSplit.chunks("   \n\t \n").isEmpty())
    }

    @Test fun shortTextIsSingleChunk() {
        assertEquals(listOf("你好世界"), KnowledgeSplit.chunks("你好世界"))
    }

    @Test fun exactBoundaryStaysOneChunk() {
        val s = "测".repeat(50)
        assertEquals(listOf(s), KnowledgeSplit.chunks(s, 50))
    }

    @Test fun overlongLineIsHardSplitWithoutLoss() {
        assertEquals(listOf("abcde", "fghij"), KnowledgeSplit.chunks("abcdefghij", 5))
    }

    @Test fun multiLineChunksRespectMaxAndPreserveAllContent() {
        val text = buildString { repeat(60) { append("第 $it 行：光合作用把光能变成化学能，需要水和二氧化碳。\n") } }
        val cs = KnowledgeSplit.chunks(text, 40)
        assertTrue("长文应被切成多段", cs.size > 1)
        assertTrue("每段都不能超过上限，也不能是空段", cs.all { it.isNotEmpty() && it.length <= 40 })
        // 去掉空白后必须逐字相等 —— 分段不允许静默丢内容
        assertEquals(text.filterNot { it.isWhitespace() }, cs.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test fun tailIsNeverDropped() {
        val text = "开头一段。\n" + "中间".repeat(200) + "\n结尾标记END"
        val cs = KnowledgeSplit.chunks(text, 30)
        assertTrue(cs.size > 1)
        assertTrue("结尾必须落在某一段里", cs.last().endsWith("结尾标记END"))
        assertEquals(text.filterNot { it.isWhitespace() }, cs.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test fun blankOnlyLinesDoNotProduceSegments() {
        assertEquals(listOf("abc"), KnowledgeSplit.chunks("\n\n\nabc\n\n\n"))
    }

    // ---------------- parse ----------------

    private fun item(deck: String?, front: String, back: String, excerpt: String): String =
        JSONObject().apply {
            if (deck != null) put("deck", deck)
            put("front", front)
            put("back", back)
            put("excerpt", excerpt)
        }.toString()

    private fun array(vararg items: String): String = "[" + items.joinToString(",") + "]"

    private val src = "光合作用是植物把光能变成化学能的过程。\n它发生在叶绿体里，需要水和二氧化碳。"

    @Test fun parsesFaithfulCard() {
        val raw = array(item("生物", "光合作用是什么？", "植物把光能变成化学能。", "光合作用是植物把光能变成化学能的过程。"))
        val out = KnowledgeSplit.parse(raw, src)
        assertEquals(1, out.size)
        assertEquals(KnowledgeAiImport.Draft("生物", "光合作用是什么？", "植物把光能变成化学能。", "光合作用是植物把光能变成化学能的过程。"), out[0])
    }

    @Test fun parsesInsideMarkdownFence() {
        val raw = "```json\n" + array(item("生物", "光合作用是什么？", "见资料。", "它发生在叶绿体里")) + "\n```"
        assertEquals(1, KnowledgeSplit.parse(raw, src).size)
    }

    @Test fun parsesWithProseAroundArray() {
        val raw = "好的，结果如下：\n" + array(item("生物", "光合作用是什么？", "见资料。", "它发生在叶绿体里")) + "\n以上。"
        assertEquals(1, KnowledgeSplit.parse(raw, src).size)
    }

    @Test fun bracketsInsideStringsDoNotBreakParsing() {
        val raw = array(item("数学", "数组 a[0] 表示什么？", "第一个元素。", "它发生在叶绿体里"))
        val out = KnowledgeSplit.parse(raw, src)
        assertEquals(1, out.size)
        assertEquals("数组 a[0] 表示什么？", out[0].front)
    }

    @Test fun excerptMissingFromSourceRejectsWholeBatch() {
        val raw = array(item("生物", "光合作用是什么？", "答案。", "资料里根本没有这句话"))
        try { KnowledgeSplit.parse(raw, src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun excerptWithWhitespaceDifferenceReturnsLiteralSourceSubstring() {
        val source = "光合作用\n需要水和二氧化碳"
        val raw = array(item("生物", "光合作用需要什么？", "水和二氧化碳。", "光合作用 需要水和二氧化碳"))
        val out = KnowledgeSplit.parse(raw, source)
        assertEquals(1, out.size)
        assertEquals(source, out[0].excerpt)   // 交付的是原文那一整段字，不是模型改写过的
    }

    @Test fun tooLongFrontRejectsWholeBatch() {
        val ok = array(item("生物", "问".repeat(200), "答。", "它发生在叶绿体里"))
        assertEquals(1, KnowledgeSplit.parse(ok, src).size)
        val tooLong = array(item("生物", "问".repeat(201), "答。", "它发生在叶绿体里"))
        try { KnowledgeSplit.parse(tooLong, src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun tooLongBackRejectsWholeBatch() {
        val ok = array(item("生物", "问？", "答".repeat(500), "它发生在叶绿体里"))
        assertEquals(1, KnowledgeSplit.parse(ok, src).size)
        val tooLong = array(item("生物", "问？", "答".repeat(501), "它发生在叶绿体里"))
        try { KnowledgeSplit.parse(tooLong, src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun excerptAtMaxIsAcceptedButOverlongRejectsWholeBatch() {
        val atMax = "甲".repeat(KnowledgeAiImport.EXCERPT_MAX)
        assertEquals(1, KnowledgeSplit.parse(array(item("生物", "问？", "答。", atMax)), atMax).size)
        val overlong = "甲".repeat(KnowledgeAiImport.EXCERPT_MAX + 1)
        try { KnowledgeSplit.parse(array(item("生物", "问？", "答。", overlong)), overlong); fail() }
        catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun blankFrontOrBackRejectsWholeBatch() {
        try {
            KnowledgeSplit.parse(array(item("生物", "", "答。", "它发生在叶绿体里")), src); fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
        try {
            KnowledgeSplit.parse(array(item("生物", "问？", "   ", "它发生在叶绿体里")), src); fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun missingDeckFallsBackToDefault() {
        val raw = array(item(null, "问？", "答。", "它发生在叶绿体里"))
        val out = KnowledgeSplit.parse(raw, src)
        assertEquals(1, out.size)
        assertEquals(KnowledgeAiImport.DEFAULT_DECK, out[0].deck)
    }

    @Test fun mixedValidInvalidRejectsWholeBatch() {
        val raw = array(
            item("生物", "问一？", "答一。", "它发生在叶绿体里"),
            item("生物", "问二？", "答二。", "这句资料里没有"),
            item("生物", "问三？", "答三。", "光合作用是植物把光能变成化学能的过程。"),
        )
        // 好卡不能掩盖坏卡：混了坏卡就整段拒绝，不做「只留好的」部分导入
        try { KnowledgeSplit.parse(raw, src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun emptyArrayIsEmptyList() {
        assertTrue(KnowledgeSplit.parse("[]", src).isEmpty())
        assertTrue(KnowledgeSplit.parse("```json\n[]\n```", src).isEmpty())
    }

    @Test fun blankOutputIsRejected() {
        try { KnowledgeSplit.parse("   \n", src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun outputWithoutArrayIsRejected() {
        try { KnowledgeSplit.parse("抱歉，我不能这样做", src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun brokenJsonArrayIsRejected() {
        try { KnowledgeSplit.parse("[{front: 坏}]", src); fail() } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }
}

/** KnowledgeAiImport.generate：全文分段都要处理、失败不返回半成品、取消即断（跑本地假接口） */
class KnowledgeAiImportTest {
    private lateinit var server: MiniHttp
    @Volatile private var handler: (Int) -> Pair<Int, String> = { 200 to "" }
    private val hits = AtomicInteger()
    private val bodies: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())

    @Before fun up() {
        ApiClient.RETRY_WAIT_MS = 100
        server = MiniHttp { _, body -> bodies.add(body); handler(hits.incrementAndGet()) }
    }

    @After fun down() { server.stop(); ApiClient.RETRY_WAIT_MS = 1500 }

    private fun cfg() = ApiConfig("http://127.0.0.1:${server.port}/v1", "sk-test", "m")

    private fun sse(content: String, finish: String? = null): String {
        val choice = JSONObject().put("delta", JSONObject().put("content", content))
        if (finish != null) choice.put("finish_reason", finish)
        return "data: " + JSONObject().put("choices", JSONArray().put(choice)) + "\n\n" + "data: [DONE]\n\n"
    }

    private fun card(front: String, excerpt: String) =
        JSONObject().put("deck", "测试").put("front", front).put("back", "答案").put("excerpt", excerpt).toString()

    private fun arrayOfCard(front: String, excerpt: String) = JSONArray().put(JSONObject(card(front, excerpt))).toString()

    /** 两段以上、总长超过 6000 的样例文本 */
    private fun longText(): Pair<String, String> {
        val tail = "乙".repeat(3500)
        return ("问题一" + "甲".repeat(3500) + "\n" + "问题二" + tail) to tail
    }

    @Test fun everyChunkIsProcessedAndTailReachesTheModel() {
        val (text, tail) = longText()
        handler = { 200 to sse(arrayOfCard("这段讲了什么？", "问题")) }
        val progress = ArrayList<Pair<Int, Int>>()
        val drafts = KnowledgeAiImport.generate(text, "sample.txt", cfg(), Cancel()) { c, t -> progress.add(c to t) }

        assertEquals("两段资料应各出一张卡", 2, drafts.size)
        assertEquals(2, hits.get())
        assertEquals(0 to 2, progress.first())
        assertEquals(2 to 2, progress.last())
        assertTrue("最后一段的结尾必须真的送出去（不静默丢尾巴）", bodies.last().contains(tail))
        assertTrue(drafts.all { it.excerpt == "问题" })
    }

    @Test fun singleChunkShortTextWorks() {
        handler = { 200 to sse(arrayOfCard("光合作用是什么？", "光合作用")) }
        val out = KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), Cancel()) { _, _ -> }
        assertEquals(1, out.size)
        assertEquals("光合作用", out[0].excerpt)
        assertEquals(1, hits.get())
    }

    @Test fun truncatedOutputIsRejected() {
        handler = { 200 to sse(arrayOfCard("光合作用是什么？", "光合作用"), finish = "length") }
        try {
            KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun emptyOutputIsRejected() {
        handler = { 200 to sse("") }
        try {
            KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun malformedOutputIsRejected() {
        handler = { 200 to sse("这不是 JSON") }
        try {
            KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun laterChunkFailureReturnsNoPartialImport() {
        val (text, _) = longText()
        handler = { n -> if (n == 1) 200 to sse(arrayOfCard("这段讲了什么？", "问题")) else 200 to sse("坏掉了") }
        try {
            KnowledgeAiImport.generate(text, "sample.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
        assertEquals("两段都要尝试，不能拿第一段的结果收工", 2, hits.get())
    }

    @Test fun noCardsAnywhereIsRejected() {
        handler = { 200 to sse("[]") }
        try {
            KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun blankTextIsRejectedBeforeAnyRequest() {
        try {
            KnowledgeAiImport.generate("   \n  ", "s.txt", cfg(), Cancel()) { _, _ -> }
            fail()
        } catch (e: ApiException) { assertTrue(e.message!!.isNotBlank()) }
        assertEquals(0, hits.get())
    }

    @Test fun cancelAbortsQuicklyAndStopsRetrying() {
        handler = { Thread.sleep(20_000); 200 to sse(arrayOfCard("问？", "光合作用")) }
        val c = Cancel()
        var err: Throwable? = null
        val t = thread {
            try { KnowledgeAiImport.generate("光合作用是植物把光能变成化学能。", "s.txt", cfg(), c) { _, _ -> } }
            catch (e: Throwable) { err = e }
        }
        Thread.sleep(400)
        val t0 = System.currentTimeMillis()
        c.abort()
        t.join(5_000)
        assertTrue("abort 后 2 秒内应返回", !t.isAlive && System.currentTimeMillis() - t0 < 2_000)
        assertTrue(err is ApiException)
        assertEquals("取消后不再重试", 1, hits.get())
    }
}
