package com.chun.lingzhu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页的外皮：浅灰页底 + 白色分组卡片 + 行（图标 · 标题 · 灰色当前值 · ›）。
 *
 * 颜色全部取 [Brut] 的当前色板（PAPER / CARD / INK / HINT / MINT / ROSE / SAND）再用 [Theme.mix] 派生，
 * 不写死任何微信色；七套外观和深色变体都跟着走。用户是灰度屏：状态靠文字和形状（✓ ✗ ›、开关拇指位置），
 * 不靠颜色。
 *
 * ## 瑞士国际主义（swiss / swiss_dark）下不是换色，是换形状 + 排版
 * 「白底圆角卡片 + 彩色圆角图标」是微信设置页的语言；瑞士那套要的是网格、细线、留白：
 *  - 页底 = 印刷纸 [Brut.PAPER]（不再压一层灰 —— 层次靠线，不靠底色）；
 *  - 卡片 = [Brut.CARD] 直角 + **1dp 墨线框**（线代替投影），内边距四边等距、不为投影留位；
 *  - 分隔线、输入框都跟着换：输入框只剩一条底线（聚焦 2dp 墨线 / 禁用淡线）；
 *  - 小节标题 = 信号红竖条 + 墨色粗体（与 [Brut.heading] 的瑞士分支同一套说法）；
 *  - 行首图标不再有彩色圆角块，直接画墨色线稿（见 [SettingIcon]）。
 * 判据只看配色自报的 [Theme.noShadowStyle]（只有 swiss / swiss_dark 置 true），不猜 id ——
 * 渲染层拿不到用户存的 id，以后加同族配色也不必再改这里。用户显式导入的形状
 * （见 [Prefs.effectiveShape]）照旧生效：圆角 / 描边粗细仍读 [Ui.shape]，这套分支只负责
 * 「纸底 / 卡片 / 线 / 图标」长什么样。
 *
 * 用法与 [Brut.style] 相同：控件按平常写法建好，最后 [style] 刷一遍；tag = [Brut.KEEP] 的子树不刷
 * （唯一例外：瑞士下的 EditText —— 那套风格里输入框只能有下划线，见 [style] 的说明）。
 * 只给设置页用 —— 首次配置引导 / 其它页面仍是原来的 Brut 皮肤。
 */
object WxSkin {
    private fun dp(ctx: Context, v: Float) = Ui.dp(ctx, v)

    private val darkPaper: Boolean get() = Theme.luminance(Brut.PAPER) < 0.4

    /**
     * 当前是不是瑞士那套**配色语言**（黑白 + 单一信号红 / 纸底 + 墨线 / 无投影）。
     *
     * 判据只有一条：配色自报 [Theme.noShadowStyle]（只有 swiss / swiss_dark 置 true）——
     * 这就是 Theme.kt 里那条「配色自己声明，不让渲染层猜 id」的约定（渲染层也拿不到用户存的 id）。
     *
     * **故意不掺形状判断**：用户显式导入的形状（[Prefs.effectiveShape]，`shapeSet` 一置就锁定）必须照旧生效，
     * 圆角 / 线宽在本文件里仍然逐处读 [Ui.shape]。这个标志只回答「纸底用哪张、卡片要不要线框、
     * 图标要不要色块」这几个问题，形状一概不碰 —— 于是「瑞士配色 + 自定义 8dp 圆角 / 3dp 线」
     * 会得到「印刷纸 + 带圆角的细线框」，而不是被拽回默认。
     */
    private val swiss: Boolean get() = Ui.theme.noShadowStyle

    /**
     * 液态玻璃（v2.7.3）：判据同 [Brut.glass]——只看配色自报 [Theme.isGlassMaterial]。
     * 玻璃下卡片 / 输入框 / 行图标改走 [Ui.material]，发丝边统一走 [Brut.edge]。
     */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial


    /**
     * 页底。瑞士：直接铺印刷纸 [Brut.PAPER]（0xF2F2F0 / 0x0E0E0E）——
     * 白卡片靠 1dp 墨线框立起来，不靠「把纸压暗一档」的假层次。
     * 其余外观照旧：浅色主题比纸色压暗一点（让白卡浮出来）；深色主题直接用纸色。
     */
    val PAGE: Int get() = when {
        glass || swiss || darkPaper -> Brut.PAPER   // 玻璃：页底就是纸色，透明层在卡片上才透出来
        else -> 0xFF000000.toInt() or Theme.mix(Brut.PAPER, Brut.INK, 0.05f)
    }
    /** 卡片底。瑞士 = 色板里的 card（白 / 0x1C1C1C），深色也不额外提亮 —— 层次交给线框 */
    val CARD: Int get() = when {
        glass -> Brut.CARD                         // 玻璃：卡片底就是 palette.card，材质透明层由 Ui.material 出
        swiss -> Brut.CARD
        darkPaper -> 0xFF000000.toInt() or Theme.mix(Brut.PAPER, 0xFFFFFF, 0.09f)
        else -> Brut.CARD
    }
    /** 卡片内的细分隔线。瑞士没有投影也没有底色分层，线要实一点才读得出来（仍是发丝级） */
    val LINE: Int get() = Brut.edge()              // 全库统一发丝边（Theme.hairline），不再单独写 0x1F/0x33
    /** 输入框 / 卡内小块的浅底。瑞士的输入框改走底线（见 [field]），这个色只留给卡内嵌入式小块 */
    val FIELD: Int get() = if (glass) Brut.PAPER else 0xFF000000.toInt() or Theme.mix(CARD, Brut.INK, 0.06f)

