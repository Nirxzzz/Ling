package com.chun.lingzhu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 学习与复习的**导航页**（v2.3.3 起；原来是导航 + 计划表单挤在一页）。
 *
 * ## 这一页只回答一个问题：今天要干什么
 * 顶部是「我现在有多少东西要过」的摘要，下面全是去处的按钮。
 * 计划表单、牌组选择、提醒设置、弹窗设置都搬去了 [StudyPlanActivity] ——
 * 它们几个月才改一次，而这一页每天都要打开，不该让用户滚过一屏输入框才能点到「开始复习」。
 *
 * ## 顺序是刻意的（v2.7.2 起分三档，不再是一串等宽按钮）
 * 1. 摘要（欠多少 + 库有多大）+ `开始学习 / 开始复习` 两个主按钮放最上面：这是 90% 的情况，一进来就能点。
 * 2. 次行两列放计划设置与文档导入：都属于「今天/以后要练什么」这条主线，但几个月才动一次，
 *    所以做成短标题的并列小入口，长说明挂在 `contentDescription` 里。
 * 3. 最后「其他功能」是一段**默认收起**的可展开区，由 [Features.sorted] 现算
 *    （知识卡库、学习画像、数据面板、Anki 牌组）：画哪些由 [draw] 里那份 id 白名单 +
 *    「模块管理」的开关决定，标题与顺序只在 registry 一处，本页不认识具体类。
 *    待办 / 记忆辅助不在白名单里 —— 它们各有自己的入口位置。
 *    展开态只存在本页字段里（[restOpen]），不写 pref：不为一次展开新增存档键。
 *
 * ## 不在这里做什么
 * 不存任何学习进度，也不持有 [StudyPlan] 的可变副本 —— 摘要里的「每轮最多 N 项」是从
 * `prefs.studyPlan` 现读的，[onResume] 每次重画，所以从设置页改完返回会立刻看到新数字。
 */
class StudyActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val store by lazy { KnowledgeCard.Store(this) }
    private lateinit var root: LinearLayout
    /** 「其他功能」展开没有。只活在本页（不落 pref）：从别的页切回来还在原样，重进 App 回到收起 */
    private var restOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "学习与复习"
    }

    override fun onResume() { super.onResume(); draw() }

    /**
     * 从别的区域经底部栏切回来：带 FLAG_ACTIVITY_SINGLE_TOP 时不会重建，走这里。
     * 本页数据在 onResume 里已经重画，没必要再来一遍。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun draw() {
        Ui.applyTheme(prefs)
        val plan = prefs.studyPlan
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底栏（BottomNav）挂在外层 page 上，滚动区在栏的上沿结束，不会被压住；
            // 底部只留与普通内页一致的 28dp 呼吸位。
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        // 外层一列：可滚的学习内容 + 固定的底部入口栏（塞进 scroll 里会跟着滚走）
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page); Brut.page(this, page)
        BottomNav.attach(page, this, BottomNav.STUDY)
        label("学习与复习", 24f)

        summary(plan)

        button("开始学习 · 每轮最多 ${plan.newLimit} 项", primary = true) { startActivity(ReviewActivity.intent(this).putExtra("studyNew", true)) }
        button("开始复习 · 每轮最多 ${plan.reviewLimit} 项", primary = true) { startActivity(ReviewActivity.intent(this)) }

        // 次行两列：学习计划 / 导入文档。原来是两条整宽按钮、标题里各拖一长串说明
        // （「限额 / 牌组 / 提醒」「TXT / MD / DOC / DOCX / 文本 PDF」），两行近 100dp 只为一个月点一次的东西。
        // 现在短标题并排，说明移进 contentDescription —— 读屏仍能听到，视觉上不再堆副标题。
        val secondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        secondary.addView(sideButton("学习计划", "限额 / 牌组 / 提醒") {
            startActivity(Intent(this, StudyPlanActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        secondary.addView(sideButton("导入文档", "TXT / MD / DOC / DOCX / 文本 PDF，导入后生成知识卡") {
            startActivity(Intent(this, ImportActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        root.addView(secondary)

        // 其余模块入口不再逐条硬编码：知识卡库 → 学习画像 → 数据面板 → Anki 牌组，由 [Features.sorted]
        // 现算，标题 / 副标题 / 顺序只在 registry 一处。这里只认 id、不引用具体 Activity 类。
        //
        // **本页不是「registry 里有什么就全画」**，下面的 [here] 就是白名单 —— 新的功能包想让入口
        // 出现在这一页，得往那个 set 里加一行 id（只改这一处，永远不写类名）。之所以不给全画：
        // todo / memory-aid 各有自己的入口位置 —— 待办挂在记事页与设置「提醒」组，记忆辅助必须
        // 带着某张卡的 deck/正面/背面才有意义（空着进去只会看到「这张卡没有正面」）。把它们硬搬
        // 到这里不是补齐模块，是多出两个走不通的入口。
        // 关掉的模块（[Prefs.disabledFeatures]）整个不画；真被点到时 [Features.open] 再兜一次底
        // —— 深链、旧任务栈恢复、上一版留下的页面仍可能走到这里。
        val disabled = prefs.disabledFeatures
        val here = setOf("stats", "twin", "knowledge", "anki")
        val rest = Features.sorted().filter { it.id in here && Features.isEnabled(disabled, it.id) }
        if (rest.isNotEmpty()) otherBlock(rest)

        Brut.style(root)
    }

    /**
     * 统计摘要：今天还欠多少（主行）+ 库有多大（次行小字）。
     *
     * 数字**按当前学习计划过滤**（`plan.acceptsCard` / `acceptsWord`）：
     * 用户把范围限定在「考研数学」以后，摘要就该只说这个范围里的事，
     * 而不是拿全库的几千张吓唬他。真实到期数在复习页会再算一遍（那里也按计划过滤），两处口径一致。
     *
     * 只报**真的算得出来**的数：知识卡待复习 = 非新卡且已到期；单词只在单词复习开着时报，
     * 关着就直说「已关」—— 报了也不会复习，只会让人以为漏做了（v2.4 的口径不变）。
     * 原来两行都塞着「知识卡」三个字（「知识卡 23 张 · 新卡 8 张」「待复习：知识卡 5 张」），
     * 扫一眼分不清哪个是要做的事；现在主行只说欠多少，库的总量退到次行。
     */
    private fun summary(plan: StudyPlan) {
        val now = System.currentTimeMillis()
        val all = store.list().filter { plan.acceptsCard(it.deck) }
        val cardDue = all.count { !it.fsrs.isNew && it.isDue(now) }
        val wordDue = WordSrs.all(prefs).count { (key, card) -> plan.acceptsWord(key.substringBefore("\u0000")) && Fsrs.isDue(card, now) }
        root.addView(TextView(this).apply {
            text = "待复习：知识卡 " + cardDue + " 张" +
                (if (prefs.wordReviewOn) " · 单词 " + wordDue + " 个" else " · 单词复习已关")
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "库里共 " + all.size + " 张知识卡 · 新卡 " + all.count { it.fsrs.isNew } + " 张"
            textSize = 12.5f
            // tag = KEEP：这行是次级灰字，别被 Brut.style 的 TextView 分支一刀刷成墨色
            setTextColor(Ui.TX3); tag = Brut.KEEP
            setPadding(0, dp(2), 0, dp(4))
        })
    }

    private fun dp(n: Int) = Ui.dp(this, n.toFloat())
    private fun label(text: String, size: Float = 14f) = root.addView(TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8)) })

    /**
     * 一个入口按钮。v2.5.1：加了 [primary] 这一档 —— 修「整页零强调色」。
     *
     * ## 为什么原来一个主色都没有
     * 本页的按钮是裸的 `android.widget.Button`，从来没人给它们打 tag；而 [Brut.atelierStyle] 的
     * Button 分支是 `when (v.tag) { PRIMARY -> MINT; DANGER -> coral; else -> surfaceAlt }`，
     * 于是**九个按钮全部落到 surfaceAlt 灰底**，整页没有一处主色。截图上看着就像一张“禁用页”。
     *
     * ## 为什么只有这几个是主色
     * 60/25/10/5 里薄荷绿只占 25%，而「开始学习 / 开始复习」是 90% 的使用路径（见类注释的顺序说明）。
     * 两个主按钮 ≈ 整页 8% 面积，落在 10%~25% 那档的下沿；再多给一个就会把这一页刷成绿色底纹，
     * 与 `Brut.heading` 的注释（「大纲标题不用色块底，防止吃掉 25% 配额」）自相矛盾。
     */
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) =
        root.addView(android.widget.Button(this).apply {
            this.text = text
            if (primary) tag = Brut.PRIMARY
            setOnClickListener { action() }
        })

    /**
     * 次行那种短标题按钮：只建视图、不挂到 root（调用方按两列摆放）。
     *
     * 高度交给 [Brut.button] 的 minimumHeight（粗野 50dp / Atelier 48dp），这里不写死高度 ——
     * 写死 48dp 在大字号下会把第二行字裁掉。长说明放 `contentDescription`。
     */
    private fun sideButton(text: String, desc: String, action: () -> Unit) =
        android.widget.Button(this).apply {
            this.text = text
            contentDescription = desc
            // 不锁单行：窄屏 + 大字号时宁可折成两行，也不把字截掉
            minWidth = 0   // 系统 Button 自带 88dp 最小宽，两列会被挤出屏幕
            setOnClickListener { action() }
        }

    /**
     * 「其他功能」：一行可点的标题 + 可展开的入口列表（默认收起，见 [restOpen]）。
     *
     * 开合走 [AnimGlue.FoldMotion]（设置页那套），连点就是打断上一段，不会把刚展开的块又收掉。
     * 每个入口的副标题不再拼在按钮文字里 —— 一整行「知识卡库 · 新建 / 编辑 / 检索」读起来全是噪点；
     * 现在按钮只写名字，副标题进 `contentDescription`，读屏照样能听到。
     * 收起时标题行右边写着有几个入口，用户看得出来里面没丢东西。
     */
    private fun otherBlock(rest: List<Feature>) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in rest) {
            body.addView(android.widget.Button(this).apply {
                text = f.title
                contentDescription = if (f.subtitle.isBlank()) f.title else f.title + "，" + f.subtitle
                minWidth = 0
                setOnClickListener {
                    if (!Features.open(this@StudyActivity, f.id)) toast("「" + f.title + "」打不开 —— 可能在「模块管理」里被关掉了")
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val state = TextView(this).apply {
            textSize = 13f; setTextColor(Ui.TX3); tag = Brut.KEEP
            gravity = android.view.Gravity.END
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true; isFocusable = true
            // 按压反馈用系统 ripple（和设置页的折叠头同一套），不自己画状态机
            background = WxSkin.ripple(this@StudyActivity, null, 12f)
            addView(Ui.text(this@StudyActivity, "其他功能", 16f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(state, LinearLayout.LayoutParams(-2, -2))
        }
        val fold = AnimGlue.FoldMotion(body)
        fun setOpen(open: Boolean, animate: Boolean = true) {
            restOpen = open
            fold.setOpen(open, animate)
            state.text = if (open) "收起 ▾" else rest.size.toString() + " 个入口 ›"
            head.contentDescription = "其他功能：" + rest.joinToString("、") { it.title } +
                (if (open) "，已展开，点按收起" else "，已收起，点按展开")
        }
        head.setOnClickListener { setOpen(!fold.isOpen) }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        setOpen(restOpen, animate = false)   // 建树落终态：本页每次 onResume 重建，别播动画
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}