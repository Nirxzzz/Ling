package com.chun.lingzhu.voice

/**
 * 转写会话的跨界面共享状态（**只有内存**，进程死了就没了）。
 *
 * 界面可能比服务晚绑定、也可能被重建，所以这里留一份「最后一次状态 + 最后一次错误 + 已听到的句子」，
 * 新绑上来的界面能立刻画出真实状态，而不是从「未开始」闪一下。
 * 句子本身在 [TranscriptWindow]（有界）；离开页面时由界面调 [reset] 清干净 —— 不落盘、不同步。
 */
object VoiceSession {

    enum class Phase {
        /** 没在转写（未开始 / 已停止 / 启动失败） */
        IDLE,

        /** 已点了开始，正在连接 / 开麦，**还没有真的送出音频** */
        STARTING,

        /** 真的在录音并上传：只有第一帧音频送出去之后才会到这里 */
        RECORDING,

        /** 用户点了停止，正在收尾（等最后一句） */
        STOPPING,
    }

    /** 已经连续多少帧（200ms 一帧）完全静音才敢说「未检测到声音」：15 帧 = 3 秒。 */
    const val SILENCE_FRAMES = 15

    /** 会话里已经听到的句子（有界、按 startMs 原位替换） */
    val window = TranscriptWindow()

    @Volatile var last: VoiceSnapshot = VoiceSnapshot()

    /** 服务没起来时（连前台服务都拉不起来）直接把原因写进来，界面绑定时能看到。 */
    fun fail(message: String) {
        last = VoiceSnapshot(Phase.IDLE, message, error = true)
    }

    /** 离开页面 / 清空：状态与句子一起归零。录音还在跑时不调用（那种情况只清句子）。 */
    fun reset() {
        window.clear()
        last = VoiceSnapshot()
    }
}

/**
 * 这一次采集里**每一路**声音的实际观察结果。
 *
 * 为什么单列出来：混录时两路的状态必须各自说话。麦克风里有人在说话，绝不等于
 * 系统播放那一路也在出声音 —— 只用一句「正在转写」概括，用户会以为混录是好的，
 * 实际上系统那一路可能一直被禁止捕获。这里每个字段都只是**观察到的证据**，不是推断：
 *  - [micHeard] / [systemHeard] 只在一帧里读到非零样本之后才变真（全零就是没声音，不设音量阈值）；
 *  - [micSilenced] 由系统的 AudioRecordingCallback 给出，**低版本无从得知就是 null**，不假装知道；
 *  - 两路的静音帧数**各自**累加，各自到 [VoiceSession.SILENCE_FRAMES] 才允许说「这一路未检测到声音」。
 */
data class VoiceAudioStatus(
    val source: String = VoiceSource.MIC,
    /** 麦克风这一路真的读到过非零样本 */
    val micHeard: Boolean = false,
    /** 麦克风被系统静音：null = 这一档 / 这台设备无法判定（API 29 以下），false = 知道没被静音 */
    val micSilenced: Boolean? = null,
    /** 系统播放这一路真的读到过非零样本 */
    val systemHeard: Boolean = false,
    /** 麦克风这一路连续全零的帧数（封顶到 [VoiceSession.SILENCE_FRAMES]） */
    val micSilentFrames: Int = 0,
    /** 系统播放这一路连续全零的帧数（封顶到 [VoiceSession.SILENCE_FRAMES]） */
    val systemSilentFrames: Int = 0,
    /** 系统播放捕获的授权被系统 / 用户撤销（MediaProjection onStop） */
    val projectionRevoked: Boolean = false,
) {
    val usesMic: Boolean get() = VoiceSource.usesMic(source)
    val usesSystem: Boolean get() = VoiceSource.requiresProjection(source)

    /** 任何一路真的有过声音。 */
    val detected: Boolean get() = micHeard || systemHeard

    val micSilentTooLong: Boolean get() = micSilentFrames >= VoiceSession.SILENCE_FRAMES
    val systemSilentTooLong: Boolean get() = systemSilentFrames >= VoiceSession.SILENCE_FRAMES

    /** 这一路参与采集、持续零帧到了门槛（混录时两路各自判断，互不掩盖）。 */
    fun silentTooLong(): Boolean =
        (usesMic && !micHeard && micSilentTooLong) || (usesSystem && !systemHeard && systemSilentTooLong)

    /**
     * 逐路的状态行（混录时两行，各自独立）。纯计算：界面直接照抄，不用再自己拼字符串。
     */
    fun routeLines(): List<String> {
        val out = ArrayList<String>(3)
        if (usesMic) {
            out.add(
                when {
                    micHeard -> "麦克风：已检测到声音"
                    micSilentTooLong -> "麦克风：未检测到声音（可能静音，或被系统静音）"
                    else -> "麦克风：等第一段声音…"
                }
            )
        }
        if (usesSystem) {
            out.add(
                when {
                    systemHeard -> "系统声音：已检测到声音"
                    systemSilentTooLong -> "系统声音：未检测到声音（可能没在播放，或该应用禁止被捕获）"
                    else -> "系统声音：等第一段播放…"
                }
            )
        }
        if (micSilenced == true) {
            out.add("麦克风被系统静音：可能被别的应用占用，或系统把这一路静音了")
        } else if (micSilenced == null && usesMic && micSilentTooLong) {
            // 只在「确实一直没声音」时才提一句：平时没必要让用户读一句「本机无法判定」。
            out.add("这台设备（Android 10 以下）无法判定麦克风是否被系统静音")
        }
        if (projectionRevoked) out.add("系统声音的授权已被撤销")
        return out
    }
}

/** 服务 → 界面的一次状态快照（不可变小对象，跨线程靠赋值传递）。 */
data class VoiceSnapshot(
    val phase: VoiceSession.Phase = VoiceSession.Phase.IDLE,
    val text: String = "未在转写",
    val error: Boolean = false,
    /** 真的在采集并上传：`phase == RECORDING` 的同义词，单列出来是为了让界面读得直白 */
    val recording: Boolean = phase == VoiceSession.Phase.RECORDING,
    /** 这一档的声音来源与每一路的观察结果（界面按它画「麦克风 / 系统声音」两行状态） */
    val audio: VoiceAudioStatus = VoiceAudioStatus(),
)
