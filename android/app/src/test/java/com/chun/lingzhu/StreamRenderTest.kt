package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1 流式渲染节奏（[StreamRender]）：间隔跟上一次 render 的真实耗时走，
 * 夹在 MIN_MS~MAX_MS。背景：实测长答案单次 render 20ms+，旧的固定 16ms 排队让
 * 流式期间主线程被背靠背的重排占满 —— 间隔必须能把占空比压下来，又不能慢到肉眼可见地「一顿一顿」。
 */
class StreamRenderTest {
    @Test fun clampsToRange() {
        assertEquals(StreamRender.MIN_MS, StreamRender.intervalMs(0))          // 还没量过：按最便宜处理
        assertEquals(StreamRender.MIN_MS, StreamRender.intervalMs(-5))         // 异常值也不炸
        assertEquals(StreamRender.MIN_MS, StreamRender.intervalMs(5))          // 5×3=15 → 下限
        assertEquals(StreamRender.MAX_MS, StreamRender.intervalMs(500))        // 再贵也不超过上限
    }

    @Test fun scalesWithRealRenderCost() {
        // §11.26 实测长答案 Md.render 中位数 21~25ms → 间隔 63~75ms（约 13~16Hz），占空比 ≤ 1/3
        val mid = StreamRender.intervalMs(21)
        assertTrue("21ms 的 render 应排到 50~80ms，实际 $mid", mid in 50..80)
        val heavy = StreamRender.intervalMs(40)
        assertEquals("40ms 的 render 顶到上限", StreamRender.MAX_MS, heavy)
        // 短答案 render 便宜也至少留 MIN_MS，给系统动画 / 滚动让主线程
        assertEquals(StreamRender.MIN_MS, StreamRender.intervalMs(10))
    }

    @Test fun boundsAreSane() {
        assertTrue("下限要慢过一帧(16ms)，否则起不到让出主线程的作用", StreamRender.MIN_MS > 16)
        assertTrue("上限要快过 125ms(8Hz)，否则流式文字肉眼可见地跳", StreamRender.MAX_MS <= 125)
    }
}
