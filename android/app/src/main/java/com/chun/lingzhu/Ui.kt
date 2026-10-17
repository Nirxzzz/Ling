package com.chun.lingzhu

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 默认配色同桌面 ui.css：深色玻璃底 + 薄荷/玫瑰渐变小球；v1.55 起可在设置里换（见 Theme）。含义一律靠文字（开/关、✓），不靠颜色 */
object Ui {
    /** 当前配色：OrbService / TranslateActivity 启动时从 Prefs 读，设置页改了由 OrbService 的监听刷新 */
    @Volatile var theme: Theme = Theme.EYE
    val SURFACE: Int get() = theme.surface        // 面板底（手机上没有跨窗口模糊，不透明度要高，否则后面的字会透上来）
    val CARD: Int get() = theme.card              // 菜单底
    val HAIRLINE: Int get() = theme.hairline
    val TX1: Int get() = theme.tx1
    val TX2: Int get() = theme.tx2
    val TX3: Int get() = theme.tx3

    fun dp(ctx: Context, v: Float): Int = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics))

    /**
     * v1.73 弹出反馈（v1.86 起统一走 [Vibrate]）：面板 / 菜单弹出、划词判定过临界时来一下极短的震动。
     * 力度跟着设置里的「震动强度」走（关档 = 不震）。
     */
    fun popTick(ctx: Context) = Vibrate.tickPref(ctx)

    /**
     * v1.80 「试弹」的提醒震（v1.86 起统一走 [Vibrate]）：按设置档位震一段短节拍（「哒、哒」/「哒哒哒」）。
     * 仍然只是「拍一下你肩膀」的量级 —— 长震 / 循环震是骚扰，不在档位里。
     * [level] < 0 = 跟全局设置；否则用这一条待办自己的强度（记期页能逐条设）。
     */
    fun remindVibe(ctx: Context, level: Int = -1) = Vibrate.remindAt(ctx, level)

    fun glass(ctx: Context, color: Int = SURFACE, radiusDp: Float = 16f): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setStroke(Math.max(1, dp(ctx, 0.7f)), HAIRLINE)
    }

    /** v1.66 面板底：配色带渐变（如默认「护眼」薄荷绿 → 粉）就左上 → 右下渐变，否则同 glass */
    fun panelBg(ctx: Context, t: Theme = theme, radiusDp: Float = 18f): GradientDrawable =
        if (t.bg2 == null) glass(ctx, t.surface, radiusDp).apply { setStroke(Math.max(1, dp(ctx, 0.7f)), t.hairline) }
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(t.surface, t.surfaceEnd)).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(Math.max(1, dp(ctx, 0.7f)), t.hairline)
        }

    fun pill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(theme.pillFill)
        cornerRadius = dp(ctx, 8f).toFloat()
        setStroke(Math.max(1, dp(ctx, 0.7f)), HAIRLINE)
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 14f, color: Int = TX1): TextView = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** 统一小按钮底（v1.74）：白底黑描边圆角 6dp —— 新粗野主义规格，参数与 Brut.rect 同口径 */
    fun smallPill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        setColor(Brut.CARD)
        cornerRadius = dp(ctx, 6f).toFloat()
        setStroke(Math.max(1, dp(ctx, 1f)), Brut.INK)
    }

    /** 小按钮统一上色（applyTheme 换配色时用）：字按白底选可读色 + 白底黑描边（默认配色仍为墨色） */
    fun restyle(v: TextView) { v.setTextColor(Brut.on(Brut.CARD)); v.background = smallPill(v.context) }

    /** 主操作强调：主色底 + 加粗 + 加粗描边（发送按钮；风格沿用 Brut 的 PRIMARY）；
     *  字色按主色底选可读值（默认配色 = 墨色，零变化） */
    fun emphasize(v: TextView) {
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setTextColor(Brut.on(Brut.MINT))
        (v.background as? GradientDrawable)?.apply { setColor(Brut.MINT); setStroke(dp(v.context, 1.5f), Brut.INK) }
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): TextView = text(ctx, label, 12.5f, Brut.on(Brut.CARD)).apply {
        background = smallPill(ctx)
        gravity = Gravity.CENTER
        val p = dp(ctx, 9f)
        setPadding(p, dp(ctx, 5f), p, dp(ctx, 5f))
        minimumHeight = dp(ctx, 32f)   // 统一 32dp 高的小按钮
        isClickable = true
        // 统一按压反馈（v1.84）：按下缩小 + 下沉 + 底色变深，抬起弹簧回弹。
        // 放在触摸层而不是 onClick —— 手指还压着就有反馈，不用等抬手。见 [AnimGlue.attachPress]
        AnimGlue.attachPress(this, fillOf = { (background as? GradientDrawable)?.color?.defaultColor ?: 0 })
        setOnClickListener { onClick() }
    }

    /** 主操作按钮：小按钮 + 薄荷绿强调（面板上只有「发送」一个） */
    fun buttonEmph(ctx: Context, label: String, onClick: () -> Unit): TextView = button(ctx, label, onClick).also { emphasize(it) }

    /**
     * 底色按下变深（v1.84）：逻辑收进 [Anim.Press.fill]，这里只做转发给老调用点 / 单测。
     * 纯函数（各通道 ×0.80、alpha 原样，只做位运算不碰 android），JVM 直跑 —— 见 [PressTest]。
     */
    fun darken(c: Int): Int = Anim.Press.fill(c)

    /** 状态栏 / 导航条高度（悬浮窗拿不到 WindowInsets，只能读系统尺寸；读不到按 0） */
    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun statusBarHeight(ctx: Context): Int { val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android"); return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0 }
    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun navBarHeight(ctx: Context): Int { val id = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android"); return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0 }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
}

/**
 * 高度有上限的 ScrollView：内容少时贴合内容，多了就滚。
 * 父布局给的空间比上限还小时（大屏档 + 大字号 + 答案行占了几行）要跟着缩 ——
 * v1.53 以前直接用上限盖掉父布局的限制，结果 ScrollView 比窗口还高，最后几行被窗口裁掉、也滚不到。
 */
class MaxHeightScrollView(ctx: Context, var maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMax = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.min(maxHeightPx, parentMax), MeasureSpec.AT_MOST))
    }
}

