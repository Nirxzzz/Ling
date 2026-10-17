package com.chun.lingzhu

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词库解析性能基线（v1.78 加，perf 轴产物；宽松上限当回归哨兵，数字看 stdout）。
 *
 * 场景 = 5 级内置词库全导入（合计约 9331 词，每级文本 ~130KB）。测三段：
 *  1. 冷解析：parseMemoized 首见每级全文 —— 对应设置页首次渲染 / 首次弹词挑卡
 *  2. 热命中：同文本再 parse —— 对应缓存生效后的日常路径，应当 <50ms 量级
 *  3. 摘要渲染：summaries() 走缓存 —— 对应 fillDecks 每次重建的固定开销
 * 断言只设量级上限（机器快慢都该过），真正的回归看两次运行 stdout 的比值。
 */
class ParsePerfTest {
    private fun bank(seed: String, lines: Int): String =
        (1..lines).joinToString("\n") { "${seed}word$it n. 释义第${it}条：例句 number $it" }

    private val banks = listOf(
        "junior" to 1866, "gaokao" to 1935, "cet4" to 1971, "cet6" to 1873, "ielts" to 1686,
    ).map { (seed, n) -> Words.Deck("wb_$seed", seed, bank(seed, n)) }

    @Test fun parseColdWarmAndSummaryBaseline() {
        // 预热 JIT，避免首测把解释执行的噪声算进来
        Words.parse("warm up v. 热身")
        var cold = 0L
        for (d in banks) {
            val t0 = System.nanoTime()
            val cards = Words.parseMemoized(d.text)
            cold += System.nanoTime() - t0
            assertTrue("每级都该解析出足量卡片", cards.size > d.text.lines().size / 2)
        }
        var warm = 0L
        for (d in banks) {
            val t0 = System.nanoTime()
            Words.parseMemoized(d.text)
            warm += System.nanoTime() - t0
        }
        val st = emptyMap<String, Words.Stat>()
        val t0 = System.nanoTime()
        val s = Words.summaries(banks, st)
        val summaryMs = (System.nanoTime() - t0) / 1_000_000.0
        val coldMs = cold / 1_000_000.0
        val warmMs = warm / 1_000_000.0
        println("[perf] 5级词库(${banks.sumOf { it.text.lines().size }}行) 冷解析=${coldMs}ms 热命中=${warmMs}ms 摘要=${summaryMs}ms 条数=${s.sumOf { it.total }}")
        assertTrue("热命中应当是毫秒级（实测 ${warmMs}ms）", warmMs < 50.0)
        // v1.84：这台机器常驻一个 Android 模拟器（qemu 常年吃 130%+ CPU），摘要那条路径
        // 实测会在 10ms（空载）~130ms（模拟器忙）之间抖 —— 原阈值 50ms 在负载下必挂，
        // 而它挡的其实是「数量级退化」（毫秒级掉到秒级）。放到 250ms，保留这个意图。
        assertTrue("摘要（含缓存）应当是毫秒级（实测 ${summaryMs}ms）", summaryMs < 250.0)
        assertTrue("冷解析 9331 行不该超过 3 秒（实测 ${coldMs}ms）", coldMs < 3000.0)
    }
}
