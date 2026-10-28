package com.chun.lingzhu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常连的前台服务（`specialUse`）：只有用户自己在「手机常连」页按下开关才会启动，
 * 装好、配对完成、开机都不自动开。
 *
 * 职责只有三件：
 *  1. 挂前台通知（点进 [LinkSettingsActivity]，带「停止」按钮 —— 用户在任何时候都能一键断）；
 *  2. 起一条后台线程跑 [LingLink.run]（长轮询 + 串行执行都在里面，本类不碰协议细节）；
 *  3. 网络可用性变化时 [LingLink.reconnectNow] 立刻重连，不干等 30 秒退避。
 *
 * 401（电脑那头已经不认这条配对）不重试：状态如实显示「需要重新配对」，服务停下 ——
 * 拿失效的密钥一直重连只会让用户以为手机在正常工作。
 */
class LinkService : Service() {

    /**
     * 服务 / 连接状态（跨界面重建要能立刻显示，不必等下一次回调）。
     * `running` 只表示前台服务活着，`text` 是给人看的一句话。
     */
    companion object {
        const val ACTION_START = "com.chun.lingzhu.link.START"
        const val ACTION_STOP = "com.chun.lingzhu.link.STOP"

        private const val CHANNEL = "ling-link"
        private const val NOTI_ID = 0x4c4b
        private const val CHANNEL_NAME = "手机常连"

        @Volatile var running: Boolean = false
            private set

        @Volatile var stateText: String = "未开启"
            private set

        /**
         * 启动常连（用户显式操作）。**不在这里改 `enabled`** —— 开关状态由调用方先落盘，
         * 服务只负责执行，避免「服务没起来但开关显示已开」这种两处状态打架。
         */
        fun start(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
                // 系统限制后台起前台服务（极少见）：状态如实说没起来，不假装在连。
                stateText = "系统不允许在后台启动常连，请打开本页再试"
            }
        }

        /**
         * 停止常连（并同步关掉用户开关 —— 「停」就该是全局停，不能留下一个谎言式的开启状态）。
         *
         * 用 [Context.stopService] 而不是再发一条 START/STOP Intent：在后台 / 被系统限制时
         * `startService` 会直接抛 IllegalStateException，而 `stopService` 任何时候都合法，
         * 并且会立刻走 [onDestroy] → 断连接、撤前台通知、取消待确认弹窗。
         */
        fun stop(ctx: Context) {
            runCatching { LinkPairStore(ctx).enabled = false }
            runCatching { ctx.stopService(Intent(ctx, LinkService::class.java)) }
            // 服务可能已经被系统回收：直接改状态，界面不必等一次广播。
            running = false
            stateText = "未开启"
        }

        fun statusText(): String = if (running) stateText else {
            val s = stateText
            if (s == "未开启") s else "$s（已停止）"
        }
    }

    private val stopping = AtomicBoolean(false)
    @Volatile private var link: LingLink? = null
    @Volatile private var worker: Thread? = null
    private var networks: ConnectivityManager? = null
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything("未开启")
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_STICKY
        stopping.set(false)
        // 先把前台通知挂上：Android 12+ 的 startForegroundService 要求几秒内 startForeground，
        // 否则系统直接判超时把进程崩掉。读配对（磁盘 + Keystore 解密）是 IO，
        // 放主线程会卡住整个应用的输入，所以它在下面的常连线程里做。
        goForeground("正在连接电脑…")
        if (stopping.get()) return START_NOT_STICKY   // 前台起不来：goForeground 里已经收干净，别把状态又改回「连接中」
        running = true
        stateText = "正在连接电脑…"

        worker = Thread({
            val store = LinkPairStore(applicationContext)
            // 用户关了开关 / 没有配对：不启动连接，也不留一个假的前台状态。
            if (!store.enabled) {
                stopEverything("未开启")
                return@Thread
            }
            val pair = store.load()
            if (pair == null) {
                runCatching { store.enabled = false }
                stopEverything("没有配对信息，已停止")
                return@Thread
            }
            val ll = LingLink(applicationContext, pair, LinkDispatcher(applicationContext)) { text ->
                onLinkState(text)
            }
            // 网络回调要比对「当前这条连接」：构造好就登记，别再等 run() 开始。
            link = ll
            try {
                ll.run()
            } finally {
                // 自己跑完（401 / stop）就收拾干净：不留前台通知，不留半开的连接。
                onSessionEnd(ll)
            }
        }, "ling-link").apply { isDaemon = true; start() }

        registerNetwork()
        return START_STICKY
    }

    private fun registerNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 切网 / 恢复网络：立刻重连，不干等退避那 30 秒。
                if (!stopping.get()) link?.reconnectNow()
            }

            override fun onLost(network: Network) {
                if (!stopping.get()) postState("网络断开 · 等网络恢复")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networks = cm
            netCallback = cb
        } catch (_: Exception) {
            // 回调注册失败只影响「立刻重连」，退避重连照旧。
        }
    }

    private fun unregisterNetwork() {
        netCallback?.let { cb ->
            runCatching { networks?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        networks = null
    }

    private fun onLinkState(text: String) {
        if (stopping.get()) return
        postState(text)
        notifyState()
    }

    private fun postState(text: String) {
        stateText = text
    }

    /** 会话自然结束（401 / 内部退出）：停服务、关开关、状态说清。 */
    private fun onSessionEnd(finished: LingLink) {
        if (link !== finished) return
        val auth = finished.authFailed
        val finalText = when {
            stopping.get() -> "未开启"
            auth -> "配对已失效（电脑返回 401）· 需要重新配对"
            else -> "常连已停止"
        }
        if (auth) runCatching { LinkPairStore(this).enabled = false }
        stopEverything(finalText)
    }

    private fun stopEverything(finalText: String) {
        if (stopping.compareAndSet(false, true)) {
            link?.stop()
            link = null
            unregisterNetwork()
            worker?.interrupt()
            worker = null
            running = false
            stateText = finalText
            notifyState()
        }
        runCatching { stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() {
        stopping.set(true)
        link?.stop()
        link = null
        unregisterNetwork()
        worker?.interrupt()
        worker = null
        running = false
        // 已经把「为什么停」说清楚的（401 / 没有配对）就留着，别覆盖成笼统的「未开启」。
        if (stateText.startsWith("正在连接") || stateText == "未连接") stateText = "未开启"
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "手机与电脑之间的常连（由你手动开启）"
            }
        )
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTI_ID, n)
            }
        } catch (_: Exception) {
            // 起不来（被系统限制）：不留一个假的前台状态。
            stateText = "系统不允许常连在后台运行"
            running = false
            stopEverything("系统不允许常连在后台运行")
        }
    }

    private fun notifyState() {
        if (!running) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTI_ID, buildNotification(stateText)) }
    }

    private fun buildNotification(text: String): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, LinkSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_orb)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle(Preset.appName + " 手机常连")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "停止常连", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }
}
