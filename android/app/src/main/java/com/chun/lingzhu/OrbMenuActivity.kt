package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * v2.4：自定义长按小球的菜单 —— 哪些项显示、什么顺序。
 *
 * ## 为什么用「上移/下移」而不是拖动排序
 * 拖动更顺手，但这个页面在一个 ScrollView 里：手指按在行上往下拖时，到底算「排序」还是「滚动页面」
 * 没法只靠手势区分（要长按起拖，还要在拖到边缘时自动滚）。逐行两个小箭头不酷，但它每次都能成功，
 * 也不会误触把顺序拖乱。
 *
 * ## 常驻项为什么没有隐藏按钮
 * 「和小Ling聊聊」是长按菜单存在的理由；「设置」和「停止小球」要是能隐藏，用户可能把自己关在门外 ——
 * 菜单里再也找不到设置，小球又关不掉。这三个只显示「固定」标记。
 */
class OrbMenuActivity : Activity() {

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, OrbMenuActivity::class.java)
    }

    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    /** 正在原地改名的那一项（null = 没有在编辑）。放在这里而不是每行各存一份，是为了「同时只编辑一行」 */
    private var editing: String? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    /** 改动成功后的短提示：新一条替换上一条，连点不排队堆积。重建/进入页面不调用 */
    private var lastToast: Toast? = null
    private fun saved(msg: String) {
        if (isFinishing) return
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    /** 当前顺序（已清洗）。每次改都从它出发，避免读到一半的旧值 */
    private fun currentOrder(): MutableList<String> =
        OrbMenu.resolve(prefs.orbMenuOrder, prefs.orbMenuHidden).toMutableList()

    private fun save(order: MutableList<String>) {
        prefs.orbMenuOrder = order
        // 隐藏集合跟着走：不在新顺序里的可选项 = 用户刚隐藏的
        val hidden = OrbMenu.ALL.filter { OrbMenu.canHide(it.id) && it.id !in order }.map { it.id }.toSet()
        prefs.orbMenuHidden = hidden
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.applyTheme(prefs)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        Brut.page(this, root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(14f), dp(8f))
        }
        val back = TextView(this).apply {
            text = "‹ 返回"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(6f), dp(14f), dp(8f))
            minHeight = 0
            minimumHeight = 0
            tag = Brut.KEEP
            contentDescription = "返回设置"
            setOnClickListener { finish() }
        }
        back.background = Brut.pressable(this, Brut.MINT, 3f)
        back.setTextColor(Brut.on(Brut.MINT))
        top.addView(back)
        top.addView(Ui.text(this, "长按小球的菜单", 17f, Brut.INK).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
        root.addView(top)

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(4f), dp(14f), dp(28f))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        render()
    }

    private fun render() {
        body.removeAllViews()
        val order = currentOrder()
        val hidden = OrbMenu.ALL.filter { OrbMenu.canHide(it.id) && it.id !in order }

        body.addView(sectionTitle("菜单顺序"))
        body.addView(Ui.text(this, "用 ↑ ↓ 调顺序。下面「已隐藏」里的项点「显示」就能加回菜单。", 12.5f, Brut.HINT).apply {
            setPadding(0, 0, 0, dp(10f))
        })

        order.forEachIndexed { i, id -> body.addView(row(id, i, order.size)) }

        if (hidden.isNotEmpty()) {
            body.addView(sectionTitle("已隐藏"))
            hidden.forEach { body.addView(hiddenRow(it.id)) }
        }

        val reset = TextView(this).apply {
            text = "恢复默认顺序（并全部显示）"
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            tag = Brut.KEEP
            setOnClickListener {
                prefs.orbMenuOrder = emptyList()
                prefs.orbMenuHidden = emptySet()
                saved("已恢复默认")
                render()
            }
        }
        Brut.button(reset, Brut.CARD)
        body.addView(reset, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26f) })
    }

    /**
     * 开始原地改名。留空保存 = 恢复默认标题。
     *
     * 原来这里用 AlertDialog，但系统会把对话框往上推给键盘让位 —— 用户的手指还停在原来的「保存」
     * 位置上，点下去打的是键盘，看起来就像「点了没反应」。改成行内编辑：这一行自己变成输入框，
     * 存/取消就在旁边，位置永远不变。
     */
    private fun beginNick(id: String) {
        editing = id
        render()
    }

    /** 行内编辑态的那一行：输入框 + 存 + 取消 */
    private fun editRow(id: String): View {
        val item = OrbMenu.byId(id) ?: return View(this)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        Brut.card(line, Brut.CARD)
        line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }

        val input = android.widget.EditText(this).apply {
            setText(prefs.orbMenuNicks[id] ?: "")
            hint = item.label
            textSize = 14f
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(OrbMenu.NICK_CHARS))
        }
        line.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        fun commit() {
            val v = OrbMenu.normNick(input.text?.toString() ?: "")
            val prev = prefs.orbMenuNicks[id]?.trim().orEmpty()
            val m = prefs.orbMenuNicks.toMutableMap()
            if (v.isEmpty()) m.remove(id) else m[id] = v
            prefs.orbMenuNicks = m
            editing = null
            render()
            // 名字没变就别报「已改名」—— 点了存但什么都没动，不该说改成功
            if (v != prev) saved(if (v.isEmpty()) "已恢复默认名：" + item.label else "已改名：" + v)
        }
        line.addView(arrow("存", true, "保存名字") { commit() })
        line.addView(arrow("取消", true, "不改了") {
            editing = null
            render()
        })

        // 自动聚焦并把光标放到末尾：点「改名」之后直接就能打字，省一次点击
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        return line
    }

    private fun sectionTitle(s: String): TextView = Ui.text(this, s, 15f, Brut.INK).apply {
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8f), 0, dp(4f))
    }

    /** 一行：标题 + ↑ + ↓ + 隐藏 */
    private fun row(id: String, index: Int, total: Int): View {
        val item = OrbMenu.byId(id) ?: return View(this)
        // 这一行正在改名 → 就地换成输入框，整行不再显示别的按钮
        if (editing == id) return editRow(id)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        Brut.card(line, Brut.CARD)
        line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }

        // 显示的是**实际会出现在菜单上的字**（设了昵称就是昵称），不是注册表里的默认标题 ——
        // 否则用户改完名回到这页还看见旧名字，会以为没保存成功。
        val shown = OrbMenu.labelOf(id, prefs.orbMenuNicks)
        val renamed = shown != item.label
        line.addView(Ui.text(this, shown, 14.5f, Brut.INK), LinearLayout.LayoutParams(0, -2, 1f))

        if (item.pinned) {
            line.addView(Ui.text(this, "固定", 11.5f, Brut.HINT).apply { setPadding(dp(6f), 0, dp(6f), 0) })
        }
        // 改名对所有项都开放（包括常驻项）—— 常驻只是「不能隐藏」，叫什么是用户的自由
        line.addView(arrow(if (renamed) "改名*" else "改名", true, "给这一项起个名字") { beginNick(id) })
        if (item.pinned) return line

        // 上移：第一项（常驻的「聊天」）不能再往上，所以 index > 1 才可用
        val up = arrow("↑", index > 1, "上移") {
            val o = currentOrder()
            if (index in 1 until o.size) {
                java.util.Collections.swap(o, index, index - 1)
                save(o)
                render()
                saved("已上移：" + shown)
            }
        }
        // 下移：最后两项是固定的「设置」「停止小球」，插不到它们后面去
        val down = arrow("↓", index < total - 3, "下移") {
            val o = currentOrder()
            if (index in 0 until minOf(o.size - 1, total - 3)) {
                java.util.Collections.swap(o, index, index + 1)
                save(o)
                render()
                saved("已下移：" + shown)
            }
        }
        val hide = arrow("隐藏", true, "从菜单里隐藏") {
            val o = currentOrder()
            o.remove(id)
            save(o)
            render()
            saved("已隐藏：" + shown)
        }
        line.addView(up)
        line.addView(down)
        line.addView(hide)
        return line
    }

    /** 已隐藏项：只有「显示」一个按钮 */
    private fun hiddenRow(id: String): View {
        val item = OrbMenu.byId(id) ?: return View(this)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        Brut.card(line, Brut.CARD)
        line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }
        line.addView(Ui.text(this, item.label, 14.5f, Brut.HINT), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(arrow("显示", true, "加回菜单") {
            val o = currentOrder()
            val at = (o.size - 2).coerceAtLeast(1)
            o.add(at, id)
            save(o)
            render()
            saved("已显示：" + item.label)
        })
        return line
    }

    private fun arrow(label: String, enabled: Boolean, desc: String, act: () -> Unit): TextView {
        val t = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            minHeight = 0
            minimumHeight = 0
            tag = Brut.KEEP
            contentDescription = desc
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.3f
            setTextColor(Brut.INK)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
            setOnClickListener { if (enabled) act() }
        }
        t.background = Brut.pressable(this, Brut.CARD, 2f)
        return t
    }
}
