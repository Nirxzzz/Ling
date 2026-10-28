package com.chun.lingzhu

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * v2.3.3「允许自定义 Agent」：勾选这个 Agent 能用哪些本机工具。
 *
 * 判断逻辑全在 AgentScope（纯 JVM、有单测），这里只做「把状态画成复选框，把勾选写回去」。
 *
 * 一个容易做错的地方：用户把最后一个勾也取消时，存的是 AgentScope.NONE 哨兵而不是空集合 ——
 * 否则「我全关了」读回来会变成「我没设置过」= 全开，用户会觉得设置根本没生效。
 */
object AgentScopeDialog {

    fun show(a: Activity, title: String, current: String, onSave: (String) -> Unit) {
        fun dp(v: Float) = Ui.dp(a, v)
        val disabled = Prefs(a).disabledFeatures
        // known 用**全量**工具名：勾选状态要从存的值里还原，隐藏行的既有勾选也得算出来，
        // 否则保存时会把「因为模块关着而没显示」的工具一起抹掉（等模块重新打开，权限就悄悄变了）。
        val known = AgentTools.names()
        val before = AgentScope.checked(current, known)
        // 只显示还能用的：模块被关掉，它的工具连执行都会被拒，列在这里只会让用户以为勾上就能用。
        val specs = AgentTools.registryFor(AgentScope.ALL, disabled)
        val shown = specs.map { it.name }.toSet()
        val boxes = LinkedHashMap<String, CheckBox>()

        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
        }
        col.addView(Ui.text(a,
            if (shown.size == known.size) "关掉的工具，这个 Agent 就不会去用。"
            else "关掉的工具，这个 Agent 就不会去用。有 " + (known.size - shown.size) +
                " 个工具属于已关闭的模块，暂不显示，原来的设置会保留。",
            12.5f, Ui.TX2).apply { setPadding(0, dp(4f), 0, dp(8f)) })

        // v2.4：按功能分组显示。工具只有 6 个时平铺还行，模块多了之后只会更多，
        // 用户看到「建知识卡 / 建提醒 / 查记忆…」一串光名字，根本不知道哪个属于哪块。
        // 归属查不到的工具（还没登记成功能包的）统一放在最后「其他」，不隐藏 —— 藏起来等于偷偷改权限。
        // （模块被关掉因而没显示的行是另一回事：它们连执行都会被拒，且保存时按原状态保留，见下。）
        val groupOrder = Features.sorted().filter { it.toolNames.isNotEmpty() }
        val grouped = LinkedHashMap<String, MutableList<ToolSpec>>()
        for (s in specs) {
            val owner = Features.ofTool(s.name)
            val key = owner?.title ?: "其他"
            grouped.getOrPut(key) { mutableListOf() }.add(s)
        }

        fun addRow(s: ToolSpec) {
            val cb = CheckBox(a).apply {
                text = s.label
                isChecked = s.name in before
                textSize = 14f
                setTextColor(Ui.TX1)
                contentDescription = s.label
                minimumHeight = dp(48f)
                setPadding(0, dp(4f), 0, dp(4f))
            }
            boxes[s.name] = cb
            col.addView(cb, LinearLayout.LayoutParams(-1, -2))
        }

        for (f in groupOrder) {
            val rows = grouped.remove(f.title) ?: continue
            col.addView(Ui.text(a, f.title, 12f, Ui.TX2).apply {
                setPadding(0, dp(10f), 0, dp(2f))
            })
            for (s in rows) addRow(s)
        }
        // 剩余未归属的（含「其他」）
        for ((name, rows) in grouped) {
            col.addView(Ui.text(a, name, 12f, Ui.TX2).apply {
                setPadding(0, dp(10f), 0, dp(2f))
            })
            for (s in rows) addRow(s)
        }

        val dlg = ThemeDialogs.builder(a)
            .setTitle(title)
            .setView(ScrollView(a).apply { addView(col) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val picked = boxes.entries.filter { it.value.isChecked }.map { it.key }.toSet()
            // 隐藏行按**原状态**并回去：没显示不等于用户关掉了它。
            val next = picked + before.filter { it !in shown }
            // 没改动就原样存回（含 NONE 哨兵与「没设置过」）。否则「全开」会被写成一份
            // 恰好列全的名单 —— 行为一样，但列表页的「全部 N 个工具」会变成「启用 N / N 个」。
            onSave(if (next == before) current else AgentScope.encode(next))
            dlg.dismiss()
        }
    }

    fun offerAfterCreate(a: Activity, pid: String, store: Personas.Store, onDone: (String) -> Unit) {
        ThemeDialogs.builder(a)
            .setTitle("配置工具权限？")
            .setMessage("这个 Agent 默认能用全部已开启模块的本机工具。现在要挑一挑吗？")
            .setNegativeButton("以后再说") { _, _ -> onDone(pid) }
            .setPositiveButton("现在挑") { _, _ ->
                val p = store.list().firstOrNull { it.id == pid }
                val nm = p?.name ?: "Agent"
                show(a, nm + " 能用哪些工具", p?.tools ?: "") { enc ->
                    if (p != null) store.update(p.copy(tools = enc))
                    val msg = if (AgentScope.isExplicitlyNone(enc)) "已关掉全部工具" else "已保存工具设置"
                    Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
                    onDone(pid)
                }
            }
            .show()
    }
}
