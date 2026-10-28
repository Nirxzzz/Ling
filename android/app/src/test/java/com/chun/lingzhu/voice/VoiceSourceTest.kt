package com.chun.lingzhu.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音来源与「这一路到底有没有声音」的纯 JVM 测试。
 *
 * 只测用户真能看见 / 听见的后果：
 *  - 混音饱和（不回绕成破音）、短帧只混整样本（不错位）；
 *  - 每一路的状态各自说话（麦克风有人说话不能掩盖系统那一路全静音）；
 *  - 低版本系统声音不可选、不可用原因带 API 号（不悄悄退回麦克风）；
 *  - 同意的存储键按「服务商 × 来源」分开，麦克风沿用旧键（升级不丢已同意）。
 */
class VoiceSourceTest {

    // ---------------- 混音：饱和而不是回绕 ----------------

    @Test fun mixSaturatesInsteadOfWrapping() {
        val mic = pcm(Short.MAX_VALUE.toInt(), 30_000)
        val sys = pcm(Short.MAX_VALUE.toInt(), 30_000)
        val out = ByteArray(4)
        val n = VoiceMixer.mixInto(mic, 0, mic.size, sys, 0, sys.size, out, 0)
        assertEquals("两个样本", 4, n)
        assertEquals("正溢出贴到 32767，不能回绕成负数", Short.MAX_VALUE.toInt(), VoiceMixer.sampleAt(out, 0))
        assertEquals(Short.MAX_VALUE.toInt(), VoiceMixer.sampleAt(out, 2))
    }

    @Test fun mixSaturatesOnNegativeOverflow() {
        val mic = pcm(Short.MIN_VALUE.toInt())
        val sys = pcm(Short.MIN_VALUE.toInt())
        val out = ByteArray(2)
        VoiceMixer.mixInto(mic, 0, 2, sys, 0, 2, out, 0)
        assertEquals(Short.MIN_VALUE.toInt(), VoiceMixer.sampleAt(out, 0))
    }

    @Test fun mixKeepsQuietRoomNoiseQuiet() {
        // 只有一路说话（另一路全零）时不做衰减：识别率靠的就是这一条。
        val mic = pcm(12_000)
        val sys = pcm(0)
        val out = ByteArray(2)
        VoiceMixer.mixInto(mic, 0, 2, sys, 0, 2, out, 0)
        assertEquals(12_000, VoiceMixer.sampleAt(out, 0))
    }

    @Test fun mixOnlyTakesWholeSamples() {
        val mic = pcm(1, 2, 3)      // 6 字节 = 3 个样本
        val sys = pcm(1, 2)         // 4 字节 = 2 个样本
        val out = ByteArray(6)
        val n = VoiceMixer.mixInto(mic, 0, mic.size, sys, 0, sys.size, out, 0)
        assertEquals("只能混两整样本，不能把半个样本拼进去", 4, n)
        assertEquals(2, VoiceMixer.sampleAt(out, 0))
        assertEquals(4, VoiceMixer.sampleAt(out, 2))
    }

    @Test fun mixIgnoresTrailingHalfSample() {
        val mic = pcm(5, 6)         // 4 字节
        val sys = ByteArray(3)      // 3 字节：1.5 个样本
        val out = ByteArray(4)
        val n = VoiceMixer.mixInto(mic, 0, mic.size, sys, 0, sys.size, out, 0)
        assertEquals("两路较短的一路向下取整到样本", 2, n)
        assertEquals(5, VoiceMixer.sampleAt(out, 0))
    }

    @Test fun mixWithEmptyRouteWritesNothing() {
        val out = ByteArray(4)
        val n = VoiceMixer.mixInto(pcm(1, 2), 0, 4, ByteArray(0), 0, 0, out, 0)
        assertEquals(0, n)
        assertEquals(0, VoiceMixer.peak(out))
    }

    @Test fun peakDetectsOnlyRealSamples() {
        assertEquals(0, VoiceMixer.peak(pcm(0, 0, 0)))
        assertEquals(7, VoiceMixer.peak(pcm(0, 7, 0)))
        assertEquals("负数取绝对值", 8, VoiceMixer.peak(pcm(0, -8)))
    }

