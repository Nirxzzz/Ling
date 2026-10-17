package com.chun.lingzhu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 待办 + 随手记的列表页（M3）：小球菜单、随手记通知的「看全部」、简报通知都进这里。
 *
 * 本页只是列表和管理 —— **写系统日历不给按钮**，一律跳 [TodoCardActivity] 的确认卡，
 * 这样「不静默写日历」只有一条路径，绕不过去。
 * 「加一条」按句子里的时间关键词就地解析（`明天 15:30 交周报` / `今晚8点 买菜` / `3天后`），
 * 解析规则是 [Todo.parseWhen] 这个纯函数，单测直接测。
 */
class TodoListActivity : Activity() {
    private lateinit var root: LinearLayout
    private val store by lazy { Todo.Store(this) }
    private val memo by lazy { Memo.Store(this) }
    private var tab = 0   // 0 待办 / 1 随手记

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.theme = Prefs(this).theme()
        tab = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)
        Brut.page(this, sv)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()   // 从确认卡回来时列表要跟着变
    }

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "记事"; textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, name) in listOf("待办", "随手记").withIndex()) {
            bar.addView(Button(this).apply {
                text = (if (tab == i) "▸ " else "") + name
                if (tab == i) tag = Brut.PRIMARY
                setOnClickListener { tab = i; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar)
        // v1.86 记期入口：能重复的提醒（日历 / 每周几）。它自己一页，这里只放入口。
        root.addView(Button(this).apply {
            text = "记期（日历 / 每周提醒）"
            setOnClickListener {
                try { startActivity(ScheduleActivity.intent(this@TodoListActivity)) }
                catch (_: Exception) { android.widget.Toast.makeText(this@TodoListActivity, "打不开记期页", android.widget.Toast.LENGTH_SHORT).show() }
            }
        })
        if (tab == 0) renderTodos() else renderMemos()
        root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
        Brut.style(root)
    }

    private fun renderTodos() {
        val now = System.currentTimeMillis()
        val list = Todo.sorted(store.list())
        val overdue = Todo.due(list, now)
        val today = list.filter { it.dueAt in now until now + 86400_000L }
        val rest = list.filter { it !in overdue && it !in today }

        root.addView(TextView(this).apply {
            text = "共 ${list.count { !it.done }} 件没做（最多留 ${Todo.MAX} 条）"; textSize = 12.5f; alpha = 0.75f
            setPadding(0, Ui.dp(this@TodoListActivity, 6f), 0, Ui.dp(this@TodoListActivity, 4f))
        })
        if (overdue.isNotEmpty()) group("过期 / 到点了", overdue)
        if (today.isNotEmpty()) group("今天", today)
        if (rest.isNotEmpty()) group("以后", rest)

        root.addView(TextView(this).apply {
            text = "加一条"; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(this@TodoListActivity, 18f), 0, Ui.dp(this@TodoListActivity, 4f))
        })
        val ed = EditText(this).apply { hint = "比如「明天 15:30 交周报」"; inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(ed)
        root.addView(Button(this).apply {
            text = "加上"; tag = Brut.PRIMARY
            setOnClickListener {
                val raw = ed.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return@setOnClickListener
                val p = Todo.parseWhen(raw, now)
                val nid = store.add(p.title, p.dueAt)
                if (nid > 0) { TodoReceiver.schedule(this@TodoListActivity, nid); render() }
            }
        })
        tip("时间写在句子里就行：明天 / 今晚 / 后天 / 3天后 / 15:30 / 「周五」。解析不了就把整句当标题、默认一小时后提醒，剩下的字进备注。")
    }

    private fun renderMemos() {
        val list = memo.list()
        root.addView(TextView(this).apply {
            text = "共 ${list.size} 条（最多留 ${Memo.MAX} 条）· 通知栏那条常驻通知里可以直接打字记"; textSize = 12.5f; alpha = 0.75f
            setPadding(0, Ui.dp(this@TodoListActivity, 6f), 0, Ui.dp(this@TodoListActivity, 4f))
        })
        val ed = EditText(this).apply { hint = "想到什么就写一句"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        root.addView(ed)
        root.addView(Button(this).apply {
            text = "记下"; tag = Brut.PRIMARY
            setOnClickListener {
                if (memo.add(ed.text?.toString().orEmpty()) > 0) { MemoNotify.refresh(this@TodoListActivity); render() }
            }
        })
        if (list.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "还没有记过。\n下拉状态栏里 ${Preset.appName} 那条常驻通知，输入框里写一句就存下来了。"; textSize = 14f
            })
            return
        }
        for (m in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = "${Memo.clip(m.text, 300)}\n${Memo.fmtAt(m.at)}" + if (m.todoId > 0) " · 已转成待办" else ""
                textSize = 14f; setLineSpacing(0f, 1.3f)
            })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Button(this).apply {
                text = "转成待办"
                setOnClickListener {
                    val nid = store.add(Memo.clip(m.text, 120), System.currentTimeMillis() + 3600_000L)
                    if (nid > 0) { memo.link(m.id, nid); TodoReceiver.schedule(this@TodoListActivity, nid); render() }
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            acts.addView(Button(this).apply {
                text = "删掉"; tag = Brut.DANGER
                setOnClickListener { memo.remove(m.id); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(acts)
            root.addView(row)
            Brut.card(row)
        }
    }

    private fun group(name: String, items: List<Todo.Item>) {
        root.addView(TextView(this).apply {
            text = "$name（${items.count { !it.done }}）"; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, Ui.dp(this@TodoListActivity, 18f), 0, Ui.dp(this@TodoListActivity, 4f))
        })
        for (t in items) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = (if (t.done) "✓ " else "") + t.text +
                    "\n" + Todo.fmtDue(Todo.dayStart(t.dueAt), Todo.dayStart(System.currentTimeMillis())) { Todo.fmtHM(it) } +
                    (if (t.calId > 0) " · 已进日历" else "") +
                    (if (t.note.isBlank()) "" else "\n备注：${Memo.clip(t.note, 60)}")
                textSize = 14f; setLineSpacing(0f, 1.3f)
            })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acts.addView(Button(this).apply {
                text = if (t.done) "改回未完成" else "完成"
                setOnClickListener { store.setDone(t.id, !t.done); TodoReceiver.reschedule(this@TodoListActivity); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // 详情 / 写日历只在这一条路径上，必须经过确认卡
            acts.addView(Button(this).apply {
                text = "详情 / 日历"
                setOnClickListener { startActivity(TodoActivity.intent(this@TodoListActivity, t.id)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            acts.addView(Button(this).apply {
                text = "删掉"; tag = Brut.DANGER
                setOnClickListener { store.remove(t.id); TodoReceiver.reschedule(this@TodoListActivity); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(acts)
            root.addView(row)
            Brut.card(row)
        }
    }

    private fun tip(s: String) = root.addView(TextView(this).apply {
        text = s; textSize = 12f; alpha = 0.75f; setPadding(0, Ui.dp(this@TodoListActivity, 4f), 0, 0)
    })

    companion object {
        const val EXTRA_TAB = "tab"
        fun intent(ctx: android.content.Context, tab: Int = 0) = Intent(ctx, TodoListActivity::class.java)
            .putExtra(EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}