    /**
     * 圆角块。v2.4：瑞士风格下圆角一律归零 —— 传进来的 radiusDp 被忽略。
     *
     * 为什么在这里拦而不是改所有调用点：圆角数字散落在几十处（卡片 12dp、色卡 10dp、
     * 标签 8dp…），逐个加分情况既容易漏又难维护；而「瑞士风格 = 没有圆角」是一条全局规则，
     * 收在唯一的出口处最省事，也保证不会漏掉某一种卡片。
     */
    fun rounded(fill: Int, ctx: Context, radiusDp: Float,
                role: Ui.SurfaceRole = Ui.SurfaceRole.CARD): GradientDrawable = GradientDrawable().apply {
        // 圆角统一走 [Ui.cornerDp]：瑞士→0、Atelier→cardCornerDp、玻璃→card24/button16/chip999/sheet28，
        // 普通形状回落 radiusDp。一个出口管全部分层形状，不再各处 if cornerDp<=0。
        setColor(fill)
        cornerRadius = dp(ctx, Ui.cornerDp(role, radiusDp)).toFloat()
    }

    /** 按压反馈：系统 ripple（不自己画状态机） */
    fun ripple(ctx: Context, content: Drawable?, radiusDp: Float = 0f): Drawable =
        RippleDrawable(ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)), content,
            if (radiusDp > 0f) rounded(0xFFFFFFFF.toInt(), ctx, radiusDp) else GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()) })

    /**
     * 一张分组卡片（内容自己往里加）。
     *
     *  - 微信式：白底 12dp 圆角，左右 16dp / 上下 6·8dp 内边距；
     *  - 瑞士：CARD 底 + **[Ui.shape] 的细线框**（线代替投影），内边距**四边等距 16dp**。
     *    微信版上下薄、右边多是为投影和圆角让位；换成线框后不留投影位，四条边只该有一个值，
     *    否则内容看起来是歪的。这就是规格里的「卡片细线框、四边等距」。
     *    圆角仍听 [Ui.shape]：瑞士 0dp 直角，用户显式导入的形状（比如 8dp）在这里也照旧生效。
     */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        when {
            glass -> {
                // 玻璃卡片：材质一次出齐，不在这里叠发丝线（材质自带高光边）。
                background = Ui.material(ctx, CARD, Ui.SurfaceRole.CARD, 16f, Ui.theme)
                setPadding(dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 16f))
            }
            swiss -> {
                background = rounded(CARD, ctx, 12f).apply {
                    setStroke(maxOf(1, dp(ctx, Ui.shape.strokeDp)), Brut.INK)
                }
                val p = dp(ctx, 16f)   // 四边等距：线框不留投影位
                setPadding(p, p, p, p)
            }
            else -> {
                // 微信式：白卡浮在灰底上。分层形状（Atelier）补一道 [Brut.edge] 发丝边 ——
                // 那套语言里卡片靠边框定位；普通形状保持无框（靠底色差浮起来）。
                val bg = rounded(CARD, ctx, 12f)
                if (Ui.shape.isLayered) bg.setStroke(maxOf(1, dp(ctx, Ui.shape.strokeDp)), Brut.edge())
                background = bg
                setPadding(dp(ctx, 16f), dp(ctx, 6f), dp(ctx, 16f), dp(ctx, 8f))
            }
        }
    }

    /** 卡片上方的小号灰色分组标题（微信设置页那种） */
    fun caption(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = 13f; setTextColor(Brut.HINT); tag = Brut.KEEP
        setPadding(dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 6f))
    }

    /**
     * 卡片里的小节标题（替代原来的玫瑰色积木块标题）。
     *
     * 瑞士：**左缘一道信号红竖条 + 墨色粗体**，没有底色块、没有边框、没有投影 ——
     * 竖条是这套布局里唯一允许出现红色的地方之一（另两处是主按钮和底栏选中胶囊）。
     * 用 [SideBar] 只画左缘那一条，而不是把 TextView 的整个背景填红：填红就成了「红底黑字的色块」，
     * 那正是粗野风格的玫瑰色积木块，与「细线 + 留白」的瑞士网格相反（黑字压在正红上对比度也只有 4.0）。
     *
     * 竖条宽度固定 3dp、文字左边距 10dp：竖条是标点、不是容器，宽了就成了色条。
     * 上下留白改由 layoutParams 的外边距给 —— 有背景的 View 里再塞 padding，竖条会被一起拉长。
     */
    fun subhead(ctx: Context, s: String): TextView = TextView(ctx).apply {
        text = s; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); tag = Brut.KEEP
        if (swiss) {
            background = SideBar(dp(ctx, 3f), Brut.MINT)
            setPadding(dp(ctx, 10f), dp(ctx, 3f), 0, dp(ctx, 3f))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(ctx, 20f); bottomMargin = dp(ctx, 8f)
            }
        } else {
            setPadding(0, dp(ctx, 14f), 0, dp(ctx, 4f))
        }
    }

    /**
     * 一条细分隔线（行与行之间；左缘从文字开始）。
     * 瑞士没有投影和底色分层，线是唯一的分界手段 —— 加粗到 1dp（0.6dp 在 3x 屏上只有 1.8px，几乎看不见）。
     */
    fun divider(ctx: Context, startDp: Float = 0f): View = View(ctx).apply {
        setBackgroundColor(LINE); tag = Brut.KEEP
        // 线粗：瑞士 / 玻璃（无硬投影分层）用 1dp，其余 0.6dp 发丝级。
        layoutParams = LinearLayout.LayoutParams(-1, maxOf(1, dp(ctx, if (swiss || glass) 1f else 0.6f)))
            .apply { marginStart = dp(ctx, startDp) }
    }

    /**
     * 一行「图标 · 标题 · 灰色当前值 · ›」（首页的分类入口 / 常用入口）。
     * 返回行本身和摘要 TextView（摘要由调用方随时刷新）。整行 ≥ 56dp 触达。
     *
     * 瑞士下的留白自己撑：首页那两张卡是 `card().setPadding(0,0,0,0)` 再往里塞行的
     * （见 MainActivity 的 hubCard），行里不加就是第一行直接贴在 1dp 线框上。
     * 所以上下 8 → 12dp，右端 12 → 16dp（与左端同值，线框内四边等距）。
     * 微信式保持原值（8 / 12），逐位不变 —— 那版卡片自己有上下内边距。
     */
    fun row(ctx: Context, icon: String?, title: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56f)
            val v = dp(ctx, if (swiss) 12f else 8f)
            setPadding(dp(ctx, 16f), v, dp(ctx, if (swiss) 16f else 12f), v)
            isClickable = true; isFocusable = true
            background = ripple(ctx, null)
            tag = Brut.KEEP
            setOnClickListener { onClick() }
        }
        if (icon != null) r.addView(SettingIcon(ctx, icon), LinearLayout.LayoutParams(dp(ctx, 30f), dp(ctx, 30f)).apply { marginEnd = dp(ctx, 14f) })
        r.addView(TextView(ctx).apply {
            text = title; textSize = 16f; setTextColor(Brut.INK); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2))
        val sum = TextView(ctx).apply {
            textSize = 13f; setTextColor(Brut.HINT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12f), 0, dp(ctx, 4f), 0)
        }
        r.addView(sum, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(View(ctx).apply { background = Chevron(Brut.HINT, dp(ctx, 1.6f).toFloat()) }, LinearLayout.LayoutParams(dp(ctx, 10f), dp(ctx, 16f)))
        r.contentDescription = title
        return r to sum
    }

    /** 递归刷皮肤（设置页专用）。控件已经加进父布局后再调 */
    fun style(v: View) {
        // KEEP = 「别刷我这棵子树」，颜色 / 背景由调用方自己负责。
        // 瑞士下的唯一例外是输入框：这套风格里输入框**只有下划线**一种合法形态，而搜索框那类控件
        // 自己画的是「浅底圆角块」（SettingsSearchView 的 field 就是 KEEP + 灰底方框）。
        // 留着它就是整页中间孤零零一个方框表格，正好是这轮要清掉的缺口。所以瑞士下仍给
        // EditText 刷底线（只动背景 / 字色 / 间距，不碰文字内容与监听器）。
        // 其它外观保留原语义：KEEP 一律原样跳过，逐位不变。
        if (v.tag == Brut.KEEP) {
            if (swiss && v is EditText) field(v)
            return
        }
        val c = v.context
        when (v) {
            is EditText -> field(v)
            is Switch -> {
                v.setTextColor(Brut.INK); v.typeface = Typeface.DEFAULT
                v.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Brut.INK, CARD))
                v.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Brut.MINT, Theme.argb(0x55, Brut.INK)))
                v.minHeight = dp(c, 52f)
            }
            is RadioButton -> { v.setTextColor(Brut.INK); v.buttonTintList = ColorStateList.valueOf(Brut.INK); v.textSize = 15f; v.minHeight = dp(c, 44f) }
            is CompoundButton -> { v.setTextColor(Brut.INK); v.buttonTintList = ColorStateList.valueOf(Brut.INK); v.minHeight = dp(c, 44f) }
            is Button -> button(v)
            is TextView -> v.setTextColor(Brut.INK)
            is SeekBar -> {
                v.progressTintList = ColorStateList.valueOf(Brut.INK)
                v.progressBackgroundTintList = ColorStateList.valueOf(Theme.argb(0x40, Brut.INK))
                v.thumbTintList = ColorStateList.valueOf(Brut.INK)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) style(v.getChildAt(i))
    }

    /**
     * 输入框。
     *
     *  - 微信式：FIELD 浅底 + 8dp 圆角（和其他卡片内的色块同一族）；
     *  - 瑞士：**只有一条下划线**，没有框、没有底色 —— 完整方框会把这页变成一张表单表格。
     *    三种状态：常态 1dp 淡墨线 / 聚焦 2dp 实墨线 / 禁用 1dp 几乎看不见的线 + 灰字。
     *    线与字都走状态列表，所以「能不能编辑」这件事在灰度屏上也读得出来。
     *
     * 边界值都取 [Ui.shape] 的 strokeDp 为基数（瑞士 1dp → 聚焦 [Shape.focusStrokeDp] 2dp），
     * 用户显式导入的形状改粗细时，这里的线也跟着变，不会出现「卡片 3dp 线 + 输入框 1dp 线」的错位。
     *
     * 左右 padding 归零：下划线和左侧标题的左缘对齐（网格的竖线只有一条），
     * 微信式保留 12dp 是因为有底色块，文字不能贴着色块边缘。
     */
    private fun field(e: EditText) {
        val c = e.context
        if (glass) {
            // 玻璃输入框：材质由 Ui.material 出 BUTTON 角色，聚焦时往主色掺一抹。
            val rest = FIELD
            e.background = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), Ui.material(c, Brut.DISABLED, Ui.SurfaceRole.BUTTON, 14f, Ui.theme))
                addState(intArrayOf(android.R.attr.state_focused), Ui.material(c, Theme.mix(rest, Brut.MINT, 0.22f), Ui.SurfaceRole.BUTTON, 14f, Ui.theme))
                addState(intArrayOf(), Ui.material(c, rest, Ui.SurfaceRole.BUTTON, 14f, Ui.theme))
            }
            e.setTextColor(Brut.INK); e.setHintTextColor(Brut.HINT)
            e.setPadding(dp(c, 14f), dp(c, 10f), dp(c, 14f), dp(c, 10f))
            margins(e, 4f, 6f)
            return
        }
        if (!swiss) {
            e.background = rounded(FIELD, c, 8f, Ui.SurfaceRole.BUTTON)
            e.setTextColor(Brut.INK); e.setHintTextColor(Brut.HINT)
            e.setPadding(dp(c, 12f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
            margins(e, 4f, 6f)
            return
        }
        val hair = maxOf(1, dp(c, Ui.shape.strokeDp))
        val focus = maxOf(hair, dp(c, if (Ui.shape.focusStrokeDp >= Ui.shape.strokeDp) Ui.shape.focusStrokeDp else 2f))
        e.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), Underline(hair, Theme.argb(0x26, Brut.INK)))
            addState(intArrayOf(android.R.attr.state_focused), Underline(focus, Brut.INK))
            addState(intArrayOf(), Underline(hair, Theme.argb(0x55, Brut.INK)))
        }
        e.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Brut.HINT, Brut.INK)))
        e.setHintTextColor(Brut.HINT)
        e.setPadding(dp(c, 0f), dp(c, 10f), dp(c, 0f), dp(c, 10f))
        // 下划线要离下一行远一点：它现在是这个输入框唯一的「边」，贴着别人就分不清是谁的线
        margins(e, 4f, 10f)
    }

    /**
     * 按钮三种长相：
     *  - 主按钮（tag [Brut.PRIMARY]）：主色实心块，字居中加粗；
     *  - 横排里的按钮（父布局是水平 LinearLayout：方案 chip / 并排的两个按钮）：浅底小块；
     *  - 其余：整行「文字 ……… ›」，透明底 + ripple（微信设置行）。危险操作（tag [Brut.DANGER]）字前加 ⚠、加粗。
     *
     * 瑞士分支（[swiss]）不是「同一套长相换个颜色」，而是把三处反馈一起换掉：
     *  - 主按钮：直角实心信号红，**按下填色变深**（ripple 那层 13% 墨是叠亮，红色上变成脏橘，
     *    而且「按下变深」是这套风格的明确规则，见 Brut 的瑞士按钮）；
     *  - 横排小块：直角 + **1dp 墨线框**，按下淡墨底（没有投影可以「沉下去」，只能靠底色和线）；
     *  - 整行：仍是「文字 …… ›」，只是箭头换成 1dp 直角感的细折线（[Chevron] 本身就是细线，无需改）。
     */
    private fun button(v: Button) {
        val c = v.context
        v.isAllCaps = false; v.stateListAnimator = null; v.elevation = 0f
        v.textSize = 15f
        val parent = v.parent as? LinearLayout
        val inRow = parent != null && parent.orientation == LinearLayout.HORIZONTAL
        if (glass) { glassButton(v, inRow); return }
        if (swiss) { swissButton(v, inRow); return }
        when {
            v.tag == Brut.PRIMARY -> {
                v.background = ripple(c, StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), rounded(Brut.DISABLED, c, 10f))
                    addState(intArrayOf(), rounded(Brut.MINT, c, 10f))
                }, 10f)
                v.setTextColor(Brut.on(Brut.MINT)); v.typeface = Typeface.DEFAULT_BOLD
                v.gravity = Gravity.CENTER
                v.minHeight = dp(c, 48f); v.minimumHeight = dp(c, 48f)
                v.setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 8f))
                margins(v, 8f, 8f)
            }
            inRow -> {
                v.background = ripple(c, rounded(FIELD, c, 10f), 10f)
                v.setTextColor(Brut.INK); v.typeface = Typeface.DEFAULT
                v.minHeight = dp(c, 44f); v.minimumHeight = dp(c, 44f)
                v.setPadding(dp(c, 12f), dp(c, 6f), dp(c, 12f), dp(c, 6f))
                margins(v, 6f, 6f)
            }
            else -> rowButton(v)
        }
    }

    /**
     * 瑞士按钮的三种长相（对应 [button] 的三个分支）。
     *
     * 主按钮按下变深用 [Theme.mix] 往墨色掺 18% —— 和 [Brut] 的瑞士按钮同一个数，
     * 两处按钮在同一屏里同时出现时深浅一致。
     */
    private fun swissButton(v: Button, inRow: Boolean) {
        val c = v.context
        when {
            v.tag == Brut.PRIMARY -> {
                fun solid(fill: Int) = rounded(fill, c, 10f)   // [rounded] 在 0dp 形状下自己把圆角归零
                v.background = StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), solid(Brut.DISABLED))
                    addState(intArrayOf(android.R.attr.state_pressed), solid(Theme.mix(Brut.MINT, Brut.INK, 0.18f)))
                    addState(intArrayOf(), solid(Brut.MINT))
                }
                v.setTextColor(Brut.on(Brut.MINT)); v.typeface = Typeface.DEFAULT_BOLD
                v.gravity = Gravity.CENTER
                v.minHeight = dp(c, 48f); v.minimumHeight = dp(c, 48f)
                // 四边等距：没有投影要在右下留位
                v.setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
                margins(v, 8f, 8f)
            }
            inRow -> {
                val hair = maxOf(1, dp(c, Ui.shape.strokeDp))
                fun chip(fill: Int) = rounded(fill, c, 10f).apply { setStroke(hair, Brut.INK) }
                v.background = StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), chip(Brut.DISABLED))
                    addState(intArrayOf(android.R.attr.state_pressed), chip(Theme.argb(0x1F, Brut.INK)))
                    addState(intArrayOf(), chip(0x00000000))
                }
                v.setTextColor(ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Brut.HINT, Brut.INK)))
                v.typeface = Typeface.DEFAULT
                v.minHeight = dp(c, 44f); v.minimumHeight = dp(c, 44f)
                v.setPadding(dp(c, 12f), dp(c, 6f), dp(c, 12f), dp(c, 6f))
                margins(v, 6f, 6f)
            }
            else -> rowButton(v)
        }
    }

    /**
     * 液态玻璃的按钮（v2.7.3）：材质由 [Ui.material] 出 BUTTON 角色，叠加系统 ripple。
     * 主按钮 = 主色材质 + ripple；横排小块 = FIELD 浅底材质 + ripple；整行仍走 [rowButton]（透明 + ripple + ›）。
     */
    private fun glassButton(v: Button, inRow: Boolean) {
        val c = v.context
        when {
            v.tag == Brut.PRIMARY -> {
                v.background = ripple(c, StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), Ui.material(c, Brut.DISABLED, Ui.SurfaceRole.BUTTON, 14f, Ui.theme))
                    addState(intArrayOf(), Ui.material(c, Brut.MINT, Ui.SurfaceRole.BUTTON, 14f, Ui.theme))
                }, 14f)
                v.setTextColor(Brut.on(Brut.MINT)); v.typeface = Typeface.DEFAULT_BOLD
                v.gravity = Gravity.CENTER
                v.minHeight = dp(c, 48f); v.minimumHeight = dp(c, 48f)
                v.setPadding(dp(c, 16f), dp(c, 8f), dp(c, 16f), dp(c, 8f))
                margins(v, 8f, 8f)
            }
            inRow -> {
                v.background = ripple(c, Ui.material(c, FIELD, Ui.SurfaceRole.BUTTON, 14f, Ui.theme), 14f)
                v.setTextColor(Brut.INK); v.typeface = Typeface.DEFAULT
                v.minHeight = dp(c, 44f); v.minimumHeight = dp(c, 44f)
                v.setPadding(dp(c, 12f), dp(c, 6f), dp(c, 12f), dp(c, 6f))
                margins(v, 6f, 6f)
            }
            else -> rowButton(v)
        }
    }

    /** 整行「文字 ……… ›」：透明底 + ripple，右端一枚细折线箭头 */
    private fun rowButton(v: Button) {
        val c = v.context
        v.background = ripple(c, null)
        v.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        v.typeface = if (v.tag == Brut.DANGER) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        v.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Brut.HINT, Brut.INK)))
        if (v.tag == Brut.DANGER && !v.text.startsWith("⚠")) v.text = "⚠ " + v.text
        v.minHeight = dp(c, 50f); v.minimumHeight = dp(c, 50f)
        v.setPadding(0, dp(c, 6f), 0, dp(c, 6f))
        val chev = Chevron(Brut.HINT, dp(c, 1.6f).toFloat()).apply { setBounds(0, 0, dp(c, 10f), dp(c, 16f)) }
        v.setCompoundDrawablesRelative(null, null, chev, null)
        v.compoundDrawablePadding = dp(c, 10f)
    }

    private fun margins(v: View, top: Float, bottom: Float) {
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dp(v.context, top); it.bottomMargin = dp(v.context, bottom)
            v.layoutParams = it
        }
    }
}

