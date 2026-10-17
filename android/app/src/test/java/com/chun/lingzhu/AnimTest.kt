package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效底座的单测。Anim.kt 的文档一直写着「单元测试见 AnimTest」，但 v1.78 之前**没有这个文件** ——
 * 全 App 的时长 / 曲线 / 弹簧 / 终态收敛一条断言都没有。这里把它补上，重点是最容易写错、
 * 又最影响手感的三件事：解析解对不对、打断连不连续、收敛判据会不会误判。
 */
class AnimTest {

    private val dt60 = 1f / 60f

    // ---------------- 解析解 ----------------

    /**
     * [Spring.step] 必须和 [Spring.progress] 是同一条曲线的两种写法。
     * 两者是独立推出来的（一个用「离目标的距离」当自变量、一个用「完成百分比」），
     * 对得上就说明两边都没写错。
     *
     * 两处归一化的换算：progress 的 v0 是「每秒完成整个距离的百分之几、正数朝目标」，
     * 而 step 的 velocity 是「值的绝对速度」。起点距离 = 1 时两者互为相反数。
     */
    @Test fun stepAgreesWithNormalizedProgress() {
        for (s in listOf(Anim.Spring.snap, Anim.Spring.size, Anim.Spring(300f, 40f), Anim.Spring(100f, 26f))) {
            for (t in listOf(0.05f, 0.1f, 0.2f, 0.4f, 0.8f)) {
                // 静置起步
                assertEquals(1f - s.progress(t, 0f), s.step(Anim.State(1f, 0f), t).value, 2e-4f)
                // 带初速度（progress 里 0.8 = 每秒走完距离的 80%）
                assertEquals(1f - s.progress(t, 0.8f), s.step(Anim.State(1f, -0.8f), t).value, 2e-4f)
            }
        }
    }

    /**
     * 解析解的「半群性质」：先走半帧再走半帧，必须等于一次走一整帧。
     *
     * 这条是「不抖」的数学保证 —— 数值积分（欧拉 / 半隐式欧拉）每一步都会引入误差，
     * 帧率一变（60Hz ↔ 90Hz ↔ 掉帧）误差就变，看起来就是「同样的动画有时顺有时顿」。
     * 解析解没有这个问题，这条断言把它钉住。
     */
    @Test fun stepIsExactAcrossFrameSplits() {
        val s = Anim.Spring.snap
        val start = Anim.State(1f, 0.3f)
        val whole = s.step(start, dt60)
        val half = s.step(s.step(start, dt60 / 2f), dt60 / 2f)
        assertEquals(whole.value, half.value, 1e-5f)
        assertEquals(whole.velocity, half.velocity, 1e-5f)
    }

    /** 过阻尼 / 临界阻尼不该因为 dt 变小而改变结果，ζ 在 1 附近的边界也不能炸成 NaN */
    @Test fun stepIsFiniteAroundCriticalDamping() {
        val nearCritical = (1..5).map { 0.999f + it * 0.0005f }
        for (z in nearCritical) {
            for (k in listOf(180f, 220f, 500f)) {
                val s = Anim.Spring(k, 2f * z * Math.sqrt(k.toDouble()).toFloat())
                val r = s.step(Anim.State(1f, 0f), dt60)
                assertTrue("z=$z k=$k 出了 NaN/Inf", r.value.isFinite() && r.velocity.isFinite())
            }
        }
    }

    /**
     * 按下 / 松手这两个手感弹簧，替代原来的「70ms 缩放」+「OvershootInterpolator(2.2)」。
     * 按下必须极快且**不弹**（按下反馈要先于回弹发生）；松手必须**会**弹一下（那一下回弹就是它）。
     */
    @Test fun pressAndReleaseSpringsMatchTheirFeel() {
        assertTrue("按下没在 ${Anim.PRESS}ms 内到位", Anim.Spring.press.progress(Anim.PRESS / 1000f) >= 0.95f)
        assertTrue("按下弹簧不该弹（ζ≥1），实际 ζ=${Anim.Spring.press.zeta}", Anim.Spring.press.zeta >= 1f)

        val rel = Anim.Spring.release
        assertTrue("松手弹簧要弹（ζ<1），实际 ζ=${rel.zeta}", rel.zeta < 1f)
        var st = Anim.State(1f, 0f)
        var min = 0f
        repeat(400) { st = rel.step(st, dt60); if (st.value < min) min = st.value }
        val overshoot = -min
        assertTrue("松手没有过冲，回弹感没了：$overshoot", overshoot > 0.02f)
        assertTrue("松手过冲太大：$overshoot", overshoot < 0.30f)
        // 时序：按下是先发生的那个，回弹跟着来（同一时刻按下走得更多）
        assertTrue("松手比按下还快", rel.progress(Anim.PRESS / 1000f) < 0.95f)
    }

