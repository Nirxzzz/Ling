package com.chun.lingzhu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 选中文字 → 系统文字菜单「翻译」（ACTION_PROCESS_TEXT）→ 浮出一张卡片流式出译文。
 * v1.61：引擎按 Translate.plan 依次兜底（v2.6.3 起自动档 = DeepL → 百度 → AI）。
 * EXTRA_CLIPBOARD：翻译「刚复制的文字」—— 等这张卡片窗口拿到焦点再读剪贴板（Android 10 的限制）。
 * 「重翻」（跳过缓存真发请求、覆盖掉旧结果）与「复制」：常驻工具条上只留 →语言 / AI 解析 / ⋯ / ✕，
 * 这两项收进 ⋯ 菜单（见 [showMoreMenu]），仍是同一批本地回调。
 *
 * v2.9（本轮用户要求，三条）：
 *  1) **标题只写「翻译」**：Ling 名 / 引擎 / AI 解析 / 缓存这些标记不再堆进标题，正常加载时连小字都没有；
 *     只有这一轮真的降级过 / 有引擎失败（[Translate.reason] 记的原因）才在下面补一行小字。
 *  2) **整段 AI 解析改为用户主动**：机器翻译完成后不再自动发第二次请求；顶部多一个「AI 解析」按钮，
 *     点了才整段分析，进行中有明确状态、可取消（按钮变「取消解析」）。
 *     解析正文**不改机器译文**：译文留在 [out] 里，解析单独显示在它下面；复制仍是那份译文。
 *     显式选 AI 当翻译引擎、以及机器翻译都失败后的 AI 兜底**照旧是真翻译**（[Translate.plan] 不动），
 *     只是 AI 直出的也变成纯译文（[TranslationWord.plainPrompt]），不再夹带词性 / 义项 / 例句。
 *     缓存换成新命名空间 `translate2`（[trKey]）：旧版本存下的「机器译文 + AI 解析」富结果不会被默认命中
 *     —— 否则用户默认又看到一段旧的 AI 解析。解析结果另存一个命名空间（[analysisKey]），只有点按钮才会读。
 *  3) **点词**（[WordTap] / [TranslationWord]，对齐电脑版 `translate.html:71-96` + `main.js:3038-3096`）：
 *     译文里每个英文词都可以点，点了在同一张卡里换到词条页（译文暂时收起，返回就回来），
 *     真发一次非流式查词请求，给译义 / 解析 / 搭配用法 / 双语例句；加载如实、失败可重试、换词不串结果。
 *
 * 卡片是 Activity（`TranslateCard` 主题），不新增悬浮窗；打开期间由 [OrbService.suppressForForeground]
 * 把服务那层浮层（答案面板 / 菜单 / 各种卡）park 掉，球本身不动，退出时 [OrbService.restoreAfterForeground] 恢复。
 */
