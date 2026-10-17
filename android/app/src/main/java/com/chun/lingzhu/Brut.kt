package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页 / 历史页的「新粗野主义」皮肤（v1.58）：暖白底、2dp 黑描边、4dp 实心黑投影（不模糊）、
 * 薄荷绿主按钮、玫瑰金小节标题。用户看的是灰度屏 —— 状态一律靠形状表达：
 * 按下 = 投影消失、按钮下沉；开关靠拇指位置 + 黑白；单选靠圆点；从不只靠颜色。
 * 用法：先按平常写法建好控件，最后 Brut.style(根视图) 统一刷一遍；不想被刷的子树 tag = Brut.KEEP。
 */
object Brut {
    /**
     * 颜色不再是编译期常量（v2.0.4）：六套新配色把整套色板挂在 [Theme.palette] 上，这里按当前
     * [Ui.theme] 取用。palette == null（默认「护眼」/ 深色兜底 / 自定义）时，每个名字都返回与旧版
     * **逐位相同**的常量 —— 默认外观、老配色零变化。名字保持原样，只是从 const 变成 getter。
     */
    private val pal get() = Ui.theme.palette

    val INK: Int get() = pal?.ink?.let(::op) ?: 0xFF141414.toInt()
    val PAPER: Int get() = pal?.paper?.let(::op) ?: 0xFFFFF5EE.toInt()       // 暖白，带一点玫瑰调
    val CARD: Int get() = pal?.card?.let(::op) ?: 0xFFFFFFFF.toInt()
    val MINT: Int get() = pal?.primary?.let(::op) ?: 0xFF7DE3C0.toInt()      // 主色（默认薄荷绿）
    val ROSE: Int get() = pal?.secondary?.let(::op) ?: 0xFFEAB8A5.toInt()    // 副色（默认玫瑰金浅色）
    /** 副色深一档（只做点缀）：新配色把 secondary 压暗得到；默认保持原玫瑰金深色 */
    val ROSE_DEEP: Int get() = pal?.secondary?.let { op(Theme.mix(it, 0x000000, 0.30f)) } ?: 0xFFB9786A.toInt()
    /** 强调色（默认米黄）：词卡背面「助记」标签 chip 的淡色底 */
    val SAND: Int get() = pal?.highlight?.let(::op) ?: 0xFFF4ECD8.toInt()
    /** 关着的开关轨道 / 进度条底：新配色从 paper 往 ink 掺一点；默认原值 */
    val MUTED: Int get() = pal?.let { op(Theme.mix(it.paper, it.ink, 0.14f)) } ?: 0xFFDDD5CE.toInt()
    val DISABLED: Int get() = pal?.let { op(Theme.mix(it.paper, it.ink, 0.07f)) } ?: 0xFFEDE7E1.toInt()
    /** 提示 / 次要文字（输入框 hint、状态行、说明行） */
    val HINT: Int get() = pal?.hint?.let(::op) ?: 0xFF8A817B.toInt()
    const val KEEP = "brut-keep"
    /** 按钮 tag：主按钮（薄荷绿）/ 危险按钮（玫瑰金）；其余按钮白底 */
    const val PRIMARY = "brut-primary"
    const val DANGER = "brut-danger"

