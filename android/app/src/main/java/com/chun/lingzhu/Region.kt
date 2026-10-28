package com.chun.lingzhu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * v1.64 框选识别：先截一张整屏（小球面板都藏好），盖一层「定格」的截图，手指拖出一个框，只把框里的那块发给模型。
 * 一屏好几道题、或者只想问图里一小块时用。坐标换算是纯函数（Region.map），单测直接测。
 */
object Region {
    /** 屏幕坐标里的框（左上右下，已排好序、已夹在屏幕内） */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w get() = r - l
        val h get() = b - t
    }

    /** 两个触点 → 框（顺序随便、可以拖出屏幕），夹到 [0,sw)×[0,sh) */
    fun box(x0: Float, y0: Float, x1: Float, y1: Float, sw: Int, sh: Int): Box {
        fun cx(v: Float) = v.toInt().coerceIn(0, sw)
        fun cy(v: Float) = v.toInt().coerceIn(0, sh)
        return Box(minOf(cx(x0), cx(x1)), minOf(cy(y0), cy(y1)), maxOf(cx(x0), cx(x1)), maxOf(cy(y0), cy(y1)))
    }

    /** 框太小（误触）就不算 */
    fun usable(b: Box, minPx: Int) = b.w >= minPx && b.h >= minPx

    /** 屏幕框 → 截图里的框（截图可能被缩过：按比例换算，夹在图内） */
    fun map(b: Box, sw: Int, sh: Int, bw: Int, bh: Int): Box {
        val kx = bw.toDouble() / sw; val ky = bh.toDouble() / sh
        return Box((b.l * kx).toInt().coerceIn(0, bw - 1), (b.t * ky).toInt().coerceIn(0, bh - 1),
            Math.round(b.r * kx).toInt().coerceIn(1, bw), Math.round(b.b * ky).toInt().coerceIn(1, bh))
    }

    /** 框出来的小块放大多少倍再发：短边不到 480 就放大（最多 3 倍、长边不超 2400），小字模型才认得清 */
    fun upscale(w: Int, h: Int): Double {
        val s = minOf(w, h); if (s <= 0 || s >= 480) return 1.0
        return minOf(480.0 / s, 3.0, 2400.0 / maxOf(w, h)).coerceAtLeast(1.0)
    }

    fun crop(src: Bitmap, b: Box): Bitmap {
        val c = Bitmap.createBitmap(src, b.l, b.t, b.w.coerceAtLeast(1), b.h.coerceAtLeast(1))
        val k = upscale(c.width, c.height)
        if (k <= 1.0) return c
        val s = Bitmap.createScaledBitmap(c, (c.width * k).toInt(), (c.height * k).toInt(), true)
        if (s !== c) c.recycle()
        return s
    }
}

