package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 局域网同步（手机端）：电脑开一个配对页并把 `ling-sync://IPv4:port/<base64url32bytekey>`
 * 显示出来，手机把这串贴进来，点「开始同步」才发生一次 HTTP 交换。
 *
 * - 只有前台、只有手动触发：离开页面 / 取消就断开，不起服务、不排后台任务、不自动重试。
 * - 配对串视同密码：不写 Prefs、不写日志、不自动读剪贴板；输入框始终遮蔽，离开页面即清空。
 * - 传输：POST http://ip:port/ling-sync/v1/exchange，AES-256-GCM，AAD 固定 `ling-sync-v1`。
 *   请求与响应都走同一 envelope：`{v:1,iv:B64(12B),data:B64(密文+GCM tag)}`。
 * - 双向合并：本机 [LanSyncStore.export] → 电脑合并 → 返回合并后 records → 本机 [LanSyncStore.apply]。
 *   重复同步只合一，不重复；取消不撤销电脑可能已经保存的那次结果。
 *
 * 皮肤沿用设置页的 [Brut] / [Ui]，不另起一套 UI。
 */
class LanSyncActivity : Activity() {

    companion object {
        private const val SCHEME = "ling-sync://"
        private const val PATH = "/ling-sync/v1/exchange"
        private const val AAD = "ling-sync-v1"
        private const val VERSION = 1

        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val TAG_BYTES = GCM_TAG_BITS / 8

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        /** 请求体与响应体共用的硬上限（8 MiB）。响应按流读，超了立即中止。 */
        private const val MAX_BYTES = 8 * 1024 * 1024

        private const val CANCEL_TEXT =
            "已取消，未完成，重试安全。连接已断开；本机不再应用尚未落库的结果（已开始的落库无法回滚）。" +
                "电脑可能已收到这次同步，取消不会撤回对端已保存的内容。再点同步会自动合并。"

        /** 界面重建时只记录一个布尔位：同步未完成被打断。绝不放密钥、连接串或目标地址。 */
        private const val STATE_INTERRUPTED = "lan_sync_interrupted"
        private const val INTERRUPT_TEXT =
            "同步已因界面重建停止，请重新配对。连接已断开，配对串没有保存。"

        private const val PHASE_READ = "读取本机资料"
        private const val PHASE_WRITE = "写回本机资料"

        private const val REQ_CAMERA = 0x4c51
        /** errorStream 只读这么多：足够识别 401 / busy 文案，又不会被超大错误页拖住。 */
        private const val MAX_ERROR_BYTES = 1024
    }

    private lateinit var root: FrameLayout
    private lateinit var input: EditText
    private lateinit var syncBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var scanBtn: Button
    private lateinit var status: TextView
    private lateinit var conflictHost: LinearLayout

    /** 冲突读取 / 解决走独立 IO 线程，绝不和主线程或同步线程串在一起。 */
    private var io: ExecutorService? = null
    private var scanner: LanQrScanner? = null
    @Volatile
    private var resolving = false
    private var lastConflictSig: String? = null

    /**
     * 当前这一次同步。取消 / 退出时只认这一个对象，迟到回调拿不到它就不会再碰 UI；
     * 每次重试都是新对象，不会把上一次的取消状态带过来。
     */
    @Volatile
    private var current: Run? = null
    private var worker: ExecutorService? = null

    private class Run(val host: String, val port: Int, val key: ByteArray) {
        val cancelled = AtomicBoolean(false)
        @Volatile var done = false
        /** 页面已离开：此后任何迟到回调都不许再改 UI，更不能把结果显示成成功。 */
        @Volatile var detached = false
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var thread: Thread? = null
        /** 当前网络阶段：异常分类用（CONNECT / WRITE / READ），不参与界面状态保存。 */
        @Volatile var stage = SyncStage.CONNECT
    }

    private class BadInput(message: String) : Exception(message)

    private class SyncFailure(val reason: String, val sent: Boolean = true) : Exception(reason)

