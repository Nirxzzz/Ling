package com.chun.lingzhu

/**
 * v2.1 流式渲染节奏（纯函数，可单测）。
 *
 * 为什么要有它：AnswerPanel.append 的旧节流是固定 16ms —— 流式块到达就排队，一帧最多重排一次。
 * 但「重排一次」本身对长答案要 20ms+（§11.26 实测 Md.render 中位数 21~25ms，加上拆分与整段 re-layout 更长），
 * 16ms 的排队间隔根本兜不住：整个流式期间主线程被背靠背的 render 占满，帧帧超预算 —— 这就是「流式回答时卡」。
 *
 * 做法：下一次 render 的排队间隔跟着**上一次 render 的真实耗时**走（间隔 = 3×耗时，夹在 [MIN_MS]~[MAX_MS]）：
 *  - 短答案 render 才几 ms → 40ms 一排（25Hz），照样跟手；
 *  - 长答案 render 20~40ms → 60~120ms 一排（8~16Hz），主线程占空比压到 1/3 以下；
 *  - 流式本来就是文字逐步长出来，10Hz 级别的刷新人眼完全跟得上，收尾那次 render 在 done() 里立即跑，不受这里影响。
 */
object StreamRender {
    /** 最短间隔：render 再便宜也不贴着 16ms 排，给系统动画 / 滚动留主线程 */
    const val MIN_MS = 40L
    /** 最长间隔：再贵也保证流式文字肉眼可见地在长（约 8Hz） */
    const val MAX_MS = 120L

    /** 上一次 render 耗时(ms) → 下一次流式 render 的排队间隔(ms)。非正数按「很便宜」处理，取下限 */
    fun intervalMs(lastRenderMs: Long): Long =
        (lastRenderMs.coerceAtLeast(0L) * 3L).coerceIn(MIN_MS, MAX_MS)
}