/**
 * 框选层：全屏覆盖，底下画定格的截图（和真实屏幕对齐），框外压暗。
 * 拖一下画新框；在框里拖 = 挪框。底部「取消 / 整屏识别 / 识别框里」。返回键 = 取消。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class RegionOverlay(
    ctx: Context,
    private val shot: Bitmap,
    private val screenW: Int,
    private val screenH: Int,
    private val onDone: (Region.Box?, whole: Boolean) -> Unit
) : FrameLayout(ctx) {
    private var box: Region.Box? = null
    private var downX = 0f; private var downY = 0f
    private var moving: Region.Box? = null
    private val loc = IntArray(2)
    /** 瑞士风格的形状：直角 + [Shape.strokeDp] 细线（[Shape.SWISS]）。显式自定义了别的形状就按用户的来 */
    private val swiss get() = Ui.shape.cornerDp <= 0f
    /** 瑞士风格的**配色**：只有 swiss / swiss_dark 自报 [Theme.noShadowStyle]（见 [Theme]）。不猜 id */
    private val inkPlate get() = Ui.theme.noShadowStyle
    /**
     * 浮板底色。框选层压在**别人的截图**上，不是压在自己的纸底上，所以瑞士风格不能直接用 paper：
     * 用「墨色板 + 反色字」——浅色瑞士 = 黑板白字，深色瑞士 = 白板墨字，任何底图上都读得清。
     * 非瑞士配色保持原来的固定近黑（逐位不变）。
     */
    private val plate: Int get() = if (inkPlate) Theme.argb(0xE6, Brut.INK) else Color.argb(200, 20, 20, 20)
    /** 底部操作条比提示条更实一点（压在功能键上，字不能被底图透上来）；非瑞士保持原来的 220 */
    private val plateBar: Int get() = if (inkPlate) Theme.argb(0xE6, Brut.INK) else Color.argb(220, 20, 20, 20)
    private val plateInk: Int get() = if (inkPlate) Brut.on(Brut.INK) else Color.WHITE
    /** 瑞士下浮板的一圈 1dp 细线（同墨色 20%，只做边界，不做装饰）；非瑞士风格没有这一层 */
    private val plateEdge: Int get() = Theme.argb(0x33, plateInk)
    /**
     * 框线：非瑞士是白 2.5dp + 黑晕（逐位不变）；瑞士是**信号红** 1dp 细线 + 一圈窄黑晕
     * （框选压在任意截图上，细线单靠红在浅底上会糊，黑晕只做可读性兜底，不是装饰）。
     */
    private val edgeW = if (swiss) Ui.dp(ctx, Ui.shape.strokeDp).toFloat() else Ui.dp(ctx, 2.5f).toFloat()
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (inkPlate) Brut.MINT else Color.WHITE
        style = Paint.Style.STROKE; strokeWidth = edgeW
    }
    private val edgeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = if (swiss) edgeW + Ui.dp(ctx, 2f) else Ui.dp(ctx, 4.5f).toFloat()
    }
    private val hint = Ui.text(ctx, "拖出一个框，框住要问的那一块（在框里拖可以挪）", 14f, plateInk).apply {
        setPadding(Ui.dp(ctx, 14f), Ui.dp(ctx, 8f), Ui.dp(ctx, 14f), Ui.dp(ctx, 8f))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(plate); cornerRadius = if (swiss) 0f else Ui.dp(ctx, 16f).toFloat()
            if (swiss) setStroke(Math.max(1, Ui.dp(ctx, Ui.shape.strokeDp)), plateEdge)
        }
    }
    private val okBtn: View

    init {
        setWillNotDraw(false)
        isFocusable = true; isFocusableInTouchMode = true
        contentDescription = "框选识别：拖出一个框"
        addView(hint, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dp(ctx, 48f) })
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 8f), Ui.dp(ctx, 10f), Ui.dp(ctx, 8f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(plateBar); cornerRadius = if (swiss) 0f else Ui.dp(ctx, 22f).toFloat()
                if (swiss) setStroke(Math.max(1, Ui.dp(ctx, Ui.shape.strokeDp)), plateEdge)
            }
        }
        fun b(label: String, act: () -> Unit) = Ui.text(ctx, label, 15f, plateInk).apply {
            setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 10f), Ui.dp(ctx, 16f), Ui.dp(ctx, 10f)); setOnClickListener { act() }
        }.also { bar.addView(it) }
        b("取消") { onDone(null, false) }
        b("整屏识别") { onDone(null, true) }
        okBtn = b("识别框里 ✓") { box?.let { onDone(it, false) } }
        okBtn.alpha = 0.4f
        addView(bar, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(ctx, 56f) })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestFocus() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) onDone(null, false); return true }
        return super.dispatchKeyEvent(e)
    }

    /** 视图坐标 → 屏幕坐标（窗口可能没盖到状态栏，差一截） */
    private fun sx(x: Float) = x + loc[0]
    private fun sy(y: Float) = y + loc[1]

    override fun onTouchEvent(e: MotionEvent): Boolean {
        getLocationOnScreen(loc)
        val x = sx(e.x); val y = sy(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                val b = box
                moving = if (b != null && x >= b.l && x <= b.r && y >= b.t && y <= b.b) b else null
                hint.visibility = View.GONE
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val m = moving
                box = if (m != null) {
                    val dx = (x - downX).toInt().coerceIn(-m.l, screenW - m.r); val dy = (y - downY).toInt().coerceIn(-m.t, screenH - m.b)
                    Region.Box(m.l + dx, m.t + dy, m.r + dx, m.b + dy)
                } else Region.box(downX, downY, x, y, screenW, screenH)
                val ok = box?.let { Region.usable(it, Ui.dp(context, 24f)) } == true
                if (e.actionMasked == MotionEvent.ACTION_UP && !ok && m == null) box = null
                okBtn.alpha = if (ok) 1f else 0.4f
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        // 截图按整块屏幕画，再平移到本窗口坐标 —— 定格的画面和真实屏幕严丝合缝
        c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        c.drawBitmap(shot, null, RectF(0f, 0f, screenW.toFloat(), screenH.toFloat()), null)
        val b = box
        if (b == null) c.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), dim)
        else {
            c.drawRect(0f, 0f, screenW.toFloat(), b.t.toFloat(), dim)
            c.drawRect(0f, b.b.toFloat(), screenW.toFloat(), screenH.toFloat(), dim)
            c.drawRect(0f, b.t.toFloat(), b.l.toFloat(), b.b.toFloat(), dim)
            c.drawRect(b.r.toFloat(), b.t.toFloat(), screenW.toFloat(), b.b.toFloat(), dim)
            val r = RectF(b.l.toFloat(), b.t.toFloat(), b.r.toFloat(), b.b.toFloat())
            c.drawRect(r, edgeShadow); c.drawRect(r, edge)
        }
        c.restore()
    }
}
