package com.chun.lingzhu.voice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chun.lingzhu.Brut
import com.chun.lingzhu.Prefs
import com.chun.lingzhu.Preset
import com.chun.lingzhu.Ui
import java.util.concurrent.Executors

/**
 * 「实时转写」页：选声音来源 + 服务商 → 填那家的 Key → 同意隐私提示 → 开始 → 看文字。
 *
 * 页面只做这几件事，别的都不做（没有建议生成、没有导入音频 / 文字、没有历史）：
 *  1. **声音来源**（[VoiceSource]）：麦克风 / 系统声音 / 两者混录。系统声音那两档要 Android 10 起，
 *     低版本**显示为不可选并说明原因**，绝不悄悄退回麦克风。换来源会先停下当前会话。
 *  2. **选择与密钥**：豆包 / 阿里各一条 Key，各存各的（AndroidKeyStore AES-GCM 密文，见 [VoiceKeyStore]）。
 *     输入框始终遮蔽；已保存的 Key 从不回显，**留空 = 沿用**，只有点「删除已保存」并二次确认才真的删。
 *  3. **隐私同意**：按「服务商 × 声音来源」各记一次 —— 同意把麦克风发给豆包，不等于同意把系统播放的声音也发给它。
 *  4. **开始 / 停止**：采集在 [VoiceService]（前台服务 + 常驻通知，通知里也能停）；
 *     本页只用 Binder 读状态，不自己开 AudioRecord。系统声音那两档在这里先弹系统的共享 / 授权框，
 *     拿到 token 才启动服务（服务侧在 `getMediaProjection` 之前已经以 `mediaProjection` 类型前台运行）。
 *  5. **显示**：partial 用次要色斜体、final 用墨色，按句原位替换；文字只在内存。
 *     下方按**每一路**分别显示「已检测到声音 / 未检测到声音」，混录时麦克风有人说话不会掩盖系统那一路全静音。
 *
 * 三条不将就的地方：
 *  - **「正在录音」只在真的送出第一帧音频之后才显示**（服务侧 [VoiceSession.Phase.RECORDING]）；
 *    连接中 / 开设备中显示「正在准备」，失败显示失败原因 —— 绝不提前说在录。
 *  - **改配置不影响正在跑的会话**：配置在开始时快照一份；换服务商 / 换来源 / 删 Key 会先停下当前会话。
 *  - **停止即释放设备**：服务先停采集再收尾（等最后一句，有上限），文字保留到离开页面。
 */
class VoiceActivity : Activity() {

    private lateinit var settings: VoiceSettings
    private lateinit var keys: VoiceKeyStore

    private lateinit var providerRow: LinearLayout
    private lateinit var sourceRow: LinearLayout
    private lateinit var sourceLine: TextView
    private lateinit var keyLine: TextView
    private lateinit var keyInput: EditText
    private lateinit var saveKeyBtn: Button
    private lateinit var deleteKeyBtn: Button
    private lateinit var consentBtn: Button
    private lateinit var consentLine: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var stateLine: TextView
    private lateinit var routeLine: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var emptyLine: TextView

    private val providerMarks = LinkedHashMap<String, TextView>()
    private val sourceMarks = LinkedHashMap<String, TextView>()

    /** 正在展示的服务商（切服务商时用它判断要不要重读 Key / 重画隐私行）。 */
    private var shownProvider = ""
    private var shownHasKey = false

    /** 正在展示 / 正在用的声音来源（隐私同意、开始按钮都按它判）。 */
    private var shownSource = VoiceSource.MIC

    /** 读 / 写 Key 是 Keystore IO：统一放这里，别在主线程上碰。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-settings-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private var service: VoiceService? = null

    /** 当前注册进服务的 binder（解绑前要先摘监听，否则服务里留着一个指向已销毁 Activity 的引用）。 */
    private var binderRef: VoiceService.LocalBinder? = null

    /**
     * 「已经向系统要过绑定、还没撤回」——**与 [bound] 不是一回事**。
     *
     * `bindService` 返回 true 之后到 `onServiceConnected` 回调之间有一段真空期：这期间用户按 Back / Home，
     * `onStop` 会执行。以前只用 [bound] 判，那个还是 false，于是跳过 unbind —— 系统随后照样把服务连上来，
     * binder 永远留着，一个已经停掉的 Activity 挂在服务的监听列表里。所以要按「请求过」来撤。
     */
    private var bindRequested = false