/** 行尾那个「›」：一笔折线，按 bounds 居中画（不靠字体，大字号下也不变形） */
class Chevron(private val inkColor: Int, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = inkColor
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = minOf(b.height().toFloat(), b.width() * 1.6f) * 0.62f
        val w = h * 0.5f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        path.reset()
        path.moveTo(cx - w / 2, cy - h / 2); path.lineTo(cx + w / 2, cy); path.lineTo(cx - w / 2, cy + h / 2)
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}

/**
 * 瑞士输入框的「只有一条下划线」：在 bounds **底边**画一条实心横线，其余什么都不画。
 *
 * 为什么不用 [GradientDrawable.setStroke]：那是四边闭合的框。把左右和上边的颜色设成透明也做不到 ——
 * GradientDrawable 的描边是一条闭合路径，透明那三边在抗锯齿边缘仍会留一圈淡痕。
 * 自绘一条 rect 是唯一干净的写法（和 [Chevron] 一样，不引图标库、不建 shape xml）。
 *
 * 线宽用像素传入（调用方已按 dp 换算），因为这里是纯绘制，没有 Context。
 */
class Underline(private val strokePx: Int, private val inkColor: Int) : Drawable() {
    private val paint = Paint().apply { style = Paint.Style.FILL; color = inkColor }
    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = maxOf(1, strokePx)
        // 线贴着底边内沿画：底边之外没有空间，画出去会被裁掉半条
        canvas.drawRect(b.left.toFloat(), (b.bottom - h).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}

/**
 * 瑞士小节标题左缘的那道竖条：只在 bounds 的**左边缘**画一条实心窄条，其余透明。
 *
 * 和 [Underline] 同一个理由：要的是「一条线」，不是「一个框」或「一整块底」。
 * 高度跟着 TextView 的实际高度走（含字高与上下 padding），所以字多大条就多高，不需要在调用处算。
 */
class SideBar(private val widthPx: Int, private val inkColor: Int) : Drawable() {
    private val paint = Paint().apply { style = Paint.Style.FILL; color = inkColor }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = minOf(maxOf(1, widthPx), b.width())
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), (b.left + w).toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}

