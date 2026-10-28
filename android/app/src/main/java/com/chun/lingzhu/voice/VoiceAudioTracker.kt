package com.chun.lingzhu.voice

/**
 * 采集过程中的「每一路到底有没有声音」状态机。**纯内存 + 纯计算**，所以生命周期
 * （先静音后出声、麦克风被系统静音、投影被撤销、停止后不再更新）能在 JVM 上直接测。
 *
 * 它不做任何推断，只记观察：
 *  - 某一路的帧里出现了非零样本 → 那一路 micHeard / systemHeard 变真，**永不回退**
 *    （说过话这件事发生过就是发生过，中途停顿不该把「已检测到声音」擦掉）；
 *  - **每一路的静音帧数各自数**：麦克风一直有人说话，绝不把「系统那一路一直是零」冲掉 ——
 *    混录的用户必须能看出是第二路没声音，而不是笼统地「正在转写」；
 *  - 麦克风被系统静音来自 AudioRecordingCallback（[onMicSilenced]），API 29 以下无从得知 → 保持 null。
 *
 * onXxxFrame 返回「里程碑」：这一路第一次听到声音、或某一路的静音帧数刚好到门槛。
 * 采集线程只在里程碑时把状态推给界面 —— 每帧都发一次快照既没必要，也白造对象。
 *
 * 谁调用它：采集线程（每帧，单线程），主线程（系统回调）。所以整个类自己加锁。
 */
internal class VoiceAudioTracker(val source: String) {

    private val lock = Any()
    private var micHeard = false
    private var systemHeard = false
    private var micSilenced: Boolean? = null
    private var micSilentFrames = 0
    private var systemSilentFrames = 0
    private var projectionRevoked = false

    /** 停止后置位：之后任何迟到的帧 / 回调都不再改状态（避免收尾期把快照改脏）。 */
    @Volatile private var frozen = false

    val usesMic: Boolean = VoiceSource.usesMic(source)
    val usesSystem: Boolean = VoiceSource.requiresProjection(source)

    /** 麦克风这一路的 200ms 帧。 */
    fun onMicFrame(buf: ByteArray, off: Int = 0, len: Int = buf.size): Boolean {
        if (frozen) return false
        val peak = VoiceMixer.peak(buf, off, len)
        return synchronized(lock) {
            if (frozen) return@synchronized false
            val was = micHeard
            if (usesMic && peak != 0) micHeard = true
            (!was && micHeard) || countMicLocked(peak != 0)
        }
    }

    /** 系统播放这一路的 200ms 帧。 */
    fun onSystemFrame(buf: ByteArray, off: Int = 0, len: Int = buf.size): Boolean {
        if (frozen) return false
        val peak = VoiceMixer.peak(buf, off, len)
        return synchronized(lock) {
            if (frozen) return@synchronized false
            val was = systemHeard
            if (usesSystem && peak != 0) systemHeard = true
            (!was && systemHeard) || countSystemLocked(peak != 0)
        }
    }

    /**
     * 混录：两路同一帧一起报，两路各自记状态与静音帧。
     *
     * 混录时两路未必同时读满一帧（播放捕获在安静时给得慢），采集线程会把**半帧**拼进同一个
     * 200ms 输出帧。所以它改用 [onMixedFramePeaks] 按「这一整帧里哪一路出现过非零样本」上报 ——
     * 否则一个 200ms 帧会被拆成好几「帧」计数，3 秒的静音门槛就变成 1 秒多。
     */
    fun onMixedFrame(mic: ByteArray, micLen: Int, sys: ByteArray, sysLen: Int): Boolean =
        onMixedFramePeaks(
            micAudible = VoiceMixer.peak(mic, 0, micLen) != 0,
            sysAudible = VoiceMixer.peak(sys, 0, sysLen) != 0,
        )

    /** 混录：一整个 200ms 帧里两路各自有没有出现过非零样本。 */
    fun onMixedFramePeaks(micAudible: Boolean, sysAudible: Boolean): Boolean {
        if (frozen) return false
        return synchronized(lock) {
            if (frozen) return@synchronized false
            val micWas = micHeard
            val sysWas = systemHeard
            if (usesMic && micAudible) micHeard = true
            if (usesSystem && sysAudible) systemHeard = true
            val micTurn = countMicLocked(micAudible)
            val sysTurn = countSystemLocked(sysAudible)
            (!micWas && micHeard) || (!sysWas && systemHeard) || micTurn || sysTurn
        }
    }

    private fun countMicLocked(audible: Boolean): Boolean {
        if (!usesMic) return false
        val before = micSilentFrames
        if (audible) micSilentFrames = 0 else if (micSilentFrames < VoiceSession.SILENCE_FRAMES) micSilentFrames++
        return before < VoiceSession.SILENCE_FRAMES && micSilentFrames >= VoiceSession.SILENCE_FRAMES
    }

    private fun countSystemLocked(audible: Boolean): Boolean {
        if (!usesSystem) return false
        val before = systemSilentFrames
        if (audible) systemSilentFrames = 0 else if (systemSilentFrames < VoiceSession.SILENCE_FRAMES) systemSilentFrames++
        return before < VoiceSession.SILENCE_FRAMES && systemSilentFrames >= VoiceSession.SILENCE_FRAMES
    }

    /** 系统回调：麦克风是否被静音。null = 不知道（API < 29 / 没有回调）。 */
    fun onMicSilenced(silenced: Boolean?) {
        if (frozen) return
        synchronized(lock) { micSilenced = silenced }
    }

    /** MediaProjection 的 onStop：系统声音的授权被撤回。 */
    fun onProjectionRevoked() {
        if (frozen) return
        synchronized(lock) { projectionRevoked = true }
    }

    /** 会话结束：此后不再更新（快照保留最后一次观察结果，供停止后的界面显示）。 */
    fun freeze() {
        frozen = true
    }

    fun status(): VoiceAudioStatus = synchronized(lock) {
        VoiceAudioStatus(
            source = source,
            micHeard = micHeard,
            micSilenced = micSilenced,
            systemHeard = systemHeard,
            micSilentFrames = micSilentFrames,
            systemSilentFrames = systemSilentFrames,
            projectionRevoked = projectionRevoked,
        )
    }
}