/** 整块面板的总高度上限：答案行 + 标题栏 + 正文加起来也不许超出窗口可用高度，超了就让正文（MaxHeightScrollView）去缩 */
class MaxHeightLinearLayout(ctx: Context) : LinearLayout(ctx) {
    var maxHeightPx = Int.MAX_VALUE
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMax = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.min(maxHeightPx, parentMax), MeasureSpec.AT_MOST))
    }
}

/**
 * 小球：薄荷→玫瑰渐变（可换色） + 高光。
 *  - [busy]：外圈转一段弧（按**时间**推进，掉帧也不变慢）；
 *  - [scanning]：外圈是**静止**的虚线环 + 角标「扫」（不排帧、不空转）；
 *  - [readingPaused]：外圈是静止的小环 + 暂停符（不是转圈 —— 那会误读成「还在算」）；
 *  - [pulseReady]：结果更新时来一下**一次性**的短脉冲（外圈扩散 + 淡出）。
 */
class OrbView(ctx: Context) : View(ctx) {
    /** 非 null 时用它画（设置页预览），否则跟全局 [Ui.theme]。换值会让 shader 缓存失效 */
    var themeOverride: Theme? = null
        set(v) { field = v; shaderKey = "" }
    var busy = false
        set(v) { if (field != v) { field = v; if (!v) lastSpinAt = 0L; invalidate() } }
    var scanning = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 阅读暂停（自动扫描停下来专心读，见 [com.chun.lingzhu.OrbService]）：外圈画成**静止**的暂停环 + 暂停符，
     *  不再转 busy 弧。由服务端置位 */
    var readingPaused = false
        set(v) { if (field != v) { field = v; if (v) pulseStartedAt = 0L; invalidate() } }
    /** 长按进度 0~1（v1.57）：按住时沿外圈画一圈，满了弹菜单 —— 让人知道「按住有反应、快好了」。
     *  v1.68：线性推进（画多满就是按了多久，不骗人），见 Anim.LINEAR。 */
    var hold = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt() }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt() }
    private val badgeTx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    /** 暂停符 / 就绪脉冲复用的矩形：onDraw 里反复用同一只，不每帧 new（见下方 v1.68 分配专项注释） */
    private val tmp = android.graphics.RectF()

    // ---- [Anim] 绘制路径上的缓存 ----
    // v1.68：以前 onDraw 每帧 new LinearGradient + new RadialGradient + 每帧 new DashPathEffect（连 floatArray 都是新的），
    // busy 时每帧 postInvalidateOnAnimation —— 识别那十几秒里每秒制造 60~120 组垃圾。
    // 现在只在「尺寸或配色真的变了」时才重建，稳态下 onDraw 零分配。
    private var shaderKey = ""       // 尺寸 + 配色的指纹；变了才重建 shader
    private var holdInk = 0xFFFFFFFF.toInt()   // 进度环的颜色（跟小球底色明暗相反才看得清）
    private var dash: android.graphics.DashPathEffect? = null
    private var spin = 0f
    private var lastSpinAt = 0L      // 上次转弧的时刻（ms）
    /** 「就绪」脉冲的起始时刻（uptimeMillis）；0 = 没有在跑的脉冲（中性态） */
    private var pulseStartedAt = 0L

    /**
     * 系统「动画时长缩放」是否 > 0。开发者选项 / 无障碍「关闭动画」把它调成 0 时，
     * busy 弧走静止终态、[pulseReady] 直接不画 —— 尊重系统给的无动效意图。
     * [android.animation.ValueAnimator.areAnimatorsEnabled] 是廉价静态读，逐帧调没问题。
     */
    private fun motionOn(): Boolean = android.animation.ValueAnimator.areAnimatorsEnabled()

    /**
     * 「结果就绪」的**一次性**短脉冲：外圈再抽一圈向外扩散 + 淡出，一晃而过。
     * 只改**绘制参数**，绝不碰 View 的 alpha / scale（那两样归服务端的 orbMotion 管，别抢）。
     * 动效关了、或视图没挂上 / 不可见时直接落中性终态（什么都不画）。由 [com.chun.lingzhu.OrbService]
     * 在结果真正变了时调一次。
     */
    fun pulseReady() {
        if (!motionOn() || !isShown) { pulseStartedAt = 0L; return }
        pulseStartedAt = android.os.SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** 转弧的角度：按**时间**推进，不按帧 —— 掉帧时球不会跟着变慢（原来每帧 +9°，120fps 屏上快一倍） */
    private fun spinDeg(now: Long): Float {
        if (lastSpinAt == 0L) lastSpinAt = now
        val dt = (now - lastSpinAt).coerceAtLeast(0)
        lastSpinAt = now
        spin += dt * SPIN_DEG_PER_MS
        if (spin >= 360f) spin -= 360f
        return spin
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shaderKey = ""   // 尺寸变了：重建的 shader 要按新半径算
    }

    /** 配色变了要重建 shader。跟随系统深浅色切换后走这里 */
    fun invalidateShader() { shaderKey = ""; invalidate() }

    /** 掉线：脉冲归零（中性终态，不留半截）。帧循环不靠这里停 —— 离线视图的 postInvalidateOnAnimation
     *  不再触发 onDraw，循环自然断，不会空转 */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseStartedAt = 0L
    }

    override fun onDraw(c: Canvas) {
        val s = Math.min(width, height).toFloat()
        val pad = s * 0.12f
        val r = (s - pad * 2) / 2
        val cx = width / 2f
        val cy = height / 2f
        val t = themeOverride ?: Ui.theme

        // [Anim] shader 缓存：指纹一样就直接复用，不碰 allocation
        val key = "$s|${t.orbA}|${t.orbB}"
        if (key != shaderKey) {
            shaderKey = key
            fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, t.orbStops, null, Shader.TileMode.CLAMP)
            gloss.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 0.9f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            dash = android.graphics.DashPathEffect(floatArrayOf(s * 0.06f, s * 0.05f), 0f)
            // [Anim] 进度环画一遍就够：底色浅就用深色环、深就用白环。
            // 原来描两遍（深色描边 + 白），是为了不管底色都看得见 —— 现在按明暗选一个颜色，省一遍绘制、边缘也干净
            val lum = Theme.luminance(t.orbA).toFloat()
            holdInk = if (lum > 0.5) 0xE6000000.toInt() else 0xF2FFFFFF.toInt()
        }

        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, gloss)
        // 外圈颜料每次复位：下面几个分支各改各的，别把状态漏给下一个分支
        ring.pathEffect = null
        ring.strokeCap = Paint.Cap.BUTT
        ring.strokeWidth = s * 0.05f
        ring.color = 0xE6FFFFFF.toInt()

        val now = android.os.SystemClock.uptimeMillis()
        val motion = motionOn()
        val outerR = r + pad * 0.5f     // 外圈半径
        var animating = false           // 这一帧有没有「在动」的内容 → 决定要不要排下一帧
        if (readingPaused) {
            // 阅读暂停：静止的小环 + 两根短竖条（暂停符）—— 停着等你看完，不是还在算
            c.drawCircle(cx, cy, outerR, ring)
            drawPauseGlyph(c, cx, cy, s)
        } else if (busy) {
            // [Anim] 按时间转，不按帧：掉一帧不会让球变慢。动效关了就画成静止的一段（不转、不排帧）
            val deg = if (motion) spinDeg(now) else 0f
            c.drawArc(cx - outerR, cy - outerR, cx + outerR, cy + outerR, deg, SPIN_ARC_DEG, false, ring)
            animating = motion
        } else if (scanning) {
            ring.pathEffect = dash
            c.drawCircle(cx, cy, outerR, ring)
            ring.pathEffect = null
        }
        if (hold > 0f) {
            // [Anim] 圆头单条弧：起点 12 点方向、线宽比转圈粗。圆头让「当前走到哪」读得更准
            val o = r + pad * 0.55f
            ring.strokeCap = Paint.Cap.ROUND
            ring.strokeWidth = s * 0.07f
            ring.color = holdInk
            c.drawArc(cx - o, cy - o, cx + o, cy + o, -90f, 360f * Anim.clamp01(hold), false, ring)
            ring.strokeCap = Paint.Cap.BUTT
        }
        if (scanning && !readingPaused) {
            val br = s * 0.17f
            c.drawCircle(width - br, br, br, badge)
            badgeTx.textSize = br * 1.2f
            c.drawText("扫", width - br, br + badgeTx.textSize * 0.36f, badgeTx)
        }
        // 结果就绪的一次性脉冲：外圈再抽一圈向外扩散 + 淡出；跑完置 0 回中性态
        if (pulseStartedAt != 0L) {
            val e = now - pulseStartedAt
            if (e >= PULSE_MS) {
                pulseStartedAt = 0L
            } else {
                val p = Anim.easeOut(e / PULSE_MS.toFloat())
                ring.pathEffect = null
                ring.strokeCap = Paint.Cap.BUTT
                ring.strokeWidth = s * 0.04f
                val a = (PULSE_ALPHA * (1f - p) * 255f).toInt().coerceIn(0, 255)
                ring.color = (a shl 24) or (holdInk and 0x00FFFFFF)   // 颜色随配色明暗走，不新增主题色
                val pr = r + pad * (0.5f + p)                         // 从外圈向外扩散一小段
                tmp.set(cx - pr, cy - pr, cx + pr, cy + pr)
                c.drawArc(tmp, -90f, 360f, false, ring)
                animating = true
            }
        }
        // 只在「真的在动」且视图还挂着时才继续刷帧：空闲 / 扫描（静止虚线环）/ 阅读暂停（静止）都不排帧，不空转刷电
        if (animating && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** 暂停符：两根圆头短竖条，居中。复用 [tmp] 与 [ring]，不每帧 new */
    private fun drawPauseGlyph(c: Canvas, cx: Float, cy: Float, s: Float) {
        val bw = s * 0.05f          // 条宽
        val bh = s * 0.20f          // 条高
        val gap = s * 0.07f         // 两条间距
        ring.style = Paint.Style.FILL
        ring.color = holdInk        // 跟底色明暗相反，保证看得清（不新增颜色）
        tmp.set(cx - gap / 2f - bw, cy - bh / 2f, cx - gap / 2f, cy + bh / 2f)
        c.drawRoundRect(tmp, bw * 0.4f, bw * 0.4f, ring)
        tmp.set(cx + gap / 2f, cy - bh / 2f, cx + gap / 2f + bw, cy + bh / 2f)
        c.drawRoundRect(tmp, bw * 0.4f, bw * 0.4f, ring)
        ring.style = Paint.Style.STROKE
    }

    companion object {
        /** 转弧速度：每秒 360°（一圈一秒） */
        private const val SPIN_DEG_PER_MS = 0.36f
        /** 转弧画多长（度） */
        private const val SPIN_ARC_DEG = 100f
        /** 「就绪」脉冲时长（ms）：一次性、克制 —— 比入场短，一晃而过 */
        private const val PULSE_MS = 520L
        /** 脉冲环起始不透明度：只到 ~0.5，别盖过小球本身 */
        private const val PULSE_ALPHA = 0.5f
    }
}
