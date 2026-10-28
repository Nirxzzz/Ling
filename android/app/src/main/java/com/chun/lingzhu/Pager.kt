package com.chun.lingzhu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
    /**
     * 跨多页的程序化跳转（首页 → 某个分类，索引可能差好几页）：-1 = 不在转场。
     * 转场期间只有 [slideFrom] / [slideTo] 两页参与，其余页不画、也不逐帧碰。
     */
    private var slideFrom = -1
    private var slideTo = -1
    private var slideP = 1f

    fun addPage(v: View) { addView(v, LayoutParams(-1, -1)); place() }

    fun goTo(i: Int, animate: Boolean = true) {
        val to = i.coerceIn(0, Math.max(0, count - 1))
        if (to != current && !canGo(current, to)) { onBlocked(current); settle(); return }
        val old = current
        if (to == old) {
            // 点的是当前页（连点两次分类）：动画还在跑就让它跑完，别把画面拨回起点；否则把半路的位移收回 0
            if (anim?.isRunning != true) settle()
            return
        }
        if (!animate || width <= 0 || !AnimGlue.motionOn()) {
            // 关动画 / 还没量到宽度：直接落终态，不留半路的 displacement
            anim?.cancel(); anim = null
            resetSlide()
            current = to; offset = 0f
            place()
            onPageChanged(current)
            return
        }
        if (Math.abs(to - old) == 1) {
            // 相邻页（引导的上下一步）：整屏平移，从手指松开的位置接着走，行为与以前一致
            resetSlide()
            offset += (to - old) * width.toFloat()
            current = to
            settle()
            onPageChanged(current)
            return
        }
        // 跨多页（首页 ↔ 分类）：旧写法按索引跨度平移，而中间的页根本不画（只有 ±1 页可见）——
        // 用户看到的是终点页从几屏外飞进来、中间一段空档，还要把两棵大树整天平移、逐帧遍历所有页。
        // 现在不管索引差几页，都只当「相邻两页」滑一屏：旧页滑出、新页从另一侧滑进，两页首尾相接，
        // 中间没有空档；只有这两页参与，逐帧只写它们俩的 translationX（不请求布局，
        // 也不给大子树加 alpha —— alpha < 1 会逼出离屏层重绘，比纯位移贵得多）
        val prev = anim
        anim = null
        prev?.cancel()          // 上一段（拖动回弹 / 上一次短滑）立即收尾，它的 onEnd 不会再改写新转场
        resetSlide()
        offset = 0f
        current = to
        slideFrom = old; slideTo = to; slideP = 0f
        place()                 // 先把参与者摆好（只这两页可见），第一帧就是转场起点，不是空白
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Anim.ENTER
            interpolator = DecelerateInterpolator()
            addUpdateListener { slideP = it.animatedValue as Float; slideFrame() }
        }
        a.addListener(object : AnimatorListenerAdapter() {
            // 正常跑完 / 被手指或下一段转场打断，都落到终态：目标页在位，半程位置绝不留屏
            override fun onAnimationEnd(animation: Animator) {
                if (anim !== animation) return
                anim = null
                resetSlide()
                place()
            }
        })
        anim = a                // 登记后才能被手指 / 下一段转场打断，onEnd 也才认得它
        a.start()
        onPageChanged(current)
    }

    /** 退出跨页转场状态（不动 current / offset） */
    private fun resetSlide() { slideFrom = -1; slideTo = -1; slideP = 1f }

    /**
     * 滚动回弹 / 相邻页翻页的收尾。先把未完成的跨页短滑作废（current 已是目标页），
     * 免得旧的 [slideTo] 把 [place] 引到转场分支上。
     */
    private fun settle() {
        resetSlide()
        val running = anim
        anim = null                    // 先清空：cancel 触发的 onAnimationEnd 会直接返回，不重复 place
        running?.cancel()
        if (!AnimGlue.motionOn() || offset == 0f) { offset = 0f; place(); return }
        place()                                   // 先按常规排布落到手指松开的位置，再从它接着走
        anim = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; place() }
            start()
        }
    }
    /**
     * 手指真的开始拖了：把正在跑的动画掐在这个位置，别让它和手指逐帧抢 [offset]。
     * 跨页转场里「目标页的位移」就是它此刻的 translationX，换算成 [offset] 再退出转场状态 ——
     * 目标页停在手指底下不动，已经滑出去的那一页按常规排布离场（不留半路画面）。
     */
    private fun takeOverGesture() {
        val running = anim ?: return
        val residual = if (slideFrom >= 0 && slideTo >= 0)
            (1f - slideP) * (if (slideTo > slideFrom) 1f else -1f) * width else offset
        anim = null                    // 先注销：cancel 触发的 onAnimationEnd 会直接返回，不重复 place
        running.cancel()
        resetSlide()
        offset = residual
        place()
    }

    private fun place() {
        val w = width.toFloat()
        val sliding = slideFrom >= 0 && slideTo >= 0
        val dir = if (slideTo > slideFrom) 1f else -1f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            // 离得远的页不画（也免得读屏念到看不见的页），但保持 INVISIBLE 而不是 GONE：
            // GONE 的页不参加 measure，第一次翻到它时就要在点击那一帧量整棵子树 —— 那正是「进子页卡一下」。
            val shown = if (sliding) i == slideTo || i == slideFrom else Math.abs(i - current) <= 1
            val vis = if (shown) View.VISIBLE else View.INVISIBLE
            if (c.visibility != vis) c.visibility = vis
            c.translationX = when {
                !sliding -> (i - current) * w + offset
                i == slideTo -> (1f - slideP) * dir * w      // 新页：从侧边一屏处滑进 0
                i == slideFrom -> -slideP * dir * w          // 旧页：从 0 滑出侧边一屏
                else -> (i - current) * w
            }
            c.importantForAccessibility = if (i == current) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    /** 转场逐帧只挪这两页（不遍历其余页、不请求布局：translationX 只改绘制） */
    private fun slideFrame() {
        if (slideFrom < 0 || slideTo < 0) return
        val w = width.toFloat()
        val dir = if (slideTo > slideFrom) 1f else -1f
        getChildAt(slideTo)?.translationX = (1f - slideP) * dir * w
        getChildAt(slideFrom)?.translationX = -slideP * dir * w
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
                // 动画中按下**不立刻掐动画**：只点一下（转场途中点目标页某一行）就让动画照常跑完，
                // 画面才不会在手指底下跳一下。真要拖了，等手势真的起来时再接管（见 [takeOverGesture]）。
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!ignore && !dragging) {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        takeOverGesture()
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
                    if (Math.abs(dx) > slop && count > 1) { takeOverGesture(); dragging = true; downX = ev.x - offset; parent?.requestDisallowInterceptTouchEvent(true) } else return true
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
