package com.chun.lingzhu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 *  - 新答案 / 打开历史**从开头看**，不自动跳末尾（初始 follow = false；请求开始也不许对空正文开跟随，见 [followOnRequestStart]）；
 *  - 正文增量、done 最终重排、关键词出现、切档、键盘弹起这些**都不抢**用户正在看的位置 ——
 *    只有用户自己在跟随时才继续跟；
 *  - 只有**用户自己**滚 / 拖到最底才打开跟随；手指一按住正文（准备往上读 / 选字）立刻停跟随，
 *    松手（含甩动惯性）时确实停在最底才恢复；
 *  - 排队中的自动滚动执行时会**复验**（期间用户接管就不滚），用户一按住就取消；
 *  - 发送追问（点词块 / 打字）时按**那一刻的真实位置**重判一次：本来就在最底就继续跟、在上方就原地不动
 *    （见 [beginFollowUp] / [followOnSend]）—— 光靠手势 settle 会在流式增量下漏判成 false，导致「停在底部却不跟」；
 *  - 程序滚动 / 布局回调**不许**打开跟随（不然回回都被抢回底部）。
 *  - 同一份答案**重开**（面板重开同一文 / 阅读态退出再进）保持已有阅读位置，不跳顶也不跳底
 *    （[keepReadPosOnShow]）；只有新识别 / 内容真的换了才从开头读 —— 默认从头读 ≠ 所有操作永远强制回顶。
 *  - v2.7.1 补一条：用户上翻后在流式期间**又有新内容**进来，只在正文下沿露一条「↓ 有新内容」，
 *    点它才回底并恢复跟随（[pendingNew] / [updateMoreHint] / [jumpToBottom]）。判据只看真实可滚距离。
 *
 * 流式进行态（v2.7.1，见 [stage] / [updateThinkLine]）：
 *  - 首 chunk 之前就有反馈：「已发送请求 · 等待首个输出 X.Xs」—— 报的是**真实请求时刻**，不是模型思考；
 *  - 真收到 reasoning 增量才显示思考字数与预览；点这一行展开「已收到的思考全文」（限高 + 自滚，正文优先）；
 *  - 正文一开始思考区**自动收起**，阶段转成「正文输出中 · 已 N 字」，正文增量照常渐进渲染；
 *  - 结束（done / 出错 / 取消 / 面板关闭）都由 [endThink] 收口：进行态收掉、读数冻结、已收到的内容留着；
 *  - 全程**不显示百分比 / 不估算 token**；思考内容永不进正文（[raw]）、历史、复制与追问上下文。
 *
 * 底部（v2.9，见 [applyToolsVisibility]）：关键词 / 来源 / 追问 三个入口同处一条 48dp 工具行，
 * 展开内容（词块 / 来源列表 / 输入栏）都挂在它上方 —— 半屏档比原来「三行入口各自 48dp」多出约两行正文，
 * 且没有内容的入口自己 GONE（不留空壳）。输入栏平时不占位，点「追问」才出现，再点一次收起。
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

        /** 手指离开正文后等这么久没再滚动，就认为甩动惯性停了（见 [armBodySettle]） */
        const val SETTLE_MS = 120L

        /** 思考行显示的字数上限（只留尾巴 —— 用户要看「正在想什么」，不是完整思维链） */
        const val THINK_TAIL_MAX = 240
        /** 思考行最短刷新间隔：思考 token 也是几十毫秒一块，逐块 setText 会把主线程打满 */
        const val THINK_TICK_MS = 120L
        /** 攒够这么多新字就破例立刻刷一次（长思考时看着才是在动） */
        const val THINK_MIN_CHARS = 24

        /** 思考计时的心跳间隔：0.1s 的读数在走，又不至于每帧 setText */
        const val THINK_CLOCK_MS = 200L

        /**
         * 展开「已收到的完整思考」时那块内容最多这么高 —— 有上限才不会把正文挤掉；
         * 实际高度取**它**和正文可用高度的 [THINK_SHARE_PCT]% 里更小的那个（见 [updateThinkHeight]）。
         */
        const val THINK_EXPAND_MAX_DP = 168f
        /** 展开的思考最多占正文可用高度的百分比：正文永远留着大半，不被固定大块思考区挤掉 */
        const val THINK_SHARE_PCT = 30
        /**
         * 已收思考全文的保留上限（字符）。展开看的是「最近收到的完整内容」，
         * 超了只从**最前面**丢，并在展开视图开头如实写「前 N 字已省略」—— 不偷偷截断。
         */
        const val THINK_FULL_MAX = 6000

        /** 这一轮请求的流式阶段。只决定提示文案 / 进行态的显示，不代表任何百分比 */
        const val STREAM_IDLE = 0      // 没有在跑的请求
        const val STREAM_WAIT = 1      // 已发出请求，还没收到第一个思考 / 正文增量（网络等候）
        const val STREAM_THINK = 2     // 正在收 reasoning 增量
        const val STREAM_BODY = 3      // 正文已在流（思考已收口）

        /** 还在跑的阶段（决定心跳与进行态要不要继续） */
        fun streamRunning(stage: Int): Boolean =
            stage == STREAM_WAIT || stage == STREAM_THINK || stage == STREAM_BODY

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

        /**
         * 一轮请求开始（[requestStarted]）时「要不要跟到底」的纯判定（脱离 View，JVM 可单测）。
         *
         * 新答案（[hasBody] = false：[reset] 刚清空正文）一律**不**打开跟随 —— 从开头读，
         * 正文增量不许把还没主动到底的用户一路拽到末尾。这正是「识别完成，答案默认在底部」的最早断点：
         * 旧写法 `raw.isEmpty() → follow = true`，之后每个 chunk 的 [scheduleFollowScroll] 就都贴底了。
         * 空正文也**不能**拿 [atTrueBottom] 兜底：reset 刚清完、布局还没重新量，可滚距离读到的还是上一轮的高度，全是假读数。
         *
         * 追加（[hasBody] = true：追问「## 追问」小标题 / 自动扫描重扫旧文还挂着）维持原有追加阅读策略 ——
         * 发送时 [followOnSend] 已按真实位置重判过一次，请求开始只在确实停在最底时恢复跟随，在上方则原地不动。
         */
        fun followOnRequestStart(hasBody: Boolean, wasFollowing: Boolean, atTrueBottom: Boolean): Boolean =
            if (!hasBody) false else followOnSend(wasFollowing, atTrueBottom)

        /**
         * 展示一份答案时「要不要保持已有阅读位置」的纯判定（脱离 View，JVM 可单测）。
         *
         * **同一份答案重开**（[sameText] 且正文正按同一形态展示着，[bodyShowsSame]）→ 读位原样留着，
         * 不跳顶也不跳底 —— 「默认从头读」是新答案的规则，不是所有操作永远强制回顶。
         * 新识别 / 内容换了 / 刚从被动层的「只抽答案」切回全文（原文虽同一份，但正文形态变了，
         * 旧 scrollY 在新排版里没有意义）都算**首次展示** → 从开头读。
         */
        fun keepReadPosOnShow(sameText: Boolean, bodyShowsSame: Boolean): Boolean = sameText && bodyShowsSame
    }

    var attached = false
        private set
    /**
     * 被上层临时让路（截屏前 / 前台卡片页盖住 / 另一层浮窗打开）：窗口 alpha=0 且**不可触摸**。
     * 与 [passive] 分开记 —— 隐藏是「这一会儿先让路」，被动层是「自动扫描时的常驻形态」。
     */
    private var hidden = false
    /**
     * 退场收尾的认领号（见 [fadeOutAndRemove]）：只有最新一轮排的 remove() 会执行。
     * 单调递增 —— attach / 新的退场 / remove 都会让它 +1，旧 completion 自动作废。
     */
    private var exitToken = 0
    private val main = Handler(Looper.getMainLooper())
    private val root: MaxHeightLinearLayout

    /**
     * 面板的弹簧动画（v1.78）。面板是**长期复用的同一个视图**，所以这条动画也常驻 ——
     * 「刚弹出来又收回去」「连着弹两次」都会接着当前速度走，不会重新起步。
     * 用 lazy 是因为 [root] / [modeTray] 得先在构造里建出来。
     */
    private val motion by lazy { AnimGlue.SpringAnimation(root, Anim.Spring.enter) }
    private val trayMotion by lazy { AnimGlue.SpringAnimation(modeTray, Anim.Spring.popIn) }
    /**
     * 正文高度（= [MaxHeightScrollView.maxHeightPx]）的过渡。面板是复用的，档位又会连点（半屏→大屏→半屏），
     * 所以必须**持有实例**才能打断上一段 —— 每次现开一个 ValueAnimator 的话，两段会同时往正文写高度、
     * 旧段的收尾还会把旧档位的值写回来，看起来就是「高度叠着走」。规矩见 [AnimGlue.HeightMotion]。
     */
    private val bodyHeight by lazy {
        AnimGlue.HeightMotion(
            read = { scroll.height.coerceAtLeast(1) },
            write = { scroll.maxHeightPx = it; scroll.requestLayout() },
        )
    }
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
    /**
     * 自动扫描会话那一行（页数 / 下次倒计时 / 已暂停）。同样不在 [chrome] 里：
     * 被动层和阅读态都要一直看得见（见 [setScanSession]），阅读展开全文时也不能被清掉。
     */
    private val scanMeta: TextView
    /**
     * 思考 / 请求状态行（v2.7.1，原来的 v2.7 思考行扩展而来）：一行说清「在等什么 / 想了多久 / 收了多少字」，
     * 有内容时**可点开**看已收到的完整思考。
     *
     * 与 [scanLine] 同一个套路 —— 只改这一行自己的文字，不碰正文、不调 [render]（流式期间每帧全树 markdown
     * 重排正是「流式卡」的来源）。内容一律来自 provider 真吐的 delta：没有就只报真实的等待 / 耗时，
     * 绝不凭空编造「正在思考…」的内容，也**不显示百分比**（谁也不知道这次要吐多少字）。
     */
    private val thinkHead: TextView
    /**
     * 紧凑预览：最近收到的一段思考（单行、从尾部截断）。不展开也看得见「正在想什么」；
     * 只有真收到过 delta 才出现 —— 等候阶段它是 GONE，不占高度。
     */
    private val thinkPeek: TextView
    /**
     * 展开的「已收到的思考全文」：限高（正文可用高度的 [THINK_SHARE_PCT]%，且不超过 [THINK_EXPAND_MAX_DP]）
     * + 自己滚。默认收起，点 [thinkHead] 才展开 —— 这样「思考 → 正文」阶段转变时正文不会被一块固定的
     * 大思考区挤掉；正文一开始这块就自动收起，用户想回看再点开。
     *
     * 只是**展示**：思考永远不进 [raw]，也不进历史 / 复制 / 追问上下文。
     */
    private val thinkScroll: MaxHeightScrollView
    private val thinkFull: TextView
    /** 流式进行态：一条按时间推进的细线（见 [StreamBarView]）。它不是百分比 —— 谁也不知道这次要吐多少字 */
    private val streamBar: StreamBarView
    /**
     * 正文「有新内容 / 回到底部」提示条（v2.7.1）：用户上翻离开底部后，流式还在继续 → 这里显示
     * 「↓ 有新内容」；点一下才回到底并恢复跟随。**绝不自动抢读位** —— 读位只由用户自己的手势决定。
     */
    private val moreBar: LinearLayout
    private lateinit var moreHint: TextView
    private val body: TextView
    private val scroll: MaxHeightScrollView
    /**
     * 底部工具行（v2.9）：关键词 / 来源 / 追问 三个入口原来各占一整行 48dp，半屏档白吃两行正文空间。
     * 现在它们并排在同一条 48dp 的紧凑工具行里，各自的展开内容都挂在这条行**上方**（向上长，入口行不动）。
     * 每个入口至少 48dp 触控；没有内容的入口自己 GONE，不留空壳。
     */
    private val toolsRow: LinearLayout
    private lateinit var askEntry: TextView
    /** 上一次落到视图上的「输入栏是否占位」—— 只用来避免流式里逐帧重写读屏文案 */
    private var composerShown = false
    private val termsHead: TextView
    private val termsScroll: android.widget.HorizontalScrollView
    private val termsRow: LinearLayout
    private var activeTerm: String? = null
    /** 词块是否展开（用户点入口才展开；[reset] 换答案恢复收起，换主题不丢） */
    private var termsOpen = false
    /** 上一次写进入口的（词数量、展开态）—— 流式每帧只比较，不重复 setText / 分配字符串 */
    private var termsHeadCount = -1
    private var termsHeadOpen: Boolean? = null
    /**
     * v2.3.3 联网来源块：把「已联网搜索 · N 条来源」从正文末尾那一行网址串
     * 改成每行一条（标题 / 域名 / 摘要），默认折叠前 [WebSources.PREVIEW] 条、可点开看全部。
     *
     * 为什么单独一块而不是塞进 body：body 是 [Md.render] 出来的富文本，
     * 在里面插可点击的折叠控件会把 Markdown 的 span 结构搞乱；来源是元数据，本就该和正文分开。
     */
    private val sourcesHead: TextView
    private val sourcesList: LinearLayout
    private var sourcesOpen = false
    private var sourceHits: List<WebSearch.Hit> = emptyList()

    /** v1.67 [M1]：这一轮拆出来的「记住这题」话题（模型给了动作行才有） */
    private var rememberTopic: String? = null
    private val askBar: LinearLayout
    private val askInputRow: LinearLayout
    /**
     * v2.4：追加快捷项（what / why / 新图 / 联网 / 选图）—— 点三横杠才展开，而且**向上**展开
     * （[askBar] 先 addView 这一行、再 addView 输入行）：展开吃的是正文上方的空间，底部输入行高度不变。
     * 窄屏放不下时横向滚动兜底，不把输入框压窄。
     */
    private val askOptRow: LinearLayout
    private val askOptScroll: android.widget.HorizontalScrollView
    private var askOptOpen = false
    /** 选项入口 = 纯三横杠图标按钮（48dp 触控，视觉 16dp）；不再拼「•选项」这类字符 */
    private lateinit var askMoreBtn: TextView
    private val askEdit: android.widget.EditText
    /** 主操作（唯一强调按钮） */
    private lateinit var sendBtn: TextView
    private lateinit var shotChip: TextView
    /** 选图：复用 ShareActivity 现成的选图入口（系统照片选择器 → 识别 → 新的一轮），不另造回调 */
    private lateinit var pickChip: TextView
    /** 联网开关：直接读 / 写 [Prefs.searchConfig] 的 enabled 字段（copy 保留其余字段），和设置页是同一个开关 */
    private lateinit var netChip: TextView
    /** v1.66 追问开关（收进「追问选项」）：what = 讲简单点 + 举例；why = 第一性原理问为什么。可以同时开 */
    private lateinit var whatChip: TextView
    private lateinit var whyChip: TextView
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
    private lateinit var starBtn: android.widget.TextView   // v2.3.3 收藏：compoundDrawable 星形（不再是 ☆/★ 文字）
    /** 当前是否已收藏：换主题后要按它重刷实心/空心星，不能只看 text（text 恒为空） */
    private var starOn = false
    private lateinit var closeBtn: TextView
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
    /** 自动扫描会话（[setScanSession]）：页数 / 距下次识题的毫秒 / 是否暂停。start0 由扫描方计数 */
    private var scanPages = 0
    private var scanNextInMs = 0L
    private var scanPaused = false
    /** 会话行是否已经建立过（手动识别 / reset 后清掉，避免旧页数赖在面板上） */
    private var scanSession = false
    /**
     * 当前是不是「自动扫描暂停后展开全文」的阅读态（[showScanReading]）。
     * 被动层退出后仍要把页数 / 倒计时留在面板上，而普通手动识别不该带这些行。
     */
    private var scanReading = false
    /** 「跟到底」开关：只有**用户自己**滚到底才打开（初始 false = 新答案 / 历史都从开头看，见类注释） */
    private var follow = false
    private var renderQueued = false
    /**
     * v2.1 性能：上一次 [render] 的真实耗时(ms)，给 [StreamRender] 定下一次流式排队间隔。
     * 0 = 还没量过（按最便宜处理，取 [StreamRender.MIN_MS]）。
     */
    private var lastRenderCostMs = 0L
    /** 排队中的一次「跟到底」滚动；用户接管时 [cancelFollowScroll]，执行时还会再复验一次 [follow] */
    private var pendingFollowScroll: Runnable? = null
    /** 手指正按在正文上 / 甩动惯性还没停：这期间由滚动位置推出的「到底了」才算用户自己的意图 */
    private var bodyGestureActive = false
    /** 惯性停下后判定一次（每一次滚动都往后顺延，见 [armBodySettle]） */
    private val bodySettle = Runnable {
        bodyGestureActive = false
        if (!scroll.canScrollVertically(1)) {
            follow = true                 // 确实停在最底才恢复跟随
            if (pendingNew) { pendingNew = false; updateMoreHint() }   // 自己读回底部了：提示就该消失
        }
    }
    /** 顶部「答案」那行是不是和正文里那一段重复（长答案时 AnswerText 不把它从正文剥掉）—— 重复就只摘 1~2 行 */
    private var answerDuplicated = false
    /** v1.85：用户可见的最小档是半屏 —— 记住的若是老版本留下的收起档（COLLAPSED），开面板时归到半屏 */
    private var state = if (prefs.panelState == COLLAPSED) HALF else prefs.panelState
    private var jumpCollapsed = false
    /** 这次回答里拆出来的关键词（词块） */
    private var showTerms: List<String> = emptyList()
    /**
     * 上一次 [refreshTermsRow] 处理过的清单快照（清单内容 + 当时的 activeTerm）。
     * 清单和选中项都没变就保留现有视图、不重建 —— 流式里的绝大多数帧都没变。
     * 收着（[termsOpen] = false）时只记快照、不建 chip：默认那条入口才是常态，展开时才付建视图的代价。
     * null = 快照已作废（首次 / reset 清理 / 展开时强制建一次），下一次调用强制重建。
     * 只存引用、每帧只做一次结构比较，不复制列表、不新增缓存框架。
     */
    private var termsBuilt: List<String>? = null
    private var termsBuiltActive: String? = null

    val hasText: Boolean get() = raw.isNotEmpty()
    /** 面板里的全部文字（第一轮 + 追问），追问答完存回历史用 */
    val text: String get() = raw.toString()

    /** 仅供同一答案关闭后重开；不持有窗口，不写磁盘。 */
    class ReadPos(val text: String, val scrollY: Int, val follow: Boolean)
    private var pendingReadRestore: android.view.ViewTreeObserver.OnPreDrawListener? = null

    fun readPosSnapshot(): ReadPos? =
        if (raw.isEmpty() || passive) null else ReadPos(raw.toString(), scroll.scrollY, follow)

    private fun cancelReadRestore() {
        pendingReadRestore?.let { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(it) }
        pendingReadRestore = null
    }
    /** 是否处于被动层（自动扫描）：此时状态行默认 GONE，报错要走 peekStatus 才看得见 */
    val isPassive: Boolean get() = passive

    private fun dp(v: Float) = Ui.dp(ctx, v)

    init {
        root = MaxHeightLinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(10f))
            background = shellBg()
        }

        // [K5] v1.70 知识库检索：把取卡的口子交给纯逻辑层（Solver 那边不碰 Context）。
        // 用 applicationContext —— 面板销毁后这张闭包还可能被后台追问线程用到。
        KnowledgeSearch.source = { runCatching { KnowledgeCard.Store(ctx.applicationContext).list() }.getOrDefault(emptyList<KnowledgeCard.KCData>()) }

        // 顶部横条（v1.85：拖动在半屏 ↔ 大屏两档间换档 / 点一下切换）
        grip = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(0, dp(7f), 0, dp(3f)) }
        handle = View(ctx)
        // 瑞士风：40×5dp 的实心块会变成一块多余的墨疙瘩 —— 收成一条细横线（宽度略收，高度按 1dp）
        val gripPx = if (Ui.shape.cornerDp <= 0f) LinearLayout.LayoutParams(dp(28f), dp(1f))
                     else LinearLayout.LayoutParams(dp(40f), dp(5f))
        grip.addView(handle, gripPx)
        root.addView(grip)

        // 工具行（v1.88）：一排轻量入口。模式标签保留可见（切模式高频）；☆ / ✕ 只留符号，
        // 触控区 48dp（视觉符号仍是小的），不再套白底黑框的小按钮。
        //
        // v2.3.2：用户要「图标缩小一半、把占格让给文字」。☆ / ✕ 的视觉符号缩到 ~12dp（字号 11f/10f），
        // 但触控区只收 48→36dp，**不跟着缩到 24**：36dp 是手指能稳定点中的下限，
        // ✕ 太小会误触邻键 —— 而这个 ✕ 一误触就是直接关掉面板，代价比多占 12dp 高得多。
        // 两个图标省下 24dp，全部让给 modeChip（见其 maxWidth）。
        bar = Ui.row(ctx).apply { setPadding(dp(4f), 0, dp(2f), 0) }
        modeChip = Ui.button(ctx, "") { toggleModes() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10f), 0, dp(10f), 0)
            minimumHeight = dp(48f); minimumWidth = dp(48f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            // v2.3.2：两个图标各让出 12dp（48→36），共 24dp 全给模式标签：140 → 164。
            // 保留 maxWidth 而不是改用 layout weight —— 这条横条里 modeChip 本就靠左侧、右侧用 0dp 空 View
            // 撑开（见下面 addView），已经吃满了剩余空间；再叠一个 weight 只会让 -2 的 wrap 宽度被拉成满行，
            // 短模式名（「通用 ▾」）后面拖出一大片空白，点了也会被当成切模式。maxWidth 只在上限处截断，
            // 短名字时按钮就是名字那么宽，正好。
            ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(164f)
            contentDescription = "当前模式，点按切换模式"
        }
        bar.addView(modeChip, LinearLayout.LayoutParams(-2, -2))
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // desc：读屏（TalkBack）念的名字 —— 「✕」念出来是符号名（v1.63）
        // v2.3.3：☆ / ✕ 从「文字符号」换成真正的 VectorDrawable（ic_star_line / ic_close）。
        // 之前用 ☆ ✕ 当图标，字形宽高和基线在每台 ROM / 字体下都不一样，缩到 11f 之后糊成一团 ——
        // 这才是用户说「图标都不太好」的根因（PersonaIcon 顶部记过同一笔账）。
        //
        // 用 TextView + setCompoundDrawablesRelative 而不是 ImageView：Ui.button 自带按下回弹、
        // 淡底按压反馈、可点性与 TalkBack 语义，换成 ImageView 这些要全部重写一遍；
        // 而 compoundDrawable 能直接接管尺寸与着色，按钮的 36dp 触控和 contentDescription 都不用动。
        starBtn = Ui.button(ctx, "") { onStar()?.let { setStarred(it) } }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, 0)
            minimumWidth = dp(36f); minimumHeight = dp(36f)
            gravity = Gravity.CENTER
            contentDescription = "收藏这道题"
        }
        setStarIcon(false)
        bar.addView(starBtn, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginStart = dp(2f) })
        // v1.78：这一行的「存卡」按钮撤掉了（用户反馈）。存知识卡照样有入口：系统分享纯文本给 Ling、
        // 设置 →「导入知识卡（Anki / TXT / Word / PDF）」、知识卡库。这里只留 收藏 / 关闭。
        closeBtn = Ui.button(ctx, "") { fadeOutAndRemove(); onClose() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, 0)
            minimumWidth = dp(36f); minimumHeight = dp(36f)
            gravity = Gravity.CENTER
            contentDescription = "关闭面板"
            setIcon(this, R.drawable.ic_close, 12f, Ui.TX2)
        }
        bar.addView(closeBtn, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginStart = dp(2f) })
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

        // 自动扫描会话行（页数 / 下次识题倒计时）：只在被动层与阅读态出现（普通手动面板不占额外空行）。
        scanMeta = Ui.text(ctx, "", 12f, Ui.TX3).apply {
            setPadding(dp(12f), 0, dp(12f), dp(1f))
            setSingleLine(true); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.MONOSPACE
            minWidth = dp(96f)
            visibility = View.GONE
        }
        root.addView(scanMeta)

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
        // 思考 / 请求状态块（v2.7.1）：一行状态 + 一行紧凑预览 + 可展开的已收内容 + 一条推进细线。
        // 和 scanLine 同一条纪律：只改这几块自己的文字 / 可见性，不碰正文、不触发 render。
        // 全部内容都来自 provider 真吐的 delta（或真实的请求时刻），不编造、不显示百分比。
        thinkHead = Ui.text(ctx, "", 12f, Ui.TX3).apply {
            setPadding(dp(12f), 0, dp(12f), dp(1f))
            setSingleLine(true); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            // 有真内容时点这一行 = 展开 / 收起「已收到的思考全文」（[toggleThinkFull] 自己挡空内容）
            setOnClickListener { toggleThinkFull() }
        }
        root.addView(thinkHead)
        // 预览：单行、从**尾部**截断（看的是最近在想什么）；不展开也读得到，等候阶段 GONE 不占高度
        thinkPeek = Ui.text(ctx, "", 11.5f, Ui.TX3).apply {
            setPadding(dp(12f), 0, dp(12f), dp(3f))
            setSingleLine(true); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setOnClickListener { toggleThinkFull() }   // 点预览同样能展开，命中区比一行状态更大
        }
        root.addView(thinkPeek)
        // 展开的已收思考全文：默认 GONE（收着不占高度），限高 + 自己滚，绝不把正文挤掉。
        // 不挂 movementMethod：这段是纯展示的思考原文，不需要链接；挂了会把它变成 clickable/longClickable，
        // 在这层嵌套滚动里抢走手指（同 v2.3.1 横划那个坑）。
        thinkFull = Ui.text(ctx, "", 11.5f, Ui.TX3).apply {
            setPadding(dp(12f), dp(2f), dp(12f), dp(6f))
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1.35f)
            visibility = View.GONE
        }
        thinkScroll = MaxHeightScrollView(ctx, 0).apply {
            addView(thinkFull)
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
        }
        root.addView(thinkScroll, LinearLayout.LayoutParams(-1, -2))
        // 进行态细线：只在跑着的时候占 2dp，结束立刻收掉（不留「卡住的进度条」）
        streamBar = StreamBarView(ctx).apply { visibility = View.GONE }
        root.addView(streamBar, LinearLayout.LayoutParams(-1, dp(2f)))

        body = Ui.text(ctx, "", prefs.textSp.toFloat()).apply { setLineSpacing(0f, 1.4f); setPadding(dp(12f), dp(2f), dp(12f), dp(10f)); movementMethod = Links.Tap }
        scroll = MaxHeightScrollView(ctx, 0).apply { addView(body); isVerticalScrollBarEnabled = true; isFillViewport = false }
         // 滚动位置**只**用来「等手势停下再判定到底没有」，绝不在这里打开跟随：
         // 程序滚动（自动跟底）、布局变化（正文变高 / 键盘弹起）也会走到这里，误判就会把用户正在看的位置抢回底部。
         // 但「有新内容」那条提示要跟着**真实位置**走：用户自己滚回底部时它必须消失（下面那个判断只读位置，不改跟随）。
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (bodyGestureActive) armBodySettle()
            if (pendingNew) updateMoreHint()   // 用户自己滚回底部时提示自动消失（只读位置，不改跟随）
        }
        // 正文带权重：总高度到顶时先缩正文，底下的追问栏不被挤掉
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))

        // 有新内容 / 回到底部（v2.7.1）：用户上翻后流式仍在长 → 这一条浮在正文下沿提示；
        // 点它才回底并恢复跟随。默认 GONE，不占高度、不抢读位。
        moreHint = Ui.text(ctx, "↓ 有新内容", 12f, Ui.TX2).apply {
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            setSingleLine(true)
            gravity = Gravity.CENTER
            minimumHeight = dp(36f)
            isClickable = true
            contentDescription = "下面有新内容，点按回到底部"
            // 点击挂在**这一行本身**：按下反馈（pressBg 的 pressed 态）和动作落在同一个视图上
            setOnClickListener { jumpToBottom() }
        }
        moreBar = Ui.row(ctx).apply {
            visibility = View.GONE
            setPadding(dp(6f), 0, dp(6f), dp(2f))
            addView(moreHint, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(moreBar)

        // 底部工具行（v2.9）：关键词 / 来源 / 追问 三个入口原来各占一整行 48dp，半屏档白吃两行正文。
        // 现在入口并排在同一条 48dp 的行里：两个计数入口按剩余宽度等分、追问按内容取宽靠右，
        // 所以任何屏宽下都不会溢出（文字超长自己截断，完整信息在 contentDescription 里）；
        // 展开内容照旧挂在行**上方**，向上长，入口行位置 / 高度不动。
        termsRow = Ui.row(ctx)
        termsHead = Ui.button(ctx, "") { toggleTerms() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(4f), 0, dp(6f), 0)
            minimumHeight = dp(48f)   // 触控区 48dp（行高统一给）；字号 / 内边距不变，符号视觉仍小
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "关键词"
        }
        termsScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(termsRow)
        }
        // 展开的词块挂在工具行上方：和入口分开加，入口行本身不被横向窗口一起撑高
        root.addView(termsScroll, LinearLayout.LayoutParams(-1, -2))

        // v2.3.3 联网来源：和关键词同一个形态 —— 一条「来源 N ▾」入口 + 展开后的列表。
        // 没联网（sourceHits 为空）时入口和列表都 GONE，工具行不为它留空位。
        sourcesHead = Ui.button(ctx, "") { toggleSources() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(4f), 0, dp(6f), 0)
            minimumHeight = dp(48f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "联网来源"
        }
        sourcesList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), 0, dp(8f), dp(2f))
            visibility = View.GONE
        }
        root.addView(sourcesList, LinearLayout.LayoutParams(-1, -2))

        // 工具行本体：关键词 | 来源 |（撑开）| 追问。两个计数入口各占一半剩余宽度（wrap 不下就截断），
        // 追问按内容取宽、至少 48dp —— 每项 48dp 高、触控 ≥48dp，360dp 甚至更窄都不会越界。
        // 谁没内容谁自己 GONE，剩下的自然摊开。
        toolsRow = Ui.row(ctx).apply {
            // 计数入口按 weight 吃满剩余宽度；只剩「追问」一个入口时（没有词、没联网）靠右，不孤零零挂在左边
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), 0)
            visibility = View.GONE   // 三个入口都露不出来时整行 GONE（applyToolsVisibility 统一算）
            addView(termsHead, LinearLayout.LayoutParams(0, dp(48f), 1f))
            addView(sourcesHead, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(4f) })
            askEntry = Ui.button(ctx, "追问") { if (inputOpen) closeInput() else openInput() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(12f), 0, dp(12f), 0)
                minimumHeight = dp(48f); minimumWidth = dp(48f)
                gravity = Gravity.CENTER
                maxLines = 1
                contentDescription = "追问：点开输入框"
            }
            addView(askEntry, LinearLayout.LayoutParams(-2, dp(48f)).apply { marginStart = dp(4f) })
        }
        // 追问：输入框 + 发送收进同一个轻量 composer（一层淡底，没有黑框）。
        // v2.4：快捷项收在三横杠里、**向上展开** —— 展开只吃正文上方的空间，底部这一行高度不变；
        // 平时就是 输入框 + 三横杠 + 发送 一行。
        askBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(2f), dp(8f), dp(4f))
            visibility = View.GONE
        }
        askOptRow = Ui.row(ctx).apply { setPadding(0, dp(2f), 0, dp(4f)) }
        whatChip = toggleChip("what", "讲简单点并举例") { askWhat = !askWhat; refreshToggles() }
        whyChip = toggleChip("why", "用第一性原理问为什么") { askWhy = !askWhy; refreshToggles() }
        askOptRow.addView(whatChip, chipLp())
        askOptRow.addView(whyChip, chipLp())
        // 续图开关 —— 追问时先截一张最新屏幕（原图和之前轮次照常带上，以新图为准）
        shotChip = askChip("新图：关") { askWithShot = !askWithShot; refreshToggles() }
        askOptRow.addView(shotChip, chipLp())
        // 联网：真的开 / 关 [Prefs.searchConfig]（和设置页同一个开关）；缺 Key / 地址时如实提示，不假装联网
        netChip = toggleChip("联网：关", "联网搜索") { toggleNet() }
        askOptRow.addView(netChip, chipLp())
        pickChip = askChip("选图") {
            try { ctx.startActivity(ShareActivity.pickIntent(ctx)) }
            catch (e: Exception) { setStatus("打不开相册：" + (e.message ?: "")) }
        }
        askOptRow.addView(pickChip, chipLp())
        // 窄屏兜底：选项自己横向滚，绝不把同一行之外的输入框挤窄（选项行在输入行上方）
        askOptScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(askOptRow, android.widget.FrameLayout.LayoutParams(-2, -2))
        }

        askInputRow = Ui.row(ctx).apply { setPadding(dp(2f), 0, dp(2f), 0) }
        askEdit = android.widget.EditText(ctx).apply {
            // v2.4：去掉占位 hint —— 输入行只留真内容，空着就是空着
            hint = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
            minimumHeight = dp(48f)   // 触控区 48dp；字号不压，内边距收一点让整行紧凑
            maxLines = 2              // 最多两行，长问题自己滚，不再一路撑高把正文挤没
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
        // 选项入口：纯三横杠图标，触控区 48dp（图标视觉 16dp，见 updateAskMore / retintIcons）
        askMoreBtn = Ui.button(ctx, "") { toggleAskOptions() }.apply {
            buttons.add(this)
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            contentDescription = "追问选项"
        }
        askInputRow.addView(askMoreBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        // 主操作唯一突出：发送 = 主色强调按钮（局部样式，不再套黑描边）
        sendBtn = Ui.buttonEmph(ctx, "发送") { sendTyped() }.apply {
            buttons.add(this)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), 0, dp(12f), 0)
            minimumWidth = dp(56f); minimumHeight = dp(48f)
            contentDescription = "发送追问"
        }
        askInputRow.addView(sendBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4f) })
        // 先加选项行、后加输入行 = 选项向上展开（底部这一行位置 / 高度都不动）
        askBar.addView(askOptScroll, LinearLayout.LayoutParams(-1, -2))
        askBar.addView(askInputRow, LinearLayout.LayoutParams(-1, -2))
        // 行序和旧版一致：词块 / 来源列表 → 工具行（三个入口）→ 输入栏。输入栏仍在最底、紧贴键盘，
        // 入口行在它上面：开输入时入口原地不动，只是输入栏那一段出现 / 消失。
        root.addView(toolsRow, LinearLayout.LayoutParams(-1, -2))
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
        // v2.3.1 横划真因修复：正文 / 答案行都是设了 movementMethod 的 TextView ——
        // setMovementMethod 内部走 fixFocusableAndClickableSettings，会把 TextView 变成 **clickable + longClickable**，
        // 于是它自己吃掉 DOWN、成为触摸目标，之后所有 MOVE 都只发给它；挂在 ScrollView 上的监听一个 MOVE 都收不到，
        // 面板纹丝不动（竖向滚动照旧：ScrollView 在父层 intercept，不受影响）。三块都要绑。
        bindBodyDismiss(scroll)
        bindBodyDismiss(body)
        bindBodyDismiss(answerLine)
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
        root.background = if (passive) null else shellBg()
        handle.background = handleBg()
        styleModeChip()
        styleQuiet(starBtn)
        styleQuiet(closeBtn)
        askInputRow.background = composerBg()
        // 其余小按钮（选项 / 词块 / 选项入口 / 发送）统一走局部轻量底：淡底、无描边；发送最后单独上主色强调
        for (b in buttons) styleSoft(b)
        styleQuiet(askMoreBtn)
        styleSend(sendBtn)
        answerLine.setTextColor(Theme.argb(0xFF, t.answer))
        status.setTextColor(Ui.TX3)
        scanMeta.setTextColor(Ui.TX3)
        scanLine.setTextColor(Ui.TX3)
        // 思考 / 请求状态与展开的全文用的都是次要色：它们是过程信息，不该和正文抢注意力
        thinkHead.setTextColor(Ui.TX3)
        thinkPeek.setTextColor(Ui.TX3)
        thinkFull.setTextColor(Ui.TX3)
        moreHint.setTextColor(Ui.TX2)
        moreHint.background = pressBg(Ui.theme.pillFill, cornerChip)
        body.setTextColor(Ui.TX1)
        askEdit.setTextColor(Ui.TX1); askEdit.background = null
        // 工具行三个入口同一层视觉：淡底、无描边、次要字色（和词块一个量级，不比正文抢眼）
        for (v in listOf(termsHead, sourcesHead, askEntry)) styleToolEntry(v)
        // v2.3.3：图标是白色 path + setColorFilter 上色，换主题时**必须重刷一遍**，
        // 否则深色主题切浅色（或反过来）后图标仍是上一个主题的颜色，会和底撞在一起看不清。
        retintIcons()
        refreshModes()   // 抽屉开着时按新配色重建（行底也是局部轻量样式）
        refreshToggles()
        // 进行态细线用的是主题色（Ui.TX3 / Brut 主色），换皮肤后要重画一次
        streamBar.invalidate()
        // 展开区的可用高度跟着主题的字号 / 面板档位变，换肤后重算一次
        updateThinkHeight()
        // 词块在 [buttons] 里已经就地换过底 / 字色了：这里不再强制重建，展开态与现有 chip 都留着
        if (raw.isNotEmpty()) render()
    }

    // ---------------- 局部样式（只服务本面板，不改共享 Ui / Brut） ----------------
    // 目标：圆角、淡底、无粗黑边 / 硬阴影；六套配色仍从 Ui.theme 取色。
    //
    // v2.7.1：这一层**不再自带一套圆角数字**。形状只有两个来源：
    //  - [Ui.shape]（瑞士风格 = 直角 0dp / 1dp 细线；默认与 Atelier 各自的分层圆角）；
    //  - 本类的三个槽位 [cornerPanel] / [cornerChip] / [cornerBar]，它们都从 [Ui.shape] 折算出来。
    // 于是「整套瑞士风」在这里也是完整的：面板、按钮、词块、进行态一起变成直角细线，而不是只换配色。

    /** 这套形状是不是直角（瑞士国际主义：0dp 圆角、1dp 细线、无投影） */
    private val sharp get() = Ui.shape.cornerDp <= 0f

    /** 当前配色是不是液态玻璃材质（v2.7.3）：只决定「谁来画底」，面板交互 / 流式渲染都不变 */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 面板 / 大块容器用的圆角（dp）：瑞士 0，其余沿用本面板原来的 20dp 量级 */
    private val cornerPanel: Float get() = if (sharp) 0f else 20f
    /** 按钮 / 词块 / 提示条用的圆角（dp）：瑞士 0，其余 10~12dp */
    private val cornerChip: Float get() = if (sharp) 0f else 11f
    /** 小控件（模式标签 / 静默入口）用的圆角（dp） */
    private val cornerBar: Float get() = if (sharp) 0f else 12f

    /** 面板外壳：圆角（瑞士直角）+ 主题细描边（不是黑框）。描边粗细跟 [Ui.shape] 走 */
    private fun shellBg(): android.graphics.drawable.Drawable = when {
        // 玻璃：整块外壳交给共享材质（轻透明层 + 高光边 + 柔和阴影），圆角走弹层档
        glass -> Ui.material(ctx, Ui.theme.surface, Ui.SurfaceRole.SHEET, cornerPanel)
        // 非玻璃：与旧 [Ui.panelBg] 同源同参，就地画（panelBg 现在返回 Drawable，不能再 .apply setStroke）。
        // 渐变配色（默认护眼 bg2 != null）保持左上→右下渐变；无投影形状强制纯色 —— 面板外观逐位不变。
        else -> when {
            Ui.theme.bg2 != null && Ui.shape.shadowDp > 0f ->
                android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Ui.theme.surface, Ui.theme.surfaceEnd),
                ).apply {
                    cornerRadius = dp(cornerPanel).toFloat()
                    setStroke(Math.max(1, dp(Ui.shape.strokeDp.coerceAtLeast(0.7f))), Ui.theme.hairline)
                }
            else -> android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.theme.surface)
                cornerRadius = dp(cornerPanel).toFloat()
                setStroke(Math.max(1, dp(Ui.shape.strokeDp.coerceAtLeast(0.7f))), Ui.theme.hairline)
            }
        }
    }

    /**
     * 横条手柄：纯色，不带描边。瑞士风下它本身就是 28×1dp 的一条细线（见 init 里的 [grip]），
     * 所以这里只把圆角也归零 —— 40×5dp 的实心块在瑞士风里会变成一块多余的墨疙瘩。
     */
    private fun handleBg(): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(Ui.theme.handle); cornerRadius = dp(if (sharp) 0f else 2.5f).toFloat()
    }

    /**
     * 轻量触感底：静止是一层淡底（[restFill] = 0 时完全透明），按下才浮一层。
     * 只在建面板 / 换主题时分配，流式帧内不会走到这里。
     * 按下态的填充由主题文字色派生，圆角跟 [Ui.shape] —— 所以瑞士风下是直角，默认风下还是圆角。
     */
    private fun pressBg(restFill: Int, radiusDp: Float): android.graphics.drawable.StateListDrawable {
        val t = Ui.theme
        // 玻璃：静止那层走共享材质（高光边跟来），按下态仍是纯色加深层；其余配色原样。
        val r = if (glass) Ui.cornerDp(Ui.SurfaceRole.CHIP, radiusDp) else radiusDp
        fun shape(fill: Int): android.graphics.drawable.Drawable = when {
            glass && fill != 0 -> Ui.material(ctx, fill, Ui.SurfaceRole.CHIP, r)
            else -> android.graphics.drawable.GradientDrawable().apply {
                setColor(fill); cornerRadius = dp(r).toFloat()
            }
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(Theme.argb(0x33, t.text)))
            addState(intArrayOf(), shape(restFill))
        }
    }

    /** 模式标签：一层很淡的底，保留「这里可以点」的暗示，但没有黑框 */
    private fun styleModeChip() {
        modeChip.setTextColor(Ui.TX1)
        modeChip.background = pressBg(Ui.theme.pillFill, cornerBar)
    }

    /** 顶栏 / 输入区里的静默入口：平时无框，按下才有一层淡底 */
    private fun styleQuiet(v: TextView) {
        v.setTextColor(Ui.TX2)
        v.background = pressBg(0, cornerBar)
    }

    /** 词块 / 选项这类小控件：淡底、圆角（瑞士直角）、无描边 */
    private fun styleSoft(v: TextView) {
        v.setTextColor(Ui.TX1)
        v.background = pressBg(Ui.theme.pillFill, if (sharp) 0f else 10f)
    }

    /**
     * 工具行入口（关键词 / 来源 / 追问）：一层淡底 + 无描边，静止也能看出「这里可以点」。
     * 字色取次要档 —— 这三个只是入口，正文才是主角；展开态的箭头由 [updateTermsHead] 单独刷。
     */
    private fun styleToolEntry(v: TextView) {
        v.setTextColor(Ui.TX2)
        v.background = pressBg(Ui.theme.pillFill, cornerChip)
    }

    // ---------------- 矢量图标（v2.3.3） ----------------

    /**
     * 把一个 VectorDrawable 挂到 TextView 的唯一位置（左上右下都设为 null，只留一个）。
     *
     * [color] 是主题色，必须每次现取：drawable 里 fillColor 写的是纯白占位，
     * 白 icon 涂在浅色主题的淡底上会直接看不见。用 setColorFilter 而不是给 drawable 加 tint 属性，
     * 是因为同一份 drawable 会被不同主题的多个面板共用，改属性等于改全局。
     *
     * [sizeDp] 是**视觉尺寸**（12dp），和按钮的 36dp 触控区是两件事：
     * 图标小、命中区大，既好看又好点。
     */
    private fun setIcon(v: TextView, res: Int, sizeDp: Float, color: Int, right: Boolean = false) {
        val d = ctx.getDrawable(res)?.mutate() ?: return
        val px = dp(sizeDp)
        d.setBounds(0, 0, px, px)
        d.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        v.setCompoundDrawablesRelative(null, null, null, null)
        // right = true 用于「文字 + 箭头」这种组合（关键词头）；否则是纯图标按钮，画在左侧居中的位置
        if (right) {
            v.setCompoundDrawablesRelative(null, null, d, null)
            v.compoundDrawablePadding = dp(3f)
        } else {
            v.setCompoundDrawablesRelative(d, null, null, null)
            v.compoundDrawablePadding = 0
        }
    }

    /** 收藏按钮：空心星 = 未收藏，实心星 = 已收藏。颜色跟着主题走，不写死。 */
    private fun setStarIcon(on: Boolean) {
        starOn = on
        setIcon(starBtn, if (on) R.drawable.ic_star_filled else R.drawable.ic_star_line, 12f, Ui.TX2)
    }

    /** 换主题后按当前状态重刷所有矢量图标（星标要保住实心/空心，箭头要保住展开/收起） */
    private fun retintIcons() {
        setStarIcon(starOn)
        setIcon(closeBtn, R.drawable.ic_close, 12f, Ui.TX2)
        setIcon(modeChip,
            if (modeTray.visibility == View.VISIBLE) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            10f, Ui.TX2, right = true)
        setIcon(termsHead,
            if (termsOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up,
            10f, Ui.TX2, right = true)
        setIcon(sourcesHead,
            if (sourcesOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up,
            10f, Ui.TX2, right = true)
        setIcon(askMoreBtn, R.drawable.ic_menu, 16f, askTint())
    }

    /**
     * 唯一强调：主色底 + 加粗字，仍然不带黑描边。
     * 瑞士风下改成「直角实心 + 无圆角」（形状跟 [Ui.shape]），颜色仍是当前主题的强调色 —— 于是 SWISS 得到
     * 一块正方形信号红，默认配色仍是圆角薄荷。
     */
    private fun styleSend(v: TextView) {
        v.setTypeface(v.typeface, Typeface.BOLD)
        v.setTextColor(Brut.on(Brut.MINT))
        v.background = when {
            glass -> Ui.material(ctx, Brut.MINT, Ui.SurfaceRole.BUTTON, 11f)   // 主色强调交给共享材质
            else -> android.graphics.drawable.GradientDrawable().apply {
                setColor(Brut.MINT); cornerRadius = dp(if (sharp) 0f else 11f).toFloat()
            }
        }
    }

    /**
     * composer 的一层淡底：输入框和发送像同一件东西。
     *
     * 瑞士风下它不再是一块圆角色块（那会和直角网格打架），而是**一条底边细线**：
     * 输入区看起来像印在纸上的一栏。只换背景 drawable —— 焦点、键盘、草稿行为一个字都不改。
     */
    private fun composerBg(): android.graphics.drawable.Drawable = when {
        sharp -> UnderlineBg(Math.max(1, dp(1f)), Ui.theme.hairline)
        glass -> Ui.material(ctx, Ui.theme.pillFill, Ui.SurfaceRole.CHIP, 15f)
        else -> android.graphics.drawable.GradientDrawable().apply {
            setColor(Ui.theme.pillFill); cornerRadius = dp(15f).toFloat()
        }
    }

    private fun askChip(label: String, act: () -> Unit) = Ui.button(ctx, label, act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        minimumHeight = dp(48f)   // 触控区 48dp，字与内边距不动
        styleSoft(this)   // 新建即按当前主题上轻量底：流式里新冒出的词块不会先闪一下黑框
        buttons.add(this)
    }

    /** 追问开关：统一小按钮字号 12sp，英文单词放得下不挤；开着 = 前面打 ✓、加粗加下划线（不靠颜色） */
    private fun toggleChip(label: String, desc: String, act: () -> Unit) = Ui.button(ctx, label, act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
        minimumHeight = dp(48f)   // 触控区 48dp，字与内边距不动
        maxLines = 1
        styleSoft(this)
        buttons.add(this); tag = desc
    }

    /** 选项行里每个 chip 统一 5dp 右间距：间距处处一样，不再「第一个没有、后面 marginStart」那种不齐 */
    private fun chipLp() = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5f) }

    /**
     * 刷新追加快捷项的外观 + 读屏描述：what / why 的 ✓ 与加粗下划线、新图的「开 / 关」、
     * 联网的真实状态（开 / 关 / 开着但缺配置），以及三横杠入口。
     * 联网标签只照 [Prefs.searchConfig] 与 [WebSearch.usable] 写，绝不把它显示成「已联网」。
     */
    private fun refreshToggles() {
        for ((chip, on, label) in listOf(Triple(whatChip, askWhat, "what"), Triple(whyChip, askWhy, "why"))) {
            chip.text = if (on) "✓$label" else label
            chip.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            chip.paint.isUnderlineText = on
            chip.contentDescription = (chip.tag as? String ?: label) + (if (on) "，已打开，接下来的追问都按这个要求回答" else "，已关闭")
            chip.invalidate()
        }
        // 新图开关：文案同样在这一处统一刷（applyTheme 换配色时也要跟着变）
        shotChip.text = if (askWithShot) "新图：开" else "新图：关"
        shotChip.contentDescription = "新图：" + if (askWithShot) "开，追问时先截一张最新屏幕（以新图为准）" else "关，追问沿用原来的截图"
        // 联网开关：读的是真正生效的那份配置
        val cfg = prefs.searchConfig
        val api = prefs.api()
        val ok = WebSearch.usable(cfg, api)
        netChip.text = when {
            cfg.enabled && ok -> "联网：开"
            cfg.enabled -> "联网：开·缺配置"
            else -> "联网：关"
        }
        netChip.setTypeface(Typeface.DEFAULT, if (cfg.enabled) Typeface.BOLD else Typeface.NORMAL)
        netChip.paint.isUnderlineText = cfg.enabled && ok
        netChip.contentDescription = when {
            cfg.enabled && ok -> "联网搜索已开，接下来的追问会带来源；点按关闭"
            cfg.enabled -> "联网搜索开着但没配好（" + WebSearch.missing(cfg, api) + "），这一轮不会联网；点按关闭"
            else -> "联网搜索关着，回答只靠模型自己知道的事；点按打开"
        }
        netChip.invalidate()
        updateAskMore()
    }

    /**
     * 联网开关：真的开 / 关 [Prefs.searchConfig]（copy 只动 enabled，来源 / Key / 深度原样保留），
     * 和设置页是同一个开关、下一轮追问立刻按它走。
     * 要打开但配置不全时**不**把开关拨成「开」—— 那样只会让人以为这一轮真联网了；改为如实说出缺什么。
     */
    private fun toggleNet() {
        val cfg = prefs.searchConfig
        val api = prefs.api()
        if (!cfg.enabled) {
            val want = cfg.copy(enabled = true)
            if (!WebSearch.usable(want, api)) {
                setStatus("联网搜索还没配好：" + WebSearch.missing(want, api) + " · 去设置页补上再开")
                refreshToggles()
                return
            }
            prefs.searchConfig = want
            setStatus("联网搜索：开 · 接下来的追问会带来源")
        } else {
            prefs.searchConfig = cfg.copy(enabled = false)
            setStatus("联网搜索：关 · 回答只靠模型自己知道的事")
        }
        refreshToggles()
    }

    /** v2.4：展开 / 收起「追问选项」—— 选项行在输入行上方，展开即向上长 */
    private fun toggleAskOptions() {
        askOptOpen = !askOptOpen
        askOptScroll.visibility = if (askOptOpen) View.VISIBLE else View.GONE
        updateAskMore()
    }

    /** 三横杠入口：纯图标（不再拼字符），展开或有开关开着时染主色，读屏说明带上具体状态 */
    private fun updateAskMore() {
        val on = listOfNotNull(
            if (askWhat) "讲简单点" else null,
            if (askWhy) "第一性原理" else null,
            if (askWithShot) "附新图" else null,
            if (prefs.searchConfig.enabled) "联网" else null,
        )
        setIcon(askMoreBtn, R.drawable.ic_menu, 16f, askTint())
        val suffix = if (askOptOpen) "，已展开" else "，点一下向上展开"
        askMoreBtn.contentDescription = "追问选项" + (if (on.isEmpty()) "，都关着" else "，开着：" + on.joinToString("、")) + suffix
    }

    /** 三横杠的着色：展开或有开关生效 = 主色；否则主题次色。状态不只靠颜色：读屏说明逐条列出开着的项 */
    private fun askTint(): Int =
        if (askOptOpen || askWhat || askWhy || askWithShot || prefs.searchConfig.enabled) Brut.MINT else Ui.TX2

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
        applyToolsVisibility()              // 输入栏这时才占位置：平时那一行还给正文
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
        applyToolsVisibility()
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

    /** 有可追问的对话、面板不在收起档时才露出「追问」入口与输入栏 */
    fun setAskEnabled(b: Boolean) {
        askEnabled = b
        if (!b) closeInput()
        applyToolsVisibility()
    }

    /**
     * 底部工具行 + 追问输入栏的唯一可见性口径：关键词 / 来源 / 追问 / composer 都在这一处定。
     *  - 关键词：本帧有词、不在收起档、不在被动层；
     *  - 来源：本轮真联网且有来源（空表 = 没联网，不为它留空位）；
     *  - 追问：有可追问的对话、不在收起档、不在被动层；
     *  - 输入栏（composer）跟着 [inputOpen]：收起时把这一整行还给正文，输入的草稿仍留在 [askEdit] 里。
     * 三个入口各自 GONE，剩下的自动摊宽；一个都露不出来时整行 GONE —— 不留空 bar。
     */
    private fun applyToolsVisibility() {
        val live = state != COLLAPSED && !passive
        val hasTerms = showTerms.isNotEmpty() && live
        val hasSources = sourceHits.isNotEmpty() && live
        val hasAsk = askEnabled && live
        termsHead.visibility = if (hasTerms) View.VISIBLE else View.GONE
        termsScroll.visibility = if (hasTerms && termsOpen) View.VISIBLE else View.GONE
        sourcesHead.visibility = if (hasSources) View.VISIBLE else View.GONE
        sourcesList.visibility = if (hasSources && sourcesOpen) View.VISIBLE else View.GONE
        askEntry.visibility = if (hasAsk) View.VISIBLE else View.GONE
        toolsRow.visibility = if (hasTerms || hasSources || hasAsk) View.VISIBLE else View.GONE
        val showComposer = hasAsk && inputOpen
        askBar.visibility = if (showComposer) View.VISIBLE else View.GONE
        // 读屏文案只在开合真的变了时重写 —— [applyToolsVisibility] 每帧（流式）都会被调，不逐帧分配字符串
        if (composerShown != showComposer) {
            composerShown = showComposer
            askEntry.contentDescription = if (showComposer) "收起追问输入框" else "追问：点开输入框"
        }
        // v1.88：输入栏收起来时，顺带把「追问选项」也收回去（下次露出来是整行清爽的输入框）
        if (!showComposer && askOptOpen) { askOptOpen = false; askOptScroll.visibility = View.GONE; updateAskMore() }
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

    /**
     * 落到当前档位的高度。高度走 [bodyHeight]（**同一实例**）：连点换档就是打断上一段、从当前高度接着走，
     * 旧段的收尾不会再把上一档的值写回来。收尾精确落在 [target]，不会停在半路。
     * [animate] = false（建树 / 转屏 / 改设置 / 收起档）直接落终态。
     */
    private fun applyState(animate: Boolean) {
        val target = maxBodyHeight(state)
        // 被动模式：正文**留着**但限高（几行就够瞄一眼），不然随手扫一页非题目画面时面板是空的
        scroll.visibility = if (state == COLLAPSED && !passive) View.GONE else View.VISIBLE
        // 换档 = 视口高度变了：展开的思考区上限按新视口重算（它是「正文的一部分」，不是写死的大盒子），
        // 思考块收起时这里就是一次空操作。
        updateThinkHeight()
        updateMoreHint()
        if (passive) { bodyHeight.settleAt(minOf(target, dp(PASSIVE_BODY_MAX_DP))); return }
        applyToolsVisibility()
        // 收起档不播过渡（屏幕上的面板要立刻让位，见 [linksOnOpened]）：直接写终态
        if (!animate || state == COLLAPSED) { bodyHeight.settleAt(target); return }
        bodyHeight.to(target)
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
        fun rebase() { baseX = lp.x; baseY = lp.y; lastX = Int.MIN_VALUE; lastY = Int.MIN_VALUE }
        /** 上一次真推给窗口的坐标（去重：一帧里没变就不重复 updateViewLayout） */
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        /**
         * 一帧只推**一次**窗口。以前 translationX / translationY 的 setter 各自调一次 updateViewLayout，
         * 而弹簧每帧写四个通道 → 每帧两次窗口布局，正是「跟手 / 划出掉帧」的直接原因
         * （[Anim.VisualTarget.commit] 的注释就是为这件事写的）。
         */
        override fun commit() {
            if (!root.isAttachedToWindow) return
            if (lp.x == lastX && lp.y == lastY) return
            lastX = lp.x; lastY = lp.y
            try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
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
            set(v) { lp.x = baseX + (v * signX).toInt() }
        override var translationY: Float
            get() = ((lp.y - baseY) * signY).toFloat()
            set(v) { lp.y = baseY + (v * signY).toInt() }
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
     *
     * 必须同时绑在 [scroll]、[body]、[answerLine] 上：后两个是 clickable 的 TextView（movementMethod 的副作用），
     * 会自己消费 DOWN 并独占整段手势，只绑 ScrollView 等于横划没有入口。
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
     * 划掉就走和 ✕ 一样的关闭路径（[fadeOutAndRemove] 淡出移除 + [onClose]），但**不许先把窗口横向位移收回原位**：
     * 收回的那一帧窗口已经摆回原位、alpha 还接近 1，肉眼就是「原位闪一下」再淡出（v2.3.1 的残影）。
     * 窗口就停在手指离开的位置淡出 —— 退出动画 [motion] 写的是视图变换，碰不到 lp.x；归位交给下一次
     * [attach] 里的 [relayout]。
     * 没过就带**手指速度**弹回（[Anim.Spring.release]，和词卡一样，不留「被拖走」的钝感）。
     */
    private fun releaseBodyDrag(vx: Float) {
        val thresholdX = Math.max(dp(44f).toFloat(), root.width * 0.3f)
        if (Anim.dragDismissAny(bodyDragDx, 0f, vx, 0f, thresholdX, 0f)) {
            // 带着松手那一刻的横向速度进退场：面板接着刚才那一下继续走并淡出，
            // 不会先停住一帧再从静止起步（那一下就是用户感到的「卡」）。窗口 x 停在手指离开的位置。
            fadeOutAndRemove(Anim.Visual(0f, 0f, vx, 0f))
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
        // P1：上一轮的退场完成回调（fadeOutAndRemove 排的 remove()）可能还没跑，窗口就还挂在屏上。
        // 直接 addView **同一个 root** 会抛 "already added"，被下面 runCatching 吞掉 → attached=true 但窗口不在，
        // 或者反过来留着一个幽灵窗口。这里先按「真的在窗口里」这个事实把它摘掉，再从干净状态加。
        if (root.isAttachedToWindow) runCatching { wm.removeViewImmediate(root) }
        exitToken++                  // 作废任何还没跑完的旧退场收尾（它们只 remove 自己那一轮）
        // 复用的面板重新挂上来：上一轮（思考心跳 / 排队跟底 / 手势 settle）的定时回调不许跨生命周期留下来跑
        // （[remove] 已清过一次，这里是「划走后马上又打开」那一路的兜底）。
        main.removeCallbacks(streamTick)
        cancelFollowScroll()
        main.removeCallbacks(bodySettle)
        bodyGestureActive = false
        hidden = false
        relayout()   // 小球可能挪过：跟着 / 避开小球的位置按现在的算
        applyPassive()   // 复用的面板：上一轮如果开着被动模式（自动扫描），这一轮得先落回来再加窗口
        syncTouchFlag()  // 上一轮可能因为隐藏 / 被动层被压成不可触摸：新窗口必须从一开始就能摸
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

    /**
     * 划走 / ✕ / 单击关：走可见的退场，然后把窗口摘掉。
     *
     * [velocity] 是松手那一刻的手指速度（划走时有，点 ✕ 时为 null）—— 交给弹簧当起点速度，
     * 出场才「接着刚才那一下」走，不会先停住再从静止起步。
     *
     * 收尾用 [exitToken] 认领：这次退场排的 remove() **只在这一轮还是最新一轮时**才执行。
     * 于是「关闭 → 立刻又打开」时，旧 completion 既不会删掉新窗口，也不会因为 settleAt 把回调吞掉
     * 而留下一个幽灵窗口（[attach] 会按 isAttachedToWindow 自己清场）。
     */
    fun fadeOutAndRemove(velocity: Anim.Visual? = null) {
        if (!attached) return
        attached = false
        val mine = ++exitToken
        val sp = spec
        motion.to(Anim.Visual.leaving(sp?.enterX ?: 0, sp?.enterY ?: 1, dp(16f).toFloat()), Anim.Spring.exit, velocity) {
            if (mine == exitToken) remove()
        }
    }

    fun remove() {
        exitToken++                             // 这一轮已经收尾：任何还没跑到的旧 completion 立刻作废
        cancelReadRestore()
        cancelFollowScroll()                    // 面板走了：排队中的自动滚动 / 待判定都别再跑
        main.removeCallbacks(bodySettle)
        bodyGestureActive = false
        stagingActive = false; staging.setLength(0)
        cancelScanSettle()                      // 内容动画落终态：alpha=1 / scale=1，不再动已脱窗的视图
        clearThink()                            // 思考行 / 计时状态一并收干净（面板没了不该再留 ticker 或残留文字）
        try { wm.removeView(root) } catch (_: Exception) {}
        if (Links.onOpened === linksOnOpened) Links.onOpened = null   // P2-10：清全局引用防泄漏
        attached = false; liftedForIme = false; hidden = false
    }

    /**
     * 截屏前会临时藏面板：停在半路的 translationY 残留 24dp 会让面板底部被窗口裁掉一截（v1.54 修）。
     * v1.78 起走动画器的 settleAt —— 停帧循环 + 属性写死成终态是同一个动作，漏不掉。
     * v1.86 P1-1：已经开始关闭的（attached=false，fadeOutAndRemove 已排队 remove()）不再接管 ——
     * 否则这次 capture 的 settleAt 会把排队的 remove() 吞掉、留下一个 alpha=0 的幽灵窗口。
     *
     * v2.7：隐藏必须**同时**不可触摸。以前只把 alpha 压到 0，窗口仍收触摸 —— 截屏那 120ms 里
     * 用户的手指点在「看不见的面板」上，等于把下层（菜单 / 提问窗）的点击吞掉。
     */
    fun setHidden(h: Boolean) {
        if (!attached) return
        // 幂等：已经在这个状态就只对齐触摸标志（不能每次 settleAt —— 那会把正在跑的入场弹簧按停，
        // 面板就变成「直接跳出来」，丢掉滑入）
        if (hidden == h) { syncTouchFlag(); return }
        hidden = h
        motion.settleAt(if (h) Anim.Visual.HIDDEN else Anim.Visual.SHOW)
        syncTouchFlag()
    }

    /** 窗口要不要收触摸：隐藏 / 被动层收，破例亮着的那一行（[peekStatus]）例外（它必须点得中） */
    private fun syncTouchFlag() {
        val block = hidden || (passive && !peeking)
        val want = if (block) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                   else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (want == lp.flags) return
        lp.flags = want
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    /**
     * 被动模式（v1.78，自动扫描时用）：面板只剩**答案文字**。
     *
     * 自动扫描时要的是一块「看得见、但完全不碍事」的答案，所以：
     *  - 外壳全收（把手、工具行、模式抽屉、状态行、词块、追问）；正文只留抽出来的答案，
     *    没有可靠答案结构就提示「点小球查看」，不拿整段解析兜底；
     *  - 面板底去掉 —— 整块完全透明，只剩文字浮在屏幕上；
     *  - 窗口加 FLAG_NOT_TOUCHABLE：**点哪都直接穿到下面的 App**，不抢触摸也不占地方；
     *  - 于是这个模式下唯一的出口是长按小球 → 菜单里关掉自动扫描。
     *
     * 没有面板底之后，文字得自己压得住任意背景，所以给答案一行加一层深色投影。
     */
    fun setPassive(on: Boolean) {
        if (passive == on) {
            if (!on) {
                // 已经不在被动层：退出阅读态（停扫描）时把会话行收回去；阅读暂停随后会调 showScanReading 再显式打开
                scanReading = false
                updateScanMeta()
            } else if (scanReading) {
                // 已经是被动层：若刚从阅读态退回来，正文还留着整段解析 —— 重画成「只有答案」。
                scanReading = false; updateScanMeta(); if (raw.isNotEmpty()) render()
            }
            return
        }
        passive = on
        if (on) scanReading = false   // 回被动层就不再是阅读态
        applyPassive()
        relayout()   // 外壳收了，窗口要重新量 / 重新摆（不然留着上一档的高度）
    }

    /**
     * 被动模式要收起来的那几块。**正文（scroll）不在里面** —— 正文现在只放
     * [AnswerText.scanAnswers] 抽出来的答案（没有可靠结构就放一句「点小球查看」），
     * 解析与关键词不进被动层；高度另行限住（见 [PASSIVE_BODY_MAX_DP]）。
     */
    private val chrome by lazy {
        listOf<View>(grip, bar, modeTray, status, thinkHead, thinkPeek, thinkScroll, streamBar, moreBar, toolsRow, termsScroll, sourcesList, askBar)
    }
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
            // 还原的是「进被动层之前」的可见性：思考块 / 进行态 / 回底提示的真实状态得按**现在**重算，
            // 否则会留下一块没有内容的展开区，或者一条已经跑完的进行态。
            if (stage != STREAM_IDLE) streamBar.start() else streamBar.stop()
            thinkScroll.visibility = if (thinkExpanded && thinkChars > 0) View.VISIBLE else View.GONE
            if (thinkChars > 0) updateThinkLine(SystemClock.elapsedRealtime(), done = stage == STREAM_IDLE)
            else if (stage == STREAM_IDLE) hideThinkLine()
            updateMoreHint()
        }
        root.background = if (passive) null else shellBg()
        val p = dp(if (passive) 3f else 12f)
        root.setPadding(p, dp(if (passive) 3f else 8f), p, dp(if (passive) 3f else 10f))
        // 没有面板底，文字得自己压得住背景：给答案行 / 正文 / 两行扫描信息都加一层深色投影（只作用于文字，不占地方）
        val shadow = if (passive) dp(3f).toFloat() else 0f
        for (v in listOf(answerLine, body, scanMeta, scanLine)) { v.setShadowLayer(shadow, 0f, 0f, 0xFF000000.toInt()); v.invalidate() }
        // 退出被动模式时由 applyState 重新算。这里先掐掉可能在跑的高度段（它下一帧会把上限写回旧档位），
        // 再绕过 [bodyHeight] 直接写 —— 所以也要让它忘掉记着的值
        if (!passive) { bodyHeight.cancel(); scroll.maxHeightPx = Int.MAX_VALUE }
        // 退出被动模式：chromeWas 还原的是「进去之前」的可见性，关键词 / 追问还要按当前内容与档位再校一次
        if (!passive) applyToolsVisibility()
        syncTouchFlag()   // 被动层 / 隐藏态 / 破例亮着的那一行，三者共同决定收不收触摸（单一口径）
        updateScanMeta()
        // 被动层只露「抽出来的答案」：从阅读态 / 手动全文回来时，正文得重画一次（不能把解析留在屏幕上）
        if (passive && raw.isNotEmpty()) render()
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    fun setModeLabel(s: String) {
        modeLabel = s
        val open = modeTray.visibility == View.VISIBLE
        // v2.3.3：▴/▾ 换成矢量折线。箭头**不进 text** —— 拼进字符串会被 maxWidth=164dp 的
        // ellipsize=END 连名字一起截掉（名字一长就只剩「模式名…」看不到箭头），
        // 挂成右侧 compoundDrawable 之后箭头永远在场，被截的只有模式名。
        modeChip.text = s
        setIcon(modeChip, if (open) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, 10f, Ui.TX2, right = true)
        modeChip.contentDescription = "当前模式：" + s + (if (open) "，模式列表已展开，点按收起" else "，点按切换模式")
    }

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
                    if (raw.isNotEmpty()) setStatus("已切到「${Modes.label(m, prefs.customModes)}」")
                }
                toggleModes()
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
                minimumHeight = dp(48f)   // 触控区 48dp；内边距不变，行看起来还是紧凑的
                setTextColor(Ui.TX1)
                background = pressBg(if (sel) Ui.theme.pillFill else 0, cornerBar)
                if (sel) { setTypeface(typeface, Typeface.BOLD); paint.isUnderlineText = true }
                contentDescription = Modes.label(m, customs) + (if (sel) "，当前模式" else "，点按切换到这个模式")
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
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
        peeking = true
        syncTouchFlag()   // 破例亮着的这一行必须点得中 —— 但它**不能**把隐藏态的窗口一起放出来
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
        syncTouchFlag()
    }

    /** 强行收回亮着的那一行（关掉自动扫描 / 面板要收起时） */
    fun cancelPeek() { main.removeCallbacks(peekEnd); endPeek() }

    fun setStarred(on: Boolean) {
        // v2.3.3：不再改 text（那会让按钮宽度随字形变），改为换图标：空心 = 未收藏 / 实心 = 已收藏。
        setStarIcon(on)
        starBtn.contentDescription = if (on) "已收藏，点一下取消" else "收藏这道题"
    }

    fun reset() {
        cancelReadRestore()
        // v1.85：从链接临时收起里恢复时，记住的若是老版本的收起档也归到半屏（用户档永远到不了收起档）
        if (jumpCollapsed) { jumpCollapsed = false; setState(if (prefs.panelState == COLLAPSED) HALF else prefs.panelState, persist = false) }
        closeInput(); awaitingFollow = false
        setStarred(false)
        // v2.3.3：换题必须清掉上一轮的来源，否则新答案下面还挂着上一题的网址
        resetSources()
        raw.setLength(0)
        clearThink()  // 新一轮：思考行与计时状态一并归零（上一轮的「思考输出 X.Xs」不许留在新题上）
        clearScan()   // 丢掉自动扫描的暂存 / 计时行，并把内容动画落回 alpha=1 scale=1
        // v1.88 新契约：新一轮从**开头**看，不跟到底、也不留上一次的排队滚动 / 手势状态
        follow = false; preInputAtBottom = false; cancelFollowScroll(); bodyGestureActive = false; main.removeCallbacks(bodySettle)
        answerDuplicated = false
        body.text = ""; answerLine.visibility = View.GONE; scroll.scrollTo(0, 0)
        showTerms = emptyList(); activeTerm = null
        // 换答案恢复收起；入口文案的快照一并作废（下一次 render 重新写）
        termsOpen = false; termsHeadCount = -1; termsHeadOpen = null
        termsScroll.visibility = View.GONE
        applyToolsVisibility()
        clearTermsRow() }   // v1.65：词块跟着上一轮一起清掉；快照一并作废，下一轮 render 必须真重建

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
        clearThink()
        scanLine.text = scanText(0)
        scanLine.visibility = View.VISIBLE
        setStatus("自动扫描 · 正在重新识别…（上一轮结果先留着）")
        // v2.4：自动扫描**不走 reset()**，旧来源会一直挂着 —— 新题（或缓存命中）出来时，
        // 下面还列着上一题的网址。新一轮开始就把来源清掉：本轮真联网了再由 onSources 填回来。
        resetSources()
    }

    /**
     * 清掉来源块。[reset] 与 [beginRescan] 共用：条目的点击 / 折叠状态一并作废，
     * 免得下一轮沿用上一轮的「已展开」。
     */
    private fun resetSources() {
        sourcesOpen = false
        setSourceHits(emptyList())
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
     * 自动扫描会话行（页数 / 下次倒计时 / 暂停）的唯一入口。
     * 服务按实际剩余时间（单调钟算出的 `nextScanAt - now`）推，面板只负责显示，不自己跑倒计时。
     * 被动层与阅读态都要看得见；普通手动识别不显示（[clearScan] 会清掉会话）。
     */
    fun setScanSession(pages: Int, nextInMs: Long, paused: Boolean) {
        scanPages = pages.coerceAtLeast(0)
        scanNextInMs = nextInMs.coerceAtLeast(0L)
        scanPaused = paused
        scanSession = true
        updateScanMeta()
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

    /** 计时行文案：固定前缀 + 一位小数秒（宽度稳定，不来回跳）；「含识别/搜索/生成」说明这不是纯模型耗时 */
    private fun scanText(ms: Long): String =
        String.format(java.util.Locale.US, "思考时间记录 %.1fs（含识别/搜索/生成）", ms / 1000f)

    // ---------------- 真实思考流 / 流式进行态（v2.7.1）----------------
    /**
     * 这一轮请求的发出时刻（[SystemClock.elapsedRealtime]，单调、不受用户改时间影响）；0 = 没有在等。
     *
     * **不是**模型思考耗时 —— 从发请求到第一个 reasoning / 正文增量之间是排队 + 首字节，属于网络等候。
     * 所以界面上分成两段说清（见 [updateThinkLine]），绝不把等候时间冒充成「模型思考」。
     */
    private var reqAt = 0L
    /** 第一个真 reasoning delta 到达的时刻；0 = 还没吐过思考 */
    private var thinkAt = 0L
    /** 思考输出结束时刻（首个正文 token / done / error / cancel 任一先到者）；0 = 还在思考 */
    private var thinkEndAt = 0L
    /**
     * 这一轮的流式阶段（[STREAM_WAIT] / [STREAM_THINK] / [STREAM_BODY]，结束时回 [STREAM_IDLE]）。
     * 只决定这一行的文案与进行态要不要走 —— 不代表任何进度，也不产生内容。
     */
    private var stage = STREAM_IDLE
    /** 这一轮已收到的**正文**字数（真实计数，用于「已 N 字」；不是估算、不是百分比） */
    private var bodyChars = 0
    /**
     * 心跳：跑着的时候每 [THINK_CLOCK_MS] 刷一次这一行，让「等了几秒 / 想了多久」真的在走。
     *
     * 为什么需要它：provider 的 delta 常常一段一段来，中间会停几百毫秒 —— 只在 delta 到达时刷新的话，
     * 读到的数字看起来是「卡住了」。这里只用单调钟读时间，不产生任何内容：没有 delta 就没有新文字。
     * 结束路径（[freezeThink] / [finishStream] / [clearThink] / [remove]）都会摘掉它。
     */
    private val streamTick = object : Runnable {
        override fun run() {
            if (!streamRunning(stage)) return
            updateThinkLine(SystemClock.elapsedRealtime())
            main.postDelayed(this, THINK_CLOCK_MS)
        }
    }
    /** 思考预览（只留尾巴，紧凑一行）；[thinkChars] 是这一轮真实收到的思考总字数 */
    private val thinkTail = StringBuilder()
    /**
     * 已收到的思考全文（点开才看）。上限 [THINK_FULL_MAX]，超了从**最前面**丢，
     * [thinkDropped] 记着丢了多少字 —— 展开视图开头如实写「前 N 字已省略」，不偷偷截断。
     */
    private val thinkFullBuf = StringBuilder()
    private var thinkDropped = 0
    private var thinkChars = 0
    /** 思考全文是否展开（用户点入口才开；正文一开始自动收起 —— 正文优先拿高度） */
    private var thinkExpanded = false
    /** 展开区上一次写进去时的字数：只有真的变了才重建文案（绝不逐 delta 复制大字符串） */
    private var thinkFullShown = -1
    /** 思考行刷新节流：每 [THINK_TICK_MS] 或每 [THINK_MIN_CHARS] 字才写一次视图 */
    private var thinkShownAt = 0L
    private var thinkShownChars = 0
    /** 用户在底部之外、这一轮又有新内容进来（点「有新内容」那条才回底） */
    private var pendingNew = false

    /**
     * 一轮请求开始（服务在**真正发请求那一刻**调）。[reset] / [beginRescan] 走的是 [clearThink]（只归零状态，
     * 不冒充一次新请求）。首 chunk 之前也必须有清楚反馈：这里就把「已发送请求 · 等待首个输出 0.0s」写上
     * 并让进行态走起来 —— 报的是真实的请求时刻，不编造任何思考内容，也不显示百分比。
     */
    fun requestStarted() {
        reqAt = SystemClock.elapsedRealtime()
        thinkAt = 0L; thinkEndAt = 0L
        thinkTail.setLength(0); thinkChars = 0
        thinkFullBuf.setLength(0); thinkDropped = 0; thinkFullShown = -1
        thinkShownAt = 0L; thinkShownChars = 0
        bodyChars = 0
        stage = STREAM_WAIT
        setThinkExpanded(false)   // 新一轮从收着开始：不把上一轮展开的大块思考带过来
        pendingNew = false
        // 要不要跟（v2.7.2 修，判定见 [followOnRequestStart]）：新答案（正文为空，刚 reset）**不**打开跟随 ——
        // 从开头读，正文增量不许把还没主动到底的用户拽到底。旧写法 `raw.isEmpty() → follow = true`
        // 正是「识别完成，答案默认在底部」的最早断点（之后每个 chunk 的 [scheduleFollowScroll] 都贴底）。
        // 空正文也别拿 atTrueBottom() 兜底：reset 刚清完、布局还没重新量，读到的是上一轮的可滚距离，全是假读数。
        // 追问 / 自动扫描重扫（正文还挂着）维持原有追加阅读策略：发送时 [followOnSend] 已按真实位置重判过，
        // 这里只在确实停在最底时恢复跟随，用户正读在上面则原地不动（绝不抢读位）。
        follow = followOnRequestStart(raw.isNotEmpty(), follow, atTrueBottom())
        updateMoreHint()
        streamBar.start()
        main.removeCallbacks(streamTick)
        main.postDelayed(streamTick, THINK_CLOCK_MS)
        updateThinkLine(reqAt)
    }

    /**
     * 收到**真实的**思考增量（providers 的 reasoning_content 原文）。第一个 delta 立即上屏（不等节流），
     * 之后按 [THINK_TICK_MS] / [THINK_MIN_CHARS] 节流刷新 —— 不逐 delta 无界重排。
     *
     * 只改这一块的文字（不 render、不碰正文）：流式期间每帧全树 markdown 重排就是「流式卡」的来源。
     * 思考内容**永不**进 [raw]：它不参与历史 / 复制 / 追问上下文。
     */
    fun onThink(delta: String) {
        if (delta.isEmpty()) return
        if (thinkEndAt != 0L) return   // 思考已收口（正文已开始 / 本轮已结束）：迟到的尾巴不再上屏
        val now = SystemClock.elapsedRealtime()
        val first = thinkAt == 0L
        if (reqAt == 0L) reqAt = now
        if (first) {
            thinkAt = now
            main.removeCallbacks(streamTick)
            main.postDelayed(streamTick, THINK_CLOCK_MS)   // 计时开始走（心跳刷新，直到正文 / 收尾）
        }
        stage = STREAM_THINK
        streamBar.start()
        thinkChars += delta.length
        thinkTail.append(delta)
        if (thinkTail.length > THINK_TAIL_MAX) thinkTail.delete(0, thinkTail.length - THINK_TAIL_MAX)
        thinkFullBuf.append(delta)
        if (thinkFullBuf.length > THINK_FULL_MAX) {
            val cut = thinkFullBuf.length - THINK_FULL_MAX
            thinkFullBuf.delete(0, cut)
            thinkDropped += cut
        }
        if (first || thinkChars - thinkShownChars >= THINK_MIN_CHARS || now - thinkShownAt >= THINK_TICK_MS) {
            thinkShownChars = thinkChars
            thinkShownAt = now
            updateThinkLine(now)
        }
    }

    /**
     * 第一个正文 token 到了 = 思考收口（[append] 调）。幂等：只冻一次，之后 [onThink] 的迟到增量一律丢弃。
     * 冻结后这一行**留着**（用户看得见「想了多久」），直到下一次 [requestStarted] / [clearThink] 清掉。
     *
     * 顺手把展开的思考全文收起来：阶段一变，（可能很高的）思考区不该继续挤占正文的高度。
     */
    private fun freezeThink() {
        if (thinkAt == 0L || thinkEndAt != 0L) return
        thinkEndAt = SystemClock.elapsedRealtime()
        setThinkExpanded(false)
        updateThinkLine(thinkEndAt)
    }

    /**
     * 本轮结束（首个正文之后的 done / 错误 / 取消 / 面板关闭都由服务调它）。
     * 幂等：思考行冻结、进行态收掉、心跳摘掉 —— 界面上的「还在跑」必须准确停下，已收到的内容原样留着。
     * 不吞真实错误：错误文案由调用方写进正文（[reset] + [append]），这里只负责把流式状态收干净。
     */
    fun endThink() {
        freezeThink()
        finishStream()
    }

    /** 收掉进行态与心跳；真实收到过思考时这一行**冻结保留**（「思考 X.Xs · N 字」），否则整行收掉不留空行 */
    private fun finishStream() {
        val wasRunning = stage != STREAM_IDLE
        stage = STREAM_IDLE
        main.removeCallbacks(streamTick)
        streamBar.stop()
        if (thinkAt != 0L) {
            if (thinkEndAt == 0L) thinkEndAt = SystemClock.elapsedRealtime()
            updateThinkLine(thinkEndAt, done = true)
        } else if (wasRunning) {
            hideThinkLine()
        }
        updateMoreHint()
    }

    /** 收掉思考块（新一轮 / 面板关闭 / 出错重置）。不发任何网络请求、不改正文 */
    fun clearThink() {
        reqAt = 0L; thinkAt = 0L; thinkEndAt = 0L
        thinkTail.setLength(0); thinkChars = 0
        thinkFullBuf.setLength(0); thinkDropped = 0; thinkFullShown = -1
        thinkShownAt = 0L; thinkShownChars = 0
        bodyChars = 0
        stage = STREAM_IDLE
        main.removeCallbacks(streamTick)
        streamBar.stop()
        thinkExpanded = false
        thinkScroll.visibility = View.GONE
        pendingNew = false
        hideThinkLine()
        hideMoreBar()
    }

    /** 收掉头行与预览（新一轮 / 面板关闭 / 出错重置）：不留下空行，也不留旧文字 */
    private fun hideThinkLine() {
        if (thinkHead.text.isNotEmpty()) thinkHead.text = ""
        if (thinkHead.visibility != View.GONE) thinkHead.visibility = View.GONE
        if (thinkPeek.text.isNotEmpty()) thinkPeek.text = ""
        if (thinkPeek.visibility != View.GONE) thinkPeek.visibility = View.GONE
    }

    /** 点思考行 = 展开 / 收起「已收到的思考全文」。只有真收到过内容才可点（点开不能是空白） */
    private fun toggleThinkFull() {
        if (thinkChars <= 0) return
        setThinkExpanded(!thinkExpanded)
    }

    /** 展开态的唯一写入口：收着的时候「点开」不该生效（还没收到任何内容，点开是空白） */
    private fun setThinkExpanded(on: Boolean) {
        val want = on && thinkChars > 0
        if (thinkExpanded == want) return
        thinkExpanded = want
        applyThinkExpanded()
    }

    /**
     * 展开 / 收起已收思考全文。展开时按当前正文视口算高度上限（[THINK_SHARE_PCT]% 且不超过
     * [THINK_EXPAND_MAX_DP]）—— 正文永远留着大半，不会被一块固定的思考区挤掉。
     * 高度按**实际排版**写死，不用任何假进度；跟在底部的人由 [scheduleFollowScroll] 贴底，上翻的人不动。
     */
    private fun applyThinkExpanded() {
        if (thinkExpanded) {
            thinkFullShown = -1
            refreshThinkFull()
            thinkScroll.visibility = if (passive) View.GONE else View.VISIBLE
            updateThinkHeight()
            thinkFull.post { if (thinkExpanded) thinkScroll.scrollTo(0, thinkFull.height) }
        } else {
            thinkScroll.visibility = View.GONE
            thinkFullShown = -1
        }
        // 头行的「点开全文 / 收起」跟着变；已收口时 stage 是 IDLE，所以显式按冻结态重写一次
        if (thinkChars > 0) {
            updateThinkLine(
                if (thinkEndAt != 0L) thinkEndAt else SystemClock.elapsedRealtime(),
                done = stage == STREAM_IDLE,
            )
        }
        scheduleFollowScroll()
    }

    /** 展开区的高度上限：取面板给正文的高度预算的一部分（**不**用被思考区压矮后的实测高度），正文永远留得住 */
    private fun updateThinkHeight() {
        // 预算取**面板给正文的固定预算**（[maxBodyHeight]），不用正文当前的实测高度：
        // 思考区一展开就把正文压矮，再用压矮后的高度算上限，就会「自己越算越小」（每次心跳缩一截）。
        // 上限 = 预算的 [THINK_SHARE_PCT]%，且不超过 [THINK_EXPAND_MAX_DP]；再给正文留出至少 72dp，
        // 小屏 / 大写字号下也不会把正文挤没。全部是真实排版像素，不含任何进度假设。
        val budget = maxBodyHeight(state)
        val cap = Math.min(dp(THINK_EXPAND_MAX_DP), budget * THINK_SHARE_PCT / 100)
            .coerceAtLeast(dp(28f))
            .coerceAtMost((budget - dp(72f)).coerceAtLeast(dp(28f)))
        if (thinkScroll.maxHeightPx == cap) return
        thinkScroll.maxHeightPx = cap
        thinkScroll.requestLayout()
    }

    /**
     * 把已收思考全文写进展开区。只在节流点、且字数真的变了时重建一次字符串
     * —— 逐 delta 复制 6000 字的缓冲会把主线程打满，而这里本来也不该逐字跟。
     */
    private fun refreshThinkFull() {
        if (thinkFullShown == thinkChars) return
        val stick = !thinkScroll.canScrollVertically(1)
        val body = thinkFullBuf.toString()
        thinkFull.text = if (thinkDropped > 0) "（前 $thinkDropped 字已省略）\n$body" else body
        thinkFullShown = thinkChars
        if (stick) thinkFull.post { if (thinkExpanded) thinkScroll.scrollTo(0, thinkFull.height) }
    }

    /**
     * 写这一行：阶段文案 + 紧凑预览（最近收到的思考，一行），末尾给「点开全文 / 收起」的真入口。
     * 文案刻意区分「等候」→「思考」→「正文输出中」→ 冻结：从发请求到第一个增量是网络等候，
     * 只报思考 delta 的跨度。没有百分比、没有 token 估算 —— 只有真实时刻、真实字数。
     *
     * [done] = 本轮已结束（冻结读数）：此时即使 [stage] 已回 [STREAM_IDLE] 也要把这一行写出来。
     */
    private fun updateThinkLine(now: Long, done: Boolean = false) {
        if (stage == STREAM_IDLE && !done) return
        if (done && thinkAt == 0L) return   // 没有真思考：结束时不凭空留一行
        val end = if (thinkEndAt != 0L) thinkEndAt else now
        val thinkMs = if (thinkAt == 0L) 0L else (end - thinkAt).coerceAtLeast(0L)
        val waitMs = if (reqAt == 0L) 0L else (end - reqAt).coerceAtLeast(0L)
        val head = when {
            done -> String.format(java.util.Locale.US, "思考 %.1fs · %d 字", thinkMs / 1000f, thinkChars)
            stage == STREAM_WAIT -> String.format(java.util.Locale.US, "已发送请求 · 等待首个输出 %.1fs", waitMs / 1000f)
            stage == STREAM_THINK -> String.format(java.util.Locale.US, "正在思考 %.1fs · %d 字", thinkMs / 1000f, thinkChars)
            thinkChars > 0 -> String.format(java.util.Locale.US, "思考 %.1fs · %d 字 · 正文输出中", thinkMs / 1000f, thinkChars)
            else -> String.format(java.util.Locale.US, "正文输出中 · 已 %d 字", bodyChars)
        }
        // 有真内容才给「点开全文 / 收起」入口；等候阶段这行点了也没东西可看，就不加这个暗示
        val affordance = if (thinkChars <= 0) "" else if (thinkExpanded) " · 收起" else " · 点开全文"
        // 头行：阶段 + 真实耗时 / 字数（单行、END 截断，读数永远优先可见）
        val newHead = head + affordance
        if (thinkHead.text != newHead) thinkHead.text = newHead
        val desc = if (thinkChars <= 0) head
            else head + "，已收到思考 " + thinkChars + " 字，" + (if (thinkExpanded) "点按收起" else "点按查看全文")
        if (thinkHead.contentDescription != desc) thinkHead.contentDescription = desc
        // 预览行：只有真收到思考才出现（等候阶段不占高度）；从 START 截断 = 看的永远是最近那段
        val preview = if (thinkChars > 0) thinkTail.toString().trim().replace('\n', ' ') else ""
        if (thinkPeek.text != preview) thinkPeek.text = preview
        // 可见性变化才会改变视口高度：那一刻跟在底部的人要贴着实际排版走，上翻的人原地不动
        // （scheduleFollowScroll 会复验 follow，排队中用户一按正文就取消）。
        // 纯文字刷新（同样的行数、同样的可见性）不碰滚动 —— 否则每 200ms 心跳都会重起一次回底动画。
        if (!passive) {
            var grew = false
            if (thinkHead.visibility != View.VISIBLE) { thinkHead.visibility = View.VISIBLE; grew = true }
            if (preview.isNotEmpty() && thinkPeek.visibility != View.VISIBLE) {
                thinkPeek.visibility = View.VISIBLE; grew = true
            }
            if (grew) scheduleFollowScroll()
        }
        if (thinkExpanded) refreshThinkFull()
        updateThinkHeight()
    }

    // ---------------- 有新内容 / 回到底部（v2.7.1）----------------
    /**
     * 用户上翻离开底部后又有新内容进来 → 露一条「↓ 有新内容」（还在流）/「↓ 回到底部」（已收口）；
     * 点它才回底并恢复跟随。判据全基于**真实排版**（[View.canScrollVertically]），不看任何进度 / 估算。
     *
     * 只有本轮真的漏看过内容（[pendingNew]，[append] 在用户不在底部时置位）才出现 ——
     * 浏览历史 / 往回读时不会平白多一条一直挂着的提示。
     */
    private fun updateMoreHint() {
        val canScroll = scroll.canScrollVertically(1)
        if (!canScroll) pendingNew = false   // 已经在底部：没什么可回去的
        val want = pendingNew && !follow && !passive && !stagingActive && state != COLLAPSED && canScroll
        val vis = if (want) View.VISIBLE else View.GONE
        if (moreBar.visibility != vis) moreBar.visibility = vis
        if (!want) return
        // 文案只在阶段真的变了时才写（滚动回调会高频调到这里）
        val live = streamRunning(stage)
        val text = if (live) "↓ 有新内容" else "↓ 回到底部"
        if (moreHint.text != text) {
            moreHint.text = text
            moreHint.contentDescription = if (live) "下面有新内容，点按回到底部并继续跟随" else "点按回到底部"
        }
    }

    /** 收掉提示条（新的一轮 / 面板关闭 / 已经回到底部） */
    private fun hideMoreBar() {
        pendingNew = false
        if (moreBar.visibility != View.GONE) moreBar.visibility = View.GONE
    }

    /** 点「↓ 有新内容」：回到底并恢复跟随（之后的新增量继续贴底） */
    private fun jumpToBottom() {
        pendingNew = false
        follow = true
        cancelFollowScroll()
        hideMoreBar()
        scroll.smoothScrollTo(0, body.height)
    }

    /** 会话行文案：页数 + 下次倒计时；暂停时只留页数与「已暂停」 */
    private fun updateScanMeta() {
        if (!scanSession) {
            scanMeta.text = ""
            if (scanMeta.visibility != View.GONE) scanMeta.visibility = View.GONE
            return
        }
        scanMeta.text = if (scanPaused) {
            "第 $scanPages 页 · 已暂停"
        } else {
            val sec = ((scanNextInMs + 999L) / 1000L).coerceAtLeast(0L)
            "第 $scanPages 页 · 下次自动识题 ${sec}s"
        }
        scanMeta.contentDescription = scanMeta.text
        val show = passive || scanReading
        scanMeta.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 把自动扫描的暂存 / 计时行状态清干净，并把内容动画收尾（alpha=1 / scale=1），reset / showText / remove 共用 */
    private fun clearScan() {
        stagingActive = false
        staging.setLength(0)
        scanComplete = false
        scanElapsedMs = 0
        scanSession = false
        scanReading = false
        scanPages = 0
        scanNextInMs = 0
        scanPaused = false
        scanLine.text = ""
        scanLine.visibility = View.GONE
        updateScanMeta()
        cancelScanSettle()
    }

    /**
     * 展示一份完整答案（单击小球重开面板时调）。**同一份答案重开保持已有阅读位置**（判定见 [keepReadPosOnShow]），
     * 新识别 / 内容真的换了才从开头读 —— 默认从头读是新答案的规则，不是所有操作永远强制回顶。
     */
    fun showText(t: String, restore: ReadPos? = null) {
        val keepPos = keepReadPosOnShow(sameText = t == raw.toString(), bodyShowsSame = raw.isNotEmpty() && !passive)
        val pos = scroll.scrollY
        val wasFollowing = follow
        reset(); raw.append(t); status.visibility = View.GONE; render()
        val saved = restore?.takeIf { it.text == t }
            ?: if (keepPos) ReadPos(t, pos, wasFollowing) else null
        if (saved == null) {
            scroll.scrollTo(0, 0)
            return
        }
        // 整棵窗口排版完成后恢复，避免新 ScrollView 尚无可滚高度时把位置夹成 0。
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            cancelReadRestore()
            if (attached && raw.toString() == saved.text) {
                follow = saved.follow
                scroll.scrollTo(0, saved.scrollY)
                updateMoreHint()
            }
            true
        }
        pendingReadRestore = listener
        root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * 小球展开「这一轮的完整原文」（自动扫描暂停后阅读）：把 lastAnswer 原样放进正文，
     * **不走** [reset] / [clearScan] —— 冻结的用时、页数、倒计时都要留在面板上。
     * 纯展示，不请求网络；阅读态要能点 / 能滚，所以先从被动层退出来。
     */
    fun showScanReading(t: String) {
        // 同一份答案重开（阅读态退出再进 / 面板重开同一文）保持已有阅读位置；刚从被动层的「只抽答案」
        // 切回全文算**首次展示**（原文虽同一份，但正文形态变了，旧 scrollY 在新排版里没意义）→ 从开头读。
        val keepPos = keepReadPosOnShow(sameText = t == raw.toString(), bodyShowsSame = raw.isNotEmpty() && !passive)
        val pos = scroll.scrollY
        scanReading = true
        if (passive) setPassive(false)   // 阅读态必须可触摸可滚动；setPassive 不会清扫描信息
        updateScanMeta()
        // 冻结的计时行：暂停阅读前服务已 freeze，abortRescan 只是切成 INVISIBLE —— 这里原样恢复。
        // 顺手把 scanComplete 立起来：阅读期间就算有迟到的计时回调，也不能把冻结的用时改掉。
        if (scanSession && scanLine.text.isNotEmpty()) {
            scanComplete = true
            scanLine.text = scanText(scanElapsedMs)
            scanLine.visibility = View.VISIBLE
        }
        closeInput(); awaitingFollow = false
        follow = false; preInputAtBottom = false; cancelFollowScroll(); bodyGestureActive = false
        main.removeCallbacks(bodySettle)
        answerDuplicated = false
        showTerms = emptyList(); activeTerm = null
        termsOpen = false; termsHeadCount = -1; termsHeadOpen = null
        termsScroll.visibility = View.GONE
        clearTermsRow()
        raw.setLength(0); raw.append(t)
        if (state == COLLAPSED) setState(HALF, persist = false)
        status.visibility = View.GONE
        render()
        scroll.scrollTo(0, if (keepPos) pos else 0)   // 重开同一答案回到原读位；新内容才回开头
    }

    /**
     * 撤回刚流出去的前 [chars] 个字（v2.2.1）。
     * 搜索开着时模型常常先吐一句「我先查一下」再去调工具，那句前言不是答案 —— 已经上屏的要清掉。
     * 只裁正文后缀，不动状态行、词块和滚动位置。
     */
    fun rewind(chars: Int) {
        if (chars <= 0) return
        if (stagingActive) return      // 自动扫描这一轮正文还在暂存里，没上屏，不用裁
        val keep = (raw.length - chars).coerceAtLeast(0)
        raw.setLength(keep)
        render()
    }
    fun append(t: String) {
        if (t.isEmpty()) return
        // 第一个**正文** token 到了 = 思考结束。顺序要紧：先把阶段切到「正文输出中」再冻结，
        // 冻结那一下写出的读数才不会还挂着「正在思考」。之后迟到的思考尾巴一律丢弃（见 [onThink]）。
        // 这一段必须在暂存分支**之前**：自动扫描轮也会先走暂存，但思考确实已经结束了。
        //
        // 只有阶段**真的变了**才写这一行：正文后续增量由 [streamTick] 每 [THINK_CLOCK_MS] 刷一次，
        // 不逐 chunk 重排两个 TextView（几十毫秒一块的 token 会把主线程打满）。
        if (stage == STREAM_WAIT || stage == STREAM_THINK) {
            stage = STREAM_BODY
            streamBar.start()
            updateThinkLine(SystemClock.elapsedRealtime())
        }
        freezeThink()
        bodyChars += t.length
        // 自动扫描这一轮：先只攒进暂存，面板上的上一轮答案原样留着（首个 token 不再抹掉旧答案）
        if (stagingActive) { staging.append(t); return }
        // 没有 requestStarted 的一轮（缓存命中 / 历史回放 / showText / 追问命中）只照常显示正文，
        // **不**凭空造一个进行态 —— 那会显示一个根本没有在跑的「正文输出中」。
        //
        // 正文只在这里进 [raw]：面板渲染、历史、复制、追问上下文、缓存写的全是它。
        // （暂存分支在上面已经 return —— 自动扫描这一轮要等 done 才原子换掉旧全文。）
        raw.append(t)
        awaitingFollow = false
        status.visibility = View.GONE
        // 用户在上面读、这一轮又在长：只把「有新内容」那条亮起来，绝不抢读位
        if (!follow) pendingNew = true
        if (renderQueued) return
        renderQueued = true
        // v2.1：排队间隔跟上一帧 render 的真实耗时走（[StreamRender]）—— 旧的固定 16ms 兜不住
        // 长答案 20ms+ 的单次重排，流式期间主线程被背靠背的 render 占满，帧帧超预算（就是「流式卡」）。
        // 收尾那次完整 render 在 done() 里立即跑，不受这个间隔影响。
        main.postDelayed({ renderQueued = false; render() }, StreamRender.intervalMs(lastRenderCostMs))
    }

    // ---------------- 联网来源（v2.3.3，排版 / 折叠判断全在 WebSources，纯 JVM 可单测）----------------

    /**
     * 设置本轮联网来源。由外部（Solver / Chat 那条链）在拿到 [WebSearch.Session] 后调用。
     *
     * 传空表等于「本轮没联网」：整块 GONE，不占位置 —— 不能因为「上次有来源」就一直挂着一个空壳。
     */
    fun setSourceHits(hits: List<WebSearch.Hit>) {
        sourceHits = hits
        sourcesOpen = false
        renderSources()
    }

    private fun toggleSources() {
        if (sourceHits.isEmpty()) return
        sourcesOpen = !sourcesOpen
        renderSources()
    }

    /** 重画来源块。折叠时只建前 [WebSources.PREVIEW] 条，展开时建全部 —— 默认状态不付全部代价 */
    private fun renderSources() {
        val hits = sourceHits
        sourcesList.removeAllViews()
        // 入口与列表的显隐统一由 [applyToolsVisibility] 算（含收起档 / 被动层）：空表就是没联网，整块不占位
        sourcesHead.text = "来源 " + hits.size
        setIcon(sourcesHead, if (sourcesOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up, 10f, Ui.TX2, right = true)
        sourcesHead.contentDescription = "联网来源 " + hits.size + " 条，" +
            (if (sourcesOpen) "已展开，点按收起" else "已收起，点按展开")
        applyToolsVisibility()
        // 折叠时先不建行（和关键词块同一个取舍：默认那条入口才是常态）
        if (!sourcesOpen) return
        for (h in hits) {
            sourcesList.addView(sourceRow(h))
        }
    }

    /** 一条来源 = 标题 / 域名 / 摘要三行；整块可点，点了打开原网页 */
    private fun sourceRow(hit: WebSearch.Hit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(6f))
            isClickable = true
        }
        col.addView(Ui.text(ctx, WebSources.label(hit), 13f, Ui.TX1).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val d = WebSources.domain(hit.url)
        if (d.isNotEmpty()) {
            col.addView(Ui.text(ctx, d, 11.5f, Ui.TX3).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val s = WebSources.summary(hit)
        if (s.isNotEmpty()) {
            col.addView(Ui.text(ctx, s, 12f, Ui.TX2).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        col.contentDescription = WebSources.label(hit)
        col.setOnClickListener { Links.open(ctx, hit.url) }
        return col
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

    /** 词块入口：点一下展开 / 收起。展开时才建 chip；收起只隐藏。换主题时就地改色（见 [applyTheme] 的 buttons 循环），重开不重建 */
    private fun toggleTerms() {
        if (showTerms.isEmpty()) return
        termsOpen = !termsOpen
        // 收着的时候 chip 是空的（或刚被清单 / 主题作废）：展开才建一次；反复开合不重复建
        if (termsOpen && termsRow.childCount == 0) { invalidateTermsRow(); refreshTermsRow() }
        applyToolsVisibility()
        updateTermsHead()
    }

    /**
     * 入口文案（关键词 N ▾ / ▴）。只在词数量或展开态真的变了时才写 —— 流式每帧都调，
     * 没变就只做一次比较，不分配字符串、不触发重排。
     */
    private fun updateTermsHead() {
        val n = showTerms.size
        if (n == termsHeadCount && termsHeadOpen == termsOpen) return
        termsHeadCount = n
        termsHeadOpen = termsOpen
        // v2.3.3：▴/▾ 是字体里的箭头，字重和基线各 ROM 不一，换成矢量 V 形折线挂在文字右侧。
        // 注意这里不再把箭头拼进 text —— 拼进去的话箭头会跟着文字一起被 ellipsize 截掉。
        termsHead.text = "关键词 $n"
        setIcon(termsHead, if (termsOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up, 10f, Ui.TX2, right = true)
        termsHead.contentDescription = "关键词 " + n + " 个，" + (if (termsOpen) "已展开，点按收起" else "已收起，点按展开")
    }

    /**
     * 按现在的 activeTerm 重画词块（点过的加粗 + 下划线，不靠颜色）。
     * 幂等：清单内容 + activeTerm 与上次构建时一致就直接返回，保留现有词块 —— 所以每帧调也只重建真正变化的那一次。
     * 收起状态只清旧 chip、记快照，不建新 chip（默认轻入口，展开时才付代价）。
     */
    private fun refreshTermsRow() {
        if (termsBuilt == showTerms && termsBuiltActive == activeTerm) return
        clearTermsRow()
        termsBuilt = showTerms
        termsBuiltActive = activeTerm
        if (!termsOpen) return
        for (term in showTerms) {
            val active = term == activeTerm
            termsRow.addView(askChip(if (active) "✓ $term" else term) { askTerm(term) }.apply {
                if (active) { setTypeface(typeface, Typeface.BOLD); paint.isUnderlineText = true }
                // v1.65 读屏：词块点开的是一条解释分支 —— 状态和后果都说清楚
                contentDescription = (if (active) "正在解释" else "解释") + term + "，点按在正文后面展开大白话解释"
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5f) })
        }
    }

    /** 清掉现有词块，并把 [buttons] 里的引用一并摘掉（reset / 清单换了时用；不建新 chip） */
    private fun clearTermsRow() {
        for (i in 0 until termsRow.childCount) buttons.remove(termsRow.getChildAt(i))
        termsRow.removeAllViews()
        invalidateTermsRow()
    }

    /** 作废词块快照：下一次 [refreshTermsRow] 无视「没变」判断、强制重建（清理 / 展开时用） */
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
        val t0 = android.os.SystemClock.elapsedRealtime()
        // 被动层（自动扫描）：屏幕上只准出现「抽出来的答案」+ 扫描信息，解析 / 关键词一律不进正文。
        if (passive) { renderPassive(); lastRenderCostMs = android.os.SystemClock.elapsedRealtime() - t0; return }
        val src = raw.toString()
        // v2.1：一遍拆完（顶部答案 / 正文 / 第一轮 / 是否重复）。原来这里要跑三遍全文拆分
        // （forPanel 两遍 + 为 answerDuplicated 再 splitFollowUps + split 一遍），流式时每帧都是白费。
        val p = AnswerText.panel(src)
        answerDuplicated = p.answerDuplicated
        // Terms.parse 已经把清单行整行剥掉了（含流式途中只吐到一半的标记行，见其 v1.89 说明）——
        // 直接用它给的正文，不再自己扫第二遍（原来那个 stripTermsMeta 是重复解析）。
        val (shown, terms) = Terms.parse(p.body)
        // 这一帧没有关键词：收回到默认的「一条入口」状态，下一次出现词时也是收起的（用户展开过的状态只在同一组词里保留）
        if (terms.isEmpty()) termsOpen = false
        showTerms = terms
        answerLine.text = Md.inline(p.ans, Ui.theme.codeBg)
        updateAnswerLineVisibility()
        body.text = Md.render(shown, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
        // 先写入口文案再定显隐：入口刚出现的那一帧就带着正确的「关键词 N」
        updateTermsHead()
        applyToolsVisibility()
        refreshTermsRow()
        // 只有用户在跟随时才往下贴；排队的那次滚动执行时还会再看一眼 follow（用户中途往上读就不滚）
        scheduleFollowScroll()
        // 排版刚变过：「有新内容」那条要按**新的**可滚距离重判（内容变高之后才可能出现「下面还有」）
        updateMoreHint()
        lastRenderCostMs = android.os.SystemClock.elapsedRealtime() - t0
    }

    /**
     * 被动层渲染：只显示 [AnswerText.scanAnswers] 抽出的答案（多题 / 重复「## 答案」/ 旧粗体都保留），
     * 没有可靠结构就显示一句「点小球查看」，**绝不**拿整段解析兜底。关键词、答案重复行都不出现。
     * 高度仍由 [PASSIVE_BODY_MAX_DP] 限住；完整原文由小球展开（[showScanReading]）看。
     */
    private fun renderPassive() {
        showTerms = emptyList()
        termsOpen = false; termsHeadCount = -1; termsHeadOpen = null
        termsScroll.visibility = View.GONE
        applyToolsVisibility()
        answerLine.text = ""
        answerLine.visibility = View.GONE
        val extracted = AnswerText.scanAnswers(raw.toString())
        // 关键词清单行属于元数据：被动层不显示词块，也不能让这一行混进答案正文
        val ans = if (extracted.isEmpty()) "" else Terms.parse(extracted).first.trim()
        body.text = if (ans.isEmpty()) {
            if (raw.isEmpty()) "" else "未提取到明确答案，点小球查看"
        } else {
            Md.render(ans, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
        }
    }

    private fun updateAnswerLineVisibility() {
        // 被动层的答案已经整段放在正文里（只抽答案），顶部这行不再重复
        if (passive) { answerLine.visibility = View.GONE; return }
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
        endThink()   // done 是这一轮的收口点：思考行在这里一定冻结（没吐过思考时是空操作）
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

/**
 * 流式进行态：一条 2dp 高的细线，按**时间**推进一小段——不是百分比，也不假装知道还剩多少。
 *
 * 为什么用「来回扫的一段」而不是一条越来越满的进度条：这次要吐多少字、要不要先联网 / 调工具，
 * 客户端根本不知道。画一条填到 80% 就停住的条，等于凭空编了一个进度（用户会盯着它等）。
 * 这里只表达「还在跑」，跑完（[stop]）立刻整条收掉，不留半截。
 *
 * 颜色只从当前主题取（[Ui.TX3] 淡轨 + [Brut.MINT] 信号色），不写死薄荷绿：
 * 瑞士风格的信号红、深色主题的浅墨色都跟着 [Ui.theme] 走。
 */
class StreamBarView(ctx: Context) : View(ctx) {
    /** 一段扫过整条轨道要多久；与动画时长一致，掉帧也不会变速 */
    private val periodMs = 1100f
    private var running = false
    private var startedAt = 0L
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()

    fun start() {
        if (running) return
        running = true
        startedAt = android.os.SystemClock.elapsedRealtime()
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        invalidate()
    }

    fun stop() {
        running = false
        if (visibility != View.GONE) visibility = View.GONE
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (!running) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 轨道：一条极淡的底线，说明「这里是进行态」，不是内容
        paint.color = Ui.TX3
        paint.alpha = 0x30
        canvas.drawRect(0f, 0f, w, h, paint)
        // 扫过的一段：宽度固定为轨道的 22%，位置按时间循环
        val track = w * 0.22f
        val t = ((android.os.SystemClock.elapsedRealtime() - startedAt) % periodMs.toLong()) / periodMs
        val x = (w + track) * t - track
        paint.color = Brut.MINT
        paint.alpha = 0xFF
        rect.set(x.coerceAtLeast(0f), 0f, (x + track).coerceAtMost(w), h)
        canvas.drawRect(rect, paint)
        // 下一帧：只在跑着的时候排（停了不留空转）
        postInvalidateOnAnimation()
    }
}

/**
 * 瑞士风输入区底：透明 + 一条贴底的细线（不画圆角色块）。
 *
 * 为什么单独写一个 drawable 而不是拿 `LayerDrawable + inset` 拼：那种拼法要按测量到的高度反算 inset，
 * 每次布局都会变，且 `Paint` 的线宽在不同密度下会被四舍五入得不齐。这里在 [draw] 里直接量
 * `bounds` 画一条线：无论高度怎么变，线永远贴着底、粗细就是给的像素数。
 */
class UnderlineBg(private val strokePx: Int, private val color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.color = color
        canvas.drawRect(b.left.toFloat(), (b.bottom - strokePx).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    /** 半透明：这一层只画一条线，其余像素必须透出面板底 */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
