package com.chun.lingzhu

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 全 App 唯一的动效规格来源（v1.68 动效专项）。
 *
 * 这个文件**刻意不 import 任何 android 类**：时长、缓动曲线、弹簧、状态收敛全是纯函数，
 * 单元测试在 JVM 上直接跑（见 AnimTest）。要把它接到 Android 上（TimeInterpolator / View.animate）
 * 用同文件底部的适配器，那些是唯一允许碰 View 的地方。
 *
 * ## 为什么是这些数（推荐值 + 理由）
 *
 * | 场景 | 时长 | 曲线 | 为什么 |
 * |---|---|---|---|
 * | 面板 / 菜单入场 | [ENTER] 220ms | [easeOut] | Material 的「中等」入场是 225ms；220 落在同一个量级。入场要让人看清「从哪边来」，太短像闪现、太长像卡住 |
 * | 面板 / 菜单出场 | [EXIT] 150ms | [easeIn] | 比入场快约 32%（≈ 1/ENTER×EXIT）。出场是「让路」，用户注意力已经在下一个动作上，多等 70ms 就是明显的拖沓。曲线换成 easeIn：起步慢、收尾快，物体加速离开视野，符合直觉 |
 * | 微交互（按钮按下、chip、开关） | [MICRO] 120ms | [easeOut] | 120ms 约等于 7 帧，是「感觉是即时的」上限。再短看不清变化，再长手指已经走了 |
 * | 按下瞬间 | [PRESS] 70ms | [easeOut] | 按下反馈必须**先于**回弹发生。70ms ≈ 4 帧，够看见；回弹给它 [MICRO] ×1.5 的时间 |
 * | 面板三档高度切换 | [HEIGHT] 200ms | [easeInOut] | 内容高度变化是「两边都要看清」的形变，不是位移。用 easeInOut：起步收尾都慢，中间快，观感上「稳」 |
 * | 小球闲置缩边 | [DOCK] 240ms | [easeInOut] | 比 [MICRO] 长，因为它同时改三样东西（alpha + scale + x）。三样不同步就会看着像「先缩小再挪」，拉长一点才叠成一个动作 |
 * | 小球拖拽跟手 | 每帧一次（[DRAG_FRAME_MS]） | 无（直接跟手） | 拖拽绝不能加插值器 —— 手指在哪球就在哪，任何曲线都是「延迟感」。只在**松手之后**才接管 |
 * | 松手贴边 | 弹簧 [Spring.snap] | 弹簧 | 固定的时长 + 减速曲线读不出「甩出去的速度」。弹簧把手指的初速度带进去，快甩快到、慢放慢到 |
 * | 长按进度环 | [HOLD_FILL] 380ms | 线性 | 进度环是**计量**不是表演。用 easeOut 会让圈看起来早就满了但还没触发，用户以为卡住了 |
 * | 收起 / 展开（折叠区） | [HEIGHT] 200ms | [easeInOut] | 和面板高度切换同源，复用同一个值 |
 *
 * ## 状态收敛（v1.57 的教训）
 *
 * v1.57 出过一次事故：`setHidden` 里 `animate().cancel()` 把入场动画停在半路，
 * `translationY` 残留 24dp，面板底部被窗口裁掉一截。根因不是 cancel 本身，
 * 而是 **cancel 之后没有任何东西保证属性落到终态**。
 *
 * 所以本文件把「一个视图在某个阶段的全部视觉属性」收成一个不可变数据类 [Visual]，
 * 并要求：任何动画被打断，属性必须由 [applyVisual] 显式写回某个 [Visual] 终态，
 * 不允许「停在中间」。[Visual.HIDDEN] / [Visual.SHOW] / [Visual.entering] / [Visual.leaving] 就是全部合法终态。
 */
object Anim {

    // ---------------- 时长（毫秒）----------------

    /** 入场：面板 / 菜单 / 抽屉从贴着的那一边进来 */
    const val ENTER = 220L

    /** 出场：比入场快约 32%（ENTER × 0.68 ≈ 150）。出场让路，不该拦人 */
    const val EXIT = 150L

    /** 微交互：按钮按下回弹、chip 出现、开关 */
    const val MICRO = 120L

    /** 按下瞬间（比 [MICRO] 短：按下反馈要先发生，回弹才跟上） */
    const val PRESS = 70L

    /** 松手后的回弹（比按下长，「弹回去」要看得见过程） */
    const val RELEASE = 180L

    /** 面板三档高度 / 折叠区展开收起 */
    const val HEIGHT = 200L

    /** 小球闲置缩边（同时改 alpha + scale + x，要叠成一个动作所以比 MICRO 长） */
    const val DOCK = 240L

    /** 小球贴边动画的时长上限；实际由弹簧决定，这里只是兜底（弹簧参数变了也不会失控） */
    const val SNAP_MAX = 600L

    /** 小球入场 */
    const val ORB_ENTER = 220L

    /** 拖拽跟手：一帧挪一次。低于一帧（16ms）只是白费 updateViewLayout */
    const val DRAG_FRAME_MS = 16L

