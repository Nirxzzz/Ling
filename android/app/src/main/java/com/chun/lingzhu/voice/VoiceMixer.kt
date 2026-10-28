package com.chun.lingzhu.voice

/**
 * 两路 16k / 单声道 / s16le PCM 的混音器。**纯计算，不碰 AudioRecord**，所以边界（饱和、短帧、半样本）
 * 都能在 JVM 上直接测。
 *
 * 两条硬规则，都是为了让「混音」听起来只是「两路都在」，而不是失真或忽大忽小：
 *  1. **逐样本相加后饱和到 Int16**（不打回绕）。直接 `Short` 相加会溢出翻转，声音会变成刺耳的破音；
 *     除以 2 又会把「只有麦克风说话、系统静音」这种情况整体压小一半，识别率跟着掉。
 *  2. **只混两路对齐的完整样本**。任何一路短读（设备给不满一帧）就按较短的那一路混，
 *     多出来的半截字节不参与，由调用方接着读下一帧补齐 —— 绝不把半样本拼进下一帧，
 *     那会让整段音频从此错开一个字节，听起来是持续的炸响。
 *
 * 复用调用方的缓冲：混音不为每帧分配任何数组（200ms 一帧、一录几十分钟，这个量不能忽GC）。
 *
 * **public 而不是 internal**：验收 harness 用自己的包 PathClassLoader 加载正式 APK 后直接调这里，
 * internal 成员在 JVM 字节码上会被改名（`mixInto$app`），按名字反射调不到。这里没有状态、没有设备依赖，
 * 公开它不会给产品多出任何入口。
 */
object VoiceMixer {

    /**
     * 把 [mic]／[sys] 里对齐的部分逐样本相加写进 [out]。

     * 返回**实际写入 @[outOff] 的字节数**（偶数，等于两路较短的那一路向下取整到样本的长度）。
     * [out] 与两路缓冲是同一块也安全：每个样本都是先读完两路、再写出去。
     */
    fun mixInto(
        mic: ByteArray, micOff: Int, micLen: Int,
        sys: ByteArray, sysOff: Int, sysLen: Int,
        out: ByteArray, outOff: Int,
    ): Int {
        val usable = minOf(micLen, sysLen)
        val bytes = if (usable <= 0) 0 else usable - (usable % 2)
        var i = 0
        while (i < bytes) {
            val v = saturate(sampleAt(mic, micOff + i) + sampleAt(sys, sysOff + i))
            writeSample(out, outOff + i, v)
            i += 2
        }
        return bytes
    }

    /** 读一个 s16le 样本（小端、有符号）。 */
    fun sampleAt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) or (b[off + 1].toInt() shl 8)).toShort().toInt()

    /** 写一个 s16le 样本（小端、有符号）。饱和后写。 */
    fun writeSample(out: ByteArray, off: Int, v: Int) {
        val s = saturate(v)
        out[off] = (s and 0xff).toByte()
        out[off + 1] = ((s shr 8) and 0xff).toByte()
    }

    /** 相加后的限幅：超出 Int16 就贴边，不回绕。 */
    fun saturate(sum: Int): Int = when {
        sum > Short.MAX_VALUE -> Short.MAX_VALUE.toInt()
        sum < Short.MIN_VALUE -> Short.MIN_VALUE.toInt()
        else -> sum
    }

    /**
     * 这一段里最大的样本绝对值（0 = 全零帧）。
     *
     * 只用来说明「这一路到底有没有声音」：**全零**才算没声音，不做「音量阈值」判断 ——
     * 阈值低了会把安静说话判成没声音，阈值高了又变成替用户下结论。真实设备的静音就是全零。
     */
    fun peak(b: ByteArray, off: Int = 0, len: Int = b.size): Int {
        val end = minOf(b.size, off + len) - 1
        var i = off
        var peak = 0
        while (i < end) {
            val v = sampleAt(b, i)
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }
}
