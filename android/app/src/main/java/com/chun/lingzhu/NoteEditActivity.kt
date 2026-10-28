package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 写一条 / 改一条。
 *
 * ## 为什么要有这个类
 *
 * v2.4.1 之前，新建笔记的唯一入口是悬浮球（OrbService.startQuickNote）。
 * 记事页的空状态文案却写着「写一句也行 —— 正文里打 # 就能加标签」，
 * 而页面上根本没有能写字的地方。真人走查时这是第一个卡死点：
 * 装上 App、没启动悬浮球，就一条笔记都记不了。
 *
 * 详情页也同样只有「关掉」—— 写错了没有编辑入口，只能重新截一次图。
 *
 * 所以补这个编辑器，两个用途：
 *  - openNew：记事页「写一条」→ 空白编辑
 *  - openEdit：详情页「编辑」→ 带出原文
 *
 * ## 为什么不用 QuickNote 复用
 *
 * QuickNote 走的是 TYPE_APPLICATION_OVERLAY 悬浮窗，硬依赖悬浮窗权限。
 * 而「写一条」恰恰是给不想开悬浮球的用户准备的 —— 用它就自相矛盾了。
 * 这里用普通 Activity：不需要任何权限，从记事页进，返回就回记事页。
 */
class NoteEditActivity : Activity() {

    companion object {
        private const val EXTRA_ID = "note_id"
        private const val STATE_DRAFT = "draft_text"

        /** 新建：不带 id */
        fun openNew(ctx: Context) {
            ctx.startActivity(Intent(ctx, NoteEditActivity::class.java))
        }

        /** 编辑已有笔记 */
        fun openEdit(ctx: Context, noteId: Long) {
            ctx.startActivity(Intent(ctx, NoteEditActivity::class.java).putExtra(EXTRA_ID, noteId))
        }
    }

