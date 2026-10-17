package com.chun.lingzhu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * [Anim] 的 Android 侧胶水 —— 全 App **只有这个文件**允许把 [Anim] 的规格接到 View / Animator 上。
 *
 * 分成两个文件是为了让 `Anim.kt` 保持零 android 依赖（单元测试在 JVM 上直接跑）。
 * 这里的每个函数都遵守同一条规矩：**动画无论正常结束还是被 cancel，属性都落到终态**。
 *
 * 用法：
 * ```
 * // 入场
 * v.enterFrom(v.isInEditMode)          // 先摆到起点
 * v.animateVisual(Visual.SHOW, ENTER)   // 再动到终态
 * ```
 */
object AnimGlue {

    // ---------------- 插值器 ----------------
    // 用分析式的自定义 TimeInterpolator 而不是系统的 DecelerateInterpolator：
    // 系统那些在大多情况下等价，但自定义的能被 Anim 的纯函数单测覆盖，
    // 「曲线到底是什么」有断言而不是靠约定。

    val EASE_OUT = TimeInterpolator { t -> Anim.easeOut(t) }
    val EASE_IN = TimeInterpolator { t -> Anim.easeIn(t) }
    val EASE_IN_OUT = TimeInterpolator { t -> Anim.easeInOut(t) }
    val LINEAR = TimeInterpolator { t -> Anim.linear(t) }

    /** 把 [Anim.Spring] 变成插值器：输入 0..1 的时间进度，内部按弹簧解析解取值（可以冲过 1） */
    fun spring(s: Anim.Spring, v0: Float = 0f) = TimeInterpolator { t -> s.progress(t, v0) }

    /** 兼容旧调用点：系统 DecelerateInterpolator（保留给不认识 Anim 的老代码） */
    val SYSTEM_DECELERATE = DecelerateInterpolator()
    val SYSTEM_LINEAR = LinearInterpolator()

    // ---------------- View 扩展 ----------------

    /** View 直接实现 [Anim.VisualTarget]，让 [Anim.applyVisual] 能写它 */
    private fun View.asTarget() = object : Anim.VisualTarget {
        override var alpha: Float
            get() = this@asTarget.getAlpha()
            set(v) = this@asTarget.setAlpha(v)
        override var scaleX: Float
            get() = this@asTarget.getScaleX()
            set(v) = this@asTarget.setScaleX(v)
        override var scaleY: Float
            get() = this@asTarget.getScaleY()
            set(v) = this@asTarget.setScaleY(v)
        override var translationX: Float
            get() = this@asTarget.getTranslationX()
            set(v) = this@asTarget.setTranslationX(v)
        override var translationY: Float
            get() = this@asTarget.getTranslationY()
            set(v) = this@asTarget.setTranslationY(v)
    }

    /**
     * 把 [v] 的视觉属性一次写死成 [s]。
     *
     * **这是 v1.57 事故的正解**：原来 `setHidden` 只 `cancel()` 了动画却没写回属性，
     * `translationY` 停在 24dp。现在 cancel 和写回是同一个动作，漏不掉。
     */
    fun View.applyVisual(s: Anim.Visual) = Anim.applyVisual(asTarget(), s)

