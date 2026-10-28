package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/**
 * v1.86 记期列表：**日历 + 每周几**，一条提醒可以带内容 / 时间 / 提醒次数 / 提醒强度。
 *
 * 布局（Brut 皮肤，全原生 View）：
 *  记期
 *  ‹ 2026 年 10 月 ›
 *  一 二 三 四 五 六 日        ← 点哪一天，就给那天填一条
 *  [ 1  2  3  4  5  6  7 ]
 *  ...
 *  每周固定：一 二 三 四 五 六 日   ← 多选 + 「加一条每周提醒」
 *  全部记期：<一行一条，可编辑 / 停 / 删>
 *
 * ⚠️ 规则自己**不排闹钟**：存下后一律走 [Schedule.sync] 物化成普通待办（复用已修好的那条提醒链）。
 * 所以每次增删改完都要 sync 一趟；退回本页（onResume）也 sync 一次，把「过点的」收掉。
 */
class ScheduleActivity : Activity() {
    private lateinit var root: LinearLayout
    private val store by lazy { Schedule.Store(this) }
    private var year = 0
    private var month0 = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        val (y, m) = Schedule.Month.ym(System.currentTimeMillis())
        year = y; month0 = m
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(root) }
        // v1.85.13（UX-7/U2）：内容加横向边距 + 上呼吸空间
        root.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@ScheduleActivity, 16f), Ui.dp(this@ScheduleActivity, 4f), Ui.dp(this@ScheduleActivity, 16f), Ui.dp(this@ScheduleActivity, 24f))
        }
        setContentView(sv)
        Brut.page(this, sv)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::root.isInitialized) return
        // 从别处改过规则（聊天页一句话建了一条）也能看到；顺带把过点的生成项收掉
        Schedule.sync(this)
        render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply { text = "记期"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        tip("点日历里的哪一天，就给那天加一条提醒；要「每周都提醒」就选下面的周几。每条能单独设提醒次数和震动强度。")
        renderCalendar()
        renderWeekly()
        renderRules()
        root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
        Brut.style(root)
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    /** 竖向容器里加一行「横向 + 有 weight 子项」的东西时，一律给满宽 ——
     *  不给的话父布局按 wrap_content 量，weight=1 的子项宽度会被算成 0，整行挤成一团。 */
    private fun fullRow(v: android.view.View) = LinearLayout.LayoutParams(-1, -2)

    private fun tip(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 12.5f; alpha = 0.78f
        setPadding(0, dp(6f), 0, 0)
    })

    private fun heading(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20f), 0, dp(6f))
    })

    // ---- 日历 ----

    private fun renderCalendar() {
        val all = store.list()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        nav.addView(Button(this).apply { text = "‹"; setOnClickListener { shiftMonth(-1) } })
        nav.addView(TextView(this).apply {
            text = "$year 年 ${month0 + 1} 月"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(Button(this).apply { text = "›"; setOnClickListener { shiftMonth(1) } })
        grid.addView(nav, fullRow(nav))

        // 周一~周日表头（tag = KEEP：Brut.style 别把它的灰刷成墨）
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (w in Schedule.WEEK) head.addView(cell(w, muted = true), LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(head, fullRow(head))

        val days = Schedule.Month.daysInMonth(year, month0)
        val lead = Schedule.Month.firstIso(year, month0) - 1
        val todayStart = Todo.dayStart(System.currentTimeMillis())
        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var col = 0
        for (i in 0 until lead) { row.addView(cell("", muted = true), LinearLayout.LayoutParams(0, -2, 1f)); col++ }
        for (d in 1..days) {
            val ds = Schedule.Month.dayStart(year, month0, d)
            val n = all.sumOf { Schedule.firesOn(it, ds).size }
            row.addView(dayCell(d, n, ds == todayStart) { openDay(ds) }, LinearLayout.LayoutParams(0, -2, 1f))
            col++
            if (col == 7) { grid.addView(row, fullRow(row)); row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; col = 0 }
        }
        if (col > 0) {
            while (col < 7) { row.addView(cell("", muted = true), LinearLayout.LayoutParams(0, -2, 1f)); col++ }
            grid.addView(row, fullRow(row))
        }
        root.addView(grid, fullRow(grid))
    }

    /** 表头 / 补位的空格子 */
    private fun cell(s: String, muted: Boolean): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(if (muted) Brut.HINT else Brut.INK)
        gravity = android.view.Gravity.CENTER
        setPadding(0, dp(8f), 0, dp(8f))
        tag = Brut.KEEP
    }

    /**
     * 一天一格：上面是号数，有记期就再一行「·N」。
     * 今天描黑框；有记期的日子铺薄荷底（灰度屏上也靠**计数**而不是颜色区分）。
     */
    private fun dayCell(d: Int, count: Int, isToday: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = if (count > 0) "$d\n·$count" else "$d"
        textSize = 13f
        // 有记期的日子铺主色底：字按底色选可读色（默认配色 = 墨色，零变化）
        val fill = if (count > 0) Brut.MINT else Brut.CARD
        setTextColor(Brut.on(fill))
        gravity = android.view.Gravity.CENTER
        setPadding(0, dp(5f), 0, dp(5f))
        // v1.85.12：今天格必须**不透明**（CARD 白底）。原来传 TRANSPARENT，Brut.box 的实心墨色
        // 投影从透明卡里整个透出来 → 「今天」渲染成纯黑块、数字不可见（灰度可读性硬伤，实机截图复现）。
        // 有记期的日子照旧薄荷底；今天/记期靠底色+描边+计数区分，不靠颜色单独表意。
        background = if (isToday || count > 0) Brut.box(this@ScheduleActivity, fill, shadowDp = 2f)
                     else null
        isClickable = true
        setOnClickListener { onClick() }
        tag = Brut.KEEP   // 铺底 / 描边都是这里定的，别让 Brut.style 覆盖
    }

    private fun shiftMonth(delta: Int) {
        val c = Calendar.getInstance().apply { set(year, month0, 1); add(Calendar.MONTH, delta) }
        year = c.get(Calendar.YEAR); month0 = c.get(Calendar.MONTH)
        render()
    }

    private fun openDay(dayStart: Long) {
        val existing = store.list().firstOrNull { !it.weekly && Todo.dayStart(it.dateMs) == dayStart }
        editDialog(existing = existing, forDate = dayStart, seedMask = 0)
    }

    // ---- 每周固定 ----

    private fun renderWeekly() {
        heading("每周固定")
        tip("选好周几 → 点「加一条每周提醒」。一周里可以选好几天（周一、三、五…）。")
        var mask = 0
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun draw() {
            chips.removeAllViews()
            for ((i, w) in Schedule.WEEK.withIndex()) {
                val iso = i + 1
                val on = Schedule.hasDay(mask, iso)
                chips.addView(Button(this).apply {
                    text = if (on) "✓$w" else w
                    if (on) tag = Brut.PRIMARY
                    setOnClickListener { mask = mask xor (1 shl iso); draw() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3f) })
            }
            Brut.style(chips)
        }
        draw()
        root.addView(chips, fullRow(chips))
        root.addView(Button(this).apply {
            text = "加一条每周提醒"; tag = Brut.PRIMARY
            setOnClickListener {
                if (mask == 0) { toast("先在上面选周几"); return@setOnClickListener }
                editDialog(existing = null, forDate = 0L, seedMask = mask)
            }
        })
    }

    // ---- 规则清单 ----

    private fun renderRules() {
        val all = Schedule.sorted(store.list())
        heading("全部记期（${all.size}）")
        if (all.isEmpty() && store.list().isEmpty()) {
            tip("还没有记期。日历里点一天，或上面选好周几加一条。")
            return
        }
        for (r in all) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = r.text + if (r.enabled) "" else "（已停）"
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.3f)
                alpha = if (r.enabled) 1f else 0.5f
            })
            row.addView(TextView(this).apply {
                text = Schedule.describe(r); textSize = 12.5f; alpha = 0.8f
                setPadding(0, dp(2f), 0, dp(4f))
            })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Button(this).apply {
                text = "编辑"
                setOnClickListener { editDialog(existing = r, forDate = if (r.weekly) 0L else Todo.dayStart(r.dateMs), seedMask = r.mask) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4f) })
            acts.addView(Button(this).apply {
                text = if (r.enabled) "停" else "开"
                setOnClickListener { store.update(r.id) { it.copy(enabled = !it.enabled) }; Schedule.sync(this@ScheduleActivity); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4f) })
            acts.addView(Button(this).apply {
                text = "删掉"; tag = Brut.DANGER
                setOnClickListener {
                    ThemeDialogs.builder(this@ScheduleActivity).setTitle("删掉这条记期？")
                        .setMessage(Schedule.describe(r))
                        .setPositiveButton("删掉") { _, _ -> store.remove(r.id); Schedule.sync(this@ScheduleActivity); render() }
                        .setNegativeButton("算了", null).show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(acts, fullRow(acts))
            root.addView(row)
            Brut.card(row)
        }
    }

    // ---- 编辑卡（一次性 / 每周共用） ----

    /**
     * @param existing null = 新建
     * @param forDate  新建一次性时要给的那天 0 点（0 = 不预设，走「每周」）
     * @param seedMask 新建每周时预选的周几（0 = 不预设）
     */
    private fun editDialog(existing: Schedule.Rule?, forDate: Long, seedMask: Int) {
        val weekly0 = existing?.weekly ?: (forDate == 0L)
        var weekly = weekly0
        var mask = existing?.mask ?: seedMask
        var minute = existing?.minute ?: 9 * 60
        var times = existing?.times ?: 1
        var every = existing?.everyMin ?: Schedule.EVERY_MIN_DEF
        var strength = existing?.strength ?: Schedule.VIBE_GLOBAL
        val dateMs = if (existing != null && !existing.weekly) Todo.dayStart(existing.dateMs) else Todo.dayStart(if (forDate != 0L) forDate else System.currentTimeMillis())

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18f), dp(6f), dp(18f), dp(6f)) }
        val title = TextView(this).apply {
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            text = when {
                existing == null && weekly -> "加一条每周提醒"
                existing == null -> "${Schedule.Month.formatDate(dateMs)} 提醒"
                else -> "改这条记期"
            }
        }
        box.addView(title)

        // 内容
        box.addView(TextView(this).apply { text = "提醒什么"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        val edText = EditText(this).apply {
            hint = "比如「背 50 个单词」"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.text.orEmpty())
        }
        box.addView(edText, LinearLayout.LayoutParams(-1, -2))

        // 一次性 / 每周
        box.addView(TextView(this).apply { text = "哪一天"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        // drawDays 在下面才声明（Kotlin 的局部函数不许前向引用）—— 这里先留一个可变引用
        var syncDays: () -> Unit = {}
        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawKind() {
            kindRow.removeAllViews()
            kindRow.addView(Button(this).apply {
                text = if (!weekly) "✓ 就这一天" else "就这一天"
                if (!weekly) tag = Brut.PRIMARY
                setOnClickListener { weekly = false; drawKind(); syncDays() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4f) })
            kindRow.addView(Button(this).apply {
                text = if (weekly) "✓ 每周" else "每周"
                if (weekly) tag = Brut.PRIMARY
                setOnClickListener { weekly = true; drawKind(); syncDays() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            Brut.style(kindRow)
        }
        box.addView(kindRow, LinearLayout.LayoutParams(-1, -2))

        val daysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dateLine = TextView(this).apply { textSize = 12.5f; alpha = 0.8f; setPadding(0, dp(4f), 0, 0) }
        fun drawDays() {
            daysRow.removeAllViews()
            if (weekly) {
                dateLine.visibility = android.view.View.GONE
                for ((i, w) in Schedule.WEEK.withIndex()) {
                    val iso = i + 1
                    val on = Schedule.hasDay(mask, iso)
                    daysRow.addView(Button(this).apply {
                        text = if (on) "✓$w" else w
                        if (on) tag = Brut.PRIMARY
                        setOnClickListener { mask = mask xor (1 shl iso); drawDays() }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3f) })
                }
            } else {
                dateLine.visibility = android.view.View.VISIBLE
                dateLine.text = "日期：${Schedule.Month.formatDate(dateMs)}（想改日期就回日历点那一天）"
            }
            Brut.style(daysRow)
        }
        syncDays = { drawDays() }
        daysBox.addView(daysRow, LinearLayout.LayoutParams(-1, -2)); daysBox.addView(dateLine)
        box.addView(daysBox, LinearLayout.LayoutParams(-1, -2))

        // 时间
        box.addView(TextView(this).apply { text = "几点"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        val timeBtn = Button(this)
        val refreshTime: () -> Unit = { timeBtn.text = "提醒时刻：" + String.format("%02d:%02d", minute / 60, minute % 60) }
        timeBtn.setOnClickListener { pickTime(minute) { minute = it; refreshTime() } }
        refreshTime()
        box.addView(timeBtn, LinearLayout.LayoutParams(-1, -2))

        // 提醒次数
        box.addView(TextView(this).apply { text = "提醒次数（到点后再补几次）"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        // 次数一变，下面「每次隔多久」那一行要跟着显/隐 —— 但那个函数在下面才声明，
        // 这里先用一个可变的引用（MainActivity 的 refreshPopupTimeUi 是同一个套路）
        var syncEvery: () -> Unit = {}
        val timesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun drawTimes() {
            timesRow.removeAllViews()
            for (n in Schedule.TIMES_MIN..Schedule.TIMES_MAX) {
                timesRow.addView(Button(this).apply {
                    text = if (times == n) "✓$n" else "$n"
                    if (times == n) tag = Brut.PRIMARY
                    setOnClickListener { times = n; drawTimes(); syncEvery() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3f) })
            }
            Brut.style(timesRow)
        }
        drawTimes()
        box.addView(timesRow, LinearLayout.LayoutParams(-1, -2))

        // 间隔（次数 > 1 才有意义）
        val everyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val everyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val everyLabels = listOf(5, 10, 15, 30)
        fun drawEvery() {
            everyBox.visibility = if (times > 1) android.view.View.VISIBLE else android.view.View.GONE
            everyRow.removeAllViews()
            for (m in everyLabels) {
                everyRow.addView(Button(this).apply {
                    text = if (every == m) "✓${m}分" else "${m}分"
                    if (every == m) tag = Brut.PRIMARY
                    setOnClickListener { every = m; drawEvery() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3f) })
            }
            Brut.style(everyRow)
        }
        everyBox.addView(TextView(this).apply { text = "每次隔多久"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        everyBox.addView(everyRow, LinearLayout.LayoutParams(-1, -2))
        syncEvery = { drawEvery() }
        drawEvery()
        box.addView(everyBox, LinearLayout.LayoutParams(-1, -2))

        // 提醒强度
        box.addView(TextView(this).apply { text = "提醒强度（震动）"; textSize = 13f; setPadding(0, dp(10f), 0, dp(4f)) })
        val strengthRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val strengthVals = listOf(Schedule.VIBE_GLOBAL, Vibrate.OFF, Vibrate.LIGHT, Vibrate.MID, Vibrate.STRONG)
        val strengthLabels = listOf("跟设置", "关", "轻", "中", "重")
        fun drawStrength() {
            strengthRow.removeAllViews()
            for ((i, v) in strengthVals.withIndex()) {
                strengthRow.addView(Button(this).apply {
                    text = if (strength == v) "✓${strengthLabels[i]}" else strengthLabels[i]
                    if (strength == v) tag = Brut.PRIMARY
                    setOnClickListener { strength = v; drawStrength() }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3f) })
            }
            Brut.style(strengthRow)
        }
        drawStrength()
        box.addView(strengthRow, LinearLayout.LayoutParams(-1, -2))

        drawKind(); drawDays()
        Brut.style(box)   // 整张卡统一刷成 Brut 皮肤（输入框 → field、按钮 → 白底/薄荷绿、文字 → 墨色）

        val dlg = ThemeDialogs.builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("取消", null)
            .setPositiveButton(if (existing == null) "加上" else "保存", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = edText.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { toast("先写上提醒什么"); return@setOnClickListener }
                if (weekly && mask == 0) { toast("选一下周几"); return@setOnClickListener }
                val r = Schedule.Rule(
                    id = existing?.id ?: 0L,
                    text = Memo.clip(text, 60),
                    weekly = weekly,
                    mask = if (weekly) mask else 0,
                    dateMs = if (weekly) 0L else Todo.dayStart(dateMs),
                    minute = minute,
                    times = times,
                    everyMin = every,
                    strength = strength,
                    enabled = existing?.enabled ?: true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                )
                if (existing == null) store.add(r) else store.update(existing.id) { r }
                Schedule.sync(this@ScheduleActivity)
                dlg.dismiss()
                render()
            }
        }
        dlg.show()
    }

    private fun pickTime(curMinute: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPick(h * 60 + m) }, curMinute / 60, curMinute % 60, true).show()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(ctx: android.content.Context) = Intent(ctx, ScheduleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
