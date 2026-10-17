package com.chun.lingzhu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 选中文字 → 系统文字菜单「Ling 翻译」（ACTION_PROCESS_TEXT）→ 浮出一张卡片流式出译文。
 * v1.61：
 *  - 引擎按 Translate.plan 依次兜底（自动档配了 AI 时 = AI → DeepL → 百度），标题写这次用的是哪个、前面为什么没成功；
 *  - EXTRA_CLIPBOARD：翻译「刚复制的文字」—— 微信这类 App 的选中菜单里没有「Ling 翻译」，先复制再点小球菜单 / 通知里的按钮。
 *    Android 10 起只有拿到焦点的应用才能读剪贴板，所以等这张卡片窗口拿到焦点再读。
 *  - 「重翻」：同档有缓存时露出来，一键跳过缓存真发请求（覆盖掉旧结果）。
 *
 * v1.89 完整翻译（用户要「翻译 + 语言点解析」，不是一行直译）：
 *  - 自动档（auto）配了 AI 就把 AI 排最前，默认走富结果；AI 真失败再按原顺序降级。
 *  - 显式选了 DeepL / 百度：译文照旧由所选引擎给，配了 AI 就再补一份语言点解析（[enrich]）——
 *    译文不换、标题照写所选引擎，只是多个「AI 解析」的小标。
 *  - 降级 / 解析失败时如实标注「AI 说明不可用」，绝不拿机器直译冒充完整解析；没配 AI 则带一句「未配置 AI」的诚实说明。
 *  - 缓存键带上翻译提示词版本 + 配了 AI 时的**具体 AI 模型**（base|model，见 [Prompts.TRANSLATE_PROMPT_VERSION]）：
 *    富结果是哪个模型解析的要认得出；只有解析成功的富结果才写进带 AI 模型的键，显式选的引擎也不借别的引擎的缓存。
 */
class TranslateActivity : Activity() {
    companion object {
        const val EXTRA_CLIPBOARD = "fromClipboard"
        /**
         * v1.89：**配置了 AI、但这一轮解析没拿到**（报错 / 空 / 自动档里 AI 先失败降级）时，机器译文后面
         * 如实加的一句说明 —— 不许拿机器直译冒充「有词性、义项、例句」的完整解析。
         */
        private const val NO_RICH_NOTE = "（AI 语言点解析暂时不可用：以上只有机器翻译的直译结果，没有词性、义项和例句分析。）"
        /** v1.89：**压根没配 AI** 时的诚实说明 —— 直说「未配置」，不假装这是完整解析。 */
        private const val NO_AI_NOTE = "（未配置 AI，暂不提供词性例句解析）"
        fun clipboardIntent(ctx: android.content.Context) = Intent(ctx, TranslateActivity::class.java)
            .putExtra(EXTRA_CLIPBOARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    private var waitClip = false
    private lateinit var srcView: TextView
    private var current = AtomicBoolean(false)   // 当前这一轮的取消开关；换语言重翻 / 关卡片时置位
    private lateinit var prefs: Prefs
    private lateinit var out: TextView
    private lateinit var langBtn: TextView
    private lateinit var titleView: TextView
    private val cache by lazy { Cache.translateStore(this) }
    private lateinit var redoBtn: TextView
    /**
     * 标题：「Ling 翻译 · DeepL · 缓存（刚刚）」/「Ling 翻译 · 百度（DeepL 没成功：额度用完）」/
     * 「Ling 翻译 · DeepL · AI 解析」。
     *
     * [cached] 非空 = 这份译文命中了缓存，标题要标出「缓存（多久前）」，同时露出「重翻」让用户能覆盖掉。
     */
    private fun showTitle(engine: String?, cached: Cache.Item? = null, fails: List<String> = emptyList(), enrich: Boolean = false) {
        titleView.text = Preset.appName + " 翻译" + (engine?.let { " · " + Translate.SHORT[it] } ?: "") +
            (if (enrich) " · AI 解析" else "") +
            (cached?.let { " · 缓存（" + Cache.ago(it.created) + "）" } ?: "") +
            (if (fails.isNotEmpty()) "（" + fails.joinToString("；") + "）" else "")
        // 有缓存结果时才给「重翻」：没有可覆盖的东西，按钮没有意义
        redoBtn.visibility = if (cached != null) android.view.View.VISIBLE else android.view.View.GONE
    }
    private val raw = StringBuilder()
    private var src = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.theme = prefs.theme()
        src = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty().take(4000)
        val ctx = this
        val dm = resources.displayMetrics
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = Ui.panelBg(ctx, radiusDp = 16f); val p = Ui.dp(ctx, 14f); setPadding(p, Ui.dp(ctx, 10f), p, p) }
        val bar = Ui.row(ctx)
        titleView = Ui.text(ctx, "", 13f, Ui.TX2)
        bar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        langBtn = Ui.button(ctx, "") { cycleLang() }
        bar.addView(langBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        // 「重翻」：只在有缓存结果时露出来（见 showTitle）—— 翻腻了旧结果时能一键覆盖掉
        redoBtn = Ui.button(ctx, "重翻") { run(force = true) }
        redoBtn.visibility = android.view.View.GONE
        bar.addView(redoBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        bar.addView(Ui.button(ctx, "复制") { copy() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(ctx, 6f) })
        bar.addView(Ui.button(ctx, "✕") { finish() })
        showTitle(null)
        root.addView(bar)
        srcView = Ui.text(ctx, src, 12.5f, Ui.TX3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 6f)) }
        root.addView(srcView)
        out = Ui.text(ctx, "", 15f).apply { setLineSpacing(0f, 1.35f); setTextIsSelectable(true); movementMethod = Links.SelectableTap }
        root.addView(MaxHeightScrollView(ctx, (dm.heightPixels * 0.5f).toInt()).apply { addView(out) })
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
        srcView.text = src
        if (src.isEmpty()) { out.text = "剪贴板里没有文字：先在别的 App 里长按 → 复制，再点「翻译刚复制的」"; return }
        run()
    }