    // ---------------- 来源可用性 ----------------

    @Test fun micAlwaysAvailableEvenOnOldDevices() {
        for (sdk in intArrayOf(26, 28, 29, 34)) {
            assertTrue("API $sdk", VoiceSource.available(VoiceSource.MIC, sdk))
            assertNull(VoiceSource.unavailableReason(VoiceSource.MIC, sdk))
        }
    }

    @Test fun systemSourcesNeedApi29() {
        assertFalse(VoiceSource.available(VoiceSource.SYSTEM, 28))
        assertFalse(VoiceSource.available(VoiceSource.MIXED, 28))
        assertTrue(VoiceSource.available(VoiceSource.SYSTEM, 29))
        assertTrue(VoiceSource.available(VoiceSource.MIXED, 29))
        val why = VoiceSource.unavailableReason(VoiceSource.SYSTEM, 28)
        assertTrue("原因要带上当前 API 号，用户才能对上机型", why!!.contains("28"))
    }

    @Test fun micOnlySourceSkipsMicFlag() {
        assertTrue(VoiceSource.usesMic(VoiceSource.MIC))
        assertTrue(VoiceSource.usesMic(VoiceSource.MIXED))
        assertFalse("系统声音档不能开麦", VoiceSource.usesMic(VoiceSource.SYSTEM))
        assertFalse(VoiceSource.requiresProjection(VoiceSource.MIC))
        assertTrue(VoiceSource.requiresProjection(VoiceSource.SYSTEM))
        assertTrue(VoiceSource.requiresProjection(VoiceSource.MIXED))
    }

    // ---------------- 每一路的状态 ----------------

    @Test fun micSpeakingDoesNotHideSilentSystemRoute() {
        val t = VoiceAudioTracker(VoiceSource.MIXED)
        repeat(VoiceSession.SILENCE_FRAMES) { t.onMixedFrame(pcm(9_000), 2, pcm(0), 2) }
        val st = t.status()
        assertTrue("麦克风确实有声音", st.micHeard)
        assertFalse("系统那一路一直是零，不能说也听到了", st.systemHeard)
        assertTrue("有一路有声音就不算整体静音", st.detected)
        assertTrue("系统那一路自己到了静音门槛", st.systemSilentTooLong)
        assertFalse("麦克风这一路不该被一起判成没声音", st.micSilentTooLong)
    }

    @Test fun silenceNeedsSeveralFramesBeforeSayingNoSound() {
        val t = VoiceAudioTracker(VoiceSource.MIC)
        t.onMicFrame(pcm(0))
        assertFalse("一帧静音不能就下结论", t.status().silentTooLong())
        repeat(VoiceSession.SILENCE_FRAMES - 2) { t.onMicFrame(pcm(0)) }
        assertFalse("差一帧还不行", t.status().silentTooLong())
        t.onMicFrame(pcm(0))
        assertTrue("够了门槛才说未检测到声音", t.status().silentTooLong())
    }

    /**
     * 混录的静音门槛按**一整个 200ms 帧**算，不按采集线程每次 read 的小块算。
     *
     * 播放捕获在安静时给得慢，一个 200ms 帧可能由四五块拼成：如果每块都记一「帧」，
     * 「3 秒没声音」会缩成 1 秒多 —— 用户会被提前告知没声音，而其实才刚安静了一下。
     */
    @Test fun mixedSilenceCountsWholeFramesNotReadChunks() {
        val t = VoiceAudioTracker(VoiceSource.MIXED)
        repeat(VoiceSession.SILENCE_FRAMES - 1) { t.onMixedFramePeaks(micAudible = false, sysAudible = false) }
        assertFalse("少一帧还不能下结论", t.status().silentTooLong())
        t.onMixedFramePeaks(micAudible = false, sysAudible = false)
        assertTrue(t.status().silentTooLong())

        // 同一个 200ms 帧里先静、后出声：出声那一下就把这一路的静音计数清零。
        val u = VoiceAudioTracker(VoiceSource.MIXED)
        repeat(VoiceSession.SILENCE_FRAMES - 1) { u.onMixedFramePeaks(false, false) }
        u.onMixedFramePeaks(micAudible = true, sysAudible = false)
        val st = u.status()
        assertTrue(st.micHeard)
        assertEquals("麦克风这一路重新计数", 0, st.micSilentFrames)
        assertTrue("系统那一路不受影响，继续数", st.systemSilentTooLong)
    }

