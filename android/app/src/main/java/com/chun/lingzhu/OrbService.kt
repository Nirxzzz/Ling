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
import android.view.animation.DecelerateInterpolator
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
        /** 自动扫描「已用时」刷新间隔：250–500ms 区间取中，够顺滑又不费电（单调时钟计时见 [scanElapsedTick]） */
        private const val SCAN_ELAPSED_MS = 350L
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
    private lateinit var orbLp: WindowManager.LayoutParams
    private var panel: AnswerPanel? = null
    /** 小球是否正在接收一次触摸（DOWN 到 UP 之间）。orbToFront 据此决定同步重排还是等这次手势结束（P0-2） */
    private var orbTouchDown = false
    /** 手势进行中收到的「把小球重新挂一遍」请求：等这次触摸 UP 之后再执行 */
    private var pendingOrbToFront = false
    private var menu: View? = null
    /** 菜单的弹簧动画（菜单每次重开都新建一个视图，所以动画器也跟着换） */
    private var menuMotion: AnimGlue.SpringAnimation? = null

    private val a11ySource = A11ySource { main.post(it) }
    private var solving = false
    private var cancel = Cancel()
    private var autoScan = false
    /** 在途的这一轮是不是自动扫描轮：显式关扫描只掐自动轮（[stopAutoScan]），手动双击 / 框选那一轮不动 */
    @Volatile private var activeAutoRound = false
    // ---------------- 自动扫描省钱（v1.70）----------------
    /** 上一轮的画面轻量指纹 + 连续几轮没变化（纯逻辑在 AutoScanPolicy） */
    private var scanStill = AutoScanPolicy.Still()
    /** 下一轮扫描的时刻（毫秒），状态行倒计时用 */
    private var nextScanAt = 0L
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
                return
            }
            val p = panel
            // 正在识别时状态行归识别流程管，不抢；v1.86 「破例亮着的失败行」也不抢（不然失败原因只活 1 秒）。
            // 自动扫描「已用时」是面板的**专属行**（setScanElapsed），与这里的状态行互不覆盖。
            if (!solving && p != null && p.attached && !p.peeking) {
                p.setStatus(AutoScanPolicy.status(autoScan, scanPausedByStill, scanStill.rounds, nextScanAt - System.currentTimeMillis()))
            }
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
            Ui.theme = prefs.theme(); panel?.applyTheme(); orb?.invalidate()
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
        Ui.theme = prefs.theme()
        lastSystemDark = prefs.systemDark()   // v1.67：记住当前系统深浅色，转屏/切深色时才知道变了没
        prefs.orbHidden = false   // 每次启动都先露出来（隐藏只管这一次运行）
        prefs.onChange(themeListener)
        WatchdogReceiver.kick(this)   // v1.65：服务在跑就保证看门狗闹钟排着（进程被清后的兜底层）
        // v1.85：服务每次起来都把「最近一条待办 + 简报 + 复习」的闹钟重排一遍。
        // 闹钟能跨进程存活，但**不跨重启**，而且用户可能在我们没察觉的时候清过数据 —— 这里重排是幂等的
        // （同一条 PendingIntent 用 FLAG_UPDATE_CURRENT 覆盖），成本一次几十微秒，换来「提醒一定排着」。
        try { TodoReceiver.kick(this) } catch (_: Exception) {}
        registerScreenReceiver()   // v1.70：息屏 / 解锁 —— 自动扫描跟着停、跟着起（运行时注册，onDestroy 注销，不留残留）
        running = true
        self = this
        OrbTile.refresh(this)
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
                Ui.theme = prefs.theme(); panel?.applyTheme(); applyOrbSize(); orb?.invalidate()
            if (wordCard.showing) wordCard.hide()   // 配色变了：开着的词卡收掉重弹（不评分、不联网）
            if (knowledgeCard.showing) knowledgeCard.hide()   // 知识卡同理
            }
            return if (prefs.orbWanted) START_STICKY else START_NOT_STICKY
        }
        val delay = intent?.getLongExtra(EXTRA_DELAY, 0L) ?: 0L
        if (intent?.action == ACTION_REGION && orb != null) { main.postDelayed({ solveRegion() }, delay); return START_STICKY }
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
        }
        intent?.getStringExtra(EXTRA_IMAGE_FILE)?.let { f -> main.postDelayed({ solveImageFile(f) }, 250) }
        // [M2M3] v1.67：服务活着就保证三件事——随手记常驻通知在、早晚简报闹钟排上、
        // （服务被杀重启后这三样靠这里恢复；Memo.post / BriefingReceiver.kick 都是幂等的）
        try { MemoNotify.post(this) } catch (_: Exception) {}
        try { BriefingReceiver.kick(this) } catch (_: Exception) {}
        try { ReviewReceiver.kick(this) } catch (_: Exception) {}   // 审查A：默认开的复习提醒也要有人排闹钟
        // 小球没开着时从快捷方式 / 快捷开关进来：先起小球，再识别 / 框选
        if (intent?.action == ACTION_REGION) main.postDelayed({ solveRegion() }, 600)
        if (intent?.action == ACTION_SOLVE) main.postDelayed({ solve(auto = false) }, 600)
        // 从「共享屏幕」框回来：等系统的框完全消失（别把它截进去）再接着识别
        if (solveAfter && capture != null) main.postDelayed({ solve(auto = false) }, 700)
        // v1.62：无障碍模式以前只发普通通知、不算前台 —— 系统清内存时小球说没就没。现在也挂前台（specialUse 类型）
        // 等小球窗口真正显示出来再挂前台：Android 14 起「有可见悬浮窗」才算豁免后台起前台（系统按 STICKY 重启时就靠这个）
        if (!foreground) main.postDelayed({ if (!foreground && running) keepAliveForeground() }, 300)
        return START_STICKY
    }

    override fun onDestroy() {
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
        cancel.abort()
        capture?.release(); capture = null
        closeMenu(); panel?.remove(); panel = null
        regionView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; regionView = null
        orb?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        orb = null
        orbMotion = null   // 动画器握着视图；视图没了必须一起放掉
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
     * 明确停球额外撤掉**只属于小球保活**的两条闹钟：[WatchdogReceiver.cancel]（看门狗）与 [onDestroy] 里
     * 带 orbWanted 门槛的 [WordAlarm]（弹词时钟）。**不碰**待办 / 早晚简报 / 复习提醒（[TodoReceiver] /
     * [BriefingReceiver] / [ReviewReceiver]）—— 那几条提醒与小球开着与否无关，误清会把用户的提醒搞丢。
     */
    private fun stopOrb() {
        prefs.orbWanted = false
        // 用户明确停球：撤掉看门狗闹钟，别再留一个没意义的闹钟空转（退避档位/计数不动）。
        WatchdogReceiver.cancel(this)
        // 「停止小球」也要停知识卡弹窗时钟（独立于弹单词）：这里是显式 cancel —— 此刻 running 仍为 true，
        // 调 schedule 会重排而不是撤（同 [WordAlarm] 在 onDestroy 里的取舍）。
        KnowledgeAlarm.cancel(this)
        stopSelf()
    }

    /** 没开通知权限时 notify 会被系统静默丢掉 —— 那就只能靠快捷开关（OrbTile）或菜单 */
    private fun addOrbSafely(): Boolean = try { addOrb(); true } catch (e: Exception) {
        orb?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; orb = null; orbMotion = null; false
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
        if (wordCard.showing || panel?.attached == true || menu != null) {
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
            closeMenu(); stopAutoScan(); cancelHold()
            // P0-1：隐藏小球也是「关面板」的一种 —— 同样先掐断在途识别，否则 solving 不复位、恢复小球后双击没反应
            cancelCurrentIfSolving()
            // 面板直接关掉：只调透明度的话，透明的面板还会挡住游戏的触摸
            panel?.let { if (it.attached) it.fadeOutAndRemove() }; panel = null
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
        // 首次启动（没存过位置，orbX / orbY 还是哨兵 -1）：贴左边 + 竖直正中对齐。
        // 竖直位置减掉小球自身高度 —— 落在屏幕「中心」而不是让小球顶边压在中线上。
        // 存过位置（>=0）就照存的原样，绝不覆盖用户手动摆的位置（-1 才是默认）。
        orbLp.x = sideX(if (prefs.orbX >= 0) prefs.orbX + size / 2 < dm.widthPixels / 2 else true)
        orbLp.y = clampY(if (prefs.orbY >= 0) prefs.orbY else (dm.heightPixels - orbLp.height) / 2)
        orbA11y(v)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { orbTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                cancelHold()
                // v1.70：因画面没变化被自动暂停的 —— 双击小球当作「继续」，不用去翻通知
                if (scanPausedByStill) { scanPausedByStill = false; main.post { resumeScan() } }
                solve(auto = false); return true
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
                    undock()
                    dragging = false; holdFired = false; downX = ev.rawX; downY = ev.rawY; startX = orbLp.x; startY = orbLp.y
                    // 老式 Animator 若还挂在这个视图上（历史代码路径），先掐掉 —— 两套动画抢同一批属性最难看
                    view.animate().cancel()
                    orbMotion?.to(Anim.Visual(1f, PRESS_SCALE, 0f, 0f), Anim.Spring.press)
                    cancelHold(); main.postDelayed(holdStart, HOLD_DELAY)
                    feed(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && !holdFired && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true; cancelHold(); feed(ev, MotionEvent.ACTION_CANCEL)
                    }
                    if (dragging) { orbLp.x = startX + dx.toInt(); orbLp.y = startY + dy.toInt(); wm.updateViewLayout(view, orbLp) }
                    else if (!holdFired) feed(ev)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    // 松手回弹：换成弹簧之后带一点过冲（ζ=0.55，约 12%），替代原来的 OvershootInterpolator(2.2)。
                    // 关键是**可打断**：弹到一半又按下去，会带着当时的速度接着走，不会停住重来。
                    orbMotion?.to(Anim.Visual.SHOW, Anim.Spring.release)
                    if (dragging) snapToEdge(view)
                    // 菜单已经弹了：这次松手不能再算单击（不然菜单刚出来面板又开了）
                    else feed(ev, if (holdFired) MotionEvent.ACTION_CANCEL else ev.action)
                    // v1.59：拖完一定要清掉 —— 以前一直是 true，dock() 看到「还在拖」就无限往后推，小球再也不缩回去
                    dragging = false
                    // P0-2：这次触摸到此结束。把被推迟的重排放到 UP 之后再执行 —— 此时手势已完整派发完，
                    // 不会再拆到「正在接收手势」的窗口。
                    orbTouchDown = false
                    if (pendingOrbToFront) { pendingOrbToFront = false; main.post { orbToFrontNow() } }
                    scheduleDock()
                }
            }
            true
        }
        v.alpha = 0f
        wm.addView(v, orbLp)
        v.post { measureOrbWinTop() }
        val motion = AnimGlue.SpringAnimation(v, Anim.Spring.enter)
        orbMotion = motion
        motion.settleAt(Anim.Visual.HIDDEN)   // 起点写死（透明），再加进来走弹簧
        motion.to(Anim.Visual.SHOW, Anim.Spring.enter)
        applyEdgeExclusion()
        scheduleDock()
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

    /** 上下也别压到状态栏和底部横条 */
    private fun clampY(y: Int): Int {
        val dm = resources.displayMetrics
        val top = Ui.statusBarHeight(this)
        val bottom = dm.heightPixels - orbLp.height - Ui.navBarHeight(this) - Ui.dp(this, 8f)
        return y.coerceIn(top, Math.max(top, bottom))
    }

    private fun applyEdgeExclusion() {
        val v = orb ?: return
        if (Build.VERSION.SDK_INT < 29) return
        // 贴近 / 缩进边里时小球压在返回手势区上：申请豁免，按它不会被当成返回
        v.systemGestureExclusionRects = if (!prefs.orbFar || prefs.orbTuck) listOf(android.graphics.Rect(0, 0, orbLp.width, orbLp.height)) else emptyList()
    }

    /** 设置里切了「离屏幕边远一点」：小球立刻挪到新位置 */
    private fun applyEdgeMode() {
        val v = orb ?: return
        undock()
        orbLp.x = sideX(onLeft()); orbLp.y = clampY(orbLp.y)
        if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
        prefs.orbX = orbLp.x; prefs.orbY = orbLp.y
        applyEdgeExclusion()
        scheduleDock()
    }

    /** 设置里改了小球大小（v1.60）：原地换大小，还贴着原来那条边 */
    private fun applyOrbSize() {
        val v = orb ?: return
        val left = onLeft()
        undock()
        val size = Ui.dp(this, prefs.orbSizeDp.toFloat())
        orbLp.width = size; orbLp.height = size
        orbLp.x = sideX(left); orbLp.y = clampY(orbLp.y)
        if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
        prefs.orbX = orbLp.x; prefs.orbY = orbLp.y
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
    private fun dock() {
        val v = orb ?: return
        // v1.59：识别卡住（截图回调一直没来）超过 150 秒就当它结束了 —— 不然小球永远不缩、双击也没反应
        // 看门狗不关闭面板：取消旧轮后也恢复仍挂载的面板，迟到 capture 已被 live 守卫作废。
        if (solving && System.currentTimeMillis() - solvingSince > 150_000) {
            cancelCurrentIfSolving()
            panel?.setHidden(false)
        }
        if (docked || solving || autoScan || menu != null || dragging) { scheduleDock(); return }
        docked = true
        v.pivotX = if (onLeft()) 0f else v.width.toFloat()
        v.pivotY = v.height / 2f
        orbMotion?.to(Anim.Visual(prefs.orbIdleAlpha / 100f, prefs.orbIdleScale / 100f, 0f, 0f), Anim.Spring.enter)
        if (prefs.orbTuck) {
            val out = (orbLp.width * 0.3f).toInt()   // 缩小后约宽 60%（闲置比例自定义后取近似值），挪出去一半
            animateOrbX(if (onLeft()) -out else resources.displayMetrics.widthPixels - orbLp.width + out)
        }
    }
    private fun undock() {
        main.removeCallbacks(dockRunnable)
        val v = orb ?: return
        if (!docked) return
        docked = false
        tuckAnim?.cancel()
        val x = sideX(onLeft())
        if (orbLp.x != x) { orbLp.x = x; if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp) }
        // 数值上就是原来 undock 的 120ms 那一档（popOut），恢复要快
        orbMotion?.to(Anim.Visual.SHOW, Anim.Spring.popOut)
    }
    private var tuckAnim: ValueAnimator? = null
    private fun animateOrbX(target: Int) {
        val v = orb ?: return
        tuckAnim?.cancel()
        tuckAnim = ValueAnimator.ofInt(orbLp.x, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { if (docked) { orbLp.x = it.animatedValue as Int; if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp) } }
            start()
        }
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
        docked = false
        orb?.let { v ->
            v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
            orbLp.x = sideX(onLeft())
            orbLp.y = clampY(orbLp.y)
            if (v.isAttachedToWindow) wm.updateViewLayout(v, orbLp)
        }
        panel?.relayout()
        capture?.resizeToScreen(this)   // 截图尺寸跟着横竖屏换，不然横屏题目会被压扁
        scheduleDock()
        // v1.67：系统深浅色变了 —— 跟随系统开着就换成伴生深色，小球和面板立刻重画
        if (darkChanged) main.post {
            Ui.theme = prefs.theme(); panel?.applyTheme(); orb?.invalidate()
        }
    }

    private var dragging = false

    private fun snapToEdge(view: View) {
        val targetX = sideX(onLeft())
        orbLp.y = clampY(orbLp.y)
        ValueAnimator.ofInt(orbLp.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { orbLp.x = it.animatedValue as Int; if (view.isAttachedToWindow) wm.updateViewLayout(view, orbLp) }
            start()
        }
        prefs.orbX = targetX; prefs.orbY = orbLp.y
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

    private fun ensurePanel(): AnswerPanel {
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
        if (!p.attached && !prefs.mutedNow()) { p.attach(); orbToFront() }
        // 自动扫描中途才建出来的面板：被动模式要同步过去（不然会挡手挡视线）。
        // 阅读暂停时**不**切被动 —— 那正是要把答案展开成可读的活动面板。
        if (autoScan && !readingPaused) p.setPassive(true)
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

    private fun togglePanel() {
        val p = panel
        // P0-1：单击关闭面板要和 ✕ 走同一条销毁语义 —— 先掐断在途识别（否则刚才双击发起的这一轮
        // 会继续跑、把答案写进已销毁的面板，且 solving 不复位 → 小球「卡」住，再双击无反应）。
        if (p != null && p.attached) { cancelCurrentIfSolving(); p.fadeOutAndRemove(); panel = null; return }
        val np = ensurePanel()
        if (!solving) {
            np.setAskEnabled(conv != null)
            val last = lastAnswer()
            if (last != null) { np.showText(last); np.setStarred(store.get(convHistoryId)?.star == true) }
            else if (!prefs.hasApi()) np.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }
            else np.setStatus(if (screenSource() == null) "还不能截屏 · 点这里授权（直接弹系统的「共享屏幕」框）" else "双击小球识别屏幕", clickable = screenSource() == null)
        }
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

    /** 双击小球、自动扫描都先查缓存 */
    private fun solve(auto: Boolean) {
        closeMenu()
        if (solving) return
        val p = ensurePanel()
        // v1.63：先看接口 —— 以前没填接口也先弹录屏框，点完「开始」才告诉人没填；这行点了直接去设置
        if (!prefs.hasApi()) {
            if (auto) { reportScanFailure("还没填接口（地址 / Key / 模型）"); return }   // v1.86：自动扫描里这行永远看不见，走失败计数
            p.setStatus("还没填接口（OpenAI 兼容地址 / Key / 模型）· 点这里去填", clickable = true) { openSettings("接口") }; return
        }
        val src = screenSource()
        if (src == null) {
            // v1.59：不再让人先点提示、再进设置页、再点按钮 —— 直接弹系统的「共享屏幕」框；授权完自动接着识别。
            // 不再承诺「开一次长期有效」：无障碍是系统里授权一次、但服务要连着才截得了（已授权 ≠ 已连上）。
            // 要去开 / 查看系统开关统一走 [PermissionActivity]（那里才有「查看系统开关」与受限设置入口），不直达系统页。
            if (A11yService.supported) p.setStatus("请在弹出的框里点「开始」（这次运行一直有效）\n不想每次都弹框 → 点这里去权限页开「无障碍截屏」", clickable = true) { openPermission() }
            else p.setStatus("请在弹出的框里点「开始」（授权一次，本次运行一直有效）")
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
            p.isPassive -> p.setStatus("正在截取屏幕…")
            else -> { p.reset(); p.setStatus("正在截取屏幕…") }
        }
        if (auto) startScanElapsed()   // 自动扫描这一轮开始计时（单调时钟，见 scanElapsedTick）
        val opts = prefs.solveOptions()
        p.setModeLabel(modeLabel())
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

    private val TAG_IMAGE = " · 图片"
    private val TAG_REGION = " · 框选"

    /** 拿到图（截屏或分享来的）之后：查缓存 → 调接口 → 记历史 / 缓存 / 追问上下文。tag 写进历史的标签 */
    private fun runSolve(b64: String, auto: Boolean, p: AnswerPanel, myCancel: AtomicBoolean, opts: Solver.Options, api: ApiConfig, tag: String) {
        worker.execute {
            // v1.70 变化预检（自动扫描专用）：算个轻量指纹和上一轮比，一样就跳过这轮 —— 连缓存查询都不做。
            // 以前是「照跑一遍、命中缓存再退回」：钱是省了，但每轮白解码一次图、白读一次缓存文件、白写一条历史。
            // 双击 / 框选 / 图片识别不走这条（用户是主动点的，就该给结果）。
            if (auto) {
                val fp = Cache.screenLightFingerprint(b64)
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
            // 缓存（v1.58）：同一画面 + 同样设置识别过 → 直接用上次的结果，不调接口
            val cacheKey = if (prefs.useCache) Cache.screenFingerprint(b64)?.let { Cache.solveKey(it, opts, api) } else null
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
                    }
                    finishSolve(myCancel)
                    // v1.90：同答案（非成功提交）的计时行在 finish/freeze **之后**收 —— 先 abort 会被 freezeScanElapsed 置回可见
                    if (sameAsPrior) p.abortRescan()
                }
                return@execute
            }
            try {
                val usage = arrayOfNulls<Pair<Int, Int>>(1)   // v1.65：接口上报的真实 token 用量
                val text = Solver(api).solve(b64, opts, object : Solver.Callbacks {
                    override fun onPhase(t: String) { main.post { if (live(p, myCancel)) p.phase(t) } }
                    override fun onText(t: String) { main.post { if (live(p, myCancel)) p.append(t) } }
                    override fun onUsage(prompt: Int, completion: Int) { usage[0] = prompt to completion }
                }, myCancel)
                main.post {
                    // 这一轮相对上一轮完整结果**是否真的换了新全文**（自动扫描才用它决定要不要 pulseReady）
                    val changed = auto && text.isNotBlank() && text != lastAnswer
                    if (text.isNotBlank() && live(p, myCancel)) {   // 关面板时流到一半的残缺答案不进历史 / 缓存 / 追问
                        lastAnswer = text
                        conv = Solver.Conversation(b64, opts, text)
                        convHistoryId = store.add(History.KIND_SOLVE, optsLabel(opts) + tag, text)
                        if (cacheKey != null) solveCache.put(cacheKey, text)
                        // 用量只做后台记账（prefs.noteUsage），面板状态行不再写 token 数字 / 估算成本
                        val u = usage[0]
                        prefs.noteUsage(u?.first ?: 0, u?.second ?: Usage.estimate(text.length), text.length)
                        // 关键词兜底（v1.65 通识起，v1.66 所有模式、「识别关键词」开着时）：首轮没带清单行 →
                        // 纯文本补问一次把词块补上（不带截图，输入约 1500 字 + 输出一行，不进历史）；带上了就不补问
                        if (opts.terms && tag == "" && !auto && Terms.parse(text).second.isEmpty()) {
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
                val followStore = Cache.followStore(this)
                val fk = Cache.screenFingerprint(c.jpegB64)?.let {
                    // turns.size 入键：同屏同问题但对话已推进（中间问过别的）不算命中
                    Cache.key("follow", it, q, c.opts.mode, c.opts.detail, c.opts.novice.toString(), c.opts.customPrompt, newShot?.let { "1" } ?: "0", c.turns.size.toString(), directive, lang ?: "")
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
                val text = Solver(api).followUp(c, q, object : Solver.Callbacks {
                    override fun onPhase(t: String) { main.post { if (live(p, myCancel)) p.phase(t) } }
                    override fun onText(t: String) { main.post { if (live(p, myCancel)) p.append(t) } }
                    override fun onUsage(prompt: Int, completion: Int) { usage[0] = prompt to completion }
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
        // 本轮结束（成功 / 失败 / 取消）：把自动扫描的「已用时」冻结成 complete（非自动扫描轮是空操作）
        freezeScanElapsed()
        solving = false; orb?.busy = false; activeAutoRound = false; scheduleDock()
    }
    private var solvingSince = 0L

    private fun setOverlaysHidden(hidden: Boolean) {
        val a = if (hidden) 0f else 1f
        // 先掐掉进行中的动画（undock 的淡入 / dock 的变淡），否则它会在截图前把小球又拉回可见。
        // 走 settleAt：停帧循环 + 属性写死成终态一步做完；**缩放照原样保留**（闲置时是小球，别被截图这趟撑大）。
        showOrb(a)
        panel?.setHidden(hidden)
        if (hidden) wordCard.hide()   // 单词卡片别被截进去
        val mm = menuMotion
        if (mm != null) mm.settleAt(Anim.Visual(a, 1f, 0f, 0f)) else menu?.let { it.alpha = a }
    }

    /** 把小球（连同缩放）一步写死到目标不透明度：截屏前藏起来 / 取消后恢复都用它，语义唯一 */
    private fun showOrb(a: Float) {
        val om = orbMotion
        val o = orb
        when {
            om != null -> om.settleAt(Anim.Visual(a, o?.scaleX ?: 1f, 0f, 0f))
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
     * 每一轮：截屏 → 变化预检 → 没变就跳过（不调接口）→ 变了才走缓存 / 接口。
     * scanTick 本身不判断「跳过」—— 那时候还没截屏；跳过判定在 runSolve 里拿到图之后做。
     */
    private val scanTick = object : Runnable {
        override fun run() {
            // 阅读暂停期间节拍器不该再触发（暂停时已 removeCallbacks，这里再兜一道，防迟到的一拍把面板冲掉）
            if (!autoScan || readingPaused) return
            nextScanAt = System.currentTimeMillis() + prefs.intervalSec * 1000L
            // v1.86：以前「没有截屏来源」这一轮直接静悄悄跳过 —— 自动扫描看起来就是「没反应」，
            // 而用户完全不知道为什么。现在算一次失败（连到上限自己停 + 留通知）。
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
        // v1.78：自动扫描 = 被动模式 —— 面板只剩答案文字、面板底全透明、窗口不可触摸（点哪都穿下去），
        // 出口只有长按小球 → 菜单里关掉自动扫描
        panel?.setPassive(true)
        // v1.70：重新开一轮扫描就从零开始数「连续没变化」（P2-2：与 worker 线程同锁，防竞态）
        synchronized(scanLock) { scanStill = AutoScanPolicy.Still() }
        main.removeCallbacks(scanTick); main.post(scanTick)
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)
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
            showOrb(1f); panel?.setHidden(false)
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
        if (autoScan) panel?.setStatus("自动扫描 · " + AutoScanPolicy.countdown(prefs.intervalSec * 1000L))
    }

    // ---------------- 阅读暂停（v1.89）----------------
    /**
     * 单击小球（触摸手势与读屏「点击」共用同一入口）：
     *  - 阅读暂停中 → 保持面板可读，不关（免得看着看着被自己点掉）；
     *  - 自动扫描进行中 → 暂停扫描专心读（[pauseForReading]，不是普通的「关面板」）；
     *  - 其余 → 普通开 / 关面板（[togglePanel]）。
     */
    private fun orbTap() {
        if (readingPaused) { keepPanelReadable(); return }
        if (autoScan) { pauseForReading(); return }
        togglePanel()
    }

    /**
     * 自动扫描进行中单击小球 = **暂停扫描、专心读**（不是普通单击的「关面板」）。
     *
     * 做三件事：
     *  1. 掐掉在途这一轮 —— 丢弃暂存的部分流（[AnswerPanel.abortRescan]），**保留**上一轮完整结果与对话关联；
     *  2. 挂起两个定时器（扫描节拍 [scanTick] + 倒计时 [scanCountdown]）与计时器 [scanElapsedTick]，但**不关**这一轮
     *     自动扫描会话（[autoScan] 仍为 true）—— 收起面板时接着扫（见 [onPanelClosed]）；
     *  3. 把上一轮完整答案展开成可读的**活动**面板（没有结果就给等待空态），并置 [OrbView.readingPaused]。
     *
     * 与「画面没变」/「连败自停」两种暂停彼此独立：那时 [autoScan] 已经是 false，单击走的是别的分支。
     */
    private fun pauseForReading() {
        if (!autoScan || readingPaused) return
        // 1) 掐掉在途这一轮：abort 后迟到回调会被 live() 作废，暂存的部分流不会覆盖旧全文
        if (solving) { cancel.abort(); finishSolve() }
        panel?.abortRescan()
        // 这一轮若已经进入「截屏前藏起来」的隐藏态，要把小球和面板都放出来 —— 阅读是**要看得见**答案
        showOrb(1f)
        panel?.setHidden(false)
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
            if (p.text != last) p.showText(last)   // 内容没变就不重写正文（showText 会回顶），保住用户已经读到的位置
            p.setStarred(store.get(convHistoryId)?.star == true); p.setAskEnabled(conv != null)
            p.setStatus("已暂停自动扫描 · 看完收起面板继续扫码")
        } else {
            p.setStatus("已暂停自动扫描 · 还没有识别结果（收起面板继续扫码）")
        }
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
        // 下一轮按正常间隔再来（不立刻扫）：让收起面板的收尾动画先走完，也避免刚收起就被被动层顶回来
        main.removeCallbacks(scanTick); main.postDelayed(scanTick, prefs.intervalSec * 1000L)
        main.removeCallbacks(scanCountdown); main.post(scanCountdown)
    }

    /**
     * 面板关闭（✕ / 横向划掉 / 单击）的统一收口：先掐断在途识别，再判断要不要结束「阅读暂停」接着扫。
     * 走的是 [ensurePanel] 里既有的 onClose 钩子 —— 面板自己的划掉手势也调它，所以只需一处。
     */
    private fun onPanelClosed() {
        cancelCurrentIfSolving()
        panel = null
        if (autoScan && readingPaused) resumeFromReading()
    }

    /** 阅读暂停时单击小球：保持面板可读，**不**触发普通的「关面板」（免得看着看着被自己点掉） */
    private fun keepPanelReadable() {
        val p = panel
        if (p != null && p.attached) return
        val np = ensurePanel()
        np.setPassive(false)   // 复用的面板可能还带着被动外壳：阅读态必须是可读的活动面板
        val last = lastAnswer()
        if (last != null) { np.showText(last); np.setStarred(store.get(convHistoryId)?.star == true); np.setAskEnabled(conv != null) }
        else np.setStatus("已暂停自动扫描 · 还没有识别结果（收起面板继续扫码）")
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
        if (!autoScan) return
        scanSuspendedByScreen = true
        stopAutoScan()
        panel?.setStatus("屏幕已熄 · 自动扫描已暂停（解锁后自动继续）")
    }

    private fun onScreenOn() {
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
    private fun closeMenu() {
        val m = menu ?: return
        menu = null
        val motion = menuMotion
        menuMotion = null
        scheduleDock()
        val remove = { try { wm.removeView(m) } catch (_: Exception) {} }
        if (motion == null) { remove(); return }
        // 弹出还没落定就被关掉（点到外面 / 选了条目 / 横向划掉）：带着当时的速度接着收，
        // 而不是像原来那样先停在半路、再重播一段 110ms 的淡出
        motion.to(Anim.Visual.HIDDEN, Anim.Spring.popOut) { remove() }
    }

    /**
     * 菜单的窗口位移适配（v1.85）：把弹簧的「位移」接到**窗口 x/y** 上，而不是视图的 translationX/Y ——
     * 悬浮窗 surface 只有窗口那么大，视图变换超出窗口会被裁掉（和 [WordCard] 的 WindowShift 同一个坑）。
     * 横向划菜单时挪的是整张窗口，看着才是「整张跟着手指走」。
     */
    private inner class MenuShift(private val r: View, private val p: WindowManager.LayoutParams) : Anim.VisualTarget {
        private val baseX = p.x
        private val baseY = p.y
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
            set(v) { p.x = baseX + v.toInt(); if (r.isAttachedToWindow) try { wm.updateViewLayout(r, p) } catch (_: Exception) {} }
        override var translationY: Float
            get() = (p.y - baseY).toFloat()
            set(v) { p.y = baseY + v.toInt(); if (r.isAttachedToWindow) try { wm.updateViewLayout(r, p) } catch (_: Exception) {} }
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
                    if (Anim.dragDismissAny(dragX, 0f, vx, 0f, thresholdX, 0f)) closeMenu()
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
        val now = System.currentTimeMillis()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Brut.box(this@OrbService, Brut.PAPER, 5f)
            setPadding(Ui.dp(this@OrbService, 14f), Ui.dp(this@OrbService, 11f),
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
     * v1.85 免打扰契约（菜单里的「免打扰」，预置 30 分钟）——**全部静音 = 只更新历史，不挡屏**：
     * 静音期间 [showWord] 不弹词卡、待办到点不浮「试弹」卡也不补通知、双击识别的答案面板不再 attach
     * （识别照跑、结果照常进历史）、[startAutoScan] 不再开始。到点自己失效，不用任何定时器；
     * 出口只有一个：同一个菜单项再点一下立刻解除。
     */
    @SuppressLint("ClickableViewAccessibility")   // 菜单根自己挂 OnTouchListener 做横向划掉
    private fun showMenu() {
        closeMenu()
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
            // 主操作（目前只有「和小Ling聊聊」）加一点分量：薄荷绿底 + 加粗 —— 沿用面板「发送」那套
            // Ui.emphasize 的规格，全 App 只有一个主操作的样子。
            //
            // v1.85 配色统一：薄荷底以前先设后**被下面那行 ripple 覆盖掉**（RippleDrawable 的 content 传了
            // null），所以主操作其实一直是透明的 —— 现在把薄荷底当 ripple 的 content 传进去，底和波纹都留住。
            val primaryBg = if (primary) android.graphics.drawable.GradientDrawable().apply {
                setColor(Brut.MINT); cornerRadius = Ui.dp(ctx, 8f).toFloat()
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
            r.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleInk),
                primaryBg, Ui.glass(ctx, Ui.CARD, 8f))
            r.setOnClickListener { act(); if (keepOpen) v.text = value() else closeMenu() }
            card.addView(r)
        }
        // v1.85：原来是「高频 8 项 + 更多调节」两层，现在收成**一层** —— 二级项（识别模式 / 解析强度 /
        // 纯小白讲解 / 内置接口 / 思考强度 / 自动扫描间隔 / 离屏幕边 / 缩边 / 自定义 / 待办 / 复习 /
        // 隐藏小球）全部并进设置页（设置页都有对应入口，已逐条核对）。长按一眼看全，不用再切层。
        item("和小Ling聊聊", { "" }, false, primary = true) { try { startActivity(ChatActivity.intent(ctx)) } catch (e: Exception) { toast("打不开聊天页") } }
        sep()
        // v1.88：把「框选识别」加回悬浮球菜单 —— 功能一直都在（Region.kt / RegionOverlay / solveRegion /
        // endRegion / TAG_REGION），快捷开关（ACTION_REGION）和桌面快捷方式也都能进，唯独长按菜单没有入口。
        // 点和菜单里别的「要截屏的动作」一样：先让菜单收起（closeMenu 有 popOut 动画，等它收完再定格），
        // 再走**同一个** solveRegion() —— 和磁贴 / 快捷方式共用一条链路，不另起一套、不重复发布。
        item("框选识别", { "" }, false) { main.postDelayed({ solveRegion() }, 260) }
        item("自动扫描", { if (autoScan) "开 · ${Prefs.fmtInterval(prefs.intervalSec)}一次" else if (scanPausedByFail) "已停 · 连败 ${scanFails.streak} 次" else if (scanPausedByStill) "已暂停 · 点这里继续" else "关" }, true) { if (autoScan) stopAutoScan() else if (scanPausedByFail || scanPausedByStill) resumeScan() else { startAutoScan() } }
        // v1.85：老的「来个单词」（强制弹一张）换成「弹单词」—— 它现在是**自动弹单词的总开关**。
        // 值跟着 wordOn / 间隔走；打开的那一刻顺手来一张（showWord 在免打扰里会自己跳过），别像个没反应的开关。
        item("弹单词", { if (prefs.wordOn) "开 · 每 ${Prefs.fmtInterval(prefs.wordEverySec)} 自动弹" else "关" }, true) {
            prefs.wordOn = !prefs.wordOn
            if (prefs.wordOn) showWord(force = true)
        }
        item("历史记录…", { "" }, false) { startActivity(Intent(ctx, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        item("翻译刚复制的", { Translate.SHORT[Translate.plan(prefs.translateEngine, prefs.deepl().configured, prefs.baidu().configured, prefs.hasApi()).firstOrNull() ?: ""] ?: "" }, false) { translateClipboard() }
        sep()
        item("设置…", { "" }, false) { startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        // v1.85 免打扰：按一下静音 30 分，再按一下立刻解除。全部静音的契约见 showMenu 上方注释。
        // 位置按用户要求放在**退出前一栏**（设置之后、退出之前）。
        sep()
        item("免打扰", { Mute.label(System.currentTimeMillis(), prefs.muteUntil) }, true) {
            val now = System.currentTimeMillis()
            if (Mute.active(now, prefs.muteUntil)) prefs.muteUntil = 0L
            else { prefs.muteUntil = Mute.until(now); stopAutoScan() }   // 静音就把会挡屏的自动扫描先停了
        }
        // 退出 = **只停小球、保留授权**（和通知里那条「停止小球」同一条路径，见 [stopOrb]）：
        // 不再 disableSelf 无障碍、不杀进程，所以截屏授权保留 —— 下次手动启动无需重新授权；清理交给 onDestroy。
        item("停止小球（保留授权）", { "下次启动无需重授" }, false) { stopOrb() }

        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val dm = resources.displayMetrics
        lp.x = if (orbLp.x > dm.widthPixels / 2) orbLp.x - w + orbLp.width / 2 else orbLp.x + orbLp.width / 2
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
                Ui.popTick(ctx)   // v1.73：菜单弹出时轻轻震一下
                orbToFront()
            } else try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        place(true)
    }
}
