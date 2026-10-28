package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专注守护（第 9 条）的判据测试。
 *
 * 分三块：
 *  1. [FocusGuard.classify]：特征 -> 三档。**纯函数**，直接构造 [FocusGuard.Features]，不碰图；
 *  2. [LayoutSense]：合成灰度图 -> 特征。用「纸上写了 12 行字」「满屏棋盘格」「纯色」三张假图，
 *     验证「行密度 / 贴左 / 实心色块」这几个量真的是照设计在算 —— 阈值可以调，但这些量的方向不能错；
 *  3. 温和提醒的状态机（[FocusGuard.observe] / [due] / [nudged]）与文案。
 *
 * 真图（06-实测录屏-20261017 里的 9 张截图）的标定结果写在 [LayoutSense] 的文件注释里，
 * 单测不依赖那些 png —— 测试要能离线、在任何机器上跑。
 */
class FocusGuardTest {

    // ---------------- 一、特征 -> 三档 ----------------

    /**
     * 一张「像书页」的特征：十几行、行够宽、贴左、左边界齐、没有实心大色块。
     * 每个用例都以它为基准改一两个字段，这样「改了哪个量导致判据翻转」一眼看得出来。
     */
    private fun page(
        lines: Int = 12,
        rounds: Double = 0.7,
        align: Double = 0.9,
        left: Double = 0.9,
        center: Double = 0.0,
        solid: Double = 0.05,
        flat: Double = 0.1,
        edge: Double = 0.02,
        ink: Double = 0.1,
    ) = FocusGuard.Features(
        lineCount = lines,
        lineRounds = rounds,
        leftAlign = align,
        leftRatio = left,
        centerRatio = center,
        solidRatio = solid,
        flatRatio = flat,
        edgeRatio = edge,
        inkRatio = ink,
        topBand = 0.02,
        bottomBand = 0.03,
        paper = 250,
        w = 240,
        h = 400,
    )

    /** 一页正文：判成在学习，且分数到线 */
    @Test fun studyPageIsStudy() {
        val c = FocusGuard.classify(page())
        assertEquals(FocusGuard.Verdict.STUDY, c.verdict)
        assertEquals(true, c.learns)
        assertTrue("分数应该到线：" + c.score, c.score >= FocusGuard.STUDY_AT)
    }

    /**
     * 聊天 / 列表页：行数不少，但**不贴左**、左边界忽左忽右、还有一块头像色块。
     * 这是真实截图里最容易误判成「在学习」的一类，必须挡住。
     */
    @Test fun chatPageIsNotStudy() {
        val c = FocusGuard.classify(page(lines = 14, rounds = 0.3, align = 0.0, left = 0.3, center = 0.9, solid = 0.0))
        // 这张合成图只保证「不误判成 STUDY」，不保证能判成 NOT_STUDY：
        // 真实的聊天截图**尚未用于标定**（标定集的非学习样本是聊天/文件列表/确认弹窗的其它几张），
        // 而判据本身的态度是「不确定就 UNSURE」。所以这里断言的是「不冤枉」，不是「精确识别」。
        assertFalse("聊天页绝不能判成学习：" + c.score, c.verdict == FocusGuard.Verdict.STUDY)
        assertEquals("不在学习 = false，判不出来 = null；两者都不许是 true", false, c.learns ?: false)
    }

    /** 视频 / 游戏：几乎没有文字行，画面是一块块实心色 —— 明确判成「不像学习」 */
    @Test fun videoIsNotStudy() {
        val c = FocusGuard.classify(page(lines = 2, rounds = 0.0, align = 0.0, left = 0.0, solid = 0.6, flat = 0.0, edge = 0.1))
        assertEquals(FocusGuard.Verdict.NOT_STUDY, c.verdict)
        assertEquals(false, c.learns)
    }

    /**
     * 介于两者之间（行数够、但不贴左也不齐）→ **必须 UNSURE**。
     * 这一条是这个功能的态度：不确定就闭嘴，宁可少记一条学习，也不冤枉人在玩手机。
     */
    @Test fun ambiguousIsUnsure() {
        // 5 行(+1) + 行宽够宽(+1) = 2 分，落在「判不出来」区间
        val c = FocusGuard.classify(page(lines = 5, rounds = 0.6, align = 0.2, left = 0.0, center = 0.0, solid = 0.0))
        assertTrue("夹具分数应落在 UNSURE 区间（2~3），实际 " + c.score, c.score > FocusGuard.NOT_STUDY_BELOW && c.score < FocusGuard.STUDY_AT)
        assertEquals(FocusGuard.Verdict.UNSURE, c.verdict)
        assertNull("UNSURE 既不算学也不算离开", c.learns)
    }