    @Test fun heardSoundStaysHeardAfterSilence() {
        val t = VoiceAudioTracker(VoiceSource.MIC)
        t.onMicFrame(pcm(5_000))
        repeat(VoiceSession.SILENCE_FRAMES + 3) { t.onMicFrame(pcm(0)) }
        val st = t.status()
        assertTrue("说过话这件事发生过就是发生过", st.micHeard)
        assertFalse("中间静音不该被算成「没声音」", st.silentTooLong())
    }

    @Test fun milestoneFiresOncePerRoute() {
        val t = VoiceAudioTracker(VoiceSource.MIC)
        assertTrue("第一次听到要有一次刷新", t.onMicFrame(pcm(1)))
        assertFalse("同一路第二次不用再刷", t.onMicFrame(pcm(1)))
    }

    @Test fun systemSourceIgnoresMicFrames() {
        val t = VoiceAudioTracker(VoiceSource.SYSTEM)
        t.onMicFrame(pcm(9_000, 9_000))
        assertFalse("系统声音档根本不开麦，麦克风帧不算数", t.status().micHeard)
        t.onSystemFrame(pcm(4_000))
        assertTrue(t.status().systemHeard)
    }

    @Test fun silencedAndRevokedAreReported() {
        val t = VoiceAudioTracker(VoiceSource.MIXED)
        assertNull("没有系统回调时就是不知道", t.status().micSilenced)
        t.onMicSilenced(true)
        assertEquals(true, t.status().micSilenced)
        t.onProjectionRevoked()
        assertTrue(t.status().projectionRevoked)
        val lines = t.status().routeLines()
        assertTrue("撤销与静音都要在页面里有对应的一句", lines.size >= 2)
    }

    @Test fun freezeStopsLaterUpdates() {
        val t = VoiceAudioTracker(VoiceSource.MIXED)
        t.onMixedFrame(pcm(1_000), 2, pcm(0), 2)
        t.freeze()
        t.onMixedFrame(pcm(0), 2, pcm(8_000), 2)
        t.onMicSilenced(true)
        val st = t.status()
        assertTrue(st.micHeard)
        assertFalse("停止后迟到的帧不许改状态", st.systemHeard)
        assertNull(st.micSilenced)
    }

    @Test fun snapshotWithoutAudioKeepsMicDefault() {
        val snap = VoiceSnapshot()
        assertEquals(VoiceSource.MIC, snap.audio.source)
        assertTrue(snap.audio.usesMic)
        assertFalse(snap.audio.usesSystem)
    }

    // ---------------- 同意的键 ----------------

    @Test fun micConsentKeepsLegacyKey() {
        assertEquals("consent.doubao", VoiceSettings.consentKey(VoiceProvider.DOUBAO, VoiceSource.MIC))
        assertEquals("consent.alibaba", VoiceSettings.consentKey(VoiceProvider.ALIBABA, VoiceSource.MIC))
    }

    @Test fun systemConsentIsSeparateFromMic() {
        val sys = VoiceSettings.consentKey(VoiceProvider.DOUBAO, VoiceSource.SYSTEM)
        val mix = VoiceSettings.consentKey(VoiceProvider.DOUBAO, VoiceSource.MIXED)
        assertEquals("consent.system.doubao", sys)
        assertEquals("consent.mixed.doubao", mix)
        assertFalse("同意麦克风不等于同意系统声音", sys == VoiceSettings.consentKey(VoiceProvider.DOUBAO, VoiceSource.MIC))
        assertFalse(sys == mix)
    }

    /** 小端 s16le 样本序列。 */
    private fun pcm(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        for ((i, v) in values.withIndex()) VoiceMixer.writeSample(out, i * 2, v)
        return out
    }
}
