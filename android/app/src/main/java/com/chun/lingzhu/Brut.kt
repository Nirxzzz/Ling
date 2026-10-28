package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
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

    /**
     * v2.4：形状全部走 [Ui.shape]。默认 [Shape.BRUT] 的数值与改造前逐位相同（6dp 圆角 / 2dp 描边），
     * 所以现有七套配色外观零变化；瑞士风格把这三个数换成 0 / 1 / 0 就得到直角细线无投影。
     */
    private val sh get() = Ui.shape

    /**
     * 液态玻璃（v2.7.3）：这一族配色的面板 / 卡片 / 输入框 / 弹层一律走 [Ui.material]，
     * 由那一支统一出「轻透明层 + 高光边 + 柔和阴影」。判据只看配色自报的 [Theme.isGlassMaterial]，
     * 不猜 id、也不看形状 —— 玻璃是一套**材质**，不是直角或圆角，不能拿形状反推。
     */
    private val glass get() = Ui.theme.isGlassMaterial

    /**
     * 直角语言：判据是 [Shape.cornerDp] <= 0。排版规则按它切（[swissStyle]）—— 和平日各页面的
     * 瑞士分支同一个口径，不能各处自己发明一套。
     */
    private val square get() = sh.cornerDp <= 0f

    /**
     * 这套形状自己声明了「不要投影」（[Shape.shadowDp] <= 0）。瑞士是唯一的内置例子。
     *
     * 这个判据要拦的是**显式传进来的** shadowDp：老调用点传的 2f / 3f / 5f（词块、菜单项、返回键）
     * 会在无投影形状下把硬投影带回来，和整页的细线自相矛盾。用户从主题包导入的自定义形状
     * 只要 shadowDp 写了 0，这条同样生效 —— 那是他自己设的，不是这里替他决定的。
     *
     * **和 [square] 分开判**（v2.7.3）：直角是排版语言，无投影是明暗语言。用户导入的
     * 「8dp 圆角 + 0 投影」是合法形状，投影为 0 不代表他要瑞士那套直角网格。
     */
    private val noShadow get() = sh.shadowDp <= 0f

    /**
     * v2.5：这套配色是不是 Mint Rose Atelier 一族。判据只看色板（见 ThemePalette.isAtelier），
     * 不看 id —— 渲染层拿不到 id，而且以后加同族配色只要填了 surfaceAlt 就自动继承。
     *
     * 玻璃色板同样带 surfaceAlt，但它不是 Atelier：材质由 [glass] 那支接管，
     * 不能落进「柔粉描边 + 薄荷色块」里，否则同一个 surfaceAlt 槽会把两套风格混成一套。
     */
    private val atelier get() = pal?.isAtelier == true && !glass

    /**
     * 全库唯一的**发丝边**来源（卡片 / 输入框 / 行 / 图标 / 弹层 / 分隔线共用）。
     *
     * 以前同一屏上有三个边色来源：这里 Atelier 用 roseDeep、[WxSkin.LINE] 自己写 0x1F / 0x33、
     * [Ui.HAIRLINE] 用 Theme.hairline。现在一律取 [Theme.hairline]（它本来就跟主题墨色与
     * 深浅档走），需要更强的地方在它上面加档，不再另起一支颜色。
     */
    fun edge(t: Theme = Ui.theme): Int = t.hairline

    /**
     * 结构实线（粗野 2dp 框 / 瑞士 1dp 线）：和 [edge] 同一色源（主题墨色），只是不透明 ——
     * 这两套语言里「线就是边界本身」（粗野拿它当积木边，瑞士拿它代替投影），半透明会直接失效。
     */
    private val strokeInk: Int get() = INK

    /** 柔和阴影色：粗野那套是实心 INK；Atelier / 玻璃的阴影只是「卡片离纸面一点」的暗，不能是纯黑 */
    private val softShadow: Int get() = if (atelier || glass) Theme.argb(0x1F, INK) else INK

    /**
     * 每个角色在**非分层形状**上的回落圆角。分层形状由 [Ui.cornerDp] 换成自己的槽
     * （玻璃 card24 / button16 / chip999 / sheet28），这里只在用户导入的普通形状上兜底。
     */
    private fun fallbackDp(role: Ui.SurfaceRole): Float = when (role) {
        Ui.SurfaceRole.CARD -> 16f
        Ui.SurfaceRole.BUTTON -> 14f
        Ui.SurfaceRole.CHIP -> 8f
        Ui.SurfaceRole.SHEET -> 20f
    }

    /**
     * 玻璃材质的唯一出口：把「角色 + 当前主题」交给 [Ui.material]，圆角回落也在这里定死，
     * 于是按钮 / 卡片 / 输入框 / 弹层不会各传一个自己编的半径。
     */
    private fun material(ctx: Context, fill: Int, role: Ui.SurfaceRole): Drawable =
        Ui.material(ctx, fill, role, fallbackDp(role), Ui.theme)

    /**
     * 圆角矩形。[cornerDp] 传 null 表示「跟这套形状的默认档」——
     * 分层圆角（Shape.ATELIER）用 Shape.cardCornerDp，老形状用 Shape.cornerDp，两者在
     * BRUT / SWISS 上数值相同，所以既有外观点位不变。
     */
    private fun rect(ctx: Context, color: Int, stroke: Boolean, cornerDp: Float? = null): Drawable {
        // 玻璃不在这一支画：[material] 已经把轻透明层 / 高光边 / 柔和阴影一次出齐。
        // 这里返回的 GradientDrawable 只给粗野 / 瑞士 / Atelier 用，老调用点行为不变。
        return GradientDrawable().apply {
            val cr = cornerDp ?: if (sh.isLayered) sh.cardCornerDp else sh.cornerDp
            setColor(color); cornerRadius = dp(ctx, cr).toFloat()
            if (stroke) setStroke(Math.max(1, dp(ctx, sh.strokeDp)), strokeInk)
        }
    }

    /**
     * 块：填色 + 黑描边，右下角实心黑投影（投影画在视图自身范围里，不怕被父布局裁掉）。
     *
     * v2.4：shadowDp 传 null 表示「跟当前形状走」——默认 [Shape.BRUT] 是 4dp（与改造前逐位相同）。
     * [noShadow] 形状下显式数值一律作废（瑞士的 0dp 投影就这么来的）；其它形状上显式传数值的老调用点行为不变。
     */
    fun box(ctx: Context, fill: Int, shadowDp: Float? = null, pressed: Boolean = false,
            role: Ui.SurfaceRole = Ui.SurfaceRole.CARD): Drawable {
        // 玻璃：材质一次出齐（轻透明层 + 高光边 + 柔和阴影），不在这支叠硬投影 / 位移。
        if (glass) return material(ctx, fill, role)
        val sd = if (noShadow) 0f else (shadowDp ?: sh.shadowDp)
        // 无投影时不要叠加一层「投影色」——那层是实心 INK，偏移 0 会把整块盖成黑的。
        if (sd <= 0f) return rect(ctx, fill, true)
        val s = dp(ctx, sd)
        // v2.5：Atelier 的投影是 31% 透明的柔和色（softShadow），不是实心黑 —— 换成实心 INK 会让
        // 20dp 圆角的卡片看起来像贴了一张黑纸在后面，立刻回到「积木感」。
        val ld = LayerDrawable(arrayOf(rect(ctx, softShadow, false), rect(ctx, fill, true)))
        ld.setLayerInset(0, s, s, 0, 0)
        if (pressed) ld.setLayerInset(1, s, s, 0, 0) else ld.setLayerInset(1, 0, 0, s, s)
        return ld
    }

    /**
     * 可按的块：按下时整块往右下沉到投影上（投影消失）。
     *
     * 瑞士没有投影可「沉」，按下改成**填色移动一档**（见 [pressFill]，和 [swissButton] 同一手感）；
     * 否则 box 的无投影分支在按下态返回的是同一个 drawable，按下去一点反馈都没有。
     */
    fun pressable(ctx: Context, fill: Int, shadowDp: Float? = null,
                  role: Ui.SurfaceRole = Ui.SurfaceRole.CARD): Drawable = StateListDrawable().apply {
        // 玻璃按下：把填色往墨色压 12%（和 Atelier「按下变深」同一个口径），不位移。
        if (glass) {
            addState(intArrayOf(-android.R.attr.state_enabled), material(ctx, DISABLED, role))
            addState(intArrayOf(android.R.attr.state_pressed), material(ctx, Theme.mix(fill, INK, 0.12f), role))
            addState(intArrayOf(), material(ctx, fill, role))
            return@apply
        }
        if (noShadow) {
            val edge = needsEdge(fill)
            addState(intArrayOf(-android.R.attr.state_enabled), plate(ctx, DISABLED, edge))
            addState(intArrayOf(android.R.attr.state_pressed), plate(ctx, pressFill(fill), edge))
            addState(intArrayOf(), plate(ctx, fill, edge))
            return@apply
        }
        addState(intArrayOf(-android.R.attr.state_enabled), box(ctx, DISABLED, shadowDp, pressed = true))
        addState(intArrayOf(android.R.attr.state_pressed), box(ctx, fill, shadowDp, pressed = true))
        addState(intArrayOf(), box(ctx, fill, shadowDp))
    }

    /**
     * 瑞士的实心色块：直角 + 无投影，圆角 / 描边都取当前形状。
     *
     * [edge] 由**静息填色**决定（调用方按静息态算好后三态共用）：白卡压在纸底上只有 1.12:1、
     * 禁用底 1.07:1，不画线整块就消失在纸里了（页面上大量「白卡按钮 / 行」正是这个组合）。
     * 信号红、墨色、灰阶底本身已经和纸底拉开，加框反而违背这套「实心填色、无框」的按钮规格。
     *
     * 三态共用同一个 [edge]，按下时那条线不会突然消失 —— 边界的去留跟着按钮的身份，不跟着状态。
     */
    private fun plate(ctx: Context, fill: Int, edge: Boolean) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(ctx, sh.cornerDp).toFloat()
        if (edge) setStroke(Math.max(1, dp(ctx, sh.strokeDp)), INK)
    }

    /** 这个填色压在纸底上需不需要一条线才看得见（< 1.3 就是「几乎同色」） */
    private fun needsEdge(fill: Int): Boolean = Theme.contrast(fill, PAPER) < 1.3

    fun button(v: TextView, fill: Int = CARD) {
        val c = v.context
        if (glass) { glassButton(v, fill); return }
        if (sh.isLayered) { atelierButton(v, fill); return }
        if (noShadow) { swissButton(v, fill); return }
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

    /**
     * v2.5 Mint Rose Atelier 的按钮：14dp 圆角 + 无描边 + 按下时**填色变深**（不位移）。
     *
     * 为什么不沿粗野那套「按下沉到投影上」：那是印刷错位的隐喻，配 20dp 圆角 + 柔和阴影时
     * 会自相矛盾（柔和阴影的卡片突然跳 2dp，像没对齐）。这里改成填色压暗 + [AnimGlue.attachPress]
     * 的轻微缩放，反馈在「颜色变深 + 手感知的缩小」，不动布局位置。
     */
    private fun atelierButton(v: TextView, fill: Int) {
        val c = v.context
        val cr = sh.buttonCornerDp
        fun solid(f: Int) = GradientDrawable().apply { setColor(f); cornerRadius = dp(c, cr).toFloat() }
        v.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), solid(DISABLED))
            addState(intArrayOf(android.R.attr.state_pressed), solid(Theme.mix(fill, INK, 0.10f)))
            addState(intArrayOf(), solid(fill))
        }
        v.stateListAnimator = null; v.elevation = 0f
        v.isAllCaps = false
        val fg = on(fill)
        val dis = Theme.argb(0x99, on(DISABLED))
        v.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(dis, fg)))
        // 字重用 MEDIUM 而不是 BOLD：Atelier 的精致感来自「字轻、边界细、留白多」，
        // 全 BOLD 会把它推回粗野那套的厚重感。
        v.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        v.textSize = 14.5f
        // 四边等距（没有投影要留位），左右多给 4dp —— 圆角大，文字贴边会「掉出去」
        v.setPadding(dp(c, 20f), dp(c, 13f), dp(c, 20f), dp(c, 13f))
        v.minHeight = dp(c, 48f); v.minimumHeight = dp(c, 48f)
        spacing(v, 8f, 10f)
        AnimGlue.attachPress(v)
    }

    /**
     * 液态玻璃的按钮（v2.7.3）：材质由 [material] 出 BUTTON 角色，三态都换一张材质 ——
     * 静息 / 按下压暗一档 / 禁用浅底。没有粗野那套「沉到投影上」的位移：柔和阴影的块跳 2dp
     * 会像没对齐，反馈只走「颜色变深 + [AnimGlue.attachPress] 的轻微缩放」。
     */
    private fun glassButton(v: TextView, fill: Int) {
        val c = v.context
        v.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), material(c, DISABLED, Ui.SurfaceRole.BUTTON))
            addState(intArrayOf(android.R.attr.state_pressed), material(c, Theme.mix(fill, INK, 0.12f), Ui.SurfaceRole.BUTTON))
            addState(intArrayOf(), material(c, fill, Ui.SurfaceRole.BUTTON))
        }
        v.stateListAnimator = null; v.elevation = 0f
        v.isAllCaps = false
        val fg = on(fill)
        val dis = Theme.argb(0x99, on(DISABLED))
        v.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(dis, fg)))
        v.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        v.textSize = 14.5f
        v.setPadding(dp(c, 18f), dp(c, 12f), dp(c, 18f), dp(c, 12f))
        v.minHeight = dp(c, 46f); v.minimumHeight = dp(c, 46f)
        spacing(v, 8f, 8f)
        AnimGlue.attachPress(v)
    }

    /**
     * 瑞士风格的按钮：直角 + 实心填色。三种按钮＝三档灰阶/红，不靠描边区分层次。
     *
     *  - 主按钮（[PRIMARY]）：信号红实心，**无描边**（这是整页唯一的红）。
     *  - 危险按钮（[DANGER]）：墨色实心，无描边 —— 这套语言只有一个红，危险靠文字说清楚。
     *  - 其余（次要，[CARD]）：白卡实心 + 1dp 墨线。白卡压在印刷纸上只有 1.12:1，不画这条线
     *    整块按钮就会消失。判据是「填色就是白卡」，和 [Ui.smallPill] 同一套规格 —— 两个入口
     *    必须长得一样，否则同一页的次要按钮一个有线一个没线。
     *
     * 和粗野按钮的三点不同：
     *  1. 没有投影，所以按下时不再「下沉到投影上」，改成**填色变深**表示按下。
     *  2. 四边等距（粗野版右下多留 4dp 给投影）。
     *  3. 字重保持 BOLD —— 瑞士风格的按钮字本来就是重的，这一条不用改。
     */
    private fun swissButton(v: TextView, fill: Int) {
        val c = v.context
        val edge = needsEdge(fill)
        v.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), plate(c, DISABLED, edge))
            addState(intArrayOf(android.R.attr.state_pressed), plate(c, pressFill(fill), edge))
            addState(intArrayOf(), plate(c, fill, edge))
        }
        v.stateListAnimator = null; v.elevation = 0f
        v.isAllCaps = false
        // 白卡底上的字用墨色本身（on(CARD) 在调色板配色下挑的是纯黑，和这套墨色差一档）；
        // 实心底（红 / 墨）仍按填色明暗挑对比度更高的那端。
        val fg = if (fill == CARD) INK else on(fill)
        val dis = Theme.argb(0x99, on(DISABLED))
        v.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(dis, fg)))
        v.typeface = Typeface.DEFAULT_BOLD
        v.textSize = 15f
        v.setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
        v.minHeight = dp(c, 48f); v.minimumHeight = dp(c, 48f)
        spacing(v, 6f, 8f)
        AnimGlue.attachPress(v)
    }

    fun field(e: EditText) {
        val c = e.context
        if (glass) {
            // 玻璃输入框：底用 surfaceAlt 槽（色板里给「输入 / 次级表面」的档），聚焦时往主色掺 22%
            // 做一个「聚焦有一抹主色」的提示 —— 玻璃不靠描边加粗聚焦，那是粗野 / 瑞士的语言。
            val alt = pal?.surfaceAlt
            val rest = if (alt != null && alt != pal!!.paper) op(alt) else Theme.mix(CARD, INK, 0.05f)
            e.background = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), material(c, DISABLED, Ui.SurfaceRole.BUTTON))
                addState(intArrayOf(android.R.attr.state_focused), material(c, Theme.mix(rest, MINT, 0.22f), Ui.SurfaceRole.BUTTON))
                addState(intArrayOf(), material(c, rest, Ui.SurfaceRole.BUTTON))
            }
            e.setTextColor(INK); e.setHintTextColor(HINT)
            e.setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
            spacing(e, 6f, 10f)
            return
        }
        if (sh.isLayered) {
            // Atelier 输入框：surfaceAlt 浅绿灰底 + 14dp 圆角 + 无描边；聚焦时**加一圈 1.5dp 薄荷绿**。
            // 用「底色区分」而不是「方框」：20dp 圆角的卡片里再套一个描边方框，会立刻出现三层边界。
            fun filed(bg: Int, stroke: Int?) = GradientDrawable().apply {
                setColor(bg); cornerRadius = dp(c, sh.buttonCornerDp).toFloat()
                stroke?.let { setStroke(dp(c, 1.5f), it) }
            }
            val rest = op(pal!!.surfaceAlt)
            e.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), filed(op(pal!!.card), MINT))
                addState(intArrayOf(), filed(rest, null))
            }
            e.setTextColor(INK); e.setHintTextColor(HINT)
            e.setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
            spacing(e, 6f, 10f)
            return
        }
        if (noShadow) {
            // 瑞士：只有下划线，没有完整的框。聚焦时底线从 shape.strokeDp（1dp）加粗到
            // shape.focusStrokeDp（2dp）并转成实心墨色 —— 这套语言里聚焦**不加颜色**，只加粗细。
            //
            // 原来用的是「透明填色 + setStroke」的 GradientDrawable：那是**整圈外框**，四条边都会画，
            // 正好是「只有下划线」的反面（渲染成一张表单格子）。现在换成只画底边一条线。
            //
            // 静息线用 HINT 而不是 0x55 的淡墨：这条线是输入框**唯一**的存在感（没有底色、没有边框），
            // 0x55 在纸底上只有 2.15:1，等于看不见；HINT 是色板里现成的次级档（浅 5.12 / 深 6.94）。
            val thin = Math.max(1, dp(c, sh.strokeDp))
            val focus = Math.max(thin, dp(c, sh.focusStrokeDp))
            e.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), UnderlineRule(INK, focus))
                addState(intArrayOf(), UnderlineRule(HINT, thin))
            }
            e.setTextColor(INK); e.setHintTextColor(HINT)
            e.setPadding(dp(c, 0f), dp(c, 10f), dp(c, 0f), dp(c, 10f))
            spacing(e, 6f, 10f)
            return
        }
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
        if (noShadow) {
            // 瑞士：左边一条**红色实心竖条** + 墨色粗体标题，没有底色块、没有边框、没有投影。
            // 这是整页里唯一出现信号红的地方之一（另一个是主按钮），多一个就不叫瑞士国际主义了。
            //
            // 原来这里把整个标题底铺成信号红再压墨色字 —— 那是「红色色块」，不是「红色竖条」：
            // 每个小节标题顶着一大块红，红色立刻变成底纹，字也压在红上（墨色对 0xE02B20 只有 4.08:1）。
            // 竖条改成 4dp 定宽的一条线，字仍然落在纸底上。
            text = s; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(INK)
            background = LeftBar(MINT, dp(ctx, 4f))   // MINT 在瑞士配色里就是信号红
            setPadding(dp(ctx, 12f), dp(ctx, 2f), dp(ctx, 0f), dp(ctx, 2f))   // 4dp 条 + 8dp 间隙
            layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply { topMargin = dp(ctx, 28f); bottomMargin = dp(ctx, 10f) }
            return@apply
        }
        if (sh.isLayered || glass) {
            // Atelier 小节标题：**左侧一道薄荷绿圆头短竖条** + 墨色 15f MEDIUM。
            // 不用色块底 —— 大面积薄荷绿块会吃掉页面上 25% 的主色配额（用户要求 60/25/10/5），
            // 而小节标题在长页面里会出现十几次，色块一多整页就变成「薄荷绿底纹」。
            text = s; textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            val bar = GradientDrawable().apply {
                setColor(MINT); cornerRadius = dp(ctx, 2f).toFloat()
            }
            background = bar
            setPadding(dp(ctx, 10f), dp(ctx, 3f), dp(ctx, 0f), dp(ctx, 3f))
            layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply {
                topMargin = dp(ctx, 26f); bottomMargin = dp(ctx, 10f)
            }
            return@apply
        }
        text = s; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(on(ROSE))
        background = box(ctx, ROSE, 3f)
        setPadding(dp(ctx, 12f), dp(ctx, 6f), dp(ctx, 15f), dp(ctx, 9f))
        layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply { topMargin = dp(ctx, 26f); bottomMargin = dp(ctx, 8f) }
    }

    /** 卡片底：白底黑边投影，内容自己留边 */
    fun card(v: View, fill: Int = CARD) {
        val c = v.context
        if (glass) {
            // 玻璃卡片：材质一次出齐（轻透明层 + 高光边 + 柔和阴影），不在这里再叠发丝线 ——
            // 材质自己带边，再画一圈会和它的高光边打架。四边等距 18dp，和 Atelier 同一口径。
            v.background = material(c, fill, Ui.SurfaceRole.CARD)
            v.setPadding(dp(c, 18f), dp(c, 16f), dp(c, 18f), dp(c, 16f))
            return
        }
        if (sh.isLayered) {
            // Atelier：卡片 20dp 圆角 + 发丝线 + 2dp 柔和投影，四边等距 18dp。
            // 发丝线统一走 [edge]（Theme.hairline），不再单独取 roseDeep —— 边色全库一个来源。
            v.background = box(c, fill, null).also { d ->
                (d as? LayerDrawable)?.let { ld ->
                    (ld.getDrawable(1) as? GradientDrawable)?.setStroke(
                        Math.max(1, dp(c, sh.strokeDp)), edge())
                }
                (d as? GradientDrawable)?.setStroke(Math.max(1, dp(c, sh.strokeDp)), edge())
            }
            v.setPadding(dp(c, 18f), dp(c, 16f), dp(c, 18f), dp(c, 16f))
            return
        }
        if (noShadow) {
            // 细线描边代替投影，四边等距 16dp —— 网格才是这套风格的骨架。
            // （原来的 14dp 上/下、右边 18dp 是给 4dp 实心投影让位的；没有投影还留着就不是网格了。）
            // 底走 [rect]：圆角与描边都取当前形状，瑞士是 0dp / 1dp，导入的自定义形状照它自己的数值。
            v.background = rect(c, fill, true)
            v.setPadding(dp(c, 16f), dp(c, 16f), dp(c, 16f), dp(c, 16f))
            return
        }
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

    /**
     * 瑞士的「按下」填色：默认往墨色方向压 18%（浅底变深，和规格里「按下变深」一致）。
     *
     * 唯一例外是**填色本身就是墨色**（危险按钮）：再往墨里混是零变化，按下去毫无反馈 ——
     * 那种底改成往纸色方向提 22%，观感是「按下去亮一档」。两种深浅配色都覆盖：
     * 深色主题的墨是浅色，这条规则照样把它往底纸方向拉开。
     */
    private fun pressFill(fill: Int): Int =
        if (Theme.contrast(fill, INK) < 1.15) Theme.mix(fill, PAPER, 0.22f) else Theme.mix(fill, INK, 0.18f)

    /**
     * v2.5 Mint Rose Atelier 的排版规则 —— 和瑞士那套的**目的是同一个**（没有黑框之后靠字重分层次），
     * 但具体做法不同：
     *
     *  - **不一律用墨色**：层级靠「墨色 / 提亮墨色 / hint」三档，而不是靠框线。
     *    Atelier 有 20dp 圆角的卡片帮忙分组了，字再全用一个色反而分不出主次。
     *  - **按钮走 [atelierButton]**（14dp 圆角、无描边）。
     *  - **开关 / 单选 / 进度条一律用主色薄荷**——它们是这套配色的「状态指示」，
     *    用户明确要求薄荷绿承担选中态与状态指示。
     *  - **不加字重一刀切**：只把 Switch / RadioButton 的字重保持 NORMAL（精致感来自轻），
     *    其余按调用方自己的设置来。
     */
    private fun atelierStyle(v: View) {
        when (v) {
            is EditText -> field(v)
            is Switch -> {
                v.setTextColor(INK); v.typeface = Typeface.DEFAULT
                v.thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(op(pal!!.card), op(pal!!.surfaceAlt)))
                v.trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MINT, op(Theme.mix(pal!!.paper, pal!!.ink, 0.16f))))
            }
            is RadioButton -> {
                v.setTextColor(INK); v.textSize = 15f
                v.buttonTintList = ColorStateList.valueOf(MINT)
            }
            is CompoundButton -> { v.setTextColor(INK); v.buttonTintList = ColorStateList.valueOf(MINT) }
            is Button -> button(v, when (v.tag) { PRIMARY -> MINT; DANGER -> op(pal!!.coral); else -> op(pal!!.surfaceAlt) })
            is TextView -> v.setTextColor(INK)
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(MINT)
                v.progressBackgroundTintList = ColorStateList.valueOf(op(Theme.mix(pal!!.paper, pal!!.ink, 0.16f)))
                v.thumbTintList = ColorStateList.valueOf(op(pal!!.roseDeep))
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /**
     * 液态玻璃的排版规则（v2.7.3）：材质归 [material]，这里只管字色 / 状态色 ——
     * 和 Atelier 同一套「无框之后靠墨色三档 + 主色承担状态」的分层逻辑，差别只在按钮危险色取
     * 色板的 coral 槽（玻璃配色的提醒色），不再借用 roseDeep（那是 Atelier 的玫瑰金配额）。
     */
    private fun glassStyle(v: View) {
        val p = pal ?: return run { styleFallback(v) }
        when (v) {
            is EditText -> field(v)
            is Switch -> {
                v.setTextColor(INK); v.typeface = Typeface.DEFAULT
                v.thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(CARD, MUTED))
                v.trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MINT, Theme.argb(0x33, INK)))
            }
            is RadioButton -> { v.setTextColor(INK); v.textSize = 15f; v.buttonTintList = ColorStateList.valueOf(MINT) }
            is CompoundButton -> { v.setTextColor(INK); v.buttonTintList = ColorStateList.valueOf(MINT) }
            is Button -> button(v, when (v.tag) { PRIMARY -> MINT; DANGER -> op(p.coral); else -> CARD })
            is TextView -> v.setTextColor(INK)
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(MINT)
                v.progressBackgroundTintList = ColorStateList.valueOf(MUTED)
                v.thumbTintList = ColorStateList.valueOf(INK)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /** 玻璃色板意外缺失时的兜底：退回粗野排版规则（不会走到，glass 主题必然带 palette） */
    private fun styleFallback(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /**
     * v2.4 瑞士风格的排版规则（单独抽出来，因为它是**形状之外**的第二套语言）：
     *
     *  - **字重对比**代替描边：标题用 BOLD，正文用 NORMAL。粗野风格是全 BOLD + 黑框，
     *    瑞士风格没有框，层次只能靠字重和字号拉开。
     *  - **不加字距**（letterSpacing 保持 0）：Helvetica 原样才是瑞士的，加宽会变成时尚杂志那一路。
     *  - 字色是**墨色 + 灰阶**两档，不是一律实心墨：调用方已经用半透明墨色表达的次级文字
     *    （Ui.TX2 / TX3）原样保留，那是这套语言里唯一能做层次的东西；其余才拉回墨色。
     *  - 只有一个信号红：主按钮。危险按钮走墨色，进度条拇指走墨色（不再借副色红）。
     *
     * 不碰字号（字号由各页面自己的排版决定，统一改会打架）。
     */
    private fun swissStyle(v: View) {
        when (v) {
            is EditText -> field(v)
            is Switch -> {
                v.setTextColor(INK); v.typeface = Typeface.DEFAULT
                v.thumbTintList = thumbTint(); v.trackTintList = trackTint()
            }
            is RadioButton -> { v.setTextColor(INK); v.buttonTintList = checkTint(); v.textSize = 15f }
            is CompoundButton -> { v.setTextColor(INK); v.buttonTintList = checkTint() }
            // 次要按钮 = 白卡 + 1dp 墨线（见 [swissButton] 的 edge 判据）：白卡压在印刷纸上
            // 只有 1.12:1，没有那条线整块按钮就消失了。
            // 危险按钮不用 ROSE（= 色板副色，在瑞士里是第二支红）：这套语言只允许一个信号红，
            // 「危险」本来就靠按钮文字说清楚（项目一贯「含义靠文字」），这里用实心墨色。
            is Button -> button(v, when (v.tag) { PRIMARY -> MINT; DANGER -> INK; else -> CARD })
            is TextView -> {
                // 瑞士靠**灰阶**分层次：次级文字（Ui.TX2 / TX3 的半透明墨色，深浅两版都在 6.4:1 以上）
                // 和提示灰（Brut.HINT，浅 5.12 / 深 6.94）都是这套语言里唯一能做层级的档位，原样保留 ——
                // 一刀切成实心墨，正文和说明行就没主次了。平台默认黑 / 别的配色留下的彩色字才拉回墨色
                // （深色瑞士下这一步是必须的，否则黑字压黑底）。
                val cur = v.currentTextColor
                val tone = cur and 0xFFFFFF
                val gray = (cur ushr 24) in 1..254 && tone == (INK and 0xFFFFFF)   // 半透明墨色
                if (!gray && cur != HINT) v.setTextColor(INK)
            }
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(INK)
                v.progressBackgroundTintList = ColorStateList.valueOf(MUTED)
                // 进度条拇指不能用 ROSE_DEEP：那是「玫瑰金深一档」，在瑞士色板里等于副色红
                // （0xB31F17 / 0xD62B20）—— 页面上凭空多出第二块红。瑞士只有一个信号红，拇指也用墨色。
                v.thumbTintList = ColorStateList.valueOf(INK)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /** 递归刷皮肤。控件已经加进父布局后再调（按钮的外边距要改 LayoutParams） */
    fun style(v: View) {
        if (v.tag == KEEP) return
        if (glass) { glassStyle(v); return }               // v2.7.3 玻璃材质 → 字色 / 状态色规则
        if (sh.isLayered) { atelierStyle(v); return }            // v2.5 分层圆角 → Atelier 排版规则
        if (square) { swissStyle(v); return }                     // 瑞士语言（直角）→ 走另一套排版规则
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
        // 下面这个监听算的是与 BottomNav.attach 共用的那一份（它挂底栏时会把监听换成同一个公式，
        // 因为底栏垫高必须落在 page 上，而覆盖监听会把这里的 top / 左右一起丢掉）。
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(base[0] + ins.systemWindowInsetLeft, base[1] + ins.systemWindowInsetTop, base[2] + ins.systemWindowInsetRight, base[3] + ins.systemWindowInsetBottom)
            ins
        }
        // 内容滚到状态栏那条就裁掉（状态栏是透明的，不裁会和时间、电量叠在一起）
        (root as? ViewGroup)?.clipToPadding = true
        root.requestApplyInsets()
    }

    /**
     * 小标签（分类名、字段名那种）。
     *
     * 瑞士国际主义的标志之一就是**全大写 + 加宽字距**的小标签：它靠这种「微小的排版噪音」
     * 在纯黑白页面里区分层级，而不是靠颜色或边框。Müller-Brockmann 那套网格系统里，
     * 大写小标签和正文之间的字号差 + 字距差，就是全部的层次信息。
     *
     * 其它配色下保持原样 —— 中文没有「大写」，硬转只会得到一堆拉丁字母变体，加字距反而更松。
     */
    fun label(ctx: Context, s: String, sizeSp: Float = 11f, color: Int = HINT): TextView = TextView(ctx).apply {
        tag = KEEP
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (sh.isLayered || glass) {
            // Atelier 小标签：不加字距、字重 MEDIUM、色用 hint。
            // 瑞士靠「大写+字距」制造排版噪音，Atelier 靠「字号差 + 颜色档」——
            // 中文没有大写，硬加字距只会显得松散。
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            letterSpacing = 0.02f
        } else if (noShadow) {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f   // 0.12em：再宽就散了，再窄看不出是「标签」
        }
    }
}

/**
 * 只画**底边一条线**的 drawable：瑞士输入框的「下划线」。
 *
 * 为什么不用 GradientDrawable：它的 setStroke 是整圈外框，透明填色也照样四条边全画，
 * 于是「只有下划线」会变成一张四边封口的表单格子。这里只落一条底边，粗细由外面按 shape 传。
 */
private class UnderlineRule(private val lineColor: Int, private val strokePx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = lineColor }
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), (b.bottom - strokePx).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * 只画**左侧一条竖条**的 drawable：瑞士小节标题的红色竖条。
 *
 * 同理不能拿 GradientDrawable 的填色充当：那会把整块标题铺成红色。这里只画左边 [barPx] 宽的一条，
 * 高度跟着视图走，文字区留白由调用方的 padding 决定。
 */
private class LeftBar(private val barColor: Int, private val barPx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = barColor }
    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + barPx).toFloat(), b.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
