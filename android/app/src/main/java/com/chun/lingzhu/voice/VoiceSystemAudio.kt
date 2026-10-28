package com.chun.lingzhu.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build

/**
 * 系统播放声音的采集（Android 10 / API 29 起）：把手机正在播放的媒体声（USAGE_MEDIA / GAME / UNKNOWN）
 * 当录音源读出来，格式跟麦克风那一路完全一致（16k / 单声道 / s16le），好让 [VoiceMixer] 直接混。
 *
 * 几条**不做**的事，都是为了让「录了别人说的话」这件事有边界：
 *  - 不加 `USAGE_VOICE_COMMUNICATION`：通话录音本来就不允许通过播放捕获拿到，写进去只会让配置报错；
 *  - 不用 `CAPTURE_AUDIO_OUTPUT`（那是系统级权限，普通应用拿了会被系统直接拒）；
 *  - **不复用录屏那条 MediaProjection**：投影被 stop 会连带释放另一条链路（小球截图），
 *    这里每次都是从界面拿到的一份独立授权。
 *
 * 播放捕获本身也有系统限制，这里不粉饰：部分应用（DRM 保护的播放器、某些会议 / 通话应用）
 * 会禁止自己被捕获，那时读到的就是全零 —— 那不是故障，也不能替用户下结论说「禁止」。
 *
 * **public 而不是 internal**：验收 harness 用自己的包 PathClassLoader 加载正式 APK 后直接调这里，
 * internal 成员在 JVM 字节码上会被改名（`open$app`），按名字反射调不到。这里不做任何授权决策
 * （投影由调用方取与释放），公开它不会给产品多出任何入口。
 */
object VoiceSystemAudio {

    const val SAMPLE_RATE = 16_000

    /** 能捕获的用途：媒体、游戏、未标注的播放。**故意不含通话。** */
    private val USAGES = intArrayOf(
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_UNKNOWN,
    )

    fun available(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.Q

    /** 捕获配置：只按用途筛，不按包名 / uid 限制（用户选的是「系统声音」）。 */
    fun configFor(projection: MediaProjection): AudioPlaybackCaptureConfiguration {
        val b = AudioPlaybackCaptureConfiguration.Builder(projection)
        for (u in USAGES) b.addMatchingUsage(u)
        return b.build()
    }

    /** 混音 / 状态文案用的用途说明（写进页面，用户知道哪类声音会被录）。 */
    fun usageSummary(): String = "媒体 / 游戏 / 未标注的播放（不含通话）"

    /**
     * 打开系统播放这一路的 AudioRecord。**失败抛异常**（原因带在 message 里），
     * 由调用方决定怎么报 —— 绝不在这里退回到麦克风。
     *
     * 16k 单声道是本项目的协议约定；设备不接受这个格式就明确失败。
     */
    fun open(projection: MediaProjection, frameBytes: Int): AudioRecord {
        if (!available()) {
            throw IllegalStateException("这台设备是 API ${Build.VERSION.SDK_INT}，系统声音需要 Android 10 及以上")
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("这台设备不支持 16k 单声道录音格式")
        val rec = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * 4))
            .setAudioPlaybackCaptureConfig(configFor(projection))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            throw IllegalStateException("系统声音采集打不开：这台设备不接受播放捕获的 16k 单声道格式")
        }
        return rec
    }
}
