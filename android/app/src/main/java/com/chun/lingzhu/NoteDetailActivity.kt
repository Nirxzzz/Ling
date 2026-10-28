package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 一条笔记的详情：看正文、看图、看附件，点得动里面的链接。
 *
 * 这里不做编辑器（编辑走 [NoteEditActivity]，本页只「编辑」按钮跳过去）。
 * 三件以前没有的事：
 *  - **链接能点**：`[[目标#小节|别名]]` 与 `[文字](dest)` 都在正文里可点，
 *    目标唯一才跳，有歧义就摆出候选让用户选，找不到就明说 —— 不做「猜一个」。；
 *  - **小节能跳**：本篇里的 `[[#小节]]` 直接滚到那个标题；
 *  - **反向链接**：谁引用了这一篇。要扫全库，所以放后台线程，扫完再贴上来。
 */
class NoteDetailActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var sv: ScrollView
    private lateinit var db: android.database.sqlite.SQLiteDatabase
    /**
     * 库打开成功没有。
     *
     * 开库会把 SQLiteOpenHelper 的 onOpen（[NoteVault] 的迁移从那里进来）原样抛出来，
     * 以前这一行裸在 onCreate 里 —— 升级后第一次点开笔记就是闪退。
     * 现在失败只记原因、页面改画 [showDbError]；所有读库入口先看这个标记，
     * 免得 lateinit 的 [db] 在没初始化的状态下被访问。
     */
    private var dbReady = false
    /** 开库失败的原因（只给错误页用） */
    private var dbError: String? = null
    private var noteId = 0L

    /** 正文里点击用的全套文档（uid/标题/正文/路径/别名），扫一次留着用 */
    private var docs: List<NoteLinks.Doc> = emptyList()
    /** 反向链接扫描跑过一次没有：失败不重试，避免每次 onResume 都卡一遍 */
    private var docsTried = false
    /** 本次 render 的代次：后台线程回来时如果已经重画过，就丢掉旧结果 */
    private var generation = 0
    /**
     * 进页面后要滚到的小节（从别的笔记的 `[[A#小节]]` 跳过来时带上的）。
     *
     * 只在第一次画完之后消费一次：onResume 也会重画，若不在这里清掉，
     * 用户从编辑器返回就会被弹回那个标题。
     */
    private var pendingHeading: String? = null
    /** 等 layout 的重投次数上限：布局永远不成立时也不能无限 post */
    private var headingRetries = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        noteId = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        pendingHeading = intent?.getStringExtra(EXTRA_HEADING)?.takeIf { it.isNotBlank() }
        // 开库**不能裸着**：SQLiteOpenHelper 会把 onOpen（NoteVault 的迁移从那里进来）
        // 抛的异常原样带出来，升级后第一次点开笔记就是闪退。失败改画错误页，可重试。
        try {
            db = NoteStore(this).writableDatabase
            dbReady = true
            dbError = null
        } catch (e: Exception) {
            dbReady = false
            dbError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
        // 之前这里没有任何 padding，真机实测正文首个像素落在 x=0 —— 字贴着屏幕边缘。
        // Brut.page() 只管状态栏配色，不加内边距。这里补上，和记事页（32dp）对齐。
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@NoteDetailActivity, 16f)
            setPadding(p, p, p, p)
        }
        sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)
        Brut.page(this, sv)
        if (dbReady) render() else showDbError()
    }

    /**
     * 从编辑器返回时要把内容重画一遍。
     *
     * 没有这段的话：详情页 →「编辑」→ 改完保存 → 回到详情页，
     * 看到的还是**改之前**的正文（Activity 没重建，render 只在 onCreate 调过），
     * 用户会以为没保存上。真机实测确实如此。
     */
    private var renderedOnce = false

    override fun onResume() {
        super.onResume()
        if (dbReady && renderedOnce) render()
        renderedOnce = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // 后台的反向链接扫描就让它自己跑完（只读，不会写坏东西），
        // 回来时看到代次对不上就丢掉 —— 不往已经没了的窗口贴控件。
        generation++
    }

    private fun render() {
        generation++
        val gen = generation
        root.removeAllViews()
        if (!dbReady) { showDbError(); return }
        // 读库**抛异常**和「查不到这一行」是两件事：
        // 前者说明库这次读不出来，这条笔记可能还在硬盘上；把两者都画成「已经不在了」，
        // 等于让一次临时故障冒充用户数据丢失。所以异常单独走一条路：说清原因 + 可重试。
        val full = try {
            Notes.one(db, noteId)
        } catch (e: Exception) {
            pendingHeading = null
            readFailed(e)
            return
        }
        if (full == null) {
            // 查询成功但没有这一行：笔记确实没了，小节跳转也就没有意义（清掉，
            // 免得这条 Activity 日后重画时还去跳）。
            pendingHeading = null
            root.addView(Ui.text(this, "这条笔记已经不在了", 14f, Ui.TX3))
            root.addView(Button(this).apply { text = "关掉"; setOnClickListener { finish() } })
            Brut.style(root)
            return
        }

        root.addView(TextView(this).apply {
            text = if (full.item.title.isNotBlank()) full.item.title else "笔记";
            textSize = 21f; typeface = Typeface.DEFAULT_BOLD;
            setPadding(0, 0, 0, Ui.dp(this@NoteDetailActivity, 6f))
        })
        val meta = ArrayList<String>(3)
        meta.add(fmtFull(full.item.createdAt))
        if (full.item.updatedAt > full.item.createdAt + 60_000L) meta.add("改于 " + fmtFull(full.item.updatedAt))
        if (full.item.pinned) meta.add("置顶")
        if (full.item.archived) meta.add("已归档")
        if (full.item.deleted) meta.add("在回收站")
        root.addView(Ui.text(this, meta.joinToString(" · "), 12f, Ui.TX3))

        // 图：先放缩略图占位，点开才是原图。原图动辄 1080x2400，直接解会卡一下
        if (full.hasImage) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER;
                setPadding(0, Ui.dp(this@NoteDetailActivity, 10f), 0, Ui.dp(this@NoteDetailActivity, 10f));
                setOnClickListener { openFull(full.item.uid) }
            }
            val f = NoteAttach.thumbFile(this, full.item.uid)
            if (f != null && f.exists()) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { iv.setImageBitmap(it) }
            }
            root.addView(iv, LinearLayout.LayoutParams(-1, Ui.dp(this@NoteDetailActivity, 240f)))
        }

        // 正文：原样显示（不重排 Markdown，用户写的什么样就什么样），
        // 只是把链接的位置标成可点。直接挂在 root 下，跳小节时算滚动位置才不用做偏移换算。
        val bodyTv = TextView(this).apply {
            text = linkify(full.item.body.ifBlank { "（空笔记）" }, full.item.uid, full.item.body)
            textSize = 15f
            setTextIsSelectable(true)
            movementMethod = TapOrSelect
            setPadding(0, Ui.dp(this@NoteDetailActivity, 8f), 0, 0)
        }
        root.addView(bodyTv)

        // 小节导航：两个以上标题才给，否则这一行是纯噪音
        val heads = try { NoteSyntax.headings(full.item.body) } catch (e: Exception) { emptyList() }
        if (heads.size >= 2) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@NoteDetailActivity, 10f), 0, 0)
            }
            bar.addView(Ui.text(this, "跳到", 12f, Ui.TX3).apply {
                setPadding(0, 0, Ui.dp(this@NoteDetailActivity, 8f), 0)
            })
            heads.take(8).forEach { h ->
                bar.addView(Ui.button(this, h.text.ifBlank { "（空标题）" }) {
                    scrollToOffset(bodyTv, h.offset)
                }, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = Ui.dp(this@NoteDetailActivity, 6f)
                })
            }
            root.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(bar, -2, -2)
            }, LinearLayout.LayoutParams(-1, -2))
        }

        if (full.tags.isNotEmpty()) {
            val tags = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@NoteDetailActivity, 8f), 0, 0)
            }
            full.tags.forEach { t ->
                tags.addView(Ui.text(this, "#" + t, 12.5f, Ui.TX3).apply {
                    // 点标签 → 回列表只看这个标签（和列表页的标签 chip 同一条路）
                    setOnClickListener {
                        try { startActivity(NotesActivity.intent(this@NoteDetailActivity, 0, t)) }
                        catch (e: Exception) { toast("打不开列表") }
                    }
                    val padV = Ui.dp(this@NoteDetailActivity, 5f)
                    val padH = Ui.dp(this@NoteDetailActivity, 4f)
                    setPadding(padH, padV, padH, padV)
                })
            }
            root.addView(tags)
        }

        renderAttachments()
        // 反向链接要长在附件下面、操作按钮上面。后台线程回来时不能 root.addView
        // （那会把它甩到最底下、跑到「编辑 / 删除」之后），所以先留一个空槽位。
        val backHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(backHost, LinearLayout.LayoutParams(-1, -2))
        renderBacklinks(full, gen, backHost)

        // 底部操作：编辑 / 删除 / 关掉。
        //
        // 原来只有一个「关掉」，而且是未指定 LayoutParams 的 Button ——
        // MATCH_PARENT 铺满整行 + Brut 的 2dp 粗描边，屏幕上最显眼的元素
        // 成了一个"退出"操作，视觉重心完全错了。现在三个按钮平分一行。
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteDetailActivity, 16f), 0, 0)
        }
        fun act(label: String, primary: Boolean = false, run: () -> Unit) =
            Button(this).apply {
                text = label
                if (primary) tag = Brut.PRIMARY
                setOnClickListener { run() }
            }
        row.addView(act("编辑", true) {
            try { NoteEditActivity.openEdit(this@NoteDetailActivity, noteId) }
            catch (e: Exception) { toast("打不开编辑器") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(act("删除") {
            confirmDelete()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteDetailActivity, 8f)
        })
        row.addView(act("关掉") { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteDetailActivity, 8f)
        })
        root.addView(row)

        // 这条笔记挂着待办：给一条真能走的路，而不是只留个 todo_id 谁都看不见
        if (full.item.todoId > 0L) {
            root.addView(Button(this).apply {
                text = "看这条笔记的待办 →"
                setOnClickListener {
                    try { startActivity(TodoActivity.intent(this@NoteDetailActivity, full.item.todoId)) }
                    catch (e: Exception) { toast("打不开待办") }
                }
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Ui.dp(this@NoteDetailActivity, 8f)
            })
        }
        Brut.style(root)
        // 跳小节必须等这一屏画完：TextView 的行位置只能从 layout 拿。
        // 放在最后一行，且只消费一次（见 [pendingHeading]）。
        pendingHeading?.let { jumpWhenLaidOut(it) }
    }

    /**
     * 库开不出来时的整页错误态（和 [NotesActivity.showDbError] 同一套三条要求）。
     *
     * 1. **不能画「笔记已经不在了」** —— 那是把「读不到」说成「你的笔记没了」；
     * 2. 要能重试：迁移失败、磁盘临时故障都会自己好，重建 Activity 是最省事的重试；
     * 3. 要能退出：卡在只能重试的页面上同样难受。
     * 原因照贴原文 —— 「打不开」三个字没法让人判断是磁盘满还是升级失败。
     */
    private fun showDbError() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "笔记库没能打开"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, Ui.dp(this@NoteDetailActivity, 6f))
        })
        root.addView(Ui.text(this,
            "笔记文件还在，只是这次读不出来。可以点「重新试」再开一次；一直不行就先把这条信息记下来。",
            13f, Ui.TX3))
        root.addView(Ui.text(this, "原因：" + (dbError ?: "未知"), 12.5f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NoteDetailActivity, 8f), 0, Ui.dp(this@NoteDetailActivity, 8f))
            setTextIsSelectable(true)   // 让用户能把原因复制出来
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteDetailActivity, 10f), 0, 0)
        }
        fun act(label: String, primary: Boolean = false, run: () -> Unit) =
            Button(this).apply {
                text = label
                if (primary) tag = Brut.PRIMARY
                setOnClickListener { run() }
            }
        row.addView(act("重新试", true) { recreate() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(act("返回") { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteDetailActivity, 8f)
        })
        root.addView(row)
        Brut.style(root)
    }

    /**
     * 库开着，但这一次读这一条失败了（游标炸了 / 行读不出来 / 瞬时 IO 错）。
     *
     * 和「查不到这一行」必须分开：那条路会说「笔记已经不在了」，用户会以为数据没了，
     * 于是去回收站翻、或者干脆重新写一遍 —— 而真实情况可能只是这次没读出来。
     * 这里说清原因，给「重新试」（原地重画）和「返回」两个出口。
     */
    private fun readFailed(e: Exception) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "这条笔记这次没读出来"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, Ui.dp(this@NoteDetailActivity, 6f))
        })
        root.addView(Ui.text(this,
            "库是开着的，只是读这一条时出错了。它多半还在 —— 点「重新试」再读一次。",
            13f, Ui.TX3))
        root.addView(Ui.text(this, "原因：" + (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName),
            12.5f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NoteDetailActivity, 8f), 0, Ui.dp(this@NoteDetailActivity, 8f))
            setTextIsSelectable(true)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@NoteDetailActivity, 10f), 0, 0)
        }
        fun act(label: String, primary: Boolean = false, run: () -> Unit) =
            Button(this).apply {
                text = label
                if (primary) tag = Brut.PRIMARY
                setOnClickListener { run() }
            }
        row.addView(act("重新试", true) { render() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(act("返回") { finish() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = Ui.dp(this@NoteDetailActivity, 8f)
        })
        root.addView(row)
        Brut.style(root)
    }

    // ---------------- 正文里的链接 ----------------

    /**
     * 把正文里所有链接的位置变成可点的 span。
     *
     * 只改「这几个字符可点」，不动文字本身 —— 用户在正文里写的 `[[A|B]]` 依然是原文，
     * 不做渲染期的重写（那会让复制出来的内容和存下来的不一致）。
     * 代码块 / 行内代码 / `%%注释%%` 里的东西由 [NoteSyntax] 自己排除，这里不重复判断。
     */
    private fun linkify(text: String, selfUid: String, rawBody: String): CharSequence {
        if (text.isBlank()) return text
        val sp = SpannableString(text)
        // 正文被替换成占位文案时（空笔记），偏移对不上，干脆不加 span
        val plain = rawBody.ifBlank { "（空笔记）" }
        if (plain.length != sp.length) return sp

        runCatching {
            NoteSyntax.wikiLinks(plain).forEach { w ->
                if (w.start < 0 || w.end > sp.length || w.start >= w.end) return@forEach
                // 附件形态的 wiki 链接（图片/PDF）走附件，不是笔记跳转
                if (w.embed || w.isPdf) {
                    sp.setSpan(AttachSpan(w.target.ifBlank { w.raw }), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    return@forEach
                }
                sp.setSpan(WikiSpan(w.target, w.heading, selfUid), w.start, w.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            NoteSyntax.markdownLinks(plain).forEach { m ->
                if (m.start < 0 || m.end > sp.length || m.start >= m.end) return@forEach
                if (m.image) {
                    sp.setSpan(AttachSpan(m.dest), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sp.setSpan(DestSpan(m.dest), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sp
    }

    /** `[[目标#小节|别名]]` */
    private inner class WikiSpan(
        private val target: String,
        private val heading: String,
        private val selfUid: String,
    ) : ClickableSpan() {
        override fun onClick(widget: View) { openWiki(target, heading, selfUid) }
        override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
    }

    /** 标准 Markdown 链接：只放行 http(s)/tel/mailto，其余当作库内笔记名再试一次 */
    private inner class DestSpan(private val dest: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (Links.safe(dest)) { Links.open(this@NoteDetailActivity, dest); return }
            openWiki(dest, "", Notes.one(db, noteId)?.item?.uid.orEmpty())
        }
        override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
    }

    /** 附件链接 / 图片：点开在应用内看，绝不把 file:// 甩给外部 App */
    private inner class AttachSpan(private val target: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            val f = NoteLinks.attachment(NoteAttach.dir(this@NoteDetailActivity), target)
            if (f == null) { toast("这个附件不在了：" + target); return }
            showImage(f) ?: toast("不是图片，打不开：" + f.name)
        }
        override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
    }

    /**
     * 打开另一篇笔记，把 `[[A#小节]]` 的小节一起带过去。
     *
     * 只传 id 的话，目标篇打开后停在顶部 —— 链接明明写了小节，用户还得自己翻一遍。
     * 滚动位置只能由**接收方**算（要它自己的 TextView layout），所以走 extra。
     */
    private fun openNote(id: Long, heading: String) {
        try {
            val i = android.content.Intent(this, NoteDetailActivity::class.java).putExtra(EXTRA_ID, id)
            if (heading.isNotBlank()) i.putExtra(EXTRA_HEADING, heading)
            startActivity(i)
        } catch (e: Exception) { toast("打不开这一篇") }
    }

    /**
     * 点一条 wiki 链接。
     *
     * 三种结局都要有明确出口：
     *  - 唯一命中 → 跳过去（同篇且带小节时，就地滚到那个标题；别的篇带着小节一起跳）；
     *  - 有歧义 → 把候选摆出来让用户选，**不替用户挑一个**（选中的那篇也带小节）；
     *  - 找不到 → 明说没找到。空目标 + 空小节（`[[#]]`）才什么都不做。
     */
    private fun openWiki(target: String, heading: String, selfUid: String) {
        val key = target.trim()
        if (key.isEmpty()) {
            // 本篇的小节跳转
            if (heading.isNotBlank()) jumpToHeading(heading) else toast("这条链接没有目标")
            return
        }
        val corpus = docs
        if (corpus.isEmpty()) { toast("还读不到全库，稍后再点"); return }
        when (val r = NoteLinks.resolve(key, corpus)) {
            is NoteLinks.Resolution.Hit -> {
                val doc = r.doc
                if (doc.uid == selfUid && doc.uid.isNotEmpty()) {
                    if (heading.isNotBlank()) jumpToHeading(heading) else toast("就是这一篇")
                    return
                }
                val id = try { Notes.idOfUid(db, doc.uid) } catch (e: Exception) { 0L }
                if (id <= 0L) { toast("「" + doc.title.ifBlank { key } + "」不在本机库里"); return }
                openNote(id, heading)
            }
            is NoteLinks.Resolution.Ambiguous -> {
                val names = r.matches.map { it.title.ifBlank { it.uid }.take(60) }.toTypedArray()
                ThemeDialogs.builder(this)
                    .setTitle("「" + key + "」指向好几篇，选一篇")
                    .setItems(names) { _, i ->
                        val doc = r.matches[i]
                        val id = try { Notes.idOfUid(db, doc.uid) } catch (e: Exception) { 0L }
                        if (id <= 0L) { toast("这一篇不在本机库里"); return@setItems }
                        openNote(id, heading)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            is NoteLinks.Resolution.Missing -> toast("没找到「" + key + "」这一篇")
        }
    }

    /**
     * `[[#小节]]` / `[[A#小节]]`：滚到那个标题。找不到就说清楚，别默默不动。
     *
     * 偏移能直接用，是因为本页**不渲染 Markdown**：正文 TextView 里放的就是
     * `notes.body` 原文（只剩 span 上色，一个字都没被替换），所以
     * [NoteSyntax.headings] 在 body 上算出的 offset 与 TextView 的文本偏移一一对应。
     * 一旦以后有人把正文换成渲染后的文本，这里必须先做偏移映射，否则会滚到错误的位置 ——
     * 所以下面留了长度校验：文本对不上就宁可不动，也不按错的偏移乱滚。
     */
    private fun jumpToHeading(heading: String) {
        val body = try { Notes.one(db, noteId)?.item?.body.orEmpty() } catch (e: Exception) { "" }
        val off = try { NoteLinks.findHeading(body, heading) } catch (e: Exception) { -1 }
        if (off < 0) { toast("这篇里没有「" + heading.trimStart('#') + "」这个小节"); return }
        val tv = bodyView() ?: return
        // 空正文显示的是「（空笔记）」占位，偏移不再对应；上面 findHeading 已返回 -1，
        // 这里只兜「正文被别的逻辑改写过」的情况。
        if (tv.text.length != body.length) {
            toast("这篇的小节位置对不上，往上翻一下")
            return
        }
        scrollToOffset(tv, off)
    }

    /**
     * 等这一屏真正画出来之后再跳小节。
     *
     * 正文 TextView 的行位置只能从 layout 拿；onCreate 里刚 addView 完还没有 layout，
     * 此时滚会算到 0 —— 页面停在顶部，看起来就像「小节没生效」。
     * 也不能无限重投：页面已经关掉时布局永远不会成立，所以有次数上限。
     *
     * 一进来就清掉 [pendingHeading]：onResume 还会重画一次，
     * 不清的话用户从编辑器返回就会被弹回那个标题。
     */
    private fun jumpWhenLaidOut(heading: String) {
        pendingHeading = null
        headingRetries = 0
        val step = object : Runnable {
            override fun run() {
                val tv = bodyView()
                if (tv != null && tv.layout != null && sv.height > 0) {
                    jumpToHeading(heading)
                    return
                }
                if (headingRetries++ >= MAX_HEADING_TRIES) return
                sv.postDelayed(this, 50L)
            }
        }
        sv.post(step)
    }

    /** 正文那个 TextView：它永远是 root 的第一个 TextView 且带 TapOrSelect */
    private fun bodyView(): TextView? {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is TextView && v.movementMethod === TapOrSelect) return v
        }
        return null
    }

    /**
     * 把 [offset] 那一段滚到屏幕上方。
     *
     * 不直接 `scrollTo(y)`：TextView 的行位置只能从 [android.text.Layout] 拿，
     * 拿不到 layout（还没量过）就退化成按比例估算，至少不会什么都不做。
     */
    private fun scrollToOffset(tv: TextView, offset: Int) {
        val y: Int = tv.layout?.let { l ->
            val line = l.getLineForOffset(offset.coerceIn(0, l.text.length))
            // 正文顶部相对 root 的位置 + 该行的顶部 − 一点留白，让标题不那么贴边
            tv.top + l.getLineTop(line) - Ui.dp(this, 8f)
        } ?: (tv.top + (tv.height * offset.toFloat() / maxOf(1, tv.text.length)).toInt())
        sv.post { sv.smoothScrollTo(0, y.coerceAtLeast(0)) }
    }

    // ---------------- 附件 ----------------

    /**
     * 附件列表。
     *
     * 只有「图」这里给缩略图，其余给文件名 —— 一份 PDF/zip 在详情页里没有可看的缩略图，
     * 假装有一个只会让点击落空。
     */
    private fun renderAttachments() {
        val list = try { Notes.attachmentsOf(db, noteId) } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) return
        root.addView(Ui.text(this, "附件 " + list.size + " 个", 12f, Ui.TX3).apply {
            setPadding(0, Ui.dp(this@NoteDetailActivity, 14f), 0, Ui.dp(this@NoteDetailActivity, 4f))
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (a in list) {
            val f = try { NoteLinks.attachment(NoteAttach.dir(this), a.path) } catch (e: Exception) { null }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@NoteDetailActivity, 4f), 0, Ui.dp(this@NoteDetailActivity, 4f))
                isClickable = true
                setOnClickListener {
                    if (f == null) toast("这个附件不在磁盘上了") else if (showImage(f) == null) toast("不是图片：" + f.name)
                }
            }
            val thumbRel = a.thumb
            if (thumbRel.isNotBlank()) {
                val tf = try { NoteLinks.attachment(NoteAttach.dir(this), thumbRel) } catch (e: Exception) { null }
                if (tf != null && tf.exists()) {
                    val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    runCatching { BitmapFactory.decodeFile(tf.absolutePath) }.getOrNull()?.let { iv.setImageBitmap(it) }
                    row.addView(iv, LinearLayout.LayoutParams(
                        Ui.dp(this@NoteDetailActivity, 40f), Ui.dp(this@NoteDetailActivity, 40f)).apply {
                        marginEnd = Ui.dp(this@NoteDetailActivity, 8f)
                    })
                }
            }
            row.addView(Ui.text(this, f?.name ?: a.path, 13f, Ui.TX2), LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
        root.addView(box)
    }

    /** 在应用内看一张图。不是图（解不出来）返回 null，交给调用方说清楚 */
    private fun showImage(f: File): Unit? {
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val overlay = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { (parent as? android.view.ViewGroup)?.removeView(this) }
        }
        (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.addView(
            overlay, android.view.ViewGroup.LayoutParams(-1, -1)
        )
        return Unit
    }

    // ---------------- 反向链接 ----------------

    /**
     * 谁引用了这一篇。
     *
     * 要扫全库，所以先拿库里的文档清单（后台线程），再在同一个后台线程里算。
     * 结果为零就**整块不出现** —— 一个写着「没有反向链接」的空标题只是占地方。
     * 读不出来时给一句可见的说明，而不是让这块悄悄消失（那和「没有」分不清）。
     */
    private fun renderBacklinks(full: Notes.Full, gen: Int, host: LinearLayout) {
        val uid = full.item.uid
        if (uid.isBlank()) return
        val reload = !docsTried
        docsTried = true
        if (docs.isNotEmpty()) {
            paintBacklinks(NoteLinks.backlinks(docs, uid), gen, host)
            return
        }
        if (!reload) return
        Thread {
            val got = try {
                NoteVault.docs(this, db).map { NoteLinks.Doc(it.uid, it.title, it.body, it.path, it.aliases) }
            } catch (e: Exception) { emptyList() }
            val back = try { if (got.isEmpty()) emptyList() else NoteLinks.backlinks(got, uid) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || gen != generation) return@runOnUiThread
                if (got.isNotEmpty()) docs = got
                if (got.isEmpty()) {
                    host.addView(Ui.text(this, "反向链接读不出来（索引可能还没重建）", 12f, Ui.TX3).apply {
                        setPadding(0, Ui.dp(this@NoteDetailActivity, 12f), 0, 0)
                    })
                    Brut.style(host)
                    return@runOnUiThread
                }
                paintBacklinks(back, gen, host)
                Brut.style(host)
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * 反向链接列表。可点开、也可收起 —— 一条被引用很多的笔记，这块能占掉半屏。
     *
     * 只显示标题和一个「展开」入口，不做成默认展开的长列表。
     */
    private fun paintBacklinks(back: List<NoteLinks.Doc>, gen: Int, host: LinearLayout) {
        if (back.isEmpty()) return
        if (isFinishing || isDestroyed || gen != generation || host.parent == null) return
        val head = TextView(this).apply {
            text = "被 " + back.size + " 篇引用 ▾"
            textSize = 12.5f
            setTextColor(Ui.TX3)
            isClickable = true
            setPadding(0, Ui.dp(this@NoteDetailActivity, 14f), 0, Ui.dp(this@NoteDetailActivity, 4f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        for (d in back) {
            box.addView(Ui.text(this, "· " + d.title.ifBlank { d.uid }, 13f, Ui.TX2).apply {
                isClickable = true
                setPadding(0, Ui.dp(this@NoteDetailActivity, 6f), 0, Ui.dp(this@NoteDetailActivity, 6f))
                setOnClickListener {
                    val id = try { Notes.idOfUid(db, d.uid) } catch (e: Exception) { 0L }
                    if (id <= 0L) { toast("这一篇不在本机库里"); return@setOnClickListener }
                    try {
                        startActivity(android.content.Intent(this@NoteDetailActivity, NoteDetailActivity::class.java)
                            .putExtra(EXTRA_ID, id))
                    } catch (e: Exception) { toast("打不开这一篇") }
                }
            })
        }
        head.setOnClickListener {
            val open = box.visibility != View.VISIBLE
            box.visibility = if (open) View.VISIBLE else View.GONE
            head.text = "被 " + back.size + " 篇引用 " + (if (open) "▴" else "▾")
        }
        host.addView(head)
        host.addView(box)
    }

    // ---------------- 删除 ----------------

    /**
     * 删除要二次确认。
     *
     * 删的是软删除（进回收站 30 天），不是立刻抹掉，但用户不知道这件事 ——
     * 一个没有确认的删除按钮在详情页上太容易误触。
     */
    private fun confirmDelete() {
        ThemeDialogs.builder(this)
            .setMessage("删掉这条笔记？\n\n会先进回收站，30 天内还能捞回来。")
            .setPositiveButton("删掉") { _, _ ->
                val n = try { Notes.softDelete(db, noteId, System.currentTimeMillis()) } catch (e: Exception) { -1 }
                when {
                    n < 0 -> toast("没删成：" + "读不到库")
                    n == 0 -> toast("这条笔记已经不在了")
                    else -> { toast("已移进回收站"); finish() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    /**
     * 点击图片 → 在应用内放大看原图。
     *
     * **不再走外部 App**：原来是 Uri.fromFile + ACTION_VIEW，
     * 在 targetSdk 35 上必定抛 FileUriExposedException，
     * 而 catch 里写的是「没有能看图的 App」—— 把应用自己的错报成系统的错，
     * 真机日志证实了：FileUriExposedException / file:///... exposed beyond app。
     *
     * 改成应用内显示，顺带解决另一个问题：原来拿到的是 320px 缩略图，
     * 「看原图」看到的其实是糊的。
     */
    private fun openFull(uid: String) {
        val f = NoteAttach.fullFile(this, uid) ?: return
        if (showImage(f) == null) {
            android.widget.Toast.makeText(this, "这张图读不出来", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun fmtFull(at: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return (c.get(java.util.Calendar.MONTH) + 1).toString() + " 月 " +
            c.get(java.util.Calendar.DAY_OF_MONTH) + " 日 " +
            String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    companion object {
        /** 打开哪一条：和 NotesActivity 跳过来时用的键一致 */
        const val EXTRA_ID = "note_id"

        /**
         * 进页面后要滚到的小节名（`[[A#小节]]` 跳过来时带）。
         *
         * 不传的话目标篇停在顶部 —— 链接明明写了小节，用户还得自己翻。
         * 只带小节名、不带偏移：偏移必须在接收方自己的正文上重算。
         */
        const val EXTRA_HEADING = "note_heading"

        /** 等正文 layout 的重投上限（50ms 一次 ≈ 1.5s）。页面关掉时布局永远不会成立 */
        private const val MAX_HEADING_TRIES = 30

        /**
         * 正文的触摸处理：点在链接上就点链接，别的地方交给默认实现（长按选字照旧）。
         *
         * 不用系统的 [android.text.method.LinkMovementMethod]：它会把整块 TextView 的触摸都
         * 吃掉并自己滚动，外面这层 ScrollView 就再也划不动了。
         */
        val TapOrSelect: android.text.method.MovementMethod = object : ArrowKeyMovementMethod() {
            override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                val a = event.actionMasked
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) {
                    val span = spanAt(widget, buffer, event)
                    if (span != null && !widget.hasSelection()) {
                        if (a == MotionEvent.ACTION_UP) span.onClick(widget)
                        return true
                    }
                }
                return super.onTouchEvent(widget, buffer, event)
            }

            /** 手指底下有没有可点的 span */
            private fun spanAt(w: TextView, buf: Spannable, e: MotionEvent): ClickableSpan? {
                val layout = w.layout ?: return null
                val x = e.x.toInt() - w.totalPaddingLeft + w.scrollX
                val y = e.y.toInt() - w.totalPaddingTop + w.scrollY
                if (y < 0 || y > layout.height) return null
                val line = layout.getLineForVertical(y)
                if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
                val off = layout.getOffsetForHorizontal(line, x.toFloat())
                return buf.getSpans(off, off, ClickableSpan::class.java).firstOrNull()
            }
        }
    }
}
