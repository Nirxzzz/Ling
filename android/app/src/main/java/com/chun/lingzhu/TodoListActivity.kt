package com.chun.lingzhu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 「记事 · 提醒」的独立落点页：通知（[EXTRA_TAB]）、聊天「＋」的「添加提醒」、设置页的模块入口都进这里。
 *
 * 它和 [NotesActivity] 是同一个「记事」页的两种进入姿势：
 *  - 本页选中「提醒」模块 —— 标题、两模块切换、底栏三样与记事页一模一样；
 *  - 点「笔记」切回 [NotesActivity]；底栏是同一份 [BottomNav]。
 *
 * 「提醒」**不占底栏槽位**：底栏仍然是 agent / 学习 / 记事 / 设置四格，提醒只是「记事」里的一个模块。
 * 底栏也不能塞进页面里的 ScrollView —— 那是「双底栏 + 嵌套滚动」，滚两下栏就跟着跑了。
 *
 * 本页只是列表和管理 —— **写系统日历不给按钮**，一律跳 [TodoCardActivity] 的确认卡，
 * 这样「不静默写日历」只有一条路径，绕不过去。
 * 「加一条」按句子里的时间关键词就地解析（`明天 15:30 交周报` / `今晚8点 买菜` / `3天后`），
 * 解析规则是 [Todo.parseWhen] 这个纯函数，单测直接测。
 */
class TodoListActivity : Activity() {
    private lateinit var root: LinearLayout

    /** 列表外的滚动容器：同栏刷新重画后要把它原来的位置放回去（见 [refresh]） */
    private lateinit var scroll: ScrollView

    /** 挂着的「重画后把滚动位置放回去」监听：同一轮里再来一次刷新就先摘掉旧的，免得回放到过期位置 */
    private var scrollRestore: ViewTreeObserver.OnGlobalLayoutListener? = null

    /**
     * 页内**只建一次**的骨架：子视图按钮行，与它下面的内容位 [body]。
     *
     * 以前每次 [render] 都把整页拆了重摆（标题 / 说明 / 按钮 / 清单全新建），同栏刷新也跟着重建；
     * 现在刷新与数据变动只重摆 [body]，换栏的过渡也只作用在它上面 —— 不牵动整页高度。
     */
    private var subTabBar: LinearLayout? = null
    private var body: LinearLayout? = null

    /** 0 待办 / 1 随手记。这是 [EXTRA_TAB] 的语义，通知落点（`todoTabOf`）依赖它，不能改 */
    private var tab = 0