    private lateinit var db: android.database.sqlite.SQLiteDatabase
    private lateinit var input: EditText
    /** 底部状态条：字数 + 标签。建好后每次输入都刷 */
    private lateinit var status: TextView
    private var noteId = 0L
    /**
     * 库打开成功没有。
     *
     * 开库会把 SQLiteOpenHelper 的 onOpen（[NoteVault] 的迁移从那里进来）原样抛出来，
     * 以前这一行裸在 onCreate 里 —— 升级后第一次进编辑页就是闪退。
     * 失败改画错误页（说明原因 + 可重试），并且**不建输入框**：
     * 下面所有用到 [input] 的路径（保存 / 丢稿确认 / 存草稿 / 着色）都先看这个标记，
     * 免得 lateinit 的 input 在没初始化的状态下被访问。
     */
    private var dbReady = false
    /** 开库失败的原因（只给错误页用） */
    private var dbError: String? = null
    /** 编辑既有笔记时的原始正文。没改就不写库，免得白刷一次 updatedAt */
    private var original = ""
    /** 原文标题。updateBody 要连 title 一起写，传空会把原标题抹掉 */
    private var originalTitle = ""
    /**
     * 进页面时读到的版本号，保存时原样交给 [Notes.updateBody] 当条件。
     * 不记它的话，编辑期间这条被别处改过（悬浮球速记 / 同步），
     * 保存会把别人的改动直接盖掉，双方都不知道。
     *
     * 初值 -1（和 [Notes.revOf] 的「行不存在」一致）；新建不会走 updateBody，
     * 编辑模式下 onCreate 读到原文后立刻被真实 rev 覆盖，所以不会带着 -1 去保存。
     */
    private var loadedRev = -1
    /** 保存时撞上并发冲突的次数。只为让提示文案说得准，不参与任何判断 */
    private var conflictCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        noteId = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        // 开库**不能裸着**：SQLiteOpenHelper 会把 onOpen（NoteVault 的迁移从那里进来）
        // 抛的异常原样带出来，升级后第一次点「写一条」就是闪退。失败改画错误页。
        try {
            db = NoteStore(this).writableDatabase
            dbReady = true
            dbError = null
        } catch (e: Exception) {
            dbReady = false
            dbError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 老详情页忘了设 padding，正文贴着屏幕边缘（真机实测首个像素 x=0）。
            // 这里一开始就设好，别再犯同一个错。
            val p = Ui.dp(this@NoteEditActivity, 16f)
            setPadding(p, p, p, p)
        }
        setContentView(root)
        Brut.page(this, root)
        // 库没开出来就只有这一页：不建输入框、不挂高亮、不留「保存」——
        // 让用户对着一个写完了却存不下去的编辑器打字是最坏的结果。
        if (!dbReady) { showDbError(root); return }
        // 顶栏只留一行标题：正文要占满屏幕，工具栏一大就挤掉了写字的地方。
        root.addView(TextView(this).apply {
            text = if (noteId > 0L) "改这条" else "写一条"
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, Ui.dp(this@NoteEditActivity, 8f))
        })

        input = EditText(this).apply {
            hint = "记点什么…（正文里打 # 就是标签，多个用空格隔开）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 8
            setPadding(Ui.dp(this@NoteEditActivity, 10f), Ui.dp(this@NoteEditActivity, 10f),
                       Ui.dp(this@NoteEditActivity, 10f), Ui.dp(this@NoteEditActivity, 10f))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = Ui.dp(this@NoteEditActivity, 4f)
        })

        // 底部状态条：字数 + 正文里解析出来的标签。
        //
        // 不做工具栏（加粗 / 列表那些按钮），一是不引入富文本存储，二是它们会常驻
        // 占掉一行高度。标签只**显示**解析结果，真要改还是直接改正文里的 #。
        status = TextView(this).apply {
            textSize = 12f
            setPadding(0, Ui.dp(this@NoteEditActivity, 8f), 0, 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        // 编辑模式：先把原文读出来。
        //
        // 「读库抛异常」和「查不到这一行」要分开：前者是这次没读出来（笔记多半还在），
        // 以前两者都走「已经不在了 → finish()」，一次瞬时故障就把用户赶出页面，
        // 还告诉人家数据没了。异常那条走可重试的错误页；真的没有这一行才说「已经不在了」。
        if (noteId > 0L) {
            val full = try {
                Notes.one(db, noteId)
            } catch (e: Exception) {
                showReadError(root, e)
                return
            }
            if (full == null) {
                toast("这条笔记已经不在了")
                finish()
                return
            }
            original = full.item.body
            originalTitle = full.item.title
            loadedRev = full.item.rev
            input.setText(original)
            input.setSelection(original.length)
        }

        // 屏幕旋转 / 进程重建后把草稿找回来。
        //
        // 上面刚把 input 设成原文（新建时是空），会把系统自动恢复的内容盖掉 ——
        // 真机实测：写了一半转横屏，字全没了。
        // EditText 自己有 id 时系统会存 state，但这里是在代码里 new 出来的、
        // 没有 id，所以必须自己存。存的是"当前输入"，不是原文。
        if (savedInstanceState != null) {
            val draftText = savedInstanceState.getString(STATE_DRAFT)
            if (draftText != null) {
                input.setText(draftText)
                input.setSelection(draftText.length)
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteEditActivity, 10f), 0, 0)
        }

        row.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { confirmDiscard() }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(Button(this).apply {
            text = "保存"
            tag = Brut.PRIMARY
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteEditActivity, 8f)
        })

        root.addView(row)
        Brut.style(root)

        // 字数 / 标签跟随输入更新。afterTextChanged 里只算一个短串，不碰数据库。
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshStatus() }
        })
        refreshStatus()

        // 正文里的 #标签 / [[链接]] / `代码` 上色。装在 TextWatcher 之后没问题：
        // 它自己也是一个独立的 watcher，只挂 span、不改文字，
        // 所以上面那个算字数的 watcher 不受影响（顺序也不影响结果）。
        // 必须挂在这个页面里唯一的输入框上，别处没有第二个。
        try { NoteHighlight.attach(input) } catch (e: Exception) { /* 上色失败不该挡住写字 */ }

        input.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 高亮持着 Handler 和 ViewTreeObserver 回调：不拆的话，
        // 页面关掉后那几次 throttle 回调还会往一个已经销毁的输入框上贴 span。
        if (::input.isInitialized) {
            try { NoteHighlight.detach(input) } catch (e: Exception) {}
        }
    }

    /**
     * 返回键。**必须自己处理**，否则默认行为是直接 finish ——
     * 真机实测：输入 "dirtytest" 后按返回键，页面纹丝不动（因为
     * Activity 没设 onBackPressed，系统把它交给了输入法的收起），
     * 用户再点「取消」就静默丢掉刚写的内容。
     *
     * 这里统一交给 [confirmDiscard]，有改动才问。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        confirmDiscard()
    }

    /**
     * 返回键的第二道保险。
     *
     * 真机实测：只重写 onBackPressed 时，编辑页里按返回键**毫无反应** ——
     * 页面不退出、不弹确认，用户只能去点「取消」然后静默丢内容。
     *
     * 原因是输入法在前台时，返回键先被 IME 消费掉（收起键盘），
     * 等物理返回真正到达 Activity 时行为已经不可预期。
     * 这里在 keyDown 阶段直接截住，两条路都走 [confirmDiscard]。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            confirmDiscard()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 有未保存内容时先确认，再退出。
     *
     * 判断「有没有改动」用原始正文对比：
     * 新建场景 original 是空串，只要打了字就算脏；
     * 编辑场景 original 是进页面时的正文，没改就不该弹窗。
     */
    private fun confirmDiscard() {
        // 错误页（开库失败）上没有输入框：没有什么可丢，直接退出
        if (!::input.isInitialized) { finish(); return }
        val now = input.text?.toString().orEmpty()
        // 唯一的退出判据就是「输入框和进页面时读到的原文不一样」。
        // 保存被拒（冲突 / 笔记已不在）时页面留在原地、输入框原样，
        // 只要用户真的动过，这里仍然会问；而没动过就直接退出本来就该安静关掉。
        if (!NoteEditPolicy.isDirty(now, noteId > 0L, original)) {
            finish()
            return
        }
        ThemeDialogs.builder(this)
            .setTitle("不保存就退出？")
            .setMessage("刚写的内容会丢掉。")
            .setNegativeButton("继续写", null)
            .setPositiveButton("丢掉") { _, _ -> finish() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 错误页上 input 没建：没有草稿可存，别为它抛 lateinit
        if (::input.isInitialized) outState.putString(STATE_DRAFT, input.text?.toString().orEmpty())
    }

    /**
     * 保存。决策逻辑在 [NoteEditPolicy.decide]（纯函数，有 JVM 单测覆盖），
     * 这里只负责落库和反馈。
     *
     * 第三条规则「写库失败必须明说，不能悄悄关页面」留在这一层 ——
     * 它依赖真实的 SQLite，测试由真机自检覆盖。
     */
    private fun save() {
        // 库打不开时页面只有错误页，[input] 根本不存在：这时候没有任何内容可存
        if (!::input.isInitialized) { finish(); return }
        val body = input.text?.toString().orEmpty()
        when (val act = NoteEditPolicy.decide(body, noteId > 0L, original)) {
            is NoteEditPolicy.Action.RejectEmpty -> {
                toast("还没写内容")
                return
            }
            is NoteEditPolicy.Action.CloseWithoutWrite -> {
                finish()
                return
            }
            else -> { /* 继续落库 */ }
        }
        try {
            if (noteId > 0L) {
                val hit = Notes.updateBody(db, noteId, originalTitle, body, System.currentTimeMillis(), loadedRev)
                when (hit) {
                    is Notes.UpdateResult.Wrote -> {
                        // 写成功了：版本号和「基线」一起推进。
                        // original 推进之后，再点保存会走 CloseWithoutWrite（内容确实已在库里）；
                        // 而被拒的 Stale 分支不会走到这里，基线仍是进页面时那份 ——
                        // 用户再点保存会真的重试，而不是被当成「没改动」关页面。
                        loadedRev = hit.rev
                        original = body
                    }
                    Notes.UpdateResult.Gone -> {
                        // 0 行 = 这条笔记已经不在库里了（被删或被清）。
                        // 以前这里静默成功、照样 RESULT_OK 退出，用户以为存上了。
                        // **不直接 finish**：输入框里的内容还在，用户至少能复制走。
                        toast("这条笔记已经不在了，没能保存。刚写的内容还在，可以先复制出来")
                        setResult(RESULT_CANCELED)
                        return
                    }
                    Notes.UpdateResult.Stale -> {
                        // 编辑期间被别处改过。不能覆盖，也不能把用户的字扔掉 ——
                        // 页面保持打开、输入框原样，用户可以先复制再决定怎么办。
                        conflictCount++
                        toast(if (conflictCount == 1)
                            "这条笔记刚在别处被改过，为免覆盖别人的修改没有保存。你写的内容还在，可以先复制出来"
                        else
                            "还是没有保存：这条笔记一直在被改动。请先复制你的内容，再返回重新打开这条笔记")
                        setResult(RESULT_CANCELED)
                        return
                    }
                }
            } else {
                val id = Notes.insert(db, Note.create(body, System.currentTimeMillis()))
                if (id <= 0L) { toast("没存上，再试一次"); return }
            }
        } catch (e: NoteVault.IndexException) {
            // 内容已经写进 md 文件（fsync + 改名都完成了），只是索引没跟上。
            // 这不是「保存失败」：说失败的话，用户会把已经存好的内容再抄一遍，
            // 而重建索引时那一段就会被覆盖/重复。所以按成功处理并明说正在补索引。
            toast("内容已保存，索引稍后重建")
        } catch (e: Exception) {
            toast("保存失败：" + (e.message ?: e.javaClass.simpleName))
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    /** 字数 + 标签：正文每变一次就刷一次（只拼一个短串，开销可以忽略） */
    private fun refreshStatus() {
        if (!::status.isInitialized) return
        // 错误页上没有输入框（开库失败时 onCreate 提前返回）：没有状态可刷
        if (!::input.isInitialized) return
        val body = input.text?.toString().orEmpty()
        val chars = body.count { !it.isWhitespace() }
        val tags = try { NoteSyntax.tags(body) } catch (e: Exception) { emptyList() }
        val tagPart = if (tags.isEmpty()) "" else
            " · " + tags.take(8).joinToString(" ") { "#" + it } + (if (tags.size > 8) " +" + (tags.size - 8) else "")
        status.text = chars.toString() + " 字" + tagPart
    }

    /**
     * 库开不出来时的错误页（和记事页 / 详情页同一套）。
     *
     * 关键一条：**不建输入框**。一个能打字、保存时才发现存不下去的编辑器，
     * 比直接说「打不开」更伤人 —— 用户会把内容写进去，然后丢掉。
     * 原因照贴原文，可复制；「重新试」= 重建 Activity，「返回」= 关掉。
     */
    private fun showDbError(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "笔记库没能打开"; textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, Ui.dp(this@NoteEditActivity, 6f))
        })
        root.addView(Ui.text(this,
            "笔记文件还在，只是这次读不出来，所以先不让你往下写 —— 免得写完存不进去。",
            13f, Ui.TX3))
        root.addView(Ui.text(this, "原因：" + (dbError ?: "未知"), 12.5f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NoteEditActivity, 8f), 0, Ui.dp(this@NoteEditActivity, 8f))
            setTextIsSelectable(true)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteEditActivity, 10f), 0, 0)
        }
        row.addView(Button(this).apply {
            text = "重新试"; tag = Brut.PRIMARY
            setOnClickListener { recreate() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteEditActivity, 8f)
        })
        root.addView(row)
        Brut.style(root)
    }

    /**
     * 库开着，但这一次读这条笔记失败了。
     *
     * 和「已经不在了」分开：这里不 finish，也不用「没了」这种话 ——
     * 用户的原文多半还在，只是这一次没读出来。给「重新试」（重建 Activity 重读）
     * 和「返回」两个出口；重试仍然失败的话提示里带着原因，用户能把原文抄走。
     */
    private fun showReadError(root: LinearLayout, e: Exception) {
        root.addView(TextView(this).apply {
            text = "这条笔记这次没读出来"; textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, Ui.dp(this@NoteEditActivity, 6f))
        })
        root.addView(Ui.text(this,
            "库是开着的，只是读这一条时出错了。它多半还在 —— 点「重新试」再读一次。",
            13f, Ui.TX3))
        root.addView(Ui.text(this, "原因：" + (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
            12.5f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NoteEditActivity, 8f), 0, Ui.dp(this@NoteEditActivity, 8f))
            setTextIsSelectable(true)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteEditActivity, 10f), 0, 0)
        }
        row.addView(Button(this).apply {
            text = "重新试"; tag = Brut.PRIMARY
            setOnClickListener { recreate() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteEditActivity, 8f)
        })
        root.addView(row)
        Brut.style(root)
    }

    private fun toast(s: String) {
        try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }
}