    /** 真正拿到 binder（[onServiceConnected] 之后才为 true）。 */
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // 迟到的回调：页面已经在 onStop 里撤回了绑定请求，就别再挂监听、别再改界面。
            if (!bindRequested || isFinishing || isDestroyed) return
            val local = binder as? VoiceService.LocalBinder ?: return
            service = local.service
            binderRef = local
            bound = true
            local.addListener(listener)
            // 服务刚被 bindService 拉起来时它的快照是空白的「未在转写」，会把上一次的失败原因盖掉 ——
            // 两边的状态都不在跑时，优先显示那条带错误的具体原因。
            val snap = local.snapshot()
            val keep = VoiceSession.last
            render(if (snap.phase == VoiceSession.Phase.IDLE && snap.text == "未在转写" && keep.error) keep else snap)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binderRef = null
            bound = false
            service = null
            // 服务没了（被系统回收 / 自己停了）：界面按共享状态回到真实状态，不留假「正在录音」。
            render(VoiceSession.last)
        }
    }

    private val listener = object : VoiceService.Listener {
        override fun onVoiceUpdate(snap: VoiceSnapshot) {
            if (isFinishing || isDestroyed) return
            render(snap)
        }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        settings = VoiceSettings(this)
        keys = VoiceKeyStore(this)
        title = Preset.appName + " · 实时转写"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        Brut.page(this, scroll)

        col.addView(TextView(this).apply {
            text = "实时转写"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(10f))
        })

        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        intro.addView(
            hint(
                "把声音边说边转成字。声音来源可选：麦克风 / 手机正在播放的系统声音 / 两者混录；" +
                    "两家语音服务可选，各用各的 Key；声音实时上传到你选的那一家。" +
                    "这里不存转写文字、也不保存音频：不写文件、不进历史、不参与同步。" +
                    "关掉这一页会停转写并清空；按 Home 切走会继续（通知里可停），文字只留在内存里。", 13.5f,
            )
        )
        intro.addView(
            hint(
                "不做的事：不导入音频或文字、不生成建议、不录通话。系统声音靠系统的播放捕获，" +
                    "部分应用（DRM 保护的播放器、某些会议应用）会禁止被捕获 —— 那时会如实显示「未检测到声音」，不会替它下别的结论。",
                13.5f,
            ).apply { setPadding(0, dp(8f), 0, 0) }
        )
        col.addView(intro)
        Brut.card(intro)

        // ---------------- 声音来源 ----------------
        col.addView(Brut.heading(this, "声音来源"))
        val srcCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sourceRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        srcCard.addView(sourceRow)
        sourceLine = hint("", 12.5f).apply { setPadding(0, dp(8f), 0, 0) }
        srcCard.addView(sourceLine)
        col.addView(srcCard)
        Brut.card(srcCard)

        // ---------------- 服务商 ----------------
        col.addView(Brut.heading(this, "语音服务商"))
        val provCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        providerRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        provCard.addView(providerRow)
        provCard.addView(
            hint("换一家会先停下当前正在跑的转写；另一家已保存的 Key 不动。", 12.5f)
                .apply { setPadding(0, dp(8f), 0, 0) }
        )
        col.addView(provCard)
        Brut.card(provCard)

        // ---------------- Key ----------------
        col.addView(Brut.heading(this, "API Key"))
        val keyCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            tag = Brut.KEEP
        }
        keyCard.addView(keyLine)

        keyInput = EditText(this).apply {
            hint = "粘贴这一家的 API Key"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // Key 视同密码：不参与系统保存 / 自动填充，也不显示明文。
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(0, dp(10f), 0, dp(10f))
        }
        keyCard.addView(keyInput)

        val keyActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveKeyBtn = Button(this).apply {
            text = "保存 Key"
            tag = Brut.PRIMARY
            setOnClickListener { saveKey() }
        }
        deleteKeyBtn = Button(this).apply {
            text = "删除已保存"
            tag = Brut.DANGER
            setOnClickListener { askDeleteKey() }
        }
        keyActions.addView(saveKeyBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8f) })
        keyActions.addView(deleteKeyBtn, LinearLayout.LayoutParams(0, -2, 1f))
        keyCard.addView(keyActions)
        keyCard.addView(
            hint(
                "输入框留空 = 沿用已保存的 Key（不必每次重贴）。Key 用 AndroidKeyStore 的 AES-256-GCM 加密后" +
                    "存在应用私有目录，两家各用各自的密钥；不进系统备份、不写日志、不回显明文。", 12.5f,
            ).apply { setPadding(0, dp(10f), 0, 0) }
        )
        col.addView(keyCard)
        Brut.card(keyCard)

        // ---------------- 隐私同意 ----------------
        col.addView(Brut.heading(this, "隐私与授权"))
        val consentCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        consentLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            tag = Brut.KEEP
        }
        consentCard.addView(consentLine)
        consentBtn = Button(this).apply {
            text = "我已了解，允许上传声音"
            setOnClickListener { toggleConsent() }
        }
        consentCard.addView(consentBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(consentCard)
        Brut.card(consentCard)

        // ---------------- 转写 ----------------
        col.addView(Brut.heading(this, "转写"))
        val runCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stateLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.35f)
            tag = Brut.KEEP
        }
        runCard.addView(stateLine)
        routeLine = hint("", 12.5f).apply { setPadding(0, dp(6f), 0, 0) }
        runCard.addView(routeLine)

        val runActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply {
            text = "开始转写"
            tag = Brut.PRIMARY
            setOnClickListener { onStartClicked() }
        }
        stopBtn = Button(this).apply {
            text = "停止"
            setOnClickListener { onStopClicked() }
        }
        runActions.addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
        runActions.addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
        runCard.addView(runActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(runCard)
        Brut.card(runCard)

        val outCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyLine = hint("还没有文字。点「开始转写」后说话，边说边出。", 13f)
        outCard.addView(emptyLine)
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outCard.addView(transcript)
        col.addView(outCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        Brut.card(outCard)

        col.addView(
            hint(
                "停止后采集用的麦克风 / 系统声音立即释放；最后一句会等服务商说完（通常一两秒，最长约 8 秒）。" +
                    "关掉这一页会停转写并清空文字。",
                12.5f,
            ).apply { setPadding(0, dp(12f), 0, 0) }
        )

        Brut.style(col)
        buildSourceRow()
        selectSource(settings.source, force = true)
        buildProviderRow()
        selectProvider(settings.selectedProvider, force = true)
        // 先把「上一次会话留下的真实状态 + 已听到的句子」画出来，等绑定上后再按服务的快照覆盖。
        render(VoiceSession.last)
    }

    override fun onStart() {
        super.onStart()
        // 按「请求过没有」判，不是按「连上没有」：bind 返回 true 但回调还没来的时候，onStart 仍要拦一次。
        if (!bindRequested) {
            val ok = runCatching {
                bindService(Intent(this, VoiceService::class.java), conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            // 返回 true 就立刻记下来 —— 系统随后一定会回调（或者被我们撤回），两种都不能漏。
            if (ok) bindRequested = true else render(VoiceSession.last)
        }
    }

    override fun onStop() {
        // 只解绑，不停会话：用户切到别的 app 说话时录音要继续（麦克风由前台服务持有）。
        unbindServiceCompat()
        super.onStop()
    }

    /**
     * 撤回绑定请求并摘掉监听。
     *
     * 关键点：`bindService` 返回 true 之后、`onServiceConnected` 之前按 Back / Home 时，[bound] 还是 false
     * 但请求已经发出去了 —— 所以这里按 [bindRequested] 撤，否则系统照样把服务连上来，
     * 一个已经停掉的 Activity 会永久挂在服务的监听列表里。撤回后 [onServiceConnected] 也会直接拒绝。
     */
    private fun unbindServiceCompat() {
        runCatching { binderRef?.removeListener(listener) }
        binderRef = null
        if (bindRequested) {
            runCatching { unbindService(conn) }
            bindRequested = false
        }
        bound = false
        service = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        // 只有「真的关掉这一页」（返回键 / 划掉）才停会话并清文字；转屏、分屏重建不走这里。
        //
        // 为什么关闭页面就停：这一页是唯一的显示窗口，页面没了服务还在收麦克风、还在往内存窗口写，
        // 用户既看不见、也拿不回那段文字 —— 「关了还在录」是最容易被误解成偷录的状态。
        // 按 Home 键切走不算关闭（不进这里），前台服务继续跑，通知里随时能停。
        //
        // 必须走 [VoiceService.closeAndDiscard] 而不是 requestStop + reset：后者只「停」，
        // 收尾那一两秒里服务还会把最后一句写进窗口，于是刚清空的内容又被填回来，下次进页面就看见上次的字。
        if (isFinishing) {
            if (VoiceSession.last.phase != VoiceSession.Phase.IDLE) {
                val s = service
                if (s != null) runCatching { s.closeAndDiscard() } else VoiceService.close(this)
            }
            VoiceSession.reset()
        }
        super.onDestroy()
    }

    // ---------------- 声音来源 ----------------

    /**
     * 三档来源一次画齐。**不可用的档位照样显示**（灰掉 + 写明原因），而不是藏起来 ——
     * 用户换到低版本手机时需要知道「为什么这里没有系统声音」，而不是以为这功能不存在。
     */
    private fun buildSourceRow() {
        sourceRow.removeAllViews()
        sourceMarks.clear()
        for (src in VoiceSource.ALL) {
            val ok = VoiceSource.available(src)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                isClickable = ok
                isEnabled = ok
                alpha = if (ok) 1f else 0.45f
                if (ok) setOnClickListener { selectSource(src, force = false) }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                Ui.text(this, VoiceSource.label(src), 15f, Brut.INK).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    tag = Brut.KEEP
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            val mark = Ui.text(this, "", 13f, Brut.HINT).apply { tag = Brut.KEEP }
            line.addView(mark)
            row.addView(line)
            row.addView(hint(VoiceSource.detail(src), 12f))
            sourceRow.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            sourceMarks[src] = mark
        }
        sourceLine.text = if (Build.VERSION.SDK_INT >= VoiceSource.MIN_API) {
            "系统声音：使用系统的播放捕获，开始时会弹「共享 / 录制」授权框；只录 " +
                VoiceSystemAudio.usageSummary() + "。"
        } else {
            VoiceSource.unavailableReason(VoiceSource.SYSTEM) ?: ""
        }
    }

    /**
     * 切到某一档来源。[force] = 首次画页面（设置相同也要画一次）。
     *
     * 换来源与换服务商同一条规矩：**先把正在跑的会话停掉**，不让它半路换音源 ——
     * 一条会话的音频流里混两种来源，时间和内容都对不上。
     */
    private fun selectSource(src: String, force: Boolean) {
        if (!force && src == shownSource) return
        if (!force && src == settings.source) return
        // 用户自己点的一档：这台设备不支持就不切，明确说原因（绝不改录麦克风）。
        // 首次画页面（[force]）例外：设置里存的就是这一档，必须如实画成「已选」并说明它不可用 ——
        // 否则界面会显示「麦克风已选」而设置里其实是系统声音，按钮与同意行都会认错档。
        val unavailable = VoiceSource.unavailableReason(src)
        if (unavailable != null && !force) {
            stateLine.text = unavailable
            return
        }
        if (!force && VoiceSession.last.phase != VoiceSession.Phase.IDLE) {
            service?.let { runCatching { it.requestStop() } } ?: VoiceService.stop(this)
            stateLine.text = "已停止当前转写（换声音来源要重新开始）。"
        }
        if (!force) settings.source = src
        shownSource = src
        for ((id, mark) in sourceMarks) {
            val on = id == src
            mark.text = when {
                on -> "● 已选"
                !VoiceSource.available(id) -> "不可用"
                else -> ""
            }
            mark.setTextColor(if (on) Brut.MINT else Brut.HINT)
        }
        // 设置里选中的这一档本机不支持：原因由 [render] 写在状态行（「开始」已按 available 关掉）。
        refreshConsent()
        renderStartStop()
    }

    // ---------------- 服务商与 Key ----------------

    private fun buildProviderRow() {
        providerRow.removeAllViews()
        providerMarks.clear()
        for (p in VoiceProvider.ALL) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                isClickable = true
                setOnClickListener { selectProvider(p, force = false) }
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                Ui.text(this, VoiceProvider.label(p), 15f, Brut.INK).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    tag = Brut.KEEP
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            val mark = Ui.text(this, "", 13f, Brut.HINT).apply { tag = Brut.KEEP }
            line.addView(mark)
            row.addView(line)
            row.addView(hint(VoiceProvider.detail(p), 12f))
            providerRow.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
            providerMarks[p] = mark
        }
    }

    /** 切到某一家。[force] = 首次画页面（设置相同也要画一次）。 */
    private fun selectProvider(p: String, force: Boolean) {
        if (!force && p == shownProvider) return
        if (!force && p == settings.selectedProvider) return
        // 换服务商 = 换上传目的地：正在跑的会话先停，不让它半路换家。
        if (!force && VoiceSession.last.phase != VoiceSession.Phase.IDLE) {
            service?.let { runCatching { it.requestStop() } } ?: VoiceService.stop(this)
            stateLine.text = "已停止当前转写（换服务商要重新开始）。"
        }
        if (!force) settings.selectedProvider = p
        shownProvider = p
        shownHasKey = false
        for ((id, mark) in providerMarks) {
            mark.text = if (id == p) "● 已选" else ""
            mark.setTextColor(if (id == p) Brut.MINT else Brut.HINT)
        }
        keyInput.setText("")
        keyInput.hint = "粘贴 " + VoiceProvider.label(p) + " 的 API Key"
        deleteKeyBtn.text = "删除已保存"
        deleteKeyBtn.isEnabled = false
        keyLine.text = "正在读取已保存的 Key…"
        refreshConsent()
        renderStartStop()
        val want = p
        io.execute {
            val saved = runCatching { keys.isSaved(want) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed || shownProvider != want) return@runOnUiThread
                shownHasKey = saved
                keyLine.text =
                    if (saved) "已保存 ${VoiceProvider.label(want)} 的 Key（加密存放，不回显）。"
                    else "还没有保存 ${VoiceProvider.label(want)} 的 Key。"
                deleteKeyBtn.isEnabled = saved
                renderStartStop()
            }
        }
    }

    /**
     * 隐私行按「服务商 × 来源」画。系统声音那两档录的是**别人说的话 / 视频里的声音**，
     * 与「录我自己的麦克风」是两件事，所以各问一次、各记一次。
     */
    private fun refreshConsent() {
        val p = shownProvider
        val src = shownSource
        val given = settings.consentGiven(p, src)
        val what = when (src) {
            VoiceSource.SYSTEM -> "手机正在播放的声音（" + VoiceSystemAudio.usageSummary() + "）"
            VoiceSource.MIXED -> "麦克风 + 手机正在播放的声音"
            else -> "麦克风声音"
        }
        consentLine.text =
            if (given) "已同意把${what}实时上传到 ${VoiceProvider.label(p)}（${VoiceSource.label(src)}）。"
            else "开始前需要你确认：${what}会实时上传到 ${VoiceProvider.label(p)}，由它把语音转成文字。" +
                "本机不保存音频，也不保存转写文字。换声音来源需要重新确认。"
        consentBtn.text = if (given) "撤回同意" else "我已了解，允许上传声音"
        val fill = if (given) Brut.CARD else Brut.MINT
        consentBtn.tag = if (given) null else Brut.PRIMARY
        Brut.button(consentBtn, fill)
        renderStartStop()
    }

    /**
     * 同意 / 撤回。撤回只在**停止之后**生效：正在上传时点撤回，先停下这条会话再记「未同意」，
     * 不让「界面说不同意」和「声音还在往上发」同时成立。
     */
    private fun toggleConsent() {
        val p = shownProvider
        val src = shownSource
        if (settings.consentGiven(p, src)) {
            if (VoiceSession.last.phase != VoiceSession.Phase.IDLE) {
                service?.let { runCatching { it.requestStop() } } ?: VoiceService.stop(this)
                stateLine.text = "已停止当前转写，撤回同意将在这一条结束后生效。"
            }
            settings.setConsent(p, src, false)
        } else {
            settings.setConsent(p, src, true)
        }
        refreshConsent()
    }

    private fun saveKey() {
        val p = shownProvider
        val typed = keyInput.text?.toString()?.trim() ?: ""
        if (typed.isEmpty()) {
            // 留空不覆盖：这是「沿用已保存的」，不是错误。
            keyLine.text =
                if (shownHasKey) "输入框是空的 —— 保持已保存的 Key 不变。"
                else "输入框是空的，还没保存任何 Key。"
            return
        }
        if (typed.length > MAX_KEY_LEN) {
            keyInput.setText("")
            keyLine.text = "这串太长了（超过 $MAX_KEY_LEN 字），不像 API Key，没有保存。"
            return
        }
        saveKeyBtn.isEnabled = false
        io.execute {
            val err = runCatching { keys.save(p, typed) }.exceptionOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 「保存」按钮和输入框是**两家共用的同一组 View**：先把它们恢复成可用，
                // 再判断结果属于哪一家。否则保存途中切到另一家时，下面那个 return 会把按钮永久留在禁用态
                // （按钮没人再打开它，用户只能退出页面重进才能再保存）。
                saveKeyBtn.isEnabled = true
                // 不论成败先清空输入框：明文不在 View 里多留一秒。
                keyInput.setText("")
                // 保存是异步的：这期间用户可能已经切到另一家。这时结果属于「上一家」，
                // 绝不能拿来改当前页面的状态（否则 B 会显示「已保存 A 的 Key」）。
                if (shownProvider != p) return@runOnUiThread
                if (err == null) {
                    shownHasKey = true
                    deleteKeyBtn.isEnabled = true
                    deleteKeyBtn.text = "删除已保存"
                    keyLine.text = "已保存 ${VoiceProvider.label(p)} 的 Key（加密存放）。"
                } else {
                    keyLine.text = "保存失败：" + (err.message ?: err.javaClass.simpleName)
                }
                renderStartStop()
            }
        }
    }

    /**
     * 删除是破坏性动作：页面内二次确认（4 秒内再点一次才删）。
     *
     * 删之前**必须先把当前会话停掉**：这条会话可能正拿这把 Key 在跑，一边删一边用，
     * 用户会以为「已经收回了」。停的是整条会话，所以不区分现在用哪一家 ——
     * 「删 Key 先把转写停掉」比「猜它现在用不用得上」更不容易错。
     */
    private fun askDeleteKey() {
        val p = shownProvider
        if (deleteKeyBtn.text != CONFIRM_DELETE) {
            deleteKeyBtn.text = CONFIRM_DELETE
            keyLine.text = "删除后这一家就用不了；要恢复只能重新粘贴 Key。"
            main.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                if (deleteKeyBtn.text == CONFIRM_DELETE) {
                    deleteKeyBtn.text = "删除已保存"
                    refreshKeyLine(p)
                }
            }, 4_000L)
            return
        }
        deleteKeyBtn.text = "删除已保存"
        deleteKeyBtn.isEnabled = false
        if (VoiceSession.last.phase != VoiceSession.Phase.IDLE) {
            service?.let { runCatching { it.requestStop() } } ?: VoiceService.stop(this)
            stateLine.text = "已停止当前转写，接着删这一家的 Key。"
        }
        io.execute {
            runCatching { keys.clear(p) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // 与保存同一条规矩：回调属于发起时那一家，切走了就不许改当前页面。
                if (shownProvider != p) return@runOnUiThread
                shownHasKey = false
                keyLine.text = "已删除 ${VoiceProvider.label(p)} 的 Key。"
                renderStartStop()
            }
        }
    }

    /** 「这一家存没存 Key」的一句话说明（删除确认超时后回填用）。 */
    private fun refreshKeyLine(p: String) {
        keyLine.text =
            if (shownHasKey) "已保存 ${VoiceProvider.label(p)} 的 Key（加密存放，不回显）。"
            else "还没有保存 ${VoiceProvider.label(p)} 的 Key。"
    }

    // ---------------- 开始 / 停止 ----------------

    private fun onStartClicked() {
        val p = shownProvider
        val src = shownSource
        // 连点保护：已经开始到服务那边，就别再递第二条（服务侧也会拒绝，但界面不该先闪一下）。
        if (VoiceSession.last.phase != VoiceSession.Phase.IDLE) return
        if (!VoiceSource.available(src)) {
            stateLine.text = VoiceSource.unavailableReason(src) ?: "这一档声音来源不可用"
            return
        }
        if (!settings.consentGiven(p, src)) {
            stateLine.text = "先在「隐私与授权」里点同意：这一档的声音要上传到 ${VoiceProvider.label(p)} 才能转写。"
            return
        }
        if (!shownHasKey) {
            stateLine.text = "先保存 ${VoiceProvider.label(p)} 的 API Key。"
            return
        }
        // 记下这次点击的意图：后面每一步授权回调都按它复核，中途改设置就不再用它。
        pendingProvider = p
        pendingSource = src
        requestNextPermission(p, src)
    }

    /**
     * 权限 / 授权**一次只问一个**：同时 request 通知 + 录音、再叠一个系统共享框，回调顺序不固定，
     * 很容易出现「录音没同意但界面以为开始了」。顺序固定为
     * **通知 → 录音（仅用麦克风的档位）→ 系统声音授权**，每一步的回调里再问下一步。
     */
    private fun requestNextPermission(p: String, src: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotifyThisRun
        ) {
            askedNotifyThisRun = true
            // 通知被拒不影响转写（前台通知仍会挂），但用户就看不见「正在录音」那条提示、
            // 也点不到通知里的停止 —— 先问一次，无论结果都继续往下走。
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        // RECORD_AUDIO 三档都要：系统播放捕获同样是拿一个 AudioRecord 读，系统按 RECORD_AUDIO 判权。
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        requestProjectionIfNeeded(p, src)
    }

    /**
     * 需要系统播放声音的档位：先弹系统的共享 / 授权框（[onActivityResult] 拿 token）。
     *
     * 「用户拒绝」= 这一档不能开始 —— **不退回麦克风**，如实说清并把按钮留在「开始」。
     */
    private fun requestProjectionIfNeeded(p: String, src: String) {
        if (!VoiceSource.requiresProjection(src)) {
            beginSession(p, src, 0, null)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            stateLine.text = "这台设备没有系统声音授权服务，无法录系统声音（不会改录麦克风）。"
            renderStartStop()
            return
        }
        val intent = try {
            // Android 14 起固定「只共享整个屏幕」：不给用户一个「单个应用」的选项，
            // 因为播放捕获与勾选哪个应用无关，选错只会让人以为录不到。
            if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
        } catch (e: Exception) {
            stateLine.text = "打不开系统声音授权框：" + (e.message ?: e.javaClass.simpleName) + "（不会改录麦克风）"
            renderStartStop()
            return
        }
        // pendingProvider / pendingSource 已在 [onStartClicked] 记下；这里不再覆盖，
        // 免得「换来源后的旧回调」把意图改回一个已经不成立的组合。
        runCatching { startActivityForResult(intent, REQ_PROJECTION) }
            .onFailure {
                stateLine.text = "打不开系统声音授权框：" + (it.message ?: it.javaClass.simpleName)
                renderStartStop()
            }
    }

    /**
     * 真正开始。**先把界面切到 STARTING 再叫服务**：否则在服务回话之前这一小段，
     * 「开始」还亮着（能连点）、「停止」却是灰的（用户想撤销也点不动）。
     */
    private fun beginSession(p: String, src: String, resultCode: Int, data: Intent?) {
        // 新会话从空屏开始：上一次的句子与错误都不带过来（文字只在内存，本来也不该跨会话）。
        VoiceSession.window.clear()
        VoiceSession.last = VoiceSnapshot(
            VoiceSession.Phase.STARTING, "正在准备…", audio = VoiceAudioStatus(source = src),
        )
        renderTranscript()
        render(VoiceSession.last)
        // 服务连系统都没让起时 start 返回 false（原因已由它写进 [VoiceSession.last]）：
        // 这里必须立刻按真实状态重画，否则这一页会一直停在「正在准备」，按钮永远是灰的。
        if (!VoiceService.start(this, p, src, resultCode, data)) render(VoiceSession.last)
    }

    private fun onStopClicked() {
        if (VoiceSession.last.phase == VoiceSession.Phase.IDLE) return
        // 先落 STOPPING：服务侧收尾要等最后一句，这一两秒里按钮不能还是「停止」。
        VoiceSession.last = VoiceSession.last.copy(phase = VoiceSession.Phase.STOPPING, text = "正在收尾，等最后一句…")
        render(VoiceSession.last)
        service?.let { runCatching { it.requestStop() } } ?: VoiceService.stop(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFY -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    stateLine.text = "没给通知权限：转写照常，但通知栏看不到「正在录音」，也点不到那里的停止。"
                }
                // 通知这一步无论结果如何，都接着往下走（录音 → 系统声音授权）。
                requestNextPermission(pendingProvider, pendingSource)
            }
            REQ_AUDIO -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    stateLine.text = "没有麦克风权限，无法录麦克风。请在系统设置 → 应用 → 本应用 → 权限里允许录音。"
                    renderStartStop()
                    return
                }
                // 权限回调不是「用户点了开始」：这中间可能切了服务商 / 来源、撤了同意、或者干脆关了页面。
                // 所以按当前真实状态再复核一遍，不直接开跑。
                if (isFinishing || isDestroyed) return
                if (!stillWanted()) return
                requestProjectionIfNeeded(pendingProvider, pendingSource)
            }
        }
    }

    /**
     * 系统声音的授权结果。拒绝 = 这一档不能开始（**不退回麦克风**）：
     * 用户明确不同意录系统声音，界面就必须停在「未开始」，而不是偷偷换成录房间里的声音。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (isFinishing || isDestroyed) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            stateLine.text = "没有同意共享 / 录制，系统声音这一档没开始（不会改录麦克风）。"
            renderStartStop()
            return
        }
        if (!stillWanted()) return
        beginSession(pendingProvider, pendingSource, resultCode, data)
    }

    /**
     * 授权途中可能已经换了服务商 / 来源、撤了同意、删了 Key，或者已经有一条在跑。
     * 只要有一项对不上就放弃这次启动，并说清为什么 —— 不复用用户当时的那次点击。
     */
    private fun stillWanted(): Boolean {
        if (VoiceSession.last.phase != VoiceSession.Phase.IDLE) return false
        if (pendingProvider != shownProvider || pendingSource != shownSource) {
            stateLine.text = "设置已经改了，这次没有开始；请再点一次「开始转写」。"
            renderStartStop()
            return false
        }
        if (!settings.consentGiven(pendingProvider, pendingSource)) {
            stateLine.text = "还没同意这一档声音的隐私提示：点「我已了解，允许上传声音」后再开始。"
            renderStartStop()
            return false
        }
        if (!shownHasKey) {
            stateLine.text = "还没保存 ${VoiceProvider.label(pendingProvider)} 的 API Key。"
            renderStartStop()
            return false
        }
        return true
    }

    /** 这一次进页面是否已经问过通知权限（避免被拒不授权后又反复弹）。 */
    private var askedNotifyThisRun = false

    /** 这一次点击开始时要用的服务商 / 来源（权限与授权回调里按它复核）。 */
    private var pendingProvider = ""
    private var pendingSource = VoiceSource.MIC

    // ---------------- 渲染 ----------------

    private fun render(snap: VoiceSnapshot) {
        if (!::stateLine.isInitialized) return
        val noSource = VoiceSource.unavailableReason(shownSource)
        stateLine.text = when {
            snap.error && snap.phase == VoiceSession.Phase.IDLE -> "没能开始：" + snap.text
            // 设置里选中的这一档本机不支持：停在「未在转写」时也要说清原因，别让人以为只是没开始。
            snap.phase == VoiceSession.Phase.IDLE && noSource != null -> noSource
            else -> snap.text
        }
        stateLine.setTextColor(if (snap.error) DANGER_INK else Ui.TX1)
        renderRoutes(snap)
        renderStartStop()
        renderTranscript()
    }

    /**
     * 每一路的状态单独一行：混录时**麦克风有声音不会掩盖系统那一路全静音**。
     * 只画服务真正观察到的结果（[VoiceAudioStatus]），没有观察就什么都不说。
     */
    private fun renderRoutes(snap: VoiceSnapshot) {
        if (!::routeLine.isInitialized) return
        val lines = if (snap.phase == VoiceSession.Phase.IDLE && !snap.recording) {
            emptyList()
        } else {
            snap.audio.routeLines()
        }
        routeLine.text = lines.joinToString("\n")
        routeLine.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        routeLine.setTextColor(
            if (snap.audio.micSilenced == true || snap.audio.projectionRevoked) DANGER_INK else Ui.TX2
        )
    }

    private fun renderStartStop() {
        if (!::startBtn.isInitialized) return
        val phase = VoiceSession.last.phase
        val recording = VoiceSession.last.recording
        startBtn.isEnabled = phase == VoiceSession.Phase.IDLE && shownHasKey &&
            shownProvider.isNotBlank() && VoiceSource.available(shownSource)
        startBtn.text = when (phase) {
            VoiceSession.Phase.STARTING -> "正在准备…"
            VoiceSession.Phase.STOPPING -> "正在收尾…"
            else -> "开始转写"
        }
        Brut.button(startBtn, Brut.MINT)
        stopBtn.isEnabled = phase != VoiceSession.Phase.IDLE
        stopBtn.text = if (recording) "停止录音" else "停止"
        stopBtn.tag = if (recording) Brut.DANGER else null
        Brut.button(stopBtn, if (recording) Brut.ROSE else Brut.CARD)
    }

    /**
     * 逐句画：partial 次要色斜体、final 墨色。同一句是在**原位被替换**，不是越加越长
     * （替换与去重逻辑在 [TranscriptWindow]）。
     */
    private fun renderTranscript() {
        if (!::transcript.isInitialized) return
        val items = VoiceSession.window.snapshot()
        transcript.removeAllViews()
        emptyLine.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (t in items) {
            val row = Ui.text(this, t.text, 15f, if (t.isFinal) Ui.TX1 else Ui.TX2).apply {
                tag = Brut.KEEP
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(4f), 0, dp(4f))
                if (!t.isFinal) typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            }
            transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun hint(text: String, size: Float = 12.5f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.TX2)
        tag = Brut.KEEP
        setLineSpacing(0f, 1.25f)
    }

    companion object {
        private const val REQ_AUDIO = 0x4c70
        private const val REQ_NOTIFY = 0x4c71
        /** 系统声音授权（MediaProjection 共享 / 录制框）。 */
        private const val REQ_PROJECTION = 0x4c72

        /** 一行 API Key 的长度上限（远超任何一家的真实长度，纯粹挡住「把整段文件粘进来」） */
        private const val MAX_KEY_LEN = 512

        private const val CONFIRM_DELETE = "再点一次确认删除"

        /** 错误文字用的红：不随主题变淡，保证失败原因一眼能看见 */
        private val DANGER_INK: Int = 0xFFB3261E.toInt()

        /** 从设置页进入：只走应用内显式 Intent（manifest 里 exported=false）。 */
        fun intent(ctx: Context): Intent = Intent(ctx, VoiceActivity::class.java)
    }
}
