package com.chun.lingzhu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 答案面板 —— 手机小屏版（v1.52）：
 *  - 贴底部的抽屉。v1.85：用户能摸到的伸缩只剩**两档** —— 半屏（默认）/ 大屏。
 *    点一下顶部横条就在半屏 ↔ 大屏之间切；拖横条往外变高、往回变矮（结果都夹在半屏～大屏之间），档位记住。
 *    面板在下半屏，题目一般在上半屏露着。
 *    「收起档」（只剩「答案」一行）仍保留，但只给内部用 —— 点回答里的链接时临时收起让位（见 [linksOnOpened]）等；
 *    用户的点按 / 拖动永远落不到它上面（循环在 [PanelLayout.toggle] / [PanelLayout.nextState]）。
 *  - v1.85：正文（滚动区）**左右长滑**也能划掉面板（和词卡一样的手势），短滑弹回。
 *  - 答案先行：不管哪一档，最上面先是答案那一行，不用往上翻找。
 *  - 字号在设置页调（v1.66 起面板上不再放 A− / A+）；宽度按屏幕来：手机占满（左右各留 6dp），平板最多 480dp。
 *  - 避开状态栏 / 导航条；转屏时 relayout()。
 *  - v1.60：位置可选（底部 / 顶部 / 左右 / 跟着小球 / 避开小球 / 按住横条拖到任意位置），宽度、两档高度、离边距离可调，
 *    从贴着的那一边滑进来 —— 算法在 PanelLayout。
 * 阅读位置（v1.88 新契约，「跟到底」开关见 [follow]）：
 *  - 新答案 / 打开历史**从开头看**，不自动跳末尾（初始 follow = false）；
 *  - 正文增量、done 最终重排、关键词出现、切档、键盘弹起这些**都不抢**用户正在看的位置 ——
 *    只有用户自己在跟随时才继续跟；
 *  - 只有**用户自己**滚 / 拖到最底才打开跟随；手指一按住正文（准备往上读 / 选字）立刻停跟随，
 *    松手（含甩动惯性）时确实停在最底才恢复；
 *  - 排队中的自动滚动执行时会**复验**（期间用户接管就不滚），用户一按住就取消；
 *  - 发送追问（点词块 / 打字）时按**那一刻的真实位置**重判一次：本来就在最底就继续跟、在上方就原地不动
 *    （见 [beginFollowUp] / [followOnSend]）—— 光靠手势 settle 会在流式增量下漏判成 false，导致「停在底部却不跟」；
 *  - 程序滚动 / 布局回调**不许**打开跟随（不然回回都被抢回底部）。
 */
