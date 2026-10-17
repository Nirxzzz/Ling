package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 设置页 + 首用授权入口。改动即存（不用点保存），下一次识别就用新设置。
 *
 * v1.66：第一次打开先走「首次配置引导」（一步一页、左右滑，没做完的必需步骤不能往后）。
 * v1.79：桌面图标挪去聊天页，这里降成二级入口；设置页改成「大区tab + 大区内折叠组」。
 * v1.90：设置页默认显示**分类首页**（不再一进来就是长页）—— 首页是「标题 + 必要配置状态卡 +
 * 按用途分类入口（每项一句当前值摘要）」，点分类进入独立子页；子页里仍是折叠组（范式照Nirx 的思考块：
 * 可点标题行展开收起，收起时标题行右侧给一行摘要，不用展开就知道这组现在什么状态）。
 * 底部滑动 tab 去掉；系统返回键：子页 → 首页，首页 → 退出（回 launcher 聊天页）。
 * 首用的悬浮球授权仍在这里：聊天页只挂一条横幅点了跳过来，onStartClicked / 录屏回调那条链没动。
 *
 * v1.91：手机版授权与保活简化 —— 权限（含截屏方式）与启动收成**一个** PermissionActivity 入口；
 * 「后台保活 / 截屏方式」里逐项重复的授权按钮与长说明去掉，只留状态摘要、录屏备用、厂商后台管理与必要诊断。
 * 无障碍状态区分「系统里已授权」（[A11yService.enabledInSystem]）与「此刻已连上」（[A11yService.available]），
 * 由 [KeepAlive.observeA11y] 在 onResume 订阅、onPause 注销，变化时只刷文案不重建整页。
 * 启动按钮 [onStartClicked] 也守这条契约：只有**未授权**（或本机不支持）才走录屏备用请求；
 * 「已授权但没连上」不偷偷退录屏，而是进 PermissionActivity 让用户确认系统开关。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_API = "api"       // v1.63：面板「还没填接口 · 点这里」过来：跳到「接口与模型」分类的「接口」组
        const val EXTRA_MODES = "modes"    // 从模式相关入口过来：跳到「小球与识别」分类的「识别模式」组
        const val EXTRA_ASSIST = "assist"   // 早晚简报点进来：去「记事」页的「待办」那一栏
        const val EXTRA_MEMO = "memo"       // 随手记「看全部」点进来：去「记事」页的「随手记」那一栏

        /**
         * 通知点进来该落在「记事」页（[TodoListActivity]）的哪一栏：0 待办 / 1 随手记 / -1 不是这俩通知来的。
         *
         * v1.78 补：10 页并 5 页那次重构把这两个 extra 的处理器弄丢了 —— 通知点进来只是打开设置首页，
         * 用户还得自己去「提醒」大区里找「待办与随手记」那一行，等于通知的白点了。
         * 映射抽成纯函数是为了单测能直接钉住它（Activity 里的 if 分支单测够不着）。
         */
        fun todoTabOf(assist: Boolean, memo: Boolean): Int = when {
            memo -> 1
            assist -> 0
            else -> -1
        }
        private const val REQ_CAPTURE = 7
        // [切片 D] 文本 / Word / PDF 的 AI 导入走独立页面 ImportActivity；这里只留 Anki 牌组 .apkg 的选文件请求码
        // （SAF 自己回 result，不申请任何权限）
        private const val REQ_ANKI = 4712
        private const val REQ_NOTIFY = 8
        private const val STATE_PAGE = "lingzhu.page"   // 旋转后回到设置页原来那一页
        /** 折叠组收起时标题行右侧那行摘要的最长字数（超了截断加省略号） */
        private const val SUMMARY_MAX = 28
        /** v1.77：下载进度轮询间隔（查系统 DownloadManager 一次） */
        private const val DL_POLL_MS = 500L

        // ================= v1.90 设置页：分类首页 + 7 个分类子页 + 分类内折叠组 =================
        // 默认显示「分类首页」（hub，页 0），点分类进入独立子页；子页里仍是折叠组（标题行可点开、收起时给摘要）。

        /** 设置首页（分类总览，页 0）。返回键在首页 = 退出设置回 launcher。 */
        const val HUB = "设置"

        /** 7 个按用途分的分类。首页按这个顺序列出；子页也按这个顺序排。 */
        const val C_START = "开始使用"
        const val C_ORB = "小球与识别"
        const val C_WORDS = "背单词"
        const val C_REMIND = "提醒与免打扰"
        const val C_LOOK = "外观与阅读"
        const val C_API = "接口与模型"
        const val C_ADV = "高级设置"

        /**
         * 分类页序（首页在最前，后面跟这 7 个）。纯数据，便于单测核对接线不丢分类：
         * 首页写错 / 分类漏接，页序和 [GROUPS] 的 tab 就对不上。
         */
        val CATEGORIES = listOf(C_START, C_ORB, C_WORDS, C_REMIND, C_LOOK, C_API, C_ADV)

        // ---- 折叠组 id。id 是 Prefs.openGroups 的存档键，改名等于把老用户这一组的展开态丢掉（会退回收起，仅此而已）----
        const val G_AUTH = "start.auth"        // 上手与授权 / 权限与启动（原开始页）
        const val G_KEEP = "start.keep"        // 后台保活（原保活页）
        const val G_DATA = "start.data"        // 数据与更新（历史 / 缓存 / 更新 / 重走引导）
        const val G_SCAN = "orb.scan"          // 自动扫描（从小球与识别拆出来的，原来是 data 组里混着的）
        const val G_API = "api.api"            // 接口与模型（原接口页）
        const val G_TRANSLATE = "api.translate"// 翻译（原翻译页）
        const val G_MODES = "api.modes"        // 识别模式（原模式页）
        const val G_COLOR = "look.color"       // 配色与字号（原外观页）
        const val G_PANEL = "look.panel"       // 面板与小球（原面板页）
        const val G_CUSTOM = "look.custom"     // 自定义中心
        const val G_WORDS_POP = "words.pop"    // 弹词设置
        const val G_WORDS_DECK = "words.deck"  // 词库
        const val G_REMIND = "remind.all"      // 提醒与知识卡

        /**
         * 一个折叠组的登记项：id / 组名 / 归属分类。
         * v1.90 起没有 defaultOpen —— 默认一律收起，只有「没做完的必需项」（授权）由 [Sections.locked] 强制展开。
         */
        data class GroupDef(val id: String, val title: String, val tab: String)

        /** 折叠组清单（按分类排好）。组内 id 是 [Prefs.openGroups] 的存档键，必须唯一且稳定。 */
        val GROUPS: List<GroupDef> = listOf(
            GroupDef(G_AUTH, "上手与授权", C_START),
            GroupDef(G_MODES, "识别模式", C_ORB),
            GroupDef(G_PANEL, "面板与小球", C_ORB),
            GroupDef(G_SCAN, "自动扫描（高级）", C_ORB),
            GroupDef(G_WORDS_POP, "弹词设置", C_WORDS),
            GroupDef(G_WORDS_DECK, "词库", C_WORDS),
            GroupDef(G_REMIND, "提醒与知识卡", C_REMIND),
            GroupDef(G_COLOR, "配色与字号", C_LOOK),
            GroupDef(G_CUSTOM, "自定义中心", C_LOOK),
            GroupDef(G_API, "接口与模型", C_API),
            GroupDef(G_TRANSLATE, "翻译", C_API),
            GroupDef(G_KEEP, "后台保活", C_ADV),
            GroupDef(G_DATA, "数据与更新", C_ADV),
        )
    }

    /**
     * 折叠组的纯逻辑（不碰 View / SharedPreferences，单测直接测这里）。
     *
     * 展开态存 Prefs.openGroups —— 存的是**当前展开的组 id 全集**，不是「用户手动展开过的」；
     * 用户每点一次就整份覆盖，只有一个键，语义不绕。v1.90 起没有「默认展开的组」（[defaults] 恒为空），
     * 只有「上手与授权」在没做完时由 [locked] 强制展开。
     */
    object Sections {
        /**
         * 首次进入（Prefs 里还没存过）时默认展开的组 —— v1.90 起**一律为空**：
         * 词库 / 提醒不再默认摊开，只有用户自己点开过才展开。没做完的必需项由 [locked] 强制展开，不靠这里。
         */
        fun defaults(groups: List<GroupDef>): Set<String> = emptySet()

        /** 这个组现在是不是展开的。[locked] 为 true 时（授权没做完的上手与授权）恒展开，且不许收起 */
        fun isOpen(id: String, open: Set<String>, locked: Boolean = false): Boolean = locked || id in open

        /**
         * 点标题行，返回新的展开集合。
         * 锁着的组点了不动（返回原集合）—— 授权没做完之前不许把「该做的事」藏起来。
         */
        fun toggled(id: String, open: Set<String>, locked: Boolean = false): Set<String> =
            if (locked) open else if (id in open) open - id else open + id

        /** 「上手与授权」在授权没做完时锁住：悬浮窗权限或接口还没搞定，就一直摊开让人先做 */
        fun locked(id: String, overlay: Boolean, apiReady: Boolean): Boolean = id == G_AUTH && !(overlay && apiReady)
    }

    private lateinit var prefs: Prefs
    private lateinit var permLine: TextView
    private lateinit var startBtn: Button
    /** v1.62「后台保活」清单：每项一个按钮，文字前面写 ✓ / ✗（不靠颜色），onResume 刷新 */
    private val keepRows = ArrayList<() -> Unit>()
    /**
     * v1.90 分类首页的刷新函数：必要配置状态卡 + 每个分类入口那一行「当前值摘要」。
     * build 时和 onResume 各刷一次 —— 权限 / 接口 / 配色这些一改，首页摘要立刻跟着变。
     */
    private val hubRows = ArrayList<() -> Unit>()
    private lateinit var testRes: TextView
    private lateinit var updateRes: TextView
    /** v1.77：发现新版时才亮出来的下载按钮，平时 GONE。点了才真走 DownloadManager */
    private lateinit var updateDlBtn: Button
    /** v1.77：下载进度行（进度条 + 百分比）。不在下载时整行 GONE */
    private lateinit var dlProgRow: LinearLayout
    private lateinit var dlProgBar: ProgressBar
    private lateinit var dlProgText: TextView
    /** v1.77：当前检查到的那个新版（下载按钮点它）；没有新版时为 null */
    private var currentManifest: Update.Manifest? = null
    /** v1.77：下载轮询（500ms 一次查系统 DownloadManager）；只在下载中跑 */
    private val dlHandler = Handler(Looper.getMainLooper())
    private var dlPolling = false
    /** 已提示过「下完了要不要装」，避免连着弹两次安装器 */
    private var dlInstallPrompted = false
    /** [UpdateDirect] 直连下载进行中（系统下载器缺位 / 卡 0 字节时的兜底通道） */
    private var directDl = false
    private var directUri: Uri? = null
    /** 系统下载器 enqueue 的时刻（「字节 0 卡住 12 秒」判定起点） */
    private var dmStartedAt = 0L
    /** 现在显示的是首次配置引导（不是设置页） */
    private var inSetup = false
    /** 正在 build() 重建视图树：期间禁止再触发一次「换配色重建」，避免 build→pickTheme→build 递归 */
    private var building = false
    /**
     * v1.91 无障碍观察器：系统开关 / 服务绑定一旦变动（从系统设置回来、被系统解绑），就地刷新状态与引导。
     * onResume 订阅、onPause 注销（见 [onResume] / [onPause]）。回调在主线程，只改文案/可见性，不重建整页。
     */
    private var a11yWatch: java.io.Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 先把当前配色装进 Ui.theme 再建视图：Brut 的颜色是 getter，建控件那一刻就要取到正确色板
        Ui.theme = prefs.theme()
        title = Preset.appName
        // v1.77：内置词库开箱可选，正文首次要从 assets 读 —— 放后台线程预热，别让设置页首次渲染卡一下
        Thread { try { WordBank.warm(applicationContext) } catch (_: Exception) {} }.start()
        QuickActivity.publishShortcuts(this)   // v1.64：桌面图标长按的快捷方式
        // v1.66：老用户（v1.66 前就配置过）升级上来不拦去走引导，直接记成走完
        if (!prefs.onboarded && !prefs.onboardStarted && Onboarding.legacyConfigured(prefs.keys())) prefs.onboarded = true
        if (Onboarding.shouldShow(prefs.onboarded, prefs.onboardStarted, prefs.keys())) showSetup()
        else {
            showSettings(intent, savedInstanceState)
            // 旋转 / 进程重建：intent 还是启动时那只，里面的跳转 extra 上一轮已经消费过，
            // 别再跑一遍（否则重建后会再弹一次「记事」页 / 把人倒回启动目标）
            if (savedInstanceState == null) openTodoFrom(intent)
        }
    }

    /** 旋转 / 重建后回到原来那一页（设置页底部类别栏跟着回到选中项） */
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        // 存页名不存下标：页数随版本变（10 页并 5 页那次，老下标恢复会被钳到错页）
        if (!inSetup && ::pager.isInitialized) out.putString(STATE_PAGE, pageNames.getOrNull(pager.current))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)     // 让 getIntent() 跟上这次新 intent —— 否则它一直是启动那只旧的，下次重建会读到过期状态
        if (inSetup) return   // 引导没走完：先走完再说
        if (openTodoFrom(intent)) return
        if (intent.getBooleanExtra(EXTRA_API, false)) scrollToApi()
        if (intent.getBooleanExtra(EXTRA_MODES, false)) { rebuildModes(); scrollToModes() }
    }

    /**
     * 早晚简报 / 随手记「看全部」的通知点进来：直接把人送到「记事」页对应那一栏。
     * 返回 true = 这个 intent 就是这俩通知来的（已经处理掉，不用再往下走设置页的跳转）。
     *
     * 为什么不是「跳到设置里的待办区块」：待办 / 随手记现在是独立的 [TodoListActivity]
     * （设置「提醒」大区里那一行只是它的入口），所以正确落点是那个 Activity，不是某一页某一组。
     */
    private fun openTodoFrom(i: Intent?): Boolean {
        if (i == null) return false
        val tab = todoTabOf(
            assist = i.getBooleanExtra(EXTRA_ASSIST, false),
            memo = i.getBooleanExtra(EXTRA_MEMO, false),
        )
        if (tab < 0) return false
        return try {
            startActivity(TodoListActivity.intent(this, tab))
            true
        } catch (_: Exception) { false }
    }

    private fun showSettings(i: Intent?, saved: Bundle? = null) {
        inSetup = false
        keepRows.clear()
        val root = build()
        setContentView(root)
        Brut.page(this, root)
        Brut.style(root)
        // 按页名恢复：老版本 Bundle 里存的下标 / 已删掉的页名都自然落回首页（下标 0）
        val keep = saved?.getString(STATE_PAGE)?.let { pageNames.indexOf(it) } ?: -1
        if (keep >= 0) pager.goTo(keep, animate = false)
        // 只有「首次进入」（saved == null）才按 extra 跳转；旋转 / 重建时页已按页名恢复，
        // 再无条件跑 extra 会覆盖恢复结果、把人倒回启动时的目标页
        if (saved == null) {
            if (i?.getBooleanExtra(EXTRA_API, false) == true) scrollToApi()
            if (i?.getBooleanExtra(EXTRA_MODES, false) == true) scrollToModes()
        }
    }

    // ---------------- 设置分页：分类首页 + 7 个分类子页；子页内部是折叠组 ----------------
    private lateinit var pager: SwipePager
    private val pageNames = ArrayList<String>()
    private val pageScrolls = ArrayList<ScrollView>()

    /**
     * 跳到某个分类里的某个折叠组：先把组展开（收起着滚过去只能看见一行标题，等于没跳），再滚到它的标题行。
     * [group] 不存在就直接返回 —— 拼错的组 id 不该把用户带到一个空页。
     */
    private fun jumpTo(page: String, group: String) {
        val i = pageNames.indexOf(page)
        if (i < 0) return
        val box = groupBoxes[group] ?: return
        setGroupOpen(group, true)
        pager.goTo(i, animate = false)
        // 展开会改变组高度，所以要等这一轮布局跑完再按最终位置滚。
        // 组根到分类页顶的距离 = root.top（含外边距）+ head.top，才是真正的滚动偏移。
        pageScrolls[i].post {
            pageScrolls[i].smoothScrollTo(0, box.root.top + box.head.top)
        }
    }
    private fun scrollToModes() = jumpTo(C_ORB, G_MODES)
    private fun scrollToApi() = jumpTo(C_API, G_API)

    /**
     * v1.90.1：把首页摘要和所有折叠组标题摘要都按最新设置重刷一遍。
     *
     * 之前这些只在 [onResume] 刷 —— 而在设置页里改完一项，用户是同 Activity 内点「‹ 设置」或按系统返回回首页，
     * **不会走 onResume**，于是首页分类入口和收起着的组标题还显示改之前的旧值。凡是「回首页 / 换分类」都要刷这里。
     */
    private fun refreshSummaries() {
        hubRows.forEach { it() }
        groupBoxes.values.forEach { it.render() }
    }

    /** 系统返回键：分类子页 → 回首页（顺手刷新摘要）；首页 → 交回系统（退出设置，回 launcher 聊天页）。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!inSetup && ::pager.isInitialized && pager.current != 0) { pager.goTo(0); refreshSummaries(); return }
        super.onBackPressed()
    }

    /** 首页每个分类入口下那行「当前值摘要」—— 不用点进去就知道这块现在什么状态。 */
    private fun catSummary(cat: String): String = when (cat) {
        C_START -> when {
            !Settings.canDrawOverlays(this) -> "先开悬浮窗权限（必需）"
            !prefs.hasApi() -> "再配一下接口（必需）"
            else -> "权限与接口都好了，可以启动小球"
        }
        C_ORB -> Modes.label(prefs.mode, prefs.customModes) + " · 面板 " + prefs.panelCurWidthPct + "% · 小球 " + prefs.orbSizeDp + "dp"
        C_WORDS -> (if (prefs.wordOn) "弹词每 " + Prefs.fmtInterval(prefs.wordEverySec) else "弹词已关") + " · 勾选 " + prefs.wordDecksOn.size + " 个词库"
        C_REMIND -> listOf("早简报" to prefs.briefMorningOn, "晚简报" to prefs.briefNightOn, "知识卡复习" to prefs.reviewNotiOn)
            .filter { it.second }.map { it.first }.let { if (it.isEmpty()) "提醒都关着" else it.joinToString("、") }
        C_LOOK -> (if (Theme.normId(prefs.themeId) == Theme.CUSTOM) "自定义配色"
            else themeLabel(Theme.normId(prefs.themeId), Theme.PRESETS.firstOrNull { it.first == Theme.normId(prefs.themeId) }?.second ?: prefs.themeId)) + " · 正文 " + prefs.textSp + "sp"
        C_API -> {
            val a = prefs.api()
            when {
                a.baseUrl.isBlank() -> "还没填接口"
                else -> a.model.ifBlank { a.baseUrl.removePrefix("https://").removePrefix("http://") }
            }
        }
        C_ADV -> "缓存 " + ((Cache.solveStore(this).bytes() + Cache.translateStore(this).bytes() + 1023) / 1024) + " KB · 扫描 " + prefs.intervalSec + "s"
        else -> ""
    }

    // ---------------- 模式（v1.58：多个自定义模式，各有名字 / 提示词 / 面板不透明度）----------------
    private lateinit var modesBox: LinearLayout
    private lateinit var historyBtn: Button
    private lateinit var cacheLine: TextView
    private fun refreshCacheLine() {
        if (!::cacheLine.isInitialized) return
        val a = Cache.solveStore(this); val b = Cache.translateStore(this)
        cacheLine.text = "现在缓存了：识别 ${a.count()} 条、翻译 ${b.count()} 条，占 " + ((a.bytes() + b.bytes() + 1023) / 1024) + " KB" +
            (if (prefs.cacheHits > 0) " · 已帮你省了 ${prefs.cacheHits} 次调用" else "")
    }

    private fun rebuildModes() {
        val box = modesBox
        box.removeAllViews()
        fun tip(s: String) = box.addView(TextView(this).apply { text = s; textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(2f), 0, dp(4f)) })
        fun watch(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        })
        val customs = prefs.customModes
        val radioOf = HashMap<String, RadioButton>()
        fun desc(m: String) = when (m) { Prompts.MODE_EXAM -> "（答案+解析+考点）"; Prompts.MODE_GENERAL -> "（识别整屏）"; else -> "（我的提示词）" }
        val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (m in Modes.all(customs)) {
            val rb = RadioButton(this).apply { text = Modes.label(m, customs) + desc(m); tag = m; id = View.generateViewId() }
            g.addView(rb); radioOf[m] = rb
            if (m == prefs.mode) rb.isChecked = true
        }
        g.setOnCheckedChangeListener { grp, checkedId -> (grp.findViewById<View>(checkedId)?.tag as? String)?.let { prefs.mode = it } }
        box.addView(g)
        tip("长按小球 → 菜单，或者点答案面板左上角的模式标签（会拉出一列模式），都能随时切。")

        // v1.66：内置模式的面板不透明度（以前在面板的模式托盘里调；托盘改成只列模式的抽屉后挪到这里，功能不变）
        box.addView(TextView(this).apply { text = "内置模式的面板不透明度"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12f), 0, 0) })
        for (m in Modes.BUILTIN) {
            val opLabel = TextView(this).apply { textSize = 13f; setPadding(0, dp(6f), 0, 0) }
            val follow = Switch(this).apply { text = Modes.label(m, customs) + "：跟随配色"; textSize = 14f }
            val seek = android.widget.SeekBar(this).apply { max = Modes.OPACITY_MAX - Modes.OPACITY_MIN }
            fun show() {
                val o = prefs.modeOpacity(m)
                val shown = if (o > 0) o else prefs.baseTheme().opacity
                opLabel.text = Modes.label(m, customs) + "模式的面板不透明度：$shown%" + if (o == 0) "（跟随配色）" else if (o < 70) "（很透明，后面的内容会透上来）" else ""
                follow.isChecked = o == 0
                seek.isEnabled = o > 0; seek.progress = shown - Modes.OPACITY_MIN
            }
            follow.setOnCheckedChangeListener { _, on -> if (on != (prefs.modeOpacity(m) == 0)) { prefs.setModeOpacity(m, if (on) 0 else prefs.baseTheme().opacity); show() } }
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { prefs.setModeOpacity(m, Modes.OPACITY_MIN + p); show() } }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            box.addView(opLabel); box.addView(follow); box.addView(seek)
            show()
        }

        for (c in customs) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
            Brut.card(card)
            card.addView(TextView(this).apply { text = "自定义模式 · 名字"; textSize = 13f })
            card.addView(EditText(this).apply {
                setText(c.name); setSingleLine(); hint = "比如：错题本、只要答案、英文翻译"
                watch(this) { v -> val name = v.trim().ifEmpty { "自定义" }; prefs.updateCustom(c.id) { it.copy(name = name) }; radioOf[Modes.PREFIX + c.id]?.text = name + "（自定义）" }
            })
            card.addView(TextView(this).apply { text = "解析提示词"; textSize = 13f; setPadding(0, dp(6f), 0, 0) })
            val pe = EditText(this).apply {
                setText(c.prompt); hint = Prompts.CUSTOM_EXAMPLE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START; textSize = 14f
                watch(this) { v -> prefs.updateCustom(c.id) { it.copy(prompt = v) } }
            }
            card.addView(pe)
            val opLabel = TextView(this).apply { textSize = 13f; setPadding(0, dp(6f), 0, 0) }
            val follow = Switch(this).apply { text = "面板不透明度跟随配色"; isChecked = c.opacity == 0; textSize = 14f }
            val seek = android.widget.SeekBar(this).apply { max = Modes.OPACITY_MAX - Modes.OPACITY_MIN }
            fun show() {
                val o = prefs.customModes.firstOrNull { it.id == c.id }?.opacity ?: 0
                val shown = if (o > 0) o else prefs.baseTheme().opacity
                opLabel.text = "这个模式的面板不透明度：$shown%" + if (o == 0) "（跟随配色）" else if (o < 70) "（很透明，后面的内容会透上来）" else ""
                seek.isEnabled = o > 0; seek.progress = shown - Modes.OPACITY_MIN
            }
            follow.setOnCheckedChangeListener { _, on -> prefs.updateCustom(c.id) { it.copy(opacity = if (on) 0 else prefs.baseTheme().opacity) }; show() }
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { prefs.updateCustom(c.id) { it.copy(opacity = Modes.OPACITY_MIN + p) }; show() }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            card.addView(opLabel); card.addView(follow); card.addView(seek)
            show()
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Button(this).apply { text = "填入示例"; setOnClickListener { pe.setText(Prompts.CUSTOM_EXAMPLE); pe.setSelection(pe.text.length) } })
            btns.addView(Button(this).apply {
                text = "删除这个模式"; tag = Brut.DANGER
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setMessage("删除自定义模式「" + (prefs.customModes.firstOrNull { it.id == c.id }?.name ?: c.name) + "」？提示词也会一起删掉。")
                        .setPositiveButton("删除") { _, _ ->
                            val wasActive = prefs.mode == Modes.PREFIX + c.id
                            prefs.customModes = prefs.customModes.filter { it.id != c.id }
                            if (wasActive) prefs.mode = Prompts.MODE_GENERAL
                            rebuildModes()
                        }
                        .setNegativeButton("取消", null).show()
                }
            })
            card.addView(btns)
        }
        box.addView(Button(this).apply {
            text = "＋ 新建自定义模式"
            setOnClickListener {
                val list = prefs.customModes
                val c = Modes.Custom(Modes.newId(list), Modes.newName(list), "")
                prefs.customModes = list + c
                prefs.mode = Modes.PREFIX + c.id
                rebuildModes()
            }
        })
        Brut.style(box)
        tip("每个自定义模式有自己的名字、提示词和面板不透明度（比如「错题本」用不透明，「看视频字幕」调透明一点）。提示词直接写你想让它怎么解析截图，比如「只告诉我答案，不要解释」「把画面里的英文翻译成中文」。留空就用灰色示例。「纯小白讲解」开着时也会带上。")
    }

    override fun onResume() {
        super.onResume()
        // v1.91：无障碍的「系统开关 / 是否已连上」随时可能变（从系统设置回来、被系统解绑）——
        // 在前台时订阅观察器，变了就地刷新状态与引导；onPause 注销。先订再刷，避免刚好错过一次变动。
        a11yWatch?.close()
        a11yWatch = KeepAlive.observeA11y(this) { refreshA11yDependent() }
        if (inSetup) { refreshSetup(); return }
        refreshState()
        keepRows.forEach { it() }
        hubRows.forEach { it() }    // 首页：必要配置状态卡 + 各分类的当前值摘要
        refreshCacheLine()
        if (::historyBtn.isInitialized) historyBtn.text = "查看历史记录（" + History.Store(this).list().size + " 条）"
        // 从系统设置页开完权限回来：「上手与授权」可能刚刚解锁（能收了），摘要里的状态也变了 —— 标题行重渲染一遍
        groupBoxes.values.forEach { it.render() }
        restoreUpdateDownload()   // v1.77：上次没下完的更新包，接着看进度 / 补弹安装
    }

    override fun onPause() {
        super.onPause()
        a11yWatch?.close(); a11yWatch = null   // v1.91：不在前台就别收观察通知了，回来看 onResume 会重新订阅
        stopDlPolling()   // v1.77：页面不在前台就别查了，回来看 onResume 会重新对账
    }

    /**
     * v1.91：无障碍开关 / 绑定变化时就地刷新。只改文案与可见性（refreshState / 摘要 / 折叠组标题重渲染），
     * **不重建整页** —— 设置页里有正在编辑、还没保存的输入框（接口地址 / Key / 模型等），
     * 重建会把它们连同焦点 / 滚动位置一起丢掉（[rebuildSettingsPreservingState] 那条整棵重建路只在换配色时走）。
     */
    private fun refreshA11yDependent() {
        if (inSetup) { refreshSetup(); return }
        if (!::permLine.isInitialized) return
        refreshState()
        hubRows.forEach { it() }
        groupBoxes.values.forEach { it.render() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDlPolling()
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    /**
     * v1.90「一句用途 + 详情点开」的一行：短句后面跟一个可点的「详情 ›」，点开显示完整原说明。
     * 完整的授权要求 / 技术名 / 警示都原样放在 [full] 里，不丢。返回整块，调用方自己 addView。
     */
    private fun detailTip(short: String, full: String): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2f), 0, dp(4f)) }
        val shortTv = TextView(this).apply { textSize = 12.5f; alpha = 0.75f }
        val detail = TextView(this).apply {
            text = full; textSize = 12.5f; alpha = 0.75f; visibility = View.GONE
            setLineSpacing(0f, 1.25f); setPadding(0, dp(3f), 0, dp(2f))
        }
        val ss = android.text.SpannableString(short + "  详情 ›")
        val start = short.length + 2
        ss.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(w: View) { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            override fun updateDrawState(ds: android.text.TextPaint) { ds.color = Brut.INK; ds.isUnderlineText = false; ds.isFakeBoldText = true }
        }, start, ss.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        shortTv.text = ss
        shortTv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        wrap.addView(shortTv)
        wrap.addView(detail)
        return wrap
    }

    // ---------------- v1.79 折叠组 ----------------

    /** 组 id → 已建好的折叠组件。跳转 / 刷新都要拿到它 */
    private val groupBoxes = LinkedHashMap<String, SectionBox>()

    /** 组现在是不是锁着的（只有「上手与授权」会锁：授权没做完之前不许收起） */
    private fun groupLocked(id: String) = Sections.locked(id, Settings.canDrawOverlays(this), prefs.hasApi())

    /** 当前展开的组 id（Prefs 里还没有存档时用默认清单那份，跟 render() 的兜底一致） */
    private fun openGroupIds(): Set<String> = prefs.openGroups ?: Sections.defaults(GROUPS)

    private fun setGroupOpen(id: String, open: Boolean) {
        val box = groupBoxes[id] ?: return
        // 锁着的组只有一种合法状态（展开），点了也不动
        val locked = groupLocked(id)
        if (locked) { box.show(true); return }
        val cur = openGroupIds()
        // 已经在目标状态就别动：既省一次 prefs 写，也不打断用户正在做的操作
        if (box.renderedOpen == open && Sections.isOpen(id, cur, locked) == open) return
        // 展开 / 收起的集合算法走 Sections.toggled（单测钉的就是它，别在这里另写一套）
        val next = if (open) Sections.toggled(id, cur - id, locked) else Sections.toggled(id, cur, locked)
        prefs.openGroups = next
        box.show(Sections.isOpen(id, next, locked))
    }

    /**
     * 一个可复用的折叠组：标题行（第一行 ▸/▾ + 组名，第二行 收起时的摘要）+ 内容盒。
     *
     * 范式照Nirx 的思考块：默认收起、点标题才展开，展开态记在 Prefs（下次进来还是那样）。
     * 摘要文字由调用方给（[summary]）—— 收起时那一行要能看出「这组里现在是什么状态」，
     * 不用展开就知道该不该点。v1.90.1 起摘要排在组名**下面**（不再同横行），这样组名再大也挤不掉它；
     * 仍按 [SUMMARY_MAX] 截断（最多两行）。
     */
    private inner class SectionBox(
        val id: String,
        title: String,
        private val summary: () -> String,
    ) {
        /** 内容盒：调用方往这里 addView */
        val content = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        private val arrow = TextView(this@MainActivity).apply {
            textSize = 14f; setTextColor(Brut.INK); gravity = Gravity.CENTER; tag = Brut.KEEP
            setPadding(0, 0, dp(2f), 0)
        }
        private val name = TextView(this@MainActivity).apply {
            text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); tag = Brut.KEEP
        }
        // v1.90.1：摘要改挂在标题**下面**（整行），不再跟组名挤同一横行 —— 组名再长、字号再大也压不掉摘要。
        // maxLines = 2：给两行，长摘要不至于被 16sp 的名字一挤就只剩省略号。
        private val hint = TextView(this@MainActivity).apply {
            textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP   // v1.85：裸 hex → Brut.HINT（同值）
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        }
        /** 标题那半行：▸ + 组名（摘要另起一行，放 [head] 里） */
        private val headRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        /**
         * 标题行（可点）：第一行 ▸ + 组名，第二行收起时的摘要。存成字段是为了 jumpTo 滚到它
         * （head 的 top 相对 root 仍是 0，[jumpTo] 的 `root.top + head.top` 不受布局改动影响，跳转身份不变）。
         */
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Brut.box(this@MainActivity, Brut.CARD, 3f)
            setPadding(dp(12f), dp(11f), dp(14f), dp(11f))
            isClickable = true; isFocusable = true
        }
        // 刻意不打 Brut.KEEP：组里的按钮 / 开关还得靠 Brut.style(root) 刷成皮肤。
        // 标题行那几个 TextView 是叶子，打 KEEP 只是保住自己的灰字 / 箭头色，不会挡住子控件。
        val root = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        /** 现在渲染出来的状态，避免重复setGroupOpen 时白刷新一次 */
        var renderedOpen = false
            private set

        init {
            headRow.addView(arrow, LinearLayout.LayoutParams(-2, -2))
            headRow.addView(name, LinearLayout.LayoutParams(-2, -2))
            head.addView(headRow, LinearLayout.LayoutParams(-1, -2))
            head.addView(hint, LinearLayout.LayoutParams(-1, -2))
            head.setOnClickListener {
                val locked = groupLocked(id)
                if (locked) { toast("先把上面的授权做完，这一组暂时不能收起"); return@setOnClickListener }
                val opening = !renderedOpen
                setGroupOpen(id, opening)
                // v1.90.1：一组首次被展开时，用现成的 UsageHints 简短提示一次这个折叠交互（id = settings.<组id>）
                if (opening) UsageHints.showOnce(this@MainActivity, "settings.$id", "点标题行能随时收起；这里改了立刻生效，不用保存。")
            }
            root.addView(head, LinearLayout.LayoutParams(-1, -2))
            root.addView(content, LinearLayout.LayoutParams(-1, -2))
        }

        /** 按当前状态渲染一遍（build 时和 onResume 刷新时都调） */
        fun render() {
            val locked = groupLocked(id)
            val open = Sections.isOpen(id, openGroupIds(), locked)
            show(open)
        }

        /** 只切可见性（内容已经建好了，展开收起不重建控件，保住 EditText 的焦点和滚动位置） */
        fun show(open: Boolean) {
            renderedOpen = open
            val locked = groupLocked(id)
            hint.text = if (open) "" else oneLine(summary())
            head.contentDescription = name.text.toString() + if (locked) "，需要先完成授权，不能收起"
            else if (open) "，已展开，点按收起" else "，已收起，点按展开"
            arrow.text = if (open) "▾" else "▸"
            content.visibility = if (open) View.VISIBLE else View.GONE
            hint.visibility = if (open) View.GONE else View.VISIBLE
        }

        /** 摘要截短（超长加省略号；下方整行最多两行，不会再被组名挤掉） */
        private fun oneLine(s: String) = if (s.length <= SUMMARY_MAX) s else s.take(SUMMARY_MAX - 1) + "…"
    }

    /**
     * v1.90.1 组内「高级」盒：把进阶项收进一个默认收起、**不存档**的小盒里（每次进设置都是收着的）。
     *
     * 只负责长出这个盒子并挂到 [parent] 下，返回往里加控件的容器 —— 不动任何 shared 的 col。
     * 和 [build] 里成对的 beginAdvanced()/endAdvanced() 配合：那对负责把 col 指进来 / 退出去（严格成对），
     * 本函数只管造盒子。标题固定「高级设置 · 按需展开」，收起时给一句摘要，展开态只活在这一次视图里。
     */
    private fun advancedBody(parent: LinearLayout): LinearLayout {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6f), 0, dp(2f))
        }
        val arrow = TextView(this).apply { text = "▸"; textSize = 13f; setTextColor(Brut.INK); tag = Brut.KEEP }
        val label = TextView(this).apply {
            text = "高级设置 · 按需展开"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
            setPadding(dp(8f), 0, 0, 0); tag = Brut.KEEP
        }
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(arrow, LinearLayout.LayoutParams(-2, -2))
        headRow.addView(label, LinearLayout.LayoutParams(-2, -2))
        // 收起时的一句话摘要：写明这盒里是进阶项，不展开也能判断要不要点（不用持久化）
        val sum = TextView(this).apply {
            text = "进阶项，默认收起"; textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP
            setPadding(0, dp(2f), 0, 0)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Brut.box(this@MainActivity, Brut.CARD, 3f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            isClickable = true; isFocusable = true
            addView(headRow, LinearLayout.LayoutParams(-1, -2))
            addView(sum, LinearLayout.LayoutParams(-1, -2))
        }
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            arrow.text = if (open) "▾" else "▸"
            sum.visibility = if (open) View.GONE else View.VISIBLE
            head.contentDescription = "高级设置" + if (open) "，已展开，点按收起" else "，已收起，点按展开"
        }
        holder.addView(head, LinearLayout.LayoutParams(-1, -2))
        holder.addView(body, LinearLayout.LayoutParams(-1, -2))
        parent.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        return body
    }

    private fun build(): View {
        building = true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(14f); setPadding(p, dp(6f), p, dp(6f)) }
        // v1.90：设置默认显示分类首页。内容区是左右滑动的分页（首页 + 7 个分类子页），底部不再有类别栏。
        pager = SwipePager(this)
        // 换页（含滑回首页、点分类进/出）就把摘要重刷一遍：设置是就地改的，不能等下一次 onResume
        pager.onPageChanged = { refreshSummaries() }
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        pageNames.clear(); pageScrolls.clear()
        groupBoxes.clear(); hubRows.clear()

        // 下面的小工具都往 col 里加；usePage() 把 col 指到某个分类（首页也行）的内容列，
        // newGroup() 在列里开一个折叠组；面板 / 小球 / 词库那几节要按选择重画，临时把 col 指到它自己的盒子里（v1.60）。
        // 分类列先建好、最后按 CATEGORIES 顺序统一装进 SwipePager —— 这样正文的书写顺序可以跟分类顺序无关。
        val catCols = LinkedHashMap<String, LinearLayout>()
        var col = LinearLayout(this)
        /** 当前分类里「折叠组之外」的那一层：临时改 col 画完子块后要退回这里 */
        var pageBack: LinearLayout = col

        /** 子页顶部那行「‹ 设置 + 分类名」；首页不画（它自己就是顶层）。 */
        fun backHeader(host: LinearLayout, title: String) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(8f)) }
            // 回首页时顺手刷摘要：子页里改过设置，首页那些入口不能还显示旧值（同 Activity 内返回不走 onResume）
            head.addView(Button(this).apply { text = "‹ 设置"; isAllCaps = false; setOnClickListener { pager.goTo(0); refreshSummaries() } })
            head.addView(TextView(this).apply { text = title; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); setPadding(dp(10f), 0, 0, 0) })
            host.addView(head)
        }
        /** 把 col 指到某个分类的内容列；第一次用到时建列，并给子页加返回头。 */
        fun usePage(name: String) {
            val c = catCols.getOrPut(name) {
                LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4f), 0, dp(4f), dp(32f)) }.also {
                    if (name != HUB) backHeader(it, name)
                }
            }
            col = c; pageBack = c
        }
        /**
         * 开一个折叠组：标题行挂在当前 col 上，之后的 h/tip/控件都落进组内的内容盒。
         * 摘要 [summary] 是收起时标题行右侧那一行字（调用方给什么就显示什么，截断在 SectionBox 里做）。
         */
        fun newGroup(id: String, summary: () -> String) {
            val def = GROUPS.first { it.id == id }
            val box = SectionBox(id, def.title, summary)
            groupBoxes[id] = box
            // 外边距在这里给（root 的 layoutParams 会被 addView 覆盖，得在参数里写）
            col.addView(box.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })
            col = box.content
        }
        /** 关掉当前折叠组（col 退回分类那一层），下一个 newGroup / usePage 接着开 */
        fun endGroup() { col = pageBack }
        fun h(s: String) = col.addView(Brut.heading(this, s))
        fun tip(s: String) = col.addView(TextView(this).apply { text = s; textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(2f), 0, dp(4f)) })
        /**
         * v1.90「一句用途 + 详情点开」：短 tip 一行说清用途，后面跟一个「详情 ›」点开看完整原说明
         * （授权要求 / 技术名 / 警示都原样留着，不丢）。只有必要警示的短 tip 仍用 tip()。
         */
        fun tipD(short: String, full: String) = col.addView(detailTip(short, full))
        fun field(label: String, value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit): EditText {
            col.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8f), 0, 0) })
            val e = EditText(this).apply {
                setText(value); this.hint = hint; setSingleLine()
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: ""); keepRows.forEach { it() } }   // 上手清单「② 接口」跟着打勾
                })
            }
            col.addView(e)
            return e
        }
        fun radios(options: List<Pair<String, String>>, selected: String, vertical: Boolean = false, onPick: (String) -> Unit) {
            val g = RadioGroup(this).apply { orientation = if (vertical) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
            for ((id, label) in options) {
                val rb = RadioButton(this).apply { text = label; tag = id; this.id = View.generateViewId(); setPadding(0, 0, dp(10f), 0) }
                g.addView(rb)
                if (id == selected) rb.isChecked = true
            }
            g.setOnCheckedChangeListener { grp, checkedId -> (grp.findViewById<View>(checkedId)?.tag as? String)?.let(onPick) }
            col.addView(g)
        }
        // 滑杆：标签实时写数值（v1.60）
        fun slider(min: Int, max: Int, value: Int, step: Int = 1, label: (Int) -> String, onSet: (Int) -> Unit) {
            val tv = TextView(this).apply { textSize = 15f; setPadding(0, dp(10f), 0, 0); text = label(value) }
            col.addView(tv)
            col.addView(android.widget.SeekBar(this).apply {
                this.max = (max - min) / step; progress = (value - min) / step
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { val v = min + p * step; onSet(v); tv.text = label(v) } }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            })
        }
        fun keepRow(label: () -> String, act: () -> Unit) {
            val b = Button(this).apply { isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { act() } }
            col.addView(b)
            keepRows.add { b.text = label() }
        }
        /**
         * 组内「高级」区的进出：beginAdvanced() 在当前 col 下开一个默认收起的高级盒、把 col 指进去；
         * endAdvanced() 把 col 退回进来之前那一层。**严格成对** —— 漏写 endAdvanced() 会把后面所有控件
         * 都塞进高级盒里（收起后全看不到）。盒子本身由 [advancedBody] 长出来，这里只管 col 的进出。
         */
        var advBack: LinearLayout? = null
        fun beginAdvanced() { advBack = col; col = advancedBody(col) }
        fun endAdvanced() { col = advBack ?: col; advBack = null }

        // ================= 页 0：分类首页 =================
        usePage(HUB)
        col.addView(TextView(this).apply { text = HUB; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); setPadding(0, dp(6f), 0, 0) })
        col.addView(TextView(this).apply {
            text = "v" + BuildConfig.VERSION_NAME.substringBefore("-") + " " + Preset.channel + " · 改动立刻生效，不用点保存"
            textSize = 11f; setTextColor(Brut.HINT); setPadding(0, dp(2f), 0, dp(2f)); tag = Brut.KEEP
        })

        // —— 必要配置状态卡：悬浮窗 / 接口。没配好时这里是首页最显眼的「去做」入口（先完成必要配置）——
        val needCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(needCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        Brut.card(needCard)
        needCard.addView(TextView(this).apply { text = "必要配置"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK) })
        val needLine = TextView(this).apply { textSize = 13.5f; setTextColor(Brut.INK); setPadding(0, dp(6f), 0, dp(8f)); setLineSpacing(0f, 1.3f) }
        needCard.addView(needLine)
        val needBtn = Button(this).apply { tag = Brut.PRIMARY; isAllCaps = false }
        needCard.addView(needBtn)
        hubRows.add {
            val overlay = Settings.canDrawOverlays(this@MainActivity)
            val apiReady = prefs.hasApi()
            needLine.text = "① 悬浮窗权限：" + (if (overlay) "✓ 已开" else "✗ 还没开（没有它小球显示不出来）") +
                "\n② 接口：" + (if (apiReady) "✓ 已填" else "✗ 还没配好（要自己填）")
            when {
                !overlay -> {
                    needBtn.text = "去开悬浮窗（必需）"
                    needBtn.setOnClickListener {
                        try { startActivity(PermissionActivity.intent(this@MainActivity)) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    }
                }
                !apiReady -> {
                    needBtn.text = "去填接口（必需）"
                    needBtn.setOnClickListener { jumpTo(C_API, G_API) }
                }
                else -> {
                    needBtn.text = "✓ 都配好了 · 启动悬浮球"
                    needBtn.setOnClickListener { onStartClicked() }
                }
            }
        }

        // —— 常用动作：一眼点到最常用的几个（功能都还在各自分类里，这里只是快捷入口）——
        h("常用")
        fun quickBtn(label: String, act: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { act() } }
        val quickA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickA.addView(quickBtn("启动悬浮球") { onStartClicked() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        quickA.addView(quickBtn("待办与随手记") { try { startActivity(TodoListActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开待办") } }, LinearLayout.LayoutParams(0, -2, 1f))
        val quickB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quickB.addView(quickBtn("学习与复习") { startActivity(Intent(this@MainActivity, StudyActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        quickB.addView(quickBtn("查看历史记录") { try { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } catch (_: Exception) { toast("打不开历史") } }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(quickA); col.addView(quickB)

        // —— 按用途分类的入口：每项一句当前值摘要（摘要在分类名下面，不挤同一横行）——
        h("按用途分类")
        for (cat in CATEGORIES) {
            val idx = 1 + CATEGORIES.indexOf(cat)
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true }
            row.addView(TextView(this).apply { text = cat; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK) })
            val sum = TextView(this).apply { textSize = 12.5f; setTextColor(Brut.HINT); setPadding(0, dp(3f), 0, 0); setLineSpacing(0f, 1.2f); tag = Brut.KEEP }
            row.addView(sum)
            row.setOnClickListener { pager.goTo(idx) }
            Brut.card(row)
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
            hubRows.add { sum.text = catSummary(cat) }
        }

        // ================= 分类 1：开始使用 =================
        usePage(C_START)

        // ---- 组：上手与授权（原「开始」页）。悬浮窗 / 接口没做完时这一组强制展开且不许收起 ----
        newGroup(G_AUTH) {
            val a = if (Settings.canDrawOverlays(this)) "悬浮窗 ✓" else "悬浮窗 ✗"
            val b = if (prefs.hasApi()) "接口 ✓" else "接口 ✗"
            "$a · $b"
        }
        tipD("悬浮球：双击识别屏幕，单击开关答案面板，按住半秒出菜单，拖动挪位置。",
            "悬浮球：双击 = 识别屏幕，单击 = 开/关答案面板，按住半秒 = 菜单（小球外圈走满一圈、手机震一下就弹出），拖动 = 挪位置。选中文字 → 菜单里的「" + Preset.appName + " 翻译」= 翻译；菜单里没有就先复制，再长按小球点「翻译刚复制的」。")
        tipD("追问：答案面板底部有输入框，旁边两个开关 —— 讲简单点 / 讲为什么。",
            "追问：识别完，面板底部有输入框，旁边两个开关 —— 「what」= 讲简单点并举个例子，「why」= 用第一性原理讲为什么；开着就作用于接下来的追问，可以同时开。输入框空着、开关开着直接点「发送」也行。追问带着同一张截图和前面的回答，回答接在正文后面。")

        // v1.90：权限统一收成一个入口 —— PermissionActivity 里按顺序把系统页递到面前，设置页不再逐项重复细节。
        h("权限与启动")
        keepRow({
            val st = KeepAlive.flowState(this@MainActivity)
            when {
                PermissionFlow.blocked(st).isNotEmpty() -> "★ 权限：还差「悬浮窗」（必需）→ 点这里，我带你一项项开"
                // 已授权 ≠ 已连接：系统里开了但服务没连上时，不能说「都就绪」，优先提示去确认系统开关
                PermissionFlow.a11yAuthorizedNotConnected(st) -> "◇ 权限：无障碍已授权、服务还没连上 → 点这里确认系统开关"
                PermissionFlow.ready(st) -> "✓ 权限：" + PermissionFlow.summary(st) + " → 点这里可重查"
                else -> "★ 权限：" + PermissionFlow.summary(st) + " → 点这里，不用自己翻设置"
            }
        }) {
            try { startActivity(PermissionActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开权限页") }
        }
        // 必需配置②接口：接口没配好也在这里点一下直接跳过去
        keepRow({ if (prefs.hasApi()) "✓ 接口：已填（模型 " + prefs.api().model.ifBlank { "未填" } + "）· 点这里可「检测连接」" else "✗ 接口（地址 / Key / 模型）→ 点这里去填" }) { jumpTo(C_API, G_API) }

        permLine = TextView(this).apply { textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) }
        col.addView(permLine)
        startBtn = Button(this).apply { textSize = 16f; tag = Brut.PRIMARY; setOnClickListener { onStartClicked() } }
        col.addView(startBtn)

        // v1.91：不再单列「无障碍截屏」授权按钮，也不在这里堆「受限设置」长说明 —— 授权统一走上面这一个
        // 权限入口（PermissionActivity），受限设置（Android 13 灰色开关）细节也集中在那页。
        // 这里只留一句录屏备用说明；无障碍的当前状态由 [refreshState] 写在 permLine 上（区分已授权 / 已连上）。
        tipD("没开无障碍截屏也能用：识别时弹一次录屏授权。",
            "无障碍截屏在「权限」页打开后，只要服务连上，识别就不再弹录屏框；开关被关掉或服务没连上时会回落到录屏授权。不想开也行 —— 识别时改用录屏授权，但每次启动都要点一次「立即开始」。")

        endGroup()

        // ================= 分类 7：高级设置（后台保活 + 数据与更新）=================
        usePage(C_ADV)

        // ---- 组：保活（原「保活」页，整页搬过来）----
        // v1.91：逐项授权按钮并进「开始使用 → 权限与启动」那一个入口（不再各自重复一遍）；
        // 这里只留状态摘要 + 厂商后台管理入口 + 诊断（恢复次数 / 看门狗 / 品牌提示）。
        newGroup(G_KEEP) {
            val b = if (KeepAlive.ignoringBattery(this@MainActivity)) "电池 ✓" else "电池 ✗"
            val n = if (KeepAlive.notificationsOn(this@MainActivity)) "通知 ✓" else "通知 ✗"
            val o = if (KeepAlive.canDrawOverlays(this@MainActivity)) "悬浮窗 ✓" else "悬浮窗 ✗"
            "$b · $n · $o"
        }
        h("后台保活（建议都开上）")
        tip("这几样系统权限都在「开始使用 → 权限与启动」一处开：通知、允许后台运行、悬浮窗。开上后 App 被系统清理时会尽力自动恢复，但不保证（能不能回来最终看系统和厂商）。长按小球 →「停止小球（保留授权）」只停掉小球和常驻通知，不会去关系统里的无障碍授权 —— 下次启动不用重新授权。")
        keepRow({ "→ 厂商的自启动 / 后台管理（这一项系统不告诉 App 开没开，自己确认一下）" }) { KeepAlive.openVendorAutostart(this) }
        // v1.63：让人看得见保活在起作用
        keepRow({
            val n = prefs.reviveCount
            if (n == 0) "↻ 还没被系统清理过（被清理后会尽力自动恢复，这里记次数）"
            else "↻ 被系统清理后已自动恢复 $n 次 · 最近 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(prefs.reviveLast)) + "（" + prefs.reviveHow + "）"
        }) { }
        // v1.65：看门狗第四层可见化 —— 排上没排上、被系统拦过几次，坏了能看见
        keepRow({
            if (!prefs.orbWanted) "— 看门狗闹钟：小球已停止，后台自查已停止（下次启动小球会自动重排）"
            else if (prefs.watchdogFails > 0) "⚠ 看门狗闹钟：已排上，但这台手机拦了它 ${prefs.watchdogFails} 次（后台拉起被系统限制）—— 无障碍截屏是后台恢复的重要一层，可在「权限与启动」里开上"
            else if (prefs.watchdogKicks > 0) "✓ 看门狗闹钟：已排上（约 15 分钟自查一次，小球被清干净后由它尽力拉回，不保证）"
            else "— 看门狗闹钟：启动小球后自动排上（第四层尽力兜底，平时不用管）"
        }) {
            // v1.65：点一下立即重排闹钟（怀疑被系统清了就手动补一次），行的计数跟着刷新
            WatchdogReceiver.kick(this)
            android.widget.Toast.makeText(this, "已重新排上看门狗闹钟（约 15 分钟后第一次检查）", android.widget.Toast.LENGTH_SHORT).show()
        }
        tipD("各厂商的自启动 / 后台管理入口不一样，点开看怎么找。", KeepAlive.brandTip())
        h("打游戏时隐藏小球")
        tipD("下拉通知点「隐藏小球」，或加个快捷开关，长按小球菜单里也有。",
            "下拉状态栏 → " + Preset.appName + " 的通知里点「隐藏小球」，再点「显示小球」恢复。\n也可以加快捷开关：下拉状态栏 → 编辑（铅笔图标）→ 把「" + Preset.appName + " 小球」拖进去，以后点它就隐藏 / 恢复，副标题会写「显示中 / 已隐藏」。长按小球 → 菜单里也有「隐藏小球」。")

        endGroup()

        // ---- 组：数据与更新（原「其他」页里的历史 / 缓存 / 更新 / 重走引导）----
        newGroup(G_DATA) {
            val kb = (Cache.solveStore(this@MainActivity).bytes() + Cache.translateStore(this@MainActivity).bytes() + 1023) / 1024
            "缓存 $kb KB · 历史 " + History.Store(this@MainActivity).list().size + " 条"
        }
        h("历史记录")
        col.addView(Switch(this).apply {
            text = "保存历史记录"; isChecked = prefs.saveHistory; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.saveHistory = on }
        })
        historyBtn = Button(this).apply { text = "查看历史记录"; setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } }
        col.addView(historyBtn)
        tipD("开着时每次识别 / 翻译都存本机，最多 ${History.MAX} 条，只有你能看。",
            "开着时，每次识别的结果（连同追问）和每次翻译都存在本机，最多 ${History.MAX} 条，只有你自己能看。「查看历史记录」或长按小球 → 菜单 →「历史记录…」打开，可以搜索、复制、删除。关掉后不再记新的，已有的不动。")

        h("缓存")
        col.addView(Switch(this).apply {
            text = "使用结果缓存"; isChecked = prefs.useCache; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.useCache = on }
        })
        cacheLine = TextView(this).apply { textSize = 14f; setPadding(0, dp(4f), 0, dp(2f)) }
        col.addView(cacheLine)
        col.addView(Button(this).apply {
            text = "清空缓存"; tag = Brut.DANGER
            setOnClickListener { Cache.solveStore(this@MainActivity).clear(); Cache.translateStore(this@MainActivity).clear(); refreshCacheLine(); toast("缓存已清空") }
        })
        tipD("同一画面 / 同一段文字再问一次直接用上次结果：不调接口、不花额度。",
            "同一个画面、同样的模式和设置再识别一次，或者同一段文字再翻译一次，直接用上次的结果：不调接口、不花额度、马上出来，面板上会写「↺ 缓存」。自动扫描时画面没变也不会重复花钱。只认一模一样的画面（状态栏的时间变了不算），宁可重新问也不会拿错答案。想让同一画面重新问，就点上面「清空缓存」（或关掉缓存）再双击小球。识别留 ${Cache.SOLVE_MAX} 条、翻译留 ${Cache.TRANSLATE_MAX} 条，7 天后自动过期。")

        // v1.65 更新通道：填一个清单地址（任意静态托管的 manifest.json），每 24 小时自动查一次，发现新版给一个下载按钮
        h("更新")
        updateRes = TextView(this).apply { textSize = 13f; setPadding(0, dp(4f), 0, 0) }
        updateDlBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { currentManifest?.let { startUpdateDownload(it) } }
        }
        // 本开源副本没有内置更新源：公开 fork 不连接维护者的官方更新通道。
        // 想用更新功能就填自己的清单地址；留空则不自动检查、手动检查也会明确报「清单读不到」。
        field("更新清单地址（留空 = 不检查更新）",
            prefs.updateUrl,
            "留空 = 不检查更新。填你自己的 manifest JSON 地址后才启用；检查失败时先看下面那行提示里的「当前地址」") { prefs.updateUrl = it }
        col.addView(Button(this).apply { text = "检查更新"; setOnClickListener { checkUpdate(manual = true) } })
        col.addView(updateRes)
        col.addView(updateDlBtn)
        // v1.77：下载进度行（进度条 + 百分比 / 已下载体积）。不在下载时整行隐藏
        dlProgRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        dlProgBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        dlProgText = TextView(this).apply { textSize = 12.5f; setPadding(0, dp(2f), 0, 0) }
        dlProgRow.addView(dlProgBar, LinearLayout.LayoutParams(-1, dp(6f)).apply { bottomMargin = dp(2f) })
        dlProgRow.addView(dlProgText)
        col.addView(dlProgRow)
        tipD("清单是一个 JSON（版本号 / APK 直链 / 更新说明）；发现新版点「下载更新」才下载，24 小时自动静默查一次。",
            "清单是一个 JSON：版本号、APK 直链、更新说明。发现新版会在这里显示，点「下载更新」才真的开始下载，交给系统的下载管理器。下载中这里出进度条和百分比，下完自动弹安装界面（没装成功还能去通知栏点）。下载中把 App 关了也不怕，下次打开设置页接着看进度。24 小时自动查一次且全程静默（已是最新就什么都不写），随时可以手动点。")
        if (Update.shouldAutoCheck(prefs.updateLastCheck, System.currentTimeMillis(), prefs.updateUrl)) checkUpdate(manual = false)

        h("重走首次配置")
        col.addView(Button(this).apply { text = "再走一遍首次配置引导"; setOnClickListener { prefs.onboarded = false; prefs.onboardStarted = true; prefs.onboardSkipped = emptySet(); showSetup() } })
        tip("一步一页把权限、接口、保活重新过一遍（已经开好的会直接打勾）。")
        endGroup()

        // ================= 分类 6：接口与模型（接口 + 翻译方案）=================
        usePage(C_API)

        // ---- 组：接口（原「接口」页）----
        newGroup(G_API) {
            val a = prefs.api()
            if (a.baseUrl.isBlank()) "还没填接口地址"
            else a.baseUrl.removePrefix("https://").removePrefix("http://")
        }
        h("接口")
        // 当前方案状态行：说清配好没配好。
        // 注意：「填了」不等于「连得上」—— 能不能用请看下面「检测连接」，这里绝不写「已连接」。
        // KEEP：底色是主色块，字按主色选可读色（默认配色 = 墨色，零变化），别被 Brut.style 刷成墨色
        val apiStat = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.on(Brut.MINT)); setPadding(dp(12f), dp(10f), dp(16f), dp(14f)); tag = Brut.KEEP }
        col.addView(apiStat, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        apiStat.background = Brut.box(this, Brut.MINT, 3f)

        var baseEd: EditText? = null; var keyEd: EditText? = null; var modelEd: EditText? = null   // v1.65 接口方案切换时刷新输入框
        // v1.90：显式「自行填写接口」折叠 —— 一个按钮切换下面三项的可见性（不是靠整组隐藏）；
        // 本开源副本没有内置接口，缺配置时默认展开。
        val editBtn = Button(this).apply { isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        col.addView(editBtn)
        val editBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(editBox)
        val apiHost = col
        var editOpen = prefs.baseUrl.isBlank() || prefs.model.isBlank()
        fun applyEdit() {
            editBox.visibility = if (editOpen) View.VISIBLE else View.GONE
            editBtn.text = (if (editOpen) "▾ " else "▸ ") + "自行填写接口（地址 / Key / 模型）"
            editBtn.contentDescription = if (editOpen) "已展开，点按收起" else "已收起，点按展开"
        }
        editBtn.setOnClickListener { editOpen = !editOpen; applyEdit() }
        fun refreshApiStat() {
            apiStat.text = if (!prefs.hasApi()) "还没配好 —— 用自己的 AI 接口（要能看图），展开「自行填写接口」填好，再点下面「检测连接」。"
            else "现在用你自己填的接口 · 模型 " + prefs.api().model.ifBlank { "未填" } + "\n（填好只说明配置齐了，能不能用请点下面「检测连接」）"
        }
        var refreshApiStatFn: () -> Unit = { refreshApiStat() }
        // 自填区（临时把 col 指到 editBox，画完退回接口组内容那一层）
        col = editBox
        field("接口地址", prefs.baseUrl, "https://api.deepseek.com/v1") { prefs.baseUrl = it; refreshApiStat() }.also { baseEd = it }
        field("密钥", prefs.apiKey, "sk-…（英文叫 API Key）", password = true) { prefs.apiKey = it }.also { keyEd = it }
        field("模型", prefs.model, "要支持看图，比如 deepseek-vl / glm-4v / qwen-vl") { prefs.model = it; refreshApiStat() }.also { modelEd = it }
        col = apiHost
        refreshApiStat()
        applyEdit()

        // v1.65 接口方案：自己填的接口存成多套（主力 / 备用 / 便宜模型），点方案名整组切换，长按删除
        fun chip(label: String, act: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false
            setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
            setOnClickListener { act() }
        }
        val apBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, 0) }
        fun refreshAp() {
            apBar.removeAllViews()
            val list = prefs.customApis
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in list) {
                val active = p.baseUrl == prefs.baseUrl.trim() && p.model == prefs.model.trim()
                val b = chip((if (active) "✓ " else "") + p.label) {
                    ApiProfiles.apply(p, { prefs.baseUrl = it }, { prefs.apiKey = it }, { prefs.model = it })
                    baseEd?.setText(p.baseUrl); keyEd?.setText(p.apiKey); modelEd?.setText(p.model)
                    refreshApiStatFn(); refreshAp()
                }
                b.setOnLongClickListener {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("删掉方案「${p.label}」？")
                        .setPositiveButton("删") { _, _ -> prefs.deleteApiProfile(p.id); refreshAp() }
                        .setNegativeButton("先不了", null).show()
                    true
                }
                row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6f) })
            }
            row.addView(chip("＋ 存为方案") {
                val input = EditText(this).apply { hint = ApiProfiles.newName(prefs.customApis); setSingleLine() }
                android.app.AlertDialog.Builder(this)
                    .setTitle("把当前接口存为方案（地址 / Key / 模型一起存）")
                    .setView(input)
                    .setPositiveButton("存") { _, _ -> prefs.saveApiProfile(input.text.toString().trim()); refreshAp() }
                    .setNegativeButton("取消", null).show()
            }, LinearLayout.LayoutParams(-2, -2))
            apBar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            apBar.addView(TextView(this).apply {
                text = if (list.isEmpty()) "把现在填的接口存成方案，以后一点就换（长按方案可删除）"
                else "点方案名 = 地址 / Key / 模型整组换；长按 = 删除"
                textSize = 12f; setPadding(dp(2f), dp(4f), 0, 0)
            })
            if (apBar.isAttachedToWindow) Brut.style(apBar)
        }
        refreshAp()
        col.addView(apBar)
        testRes = TextView(this).apply { textSize = 13f; setPadding(0, dp(4f), 0, 0) }
        col.addView(Button(this).apply { text = "检测连接"; setOnClickListener { testConnection() } })
        col.addView(testRes)
        tip("接口要兼容 OpenAI 的格式（主流服务大多支持）。识别靠把截图发给模型，所以模型必须支持图片输入。")

        // —— 高级：Agent 记忆 / 思考强度。日常配好接口就能用，这几项按需展开 ——
        beginAdvanced()
        // [v1.86] 长期记忆：聊天页的 Agent 自己攒事实、下次聊天先翻相关的几条带上
        h("聊天长期记忆（实验）")
        col.addView(Switch(this).apply {
            text = "让 Agent 记住聊过的事"; isChecked = prefs.agentMemOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.agentMemOn = on }
        })
        tipD("打开后聊天会自己攒「值得长期记住的事」，下次聊天自动带上，它就「记得你」。",
            "打开后，聊天时模型会从这轮对话里挑出值得长期记住的事（你是谁、你的偏好、重要的约定），" +
            "存在手机本机，每个 Agent 各存各的。下次聊天会自动把相关的几条带给它，它就「记得你」了。" +
            "提炼用的是你上面填的接口，会多花一点点 token。关掉只是不再记新的，已记的还在。")
        val memStore = AgentMemory.Store(this, Personas.Store(this).current())
        col.addView(Button(this).apply {
            text = "查看 / 清空当前 Agent 的记忆（" + memStore.get().size + " 条）"
            isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener {
                val list = memStore.get()
                val msg = if (list.isEmpty()) "还没记住什么。聊几轮天、打开上面的开关，它自己会记。"
                else list.sortedByDescending { it.lastUsed }.take(20)
                    .joinToString("\n") { "· [" + it.category + "] " + it.content }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("当前 Agent 的长期记忆")
                    .setMessage(msg)
                    .setPositiveButton("清空") { d, _ -> memStore.clear(); toast("已清空"); d.dismiss() }
                    .setNegativeButton("关", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        h("思考强度")
        radios(Prompts.THINK_EFFORTS.map { it to Prompts.THINK_LABEL.getValue(it) + if (it == "high") "（默认）" else "" }, prefs.thinking) { prefs.thinking = it }
        tip("高：想得更久更准，首字更慢；关：最快。这是 DeepSeek 系列接口专有的选项，别家接口会自动忽略。")

        endAdvanced()
        endGroup()

        // ================= 分类 2：小球与识别（识别模式）=================
        usePage(C_ORB)

        // ---- 组：模式（原「模式」页：改的是「怎么问模型」）----
        newGroup(G_MODES) {
            val m = prefs.mode
            Modes.label(m, prefs.customModes) + " · " + Prompts.DETAIL_LABEL.getValue(prefs.detail)
        }
        h("模式")
        modesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modesBox)
        rebuildModes()

        h("做题解析强度")
        radios(Prompts.DETAILS.map { it to Prompts.DETAIL_LABEL.getValue(it) }, prefs.detail) { prefs.detail = it }
        tip("简略：只点关键一步；标准：分步讲、选择题说明错项；详细：不跳步、逐个分析选项，再给同类题通用解法。")
        col.addView(Switch(this).apply {
            text = "纯小白讲解"; isChecked = prefs.novice; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.novice = on }
        })
        tip("打开后用大白话讲：术语先解释、每步都写出来、尽量打比方。通用模式也生效。")

        // v1.66：原「通识」模式的名词块并进所有模式，这里一个总开关（默认开）
        col.addView(Switch(this).apply {
            text = "识别关键词"; isChecked = prefs.keywords; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.keywords = on }
        })
        tipD("所有模式回答下面多一排关键词，点哪个就在正文后面解释哪个。",
            "所有模式的回答下面多一排关键词（型号、规格参数、品牌、术语，比如 R7250、P3 广色域、Wi-Fi 7），点哪个就在正文后面解释哪个；解释是一次追问，答案接在正文后面，还能继续追问。")
        endGroup()

        // ---- 组：翻译（原「翻译」页，并进接口与模型分类：改的是「怎么翻译」）----
        usePage(C_API)
        newGroup(G_TRANSLATE) {
            "翻译成：" + Prompts.lang(prefs.lang).label + " · " + Translate.LABEL.getValue(prefs.translateEngine)
        }
        h("翻译语言")
        col.addView(Button(this).apply {
            fun label() = "翻译成：" + Prompts.lang(prefs.lang).label + (if (prefs.lang == "auto") "（中英互译）" else "") + "　点一下换"
            text = label()
            setOnClickListener { val i = Prompts.LANGS.indexOfFirst { it.id == prefs.lang }; prefs.lang = Prompts.LANGS[(i + 1) % Prompts.LANGS.size].id; text = label() }
        })
        tipD("「自动」= 中英互译；选中文字翻译有两种用法。",
            "「自动」= 中英互译。\n选中翻译两种用法：① 选中任意文字 → 弹出的系统菜单里点「" + Preset.appName + " 翻译」（菜单里没有就点「三个点」展开）；② 微信这类菜单里没有它的 App：长按 → 复制，再长按小球 → 菜单第一项「翻译刚复制的」，或者下拉通知栏点「翻译刚复制的」。")

        h("翻译引擎")
        radios(Translate.ENGINES.map { it to Translate.LABEL.getValue(it) + if (it == Translate.AI) "（默认）" else "" }, prefs.translateEngine, vertical = true) { prefs.translateEngine = it }
        tipD("「自动」：DeepL → 百度 → AI 模型依次兜底；单选某一个就只用它。",
            "「自动」：先用 DeepL；DeepL 不行（没 Key、额度用完、连不上）就用百度（填了百度凭证才用）；再不行用上面的 AI 模型。翻译卡片标题会写这次用的是哪个、前面为什么没成功。单选某一个就只用它。DeepL 和百度只给译文；AI 模型会多给一段词性 / 要点说明，但慢一些、耗 AI 额度。")
        // —— 高级：DeepL / 百度的凭据。上面引擎选「自动」时会用到；没有也能只靠 AI 模型翻译 ——
        beginAdvanced()
        h("翻译服务凭据（可选）")
        field("DeepL 密钥", prefs.deeplApiKey, "DeepL 的 API Key，形如 xxxxxxxx-…:fx", password = true) { prefs.deeplApiKey = it }
        tip("DeepL 官网（deepl.com/pro-api）注册免费用量，每月 50 万字符免费。Key（API Key）结尾是 :fx 的是免费版。只存在本机。")
        field("百度应用 ID", prefs.baiduAppId, "APP ID，形如 2026…") { prefs.baiduAppId = it }
        field("百度密钥（推荐）", prefs.baiduApiKey, "填了就用它（百度 API Key）", password = true) { prefs.baiduApiKey = it }
        field("百度签名密钥（没有上面的才用）", prefs.baiduSecret, "APP ID + 密钥，一起签名（Secret Key）", password = true) { prefs.baiduSecret = it }
        radios(BaiduTranslate.MODELS.map { it to BaiduTranslate.MODEL_LABEL.getValue(it) + if (it == "llm") "（默认）" else "" }, prefs.baiduModel) { prefs.baiduModel = it }
        tip("在百度翻译开放平台（fanyi-api.baidu.com）→ 管理控制台 → 开发者信息 里能看到 APP ID、API Key 和 Secret Key。只存在本机，不会上传到别处。机器翻译更快，大模型翻译更通顺。")
        endAdvanced()
        endGroup()

        // ================= 分类 5：外观与阅读（配色与字号 + 自定义中心）=================
        usePage(C_LOOK)

        // ---- 组：配色（原「外观」页 + 面板正文字号）----
        newGroup(G_COLOR) {
            val cur = Theme.normId(prefs.themeId)
            if (cur == Theme.CUSTOM) "自定义配色" else themeLabel(cur, Theme.PRESETS.firstOrNull { it.first == cur }?.second ?: cur)
        }
        buildTheme(col, { h(it) }, { tip(it) })
        // v1.66：面板正文字号从面板上的 A− / A+ 挪到设置页（功能不变，12~22 sp，面板开着也立刻生效）。
        // v1.90：从「面板与小球」并进「外观与阅读」—— 字号是阅读相关的外观项。
        h("面板文字大小")
        slider(12, 22, prefs.textSp, 1, { "面板正文字号：$it sp" + if (it == 15) "（默认）" else "" }) { prefs.textSp = it }
        tip("只改答案面板正文的字号；答案行、按钮跟着系统字号走。")
        endGroup()

        // ================= 分类 2 续：小球与识别（面板与小球）=================
        usePage(C_ORB)

        // ---- 组：面板与小球（原「面板 / 小球」页）----
        newGroup(G_PANEL) {
            "面板 ${prefs.panelCurWidthPct}% · 小球 ${prefs.orbSizeDp}dp"
        }
        h("解析面板：出现的位置和大小")

        // ---- 「面板与小球」组的内容接着继续写（同分类，这里不 usePage）----
        val panelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(panelBox)
        // fillPanel 重建时把 col 临时指到 panelBox，画完退回组内容那一层
        val panelPage = col
        var orbSizeRefresh: (() -> Unit)? = null
        lateinit var fillPanel: () -> Unit
        fillPanel = {
            panelBox.removeAllViews()
            col = panelBox
            radios(PanelLayout.POSITIONS.map { it to PanelLayout.LABEL.getValue(it) + when (it) {
                PanelLayout.BOTTOM -> "：从下往上出来"
                PanelLayout.TOP -> "：从上往下出来"
                PanelLayout.LEFT -> "（默认）从左边滑出来"
                PanelLayout.RIGHT -> "：从右边滑出来"
                PanelLayout.ORB -> "：在小球旁边展开"
                PanelLayout.AWAY -> "：小球在上半屏就放底部，在下半屏就放顶部"
                else -> "：按住面板横条拖到哪就在哪"
            } }, prefs.panelPos, vertical = true) { prefs.panelPos = it; panelBox.post { fillPanel() } }
            tip("面板开着时改，会立刻挪过去。按住面板的横条半秒（手机震一下）再拖，就是「自由位置」。")
            val posNow = prefs.panelPos
            val widthWhat = when (posNow) { PanelLayout.TOP, PanelLayout.BOTTOM, PanelLayout.AWAY -> "顶部 / 底部"; PanelLayout.FREE -> "自由位置"; else -> "左右 / 跟着小球" }
            slider(40, 100, prefs.panelCurWidthPct, 5, { "面板宽度（$widthWhat）：$it%" + if (it == 100) "（满宽）" else "" }) { prefs.panelCurWidthPct = it }
            if (posNow == PanelLayout.TOP || posNow == PanelLayout.BOTTOM || posNow == PanelLayout.AWAY) {
                col.addView(TextView(this).apply { text = "不满宽时靠哪边"; textSize = 13f; setPadding(0, dp(8f), 0, 0) })
                radios(PanelLayout.ALIGNS.map { it to PanelLayout.ALIGN_LABEL.getValue(it) }, prefs.panelAlign) { prefs.panelAlign = it }
            }
            // —— 高级：离边距离 / 两档正文最高。位置和宽度在上面（基础），这些按需展开 ——
            beginAdvanced()
            if (posNow != PanelLayout.FREE && posNow != PanelLayout.ORB)
                slider(0, 200, prefs.panelOffsetDp, 4, { "离屏幕边：" + if (it == 0) "紧贴（默认）" else "再远 $it dp" }) { prefs.panelOffsetDp = it }
            slider(PanelLayout.HALF_MIN, PanelLayout.HALF_MAX, prefs.panelHalfPct, 2, { "半屏档正文最高：屏幕的 $it%" + if (it == 42) "（默认）" else "" }) { prefs.panelHalfPct = it }
            slider(PanelLayout.FULL_MIN, PanelLayout.FULL_MAX, prefs.panelFullPct, 5, { "大屏档正文最高：屏幕的 $it%" + if (it == 80) "（默认）" else "" }) { prefs.panelFullPct = it }
            tip("面板有半屏 / 大屏两档：点横条在 半屏 ↔ 大屏 之间切换，往外拖变高、往里拖变矮。大屏档至少比半屏档高 10 个百分点。")
            endAdvanced()
            col.addView(Button(this).apply {
                text = "面板和小球恢复默认（位置、大小）"
                setOnClickListener { prefs.resetPanelLayout(); panelBox.post { fillPanel(); orbSizeRefresh?.invoke() } }
            })
            col = panelPage
            if (panelBox.isAttachedToWindow) Brut.style(panelBox)
        }
        fillPanel()

        h("小球位置和大小")
        val orbBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(orbBox)
        orbSizeRefresh = {
            orbBox.removeAllViews()
            col = orbBox
            slider(40, 84, prefs.orbSizeDp, 2, { "小球大小：$it dp" + if (it == 58) "（默认）" else "" }) { prefs.orbSizeDp = it }
            col = panelPage
            if (orbBox.isAttachedToWindow) Brut.style(orbBox)
        }
        orbSizeRefresh?.invoke()
        tip("拖动时小球立刻变大变小。小一点不挡字，大一点好按。")
        // —— 高级：贴边 / 闲置缩进 / 闲置透明度。默认就好用，按需展开 ——
        beginAdvanced()
        col.addView(Switch(this).apply {
            text = "离屏幕边远一点（躲开返回手势）"; isChecked = prefs.orbFar; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.orbFar = on }
        })
        tip("关（默认）：小球贴近屏幕边（只留一点缝），按小球不会被当成返回手势。开：让出整条返回手势区，离边远一些。")
        col.addView(Switch(this).apply {
            text = "闲置时缩进屏幕边一半"; isChecked = prefs.orbTuck; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.orbTuck = on }
        })
        tip("闲置时小球往屏幕边缘缩进一半，减少遮挡。")
        val idleLabel = TextView(this).apply { textSize = 15f; setPadding(0, dp(10f), 0, 0) }
        fun idleText(v: Int) = "闲置时不透明度：$v%（越小越透明）"
        idleLabel.text = idleText(prefs.orbIdleAlpha)
        col.addView(idleLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 70; progress = prefs.orbIdleAlpha - 10
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { prefs.orbIdleAlpha = 10 + p; idleLabel.text = idleText(10 + p) } }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })
        tip("3 秒不碰，小球缩小到 6 成并变淡（开着上面的开关还会藏进屏幕边一半）；一碰就恢复原样。")
        endAdvanced()
        endGroup()

        // ---- 组：自动扫描（原 data 组里混着的扫描项，拆到这里：它是「怎么识别」的一部分）----
        newGroup(G_SCAN) {
            "间隔 " + prefs.intervalSec + "s" + (if (prefs.scanPauseOnStill) " · 画面不变自动暂停" else "")
        }
        h("自动扫描（隔一段时间自己识别一次）")
        tipD("长按小球 → 菜单里可开自动扫描，也能用 − / + 调间隔（5~3600 秒）。",
            "长按小球 → 菜单里也能开自动扫描、能用 − / + 调间隔。自动扫描时每一轮先看画面有没有变：变了才调接口，一模一样就直接跳过、连缓存都不查；没变够多次会自动停下来（下面两项）。")
        h("自动扫描间隔（秒）")
        col.addView(EditText(this).apply {
            setText(prefs.intervalSec.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { prefs.intervalSec = it } }
            })
        })
        tip("5 ~ 3600 秒。")
        // v1.70 自动扫描省钱：忘了关 / 题目停在一页不动时，别整晚空烧
        col.addView(Switch(this).apply {
            text = "画面一直不变时自动暂停"; isChecked = prefs.scanPauseOnStill; textSize = 15f; setPadding(0, dp(12f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.scanPauseOnStill = on }
        })
        col.addView(TextView(this).apply { text = "连续多少轮没变化就暂停"; textSize = 15f; setPadding(0, dp(2f), 0, dp(2f)) })
        col.addView(EditText(this).apply {
            setText(prefs.scanStillRounds.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { prefs.scanStillRounds = it } }
            })
        })
        tipD("连续多少轮画面一模一样就自动暂停（${AutoScanPolicy.STILL_ROUNDS_MIN}~${AutoScanPolicy.STILL_ROUNDS_MAX}，默认 ${AutoScanPolicy.DEFAULT_STILL_ROUNDS}）。",
            "连续多少轮画面一模一样就自动暂停（${AutoScanPolicy.STILL_ROUNDS_MIN}~${AutoScanPolicy.STILL_ROUNDS_MAX}，默认 ${AutoScanPolicy.DEFAULT_STILL_ROUNDS}）。暂停时面板上那行字可以点，也可以下拉通知点一下继续，双击小球也算继续。屏幕一熄就停、解锁自动继续 —— 息屏了还扫就是纯烧钱。\n另外自动扫描开着时，面板状态行会写「自动扫描 · 12s 后」，下一轮什么时候来一眼看得见；扫描中的文案不变。")
        endGroup()

        // ================= 分类 5 续：外观与阅读（自定义中心）=================
        usePage(C_LOOK)

        // ---- 组：自定义中心（原「其他」页的自定义入口）----
        newGroup(G_CUSTOM) { "称呼、语气、口头禅等 ${Customize.CATALOG.size} 项" }
        h("自定义")
        col.addView(Button(this).apply {
            text = "自定义中心"
            setOnClickListener { try { startActivity(CustomizeActivity.intent(this@MainActivity)) } catch (e: Exception) { toast("打不开自定义中心") } }
        })
        tipD("称呼、语气、口头禅、字号、快捷短语……全在「自定义中心」里改，改坏了能逐项恢复。",
            "称呼、语气、口头禅、小球大小、面板字号、快捷短语……全都在这儿改。每一项都有默认值，不改也能用；改坏了在那一项的编辑页底部点「恢复这一项」。多数项也能从小球菜单或面板上长按直接进。")
        endGroup()

        // ================= 分类 3：背单词 =================
        usePage(C_WORDS)

        // ---- 组：弹词设置（原「背单词」页上半：开关 + 间隔 + 夜里不弹）----
        newGroup(G_WORDS_POP) {
            (if (prefs.wordOn) "每 ${Prefs.fmtInterval(prefs.wordEverySec)}弹一次" else "已关") + (if (prefs.wordQuiet) " · 夜里不弹" else "")
        }
        h("随机弹词（背单词 / 自定义词库）")
        col.addView(Switch(this).apply {
            text = "隔一段时间弹一张卡片"; isChecked = prefs.wordOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.wordOn = on }
        })
        h("弹词间隔（秒）")
        col.addView(EditText(this).apply {
            setText(prefs.wordEverySec.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { prefs.wordEverySec = it } }
            })
        })
        tip("5 ~ 86400 秒（默认 900 = 15 分钟）。想 15 秒来一张就填 15；填小了下场很快。")
        col.addView(Switch(this).apply {
            text = "夜里不弹（23:00 ~ 7:00）"; isChecked = prefs.wordQuiet; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.wordQuiet = on }
        })
        // —— 高级：卡片停留时长 / 弹出时朗读。默认值够用，按需展开 ——
        beginAdvanced()
        h("卡片自动收起（秒）")
        col.addView(EditText(this).apply {
            setText(prefs.wordCardStaySec.toString()); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { prefs.wordCardStaySec = it } }
            })
        })
        tip("3 ~ 600 秒（默认 20）。卡片弹出来后没人碰，就按这个秒数自己收回去；点「不认识」之后那一下是固定的短停顿，不受这里影响。")
        col.addView(Switch(this).apply {
            text = "英文词弹出时自动读一遍"; isChecked = prefs.wordSpeak; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.wordSpeak = on }
        })
        tip("朗读用手机自带的语音合成（TTS），英文词弹出时读一遍；没有可用的语音包就不出声。")
        endAdvanced()
        tipD("卡片在屏幕上方：先看正面，点一下展开释义，再点「✓ 认识 / ✗ 不认识」。",
            "卡片在屏幕上方，先只显示正面，点一下展开释义，再点「✓ 认识 / ✗ 不认识」。不认识的以后出现得更勤，连续认识 ${Words.MASTER_STREAK} 次算掌握、很少再出。锁屏、打游戏隐藏小球、正在识别、面板开着时不弹；没碰它就按上面设的秒数自己收回去。长按小球 →「来个单词」随时来一张。")
        endGroup()

        // ---- 组：词库（原「背单词」页下半的词库区块）----
        newGroup(G_WORDS_DECK) {
            val decks = prefs.deckList()
            "共 ${decks.size} 个 · 已勾 ${decks.count { it.id in prefs.wordDecksOn }} 个"
        }
        // 词库区块整块的填充：重建时把 col 临时指到 deckBox，画完退回组内容那一层
        val wordsPage = col
        val deckBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(deckBox)
        lateinit var fillDecks: () -> Unit
        // v1.76.5：默认态只渲染一行摘要（名字/条数/已掌握），点了才展开编辑区。
        // 内置 5 级词库导入后每级近 2000 行，全塞进 EditText 会让这一页测量+布局卡到像死机。
        // 展开状态放在 fillDecks 外面，保存/删除触发重建后仍保持；同一时间允许多个展开。
        val expandedDecks = Words.Expanded()
        fillDecks = {
            deckBox.removeAllViews()
            col = deckBox
            val st = prefs.wordStats
            val t = Words.todayOf(prefs.wordToday, prefs.todayInt())
            col.addView(TextView(this).apply { text = "今天背了 ${t.done} 个 · 认识 ${t.known} 个"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8f), 0, 0) })
            col.addView(TextView(this).apply { text = "用哪些词库（可多选）；自定义词库点一行展开编辑"; textSize = 13f; setPadding(0, dp(8f), 0, 0) })
            val decks = prefs.deckList()
            val summaries = Words.summaries(decks, st)
            // 内置英语词库与 5 级内置词库都不展开（editableDecks 就是这个口径）；点开的编辑区照旧只认自定义词库
            val custom = Words.editableDecks(decks)
            // 词库被删过就清掉它的展开态，免得 id 复用时莫名展开着一段旧正文
            expandedDecks.retainOnly(custom.map { it.id }.toSet())
            for (s in summaries) {
                val id = s.id
                // 内置英语词库与 5 级内置词库没有可编辑的全文（正文在代码 / assets 里），只有自定义词库能展开
                val deck = custom.firstOrNull { it.id == id }
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(android.widget.CheckBox(this).apply {
                    text = "${s.name} · ${s.total} 条 · 已掌握 ${s.mastered}"; isChecked = id in prefs.wordDecksOn; textSize = 15f
                    setOnCheckedChangeListener { _, on ->
                        prefs.wordDecksOn = Words.toggleDeck(prefs.wordDecksOn, id, on)
                        // v1.77：勾上内置分级（一次就是两千词）立刻后台预翻译，不等下一次弹卡
                        if (on && WordBank.isLevelDeck(id)) prefetchImportedWords()
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (deck != null) {
                    // 点摘要行展开/收起；勾选框会吃掉自己的点击，所以点勾选框不会误展开
                    head.addView(TextView(this).apply { text = if (expandedDecks.isOpen(id)) "收起 ▾" else "编辑 ▸"; textSize = 13f; alpha = 0.7f })
                    head.setOnClickListener { expandedDecks.toggled(id); fillDecks() }
                }
                row.addView(head)
                if (deck != null && expandedDecks.isOpen(id)) {
                    // 到这里才建输入框：默认态下这些全文控件一个都不存在
                    row.addView(TextView(this).apply { text = "词库「${deck.name}」"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12f), 0, 0) })
                    val nameEt = EditText(this).apply { setText(deck.name); setSingleLine(); hint = "词库名字" }
                    val textEt = EditText(this).apply {
                        setText(deck.text); minLines = 4; maxLines = 12; gravity = Gravity.TOP
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        hint = "每行一条，比如：\nserendipity | n. 意外发现珍宝的运气\n床前明月光：疑是地上霜\nsin²x + cos²x | 1"
                    }
                    row.addView(nameEt); row.addView(textEt)
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    r.addView(Button(this).apply { text = "保存"; setOnClickListener {
                        val txt = textEt.text.toString()
                        prefs.customDecks = Words.saveDeck(prefs.customDecks, id, nameEt.text.toString(), txt)
                        toast("已保存：" + Words.parse(txt).size + " 条"); deckBox.post { fillDecks() }
                        prefetchImportedWords()   // v1.76.6：改完词库立刻后台预翻译，不等下一次弹卡
                    } }, LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(Button(this).apply { text = "删除"; setOnClickListener {
                        expandedDecks.closed(id)
                        val (rest, on) = Words.deleteDeck(prefs.customDecks, prefs.wordDecksOn, id)
                        prefs.customDecks = rest; prefs.wordDecksOn = on; deckBox.post { fillDecks() }
                    } }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(r)
                }
                col.addView(row)
            }
            // 自动词库：各词库里点过「不认识」的，勾上就专门复习它们
            col.addView(android.widget.CheckBox(this).apply {
                text = "不认识的词（自动错词本）· ${prefs.mistakeCount()} 条"; isChecked = Words.MISTAKES in prefs.wordDecksOn; textSize = 15f
                setOnCheckedChangeListener { _, on -> prefs.wordDecksOn = if (on) prefs.wordDecksOn + Words.MISTAKES else prefs.wordDecksOn - Words.MISTAKES }
            })
            // v1.63：手机上一行行打太累 —— 在别处复制一整段（每行一条），这里一键变成新词库
            col.addView(Button(this).apply { text = "从剪贴板导入为新词库"; setOnClickListener {
                val clip = try { getSystemService(android.content.ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: "" } catch (_: Exception) { "" }
                val cards = Words.parse(clip)
                if (cards.isEmpty()) { toast("剪贴板里没有能用的内容：先复制一段文字（每行一条），再点这里"); return@setOnClickListener }
                val list = prefs.customDecks
                var n = list.size + 1; while (list.any { it.id == "d$n" }) n++
                val name = "导入 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                prefs.customDecks = list + Words.Deck("d$n", name, clip.trim())
                prefs.wordDecksOn = prefs.wordDecksOn + "d$n"
                toast("已导入「$name」：${cards.size} 条（已勾选）")
                deckBox.post { fillDecks() }
                prefetchImportedWords()   // v1.76.6：导入完立刻后台预翻译
            } })
            // v1.77：内置分级不再需要导入，上面的摘要行直接勾选就能用
            tip("内置词库（初中到雅思五级）直接在上面勾选就能用，不用导入；想改它的内容，把词表复制一份到下面的自定义词库里改。")
            tip(WordBank.LICENSE_NOTE + "；来源与授权见应用内置 assets/wordbank/LICENSE.txt")
            col.addView(Button(this).apply { text = "＋ 新建词库"; setOnClickListener {
                val list = prefs.customDecks
                var n = list.size + 1; while (list.any { it.id == "d$n" }) n++
                prefs.customDecks = list + Words.Deck("d$n", "我的词库 $n", "")
                prefs.wordDecksOn = prefs.wordDecksOn + "d$n"
                // 新建的是空词库，直接展开让他接着打字（剪贴板导入不展开：正文可能很长）
                expandedDecks.opened("d$n")
                deckBox.post { fillDecks() }
            } })
            tip("格式：每行一条，正面和背面用「 | 」「：」「Tab」或「 - 」隔开；「abandon v. 放弃」这种英文加中文释义也能自动拆开；只写正面也行（卡片上点「查」翻译）。# 开头的行当注释。能放单词、古诗上下句、公式、知识点……")
            col.addView(Button(this).apply { text = "重置背词进度（全部当新词）"; setOnClickListener { prefs.wordStats = emptyMap(); toast("进度已清空"); deckBox.post { fillDecks() } } })
            col = wordsPage
            if (deckBox.isAttachedToWindow) Brut.style(deckBox)
        }
        fillDecks()
        endGroup()

        // ================= 分类 4：提醒与免打扰 =================
        usePage(C_REMIND)

        // ---- 组：提醒与知识卡（原「提醒」页整页）----
        newGroup(G_REMIND) {
            val on = listOf("早简报" to prefs.briefMorningOn, "晚简报" to prefs.briefNightOn, "知识卡复习" to prefs.reviewNotiOn)
                .filter { it.second }.map { it.first }
            if (on.isEmpty()) "都关了" else on.joinToString("、")
        }

        // —— v1.85 精确提醒授权：32+ 系统不授权的话闹钟会被 Doze 合并，提醒会晚几分钟到几十分钟 ——
        val exactOk = RemindExact.canExact(this)
        col.addView(Button(this).apply {
            text = "精确提醒：" + RemindExact.label(exactOk) + if (exactOk) "" else "　点这里去授权"
            tag = if (exactOk) null else Brut.DANGER
            setOnClickListener {
                if (RemindExact.canExact(this@MainActivity)) return@setOnClickListener
                try { startActivity(RemindExact.settingsIntent(this@MainActivity)) } catch (_: Exception) {}
            }
        })
        tipD("精确提醒：不授权也能用，只是到点可能被系统攒着一起发、晚几十分钟。",
            "不授权也能用，只是到点会被系统攒着一起发（待机时可能拖到几十分钟后）。授权后待办/简报/复习都会准点。")

        // —— v1.85.4 测一条提醒：让「到点弹卡 + 震动」这条链能当场实测，不用真等到点 ——
        col.addView(Button(this).apply {
            text = "测一条提醒（10 秒后到点）"
            setOnClickListener {
                val id = Todo.Store(this@MainActivity).add("测试提醒（链路实测）", System.currentTimeMillis() + 10_000)
                if (id > 0) {
                    TodoReceiver.schedule(this@MainActivity, id)
                    toast("已排：10 秒后到点")
                } else toast("排不了 —— 待办库写入失败")
            }
        })
        tipD("测完整链路：到点弹提醒小卡 + 按震动强度震，当场试，不用等到点。",
            "测完整链路：到点时球在跑且有悬浮窗权限、弹出纪律放行 → 小球旁弹提醒小卡 + 按震动强度震（样式在「自定义中心」调）；" +
            "球没开 / 纪律拦了 → 退成常规通知。「主动提醒（弹出纪律）」开关关掉 = 永远直接弹。")

        // —— v1.86 震动强度：全 App 唯一一处（菜单/面板弹出、划词过临界、提醒到点都读它）——
        col.addView(choiceRow("震动强度", Vibrate.LEVELS.map { Vibrate.label(it) }, Vibrate.LEVELS.indexOf(prefs.vibrateLevel)) { which ->
            prefs.vibrateLevel = Vibrate.LEVELS[which]
            Vibrate.remind(this@MainActivity, prefs.vibrateLevel)   // 立刻按选中档震一下 —— 不用猜「中是多重」
        })
        tip("现在：${Vibrate.describe(prefs.vibrateLevel)}。菜单弹出、划词判定也用它；某一条记期可以单独另设强度。")

        // —— v1.86 记期列表：能重复的提醒（日历 / 每周几 + 次数 + 强度）——
        col.addView(Button(this).apply {
            text = "记期列表（日历 / 每周几）"
            setOnClickListener { try { startActivity(ScheduleActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开记期页") } }
        })
        tip("点日历里的某一天，或选好周几——它就每周/每天都提醒你。每条能单独设提醒次数（到点后隔几分钟再补几次）和震动强度。")

        // —— 弹出纪律 ——
        col.addView(Switch(this).apply {
            text = "主动提醒（弹出纪律）"; isChecked = prefs.popupOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.popupOn = on }
        })
        tipD("待办 / 提醒按纪律弹：每天限次、每条带「稍后」，连续忽略自动降频。",
            "待办、提醒这类事件按纪律弹：每天最多 ${prefs.popupDailyCap} 次、每类再各设软上限；每条都带「稍后」和「这类别再弹」；连续忽略会自动降频。关掉后事件照常记录，只是不弹。")
        // —— v1.80 试弹：到点时先轻震两下 + 在球边上浮一张小卡试探一下，没人理才退成通知 ——
        col.addView(Button(this).apply {
            text = "试弹一条（先看看提醒长什么样）"
            setOnClickListener {
                // 用一条真待办来试：没接住 6 秒后会退成常规通知 —— 和真实到点走的是同一条链
                val id = Todo.Store(this@MainActivity).add("试弹：这是一条样例待办，点「完成」或「稍后」都行", System.currentTimeMillis())
                if (id <= 0L) { toast("建不了样例待办"); return@setOnClickListener }
                val t = Todo.Store(this@MainActivity).get(id)
                if (t == null || !OrbService.tryPopTodo(this@MainActivity, t)) {
                    toast("小球没在跑或没授权 —— 去「开始使用」分类把悬浮球开起来再试")
                    Todo.Store(this@MainActivity).remove(id)
                }
            }
        })
        tipD("试弹：提醒到点先轻震两下、球边浮张小卡；6 秒没人碰就收回去并补通知（不漏）。",
            "试弹 = 提醒到点时先轻震两下、在球边上浮出一张小卡；6 秒没人碰就自己收回去并补一条通知（提醒不会漏）。点「完成」销掉这条待办，点「稍后」30 分钟后再来。")

        // —— 免打扰时段（起止都可点开时间选择器）——
        col.addView(Switch(this).apply {
            text = "免打扰时段"; isChecked = prefs.popupQuietOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.popupQuietOn = on }
        })
        val quietStartRow = timeRow("免打扰开始", prefs.popupQuietStart) { min -> prefs.popupQuietStart = min }
        val quietEndRow = timeRow("免打扰结束", prefs.popupQuietEnd) { min -> prefs.popupQuietEnd = min }
        col.addView(quietStartRow); col.addView(quietEndRow)
        tip("免打扰期间事件排队不弹，时段结束合并成一条摘要给你。")

        // —— 待办入口（基础：最常用，放外面）——
        col.addView(Button(this).apply {
            text = "待办与随手记"
            setOnClickListener { try { startActivity(TodoListActivity.intent(this@MainActivity)) } catch (e: Exception) { toast("打不开待办") } }
        })

        // —— 高级：统一弹出时刻 / 早晚简报 / 复习与导入。日常不改这些也能用，按需展开 ——
        beginAdvanced()
        // —— 统一弹出时刻：一个时间管住所有主动弹出 ——
        val unifiedRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var refreshPopupTimeUi: () -> Unit = {}
        val unifiedSwitch = Switch(this).apply {
            text = "统一弹出时刻"; isChecked = prefs.popupUnifiedOn; textSize = 15f; setPadding(0, dp(12f), 0, dp(4f))
            setOnCheckedChangeListener { _, on ->
                prefs.popupUnifiedOn = on
                refreshPopupTimeUi()
                rescheduleAllPopups()
            }
        }
        val unifiedTimeRow = timeRow("弹出时刻", prefs.popupUnifiedMin) { min -> prefs.popupUnifiedMin = min; rescheduleAllPopups() }
        val separateRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val morningRow = timeRow("早简报时刻", prefs.briefMorningMin) { min -> prefs.briefMorningMin = min; rescheduleAllPopups() }
        val nightRow = timeRow("晚简报时刻", prefs.briefNightMin) { min -> prefs.briefNightMin = min; rescheduleAllPopups() }
        val reviewRow = timeRow("复习提醒时刻", prefs.reviewNotiMin) { min -> prefs.reviewNotiMin = min; rescheduleAllPopups() }
        separateRows.addView(morningRow); separateRows.addView(nightRow); separateRows.addView(reviewRow)
        unifiedRow.addView(unifiedSwitch); unifiedRow.addView(unifiedTimeRow); unifiedRow.addView(separateRows)
        col.addView(unifiedRow)
        // 按当前开关状态刷新三行的可见/可点（用 lambda 变量，避免局部函数前向引用）
        refreshPopupTimeUi = {
            val on = prefs.popupUnifiedOn
            unifiedTimeRow.visibility = if (on) View.VISIBLE else View.GONE
            separateRows.visibility = if (on) View.GONE else View.VISIBLE
        }
        refreshPopupTimeUi()
        tipD("打开「统一弹出时刻」= 早 / 晚简报、复习提醒都在这一个点弹，不用记三处。",
            "打开「统一弹出时刻」= 早简报、晚简报、复习提醒全都在你设的这一个点弹，不用记三处。关掉则各自单独设。免打扰时段独立生效：落在里面的一律不弹。")

        // —— 待办 / 简报 / 复习入口（待办入口已挪到上面的基础区）——
        col.addView(Switch(this).apply {
            text = "早简报"; isChecked = prefs.briefMorningOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.briefMorningOn = on; rescheduleAllPopups() }
        })
        col.addView(Switch(this).apply {
            text = "晚简报"; isChecked = prefs.briefNightOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.briefNightOn = on; rescheduleAllPopups() }
        })
        tip("简报一天两条：早上说今天有什么事，晚上收个尾。内容在本地拼，不调接口不花钱。")

        col.addView(Button(this).apply {
            text = "学习与复习 · 自定义计划"
            setOnClickListener { startActivity(Intent(this@MainActivity, StudyActivity::class.java)) }
        })
        col.addView(Switch(this).apply {
            text = "知识卡到期提醒"; isChecked = prefs.reviewNotiOn; textSize = 15f; setPadding(0, dp(8f), 0, dp(4f))
            setOnCheckedChangeListener { _, on -> prefs.reviewNotiOn = on; rescheduleAllPopups() }
        })
        tip("到期的知识卡每天提醒一次（点通知直接开始复习），没有到期卡就不发。评完自动排下次间隔。")

        // —— 知识卡导入（AI 生成）+ Anki 导入 + 知识卡库 ——
        col.addView(Button(this).apply {
            text = "导入知识卡（AI 生成）"; tag = Brut.PRIMARY
            setOnClickListener { openKnowledgeImport() }
        })
        col.addView(Button(this).apply {
            text = "导入 Anki 牌组（.apkg）"; setOnClickListener { openAnkiImport() }
        })
        col.addView(Button(this).apply {
            text = "知识卡库"; setOnClickListener { try { startActivity(Intent(this@MainActivity, KnowledgeListActivity::class.java)) } catch (_: Exception) { toast("打不开知识卡库") } }
        })
        tipD("文本 / Word / PDF：先选文件或粘贴原文，预览后再调 AI 生成卡片，逐张核对后确认导入。",
            "「导入知识卡（AI 生成）」支持 txt / md / doc / docx / PDF：先选文件或粘贴原文，预览无误后再点按钮调用当前接口生成问答卡；每张卡可编辑 / 勾选 / 删除，摘录只读用来核对来源，确认后才批量入库。原文只在点生成时才发送，接口 Key 不外显、不写日志。\n「导入 Anki 牌组（.apkg）」沿用原来的 Anki 解包导入。重复导入同一张卡会自动跳过。")
        endAdvanced()
        endGroup()

        // 所有组都建完了：按显示顺序（首页 + 7 个分类）把列装进 SwipePager。
        // v1.90：默认展开态一律为空（不再自动摊开词库 / 提醒）；锁着的「上手与授权」由 render() 强制展开。
        pageNames.clear(); pageScrolls.clear()
        for (name in listOf(HUB) + CATEGORIES) {
            val c = catCols[name] ?: continue
            val sv = ScrollView(this).apply { addView(c); isVerticalScrollBarEnabled = false }
            pager.addPage(sv)
            pageNames.add(name); pageScrolls.add(sv)
        }
        if (prefs.openGroups == null) prefs.openGroups = Sections.defaults(GROUPS)
        groupBoxes.values.forEach { it.render() }
        hubRows.forEach { it() }

        building = false
        return root
    }

    // ---------------- 外观配色（v1.55；v1.66 色系分页：左右滑动换色系，点一张卡片就换） ----------------
    private lateinit var previewBox: LinearLayout
    private lateinit var contrastLine: TextView
    private val colorRows = ArrayList<() -> Unit>()   // 各行「当前值」文字的刷新函数
    private val themeRows = ArrayList<() -> Unit>()   // 配色卡片 / 自定义那一行的 ✓ 刷新

    /**
     * 画廊短标签「中文 English」：默认 + 六套青春配色。其余（老 id / 自定义）落回预设自带名字 ——
     * 这样无论核心侧 PRESETS 里写多长，画廊这一排始终是短名。
     */
    private fun themeLabel(id: String, fallback: String): String = when (Theme.normId(id)) {
        Theme.DEFAULT -> "护眼 Eye（默认）"
        "candy_pop" -> "糖果 Candy"
        "cyber_pop" -> "赛博 Cyber"
        "fresh_pop" -> "清新 Fresh"
        "dopamine_pop" -> "多巴胺 Dopamine"
        "acid_green" -> "酸性 Acid"
        "soft_digital" -> "柔光 Soft"
        else -> fallback
    }

    private fun pickTheme(id: String) {
        val before = prefs.themeId
        if (id != before) {
            if (id == Theme.CUSTOM) prefs.setThemeColor("bg", prefs.baseTheme().bg)   // 从当前预设抄一份开始改
            else prefs.themeId = id
        }
        // 立刻让设置页也用上新配色（设置页可能比悬浮球先开）：先把色板装进 Ui.theme，再整棵重建。
        Ui.theme = prefs.theme()
        if (inSetup || id == before) { refreshTheme(); return }   // 点的是当前这套：只刷摘要，不必重建
        rebuildSettingsPreservingState()
    }

    /**
     * 换配色后把设置页整棵重建：默认皮肤的颜色在控件建好那一刻就写死了（KEEP 子树 / 自绘底等只认创建时的值），
     * 只重刷一层 Brut.style() 覆盖不到。重建保留：当前分类页、滚动位置、折叠组展开态
     * （展开态本就存在 Prefs.openGroups，build() 结尾 render() 会照它还原）、正在编辑的输入框焦点。
     *
     * 只在用户点选配色时调用 —— 初次 build() 里由 buildTheme() 直接刷新摘要，不会走这里；
     * [building] 再兜一道，杜绝 build → pickTheme → build 的递归。
     */
    private fun rebuildSettingsPreservingState() {
        if (building || inSetup) return
        val pageIdx = if (::pager.isInitialized) pager.current else 0
        val scrollY = pageScrolls.getOrNull(pageIdx)?.scrollY ?: 0
        val focusHint = focusedEditHint()
        keepRows.clear()
        val root = build()
        setContentView(root)
        Brut.page(this, root)
        Brut.style(root)
        // 换配色走的是整棵重建，不经过 onResume；而 build() 结尾只补了 hubRows / 折叠组 render，
        // 依赖运行时状态写的按钮/文案（keepRow 按钮、startBtn / permLine、缓存行、历史条数）不会自动刷新，
        // 重建后就成空白。这里按「首次显示」onResume 同一套最小状态初始化补齐（refreshState 内含 keepRows）。
        // 全是纯读/写文案，不触发重建，无递归；scroll / focus / 展开态已在上面按原样恢复，不受影响。
        refreshState()
        refreshCacheLine()
        if (::historyBtn.isInitialized) historyBtn.text = "查看历史记录（" + History.Store(this).list().size + " 条）"
        if (pageIdx in pageNames.indices) pager.goTo(pageIdx, animate = false)
        pageScrolls.getOrNull(pageIdx)?.let { scroll -> scroll.post { scroll.scrollTo(0, scrollY) } }
        refocusByHint(root, focusHint)
    }

    /** 正在编辑的输入框（重建后控件是新的，靠 hint 找回同一个） */
    private fun focusedEditHint(): String? =
        (currentFocus as? android.widget.EditText)?.hint?.toString()?.takeIf { it.isNotBlank() }

    private fun refocusByHint(root: View, hint: String?) {
        if (hint == null) return
        val stack = ArrayList<View>(); stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeAt(stack.size - 1)
            if (v is android.widget.EditText && v.hint?.toString() == hint) { v.requestFocus(); v.setSelection(v.text.length); return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
    }

    /** 一张配色卡：左边一块渐变 / 纯色样张（就是面板底 + 正文色写的「字」），右边名字；选中的前面 ✓ + 加粗 */
    private fun themeCard(id: String, label: String, t: Theme): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true; setPadding(dp(8f), dp(6f), dp(10f), dp(6f)) }
        val sw = TextView(this).apply {
            text = "字"; gravity = Gravity.CENTER; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.tx1)
            background = Ui.panelBg(this@MainActivity, t.copy(opacity = 100), 8f).apply { setStroke(dp(1.5f), Brut.INK) }
            tag = Brut.KEEP
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(44f), dp(40f)).apply { marginEnd = dp(10f) })
        val orb = OrbView(this).apply { themeOverride = t }
        // tag = KEEP：这张卡的底色在选中时会切成主色，字色要跟着填色走，别被 Brut.style 一刀切成墨色
        val tv = TextView(this).apply { textSize = 14f; setTextColor(Brut.on(Brut.CARD)); tag = Brut.KEEP }
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(orb, LinearLayout.LayoutParams(dp(30f), dp(30f)))
        row.setOnClickListener { pickTheme(id) }
        themeRows.add {
            val on = prefs.themeId == id
            val fill = if (on) Brut.MINT else Brut.CARD
            tv.text = (if (on) "✓ " else "") + label
            tv.setTextColor(Brut.on(fill))
            tv.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            row.background = Brut.box(this, fill, if (on) 3f else 2f)
            row.contentDescription = label + if (on) "，正在用" else "，点按换成这套配色"
        }
        return row
    }

    private fun buildTheme(col: LinearLayout, h: (String) -> Unit, tip: (String) -> Unit) {
        themeRows.clear(); colorRows.clear()
        h("外观配色")
        tip("默认「护眼」；下面六套是新的青春配色，点一张就换 —— 小球和开着的面板立刻跟着变。都不合意就点「自定义」逐项调。")
        // v2.0.4：画廊 = 默认 + 六套新配色（都在 Theme.HOT_IDS 里，一个竖排列表放得下）。
        // 旧配色不再出现在画廊里，但老用户存着的旧 id 仍按迁移表照常解析（Theme.normId），不会失效。
        for (id in Theme.HOT_IDS) {
            val p = Theme.PRESETS.firstOrNull { it.first == id } ?: continue
            col.addView(themeCard(id, themeLabel(id, p.second), p.third), LinearLayout.LayoutParams(-1, dp(52f)).apply { bottomMargin = dp(6f) })
        }
        // 自定义（用下面各项的颜色）
        val customRow = Button(this).apply { isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { pickTheme(Theme.CUSTOM) } }
        col.addView(customRow)
        themeRows.add { customRow.text = (if (prefs.themeId == Theme.CUSTOM) "✓ " else "") + "自定义（用下面各项的颜色）" }

        // 预览：迷你面板 + 小球，下面一行写对比度数字（不用看颜色也知道清不清楚）
        previewBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(12f); setPadding(p, dp(8f), p, p); tag = Brut.KEEP }   // 预览要显示面板真实配色，不刷皮肤
        col.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        contrastLine = TextView(this).apply { textSize = 13f; setPadding(0, dp(6f), 0, dp(2f)) }
        col.addView(contrastLine)

        // —— 高级：自定义逐项颜色。只有点上面「自定义」才用得到，默认收起、不存档 ——
        // advancedBody 只把盒子挂到 col 下并返回内容容器；往后这一段的控件都加到 target（= 高级盒里）。
        val target = advancedBody(col)
        target.addView(Brut.heading(this, "自定义颜色（逐项调）"))
        fun colorRow(key: String, label: String, get: (Theme) -> Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6f), 0, dp(6f)); isClickable = true }
            val swatch = View(this)
            val tv = TextView(this).apply { textSize = 14f }
            row.addView(swatch, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(10f) })
            row.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = "改 ›"; textSize = 14f })
            row.setOnClickListener { pickColor(label, get(prefs.baseTheme())) { prefs.setThemeColor(key, it); refreshTheme() } }
            colorRows.add {
                val c = get(prefs.baseTheme())
                swatch.background = android.graphics.drawable.GradientDrawable().apply { setColor(Theme.argb(0xFF, c)); cornerRadius = dp(6f).toFloat(); setStroke(dp(1f), 0xFF888888.toInt()) }
                tv.text = "$label：" + colorDesc(c)
            }
            target.addView(row)
        }
        colorRow("bg", "面板底色（渐变时是左上）") { it.bg }
        colorRow("bg2", "面板渐变色（右下；和底色一样 = 纯色）") { it.bg2 ?: it.bg }
        val opLabel = TextView(this).apply { textSize = 14f; setPadding(0, dp(6f), 0, 0) }
        target.addView(opLabel)
        val seek = android.widget.SeekBar(this).apply {
            max = 50; progress = prefs.baseTheme().opacity - 50
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { prefs.setThemeColor("opacity", 50 + p); refreshTheme() } }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        target.addView(seek)
        colorRows.add { val o = prefs.baseTheme().opacity; opLabel.text = "面板不透明度：$o%" + if (o < 85) "（后面的内容会透上来，可能看不清）" else ""; if (seek.progress != o - 50) seek.progress = o - 50 }
        colorRow("text", "正文文字") { it.text }
        colorRow("answer", "答案行文字") { it.answer }
        colorRow("heading", "标题文字") { it.heading }
        colorRow("orbA", "小球颜色（左上）") { it.orbA }
        colorRow("orbB", "小球颜色（右下）") { it.orbB }
        target.addView(TextView(this).apply {
            text = "选好就生效，悬浮球和开着的面板会立刻换色。改任意一项会自动切到「自定义」（先照当前方案抄一份再改）。对比度 ≥ 4.5 看着清楚，低于 3 就太淡了（渐变底按两端里更差的那端算）；按钮、分隔线、次要文字会跟着正文文字色自动配。"
            textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(2f), 0, dp(4f))
        })
        refreshTheme()
    }

    private fun colorDesc(c: Int): String = Theme.hex(c) + (Theme.nameOf(c)?.let { " $it" } ?: "") + " · 亮度 ${Theme.brightness(c)}"

    private fun refreshTheme() {
        val t = prefs.baseTheme()
        themeRows.forEach { it() }
        colorRows.forEach { it() }
        // 预览（用 t 而不是 Ui.theme：设置页可能比悬浮球先开）
        previewBox.removeAllViews()
        previewBox.background = Ui.panelBg(this, t, 16f)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "预览 · 做题"; textSize = 12f; setTextColor(t.tx2) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(OrbView(this).apply { themeOverride = t }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        previewBox.addView(top)
        previewBox.addView(TextView(this).apply { text = "B. 木块受到的合力为零"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setTextColor(Theme.argb(0xFF, t.answer)) })
        previewBox.addView(TextView(this).apply { text = Md.render("## 解析\n1. 匀速直线运动，合力一定为零。\n2. 其余选项都和这一点矛盾。", Theme.argb(0xFF, t.heading), t.codeBg); textSize = 15f; setLineSpacing(0f, 1.3f); setTextColor(t.tx1) })
        previewBox.addView(TextView(this).apply { text = "次要文字（状态提示）"; textSize = 12.5f; setTextColor(t.tx3) })
        // 对比度按不透明底算（面板半透明时实际还受后面内容影响）；渐变底取两端里更差的
        fun cr(fg: Int) = t.bgs.minOf { Theme.contrast(fg, it) }.let { String.format("%.1f:1（%s）", it, Theme.contrastWord(it)) }
        contrastLine.text = "对比度 —— 正文 ${cr(t.text)}；答案行 ${cr(t.answer)}；标题 ${cr(t.heading)}"
    }

    // ---------------- v1.66 首次配置引导：一步一页，左右滑；没做完的不能往后，可以往回（判断在 Onboarding） ----------------
    private lateinit var setupPager: SwipePager
    private lateinit var setupSteps: List<Onboarding.Step>
    private lateinit var setupDots: PageDots
    private lateinit var setupStepLine: TextView
    private lateinit var setupHint: TextView
    private lateinit var setupPrev: Button
    private lateinit var setupNext: Button
    /** 各页里「✓ 已完成 / ✗ 还没做」那几行的刷新函数 */
    private val setupRows = ArrayList<() -> Unit>()

    private fun setupState() = Onboarding.State(
        overlay = Settings.canDrawOverlays(this),
        apiReady = prefs.api().baseUrl.isNotBlank() && prefs.api().model.isNotBlank(),
        a11ySupported = A11yService.supported,
        // Onboarding 的「无障碍」这一步按「系统里已授权」算完成（授权了没连上也已算开，别把人拦在这一步）
        a11yOn = a11yEnabled(),
        notifications = KeepAlive.notificationsOn(this),
        battery = KeepAlive.ignoringBattery(this),
        skipped = prefs.onboardSkipped,
    )

    private fun showSetup() {
        inSetup = true
        val resumed = prefs.onboardStarted   // 之前走到一半（去系统设置开权限、进程被杀）→ 接着停在第一个没做完的那步
        prefs.onboardStarted = true
        setupRows.clear()
        val st = setupState()
        setupSteps = Onboarding.steps(st)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(16f); setPadding(p, dp(12f), p, dp(10f)) }
        val banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        banner.addView(TextView(this).apply { text = Preset.appName + " · 首次配置"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.on(Brut.MINT)); tag = Brut.KEEP })
        setupStepLine = TextView(this).apply { textSize = 14f; setTextColor(Brut.on(Brut.MINT)); setPadding(0, dp(2f), 0, 0); tag = Brut.KEEP }
        banner.addView(setupStepLine)
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) })
        Brut.card(banner, Brut.MINT)
        setupDots = PageDots(this).apply { count = setupSteps.size }
        root.addView(setupDots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        setupPager = SwipePager(this)
        root.addView(setupPager, LinearLayout.LayoutParams(-1, 0, 1f))
        setupHint = TextView(this).apply { textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(2f), dp(4f), dp(2f), dp(2f)) }
        root.addView(setupHint)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setupPrev = Button(this).apply { text = "‹ 上一步"; setOnClickListener { setupPager.goTo(setupPager.current - 1) } }
        setupNext = Button(this).apply { tag = Brut.PRIMARY; setOnClickListener { if (setupPager.current == setupSteps.size - 1) finishSetup(true) else setupPager.goTo(setupPager.current + 1) } }
        nav.addView(setupPrev, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(setupNext, LinearLayout.LayoutParams(0, -2, 1.4f))
        root.addView(nav)

        for (step in setupSteps) setupPager.addPage(setupPage(step))
        setupPager.canGo = { from, to -> Onboarding.canGo(setupSteps, from, to, setupState()) }
        setupPager.onBlocked = { from ->
            setupHint.text = "✗ " + Onboarding.blockedHint(setupSteps[from])
            setupHint.animate().translationX(dp(8f).toFloat()).setDuration(60).withEndAction { setupHint.animate().translationX(0f).setDuration(120).start() }.start()
            setupPager.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        setupPager.onPageChanged = { refreshSetup() }
        if (resumed) setupPager.goTo(Onboarding.reachable(setupSteps, st), animate = false)

        setContentView(root)
        Brut.page(this, root)
        Brut.style(root)
        refreshSetup()
    }

    private fun refreshSetup() {
        if (!::setupPager.isInitialized) return
        val st = setupState()
        val i = setupPager.current
        val step = setupSteps[i]
        setupRows.forEach { it() }
        setupStepLine.text = "第 ${i + 1} 步 / 共 ${setupSteps.size} 步 · ${step.title}" + if (step.required) "（必需）" else ""
        setupDots.count = setupSteps.size; setupDots.current = i
        val done = Onboarding.done(step, st)
        setupHint.text = when {
            i == setupSteps.size - 1 -> "都设好了 —— 点「完成」开始用"
            done -> "✓ 这一步好了 · 向左滑或点「下一步」"
            else -> "这一步做完才能往后（可以往回滑）· " + Onboarding.blockedHint(step)
        }
        setupPrev.isEnabled = i > 0
        setupNext.text = when {
            i == setupSteps.size - 1 -> "完成，启动小球"
            done -> "下一步 ›"
            else -> "下一步 ›（先做完这步）"
        }
        setupNext.isEnabled = done
    }

    /** 一步一页：标题 + 说明 + 状态行 + 去做的按钮（推荐步骤多一个「先不开」） */
    private fun setupPage(step: Onboarding.Step): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4f), dp(4f), dp(4f), dp(24f)) }
        fun h(s: String) = col.addView(Brut.heading(this, s))
        fun tip(s: String) = col.addView(TextView(this).apply { text = s; textSize = 14f; setPadding(0, dp(4f), 0, dp(6f)) })
        // v1.90：长解释折叠 —— 一句用途 + 「详情 ›」点开看完整说明（步骤顺序 / required 语义不变）
        fun tipD(short: String, full: String) = col.addView(detailTip(short, full))
        fun status(f: () -> String) {
            val tv = TextView(this).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12f), dp(10f), dp(16f), dp(14f)); tag = Brut.KEEP }
            col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f); bottomMargin = dp(6f) })
            setupRows.add { val s = f(); val fill = if (s.startsWith("✓")) Brut.MINT else Brut.CARD; tv.text = s; tv.setTextColor(Brut.on(fill)); tv.background = Brut.box(this, fill, 3f) }
        }
        fun button(label: String, primary: Boolean = false, act: () -> Unit) = col.addView(Button(this).apply { text = label; isAllCaps = false; if (primary) tag = Brut.PRIMARY; setOnClickListener { act() } })
        fun skip(label: String) = if (Onboarding.canSkip(step)) button(label) {
            prefs.onboardSkipped = prefs.onboardSkipped + step.id
            refreshSetup(); setupPager.goTo(setupPager.current + 1)
        } else Unit
        when (step) {
            Onboarding.Step.WELCOME -> {
                h("欢迎用 " + Preset.appName)
                tipD("一步一页，把用小球需要的几样设好：悬浮窗权限、接口、截屏方式、通知、后台运行。",
                    "接下来一步一页，把用小球需要的几样设好：悬浮窗权限、接口、截屏方式、通知、后台运行。左右滑动翻页，也可以点底下的按钮。必需的步骤做完才能往后；推荐的步骤可以点「先不开」。随时可以往回滑。")
                tipD("小球怎么用：双击识别屏幕，单击开关答案面板，按住半秒出菜单，拖动挪位置。",
                    "小球怎么用：双击 = 识别屏幕；单击 = 开 / 关答案面板；按住半秒 = 菜单；拖动 = 挪位置。")
                // v1.86：不想一页一页走的话，这里一键把系统页按顺序递到面前（回来自动接着下一页）
                button("一键开权限（不用自己翻设置）", primary = true) {
                    try { startActivity(PermissionActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开权限页") }
                }
                status { "✓ 准备好了就往左滑" }
            }
            Onboarding.Step.OVERLAY -> {
                h("悬浮窗权限（必需）")
                tipD("小球要显示在别的 App 上面，靠系统「显示在其他应用上层」权限，没有它小球出不来。",
                    "小球要显示在别的 App 上面，靠的是系统的「显示在其他应用上层」权限。没有它小球出不来。")
                status { if (Settings.canDrawOverlays(this)) "✓ 已允许" else "✗ 还没允许" }
                button("去打开「显示在其他应用上层」", primary = true) { KeepAlive.openOverlay(this) }
                tip("在打开的页面里找到「" + Preset.appName + "」，把开关打开，再按返回回到这里。")
            }
            Onboarding.Step.API -> {
                h("填接口（必需）")
                tipD("识别要把截图发给 AI，用你自己的接口（模型要能看图），填好点「检测连接」。",
                    "识别屏幕要把截图发给 AI，用你自己的接口：要兼容 OpenAI 的调用格式（主流服务大多支持），模型要能看图。填好地址和模型，点「检测连接」确认一下。")
                status { val a = prefs.api(); if (a.baseUrl.isNotBlank() && a.model.isNotBlank()) "✓ 已填：模型 " + a.model else "✗ 还没填全（地址和模型都要填）" }
                fun field(label: String, value: String, hint: String, password: Boolean, onChange: (String) -> Unit) {
                    col.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8f), 0, 0) })
                    col.addView(EditText(this).apply {
                        setText(value); this.hint = hint; setSingleLine()
                        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: ""); refreshSetup() }
                        })
                    })
                }
                field("接口地址", prefs.baseUrl, "https://api.deepseek.com/v1", false) { prefs.baseUrl = it }
                field("Key", prefs.apiKey, "sk-…", true) { prefs.apiKey = it }
                field("模型", prefs.model, "要支持看图，比如 deepseek-vl / glm-4v / qwen-vl", false) { prefs.model = it }
                testRes = TextView(this).apply { textSize = 13f; setPadding(0, dp(4f), 0, 0) }
                button("检测连接") { testConnection() }
                col.addView(testRes)
                tip("以后在设置 →「接口」组里还能改，也能存成几套方案一键切换。")
            }
            Onboarding.Step.CAPTURE -> {
                h("截屏方式：无障碍截屏（可选）")
                // v1.91：一句话状态 + 一个统一授权入口（PermissionActivity 里能看系统开关、受限设置等细节）——
                // 「受限设置」「列表里是灰的」这些说明不再在这里重复堆。
                status {
                    when {
                        !A11yService.supported -> "✓ 本机系统太老（需 Android 11+），用录屏授权，这步跳过"
                        a11yConnected() -> "✓ 无障碍截屏已开（已连接）"
                        a11yEnabled() -> "· 系统里已开，还没连上 —— 去权限页看一眼；连上后识别不再弹录屏框"
                        step.id in prefs.onboardSkipped -> "✓ 先不开：识别时用录屏授权（每次启动点一次）"
                        else -> "✗ 还没开"
                    }
                }
                if (A11yService.supported) {
                    button("去权限页开启（我带你一项项走）", primary = true) {
                        try { startActivity(PermissionActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开权限页") }
                    }
                    skip("先不开，用录屏授权（每次启动要点一次）")
                }
            }
            Onboarding.Step.NOTIFY -> {
                h("通知（推荐）")
                tip("小球常驻后台靠一条前台通知；通知里还有「翻译刚复制的 / 隐藏小球 / 停止小球」几个按钮（「停止小球」保留无障碍授权，下次启动不用重授）。")
                status { if (KeepAlive.notificationsOn(this)) "✓ 通知已开" else if (step.id in prefs.onboardSkipped) "✓ 先不开（看不到常驻通知和它的按钮）" else "✗ 通知没开" }
                button("打开通知", primary = true) {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs.onboardSkipped.contains("notify-asked")) {
                        prefs.onboardSkipped = prefs.onboardSkipped + "notify-asked"   // 系统弹框只弹一两次，之后直接去设置页
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                    } else KeepAlive.openNotifications(this)
                }
                skip("先不开")
            }
            Onboarding.Step.BATTERY -> {
                h("允许后台运行（推荐）")
                tip("不把 " + Preset.appName + " 排除在电池优化外，系统可能过一会就把小球清掉。")
                status { if (KeepAlive.ignoringBattery(this)) "✓ 已允许后台运行" else if (step.id in prefs.onboardSkipped) "✓ 先不开（小球可能被系统清掉）" else "✗ 还在电池优化里" }
                button("允许后台运行", primary = true) { KeepAlive.askBattery(this) }
                button("厂商的自启动 / 后台管理") { KeepAlive.openVendorAutostart(this) }
                tipD("各厂商的自启动 / 后台管理入口不一样，点开看怎么找。", KeepAlive.brandTip())
                skip("先不开")
            }
            Onboarding.Step.FINISH -> {
                h("开始使用")
                status {
                    val s = setupState()
                    setupSteps.filter { it != Onboarding.Step.WELCOME && it != Onboarding.Step.FINISH }.joinToString("\n") { st ->
                        val ok = Onboarding.done(st, s)
                        val skipped = st.id in s.skipped && when (st) { Onboarding.Step.CAPTURE -> s.a11ySupported && !s.a11yOn; Onboarding.Step.NOTIFY -> !s.notifications; Onboarding.Step.BATTERY -> !s.battery; else -> false }
                        (if (!ok) "✗ " else if (skipped) "△ " else "✓ ") + st.title + if (skipped) "（先不开）" else ""
                    }.let { "✓ 配置情况\n$it" }
                }
                tipD("点下面「完成，启动小球」就开始；之后都在设置首页按分类里改。",
                    "点下面「完成，启动小球」就开始；之后都在设置首页里改（按用途点进分类，区内点标题展开折叠组）。「先不开」的几项在设置 →「高级设置 → 后台保活」随时能补上。")
                button("完成（先不启动小球）") { finishSetup(false) }
            }
        }
        return ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
    }

    private fun finishSetup(start: Boolean) {
        prefs.onboarded = true
        showSettings(null)
        refreshState(); keepRows.forEach { it() }
        if (start) onStartClicked()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY && inSetup) refreshSetup()
    }

    /** 取色：列表里每项都写名字 + 色号 + 亮度（灰度屏也分得清），最后一项手动输色号 */
    private fun pickColor(label: String, cur: Int, onPick: (Int) -> Unit) {
        val items: Array<CharSequence> = (Theme.NAMED.map { (name, c) ->
            android.text.SpannableString("■■  $name  ${Theme.hex(c)} · 亮度 ${Theme.brightness(c)}" + if (c == cur) "  ✓ 当前" else "").apply {
                setSpan(android.text.style.ForegroundColorSpan(Theme.argb(0xFF, c)), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } + "输入色号（如 #1C1C1E）…").toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("选$label").setItems(items) { _, i ->
            if (i < Theme.NAMED.size) onPick(Theme.NAMED[i].second) else inputHex(label, cur, onPick)
        }.setNegativeButton("取消", null).show()
    }

    private fun inputHex(label: String, cur: Int, onPick: (Int) -> Unit) {
        val et = EditText(this).apply { setText(Theme.hex(cur)); setSingleLine(); setSelection(text.length) }
        android.app.AlertDialog.Builder(this).setTitle("$label · 输入色号").setMessage("格式 #RRGGBB（也可 #RGB），比如 #FFFFFF = 白、#000000 = 黑").setView(et)
            .setPositiveButton("确定") { _, _ -> Theme.parseHex(et.text.toString())?.let(onPick) ?: toast("色号格式不对：要像 #1C1C1E 这样 6 位") }
            .setNegativeButton("取消", null).show()
    }

    /** 系统里无障碍开关是否已授权（≠ 已连上，见 [A11yService.enabledInSystem]） */
    private fun a11yEnabled() = A11yService.enabledInSystem(this)

    /** 无障碍服务此刻是否已连上 —— 只有连上了才真能截屏（[A11yService.available]） */
    private fun a11yConnected() = A11yService.available

    private fun refreshState() {
        if (!::permLine.isInitialized) return
        val overlay = Settings.canDrawOverlays(this)
        val connected = a11yConnected()
        // v1.91：无障碍分「系统里已授权」和「此刻已连上」两种状态，分别说清楚：
        // 已授权但没连上时不能假称「系统关了 / 已恢复」，也不自动改走录屏；点上面的权限入口可查看系统开关。
        permLine.text = when {
            !A11yService.supported -> "无障碍截屏：本机系统太老（需 Android 11+），识别时用录屏授权（每次启动点一次）。"
            connected -> "无障碍截屏：已开（已连接）。"
            a11yEnabled() -> "无障碍截屏：系统里已开，但此刻没连上 —— 点上面的「权限」看系统开关；连上后识别不再弹录屏框。"
            else -> "无障碍截屏：还没开 —— 点上面的「权限」去开；不开也能用，识别时弹一次录屏授权。"
        }
        permLine.visibility = View.VISIBLE
        keepRows.forEach { it() }
        startBtn.text = when {
            !overlay -> "先打开「显示在其他应用上层」权限"
            connected && OrbService.running -> "悬浮球运行中 · 用无障碍截屏"
            connected -> "启动悬浮球（无障碍已就绪，不弹录屏框）"
            // 已授权但没连上：按钮点了去权限页确认系统开关，不偷偷走录屏
            a11yEnabled() -> "去权限页确认无障碍（系统里已开、当前没连上）"
            OrbService.running -> "改用录屏授权（重新授权）"
            else -> "启动悬浮球（将用录屏授权，每次都要点）"
        }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    /**
     * v1.76.6：导入 / 保存词库成功后，立刻在后台把待弹的词全量预翻译掉。
     * 原来只有弹卡时那一轮 20 个的懒加载，导入两千词的词库要弹几十轮才翻得完。
     * 整段在 Thread 里跑（UI 不等）；额度保险丝与失败退避都在 [WordPretranslateStore.prefetchAll] 里。
     * v1.78：联网判断改用 [isOnlineForFetch]（原来这里自己写了一份，缺 ACCESS_NETWORK_STATE 时恒为「没网」，
     * 于是这段预翻译从来没真正跑过）。
     */
    private fun prefetchImportedWords() {
        Thread {
            try {
                val cards = prefs.activeCards()
                if (!isOnlineForFetch()) return@Thread
                val now = System.currentTimeMillis()
                val store = WordPretranslateStore(prefs)
                // 先数一遍要翻几个：全是缓存命中就别打扰用户了
                val todo = WordPretranslate.pending(store.load(), cards.map { it.first to it.second.front }, now, max = Int.MAX_VALUE).size
                if (todo <= 0) return@Thread
                val cap = minOf(todo, WordPretranslate.PREFETCH_ALL_MAX_BATCHES * WordPretranslate.BATCH_MAX)
                runOnUiThread { toast("已开始预翻译 $cap 个词（后台进行中）") }
                val done = store.prefetchAll(cards, prefs.deepl(), true, now)
                if (done > 0) runOnUiThread { toast("预翻译完成：成功 $done 个") }
            } catch (_: Exception) {}
        }.start()
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            // v1.86：不再只把用户甩到「悬浮窗」那一页 —— 走一键开权限，顺手把通知 / 后台运行 / 无障碍 / 精确闹钟
            // 也按顺序递到面前（回来自动接着下一页）。打不开就退回原来那一跳。
            try { startActivity(PermissionActivity.intent(this)) } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)   // 不给也能用，只是看不到常驻通知
        }
        // v1.91：只有无障碍**已连上**（available）才走无障碍截屏这条路 —— 系统里已授权但没连上时不当作能截屏。
        if (a11yConnected()) {
            // 无障碍已连上：直接起服务，完全不碰录屏授权（普通 startService：本页在前台，允许；不起录屏类前台）
            startService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_START))
            startBtn.postDelayed({ refreshState() }, 400)
            moveTaskToBack(true)
            return
        }
        // 「系统里已授权、当前没连上」不能自动退录屏 —— 那是「已授权」状态，用户期望的是不弹录屏框。
        // 直接进权限页让他确认系统开关（连上后启动就不弹框），并明确说一句，别静默走掉录屏那条路。
        if (a11yEnabled()) {
            toast("无障碍已在系统里授权、当前还没连上 —— 去「权限」确认系统开关；连上后启动不再弹录屏框")
            try { startActivity(PermissionActivity.intent(this)) } catch (_: Exception) {}
            return
        }
        // 未授权（或本机不支持无障碍）：走录屏备用这条路 —— 主动请求录屏只发生在这一种「确认用不了无障碍」的状态。
        requestCapture()
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) { refreshState(); return }
        // v1.59：Android 14 起直接要「整个屏幕」，框里不用再从「单个应用」切过去
        @Suppress("DEPRECATION") startActivityForResult(CaptureActivity.captureIntent(this), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // [切片 D] Anki 牌组选文件回结果（AI 导入在 ImportActivity 里独立处理），跟录屏那条路互不干扰
        if (requestCode == REQ_ANKI) { onAnkiPicked(resultCode, data); return }
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            permLine.text = "录屏授权被取消了 —— 没有它就截不了屏。再点一次按钮重新授权。"
            return
        }
        startForegroundService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_START)
            .putExtra(OrbService.EXTRA_CODE, resultCode).putExtra(OrbService.EXTRA_DATA, data))
        startBtn.postDelayed({ refreshState() }, 400)
        moveTaskToBack(true)   // 回到桌面，悬浮球已经在了
    }

    // [切片 D] 知识卡导入拆成两个**显式**入口，不做「一个按钮猜格式」的隐式分流：
    //  - 文本 / Word / PDF → 独立页面 [ImportActivity]（选文件或粘贴 → 预览 → 显式调 AI → 核对 → 批量入库）
    //  - Anki 牌组 .apkg → 沿用原来的解包导入（[AnkiImporter]）
    private fun openKnowledgeImport() {
        try { startActivity(ImportActivity.intent(this)) }
        catch (e: Exception) { toast("打不开导入页：" + (e.message ?: "")) }
    }

    // [AnkiImport] 显式的 Anki 入口：只认 .apkg（SAF 选文件，零权限）。
    private fun openAnkiImport() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(AnkiImporter.pickerIntent(), REQ_ANKI)
        } catch (e: Exception) { toast("打不开文件选择器：" + (e.message ?: "")) }
    }

    private fun onAnkiPicked(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return   // 用户取消：什么都不做
        importAnki(uri)
    }

    // [AnkiImport] K2.5：.apkg 专用解包导入（解包和查库是 IO，必须子线程）。
    private fun importAnki(uri: android.net.Uri) {
        toast("正在导入…")
        thread {
            val r = AnkiImporter(this).import(uri)
            runOnUiThread { toast(if (!r.ok) (r.error ?: "导入失败") else "导入 ${r.added} 张（跳过 ${r.skipped}）") }
        }
    }

    /**
     * v1.73 可点时间行：一行「标签 …… 时间值 ›」，点整行弹系统 TimePickerDialog，选完回调 onPicked(分钟)。
     * 行本身做成白卡片，符合 Brut 风格；返回值可直接 addView。
     */
    private fun timeRow(label: String, min: Int, onPicked: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(12f), dp(12f))
            isClickable = true; isFocusable = true
        }
        val name = TextView(this).apply { text = label; textSize = 15f; setTextColor(Brut.INK) }
        val value = TextView(this).apply {
            text = PopupPolicy.fmtMin(min) + "  ›"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        Brut.card(row)
        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dp(6f); it.bottomMargin = dp(6f); row.layoutParams = it
        }
        row.setOnClickListener {
            val cur = min / 60
            val curMin = min % 60
            android.app.TimePickerDialog(this, { _, h, m ->
                val total = PopupPolicy.normMin(h * 60 + m)
                value.text = PopupPolicy.fmtMin(total) + "  ›"
                onPicked(total)
            }, cur, curMin, true).show()
        }
        return row
    }

    /** v1.86「标签 + 当前值 › + 点开单选」的行（和 [timeRow] 一个长相，只是选项是文本） */
    private fun choiceRow(label: String, options: List<String>, cur: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(12f), dp(12f), dp(12f))
            isClickable = true; isFocusable = true
        }
        val name = TextView(this).apply { text = label; textSize = 15f; setTextColor(Brut.INK) }
        val value = TextView(this).apply {
            text = (options.getOrNull(cur) ?: "") + "  ›"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        Brut.card(row)
        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dp(6f); it.bottomMargin = dp(6f); row.layoutParams = it
        }
        row.setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(options.toTypedArray(), cur.coerceIn(0, (options.size - 1).coerceAtLeast(0))) { d, which ->
                    value.text = options[which] + "  ›"
                    onPick(which)
                    d.dismiss()
                }
                .setNegativeButton("取消", null).show()
        }
        return row
    }

    /**
     * v1.73 统一弹出时刻改动后，把三类主动弹出的闹钟全重排一遍。
     * 简报由 Briefing 自己排，复习提醒由 ReviewNotify 排；两个都 reschedule 一次就对齐到新时刻。
     */
    private fun rescheduleAllPopups() {
        try { BriefingReceiver.kick(this) } catch (_: Exception) {}
        try { ReviewNotify.reschedule(this) } catch (_: Exception) {}
    }

    private fun testConnection() {
        testRes.text = "检测中…"
        val api = prefs.api()
        thread {
            val c = ApiClient(api)
            val msg = try { "✓ 连通：" + c.ping().take(40) } catch (e: Exception) { "✗ " + NetErrors.friendly(e, api.base) }
            runOnUiThread { testRes.text = if (msg.startsWith("✓")) "$msg\n检测能不能看图…" else msg }
            if (!msg.startsWith("✓")) return@thread
            // v1.63：识别屏幕要靠看图 —— 顺带发张小图试试，不会看图的模型在这里就说清楚
            val v = c.pingVision()
            runOnUiThread { testRes.text = msg + "\n" + (v?.let { "⚠ $it" } ?: "✓ 能看图，可以识别屏幕") }
        }
    }

    /**
     * v1.77 更新通道：抓清单 → 比版本 → 怎么显示全交给 [Update.check] 决定。
     * 自动检查（开设置页、距上次超 24h）全程静默：不写「检查中…」，已是最新 / 读不到清单都不写文案，
     * 发现新版也只写文案 + 亮一个下载按钮，**绝不自己 enqueue 下载** —— 之前就是这里直接开下载，
     * 用户一打开页面就被弹「开始下载」，看着莫名失控。
     * 下载/安装都交给系统，不搞静默安装。
     */
    private fun checkUpdate(manual: Boolean) {
        // 依次要试的地址：用户手填的（非空）+ 内置源；本开源副本内置源为空，留空时列表为空 = 不检查。
        // 使用 [Update.sources] 保留上游的多源语义，也方便 fork 自己注入更新源。
        val sources = Update.sources(prefs.updateUrl)
        val customOverride = sources.size > 1          // 用户另外填了一个（且和内置源不同）
        // 自动检查时不写「检查中…」：页面正开着呢，突然冒一行字也是干扰
        if (manual) updateRes.text = "检查中…"
        thread {
            prefs.updateLastCheck = System.currentTimeMillis()   // 检查即记，失败也不反复重试
            // 按顺序逐个试，第一个能解析成清单的就用它；试过但没读到的地址记下来，失败时摆给用户看
            var used: String? = null
            var m: Update.Manifest? = null
            val tried = ArrayList<String>()
            for (u in sources) {
                val parsed = try { Update.parse(java.net.URL(u).readText(Charsets.UTF_8)) } catch (e: Exception) { null }
                if (parsed != null) { used = u; m = parsed; break }
                tried.add(u)
            }
            val r = Update.check(m, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, manual)
            // 手填的失败了、内置源顶上：明确说一句，别静默替换，也不把手填的失败当整体失败
            val fallbackNote = if (used != null && customOverride) "\n" + Update.FALLBACK_NOTE else ""
            runOnUiThread {
                currentManifest = null
                updateDlBtn.visibility = View.GONE
                when (r) {
                    is Update.CheckResult.Silent -> updateRes.text = ""
                    is Update.CheckResult.UpToDate -> updateRes.text = r.text + fallbackNote
                    // 失败：把这次试过的地址都摆出来（还是内置源一条时就是「当前地址」），用户才知道实际用的是哪个
                    is Update.CheckResult.Failed ->
                        updateRes.text = r.text + if (manual) "\n当前地址：\n" + tried.joinToString("\n") { "✗ $it" } else ""
                    is Update.CheckResult.NewVersion -> {
                        updateRes.text = r.text + fallbackNote
                        currentManifest = r.manifest
                        updateDlBtn.text = r.button
                        // v1.77：已经有一个包在下时不亮按钮（防重复下）；轮询/恢复会继续管它
                        if (prefs.updateDlId <= 0L) updateDlBtn.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /**
     * v1.77：只有用户点了「下载更新」才走到这 —— 交给系统 DownloadManager 下载，
     * 进度自己在设置页显示（500ms 查一次），下完立刻拉起系统安装器。
     * 系统会继续把包下完（App 关了也不影响），所以 id 记在 Prefs 里，重进设置页能接着看（见 [restoreUpdateDownload]）。
     */
    private fun startUpdateDownload(m: Update.Manifest) {
        val dm = getSystemService(android.app.DownloadManager::class.java)
        if (dm == null) {
            // v1.85.7：这台设备连系统下载器都没有（精简 ROM / 部分模拟器镜像）—— 直接走直连兜底
            startDirectDownload(m)
            return
        }
        // v1.85.3：清单 url 可能带中文文件名 —— 不编码 DownloadManager 直接 Illegal character in path
        dmStartedAt = System.currentTimeMillis()
        val req = android.app.DownloadManager.Request(android.net.Uri.parse(Update.safeUrl(m.url)))
            .setTitle(Preset.appName + " " + m.versionName)
            .setDescription("更新包下载（下完自动装）")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        try {
            val id = dm.enqueue(req)
            prefs.updateDlId = id
            currentManifest = m
            dlInstallPrompted = false
            updateDlBtn.visibility = View.GONE      // 下载中不亮按钮：防重复下第二个包
            showDlProgress(0, 0)
            startDlPolling()
            toast("开始下载 " + m.versionName + "，下完自动弹安装")
        } catch (e: Exception) {
            prefs.updateDlId = 0
            updateRes.text = updateRes.text.toString() + "\n✗ 下载起不来：" + (e.message ?: e.javaClass.simpleName)
            updateDlBtn.visibility = View.VISIBLE
        }
    }

    /** v1.77：一次 DownloadManager 查询的结果（status/已下/总量），查不到就是 null（记录已失效） */
    private data class DlInfo(val status: Int, val soFar: Long, val total: Long)

    private fun queryDl(id: Long): DlInfo? = try {
        val dm = getSystemService(android.app.DownloadManager::class.java)
        dm.query(android.app.DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) null
            else DlInfo(
                c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun startDlPolling() {
        if (dlPolling) return
        dlPolling = true
        dlHandler.post(dlTick)
    }

    private fun stopDlPolling() {
        dlPolling = false
        dlHandler.removeCallbacks(dlTick)
    }

    private val dlTick = object : Runnable {
        override fun run() {
            if (!dlPolling) return
            val id = prefs.updateDlId
            if (id <= 0L) { stopDlPolling(); return }
            val info = queryDl(id) ?: run { onDlEnd(id, failed = true); return }
            // v1.85.7：enqueue 成功但一个字节都没下到、超过 12 秒 —— 系统下载器对这个直链不干活
            // （真机实测：「下载中 0.0 MB」永远不动）。移掉它转 App 内直连下载，别让用户干等。
            if (UpdateDirect.isStuck(dmStartedAt, info.soFar, System.currentTimeMillis())) {
                try { getSystemService(android.app.DownloadManager::class.java)?.remove(id) } catch (_: Exception) {}
                prefs.updateDlId = 0
                stopDlPolling()
                updateRes.text = updateRes.text.toString() + "\n系统下载器没动静，改用 App 内下载…"
                startDirectDownload(currentManifest ?: return)
                return
            }
            when (info.status) {
                android.app.DownloadManager.STATUS_SUCCESSFUL -> onDlEnd(id, failed = false)
                android.app.DownloadManager.STATUS_FAILED -> onDlEnd(id, failed = true)
                // 其余（RUNNING / PENDING / PAUSED 等着重试或等网）都还可能自己好起来：继续轮询，不当失败
                else -> { showDlProgress(info.soFar, info.total); dlHandler.postDelayed(this, DL_POLL_MS) }
            }
        }
    }

    /** 进度条 + 文字。total 未知（-1）时不给假百分比，改报已下载多少 MB */
    private fun showDlProgress(soFar: Long, total: Long) {
        if (!::dlProgRow.isInitialized) return
        dlProgRow.visibility = View.VISIBLE
        val known = total > 0
        dlProgBar.isIndeterminate = !known && soFar <= 0
        if (known) {
            val pct = ((soFar * 100L) / total).toInt().coerceIn(0, 100)
            dlProgBar.progress = pct
            dlProgText.text = "下载中 $pct%（${mb(soFar)} / ${mb(total)}）"
        } else {
            dlProgBar.progress = 0
            dlProgText.text = "下载中 已下载 ${mb(soFar)}"
        }
    }

    private fun mb(b: Long): String = String.format("%.1f MB", b / 1048576.0)

    /** 下载结束（成功或失败）：停轮询、清记录，成功就弹安装器 */
    private fun onDlEnd(id: Long, failed: Boolean) {
        stopDlPolling()
        prefs.updateDlId = 0
        if (failed) {
            if (::dlProgRow.isInitialized) {
                dlProgRow.visibility = View.VISIBLE
                dlProgBar.isIndeterminate = false
                dlProgBar.progress = 0
                dlProgText.text = "✗ 下载失败，请重试"
            }
            if (::updateDlBtn.isInitialized) updateDlBtn.visibility = View.VISIBLE
            return
        }
        if (::dlProgRow.isInitialized) dlProgRow.visibility = View.GONE
        promptInstall(id)
    }

    /**
     * v1.77：下完直接拉起系统安装器。
     * 方案一（主方案，官方可行）：用 DownloadManager 自己给这条下载生成的 content:// URI，
     * 配 FLAG_GRANT_READ_URI_PERMISSION 交给安装器读 —— targetSdk 35 下这是系统明确支持的路子，
     * 不需要额外声明 FileProvider。
     * 方案二（备选，真机上方案一被 ROM 拦时才换）：把 APK 落到 getExternalFilesDir("update")，
     * Manifest 里加 provider（authority = ${applicationId}.fileprovider）+ res/xml/file_paths.xml
     * （<external-files-path name="update" path="update/" />），再用 FileProvider.getUriForFile 拿
     * 同权限的 content URI，Intent 的 data 换成它。代价是要多两个文件 + 自己管下载落盘，所以不作为默认。
     */
    /**
     * v1.85.7 App 内直连下载（后台线程）：MediaStore.Downloads 流式写 → 完成 → 拉系统安装器。
     * 进度直接刷 [showDlProgress]；任何失败都给出可见文案，绝不静默。
     */
    private fun startDirectDownload(m: Update.Manifest) {
        if (directDl) return
        directDl = true
        if (::dlProgRow.isInitialized) {
            dlProgRow.visibility = View.VISIBLE
            dlProgBar.isIndeterminate = true
        }
        showDlProgress(0, -1)
        val internal = applicationInfo.packageName.endsWith(".internal")
        kotlin.concurrent.thread(name = "lz-updl") {
            val uri = UpdateDirect.download(this@MainActivity, m.url,
                UpdateDirect.fileNameFor(m.versionName, internal)) { soFar, total ->
                runOnUiThread { showDlProgress(soFar, total) }
            }
            runOnUiThread {
                directDl = false
                if (uri == null) {
                    if (::dlProgRow.isInitialized) {
                        dlProgBar.isIndeterminate = false
                        dlProgBar.progress = 0
                        dlProgText.text = "✗ App 内下载也失败了 —— 检查网络后点「下载更新」重试"
                    }
                    if (::updateDlBtn.isInitialized) updateDlBtn.visibility = View.VISIBLE
                } else {
                    directUri = uri
                    if (::dlProgRow.isInitialized) dlProgRow.visibility = View.GONE
                    try {
                        startActivity(UpdateDirect.installIntent(uri))
                    } catch (e: Exception) {
                        toast("下载完成，但打不开安装界面 —— 请在「文件管理 → 下载」里点它安装")
                    }
                }
            }
        }
    }

    private fun promptInstall(id: Long) {
        if (dlInstallPrompted) return
        dlInstallPrompted = true
        val uri = try { getSystemService(android.app.DownloadManager::class.java).getUriForDownloadedFile(id) } catch (_: Exception) { null }
        if (uri == null) {
            toast("下载好了，但没拿到安装包：请到通知栏点那条下载完成的通知")
            return
        }
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // 少数 ROM 不认这个 content URI：退一步让用户去系统下载列表自己点
            toast("打不开安装界面，请到通知栏/下载列表点那条下载完成的通知")
        }
    }

    /**
     * v1.77：打开设置页时对账 —— Prefs 里记着没下完的 id 就查一次系统那边的真实状态：
     * 还在下 → 恢复进度条和轮询；已完成 → 直接弹安装；失败/查无此项 → 清掉记录当没下过。
     * 这样下载中把 App 杀掉，回来进度不丢、也不会把已下好的包晾着。
     */
    private fun restoreUpdateDownload() {
        if (!::dlProgRow.isInitialized) return
        val id = prefs.updateDlId
        if (id <= 0L) return
        val info = queryDl(id)
        when {
            info == null -> prefs.updateDlId = 0                                   // 记录失效（比如通知栏被清）
            info.status == android.app.DownloadManager.STATUS_SUCCESSFUL -> onDlEnd(id, failed = false)   // 上次已经下完了 → 补弹安装
            info.status == android.app.DownloadManager.STATUS_FAILED -> onDlEnd(id, failed = true)
            else -> {
                currentManifest?.let { updateDlBtn.visibility = View.GONE }        // 下着的包别让用户再点一次
                showDlProgress(info.soFar, info.total)
                startDlPolling()
            }
        }
    }
}
