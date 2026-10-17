package com.chun.lingzhu

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * 边界探针（v1.78，edge 轴产物）：极端输入 / 时钟偏移 / 并发 / 空 collective，全部只断言
 * 「不崩 + 语义可接受」，跑不快不是这里的关注点（那是 ParsePerfTest 的事）。
 */
class EdgeCasesTest {

    // ---- 1. 正则回溯炸弹：EN_ZH_RE 对「超长无汉字行」不许卡死 ----
    @Test fun parseLineNoReDoSOnLongNonHanLines() {
        val bombs = listOf(
            "a".repeat(50_000),
            "a".repeat(20_000) + " " + "n. ".repeat(5_000),          // 词性段重复但永远等不到汉字
            "word ".repeat(10_000) + "| 释义",
            "\t".repeat(10_000),
        )
        for (b in bombs) {
            val t0 = System.nanoTime()
            val c = Words.parseLine(b)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("回溯炸弹不该卡死（${ms}ms）", ms < 1_000)
            assertTrue("无释义的行正面应为原文截断语义", c == null || c.front.isNotEmpty())
        }
    }

    // ---- 2. 时钟偏移：历史条目时间在未来（手机改时间 / 对时漂移）不许出现「负 X 分钟前」 ----
    @Test fun historySummaryWithFutureTimestampsStaysSane() {
        val now = 10_000_000L
        val e = History.Entry(id = now + 3_600_000, kind = History.KIND_SOLVE, label = "做题", text = "答案 A")
        val s = ChatStore.historySummary(listOf(e), now)
        assertTrue("摘要照常生成", s.isNotEmpty())
        assertFalse("不许出现负数时长", s.contains("-"))
        assertEquals("刚刚", ChatStore.ago(now + 3_600_000, now))
    }

    // ---- 3. 极端大词库：单库 10 万行解析与摘要不崩、结果自洽 ----
    @Test fun hugeDeckParseAndSummaryStayConsistent() {
        val text = (1..100_000).joinToString("\n") { "huge$it v. 第$it 条" }
        val deck = Words.Deck("d_huge", "巨库", text)
        val cards = Words.parse(text)
        assertEquals(100_000, cards.size)
        val s = Words.summaries(listOf(deck), emptyMap())
        assertEquals(100_000, s[0].total)
        assertEquals(0, s[0].mastered)
    }

    // ---- 4. 解析缓存并发：多线程同抢同一份大文本，结果必须一致且不崩 ----
    @Test fun parseMemoizedIsThreadSafeUnderContention() {
        val text = (1..5_000).joinToString("\n") { "t$it n. 并发第$it" }
        val expected = Words.parse(text).size
        val threads = 4
        val latch = CountDownLatch(threads)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        for (t in 0 until threads) {
            Thread {
                try {
                    repeat(20) { round ->
                        val got = Words.parseMemoized(text + if (round % 2 == 0) "" else " ")
                        if (got.size != expected) errors.add("round$round got ${got.size}")
                    }
                } catch (e: Throwable) { errors.add(e.toString()) } finally { latch.countDown() }
            }.start()
        }
        latch.await()
        assertTrue("并发解析出错：$errors", errors.isEmpty())
    }

    // ---- 5. 空集合与空 id：摘要 / 展开态 / 折叠 / 勾选对空输入全部稳 ----
    @Test fun emptyAndWeirdIdsStaySafe() {
        assertTrue(Words.summaries(emptyList(), emptyMap()).isEmpty())
        assertNull(Translate.pickCached(emptyList(), probe = { null }))
        assertTrue(ChatStore.windowChunked(emptyList()).isEmpty())
        val ex = Words.Expanded()
        ex.toggled(""); ex.toggled("\u0000"); ex.retainOnly(emptySet())
        assertFalse(ex.isOpen(""))
        assertTrue(Words.toggleDeck(emptySet(), "", true).contains(""))
    }

    // ---- 6. 缓存边界：ttl=0 即刻过期；16 条满后整体清空不许丢当前条 ----
    @Test fun cachePutWithZeroTtlEvictsImmediately() {
        var l = ArrayList<Cache.Item>()
        l = ArrayList(Cache.put(l, "k", "v", now = 1_000, max = 10, ttl = 0))
        val (hit, _) = Cache.get(l, "k", now = 1_001, ttl = 0)
        assertNull(hit)
    }

    @Test fun parseMemoCacheEvictionKeepsFreshEntriesCorrect() {
        // 哨兵必须走 EN_ZH_RE 的真实口径：ASCII 开头 + 汉字释义（汉字开头的行本来就不拆背面，是设计）
        Words.parseMemoized("bingo v. 第一")
        for (i in 0 until 20) Words.parseMemoized("挤占$i v. x")   // 顶掉 16 条容量
        val got = Words.parseMemoized("bingo v. 第一")
        assertEquals("bingo", got[0].front)
        assertTrue(got[0].back.contains("第一"))
    }

    // ---- 7. 主题：超长 / 全角 / 垃圾十六进制输入不崩、返回 null ----
    @Test fun parseHexRejectsGarbageGracefully() {
        assertNull(Theme.parseHex("#" + "9".repeat(100)))
        assertNull(Theme.parseHex("＃zzzzzz"))
        assertNull(Theme.parseHex("\u0000"))
        assertEquals(0xABCDEF, Theme.parseHex("abcdef"))
    }

    // ---- 8. 追问滑窗：n=0 / chunk=0 的防御口径 ----
    @Test fun windowChunkedDegenerateParamsStaySafe() {
        val l = (1..5).map { ChatStore.user("m$it", it.toLong()) }
        assertTrue(ChatStore.windowChunked(l, 0).isEmpty())
        assertEquals(5, ChatStore.windowChunked(l, 10).size)
        assertEquals(5, ChatStore.windowChunked(l, 10, 0).size)
    }
}
