package com.chun.lingzhu.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时转写的前台服务：**只有用户在「实时转写」页按下开始才会启动**，
 * 不随开机、不随上次会话自动恢复（[START_NOT_STICKY]；拿到没有 action 的 Intent 立刻收工）。
 *
 * 三档声音来源（[VoiceSource]），最终都是 16k / 单声道 / s16le，走同一个 [VoiceAsrClient.sendPcm] 出口：
 *  - `mic`：麦克风（原来唯一的行为，默认）；
 *  - `system`：系统播放的声音（Android 10 起，走播放捕获，需要一份 MediaProjection 授权）；
 *  - `mixed`：两路各自读满一帧、逐样本饱和相加（[VoiceMixer]）。
 *
 * 两条**绝不做**的事：来源不可用（低版本 / 授权被拒）时**不自动退回麦克风**；
 * 采集失败时**不悄悄换音源**——用户以为录的是系统声音、实际是房间里的声音，是这一页最坏的一种错。
 *
 * ## 会话令牌（关键）
 * 每次 `begin` 都造一个新的 [Session]（自己的收尾 latch、自己的停止闸门与看门狗），记在 [current] 上；
 * **`current === s` 就是唯一有效的身份判定**（用对象身份，不用自增编号）。所有异步路径都捕获自己那一份 `s`：
 *  - 解 Key 的后台线程 → 回主线程后 `isCurrent(s) && s.mayDriveCapture()` 才继续；
 *  - ASR 回调（ready / 文字 / 结束 / 错误）→ 旧会话一律丢弃（收尾期只允许文字进窗口，那是尾巴）；
 *  - 采集线程 → 开麦 / 开系统声音前后各校验一次，请求停止后**绝不再打开**，已开的立刻停掉释放；
 *  - MediaProjection / AudioRecordingCallback → 都带自己那份 session，`s.tornDown` 或会话已换人时一律不动状态。
 *
 * ## 顺序（Android 14 的硬要求）
 * ```
 * 可见 Activity 弹授权 → RECORD_AUDIO → MediaProjection 同意框 → 前台服务(类型) → getMediaProjection 一次
 *   → registerCallback → AudioRecord（麦克风 / 播放捕获）
 * ```
 * `getMediaProjection` 必须在前台服务以 `mediaProjection` 类型跑起来之后调，否则系统直接抛；
 * 一个 Intent token 只能用一次，所以整个会话只取一次投影。
 *
 * ## 状态机（[VoiceSession.Phase]）
 * ```
 * IDLE ──begin──▶ STARTING ──onReady（服务端就绪）──▶ STARTING(已连接) ──第一帧真的送出──▶ RECORDING
 *                    │                                                                        │
 *                    ├── 失败（权限 / 授权 / 开麦 / 连不上 / 超时）──▶ IDLE（带原因）          │
 *                    └──────────────────── stop ─────────────────────────────────┴──▶ STOPPING ──收尾完──▶ IDLE
 * ```
 *
 * ## 线程
 * 除「解 Key、读录音设备、收尾等待」这三段阻塞活在后台线程外，**状态只在主线程改**。
 * 对外的 [requestStop] / [begin] 都先把自己丢回主线程再做判断，因此停止与开始的竞争是串行的。
 */
class VoiceService : Service() {

    /** 服务与界面之间的最小契约：状态快照 + 变化通知。回调都在主线程。 */
    interface Listener {
        fun onVoiceUpdate(snap: VoiceSnapshot)
    }

    inner class LocalBinder : Binder() {
        /** 绑上来的界面拿到服务本体（只为读快照 / 注册监听 / 请求停止，别的一概不开放）。 */
        val service: VoiceService get() = this@VoiceService

        fun snapshot(): VoiceSnapshot = this@VoiceService.snapshot

        fun addListener(l: Listener) {
            listeners.addIfAbsent(l)
            l.onVoiceUpdate(snapshot)
        }

        fun removeListener(l: Listener) {
            listeners.remove(l)
        }

        fun requestStop() = this@VoiceService.requestStop()

        /**
         * 关页面 / 丢弃：**先停投递文字、再清窗口**，然后正常收尾。
         *
         * 只 requestStop 是不够的：收尾那一两秒里服务还会把最后一句定稿送进来，
         * 而页面已经 `reset` 过 —— 那段尾巴会把刚清空的窗口重新填上，下次进页面就看见上一次的字。
         */
        fun closeAndDiscard() = this@VoiceService.closeAndDiscard()
    }

    /**
     * 一次会话的全部可变状态。
     *
     * [dead] 一旦置位就再也不会复位：旧会话的任何回调、任何后台线程都不许再碰窗口 / 状态 / 连接。
     * [stopping] 表示「已请求停止，正在收尾」—— 此时**只有识别结果还允许进窗口**（那是尾巴），
     * 状态与采集一律冻结。[discardText] 是比 `stopping` 更强的闸门：连尾巴也不要（页面已经关了）。
     */
    private class Session(val provider: String, val source: String, val resultCode: Int, val resultData: Intent?) {
        val latch = CountDownLatch(1)
        val stopOnce = AtomicBoolean(false)
        @Volatile var client: VoiceAsrClient? = null
        @Volatile var capture: Thread? = null

        /** 麦克风那一路（`mic` / `mixed` 才有）。 */
        @Volatile var mic: AudioRecord? = null

        /** 系统播放那一路（`system` / `mixed` 才有）。 */
        @Volatile var sys: AudioRecord? = null

        @Volatile var projection: MediaProjection? = null
        @Volatile var projectionCallback: MediaProjection.Callback? = null
        @Volatile var audioCallback: AudioManager.AudioRecordingCallback? = null

        /** 收尾已开始拆设备：此后投影 / 录音回调里的 onStop 不算「用户在别处撤销了授权」。 */
        @Volatile var tornDown = false

        /** 第一帧真的进了连接（决定要不要切 RECORDING，以及「一帧都没送出去」的判定）。 */
        @Volatile var sentFirst = false