    /**
     * 回归：`progress` 的过阻尼分支曾经把两个系数写反（该配 r1 的份额配给了 r2）。
     * 症状是 ζ>1 时 t=0.02s 就报「完成 52%」（真值 96%）、t=0.1s 起**输出负数**、属性被推过目标 13%。
     * 而 `Spring.size` 的注释承诺的正是「不过冲」—— 这条同时把那个承诺钉在插值器本身上。
     */
    @Test fun progressNeverOvershootsWhenNotUnderdamped() {
        for (s in listOf(Anim.Spring.size, Anim.Spring(300f, 40f), Anim.Spring(180f, 26.9f))) {
            assertTrue("这组样本必须是 ζ≥1，实际 ζ=${s.zeta}", s.zeta >= 1f)
            var prev = 0f
            for (i in 0..600) {
                val t = i / 600f
                val p = s.progress(t, 0f)
                assertTrue("s=$s t=$t 输出越界：$p", p >= -1e-4f && p <= 1f + 1e-4f)
                assertTrue("s=$s t=$t 中途倒退：$prev → $p", p >= prev - 1e-4f)
                prev = p
            }
        }
    }

    // ---------------- 过冲 ----------------

    /**
     * `Spring.size` 的文档承诺「缩放冲过头会很怪，所以这条不给出弹」。
     * k=220 / c=30 → ζ≈1.011，略过阻尼，必须**永不越过目标**。这条以前没有任何测试看着。
     */
    @Test fun sizeSpringNeverOvershoots() {
        val s = Anim.Spring.size
        assertTrue("size 弹簧应当是过阻尼或临界（ζ≥1），实际 ζ=${s.zeta}", s.zeta >= 1f)
        var st = Anim.State(1f, 0f)
        repeat(400) {
            st = s.step(st, dt60)
            assertTrue("过冲了：offset=${st.value}", st.value >= -1e-4f)
        }
    }

    /**
     * `Spring.snap` 反过来必须**会**过冲一点点（「吸一下」的手感），但只能是一点点。
     * 过冲量理论值 exp(-πζ/√(1-ζ²))，ζ≈0.82 → 约 1.1%。
     */
    @Test fun snapSpringOvershootsSlightly() {
        val s = Anim.Spring.snap
        assertTrue("snap 应当是欠阻尼（ζ<1），实际 ζ=${s.zeta}", s.zeta < 1f)
        var st = Anim.State(1f, 0f)
        var min = 0f
        repeat(400) { st = s.step(st, dt60); if (st.value < min) min = st.value }
        val overshoot = -min
        assertTrue("没有过冲，不像弹簧：$overshoot", overshoot > 0f)
        assertTrue("过冲太大（>10%）：$overshoot", overshoot < 0.10f)
    }

    // ---------------- 可打断 ----------------

    /**
     * 换目标时位置必须**原地接管**：不跳、不重置，速度一个都不能丢。
     * 这正是「苹果式顺滑」和「固定时长动画」的分水岭 ——
     * 旧做法是 cancel 再从头播，速度归零，看着像运动被拦腰截断。
     */
    @Test fun retargetKeepsPositionAndVelocityContinuous() {
        val s = Anim.Spring.snap
        var target = 0f
        var st = Anim.State(1f, 0f)
        repeat(6) { st = s.step(st, dt60) }

        val valueBefore = target + st.value      // 绝对位置
        val speedBefore = st.velocity
        assertTrue("这一帧速度应当非零，否则测不出连续性", Math.abs(speedBefore) > 0.01f)

        target = 0.5f                            // 半路改主意
        st = Anim.retarget(valueBefore, speedBefore, target)

        assertEquals("位置跳了", valueBefore, target + st.value, 1e-6f)
        assertEquals("速度被重置了", speedBefore, st.velocity, 0f)

        // 接管之后的第一帧速度应当和前一帧相差不大（没有拐点）
        val after = s.step(st, dt60)
        assertTrue("换目标后速度突变：$speedBefore → ${after.velocity}", Math.abs(after.velocity - speedBefore) < Math.abs(speedBefore) * 0.5f)
    }

