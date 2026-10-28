package com.chun.lingzhu

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：悬浮球 + 答案面板 + 长按菜单 + 自动扫描。
 * 手势对照桌面版：双击 = 识别，单击 = 开/关面板（看上一次结果），长按 = 菜单，拖动 = 挪位置（松手吸边）。
 */
class OrbService : Service() {

    companion object {
        /** 截屏前等小球消失的默认时长：2 帧 */
        private const val DEFAULT_HIDE_DELAY_MS = 32

        const val ACTION_START = "com.chun.lingzhu.START"
        const val ACTION_STOP = "com.chun.lingzhu.STOP"
        const val EXTRA_SOLVE_AFTER = "solveAfter"
        const val ACTION_TOGGLE_HIDE = "com.chun.lingzhu.TOGGLE_HIDE"   // 通知栏「隐藏 / 显示小球」
        /** 本进程里正在跑的服务（无障碍连上时通知它换通道用） */
        @Volatile private var self: OrbService? = null
        fun onA11yReady() { self?.let { s -> s.main.post { s.switchToA11y() } } }
        const val EXTRA_REVIVE = "revive"   // 谁把小球带回来的（无障碍守护 / 开机 / 升级后）
        const val ACTION_WORD = "com.chun.lingzhu.WORD"   // 马上来一个单词（菜单 / adb 测试）
        const val ACTION_WORD_TICK = "com.chun.lingzhu.WORD_TICK"   // 单词推送主时钟（WordAlarm 精确闹钟）到点拉起服务弹词
        const val ACTION_KNOWLEDGE = "com.chun.lingzhu.KNOWLEDGE"   // 马上来一张知识卡（设置页「试弹一张」）
        const val ACTION_KNOWLEDGE_TICK = "com.chun.lingzhu.KNOWLEDGE_TICK"   // 知识卡弹窗主时钟（KnowledgeAlarm 精确闹钟）到点拉起服务弹卡
        const val ACTION_TRANSLATE_CLIP = "com.chun.lingzhu.TRANSLATE_CLIP"   // 翻译刚复制的（菜单 / 通知 / adb 测试）
        const val ACTION_REGION = "com.chun.lingzhu.REGION"   // v1.64 框选识别（菜单 / 快捷开关 / 桌面快捷方式）
        const val ACTION_NOTE = "com.chun.lingzhu.NOTE"   // v2.4 截图速记（菜单 / adb 验证）
        const val ACTION_SOLVE = "com.chun.lingzhu.SOLVE"   // 识别屏幕：快捷方式 / 快捷开关（v1.64），也给 adb 测试用
        const val ACTION_REFRESH = "com.chun.lingzhu.REFRESH"   // v1.67 自定义中心改完设置让小球/面板立即重刷（CustomizeActivity 发）
        /** v1.70：点「自动扫描已暂停」那条通知 → 继续扫描 */
        const val ACTION_RESUME_SCAN = "com.chun.lingzhu.RESUME_SCAN"
        /** v1.80：试弹一条待办（[TodoNotify] 到点时先走这条；设置页「试弹一条」也走它） */
        const val ACTION_TRY_POPUP = "com.chun.lingzhu.TRY_POPUP"
        const val EXTRA_TODO_ID = "todoId"
        const val EXTRA_TODO_TEXT = "todoText"
        const val EXTRA_TODO_DUE = "todoDue"

        /**
         * 让正在跑的球「试弹」一条待办（v1.80）。
         * 返回 false = 没弹成（球没在跑 / 没有悬浮窗权限）→ 调用方直接发常规通知，提醒不能因为试弹失败而丢。
         * 用 [Service.startService] 而不是 startForegroundService：只有球已经在跑时才可能走到这儿，
         * 不涉及后台启动前台服务的限制。
         */
        fun tryPopTodo(ctx: Context, t: Todo.Item): Boolean {
            if (!running || !android.provider.Settings.canDrawOverlays(ctx)) return false
            return try {
                ctx.startService(Intent(ctx, OrbService::class.java).setAction(ACTION_TRY_POPUP)
                    .putExtra(EXTRA_TODO_ID, t.id).putExtra(EXTRA_TODO_TEXT, t.text).putExtra(EXTRA_TODO_DUE, t.dueAt))
                true
            } catch (_: Exception) { false }
        }

        /**
         * 前台卡片页（[TranslateActivity] 这种 Activity 窗口）打开期间，把它上面的 service 浮窗临时收起来。
         *
         * v2.7：`TYPE_APPLICATION_OVERLAY` 永远盖在 Activity 之上 —— 译文卡贴底也没用，答案面板 / 菜单
         * 一展开就压住它。以前靠「把卡片放低一点」避让，是布局上的错觉。
         *
         * 契约（调用方不用关心内部实现）：
         *  - **成对**调用：[suppressForForeground] 在 Activity onCreate 之后，[restoreAfterForeground] 在 onDestroy；
         *  - 可嵌套（两个 Activity 同时压着时，最后一个 restore 才真的恢复）；
         *  - 服务没在跑 → 什么都不做（**绝不** startService 把球拉起来，也绝不创建浮窗）；
         *  - **小球不动**，在途识别不取消，面板正文 / 阅读位置原样保留（只是窗口暂时不可见、不可触摸）。
         */
        fun suppressForForeground(ctx: Context) {
            self?.let { s -> s.main.post { s.setForegroundOwner(ctx, true) } }
        }

        /**
         * [suppressForForeground] 的配对释放。未知 / 已释放的 ctx 再调一次是安全空操作，
         * 服务已经没了同样是空操作 —— 记成幂等，Activity 重建也不会把计数打歪。
         */
        fun restoreAfterForeground(ctx: Context) {
            self?.let { s -> s.main.post { s.setForegroundOwner(ctx, false) } }
        }
        const val EXTRA_DELAY = "delay"   // 等中转页 / 下拉栏收干净再截（毫秒）
        /** 按下小球时缩到这么大（v1.78 起由 [Anim.Spring.press] 驱动，可打断） */
        private const val PRESS_SCALE = 0.92f
        const val EXTRA_IMAGE_FILE = "imageFile"   // v1.63：识别这张图（分享 / 相册），ShareActivity 传来
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "orb"
        private const val NOTI_ID = 1
        private const val CHANNEL_ALERT = "alert"
        private const val NOTI_ALERT_ID = 2
        /**
         * 缩边（orbTuck）时球留在屏幕里的比例：占**缩放后宽度**的 40%。
         * 默认 idleScale 60 时露出约 0.24w（旧实现露 0.3w），换来的是任何刻度下触点都不会变成 0 像素。
         */
        private const val EXPOSED_FRACTION = 0.4f
        /** 自动扫描「已用时」刷新间隔：250–500ms 区间取中，够顺滑又不费电（单调时钟计时见 [scanElapsedTick]） */
        private const val SCAN_ELAPSED_MS = 350L
        /**
         * 专注守护的采样周期（v2.3.4）：12 秒独立一拍。
         *
         * 为什么不复用自动扫描的节拍：那条路每轮都要截屏 + 调接口（用户可以在设置里改成更慢甚至关掉），
         * 而专注守护无论走 APP 模式还是屏幕模式都必须是**它自己的**节奏，且关掉自动扫描时依然成立。
         * 12 秒这个数沿用 [FocusGuard.Cfg.roundMs] 的口径：够密到「刚离开 5 分钟」能及时说起，
         * 又稀到一块电池耗得起（APP 模式只读一次 Binder，屏幕模式只解一张缩图）。
         */
        private const val FOCUS_TICK_MS = 12_000L
        /**
         * 瞬态层的记号（[topVisibleLayer] 的返回值）：同一时刻只有一个「前层」。
         * 新开一层先把前一层藏起来（不可见 + 不可触摸），关掉再把它恢复回原样。
         */
        private const val LAYER_NONE = 0
        private const val LAYER_PANEL = 1
        private const val LAYER_MENU = 2
        private const val LAYER_GATE = 3
        private const val LAYER_NOTE = 4
        @Volatile var running = false
            private set
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var capture: Capture? = null

    private var orb: OrbView? = null
    /**
     * 小球的弹簧动画（v1.78）：入场 / 按下 / 松手回弹 / 闲置缩边 全走同一条 ——
     * 连按两下、或者缩边缩到一半又去碰它，都是「带着当时的速度接着走」，
     * 不会像原来那样 `animate().cancel()` 之后从当前值重新起步（速度归零 = 看着一顿）。
     */
    private var orbMotion: AnimGlue.SpringAnimation? = null
    /** 小球窗口 x 的动效适配（见 [OrbShift]）：和 [orbMotion] 一起建、一起废 */
    private var orbShift: OrbShift? = null
    private lateinit var orbLp: WindowManager.LayoutParams
    private var panel: AnswerPanel? = null
    private var panelReadPos: AnswerPanel.ReadPos? = null
    /** 小球是否正在接收一次触摸（DOWN 到 UP 之间）。orbToFront 据此决定同步重排还是等这次手势结束（P0-2） */
    private var orbTouchDown = false
    /** 手势进行中收到的「把小球重新挂一遍」请求：等这次触摸 UP 之后再执行 */
    private var pendingOrbToFront = false
    private var menu: View? = null
    /** 菜单的弹簧动画（菜单每次重开都新建一个视图，所以动画器也跟着换） */
    private var menuMotion: AnimGlue.SpringAnimation? = null

    /** 菜单窗口的布局参数（[syncLayers] 要按「是不是前层」切 FLAG_NOT_TOUCHABLE）与它**原始**的 flag 组合 */
    private var menuLp: WindowManager.LayoutParams? = null
    private var menuBaseFlags = 0
    /**
     * 层的打开次序（单调递增，谁大谁在前）。只有 4 个固定层，所以不做通用窗口栈：
     * 每开一层 +1 记在那一层头上，关掉清零；[topVisibleLayer] 取「还开着的里面记号最大的」。
     * 这样次序就是**真实打开先后**，而不是写死的优先级。
     */
    private var layerSeq = 0L
    private var panelLayerAt = 0L
    private var menuLayerAt = 0L
    private var gateLayerAt = 0L
    private var noteLayerAt = 0L

    /** 某一层**刚刚**打开：拿一个更大的记号，从此它压住之前开着的每一层 */
    private fun markLayerOpened(layer: Int) {
        val at = ++layerSeq
        when (layer) {
            LAYER_PANEL -> panelLayerAt = at
            LAYER_MENU -> menuLayerAt = at
            LAYER_GATE -> gateLayerAt = at
            LAYER_NOTE -> noteLayerAt = at
        }
    }

    /** 某一层关掉了：记号清零（窗口对象由各自的 close / remove 摘掉） */
    private fun markLayerClosed(layer: Int) {
        when (layer) {
            LAYER_PANEL -> panelLayerAt = 0L
            LAYER_MENU -> menuLayerAt = 0L
            LAYER_GATE -> gateLayerAt = 0L
            LAYER_NOTE -> noteLayerAt = 0L
        }
    }

    /** 真正的新用户那张悬浮新手卡（独立 TYPE_APPLICATION_OVERLAY 窗口；null = 没显示） */
    private var orbGuide: View? = null
    private var orbGuideLp: WindowManager.LayoutParams? = null
    /** 等小球入场动画走完再弹（addView 之后延时；onDestroy / 隐藏时 removeCallbacks） */
    private val orbGuideShow = Runnable { maybeShowOrbGuide() }

    private val a11ySource = A11ySource { main.post(it) }
    private var solving = false
    private var cancel = Cancel()
    /**
     * 双击截屏后「先问再发」的小浮窗（v2.6，null = 没在等用户选）。
     * 等用户这段时间**不算** [solving]（不算网络忙态、不占 150 秒看门狗），也绝不先自动答一遍再补充。
     */
    private var questionGate: CaptureQuestionPanel? = null
    /** 等用户选问题前截到的那张图（base64）：只跟 [questionGate] 一起存在，收尾时一并丢掉 */
    private var gateShot: String? = null

    // ---------------- 浮层分层（v2.7）----------------

    /**
     * 前台 Activity（译文卡等）压着的那几个 Context。集合里非空 = 所有 service 浮窗都让路。
     *
     * 用「谁压着」的集合而不是裸计数：同一个 Activity 重复 suppress 幂等，restore 未知 ctx 安全，
     * Activity 重建也不会把计数打歪。**不用 WeakHashMap 自动失效** —— GC 掉的 key 会让界面永远
     * 停在「被压住」的状态；成对的 onCreate / onDestroy 才是唯一释放路径（见 [setForegroundOwner]）。
     */
    private val foregroundOwners = HashSet<Context>()
    /**
     * 正在为截屏藏浮窗（见 [setOverlaysHidden]）。与 [foregroundOwners] 分开记：
     * 一个是「用户在前面看别的页面」，一个是「我们自己要截一张干净的屏」，恢复条件不同。
     */
    private var captureHidden = false
    /**
     * 双击时还没截屏权限、先去弹「共享屏幕」框：授权回来续接的那趟要**沿用这次选的那一档**
     * （见 [OrbMenu.TAPS]），而不是重新读一遍设置 —— 用户选的是「这一下双击做什么」。
     * null = 没有待续接的双击。
     */
    private var tapAfterAuth: String? = null
    private var autoScan = false
    /** 在途的这一轮是不是自动扫描轮：显式关扫描只掐自动轮（[stopAutoScan]），手动双击 / 框选那一轮不动 */
    @Volatile private var activeAutoRound = false
    // ---------------- 自动扫描省钱（v1.70）----------------
    /** 上一轮的画面轻量指纹 + 连续几轮没变化（纯逻辑在 AutoScanPolicy） */
    private var scanStill = AutoScanPolicy.Still()
    /** 下一轮扫描的截止时刻（**单调时钟** SystemClock.elapsedRealtime），状态行与会话行都按它算真实剩余 */
    private var nextScanAt = 0L
    /** 本次自动扫描会话已经成功识别过的新画面数（0 起；纯状态与判据见 [AutoScanPolicy.Pages]） */
    private var scanPages = AutoScanPolicy.Pages()
    /** 因画面没变化被自动暂停的（这时点通知 / 双击小球 = 继续，而不是重新开一轮） */
    private var scanPausedByStill = false
    /** 息屏 / 锁屏时暂停的：解锁自动恢复 */
    private var scanSuspendedByScreen = false
    /**
     * v1.86 自动扫描的**连败**状态与「因为连败自己停了」。
     * 被动模式下面板没有状态行、窗口还不可触摸，所以「截屏失败 / 接口失败 / 授权被收回」写了也永远看不见 ——
     * 这就是用户报的「自动扫描看起来像没反应」。判据和文案都在 [AutoScanPolicy]（纯函数、有单测）。
     */
    private var scanFails = AutoScanPolicy.Fails()
    private var scanPausedByFail = false
    // ---------------- 专注守护（v2.3.2，第 9 条 / v2.3.4 手机版）----------------
    /**
     * 「离开学习」的进行态。判据、分档文案全在 [FocusGuard]（纯函数、有单测），这里只存状态。
     *
     * v2.3.4 起由**独立 12 秒节拍**（[focusTick]）推进，不再搭自动扫描的顺风车：
     * 自动扫描是「把屏幕发给模型识别题目」，专注守护是「本机判断这一屏 / 这个应用算不算学习」，
     * 目的、通道、隐私边界都不一样，而且 APP 模式根本不看屏幕内容。
     * 只在主线程读写（worker 的结果一律 [main] 回投），所以不需要锁。
     */
    private var focusLeave = FocusGuard.Leave()
    private val focus by lazy { FocusSettings(this) }
    /**
     * 采样代次。**每个让它自增的动作都意味着「之前那些在途结果作废」**：
     * 关开关、切模式、改白名单、熄屏、服务销毁、设置刷新。worker / 截屏回调回来时代次不符就地丢弃 ——
     * 否则「切到 APP 模式之前那一次屏幕采样」会拿 APP 模式的文案去提醒用户，那是纯冤枉。
     */
    private var focusGen = 0L
    /** 独立节拍是否已排（[Handler] 没有 hasCallbacks，只能自己记一笔） */
    private var focusArmed = false
    /** 上一轮采样还在飞：这一拍跳过，别把 Binder / 截屏通道压满 */
    private var focusBusy = false
    /** SCREEN 模式通道不可用 / APP 模式没权限：只提示一次（每 12 秒刷一条没人看，也变成打扰） */
    private var focusChannelWarned = false
    private var focusNoAccess = false
    /** 陪伴（v2.3.4）：洗牌袋（不连续重复）与气泡窗口。都在主线程碰 */
    private var focusBag: FocusCompanion.Bag? = null
    /** 专注提醒与陪伴**共用**这一个气泡（null = 没显示）：一个出口，绝不叠两个 */
    private var focusBubble: View? = null
    private val focusBubbleTimeout = Runnable { closeFocusBubble() }
    /**
     * 「阅读暂停」（v1.89 用户要求）：自动扫描进行中单击小球 → 暂停扫描、把上一轮完整答案展开成可读的活动面板。
     * 与 [scanPausedByStill]（画面没变）/ [scanPausedByFail]（连败自停）**彼此独立** —— 那两种是扫描自己停的，
     * 这种是用户在看答案；收起 / 划掉面板即恢复（前提是同一轮自动扫描还开着）。明确停球 / 授权退出 / 服务销毁会清掉它。
     */
    private var readingPaused = false
    private val scanCountdown = object : Runnable {
        override fun run() {
            // 阅读暂停：倒计时不该再写任何东西（一下就把「已暂停自动扫描」那行冲掉；暂停时本就已 removeCallbacks）
            if (readingPaused) return
            if (!autoScan) {
                // 自己停下来的两种原因：把可点的说明一直留在面板上，用户不点通知也不会错过
                val p = panel
                if (p != null && p.attached && !solving) {
                    if (scanPausedByFail) p.setStatus(AutoScanPolicy.failPausedStatus(scanFails), clickable = true) { resumeScan() }
                    else if (scanPausedByStill) p.setStatus(AutoScanPolicy.stillPausedStatus(scanStill.rounds), clickable = true) { resumeScan() }
                }
                syncScanSession()   // 自停时保留页数、显示「已暂停」
                return
            }
            val p = panel
            // 正在识别时状态行归识别流程管，不抢；v1.86 「破例亮着的失败行」也不抢（不然失败原因只活 1 秒）。
            // 自动扫描「已用时」是面板的**专属行**（setScanElapsed），与这里的状态行互不覆盖。
            if (!solving && p != null && p.attached && !p.peeking) {
                p.setStatus(AutoScanPolicy.status(autoScan, scanPausedByStill, scanStill.rounds,
                    AutoScanPolicy.remaining(nextScanAt, SystemClock.elapsedRealtime())))
            }
            // 会话行每秒都按单调时钟重算一次真实剩余（含「正在识别」的轮次，不拿过期时刻糊弄）
            syncScanSession()
            main.postDelayed(this, 1000L)
        }
    }
    /** scanStill 只在 worker 上改，但 startAutoScan / 恢复会在主线程改它 —— 加锁，别把轮数算乱 */
    private val scanLock = Any()
    /** 最近一次识别 / 追问后的全文（单击小球重开面板时显示）；进程重启后从历史文件里找回（v1.63） */
    private var lastAnswer: String? = null
    private fun lastAnswer(): String? = lastAnswer ?: store.list().firstOrNull { it.kind == History.KIND_SOLVE }?.also { convHistoryId = it.id }?.text
    /** 最近一次识别的对话（截图 + 回答 + 追问），面板上的追问接着它问；对应 lastAnswer */
    private var conv: Solver.Conversation? = null
    /** 这次识别在历史记录里的 id：追问答完把整段（含追问）写回同一条 */
    private var convHistoryId = -1L
    private val store by lazy { History.Store(this) }
    private val solveCache by lazy { Cache.solveStore(this) }
    // 设置页改配色 → 小球和开着的面板立刻换色；改「紧贴屏幕边」→ 小球立刻挪位置（SharedPreferences 只弱引用监听器，必须留成员）
    private val themeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 模式也带不透明度（v1.58）：切模式 / 改模式透明度都要重算配色
        if (key == "themeId" || key?.startsWith("themeC_") == true || key == "mode" || key == "customModes" || key?.startsWith("modeOpacity_") == true
            || key == "followSystemDark") main.post {   // v1.67：跟随系统深浅色的开关也算配色改动
            Ui.applyTheme(prefs); panel?.applyTheme(); orb?.invalidate()
            // 配色变了：开着的词卡没法就地换皮，收掉即可（不评分、不联网、不碰学习记录），下次按新配色重弹
            if (key == "themeId" || key?.startsWith("themeC_") == true || key == "followSystemDark") {
                if (wordCard.showing) wordCard.hide()
                if (knowledgeCard.showing) knowledgeCard.hide()   // 知识卡同理：配色变了就收掉，不评分
            }
        }
        if (key == "mode" || key == "customModes" || key == "detail" || key == "novice") main.post { panel?.setModeLabel(modeLabel()); panel?.refreshModes() }
        if (key == "orbFar" || key == "orbTuck") main.post { applyEdgeMode() }
        if (key?.startsWith("panel") == true && key != "panelState") main.post { panel?.relayout(); panel?.refreshModes() }
        if (key == "orbSizeDp") main.post { applyOrbSize() }
        if (key == "textSp") main.post { panel?.applyTextSize() }   // v1.66：字号在设置页调，面板开着也立刻变
        if (key == "orbIdleAlpha") main.post { if (docked) orb?.alpha = prefs.orbIdleAlpha / 100f }
        if (key == "orbHidden") main.post { applyHidden() }
        if (key == "wordOn" || key == "wordEverySec") main.post { scheduleWord() }
        if (key == "knowledgePopupOn" || key == "knowledgePopupEverySec") main.post { scheduleKnowledge() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        // v1.77：内置词库开箱可选，弹词热路径要它们的正文 —— 后台预热，别让第一次弹卡在主线程读 assets
        Thread { try { WordBank.warm(applicationContext) } catch (_: Exception) {} }.start()
        QuickActivity.publishShortcuts(this)   // v1.64：升级后开机 / 守护拉起服务时也发布一次桌面快捷方式
        Ui.applyTheme(prefs)
        lastSystemDark = prefs.systemDark()   // v1.67：记住当前系统深浅色，转屏/切深色时才知道变了没
        prefs.orbHidden = false   // 每次启动都先露出来（隐藏只管这一次运行）
        prefs.onChange(themeListener)
        WatchdogReceiver.kick(this)   // v1.65：服务在跑就保证看门狗闹钟排着（进程被清后的兜底层）
        // v2.5：保活第六层（周期 Job）在这里做一次幂等补排：服务已经在跑、用户也要着（关过球再启动时会走到这），
        // 就把「上次没排上 / 被系统清掉」的那条补回来。不在这里新建 = 不改变「建球成功才排」的口径（见 onStartCommand）。
        if (prefs.orbWanted) ReviveJob.schedule(this)
        // v1.85：服务每次起来都把「最近一条待办 + 简报 + 复习」的闹钟重排一遍。
        // 闹钟能跨进程存活，但**不跨重启**，而且用户可能在我们没察觉的时候清过数据 —— 这里重排是幂等的
        // （同一条 PendingIntent 用 FLAG_UPDATE_CURRENT 覆盖），成本一次几十微秒，换来「提醒一定排着」。
        try { TodoReceiver.kick(this) } catch (_: Exception) {}
        registerScreenReceiver()   // v1.70：息屏 / 解锁 —— 自动扫描跟着停、跟着起（运行时注册，onDestroy 注销，不留残留）
        running = true
        self = this
        OrbTile.refresh(this)
        // 专注守护（v2.3.4）：用户开着的话，服务一活就把 12 秒节拍排上 —— 不等自动扫描、不等用户点任何东西。
        // 陪伴同理由这里排第一次：否则「刚启动服务」这一档要等到第一次解锁才可能冒泡。
        if (focus.enabled) startFocus()
        if (focus.companionEnabled) scheduleCompanion(first = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v1.62：用户点「退出」才真的不要了；其余命令都保持 START_STICKY —— 返回值以最后一次为准，
        // 以前这些分支回 NOT_STICKY，点一次通知按钮之后进程被杀就再也起不来。
        // 菜单「停止小球」与通知里那条「停止小球」共用这一条正常停服务路径（[stopOrb]）。
        if (intent?.action == ACTION_STOP) { stopOrb(); return START_NOT_STICKY }
        // 球已经在跑：直接弹。球还没建（冷启动点「弹单词」）：**不在这里早退**，落到下面建小球那段，
        // 等小球窗口真正加好之后再弹 —— 于是调用方只要发一次 ACTION_WORD，不用靠固定延时猜冷启动耗时。
        if (intent?.action == ACTION_WORD && orb != null) { main.post { showWord(force = true) }; return START_STICKY }
        if (intent?.action == ACTION_WORD_TICK && orb != null) { main.post { showWord(force = false) }; return START_STICKY }
        if (intent?.action == ACTION_KNOWLEDGE && orb != null) { main.post { showKnowledge(force = true) }; return START_STICKY }
        if (intent?.action == ACTION_KNOWLEDGE_TICK && orb != null) { main.post { showKnowledge(force = false) }; return START_STICKY }
        val wordAfterStart = intent?.action == ACTION_WORD
        val wordTickAfterStart = intent?.action == ACTION_WORD_TICK
        val knowAfterStart = intent?.action == ACTION_KNOWLEDGE
        val knowTickAfterStart = intent?.action == ACTION_KNOWLEDGE_TICK
        if (intent?.action == ACTION_TOGGLE_HIDE) { prefs.orbHidden = !prefs.orbHidden; return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY }
        if (intent?.action == ACTION_TRANSLATE_CLIP) { main.post { translateClipboard() }; return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY }
        // v1.70：点「自动扫描已暂停」那条通知 = 继续扫
        if (intent?.action == ACTION_RESUME_SCAN) { main.post { resumeScan() }; return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY }
        // v1.80 试弹：待办到点先浮一张小卡（TodoNotify → tryPopTodo 发过来）
        if (intent?.action == ACTION_TRY_POPUP) {
            val id = intent.getLongExtra(EXTRA_TODO_ID, 0L)
            val title = intent.getStringExtra(EXTRA_TODO_TEXT).orEmpty()
            val due = intent.getLongExtra(EXTRA_TODO_DUE, System.currentTimeMillis())
            main.post { showTryPop(id, title, due) }
            return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY
        }
        // v1.67 自定义中心改完回来：重读 prefs 刷小球和面板（同一套逻辑见 themeListener / applyOrbSize）
        if (intent?.action == ACTION_REFRESH) {
            main.post {
                Ui.applyTheme(prefs); panel?.applyTheme(); applyOrbSize(); orb?.invalidate()
            if (wordCard.showing) wordCard.hide()   // 配色变了：开着的词卡收掉重弹（不评分、不联网）
            if (knowledgeCard.showing) knowledgeCard.hide()   // 知识卡同理
            }
            return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY
        }
        // v2.3.4 专注守护设置改了（[FocusSettings.refresh] 发过来）：就地应用 ——
        // 只重排采样节拍 / 陪伴定时器、丢掉在途结果。**不**申请截屏授权、**不**开自动扫描、**不**改浮层。
        if (intent?.action == FocusSettings.ACTION_REFRESH) {
            main.post {
                // panels / 球的配色跟它无关，这里只碰专注那几件事
                applyFocusSettings()
            }
            return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY
        }
        val delay = intent?.getLongExtra(EXTRA_DELAY, 0L) ?: 0L
        if (intent?.action == ACTION_REGION && orb != null) { main.postDelayed({ solveRegion() }, delay); return START_STICKY }
        if (intent?.action == ACTION_NOTE && orb != null) { main.postDelayed({ startQuickNote() }, delay); return START_STICKY }
        if (intent?.action == ACTION_SOLVE && orb != null) { main.postDelayed({ solve(intent.getBooleanExtra("auto", false)) }, delay); return START_STICKY }
        // 进程被系统杀掉后按 START_STICKY 重启：intent 是 null。用户之前点了退出就别回来
        if (intent == null && !prefs.orbWanted) { stopSelf(); return START_NOT_STICKY }
        // v1.63 保活可见：小球是被自动带回来的就记一笔（设置页「后台保活」里显示次数和最近一次）
        if (orb == null) (if (intent == null) "系统重启服务" else intent.getStringExtra(EXTRA_REVIVE))?.let { prefs.noteRevive(it) }
        prefs.orbWanted = true
        val solveAfter = intent?.getBooleanExtra(EXTRA_SOLVE_AFTER, false) == true
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data != null) {
            // 录屏：必须在 getMediaProjection 之前拉起 mediaProjection 类型前台（Android 14 规定）。
            // 无障碍模式不走这里 —— 没有录屏令牌时用 mediaProjection 类型起前台会被系统抛 SecurityException；
            // 那时进程由系统绑定的无障碍服务保活，不需要前台通知，退出走菜单「停止小球（保留授权）」
            goForeground()
            capture?.release()
            capture = try {
                Capture(this, code, data) { main.post { onCaptureStopped() } }
            } catch (e: Exception) {
                toast("录屏授权失败：" + (e.message ?: e.javaClass.simpleName)); null
            }
        }
        if (orb == null) {
            // v1.63：悬浮窗权限被收回后，系统按 STICKY 拉起服务 → 加小球抛异常 → 崩 → 再拉起，循环。先查，没有就发通知说清楚再退出
            if (!android.provider.Settings.canDrawOverlays(this) || !addOrbSafely()) {
                // 手动点了「弹单词」却连球都建不起来：除通知外再给一句就地反馈，别让用户以为点了没反应
                if (wordAfterStart) toast("悬浮球起不来（多半是悬浮窗权限），弹不了单词")
                overlayLost(); return START_NOT_STICKY
            }
            try { getSystemService(NotificationManager::class.java).cancel(NOTI_ALERT_ID) } catch (_: Exception) {}
            scheduleWord()
            scheduleKnowledge()
            // 小球窗口已经加好：冷启动带来的「弹单词」这时才弹，确定性地落在建球之后（不再靠延时猜时机）
            if (wordAfterStart) main.post { showWord(force = true) }
            if (wordTickAfterStart) main.post { showWord(force = false) }   // 冷启动：等球建好再弹，非 force，走全部抑制判定
            // 知识卡同理（独立于弹单词）：冷启动点「试弹一张」落在建球之后
            if (knowAfterStart) main.post { showKnowledge(force = true) }
            if (knowTickAfterStart) main.post { showKnowledge(force = false) }
            // 新手卡：小球窗口 addView 已成功，再等入场动画露出才弹；addView 失败走不到这里，也就不会标 seen
            if (prefs.orbGuidePending && !prefs.orbGuideSeen) {
                main.removeCallbacks(orbGuideShow)
                main.postDelayed(orbGuideShow, 420L)
            }
        }
        intent?.getStringExtra(EXTRA_IMAGE_FILE)?.let { f -> main.postDelayed({ solveImageFile(f) }, 250) }
        // [M2M3] v1.67：服务活着就保证三件事——随手记常驻通知在、早晚简报闹钟排上、
        // （服务被杀重启后这三样靠这里恢复；Memo.post / BriefingReceiver.kick 都是幂等的）
        try { MemoNotify.post(this) } catch (_: Exception) {}
        try { BriefingReceiver.kick(this) } catch (_: Exception) {}
        try { ReviewReceiver.kick(this) } catch (_: Exception) {}   // 审查A：默认开的复习提醒也要有人排闹钟
        // v2.5：小球真挂出来了（走到这里 = addOrbSafely 成功、orbWanted 已置 true）才排周期巡检 Job。
        // 排在这里而不是 onCreate：onCreate 时小球还没建，权限缺失 / 建球失败（overlayLost）就不该留下一条空转的 Job。
        if (orb != null) ReviveJob.schedule(this)
        // 小球没开着时从快捷方式 / 快捷开关进来：先起小球，再识别 / 框选
        if (intent?.action == ACTION_REGION) main.postDelayed({ solveRegion() }, 600)
        if (intent?.action == ACTION_NOTE) main.postDelayed({ startQuickNote() }, 600)
        if (intent?.action == ACTION_SOLVE) main.postDelayed({ solve(auto = false) }, 600)
        // 从「共享屏幕」框回来：等系统的框完全消失（别把它截进去）再接着识别
        if (solveAfter && capture != null) {
            // 双击时因没截屏权限转了趟授权页：回来后仍要**按当初选的那一档**接着做
            // （这次选择只在这一趟续接里消费掉；null = 不是双击来的，走老路径）
            val tap = tapAfterAuth
            tapAfterAuth = null
            main.postDelayed({ if (tap == null) solve(auto = false) else solveTap(tap) }, 700)
        }
        // v1.62：无障碍模式以前只发普通通知、不算前台 —— 系统清内存时小球说没就没。现在也挂前台（specialUse 类型）
        // 等小球窗口真正显示出来再挂前台：Android 14 起「有可见悬浮窗」才算豁免后台起前台（系统按 STICKY 重启时就靠这个）
        if (!foreground) main.postDelayed({ if (!foreground && running) keepAliveForeground() }, 300)
        return START_STICKY
    }

    override fun onDestroy() {
        // 速记链路最先关门：本实例从此不再弹层 / 恢复浮窗（在飞的截图回调回来看 [quickNoteDestroyed] 就地回收图）。
        // close() 幂等且自己回收浮层持有的那张图（releaseShot），这里只负责把窗口摘掉 + 放引用。
        quickNoteDestroyed = true
        try { quickNote?.close() } catch (_: Exception) {}
        quickNote = null
        hideOrbGuide()   // 独立窗口必须在这里摘掉，不能留给系统回收（否则泄漏 Window）
        try { wordCard.hide() } catch (_: Exception) {}
        try { knowledgeCard.hide() } catch (_: Exception) {}
        closeTryPop(escalate = true)   // v1.80：服务没了，试弹卡不能留在屏幕上；没接住的补通知
        unregisterScreenReceiver()   // v1.70：先注销，别让 receiver 回调打进一个正在销毁的服务
        try { tts?.shutdown() } catch (_: Exception) {}; tts = null
        running = false
        // 单词推送闹钟只在**用户明确停球**（orbWanted=false）时撤：WordAlarm.schedule 在「服务没在跑」时走的是
        // 取消分支（现有可用 API），这里 running 已置 false。
        // ⚠️ 不能无条件撤 —— 这个 onDestroy 对**任何**服务销毁都跑（系统清理 / 无障碍守护重拉 / 升级重启…），
        // 那些情况用户还想开着球，撤了会连弹词时钟一起停摆（它下一拍本该把服务带回来），必须留着。
        // 也不放进 stopOrb：那儿 running 还是 true，schedule 会**重新排一拍**而不是取消，等于没撤。
        if (!prefs.orbWanted) { try { WordAlarm.schedule(this) } catch (_: Exception) {} }
        // 知识卡弹窗时钟同理：只在**用户明确停球**时撤（显式 cancel；scheduleIn 此刻也会因 running=false 走取消分支，
        // 这里写 cancel 更直白）。系统清理 / 无障碍守护重拉 / 升级重启这些销毁路径要留着它，下一拍才能把服务带回来。
        if (!prefs.orbWanted) { try { KnowledgeAlarm.cancel(this) } catch (_: Exception) {} }
        // 看门狗闹钟由 stopOrb 通过 WatchdogReceiver.cancel 撤（这里不撤，理由同上：别误撤系统重拉后的兜底）。
        if (self === this) self = null
        foreground = false
        prefs.offChange(themeListener)
        getSystemService(NotificationManager::class.java).cancel(NOTI_ID)
        OrbTile.refresh(this)
        stopAutoScan()
        main.removeCallbacks(scanCountdown)   // v1.70：倒计时别在服务没了以后还跑
        main.removeCallbacks(scanElapsedTick)   // v1.89：自动扫描「已用时」计时器同理，服务没了必须摘干净
        scanRoundStart = 0L
        // 专注守护（v2.3.4）：节拍 / 陪伴定时器 / 气泡全摘干净。**不**清已记下的报告，
        // 也不改设置 —— 服务被系统重拉回来后按设置重新开始采样（离开段从新时间起算）。
        focusGen++
        stopFocus()
        cancel.abort()
        capture?.release(); capture = null
        dropQuestionGate(); closeMenu(); panel?.remove(); panel = null
        // 分层状态归零：服务重建后是一张白纸（残留的「被压住」会让新面板永远不露面）
        foregroundOwners.clear(); captureHidden = false
        // 分层记号也一起归零（窗口都没了；残留记号会让重建后的新层算错前后次序）
        layerSeq = 0L; panelLayerAt = 0L; menuLayerAt = 0L; gateLayerAt = 0L; noteLayerAt = 0L
        menuLp = null; menuBaseFlags = 0
        regionView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; regionView = null
        orb?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        orb = null
        orbMotion = null   // 动画器握着视图；视图没了必须一起放掉
        orbShift = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------
    private var foreground = false

    /** v1.58：通知里加「隐藏小球 / 显示小球」—— 打游戏时下拉状态栏点一下就收起来 */
    private fun buildNotification(restricted: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮球", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        // restricted：保活受限的兜底通知。前台服务起不来是「受限设置 / 后台限制」，不是没开通知 ——
        // 所以点它去 [PermissionActivity]（里面能看到受限设置与各项开关），文案也不说「开通知能解决」，只如实说前台服务受限。
        val open = if (restricted)
            PendingIntent.getActivity(this, 6, PermissionActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        else
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OrbService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getService(this, 2, Intent(this, OrbService::class.java).setAction(ACTION_TOGGLE_HIDE), PendingIntent.FLAG_IMMUTABLE)
        // v1.61：通知里一键「翻译刚复制的」（翻译卡片是个 Activity，拿到焦点才能读剪贴板）
        val trans = PendingIntent.getActivity(this, 3, TranslateActivity.clipboardIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_orb)
        val hidden = prefs.orbHidden
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle(if (restricted) Preset.appName + " 前台服务受限" else if (hidden) Preset.appName + " 小球已隐藏" else Preset.appName + " 在运行")
            .setContentText(if (restricted) "点此查看后台设置" else if (hidden) "点「显示小球」恢复" else "双击小球识别屏幕 · 长按打开菜单")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "翻译刚复制的", trans).build())
            .addAction(Notification.Action.Builder(icon, if (hidden) "显示小球" else "隐藏小球", hide).build())
            .addAction(Notification.Action.Builder(icon, "停止小球", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTI_ID, n)
        foreground = true
    }

    /**
     * 没有录屏令牌时（无障碍截屏）的前台保活（v1.62）：
     *  - Android 14+：specialUse 类型（mediaProjection 类型没令牌会抛 SecurityException）；
     *  - Android 10~13：不带类型（这些版本不查录屏令牌）；
     * 起不来（极少数 ROM 限制后台起前台）就退回一条**受限通知**（不假装保活成功，见下面的 catch），
     * 至少「隐藏 / 翻译 / 停止小球」按钮还在，并能点进权限页处理。
     */
    private fun keepAliveForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTI_ID, n)
            foreground = true
        } catch (e: Exception) {
            // 起不了前台（后台受限 / ROM 拦截）：绝不把普通通知当成「保活成功」——
            // 记一条 warning，并把常驻通知文案改成「前台服务受限」，点它去 PermissionActivity（受限设置 / 后台限制都在这）。
            // 不谎称「开通知」能解决前台失败 —— 这是后台限制，不是通知权限。
            android.util.Log.w("LingOrb", "keepAliveForeground 没挂上前台：后台保活受限，退回受限通知", e)
            postNotification(restricted = true)
        }
    }

    /**
     * 正常停服务：菜单里「停止小球」和通知里那条「停止小球」共用这一条路径（[ACTION_STOP] 也调它）。
     *
     * 先把 [Prefs.orbWanted] 置 false —— 这是看门狗、无障碍守护、弹词闹钟、开机自启**共同的复活门槛**，
     * 置 false 之后它们都不会再把小球拉回来（idle guard 依旧在跑，但只会拿到 false 空转，见 A11yService.revive）；
     * 再 stopSelf() → 系统按 START_NOT_STICKY 不再拉起。
     *
     * 关键：**不再 disableSelf 无障碍、不杀进程、不 finishAndRemove 全部 Activity**。
     *  - 不关无障碍 = 系统绑定保留、截屏授权保留 → 下次手动启动无需重新授权（旧的 disableSelf 会把授权一起搞丢）；
     *  - 不杀进程   = 不再触发系统重新绑定无障碍而把小球唤醒（旧的 killProcess 会这样）。
     * 小球 / 答案面板 / 自动扫描 / 录屏 / 试弹卡 / 常驻通知的清理全部交给 [onDestroy]（原样复用，不在这里重做）。
     *
     * 明确停球额外撤掉**只属于小球保活**的几条：[WatchdogReceiver.cancel]（看门狗）、[ReviveJob.cancel]
     * （周期巡检 Job）与 [onDestroy] 里带 orbWanted 门槛的 [WordAlarm]（弹词时钟）。**不碰**待办 / 早晚简报 /
     * 复习提醒（[TodoReceiver] / [BriefingReceiver] / [ReviewReceiver]）—— 那几条提醒与小球开着与否无关，误清会把用户的提醒搞丢。
     */
    private fun stopOrb() {
        prefs.orbWanted = false
        // 用户明确停球：撤掉看门狗闹钟，别再留一个没意义的闹钟空转（退避档位/计数不动）。
        WatchdogReceiver.cancel(this)
        // 「停止小球」也要停知识卡弹窗时钟（独立于弹单词）：这里是显式 cancel —— 此刻 running 仍为 true，
        // 调 schedule 会重排而不是撤（同 [WordAlarm] 在 onDestroy 里的取舍）。
        KnowledgeAlarm.cancel(this)
        // v2.5：周期巡检 Job 同理 —— 球都不想要了，别留一条每 15 分钟空转的 Job（它醒来也只会拿到 orbWanted=false）。
        ReviveJob.cancel(this)
        stopSelf()
    }

    /** 没开通知权限时 notify 会被系统静默丢掉 —— 那就只能靠快捷开关（OrbTile）或菜单 */
    private fun addOrbSafely(): Boolean = try { addOrb(); true } catch (e: Exception) {
        orb?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; orb = null; orbMotion = null; orbShift = null; false
    }

    /** 小球显示不出来（悬浮窗权限被关了）：别崩溃循环，发一条能点的通知，然后停掉 */
    private fun overlayLost() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTI_ALERT_ID, Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(Preset.appName + " 的小球显示不出来")
                .setContentText("「显示在其他应用上层」权限被关了 · 点这里重新打开")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
        // 开机 / 无障碍守护是用 startForegroundService 拉起的：不挂一下前台就停，Android 12+ 会判超时让进程崩 —— 挂上立刻撤掉
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTI_ID, buildNotification())
        } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        foreground = false
        ReviveJob.cancel(this)   // v2.5：悬浮窗权限没了，复活门槛 [KeepAlivePlan.shouldRevive] 永远不过 —— 不留空转的 Job（下次建球成功会重排）
        stopSelf()
    }

    /** [restricted] = 保活受限时的兜底通知（见 [keepAliveForeground] 的 catch）：文案说清后台受限，点它去权限页 */
    private fun postNotification(restricted: Boolean = false) {
        try { getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification(restricted)) } catch (_: Exception) {}
    }

    // ---------------- 随机弹词（v1.62）----------------
    /** v1.85.6：评分落库专用单线程（读改写串行） */
    private val wordIo = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "lz-wordio").apply { isDaemon = true } }
    /**
     * v1.85.7：「刚弹过」的 key → 时刻（[Words.key]，跨词库）。只在内存里，只在弹/评卡时动，纳秒级。
     *
     * 修「点认识后同一个词短时间内反复弹出」：选卡时靠它把刚弹过的排除掉（见 [WordRecent.pick]），
     * **不依赖评分有没有落库成功**，所以后台 `wordIo` 那边的异步写不会再造成「读到旧状态 → 又选它」。
     */
    private val wordRecent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val wordCard by lazy {
        WordCard(this, wm,
            // v1.85 三档记忆反馈：左滑不认识 / 下滑模糊 / 右滑认识 —— 同时更新 wordStats 计数与 FSRS（WordSrs）。
            // 上滑（CLOSE）只关闭、不评分，这里不写任何记录（卡片自己走退场；「认识才 hide」的行为不在这里，不去碰）。
            onJudge = { deck, card, grade ->
                val now = System.currentTimeMillis()
                val k = Words.key(deck, card)
                // v1.85.7：先在内存里记一笔「刚弹过」（同步、极轻）。必须在 wordIo 排队之前 ——
                // 评分落库是异步的，而下一次选卡（hide() 完就可能来）不能等它，否则读到的还是旧状态，
                // 同一个词会被立刻再选一遍。CLOSE（只关闭不评分）同样要记：它也是「刚弹过」。
                WordRecent.mark(wordRecent, k, now)
                if (grade != SwipeJudge.Grade.CLOSE) {
                    // v1.85.6：词频 / FSRS 是全表 JSON 读写，词表大了以后主线程做=每次评分卡几十~几百 ms。
                    // 挪到单线程串行后台（FSRS 读改写必须串行，不能并发）；按钮反馈 / 收卡动画不等它。
                    wordIo.execute {
                        try {
                            val st = prefs.wordStats.toMutableMap()
                            st[k] = Words.mark(st[k], grade, now)
                            prefs.wordStats = st
                            prefs.wordToday = Words.countToday(prefs.wordToday, prefs.todayInt(), grade)
                            WordSrs.grade(prefs, k, grade, now)   // 接进真正的复习系统（自己的 prefs 键）
                        } catch (e: Exception) {
                            android.util.Log.e("LingWord", "评分落库失败", e)
                        }
                    }
                }
            },
            onSpeak = { w -> speak(w) },
            cachedDetail = { deck, w -> WordPretranslateStore(prefs).lookup(deck, w, System.currentTimeMillis()) },
            fetchDetail = { deck, w, done -> fetchWordDetail(deck, w, done) },
            stayMs = { prefs.wordCardStaySec * 1000L })
    }

    // ---------------- 知识卡定时弹窗（独立于弹单词；见 KnowledgeAlarm / KnowledgePopup）----------------
    private val knowledgeStore by lazy { KnowledgeCard.Store(this) }
    /** 「刚弹过」的知识卡 id → 时刻（[KnowledgePopupPolicy]，只在内存里）。避免同一张立刻重复。 */
    private val knowledgeRecent = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val knowledgeCard by lazy {
        KnowledgePopup(this, wm,
            // 四档评分：揭示之后才可能触发。先记「刚弹过」（避免立刻重复），再写回**同一个** Store 的 FSRS。
            onRate = { id, rating ->
                val now = System.currentTimeMillis()
                KnowledgePopupPolicy.mark(knowledgeRecent, id, now)
                try { knowledgeStore.answer(id, rating, now) } catch (e: Exception) { android.util.Log.e("LingKnow", "评分落库失败", e) }
            },
            // 关闭：只收起、不评分（不写学习记录）；记一笔「刚弹过」，免得下一次立刻又是这张。
            onClose = { id -> KnowledgePopupPolicy.mark(knowledgeRecent, id, System.currentTimeMillis()) },
            // 稍后：同样不评分；把下一拍提前到 1 分钟后（闹钟门槛不变：关着 / 停了就不排）。
            onSnooze = { id ->
                KnowledgePopupPolicy.mark(knowledgeRecent, id, System.currentTimeMillis())
                KnowledgeAlarm.scheduleIn(this, KnowledgeAlarm.SNOOZE_MS)
            },
            stayMs = { prefs.wordCardStaySec * 1000L })
    }
    // 发音（v1.63）：系统 TextToSpeech，第一次用时才初始化；引擎没就绪 / 不支持英语返回 false
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var ttsPending: String? = null
    private fun speak(w: String): Boolean {
        val t = tts
        if (t == null) {
            ttsPending = w
            tts = android.speech.tts.TextToSpeech(this) { st ->
                ttsReady = st == android.speech.tts.TextToSpeech.SUCCESS &&
                    (tts?.setLanguage(java.util.Locale.US) ?: -2) >= android.speech.tts.TextToSpeech.LANG_AVAILABLE
                android.util.Log.i("LingWord", "tts init status=$st ready=$ttsReady")
                ttsPending?.let { p -> ttsPending = null; if (ttsReady) doSpeak(p) }
            }
            return true
        }
        if (!ttsReady) return false
        doSpeak(w); return true
    }
    private fun doSpeak(w: String) {
        // v1.63：朗读答案要念中文 —— 每次按内容选语言（单词卡还是英文）
        tts?.language = if (AnswerText.isMostlyChinese(w)) java.util.Locale.CHINA else java.util.Locale.US
        val r = tts?.speak(w, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "w")
        android.util.Log.i("LingWord", "tts speak len=${w.length} result=$r")   // P2-9：不打用户文本原文
    }

    /**
     * 架构变更（2026-10-14）：单词推送主时钟从 Handler.postDelayed 自续期改为 WordAlarm 精确闹钟
     * （AlarmManager + WordReceiver 自续订，同待办 / 简报 / 复习）。postDelayed 在服务被杀 /
     * Doze 下停摆且错过不补；现在每拍闹钟把服务拉起来，抑制判定不变，被抑制也照排下一拍。
     */
    private fun scheduleWord() { WordAlarm.schedule(this) }
    /**
     * force = 菜单里「来个单词」：只要有卡就弹。定时弹时先看时机：锁屏、夜里、小球隐藏（打游戏）、
     * 正在识别 / 自动扫描、答案面板或菜单开着，都跳过这一轮（不打断正事）。
     */
    private fun showWord(force: Boolean) {
        // v1.85 免打扰：静音期间一律不弹（「全部静音 = 只更新历史，不挡屏」）——
        // 连菜单里「弹单词」刚打开时顺手弹的那一下也一起压住（开关本身照常翻转）。
        // 手动点的补一句说明，免得用户以为点了没反应（免打扰逻辑本身不改）。
        if (prefs.mutedNow()) { if (force) toast("现在是静音时段：先关掉「全部静音」再弹单词"); return }
        if (orb == null || wordCard.showing || knowledgeCard.showing) return
        // 前台卡片页压着 / 正在为截屏藏浮窗：这时冒一张词卡就会盖在别人（或截出来的画面）上面
        if (layersSuppressed()) return
        if (!force) {
            if (!prefs.wordOn || prefs.orbHidden || solving || autoScan || menu != null) return
            if (panel?.attached == true) return
            if (!getSystemService(android.os.PowerManager::class.java).isInteractive) return
            if (prefs.wordQuiet && Words.quiet(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), 23, 7)) return
        }
        val cards = prefs.activeCards()
        if (cards.isEmpty()) { if (force) toast("没有选中的词库：设置 → 背单词 → 词库 里勾一个"); return }
        val now = System.currentTimeMillis()
        val st = prefs.wordStats
        // v1.85 选卡口径：优先弹「到期的」（WordSrs 里已有记录、被 Fsrs 判定到期的那些）；没有到期的再退回原来的
        // Words.pick 加权随机。见 WordSrs.pickDue —— 没记录的新词不算到期，免得几千个新词把加权挑卡挤没。
        // v1.85.7：整段收进 WordRecent.pick —— 先排掉「刚弹过」（含刚评完分的）的卡，再走上面这套口径。
        // 之前这里直接 pickDue/pick：候选缩到「到期的那一小撮」后常常权重全为 0（刚出过的都是 0 权重），
        // 于是走 Words.pick 的 total<=0 退路变纯随机，把刚评完的词立刻又选出来 —— 用户连着看到三次同一个词。
        val picked = WordRecent.pick(cards, WordSrs.all(prefs), st, wordRecent, now)
        if (picked == null) { if (force) toast("这次没挑出单词卡，再点一次试试"); return }
        val (deck, card0) = picked
        prefetchWords()   // v1.67：后台预取下一批的译文（不阻塞选卡）
        // v1.67 起预翻译命中就弹详细排版释义；v1.78 修掉「译文被丢掉」：内置 5 级词库的卡只有单词、没有背面，
        // 原来只把 lookup 当「有没有」用、译文本身扔了，再对空 back 做 prettify（空 back 是原样返回的），
        // 结果预翻译明明早就翻好，卡片上却一个字都没有，只能去点「查」联网 —— 现在译文真的排进卡片。
        val card = Words.detail(card0, WordPretranslateStore(prefs).lookup(deck, card0.front, now))
        // v1.76.6：只要名字就别全量解析 —— deckList() 不 parse 正文；弹卡是热路径，5 级词库开箱后
        // allDecks() 每次都把近万行翻一遍（虽有解析缓存，仍白建几千个 Card 对象）
        val name = prefs.deckList().firstOrNull { it.id == deck }?.name ?: ""
        val t = Words.todayOf(prefs.wordToday, prefs.todayInt())
        try {
            wordCard.show(name, deck, card, st[Words.key(deck, card)], if (t.done > 0) "今天第 ${t.done + 1} 个" else "今天第 1 个", autoSpeak = prefs.wordSpeak)
        } catch (e: Exception) {
            // 词卡窗口没弹出来（个别 ROM 限制 / 视图异常）：手动点的如实反馈，别静默
            android.util.Log.e("LingWord", "单词卡弹出失败", e)
            if (force) toast("单词卡没弹出来：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        // v1.85.7：这次「弹过」也记进 wordRecent（CLOSE / 划到一半收回 / 自动隐藏都算弹过）——
        // 它和 onJudge 里那次 mark 一起，保证「刚露过脸」的卡在 WINDOW_MS 内不会被立刻再选。
        WordRecent.mark(wordRecent, Words.key(deck, card), now)
        // v1.85.7：把「弹过」也挪进 wordIo 串行写。原来这里是主线程拿 showWord 开头读到的整张 st 直接写回，
        // 而后台 wordIo 同时在读-改-写同一张表 —— 两边都是「读全表→改一个 key→写全表」，没有跨线程串行，
        // 后写的会覆盖先写的，评分（streak/FSRS）可能整个丢掉，卡的权重就永远降不下来、反复被选。
        // 挪进同一条队列后读-改-写全程串行；大 JSON 也顺带不在主线程做了（v1.85.6 的初衷）。
        wordIo.execute {
            try {
                val s2 = prefs.wordStats.toMutableMap()
                val sk = Words.key(deck, card)
                s2[sk] = Words.shown(s2[sk], now)
                prefs.wordStats = s2
            } catch (e: Exception) {
                android.util.Log.e("LingWord", "记录弹出时间失败", e)
            }
        }
    }

    /**
     * 知识卡弹窗时钟（独立于 [scheduleWord]）：服务起来 / 开关或间隔改了都重排一拍。
     * 是否真排由 [KnowledgeAlarm] 自己按 `knowledgePopupOn && orbWanted` 判定。
     */
    private fun scheduleKnowledge() { KnowledgeAlarm.schedule(this) }

    /**
     * 弹一张知识卡。force = 设置页「试弹一张」（有可弹的就弹）；
     * 定时弹则先过 [KnowledgePopupPolicy.Gate]：关着 / 用户点过退出 / 静音 / 静音时段 / 锁屏（屏幕没亮或处于
     * 锁屏界面）/ 隐藏球 / 正忙（识别 / 自动扫描）/ 答案面板开着 / 菜单开着 —— 任一命中就跳过这一拍；
     * 过门后再过一遍共用弹出池（[PopupCenter.allow]，归「学习」类），被每日 / 类别上限或类别静音挡下就不弹。
     * 手动试弹不占额度，但同样守静音与免打扰时段。
     *
     * 与其它浮层**互斥**（两个方向都守）：词卡在屏 / 面板开着 / 菜单开着时不弹；
     * 反过来 [showWord] 也会因 `knowledgeCard.showing` 跳过。选卡口径见 [KnowledgePopupPolicy.pick]。
     */
    private fun showKnowledge(force: Boolean) {
        if (orb == null || knowledgeCard.showing) return
        // 浮层互斥：无论是否 force 都不叠着弹（force 时给一句说明，别让用户以为点了没反应）
        if (wordCard.showing || panel?.attached == true || menu != null || questionGate != null || quickNote != null || layersSuppressed()) {
            if (force) toast("先收起当前浮层（词卡 / 面板 / 菜单）再试弹知识卡")
            return
        }
        val now = System.currentTimeMillis()
        val quietNow = KnowledgePopupPolicy.quietNow(
            PopupPolicy.minuteOfDay(now), prefs.popupQuietOn, prefs.popupQuietStart, prefs.popupQuietEnd,
        )
        if (force) {
            // 手动试弹也不越纪律：静音 / 免打扰时段照样不弹（给一句说明，免得以为点了没反应）
            if (prefs.mutedNow()) { toast("现在是静音时段：先关掉「全部静音」再弹知识卡"); return }
            if (quietNow) { toast("现在是免打扰时段：先关掉 / 调开时段再弹知识卡"); return }
        } else {
            val gate = KnowledgePopupPolicy.Gate(
                on = prefs.knowledgePopupOn,
                orbWanted = prefs.orbWanted,
                muted = prefs.mutedNow(),
                orbHidden = prefs.orbHidden,
                busy = solving || autoScan,
                panelOpen = panel?.attached == true,
                menuOpen = menu != null,
                // 亮屏且没处于锁屏界面才弹：锁屏上弹了也没人看得见，白白消耗一次额度
                interactive = (getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: false) &&
                    !(getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: false),
                quiet = quietNow,
            )
            if (!KnowledgePopupPolicy.shouldPop(gate)) return
        }
        val decks = prefs.knowledgePopupDecks
        // 空集合 = 显式「不弹任何牌组」（不是「全部」）—— 与弹单词的空=全部口径刻意不同，避免误弹
        if (decks.isEmpty()) { if (force) toast("先在设置里勾选要弹的知识卡牌组"); return }
        val picked = KnowledgePopupPolicy.pick(
            knowledgeStore.list().filter { it.deck in decks }, knowledgeRecent, now,
        )
        if (picked == null) { if (force) toast("选中的牌组里没有可弹的知识卡（都还没到期或刚弹过）"); return }
        // 定时弹要和「主动提醒」共用同一个弹出池（每日上限 / 类别上限 / 类别静音 / 免打扰）：
        // 复习归入「学习」类，放行（顺带记额度）才弹，被挡就这一拍不弹。手动试弹不占额度。
        if (!force &&
            !PopupCenter.allow(this, PopupPolicy.Event("knowledge-due-${picked.id}", PopupPolicy.CAT_STUDY, picked.front))
        ) return
        try {
            knowledgeCard.show(picked, picked.deck)
        } catch (e: Exception) {
            android.util.Log.e("LingKnow", "知识卡弹出失败", e)
            if (force) toast("知识卡没弹出来：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        KnowledgePopupPolicy.mark(knowledgeRecent, picked.id, now)
    }

    // v1.67：背单词预翻译（子线程一轮最多 20 个；无网 / 失败冷却在 WordPretranslate 内部处理，静默降级）
    private fun prefetchWords() {
        Thread {
            try {
                WordPretranslateStore(prefs).prefetch(prefs.activeCards(), prefs.deepl(), isOnlineForFetch(), System.currentTimeMillis())
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * 查词（v1.80）：**模仿电脑版的悬停词典**。
     *
     * 顺序：本地解读缓存 → 模型（词典提示词；没结果再用最短那句兜一次）→ 老的 DeepL 预翻译。
     * 最后那条退路是安卓独有的：离线 / 没配接口 / 模型不听话时，点「查」也得有东西给出来，
     * 不能因为学电脑版就把原来的兜底丢了。查不到会记 60 秒，免得反复点反复打接口。
     */
    private fun fetchWordDetail(deck: String, word: String, done: (String?) -> Unit) {
        Thread {
            val w = WordDict.normalize(word)
            val store = WordDict.Store(this)
            val now = System.currentTimeMillis()
            store.get(w)?.let { main.post { done(it) }; return@Thread }
            if (WordDict.isWord(word) && prefs.hasApi() && !store.missedRecently(w, now)) {
                val cfg = prefs.api()
                var out = askWord(cfg, WordDict.prompt(w), WordDict.MAX_TOKENS)
                if (out.isNullOrBlank()) out = askWord(cfg, WordDict.fallbackPrompt(w), WordDict.MAX_TOKENS_FALLBACK)
                if (!out.isNullOrBlank()) {
                    val text = WordDict.tidy(out, w)
                    store.put(w, text)
                    main.post { done(text) }
                    return@Thread
                }
                store.markMiss(w, now)
            }
            val tr = try {
                val ps = WordPretranslateStore(prefs)
                ps.prefetch(listOf(deck to Words.Card(word, "")), prefs.deepl(), isOnlineForFetch(), System.currentTimeMillis())
                ps.lookup(deck, word, System.currentTimeMillis())
            } catch (_: Exception) { null }
            main.post { done(tr) }
        }.start()
    }

    /**
     * 一次非流式取词（电脑版 `wordAsk` 同款）：关思考、拿 `choices[0].message.content`。
     * 失败一律 null（由调用方决定退哪条路），异常不往外冒。
     * 关思考这条很要紧：思考模式下预算被思维链吃光，正文一个字不剩 —— 电脑版踩过这个坑。
     */
    private fun askWord(cfg: ApiConfig, prompt: String, maxTokens: Int): String? = try {
        if (cfg.base.isEmpty()) null else {
            val body = org.json.JSONObject()
                .put("model", cfg.model)
                .put("stream", false)
                .put("max_tokens", maxTokens)
                .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", prompt)))
            Bodies.thinking(cfg, true, body)
            val conn = (java.net.URL(cfg.base + "/chat/completions").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 8_000; readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) null else {
                    val txt = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    org.json.JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                        ?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }
                }
            } finally { conn.disconnect() }
        }
    } catch (_: Exception) { null }

    private fun translateClipboard() {
        try { startActivity(TranslateActivity.clipboardIntent(this)) } catch (_: Exception) {}
    }

    // ---------------- 隐藏小球（v1.58，打游戏时）----------------
    private fun applyHidden() {
        val v = orb ?: return
        val hidden = prefs.orbHidden
        if (hidden) {
            hideOrbGuide()   // 球藏起来了，提示卡不能还留在屏幕上
            closeMenu(); stopAutoScan(); cancelHold()
            dropQuestionGate()   // 提问闸门也是浮窗：球藏起来就得一起收，否则它会一直挂着等一张再也不会来的选择
            // P0-1：隐藏小球也是「关面板」的一种 —— 同样先掐断在途识别，否则 solving 不复位、恢复小球后双击没反应
            panelReadPos = panel?.readPosSnapshot()
            cancelCurrentIfSolving()
            // 面板直接关掉：只调透明度的话，透明的面板还会挡住游戏的触摸
            panel?.let { if (it.attached) it.fadeOutAndRemove() }; panel = null
            markLayerClosed(LAYER_PANEL)
            syncLayers()   // 球藏起来了：把「谁是前层」重算一遍（剩下还开着的层照常露出）
            v.visibility = View.GONE
        } else {
            v.visibility = View.VISIBLE
            docked = true; undock(); scheduleDock()
        }
        postNotification()
        OrbTile.refresh(this)
    }

    /**
     * v1.62：无障碍截屏就绪后，不再需要录屏授权 —— 停掉录屏（系统的投屏提示、状态栏图标一起消失），
     * 前台类型换成 specialUse。正在识别时先不动，等这一轮结束下次再换。
     */
    private fun switchToA11y() {
        if (!a11ySource.ready) return
        if (capture != null && !solving && !autoScan) {
            capture?.release(); capture = null
            foreground = false
            keepAliveForeground()
        }
    }

    private fun onCaptureStopped() {
        capture = null
        if (a11ySource.ready) return   // 还有无障碍通道，录屏没了也能继续
        stopAutoScan()
        panel?.let { if (it.attached) it.setStatus("录屏授权已结束（系统收回了）· 点这里改用无障碍或重新授权", clickable = true) }
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------------- 小球 ----------------
    // 长按（v1.57）：以前靠 GestureDetector 的长按，按下时小球从缩边位置弹回来、窗口在手指下挪了半个球，
    // 它按「手指移动了」处理，长按被拖到 1~2 秒甚至不触发。现在自己计时：按住 0.12 秒开始画进度圈，
    // 再 0.38 秒圈满 → 震一下 + 弹菜单。GestureDetector 只管单击 / 双击，喂屏幕坐标（窗口挪了也不算移动）。
    private val HOLD_DELAY = 120L
    private val HOLD_FILL get() = (prefs.orbHoldMs - HOLD_DELAY).coerceAtLeast(130L)   // v1.68 P1-1：长按总时长跟自定义（默认 500ms = 120 起手 + 380 注满）
    private var holdAnim: ValueAnimator? = null
    private var holdFired = false
    private val holdStart = Runnable {
        val v = orb ?: return@Runnable
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = HOLD_FILL
            addUpdateListener { v.hold = it.animatedValue as Float }
            start()
        }
        main.postDelayed(holdFire, HOLD_FILL)
    }
    private val holdFire = Runnable {
        val v = orb ?: return@Runnable
        if (!orbTouchDown || dragging) return@Runnable   // 这一轮已被取消 / 拖动作废
        holdFired = true
        holdAnim?.cancel(); holdAnim = null; v.hold = 0f
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showMenu()
    }
    private fun cancelHold() {
        main.removeCallbacks(holdStart); main.removeCallbacks(holdFire)
        holdAnim?.cancel(); holdAnim = null
        orb?.hold = 0f
    }

    /**
     * v1.63 读屏（TalkBack）：小球的双击 / 长按是自己认的手势，读屏模式下点不到 ——
     * 给它名字，双击（= 点击）开关面板，另挂「识别屏幕 / 打开菜单 / 翻译刚复制的」三个操作（读屏里上下滑选）。
     */
    private fun orbA11y(v: View) {
        v.contentDescription = Preset.appName + " 小球"
        v.isFocusable = true
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, "打开或关闭答案面板"))
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.a11y_solve, "识别屏幕"))
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.a11y_menu, "打开菜单"))
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.a11y_translate_clip, "翻译刚复制的"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> { orbTap(); true }
                R.id.a11y_solve -> { solve(auto = false); true }
                R.id.a11y_menu -> { showMenu(); true }
                R.id.a11y_translate_clip -> { translateClipboard(); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOrb() {
        val size = Ui.dp(this, prefs.orbSizeDp.toFloat())
        val v = OrbView(this)
        orb = v
        orbLp = overlayParams(size, size)
        val dm = resources.displayMetrics
        // 首次启动（没存过位置）：贴左边 + 竖直正中对齐。
        // 竖直位置先按窗内坐标给近似值，落帧后由 [centerOrbDefault] 用真实窗口原点修正到屏幕正中。
        // 存过位置就照存的原样，绝不覆盖用户手动摆的位置。
        val saved = prefs.orbPosSaved
        orbLp.x = sideX(if (saved) onLeftAt(prefs.orbX, lastScreenW()) else true)
        orbLp.y = clampY(if (saved) prefs.orbY else (dm.heightPixels - orbLp.height) / 2)
        orbA11y(v)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { orbTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelHold()
                // v1.70：因画面没变化被自动暂停的 —— 双击小球当作「继续」，不用去翻通知
                if (scanPausedByStill) { scanPausedByStill = false; main.post { resumeScan() } }
                solveTap(prefs.orbDoubleTap); return true
            }
        }).apply { setIsLongpressEnabled(false) }
        fun feed(ev: MotionEvent, action: Int = ev.action) {
            val e = MotionEvent.obtain(ev)
            e.setLocation(ev.rawX, ev.rawY)
            e.action = action
            gd.onTouchEvent(e); e.recycle()
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    orbTouchDown = true
                    if (orbGuide != null) hideOrbGuide()   // 一碰球就收起提示，别挡拖动 / 菜单
                    dragging = false; holdFired = false; downX = ev.rawX; downY = ev.rawY; startX = orbLp.x; startY = orbLp.y
                    // 老式 Animator 若还挂在这个视图上（历史代码路径），先掐掉 —— 两套动画抢同一批属性最难看
                    view.animate().cancel()
                    // 按住期间冻结窗口位置和缩放，避免缩边展开抢走正在进行的手势。
                    main.removeCallbacks(dockRunnable)
                    // 取消上一段（缩边 / 贴边 / 回弹都可能正在动 x）：基准接回当前窗口坐标后就地落定，
                    // 于是 tx = 0 就是手指按下的这一刻的位置。拖动期间再没有别的动画写 orbLp.x。
                    // 若被掐的是一段没走完的吸边，记下来：松手若是「点一下」（没拖），还要把它接上，
                    // 否则球会停在半路（旧实现靠一条**取消不掉**的动画硬把它写完，那是 bug 不是设计）。
                    snapCut = orbMotion?.isRunning == true
                    stopOrbMotion()
                    cancelHold(); main.postDelayed(holdStart, HOLD_DELAY)
                    feed(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        orbLp.x = startX + (ev.rawX - downX).toInt(); orbLp.y = startY + (ev.rawY - downY).toInt()
                        wm.updateViewLayout(view, orbLp)
                    } else if (!holdFired) {
                        val dx = ev.rawX - downX; val dy = ev.rawY - downY
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) { dragging = true; cancelHold(); feed(ev, MotionEvent.ACTION_CANCEL) }
                        else feed(ev)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    // 拖过：吸边和回弹走**同一段**驱动（snapToEdge 里 alpha / scale / x 一起收），
                    // 不再有一条只挪 x 的时间线在背后抢坐标；被再按一下打断时，DOWN 里的 stopOrbMotion
                    // 已经把它就地落定，球停在手指下那一格，绝不回拉。
                    // 没拖（点 / 长按松手 / 手势被拆）：被打断的吸边接上（球回贴边），否则就是那一下带过冲的回弹（ζ=0.55，约 12%）。
                    if (ev.actionMasked == MotionEvent.ACTION_UP && dragging) {
                        docked = false
                        snapToEdge(view)
                    } else if (snapCut && !docked) {
                        snapToEdge(view)
                    } else {
                        orbTo(Anim.Visual.SHOW, Anim.Spring.release)
                    }
                    snapCut = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // 长按已弹菜单：松手只取消识别器，不能漏出单击。
                        if (!dragging) feed(ev, if (holdFired) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
                    } else {
                        feed(ev, MotionEvent.ACTION_CANCEL)
                    }
                    // v1.59：拖完一定要清掉 —— 以前一直是 true，dock() 看到「还在拖」就无限往后推，小球再也不缩回去
                    dragging = false
                    // P0-2：这次触摸到此结束。把被推迟的重排放到 UP 之后再执行 —— 此时手势已完整派发完，
                    // 不会再拆到「正在接收手势」的窗口。
                    orbTouchDown = false
                    undock()
                    if (pendingOrbToFront) { pendingOrbToFront = false; main.post { orbToFrontNow() } }
                    scheduleDock()
                }
            }
            true
        }
        v.alpha = 0f
        wm.addView(v, orbLp)
        v.post {
            measureOrbWinTop()
            // 首次 attach 记下这块屏的宽度：之后转屏才有「旧 x 属于多宽」的依据（不落盘，等用户真摆位置时再存）
            orbW = resources.displayMetrics.widthPixels
            if (!prefs.orbPosSaved) {
                // 没存过位置：用窗口真实原点修正，屏幕上才是真正的「左边垂直中间」
                centerOrbDefault(v)
            } else {
                // 存过位置：只按真实窗口原点重新夹一次上下边界，坐标本身不动
                val y = clampY(orbLp.y)
                if (y != orbLp.y) {
                    orbLp.y = y
                    try { wm.updateViewLayout(v, orbLp) } catch (_: Exception) {}
                }
            }
        }
        // 位移也接到窗口 x 上（[OrbShift]）：入场这一段同样只有一条时间线
        val shift = OrbShift(v, orbLp).also { orbShift = it }
        val motion = AnimGlue.SpringAnimation(v, Anim.Spring.enter, target = shift)
        orbMotion = motion
        motion.settleAt(Anim.Visual.HIDDEN)   // 起点写死（透明），再加进来走弹簧（关了动效则下一步直接落终态）
        orbTo(Anim.Visual.SHOW, Anim.Spring.enter)
        applyEdgeExclusion()
        scheduleDock()
        // attach / 首次布局后 View 才有尺寸，这时补一次；尺寸再变（改大小 / 转屏）只按变更回调刷新，不做逐帧轮询
        v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) applyEdgeExclusion()
        }
    }

    // ---------------- 离边距离（v1.57 躲开手势导航；v1.58 默认改回贴近）----------------
    // 全面屏手势：屏幕左右边缘一条是「返回」手势区。v1.57 默认离边留出整条手势区，用户嫌太远。
    // v1.58 默认只留 4dp，小球那一块向系统申请手势豁免（系统每边最多给 200dp 高，一个小球够用），
    // 这样按小球不会被当成返回；想完全不碰手势区就开「离屏幕边远一点」（= v1.57 默认）。
    // 闲置时：缩小变淡，默认再缩进屏幕边一半（orbTuck，可关）。
    private fun sideGap(): Int {
        if (!prefs.orbFar) return Ui.dp(this, 4f)
        var g = 0
        if (Build.VERSION.SDK_INT >= 30) {
            g = try {
                val ins = wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemGestures())
                Math.max(ins.left, ins.right)
            } catch (_: Exception) { 0 }
        }
        if (g == 0 && android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode", 0) == 2) g = Ui.dp(this, 30f)
        return Math.max(Ui.dp(this, 6f), g + Ui.dp(this, 4f))
    }

    private fun sideX(left: Boolean): Int = if (left) sideGap() else resources.displayMetrics.widthPixels - orbLp.width - sideGap()

    private fun onLeft(): Boolean = orbLp.x + orbLp.width / 2 < resources.displayMetrics.widthPixels / 2

    /**
     * 上次坐标所对应的屏幕宽度（px）——判旧 x 靠哪边只能按「当时那块屏」算。
     * 运行时记最近一次 attach / 贴边 / 转屏后的宽度；进程重启后先用 [Prefs.orbW]（老数据没有就退回当前宽度）。
     */
    private var orbW = 0
    private fun lastScreenW(): Int =
        if (orbW > 0) orbW else prefs.orbW.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    /** 旧 x 是贴左还是贴右：按产生这个 x 时的屏幕宽度判，不看转屏后的新宽度 */
    private fun onLeftAt(x: Int, width: Int) = x + orbLp.width / 2 < width / 2

    /** 上下也别压到状态栏和底部横条（y 是窗口内坐标，用真实窗口原点换算成屏幕安全区） */
    private fun clampY(y: Int): Int {
        val safe = safeScreenBounds()
        val min = safe.top - orbWinTop
        val max = safe.bottom - orbLp.height - orbWinTop - Ui.dp(this, 8f)
        return y.coerceIn(min, Math.max(min, max))
    }

    /**
     * 贴近 / 缩进边里时小球压在返回手势区上：申请豁免，按它不会被当成返回。
     * 矩形用**全 View 本地坐标** —— 系统会替我们套上 scale / translation（缩边），再按 scale 折算一次就错位了。
     */
    private fun applyEdgeExclusion() {
        val v = orb ?: return
        if (Build.VERSION.SDK_INT < 29) return
        v.systemGestureExclusionRects = if (!prefs.orbFar || prefs.orbTuck) listOf(android.graphics.Rect(0, 0, v.width, v.height)) else emptyList()
    }

    /** 设置里切了「离屏幕边远一点」：小球立刻挪到新位置（没存过位置就还是默认左中，不落坐标） */
    private fun applyEdgeMode() {
        val v = orb ?: return
        val saved = prefs.orbPosSaved
        undock()
        // 下面要直接改坐标：先停掉在跑的那段（否则下一帧按旧基准把 x 写回旧边），摆好新位置、把基准接过去，
        // 再把 undock 刚被停掉的展开按新位置走完 —— 终态还是 SHOW，不留一个冻在闲置透明度的球。
        stopOrbMotion()
        orbLp.x = sideX(onLeft()); orbLp.y = clampY(orbLp.y)
        if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
        orbShift?.rebase()
        orbTo(Anim.Visual.SHOW, Anim.Spring.popOut)
        if (saved) {
            val w = resources.displayMetrics.widthPixels
            orbW = w
            prefs.saveOrbPos(orbLp.x, orbLp.y, w)
        } else {
            // 未保存：只挪给用户看，别把默认位置写成「已摆过」；布局完按真实窗口重新左中
            v.post { if (orb === v) centerOrbDefault(v) }
        }
        applyEdgeExclusion()
        scheduleDock()
    }

    /** 设置里改了小球大小（v1.60）：原地换大小，还贴着原来那条边（没存过位置就不落坐标） */
    private fun applyOrbSize() {
        val v = orb ?: return
        val saved = prefs.orbPosSaved
        val left = onLeft()
        undock()
        // 同上：停旧段 → 按新尺寸直接摆 → 接基准 → 展开走完
        stopOrbMotion()
        val size = Ui.dp(this, prefs.orbSizeDp.toFloat())
        orbLp.width = size; orbLp.height = size
        orbLp.x = sideX(left); orbLp.y = clampY(orbLp.y)
        if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
        orbShift?.rebase()
        orbTo(Anim.Visual.SHOW, Anim.Spring.popOut)
        if (saved) {
            val w = resources.displayMetrics.widthPixels
            orbW = w
            prefs.saveOrbPos(orbLp.x, orbLp.y, w)
        } else {
            // 尺寸变了，竖直中心也要按新高度重算；同样保持未保存标记
            v.post { if (orb === v) centerOrbDefault(v) }
        }
        applyEdgeExclusion()
        panel?.relayout()
        scheduleDock()
    }

    // ---------------- 小球缩边（v1.52，小屏省地方）----------------
    // 3 秒没碰、也没在识别 / 自动扫描时变淡变小；一碰就回来。
    // 以靠边那一侧为轴缩到 6 成、不透明度按设置（默认 80%）；orbTuck 开着再往屏幕外挪，只露出半个。
    private var docked = false
    private val dockRunnable = Runnable { dock() }
    private fun scheduleDock() { main.removeCallbacks(dockRunnable); main.postDelayed(dockRunnable, 3000) }

    // ---------------- 小球动效：一条时间线 ----------------
    // 以前窗口 x 有它自己的一条时间线（贴边 snapToEdge 一个 ValueAnimator、缩边 animateOrbX 一个），
    // alpha / scale 走 orbMotion 弹簧 —— 两套各跑各的，而且 x 那条**没有句柄**，想取消都取消不掉：
    // 缩边途中一拖 / 一松手，旧的那条还在往旧坐标写，球就被「拉回去」半截。
    // 现在位移接进 orbMotion 的落点（[OrbShift]，和 [MenuShift] 同一个理由：悬浮窗 surface 只有窗口那么大，
    // 视图 translation 超出会被裁掉），所以一个小球动效只有一条时间线、一个可取消句柄。

    /**
     * 系统动效开关（开发者选项 / 无障碍「关闭动画」把时长缩放调到 0）。
     * [AnimGlue.SpringAnimation.to] 自身也带同一道闸（关了直接落终态），这里再短路一次
     * 是让本文件的语义显式：关动效时小球的位置 / 缩放一步到位，绝不停在半路。
     */
    private fun motionOn(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /**
     * 小球窗口位移的落点：把弹簧的 tx 接到**整个窗口的 x** 上（纵向 ty 恒 0，见 [translationY]）。
     * [baseX] 是「位移 0」对应的窗口坐标：窗口被直接挪过（拖动 / 转屏 / 换边）之后必须 [rebase] 一次，
     * 否则 `tx = 0` 会按旧基准算，把球拉回上一段动画开始时的地方。
     */
    private inner class OrbShift(private val r: View, private val p: WindowManager.LayoutParams) : Anim.VisualTarget {
        private var baseX = p.x
        override var alpha: Float
            get() = r.alpha
            set(v) { r.alpha = v }
        override var scaleX: Float
            get() = r.scaleX
            set(v) { r.scaleX = v }
        override var scaleY: Float
            get() = r.scaleY
            set(v) { r.scaleY = v }
        override var translationX: Float
            get() = (p.x - baseX).toFloat()
            set(v) { p.x = baseX + v.toInt() }
        /** 纵向一律不挪窗口：y 由 clampY / 拖动直接管（视图变换会被窗口裁掉，窗口又没必要动） */
        override var translationY: Float
            get() = 0f
            set(value) { /* 忽略：小球所有目标的 ty 都是 0 */ }
        /** 一帧只推一次窗口（tx / ty 各推一次就是双倍布局，悬浮窗上很贵） */
        override fun commit() { if (r.isAttachedToWindow) try { wm.updateViewLayout(r, p) } catch (_: Exception) {} }

        /** 把「位移 0」重新对到 [x]（默认当前窗口位置）。只在动效停下之后调，别在飞行中改基准 */
        fun rebase(x: Int = p.x) { baseX = x }
    }

    /**
     * 立刻停下小球动效并**就地**落定：位移基准先对回当前窗口坐标，再把属性按此刻真实值写死。
     * 取消点（这一批全部走这里，不再有第二条 x 时间线）：
     *  - 手指按下（ACTION_DOWN）：拖动期间不会有旧动画抢写 orbLp.x；
     *  - dock / undock / snapToEdge 开始前（新一段接管旧一段）；
     *  - onConfigurationChanged 重算贴边坐标之前；
     *  - 截屏前藏起来（[showOrb] 走自己的 settleAt）与销毁 / 重建小球（引用直接放掉）。
     */
    private fun stopOrbMotion() {
        val motion = orbMotion ?: return
        val v = orb
        orbShift?.rebase()   // 基准先回到窗口真实位置：下面 tx = 0 才不会把球弹回旧基准
        motion.settleAt(Anim.Visual(v?.alpha ?: 0f, v?.scaleX ?: 1f, 0f, 0f))
    }

    /**
     * 缩边时**相对贴边展开位置**的窗口 x 位移（orbTuck 关掉就是 0）。
     * 绝对落点和原来的 `animateOrbX` 完全一致：左边挪到 `-out`、右边挪到「屏宽 − 球宽 + out」，
     * 也就是再往屏幕外挪半个身位；换成位移只是因为基准是 [sideX]。
     */
    private fun tuckShift(): Int {
        if (!prefs.orbTuck) return 0
        // 缩边后球只剩 orbIdleScale 那么宽，往里挪多少必须按**缩放后宽度**算：
        // 旧代码写死 0.3w 是「按未缩放宽度」估的，idleScale 调到 30 时挪出去的量比缩放后宽度还大，
        // 球一个像素也露不出来，而窗口仍在屏幕外 —— 于是看得见一点点、却怎么点都点不中。
        // 现在只把缩放后宽度的 (1 - EXPOSED_FRACTION) 推出去，任何刻度都留得下可点的边。
        val scale = prefs.orbIdleScale.coerceIn(10, 100) / 100f
        val out = (orbLp.width * scale * (1f - EXPOSED_FRACTION)).toInt()
        return if (onLeft()) -(out + sideGap()) else out + sideGap()
    }

    /** 走一段小球动效；系统关了动效就直接写终态（弹簧的帧循环不会自己停） */
    private fun orbTo(target: Anim.Visual, spring: Anim.Spring) {
        val motion = orbMotion ?: return
        if (motionOn()) motion.to(target, spring) else motion.settleAt(target)
    }
    private fun dock() {
        val v = orb ?: return
        // v1.59：识别卡住（截图回调一直没来）超过 150 秒就当它结束了 —— 不然小球永远不缩、双击也没反应
        // 看门狗不关闭面板：取消旧轮后也恢复仍挂载的面板，迟到 capture 已被 live 守卫作废。
        if (solving && System.currentTimeMillis() - solvingSince > 150_000) {
            cancelCurrentIfSolving()
            syncLayers()   // 只把「该露的那一层」露出来（正在为截屏藏浮窗时它仍旧藏着）
        }
        // orbTouchDown：手指按着时不动窗口 —— 缩下去再弹回来会让系统把这串事件发成 CANCEL
        if (docked || solving || autoScan || menu != null || dragging || orbTouchDown) { scheduleDock(); return }
        docked = true
        v.pivotX = if (onLeft()) 0f else v.width.toFloat()
        v.pivotY = v.height / 2f
        // 缩边只有**一条**时间线：alpha / scale / 窗口 x 同一个句柄。基准对到「贴边展开位置」，
        // 缩进去的那一段就是 tx —— 落点和原来的 animateOrbX 逐像素一致（见 [tuckShift]）。
        // orbTuck 关掉时**不挪 x**（和原来一样：不动就是不动的语义），所以基准就地取当前窗口位置。
        stopOrbMotion()
        orbShift?.rebase(if (prefs.orbTuck) sideX(onLeft()) else orbLp.x)
        orbTo(Anim.Visual(prefs.orbIdleAlpha / 100f, prefs.orbIdleScale / 100f, tuckShift().toFloat(), 0f), Anim.Spring.enter)
    }
    private fun undock() {
        main.removeCallbacks(dockRunnable)
        // 识别 / 自动扫描进行中：150 秒卡死自愈的看门狗就挂在 [dock] 里，只靠它自己的节拍重排。
        // 菜单「识别屏幕」、自动扫描、闸门提交这几条入口都没有触摸 UP 后面的 [scheduleDock]，
        // 这里若不把节拍接回去，模型一直吐思考时就没有任何 UI 级退出，只剩 socket 超时。
        if (solving || autoScan) scheduleDock()
        if (orb == null) return
        if (!docked) return
        // 回弹同理：以前 x 是瞬移、alpha / scale 才走弹簧。现在基准对到贴边展开位置，
        // 当前 tx 就是缩进去的那一段，和透明度 / 缩放一起收回来（被按住打断也不会留下半截缩进）。
        stopOrbMotion()
        orbShift?.rebase(sideX(onLeft()))
        orbTo(Anim.Visual.SHOW, Anim.Spring.popOut)
    }

    // ---------------- 转屏（v1.52）----------------
    /** 上次看到的系统深浅色（onConfigurationChanged 里比新旧值判断要不要换配色） */
    private var lastSystemDark: Boolean = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // 注意：回调里 resources.configuration 可能已经是新的，所以拿 newConfig 和「记住的那个」比
        val newDark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val darkChanged = newDark != lastSystemDark
        lastSystemDark = newDark
        super.onConfigurationChanged(newConfig)
        closeMenu()
        // 转屏修复：词卡 / 知识卡是按**旧屏宽**建窗口的，系统转屏不会替悬浮窗重新排版 ——
        // 横屏 → 竖屏会留下一张旧宽度的巨大浮层盖住屏幕。这里直接收起（只 hide，不碰 onJudge：
        // 不评分、不 mark recent、不调 Words.mark / Srs；进行中的拖动 / 动画由 hide 自己取消）。
        if (wordCard.showing) wordCard.hide()
        if (knowledgeCard.showing) knowledgeCard.hide()
        // 在跑的动效（缩边 / 贴边 / 入场）先停掉：弹簧是帧驱动，它下一帧会把刚算好的新屏幕坐标写回旧值。
        // 停下时就地落定，随后按新屏幕重算的 x 才是唯一真相。
        stopOrbMotion()
        docked = false
        orb?.let { v ->
            v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
            if (prefs.orbPosSaved) {
                // 用户摆过位置：只按新屏幕重新吸边 / 夹回安全区，绝不重置成默认左边中间。
                // 判旧 x 靠哪边要用「上次那块屏」的宽度 —— 拿新宽度解释旧 x，竖屏右侧的点会落进横屏左半边，球就翻到左边了
                orbLp.x = sideX(onLeftAt(orbLp.x, lastScreenW()))
                orbLp.y = clampY(orbLp.y)
                orbW = resources.displayMetrics.widthPixels
            } else {
                // 没存过位置：先给一个窗内近似值，落帧后再用真实窗口几何精确竖直居中
                orbLp.x = sideX(true)
                orbLp.y = clampY((resources.displayMetrics.heightPixels - orbLp.height) / 2)
            }
            if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
            orbShift?.rebase()   // 基准接到新位置：之后 tx = 0 就是「就在这里」
            // 等这一轮布局真正跑完再量：getLocationOnScreen / orbWinTop 才是新屏幕上的真实值
            v.postDelayed({
                if (orb !== v) return@postDelayed
                measureOrbWinTop()
                if (!prefs.orbPosSaved) {
                    centerOrbDefault(v)
                } else {
                    // 存过位置：只按新窗口原点重新夹上下边界，不把坐标重置成默认
                    val y = clampY(orbLp.y)
                    if (y != orbLp.y) {
                        orbLp.y = y
                        try { wm.updateViewLayout(v, orbLp) } catch (_: Exception) {}
                    }
                }
                // 居中 / 夹边后再等一帧，卡片按球的新屏幕坐标重新贴
                v.postDelayed({ if (orb === v) relayoutOrbGuide() }, 32L)
            }, 48L)
        }
        panel?.relayout()
        capture?.resizeToScreen(this)   // 截图尺寸跟着横竖屏换，不然横屏题目会被压扁
        scheduleDock()
        // v1.67：系统深浅色变了 —— 跟随系统开着就换成伴生深色，小球和面板立刻重画
        if (darkChanged) main.post {
            Ui.applyTheme(prefs); panel?.applyTheme(); orb?.invalidate()
        }
    }

    private var dragging = false
    /** 按下这一下掐掉过一段还没走完的吸边：松手若只是点一下，要把吸边接上，别把球留在半路 */
    private var snapCut = false

    private fun snapToEdge(view: View) {
        val targetX = sideX(onLeft())
        orbLp.y = clampY(orbLp.y)
        // 先停在跑的上一段（上一次吸边 / 缩边），把位移基准对到**贴边后的位置**：
        // 于是终态 tx = 0 就是「贴到这一边」，此刻的 tx 是还差多少 —— 位置连续，不会带旧速度往回拉。
        stopOrbMotion()
        orbShift?.rebase(targetX)
        if (orbMotion == null) {
            orbLp.x = targetX
            if (view.isAttachedToWindow) wm.updateViewLayout(view, orbLp)
        } else {
            // 吸边 + 松手回弹是同一段驱动：alpha / scale 一起落回常态，没有第二条时间线在背后改 orbLp.x
            orbTo(Anim.Visual.SHOW, Anim.Spring.snap)
        }
        val w = resources.displayMetrics.widthPixels
        orbW = w
        prefs.saveOrbPos(targetX, orbLp.y, w)   // 坐标与「当时的屏幕宽度」一起存，转屏后才知道旧 x 靠哪边
        // 面板「跟着 / 避开小球」时，小球挪了面板也跟着换地方
        if (prefs.panelPos == PanelLayout.ORB || prefs.panelPos == PanelLayout.AWAY) main.postDelayed({ panel?.let { if (it.attached) it.relayout() } }, 240)
    }

    // ---------------- 面板 ----------------
    /** 小球在哪（缩边时按展开后的位置算，免得面板贴着半藏的小球）；小球隐藏时 null */
    private fun orbNow(x: Int? = null): PanelLayout.Orb? =   // v1.63：默认值以前在判空前就求值，小球还没建时访问 orbLp 会崩
        if (orb == null || prefs.orbHidden) null else PanelLayout.Orb(x ?: sideX(onLeft()), orbLp.y + orbWinTop, orbLp.width)
    /** 小球窗口的 y 从哪算起（屏幕坐标 − orbLp.y）：小球窗口排在状态栏 / 挖孔下面，面板窗口从屏幕顶算，差这一截（v1.60 实测 128px） */
    private var orbWinTop = 0
    private fun measureOrbWinTop() {
        val v = orb ?: return
        if (docked || !v.isAttachedToWindow || v.scaleY != 1f) return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        orbWinTop = loc[1] - orbLp.y
    }

    // ---------------- 新手悬浮卡（只给真正的新用户，一次） ----------------

    /**
     * 浮窗真正能用的安全区（**屏幕坐标**）。
     *
     * 不用「displayMetrics 减状态栏高度」那种猜法：API 30+ 直接读 WindowMetrics 的 bounds + systemBars/cutout
     * insets；只有老系统（悬浮窗拿不到 WindowInsets）才退回系统尺寸资源。
     */
    private fun safeScreenBounds(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val m = wm.currentWindowMetrics
                val b = m.bounds
                val i = m.windowInsets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                )
                val r = android.graphics.Rect(b.left + i.left, b.top + i.top, b.right - i.right, b.bottom - i.bottom)
                if (r.width() > 0 && r.height() > 0) return r
            } catch (_: Exception) {}
        }
        val dm = resources.displayMetrics
        return android.graphics.Rect(0, Ui.statusBarHeight(this), dm.widthPixels, dm.heightPixels - Ui.navBarHeight(this))
    }

    /**
     * 没存过位置时把球摆到「屏幕左边 + 垂直中间」。
     *
     * [orbLp] 是**窗口内**坐标，而窗口原点并不在屏幕 (0,0)（差一截 [orbWinTop]）——
     * 先算屏幕目标再减真实偏移，不能盲减状态栏。用户摆过位置（[Prefs.orbPosSaved]）就完全不动。
     */
    private fun centerOrbDefault(v: View) {
        if (prefs.orbPosSaved || !v.isAttachedToWindow) return
        val safe = safeScreenBounds()
        val screenY = safe.top + (safe.height() - orbLp.height) / 2
        val minY = safe.top - orbWinTop
        val maxY = safe.bottom - orbLp.height - orbWinTop
        orbLp.x = sideX(true)
        orbLp.y = (screenY - orbWinTop).coerceIn(minY, Math.max(minY, maxY))
        try { wm.updateViewLayout(v, orbLp) } catch (_: Exception) {}
    }

    /**
     * 欠着新手卡 + 球已经真正可见时才弹。
     * **addView 成功才写 seen**：加窗失败就保持 pending，下次建球再试，绝不提前记账。
     */
    private fun maybeShowOrbGuide() {
        if (!running || orbGuide != null) return
        if (prefs.orbGuideSeen || !prefs.orbGuidePending) return
        val v = orb ?: return
        if (!v.isAttachedToWindow || v.visibility != View.VISIBLE || v.alpha <= 0f) return
        if (prefs.orbHidden) return
        val card = buildOrbGuide()
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // 非焦点 + 非模态：卡片以外的手势全部照常落到后面的 App，不拦任何其他区域
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        placeOrbGuide(card, lp)
        try { wm.addView(card, lp) } catch (_: Exception) { return }
        orbGuide = card
        orbGuideLp = lp
        prefs.markOrbGuideSeen()
    }

    /** 一次性新手卡：说明文字 + 「试试打开面板」/「知道了」；真正开面板走 [orbTap]，不伪造识别 */
    private fun buildOrbGuide(): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(ctx, Ui.CARD, 14f)
            val p = Ui.dp(ctx, 12f)
            setPadding(p, p, p, p)
        }
        card.addView(Ui.text(ctx, "小球这样用", 14f, Ui.TX1).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(Ui.text(ctx, "单击：开关答案面板\n双击：识别屏幕\n长按：菜单\n拖动：换位置", 13f, Ui.TX2).apply {
            setPadding(0, Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 10f))
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(-1, -2))
        val row = Ui.row(ctx)
        val tryBtn = Ui.button(ctx, "试试打开面板") { main.post { hideOrbGuide(); orbTap() } }
        val okBtn = Ui.button(ctx, "知道了") { hideOrbGuide() }
        for (b in arrayOf(tryBtn, okBtn)) b.minimumHeight = Ui.dp(ctx, 48f)
        row.addView(tryBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(okBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(ctx, 8f) })
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    /**
     * 把卡贴到球旁边：横向优先放在球朝屏幕中间的一侧，放不下换另一侧；纵向和球心对齐。
     *
     * 位置用 [View.getLocationOnScreen]（真实屏幕坐标）+ 球的窗口参数反推窗口原点，再换算回卡自己的窗口坐标；
     * 安全区来自 [safeScreenBounds]。窄到横向会压住球时，改放球上方 / 下方。
     */
    private fun placeOrbGuide(g: View, lp: WindowManager.LayoutParams) {
        val v = orb ?: return
        if (!v.isAttachedToWindow) return
        val safe = safeScreenBounds()
        val maxW = minOf(Ui.dp(this, 300f), (safe.width() * 0.74f).toInt()).coerceAtLeast(Ui.dp(this, 180f))
        g.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = g.measuredWidth.coerceAtLeast(1)
        val h = g.measuredHeight.coerceAtLeast(1)
        // 行内有 weight 孩子：窗口若保持 WRAP_CONTENT 会被量到整屏宽，这里钉死实测宽度
        if (lp.width != w) lp.width = w
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        // 卡和球同一 type / gravity / FLAG_LAYOUT_NO_LIMITS → 窗口原点偏移一致，用球反推这个偏移
        val offX = loc[0] - orbLp.x
        val offY = loc[1] - orbLp.y
        val gap = Ui.dp(this, 10f)
        val edge = Ui.dp(this, 8f)
        val minX = safe.left + edge
        val maxX = (safe.right - edge - w).coerceAtLeast(minX)
        val minY = safe.top + edge
        val maxY = (safe.bottom - edge - h).coerceAtLeast(minY)
        var x = loc[0] + orbLp.width + gap
        if (x > maxX) x = loc[0] - gap - w
        x = x.coerceIn(minX, maxX)
        var y = loc[1] + (orbLp.height - h) / 2
        // 横向实在放不开、夹回来会压住球 → 改放球下方；下方也不够就放上方
        val overlapsX = x < loc[0] + orbLp.width && x + w > loc[0]
        if (overlapsX && y < loc[1] + orbLp.height && y + h > loc[1]) {
            y = loc[1] + orbLp.height + gap
            if (y > maxY) y = loc[1] - gap - h
        }
        y = y.coerceIn(minY, maxY)
        lp.x = x - offX
        lp.y = y - offY
    }

    /** 转屏 / 球换位后把卡重新贴过去；失败就收掉，绝不留下一个位置错乱或没有主的窗口 */
    private fun relayoutOrbGuide() {
        val g = orbGuide ?: return
        val lp = orbGuideLp ?: return
        if (!g.isAttachedToWindow) return
        try {
            placeOrbGuide(g, lp)
            wm.updateViewLayout(g, lp)
        } catch (_: Exception) { hideOrbGuide() }
    }

    /** 收起新手卡：先摘延时回调，再 removeViewImmediate（截屏前不留一帧） */
    private fun hideOrbGuide() {
        main.removeCallbacks(orbGuideShow)
        val g = orbGuide ?: return
        orbGuide = null
        orbGuideLp = null
        try { wm.removeViewImmediate(g) } catch (_: Exception) {}
    }

    /**
     * 拿到（必要时新建）答案面板这个**长期复用**的对象。
     *
     * [attach] = false：只建对象 / 只写状态，**不加窗口**。用于「先问再发」那一档 ——
     * 那时屏幕上只该有提问浮窗，旧答案绝不能被提前弹出来（v2.7 修的就是这一条：
     * 以前这里无条件 attach，于是「先问」变成「先看旧答案、问题再叠在它上面」）。
     */
    private fun ensurePanel(attach: Boolean = true): AnswerPanel {
        val p = panel ?: AnswerPanel(this, wm, prefs,
            onClose = { onPanelClosed() },
            // 能用无障碍的系统：回设置页让用户重开无障碍（持久）；老系统（<11）只能直接走录屏授权
            onReauth = { askCapture(thenSolve = false) },   // v1.59：点提示直接弹「共享屏幕」框，不再先进设置页
            onAsk = { shown, q, withShot, directive, lang -> ask(shown, q, withShot, directive, lang) },
            onStar = { if (convHistoryId < 0) { toast("这条没存进历史（设置里关了「保存历史记录」）"); null } else { val on = store.get(convHistoryId)?.star != true; store.setStar(convHistoryId, on); toast(if (on) "已收藏：历史记录页「只看收藏」能找到" else "已取消收藏"); on } },
            orbRect = { orbNow() }
        ).also { panel = it }
        p.setModeLabel(modeLabel())
        // v1.85 免打扰（全部静音）：静音期间面板**不 attach**。选这里当闸门是因为「所有会盖住屏幕的面板输出」
        // —— 双击识别 / 框选 / 图片识别 / 单击看上次结果 —— 都从 ensurePanel 走，一处收口就够。
        // 放心：solve 照常跑、结果照常 store.add 进历史（见 runSolve），只是没有窗口把它显示出来。
        // v2.7：面板出现前先收菜单 —— 否则新面板会加在菜单**下面**，看着像「菜单里点出来的东西被菜单压住」。
        if (attach && !p.attached && !prefs.mutedNow()) { closeMenu(); p.attach(); markLayerOpened(LAYER_PANEL); orbToFront() }
        // 自动扫描中途才建出来的面板：被动模式要同步过去（不然会挡手挡视线）。
        // 阅读暂停时**不**切被动 —— 那正是要把答案展开成可读的活动面板。
        if (autoScan && !readingPaused) p.setPassive(true)
        // 面板新建 / 重新挂上：会话行（页数 + 真实剩余 / 已暂停）立刻补齐，不等下一次倒计时 tick。
        syncScanSession()
        syncLayers()   // 让「谁是前层」立刻落到窗口上（新面板 / 没 attach 的面板都在这里对齐）
        return p
    }

    /** 悬浮窗按添加顺序叠放：面板后加就会盖住小球（小球贴右边时正好被压住，没法再双击）→ 把小球重新挂一次 */
    /**
     * 面板 attach 会把窗口加到小球之上 → 需要把小球重新挂一遍排到最前。
     *
     * v1.86 P0-2：双击是在 ACTION_DOWN 里认出并**同步**走 ensurePanel 的。此刻若立刻
     * removeViewImmediate/addView 小球，等于把「正在接收这次手势」的窗口在下按瞬间拆了又装，
     * 会打断这次的 UP、使手势状态机错位。所以手势进行中只记一个标记，等这次触摸 UP 之后再重排
     * （见 ACTION_UP 分支）。非手势路径（读屏点按 / 自动扫描 / 分享进来）不在 DOWN 中，直接重排即可。
     * 注意：不能只是 main.post —— 那只是把重排挪到本次 DOWN 事件处理完之后，此时 UP 还没派发。
     */
    private fun orbToFront() {
        if (!orbTouchDown) { orbToFrontNow(); return }
        pendingOrbToFront = true
    }

    private fun orbToFrontNow() {
        val v = orb ?: return
        try { wm.removeViewImmediate(v); wm.addView(v, orbLp) } catch (_: Exception) {}
    }

    /**
     * 单击小球：开 / 关面板。
     *
     * v2.7：提问浮窗开着时，单击小球**不再去 ensurePanel** —— 那会把旧答案面板顶到提问窗上面
     * （用户报的正是这个）。这里改成「收掉提问浮窗」，等同于用户点了它自己的**取消**：不发任何请求，
     * 并补上取消失败时该做的收尾（追问按钮 / 状态行）——[dropQuestionGate] 本身是静默的（提交路径依赖
     * 这一点，不能改），所以收尾只能在这一处调用点自己对齐 [openQuestionGate] 的 onCancel。
     * 小球永远可控：这一下一定有反应。
     */
    private fun togglePanel() {
        if (questionGate != null) {
            val gp = panel   // 闸门挂着的那个面板：下面判断它是否还活着，绝不给别的面板写状态
            dropQuestionGate()
            // 与 onCancel 同一份收尾：这一趟没发请求（askFirst 不 reset 正文，读位 / 正文一点没动），
            // 只把追问按钮还回去、状态行收回默认。原面板已被换掉 / 新一轮已在跑 → 不碰（归别人管）。
            if (gp != null && panel === gp && !solving) {
                gp.setAskEnabled(conv != null)
                if (screenSource() == null) gp.setStatus("还不能截屏 · 点这里授权…", clickable = true) { openPermission() }
                else gp.setStatus("双击小球识别屏幕")
            }
            return
        }
        val p = panel
        // P0-1：单击关闭面板要和 ✕ 走同一条销毁语义 —— 先掐断在途识别（否则刚才双击发起的这一轮
        // 会继续跑、把答案写进已销毁的面板，且 solving 不复位 → 小球「卡」住，再双击无反应）。
        if (p != null && p.attached) {
            panelReadPos = p.readPosSnapshot()
            dropQuestionGate(); cancelCurrentIfSolving(); p.fadeOutAndRemove(); panel = null
            markLayerClosed(LAYER_PANEL)
            return
        }
        val np = ensurePanel()
        if (!solving) {
            np.setAskEnabled(conv != null)
            val last = lastAnswer()
            if (last != null) {
                np.showText(last, panelReadPos); panelReadPos = null
                np.setStarred(store.get(convHistoryId)?.star == true)
            }
            else if (!prefs.hasApi()) np.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }
            else np.setStatus(if (screenSource() == null) "还不能截屏 · 点这里授权（直接弹系统的「共享屏幕」框）" else "双击小球识别屏幕", clickable = screenSource() == null)
        }
        syncLayers()
    }

    /** 关面板：掐断连接并马上结束这一轮（v1.63：以前只打标记，卡住的请求挂满 90 秒，期间双击没反应） */
    private fun openSettings(section: String = "") {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (section == "接口") i.putExtra(MainActivity.EXTRA_API, true)
        try { startActivity(i) } catch (e: Exception) { toast("打不开设置：" + (e.message ?: "")) }
    }

    /** 打开统一的权限页（[PermissionActivity]）—— 无障碍授权 / 查看系统开关只有这一个入口，不另开系统页直链 */
    private fun openPermission() {
        try { startActivity(PermissionActivity.intent(this)) } catch (_: Exception) {}
    }

    private fun cancelCurrentIfSolving() {
        if (solving) {
            cancel.abort(); finishSolve()
            // 取消这一轮时小球多半还停在截屏前藏起来的状态（setOverlaysHidden(true) 把它压到 alpha=0）。
            // 取消 / 看门狗兜底都必须把它恢复可见 —— 只恢复小球，不动面板（面板正要关闭，别让它闪一下）。
            showOrb(1f)
        }
    }

    /**
     * 这一轮的迟到回调还算数吗：没被取消、仍是当前那一轮（cancel 未被新一轮替换）、且面板还是当时那个。
     * P0-1：capture / onText / onPhase / final 回调只有它成立才允许写 UI / 恢复 overlay ——
     * 否则「旧回调写进已销毁或别的面板、或把新一轮的隐藏态又恢复了」，会收掉别人的面板。
     */
    private fun live(p: AnswerPanel, myCancel: AtomicBoolean): Boolean =
        !myCancel.get() && cancel === myCancel && panel === p

    /** v1.59：直接弹系统的「共享屏幕」授权框（透明中转页，用户只看到系统的框）；有悬浮窗权限的应用可以从后台这么拉 */
    private fun askCapture(thenSolve: Boolean) {
        try { startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SOLVE_AFTER, thenSolve)) }
        catch (e: Exception) { toast("弹不出授权框：" + (e.message ?: e.javaClass.simpleName)) }
    }

    // ---------------- 识别 ----------------
    /** 当前用哪条截屏通道：无障碍优先（授权持久），没开才用录屏 */
    private fun screenSource(): ScreenSource? {
        if (a11ySource.ready) return a11ySource
        capture?.let { if (it.alive) return ProjectionSource(it) { r -> worker.execute(r) } }
        return null
    }

    /**
     * 双击小球这一条入口：按用户选的这一档做（见 [OrbMenu.TAPS]）。
     * 单独一层是为了让「双击」与「授权回来续接」用**同一个**选择，不用两处各读一遍设置。
     */
    private fun solveTap(tap: String) {
        val t = OrbMenu.normTap(tap)
        tapAfterAuth = null   // 上一趟没走完的续接作废：这次双击是新的一趟
        solve(auto = false, tap = t)
    }

    /**
     * 截屏识别。双击小球、自动扫描都先查缓存。
     *
     * [tap] 是**这一趟由哪种双击来的**（见 [OrbMenu.TAPS]），null = 不是双击（菜单 / 快捷开关 /
     * 快捷方式 / 自动扫描）：这条入口一律走设置里存着的模式，也不碰提问浮窗。
     *  - [OrbMenu.TAP_ASK]  先弹小浮窗问「想了解什么」，选了才发一次（老行为）
     *  - [OrbMenu.TAP_EXAM] 直接按「做题」模式识别，不问
     *  - [OrbMenu.TAP_WORD] 直接按「单词翻译」模式识别，不问
     *
     * 直接那两档**不写回** [Prefs.mode]：双击只是把这一条入口换个做法，设置里的模式原样不动
     * （自动扫描 / 快捷开关 / 面板模式抽屉都还按设置来 —— 不能因为双击选了做题就全盘改掉）。
     */
    private fun solve(auto: Boolean, tap: String? = null) {
        closeMenu()
        if (solving) return
        val askFirst = tap == OrbMenu.TAP_ASK
        // 直接识别的那两档（值就是模式 id，见 OrbMenu.TAPS）：它们才要强制模式。
        // 「先问问题」那档照样用设置里的模式 —— 用户选的是问题，问题走自定义提示词。
        val forceMode = if (tap == null || tap == OrbMenu.TAP_ASK) null else tap
        // 非双击入口（菜单「识别屏幕」/ 快捷开关 / 自动扫描）走的不是「先问再发」：
        // 顺手清掉上次没走完的授权标记，免得从授权页回来时被当成双击的续接而多弹一次提问浮窗；
        // 也把还挂着的提问浮窗收掉 —— 这一轮已经换人做了，那个浮窗再选问题也没人接着发（startGateSolve 会拒绝）
        if (tap == null) { tapAfterAuth = null; dropQuestionGate() }
        // 双击选了直接做的那两档：没提问浮窗什么事了，挂着的那个收掉（它等的问题是上一趟 ask 的）；
        // 但 tapAfterAuth 不能清 —— 它就是这一趟刚给自己留的续接标记
        else if (!askFirst) dropQuestionGate()
        // 「先问再发」这一档**不 attach 面板**：屏幕上只该有提问浮窗 —— 旧答案绝不能被提前弹出来，
        // 更不能变成「旧答案在下面、提问浮窗叠在上面」。面板对象照建（状态行 / 追问开关都写它），只是先不加窗口。
        val p = ensurePanel(attach = !askFirst)
        // v1.63：先看接口 —— 以前没填接口也先弹录屏框，点完「开始」才告诉人没填；这行点了直接去设置
        if (!prefs.hasApi()) {
            if (auto) { reportScanFailure("还没填接口（地址 / Key / 模型）"); return }   // v1.86：自动扫描里这行永远看不见，走失败计数
            p.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }; return
        }
        val src = screenSource()
        if (askFirst) {
            // 上一轮浮窗还挂着（连点两下 / 浮窗还没点完）：不重拍、不发请求，这一轮让给已经弹出的那个
            if (questionGate != null) return
            // 先问再发的前提：能把这个小浮窗弹出来。弹不出来就**不截、不发**（绝不跳过提问直接调接口）
            if (prefs.mutedNow()) {
                val why = "免打扰中：双击识别先不弹提问浮窗（没发请求）"
                p.setStatus(why); toast(why); return
            }
            if (!android.provider.Settings.canDrawOverlays(this)) {
                toast("悬浮窗权限没了，弹不出提问浮窗（没发请求）")
                p.setStatus("悬浮窗权限没了，弹不出提问浮窗（没发请求）", clickable = true) { openPermission() }
                return
            }
        }
        if (src == null) {
            // v1.59：不再让人先点提示、再进设置页、再点按钮 —— 直接弹系统的「共享屏幕」框；授权完自动接着识别。
            // 不再承诺「开一次长期有效」：无障碍是系统里授权一次、但服务要连着才截得了（已授权 ≠ 已连上）。
            // 要去开 / 查看系统开关统一走 [PermissionActivity]（那里才有「查看系统开关」与受限设置入口），不直达系统页。
            if (A11yService.supported) p.setStatus("请在弹出的框里点「开始」（这次运行一直有效）\n不想每次都弹框 → 点这里去权限页开「无障碍截屏」", clickable = true) { openPermission() }
            else p.setStatus("请在弹出的框里点「开始」（授权一次，本次运行一直有效）")
            if (tap != null) tapAfterAuth = tap   // 授权回来续接的那趟仍按**这次选的这一档**做
            askCapture(thenSolve = true)
            return
        }
        solving = true; solvingSince = System.currentTimeMillis()
        undock()
        cancel = Cancel()
        val myCancel = cancel
        orb?.busy = true
        p.setAskEnabled(false)
        activeAutoRound = auto   // 这一轮是不是自动扫描：显式关扫描只掐自动轮（finishSolve 清掉）
        // 自动扫描的**每一轮**都起暂存（[AnswerPanel.beginRescan]）：旧全文原样留着，直到新全文完整提交才原子换掉
        // （append 进暂存缓冲、done 才 commit）—— 这样截屏失败 / 出错 / 取消都不会把上一轮完整结果冲掉。
        // 被动层（自动扫描）里手动双击同样先暂存，真拿到图才继续；普通双击仍即时 reset。
        when {
            auto -> p.beginRescan()
            // 先问再发：这一轮还没真要答案，别清掉上一轮的正文（用户取消时它得原样留着）
            askFirst -> p.setStatus("正在截取屏幕…")
            p.isPassive -> p.setStatus("正在截取屏幕…")
            else -> { p.reset(); p.setStatus("正在截取屏幕…") }
        }
        if (auto) startScanElapsed()   // 自动扫描这一轮开始计时（单调时钟，见 scanElapsedTick）
        // 这一轮真正用的 options：强制模式那两档只换 mode、清掉自定义提示词（缓存键含 customPrompt，
        // 见 Cache.solveKey；不清的话做题 / 单词翻译会命中一条自定义模式的旧答案）。
        // 先例见 [endRegion] 的强制通用：面板标签 / 历史 / 追问 / 缓存键全按这份 opts 算，不沿用设置里的模式名。
        val opts = prefs.solveOptions().let { o ->
            if (forceMode == null) o else o.copy(mode = forceMode, customPrompt = "")
        }
        p.setModeLabel(if (forceMode == null) modeLabel() else optsLabel(opts))
        val api = prefs.api()
        // v1.90：这一轮走没走「藏浮窗」——自动扫描优先走窗口截图（API 34+）不必先藏，回调里也就不能去恢复隐藏态。
        var usedHide = false
        // 拿到图（或失败）后的统一收口：截图通道只会调它一次。是否恢复浮窗可见由 usedHide 决定，
        // 所有写入都在 live() 之后（P0-1：这一轮已被取消 / 被新一轮取代 → 面板与 overlay 都归别人管，别乱写）。
        val callback: (String?) -> Unit = { b64 ->
            main.post {
                if (!live(p, myCancel)) return@post
                if (usedHide) setOverlaysHidden(false)
                if (b64 == null) {
                    finishSolve(myCancel)
                    val why = "截屏失败：" + (src.lastError ?: "没拿到画面（锁屏了？或无障碍 / 录屏被系统收回）")
                    // v1.86：自动扫描走失败计数（亮 3 秒 / 连败自停）；被动层没有状态行 → 破例亮一行才看得见；
                    // 普通手动识别本来就看得见，直接写状态行。
                    when {
                        // 自动扫描：这轮没拿到图 → 丢掉暂存（旧全文原样留着），再走失败计数
                        auto -> { p.abortRescan(); reportScanFailure(why) }
                        p.isPassive -> p.peekStatus(why, AutoScanPolicy.PEEK_MS)
                        else -> p.setStatus(why, clickable = true)
                    }
                    return@post
                }
                if (askFirst) {
                    finishSolve(myCancel)   // 等用户选问题这段时间不算识别中：不占 150 秒看门狗、不挡缩球
                    openQuestionGate(p, myCancel, b64, opts, api)
                    return@post
                }
                if (!auto) { p.reset(); p.setStatus("正在识别分析…") }
                runSolve(b64, auto, p, myCancel, opts, api, if (auto) " · 自动扫描" else "")
            }
        }
        // 自动扫描优先：不藏浮窗 + 不等 120ms，直接截「目标窗口」（captureForScan 返回 true = 已开始，cb 必到一次）。
        // 返回 false = 没开始（通道不支持 / 选不到窗口）→ 退回老路：藏浮窗 → 120ms → 整屏 capture。
        if (!(auto && src.captureForScan(callback))) {
            usedHide = true
            setOverlaysHidden(true)
            main.postDelayed({ src.capture(callback) }, 120)
        }
    }

    /**
     * 双击截屏的提问闸门：图已经截好（[b64]），先把「想了解什么」问清楚，选定后才发**一次**请求。
     * [CaptureQuestionPanel] 自己保证 onSubmit / onCancel 各最多一次、[CaptureQuestionPanel.dismiss] 静默。
     */
    private fun openQuestionGate(p: AnswerPanel, myCancel: AtomicBoolean, b64: String, opts: Solver.Options, api: ApiConfig) {
        if (layersSuppressed()) return   // 前台卡片页压着 / 正在截屏：不许在这时候再冒出一层
        gateShot = b64
        // 图已经截好、请求还没发：状态行要说清在等什么（原来是「正在截取屏幕…」，图截完了还挂着会说谎）
        p.setStatus("在提问浮窗里点一个问题（选定后才发给模型）")
        val g = CaptureQuestionPanel(this, prefs,
            onSubmit = { q ->
                // 先摘自己的登记：同一张图只发一次
                val shot = gateShot
                gateShot = null
                questionGate = null
                markLayerClosed(LAYER_GATE)
                if (shot != null) startGateSolve(p, myCancel, shot, opts, api, q) else syncLayers()
            },
            onCancel = {
                // 取消 = **不发任何请求**。把下层（面板）交还回去，并对齐分层。
                questionGate = null
                markLayerClosed(LAYER_GATE)
                gateShot = null
                // 没发请求，正文原样留着（askFirst 那趟没 reset）：只把状态行收回默认、把追问按钮还回去
                if (live(p, myCancel) && !solving) {
                    p.setAskEnabled(conv != null)
                    if (screenSource() == null) p.setStatus("还不能截屏 · 点这里授权…", clickable = true) { openPermission() }
                    else p.setStatus("双击小球识别屏幕")
                }
                // 取消 = 回到**提问之前那一层**：面板若本来就开着（挂在屏上、显示着上一轮答案）就继续显示，
                // 读位 / 正文一点没动；本来就没开就什么都不冒出来 —— 绝不在这里新建 / 重新 show 旧答案。
                syncLayers()
            })
        questionGate = g
        markLayerOpened(LAYER_GATE)
        g.show()   // 弹不出来时它自己 Toast 并走 onCancel（上面已兜住）
        syncLayers()   // 提问层压在最上：下面的面板（如果本来就开着）立刻让路
    }

    /** 用户在闸门里选了问题：这一张图只发这一次（走自定义模式，问题进 customPrompt；缓存键含该字段） */
    private fun startGateSolve(p: AnswerPanel, myCancel: AtomicBoolean, b64: String, opts: Solver.Options, api: ApiConfig, q: String) {
        // 等选择这段时间用户可能已关面板 / 换了轮 / 已经开始别的识别：那就不发
        if (solving || !live(p, myCancel)) {
            // 不发也得把追问按钮还回去（askFirst 那趟在截屏前置过 false，不然它会一直灰着）
            if (live(p, myCancel)) p.setAskEnabled(conv != null)
            return
        }
        solving = true; solvingSince = System.currentTimeMillis()
        cancel = Cancel()
        val mine = cancel
        orb?.busy = true
        p.setAskEnabled(false)
        undock()
        // 用户选定了问题 = 从「只问不答」切到「答」：面板现在才挂出来（askFirst 那趟故意没 attach），
        // 并且**先收菜单** —— 绝不能让「发送后答案被长按菜单压住」重演。面板对象还是同一个，
        // live() 的 `panel === p` 照样成立，迟到的旧回调不会误写新面板。
        closeMenu()
        if (!p.attached && !prefs.mutedNow()) { p.attach(); markLayerOpened(LAYER_PANEL); orbToFront() }
        p.reset()
        p.setStatus("正在识别分析…")
        val qOpts = opts.copy(mode = Prompts.MODE_CUSTOM, customPrompt = q)
        p.setModeLabel(optsLabel(qOpts))
        syncLayers()   // 提问层已经摘了：面板成为前层
        runSolve(b64, false, p, mine, qOpts, api, "")
    }

    /** 静默收掉提问闸门（连点 / 取消 / 关面板 / 隐藏 / 销毁 / 别的入口抢这一轮）：不回调、不发请求 */
    private fun dropQuestionGate() {
        val g = questionGate
        questionGate = null
        markLayerClosed(LAYER_GATE)
        gateShot = null
        g?.dismiss()
        syncLayers()   // 闸门摘掉后把前层交还给下面那一层（面板内容原样，不会重开旧答案）
    }

    private val TAG_IMAGE = " · 图片"
    private val TAG_REGION = " · 框选"

    /** 拿到图（截屏或分享来的）之后：查缓存 → 调接口 → 记历史 / 缓存 / 追问上下文。tag 写进历史的标签 */
    private fun runSolve(b64: String, auto: Boolean, p: AnswerPanel, myCancel: AtomicBoolean, opts: Solver.Options, api: ApiConfig, tag: String) {
        panelReadPos = null
        worker.execute {
            // 这一轮真正要发请求了：让面板记住「请求发出时刻」（思考行按单调钟从第一个真 delta 起算，
            // 请求→首 delta 那一段是网络等候，界面文案里分开说，不冒充成模型思考）。
            main.post { if (live(p, myCancel)) p.requestStarted() }
            // v1.70 变化预检（自动扫描专用）：算个轻量指纹和上一轮比，一样就跳过这轮 —— 连缓存查询都不做。
            // 以前是「照跑一遍、命中缓存再退回」：钱是省了，但每轮白解码一次图、白读一次缓存文件、白写一条历史。
            // 双击 / 框选 / 图片识别不走这条（用户是主动点的，就该给结果）。
            // 记页也用这个轻量指纹：只有自动轮、且这一轮真的成功提交时才 +1（见 [AutoScanPolicy.Pages]）。
            var lightFp: String? = null
            if (auto) {
                val fp = Cache.screenLightFingerprint(b64)
                lightFp = if (fp.isNullOrEmpty()) null else fp
                val st = synchronized(scanLock) {
                    val (s, skipped) = AutoScanPolicy.observe(scanStill, fp)
                    scanStill = s
                    s to skipped
                }
                if (st.second) {
                    main.post {
                        // done() 先跑：把 beginRescan 留的 pendingReset 收掉，状态行才留得住新文案（done 里 pendingReset 会隐藏状态行）
                        if (live(p, myCancel)) {
                            p.done(); p.setStatus(AutoScanPolicy.skippedStatus(st.first.rounds))
                            scanFails = AutoScanPolicy.succeeded(scanFails)   // v1.86：截到了图、只是画面没变 → 这轮是健康的
                        }
                        finishSolve(myCancel)
                        // v1.90：跳过轮没有新正文 —— 计时行在 finish/freeze **之后**收掉
                        // （先收会被 finishSolve 里的 freezeScanElapsed 置回可见，冻结的「扫描 X.Xs」就留在面板上）
                        if (live(p, myCancel)) p.abortRescan()
                        // 连续 N 轮都没变：自己停下来，发条可点的通知（防止用户忘了关、整晚空烧）
                        // P0-1：只在这一轮仍有效时才停 —— 作废的迟到回调不能把新一轮扫描误停
                        if (live(p, myCancel) && AutoScanPolicy.shouldPause(st.first.rounds, if (prefs.scanPauseOnStill) prefs.scanStillRounds else 0)) pauseByStill()
                    }
                    return@execute
                }
            }
            // 专注守护（v2.3.4）：这里**不再**顺手判一次。
            // 它已经有自己的 12 秒节拍（[focusTick]）：两处都喂采样会把同一屏算两次，
            // 而且自动扫描可以被用户关掉 / 调慢，守护的计时不能跟着一起抖。

            // 缓存（v1.58）：同一画面 + 同样设置识别过 → 直接用上次的结果，不调接口。
            // v1.91：搜索开启时**整条绕过** solve 缓存的 get / put（连关键词回写那处也一并绕过）——
            // 联网搜到的答案有时效，7 天 TTL 会把过期的搜索结果当新答案发出去。识别本身照常跑。
            val searchOn = api.searchConfig.enabled
            val cacheKey = if (prefs.useCache && !searchOn) Cache.screenFingerprint(b64)?.let { Cache.solveKey(it, opts, api) } else null
            val hit = if (cacheKey != null) solveCache.get(cacheKey) else null
            if (hit != null) {
                main.post {
                    var sameAsPrior = false   // 同答案（非成功提交）：计时行要收回，但必须等 finish/freeze 之后
                    if (live(p, myCancel)) {
                        val prior = lastAnswer
                        if (auto && hit.v == prior) {
                            // 自动扫描、新全文与上一轮**完全相同**：丢弃这轮暂存，旧全文原样留着 —— 不给任何反馈
                            // （不换字、不 pulse、不留状态行）。计时行的收口搬到 finishSolve **之后**（见下）。
                            sameAsPrior = true
                        } else {
                            // 真换了答案：提交一次（append 进暂存 + done 原子换掉）
                            p.append(hit.v); p.done()
                            // 缓存命中同样要清掉上一题的来源：自动轮没走 reset，不清就会挂着别题网址。
                            // 本轮没联网（缓存命中本来就不联网）→ 来源就该是空的
                            p.setSourceHits(emptyList())
                            p.setStatus("↺ 缓存：这个" + (if (tag == TAG_IMAGE) "图片" else "画面") + Cache.ago(hit.created) + "识别过，直接用了上次的结果（没调接口）· 要重新问：设置 → 开始 → 数据与更新 → 清空缓存")
                            lastAnswer = hit.v
                            convHistoryId = store.add(History.KIND_SOLVE, optsLabel(opts) + tag + " · 缓存", hit.v)
                            // 模式 / 收藏 / 对话关联只在「完整有效结果」上更新（丢弃分支不动它们）
                            conv = Solver.Conversation(b64, opts, hit.v)
                            p.setAskEnabled(true)
                            // 自动扫描：只有真正换了新全文才 pulseReady（面板自己做一次 tick / 内容动画）
                            if (auto && hit.v != prior) orb?.pulseReady()
                        }
                        scanFails = AutoScanPolicy.succeeded(scanFails)   // v1.86：缓存命中（含同结果）都是一轮健康的扫描
                        // v1.91：新画面命中缓存同样算一页（没调接口，但这次识别成功、画面确实换了）。
                        // 同一指纹只记一次；手动轮 / 没有指纹的轮由 Pages 自己挡掉。
                        scanPages = scanPages.recognized(auto, lightFp, true)
                        syncScanSession()
                    }
                    finishSolve(myCancel)
                    // v1.90：同答案（非成功提交）的计时行在 finish/freeze **之后**收 —— 先 abort 会被 freezeScanElapsed 置回可见
                    if (sameAsPrior) p.abortRescan()
                }
                return@execute
            }
            try {
                val usage = arrayOfNulls<Pair<Int, Int>>(1)   // v1.65：接口上报的真实 token 用量
                // 识屏也接本机工具：用户对着题目说「帮我记一下」时，模型能直接建卡。
                // 人格按当前选中的取 —— 记忆 / 建卡都该落在用户此刻正在用的那个 Agent 下。
                val text = Solver(api, toolCtx = AppToolContext(this@OrbService, Personas.Store(this@OrbService).current()))
                    .solve(b64, opts, object : Solver.Callbacks {
                    override fun onPhase(t: String) { main.post { if (live(p, myCancel)) p.phase(t) } }
                    override fun onText(t: String) { main.post { if (live(p, myCancel)) p.append(t) } }
                    override fun onUsage(prompt: Int, completion: Int) { usage[0] = prompt to completion }
                    // v2.2.1：搜索开着时模型可能先吐「我先查一下」再调工具，那句前言已经上屏的要撤回
                    override fun onDiscard(chars: Int) { main.post { if (live(p, myCancel)) p.rewind(chars) } }
                    // v2.3.3：本轮搜到的来源交给面板自己排版 / 折叠（正文里那一行网址串照旧保留给上下文）
                    override fun onSources(hits: List<WebSearch.Hit>) { main.post { if (live(p, myCancel)) p.setSourceHits(hits) } }
                    // v2.7：provider 真实给出的思考增量 —— 只给面板做展示，**绝不进正文 / 历史 / 缓存**。
                    // 面板自己用单调钟量「思考输出用时」（从第一个 delta 起算，首个正文 token 冻结）。
                    override fun onReasoning(delta: String) { main.post { if (live(p, myCancel)) p.onThink(delta) } }
                }, myCancel)
                main.post {
                    // 这一轮相对上一轮完整结果**是否真的换了新全文**（自动扫描才用它决定要不要 pulseReady）
                    val changed = auto && text.isNotBlank() && text != lastAnswer
                    if (text.isNotBlank() && live(p, myCancel)) {   // 关面板时流到一半的残缺答案不进历史 / 缓存 / 追问
                        lastAnswer = text
                        // v2.3.2 错题台账：识屏答题顺手记一笔，供「薄弱点诊断」出图。
                        // 只在**手动**识别时记（auto=false）：自动扫描每 12 秒重看一次同一块屏幕，
                        // 记进去就是同一道题被反复计数，正确率会被瞬间拉偏。
                        // recordText 自己判对错：判不出来（没有批改痕迹 / 不是判断题）就静默不记 ——
                        // 宁可少一条，也不能把"不确定"按"错"入库，那会长期冤枉那个知识点。
                        // v2.7：单词翻译模式**根本不是答题**（词卡的释义行、例句里都可能正好出现「错」/「×」），
                        // 混进台账就是拿字典去污染正确率 —— 这一档整段跳过，不做任何判断。
                        if (!auto && opts.mode != Prompts.MODE_WORD) runCatching { WrongLog.Store(this).recordText(text, System.currentTimeMillis(), "clip") }
                        conv = Solver.Conversation(b64, opts, text)
                        convHistoryId = store.add(History.KIND_SOLVE, optsLabel(opts) + tag, text)
                        if (cacheKey != null) solveCache.put(cacheKey, text)
                        // 用量只做后台记账（prefs.noteUsage），面板状态行不再写 token 数字 / 估算成本
                        val u = usage[0]
                        prefs.noteUsage(u?.first ?: 0, u?.second ?: Usage.estimate(text.length), text.length)
                        // 关键词兜底（v1.65 通识起，v1.66 所有模式、「识别关键词」开着时）：首轮没带清单行 →
                        // 纯文本补问一次把词块补上（不带截图，输入约 1500 字 + 输出一行，不进历史）；带上了就不补问。
                        // v2.7：判据交给 [Prompts.wantsTerms] —— 单词翻译模式**故意**不要关键词清单行
                        // （它只讲一个词，多一行可点击名词直接冲突），照旧用 opts.terms 判就等于每查一个词白跑一次请求。
                        if (Prompts.wantsTerms(opts.mode, opts.terms) && tag == "" && !auto && Terms.parse(text).second.isEmpty()) {
                            worker.execute {
                                if (myCancel.get()) return@execute   // P2-1：已被取消就别再白跑一次网络请求
                                try {
                                    val line = Solver(api).termsLine(text, object : Solver.Callbacks {
                                        override fun onPhase(t: String) {}
                                        override fun onText(t: String) {}
                                    }, myCancel)
                                    main.post {
                                        if (live(p, myCancel) && Terms.parse(line).second.isNotEmpty()) {
                                            p.append("\n\n$line")
                                            prefs.noteUsage(0, Usage.estimate(line.length), line.length)
                                            // 回写缓存：不然缓存里的答案永远缺清单行，同屏每次命中都要再补问一次（白花钱）
                                            if (cacheKey != null) solveCache.put(cacheKey, text + "\n\n" + line)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    if (live(p, myCancel)) {
                        p.done(); p.setAskEnabled(conv != null)
                        // 自动扫描真正换了新全文：让小球 pulseReady 一次（不在这里震动 —— tick 归面板）
                        if (changed) orb?.pulseReady()
                        if (conv != null) scanFails = AutoScanPolicy.succeeded(scanFails)   // v1.86：出答案了 = 这轮没毛病
                        // v1.91：成功出答案 + 新画面指纹 = 记一页（失败 / 取消 / 空白 / 同屏都不走到这里或不会 +1）
                        if (text.isNotBlank()) {
                            scanPages = scanPages.recognized(auto, lightFp, true)
                            syncScanSession()
                        }
                    }
                    finishSolve(myCancel)
                }
            } catch (e: Exception) {
                main.post {
                    // P0-1：这一轮的取消标记已被新一轮替换 / 面板已被换掉 → 旧错误的迟到回调不能动对话上下文、面板或失败上报
                    if (cancel !== myCancel || panel !== p) { finishSolve(myCancel); return@post }
                    // 取消（关面板 / 阅读暂停）：自动扫描要**保住**上一轮的对话关联，手动识别才清
                    if (myCancel.get()) { if (!auto) conv = null; finishSolve(myCancel); return@post }
                    if (auto) {
                        // 自动扫描这一轮失败：丢掉暂存、**保留**上一轮完整结果与对话关联（不 reset、不把错误写进正文）
                        reportScanFailure("接口：" + NetErrors.friendly(e, api.base))
                    } else {
                        p.reset(); p.append("出错：" + NetErrors.friendly(e, api.base)); p.done()
                        conv = null
                    }
                    finishSolve(myCancel)
                    // v1.90：自动轮失败的计时行在 finish/freeze **之后**收 —— 先 abort 会被 freezeScanElapsed 置回可见；
                    // 成功提交的那一轮不走这里，冻结用时照常保留。
                    if (auto) p.abortRescan()
                }
            }
        }
    }

    // ---------------- 框选识别（v1.64）----------------
    private var regionView: RegionOverlay? = null

    /** 真实屏幕大小（含状态栏 / 导航条）—— 截图就是按它截的 */
    private fun realScreen(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { return it.width() to it.height() }
        val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /** 先截整屏 → 盖一层定格截图让人框 → 只把框里那块（小块会放大）发给模型 */
    private fun solveRegion() {
        closeMenu()
        if (solving || regionView != null) return
        dropQuestionGate()   // 框选是另一条入口：先把还挂着的问题浮窗收掉（不回调、不发请求）
        tapAfterAuth = null  // 同理：上一趟双击留下的「授权回来接着做」作废，这一轮已经换人做了
        if (autoScan) stopAutoScan()
        val p = ensurePanel()
        if (!prefs.hasApi()) { p.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }; return }
        val src = screenSource()
        if (src == null) { p.setStatus("还不能截屏 · 点这里授权（直接弹系统的「共享屏幕」框）", clickable = true); askCapture(thenSolve = false); return }
        solving = true; solvingSince = System.currentTimeMillis()
        cancel = Cancel()
        val myCancel = cancel
        orb?.busy = true
        setOverlaysHidden(true)
        main.postDelayed({
            src.capture { b64 ->
                main.post {
                    val bmp = b64?.let { try { val raw = android.util.Base64.decode(it, android.util.Base64.NO_WRAP); android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) } catch (_: Throwable) { null } }
                    if (b64 == null || bmp == null) {
                        setOverlaysHidden(false); finishSolve(myCancel)
                        ensurePanel().setStatus("截屏失败：" + (src.lastError ?: "没拿到画面"), clickable = true); return@post
                    }
                    val (sw, sh) = realScreen()
                    val v = RegionOverlay(this, bmp, sw, sh) { box, whole -> endRegion(bmp, b64, box, whole, sw, sh, myCancel) }
                    val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                        gravity = Gravity.TOP or Gravity.START
                        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    try { wm.addView(v, lp); regionView = v } catch (e: Exception) {
                        bmp.recycle(); setOverlaysHidden(false); finishSolve(myCancel); toast("框选层打不开：" + (e.message ?: ""))
                    }
                }
            }
        }, 120)
    }

    private fun endRegion(bmp: android.graphics.Bitmap, b64: String, box: Region.Box?, whole: Boolean, sw: Int, sh: Int, myCancel: AtomicBoolean) {
        regionView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        regionView = null
        setOverlaysHidden(false)
        if (box == null && !whole) { bmp.recycle(); finishSolve(myCancel); return }
        val img = if (box == null) b64 else try {
            val c = Region.crop(bmp, Region.map(box, sw, sh, bmp.width, bmp.height))
            bitmapToJpegBase64(c, maxShort = 2400).also { c.recycle() }
        } catch (_: Throwable) { null }
        bmp.recycle()
        val p = ensurePanel()
        if (img == null) { finishSolve(myCancel); p.setStatus("裁图失败，再框一次试试"); return }
        undock()
        p.setAskEnabled(false)
        p.reset(); p.setStatus(if (box == null) "正在识别分析…" else "正在识别框里的内容…")
        // 框选识别固定走「通用」模式：只把这一轮真正用的 options 换成通用，**不写回 prefs** —— 设置里存着的模式原样不动。
        // 面板标签 / 历史 / 追问上下文 / 缓存键都按这份 options 算，别沿用设置里的模式名（避免标成「做题」）。
        val opts = prefs.solveOptions().copy(mode = Prompts.MODE_GENERAL)
        p.setModeLabel(optsLabel(opts))
        runSolve(img, false, p, myCancel, opts, prefs.api(), if (box == null) "" else TAG_REGION)
    }

    /**
     * v1.63：识别分享来的 / 相册里的图片（ShareActivity 把图压好存成文件，路径传过来 —— 整张图塞 Intent 会超 1MB 限制）。
     * 不截屏，其余同双击识别：缓存、历史、追问都照常。
     */
    private fun solveImageFile(path: String) {
        val f = java.io.File(path)
        val b64 = try { f.readText() } catch (_: Exception) { null }
        if (b64.isNullOrEmpty()) { toast("图片读不出来，再分享一次试试"); return }
        solveImage(b64)
    }

    private fun solveImage(b64: String) {
        closeMenu()
        if (solving) { toast("还在识别上一个，稍等"); return }
        dropQuestionGate()   // 分享进来的图直接用，别让等着的提问浮窗再抢一轮
        tapAfterAuth = null  // 这也是另一条入口：上一趟双击留下的续接作废
        if (autoScan) stopAutoScan()   // 不然下一轮扫描会把图片的答案冲掉
        val p = ensurePanel()
        if (!prefs.hasApi()) { p.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }; return }
        solving = true; solvingSince = System.currentTimeMillis()
        undock()
        cancel = Cancel()
        val myCancel = cancel
        orb?.busy = true
        p.setAskEnabled(false)
        p.reset(); p.setStatus("正在识别图片…")
        p.setModeLabel(modeLabel())
        runSolve(b64, false, p, myCancel, prefs.solveOptions(), prefs.api(), TAG_IMAGE)
    }

    // ---------------- 追问（v1.57）----------------
    // 带着第一轮提示词和前面所有问答再问一次；回答接在面板正文后面。
    // withNewShot（v1.65 续图追问）= 先截一张最新的屏（原图仍是第一张，以新图为准），之前轮次照常带上。
    // 自动扫描开着会停掉：不然下一轮扫描会把正在看的追问冲掉。
    // v1.66：shown = 面板上显示的那句；q = 真正发给模型的问题（what / why 开关的要求已拼进去）
    // v1.72：directive / lang = 用户自己打的招呼（「只给答案」「英文回我」），单独往下传，不并进 q。
    // 并进去的话风格段落在提示词中间、被后面「用中文」「不超过 N 字」两条压掉，等于没生效。
    private fun ask(shown: String, q: String, withNewShot: Boolean = false, directive: String = "", lang: String? = null) {
        val c = conv ?: return
        val p = panel ?: return
        if (solving) return
        if (autoScan) { stopAutoScan(); toast("已暂停自动扫描（追问时不刷新）") }
        solving = true; solvingSince = System.currentTimeMillis()
        undock()
        cancel = Cancel()
        val myCancel = cancel
        orb?.busy = true
        p.setAskEnabled(false)
        p.beginFollowUp(shown, withNewShot)
        val api = prefs.api()
        if (!withNewShot) { runAsk(c, q, null, p, api, myCancel, directive, lang); return }
        // 续图：截一张新屏（小球 / 面板先藏起来）。截不了（没授权 / 失败）就退回同图追问，不让人白等
        val src = screenSource()
        if (src == null) { runAsk(c, q, null, p, api, myCancel, directive, lang); return }
        setOverlaysHidden(true)
        main.postDelayed({
            src.capture { b64 ->
                main.post {
                    // P0-1：这一轮已被取消 / 被取代 → 面板与 overlay 归别人管，别再恢复隐藏态或写旧面板
                    if (!live(p, myCancel)) return@post
                    setOverlaysHidden(false)
                    if (b64 == null) p.setStatus("没截到新图 · 按原来的截图回答")
                    runAsk(c, q, b64, p, api, myCancel, directive, lang)
                }
            }
        }, 120)
    }

    private fun runAsk(c: Solver.Conversation, q: String, newShot: String?, p: AnswerPanel, api: ApiConfig, myCancel: AtomicBoolean,
                       directive: String = "", lang: String? = null) {
        worker.execute {
            try {
                // v1.65 追问缓存：同屏同问题（含同样设置）直接用上次回答 —— 「举个例子/为什么」重复率高，命中省一整次视觉调用
                // v1.72：directive / lang 必须入键 —— 同一个问题「只给答案」和「讲细一点」答案完全不同，
                // 不入键就会把上次的简短答案当成这次的详细答案发出去。
                // 联网搜索开着时整段绕过追问缓存：搜索答案有时效，而这里是 7 天 TTL —— 既不能读旧答案，也不该写进去。
                val followStore = Cache.followStore(this)
                val fk = if (api.searchConfig.enabled) null else Cache.screenFingerprint(c.jpegB64)?.let {
                    // turns.size 入键：同屏同问题但对话已推进（中间问过别的）不算命中。
                    // v1.91：接口身份（地址 / 模型 / 思考强度）+ 搜索配置的非密指纹也要入键 —— 换了接口或搜索配置，
                    // 上次的回答不能拿来充数；指纹里没有明文 key。
                    // 屏幕提示词精简（同 [Cache.solveKey]）：追问命名空间升到 -2，老风格答案不再命中，旧数据不清
                    Cache.key("follow-screen-2", it, q, c.opts.mode, c.opts.detail, c.opts.novice.toString(), c.opts.customPrompt,
                        newShot?.let { "1" } ?: "0", c.turns.size.toString(), directive, lang ?: "",
                        api.base, api.model, api.thinkingEffort, WebSearch.configFingerprint(api.searchConfig))
                }
                val fhit = fk?.let { followStore.get(it) }
                if (fhit != null) {
                    // 命中也要补对话账：后续追问要接着这条链（与 Solver.followUp 的记账一致）
                    if (newShot != null) c.addExtraShot(newShot)
                    c.turns.add("user" to Prompts.followUp(q, c.opts.novice, directive, lang)); c.turns.add("assistant" to fhit.v)
                    main.post {
                        if (live(p, myCancel)) { p.append(fhit.v); p.setStatus("↺ 缓存：这个问题在这个画面上" + Cache.ago(fhit.created) + "问过，直接用了上次回答"); }
                        afterAsk(p, myCancel)
                    }
                    return@execute
                }
                val usage = arrayOfNulls<Pair<Int, Int>>(1)   // v1.65：接口上报的真实 token 用量
                main.post { if (live(p, myCancel)) p.requestStarted() }   // 追问也是新的一轮请求：思考计时从这里起
                val text = Solver(api).followUp(c, q, object : Solver.Callbacks {
                    override fun onPhase(t: String) { main.post { if (live(p, myCancel)) p.phase(t) } }
                    override fun onText(t: String) { main.post { if (live(p, myCancel)) p.append(t) } }
                    override fun onUsage(prompt: Int, completion: Int) { usage[0] = prompt to completion }
                    override fun onDiscard(chars: Int) { main.post { if (live(p, myCancel)) p.rewind(chars) } }
                    override fun onSources(hits: List<WebSearch.Hit>) { main.post { if (live(p, myCancel)) p.setSourceHits(hits) } }
                    // 追问同样显示真实思考（只做展示；正文 / 历史仍只走 onText）
                    override fun onReasoning(delta: String) { main.post { if (live(p, myCancel)) p.onThink(delta) } }
                }, myCancel, newShot, directive, lang)
                main.post {
                    // 用量只做后台记账（追问每轮重发整段上下文，token 大头在这儿）；面板状态行不再写 token 数字
                    if (text.isNotBlank() && live(p, myCancel)) {
                        val u = usage[0]
                        prefs.noteUsage(u?.first ?: 0, u?.second ?: Usage.estimate(text.length), text.length)
                        if (fk != null) followStore.put(fk, text)
                    }
                    afterAsk(p, myCancel)
                }
            } catch (e: Exception) {
                main.post {
                    if (live(p, myCancel)) p.append("（追问出错：" + NetErrors.friendly(e, api.base) + "）")
                    afterAsk(p, myCancel)
                }
            }
        }
    }

    private fun afterAsk(p: AnswerPanel, myCancel: AtomicBoolean) {
        if (live(p, myCancel)) {
            p.done()
            // 面板里的全文（含追问）存回这次的历史，关了再开还在
            if (lastAnswer != null && conv != null) { lastAnswer = p.text; store.update(convHistoryId, p.text) }
            p.setAskEnabled(conv != null)
        }
        finishSolve(myCancel)
    }

    /** c = 发起这一轮时的取消标记：已经被新的一轮替换了就不动（旧请求的迟到回调别把新一轮的状态清掉） */
    private fun finishSolve(c: AtomicBoolean? = null) {
        if (c != null && c !== cancel) return
        // 本轮结束（成功 / 失败 / 取消）：把自动扫描的「已用时」冻结成 complete（非自动扫描轮是空操作），
        // 思考计时**一律**收口 —— 否则取消路径上那一行会永远停在「正在思考 X.Xs」，心跳定时器还在跑。
        // 两个都是幂等操作，重复调用无副作用。
        freezeScanElapsed()
        panel?.endThink()   // 不判 attached：面板正被让路（藏起来）时同样要停掉心跳并冻结数字
        solving = false; orb?.busy = false; activeAutoRound = false; scheduleDock()
    }
    private var solvingSince = 0L

    // ---------------- 浮层分层：谁在前、谁让路（v2.7）----------------
    /** 现在有没有更上层的东西压着（前台 Activity / 正在为截屏藏浮窗）→ 所有瞬态层都不许露 */
    private fun layersSuppressed(): Boolean = foregroundOwners.isNotEmpty() || captureHidden

    /**
     * 当前**该露在最上面**的那一层，纯判据、不动任何窗口。
     *
     * 次序 = **真实打开先后**（[markLayerOpened] 发的记号，谁大谁在前），不是写死的优先级：
     * 从面板长出来的菜单 / 提问浮窗 / 速记当然是后来的，但「先开了菜单再点小球开面板」这种
     * 反过来的次序也照样按用户看到的那一下来叠。关掉最上面那层，露出的自然就是**最近一个还没关的**前层。
     */
    private fun topVisibleLayer(): Int {
        if (layersSuppressed()) return LAYER_NONE
        var top = LAYER_NONE
        var best = 0L
        if (questionGate != null && gateLayerAt > best) { top = LAYER_GATE; best = gateLayerAt }
        if (quickNote != null && noteLayerAt > best) { top = LAYER_NOTE; best = noteLayerAt }
        if (menu != null && menuLayerAt > best) { top = LAYER_MENU; best = menuLayerAt }
        if (panel?.attached == true && panelLayerAt > best) { top = LAYER_PANEL; best = panelLayerAt }
        return top
    }

    /**
     * 把「谁是前层」落到窗口上：只有前层可见、可触摸，其余一律藏起来。
     *
     * 幂等 —— 任何一层开 / 关 / 被取消之后都可以随口调一次，不必各自维护「要不要恢复」。
     * ⚠️ 这里**只藏不销毁**：面板的正文、追问草稿、阅读位置都留在同一份视图里，
     * 提问浮窗 / 速记的输入草稿也一样 —— 恢复时原样回来，不重建、不丢字。
     */
    private fun syncLayers() {
        val top = topVisibleLayer()
        panel?.takeIf { it.attached }?.setHidden(top != LAYER_PANEL)
        questionGate?.setHidden(top != LAYER_GATE)
        quickNote?.setHidden(top != LAYER_NOTE)
        syncMenuLayer(top == LAYER_MENU)
    }

    /**
     * 菜单被压住 / 恢复：**可见性 + 窗口触摸标志**一起切。
     *
     * 为什么不能只靠 `view.visibility = INVISIBLE`：窗口仍是 WindowManager 里的一块 surface，
     * 视图不画 ≠ 不接触摸（INVISIBLE 只是不参与绘制与命中测试，窗口那一层的输入区域还在，
     * 下层「提问浮窗 / 面板」会被这块看不见的窗口吞掉点击）。所以同时把 FLAG_NOT_TOUCHABLE
     * 加进 lp 并 updateViewLayout；恢复时按**原始** flag 组合写回（[menuBaseFlags]），不臆造。
     *
     * 只做真变化才推（看不见的窗口一次开关就够，不是每帧）；alpha / 位移仍完全归进场弹簧。
     */
    private fun syncMenuLayer(visible: Boolean) {
        val v = menu ?: return
        if ((v.visibility == View.VISIBLE) != visible) v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        val lp = menuLp ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val want = if (visible) menuBaseFlags and nf.inv() else menuBaseFlags or nf
        if (lp.flags == want) return
        lp.flags = want
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    /**
     * 前台卡片页（译文卡等）压着 / 松开：**成对**，可嵌套，见 [suppressForForeground]。
     *
     * 进入抑制：菜单直接收掉（纯瞬态、没有用户数据，锚点也已过时）；
     * 面板 / 提问浮窗 / 速记**只藏不销毁** —— 正文、输入草稿、阅读位置全都留着，恢复时原样回来。
     * 小球**不动**、在途识别**不取消**。
     */
    private fun setForegroundOwner(ctx: Context, on: Boolean) {
        val changed = if (on) foregroundOwners.add(ctx) else foregroundOwners.remove(ctx)
        if (!changed) return   // 幂等：重复 suppress 同一个 ctx / restore 未知 ctx 都是空操作
        if (foregroundOwners.isNotEmpty()) {
            closeMenu()
            hideOrbGuide()
            wordCard.hide()
            knowledgeCard.hide()
        }
        syncLayers()
    }

    /**
     * 藏 / 恢复所有浮层（截屏前用）。只藏不销毁：提问浮窗的输入草稿、面板的读位都留着。
     * 这是「截屏时不把自己的浮窗截进去」的唯一收口 —— 双击 / 框选 / 图片 / 速记 / 续图追问都走它。
     */
    private fun setOverlaysHidden(hidden: Boolean) {
        captureHidden = hidden
        // 新手卡也是浮窗：截图 / 框选前必须摘掉，不能把它截进画面
        if (hidden) hideOrbGuide()
        // 先掐掉进行中的动画（undock 的淡入 / dock 的变淡），否则它会在截图前把小球又拉回可见。
        // 走 settleAt：停帧循环 + 属性写死成终态一步做完；**缩放照原样保留**（闲置时是小球，别被截图这趟撑大）。
        showOrb(if (hidden) 0f else 1f)
        if (hidden) wordCard.hide()   // 单词卡片别被截进去
        syncLayers()                  // 面板 / 提问浮窗 / 速记 / 菜单统一按「压着」处理
    }

    /** 把小球（连同缩放）一步写死到目标不透明度：截屏前藏起来 / 取消后恢复都用它，语义唯一。
     *  **刻意保持立即生效、没有过渡**（截图时序依赖这一点）；位移落点见函数内注释。 */
    private fun showOrb(a: Float) {
        val om = orbMotion
        val o = orb
        when {
            om != null -> {
                // 缩进中的球一步落到**缩进终态**（藏起来那几帧球是透明的，看不见这一步）；
                // 其余情况 tx = 0 就是「就在这里」，半路吸边 / 拖拽中的球不会被拉去别处。
                if (docked && prefs.orbTuck) orbShift?.rebase(sideX(onLeft()) + tuckShift()) else orbShift?.rebase()
                om.settleAt(Anim.Visual(a, o?.scaleX ?: 1f, 0f, 0f))
            }
            o != null -> o.alpha = a
        }
    }

    private fun modeLabel(): String = prefs.modeSummary()

    /**
     * 面板 / 历史里显示的模式标签，按这一轮**真正用的** options 算 —— 框选识别强制走通用时，
     * 不能沿用设置里存着的模式名（否则面板/历史会标成「做题」，和实际发给模型的提示词对不上）。
     * 对普通路径（opts 就是 prefs.solveOptions()）等价于 [Prefs.modeSummary]。
     */
    private fun optsLabel(opts: Solver.Options): String {
        val m = opts.mode
        val name = if (m == Prompts.MODE_CUSTOM) (prefs.activeCustom()?.name ?: Prompts.MODE_LABEL.getValue(Prompts.MODE_CUSTOM))
                   else Modes.label(m, prefs.customModes)
        val parts = mutableListOf(name)
        if (m == Prompts.MODE_EXAM) parts.add(Prompts.DETAIL_LABEL.getValue(opts.detail))
        if (opts.novice) parts.add("小白")
        return parts.joinToString("·")
    }

    // ---------------- 自动扫描（v1.70：不变就跳过、不变久了自己停、熄屏也停）----------------

    /** 本轮自动扫描的起始时刻（[SystemClock.elapsedRealtime]，单调、不受用户改时间影响）；0 = 没有在计时的轮 */
    private var scanRoundStart = 0L
    /**
     * 自动扫描这一轮的「已用时」计时器：每 [SCAN_ELAPSED_MS] 把单调时钟差交给面板的专属行
     * （[AnswerPanel.setScanElapsed]）—— 被动模式下也看得见，让用户知道**这一轮真的在跑**（修「看起来没反应」）。
     * 只在「自动扫描 + 在跑这一轮 + 没被阅读暂停」时刷新；本轮结束（成功 / 失败 / 取消）由 [freezeScanElapsed]
     * 停掉并冻结成 complete，不再变化。倒计时（[scanCountdown]）写的是状态行、与这条专属行互不覆盖。
     */
    private val scanElapsedTick = object : Runnable {
        override fun run() {
            if (!autoScan || readingPaused || !solving || scanRoundStart == 0L) return
            val p = panel
            if (p != null && p.attached) p.setScanElapsed(SystemClock.elapsedRealtime() - scanRoundStart, false)
            main.postDelayed(this, SCAN_ELAPSED_MS)
        }
    }
    /** 本轮开始计时（只对自动扫描轮调；见 [solve]） */
    private fun startScanElapsed() {
        scanRoundStart = SystemClock.elapsedRealtime()
        main.removeCallbacks(scanElapsedTick); main.post(scanElapsedTick)
    }
    /**
     * 停掉计时并冻结成 complete（成功 / 失败 / 取消都走这里）。
     * [scanRoundStart] 为 0 时是空操作 —— 非自动扫描的轮次不受影响。
     */
    private fun freezeScanElapsed() {
        main.removeCallbacks(scanElapsedTick)
        if (scanRoundStart == 0L) return
        val p = panel
        if (p != null && p.attached) p.setScanElapsed(SystemClock.elapsedRealtime() - scanRoundStart, true)
        scanRoundStart = 0L
    }

    /**
     * 把自动扫描的会话行同步给面板：页数 + **真实的**下一轮剩余（单调时钟）+ 是否暂停。
     * 暂停来源：用户点小球阅读（[readingPaused]）、画面没变自停（[scanPausedByStill]）、连败自停（[scanPausedByFail]）。
     * 正在识别**不算**暂停 —— 剩余照实走（scanTick 每拍都会重排真实截止时刻，这一轮结束后下一拍就是下一次尝试）。
     * 不在扫描会话里（手动识别 / 用户关掉扫描）不写，免得普通面板上冒出一行「第 0 页」。
     */
    private fun syncScanSession() {
        if (!autoScan && !readingPaused && !scanPausedByStill && !scanPausedByFail) return
        val p = panel ?: return
        if (!p.attached) return
        val paused = readingPaused || scanPausedByStill || scanPausedByFail
        val remain = if (paused) 0L else AutoScanPolicy.remaining(nextScanAt, SystemClock.elapsedRealtime())
        p.setScanSession(scanPages.pages, remain, paused)
    }

    /**
     * 每一轮：截屏 → 变化预检 → 没变就跳过（不调接口）→ 变了才走缓存 / 接口。
     * scanTick 本身不判断「跳过」—— 那时候还没截屏；跳过判定在 runSolve 里拿到图之后做。
     */
    private val scanTick = object : Runnable {
        override fun run() {
            // 阅读暂停期间节拍器不该再触发（暂停时已 removeCallbacks，这里再兜一道，防迟到的一拍把面板冲掉）
            if (!autoScan || readingPaused) return
            nextScanAt = AutoScanPolicy.deadline(SystemClock.elapsedRealtime(), prefs.intervalSec * 1000L)
            syncScanSession()
            // v1.86：以前「没有截屏来源」这一轮直接静悄悄跳过 —— 自动扫描看起来就是「没反应」，
            // 而用户完全不知道为什么。现在算一次失败（连到上限自己停 + 留通知）。
            // 提问浮窗还挂着：这一轮不算识别中（solving 是 false），但用户正在选问题，别让扫描抢图 / 抢面板
            if (questionGate != null) { main.postDelayed(this, prefs.intervalSec * 1000L); return }
            if (solving) { main.postDelayed(this, prefs.intervalSec * 1000L); return }
            if (screenSource() == null) reportScanFailure("还不能截屏（没授权，或无障碍 / 录屏被系统收回）")
            else solve(auto = true)
            main.postDelayed(this, prefs.intervalSec * 1000L)
        }
    }

    private fun startAutoScan() {
        if (autoScan) return
        // v1.85 免打扰：静音期间不开自动扫描 —— 它的被动答案层会盖住屏幕，正是「全部静音」最不想要的东西。
        // 静默拦住（静音期间不该再冒任何提示）；解除后菜单里同一个「自动扫描」项随时能再开。
        if (prefs.mutedNow()) return
        // v1.59：以前只看录屏令牌 —— 只开了无障碍截屏时自动扫描根本开不起来
        if (screenSource() == null) { ensurePanel().setStatus("请在弹出的框里点「开始」，授权后再开自动扫描"); askCapture(thenSolve = false); return }
        autoScan = true; scanPausedByStill = false; readingPaused = false; orb?.readingPaused = false; orb?.scanning = true; undock()
        // v1.86：重新开一轮扫描，连败计数从零开始（也顺手撤掉上一条「已停下」的通知）
        scanFails = AutoScanPolicy.Fails(); scanPausedByFail = false
        // v1.91：新会话从第 0 页数；第一拍就在眼前（post(scanTick) 立刻跑），截止时刻先记为「现在」——
        // scanTick 拿到真实截屏时机会马上重排成 now+interval，不拿旧会话的页数 / 过期时刻糊弄面板。
        scanPages = AutoScanPolicy.Pages()
        nextScanAt = SystemClock.elapsedRealtime()
        // v1.78：自动扫描 = 被动模式 —— 面板只剩答案文字、面板底全透明、窗口不可触摸（点哪都穿下去），
        // 出口只有长按小球 → 菜单里关掉自动扫描
        panel?.setPassive(true)
        // v1.70：重新开一轮扫描就从零开始数「连续没变化」（P2-2：与 worker 线程同锁，防竞态）
        synchronized(scanLock) { scanStill = AutoScanPolicy.Still() }
        main.removeCallbacks(scanTick); main.post(scanTick)
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)
        syncScanSession()   // 被动层一出现就带「第 0 页 · 下次自动识题」
    }

    private fun stopAutoScan() {
        val wasScanning = autoScan
        autoScan = false; scanPausedByStill = false
        // v1.86：连败状态跟着一起清 —— 「因为连败自己停了」那一位由 reportScanFailure 在停完之后自己置上，
        // 用户手动关（菜单 / 双击 / 追问）就走这里，关掉之后不该继续显示「点这里继续」
        scanFails = AutoScanPolicy.Fails(); scanPausedByFail = false
        // 明确停球 / 关扫描：阅读暂停一并清掉 —— 之后再收面板不能再「恢复扫描」（[resumeFromReading] 会因 autoScan=false 直接返回）
        readingPaused = false
        orb?.readingPaused = false
        // v1.90：显式关扫描要真把「在途的这一轮自动识别」掐掉 —— 否则请求还在跑，回来仍会写被动面板 / 覆盖正文。
        // 只掐自动轮（activeAutoRound）：手动双击 / 框选那一轮不动，免得关扫描顺手把手动识别也杀了。
        // 丢弃这一轮暂存（保留上一轮完整正文与滚动位置），清掉 busy（finishSolve 一并冻结计时），并把可能藏起来的浮窗放出来。
        if (wasScanning && activeAutoRound) {
            if (solving) cancel.abort()
            finishSolve()
            showOrb(1f); syncLayers()
        }
        freezeScanElapsed()   // 在途这一轮若在计时，冻结成 complete（停完就不再变化）
        // v1.90 修（轮间残留）：关扫描时**统一**把「已用时」计时行收掉，不再只限在途自动轮 —— 轮间关扫描时
        // activeAutoRound 已是 false，进不了上面那个分支，上一轮冻结的「扫描 X.Xs」就赖在面板上了。
        // 必须放在 finish/freeze **之后**：finishSolve 里的 freezeScanElapsed 会把计时行置回可见，先收等于没收。
        // abortRescan 只切 INVISIBLE（保留行高与正文文本），不碰手动识别那一轮的请求。
        panel?.abortRescan()
        panel?.cancelPeek()          // 亮着的失败行立刻收掉（关扫描了这一行本来就该归零）
        panel?.setPassive(false)   // 退出自动扫描就把面板外壳装回来
        orb?.scanning = false
        main.removeCallbacks(scanTick); main.removeCallbacks(scanCountdown); main.removeCallbacks(scanElapsedTick)
        if (orb != null) scheduleDock()
    }

    // ---------------- 专注守护的采样节拍（v2.3.4 手机版）----------------

    /** 亮屏且没锁屏。熄屏 / 锁屏期间专注守护与气泡都不工作（既不算专注，也不算离开） */
    private fun awake(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return false
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) return false
        return true
    }

    /**
     * 12 秒一拍：按当前模式取一次证据 → 判三态 → 喂 [FocusGuard] 的状态机 → 该提醒就提醒。
     *
     * ## 三处刻意的设计
     * 1. **与自动扫描彻底解耦**。自动扫描是「把屏幕发给模型识别题目」，这里只是「本机判断算不算学习」：
     *    关掉自动扫描时守护照样工作（[scanTick] 与这里互不影响），auto 轮里那次重复调用已删，不会双计。
     * 2. **证据在 worker / 通道回调里取，状态只在主线程改**。APP 模式的 UsageStats 查询走 Binder，
     *    屏幕模式要等一次截屏回调 —— 两者都不许压在 main 上，否则 12 秒一次的采样会卡住界面。
     * 3. **代次闸门**（[focusGen]）。采样在飞的过程中用户可能关开关 / 切模式 / 熄屏 / 改白名单，
     *    那时回来的旧结果必须**整轮丢弃**：既不改状态、也不记报告、更不提醒。
     */
    private val focusTick = object : Runnable {
        override fun run() {
            focusArmed = false
            if (!running || !focus.enabled) return
            if (focusBusy) return arm()
            val now = System.currentTimeMillis()
            // 熄屏 / 锁屏：断掉这段离开（锁屏那几小时既不算专注、也不算「离开学习」），不解锁就不采样。
            if (!awake()) {
                // 代次一定要动：断点之前起的那一拍若在 cut 之后回来，会把旧时刻的采样追加到
                // 边界之后（append 不排序），报告就又跨过去算了。代次变了就地丢弃。
                focusGen++
                focusBusy = false
                focusLeave = FocusGuard.onScreenOff(focusLeave)
                cutFocus(now)   // 锁屏那段时间不知道人在干什么：报告不许把它接进任何一段
                return arm()
            }
            val gen = focusGen
            if (focus.mode == FocusSettings.MODE_APP) {
                val apps = focus.apps
                if (apps.isEmpty()) {
                    // 白名单空 = 用户还没勾任何一个「学习应用」：这时候说「你离开了学习应用」纯属冤枉。
                    // 不采样、也不攒跨度（把可能在途的那一段清掉），只照常排下一拍。
                    // 报告同样在这里断：没有白名单就没有判据，那段时长不能算学习。
                    focusLeave = focusLeave.reset()
                    focusGen++   // 断点之后在途结果一律作废
                    cutFocus(now)
                    return arm()
                }
                focusBusy = true
                try {
                    worker.execute {
                        // 权限检查也放 worker：AppOps 那一下要走系统服务，不该占 main
                        val access = UsageProbe.hasAccess(this@OrbService)
                        val v = if (!access) FocusGuard.Verdict.UNSURE
                        else try { UsageProbe.verdict(this@OrbService, apps, now) }
                        catch (_: Throwable) { FocusGuard.Verdict.UNSURE }
                        main.post {
                            // busy 先收：它记的是「这一拍还在飞」，与代次无关
                            focusBusy = false
                            // 代次变了（关开关 / 切模式 / 熄屏 / 改白名单）：这条结果整轮丢弃 ——
                            // 连「没权限」的提示都不再报，那是上一个模式的结论
                            if (gen != focusGen) return@post
                            if (!access) {
                                // 看不到 ≠ 离开：权限没了必须把这一段清掉、把用法缓存丢掉，
                                // 否则重新授权后会拿权限丢失前攒下的跨度立刻提醒一次。
                                // 报告也在这里断：没有权限的那段时间一条采样都没有，接起来就是编的。
                                focusLeave = focusLeave.reset()
                                UsageProbe.clear()
                                cutFocus(now)
                                noticeFocusNoAccess()
                            } else {
                                focusNoAccess = false
                                acceptFocus(now, v)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    focusBusy = false   // worker 已收（服务正在销毁）：这一拍作废，别让 busy 永久卡死
                }
            } else {
                // 屏幕模式：先试「只截目标窗口」，通道不支持 / 选不到窗口再退回整屏**实际采图**。
                // 两条路拿到的都只是像素，**不走 runSolve**（不上传、不调模型、不停自动扫描、不碰面板正文）。
                val src = screenSource()
                if (src == null || !src.ready) {
                    // 通道不可用 = 看不到，就不许说「离开」。断掉这一段（连跨度都不攒），并且只提示一次。
                    // 报告同样在这里断：看不到画面时既不是学习也不是离开，前后两段不许连起来。
                    focusLeave = FocusGuard.onScreenOff(focusLeave)
                    cutFocus(now)
                    noticeFocusNoChannel()
                } else {
                    focusChannelWarned = false
                    focusBusy = true
                    // 两张图谁先回来就收口一次，另一个结果直接丢
                    val settled = AtomicBoolean(false)
                    // 超时兜底：通道偶尔不回调（授权被系统收回），不能让 focusBusy 永久卡死；
                    // 同时把这一拍标成已收口 —— 5 秒后才回来的图不许再改状态。
                    // focusBusy 的复位**不看代次**：它记的是「这一拍还在飞」，代次变了这一拍也已经结束，
                    // 留着 true 会让解锁后的第一拍永远 arm() 空转。
                    val guard = Runnable {
                        settled.set(true)
                        focusBusy = false
                    }
                    main.postDelayed(guard, 5_000L)
                    val done: (FocusGuard.Verdict) -> Unit = { v ->
                        main.post {
                            if (settled.compareAndSet(false, true)) {
                                main.removeCallbacks(guard)
                                focusBusy = false
                                // 代次闸门只管「要不要用这个结果」：熄屏 / 切模式前的那张图直接丢
                                if (gen == focusGen) acceptFocus(now, v)
                            }
                        }
                    }
                    // ① 目标窗口截图（API 34+ 无障碍通道）：true = 已开始，cb 恰好一次，回来的是 JPEG
                    val started = try {
                        src.captureForScan { b64 ->
                            runCatching {
                                worker.execute {
                                    done(if (b64 == null) FocusGuard.Verdict.UNSURE else FocusGuard.classifyScreen(b64))
                                }
                            }
                        }
                    } catch (_: Throwable) { false }
                    // ② 没开始（通道不支持 / 选不到窗口）：退回整屏实际采图，就地缩放灰度，仍是本机判版面
                    if (!started) try {
                        src.captureBitmap { bmp ->
                            runCatching {
                                worker.execute {
                                    val v = try { focusVerdict(bmp) } finally { try { bmp?.recycle() } catch (_: Throwable) {} }
                                    done(v)
                                }
                            }
                        }
                    } catch (_: Throwable) { done(FocusGuard.Verdict.UNSURE) }
                }
            }
            arm()
        }

        /** 排下一拍（幂等：已排过就不重复排） */
        private fun arm() {
            if (!running || !focus.enabled || focusArmed) return
            focusArmed = true
            main.postDelayed(this, FOCUS_TICK_MS)
        }
    }

    /** 一小块位图 → 本机三态。缩放口径沿用 [Pixels]（宽 ≤160、高 ≤400），判完这张图就可以扔了 */
    private fun focusVerdict(bmp: android.graphics.Bitmap?): FocusGuard.Verdict = try {
        if (bmp == null) FocusGuard.Verdict.UNSURE else {
            val w = bmp.width.coerceAtMost(Pixels.MAX_W).coerceAtLeast(1)
            val h = (bmp.height.toFloat() * w / bmp.width.coerceAtLeast(1)).toInt().coerceIn(1, Pixels.MAX_H)
            val px = IntArray(w * h)
            if (bmp.width == w && bmp.height == h) bmp.getPixels(px, 0, w, 0, 0, w, h)
            else {
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                small.getPixels(px, 0, w, 0, 0, w, h)
                if (small !== bmp) small.recycle()
            }
            val gray = IntArray(px.size) { i ->
                val c = px[i]
                ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            FocusGuard.classify(FocusGuard.SENSE.sense(gray, w, h)).verdict
        }
    } catch (_: Throwable) {
        FocusGuard.Verdict.UNSURE
    }

    /**
     * 排第一拍（服务刚起来 / 开关刚打开 / 设置刚刷过）。立刻跑一拍，不让用户干等 12 秒。
     *
     * 开拍前先断一次时间线：进程被杀后重启时，上一条采样之后那段时间**没有记录**，
     * 不许把重启前后的两段接成一次连续专注（[cutFocus] 已断过时是空操作）。
     */
    private fun startFocus() {
        if (!focus.enabled || focusArmed) return
        cutFocus()
        focusArmed = true
        main.post(focusTick)
    }

    /**
     * 停节拍、收气泡、丢用法缓存（关开关 / 停球 / 服务销毁 / 改设置）。
     * **不**动 [FocusReport] 里已经记下的采样 —— 但会在时间线上留一个边界（[cutFocus]）：
     * 停采样这段时间既不是学习也不是离开，报告不许把它和前后任何一段接起来。
     */
    private fun stopFocus() {
        focusArmed = false
        main.removeCallbacks(focusTick)
        main.removeCallbacks(focusCompanionTick)
        focusBusy = false
        focusGen++   // 在途结果一律作废：断点之后不许再追加一条（代次闸门见 [focusTick]）
        cutFocus()
        closeFocusBubble()
        // 提醒通知也跟着收：开关关了 / 服务停了还挂在通知栏，用户点开只能看到一条无处可归的提醒
        FocusNotify.cancel(this)
        UsageProbe.clear()
    }

    /**
     * 在专注报告的时间线上打断点：最后一条采样之后的时间**不知道人在干什么**，就不许再累计。
     *
     * 只给已有采样打一个边界（[FocusReport.Store.cut]），不写编出来的标签、不删历史。
     * 没有采样、已经断过时是空操作，所以每 12 秒一次的「判不出来」不会反复整份重写。
     */
    private fun cutFocus(at: Long = System.currentTimeMillis()) {
        runCatching { FocusReport.Store(this).cut(at) }
    }

    /**
     * 设置变了（开关 / 模式 / 白名单 / 阈值 / 陪伴频率）：作废在途结果、丢掉用法缓存、
     * **断掉当前这段离开**（换模式前后的三态不可比），再把节拍按新设置重排。
     */
    private fun applyFocusSettings() {
        focusGen++
        UsageProbe.clear()
        focusLeave = FocusGuard.Leave(lastAt = focusLeave.lastAt, nudges = focusLeave.nudges)
        focusChannelWarned = false
        focusNoAccess = false
        stopFocus()
        if (focus.enabled) startFocus()
        if (focus.companionEnabled) scheduleCompanion(first = true)
    }

    /**
     * 屏幕模式没有截屏通道（没授权 / 被系统收回）：**明确不判分心**，并且在面板上留一句。
     * 只提示一次 —— 每 12 秒报一次「没授权」本身就是打扰，而且用户已经知道要去哪儿开。
     */
    private fun noticeFocusNoChannel() {
        if (focusChannelWarned) return
        focusChannelWarned = true
        // 被动层（自动扫描）没有状态行，用 peekStatus 破例亮一下才看得见；否则直接写状态行
        panel?.peekStatus("专注守护（屏幕模式）还没有截屏通道，先不计时", AutoScanPolicy.PEEK_MS) {
            openPermission()
        }
    }

    /**
     * APP 模式没有「使用情况访问」授权：同样**不判分心**（这一轮是 UNSURE），提示一次，点了去系统设置。
     * 这条是 APP 模式唯一的授权门槛；没有它就只能什么都判不出来，绝不能默认成「你在玩」。
     */
    private fun noticeFocusNoAccess() {
        if (focusNoAccess) return
        focusNoAccess = true
        // 点了直接去系统的「使用情况访问」页 —— 这是 APP 模式唯一的授权入口，别让用户自己翻
        val open = {
            try { startActivity(UsageProbe.settingsIntent(this)) } catch (_: Exception) { openPermission() }
        }
        panel?.peekStatus("专注守护（应用模式）还没有「使用情况访问」权限，先不计时 · 点这里去开", AutoScanPolicy.PEEK_MS, open)
    }

    /**
     * 一轮证据落到状态机（**主线程**）。
     *
     * 提醒的两条硬约束：
     *  1. **这一轮自己就是 NOT_STUDY** 才可能提醒。[FocusGuard.observe] 对 UNSURE 既不推进也不清零，
     *     只靠 [FocusGuard.due] 的话，「权限被收回 / 通道断了 / 桌面」之后跨度照走，
     *     会拿一堆判不出来的采样凑出一条「你离开很久了」。这里再挡一道。
     *  2. 文案按**模式**说事实（见 [focusText]）：APP 模式不能借用屏幕模式的「那几屏不像书页」。
     */
    private fun acceptFocus(at: Long, verdict: FocusGuard.Verdict) {
        if (!focus.enabled) return
        // fp 传 null 是刻意的：[FocusReport.Store] 按 fp 去重，而这个 12 秒节拍会连续命中同一页 ——
        // 给稳定指纹会让「同一页」只记一条、时长断掉；null 让每拍都记，报告里的时长才连续。
        runCatching { FocusReport.Store(this).add(at, verdict, null) }
        focusLeave = FocusGuard.observe(focusLeave, FocusGuard.Sample(at, verdict, null))
        val cfg = FocusGuard.Cfg(leaveMs = focus.leaveMinutes * FocusGuard.ONE_MIN).clamped()
        if (verdict != FocusGuard.Verdict.NOT_STUDY) {
            // 回到学习了：把还挂在通知栏的那条提醒收掉（气泡自己会到点消失，不在这里强关）
            if (verdict == FocusGuard.Verdict.STUDY) FocusNotify.cancel(this)
            return
        }
        // 冷却不用另立字段：[FocusGuard.nudged] 已把时刻记进 Leave.lastAt，[FocusGuard.due] 每次都会看它
        if (!FocusGuard.due(focusLeave, at, cfg)) return
        // 先用**没 +1 之前**的对象出文案：[FocusGuard.tier] 内部就是 nudges + 1，
        // 先 nudged 再出文案会让第一条就按第二档说话（「还在学吗」而不是「回来接着看？」）。
        val title = FocusGuard.nudgeTitle(focusLeave)
        val text = focusText(focusLeave, at, cfg)
        focusLeave = FocusGuard.nudged(focusLeave, at)
        notifyFocus(title, text)
    }

    /** 提醒正文：按模式说这一模式下**真实发生**的事，不借另一个模式的判据 */
    private fun focusText(l: FocusGuard.Leave, now: Long, cfg: FocusGuard.Cfg): String {
        if (focus.mode != FocusSettings.MODE_APP) return FocusGuard.nudgeText(l, now, cfg)
        val dur = l.span(now).coerceAtMost(FocusGuard.MAX_SPAN_MS).let { if (it < FocusGuard.ONE_MIN) "" else FocusGuard.fmtDur(it) }
        val what = if (dur.isEmpty()) "离开选定的学习应用一会儿了" else "离开选定的学习应用 $dur 了"
        return when (FocusGuard.tier(l)) {
            1 -> "$what，回来接着看吧"
            2 -> "$what · 刚才前台大多不是勾选的学习应用，是我看错了就当没看见"
            else -> "$what · 这是今天的第 ${(l.nudges + 1).coerceAtMost(99)} 次 · 想先歇会儿也行"
        }
    }

    /**
     * 把提醒送出去：轻气泡（本机）+ 低优先级通知（切到别的 App 也能看见）。
     * 熄屏锁屏 / 免打扰时**什么都不显示** —— 「全部静音」的语义就是只记不发。
     */
    private fun notifyFocus(title: String, text: String) {
        if (!awake() || prefs.mutedNow()) return
        showFocusBubble(title, text)
        runCatching { FocusNotify.post(this, title, text) }
    }

    // ---------------- 关怀气泡（专注提醒 + 陪伴共用同一个出口）----------------
    /**
     * 现在能不能在球边冒一张气泡。**专注提醒与陪伴共用**这一条判据（两处各写一遍必然走偏）：
     * 没有球 / 没悬浮窗权限 / 免打扰 / 熄屏锁屏 / 前台卡片页压着或正在为截屏藏浮窗 /
     * 待办试弹卡在屏上 / 面板·菜单·提问浮窗·速记开着 / 已经有一条气泡 —— 任一成立就不冒。
     */
    private fun canShowFocusBubble(): Boolean {
        if (prefs.orbHidden) return false                      // 球藏起来了：没有锚点，也没在看着屏幕
        if (orb == null) return false                          // 没有球就没有锚点
        if (!android.provider.Settings.canDrawOverlays(this)) return false
        if (prefs.mutedNow() || !awake()) return false
        if (layersSuppressed()) return false
        if (tryPopCard != null) return false                   // 待办试弹卡正在屏上：不顶掉用户的提醒
        if (topVisibleLayer() != LAYER_NONE) return false      // 面板 / 菜单 / 提问浮窗 / 速记开着
        return focusBubble == null                             // 已经有一条：这一次跳过（不排队、不刷新）
    }

    /**
     * 在球边浮一张小气泡。**专注提醒与陪伴共用这一个窗口**（判据见 [canShowFocusBubble]），
     * 所以永远不可能叠成两条。
     *
     * 纪律（和 [showTryPop] 一致，但更克制）：
     *  - 不抢焦点：[overlayParams] 默认就是 FLAG_NOT_FOCUSABLE，这里也不改；
     *  - 不抢别人的层：别人的卡 / 面板在屏上 → 这一次直接跳过（不关它、不叠上去）；
     *  - 自己会收：[FocusCompanion.STAY_MS] 到点无条件消失，点一下也立刻收；
     *  - 贴边且在屏内：宽度固定 [FocusCompanion.WIDTH_DP]，x 按球在哪边贴到另一边，y 夹进安全区。
     */
    private fun showFocusBubble(title: String, text: String) {
        if (!canShowFocusBubble()) return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Brut.box(this@OrbService, Brut.PAPER, 5f)
            val px = Ui.dp(this@OrbService, 14f)
            setPadding(px, Ui.dp(this@OrbService, 11f), px, Ui.dp(this@OrbService, 12f))
        }
        card.addView(Ui.text(this, title, 12.5f, Brut.HINT).apply { tag = Brut.KEEP })
        card.addView(Ui.text(this, text, 14f, Brut.INK).apply {
            tag = Brut.KEEP
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(this@OrbService, 3f), 0, 0)
        })
        card.setOnClickListener { closeFocusBubble() }   // 点一下只关自己（不抢焦点，所以也不弹任何面板）
        Brut.style(card)
        val w = Ui.dp(this, FocusCompanion.WIDTH_DP)
        val gap = Ui.dp(this, 10f)
        val lp = overlayParams(w, -2)
        lp.x = (if (onLeft()) orbLp.x + orbLp.width + gap else orbLp.x - w - gap)
            .coerceIn(0, (resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        // 竖向：大致对齐球心往上一点。**按可能的最大高度**（标题 + 3 行正文 ≈ 110dp）夹进安全区，
        // 不能只按小球高度夹 —— 那样球靠下时气泡会被屏幕底边切掉（8 秒里只看得见半句）。
        val safe = safeScreenBounds()
        val estH = Ui.dp(this, 110f)
        val screenY = (orbLp.y + orbWinTop) + orbLp.height / 2 - Ui.dp(this, 40f)
        lp.y = screenY.coerceIn(safe.top, (safe.bottom - estH).coerceAtLeast(safe.top)) - orbWinTop
        try { wm.addView(card, lp) } catch (_: Exception) { return }   // 加不上（权限被收）就当没发生
        focusBubble = card
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(160).start()
        main.removeCallbacks(focusBubbleTimeout)
        main.postDelayed(focusBubbleTimeout, FocusCompanion.STAY_MS)
    }

    /** 收掉专注气泡（超时 / 点击 / 停节拍 / 服务销毁 / 关开关）。幂等 */
    private fun closeFocusBubble() {
        main.removeCallbacks(focusBubbleTimeout)
        val v = focusBubble ?: return
        focusBubble = null
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    /**
     * 陪伴气泡的定时器。陪伴是**不定时**的：每次从 [FocusCompanion.nextDelay] 取一个新的随机间隔，
     * 不固定节拍（固定节拍会让它变成计时器）。
     *
     * 被跳过的这一拍**不留账**：免打扰 / 熄屏锁屏 / 正有气泡 / 有试弹卡时什么都不冒，直接排下一拍 ——
     * 攒着「欠你三条」再一口气补发是最烦人的行为。
     */
    private val focusCompanionTick = object : Runnable {
        override fun run() {
            // 停球或关闭陪伴后不再显示；陪伴开关独立于专注守护。
            if (!running || !focus.companionEnabled) return
            // 只有**真能显示**时才从牌堆里抽：被面板 / 免打扰挡掉的那一次不该消耗一句
            if (canShowFocusBubble()) {
                val line = bag().next()
                if (line != null) showFocusBubble(FocusCompanion.HEAD, line)
            }
            scheduleCompanion(first = false)
        }
    }

    private fun bag(): FocusCompanion.Bag {
        val b = focusBag ?: FocusCompanion.Bag().also { focusBag = it }
        return b
    }

    /**
     * 排下一次陪伴气泡。[first] = 刚打开陪伴（或刚刷过设置）：**第一个间隔也走随机区间**，
     * 不从「立刻冒一条」开始 —— 用户刚打开开关就弹气泡会显得像确认框。
     */
    private fun scheduleCompanion(first: Boolean) {
        main.removeCallbacks(focusCompanionTick)
        if (!running || !focus.companionEnabled) return
        // 熄屏期间不排：解锁时 [onScreenOn] 会重新排（锁屏那几小时不攒、也不补发）
        if (!awake()) return
        val ms = FocusCompanion.nextDelay(focus.companionFrequency, kotlin.random.Random(System.nanoTime()))
        main.postDelayed(focusCompanionTick, ms)
        // first = 刚换过配置（开关 / 频率 / 刚起来）：牌堆重洗，别让上一档留下的序列接着用
        if (first) focusBag = null
    }

    /** 因「连续 N 轮画面没变化」暂停：面板上留一行可点的说明，并发一条可点的通知（点一下 = 继续） */
    private fun pauseByStill() {
        if (!autoScan) return
        stopAutoScan()
        scanPausedByStill = true
        val rounds = scanStill.rounds
        val p = panel
        // 用 stillPausedStatus 而不是 status()：这时 autoScan 已经是 false，status() 会按 P2 的规则让位成「关」，
        // 那行「画面 N 轮没变化 · 点一下继续」永远显示不出来（v1.86 顺手修的第二个静默问题）
        if (p != null) p.setStatus(AutoScanPolicy.stillPausedStatus(rounds), clickable = true) { resumeScan() }
        notifyStillPaused(rounds)
        syncScanSession()   // 页数保留，会话行写「已暂停」
        // 倒计时继续跑，把这行说明留在面板上（用户没点通知也不会错过）
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)
    }

    /**
     * v1.86 自动扫描这一轮失败了。被动模式下面板**没有状态行**（`applyPassive` 把 status 收起来、
     * 窗口还不可触摸），所以「截屏失败 / 接口失败 / 授权被收回」写了也永远看不见 —— 坏了整晚都在悄悄重试。
     *
     * 两条对策（判据与文案都在 [AutoScanPolicy]，有单测）：
     *  - 没到上限：[AnswerPanel.peekStatus] **破例亮 3 秒**（只露那一行 + 这 3 秒收得到点击，不还原整块面板）；
     *  - 连到 [AutoScanPolicy.FAIL_LIMIT] 次：**自己停下来**，面板上留一行可点的说明 + 发一条可点的通知。
     *
     * ⚠️ [stopAutoScan] 会把连败状态清零，所以先把这次的状态存到局部变量、停完再放回去 ——
     * 否则「已停 · 连续 0 次失败」这种话就写出来了。
     */
    private fun reportScanFailure(reason: String) {
        val f = AutoScanPolicy.failed(scanFails, reason)
        scanFails = f
        if (!AutoScanPolicy.shouldPauseOnFail(f)) {
            panel?.peekStatus(AutoScanPolicy.failStatus(f), AutoScanPolicy.PEEK_MS) { if (!solving) solve(auto = true) }
            return
        }
        stopAutoScan()
        scanFails = f; scanPausedByFail = true
        panel?.setStatus(AutoScanPolicy.failPausedStatus(f), clickable = true) { resumeScan() }
        notifyScanFailed(f)
        toast("自动扫描连续 ${f.streak} 次失败，已停下：${f.last}")
        syncScanSession()   // 页数保留，会话行写「已暂停」
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)   // 让这行说明一直留在面板上
    }

    /** 「自动扫描连续失败已停下」：和「画面没变」共用提醒渠道与「点一下继续」那条 action */
    private fun notifyScanFailed(f: AutoScanPolicy.Fails) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val resume = PendingIntent.getService(this, 5, Intent(this, OrbService::class.java).setAction(ACTION_RESUME_SCAN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTI_ALERT_ID, Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(Preset.appName + " 自动扫描已停下")
                .setContentText("连续 ${f.streak} 次没成：${f.last} · 点一下继续")
                .setContentIntent(resume).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    /** 通知 / 双击小球 / 面板那行 → 继续扫描 */
    private fun resumeScan() {
        scanPausedByStill = false
        // v1.86：连败清零（不然刚继续就又被上一次失败的状态判成「又停了」）
        scanPausedByFail = false; scanFails = AutoScanPolicy.Fails()
        try { getSystemService(NotificationManager::class.java).cancel(NOTI_ALERT_ID) } catch (_: Exception) {}
        startAutoScan()
        if (autoScan) {
            // 按 startAutoScan 里的真实截止时刻写（start 会立刻跑第一拍，随后 scanTick 重排成 now+interval），
            // 不再拿「interval 秒后」这句和实际行为对不上
            panel?.setStatus(AutoScanPolicy.status(true, false, 0,
                AutoScanPolicy.remaining(nextScanAt, SystemClock.elapsedRealtime())))
            syncScanSession()
        }
    }

    // ---------------- 阅读暂停（v1.89）----------------
    /**
     * 单击小球（触摸手势与读屏「点击」共用同一入口）：
     *  - 阅读暂停中 → 保持面板可读，不关（免得看着看着被自己点掉）；
     *  - 自动扫描进行中 → 暂停扫描专心读（[pauseForReading]，不是普通的「关面板」）；
     *  - 其余 → 普通开 / 关面板（[togglePanel]）。
     */
    private fun orbTap() {
        hideOrbGuide()   // 用户已经动手了，一次性提示卡到此为止
        if (readingPaused) { keepPanelReadable(); return }
        if (autoScan) { pauseForReading(); return }
        togglePanel()
    }

    /**
     * 自动扫描进行中单击小球 = **暂停扫描、专心读**（不是普通单击的「关面板」）。
     *
     * 做三件事：
     *  1. 掐掉在途这一轮 —— 取消后迟到回调会被 live() 作废，暂存的部分流不会覆盖旧全文，**保留**上一轮完整结果与对话关联；
     *  2. 挂起两个定时器（扫描节拍 [scanTick] + 倒计时 [scanCountdown]）与计时器 [scanElapsedTick]，但**不关**这一轮
     *     自动扫描会话（[autoScan] 仍为 true）—— 收起面板时接着扫（见 [onPanelClosed]）；
     *  3. 把上一轮完整答案展开成可读的**活动**面板（没有结果就给等待空态），并置 [OrbView.readingPaused]。
     *
     * 与「画面没变」/「连败自停」两种暂停彼此独立：那时 [autoScan] 已经是 false，单击走的是别的分支。
     */
    private fun pauseForReading() {
        if (!autoScan || readingPaused) return
        // 1) 掐掉在途这一轮：abort 后迟到回调会被 live() 作废，暂存的部分流不会覆盖旧全文。
        // 这里**不调 abortRescan**：它会把本轮冻结的「扫描 X.Xs」切成 INVISIBLE，阅读时完整用时就看丢了。
        // 暂存的下一次 beginRescan 本来就会清，正文显示走 raw、不受它影响。
        if (solving) { cancel.abort(); finishSolve() }
        // 这一轮若已经进入「截屏前藏起来」的隐藏态，要把小球和面板都放出来 —— 阅读是**要看得见**答案。
        // 面板的可见性交给 syncLayers（下面 ensurePanel 里会再对齐一次）：正在为截屏藏浮窗时不能在这里放出来。
        showOrb(1f)
        syncLayers()
        // 2) 挂起定时器与计时器（保留自动扫描会话）
        main.removeCallbacks(scanTick); main.removeCallbacks(scanCountdown); main.removeCallbacks(scanElapsedTick)
        scanRoundStart = 0L
        readingPaused = true
        orb?.scanning = false
        orb?.readingPaused = true
        // 3) 展开活动面板：有上一轮完整结果就展示，没有就给等待空态
        val p = ensurePanel()
        p.setPassive(false)
        val last = lastAnswer()
        if (last != null) {
            // showScanReading：只换正文，不 reset / 不清扫描元数据（showText 会把冻结用时和页数一起抹掉）。
            // 不能按 p.text == last 跳过 —— 被动层里 raw 一直是完整原文，但正文只渲染了抽取后的答案；
            // 跳过就停在「只有答案」的被动画面，scanReading / 会话行 / 冻结计时也不会被置回可见。
            p.showScanReading(last)
            p.setStarred(store.get(convHistoryId)?.star == true); p.setAskEnabled(conv != null)
            p.setStatus("已暂停自动扫描 · 看完收起面板继续扫码")
        } else {
            p.setStatus("已暂停自动扫描 · 还没有识别结果（收起面板继续扫码）")
        }
        syncScanSession()   // 页数 / 冻结用时都保留，只把会话行切成「已暂停」
        orbToFront()
    }

    /**
     * 阅读暂停结束 → 接着自动扫描。**只**在同一轮自动扫描还开着时生效：
     * 明确停球 / 静音 / 授权退出（走 [stopAutoScan] 已清 [readingPaused]）/ 服务销毁都不会误触发。
     */
    private fun resumeFromReading() {
        if (!autoScan || !readingPaused) return
        readingPaused = false
        orb?.readingPaused = false
        orb?.scanning = true
        panel?.setPassive(true)   // 面板多半已摘掉；若还在（异常路径）就落回被动模式
        // 下一轮按正常间隔再来（不立刻扫）：让收起面板的收尾动画先走完，也避免刚收起就被被动层顶回来。
        // v1.91：先把**真实**截止时刻排好（单调时钟），会话行 / 状态行都从它算剩余；页数保留不清零。
        nextScanAt = AutoScanPolicy.deadline(SystemClock.elapsedRealtime(), prefs.intervalSec * 1000L)
        syncScanSession()
        main.removeCallbacks(scanTick); main.postDelayed(scanTick, prefs.intervalSec * 1000L)
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)
    }

    /**
     * 面板关闭（✕ / 横向划掉 / 单击）的统一收口：先掐断在途识别，再判断要不要结束「阅读暂停」接着扫。
     * 走的是 [ensurePanel] 里既有的 onClose 钩子 —— 面板自己的划掉手势也调它，所以只需一处。
     */
    private fun onPanelClosed() {
        panelReadPos = panel?.readPosSnapshot()
        dropQuestionGate()
        cancelCurrentIfSolving()
        panel = null
        markLayerClosed(LAYER_PANEL)
        if (autoScan && readingPaused) resumeFromReading()
        syncLayers()   // 面板没了：把前层交还给还开着的那一层（或全无）
    }

    /** 阅读暂停时单击小球：保持面板可读，**不**触发普通的「关面板」（免得看着看着被自己点掉） */
    private fun keepPanelReadable() {
        val p = panel
        if (p != null && p.attached) { syncScanSession(); return }
        val np = ensurePanel()
        np.setPassive(false)   // 复用的面板可能还带着被动外壳：阅读态必须是可读的活动面板
        val last = lastAnswer()
        // 同样走 showScanReading：不 reset / 不清冻结用时与页数
        if (last != null) { np.showScanReading(last); np.setStarred(store.get(convHistoryId)?.star == true); np.setAskEnabled(conv != null) }
        else np.setStatus("已暂停自动扫描 · 还没有识别结果（收起面板继续扫码）")
        syncScanSession()
    }

    /** 「自动扫描已暂停 · 点一下继续」：用提醒渠道（默认重要性，会真的弹一下） */
    private fun notifyStillPaused(rounds: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val resume = PendingIntent.getService(this, 5, Intent(this, OrbService::class.java).setAction(ACTION_RESUME_SCAN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTI_ALERT_ID, Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(Preset.appName + " 自动扫描已暂停")
                .setContentText("画面 $rounds 轮没有变化 · 点一下继续")
                .setContentIntent(resume).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    /** v1.70 息屏 / 锁屏：自动扫描停下来（息屏了还扫就是纯烧钱）；解锁自动恢复，除非它是被「画面没变」停掉的 */
    private fun onScreenOff() {
        // 专注守护（v2.3.2 / v2.3.4 手机版）：熄屏 = 这段「离开学习」到此为止，采样也停。
        // 不在这里断的话，用户合上手机睡一夜，第二天会看到「你已离开学习 8 小时」——
        // 那是算法在指责一个其实已经去睡觉的人，既不准也不礼貌。
        // 保留 nudges / lastAt：今日提醒计数和冷却不能被「息个屏」重置，否则开关屏幕就能绕过冷却。
        focusGen++                 // 在途的那一轮采样作废：它取的是熄屏之前的证据
        focusLeave = FocusGuard.onScreenOff(focusLeave)
        cutFocus()                 // 锁屏那几小时：报告里既不算学习、也不算离开，前后段不相连
        focusArmed = false
        main.removeCallbacks(focusTick)   // 锁屏期间不采样（不是「稍后再拍」，是不拍）
        closeFocusBubble()                // 气泡不许留在锁屏上
        main.removeCallbacks(focusCompanionTick)
        FocusNotify.cancel(this)          // 通知也不许留在锁屏上
        UsageProbe.clear()                // 解锁后重新按「最近两分钟」查起，不拿锁屏前的包顶替
        if (!autoScan) return
        scanSuspendedByScreen = true
        stopAutoScan()
        panel?.setStatus("屏幕已熄 · 自动扫描已暂停（解锁后自动继续）")
    }

    private fun onScreenOn() {
        // 亮屏：从这一刻重新开始计时。同样保留今日提醒计数与冷却。
        focusLeave = FocusGuard.onScreenOn(focusLeave)
        // 专注守护（v2.3.4）：解锁后从新时间起算，**不补发**锁屏期间那一段；
        // 采样节拍与陪伴定时器都重新排（SCREEN_ON 与 USER_PRESENT 都走这里，两个都是幂等的）。
        if (focus.enabled) startFocus()
        if (focus.companionEnabled) scheduleCompanion(first = false)
        if (!scanSuspendedByScreen) return
        scanSuspendedByScreen = false
        if (scanPausedByStill) return   // 那次是被「画面没变」停的，得用户自己点继续
        startAutoScan()
        if (autoScan) panel?.setStatus("已解锁 · 自动扫描继续")
    }

    // 息屏 / 解锁：运行时注册（服务活着才有意义，服务死了自动注销，不留残留）
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> main.post { onScreenOff() }
                Intent.ACTION_SCREEN_ON -> main.post { onScreenOn() }
                Intent.ACTION_USER_PRESENT -> main.post { onScreenOn() }   // 有密码时解锁走这个，SCREEN_ON 可能在亮屏时就来了
            }
        }
    }
    private fun registerScreenReceiver() {
        try {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenReceiver, f)
        } catch (_: Exception) {}
    }
    private fun unregisterScreenReceiver() { try { unregisterReceiver(screenReceiver) } catch (_: Exception) {} }

    // ---------------- 长按菜单（v1.85：单层） ----------------
    /**
     * 收起长按菜单。
     *
     * [exit] 是退场视觉，默认 [Anim.Visual.HIDDEN]（透明度归零 + 位移回原点）—— 点外部 / 选条目 /
     * 转屏这些**没有手势位移**的关闭都该用它。但**横向划掉**不一样：那时候窗口已经被手指挪开了
     * （位移挂在窗口 x 上，见 [MenuShift]），若还按 HIDDEN 的 tx=0 收，弹簧会把窗口从松手位置
     * 一路拉回原位，撤掉之前就在原位置重画一帧近乎不透明的菜单 —— 也就是那道残影。
     * 所以划掉时由调用方传进「松手那一刻的位移」，窗口停在原地淡出。
     */
    private fun closeMenu(exit: Anim.Visual? = null) {
        val m = menu ?: return
        menu = null
        markLayerClosed(LAYER_MENU)
        menuLp = null
        menuBaseFlags = 0
        val motion = menuMotion
        menuMotion = null
        scheduleDock()
        // 菜单退场了 = 前层交还给下面那一层（面板）：**先**把面板恢复出来，再撤菜单窗口 ——
        // 反过来的话会先露出「什么都没有」的一帧，看着像面板被菜单带走了（同一帧里闪一下）。
        syncLayers()
        val remove = { try { wm.removeView(m) } catch (_: Exception) {} }
        if (motion == null) { remove(); return }
        // 弹出还没落定就被关掉（点到外面 / 选了条目 / 横向划掉）：带着当时的速度接着收，
        // 而不是像原来那样先停在半路、再重播一段 110ms 的淡出。
        // 纵向位移固定 0（[MenuShift] 的 ty 一直是 0），横向用调用方给的松手位移。
        motion.to(exit ?: Anim.Visual.HIDDEN, Anim.Spring.popOut) { remove() }
    }

    /**
     * 菜单的窗口位移适配（v1.85）：把弹簧的「位移」接到**窗口 x/y** 上，而不是视图的 translationX/Y ——
     * 悬浮窗 surface 只有窗口那么大，视图变换超出窗口会被裁掉（和 [WordCard] 的 WindowShift 同一个坑）。
     * 横向划菜单时挪的是整张窗口，看着才是「整张跟着手指走」。
     */
    private inner class MenuShift(private val r: View, private val p: WindowManager.LayoutParams) : Anim.VisualTarget {
        private val baseX = p.x
        private val baseY = p.y
        /** 上一次真推给窗口的坐标：一帧里没变就不重复 updateViewLayout（双推 = 划出 / 跟手掉帧） */
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        override var alpha: Float
            get() = r.alpha
            set(v) { r.alpha = v }
        override var scaleX: Float
            get() = r.scaleX
            set(v) { r.scaleX = v }
        override var scaleY: Float
            get() = r.scaleY
            set(v) { r.scaleY = v }
        override var translationX: Float
            get() = (p.x - baseX).toFloat()
            set(v) { p.x = baseX + v.toInt() }
        override var translationY: Float
            get() = (p.y - baseY).toFloat()
            set(v) { p.y = baseY + v.toInt() }
        /**
         * 一帧只推一次窗口（[Anim.VisualTarget.commit] 的契约）。
         * 以前 tx / ty 的 setter 各推一次 —— 弹簧每帧写四个通道，于是每帧两次窗口布局，正是划出掉帧的来源。
         */
        override fun commit() {
            if (!r.isAttachedToWindow) return
            if (p.x == lastX && p.y == lastY) return
            lastX = p.x; lastY = p.y
            try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
        }
    }

    /**
     * 菜单根布局（v1.85）：**竖向照旧给 [MaxHeightScrollView] 滚，横向长滑 = 把菜单划掉**（和词卡一套手势）。
     * 轴向在第一次移动时定死：竖向占优 = 让 ScrollView 自己滚，这一轮不再接管；横向占优 = 由本视图接管，
     * 跟手挪窗口（见 [MenuShift]），松手过了 [Anim.dragDismissAny] 的阈值就 [closeMenu]，否则弹簧砸回原位。
     */
    private inner class MenuSwipeRoot(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val samples = Anim.DragSamples()   // 横向速度采样（屏幕坐标 rawX，和 WordCard 一套口径）
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragX = 0f
        private var dragging = false
        private var axisLocked = false   // 这轮手势定过轴没有（定过就按那次结果走完，不再改）
        private var horizontal = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    dragX = 0f; dragging = false; axisLocked = false; horizontal = false
                    samples.clear(); samples.add(ev.eventTime, ev.rawX)
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = ev.rawX - downRawX
                    val my = ev.rawY - downRawY
                    if (!axisLocked && Math.hypot(mx.toDouble(), my.toDouble()) > slop) {
                        axisLocked = true
                        horizontal = Math.abs(mx) > Math.abs(my)   // 第一次移动定轴：横向占优才接管
                    }
                    if (axisLocked && horizontal) return true       // 交给本视图；竖向一律返回 false 让 ScrollView 滚
                }
            }
            return false
        }

        /** 接管后的跟手与松手（由 root 的 OnTouchListener 转发）。 */
        fun onMenuTouch(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    samples.add(ev.eventTime, ev.rawX)
                    dragX = ev.rawX - downRawX
                    if (!dragging && Math.abs(dragX) > slop) dragging = true
                    // 跟手直接把窗口挪到手指处（位移写死、速度清零），松手再交给弹簧
                    if (dragging) menuMotion?.settleAt(Anim.Visual(1f, 1f, dragX, 0f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return
                    samples.add(ev.eventTime, ev.rawX)
                    dragging = false
                    val vx = samples.velocity()
                    // 阈值和词卡一个口径：一个最小手感距离（44dp）和「宽度三成」取大；竖向不参与（= 0）
                    val thresholdX = Math.max(Ui.dp(context, 44f).toFloat(), width * 0.3f)
                    // 划掉：窗口**停在手指离开的位置**淡出（tx 就是松手时的 dragX），不能回原位 ——
                    // 回原位会在撤掉窗口之前重画一帧近乎不透明的菜单，看着像闪了一下残影。
                    if (Anim.dragDismissAny(dragX, 0f, vx, 0f, thresholdX, 0f)) closeMenu(Anim.Visual(0f, 1f, dragX, 0f))
                    else menuMotion?.to(Anim.Visual.SHOW, Anim.Spring.release, Anim.Visual(0f, 0f, vx, 0f))
                }
            }
        }
    }
    // ---------------- v1.80 试弹（提醒到点先浮一张小卡）----------------

    /** 正在试弹的那张卡；[tryPopId] = 卡上那条待办的 id（0 = 没有卡） */
    private var tryPopCard: View? = null
    private var tryPopId = 0L
    /** 用户碰过卡（完成 / 稍后 / 叉掉）= 看见了 → 退场时不补通知 */
    private var tryPopSeen = false
    private val tryPopTimeout = Runnable { closeTryPop(escalate = true) }

    /**
     * 试弹一条待办：轻震两下 + 在球边上浮出一张小卡（[TryPop.STAY_MS] 没被碰就自己收回去，退成常规通知）。
     * 一屏只留一张 —— 新的来了先把旧的按「没接住」处理掉（该补的通知补上），不能把提醒吞了。
     */
    private fun showTryPop(id: Long, title: String, dueAt: Long) {
        closeTryPop(escalate = true)
        if (!android.provider.Settings.canDrawOverlays(this)) return
        // v1.85 免打扰：静音期间不浮试弹卡、不震（全部静音 = 只更新历史，不挡屏）。
        // 待办还在列表里，解除后照常；这里先收上一张（escalate 里也判了静音，不会补通知）。
        if (prefs.mutedNow()) return
        // 前台卡片页压着 / 正在为截屏藏浮窗：不冒新卡（提醒还在待办里，下次照常）
        if (layersSuppressed()) return
        val now = System.currentTimeMillis()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Brut.box(this@OrbService, Brut.PAPER, 5f)
            // 右侧 18dp / 下方 15dp 是给 5dp 硬投影让的位；无投影形状下四边等距（16/14）才不会看着偏
            if (noShadow) setPadding(Ui.dp(this@OrbService, 16f), Ui.dp(this@OrbService, 14f),
                Ui.dp(this@OrbService, 16f), Ui.dp(this@OrbService, 14f))
            else setPadding(Ui.dp(this@OrbService, 14f), Ui.dp(this@OrbService, 11f),
                Ui.dp(this@OrbService, 18f), Ui.dp(this@OrbService, 15f))
        }
        // 抬头 + 右上角「✕」：看见了但不想现在处理 —— 叉掉不再补通知，这条提醒还留在待办里
        val head = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, TryPop.heading(dueAt, now), 12.5f, Brut.HINT).apply { tag = Brut.KEEP },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.text(this, "✕", 15f, Brut.HINT).apply {
            tag = Brut.KEEP
            setPadding(Ui.dp(this@OrbService, 10f), 0, 0, Ui.dp(this@OrbService, 2f))
            setOnClickListener { closeTryPop(escalate = false) }
        })
        card.addView(head)
        card.addView(Ui.text(this, title, 16f, Brut.INK).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(this@OrbService, 4f), 0, Ui.dp(this@OrbService, 8f))
        })
        // 小字：和通知正文一个口径（「刚刚到期」「已过期 10 分钟」+ 备注）
        val item = Todo.Store(this).get(id)
        item?.let { Todo.notiBody(it, now) }?.takeIf { it.isNotBlank() }?.let {
            card.addView(Ui.text(this, it, 12f, Brut.HINT).apply {
                tag = Brut.KEEP
                setPadding(0, 0, 0, Ui.dp(this@OrbService, 8f))
            })
        }
        val row = Ui.row(this)
        row.addView(Button(this).apply {
            text = "完成"; tag = Brut.PRIMARY
            setOnClickListener {
                tryPopSeen = true
                Todo.Store(this@OrbService).setDone(id, true)
                TodoReceiver.reschedule(this@OrbService)
                closeTryPop(escalate = false)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@OrbService, 8f) })
        row.addView(Button(this).apply {
            text = "稍后 30 分钟"
            setOnClickListener {
                tryPopSeen = true
                TodoReceiver.snooze(this@OrbService, id)
                closeTryPop(escalate = false)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        Brut.style(card)   // 按钮统一刷成白底黑框 / 薄荷绿主按钮，和 App 其它地方一套

        val w = Ui.dp(this, TryPop.CARD_W_DP)
        val gap = Ui.dp(this, 10f)
        val lp = overlayParams(w, -2)
        // 球在哪边就往另一边放，竖向大致对齐球的中心，再夹进屏幕
        lp.x = (if (onLeft()) orbLp.x + orbLp.width + gap else orbLp.x - w - gap)
            .coerceIn(0, (resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        lp.y = clampY(orbLp.y + orbLp.height / 2 - Ui.dp(this, 48f))
        try { wm.addView(card, lp) } catch (_: Exception) { return }
        tryPopCard = card; tryPopId = id; tryPopSeen = false
        // 试弹的全部存在感：一段短震 —— 强度按这条待办自己的设（记期页能逐条设），没设就跟全局
        Ui.remindVibe(this, item?.vibe ?: Schedule.VIBE_GLOBAL)
        // 从球那一侧「冒」出来：淡入 + 轻微放大
        card.alpha = 0f; card.scaleX = 0.94f; card.scaleY = 0.94f
        card.pivotX = 0f; card.pivotY = Ui.dp(this, 24f).toFloat()
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(AnimGlue.EASE_OUT).start()
        main.removeCallbacks(tryPopTimeout)
        main.postDelayed(tryPopTimeout, TryPop.STAY_MS)
    }

    /**
     * 收掉试弹卡。[escalate] = true 表示「没人接住」（超时 / 被下一条顶掉）→ 补一条常规通知；
     * false = 用户已经看见了（完成 / 稍后 / 叉掉）→ 什么都不补（补了就是重复打扰）。
     */
    private fun closeTryPop(escalate: Boolean) {
        main.removeCallbacks(tryPopTimeout)
        val v = tryPopCard ?: return
        tryPopCard = null
        val id = tryPopId; tryPopId = 0L
        val seen = tryPopSeen; tryPopSeen = false
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        // v1.85 免打扰：静音期间连「没接住 → 补通知」这一步也不做（全部静音）。
        // 待办本身还在列表里，解除免打扰后照常能看到；这里只是不再打扰。
        // silent=true：卡已经震过了，6 秒后这一条补通知必须静默（走 todo_quiet 通道，绝不再震）。
        if (escalate && !seen && id > 0L && !prefs.mutedNow()) {
            Todo.Store(this).get(id)?.takeIf { !it.done }?.let { TodoNotify.notify(this, it, silent = true) }
        }
    }

    /**
     * **无投影**形状（[Shape.shadowDp] <= 0）：判据和 [Ui.glass] / [Brut] / WxSkin 同源，不猜主题 id。
     *
     * 这里只补「不经过 [Ui] / [Brut] 的自绘底」：主操作块（圆角 / 底色 / 字色）以及给硬投影留的
     * 右下内边距（无投影时那截就是白留的偏心）。浮层底板 / 试弹卡本身走 [Ui.glass] / [Brut.box]，
     * 那两支已经自己认形状（[Brut.box] 在无投影形状下还会让显式 shadowDp 作废），不在这儿重复一套。
     */
    private val noShadow get() = Ui.shape.shadowDp <= 0f

    /** 长按菜单：一层画全集（顺序 / 显隐由设置页给，见 [OrbMenu.resolve]） */
    private fun showMenu() {
        if (layersSuppressed()) return   // 前台卡片页压着 / 正在截屏：不许这时再冒一层
        // 长按必须**一定有反应**：提问浮窗开着不再是「拒绝弹菜单」，而是让菜单成为新的前层 ——
        // 提问浮窗（连同它已写的草稿）只让路不销毁，菜单关掉后它原样回来（见 syncLayers）。
        // 菜单要压在面板上面。两个窗口都是 TYPE_APPLICATION_OVERLAY，以前两边同时可见，菜单展开到下方
        // 就盖在答案上，看着像两个窗口打架。现在统一由 syncLayers 决定谁露（菜单成为前层 → 面板整块让路），
        // 面板只藏不销毁：菜单关掉（closeMenu → syncLayers）时正文 / 读位原样回来。
        closeMenu()
        hideOrbGuide()
        undock()
        val ctx = this
        val w = Ui.dp(ctx, 236f)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(ctx, Ui.CARD, 14f)
            val p = Ui.dp(ctx, 6f); setPadding(p, p, p, p)
        }
        fun sep() = card.addView(View(ctx).apply { setBackgroundColor(Ui.HAIRLINE) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(Ui.dp(ctx, 8f), Ui.dp(ctx, 4f), Ui.dp(ctx, 8f), Ui.dp(ctx, 4f)) })
        fun item(label: String, value: () -> String, keepOpen: Boolean, primary: Boolean = false, act: () -> Unit) {
            val r = Ui.row(ctx)
            val p = Ui.dp(ctx, 10f)
            r.setPadding(p, Ui.dp(ctx, 9f), p, Ui.dp(ctx, 9f))
            // 主操作（目前只有「和小Ling聊聊」）加一点分量：主色底 + 加粗 —— 沿用面板「发送」那套
            // Ui.emphasize 的规格，全 App 只有一个主操作的样子。
            //
            // v1.85 配色统一：薄荷底以前先设后**被下面那行 ripple 覆盖掉**（RippleDrawable 的 content 传了
            // null），所以主操作其实一直是透明的 —— 现在把薄荷底当 ripple 的 content 传进去，底和波纹都留住。
            //
            // 这块底不经过 [Ui.glass]，形状得自己认：判据用和 [Ui.glass] 同一条（无投影形状），
            // 于是瑞士下它是 Ui.shape.cornerDp（0 = 直角）、实心信号红（Brut.MINT）、无描边无投影，
            // 和 [Ui.emphasize] / [Brut.swissButton] 的主操作同一个形态；其余形状仍是原来的 8dp 圆角。
            val primaryBg = if (primary) android.graphics.drawable.GradientDrawable().apply {
                setColor(Brut.MINT)
                cornerRadius = Ui.dp(ctx, if (noShadow) Ui.shape.cornerDp else 8f).toFloat()
            } else null
            if (primary) r.setPadding(p, Ui.dp(ctx, 11f), p, Ui.dp(ctx, 11f))
            val t = Ui.text(ctx, label, 14f)
            if (primary) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            // 主操作行底是主色块：新配色下字按主色选可读色（默认配色保持原样，不动）
            if (primary && Ui.theme.palette != null) t.setTextColor(Brut.on(Brut.MINT))
            // 右侧说明：限宽 + 单行 + 截断，并且和标签之间留一条固定的缝 ——
            // 以前它是 wrap_content 直接跟着标签，值一长（「模式 · 扫描间隔 · 小球样式」）就贴上去，
            // 看着像「更多调节模式·扫描间隔…」一整个词（v1.78 修的排版）
            val v = Ui.text(ctx, value(), 13f, Ui.TX3).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = Ui.dp(ctx, 110f)
            }
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(ctx, 8f) })
            // v1.85 配色统一：波纹颜色取 **墨黑 12% 透明**（和设置页那排按钮同一个口径），
            // 不再各自写一个白/灰的裸色值；普通行 content 留空 = 透出菜单底，主操作行透出薄荷底。
            val rippleInk = (Brut.INK and 0x00FFFFFF) or (0x1F shl 24)
            // 波纹遮罩只是裁剪边界，[Ui.glass] 自己会随形状给直角 / 圆角，不再单独判一次
            val mask = Ui.glass(ctx, Ui.CARD, 8f)
            r.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleInk),
                primaryBg, mask)
            r.setOnClickListener { act(); if (keepOpen) v.text = value() else closeMenu() }
            card.addView(r)
        }
        // v1.85：原来是「高频 8 项 + 更多调节」两层，现在收成**一层** —— 二级项（识别模式 / 解析强度 /
        // 纯小白讲解 / 内置接口 / 思考强度 / 自动扫描间隔 / 离屏幕边 / 缩边 / 自定义 / 待办 / 复习 /
        // 隐藏小球）全部并进设置页（设置页都有对应入口，已逐条核对）。长按一眼看全，不用再切层。
        // v2.4：顺序和显隐交给用户（设置页「长按菜单」）。这里是**唯一的渲染处**，
        // 按 OrbMenu.resolve 给出的顺序画；每个 id 对应的行为仍在下面这张 when 里逐条登记。
        val order = OrbMenu.resolve(prefs.orbMenuOrder, prefs.orbMenuHidden)
        var firstDrawn = true
        for (id in order) {
            val spec = OrbMenu.byId(id) ?: continue
            // 分隔线：第一项前面不加（菜单顶部不需要一条线）
            if (spec.sepBefore && !firstDrawn) sep()
            firstDrawn = false
            menuRow(id, ::item)
        }
        placeMenu(card, w)
    }

    /** 把一个菜单 id 接到它的行为上。列表与行为分开，是为了让「排版可自定义」不牵动任何功能逻辑 */
    // ---------------- v2.4 截图速记 ----------------

    /** 当前打开的速记浮层（同一时间只允许一个） */
    private var quickNote: QuickNote? = null

    /**
     * 正在采图的那一波速记：浮窗已藏起来，等 [ScreenSource.captureBitmap] 回来。
     * 和 [quickNote] 分开记，是因为「截图还没回来」这段时间里也必须挡住第二次 start ——
     * 否则两路回调会各弹一个浮层、各持一张图，先弹的那个就成了没人管的孤儿窗口。
     */
    private var quickNoteCapturing = false

    /**
     * 本实例已经销毁（onDestroy 置位，只置不回）。
     *
     * 采图这一波可能在服务销毁后还在飞：`postDelayed` 的等待、`ScreenSource.captureBitmap` 的回调
     * 都会晚到 —— 那时窗口 / overlay 全没了，**不能再** setOverlaysHidden（对着别的实例的浮窗乱写）、
     * 更不能弹速记浮层。晚到的位图没人要了，就地回收。
     *
     * 用实例字段而不是全局 `running`：服务被系统重启后**新**实例会把 running 重新置 true，
     * 旧实例挂着的晚回调不该因此被放行。
     */
    private var quickNoteDestroyed = false

    /**
     * 触发速记后、截屏前等多久。
     *
     * 这段等待不是在等代码，是在等系统合成一帧把小球抹掉：
     * 1 帧 ≈ 16.7ms，默认给 2 帧（32ms），可用 ling_quick_hide_delay 调到 0–500ms。
     */
    private val hideDelayMs: Long
        get() = runCatching {
            android.provider.Settings.Global.getInt(
                contentResolver, "ling_quick_hide_delay", DEFAULT_HIDE_DELAY_MS
            )
        }.getOrDefault(DEFAULT_HIDE_DELAY_MS).toLong().coerceIn(0L, 500L)

    /**
     * 截图速记的完整流程：藏浮窗 → 截一张**完整原始帧** → 弹浮层。
     *
     * 藏浮窗这一步不能省：小球和面板是独立窗口，不藏会被一起截进去 ——
     * 用户想记的是「屏幕上那个东西」，不是「我的悬浮球」。
     * 这段等待就是给窗口真正消失留的时间，太短会截到正在淡出的浮窗。
     *
     * **只截这一次**：浮窗恢复显示之后底下的画面随时会变，保存时再补截一张，
     * 存下的就不是用户当时看到的那一屏。所以这次截到的原始帧在回调这一刻就把所有权
     * 交给浮层，预览、落盘、失败重试全用它，速记链路里没有「再要一张」的路径。
     *
     * 截不到图不放弃：照样弹浮层，退化成纯文字速记，并告诉用户怎么开启截屏。
     */
    private fun startQuickNote() {
        if (quickNoteDestroyed) return   // 实例已在销毁：这条入口死掉，不再藏浮窗 / 采图
        if (layersSuppressed()) return   // 前台卡片页压着 / 别的采图还在跑：不许这时再冒一层
        if (quickNote != null || quickNoteCapturing) { toast("速记开着了"); return }
        quickNoteCapturing = true
        dropQuestionGate()   // 速记要截图：问题浮窗留在屏幕上会被一起截进去、也会跟速记抢焦点
        setOverlaysHidden(true)
        main.postDelayed({
            if (quickNoteDestroyed) { quickNoteCapturing = false; return@postDelayed }   // 等帧期间服务没了：不采图，也别去恢复浮窗
            val src = try { screenSource() } catch (e: Exception) { null }
            // 只在拿不到通道时记一笔：这条是排查「速记变成纯文字」的唯一线索
            if (src == null || !src.ready) {
                android.util.Log.w("QuickNote", "截屏通道不可用 src=" + (src?.javaClass?.simpleName ?: "null") +
                    " ready=" + (src?.ready ?: false) + " why=" + (src?.lastError ?: "-"))
                settleQuickNoteCapture(null, src)
                return@postDelayed
            }
            var done = false
            val guard = Runnable {
                if (done) return@Runnable
                done = true
                settleQuickNoteCapture(null, src)   // 4 秒没回调就当失败，不让用户干等
            }
            main.postDelayed(guard, 4000L)
            src.captureBitmap { bmp ->
                main.post {
                    if (quickNoteDestroyed || done) { bmp?.recycle(); return@post }   // 实例已销毁 / 超时后才回来：图没人要了，就地回收
                    done = true
                    main.removeCallbacks(guard)
                    settleQuickNoteCapture(bmp, src)
                }
            }
        }, hideDelayMs)
    }

    /**
     * 采图这一轮收口（截到 / 截不到 / 超时 / 通道不可用都走这里）：恢复浮窗，再弹浮层。
     * [bmp] 非空时所有权立刻转给 [QuickNote]（连同弹层失败的回收责任），这里不再持有它。
     */
    private fun settleQuickNoteCapture(bmp: android.graphics.Bitmap?, src: ScreenSource?) {
        quickNoteCapturing = false
        if (quickNoteDestroyed) {
            // 实例已销毁：窗口 / overlay 都不在了，恢复显示和弹层都不能做（那是新实例的地盘）；
            // 晚到这张图所有权没人接，就地回收。settle 每波只走一次（done 挡着），不会双回收。
            bmp?.recycle()
            return
        }
        setOverlaysHidden(false)
        openQuickNote(bmp, src)
    }

    /** 真正弹出浮层。[bmp] 为 null 时是纯文字速记；非空时这张图连同回收责任一起交给浮层 */
    private fun openQuickNote(bmp: android.graphics.Bitmap?, src: ScreenSource?) {
        val why = when {
            bmp != null -> null
            src == null || !src.ready -> "没开截屏通道，纯文字记一条"
            src.lastCode == 6 -> "这个画面不能截，纯文字记一条"
            else -> "截屏失败，纯文字记一条"
        }
        if (why != null) toast(why)

        val q = QuickNote(
            ctx = this,
            wm = getSystemService(android.view.WindowManager::class.java),
            prefs = prefs,
            onSaved = { item, shot -> onQuickNoteSaved(item, shot) },
            // 浮层关了（保存成功 / 收起 / 弹层失败）：图已由浮层回收，这里只放引用，
            // 并把前层交还给下面那一层（面板内容原样，不会重开旧答案）。
            onClosed = { quickNote = null; markLayerClosed(LAYER_NOTE); syncLayers() },
        )
        quickNote = q
        markLayerOpened(LAYER_NOTE)
        syncLayers()
        try {
            q.show(bmp)
        } catch (e: Exception) {
            // show 自己已把图回收、并回调 onClosed；这里只兜意外，别再碰那张图
            android.util.Log.w("QuickNote", "打不开速记浮层", e)
            toast("打不开速记浮层")
        }
    }

    /**
     * 存一条速记笔记：先落盘附件文件，再把「笔记 + 附件元数据」放进**同一个事务**里写。
     *
     * 顺序不能反：先插记录再存图，图存失败就会留下一条指向不存在文件的笔记，
     * 卡片流上显示成一张破图，用户也不知道该怎么修。
     *
     * 返回**是否真存上了**（[QuickNote.onSaved] 靠它决定关不关浮层）：
     * 附件写不下去就整条回滚 —— 不插笔记、清掉本轮新写的图片文件，返回 false，
     * 由浮层保留文字和截图让用户重试。以前这里在后台 post 里"存不上也照样存成笔记"，
     * 调用方无从得知，浮层关掉后用户就以为存上了。
     */
    private fun onQuickNoteSaved(item: Note.Item, shot: android.graphics.Bitmap?): Boolean {
        // 回调在浮层的「保存」点击里同步跑：这里同步落盘、同步回答成败，
        // 浮层才能在同一次点击里决定关不关（post 到主线程返回 Boolean 等于永远回答 false）。
        var wrote = false
        val files = ArrayList<String>(2)
        try {
            val store = NoteStore(this)
            val db = store.writableDatabase
            // 旧版本的 FTS rowid 是错的（和 notes.id 错开），历史笔记一条都搜不到。
            // 这里按版本号做一次性重建；同一版本只跑一次。
            runCatching { Notes.ensureFtsSchema(db) }
                .onSuccess { if (it) android.util.Log.i("Notes", "已重建全文索引") }
                .onFailure { android.util.Log.w("Notes", "重建索引失败", it) }
            // 附件只在这一轮真的要用时才写盘；位图只读（归 QuickNote），这里只负责自己写出的文件
            val att = if (shot != null) {
                val a = NoteAttach.save(shot, this, item.uid, item.createdAt)
                if (a != null) {
                    files.add(a.path)
                    if (a.thumb.isNotEmpty()) files.add(a.thumb)
                }
                a
            } else null
            // 有图却写不下 = 这轮不算存上（不回退成「无图笔记」：用户截的图丢了却告诉成功）
            if (shot == null || att != null) {
                Notes.saveWithAttachments(db, item, listOfNotNull(att))
                wrote = true
            }
        } catch (e: NoteVault.IndexException) {
            // 正文和附件已持久化；索引失败不能清图或把已保存内容当成失败重试。
            android.util.Log.w("QuickNote", "内容已保存，索引需要重建", e)
            toast("内容已保存，索引需要在记事中重建")
            prefs.noteTagsUsed(NoteSyntax.tags(item.body), item.createdAt)
            return true
        } catch (e: Exception) {
            wrote = false
            android.util.Log.w("QuickNote", "保存失败", e)
        }
        // 位图所有权：shot 是 QuickNote **借给**这里的，只读。不管成败都别 recycle ——
        // 成功时由浮层关窗时回收，失败时浮层还要拿同一张图重试（这里 recycle 了，重试就是一张废图）。
        if (!wrote) {
            // 文件发布前保存失败：仅清理本轮尚未归属笔记的图片。
            if (files.isNotEmpty()) NoteAttach.deleteFiles(this, files)
            return false
        }
        prefs.noteTagsUsed(NoteSyntax.tags(item.body), item.createdAt)
        toast(if (shot == null) "已存（无图）" else "已存笔记")
        return true
    }
    private fun menuRow(id: String, item: (String, () -> String, Boolean, Boolean, () -> Unit) -> Unit) {
        val ctx = this
        // v2.4：用户给这一项起过昵称就用昵称，否则用默认标题。
        // 包一层而不是改每个 item() 的第一个参数：那样等于把同一句「取昵称」写九遍，
        // 而且以后加新项时很容易漏掉（漏了的那项就永远改不了名）。
        val nicks = prefs.orbMenuNicks
        val name = OrbMenu.labelOf(id, nicks)
        when (id) {
            OrbMenu.ID_CHAT -> item(name, { "" }, false, true) { try { startActivity(ChatActivity.intent(ctx)) } catch (e: Exception) { toast("打不开聊天页") } }
            // v1.88：把「框选识别」加回悬浮球菜单 —— 功能一直都在（Region.kt / RegionOverlay / solveRegion /
            // endRegion / TAG_REGION），快捷开关（ACTION_REGION）和桌面快捷方式也都能进，唯独长按菜单没有入口。
            // 点和菜单里别的「要截屏的动作」一样：先让菜单收起（closeMenu 有 popOut 动画，等它收完再定格），
            // 再走**同一个** solveRegion() —— 和磁贴 / 快捷方式共用一条链路，不另起一套、不重复发布。
            // v2.4 截图速记：和「框选识别」同样的节奏 —— 等菜单收完再截图，否则菜单自己会被截进去。
            // 不同的地方是截完直接弹浮层，不进识别链路。
            OrbMenu.ID_NOTE -> item(name, { "" }, false, false) { main.postDelayed({ startQuickNote() }, 260) }
            OrbMenu.ID_REGION -> item(name, { "" }, false, false) { main.postDelayed({ solveRegion() }, 260) }
            // v2.7 双击模式：点一下就地切到下一档（先问问题 → 做题 → 单词翻译 → 回来），菜单不关，
            // 右侧值立刻换成新说法并 Toast 一句「已切换为…」—— 只改「双击」这一条入口，
            // 设置里存着的识别模式原样不动（自动扫描 / 快捷开关 / 面板模式抽屉都还按设置来）。
            OrbMenu.ID_TAPMODE -> item(name, { OrbMenu.tapLabel(prefs.orbDoubleTap) }, true, false) {
                prefs.orbDoubleTap = OrbMenu.nextTap(prefs.orbDoubleTap)
                toast("已切换为" + OrbMenu.tapLabel(prefs.orbDoubleTap))
            }
            OrbMenu.ID_AUTOSCAN -> item(name, { if (autoScan) "开 · ${Prefs.fmtInterval(prefs.intervalSec)}一次" else if (scanPausedByFail) "已停 · 连败 ${scanFails.streak} 次" else if (scanPausedByStill) "已暂停 · 点这里继续" else "关" }, true, false) { if (autoScan) stopAutoScan() else if (scanPausedByFail || scanPausedByStill) resumeScan() else { startAutoScan() } }
            // v1.85：老的「来个单词」（强制弹一张）换成「弹单词」—— 它现在是**自动弹单词的总开关**。
            // 值跟着 wordOn / 间隔走；打开的那一刻顺手来一张（showWord 在免打扰里会自己跳过），别像个没反应的开关。
            OrbMenu.ID_WORD -> item(name, { if (prefs.wordOn) "开 · 每 ${Prefs.fmtInterval(prefs.wordEverySec)} 自动弹" else "关" }, true, false) {
                prefs.wordOn = !prefs.wordOn
                if (prefs.wordOn) showWord(force = true)
            }
            OrbMenu.ID_HISTORY -> item(name, { "" }, false, false) { startActivity(Intent(ctx, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            OrbMenu.ID_TRANSLATE -> item(name, { Translate.SHORT[Translate.plan(prefs.translateEngine, prefs.deepl().configured, prefs.baidu().configured, prefs.hasApi()).firstOrNull() ?: ""] ?: "" }, false, false) { translateClipboard() }
            OrbMenu.ID_SETTINGS -> item(name, { "" }, false, false) { startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // v1.85 免打扰：按一下静音 30 分，再按一下立刻解除。
            OrbMenu.ID_MUTE -> item(name, { Mute.label(System.currentTimeMillis(), prefs.muteUntil) }, true, false) {
                val now = System.currentTimeMillis()
                if (Mute.active(now, prefs.muteUntil)) prefs.muteUntil = 0L
                else { prefs.muteUntil = Mute.until(now); stopAutoScan() }   // 静音就把会挡屏的自动扫描先停了
            }
            // 退出 = **只停小球、保留授权**（和通知里那条「停止小球」同一条路径，见 [stopOrb]）：
            // 不再 disableSelf 无障碍、不杀进程，所以截屏授权保留 —— 下次手动启动无需重新授权；清理交给 onDestroy。
            OrbMenu.ID_STOP -> item(name, { "下次启动无需重授" }, false, false) { stopOrb() }
        }
    }

    /** 菜单全部画完之后：定位置、挂滚动、装动画。从 showMenu 里拆出来只是因为那个函数太长 */
    private fun placeMenu(card: LinearLayout, w: Int) {
        val ctx = this
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val dm = resources.displayMetrics
        // undock 的回弹此刻可能还在半路（缩进位置→贴边位）：定位按**展开后的贴边位**算，
        // 和球这一段的落点一致，别拿还在路上的 orbLp.x 当锚。
        val orbX = sideX(onLeft())
        lp.x = if (orbX > dm.widthPixels / 2) orbX - w + orbLp.width / 2 else orbX + orbLp.width / 2
        lp.x = lp.x.coerceIn(Ui.dp(ctx, 6f), dm.widthPixels - w - Ui.dp(ctx, 6f))
        val maxH = (dm.heightPixels - Ui.statusBarHeight(ctx) - Ui.navBarHeight(ctx) - Ui.dp(ctx, 32f)).coerceAtLeast(Ui.dp(ctx, 200f))
        val scroll = MaxHeightScrollView(ctx, maxH).apply { addView(card); isVerticalScrollBarEnabled = true }
        // v1.85：根换成会认手势的 [MenuSwipeRoot] —— 竖向仍交给 ScrollView 滚，横向长滑 = 划掉菜单
        val root = MenuSwipeRoot(ctx)
        root.addView(scroll)
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) closeMenu() else root.onMenuTouch(ev)
            false
        }
        fun place(first: Boolean) {
            card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            val shownH = Math.min(card.measuredHeight, maxH)
            lp.y = (orbLp.y + orbLp.height).let { if (it + shownH > dm.heightPixels - Ui.navBarHeight(ctx) - Ui.dp(ctx, 8f)) orbLp.y - shownH else it }
                .coerceIn(Ui.statusBarHeight(ctx) + Ui.dp(ctx, 4f), Math.max(Ui.statusBarHeight(ctx) + Ui.dp(ctx, 4f), dm.heightPixels - shownH - Ui.navBarHeight(ctx)))
            if (first) {
                // v1.85：弹簧的落点接到 MenuShift（挪窗口 x，而不是视图平移 —— 否则横向划出会被窗口裁掉）
                val m = AnimGlue.SpringAnimation(root, Anim.Spring.popIn, target = MenuShift(root, lp))
                menuMotion = m
                m.settleAt(Anim.Visual.HIDDEN)   // 透明起点先写死，再加窗口，最后走弹簧
                wm.addView(root, lp)
                m.to(Anim.Visual.SHOW)
                menu = root
                // 记下窗口参数与**原始** flag：被压住时加 FLAG_NOT_TOUCHABLE（视图 INVISIBLE 挡不住窗口触摸），
                // 恢复时按这份原始组合写回，不臆造调用方没设过的 flag。
                menuLp = lp
                menuBaseFlags = lp.flags
                markLayerOpened(LAYER_MENU)
                Ui.popTick(ctx)   // v1.73：菜单弹出时轻轻震一下
                orbToFront()
                syncLayers()   // 菜单成为前层：面板整块让路（藏起来，不销毁）
            } else try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        place(true)
    }
}
