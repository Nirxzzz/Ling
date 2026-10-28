package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录页（v1.58）：列表（搜索 / 清空）→ 点一条看全文（公式照样排版，可选字、可复制、可删）。
 * 同一个页面切两种内容，返回键从详情回到列表。皮肤同设置页（Brut）。
 */
class HistoryActivity : Activity() {
    companion object { private const val REQ_EXPORT = 11 }
    private lateinit var store: History.Store
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private var query = ""
    private var onlyStar = false   // v1.64：只看收藏（错题本）
    private var detail: History.Entry? = null
    private var listScrollY = 0
    private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))   // 建视图前先装好当前配色（Brut 的颜色是 getter）
        title = Preset.appName + " · 历史记录"
        store = History.Store(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = dp(18f); setPadding(p, dp(12f), p, dp(32f)) }
        scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        Brut.page(this, scroll)
        showList()
    }

    private fun kindText(e: History.Entry) = if (e.kind == History.KIND_TRANSLATE) "翻译" else "识别"
    private fun meta(e: History.Entry) = (if (e.star) "★ " else "") + fmt.format(Date(e.id)) + " · " + kindText(e) + (if (e.label.isNotBlank()) " · " + e.label else "")

    private fun banner(title: String, sub: String) {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // KEEP：底色是主色块，字按主色选可读色（默认配色 = 墨色，零变化）
        b.addView(TextView(this).apply { text = title; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.on(Brut.MINT)); tag = Brut.KEEP })
        b.addView(TextView(this).apply { text = sub; textSize = 13.5f; setTextColor(Brut.on(Brut.MINT)); setPadding(0, dp(2f), 0, 0); tag = Brut.KEEP })
        col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12f) })
        Brut.card(b, Brut.MINT)
    }

    private fun showList() {
        detail = null
        col.removeAllViews()
        val all = store.list()
        banner("历史记录", if (all.isEmpty()) "还没有记录" else "共 ${all.size} 条 · 最新的在最上面 · 最多留 ${History.MAX} 条")
        val search = EditText(this).apply {
            hint = "搜索：题目、答案、原文里的字"; setSingleLine(); setText(query)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; listBox.removeCallbacks(refill); listBox.postDelayed(refill, 200) }   // 打字停 0.2 秒再刷，别每个字重建 200 张卡片
            })
        }
        col.addView(search)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply { text = "← 设置"; setOnClickListener { finish() } })
        tools.addView(Button(this).apply {
            text = if (onlyStar) "★ 只看收藏" else "☆ 只看收藏"; tag = if (onlyStar) Brut.PRIMARY else null
            contentDescription = if (onlyStar) "只看收藏：开" else "只看收藏：关"
            setOnClickListener { onlyStar = !onlyStar; showList() }
        })
        tools.addView(Button(this).apply { text = "导出…"; isEnabled = all.isNotEmpty(); setOnClickListener { export() } })
        col.addView(tools)
        // v1.64：一行放四个按钮太挤（「清空全部」被压成竖条）→ 单独一行
        val tools2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
        tools2.addView(Button(this).apply {
            text = "清空全部"; tag = Brut.DANGER; isEnabled = all.isNotEmpty()
            setOnClickListener {
                val n = store.list().size; val stars = store.list().count { it.star }
                val d = ThemeDialogs.builder(this@HistoryActivity).setNegativeButton("取消", null)
                // v1.64：有收藏时默认只清没收藏的 —— 错题本别被一键清掉
                if (stars > 0) d.setMessage("共 $n 条，其中 $stars 条收藏。清空后找不回来。")
                    .setPositiveButton("清空没收藏的") { _, _ -> store.keepStarred(); showList() }
                    .setNeutralButton("连收藏一起清") { _, _ -> store.clear(); showList() }
                else d.setMessage("清空全部 $n 条历史记录？清空后找不回来。").setPositiveButton("清空") { _, _ -> store.clear(); showList() }
                d.show()
            }
        })
        col.addView(tools2)
        if (!Prefs(this).saveHistory) col.addView(TextView(this).apply { text = "⚠ 现在没在保存历史：设置页「历史记录」里打开「保存历史记录」。"; textSize = 13f; setPadding(0, dp(6f), 0, 0) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        Brut.style(col)
        fillList(listBox)
        scroll.post { scroll.scrollTo(0, listScrollY) }
    }

    private lateinit var listBox: LinearLayout
    private val refill = Runnable { fillList(listBox) }

    private fun fillList(box: LinearLayout) {
        box.removeAllViews()
        val items = History.filter(store.list(), query).let { l -> if (onlyStar) l.filter { it.star } else l }
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = when {
                    onlyStar && query.isBlank() -> "还没有收藏。答案面板上点「☆」，或在这里打开一条点「☆ 收藏」—— 收藏的不会被新记录挤掉。"
                    query.isBlank() -> "识别过的题、翻译过的字都会出现在这里。"
                    else -> "没有包含「$query」的" + (if (onlyStar) "收藏。" else "记录。")
                }
                textSize = 14f; setPadding(0, dp(16f), 0, 0)
            })
        }
        for (e in items) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
            card.addView(TextView(this).apply { text = meta(e); textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply { text = History.preview(e); textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4f), 0, 0) })
            card.addView(TextView(this).apply { text = "看全文 ›"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; paint.isUnderlineText = true; setPadding(0, dp(6f), 0, 0) })
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
            card.background = Brut.pressable(this, Brut.CARD)
            card.setPadding(dp(14f), dp(12f), dp(18f), dp(16f))
            card.setOnClickListener { listScrollY = scroll.scrollY; showDetail(e) }
        }
        Brut.style(box)
    }

    private fun showDetail(e: History.Entry) {
        detail = e
        col.removeAllViews()
        banner(kindText(e), meta(e))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply { text = "← 返回列表"; setOnClickListener { showList() } })
        tools.addView(Button(this).apply { text = "复制全文"; tag = Brut.PRIMARY; setOnClickListener { copy(e) } })
        tools.addView(Button(this).apply {
            text = if (e.star) "★ 已收藏" else "☆ 收藏"
            setOnClickListener { store.setStar(e.id, !e.star); store.get(e.id)?.let { showDetail(it) } }
        })
        col.addView(tools)
        fun block(title: String, body: CharSequence) {
            col.addView(Brut.heading(this, title))
            val t = TextView(this).apply {
                text = body; textSize = 16f; setLineSpacing(0f, 1.4f); setTextIsSelectable(true); movementMethod = Links.SelectableTap
            }
            col.addView(t, LinearLayout.LayoutParams(-1, -2))
            Brut.card(t)
        }
        val md = { s: String -> Md.render(s, Brut.INK, Theme.argb(0x22, Brut.INK)) }   // v1.85 配色统一：代码块底也走 Theme.argb
        if (e.kind == History.KIND_TRANSLATE) { block("原文", e.src); block("译文", md(e.text)) }
        else {
            // v1.66：关键词并进所有模式 —— 清单行「[可点击名词] a｜b」在历史里换成一行好读的「关键词：a · b」
            val (rest, terms) = Terms.parse(e.text)
            block("内容", md(if (terms.isEmpty()) e.text else rest + "\n\n**关键词：**" + terms.joinToString(" · ")))
        }
        col.addView(Button(this).apply {
            text = "删除这条"; tag = Brut.DANGER
            setOnClickListener {
                ThemeDialogs.builder(this@HistoryActivity).setMessage("删除这条记录？")
                    .setPositiveButton("删除") { _, _ -> store.delete(e.id); showList() }
                    .setNegativeButton("取消", null).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) })
        Brut.style(col)
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** 导出（v1.64）：系统「另存为」选位置（不用存储权限），写 Markdown。开着「只看收藏」就只导收藏 */
    private fun export() {
        val name = (if (onlyStar) "Ling-收藏-" else "Ling-历史-") + SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".md"
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("text/markdown").putExtra(android.content.Intent.EXTRA_TITLE, name)
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_EXPORT) } catch (e: Exception) { Toast.makeText(this, "打不开「另存为」：" + (e.message ?: ""), Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || uri == null) return
        val list = History.filter(store.list(), query).let { l -> if (onlyStar) l.filter { it.star } else l }
        val md = History.toMarkdown(list, (if (onlyStar) "Ling 收藏" else "Ling 历史记录") + "（" + list.size + " 条，导出于 " + fmt.format(Date()) + "）") { fmt.format(Date(it)) }
        val ok = try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(md.toByteArray(Charsets.UTF_8)) } != null } catch (_: Exception) { false }
        Toast.makeText(this, if (ok) "已导出 ${list.size} 条" else "导出失败：写不进这个位置", Toast.LENGTH_LONG).show()
    }

    private fun copy(e: History.Entry) {
        val s = if (e.kind == History.KIND_TRANSLATE) e.src + "\n\n" + e.text else e.text
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ling 历史", s))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (detail != null) showList() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
