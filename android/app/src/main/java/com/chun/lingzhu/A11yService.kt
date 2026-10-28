package com.chun.lingzhu

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍服务。只用它两类能力：
 *  - takeScreenshot（API 30+）：整屏截图 —— 手动双击识别 / 框选用，截前调用方负责把浮窗藏好；
 *  - takeScreenshotOfWindow（API 34+）：自动扫描时**只截目标 App 窗口** —— 系统按窗口图层取图，
 *    不含压在上面的浮窗，所以自动扫描不必先藏小球、也不用等 120ms。
 * 为了挑出截图目标，声明里开了 canRetrieveWindowContent + flagRetrieveInteractiveWindows，但代码**只读窗口
 * 元数据**（类型 / 焦点 / 活动 / id）来选目标，**从不读节点、不读文本内容**（不调用 getRoot()）；
 * 事件类型保持最小（typeViewFocused），不额外订阅事件。
 *
 * ⚠️ 两个概念要分清（过去混为一谈，UI 因此报错）：
 *  - **已授权**（[enabledInSystem]）：系统「无障碍」开关里本组件是开着的 —— 用户能控制的就是这个；
 *  - **已连接**（[available]）：服务实例此刻在本进程里活着、能立刻截屏 —— 由系统绑定决定，App 管不了。
 * 开关开着但服务没连上，是很正常的一瞬（系统会重连）；反过来开关被关掉后，App **不能**替用户打开。
 * 所以这里不做任何「开一次永久有效」的假设，也不自己重绑服务。
 */
class A11yService : AccessibilityService() {

