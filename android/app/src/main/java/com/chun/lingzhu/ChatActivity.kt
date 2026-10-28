package com.chun.lingzhu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 和小珠聊聊（v1.69 陪伴 M2 → v1.79 换皮 + 图片 → [2.2] 阅读优先重做）。
 *
 * **[2.2] 结构：默认只有三块** ——
 * 紧凑顶栏（矢量头像 + 名字/状态两行，整组点一下弹人格列表；右侧轻量「设置」）
 * →（必要配置提示条，缺配置时才出现一条短句）
 * → 消息流（Agent 是**无卡片**的正文，用户是浅色圆角小块）
 * → 底部**一条** composer（左「＋」工具栏 / 中间多行输入 / 右发送）。
 *
 * **[2.2] 为什么收掉「快捷操作」整行**：那三个常驻按钮占掉一整行、把正文压得很紧，
 * 而它们一天也用不了几次。现在图片 / 识别屏幕 / 弹单词 / 添加提醒全收进 composer 左侧的「＋」菜单
 * （[showTools]，触发仍是原来的 [pickImage] / [actSolve] / [actWord] / [actRemind]，没有第二套入口，
 * 也没有被藏起来的死代码）。页面本身只留阅读和输入。
 *
 * **[2.2] 视觉**：不再给这一页刷 [Brut.style]（那套 2dp 黑描边 + 4dp 实心投影是设置页 / 历史页的厚积木规格），
 * 改成手画的轻量形状：composer 一层 1dp 淡描边、20dp 圆角；用户消息浅色圆角无描边；Agent 回复**完全无卡片**
 * （只有正文，16sp、可长按选字、链接可点），辅助操作（复制原文）收进正文上方 48dp 的「⋯」菜单。
 * 配色只取 [Brut] 的 PAPER / CARD / INK / MINT / HINT（外加派生色），按压反馈只用系统 ripple。
 *
 * **v2.6 瑞士国际主义**：这一页手画的形状全部过 [corner] / [swiss] 两个闸门，于是同一份代码在两套形状语言下
 * 各是各的样子 —— 瑞士下 0dp 直角、1dp 细线、无投影、无渐变；输入区从「圆角淡描边容器」变成**只有一条下划线**
 * （[underlineInput]）；用户消息从「浅色圆角块」变成**直角细线空心块**（[bubbleBg]）：收发靠位置 + 那条线区分，
 * 不再靠掺主色（掺出来是粉的；瑞士只有黑白 + 一个信号红，而红只给发送按钮）。其它配色逐位不变。
 *
 * **[2.1.0] 布局不变量（修「键盘展开时发送被挤出窗口」）**：
 * 顶栏常驻不滚动；消息流 weight=1 占中间；composer 整块固定最底、**永不被挤出**。
 * 键盘弹出时 root 底部垫 IME 高，可用高变矮；若固定内容之和超过可用高，LinearLayout 的 weight 孩子
 * 无法吸收负数，排在最后的 composer 会整体溢出被裁掉 —— 由 [applyClamp] 兜底：
 * 输入框高度受剩余空间上限约束（输入框已经嵌在 composer 里，预算要按 composer 的实际固定高算）。
 *
 * **[v2.1.1] 阅读位置不变量（修「异步回复一到就把正在往上读的人抢回底部」）**：
 * 只有**本来就在底部**（[atBottom]）或者**用户自己刚发消息 / 切人格**（`draw(showBottom = true)`）才贴底；
 * 其余重画（异步回复回来、回前台补收结果、旋转回来）都把原来的 scrollY 放回去；键盘弹出改高度时同理。
 *
 * **[v1.79] 图片**：相册选图 → 缩到 1280px / JPEG 80 存 files/chat-img/ → 待发预览可移除 →
 * 发送时和文字一起按多模态（text + image_url）发出去 → 气泡里显示圆角缩略图。
 *
 * 请求走 [ApiClient] 的配置（[Prefs.api]）但**自己发一次非流式 POST**：ApiClient 的 `post` 是私有的、
 * 而聊天要带 system + 一串历史 messages，现成的 [Bodies.text] 只放单条 user 消息。
 * 为了不碰 ApiClient，这里在 Activity 里就地发请求，配置 / 思考参数 / 报错措辞
 * 都还是走 ApiClient 那一套（[ApiConfig] / [Bodies.thinking] / [NetErrors]）。
 *
 * [MP] 多人格 M-a：顶栏头像 + 名字跟着当前人格走，整组点一下弹人格列表切换；每个人格一份会话文件、一份记忆卡。
 *
 * [会话列表] 多会话列表在 [ChatListActivity]（每个人格一行，点进本页带 [EXTRA_PID]；本页左上「‹」/ 系统返回回到列表）。
 *
 * 仍然**不做**：流式逐字、停止生成、新的工具调用、清空会话、重新生成、历史图片的磁盘回收。
 * 生成中不提供「停止 / 重试」—— 现在就是一次非流式请求，做一个按了没用的按钮等于骗人。
 */
class ChatActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var personas: Personas.Store
    private lateinit var store: ChatStore.Store
    private lateinit var col: LinearLayout          // 消息流
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private lateinit var who: TextView              // [MP] 顶部那个「小Ling ▾」（v1.79 起不带 emoji）
    /** 必要配置状态提示条：悬浮窗权限 / 接口地址缺一就显示这一条（原两条横幅合并，点一下去对应地方） */
    private lateinit var setupBar: LinearLayout
    private lateinit var setupLabel: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var idIconHost: LinearLayout   // [2.0.2] 顶栏左侧头像位（切人格后只重画这里）
    /** [v1.79] 选好还没发出去的那张图（null = 没图）。存的是 files/chat-img/ 下的文件 */
    private var pendingImg: File? = null
    private lateinit var pendingRow: LinearLayout
    private lateinit var pendingThumb: ImageView
    private lateinit var pendingLabel: TextView
    private var asking = false                       // 有请求在飞：禁掉发送，防连点
    /**
     * [v2.1.1] 消息流是不是停在底部（由 [scroll] 的滚动位置推出来）。决定「内容变高 / 键盘弹起 / 重画」
     * 要不要继续贴底 —— 用户往上读了就一直是 false，谁也别想把他抢回底部。进页面默认 true（先看最新）。
     */
    private var atBottom = true
    /**
     * 当前这一轮在飞请求的保留态（[ChatFlight]）。旋转/recreate 时由系统原样交给新实例，
     * 结果也留在里面 —— 新实例接着显示「正在回复…」并收结果，后台/返回前台也能重画。
     * null = 没有在飞请求。
     */
    private var flight: ChatFlight? = null
    /**
     * 上次画出来的那套配色（[Ui.applyTheme] 之后取的那份 [Ui.theme]），与 [ChatListActivity] 同一个写法。
     *
     * 系统深浅色变了不会碰 [Prefs.themeId]，onResume 里原来的两项（提示条 / 在飞请求重接）都看不出
     * 「色板已经换了」，于是整页继续用旧色板 —— 深色纸底上留着浅色的顶栏和气泡。这里按色板本身比一次。
     */
    private var shownTheme: Theme? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    /**
     * [2.0.2] **只给用户小卡**的宽度封顶（屏幕的 78%）：右对齐的一句短话缩成一小块，长句也别顶满整行。
     * Agent 回复不再用它 —— 回复占可用正文宽（见 [orbBubble]）。
     */
    private val maxBubbleW by lazy { (resources.displayMetrics.widthPixels * 0.78f).toInt() }

    // [MP] 顶栏的名字跟着当前人格走，不再写死一个
    private var pid: String = Personas.P0

    /** 当前人格（每次都从 Store 现取 —— 切换后整页重画，别缓存一份会过期的） */
    private fun persona(): Persona = personas.get(pid)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // [会话列表] 桌面图标仍落在本页（launcher 组件名不变：老用户桌面上的图标 / 快捷方式不会失效），
        // 但「从桌面冷启动」这一下转去会话列表 —— 跟微信一样先看列表，点哪个进哪个。
        // 只认 MAIN + LAUNCHER 的新实例：小球菜单「和小Ling聊聊」、列表点进来、旋转重建都不走这里。
        // 首次配置引导照旧由 launcher 这一下触发（先开列表、再开引导 → 引导盖在列表上面）。
        if (savedInstanceState == null && isLauncherLaunch(intent)) {
            try { startActivity(Intent(this, ChatListActivity::class.java)) } catch (_: Exception) {}
            maybeOpenOnboarding()
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        Ui.applyTheme(prefs)   // 建视图前先装好当前配色（Brut 的颜色是 getter）
        shownTheme = Ui.theme  // 记下这一页是按哪套色板画的（onResume 比它决定要不要重建）
        personas = Personas.Store(this)
        // [会话列表] 列表点进来带着 EXTRA_PID；旋转重建优先用存下的 pid（会话里用顶栏切过人格的话以那个为准）。
        // 打开谁就把谁记成「当前人格」—— 设置页「当前 Agent 的记忆」、小球菜单「和小Ling聊聊」都跟着它走（原语义不变）。
        val want = savedInstanceState?.getString(KEY_PID) ?: intent?.getStringExtra(EXTRA_PID)
        pid = if (want != null && personas.list().any { it.id == want }) want.also { personas.setCurrent(it) }
              else personas.current()
        store = ChatStore.Store(this, pid)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        Brut.page(this, root)

        // ---- 顶部：矢量头像 + 名字/状态两行 + 设置 ----
        // [2.2] 不再压 2dp 黑边、不再给整页刷厚积木皮肤：顶栏就是纸底上一组可点的身份 + 一个轻量「设置」。
        // 整组高度 ≥ 48dp；设置入口是文字按钮但 ≥48dp 触达、带 contentDescription。
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // 瑞士：顶栏脚下压一条 1dp 细线（画在自身底边里，不改高度，也就不动 applyClamp 的固定高预算）
            background = if (swiss) ruleBg(Brut.PAPER, bottom = true)
                         else GradientDrawable().apply { setColor(Brut.PAPER) }
            setPadding(dp(2f), dp(4f), dp(8f), dp(4f))
        }
        topBar = top
        // [会话列表] 左上返回：回到会话列表（和系统返回同一条路，见 [goBack]）
        top.addView(TextView(this).apply {
            text = "‹"; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30f); setTextColor(Brut.INK)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minimumWidth = dp(44f); minimumHeight = dp(48f)
            isClickable = true; isFocusable = true
            contentDescription = "返回会话列表"
            background = ripple(rounded(0, 22f), 22f)
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(44f), dp(48f)))
        // [MP] 名字那一行仍写「名字 ▾」；点它不在这里处理，交给左边整组（见 idGroup）
        who = TextView(this).apply {
            textSize = 16.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 状态行用小灰字（Brut 里的提示色），别和标题抢。
        status = TextView(this).apply {
            textSize = 11.5f; setTextColor(Brut.HINT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 名字 + 状态竖向摞成两行；整列占满剩余宽度，两条都单行截断 —— 条数再多也不会把设置顶出屏幕
        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(who, LinearLayout.LayoutParams(-1, -2))
        nameCol.addView(status, LinearLayout.LayoutParams(-1, -2))
        val idGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            minimumHeight = dp(48f)
            background = ripple(rounded(0, 18f), 18f)
            setOnClickListener { pickPersona() }
        }
        // 头像是独立一个 host：切人格后只清掉它重画（名字/状态那两条 TextView 的引用要留着更新文字）
        idIconHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        idGroup.addView(idIconHost, LinearLayout.LayoutParams(-2, -2))
        idGroup.addView(nameCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
        top.addView(idGroup, LinearLayout.LayoutParams(0, -2, 1f))
        // 设置是二级入口（桌面图标落在这儿），所以顶栏常驻一个入口，不藏进消息流
        top.addView(TextView(this).apply {
            text = "设置"; textSize = 14f; setTextColor(Brut.INK)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f); minimumWidth = dp(48f)
            setPadding(dp(14f), 0, dp(14f), 0)
            isClickable = true; isFocusable = true
            contentDescription = "设置"
            background = ripple(rounded(0, 18f), 18f)
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(-2, -2))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // ---- 必要配置状态提示（合并原「悬浮球未授权」「接口未配置」两条横幅，只留一句短文案）----
        // 授权 / 接口的活儿都在 MainActivity，这里只做提示与跳转，不重复实现权限系统引导。
        // [2.2] 只是一条紧凑提示，**不挡输入、不挡发送**：已配好接口时聊天照常，缺悬浮球权限只影响识屏 / 弹单词。
        setupBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            minimumHeight = dp(48f)
            isClickable = true; isFocusable = true
            // 瑞士：直角 + 1dp 细线的空底提示框（灰色块在这套风格里不承载结构，线才承载）
            // 玻璃（v2.7.3）：走共享材质的小卡片；其余配色仍是原来的淡墨色块。
            background = when {
                swiss -> ripple(outlined(0, 14f, Ui.shape.strokeDp, Brut.INK), 14f)
                glass -> ripple(Ui.material(this@ChatActivity, Brut.CARD, Ui.SurfaceRole.CHIP, 14f), 14f)
                else -> ripple(rounded(Theme.argb(0x0F, Brut.INK), 14f), 14f)
            }
            visibility = View.GONE
            setOnClickListener { routeSetup() }
        }
        setupLabel = Ui.text(this, "", 13f, Brut.HINT).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        setupBar.addView(setupLabel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(setupBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12f), dp(6f), dp(12f), 0) })

        // ---- 中间：消息流 ----
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
        }
        scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        // [v2.1.1] 阅读位置：用户是不是停在底部。只有「本来就在底部」时，内容变高 / 键盘弹起才继续贴底；
        // 往上读了（atBottom=false）就一律不再抢位置 —— 异步回复回来重画时也不许把人拽回最下面。
        scroll.setOnScrollChangeListener { v, _, _, _, _ -> atBottom = !v.canScrollVertically(View.FOCUS_DOWN) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- 底部：待发图片预览 + 单条 composer（左「＋」/ 中多行输入 / 右发送）----
        // [2.1.0] 键盘 / 系统栏留白统一垫在 root 上（见 onCreate 尾部的 insets listener）—— 子树都不再各自消费 insets。
        // 底栏铺 PAPER：键盘弹出 / 内容多的时候，气泡不会从输入栏底下透出来。
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 瑞士：底栏顶上一条 1dp 细线代替原来那圈圆角淡描边（同样画在自身内边，不改高度）
            background = if (swiss) ruleBg(Brut.PAPER, bottom = false)
                         else GradientDrawable().apply { setColor(Brut.PAPER) }
            setPadding(dp(12f), dp(6f), dp(12f), dp(10f))
        }
        pendingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(6f))
            visibility = View.GONE
        }
        pendingThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            roundCorners(10f)
        }
        pendingRow.addView(pendingThumb, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        pendingLabel = Ui.text(this, "选好 1 张图，点发送一块儿发过去", 12.5f, Brut.HINT).apply {
            setPadding(dp(10f), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pendingRow.addView(pendingLabel, LinearLayout.LayoutParams(0, -2, 1f))
        pendingRow.addView(TextView(this).apply {
            text = "移除"; textSize = 13f; setTextColor(Brut.INK)
            gravity = Gravity.CENTER
            minimumHeight = dp(48f); minimumWidth = dp(48f)
            setPadding(dp(12f), 0, dp(12f), 0)
            isClickable = true; isFocusable = true
            contentDescription = "移除待发图片"
            background = ripple(rounded(0, 14f), 14f)
            setOnClickListener { clearPending() }
        }, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(pendingRow, LinearLayout.LayoutParams(-1, -2))

        // [2.2] 一条 composer：圆角 20dp、1dp 淡描边的浅色容器；输入框自己**不再画框**，只占中间。
        // 「＋」和发送都是 48dp 触达；输入框随内容多行增长（maxLines + applyClamp 的高度上限双保险）。
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = when {
                swiss -> null
                glass -> Ui.material(this@ChatActivity, Brut.CARD, Ui.SurfaceRole.BUTTON, 20f)
                else -> outlined(Brut.CARD, 20f, 1f, Theme.argb(0x33, Brut.INK))
            }
            setPadding(dp(4f), dp(3f), dp(4f), dp(3f))
        }
        composer.addView(TextView(this).apply {
            // 「＋」是图标不是正文：按 dp 定尺寸，系统大字体下也不会把字形撑出 48dp 触达框
            text = "+"; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f); setTextColor(Brut.INK)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minimumWidth = dp(48f); minimumHeight = dp(48f)
            isClickable = true; isFocusable = true
            contentDescription = "更多功能：图片、识别屏幕、弹单词、添加提醒"
            background = ripple(rounded(0, 24f), 24f)
            setOnClickListener { showTools(it) }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        input = EditText(this).apply {
            hint = "说点什么…"; textSize = 16f
            setTextColor(Brut.INK); setHintTextColor(Brut.HINT)
            // v2.6 瑞士：composer 那层框已经去掉，输入框自己只留一条下划线（见 [underlineInput]）
            background = if (swiss) underlineInput() else null     // 正文输入没有独立框，只有 composer 那一层
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5; isSingleLine = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(44f)
            setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        }
        send = TextView(this).apply {
            text = SEND_LABEL; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumHeight = dp(48f)
            setPadding(dp(16f), 0, dp(16f), 0)
            // 「发送」↔「回复中」字数不同：宽度按**较长那条**量一次（见下面 addView），
            // 切文案时 composer 不跟着跳、输入框不被挤动。setAsking 里不再重新量。
            isClickable = true; isFocusable = true
            contentDescription = "发送"
            // 瑞士：实心信号红直角块，按下**填色变深**（没有投影可沉，与 Brut 的 swissButton 同一套反馈）
            background = ripple(StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), rounded(Brut.DISABLED, 16f))
                if (swiss) addState(
                    intArrayOf(android.R.attr.state_pressed), rounded(Theme.mix(Brut.MINT, Brut.INK, 0.18f), 16f))
                addState(intArrayOf(), rounded(Brut.MINT, 16f))
            }, 16f)
            // 禁用态字色：瑞士跟 Brut 的 swissButton 同一档（0x99 的可读色），其它配色仍是原来的 HINT
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(if (swiss) Theme.argb(0x99, Brut.on(Brut.DISABLED)) else Brut.HINT, Brut.on(Brut.MINT)),
            ))
            // 统一按压反馈（[AnimGlue.attachPress]，全 App 同一份强度）。禁用态收不到触摸事件，
            // 所以「回复中」不会出现按不动的假反馈；这里不再自己写 setOnTouchListener。
            AnimGlue.attachPress(this)
            setOnClickListener { ask() }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        // 固定宽度 = max(64dp, 较长文案 + 左右内边距)：两段文案共用同一个宽度
        val sendW = maxOf(dp(64f), Math.ceil(
            Math.max(send.paint.measureText(SEND_LABEL), send.paint.measureText(BUSY_LABEL)).toDouble()).toInt() +
            send.paddingLeft + send.paddingRight)
        composer.addView(send, LinearLayout.LayoutParams(sendW, -2).apply { marginStart = dp(2f) })
        // composer 进底栏、底栏进 root —— 这两行是 composer 上树的唯一路径：
        // 少一行，输入框和发送按钮就整块不在视图树里（220 条 fixture 上「composer 整个消失」就是这么来的）。
        bottom.addView(composer, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        // [2.1.0] insets 分工：**root 一处统一垫**，子树都不消费 —— 上 = 状态栏 / 刘海（topPad），左右 = 刘海，
        // 下 = 系统栏 + 键盘（bottomPad 里 IME 已含导航条，只取超出系统栏的差值，不重复垫）。
        // root 的 listener 在整棵树派发的最前面，永远拿到最新一版 IME inset。
        // 垫高 root 底边距 → root 可用高变矮 → weight=1 的消息流先缩；但光靠这个不够 —— 窄屏 + 大字 + 键盘时，
        // composer 的固定高会超过可用高，LinearLayout 的 weight 孩子只能缩到 0、吸收不了负数，
        // 排在最后的 composer 就整体溢出、被 root.clipToPadding 裁掉（发送按钮消失）。
        // 真正的保底是下面的 applyClamp：输入框高度受剩余空间上限约束，于是 composer 永远完整落在 IME 可见区之上，
        // 不缩字号、不禁用输入法。
        // [v1.85.4] 那个「高度变了就滚到底」的 GlobalLayoutListener 保留：root 垫高时 scroll 高度照样变。
        val rootBase = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val sb = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                val cut = ins.getInsets(android.view.WindowInsets.Type.displayCutout())
                val ime = ins.getInsets(android.view.WindowInsets.Type.ime())
                val pad = ChatBars.topPad(sb.top, cut.top, maxOf(cut.left, sb.left), maxOf(cut.right, sb.right))
                val bottomPad = ChatBars.bottomPad(sb.bottom, ime.bottom)
                v.setPadding(rootBase[0] + pad[0], rootBase[1] + pad[1], rootBase[2] + pad[2],
                    rootBase[3] + bottomPad)
            } else {
                // API 29 及以下：老 getter 的 systemWindowInsetBottom 本来就是「系统栏 + 键盘」的并集
                v.setPadding(rootBase[0] + ins.systemWindowInsetLeft, rootBase[1] + ins.systemWindowInsetTop,
                    rootBase[2] + ins.systemWindowInsetRight, rootBase[3] + ins.systemWindowInsetBottom)
            }
            ins
        }
        // 某些 ROM 的 insets 首派发早于子树挂载，保险起见首帧后再要一次
        root.post { root.requestApplyInsets() }

        // [2.1.0] 「composer 永不挤出窗口」的保底（配合上面的 insets 垫高）：
        // 可用高 = root 高 − root 上下 insets；固定部分是顶栏 +（可能出现的）配置提示条 + composer 里
        // **除输入框以外**的全部高度（待发图行、composer 内边距、＋ / 发送按钮行）。
        // [2.2] 输入框已经嵌进 composer 里：预算必须按 `bottom.height − input.height` 算实际固定高，
        // 不能再用旧的「输入框自己一行、工具行另起一行」口径 —— 否则会少扣 composer 的上下内边距和按钮行，
        // 多行展开时仍会把发送按钮顶出窗口。
        // 只设上限：不缩字号、不隐藏任何控件、数值全部来自实测高度 / insets，不写死像素。
        fun applyClamp() {
            if (root.height == 0 || bottom.height == 0 || input.height == 0) return
            val avail = root.height - root.paddingTop - root.paddingBottom
            if (avail <= 0) return
            // 常驻顶栏区 +（可能出现的）配置提示（含它的外边距；height 本身不含 margin）
            val setupLp = setupBar.layoutParams as? LinearLayout.LayoutParams
            val setupFixed = if (setupBar.visibility == View.GONE) 0
                             else setupBar.height + (setupLp?.topMargin ?: 0) + (setupLp?.bottomMargin ?: 0)
            val topFixed = topBar.height + setupFixed
            val composeFixed = (bottom.height - input.height).coerceAtLeast(0)
            val inputFloor = maxOf(input.minimumHeight, dp(44f))   // 再挤也给一行的高度，不把输入框压没
            val inputBudget = (avail - topFixed - composeFixed).coerceAtLeast(inputFloor)
            if (input.maxHeight != inputBudget) input.maxHeight = inputBudget
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { applyClamp() }
        root.post { applyClamp() }

        // [v1.85.4] 键盘弹出（root 底部垫高改变 scroll 高度）时自动重滚到底 ——
        // 修「最后一条气泡被输入栏压住一半」：以前只在 draw() 时滚一次，键盘一弹新气泡又露出来了。
        // [v2.1.1] 现在只在**用户本来就停在底部**时才重滚：正在往上读的人不许被这一下抢回底部。
        val listenResize = { last: IntArray ->
            scroll.viewTreeObserver.addOnGlobalLayoutListener {
                val h = scroll.height
                if (h != last[0]) { last[0] = h; if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
            }
        }
        listenResize(intArrayOf(-1))

        // 气泡 / 头像 / composer 底都是手画的，不走 Brut.style，也不在页面级刷 2dp 黑边 + 硬投影。
        draw()

        // 旋转/recreate 回来：恢复草稿和待发图。输入框没 id，系统不会自动存它的文字；待发图只存文件名。
        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_DRAFT)?.let { d ->
                if (d.isNotEmpty()) { input.setText(d); input.setSelection(d.length) }
            }
            savedInstanceState.getString(KEY_PENDING_IMG)?.let { n ->
                val f = File(File(filesDir, ChatStore.IMG_DIR), n)
                if (f.exists()) { pendingImg = f; showPending() }
            }
        }

        // 在飞请求：系统会把发起时的 [ChatFlight] 原样交回来（onRetainNonConfigurationInstance）——
        // 接上当前页；还没回完就继续显示「正在回复…」，已经回完的 attach 会当场把结果收掉重画
        // （回复早已由后台线程落进发起时那个人格的会话文件，这里只负责画出来）。
        // [会话列表] 返回列表后再点进同一个 Agent 是**新实例**：它的那一轮请求可能还在飞 ——
        // 从进程内登记表接上，照样显示「正在回复…」、回完当场收（不会让人对着同一个 Agent 再发一轮）。
        (carriedFlight() ?: ChatFlights.live(pid))?.let { f ->
            flight = f
            f.attach(this)
            if (flight === f) { setAsking(true); draw() }   // attach 没把它收走 = 还在飞
        }

        // launcher 自动送新用户进首次配置：只在**新实例**（savedInstanceState == null）触发，
        // 转屏 / 进程重建 / 从引导返回都不重复；老用户和已完成引导的人一概不打扰，聊天本身照常可用。
        if (savedInstanceState == null) maybeOpenOnboarding()
    }

    /**
     * 真正的新用户第一次打开 launcher 时，自动进入 MainActivity 既有的 [MainActivity.showSetup] ——
     * 不在这里另做一套引导，步骤 / 权限链 / 「先不开」全都还是原来那一套。
     *
     * **只自动开一次**：先落 [Prefs.onboardAutoOpened] 再跳，进程在跳转途中被杀也不会循环。
     * 老用户用和 MainActivity.onCreate 相同的 [Onboarding.legacyConfigured] 判定直接记为走完；
     * 只有真正的新用户才写 [Prefs.orbGuidePending]（悬浮球建好后弹一次新手卡），升级用户不会弹。
     */
    private fun maybeOpenOnboarding() {
        if (prefs.onboardAutoOpened) return
        val keys = prefs.keys()
        if (!prefs.onboarded && !prefs.onboardStarted && Onboarding.legacyConfigured(keys)) {
            prefs.onboarded = true
            return
        }
        if (!Onboarding.shouldShow(prefs.onboarded, prefs.onboardStarted, keys)) return
        if (!prefs.orbGuideSeen) prefs.orbGuidePending = true
        prefs.onboardAutoOpened = true
        try { startActivity(Intent(this, MainActivity::class.java)) } catch (_: Exception) {}
    }

    /**
     * 从系统设置页 / 引导页回来时必要配置提示条要跟着更新，所以每次回前台都重新看一眼
     * （悬浮窗权限 + 接口，见 [refreshSetupBar]）。授权 / 录屏 / 无障碍那条链在 MainActivity，
     * 这里不重复实现，只做提示与跳转。
     */
    override fun onResume() {
        super.onResume()
        // 系统深浅色变了 /「跟随系统深色」改过：换的是 [Prefs.themeId] 之外那层结果，下面两项
        // （提示条 / 在飞请求重接）都看不出色板已经换了，整页会继续用旧色板。按色板本身比一次，
        // 变了就重建 —— 草稿 / pid / 待发图片走 onSaveInstanceState，在飞请求走 [ChatFlight] 保留态，
        // 和旋转重建同一条路，不会丢。重建后 shownTheme 已是新值，不会循环。
        // shownTheme 为 null = 这次 onCreate 是「桌面冷启动转会话列表」那条早退路（视图没建过），
        // 那种情形下不重建：本来就没有旧色板可换。
        val t = prefs.theme()
        if (shownTheme != null && t != shownTheme) {
            Ui.applyTheme(prefs)
            shownTheme = Ui.theme
            recreate()
            return
        }
        // 从系统设置 / 引导页回来：重看一眼必要配置（悬浮窗权限 / 接口），缺就重新挂上提示条
        refreshSetupBar()
        // 在飞请求：回到前台就重新接上；期间已经回完的，attach 会当场把结果收掉重画
        // （后台线程不会回调到已经不显示的旧页）
        flight?.let { it.attach(this) }
    }

    override fun onPause() {
        super.onPause()
        // 断开弱引用：后台线程不再攥着、也不会回调到已经不显示的旧页；回前台 / 新实例会重新接上
        flight?.detach(this)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (::input.isInitialized) out.putString(KEY_DRAFT, input.text.toString())
        out.putString(KEY_PID, pid)
        out.putString(KEY_PENDING_IMG, pendingImg?.name)
    }

    /**
     * 旋转/recreate 时系统把在飞的 [ChatFlight] 原样交给新实例 —— 用 Android **原生** retained
     * nonconfiguration instance，不引库、不锁方向 / 不吞 configChanges。交出去前先和旧页断开：
     * 保留态里只有数据 + 一个弱引用，不会把已销毁的旧页留住。
     */
    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? {
        flight?.detach(this)
        return flight
    }

    @Suppress("DEPRECATION")
    private fun carriedFlight(): ChatFlight? = lastNonConfigurationInstance as? ChatFlight

    /**
     * 一轮请求收尾：后台线程跑完 [ChatFlight.deliver] 会喊，或者新实例 onResume 补收已经回完的结果。
     * 只在**还是这一轮**时生效 —— 用自增 id 认，迟到的回调不会误收下一轮。回复此刻已经落进发起时
     * 那个人格的会话文件（见 [runChatFlight]），这里只负责重画 + 必要的兜底提示。
     */
    /**
     * 在飞那一轮的进度行（v2.2.1：联网搜索 / 读网页的时间比较长，得让人看见在做什么）。
     * 只对当前那一轮生效；换人格 / 已经收尾的轮次不会来抢这一行。
     */
    internal fun onFlightPhase(id: Long, s: String) {
        val f = flight ?: return
        if (f.id != id || !asking) return
        if (s.isBlank()) return
        setStatus(s)
    }

    internal fun onFlightDone(id: Long) {
        val f = flight ?: return
        if (f.id != id) return
        flight = null
        setAsking(false)
        // 内容永远按**当前页**的 store 重画：draw() 读的就是当前人格的会话文件，不会把别的人格的消息画上来，
        // 所以不管这一轮属于谁，重画总是对的（也顺手把「正在回复…」状态清掉）。
        draw()
        // 但「没回话」这种用户可见的提示只有发起人还是当前页时才冒 —— 换页之后突然弹一句别人格的报错很莫名。
        if (f.blank && f.pid == pid) {
            toast("${f.orbName}没回话 —— 看看设置里的接口地址和 Key")
        }
    }

    /**
     * [会话列表] 返回：本页是任务根（从小球菜单「和小Ling聊聊」直接起来的，底下没有列表）就先把列表开出来再走，
     * 于是不管从哪儿进的聊天，返回都落在会话列表 —— 跟微信一样；底下本来就有列表就正常关掉本页。
     */
    private fun goBack() {
        if (isTaskRoot) {
            try { startActivity(Intent(this, ChatListActivity::class.java)) } catch (_: Exception) {}
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { goBack() }

    /** 设置页是二级入口（桌面图标落聊天页）。走显式Intent，不给 singleTask 的 MainActivity 带 extras。 */
    private fun openSettings() {
        try { startActivity(Intent(this, MainActivity::class.java)) } catch (_: Exception) { toast("打不开设置页") }
    }

    // ---------------- composer 左侧「＋」：图片 / 识别屏幕 / 弹单词 / 添加提醒 ----------------

    /**
     * [2.2] 四个原有入口收进一个菜单（原来「图片」在工具行、另外三个在折叠的「快捷操作」行）。
     * 触发逻辑一个字没改：图片走 [pickImage]，其余三个走 [actSolve] / [actWord] / [actRemind]。
     */
    private fun showTools(anchor: View) {
        val pm = ThemeDialogs.popup(this, anchor, Gravity.NO_GRAVITY)   // v2.7.3 统一弹层入口（锚点定位语义与原 PopupMenu 一致）
        pm.menu.add(0, MENU_IMAGE, 0, "图片")
        pm.menu.add(0, MENU_SOLVE, 1, "识别屏幕")
        pm.menu.add(0, MENU_WORD, 2, "弹单词")
        // 「添加提醒」落在记事页（todo 模块）。模块被用户关掉时这一项不出现 ——
        // 开关只在 registry 一处判，[actRemind] 里再兜一次底（菜单已弹出后用户在设置里关掉也有可能）。
        if (Features.isEnabled(this, "todo")) pm.menu.add(0, MENU_REMIND, 3, "添加提醒")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_IMAGE -> pickImage()
                MENU_SOLVE -> actSolve()
                MENU_WORD -> actWord()
                MENU_REMIND -> actRemind()
            }
            true
        }
        pm.show()
    }

    /**
     * 「识别屏幕」：走项目现有的 [QuickActivity]（它自己处理「等中转页收干净再截」的延迟），
     * 截的是**当前屏幕**（从这个聊天页点，拍到的自然就是本界面），再交给小珠解题 / 翻译。
     * 缺悬浮窗权限就先补权限，不假装已经开始识别。
     */
    private fun actSolve() {
        if (!Settings.canDrawOverlays(this)) { fixPermission(); return }
        // 只在真正要发起识别时才提示一次（缺权限那条路不弹，免得用户以为已经在识别）
        UsageHints.showOnce(this, HINT_SOLVE, "识别屏幕：拍下当前画面交给小珠解题 / 翻译")
        try { startActivity(QuickActivity.intent(this, QuickActivity.SOLVE)) }
        catch (_: Exception) { toast("打不开识别") }
    }

    /**
     * 「弹单词」：给小球发**一次** [OrbService.ACTION_WORD]，从词库里随机弹一张卡。
     *
     * 球没起来时这里**不用**补一次启动 —— [OrbService] 收到 ACTION_WORD 而小球还不存在时，会先按现有的建球
     * 流程把窗口加好、再在球存在之后弹词；于是无论冷启动多慢都不会「球没建好就空跑」，也不再需要固定
     * 700ms 去猜时机。缺权限 / 没词库 / 静音这些情况下由 Service 如实提示，不伪造「已开始」。
     */
    private fun actWord() {
        if (!Settings.canDrawOverlays(this)) { fixPermission(); return }
        // 只在真正要弹词时才提示一次（缺权限那条路不弹）
        UsageHints.showOnce(this, HINT_WORD, "弹单词：从你的词库里随机来一张单词卡")
        try { startService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_WORD)) }
        catch (e: Exception) { toast("启动悬浮球失败：" + (e.message ?: "")) }
    }

    /** 「添加提醒」：走现有的记事页（[TodoListActivity] 的「加一条」按句子里的时间就地解析），不用任何权限 */
    private fun actRemind() {
        // 记事页属于 todo 模块：关掉后不该还能从这里进去（球菜单、深链同理，都由 registry 判）。
        // 关掉的是**入口**，已有待办一条不删 —— 重新打开还在（见 [FeatureToggles] 第 2 条硬规则）。
        if (!Features.isEnabled(this, "todo")) {
            toast("待办与随手记已在「模块管理」里关掉 —— 设置 → 模块管理 可以重新打开")
            return
        }
        UsageHints.showOnce(this, HINT_REMIND, "添加提醒：一句话带时间就记成待办，例：明天 15:30 交周报")
        try { startActivity(TodoListActivity.intent(this, 0)) }
        catch (_: Exception) { toast("打不开记事页") }
    }

    // ---- 必要配置的跳转（只把人送到既有页面，不重复实现权限系统引导）----

    /** 必要配置提示条点一下：接口没填先去填接口，否则去补权限 */
    private fun routeSetup() { if (!prefs.hasApi()) openApi() else fixPermission() }

    /**
     * 补权限去哪儿：**新用户**（首次配置引导还没走完）先走既有的 MainActivity 必要配置流程；
     * 老用户直接去「一键开权限」页。判断只看 [Onboarding.shouldShow]，引导返回后本页不会自己再跳，不循环。
     */
    private fun fixPermission() {
        if (Onboarding.shouldShow(prefs.onboarded, prefs.onboardStarted, prefs.keys())) openSettings() else openPermission()
    }

    /** 「一键开权限」页（悬浮窗 / 无障碍 / 通知…一页页递到面前）；打不开退回设置页 */
    private fun openPermission() {
        try { startActivity(PermissionActivity.intent(this)) } catch (_: Exception) { openSettings() }
    }

    /** 接口缺配置：带着 EXTRA_API 去设置页翻到「接口」那一组（和面板「还没填接口」同一个落点） */
    private fun openApi() {
        try { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_API, true)) }
        catch (_: Exception) { toast("打不开设置页") }
    }

    /**
     * 必要配置状态提示条：悬浮窗权限 / 接口地址缺一就显示**一句**短文案（两条旧横幅合并，避免重复长文字）。
     * 从系统设置 / 引导页回来时 [onResume] 重刷一次。它只是一条提示，不接管输入区、不阻塞已配好接口的聊天。
     */
    private fun refreshSetupBar() {
        if (!::setupBar.isInitialized) return
        // 文案挪到 [ChatList.setupHint]（会话列表顶部那条提示也用它），原文不变
        val hint = ChatList.setupHint(!Settings.canDrawOverlays(this), !prefs.hasApi())
        if (hint == null) { setupBar.visibility = View.GONE; return }
        setupBar.visibility = View.VISIBLE
        setupLabel.text = hint
    }

    // ---------------- 图片 ----------------

    /** 选图（SAF，不要任何权限；选完系统把 Uri 回给我们） */
    private fun pickImage() {
        if (asking) return
        try {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) },
                REQ_PICK,
            )
        } catch (_: Exception) { toast("打不开相册") }
    }

    /** 选图回调（项目里统一用 startActivityForResult：录屏 / 导入那几条链也一样） */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("正在压缩…")
        // 解码 + 缩放 + 编码有几十到几百毫秒，别压在主线程（图片大了会掉帧）
        thread(name = "lz-chat-img") {
            val f = saveScaled(uri)
            runOnUiThread {
                if (f == null) { toast("这张图读不出来，换一张试试"); return@runOnUiThread }
                pendingImg = f
                showPending()
            }
        }
    }

    /**
     * Uri → 最长边不超过 [MAX_SIDE] 的 JPEG(80) → files/chat-img/img-<毫秒>.jpg。
     *
     * 为什么存文件而不是把 base64 塞进会话文件：一条 1280px 的图 base64 约 200KB，
     * 会话上限 200 条，几十张图就能把 chat-<pid>.json 撑到十几 MB（每次读写都要整份解析）。
     * 文件只在发出去那一刻读一次转 base64，会话文件里只留文件名。
     */
    private fun saveScaled(uri: Uri): File? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth; val h = bounds.outHeight
        if (w <= 0 || h <= 0) null else {
            var sample = 1
            while (w / sample > MAX_SIDE || h / sample > MAX_SIDE) sample *= 2
            val bmp: Bitmap? = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (bmp == null) null else {
                val dir = File(filesDir, ChatStore.IMG_DIR).apply { mkdirs() }
                val f = File(dir, "img-${System.currentTimeMillis()}.jpg")
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                bmp.recycle()
                f
            }
        }
    } catch (_: Exception) { null }

    /** 待发预览：小缩略图 + 一行说明 + 「移除」 */
    private fun showPending() {
        val f = pendingImg
        if (f == null) { pendingRow.visibility = View.GONE; return }
        pendingThumb.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        pendingLabel.text = "选好 1 张图，点发送一块儿发过去"
        pendingRow.visibility = View.VISIBLE
    }

    private fun clearPending() {
        pendingImg = null
        pendingThumb.setImageDrawable(null)
        pendingRow.visibility = View.GONE
    }

    // ---------------- 消息流 ----------------

    /**
     * 重画整条消息流。
     *
     * **[v2.1.1] 阅读位置**：默认**保留原来的 scrollY** —— 异步回复回来重画时，正在往上读的人不许被抢回底部
     * （原来这里无条件 `fullScroll(FOCUS_DOWN)`，回复一到就把人的位置顶没了）。
     *  - [showBottom] = true：**用户自己**刚发了消息 / 切到另一个人格 —— 显式贴底，让他的新消息看得见；
     *  - 重画前本来就停在底部（[atBottom]）→ 继续贴底（那是「跟着最新走」，不是抢位置）；
     *  - 其余情况（异步回复、旋转回来、回前台补收结果）→ 把 scrollY 放回去。
     */
    private fun draw(showBottom: Boolean = false) {
        val keepY = scroll.scrollY
        val toBottom = showBottom || atBottom
        col.removeAllViews()
        val p = persona()
        who.text = Personas.headerTitle(p)
        renderIcon(p)
        val list = store.list()
        // [会话列表] 画出来 = 看过了：已读位置推到最后一条（列表的未读数据此算）。只有本页在前台才会重画到这里
        // （在飞请求离开前台就 detach，不会替用户「看」掉后台回来的消息）。
        ChatListActivity.Marks(this).markRead(pid, list.lastOrNull()?.at ?: 0L)
        // [2.0.2] 等待回复时不再说「在想…」（那是假推理），只说「正在回复…」；正文尾部还会挂一条同样的状态
        setStatus(if (asking) "正在回复…" else ChatStore.status(list, System.currentTimeMillis()))
        if (list.isEmpty()) {
            // [2.2] 空态只留「一句主句 + 一句说明」，不铺按钮、不重复顶栏已经说过的身份。
            col.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            col.addView(TextView(this).apply {
                text = ChatStore.greeting(p); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Brut.INK)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(TextView(this).apply {
                text = "可以直接提问，也可以发送图片"; textSize = 12.5f; setTextColor(Brut.HINT)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(8f), dp(8f), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        col.gravity = Gravity.NO_GRAVITY
        for ((i, m) in list.withIndex()) {
            // [v1.85.4] 间隔超过 10 分钟插一条居中时间分隔 —— 「感应不到时间戳」：几点说的当场可核对
            val div = if (i > 0) ChatStore.dividerText(list[i - 1].at, m.at, System.currentTimeMillis())
                      else ChatStore.dividerText(null, m.at, System.currentTimeMillis())
            if (div != null) {
                col.addView(TextView(this).apply {
                    text = div; textSize = 11.5f; setTextColor(Brut.HINT)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12f), 0, dp(4f))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            // 同一个人连着说的收紧到 3dp：一眼能看出这是一段，不是每人一句来回
            val gap = if (i > 0 && list[i - 1].isOrb == m.isOrb) 3f else 10f
            col.addView(bubble(m, gap, p))
        }
        // [2.0.2] asking：正文尾部挂一条真实的「正在回复…」状态（不是假的推理过程）
        if (asking) col.addView(typingRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        // [v2.1.1] 阅读位置：要贴底才贴（用户自己发的消息 / 本来就在底部）；否则把 scrollY 原样放回去
        if (toBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        else scroll.post { scroll.scrollTo(0, keepY) }
    }

    /** 顶栏左侧头像位：重画成当前人格的矢量头像（[PersonaIcon] 自己画，不用 emoji） */
    private fun renderIcon(p: Persona) {
        idIconHost.removeAllViews()
        idIconHost.addView(PersonaIcon(this, p.iconKey, sizeDp = 34f))
    }

    /** 等待回复时正文尾部那一行「正在回复…」（左侧、小灰字，纯状态，不可点） */
    private fun typingRow(): View = TextView(this).apply {
        text = "正在回复…"; textSize = 12.5f; setTextColor(Brut.HINT)
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
    }

    /**
     * 一条消息。
     *
     * [2.2] 两种形态彻底分开，都不再有卡片描边 / 硬阴影：
     *  - **Agent**（[orbBubble]）：靠左、占**可用正文宽**，正文 16sp 直接铺在纸上（可长按选中、可点链接 / 公式），
     *    上方一行小字人格名 + 右侧 48dp「⋯」菜单（复制原文）。
     *  - **用户**（[userBubble]）：靠右收敛浅色圆角小块（纯文本可带图），宽不超过 [maxBubbleW]，无黑边。
     */
    private fun bubble(m: ChatStore.Msg, gapDp: Float, p: Persona): View =
        if (m.isOrb) orbBubble(m, gapDp, p) else userBubble(m, gapDp)

    /** Agent 回复：左侧、占满可用正文宽；无背景无描边，正文可选中 / 可点链接，辅助操作收进「⋯」 */
    private fun orbBubble(m: ChatStore.Msg, gapDp: Float, p: Persona): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 人格名是次要信息；「⋯」保持 48dp 触达，点开只有一条「复制原文」，不抢正文的长按选字。
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, p.name, 11.5f, Brut.HINT), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            // 「⋯」也是图标，按 dp 定字号，大字体下不撑破 48dp 触达框
            text = "⋯"; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f); setTextColor(Brut.HINT)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minimumWidth = dp(48f); minimumHeight = dp(48f)
            isClickable = true; isFocusable = true
            contentDescription = "更多操作"
            background = ripple(rounded(0, 24f), 24f)
            setOnClickListener { showMessageMenu(it, m.content) }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        wrap.addView(head, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(TextView(this).apply {
            text = Md.render(m.content, Brut.INK, Theme.argb(0x22, Brut.INK))
            textSize = 16f; setLineSpacing(0f, 1.45f); setTextColor(Brut.INK)
            setTextIsSelectable(true)                 // 正文可以长按选中
            movementMethod = Links.SelectableTap      // 点链接跳转，其余照常选字
            setPadding(0, 0, 0, dp(2f))
        }, LinearLayout.LayoutParams(-1, -2))
        wrap.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.START
            topMargin = dp(gapDp); bottomMargin = dp(4f)
        }
        return wrap
    }

    /** 「⋯」菜单：目前只有一条「复制完整原文」，走系统剪贴板，不新增任何历史字段 */
    private fun showMessageMenu(anchor: View, content: String) {
        val pm = ThemeDialogs.popup(this, anchor, Gravity.NO_GRAVITY)   // v2.7.3 统一弹层入口
        pm.menu.add(0, MENU_COPY, 0, "复制原文")
        pm.setOnMenuItemClickListener {
            if (it.itemId == MENU_COPY) copyText(content)
            true
        }
        pm.show()
    }

    /** 用户消息：靠右收敛的小块（纯文本可带图），宽不超过 [maxBubbleW]；瑞士下是直角细线空心块（见 [bubbleBg]） */
    private fun userBubble(m: ChatStore.Msg, gapDp: Float): View {
        // 瑞士：纸底上只留一条细线，不再掺主色（掺出来是粉的 —— 这套风格只有黑白 + 一个信号红）。
        // 玻璃：主色掺色底交给共享材质去画（轻透明层 + 高光边），掺色比例本身不动。
        val fill = when {
            swiss -> 0
            glass -> Theme.argb(0xFF, Theme.mix(Brut.CARD, Brut.MINT, 0.30f))
            else -> 0xFF000000.toInt() or Theme.mix(Brut.CARD, Brut.MINT, 0.30f)
        }
        val fg = if (swiss) Brut.INK else Brut.on(fill)
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END
            topMargin = dp(gapDp); bottomMargin = dp(4f)
        }
        val bmp = m.img?.let { imgFile(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bmp == null) {
            return TextView(this).apply {
                text = m.content; textSize = 16f; setLineSpacing(0f, 1.35f)
                setTextColor(fg)
                background = bubbleBg(fill)
                setPadding(dp(14f), dp(10f), dp(14f), dp(11f))
                maxWidth = maxBubbleW
                layoutParams = lp
            }
        }
        // 图片尺寸自己算：交给 adjustViewBounds 的话，父布局（这个块）的宽度会跟着图缩，
        // 下面那行说明就按更宽量、然后被裁掉（v1.79 实测踩过）。显式定尺寸，块宽再取「图和字里更宽的那个」。
        val padL = dp(8f); val padR = dp(8f)
        val maxW = maxBubbleW - padL - padR
        val k = minOf(maxW.toFloat() / bmp.width, dp(IMG_MAX_H_DP).toFloat() / bmp.height, 1f)
        val iw = maxOf(dp(48f), (bmp.width * k).toInt())
        val ih = maxOf(dp(48f), (bmp.height * k).toInt())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBg(fill)
            setPadding(padL, dp(8f), padR, dp(10f))
        }
        box.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            roundCorners(10f)
        }, LinearLayout.LayoutParams(iw, ih))
        val cap = if (m.content.isBlank()) null else TextView(this).apply {
            text = m.content; textSize = 16f; setLineSpacing(0f, 1.35f); setTextColor(fg)
            setPadding(0, dp(8f), 0, 0)
            maxWidth = maxW
        }
        cap?.let { box.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        // 量一下说明要多宽（这一次量只是为了定框宽，真正的布局等下走正常流程）
        val capW = cap?.let {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            it.measuredWidth
        } ?: 0
        lp.width = minOf(maxBubbleW, maxOf(iw, capW) + padL + padR)
        box.layoutParams = lp
        return box
    }

    /** 「复制」：把回复原文写进系统剪贴板（不新增任何历史字段） */
    private fun copyText(s: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("小Ling", s))
            toast("已复制")
        } catch (_: Exception) { toast("复制失败") }
    }

    // ---------------- 轻量形状（这一页自己的视觉，不改其它页面） ----------------

    /**
     * v2.6 瑞士国际主义：这一页是手画形状、不走 [Brut.style]，所以形状语言得自己跟 [Ui.shape] 走。
     * 判据与 Brut 内部同一口径（投影为 0 = 「无投影」的那套）—— 主题包显式设过形状也照样生效。
     */
    private val swiss: Boolean get() = Ui.shape.shadowDp <= 0f

    /** 当前配色是不是液态玻璃材质（v2.7.3）：只决定「谁来画底」，气泡 / 输入 / 触摸行为都不变 */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 圆角档位：平时用调用点给的（页面自己的层级），瑞士一律取 [Ui.shape] 的直角 */
    private fun corner(radiusDp: Float): Float = if (swiss) Ui.shape.cornerDp else radiusDp

    /** 圆角实心块：无描边、无投影（用户消息 / 按压底）。瑞士下圆角为 0，形同一块方底 */
    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(corner(radiusDp)).toFloat()
    }

    /** 圆角 + 一档淡描边（composer）：描边透明度由调用方给，六套配色都跟着当前 INK 走 */
    private fun outlined(fill: Int, radiusDp: Float, strokeDp: Float, stroke: Int): GradientDrawable =
        rounded(fill, radiusDp).apply { setStroke(dp(strokeDp).coerceAtLeast(1), stroke) }

    /** 统一按压反馈：系统 ripple，边界和内容同一形状（不引第三方库、不自己画状态机） */
    private fun ripple(content: Drawable, radiusDp: Float): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)),
            content,
            rounded(0xFFFFFFFF.toInt(), radiusDp),   // 显式圆角遮罩：按下水波不会跑出圆角
        )

    /** 给 ImageView 加圆角（API 21+ 的 outline 裁切，不用第三方库也不用再去包一层 drawable） */
    private fun ImageView.roundCorners(radiusDp: Float) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(corner(radiusDp)).toFloat())
        }
        clipToOutline = true
    }

    /**
     * 用户消息的底：圆角实心块（无描边、无投影），[fill] 由 [userBubble] 按主题给。
     *
     * 瑞士下改成**直角 + 1dp 细线空心块**：收发靠「靠右 / 靠左」和这条线区分，不新增第二种彩色。
     * 描边粗细取 [Ui.shape]，于是主题包换形状时这里跟着走。
     */
    private fun bubbleBg(fill: Int): Drawable = when {
        glass -> Ui.material(this, fill, Ui.SurfaceRole.CARD, 18f)
        else -> rounded(fill, 18f).apply {
            if (swiss) setStroke(maxOf(1, dp(Ui.shape.strokeDp)), Brut.INK)
        }
    }

    /**
     * 瑞士的分栏线：底色不动，只在**底边**（[bottom] = true）或**顶边**画一条 [Shape.strokeDp] 的细线。
     *
     * 为什么画在自身内边而不是插一个 1dp 高的 View：`applyClamp` 的固定高预算按「顶栏 + composer」实测算，
     * 多插一个兄弟 View 就得同步加进那份预算；画进背景则高度一个字都不用改（线条落在内边距里）。
     */
    private fun ruleBg(fill: Int, bottom: Boolean): Drawable = LayerDrawable(arrayOf(
        GradientDrawable().apply { setColor(fill) },
        GradientDrawable().apply { setColor(Brut.INK) },
    )).apply {
        setLayerGravity(1, if (bottom) Gravity.BOTTOM else Gravity.TOP)
        setLayerHeight(1, maxOf(1, dp(Ui.shape.strokeDp)))
    }

    /**
     * 瑞士的输入形态：**只有一条下划线**，没有整圈框（完整的框会把它变成一张表格）。
     * 聚焦时线加粗到 [Shape.focusStrokeDp] 并变成实心墨色 —— 这是这套风格里唯一的聚焦信号。
     * 下划线用 [LayerDrawable] 贴底画一条实色条：`setStroke` 画的是四边，那不是下划线。
     */
    private fun underlineInput(): Drawable = StateListDrawable().apply {
        fun line(wDp: Float, col: Int): Drawable =
            LayerDrawable(arrayOf(GradientDrawable().apply { setColor(col) })).apply {
                setLayerGravity(0, Gravity.BOTTOM)
                setLayerHeight(0, maxOf(1, dp(wDp)))
            }
        addState(intArrayOf(android.R.attr.state_focused), line(Ui.shape.focusStrokeDp, Brut.INK))
        addState(intArrayOf(), line(Ui.shape.strokeDp, Theme.argb(0x55, Brut.INK)))
    }

    /** 会话里记的文件名 → files/chat-img/<名字> */
    private fun imgFile(name: String) = File(File(filesDir, ChatStore.IMG_DIR), name)

    // ---------------- [MP] 人格切换 ----------------

    /**
     * 一个「图标 + 主标题 + 副标题」的列表对话框（v1.85）。
     *
     * 原来这两个对话框都用 `setItems` 的**纯文字行**，头像只能塞进文字里（`"🙂 小Ling 什么都能问"`）——
     * emoji 的字形宽高/基线各家 ROM 都不一样，和中文挤在一行就会错位（用户报的聊天页「重叠」就是这一类）。
     * 现在每行左边是 [PersonaIcon] 画的矢量图，副标题**单行截断**，各占各的位置。
     * 行 = (iconKey, 主标题, 副标题?)；`onPick` 收到行的下标。
     */
    private fun iconListDialog(title: String, rows: List<Triple<String, String, String?>>, onPick: (Int) -> Unit): android.app.AlertDialog =
        PersonaPicker.iconListDialog(this, title, rows, onPick)   // [会话列表] 挪到 PersonaPicker 共用，行样式不变

    /**
     * 点顶栏名字 → 人格列表。一行一张：**矢量头像** + 名字 + 职责一句话（v1.85 起不再用 emoji）。
     * 底部一个「+ 新建 Agent」—— 走四个模板（学习教练 / 生活管家 / 编程搭子 / 空白）。
     */
    private fun pickPersona() {
        // 请求飞着的时候不许切 / 新建人格：切了会换会话文件 —— 这一轮的回复和提炼出的记忆会落到发起时
        // 那个人格名下（用户看到「切过去了，结果跑上一个 Agent 去了」），而且切换时会 clearPending()，
        // 手上的草稿 / 待发图一起丢。回完再切。这里只是开列表前的第一道；真正切之前 switchTo / newPersona 还会再判一次。
        if (asking) { toast("${persona().name}还在回话，回完再切"); return }
        val list = personas.list()
        val rows = list.map { Triple(it.iconKey, it.name, it.role) } +
            Triple(PersonaIcon.K_CHAT, "＋ 新建 Agent", "从模板起一个（学习教练 / 生活管家 / 编程搭子 / 空白）")
        iconListDialog("跟谁聊", rows) { i ->
            if (i < list.size) switchTo(list[i].id) else newPersona()
        }.show()
    }

    /** 切人格 = 换 pid + 换聊天文件 + 换 system。存的是 id，重开应用还在这个人格 */
    private fun switchTo(id: String) {
        // 最终兜底：pickPersona 打开列表时拦过一次，但从「点开列表」到「选中一行」之间对话可能又飞起来了 ——
        // 真动 pid 之前再判一次，别让这一轮的回复 / 记忆落到别的人格名下，也别把草稿 / 待发图丢了
        if (asking) { toast("${persona().name}还在回话，回完再切"); return }
        if (id == pid) return
        pid = id
        personas.setCurrent(id)
        store = ChatStore.Store(this, id)
        clearPending()
        draw(showBottom = true)   // 换了一个人格 = 换了一整段会话：直接给他看最新的几条
    }

    /** 新建：先选模板（矢量头像，不再用 emoji），选完直接建好并切过去（改「职责一句话」留给下一轮的人格卡页） */
    private fun newPersona() {
        // 同上：从「＋新建」走到真正建号之前也要再判一次 —— 回话中建号会建完切不过去，白留一个空号
        if (asking) { toast("${persona().name}还在回话，回完再建"); return }
        PersonaPicker.newFromTemplate(this, personas) { id -> switchTo(id) }
    }

    // ---------------- 发消息 ----------------

    /** 一条消息可以只有图（没文字），但不能两者都没有 */
    private fun ask() {
        if (asking) return
        val text = input.text.toString().trim()
        val img = pendingImg
        if (text.isEmpty() && img == null) { toast("先说点什么"); return }
        if (!prefs.hasApi()) { toast("还没填接口地址 —— 右上角「设置」→ 接口"); return }

        // 发起这一刻就把身份钉死：pid / 人格 / 接口配置 / 会话快照一次取好。
        // 之后后台线程和重画回调只认这份冻结值，**绝不再回头看可变的 pid / store / persona()** ——
        // 否则等待中切了人格，回复和提炼出的记忆就会落到另一个人格名下（串台）。
        val pid0 = pid
        val app = applicationContext
        val p = persona()

        val msg = store.say(ChatStore.user(text, System.currentTimeMillis(), img = img?.name))
        input.setText("")
        clearPending()
        setAsking(true)
        // [M4] 直白的提醒：本地认得出「提醒我 20 分钟后喝水」「每周一三五晚上八点提醒我背单词」这种
        // → 当场建待办 / 建记期规则 + 回一句确认。
        // 放在用户消息之后、模型回复之前 —— 用户下一眼就能看到「已记下」，不用等接口回来。
        // 用户可能正发图（text 空）：plan 自带空串判定，不会误建。
        val local = Remind.plan(text, msg.at)
        if (!local.isEmpty) {
            rememberPlan(app, local)
            store.say(ChatStore.orb(Remind.confirmPlan(local, msg.at), msg.at))
        }
        // [v2.1.1] 用户自己发的：显式贴底，让他当场看见自己那条新消息（+「正在回复…」）。
        // 之后异步回复回来走的是默认 draw()（保留 scrollY）—— 只有他本来就在底部才继续贴底。
        draw(showBottom = true)

        val cfg = prefs.api()
        // 请求消息快照：**这一刻**的会话（含刚写进去的 user 消息 + 本地确认那条）。冻结后不再 store.list()。
        val snapshot = store.list()

        // 身份 / system / 记忆检索 / 网全在后台线程里用这份冻结值算（[runChatFlight]）——
        // 这样读文件不占主线程，而且等待中切了人格也影响不到这一轮。
        val f = ChatFlight(
            app = app, pid = pid0, persona = p, text = text, orbName = p.name, cfg = cfg,
            snapshot = snapshot, saveHistory = prefs.saveHistory, img = img, at = msg.at,
            memOn = prefs.agentMemOn,
        )
        flight = f
        ChatFlights.put(f)
        f.attach(this)
        runChatFlight(f)
    }

    private fun setAsking(on: Boolean) {
        asking = on
        send.isEnabled = !on
        input.isEnabled = !on
        // [2.0.2] 禁用规则照旧，只是文案直说「回复中」，不用省略号。
        // 两段文案的宽度已在建按钮时按**较长那条**钉死，这里换字不会让 composer 抖动。
        send.text = if (on) BUSY_LABEL else SEND_LABEL
    }

    /**
     * 顶栏状态行。文案真变了才做一次 [Anim.MICRO] 的短 alpha 过渡（0.45 → 1），
     * 不循环、不重放、不碰消息流（重画与滚动位置完全不受影响）。
     * 系统关掉动画（[android.animation.ValueAnimator.areAnimatorsEnabled] 为 false）时直接写死，不留半透明。
     */
    private fun setStatus(s: String) {
        if (status.text.toString() == s) return
        status.text = s
        // 还没上屏（首次 draw 之前）或系统关掉动画：直接写死，不为第一次填字 / 无动画环境闪一下
        if (status.height == 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            status.alpha = 1f
            return
        }
        status.animate().cancel()
        status.alpha = 0.45f
        status.animate().alpha(1f).setDuration(Anim.MICRO).setInterpolator(AnimGlue.EASE_OUT).start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 选图的请求码（别和 MainActivity 的录屏 7 / 导入 4712 / 通知 8 撞） */
        private const val REQ_PICK = 91

        /** 发送按钮的两段文案。两段字数不同，宽度按较长那条钉死（见 onCreate 的 sendW） */
        private const val SEND_LABEL = "发送"
        private const val BUSY_LABEL = "回复中"

        /** 图片最长边（1280 是识屏那条线一直在用的口径，接口那边也认这个尺寸） */
        private const val MAX_SIDE = 1280

        /** 气泡里那张图最多多高（dp）：再高就该长按看大图了，聊天流里不该压过一整屏 */
        private const val IMG_MAX_H_DP = 200f

        /** 「＋」菜单里的四项（原来是「图片」按钮 + 快捷操作行的三个按钮） */
        private const val MENU_IMAGE = 1
        private const val MENU_SOLVE = 2
        private const val MENU_WORD = 3
        private const val MENU_REMIND = 4

        /** 回复「⋯」菜单：复制完整原文 */
        private const val MENU_COPY = 5

        /** 旋转/recreate 恢复用：草稿文字 + 待发图文件名（EditText 没 id，系统不会自动存） */
        private const val KEY_DRAFT = "chat_draft"
        private const val KEY_PENDING_IMG = "chat_pending_img"
        private const val KEY_PID = "chat_pid"

        /** [会话列表] 列表点进来时带的人格 id */
        const val EXTRA_PID = "com.chun.lingzhu.extra.PID"

        /** 某个 Agent 的聊天页（会话列表点一行用） */
        fun open(ctx: android.content.Context, pid: String) =
            android.content.Intent(ctx, ChatActivity::class.java).putExtra(EXTRA_PID, pid)

        /** 桌面图标那一下（MAIN + LAUNCHER）。纯判断，别的入口一律 false */
        fun isLauncherLaunch(i: android.content.Intent?): Boolean =
            i != null && i.action == android.content.Intent.ACTION_MAIN && i.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)

        /** 常用动作「只提示一次」的 id（存进 [UsageHints] 的独立 key，跨旋转 / 进程不重复） */
        private const val HINT_SOLVE = "action.solve"
        private const val HINT_WORD = "action.word"
        private const val HINT_REMIND = "action.remind"

        /** 小球菜单里那一条（Service 里没有 Activity 上下文，要自己带 NEW_TASK） */
        fun intent(ctx: android.content.Context) =
            android.content.Intent(ctx, ChatActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * [会话列表] 进程内「谁还在回话」登记表：pid → 在飞的那一轮。
 * 用途两个：① 返回列表后再点进同一个 Agent（新实例）能接上那一轮；② 会话列表那一行显示「正在回复…」。
 * 只存在内存里 —— 进程没了请求也就没了，没有要落盘的东西。
 */
internal object ChatFlights {
    private val live = java.util.concurrent.ConcurrentHashMap<String, ChatFlight>()
    fun put(f: ChatFlight) { live[f.pid] = f }
    fun done(f: ChatFlight) { live.remove(f.pid, f) }
    fun live(pid: String): ChatFlight? = live[pid]?.takeIf { !it.finished }
    fun busy(pid: String): Boolean = live(pid) != null
}

/** 每轮在飞请求一个自增号：重画回调认号不认对象，迟到的回调不会把下一轮的结果收走 */
private val chatFlightSeq = AtomicLong(0L)

/**
 * 一次在飞请求的保留态（[ChatActivity.onRetainNonConfigurationInstance] 交给新实例的就是它）。
 *
 * **绝不能持有 Activity**：里面只有发起时冻结的身份 / 请求快照 + 结果位，加一个指向当前前台页的
 * **弱引用**（只用来喊重画）。文件读写全走 [app]（applicationContext），于是后台线程、旋转后的新实例
 * 都能独立完成落盘与重画，旧页也不会被攥住。
 *
 * 冻结的身份是关键：回复和提炼出的记忆一律按发起时的 [pid] 走，等接口期间切了人格也不会串台。
 */
internal class ChatFlight(
    val app: Context,
    val pid: String,                    // 发起时的人格 id —— 回复 / 记忆都钉死在它上面
    val persona: Persona,               // 发起时那个人格（system 段 / 记忆卡都按它取，之后变了也不影响这一轮）
    val text: String,                   // 发起时那句用户原文（提炼记忆用）
    val orbName: String,                // 人格名（「没回话」兜底文案用）
    val cfg: ApiConfig,
    val snapshot: List<ChatStore.Msg>,  // 发起时的会话快照（含本轮 user 消息 + 本地确认那条）
    val saveHistory: Boolean,           // 发起时「保存识屏历史」开关（决定要不要带识屏记录摘要）
    val img: File?,                     // 本轮刚发的图（读 base64 用）
    val at: Long,                       // 本轮 user 消息的时间戳（按它认出那条图）
    val memOn: Boolean,                 // 发起时「Agent 记忆」开关（后台不再回头读 Activity 的 prefs）
) {
    val id: Long = chatFlightSeq.incrementAndGet()

    /** 结果已回（成功 / 失败都会置 true）。后台线程写、主线程读 → volatile */
    @Volatile var finished = false
    /** 接口答了但正文是空的 → 走「没回话」兜底提示 */
    @Volatile var blank = false
    /** 当前前台页；弱引用保证不会把已销毁的旧页留住 */
    @Volatile private var host: WeakReference<ChatActivity>? = null

    fun attach(a: ChatActivity) {
        host = WeakReference(a)
        // 先写 host 再看 finished：靠 volatile 的全序把「后台读不到 host、前台又读到 finished=false」这个
        // 丢唤醒窗口关掉（两边的程序顺序凑不出合法全序）—— 已经回完的当场收，没回完的就等 deliver。
        if (finished) a.onFlightDone(id)
    }

    fun detach(a: ChatActivity) { if (host?.get() === a) host = null }

    /**
     * 后台线程报一句进度（「正在联网搜索…」「正在读取网页…」）。
     * 没有前台页就丢掉 —— 进度只是提示，绝不能因为它影响请求本身。
     */
    fun phase(s: String) {
        val a = host?.get() ?: return
        a.runOnUiThread { if (host?.get() === a) a.onFlightPhase(id, s) }
    }

    /** 后台线程收尾时调：有前台页就让它重画；没有（销毁 / 后台）就留着，等新页 onResume 自己来收 */
    fun deliver() {
        val a = host?.get() ?: return
        val mine = id
        a.runOnUiThread { if (host?.get() === a) a.onFlightDone(mine) }
    }
}

/**
 * 把一轮在飞请求跑完：网络 → 解析提醒标记 → 落盘 →（可选）提炼记忆。
 *
 * **全程只用 [f] 里冻结的 pid / 请求快照 + applicationContext**，一次都不碰 Activity：于是等待中切了
 * 人格也不会串台（回复永远落回发起时那个 Agent），旋转回收后线程照样能把结果写下来。跑完更新结果位
 * 并喊当前页重画；当前页不在就留着，等新页 onResume 收。
 */
private fun runChatFlight(f: ChatFlight) {
    thread(name = "lz-chat") {
        // v2.2.1：聊天也接入联网搜索（用户明确拍板「聊天接搜索」）。
        // 开关 / 来源 / Key 全在冻结的 [f.cfg.searchConfig] 里，和识屏那条线同一套配置与协议；
        // 只是聊天没有截图：请求体换成流式 + tools，工具轮由 streamWithSession 跑完，正文照旧一次性落盘。
        val search = WebSearch.session(f.cfg.searchConfig, f.cfg)
        // v2.3.1：工具循环不再由联网开关决定。联网工具（web_search / web_fetch）跟着搜索开关走，
        // 但本机工具（建卡 / 提醒 / 查记忆 / 报账 / 看台账 / 排计划）不需要网络，
        // 关掉联网也该能用 —— 否则用户说「帮我记一张卡」只能得到一句「好的我记住了」。
        // online 现在只表示「要不要给联网工具」，工具循环本身始终走。
        val online = search.active
        // [MP] system 跟着**发起时**的人格走：人格段（空则回落 PERSONA）+ 这个人格自己的记忆卡
        val sys0 = ChatStore.systemPrompt(f.persona, MemoryCard.Store(f.app, f.pid).get().takeIf { !it.isEmpty })
        val sys = if (online) sys0 + WebSearch.toolPrompt() else sys0
        // [v1.87] 每轮实时告诉模型「现在几点」：提醒标记（ChatStore.REMIND_RULE）要本机当地时间，
        // 但此前从没把当前值交给模型，它只能瞎猜。时间行走 suffix 开头 —— **不进 system**，system 是前缀
        // 缓存锚点（见 ChatStore.body），时间每轮都变。每轮现取 System.currentTimeMillis()：不是会话创建时
        // 冻结的那个值，更不是编译期常量。
        var suffix = "\n\n" + ChatStore.nowLine(System.currentTimeMillis())
        // 问法里带「总结/梳理最近的识屏记录」才把最近几条识屏结果接上（[ChatStore.wantsHistory]）。
        // 没命中、历史是空的、设置里关了保存历史 —— 三种情况都不追加（时间行始终在，见上）。
        if (f.saveHistory && ChatStore.wantsHistory(f.text)) {
            suffix += ChatStore.historySummary(History.Store(f.app).list(), System.currentTimeMillis())
        }
        // [v1.86] Agent 长期记忆：发消息前先在本机库里检索几条相关的（纯本地，毫秒级），拼进 suffix
        // （**不进 system** —— system 是前缀缓存的锚点，每轮检索结果都变，塞进去等于每轮全价重算）。
        // 按**冻结的 pid** 取，等待中切了人格也不会把别人格的记忆带进来。
        if (f.memOn) {
            val memStore = AgentMemory.Store(f.app, f.pid)
            val (hits, bumped) = AgentMemory.top(f.text, memStore.get(), System.currentTimeMillis())
            if (hits.isNotEmpty()) {
                memStore.put(bumped)
                suffix += AgentMemory.contextBlock(hits)
            }
        }
        var ok = false
        // 联网搜索真返回过来源时，App 自己补一行「已联网 · N 条来源」（不靠模型写来源行）
        var searchNote = ""
        val reply = try {
            // [v1.79] 只有这一刻刚发的那张图带 base64（按文件名认；历史里的图不带，退化成「[图片]」占位）
            val b64 = f.img?.let { runCatching { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }.getOrNull() }
            // v2.7.1：发出去的历史先过一遍压缩（按 token 预算，不看条数）。
            // 装得下就整份原样发、一次额外请求都不发；压不成时要么整份会话照发、要么这次不发（见 [historyFor]）。
            val history = historyFor(f, sys, suffix, search)
            // withTools 恒为 true：Bodies.tools 里常驻挂 AgentTools 的本机工具，联网工具由它自己按
            // session.active 决定加不加。这里若跟着 online 走，关掉联网就等于把建卡 / 提醒也一起关了。
            val body = ChatStore.body(
                f.cfg, f.snapshot, system = sys, suffix = suffix,
                stream = true, withTools = true, history = history,
                imgB64 = { m -> if (f.img != null && m.at == f.at) b64 else null },
            )
            // 一律走工具循环（搜索 / 抓网页 / 建卡 / 提醒 / 查记忆 / 报账 / 看台账 / 排计划都在那边收口）。
            // f.app 是发起时冻结的 applicationContext，f.pid 是冻结的人格 —— 等待中切人格也不会串台。
            val text = ApiClient(f.cfg).streamWithSession(
                body,
                object : ApiClient.Listener {
                    override fun onText(text: String) {}
                },
                AtomicBoolean(false),
                fallbackQuery = f.text,
                session = search,
                onPhase = { ph -> f.phase(ph) },
                tools = AppToolContext(f.app, f.pid),
                // 用户在聊天框里说话就是要办事，首轮 required 逼模型先动手，别只回一句「好的我来安排」
                agentMode = true,
            ).first
            if (online) searchNote = search.sourcesLine()
            ok = text.isNotBlank()
            text.trim()
        } catch (e: Exception) {
            NetErrors.friendly(e, f.cfg.base)
        }
        // 落盘：用**冻结的 pid** 另开一个 Store（同 applicationContext 的 filesDir，[FileMemo] 按路径共享）——
        // 不管当前页面是谁、还在不在，回复都进发起时那个人格的会话文件。
        val store0 = ChatStore.Store(f.app, f.pid)
        val nowR = System.currentTimeMillis()
        if (reply.isNotBlank()) {
            // [M4] 模型回复里可能带了提醒 / 记期标记：剥掉再展示（标记一行都不许漏到界面），
            // 解析出的提醒照直白那条链建掉 + 回一句确认。
            val (shown, asks, rules) = Remind.fromReplyFull(reply, nowR)
            val plan = Remind.Plan(asks, rules)
            if (!plan.isEmpty) rememberPlan(f.app, plan)
            // shown 为空 = 整条回复只有标记：别把空消息灌进会话，用一句兜底
            val body0 = shown.ifBlank { if (plan.isEmpty) "" else "好，记下了。" }
            // 来源行只进「落盘 + 展示」这一条：提炼记忆用的是干净的 reply，不把网址当记忆存进去
            val body = body0 + if (searchNote.isNotEmpty() && body0.isNotEmpty()) searchNote else ""
            if (body.isNotEmpty()) store0.say(ChatStore.orb(body, nowR))
            if (!plan.isEmpty) store0.say(ChatStore.orb(Remind.confirmPlan(plan, nowR), nowR))
        }
        // [v1.86] 这一轮真答上了才值得提炼记忆（报错文案提取不出东西）。后台小线程，同样钉死在 f.pid 上。
        if (ok && f.memOn) {
            thread(name = "lz-mem") {
                runCatching {
                    val mems = AgentMemory.Extractor(f.cfg).run(f.text, reply)
                    if (mems.isNotEmpty()) AgentMemory.Store(f.app, f.pid).mergeAndPut(mems)
                }
            }
        }
        f.blank = reply.isBlank()
        f.finished = true
        ChatFlights.done(f)
        f.deliver()
    }
}

/**
 * 一次非流式 chat/completions；返回正文。失败抛 [ApiException]（消息已经是大白话）。
 *
 * 现在只给会话压缩用（[historyFor]）。**`finish_reason == "length"` 一律当失败**：
 * 那表示摘要被 max_tokens 截断了，半截摘要写进 sidecar 等于把「没说完的旧事」伪装成完整记忆。
 */
private fun chatPost(cfg: ApiConfig, body: String): String {
    if (cfg.base.isEmpty()) throw ApiException("还没填接口地址（右上角「设置」→ 接口）")
    val conn = (URL(cfg.base + "/chat/completions").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15_000
        readTimeout = 90_000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException("HTTP $code" + chatHttpHint(code) + " " + conn.chatErrHint())
        val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val choice = try { JSONObject(txt).getJSONArray("choices").getJSONObject(0) } catch (_: Exception) {
            throw ApiException("自动整理对话历史失败：接口返回的不是正常的 chat 响应")
        }
        if (choice.optString("finish_reason") == "length") {
            throw ApiException("自动整理对话历史失败：摘要被输出上限截断，这次先不发 —— 再试一次")
        }
        return choice.optJSONObject("message")?.optString("content", "").orEmpty().trim()
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(NetErrors.friendly(e, cfg.base))
    } finally {
        conn.disconnect()
    }
}

/**
 * 这一轮真正要发出去的历史（不含 system）：装得下就整份会话原样返回（一次额外请求都不发），
 * 超窗才是「更早对话的摘要 + 完整尾部 exchange」（[AgentContext.compact]）。
 *
 * **是否要压只由 token 预算判**：[AgentContext.plan] 把「已验摘要 + 未压到的后缀」按真实口径估算，
 * 加上 [fixedReserve]（system / 本轮 suffix / 工具定义）与输出预留超过窗口才算要压。
 * 条数不再参与判断 —— 几十条短消息装得下就一条不压，「>26 条」那条提示已摘除。
 *
 * **压缩失败一律停止这一轮**：异常原样抛到 [runChatFlight] 的 catch，由已有的错误气泡把原因
 * （接口报错 / 消息太长 / 会话刚被清空）显示给用户。不做任何「装得下就凑合发」的兜底 ——
 * 那要么让用户看不见失败，要么在会话刚被清空时把旧历史再发出去，两者都比「重试一次」糟。
 */
private fun historyFor(
    f: ChatFlight,
    sys: String,
    suffix: String,
    search: WebSearch.Session,
): List<ChatStore.Msg> {
    val store = AgentContext.SummaryStore(File(f.app.filesDir, AgentContext.file(f.pid)))
    return AgentContext.compact(
        f.cfg, f.snapshot, store,
        ask = { body -> chatPost(f.cfg, body) },
        fresh = { ChatStore.Store(f.app, f.pid).list() },
        reserve = fixedReserve(f, sys, suffix, search),
        onLog = { s -> android.util.Log.i("LingCtx", s) },
        // 只有真要做压缩（plan 非空）才闪这一行：短聊天 / 条数多但装得下都不提示
        onCompacting = { f.phase("正在整理更早的对话…") },
        // 这一轮已经不是该人格「在飞的那一轮」＝被打断（新请求顶掉 / 页面重开换了一轮）：
        // 立刻停，不写摘要、也不把整理到一半的结果发出去。不新增任何取消入口，只认已有登记表。
        cancelled = { ChatFlights.live(f.pid) !== f },
    )
}

/**
 * 冻结前缀（system / 本轮 suffix / 工具定义）换算成的 token 预留。
 *
 * 工具定义只在**这里量一次长度**：和 [Bodies.tools] 真正挂进请求的是同一份 JSON，
 * 不为了计数再造一个请求体、也不新增模型目录 / 设置项。
 */
private fun fixedReserve(f: ChatFlight, sys: String, suffix: String, search: WebSearch.Session): Int {
    val tools = runCatching {
        AgentTools.toolsJson(f.cfg.agentScope, f.cfg.disabledFeatures).length +
            (if (search.active) WebSearch.toolsJson(search.canFetch).length else 0)
    }.getOrDefault(0)
    return AgentContext.estimateFixed(sys.length, suffix.length, tools)
}

/** 常见的几个状态码补一句人话（和 ApiClient 那一套口径一致） */
private fun chatHttpHint(code: Int) = when (code) {
    401, 403 -> "（Key 不对或没权限）"
    404 -> "（地址不对：一般要以 /v1 结尾）"
    429 -> "（请求太频繁或额度用完）"
    in 500..599 -> "（服务端出错，稍后再试）"
    else -> ""
}

private fun HttpURLConnection.chatErrHint(): String =
    try {
        val b = errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        JSONObject(b).optJSONObject("error")?.optString("message").orEmpty().ifBlank { b.take(120) }
    } catch (e: Exception) { "" }

/**
 * [M4] 把识别出的计划落成真东西：一次性提醒建成待办（**[Todo.Store.add] 只落盘、不排闹钟** ——
 * 到点要响得自己 `schedule` 一趟），重复的建成记期规则（建完 [Schedule.sync] 一趟把未来两周的点物化成待办）。
 * 传 applicationContext 也能跑：聊天页的后台线程 / 回收后落盘都走它。
 */
private fun rememberPlan(ctx: Context, plan: Remind.Plan) {
    val todos = Todo.Store(ctx)
    for (a in plan.asks) {
        val id = todos.add(a.text, a.dueAt)
        if (id > 0) TodoReceiver.schedule(ctx, id)
    }
    if (plan.rules.isNotEmpty()) {
        val sch = Schedule.Store(ctx)
        for (r in plan.rules) sch.add(r)
        Schedule.sync(ctx)
    }
}