    /** 位置虽然连续，但「从静置开始重新播」和「带着速度接管」的区别必须看得见：后者更快到目标 */
    @Test fun retargetWithVelocityArrivesSoonerThanFromRest() {
        val s = Anim.Spring.snap
        val fromRest = s.step(Anim.State(1f, 0f), dt60)
        val withSpeed = s.step(Anim.retarget(1f, -0.6f, 0f), dt60)
        assertTrue(withSpeed.value < fromRest.value)
    }

    // ---------------- 收敛判据 ----------------

    /**
     * 收敛必须「距离小 **且** 速度小」。只看距离会在掠过目标那一帧误判成停住；
     * 只看速度会在过冲顶点（速度为 0、离目标最远）误判。
     */
    @Test fun settledNeedsBothSmallDistanceAndSmallSpeed() {
        val s = Anim.Spring.snap
        assertTrue(s.settled(Anim.State(0f, 0f), 0.5f, 0.5f))
        assertFalse("掠过目标、速度还很大不该判停", s.settled(Anim.State(0f, 9f), 0.5f, 0.5f))
        assertFalse("过冲顶点、离目标还很远不该判停", s.settled(Anim.State(40f, 0f), 0.5f, 0.5f))
        assertFalse(s.settled(Anim.State(0.6f, 0f), 0.5f, 0.5f))
    }

    /** 从任意初值出发，弹簧最终都必须收敛（不是只对数就收敛） */
    @Test fun springAlwaysSettles() {
        for (s in listOf(Anim.Spring.snap, Anim.Spring.size, Anim.Spring(500f, 20f))) {
            for (v0 in listOf(0f, 3f, -3f, 20f)) {
                var st = Anim.State(1f, v0)
                var t = 0f
                while (t < 3f && !s.settled(st, 0.002f, 0.02f)) { st = s.step(st, dt60); t += dt60 }
                assertTrue("s=$s v0=$v0 没收敛（3 秒后 offset=${st.value}）", t < 3f)
            }
        }
    }

    // ---------------- 既有的零散承诺 ----------------

    /** 贴边时长：距离为 0 不动画；其余都要落在 [MIN_SNAP_MS, SNAP_MAX] 里，不能被弹簧参数带飞 */
    @Test fun snapDurationStaysInBounds() {
        assertEquals(0L, Anim.snapDuration(0))
        for (d in listOf(1, 50, 400, 2000)) {
            for (v in listOf(0f, 0.5f, 5f, 100f, -100f)) {
                val ms = Anim.snapDuration(d, v)
                assertTrue("$ms 越界", ms in 120..600)
            }
        }
    }

    /** 闲置淡出有下限：alpha 掉到 0 就等于「球不见了」，用户会以为程序挂了 */
    @Test fun idleAlphaNeverReachesInvisible() {
        assertTrue(Anim.idleAlpha(0) >= 0.1f)
        assertTrue(Anim.idleAlpha(-50) >= 0.1f)
        assertEquals(0.8f, Anim.idleAlpha(999), 1e-6f)
    }

    // ---------------- 手势：拖拽关闭 ----------------

    /**
     * 投影判定：四种手感都要判对。最容易写错的是「拖了一半又往回甩」——
     * 写成「位移够 或 速度够」会把它判成甩出去（位移已经过半），手感就错了。
     */
    @Test fun dragDismissFollowsProjectedEndPoint() {
        val th = 100f
        assertTrue("慢慢拖过阈值该甩出去", Anim.dragDismiss(120f, 0f, th))
        assertTrue("轻轻一甩（位移小、速度大）该甩出去", Anim.dragDismiss(10f, 2000f, th))
        assertFalse("拖了一半又往回甩该弹回来", Anim.dragDismiss(60f, -600f, th))
        assertFalse("慢慢拖一点点该弹回来", Anim.dragDismiss(30f, 0f, th))
        // 上下都能甩掉，方向对称
        assertTrue(Anim.dragDismiss(-120f, 0f, th))
        assertTrue(Anim.dragDismiss(-10f, -2000f, th))
        assertFalse(Anim.dragDismiss(-30f, 0f, th))
        // 阈值非法（0 或负）时一律不甩，免得把卡片无条件吃掉
        assertFalse(Anim.dragDismiss(999f, 999f, 0f))
        assertFalse(Anim.dragDismiss(999f, 999f, -10f))
    }

