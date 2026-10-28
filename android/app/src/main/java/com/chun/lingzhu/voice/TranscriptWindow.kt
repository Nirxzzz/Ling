package com.chun.lingzhu.voice

/**
 * 一次转写会话里「已经听到的句子」—— **只在内存里**，有上限，按 [VoiceTranscript.startMs] 原位替换。
 *
 * 三条规矩，都是为了避免出现「同一句话被追加一遍」和「转写越听越长直到 OOM」：
 *  1. **同一句只留一条**：用 `startMs` 当身份（协议里 `start_time` / `begin_time` 就是句子的稳定标识），
 *     新结果覆盖同 key 的旧结果，**不做字符串拼接** —— 服务商给的是整句重发，不是增量。
 *  2. **终态不会被降级**：已经 `isFinal` 的句子，再来一条 partial 一律忽略（迟到的旧回调）。
 *  3. **有界**：句数超上限先丢最老的**已定稿**句；单句字数也有上限。全部在内存，不落盘、不同步、不进历史。
 *
 * 不碰 android.*：这一层是纯数据，方便单测。
 */
class TranscriptWindow(private val limit: Int = MAX_UTTERANCES) {

    private val items = ArrayList<VoiceTranscript>(32)
    private val lock = Any()

    /**
     * 收一条结果。[startMs] 相同即视为同一句。
     *
     * @return 显示内容是否真的变了（变了才需要重画界面；纯重复的 partial 不重画）
     */
    fun apply(update: VoiceTranscript): Boolean = synchronized(lock) {
        val text = cap(update.text).trim()
        // 没有时间戳的句子无法定位槽位；空文本也不是一句能显示的话（服务商清空重发时会出现）。
        if (update.startMs < 0 || text.isEmpty()) return false
        val idx = indexOf(update.startMs)
        if (idx >= 0) {
            val old = items[idx]
            // 终态不回退：已定稿的句子不接受后来的 partial（网络乱序 / 迟到的旧包）
            if (old.isFinal && !update.isFinal) return false
            val next = VoiceTranscript(text, update.isFinal, update.startMs, maxOf(old.endMs, update.endMs))
            if (next.text == old.text && next.isFinal == old.isFinal && next.endMs == old.endMs) return false
            items[idx] = next
            return true
        }
        // 新句：按 startMs 插入，保持时间顺序（服务商偶尔会补发一句更早开始的）
        val at = items.indexOfFirst { it.startMs > update.startMs }
        items.add(if (at < 0) items.size else at, VoiceTranscript(text, update.isFinal, update.startMs, update.endMs))
        trim()
        true
    }

    fun snapshot(): List<VoiceTranscript> = synchronized(lock) { ArrayList(items) }

    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }

    /** 清空（开新会话 / 用户点了清空 / 离开页面）。 */
    fun clear() = synchronized(lock) { items.clear() }

    private fun indexOf(startMs: Long): Int = items.indexOfFirst { it.startMs == startMs }

    /** 超上限时先丢最老的已定稿句：还在说的那一句不能被顶掉。 */
    private fun trim() {
        while (items.size > limit) {
            val drop = items.indexOfFirst { it.isFinal }
            items.removeAt(if (drop >= 0) drop else 0)
        }
    }

    private fun cap(text: String): String =
        if (text.length <= MAX_CHARS) text else text.substring(0, MAX_CHARS)

    private companion object {
        /** 一次会话最多留多少句（一屏能看几十句，200 已经远超实际；超了先丢最老的定稿句） */
        const val MAX_UTTERANCES = 200

        /** 单句最多留多少字：防止某一家把整段累计文本当成一句重发，越滚越大 */
        const val MAX_CHARS = 2000
    }
}