    /** 本机阶段（export / apply）的失败，和网络失败分开报，避免把磁盘 IO 说成网络中断。 */
    private class LocalFailure(val phase: String, cause: Throwable) : Exception(cause.message, cause)

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        title = Preset.appName + " · 局域网同步"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18f)
            setPadding(p, dp(12f), p, dp(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        // 扫码是「同页覆盖层」：根布局固定，摄像头预览直接盖在页面上面，不新开 Activity、
        // 不把配对串放进 Intent / Bundle。
        root = FrameLayout(this)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // 系统栏留边必须垫在**窗口根**上：垫在 scroll 上时，它的内容会被内边距顶下来，
        // 但滚动时内容照样滚进状态栏 / 导航条底下的透明区域，字直接叠到时间和手势条上。
        Brut.page(this, root)

        col.addView(TextView(this).apply {
            text = "局域网同步"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(10f))
        })

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@LanSyncActivity).apply {
                text = "电脑端先开启「手机连接」，页面上会显示一串 ling-sync://… 的配对串（旁边也有二维码）。手机和电脑连同一个 Wi-Fi，把配对串贴到下面或扫码，点「开始同步」。"
                textSize = 13.5f
                setLineSpacing(0f, 1.25f)
            })
            addView(TextView(this@LanSyncActivity).apply {
                text = "只同步知识卡和收藏的历史记录；双向合并，重复同步不会重复。同步只在你点按钮时进行，离开本页 / 取消即断开，不起后台服务、不自动重试。"
                textSize = 13.5f
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(8f), 0, 0)
            })
        }
        col.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) })
        Brut.card(intro)

        col.addView(Brut.heading(this, "配对串（视同密码）"))

        input = EditText(this).apply {
            hint = "ling-sync://192.168.1.23:8765/…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // 配对串里有会话密钥：不参与系统保存 / 自动填充，也不显示明文。
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(input)
        col.addView(TextView(this).apply {
            text = "输入框始终遮蔽，无法切换明文。配对串等同密码：不保存到设置、不写日志、不自动读剪贴板；离开本页或锁屏会清空，需要重新从电脑复制。"
            textSize = 12.5f
            alpha = 0.75f
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(4f), 0, dp(2f))
        })

        // 扫码结果只写进上面这个遮蔽输入框，不落盘、不进 Bundle / Intent。
        scanBtn = Button(this).apply {
            text = "扫描电脑上的二维码"
            setOnClickListener { requestScan() }
        }
        col.addView(scanBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, dp(2f))
        }
        syncBtn = Button(this).apply {
            text = "开始同步"
            tag = Brut.PRIMARY
            setOnClickListener { startSync() }
        }
        cancelBtn = Button(this).apply {
            text = "取消"
            tag = Brut.DANGER
            isEnabled = false
            setOnClickListener { cancelSync() }
        }
        actions.addView(syncBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8f) })
        actions.addView(cancelBtn, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(actions)

        val statusCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 只消费一次：重建时读掉这个布尔位，新实例自己的 onSaveInstanceState 不会把它继续传下去。
        val interrupted = savedInstanceState?.getBoolean(STATE_INTERRUPTED, false) == true
        status = TextView(this).apply {
            text = if (interrupted) {
                INTERRUPT_TEXT
            } else {
                "未开始。配对串只在本次输入框里，离开即清空。"
            }
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setTextIsSelectable(false)
            // 状态文字不参与 View 状态保存，避免重建后把提示重复带回来。
            isSaveEnabled = false
        }
        statusCard.addView(status)
        col.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        Brut.card(statusCard)

        col.addView(Brut.heading(this, "冲突处理"))
        conflictHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(conflictHost)

        col.addView(TextView(this).apply {
            text = "安全说明：只接受局域网 IPv4（10.x / 172.16–31.x / 192.168.x）和回环地址（含模拟器测试用 10.0.2.2），端口 1024–65535；" +
                "连接与读取分别 10 / 30 秒超时，请求和响应各 8 MiB 上限。加密用 AES-256-GCM，认证失败会直接报错，不会降级成明文，也不会关闭认证。"
            textSize = 12.5f
            alpha = 0.75f
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(14f), 0, 0)
        })

        Brut.style(col)
        setRunningUi(false)

        io = Executors.newSingleThreadExecutor { th ->
            Thread(th, "lan-sync-conflicts").apply { isDaemon = true }
        }
        refreshConflicts()
    }

    // ---------------- 生命周期 / 取消 ----------------

    override fun onStop() {
        // 相机是前台资源：一离开页面就关（扫码覆盖层也不留在后台）。
        closeScanner()
        // 离开页面（切走、锁屏、进后台）= 停止：断开在途请求，绝不后台跑。
        if (!isChangingConfigurations) {
            if (current != null) cancelSync()
            clearSecret()
        }
        super.onStop()
    }

    /**
     * 旋转等界面重建时，同步不能跨实例保留（没有后台服务、没有 retained secret）。
     * 这里只记一个「未完成被打断」的布尔位，让新实例提示重新配对；
     * 密钥、连接串、目标地址和运行状态一律不保存。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        val r = current
        if (r != null && !r.done) outState.putBoolean(STATE_INTERRUPTED, true)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        val r = current
        if (r != null) {
            // 保守取消：宁可断开，也不让旧实例的线程在后台继续跑。
            r.detached = true
            r.cancelled.set(true)
            current = null
            synchronized(r) { r.conn?.disconnect(); r.conn = null }
            r.thread?.interrupt()
            Arrays.fill(r.key, 0)
        }
        worker?.shutdownNow()
        worker = null
        io?.shutdownNow()
        io = null
        closeScanner()
        clearSecret()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 扫码覆盖层是页面内的一层模式：先退扫码，再按一次才离开本页（相机同时关掉）。
        if (scanner != null) {
            closeScanner()
            status.text = "已取消扫描，仍可粘贴配对串。"
            return
        }
        if (current != null) cancelSync()
        super.onBackPressed()
    }

    /** 清掉输入框里的配对串（字符串不可擦，只能让它尽快失去引用）。 */
    private fun clearSecret() {
        if (::input.isInitialized) input.setText("")
    }

    private fun setRunningUi(running: Boolean) {
        input.isEnabled = !running
        syncBtn.isEnabled = !running
        cancelBtn.isEnabled = running
        scanBtn.isEnabled = !running && !resolving
    }

    // ---------------- 扫码（同页覆盖层） ----------------

    private fun requestScan() {
        if (current != null) {
            status.text = "同步进行中，完成后再扫码。"
            return
        }
        // 懒申请：只在用户点「扫描」时才要相机权限；拒绝后粘贴方式完全不受影响。
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanner()
        } else {
            status.text = "未授予相机权限，仍可粘贴配对串，不影响同步。"
        }
    }

    private fun startScanner() {
        if (scanner != null) return
        status.text = "把电脑上「显示二维码」里的码放进框内。"
        scanner = LanQrScanner(
            activity = this,
            host = root,
            onResult = { text ->
                scanner = null
                onScanned(text)
            },
            onError = { message ->
                scanner = null
                status.text = message
            },
            onCancel = {
                // 覆盖层已由 LanQrScanner 自己关掉；这里只清引用并保留粘贴方式。
                scanner = null
                status.text = "已取消扫描，仍可粘贴配对串。"
            },
        ).also { it.start() }
    }

    /** 回调只把**校验通过**的原文写进遮蔽输入框；不落盘、不进 Bundle / Intent。 */
    private fun onScanned(raw: String) {
        val text = raw.trim()
        try {
            val target = parseTarget(text)
            Arrays.fill(target.key, 0)   // 只是预校验，真正的密钥在点同步时再解析
            input.setText(text)
            status.text = "已扫描到配对串（输入框仍遮蔽显示），点「开始同步」。"
        } catch (e: BadInput) {
            status.text = "二维码不是有效的配对串：${e.message}"
        }
    }

    private fun closeScanner() {
        scanner?.stop()
        scanner = null
    }

    // ---------------- 冲突处理 ----------------

    /** 读冲突列表是磁盘 / JSON 工作，必须在 IO 线程；回到主线程才动 View。 */
    private fun refreshConflicts() {
        val exec = io ?: return
        if (!::conflictHost.isInitialized) return
        exec.execute {
            try {
                val arr = LanSyncStore(applicationContext).listConflicts()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) renderConflicts(arr)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "读取冲突列表失败：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    private fun renderConflicts(arr: JSONArray) {
        // 签名只取 uid / side / resolved，避免把整段卡面内容塞进字符串比较。
        val sig = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sig.append(o.optString("copyUid")).append(':')
                .append(o.optString("side")).append(':')
                .append(o.optBoolean("resolved")).append(';')
        }
        val signature = sig.toString()
        if (signature == lastConflictSig) return
        lastConflictSig = signature
        conflictHost.removeAllViews()
        if (arr.length() == 0) {
            conflictHost.addView(TextView(this).apply {
                text = "没有待处理的冲突副本。"
                textSize = 14f
                alpha = 0.8f
                setPadding(0, dp(4f), 0, dp(2f))
            })
            return
        }
        LanConflictPanel.render(this, conflictHost, arr) { copyUid, pick ->
            resolveConflict(copyUid, pick)
        }
    }

    private fun resolveConflict(copyUid: String, pick: ConflictPick) {
        if (current != null) {
            status.text = "同步进行中，完成后再处理冲突。"
            return
        }
        if (resolving) return
        val exec = io ?: return
        resolving = true
        setRunningUi(false)
        syncBtn.isEnabled = false
        status.text = "正在应用冲突选择…"
        exec.execute {
            val message = try {
                val result = LanSyncStore(applicationContext).resolveConflict(copyUid, pick)
                "已处理：${pickLabel(pick)}。剩余待处理冲突 ${result.optInt("conflicts", 0)} 个。"
            } catch (e: Exception) {
                "解决冲突失败：${e.message ?: e.javaClass.simpleName}（可重试）"
            }
            runOnUiThread {
                resolving = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                setRunningUi(current != null)
                status.text = message
                lastConflictSig = null
                refreshConflicts()
            }
        }
    }

    private fun pickLabel(pick: ConflictPick): String = when (pick) {
        ConflictPick.KEEP_BASE -> "保留主版本"
        ConflictPick.KEEP_COPY -> "保留副本"
        ConflictPick.KEEP_BOTH -> "两条都保留"
    }

    private fun cancelSync() {
        val r = current ?: return
        // 取消 / 离开页面后，迟到回调一律不许再显示成功。
        r.detached = true
        if (r.done) return
        if (!r.cancelled.compareAndSet(false, true)) return
        current = null
        synchronized(r) {
            r.conn?.disconnect()
            r.conn = null
        }
        r.thread?.interrupt()
        worker?.shutdownNow()
        worker = null
        Arrays.fill(r.key, 0)
        setRunningUi(false)
        status.text = CANCEL_TEXT
    }

    // ---------------- 启动一次同步 ----------------

    private fun startSync() {
        if (current != null) return
        val target = try {
            parseTarget(input.text.toString())
        } catch (e: BadInput) {
            status.text = "未完成，重试安全。${e.message}"
            return
        }

        val r = Run(target.host, target.port, target.key)
        current = r
        setRunningUi(true)
        status.text = "正在同步…只在前台进行；点「取消」会立即断开连接。"

        worker = Executors.newSingleThreadExecutor { th ->
            Thread(th, "ling-lan-sync").apply { isDaemon = true }
        }
        worker?.execute { runSync(r) }
    }

    private fun runSync(r: Run) {
        try {
            r.thread = Thread.currentThread()
            if (r.cancelled.get()) return

            // 文件 IO 与 JSON 都在这个后台线程里做：主线程只负责输入与显示。
            // 本机读 / 写单独包一层阶段，磁盘 IOException 不会被 reasonOf 误报成网络中断。
            val store = LanSyncStore(applicationContext)
            val local: JSONArray = localPhase(PHASE_READ) { store.export() }
            if (r.cancelled.get()) return

            val body = buildRequest(local, r.key)
            if (r.cancelled.get()) return

            val response = exchange(r, body)
            if (r.cancelled.get()) return

            val remote: JSONObject = decodeResponse(response, r.key)
            if (r.cancelled.get()) return

            val records = remote.getJSONArray("records")

            // 简单门闩：落库前最后确认一次；取消后绝不再启动新的 apply。
            if (r.cancelled.get()) return
            val result = localPhase(PHASE_WRITE) { store.apply(records) }
            if (r.cancelled.get()) return
            r.done = true

            postIfCurrent(r) {
                status.text = completionText(result)
                lastConflictSig = null
                refreshConflicts()
            }
        } catch (e: LocalFailure) {
            if (!r.cancelled.get()) {
                postIfCurrent(r) {
                    status.text = failure(localReason(e), sent = e.phase == PHASE_WRITE)
                }
            }
        } catch (e: SyncFailure) {
            if (!r.cancelled.get()) postIfCurrent(r) { status.text = failure(e.reason, e.sent) }
        } catch (e: Exception) {
            if (!r.cancelled.get()) postIfCurrent(r) { status.text = failure(reasonOf(e, r.stage), sent = true) }
        } finally {
            Arrays.fill(r.key, 0)
            runOnUiThread {
                if (current === r) {
                    current = null
                    worker?.shutdown()
                    worker = null
                    setRunningUi(false)
                }
            }
        }
    }

    /** 只包住本机阶段：任何异常都带上阶段名，交给 [localReason] 分类，绝不落到网络错误分支。 */
    private inline fun <T> localPhase(phase: String, block: () -> T): T =
        try {
            block()
        } catch (e: LocalFailure) {
            throw e
        } catch (e: Exception) {
            throw LocalFailure(phase, e)
        }

    private fun localReason(f: LocalFailure): String {
        // 本机阶段永远按 LOCAL_IO / PROTOCOL 归类，绝不会被说成「电脑已关闭」。
        val kind = LanSyncErrors.classifyLocal(f.cause)
        val reason = when (kind) {
            SyncErrorKind.PROTOCOL ->
                if (f.phase == PHASE_READ) {
                    "本机数据格式异常，本次未继续。"
                } else {
                    "电脑返回的合并数据不合法，本次未应用。"
                }
            else -> when (val e = f.cause) {
                // LanSyncException 也是 IllegalStateException，必须先判。
                is LanSyncException ->
                    "本机落盘失败，已保留恢复日志；重新进入页面再试会自动恢复，这次不算同步成功。"
                is IllegalStateException ->
                    "本机资料库过大，超过同步容量上限，本次未继续（不是网络问题）。"
                else ->
                    if (f.phase == PHASE_READ) {
                        "本机磁盘读写出错（不是网络问题）。请检查手机存储空间后再试。"
                    } else {
                        "本机磁盘落盘出错（不是网络问题）。请检查手机存储空间后再试。"
                    }
            }
        }
        return "${f.phase}失败：$reason"
    }

    /** 完成文案：两类记录各自的新增 / 更新 / 删除 / 恢复 / 冲突，以及同步前后总数。 */
    private fun completionText(result: JSONObject): String {
        fun line(name: String, o: JSONObject?): String {
            if (o == null) return "$name：统计不可用"
            return "$name：新增 ${o.optInt("added")} · 更新 ${o.optInt("updated")} · " +
                "删除 ${o.optInt("deleted")} · 恢复 ${o.optInt("restored")} · " +
                "冲突 ${o.optInt("conflicts")}（${o.optInt("before")} → ${o.optInt("after")}）"
        }
        val text = StringBuilder("完成。\n")
        text.append(line("知识卡", result.optJSONObject("cards"))).append('\n')
        text.append(line("收藏", result.optJSONObject("history")))
        val conflicts = result.optInt("conflicts", 0)
        if (conflicts > 0) {
            text.append("\n本次新增冲突副本 $conflicts 个，下面可以逐个选择保留哪一版。")
        } else {
            text.append("\n本次没有新增冲突。重复同步只会合并，不会重复。")
        }
        return text.toString()
    }

    private fun postIfCurrent(r: Run, block: () -> Unit) {
        runOnUiThread {
            if (current === r && !r.detached && !r.cancelled.get() && !isFinishing && !isDestroyed) {
                block()
            }
        }
    }

    private fun failure(reason: String, sent: Boolean): String =
        if (sent) {
            "未完成，重试安全。$reason 若请求已发出，电脑可能已收到；再点同步会自动合并。"
        } else {
            "未完成，重试安全。$reason"
        }

    // ---------------- 连接串 ----------------

    private data class Target(val host: String, val port: Int, val key: ByteArray)

    /**
     * 严格解析 `ling-sync://IPv4:port/<base64url32bytekey>`。
     * 只接受字面 IPv4（不做 DNS），私网 / 回环之外的地址一律拒绝，避免把密钥发给公网。
     */
    private fun parseTarget(raw: String): Target {
        val s = raw.trim()
        if (s.isEmpty()) throw BadInput("先粘贴电脑上显示的连接串。")
        if (s.length > 512) throw BadInput("连接串太长，不像有效的配对串。")
        if (!s.startsWith(SCHEME)) throw BadInput("配对串应以 ling-sync:// 开头。")

        val rest = s.substring(SCHEME.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) throw BadInput("配对串缺少 /密钥。")
        val authority = rest.substring(0, slash)
        val keyText = rest.substring(slash + 1)
        if (keyText.isEmpty() || keyText.contains('/') || keyText.contains('?') || keyText.contains('#')) {
            throw BadInput("密钥格式不对（应是一段 base64url，不含其它路径或参数）。")
        }

        val colon = authority.lastIndexOf(':')
        if (colon <= 0 || authority.indexOf(':') != colon) {
            throw BadInput("地址应是 IPv4:端口（不支持 IPv6 或主机名）。")
        }
        val host = authority.substring(0, colon)
        val portText = authority.substring(colon + 1)
        if (portText.isEmpty() || portText.length > 5 || portText.any { it !in '0'..'9' }) {
            throw BadInput("端口不是有效数字。")
        }
        val port = portText.toIntOrNull() ?: throw BadInput("端口不是有效数字。")
        if (port !in 1024..65535) throw BadInput("端口应在 1024–65535。")

        val ip = parseIpv4(host) ?: throw BadInput("只接受字面 IPv4 地址（例如 192.168.1.23）。")
        if (!isPrivateOrLoopback(ip)) {
            throw BadInput("只接受局域网地址（10.x / 172.16–31.x / 192.168.x）或回环地址。")
        }

        if (keyText.length != 43 || !keyText.all(::isBase64UrlChar)) {
            throw BadInput("密钥应是 32 字节的 base64url（43 个字符，不带 =）。")
        }
        val key = try {
            Base64.getUrlDecoder().decode(keyText)
        } catch (_: IllegalArgumentException) {
            throw BadInput("密钥不是合法的 base64url。")
        }
        if (key.size != KEY_BYTES) {
            Arrays.fill(key, 0)
            throw BadInput("密钥不是 32 字节。")
        }
        // 拒绝非规范编码（末位多余 bit 之类的变体），保证同一串只有一种解释。
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(key) != keyText) {
            Arrays.fill(key, 0)
            throw BadInput("密钥不是规范的 base64url。")
        }
        return Target(host, port, key)
    }

    private fun parseIpv4(host: String): IntArray? {
        if (host.isEmpty() || host.length > 15) return null
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            if (p.length > 1 && p[0] == '0') return null   // 不做八进制 / 前导零的歧义解析
            if (p.any { it !in '0'..'9' }) return null
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    private fun isPrivateOrLoopback(ip: IntArray): Boolean {
        val a = ip[0]
        val b = ip[1]
        return a == 10 ||                       // 10.0.0.0/8（含模拟器测试用的 10.0.2.2）
            (a == 172 && b in 16..31) ||        // 172.16.0.0/12
            (a == 192 && b == 168) ||           // 192.168.0.0/16
            a == 127                            // 127.0.0.0/8
    }

    private fun isBase64UrlChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    // ---------------- 加密 / 解密 ----------------

    private fun buildRequest(records: JSONArray, key: ByteArray): ByteArray {
        val plain = JSONObject()
            .put("v", VERSION)
            .put("records", records)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_BYTES) {
            throw SyncFailure(
                "本机资料库过大：导出数据超过 8 MiB，本次未发送（不是网络问题）。",
                sent = false,
            )
        }

        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val cipherText = cipher.doFinal(plain)

        val body = JSONObject()
            .put("v", VERSION)
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("data", Base64.getEncoder().encodeToString(cipherText))
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (body.size > MAX_BYTES) {
            throw SyncFailure(
                "本机资料库过大：加密后的请求超过 8 MiB，本次未发送（不是网络问题）。",
                sent = false,
            )
        }
        return body
    }

    private fun decodeResponse(body: ByteArray, key: ByteArray): JSONObject {
        val envelope = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            throw SyncFailure("电脑返回的不是合法 JSON。")
        }
        checkVersion(envelope)

        val ivText = envelope.opt("iv") as? String
            ?: throw SyncFailure("响应缺少 iv。")
        val dataText = envelope.opt("data") as? String
            ?: throw SyncFailure("响应缺少 data。")

        val iv = try {
            Base64.getDecoder().decode(ivText)
        } catch (_: IllegalArgumentException) {
            throw SyncFailure("响应 iv 不是合法 Base64。")
        }
        if (iv.size != IV_BYTES) throw SyncFailure("响应 iv 长度不是 12 字节。")

        val cipherText = try {
            Base64.getDecoder().decode(dataText)
        } catch (_: IllegalArgumentException) {
            throw SyncFailure("响应 data 不是合法 Base64。")
        }
        if (cipherText.size < TAG_BYTES) throw SyncFailure("响应密文长度不足（缺少 GCM 认证标签）。")

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(cipherText)
        } catch (_: AEADBadTagException) {
            // 认证失败就是失败：不重试明文、不降级、不关认证。
            throw SyncFailure("认证失败：配对串密钥不匹配或数据被改，未应用任何数据。")
        } catch (_: BadPaddingException) {
            // 个别提供方把 GCM tag 失败包成 BadPaddingException —— 同样按认证失败处理。
            throw SyncFailure("认证失败：配对串密钥不匹配或数据被改，未应用任何数据。")
        } catch (_: GeneralSecurityException) {
            throw SyncFailure("解密失败：加密参数不被接受，未应用任何数据。")
        }

        val payload = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            throw SyncFailure("响应明文不是合法 JSON。")
        }
        checkVersion(payload)

        val records = payload.optJSONArray("records")
            ?: throw SyncFailure("响应缺少 records 数组。")
        // 服务端附带的统计只做类型核对；真正报给用户的数字以本机 apply 结果为准。
        for (name in listOf("conflicts", "cards", "stars")) {
            if (!payload.has(name)) continue
            val n = payload.opt(name)
            if (n !is Number || n.toDouble() < 0 || n.toDouble() != Math.floor(n.toDouble())) {
                throw SyncFailure("响应字段 $name 不合法。")
            }
        }
        // records 的元素由 LanSyncStore.apply 做完整校验（它也负责 FileMemo 的锁）。
        return payload
    }

    private fun checkVersion(o: JSONObject) {
        val v = o.opt("v")
        if (v !is Number || v.toInt() != VERSION || v.toDouble() != VERSION.toDouble()) {
            throw SyncFailure("协议版本不符（需要 v=1）。")
        }
    }

    // ---------------- HTTP ----------------

    private fun exchange(r: Run, body: ByteArray): ByteArray {
        val url = URL("http://${r.host}:${r.port}$PATH")
        var connection: HttpURLConnection? = null
        try {
            // 局域网直连：绕过系统代理，别把带密钥的请求交给代理。
            val c = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                setFixedLengthStreamingMode(body.size)
            }
            connection = c
            synchronized(r) {
                if (r.cancelled.get()) throw SyncFailure("已取消。")
                r.conn = c
            }

            // 阶段只用于错误分类：CONNECT 超时 ≠ 读取超时，也绝不把超时说成「电脑已关闭」。
            r.stage = SyncStage.CONNECT
            c.connect()
            r.stage = SyncStage.WRITE
            c.outputStream.use { out -> out.write(body) }

            r.stage = SyncStage.READ
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 读 errorStream（最多 1 KiB）只用于分类，不显示原始正文。
                val snippet = readErrorSnippet(c)
                val kind = LanSyncErrors.classify(SyncStage.READ, code, null, snippet)
                throw SyncFailure(LanSyncErrors.message(kind, code))
            }

            val declared = c.contentLengthLong
            if (declared > MAX_BYTES) {
                throw SyncFailure("电脑返回的数据超过 8 MiB，已停止（是数据量上限，不是连接中断）。")
            }
            return c.inputStream.use { stream -> readCapped(stream) }
        } catch (e: SyncFailure) {
            throw e
        } catch (e: Exception) {
            // 取消会主动 disconnect，迟到 IOException 直接上抛，由 runSync 的 cancelled 判断吞掉。
            if (r.cancelled.get()) throw e
            val kind = LanSyncErrors.classify(r.stage, null, e)
            throw SyncFailure(LanSyncErrors.message(kind))
        } finally {
            synchronized(r) {
                if (r.conn === connection) r.conn = null
            }
            connection?.disconnect()
        }
    }

    /** 错误页最多读 1 KiB；读不到 / 被中断都不影响按状态码分类。 */
    private fun readErrorSnippet(c: HttpURLConnection): String = try {
        val input = c.errorStream ?: return ""
        input.use { stream ->
            val buf = ByteArray(MAX_ERROR_BYTES)
            var n = 0
            while (n < buf.size) {
                val read = stream.read(buf, n, buf.size - n)
                if (read < 0) break
                n += read
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        ""
    }

    /** 流式读取并封顶，绝不按 Content-Length 直接分配。 */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > MAX_BYTES) {
                throw SyncFailure("电脑返回的数据超过 8 MiB，已停止（是数据量上限，不是连接中断）。")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun reasonOf(e: Throwable, stage: SyncStage): String {
        val kind = LanSyncErrors.classify(stage, null, e)
        return LanSyncErrors.message(kind)
    }
}

// ---------------- 错误分类（纯 JVM，便于单测） ----------------

internal enum class SyncStage { CONNECT, WRITE, READ }

internal enum class SyncErrorKind {
    NO_SERVICE,
    PAIR_REJECTED,
    UNREACHABLE,
    READ_TIMEOUT,
    SERVER_BUSY,
    HTTP,
    LOCAL_IO,
    PROTOCOL,
}

/**
 * 手机端错误分类：读 errorStream（≤1 KiB）+ 状态码 + 阶段（CONNECT / WRITE / READ）。
 *
 * 映射：
 *  - `ECONNREFUSED` / 连接被拒 → NO_SERVICE；
 *  - 401 → PAIR_REJECTED（**不区分**过期 / 密钥错误，文案只让用户重新配对）；
 *  - 连接超时 / 无路由 / 地址不可解析 → UNREACHABLE；
 *  - 已连上后的读等待超时 → READ_TIMEOUT；
 *  - 503 / 408 / 409 → SERVER_BUSY；其它非 200 → HTTP；
 *  - 本机读写失败 → LOCAL_IO；JSON / 协议不符 → PROTOCOL。
 *
 * 任何超时文案都不说「电脑已关闭」：只能说明这 10 / 30 秒内没等到回应。
 */
internal object LanSyncErrors {
    fun classify(
        stage: SyncStage,
        code: Int? = null,
        error: Throwable? = null,
        body: String? = null,
    ): SyncErrorKind {
        if (error != null) {
            when (error) {
                is SocketTimeoutException ->
                    return if (stage == SyncStage.CONNECT) SyncErrorKind.UNREACHABLE else SyncErrorKind.READ_TIMEOUT
                is ConnectException -> {
                    val text = (error.message.orEmpty() + " " + body.orEmpty()).lowercase()
                    return if (text.contains("econnrefused") || text.contains("connection refused")) {
                        SyncErrorKind.NO_SERVICE
                    } else {
                        SyncErrorKind.UNREACHABLE
                    }
                }
                is NoRouteToHostException, is UnknownHostException -> return SyncErrorKind.UNREACHABLE
                is SecurityException -> return SyncErrorKind.LOCAL_IO
                is LanSyncException -> return SyncErrorKind.LOCAL_IO
                is LanSyncCorruptException -> return SyncErrorKind.PROTOCOL
                // 网络类 IOException 的已知子类（超时 / 拒连 / 无路由 / 域名）都已在上面的分支判掉；
                // 走到这里的 IOException 按本机文件 / journal 读写失败处理，不再笼统算成「电脑不可达」。
                is IOException -> return SyncErrorKind.LOCAL_IO
                is org.json.JSONException -> return SyncErrorKind.PROTOCOL
                is IllegalArgumentException -> return SyncErrorKind.PROTOCOL
                is IllegalStateException -> return SyncErrorKind.LOCAL_IO
            }
        }
        if (code != null && code != 200) {
            return when (code) {
                401 -> SyncErrorKind.PAIR_REJECTED
                503, 408, 409 -> SyncErrorKind.SERVER_BUSY
                else -> SyncErrorKind.HTTP
            }
        }
        return SyncErrorKind.PROTOCOL
    }

    /** 本机阶段（export / apply / resolve）只可能是本机 IO 或数据格式问题。 */
    fun classifyLocal(error: Throwable?): SyncErrorKind = when (error) {
        is LanSyncException -> SyncErrorKind.LOCAL_IO
        is IOException -> SyncErrorKind.LOCAL_IO
        is SecurityException -> SyncErrorKind.LOCAL_IO
        is LanSyncCorruptException -> SyncErrorKind.PROTOCOL
        is IllegalArgumentException -> SyncErrorKind.PROTOCOL
        is IllegalStateException -> SyncErrorKind.LOCAL_IO
        else -> SyncErrorKind.LOCAL_IO
    }

    fun message(kind: SyncErrorKind, code: Int? = null): String = when (kind) {
        SyncErrorKind.NO_SERVICE ->
            "电脑没有接受连接（确认电脑端已开启「手机连接」、端口一致、在同一局域网）。"
        SyncErrorKind.PAIR_REJECTED ->
            "电脑拒绝了这次配对（HTTP 401）。配对串可能已不匹配，请重新从电脑复制或扫码。"
        SyncErrorKind.UNREACHABLE ->
            "连不上电脑：可能不在同一 Wi-Fi、地址或端口不对，或电脑暂时不可达。"
        SyncErrorKind.READ_TIMEOUT ->
            "已连上电脑，但等待回应超时。电脑可能在忙，稍后重试即可；这只是这一次没等到回应，不代表电脑已关机或离线。"
        SyncErrorKind.SERVER_BUSY ->
            "电脑端正忙（HTTP ${code ?: "忙"}），稍后重试即可。"
        SyncErrorKind.HTTP ->
            "电脑返回 HTTP ${code ?: "错误"}，本次未应用。"
        SyncErrorKind.LOCAL_IO ->
            "本机读写出错（不是网络问题）。"
        SyncErrorKind.PROTOCOL ->
            "协议或数据格式不符，本次未应用任何数据（不是电脑关闭）。"
    }
}