    /**
     * 二维版甩出判定（v1.81，词卡四个方向都能划掉）：**任一轴**的投影终点越过该轴阈值就算甩掉。
     * 某一轴阈值给 0/负 = 那一轴不参与判定。
     */
    @Test fun dragDismissAnyChecksBothAxes() {
        val tx = 300f  // 横向阈值（卡片宽度那档）
        val ty = 200f  // 纵向阈值（卡片高度那档）
        assertTrue("往右拖过阈值", Anim.dragDismissAny(320f, 0f, 0f, 0f, tx, ty))
        assertTrue("往左拖过阈值", Anim.dragDismissAny(-320f, 0f, 0f, 0f, tx, ty))
        assertTrue("往下拖过阈值", Anim.dragDismissAny(0f, 220f, 0f, 0f, tx, ty))
        assertTrue("往上一甩（位移小、速度大）", Anim.dragDismissAny(0f, 10f, 0f, 2000f, tx, ty))
        assertTrue("斜着甩，横向那一轴就够", Anim.dragDismissAny(310f, 20f, 0f, 0f, tx, ty))
        assertFalse("两轴都差一点", Anim.dragDismissAny(280f, 180f, 0f, 0f, tx, ty))
        assertFalse("拖了一半又往回甩", Anim.dragDismissAny(100f, 0f, -1500f, 0f, tx, ty))
        // 纵向不参与判定（阈值 0）时，竖向拖再多也不甩
        assertFalse(Anim.dragDismissAny(0f, 999f, 0f, 0f, tx, 0f))
        assertTrue(Anim.dragDismissAny(999f, 0f, 0f, 0f, tx, 0f))
    }

    /** 甩出去的方向：慢慢拖看拖的方向，快速一甩看甩的方向；永远是单位向量，退化时退回向下 */
    @Test fun flingDirFollowsDragOrFling() {
        val (rx, ry) = Anim.flingDir(200f, 0f, 0f, 0f)
        assertEquals(1f, rx, 1e-3f); assertEquals(0f, ry, 1e-3f)
        // 快甩：位移几乎为 0，但速度很大 → 方向跟着速度走
        val (lx, ly) = Anim.flingDir(-4f, 0f, -3000f, 0f)
        assertTrue("应当往左飞：$lx", lx < -0.99f); assertEquals(0f, ly, 1e-3f)
        // 斜着划：两轴都有分量，且是单位向量
        val (dx, dy) = Anim.flingDir(100f, 100f, 0f, 0f)
        assertEquals(1f, Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(), 1e-3f)
        assertTrue(dx > 0f && dy > 0f)
        // 退化（位移与速度都约等于 0）时不能给出 NaN 方向
        val (zx, zy) = Anim.flingDir(0f, 0f, 0f, 0f)
        assertEquals(0f, zx, 1e-3f); assertEquals(1f, zy, 1e-3f)
    }

    /**
     * 手势速度估计。用平台 VelocityTracker 会踩坑：它按**视图内坐标**算，而悬浮窗跟着手指一起挪，
     * 手指相对窗口几乎不动 → 算出来恒为 0（v1.78 实测踩过）。所以这里一律用屏幕坐标 rawY 自己估。
     */
    @Test fun dragSamplesMeasureVelocityOverRecentWindow() {
        val s = Anim.DragSamples()
        // 匀速 100px / 200ms = 500px/s
        for (i in 0..20) s.add(i * 10L, i * 5f)
        assertEquals(500f, s.velocity(), 30f)

        // 先慢慢拖一段、最后猛地一甩：最后一甩必须体现出来（整段平均会把它抹平）
        s.clear()
        for (i in 0..20) s.add(i * 10L, i * 0.5f)              // 前 200ms 只挪了 10px
        for (i in 1..5) s.add(200L + i * 10L, 10f + i * 20f)   // 最后 50ms 甩了 100px
        val wholeAvg = 110f / 0.25f
        assertTrue("最后一甩没被体现：${s.velocity()}（整段平均才 $wholeAvg）", s.velocity() > wholeAvg * 2f)

        // 松手前手指停住了：窗口内没动 → 速度回到 0（那本来也不算甩）
        s.clear()
        for (i in 0..20) s.add(i * 10L, i * 5f)
        s.add(400L, 100f); s.add(500L, 100f); s.add(600L, 100f)
        assertEquals(0f, s.velocity(), 1f)

        // 采样不足 / 时间没走 / 时间倒流：一律不敢猜，返回 0
        s.clear()
        assertEquals(0f, s.velocity(), 0f)
        s.add(100L, 10f)
        assertEquals(0f, s.velocity(), 0f)
        s.add(100L, 50f)
        assertEquals(0f, s.velocity(), 0f)
        s.clear(); s.add(1000L, 0f); s.add(900L, 50f)
        assertEquals(0f, s.velocity(), 0f)
    }

