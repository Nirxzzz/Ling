package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.65 用量核算：SSE usage 解析 + 按天统计 + 估算/格式化 */
class UsageTest {

    // ---- Sse：接口在最后的块上报 usage（OpenAI 兼容流式两种形态都要接住）----

    @Test fun usageInChunkWithEmptyChoices() {
        val d = Sse.parseLine("""data: {"choices":[],"usage":{"prompt_tokens":4200,"completion_tokens":318}}""")
        assertEquals(4200 to 318, d?.usage)
    }

    @Test fun usageInChunkWithChoices() {
        val d = Sse.parseLine("""data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":7}}""")
        assertEquals("好", d?.content)
        assertEquals(100 to 7, d?.usage)
    }

    @Test fun noUsageGivesNull() {
        assertNull(Sse.parseLine("""data: {"choices":[{"delta":{"content":"字"}}]}""")?.usage)
        assertNull(Sse.parseLine("""data: {"choices":[{"delta":{}}]}""")?.usage)
    }

    @Test fun badUsageNumbersIgnored() {
        assertNull(Sse.parseLine("""data: {"choices":[],"usage":{"prompt_tokens":-1,"completion_tokens":5}}""")?.usage)
    }

    // ---- Usage：按天累计 / 裁剪 / 汇总 / 格式化 ----

    @Test fun addAccumulatesPerDay() {
        val m = Usage.parse(null)
        Usage.add(m, "20261005", pin = 100, pout = 50, chars = 80)
        Usage.add(m, "20261005", pin = 30, pout = 20, chars = 40)
        Usage.add(m, "20261004", pin = 7, pout = 3, chars = 5)
        assertEquals(2, Usage.dayTotal(m, "20261005").req)
        assertEquals(130, Usage.dayTotal(m, "20261005").pin)
        assertEquals(Usage.Day(1, 7, 3, 5), Usage.dayTotal(m, "20261004"))
        val t = Usage.total(m)
        assertEquals(3, t.req); assertEquals(137, t.pin); assertEquals(73, t.pout); assertEquals(125, t.ch)
    }

    @Test fun keepAtMost30DaysAndDropOldest() {
        val m = Usage.parse(null)
        for (i in 1..35) Usage.add(m, String.format("2026%04d", i), 1, 1, 1)
        assertEquals(Usage.KEEP_DAYS, m.size)
        assertTrue(!m.containsKey("20260001"))
        assertTrue(m.containsKey("20260035"))
    }

    @Test fun parseToleratesGarbage() {
        assertTrue(Usage.parse(null).isEmpty())
        assertTrue(Usage.parse("").isEmpty())
        assertTrue(Usage.parse("垃圾").isEmpty())
    }

    @Test fun roundtripJson() {
        val m = Usage.parse(null)
        Usage.add(m, "20261005", 10, 5, 8)
        val back = Usage.parse(Usage.toJson(m))
        assertEquals(Usage.Day(1, 10, 5, 8), back["20261005"])
    }

    @Test fun estimateChineseChars() {
        assertEquals(0, Usage.estimate(0))
        assertEquals(31, Usage.estimate(50))   // 50 × 0.62 = 31
        assertTrue(Usage.estimate(1000) in 580..660)
    }

    @Test fun fmtHumanReadable() {
        assertEquals("0", Usage.fmt(0))
        assertEquals("999", Usage.fmt(999))
        assertEquals("1.2k", Usage.fmt(1200))
        assertEquals("38.5k", Usage.fmt(38500))
        assertEquals("30.0万", Usage.fmt(300_000))
    }

    @Test fun lineContainsAllParts() {
        val s = Usage.line("今天", Usage.Day(2, 4200, 318, 500))
        assertTrue(s.contains("今天") && s.contains("2 次") && s.contains("4.2k") && s.contains("318") && s.contains("500"))
    }
}
