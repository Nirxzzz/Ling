package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * 「图标 + 主标题 + 副标题」列表对话框 + 「从模板新建 Agent」（原来是 ChatActivity 的私有方法，
 * 会话列表的「＋」也要用同一套，所以原样挪出来共用 —— 行样式、文案、上限判定一个字没改）。
 */
object PersonaPicker {

    /** 行 = (iconKey, 主标题, 副标题?)；`onPick` 收到行的下标 */
    fun iconListDialog(a: Activity, title: String, rows: List<Triple<String, String, String?>>, onPick: (Int) -> Unit): AlertDialog {
        fun dp(v: Float) = Ui.dp(a, v)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        val dlg = ThemeDialogs.builder(a)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(col) })
            .setNegativeButton("取消", null)
            .create()
        rows.forEachIndexed { i, (key, main, sub) ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
                isClickable = true
                setOnClickListener { dlg.dismiss(); onPick(i) }
            }
            row.addView(PersonaIcon(a, key))
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(a, main, 15f), LinearLayout.LayoutParams(-1, -2))
            if (!sub.isNullOrBlank()) {
                texts.addView(Ui.text(a, sub, 12.5f, Ui.TX2).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
            col.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return dlg
    }

    /**
     * 新建：先选模板，选完建好，把新 id 交给 [onCreated]。满了 / 提示词总量超了照原文案提示、不回调。
     */
    fun newFromTemplate(a: Activity, personas: Personas.Store, onCreated: (String) -> Unit) {
        val list = personas.list()
        if (!Personas.canAdd(list)) { toast(a, "最多 ${Personas.MAX} 个 Agent"); return }
        val t = Personas.TEMPLATES
        val rows = t.map { Triple(it.iconKey, it.label, it.role) }
        iconListDialog(a, "新 Agent 从哪个模板起", rows) { i ->
            val before = personas.list().size
            personas.fromTemplate(t[i].id)
            val after = personas.list()
            if (after.size <= before) { toast(a, "建不了 —— 提示词已经写满 ${Personas.PROMPT_TOTAL} 字"); return@iconListDialog }
            val pid = after.last().id
            // v2.3.3：建完顺口问一句要不要配工具。默认全开，所以「以后再说」什么都不丢。
            AgentScopeDialog.offerAfterCreate(a, pid, personas) { onCreated(it) }
        }.show()
    }

    private fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()
}