class AnswerPanel(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val onClose: () -> Unit,
    private val onReauth: () -> Unit,
    /**
     * 追问：(面板上显示的那句, 真正发给模型的问题, 续图追问, 回答风格覆盖层, 强制语言)。
     * v1.66：what / why 开关会把要求拼进第二个参数。
     * v1.72：覆盖层和语言单独传（第 4 / 5 个）—— 得由 Prompts 拼在提示词最后，压得住默认的
     * 「不超过 N 字」「用中文」两条约定；早先并进第二个参数就失效了。
     */
    private val onAsk: (String, String, Boolean, String, String?) -> Unit,
    /** v1.64：收藏这条（错题本）；返回收藏后的状态，null = 这条没存进历史 */
    private val onStar: () -> Boolean? = { null },
    /** v1.67 [M1]：点「记住这题」把这道题记进记忆卡；返回记进去的话题（空串 = 没记上） */
    private val onRemember: (String) -> String = { "" },
    /** 小球现在在哪（「跟着小球 / 避开小球」要用；小球藏起来时给 null） */
    private val orbRect: () -> PanelLayout.Orb? = { null },
) {
    companion object {
        /** 被动模式（自动扫描）下正文最多显示这么高：够瞄一眼，又不至于把整个屏幕盖住 */
        const val PASSIVE_BODY_MAX_DP = 104f
        const val COLLAPSED = 0
        const val HALF = 1
        const val FULL = 2

        /**
         * 长答案时顶部「答案」行最多显示几行（v1.88）：这种答案正文里本来就整段留着，
         * 顶栏再整段铺一遍就是白占两遍高度 —— 只摘 1~2 行，正文优先拿高度。
         * 短答案（[AnswerText] 已把它从正文剥掉）不受影响，照旧整段显示，免得有内容哪儿都看不到。
         */
        const val ANSWER_MAX_LINES = 2

        /** 「追问选项」入口的两种文案（▾/▴ 是几何符号，不是 emoji） */
        const val ASK_MORE_SHUT = "选项 ▾"
        const val ASK_MORE_OPEN = "选项 ▴"

        /** 手指离开正文后等这么久没再滚动，就认为甩动惯性停了（见 [armBodySettle]） */
        const val SETTLE_MS = 120L

        /**
         * 发送追问时「要不要继续跟到底」的纯判定（脱离 View，JVM 可单测）。
         *
         * 原来 [beginFollowUp] 只删了 `follow = true`，本意是「保留读位」；但用户本来就在最底看流式时，
         * `follow` 会因为流式增量与甩动 settle 的竞态停在 false —— 这时新内容追加后 `scheduleFollowScroll`
         * 因 `follow=false` 不滚，视图卡在旧行（实测 117-120）。
         * 修法：发送瞬间按**此刻真实位置**重判 —— 真在底部就保持/恢复跟随；在上方则维持原状，不抢位。
         *
         * @param wasFollowing 发送前是否已在跟到底
         * @param atTrueBottom 发送那一刻是否真的停在正文最底（由 [AnswerPanel.atTrueBottom] 读取）
         */
        fun followOnSend(wasFollowing: Boolean, atTrueBottom: Boolean): Boolean = wasFollowing || atTrueBottom
    }

    var attached = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val root: MaxHeightLinearLayout

    /**
     * 面板的弹簧动画（v1.78）。面板是**长期复用的同一个视图**，所以这条动画也常驻 ——
     * 「刚弹出来又收回去」「连着弹两次」都会接着当前速度走，不会重新起步。
     * 用 lazy 是因为 [root] / [modeTray] 得先在构造里建出来。
     */
    private val motion by lazy { AnimGlue.SpringAnimation(root, Anim.Spring.enter) }
    private val trayMotion by lazy { AnimGlue.SpringAnimation(modeTray, Anim.Spring.popIn) }
    private val handle: View
    private val grip: LinearLayout
    /** 工具行（模式标签 / ☆ / ✕）。被动模式要整行收起来，所以提成字段 */
    private val bar: LinearLayout
    /** 被动模式（v1.78，自动扫描用）：只剩答案文字，外壳全收、窗口不可触摸 */
    private var passive = false
    /**
     * v1.86 被动模式下「破例亮一行」的状态（见 [peekStatus]）。
     * ⚠️ 这两个字段必须声明在 `init` **之前**：[setStatus] 会调 [cancelPeek]，而构造过程里就会走 setStatus；
     * 声明在后面的话此时 `peekEnd` 还没赋值，等于往 Handler 里塞一个 null。
     */
    var peeking = false; private set
    private val peekEnd = Runnable { endPeek() }
    /** 这次排版的结果（位置、贴哪边、横条在上还是在下） */
    private var spec: PanelLayout.Spec? = null
    /** 追问输入时为了躲键盘临时挪了位置（键盘收起再按 spec 摆回去） */
    private var liftedForIme = false
    private val modeChip: TextView
    private val answerLine: TextView
    private val status: TextView
    /**
     * 自动扫描计时行（v1.86）：专用的一行小字，**被动层也可见**（不在 [chrome] 里，不被 applyPassive 收起）。
     * 和状态行分开是因为服务每 250~500ms 刷一次用时 —— 刷它只改这一行的文字，不重排正文 / 不触发 [render]。
     */
    private val scanLine: TextView
    private val body: TextView
    private val scroll: MaxHeightScrollView
    /** 关键词块（v1.65 通识引入，v1.66 起所有模式、设置「识别关键词」开着时）：模型给的清单变成一排词块，点哪个解释哪个 */
    private val termsBar: LinearLayout
    private val termsRow: LinearLayout
    private var activeTerm: String? = null
    /** v1.67 [M1]：这一轮拆出来的「记住这题」话题（模型给了动作行才有） */
    private var rememberTopic: String? = null
    private val askBar: LinearLayout
    private val askInputRow: LinearLayout
    /** v1.88：三个追问开关（what / why / 新图）的「追问选项」行 —— 点 [askMoreBtn] 才展开，平时不占输入框的宽度 */
    private val askOptRow: LinearLayout
    private val askEdit: android.widget.EditText
    /** 主操作（唯一强调按钮） */
    private lateinit var sendBtn: TextView
    private lateinit var shotChip: TextView
    /** v1.66 追问开关（v1.88 起收进「追问选项」）：what = 讲简单点 + 举例；why = 第一性原理问为什么。可以同时开 */
    private lateinit var whatChip: TextView
    private lateinit var whyChip: TextView
    /** v1.88：追问选项的入口（输入框与发送之间那颗小按钮） */
    private lateinit var askMoreBtn: TextView
    private var askWhat = false
    private var askWhy = false
    /** 输入框正在打字（窗口临时可获焦点、键盘弹着） */
    private var inputOpen = false
    /**
     * 打开输入框（键盘即将弹起、面板要临时上抬）那一刻用户是不是停在正文最底。
     * 键盘把视口压矮后 ScrollView 的 scrollY 不变、可滚距离却变长，发送时直接读位置会误判成「不在底部」——
     * 打字发送这条路径就靠它兜底（词块追问不弹键盘，直接读即可）。用户一旦按正文接管阅读会被清掉（见 [pauseFollowForUser]）。
     */
    private var preInputAtBottom = false
    private var askEnabled = false
    private var awaitingFollow = false
    /** v1.65：追问附新图开关（打字追问生效；快捷词 / 名词词块永远同图）。开着时新截一张屏，以新图为准 */
    private var askWithShot = false
    private val buttons = ArrayList<TextView>()
    private lateinit var starBtn: android.widget.TextView   // v1.64 收藏 ☆/★
    /** 模式抽屉（v1.66）：点左上角模式标签往下拉出一列模式，只列模式、点一个就切过去并收起 */
    private val modeTray: LinearLayout
    private var modeLabel = ""
    private val lp: WindowManager.LayoutParams
    /** [Links.onOpened] 的回调引用，remove 时用于比对清除（P2-10） */
    private val linksOnOpened: () -> Unit
    private val raw = StringBuilder()
    /**
     * 自动扫描这一轮的**暂存正文**（staging v1.86）：[beginRescan] 起一次、[append] 只往里攒、[done] 一次提交。
     * 攒的过程中**不碰**面板上的上一轮答案 —— 这样首个 token 不会再把旧答案抹掉（老实现 pendingReset 的坑）。
     */
    private val staging = StringBuilder()
    /** 是否在自动扫描的一轮里（暂存进行中）。false 时 [append] 走原来的手动流式路径 */
    private var stagingActive = false
    /** 计时行状态：已用毫秒 + 是否已完成。完成后冻结，不再被倒计时覆盖（见 [setScanElapsed]） */
    private var scanElapsedMs = 0L
    private var scanComplete = false
    /** 「跟到底」开关：只有**用户自己**滚到底才打开（初始 false = 新答案 / 历史都从开头看，见类注释） */
    private var follow = false
    private var renderQueued = false
    /** 排队中的一次「跟到底」滚动；用户接管时 [cancelFollowScroll]，执行时还会再复验一次 [follow] */
    private var pendingFollowScroll: Runnable? = null
    /** 手指正按在正文上 / 甩动惯性还没停：这期间由滚动位置推出的「到底了」才算用户自己的意图 */
    private var bodyGestureActive = false
    /** 惯性停下后判定一次（每一次滚动都往后顺延，见 [armBodySettle]） */
    private val bodySettle = Runnable {
        bodyGestureActive = false
        if (!scroll.canScrollVertically(1)) follow = true   // 确实停在最底才恢复跟随
    }
    /** 顶部「答案」那行是不是和正文里那一段重复（长答案时 AnswerText 不把它从正文剥掉）—— 重复就只摘 1~2 行 */
    private var answerDuplicated = false
    /** v1.85：用户可见的最小档是半屏 —— 记住的若是老版本留下的收起档（COLLAPSED），开面板时归到半屏 */
    private var state = if (prefs.panelState == COLLAPSED) HALF else prefs.panelState
    private var jumpCollapsed = false
    /** 这次回答里拆出来的关键词（词块） */
    private var showTerms: List<String> = emptyList()
    /**
     * 上一次 [refreshTermsRow] 真正建出来的词块快照（清单内容 + 当时的 activeTerm）。
     * [render] 每帧都调 refreshTermsRow，清单和选中项都没变就保留现有视图、不重建 ——
     * 原来每帧 removeAllViews + 重建一排词块是白费（流式里的绝大多数帧都没变）。
     * null = 快照已作废（首次 / reset 清理 / 换主题），下一次调用强制重建。
     * 只存引用、每帧只做一次结构比较，不复制列表、不新增缓存框架。
     */
    private var termsBuilt: List<String>? = null
    private var termsBuiltActive: String? = null

    val hasText: Boolean get() = raw.isNotEmpty()
    /** 面板里的全部文字（第一轮 + 追问），追问答完存回历史用 */
    val text: String get() = raw.toString()
    /** 是否处于被动层（自动扫描）：此时状态行默认 GONE，报错要走 peekStatus 才看得见 */
    val isPassive: Boolean get() = passive

    private fun dp(v: Float) = Ui.dp(ctx, v)

    init {
        root = MaxHeightLinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Ui.panelBg(ctx) }

        // [K5] v1.70 知识库检索：把取卡的口子交给纯逻辑层（Solver 那边不碰 Context）。
        // 用 applicationContext —— 面板销毁后这张闭包还可能被后台追问线程用到。
        KnowledgeSearch.source = { runCatching { KnowledgeCard.Store(ctx.applicationContext).list() }.getOrDefault(emptyList<KnowledgeCard.KCData>()) }

        // 顶部横条（v1.85：拖动在半屏 ↔ 大屏两档间换档 / 点一下切换）
        grip = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(0, dp(7f), 0, dp(3f)) }
        handle = View(ctx)
        grip.addView(handle, LinearLayout.LayoutParams(dp(40f), dp(5f)))
        root.addView(grip)

        // 工具行（v1.74 重排）：一排小图标按钮。模式标签保留可见（切模式高频）；☆ 收藏、✕ 关闭 收成小按钮
        // v1.88：竖向内边距再收一点（模式 + ☆ + ✕ 仍是整齐一行，但固定 chrome 更薄，正文多让出些高度）
        bar = Ui.row(ctx).apply { setPadding(dp(8f), dp(1f), dp(6f), dp(2f)) }
        modeChip = Ui.text(ctx, "", 13f, Ui.TX1).apply {
            background = Ui.pill(ctx); setPadding(dp(9f), dp(4f), dp(9f), dp(4f)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(160f)
            setOnClickListener { toggleModes() }
        }
        bar.addView(modeChip, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val gap = dp(5f)
        // desc：读屏（TalkBack）念的名字 —— 「✕」念出来是符号名（v1.63）
        starBtn = Ui.button(ctx, "☆") { onStar()?.let { setStarred(it) } }.apply { setPadding(dp(7f), dp(3f), dp(7f), dp(3f)); buttons.add(this); contentDescription = "收藏这道题" }
        bar.addView(starBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        // v1.78：这一行的「存卡」按钮撤掉了（用户反馈）。存知识卡照样有入口：系统分享纯文本给 Ling、
        // 设置 →「导入知识卡（Anki / TXT / Word / PDF）」、知识卡库。这里只留 收藏 / 关闭。
        bar.addView(Ui.button(ctx, "✕") { fadeOutAndRemove(); onClose() }.apply { setPadding(dp(7f), dp(3f), dp(7f), dp(3f)); buttons.add(this); contentDescription = "关闭面板" },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = gap })
        root.addView(bar)
        modeTray = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(10f), 0, dp(10f), dp(6f)) }
        root.addView(modeTray)

        // 答案先行：永远在最上面的一行（收起档只剩它）。v1.88：长答案时只摘 1~2 行（见 [updateAnswerLineVisibility]）
        answerLine = Ui.text(ctx, "", 17f).apply {
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(12f), dp(1f), dp(12f), dp(4f))
            maxLines = AnswerText.PANEL_LINES; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
            movementMethod = Links.Tap
        }
        root.addView(answerLine)

        status = Ui.text(ctx, "", 12.5f, Ui.TX3).apply { setPadding(dp(12f), 0, dp(12f), dp(4f)); visibility = View.GONE }
        root.addView(status)

        // 自动扫描计时行（v1.86）：状态行下面一行更小的字，只由 setScanElapsed 刷；被动层照常可见。
        // v1.90：从创建起就固定成「常驻一行」——单行 + 等宽字体 + 最小宽度，且默认 INVISIBLE 而非 GONE，
        // 行高一直占着；这样每轮 beginRescan / abortRescan 只切可见性，正文不会被这一行的出现 / 消失挤得上下跳。
        scanLine = Ui.text(ctx, "", 12f, Ui.TX3).apply {
            setPadding(dp(12f), 0, dp(12f), dp(4f))
            setSingleLine(true); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.MONOSPACE
            minWidth = dp(96f)
            visibility = View.INVISIBLE
        }
        root.addView(scanLine)

        body = Ui.text(ctx, "", prefs.textSp.toFloat()).apply { setLineSpacing(0f, 1.4f); setPadding(dp(12f), dp(2f), dp(12f), dp(10f)); movementMethod = Links.Tap }
        scroll = MaxHeightScrollView(ctx, 0).apply { addView(body); isVerticalScrollBarEnabled = true; isFillViewport = false }
        // 滚动位置**只**用来「等手势停下再判定到底没有」，绝不在这里打开跟随：
        // 程序滚动（自动跟底）、布局变化（正文变高 / 键盘弹起）也会走到这里，误判就会把用户正在看的位置抢回底部。
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> if (bodyGestureActive) armBodySettle() }
        // 正文带权重：总高度到顶时先缩正文，底下的追问栏不被挤掉
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))

        // 关键词词块行（答案里带 [可点击名词] 清单时才露出；横着滑，不挤正文）
        // v1.88：「点名词看解释」那行独立标题撤掉了 —— 固定占一整行高、正文更挤；词块本身就是按钮形状，一行横滚照样点。
        termsRow = Ui.row(ctx)
        termsBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(10f), dp(1f), dp(10f), dp(1f))
            addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(termsRow) })
        }
        root.addView(termsBar)

        // 追问栏（v1.74 重排 → v1.88 再收）：底部只留「输入框 + 追问选项入口 + 发送」三件，
        // what / why / 新图 三个开关收进按需展开的「追问选项」行（点入口才出来，平时不挤输入框）。
        askBar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8f), dp(2f), dp(8f), dp(6f)); visibility = View.GONE }
        askOptRow = Ui.row(ctx).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(4f)) }
        whatChip = toggleChip("what", "讲简单点并举例") { askWhat = !askWhat; refreshToggles() }
        whyChip = toggleChip("why", "用第一性原理问为什么") { askWhy = !askWhy; refreshToggles() }
        askOptRow.addView(whatChip, LinearLayout.LayoutParams(-2, -2))
        askOptRow.addView(whyChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4f) })
        // v1.65：续图开关 —— 追问时先截一张最新屏幕（原图和之前轮次照常带上，以新图为准）
        shotChip = askChip("新图：关") { askWithShot = !askWithShot; refreshToggles() }
        askOptRow.addView(shotChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4f) })

        askInputRow = Ui.row(ctx)
        askEdit = android.widget.EditText(ctx).apply {
            hint = "想追问什么…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = Ui.pill(ctx); setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            setOnEditorActionListener { _, id, _ -> if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
            // 键盘收起后再按返回：退出输入（悬浮窗拿到焦点时返回键先到这里）
            setOnKeyListener { _, code, ev -> if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { closeInput(); true } else false }
        }
        // 面板平时不抢焦点：点输入框时才临时可获焦点、弹键盘
        askEdit.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP && !inputOpen) openInput(); false }
        // [FreePrompt] 长按输入框清掉 what / why 快捷开关 → 纯自由模式（开关没开时不拦，保留选词手柄）
        askEdit.setOnLongClickListener { clearQuickTogglesOnLongPress() }
        askInputRow.addView(askEdit, LinearLayout.LayoutParams(0, -2, 1f))
        // v1.88：追问选项的入口（输入框 / 发送之间那颗小按钮）—— 点开才露出 what / why / 新图
        askMoreBtn = Ui.button(ctx, ASK_MORE_SHUT) { toggleAskOptions() }.apply {
            buttons.add(this)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            maxLines = 1
        }
        askInputRow.addView(askMoreBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        // 主操作唯一突出：发送 = 薄荷绿强调按钮，其他全是白底小按钮
        sendBtn = Ui.buttonEmph(ctx, "发送") { sendTyped() }.apply { buttons.add(this) }
        askInputRow.addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        askBar.addView(askOptRow)
        askBar.addView(askInputRow)
        root.addView(askBar)
        refreshToggles()
        // 输入时点到面板外面：收起输入（字留着，下次接着写）
        root.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE && inputOpen) closeInput(); false }

        lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        bindGrip(grip)
        bindBodyDismiss(scroll)   // v1.85：正文左右长滑划掉面板（横条自己的手势不受影响）
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> main.post { measureBottomRef() } }
        // 悬浮窗不会跟着键盘自动缩：键盘弹出时把面板挪到键盘正上方（不管平时在哪个位置），收起再摆回原位（Android 11+）
        // v1.60：以前只有贴底一种位置，按遮挡量往上抬；现在统一成「临时贴底、坐在键盘上」，抬一次就记下，系统补发旧遮挡值时不再叠加
        if (android.os.Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { v, ins ->
            val imeOn = ins.isVisible(android.view.WindowInsets.Type.ime()) && inputOpen
            val over = ins.getInsets(android.view.WindowInsets.Type.ime()).bottom
            if (attached && imeOn && over > 0 && !liftedForIme) {
                liftedForIme = true
                val sp = spec
                lp.gravity = Gravity.BOTTOM or (sp?.let { hGravity(it.h) } ?: Gravity.CENTER_HORIZONTAL)
                lp.y = Ui.navBarHeight(ctx) + dp(6f) + over + dp(6f)
                root.maxHeightPx = realScreenHeight() - Ui.statusBarHeight(ctx) - lp.y - dp(8f)
                wm.updateViewLayout(root, lp)
            } else if (attached && !imeOn && liftedForIme) {
                liftedForIme = false
                relayout()
            }
            v.onApplyWindowInsets(ins)
        }
        // 点了回答里的链接：面板临时收起到只剩答案行，给打开的网页 / 拨号盘让位置（不改记住的高度，下次识别恢复）
        // P2-10：闭包提成成员，remove 时清掉全局引用，避免泄漏整个面板 View 树
        linksOnOpened = { main.post { if (attached && state != COLLAPSED) { setState(COLLAPSED, persist = false); jumpCollapsed = true } } }
        Links.onOpened = linksOnOpened
        applyTheme()
        relayout()
        applyState(animate = false)
    }

    /** 按 Ui.theme 重新上色（设置页换配色时面板开着也立刻生效） */
    fun applyTheme() {
        val t = Ui.theme
        root.background = Ui.panelBg(ctx)
        handle.background = Ui.glass(ctx, t.handle, 3f)
        modeChip.setTextColor(Ui.TX1); modeChip.background = Ui.pill(ctx)
        refreshModes()
        // v1.74：小按钮统一规格（白底黑描边），发送按钮刷回薄荷绿强调
        for (b in buttons) Ui.restyle(b)
        Ui.emphasize(sendBtn)
        answerLine.setTextColor(Theme.argb(0xFF, t.answer))
        status.setTextColor(Ui.TX3)
        scanLine.setTextColor(Ui.TX3)
        body.setTextColor(Ui.TX1)
        askEdit.setTextColor(Ui.TX1); askEdit.setHintTextColor(Ui.TX3); askEdit.background = Ui.pill(ctx)
        refreshToggles()
        invalidateTermsRow()   // 换配色：作废词块快照，下一次 render 强制重建（词块按新主题重新上色）
        if (raw.isNotEmpty()) render()
    }

    private fun askChip(label: String, act: () -> Unit) = Ui.button(ctx, label, act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(9f), dp(4f), dp(9f), dp(4f)); buttons.add(this)
    }

    /** 追问开关：统一小按钮字号 12sp，英文单词放得下不挤；开着 = 前面打 ✓、加粗加下划线（不靠颜色） */
    private fun toggleChip(label: String, what: String, act: () -> Unit) = Ui.button(ctx, label, act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(7f), dp(4f), dp(7f), dp(4f)); maxLines = 1
        buttons.add(this); tag = what
    }

    /**
     * 刷新三个追问开关的外观 + 读屏描述（what / why 的 ✓ 与加粗下划线，新图的「开 / 关」，入口按钮的状态）。
     * v1.88：三个开关都收进了 [askOptRow]，所以入口按钮也要跟着刷 —— 收着的时候靠按钮上的记号看出「有开关开着」。
     */
    private fun refreshToggles() {
        for ((chip, on, label) in listOf(Triple(whatChip, askWhat, "what"), Triple(whyChip, askWhy, "why"))) {
            chip.text = if (on) "✓$label" else label
            chip.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            chip.paint.isUnderlineText = on
            chip.contentDescription = label + "：" + chip.tag + if (on) "，已打开，接下来的追问都按这个要求回答" else "，已关闭"
            chip.invalidate()
        }
        // 新图开关：文案同样在这一处统一刷（原来写在点击回调里，applyTheme 换配色时就刷不到了）
        shotChip.text = if (askWithShot) "新图：开" else "新图：关"
        shotChip.contentDescription = "新图：" + if (askWithShot) "开，追问时先截一张最新屏幕（以新图为准）" else "关，追问沿用原来的截图"
        updateAskMore()
    }

    /** v1.88：展开 / 收起「追问选项」（what / why / 新图）—— 这几个开关平时不占输入框的宽度 */
    private fun toggleAskOptions() {
        val open = askOptRow.visibility != View.VISIBLE
        askOptRow.visibility = if (open) View.VISIBLE else View.GONE
        updateAskMore()
    }

    /** 追问选项入口的样子：箭头跟着开合；有开关开着时前面记一个「•」（收起来时也看得出来） */
    private fun updateAskMore() {
        val open = askOptRow.visibility == View.VISIBLE
        val on = listOfNotNull(
            if (askWhat) "讲简单点" else null,
            if (askWhy) "第一性原理" else null,
            if (askWithShot) "附新图" else null,
        )
        askMoreBtn.text = (if (on.isEmpty()) "" else "•") + (if (open) ASK_MORE_OPEN else ASK_MORE_SHUT)
        val suffix = if (open) "，已展开" else "，点一下展开"
        askMoreBtn.contentDescription = "追问选项" + (if (on.isEmpty()) "，都关着" else "，开着：" + on.joinToString("、")) + suffix
    }

    // ---------------- 追问输入 ----------------
    // 面板平时不抢焦点（不然底下的 App 收不到按键）；只有展开输入框时临时可获焦点、弹键盘，发完 / 取消就还回去
    private fun setFocusable(f: Boolean) {
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val watch = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.flags = if (f) (lp.flags and nf.inv()) or watch else (lp.flags or nf) and watch.inv()
        if (attached) wm.updateViewLayout(root, lp)
    }

    private fun imm() = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)

    private fun openInput() {
        inputOpen = true
        preInputAtBottom = atTrueBottom()   // 必须在键盘上抬（relayout for IME）之前读，否则读到的是压矮后的视口
        setFocusable(true)
        askEdit.requestFocus()
        // 窗口刚改成可获焦点、还没真拿到焦点时叫键盘会被系统丢掉 → 等拿到焦点再叫
        if (root.hasWindowFocus()) showIme()
        else root.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(has: Boolean) {
                if (!has) return
                root.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                if (inputOpen) showIme()
            }
        })
    }

    private fun showIme() {
        askEdit.requestFocus()
        if (android.os.Build.VERSION.SDK_INT >= 30) askEdit.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
        else imm().showSoftInput(askEdit, 0)
    }

    fun closeInput() {
        if (!inputOpen) return
        imm().hideSoftInputFromWindow(askEdit.windowToken, 0)
        askEdit.clearFocus()
        inputOpen = false
        setFocusable(false)
        relayout()   // 面板从键盘上方落回原位
    }

    /**
     * 发送：输入框的话先过 FreePrompt.parse —— 里面既有问的是什么、也有希望怎么答（「只给答案」「分步骤」「用英文」）。
     * 认得出的参数和认不出的原话一起拼成风格段（见 FreePrompt.funtoPrompt）。
     * 整句都是指令（「只给答案」）时没有真问题，补一句中性的问法顶上，指令本身当主体。
     * 空着又没开开关 → 只是把输入框点开。
     *
     * v1.72：风格段**不再**并进 question —— 它得由 Solver 单独传给 Prompts，拼在提示词**最后**。
     * 并进去的话它落在中间，后面「不超过 N 字」「用中文」两条默认约定紧跟着把它压掉，等于没生效。
     */
    private fun sendTyped() {
        val style = FreePrompt.parse(askEdit.text.toString())
        // [FreePrompt] 真问题 = 抽掉指令词后剩下的；纯指令句用一句中性问法顶上，别发空问题
        val q = style.question.ifBlank { if (style.hasDirective) Prompts.ASK_FREE_Q else "" }
        val question = Prompts.styledAsk(q, askWhat, askWhy)
        // [FreePrompt] 风格段单独走（第 4 个参数），位置由 Prompts 决定 —— 压在所有默认约定之后
        val styled = FreePrompt.funtoPrompt(style)
        if (question.isEmpty() && styled.isEmpty()) { if (!inputOpen) openInput(); return }
        askEdit.setText("")
        // 面板上显示用户原话（他刚写的，认得出来），别把风格段贴到面板上
        val shown = if (style.raw.isNotBlank()) Prompts.askShown(style.raw, askWhat, askWhy)
                    else Prompts.askShown(q, askWhat, askWhy)
        onAsk(shown, question, askWithShot, styled, style.lang)
    }

    /** [FreePrompt] 长按输入框 = 清除 what / why 两个快捷开关，回到纯自由模式（只按输入框里写的要求回答）。
     * 没开开关时返回 false —— 让长按照常弹选词手柄，不然就没法局部修改文字了。 */
    private fun clearQuickTogglesOnLongPress(): Boolean {
        if (!askWhat && !askWhy) return false
        askWhat = false; askWhy = false
        refreshToggles()
        setStatus("已关掉快捷开关 · 接下来只按你输入的要求回答")
        return true
    }

    /** 有可追问的对话、面板不在收起档时才露出追问栏 */
    fun setAskEnabled(b: Boolean) {
        askEnabled = b
        if (!b) closeInput()
        updateAskBar()
    }

    private fun updateAskBar() {
        val show = askEnabled && state != COLLAPSED && !passive
        askBar.visibility = if (show) View.VISIBLE else View.GONE
        // v1.88：整个追问栏收起来时，顺带把「追问选项」也收回去（下次露出来是整行清爽的输入框）
        if (!show && askOptRow.visibility == View.VISIBLE) { askOptRow.visibility = View.GONE; updateAskMore() }
    }

    /** 开始一次追问：问题作为「## 追问：…」小标题接在正文最后，回答流式接在它下面；withShot = 续图追问 */
    fun beginFollowUp(q: String, withShot: Boolean = false) {
        // v1.88 修（follow-from-bottom）：发送这一刻按**真实位置**重判跟随 ——
        // 用户本来就在最底看流式（follow 可能因流式增量 / 手势 settle 竞态停在 false）→ 恢复跟随，
        // 后面每个 chunk 的 render→scheduleFollowScroll 才会真的贴底；在上方则维持原状，不抢他的读位。
        // 打字发送时键盘把视口压矮了，位置读数会偏「不在底部」，用开输入框那一刻记录的真底兜底。
        val atBottom = atTrueBottom() || (inputOpen && preInputAtBottom)
        follow = followOnSend(follow, atBottom)
        closeInput()
        if (state == COLLAPSED) setState(HALF, persist = false)
        raw.append("\n\n").append(if (withShot) Prompts.FOLLOW_HEAD_SHOT else Prompts.FOLLOW_HEAD).append(q.replace('\n', ' ')).append("\n\n")
        awaitingFollow = true
        render()
        setStatus("正在回答追问…")
    }

    /** 位置、宽度、各档高度 —— 建面板、转屏、改设置、小球挪了都走这里（v1.60 位置可选，见 PanelLayout） */
    fun relayout() {
        if (liftedForIme) return   // 正坐在键盘上：等键盘收起再摆
        val s = screen()
        val sp = PanelLayout.compute(prefs.panelOptions(), s, orbRect())
        spec = sp
        lp.gravity = vGravity(sp.v) or hGravity(sp.h)
        lp.x = sp.x; lp.y = sp.y
        // v1.74：竖屏下面板宽度一律收到屏宽 75%（和 WordCard 弹层同一口径）；横屏保持 PanelLayout 原逻辑
        val dm = ctx.resources.displayMetrics
        lp.width = if (dm.heightPixels >= dm.widthPixels) Math.min(sp.width, dm.widthPixels * 75 / 100) else sp.width
        // v1.54：正文上限只按屏幕比例算、没扣标题栏和答案行，全屏档 + 大字号时面板比窗口还高，最后一行被裁掉
        root.maxHeightPx = sp.maxHeight
        placeGrip(sp.gripBottom)
        applyState(animate = false)
        if (attached) wm.updateViewLayout(root, lp)
    }

    /** 实测的「贴底 y 从哪量起」（悬浮窗父框下沿）；没量到之前按「屏幕高 − 导航条」算 */
    private var bottomRef = 0
    private var bottomRefFor = 0L
    private fun screen(): PanelLayout.Screen {
        val dm = ctx.resources.displayMetrics
        val h = realScreenHeight()
        val key = dm.widthPixels.toLong() * 100000 + h
        if (key != bottomRefFor) { bottomRefFor = key; bottomRef = 0 }   // 转屏了：重新量
        val nav = Ui.navBarHeight(ctx)
        return PanelLayout.Screen(dm.widthPixels, h, dm.density, Ui.statusBarHeight(ctx), nav, if (bottomRef > 0) bottomRef else h - nav)
    }

    /** 贴底摆好之后量一次父框下沿：和假设的不一样（导航条高度拿错、有挖孔等）就按实测的重摆 */
    private fun measureBottomRef() {
        if (!attached || liftedForIme || lp.gravity and Gravity.VERTICAL_GRAVITY_MASK != Gravity.BOTTOM) return
        if (root.translationY != 0f || root.height == 0) return
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        val ref = loc[1] + root.height + lp.y
        if (Math.abs(ref - screen().bottomRef) > 2) { bottomRef = ref; relayout() }
    }

    private fun vGravity(v: PanelLayout.V) = when (v) { PanelLayout.V.TOP -> Gravity.TOP; PanelLayout.V.BOTTOM -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
    private fun hGravity(h: PanelLayout.H) = when (h) { PanelLayout.H.START -> Gravity.LEFT; PanelLayout.H.END -> Gravity.RIGHT; else -> Gravity.CENTER_HORIZONTAL }

    /** 横条放在能长高的那一边：贴顶的面板放最下面（往下拖变高），其余放最上面 */
    private fun placeGrip(bottom: Boolean) {
        val idx = root.indexOfChild(grip)
        val want = if (bottom) root.childCount - 1 else 0
        if (idx == want) return
        root.removeView(grip)
        if (bottom) root.addView(grip) else root.addView(grip, 0)
        grip.setPadding(0, if (bottom) dp(3f) else dp(7f), 0, if (bottom) dp(7f) else dp(3f))
    }

    private fun realScreenHeight(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels

    private fun maxBodyHeight(s: Int): Int {
        val dm = ctx.resources.displayMetrics
        val usable = dm.heightPixels - Ui.statusBarHeight(ctx) - Ui.navBarHeight(ctx) - dp(24f)
        val (half, full) = PanelLayout.heights(prefs.panelHalfPct, prefs.panelFullPct)
        return when (s) {
            COLLAPSED -> 0
            HALF -> usable * half / 100
            else -> usable * full / 100
        }
    }

    private fun applyState(animate: Boolean) {
        val target = maxBodyHeight(state)
        // 被动模式：正文**留着**但限高（几行就够瞄一眼），不然随手扫一页非题目画面时面板是空的
        scroll.visibility = if (state == COLLAPSED && !passive) View.GONE else View.VISIBLE
        if (passive) { scroll.maxHeightPx = minOf(target, dp(PASSIVE_BODY_MAX_DP)); scroll.requestLayout(); return }
        updateAskBar()
        if (!animate || state == COLLAPSED) { scroll.maxHeightPx = target; scroll.requestLayout(); return }
        val from = scroll.height.coerceAtLeast(1)
        android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = 180
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { scroll.maxHeightPx = it.animatedValue as Int; scroll.requestLayout() }
            start()
        }
        updateAnswerLineVisibility()
    }

    private fun setState(s: Int, persist: Boolean = true) {
        if (s == state) return
        state = s.coerceIn(COLLAPSED, FULL)
        if (persist) { prefs.panelState = state; jumpCollapsed = false }
        applyState(animate = true)
        if (raw.isNotEmpty()) render() else updateAnswerLineVisibility()   // 从收起档展开：顶部行换回答案（收起时放的是正文首行）
    }

    /**
     * 横条（v1.85 两档循环）：点一下 = 半屏 ↔ 大屏；往「能长高的方向」拖 = 更高，反过来 = 更矮
     * —— 结果都夹在半屏～大屏之间，到不了收起档（循环在 [PanelLayout.toggle] / [PanelLayout.nextState]，可单测）；
     * v1.60：按住 0.35 秒（震一下）再拖 = 把整个面板挪到任意位置，松手就记成「自由位置」。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindGrip(grip: View) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var moved = false
        var moving = false
        var startLeft = 0; var startTop = 0
        val armMove = Runnable {
            if (moved || !attached || liftedForIme) return@Runnable
            moving = true
            grip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // 换成左上角定位，窗口跟着手指走
            val loc = IntArray(2); root.getLocationOnScreen(loc)
            startLeft = loc[0]; startTop = loc[1]
            lp.gravity = Gravity.TOP or Gravity.LEFT; lp.x = startLeft; lp.y = startTop
            wm.updateViewLayout(root, lp)
            root.animate().alpha(0.85f).setDuration(80).start()
            setStatus("拖到想要的位置松手 · 以后就在这里出现")
        }
        grip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; moved = false; moving = false; main.postDelayed(armMove, 350) }
                MotionEvent.ACTION_MOVE -> {
                    if (moving) {
                        val dm = ctx.resources.displayMetrics
                        lp.x = (startLeft + (ev.rawX - downX).toInt()).coerceIn(0, Math.max(0, dm.widthPixels - root.width))
                        lp.y = (startTop + (ev.rawY - downY).toInt()).coerceIn(Ui.statusBarHeight(ctx), Math.max(Ui.statusBarHeight(ctx), realScreenHeight() - Ui.navBarHeight(ctx) - dp(60f)))
                        wm.updateViewLayout(root, lp)
                    } else if (Math.abs(ev.rawY - downY) > slop || Math.abs(ev.rawX - downX) > slop) { moved = true; main.removeCallbacks(armMove) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(armMove)
                    if (moving) {
                        moving = false
                        root.animate().alpha(1f).setDuration(80).start()
                        val dm = ctx.resources.displayMetrics
                        val s = screen()
                        if (prefs.panelPos != PanelLayout.FREE) prefs.panelFreeWidthPct = (root.width * 100 / Math.max(1, dm.widthPixels - dp(12f))).coerceIn(40, 100)
                        val (fx, fy) = PanelLayout.toFree(s, lp.x, lp.y)
                        prefs.setPanelFree(fx, fy)   // 触发设置监听 → relayout()
                        status.visibility = View.GONE
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // 贴顶的面板横条在下面：往下拖才是变高
                        val dy = (ev.rawY - downY) * if (spec?.gripBottom == true) -1 else 1
                        when {
                            !moved -> setState(PanelLayout.toggle(state))          // v1.85 点一下：半屏 ↔ 大屏
                            dy < 0 -> setState(PanelLayout.nextState(state, 1))     // 往外拖：更高（夹在半屏～大屏）
                            else -> setState(PanelLayout.nextState(state, -1))      // 往回拖：更矮（夹在半屏～大屏）
                        }
                    }
                }
            }
            true
        }
    }

    // ---------------- 正文左右长滑划掉面板（v1.85，手势照词卡那套）----------------

    /**
     * 把「横向位移」接到**窗口 x** 上（视图自己的 translationX 会被窗口 surface 裁掉 —— 面板窗口正好
     * 只有一个面板那么宽，同 [WordCard] 的 WindowShift）。横向 gravity 不同，lp.x 的度量方向也不同：
     *  - START / CENTER_HORIZONTAL：+x 就是往右（正号）；
     *  - END（= RIGHT）：+x 是离右边缘的距离，得取反 —— 手指往右划，面板才真的往右走。
     * [rebase] 在按下时记一次基准 x，弹簧写回 0 时窗口就回到原位。
     */
    private inner class PanelShift : Anim.VisualTarget {
        private var baseX = 0
        private var baseY = 0
        // lp.x / lp.y 的度量方向跟 gravity 有关：贴右 / 贴底时偏移方向相反，取符号对齐「手指方向」
        private val signX: Int get() = if (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) -1 else 1
        private val signY: Int get() = if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM) -1 else 1
        fun rebase() { baseX = lp.x; baseY = lp.y }
        /** 只把窗口横向位移收回原位（不动 alpha）：甩出时先归位，再走原有的淡出关闭 */
        fun resetX() { lp.x = baseX; push() }
        private fun push() { if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {} }
        override var alpha: Float
            get() = root.alpha
            set(v) { root.alpha = v }
        override var scaleX: Float
            get() = root.scaleX
            set(v) { root.scaleX = v }
        override var scaleY: Float
            get() = root.scaleY
            set(v) { root.scaleY = v }
        override var translationX: Float
            get() = ((lp.x - baseX) * signX).toFloat()
            set(v) { lp.x = baseX + (v * signX).toInt(); push() }
        override var translationY: Float
            get() = ((lp.y - baseY) * signY).toFloat()
            set(v) { lp.y = baseY + (v * signY).toInt(); push() }
    }

    private val panelShift by lazy { PanelShift() }
    /** 正文划动专用的弹簧：和入场 / 出场的 [motion] 分开 —— 那个走视图位移，这个走窗口 x，互不打架 */
    private val dragMotion by lazy { AnimGlue.SpringAnimation(root, Anim.Spring.release, target = panelShift) }
    /** 手指横向速度采样（屏幕坐标，见 [Anim.DragSamples] 的说明） */
    private val bodySamples = Anim.DragSamples()
    private var bodyDownX = 0f
    private var bodyDownY = 0f
    private var bodyDragDx = 0f
    private var bodyDragging = false
    private var bodyAxisDecided = false

    /**
     * 正文（滚动区）上的触摸：**第一次移动**就定方向 ——
     * 竖向 / 斜向里竖向占优 → 本手势彻底交给 ScrollView 滚（返回 false，此后永不接管）；
     * 横向占优 → 接管，跟手横移 + 轻微淡出，松手按「投影终点」（[Anim.dragDismissAny]，阈值同词卡）
     * 决定划掉还是弹回。这样竖向滚动、横条拖动都不受影响。
     *
     * v1.88：这条监听也是**阅读位置**的入口 —— 按下就停跟随（往上读 / 按住选字都不许被抢回底部），
     * 松手后等惯性停下、确实停在最底才恢复（见 [pauseFollowForUser] / [armBodySettle]）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindBodyDismiss(v: View) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bodyDownX = ev.rawX; bodyDownY = ev.rawY
                    bodyDragDx = 0f; bodyDragging = false; bodyAxisDecided = false
                    bodySamples.clear(); bodySamples.add(ev.eventTime, ev.rawX)
                    pauseFollowForUser()  // 用户接管阅读位置：停跟随 + 掐掉排队中的自动滚动
                    panelShift.rebase()   // 记下窗口当前的静止 x
                    // 复位（上一次手势若被打断）：保留当前 alpha，只把窗口位移收回、停掉残留弹簧
                    dragMotion.settleAt(Anim.Visual(root.alpha, 1f, 0f, 0f))
                    false                 // 先不接管：竖向留给 ScrollView
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - bodyDownX
                    val dy = ev.rawY - bodyDownY
                    if (!bodyAxisDecided) {
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) < slop) return@setOnTouchListener false
                        bodyAxisDecided = true
                        // 竖向占优：这次手势是滚动，交回 ScrollView（此后一直返回 false）
                        if (Math.abs(dy) > Math.abs(dx)) return@setOnTouchListener false
                        bodyDragging = true
                    }
                    if (!bodyDragging) return@setOnTouchListener false
                    bodySamples.add(ev.eventTime, ev.rawX)
                    bodyDragDx = dx
                    applyBodyDrag()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 手指离开（含被系统收走）：等甩动惯性停下来再判定 —— 确实停在最底才恢复跟随
                    if (bodyGestureActive) armBodySettle()
                    if (!bodyDragging) return@setOnTouchListener false
                    bodySamples.add(ev.eventTime, ev.rawX)
                    bodyDragging = false
                    releaseBodyDrag(bodySamples.velocity())
                    true
                }
                else -> false
            }
        }
    }

    /** 跟手：横向位移直接写死（经 [PanelShift] 落到窗口 x），拖得越远越淡（最多 15%，同词卡 v1.81） */
    private fun applyBodyDrag() {
        val w = root.width.coerceAtLeast(1)
        val rel = (Math.abs(bodyDragDx) / w).coerceIn(0f, 1f)
        dragMotion.settleAt(Anim.Visual(1f - rel * 0.15f, 1f, bodyDragDx, 0f))
    }

    /**
     * 松手：横向投影终点过阈值就划掉 —— 阈值同词卡 = max(44dp, 面板宽 0.3f)，纵向阈值为 0（不参与判定）。
     * 划掉走和 ✕ 完全一样的关闭路径（先把窗口横向位移收回原位，再淡出移除 + [onClose]）；
     * 没过就带**手指速度**弹回（[Anim.Spring.release]，和词卡一样，不留「被拖走」的钝感）。
     */
    private fun releaseBodyDrag(vx: Float) {
        val thresholdX = Math.max(dp(44f).toFloat(), root.width * 0.3f)
        if (Anim.dragDismissAny(bodyDragDx, 0f, vx, 0f, thresholdX, 0f)) {
            panelShift.resetX()
            fadeOutAndRemove()
            onClose()
        } else {
            dragMotion.to(Anim.Visual.SHOW, Anim.Spring.release, Anim.Visual(0f, 0f, vx, 0f))
        }
        bodyDragDx = 0f
    }

    /** 正文字号（v1.66 起在设置页调，改了面板开着也立刻生效） */
    fun applyTextSize() { body.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSp.toFloat()) }

    fun attach() {
        if (attached) return
        relayout()   // 小球可能挪过：跟着 / 避开小球的位置按现在的算
        applyPassive()   // 复用的面板：上一轮如果开着被动模式（自动扫描），这一轮得先落回来再加窗口
        val sp = spec
        // 从贴着的那一边滑进来（v1.60）。起点先写死（值写死、速度清零），再加窗口，最后交给弹簧（v1.78）。
        motion.settleAt(Anim.Visual(0f, 1f, dp(24f) * (sp?.enterX ?: 0).toFloat(), dp(24f) * (sp?.enterY ?: 1).toFloat()))
        try { wm.addView(root, lp) } catch (e: Exception) { return }   // 悬浮窗权限刚被收回：不加就是了，别崩
        attached = true
        Ui.popTick(ctx)   // v1.73：弹出来时轻轻震一下
        // 面板是长期复用的同一个视图，所以这条弹簧也常驻：接连弹两下、或者刚弹出又收起，
        // 都是「接着当前速度走」而不是重新起步
        motion.to(Anim.Visual.SHOW, Anim.Spring.enter) { measureBottomRef() }
    }

    fun fadeOutAndRemove() {
        if (!attached) return
        attached = false
        val sp = spec
        motion.to(Anim.Visual.leaving(sp?.enterX ?: 0, sp?.enterY ?: 1, dp(16f).toFloat()), Anim.Spring.exit) { remove() }
    }

    fun remove() {
        cancelFollowScroll()                    // 面板走了：排队中的自动滚动 / 待判定都别再跑
        main.removeCallbacks(bodySettle)
        bodyGestureActive = false
        stagingActive = false; staging.setLength(0)
        cancelScanSettle()                      // 内容动画落终态：alpha=1 / scale=1，不再动已脱窗的视图
        try { wm.removeView(root) } catch (_: Exception) {}
        if (Links.onOpened === linksOnOpened) Links.onOpened = null   // P2-10：清全局引用防泄漏
        attached = false; liftedForIme = false
    }

    // 截屏前会临时藏面板：停在半路的 translationY 残留 24dp 会让面板底部被窗口裁掉一截（v1.54 修）。
    // v1.78 起走动画器的 settleAt —— 停帧循环 + 属性写死成终态是同一个动作，漏不掉。
    // v1.86 P1-1：已经开始关闭的（attached=false，fadeOutAndRemove 已排队 remove()）不再接管 ——
    // 否则这次 capture 的 settleAt 会把排队的 remove() 吞掉、留下一个 alpha=0 的幽灵窗口。
    fun setHidden(h: Boolean) { if (!attached) return; motion.settleAt(if (h) Anim.Visual.HIDDEN else Anim.Visual.SHOW) }

    /**
     * 被动模式（v1.78，自动扫描时用）：面板只剩**答案文字**。
     *
     * 自动扫描时要的是一块「看得见、但完全不碍事」的答案，所以：
     *  - 外壳全收（把手、工具行、模式抽屉、状态行、正文、词块、追问）；
     *  - 面板底去掉 —— 整块完全透明，只剩文字浮在屏幕上；
     *  - 窗口加 FLAG_NOT_TOUCHABLE：**点哪都直接穿到下面的 App**，不抢触摸也不占地方；
     *  - 于是这个模式下唯一的出口是长按小球 → 菜单里关掉自动扫描。
     *
     * 没有面板底之后，文字得自己压得住任意背景，所以给答案一行加一层深色投影。
     */
    fun setPassive(on: Boolean) {
        if (passive == on) return
        passive = on
        applyPassive()
        relayout()   // 外壳收了，窗口要重新量 / 重新摆（不然留着上一档的高度）
    }

    /** 被动模式要收起来的那几块。注意「词块 / 追问」本来就是有时才显示，不能一律设 VISIBLE */
    /**
     * 被动模式要收起来的那几块。**正文（scroll）不在里面** ——
     * 实测：非题目画面（随手扫一页网页/文档）模型不会给「答案」行，内容全在正文里，
     * 只留答案行的话被动面板经常是空的。所以正文留着，另行限高（见 [PASSIVE_BODY_MAX_DP]）。
     */
    private val chrome by lazy { listOf<View>(grip, bar, modeTray, status, termsBar, askBar) }
    /** 进被动模式前各块的可见性：退出时原样还原 */
    private var chromeWas: IntArray? = null

    /** 把 [passive] 落到视图上。attach 之后也要再落一次（面板是复用的，上一轮可能开着被动模式） */
    private fun applyPassive() {
        cancelPeek()   // v1.86：外壳要换了，先把「破例亮着」的那一行收掉，别留下一个可触摸的透明窗口
        if (passive) {
            if (chromeWas == null) chromeWas = IntArray(chrome.size) { chrome[it].visibility }
            for (v in chrome) v.visibility = View.GONE
        } else {
            chromeWas?.let { was -> chrome.indices.forEach { chrome[it].visibility = was[it] } }
            chromeWas = null
        }
        root.background = if (passive) null else Ui.panelBg(ctx)
        val p = dp(if (passive) 3f else 14f)
        root.setPadding(p, dp(if (passive) 3f else 10f), p, dp(if (passive) 3f else 14f))
        // 没有面板底，文字得自己压得住背景：给答案行和正文都加一层深色投影（只作用于文字，不占地方）
        val shadow = if (passive) dp(3f).toFloat() else 0f
        for (v in listOf(answerLine, body, scanLine)) { v.setShadowLayer(shadow, 0f, 0f, 0xFF000000.toInt()); v.invalidate() }
        if (!passive) scroll.maxHeightPx = Int.MAX_VALUE   // 退出被动模式时由 applyState 重新算
        lp.flags = if (passive) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                   else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    fun setModeLabel(s: String) { modeLabel = s; modeChip.text = s + if (modeTray.visibility == View.VISIBLE) "  ▴" else "  ▾" }

    private fun toggleModes() {
        val open = modeTray.visibility != View.VISIBLE
        modeTray.visibility = if (open) View.VISIBLE else View.GONE
        setModeLabel(modeLabel)
        refreshModes()
        // 抽屉感：从模式标签下面往下拉出来
        if (open) {
            trayMotion.settleAt(Anim.Visual(0f, 1f, 0f, -dp(10f).toFloat()))
            trayMotion.to(Anim.Visual.SHOW)
        }
    }

    /**
     * 模式抽屉（v1.66）：只列模式（通用 / 做题 / 自定义…），一行一个，点了就切并收起。
     * 不透明度、解析强度、小白、位置宽度、新建 / 改名都在设置页里调 —— 抽屉保持简洁。
     */
    fun refreshModes() {
        if (modeTray.visibility != View.VISIBLE) return
        modeTray.removeAllViews()
        val cur = prefs.mode
        val customs = prefs.customModes
        for (m in Modes.all(customs)) {
            val sel = m == cur
            // 选中的前面打 ✓、加粗加下划线 —— 不靠颜色
            modeTray.addView(Ui.button(ctx, (if (sel) "✓ " else "　") + Modes.label(m, customs)) {
                if (m != prefs.mode) {
                    prefs.mode = m
                    if (raw.isNotEmpty()) setStatus("已切到「${Modes.label(m, prefs.customModes)}」· 双击小球按这个模式识别")
                }
                toggleModes()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                if (sel) { setTypeface(typeface, Typeface.BOLD); paint.isUnderlineText = true }
                contentDescription = Modes.label(m, customs) + if (sel) "，当前模式" else "，点按切换到这个模式"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        }
    }

    /** tap：点状态行做什么（默认 = onReauth 弹授权框）；v1.62 加上，好让提示直接跳到无障碍开关 */
    fun setStatus(s: String, clickable: Boolean = false, tap: (() -> Unit)? = null) {
        cancelPeek()   // v1.86：新的状态行盖过「破例亮着」的那一行（否则 3 秒倒计时里会两层信息抢一行）
        status.text = s
        status.visibility = if (passive) View.GONE else View.VISIBLE   // 被动模式没有状态行（自动扫描的倒计时靠小球的「扫」角标）
        status.setOnClickListener(if (clickable) View.OnClickListener { (tap ?: onReauth)() } else null)
        status.paint.isUnderlineText = clickable   // 可点的状态行加下划线（不靠颜色区分）
        status.invalidate()
    }

    fun phase(s: String) { if (awaitingFollow) setStatus("追问 · $s") else if (raw.isEmpty() || stagingActive) setStatus(if (stagingActive) "自动扫描 · 正在重新识别…（上一轮结果先留着）" else s) }

    // ---------------- v1.86 被动模式下「破例亮一行」（字段声明在类顶部，init 之前） ----------------

    /**
     * 被动模式（自动扫描）下**破例亮一行**状态，[ms] 毫秒后自己收回。
     *
     * 为什么不干脆 `setPassive(false)` 三秒：那会把整块面板外壳（面板底、把手、工具行、正文限高）
     * 突然压回来，看着像面板自己弹出来了，比亮一行字打扰得多。这里只动两样：状态行露出来、
     * 并且这 3 秒窗口收得到点击（不然「点这里继续」是假的）。窗口仍全透明、正文仍限高。
     * 非被动模式下状态行本来就在 —— 那就是普通 [setStatus]。
     */
    fun peekStatus(s: String, ms: Long, tap: (() -> Unit)? = null) {
        if (!passive) { setStatus(s, tap != null, tap); return }
        main.removeCallbacks(peekEnd)
        status.text = s
        status.visibility = View.VISIBLE
        status.paint.isUnderlineText = tap != null
        status.setOnClickListener(tap?.let { f -> View.OnClickListener { f() } })
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        peeking = true
        main.postDelayed(peekEnd, ms.coerceAtLeast(600L))
    }

    /** 亮着的那一行收回。只在**还在**被动模式时收 —— 期间用户关掉了自动扫描，状态行本来就该留着 */
    private fun endPeek() {
        if (!peeking) return
        peeking = false
        status.setOnClickListener(null)
        status.isClickable = false
        if (!passive) return
        status.visibility = View.GONE
        status.paint.isUnderlineText = false
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    /** 强行收回亮着的那一行（关掉自动扫描 / 面板要收起时） */
    fun cancelPeek() { main.removeCallbacks(peekEnd); endPeek() }

    fun setStarred(on: Boolean) {
        starBtn.text = if (on) "★" else "☆"
        starBtn.contentDescription = if (on) "已收藏，点一下取消" else "收藏这道题"
    }

    fun reset() {
        // v1.85：从链接临时收起里恢复时，记住的若是老版本的收起档也归到半屏（用户档永远到不了收起档）
        if (jumpCollapsed) { jumpCollapsed = false; setState(if (prefs.panelState == COLLAPSED) HALF else prefs.panelState, persist = false) }
        closeInput(); awaitingFollow = false
        setStarred(false)
        raw.setLength(0)
        clearScan()   // 丢掉自动扫描的暂存 / 计时行，并把内容动画落回 alpha=1 scale=1
        // v1.88 新契约：新一轮从**开头**看，不跟到底、也不留上一次的排队滚动 / 手势状态
        follow = false; preInputAtBottom = false; cancelFollowScroll(); bodyGestureActive = false; main.removeCallbacks(bodySettle)
        answerDuplicated = false
        body.text = ""; answerLine.visibility = View.GONE; scroll.scrollTo(0, 0)
        showTerms = emptyList(); activeTerm = null; termsBar.visibility = View.GONE
        invalidateTermsRow() }   // v1.65：词块跟着上一轮一起清掉；快照一并作废，下一轮 render 必须真重建

    /**
     * 自动扫描：开始新的一轮（**每一轮都调，含第一轮**）。从这一刻起 [append] 只往 [staging] 里攒，
     * 面板上仍是上一轮的答案；等 [done] 拿到完整暂存才一次性提交。计时行在这里复位（服务随后每
     * 250~500ms 调 [setScanElapsed] 推进）。
     */
    fun beginRescan() {
        stagingActive = true
        staging.setLength(0)
        scanComplete = false
        scanElapsedMs = 0
        scanLine.text = scanText(0)
        scanLine.visibility = View.VISIBLE
        setStatus("自动扫描 · 正在重新识别…（上一轮结果先留着）")
    }

    /**
     * 丢掉这一轮的暂存：**保留**当前 raw / text / 词块 / 滚动位置，也不触发任何 [done] 的提交副作用
     * （不换正文、不弹 tick、不做动画、不回顶）。失败 / 取消时由服务调用（服务同时停掉计时行）。
     */
    fun abortRescan() {
        stagingActive = false
        staging.setLength(0)
        scanComplete = false
        // v1.90：不清空文案、不收成 GONE —— 保留这一轮冻结的用时行（服务已在 finishSolve 里冻结），只切成 INVISIBLE。
        // 清空 + GONE 会让计时行每轮整个塌掉，正文跟着上下跳一段；保留行高就没有这个「每轮跳一下」。
        if (scanLine.visibility != View.INVISIBLE) scanLine.visibility = View.INVISIBLE
    }

    /**
     * 自动扫描计时行的唯一入口（服务每 250~500ms 单调推进一次）。
     * 只改这一行的文字，**不重排正文**。[complete] = 这一轮结束：把用时**冻结**，之后（含倒计时式的
     * 未完成上报）一律不再覆盖，直到下一次 [beginRescan] 复位。
     */
    fun setScanElapsed(elapsedMs: Long, complete: Boolean) {
        if (complete) {
            if (scanComplete) return
            scanComplete = true
            scanElapsedMs = elapsedMs.coerceAtLeast(0L)
        } else {
            if (scanComplete) return   // 已完成：不再被倒计时改写
            scanElapsedMs = elapsedMs.coerceAtLeast(0L)
        }
        scanLine.text = scanText(scanElapsedMs)
        if (scanLine.visibility != View.VISIBLE) scanLine.visibility = View.VISIBLE
    }

    /** 计时行文案：固定前缀 + 一位小数秒（宽度稳定，不来回跳） */
    private fun scanText(ms: Long): String = String.format(java.util.Locale.US, "扫描 %.1fs", ms / 1000f)

    /** 把自动扫描的暂存 / 计时行状态清干净，并把内容动画收尾（alpha=1 / scale=1），reset / showText / remove 共用 */
    private fun clearScan() {
        stagingActive = false
        staging.setLength(0)
        scanComplete = false
        scanElapsedMs = 0
        scanLine.text = ""
        scanLine.visibility = View.GONE
        cancelScanSettle()
    }

    fun showText(t: String) { reset(); raw.append(t); status.visibility = View.GONE; render() }

    fun append(t: String) {
        // 自动扫描这一轮：先只攒进暂存，面板上的上一轮答案原样留着（首个 token 不再抹掉旧答案）
        if (stagingActive) { staging.append(t); return }
        raw.append(t)
        awaitingFollow = false
        status.visibility = View.GONE
        if (renderQueued) return
        renderQueued = true
        // 一帧最多重排一次：流式块很碎，每块都重排是白费
        main.postDelayed({ renderQueued = false; render() }, 16)
    }

    // ---------------- 关键词词块（v1.65 起，解析逻辑在 Terms，纯 JVM 可单测）----------------

    /** 词块点击 = 带着截图追问这个词（同一个对话里展开分支，支持继续追问） */
    private fun askTerm(term: String) {
        activeTerm = term
        refreshTermsRow()
        // 真正发给模型的问题里那段「用大白话两三句话解释（…）」是内部指令，只走第 2 个参数；
        // 面板上显示的标题换成一句中性短标签 —— 内部解释指令不当作正文露给用户看。
        val q = "「$term」是什么意思？用大白话两三句话解释（是型号、编号或技术规格的话，说清是哪家的什么东西、这个参数意味着什么），再举一个贴近日常的小例子。"
        onAsk("解释「$term」", q, false, "", null)
    }

    /**
     * 按现在的 activeTerm 重画词块（点过的加粗 + 下划线，不靠颜色）。
     * 幂等：清单内容 + activeTerm 与上次构建时一致就直接返回，保留现有词块 —— 所以每帧调也只重建真正变化的那一次。
     * 词块的外观（字色 / 底色）随主题走，主题变了由 [invalidateTermsRow] 作废快照强制重建。
     */
    private fun refreshTermsRow() {
        if (termsBuilt == showTerms && termsBuiltActive == activeTerm) return
        termsBuilt = showTerms
        termsBuiltActive = activeTerm
        for (i in 0 until termsRow.childCount) buttons.remove(termsRow.getChildAt(i))
        termsRow.removeAllViews()
        for (term in showTerms) {
            val active = term == activeTerm
            termsRow.addView(askChip(if (active) "✓ $term" else term) { askTerm(term) }.apply {
                if (active) { setTypeface(typeface, Typeface.BOLD); paint.isUnderlineText = true }
                // v1.65 读屏：词块点开的是一条解释分支 —— 状态和后果都说清楚
                contentDescription = (if (active) "正在解释" else "解释") + term + "，点按在正文后面展开大白话解释"
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        }
    }

    /** 作废词块快照：下一次 [refreshTermsRow] 无视「没变」判断、强制重建（换主题 / 清理时用） */
    private fun invalidateTermsRow() { termsBuilt = null; termsBuiltActive = null }

    // ---------------- 阅读位置（v1.88「跟到底」开关） ----------------
    // 规则：只有**用户自己**滚到底才打开跟随；一按住就停；松手（含惯性）时停在最底才恢复。
    // 程序滚动 / 布局变化一律不许开合跟随，否则每次内容变高、键盘弹起都会被抢回底部。

    /** 手指按住正文 / 惯性还没停的这段窗口里，滚动位置才代表用户的意图 */
    private fun armBodySettle() { main.removeCallbacks(bodySettle); main.postDelayed(bodySettle, SETTLE_MS) }

    /**
     * 此刻是不是真的停在正文最底 —— 发送追问时按它重判跟随（见 [followOnSend]）。
     * 先信 [View.canScrollVertically]（精确），再补 1dp 容差：末行的取整 / 像素换算常差一两点，
     * 只看 canScrollVertically 会把「视觉上已经到底」误判成「还能往下滚一点」，正是「停在底部却不跟」的来源之一。
     * 只在**发送瞬间**读一次，不参与手势 / 程序滚动路径，故不会把用户正在看的位置抢回底部。
     */
    private fun atTrueBottom(): Boolean {
        if (!scroll.canScrollVertically(1)) return true
        val child = scroll.getChildAt(0) ?: return false
        val max = child.height + scroll.paddingTop + scroll.paddingBottom - scroll.height
        return max <= 0 || scroll.scrollY >= max - dp(1f)
    }

    /** 用户接管正文（手指按下 / 开始划动）：立刻停跟随，并掐掉排队中的自动滚动 */
    private fun pauseFollowForUser() {
        follow = false
        preInputAtBottom = false   // 用户自己上来读了：「发送时底部」这个判断作废
        cancelFollowScroll()
        bodyGestureActive = true
        main.removeCallbacks(bodySettle)
    }

    /** 掐掉排队中的「跟到底」滚动（执行时还会再复验一次 [follow]，这里是提前取消） */
    private fun cancelFollowScroll() {
        pendingFollowScroll?.let { scroll.removeCallbacks(it) }
        pendingFollowScroll = null
    }

    /**
     * 排队一次「跟到底」的滚动。**执行时复验**：这中间用户往上读了、面板收起了，就不滚；
     * 排队的那条 Runnable 留着引用，用户一按住正文就能提前取消（见 [cancelFollowScroll]）。
     */
    private fun scheduleFollowScroll() {
        if (!follow || state == COLLAPSED) return
        cancelFollowScroll()
        val r = Runnable {
            pendingFollowScroll = null
            if (follow && state != COLLAPSED) scroll.smoothScrollTo(0, body.height)
        }
        pendingFollowScroll = r
        scroll.post(r)
    }

    private fun render() {
        val src = raw.toString()
        val (ans, rest) = AnswerText.forPanel(src)
        // 顶栏那行是不是和正文里那一段重复：问 AnswerText 自己（forPanel 内部就是 splitFollowUps + split），
        // 免得「长 / 短」的判定和它漂移（见 [ANSWER_MAX_LINES]）
        val firstRound = AnswerText.splitFollowUps(src).first
        answerDuplicated = ans.isNotEmpty() && AnswerText.split(firstRound).second == firstRound
        // Terms.parse 已经把清单行整行剥掉了（含流式途中只吐到一半的标记行，见其 v1.89 说明）——
        // 直接用它给的正文，不再自己扫第二遍（原来那个 stripTermsMeta 是重复解析）。
        val (shown, terms) = Terms.parse(rest)
        showTerms = terms
        answerLine.text = Md.inline(ans, Ui.theme.codeBg)
        updateAnswerLineVisibility()
        body.text = Md.render(shown, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
        termsBar.visibility = if (terms.isEmpty() || state == COLLAPSED || passive) View.GONE else View.VISIBLE
        refreshTermsRow()
        // 只有用户在跟随时才往下贴；排队的那次滚动执行时还会再看一眼 follow（用户中途往上读就不滚）
        scheduleFollowScroll()
    }

    private fun updateAnswerLineVisibility() {
        val has = answerLine.text.isNotEmpty()
        answerLine.visibility = if (has) View.VISIBLE else View.GONE
        // 长答案（正文里还留着同一段）顶栏只摘 [ANSWER_MAX_LINES] 行，不整段铺两遍；短答案照旧整段显示
        answerLine.maxLines = if (answerDuplicated && state != COLLAPSED) ANSWER_MAX_LINES else AnswerText.PANEL_LINES
        // 收起档但还没解析出「答案」（通用模式看画面这类）：至少露出正文开头两行
        if (state == COLLAPSED && !has && raw.isNotEmpty()) {
            answerLine.text = Md.inline(AnswerText.firstLine(raw.toString()), Ui.theme.codeBg); answerLine.visibility = View.VISIBLE
        }
    }

    fun done() {
        awaitingFollow = false
        if (stagingActive) {
            stagingActive = false
            val staged = staging.toString()
            staging.setLength(0)
            // 这一轮没出正文（截屏失败 / 跳过 / 取消）：上一轮答案原样留着，不留任何反馈
            if (staged.isBlank()) return
            // 与当前完全一致：不换、不反馈（不弹 tick、不做动画、不回顶）
            if (staged == raw.toString()) return
            // 真正替换：正文 / 关键词 / 答案行一次性重排（render 里就是一起做的）
            raw.setLength(0); raw.append(staged)
            follow = false; preInputAtBottom = false; cancelFollowScroll(); bodyGestureActive = false; main.removeCallbacks(bodySettle)
            render()
            scroll.scrollTo(0, 0)   // 只有真的换了内容才回到开头
            Ui.popTick(ctx)
            playScanSettle()
            return
        }
        render()
        if (raw.isEmpty()) setStatus("解析完成，但模型没有返回内容")
    }

    /**
     * 内容替换后的**短促微动效**：只作用在正文这个内容子视图上（[body]）—— 从略淡 / 略小回到正常。
     * 刻意不碰 [root]：root 的 alpha / translation 是截图隐藏（[setHidden]）与入场弹簧 [motion] 的地盘，
     * 在这里动它会和「隐藏 / 弹入」抢所有权。
     * 收尾交给 [AnimGlue.animateVisual] 的终态纪律（被打断也写回 [Anim.Visual.SHOW]），
     * 因此中断时一定落到 alpha=1 / scale=1，不会把 mid-flight 的半透明留在屏幕上。
     */
    private fun playScanSettle() {
        if (!attached) return   // 面板已 detach：别再起动画，免得动画自己把「已移除」的视图又拉回来
        with(AnimGlue) {
            body.applyVisual(Anim.Visual(0.4f, 0.99f, 0f, 0f))
            body.animateVisual(Anim.Visual.SHOW, Anim.MICRO, AnimGlue.EASE_OUT)
        }
    }

    /** 收掉内容动画并立刻落到终态：cancel 会触发 animateVisual 的 cancel 分支，这里再兜一次底，确保 alpha=1 / scale=1 */
    private fun cancelScanSettle() {
        body.animate().cancel()
        with(AnimGlue) { body.applyVisual(Anim.Visual.SHOW) }
    }
}
