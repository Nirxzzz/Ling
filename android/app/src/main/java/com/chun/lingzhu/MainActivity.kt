package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.chun.lingzhu.voice.LiveTalkActivity
import com.chun.lingzhu.voice.LiveTalkSettings
import com.chun.lingzhu.voice.TencentVoice
import com.chun.lingzhu.voice.VoiceActivity
import com.chun.lingzhu.voice.VoiceProvider
import com.chun.lingzhu.voice.VoiceSettings

/**
 * 设置页 + 首用授权入口。改动即存（不用点保存），下一次识别就用新设置。
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
 * v2.7.x：分类首页去重 —— 必要配置两样都配齐时整卡收成一行摘要（点进权限页），没配齐才摊开给「去做」按钮；
 * 「常用」只留底栏到不了的启动小球与历史记录（待办 / 学习在底栏各有页，不再重复）。
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_API = "api"       // v1.63：面板「还没填接口 · 点这里」过来：跳到「接口与模型」分类的「接口」组
        const val EXTRA_MODES = "modes"    // 从模式相关入口过来：跳到「小球与识别」分类的「识别模式」组
        const val EXTRA_ASSIST = "assist"   // 早晚简报点进来：去「记事」页的「待办」那一栏
        const val EXTRA_MEMO = "memo"       // 随手记「看全部」点进来：去「记事」页的「随手记」那一栏
        /** 任意卡片 id：别的页面「去设置里改」用它落到那张卡片（找不到就停在首页，不猜） */
        const val EXTRA_SECTION = "section"

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
        // 注：Anki 牌组的 .apkg 选文件不再走本页 —— 它整体搬进了 anki 功能包（自己的页面管选文件与进度），
        // 本页只留一个「导入 Anki 牌组」按钮去开那个功能，所以这里没有对应的请求码。
        private const val REQ_NOTIFY = 8
        private const val STATE_PAGE = "lingzhu.page"   // 旋转后回到设置页原来那一页
        /** 折叠组收起时标题行右侧那行摘要的最长字数（超了截断加省略号） */
        private const val SUMMARY_MAX = 28
        /** v1.77：下载进度轮询间隔（查系统 DownloadManager 一次） */
        private const val DL_POLL_MS = 500L
        /** 输入框停手多久才给一条「已保存」（逐字刷会盖住整屏，700ms 已在人眼可接受范围内） */
        private const val SAVE_TYPING_MS = 700L
        /** 同一句回执在这个窗口内重复只出一次（重建 / 连点造成的双触发） */
        private const val SAVE_DEDUPE_MS = 1200L
        /** 回执项名里要剥掉的补充说明：（…）/（...）—— 只说项名，不带括号里的长解释 */
        private val PAREN_NOTE = Regex("[（(][^）)]*[）)]")
        private val WHITESPACE = Regex("\\s+")

        // ================= 2.2.x 设置页：微信式「首页分组行 → 分类二级页（白色分组卡片）」=================
        // 分类 / 卡片 / 旧项 → 新位置 的唯一登记表在 [SettingsRegistry]（纯 Kotlin，单测钉住一项不丢）。

        /** 设置首页（页 0）。返回键在首页 = 退出设置。 */
        const val HUB = "设置"

        /** 8 个按用户心智分的一级分类（顺序 = 首页顺序 = 子页顺序） */
        const val C_AI = SettingsRegistry.C_AI
        const val C_ORB = SettingsRegistry.C_ORB
        const val C_POP = SettingsRegistry.C_POP
        const val C_STUDY = SettingsRegistry.C_STUDY
        const val C_LOOK = SettingsRegistry.C_LOOK
        const val C_DATA = SettingsRegistry.C_DATA
        const val C_PERM = SettingsRegistry.C_PERM
        const val C_ABOUT = SettingsRegistry.C_ABOUT

        val CATEGORIES = SettingsRegistry.CATEGORIES

        // ---- 旧折叠组 id（原值不变：Prefs.openGroups 存档键 / 跳转用）。现在每个都是二级页里的一张分组卡片 ----
        const val G_AUTH = SettingsRegistry.G_AUTH
        const val G_KEEP = SettingsRegistry.G_KEEP
        const val G_DATA = SettingsRegistry.G_DATA
        const val G_SCAN = SettingsRegistry.G_SCAN
        const val G_API = SettingsRegistry.G_API
        const val G_SEARCH = SettingsRegistry.G_SEARCH
        const val G_TRANSLATE = SettingsRegistry.G_TRANSLATE
        const val G_MODES = SettingsRegistry.G_MODES
        const val G_COLOR = SettingsRegistry.G_COLOR
        const val G_PANEL = SettingsRegistry.G_PANEL
        const val G_CUSTOM = SettingsRegistry.G_CUSTOM
        const val G_WORDS_POP = SettingsRegistry.G_WORDS_POP
        const val G_WORDS_DECK = SettingsRegistry.G_WORDS_DECK
        const val G_REMIND = SettingsRegistry.G_REMIND

        /** 一个旧折叠组的登记项：id / 新卡片标题 / 新归属分类（都从 [SettingsRegistry.SECTIONS] 取） */
        data class GroupDef(val id: String, val title: String, val tab: String)

        /** 旧折叠组清单（id 稳定）；标题与归属跟着新信息架构走 */
        val GROUPS: List<GroupDef> = SettingsRegistry.OLD_GROUPS.keys.map { id ->
            val sec = SettingsRegistry.section(id)!!
            GroupDef(id, sec.title, sec.cat)
        }
    }

    /**
     * 折叠组的纯逻辑（不碰 View / SharedPreferences，单测直接测这里）。
     *
     * 展开态存 Prefs.openGroups —— 存的是**当前展开的组 id 全集**，不是「用户手动展开过的」；
     * 用户每点一次就整份覆盖，只有一个键，语义不绕。长配置卡（[LONG]）默认收着（[defaults] 恒为空 =
     * 谁都不在展开集合里），只有「权限」在必需项没做完时由 [locked] 强制展开；短卡根本不参与开合。
     */
    object Sections {
        /**
         * 默认收起的长配置卡：内容多到一屏放不下（接口 / 搜索 / 翻译 / 词库 / 提醒 / 保活…），
         * 进来只该看见「标题 + 现在什么状态」，想改再点开。一两个开关的短卡、纯入口卡（自定义中心那样
         * 只有几个跳转按钮的）不在此列，恒展开 —— 把入口藏起来只会让人找不到。
         */
        val LONG: Set<String> = setOf(
            G_API, G_SEARCH, G_TRANSLATE,
            G_MODES,
            G_PANEL, G_SCAN,
            G_WORDS_POP, G_REMIND, SettingsRegistry.S_POP_RULE, SettingsRegistry.S_POP_BRIEF,
            G_WORDS_DECK, SettingsRegistry.S_STUDY,
            G_AUTH, G_KEEP,
        )

        /**
         * 首次进入（Prefs 里还没存过）时默认展开的组 —— **一律为空**：
         * 长配置卡（[LONG]）默认收着，短卡本来就恒展开；没做完的必需项由 [locked] 强制展开，不靠这里。
         */
        fun defaults(groups: List<GroupDef>): Set<String> = emptySet()

        /** 这个组现在是不是展开的。[locked] 为 true 时（授权没做完的权限卡）恒展开，且不许收起 */
        fun isOpen(id: String, open: Set<String>, locked: Boolean = false): Boolean = locked || id in open

        /**
         * 点标题行，返回新的展开集合。
         * 锁着的组点了不动（返回原集合）—— 授权没做完之前不许把「该做的事」藏起来。
         */
        fun toggled(id: String, open: Set<String>, locked: Boolean = false): Set<String> =
            if (locked) open else if (id in open) open - id else open + id

        /** 明确要求某组展开 / 收起（[jumpTo] 用），返回新的展开集合 */
        fun setOpen(id: String, open: Boolean, cur: Set<String>): Set<String> =
            if (open) cur + id else cur - id

        /** 授权没做完时权限卡锁住：悬浮窗权限或接口还没搞定，就一直摊开让人先做 */
        fun locked(id: String, overlay: Boolean, apiReady: Boolean): Boolean = id == G_AUTH && !(overlay && apiReady)
    }

    private lateinit var prefs: Prefs
    private lateinit var permLine: TextView
    private lateinit var startBtn: Button
    /** v1.62「后台保活」清单：每项一个按钮，文字前面写 ✓ / ✗（不靠颜色），onResume 刷新 */
    private val keepRows = ArrayList<() -> Unit>()
    /**
     * 专注守护 / 陪伴气泡卡片里「跟着运行时状态变」的文案（小球跑没跑、使用情况访问有没有给）。
     * 和 [keepRows] 一样在 onResume / 重建后重算一次 —— 用户可能刚去系统设置里开了授权。
     */
    private val focusRows = ArrayList<() -> Unit>()
    /**
     * v1.90 分类首页的刷新函数：必要配置（摊开的卡 / 配齐后的那一行）+ 常用两行 + 每个分类入口那一行「当前值摘要」。
     * build 时和 onResume 各刷一次 —— 权限 / 接口 / 配色这些一改，首页摘要立刻跟着变。
     */
    private val hubRows = ArrayList<() -> Unit>()
    private lateinit var testRes: TextView
    /** 连接检测的代号：接口 / 方案一改就 +1，旧检测晚到的结果不许再写回界面（避免「旧配置 ✓」误导） */
    private var testGen = 0
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
     * 上次画出来的那套配色（建视图那一刻的 [Ui.theme]）。
     *
     * 为什么要单独记一份：系统深浅色变化 /「跟随系统深色」开关改的是 [Prefs.theme] 的**结果**，
     * [Prefs.themeId] 可以一个字节都没动。onResume 里原有的那几项比较（模块开关 / 权限 / 接口）
     * 都看不出「色板已经换了」，页面就会继续用旧色板画着。这里按色板本身比一次，变了才重建。
     */
    private var shownTheme: Theme? = null
    /**
     * 悬浮窗授权快照。系统权限只可能在 Activity 不在前台时变，所以建树 / 回前台各量一次 ——
     * 设置页 26 张卡每次刷新都要问一遍 [Settings.canDrawOverlays]（跨进程 binder），没必要每张卡都走一趟。
     */
    private var overlayGranted = false
    /**
     * 「接口配好了没」的快照。[Prefs.hasApi] 背后是 [Prefs.api]：要解析搜索配置、Agent 列表、模块开关，
     * 设置页建树 + 每次刷新会问上百次；而它的结论其实只由一个运行时输入决定 ——
     * [Prefs.api] 的 `baseUrl`：这个开源副本不内置任何接口，地址只能是用户自己填的那个；
     * Key / 模型 / 自定义方案列表都不影响“通不通”。
     * 所以快照键就是这个输入：键没变结论必然相同，键一变立刻重算。
     * 这个值只会被设置页「自行填写接口」的输入框改（改了键立刻跟着变）。
     * 这条结论只用于界面提示与卡片锁态，请求路径仍各自读 [Prefs.api]。
     */
    private var apiReadyKey: String? = null
    private var apiReadyCached = false
    /**
     * v1.91 无障碍观察器：系统开关 / 服务绑定一旦变动（从系统设置回来、被系统解绑），就地刷新状态与引导。
     * onResume 订阅、onPause 注销（见 [onResume] / [onPause]）。回调在主线程，只改文案/可见性，不重建整页。
     */
    private var a11yWatch: java.io.Closeable? = null
    /**
     * 上一次 build() 用的「关掉的模块」快照。
     *
     * 设置页的模块入口行（待办 / Anki / 学习 / 知识卡库）是 build 时按当时的开关**画或不画**的；
     * 用户去「模块管理」改完开关回来，[onResume] 按这个快照比一次，变了就整棵重建 ——
     * 否则会留着一条点了才弹「已关闭」的旧行，那等于入口没关掉，只是晚了半秒才告诉你。
     */
    private var builtDisabled: Set<String> = emptySet()
    /** 首页顶部搜索栏（2.2.x）。没建出来 / 已被重建替换掉的旧实例由它自己靠代际号丢弃迟到结果 */
    private var search: SettingsSearchView? = null
    /** 分组卡片 id → 它自己「高级设置」那一行的 head（跳转到高级项时只展开这一组，不动别的组、不改 pref） */
    private val advHeads = HashMap<String, View>()
    /** 分组卡片 id → 它「高级设置」盒的内容容器（[findAdvancedBody] 优先用这个，不靠结构/背景猜） */
    private val advBodies = HashMap<String, LinearLayout>()
    /** 分组卡片 id → 它「高级设置」盒的开合器（[openHit] 判开合用它：播动画时 visibility 还不是终态） */
    private val advFolds = HashMap<String, AnimGlue.FoldMotion>()
    /** 分组卡片 id → 立刻展开它的高级盒（搜索跳转用：要终态，不播过渡） */
    private val advOpeners = HashMap<String, () -> Unit>()
    /** 正在画哪张卡片（[advancedBody] 把高级盒记到这张卡名下）；null = 不在卡片里 */
    private var advSec: String? = null
    /** 设置项「改完就存好了」的短回执：上一条 Toast / 排队中的输入回执 / 去重窗口 */
    private val saveHandler = Handler(Looper.getMainLooper())
    private var savedToast: android.widget.Toast? = null
    private var saveSerial = 0
    private var lastSavedMsg: String? = null
    private var lastSavedAt = 0L
    /** >0 = 正在程序化回填输入框（切方案 / 填入示例）：这段里写 pref 不算用户改，不出回执 */
    private var quietEdits = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // 先把当前配色装进 Ui.theme 再建视图：Brut 的颜色是 getter，建控件那一刻就要取到正确色板
        Ui.applyTheme(prefs)
        shownTheme = Ui.theme
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
        intent.getStringExtra(EXTRA_SECTION)?.let { scrollToSection(it) }
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
        focusRows.clear()
        val root = build()
        setContentView(root)
        skinSettings(root)

        // 按页名恢复：老版本 Bundle 里存的下标 / 已删掉的页名都自然落回首页（下标 0）
        val keep = saved?.getString(STATE_PAGE)?.let { pageNames.indexOf(it) } ?: -1
        if (keep >= 0) pager.goTo(keep, animate = false)
        // 只有「首次进入」（saved == null）才按 extra 跳转；旋转 / 重建时页已按页名恢复，
        // 再无条件跑 extra 会覆盖恢复结果、把人倒回启动时的目标页
        if (saved == null) {
            if (i?.getBooleanExtra(EXTRA_API, false) == true) scrollToApi()
            if (i?.getBooleanExtra(EXTRA_MODES, false) == true) scrollToModes()
            i?.getStringExtra(EXTRA_SECTION)?.let { scrollToSection(it) }
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

    /**
     * 搜索点了某一条结果：收到 [SettingsRegistry] 里那张分组卡片。
     * 卡片标题本身就是结果（[SettingsSearch.Hit.itemId] 就是卡片 id）时不碰高级盒；
     * 其余高级项先把**它那一组**收着的「高级设置」盒直接展开（不播过渡：跳转紧随其后要按最终高度滚），
     * 再走原来的 [jumpTo] —— 只动这一组，不整组摊开，也不改 pref.openGroups（高级盒本来就不存档）。
     */
    private fun openHit(h: SettingsSearch.Hit) {
        val sec = SettingsRegistry.section(h.sectionId) ?: return
        if (h.advanced && h.itemId != sec.id) {
            val head = advHeads[sec.id]
            if (head != null && head.visibility == View.VISIBLE) {
                // 以开合器记的状态为准（收起动画还在跑时 body 仍是 VISIBLE）；没登记过才退回看 visibility
                val fold = advFolds[sec.id]
                val open = fold?.isOpen ?: (advBodies[sec.id]?.visibility == View.VISIBLE)
                // 跳转要的是**终态**：紧接着就得按最终布局滚过去，不能让它一边长高一边滚
                if (!open) advOpeners[sec.id]?.invoke()
            }
        }
        jumpTo(sec.cat, sec.id)
    }

    private fun scrollToModes() = jumpTo(C_ORB, G_MODES)
    private fun scrollToApi() = jumpTo(C_AI, G_API)

    /** 落到任意一张卡片（[EXTRA_SECTION]）：卡片 id 不认识就原地不动，不猜一个近似的 */
    private fun scrollToSection(id: String) {
        val sec = SettingsRegistry.section(id) ?: return
        jumpTo(sec.cat, sec.id)
    }

    /**
     * 把首页摘要和所有卡片标题摘要都按最新设置重刷一遍（全量版）。
     *
     * 只给「回前台 / 无障碍状态变了 / 换配色重建」这种低频点用：26 张卡的摘要里
     * 有历史条数、知识卡数、词库列表、缓存大小，全是解析文件 / 读存档的活。
     * 翻页用 [refreshPageSummaries]：一页只刷自己那几张卡，别在点的这一拍里跑全量。
     */
    private fun refreshSummaries() {
        hubRows.forEach { it() }
        groupBoxes.values.forEach { it.render() }
    }

    /** 重算悬浮窗授权快照（[build] 开头 / [onResume] 各一次；系统权限只在这两处之间会变） */
    private fun refreshOverlayGranted() { overlayGranted = Settings.canDrawOverlays(this) }

    /** 接口配好没有（[Prefs.hasApi] 的缓存版：键 = 自填地址，变了就重算） */
    private fun apiReady(): Boolean {
        val key = prefs.baseUrl
        if (key != apiReadyKey) {
            apiReadyKey = key
            apiReadyCached = prefs.hasApi()
        }
        return apiReadyCached
    }


    /**
     * 只刷新刚翻到的那一页（首页 = 那几行分类摘要 + 必需配置卡）。
     *
     * 全量 [refreshSummaries] 是 26 张卡：历史 / 知识卡 / 词库 / 缓存读数 + 每张卡的标题行重写。
     * 原来每次翻页都在点击回调里同步跑一遍，而那 26 张卡里只有本页那几张看得见 ——
     * 这一拍花掉的正是「点分类 / 返回要先卡一下才开始动」的时间。
     */
    private fun refreshPageSummaries(page: String?) {
        if (page == null) return
        if (page == HUB) { hubRows.forEach { it() }; return }   // 首页只有分类入口那几行 + 必要配置卡，没有分组卡片
        for (sd in SettingsRegistry.SECTIONS) if (sd.cat == page) groupBoxes[sd.id]?.render()
    }

    /** 系统返回键：分类子页 → 回首页（保留查询，只刷新摘要）；首页 → 交回系统（退出设置，回 launcher 聊天页）。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!inSetup && ::pager.isInitialized && pager.current != 0) {
            pager.goTo(0); return   // 回首页：查询保留（不 reset）；摘要由 onPageChanged 按页刷新
        }
        if (!inSetup) {
            // 首页再按返回：优先只清掉搜索查询 + 收键盘，不直接退出设置
            val s = search
            if (s != null && s.queryText().isNotBlank()) { s.clearQuery(); return }
            s?.leave()   // 真要退出设置：掐断在飞的搜索请求
        }
        super.onBackPressed()
    }

    /** 首页每个分类入口下那行「当前值摘要」—— 不用点进去就知道这块现在什么状态。 */
    private fun catSummary(cat: String): String = when (cat) {
        C_AI -> {
            val a = prefs.api()
            when {
                a.baseUrl.isBlank() -> "还没填接口"
                else -> a.model.ifBlank { a.baseUrl.removePrefix("https://").removePrefix("http://") }
            }
        }
        C_ORB -> Modes.label(prefs.mode, prefs.customModes) + " · 小球 " + prefs.orbSizeDp + "dp"
        C_POP -> (if (prefs.wordOn) "弹词每 " + Prefs.fmtInterval(prefs.wordEverySec) else "弹词已关") +
            (if (prefs.popupQuietOn) " · 免打扰开" else "")
        C_STUDY -> "勾选 " + prefs.wordDecksOn.size + " 个词库" + (if (FocusSettings(this).enabled) " · 专注守护开" else "")
        C_LOOK -> (if (Theme.normId(prefs.themeId) == Theme.CUSTOM) "自定义配色"
            else themeLabel(Theme.normId(prefs.themeId), Theme.PRESETS.firstOrNull { it.first == Theme.normId(prefs.themeId) }?.second ?: prefs.themeId)) + " · " + prefs.textSp + "sp"
        C_DATA -> "历史 " + (if (prefs.saveHistory) "开" else "关") + " · 缓存 " + ((Cache.solveStore(this).bytes() + Cache.translateStore(this).bytes() + 1023) / 1024) + " KB"
        C_PERM -> {
            val st = KeepAlive.flowState(this)
            when {
                PermissionFlow.blocked(st).isNotEmpty() -> "✗ 还差悬浮窗"
                PermissionFlow.ready(st) -> "✓ 必需项已开"
                else -> PermissionFlow.summary(st)
            }
        }
        C_ABOUT -> "v" + BuildConfig.VERSION_NAME.substringBefore("-") + " " + Preset.channel
        else -> ""
    }

    // ---------------- 专注守护 / 陪伴气泡（v2.3.4 手机版）----------------

    /** 「判定方式」单选的两个标签：下标 0 = 指定学习应用，1 = 屏幕内容（与 [FocusSettings.mode] 一一对应） */
    private val FOCUS_MODE_LABELS = listOf("指定学习应用", "屏幕内容")

    /** 陪伴气泡三档（值就是 [FocusSettings] 存的字符串；顺序 = 频率从低到高） */
    private val COMPANION_FREQS = listOf(FocusSettings.FREQ_LOW, FocusSettings.FREQ_MED, FocusSettings.FREQ_HIGH)
    private val COMPANION_FREQ_LABELS = listOf("低（60~180 分钟一次）", "中（45~120 分钟一次）", "高（15~60 分钟一次）")

    private fun companionFreqLabel(v: String): String {
        val i = COMPANION_FREQS.indexOf(v)
        return if (i >= 0) COMPANION_FREQ_LABELS[i] else COMPANION_FREQ_LABELS[1]
    }

    /** 专注守护卡标题右侧那行摘要：开关 / 方式 / 阈值 / 是否真有采样前提 */
    private fun focusSummary(fs: FocusSettings): String {
        if (!fs.enabled) return "已关"
        val mode = if (fs.mode == FocusSettings.MODE_SCREEN) "屏幕内容" else "指定学习应用"
        return mode + " · 离开 " + fs.leaveMinutes + " 分钟" + (if (OrbService.running) "" else " · 小球未启动")
    }

    /** 陪伴气泡卡标题右侧的摘要：开关 + 频率（关着就只说关） */
    private fun companionSummary(fs: FocusSettings): String =
        if (!fs.companionEnabled) "已关" else "开 · " + companionFreqLabel(fs.companionFrequency)

    /**
     * 专注守护 / 陪伴气泡改完设置后一起刷：卡片标题右侧那行摘要 + 卡内跟着运行时状态变的文案。
     *
     * 只刷 [focusRows] 的话，标题会停在旧值（「已关」却已经开了），正是「看不出到底开没开」那类错；
     * [SectionBox.render] 只读存档、不动展开态存档，可以在这里放心调。
     */
    private fun refreshFocusCards() {
        groupBoxes[SettingsRegistry.S_FOCUS]?.render()
        groupBoxes[SettingsRegistry.S_COMPANION]?.render()
        focusRows.forEach { it() }
    }

    /**
     * 带启动入口的 App（包名 + 名字）缓存：一次 build 里 focusRows 可能被刷好几次，
     * 每次都走一遍 binder 查应用列表没必要。回前台时清掉 —— 期间可能刚装了 / 卸了 App。
     */
    private var appListCache: List<Pair<String, String>>? = null

    /**
     * 系统里所有带启动入口的 App（包名 + 名字），按名字排序。
     *
     * 只查 `ACTION_MAIN / CATEGORY_LAUNCHER`，**不用 QUERY_ALL_PACKAGES** —— 只看得见桌面上本来就有入口的 App，
     * 也就能看见系统允许普通应用枚举的那些。Manifest 里缺 `<queries>` 时这里拿到空表，
     * 界面会照实说「读不到应用列表」，不假装选过。
     */
    private fun launchableApps(): List<Pair<String, String>> {
        appListCache?.let { return it }
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val out = LinkedHashMap<String, String>()
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue                       // 自己不算学习应用
            val label = try { ri.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                ?: pkg.substringAfterLast('.')
            if (!out.containsKey(pkg)) out[pkg] = label
        }
        val sorted = out.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        appListCache = sorted
        return sorted
    }

    /** 学习应用多选：勾上的包名存进 [FocusSettings.apps]；一个都没勾就明说「不算学习」 */
    private fun pickStudyApps() {
        val apps = launchableApps()
        if (apps.isEmpty()) {
            toast("读不到应用列表 —— 系统没放开应用查询，或本机没有可启动的 App")
            return
        }
        val fs = FocusSettings(this)
        val chosen = fs.apps.toMutableSet()
        ThemeDialogs.builder(this)
            .setTitle("哪些 App 算学习（可多选）")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), apps.map { it.first in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen.add(apps[i].first) else chosen.remove(apps[i].first)
            }
            .setPositiveButton("确定") { _, _ ->
                fs.apps = chosen
                FocusSettings.refresh(this)
                refreshFocusCards()
                saved("已保存：学习应用 " + chosen.size + " 个")
            }
            .setNegativeButton("取消", null)
            .show()
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
        /**
         * 模式名 / 提示词的输入框：照样每改一次就写回（存的是当前文字，语义没动）。
         * `old` 是建这个框时填进去的值，只用来认出「这次的文字和上一次写回的一样」（重建 / 程序化 setText）
         * —— 那种情况不重复写、也不给回执，免得重建时平白弹一条「已保存」。
         */
        fun watch(e: EditText, old: String, label: String, f: (String) -> Unit): TextWatcher {
            var last = old
            val w = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString() ?: ""
                    if (v == last) return
                    last = v
                    f(v)
                    if (canReportEdit(e)) savedAfterTyping("已保存：" + brief(label))
                }
            }
            e.addTextChangedListener(w)
            return w
        }
        val customs = prefs.customModes
        val radioOf = HashMap<String, RadioButton>()
        fun desc(m: String) = when (m) { Prompts.MODE_EXAM -> "（答案+解析+考点）"; Prompts.MODE_GENERAL -> "（识别整屏）"; else -> "（我的提示词）" }
        val g = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (m in Modes.all(customs)) {
            val rb = RadioButton(this).apply { text = Modes.label(m, customs) + desc(m); tag = m; id = View.generateViewId() }
            g.addView(rb); radioOf[m] = rb
            if (m == prefs.mode) rb.isChecked = true
        }
        g.setOnCheckedChangeListener { grp, checkedId ->
            val m = grp.findViewById<View>(checkedId)?.tag as? String
            if (m != null && m != prefs.mode) { prefs.mode = m; saved("已保存：识别模式 " + brief(Modes.label(m, prefs.customModes))) }
        }
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
            // 旧值 / 新值真正不同才算用户改：show() 里那次程序化 isChecked（重建、跟随配色回填）不许报
            follow.setOnCheckedChangeListener { _, on ->
                if (on == (prefs.modeOpacity(m) == 0)) return@setOnCheckedChangeListener
                prefs.setModeOpacity(m, if (on) 0 else prefs.baseTheme().opacity); show()
                saved("已保存：" + brief(Modes.label(m, customs)) + if (on) " 跟随配色" else " 不透明度 " + prefs.baseTheme().opacity + "%")
            }
            var seekMoved = false
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { seekMoved = true; prefs.setModeOpacity(m, Modes.OPACITY_MIN + p); show() } }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { seekMoved = false }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    if (!seekMoved) return        // 只是点了一下没拖：值没变，不报
                    val o = prefs.modeOpacity(m)
                    if (o > 0) saved("已保存：" + brief(Modes.label(m, customs)) + " 不透明度 " + o + "%")
                }
            })
            box.addView(opLabel); box.addView(follow); box.addView(seek)
            show()
        }

        for (c in customs) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
            card.background = WxSkin.rounded(WxSkin.FIELD, this, 10f)
            card.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            card.addView(TextView(this).apply { text = "自定义模式 · 名字"; textSize = 13f })
            card.addView(EditText(this).apply {
                setText(c.name); setSingleLine(); hint = "比如：错题本、只要答案、英文翻译"
                watch(this, c.name, "自定义模式 · 名字") { v -> val name = v.trim().ifEmpty { "自定义" }; prefs.updateCustom(c.id) { it.copy(name = name) }; radioOf[Modes.PREFIX + c.id]?.text = name + "（自定义）" }
            })
            card.addView(TextView(this).apply { text = "解析提示词"; textSize = 13f; setPadding(0, dp(6f), 0, 0) })
            val pe = EditText(this).apply {
                setText(c.prompt); hint = Prompts.CUSTOM_EXAMPLE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3; maxLines = 10; gravity = Gravity.TOP or Gravity.START; textSize = 14f
                watch(this, c.prompt, "自定义模式 · 提示词") { v -> prefs.updateCustom(c.id) { it.copy(prompt = v) } }
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
            follow.setOnCheckedChangeListener { _, on ->
                val following = (prefs.customModes.firstOrNull { it.id == c.id }?.opacity ?: 0) == 0
                if (on == following) return@setOnCheckedChangeListener   // 没真的改（重建时的程序化赋值）不报
                prefs.updateCustom(c.id) { it.copy(opacity = if (on) 0 else prefs.baseTheme().opacity) }; show()
                saved("已保存：面板不透明度跟随配色" + if (on) " 开" else " 关")
            }
            var seekMoved = false
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { seekMoved = true; prefs.updateCustom(c.id) { it.copy(opacity = Modes.OPACITY_MIN + p) }; show() }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { seekMoved = false }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    if (!seekMoved) return        // 只是点了一下没拖：值没变，不报
                    val o = prefs.customModes.firstOrNull { it.id == c.id }?.opacity ?: 0
                    if (o > 0) saved("已保存：面板不透明度 " + o + "%")
                }
            })
            card.addView(opLabel); card.addView(follow); card.addView(seek)
            show()
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Button(this).apply { text = "填入示例"; setOnClickListener { setQuiet(pe, Prompts.CUSTOM_EXAMPLE); pe.setSelection(pe.text.length) } })
            btns.addView(Button(this).apply {
                text = "删除这个模式"; tag = Brut.DANGER
                setOnClickListener {
                    ThemeDialogs.builder(this@MainActivity)
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
        WxSkin.style(box)
        tip("每个自定义模式有自己的名字、提示词和面板不透明度（比如「错题本」用不透明，「看视频字幕」调透明一点）。提示词直接写你想让它怎么解析截图，比如「只告诉我答案，不要解释」「把画面里的英文翻译成中文」。留空就用灰色示例。「纯小白讲解」开着时也会带上。")
    }

    override fun onResume() {
        super.onResume()
        // 权限快照由下面的 refreshState() 统一重算一次（从系统设置页开完权限回来就是靠它）
        // v1.91：无障碍的「系统开关 / 是否已连上」随时可能变（从系统设置回来、被系统解绑）——
        // 在前台时订阅观察器，变了就地刷新状态与引导；onPause 注销。先订再刷，避免刚好错过一次变动。
        a11yWatch?.close()
        a11yWatch = KeepAlive.observeA11y(this) { refreshA11yDependent() }
        if (inSetup) { refreshSetup(); return }
        // 系统深浅色变了（含「跟随系统深色」开关被改）：换的是 themeId 之外那层结果，[Prefs.themeId]
        // 可能一个字节都没动 —— 下面几项比较全看不出来，整页会继续用旧色板画着。
        // 这里按**色板本身**比一次：变了就走「换配色」同一条重建路（保留分类页 / 滚动位置 / 展开态 / 焦点）。
        // 重建后 shownTheme / builtDisabled 都已推进到新值，同一次 onResume 不会再来一遍，也就不会循环。
        val now = prefs.theme()
        val paletteChanged = now != shownTheme
        if (paletteChanged) {
            Ui.applyTheme(prefs)
            shownTheme = Ui.theme
        }
        // 从「模块管理」改完开关回来：本页的模块入口行（待办 / Anki / 学习 / 知识卡库）是按 build 时的
        // 开关画或不画的，只刷摘要改不了「这一行在不在」—— 变了就整棵重建（保留当前分类页 / 滚动位置 / 展开态）。
        // 重建后 builtDisabled 已是新值，同一次 onResume 不会再来一遍。
        if (paletteChanged || prefs.disabledFeatures != builtDisabled) rebuildSettingsPreservingState()
        appListCache = null      // 回前台可能刚装了 / 卸了 App：专注守护那张卡的已选应用名要重查一次
        refreshState()
        refreshSummaries()        // 首页必要配置卡 + 各分类当前值 + 卡片标题摘要（回前台才做一次全量）
        refreshCacheLine()
        if (::historyBtn.isInitialized) historyBtn.text = "查看历史记录（" + History.Store(this).list().size + " 条）"
        restoreUpdateDownload()   // v1.77：上次没下完的更新包，接着看进度 / 补弹安装
    }

    override fun onPause() {
        super.onPause()
        a11yWatch?.close(); a11yWatch = null   // v1.91：不在前台就别收观察通知了，回来看 onResume 会重新订阅
        stopDlPolling()   // v1.77：页面不在前台就别查了，回来看 onResume 会重新对账
        if (!inSetup) search?.leave()   // 退到后台也别留着在飞的搜索请求（leave 只掐请求、保留输入）
    }

    /**
     * v1.91：无障碍开关 / 绑定变化时就地刷新。只改文案与可见性（refreshState / 摘要 / 折叠组标题重渲染），
     * **不重建整页** —— 设置页里有正在编辑、还没保存的输入框（接口地址 / Key / 模型等），
     * 重建会把它们连同焦点 / 滚动位置一起丢掉（[rebuildSettingsPreservingState] 那条整棵重建路只在换配色、
     * 以及「模块管理」的开关变了时走 —— 后者见 [onResume]）。
     */
    private fun refreshA11yDependent() {
        if (inSetup) { refreshSetup(); return }
        if (!::permLine.isInitialized) return
        refreshState()
        refreshSummaries()
    }

    override fun onDestroy() {
        super.onDestroy()
        search?.destroy()
        search = null
        stopDlPolling()
        cancelSaveFeedback()   // 页面没了：还在排队的输入回执不许晚一步飘出来
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

    // ---------------- 分组卡片（2.2.x：原折叠组 → 二级页里的一张白色分组卡片）----------------

    /** 组 / 卡片 id → 已建好的卡片。跳转 / 刷新都要拿到它 */
    private val groupBoxes = LinkedHashMap<String, SectionBox>()

    /**
     * 让某张卡片展开 / 收起。[jumpTo] 用 —— 收起着滚过去只看得见一行标题，等于没跳。
     * 展开态照旧记在 [Prefs.openGroups]（当前展开全集），换配色整棵重建后还认得。
     */
    private fun setGroupOpen(id: String, open: Boolean) {
        val box = groupBoxes[id] ?: return
        // 短卡恒展开，没必要往存档里塞 id；锁着的卡也不许改（它本来就得摊开）
        if (box.collapsible && !box.lockedNow) {
            prefs.openGroups = Sections.setOpen(id, open, prefs.openGroups ?: Sections.defaults(GROUPS))
        }
        box.show(open)
    }

    /**
     * 二级页里的一张分组卡片：上方小号灰字标题（[head]）+ 白色圆角卡片（[content]，调用方往里加控件）。
     *
     * 长配置卡（[Sections.LONG]）**默认收起**：只露标题 + 右侧那行当前状态摘要，整行可点（≥48dp），
     * 点开才摊开卡片、再点收起。长相和卡内「高级设置」行一致，不另立一套折叠；展开态存 [Prefs.openGroups]。
     * 一两个开关 / 一行按钮的短卡不值得点两下：不可点，内容恒展开。
     */
    private inner class SectionBox(
        val id: String,
        title: String,
        var summaryFn: () -> String,
        /** 长配置卡：标题行可点开合，首次进入是收着的 */
        val collapsible: Boolean,
        lockedInitially: Boolean,
        openInitially: Boolean,
    ) {
        private val titleText = title
        /** 上次刷新时锁没锁（用来识别「刚补完必需项」的瞬间） */
        private var wasLocked = lockedInitially
        /**
         * 锁着（必需项没做完）：恒展开、点了不动。
         * 判据用建树 / 回前台时量好的快照（[overlayGranted] + [apiReady]）—— 每张卡每次刷新都走一次
         * binder 权限查询 + 整套配置解析的话，光刷新就把主线程占满了。
         */
        val lockedNow: Boolean
            get() = collapsible && Sections.locked(id, overlayGranted, apiReady())
        val content: LinearLayout = WxSkin.card(this@MainActivity)
        /** 卡片内容的高度过渡（短卡不用）。持有实例才能被打断 —— 连点开合不会两段一起走 */
        private val fold = AnimGlue.FoldMotion(content)
        /** 当前开合态（短卡恒 true；锁着的恒 true；长卡照 [Prefs.openGroups] 存档） */
        var isOpen = !collapsible || lockedInitially || openInitially
            private set
        private val sum = TextView(this@MainActivity).apply {
            textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        /** 「展开 › / 收起 ▾」：只有长配置卡上出现（和卡内「高级设置」行同一个说法） */
        private val state = TextView(this@MainActivity).apply {
            textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP; gravity = Gravity.END
            visibility = if (collapsible) View.VISIBLE else View.GONE
        }
        /** 卡片标题行（灰字组名 + 右侧摘要 + 开合态）。[jumpTo] 滚到它 */
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            tag = Brut.KEEP
            if (collapsible) {
                minimumHeight = dp(48f)
                setPadding(dp(16f), dp(6f), dp(12f), dp(6f))
                background = WxSkin.ripple(this@MainActivity, null)
                setOnClickListener { toggle() }
            } else {
                gravity = Gravity.BOTTOM
                setPadding(dp(16f), dp(18f), dp(16f), dp(6f))
            }
            addView(TextView(this@MainActivity).apply { text = titleText; textSize = 13f; setTextColor(Brut.HINT); tag = Brut.KEEP }, LinearLayout.LayoutParams(-2, -2))
            addView(sum, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
            addView(state, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) })
            contentDescription = titleText
        }
        val root = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        init {
            root.addView(head, LinearLayout.LayoutParams(-1, -2))
            root.addView(content, LinearLayout.LayoutParams(-1, -2))
            // 摘要不在这里算：构造时 summaryFn 还是占位的 { "" }，真正的值由 build() 结尾的 render() 一次算好
            applyState(animate = false)   // 建树不播动画：一进来整页一起长高晃一下很难看
        }

        /** 点标题行：开合翻转一次，整份展开集合覆盖写回（不做「点了但没变」的空动作） */
        private fun toggle() {
            if (!collapsible || lockedNow) return
            val next = Sections.toggled(id, prefs.openGroups ?: Sections.defaults(GROUPS))
            prefs.openGroups = next
            show(id in next, animate = true)
        }

        /**
         * 刷新：摘要 + 开合态 + 锁态。build 结尾 / onResume / 无障碍变化 / 搜索统计都走这里，
         * 所以只读存档、不把卡片擅自展开或收起。
         */
        fun render() {
            refreshSum()
            val nowLocked = lockedNow
            if (collapsible && wasLocked && !nowLocked) {
                // 刚补完必需的权限 / 接口：这张卡别在眼前突然塌掉，保持展开，想收用户自己点
                prefs.openGroups = Sections.setOpen(id, true, prefs.openGroups ?: Sections.defaults(GROUPS))
            }
            wasLocked = nowLocked
            isOpen = !collapsible || nowLocked || id in (prefs.openGroups ?: Sections.defaults(GROUPS))
            applyState(animate = false)   // 刷新（onResume / 搜索统计 / 无障碍变化）只落终态，不做过渡
        }

        /**
         * 设定开合（[jumpTo] 展开目标卡用）：默认**不播动画** —— 跳转紧接着要按最终位置滚过去。
         * 用户点标题行才走过渡（[toggle]）；短卡与锁着的卡永远摊开。
         */
        fun show(open: Boolean, animate: Boolean = false) {
            isOpen = !collapsible || lockedNow || open
            applyState(animate)
        }

        private fun applyState(animate: Boolean = false) {
            if (!collapsible) { content.visibility = View.VISIBLE; head.contentDescription = titleText; return }
            fold.setOpen(isOpen, animate)
            val locked = lockedNow
            head.isClickable = !locked; head.isFocusable = !locked
            state.text = when {
                locked -> "★ 必需"
                isOpen -> "收起 ▾"
                else -> "展开 ›"
            }
            val s = sum.text?.toString().orEmpty()
            head.contentDescription = titleText + (if (s.isEmpty()) "" else "，$s") + when {
                locked -> "，必需项，已展开"
                isOpen -> "，已展开，点按收起"
                else -> "，已收起，点按展开"
            }
        }

        private fun refreshSum() {
            sum.text = try { summaryFn() } catch (_: Exception) { "" }
        }
    }

    /**
     * 卡片内的「高级设置」盒：进阶项收进一个默认收起、**不存档**的盒里（每次进设置都是收着的）。
     * 只负责长出盒子并挂到 [parent] 下，返回往里加控件的容器。长相是卡片里的一行「高级设置 ……… 展开 ›」，
     * 上面一条细分隔线。
     */
    private fun advancedBody(parent: LinearLayout): LinearLayout {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2f), 0, dp(4f))
        }
        val label = TextView(this).apply {
            text = "高级设置"; textSize = 15f; setTextColor(Brut.INK); tag = Brut.KEEP
        }
        val state = TextView(this).apply { text = "进阶项 · 展开 ›"; textSize = 13f; setTextColor(Brut.HINT); tag = Brut.KEEP; gravity = Gravity.END }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50f)
            isClickable = true; isFocusable = true; tag = Brut.KEEP
            background = WxSkin.ripple(this@MainActivity, null)
            addView(label, LinearLayout.LayoutParams(-2, -2))
            addView(state, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = "高级设置，已收起，点按展开"
        }
        // 开合走统一的高度过渡（[AnimGlue.FoldMotion]）：连点就是打断上一段，收起末尾的 GONE 不会落到新一段展开上
        val fold = AnimGlue.FoldMotion(body)
        /** 收起 / 展开：`animate = false` 给搜索跳转用（要的是终态，紧接着还要按最终位置滚过去） */
        fun setOpen(open: Boolean, animate: Boolean = true) {
            fold.setOpen(open, animate)
            state.text = if (open) "收起 ▾" else "进阶项 · 展开 ›"
            head.contentDescription = "高级设置" + if (open) "，已展开，点按收起" else "，已收起，点按展开"
        }
        head.setOnClickListener { setOpen(!fold.isOpen, animate = true) }
        holder.addView(WxSkin.divider(this))
        holder.addView(head, LinearLayout.LayoutParams(-1, -2))
        holder.addView(body, LinearLayout.LayoutParams(-1, -2))
        // 登记：搜索跳到「这张卡里的高级项」时只展开这一行（见 [openHit]）；开合态以 fold 为准，
        // 不能用 visibility 猜 —— 收起动画还在跑时它仍是 VISIBLE
        advSec?.let {
            advHeads.put(it, head); advBodies.put(it, body); advFolds.put(it, fold)
            advOpeners[it] = { setOpen(true, animate = false) }
        }
        parent.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        return body
    }

    /** 二级页顶部返回栏：左「‹ 设置」（≥48dp 触达）+ 分类名，底下一条细线。不随内容滚动 */
    private fun subPageBar(title: String): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f); tag = Brut.KEEP
            setPadding(dp(4f), 0, dp(12f), 0)
        }
        bar.addView(TextView(this).apply {
            text = "‹ 设置"; textSize = 16f; setTextColor(Brut.INK); gravity = Gravity.CENTER
            minimumHeight = dp(48f); minimumWidth = dp(48f); setPadding(dp(10f), 0, dp(10f), 0)
            isClickable = true; isFocusable = true; contentDescription = "返回设置首页"
            background = WxSkin.ripple(this@MainActivity, null, 18f)
            setOnClickListener { pager.goTo(0) }   // 回首页：查询保留，不 reset；摘要由 onPageChanged 按页刷新
        }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(TextView(this).apply {
            text = title; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = Brut.KEEP }
        wrap.addView(bar, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(WxSkin.divider(this))
        return wrap
    }

    /** 设置页整页上皮：先走原来的 [Brut.page]（铺满 / 系统栏 insets / 状态栏图标深浅），再换成微信式页底 + [WxSkin.style] */
    private fun skinSettings(root: View) {
        Brut.page(this, root)
        root.setBackgroundColor(WxSkin.PAGE)
        window.decorView.setBackgroundColor(WxSkin.PAGE)
        WxSkin.style(root)
    }

    private fun build(): View {
        building = true
        refreshOverlayGranted()   // 整棵树按「这一刻」的权限 / 接口快照画：26 张卡不再各查一遍
        search?.destroy()
        search = null
        // 记住这一版视图是按哪套模块开关画的：从「模块管理」回来时 onResume 拿它比一次，变了就重建
        builtDisabled = prefs.disabledFeatures
        // 外层 page 只负责「可滚动内容 + 固定底栏」两件事。导航栏若也塞进 root，
        // 它就成了滚动内容的最后一节，最后一行会被它盖住 —— 这就是「外观」那行只露出半截的原因。
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 2.2.x：首页（分组行列表）+ 8 个分类二级页（分组卡片）。内容区仍是 SwipePager，底部没有类别栏。
        pager = SwipePager(this)
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        pageNames.clear(); pageScrolls.clear()
        groupBoxes.clear(); hubRows.clear(); advHeads.clear(); advBodies.clear(); advFolds.clear(); advOpeners.clear(); advSec = null

        // 下面的小工具都往 col 里加。sec(id) 把 col 指到某张分组卡片（卡片按 [SettingsRegistry.SECTIONS] 预先建好、
        // 按登记顺序摆进各自的分类页 —— 所以正文的书写顺序与显示顺序无关）；面板 / 小球 / 词库那几节要按选择重画，
        // 临时把 col 指到它自己的盒子里（v1.60），画完退回卡片那一层。
        val catCols = LinkedHashMap<String, LinearLayout>()
        fun pageCol(name: String): LinearLayout = catCols.getOrPut(name) {
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(32f)) }
        }
        // 长配置卡默认收起（只露标题 + 当前状态），必填卡（权限）没做完时锁着摊开 —— 见 [Sections.LONG] / [Sections.locked]。
        val openSet = prefs.openGroups ?: Sections.defaults(GROUPS)
        for (sd in SettingsRegistry.SECTIONS) {
            val locked = Sections.locked(sd.id, overlayGranted, apiReady())
            val box = SectionBox(
                sd.id, sd.title, { "" },
                collapsible = sd.id in Sections.LONG,
                lockedInitially = locked,
                openInitially = Sections.isOpen(sd.id, openSet, locked),
            )
            groupBoxes[sd.id] = box
            pageCol(sd.cat).addView(box.root, LinearLayout.LayoutParams(-1, -2))
        }
        var col = LinearLayout(this)
        /** 当前卡片那一层：临时改 col 画完子块后要退回这里 */
        var pageBack: LinearLayout = col

        /** 指到某张卡片；[summary] 是卡片标题右侧那行灰色当前值（可省略） */
        fun sec(id: String, summary: (() -> String)? = null) {
            val box = groupBoxes.getValue(id)
            if (summary != null) box.summaryFn = summary
            col = box.content; pageBack = col; advSec = id
        }
        /** 卡片画完：col 退回卡片那一层（沿用原来成对的 newGroup / endGroup 写法） */
        fun endGroup() { col = pageBack; advSec = null }
        fun h(s: String) = col.addView(WxSkin.subhead(this, s))
        fun tip(s: String) = col.addView(TextView(this).apply { text = s; textSize = 12.5f; setTextColor(Brut.HINT); tag = Brut.KEEP; setLineSpacing(0f, 1.2f); setPadding(0, dp(2f), 0, dp(6f)) })
        fun tipD(short: String, full: String) = col.addView(detailTip(short, full))
        /**
         * 单行输入：逐字写 pref（行为不变）。回执只在用户真在打字、且文字真的变了时给一条，
         * 并且停手约 700ms 才出 —— 不回显输入内容（接口地址 / 密钥 / 模型名都不进 toast）。
         */
        fun field(label: String, value: String, hint: String, password: Boolean = false, onChange: (String) -> Unit): EditText {
            col.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8f), 0, 0) })
            val e = EditText(this)
            e.setText(value); e.hint = hint; e.setSingleLine()
            e.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            var last = value
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString() ?: ""
                    onChange(v); keepRows.forEach { it() }   // 上手清单「② 接口」跟着打勾
                    if (v == last) return                     // 程序化回填 / 重建：值没变，不重复提示
                    last = v
                    if (canReportEdit(e)) savedAfterTyping("已保存：" + brief(label))
                }
            })
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
            // 选中项在上面就设好了、监听最后才挂：初始绑定与整页重建都不会误报
            var applied = selected   // 已落盘的值随每次点选推进：回切最初选项也要真的保存
            g.setOnCheckedChangeListener { grp, checkedId ->
                val id = grp.findViewById<View>(checkedId)?.tag as? String
                if (id == null || id == applied) return@setOnCheckedChangeListener
                applied = id
                onPick(id)
                saved("已保存：" + brief(options.firstOrNull { it.first == id }?.second ?: id))
            }
            col.addView(g)
        }
        // 滑杆：标签实时写数值（v1.60）；回执只在松手、且这一趟真的动过时给一条
        fun slider(min: Int, max: Int, value: Int, step: Int = 1, label: (Int) -> String, onSet: (Int) -> Unit) {
            val tv = TextView(this).apply { textSize = 15f; setPadding(0, dp(10f), 0, 0); text = label(value) }
            col.addView(tv)
            var moved = false
            var cur = value
            col.addView(android.widget.SeekBar(this).apply {
                this.max = (max - min) / step; progress = (value - min) / step
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = min + p * step
                        cur = v; moved = true; onSet(v); tv.text = label(v)
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { moved = false }
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        if (!moved) return          // 只是点了一下没拖：值没变，不报
                        moved = false
                        saved("已保存：" + label(cur))
                    }
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


        // 换页（点分类进 / 返回 / 滑回首页）：只刷**目标页**那几张卡，不再是一次全量 26 张。
        // 原来这一拍把 26 张卡的摘要全算一遍（历史条数、知识卡数、词库列表、缓存大小，逐张重写标题行），
        // 而其中绝大多数卡当下根本看不见 —— 那正是「点下去先卡一下才开始动」的来源。
        // 摘要仍然当场刷（不拿过期数据换顺滑），只是范围收敛到这一页；
        // 收键盘 / 掐搜索请求会推动窗口 insets 重新布局，排到这一帧之后，不跟转场首帧抢。
        pager.onPageChanged = { at ->
            refreshPageSummaries(pageNames.getOrNull(at))
            if (at != 0) pager.post { search?.leave() }
        }

        val hub = pageCol(HUB)
        col = hub; pageBack = hub
        hub.addView(TextView(this).apply { text = HUB; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); tag = Brut.KEEP; setPadding(dp(4f), dp(14f), 0, 0) })
        hub.addView(TextView(this).apply {
            text = "v" + BuildConfig.VERSION_NAME.substringBefore("-") + " " + Preset.channel + " · 改动自动保存"
            textSize = 11.5f; setTextColor(Brut.HINT); setPadding(dp(4f), dp(2f), 0, dp(4f)); tag = Brut.KEEP
        })

        // —— 顶部搜索：输入即本地匹配；停手 500ms 自动按意思匹配一次（没配接口 / 配置不全就静默只留本地结果），
        // 点「搜索」或键盘搜索键 = 立刻再发一次 ——
        // 横向拖拽标签挂在外层容器（输入框自己的 tag 留给 Brut.KEEP，两个 tag 只能占一个），
        // 否则在输入框里横拖会被 SwipePager 当成翻页。
        val searchView = SettingsSearchView(
            this, prefs,
            onOpen = { openHit(it) },
            onApi = {
                search?.leave()
                jumpTo(C_AI, G_API)
            },
        )
        searchView.swipeGuard.tag = SwipePager.NO_SWIPE
        search = searchView
        hub.addView(searchView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        // —— 必要配置：悬浮窗 / 接口 ——
        // 缺哪一项，这张卡就摊开、把「去做」按钮摆到面前（首页最显眼的入口）。
        // 两样都配齐后卡整个收起，换成一行摘要（点一下去权限页）—— 不再留一张只会复述「已配好」的大卡，
        // 也不在这里放第二个「启动悬浮球」：启动入口只有下面「常用」卡那一行。
        val needCard = WxSkin.card(this)
        hub.addView(needCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        needCard.addView(TextView(this).apply { text = "必要配置"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK); tag = Brut.KEEP; setPadding(0, dp(8f), 0, 0) })
        val needLine = TextView(this).apply { textSize = 13.5f; setTextColor(Brut.INK); setPadding(0, dp(6f), 0, dp(4f)); setLineSpacing(0f, 1.3f); tag = Brut.KEEP }
        needCard.addView(needLine)
        val needBtn = Button(this).apply { tag = Brut.PRIMARY; isAllCaps = false }
        needCard.addView(needBtn)
        // 配齐后的那一行：卡片不留内边距，行本身就有 ≥56dp 触达（与「常用」卡同一套行）
        val needDoneCard = WxSkin.card(this).apply { setPadding(0, 0, 0, 0) }
        val (needDoneRow, needDoneSum) = WxSkin.row(this, SettingIcon.K_PERM, "必要配置") {
            try { startActivity(PermissionActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开权限页") }
        }
        needDoneCard.addView(needDoneRow, LinearLayout.LayoutParams(-1, -2))
        hub.addView(needDoneCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })

        hubRows.add {
            val overlay = overlayGranted
            val ready = apiReady()
            if (overlay && ready) {
                // 配齐：大卡整块收起，只留一行（权限 / 接口的状态仍看得见，点进去还能重查）
                needDoneSum.text = "悬浮窗 ✓ · 接口 ✓"
                // 行上挂了 contentDescription（WxSkin.row 只写标题），读屏要能听到「都开了」
                needDoneRow.contentDescription = "必要配置：悬浮窗已开 · 接口已填"
                needDoneCard.visibility = View.VISIBLE
                needCard.visibility = View.GONE
            } else {
                needDoneCard.visibility = View.GONE
                needCard.visibility = View.VISIBLE
                needLine.text = "① 悬浮窗权限：" + (if (overlay) "✓ 已开" else "✗ 还没开（没有它小球显示不出来）") +
                    "\n② 接口：" + (if (ready) "✓ " + "已填" else "✗ 还没配好（要自己填）")
                if (!overlay) {
                    needBtn.text = "去开悬浮窗（必需）"
                    needBtn.setOnClickListener {
                        try { startActivity(PermissionActivity.intent(this@MainActivity)) }
                        catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                    }
                } else {
                    needBtn.text = "去填接口（必需）"
                    needBtn.setOnClickListener { jumpTo(C_AI, G_API) }
                }
            }
        }

        /** 首页一张卡片里的几行（行间细线从文字左缘开始，和微信一样） */
        fun hubCard(rows: List<Triple<String, String, () -> Unit>>, sums: List<(() -> String)?>) {
            val card = WxSkin.card(this).apply { setPadding(0, 0, 0, 0) }
            rows.forEachIndexed { i, (icon, title, act) ->
                if (i > 0) card.addView(WxSkin.divider(this, 60f))
                val (r, sum) = WxSkin.row(this, icon, title, act)
                card.addView(r, LinearLayout.LayoutParams(-1, -2))
                sums.getOrNull(i)?.let { f -> hubRows.add { sum.text = f() } }
            }
            hub.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
        }
        // —— 常用：只留底栏到不了的两个入口 ——
        // 待办 / 学习在底栏各有一页、分类页里也能进，摆在这里只是把同一目的地再写一遍，已删；
        // 启动小球（缺权限时必要配置卡只给「去做权限 / 填接口」，不重复它）和历史记录没有底栏入口，留着。
        hubCard(
            rows = listOf<Triple<String, String, () -> Unit>>(
                Triple(SettingIcon.K_START, "启动悬浮球", { onStartClicked() }),
                Triple(SettingIcon.K_HISTORY, "查看历史记录", {
                    try { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } catch (_: Exception) { toast("打不开历史") }
                }),
            ),
            sums = listOf<(() -> String)?>(
                { if (OrbService.running) "运行中" else "未启动" },
                { History.Store(this@MainActivity).list().size.toString() + " 条" },
            ),
        )

        val catIcon = mapOf(C_AI to SettingIcon.K_AI, C_ORB to SettingIcon.K_ORB, C_POP to SettingIcon.K_POP, C_STUDY to SettingIcon.K_STUDY,
            C_LOOK to SettingIcon.K_LOOK, C_DATA to SettingIcon.K_DATA, C_PERM to SettingIcon.K_PERM, C_ABOUT to SettingIcon.K_ABOUT)
        for (grp in listOf(listOf(C_AI, C_ORB), listOf(C_POP, C_STUDY), listOf(C_LOOK), listOf(C_DATA, C_PERM), listOf(C_ABOUT))) {
            hubCard(grp.map { cat -> Triple(catIcon.getValue(cat), cat) { pager.goTo(1 + CATEGORIES.indexOf(cat)) } },
                grp.map { cat -> { catSummary(cat) } })
        }

        // ================= 小球与识屏 › 启动与手势（原「上手与授权」里的启动按钮 + 两条用法说明）=================
        sec(SettingsRegistry.S_ORB_START) { if (OrbService.running) "小球运行中" else "小球未启动" }
        startBtn = Button(this).apply { textSize = 16f; tag = Brut.PRIMARY; setOnClickListener { onStartClicked() } }
        col.addView(startBtn)
        tipD("悬浮球：双击识别屏幕，单击开关答案面板，按住半秒出菜单，拖动挪位置。",
            "悬浮球：双击 = 识别屏幕，单击 = 开/关答案面板，按住半秒 = 菜单（小球外圈走满一圈、手机震一下就弹出），拖动 = 挪位置。选中文字 → 菜单里的「" + Preset.appName + " 翻译」= 翻译；菜单里没有就先复制，再长按小球点「翻译刚复制的」。")
        tipD("追问：答案面板底部有输入框，旁边两个开关 —— 讲简单点 / 讲为什么。",
            "追问：识别完，面板底部有输入框，旁边两个开关 —— 「what」= 讲简单点并举个例子，「why」= 用第一性原理讲为什么；开着就作用于接下来的追问，可以同时开。输入框空着、开关开着直接点「发送」也行。追问带着同一张截图和前面的回答，回答接在正文后面。")

        endGroup()

        // ================= 权限与后台 › 权限（原「上手与授权」的权限入口 + 无障碍状态 + 录屏备用说明）=================
        // 卡片恒展开：授权没做完时它本来就摊开着（原 Sections.locked 的意图在新结构里天然成立）。
        sec(G_AUTH) {
            val a = if (overlayGranted) "悬浮窗 ✓" else "悬浮窗 ✗"
            val b = if (apiReady()) "接口 ✓" else "接口 ✗"
            "$a · $b"
        }
        // v1.90：权限统一收成一个入口 —— PermissionActivity 里按顺序把系统页递到面前，设置页不再逐项重复细节。
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
        // 原「② 接口」那一行与首页「必要配置」卡、「AI 接口与翻译 › 接口与模型」重复，合并掉（见 SettingsRegistry 的 A4）。

        permLine = TextView(this).apply { textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) }
        col.addView(permLine)

        // v1.91：不再单列「无障碍截屏」授权按钮，也不在这里堆「受限设置」长说明 —— 授权统一走上面这一个
        // 权限入口（PermissionActivity），受限设置（Android 13 灰色开关）细节也集中在那页。
        // 这里只留一句录屏备用说明；无障碍的当前状态由 [refreshState] 写在 permLine 上（区分已授权 / 已连上）。
        tipD("没开无障碍截屏也能用：识别时弹一次录屏授权。",
            "无障碍截屏在「权限」页打开后，只要服务连上，识别就不再弹录屏框；开关被关掉或服务没连上时会回落到录屏授权。不想开也行 —— 识别时改用录屏授权，但每次启动都要点一次「立即开始」。")

        endGroup()

        // ================= 权限与后台 › 后台保活 =================
        // ---- 组：保活（原「保活」页，整页搬过来）----
        // v1.91：逐项授权按钮并进「开始使用 → 权限与启动」那一个入口（不再各自重复一遍）；
        // 这里只留状态摘要 + 厂商后台管理入口 + 诊断（恢复次数 / 看门狗 / 品牌提示）。
        sec(G_KEEP) {
            val b = if (KeepAlive.ignoringBattery(this@MainActivity)) "电池 ✓" else "电池 ✗"
            val n = if (KeepAlive.notificationsOn(this@MainActivity)) "通知 ✓" else "通知 ✗"
            val o = if (overlayGranted) "悬浮窗 ✓" else "悬浮窗 ✗"   // 快照：refreshState() 里刚量过
            "$b · $n · $o"
        }
        h("后台保活（建议都开上）")
        tip("这几样系统权限都在上面「权限」卡片一处开：通知、允许后台运行、悬浮窗。开上后 App 被系统清理时会尽力自动恢复，但不保证（能不能回来最终看系统和厂商）。长按小球 →「停止小球（保留授权）」只停掉小球和常驻通知，不会去关系统里的无障碍授权 —— 下次启动不用重新授权。")
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
            else if (prefs.watchdogFails > 0) "⚠ 看门狗闹钟：已排上，但这台手机拦了它 ${prefs.watchdogFails} 次（后台拉起被系统限制）—— 无障碍截屏是后台恢复的重要一层，可在「权限与后台 › 权限」里开上"
            else if (prefs.watchdogKicks > 0) "✓ 看门狗闹钟：已排上（约 15 分钟自查一次，小球被清干净后由它尽力拉回，不保证）"
            else "— 看门狗闹钟：启动小球后自动排上（第四层尽力兜底，平时不用管）"
        }) {
            // v1.65：点一下立即重排闹钟（怀疑被系统清了就手动补一次），行的计数跟着刷新
            WatchdogReceiver.kick(this)
            android.widget.Toast.makeText(this, "已重新排上看门狗闹钟（约 15 分钟后第一次检查）", android.widget.Toast.LENGTH_SHORT).show()
        }
        tipD("各厂商的自启动 / 后台管理入口不一样，点开看怎么找。", KeepAlive.brandTip())
        endGroup()

        // ================= 小球与识屏 › 打游戏时隐藏小球（原在后台保活组里）=================
        sec(SettingsRegistry.S_ORB_HIDE)
        tipD("下拉通知点「隐藏小球」，或加个快捷开关，长按小球菜单里也有。",
            "下拉状态栏 → " + Preset.appName + " 的通知里点「隐藏小球」，再点「显示小球」恢复。\n也可以加快捷开关：下拉状态栏 → 编辑（铅笔图标）→ 把「" + Preset.appName + " 小球」拖进去，以后点它就隐藏 / 恢复，副标题会写「显示中 / 已隐藏」。长按小球 → 菜单里也有「隐藏小球」。")

        endGroup()

        // ================= 数据与同步 › 历史记录（原「数据与更新」组拆成：历史 / 缓存 → 数据与同步；更新 / 重走引导 → 关于与更新）=================
        sec(G_DATA) { History.Store(this@MainActivity).list().size.toString() + " 条" }
        col.addView(toggle("保存历史记录", prefs.saveHistory) { prefs.saveHistory = it })
        historyBtn = Button(this).apply { text = "查看历史记录"; setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) } }
        col.addView(historyBtn)
        tipD("开着时每次识别 / 翻译都存本机，最多 ${History.MAX} 条，只有你能看。",
            "开着时，每次识别的结果（连同追问）和每次翻译都存在本机，最多 ${History.MAX} 条，只有你自己能看。「查看历史记录」或长按小球 → 菜单 →「历史记录…」打开，可以搜索、复制、删除。关掉后不再记新的，已有的不动。")

        endGroup()

        // ================= 数据与同步 › 缓存 =================
        sec(SettingsRegistry.S_DATA_CACHE) { ((Cache.solveStore(this@MainActivity).bytes() + Cache.translateStore(this@MainActivity).bytes() + 1023) / 1024).toString() + " KB" }
        col.addView(toggle("使用结果缓存", prefs.useCache) { prefs.useCache = it })
        cacheLine = TextView(this).apply { textSize = 14f; setPadding(0, dp(4f), 0, dp(2f)) }
        col.addView(cacheLine)
        col.addView(Button(this).apply {
            text = "清空缓存"; tag = Brut.DANGER
            setOnClickListener { Cache.solveStore(this@MainActivity).clear(); Cache.translateStore(this@MainActivity).clear(); refreshCacheLine(); toast("缓存已清空") }
        })
        tipD("同一画面 / 同一段文字再问一次直接用上次结果：不调接口、不花额度。",
            "同一个画面、同样的模式和设置再识别一次，或者同一段文字再翻译一次，直接用上次的结果：不调接口、不花额度、马上出来，面板上会写「↺ 缓存」。自动扫描时画面没变也不会重复花钱。只认一模一样的画面（状态栏的时间变了不算），宁可重新问也不会拿错答案。想让同一画面重新问，就点上面「清空缓存」（或关掉缓存）再双击小球。识别留 ${Cache.SOLVE_MAX} 条、翻译留 ${Cache.TRANSLATE_MAX} 条，7 天后自动过期。")

        endGroup()

        // ================= 关于与更新 › 版本 =================
        sec(SettingsRegistry.S_ABOUT_VER)
        col.addView(TextView(this).apply {
            text = Preset.appName + " v" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）· " + Preset.channel
            textSize = 15f; setPadding(0, dp(12f), 0, dp(4f))
        })
        tip("同渠道覆盖安装即可升级，聊天记录、词库进度、设置都保留；公测版和内测版是两个独立的 App。")
        endGroup()

        // ================= 关于与更新 › 检查更新 =================
        // v1.65 更新通道：填一个清单地址（任意静态托管的 manifest.json），每 24 小时自动查一次，发现新版给一个下载按钮
        sec(SettingsRegistry.S_ABOUT_UPDATE) { if (prefs.updateUrl.isBlank()) "内置更新源" else "自填清单地址" }
        updateRes = TextView(this).apply { textSize = 13f; setPadding(0, dp(4f), 0, 0) }
        updateDlBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { currentManifest?.let { startUpdateDownload(it) } }
        }
        // 开源构建不内置任何更新源：清单地址由使用者自己填；空着时更新功能自己说「未配置」。
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
        // 更新清单地址是低频项（局域网 / 自建源才用）：收进高级
        beginAdvanced()
        field("更新清单地址（留空 = 用内置的网络更新源；填了就以你填的为准）",
            prefs.updateUrl,
            "留空 = 用内置的网络更新源；填了就以你填的为准。检查更新失败时先看下面那行提示里的「当前地址」") { prefs.updateUrl = it }
        endAdvanced()
        if (Update.shouldAutoCheck(prefs.updateLastCheck, System.currentTimeMillis(), prefs.updateUrl)) checkUpdate(manual = false)
        endGroup()

        // ================= 关于与更新 › 重走首次配置 =================
        sec(SettingsRegistry.S_ABOUT_SETUP)
        col.addView(Button(this).apply { text = "再走一遍首次配置引导"; setOnClickListener { prefs.onboarded = false; prefs.onboardStarted = true; prefs.onboardSkipped = emptySet(); showSetup() } })
        tip("一步一页把权限、接口、保活重新过一遍（已经开好的会直接打勾）。")
        endGroup()

        // ================= 分类 6：接口与模型（接口 + 翻译方案）=================

        // ---- 组：接口（原「接口」页）----
        sec(G_API) {
            val a = prefs.api()
            if (a.baseUrl.isBlank()) "还没填接口地址"
            else a.baseUrl.removePrefix("https://").removePrefix("http://")
        }
        // 当前方案状态行：说清现在用内置还是自己填的、配好没配好。
        // 注意：「填了」不等于「连得上」—— 能不能用请看下面「检测连接」，这里绝不写「已连接」。
        // KEEP：底色是主色块，字按主色选可读色（默认配色 = 墨色，零变化），别被 Brut.style 刷成墨色
        val apiStat = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.on(Brut.MINT)); setPadding(dp(12f), dp(10f), dp(16f), dp(14f)); tag = Brut.KEEP }
        col.addView(apiStat, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        apiStat.background = WxSkin.rounded(Brut.MINT, this, 10f)

        var baseEd: EditText? = null; var keyEd: EditText? = null; var modelEd: EditText? = null   // v1.65 接口方案切换时刷新输入框
        // 接口 / 搜索 / 思考强度三块会互相影响（切方案要一起换），先把刷新入口留出来，建到后面再赋值
        var refreshApFn: () -> Unit = {}
        var refreshSearchUi: () -> Unit = {}
        var refreshThinkingUi: () -> Unit = {}
        // 手动改接口 = 上一次「检测连接」的结果作废；方案 chip 的「已改」标记也要跟着变
        fun apiEdited() { clearTestResult(); refreshApFn() }
        // v1.90：显式「自行填写接口」折叠 —— 一个按钮切换下面三项的可见性（不是靠整组隐藏）；
        // 有内置也保留（想覆盖内置时用），没内置且缺配置时默认展开。
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
        fun refreshPreset() {
            apiStat.text = when {
                !apiReady() -> "还没配好 —— 用自己的 AI 接口（要能看图），展开「自行填写接口」填好，再点下面「检测连接」。"
                apiReady() -> "现在用你自己填的接口 · 模型 " + prefs.api().model.ifBlank { "未填" } + "\n（填好只说明配置齐了，能不能用请点下面「检测连接」）"
                else -> "还没填接口地址"
            }
        }
        var refreshPresetFn: () -> Unit = { refreshPreset() }
        // 自填区（临时把 col 指到 editBox，画完退回接口组内容那一层）
        col = editBox
        field("接口地址", prefs.baseUrl, "https://api.deepseek.com/v1") { prefs.baseUrl = it; refreshPreset(); apiEdited() }.also { baseEd = it }
        field("密钥", prefs.apiKey, "sk-…（英文叫 API Key）", password = true) { prefs.apiKey = it; apiEdited() }.also { keyEd = it }
        field("模型", prefs.model, "要支持看图，比如 deepseek-vl / glm-4v / qwen-vl") { prefs.model = it; refreshPreset(); apiEdited() }.also { modelEd = it }
        col = apiHost
        refreshPreset()
        applyEdit()

        // v1.65 接口方案：自己填的接口存成多套（主力 / 备用 / 便宜模型），点方案名整组切换，长按删除
        // v1.92：一套方案 = 地址 / Key / 模型 / 思考强度 / 联网搜索配置；激活的是哪一套用 activeProfileId 明确记。
        fun chip(label: String, act: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false
            setPadding(dp(10f), dp(4f), dp(10f), dp(6f))
            setOnClickListener { act() }
        }
        val apBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, 0) }
        // 这几个动作互相调用（切换 → 问保存 → 保存 → 再切换），先用变量占位，赋值在后面
        var switchTo: (ApiProfiles.Profile) -> Unit = {}
        var applyProfile: (ApiProfiles.Profile) -> Unit = {}
        var askSaveName: ((() -> Unit)?) -> Unit = { _ -> }
        var saveCurrent: (String, String, (() -> Unit)?) -> Unit = { _, _, _ -> }
        fun refreshAp() {
            apBar.removeAllViews()
            val list = prefs.customApis
            val activeId = prefs.activeProfileId
            val dirty = prefs.tempDirty()
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (p in list) {
                // 激活态只看显式 id；真正改脏了才标 ✎ —— 不再用「地址 / 模型碰巧相同」来猜
                val mark = when {
                    p.id != activeId -> ""
                    dirty -> "✎ "
                    else -> "✓ "
                }
                val b = chip(mark + p.label) { switchTo(p) }
                b.setOnLongClickListener {
                    val isActive = p.id == prefs.activeProfileId
                    ThemeDialogs.builder(this)
                        .setTitle("删掉方案「${p.label}」？")
                        .setMessage(if (isActive) "这是当前使用中的方案。删除后，现在填写的地址 / Key / 模型会原样保留，变成未保存的临时配置；小活路由也会取消这套。"
                                   else "只删这套存着的方案，当前填写不变。")
                        .setPositiveButton("删") { _, _ ->
                            val wasActive = prefs.deleteApiProfile(p.id)
                            refreshAp()
                            if (wasActive) toast("已删除。当前填写保留为临时配置")
                        }
                        .setNegativeButton("先不了", null).show()
                    true
                }
                row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6f) })
            }
            row.addView(chip("＋ 存为方案") { askSaveName(null) }, LinearLayout.LayoutParams(-2, -2))
            apBar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            apBar.addView(TextView(this).apply {
                text = when {
                    list.isEmpty() -> "把现在填的接口存成方案，以后一点就换（长按方案可删除）"
                    activeId.isEmpty() && dirty -> "当前是未保存的临时配置 —— 点「＋ 存为方案」可以存下来"
                    dirty -> "当前方案有改动（✎）—— 切换 / 删除前会问你要不要保存"
                    else -> "点方案名 = 地址 / Key / 模型 / 思考强度 / 搜索设置整组换；长按 = 删除"
                }
                textSize = 12f; setPadding(dp(2f), dp(4f), 0, 0)
            })
            if (apBar.isAttachedToWindow) WxSkin.style(apBar)
        }
        applyProfile = { p ->
            prefs.applyApiProfile(p)
            setQuiet(baseEd, p.baseUrl); setQuiet(keyEd, p.apiKey); setQuiet(modelEd, p.model)   // 程序化回填：不出「已保存」
            clearTestResult()
            refreshPresetFn(); refreshAp(); refreshSearchUi(); refreshThinkingUi()
            toast("已切换到方案「${p.label}」")
        }
        switchTo = { p ->
            if (prefs.tempDirty() && !prefs.currentMatches(p)) {
                val active = prefs.activeProfile()
                ThemeDialogs.builder(this)
                    .setTitle("当前接口改动还没保存")
                    .setMessage(if (active != null) "现在填的和「${active.label}」不一样。直接切换会丢掉这些改动。"
                                else "现在填的接口还没存成方案。直接切换会丢掉这些改动。")
                    .setPositiveButton("保存后切换") { _, _ ->
                        // 保存可能更新了目标方案本身（点的就是当前这套）：切之前按 id 重读一次，别把刚存的改动丢掉
                        askSaveName {
                            applyProfile(prefs.customApis.firstOrNull { it.id == p.id } ?: p)
                        }
                    }
                    .setNeutralButton("不保存，直接切换") { _, _ -> applyProfile(p) }
                    .setNegativeButton("取消", null).show()
            } else applyProfile(p)
        }
        askSaveName = { after ->
            val active = prefs.activeProfile()
            val input = EditText(this).apply {
                setText(active?.label ?: ""); hint = ApiProfiles.newName(prefs.customApis); setSingleLine()
            }
            ThemeDialogs.builder(this)
                .setTitle(if (active != null) "保存当前接口（更新「${active.label}」或换个名字另存）"
                         else "把当前接口存为方案（地址 / Key / 模型 / 思考强度 / 搜索设置一起存）")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val name = input.text.toString().trim()
                    // 名字没改 = 更新当前方案（保留真实 id）；改了名 = 另存新方案
                    val keepId = if (active != null && name == active.label) active.id else ""
                    saveCurrent(name, keepId, after)
                }
                .setNegativeButton("取消", null).show()
        }
        saveCurrent = { label, overwriteId, after ->
            val r = prefs.saveApiProfile(label, overwriteId)
            val clash = r.conflict
            val err = r.error
            if (err != null) {
                // 保存被拒（地址空白）：错误写在保存区这一行，且**绝不调用 after** —— 没存成就不能接着切方案
                testRes.text = "⚠ " + err
            } else if (clash != null) {
                // 同名不静默覆盖：必须用户明确点「覆盖」，并且更新的是那套方案的真实 id
            ThemeDialogs.builder(this)
                    .setTitle("已有同名方案")
                    .setMessage("「${clash.label}」已经存在。覆盖它会用当前填写替换那套方案。")
                    .setPositiveButton("覆盖") { _, _ ->
                        val again = prefs.saveApiProfile(clash.label, clash.id)
                        val err2 = again.error
                        if (err2 != null) testRes.text = "⚠ " + err2
                        else {
                            again.profile?.let { toast("已更新方案「${it.label}」") }
                            refreshAp(); refreshPresetFn()
                            after?.invoke()
                        }
                    }
                    .setNegativeButton("换一个名字") { _, _ -> askSaveName(after) }
                    .show()
            } else {
                r.profile?.let { toast("已保存方案「${it.label}」") }
                refreshAp(); refreshPresetFn()
                after?.invoke()
            }
        }
        refreshApFn = { refreshAp() }
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
        col.addView(toggle("让 Agent 记住聊过的事", prefs.agentMemOn) { prefs.agentMemOn = it })
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
                ThemeDialogs.builder(this@MainActivity)
                    .setTitle("当前 Agent 的长期记忆")
                    .setMessage(msg)
                    .setPositiveButton("清空") { d, _ -> memStore.clear(); toast("已清空"); d.dismiss() }
                    .setNegativeButton("关", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        h("思考强度")
        // 跟着方案一起存 / 一起换，所以要能重建（否则切完方案界面还停在旧档位）
        val thinkingBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(thinkingBox)
        fun renderThinking() {
            thinkingBox.removeAllViews()
            val back = col; col = thinkingBox
            radios(Prompts.THINK_EFFORTS.map { it to Prompts.THINK_LABEL.getValue(it) + if (it == "high") "（默认）" else "" }, prefs.thinking) {
                prefs.thinking = it
                clearTestResult(); refreshApFn()
            }
            col = back
            if (thinkingBox.isAttachedToWindow) WxSkin.style(thinkingBox)
        }
        renderThinking()
        refreshThinkingUi = { renderThinking() }
        tip("高：想得更久更准，首字更慢；关：最快。默认高，和联网搜索各自独立保存。只有 DeepSeek 系列接口会收到这个参数；别家接口不会发送思考设置（不会硬塞一个它们不认识的值）。")

        endAdvanced()
        endGroup()

        // ---- 组：联网搜索（v1.92；配置跟接口方案一起存 / 一起切，默认关）----
        fun searchProviderLabel(id: String): String = when (id) {
            WebSearch.DEEPSEEK -> "DeepSeek 官方搜索"
            WebSearch.TAVILY -> "Tavily"
            WebSearch.SEARXNG -> "SearXNG（自建）"
            else -> id
        }
        sec(G_SEARCH) {
            // v2.2.1：首页摘要直接用运行时那套判定（[WebSearch.statusLine]），不再自己另写一套文案 ——
            // 以前这里只写「开 · DeepSeek 官方搜索」，子页却说「还差搜索 Key」，两处互相矛盾。
            WebSearch.statusLine(prefs.searchConfig, prefs.api())
        }
        val searchStat = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12f), dp(10f), dp(16f), dp(14f)); tag = Brut.KEEP }
        col.addView(searchStat, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        val searchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(searchBox)

        fun refreshSearchStat() {
            val c = prefs.searchConfig
            val api = prefs.api()
            // 一句话说清「开没开 / 用哪家 / 配没配全」，和运行时同一个判定函数
            val txt = WebSearch.statusLine(c, api)
            // 只有真的能搜（开关开 + 配置齐）才点亮，配不齐就保持普通底色，别让人以为已经通了
            val on = WebSearch.usable(c, api)
            val fill = if (on) Brut.MINT else WxSkin.FIELD
            searchStat.text = txt
            searchStat.setTextColor(Brut.on(fill))
            searchStat.background = WxSkin.rounded(fill, this, 10f)
            // v2.2.1：分组标题右侧那条摘要（「关（回答只靠模型自己知道的事）」）只在整块重建时才更新。
            // 以前改开关 / 换来源 / 填 Key 只重建卡片正文，标题会停在旧的「关」，正文却写着「开着 · …」——
            // 正是这次要修的「看不出到底开没开」。摘要和正文共用 statusLine，这里顺手一起刷。
            groupBoxes[G_SEARCH]?.render()
        }
        fun renderSearch() {
            searchBox.removeAllViews()
            val c = prefs.searchConfig
            val back = col
            col = searchBox
            col.addView(toggle("开启联网搜索", c.enabled) {
                prefs.searchConfig = prefs.searchConfig.copy(enabled = it)
                renderSearch()
            })
            tip("默认关：回答只靠模型自己知道的事。打开后，需要实时信息时会把搜索词发到下面选的服务 —— " +
                "识屏、追问、和小Ling 聊天这三处都生效；搜过会在答案末尾补一行「已联网 · N 条来源」。搜索配置跟着当前接口方案保存。")
            col.addView(Button(this).apply {
                text = "一键启用推荐：DeepSeek 官方搜索"; isAllCaps = false
                setOnClickListener {
                    // 推荐方案：开、DeepSeek、官方固定接口；不抄模型 Key、不打开 Tavily 增强
                    prefs.searchConfig = ApiProfiles.recommendedSearchConfig(prefs.searchConfig)
                    renderSearch()
                }
            })
            tipD("推荐用 DeepSeek 官方搜索：和模型同一家，不用另装搜索服务。",
                "推荐用 DeepSeek 官方搜索，地址由 App 固定为官方接口。Key 只在当前接口恰好是 https://api.deepseek.com（HTTPS）时复用模型 Key；" +
                "换过地址、不是 HTTPS，或用的是别的服务，就必须单独填搜索 Key —— 不会靠「网址里有没有 deepseek 字样」或模型名去猜，免得把 Key 发给别人的服务器。" +
                "点这个按钮会打开联网搜索、来源选 DeepSeek，但不会把模型 Key 抄进搜索配置，也不会替你打开 Tavily 增强。")

            radios(
                listOf(
                    WebSearch.DEEPSEEK to "DeepSeek 官方（推荐）",
                    WebSearch.TAVILY to "Tavily（要 Key）",
                    WebSearch.SEARXNG to "SearXNG（自建）",
                ), c.provider
            ) { id ->
                // 换来源时把「增强」关掉：增强只属于 Tavily，不能跟着别的来源残留
                prefs.searchConfig = prefs.searchConfig.copy(provider = id, enhance = false)
                renderSearch()
            }

            when (c.provider) {
                WebSearch.DEEPSEEK -> {
                    col.addView(TextView(this).apply {
                        text = if (prefs.searchKeyReusable()) "✓ 当前接口是官方 https://api.deepseek.com，搜索会复用这条接口的 Key，不用另填。"
                               else "当前接口不是官方 https://api.deepseek.com（或不是 HTTPS），要单独填搜索 Key；不会拿模型名或网址片段去猜。"
                        textSize = 12.5f; alpha = 0.8f; setPadding(0, dp(4f), 0, 0)
                    })
                    field("DeepSeek 搜索 Key" + when {
                        c.deepseekKey.isNotBlank() -> "（已填）"
                        prefs.searchKeyReusable() -> "（可留空，复用模型 Key）"
                        else -> "（必填）"
                    }, c.deepseekKey, "sk-…（只存在本机）", password = true) {
                        prefs.searchConfig = prefs.searchConfig.copy(deepseekKey = it.trim())
                        refreshSearchStat()   // 只刷状态行：整块重建会打断正在输入的输入框
                    }
                    // v2.2.1：格式软提示。不拦、不自动改，只提醒一句 —— 填错的 Key 以前要等到真实识别才暴露
                    if (c.deepseekKey.isNotBlank() && !c.deepseekKey.startsWith("sk-")) {
                        col.addView(TextView(this).apply {
                            text = "⚠ 官方 DeepSeek 的 Key 通常以 sk- 开头，先确认没贴错（比如贴成了 Tavily 的 tvly-…）"
                            textSize = 12f; setPadding(0, dp(2f), 0, 0)
                        })
                    }
                }
                WebSearch.TAVILY -> {
                    col.addView(TextView(this).apply {
                        text = "官方地址已预填：" + WebSearch.TAVILY_URL + "（不用改）"
                        textSize = 12.5f; alpha = 0.8f; setPadding(0, dp(4f), 0, 0)
                    })
                    field("Tavily API Key（必填）", c.tavilyKey, "tvly-…（只存在本机）", password = true) {
                        prefs.searchConfig = prefs.searchConfig.copy(tavilyKey = it.trim())
                        refreshSearchStat()
                    }
                    if (c.tavilyKey.isNotBlank() && !c.tavilyKey.startsWith("tvly-")) {
                        col.addView(TextView(this).apply {
                            text = "⚠ Tavily 的 Key 通常以 tvly- 开头，先确认没贴错"
                            textSize = 12f; setPadding(0, dp(2f), 0, 0)
                        })
                    }
                    // 只有用户自己点开才会开；选 Tavily / 填 Key 都不会自动打开
                    col.addView(toggle("用 Tavily 增强搜索", c.enhance) {
                        prefs.searchConfig = prefs.searchConfig.copy(enhance = it)
                        refreshSearchStat()
                    })
                    tip("增强默认关：打开后 Tavily 会多取一些网页内容再交给模型，更准但更慢、也更费额度。只有你亲手打开才生效。")
                    radios(WebSearch.TAVILY_DEPTHS.map { it to if (it == "advanced") "深度（更准更慢）" else "标准（默认）" }, c.depth) {
                        prefs.searchConfig = prefs.searchConfig.copy(depth = it)
                    }
                }
                WebSearch.SEARXNG -> {
                    field("SearXNG 实例地址", c.searxngBase, "https://你的实例（要带 http:// 或 https://）") {
                        prefs.searchConfig = prefs.searchConfig.copy(searxngBase = it.trim())
                        refreshSearchStat()
                    }
                    WebSearch.userUrlIssue(c.searxngBase)?.let { issue ->
                        col.addView(TextView(this).apply { text = "⚠ $issue"; textSize = 12.5f; setPadding(0, dp(4f), 0, 0) })
                    }
                    tip("SearXNG 是自建 / 公共实例，免 Key；地址不对时搜索会明确失败，不会假装搜到了。")
                }
            }
            col.addView(TextView(this).apply {
                text = "搜索服务的 Key 只存在本机，不会写进日志或缓存。"
                textSize = 12f; alpha = 0.7f; setPadding(0, dp(6f), 0, 0)
            })

            // ---- v2.2.1 测试搜索：配没配通，在这里就能知道，不必等真实识别时碰运气 ----
            // 以前只有「检测接口」按钮（ping + 看图），完全没有测搜索的入口；Key 填错了要等到模型
            // 恰好决定联网、而且真的调用工具时才暴露，用户一直以为配置是好的。
            val probeRes = TextView(this).apply {
                textSize = 12.5f; setPadding(0, dp(4f), 0, 0); tag = Brut.KEEP
                visibility = View.GONE
            }
            col.addView(Button(this).apply {
                text = "测试搜索（真实发一次请求）"; isAllCaps = false
                setOnClickListener { btn ->
                    val cfgNow = prefs.searchConfig
                    val apiNow = prefs.api()
                    probeRes.visibility = View.VISIBLE
                    probeRes.text = "测试中…（会真的发一次搜索请求，按服务方计费）"
                    btn.isEnabled = false
                    thread {
                        val s = WebSearch.session(cfgNow, apiNow)
                        val msg = try {
                            if (!cfgNow.enabled) "还没打开联网搜索：先把上面的开关打开再测"
                            else WebSearch.probeResult(s.search("今天日期", null))
                        } catch (e: Exception) {
                            "✗ " + WebSearch.friendly(e)   // 文案里不含 Key
                        }
                        runOnUiThread { probeRes.text = msg; btn.isEnabled = true }
                    }
                }
            })
            tipD("用当前配置真发一次搜索，看配没配通。",
                "点一下会拿电脑/手机现在的配置向所选服务发一次真实搜索（关键词固定为「今天日期」）。" +
                "成功会显示搜到几条和第一条标题；Key 不对会说「Key 不对或没权限」；地址不通会说连不上。" +
                "每次点都会产生一次计费请求（官方 DeepSeek 约 $0.75/千次），不要连点。")
            col.addView(probeRes)
            col = back
            if (searchBox.isAttachedToWindow) WxSkin.style(searchBox)
            refreshSearchStat()
        }
        renderSearch()
        refreshSearchUi = { renderSearch() }
        endGroup()

        // ================= 分类 2：小球与识别（识别模式）=================

        // ---- 组：模式（原「模式」页：改的是「怎么问模型」）----
        sec(G_MODES) {
            val m = prefs.mode
            Modes.label(m, prefs.customModes) + " · " + Prompts.DETAIL_LABEL.getValue(prefs.detail)
        }
        modesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modesBox)
        rebuildModes()

        h("做题解析强度")
        radios(Prompts.DETAILS.map { it to Prompts.DETAIL_LABEL.getValue(it) }, prefs.detail) { prefs.detail = it }
        tip("简略：只点关键一步；标准：分步讲、选择题说明错项；详细：不跳步、逐个分析选项，再给同类题通用解法。")
        col.addView(toggle("纯小白讲解", prefs.novice) { prefs.novice = it })
        tip("打开后用大白话讲：术语先解释、每步都写出来、尽量打比方。通用模式也生效。")

        // v1.66：原「通识」模式的名词块并进所有模式，这里一个总开关（默认开）
        col.addView(toggle("识别关键词", prefs.keywords) { prefs.keywords = it })
        tipD("所有模式回答下面多一排关键词，点哪个就在正文后面解释哪个。",
            "所有模式的回答下面多一排关键词（型号、规格参数、品牌、术语，比如 R7250、P3 广色域、Wi-Fi 7），点哪个就在正文后面解释哪个；解释是一次追问，答案接在正文后面，还能继续追问。")
        endGroup()

        // ---- 组：翻译（原「翻译」页，并进接口与模型分类：改的是「怎么翻译」）----
        sec(G_TRANSLATE) {
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

        // ================= AI 接口与翻译 › 语音转写 =================
        // 麦克风实时转写（豆包 / 阿里云）：只做「说话 → 出字」。两家各存各的 Key（Keystore 密文），
        // 这一页不重贴 Key、也不显示明文 —— 全部在 VoiceActivity 里选服务商、填 Key、看文字。
        sec(SettingsRegistry.S_VOICE) {
            VoiceProvider.label(VoiceSettings(this).selectedProvider).substringBefore("（")
        }
        col.addView(Button(this).apply {
            text = "实时转写（说话 / 系统声音 → 文字）"
            tag = Brut.PRIMARY
            setOnClickListener {
                try { startActivity(VoiceActivity.intent(this@MainActivity)) }
                catch (_: Exception) { toast("打不开语音转写") }
            }
        })
        tipD("声音来源三档：麦克风 / 系统声音 / 混录 —— 在实时转写页里选。",
            "进入后先选服务商（豆包 / 阿里云），两家各用各自的 API Key，都只存在本机（AndroidKeyStore AES-256-GCM 加密，" +
                "不进系统备份、不写日志、不回显明文）。第一次开始前要你确认「声音会实时上传到所选服务商」。\n" +
                "声音来源在那一页选：麦克风 / 系统声音 / 麦克风 + 系统声音（混录）。后两档要走系统播放捕获，需要 Android 10（API 29）起、" +
                "并且播放方允许被捕获 —— 开始时会弹一次系统确认；DRM 保护的内容和部分会议 App 抓不到，不承诺会议两边的人声都能录进来。" +
                "本机不支持的那一档会写清原因，绝不悄悄退回麦克风。\n" +
                "文字只在那一页里、只在内存中：不写文件、不进历史记录、不参与同步，离开页面即清空。" +
                "采集期间会挂一条前台通知（Android 要求采集时前台服务必须可见），通知里可以随时停止；" +
                "换服务商或删 Key 都会先停下当前这一条，不让声音半路换家。")
        endGroup()

        // ---- 语音对话（v2.8）：麦克风 → 腾讯实时识别 → 现有模型 → 腾讯语音合成 → 扬声器 ----
        // 与上面那条「实时转写」是**两条独立链路**：那边只出字（豆包 / 阿里），这边能说话（腾讯），
        // 凭据分开加密存放、互不影响；腾讯不进转写的服务商选择器（见 VoiceProvider.KEYED 的说明）。
        sec(SettingsRegistry.S_VOICE_TALK) {
            // 摘要只读非密参数：这里**不去解密 Keystore**（摘要在主线程上算，一次解密就是一次磁盘 IO）。
            // 「凭据存了没有」在语音对话页里如实显示。
            val s = LiveTalkSettings(this)
            "音色 " + s.voiceType + " · " + TencentVoice.speedLabel(s.speed)
        }
        col.addView(Button(this).apply {
            text = "语音对话（说话 → 回答 → 放出来）"
            tag = Brut.PRIMARY
            setOnClickListener {
                try { startActivity(LiveTalkActivity.intent(this@MainActivity)) }
                catch (_: Exception) { toast("打不开语音对话") }
            }
        })
        tipD("要自己填腾讯云的三项凭据；声音会同时发给腾讯和当前大模型。",
            "整条链路：你的声音实时上传到**腾讯云**转成文字 → 文字发给**你现在配的那个大模型** → " +
                "它答的话发给**腾讯云**合成语音 → 从手机扬声器放出来。三处都在你自己的账号里计费" +
                "（腾讯识别按音频时长、腾讯合成按字符、大模型按 token）。\n" +
                "需要先填腾讯云的 AppId / SecretId / SecretKey（三项一起），它们和转写那两家的 Key 分开、" +
                "同样用 AndroidKeyStore AES-256-GCM 加密，不进系统备份、不写日志、不回显明文。\n" +
                "全双工：回答还在说的时候你也可以直接开口，它会立刻停下正在生成和播放的内容，改听你说。" +
                "回声消除用了通话音频通路 + 系统的 AcousticEchoCanceler，但**没有在任何真机上验证过实际效果**，" +
                "外放时如果它把自己的声音识别成你的话，请戴耳机。\n" +
                "本机不存音频、不存对话文字、不进聊天历史；离开这一页（返回 / 切走）就停掉麦克风、识别、" +
                "模型请求和播放。这一版没有为它配前台服务，所以不会在后台继续听。")
        endGroup()

        // ================= 分类 5：外观与阅读（配色与字号 + 自定义中心）=================

        // ---- 组：配色（原「外观」页 + 面板正文字号）----
        sec(G_COLOR) {
            val cur = Theme.normId(prefs.themeId)
            if (cur == Theme.CUSTOM) "自定义配色" else themeLabel(cur, Theme.PRESETS.firstOrNull { it.first == cur }?.second ?: cur)
        }
        buildTheme(col, { h(it) }, { tip(it) })
        // v1.66：面板正文字号从面板上的 A− / A+ 挪到设置页（功能不变，12~22 sp，面板开着也立刻生效）。
        // v1.90：从「面板与小球」并进「外观与阅读」—— 字号是阅读相关的外观项。
        endGroup()

        // ================= 外观 › 面板文字大小（原在「配色与字号」组里）=================
        sec(SettingsRegistry.S_LOOK_TEXT) { prefs.textSp.toString() + " sp" }
        slider(12, 22, prefs.textSp, 1, { "面板正文字号：$it sp" + if (it == 15) "（默认）" else "" }) { prefs.textSp = it }
        tip("只改答案面板正文的字号；答案行、按钮跟着系统字号走。")
        endGroup()

        // ================= 分类 2 续：小球与识别（面板与小球）=================

        // ---- 组：面板与小球（原「面板 / 小球」页）----
        sec(G_PANEL) {
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
            if (panelBox.isAttachedToWindow) WxSkin.style(panelBox)
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
            if (orbBox.isAttachedToWindow) WxSkin.style(orbBox)
        }
        orbSizeRefresh?.invoke()
        tip("拖动时小球立刻变大变小。小一点不挡字，大一点好按。")
        // —— 高级：贴边 / 闲置缩进 / 闲置透明度。默认就好用，按需展开 ——
        beginAdvanced()
        col.addView(toggle("离屏幕边远一点（躲开返回手势）", prefs.orbFar) { prefs.orbFar = it })
        tip("关（默认）：小球贴近屏幕边（只留一点缝），按小球不会被当成返回手势。开：让出整条返回手势区，离边远一些。")
        col.addView(toggle("闲置时缩进屏幕边一半", prefs.orbTuck) { prefs.orbTuck = it })
        tip("闲置时小球往屏幕边缘缩进一半，减少遮挡。")
        val idleLabel = TextView(this).apply { textSize = 15f; setPadding(0, dp(10f), 0, 0) }
        fun idleText(v: Int) = "闲置时不透明度：$v%（越小越透明）"
        idleLabel.text = idleText(prefs.orbIdleAlpha)
        col.addView(idleLabel)
        col.addView(saveSeek(70, prefs.orbIdleAlpha - 10, { p -> "闲置时不透明度 " + (10 + p) + "%" }) { p ->
            prefs.orbIdleAlpha = 10 + p; idleLabel.text = idleText(10 + p)
        })
        tip("3 秒不碰，小球缩小到 6 成并变淡（开着上面的开关还会藏进屏幕边一半）；一碰就恢复原样。")
        endAdvanced()
        endGroup()

        // ---- 组：自动扫描（原 data 组里混着的扫描项，拆到这里：它是「怎么识别」的一部分）----
        sec(G_SCAN) {
            "间隔 " + prefs.intervalSec + "s" + (if (prefs.scanPauseOnStill) " · 画面不变自动暂停" else "")
        }
        tipD("长按小球 → 菜单里可开自动扫描，也能用 − / + 调间隔（5~3600 秒）。",
            "长按小球 → 菜单里也能开自动扫描、能用 − / + 调间隔。自动扫描时每一轮先看画面有没有变：变了才调接口，一模一样就直接跳过、连缓存都不查；没变够多次会自动停下来（下面两项）。")
        h("自动扫描间隔（秒）")
        col.addView(numberField("自动扫描间隔（秒）", prefs.intervalSec) { prefs.intervalSec = it })
        tip("5 ~ 3600 秒。")
        // v1.70 自动扫描省钱：忘了关 / 题目停在一页不动时，别整晚空烧
        col.addView(toggle("画面一直不变时自动暂停", prefs.scanPauseOnStill, topDp = 12f) { prefs.scanPauseOnStill = it })
        col.addView(TextView(this).apply { text = "连续多少轮没变化就暂停"; textSize = 15f; setPadding(0, dp(2f), 0, dp(2f)) })
        col.addView(numberField("连续多少轮没变化就暂停", prefs.scanStillRounds) { prefs.scanStillRounds = it })
        tipD("连续多少轮画面一模一样就自动暂停（${AutoScanPolicy.STILL_ROUNDS_MIN}~${AutoScanPolicy.STILL_ROUNDS_MAX}，默认 ${AutoScanPolicy.DEFAULT_STILL_ROUNDS}）。",
            "连续多少轮画面一模一样就自动暂停（${AutoScanPolicy.STILL_ROUNDS_MIN}~${AutoScanPolicy.STILL_ROUNDS_MAX}，默认 ${AutoScanPolicy.DEFAULT_STILL_ROUNDS}）。暂停时面板上那行字可以点，也可以下拉通知点一下继续，双击小球也算继续。屏幕一熄就停、解锁自动继续 —— 息屏了还扫就是纯烧钱。\n另外自动扫描开着时，面板状态行会写「自动扫描 · 12s 后」，下一轮什么时候来一眼看得见；扫描中的文案不变。")
        endGroup()

        // ================= 分类 5 续：外观与阅读（自定义中心）=================

        // ---- 组：自定义中心（原「其他」页的自定义入口）----
        sec(G_CUSTOM) { "称呼、语气、口头禅等 ${Customize.CATALOG.size} 项" }
        col.addView(Button(this).apply {
            text = "自定义中心"
            setOnClickListener { try { startActivity(CustomizeActivity.intent(this@MainActivity)) } catch (e: Exception) { toast("打不开自定义中心") } }
        })
        tipD("称呼、语气、口头禅、字号、快捷短语……全在「自定义中心」里改，改坏了能逐项恢复。",
            "称呼、语气、口头禅、小球大小、面板字号、快捷短语……全都在这儿改。每一项都有默认值，不改也能用；改坏了在那一项的编辑页底部点「恢复这一项」。多数项也能从小球菜单或面板上长按直接进。")
        // v2.4：长按菜单的排列 —— 哪些项显示、什么顺序
        sec(G_CUSTOM) { val n = OrbMenu.resolve(prefs.orbMenuOrder, prefs.orbMenuHidden).size; "长按菜单 · 显示 ${n} 项" }
        col.addView(Button(this).apply {
            text = "长按小球的菜单"
            setOnClickListener { try { startActivity(OrbMenuActivity.intent(this@MainActivity)) } catch (e: Exception) { toast("打不开菜单设置") } }
        })
        // v2.4 C 档：模块管理 —— 决定 App 里有哪些功能
        sec(G_CUSTOM) { "已关 " + prefs.disabledFeatures.size + " 个模块" }
        col.addView(Button(this).apply {
            text = "模块管理"
            setOnClickListener { try { startActivity(ModulesActivity.intent(this@MainActivity)) } catch (e: Exception) { toast("打不开模块管理") } }
        })
        tipD("决定 App 里显示哪些功能模块。",
            "关掉的模块不显示入口，但数据一条不删 —— 重新打开还在。" +
            "\n「学习」固定开启：知识卡、复习、计划都挂在它下面，关掉等于把主用途卸掉。")
        tipD("决定长按小球时弹出哪些项、按什么顺序。",
            "长按小球会弹出一列快捷操作。这里可以调它们的先后顺序，也可以把用不到的项隐藏掉；「和小Ling聊聊」「设置」「停止小球」三项固定保留 —— 藏起来的话你可能再也找不到设置、也关不掉小球，所以不给隐藏。")
        endGroup()

        // ================= 分类 3：背单词 =================

        // ---- 组：弹词设置（原「背单词」页上半：开关 + 间隔 + 夜里不弹）----
        sec(G_WORDS_POP) {
            (if (prefs.wordOn) "每 ${Prefs.fmtInterval(prefs.wordEverySec)}弹一次" else "已关") + (if (prefs.wordQuiet) " · 夜里不弹" else "")
        }
        col.addView(toggle("隔一段时间弹一张卡片", prefs.wordOn) { prefs.wordOn = it })
        // v2.4：**复习模式**开关。和上面那个「弹卡片」是两件事：
        // 上面管自动打扰，这个管复习页里排不排单词。关掉后复习页只剩知识卡。
        col.addView(toggle("复习时也排单词", prefs.wordReviewOn) { prefs.wordReviewOn = it })
        tipD("关掉后「开始复习」只过知识卡，不再排单词。",
            "关掉后复习页只排知识卡，单词一张都不出现 —— 只拿它当知识卡工具、不想被几千个词追着复习的人用这个。" +
            "\n和上面「隔一段时间弹一张卡片」是两件事：那个管**自动弹**，这个管**复习页排不排**。" +
            "\n关开关不删数据：单词的 FSRS 记录一条不动，重新打开能接着复习。")
        h("弹词间隔（秒）")
        col.addView(numberField("弹词间隔（秒）", prefs.wordEverySec) { prefs.wordEverySec = it })
        tip("5 ~ 86400 秒（默认 120 = 2 分钟）。想 15 秒来一张就填 15；填小了下场很快。")
        col.addView(toggle("夜里不弹（23:00 ~ 7:00）", prefs.wordQuiet) { prefs.wordQuiet = it })
        // —— 高级：卡片停留时长 / 弹出时朗读。默认值够用，按需展开 ——
        beginAdvanced()
        h("卡片自动收起（秒）")
        col.addView(numberField("卡片自动收起（秒）", prefs.wordCardStaySec) { prefs.wordCardStaySec = it })
        tip("3 ~ 600 秒（默认 20）。卡片弹出来后没人碰，就按这个秒数自己收回去；点「不认识」之后那一下是固定的短停顿，不受这里影响。")
        col.addView(toggle("英文词弹出时自动读一遍", prefs.wordSpeak) { prefs.wordSpeak = it })
        tip("朗读用手机自带的语音合成（TTS），英文词弹出时读一遍；没有可用的语音包就不出声。")
        endAdvanced()
        tipD("卡片在屏幕上方：先看正面，点一下展开释义，再点「✓ 认识 / ✗ 不认识」。",
            "卡片在屏幕上方，先只显示正面，点一下展开释义，再点「✓ 认识 / ✗ 不认识」。不认识的以后出现得更勤，连续认识 ${Words.MASTER_STREAK} 次算掌握、很少再出。锁屏、打游戏隐藏小球、正在识别、面板开着时不弹；没碰它就按上面设的秒数自己收回去。长按小球 →「来个单词」随时来一张。")
        endGroup()

        // ---- 组：词库（原「背单词」页下半的词库区块）----
        sec(G_WORDS_DECK) {
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
                        saved((if (on) "已勾选：" else "已取消：") + brief(s.name))
                        // v1.77：勾上内置分级（一次就是两千词）立刻后台预翻译，不等下一次弹卡
                        // （它自己的「已开始预翻译」会把这条顶掉，屏上不会同时挂两条）
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
            // 这条不计入「共 N 个」，那就别在这里报一个比总数还大的数字：
            // 它统计的是所有词库里的错词，句子里的总数只算勾选的词库，两个口径混着看会以为是 bug。
            // 所以行内只写「有」，条数放到勾上之后的下一步去数 —— 那里本来就是错词本自己的页面。
            col.addView(android.widget.CheckBox(this).apply {
                text = "不认识的词（自动错词本）"; isChecked = Words.MISTAKES in prefs.wordDecksOn; textSize = 15f
                setOnCheckedChangeListener { _, on ->
                    prefs.wordDecksOn = if (on) prefs.wordDecksOn + Words.MISTAKES else prefs.wordDecksOn - Words.MISTAKES
                    saved(if (on) "已勾选：错词本" else "已取消：错词本")
                }
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
            if (deckBox.isAttachedToWindow) WxSkin.style(deckBox)
        }
        fillDecks()
        endGroup()

        // ================= 弹出与提醒 › 提醒与待办（原「提醒与知识卡」整组按用途拆开：待办/记期/精确提醒留这里，
        // 弹出纪律 + 免打扰 → 弹出节奏与免打扰；简报 / 复习提醒 → 简报与复习提醒；震动 → 震动；
        // 学习计划 / 知识卡导入与库 → 学习与词库；局域网同步 → 数据与同步）=================
        sec(G_REMIND) { "精确提醒 " + (if (RemindExact.canExact(this@MainActivity)) "✓" else "✗") }
        // —— 待办入口（基础：最常用，放外面）。todo 模块被关掉就不画 ——
        // 下面那些提醒本身（记期、精确提醒授权、测一条提醒）不归模块管：关掉待办只是不再提供入口，
        // 已经排好的提醒照发（[FeatureToggles] 第 2 条硬规则：关掉不删数据、不静默停掉后台）。
        if (Features.isEnabled(this@MainActivity, "todo")) col.addView(Button(this).apply {
            text = "待办与随手记"
            setOnClickListener { if (!Features.open(this@MainActivity, "todo")) toast("打不开待办") }
        })

        // —— v1.86 记期列表：能重复的提醒（日历 / 每周几 + 次数 + 强度）——
        col.addView(Button(this).apply {
            text = "记期列表（日历 / 每周几）"
            setOnClickListener { try { startActivity(ScheduleActivity.intent(this@MainActivity)) } catch (_: Exception) { toast("打不开记期页") } }
        })
        tip("点日历里的某一天，或选好周几——它就每周/每天都提醒你。每条能单独设提醒次数（到点后隔几分钟再补几次）和震动强度。")

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

        endGroup()

        // ================= 弹出与提醒 › 弹出节奏与免打扰 =================
        sec(SettingsRegistry.S_POP_RULE) { (if (prefs.popupOn) "主动提醒开" else "主动提醒关") + (if (prefs.popupQuietOn) " · 免打扰 " + PopupPolicy.fmtMin(prefs.popupQuietStart) + "–" + PopupPolicy.fmtMin(prefs.popupQuietEnd) else "") }
        // —— 弹出纪律 ——
        col.addView(toggle("主动提醒（弹出纪律）", prefs.popupOn) { prefs.popupOn = it })
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
                    toast("小球没在跑或没授权 —— 去「小球与识屏」点「启动悬浮球」再试")
                    Todo.Store(this@MainActivity).remove(id)
                }
            }
        })
        tipD("试弹：提醒到点先轻震两下、球边浮张小卡；6 秒没人碰就收回去并补通知（不漏）。",
            "试弹 = 提醒到点时先轻震两下、在球边上浮出一张小卡；6 秒没人碰就自己收回去并补一条通知（提醒不会漏）。点「完成」销掉这条待办，点「稍后」30 分钟后再来。")

        // —— 免打扰时段（起止都可点开时间选择器）——
        col.addView(toggle("免打扰时段", prefs.popupQuietOn) { prefs.popupQuietOn = it })
        val quietStartRow = timeRow("免打扰开始", prefs.popupQuietStart) { min -> prefs.popupQuietStart = min }
        val quietEndRow = timeRow("免打扰结束", prefs.popupQuietEnd) { min -> prefs.popupQuietEnd = min }
        col.addView(quietStartRow); col.addView(quietEndRow)
        tip("免打扰期间事件排队不弹，时段结束合并成一条摘要给你。")

        endGroup()

        // ================= 弹出与提醒 › 简报与复习提醒 =================
        sec(SettingsRegistry.S_POP_BRIEF) {
            val on = listOf("早简报" to prefs.briefMorningOn, "晚简报" to prefs.briefNightOn, "知识卡复习" to prefs.reviewNotiOn)
                .filter { it.second }.map { it.first }
            if (on.isEmpty()) "都关了" else on.joinToString("、")
        }
        // —— 待办 / 简报 / 复习入口（待办入口已挪到上面的基础区）——
        col.addView(toggle("早简报", prefs.briefMorningOn) { prefs.briefMorningOn = it; rescheduleAllPopups() })
        col.addView(toggle("晚简报", prefs.briefNightOn) { prefs.briefNightOn = it; rescheduleAllPopups() })
        tip("简报一天两条：早上说今天有什么事，晚上收个尾。内容在本地拼，不调接口不花钱。")

        col.addView(toggle("知识卡到期提醒", prefs.reviewNotiOn) { prefs.reviewNotiOn = it; rescheduleAllPopups() })
        tip("到期的知识卡每天提醒一次（点通知直接开始复习），没有到期卡就不发。评完自动排下次间隔。")

        // —— 高级：统一弹出时刻 / 各自的时刻。日常不改这些也能用，按需展开 ——
        beginAdvanced()
        // —— 统一弹出时刻：一个时间管住所有主动弹出 ——
        val unifiedRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var refreshPopupTimeUi: () -> Unit = {}
        val unifiedSwitch = toggle("统一弹出时刻", prefs.popupUnifiedOn, topDp = 12f) {
            prefs.popupUnifiedOn = it
            refreshPopupTimeUi()
            rescheduleAllPopups()
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

        endAdvanced()
        endGroup()

        // ================= 弹出与提醒 › 震动 =================
        sec(SettingsRegistry.S_POP_VIBE) { Vibrate.label(prefs.vibrateLevel) }
        // —— v1.86 震动强度：全 App 唯一一处（菜单/面板弹出、划词过临界、提醒到点都读它）——
        col.addView(choiceRow("震动强度", Vibrate.LEVELS.map { Vibrate.label(it) }, Vibrate.LEVELS.indexOf(prefs.vibrateLevel)) { which ->
            prefs.vibrateLevel = Vibrate.LEVELS[which]
            Vibrate.remind(this@MainActivity, prefs.vibrateLevel)   // 立刻按选中档震一下 —— 不用猜「中是多重」
        })
        tip("现在：${Vibrate.describe(prefs.vibrateLevel)}。菜单弹出、划词判定也用它；某一条记期可以单独另设强度。")

        endGroup()

        // ================= 数据与同步 › 局域网同步 =================
        sec(SettingsRegistry.S_DATA_SYNC)
        // —— 局域网同步：手动一次，只在前台；配对串视同密码 ——
        col.addView(Button(this).apply {
            text = "局域网同步（手机 ↔ 电脑）"
            setOnClickListener { try { startActivity(Intent(this@MainActivity, LanSyncActivity::class.java)) } catch (_: Exception) { toast("打不开局域网同步") } }
        })
        tipD("电脑和手机在同一 Wi-Fi：把电脑端显示的配对串贴到手机，手动点同步；只在前台进行，退出即断。",
            "电脑端开启「手机连接」后会显示一串 ling-sync://IPv4:port/密钥。手机进入本页粘贴、点「开始同步」，双向合并知识卡和收藏的历史记录；" +
            "配对串视同密码（不保存、不写日志、不自动读剪贴板），离开本页即清空。离开页面或点取消会断开，不起后台服务；" +
            "取消不撤回电脑可能已保存的内容，再同步会自动合并。")
        // —— 手机常连（MCP）：和上面那条局域网同步是两件事，别混成一条 ——
        // 局域网同步 = 前台点一次、交换一次快照、退出即断；
        // 手机常连 = 扫码记住这台电脑，由用户在那一页按下开关后才在后台长轮询。
        // 两条入口并列，谁也没被改掉：原来的按钮、文案、行为一字未动。
        col.addView(Button(this).apply {
            text = "手机常连（MCP · 后台常连）"
            setOnClickListener {
                try { startActivity(LinkSettingsActivity.intent(this@MainActivity)) }
                catch (_: Exception) { toast("打不开手机常连") }
            }
        })
        tipD("持久配对：扫一次码记住这台电脑，由你在那一页打开开关后，电脑才能远程调手机上的工具；关掉即断。",
            "电脑端「手机常连」显示 ling-link://IPv4:port/pairId.密钥 的配对串与二维码。手机扫码（或粘贴）保存后，" +
            "配对信息用 Android Keystore 的 AES-256-GCM 整条加密存在应用私有目录，不进系统备份、不写日志；" +
            "传输是同一把密钥的 AES-256-GCM 密文，认证失败直接报错、不降级。默认是关的：配对不等于开启，" +
            "要后台常连必须在本页打开开关（会挂一条前台通知，通知里可随时停止）。电脑调用的每个工具都会在" +
            "「手机审计」里留一行，敏感动作（读屏 / 打开 app）还要你在手机弹窗上当场确认。")

        endGroup()

        // ================= 学习与词库 › 学习与知识卡 =================
        sec(SettingsRegistry.S_STUDY) { KnowledgeCard.Store(this@MainActivity).count().toString() + " 张知识卡" }
        col.addView(Button(this).apply {
            text = "学习与复习 · 自定义计划"
            setOnClickListener { if (!Features.open(this@MainActivity, "study")) toast("打不开学习与复习") }
        })
        // —— 知识卡导入（AI 生成）+ Anki 导入 + 知识卡库 ——
        // 知识卡导入是核心流程（study 挂在它下面），不受模块开关影响。
        col.addView(Button(this).apply {
            text = "导入知识卡（AI 生成）"; tag = Brut.PRIMARY
            setOnClickListener { openKnowledgeImport() }
        })
        // Anki 是独立功能包：用户关掉就整行不画 —— 设置页留一个点了弹「打不开」的按钮只是噪音。
        // 开关只在 registry 一处判，本页不认识 AnkiFeature 也不认识它的 Activity，只认 id "anki"。
        val ankiOn = Features.isEnabled(this, "anki")
        if (ankiOn) col.addView(Button(this).apply {
            text = "导入 Anki 牌组（.apkg）"; setOnClickListener { openAnkiModule() }
        })
        col.addView(Button(this).apply {
            text = "知识卡库"
            setOnClickListener { if (!Features.open(this@MainActivity, "knowledge")) toast("知识卡库打不开 —— 可能在「模块管理」里被关掉了") }
        })
        tipD("文本 / Word / PDF：先选文件或粘贴原文，预览后再调 AI 生成卡片，逐张核对后确认导入。",
            "「导入知识卡（AI 生成）」支持 txt / md / doc / docx / PDF：先选文件或粘贴原文，预览无误后再点按钮调用当前接口生成问答卡；每张卡可编辑 / 勾选 / 删除，摘录只读用来核对来源，确认后才批量入库。原文只在点生成时才发送，接口 Key 不外显、不写日志。" +
                (if (ankiOn) "\n「导入 Anki 牌组（.apkg）」打开 Anki 模块：选一个 .apkg 就能导入牌组与媒体。每次导入是一个独立集合，同一份文件重复导入会复用原集合（学习进度保留），不同的牌组不会互相合并。" else ""))
        endGroup()

        // ================= 学习与词库 › 专注守护 =================
        // 手机版（v2.3.4）：两档判定 —— 指定学习应用（系统 UsageStats 的前台包名）/ 屏幕内容（本机灰度版面三态）。
        // 设置只落本机；判定与陪伴文案都不联网，屏幕截图不上传。开关默认关，不主动开就完全不看。
        sec(SettingsRegistry.S_FOCUS) { focusSummary(FocusSettings(this@MainActivity)) }
        col.addView(toggle("专注守护", FocusSettings(this).enabled) { on ->
            FocusSettings(this).enabled = on
            FocusSettings.refresh(this@MainActivity)
            refreshFocusCards()
            if (on && !OrbService.running) toast("专注守护要小球在跑才会采样 —— 用下面那行「启动悬浮球」")
        })
        col.addView(choiceRow("判定方式", FOCUS_MODE_LABELS, if (FocusSettings(this).mode == FocusSettings.MODE_SCREEN) 1 else 0) { which ->
            FocusSettings(this).mode = if (which == 1) FocusSettings.MODE_SCREEN else FocusSettings.MODE_APP
            FocusSettings.refresh(this@MainActivity)
            refreshFocusCards()
        })
        tipD("「指定学习应用」看前台 App 是不是你选的那几个；「屏幕内容」只看这一屏的版面形状，判不出来就说判不出来。",
            "「指定学习应用」：读系统记录的前台应用包名（UsageStats），只有你勾选的那几个算学习，别的一律算走开。" +
                "这一档要系统「使用情况访问」授权；没授权、查不到或锁屏时按「判不出来」处理 —— 既不算学习也不算离开，" +
                "绝不因为读不到就把你记成走开。\n" +
                "「屏幕内容」：把当前这一屏缩成灰度图，算版面形状（文字行数、左边界是否整齐、有没有大块实心色），" +
                "三态：在学习 / 不像学习 / 判不出来。这是本机像素判定，不是读懂屏幕内容，也不上传截图 —— " +
                "没有联网请求。这一档沿用小球自己的屏幕采集通道（无障碍或录屏授权），没授权就采不到，同样只记「判不出来」。")
        // —— 屏幕内容这一档的硬前提：要有屏幕采集通道（无障碍已连上，或小球启动时授权过的录屏）。
        // 状态照实写、入口给到 PermissionActivity，不能因为「选了屏幕内容」就把授权条件藏起来 ——
        col.addView(TextView(this).apply { text = "屏幕内容的采集通道"; textSize = 15f; setPadding(0, dp(10f), 0, dp(2f)) })
        val screenLine = TextView(this).apply {
            textSize = 13f; setTextColor(Brut.INK); setLineSpacing(0f, 1.3f); setPadding(0, dp(8f), 0, dp(4f)); tag = Brut.KEEP
        }
        col.addView(screenLine)
        col.addView(Button(this).apply {
            text = "去开截屏通道（无障碍 / 录屏授权）"
            setOnClickListener {
                try { startActivity(PermissionActivity.intent(this@MainActivity)) }
                catch (_: Exception) { toast("打不开权限页") }
            }
        })
        tip("「屏幕内容」这一档只在这一屏的版面形状上算，需要上面那项权限；没通道时只记「判不出来」，不猜。")
        col.addView(TextView(this).apply { text = "离开多少分钟算走开（1~60）"; textSize = 15f; setPadding(0, dp(10f), 0, dp(2f)) })
        col.addView(numberField("离开多少分钟算走开（1~60）", FocusSettings(this).leaveMinutes) {
            FocusSettings(this).leaveMinutes = it
            FocusSettings.refresh(this@MainActivity)
            refreshFocusCards()
        })
        tip("离开超过这个时长才温和提醒一句；锁屏、停掉服务都不再计时，解锁后重新算。默认 5 分钟。")
        // —— 使用情况访问：APP 模式的硬前提，状态每次都照实写出来，不藏 ——
        val usageLine = TextView(this).apply {
            textSize = 13f; setTextColor(Brut.INK); setLineSpacing(0f, 1.3f); setPadding(0, dp(8f), 0, dp(4f)); tag = Brut.KEEP
        }
        col.addView(usageLine)
        col.addView(Button(this).apply {
            text = "去开「使用情况访问」权限"
            setOnClickListener {
                try { startActivity(UsageProbe.settingsIntent(this@MainActivity)) }
                catch (_: Exception) { toast("打不开系统的「使用情况访问」页 —— 在系统设置 → 应用 → 特殊权限里找") }
            }
        })
        // —— 学习应用白名单：只列有启动入口的 App（queryIntentActivities，不用 QUERY_ALL_PACKAGES），按名字排序 ——
        val appsBtn = Button(this).apply {
            setOnClickListener { pickStudyApps() }
        }
        col.addView(appsBtn)
        val appsLine = TextView(this).apply {
            textSize = 13f; setLineSpacing(0f, 1.3f); setPadding(0, dp(4f), 0, dp(4f)); tag = Brut.KEEP
        }
        col.addView(appsLine)
        // —— 小球：专注采样由小球自己起节拍，和自动扫描开不开无关；这里只照实写跑没跑 + 给现有启动入口 ——
        val orbLine = TextView(this).apply {
            textSize = 13f; setLineSpacing(0f, 1.3f); setPadding(0, dp(10f), 0, dp(4f)); tag = Brut.KEEP
        }
        col.addView(orbLine)
        col.addView(Button(this).apply {
            text = "启动悬浮球"
            tag = Brut.PRIMARY
            setOnClickListener { onStartClicked() }
        })
        col.addView(Button(this).apply {
            text = "专注报告（今天 / 最近 7 天）"
            setOnClickListener {
                try { startActivity(Intent(this@MainActivity, FocusReportActivity::class.java)) }
                catch (_: Exception) { toast("打不开专注报告") }
            }
        })
        focusRows.add {
            val fs = FocusSettings(this)
            // 屏幕内容这一档的前提是**无障碍已连上**。小球在跑 != 有录屏通道：
            // 小球完全可能是在无障碍没连上时、用「无录屏」的方式起来的，那时候一条采集通道都没有。
            // 这条通道是否真的活着只有 OrbService / Capture 知道，本页读不到，所以这里**不提**「小球在用录屏通道」，
            // 只说清「无障碍没连上」这一件已经证实的事。
            val a11y = a11yConnected()
            screenLine.text = when {
                a11y -> "采集通道：✓ 无障碍已连上（屏幕模式可用，不需要每次授权）"
                else -> "采集通道：✗ 无障碍没连上 —— 屏幕模式要靠它；没有通道时只记「判不出来」。"
            }
            screenLine.setTextColor(if (a11y) Brut.HINT else Brut.INK)
            val granted = UsageProbe.hasAccess(this)
            usageLine.text = if (granted) "使用情况访问：✓ 已授权（「指定学习应用」这一档可用）"
            else "使用情况访问：✗ 还没授权 —— 「指定学习应用」现在判不出结果，只记「判不出来」，不会把你记成走开。"
            usageLine.setTextColor(if (granted) Brut.HINT else Brut.INK)   // 缺授权是「要处理的事」，给正文色，不压灰
            val apps = fs.apps
            appsBtn.text = "学习应用（已选 ${apps.size} 个）"
            val labels = launchableApps().filter { it.first in apps }.map { it.second }
            appsLine.text = when {
                apps.isEmpty() -> "还没选学习应用：一个都不会算作学习，也不会提醒你走开。"
                labels.isEmpty() -> "选了 ${apps.size} 个应用，但现在系统里读不到它们（可能已卸载）—— 重新选一次。"
                else -> "已选：" + labels.take(6).joinToString("、") + (if (labels.size > 6) " 等 ${labels.size} 个" else "")
            }
            orbLine.text = if (OrbService.running) "小球：运行中（专注守护的设置改完立刻生效）"
            else "小球：未启动 —— 现在不会采样，也不会提醒；先启动小球才生效。"
        }
        tipD("隐私：判定全在本机做，不联网、不上传截图；报告里只有时刻、时长、三态标签和画面指纹。",
            "所有判定都在手机上完成：APP 模式只读前台包名，屏幕模式只算版面形状的像素统计。" +
                "落盘只有四样（时刻、时长、三态标签、画面指纹），没有文字、没有截图、没有网络请求。" +
                "「判不出来」（锁屏 / 纯色页 / 解码失败 / 没授权）既不算学习也不算离开。" +
                "停止服务或锁屏就停止采样并清掉离开计时，解锁后重新算。这些数字在「专注报告」页：本卡上面那个按钮，或点专注提醒那条通知。")
        endGroup()

        // ================= 学习与词库 › 陪伴气泡 =================
        // 陪伴开关独立于专注守护：小球在跑时用本机短句，8 秒自己消失，不抢焦点、不响不震。
        sec(SettingsRegistry.S_COMPANION) { companionSummary(FocusSettings(this@MainActivity)) }
        col.addView(toggle("陪伴气泡", FocusSettings(this).companionEnabled) { on ->
            FocusSettings(this).companionEnabled = on
            FocusSettings.refresh(this@MainActivity)
            refreshFocusCards()
        })
        col.addView(choiceRow("出现频率", COMPANION_FREQ_LABELS, COMPANION_FREQS.indexOf(FocusSettings(this).companionFrequency).coerceAtLeast(0)) { which ->
            FocusSettings(this).companionFrequency = COMPANION_FREQS[which]
            FocusSettings.refresh(this@MainActivity)
            refreshFocusCards()
        })
        val companionLine = TextView(this).apply {
            textSize = 13f; setLineSpacing(0f, 1.3f); setPadding(0, dp(4f), 0, dp(4f)); tag = Brut.KEEP
        }
        col.addView(companionLine)
        col.addView(Button(this).apply {
            text = "启动悬浮球"
            setOnClickListener { onStartClicked() }
        })
        focusRows.add {
            val fs = FocusSettings(this)
            companionLine.text = when {
                !fs.companionEnabled -> "开关是关的：一句都不会冒。"
                !OrbService.running -> "小球没跑 —— 现在不会冒；启动小球之后才会，不需要开启专注守护。"
                else -> "会用「" + companionFreqLabel(fs.companionFrequency) + "」的节奏冒一句本机短句。"
            }
        }
        tipD("本地短句（不调 AI）、8 秒自己消失、不抢焦点；免打扰或息屏时不冒。",
            "陪伴气泡独立于专注守护；开启陪伴且小球在跑时才可能出现，出现时机不定（" +
                "低：60~180 分钟一次；中：45~120 分钟一次；高：15~60 分钟一次），不会按点报时、也不是番茄钟。" +
                "文案是随包内置的短句，纯本机挑选，不调用任何接口、不上传任何内容。冒出来 8 秒就自己消失、" +
                "不抢输入焦点、不弹全屏、不响不震动；免打扰时段、息屏 / 锁屏、小球浮层被压住或已有气泡在显示时一律不冒。" +
                "停掉小球就彻底不冒。")
        endGroup()

        // 专注守护 / 陪伴气泡两卡里跟着运行时状态变的文案，onResume 与重建后各刷一次
        focusRows.forEach { it() }

        // 所有卡片都建完了：按显示顺序（首页 + 8 个分类）把页装进 SwipePager。
        // 首页只是一列可滚动的分组行；分类页 = 固定顶部返回栏「‹ 设置 + 分类名」+ 可滚动的卡片列。
        pageNames.clear(); pageScrolls.clear()
        for (name in listOf(HUB) + CATEGORIES) {
            val c = catCols[name] ?: continue
            // 一张卡片里一个控件都没放（不应出现，SettingsRegistryTest 钉着每张卡都有项）就整张藏掉，不留空白卡
            if (name != HUB) for (sd in SettingsRegistry.SECTIONS.filter { it.cat == name }) {
                groupBoxes[sd.id]?.let { if (it.content.childCount == 0) it.root.visibility = View.GONE }
            }
            val sv = ScrollView(this).apply { addView(c); isVerticalScrollBarEnabled = false; isFillViewport = true }
            val page: View = if (name == HUB) sv else LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(subPageBar(name), LinearLayout.LayoutParams(-1, -2))
                addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            pager.addPage(page)
            pageNames.add(name); pageScrolls.add(sv)
        }
        if (prefs.openGroups == null) prefs.openGroups = Sections.defaults(GROUPS)
        groupBoxes.values.forEach { it.render() }
        hubRows.forEach { it() }

        // 底部入口栏：设置页以前是这条链路的终点，进来只能按返回。
        // 现在四个区域平级互通，点谁都在原地换页、不重建。
        // 挂在外层 page 上（不是 root）：root 是滚动内容，导航栏要在它下面常驻。
        // 顺序有讲究 —— WxSkin.style 是一条「把整棵子树按微信设置页刷一遍」的规则，
        // 底栏要排在它后面挂，才不会被当成普通分组卡片重刷。
        page.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        // 内容区在底栏**之上**结束：weight=1 的 root 拿到的是「page 高 - 底栏高」，
        // 所以 pager 里滚到底时最后一行正好停在栏的上沿，不会被栏压住。
        //
        // 早先这里少了 weight=1 之外的保护 —— 底栏是后挂的，root 先按满高量了一遍，
        // 结果「外观」那行被白胶囊盖住大半（实机截图可见）。现在靠 weight 定高，不再需要额外 padding。
        BottomNav.attach(page, this, BottomNav.SETTINGS)

        building = false
        return page
    }

    // ---------------- 外观配色（v1.55；v1.66 色系分页：左右滑动换色系，点一张卡片就换） ----------------
    private lateinit var previewBox: LinearLayout
    private lateinit var contrastLine: TextView
    private val colorRows = ArrayList<() -> Unit>()   // 各行「当前值」文字的刷新函数
    private val themeRows = ArrayList<() -> Unit>()   // 配色卡片 / 自定义那一行的 ✓ 刷新

    /**
     * 画廊短标签「中文 English」：默认 + 六套青春配色 + 瑞士 + 薄荷玫瑰（= [Theme.HOT_IDS]）。
     * 其余（深色伴生 / 老 id / 自定义）落回预设自带名字 —— 但瑞士的深色伴生必须给短名，
     * 否则跟随系统深色时满载全名的两行标签会把卡片撑破。
     */
    private fun themeLabel(id: String, fallback: String): String = when (Theme.normId(id)) {
        Theme.DEFAULT -> "护眼 Eye（默认）"
        "candy_pop" -> "糖果 Candy"
        "cyber_pop" -> "赛博 Cyber"
        "fresh_pop" -> "清新 Fresh"
        "dopamine_pop" -> "多巴胺 Dopamine"
        "acid_green" -> "酸性 Acid"
        "soft_digital" -> "柔光 Soft"
        "swiss" -> "瑞士 Swiss（直角 / 黑白红 / 无投影）"
        // 深色伴生不进画廊，但「跟随系统深色」时画廊里正在用的那行会切到它 ——
        // 不补这一行就会落回预设全名（「瑞士国际主义 · 深色 · Swiss Dark」），两行标签直接把卡片撑破。
        "swiss_dark" -> "瑞士 Swiss 深色（直角 / 黑白红 / 无投影）"
        // v2.7.3：液态玻璃的短名；深色伴生在「跟随系统深色」时也会顶到画廊的「正在用」行，同样给短名
        "liquid_glass" -> "液态玻璃 Glass（冷白 / 冰蓝 / 清透材质）"
        "liquid_glass_dark" -> "液态玻璃 Glass 深色（墨蓝 / 清透材质）"
        else -> fallback
    }

    private fun pickTheme(id: String) {
        val before = prefs.themeId
        if (id != before) {
            if (id == Theme.CUSTOM) prefs.setThemeColor("bg", prefs.baseTheme().bg)   // 从当前预设抄一份开始改
            else prefs.themeId = id
        }
        // 立刻让设置页也用上新配色（设置页可能比悬浮球先开）：先把色板装进 Ui.theme，再整棵重建。
        Ui.applyTheme(prefs)
        // 这一页马上要按新色板重建：把快照一起推进，否则下一次 onResume 会误判「色板变了」再重建一遍。
        shownTheme = Ui.theme
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
        focusRows.clear()
        val root = build()
        setContentView(root)
        skinSettings(root)

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
    /**
     * 一条扁平色板条：左起纸底 60% → 卡片 25% → 主色 10% → 副色 5%（四段，按权重硬编码）。
     *
     * 为什么做成一条而不用「四个小圆点」：圆点只有色不带比例，眼睛读到的还是「有四个颜色」而不是
     * 「薄荷绿占多少」。60/25/10/5 是面积约定，就该按面积画出来 —— 用户点开外观页扫一眼，
     * 就知道这套配色大面积是中性色、主色只有一条、副色更细。
     */
    private fun themeCard(id: String, label: String, t: Theme): View {
        // 圆角 / 描边一律跟当前形状语言走：瑞士风格是 0dp 直角 + 1dp 细线，粗野是 8dp 圆角 + 2dp 墨框。
        // 卡里的样张与色板条自己画 drawable（不经过 Brut.style），所以这里必须显式取一次，
        // 否则瑞士配色下会冒出「直角页面里两块圆角小方块」。
        val sharp = Ui.shape.cornerDp <= 0f
        val cardCorner = when {
            t.isGlassMaterial -> 12f   // 玻璃样张：44dp 小方块上取玻璃主圆角的缩小档，24dp 会糊成药丸
            sharp -> 0f
            else -> 8f
        }
        val strokePx = Math.max(1, dp(Ui.shape.strokeDp))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(8f), dp(6f), dp(10f), dp(6f))
            // 两行标签 + 色板条要塞得下：给一个最小高度，但不再锁死 52dp ——
            // 瑞士标签比其它配色长（「瑞士 Swiss（直角 / 黑白红 / 无投影）」），锁死就会把文字切掉。
            minimumHeight = dp(52f)
        }
        val sw = TextView(this).apply {
            text = "字"; gravity = Gravity.CENTER; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.tx1)
            // v2.7.3：玻璃配色连画廊样张也是玻璃材质（Ui.material 带 t = 预览的这套配色，不偷读当前全局主题）；
            // 玻璃样张不再叠 Brut.INK 描边 —— 边界归 material 的高光细边。普通配色保持 panelBg + 墨框原样。
            background = if (t.isGlassMaterial) Ui.material(this@MainActivity, t.card, Ui.SurfaceRole.CARD, cardCorner, t)
                // 非 glass 分支已确认仍是 GradientDrawable（panelBg 玻璃路径才委派 material），这里补墨框原样
                else (Ui.panelBg(this@MainActivity, t.copy(opacity = 100), cardCorner) as GradientDrawable)
                    .apply { setStroke(strokePx, Brut.INK) }
            tag = Brut.KEEP
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(44f), dp(40f)).apply { marginEnd = dp(10f) })
        val orb = OrbView(this).apply { themeOverride = t }
        // v2.5.1：加一条**扁平色板条**（纸底 / 卡片 / 主色 / 副色各一段，宽度按 60/25/10/5 分）。
        // 原来这张卡只有 44dp 一块渐变字块 + 小球，实机截图里根本看不出这套配色的四档面积 ——
        // 「用色比例」在设置页上变成了只能靠数字判断的东西。色板条直接把比例画出来。
        // 用四个等比、圆角、等高的小色块拼出来，而不是自绘 Canvas：
        // 一是单测环境里没法验证自绘结果，二是四段的边界本来就要看得到（深色配色里纸底和卡片很接近）。
        // 权重 60/25/10/5 就是设计要求的用色比例，直接写在布局里，改比例只改这组数。
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // palette 是可空的：老配色「护眼」/「夜航」是手调的 Theme，没有走 ThemePalette 那条路（见 Theme.kt:121
        // 的 EYE 与 255 行的 night_mint）。这里原本写 t.palette!!，一进外观页就 NullPointerException 整进程崩掉
        // —— 那正是「切到设置就闪退」的真凶。四段色于是退化成从 Theme 自身的字段推：纸底/纸底/主色/副色。
        val segs = when {
            // 瑞士风格不是「四档用色比例」，而是「黑白 + 一个信号红」：纸底 60 / 墨色 25 / 灰阶 10 / 信号红 5。
            // 照搬 paper/card/primary/secondary 会得到「近白 + 白 + 红 + 暗红」——实拍就是一片白，读不出这套风格。
            t.noShadowStyle && t.palette != null -> {
                val pal = t.palette
                listOf(0.60f to pal.paper, 0.25f to pal.ink, 0.10f to pal.highlight, 0.05f to pal.primary)
            }
            t.palette != null -> {
                val pal = t.palette
                listOf(0.60f to pal.paper, 0.25f to pal.card, 0.10f to pal.primary, 0.05f to pal.secondary)
            }
            else -> listOf(0.60f to t.bg, 0.25f to t.bg, 0.10f to t.orbA, 0.05f to t.orbB)
        }
        for ((w, colRaw) in segs) {
            val seg = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(0xFF000000.toInt() or colRaw)
                    cornerRadius = if (sharp) 0f else dp(3f).toFloat()
                }
                tag = Brut.KEEP
            }
            band.addView(seg, LinearLayout.LayoutParams(0, dp(30f), w).apply {
                marginEnd = dp(1f)
            })
        }
        row.addView(band, LinearLayout.LayoutParams(0, dp(30f), 1f).apply { marginEnd = dp(10f) })
        // tag = KEEP：这张卡的底色在选中时会切成主色，字色要跟着填色走，别被 Brut.style 一刀切成墨色
        val tv = TextView(this).apply { textSize = 14f; setTextColor(Brut.on(Brut.CARD)); tag = Brut.KEEP }
        // 2f：色板条占 1f 之后，文字仍是这一行最宽的部分（长配色名要在两行里排得下）
        row.addView(tv, LinearLayout.LayoutParams(0, -2, 2f))
        row.addView(orb, LinearLayout.LayoutParams(dp(30f), dp(30f)))
        row.setOnClickListener { pickTheme(id) }
        themeRows.add {
            val on = prefs.themeId == id
            val fill = if (on) Brut.MINT else WxSkin.FIELD
            tv.text = (if (on) "✓ " else "") + label
            tv.setTextColor(Brut.on(fill))
            tv.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            row.background = WxSkin.rounded(if (on) fill else WxSkin.FIELD, this, 10f)
            row.contentDescription = label + if (on) "，正在用" else "，点按换成这套配色"
        }
        return row
    }

    private fun buildTheme(col: LinearLayout, h: (String) -> Unit, tip: (String) -> Unit) {
        themeRows.clear(); colorRows.clear()
        h("外观配色")
        // 文案必须数得上：张数全部从 Theme.HOT_IDS 现算（护眼 + 青春配色 + 风格套件），以后加风格不会再出现「文案少一套」。
        val galleryCount = Theme.HOT_IDS.size
        val styleCount = Theme.HOT_IDS.count { Theme.isSwiss(it) || Theme.isAtelier(it) || Theme.isGlass(it) }
        val youthCount = galleryCount - styleCount - if (Theme.DEFAULT in Theme.HOT_IDS) 1 else 0
        tip("新装默认「薄荷玫瑰」，升级保留已选配色；画廊共 " + galleryCount + " 套：" + youthCount + " 套青春配色，另外 " + styleCount +
            " 套是整体风格 —— 瑞士（直角 / 黑白红 / 无投影）、薄荷玫瑰（分层圆角 + 柔和阴影）和液态玻璃（清透材质 / 蓝青主色，无实时模糊）。" +
            "点一张就换 —— 小球和开着的面板立刻跟着变。都不合意就点「自定义」逐项调。")
        // 画廊全部来自 Theme.HOT_IDS；旧配色不再出现，但老用户存着的旧 id 仍按迁移表照常解析（Theme.normId），不会失效。
        for (id in Theme.HOT_IDS) {
            val p = Theme.PRESETS.firstOrNull { it.first == id } ?: continue
            // 高度交给内容自己定（卡片里设了 52dp 最小高）：瑞士标签换行成两行时，
            // 固定 52dp 会把第二行切掉 —— 这正是实拍里看到的那处截断。
            col.addView(themeCard(id, themeLabel(id, p.second), p.third), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) })
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
        target.addView(WxSkin.subhead(this, "自定义颜色（逐项调）"))
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
                // 色块同样跟形状语言走：瑞士 0dp 直角 + 1dp 细线，其余 6dp 圆角。
                // 描边以前写死 0xFF888888（和任何配色都无关的灰），改成当前配色的细线色，
                // 深色配色下才不会出现「亮灰框贴在黑底上」。
                val sharp = Ui.shape.cornerDp <= 0f
                swatch.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Theme.argb(0xFF, c))
                    cornerRadius = if (sharp) 0f else dp(6f).toFloat()
                    setStroke(Math.max(1, dp(Ui.shape.strokeDp)), Ui.HAIRLINE)
                }
                tv.text = "$label：" + colorDesc(c)
            }
            target.addView(row)
        }
        colorRow("bg", "面板底色（渐变时是左上）") { it.bg }
        colorRow("bg2", "面板渐变色（右下；和底色一样 = 纯色）") { it.bg2 ?: it.bg }
        val opLabel = TextView(this).apply { textSize = 14f; setPadding(0, dp(6f), 0, 0) }
        target.addView(opLabel)
        val seek = saveSeek(50, prefs.baseTheme().opacity - 50,
            name = { p -> "面板不透明度 " + (50 + p) + "%" }) { p ->
            prefs.setThemeColor("opacity", 50 + p); refreshTheme()
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
        // v2.4：主题包 —— 把当前这套（颜色 + 形状 + 字号）打包成一段文本，或粘贴别人的
        target.addView(TextView(this).apply {
            text = "主题包"; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18f), 0, dp(4f))
        })
        target.addView(Button(this).apply {
            text = "导出当前主题（复制到剪贴板）"
            setOnClickListener {
                val pack = ThemePack.capture(prefs, "我的主题")
                val code = ThemePack.encode(pack)
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Ling 主题包", code))
                    toast("已复制。发给别人，对方粘到「导入」里就能用")
                } catch (e: Exception) { toast("复制失败") }
            }
        })
        target.addView(Button(this).apply {
            text = "从剪贴板导入主题"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val raw = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() } catch (e: Exception) { null }
                val pack = ThemePack.decode(raw)
                if (pack == null) {
                    toast("这不是一个主题包（应以 LINGTHEME1: 开头）")
                } else {
                    ThemePack.apply(prefs, pack)
                    Ui.applyTheme(prefs)
                    toast("已套用「" + pack.name + "」")
                    recreate()   // 整页按新形状重建：已画好的卡片不会自己改边角
                }
            }
        })
        target.addView(TextView(this).apply {
            text = "导出的是颜色、基础圆角、描边、投影和字号，不包含玻璃材质与分层圆角。导入会覆盖当前自定义配色，但不删你的卡片和单词记录。"
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
        // v2.7.3：玻璃配色连预览底也是玻璃材质（t = 预览的这套配色，弹层档）；普通配色保持 panelBg 渐变 / 纯色
        previewBox.background = if (t.isGlassMaterial) Ui.material(this, t.card, Ui.SurfaceRole.SHEET, 28f, t)
                                else Ui.panelBg(this, t, 16f)
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
        presetApi = false,
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
        setupNext = Button(this).apply {
            tag = Brut.PRIMARY
            setOnClickListener {
                val cur = setupPager.current
                val stepNow = setupSteps[cur]
                // 没做完也让人点 —— 点了至少要知道差在哪。
                // 原来这个按钮在未完成时 isEnabled=false，点击零反馈，
                // 而 Onboarding.blockedHint() 里明明写着具体该怎么做，却从不display给用户。
                if (!Onboarding.done(stepNow, setupState())) {
                    toast(Onboarding.blockedHint(stepNow).ifBlank { "这一步还没做完" })
                    return@setOnClickListener
                }
                if (cur == setupSteps.size - 1) finishSetup(true) else setupPager.goTo(cur + 1)
            }
        }
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
        // 不禁用，只做视觉弱化。禁用会让点击完全没有反应，
        // 用户看到「（先做完这步）」却不知道先做什么。
        setupNext.alpha = if (done) 1f else 0.55f
        setupNext.isEnabled = true
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
                tipD("一步一页，把用小球需要的几样设好：悬浮窗权限" + (if (Onboarding.steps(setupState()).contains(Onboarding.Step.API)) "、接口" else "") + "、截屏方式、通知、后台运行。",
                    "接下来一步一页，把用小球需要的几样设好：悬浮窗权限" + (if (Onboarding.steps(setupState()).contains(Onboarding.Step.API)) "、接口" else "") + "、截屏方式、通知、后台运行。左右滑动翻页，也可以点底下的按钮。必需的步骤做完才能往后；每一步都给了「继续但不开这个功能」的按钮。随时可以往回滑。")
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
                            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: ""); clearTestResult(); refreshSetup() }
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
                // 「可选」两个字会让人以为能直接跳过，但底部的跳转规则是
                // 「这一步做完才能往后」。真机走查：用户在第 3 步连点 8 次
                // 「下一步 ›（先做完这步）」都没反应，因为「可选」在他心里
                // 等于「可以跳过」，而代码里等于「可以点先不开」。
                // 改成如实描述：推荐，但两种方式都行。
                h("截屏方式：选一种就行")
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
                    // 原文案「先不开，用录屏授权（每次启动要点一次）」有两个劝退点：
                    // 「先不开」听起来像放弃功能，「每次启动要点一次」听起来像自找麻烦。
                    // 而它其实是这一步唯一的「继续」按钮 —— 没有任何词暗示这一点。
                    skip("就用录屏（以后想改再来这页）")
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
                    "点下面「完成，启动小球」就开始；之后都在设置首页里改（按用途点进分类，每类里是分组卡片）。「先不开」的几项在设置 →「权限与后台」随时能补上。")
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
        ThemeDialogs.builder(this).setTitle("选$label").setItems(items) { _, i ->
            if (i < Theme.NAMED.size) onPick(Theme.NAMED[i].second) else inputHex(label, cur, onPick)
        }.setNegativeButton("取消", null).show()
    }

    private fun inputHex(label: String, cur: Int, onPick: (Int) -> Unit) {
        val et = EditText(this).apply { setText(Theme.hex(cur)); setSingleLine(); setSelection(text.length) }
        ThemeDialogs.builder(this).setTitle("$label · 输入色号").setMessage("格式 #RRGGBB（也可 #RGB），比如 #FFFFFF = 白、#000000 = 黑").setView(et)
            .setPositiveButton("确定") { _, _ -> Theme.parseHex(et.text.toString())?.let(onPick) ?: toast("色号格式不对：要像 #1C1C1E 这样 6 位") }
            .setNegativeButton("取消", null).show()
    }

    /** 系统里无障碍开关是否已授权（≠ 已连上，见 [A11yService.enabledInSystem]） */
    private fun a11yEnabled() = A11yService.enabledInSystem(this)

    /** 无障碍服务此刻是否已连上 —— 只有连上了才真能截屏（[A11yService.available]） */
    private fun a11yConnected() = A11yService.available

    private fun refreshState() {
        if (!::permLine.isInitialized) return
        refreshOverlayGranted()   // 界面里所有「要不要悬浮窗」的判断都读这个快照：在这里量一次就够
        val overlay = overlayGranted
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
        focusRows.forEach { it() }   // 专注守护 / 陪伴气泡：小球状态、使用情况访问授权（可能刚去系统里开过）
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

    // ---------------- 设置项「改完就存好了」的短回执 ----------------
    // 本页没有保存按钮，改一下就写进 Prefs。三条硬规则：
    // ① 只有用户真的改了才出（初始绑定 / 整页重建 / 程序化回填一律不报）；
    // ② 连续输入、拖动滑杆不逐字刷 —— 停手 / 松手才出一条；
    // ③ 文案只说「哪一项、开还是关」，接口地址 / Key / 模型名这些输入内容一个字都不进 toast。

    /** 一项设置真的落盘后的短回执：先把上一条按掉再弹，屏幕上永远只有一条，不排队堆长 */
    private fun saved(msg: String) {
        saveSerial++          // 作废还在排队的输入回执：后来者优先
        showSaved(msg)
    }

    /** 真正弹那条 SHORT 回执（内部用）。同一句在窗口期内重复（重建 / 连点造成的双触发）只出一次 */
    private fun showSaved(msg: String) {
        if (isFinishing || isDestroyed) return
        val now = System.currentTimeMillis()
        if (msg == lastSavedMsg && now - lastSavedAt < SAVE_DEDUPE_MS) return
        lastSavedMsg = msg; lastSavedAt = now
        savedToast?.cancel()
        savedToast = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).also { it.show() }
    }

    /** 逐字写的输入框：停手 [SAVE_TYPING_MS] 才给一条；期间又改了别处，这条就作废 */
    private fun savedAfterTyping(msg: String) {
        val serial = ++saveSerial
        saveHandler.postDelayed({ if (serial == saveSerial) showSaved(msg) }, SAVE_TYPING_MS)
    }

    /** 离开页面 / 销毁：别让还在排队的输入回执晚一步飘出来 */
    private fun cancelSaveFeedback() {
        saveHandler.removeCallbacksAndMessages(null)
        savedToast?.cancel(); savedToast = null
    }

    /** 输入框要不要给回执：用户真在打字（有焦点）、且不在程序化回填期间 */
    private fun canReportEdit(e: EditText): Boolean = quietEdits == 0 && e.hasFocus()

    /** 程序化改写输入框（切方案回填 / 填入示例）：这一段里写 pref 不算用户改动，不出回执 */
    private fun setQuiet(e: EditText?, v: String) {
        if (e == null) return
        quietEdits++
        try { e.setText(v) } finally { quietEdits-- }
    }

    /** 回执里的项名：去掉「（…）」补充说明并截断 —— 只说改了哪一项，不回显内容 */
    private fun brief(name: String): String {
        val s = name.replace(PAREN_NOTE, "").replace(WHITESPACE, " ").trim()
        val out = s.ifEmpty { name.trim() }
        return if (out.length <= 16) out else out.take(15) + "…"
    }

    /**
     * 数字输入行：逐字写 pref（行为不变）。回执只在用户真在打字、且值真的变了时给一条 ——
     * 停手才出，空着 / 非数字（pref 不动）不报。
     */
    private fun numberField(label: String, value: Int, save: (Int) -> Unit): EditText {
        val e = EditText(this)
        e.setText(value.toString()); e.inputType = InputType.TYPE_CLASS_NUMBER; e.setSingleLine()
        var last = value
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s?.toString()?.toIntOrNull() ?: return
                save(n)
                if (n == last) return          // 程序化 setText（重建 / 回填）不算改动
                last = n
                if (canReportEdit(e)) savedAfterTyping("已保存：" + brief(label))
            }
        })
        return e
    }

    /**
     * 一条开关：**先写 isChecked、再挂监听** —— 初始绑定与整页重建都不会误报「已保存」。
     * [label] 同时当文案和回执项名（回执只说「已开 / 已关 + 哪一项」）。
     */
    private fun toggle(label: String, on: Boolean, topDp: Float = 8f, act: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label; isChecked = on; textSize = 15f; setPadding(0, dp(topDp), 0, dp(4f))
            setOnCheckedChangeListener { _, checked ->
                act(checked)
                saved((if (checked) "已开：" else "已关：") + brief(label))
            }
        }

    /**
     * 自己写 pref 的滑杆：拖动中照旧逐次落盘（面板 / 小球要跟手），但**一条回执都不出**；
     * 只在松手、且这一趟真的动过时给一条。[name] 拿松手时的进度写新档位。
     * 初始 progress 是程序化的（fromUser=false），所以初始绑定 / 重建 / 回填都不报。
     */
    private fun saveSeek(max: Int, progress: Int, name: (Int) -> String, onMove: (Int) -> Unit): android.widget.SeekBar {
        val bar = android.widget.SeekBar(this)
        bar.max = max; bar.progress = progress
        var moved = false
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                onMove(p); moved = true
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { moved = false }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                if (!moved) return
                moved = false
                saved("已保存：" + name(bar.progress))
            }
        })
        return bar
    }

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
                runOnUiThread { saved("已开始预翻译 $cap 个词（后台进行中）") }
                val done = store.prefetchAll(cards, prefs.deepl(), true, now)
                if (done > 0) runOnUiThread { saved("预翻译完成：成功 $done 个") }
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
        // 这里**不再**顺手要通知权限。
        //
        // 原来这行会让用户点「启动小球」后第一个看到的就是一个英文系统框
        // 「Allow Ling 内测 to send you notifications?」——前面没有任何解释，
        // 而且紧接着才是屏幕权限的解释框，两个框连着弹，用户根本来不及想。
        //
        // 通知对这个功能不是必需的：startForeground() 在没授权时照样成功，
        // 只是那条常驻通知不显示。引导第 4 步和权限页都已经问过，
        // 不该在启动这一下再拦一道。
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

    /**
     * 请求录屏授权。
     *
     * **注意这里必须先解释再跳系统框。** 真机走查：用户点「启动小球」后，
     * 屏幕立刻跳出一个全英文的系统对话框，内容是
     *
     *   Start recording or casting with Ling 内测?
     *   ... will have access to all of the information that is visible on your
     *   screen ... passwords, payment details, photos, messages, and audio ...
     *
     * 用户刚被问「要不要启动小球」答「要」，紧接着就要交出整个屏幕，
     * 中间一句话都没有。系统框的语言跟不了应用设置，改不了；
     * 但可以在它前面垫一句中文，让用户知道自己在同意什么。
     */
    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) { refreshState(); return }
        ThemeDialogs.builder(this)
            .setTitle("下一步会问你要「屏幕内容」权限")
            .setMessage(
                "悬浮球要看你屏幕上的字才能记笔记、翻译 —— 系统会弹一个提示框问你要这个权限。\n\n" +
                "那个框是系统给的，只有英文，说的比较吓人（会提到密码、支付信息）。" +
                "它讲的是「技术上能看到的范围」，不是 Ling 要拿走这些。" +
                "识别的文字只在本机处理，除了你配的模型接口，不上传到别处。\n\n" +
                "不想给也没关系 —— 记事、待办、聊天都不用这个权限。"
            )
            .setNegativeButton("先不给") { _, _ -> permLine.text = "没给屏幕权限 —— 悬浮球用不了，别的功能都在。" }
            .setPositiveButton("去授权") { _, _ ->
                // v1.59：Android 14 起直接要「整个屏幕」，框里不用再从「单个应用」切过去
                @Suppress("DEPRECATION") startActivityForResult(CaptureActivity.captureIntent(this), REQ_CAPTURE)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 本页只剩录屏授权这一条回结果的路；Anki 的 .apkg 选文件归 anki 功能包自己处理。
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
    // 知识卡导入的显式入口：文本 / Word / PDF 走独立页面 [ImportActivity]
    // （选文件或粘贴 → 预览 → 显式调 AI → 核对 → 批量入库）。
    // Anki 牌组不在这里分流 —— 它是独立的 anki 功能包，入口见 openAnkiModule()。
    private fun openKnowledgeImport() {
        try { startActivity(ImportActivity.intent(this)) }
        catch (e: Exception) { toast("打不开导入页：" + (e.message ?: "")) }
    }

    /**
     * 打开 anki 功能包（Anki 牌组 .apkg 的导入 / 浏览全在它自己的页面里）。
     *
     * 这里**只认 id、不引用 [AnkiFeature] 这类具体类**：功能包自己登记进 [Features.all]，
     * 主壳不需要认识它；没登记（或用户关掉了）时 [Features.open] 返回 false，给一句人话就行，
     * 不会因为「类还没落地 / 清单里漏了 Activity」把设置页带崩。
     */
    private fun openAnkiModule() {
        if (!Features.open(this, "anki")) toast("Anki 模块打不开 —— 可能在「模块管理」里被关掉了")
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
            text = PopupPolicy.fmtMin(min) + "  ›"; textSize = 15f; setTextColor(Brut.HINT)
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        // 2.2.x 设置页微信式：卡片里一条平的行（左标签、右灰色当前值 ›），不再是带描边投影的小积木
        row.minimumHeight = dp(50f); row.tag = Brut.KEEP
        row.setPadding(0, dp(8f), 0, dp(8f))
        row.background = WxSkin.ripple(this, null)
        var curMin = min        // 上一次确认的时刻：选完还是同一分钟就不算改动、不报
        row.setOnClickListener {
            val h = curMin / 60
            val m = curMin % 60
            android.app.TimePickerDialog(this, { _, hh, mm ->
                val total = PopupPolicy.normMin(hh * 60 + mm)
                if (total == curMin) return@TimePickerDialog     // 没变：只关对话框，不写不报
                curMin = total
                value.text = PopupPolicy.fmtMin(total) + "  ›"
                onPicked(total)
                saved("已保存：" + brief(label) + " " + PopupPolicy.fmtMin(total))
            }, h, m, true).show()
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
            text = (options.getOrNull(cur) ?: "") + "  ›"; textSize = 15f; setTextColor(Brut.HINT)
        }
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        // 2.2.x 设置页微信式：卡片里一条平的行（左标签、右灰色当前值 ›），不再是带描边投影的小积木
        row.minimumHeight = dp(50f); row.tag = Brut.KEEP
        row.setPadding(0, dp(8f), 0, dp(8f))
        row.background = WxSkin.ripple(this, null)
        var curIdx = cur        // 上一次选中的项：再点同一项不算改动、不报
        row.setOnClickListener {
            ThemeDialogs.builder(this).setTitle(label)
                .setSingleChoiceItems(options.toTypedArray(), curIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))) { d, which ->
                    if (which != curIdx) {
                        curIdx = which
                        value.text = options[which] + "  ›"
                        onPick(which)
                        saved("已保存：" + brief(label) + " · " + options[which])
                    }
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
        val gen = ++testGen
        testRes.text = "检测中…"
        val api = prefs.api()
        thread {
            val c = ApiClient(api)
            val msg = try { "✓ 连通：" + c.ping().take(40) } catch (e: Exception) { "✗ " + NetErrors.friendly(e, api.base) }
            runOnUiThread { if (gen == testGen) testRes.text = if (msg.startsWith("✓")) "$msg\n检测能不能看图…" else msg }
            if (!msg.startsWith("✓")) return@thread
            // v1.63：识别屏幕要靠看图 —— 顺带发张小图试试，不会看图的模型在这里就说清楚
            val v = c.pingVision()
            runOnUiThread { if (gen == testGen) testRes.text = msg + "\n" + (v?.let { "⚠ $it" } ?: "✓ 能看图，可以识别屏幕") }
        }
    }

    /** 接口 / 方案 / 思考强度一改，上一次的检测结果就过期了 —— 立刻清掉，别让用户对着旧「✓」以为新配置也通 */
    private fun clearTestResult() {
        testGen++
        if (::testRes.isInitialized) testRes.text = ""
    }

    /**
     * v1.77 更新通道：抓清单 → 比版本 → 怎么显示全交给 [Update.check] 决定。
     * 自动检查（开设置页、距上次超 24h）全程静默：不写「检查中…」，已是最新 / 读不到清单都不写文案，
     * 发现新版也只写文案 + 亮一个下载按钮，**绝不自己 enqueue 下载** —— 之前就是这里直接开下载，
     * 用户一打开页面就被弹「开始下载」，看着莫名失控。
     * 下载/安装都交给系统，不搞静默安装。
     */
    private fun checkUpdate(manual: Boolean) {
        // 依次要试的地址：手填的（非空）在前，内置源兜底；清空输入框 / 手填等于内置 → 只剩内置源。
        // 用 [Update.sources] 而不是「手填了就不再回内置」—— 手填的读不到时能自己爬回内置源。
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
