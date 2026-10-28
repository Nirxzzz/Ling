package com.chun.lingzhu

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 第 14 条「学习者数字孪生 + Agent 自我反思」的页面。
 *
 * ## 这个页面最重要的一条规矩
 * 不知道的维度显示「数据不足」，**绝不显示 0**。
 * 一个刚装 App 的人如果看到「掌握度 0% / 保持率 0% / 专注 0 分钟」，
 * 读起来就是「你已经学废了」——而他其实只是还没开始。
 * 所以 [LearnerTwin.Dim] 的 v 为 null 时，这里只写空缺原因，不画空条。
 *
 * ## 为什么分成「数字」和「反思」两段
 * 数字是本地算的，任何时候都可信、可核对；反思是模型说的，可能失败、可能降级。
 * 混在一起会让用户分不清哪句话有账可查。分开之后，下段开始处固定标出来源：
 * 云端成功 → 标「模型基于上面这些数说」；降级 → 标「没连上模型」并写明原因。
 */
class TwinActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val logStore by lazy { ReviewLog.Store(this) }
    private val cardStore by lazy { KnowledgeCard.Store(this) }
    private val wrongStore by lazy { WrongLog.Store(this) }
    private val focusStore by lazy { FocusReport.Store(this) }
    private val ledger by lazy { CostLedger.Store(this) }
    private val reflectStore by lazy { Reflection.Store(this) }

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "我的学习画像"
    }

    override fun onResume() {
        super.onResume()
        Ui.applyTheme(prefs)
        draw()
    }

    private fun draw() {
        val now = System.currentTimeMillis()
        val twin = buildTwin(now)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(28f))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Brut.page(this, scroll)

        label("我的学习画像", 24f)
        label("这些数全部从这台手机上的记录算出来，每个都写着它是怎么来的。" +
            "没数据的地方我写「数据不足」，不会填个 0 糊弄你。", 13f)

        if (!twin.hasAny) {
            emptyBlock(twin)
            return
        }

        heading("一、五个维度")
        for (f in twin.dims) dimBlock(f)

        heading("二、薄弱知识点")
        weakBlock(twin)

        heading("三、习惯")
        habitBlock(twin)

        heading("四、Agent 反思")
        reflectBlock(twin, now)
    }

    // ---------------- 数据 ----------------

    private fun buildTwin(now: Long): LearnerTwin.Twin {
        val today = ReviewLog.dateOf(now)
        val input = LearnerTwin.Inputs(
            wrong = wrongStore.list(),
            reviews = logStore.all(),
            samples = focusStore.all(),
            cards = cardStore.list().map { it.fsrs },
            ledger = ledger.snapshot(),
            ym = today.substring(0, 6),
        )
        return LearnerTwin.build(input, now)
    }

    // ---------------- 一、维度 ----------------

    /**
     * 一个维度一行。[LearnerTwin.Dim.v] 为 null 时只写原因，不写数字。
     *
     * 这里的 [format] 决定「怎么把数说成人话」，每个维度量纲不同，必须分开写：
     * 掌握度是百分比、节奏是「张/天」、专注是时长、成本是元。用同一个格式串会全错。
     */
    private fun dimBlock(f: LearnerTwin.Feat) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        Brut.card(box)

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = f.name
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        })
        head.addView(space())
        head.addView(TextView(this).apply {
            text = if (f.dim.unknown) LearnerTwin.Conf.UNKNOWN.label else valueText(f)
            setTextColor(if (f.dim.unknown) Brut.MUTED else Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (f.dim.unknown) 14f else 18f)
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(head)

        // 趋势只在「确实能比」的时候显示。NA 不画箭头，因为「看不出」画成「→」会被当成「没变」
        if (f.dir != LearnerTwin.Dir.NA && !f.dim.unknown) {
            box.addView(TextView(this).apply {
                text = f.dir.arrow + " " + f.dir.cn + if (f.trendSrc.isNotEmpty()) "（" + f.trendSrc + "）" else ""
                setTextColor(Brut.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }
        box.addView(TextView(this).apply {
            text = f.dim.src
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4f), 0, 0)
        })
        root.addView(box)
        root.addView(gap())
    }

    private fun valueText(f: LearnerTwin.Feat): String = when (f.name) {
        LearnerTwin.MASTERY -> pct(f.dim.or(0.0))
        LearnerTwin.PACE -> one(f.dim.or(0.0)) + " 张/天"
        LearnerTwin.RETENTION -> pct(f.dim.or(0.0))
        LearnerTwin.FOCUS -> FocusGuard.fmtDur(f.dim.or(0.0).toLong())
        LearnerTwin.COST -> CostLedger.fmtYuan(f.dim.or(0.0).toLong())
        else -> one(f.dim.or(0.0))
    }

    // ---------------- 二、薄弱 ----------------

    /**
     * 薄弱知识点。
     *
     * 每一项都写成「函数  33%（3 题错 2 题）」而不是只给「函数 33%」：
     * 用户看到百分比的第一反应是「凭什么」，只有把原始次数摆出来，
     * 这个数才从「系统说的」变成「我自己能算的」。这是画像能不能被信任的关键。
     */
    private fun weakBlock(t: LearnerTwin.Twin) {
        if (t.weak.isEmpty()) {
            hint("还没有带知识点的答题记录，算不出哪块最弱。" +
                "做几道会被判错的题，或者用识别把错题存进台账，这里就有了。")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        Brut.card(box)
        box.addView(TextView(this).apply {
            text = "最该补的 " + t.weak.size + " 个（掌握度低的在前）"
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        for (n in t.weak) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6f), 0, 0)
            }
            line.addView(TextView(this).apply {
                text = "· " + n.name
                setTextColor(Brut.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            line.addView(space())
            line.addView(TextView(this).apply {
                text = pct(n.mastery)
                setTextColor(Brut.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(line)
            // 原始次数单独一行、用次要色：主信息是掌握度，但核对所需的数必须在
            box.addView(TextView(this).apply {
                text = "   共 " + n.total + " 题，错 " + n.wrong + " 题，对 " + n.correct + " 题"
                setTextColor(Brut.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
        }
        box.addView(TextView(this).apply {
            text = "掌握度 = 答对次数 ÷ 总次数，你可以拿上面的次数自己算一遍。" +
                "看的是对错，不是刷了几遍。"
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8f), 0, 0)
        })
        root.addView(box)
        root.addView(gap())
    }

    // ---------------- 三、习惯 ----------------

    private fun habitBlock(t: LearnerTwin.Twin) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        Brut.card(box)
        box.addView(row("连续天数", if (t.streak.unknown) LearnerTwin.Conf.UNKNOWN.label
            else t.streak.v!!.toInt().toString() + " 天"))
        box.addView(row("常学时段", if (t.bestHour < 0) LearnerTwin.Conf.UNKNOWN.label
            else t.bestHour.toString() + " 点前后"))
        box.addView(row("最易分心", if (t.worstHour.unknown) LearnerTwin.Conf.UNKNOWN.label
            else t.worstHour.v!!.toInt().toString() + " 点前后"))
        box.addView(row("每小时离开", if (t.leavesPerHour.unknown) LearnerTwin.Conf.UNKNOWN.label
            else one(t.leavesPerHour.v!!) + " 次"))
        box.addView(TextView(this).apply {
            text = "「最易分心」要专注守护抓到过「不像学习」的画面才算得出；" +
                "一次都没抓到不等于没分心，所以这里写「数据不足」而不是 0。"
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8f), 0, 0)
        })
        root.addView(box)
        root.addView(gap())
    }

    // ---------------- 数据缺口 ----------------

    private fun emptyBlock(t: LearnerTwin.Twin) {
        heading("还没有可算的数据")
        hint("不是我算不出来，是这台手机上还没有足够的记录。" +
            "下面每一条都写清了缺什么、去哪里补：")
        for (g in t.gaps) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
            Brut.card(box)
            box.addView(TextView(this).apply {
                text = g.dim
                setTextColor(Brut.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
            })
            box.addView(TextView(this).apply {
                text = "为什么空：" + g.why
                setTextColor(Brut.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4f), 0, 0)
            })
            box.addView(TextView(this).apply {
                text = "怎么补：" + g.need
                setTextColor(Brut.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4f), 0, 0)
            })
            root.addView(box)
            root.addView(gap())
        }
    }

    // ---------------- 四、反思 ----------------

    /**
     * 反思块。数字永远先显示（本地算的），反思是按需生成的。
     *
     * 不给自动生成：反思要调模型要花钱，用户没点就不该发请求。
     * 历史结论从 [Reflection.Store] 读，所以重新进页面也不会重复花钱。
     */
    private fun reflectBlock(t: LearnerTwin.Twin, now: Long) {
        val history = reflectStore.load()
        if (history.isNotEmpty()) {
            val last = history.first()
            showAdvice(last)
        } else {
            hint("点下面的按钮，让 Agent 看一遍上面这些数，指出矛盾并给三条能立刻做的建议。")
        }

        val go = TextView(this).apply {
            text = if (history.isEmpty()) "生成反思" else "再生成一次"
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            gravity = android.view.Gravity.CENTER
        }
        Brut.card(go)
        go.setOnClickListener { generate(t, now) }
        root.addView(go)

        root.addView(TextView(this).apply {
            text = "反思会把这几个统计数字发给模型（不含题目原文、不含卡片正面背面）。" +
                "没连上就在本机按调度队列给结论，并明确标出来源。"
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(8f), 0, 0)
        })
    }

    private fun showAdvice(a: Reflection.Advice) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        Brut.card(box)

        box.addView(TextView(this).apply {
            text = if (a.source == Reflection.Source.CLOUD) "模型反思" else "本地结论"
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        // 降级原因必须显式写出来：用户有权知道这段是模型说的还是本机凑的
        if (a.degradedReason != null) {
            box.addView(TextView(this).apply {
                text = "没连上模型：" + a.degradedReason
                setTextColor(Brut.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4f), 0, 0)
            })
        }
        box.addView(TextView(this).apply {
            text = a.summary
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8f), 0, 0)
        })
        if (a.actions.isNotEmpty()) {
            box.addView(section("接下来做这几件事"))
            for ((i, s) in a.actions.withIndex()) {
                box.addView(TextView(this).apply {
                    text = (i + 1).toString() + ". " + s
                    setTextColor(Brut.INK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(4f), 0, 0)
                })
            }
        }
        if (a.conflicts.isNotEmpty()) {
            box.addView(section("看到的矛盾"))
            for (s in a.conflicts) {
                box.addView(TextView(this).apply {
                    text = "· " + s
                    setTextColor(Brut.INK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(4f), 0, 0)
                })
            }
        }
        if (a.insufficient.isNotEmpty()) {
            box.addView(section("数据不足、没有下结论的地方"))
            for (s in a.insufficient) {
                box.addView(TextView(this).apply {
                    text = "· " + s
                    setTextColor(Brut.MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, dp(4f), 0, 0)
                })
            }
        }
        root.addView(box)
        root.addView(gap())
    }

    private fun generate(t: LearnerTwin.Twin, now: Long) {
        // words 必须真读，不能传 emptyMap()：
        // StudyCoach 用它在 summary 里算「今天该复习几张」，传空表就等于把单词学习者
        // 的整个学习量从建议里抹掉 —— 画像页会给出一个比实际轻飘得多的结论。
        val coach = StudyCoach.suggest(
            cardStore.list(), WordSrs.all(prefs), prefs.studyPlan, now)
        // 没配接口时传 null，让 Reflection 走「未配置模型接口」的降级分支 ——
        // 不能传一个 baseUrl 为空的 ApiConfig，那样会真的发一次注定失败的请求
        val advice = Reflection.run(
            cfg = if (prefs.hasApi()) prefs.api() else null,
            twin = t,
            coach = coach,
            now = now,
            ledger = ledger,
        )
        reflectStore.append(advice)
        draw()
    }

    // ---------------- 排版小工具 ----------------

    private fun label(s: String, sz: Float) {
        root.addView(TextView(this).apply {
            text = s
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sz)
            setPadding(0, 0, 0, dp(8f))
        })
    }

    private fun heading(s: String) {
        root.addView(TextView(this).apply {
            text = s
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14f), 0, dp(8f))
        })
    }

    private fun hint(s: String) {
        root.addView(TextView(this).apply {
            text = s
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4f), 0, dp(10f))
        })
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        setTextColor(Brut.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12f), 0, dp(2f))
    }

    private fun row(k: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5f), 0, dp(5f))
        addView(TextView(this@TwinActivity).apply {
            text = k
            setTextColor(Brut.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        addView(space())
        addView(TextView(this@TwinActivity).apply {
            text = v
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
    }

    private fun space() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun gap() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(10f))
    }

    private fun dp(v: Float): Int = Ui.dp(this, v)

    private fun pct(v: Double): String =
        String.format(java.util.Locale.US, "%.0f%%", v * 100.0)

    private fun one(v: Double): String =
        String.format(java.util.Locale.US, "%.1f", v)
}
