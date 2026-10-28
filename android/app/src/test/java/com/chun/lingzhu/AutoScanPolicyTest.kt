package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.70 自动扫描策略（AutoScanPolicy）：全纯函数，不碰 Android / SharedPreferences。
 *  - 画面指纹：缩图灰度 + 8×16 网格取样 + 只留高 4 位，认「一模一样」
 *  - 变化预检：指纹相同就跳过这一轮，连缓存都不查
 *  - 连续无变化计数 → 到阈值自动暂停（用户忘了关时整晚空烧）
 *  - 倒计时 / 状态行文案
 */
class AutoScanPolicyTest {

    // ================= 工具：造确定的测试图 =================

    /** 斜向渐变，行列都有变化；改一个采样点就能测出差异 */
    private fun ramp(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        ((x * 5 + y * 11) % 256)
    }

    private fun copyWith(src: IntArray, w: Int, h: Int, y0: Int, y1: Int, v: Int): IntArray {
        val c = src.copyOf()
        for (y in y0 until minOf(y1, h)) for (x in 0 until w) c[y * w + x] = v
        return c
    }

    // ================= 1) 指纹 =================

    @Test fun fingerprintIsStableHex64() {
        // 同一张图算两次必须一模一样（哈希稳定，否则每轮都会被当成「变了」→ 白调接口）
        val g = ramp(32, 64)
        val a = AutoScanPolicy.lightFingerprint(g, 32, 64)
        val b = AutoScanPolicy.lightFingerprint(g, 32, 64)
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test fun fingerprintChangesWhenContentChanges() {
        val g = ramp(32, 64)
        val base = AutoScanPolicy.lightFingerprint(g, 32, 64)
        // 改中间一块内容（必然落在采样网格上）→ 指纹必须变
        val g2 = g.copyOf()
        for (y in 20..40) for (x in 5..25) g2[y * 32 + x] = 250
        assertNotEquals(base, AutoScanPolicy.lightFingerprint(g2, 32, 64))
    }

    @Test fun fingerprintIgnoresStatusBarRegion() {
        // 状态栏的时间 / 电量一直在变，不能因此每轮都重算。skipTop 默认 6%，h=100 时前 6 行不采样
        val g = ramp(32, 100)
        val base = AutoScanPolicy.lightFingerprint(g, 32, 100)
        val noisy = copyWith(g, 32, 100, 0, 6, 255)
        assertEquals(base, AutoScanPolicy.lightFingerprint(noisy, 32, 100))
        // 对照：skipTop=0 时同一张图就采样到第 0 行，指纹必须不同（证明上面不是「压根没采样」）
        assertNotEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 100, skipTop = 0f),
            AutoScanPolicy.lightFingerprint(noisy, 32, 100, skipTop = 0f)
        )
    }

    @Test fun fingerprintToleratesJpegNoiseWithinSameQuantizedStep() {
        // 只留高 4 位 → 同一档内的细微噪声（JPEG 压缩、录屏噪点）不算「画面变了」
        val g = IntArray(32 * 64) { 0x10 }          // 高 4 位 = 1
        val jitter = IntArray(32 * 64) { 0x1F }     // 高 4 位 = 1
        assertEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 64),
            AutoScanPolicy.lightFingerprint(jitter, 32, 64)
        )
        // 跨一档（0x10 → 0x20）就必须算变了
        assertNotEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 64),
            AutoScanPolicy.lightFingerprint(IntArray(32 * 64) { 0x20 }, 32, 64)
        )
    }

    @Test fun fingerprintRejectsInvalidInput() {
        // 拿不到指纹 → 返回空串，调用方按「不知道」处理，不当成「画面没变」
        assertEquals("", AutoScanPolicy.lightFingerprint(IntArray(0), 0, 0))
        assertEquals("", AutoScanPolicy.lightFingerprint(ramp(32, 64), 0, 64))
        assertEquals("", AutoScanPolicy.lightFingerprint(ramp(32, 64), 32, 0))
        assertEquals("", AutoScanPolicy.lightFingerprint(ramp(32, 64), 32, -1))
        // 像素数不够（解码被截断）→ 空串，不是半个图的指纹
        assertEquals("", AutoScanPolicy.lightFingerprint(IntArray(100), 32, 64))
    }

    @Test fun fingerprintCoercesGridToAtLeastOne() {
        // cols/rows 传 0 或负数不能崩：夹到 1
        val g = ramp(32, 64)
        assertEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 64, 1, 1),
            AutoScanPolicy.lightFingerprint(g, 32, 64, 0, 0)
        )
        assertEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 64, 1, 1),
            AutoScanPolicy.lightFingerprint(g, 32, 64, -8, -8)
        )
    }

    @Test fun fingerprintToleratesOutOfRangeSkipTop() {
        // skipTop 越界（负数 / >1）不能越界读，也不能崩
        val g = ramp(32, 64)
        val a = AutoScanPolicy.lightFingerprint(g, 32, 64, skipTop = -0.5f)
        val b = AutoScanPolicy.lightFingerprint(g, 32, 64, skipTop = 0f)
        val c = AutoScanPolicy.lightFingerprint(g, 32, 64, skipTop = 5f)
        assertEquals(64, a.length); assertEquals(64, b.length); assertEquals(64, c.length)
    }

    @Test fun fingerprintDiffersForDifferentGridDensity() {
        // 网格密度写进了哈希前缀（8x16:），不同密度不该撞出同一个指纹
        val g = ramp(32, 64)
        assertNotEquals(
            AutoScanPolicy.lightFingerprint(g, 32, 64, 8, 16),
            AutoScanPolicy.lightFingerprint(g, 32, 64, 4, 8)
        )
    }

    @Test fun outOfRangeGrayValuesAreClamped() {
        // 解码偶尔给出越界灰度：夹到 0~255，不能因为 coerceIn 变出 'a'+16 这种越界字符
        val a = IntArray(32 * 64) { -999 }
        val b = IntArray(32 * 64) { 0 }
        assertEquals(
            AutoScanPolicy.lightFingerprint(a, 32, 64),
            AutoScanPolicy.lightFingerprint(b, 32, 64)
        )
        val c = IntArray(32 * 64) { 9999 }
        val d = IntArray(32 * 64) { 255 }
        assertEquals(
            AutoScanPolicy.lightFingerprint(c, 32, 64),
            AutoScanPolicy.lightFingerprint(d, 32, 64)
        )
    }

    // ================= 2) 变化预检 shouldSkip =================

    @Test fun sameFingerprintSkipsRound() {
        assertTrue(AutoScanPolicy.shouldSkip("abc", "abc"))
    }

    @Test fun differentFingerprintTriggersRound() {
        assertFalse(AutoScanPolicy.shouldSkip("abc", "abd"))
        assertFalse(AutoScanPolicy.shouldSkip("abc", "abc "))   // 差一个空格也算不同
        assertFalse(AutoScanPolicy.shouldSkip("abc", "ABC"))
    }

    @Test fun missingFingerprintNeverSkips() {
        // 拿不到指纹 → 宁可多调一次接口，也不要漏更新
        assertFalse(AutoScanPolicy.shouldSkip(null, "abc"))
        assertFalse(AutoScanPolicy.shouldSkip("abc", null))
        assertFalse(AutoScanPolicy.shouldSkip(null, null))
        assertFalse(AutoScanPolicy.shouldSkip("", "abc"))
        assertFalse(AutoScanPolicy.shouldSkip("abc", ""))
        assertFalse(AutoScanPolicy.shouldSkip("", ""))
    }

    @Test fun emptyLightFingerprintDoesNotSkip() {
        // lightFingerprint 失败返回空串，直接喂给 shouldSkip 不能被当成「画面没动」
        val bad = AutoScanPolicy.lightFingerprint(IntArray(10), 32, 64)
        assertEquals("", bad)
        assertFalse(AutoScanPolicy.shouldSkip(bad, bad))
        assertFalse(AutoScanPolicy.shouldSkip("good", bad))
    }

    // ================= 3) observe：连续无变化计数 =================

    @Test fun firstRoundIsNeverSkipped() {
        val (st, skipped) = AutoScanPolicy.observe(AutoScanPolicy.Still(), "fp1")
        assertFalse("没有上一轮可比，不能跳", skipped)
        assertEquals(0, st.rounds)
        assertEquals("fp1", st.fp)
    }

    @Test fun sameFingerprintIncrementsRoundsAndSkips() {
        var st = AutoScanPolicy.observe(AutoScanPolicy.Still(), "same").first   // 首轮建基线（真调接口），不计入跳过
        var skipped = false
        for (i in 1..5) {
            val r = AutoScanPolicy.observe(st, "same")
            st = r.first; skipped = r.second
            assertTrue("第 $i 轮画面没变，应该跳过", skipped)
            assertEquals(i, st.rounds)
        }
    }

    @Test fun changedFingerprintResetsRoundsToZero() {
        // 中途翻页 → 计数必须清零，不能把前后两段静默拼起来
        var st = AutoScanPolicy.Still()
        st = AutoScanPolicy.observe(st, "page1").first
        st = AutoScanPolicy.observe(st, "page1").first
        st = AutoScanPolicy.observe(st, "page1").first
        assertEquals(2, st.rounds)   // 基线那轮不计入（P1 口径）
        val (after, skipped) = AutoScanPolicy.observe(st, "page2")
        assertFalse("画面变了，必须调接口", skipped)
        assertEquals(0, after.rounds)
        assertEquals("page2", after.fp)
    }

    @Test fun nullFingerprintResetsRoundsAndKeepsFp() {
        // 这一轮没解出指纹 = 状态不可知：rounds 清零，但指纹留着，下一轮还能接着比
        val prev = AutoScanPolicy.Still("fp1", 7)
        val (after, skipped) = AutoScanPolicy.observe(prev, null)
        assertFalse(skipped)
        assertEquals(0, after.rounds)
        assertEquals("fp1", after.fp)
        // 空串走同一条路
        val (after2, skipped2) = AutoScanPolicy.observe(prev, "")
        assertFalse(skipped2)
        assertEquals(0, after2.rounds)
        assertEquals("fp1", after2.fp)
        // 下一轮又变回同一个指纹 → 从 1 开始重新数
        val (after3, skipped3) = AutoScanPolicy.observe(after, "fp1")
        assertTrue(skipped3)
        assertEquals(1, after3.rounds)
    }

    @Test fun resetRoundsKeepsFingerprintForNextCompare() {
        // 「点一下继续」走的就是 resetRounds：轮数归零，指纹留着当下一轮的「上一轮」
        val s = AutoScanPolicy.Still("fp1", 9).resetRounds()
        assertEquals(0, s.rounds)
        assertEquals("fp1", s.fp)
        val (next, skipped) = AutoScanPolicy.observe(s, "fp1")
        assertTrue("恢复后画面还是没动，这一轮仍应跳过", skipped)
        assertEquals(1, next.rounds)
    }

    @Test fun defaultStillHasNullFpAndZeroRounds() {
        val s = AutoScanPolicy.Still()
        assertEquals(null, s.fp)
        assertEquals(0, s.rounds)
    }

    // ================= 4) 阈值与自动暂停判定 =================

    @Test fun pausesExactlyAtThreshold() {
        val t = AutoScanPolicy.DEFAULT_STILL_ROUNDS
        assertFalse(AutoScanPolicy.shouldPause(t - 1, t))
        assertTrue(AutoScanPolicy.shouldPause(t, t))
        assertTrue(AutoScanPolicy.shouldPause(t + 1, t))
    }

    @Test fun thresholdZeroOrNegativeDisablesAutoPause() {
        // threshold <= 0 = 用户关了自动暂停这个开关
        assertFalse(AutoScanPolicy.shouldPause(100, 0))
        assertFalse(AutoScanPolicy.shouldPause(100, -1))
        assertFalse(AutoScanPolicy.shouldPause(0, 0))
        // 开了也不能在 rounds=0 时就停
        assertFalse(AutoScanPolicy.shouldPause(0, 2))
    }

    @Test fun clampRoundsKeepsSettingInRange() {
        // 太小：题目切页中间就误停；太大：该停没停
        assertEquals(AutoScanPolicy.STILL_ROUNDS_MIN, AutoScanPolicy.clampRounds(0))
        assertEquals(AutoScanPolicy.STILL_ROUNDS_MIN, AutoScanPolicy.clampRounds(-99))
        assertEquals(AutoScanPolicy.STILL_ROUNDS_MIN, AutoScanPolicy.clampRounds(1))
        assertEquals(AutoScanPolicy.DEFAULT_STILL_ROUNDS, AutoScanPolicy.clampRounds(AutoScanPolicy.DEFAULT_STILL_ROUNDS))
        assertEquals(AutoScanPolicy.STILL_ROUNDS_MAX, AutoScanPolicy.clampRounds(AutoScanPolicy.STILL_ROUNDS_MAX))
        assertEquals(AutoScanPolicy.STILL_ROUNDS_MAX, AutoScanPolicy.clampRounds(99999))
    }

    @Test fun configuredThresholdDrivesRealPauseSequence() {
        // 阈值配置真的生效：阈值 3 时第 4 轮静默就停，阈值 5 时第 6 轮停
        // （首轮只用来建立基线，rounds 还是 0，所以是第 threshold+1 轮触发）
        for (raw in listOf(3, 5, 10)) {
            val t = AutoScanPolicy.clampRounds(raw)
            var st = AutoScanPolicy.Still()
            var pausedAt = -1
            for (i in 1..20) {
                st = AutoScanPolicy.observe(st, "still").first
                if (pausedAt < 0 && AutoScanPolicy.shouldPause(st.rounds, t)) pausedAt = i
            }
            assertEquals("阈值 $raw 应在第 ${t + 1} 轮停", t + 1, pausedAt)
        }
    }

    @Test fun changingPageResetsPauseSequence() {
        // 中间翻一页 → 静默计数清零 → 阈值从新一轮重新数
        val t = AutoScanPolicy.clampRounds(4)
        var st = AutoScanPolicy.Still()
        // page1 静默 3 轮（未到阈值 4）
        st = AutoScanPolicy.observe(st, "page1").first   // rounds 0，建立基线
        st = AutoScanPolicy.observe(st, "page1").first   // rounds 1
        st = AutoScanPolicy.observe(st, "page1").first   // rounds 2
        st = AutoScanPolicy.observe(st, "page1").first   // rounds 3
        assertEquals(3, st.rounds)
        assertFalse(AutoScanPolicy.shouldPause(st.rounds, t))
        // 翻页 → 清零
        st = AutoScanPolicy.observe(st, "page2").first
        assertEquals(0, st.rounds)
        assertEquals("page2", st.fp)
        // 新页面再静默 3 轮也不停，第 4 轮才停
        repeat(3) { st = AutoScanPolicy.observe(st, "page2").first }
        assertEquals(3, st.rounds)
        assertFalse("第 4 轮才到阈值", AutoScanPolicy.shouldPause(st.rounds, t))
        st = AutoScanPolicy.observe(st, "page2").first
        assertEquals(4, st.rounds)
        assertTrue(AutoScanPolicy.shouldPause(st.rounds, t))
    }

    @Test fun defaultThresholdIsWithinClampRange() {
        val d = AutoScanPolicy.DEFAULT_STILL_ROUNDS
        assertTrue(d >= AutoScanPolicy.STILL_ROUNDS_MIN)
        assertTrue(d <= AutoScanPolicy.STILL_ROUNDS_MAX)
        assertEquals(d, AutoScanPolicy.clampRounds(d))
    }

    // ================= 5) 倒计时文案 =================

    @Test fun countdownNeverShowsZeroSeconds() {
        assertEquals("马上", AutoScanPolicy.countdown(0))
        assertEquals("马上", AutoScanPolicy.countdown(-1))
        assertEquals("马上", AutoScanPolicy.countdown(-9999))
        assertEquals("1s 后", AutoScanPolicy.countdown(1))
    }

    @Test fun countdownRoundsUpWithinSecond() {
        // 向上取整：999ms 显示「1s 后」，不是「0s 后」
        assertEquals("1s 后", AutoScanPolicy.countdown(1))
        assertEquals("1s 后", AutoScanPolicy.countdown(500))
        assertEquals("1s 后", AutoScanPolicy.countdown(999))
        assertEquals("1s 后", AutoScanPolicy.countdown(1000))
        assertEquals("2s 后", AutoScanPolicy.countdown(1001))
        assertEquals("2s 后", AutoScanPolicy.countdown(1999))
    }

    @Test fun countdownFormatsWholeSeconds() {
        assertEquals("3s 后", AutoScanPolicy.countdown(2500))
        assertEquals("12s 后", AutoScanPolicy.countdown(12000))
        assertEquals("13s 后", AutoScanPolicy.countdown(12999))   // 向上取整：还剩 12.999s 也说 13s
        assertEquals("60s 后", AutoScanPolicy.countdown(60000))
        assertEquals("3600s 后", AutoScanPolicy.countdown(3_600_000))
    }

    // ================= 6) 状态行 / 跳过文案 =================

    @Test fun statusShowsPausedReasonFirst() {
        val s = AutoScanPolicy.status(autoOn = true, pausedByStill = true, stillRounds = 10, remainingMs = 12_000)
        assertTrue(s, s.contains("已暂停"))
        assertTrue(s, s.contains("10 轮没变化"))
        assertTrue(s, s.contains("点这里继续"))
        // 暂停时不谈倒计时
        assertFalse(s, s.contains("s 后"))
    }

    @Test fun statusShowsOffWhenDisabled() {
        assertEquals("自动扫描 · 关", AutoScanPolicy.status(false, false, 0, 12_000))
        assertEquals("自动扫描 · 关", AutoScanPolicy.status(false, false, 99, 0))
    }

    @Test fun statusShowsCountdownWhenRunning() {
        assertEquals("自动扫描 · 12s 后", AutoScanPolicy.status(true, false, 0, 12_000))
        assertEquals("自动扫描 · 马上", AutoScanPolicy.status(true, false, 0, 0))
        assertEquals("自动扫描 · 1s 后", AutoScanPolicy.status(true, false, 3, 1))
    }

    @Test fun statusOffWinsOverPaused() {
        // 已修复（主脑整合期，31号 P2）：开关已关时暂停态必须让位，
        // 否则状态行显示「点这里继续」但点了也没有扫描在跑。
        assertEquals(
            "自动扫描 · 关",
            AutoScanPolicy.status(false, true, 5, 12_000)
        )
    }

    @Test fun skippedStatusMentionsRoundAndNoApiCall() {
        assertEquals("画面没变化 · 第 1 轮跳过（没调接口）", AutoScanPolicy.skippedStatus(1))
        assertEquals("画面没变化 · 第 10 轮跳过（没调接口）", AutoScanPolicy.skippedStatus(10))
        val s = AutoScanPolicy.skippedStatus(3)
        assertTrue(s, s.contains("没调接口"))
    }

    // ================= 7) 端到端：整轮扫描流程 =================

    @Test fun endToEndScanLoopPausesAndResumes() {
        val threshold = AutoScanPolicy.clampRounds(3)
        var st = AutoScanPolicy.Still()
        var apiCalls = 0
        var paused = false
        var resumeCount = 0
        val frames = listOf("fp1", "fp1", "fp1", "fp1", "fp2", "fp2", "fp2", "fp2", "fp2")

        for (fp in frames) {
            if (paused) {
                // 用户点通知继续：轮数清零，指纹留着
                st = st.resetRounds(); paused = false; resumeCount++
                continue
            }
            val (next, skipped) = AutoScanPolicy.observe(st, fp)
            st = next
            if (!skipped) apiCalls++
            if (AutoScanPolicy.shouldPause(st.rounds, threshold)) paused = true
        }

        // fp1 首次 + fp2 首次 = 2 次真实调用，其余都被指纹挡掉
        assertEquals(2, apiCalls)
        // 第一次在第 3 轮静默时停；继续后 fp2 又数到 3 轮再停一次
        assertEquals(1, resumeCount)
        assertTrue(paused)
        assertEquals(3, st.rounds)
        assertEquals("fp2", st.fp)
    }

    @Test fun unknownFingerprintRoundsCostExtraApiCallsButNeverPause() {
        // 连续拿不到指纹：每轮都会真调接口（宁可多调不漏更新），但永远不会误判成「画面静止」
        val threshold = AutoScanPolicy.clampRounds(2)
        var st = AutoScanPolicy.Still()
        var apiCalls = 0
        repeat(50) {
            val (next, skipped) = AutoScanPolicy.observe(st, null)
            st = next
            if (!skipped) apiCalls++
            assertFalse(AutoScanPolicy.shouldPause(st.rounds, threshold))
        }
        assertEquals(50, apiCalls)
        assertEquals(0, st.rounds)
    }

    @Test fun repeatedIdenticalScreenshotNeverCallsApi() {
        // 用户忘了关自动扫描、题目停在一页：过 N 轮后自动暂停，接口一次都不再调
        val threshold = AutoScanPolicy.DEFAULT_STILL_ROUNDS
        val g = ramp(32, 64)
        val fp = AutoScanPolicy.lightFingerprint(g, 32, 64)
        var st = AutoScanPolicy.Still()
        var apiCalls = 0
        var pausedAt = -1
        for (i in 1..60) {
            val (next, skipped) = AutoScanPolicy.observe(st, fp)
            st = next
            if (!skipped) apiCalls++
            if (pausedAt < 0 && AutoScanPolicy.shouldPause(st.rounds, threshold)) pausedAt = i
        }
        assertEquals(1, apiCalls)                       // 只第一轮真调
        assertEquals(threshold + 1, pausedAt)          // 首轮只建立基线，第 threshold+1 轮达到阈值
        assertTrue(st.rounds >= threshold)   // 60 轮里除基线外全在跳过，计数早已过阈值
    }

    // ================= 8) 连续失败（v1.86：被动模式里的静默失效） =================

    @Test fun failCounterCountsUpAndNeverBlanks() {
        val a = AutoScanPolicy.failed(AutoScanPolicy.Fails(), "截屏失败：锁屏了")
        assertEquals(1, a.streak)
        assertEquals("截屏失败：锁屏了", a.last)
        val b = AutoScanPolicy.failed(a, "   ")
        assertEquals(2, b.streak)
        assertTrue("原因再空也得有句话，别亮一行空字", b.last.isNotBlank())
        assertEquals("首尾空白要抹掉（拼进状态行里才不占位）", "两边都trim", AutoScanPolicy.failed(AutoScanPolicy.Fails(), "  两边都trim  ").last)
    }

    @Test fun failLimitPausesOnThird() {
        assertFalse(AutoScanPolicy.shouldPauseOnFail(AutoScanPolicy.Fails(0)))
        assertFalse(AutoScanPolicy.shouldPauseOnFail(AutoScanPolicy.Fails(1)))
        assertFalse(AutoScanPolicy.shouldPauseOnFail(AutoScanPolicy.Fails(AutoScanPolicy.FAIL_LIMIT - 1)))
        assertTrue(AutoScanPolicy.shouldPauseOnFail(AutoScanPolicy.Fails(AutoScanPolicy.FAIL_LIMIT)))
        assertTrue(AutoScanPolicy.shouldPauseOnFail(AutoScanPolicy.Fails(AutoScanPolicy.FAIL_LIMIT + 5)))
        // 上限是个位数的轮数：太快 = 一次抖动就停（烦），太慢 = 白烧一整晚
        assertTrue(AutoScanPolicy.FAIL_LIMIT in 2..5)
    }

    @Test fun successResetsTheFailStreak() {
        val f = AutoScanPolicy.failed(AutoScanPolicy.failed(AutoScanPolicy.Fails(), "a"), "b")
        assertEquals(2, f.streak)
        assertEquals(AutoScanPolicy.Fails(), AutoScanPolicy.succeeded(f))
        val untouched = AutoScanPolicy.Fails(0, "旧原因")
        assertEquals("本来就没失败过 → 原样返回，不做无谓的对象 churn", untouched, AutoScanPolicy.succeeded(untouched))
    }

    /** 中间态：连着失败但中间成功过一次，不该 cumulate 到上限 */
    @Test fun interleavedSuccessKeepsItUnderLimit() {
        var f = AutoScanPolicy.Fails()
        repeat(30) {
            f = AutoScanPolicy.failed(f, "接口：超时")
            if (AutoScanPolicy.shouldPauseOnFail(f)) f = AutoScanPolicy.succeeded(f)   // 服务停完之后用户点了「继续」
            assertTrue("成功过就该重新数：${f.streak}", f.streak <= AutoScanPolicy.FAIL_LIMIT)
        }
    }

    @Test fun failStatusesSayWhichRoundAndWhatToDo() {
        val f = AutoScanPolicy.Fails(2, "还不能截屏（没授权）")
        val s = AutoScanPolicy.failStatus(f)
        assertTrue(s, s.contains("2 次"))
        assertTrue(s, s.contains("还不能截屏"))
        val p = AutoScanPolicy.failPausedStatus(AutoScanPolicy.Fails(3, "截屏失败：无障碍被系统收回"))
        assertTrue(p, p.contains("已停"))
        assertTrue(p, p.contains("3 次"))
        assertTrue(p, p.contains("无障碍被系统收回"))
        assertTrue(p, p.contains("点一下继续"))
    }

    /**
     * 「自己停的」必须走这两条独立文案，**不能**走 [AutoScanPolicy.status] ——
     * status() 按 P2 的规则让「用户把开关关了」优先，而自己停的时候 autoOn 已经是 false，
     * 那句话会被压成「自动扫描 · 关」，于是暂停原因永远显示不出来（v1.86 顺手修的第二个静默问题）。
     */
    @Test fun selfPausedLinesDoNotGetSwallowedByOff() {
        assertEquals("自动扫描 · 关", AutoScanPolicy.status(false, true, 5, 12_000))   // P2 钉住的优先级，别动
        val still = AutoScanPolicy.stillPausedStatus(5)
        assertTrue(still, still.contains("5 轮没变化"))
        assertTrue(still, still.contains("点一下继续"))
        assertFalse("自己停的不许写成「关」", still == "自动扫描 · 关")
        val fail = AutoScanPolicy.failPausedStatus(AutoScanPolicy.Fails(3, "接口挂了"))
        assertFalse("自己停的不许写成「关」", fail == "自动扫描 · 关")
        assertTrue(fail, fail.contains("接口挂了"))
    }

    /** 亮一行的时长要够读完一行、又不至于一直挡着屏幕 */
    @Test fun peekWindowIsReadableButNotSticky() {
        assertTrue("亮太久就成常驻了：${AutoScanPolicy.PEEK_MS}", AutoScanPolicy.PEEK_MS <= 6_000)
        assertTrue("太短读不完一行：${AutoScanPolicy.PEEK_MS}", AutoScanPolicy.PEEK_MS >= 2_000)
    }

    /** 端到端：坏掉的授权 → 三次就自己停；中间只要成功过一轮就不 cumulate */
    @Test fun endlessFailureLoopStopsAfterLimit() {
        var f = AutoScanPolicy.Fails()
        var rounds = 0
        while (!AutoScanPolicy.shouldPauseOnFail(f) && rounds < 100) { f = AutoScanPolicy.failed(f, "还不能截屏"); rounds++ }
        assertEquals(AutoScanPolicy.FAIL_LIMIT, f.streak)
        assertEquals(AutoScanPolicy.FAIL_LIMIT, rounds)
    }

    // ================= 9) 会话记页 / 下一轮截止（v1.91） =================
    //
    // 面板那行「第 N 页」只数**成功换页**：计时器 tick、失败 / 取消、同一画面、手动识别都不该把页数刷上去；
    // 新画面命中缓存和真调接口一样算一页（调用方拿到提交结果就调 recognized，不区分来源）。

    @Test fun pageCountsOnlySuccessfulChangedAutoRounds() {
        var p = AutoScanPolicy.Pages()
        // 手动双击 / 框选 / 图片：不是自动轮，成功也不算
        p = p.recognized(autoRound = false, fp = "p1", ok = true)
        assertEquals(0, p.pages)
        // 自动轮但截屏失败 / 请求取消 / 空答案：不算
        p = p.recognized(autoRound = true, fp = "p1", ok = false)
        assertEquals(0, p.pages)
        // 自动轮 + 新画面 + 成功：第 1 页
        p = p.recognized(autoRound = true, fp = "p1", ok = true)
        assertEquals(1, p.pages)
    }

    @Test fun sameScreenNeverCountsTwice() {
        var p = AutoScanPolicy.Pages()
        p = p.recognized(true, "p1", true)
        // 同屏每拍都成功（含指纹预检跳过的轮、计时 tick 后的重复提交）→ 页数不能一直涨
        repeat(20) { p = p.recognized(true, "p1", true) }
        assertEquals(1, p.pages)
        // 真翻页 → 第 2 页；翻回上一页相对上一张记过页的指纹仍是新画面 → 第 3 页
        p = p.recognized(true, "p2", true)
        assertEquals(2, p.pages)
        p = p.recognized(true, "p1", true)
        assertEquals(3, p.pages)
    }

    @Test fun unknownFingerprintNeverCounts() {
        // 拿不到画面指纹 = 无法确认是不是新画面：宁可少记一页，也不能凭空白指纹乱加
        var p = AutoScanPolicy.Pages()
        p = p.recognized(true, null, true)
        p = p.recognized(true, "", true)
        assertEquals(0, p.pages)
    }

    @Test fun failedNewScreenDoesNotBurnThePage() {
        // 新画面第一次失败不计数，也不占用 lastFp：同一画面重试成功时仍能补记这一页
        var p = AutoScanPolicy.Pages()
        p = p.recognized(true, "p1", false)
        assertEquals(0, p.pages)
        p = p.recognized(true, "p1", true)
        assertEquals("失败过不挡住下一次成功", 1, p.pages)
    }

    @Test fun resetStartsNewSessionAtZeroAndAllowsSameScreenAgain() {
        var p = AutoScanPolicy.Pages()
        p = p.recognized(true, "p1", true)
        p = p.recognized(true, "p2", true)
        assertEquals(2, p.pages)
        // 重新开一轮扫描（start）从 0 数；阅读暂停不走 reset，保留页数
        p = p.reset()
        assertEquals(0, p.pages)
        // 新会话从同一画面开始也算第 1 页
        p = p.recognized(true, "p2", true)
        assertEquals(1, p.pages)
    }

    @Test fun remainingNeverGoesNegativeAtDeadlineEdge() {
        // 截止时刻与当前时刻都用单调时钟；刚好到点 / 已过点 / 没有截止时刻都按 0（面板显示「马上」）
        assertEquals(0L, AutoScanPolicy.remaining(1_000, 1_000))
        assertEquals(0L, AutoScanPolicy.remaining(1_000, 1_001))
        assertEquals(0L, AutoScanPolicy.remaining(1_000, 99_999))
        assertEquals(0L, AutoScanPolicy.remaining(0, 5_000))
        assertEquals(1L, AutoScanPolicy.remaining(1_001, 1_000))
    }

    @Test fun deadlineForNonPositiveIntervalIsNow() {
        // 间隔配错（0 / 负数）不能排出「过去」或无限远的截止时刻
        assertEquals(5_000L, AutoScanPolicy.deadline(5_000, 0))
        assertEquals(5_000L, AutoScanPolicy.deadline(5_000, -2_000))
        assertEquals(8_000L, AutoScanPolicy.deadline(5_000, 3_000))
    }
}