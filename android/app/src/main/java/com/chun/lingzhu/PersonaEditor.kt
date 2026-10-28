package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * v2.4「每个 Agent 一套完整人设」：一个 Agent 的名字 / 职责 / 提示词 / 开场白 / 模型 / 温度 / 工具。
 *
 * ## 双模式
 * 用户要的是「默认简单，高级模式全开」。所以这里分两屏：
 *  - **简单**：名字、职责、它先说什么。够普通人用，四个以内字段。
 *  - **高级**：再展开提示词、模型、温度、工具权限。
 * 不做成「一个长表单滚动到底」——那对普通用户就是劝退，对高级用户又要翻半天。
 *
 * ## 为什么每项都是可选
 * 空提示词 = 用全局那份；空模型 = 跟随接口；温度 -1 = 跟随全局。
 * 每一项都能「不表态」，这样老用户不动任何设置也完全不受影响。
 */
object PersonaEditor {

    fun open(a: Activity, pid: String, store: Personas.Store, onDone: (String) -> Unit) {
        val p = store.list().firstOrNull { it.id == pid }
        if (p == null) { Toast.makeText(a, "这个 Agent 已经不在了", Toast.LENGTH_SHORT).show(); return }
        fun dp(v: Float) = Ui.dp(a, v)

        // 照 MainActivity 里 field() 的老写法：EditText(activity) + setText + hint，
        // 多行用 setSingleLine(false) + minLines。项目里没有 Ui.input 这个封装，别自己造。
        fun field(hint: String, value: String, multi: Boolean = false): EditText =
            EditText(a).apply {
                setText(value)
                this.hint = hint
                if (multi) {
                    setSingleLine(false)
                    minLines = 3
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                } else setSingleLine()
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
            }

        val nameIn = field("名字（最多 " + Personas.NAME_CHARS + " 字）", p.name)
        val roleIn = field("一句话职责（最多 " + Personas.ROLE_CHARS + " 字）", p.role)
        val greetIn = field("它先说的一句话（可空）", p.greeting, multi = true)
        val promptIn = field("系统提示词（空 = 用默认那份）", p.prompt, multi = true)
        val modelIn = field("模型名（空 = 跟随 AI 接口）", p.model)
        val tempIn = field("温度 0.0–2.0（空 = 跟随全局）",
            if (p.usesDefaultTemperature) "" else p.temperatureValue?.toString() ?: "")

        val advanced = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        advanced.addView(Ui.text(a, "提示词", 12f, Ui.TX2).apply { setPadding(0, dp(12f), 0, dp(2f)) })
        advanced.addView(promptIn)
        advanced.addView(Ui.text(a, "模型", 12f, Ui.TX2).apply { setPadding(0, dp(12f), 0, dp(2f)) })
        advanced.addView(modelIn)
        advanced.addView(Ui.text(a, "温度", 12f, Ui.TX2).apply { setPadding(0, dp(12f), 0, dp(2f)) })
        advanced.addView(tempIn)

        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
        }
        col.addView(Ui.text(a, "名字", 12f, Ui.TX2))
        col.addView(nameIn)
        col.addView(Ui.text(a, "职责", 12f, Ui.TX2).apply { setPadding(0, dp(10f), 0, 0) })
        col.addView(roleIn)
        col.addView(Ui.text(a, "开场白", 12f, Ui.TX2).apply { setPadding(0, dp(10f), 0, 0) })
        col.addView(greetIn)
        col.addView(advanced)

        val dlg = ThemeDialogs.builder(a)
            .setTitle(if (pid == Personas.P0) "内置 Agent（可改外观，不能删）" else "编辑 Agent")
            .setView(ScrollView(a).apply { addView(col) })
            .setNeutralButton("高级", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dlg.show()

        // 「高级」按钮只做展开，不关闭对话框 —— 所以不能交给默认实现（默认会 dismiss）
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val show = advanced.visibility != android.view.View.VISIBLE
            advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).text = if (show) "收起高级" else "高级"
        }

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val tempRaw = tempIn.text.toString().trim()
            val temp = if (tempRaw.isEmpty()) TEMP_FOLLOW_GLOBAL
                       else ((tempRaw.toFloatOrNull() ?: 0.7f) * 10).toInt().coerceIn(0, 20)
            val updated = p.copy(
                name = nameIn.text.toString(),
                role = roleIn.text.toString(),
                greeting = greetIn.text.toString(),
                prompt = promptIn.text.toString(),
                model = modelIn.text.toString(),
                temperature = temp,
            )
            if (updated.name.isBlank()) {
                Toast.makeText(a, "名字不能空着", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.update(updated)
            Toast.makeText(a, "已保存", Toast.LENGTH_SHORT).show()
            dlg.dismiss()
            onDone(pid)
        }
    }
}
