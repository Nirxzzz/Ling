package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 设置首页顶部的搜索栏：**一行**「输入框 + 搜索 + 清空」。
 *
 * 分工：检索与协议全在 [SettingsSearch]（纯 Kotlin、不联网）；这里只管界面和请求的时机与取消。
 *
 *  - **输入先出本地结果**：[SettingsSearch.local] 同步算完立刻上屏，不联网、不碰密钥、不改任何设置值；
 *  - **停手片刻后默认按意思匹配一次**：输入安静一小会儿就用 [Prefs.api] 的 [ApiConfig]
 *    + [SettingsSearch.prompt] + `Bodies.text(..., stream = true)` 配 [ApiClient.stream] 发一次，
 *    后台线程发、主线程只收结果；点「搜索」/ 键盘搜索键是立刻再发一次（同样可取消）；
 *  - **没配接口 / 地址或模型不全就静默只留本地结果**：补齐接口不是搜索的前置条件，不弹「去配置」，
 *    也不假装 AI 回来了东西 —— 真实网络失败照旧在状态行写明原因；
 *  - 结果最多 6 条，点一条 → [onOpen] 跳到它所属的**分类页 › 分组卡片**（高级项由 Main 那边顺手展开）。
 *    界面写明「跳到分组」，不谎称精确到单个开关；
 *  - 异步纪律：每次发送带一个代际号，输入变化 / 离开分类首页 / 页面重建 / onDestroy 都 [Cancel.abort] 并 +1，
 *    旧代际的结果回来一律丢弃 —— 不许覆盖新输入，也不许往已经拆掉的视图上写。
 */