    override fun onDestroy() { current.set(true); super.onDestroy() }

    private fun updateLangBtn() { langBtn.text = "→ " + Prompts.lang(prefs.lang).label }

    private fun cycleLang() {
        val i = Prompts.LANGS.indexOfFirst { it.id == prefs.lang }
        prefs.lang = Prompts.LANGS[(i + 1) % Prompts.LANGS.size].id
        updateLangBtn()
        run()
    }

    private var gen = 0

    /**
     * v1.89 自动档（auto）排引擎：配了 AI 就把 AI 放最前 —— 默认走「完整翻译 + 语言点解析」的富结果；
     * AI 真失败时再按原顺序往下降级（DeepL → 百度），降级拿到的是纯机器直译，会如实标注没有 AI 解析。
     * 显式选单一引擎（deepl / baidu / ai）不改顺序，尊重用户的选择。
     */
    private fun planFor(engine: String, dl: Boolean, bt: Boolean, ai: Boolean): List<String> {
        val p = Translate.plan(engine, dl, bt, ai)
        if (engine == Translate.AUTO && ai && p.isNotEmpty() && p.first() != Translate.AI)
            return listOf(Translate.AI) + p.filter { it != Translate.AI }
        return p
    }

    /**
     * v1.89：机器译文已经出来了（raw 里就是它），再让 AI 在**不改动所选引擎译文**的前提下补语言点解析
     * （提示词见 [Prompts.translateEnrich]）。解析正文流式追加在译文之后；拿不到（没配 / 报错 / 空）返回 null，
     * 由调用方如实标注，不假装有解析。
     */
    private fun enrich(text: String, lang: String, engine: String, ref: String, api: ApiConfig, g: Int, my: AtomicBoolean): String? {
        runOnUiThread {
            if (g == gen && !my.get()) {
                showTitle(engine, enrich = true)
                raw.setLength(0); raw.append(ref).append("\n\n")
                out.text = Md.render(raw.toString() + "（正在生成语言点解析…）", Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
            }
        }
        // 解析正文只在 UI 线程里攒：sb 只被下面这些 runOnUiThread 块碰，工作线程不碰它，
        // 免得解析边流边渲染时串字符 / 上一个引擎的旧块糊上来（每块都先过 g/my 闸）。
        val sb = StringBuilder()
        val cb = object : Solver.Callbacks {
            override fun onPhase(t: String) { runOnUiThread { if (g == gen && !my.get() && sb.isEmpty()) out.text = Md.render(ref + "\n\n（" + t + "）", Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg) } }
            override fun onText(t: String) {
                runOnUiThread {
                    if (g == gen && !my.get()) {
                        sb.append(t)
                        raw.setLength(0); raw.append(ref).append("\n\n").append(sb)
                        out.text = Md.render(raw.toString(), Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg)
                    }
                }
            }
            override fun onUsage(prompt: Int, completion: Int) { Prefs(this@TranslateActivity).noteUsage(prompt, completion, 0) }
        }
        return try {
            val a = Solver(api).translateEnrich(text, lang, ref, cb, my)
            if (!my.get() && a.isNotBlank()) a else null
        } catch (_: Exception) { null }
    }

    /** @param force 用户点了「重翻」：跳过缓存查找，一定真发请求（覆盖掉旧的译文） */
    private fun run(force: Boolean = false) {
        val api = prefs.api(); val bt = prefs.baidu(); val dl = prefs.deepl(); val lang = prefs.lang; val text = src
        val plan = planFor(prefs.translateEngine, dl.configured, bt.configured, prefs.hasApi())
        if (plan.isEmpty()) { out.text = "还没有能用的翻译：打开 ${Preset.appName} → 翻译引擎 填 DeepL Key 或百度凭证，或者填上 AI 接口"; return }
        current.set(true)
        val my = AtomicBoolean(false)
        current = my
        val g = ++gen
        raw.setLength(0)
        // 缓存键（v1.89）：引擎 / 模型 + 提示词版本 + 目标语言 + 原文；配了 AI 就再挂上**这次用的 AI 模型**
        // （base|model）—— 富结果是哪个模型解析的要认得出，光一个「有没有配 AI」的布尔会让不同模型互相串味。
        // 提示词一改（版本号变）或换了 AI 模型，旧键就不该再命中。
        val aiOn = prefs.hasApi()
        val aiTag = if (aiOn) api.base + "|" + api.model else "-"
        fun key(e: String) = Cache.key("translate", e,
            when (e) { Translate.BAIDU -> prefs.baiduModel; Translate.DEEPL -> "v2"; else -> api.base + "|" + api.model },
            "p" + Prompts.TRANSLATE_PROMPT_VERSION, aiTag, lang, text)
        // 缓存（v1.58）：同一引擎 / 接口 / 模型 / 目标语言下翻过同一段 → 直接用，一个接口都不用调。
        // v1.89 查找范围：首选 AI 时只认 AI 那档（别的引擎存的纯直译不能把富结果降级）；显式钉了单个引擎
        // （DeepL / 百度 / AI）也**不借**别的引擎的缓存 —— 所选引擎必须还是所选的那个。只有自动档（没被 AI 顶到最前）
        // 才按 plan 顺序找。force = 显式「重翻」，全部跳过。
        val aiFirst = plan.firstOrNull() == Translate.AI
        val probe = if (aiFirst) listOf(Translate.AI) else plan
        val pick = Translate.pickCached(probe, force) { e -> cache.get(key(e)) }
        if (pick != null) {
            val e = pick.engine
            // 配了 AI 的档里存的就是「机器译文 + AI 解析」的富结果，AI 档本身就是富结果 → 按 Markdown 渲染、亮「AI 解析」标
            val rich = e == Translate.AI || aiOn
            showTitle(e, pick.item, enrich = rich && e != Translate.AI)
            raw.append(pick.item.v); out.text = if (rich) Md.render(pick.item.v, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg) else pick.item.v
            return
        }
        showTitle(plan[0])
        out.text = "翻译中…（" + Translate.SHORT[plan[0]] + "）"
        thread {
            val fails = ArrayList<String>()
            var aiTried = false   // AI 这轮试过（没成功，否则早就 return 了）→ 降级后的机器译文别再花钱去补 AI 解析
            for ((i, e) in plan.withIndex()) {
                if (my.get()) return@thread
                val failsNow = fails.toList()
                runOnUiThread { if (g == gen && !my.get()) { showTitle(e, null, failsNow); if (raw.isEmpty()) out.text = "翻译中…（" + Translate.SHORT[e] + "）" } }
                try {
                    if (e == Translate.AI) aiTried = true
                    val r = when (e) {
                        Translate.DEEPL -> dl.translate(text, lang, my)
                        Translate.BAIDU -> bt.translate(text, lang, my)
                        else -> Solver(api).translate(text, lang, object : Solver.Callbacks {
                            // v1.89：回调都过一道 my 闸 —— 这一轮被换掉 / 关卡片后，排队中的旧块别再往 UI 上糊
                            override fun onPhase(t: String) { runOnUiThread { if (g == gen && !my.get() && raw.isEmpty()) out.text = t } }
                            override fun onText(t: String) { runOnUiThread { if (g == gen && !my.get()) { raw.append(t); out.text = Md.render(raw.toString(), Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg) } } }
                            override fun onUsage(prompt: Int, completion: Int) {
                                // v1.65 用量：AI 翻译也调接口，一样记账（DeepL / 百度不走这里，不计）
                                Prefs(this@TranslateActivity).noteUsage(prompt, completion, 0)
                            }
                        }, my)
                    }
                    if (my.get()) return@thread
                    if (r.isBlank()) throw ApiException(Translate.SHORT[e] + " 返回了空译文")
                    if (e == Translate.AI) {
                        // AI 直出的就是完整结果（译文 + 解析），流式已经写进 raw
                        History.Store(this).add(History.KIND_TRANSLATE, Translate.SHORT[e] + " → " + Prompts.lang(lang).label, r, text)
                        cache.put(key(e), r)
                        return@thread
                    }
                    // 机器翻译：译文先如实显示（它是所选引擎给的，不换）
                    runOnUiThread { if (g == gen && !my.get()) { raw.setLength(0); raw.append(r); out.text = r } }
                    // 配了 AI、且这轮没试过 AI → 在原译文之外补一份语言点解析（拿不到就返回 null）
                    val wantAi = aiOn && !aiTried
                    val extra = if (wantAi) enrich(text, lang, e, r, api, g, my) else null
                    if (my.get()) return@thread
                    // 配了 AI 却没拿到解析（解析失败，或自动档里 AI 刚失败降级到机器翻译）→ 如实标注，
                    // 绝不拿机器直译冒充有词性 / 例句的完整解析；没配 AI 则直说「未配置」。
                    val finalText = when {
                        !extra.isNullOrBlank() -> r + "\n\n" + extra
                        aiOn -> r + "\n\n" + NO_RICH_NOTE
                        else -> r + "\n\n" + NO_AI_NOTE
                    }
                    runOnUiThread { if (g == gen && !my.get()) { raw.setLength(0); raw.append(finalText); out.text = if (aiOn) Md.render(finalText, Theme.argb(0xFF, Ui.theme.heading), Ui.theme.codeBg) else finalText } }
                    History.Store(this).add(History.KIND_TRANSLATE, Translate.SHORT[e] + " → " + Prompts.lang(lang).label, finalText, text)
                    // v1.89：只有「配了 AI 且解析真的成功」才写进带 AI 模型的键；解析失败 / 没配 AI 的诚实机器译文
                    // 不占富键 —— 否则下次会拿「带说明的直译」冒充完整解析。
                    if (!aiOn || !extra.isNullOrBlank()) cache.put(key(e), finalText)
                    return@thread
                } catch (ex: Exception) {
                    // 自动档里 AI 排在机器翻译前面：它流式吐了一半才失败 → 先把这半截从 raw / UI 清干净
                    // （清的动作排到已入队的旧回调之后执行），免得下面机器翻译的结果接在 AI 半截话后面。
                    if (e == Translate.AI) runOnUiThread { if (g == gen && !my.get()) { raw.setLength(0); out.text = "" } }
                    if (i == plan.lastIndex) {
                        val msg = (ex.message ?: ex.javaClass.simpleName) + if (fails.isNotEmpty()) "\n（前面也没成功：" + fails.joinToString("；") + "）" else ""
                        runOnUiThread { if (g == gen) out.text = "出错：$msg" }
                        return@thread
                    }
                    fails.add(Translate.SHORT[e] + " 没成功：" + Translate.reason(ex.message))
                }
            }
        }
    }

    private fun copy() {
        if (raw.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Preset.appName + " 翻译", raw.toString()))
        Toast.makeText(this, "已复制译文", Toast.LENGTH_SHORT).show()
    }
}