    /** 长按：按住 [HOLD_DELAY] 毫秒开始画进度圈，再 [HOLD_FILL] 毫秒走满 → 触发菜单 */
    const val HOLD_DELAY = 120L
    const val HOLD_FILL = 380L

    /** 闲置多少毫秒后缩边 */
    const val IDLE_DELAY = 3000L

    /** 菜单 / 单词卡入场 */
    const val POP_ENTER = 180L

    /** 菜单 / 单词卡出场 */
    const val POP_EXIT = 120L

    // ---------------- 位移距离（dp，转 px 时乘 density）----------------

    /** 面板入场时从边缘往里挪多少（沿 enterX / enterY 方向） */
    const val ENTER_SHIFT_DP = 24f

    /** 面板出场时往外挪多少。比入场小 —— 出去的东西不用再交代那么多 */
    const val EXIT_SHIFT_DP = 16f

    /** 面板入场时的缩放下限。0.96 是「刚好感觉到是斜着飞进来的，再小就成缩放动画了」 */
    const val ENTER_SCALE = 0.96f

    // ---------------- 缓动曲线（纯函数，输入输出都是 0..1）----------------

    /**
     * 减速（= Android 的 DecelerateInterpolator 的解析式，factor 1.0）。
     * 快进慢停：入场、按下、微交互都用它。
     */
    fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    /**
     * 加速（= AccelerateInterpolator）。慢起快收：出场用。
     * 和 easeOut 严格镜像（easeIn(t) == 1 - easeOut(1-t)），测试里断言这一点。
     */
    fun easeIn(t: Float): Float = t * t

