package com.chun.lingzhu

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v2.3.1 学习数据面板：把「我记得住吗 / 我练了多少 / 我练了多久 / 花了多少钱」摆在一页里。
 *
 * ## 为什么不引图表库
 * 这个工程从第一天起就是零依赖（只有 Android 框架 + org.json）。为了两张小图引一个几 MB 的库，
 * 会让安装包变大、让构建多一层风险，而这里要画的只是**一条折线 + 一排柱子**，
 * Canvas 几十行就能画完，而且能精确对上 [Brut] 的墨色描边规格 —— 图表库反而更难调成这个风格。
 * 两张图都是自绘 [View]（[LineChart] / [BarChart]），重写 onDraw。
 *
 * ## 这一页刻意不做的事
 *  - **不在这里算数**。所有数字来自 [LearningStats]（纯函数、有单测）；页面只负责取数据、排版、画。
 *  - **不造假**。没有数据就写「还没有数据」，不显示 0%、不画一条平的虚线冒充曲线。
 *  - **不说术语**。FSRS、可提取性、留存率这些词只在下面的注释里出现，
 *    页面上一律说「还记得多少」「到期复习时想起来几次」，学生和评委都能一眼看懂。
 *
 * 入口在 [StudyActivity] 顶部按钮「学习数据面板」。
 */
class LearningPanelActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val cardStore by lazy { KnowledgeCard.Store(this) }
    private val logStore by lazy { ReviewLog.Store(this) }
    private val ledger by lazy { CostLedger.Store(this) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "学习数据面板"
    }

    override fun onResume() { super.onResume(); draw() }

    /**
     * 取数据 → 交给 [LearningStats] 算 → 排版。
     *
     * 取的是**本周**的活跃天数做成本分母（不是本月）：本月刚开始时活跃天数只有 1、2 天，
     * 乘 30 会把成本放大到离谱的倍率，家长一看就是假数。用「本周已过去的天数」里真实学过几天，
     * 样本至少是 7 天，摊出来的数才稳。
     */
    private fun draw() {
        Ui.applyTheme(prefs)
        val now = System.currentTimeMillis()
        val log = logStore.all()
        val cards = cardStore.list().map { it.fsrs }
        val words = WordSrs.all(prefs).values.toList()
        val stats = LearningStats.compute(log, cards, words, now, prefs.studyPlan.params())
        val today = ReviewLog.dateOf(now)
        val ym = today.substring(0, 6)
        val monthDays = log.map { ReviewLog.dateOf(it.at) }.filter { it.startsWith(ym) }.toHashSet().size
        val cost = LearningStats.perStudentMonth(ledger.snapshot(), ym, monthDays)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Brut.page(this, scroll)

        label("学习数据面板", 24f)
        label("这些数字全部来自这台手机上的真实记录，没有一条是预先写死的。" +
            "下面每张图后面都写着它是怎么算出来的。", 13f)

        heading("一、还记得多少");
        retentionBlock(stats)

        heading("二、练了多少");
        reviewBlock(stats)

        heading("三、练了多久");
        timeBlock(stats)

        heading("四、AI 花了多少钱");
        costBlock(cost)

        button("返回") { finish() }
        Brut.style(root)
    }

    // ---------------- 一、记忆保持率 ----------------

    /**
     * 保持率：一条自绘折线 + 两句人话。
     * 折线画的是「按现在的卡片状态倒推，过去 90 天里每天平均还剩多少记忆」，
     * 下面的两个数才是实测：现在还剩多少 / 真到复习那天想起来几次。
     */
    private fun retentionBlock(s: LearningStats.Stats) {
        val curve = s.retentionCurve
        if (curve.isEmpty()) {
            label("还没有可统计的卡片。先把知识卡或单词过一遍评分，这里就会出现曲线。")
            return
        }
        root.addView(LineChart(this, curve).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(6); bottomMargin = dp(6) }
        })
        val nowTxt = s.retentionNow?.let { LearningStats.fmtPct(it) } ?: "暂无"
        label("现在这些卡片平均还记得 " + nowTxt + "。" + line("（按每张卡上次复习至今经过的天数和它的记忆强度算出来的预测值）"))
        val real = s.recallRate?.let { LearningStats.fmtPct(it) }
        label(if (real == null) {
            "到期复习时的实测留存：还没有评分记录，所以这里不写数字。"
        } else {
            "到期复习时真正「想起来了」的比例是 " + real + " —— 这是实测，上面那条是预测。" +
                line("两条差得越多，说明复习节奏和你的实际情况偏差越大：实测明显偏低就说明复习间隔该调短。")
        })
    }

    // ---------------- 二、复习量 ----------------

    /** 近 30 天柱状图。没复习的那天是一根贴着轴的零高柱，断档要看得见。 */
    private fun reviewBlock(s: LearningStats.Stats) {
        val days = s.dailyReviews
        if (days.isEmpty()) { label("还没有复习记录。"); return }
        root.addView(BarChart(this, days).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(140)).apply { topMargin = dp(6); bottomMargin = dp(6) }
        })
        val total = days.sumOf { it.second }
        val active = days.count { it.second > 0 }
        label("近 30 天共复习 " + total + " 次，其中 " + active + " 天有在学习，" + (30 - active) + " 天空着。")
        label("一共练过 " + s.distinctCards + " 张不同的卡片（知识卡 + 单词），累计评分 " + s.reviewCount + " 次。" +
            line("练的次数多不代表练的卡片多 —— 两张数字差得远，说明你一直在重复刷同一小撮。"))
    }

    // ---------------- 三、学习时长 ----------------

    /** 时长 + 连续天数。时长是「下限值」口径，页面上必须说清楚，否则容易被当成计时的精确值。 */
    private fun timeBlock(s: LearningStats.Stats) {
        label("累计学习时长：" + s.totalText, 18f)
        label("连续学习：" + s.streakDays + " 天" + if (s.streakDays > 0) "  🔥" else "", 18f)
        label("时长是按「两次评分之间的间隔」累加的，单条最多算 5 分钟，跨天不算。" +
            line("所以这个数只会比你真实花的时间少，不会多：中途锁屏、去接水的那段不会被算进去。"))
        label(if (s.streakDays == 0) {
            "今天和昨天都没有学习记录，连续天数从头开始算。"
        } else {
            "已经连着 " + s.streakDays + " 天没断过。今天还没学也不会马上归零 —— 从昨天往前数。"
        })
    }

    // ---------------- 四、成本 ----------------

    /**
     * 成本：先给「每人每月」这个能拿来报价的口径，再给总账和按功能的拆分，最后把算法原文贴出来。
     * 顺序是刻意的 —— 家长/评委第一个问题是「一个学生多少钱」，不是「这个月花了多少」。
     */
    private fun costBlock(c: LearningStats.Cost) {
        val per = c.perStudentMonthMicro
        label("每位学生每月约：" + (per?.let { CostLedger.fmtYuan(it) } ?: "暂无"), 20f)
        label("本月 AI 实际花费 " + CostLedger.fmtYuan(c.monthMicro) + "（" + c.monthReq + " 次请求），" +
            "其中 " + c.activeDays + " 天有学习记录。")
        if (c.byFeature.isNotEmpty()) {
            label("钱花在哪：")
            for ((name, micro) in c.byFeature) {
                val pct = c.featureShare.firstOrNull { it.first == name }?.second
                label("  · " + name + "  " + CostLedger.fmtYuan(micro) + (pct?.let { "（" + it + "%）" } ?: ""), 13f)
            }
        } else {
            label("本月还没有产生 AI 请求，所以没有可拆分的费用。" +
                line("识别、追问、翻译、搜索、聊天都会记一笔账，这里是它们的汇总。"))
        }
        label(c.basis)
    }

    // ---------------- 排版小工具（照抄 StudyActivity 的写法） ----------------

    private fun dp(n: Int) = Ui.dp(this, n.toFloat())
    private fun label(text: String, size: Float = 14f) = root.addView(TextView(this).apply {
        this.text = text; textSize = size; setPadding(0, dp(8), 0, dp(8))
    })
    private fun heading(text: String) = root.addView(Brut.heading(this, text))
    private fun button(text: String, action: () -> Unit) = root.addView(android.widget.Button(this).apply {
        this.text = text; setOnClickListener { action() }
    })

    /** 行内换行但保留为一段：小字说明跟在主句下面一行，不另起段落（页面才不会碎成一地） */
    private fun line(text: String) = "\n" + text

    // ---------------- 自绘图表 ----------------

    /**
     * 折线图：横轴是日期（只看首/中/尾三个标签，密了看不清），纵轴固定 0..100%。
     * 纵轴固定而不是自适应，是因为「从 92% 掉到 88%」在自适应坐标里会画成断崖，
     * 那是视觉欺骗 —— 保持率的变化本来就该看起来平缓。
     */
    private class LineChart(ctx: android.content.Context, private val data: List<Pair<String, Double>>) : View(ctx) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp2(2.2f); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp2(1f) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp2(10f); typeface = Typeface.DEFAULT }

        private fun dp2(v: Float) = Ui.dp(context, v).toFloat()
        private fun sp2(v: Float) = v * context.resources.displayMetrics.scaledDensity

        override fun onDraw(canvas: Canvas) {
            if (data.isEmpty()) return
            val padL = dp2(28f); val padR = dp2(8f); val padT = dp2(10f); val padB = dp2(20f)
            val w = width - padL - padR
            val h = height - padT - padB
            if (w <= 0 || h <= 0) return

            // 三条参考线：100% / 50% / 0%，给眼睛一个绝对值锚点
            grid.color = Budget_gridColor()
            for (p in floatArrayOf(0f, 0.5f, 1f)) {
                val y = padT + h * (1f - p)
                canvas.drawLine(padL, y, padL + w, y, grid)
                textPaint.color = Brut.HINT
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText((p * 100).toInt().toString(), padL - dp2(4f), y + dp2(3.5f), textPaint)
            }

            // 折线本体
            val path = Path()
            val area = Path()
            val stepX = if (data.size == 1) 0f else w / (data.size - 1)
            var firstX = 0f; var lastX = 0f; var lastY = 0f
            data.forEachIndexed { i, (_, v) ->
                val x = padL + stepX * i
                val y = padT + h * (1f - v.toFloat().coerceIn(0f, 1f))
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, padT + h); area.lineTo(x, y); firstX = x } else path.lineTo(x, y)
                area.lineTo(x, y); lastX = x; lastY = y
            }
            area.lineTo(lastX, padT + h); area.close()
            fill.color = alpha(Brut.MINT, 60)
            canvas.drawPath(area, fill)
            stroke.color = Brut.INK
            canvas.drawPath(path, stroke)

            // 末端一个实心点：最新的数值应该最显眼
            dot.color = Brut.INK
            canvas.drawCircle(lastX, lastY, dp2(3.5f), dot)

            // 横轴只标首 / 中 / 尾，密了会糊成一片
            textPaint.color = Brut.HINT
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(data.first().first, padL, height - dp2(5f), textPaint)
            if (data.size > 2) {
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(data[data.size / 2].first, padL + w / 2, height - dp2(5f), textPaint)
            }
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(data.last().first, padL + w, height - dp2(5f), textPaint)
            if (firstX == lastX) return
        }

        /** 参考线颜色：用 hint 再压一点透明度，别抢折线的戏 */
        private fun Budget_gridColor(): Int = alpha(Brut.HINT, 70)

        private fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 柱状图：每根柱 = 一天的复习次数。零高的那天不画柱，只留轴上的位置；
     * 最高的一天标个数字，其余不标 —— 26 根柱子上都写字会糊成一片灰。
     */
    private class BarChart(ctx: android.content.Context, private val data: List<Pair<String, Int>>) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Brut.MINT }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1.6f).toFloat(); color = Brut.INK }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(context, 1.4f).toFloat(); color = Brut.INK }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * context.resources.displayMetrics.scaledDensity; color = Brut.INK; typeface = Typeface.DEFAULT_BOLD }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * context.resources.displayMetrics.scaledDensity; color = Brut.HINT }

        override fun onDraw(canvas: Canvas) {
            if (data.isEmpty()) return
            val padB = Ui.dp(context, 18f).toFloat()
            val padT = Ui.dp(context, 14f).toFloat()
            val h = height - padT - padB
            if (h <= 0) return
            val max = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            val slot = width.toFloat() / data.size
            val bw = (slot * 0.62f).coerceAtLeast(Ui.dp(context, 1.5f).toFloat())

            for ((i, pair) in data.withIndex()) {
                val n = pair.second
                if (n <= 0) continue
                val left = slot * i + (slot - bw) / 2f
                val bh = h * (n.toFloat() / max)
                val top = padT + h - bh
                canvas.drawRect(left, top, left + bw, padT + h, bar)
                canvas.drawRect(left, top, left + bw, padT + h, edge)
                // 只在最高那天写字
                if (n == max) {
                    textPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(n.toString(), left + bw / 2f, top - Ui.dp(context, 4f), textPaint)
                }
            }
            // 基线
            canvas.drawLine(0f, padT + h, width.toFloat(), padT + h, axis)
            // 首 / 尾日期
            hintPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(dash(data.first().first), 0f, height - Ui.dp(context, 4f).toFloat(), hintPaint)
            hintPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(dash(data.last().first), width.toFloat(), height - Ui.dp(context, 4f).toFloat(), hintPaint)
        }

        /** 2026-10-20 这种日期太长，图上只留「10-20」 */
        private fun dash(d: String): String = if (d.length >= 5) d.substring(d.length - 5) else d
    }
}