class SettingsSearchView(
    private val act: Activity,
    private val prefs: Prefs,
    /** 点结果：跳到这个分组（Main 的 jumpTo），并区分是不是高级项 */
    private val onOpen: (SettingsSearch.Hit) -> Unit,
    /** 「去接口」：跳「AI 接口与翻译 › 接口与模型」 */
    private val onApi: () -> Unit,
) : LinearLayout(act) {

    /** 一个结果：现在显示的那几条 + 它们从哪来 */
    private data class Shown(val hits: List<SettingsSearch.Hit>, val ai: Boolean)

    private val ui = Handler(Looper.getMainLooper())
    private var sent = 0                 // 代际号：只有等于当前值的回调才许上屏
    private var busy = false
    private var cancel: Cancel? = null
    private var lastRaw = ""
    private var destroyed = false
    /** 输入停手后自动问一次 AI；输入变化 / 离开 / 拆卸都会撤掉它（声明在 [destroyed] 之后，读到的是最新值） */
    private val autoAi = Runnable { if (!destroyed) startAi(explicit = false) }
    /** 本地结果有没有被 AI 结果顶掉（点清空 / 改输入后要重算，不能空着） */
    private var cur = Shown(emptyList(), false)

    /**
     * 包住整个搜索区的容器：横向拖拽标签挂在这里，而不是挂在输入框上 ——
     * 输入框自己 tag = [Brut.KEEP]（不让皮肤把字色刷丢），两个 tag 只能留一个。
     */
    val swipeGuard: View get() = this

    private fun dp(v: Float): Int = Ui.dp(act, v)
    private fun ink() = Brut.INK

    private val field = EditText(act).apply {
        hint = HINT
        textSize = 15f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        tag = Brut.KEEP
        background = WxSkin.rounded(WxSkin.FIELD, act, 10f)
        setTextColor(Brut.INK); setHintTextColor(Brut.HINT)
        setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
        minHeight = dp(46f)
        // 不自动拉键盘：只在真的点到这一栏时才弹（不写 requestFocus）
    }
    private val aiBtn = Button(act).apply {
        text = "搜索"; isAllCaps = false; tag = Brut.PRIMARY
        minWidth = dp(64f); setPadding(dp(12f), 0, dp(12f), 0)
    }
    private val clearBtn = Button(act).apply {
        text = "✕"; isAllCaps = false; contentDescription = "清空搜索"
        minWidth = dp(48f); minimumWidth = dp(48f); setPadding(0, 0, 0, 0)
    }
    /** 状态容器，容纳匹配来源、错误信息和接口入口。 */
    private val status = LinearLayout(act).apply {
        orientation = VERTICAL
        tag = Brut.KEEP
        setPadding(dp(4f), dp(6f), dp(4f), dp(2f))
        visibility = View.GONE
    }
    private val list = LinearLayout(act).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(2f))
        // 一行放齐：输入 + 「搜索」+ 清空。输入本身已经自动匹配，搜索键只是显式重发一次
        addView(LinearLayout(act).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(field, LayoutParams(0, -2, 1f))
            addView(aiBtn, LayoutParams(-2, -2).apply { marginStart = dp(6f) })
            addView(clearBtn, LayoutParams(dp(48f), -2).apply { marginStart = dp(4f) })
        })
        addView(status, LayoutParams(-1, -2))
        addView(list, LayoutParams(-1, -2))

        WxSkin.style(this)     // 输入框 / 按钮 / 字色都跟着主题与形状走；tag = KEEP 的子树不动

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 输入一变就作废飞着的请求，并按新词立刻算本地结果
                abortRun("")
                val q = s?.toString().orEmpty()
                localOnly(q)
                // 本地结果先留在屏幕上；停手后再按意思匹配一次（没配接口时这一步自己安静退出）
                if (q.trim().length >= AUTO_MIN_CHARS) ui.postDelayed(autoAi, AUTO_DELAY_MS)
            }
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                ui.removeCallbacks(autoAi)      // 键盘搜索键 = 立刻发，不等去抖
                startAi(explicit = true)
                true
            } else false
        }
        aiBtn.setOnClickListener {
            ui.removeCallbacks(autoAi)
            startAi(explicit = true)
        }
        clearBtn.setOnClickListener {
            field.setText("")
            field.clearFocus()
            ime(false)          // 清空就收键盘（leave 里也会收）
            abortRun("")
        }
    }

    // ---------------- 对外 ----------------

    /** 当前输入（Main 用来在切页回来时把搜索清干净；不动输入则不打扰） */
    fun queryText(): String = field.text?.toString().orEmpty()

    /** 页面重建 / onDestroy：掐断在飞的请求，之后到的结果一律丢 */
    fun destroy() {
        destroyed = true
        abortRun("")
    }

    /** 首页按返回：只清掉查询 + 收键盘（有查询时返回键先做这件事，不直接退出设置） */
    fun clearQuery() {
        field.setText("")
        field.clearFocus()
        ime(false)
        abortRun("")
    }

    /** 离开分类首页（进子页 / 退出设置 / 退到后台）：请求作废、收键盘；输入框仍是原输入，回来还在 */
    fun leave() {
        abortRun("")
        field.clearFocus()
        ime(false)
    }

    // ---------------- 本地 ----------------

    private fun localOnly(q: String) {
        val hits = try { SettingsSearch.local(q.trim(), MAX) } catch (_: Exception) { emptyList() }
        cur = Shown(hits, false)
        if (q.isBlank()) { list.removeAllViews(); status.visibility = View.GONE; return }
        render()
        if (hits.isEmpty()) say("本机没有直接匹配，换个说法试试。", null)
        else say("本地匹配 · 点结果进入对应设置分组", null)
    }

    // ---------------- AI（输入停手后默认、点「搜索」立刻） ----------------

    /**
     * [explicit] = 用户点了「搜索」/ 键盘搜索键：立刻发，空输入与忙碌时给一句说明；
     * 自动那次静默 —— 没接口、配置不全、描述过长都只留本地结果，不弹配置入口。
     */
    private fun startAi(explicit: Boolean) {
        if (busy) {
            // 自动那次撞上在飞请求：让它跑完，不重复发；忙碌时点按钮是「取消」（见下面换的监听），
            // 走这里的 explicit 只有键盘搜索键再按一次 —— 那就掐掉旧的、按同一句重发
            if (!explicit) return
            abortRun("")
        }
        val q = queryText().trim()
        if (q.isEmpty()) {
            if (explicit) say("先描述一下想找的设置，比如「晚上别弹单词」。", null)
            return
        }
        // 没配接口 / 配置读不全：静默降级到本地结果 —— 补齐接口不是搜索的前置条件，
        // 也不能在该情况下假装「AI 匹配」返回了东西
        if (!prefs.hasApi()) return
        val cfg = try { prefs.api() } catch (_: Exception) { null }
        if (cfg == null || cfg.baseUrl.isBlank() || cfg.model.isBlank()) return
        val prompt = try { SettingsSearch.prompt(q) } catch (_: Exception) {
            if (explicit) say("描述太长或读不出候选，换个短一点的试试。", null)
            return
        }
        val c = Cancel()
        val my = synchronized(this) { cancel?.abort(); cancel = c; ++sent }
        lastRaw = q
        localOnly(q)            // 开始前先把本地结果留在下面，取消 / 失败都还有东西看
        busy = true
        // 按钮保持可点：忙碌时它就是「取消」，点了只掐这次请求、还原按钮，不重复发
        aiBtn.text = "取消"; aiBtn.contentDescription = "取消这次按意思匹配"
        aiBtn.setOnClickListener { abortRun("已取消匹配，下面是本地结果。") }
        say("AI 正在按意思匹配，只发送搜索文字和设置目录；可随时取消。", null)

        thread {
            var text = ""
            var err = ""
            try {
                val client = ApiClient(cfg)
                // 复用现有 SSE 通道：Bodies.text 的 stream 与 ApiClient.stream 必须一致，否则 JSON 当 SSE 解析不出正文
                val body = Bodies.text(cfg, prompt, AI_MAX_TOKENS, thinkingOff = true, stream = true, withTools = false)
                text = client.stream(body, object : ApiClient.Listener {
                    override fun onText(t: String) {}
                }, c, READ_TIMEOUT_MS).first
            } catch (e: Throwable) {
                err = if (c.get()) "" else NetErrors.friendly(e, cfg.baseUrl)
            }
            ui.post { done(my, c, text, err) }
        }
    }

    private fun done(my: Int, c: Cancel, raw: String, err: String) {
        if (destroyed || my != sent) return          // 旧一代：输入变了 / 离开首页 / 页面重建了 —— 不碰界面
        busy = false
        idleBtn()
        val q = queryText().trim()
        if (c.get()) return                         // 用户自己取消的：状态行已经写过
        if (err.isNotEmpty()) {
            // 真实网络 / 鉴权失败：本地结果原样留着，把原因写清楚 —— 不装成「AI 匹配成功」；
            // 这类失败多半是 Key 或地址的问题，所以仍给「去填接口」，与「没配接口」的静默降级无关
            keepLocalOrRecompute(q)
            val more = if (cur.hits.isEmpty()) "" else "\n下面是本机结果，输入不变。"
            say("按意思匹配失败：$err$more", onApi)
            return
        }
        val hits = SettingsSearch.fromAi(raw, MAX)
        if (hits.isEmpty()) {
            keepLocalOrRecompute(q)
            if (cur.hits.isEmpty()) say("没找到匹配的设置，换个说法试试。", null)
            else say("AI 没有给出可用的结果（或格式不合），下面是本机结果，可以换个说法再试。", null)
            return
        }
        if (queryText().trim() != lastRaw) return   // 输入已经换了：这一份结果对不上，丢掉
        cur = Shown(hits, true)
        render()
        say("AI 按意思挑出 ${hits.size} 个；点一条会跳到它所在的分组卡片（不定位到单个开关）。", null)
    }

    private fun keepLocalOrRecompute(q: String) {
        if (cur.hits.isNotEmpty() && !cur.ai) return
        cur = Shown(try { SettingsSearch.local(q, MAX) } catch (_: Exception) { emptyList() }, false)
        render()
    }

    /** 按钮回到「搜索」的常态 */
    private fun idleBtn() {
        if (destroyed) return
        aiBtn.text = "搜索"
        aiBtn.contentDescription = null
        aiBtn.setOnClickListener {
            ui.removeCallbacks(autoAi)
            startAi(explicit = true)
        }
    }

    /** 掐断在飞的请求 + 作废代际；[msg] 非空时写一行说明（取消 / 清空用） */
    private fun abortRun(msg: String) {
        ui.removeCallbacks(autoAi)       // 去抖还没发的那次也一起作废
        val wasBusy: Boolean
        synchronized(this) {
            sent++
            val c = cancel
            cancel = null
            wasBusy = busy
            busy = false
            c?.abort()
        }
        if (destroyed) return
        idleBtn()
        if (msg.isNotEmpty()) say(msg, null) else if (wasBusy) status.visibility = View.GONE
    }

    // ---------------- 结果 ----------------

    private fun render() {
        list.removeAllViews()
        val hits = cur.hits
        if (hits.isNotEmpty()) list.addView(TextView(act).apply {
            text = if (cur.ai) "AI 按意思找的" else "本地即时匹配的"
            textSize = 12f; setTextColor(Brut.HINT); tag = Brut.KEEP
            setPadding(dp(6f), dp(2f), 0, dp(2f))
        })
        for ((i, hit) in hits.withIndex()) {
            if (i > 0) list.addView(WxSkin.divider(act, 8f))
            list.addView(row(hit), LayoutParams(-1, -2))
        }
    }

    private fun row(hit: SettingsSearch.Hit): View {
        val r = LinearLayout(act).apply {
            orientation = VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(6f), dp(8f), dp(6f), dp(8f))
            isClickable = true; isFocusable = true
            background = WxSkin.ripple(act, null, 8f)
            tag = Brut.KEEP
            setOnClickListener { open(hit) }
        }
        r.addView(TextView(act).apply {
            text = hit.label; textSize = 15f; setTextColor(ink()); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            tag = Brut.KEEP
        })
        r.addView(TextView(act).apply {
            text = pathOf(hit) + if (hit.advanced) "  ·  高级" else ""
            textSize = 12f; setTextColor(Brut.HINT); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
            tag = Brut.KEEP
        })
        r.contentDescription = hit.label + "，" + pathOf(hit) + if (hit.advanced) "，在高级设置里" else ""
        return r
    }

    /** 「分类 › 分组卡片」—— 结果跳到的是分组，不是一个开关，所以文案就这么写 */
    private fun pathOf(h: SettingsSearch.Hit): String {
        val title = SettingsRegistry.section(h.sectionId)?.title ?: h.sectionId
        return (if (h.cat.isBlank()) title else h.cat + " › " + title)
    }

    private fun open(hit: SettingsSearch.Hit) {
        // 点了结果：先收键盘 / 清焦点，再交给 Main 跳（返回时输入框里还是这次查询，方便接着找）
        field.clearFocus()
        ime(false)
        status.visibility = View.GONE
        onOpen(hit)
    }

    // ---------------- 杂项 ----------------

    private fun say(msg: String, apiLink: (() -> Unit)?) {
        status.removeAllViews()
        status.visibility = if (msg.isBlank() && apiLink == null) View.GONE else View.VISIBLE
        if (msg.isNotBlank()) status.addView(TextView(act).apply {
            text = msg; textSize = 12.5f; setTextColor(Brut.HINT); setLineSpacing(0f, 1.2f); tag = Brut.KEEP
        }, LayoutParams(-1, -2))
        if (apiLink != null) status.addView(TextView(act).apply {
            text = "去填接口 ›"; textSize = 13f; setTextColor(ink()); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, 0)
            minimumHeight = dp(40f); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            contentDescription = "去 AI 接口与翻译 › 接口与模型"
            setOnClickListener { apiLink() }
        }, LayoutParams(-1, -2))
    }

    private fun ime(show: Boolean) {
        try {
            val m = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
            if (show) m.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            else m.hideSoftInputFromWindow(field.windowToken, 0)
        } catch (_: Exception) {}
    }

    companion object {
        /** 结果最多几条（本地与 AI 同一口径） */
        private const val MAX = 6
        private const val HINT = "描述想找的设置"
        /** 自动按意思匹配的口径：停手多久、最少几个字 */
        private const val AUTO_DELAY_MS = 500L
        private const val AUTO_MIN_CHARS = 2
        /** 只回一个 ID 数组，预算给够一点就够 */
        private const val AI_MAX_TOKENS = 200
        /** 等模型回一个短数组：比识屏短，30 秒够了；卡住按超时报错 */
        private const val READ_TIMEOUT_MS = 30_000
    }
}
