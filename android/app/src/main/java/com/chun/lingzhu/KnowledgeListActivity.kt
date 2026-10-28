package com.chun.lingzhu

import android.app.Activity
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v1.69 K2 知识卡列表页：浏览 / 搜索 / 删除。
 * 到期复习走 [ReviewActivity]；本页只管「库里有啥」。
 */
class KnowledgeListActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private var headerCount = 0
    private val store by lazy { KnowledgeCard.Store(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(root) }

        // v1.85.13（UX-7/U2）：内容加横向边距 + 上呼吸空间 —— 此前整页贴屏幕边，
        // 与主设置/历史页的卡片风差距大（UX 走查 UX-7）
        root.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@KnowledgeListActivity, 16f), Ui.dp(this@KnowledgeListActivity, 4f), Ui.dp(this@KnowledgeListActivity, 16f), Ui.dp(this@KnowledgeListActivity, 24f))
        }
        setContentView(sv)
        Brut.page(this, sv)

        root.addView(TextView(this).apply {
            textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, dp(10f), 0, dp(4f))
        }.also { summary = it })

        root.addView(Button(this).apply {
            text = "学习与复习计划"
            setOnClickListener { startActivity(Intent(this@KnowledgeListActivity, StudyActivity::class.java)) }
        })
        root.addView(Button(this).apply { text = "新建知识卡"; setOnClickListener { editor(null) } })

        search = EditText(this).apply {
            hint = "搜正面 / 背面"; setSingleLine()
            setPadding(0, dp(8f), 0, dp(4f))
        }
        root.addView(search)
        root.addView(Button(this).apply { text = "搜"; setOnClickListener { render() } })
        // [K5] v1.70：知识库检索没有开关，靠问法触发 —— 在这儿说一句，别让人以为存了卡就自动生效
        root.addView(TextView(this).apply {
            text = KnowledgeSearch.HINT; textSize = 12.5f; alpha = 0.7f; setPadding(0, dp(2f), 0, dp(4f))
        })
        headerCount = root.childCount
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()   // 从复习页回来刷新到期标记
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    /** 来源代码 → 中文显示名（库里存的是英文 code，界面给中文）。 */
    private fun sourceLabel(code: String?): String = when (KnowledgeCard.Source.of(code)) {
        KnowledgeCard.Source.ANSWER -> "问答"
        KnowledgeCard.Source.CHAT -> "对话"
        KnowledgeCard.Source.CLIP -> "剪藏"
        KnowledgeCard.Source.MANUAL -> "手动"
        KnowledgeCard.Source.IMPORT -> "导入"
    }

    private fun render() {
        summary.text = run {
            val now = System.currentTimeMillis()
            val all = store.list()
            val due = all.count { !it.fsrs.isNew && it.isDue(now) }
            "共 ${all.size} 张 · 未学 ${all.count { it.fsrs.isNew }} 张 · 到期 $due 张"
        }
        // 清掉动态列表，保留固定头部和搜索控件。
        while (root.childCount > headerCount) root.removeViewAt(root.childCount - 1)
        val q = search.text?.toString()?.trim().orEmpty()
        val all = store.list()
        val hits = if (q.isEmpty()) all else all.filter { it.front.contains(q) || it.back.contains(q) }
        root.addView(TextView(this).apply {
            text = "命中 ${hits.size} / ${all.size} 张"; textSize = 12f; setPadding(0, dp(2f), 0, dp(6f))
        })
        if (hits.isEmpty()) {
            val (t, next) = "没有匹配的知识卡" to "点击新建，或在设置 → 提醒与复习里导入"
            root.addView(TextView(this).apply {
                text = "$t —— $next"; textSize = 14f; setPadding(0, dp(12f), 0, 0)
            })
            return
        }
        for (c in hits) {
            val now = System.currentTimeMillis()
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, dp(2f)) }
            row.addView(TextView(this).apply {
                text = (if (c.isDue(now)) "● " else "") + c.front
                textSize = 14.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(TextView(this).apply {
                text = "牌组：${c.deck} · ${sourceLabel(c.source)} · ${if (c.fsrs.isNew) "未学" else if (c.isDue(now)) "已到期" else "未到期"}"
                textSize = 12f; alpha = 0.65f; setPadding(0, dp(1f), 0, 0)
            })
            row.addView(TextView(this).apply { text = c.back; textSize = 13f; alpha = 0.85f })
            // 出处（只读）：导入卡里是「文件名 · 原文摘录」—— 让用户自己能核对这张卡出自哪份资料
            if (c.origin.isNotBlank()) {
                row.addView(TextView(this).apply {
                    text = "出处：${c.origin}"
                    textSize = 12f; alpha = 0.6f; setPadding(0, dp(1f), 0, 0)
                    contentDescription = "来源出处：${c.origin}"
                })
            }
            row.addView(Button(this).apply { text = "编辑"; setOnClickListener { editor(c) } })
            row.addView(Button(this).apply {
                text = "删除"; tag = Brut.DANGER
                setOnClickListener {
                    ThemeDialogs.builder(this@KnowledgeListActivity).setTitle("删除这张卡？")
                        .setMessage(c.front).setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ -> store.remove(c.id); render() }.show()
                }
            })
            root.addView(row)
        }
        Brut.style(root)
    }

    private fun editor(card: KnowledgeCard.KCData?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(8f), dp(16f), dp(8f)) }
        fun field(h: String, value: String, single: Boolean = false) = EditText(this).apply {
            hint = h; setText(value); if (single) setSingleLine(); form.addView(this)
        }
        val deck = field("牌组", card?.deck ?: "默认", true)
        val front = field("正面 · 问题（必填）", card?.front ?: "")
        val back = field("背面 · 答案（必填）", card?.back ?: "")
        val dialog = ThemeDialogs.builder(this).setTitle(if (card == null) "新建知识卡" else "编辑知识卡 · 保留进度")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = deck.text.toString().trim().ifBlank { "默认" }
                val f = front.text.toString().trim(); val b = back.text.toString().trim()
                if (f.isBlank() || b.isBlank()) { front.error = "问题和答案都要填写"; return@setOnClickListener }
                if (store.list().any { it.id != card?.id && it.deck == d && it.front == f }) {
                    front.error = "该牌组已有相同问题"; return@setOnClickListener
                }
                val saved = if (card == null) store.add(f, b, KnowledgeCard.Source.MANUAL, System.currentTimeMillis(), d) > 0
                    else store.update(card.id, d, f, b)
                if (saved) { dialog.dismiss(); render() } else front.error = "未保存，卡库可能已满或卡片已删除"
            }
        }
        dialog.show()
    }
}
