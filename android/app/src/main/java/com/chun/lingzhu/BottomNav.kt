package com.chun.lingzhu

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「回到这个页面」时选中图标的一下轻缩放：160ms、0.94 → 1。
 *
 * 三条都是文件级私有：只有 [BottomNav] 和下面的 [NavPill] 用，放在对象里反而够不着。
 * 160ms 取在「看得出但不拦人」的上限（和 [Anim.MICRO] 120ms 同量级），不延迟任何跳转。
 */
private const val NAV_NUDGE_MS = 160L

/** 起点只收 6%：再多就从「轻轻吸一下」变成「图标在表演」 */
private const val NAV_NUDGE_FROM = 0.94f

/**
 * 「窗口刚变可见」系统会连发两回（attach 时一回、首帧遍历里再一回），这之内的第二次直接吞掉 ——
 * 否则页面一进来图标会抖两下。正常切页回来远大于这个间隔，不受影响。
 */
private const val NAV_NUDGE_GAP_MS = 200L

/**
 * 四个顶层区域（agent / 学习 / 记事 / 设置）共用的底部导航栏。
 *
 * 存在的理由：这条栏原来是 ChatListActivity 里私有的，另外三个页面压根没有 —— 于是用户从配置页
 * 点「记事」之后，唯一回得去的路是按系统返回。栏一复制到四个页面就会各自漂移（一处改了颜色，
 * 另外三处忘改），所以只留这一份。
 *
 * 切换用 Intent + FLAG_ACTIVITY_REORDER_TO_FRONT + FLAG_ACTIVITY_SINGLE_TOP：
 * 已经在栈里的目标页直接提到前台，不新建实例、不重走 onCreate —— 用户切回记事时列表、滚动位置、
 * 搜索词都还在原地，而不是像刚打开一样重来一遍。（配套要求：四个活动在 AndroidManifest.xml 里
 * 必须声明 launchMode，否则这两条 flag 会被系统忽略。）
 *
 * ## v2.6 底栏改版（按参考图）
 *  - 顺序：agent / 学习 / 记事 / 设置，常量按槽位从 0 到 3 依次是
 *    [CHAT] = 0、[STUDY] = 1、[NOTES] = 2、[SETTINGS] = 3；调用方一律按名字传，不依赖具体数值。
 *  - 图标在上、文字在下；图标 24dp，线稿描边，颜色随配色自动适配可读性（见 [iconInk]）。
 *  - 选中：图标周围一圈圆角胶囊（48×28dp），**不给文字整块底**（底色由当前色板主色掺出，见 [pillColor]）。
 *  - 去掉旧的薄荷绿圆点、底部短横与 TabSlider；没有左右滑动 / 跳转 / 指示器动画，
 *    按压反馈沿用 [AnimGlue.attachPress]（按下陷进去、松手弹回）。
 *  - v2.6.2 只补一点动效：页面进入、或者被 REORDER_TO_FRONT 提回前台（窗口重新可见）时，
 *    选中那一格的胶囊从 0.94 弹回 1，160ms。**只动那一格**：不整栏移动、不做页面转场、
 *    不延迟 [go]（跳转照常立刻发生，动画不参与任何跳转时序）；活动被盖住、视图被摘掉时
 *    立刻还原成 1，回来绝不会看到停在半路的缩放。
 *  - 图标全部用 Canvas + Path 自己画（同 [PersonaIcon] / [SettingIcon]）：对话框三点、书页柱图、
 *    纸张抽屉、齿轮；设置选中时齿轮实心填充。几何全在 24 单位的虚拟坐标里建一次，
 *    onDraw 只做缩放平移后整块贴出来。
 *
 * ## 外轮廓圆角（真机反馈「白方条叠圆胶囊」）
 * 栏底原来是按主题形状分支的：Atelier 是卡片圆角，BRUT / SWISS 直接一块**方**的 CARD 色板；
 * 而选中胶囊在任何主题下都是 14dp 满圆（[PILL_H_DP] / 2）。于是老配色用户看到的是方底 + 圆胶囊。
 * 现在外轮廓不再依赖 [Ui.shape]：**圆角只有 [BAR_CORNER_DP] 一个值**，与胶囊半径逐位相同 ——
 * 底是圆角矩形、选中块是同曲率的胶囊，两者视觉同族。颜色（CARD / 发丝线 / 墨色 / 胶囊）
 * 照旧随主题，浅色、深色、非 Atelier 都走这一条路径。
 *
 * ## v2.7.3：颜色只有一支来源（几何、insets、动效一律不动）
 * 原来的「深棕线稿 / 浅杏胶囊」是写死的 0xFF493B32 / 0xFFF4E5CF，十套配色里八套（含 Atelier 与
 * 液态玻璃）都在用它 —— 那是这套调色板里根本不存在的橘色。现在：
 *  - 选中胶囊 → 当前色板主色往卡片底掺一档（[pillColor]，瑞士的信号红即其 primary）；
 *  - 选中墨色 → 从那个胶囊底色反推可读的一档（[iconInk]，优先用色板自己的墨色）；
 *  - 玻璃那一族：整条栏 + 选中胶囊都换成共享材质 [Ui.material]（轻透明层 + 高光边 + 柔和阴影）。
 * 外轮廓 14dp、胶囊 14dp、SIDE_DP、BAR_DP、insets 监听、[NavPill] 的 0.94 呼吸与全部点击 / 实例复用
 * 逻辑一个字都不改 —— 底栏是四个页面共用的浮起一条，它的几何早经真机确认，不跟着配色分支走。
 */
