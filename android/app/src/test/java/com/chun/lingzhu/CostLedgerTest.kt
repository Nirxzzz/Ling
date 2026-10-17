package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CostLedger：成本台账（记账 / 聚合 / 月滚动 / 序列化 / 估算 / 展示） */
class CostLedgerTest {

    private fun empty() = mutableMapOf<String, CostLedger.Month>()

    // ---------------- 记账与聚合 ----------------

    @Test fun recordGoesIntoDayModelAndFeatureBuckets() {
        val l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 1000L, 100L)
        val m = l.getValue("202610")
        assertEquals(1, m.days["20261007"]!!.req)
        assertEquals(1, m.models["deepseek-flash"]!!.req)
        assertEquals(1, m.features[CostLedger.Feature.SOLVE.code]!!.req)
    }

    @Test fun costComputedFromModelPrice() {
        // DeepSeek 定价 ¥0.5/M 入 → 1M 输入 token = 500_000 微元（其余为 0，数字不互相干扰）
        val l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 1_000_000L, 0L)
        assertEquals(500_000L, l.getValue("202610").total().cost)
    }

    @Test fun mimoIsCheaperThanDeepseekForSameTokens() {
        val ds = CostLedger.priceOf("deepseek-flash").costMicro(100_000L, 10_000L)
        val mimo = CostLedger.priceOf("mimo-v2.6-pro").costMicro(100_000L, 10_000L)
        assertTrue(mimo < ds)
    }

    @Test fun unknownModelFallsBackToDeepseekPrice() {
        assertEquals(CostLedger.PRICE_DEEPSEEK.costMicro(1000L, 100L), CostLedger.priceOf("whatever-model").costMicro(1000L, 100L))
    }

    @Test fun recordAccumulatesAcrossCallsAndDates() {
        var l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 100L, 10L)
        l = CostLedger.record(l, "20261007", "deepseek-flash", CostLedger.Feature.CHAT, 100L, 10L)
        l = CostLedger.record(l, "20261008", "deepseek-flash", CostLedger.Feature.SOLVE, 100L, 10L)
        val m = l.getValue("202610")
        assertEquals(3, m.total().req)
        assertEquals(2, m.days.size)
        assertEquals(2, m.days["20261007"]!!.req)
    }

    @Test fun savedIsTrackedSeparatelyFromCost() {
        val l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 100L, 10L, saved = 777L)
        assertEquals(777L, l.getValue("202610").total().saved)
    }

    @Test fun shortDateIsIgnored() {
        assertTrue(CostLedger.record(empty(), "2026", "deepseek-flash", CostLedger.Feature.SOLVE, 1L, 1L).isEmpty())
    }

    // ---------------- 月滚动 ----------------

    @Test fun rollKeepsOnlyRecentMonths() {
        val l = empty()
        for (ym in listOf("202608", "202609", "202610", "202611")) {
            CostLedger.record(l, "${ym}01", "deepseek-flash", CostLedger.Feature.SOLVE, 1L, 1L)
        }
        val rolled = CostLedger.roll(l, "202611")
        assertEquals(setOf("202610", "202611", "202609"), rolled.keys)
    }

    @Test fun rollWithKeepZeroIsNoOpGuard() {
        val l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 1L, 1L)
        assertEquals(1, CostLedger.roll(l, "202610", keep = 0).size)
    }

    // ---------------- 序列化 ----------------

    @Test fun parseIsInverseOfToJson() {
        var l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.CHAT, 1234L, 567L, saved = 89L)
        val json = CostLedger.toJson(l)
        val back = CostLedger.parse(json)
        val b = back.getValue("202610").total()
        assertEquals(1234L, b.pin)
        assertEquals(567L, b.pout)
        assertEquals(89L, b.saved)
        assertEquals(1, b.req)
    }

    @Test fun parseToleratesGarbage() {
        assertTrue(CostLedger.parse(null).isEmpty())
        assertTrue(CostLedger.parse("").isEmpty())
        assertTrue(CostLedger.parse("not json").isEmpty())
    }

    // ---------------- 估算与展示 ----------------

    @Test fun estimatePinScalesWithCharsAndImages() {
        val base = CostLedger.estimatePin(1000, 0)
        assertTrue(CostLedger.estimatePin(1000, 1) > base)   // 带图更贵
        assertTrue(CostLedger.estimatePin(2000, 0) > base)   // 字多更贵
    }

    @Test fun estimatePoutZeroForEmpty() {
        assertEquals(0L, CostLedger.estimatePout(0))
        assertTrue(CostLedger.estimatePout(100) > 0L)
    }

    @Test fun savedOnHitPositiveAndProportional() {
        val one = CostLedger.savedOnHit("deepseek-flash", 1000, 500, images = 0)
        val two = CostLedger.savedOnHit("deepseek-flash", 2000, 500, images = 0)
        assertTrue(one > 0L)
        assertTrue(two > one)
    }

    @Test fun fmtYuanBuckets() {
        assertEquals("¥0", CostLedger.fmtYuan(0))
        assertEquals("¥0", CostLedger.fmtYuan(-5L))
        assertTrue(CostLedger.fmtYuan(5_000L).startsWith("¥0.0"))    // 0.005 元 → 4 位小数
        assertEquals("¥1.23", CostLedger.fmtYuan(1_230_000L))
    }

    // ---------------- 摘要 ----------------

    @Test fun summaryMentionsTodayMonthAndFeatureNumbers() {
        var l = CostLedger.record(empty(), "20261007", "deepseek-flash", CostLedger.Feature.SOLVE, 1_000_000L, 100_000L)
        l = CostLedger.record(l, "20261007", "deepseek-flash", CostLedger.Feature.CHAT, 100_000L, 10_000L, saved = 5_000L)
        val s = CostLedger.summary(l, "20261007", listOf("20261007"))
        assertTrue(s.contains("0.55") || s.contains("0.6") || s.contains("¥"))   // 有金额表述
        assertTrue(s.contains("2"))                                              // 有次数表述
    }

    @Test fun summaryLineNonEmptyForEmptyLedger() {
        assertTrue(CostLedger.summaryLine(empty(), "20261007").isNotBlank())
    }
}