    companion object {
        /** 截屏失败码 → 大白话（v1.63：以前一律「锁屏了？」）。不把失败一律说成「去重新授权」。 */
        fun describe(code: Int): String = when (code) {
            3 -> "两次截屏隔得太近，系统拒绝了 —— 等一秒再试"
            2 -> "无障碍截屏这会儿不可用（服务没连上或权限被改）—— 去系统「无障碍」里确认开关是开着的"
            6 -> "这个画面禁止截屏（银行、支付、考试类 App 常这样）—— 换个画面再识别"
            5 -> "目标窗口这会儿截不了（已切走 / 不支持截这个窗口）—— 换个画面再试"   // 5 = INVALID_WINDOW，仅窗口截图会返回
            4 -> "屏幕状态不对（锁屏或正在切换）—— 解锁后再试"
            else -> "系统截屏出错（代码 $code）—— 锁屏了？或稍后再试"
        }

        @Volatile var instance: A11yService? = null
            private set

        /** 服务实例没了以后，还能读系统开关状态、给一句真话用（application context，不泄漏 Activity） */
        @Volatile private var appCtx: Context? = null

        /** takeScreenshot 是 Android 11(API 30) 才有的；更老的系统开了也截不了 */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        /** 连接可用：实例在本进程里活着、能截屏。**只表示已连接**，不表示「已授权」（授权看 [enabledInSystem]）。 */
        val available: Boolean get() = supported && instance != null

        private const val ALERT_CHANNEL = "alert"
        private const val ALERT_ID = 3

        // ---------------- 连接状态订阅 ----------------
        // KeepAlive.observeA11y 用它：连接 / 解绑 / 销毁时回调一次，回调在主线程。

        private val connListeners = CopyOnWriteArrayList<() -> Unit>()
        private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

        fun addConnectionListener(l: () -> Unit) { if (!connListeners.contains(l)) connListeners.add(l) }
        fun removeConnectionListener(l: () -> Unit) { connListeners.remove(l) }

        private fun notifyConnectionChanged() {
            if (connListeners.isEmpty()) return
            val snapshot = ArrayList(connListeners)
            mainHandler.post { for (l in snapshot) runCatching { l() } }
        }

        /**
         * 系统里本组件到底开没开（= 已授权）。读 `ACCESSIBILITY_ENABLED` + `ENABLED_ACCESSIBILITY_SERVICES`，
         * 在列表里按 ComponentName 找回自己 —— 不能只看一个全局开关，那可能是别的无障碍服务开着的。
         * 读的是系统设置，跟本进程里服务连没连上无关。
         */
        fun enabledInSystem(ctx: Context): Boolean {
            if (!supported) return false
            return try {
                val cr = ctx.contentResolver
                val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
                if (!on) return false
                val flat = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                val me = ComponentName(ctx, A11yService::class.java)
                val it = android.text.TextUtils.SimpleStringSplitter(':').apply { setString(flat) }
                while (it.hasNext()) {
                    val cn = ComponentName.unflattenFromString(it.next())
                    if (cn != null && cn == me) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        /** 服务没连上时，给调用方（[A11ySource]）一句不冤枉用户的实话 */
        fun disconnectedMessage(): String {
            val c = appCtx ?: return "无障碍截屏还没连上 —— 稍等一两秒再试；一直不行就去系统「无障碍」里看看这个开关"
            return if (enabledInSystem(c))
                "无障碍已授权，但服务还没连上 —— 稍等一下再试；若一直连不上，去系统开关确认它是「已开启」"
            else
                "无障碍截屏没授权（系统开关是关的）—— 去 设置 → 无障碍 → 打开「${Preset.appName}」"
        }

        private fun clearAlert(ctx: Context) {
            try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID) } catch (_: Exception) {}
        }

        /**
         * 断开时提示一声（仅当用户在跑小球时）。**不猜是谁关的**：读系统开关分两种话术 ——
         * 还开着 = 只是连接断了（通常会自动重连）；已关掉 = 需要用户自己去系统开关打开（App 不能替用户开）。
         * 通知落在 PermissionActivity：那里才有「查看系统开关」的可操作入口。
         */
        private fun alertIfDisconnected(ctx: Context) {
            try {
                val p = Prefs(ctx)
                if (!p.orbWanted || !OrbService.running) return
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "提醒", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(ctx, 5,
                    Intent(ctx, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE)
                val stillOn = enabledInSystem(ctx)
                val title = if (stillOn) Preset.appName + " 的无障碍截屏连接断了" else Preset.appName + " 的无障碍截屏已关闭"
                val text = if (stillOn) "系统里这个开关还开着，通常会自己重连；一直连不上就点这里查看开关"
                           else "系统里这个开关现在是关的 —— App 不能替你打开，点这里去手动打开"
                nm.notify(ALERT_ID, Notification.Builder(ctx, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_orb)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open).setAutoCancel(true).build())
            } catch (_: Exception) {}
        }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * v1.62 守护：无障碍服务由系统绑定，进程被清理后**有些系统**会重新连上它 —— 连上时把小球带回来；
     * 之后周期看一眼，有的系统只停服务不杀进程，也能补回来。只在用户想开着（orbWanted）时才拉。
     * v1.65 自适应退避：刚连上 30 秒一查（快速补回）；连续 5 轮无事可做就 60s → 120s → 300s 拉长间隔，
     * 真拉起过一次或检查条件变了就回到 60s。省电，又不影响守护效果（STICKY 与看门狗闹钟兜着）。
     */
    private var guardIntervalMs = 60_000L
    private var guardIdleRounds = 0
    private val guard = object : Runnable {
        override fun run() {
            val fired = revive()
            if (fired) { guardIntervalMs = 60_000L; guardIdleRounds = 0 }
            else if (++guardIdleRounds > 5) guardIntervalMs = (guardIntervalMs * 2).coerceAtMost(300_000L)
            main.postDelayed(this, guardIntervalMs)
        }
    }
    /** 返回这轮是否真的把小球拉起来了 */
    private fun revive(): Boolean {
        val p = Prefs(this)
        if (!p.orbWanted || OrbService.running || !android.provider.Settings.canDrawOverlays(this)) return false
        // 截屏通道就是自己，不用录屏令牌：普通 startService，服务起来后自己挂前台（无障碍绑定中的应用不算后台，允许）
        return try {
            startService(android.content.Intent(this, OrbService::class.java).setAction(OrbService.ACTION_START).putExtra(OrbService.EXTRA_REVIVE, "无障碍守护"))
            true
        } catch (_: Exception) { false }
    }

    override fun onServiceConnected() {
        instance = this
        appCtx = applicationContext
        Prefs(this).a11yEverOn = true
        clearAlert(this)                    // 连上了 → 清掉上次的断开告警
        OrbService.onA11yReady()            // 小球在跑、还拿着录屏授权：换成无障碍截屏，把录屏停掉
        guardIntervalMs = 30_000L; guardIdleRounds = 0
        main.removeCallbacks(guard); main.postDelayed(guard, 1_500)
        notifyConnectionChanged()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        appCtx = applicationContext
        main.removeCallbacks(guard)
        // 断开了，但不知道是谁关的（系统解绑 / 用户手动关 / 强行停止都会走到这）—— 不猜，读开关说真话。
        alertIfDisconnected(this)
        notifyConnectionChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        appCtx = applicationContext
        main.removeCallbacks(guard)
        notifyConnectionChanged()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** ScreenshotResult.hardwareBuffer → Bitmap（HARDWARE）。拿不到给 null。调用方负责 recycle */
    private fun bitmapOf(res: ScreenshotResult): Bitmap? = try {
        val hb: HardwareBuffer = res.hardwareBuffer
        val b = Bitmap.wrapHardwareBuffer(hb, res.colorSpace)
        hb.close()
        b
    } catch (_: Exception) { null }

    /** 截当前默认屏 → Bitmap（回调在主线程）。失败给 null。频率过快（<~1s）会被系统拒，返回 null */
    /** cb(位图, 失败码)：成功时失败码 0；失败码见 AccessibilityService.ERROR_TAKE_SCREENSHOT_* */
    fun shot(cb: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { cb(null, -1); return }
        try {
            val t0 = System.currentTimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val t1 = System.currentTimeMillis()
                    val bmp = bitmapOf(res)
                    val t2 = System.currentTimeMillis()
                    cb(bmp, if (bmp == null) 1 else 0)
                }
                override fun onFailure(errorCode: Int) { cb(null, errorCode) }
            })
        } catch (e: Exception) { cb(null, 1) }
    }

