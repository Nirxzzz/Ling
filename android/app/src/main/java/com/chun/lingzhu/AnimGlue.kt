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
     * 系统「动画时长缩放」是否 > 0 —— 开发者选项 / 无障碍里把动画关掉时它是 0。
     * 胶水层每个动画入口都先问这一句：关了就直接落**终态**，不偷偷把整段跑一遍再落地。
     * [ValueAnimator.areAnimatorsEnabled] 是廉价静态读，逐帧问也没问题（见 [SpringAnimation.onFrame]）。
     */
    fun motionOn(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /**
     * 高度动画器：**调用方持有实例** —— 实例就是「可打断」需要的记忆（和 [SpringAnimation] 一个纪律）。
     *
     * 原来这里是一次性的局部 `ValueAnimator`：同一个目标连点两下就有两段在跑、谁最后写全看运气，
     * 而旧段的 `onEnd/onCancel` 还会把**旧目标**写回来 —— 这就是「高度叠着走、收起后留一条缝」的病根。
     * 现在：
     *  - 同一实例上再调 [to] 就是**打断**：停掉旧段（连它的回调一起摘掉），从当前位置接着走到新目标；
     *  - 正常结束 / [settleAt] / 系统关了动画（[motionOn]）→ 都精确落到一个高度，绝不停在半路；
     *  - [write] 只在高度真的变了时调一次：一帧里插值没动就不白 requestLayout。
     */
    class HeightMotion(
        /** 「现在多高」。只在还没写过任何值（首段动画 / [reset] 之后）时读一次，之后按自己最近写下去的值接管 */
        private val read: () -> Int,
        /** 真正把高度写下去（改 layoutParams / maxHeightPx …） */
        private val write: (Int) -> Unit,
    ) {
        private var anim: ValueAnimator? = null
        /** 最近一次写下去的值；[NONE] = 还不知道（没写过，或者外部改过，见 [reset]） */
        private var last = NONE
        /** 这一段的落点。已经在往同一个高度走时不重启动画（重复 setOpen 不该让进度条回拨） */
        private var goal = NONE
        /** 这一段的收尾。只会被**当前**这一段触发；被打断即作废（旧段的 GONE 不能落到新一段展开上） */
        private var settleAfter: (() -> Unit)? = null

        /** 正在跑。调用方据此判断这次 [to] 是「打断」还是「新起一段」 */
        val isRunning: Boolean get() = anim?.isRunning == true

        /**
         * 动到 [to]。[settleAfter] 只在**这一趟**真正跑完时回调：被打断 / 被 [settleAt] 叫停都不回调
         * （所以「收起末尾 GONE」不会落到下一段展开上）。
         */
        fun to(to: Int, duration: Long = Anim.HEIGHT, curve: TimeInterpolator = AnimGlue.EASE_IN_OUT, settleAfter: (() -> Unit)? = null) {
            val target = Anim.clampHeight(to)
            if (isRunning && target == goal) { this.settleAfter = settleAfter; return }   // 同一落点：让它跑完
            stop()
            this.settleAfter = settleAfter
            goal = target
            if (!AnimGlue.motionOn() || duration <= 0L) { put(target); fireSettle(); return }
            val from = Anim.clampHeight(if (last != NONE) last else read())
            if (from == target) { put(target); fireSettle(); return }
            val a = ValueAnimator.ofInt(from, target).apply {
                this.duration = duration
                interpolator = curve
                addUpdateListener { put(it.animatedValue as Int) }
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    anim = null
                    put(target)          // 精确落位：整数插值也会差最后 1px
                    fireSettle()
                }
            })
            anim = a
            a.start()
        }

        /** 立刻落位（不打动画）：建树 / 转屏 / 收起档。上一段一并掐掉，它的收尾也不再补跑 */
        fun settleAt(px: Int) { stop(); settleAfter = null; put(px) }

        /** 只掐掉在跑的那一段（连同它的收尾）、不写值：调用方紧接着自己写终态时用 */
        fun cancel() { stop(); settleAfter = null; last = NONE }

        /** 外部直接改过这个高度（没走本实例）之后调一次，否则「值一样」会把该写的那次省掉 */
        fun reset() { last = NONE }

        private fun put(px: Int) {
            val h = Anim.clampHeight(px)
            if (h == last) return
            last = h
            write(h)
        }

        private fun fireSettle() {
            val cb = settleAfter
            settleAfter = null
            cb?.invoke()
        }

        private fun stop() {
            anim?.let {
                anim = null
                it.removeAllListeners()        // 旧段的 end / cancel 一律不落地：叠高度就是这里漏的
                it.removeAllUpdateListeners()
                it.cancel()
            }
            goal = NONE
        }

        private companion object { const val NONE = Int.MIN_VALUE }
    }

    /**
     * 折叠区 / 「高级设置」盒的公共开合（设置页用）：量一次自然高度，再把高度从 0 动到自然高度（收起反之）。
     *
     * **调用方持有实例**，一个盒子的开合都走它 —— 快速连点就是打断上一段：
     *  - 收起的收尾（`GONE`）只属于**最新**一段：旧段回调被摘掉，不会把刚展开的盒子又藏掉（原 `withEndAction` 会）；
     *  - 展开结束恢复 `WRAP_CONTENT`：之后摘要 / 状态行变了照常自适应；
     *  - 系统关了动画直接落终态；`animate = false` 用于建树 / 刷新 / 搜索跳转（要的是终态，不是过渡）。
     */
    class FoldMotion(private val v: View) {
        private val height = HeightMotion(read = { shownHeight() }, write = { setHeight(it) })

        /** 现在是不是展开的（收起动画跑到一半时已经是 false —— 别再用 visibility 猜） */
        var isOpen = false
            private set

        fun setOpen(open: Boolean, animate: Boolean = true) {
            val was = isOpen
            isOpen = open
            if (!animate || !AnimGlue.motionOn()) { if (open) expandNow() else collapseNow(); return }
            if (open == was) {
                // 没换方向：正在往同一个终态走就让它跑完（重复 setOpen 不该回拨进度），否则只补齐终态
                if (height.isRunning) return
                if (open) expandNow() else collapseNow()
                return
            }
            if (!open) {
                height.to(0, settleAfter = {
                    v.visibility = View.GONE
                    setHeight(WRAP_CONTENT)
                    height.reset()      // lp 又变回 wrap，记的值作废
                })
                return
            }
            v.visibility = View.VISIBLE
            // 量不出自然高度（父容器还没布局、拿不到宽度）时按 wrap 直接摊开：
            // 照 1px 宽去量会把文字折成一列，得出个荒唐的高度
            val natural = naturalHeight()
            if (natural <= 0) { expandNow(); return }
            // 从 0 长出来：收起态的 lp 是 wrap，直接接管会整块跳出来。打断收起时就接着当前高度走
            if (!height.isRunning) height.settleAt(0)
            height.to(natural, settleAfter = {
                setHeight(WRAP_CONTENT)
                height.reset()          // lp 变回 wrap：下一段按视图实测高度接管，不用这次量的旧值
            })
        }

        private fun collapseNow() {
            height.cancel()
            v.visibility = View.GONE
            setHeight(WRAP_CONTENT)
        }

        private fun expandNow() {
            height.cancel()
            v.visibility = View.VISIBLE
            setHeight(WRAP_CONTENT)
        }

        /** 屏幕上现在多高（GONE 时是 0，别信 GONE 之前留下的残高） */
        private fun shownHeight(): Int {
            if (v.visibility != View.VISIBLE) return 0
            if (v.height > 0) return v.height
            return (v.layoutParams?.height ?: 0).coerceAtLeast(0)
        }

        /** 只写 lp.height（值没变就不写）：[View.setLayoutParams] 自己会 requestLayout */
        private fun setHeight(h: Int) {
            val lp = v.layoutParams ?: return
            if (lp.height == h) return
            lp.height = h
            v.layoutParams = lp
        }

        /**
         * 量一次「完全展开」时的高度：收起态视图是 GONE，拿不到自己的实测高度。
         * 父容器宽度都还没有（还在建树 / 在别的页里没布局）时返回 0 —— 拿 1px 宽去量，
         * 文字会折成一列、得出个荒唐的高度，然后照着它动画。
         */
        private fun naturalHeight(): Int {
            val lp = v.layoutParams
            if (lp != null && lp.width > 0) {          // 定宽：直接按它量
                v.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                return v.measuredHeight
            }
            val parent = v.parent as? View ?: return 0
            val parentW = parent.width - parent.paddingLeft - parent.paddingRight
            if (parentW <= 0) return 0
            val margins = (lp as? ViewGroup.MarginLayoutParams)?.let { it.marginStart + it.marginEnd } ?: 0
            val avail = (parentW - margins).coerceAtLeast(1)
            v.measure(View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return v.measuredHeight
        }

        private companion object { val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT }
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
            // 系统动画被关掉（时长缩放 = 0 / 无障碍「移除动画」）：Choreographer 这条帧循环不吃缩放设置，
            // 不自己问一句就会硬跑完整段弹簧 —— 全 App 的按压、浮层入场都走这里，所以统一在这一层收住。
            if (!AnimGlue.motionOn()) { finish(); return }
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
            // 系统关了动画：直接落终态、回调恰一次，连一帧都别跑（也别留着帧回调）
            if (!AnimGlue.motionOn()) { finish(); return }
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