    /**
     * 动到终态 [to]。不管正常结束、被 [cancel]、还是视图被 detach，都写回 [to]。
     *
     * @param settleAfter 动画真正跑完（不是被 cancel）之后才调；cancel 时不调，避免「半路的收尾」生效
     */
    fun View.animateVisual(to: Anim.Visual, duration: Long, curve: TimeInterpolator = EASE_OUT, settleAfter: (() -> Unit)? = null) {
        animate().cancel()
        animate().apply {
            alpha(to.alpha)
            scaleX(to.scale); scaleY(to.scale)
            translationX(to.tx); translationY(to.ty)
            setDuration(duration)
            setInterpolator(curve)
            setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    this@animateVisual.applyVisual(to)   // 终点再写一遍：浮点插值可能差最后 0.001
                    settleAfter?.invoke()
                }

                override fun onAnimationCancel(animation: Animator) {
                    this@animateVisual.applyVisual(to)   // ← v1.57 就是缺这一行
                }
            })
            start()
        }
    }

    /** 摆到入场起点（不进窗口时调）：之后调 [animateVisual] 到 [Anim.Visual.SHOW] */
    fun View.enterFrom(from: Anim.Visual) = applyVisual(from)

    // ---------------- 统一按压反馈（v1.84）----------------

    // 惰性弹簧表：键是 View（弱引用），值是 WeakReference<SpringAnimation> 而**不是**动画器本身。
    // 为什么再包一层：SpringAnimation 内部强持有它的 View，若把动画器直接当 WeakHashMap 的值，
    // 弱键就被值钉住、永不回收（经典坑）。包一层 WeakReference 之后，空闲的动画器可被 GC；
    // 正在跑的那个被 Choreographer 的帧回调钉住，不会跑到一半「消失」。
    // 只在主线程访问（触摸回调本来就在主线程），WeakHashMap 不必加锁。
    private val presses = java.util.WeakHashMap<View, java.lang.ref.WeakReference<SpringAnimation>>()

    /** 取（或按需创建）这个 View 的按压动画器。空闲被回收了就重建 —— 重建时 [SpringAnimation.to] 会读视图真实值，从而无缝接管。 */
    private fun pressMotion(v: View): SpringAnimation =
        presses[v]?.get() ?: SpringAnimation(v, Anim.Press.downSpring).also { presses[v] = java.lang.ref.WeakReference(it) }

    /**
     * 统一按压反馈（v1.84）：全 App 原来三套各写各的按压 —— `Ui.pressIn/pressOut`（60ms 线性、只缩 4%）、
     * `Brut.pressable`（只收投影、没缩放回弹）、WordCard 四个小按钮的本地 `setOnTouchListener`（白底上几乎看不出）
     * —— 全部收成这一个入口。强度只有一份，见 [Anim.Press]；[PressTest] 把它钉住。
     *
     * 手感：
     *  - **按下**：`scale → 0.93`、`translationY → +2.5dp`（往下沉，和 Brut「投影收掉」一路）、底色 ×0.80；
     *    三者由 [Anim.Spring.pressDown]（收敛 < 120ms、不过冲）**同一个 dt 一起**推 → 缩小与下沉同时发生；
     *  - **抬起 / 取消**：[Anim.Spring.pressUp]（约 200ms、ζ=0.70 → 约 4.6% 过冲）弹簧回弹，带一点点弹；
     *  - **连点不粘**：复用同一个动画器并 `to(...)` 重定向 —— 这是弹簧版的 cancel（位置和速度一起接管），
     *    快速连按既不会卡在半按、也不会「重新起步」；
     *  - **不消费事件**：始终返回 false，`OnClickListener` 照常触发。
     *
     * @param haptic true 时按下一记 [Ui.popTick]（系统 tick 级、不引新权限，失败静默）
     * @param fillOf 返回这个 View **当前**的底色（如 `{ (background as? GradientDrawable)?.color?.defaultColor ?: 0 }`）。
     *   非 null 时按下把它按 [Anim.Press.fill] 变深、抬起还原。Brut 那套按下反馈在 drawable 的 state_pressed 上
     *   （按下即投影收掉），从 [Brut.button] 走时不传即可。
     */
    fun attachPress(v: View, haptic: Boolean = false, fillOf: (() -> Int)? = null) {
        // 底色在「按下」那一刻记下来，抬起照它还原 —— emphasize / 换主题改过底色也不会按一次就丢。
        // 用 null 判「这一轮记过没有」：抬起后清零；万一哪次没收到 UP/CANCEL（视图被 detach），
        // 下次按下也只补记一次，不会把已经变深的颜色再乘一遍。
        var baseFill: Int? = null
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressMotion(view).to(Anim.Press.down(view.resources.displayMetrics.density), Anim.Press.downSpring)
                    if (fillOf != null && baseFill == null) baseFill = fillOf()
                    baseFill?.let { (view.background as? GradientDrawable)?.setColor(Anim.Press.fill(it)) }
                    if (haptic) Ui.popTick(view.context)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressMotion(view).to(Anim.Press.up, Anim.Press.upSpring)
                    baseFill?.let {
                        (view.background as? GradientDrawable)?.setColor(it)
                        baseFill = null
                    }
                }
            }
            false   // 不消费，click 照常触发
        }
    }

    /**
     * 高度动画（面板三档 / 折叠区）。和 [animateVisual] 同一个规矩：
     * 被打断时高度立刻写成目标值，不停在半路 —— 半路的高度配上后面重排的正文，就是「面板忽高忽低」。
     */
    fun animateHeight(apply: (Int) -> Unit, from: Int, to: Int, duration: Long = Anim.HEIGHT, curve: TimeInterpolator = EASE_IN_OUT) {
        val a = ValueAnimator.ofInt(Anim.clampHeight(from), Anim.clampHeight(to)).apply {
            this.duration = duration
            interpolator = curve
            addUpdateListener { apply(it.animatedValue as Int) }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { apply(Anim.clampHeight(to)) }
            override fun onAnimationCancel(animation: Animator) { apply(Anim.clampHeight(to)) }
        })
        a.start()
        return
    }

    /**
     * 折叠区展开 / 收起：测一次自然高度，再把高度从 0 动到自然高度（收起反之）。
     *
     * [target] 事先是 GONE 的话 [visibleWhenOpen] 会先把它 VISIBLE 回来，否则量出来是 0。
     * 打断时同样收敛到目标高度 —— 收起被打断就真的收起，不会留一条缝。
     */
    fun toggleCollapse(target: View, parent: ViewGroup?, open: Boolean, visibleWhenOpen: () -> Unit = {}) {
        target.animate().cancel()
        if (open) visibleWhenOpen()
        val lp = target.layoutParams
        val wSpec = View.MeasureSpec.makeMeasureSpec(
            if (lp.width > 0) lp.width else parent?.width?.coerceAtLeast(1) ?: 1, View.MeasureSpec.EXACTLY)
        target.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val full = target.measuredHeight
        val from = if (open) 0 else target.height.coerceAtLeast(1)
        val to = if (open) full else 0
        val setH: (Int) -> Unit = { h ->
            lp.height = h
            target.layoutParams = lp
            target.requestLayout()
        }
        target.alpha = if (open) 0.4f else 1f
        target.animate().alpha(if (open) 1f else 0f).setDuration(if (open) Anim.HEIGHT else Anim.EXIT)
            .setInterpolator(if (open) EASE_OUT else EASE_IN)
            .withEndAction { if (!open) target.visibility = View.GONE }
            .start()
        animateHeight(setH, from, to)
    }

    // ---------------- 帧驱动弹簧动画（v1.78「苹果式」动效）----------------

    /** 一帧最短按这么多秒算（240Hz 上限）：再短的 dt 只是浮点噪声 */
    private const val MIN_DT = 1f / 240f

    /** 一帧最长按这么多秒算（30Hz 下限）：掉帧时不让弹簧「瞬移」，观感上宁可慢一点 */
    private const val MAX_DT = 1f / 30f

    /** 一轮最多跑这么多帧（≈4 秒 @60Hz）就强制收场：正常弹簧 0.2~0.5 秒就收敛了，这是防帧循环空转的兜底 */
    private const val MAX_FRAMES = 240

    /**
     * 帧驱动、可打断、带速度继承的弹簧动画。
     *
     * 和 [animateVisual] 那条路（固定时长 + 缓动 + ViewPropertyAnimator）的差别，正是手感的分水岭：
     *
     * | | [animateVisual] 固定时长 | 本类 弹簧 |
     * |---|---|---|
     * | 起点 | 由 ViewPropertyAnimator 接手当前位置 | 每帧读视图**真实值**，别的东西挪过它也认 |
     * | 被打断 | 时长从头再算、**速度归零** → 看着像「重新起步」 | 位置与速度一起接管 → 运动是连续的 |
     * | 多属性 | 每个属性各挂一个 Animator，时长/曲线一改就脱节 | 一个 dt、一帧里一起写 → 永远是一个动作 |
     * | 收尾 | 时长到点就停，浮点插值差最后 0.001 | 距离和速度都够小才停，停下时**精确**写终态 |
     *
     * 调用方**持有实例**（这个实例就是「可打断」需要的记忆）：
     * ```
     * private val motion = AnimGlue.SpringAnimation(root)                  // 入场弹簧
     * motion.settleAt(Anim.Visual.entering(0, -1, dp(24f)))                // 摆到起点
     * motion.to(Anim.Visual.SHOW)                                          // 走
     * motion.to(Anim.Visual.leaving(0, -1, dp(16f)), Anim.Spring.exit) {    // 半路改主意
     *     wm.removeView(root)                                              //   → 连续拐出去
     * }
     * ```
     * 纪律沿用 [animateVisual]：任何收场都必须落到一个 [Anim.Visual] 终态，绝不停在半路（v1.57 的教训）。
     */
    class SpringAnimation(
        private val view: View,
        spring: Anim.Spring = Anim.Spring.enter,
        density: Float = view.resources.displayMetrics.density,
        /**
         * 视觉属性的落点。默认写视图自身的变换；也可以接到别的地方 —— 比如悬浮窗要上下拖动时，
         * 把「纵向位移」翻译成**整个窗口 y**（视图变换超出窗口会被 surface 裁掉，挪窗口不会）。
         * [view] 仍然用来判断「还在不在窗口上」。
         */
        private val target: Anim.VisualTarget = view.asTarget(),
    ) {
        private val track = Anim.VisualTrack(spring, density)
        private val viewTarget = target
        private val choreographer = Choreographer.getInstance()
        private var goal = Anim.Visual.SHOW
        private var running = false
        private var lastFrameNanos = 0L
        private var onSettle: (() -> Unit)? = null

        /** 正在跑。调用方据此判断这次 [to] 是「打断」还是「新起一段」 */
        val isRunning: Boolean get() = running

        /** 当前弹簧参数 */
        val spring: Anim.Spring get() = track.spring

        private val frame = Choreographer.FrameCallback { nanos -> onFrame(nanos) }

        /**
         * 视图**曾经** attach 过没有。
         *
         * 时序坑（v1.78 实测踩到）：`WindowManager.addView` 之后要等**下一次遍历** `isAttachedToWindow`
         * 才变 true，而第一帧回调可能早于那次遍历 —— 直接拿 `isAttachedToWindow` 当「掉线」判据的话，
         * 动画会在第一帧就自杀（症状：卡片一闪到位、动画时长=0，录屏只抓到一跳）。
         * 所以只有「attach 过又掉了」或者「连父容器都没有」才算真的掉线。
         */
        private var everAttached = false

        /** 这一轮已经跑了几帧 —— [MAX_FRAMES] 兜底用 */
        private var frames = 0

        private fun onFrame(nanos: Long) {
            if (!running) return
            if (view.isAttachedToWindow) everAttached = true
            else if (everAttached || view.parent == null) { finish(); return }
            // 兜底：正常弹簧 0.2~0.5 秒就收敛了。跑满这么多帧还没停（比如视图压根没 attach 过、
            // 或者弹簧参数被配成几乎不衰减），直接收场 —— 帧循环空转是要烧电的。
            if (++frames > MAX_FRAMES) { finish(); return }
            val dt = if (lastFrameNanos == 0L) 0f else (nanos - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = nanos
            if (dt > 0f) {
                if (track.step(dt.coerceIn(MIN_DT, MAX_DT))) { finish(); return }
                track.writeTo(viewTarget)
            }
            choreographer.postFrameCallback(frame)
        }

        /**
         * 走到 [visual]。已经在跑就**接着跑**：位置和速度原样接管，这就是「可打断」。
         * [spring] 允许这一步换一套弹簧参数（入场弹簧跑到一半改成出场弹簧）。
         * [velocity] 给「从静止开始的接管」用：拖拽松手时卡片这一刻是静止的（跟手期间属性是直接写死的），
         * 但手指其实在动 —— 把那个速度显式喂进来，甩出去才有动量。
         * [settleAfter] 只在真正跑完时回调；被 [settleAt] 叫停时不回调（免得「半路的收尾」生效）。
         */
        fun to(
            visual: Anim.Visual,
            spring: Anim.Spring = track.spring,
            velocity: Anim.Visual? = null,
            settleAfter: (() -> Unit)? = null,
        ) {
            goal = visual
            onSettle = settleAfter
            track.readFrom(viewTarget)                  // 「现在在哪」按视图实际值算，别信自己记的
            track.setSpring(spring)
            velocity?.let { track.injectVelocity(it) }
            track.to(visual, keepVelocity = running || velocity != null)   // 新起一段才清零速度；打断 / 带速度接管要带走
            if (running) return                         // 帧循环已经在跑，下一帧自然接上
            if (track.settled) { finish(); return }      // 本来就在目标上：立刻收场，别白跑一轮
            running = true
            frames = 0
            lastFrameNanos = 0L
            choreographer.postFrameCallback(frame)
        }

        /**
         * 把属性**写死**成 [visual]，并停掉帧循环。两种用法：
         *  - 摆到入口起点（之后调 [to] 开始走）—— 值写死、速度清零；
         *  - 收场（打断 / 要立刻藏起来）—— 必须落到一个合法终态，绝不停在半路。
         */
        fun settleAt(visual: Anim.Visual) {
            goal = visual
            onSettle = null
            running = false
            choreographer.removeFrameCallback(frame)
            track.settleAt(visual)
            track.writeTo(viewTarget)
        }

        /** 真正跑完（或发现已经在目标上）：精确落位并回调 [onSettle] */
        private fun finish() {
            running = false
            choreographer.removeFrameCallback(frame)
            track.settleAt(goal)
            track.writeTo(viewTarget)
            val cb = onSettle
            onSettle = null
            cb?.invoke()
        }
    }
}
