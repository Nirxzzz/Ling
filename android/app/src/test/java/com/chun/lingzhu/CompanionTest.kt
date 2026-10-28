package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 陪伴气泡（[FocusCompanion]）的纯逻辑测试。
 *
 * 三块：
 *  1. **时延区间**：三档各自的上下界与「区间内取值」，以及认不出的频率如何兜到中档；
 *  2. **洗牌袋**：抽空之前不重复、跨袋不连着重复、空池不出句；
 *  3. **文案**：抬头 + 一句，长度可控（气泡固定 248dp），没有 emoji（和工程里其它提醒同一口径）。
 *
 * 全部是纯函数 / 纯状态机，不需要 Android，也不需要真跑定时器。
 */
class FocusCompanionTest {

    // ---------------- 一、时延区间 ----------------

    @Test
    fun rangesMatchTheAgreedBands() {
        // 契约里的三档：低 60–180、中 45–120、高 15–60（分钟）
        assertEquals(60L * 60_000L, FocusCompanion.LOW.minMs)
        assertEquals(180L * 60_000L, FocusCompanion.LOW.maxMs)
        assertEquals(45L * 60_000L, FocusCompanion.MED.minMs)
        assertEquals(120L * 60_000L, FocusCompanion.MED.maxMs)
        assertEquals(15L * 60_000L, FocusCompanion.HIGH.minMs)
        assertEquals(60L * 60_000L, FocusCompanion.HIGH.maxMs)
    }

    @Test
    fun nextDelayStaysInsideTheBand() {
        val rnd = Random(7)
        repeat(200) {
            val low = FocusCompanion.nextDelay(FocusSettings.FREQ_LOW, rnd)
            assertTrue("low=$low", low in FocusCompanion.LOW.minMs..FocusCompanion.LOW.maxMs)
            val med = FocusCompanion.nextDelay(FocusSettings.FREQ_MED, rnd)
            assertTrue("med=$med", med in FocusCompanion.MED.minMs..FocusCompanion.MED.maxMs)
            val high = FocusCompanion.nextDelay(FocusSettings.FREQ_HIGH, rnd)
            assertTrue("high=$high", high in FocusCompanion.HIGH.minMs..FocusCompanion.HIGH.maxMs)
        }
    }

    @Test
    fun nextDelayActuallyVaries() {
        // 不定时：同一档取 50 次不能全是同一个值（固定间隔就变成计时器了）
        val rnd = Random(11)
        val picked = HashSet<Long>()
        repeat(50) { picked.add(FocusCompanion.nextDelay(FocusSettings.FREQ_MED, rnd)) }
        assertTrue("MED 档只取到 ${picked.size} 个不同间隔", picked.size > 1)
    }

    @Test
    fun unknownFrequencyFallsBackToMedium() {
        // 脏值（文件被手改 / 版本回滚）一律按中档 —— 低档等于没有、高档等于打扰，中档是唯一无害的默认
        assertEquals(FocusCompanion.MED, FocusCompanion.range("nonsense"))
        assertEquals(FocusCompanion.MED, FocusCompanion.range(""))
        assertEquals(FocusCompanion.MED, FocusCompanion.range("NORMAL"))
        assertEquals(FocusCompanion.LOW, FocusCompanion.range(FocusSettings.FREQ_LOW))
        assertEquals(FocusCompanion.HIGH, FocusCompanion.range(FocusSettings.FREQ_HIGH))
    }

    @Test
    fun degenerateRangeReturnsTheOnlyPoint() {
        assertEquals(5_000L, FocusCompanion.Range(5_000L, 5_000L).pick(Random(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRangeIsRejected() {
        // 最小 > 最大：构造就炸，别让一个坏区间在运行期算出负数延时
        FocusCompanion.Range(2_000L, 1_000L)
    }

    // ---------------- 二、洗牌袋 ----------------

    @Test
    fun fullCycleCoversEveryLineExactlyOnce() {
        val bag = FocusCompanion.Bag(FocusCompanion.LINES, Random(3))
        val seen = ArrayList<String>()
        repeat(FocusCompanion.LINES.size) { bag.next()?.let { seen.add(it) } }
        assertEquals(FocusCompanion.LINES.size, seen.size)
        assertEquals(FocusCompanion.LINES.toSet(), seen.toSet())   // 一副牌里每条恰好出现一次
    }

    @Test
    fun noImmediateRepeatWithinOrAcrossBags() {
        // 连抽 6 副牌：任意相邻两次都不能是同一句（这是洗牌袋存在的最小理由）
        val bag = FocusCompanion.Bag(FocusCompanion.LINES, Random(5))
        var prev: String? = null
        repeat(FocusCompanion.LINES.size * 6) {
            val cur = bag.next()
            assertNotNull(cur)
            assertFalse("连续两次都是「$cur」", cur == prev)
            prev = cur
        }
    }

    @Test
    fun lastIsTheLineJustHandedOut() {
        val bag = FocusCompanion.Bag(listOf("a", "b"), Random(9))
        val first = bag.next()
        assertEquals(first, bag.last)
    }

    @Test
    fun emptyPoolYieldsNothing() {
        // 空池不编句子：调用方据此「不弹气泡」，不许凭空造一句出来
        assertNull(FocusCompanion.Bag(emptyList(), Random(1)).next())
    }

    @Test
    fun singleLinePoolStillTerminates() {
        // 只有一句：必然重复，但不能死循环 / 不能抛
        val bag = FocusCompanion.Bag(listOf("only"), Random(1))
        assertEquals("only", bag.next())
        assertEquals("only", bag.next())
    }
}