object BottomNav {

    /** 四个区域的槽位下标，与 [LABELS] 从左到右一一对应（调用方按名字传）。 */
    const val CHAT = 0
    const val STUDY = 1
    const val NOTES = 2
    const val SETTINGS = 3

    /**
     * 栏上从左到右：[CHAT] agent → [STUDY] 学习 → [NOTES] 记事 → [SETTINGS] 设置。
     *
     * 「记事」这一格进的是 [NotesActivity]：页内再分「笔记 / 提醒」两个模块（笔记是原来的卡片流，
     * 提醒是待办 + 记期 + 随手记）。栏上只有一格 —— 两个模块共用同一个顶层区域，
     * 否则「提醒」会变成第五个槽位，和「记事」并列摆着，用户分不清谁是主体。
     *
     * 这个字符串同时是 [tab] 里 contentDescription 的来源：读屏念的就是它，
     * 所以改名只需改这一处，文字、跳转、无障碍标签一起跟上。
     */
    private val LABELS = arrayOf("agent", "学习", "记事", "设置")

    /** 栏高。56dp 是「手指按得到、又不吃内容」的折中（insets 垫在 page 上，不占栏内高度） */
    private const val BAR_DP = 56f

    /** 两端内缩：让栏看起来是一条浮起的圆角条，而不是通栏色带 */
    private const val SIDE_DP = 10f

    /** 图标尺寸 */
    private const val ICON_DP = 24f

    /** 选中胶囊：宽 48dp（比 24dp 图标多一圈左右留白），高 28dp */
    private const val PILL_W_DP = 48f
    private const val PILL_H_DP = 28f

    /** 胶囊半径 = 高的一半（满圆）：48×28 的块只有半径到顶才读成「胶囊」 */
    private const val PILL_CORNER_DP = PILL_H_DP / 2f

    /**
     * 底栏外轮廓圆角，**故意与胶囊同值**（[PILL_CORNER_DP] = 14dp）。
     *
     * 同值之后，选中胶囊的左右圆弧与栏两端的圆角是同一个曲率，两者看起来才是一族；
     * 差得越多，越像「一块圆角里又塞了另一个形状」。
     *
     * 也刻意**不读** [Ui.shape]：旧主题的直角语言只属于那些风格自己的元素，而底栏在四个页面里
     * 都是「浮起的一条」。真机反馈的「白色方条 + 圆形选中块」正是按形状分支的旧写法造成的 ——
     * 现在外轮廓只由这一个常量决定，14dp < [BAR_DP] / 2，竖边仍然是直的，不会变成胶囊条。
     */
    private const val BAR_CORNER_DP = PILL_CORNER_DP