    /**
     * 截**指定窗口**（API 34+）→ Bitmap（回调在工作线程）。失败给 null + 失败码（同 [shot] 的码）。
     * 与 [shot] 的差别：只截这一个窗口自己的画面，**不含压在上面的浮窗**（系统按窗口图层取图），
     * 所以自动扫描不必先藏小球。windowId 由 [scanTargetWindowId] 从窗口列表里选出。
     */
    fun shotWindow(windowId: Int, cb: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { cb(null, -1); return }
        try {
            takeScreenshotOfWindow(windowId, exec, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bmp = bitmapOf(res)
                    cb(bmp, if (bmp == null) 1 else 0)
                }
                override fun onFailure(errorCode: Int) { cb(null, errorCode) }
            })
        } catch (e: Exception) { cb(null, 1) }
    }

}

/**
 * 从窗口列表里挑一个**可截图的目标窗口** id：当前获得焦点 / 处于活动的 TYPE_APPLICATION 窗口（优先「焦点」，
 * 退到「活动」）。只读窗口**元数据**（类型 / 焦点 / 活动 / id），不读节点、不读文本（不碰 window.root）。
 *
 * 为什么不按包名排除自己：无障碍只有拿到 window.root（节点）才知道窗口属于哪个包，而读节点被明确禁止；
 * 于是改用「类型 + 焦点」这一层元数据来排除自己的浮窗 —— 小球 / 面板都是 TYPE_SYSTEM + flagNotFocusable，
 * 天然落不进 TYPE_APPLICATION 这一支。**已知边角**：万一 Ling 自己的 Activity 正处最前（用户手动打开了
 * Ling），它同样是 TYPE_APPLICATION + focused，此刻**无法**在不读节点的前提下与别家 App 区分 ——
 * 自动扫描场景下极少发生（用户正看着目标 App）。纯函数，便于单测。
 *
 * 返回 -1 = 没有合适的目标窗口（没拿到交互窗口权限 / 没有应用窗口在活动）→ 调用方走原来的整屏截图。
 */
internal fun scanTargetWindowId(wins: List<AccessibilityWindowInfo>?): Int {
    if (wins == null) return -1
    var focused = -1
    var active = -1
    for (w in wins) {
        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
        if (focused == -1 && w.isFocused) focused = w.id
        if (active == -1 && w.isActive) active = w.id
    }
    return if (focused != -1) focused else active
}

