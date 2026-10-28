package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 待办 → 系统日历的**确认卡**（M3）。
 *
 * ⚠️ 铁律：不静默写系统日历。任何路径（通知上的「写进日历」、待办列表里的日历按钮）都必须先落到这一页，
 * 把「标题 / 时间 / 写进哪个日历」摊开给用户看，点了「确认写进日历」才动 CalendarContract。
 * 这条不可绕过 —— 写用户自己的日历是不可逆的（脏了还得自己去系统日历里删），所以宁可多一次点击。
 *
 * 这一页同时是待办的管理页（改时间、改备注、标完成、转成随手记），不只是一次性确认框。
 * CalendarContract 那部分是 Android API；本页所有「时间怎么显示、标题怎么拼」的判断都在 [Todo] 的纯函数里。
 */
class TodoActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs
    private var id = 0L
    private var wrote = false   // 已经确认写过日历，防止旋转 / 重进重复插一条

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.applyTheme(prefs)
        id = intent?.getLongExtra(TodoReceiver.EXTRA_ID, 0L) ?: 0L
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // v1.85.13（U3）：内容加边距 —— 冷启动兜底分支的文字此前贴屏幕左缘
            setPadding(Ui.dp(this@TodoActivity, 16f), Ui.dp(this@TodoActivity, 8f), Ui.dp(this@TodoActivity, 16f), 0)
        }
        val sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)
        Brut.page(this, sv)
        render()
    }

    private fun render() {
        root.removeAllViews()
        val t = Todo.Store(this).get(id)
        if (t == null) {
            root.addView(TextView(this).apply { text = "这条待办已经不在了"; textSize = 15f })
            root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
            Brut.style(root)
            return
        }
        val dp = { v: Float -> Ui.dp(this, v) }
        root.addView(TextView(this).apply { text = "待办"; textSize = 22f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "改动即时生效 · ${if (t.calId > 0) "已写进日历" else "还没进日历"}"
            textSize = 12.5f; alpha = 0.75f
        })

        // ---- 时间：三个输入（日期 / 时 / 分）改完立刻存 ----
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t.dueAt }
        fun stamp(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
            java.util.Calendar.getInstance().apply {
                timeInMillis = t.dueAt
                set(y, mo, d, h, mi, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        root.addView(section("什么时候提醒"))
        val ymd = EditText(this).apply {
            setText(String.format("%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)))
            inputType = InputType.TYPE_CLASS_DATETIME; setSingleLine(); hint = "年-月-日"
        }
        val hm = EditText(this).apply {
            setText(String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)))
            inputType = InputType.TYPE_CLASS_DATETIME; setSingleLine(); hint = "时:分"
        }
        fun readTime(): Long? {
            val d = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(ymd.text.toString()) ?: return null
            val t2 = Regex("(\\d{1,2}):(\\d{2})").find(hm.text.toString()) ?: return null
            return stamp(d.groupValues[1].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[3].toInt(),
                t2.groupValues[1].toInt(), t2.groupValues[2].toInt())
        }
        fun saveTime() {
            val ms = readTime()
            if (ms == null) { toast("时间没看懂 —— 试试「明天 14:30」或「3 小时后」"); return }
            if (ms != t.dueAt) {
                Todo.Store(this).setDue(this@TodoActivity.id, ms)
                TodoReceiver.reschedule(this)   // 提醒时间变了 → 闹钟重排
                toast("已改"); render()
            }
        }
        root.addView(ymd); root.addView(hm)
        root.addView(Button(this).apply { text = "改时间"; setOnClickListener { saveTime() } })

        // ---- 备注 ----
        root.addView(section("备注（可空）"))
        val note = EditText(this).apply {
            setText(t.note); hint = "比如「带身份证」"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // P2-3：失焦才落盘（备注逐键会把整个 todo.json 序列化一遍）；页面退出时 root 减焦触发
            setOnFocusChangeListener { _, has -> if (!has) Todo.Store(this@TodoActivity).setNote(this@TodoActivity.id, text?.toString() ?: "") }
        }
        root.addView(note)

        // ---- 状态 ----
        root.addView(section("状态"))
        root.addView(Button(this).apply {
            text = if (t.done) "✓ 已完成（点一下改回未完成）" else "标为已完成"
            tag = Brut.PRIMARY
            setOnClickListener { Todo.Store(this@TodoActivity).setDone(this@TodoActivity.id, !t.done); TodoReceiver.reschedule(this@TodoActivity); render() }
        })
        root.addView(Button(this).apply {
            text = "改成 30 分钟后再提醒"
            setOnClickListener {
                Todo.Store(this@TodoActivity).setDue(this@TodoActivity.id, System.currentTimeMillis() + TodoReceiver.SNOOZE_MS)
                TodoReceiver.reschedule(this@TodoActivity); render()
            }
        })

        // ---- ⚠️ 铁律：日历只能从这里写，且必须先过这张确认卡 ----
        root.addView(section("系统日历"))
        root.addView(TextView(this).apply {
            text = if (t.calId > 0) "已写进「${t.calendar.ifBlank { "系统日历" }}」（事件 id ${t.calId}）—— 点下面那个按钮可以删掉它"
            else "确认卡：下面这一段就是确认卡。核对标题和时间再点「确认写进日历」，之前不会碰你的系统日历。"
            textSize = 13.5f; setLineSpacing(0f, 1.3f)
        })
        root.addView(TextView(this).apply {
            text = "标题：${t.text}\n时间：${Todo.fmtDue(t.dueAt, System.currentTimeMillis()) { hhmm(it) }}"
            textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(8f), 0, dp(4f))
        })
        root.addView(Button(this).apply {
            text = "确认写进日历"
            tag = Brut.PRIMARY
            setOnClickListener { confirmCalendar(t) }
        })
        if (t.calId > 0) root.addView(Button(this).apply {
            text = "从系统日历删掉这一条"
            tag = Brut.DANGER
            setOnClickListener { deleteFromCalendar(t) }
        })
        root.addView(TextView(this).apply {
            text = "只写你自己能编辑的日历；写完记下事件 id，删除时只删自己建的那一条，不会碰你原有的日程。"
            textSize = 12f; alpha = 0.75f
        })

        // ---- 顺手记那边 ----
        root.addView(section("随手记"))
        root.addView(Button(this).apply {
            text = "把这条也存一份到随手记"
            setOnClickListener { Memo.Store(this@TodoActivity).add(t.text); MemoNotify.refresh(this@TodoActivity); toast("已存到随手记") }
        })

        root.addView(Button(this).apply { text = "删掉这条待办"; tag = Brut.DANGER; setOnClickListener { deleteAll(t) } })
        root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
        Brut.style(root)
    }

    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, Ui.dp(this@TodoActivity, 18f), 0, Ui.dp(this@TodoActivity, 4f))
    }

    private fun hhmm(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    // ---------------- 日历写入：权限 → 挑日历 → 插事件 ----------------

    private fun confirmCalendar(t: Todo.Item) {
        if (t.calId > 0) { toast("已经写进日历了，要改先删掉那一条"); return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR), REQ_CAL)
            return
        }
        write(t)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAL) return
        val t = Todo.Store(this).get(id) ?: return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) write(t)
        else toast("没有日历写入权限，就不写了")
    }

    /** 查可写的日历 → 插一条 → 把 event id 记回条目 */
    private fun write(t: Todo.Item) {
        thread {
            val target = writableCalendar()
            if (target == null) { runOnUiThread { toast("没找到能写入的日历") }; return@thread }
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, t.text)
                put(CalendarContract.Events.DTSTART, t.dueAt)
                put(CalendarContract.Events.DTEND, t.dueAt + 30 * 60_000L)
                put(CalendarContract.Events.CALENDAR_ID, target.first)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                if (t.note.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, t.note)
            }
            val uri = try { contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
            catch (e: Exception) { runOnUiThread { toast("写日历失败：" + (e.message ?: e.javaClass.simpleName)) }; return@thread }
            val eventId = uri?.let { try { android.content.ContentUris.parseId(uri) } catch (_: Exception) { -1L } } ?: -1L
            runOnUiThread {
                if (eventId > 0) {
                    Todo.Store(this).setCalendar(id, eventId, target.second)
                    Memo.Store(this).add(t.text)     // 写进日历了，随手记那边留个底
                    render()
                    toast("已写进「${target.second}」")
                } else toast("写日历失败了，没改成")
            }
        }
    }

    /** (日历 id, 日历名)；按写入权限从小到大挑第一个（Google 优先，本地日历兜底） */
    private fun writableCalendar(): Pair<Long, String>? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY)
        val rows = ArrayList<Pair<Int, Triple<Long, String, Boolean>>>()
        try {
            contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val iName = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val iAcc = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val iPri = c.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                while (c.moveToNext()) {
                    val acc = c.getInt(iAcc)
                    if (acc < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue   // 只读日历写不了
                    rows.add(acc to Triple(c.getLong(iId), c.getString(iName) ?: "系统日历", if (Build.VERSION.SDK_INT >= 17) c.getInt(iPri) == 1 else false))
                }
            }
        } catch (_: Exception) {}
        if (rows.isEmpty()) return null
        rows.sortWith(compareBy({ it.first }, { if (it.second.third) 0 else 1 }))   // 权限小的先（Google），主日历次之
        val (_, best) = rows.first()
        return best.first to best.second
    }

    /** 只删自己建的那一条（按 id 精确删，不按标题匹配） */
    private fun deleteFromCalendar(t: Todo.Item) {
        if (t.calId <= 0) return
        thread {
            val n = try { contentResolver.delete(CalendarContract.Events.CONTENT_URI, "${CalendarContract.Events._ID}=?", arrayOf(t.calId.toString())) } catch (_: Exception) { 0 }
            runOnUiThread {
                if (n > 0) { Todo.Store(this).setCalendar(id, 0L, ""); render(); toast("已从日历删掉") } else toast("删不掉，可能已经被你在系统日历里删过了")
            }
        }
    }

    private fun deleteAll(t: Todo.Item) {
        if (t.calId > 0) deleteFromCalendar(t)
        Todo.Store(this).remove(id)
        TodoReceiver.reschedule(this)
        toast("已删掉")
        finish()
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAL = 91
        fun intent(ctx: android.content.Context, id: Long) = android.content.Intent(ctx, TodoActivity::class.java)
            .putExtra(TodoReceiver.EXTRA_ID, id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}