class TranslateActivity : Activity() {
    companion object {
        const val EXTRA_CLIPBOARD = "fromClipboard"
        fun clipboardIntent(ctx: android.content.Context) = Intent(ctx, TranslateActivity::class.java)
            .putExtra(EXTRA_CLIPBOARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        /** 翻译缓存命名空间（v2.9）：与旧版 `translate` 分开，旧的富结果（含整段 AI 解析）不会再被默认命中 */
        private const val TR_NS = "translate2"
        /** 整段 AI 解析的缓存命名空间：只有用户点「AI 解析」才会读 */
        private const val AN_NS = "analysis"
        // ⋯ 菜单项 id（重翻 / 复制）
        private const val MENU_REDO = 1
        private const val MENU_COPY = 2
    }

    private var waitClip = false
    private lateinit var srcView: TextView
    private var current = AtomicBoolean(false)   // 当前这一轮的取消开关；换语言重翻 / 关卡片时置位
    private lateinit var prefs: Prefs
    private lateinit var out: TextView
    private lateinit var langBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var badge: TextView
    private val cache by lazy { Cache.translateStore(this) }
    private lateinit var moreBtn: TextView
    private lateinit var analyzeBtn: TextView
    private lateinit var scroll: MaxHeightScrollView
    private lateinit var cardPane: LinearLayout
    private lateinit var cardWord: TextView
    private lateinit var cardBody: TextView
    private lateinit var cardRetry: TextView
    private lateinit var analysisView: TextView

    /** 当前配色是不是液态玻璃材质（v2.7.3）：只决定「谁来画底」，翻译 / 取词行为都不变 */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 屏幕上那份译文（渲染后的纯文本）：取词语境、词 span 的 offset 都以它为准 */
    private var displayText = ""
    /** 原文那两行的显示文本（单行化后的 [src]）：在原文里点词时用它的 offset */
    private var srcDisplay = ""
    private val raw = StringBuilder()
    /** 译文还在流式吐字：「AI 解析」先不露（解析的参考译文必须是完整的那份） */
    @Volatile private var busy = false
    private var src = ""

    // ---- 整段 AI 解析（用户点按钮才跑） ----
    private var analysis: Cancel? = null
    private var analysisBody = ""

    // ---- 点词 ----
    private val wordLatest = TranslationWord.Latest()
    private var wordCall: TranslationWord.Call? = null
    private var wordHit: TranslationWord.Hit? = null
    private var wordContext = ""
    private var cardVisible = false
    private var savedScrollY = 0

    /**
     * 标题只有「翻译」两个字（v2.9）：以前这里堆着「Ling 内测 · DeepL · 缓存 · AI 解析」这类
     * 装饰性标签，用户点名要去掉。来源引擎 / 缓存年龄不再写在界面上 —— ⋯ 菜单里的「重翻」本身就在
     * 提示这轮命中了缓存；只有**这一轮真的降级过 / 有引擎失败**时才在下面补一行小字。
     */
    private fun showTitle(fails: List<String> = emptyList()) {
        titleView.text = "翻译"
        badge.text = fails.joinToString("；")
        badge.visibility = if (badge.text.isEmpty()) View.GONE else View.VISIBLE
        analyzeBtn.visibility = if (!busy && raw.isNotEmpty() && prefs.hasApi()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.applyTheme(prefs)
        // 卡片在的时候，服务那层浮层让位（球不动、在途识别不打断）；成对调用见 onDestroy
        OrbService.suppressForForeground(this)
        src = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty().take(4000)
        val ctx = this
        val dm = resources.displayMetrics
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 玻璃（v2.7.3）：整张翻译卡是共享材质的浮层（轻透明层 + 高光边 + 柔和阴影），弹层档圆角；
            // 其余配色仍是 [Ui.panelBg]（渐变 / 直角语言它自己认）。
            background = if (Ui.theme.isGlassMaterial) Ui.material(ctx, Ui.SURFACE, Ui.SurfaceRole.SHEET, 16f)
                         else Ui.panelBg(ctx, radiusDp = 16f)
            val p = Ui.dp(ctx, 14f); setPadding(p, Ui.dp(ctx, 10f), p, p)
        }
        // 工具条（v2.9）：常驻只剩「→语言 / AI 解析（有译文且配好接口才出现）/ ⋯ / ✕」。
        // 原来一行硬塞 5 个控件，标题（weight=1）被挤成 0px；低频的「重翻」「复制」收进 ⋯ 菜单，
        // 仍是同一批本地回调和同一份译文 —— 只是不再各占一格，标题也重新有位置。
        // 每个入口都是 ≥48dp 的触控区（Ui.button 默认 32dp，这里按手机最小触控尺寸抬上去）。
        val bar = Ui.row(ctx)
        titleView = Ui.text(ctx, "", 13f, Ui.TX2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END   // 窄屏 / 长语言名时先截标题，不把右边按钮挤出卡片
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        langBtn = Ui.button(ctx, "") { cycleLang() }.apply {
            minimumHeight = Ui.dp(ctx, 48f)
            contentDescription = "切换译文的目标语言"
        }
        bar.addView(langBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        // 「AI 解析」：用户点了才整段分析；跑的时候变成「取消解析」（按钮文字由 startAnalysis / cancelAnalysis 刷）
        analyzeBtn = Ui.button(ctx, "AI 解析") { toggleAnalysis() }.apply {
            visibility = View.GONE
            minimumHeight = Ui.dp(ctx, 48f)
            contentDescription = "AI 解析：点按后整段分析这段原文，进行中可取消"
        }
        bar.addView(analyzeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        // 溢出：重翻 / 复制（重翻跳过缓存真发一次请求；复制仍是这份译文）
        moreBtn = Ui.button(ctx, "⋯") { showMoreMenu(moreBtn) }.apply {
            minimumWidth = Ui.dp(ctx, 48f); minimumHeight = Ui.dp(ctx, 48f)
            contentDescription = "更多操作：重翻、复制译文"
        }
        bar.addView(moreBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        bar.addView(Ui.button(ctx, "✕") { finish() }.apply {
            minimumWidth = Ui.dp(ctx, 48f); minimumHeight = Ui.dp(ctx, 48f)
            contentDescription = "关闭翻译卡片"
        })
        root.addView(bar)
        badge = Ui.text(ctx, "", 11f, Ui.TX3).apply { visibility = View.GONE; setPadding(0, Ui.dp(ctx, 4f), 0, 0) }
        showTitle()
        root.addView(badge)
        // 原文那两行同样可点（电脑版 translate.html:425 也把原文区 linkify 了）：英文原文里查词最常见
        srcView = Ui.text(ctx, "", 12.5f, Ui.TX3).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 6f))
            setTextIsSelectable(true)
            movementMethod = WordTap.Tap { span -> openWordIn(srcDisplay, span) }
        }
        setSrcText(src)
        root.addView(srcView)

        // 正文区：译文在这，点词时整块换成词条页（同一张卡内切换，不叠新窗口）
        val body = FrameLayout(ctx)
        val holder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        out = Ui.text(ctx, "", 15f).apply {
            setLineSpacing(0f, 1.35f); setTextIsSelectable(true)
            // 点词优先、其次链接；都不是就交回选字（长按照旧能选）
            movementMethod = WordTap.Tap { span -> openWordIn(displayText, span) }
        }
        holder.addView(out)
        analysisView = Ui.text(ctx, "", 13f, Ui.TX2).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true); visibility = View.GONE; setPadding(0, Ui.dp(ctx, 10f), 0, 0) }
        holder.addView(analysisView)
        scroll = MaxHeightScrollView(ctx, (dm.heightPixels * 0.5f).toInt()).apply { addView(holder) }
        body.addView(scroll, FrameLayout.LayoutParams(-1, -2))

        cardPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            background = if (glass) Ui.material(ctx, Brut.CARD, Ui.SurfaceRole.CARD, 12f)
                         else Ui.panelBg(ctx, radiusDp = 12f)
        }
        val cardBar = Ui.row(ctx)
        cardBar.addView(Ui.button(ctx, "← 返回") { hideWordPane() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 8f) })
        cardWord = Ui.text(ctx, "", 15f, Ui.TX1)
        cardBar.addView(cardWord, LinearLayout.LayoutParams(0, -2, 1f))
        cardRetry = Ui.button(ctx, "重试") { wordHit?.let { h -> loadWord(h, wordContext) } }
        cardRetry.visibility = View.GONE
        cardBar.addView(cardRetry, LinearLayout.LayoutParams(-2, -2))
        cardPane.addView(cardBar)
        cardBody = Ui.text(ctx, "", 13.5f).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true); setPadding(0, Ui.dp(ctx, 8f), 0, 0) }
        cardPane.addView(MaxHeightScrollView(ctx, (dm.heightPixels * 0.45f).toInt()).apply { addView(cardBody) })
        body.addView(cardPane, FrameLayout.LayoutParams(-1, -2))
        root.addView(body)

        setContentView(root)
        window.setLayout(Math.min((dm.widthPixels * 0.92f).toInt(), Ui.dp(ctx, 460f)), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        // 答案面板是悬浮窗，永远在应用之上、贴屏幕上方 —— 翻译卡放到下方，两者不打架
        window.setGravity(Gravity.BOTTOM)
        window.attributes = window.attributes.apply { y = Ui.dp(ctx, 28f) }
        updateLangBtn()
        if (src.isEmpty() && intent?.getBooleanExtra(EXTRA_CLIPBOARD, false) == true) { waitClip = true; out.text = "正在读刚复制的文字…"; return }
        if (src.isEmpty()) { out.text = "没收到选中的文字"; return }
        run()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !waitClip) return
        waitClip = false
        val clip = try { getSystemService(ClipboardManager::class.java).primaryClip } catch (_: Exception) { null }
        src = (clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: "").trim().take(4000)
        setSrcText(src)
        if (src.isEmpty()) { failText("剪贴板里没有文字：先在别的 App 里长按 → 复制，再点「翻译刚复制的」"); return }
        run()
    }

    override fun onDestroy() {
        current.set(true)
        analysis?.abort()
        analysis = null
        wordCall?.abort()
        wordCall = null
        wordLatest.invalidate()
        OrbService.restoreAfterForeground(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            cardVisible -> hideWordPane()
            analysis != null -> cancelAnalysis()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /**
     * 溢出菜单（⋯）：重翻 / 复制。两项都是原来工具条上那两个常驻按钮的同一批动作，没有换实现：
     *  - 重翻 = [run] 的 force 档（跳过缓存真发一次请求，覆盖掉旧译文）；
     *  - 复制 = [copy]（只拷这份译文，复制后的提示照旧）。
     * 锚点就是 ⋯ 按钮本身（48dp 触控）；菜单行高由系统 PopupMenu 主题给，与卡片其它入口同一量级。
     * 两项都不看译文是否为空 —— 和原来那两个常驻按钮一样：没有译文时 [copy] 自己不动、[run] 自己报原因。
     */
    private fun showMoreMenu(anchor: View) {
        val pm = ThemeDialogs.popup(this, anchor, Gravity.END)   // v2.7.3 统一弹层入口
        pm.menu.add(0, MENU_REDO, 0, "重翻（跳过缓存）")
        pm.menu.add(0, MENU_COPY, 1, "复制译文")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_REDO -> run(force = true)
                MENU_COPY -> copy()
            }
            true
        }
        pm.show()
    }

    private fun updateLangBtn() { langBtn.text = "→ " + Prompts.lang(prefs.lang).label }

    private fun cycleLang() {
        val i = Prompts.LANGS.indexOfFirst { it.id == prefs.lang }
        prefs.lang = Prompts.LANGS[(i + 1) % Prompts.LANGS.size].id
        updateLangBtn()
        run()
    }

    private var gen = 0

    /** v2.6.3 auto 不再把 AI 顶到最前：直接用 [Translate.plan] 的顺序（DeepL → 百度 → AI）。 */
    private fun planFor(engine: String, dl: Boolean, bt: Boolean, ai: Boolean): List<String> =
        Translate.plan(engine, dl, bt, ai)

    /** 这一轮某引擎的缓存键：命名空间 + 引擎 + 该引擎的模型 / 版本 + 提示词版本 + 纯翻译档 + 目标语言 + 原文 */
    private fun trKey(e: String, lang: String, text: String, api: ApiConfig): String {
        val model = when (e) {
            Translate.BAIDU -> prefs.baiduModel
            Translate.DEEPL -> "v2"
            else -> api.base + "|" + api.model
        }
        return Cache.key(TR_NS, e, model, "p" + Prompts.TRANSLATE_PROMPT_VERSION, "plain" + TranslationWord.PLAIN_VERSION, lang, text)
    }

    /** 整段解析的缓存键：只跟 AI 模型 / 目标语言 / 提示词版本 / 那句机器译文有关，默认不会被读 */
    private fun analysisKey(lang: String, text: String, ref: String, api: ApiConfig): String =
        Cache.key(AN_NS, "p" + Prompts.TRANSLATE_PROMPT_VERSION, api.base + "|" + api.model, lang, text, ref)

    /**
     * 译文上屏：统一走 [Md]（标题 / 列表 / 链接 / 公式），再套一层词 span（[WordTap.mark]）。
     * [displayText] 同步成渲染后的文本 —— 点词的 offset 与语境都从这份文本取，不吃 Markdown 标记的亏。
     */
    private fun renderOut(text: String) {
        val sp = Md.render(text, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
        val marked = WordTap.mark(sp)
        out.text = marked
        displayText = marked.toString()
    }

    /** 原文那两行：同样套词 span（只给显示用的短文本，不影响发给引擎的原文 [src]） */
    private fun setSrcText(text: String) {
        val shown = text.replace("\n", " ")
        val sp = SpannableString(shown)
        srcView.text = WordTap.mark(sp)
        srcDisplay = shown
    }

    /** 错误 / 提示上屏：屏幕上没有可点的译文了，[displayText] 要一起清掉（否则点词会按旧文本取词） */
    private fun failText(msg: String) {
        displayText = ""
        out.text = msg
    }

    /** @param force 用户点了「重翻」：跳过缓存查找，一定真发请求（覆盖掉旧的译文） */
    private fun run(force: Boolean = false) {
        if (src.isBlank()) {
            current.set(true)
            ++gen
            busy = false
            failText("剪贴板里没有文字：先在别的 App 里长按 → 复制，再点「翻译刚复制的」")
            return
        }
        val api = prefs.api(); val bt = prefs.baidu(); val dl = prefs.deepl(); val lang = prefs.lang; val text = src
        val plan = planFor(prefs.translateEngine, dl.configured, bt.configured, prefs.hasApi())
        if (plan.isEmpty()) { busy = false; analyzeBtn.visibility = View.GONE; failText("还没有能用的翻译：打开 ${Preset.appName} → 翻译引擎 填 DeepL Key 或百度凭证，或者填上 AI 接口"); return }
        current.set(true)
        // 换一轮：旧解析、旧词请求全部作废（各自的票号一起失效，晚回来的结果盖不到新画面上）
        cancelAnalysis(silent = true)
        analysisBody = ""; analysisView.visibility = View.GONE; analysisView.text = ""
        wordCall?.abort(); wordCall = null; wordLatest.invalidate(); hideWordPane()
        val my = AtomicBoolean(false)
        current = my
        busy = true
        val g = ++gen
        raw.setLength(0)
        // 缓存（v1.58）：同一引擎 / 接口 / 模型 / 目标语言下翻过同一段 → 直接用，一个接口都不用调。
        // v2.9：查的是新命名空间（[trKey]），旧版「机器译文 + AI 解析」的富结果**不会**在这里命中。
        val pick = Translate.pickCached(plan, force) { e -> cache.get(trKey(e, lang, text, api)) }
        if (pick != null) {
            raw.append(pick.item.v)
            busy = false
            showTitle()
            renderOut(pick.item.v)
            return
        }
        showTitle()
        out.text = "翻译中…"
        displayText = ""
        thread {
            val fails = ArrayList<String>()
            // v2.6.3：plan 里 AI 只排在机器翻译之后（auto）或单独出现
            for ((i, e) in plan.withIndex()) {
                if (my.get()) return@thread
                val failsNow = fails.toList()
                runOnUiThread { if (g == gen && !my.get()) { showTitle(failsNow); if (raw.isEmpty()) out.text = "翻译中…" } }
                try {
                    // AI 只会在没有机器翻译可用时进来：它直出的就是译文（纯翻译提示词，不夹解析）
                    val r = when (e) {
                        Translate.DEEPL -> dl.translate(text, lang, my)
                        Translate.BAIDU -> bt.translate(text, lang, my)
                        else -> TranslationWord.translate(api, text, lang, object : Solver.Callbacks {
                            // 回调都过一道 my 闸 —— 这一轮被换掉 / 关卡片后，排队中的旧块别再往 UI 上糊
                            override fun onPhase(t: String) { runOnUiThread { if (g == gen && !my.get() && raw.isEmpty()) failText(t) } }
                            override fun onText(t: String) {
                                runOnUiThread { if (g == gen && !my.get()) { raw.append(t); renderOut(raw.toString()) } }
                            }
                            override fun onUsage(prompt: Int, completion: Int) { Prefs(this@TranslateActivity).noteUsage(prompt, completion, 0) }
                        }, my)
                    }
                    if (my.get()) return@thread
                    if (r.isBlank()) throw ApiException(Translate.SHORT[e] + " 返回了空译文")
                    if (e == Translate.AI) {
                        // v2.9：AI 直出的就是纯译文（[TranslationWord.plainPrompt]）。中英互译段能白拿就白拿
                        // （原文 + 这份译文本来就是两边现成原话）；拿不到就不写，不给「只翻译」再塞说明。
                        val body = r.trim()
                        val pair = if (Prompts.wantsBilingual(lang)) Prompts.bilingualPair(text, Prompts.firstBlock(body)) else null
                        val finalText = if (pair == null) body else body + "\n\n" + pair
                        runOnUiThread {
                            if (g == gen && !my.get()) {
                                raw.setLength(0); raw.append(finalText)
                                busy = false
                                renderOut(finalText)
                                showTitle()
                            }
                        }
                        History.Store(this).add(History.KIND_TRANSLATE, Translate.SHORT[e] + " → " + Prompts.lang(lang).label, finalText, text)
                        cache.put(trKey(e, lang, text, api), finalText)
                        return@thread
                    }
                    // 机器翻译：译文先如实显示（它是所选引擎给的，不换）；v2.9 不再自动补 AI 解析
                    runOnUiThread {
                        if (g == gen && !my.get()) {
                            raw.setLength(0); raw.append(r)
                            busy = false
                            renderOut(r)
                            showTitle()
                        }
                    }
                    // 缓存里只存这份纯译文（v2.9）：整段解析从不进这个键，所以默认加载永远是「只有译文」
                    History.Store(this).add(History.KIND_TRANSLATE, Translate.SHORT[e] + " → " + Prompts.lang(lang).label, r, text)
                    cache.put(trKey(e, lang, text, api), r)
                    return@thread
                } catch (ex: Exception) {
                    // AI 流式吐了一半才失败 → 先把这半截从 raw / UI 清干净，免得错误提示接在半截话后面
                    if (e == Translate.AI) runOnUiThread { if (g == gen && !my.get()) { raw.setLength(0); displayText = ""; out.text = "" } }
                    if (i == plan.lastIndex) {
                        busy = false
                        val msg = (ex.message ?: ex.javaClass.simpleName) + if (fails.isNotEmpty()) "\n（前面也没成功：" + fails.joinToString("；") + "）" else ""
                        runOnUiThread { if (g == gen) { failText("出错：$msg"); showTitle() } }
                        return@thread
                    }
                    fails.add(Translate.SHORT[e] + " 没成功：" + Translate.reason(ex.message))
                }
            }
        }
    }

    // ---------------- 整段 AI 解析（用户主动） ----------------

    private fun toggleAnalysis() {
        if (analysis != null) cancelAnalysis() else startAnalysis()
    }

    private fun renderAnalysis() {
        analysisView.visibility = View.VISIBLE
        analysisView.text = Md.render(analysisBody, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
    }

    /** 取消：断开连接、保留已经收到的部分，并如实标一句「已取消」（不是失败） */
    private fun cancelAnalysis(silent: Boolean = false) {
        val c = analysis ?: return
        analysis = null
        c.abort()
        analyzeBtn.text = "AI 解析"
        if (!silent) {
            analysisBody = (analysisBody.trimEnd() + "\n\n（已取消，可再点「AI 解析」重来）").trim()
            renderAnalysis()
        }
    }

    /**
     * 整段解析：译文（[raw]）是唯一译文，解析只补在它下面，**不改译文**，也不进译文的缓存键。
     * 命中 [analysisKey] 的旧解析就直接显示（这是用户点按钮才走到的路，不是默认加载）。
     */
    private fun startAnalysis() {
        val text = src
        val ref = raw.toString().trim()
        if (text.isBlank() || ref.isBlank() || busy) return
        if (!prefs.hasApi()) return
        val api = prefs.api()
        val lang = prefs.lang
        // 解析正文在译文下面：词条页开着就先收起，否则用户点了按钮却看不到任何变化
        hideWordPane()
        analysisView.visibility = View.VISIBLE
        analysisBody = ""
        analyzeBtn.text = "取消解析"
        cache.get(analysisKey(lang, text, ref, api))?.let { item ->
            analysisBody = item.v
            renderAnalysis()
            analyzeBtn.text = "AI 解析"
            return
        }
        val c = Cancel()
        analysis = c
        val g = gen
        analysisBody = "正在生成 AI 解析…"
        renderAnalysis()
        thread {
            val sb = StringBuilder()
            val cb = object : Solver.Callbacks {
                override fun onPhase(t: String) {
                    runOnUiThread { if (isAnalysisCurrent(g, c) && sb.isEmpty()) { analysisBody = "（" + t + "）"; renderAnalysis() } }
                }
                override fun onText(t: String) {
                    runOnUiThread {
                        if (isAnalysisCurrent(g, c)) { sb.append(t); analysisBody = "## AI 解析\n\n" + sb; renderAnalysis() }
                    }
                }
                override fun onUsage(prompt: Int, completion: Int) { Prefs(this@TranslateActivity).noteUsage(prompt, completion, 0) }
            }
            try {
                val a = Solver(api).translateEnrich(text, lang, ref, cb, c)
                runOnUiThread {
                    if (!isAnalysisCurrent(g, c)) return@runOnUiThread
                    analysis = null
                    analyzeBtn.text = "AI 解析"
                    if (a.isBlank()) {
                        failAnalysis("模型返回了空内容")
                    } else {
                        analysisBody = if (a.trim().startsWith("## AI 解析")) a.trim() else "## AI 解析\n\n" + a.trim()
                        renderAnalysis()
                        val body = analysisBody.substringAfter("## AI 解析", "").trim()
                        if (body.isNotEmpty()) cache.put(analysisKey(lang, text, ref, api), analysisBody)
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    if (!isAnalysisCurrent(g, c)) return@runOnUiThread
                    analysis = null
                    analyzeBtn.text = "AI 解析"
                    failAnalysis(ex.message ?: ex.javaClass.simpleName)
                }
            }
        }
    }

    private fun isAnalysisCurrent(g: Int, c: Cancel) = g == gen && analysis === c && !c.get()

    /** 失败如实说原因，并指回按钮重试（部分正文留着，不假装成功、也不清掉已有的） */
    private fun failAnalysis(reason: String) {
        analysisBody = (analysisBody.trimEnd() + "\n\n（AI 解析没成功：" + reason + "。可再点「AI 解析」重试）").trim()
        renderAnalysis()
    }

    // ---------------- 点词 ----------------

    /** 词条页展开：同一张卡里的前面板（译文整块收起，滚动位置记住，返回时恢复） */
    private fun showWordPane() {
        if (cardVisible) return
        savedScrollY = scroll.scrollY
        cardVisible = true
        scroll.visibility = View.GONE
        cardPane.visibility = View.VISIBLE
    }

    /**
     * 收起词条页、把译文还回来。在途的查词请求就地作废（[wordLatest.invalidate] + abort）——
     * 否则用户返回后，一个晚回来的旧词结果还会被当成「当前这个词」写进卡片。
     */
    private fun hideWordPane() {
        if (!cardVisible) return
        cardVisible = false
        wordCall?.abort(); wordCall = null
        wordLatest.invalidate()
        cardPane.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        scroll.post { scroll.scrollTo(0, savedScrollY) }
        cardBody.text = ""
        cardRetry.visibility = View.GONE
    }

    /** 点词：从**被点的那块文本**里按 offset 取词（译文和原文各有自己的文本，不能混用 offset） */
    private fun openWordIn(text: String, span: WordTap.WordSpan) {
        val hit = TranslationWord.wordAt(text, span.start)
            ?: TranslationWord.Hit(span.start, span.start + span.word.length, span.word)
        showWordPane()
        cardWord.text = hit.word
        wordHit = hit
        wordContext = TranslationWord.sentenceAt(text, hit)
        loadWord(hit, wordContext)
    }

    /**
     * 查一个词（用户点词就是授权）。顺序：本机缓存（按词 + 语境）→ 模型（四类内容）→ 兜底短译。
     * 每次请求取一张新票（[wordLatest]），回来时验票 —— 点了别的词、换了语种、关掉卡片之后，
     * 旧请求晚回来也不会覆盖新画面；同时 abort 掉旧连接，不让它在后台继续占着。
     */
    private fun loadWord(hit: TranslationWord.Hit, context: String) {
        wordCall?.abort()
        wordCall = null
        wordHit = hit
        wordContext = context
        val ticket = wordLatest.next()
        cardWord.text = hit.word
        cardRetry.visibility = View.GONE
        cardBody.text = "查询中…"
        if (!prefs.hasApi()) {
            renderWordError(ticket, "没配置 AI 接口，查不了词义（打开 ${Preset.appName} → 接口设置）")
            return
        }
        val api = prefs.api()
        val lang = prefs.lang
        val key = TranslationWord.cacheKey(hit.word, lang, api.base + "|" + api.model, context)
        cache.get(key)?.let { item -> renderWord(ticket, item.v); return }
        val call = TranslationWord.Call()
        wordCall = call
        thread {
            try {
                var outText = call.ask(api, TranslationWord.prompt(hit.word, context), TranslationWord.CARD_MAX_TOKENS)
                if (outText.isBlank()) outText = call.ask(api, WordDict.fallbackPrompt(hit.word), WordDict.MAX_TOKENS_FALLBACK)
                if (call.aborted()) return@thread
                val t = WordDict.tidy(outText, hit.word)
                if (t.isBlank()) {
                    runOnUiThread { renderWordError(ticket, "模型没返回内容，点「重试」再来一次") }
                    return@thread
                }
                cache.put(key, t)
                runOnUiThread { renderWord(ticket, t) }
            } catch (ex: Exception) {
                if (call.aborted()) return@thread
                runOnUiThread { renderWordError(ticket, ex.message ?: ex.javaClass.simpleName) }
            }
        }
    }

    private fun renderWord(ticket: Int, text: String) {
        if (!wordLatest.isCurrent(ticket) || !cardVisible) return
        cardBody.text = Md.render(text, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
        cardRetry.visibility = View.GONE
    }

    /** 失败给真原因 + 「重试」（重试走 [loadWord]，仍是一次用户授权的请求，不会自己重打） */
    private fun renderWordError(ticket: Int, reason: String) {
        if (!wordLatest.isCurrent(ticket) || !cardVisible) return
        cardBody.text = "查词失败：" + reason
        cardRetry.visibility = View.VISIBLE
    }

    private fun copy() {
        if (raw.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Preset.appName + " 翻译", raw.toString()))
        Toast.makeText(this, "已复制译文", Toast.LENGTH_SHORT).show()
    }
}