        @Volatile var stopping = false
        @Volatile var dead = false

        /** 这一条的文字**一律丢弃**（页面已关闭，没有人会看）。与 [stopping] 正交：正常停止要留尾巴。 */
        @Volatile var discardText = false

        /** 每一路的观察结果（谁听到声音、谁被静音、静音多久）。 */
        val tracker = VoiceAudioTracker(source)

        /**
         * 自己那份「连接超时」闹钟。**必须存起来**：`Handler.removeCallbacks` 按对象身份比对，
         * 每次 new 一个 Runnable 是永远摘不掉的，旧闹钟到点就会误杀会话。
         */
        var watchdog: Runnable? = null

        /** 收尾阶段连接断开的原文（只用来把「最后一句可能没说完」说得更具体），可为空。 */
        @Volatile var tailError: String? = null

        /** 还允许往窗口里写识别结果（正常收尾期间也要，尾巴不能丢；丢弃模式下一律不要）。 */
        fun mayDeliverText(): Boolean = !dead && !discardText

        /** 还允许改状态 / 开设备 / 切 RECORDING。 */
        fun mayDriveCapture(): Boolean = !dead && !stopping
    }

    companion object {
        const val ACTION_START = "com.chun.lingzhu.voice.START"
        const val ACTION_STOP = "com.chun.lingzhu.voice.STOP"

        /** 关页面 / 丢弃：停止 + 之后到的尾巴一律不进窗口（见 [closeAndDiscard]）。 */
        const val ACTION_CLOSE = "com.chun.lingzhu.voice.CLOSE"

        /** 从「开始」到听见第一段真实结果最多等多久（含连接 + 首帧）。超时如实报错，不假装在录 */
        private const val READY_TIMEOUT_MS = 15_000L

        /**
         * 停止后等服务商把最后一句说完的上限。比 [VoiceAsrClient] 自己的收尾超时（8 秒）多留一点，
         * 让客户端先自行结束并给出准确原因；这里只是兜底，防止不响应的连接把界面永远挂在「收尾中」。
         */
        private const val FINISH_TIMEOUT_MS = 9_000L

        /** 采集线程退出等待（read 被 stop 打断后立刻返回，这个只是兜底） */
        private const val CAPTURE_JOIN_MS = 600L

        /** 采样率与一帧长度：200ms / 16k / 单声道 / s16le = 6400 字节（与协议约定一致） */
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 3_200

        /** 一个 s16le 样本占 2 字节：混录只按整样本对齐，半样本绝不拼进下一帧。 */
        private const val BYTES_PER_SAMPLE = 2

        /** 连续多少轮读不到数据就判定设备有问题（每轮之间歇 20ms，约 1 秒） */
        private const val MAX_EMPTY_READS = 50

        private const val CHANNEL = "ling-voice"
        private const val CHANNEL_NAME = "实时转写"
        private const val NOTI_ID = 0x4c56
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        private const val STOPPED = "已停止"

        /**
         * 启动一次会话（用户显式点击）。是否真的能录由服务再核一遍权限、授权与 Key ——
         * 界面上的按钮状态可能比系统状态旧一拍。
         *
         * [resultCode] / [resultData] 是**这一档需要系统播放声音时**从 MediaProjection 同意框拿回来的；
         * 只录麦克风时传 0 / null。token 只在这里透传给服务，不落盘、不写日志。
         * 返回 `false` 表示系统连前台服务都没让起（原因已写进 [VoiceSession.fail]）：调用方必须立刻画真实状态，
         * 不能把界面留在「正在准备」。
         */
        fun start(ctx: Context, provider: String, source: String, resultCode: Int, resultData: Intent?): Boolean {
            val i = Intent(ctx, VoiceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROVIDER, provider)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                // 系统不让起前台服务：如实说没起来，也不留一个假的状态（返回 false 让界面立刻画出来）。
                VoiceSession.fail("系统不允许启动前台服务（" + (e.message ?: e.javaClass.simpleName) + "）")
                false
            }
        }

        /** 只录麦克风（原来的唯一路径）：不需要投影授权，保留这个重载给不带投影的调用方。 */
        fun start(ctx: Context, provider: String): Boolean = start(ctx, provider, VoiceSource.MIC, 0, null)