    /** 解码失败（Features 为空）→ UNSURE，绝不抛 */
    @Test fun nullFeaturesIsUnsure() {
        val c = FocusGuard.classify(null)
        assertEquals(FocusGuard.Verdict.UNSURE, c.verdict)
        assertNull(c.learns)
    }

    /** 尺寸不合法（0 宽）→ UNSURE */
    @Test fun badSizeIsUnsure() {
        val c = FocusGuard.classify(page().copy(w = 0))
        assertEquals(FocusGuard.Verdict.UNSURE, c.verdict)
    }

    /**
     * classifyScreen 是对外那个「传 base64 进来」的入口，**任何输入都不许抛**。
     * 空串 / 垃圾串都当解码失败处理。
     */
    @Test fun classifyScreenNeverThrows() {
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.classifyScreen(""))
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.classifyScreen("不是 base64 的垃圾"))
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.classifyScreen("AAAAAAAA"))
    }

    /** Verdict 的 code 要能来回：写进文件的是 code，读回来必须还是同一档；认不出的兜到 UNSURE */
    @Test fun verdictCodeRoundTrip() {
        for (v in FocusGuard.Verdict.entries) assertEquals(v, FocusGuard.Verdict.of(v.code))
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.Verdict.of("garbage"))
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.Verdict.of(null))
    }

    // ---------------- 二、合成图 -> 特征 ----------------

    /**
     * 画一张白底黑字的「纸」：14 行，行高 h/18，左边距 12（= 图宽的 5%，落在 [LayoutSense.LEFT_TOL] 以内）。
     *
     * **必须按「字」来画，不能整行涂黑。** 真实的一行字是被字间距切开的：
     * 每个字 6px 宽、字间空 2px。整行涂黑的话，最长连续墨段会占满 77% 的图宽，
     * 直接触发 [LayoutSense.SOLID_RUN] 的「大色块」判据 —— 一张课本页会被判成视频。
     * 这个夹具此前就踩过这个坑（solidRatio 算出来 0.38），所以这里逐字画。
     */
    private fun paperImage(w: Int = 240, h: Int = 400): IntArray {
        val g = IntArray(w * h) { 255 }
        val lineH = h / 18
        var y = 14
        while (y < h - 24) {
            for (yy in y until (y + lineH).coerceAtMost(h)) {
                var x = 12
                // 一行字：字宽 6px + 字缝 2px，铺到 82% 宽为止
                while (x < (w * 0.82).toInt()) {
                    for (k in 0 until 6) {
                        val xx = x + k
                        if (xx < w) g[yy * w + xx] = 30
                    }
                    x += 8
                }
            }
            y += lineH * 2
        }
        return g
    }

    /** 满屏高频棋盘格：模拟视频 / 游戏的画面 */
    private fun checkerImage(w: Int = 240, h: Int = 400): IntArray {
        val g = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            g[y * w + x] = if (((x / 3) + (y / 3)) % 2 == 0) 20 else 210
        }
        return g
    }

    /** 纯色底：整屏一个颜色，一条文字行都切不出来 */
    private fun solidImage(w: Int = 240, h: Int = 400): IntArray = IntArray(w * h) { 240 }

    /** 纸页：切得出十几条文字行，而且几乎全部贴左 */
    @Test fun paperHasManyLeftAlignedLines() {
        val f = LayoutSense.sense(paperImage(), 240, 400)
        assertTrue("行数应该 >= 8，实际 " + f.lineCount, f.lineCount >= 8)
        assertTrue("贴左行占比应该够高，实际 " + f.leftRatio, f.leftRatio >= 0.5)
        assertTrue("实心色块应该很小，实际 " + f.solidRatio, f.solidRatio < 0.3)
        assertEquals(FocusGuard.Verdict.STUDY, FocusGuard.classify(f).verdict)
    }

    /** 高频画面：行数极少（整片连成一块，且是实心），边缘占比很高 -> 不会判成学习 */
    @Test fun checkerIsNotStudy() {
        val f = LayoutSense.sense(checkerImage(), 240, 400)
        assertTrue("边缘占比应该很高，实际 " + f.edgeRatio, f.edgeRatio > 0.25)
        assertFalse(FocusGuard.classify(f).verdict == FocusGuard.Verdict.STUDY)
    }

    /** 纯色底：一条文字行都没有 -> 不会被当成学习 */
    @Test fun solidHasNoLines() {
        val f = LayoutSense.sense(solidImage(), 240, 400)
        assertEquals(0, f.lineCount)
        assertTrue("整屏纯色，纯色行占比应该接近 1，实际 " + f.flatRatio, f.flatRatio > 0.9)
        assertFalse(FocusGuard.classify(f).verdict == FocusGuard.Verdict.STUDY)
    }

    /** 深色模式：纸色暗、字亮 —— 方向要跟着纸色翻转，否则整屏都会被当成「墨」 */
    @Test fun darkModeInvertsInk() {
        val w = 240
        val h = 400
        val g = IntArray(w * h) { 20 }   // 深色底
        val lineH = h / 18
        var y = 14
        while (y < h - 24) {
            for (yy in y until (y + lineH).coerceAtMost(h)) for (x in 12 until (w * 0.82).toInt()) g[yy * w + x] = 235
            y += lineH * 2
        }
        val f = LayoutSense.sense(g, w, h)
        assertTrue("纸色应该是深的，实际 " + f.paper, f.paper < 96)
        assertTrue("深色模式下也要切得出文字行，实际 " + f.lineCount, f.lineCount >= 8)
        assertTrue("墨色占比应该是有字的那部分，实际 " + f.inkRatio, f.inkRatio in 0.02..0.6)
    }

    /** 尺寸不合法给空特征，不炸 */
    @Test fun badInputGivesEmptyFeatures() {
        val f = LayoutSense.sense(IntArray(0), 0, 0)
        assertEquals(0, f.lineCount)
        assertEquals(FocusGuard.Verdict.UNSURE, FocusGuard.classify(f).verdict)
    }

    // ---------------- 三、离开与提醒 ----------------

    private val cfg = FocusGuard.Cfg()

    /**
     * 相对基准时刻的分钟数 -> 毫秒。
     *
     * 基准特意**不从 0 开始**：[FocusGuard.Leave.since] 用 0 当「当前没在离开」的哨兵值
     * （见 Leave.active）。如果基准是 0，那 at(0.0) 造出来的「离开起点」正好等于哨兵值，
     * 状态机会认为这段离开根本没开始 —— 这是测试夹具的坑，不是实现的坑。
     */
    private fun at(min: Double): Long = FocusGuard.TEN_MIN + (min * FocusGuard.ONE_MIN).toLong()

    /** 学习采样一进来，之前攒的离开就断掉 */
    @Test fun studyResetsLeave() {
        var l = FocusGuard.Leave()
        l = FocusGuard.observe(l, FocusGuard.Sample(at(0.0), FocusGuard.Verdict.NOT_STUDY))
        assertTrue(l.active)
        l = FocusGuard.observe(l, FocusGuard.Sample(at(1.0), FocusGuard.Verdict.STUDY))
        assertFalse(l.active)
        assertEquals(0, l.rounds)
    }

    /**
     * UNSURE 既不推进也不清零：解码失败不该被记成「你在玩」。
     * 这是设计的核心态度，改之前先想清楚为什么当初要这样。
     */
    @Test fun unsureNeitherAdvancesNorClears() {
        var l = FocusGuard.Leave()
        l = FocusGuard.observe(l, FocusGuard.Sample(at(0.0), FocusGuard.Verdict.NOT_STUDY))
        val before = l
        l = FocusGuard.observe(l, FocusGuard.Sample(at(1.0), FocusGuard.Verdict.UNSURE))
        assertEquals("轮数不该被 UNSURE 推进", before.rounds, l.rounds)
        assertEquals("离开起点不该被 UNSURE 改掉", before.since, l.since)
        assertTrue("离开状态要保留", l.active)
    }

    /** 只有连续 N 轮「不像学习」且跨度够久才提醒：轮数不够不提醒 */
    @Test fun notDueWithoutEnoughRounds() {
        var l = FocusGuard.Leave()
        repeat(2) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 3.0), FocusGuard.Verdict.NOT_STUDY)) }
        assertFalse("轮数不够不该提醒", FocusGuard.due(l, at(10.0), cfg))
    }

    /** 轮数够了但跨度不够也不提醒：切出去回一条消息不算离开 */
    @Test fun notDueWithoutEnoughTime() {
        var l = FocusGuard.Leave()
        repeat(3) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 1.0), FocusGuard.Verdict.NOT_STUDY)) }
        assertFalse("才 2 分钟，不该提醒", FocusGuard.due(l, at(2.0), cfg))
    }

    /** 轮数和跨度都够 -> 该提醒 */
    @Test fun dueWhenLongAndSteady() {
        var l = FocusGuard.Leave()
        repeat(4) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        assertTrue(FocusGuard.due(l, at(8.0), cfg))
    }

    /** 提醒后 10 分钟内不重复 —— 每 12 秒一轮扫描不能变成每分钟五条通知 */
    @Test fun cooldownBlocksRepeat() {
        var l = FocusGuard.Leave()
        repeat(4) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        assertTrue(FocusGuard.due(l, at(8.0), cfg))
        l = FocusGuard.nudged(l, at(8.0))
        assertFalse("冷却期内不该再提醒", FocusGuard.due(l, at(12.0), cfg))
        assertTrue("冷却完了还该提醒", FocusGuard.due(l, at(19.0), cfg))
    }

    /** 分档：第一次最轻，第三次封顶 */
    @Test fun tierEscalatesAndCaps() {
        var l = FocusGuard.Leave()
        assertEquals(1, FocusGuard.tier(l))
        l = FocusGuard.nudged(l, at(0.0))
        assertEquals(2, FocusGuard.tier(l))
        l = FocusGuard.nudged(l, at(20.0))
        assertEquals(3, FocusGuard.tier(l))
        l = FocusGuard.nudged(l, at(40.0))
        assertEquals("封顶在第 3 档", FocusGuard.MAX_TIER, FocusGuard.tier(l))
    }

    /** 文案里不许出现数落人的词 —— 这是给学习者用的工具，不是家长监控 */
    @Test fun nudgeTextNeverBlames() {
        val banned = listOf("不许", "必须", "又在", "监控", "批评", "偷懒", "摸鱼")
        var l = FocusGuard.Leave(since = at(0.0), rounds = 5)
        for (i in 0 until 4) {
            val text = FocusGuard.nudgeText(l, at(30.0), cfg) + FocusGuard.nudgeTitle(l)
            for (b in banned) assertFalse("文案里不该出现「" + b + "」：" + text, text.contains(b))
            l = FocusGuard.nudged(l, at(30.0))
        }
    }

    /** 文案要带时长（有真数据时）；不到一分钟不说「0 分钟」 */
    @Test fun nudgeTextUsesDuration() {
        val l = FocusGuard.Leave(since = at(0.0), rounds = 5)
        assertTrue(FocusGuard.nudgeText(l, at(8.0), cfg).contains("8 分钟"))
        assertTrue(FocusGuard.nudgeText(l, at(45.0), cfg).contains("45 分钟"))
        assertFalse(FocusGuard.nudgeText(l, at(0.3), cfg).contains("0 分钟"))
    }

    /** 时长封顶：手机在兜里放两小时，不会说成「离开 120 分钟」 */
    @Test fun nudgeTextCapsSpan() {
        val l = FocusGuard.Leave(since = at(0.0), rounds = 5)
        val text = FocusGuard.nudgeText(l, at(600.0), cfg)
        assertTrue(text, text.contains(FocusGuard.fmtDur(FocusGuard.MAX_SPAN_MS)))
    }

    /**
     * 时长文案：**向下截断**，不显示秒。
     *
     * 这里的每一条断言都在守同一条规则：宁可少报，不能多报。
     * 50 秒就是「不到 1 分钟」——四舍五入成 1 分钟会让用户看到比自己实际学习更长的数字，
     * 那份报告立刻就不值得信了。所以 30 秒和 50 秒的期望都是「不到 1 分钟」。
     */
    @Test fun fmtDur() {
        assertEquals("59 秒不满 1 分钟", "不到 1 分钟", FocusGuard.fmtDur(59_999L))
        assertEquals("69 秒已经满 1 分钟，向下取整报 1 分钟", "1 分钟", FocusGuard.fmtDur(69_000L))
        assertEquals("不到 1 分钟", FocusGuard.fmtDur(30_000L))
        assertEquals("不到 1 分钟", FocusGuard.fmtDur(50_000L))
        assertEquals("刚好 1 分钟", "1 分钟", FocusGuard.fmtDur(60_000L))
        assertEquals("1 分 59 秒只报 1 分钟", "1 分钟", FocusGuard.fmtDur(119_000L))
        assertEquals("59 分钟", FocusGuard.fmtDur(59 * 60_000L))
        assertEquals("1 小时", FocusGuard.fmtDur(60 * 60_000L))
        assertEquals("1 小时 30 分", FocusGuard.fmtDur(90 * 60_000L))
        assertEquals("负数不乱来", "不到 1 分钟", FocusGuard.fmtDur(-5_000L))
    }

    /** Cfg 会被夹到合理区间：用户把阈值填成 0 或 999 都不该让守护失效或变成骚扰 */
    @Test fun cfgIsClamped() {
        val c = FocusGuard.Cfg(leaveMs = 1L, leaveRounds = 0, cooldownMs = 0L, roundMs = 0L).clamped()
        assertEquals(FocusGuard.ONE_MIN, c.leaveMs)
        assertEquals(2, c.leaveRounds)
        assertEquals(FocusGuard.ONE_MIN, c.cooldownMs)
        assertTrue(c.roundMs >= 1_000L)
    }

    /** 报告去重：UNSURE 不记，同一屏（指纹相同）不重复记 */
    @Test fun keepForReportRules() {
        assertFalse(FocusGuard.keepForReport(FocusGuard.Sample(0L, FocusGuard.Verdict.UNSURE, "a"), null))
        assertTrue(FocusGuard.keepForReport(FocusGuard.Sample(0L, FocusGuard.Verdict.STUDY, "a"), null))
        assertFalse(FocusGuard.keepForReport(FocusGuard.Sample(0L, FocusGuard.Verdict.STUDY, "a"), "a"))
        assertTrue(FocusGuard.keepForReport(FocusGuard.Sample(0L, FocusGuard.Verdict.STUDY, "b"), "a"))
    }

    /** 空离开不该被判 due（没有 since 就没有跨度） */
    @Test fun emptyLeaveIsNeverDue() {
        assertFalse(FocusGuard.due(FocusGuard.Leave(), at(100.0), cfg))
        assertEquals(0L, FocusGuard.Leave().span(at(100.0)))
    }

    /** brief() 是排查用的，别让它崩（它是唯一会做字符串格式化的地方） */
    @Test fun briefDoesNotCrash() {
        assertNotNull(LayoutSense.sense(paperImage(), 240, 400).brief())
    }

    // ---------------- 四、息屏 / 亮屏 ----------------

    /**
     * 熄屏要断掉这一段离开：合上手机去睡觉，不该早上看到一个「离开 8 小时」。
     */
    @Test fun screenOffEndsCurrentLeave() {
        var l = FocusGuard.Leave()
        repeat(5) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        assertTrue(l.active)
        val off = FocusGuard.onScreenOff(l)
        assertFalse("熄屏后不该还算在离开", off.active)
        assertEquals(0, off.rounds)
    }

    /**
     * 但「今天提醒过几次 / 上次几点提醒」必须活过熄屏 ——
     * 否则用户每次锁屏解锁就重置冷却，12 秒一轮的扫描能立刻再弹一条，那才是真骚扰。
     */
    @Test fun screenOffKeepsTodayNudgeCount() {
        var l = FocusGuard.Leave()
        repeat(5) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        l = FocusGuard.nudged(l, at(10.0))
        val off = FocusGuard.onScreenOff(l)
        assertEquals("提醒次数要保留", 1, off.nudges)
        assertEquals("上次提醒时刻要保留（冷却靠它）", at(10.0), off.lastAt)
    }

    /** 亮屏是干净的起点：不带熄屏前的残留轮数，否则「解锁看一眼桌面」会被算进离开 */
    @Test fun screenOnStartsFresh() {
        var l = FocusGuard.Leave()
        repeat(5) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        l = FocusGuard.nudged(l, at(10.0))
        val on = FocusGuard.onScreenOn(l)
        assertFalse(on.active)
        assertEquals(0, on.rounds)
        assertEquals("提醒次数跨过亮屏保留", 1, on.nudges)
        assertEquals(at(10.0), on.lastAt)
    }

    /** 熄屏 -> 亮屏 -> 只有一轮「不像学习」，不该立刻提醒（轮数不够） */
    @Test fun screenCycleNeedsFreshRounds() {
        var l = FocusGuard.Leave()
        repeat(5) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(i * 2.0), FocusGuard.Verdict.NOT_STUDY)) }
        l = FocusGuard.onScreenOff(l)
        l = FocusGuard.onScreenOn(l)
        l = FocusGuard.observe(l, FocusGuard.Sample(at(30.0), FocusGuard.Verdict.NOT_STUDY))
        assertEquals(1, l.rounds)
        assertFalse("刚解锁不该马上提醒", FocusGuard.due(l, at(31.0), cfg))
    }

    /** 熄屏后再亮屏、冷却已经过了，重新攒够轮数仍然会提醒（守护没被永久关掉） */
    @Test fun guardStillWorksAfterScreenCycle() {
        var l = FocusGuard.onScreenOn(FocusGuard.onScreenOff(FocusGuard.Leave()))
        repeat(4) { i -> l = FocusGuard.observe(l, FocusGuard.Sample(at(100.0 + i * 2), FocusGuard.Verdict.NOT_STUDY)) }
        assertTrue(FocusGuard.due(l, at(110.0), cfg))
    }
}
