package com.chun.lingzhu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 和小珠聊聊（v1.69 陪伴 M2 → v1.79 换皮 + 图片）。
 *
 * 结构（[2.0.2] 重做）：紧凑顶栏（左侧矢量头像 + 名字/状态两行，整组点一下弹人格列表；右侧常驻「设置」）
 * →「快捷操作」折叠行（展开才是识别屏幕 / 弹单词 / 添加提醒，都在聊天内容区之外）→（必要配置提示条）
 * → 中间消息流（Agent 靠左占满正文宽、用户靠右小卡）→ 底部整宽多行输入框 + 单独工具行（左图片 / 右发送）。
 * 全是原生 View，项目不用 Compose。
 *
 * **[2.1.0] 布局不变量（修「键盘展开时工具行 / 发送被挤出窗口」）**：
 * 顶栏 + 分隔线**常驻不滚动**；「快捷操作 + 配置提示」收在 [chromeScroll]（默认不滚，可用高不足时滚动）；
 * 消息流 weight=1 占中间；composer（输入框 + 工具行）整块固定最底、**永不被挤出**。
 * 键盘弹出时 root 底部垫 IME 高，可用高变矮；若固定内容之和超过可用高，LinearLayout 的 weight 孩子无法吸收负数，
 * 排在最后的 composer 会整体溢出被裁掉 —— 由 applyClamp 两把锁兜底：chromeScroll 超高可滚、输入框高度受剩余空间上限约束。
 *
 * **[v2.1.1] 阅读位置不变量（修「异步回复一到就把正在往上读的人抢回底部」）**：
 * 只有**本来就在底部**（[atBottom]）或者**用户自己刚发消息 / 切人格**（`draw(showBottom = true)`）才贴底；
 * 其余重画（异步回复回来、回前台补收结果、旋转回来）都把原来的 scrollY 放回去；键盘弹出改高度时同理。
 * 原来 `draw()` 和「高度变了就滚到底」两处都无条件 `fullScroll(FOCUS_DOWN)`，读历史的人每收一条回复就被顶走一次。
 *
 * **[v1.79] 这一页改用 App 自己的「新粗野主义」皮肤**（[Brut]，和设置页 / 历史页 / 待办页同一套）：
 * 纸底 + 2dp 黑描边 + 4dp 实心投影 + 薄荷绿主按钮。改之前这页是全 App 唯一的例外 ——
 * 它按 [Ui.theme] 铺底，而且铺的是 `flat(0xFF)`（**正文色**），默认「护眼」配色下就变成
 * 一页墨绿底 + 浅薄荷／粉渐变顶栏 + 半透明气泡 + 白底黑框小按钮，四种语言打架。
 *
 * **[v1.79] 图片**：输入条左边的「图片」→ 相册选图 → 缩到 1280px / JPEG 80 存 files/chat-img/ →
 * 待发预览可移除 → 发送时和文字一起按多模态（text + image_url）发出去 → 气泡里显示圆角缩略图。
 *
 * 请求走 [ApiClient] 的配置（[Prefs.api]）但**自己发一次非流式 POST**：ApiClient 的 `post` 是私有的、
 * 而聊天要带 system + 一串历史 messages，现成的 [Bodies.text] 只放单条 user 消息。
 * 为了不碰正在并行改动的 ApiClient，这里在 Activity 里就地发请求，配置 / 思考参数 / 报错措辞
 * 都还是走 ApiClient 那一套（[ApiConfig] / [Bodies.thinking] / [NetErrors]）。
 *
 * [MP] 多人格 M-a：顶栏头像 + 名字跟着当前人格走，整组点一下弹人格列表切换；每个人格一份会话文件、一份记忆卡。
 *
 * [2.0.2] Agent 回复改用 [Md.render] 排版（加粗 / 列表 / 公式 / 链接），正文可长按选中、可点链接，
 * 下方一行小字「复制」复制原文；用户消息仍是纯文本小卡。
 *
 * 仍然**不做**：流式逐字、停止生成、新的工具调用、清空会话、多会话列表、重新生成、历史图片的磁盘回收。
 */
class ChatActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var personas: Personas.Store
    private lateinit var store: ChatStore.Store
    private lateinit var col: LinearLayout          // 气泡流
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var who: TextView              // [MP] 顶部那个「小Ling ▾」（v1.79 起不带 emoji）
    /** 必要配置状态提示条：悬浮窗权限 / 接口地址缺一就显示这一条（原两条横幅合并，点一下去对应地方） */
    private lateinit var setupBar: LinearLayout
    private lateinit var setupLabel: TextView
    private lateinit var setupBtn: Button
    private lateinit var topBar: LinearLayout
    private lateinit var idIconHost: LinearLayout   // [2.0.2] 顶栏左侧头像位（切人格后只重画这里）
    private lateinit var quickToggle: Button        // [2.0.2] 「快捷操作」折叠开关
    private lateinit var actionsRow: LinearLayout   // [2.0.2] 展开后的三个常用动作按钮
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
        Ui.theme = prefs.theme()   // 建视图前先装好当前配色（Brut 的颜色是 getter）
        personas = Personas.Store(this)
        pid = personas.current()
        store = ChatStore.Store(this, pid)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        Brut.page(this, root)

        // ---- 顶部：矢量头像 + 名字/状态两行 + 设置 ----
        // [2.0.2] 紧凑顶栏：左侧**一整组**（[PersonaIcon] 矢量头像 + 名字/状态上下两行）点一下弹人格列表；
        // 右侧「设置」常驻。整组高度 ≥ 48dp，好点。顶栏下沿只留一条 1dp 细边（原来 2dp 黑压边太重）。
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Brut.PAPER)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        }
        topBar = top
        // [MP] 名字那一行仍写「名字 ▾」；点它不在这里处理，交给左边整组（见 idGroup）
        who = TextView(this).apply {
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 状态行用小灰字（Brut 里的提示色），别和标题抢。
        // tag = KEEP：Brut.style 会把所有 TextView 刷成墨色，这一行要留住灰
        status = TextView(this).apply {
            textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 名字 + 状态竖向摞成两行；整列占满剩余宽度，两条都单行截断 —— 条数再多也不会把设置顶出屏幕
        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(who, LinearLayout.LayoutParams(-1, -2))
        nameCol.addView(status, LinearLayout.LayoutParams(-1, -2))
        val idGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            minimumHeight = dp(48f)
            setOnClickListener { pickPersona() }
        }
        // 头像是独立一个 host：切人格后只清掉它重画（名字/状态那两条 TextView 的引用要留着更新文字）
        idIconHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        idGroup.addView(idIconHost, LinearLayout.LayoutParams(-2, -2))
        idGroup.addView(nameCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
        top.addView(idGroup, LinearLayout.LayoutParams(0, -2, 1f))
        // 设置是二级入口（桌面图标落在这儿），所以顶栏常驻一个入口，不藏进气泡流
        top.addView(Button(this).apply { text = "设置"; setOnClickListener { openSettings() } })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))
        // 顶栏下沿压一条 1dp 细黑线：新粗野主义的「边」，也把纸底分成两段。
        // [2.1.0] 顶栏 + 这条线是**常驻不滚动**的一块：applyClamp 算可用高时先把它们扣掉。
        val topDivider = View(this).apply { setBackgroundColor(Brut.INK) }
        root.addView(topDivider, LinearLayout.LayoutParams(-1, dp(1f)))

        // ---- 常用动作（识别屏幕 / 弹单词 / 添加提醒）：默认折叠进一个「快捷操作」 ----
        // [2.0.2] 这三个动作原来的常驻大按钮占掉一整行，把正文空间压得很紧。现在默认收起，
        // 点「快捷操作」才展开原来的三个**真实按钮**（触发仍走 QuickActivity / OrbService / TodoListActivity，
        // 缺权限就回设置或系统引导，不假装执行）。三个按钮等宽、≥48dp 触达，小屏 / 大字号下只在按钮内部换行。
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(0f))
        }
        quickToggle = Button(this).apply {
            text = QUICK_SHOW
            setOnClickListener { toggleQuick() }
        }
        quick.addView(quickToggle, LinearLayout.LayoutParams(-1, -2))
        actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        fun actBtn(label: String, primary: Boolean, onTap: () -> Unit) = Button(this).apply {
            text = label
            if (primary) tag = Brut.PRIMARY
            setOnClickListener { onTap() }
        }
        // 三个等宽动作按钮（权重各 1）。标签是四个字，小屏 / 系统大字体（fontScale>1）下一行放不下 ——
        // 这里先建好留引用，等 Brut.style 刷完皮肤再统一收内边距 + 允许两行（见下方 onCreate 尾部）。
        val actionBtns = listOf(
            actBtn("识别屏幕", true) { actSolve() },
            actBtn("弹单词", false) { actWord() },
            actBtn("添加提醒", false) { actRemind() },
        )
        for (b in actionBtns) actionsRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        quick.addView(actionsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        // [2.1.0] 「快捷操作」+ 下面的配置提示条收进 [chromeScroll]：它们是可折叠、也可滚动的「非编辑内容」。
        // 可用高度不够时**只滚这一块**，不去挤底部的 composer（顶栏 + 分隔线仍常驻，见 applyClamp）。
        val chromeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chromeBox.addView(quick, LinearLayout.LayoutParams(-1, -2))

        // ---- 必要配置状态提示（合并原「悬浮球未授权」「接口未配置」两条横幅，只留一句短文案）----
        // 授权 / 接口的活儿都在 MainActivity，这里只做提示与跳转，不重复实现权限系统引导。
        setupBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            Brut.card(this)
            visibility = View.GONE
            setOnClickListener { routeSetup() }
        }
        setupLabel = Ui.text(this, "", 13.5f, Brut.INK)
        setupBar.addView(setupLabel, LinearLayout.LayoutParams(0, -2, 1f))
        setupBtn = Button(this).apply { text = "去设置"; tag = Brut.PRIMARY; setOnClickListener { routeSetup() } }
        setupBar.addView(setupBtn)
        chromeBox.addView(setupBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12f), dp(10f), dp(12f), 0) })
        // 快捷操作 / 配置提示的可滚动容器：高度默认跟着内容（不滚）；可用高不足时由 [applyClamp] 压到上限（滚动）。
        val chromeScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(chromeBox)
        }
        root.addView(chromeScroll, LinearLayout.LayoutParams(-1, -2))

        // ---- 中间：气泡流 ----
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))   // [v1.85.8] 40→12：底栏现在是不透明容器，不再需要给被盖住的气泡留大余量
        }
        scroll = ScrollView(this).apply { addView(col); isFillViewport = true }
        // [v2.1.1] 阅读位置：用户是不是停在底部。只有「本来就在底部」时，内容变高 / 键盘弹起才继续贴底；
        // 往上读了（atBottom=false）就一律不再抢位置 —— 异步回复回来重画时也不许把人拽回最下面。
        scroll.setOnScrollChangeListener { v, _, _, _, _ -> atBottom = !v.canScrollVertically(View.FOCUS_DOWN) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- 底部：待发图片预览 + 整宽多行输入框 + 单独工具行（左图片 / 右发送），收进一个不透明的底栏容器 ----
        // [2.1.0] 键盘 / 系统栏留白统一垫在 root 上（见 onCreate 尾部的 insets listener）—— 子树都不再各自消费 insets。
        // composer（输入框 + 工具行）整块固定最底，是**永不被挤出**的那一块；它上面的快捷操作才让位（见 applyClamp）。
        // 底栏铺 PAPER：键盘弹出 / 内容多的时候，气泡不会从按钮底下透出来。
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Brut.PAPER) }
        pendingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(14f), dp(8f))
            visibility = View.GONE
        }
        pendingThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; roundCorners(8f) }
        pendingRow.addView(pendingThumb, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        pendingLabel = Ui.text(this, "选好 1 张图，点发送一块儿发过去", 12.5f, Brut.HINT).apply {
            setPadding(dp(10f), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            tag = Brut.KEEP   // 同上：说明行要留灰，别被 Brut.style 刷成墨色
        }
        pendingRow.addView(pendingLabel, LinearLayout.LayoutParams(0, -2, 1f))
        pendingRow.addView(Button(this).apply { text = "移除"; setOnClickListener { clearPending() } })
        bottom.addView(pendingRow, LinearLayout.LayoutParams(-1, -2))

        // [2.0.2] composer 固定一种排法，不再随窄 / 宽切换：
        // 输入框永远整宽、自己一行、可多行；工具行另起一行、左「图片」右「发送」。
        // 这样 280dp 窄屏 / fontScale 1.5 下输入框也不会被两个按钮的固有宽度挤成竖排；
        // 两个按钮都由 Brut.button 保证 ≥50dp 触达，中间的空白把发送顶到最右。
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(12f))
        }
        input = EditText(this).apply {
            hint = "说点什么…"; textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4; isSingleLine = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(50f)
        }
        val imgBtn = Button(this).apply {
            text = "图片"
            setOnClickListener { pickImage() }
        }
        send = Button(this).apply {
            text = "发送"; tag = Brut.PRIMARY
            setOnClickListener { ask() }
        }
        bar.addView(input, LinearLayout.LayoutParams(-1, -2))
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        toolRow.addView(imgBtn, LinearLayout.LayoutParams(-2, -2))
        toolRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // 中间留白：左图片 / 右发送
        toolRow.addView(send, LinearLayout.LayoutParams(-2, -2))
        bar.addView(toolRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        bottom.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        // [2.1.0] insets 分工：**root 一处统一垫**，子树都不消费 —— 上 = 状态栏 / 刘海（topPad），左右 = 刘海，
        // 下 = 系统栏 + 键盘（bottomPad 里 IME 已含导航条，只取超出系统栏的差值，不重复垫）。
        // root 的 listener 在整棵树派发的最前面，永远拿到最新一版 IME inset。
        // 垫高 root 底边距 → root 可用高变矮 → weight=1 的消息流先缩；但光靠这个不够 —— 窄屏 + 大字 + 键盘时，
        // 「顶栏 + 分隔线 + 快捷操作 + composer」的固定高之和会超过可用高，LinearLayout 的 weight 孩子只能缩到 0、
        // 吸收不了负数，排在最后的 composer 就整体溢出、被 root.clipToPadding 裁掉（工具行「图片 / 发送」消失）。
        // 真正的保底是下面的 applyClamp：快捷操作所在 chromeScroll 超高时可滚动、输入框高度受剩余空间上限约束，
        // 于是 composer 永远完整落在 IME 可见区之上，不缩字号、不禁用输入法。
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

        // [2.1.0] 「工具行永不挤出窗口」的保底（配合上面的 insets 垫高，两把锁）：
        // 实测（窄屏 720px + fontScale 1.5 + 键盘）：可用高 = 屏高 − 状态栏 − IME(617px) ≈ 920px，
        // 展开快捷操作后「顶栏 + 分隔线 + 快捷操作 + 输入框 + 工具行」固定高之和超过它 → 工具行被裁掉。
        // 1) chromeScroll 上限 = 可用高 − 常驻顶栏区 − composer 实际高：内容不超高就维持原样（不滚），
        //    超高才滚 —— 让「快捷操作」这块非编辑内容滚动，而不是把 composer 顶出去。
        // 2) 输入框 maxHeight = 可用高 − 常驻顶栏区 − composer 里除输入框外的固定部分：
        //    多行展开时输入框自己也受剩余可用空间约束，不会把工具行顶出窗口。
        // 两把锁都只设上限：不缩字号、不隐藏任何控件、数值全部来自实测高度 / insets，不写死像素。
        fun applyClamp() {
            if (root.height == 0 || bottom.height == 0 || input.height == 0) return
            val avail = root.height - root.paddingTop - root.paddingBottom
            if (avail <= 0) return
            val topFixed = topBar.height + topDivider.height         // 常驻顶栏区
            // (2) 输入框剩余高度上限。composeFixed = 待发图行 + 工具行 + 内边距，与输入框本身高度无关。
            val composeFixed = bottom.height - input.height
            val inputFloor = input.minimumHeight.coerceAtLeast(0)
            val inputBudget = (avail - topFixed - composeFixed).coerceAtLeast(inputFloor)
            if (input.maxHeight != inputBudget) input.maxHeight = inputBudget
            // (1) chromeScroll 上限：内容不超高 → 维持 wrap（不滚）；超高 → 压到剩余空间（滚动）
            val cap = (avail - topFixed - bottom.height).coerceAtLeast(0)
            val want = if (chromeBox.measuredHeight <= cap) LinearLayout.LayoutParams.WRAP_CONTENT else cap
            val lp = chromeScroll.layoutParams as LinearLayout.LayoutParams
            if (lp.height != want) { lp.height = want; chromeScroll.layoutParams = lp }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { applyClamp() }
        root.post { applyClamp() }

        // [v1.85.4] 键盘弹出（root 底部垫高改变 scroll 高度）时自动重滚到底 ——
        // 修「最后一条气泡被输入栏压住一半」：以前只在 draw() 时滚一次，键盘一弹新气泡又露出去了。
        // [v2.1.1] 现在只在**用户本来就停在底部**时才重滚：正在往上读的人不许被这一下抢回底部。
        val listenResize = { last: IntArray ->
            scroll.viewTreeObserver.addOnGlobalLayoutListener {
                val h = scroll.height
                if (h != last[0]) { last[0] = h; if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
            }
        }
        listenResize(intArrayOf(-1))

        // 一次性把整棵树刷成新粗野主义皮肤（输入框 → field、按钮 → 白底/薄荷绿、文字 → 墨色）
        Brut.style(root)
        // Brut.button 给每个按钮留的是 14dp/18dp 内边距；三个按钮平分屏宽后，系统大字体下四个字一行放不下。
        // 这里把左右内边距收窄、允许两行居中 —— 文字不会被挤掉或裁掉，三个按钮仍严格等宽。
        for (b in actionBtns) {
            b.isSingleLine = false
            b.maxLines = 2
            b.gravity = Gravity.CENTER
            b.setPadding(dp(5f), dp(10f), dp(9f), dp(14f))
        }
        // 气泡底是手画的（要自己控宽度和拾边），Brut.style 不碰 TextView 的背景，所以放在刷皮肤之后也没事
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
        carriedFlight()?.let { f ->
            flight = f
            f.attach(this)
            if (flight === f) { setAsking(true); draw() }   // attach 没把它收走 = 还在飞
        }
    }

    /**
     * 从系统设置页 / 引导页回来时必要配置提示条要跟着更新，所以每次回前台都重新看一眼
     * （悬浮窗权限 + 接口，见 [refreshSetupBar]）。授权 / 录屏 / 无障碍那条链在 MainActivity，
     * 这里不重复实现，只做提示与跳转。
     *
     * [v1.79] 这里原来还有一段「配色变了就重铺整页」—— 现在这页是纸底固定的皮肤，不再跟配色走，
     * 所以那段删了（配色只影响悬浮球 / 面板 / 词卡，和设置页、历史页一个口径）。
     */
    override fun onResume() {
        super.onResume()
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

    /** 设置页是二级入口（桌面图标落聊天页）。走显式Intent，不给 singleTask 的 MainActivity 带 extras。 */
    private fun openSettings() {
        try { startActivity(Intent(this, MainActivity::class.java)) } catch (_: Exception) { toast("打不开设置页") }
    }

    // ---------------- 常用动作（识别屏幕 / 弹单词 / 添加提醒） ----------------

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
     * 必要配置状态提示条：悬浮窗权限 / 接口地址缺一就显示一句短文案（两条旧横幅合并，避免重复长文字）。
     * 从系统设置 / 引导页回来时 [onResume] 重刷一次。
     */
    private fun refreshSetupBar() {
        if (!::setupBar.isInitialized) return
        val noOverlay = !Settings.canDrawOverlays(this)
        val noApi = !prefs.hasApi()
        if (!noOverlay && !noApi) { setupBar.visibility = View.GONE; return }
        setupBar.visibility = View.VISIBLE
        setupLabel.text = when {
            noOverlay && noApi -> "还差 2 项：接口地址、悬浮窗权限"
            noApi -> "还没填接口地址 —— 填上才能聊"
            else -> "悬浮球还没授权（识别屏幕 / 弹单词要用）"
        }
        setupBtn.text = if (noApi) "去填接口" else "去开启"
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

    // ---------------- 气泡流 ----------------

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
        // [2.0.2] 等待回复时不再说「在想…」（那是假推理），只说「正在回复…」；正文尾部还会挂一条同样的状态
        status.text = if (asking) "正在回复…" else ChatStore.status(list, System.currentTimeMillis())
        if (list.isEmpty()) {
            // 空会话：说清「跟谁聊 + 怎么开口」，不做伪功能卡。挂正中间（挂顶会让整页看着像没加载出来）
            col.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            col.addView(TextView(this).apply {
                text = p.name; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(TextView(this).apply {
                text = ChatStore.greeting(p); textSize = 15f; setTextColor(Brut.HINT); tag = Brut.KEEP
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(6f), dp(8f), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(TextView(this).apply {
                text = "可以直接提问，也可以发送图片"; textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(4f), dp(8f), 0)
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
                    text = div; textSize = 12f; setTextColor(Brut.HINT); tag = Brut.KEEP
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10f), 0, dp(2f))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            // 同一个人连着说的收紧到 3dp：一眼能看出这是一段，不是每人一句来回
            val gap = if (i > 0 && list[i - 1].isOrb == m.isOrb) 3f else 9f
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
        text = "正在回复…"; textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
    }

    /** [2.0.2]「快捷操作」折叠开关：展开 / 收起那三个常用动作按钮（纯显隐，不改任何功能） */
    private fun toggleQuick() {
        val show = actionsRow.visibility != View.VISIBLE
        actionsRow.visibility = if (show) View.VISIBLE else View.GONE
        quickToggle.text = if (show) QUICK_HIDE else QUICK_SHOW
    }

    /**
     * 一条消息的气泡。
     *
     * [2.0.2] 两种形态彻底分开：
     *  - **Agent**（[orbBubble]）：靠左、占**可用正文宽**（不再 78% 窄卡、不再黑投影），顶部一行小字人格名，
     *    正文走 [Md.render]（加粗 / 列表 / 公式 / 链接可点），可长按选中文字，下面一行小字「复制」。
     *  - **用户**（[userBubble]）：靠右收敛小卡（纯文本，可带图），宽不超过 [maxBubbleW]。
     * 两者都只画 **1dp 轻描边**（[chatPanel]），不画重投影 —— 和设置页那种厚块区分开，灰度下靠形状辨。
     */
    private fun bubble(m: ChatStore.Msg, gapDp: Float, p: Persona): View =
        if (m.isOrb) orbBubble(m, gapDp, p) else userBubble(m, gapDp)

    /** Agent 回复：左侧、占满可用正文宽，顶部小字人格名 + [Md.render] 正文（可选中 / 可点链接）+ 小字「复制」 */
    private fun orbBubble(m: ChatStore.Msg, gapDp: Float, p: Persona): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = chatPanel(Brut.CARD)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            tag = Brut.KEEP
        }
        wrap.addView(Ui.text(this, p.name, 11.5f, Brut.HINT).apply { tag = Brut.KEEP },
            LinearLayout.LayoutParams(-1, -2))
        wrap.addView(TextView(this).apply {
            text = Md.render(m.content, Brut.INK, Theme.argb(0x22, Brut.INK))
            textSize = 15f; setLineSpacing(0f, 1.4f); setTextColor(Brut.INK)
            setTextIsSelectable(true)                 // 正文可以长按选中
            movementMethod = Links.SelectableTap      // 点链接跳转，其余照常选字
            setPadding(0, dp(5f), 0, 0)
            tag = Brut.KEEP
        }, LinearLayout.LayoutParams(-1, -2))
        // 「复制」：复制原文（走系统剪贴板，不加任何新的历史字段）
        wrap.addView(Ui.text(this, "复制", 12f, Brut.HINT).apply {
            tag = Brut.KEEP
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)   // [2.0.2] 触达行最低 48dp（原来 40dp 偏小）
            setPadding(dp(4f), 0, dp(8f), 0)
            setOnClickListener { copyText(m.content) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2f) })
        wrap.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.START
            topMargin = dp(gapDp); bottomMargin = dp(3f)
        }
        return wrap
    }

    /** 用户消息：靠右收敛小卡（纯文本可带图），宽不超过 [maxBubbleW] */
    private fun userBubble(m: ChatStore.Msg, gapDp: Float): View {
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END
            topMargin = dp(gapDp); bottomMargin = dp(3f)
        }
        val bmp = m.img?.let { imgFile(it) }?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bmp == null) {
            return TextView(this).apply {
                text = m.content; textSize = 15f; setLineSpacing(0f, 1.35f)
                setTextColor(Brut.on(Brut.MINT))
                background = chatPanel(Brut.MINT)
                setPadding(dp(12f), dp(9f), dp(12f), dp(10f))
                maxWidth = maxBubbleW
                tag = Brut.KEEP
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
            background = chatPanel(Brut.MINT)
            setPadding(padL, dp(8f), padR, dp(10f))
            tag = Brut.KEEP
        }
        box.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            roundCorners(10f)
        }, LinearLayout.LayoutParams(iw, ih))
        val cap = if (m.content.isBlank()) null else TextView(this).apply {
            text = m.content; textSize = 15f; setLineSpacing(0f, 1.35f); setTextColor(Brut.on(Brut.MINT))
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

    /**
     * 聊天页私有的「轻描边」块：填色 + 1dp 黑边、无投影。
     * 刻意**不用** [Brut.box]（那个带 4dp 实心黑投影，是设置页那种厚积木的规格）—— 聊天流要轻。
     * 灰度下仍靠形状区分：用户是薄荷底、Agent 是白底。
     */
    private fun chatPanel(fill: Int): Drawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(6f).toFloat()
        setStroke(dp(1f), Brut.INK)
    }

    /** 「复制」：把回复原文写进系统剪贴板（不新增任何历史字段） */
    private fun copyText(s: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("小Ling", s))
            toast("已复制")
        } catch (_: Exception) { toast("复制失败") }
    }
    /** 给 ImageView 加圆角（API 21+ 的 outline 裁切，不用第三方库也不用再去包一层 drawable） */
    private fun ImageView.roundCorners(radiusDp: Float) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(radiusDp).toFloat())
        }
        clipToOutline = true
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
    private fun iconListDialog(title: String, rows: List<Triple<String, String, String?>>, onPick: (Int) -> Unit): android.app.AlertDialog {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("取消", null)
            .create()
        rows.forEachIndexed { i, (key, main, sub) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
                isClickable = true
                setOnClickListener { dlg.dismiss(); onPick(i) }
            }
            row.addView(PersonaIcon(this, key))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this@ChatActivity, main, 15f), LinearLayout.LayoutParams(-1, -2))
            if (!sub.isNullOrBlank()) {
                texts.addView(Ui.text(this@ChatActivity, sub, 12.5f, Ui.TX2).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return dlg
    }

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
        val list = personas.list()
        if (!Personas.canAdd(list)) { toast("最多 ${Personas.MAX} 个 Agent"); return }
        val t = Personas.TEMPLATES
        val rows = t.map { Triple(it.iconKey, it.label, it.role) }
        iconListDialog("新 Agent 从哪个模板起", rows) { i ->
            val before = personas.list().size
            personas.fromTemplate(t[i].id)
            val after = personas.list()
            if (after.size <= before) { toast("建不了 —— 提示词已经写满 ${Personas.PROMPT_TOTAL} 字"); return@iconListDialog }
            switchTo(after.last().id)
        }.show()
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
        f.attach(this)
        runChatFlight(f)
    }

    private fun setAsking(on: Boolean) {
        asking = on
        send.isEnabled = !on
        input.isEnabled = !on
        // [2.0.2] 禁用规则照旧，只是文案直说「回复中」，不用省略号
        send.text = if (on) "回复中" else "发送"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 选图的请求码（别和 MainActivity 的录屏 7 / 导入 4712 / 通知 8 撞） */
        private const val REQ_PICK = 91

        /** 图片最长边（1280 是识屏那条线一直在用的口径，接口那边也认这个尺寸） */
        private const val MAX_SIDE = 1280

        /** 气泡里那张图最多多高（dp）：再高就该长按看大图了，聊天流里不该压过一整屏 */
        private const val IMG_MAX_H_DP = 200f

        /** 「快捷操作」折叠开关的两种文案（▾/▴ 是几何符号，不是 emoji） */
        private const val QUICK_SHOW = "快捷操作 ▾"
        private const val QUICK_HIDE = "收起快捷操作 ▴"

        /** 旋转/recreate 恢复用：草稿文字 + 待发图文件名（EditText 没 id，系统不会自动存） */
        private const val KEY_DRAFT = "chat_draft"
        private const val KEY_PENDING_IMG = "chat_pending_img"

        /** 常用动作「只提示一次」的 id（存进 [UsageHints] 的独立 key，跨旋转 / 进程不重复） */
        private const val HINT_SOLVE = "action.solve"
        private const val HINT_WORD = "action.word"
        private const val HINT_REMIND = "action.remind"

        /** 小球菜单里那一条（Service 里没有 Activity 上下文，要自己带 NEW_TASK） */
        fun intent(ctx: android.content.Context) =
            android.content.Intent(ctx, ChatActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
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
private class ChatFlight(
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
        // [MP] system 跟着**发起时**的人格走：人格段（空则回落 PERSONA）+ 这个人格自己的记忆卡
        val sys = ChatStore.systemPrompt(f.persona, MemoryCard.Store(f.app, f.pid).get().takeIf { !it.isEmpty })
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
        val reply = try {
            // [v1.79] 只有这一刻刚发的那张图带 base64（按文件名认；历史里的图不带，退化成「[图片]」占位）
            val b64 = f.img?.let { runCatching { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }.getOrNull() }
            val body = ChatStore.body(f.cfg, f.snapshot, system = sys, suffix = suffix) { m ->
                if (f.img != null && m.at == f.at) b64 else null
            }
            chatPost(f.cfg, body).also { ok = true }
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
            val body = shown.ifBlank { if (plan.isEmpty) "" else "好，记下了。" }
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
        f.deliver()
    }
}

/** 一次非流式 chat/completions；返回正文。失败抛 [ApiException]（消息已经是大白话） */
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
        return try {
            JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim()
        } catch (e: Exception) { "" }
    } finally {
        conn.disconnect()
    }
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