    /** 这次 resume 是 [onNewIntent] 把页面复用提回前台带出来的（refresh 时已刷过）：onResume 别再整页重建 */
    private var reused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        tab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0
        // 外层一列：上面可滚动的列表区，下面固定的底栏（与记事页同一套，见 BottomNav.attach）
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@TodoListActivity, 16f), 0, Ui.dp(this@TodoListActivity, 16f), 0)
        }
        val sv = ScrollView(this).apply { addView(root); isFillViewport = true }
        scroll = sv
        page.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        Brut.page(this, page)
        BottomNav.attach(page, this, BottomNav.NOTES)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::root.isInitialized) return
        // 复用提回前台：[onNewIntent] 已经处理过这次 Intent（同栏刷新 / 换栏重建），这里不能再来一遍 ——
        // 裸 Intent（底栏切回）的语义就是不碰「加一条」里没提交的字和滚动位置。
        // 系统保证「先 onPause，onNewIntent 之后一定跟一次 onResume」，所以这个标记必然在这里用掉。
        if (reused) { reused = false; return }
        render()   // 从确认卡 / 记期页回来时列表要跟着变
    }

    /**
     * 页面被复用（SINGLE_TOP / REORDER_TO_FRONT 提回前台）时的新 Intent：带着 [EXTRA_TAB] 的显式落点就按它走 ——
     * 落点不同换栏，**落点相同也要刷新**。通知 tab=1（简报 tab=0）落在已经在栈里的这一页时 onCreate 不会再走，
     * 只判「要不要换栏」会把被盖住期间新增的随手记 / 改过的待办一起吞掉，页面显示的还是遮盖前的样子。
     * 裸 Intent（底栏切回）什么都不碰：滚动位置、正在输入的「加一条」都原样留着。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reused = true
        if (!intent.hasExtra(EXTRA_TAB)) return
        val want = if (intent.getIntExtra(EXTRA_TAB, 0) == 1) 1 else 0
        if (want == tab) {
            if (::root.isInitialized) refresh()   // 同栏：刷数据，但草稿和滚动位置留着
            return
        }
        // 落点不同 = 用户/系统明确要求换栏（通知、聊天「＋」、设置入口）：这一下播过渡。
        // 同栏刷新（上面那一支）与裸 Intent 都不播 —— 它们不是切换。
        tab = want
        if (::root.isInitialized) render(animate = true)
    }

    /**
     * 原地刷新：数据重画一遍，但「加一条 / 想到什么就写一句」里没提交的草稿（含光标、焦点）和滚动位置原样放回去。
     *
     * 与 [render] 的取舍不同：[render] 是数据变动后的重建（加完一条、删掉一条、换栏），草稿本来就该清空；
     * 回到前台只是把被遮盖期间别处产生的改动补显出来，用户正在写的字不该是这次刷新的代价。
     * 焦点只在「原来就在这个框里打字」时跟着回来 —— 没在输入的人不该凭空被弹键盘。
     */
    private fun refresh() {
        val y = scroll.scrollY
        val old = firstInput()
        val draft = old?.text?.toString()
        // 光标可能没有（selectionStart = -1）：那种情况放回末尾，不硬塞到开头
        val caret = old?.selectionStart?.takeIf { it >= 0 } ?: -1
        val typing = old?.hasFocus() == true
        render()
        if (draft != null) firstInput()?.apply {
            setText(draft)
            setSelection(if (caret < 0) draft.length else caret.coerceAtMost(draft.length))
            if (typing) requestFocus()
        }
        // 新视图还没量过高度，这时 scrollTo 会被夹到 0：等这一轮布局完再放回去。
        if (y > 0) {
            val l = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    scrollRestore = null
                    scroll.scrollTo(0, y)
                }
            }
            scrollRestore = l
            scroll.viewTreeObserver.addOnGlobalLayoutListener(l)
        }
    }

    /** 当前这一栏唯一那个输入框（待办是「加一条」，随手记是「想到什么就写一句」）；面板不暴露 id，按树找 */
    private fun firstInput(g: ViewGroup = root): EditText? {
        for (i in 0 until g.childCount) {
            when (val c = g.getChildAt(i)) {
                is EditText -> return c
                is ViewGroup -> firstInput(c)?.let { return it }
            }
        }
        return null
    }

    /**
     * 重摆页面。
     *
     * 骨架（标题 / 模块切换 / 记期说明 / 子视图按钮 / 「关掉」）只建一次：同栏刷新（[refresh]）
     * 与数据变动只重摆内容位 [body]，不再把 [TodoPanels.header] 那段一起拆了重建。
     *
     * @param animate 只有用户真的换了栏（点「待办 / 随手记」，或带 [EXTRA_TAB] 的显式 Intent 落点不同）
     *   才为 true。同栏刷新、data 变动、返回本页都不播 —— 那些不是「切换」，播了就是整块闪一下。
     */
    private fun render(animate: Boolean = false) {
        // 上一轮刷新挂出去的「放回滚动位置」还没跑到就重画了（换栏 / 数据变动）：旧位置对新内容没意义，先摘掉
        scrollRestore?.let { scroll.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        scrollRestore = null
        if (body == null) buildPage()
        fillSubTabs()
        renderBody(animate)
    }

    /** 建树（只建一次）：标题、模块切换、记期入口、子视图按钮、内容位、「关掉」 */
    private fun buildPage() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "记事"; textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(moduleBar())
        // 记期入口 + 提醒说明（与记事页的「提醒」模块共用同一份，见 TodoPanels.header）
        TodoPanels.header(this, root)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in TAB_NAMES.indices) {
            bar.addView(Button(this).apply {
                setOnClickListener { pickTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(box)
        root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
        subTabBar = bar
        body = box
        Brut.style(root)
    }

    /** 待办 / 随手记 两个子视图（[EXTRA_TAB] 就是切这两个）。选中态只改文字和高亮，不重建按钮 */
    private fun fillSubTabs() {
        val bar = subTabBar ?: return
        for (i in 0 until minOf(bar.childCount, TAB_NAMES.size)) {
            val b = bar.getChildAt(i) as? Button ?: continue
            b.text = (if (tab == i) "▸ " else "") + TAB_NAMES[i]
            b.tag = if (tab == i) Brut.PRIMARY else null
        }
        Brut.style(bar)
    }

    /**
     * 点「待办 / 随手记」。换栏才播过渡；再点当前那一格照旧重读一次数据（别处可能刚建了一条），
     * 但内容没换，不播。
     */
    private fun pickTab(i: Int) {
        val switched = i != tab
        tab = i
        render(animate = switched)
    }

    /**
     * 只重摆内容位。数据变动（[TodoPanels] 的 `changed`）也走这里：数据变了不等于用户切了栏，
     * 不该带动效；草稿本来就在这次重建里清掉（和以前整页重画一致）。
     */
    private fun renderBody(animate: Boolean) {
        val box = body ?: return
        box.removeAllViews()
        if (tab == 0) TodoPanels.todos(this, box) { renderBody(false) }
        else TodoPanels.memos(this, box) { renderBody(false) }
        Brut.style(box)
        if (animate) box.playSoftIn() else box.settleVisuals()
    }

    /**
     * 「笔记 / 提醒」两个模块。选中只靠主色底（[Brut.PRIMARY]），与记事页 [NotesActivity] 的画法一致 ——
     * 用户从两处进来看到的是同一个控件，不是两套长相差一点的按钮。
     */
    private fun moduleBar(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in listOf("笔记", "提醒").withIndex()) {
            bar.addView(Button(this).apply {
                text = name
                if (i == 1) tag = Brut.PRIMARY
                setOnClickListener { if (i == 0) openNotes() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    // ---------------- 轻过渡（只在用户换栏时用）----------------
    //
    // 只动 alpha + 纵向小位移：**不碰高度、不缩放**。整页在 ScrollView 里，逐帧改高度会让每帧
    // 都重新测量整页（掉帧的典型来源），而用户只需要看清「换了一块」。

    /** 换栏时的进入位移：8dp。够看出方向，又不像整页在跳 */
    private fun softShift(): Float = Ui.dp(this, 8f).toFloat()

    /** 立刻落到终态 [Anim.Visual.SHOW] 并停掉在跑的过渡（alpha / scale / 位移一次写全，绝不半路） */
    private fun android.view.View.settleVisuals() {
        animate().cancel()
        val v = this
        with(AnimGlue) { v.applyVisual(Anim.Visual.SHOW) }
    }

    /**
     * 短促进入：略淡 + 从下方 8dp 归位 [Anim.MICRO]。
     *
     * 连点安全：先 [settleVisuals] 收掉上一段的半路状态再摆起点；被 cancel 的那一段也会写回
     * [Anim.Visual.SHOW] —— 所以不会停在 alpha≈0、看不见也点不到的块上。系统关了动画就直接落终态。
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

    /**
     * 切到「笔记」模块：记事页若已在栈里就提回前台复用（筛选 / 搜索词 / 滚动位置都还在），
     * 否则新建一个。本页留在栈里，系统返回照常回到「提醒」。
     */
    private fun openNotes() {
        try {
            startActivity(Intent(this, NotesActivity::class.java)
                .putExtra(NotesActivity.EXTRA_MODULE, NotesActivity.MODULE_NOTES)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "打不开笔记", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        /** 两个子视图的名字。顺序就是 [EXTRA_TAB] 的取值 0 / 1 —— 不能调换 */
        private val TAB_NAMES = listOf("待办", "随手记")
        /**
         * 通知落点 / 聊天「＋」的「添加提醒」/ 设置里的「提醒」入口。
         *
         * flag 与底栏 [BottomNav.go] 是同一套约定：
         *  - REORDER_TO_FRONT：本页若已在栈里（哪怕正被详情页 / 记期页盖着）就提回前台复用；
         *  - SINGLE_TOP：栈顶就是本页时直接复用，不再新建一份 —— 这两条到齐才会走 [onNewIntent]。
         * 清单里那句 `launchMode="singleTop"` 是另一半但**替代不了** REORDER：它只管栈顶，
         * 被盖住的实例照样会压出第二份。别拿「加了 launchMode」当成「复用了」。
         *
         * NEW_TASK 只在调用方不是 Activity 时补：那种 Context（通知派发链里的应用上下文）没有
         * 自己的任务栈，不补会直接抛 AndroidRuntimeException；Activity 调用方带上它反而会把
         * 「同一任务里提回前台」变成「按 affinity 找任务」，与底栏那条路径不一致。
         *
         * [EXTRA_TAB] 始终写进 Intent：这是「显式落点」，复用时按它切栏 —— 落点与当前栏相同也照样刷新
         * （被确认卡 / 记期页 / 常驻通知遮住期间数据可能变了），只把草稿和滚动位置留着；
         * 不带它的裸 Intent（底栏切回）不碰页面状态，见 [onNewIntent]。
         */
        fun intent(ctx: android.content.Context, tab: Int = 0): Intent {
            var flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (ctx !is Activity) flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
            return Intent(ctx, TodoListActivity::class.java)
                .putExtra(EXTRA_TAB, tab)
                .addFlags(flags)
        }
    }
}

/**
 * 「提醒」模块的内容面板：待办清单 / 随手记。
 *
 * 为什么单拎出来：这两块原来只长在 [TodoListActivity] 里，而记事页的「提醒」模块要渲染同一份内容。
 * 复制一遍必然漂移（改了一边忘了另一边，用户看到两个不一样的世界），所以面板本体只留这一份，
 * 两个入口各自把自己那一层容器传进来。面板只往 [box] 里加 View、只经 `changed` 回调通知重建，
 * 不碰任何 Activity 状态 —— 存储仍然是 [Todo.Store] / [Memo.Store]，一条没搬、没复制。
 *
 * 写日历依旧只走确认卡（[TodoActivity]）；这里只给「详情 / 日历」的入口。
 */
internal object TodoPanels {

    /** 模块头：一段说明 + 记期入口 + Agent 联动说明。两处都必须有，所以只写一份 */
    fun header(a: Activity, box: LinearLayout) {
        box.addView(TextView(a).apply {
            text = "待办到点会响；记期管重复的（日历 / 每周几）；随手记是随手一句，不进清单。"
            textSize = 12.5f; alpha = 0.75f
            setPadding(0, Ui.dp(a, 4f), 0, Ui.dp(a, 8f))
        })
        // v1.86 记期入口：能重复的提醒（日历 / 每周几）。它自己一页，这里只放入口。
        box.addView(Button(a).apply {
            text = "记期（日历 / 每周提醒）"
            setOnClickListener {
                try { a.startActivity(ScheduleActivity.intent(a)) }
                catch (_: Exception) { toast(a, "打不开记期页") }
            }
        })
        // Agent 联动说明（两处共用）：只说真实存在的链路 —— 应用内 Agent + 工具权限 + 共享待办清单。
        box.addView(TextView(a).apply {
            text = "可让应用内已开启「提醒」工具的 Agent 创建提醒；在对话列表长按 Agent 可设置工具权限。"
            textSize = 12f; alpha = 0.75f
            setPadding(0, Ui.dp(a, 6f), 0, Ui.dp(a, 4f))
        })
    }

    /** 待办清单：过期 / 今天 / 以后三组 + 「加一条」。[changed] 在数据变动后叫重建 */
    fun todos(a: Activity, box: LinearLayout, changed: () -> Unit) {
        val store = Todo.Store(a)
        val now = System.currentTimeMillis()
        val list = Todo.sorted(store.list())
        val overdue = Todo.due(list, now)
        val today = list.filter { it.dueAt in now until now + 86400_000L }
        val rest = list.filter { it !in overdue && it !in today }

        box.addView(TextView(a).apply {
            text = "共 ${list.count { !it.done }} 件没做（最多留 ${Todo.MAX} 条）"; textSize = 12.5f; alpha = 0.75f
            setPadding(0, Ui.dp(a, 6f), 0, Ui.dp(a, 4f))
        })
        if (overdue.isNotEmpty()) group(a, box, "过期 / 到点了", overdue, store, changed)
        if (today.isNotEmpty()) group(a, box, "今天", today, store, changed)
        if (rest.isNotEmpty()) group(a, box, "以后", rest, store, changed)

        box.addView(TextView(a).apply {
            text = "加一条"; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(a, 18f), 0, Ui.dp(a, 4f))
        })
        val ed = EditText(a).apply { hint = "比如「明天 15:30 交周报」"; inputType = InputType.TYPE_CLASS_TEXT }
        box.addView(ed)
        box.addView(Button(a).apply {
            text = "加上"; tag = Brut.PRIMARY
            setOnClickListener {
                val raw = ed.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return@setOnClickListener
                val p = Todo.parseWhen(raw, now)
                val nid = store.add(p.title, p.dueAt)
                if (nid > 0) { TodoReceiver.schedule(a, nid); changed() }
            }
        })
        tip(a, box, "时间写在句子里就行：明天 / 今晚 / 后天 / 3天后 / 15:30 / 「周五」。解析不了就把整句当标题、默认一小时后提醒，剩下的字进备注。")
    }

    /** 随手记：一句一条，可转成待办。常驻通知那条输入框（[MemoNotify]）不在这里，入口照旧 */
    fun memos(a: Activity, box: LinearLayout, changed: () -> Unit) {
        val memo = Memo.Store(a)
        val store = Todo.Store(a)
        val list = memo.list()
        box.addView(TextView(a).apply {
            text = "共 ${list.size} 条（最多留 ${Memo.MAX} 条）· 通知栏那条常驻通知里可以直接打字记"; textSize = 12.5f; alpha = 0.75f
            setPadding(0, Ui.dp(a, 6f), 0, Ui.dp(a, 4f))
        })
        val ed = EditText(a).apply { hint = "想到什么就写一句"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        box.addView(ed)
        box.addView(Button(a).apply {
            text = "记下"; tag = Brut.PRIMARY
            setOnClickListener {
                if (memo.add(ed.text?.toString().orEmpty()) > 0) { MemoNotify.refresh(a); changed() }
            }
        })
        if (list.isEmpty()) {
            box.addView(TextView(a).apply {
                text = "还没有记过。\n下拉状态栏里 ${Preset.appName} 那条常驻通知，输入框里写一句就存下来了。"; textSize = 14f
            })
            return
        }
        for (m in list) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(a).apply {
                text = "${Memo.clip(m.text, 300)}\n${Memo.fmtAt(m.at)}" + if (m.todoId > 0) " · 已转成待办" else ""
                textSize = 14f; setLineSpacing(0f, 1.3f)
            })
            val acts = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Button(a).apply {
                text = "转成待办"
                setOnClickListener {
                    val nid = store.add(Memo.clip(m.text, 120), System.currentTimeMillis() + 3600_000L)
                    if (nid > 0) { memo.link(m.id, nid); TodoReceiver.schedule(a, nid); changed() }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            acts.addView(Button(a).apply {
                text = "删掉"; tag = Brut.DANGER
                setOnClickListener { memo.remove(m.id); changed() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(acts)
            box.addView(row)
            Brut.card(row)
        }
    }

    /** 一组待办：组头 + 每条一格（完成 / 详情·日历 / 删掉） */
    private fun group(
        a: Activity,
        box: LinearLayout,
        name: String,
        items: List<Todo.Item>,
        store: Todo.Store,
        changed: () -> Unit,
    ) {
        box.addView(TextView(a).apply {
            text = "$name（${items.count { !it.done }}）"; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(a, 18f), 0, Ui.dp(a, 4f))
        })
        for (t in items) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(a).apply {
                text = (if (t.done) "✓ " else "") + t.text +
                    "\n" + Todo.fmtDue(t.dueAt, System.currentTimeMillis()) { Todo.fmtHM(it) } +
                    (if (t.calId > 0) " · 已进日历" else "") +
                    (if (t.note.isBlank()) "" else "\n备注：${Memo.clip(t.note, 60)}")
                textSize = 14f; setLineSpacing(0f, 1.3f)
            })
            val acts = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Button(a).apply {
                text = if (t.done) "改回未完成" else "完成"
                setOnClickListener { store.setDone(t.id, !t.done); TodoReceiver.reschedule(a); changed() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // 详情 / 写日历只在这一条路径上，必须经过确认卡
            acts.addView(Button(a).apply {
                text = "详情 / 日历"
                setOnClickListener { a.startActivity(TodoActivity.intent(a, t.id)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            acts.addView(Button(a).apply {
                text = "删掉"; tag = Brut.DANGER
                setOnClickListener { store.remove(t.id); TodoReceiver.reschedule(a); changed() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(acts)
            box.addView(row)
            Brut.card(row)
        }
    }

    private fun tip(a: Activity, box: LinearLayout, s: String) = box.addView(TextView(a).apply {
        text = s; textSize = 12f; alpha = 0.75f; setPadding(0, Ui.dp(a, 4f), 0, 0)
    })

    private fun toast(a: Activity, s: String) =
        android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show()
}