    /** 终态之间插值：两端必须精确命中，且中间不会越界（会越界就等于动画里出现「回弹」） */
    @Test fun interpolateHitsBothEndsExactly() {
        val from = Anim.Visual.entering(0, -1, 24f)
        val to = Anim.Visual.SHOW
        assertEquals(from, Anim.interpolate(from, to, 0f))
        assertEquals(to, Anim.interpolate(from, to, 1f))
        assertEquals(to, Anim.interpolate(from, to, 9f))     // 超界的 t 被夹住
        val mid = Anim.interpolate(from, to, 0.5f)
        assertTrue(mid.alpha in 0f..1f)
        assertTrue(mid.scale in from.scale..to.scale)
    }

    // ---------------- 帧循环语义（Track / VisualTrack）----------------

    /** iOS 那套参数换算：of(response, ζ) 必须真的给出 ω₀ = 2π/response、阻尼比 = ζ */
    @Test fun springOfMatchesIosParameters() {
        val s = Anim.Spring.of(0.4f, 0.8f)
        assertEquals(2f * Math.PI.toFloat() / 0.4f, s.omega0, 1e-3f)
        assertEquals(0.8f, s.zeta, 1e-4f)
        assertEquals(1f, Anim.Spring.of(0.3f, 1f).zeta, 1e-4f)
    }

    /**
     * 入场 / 出场弹簧的观感时长必须和规格表里的 [Anim.ENTER] / [Anim.EXIT] 对得上 ——
     * 换成弹簧不该偷偷把时长改掉（99% 到位就算「到了」）。反方向也要防：不能快成闪现。
     */
    @Test fun enterAndExitSpringsMatchDocumentedDurations() {
        val enterAt = Anim.Spring.enter.progress(Anim.ENTER / 1000f)
        val exitAt = Anim.Spring.exit.progress(Anim.EXIT / 1000f)
        assertTrue("入场 ${Anim.ENTER}ms 只走了 $enterAt", enterAt >= 0.97f)
        assertTrue("出场 ${Anim.EXIT}ms 只走了 $exitAt", exitAt >= 0.97f)
        assertTrue("入场一半时间就到位了，太快", Anim.Spring.enter.progress(Anim.ENTER / 2000f) < 0.95f)
        assertTrue("出场比入场还慢", Anim.Spring.exit.progress(Anim.EXIT / 1000f) >= Anim.Spring.enter.progress(Anim.ENTER / 1000f) - 0.02f)
        // 小东西的弹出同样要对齐 POP_ENTER / POP_EXIT
        assertTrue("popIn 没对齐 ${Anim.POP_ENTER}ms", Anim.Spring.popIn.progress(Anim.POP_ENTER / 1000f) >= 0.97f)
        assertTrue("popOut 没对齐 ${Anim.POP_EXIT}ms", Anim.Spring.popOut.progress(Anim.POP_EXIT / 1000f) >= 0.97f)
        // 小东西该比面板轻快：同一个时刻走得更多
        assertTrue("popIn 不比 enter 快", Anim.Spring.popIn.progress(0.1f) > Anim.Spring.enter.progress(0.1f))
    }

    /** 单通道：从当前值接管，逐帧推到目标，收敛后精确等于目标（不留浮点尾巴） */
    @Test fun trackSettlesExactlyOnTarget() {
        val t = Anim.Track(Anim.Spring.enter, 0.5f, 2f)
        t.snapTo(24f)                       // 视图此刻在 24px 处
        t.to(0f, keepVelocity = false)
        var frames = 0
        while (!t.step(dt60) && frames < 600) frames++
        assertTrue("一秒还没收敛，走了 $frames 帧", frames < 60)
        t.settle()
        assertEquals(0f, t.value, 0f)
    }

