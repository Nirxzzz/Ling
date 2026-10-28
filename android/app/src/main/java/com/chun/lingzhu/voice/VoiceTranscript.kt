package com.chun.lingzhu.voice

import java.util.TreeMap
import java.util.TreeSet

/**
 * 一段识别结果。
 *
 * **按 [startMs] 替换，不要往后追加**：同一句话的中间结果会反复刷新（豆包的 partial、
 * 阿里的 `sentence_end=false`），每次都是「这一句到目前为止的全文」，不是增量。
 * 界面用 `startMs` 当槽位 id —— 槽位已有就替换文本，没有就新开一段。
 *
 * [isFinal] 为 true 表示**服务商**说这句定稿了（豆包 `definite`、阿里 `sentence_end`）。
 * 客户端不会替服务商定稿：会话正常结束时还没定稿的句子就保持 false 交给界面。
 *
 * 时间戳单位毫秒，相对本次会话开头。个别分句服务商不给开始时间时退回字级时间戳
 * （协议文档允许），这时 [startMs] 是推导值，只用来定位槽位，不是精确测量值。
 */
data class VoiceTranscript(
    val text: String,
    val isFinal: Boolean,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 两家服务商的句子级 reducer：**按稳定 start upsert**，同槽位覆盖，不拼接。
 *
 * 规则与依据：
 *  - 同一 startMs 的新结果覆盖旧结果（服务商发的是整句重发，不是增量）。
 *  - 已定稿的句子不接受中间结果（迟到/乱序的包不许把定稿改回去）。**定稿之间的更新不限次数**：
 *    协议没有任何「每句只改写一次」的约定，限制它只会丢掉真实的最终结果。
 *  - 空文本不通知（心跳包、纯标点/空白包很常见）。
 *  - **不做时间窗近似合并**：只有两种情况会并入别的槽位——服务商没给 `start_time`、开始时间由
 *    字级时间戳推导出来时（见 [resolveDerived]），或同一次重发被服务商改了起点、但结束时间与真实
 *    区间都对得上（见 [locateSameSentence]）。两个真实的相邻短句起点可能只差几十毫秒，
 *    按距离合并会把它们并成一句、丢掉其中一句。
 *  - 服务商连字级时间都不给时，交给调用方挂到当前未定稿的那一句（[lastUnfinalized]）或游标
 *    （[liveStart]）上，绝不新开一段。
 *
 * **有界**：豆包 `result_type=full` 每包重发**全部**历史分句，一场长会下来重发几千句。
 *  - [finalFloor] 水位：已定稿分句里最大的开始时间。水位以下、槽位又已被淘汰的句子
 *    当历史重发丢掉（不复活、不刷新界面）。
 *  - 槽位最多 [MAX_SLOTS] 条，超了淘汰开始时间最早的。
 */
internal class SentenceSlots {
    private class Slot(var value: VoiceTranscript)

    /** 按开始时间排序：找时间重叠的那一句是区间判断，不是全表猜距离。 */
    private val slots = TreeMap<Long, Slot>()

    /** 还没定稿的槽位（[lastUnfinalized] 要 O(log n) 拿到最靠后那句）。 */
    private val pending = TreeSet<Long>()

    private var cursor = 0L                    // 见到的最晚结束时间
    private var finalFloor = Long.MIN_VALUE    // 已定稿分句里最大的开始时间（水位）

    /** 下一句该从哪儿开始（= 目前见到的最晚结束时间）。 */
    fun liveStart(): Long = cursor

    /**
     * 服务商没给分句开始时间时，客户端退回 `words[0].start_time`（协议文档允许）。
     * 这个推导值可能比真正的分句起点晚几十毫秒（实测有一次差 40ms），直接当新槽位会在屏幕上
     * 多出半句重复文本。
     *
     * 判定只用**真实时间区间重叠**，且只与**未定稿**的句子比：
     *  - 恰好一句未定稿句的 `[start,end]` 与推导区间重叠 → 就是那句，沿用它的槽位；
     *  - 重叠到零句或多句（说不清是哪句）→ 当新句：宁多一条，也不覆盖别的句子；
     *  - 已定稿的句子一律不参与匹配 —— 定稿内容不允许被推导时间改写。
     *
     * 服务商**直接给了** `start_time` 的一律不走这里（直接值就是身份，不做近似）。
     */
    fun resolveDerived(derivedStart: Long, endMs: Long?): Long {
        val derivedEnd = maxOf(derivedStart, endMs ?: derivedStart)
        var hit: Long? = null
        for (start in pending) {
            val v = slots[start]?.value ?: continue
            if (v.startMs > derivedEnd) break                 // 后面只会更靠后，不可能再重叠
            if (v.endMs < derivedStart) continue
            if (hit != null) return derivedStart              // 重叠到多句 → 说不清，当新句
            hit = start
        }
        return hit ?: derivedStart
    }

    /** 收一条更新；不需要上屏（重复/空/历史重发/被定稿压住）时返回 null。 */
    fun accept(text: String, isFinal: Boolean, startMs: Long, endMs: Long?): VoiceTranscript? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val prev = slots[startMs]
        if (prev != null) {
            val old = prev.value
            if (old.isFinal && !isFinal) return null
            // 结束时间只增不减：同槽位的后续结果带的是这句话到目前为止的全文。
            val end = maxOf(old.endMs, startMs, endMs ?: old.endMs)
            if (old.text == t && old.isFinal == isFinal && old.endMs == end) return null
            return store(VoiceTranscript(t, isFinal, startMs, end), end)
        }
        // 服务商可能先按字级/推导起点说这句，定稿那次才给真实 `start_time`：实测同一句 17 次修订
        // 都从 260 开始，final 却报 80 开始、end 一样是 6472。真实结束时间相同 + 区间真实重叠
        // ⇒ 就是同一句，落回原槽位，不再多开一行。
        val corrected = locateSameSentence(startMs, endMs)
        if (corrected != null) return accept(t, isFinal, corrected, endMs)
        // 水位以下又没有活槽位 = 累积重发里的历史句：丢掉，不复活、不刷新界面。
        if (startMs <= finalFloor) return null
        val end = maxOf(startMs, endMs ?: startMs)
        return store(VoiceTranscript(t, isFinal, startMs, end), end)
    }

    /**
     * 给「起点被修正过」的重发找原槽位：**严格**同一结束时间 + 真实区间重叠，且只认唯一一句。
     *
     *  - 不做 ±N 毫秒近似：相邻短句起点可能只差几十毫秒，按距离合并会吃掉其中一句；
     *  - 不看文本：两句说得一样但时间区间不同仍是两句（不是按文字去重）；
     *  - 只碰一个点（`startMs` 正好等于那句的结束）不算重叠，宁可多一条也不覆盖别的句子；
     *  - 匹配到多句 ⇒ 说不清是哪句，当新句。
     */
    private fun locateSameSentence(startMs: Long, endMs: Long?): Long? {
        if (endMs == null || endMs <= startMs) return null
        var hit: Long? = null
        for ((start, slot) in slots) {
            if (start >= endMs) break                // 后面只会更靠后，不可能再重叠
            val v = slot.value
            if (v.endMs != endMs) continue           // 结束时间不同就不是同一句
            if (v.endMs <= startMs) continue         // 只接触一个点，不算真实重叠
            if (hit != null) return null             // 多句都对得上 ⇒ 说不清，当新句
            hit = start
        }
        return hit
    }

    private fun store(value: VoiceTranscript, endMs: Long): VoiceTranscript {
        val startMs = value.startMs
        slots[startMs] = Slot(value)
        if (value.isFinal) {
            pending.remove(startMs)
            if (startMs > finalFloor) finalFloor = startMs
        } else {
            pending.add(startMs)
        }
        if (endMs > cursor) cursor = endMs
        trim()
        return value
    }

    /**
     * 槽位上限：界面只留最近 200 条，这里一倍余量足够去重，又不会随会议时长无限涨。
     * 淘汰最老的（开始时间最早的）—— 它们一定已定稿并落到水位以下，重发也进不来。
     */
    private fun trim() {
        while (slots.size > MAX_SLOTS) {
            val eldest = slots.firstKey()
            if (eldest in pending) break        // 还没定稿的不淘汰，宁可短暂多留几条
            slots.remove(eldest)
        }
    }

    /** 最近一句还没定稿的句子：服务商连时间都不给时挂到它上面。 */
    fun lastUnfinalized(): VoiceTranscript? = pending.lastOrNull()?.let { slots[it]?.value }

    companion object {
        const val MAX_SLOTS = 400
    }
}