    /**
     * 造一条底部栏。[current] 传本页属于哪个区域（CHAT / STUDY / NOTES / SETTINGS）。
     * 返回的 View 高度交给调用方的布局决定（一般是 wrap_content）。
     */
    fun bar(activity: Activity, current: Int): View {
        val ctx = activity

        // 外层：给两端留白，栏本身才显出圆角；外边距透明，圆角之外看到的是页面底色。
        // 这里**不能**设 clipChildren = false：栏是常驻在屏幕底部的，一旦允许越界绘制，
        // 圆角方块之外的四个角会画到上面的内容区里，看着像一条白带压在列表上。
        val outer = NavBar(ctx).apply {
            setPadding(Ui.dp(ctx, SIDE_DP), 0, Ui.dp(ctx, SIDE_DP), 0)
        }

        // 圆角底 + 发丝线：所有主题走同一条路径，不再分「分层圆角 / 方板」两支。
        // 裁剪轮廓显式取**整块**圆角矩形，不用 GradientDrawable 自报的那一圈 —— 它会按描边
        // 向内收，把填充最外沿和圆角一起削掉一点，边看起来就比画出来的小一号。
        //
        // v2.7.3 液态玻璃：整条栏换成共享材质（轻透明层 + 高光边 + 柔和阴影），圆角跟材质的卡片档；
        // 同一个值也喂给裁剪轮廓，四角不会缺一块。其它主题仍是 14dp 实底 + 1dp 发丝线。
        val barCornerDp = if (glass) Ui.cornerDp(Ui.SurfaceRole.CARD, BAR_CORNER_DP) else BAR_CORNER_DP
        val barCorner = Ui.dp(ctx, barCornerDp).toFloat()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (glass) Ui.material(ctx, Brut.CARD, Ui.SurfaceRole.CARD, barCornerDp)
                         else GradientDrawable().apply {
                             setColor(Brut.CARD)
                             cornerRadius = barCorner
                             // 线宽与透明度所有主题一致（瑞士也一样）：栏的几何与外轮廓是全局约定，不随风格分支。
                             setStroke(Math.max(1, Ui.dp(ctx, 1f)),
                                 Theme.argb(0x2E, Brut.INK and 0xFFFFFF))
                         }
            // 四格按 weight 铺满整条，按压 / 将来给某一格加底色时，子 View 会一路铺到方角里去 ——
            // 按外轮廓裁剪，圆角之外一点颜色都不会漏出来。
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    if (v.width > 0 && v.height > 0) {
                        outline.setRoundRect(0, 0, v.width, v.height, barCorner)
                    } else {
                        outline.setEmpty()
                    }
                }
            }
        }
        outer.addView(row, FrameLayout.LayoutParams(-1, -1))

        // 选中那一格的胶囊留给窗口回调用：只它会在「回到本页」时弹一下。
        var currentPill: NavPill? = null
        for (i in LABELS.indices) {
            // 当前页那一格不给跳转：点了等于原地重开一次，除了闪一下没有任何效果
            val jump: (() -> Unit)? = if (i == current) null else ({ go(activity, i) })
            val cell = tab(activity, i, LABELS[i], i == current, jump) { if (i == current) currentPill = it }
            row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }

        // 切页走的是 REORDER_TO_FRONT：目标页的 Activity 实例不重建、onCreate 不会再走，
        // 所以「回来了」这一刻唯一可靠的信号就是窗口可见性 —— 系统在页面被盖住 / 重回前台时
        // 会给整棵视图树派发 onWindowVisibilityChanged。只有这里叫得动 [NavPill.nudge]，
        // 页面本身该干嘛干嘛（列表、滚动位置、搜索词都还是原来那份，一次也没刷新）。
        var lastShown = 0L
        outer.onWindowShown = {
            val now = SystemClock.uptimeMillis()
            if (now - lastShown >= NAV_NUDGE_GAP_MS) {
                lastShown = now
                currentPill?.nudge()
            }
        }
        // 切走那一刻就还原：不等动画自然收尾，也不必担心停在 0.94。
        outer.onWindowHidden = { currentPill?.settle() }
        return outer
    }

    /**
     * 造一条栏并塞进 [parent]（一般是整页的根 `page`）。
     *
     * 原来非 Atelier 主题会在栏上面另垫一条**通栏** 1dp 发丝线（它不在 [SIDE_DP] 内缩里，
     * 于是方条上方多出一整条横线）。现在栏自带圆角描边，那条额外线已删除 ——
     * 分隔靠栏自己的 stroke，四角都是圆的。
     */
    fun attach(parent: LinearLayout, activity: Activity, current: Int) {
        val view = bar(activity, current)
        // 系统导航条（返回 / 主页那条手势条）压在页面最底，栏若贴着屏幕底边就会被它盖住半截。
        // 这个高度**必须垫在 page 自己的 padding 上**，不能给栏加 paddingBottom：
        // 栏是固定 56dp，从内部挤会把「图标 + 文字」压扁，四格叠成一团。
        // 也不能在栏下面另塞一个 spacer View —— 试过，spacer 拿到 0 高度、栏仍贴底。
        //
        // ## inset 分工：这里接管 Brut.page 的监听，完整保留四边避让
        // 早先这里用只处理底边的监听覆盖掉 Brut.page，导致聊天 / 记事 / 学习的标题钻进状态栏。
        // 当前四页都在首帧前依次调用 Brut.page、attach，此时读取的仍是建页时的基础 padding。
        // 四边全部按 base + inset 计算，不读回调里 v.paddingXxx —— 后者是上一轮写进去的，
        // 一旦把 inset 也读进来就会在每次回调上累加（键盘 / 旋转 / 分屏反复派发时越垫越高）。
        // 左右照旧是「base + inset」：横屏刘海那侧的避让必须留着。
        val base = IntArray(4).also {
            it[0] = parent.paddingLeft; it[1] = parent.paddingTop
            it[2] = parent.paddingRight; it[3] = parent.paddingBottom
        }
        parent.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(
                base[0] + ins.systemWindowInsetLeft,
                base[1] + ins.systemWindowInsetTop,
                base[2] + ins.systemWindowInsetRight,
                base[3] + ins.systemWindowInsetBottom,
            )
            ins
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, Ui.dp(activity, BAR_DP)))
        parent.requestApplyInsets()
    }

    private fun tab(
        activity: Activity,
        which: Int,
        label: String,
        selected: Boolean,
        go: (() -> Unit)?,
        onPill: (NavPill) -> Unit,
    ): View {
        val ctx = activity

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // 选中胶囊只包图标：未选中时同样留一块同尺寸的透明占位，切换时文字不跳。
        // 用 FrameLayout 而不是给图标画背景 —— 胶囊比图标宽（48 对 24），需要一个能居中的容器。
        // 底色由当前色板的主色掺出（[pillColor]），玻璃那一族再走一层共享材质（高光边跟着来）。
        val pillFill = pillColor()
        val iconBox = NavPill(ctx).apply {
            if (selected) background = if (glass) Ui.material(ctx, pillFill, Ui.SurfaceRole.CHIP, PILL_CORNER_DP)
                                       else GradientDrawable().apply {
                                           setColor(pillFill)
                                           cornerRadius = Ui.dp(ctx, PILL_CORNER_DP).toFloat()
                                       }
        }
        iconBox.addView(NavIcon(ctx, which, selected),
            FrameLayout.LayoutParams(Ui.dp(ctx, ICON_DP), Ui.dp(ctx, ICON_DP),
                Gravity.CENTER))
        col.addView(iconBox, LinearLayout.LayoutParams(
            Ui.dp(ctx, PILL_W_DP), Ui.dp(ctx, PILL_H_DP)))
        // 交给 bar() 记住「选中那一格」，窗口重新可见时只叫它弹一下
        onPill(iconBox)

        val labelView = TextView(ctx).apply {
            text = label
            textSize = 10.5f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, Ui.dp(ctx, 3f), 0, 0)
            // 未选中：hint→ink 之间（约 7:1）—— 是次要层级，但不会被误读成禁用。
            // 选中：墨色 + 加粗。灰度屏上也读得出（粗细 + 上方胶囊形状）。
            setTextColor(if (selected) iconInk() else Theme.mix(Brut.HINT, Brut.INK, 0.55f))
            typeface = if (selected) Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else Typeface.DEFAULT
        }
        col.addView(labelView, LinearLayout.LayoutParams(-2, -2))

        col.contentDescription = if (selected) label + "（当前页）" else label
        // 当前页那格也做成可点的：之前它完全没反应，用户点在自己脚下的位置上会以为栏卡了。
        // 现在它只回一下缩放 —— 不跳转、不重建、不刷新，什么状态都不动。
        col.isClickable = true
        col.isFocusable = true
        if (go != null) {
            col.setOnClickListener {
                try {
                    go.invoke()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(activity, "打不开",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            // 只保留轻微按压反馈；没有选中态滑动 / 指示器动画。
            AnimGlue.attachPress(col)
        } else {
            // 当前页那格同样给按压反馈，再补一下胶囊的轻缩放 —— 不跳转、不重建、不刷新。
            AnimGlue.attachPress(col)
            col.setOnClickListener { iconBox.nudge() }
        }
        return col
    }

    /**
     * 选中图标 / 选中文字的墨色。
     *
     * v2.7.3 起不再有写死的深棕 / 暖杏：选中块的底色由当前色板的主色掺出来（见 [pillColor]），
     * 墨色则**从那个底色反推**——色板自己的墨色读得清就用它（瑞士下正是正文那支黑，一条底栏不会
     * 突然变成另一个品牌），读不清才退到对比度更高的黑 / 白。于是十套配色 + 玻璃 + 用户自建配色
     * 都只有这一条路径，没有「哪套没接上」的口子。
     */
    internal fun iconInk(): Int {
        val fill = pillColor()
        val ink = Ui.theme.palette?.let { Theme.argb(0xFF, it.ink) } ?: Ui.TX1
        if (Theme.contrast(ink, fill) >= 4.5) return ink
        return if (Theme.contrast(-1, fill) >= Theme.contrast(-0x1000000, fill)) -1 else -0x1000000
    }

    /**
     * 选中胶囊的底色：**当前色板的主色往卡片底掺一档**（浅底 13%，深底 26% —— 深底上同一比例会
     * 糊进底色，读不出「选中」）。玻璃那一族抬一档：栏本身是半透明材质，13% 的选中块在它上面几乎看不见。
     *
     * 用掺色而不是半透明主色：胶囊是实心 drawable，alpha 色要依赖父层底色才对，一旦某页把栏放进
     * 别的容器里就会偏色。瑞士的信号红就是它的 primary，于是原来那条单独分支不再需要存在。
     */
    internal fun pillColor(): Int {
        val light = Theme.luminance(Brut.CARD) >= 0.4
        val k = when {
            glass -> if (light) 0.22f else 0.34f
            light -> 0.13f
            else -> 0.26f
        }
        return Theme.mix(Brut.CARD, Brut.MINT, k)
    }

    /** 当前配色是不是液态玻璃材质（只影响材质与配色，**不碰任何几何 / inset / 动效**） */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 切到第 [which] 个区域；目标页若已在栈里就复用，不重建。 */
    fun go(activity: Activity, which: Int) {
        if (which == targetOf(activity)) return
        val i = Intent(activity, clsOf(which)).apply {
            // REORDER：已在栈里的页提到前台复用；SINGLE_TOP：栈顶就是它时不重建。
            // NO_ANIMATION：系统那一侧的切换动画（launch / reorder 各自的默认效果）直接关掉。
            addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        // 真正覆盖「进入目标页」动画的是随 startActivity 一起交给系统的那对 empty 动画（0,0），
        // launch / reorder 都走这一份；overrideActivityTransition / overridePendingTransition
        // 只管出栈方，单靠它们会让目标页仍然带系统默认动画。
        @Suppress("DEPRECATION")
        val opts = ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
        activity.startActivity(i, opts)
        // 兼容兜底：把出栈方的切场也关掉（新系统走 overrideActivityTransition，旧系统走
        // overridePendingTransition），两处都是 0,0，不引入任何动画。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    /** 区域下标 → 目标页，与 [LABELS] 的顺序逐格对应。 */
    private fun clsOf(which: Int): Class<*> = when (which) {
        CHAT -> ChatListActivity::class.java
        STUDY -> StudyActivity::class.java
        NOTES -> NotesActivity::class.java
        else -> MainActivity::class.java
    }

    /** 本页对应的区域下标；认不出来就返回 -1（不会命中任何 tab）。
     *  [TodoListActivity] 也是 [NOTES]：它是「记事」页的「提醒」模块，不是第五个区域 */
    fun targetOf(activity: Context): Int = when (activity) {
        is ChatListActivity -> CHAT
        is StudyActivity -> STUDY
        is NotesActivity -> NOTES
        is TodoListActivity -> NOTES
        is MainActivity -> SETTINGS
        else -> -1
    }
}

/**
 * 底栏图标：几何全部在 24×24 的虚拟坐标里构建一次（[path]、[filled]、[gearPath]、[boxes]），
 * onDraw 只做「缩放到实际尺寸 → 画 → 还原」，不再每帧重算坐标 / 三角函数。颜色与 [BottomNav]
 * 的图标/文字同一份墨色，随主题（配色深浅）自动适配；栏在某页重建时随之刷新。
 *
 *  - [BottomNav.CHAT]：对话框 + 三个点
 *  - [BottomNav.STUDY]：翻开的书页 + 右侧柱状图
 *  - [BottomNav.NOTES]：纸张 + 抽屉
 *  - [BottomNav.SETTINGS]：齿轮；选中时实心填充（中间留孔）
 *
 * 所有图形都落在 x∈[3, 21]、y∈[3.5, 20.5] 之内，留出 0.85 的描边半径余量，不会被 24dp 视图裁掉。
 */
private class NavIcon(
    ctx: Context,
    private val which: Int,
    private val selected: Boolean,
) : View(ctx) {

    private val ink = BottomNav.iconInk()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 虚拟单位：几何按 24 建，描边也按 24 量，再随画布一起缩放，粗细外观与原来一致
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ink
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }

    /** 轮廓线（描边绘制），虚拟坐标 */
    private val path = Path()

    /** 填充图形（对话框的三点），虚拟坐标 */
    private val filled = Path()

    /** 齿轮轮廓单独一条：实心 / 描边共用同一条 EVEN_ODD 路径，只在选中时换 Paint */
    private val gearPath = Path()

    /** 圆角矩形几何，虚拟坐标 */
    private val boxes = ArrayList<RectF>(4)

    /** 圆角半径，虚拟坐标 */
    private val radii = ArrayList<Float>(4)

    init {
        build()
    }

    private fun build() {
        path.reset(); filled.reset(); gearPath.reset()
        boxes.clear(); radii.clear()
        fun box(l: Float, t: Float, r: Float, b: Float, rad: Float) {
            boxes.add(RectF(l, t, r, b)); radii.add(rad)
        }
        when (which) {
            BottomNav.CHAT -> {                 // 对话框 + 三点
                box(3.5f, 5f, 20.5f, 16.5f, 3.2f)
                path.moveTo(8.5f, 16.5f); path.lineTo(8.5f, 20f); path.lineTo(12.5f, 16.5f)
                // 三个实心圆点也预先并进 filled，onDraw 里不再逐个 drawCircle
                fun dot(cx: Float, cy: Float, r: Float) {
                    filled.addCircle(cx, cy, r, Path.Direction.CW)
                }
                dot(8.5f, 10.75f, 1.05f)
                dot(12f, 10.75f, 1.05f)
                dot(15.5f, 10.75f, 1.05f)
            }
            BottomNav.STUDY -> {                // 翻开的书页 + 柱状图（整体内缩，最右到 21）
                path.moveTo(3f, 6f)
                path.quadTo(6.2f, 6.6f, 8.6f, 8.2f)
                path.lineTo(8.6f, 18f)
                path.quadTo(6.2f, 16.4f, 3f, 16.4f)
                path.close()
                // 柱：右边三根，底部对齐
                box(11.2f, 12.5f, 13.8f, 17.5f, 0.6f)
                box(15.2f, 9.5f, 17.8f, 17.5f, 0.6f)
                box(19.2f, 6.5f, 21f, 17.5f, 0.6f)
            }
            BottomNav.NOTES -> {                // 纸张 + 抽屉
                box(5f, 3.5f, 16.5f, 20.5f, 1.6f)
                path.moveTo(8f, 8f); path.lineTo(13.5f, 8f)
                path.moveTo(8f, 11.5f); path.lineTo(11.5f, 11.5f)
                box(13.5f, 13f, 20.5f, 20.5f, 1.2f)
                path.moveTo(15.5f, 16.2f); path.lineTo(18.5f, 16.2f)
            }
            else -> {                           // 齿轮；选中实心
                // 8 齿：齿谷-齿顶-齿顶-齿谷交替成一条闭合路径；中间再挖一个圆孔。
                // FillType.EVEN_ODD 保证实心时孔是真的空的，描边时只描齿轮轮廓。
                val cx = 12f; val cy = 12f
                val teeth = 8
                val rOut = 9.6f; val rIn = 7.4f
                val step = (Math.PI * 2 / teeth).toFloat()
                fun px(r: Float, a: Float) = cx + r * Math.cos(a.toDouble()).toFloat()
                fun py(r: Float, a: Float) = cy + r * Math.sin(a.toDouble()).toFloat()
                for (k in 0 until teeth) {
                    val a0 = k * step
                    val g0x = px(rIn, a0 - step * 0.30f); val g0y = py(rIn, a0 - step * 0.30f)
                    val g1x = px(rIn, a0 + step * 0.30f); val g1y = py(rIn, a0 + step * 0.30f)
                    if (k == 0) gearPath.moveTo(g0x, g0y) else gearPath.lineTo(g0x, g0y)
                    gearPath.lineTo(px(rOut, a0 + step * 0.12f), py(rOut, a0 + step * 0.12f))
                    gearPath.lineTo(px(rOut, a0 + step * 0.38f), py(rOut, a0 + step * 0.38f))
                    gearPath.lineTo(g1x, g1y)
                }
                gearPath.close()
                gearPath.addCircle(cx, cy, 3.3f, Path.Direction.CW)
                gearPath.fillType = Path.FillType.EVEN_ODD
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        // 虚拟 24 → 实际像素；描边随画布同比缩放，不必重设 strokeWidth
        val u = s / 24f
        c.save()
        c.scale(u, u)
        for (i in boxes.indices) {
            c.drawRoundRect(boxes[i], radii[i], radii[i], line)
        }
        if (which == BottomNav.SETTINGS) {
            c.drawPath(gearPath, if (selected) solid else line)
        } else {
            c.drawPath(path, line)
            c.drawPath(filled, solid)
        }
        c.restore()
    }
}

/**
 * 底栏外壳：只为了知道「窗口什么时候重新可见」。
 *
 * 四个页面切来切去用的是 REORDER_TO_FRONT，目标页实例留在栈里，onCreate / onResume 之外的
 * 生命周期信号都不够可靠。窗口可见性是系统统一派发到整棵视图树的：被别的页盖住时
 * GONE / INVISIBLE，提回前台时 VISIBLE。这里把它翻成两个回调，[BottomNav.bar] 只关心
 * 「回来了就弹一下、走了就还原」。
 *
 * 注意 attach 那一刻系统也会派发一次 VISIBLE（首帧遍历里），所以调用方那头还要有一层
 * 去重 —— 见 [NAV_NUDGE_GAP_MS]，否则新建页面时图标会连着抖两下。
 */
private class NavBar(ctx: Context) : FrameLayout(ctx) {
    /** 窗口变为可见（首次进入 / 被提回前台） */
    var onWindowShown: (() -> Unit)? = null

    /** 窗口不再可见（被别的页盖住 / 退到后台） */
    var onWindowHidden: (() -> Unit)? = null

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) onWindowShown?.invoke() else onWindowHidden?.invoke()
    }
}

/**
 * 选中胶囊容器：除了装图标，只多管一次「0.94 → 1」的轻缩放（[nudge]）。
 *
 * 为什么不用 [AnimGlue] 那套弹簧：按压反馈是「手指按住 → 松手回弹」，由触摸事件驱动；
 * 这里要的是「页面回来了，胶囊轻轻吸一下」，由窗口可见性驱动，两者时机完全不同，混在
 * 一个动画器上会互相抢位置。所以走独立的 ViewPropertyAnimator：
 *  - 系统把动画时长调成 0（开发者选项 / 无障碍「移除动画」）时，它直接落到终态 ——
 *    用户关掉动画的设置**天然**被尊重，不需要自己读 Settings.Global 再判断一遍；
 *  - 只改 scaleX / scaleY，不改 alpha、不改位移：胶囊背景与图标一起收放，下面的文字
 *    一格都不动（没有「整栏在动」的观感）。
 *
 * 纪律沿用 [AnimGlue]：无论正常结束、被打断、还是页面切走，缩放都要落回 1；
 * 同一时刻只跑一段，连点 / 反复切页都不会叠成「抖两下」。
 */
private class NavPill(ctx: Context) : FrameLayout(ctx) {
    /** 这一段还在跑吗 —— 只用来拒掉「叠第二段」，不参与任何布局 */
    private var running = false

    /** 从 0.94 弹回 1。已经在跑就什么都不做：不重新起步，也不排队 */
    fun nudge() {
        if (running) return
        // 先清掉可能残留的一段：它的 end action 会把 running 归 false，所以必须在置位之前
        animate().cancel()
        running = true
        scaleX = NAV_NUDGE_FROM
        scaleY = NAV_NUDGE_FROM
        animate().scaleX(1f).scaleY(1f)
            .setDuration(NAV_NUDGE_MS)
            .setInterpolator(AnimGlue.EASE_OUT)
            .withEndAction { running = false }
            .start()
    }

    /** 立刻回到常态：页面被盖住 / 视图被摘掉时调，绝不留半截缩放 */
    fun settle() {
        running = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }
}
