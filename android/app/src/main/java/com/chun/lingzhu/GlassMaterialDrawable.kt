package com.chun.lingzhu

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * v2.7.3 液态玻璃的材质 [Drawable]：轻透明渐变卡面 + 顶部高光细边 + 两层柔和投影。
 *
 * 为什么自己画而不叠 [android.graphics.drawable.LayerDrawable]：玻璃材质出现在悬浮窗
 * （TYPE_APPLICATION_OVERLAY）和 Activity 页面两条路上，一层自绘把「投影 / 卡面 / 高光边」
 * 收进同一次 draw（投影两层 + 卡面 + 按压层 + 细边），圆角永远自洽，也不会像分层实现那样
 * 因视图自身裁剪（clipToOutline）把投影切没。
 *
 * 硬性纪律（与 [Ui] 的材质契约一致）：
 *  - **不实时模糊**：不碰 RenderEffect / BlurMaskFilter，也不采样背景 —— 悬浮窗拿不到后面的内容，
 *    手机上跨窗口模糊不可依赖（见 Ui.SURFACE 注释）。层次感全部来自「半透明卡面 + 高光细边 + 柔和阴影」。
 *  - **每帧零分配**：[Paint] 在构造时建好，[LinearGradient] 只在 [onBoundsChange]（尺寸变化）时建，
 *    draw 里只有 drawRoundRect 和几个整型换算，没有任何 new。
 *  - **按压有反馈**：[isStateful] = true，按下时卡面压暗一档（和 AnimGlue 的按下变深同一手感），
 *    玻璃底不是 GradientDrawable，AnimGlue 的按色加深到不了它，这里自己补上。
 *
 * 颜色全部由 [Ui.material] 按**传入的主题**派生，本类不做任何主题读取 ——
 * 设置页预览别的配色时，画出来的就是那套配色的玻璃，不是当前这套。
 */
internal class GlassMaterialDrawable(
    /** 卡面渐变顶端（ARGB，已带材质透明度） */
    private val bodyTop: Int,
    /** 卡面渐变底端（ARGB） */
    private val bodyBottom: Int,
    /** 高光细边顶端：亮一档（ARGB） */
    private val rimTop: Int,
    /** 高光细边底端：压回一档，卡面才有「上亮下沉」的体积（ARGB） */
    private val rimBottom: Int,
    /** 柔和投影色（ARGB，低 alpha 的墨色） */
    private val shadow: Int,
    /** 投影下坠 px（Shape.shadowDp 换算；0 = 不画投影） */
    private val shadowPx: Int,
    /** 圆角 px（跟 Shape 分层档走） */
    private val radiusPx: Float,
    /** 细边宽度 px（Shape.strokeDp，玻璃 1dp） */
    private val strokePx: Int,
) : Drawable() {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx.toFloat()
    }
    /** 投影两层：近层深一点、远层淡一点，叠出「柔」的边（不用 blur，两次 drawRoundRect 的成本） */
    private val shadeNear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadow }
    private val shadeFar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (shadow and 0x00FFFFFF) or (Math.round((shadow ushr 24) * 0.5f) shl 24)
    }
    /** 按下时压暗卡面的那层薄墨（跟 Anim.Press 的 ×0.80 一个方向） */
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.argb(0x1F, 0x000000) }

    /** 各层自带的基准 alpha：[setAlpha] 来的是「乘数」，不能把低 alpha 的投影 / 按压层直接顶成不透明 */
    private val baseShadeNearAlpha = shadow ushr 24
    private val baseShadeFarAlpha = Math.round((shadow ushr 24) * 0.5f)
    private val baseWashAlpha = 0x1F
    private var drawAlpha = 255

    /** 卡面矩形：细边画在路径上（stroke 居中），往里收半个边宽才不会被视图边界削掉 */
    private val rect = RectF()
    private var pressed = false

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        rect.inset(strokePx / 2f, strokePx / 2f)
        if (rect.width() <= 0f || rect.height() <= 0f) {
            body.shader = null; rim.shader = null
            return
        }
        // 渐变只跟 bounds 走：视图尺寸不变就一个 shader 用到底（换配色 = 换一个新 Drawable 实例）
        body.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, bodyTop, bodyBottom, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, rimTop, rimBottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val r = radiusPx
        if (shadowPx > 0) {
            // 投影画在卡面正下方：上面被卡面盖住，露出来的就是底边那一条「浮起来」的暗
            canvas.drawRoundRect(rect.left, rect.top + shadowPx, rect.right, rect.bottom + shadowPx, r, r, shadeNear)
            canvas.drawRoundRect(rect.left, rect.top + shadowPx * 2f, rect.right, rect.bottom + shadowPx * 2f, r, r, shadeFar)
        }
        canvas.drawRoundRect(rect, r, r, body)
        if (pressed) canvas.drawRoundRect(rect, r, r, wash)
        canvas.drawRoundRect(rect, r, r, rim)
    }

    /** 按下压暗：背景 Drawable 会跟着 View 的 pressed 态走（View.drawableStateChanged → setState），不占动画帧 */
    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val was = pressed
        pressed = state.contains(android.R.attr.state_pressed)
        if (pressed != was) { invalidateSelf(); return true }
        return false
    }

    override fun setAlpha(alpha: Int) {
        // setAlpha 是罕见调用（视图 alpha 走 RenderNode，不经这里）；shader 的 alpha 在颜色里，
        // paint.alpha 对 shader 是乘数；纯色层必须「基准 alpha × 乘数」，直接赋值会把柔影 / 按压层顶成实心。
        drawAlpha = alpha.coerceIn(0, 255)
        body.alpha = drawAlpha            // 卡面 / 细边的 alpha 在 shader 颜色里，paint.alpha 只做乘数
        rim.alpha = drawAlpha
        shadeNear.alpha = baseShadeNearAlpha * drawAlpha / 255
        shadeFar.alpha = baseShadeFarAlpha * drawAlpha / 255
        wash.alpha = baseWashAlpha * drawAlpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        body.colorFilter = filter; rim.colorFilter = filter
        shadeNear.colorFilter = filter; shadeFar.colorFilter = filter; wash.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