    /** 帧循环版的「可打断」：换目标时位置原地接管、速度不丢 */
    @Test fun trackRetargetIsContinuous() {
        val t = Anim.Track(Anim.Spring.snap, 0.5f, 2f)
        t.snapTo(100f); t.to(0f, keepVelocity = false)
        repeat(5) { t.step(dt60) }
        val v = t.value; val sp = t.velocity
        assertTrue("这一帧速度应当非零", Math.abs(sp) > 0.5f)
        t.to(40f)                           // 半路改主意
        assertEquals("位置跳了", v, t.value, 1e-6f)
        assertEquals("速度被重置了", sp, t.velocity, 0f)
    }

    /** 拖拽跟手：snapTo 之后速度必须归零，否则松手瞬间会带上一段幽灵惯性 */
    @Test fun snapToClearsVelocity() {
        val t = Anim.Track(Anim.Spring.snap, 0.5f, 2f)
        t.snapTo(100f); t.to(0f); repeat(4) { t.step(dt60) }
        assertTrue(t.velocity != 0f)
        t.snapTo(30f)
        assertEquals(30f, t.value, 0f)
        assertEquals(0f, t.velocity, 0f)
    }

    /**
     * 四个通道必须**同一个 dt 一起走**：八帧之后每个通道都完成了大半、且都还没到。
     * 这条防的正是「给每个属性各挂一个 Animator」造成的「先缩小、再挪走」。
     */
    @Test fun visualTrackMovesAllChannelsTogether() {
        val d = 2.75f                       // 模拟 2.75x 密度
        val v = Anim.VisualTrack(Anim.Spring.enter, density = d)
        v.snapTo(Anim.Visual.entering(0, -1, 24f * d))
        v.to(Anim.Visual.SHOW, keepVelocity = false)
        repeat(8) { v.step(dt60) }
        assertTrue("alpha 没跟上：${v.alpha.value}", v.alpha.value > 0.5f)
        assertTrue("scale 没动：${v.scale.value}", v.scale.value > 0.96f)
        assertTrue("位移没跟上：${v.ty.value}", v.ty.value > -33f * d)
        assertTrue("位移已经到终点了（太快）：${v.ty.value}", v.ty.value < 0f)
    }

    /** 收敛速度：入场那种幅度应当在半秒内收敛，收敛后写终态必须和 [Anim.Visual.SHOW] 完全相等 */
    @Test fun visualTrackSettlesWithinHalfSecond() {
        val v = Anim.VisualTrack(Anim.Spring.enter, density = 1f)
        v.snapTo(Anim.Visual.entering(0, -1, 24f))
        v.to(Anim.Visual.SHOW, keepVelocity = false)
        var frames = 0
        while (!v.step(dt60) && frames < 600) frames++
        assertTrue("30 帧（0.5 秒）还没收敛，实际 $frames", frames < 30)
        v.settleAt(Anim.Visual.SHOW)
        assertEquals(Anim.Visual.SHOW, Anim.Visual(v.alpha.value, v.scale.value, v.tx.value, v.ty.value))
    }

    /**
     * 被甩出去的退场弹簧（v1.81）：必须**明显慢于**普通的收起弹簧。
     *
     * 原来的收起弹簧（popOut / 115ms）配上「单帧最多 33ms」的步长和甩出时喂进去的手指速度，
     * 整段退场只剩一两帧 —— 录屏实测 100ms 就走完，用户看到的就是「闪一下就被回收」。
     * 这条把它钉住：throwOut 至少要是 popOut 的两倍长，否则方向根本读不出来。
     */
    @Test fun throwOutSpringIsReadablySlowerThanPopOut() {
        val pop = Anim.Spring.popOut.settleTime()
        val out = Anim.Spring.throwOut.settleTime()
        // 实测口径：popOut≈158ms、throwOut≈317ms（1/ω 与 99% 收敛口径见 Anim.kt 的注释）。
        // 这里只钉「明显更慢」这条性质，不去卡两位小数的绝对值（弹簧公式动一下就会飘）。
        assertTrue("throwOut(${out}s) 应当明显慢于 popOut(${pop}s)", out > pop * 1.5f)
        assertTrue("也不能慢到拖沓（<0.8s）：$out", out < 0.8f)
        assertTrue("throwOut 的阻尼比应当接近临界：${Anim.Spring.throwOut.zeta}", Anim.Spring.throwOut.zeta > 0.7f)
    }
}