/**
 * 设置行左侧的小图标：一个用 Path 画的简单图形（不用 emoji、不引图标库）。
 *
 * ## 两种长相
 *  - 微信式：**圆角方块底**（24% 圆角）+ 底上反色的图形。底色按「这一行是做事的，还是调设置的」二分：
 *    薄荷绿 = 行为 / 内容入口（启动、学习、AI、数据、历史），玫瑰金 = 外观 / 呈现相关的设置
 *    （小球外观、配色、权限与关于这类元信息），其余用米黄。早先是三家轮换，同一张卡里
 *    玫瑰粉和薄荷绿混着排看起来像随机 —— 用户看不出颜色在表达什么。
 *  - 瑞士式：**没有色块、没有圆角、没有投影**，图形直接以墨色线稿画在网格上（[Theme.noShadowStyle]）。
 *    这套风格只有「黑白 + 一个信号红」，而信号红是留给主按钮 / 小节标题竖条 / 选中态的配额 ——
 *    十几行图标各占一块红色就把它稀释成装饰色了。区分靠**形状**（书 / 钟 / 盾 / 齿轮），
 *    不靠色块，这也正是灰度屏上唯一可靠的信息。
 *
 * 布局一个数都不动（行里仍给 30dp 方框、图标的 margin / 文字起点 / 摘要右对齐全部照旧）：
 * 去掉色块只是「不画那块底」，瑞士分支再把图形按画布放大 1.15 倍补足留白 —— 放大只发生在
 * 20dp 出头的那块画布内部，尺寸仍落在 30dp 框里，不会顶到文字。
 */
