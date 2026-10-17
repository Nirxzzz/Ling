package com.chun.lingzhu

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar

/**
 * 左右滑动翻页（v1.66）：首次配置引导、设置页分类、外观色系都用它。不依赖 androidx（工程没有引 ViewPager）。
 *  - 每页一个子视图（通常是 ScrollView 包一列控件），上下滚动照常交给页里；横向手势明显（横移 > 竖移 1.5 倍）才翻页；
 *  - 手指落在滑杆、横滑条（HorizontalScrollView）、另一个 SwipePager 或 tag = NO_SWIPE 的视图上时不抢手势；
 *  - canGo(from, to) 拦住不让翻（引导：这一步没做完不能往后）—— 被拦时带阻尼回弹，并回调 onBlocked；
 *  - 追加新页就是 addPage()，页码指示器自己按 count 画。
 */
class SwipePager(ctx: Context) : FrameLayout(ctx) {
    companion object { const val NO_SWIPE = "no-swipe" }

    var current = 0
        private set
    val count: Int get() = childCount
    /** 能不能从 from 翻到 to（默认随便翻） */
    var canGo: (from: Int, to: Int) -> Boolean = { _, _ -> true }
    var onBlocked: (from: Int) -> Unit = {}
    var onPageChanged: (Int) -> Unit = {}

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var ignore = false
    private var offset = 0f          // 手指拖出的横向位移（px）
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    fun addPage(v: View) { addView(v, LayoutParams(-1, -1)); place() }

    fun goTo(i: Int, animate: Boolean = true) {
        val to = i.coerceIn(0, Math.max(0, count - 1))
        if (to != current && !canGo(current, to)) { onBlocked(current); settle(); return }
        val changed = to != current
        // 从「相对旧页的偏移」换算成「相对新页的偏移」，动画从手指松开的位置接着走
        offset += (to - current) * width.toFloat()
        current = to
        if (animate && width > 0) settle() else { offset = 0f; place() }
        if (changed) onPageChanged(current)
    }

    private fun settle() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; place() }
            start()
        }
    }

    private fun place() {
        val w = width.toFloat()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.translationX = (i - current) * w + offset
            // 离得远的页不画（省事，也免得读屏念到看不见的页）
            val near = Math.abs(i - current) <= 1
            c.visibility = if (near) View.VISIBLE else View.INVISIBLE
            c.importantForAccessibility = if (i == current) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); place() }

    /** 落点在会自己吃横向手势的控件上 → 这一手不翻页。x / y 是 v 自己的坐标（不含它的滚动量） */
    private fun hitsHorizontal(v: View, x: Float, y: Float): Boolean {
        if (v !== this && (v is SeekBar || v is HorizontalScrollView || v is SwipePager || v.tag == NO_SWIPE)) return true
        if (v !is ViewGroup) return false
        for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            val cx = x + v.scrollX - c.left - c.translationX
            val cy = y + v.scrollY - c.top - c.translationY
            if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && hitsHorizontal(c, cx, cy)) return true
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                ignore = count < 2 || hitsHorizontal(this, ev.x, ev.y)
                if (anim?.isRunning == true) { anim?.cancel(); dragging = !ignore }   // 动画中按住：接着拖
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!ignore && !dragging) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true; downX = ev.x - offset
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (!dragging) { vt?.recycle(); vt = null } }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true   // 空白处按下也要接着收后续事件
            MotionEvent.ACTION_MOVE -> {
                if (ignore) return true
                if (!dragging) {
                    val dx = ev.x - downX
                    if (Math.abs(dx) > slop && count > 1) { dragging = true; downX = ev.x - offset; parent?.requestDisallowInterceptTouchEvent(true) } else return true
                }
                var o = ev.x - downX
                // 往后拖但这一步没做完、或者已经到头：只给一点阻尼位移，表示「拉不动」
                val toward = if (o < 0) current + 1 else current - 1
                if (toward !in 0 until count || !canGo(current, toward)) o *= 0.25f
                offset = o; place()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    val w = width.coerceAtLeast(1)
                    val target = when {
                        offset < -w / 4f || v < -minFling -> current + 1
                        offset > w / 4f || v > minFling -> current - 1
                        else -> current
                    }
                    // 往后被拦时 goTo 里会回调 onBlocked 并弹回原页
                    if (target != current && target in 0 until count) goTo(target) else settle()
                }
                dragging = false; vt?.recycle(); vt = null
            }
        }
        return true
    }
}

/** 页码指示器：一排小方块，当前页实心加大，其余空心（不靠颜色，灰度屏也看得出第几页） */
class PageDots(ctx: Context) : View(ctx) {
    var count = 0
        set(v) { field = v; requestLayout(); invalidate() }
    var current = 0
        set(v) { field = v; invalidate() }
    var ink = Brut.INK
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private fun dp(v: Float) = Ui.dp(context, v).toFloat()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(resolveSize((count * dp(18f) + dp(8f)).toInt(), wSpec), resolveSize(dp(20f).toInt(), hSpec))
    }

    override fun onDraw(c: android.graphics.Canvas) {
        if (count <= 0) return
        val step = dp(18f)
        val x0 = (width - count * step) / 2f + step / 2f
        val cy = height / 2f
        p.color = ink
        for (i in 0 until count) {
            val cx = x0 + i * step
            val r = if (i == current) dp(6f) else dp(4f)
            p.style = if (i == current) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
            p.strokeWidth = dp(1.6f)
            c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
        }
    }
}