    /**
     * 两头慢中间快。高度变化用：形变的起点和终点都是「停着的」，不该用位移那种一头快一头慢。
     */
    fun easeInOut(t: Float): Float = if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)

    /** 线性。进度环专用：它是计量不是表演，缓动会让「看起来满了但没触发」 */
    fun linear(t: Float): Float = t

    /** 把 [t] 夹到 0..1。所有曲线入口都过一道，负数 / >1 不该产生「倒着播」的效果 */
    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    // ---------------- 弹簧（阻尼谐振子解析解）----------------

    /**
     * 弹簧的一个瞬时状态。**相对量**，不是绝对坐标：
     *  - [value] = 当前值 − 目标值（0 = 正好停在目标上，可正可负）；
     *  - [velocity] = d(当前值)/dt，单位是「值 / 秒」（正 = 值在变大，不是「朝目标」）。
     *
     * 用相对量是因为弹簧只关心「还差多少」，跟绝对坐标无关。整个 App 里，
     * 位移就是像素、alpha / scale 就是无量纲比例，同一套数学直接套。
     */
    data class State(val value: Float, val velocity: Float) {
        companion object { val ZERO = State(0f, 0f) }
    }

    /**
     * 弹簧参数。刚度 [stiffness]（越大越快回位）、阻尼 [damping]（越大越不弹）。
     *
     * 阻尼比 ζ = damping / (2·√(stiffness·mass))：
     *  - ζ < 1 欠阻尼：会冲过目标再回来，有「弹一下」的物理感
     *  - ζ = 1 临界阻尼：最快到目标、不过冲
     *  - ζ > 1 过阻尼：爬过去，慢
     */
    data class Spring(val stiffness: Float, val damping: Float, val mass: Float = 1f) {
        init {
            require(stiffness > 0f) { "stiffness 必须 > 0" }
            require(mass > 0f) { "mass 必须 > 0" }
            require(damping >= 0f) { "damping 不能为负" }
        }

        /** 无阻尼固有角频率 ω₀ = √(k/m) */
        val omega0: Float get() = sqrt(stiffness / mass)

        /** 阻尼比 ζ */
        val zeta: Float get() = damping / (2f * sqrt(stiffness * mass))

        /**
         * 位移比例：t 秒后完成了从 [from] 到 [to] 的百分之几。
         * [v0] 是初速度，单位「每秒走完整个距离的百分之几」——
         * 也就是甩得越快 v0 越大，正数朝目标、负数背离目标。
         *
         * 阻尼谐振子的解析解，不是数值积分：给定 t 一次算出结果，
         * 单元测试可以逐点断言（积分做不到）。
         */
        fun progress(t: Float, v0: Float = 0f): Float {
            if (t <= 0f) return 0f
            val z = zeta
            val w = omega0
            return when {
                // 欠阻尼：e^(-ζωt)·(cos ω_d t + (ζω - v0)/ω_d · sin ω_d t)
                z < 1f -> {
                    val wd = w * sqrt(1f - z * z)
                    val env = exp(-z * w * t)
                    1f - env * (cos(wd * t) + (z * w - v0) / wd * sin(wd * t))
                }
                // 临界阻尼：e^(-ωt)·(1 + (ω - v0)·t)
                z == 1f -> {
                    val env = exp(-w * t)
                    1f - env * (1f + (w - v0) * t)
                }
                // 过阻尼：两根指数
                else -> {
                    val s = w * sqrt(z * z - 1f)
                    val r1 = -z * w + s
                    val r2 = -z * w - s
                    // 系数必须「a 配 r1、b 配 r2」：r1 衰减慢，配的份额大。
                    // v1.68 起这里写反了（把该给 a 的值赋给了 b），ζ>1 时曲线当场错到没边：
                    // t=0.02s 就报「完成 52%」（真值 96%），t=0.1s 起**输出负数** →
                    // 插值器会把属性推过目标 13%，而 Spring.size 的注释承诺的正是「不过冲」。
                    // 潜伏至今是因为没人调用过 AnimGlue.spring()（见 AnimTest 的注解）。
                    val a = (v0 + r2) / (r2 - r1)
                    val b = 1f - a
                    1f - (a * exp(r1 * t) + b * exp(r2 * t))
                }
            }
        }

        /**
         * 从 [v0] 初速度出发，到「离目标不到 [restDist]（0..1 的距离比）」要多久（秒）。
         * 用来给 ValueAnimator 一个不会「动画还没完就被移除」的时长。
         */
        fun settleTime(v0: Float = 0f, restDist: Float = 0.002f): Float {
            var t = 0f
            val dt = 1f / 120f
            var last = 0f
            // 一次 2 秒还到不了就地当 2 秒；反正外面还会套 SNAP_MAX
            while (t < 2f) {
                t += dt
                val p = progress(t, v0)
                // 要求「已经过去且不再回来」：只看当前值会漏掉过冲后回落的过程
                if (abs(1f - p) < restDist && abs(p - last) < restDist) return t
                last = p
            }
            return 2f
        }

        /**
         * 走一帧：[dt] 秒之后的新状态。**任意初值**（[s] 的 value / velocity 都不必是 0），
         * 这才是「可打断」的地基 —— 打断时把当前值和当前速度原样喂进来，运动就是连续的。
         *
         * 阻尼谐振子 ẍ + 2ζω₀ẋ + ω₀²x = 0 的解析解在任意初值下的求值，不是数值积分：
         * 单步一次算完，误差不随帧数累积（[progress] 是它的归一化特例，测试里断言两者一致）。
         *
         * 靠近临界阻尼时 1/ω_d 会爆掉，所以 ζ 在 1 附近直接走临界分支（差 0.1% 以内看不出来）。
         */
        fun step(s: State, dt: Float): State {
            if (dt <= 0f) return s
            val x0 = s.value
            val v0 = s.velocity
            val z = zeta
            val w = omega0
            return when {
                z < ZETA_EPS -> {   // 无阻尼：纯简谐，ω_d ≈ ω₀、包络 ≈ 1
                    val c = cos(w * dt); val sn = sin(w * dt)
                    State(x0 * c + v0 / w * sn, -x0 * w * sn + v0 * c)
                }
                z < 1f - ZETA_EPS -> {   // 欠阻尼
                    val wd = w * sqrt(1f - z * z)
                    val env = exp(-z * w * dt)
                    val c = cos(wd * dt); val sn = sin(wd * dt)
                    State(
                        env * (x0 * c + (v0 + z * w * x0) / wd * sn),
                        env * (v0 * c - (w * w * x0 + z * w * v0) / wd * sn),
                    )
                }
                z <= 1f + ZETA_EPS -> {   // 临界阻尼
                    val env = exp(-w * dt)
                    val b = v0 + w * x0
                    State(env * (x0 + b * dt), env * (v0 - w * b * dt))
                }
                else -> {   // 过阻尼：两根实指数
                    val sq = w * sqrt(z * z - 1f)
                    val r1 = -z * w + sq
                    val r2 = -z * w - sq
                    val b = (v0 - x0 * r1) / (r2 - r1)
                    val a = x0 - b
                    val e1 = exp(r1 * dt); val e2 = exp(r2 * dt)
                    State(a * e1 + b * e2, a * r1 * e1 + b * r2 * e2)
                }
            }
        }

        /**
         * 收敛判据：离目标够近**而且**速度够小才算停。
         *
         * 两个条件缺一不可 —— 只看距离会在「刚好路过目标、速度还很大」的那一帧误判成停住；
         * 只看速度会在「顶在最远处、速度为 0」的过冲顶点误判（下一步就冲回来了）。
         */
        fun settled(s: State, restDist: Float, restSpeed: Float): Boolean =
            abs(s.value) <= restDist && abs(s.velocity) <= restSpeed

        companion object {
            /** ζ 离 1 多近就直接当临界阻尼（欠阻尼分支里 1/ω_d 会在这附近爆掉） */
            private const val ZETA_EPS = 1e-3f

            /**
             * 贴边用（v1.68）：略欠阻尼 —— 冲过目标一点点再回来，手感上有「吸」一下。
             * 固定 k=180 / c=22 → ω₀≈13.42 rad/s，ζ≈0.820，约 0.45 秒稳定。
             * 过冲量 = exp(−πζ/√(1−ζ²)) ≈ **1.1%**（原文写「约 8%」，是估错了，v1.78 按公式校正；
             * AnimTest.snapSpringOvershootsSlightly 现在把这个量钉在 0~10% 之间）。
             */
            val snap = Spring(180f, 22f)

            /**
             * 缩放 / alpha 这类「不是位移、只是大小变化」的回弹用：临界阻尼附近，不过冲。
             * 缩放冲过头会很怪（小球突然变得比正常大再缩回来），所以这条不给出弹。
             */
            val size = Spring(220f, 30f)

            /**
             * 按 **iOS 那套参数** 建弹簧（`UISpringTimingParameters`）：[response] ≈ 一个振荡周期几秒、
             * [dampingFraction] 就是阻尼比 ζ。比「硬填 k/c」直观：想快就调小 response，想不弹就把 ζ 调到 1。
             * 换算：ω₀ = 2π/response，k = ω₀²·m，c = 2ζ·ω₀·m。
             */
            fun of(response: Float, dampingFraction: Float = 1f, mass: Float = 1f): Spring {
                val w = 2f * Math.PI.toFloat() / response
                return Spring(w * w * mass, 2f * dampingFraction * w * mass, mass)
            }

            /** 入场（面板 / 菜单 / 词卡）：[response] 取到「99% 就在 [ENTER] 那一刻」—— 时长观感和老规格一致，
             *  但换成弹簧之后有了速度继承：半路被打断不会「重新起步」。ζ=1，入场不该弹。 */
            val enter = of(0.21f, 1f)

            /** 出场：同理对齐 [EXIT]（150ms）。出场是让路，收得干脆，一点不弹 */
            val exit = of(0.145f, 1f)

            /** 词卡 / 菜单这种小东西的弹出：比面板轻快一点，对齐 [POP_ENTER]（180ms） */
            val popIn = of(0.17f, 1f)

            /** 小东西的收起：对齐 [POP_EXIT]（120ms） */
            val popOut = of(0.115f, 1f)

            /**
             * 被手势**甩出去**（v1.81）：比 [popOut] 慢一档，约 340ms、ζ≈0.9 几乎不过冲。
             *
             * 为什么不能用 [popOut]：115ms 连上 `MAX_DT`（单帧最多 33ms）和甩出时喂进去的手指速度，
             * 整段退场只剩一两帧 —— 实测录屏里 100ms 就走完，视觉上是「闪一下就被回收」，
             * 根本读不出往哪个方向飞。慢下来才看得清方向。
             */
            val throwOut = of(0.34f, 0.9f)

            /** 手势甩出后的接管：略欠阻尼，和 [snap] 同一种「吸一下」的手感，但周期更短一点 */
            val fling = of(0.40f, 0.85f)

            /** 按下：极快、不过冲，对齐 [PRESS]（70ms）。按下反馈必须**先于**回弹发生 */
            val press = of(0.09f, 1f)

            /** 松手回弹：带一点过冲（ζ=0.55 → 约 12%），替代原来那个 `OvershootInterpolator(2.2)`。
             *  换成弹簧之后，松手回弹跑到一半又按下去会**带着当时的速度**接着走，不会「弹到一半停住重来」。 */
            val release = of(0.22f, 0.55f)

            /**
             * 按钮「按下」（统一按压反馈 v1.84）：比 [press] 再紧一档 —— [PressTest] 要求收敛 **< 120ms**，
             * 让按下反馈先于回弹发生（从静止按下时手感上就是「啪」一下陷进去）。
             */
            val pressDown = of(0.085f, 1f)

            /**
             * 按钮「抬起」回弹（统一按压反馈 v1.84）：约 **200ms**、ζ=0.70 → 过冲约 4.6%（「一点点弹」）。
             * 和 [release]（ζ=0.55、约 340ms）区分开：这里回弹更快、弹得更克制，适合高频点击的小按钮。
             */
            val pressUp = of(0.16f, 0.7f)
        }
    }

    /**
     * 全 App **统一**的按键按压反馈规格（v1.84）。
     *
     * 原来三处各写各的（`Ui.pressIn/pressOut`、`Brut.pressable`、词卡四个小按钮的本地触摸监听），
     * 强度还都偏弱（缩放 4%、底色只暗约 12%、60ms 线性）—— 用户反馈「点了不认识，像没反应」。
     * 这里把强度**只收一份**、并放开到「一眼看得出」，单测 [PressTest] 钉住，防止以后又被调回看不见的强度。
     *
     * 手感：按下「陷进去」（缩小 + 下沉 + 底色变深，三者同一个 dt 一起推），抬起弹簧回弹。
     * Android 侧的入口是 [AnimGlue.attachPress]。
     */
    object Press {
        /** 按下缩到 93%。硬指标：[PressTest] 断言 ≤ 0.93（旧实现 0.96 弱到看不出） */
        const val SCALE = 0.93f

        /** 按下往下沉 2.5dp（和 Brut「投影收掉」一路：按下就该往里陷）。硬指标：≥ 2dp */
        const val SINK_DP = 2.5f

        /** 按下时底色乘这个系数（旧实现 ×0.88 只暗一点点）。硬指标：比 0.88 更狠 */
        const val FILL = 0.80f

        /** 按下态：缩小 + 下沉（[density] 把 [SINK_DP] 换算成 px） */
        fun down(density: Float): Visual = Visual(1f, SCALE, 0f, SINK_DP * density)

        /** 抬起 / 取消的还原态 */
        val up: Visual get() = Visual.SHOW

        /** 按下弹簧：极快、不过冲（收敛 < 120ms）—— 按下反馈要先于回弹发生 */
        val downSpring: Spring get() = Spring.pressDown

        /** 抬起弹簧：约 200ms、带一点点过冲 */
        val upSpring: Spring get() = Spring.pressUp

        /**
         * 底色变深：各通道 × [FILL]（整数运算，白底 255 → 204，即各通道降 20%），**alpha 原样保留**。
         * 纯函数（只做位运算、不碰 android），JVM 直测。
         */
        fun fill(color: Int): Int {
            val f = (FILL * 255f).toInt()   // 0.80 → 204
            fun ch(shift: Int) = (color shr shift and 0xFF) * f / 255
            return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /**
     * 贴边时长（毫秒）：由弹簧参数算出稳定时间，再夹到 [SNAP_MAX] 以内。
     * [v0] 是松手瞬间的速度（像素/毫秒，除以 [distance] 归一化成「每秒百分之几」传进来）。
     */
    fun snapDuration(distance: Int, v0: Float = 0f): Long {
        if (distance == 0) return 0L
        val norm = v0 / Math.max(1, abs(distance)) * 1000f   // px/ms → 每秒百分之几
        val sec = Spring.snap.settleTime(norm.coerceIn(-400f, 400f))
        return (sec * 1000f).toLong().coerceIn(MIN_SNAP_MS, SNAP_MAX)
    }

    private const val MIN_SNAP_MS = 120L

    /**
     * **换目标**：把「现在在哪、以多快在走」翻译成对新目标的弹簧初值。
     *
     * 这是「可打断」的全部秘密，就两行：位置和速度一个都不重置。
     * 反面做法（v1.78 之前全 App 都是这么干的）是 `animate().cancel()` 再从头播一遍 ——
     * 位置虽然由 view.animate() 接手了，但速度归零，看起来就是「运动被拦腰截断、重新起步」，
     * 快甩一下再改主意尤其明显。苹果那套顺滑，一半来自这里的连续性。
     *
     * 纯函数：[current] 是视图此刻的值，[target] 是新目标，返回喂给 [Spring.step] 的初值。
     */
    fun retarget(current: Float, velocity: Float, target: Float): State = State(current - target, velocity)

    /**
     * 一个标量通道的弹簧跟踪：拿「值 / 速度 / 目标」三件事当状态，逐帧推进。
     *
     * **纯数据 + 纯函数、不碰 Android**：帧循环里真正决定手感的语义（从当前值接管、速度继承、
     * 收敛后精确落位、被拖拽打断）都能在 JVM 单测里逐帧断言。挂在 View 上的那层
     * （[AnimGlue.SpringAnimation]）只负责「读视图 → 推一帧 → 写视图」。
     *
     * 内部用「离目标的偏移」当状态而不是绝对值：弹簧只关心还差多少。换目标时偏移是**重算**出来的，
     * 绝对值因此天然连续 —— 不会出现「目标和偏移各算一套、结果跳一下」。
     */
    class Track(
        /** 弹簧参数。**可以在飞行中改**：入场弹簧跑到一半被改主意换出场弹簧，正是「换过渡」该有的样子 ——
         *  位置和速度都不动，只是后面的收敛速度换一套。改它走 [VisualTrack.setSpring]，别单改一条通道。 */
        var spring: Spring,
        /** 多近算到了（单位同值：位移是 px，alpha / scale 是比例） */
        val restDist: Float,
        /** 多慢算停了（单位「值 / 秒」） */
        val restSpeed: Float,
    ) {
        var target = 0f
            private set
        private var offset = 0f
        private var speed = 0f

        /** 视图此刻的值（= 目标 + 偏移） */
        val value: Float get() = target + offset

        /** 视图此刻的速度（值 / 秒） */
        val velocity: Float get() = speed

        val settled: Boolean get() = spring.settled(State(offset, speed), restDist, restSpeed)

        /** 离目标还有多远（绝对值）—— 写终态前判断「要不要动」用 */
        val distance: Float get() = abs(offset)

        /** 外部接管（拖拽跟手 / 初始摆位）：值直接摆过去、速度归零。手指在哪就是哪，不留惯性 */
        fun snapTo(v: Float) { target = v; offset = 0f; speed = 0f }

        /** 读回当前值（速度保留）：同一段动画里连续两次换目标时，速度是真实存在的物理量，不能丢 */
        fun readValue(v: Float) { offset = v - target }

        /**
         * 显式灌入速度（值 / 秒）。给「从静止开始的接管」用：拖拽跟手期间属性是直接写死的（速度清零，
         * 手指在哪卡片在哪），但松手那一刻手指其实是在动的 —— 把那个速度喂进来，甩出去才有动量。
         */
        fun injectVelocity(v: Float) { speed = v }

        /** 推进一帧，返回是否已收敛 */
        fun step(dt: Float): Boolean {
            val s = spring.step(State(offset, speed), dt)
            offset = s.value
            speed = s.velocity
            return settled
        }

        /**
         * 换目标。[keepVelocity] = false 时速度清零，用于「一段全新动画的第一次换目标」
         * （上一次运动早停了，残留速度是假的）；同一段里连续换目标一律 true。
         */
        fun to(newTarget: Float, keepVelocity: Boolean = true) {
            if (!keepVelocity) speed = 0f
            val s = retarget(value, speed, newTarget)
            target = newTarget
            offset = s.value
            speed = s.velocity
        }

        /** 精确落到目标（收敛 / 被叫停时用）：不留浮点尾巴 */
        fun settle() { offset = 0f; speed = 0f }
    }

    /**
     * 一整组 [Visual] 通道的弹簧跟踪：alpha / scale / 位移各一条 [Track]，用**同一个 dt** 一起推进。
     *
     * 这就是「三样东西是一个动作」的实现方式 —— 固定时长那套给每个属性各挂一个 Animator，
     * 时长或曲线一改就会看着「先缩小、再挪走」（v1.68 的规格表里其实已经点到这个坑）。
     */
    class VisualTrack(spring: Spring = Spring.snap, density: Float = 1f) {
        val alpha = Track(spring, REST_ALPHA, REST_ALPHA_SPEED)
        val scale = Track(spring, REST_ALPHA, REST_ALPHA_SPEED)
        val tx = Track(spring, REST_PX_DP * density, REST_PX_SPEED_DP * density)
        val ty = Track(spring, REST_PX_DP * density, REST_PX_SPEED_DP * density)

        val settled: Boolean get() = alpha.settled && scale.settled && tx.settled && ty.settled

        /** 当前弹簧参数（[setSpring] 的目标；[to] 不带参数时沿用它就是它） */
        val spring: Spring get() = alpha.spring

        /** 离目标最远的那个通道还差多少（px / 比例混着比，只看「有没有差别」） */
        val distance: Float get() = maxOf(alpha.distance, scale.distance, tx.distance, ty.distance)

        /** 读视图真实值进来（速度保留）：换目标时按「实际在哪」算，别信自己记住的 */
        fun readFrom(t: VisualTarget) {
            // Visual 只有一个 scale，视图上是 X/Y 两个 —— 本 App 一律同时设，取 X 即可
            alpha.readValue(t.alpha); scale.readValue(t.scaleX)
            tx.readValue(t.translationX); ty.readValue(t.translationY)
        }

        /** 把视图直接摆成某个终态（入场起点 / 拖拽跟手）：值照写、速度归零 */
        fun snapTo(s: Visual) { alpha.snapTo(s.alpha); scale.snapTo(s.scale); tx.snapTo(s.tx); ty.snapTo(s.ty) }

        fun writeTo(t: VisualTarget) {
            t.alpha = alpha.value; t.scaleX = scale.value; t.scaleY = scale.value
            t.translationX = tx.value; t.translationY = ty.value
            t.commit()   // 四个通道写完统一提交：WindowShift 这类 target 一帧只更新一次窗口，不再两轴各推一次
        }

        fun to(s: Visual, keepVelocity: Boolean = true) {
            alpha.to(s.alpha, keepVelocity); scale.to(s.scale, keepVelocity)
            tx.to(s.tx, keepVelocity); ty.to(s.ty, keepVelocity)
        }

        /** 换弹簧参数（入场 ↔ 出场这种过渡切换）：四条通道一起换，别只换一条 */
        fun setSpring(s: Spring) { alpha.spring = s; scale.spring = s; tx.spring = s; ty.spring = s }

        /** 显式灌入速度（拖拽松手那一刻手指的速度）；scale 的 X/Y 共用一个通道 */
        fun injectVelocity(v: Visual) {
            alpha.injectVelocity(v.alpha); scale.injectVelocity(v.scale)
            tx.injectVelocity(v.tx); ty.injectVelocity(v.ty)
        }

        /** 四个通道都要走这一帧（**不能短路**：短路了后面的通道就停在原地，变成「先缩后挪」） */
        fun step(dt: Float): Boolean {
            val a = alpha.step(dt)
            val s = scale.step(dt)
            val x = tx.step(dt)
            val y = ty.step(dt)
            return a && s && x && y
        }

        /** 收敛：精确写成 [s]，不留浮点尾巴 */
        fun settleAt(s: Visual) { alpha.snapTo(s.alpha); scale.snapTo(s.scale); tx.snapTo(s.tx); ty.snapTo(s.ty) }

        private companion object {
            /** 0.2% 的不透明度 / 缩放差异：肉眼不可见 */
            const val REST_ALPHA = 0.002f
            /** 每秒 5% 的变化速度：比这慢就等于停了 */
            const val REST_ALPHA_SPEED = 0.05f
            /** 位移用 dp 定阈值，调用方按 density 换算成 px */
            const val REST_PX_DP = 0.5f
            const val REST_PX_SPEED_DP = 2f
        }
    }

    // ---------------- 手势 ----------------

    /**
     * 手势速度估计：只留最近 [windowMs] 毫秒里的位移采样，用「最老的那一点 → 最新那一点」求速度。
     *
     * 为什么不用平台的 `VelocityTracker`：它按**视图内坐标**（`MotionEvent.getY()`）算，而悬浮窗被我们
     * 拖着一起走的时候，手指相对窗口几乎不动 —— 算出来恒为 0（v1.78 实测踩到：窗口跟手，速度永远是 0，
     * 再快的一甩都被当成「轻轻放下」）。这里一律用**屏幕坐标** rawY，坐标系不会骗人。
     *
     * 为什么用「最近一窗」而不是整段平均：用户常常先慢慢拖一段、最后猛地一甩，整段平均会把那一甩抹平。
     * 反过来，如果手指在松手前停住了（最后 100ms 没动），速度自然回到 0 —— 那本来也不算甩。
     */
    class DragSamples(private val windowMs: Long = 100L) {
        private val ts = ArrayList<Long>(8)
        private val ys = ArrayList<Float>(8)

        fun clear() { ts.clear(); ys.clear() }

        fun add(tMs: Long, y: Float) {
            // 时间倒流（多指切换 / 系统重放）时直接重来，别让速度算出一个负数时间
            if (ts.isNotEmpty() && tMs < ts[ts.size - 1]) clear()
            ts.add(tMs); ys.add(y)
            // 只留窗口内的采样，外加一条边界（保证「刚停下」还能看出上一段的速度）
            while (ts.size > 2 && ts[ts.size - 1] - ts[0] > windowMs) { ts.removeAt(0); ys.removeAt(0) }
        }

        /** 速度（px/秒，正 = 朝屏幕下方）。采样不足或时间没走 → 0（不敢猜） */
        fun velocity(): Float {
            if (ts.size < 2) return 0f
            val dt = (ts[ts.size - 1] - ts[0]) / 1000f
            if (dt <= 0f) return 0f
            return (ys[ys.size - 1] - ys[0]) / dt
        }
    }

    /**
     * 拖拽松手时「甩出去」还是「弹回来」的判定。
     *
     * 不看两个条件的「或」（位移够 **或** 速度够），而是看**投影终点**：
     * 把手指速度按 [projectionSec] 秒的余量折算成「还会多走多远」，只看这个终点过没过阈值。
     * 这是 iOS 交互式关闭的做法，四种手感都对得上：
     *  - 慢慢拖过阈值 → 投影就是当前位移，判「甩出去」；
     *  - 轻轻一甩（位移很小、速度很大）→ 投影被速度推过阈值，也判「甩出去」；
     *  - 拖了一半又往回甩 → 投影被拉回阈值内，判「弹回来」；
     *  - 慢慢拖一点点松开 → 投影没过阈值，判「弹回来」。
     * 两个条件取「或」的话，第 3 种会被判成「甩出去」（位移已经过半了），手感就错了。
     *
     * @param dy 松手时的位移（px，正 = 朝屏幕下方）
     * @param vy 松手时的速度（px/秒，正 = 朝屏幕下方）
     * @param threshold 判定阈值（px）。调用方一般取「卡片高度的三成」和「一个最小手感距离」里大的那个。
     */
    fun dragDismiss(dy: Float, vy: Float, threshold: Float, projectionSec: Float = 0.2f): Boolean {
        if (threshold <= 0f) return false
        return abs(dy + vy * projectionSec) >= threshold
    }

    /**
     * 二维版 [dragDismiss]（v1.81）：**任一轴**上的投影终点越过该轴阈值就算甩掉。
     *
     * 词卡现在四个方向都能划掉，竖向还有滚动区要抢手势，所以阈值按轴给（横向的阈值用卡片宽度、
     * 纵向用高度）。某一轴阈值为 0 / 负 = 那一轴不参与判定。
     */
    fun dragDismissAny(
        dx: Float, dy: Float,
        vx: Float, vy: Float,
        thresholdX: Float, thresholdY: Float,
        projectionSec: Float = 0.2f,
    ): Boolean =
        (thresholdX > 0f && abs(dx + vx * projectionSec) >= thresholdX) ||
            (thresholdY > 0f && abs(dy + vy * projectionSec) >= thresholdY)

    /**
     * 松手时卡片该往哪个方向飞（单位向量）。用「位移 + 投影速度」定方向：慢慢拖就看拖的方向，
     * 快速一甩就看甩的方向（哪怕这一瞬间位移还很小）。
     *
     * 两者都近似为零（理论上不会走到 —— 那样也甩不掉）时退回「向下」，至少不会出现 NaN 方向。
     */
    fun flingDir(dx: Float, dy: Float, vx: Float, vy: Float, projectionSec: Float = 0.2f): Pair<Float, Float> {
        val ex = dx + vx * projectionSec
        val ey = dy + vy * projectionSec
        val len = sqrt(ex * ex + ey * ey)
        if (!(len > 1e-3f)) return 0f to 1f
        return (ex / len) to (ey / len)
    }

    // ---------------- 状态收敛 ----------------

    /**
     * 一个视图在某个阶段的**全部**视觉属性。终态只有四种，不允许有第五种。
     *
     * 为什么打包成一个类：v1.57 的 bug 正是「只记得 alpha，忘了 translationY」。
     * 打包之后，「cancel 之后要写回什么」变成一个类型层面的问题 ——
     * [applyVisual] 要么给全，要么不给。
     */
    data class Visual(val alpha: Float, val scale: Float, val tx: Float, val ty: Float) {
        companion object {
            /** 正常显示：不透明、不缩放、没有位移。绝大多数「动画结束后」都该是它 */
            val SHOW = Visual(1f, 1f, 0f, 0f)

            /**
             * 藏起来（截屏前）：alpha 0。
             * 位移 / 缩放必须**回到 0 / 1**，不能停在半路 —— 这正是 v1.57 面板被裁的原因。
             */
            val HIDDEN = Visual(0f, 1f, 0f, 0f)

            /**
             * 入场起点：透明 + 缩小 + 沿 [dirX] / [dirY] 偏在外面。
             * 方向来自 PanelLayout 的 enterX / enterY（-1 / 0 / 1）。
             */
            fun entering(dirX: Int, dirY: Int, shiftPx: Float, scale: Float = ENTER_SCALE) =
                Visual(0f, scale, shiftPx * dirX, shiftPx * dirY)

            /** 出场起点反向：从当前位置往回收，位移比入场小、缩放更接近 1 */
            fun leaving(dirX: Int, dirY: Int, shiftPx: Float, scale: Float = 1f) =
                Visual(0f, scale, shiftPx * dirX, shiftPx * dirY)
        }
    }

    /**
     * 把 [v] 的属性**一次性写死**成终态 [s]。
     *
     * 这是全 App 唯一允许直接写视觉属性的地方。动画被打断（cancel）时必须调它，
     * 不调就等于把上一个 bug 留着。配套的 Android 适配器在 [applyVisual]（View 扩展）。
     */
    fun applyVisual(target: VisualTarget, s: Visual) {
        target.alpha = s.alpha
        target.scaleX = s.scale
        target.scaleY = s.scale
        target.translationX = s.tx
        target.translationY = s.ty
        target.commit()
    }

    /** [applyVisual] 的最小抽象：让单元测试能断言，不依赖 android.view.View */
    interface VisualTarget {
        var alpha: Float
        var scaleX: Float
        var scaleY: Float
        var translationX: Float
        var translationY: Float
        /**
         * 一批属性写完后的**统一提交点**（v1.86）。
         *
         * 默认 no-op：写自身变换的 target（View 的 alpha / scale / translation）改一个属性就立即生效，
         * 不需要额外动作。但像悬浮窗那种「改坐标要调 `updateViewLayout`」的 target，必须等一组属性
         * **全部**写完只提交一次 —— 否则一帧里 tx / ty 各提交一次，就是**双倍**窗口布局，
         * 正是拖动跟手 / 划出退场掉帧的来源。
         *
         * [VisualTrack.writeTo] 和 [applyVisual] 都会在末尾调它；自定义 target 覆写即可。
         */
        fun commit() {}
    }

    /**
     * 入场起点 → 终点之间任意进度的属性值（纯函数，测试直接算）。
     * 出场同理，只是时长和曲线换一套。
     */
    fun interpolate(from: Visual, to: Visual, t: Float): Visual {
        val f = clamp01(t)
        return Visual(
            from.alpha + (to.alpha - from.alpha) * f,
            from.scale + (to.scale - from.scale) * f,
            from.tx + (to.tx - from.tx) * f,
            from.ty + (to.ty - from.ty) * f,
        )
    }

    /** 三档高度 / 折叠区用的目标高度钳制：负数当 0，动画中途也不给出负高度 */
    fun clampHeight(px: Int): Int = if (px < 0) 0 else px

    /**
     * 闲置缩放的 alpha 与 scale 终态（v1.68：闲置时淡出 + 缩小，但两样都要有值 ——
     * alpha 0 就等于消失了，看不见「还有个球在那儿」，所以下限 10%）。
     * 抽成函数是为了能在测试里断言「alpha 不会掉到 0」这条观感底线。
     */
    fun idleAlpha(idleAlphaPct: Int): Float = (idleAlphaPct.coerceIn(MIN_IDLE_ALPHA_PCT, 80) / 100f)
    const val IDLE_SCALE = 0.6f
    private const val MIN_IDLE_ALPHA_PCT = 10

    /**
     * 闲置时缩进屏幕边的那一段（像素）：缩到 [IDLE_SCALE] 后宽度只剩 0.6w，
     * 挪出去 (w - 0.6w)/2 = 0.2w 就正好露出 80%（原来是 0.3w / 60%）。
     * 纯函数：单测能验算「挪多少之后露出的宽度正好是 w × (1 - IDLE_SCALE + …)」。
     */
    fun tuckOutPx(orbWidth: Int): Int = (orbWidth * (1f - IDLE_SCALE) / 2f).toInt()
}