        /**
         * 从界面 / 通知里请求停止。**优先走绑定的 [LocalBinder.requestStop]**（同一进程、立刻生效）；
         * 这个只在没绑上时兜底 —— 用 startService 递一条 ACTION_STOP，服务在 onStartCommand 里
         * 走同一条 [requestStop]。
         */
        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP)) }
        }

        /**
         * 关页面兜底（没绑上时）：递一条 [ACTION_CLOSE]，服务在 onStartCommand 里走同一条
         * [closeAndDiscard]。为什么不只用 [stop]：那条路径要保留尾巴字，而页面已经不要了。
         */
        fun close(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_CLOSE)) }
        }
    }

    private val binder = LocalBinder()
    private var binderAttached = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private lateinit var keys: VoiceKeyStore

    /** 同意只按「服务商 × 来源」各记一次；这里读的与本机设置是同一份（[VoiceActivity] 那道校验可能旧一拍）。 */
    private lateinit var settings: VoiceSettings

    /**
     * 当前会话；非 null 时**不允许**再 `begin`（连接中 / 录音中 / 收尾中都算占用）。
     *
     * 写入只在主线程；读取有两处不在主线程（采集线程的 `isCurrent`、其 finally 里的摘引用），
     * 所以必须 `@Volatile`。
     */
    @Volatile private var current: Session? = null

    @Volatile private var snapshot: VoiceSnapshot = VoiceSnapshot()

    override fun onBind(intent: Intent?): IBinder {
        binderAttached = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        binderAttached = false
        return false
    }

    override fun onCreate() {
        super.onCreate()
        keys = VoiceKeyStore(this)
        settings = VoiceSettings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_CLOSE -> closeAndDiscard()
            ACTION_START -> begin(
                intent.getStringExtra(EXTRA_PROVIDER) ?: VoiceProvider.DOUBAO,
                intent.getStringExtra(EXTRA_SOURCE) ?: VoiceSource.MIC,
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                @Suppress("DEPRECATION") (intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)),
            )
            // 被系统重启（没有我们的 Intent）不许自己接着录：麦克风 / 系统声音这种事只能由用户按下开始。
            else -> if (current == null) {
                publish(VoiceSnapshot(VoiceSession.Phase.IDLE, "未在转写"))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 进程要走了：把当前会话标死，任何在途回调都不许再动窗口 / 状态。
        val s = current
        current = null
        if (s != null) {
            s.dead = true
            s.stopping = true
            s.tornDown = true
            runCatching { s.client?.cancel() }
            s.client = null
            releaseCapture(s)
        }
        listeners.clear()
        super.onDestroy()
    }

    // ---------------- 启动 ----------------

    /** 入口（主线程）。[current] 还在就说明上一条没走完 —— 无论是连接中、录音中还是收尾中。 */
    private fun begin(want: String, source: String, resultCode: Int, resultData: Intent?) {
        if (Looper.myLooper() != main.looper) {
            main.post { begin(want, source, resultCode, resultData) }
            return
        }
        if (current != null) {
            // 收尾线程还没跑完 / 已经在录：不重入、不叠加第二路设备、不打断收尾。
            // 界面会从快照里读到真实状态（还在 STARTING 或 STOPPING），不必也不许另起一条。
            notifyListeners()
            return
        }
        if (!VoiceProvider.isValid(want)) {
            rejectBeforeSession("不认识的语音服务商")
            return
        }
        if (!VoiceSource.isValid(source)) {
            rejectBeforeSession("不认识的声音来源")
            return
        }
        // 来源在这一档不可用（低版本）：明确失败。**绝不退回麦克风** —— 那会让用户以为录的是系统声音。
        val reason = VoiceSource.unavailableReason(source)
        if (reason != null) {
            rejectBeforeSession(reason)
            return
        }
        if (VoiceSource.requiresProjection(source) && resultData == null) {
            rejectBeforeSession("没有拿到系统声音的授权，已停止（不会改录麦克风）")
            return
        }

        // 同意按「服务商 × 声音来源」各记一次，且只存在本机：界面那道校验可能比设置旧一拍
        //（换档 / 撤回之后旧回调才回来），服务侧再核一遍 —— 没同意就绝不建会话：不开设备、不拿投影、不发网络。
        if (!settings.consentGiven(want, source)) {
            rejectBeforeSession(
                "还没同意把" + VoiceSource.label(source) + "上传到 " + VoiceProvider.label(want) +
                    "，已停止（没有录音、没有上传）"
            )
            return
        }
        // RECORD_AUDIO 三档都要：播放捕获同样是拿一个 AudioRecord 读，系统按 RECORD_AUDIO 判权。
        // 必须在 startForeground 之前核（Android 14 上以 microphone 类型前台却没有这个权限会直接抛
        // SecurityException），也在建会话之前核 —— 失败时不能留下一个占着 [current] 的死会话，
        // 那会让用户下一次点「开始」被当成重入而静默丢掉。
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            rejectBeforeSession("没有录音权限：请在系统设置里允许录音（系统声音也要同一个权限）")
            return
        }

        val s = Session(want, source, resultCode, resultData)
        current = s

        // 前台通知挂上：startForegroundService 之后系统只给几秒；
        // Android 14 还要求 mediaProjection 类型的前台服务先跑起来，才允许 getMediaProjection（见 [openProjection]）。
        if (!goForeground(s, "正在准备" + sourceNoun(s.source) + "…")) return

        publish(
            VoiceSnapshot(
                VoiceSession.Phase.STARTING,
                "正在连接 ${VoiceProvider.label(want)}…（还没开始上传）",
                audio = s.tracker.status(),
            )
        )
        // 连接 + 就绪的看门狗：到点还没进入 RECORDING 就如实报错，绝不装作在录。
        val dog = Runnable {
            if (isCurrent(s) && s.mayDriveCapture() && snapshot.phase == VoiceSession.Phase.STARTING) {
                fail(s, "${VoiceProvider.label(s.provider)} 在 ${READY_TIMEOUT_MS / 1000} 秒内没能开始识别，已停止")
            }
        }
        s.watchdog = dog
        main.postDelayed(dog, READY_TIMEOUT_MS)
        // 解 Key（Keystore 解密）是 IO：不在主线程做，否则点「开始」的那一下会卡住整个界面。
        Thread({ loadKeyThenStart(s) }, "voice-start").apply { isDaemon = true; start() }
    }

    /**
     * 建会话之前的失败（参数 / 来源 / 授权 / 权限 / 同意）：写进共享快照**并通知已绑定的界面**。
     *
     * 只写 [VoiceSession.fail] 是不够的：界面已经绑上、正停在「正在准备」，收不到通知就会一直卡在那里。
     */
    private fun rejectBeforeSession(message: String) {
        publish(VoiceSnapshot(VoiceSession.Phase.IDLE, message, error = true))
        detachAndStopSelf()
    }

    /** 摘掉会话的看门狗（连接已成 / 已停止都不该再响）。按存下来的那个对象摘，才能真的摘掉。 */
    private fun cancelWatchdog(s: Session) {
        s.watchdog?.let { main.removeCallbacks(it) }
        s.watchdog = null
    }

    /**
     * 取投影（**整个会话只调用一次**），注册 onStop 回调。失败就如实说，不改录麦克风。
     *
     * 「用户 / 系统在别处撤了授权」→ 立即停下这条会话并说明：系统只允许一条投影活动，
     * 别处（比如小球录屏）拿走之后我们这条就读不到任何东西，装作还在录是假状态。
     */
    private fun openProjection(s: Session): Boolean {
        val data = s.resultData ?: return false
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            mpm.getMediaProjection(s.resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (p == null) {
            fail(s, "系统声音授权没能生效（可能已被撤销），已停止（不会改录麦克风）")
            return false
        }
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                // 我们自己拆设备时也会走这里：那时不算「用户撤销」，什么都不做。
                if (s.tornDown || !isCurrent(s) || s.dead) return
                s.tracker.onProjectionRevoked()
                main.post {
                    if (!isCurrent(s) || s.dead) return@post
                    fail(
                        s,
                        "系统声音的授权被撤销（可能被别的应用拿走 / 系统回收了录屏权限），已停止。" +
                            "需要重新点开始并同意共享，不会自动改录麦克风。",
                    )
                }
            }
        }
        s.projection = p
        s.projectionCallback = cb
        try {
            p.registerCallback(cb, main)
        } catch (e: Exception) {
            releaseCapture(s)
            fail(s, "系统声音授权初始化失败：" + (e.message ?: e.javaClass.simpleName))
            return false
        }
        return true
    }

    /**
     * 后台线程只负责解 Key；拿到结果回主线程、且**确认会话还是当前那一条**才继续建连接。
     * 用户在这几百毫秒里点了停止 / 换了服务商，这里就直接作废，不会「停了又自己连上」。
     */
    private fun loadKeyThenStart(s: Session) {
        val key = runCatching { keys.load(s.provider) }.getOrNull().orEmpty()
        main.post {
            if (!isCurrent(s) || !s.mayDriveCapture()) return@post
            if (key.isBlank()) {
                fail(s, "还没保存 ${VoiceProvider.label(s.provider)} 的 API Key（或本机解不开已存的 Key）")
                return@post
            }
            val config = VoiceAsrConfig(s.provider, key)
            val issue = config.issue()
            if (issue != null) {
                fail(s, issue)
                return@post
            }
            // Key 核过了才去取投影：没 Key 时连「共享屏幕」那一步都不该走到（先拦下，不开采集）。
            // 顺序仍是 前台服务以 mediaProjection 类型跑起来 → getMediaProjection 一次 → registerCallback；
            // 一个授权 token 只能用一次，所以整个会话只在这里取一次。
            if (VoiceSource.requiresProjection(s.source) && !openProjection(s)) return@post
            attach(s, config)
        }
    }

    /** 建连接。**不开采集** —— 采集要等服务端 `onReady`（见 [onAsrReady]）。 */
    private fun attach(s: Session, config: VoiceAsrConfig) {
        if (!isCurrent(s) || !s.mayDriveCapture()) return
        val client = VoiceAsrClient(config, object : VoiceAsrClient.Listener {
            override fun onReady() = onAsrReady(s)

            /**
             * 文字一律回主线程再判、再写窗口。
             *
             * `VoiceAsrClient` 的回调在自己的后台线程上，而「关页面丢弃」是主线程做的 ——
             * 如果这里在后台线程先判 `mayDeliverText()` 再写，判与写之间就隔着一个可被丢弃插队的窗口：
             * 尾巴会在 `reset` 之后又把窗口填回来。丢回主线程后，「丢弃」与「写入」严格串行。
             */
            override fun onTranscript(update: VoiceTranscript) {
                main.post {
                    if (!isCurrent(s) || !s.mayDeliverText()) return@post
                    if (VoiceSession.window.apply(update)) notifyListeners()
                }
            }

            override fun onFinished() {
                // latch 必须无条件放行：那是「服务商确认收尾完成」的唯一信号，与世代无关。
                s.latch.countDown()
                main.post { if (isCurrent(s) && !s.stopping) requestStop() }
            }

            override fun onError(message: String) {
                s.latch.countDown()
                main.post {
                    if (s.dead) return@post
                    if (s.stopping) {
                        // 已经在收尾：不把它变成「启动失败」，只记住原因，由收尾流程一并说清。
                        s.tailError = message
                    } else if (isCurrent(s)) {
                        fail(s, message)
                    }
                }
            }
        })
        s.client = client
        client.start()
    }

    /**
     * 服务端确认就绪：**到这一步才开采集**。迟到的 onReady 不许把 RECORDING 降回 STARTING。
     *
     * 同时摘掉连接看门狗：它就绪了，只是用户还没开口 —— 那不是故障，不该在 15 秒后报「没能开始识别」。
     */
    private fun onAsrReady(s: Session) {
        main.post {
            if (!isCurrent(s) || !s.mayDriveCapture()) return@post
            val client = s.client ?: return@post
            cancelWatchdog(s)
            if (snapshot.phase != VoiceSession.Phase.RECORDING) {
                updateState(VoiceSession.Phase.STARTING, "已连接，等第一段" + sourceNoun(s.source) + "…")
            }
            if (s.capture == null) {
                val cap = Thread({ captureLoop(s, client) }, "voice-capture")
                cap.isDaemon = true
                s.capture = cap
                cap.start()
            }
        }
    }

    // ---------------- 采集 ----------------

    /**
     * 采集线程：只在 [Session.mayDriveCapture] 为真时开设备，按 200ms 一帧读，读满就交给
     * [VoiceAsrClient.sendPcm]。
     *
     * 三档各自的读法：
     *  - `mic` / `system`：直接读满一帧就送（[loopSingle]）；
     *  - `mixed`：两路**各自**读满一帧再逐样本相加（[loopMixed]）；哪一路短读就按较短的那一路混，
     *    剩下的字节挪到缓冲头部等下一轮补齐 —— 绝不把半样本拼进下一帧（整段会从此错位成炸响）。
     *
     * 设备打开失败**不换音源**：混录里系统那一路打不开就整条失败（并释放已经打开的麦克风）。
     */
    private fun captureLoop(s: Session, client: VoiceAsrClient) {
        val frameBytes = FRAME_SAMPLES * 2
        if (!isCurrent(s) || !s.mayDriveCapture()) return

        if (VoiceSource.usesMic(s.source) && openMic(s, frameBytes) == null) return
        if (VoiceSource.requiresProjection(s.source) && !openSystem(s, frameBytes)) {
            // 麦克风已经开了：整条会话放弃，先把麦克风还回去（不降级成只用麦克风）。
            releaseCapture(s)
            return
        }
        registerSilenceMonitor(s)

        try {
            when (s.source) {
                VoiceSource.SYSTEM -> {
                    val rec = s.sys ?: return
                    loopSingle(s, client, rec, ByteArray(frameBytes), isMic = false)
                }
                VoiceSource.MIXED -> {
                    val mic = s.mic ?: return
                    val sys = s.sys ?: return
                    loopMixed(
                        s, client, mic, sys,
                        ByteArray(frameBytes), ByteArray(frameBytes), ByteArray(frameBytes),
                    )
                }
                else -> {
                    val rec = s.mic ?: return
                    loopSingle(s, client, rec, ByteArray(frameBytes), isMic = true)
                }
            }
        } catch (_: InterruptedException) {
            // 收尾线程打断等待：正常退出，不算错误。
        } catch (e: Exception) {
            if (isCurrent(s) && s.mayDriveCapture()) fail(s, "采集中断，已停止（" + (e.message ?: e.javaClass.simpleName) + "）")
        } finally {
            releaseCapture(s)
            // 一帧都没送出去就退出、且不是因为停止：这是启动失败，不是「录完了」。
            if (!s.sentFirst && isCurrent(s) && s.mayDriveCapture()) {
                fail(s, "没有采到任何${sourceNoun(s.source)}")
            }
        }
    }

    /** 单路采集（`mic` / `system`）。 */
    private fun loopSingle(s: Session, client: VoiceAsrClient, rec: AudioRecord, buf: ByteArray, isMic: Boolean) {
        val frameBytes = buf.size
        var errors = 0
        while (isCurrent(s) && s.mayDriveCapture()) {
            val read = fillFrame(s, rec, buf, 0, frameBytes, isMic)
            if (read < 0) return
            if (read == 0) {
                // 连续拿不到数据（极少数设备在切前后台时短暂 read=0）：别热循环空转。
                if (++errors > MAX_EMPTY_READS) {
                    fail(s, (if (isMic) "麦克风" else "系统声音") + "一直没有数据，已停止")
                    return
                }
                Thread.sleep(20L)
                continue
            }
            errors = 0
            val milestone = if (isMic) s.tracker.onMicFrame(buf, 0, read) else s.tracker.onSystemFrame(buf, 0, read)
            if (milestone) pushAudioStatus(s)
            send(s, client, buf, read, frameBytes)
        }
    }

    /**
     * 混录：两路各自读满一帧，逐样本**饱和相加**（[VoiceMixer]）。
     *
     * 短读不硬等：一路（比如播放捕获在静音时给得慢）拿不满时，先把两路**对齐的部分**混进
     * [mixBuf] 的接续位置，剩下的字节挪到头部 —— 两路的时间轴不会彼此拖住，也不会丢掉已经读到的声音。
     * 混满一帧（6400 字节 / 3200 样本）才发：服务商那边看到的是匀速 200ms 帧，不是越来越碎的碎包。
     */
    private fun loopMixed(
        s: Session,
        client: VoiceAsrClient,
        mic: AudioRecord,
        sys: AudioRecord,
        micBuf: ByteArray,
        sysBuf: ByteArray,
        mixBuf: ByteArray,
    ) {
        val frameBytes = micBuf.size
        /** 两路各自已经读进缓冲、还没混出去的字节数。 */
        var micFill = 0
        var sysFill = 0
        /**
         * **当前这一个 200ms 输出帧**里两路各自有没有出现过非零样本。
         *
         * 不按每次 read 的小块报状态：播放捕获安静时给得慢，一个 200ms 帧可能由四五块拼成，
         * 报一次算一帧的话「3 秒没声音」会缩成 1 秒多 —— 那是在替设备提前下结论。
         */
        var micAudible = false
        var sysAudible = false
        var outFill = 0
        var errors = 0
        while (isCurrent(s) && s.mayDriveCapture()) {
            // 每轮每路只读一次（读不满也照常混）：两路的时间轴才不会被彼此拖住 ——
            // 播放捕获在安静时给得慢，硬等它读满 200ms 会把麦克风那一路一起卡住。
            val micGot = readSome(s, mic, micBuf, micFill, frameBytes - micFill, isMic = true)
            if (micGot < 0) return
            micFill += micGot
            val sysGot = readSome(s, sys, sysBuf, sysFill, frameBytes - sysFill, isMic = false)
            if (sysGot < 0) return
            sysFill += sysGot

            // 只按整样本混，而且不能混出这一帧的尾巴：多出来的字节留到下一轮混合。
            val usable = (minOf(micFill, sysFill, frameBytes - outFill) / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE
            if (usable <= 0) {
                if (++errors > MAX_EMPTY_READS) {
                    // 说清是**哪一路**没数据：两路都没数据、或只有一路卡住，用户要采取的动作不一样。
                    // 混录绝不退化成「只剩麦克风」偷偷继续发 —— 那等于冒充系统声音。
                    val which = when {
                        micFill == 0 && sysFill == 0 -> "麦克风与系统声音都没有数据"
                        micFill == 0 -> "麦克风一直没有数据（系统声音那一路在读）"
                        else -> "系统声音一直没有数据（麦克风那一路在读）"
                    }
                    fail(s, "$which，已停止")
                    return
                }
                Thread.sleep(20L)
                continue
            }
            errors = 0
            // 这一小块里两路各自有没有声音；累计到**整个 200ms 帧**混满时才报一次状态。
            if (!micAudible && VoiceMixer.peak(micBuf, 0, usable) != 0) micAudible = true
            if (!sysAudible && VoiceMixer.peak(sysBuf, 0, usable) != 0) sysAudible = true
            // 混进输出缓冲的**接续位置**：一路读得快、另一路慢时，慢的那一路不会把已经读到的声音
            // 卡在手里等到齐 —— 攒满一整帧再发，三块缓冲全程复用，不为半帧 new 一次数组。
            outFill += VoiceMixer.mixInto(micBuf, 0, usable, sysBuf, 0, usable, mixBuf, outFill)
            if (outFill == frameBytes) {
                if (s.tracker.onMixedFramePeaks(micAudible, sysAudible)) pushAudioStatus(s)
                micAudible = false
                sysAudible = false
                send(s, client, mixBuf, frameBytes, frameBytes)
                outFill = 0
            }
            // 两路剩下的字节挪到头部等下一帧（dest 在前、源在后，正向 copy 安全）。
            micFill = shift(micBuf, usable, micFill)
            sysFill = shift(sysBuf, usable, sysFill)
        }
        // 停止时把不足一帧的尾巴也送出去（只在这一刻拷一次，不在每帧拷），
        // 那半帧里听到的声音照样要算进这一路的状态 —— 否则「刚开口就被停」会被记成没声音。
        if (outFill > 0) {
            if (s.tracker.onMixedFramePeaks(micAudible, sysAudible)) pushAudioStatus(s)
            send(s, client, mixBuf, outFill, frameBytes)
        }
    }

    /** 把 [buf] 里从 [from] 起、已经填到 [filled] 的尾巴挪到头部，返回剩下的字节数。 */
    private fun shift(buf: ByteArray, from: Int, filled: Int): Int {
        val keep = filled - from
        if (keep <= 0) return 0
        System.arraycopy(buf, from, buf, 0, keep)
        return keep
    }

    /** 读一次（最多 [want] 字节）。返回 >0 读到的字节、0 暂时没数据、<0 错误（已在内部报失败）。 */
    private fun readSome(s: Session, rec: AudioRecord, buf: ByteArray, off: Int, want: Int, isMic: Boolean): Int {
        if (want <= 0) return 0
        val n = rec.read(buf, off, want)
        if (n < 0) {
            // read 返回负值是 AudioRecord 的错误码（设备被拔 / 被抢占），不是「暂时没数据」。
            fail(s, (if (isMic) "麦克风" else "系统声音") + "读取失败（错误码 $n），已停止")
        }
        return n
    }

    /**
     * 读满 [want] 字节到 [buf] 的 [off]（单路采集用：一帧就是一帧，读满再送）。
     *
     * 返回：>0 实际读到的字节；0 = 这一轮没有数据（可以歇一下再试）；负值 = 读错误。
     */
    private fun fillFrame(s: Session, rec: AudioRecord, buf: ByteArray, off: Int, want: Int, isMic: Boolean): Int {
        var got = 0
        while (got < want && isCurrent(s) && s.mayDriveCapture()) {
            val n = readSome(s, rec, buf, off + got, want - got, isMic)
            if (n < 0) return n
            if (n == 0) break
            got += n
        }
        return got
    }

    /** 送一帧给 ASR。满帧直接用复用缓冲；不足一帧（停止时那条尾巴）才拷一份。 */
    private fun send(s: Session, client: VoiceAsrClient, buf: ByteArray, len: Int, frameBytes: Int) {
        if (len <= 0) return
        val chunk = if (len == frameBytes) buf else buf.copyOf(len)
        if (client.sendPcm(chunk) && !s.sentFirst) {
            s.sentFirst = true
            main.post {
                // 只有「自己的会话还在跑、还没被要求停止」时才敢说在录音。
                if (isCurrent(s) && s.mayDriveCapture() && snapshot.phase != VoiceSession.Phase.RECORDING) {
                    updateState(VoiceSession.Phase.RECORDING, recordingText(s))
                }
            }
        }
    }

    /**
     * 打开麦克风并开始录音。**成功才返回实例；任何失败路径都在这里把实例释放掉再返回 null。**
     *
     * 为什么释放必须留在这个函数里：`startRecording()` 会抛异常，而那时会话往往还没被要求停止，
     * 由调用方的 `finally` 去「判断要不要 release」就会漏 —— 那块 AudioRecord 再也收不回来，
     * 麦克风被自己占死，下一次开始必然打不开。这里用 [handedOff] 明确「交出去了没有」。
     */
    private fun openMic(s: Session, frameBytes: Int): AudioRecord? {
        var rec: AudioRecord? = null
        var handedOff = false
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                fail(s, "麦克风不可用（这台设备不支持 16k 单声道录音）")
                return null
            }
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameBytes * 4)
            )
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                fail(s, "麦克风打不开：可能被别的应用占用或系统限制")
                return null
            }
            // 初始化是慢活（几十到几百毫秒）：这期间用户完全可能已经点了停止。
            // 所以先登记、再校验，停止后一律不进入 startRecording。
            s.mic = r
            if (!isCurrent(s) || !s.mayDriveCapture()) return null
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                if (isCurrent(s) && s.mayDriveCapture()) fail(s, "麦克风没能开始录音：可能被别的应用占用")
                return null
            }
            // startRecording 与 stop 之间还有一道窄缝：这里再确认一次，已停止就立刻收麦。
            if (!isCurrent(s) || !s.mayDriveCapture()) return null
            handedOff = true
            return r
        } catch (e: SecurityException) {
            fail(s, "没有麦克风权限：请在系统设置里允许录音")
            return null
        } catch (e: Exception) {
            fail(s, "麦克风初始化失败：" + (e.message ?: e.javaClass.simpleName))
            return null
        } finally {
            // 没交出去 = 这条路径失败了（含 return null 与抛异常）：立刻把实例收干净。
            if (!handedOff) rec?.let { releaseRecorder(s, it, isMic = true) }
        }
    }

    /**
     * 打开系统播放那一路。失败**明确报错**（16k 单声道不被接受、设备不支持播放捕获等），
     * 由调用方放弃整条会话 —— 绝不退回麦克风。
     */
    private fun openSystem(s: Session, frameBytes: Int): Boolean {
        val projection = s.projection ?: run {
            fail(s, "没有系统声音的授权，已停止（不会改录麦克风）")
            return false
        }
        var handedOff = false
        var rec: AudioRecord? = null
        try {
            val r = VoiceSystemAudio.open(projection, frameBytes)
            rec = r
            s.sys = r
            if (!isCurrent(s) || !s.mayDriveCapture()) return false
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                if (isCurrent(s) && s.mayDriveCapture()) fail(s, "系统声音没能开始采集：可能被别的应用占用")
                return false
            }
            if (!isCurrent(s) || !s.mayDriveCapture()) return false
            handedOff = true
            return true
        } catch (e: Exception) {
            fail(s, "系统声音初始化失败：" + (e.message ?: e.javaClass.simpleName) + "（不会改录麦克风）")
            return false
        } finally {
            if (!handedOff) rec?.let { releaseRecorder(s, it, isMic = false) }
        }
    }

    /**
     * 听系统的「有没有把我们的麦克风静音」（API 29+ 的 AudioRecordingCallback）。
     *
     * 只在麦克风这一路真的开起来之后注册；**API 29 以下不注册、状态保持 unknown**（[VoiceAudioStatus.micSilenced]
     * 为 null）—— 不猜、也不假装「没被静音」。
     */
    private fun registerSilenceMonitor(s: Session) {
        if (!VoiceSource.usesMic(s.source)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rec = s.mic ?: return
        val am = getSystemService(AudioManager::class.java) ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                if (s.dead || s.tornDown) return
                val live = s.mic ?: return
                val cfg = configs.firstOrNull { it.clientAudioSessionId == live.audioSessionId } ?: return
                val silenced = cfg.isClientSilenced
                val before = s.tracker.status().micSilenced
                s.tracker.onMicSilenced(silenced)
                if (before != silenced) pushAudioStatus(s)
            }
        }
        s.audioCallback = cb
        runCatching { am.registerAudioRecordingCallback(cb, main) }
    }

    /** 释放一条录音设备：只在还是同一个实例时从会话上摘掉，再 stop + release；任何一步都不抛。 */
    private fun releaseRecorder(s: Session, rec: AudioRecord, isMic: Boolean) {
        if (isMic) {
            if (s.mic === rec) s.mic = null
        } else {
            if (s.sys === rec) s.sys = null
        }
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    /**
     * 收掉这一会话的全部采集资源：两路 AudioRecord、录音回调、投影。
     * 可以重复调用（每一样都先摘引用再释放，[Session.tornDown] 保证投影的 onStop 不再算撤销）。
     */
    private fun releaseCapture(s: Session) {
        s.tornDown = true
        s.tracker.freeze()
        s.mic?.let { releaseRecorder(s, it, isMic = true) }
        s.sys?.let { releaseRecorder(s, it, isMic = false) }
        s.mic = null
        s.sys = null
        val cb = s.audioCallback
        s.audioCallback = null
        if (cb != null) {
            val am = getSystemService(AudioManager::class.java)
            runCatching { am?.unregisterAudioRecordingCallback(cb) }
        }
        val pcb = s.projectionCallback
        val p = s.projection
        s.projectionCallback = null
        s.projection = null
        if (p != null) {
            runCatching { if (pcb != null) p.unregisterCallback(pcb) }
            runCatching { p.stop() }
        }
    }

    /**
     * 关页面：**先丢弃文字、再清窗口、最后正常收尾**。
     *
     * 与 [requestStop] 的差别只有一条，但很关键：`discardText` 一置位，`onTranscript` 就再也不写窗口 ——
     * 否则「停止后还在等最后一句」的那一两秒里，尾巴会写进一个已经被页面 `reset` 过的窗口，
     * 用户下次进页面就看见上一次的字（明明文案说「关掉这一页就清空」）。
     */
    fun closeAndDiscard() {
        if (Looper.myLooper() != main.looper) {
            main.post { closeAndDiscard() }
            return
        }
        // 只读一次 current：置位、清窗口、请求停止都在同一次主线程执行里，中间没有别的线程能插进来。
        val s = current
        if (s == null) {
            // 本来就没在跑：只把窗口清干净（页面上已经显示的也不留）。
            VoiceSession.reset()
            return
        }
        s.discardText = true
        VoiceSession.window.clear()
        notifyListeners()
        requestStop()
    }

    // ---------------- 停止 ----------------

    /**
     * 停止（任何状态下都能调：连着、录着、还在解 Key 收尾，都能停）。只跑一次：
     *  1. 采集线程收工（不再有新帧，设备随即释放）；
     *  2. `finish()` 让服务商吐完最后一句，最多等 [FINISH_TIMEOUT_MS]；
     *  3. 关连接、摘前台通知、把会话标死。
     *
     * 从任何线程调都会先回主线程，所以「开始 / 停止」永远是串行的。
     */
    fun requestStop() {
        if (Looper.myLooper() != main.looper) {
            main.post { requestStop() }
            return
        }
        val s = current
        if (s == null) {
            // 本来就没在跑（按钮状态比真实状态旧一拍 / 通知里连点）：不摆一个「正在收尾」的空态。
            publish(VoiceSnapshot(VoiceSession.Phase.IDLE, "未在转写"))
            detachAndStopSelf()
            return
        }
        if (!s.stopOnce.compareAndSet(false, true)) return
        // 先关闸门，再动设备：`mayDriveCapture()` 立刻变假，采集线程不会再送帧、也不会再开设备。
        s.stopping = true
        cancelWatchdog(s)
        updateState(VoiceSession.Phase.STOPPING, "正在收尾，等最后一句…")

        Thread({
            // 先停两条 AudioRecord（让阻塞中的 read() 立刻返回），再等采集线程收工，最后才 finish 收尾。
            // 顺序反了会丢尾巴：连接先关，最后一帧就送不进服务商了。
            runCatching { s.mic?.stop() }
            runCatching { s.sys?.stop() }
            s.capture?.let { cap -> runCatching { cap.join(CAPTURE_JOIN_MS) } }
            s.capture?.let { if (it.isAlive) it.interrupt() }

            val client = s.client
            val tailOk = client != null && runCatching {
                client.finish()
                s.latch.await(FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!tailOk) runCatching { client?.cancel() }

            main.post {
                releaseCapture(s)
                s.capture = null
                s.client = null
                s.dead = true
                // 尾巴里的定稿句已经在 onTranscript 里进了 [VoiceSession.window]（内存，不落盘），
                // 这里只把状态收干净、不清文字 —— 「关掉这一页就停并清空」是页面自己的事。
                val tail = s.tailError
                val detail = when {
                    tail != null -> "$STOPPED · 收尾时连接断开（$tail），最后一句可能没说完"
                    client != null && !tailOk -> "$STOPPED · 收尾超时，最后一句可能没说完"
                    else -> "$STOPPED · 文字没保存（只在那一页的内存里）"
                }
                // 只有它还是当前会话时才改共享状态与通知：万一已经起了下一条，不能把新会话盖掉。
                if (current === s) {
                    current = null
                    publish(VoiceSnapshot(VoiceSession.Phase.IDLE, detail, audio = s.tracker.status()))
                    detachAndStopSelf()
                }
            }
        }, "voice-finish").apply { isDaemon = true; start() }
    }

    // ---------------- 失败 ----------------

    /**
     * 失败是终态：不重试、不换来源、不换服务商 —— 如实说哪一步不行，按钮回到「开始」。
     * 采集线程也会调它，所以先把会话标死（同步立刻生效），再把改状态的部分丢回主线程 ——
     * 「状态只在主线程改」这条约定不能因为一个错误路径就破掉。
     */
    private fun fail(s: Session, message: String) {
        s.stopping = true
        if (Looper.myLooper() != main.looper) {
            main.post { failOnMain(s, message) }
            return
        }
        failOnMain(s, message)
    }

    private fun failOnMain(s: Session, message: String) {
        // 已经被停止流程接管（或已经死掉）：让停止流程自己收尾，别在这里抢着报错。
        if (!s.stopOnce.compareAndSet(false, true)) return
        cancelWatchdog(s)
        s.dead = true
        runCatching { s.client?.cancel() }
        s.client = null
        s.capture?.interrupt()
        s.capture = null
        releaseCapture(s)
        // 只有它还是当前会话时才动共享状态与前台通知：旧会话的失败不许盖掉 / 摘掉新会话。
        if (current === s) {
            current = null
            publish(VoiceSnapshot(VoiceSession.Phase.IDLE, message, error = true, audio = s.tracker.status()))
            detachAndStopSelf()
        }
    }

    /** 摘前台通知 + 结束自己（状态已由调用方写好）。绑定状态下 stopSelf 不会销毁服务，符合预期。 */
    private fun detachAndStopSelf() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---------------- 状态与通知 ----------------

    private fun isCurrent(s: Session): Boolean = current === s && !s.dead

    /** 每一路的状态变了（第一次听到声音、静音到门槛、麦克风被静音）：回主线程重画快照与通知。 */
    private fun pushAudioStatus(s: Session) {
        main.post {
            if (!isCurrent(s)) return@post
            val audio = s.tracker.status()
            val text = when (snapshot.phase) {
                VoiceSession.Phase.RECORDING -> recordingText(s)
                VoiceSession.Phase.STOPPING -> snapshot.text
                else -> snapshot.text
            }
            publish(snapshot.copy(text = text, audio = audio))
            if (snapshot.phase == VoiceSession.Phase.RECORDING) refreshNotification(text)
        }
    }

    private fun updateState(phase: VoiceSession.Phase, text: String) {
        publish(
            VoiceSnapshot(
                phase, text, error = false,
                recording = phase == VoiceSession.Phase.RECORDING,
                audio = current?.tracker?.status() ?: VoiceAudioStatus(),
            )
        )
        refreshNotification(text)
    }

    private fun publish(snap: VoiceSnapshot) {
        snapshot = snap
        VoiceSession.last = snap
        notifyListeners()
    }

    private fun notifyListeners() {
        if (!binderAttached) return
        val cur = snapshot
        main.post {
            if (!binderAttached) return@post
            for (l in listeners) runCatching { l.onVoiceUpdate(cur) }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "实时转写（由你手动开始）"
            }
        )
    }

    private fun foregroundTypes(s: Session): Int {
        var t = 0
        if (VoiceSource.usesMic(s.source)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (VoiceSource.requiresProjection(s.source)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return t
    }

    private fun goForeground(s: Session, text: String): Boolean = try {
        val n = buildNotification(s, text)
        if (Build.VERSION.SDK_INT >= 30) {
            val t = foregroundTypes(s)
            startForeground(NOTI_ID, n, if (t != 0) t else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTI_ID, n)
        }
        true
    } catch (e: Exception) {
        fail(s, "系统不允许启动前台服务（" + (e.message ?: e.javaClass.simpleName) + "）")
        false
    }

    private fun refreshNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val s = current ?: return
        runCatching { nm.notify(NOTI_ID, buildNotification(s, text)) }
    }

    private fun buildNotification(s: Session, text: String): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, VoiceActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(VoiceProvider.label(s.provider) + " 实时转写 · " + VoiceSource.label(s.source))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "停止转写", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    /** 「正在转写」那句话：带来源与（混录时）每一路的状态，通知栏和页面看到的是同一句事实。 */
    private fun recordingText(s: Session): String {
        val st = s.tracker.status()
        val routes = st.routeLines().filter { it.contains("已检测到") || it.contains("未检测到") }
        val tail = if (routes.isEmpty()) "" else "（" + routes.joinToString("；") + "）"
        return "正在转写" + sourceNoun(s.source) + " → ${VoiceProvider.label(s.provider)}$tail"
    }

    /** 来源的名词形式（拼句子用）。 */
    private fun sourceNoun(source: String): String = when (source) {
        VoiceSource.SYSTEM -> "系统声音"
        VoiceSource.MIXED -> "麦克风 + 系统声音"
        else -> "麦克风"
    }
}
