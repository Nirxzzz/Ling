package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 「手机常连（MCP）」页：配对、开关、解除、手机审计、可打开的 app 名单。
 *
 * 这一页和「局域网同步」（[LanSyncActivity]）是**两件事**，页面里也这么说清楚：
 *  - 局域网同步 = 你在前台点一次，交换一次快照，退出即断；
 *  - 手机常连（本页）= 记住这台电脑，**由你按下开关后**在后台长轮询，电脑能远程调手机上的工具。
 *
 * 两条硬规矩：
 *  - **配对 ≠ 开启**：扫完码只是把配对信息加密存起来，常连开关保持关；
 *  - 配对串 / 密钥只进加密存储，不进 Intent / Bundle / 日志；输入框始终遮蔽。
 *
 * 外观一律走 [Brut] / [Ui] / [Shape]：瑞士风格下就是「直角 + 细线 + 无影 + 黑白 + 一个信号红」，
 * 默认风格下仍是圆角卡片；本页不硬编码任何圆角、投影或颜色。动态加进来的行（审计、app 名单）
 * 也会逐个过一遍 [Brut.style]，否则后加的行会留着系统默认外观。
 */
class LinkSettingsActivity : Activity() {

    companion object {
        private const val REQ_CAMERA = 0x4c60
        private const val AUDIT_ROWS = 30

        /** 状态行的刷新间隔：够快看出「已连接 / 断线重试」，又不至于每秒刷一次 View。 */
        private const val STATUS_TICK_MS = 1000L

        fun intent(ctx: android.content.Context): Intent = Intent(ctx, LinkSettingsActivity::class.java)
    }

    private lateinit var root: FrameLayout
    private lateinit var pairLine: TextView
    private lateinit var statusLine: TextView

    /** 配对保存的结果（成功 / 失败原因）。不跟 `statusLine`（常连状态，会被 ticker 每秒刷新）。 */
    private lateinit var saveLine: TextView
    private lateinit var input: EditText
    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var unpairBtn: Button
    private lateinit var confirmRow: LinearLayout
    private lateinit var enableSwitch: Switch
    private lateinit var appsHost: LinearLayout
    private lateinit var auditHost: LinearLayout

    /** 程序化回填开关时置位：那不是用户操作，不能触发启动 / 停止常连。 */
    private var suppressToggle = false
    private var scanner: LanQrScanner? = null

