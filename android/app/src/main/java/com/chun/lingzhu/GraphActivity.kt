package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v2.3.1 第 10 条「知识图谱」的可视化页：把 [KnowledgeGraph] 算出来的点和边画成一张能拖、能缩的图。
 *
 * ## 这一页只做三件事
 *  1. **画**：自绘 [GraphView]（节点圆 + 标签 + 带箭头的边），零第三方库 —— 和 [LearningPanelActivity]
 *     里那两张自绘图表同一个理由：整个工程只有 Android 框架和 org.json，为了一张小图引库得不偿失。
 *  2. **解释**：点节点弹面板，列出「它和谁连、为什么连」。边的理由直接来自 [KnowledgeGraph.Edge.reason]，
 *     每个字都能对上算法（同来源 · 某文件 / 共享关键词 · 某词 / 同牌组 · 某牌组），不是编出来的话术。
 *  3. **跳转**：面板里点某张卡 → 进 [ReviewActivity] 复习（知识卡和单词在同一个复习页里排队）。
 *
 * ## 刻意不做的事
 *  - **不做自动旋转的炫技动画**。图是给人看结构的，一动就看不清；拖拽和缩放交给人自己控制。
 *  - **不在这一页建图以外算任何东西**：节点权重、连通分量、布局全在纯函数里（[KnowledgeGraph]），
 *    这一页只负责取数据、排版、画。这样布局和边的规则能被 [KnowledgeGraphTest] 直接断言。
 *  - **不假装画全**。节点数超过 [KnowledgeGraph.MAX_NODES] 就只画权重最高的一批，页面顶部写明
 *    「已显示 N / M 个知识点」。瞒着用户截断，比截断本身更糟。
 *
 * 入口在 [PreviewActivity] … 见 [StudyActivity] 的「知识图谱」按钮（另一个同学接的线）。
 */
class GraphActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val cardStore by lazy { KnowledgeCard.Store(this) }

    /** 当前图（[onResume] 重建：从复习页回来可能刚删过卡） */
    private var graph: KnowledgeGraph.Graph = KnowledgeGraph.Graph(emptyList(), emptyList())
    private var cards: List<KnowledgeCard.KCData> = emptyList()

    private lateinit var root: LinearLayout
    private lateinit var canvasHost: LinearLayout
    private lateinit var infoBox: LinearLayout
    private var view: GraphView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "知识图谱"
    }

    override fun onResume() { super.onResume(); rebuild() }

    /**
     * 建图 → 排版 → 画。每次回到页面都重来一遍，因为卡片可能刚被删/改过；
     * onPause 时取消掉可能还在跑的布局任务（旋转屏幕时旧任务不该往已销毁的 View 上写）。
     */
    private fun rebuild() {
        Ui.applyTheme(prefs)
        cards = cardStore.list()
        graph = KnowledgeGraph.build(cards)
        val stats = KnowledgeGraph.stats(graph, cards.size)
        val clusters = KnowledgeGraph.clusters(graph)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Brut.page(this, scroll)

        label("知识图谱", 24f)
        label(
            "每个圆点是一个知识点（同一件事的多张卡合并成一个点）。连线只画**有证据**的关系：" +
                "同一份资料、共享关键词、同一牌组加分。低于阈值的边不画 —— " +
                "否则一份资料里几百张卡会连成一坨，什么也看不出来。",
            13f,
        )

        // 图本身：固定高度的一块画布，外面套 ScrollView 方便下面还有文字
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(GRAPH_HEIGHT_DP))
        }
        canvasHost = host
        root.addView(host)

        val gv = GraphView(this, graph).also { view = it }
        gv.onPick = { node -> showNode(node) }
        gv.layoutParams = LinearLayout.LayoutParams(-1, -1)
        host.addView(gv)

        if (graph.nodes.isEmpty()) {
            label("还没有可以连起来的知识点。" +
                "先在「导入文档」里生成知识卡，或者手动新建几张 —— 只有一张卡时图上也只有一个点。")
        } else {
            label(
                "已显示 " + stats.nodes + " 个知识点、" + stats.edges + " 条连线，" +
                    "全库共 " + cards.size + " 张卡（合并了 " + stats.merged + " 张重复主题的卡）。" +
                    "平均每个点连 " + fmt1(stats.avgDegree) + " 条线" +
                    if (stats.avgDegree > 3.0) " —— 这个数偏大，说明资料之间关联过多，图会比较挤。" else "。",
            )
        }

        heading("这份资料分成了几个主题")
        if (clusters.isEmpty()) {
            label("还没有数据。")
        } else {
            label("按连线切出来的连通块，共 " + clusters.size + " 块。块内任意两点都能沿连线走到，" +
                "块与块之间没有证据相连 —— 这就是「资料其实是几个互相独立的部分」的样子。")
            for ((i, c) in clusters.take(MAX_CLUSTER_LINES).withIndex()) {
                val sample = c.nodes.take(3).mapNotNull { graph.nodeById[it]?.label?.let { l -> KnowledgeGraph.clip(l, 12) } }
                label("  " + (i + 1) + ". " + c.size + " 个知识点（连线强度 " + fmt1(c.weight) + "）： " +
                    sample.joinToString("、") + if (c.size > sample.size) " …" else "", 13f)
            }
            val big = clusters.firstOrNull { it.size >= KnowledgeGraph.BIG_CLUSTER }
            if (big != null) {
                label("最大的一块有 " + big.size + " 个知识点。" +
                    "如果这比你预期的多，多半是因为它们出自同一份资料、又共享了不少词 —— " +
                    "这说明资料本身确实是围绕一个话题写的，不是算法连错了。", 13f)
            }
        }

        heading("这条边为什么连")
        val byFile = stats.byFile; val byKey = stats.byKey; val byDeck = stats.byDeck
        val top = graph.edges.sortedByDescending { it.weight }.take(6)
        if (top.isEmpty()) {
            label("没有达到阈值的连线。同一份资料里毫无交集的卡之间就是不该连线 —— 这是对的，不是坏了。")
        } else {
            for (e in top) {
                val a = graph.nodeById[e.a]?.label ?: e.a
                val b = graph.nodeById[e.b]?.label ?: e.b
                label("· " + KnowledgeGraph.clip(a, 10) + " ↔ " + KnowledgeGraph.clip(b, 10) + "\n  " +
                    e.reason + "（合计 " + fmt1(e.weight) + " 分）", 13f)
            }
            label("三条信号各自的权重：同来源 " + fmt0(KnowledgeGraph.W_FILE) + " 分、" +
                "每共享一个关键词 " + fmt0(KnowledgeGraph.W_KEY) + " 分、" +
                "同一牌组额外 " + fmt0(KnowledgeGraph.W_DECK) + " 分；低于 " +
                fmt0(KnowledgeGraph.DEFAULT_THRESHOLD) + " 分不画。这次用掉：" +
                "同来源 " + byFile + " 条、共享关键词 " + byKey + " 条、同牌组加权 " + byDeck + " 条。", 13f)
        }

        label("操作：拖动平移，双指缩放，点圆点看这个知识点名下的卡片。", 13f)
        button("返回") { finish() }
        Brut.style(root)
        gv.requestLayout()
    }

    override fun onPause() {
        view?.cancelPendingLayout()
        super.onPause()
    }

    // ---------------- 点节点：面板 ----------------

    /**
     * 点中节点后的面板。信息顺序按「用户此刻想知道什么」排：
     * 先是什么知识点、共几张卡；再是它和谁相关、为什么；最后才是具体卡片。
     * 关于「为什么」永远放在卡片列表之前 —— 这是一个展示关联关系的页面，卡片只是证据。
     */
    private fun showNode(node: KnowledgeGraph.Node) {
        val d = KnowledgeGraph.detail(graph, cards, node.id) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(box) }
        box.addTextView(KnowledgeGraph.oneLine(node.label).ifEmpty { "（无标题）" }, 18f)
        box.addTextView(
            "牌组：" + node.deck + " · 名下 " + d.cards.size + " 张卡 · 连了 " + d.edges.size + " 条线",
            13f,
        )
        if (d.edges.isNotEmpty()) {
            box.addTextView("和它相关的知识点", 15f, bold = true)
            for (e in d.edges.take(8)) {
                val otherId = if (e.a == node.id) e.b else e.a
                val other = graph.nodeById[otherId]?.label ?: otherId
                box.addTextView("· " + KnowledgeGraph.clip(other, 16) + "\n  " + e.reason, 13f)
            }
        } else {
            box.addTextView(
                "它没有达到阈值的关联 —— 也就是「这个知识点和库里其他东西都没有确凿的共同点」。",
                13f,
            )
        }
        if (d.cards.isNotEmpty()) {
            box.addTextView("这个知识点名下的卡片", 15f, bold = true)
            for (c in d.cards.take(10)) {
                box.addTextView(
                    "· " + KnowledgeGraph.clip(c.front, 40) +
                        (if (c.fsrs.isNew) "（未学）" else ""),
                    13f,
                )
            }
        }
        ThemeDialogs.builder(this)
            .setView(scroll)
            .setPositiveButton("去复习") { _, _ ->
                startActivity(ReviewActivity.intent(this))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun dp(n: Int) = Ui.dp(this, n.toFloat())
    private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)

    private fun LinearLayout.addTextView(text: String, size: Float, bold: Boolean = false) {
        addView(TextView(this@GraphActivity).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun label(text: String, size: Float = 14f) = root.addView(TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8))
    })
    private fun heading(text: String) = root.addView(Brut.heading(this, text))
    private fun button(text: String, action: () -> Unit) = root.addView(android.widget.Button(this).apply {
        this.text = text; setOnClickListener { action() }
    })

    // ---------------- 自绘 ----------------

    /**
     * 图画布。
     *
     * ## 为什么要分「先量宽度再布局」
     * [KnowledgeGraph.layout] 需要知道画布尺寸；而 View 的 onSizeChanged 之前 width == 0。
     * 所以这里在 onSizeChanged 里记下尺寸、在 onDraw 第一次拿到有效尺寸时才**异步**算布局
     * （新开一个线程算，算完 postInvalidate）。120 个点 × 200 次迭代在主线程上是几百毫秒的卡顿，
     * 学生拖图的第一下就会觉得「这 App 卡」。
     *
     * ## 缩放与平移
     * 用 Matrix 的 scale/translate 语义手写（不引 View 的 setScaleX，因为那会连圆点直径一起放大、
     * 线宽也跟着变粗，放大后看起来糊成一团）。这里只对**坐标**做变换，线宽和字体大小按 scale 折半，
     * 保证放大后线不会变成一条粗黑带。
     */
    private inner class GraphView(ctx: Context, private val g: KnowledgeGraph.Graph) : View(ctx) {

        /** 点中节点回调（坐标换算成节点后交给 Activity 弹面板） */
        var onPick: (KnowledgeGraph.Node) -> Unit = {}

        private var pos: Map<String, KnowledgeGraph.Pt> = emptyMap()
        private var layoutFor: Int = 0
        private var pending = false
        private var worker: Thread? = null

        /** 平移（像素）与缩放倍数。scale 限制在 0.5~3：再小看不清标签，再大就只剩几个点 */
        private var scale = 1f
        private var tx = 0f
        private var ty = 0f

        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Brut.HINT
            strokeCap = Paint.Cap.ROUND
        }
        private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Brut.INK }
        private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Brut.INK }
        private val dotAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Brut.CARD }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = Brut.INK
        }

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val next = (scale * d.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                // 以手指中心为锚点缩放：缩放前后该点对应的图上坐标不变，手感才对
                val fx = d.focusX; val fy = d.focusY
                tx = fx - (fx - tx) * (next / scale)
                ty = fy - (fy - ty) * (next / scale)
                scale = next
                invalidate()
                return true
            }
        })

        private val tap = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaler.isInProgress) return false
                tx -= dx; ty -= dy
                invalidate()
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pick(e.x, e.y)
                return true
            }
        })

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            scaler.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                performClick()   // 无障碍：告诉读屏器「这里被点了」
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()

        /** 找到手指下面最近的一个点（阈值 = 圆点半径 + 一点余量，手指没点准也算点中） */
        private fun pick(vx: Float, vy: Float) {
            if (pos.isEmpty()) return
            val lx = (vx - tx) / scale
            val ly = (vy - ty) / scale
            var best: KnowledgeGraph.Node? = null
            var bestD = Float.MAX_VALUE
            val r = dp(TOUCH_DP)
            for (n in g.nodes) {
                val p = pos[n.id] ?: continue
                val d = Math.hypot((p.x - lx).toDouble(), (p.y - ly).toDouble()).toFloat()
                if (d < r && d < bestD) { best = n; bestD = d }
            }
            best?.let { onPick(it) }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // 尺寸变了就要重算（旋转屏幕 / 键盘弹出都会走到这里）；布局线程还没回来的话不重复起
            if (w > 0 && h > 0) relayout(w, h)
        }

        /**
         * 异步算布局。已经在算的那一轮就丢掉（尺寸又变了，算出来的也是旧的），
         * 用 layoutFor 记住「已经算过的尺寸」，避免 onDraw 每帧都触发一轮新计算。
         */
        private fun relayout(w: Int, h: Int) {
            if (layoutFor == w * 100000 + h) return
            layoutFor = w * 100000 + h
            pending = true
            worker?.interrupt()
            val snapshot = g
            worker = Thread {
                val l = KnowledgeGraph.layoutOf(snapshot, w.toFloat(), h.toFloat(), 200, dp(PAD_DP).toFloat())
                if (Thread.currentThread().isInterrupted) return@Thread
                post {
                    if (width == w && height == h) {
                        pos = l.pos
                        pending = false
                        // 新布局把图缩到了画布内，平移回零，否则上一次的偏移会让图跑到屏幕外
                        tx = 0f; ty = 0f
                        invalidate()
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun onDetachedFromWindow() {
            cancelPendingLayout()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width; val h = height
            if (w <= 0 || h <= 0) return
            if (pos.isEmpty()) {
                if (!pending) relayout(w, h)
                // 算的时候给一句提示，别让用户对着空白屏
                labelPaint.textSize = sp(11f)
                labelPaint.color = Brut.HINT
                canvas.drawText("正在排版…", w / 2f, h / 2f, labelPaint)
                labelPaint.color = Brut.INK
                return
            }
            if (g.nodes.isEmpty()) {
                labelPaint.textSize = sp(12f)
                labelPaint.color = Brut.HINT
                canvas.drawText("还没有知识点", w / 2f, h / 2f, labelPaint)
                labelPaint.color = Brut.INK
                return
            }

            canvas.save()
            canvas.translate(tx, ty)
            canvas.scale(scale, scale)

            // 1) 先画边（线压在圆点下面，点才显得在上面）
            val maxEdge = (g.edges.maxOfOrNull { it.weight } ?: 1.0).toFloat().coerceAtLeast(1e-3f)
            for (e in g.edges) {
                val a = pos[e.a] ?: continue
                val b = pos[e.b] ?: continue
                // 线宽按边权归一（1.2~3.2px），并在放大时按 1/√scale 折一下，免得放大后变成粗黑带
                val ratio = e.weight.toFloat() / maxEdge
                edgePaint.strokeWidth = dpf(1.2f + 2.0f * ratio) / kotlin.math.sqrt(scale).toFloat().toFloat()
                edgePaint.color = alpha(Brut.HINT, (90f + 120f * ratio).toInt())
                canvas.drawLine(a.x, a.y, b.x, b.y, edgePaint)
            }

            // 2) 再画点：半径按带权度（中心知识更大），但夹在 4~9dp，太小点不中、太大互相压住
            val maxWeight = (g.nodes.maxOfOrNull { it.weight } ?: 0.0).coerceAtLeast(1e-3)
            for (n in g.nodes) {
                val p = pos[n.id] ?: continue
                val ratio = (n.weight / maxWeight).toFloat()
                val rr = dpf(4.0f + 5.0f * ratio)
                dotFill.color = if (n.degree > 0) Brut.MINT else Brut.MUTED
                dotRing.strokeWidth = dpf(1.6f) / kotlin.math.sqrt(scale).toFloat().toFloat()
                dotRing.color = Brut.INK
                canvas.drawCircle(p.x, p.y, rr, dotFill)
                canvas.drawCircle(p.x, p.y, rr, dotRing)
            }

            // 3) 最后画标签：只在放大到一定程度、或者点有连边时才画全，否则几十个标签叠成一团
            labelPaint.textSize = sp(10f) / kotlin.math.sqrt(scale).toFloat().toFloat()
            for (n in g.nodes) {
                val p = pos[n.id] ?: continue
                val showFull = scale >= 1.6f || n.degree > 0
                val text = if (showFull) KnowledgeGraph.clip(n.label, 8) else ""
                if (text.isEmpty()) continue
                // 标签画在圆点下方一行，基线留出 4dp 呼吸
                canvas.drawText(text, p.x, p.y + dpf(12f) / scale, labelPaint)
            }
            canvas.restore()
        }

        /** 页面销毁 / 尺寸变化时掐掉还在跑的布局线程 */
        fun cancelPendingLayout() {
            worker?.interrupt()
            worker = null
            pending = false
        }

        private fun dpf(v: Float) = Ui.dp(context, v).toFloat()
        private fun sp(v: Float) = v * context.resources.displayMetrics.scaledDensity
        private fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    }

    private companion object {
        /** 画布高度。300dp 在手机上正好一屏内看得到全图 + 下面还有解释文字 */
        const val GRAPH_HEIGHT_DP = 340

        /** 布局边距：圆点半径 + 一行标签的高度 */
        const val PAD_DP = 44

        /** 点中判定半径（比圆点大一圈，手指按不准也算中） */
        const val TOUCH_DP = 18

        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3.0f

        /** 主题列表最多列几块（再多页面就变成一长串编号） */
        const val MAX_CLUSTER_LINES = 8
    }
}
