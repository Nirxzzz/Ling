package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 笔记卡片流（P0 的最后一块）：把 [Notes] 里那些数据**显示出来**。
 *
 * 在这之前，数据层（Notes / NoteStore / NoteAttach / NoteSyntax，约 1500 行）是座孤岛：
 * 只有 [NoteSelfTest] 调它，用户存进去的笔记**没有任何界面能看到**。
 * 存了看不见，等于丢了。
 *
 * 设计约束（沿用全项目的做法）：
 *  - 不引 RecyclerView：笔记量级是「人一天写几条」，全程建 View 完全够，
 *    真到几千条再换 —— 现在引一堆依赖只为省那点滑动，不值得。
 *  - 缩略图按需解码（[NoteAttach.thumbFile]），列表只读 320px 的小图，
 *    不然滑到第 20 条就 OOM。
 *  - 所有判断逻辑留在纯函数里；本页只做「取数据 → 摆控件」。
 *
 * ## 记事：两个模块
 * 底栏那一格叫「记事」，本页是它的入口：标题下「笔记 / 提醒」切换。
 *  - 「笔记」= 原来这套卡片流（筛选 / 搜索 / 列表；排序收在右上角「⋯」里），行为一行没改；
 *  - 「提醒」= 待办 / 记期 / 随手记，内容由 [TodoPanels] 渲染，数据仍走 [Todo.Store] / [Memo.Store]，
 *    一条都没复制、没迁移。
 * 两个模块共用这一页的底栏：提醒**不占**底栏槽位，也不再套一层自己的 ScrollView ——
 * 否则会变成双底栏（里面的栏被外面的列表滚走）与嵌套滚动。
 */
class NotesActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var tabBar: LinearLayout
    /** 标题行的「＋」：写一条。和 [moreMenuButton] 一样，提醒模块 / 回收站 / 搜索态下不出现 */
    private var createButton: TextView? = null
    /** 标题右侧的「⋯」：笔记库菜单（排序 / 导入 / 导出 / 重建索引）。提醒模块下没有意义，跟着 [noteBox] 一起藏 */
    private var moreMenuButton: TextView? = null
    /** moduleBar 上每个按钮对应的模块值（「提醒」被模块开关关掉时那一格不建，下标就对不上了） */
    private val moduleIndex = ArrayList<Int>()

    /** 当前模块：[MODULE_NOTES] 笔记 / [MODULE_REMINDERS] 提醒 */
    private var module = MODULE_NOTES
    /** 笔记模块那一整块（筛选 / 搜索 / 列表）：切到提醒时整块 GONE，筛选与搜索词一个不动 */
    private lateinit var noteBox: LinearLayout
    /** 提醒模块的内容位：切过去时才渲染 */
    private lateinit var todoBox: LinearLayout
    /**
     * 提醒模块里**只建一次**的骨架：子视图按钮那一行，与它下面的内容位 [reminderBody]。
     *
     * 以前每次 [renderReminders] 都把 todoBox 整块拆了重摆（含 [TodoPanels.header] 那段说明）：
     * 同栏刷新时它没变也跟着重建，换「待办 / 随手记」时更是整块闪一下。现在说明和按钮原地不动，
     * 只有内容位重建 —— 过渡动效只作用在这个内容位上，不牵动整页高度。
     */
    private var reminderTabBar: LinearLayout? = null
    private var reminderBody: LinearLayout? = null
    /** 两个模块的切换按钮（选中态只在 [setModule] 里改） */
    private lateinit var moduleBar: LinearLayout
    /** 提醒模块内部的次级视图：0 待办 / 1 随手记 */
    private var reminderTab = 0
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private lateinit var helper: NoteStore

    /**
     * 库打开成功没有。
     *
     * 升级 / 迁移失败时 [NoteStore.writableDatabase] 会抛（迁移从 onOpen 进来）。
     * 以前这一行裸在 onCreate 里 —— 升级后第一次进记事页就是闪退。现在开库失败只置
     * false，页面改画 [showDbError]，所有读库入口先看这个标记。
     *
     * 绝不因为开不出来就画一张空列表：那等于告诉用户「你的笔记没了」。
     */
    private var dbReady = false
    /** 开库失败的原因（只给错误页用；成功后清空） */
    private var dbError: String? = null

    private var filter = Notes.Filter.ALL
    private var tagFilter: String? = null
    private var query: String = ""

    /**
     * 列表排序。默认「最近修改」—— 用户回来找的大多是刚动过的那几条。
     *
     * 排序只影响卡片流（[Notes.feed]）的 SQL；搜索结果和标签结果在内存里按同一套
     * 规则排一遍，否则切了排序却看不出任何变化，控件就在骗人。
     */
    private var sort = Notes.Sort.MODIFIED

    /**
     * 卡片流展开了几页。
     *
     * 原来列表一次取固定条数、没有加载更多的入口 —— 更旧的笔记在界面上永远出不来，
     * 页面也不会给任何暗示。切筛选 / 改搜索词时归 1（回到第一页）。
     * 搜索和标签筛选不分页：它们的条数本来就是定死的，翻页游标只对 feed 的排序键成立。
     */
    private var pages = 1
    /** 上一次 load() 之后还有没有更早的：决定要不要显示「加载更早的」 */
    private var hasMoreFeed = false
    private val pageSize = 200

    /** 导入 / 导出 / 重建索引正在跑：期间按钮禁用，避免两个任务同时写库 */
    private var busy = false
    /** 本页启动的后台线程。只在 Activity 还活着时回调 UI */
    private var worker: Thread? = null
    /** 选目录回来后要干的那件事（[ACT_EXPORT] / [ACT_IMPORT]）；0 = 没有 */
    private var pendingAction = 0
    /**
     * 顶部那行「后台没成」的提示位。
     *
     * 迁移 / 重建索引 / 导入导出都在后台线程跑，失败时 [NoteVault.lastError] 里有原因。
     * 那些失败**不挡**读笔记（正文照常读得出来），所以这里只占一行小字、和列表并存，
     * 不弹框、不画空列表。没有失败时整行 GONE，不占视觉位置。
     */
    private var vaultNote: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        filter = when (intent?.getIntExtra(EXTRA_FILTER, 0)) {
            1 -> Notes.Filter.PINNED
            2 -> Notes.Filter.ARCHIVED
            3 -> Notes.Filter.TRASH
            else -> Notes.Filter.ALL
        }
        tagFilter = intent?.getStringExtra(EXTRA_TAG)
        module = intent?.getIntExtra(EXTRA_MODULE, MODULE_NOTES) ?: MODULE_NOTES

        // 建库放在 render 之前：第一次进来要能立刻看到空状态，而不是等一下才出现。
        //
        // 但开库**不能裸着**：SQLiteOpenHelper 会把 onCreate / onUpgrade / onOpen 里的异常
        // 原样抛出来，NoteVault 的迁移也从 onOpen 进来。以前这里没有 try，老版本升级后
        // 第一次进记事页就是闪退 —— 用户什么都看不到，也不知道笔记还在不在。
        // 现在失败就只记下原因，页面改画 [showDbError]：明确说清楚 + 可重试。
        try {
            helper = NoteStore(this)
            db = helper.writableDatabase
            // FTS 索引重建失败不算致命（笔记正文照常能读，只是搜索会不准），
            // 保持原来的兜法：不让它把整个页面拖进错误态。
            runCatching { Notes.ensureFtsSchema(db) }
            dbReady = true
            dbError = null
        } catch (e: Exception) {
            dbReady = false
            dbError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        // 外层一列：上面可滚动的笔记区，下面固定的底部入口栏。
        // 以前整页就是一个 ScrollView，导航栏若塞进去会跟着列表一起滚走。
        //
        // 左右必须自己留边：Brut.page 只补系统栏那点 inset，一条正文贴在屏幕边缘会连字都切掉，
        // 而且 clipToPadding 会把溢出的部分裁掉 —— 看上去像内容缺字。底部不留，
        // 留给 BottomNav 自己撑（它要在栏下面透出纸底）。
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@NotesActivity, 16f), 0, Ui.dp(this@NotesActivity, 16f), 0)
        }
        val sv = ScrollView(this).apply { addView(root); isFillViewport = true }
        page.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        Brut.page(this, page)
        BottomNav.attach(page, this, BottomNav.NOTES)
        if (!dbReady) showDbError() else {
            buildChrome()
            renderList()
            refreshVaultNote()
            // 落点可能是「提醒」（带着 EXTRA_MODULE 进来）：初次落地也按它摆界面
            applyModule()
        }
    }

    /**
     * 库开不出来时的整页错误态。
     *
     * 三条硬要求：
     *  1. **不能画空列表** —— 那是在说「你没有笔记」，而真实情况是「读不到」；
     *  2. 要能重试：迁移 / 磁盘临时故障这类问题是会好的，重建 Activity 是最省事的重试；
     *  3. 要能退出：卡在一个只能重试的页面上同样难受。
     *
     * 错误原文照贴 —— 「笔记库打不开」五个字没法让人判断是磁盘满、还是版本升级失败。
     */
    private fun showDbError() {
        root.addView(TextView(this).apply {
            text = "笔记库没能打开"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(this@NotesActivity, 8f), 0, Ui.dp(this@NotesActivity, 6f))
        })
        root.addView(Ui.text(this,
            "笔记文件还在，只是这次读不出来。可以点「重新试」再开一次；一直不行就先把这条信息记下来。",
            13f, Ui.TX3))
        root.addView(Ui.text(this, "原因：" + (dbError ?: "未知"), 12.5f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NotesActivity, 8f), 0, Ui.dp(this@NotesActivity, 8f))
            setTextIsSelectable(true)   // 让用户能把原因复制出来
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NotesActivity, 10f), 0, 0)
        }
        row.addView(Button(this).apply {
            text = "重新试"; tag = Brut.PRIMARY
            setOnClickListener { recreate() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "关掉"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NotesActivity, 8f)
        })
        root.addView(row)
        Brut.style(root)
    }

    private var renderedOnce = false

    override fun onResume() {
        super.onResume()
        // onCreate 里已经建过一次；重复建一遍是纯浪费（实测 9 条笔记要 71ms）。
        // 只有「从详情页回来」才需要刷新 —— 那时 Activity 没被销毁，renderedOnce 为 true。
        // 库没开出来时 renderList 只会再撞一次同一个异常；错误页已经摆在那了，重画没有意义。
        if (renderedOnce && module == MODULE_NOTES && dbReady) renderList()
        // 后台的迁移 / 重建索引是异步的：进页面时它可能还没跑完，失败只会写进
        // [NoteVault.lastError]。每次回到本页顺手刷一次这行提示 —— 就是一次读，不新增轮询线程。
        if (dbReady) refreshVaultNote()
        // 提醒模块的数据在别处也能变（通知落点、Agent 建了一条、记期页物化）：
        // 回到本页只重画当前这一模块；另一个模块的容器还在原处没被拆掉，状态不动。
        if (renderedOnce && module == MODULE_REMINDERS && ::todoBox.isInitialized) renderReminders()
        renderedOnce = true
    }

    /**
     * 从别的区域经底部栏切回来：带 FLAG_ACTIVITY_SINGLE_TOP 时不会重建，走这里。
     *
     * 只有**带落点 extra** 的新 intent 才改筛选（如 [EXTRA_FILTER] / [EXTRA_TAG]）—— 不接管的话
     * 新 intent 会被 setIntent 悄悄吞掉，点标签没反应。底栏那条不带 extra 的 Intent
     * 不碰本页状态，切回来时搜索词、筛选、滚动都原样保留。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 库没开出来时页面只有 [showDbError]，chrome 一个都没建（tabBar 未初始化）。
        // 此时再 fillTabs / renderList 只会撞 UninitializedPropertyAccessException；
        // 错误页已经摆在那儿了，落点 extra 等重开库之后再说。
        if (!dbReady) return
        // 模块落点：底栏的裸 Intent 不带它 —— 不带就保持用户原来停在哪（笔记的筛选、搜索词都还在）。
        val hasFilter = intent.hasExtra(EXTRA_FILTER) || intent.getStringExtra(EXTRA_TAG) != null
        if (!intent.hasExtra(EXTRA_MODULE) && !hasFilter) return
        if (intent.hasExtra(EXTRA_MODULE)) setModule(intent.getIntExtra(EXTRA_MODULE, MODULE_NOTES))
        if (!hasFilter) return
        val want = when (intent.getIntExtra(EXTRA_FILTER, 0)) {
            1 -> Notes.Filter.PINNED
            2 -> Notes.Filter.ARCHIVED
            3 -> Notes.Filter.TRASH
            else -> Notes.Filter.ALL
        }
        val tag = intent.getStringExtra(EXTRA_TAG)
        if (want == filter && tag == tagFilter) return
        filter = want
        tagFilter = tag
        pages = 1   // 换了筛选就回到第一页，别把上一个筛选翻到的页数带过来
        // 显式落点优先于残留的搜索词，否则「标签 #x」会被旧 query 覆盖成空结果
        if (tag != null) query = ""
        fillTabs()
        renderList()
        rebuildSearchBox()
    }

    override fun onDestroy() {
        super.onDestroy()
        // helper 是 SQLiteOpenHelper，进程内复用同一个库句柄，这里只关游标不管库。
        //
        // 后台的导入 / 导出**不打断**：它可能正写到一半，掐掉会留下半个文件。
        // 线程只丢掉对 Activity 的强引用（worker 置空），写完自己结束；UI 回调那边
        // 会先看 isFinishing || isDestroyed，不会往已经没了的窗口弹框。
        worker = null
    }

    /**
     * 刷新筛选栏（首次调用才真正建控件）。
     *
     * 选中态以前靠「▸ 」前缀表达 —— 前缀只在选中那一格出现，那格文字变宽，
     * 在窄屏 / 大字体下「归档」「回收站」被挤成两行，整条栏跟着变高。
     * 现在选中只靠已有的主色底（[Brut.PRIMARY]）：前后是同一个背景块，高度不跳。
     *
     * 另外这里只改文字和高亮、不重建控件：切 tab 时用户随时在做，
     * 重建 4 个 Button + Brut.style 实测要 11~21ms。
     */
    private fun fillTabs() {
        if (tabBar.childCount == 0) {
            // 首次：建 4 个按钮。宽度跟随文字（-2）+ maxLines=1 ——
            // 不给 0dp 权重，就不会被均分压窄而换行；装不下时由外层横向滚动兜底。
            val tabs = listOf(
                "全部" to Notes.Filter.ALL,
                "置顶" to Notes.Filter.PINNED,
                "归档" to Notes.Filter.ARCHIVED,
                "回收站" to Notes.Filter.TRASH,
            )
            for ((name, f) in tabs) {
                tabBar.addView(Button(this).apply {
                    text = name
                    maxLines = 1
                    // 系统 Button 自带 88dp 最小宽（来自 style 的 minWidth）—— 四格合计会超出内容宽度，
                    // 逼出本来不需要的横向滚动。归零后每格只占「文字 + Brut 的内边距」。
                    minWidth = 0
                    setOnClickListener {
                        filter = f
                        tagFilter = null
                        pages = 1
                        fillTabs()
                        renderList()
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            }
        }
        // 之后只改文字和高亮，不重建控件：
        // 实测重建 4 个 Button + Brut.style 要 11~21ms，而切换 tab 用户随时在做。
        val tabs = listOf(Notes.Filter.ALL, Notes.Filter.PINNED, Notes.Filter.ARCHIVED, Notes.Filter.TRASH)
        val names = listOf("全部", "置顶", "归档", "回收站")
        for (i in 0 until minOf(tabBar.childCount, tabs.size)) {
            val b = tabBar.getChildAt(i) as? Button ?: continue
            val on = filter == tabs[i]
            b.text = names[i]
            b.tag = if (on) Brut.PRIMARY else null
        }
        Brut.style(tabBar)
    }

    /**
     * 切排序。
     *
     * 换排序等于换了一整个列表：「加载更早的」的展开进度必须归 1，
     * 否则新排序下第一屏就直接是 600 条，滚动位置也全乱了。
     *
     * v2.7.2：三个排序按钮从列表上方那条常驻行收进了「⋯ → 排序」子菜单（[showMoreMenu]）。
     * 选中态由菜单项自己的勾表达、每次打开按当前 [sort] 现算 —— 排序状态仍然只有这一处，
     * 不再另存一份「哪个按钮高亮」。菜单里切完立刻重排列表，语义和按钮时代一模一样。
     */
    private fun setSort(s: Notes.Sort) {
        if (s == sort) return
        sort = s
        pages = 1
        renderList()
    }

    /** 当前排序的名字（菜单标题用；别在别处再拼一遍） */
    private fun sortLabel(): String = SORTS.firstOrNull { it.second == sort }?.first ?: "最近修改"

    /**
     * 顶部：标题行（「＋ 写一条」/「⋯」）+ 模块切换 + 筛选 + 搜索。只建一次，切筛选时只重建列表。
     *
     * 排序不在这一层：它和导入 / 导出一起收在标题行的「⋯」里（[showMoreMenu]）。
     */
    private fun buildChrome() {
        // 最上面那行「后台没成」的提示位：本页唯一显示 [NoteVault.lastError] 的地方。
        // 迁移 / 重建索引 / 导入导出失败都落在这里 —— 那些失败不挡读笔记（正文照常读得出来），
        // 所以只占一行小字、和列表并存，不弹框。没失败时整行 GONE，不占视觉位置。
        vaultNote = Ui.text(this, "", 12f, Ui.TX3).apply {
            setPadding(0, 0, 0, Ui.dp(this@NotesActivity, 6f))
            visibility = android.view.View.GONE
        }
        root.addView(vaultNote, LinearLayout.LayoutParams(-1, -2))
        // 标题行：左边「记事」，右边「＋」和「⋯」。新建入口与库目录操作都在这行 ——
        // 「＋」是最常用的动作，所以给独立一格；导入 / 导出 / 重建索引 / 排序是低频或设定类，
        // 收进「⋯」菜单，不占列表上方的常驻空间。
        //
        // 标题是「记事」而不是「笔记」：这一页现在装两个模块（笔记 / 提醒），
        // 只有下面那几个筛选属于「笔记」。底栏那一格同一份文案 —— 两处不一致时用户会以为进错了地方。
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(this@NotesActivity, 8f))
        }
        titleRow.addView(TextView(this).apply {
            text = "记事"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // 新建入口（v2.7.2）：原来它是列表最上面那条整宽「＋ 写一条」，列表每重绘一次就要
        // 多搭一个大按钮，还会被第一条笔记挤下去。现在收进标题行、和「⋯」并排：
        // 触控 48dp（Brut 给按钮的最小高度就是 48dp），contentDescription 给读屏。
        // 「能不能新建」仍由 [canCreateHere] 说了算 —— 回收站 / 归档 / 置顶 / 搜索态下不出现，
        // 所以可见性每次都由 [refreshCreateEntry] 现算，不是一建就永远摆着。
        val add = Ui.button(this, "＋") {
            try {
                NoteEditActivity.openNew(this@NotesActivity)
            } catch (e: Exception) { toast("打不开编辑器") }
        }
        add.contentDescription = "写一条笔记"
        add.minWidth = Ui.dp(this@NotesActivity, 48f)
        add.minimumHeight = Ui.dp(this@NotesActivity, 48f)
        titleRow.addView(add, LinearLayout.LayoutParams(-2, -2))
        createButton = add

        // 尺寸交给 Brut 的按钮规格（padding / 50dp 或 48dp 最小高度）；这里只兜宽度，
        // 所以不要再写 setPadding —— Brut.style 在 buildChrome 末尾跑，会把它整个盖掉。
        val more = Ui.button(this, "⋯") { }
        // 需要视图本身当 PopupMenu 的锚点，所以另挂点击
        more.setOnClickListener { showMoreMenu(more) }
        // 菜单只管笔记库（排序 / 导入 / 导出 / 重建索引）：停在「提醒」模块时它没有意义，跟着藏起来
        more.minWidth = Ui.dp(this@NotesActivity, 48f)
        more.minimumHeight = Ui.dp(this@NotesActivity, 48f)
        titleRow.addView(more, LinearLayout.LayoutParams(-2, -2))
        root.addView(titleRow)
        moreMenuButton = more

        // 「笔记 / 提醒」两个模块。选中只改主色底、不重建 —— 和下面 [fillTabs] 同一套画法，
        // 和提醒页那半边（[TodoListActivity]）同一份布局：两格等宽，谁也不比谁大。
        // 「提醒」属于 todo 模块（[FeatureToggles] 的口径：关掉只藏入口，数据一条不删）；
        // 关掉时那一格不出现，剩「笔记」一格就不占满整行 —— 空一格比一个点了只弹
        // 「已关掉」的按钮更让人猜。
        moduleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        moduleIndex.clear()
        val cells = ArrayList<Pair<Int, String>>()
        for ((m, name) in listOf(MODULE_NOTES to "笔记", MODULE_REMINDERS to "提醒")) {
            if (m == MODULE_REMINDERS && !remindersOn()) continue
            cells.add(m to name)
        }
        for ((m, name) in cells) {
            moduleIndex.add(m)
            moduleBar.addView(Button(this).apply {
                text = name
                setOnClickListener { setModule(m) }
            }, LinearLayout.LayoutParams(if (cells.size > 1) 0 else -2, -2, 1f))
        }
        root.addView(moduleBar)

        // 笔记模块整块：筛选 / 搜索 / 列表（排序在标题行的「⋯」里）。切到提醒时整块 GONE（不是拆掉）——
        // 拆掉的话搜索框、筛选按钮全得重建，用户切回来时筛选和搜索词就没了。
        noteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(noteBox)

        // 提醒模块的内容位：切过去时才渲染（见 [renderReminders]），平时是个空壳子。
        todoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        root.addView(todoBox)

        // 四个按钮各按文字取宽，整行居中铺满；只有窄屏 / 放大字体真的装不下时，
        // 才由这层横向滚动兜底 —— 宁可让用户划一下，也不能把字截掉或折成两行。
        // （HorizontalScrollView 用 UNSPECIFIED 量孩子，所以行宽是真实总宽，滚动范围才对。）
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            // Brut.spacing 给每个按钮强制补 8dp 右间距（覆盖不掉），最后一格后面就多出一截；
            // 左边补同样的 8dp，整行居中才是真的居中，不会整体偏左 4dp。
            setPadding(Ui.dp(this@NotesActivity, 8f), 0, 0, 0)
        }
        noteBox.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(tabBar, -2, -2)
        }, LinearLayout.LayoutParams(-1, -2))
        fillTabs()

        // 搜索框：边打边搜。查询为空就回到普通列表，不额外留「搜索」按钮。
        val ed = EditText(this).apply {
            hint = "搜正文、标题、标签";
            inputType = InputType.TYPE_CLASS_TEXT;
            setText(query)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString().orEmpty().trim()
                    if (q == query) return
                    query = q
                    pages = 1
                    renderList()
                }
            })
        }
        noteBox.addView(ed, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@NotesActivity, 8f)
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        noteBox.addView(listBox)

        // 页面底部不再放「待办清单 →」那一行：待办已经是本页的「提醒」模块，
        // 顶着模块切换再给一条跳另一页的横幅，等于把同一件事说了两遍，
        // 而且原来它就在筛选栏旁边，点「置顶」时手指常误落到它上面（实测跳走过）。
        //
        // 底部那条整宽「关掉」也删了（v2.7.2）：退出的路径有系统返回和底栏两条，
        // 它只是把列表往下压、键盘一开还会被盖住。库开不出来时的错误页仍保留「关掉」
        // （见 [showDbError]）—— 那个页面没有列表可滑，必须留一个能走掉的按钮。
        fillModuleBar()
        Brut.style(root)
    }

    // ---------------- 两个模块 ----------------

    /** 「提醒」模块此刻可用吗：todo 模块被用户关掉时入口跟着关（数据一条不删，见 [FeatureToggles]） */
    private fun remindersOn(): Boolean = Features.isEnabled(this, TodoFeature.id)

    /** 「笔记 / 提醒」选中态。只改主色底，不重建按钮（同 [fillTabs] 的理由：切模块随时在做） */
    private fun fillModuleBar() {
        if (!::moduleBar.isInitialized) return
        for (k in 0 until moduleBar.childCount) {
            val m = moduleIndex.getOrNull(k) ?: continue
            (moduleBar.getChildAt(k) as? Button ?: continue).tag = if (m == module) Brut.PRIMARY else null
        }
        Brut.style(moduleBar)
    }

    /**
     * 切模块。[MODULE_REMINDERS] 时才渲染提醒内容 —— 待办 / 随手记各是一次全量读文件，
     * 停在笔记模块时白跑一遍没有意义。
     *
     * 笔记模块那整块用 GONE 而不是拆掉：筛选、搜索词、排序、翻到第几页、滚动位置全是本页状态，
     * 拆掉重建等于每次切回来都从「全部 / 无搜索 / 第一页」重来。
     */
    private fun setModule(m: Int) {
        val next = if (m == MODULE_REMINDERS && remindersOn()) MODULE_REMINDERS else MODULE_NOTES
        if (next == module && ::noteBox.isInitialized) return
        module = next
        // 只有用户按了「笔记 / 提醒」才播过渡：初次落地（onCreate）走 [applyModule] 的默认不播
        applyModule(animate = true)
    }

    /**
     * 把当前模块落到界面上（初次建页和 [setModule] 都走这里）。
     *
     * 切换是**立刻生效**的：要藏的那块先落回终态再 GONE，要显示的那块立刻 VISIBLE 并渲染内容，
     * 数据不等动画。动画只是落在 [AnimGlue.applyVisual] 的 alpha / 小位移上（不改高度、不重排），
     * 所以整页 ScrollView 不会跟着逐帧重算。
     */
    private fun applyModule(animate: Boolean = false) {
        // 落点带着「提醒」进来、而模块恰好被关掉：退回「笔记」，不画一份关着的清单
        if (module == MODULE_REMINDERS && !remindersOn()) module = MODULE_NOTES
        fillModuleBar()
        if (!::noteBox.isInitialized) return   // 库开不出来时只有错误页，没有模块可切
        val notesVisible = module == MODULE_NOTES
        val showing = if (notesVisible) noteBox else todoBox
        val hiding = if (notesVisible) todoBox else noteBox
        // 藏起来的那块先落终态：它可能正跑着上一次切换的半路 alpha / 位移，
        // 直接 GONE 会让它下次出现时从「半透明 / 偏下」起步（= 一个看起来坏掉的视图）。
        // 内容位自己也在跑一段过渡（换「待办 / 随手记」）：一并收掉，不让它带着半路 alpha 留在后台。
        reminderBody?.settleVisuals()
        hiding.settleVisuals()
        hiding.visibility = android.view.View.GONE
        moreMenuButton?.visibility = if (notesVisible) android.view.View.VISIBLE else android.view.View.GONE
        // 「＋ 写一条」跟着同一套开关：提醒模块下没有「写笔记」这回事
        refreshCreateEntry()
        showing.visibility = android.view.View.VISIBLE
        // 渲染本身不动画（数据更新不等过渡），动的是整块进入的那一下
        if (!notesVisible) renderReminders(animate = false)
        if (animate) showing.playSoftIn() else showing.settleVisuals()
    }

    /**
     * 渲染「提醒」模块：待办 / 随手记两个子视图。
     *
     * 内容全部由 [TodoPanels] 摆出来 —— 和 [TodoListActivity]（通知、聊天「＋」、设置入口的落点）
     * 是同一份代码，两处看到的清单永远一致。
     *
     * 骨架（[TodoPanels.header] 那段说明 + 子视图按钮行）只建一次：回到本页刷新数据、或在两个
     * 子视图之间切换时，只有内容位 [reminderBody] 重摆。以前每次刷新都把 header 一起拆了重建，
     * 白花一次布局，也让「刷新」看起来像整块换了一页。
     *
     * @param animate 只有用户按了「待办 / 随手记」才为 true —— 数据刷新、初次建页都不播
     */
    private fun renderReminders(animate: Boolean = false) {
        if (!::todoBox.isInitialized) return
        if (reminderBody == null) {
            todoBox.removeAllViews()
            TodoPanels.header(this, todoBox)
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in REMINDER_TABS.indices) {
                bar.addView(Button(this).apply {
                    setOnClickListener { pickReminderTab(i) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            todoBox.addView(bar)
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            todoBox.addView(body)
            reminderTabBar = bar
            reminderBody = body
            // 骨架只建一次：它的皮肤也只刷一次（以前靠每次 renderReminders 末尾的 Brut.style(todoBox) 顺手刷；
            // 现在刷新只碰内容位，不在这里补上，header 那段说明和按钮会一直是未刷的默认样式）。
            Brut.style(todoBox)
        }
        fillReminderTabs()
        renderReminderBody(animate)
    }

    /** 「待办 / 随手记」选中态：只改文字和高亮，不重建按钮（同 [fillTabs]） */
    private fun fillReminderTabs() {
        val bar = reminderTabBar ?: return
        for (i in 0 until minOf(bar.childCount, REMINDER_TABS.size)) {
            val b = bar.getChildAt(i) as? Button ?: continue
            b.text = (if (reminderTab == i) "▸ " else "") + REMINDER_TABS[i]
            b.tag = if (reminderTab == i) Brut.PRIMARY else null
        }
        Brut.style(bar)
    }

    /** 再点当前那一格：照旧重读一次数据（例如别处刚建了一条），但不播过渡 —— 内容没换 */
    private fun pickReminderTab(i: Int) {
        val switched = i != reminderTab
        reminderTab = i
        renderReminders(animate = switched)
    }

    /** 只重摆内容位。[TodoPanels] 的 `changed` 回调也走这里：数据变了不等于用户切了栏 */
    private fun renderReminderBody(animate: Boolean) {
        val body = reminderBody ?: return
        body.removeAllViews()
        if (reminderTab == 0) TodoPanels.todos(this, body) { renderReminderBody(false) }
        else TodoPanels.memos(this, body) { renderReminderBody(false) }
        Brut.style(body)
        if (animate) body.playSoftIn() else body.settleVisuals()
    }

    // ---------------- 轻过渡（只在用户切换时用）----------------
    //
    // 约束：只动 alpha + 纵向小位移。**不碰高度、不缩放** —— 这一页整块在 ScrollView 里，
    // 一旦逐帧改高度，每帧都要重新测量整页（掉帧的典型来源），而用户只是想看清「换了一块」。

    /** 进入位移：8dp。够看出方向，又不至于让上面的内容看起来在跳 */
    private fun softShift(): Float = Ui.dp(this, 8f).toFloat()

    /**
     * 立刻落到终态 [Anim.Visual.SHOW]，并停掉在跑的过渡。
     *
     * 三种场合都走这里：要藏起来之前、系统关了动画、以及初次建页 ——
     * 终态必须由 `applyVisual` 一次写全（alpha / scale / 位移），不允许停在半路。
     */
    private fun android.view.View.settleVisuals() {
        animate().cancel()
        val v = this
        with(AnimGlue) { v.applyVisual(Anim.Visual.SHOW) }
    }

    /**
     * 短促进入：略淡 + 从下方 8dp 归位 [Anim.MICRO]。
     *
     * 连点安全：先 [settleVisuals] 把上一次的半路状态收掉再摆起点，
     * 上一段动画的 cancel 分支也会写回 SHOW —— 因此不会出现停在 alpha≈0、看不见也点不到的块。
     * 系统「移除动画」时直接落终态，不额外等一帧。
     */
    private fun android.view.View.playSoftIn() {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { settleVisuals(); return }
        settleVisuals()
        val v = this
        with(AnimGlue) {
            v.applyVisual(Anim.Visual(0.62f, 1f, 0f, softShift()))
            v.animateVisual(Anim.Visual.SHOW, Anim.MICRO, EASE_OUT)
        }
    }

    // ---------------- 列表 ----------------

    private fun renderList() {
        // 标题行「＋」的可见性跟着筛选 / 搜索 / 标签走（判据 [canCreateHere]）
        refreshCreateEntry()
        listBox.removeAllViews()
        val items = try { load() } catch (e: Exception) {
            listBox.addView(Ui.text(this, "读笔记失败：" + (e.message ?: e.javaClass.simpleName), 13f, Ui.TX3))
            return
        }

        // 有搜索词时，先给出命中数和「清掉」的出口 —— 否则用户容易以为列表只剩这几条
        if (query.isNotEmpty() || tagFilter != null) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val what = when {
                tagFilter != null -> "标签 #" + tagFilter
                else -> "「" + query + "」"
            }
            head.addView(Ui.text(this, what + " · " + items.size + " 条", 12.5f, Ui.TX3),
                LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(Ui.button(this, "清掉") {
                query = ""; tagFilter = null; pages = 1; renderList(); rebuildSearchBox()
                // 清掉之后要把焦点和键盘一起收掉。
                //
                // 真机走查：点「清掉」，列表确实回到全部了，但搜索框仍然 focused、
                // 键盘仍然占着下半屏（dumpsys 里 mInputShown=true）。
                // 用户的意图是「退出搜索、看完整列表」，结果退一半 —— 列表出来了，
                // 半屏键盘还在，还得再按一次返回键。
                //
                // 光清 text 不够：EditText 有焦点就会重新弹输入法。
                clearSearchFocus()
            })
            listBox.addView(head)
        }

        if (items.isEmpty()) {
            listBox.addView(emptyView())
            return
        }

        // 按天分组：笔记是「时间流」，同一天的放一起比一堆孤零零的行好扫。
        // 按标题排时没有「天」的概念，分组头只会每隔几行插一句重复的日期，所以不分组。
        // 分组和行尾时刻都跟当前排序键走，否则时间顺序和分组顺序会互相打架。
        var lastDay = ""
        if (sort != Notes.Sort.TITLE) {
            for (s in items) {
                val day = dayLabel(shownAt(s))
                if (day != lastDay) {
                    lastDay = day
                    listBox.addView(TextView(this).apply {
                        text = day; textSize = 12.5f; setTextColor(Ui.TX3)
                        setPadding(0, Ui.dp(this@NotesActivity, 14f), 0, Ui.dp(this@NotesActivity, 4f))
                    })
                }
                listBox.addView(card(s))
            }
        } else {
            for (s in items) listBox.addView(card(s))
        }

        // 列表末尾：还有更早的就给一个入口。
        // 只对卡片流显示 —— 搜索/标签的结果本身限定了条数，也没有对应的游标。
        if (hasMoreFeed && query.isEmpty() && tagFilter == null) {
            listBox.addView(Button(this).apply {
                text = "加载更早的笔记"
                setOnClickListener { loadMore() }
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Ui.dp(this@NotesActivity, 10f)
            })
        }
        Brut.style(listBox)
    }

    /**
     * 取当前筛选条件下的列表。搜索优先于筛选 —— 用户打字就是想找东西，不该被当前 tab 挡住。
     *
     * 卡片流一次多要一条（`want + 1`）来判断「后面还有没有」，而不是靠游标翻页：
     * 排序有三套键（创建 / 修改 / 标题），游标只在其中一套上成立，多取一条对三套都成立，
     * 也顺便躲开「同毫秒创建」这类边界。搜索和标签结果在内存里按同一套规则重排 ——
     * 排序控件改了列表却纹丝不动的话，那个控件就在骗人。
     */
    private fun load(): List<Notes.Summary> {
        hasMoreFeed = false
        if (query.isNotEmpty()) return sortedForUi(Notes.search(db, query, 200))
        if (tagFilter != null) return sortedForUi(Notes.byTag(db, tagFilter!!, 500))
        val want = pageSize * pages
        val batch = Notes.feed(db, filter, want + 1, null, sort)
        if (batch.size > want) {
            hasMoreFeed = true
            return batch.subList(0, want)
        }
        return batch
    }

    /**
     * 展开下一页。
     *
     * 只把页数加一重画 —— 数据全部由 [load] 重新取，避免「已展开的旧页」和
     * 「新取的一页」在内存里拼出重复项。取完条数没长（或失败）就把页数退回去，
     * 不然列表会假装已经展开、下次点击又白跑一遍。
     */
    private fun loadMore() {
        val before = try { load().size } catch (e: Exception) { return }
        pages++
        val after = try { load().size } catch (e: Exception) {
            pages--
            toast("读笔记失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        if (after <= before) {
            pages--
            toast("没有更早的笔记了")
        }
        renderList()
    }

    /** 搜索结果 / 标签结果按当前排序键重排：置顶永远优先，其余跟随 [sort] */
    private fun sortedForUi(list: List<Notes.Summary>): List<Notes.Summary> {
        val cmp = when (sort) {
            Notes.Sort.MODIFIED -> compareByDescending<Notes.Summary> { it.updatedAt }.thenByDescending { it.id }
            Notes.Sort.CREATED -> compareByDescending<Notes.Summary> { it.createdAt }.thenByDescending { it.id }
            // 不传 String.CASE_INSENSITIVE_ORDER：compareBy(comparator, selector) 这个重载
            // 在这里推不出 K（selector 里又嵌了一层 ifBlank），换成显式小写键，语义一致。
            Notes.Sort.TITLE -> compareBy<Notes.Summary> {
                it.title.ifBlank { Note.firstLine(it.preview) }.lowercase()
            }.thenByDescending { it.id }
        }
        return list.sortedWith(compareByDescending<Notes.Summary> { it.pinned }.then(cmp))
    }

    /** 搜索框内容被程序改过（点「清掉」）之后，要把 EditText 同步回去 */
    private fun rebuildSearchBox() {
        fun walk(v: android.view.View) {
            if (v is EditText) { v.setText(query); return }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /**
     * 收起搜索焦点和输入法。
     *
     * 只在「用户明确要离开搜索」时调用（点清掉、或标签筛选接管时）——
     * 不要在 rebuildSearchBox 里顺手做，那样用户每敲一个字焦点就没了。
     */
    private fun clearSearchFocus() {
        fun walk(v: android.view.View) {
            if (v is EditText) {
                v.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                return
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    // ---------------- 一张卡片 ----------------

    /**
     * 列表的一行：标题 + 时间，第二行是正文预览。
     *
     * 没有卡片底、没有圆角、没有阴影 —— 一篇笔记就是两行文字，扫起来比一堆方块快，
     * 一屏也能多放下好几条。分隔靠留白（和末尾那条细线），不靠块。
     */
    private fun card(s: Notes.Summary): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@NotesActivity, 9f), 0, Ui.dp(this@NotesActivity, 9f))
            isClickable = true
            setOnClickListener { openDetail(s) }
            setOnLongClickListener { cardMenu(s); true }
        }

        // 缩略图：只有真的有图才解码。没有图的笔记不该为它读一次磁盘。
        if (s.hasImage) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val f = NoteAttach.thumbFile(this, s.uid)
            if (f != null && f.exists()) {
                // 只解 320 短边的小图；inSampleSize 兜一层，防缩略图缺失时读到原图
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                runCatching { BitmapFactory.decodeFile(f.absolutePath, o) }.getOrNull()?.let { iv.setImageBitmap(it) }
            }
            row.addView(iv, LinearLayout.LayoutParams(
                Ui.dp(this@NotesActivity, 40f), Ui.dp(this@NotesActivity, 40f)).apply {
                marginEnd = Ui.dp(this@NotesActivity, 10f)
            })
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 标题：有就用标题，没有就用正文首行；两者都空就是「空笔记」。
        // 有图但没有正文时，Note.firstLine 会返回「（空笔记）」—— 那是在说谎：
        // 真机上 7 条这样的笔记全都有截图，用户要的就是那张图。
        val head = when {
            s.title.isNotBlank() -> s.title
            s.preview.isBlank() && s.hasImage -> "一张截图"
            else -> Note.firstLine(s.preview)
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(this).apply {
            text = (if (s.pinned) "• " else "") + head
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // 时间跟着排序键走：按「修改时间」排却显示创建时间，用户会以为排错了
        titleLine.addView(Ui.text(this, fmtTime(shownAt(s)), 11f, Ui.TX3).apply {
            setPadding(Ui.dp(this@NotesActivity, 8f), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
        col.addView(titleLine)

        // 正文预览：标题之外的那部分，压成一行。等于标题就整行省掉，别重复一遍。
        val body = s.preview.replace("\n", " ").trim()
        if (body.isNotEmpty() && body != head) {
            col.addView(TextView(this).apply {
                text = body; textSize = 12.5f; setTextColor(Ui.TX3)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, Ui.dp(this@NotesActivity, 2f), 0, 0)
            })
        }

        // 标签逐个渲染成可点的 chip。
        //
        // 原来这里是一整串 "  #工作 #读书"，纯 TextView，点不了 ——
        // 而 [EXTRA_TAG]/tagFilter 这条链路（读 extra → byTag 查询 →
        // 「标签 #x」提示 → 「清掉」按钮 → 空态文案）早就全写好了，
        // 只是从来没有入口把 tag 传进来。整条腿长齐了，差这一根脚趾。
        if (s.tags.isNotEmpty()) {
            val tags = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@NotesActivity, 2f), 0, 0)
            }
            s.tags.take(3).forEach { tag ->
                tags.addView(Ui.text(this, "#" + tag, 11f, Ui.TX3).apply {
                    setOnClickListener { openTag(tag) }
                    // 点击目标太小手指点不准，靠 padding 把可点区域撑高一点
                    val padV = Ui.dp(this@NotesActivity, 5f)
                    val padH = Ui.dp(this@NotesActivity, 4f)
                    setPadding(padH, padV, padH, padV)
                })
            }
            col.addView(tags)
        }

        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        // 行与行之间只留一条发丝线：没有卡片块，靠这条线才不会糊成一片
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(row, LinearLayout.LayoutParams(-1, -2))
        holder.addView(android.view.View(this).apply { setBackgroundColor(Ui.HAIRLINE) },
            LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(this@NotesActivity, 0.6f))))
        return holder
    }

    /** 这一行显示哪个时刻：跟随当前排序键，避免「按修改时间排、显示创建时间」的错位 */
    private fun shownAt(s: Notes.Summary): Long =
        if (sort == Notes.Sort.MODIFIED && s.updatedAt > 0L) s.updatedAt else s.createdAt

    /**
     * 点卡片上的标签 → 只看这个标签。
     *
     * 走的是和 [EXTRA_TAG] 完全同一条路（tagFilter → byTag → 顶部「标签 #x · n 条」
     * + 「清掉」出口），只是不重启 Activity。
     */
    private fun openTag(tag: String) {
        tagFilter = tag
        // 搜索词和标签是两种筛选，混在一起会得到空结果且用户不知道为什么
        query = ""
        pages = 1
        renderList()
        rebuildSearchBox()
    }


    // ---------------- 交互 ----------------

    private fun openDetail(s: Notes.Summary) {
        try {
            startActivity(Intent(this, NoteDetailActivity::class.java)
                .putExtra(NoteDetailActivity.EXTRA_ID, s.id))
        } catch (e: Exception) { toast("打不开这条笔记") }
    }

    /** 长按：置顶 / 归档 / 删除 / 恢复。都在原地做，做完立刻重画，不用回列表 */
    private fun cardMenu(s: Notes.Summary) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        // 每个动作都检查受影响行数：0 行说明这条笔记已经不在库里了。
        // 外层的 runCatching 只兜异常，兜不住「影响 0 行」—— [Notes] 里的
        // execSQL 根本不抛，所以每个分支都得自己看返回值。
        if (s.deleted) {
            actions.add("恢复" to {
                if (Notes.restore(db, s.id) == 0) toast("这条笔记已经不在了")
                renderList()
            })
            actions.add("彻底删掉" to {
                // 明说不可恢复，并且真的连图和缩略图一起删 —— 原来这里 purge 不动
                // attachments 表，后面那句 deleteFiles(this, emptyList()) 等于什么都没删。
                ThemeDialogs.builder(this)
                    .setTitle("彻底删掉？")
                    .setMessage("这条笔记和它的图片会被永久清除，找不回来。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("彻底删掉") { _, _ ->
                        runCatching {
                            val ok = Notes.purge(db, s.id, s.uid)
                            if (!ok) toast("这条笔记已经不在了")
                            renderList()
                        }.onFailure { toast("没删掉：" + it.message) }
                    }
                    .show()
            })
        } else {
            actions.add((if (s.pinned) "取消置顶" else "置顶") to {
                if (Notes.setPinned(db, s.id, !s.pinned) == 0) toast("这条笔记已经不在了")
                renderList()
            })
            actions.add((if (s.archived) "取消归档" else "归档") to {
                if (Notes.setArchived(db, s.id, !s.archived) == 0) toast("这条笔记已经不在了")
                renderList()
            })
            actions.add("删掉" to {
                if (Notes.softDelete(db, s.id, System.currentTimeMillis()) == 0) toast("这条笔记已经不在了")
                renderList()
            })
        }
        val labels = actions.map { it.first }.toTypedArray()
        ThemeDialogs.builder(this)
            .setTitle(Note.firstLine(s.preview).ifBlank { "这条笔记" })
            .setItems(labels) { _, i -> runCatching { actions[i].second() }.onFailure { toast("没成功：" + it.message) } }
            .show()
    }

    // ---------------- 库目录：导入 / 导出 / 重建索引 ----------------

    /**
     * 右上角「⋯」：排序 / 导入 / 导出 / 重建索引。
     *
     * 这些都是低频、或只在特定时刻用的操作：摊在主页面上会挤掉列表空间，所以收进菜单。
     * 排序从 v2.7.2 起也在这里（原来是列表上方一整条常驻行）：子菜单标题写着当前排序、
     * 选中项打勾 —— 打开菜单就能看出「现在按什么排」，不用回列表里找高亮。
     * 选目录走系统的 [Intent.ACTION_OPEN_DOCUMENT_TREE]，不申请存储权限 ——
     * 用户选中哪棵树，我们只拿那一棵树的授权。
     */
    private fun showMoreMenu(anchor: android.view.View) {
        val pm = ThemeDialogs.popup(this, anchor, android.view.Gravity.END)
        val sub = pm.menu.addSubMenu(0, ID_SORT_MENU, 0, "排序 · " + sortLabel())
        sub.setGroupCheckable(GROUP_SORT, true, true)
        SORTS.forEachIndexed { i, (name, s) ->
            sub.add(GROUP_SORT, ID_SORT + i, i, name).apply {
                isCheckable = true
                isChecked = s == sort
            }
        }
        pm.menu.add(0, 1, 1, "导入目录…")
        pm.menu.add(0, 2, 2, "导出目录…")
        val last = runCatching { Notes.metaGet(db, KEY_TREE) }.getOrNull()
        if (last != null) pm.menu.add(0, 3, 3, "再导出到上次的目录")
        pm.menu.add(0, 4, 4, "重建索引")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> confirmImport()
                2 -> pickTree(ACT_EXPORT)
                3 -> {
                    val u = runCatching { Uri.parse(last) }.getOrNull()
                    if (u == null) toast("上次的目录不见了，重新选一个") else runExport(u)
                }
                4 -> runRebuild()
                else -> {
                    val i = it.itemId - ID_SORT
                    if (i in SORTS.indices) setSort(SORTS[i].second)
                }
            }
            true
        }
        pm.show()
    }

    /**
     * 导入前先说清楚会发生什么。
     *
     * 导入会往本机库里写东西，这个动作没法一键撤销 —— 所以先摆出规则再让用户点
     * 「选目录」，而不是选完文件夹才发现多了几十条笔记。
     *
     * 规则照抄 [NoteVault.importDirectory] 的实际行为：同 uid 内容一致 → 跳过（不产生
     * 第二份）；同 uid 但内容不同 → **两份都留下**，本机那份一个字节都不动，导入的那份
     * 换成新 uid 另存为「冲突副本」（frontmatter 里记着 `conflict_of`）。
     * 所以不能笼统说「保留本机版本」—— 那听起来像导入的那份被丢掉了。
     */
    private fun confirmImport() {
        if (busy) { toast("上一个任务还在跑，等它完"); return }
        ThemeDialogs.builder(this)
            .setTitle("从目录导入")
            .setMessage("会把选中目录里的 Markdown 笔记导进本机库。\n" +
                "同一条笔记（uid 相同）：内容一样就跳过；内容不同则两份都留下 —— " +
                "本机这份不动，导入的那份另存为一份「冲突副本」，由你决定留哪条。")
            .setPositiveButton("选目录") { _, _ -> pickTree(ACT_IMPORT) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 选一棵目录树。
     *
     * [pendingAction] 记录选完之后干什么（导入还是导出）—— 导入和导出用的是同一种选择器，
     * 不记下来的话 `onActivityResult` 只知道「拿到了一棵树」，不知道该往哪边跑。
     */
    private fun pickTree(action: Int) {
        if (busy) { toast("上一个任务还在跑，等它完"); return }
        pendingAction = action
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_TREE)
        } catch (e: Exception) {
            pendingAction = 0
            toast("打不开「选择文件夹」：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 目录选回来了。
     *
     * 先 `takePersistableUriPermission`：不拿持久授权的话，进程一被杀，下次点「再导出到
     * 上次的目录」就会 SecurityException —— 而那时用户早就忘了当初选的是哪个文件夹。
     * 拿不到授权不算致命（本次仍能用），但要告诉用户下次可能失效。
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val action = pendingAction
        pendingAction = 0
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val kept = try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (e: Exception) { false }
        if (kept) runCatching { Notes.metaPut(db, KEY_TREE, uri.toString()) }
        when (action) {
            ACT_IMPORT -> runImport(uri)
            ACT_EXPORT -> runExport(uri)
        }
    }

    private fun runExport(tree: Uri) {
        runVaultJob("导出") {
            val n = NoteVault.exportDirectory(this, tree)
            "已导出 " + n + " 个文件"
        }
    }

    private fun runImport(tree: Uri) {
        runVaultJob("导入") {
            val r = NoteVault.importDirectory(this, db, tree)
            var s = "导入 " + r.imported + " 条"
            // conflicts 与 imported **互斥**（见 NoteVault.importDirectory：命中同名分支只加
            // conflicts，不加 imported），所以这里不能说「其中」。这些是 uid 相同但内容不同的
            // 笔记：导入的那份换新 uid 另存为副本，本机原文一个字节都没动。
            if (r.conflicts > 0) s += "，另有 " + r.conflicts + " 条已存在且内容不同，已另存为副本（本机版本未改动）"
            // skipped 里混着三种：uid 相同且内容一致、正文超长被拒、文件读不出来/不是笔记
            if (r.skipped > 0) s += "，跳过 " + r.skipped + " 个（内容一致、超长或读不出来）"
            // work 的类型是 () -> String：这行不能少，否则 lambda 返回 Unit
            s
        }
    }

    private fun runRebuild() {
        runVaultJob("重建索引") {
            NoteVault.rebuild(this, db)
            "索引已重建"
        }
    }

    /**
     * 在后台线程跑一件库目录的活，回来后弹结果。
     *
     * 三条规矩：
     *  1. 写库 / 读写用户目录都可能卡到几秒，绝不能放在主线程上抖界面；
     *  2. 同一时刻只跑一件（[busy]）—— 两件事同时往 vault 里写，冲突没法解释；
     *  3. 回来时 Activity 可能已经没了（用户按了返回），别往已经 destroy 的窗口弹框。
     *
     * 失败时把 [NoteVault.lastError] 一起说出来：只有「失败了」三个字，用户没法判断
     * 是目录没权限、还是文件格式不对。
     */
    private fun runVaultJob(label: String, work: () -> String) {
        if (busy) { toast("上一个任务还在跑，等它完"); return }
        busy = true
        toast(label + "中…")
        val t = Thread {
            // 成功/失败连同文案一次算出来：原来 `val msg` + `val ok` 分在 try / catch
            // 两边赋值，在 Kotlin 里等于对同一个 val 重复赋值，编译不过。
            val (ok, msg) = try {
                true to work()
            } catch (e: Exception) {
                val why = runCatching { NoteVault.lastError(this) }.getOrNull()
                false to ((e.message ?: e.javaClass.simpleName) +
                    (if (why.isNullOrBlank()) "" else "\n" + why))
            }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    renderList()
                    refreshVaultNote()
                    ThemeDialogs.builder(this).setTitle(label + "完成").setMessage(msg)
                        .setPositiveButton("好", null).show()
                } else {
                    refreshVaultNote()
                    ThemeDialogs.builder(this).setTitle(label + "失败").setMessage(msg)
                        .setPositiveButton("好", null).show()
                }
            }
        }
        worker = t
        t.start()
    }

    /**
     * 把 [NoteVault.lastError] 刷到顶部那行小字上。
     *
     * 为什么是「提示」而不是「错误页」：迁移 / 重建索引失败时**库本身照样能读**，
     * 笔记正文一条不少。这种情况下把人挡在错误页外面，等于用一次后台失败否认了能读的数据。
     * 所以这行和列表并存、只占一行、不弹框；把原因原样写出来，让用户能判断严重程度。
     *
     * 刷新时机：onCreate（chrome 建好后）、每次 onResume（含从编辑器 / 详情页返回）、
     * 每个库目录任务收尾。全部复用已有入口，不另起轮询线程 ——
     * 迁移是异步的，它失败的那一刻本页通常不在前台，回到前台刷这一次就够了。
     */
    private fun refreshVaultNote() {
        val tv = vaultNote ?: return
        // lastError 在极少数情况下会碰磁盘（实现里只是读一个 volatile 字段，这里仍兜一层）
        val why = runCatching { NoteVault.lastError(this) }.getOrNull()
        if (why.isNullOrBlank()) {
            tv.visibility = android.view.View.GONE
            tv.text = ""
            return
        }
        tv.text = "⚠ 后台任务没成：" + why
        tv.visibility = android.view.View.VISIBLE
        Brut.style(tv)
    }
    /**
     * 「当前筛选下能不能新建笔记」：标题行那个「＋」的可见性判据（[refreshCreateEntry]）。
     *
     * 回收站 / 归档 / 置顶里不显示：这些是「回看已有内容」的场景，
     * 放新建入口会让人困惑（尤其在回收站里）。搜索和标签同理 —— 那时用户是在找东西。
     * 空列表不再单独画一个「写一条」大按钮（v2.7.2）：标题行那个「＋」在同一时刻总是可见，
     * 两处并存只是把同一个动作说两遍；空列表的文案直接指向右上角的「＋」。
     */
    private fun canCreateHere(): Boolean =
        query.isEmpty() && tagFilter == null && filter == Notes.Filter.ALL

    /**
     * 标题行「＋」此刻该不该出现。
     *
     * 判据是 [canCreateHere]：回收站 / 归档 / 置顶是回看场景，搜索和标签是「找东西」——
     * 这些情况下放新建入口只会让人多点一次。另外提醒模块下也没有「写笔记」这回事。
     * 两个调用点：[buildChrome] 之后由 [applyModule] 管模块切换，[renderList] 管筛选与搜索变化 ——
     * 列表每次重绘都会走一遍，所以这里只改 visibility，不重建控件。
     */
    private fun refreshCreateEntry() {
        val b = createButton ?: return
        b.visibility = if (module == MODULE_NOTES && canCreateHere())
            android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun emptyView(): android.view.View {
        val msg = when {
            query.isNotEmpty() -> "没搜到「" + query + "」\n换个词试试，或者清掉搜索";
            tagFilter != null -> "这个标签下还没有笔记";
            filter == Notes.Filter.TRASH -> "回收站是空的";
            filter == Notes.Filter.ARCHIVED -> "还没有归档的笔记";
            filter == Notes.Filter.PINNED -> "还没有置顶的笔记";
            else -> "还没有笔记。\n\n截个图，悬浮球会弹速记窗；\n也可以点右上角「＋」写一条 —— 正文里打 # 就能加标签。";
        }
        val tv = TextView(this).apply {
            text = msg; textSize = 13.5f; alpha = 0.7f;
            setPadding(0, Ui.dp(this@NotesActivity, 32f), 0, Ui.dp(this@NotesActivity, 8f))
        }
        // 空列表不再额外画一个整宽「写一条」按钮：标题行那个「＋」此刻一定可见（判据同上），
        // 两处同时出现等于把同一件事说两遍。文案里直接指路到「＋」——
        // 这一条是当年真人走查卡住的地方（装了 App、没启动悬浮球，一条笔记都记不了），
        // 所以「去哪儿写」必须在空列表上明说，不能只靠一个图标让人猜。
        return tv
    }

    // ---------------- 小工具 ----------------


    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    /** 「今天 / 昨天 / 10月20日」—— 分组头只需要日粒度 */
    private fun dayLabel(at: Long): String {
        val now = System.currentTimeMillis()
        val d = ((Todo.dayStart(now) - Todo.dayStart(at)) / 86_400_000L).toInt()
        return when {
            d == 0 -> "今天"
            d == 1 -> "昨天"
            d in 2..6 -> d.toString() + " 天前"
            else -> {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
                (c.get(java.util.Calendar.MONTH) + 1).toString() + "月" +
                    c.get(java.util.Calendar.DAY_OF_MONTH) + "日"
            }
        }
    }

    /** 卡片右下那个时刻：当天显示 HH:mm，跨天带上日期 */
    private fun fmtTime(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val hm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        return if (dayLabel(at) == "今天") hm else dayLabel(at) + " " + hm
    }

    companion object {
        const val EXTRA_FILTER = "filter"
        const val EXTRA_TAG = "tag"

        /** 「笔记 / 提醒」模块落点。底栏的裸 Intent 不带它 —— 不带就保持用户原来停的那一模块 */
        const val EXTRA_MODULE = "module"
        /** 两个模块的取值：0 笔记（本页主体）/ 1 提醒。只当数组下标用，不做语义判断 */
        const val MODULE_NOTES = 0
        const val MODULE_REMINDERS = 1

        /** 提醒模块的两个子视图。顺序就是 [reminderTab] 的取值 0 / 1 —— 不能调换 */
        private val REMINDER_TABS = listOf("待办", "随手记")

        /** 排序菜单：名字 → 排序键。顺序就是「⋯ → 排序」里从上到下的顺序 */
        private val SORTS = listOf(
            "最近修改" to Notes.Sort.MODIFIED,
            "创建时间" to Notes.Sort.CREATED,
            "标题" to Notes.Sort.TITLE,
        )

        /** 「⋯」菜单的 item id：排序子菜单本身 + 三个排序项（从 [ID_SORT] 起按 [SORTS] 顺序编号） */
        private const val ID_SORT_MENU = 10
        private const val ID_SORT = 20
        /** 排序项共用的菜单组：`setGroupCheckable(exclusive = true)` 靠它保证只有一个勾 */
        private const val GROUP_SORT = 1

        private const val REQ_TREE = 4101
        private const val ACT_IMPORT = 1
        private const val ACT_EXPORT = 2
        /** 上次导出到的目录树 uri：存下来才有「再导出到上次的目录」 */
        private const val KEY_TREE = "vault.tree"

        fun intent(ctx: android.content.Context, filter: Int = 0, tag: String? = null): Intent =
            Intent(ctx, NotesActivity::class.java)
                .putExtra(EXTRA_FILTER, filter)
                .putExtra(EXTRA_TAG, tag)

        /** 直达「提醒」模块（聊天「＋」的「添加提醒」一类落点用）。不带 filter/tag：不动笔记那边的筛选 */
        fun remindersIntent(ctx: android.content.Context): Intent =
            Intent(ctx, NotesActivity::class.java)
                .putExtra(EXTRA_MODULE, MODULE_REMINDERS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