    /** 读磁盘 / 解密配对是 IO：统一放这里，别在主线程上碰。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "link-settings-io").apply { isDaemon = true }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        title = Preset.appName + " · 手机常连"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        // 扫码是「同页覆盖层」：根布局固定，摄像头预览直接盖在页面上，不新开 Activity、
        // 也绝不把配对串放进 Intent / Bundle。
        root = FrameLayout(this)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        Brut.page(this, root)

        col.addView(TextView(this).apply {
            text = "手机常连"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(10f))
        })

        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        intro.addView(hint(
            "电脑上打开「手机常连」会显示一串 ling-link://… 的配对串（旁边有二维码）。扫一次即可记住这台电脑；" +
                "之后由你在下面打开开关，手机才在后台长轮询——电脑那头能调手机上的工具（记待办、查记忆、读屏、打开 app）。",
            13.5f,
        ))
        intro.addView(hint(
            "和「局域网同步」不一样：那是你在前台点一次、交换一次快照、退出即断；这里是持久配对 + 后台常连。两者互不影响。",
            13.5f,
        ).apply { setPadding(0, dp(8f), 0, 0) })
        col.addView(intro)
        Brut.card(intro)

        // ---------------- 当前配对 ----------------
        col.addView(Brut.heading(this, "当前配对"))
        val pairCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pairLine = TextView(this).apply { textSize = 14f; setLineSpacing(0f, 1.25f) }
        pairCard.addView(pairLine)
        col.addView(pairCard)
        Brut.card(pairCard)

        col.addView(Brut.heading(this, "配对串（视同密码）"))
        input = EditText(this).apply {
            hint = "ling-link://192.168.1.23:47322/…"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // 配对串里有链路密钥：不参与系统保存 / 自动填充，也不显示明文。
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        col.addView(input)

        scanBtn = Button(this).apply {
            text = "扫描电脑上的二维码"
            setOnClickListener { requestScan() }
        }
        col.addView(scanBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveBtn = Button(this).apply {
            text = "保存配对"
            tag = Brut.PRIMARY
            setOnClickListener { saveFromInput() }
        }
        unpairBtn = Button(this).apply {
            text = "解除配对"
            tag = Brut.DANGER
            setOnClickListener { askUnpair() }
        }
        actions.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8f) })
        actions.addView(unpairBtn, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(actions)

        // 解除配对是破坏性动作：确认条就画在页面里（不复用系统 AlertDialog —— 那套窗口不跟本 App 的
        // 瑞士 / 粗野皮肤走，会出现「黑白红页面里弹一个蓝色系统框」的割裂）。
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        col.addView(confirmRow)

        // 配对结果单独一行：常连状态行（statusLine）每秒都会被 ticker 刷成服务的真实状态，
        // 保存失败的原因写在那一行上会被立刻盖掉 —— 用户就会以为「保存成功但没配对」。
        saveLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6f), 0, dp(2f))
        }
        col.addView(saveLine)

        col.addView(hint("保存配对只会记住这台电脑，不会自动打开常连；配对串不写日志、不进系统备份。"))

        // ---------------- 常连开关 ----------------
        col.addView(Brut.heading(this, "后台常连"))
        val swCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        enableSwitch = Switch(this).apply {
            text = "手机常连（后台长轮询）"
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        swCard.addView(enableSwitch)
        statusLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6f), 0, 0)
        }
        swCard.addView(statusLine)
        col.addView(swCard)
        Brut.card(swCard)

        // ---------------- app 名单 ----------------
        col.addView(Brut.heading(this, "允许电脑打开的 app"))
        col.addView(hint(
            "只列本机已安装、且能直接打开的 app。默认只开「复习 / 单词 / 日历」这三个；其余默认关，你要用哪个再打开。" +
                "电脑只能按这里的别名打开，没法指定任意包名。",
        ))
        appsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(appsHost)

        // ---------------- 手机审计 ----------------
        col.addView(Brut.heading(this, "手机审计（最近）"))
        auditHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(auditHost)

        col.addView(hint(
            "安全说明：配对信息（含密钥）用 Android Keystore 的 AES-256-GCM 整条加密后存在应用私有目录，不进系统备份；" +
                "传输是 AES-256-GCM 密文走局域网 HTTP，认证失败直接报错、不降级；只接受私网 IPv4 / 回环地址。" +
                "关掉开关会立刻断开连接、取消还没确认的弹窗。",
        ).apply { setPadding(0, dp(14f), 0, 0) })

        Brut.style(col)
    }

    /**
     * 说明文字：字色用主题的次要色，并 `KEEP` 掉 [Brut.style] 的一刀切（否则全页文字同一档墨色，
     * 瑞士风格的层次就没了）。主题在 [onCreate] 已装好，页面重建时会重新取。
     */
    private fun hint(text: String, size: Float = 12.5f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.TX2)
        tag = Brut.KEEP
        setLineSpacing(0f, 1.25f)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        scanner?.stop()
        scanner = null
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)   // ticker 持有 Activity：不摘掉就是泄漏
        scanner?.stop()
        scanner = null
        io.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (scanner != null) {
            scanner?.stop()
            scanner = null
            saveLine.text = "已取消扫描，仍可粘贴配对串。"
            return
        }
        if (confirmRow.visibility == View.VISIBLE) {
            confirmRow.visibility = View.GONE
            return
        }
        super.onBackPressed()
    }

    // ---------------- 刷新 ----------------

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 连接状态是活的（长轮询里随时会变），页面停在这里时要自己跟上 ——
     * 只读 [LinkService] 的静态状态，不碰磁盘、不发请求，所以可以按秒刷新。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (::statusLine.isInitialized) {
                statusLine.text = LinkService.statusText()
                // 服务自己停了（401 / 用户从通知里停掉）时，开关也要跟着弹回关 —— 不留假开启。
                if (!LinkService.running && enableSwitch.isChecked && !LinkPairStore(applicationContext).enabled) {
                    setSwitchSilently(false)
                }
            }
            handler.postDelayed(this, STATUS_TICK_MS)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** 配对信息是磁盘 + 解密，放 IO 线程；界面只拿结果（不含密钥）。 */
    private fun refreshAll() {
        io.execute {
            val pair = runCatching { LinkPairStore(applicationContext).load() }.getOrNull()
            val enabled = runCatching { LinkPairStore(applicationContext).enabled }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val has = pair != null
                pairLine.text = if (pair == null) {
                    "还没有配对。扫描电脑上的二维码，或粘贴配对串后点「保存配对」。"
                } else {
                    "已配对：${pair.name}\n${pair.host}:${pair.port}"
                }
                saveBtn.isEnabled = true
                unpairBtn.isEnabled = has
                enableSwitch.isEnabled = has
                // 回填状态不能触发「用户刚打开开关」那条路，否则每次进页面都会自动拉起常连。
                setSwitchSilently(has && enabled)
                statusLine.text = LinkService.statusText()
            }
            val apps = runCatching { LinkDispatcher(this).apps() }.getOrNull()
            val audit = runCatching { LinkAudit(this).recent(AUDIT_ROWS) }.getOrNull() ?: emptyList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderApps(apps)
                renderAudit(audit)
            }
        }
    }

    private fun renderApps(apps: JSONArray?) {
        appsHost.removeAllViews()
        if (apps == null || apps.length() == 0) {
            appsHost.addView(hint("没有可打开的 app 别名（本机没装复习 / 单词 / 日历，或应用信息读不到）。", 13f))
            return
        }
        for (i in 0 until apps.length()) {
            val o = apps.optJSONObject(i) ?: continue
            val alias = o.optString("alias", "")
            if (alias.isBlank()) continue
            val label = o.optString("name", alias).ifBlank { alias }
            val row = Switch(this).apply {
                text = "$label（$alias）"
                isChecked = o.optBoolean("on", false)
                setOnCheckedChangeListener { _, checked ->
                    // 开关就是用户意图本身，就地落盘，不需要再点保存。
                    val store = LinkPairStore(applicationContext)
                    val next = store.allowedApps().toMutableSet()
                    if (checked) next.add(alias) else next.remove(alias)
                    store.setAllowedApps(next)
                    saveLine.text = "已更新 app 名单；下一轮报到时电脑会看到新的名单。"
                }
            }
            appsHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
            Brut.style(row)
        }
    }

    private fun renderAudit(rows: List<JSONObject>) {
        auditHost.removeAllViews()
        if (rows.isEmpty()) {
            auditHost.addView(hint(
                "还没有记录。电脑每调一次手机上的工具，这里会留一行（只有工具名 / 客户端 / 结果，不含内容）。",
                13f,
            ))
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        rows.forEach { o ->
            val at = o.optLong("at", 0L)
            val tool = o.optString("tool", "?")
            val client = o.optString("client", "")
            val outcome = o.optString("outcome", "")
            val confirm = o.optString("confirm", "n/a")
            val detail = o.optString("detail", "")
            val row = TextView(this).apply {
                text = buildString {
                    append(if (at > 0) fmt.format(Date(at)) else "--")
                    append("  ").append(tool)
                    if (client.isNotBlank()) append("  · ").append(client)
                    append("  · ").append(outcome)
                    if (confirm != "n/a" && confirm.isNotBlank()) append(" / ").append(confirm)
                    if (detail.isNotBlank()) append("\n").append(detail)
                }
                textSize = 12.5f
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(3f), 0, dp(3f))
            }
            auditHost.addView(row)
            Brut.style(row)
        }
    }

    // ---------------- 配对 ----------------

    private fun requestScan() {
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
            saveLine.text = "未授予相机权限，仍可粘贴配对串。"
        }
    }

    private fun startScanner() {
        if (scanner != null) return
        saveLine.text = "把电脑上「手机常连」的二维码放进框内。"
        scanner = LanQrScanner(
            activity = this,
            host = root,
            onResult = { text ->
                scanner = null
                onScanned(text)
            },
            onError = { message ->
                scanner = null
                saveLine.text = message
            },
            onCancel = {
                // 覆盖层已由 LanQrScanner 自己关掉；这里只清引用并保留粘贴方式。
                scanner = null
                saveLine.text = "已取消扫描，仍可粘贴配对串。"
            },
        ).also { it.start() }
    }

    /** 扫码结果先严格校验，通过了才写进遮蔽输入框（不落盘、不进 Bundle）。 */
    private fun onScanned(raw: String) {
        val text = raw.trim()
        try {
            val p = LinkPairStore.parse(text)
            java.util.Arrays.fill(p.key, 0)   // 只是预校验，真正的密钥在点保存时再解析一次
            input.setText(text)
            saveLine.text = "已扫描到配对串（输入框仍遮蔽显示），点「保存配对」。"
        } catch (e: IllegalArgumentException) {
            saveLine.text = "二维码不是有效的配对串：${e.message}"
        }
    }

    private fun saveFromInput() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            saveLine.text = "先粘贴电脑上显示的配对串，或扫码。"
            return
        }
        val pair = try {
            LinkPairStore.parse(text)
        } catch (e: IllegalArgumentException) {
            saveLine.text = "配对串无效：${e.message}"
            return
        }
        saveBtn.isEnabled = false
        saveLine.text = "正在保存…"
        io.execute {
            val store = LinkPairStore(applicationContext)
            val err = try {
                // 换电脑 = 换配对：先把常连停掉，避免继续拿旧密钥连旧地址。
                val old = store.load()
                if (old != null && (old.id != pair.id || old.host != pair.host || old.port != pair.port)) {
                    runCatching { LinkService.stop(applicationContext) }
                }
                store.save(pair)
                null
            } catch (e: Exception) {
                // 连异常类型一起留下：Keystore / 文件系统的问题不是「配对串写错了」，
                // 只回一句「保存失败」等于让用户去猜（实测踩过：IV 被 Keystore 拒绝，界面只显示没配对）。
                "${e.javaClass.simpleName}：${e.message?.takeIf { it.isNotBlank() } ?: "没有更多信息"}"
            } finally {
                java.util.Arrays.fill(pair.key, 0)
            }
            // 写盘成功 ≠ 下次能解开：Keystore 密钥被重置、文件被改坏都要在保存这一步就暴露。
            val verify = if (err == null) {
                val back = runCatching { store.load() }.getOrNull()
                when {
                    back == null -> "写进去了但读不出来（本机 Keystore 密钥可能已变），请重新配对"
                    back.id != pair.id || back.host != pair.host || back.port != pair.port ->
                        "读回的配对与刚保存的不一致，请重新配对"
                    else -> null
                }
            } else null
            val finalErr = err ?: verify
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saveBtn.isEnabled = true
                // 失败时**不**清输入框：用户要能直接再点一次「保存配对」，而不是回电脑重抄一遍。
                if (finalErr == null) {
                    input.setText("")
                    saveLine.text = "配对已保存，并已读回验证（加密存储）。常连开关仍是关的——要后台常连请在上面打开。"
                } else {
                    saveLine.text = "配对没保存成功：$finalErr"
                }
                refreshAll()
            }
        }
    }

    /**
     * 解除配对的确认条：画在页面里、跟当前皮肤走，不弹系统对话框。
     * 一条不可撤销的提示 + 「确认解除 / 取消」两个按钮。
     */
    private fun askUnpair() {
        confirmRow.removeAllViews()
        confirmRow.addView(hint(
            "解除后会先通知电脑「已解除」，然后删掉本机的加密配对信息并停止常连。" +
                "电脑若没收到通知，它下次连过来会被拒绝。再连需要重新扫码。",
            13f,
        ).apply { setPadding(0, dp(4f), 0, dp(6f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val yes = Button(this).apply {
            text = "确认解除"
            tag = Brut.DANGER
            setOnClickListener {
                confirmRow.visibility = View.GONE
                doUnpair()
            }
        }
        val no = Button(this).apply {
            text = "取消"
            setOnClickListener { confirmRow.visibility = View.GONE }
        }
        row.addView(yes, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8f) })
        row.addView(no, LinearLayout.LayoutParams(0, -2, 1f))
        confirmRow.addView(row)
        confirmRow.visibility = View.VISIBLE
        Brut.style(confirmRow)
    }

    private fun doUnpair() {
        unpairBtn.isEnabled = false
        io.execute {
            // 先停常连（连接、前台通知、待确认弹窗一起收掉），再尽力通知电脑，最后一定清本机。
            runCatching { LinkService.stop(applicationContext) }
            val pair = runCatching { LinkPairStore(applicationContext).load() }.getOrNull()
            if (pair != null) {
                runCatching {
                    val dispatcher = LinkDispatcher(applicationContext)
                    val one = LingLink(applicationContext, pair, dispatcher) { }
                    try {
                        one.unpairBlocking()
                    } finally {
                        // 这条一次性连接也有自己的执行线程：通知完就收掉，别在解除配对后留一个空转的池。
                        one.stop()
                    }
                }
                java.util.Arrays.fill(pair.key, 0)
            }
            runCatching { LinkPairStore(applicationContext).clear() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saveLine.text = "已解除配对。再连需要重新扫码。"
                setSwitchSilently(false)
                refreshAll()
            }
        }
    }

    // ---------------- 常连开关 ----------------

    /**
     * 打开 = 先落盘 enabled 再拉起服务；关掉 = 先停服务（服务自己也会把 enabled 清掉）。
     * 读配对是 IO，所以「能不能开」也在 IO 线程里判断；不成立就把开关弹回关，
     * 绝不留下一个「显示开、其实没跑」的开关。
     */
    private fun onToggle(on: Boolean) {
        if (suppressToggle) return
        if (on) {
            enableSwitch.isEnabled = false
            io.execute {
                val store = LinkPairStore(applicationContext)
                val has = runCatching { store.load() }.getOrNull() != null
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    enableSwitch.isEnabled = has
                    if (!has) {
                        setSwitchSilently(false)
                        // 「为什么开不起来」必须留在能看见的地方：statusLine 一秒后就会被 ticker 刷成服务状态。
                        saveLine.text = "还没有配对，先扫码或粘贴配对串。"
                        return@runOnUiThread
                    }
                    store.enabled = true
                    LinkService.start(this)
                    statusLine.text = "正在连接电脑…"
                }
            }
        } else {
            LinkService.stop(this)
            statusLine.text = "已关闭常连（前台通知已撤掉，连接已断开）。"
        }
    }

    /** 程序化改开关时不能再触发一遍 [onToggle]（那是用户在操作，这是界面在回填状态）。 */
    private fun setSwitchSilently(on: Boolean) {
        suppressToggle = true
        enableSwitch.isChecked = on
        suppressToggle = false
    }
}