    /** palette 里是 RGB，用之前补不透明 alpha（默认常量本来就不透明，不走这里，故默认值逐位不变） */
    private fun op(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    /**
     * 填色上可读的前景色（按钮 / 色块里的字）：默认配色**保持原来的墨色**；
     * 六套新配色按填色明暗，在黑 / 白里挑对比度更高的一档（cyber 的蓝紫、深色底一并照顾到）。
     */
    fun on(fill: Int): Int = if (pal == null) INK
        else if (Theme.contrast(0xFFFFFFFF.toInt(), fill) >= Theme.contrast(0xFF000000.toInt(), fill)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    private fun dp(ctx: Context, v: Float) = Ui.dp(ctx, v)

    private fun rect(ctx: Context, color: Int, stroke: Boolean) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(ctx, 6f).toFloat()
        if (stroke) setStroke(dp(ctx, 2f), INK)
    }

    /** 块：填色 + 黑描边，右下角实心黑投影（投影画在视图自身范围里，不怕被父布局裁掉） */
    fun box(ctx: Context, fill: Int, shadowDp: Float = 4f, pressed: Boolean = false): Drawable {
        val s = dp(ctx, shadowDp)
        val ld = LayerDrawable(arrayOf(rect(ctx, INK, false), rect(ctx, fill, true)))
        ld.setLayerInset(0, s, s, 0, 0)
        if (pressed) ld.setLayerInset(1, s, s, 0, 0) else ld.setLayerInset(1, 0, 0, s, s)
        return ld
    }

    /** 可按的块：按下时整块往右下沉到投影上（投影消失） */
    fun pressable(ctx: Context, fill: Int, shadowDp: Float = 4f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), box(ctx, DISABLED, shadowDp, pressed = true))
        addState(intArrayOf(android.R.attr.state_pressed), box(ctx, fill, shadowDp, pressed = true))
        addState(intArrayOf(), box(ctx, fill, shadowDp))
    }

    fun button(v: TextView, fill: Int = CARD) {
        val c = v.context
        v.background = pressable(c, fill)
        v.stateListAnimator = null; v.elevation = 0f
        v.isAllCaps = false
        // 前景色按**填色**定：默认配色 = 原来的墨色（零变化）；新配色在黑 / 白里挑对比度更高的那档，
        // 于是 cyber 的蓝 / 紫主按钮、深色主题的白底按钮都能读清。
        val fg = on(fill)
        // 禁用态底是 DISABLED，字要按**禁用底**选可读色（默认配色仍是原来的灰，逐位不变）
        val dis = if (pal == null) 0xFF8A817B.toInt() else Theme.argb(0x99, on(DISABLED))
        v.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(dis, fg)))
        v.typeface = Typeface.DEFAULT_BOLD
        v.textSize = 15f
        // 右下留出投影的 4dp，文字看着才居中
        v.setPadding(dp(c, 14f), dp(c, 10f), dp(c, 18f), dp(c, 14f))
        v.minHeight = dp(c, 50f); v.minimumHeight = dp(c, 50f)
        spacing(v, 6f, 8f)
        // 叠加统一按压反馈（v1.84）：上面 pressable() 那套「按下投影收掉」照旧，这里再补上
        // 「缩小 + 下沉」的弹簧动画（不传 fillOf —— 底色变化交给 drawable 的 state_pressed）。
        AnimGlue.attachPress(v)
    }

    fun field(e: EditText) {
        val c = e.context
        e.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(c, CARD, 4f))
            addState(intArrayOf(), box(c, CARD, 2f))
        }
        e.setTextColor(INK); e.setHintTextColor(HINT)
        e.setPadding(dp(c, 12f), dp(c, 10f), dp(c, 16f), dp(c, 14f))
        spacing(e, 4f, 6f)
    }

    /** 小节标题：副色块 + 黑描边 + 投影，宽度跟着字走 */
    fun heading(ctx: Context, s: String): TextView = TextView(ctx).apply {
        // tag = KEEP：字色按副色明暗取对比色（cyber 的紫底要浅字），别被 style() 的 TextView 分支一刀切成 INK。
        // 默认配色 on(ROSE) 恰好就是 INK，逐位不变；描边底也在建时按当前色板一次画好，无需再刷。
        tag = KEEP
        text = s; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(on(ROSE))
        background = box(ctx, ROSE, 3f)
        setPadding(dp(ctx, 12f), dp(ctx, 6f), dp(ctx, 15f), dp(ctx, 9f))
        layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply { topMargin = dp(ctx, 26f); bottomMargin = dp(ctx, 8f) }
    }

    /** 卡片底：白底黑边投影，内容自己留边 */
    fun card(v: View, fill: Int = CARD) {
        val c = v.context
        v.background = box(c, fill, 4f)
        v.setPadding(dp(c, 14f), dp(c, 12f), dp(c, 18f), dp(c, 16f))
    }

    private fun spacing(v: View, top: Float, bottom: Float) {
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = maxOf(it.topMargin, dp(v.context, top)); it.bottomMargin = maxOf(it.bottomMargin, dp(v.context, bottom))
            it.marginEnd = maxOf(it.marginEnd, dp(v.context, 8f))
            v.layoutParams = it
        }
    }

    // 这些 tint 以前是 object 初始化时就固化的 val —— 换配色后仍是老颜色。改成函数，每次 style() 现取当前色板。
    private fun checkTint() = ColorStateList.valueOf(INK)
    private fun thumbTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(INK, CARD))
    private fun trackTint() = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(MINT, MUTED))

    /** 递归刷皮肤。控件已经加进父布局后再调（按钮的外边距要改 LayoutParams） */
    fun style(v: View) {
        if (v.tag == KEEP) return
        when (v) {
            is EditText -> field(v)
            is Switch -> {
                v.setTextColor(INK); v.typeface = Typeface.DEFAULT_BOLD
                v.thumbTintList = thumbTint(); v.trackTintList = trackTint()
            }
            is RadioButton -> { v.setTextColor(INK); v.buttonTintList = checkTint(); v.textSize = 15f; v.setPadding(v.paddingLeft, dp(v.context, 4f), v.paddingRight, dp(v.context, 4f)) }
            is CompoundButton -> { v.setTextColor(INK); v.buttonTintList = checkTint() }
            is Button -> button(v, when (v.tag) { PRIMARY -> MINT; DANGER -> ROSE; else -> CARD })
            is TextView -> v.setTextColor(INK)
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(INK)
                v.progressBackgroundTintList = ColorStateList.valueOf(MUTED)
                v.thumbTintList = ColorStateList.valueOf(ROSE_DEEP)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /**
     * 整页：去掉系统标题栏（页面自己画标题）、铺满到状态栏下面、状态栏图标改深色，
     * 内容按系统栏高度留边（Android 15 起 targetSdk 35 强制铺满，老系统也统一这么做，行为一致）。
     */
    @Suppress("DEPRECATION")
    fun page(a: Activity, root: View) {
        a.actionBar?.hide()
        val w = a.window
        w.statusBarColor = 0; w.navigationBarColor = 0
        w.decorView.setBackgroundColor(PAPER)
        // 深色配色（palette 的 paper 偏暗）时状态栏 / 导航条图标要反过来用浅色，否则黑图标贴在深底上看不见。
        // 默认「护眼」paper 是浅色 → 仍走深色图标，逐位不变。
        val darkPaper = Theme.luminance(PAPER) < 0.4
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (darkPaper) 0 else light, light)
        } else {
            var uiFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!darkPaper) uiFlags = uiFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = uiFlags
        }
        root.setBackgroundColor(PAPER)
        val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(base[0] + ins.systemWindowInsetLeft, base[1] + ins.systemWindowInsetTop, base[2] + ins.systemWindowInsetRight, base[3] + ins.systemWindowInsetBottom)
            ins
        }
        // 内容滚到状态栏那条就裁掉（状态栏是透明的，不裁会和时间、电量叠在一起）
        (root as? ViewGroup)?.clipToPadding = true
        root.requestApplyInsets()
    }
}