class SettingIcon(ctx: Context, private val kind: String) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** 构造时的线宽（1.6dp）。瑞士分支在 onDraw 里乘 0.625 压到 1dp，非瑞士原样使用 */
    private val baseStroke = Ui.dp(ctx, 1.6f).toFloat()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = baseStroke
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    /** 复用的临时矩形（同 [PersonaIcon]：onDraw 每帧 set + draw，零分配） */
    private val box = RectF()
    /** 玻璃材质底（缓在实例上，主题对象变才重建 —— onDraw 每帧零分配） */
    private var plate: Drawable? = null
    private var plateKey: Theme? = null
    /** 玻璃判据同 [WxSkin.glass] */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial


    /** 当前是不是瑞士**配色**（黑白 + 单一信号红）。判据与 [WxSkin] 相同：只看配色自报，形状另算 */
    private val swiss: Boolean get() = Ui.theme.noShadowStyle

    /** 微信式的圆角色块底色（瑞士分支不用它） */
    private fun bg(): Int = when (kind) {
        K_START, K_STUDY, K_DATA, K_TODO -> Brut.MINT
        K_LOOK, K_ORB -> Brut.ROSE
        else -> Brut.SAND
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val fg = if (swiss) {
            // 瑞士：无色块，图形直接画成墨色线稿。线稿在纸底上的对比度是这套风格的
            // 「唯一信号」，不必再叠一层填色块来抢注意力。
            Brut.INK
        } else if (glass) {
            // 玻璃：底用 Ui.material 出的 BUTTON 角色材质 chip（轻透明 + 高光边），图形画反色。
            // 材质缓在 plate，主题变才重建 —— onDraw 每帧只 setBounds + draw，零分配。
            val t = Ui.theme
            if (plateKey !== t) { plate = Ui.material(context, bg(), Ui.SurfaceRole.BUTTON, 0f, t); plateKey = t }
            plate?.setBounds(0, 0, width, height)
            plate?.draw(c)
            Brut.on(bg())
        } else {
            val bgc = bg()
            fill.color = bgc
            box.set(0f, 0f, s, s)
            c.drawRoundRect(box, s * 0.24f, s * 0.24f, fill)
            Brut.on(bgc)
        }
        // 图形本体才用 [fg]：瑞士 = 墨色线稿（深色版就是浅墨），微信式 = 色块上的反色。
        // 这两行以前漏了 —— [fg] 算出来从没交给画笔，两支 Paint 一直是默认黑：
        // 深色卡（0x1C1C1C）上就是「黑底黑图标」，浅色卡上则是纯黑而不是主题墨。
        // fill 在非瑞士分支刚充当过色块底，这里必须重设成 fg，否则实心部分（小球 / 播放三角 / 勾）
        // 会和它自己的底色同色，直接看不见。
        line.color = fg
        fill.color = fg
        // 瑞士是 1dp 细线的语言：色块去掉后线稿要更细、图形也要稍微撑满，否则 30dp 方框里
        // 会剩一大圈空、线又和正文一样重，看着像「掉了底色的坏图标」。
        // 非瑞士保持构造时那 1.6dp —— 那版图形压在色块上，线必须够粗才看得清。
        // 瑞士先按 1dp 给（0.625 × 1.6dp），画布再放大 1.15 倍会把线一起带到 ~1.15dp：
        // 正好是「看得见的一根细线」，和卡片那 1dp 框、NavIcon 的 1.7 虚拟单位同一量级。
        line.strokeWidth = baseStroke * (if (swiss) 0.625f else 1f)

        // 放大走画布而不是改坐标：图形里有几处把 x(...) 当**半径**用（drawCircle 的 r、
        // drawRoundRect 的圆角），改 x() 会把半径一起算成坐标，圆就炸成一片。
        // 画布缩放对「坐标」和「半径」是同比例的，几何一个数都不用动。
        // save / restore 放在 try-finally 里：图形代码万一抛异常，画布也不会留在放大状态
        //（同一个 Canvas 后面还要用来画兄弟 View）。
        try {
            if (swiss) {
                c.save()
                c.scale(1.15f, 1.15f, s / 2f, s / 2f)
            }
            drawGlyph(c, s)
        } finally {
            if (swiss) c.restore()
        }
    }

    /** 把 24 单位虚拟坐标里的图形贴到 [s] 见方的画布上（放大与还原由 [onDraw] 负责） */
    private fun drawGlyph(c: Canvas, s: Float) {
        val u = s / 24f
        fun x(a: Float) = a * u
        path.reset()
        when (kind) {
            K_AI -> {           // 四角星（AI）
                path.moveTo(x(12f), x(4.5f)); path.quadTo(x(13f), x(11f), x(19.5f), x(12f))
                path.quadTo(x(13f), x(13f), x(12f), x(19.5f)); path.quadTo(x(11f), x(13f), x(4.5f), x(12f))
                path.quadTo(x(11f), x(11f), x(12f), x(4.5f)); path.close()
                c.drawPath(path, line)
            }
            K_ORB -> {          // 小球：实心圆 + 外圈
                c.drawCircle(x(12f), x(12f), x(3.5f), fill)
                c.drawCircle(x(12f), x(12f), x(7f), line)
            }
            K_POP -> {          // 铃铛
                path.moveTo(x(7f), x(16f)); path.lineTo(x(7f), x(11f))
                path.quadTo(x(7f), x(6f), x(12f), x(6f)); path.quadTo(x(17f), x(6f), x(17f), x(11f))
                path.lineTo(x(17f), x(16f)); path.moveTo(x(5.5f), x(16f)); path.lineTo(x(18.5f), x(16f))
                path.moveTo(x(10.5f), x(18.5f)); path.lineTo(x(13.5f), x(18.5f))
                c.drawPath(path, line)
            }
            K_STUDY -> {        // 打开的书
                path.moveTo(x(12f), x(7.5f)); path.lineTo(x(12f), x(18f))
                path.moveTo(x(12f), x(7.5f)); path.quadTo(x(8f), x(5.5f), x(5f), x(7f)); path.lineTo(x(5f), x(17f)); path.quadTo(x(8f), x(15.5f), x(12f), x(18f))
                path.moveTo(x(12f), x(7.5f)); path.quadTo(x(16f), x(5.5f), x(19f), x(7f)); path.lineTo(x(19f), x(17f)); path.quadTo(x(16f), x(15.5f), x(12f), x(18f))
                c.drawPath(path, line)
            }
            K_LOOK -> {         // 半实心圆（配色 / 深浅）
                c.drawCircle(x(12f), x(12f), x(6.5f), line)
                box.set(x(5.5f), x(5.5f), x(18.5f), x(18.5f))
                c.drawArc(box, 90f, 180f, true, fill)
            }
            K_DATA -> {         // 数据库圆柱
                box.set(x(6f), x(5f), x(18f), x(9f)); c.drawOval(box, line)
                path.moveTo(x(6f), x(7f)); path.lineTo(x(6f), x(17f)); path.quadTo(x(12f), x(21f), x(18f), x(17f)); path.lineTo(x(18f), x(7f))
                path.moveTo(x(6f), x(12f)); path.quadTo(x(12f), x(16f), x(18f), x(12f))
                c.drawPath(path, line)
            }
            K_PERM -> {         // 盾牌 + 勾
                path.moveTo(x(12f), x(4.5f)); path.lineTo(x(18f), x(7f)); path.lineTo(x(18f), x(12f))
                path.quadTo(x(18f), x(17f), x(12f), x(19.5f)); path.quadTo(x(6f), x(17f), x(6f), x(12f))
                path.lineTo(x(6f), x(7f)); path.close()
                path.moveTo(x(9.3f), x(12f)); path.lineTo(x(11.3f), x(14f)); path.lineTo(x(14.8f), x(10f))
                c.drawPath(path, line)
            }
            K_ABOUT -> {        // 圆圈里一个 i
                c.drawCircle(x(12f), x(12f), x(7f), line)
                c.drawCircle(x(12f), x(8.6f), x(1.1f), fill)
                path.moveTo(x(12f), x(11f)); path.lineTo(x(12f), x(16f)); c.drawPath(path, line)
            }
            K_START -> {        // 播放三角
                path.moveTo(x(9f), x(7f)); path.lineTo(x(17.5f), x(12f)); path.lineTo(x(9f), x(17f)); path.close()
                c.drawPath(path, fill)
            }
            K_TODO -> {         // 方框 + 勾
                box.set(x(6f), x(6f), x(18f), x(18f)); c.drawRoundRect(box, x(2.5f), x(2.5f), line)
                path.moveTo(x(9f), x(12f)); path.lineTo(x(11.2f), x(14.3f)); path.lineTo(x(15.2f), x(9.6f)); c.drawPath(path, line)
            }
            K_HISTORY -> {      // 时钟
                c.drawCircle(x(12f), x(12f), x(7f), line)
                path.moveTo(x(12f), x(8f)); path.lineTo(x(12f), x(12f)); path.lineTo(x(15f), x(14f)); c.drawPath(path, line)
            }
            else -> c.drawCircle(x(12f), x(12f), x(3f), fill)
        }
    }

    companion object {
        const val K_AI = "ai"; const val K_ORB = "orb"; const val K_POP = "pop"; const val K_STUDY = "study"
        const val K_LOOK = "look"; const val K_DATA = "data"; const val K_PERM = "perm"; const val K_ABOUT = "about"
        const val K_START = "start"; const val K_TODO = "todo"; const val K_HISTORY = "history"
    }
}