/** 无障碍截屏通道 */
class A11ySource(private val onMain: (Runnable) -> Unit) : ScreenSource {
    override val ready: Boolean get() = A11yService.available
    override val label: String get() = "无障碍"
    override var lastError: String? = null
    override fun capture(cb: (String?) -> Unit) {
        val svc = A11yService.instance
        if (svc == null) {
            // 断连 ≠ 没授权。用保存的 application context 读系统开关，给一句对得上的话（别一律要人重新授权）。
            lastError = A11yService.disconnectedMessage()
            cb(null); return
        }
        svc.shot { bmp, code ->
            // 间隔太短（刚命中缓存马上又双击识别就会这样）：系统限 1 秒一次，等一下自己再试一次
            if (bmp == null && code == 3) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    svc.shot { b2, c2 -> lastError = if (b2 == null) A11yService.describe(c2) else null; cb(bitmapToJpegBase64(b2).also { b2?.recycle() }) }
                }, 1100)
                return@shot
            }
            lastError = if (bmp == null) A11yService.describe(code) else null
            cb(bitmapToJpegBase64(bmp).also { bmp?.recycle() })
        }
    }
    /**
     * 自动扫描专用：不藏浮窗、不等帧，直接截「目标 App 窗口」（API 34+ takeScreenshotOfWindow）。
     * true = 已**开始**（cb 恰好回调一次，可能 null）；false = **没开始**，调用方必须走老路（藏浮窗 + 120ms + [capture]）。
     *
     * 只在系统 ≥ 34、服务已连、且能从窗口列表选到目标窗口时才 true；内部**不做**整屏回退 —— 此刻浮窗还没藏，
     * 退回去会把浮窗截进去。所以失败就 cb(null) + [lastError]，这一轮交给调用方按失败处理（上报 / 计数），
     * 下一轮自动扫描会重新尝试窗口截图，不会永久卡死。
     */
    override fun captureForScan(cb: (String?) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val svc = A11yService.instance ?: return false
        // Only focus events are subscribed: window ids can otherwise survive an Activity transition in the accessibility cache.
        svc.clearCache()
        val wins = try { svc.windows } catch (_: Exception) { null }
        val id = scanTargetWindowId(wins)
        if (id < 0) return false
        val once = AtomicBoolean(false)   // 兜底：不管系统回调几次，cb 只发一次
        svc.shotWindow(id) { bmp, code ->
            if (!once.compareAndSet(false, true)) return@shotWindow
            lastError = if (bmp == null) A11yService.describe(code) else null
            cb(bitmapToJpegBase64(bmp).also { bmp?.recycle() })
        }
        return true
    }
    
    /** 上次截图的失败码（0 = 成功或不知道）。速记靠它区分「安全窗口」和普通失败 */
    private var lastCodeField: Int = 0
    override val lastCode: Int get() = lastCodeField

    /**
     * v2.4 速记：整屏截图 → 软件位图（已按 scaledSize 缩放）。
     *
     * 和 [capture] 的差别只有一个：不经过 JPEG。速记要生成原图和缩略图两份文件，
     * 先压成 JPEG 再解回来纯属白做功，还多一次画质损失。
     *
     * 失败时把失败码留给 [lastCode]：速记要靠它区分「安全窗口」（码 6 / 黑帧）和普通失败。
     *
     * 位图所有权：[shot] 给的这张归这里回收 —— [scaledSoftwareBitmap] 在 HARDWARE 时 copy 出一张
     * 新的软件位图（10MB 级），旧的不回收就是每轮速记漏一张；只有它原样返回同一个对象时
     * （本来就是小尺寸软件位图）才把所有权交给 cb。
     */
    override fun captureBitmap(cb: (Bitmap?) -> Unit) {
        val svc = A11yService.instance
        if (svc == null) {
            lastCodeField = -1
            lastError = A11yService.disconnectedMessage()
            cb(null)
            return
        }
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        svc.shot { bmp, code ->
            // 重复回调：这张同样是给我们的，没人会再回收它
            if (!once.compareAndSet(false, true)) { bmp?.recycle(); return@shot }
            lastCodeField = code
            lastError = if (bmp == null) A11yService.describe(code) else null
            var soft: Bitmap? = null
            try {
                soft = bmp?.let { scaledSoftwareBitmap(it) }
            } finally {
                // 换成新对象（copy / 缩放）时原图归我们收；原样返回则所有权转给 cb
                if (bmp != null && soft !== bmp) runCatching { bmp.recycle() }
            }
            cb(soft)
        }
    }

    /** 无障碍服务生命周期由系统管，这里不动它 */
    override fun release() {}   // 服务生命周期由系统管，这里不动它
}
