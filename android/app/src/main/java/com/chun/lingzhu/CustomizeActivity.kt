package com.chun.lingzhu

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 自定义中心（v1.69 自定义体系的界面层）。
 *
 * 三层，全在这个 Activity 里（原生 View，项目不用 Compose）：
 *  1. **汇总页**（层1）：按 [Customize.grouped] 的分组列清单，每组写「改了几项 / 共几项」，底部是全部恢复默认 + 导入导出。
 *  2. **二级清单页**：展开全部自定义项（分组小标题 + 各项当前值），点一项进编辑页。
 *  3. **编辑页**：按 [Customize.Spec.kind] 给对应的编辑器（文本框 / 滑杆 / 单选 / 开关 / 取色），底部「恢复这一项」。
 *
 * 所有读写都走 [Customize]（归一化、长度防呆、删键即恢复默认），这里**不自己写任何一条截断 / 夹区间的规则** ——
 * 界面只负责「显示当前值」和「把用户输入原样交给 Customize.set」，改坏了不会脏。
 *
 * 入口有三处（都能到这）：设置页「外观 → 自定义中心」、小球菜单「更多调节 → 自定义小珠…」（带 [EXTRA_GROUP] 直达小球那组）、
 * 以及各处长按（[Customize.Spec.longPress] 里写着从哪儿长按进来）。
 */
class CustomizeActivity : Activity() {

    companion object {
        /** 直达某个分组（小球菜单的「自定义小珠…」用，省得再点一次）：[Customize] 里的 G_* 常量 */
        const val EXTRA_GROUP = "group"

        fun intent(ctx: Context, group: String? = null): Intent =
            Intent(ctx, CustomizeActivity::class.java).apply { if (group != null) putExtra(EXTRA_GROUP, group) }
    }

    private lateinit var prefs: Prefs
    private lateinit var store: CustomizeStore
    private lateinit var body: LinearLayout          // 真正放内容的盒子（换页时清空重建）
    private var curGroup: String? = null              // 非 null = 正在看某一组

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun cs(): Customize.Store = store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = CustomizeStore(prefs)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        Brut.page(this, root)

        // ---- 顶部：返回 + 标题 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(8f), dp(14f), dp(8f))
        }
        val back = TextView(this@CustomizeActivity).apply {
            text = "‹ 返回"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10f), dp(6f), dp(14f), dp(8f)); minHeight = 0; minimumHeight = 0
            tag = Brut.KEEP
            contentDescription = "返回上一层"
            setOnClickListener { onBack() }
        }
        back.background = Brut.pressable(this, Brut.MINT, 3f)
        top.addView(back)
        val title = TextView(this@CustomizeActivity).apply {
            text = Preset.appName; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14f), 0, 0, 0)
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14f); setPadding(p, dp(6f), p, dp(24f))
        }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // 小球菜单点「自定义小珠…」时直接落在小球那组；设置页进来先看汇总页
        curGroup = intent.getStringExtra(EXTRA_GROUP)
        render()
    }

    override fun onBackPressed() = onBack()

    /** 二级 → 一级 → 退出。已经在最外层就按系统默认行为退Activity */
    private fun onBack() {
        if (curGroup != null) { curGroup = null; render(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ================= 渲染：汇总页 / 二级清单页 =================

    private fun render() {
        body.removeAllViews()
        if (curGroup == null) renderHub() else renderList(curGroup!!)
        Brut.style(body)
    }

    /**
     * 汇总页（层1）：每组一卡，写清「共几项、其中改了几项」——
     * 一眼能看出自己动过哪些，而不用逐项点进去比对默认值。
     */
    private fun renderHub() {
        val groups = Customize.grouped()
        val changed = groups.sumOf { (_, specs) -> specs.count { !it.isDefault(store.get(it.id)) } }

        head("自定义中心", " Ling 的每一项都有默认值（开箱即用），这里都能改。改坏了每一项都能单独恢复默认。")
        card {
            addView(TextView(this@CustomizeActivity).apply {
                text = if (changed == 0) "共 ${Customize.CATALOG.size} 项，全是默认值 —— 现在这样就挺好。"
                else "共 ${Customize.CATALOG.size} 项，你改过 $changed 项。"
                textSize = 14f
            })
            addView(btn("查看全部自定义项 ›") { curGroup = FIRST_GROUP_MARK; render() })
        }

        for ((group, specs) in groups) {
            val n = specs.count { !it.isDefault(store.get(it.id)) }
            card {
                addView(TextView(this@CustomizeActivity).apply {
                    text = group + (if (n > 0) "  ·  改过 $n / ${specs.size} 项" else "  ·  ${specs.size} 项，默认")
                    textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                })
                for (s in specs) {
                    val row = rowBtn(s.label + (if (s.isDefault(store.get(s.id))) "" else "  ·"), s.display(store.get(s.id))) {
                        editPage(s)
                    }
                    addView(row)
                }
            }
        }

        head("恢复默认", " 全部恢复成默认值（每一项单独恢复在它自己的编辑页底部）。")
        card {
            addView(btn("全部恢复默认", danger = true) {
                confirm("全部恢复默认？", "把 ${Customize.CATALOG.size} 项自定义全部清掉，回到 Ling 出厂的样子。面板、小球、Agent 都会立刻变。这步没法撤销。") {
                    Customize.restoreAll(cs()); toast("已全部恢复默认"); render()
                }
            })
        }

        head("导入 / 导出", " 导出的是一段文本（复制给别人）；对方粘贴导入即可。只带走「和默认值不同」的项，所以两边默认值不一样也不会互相污染。")
        card {
            addView(btn("导出我的自定义") {
                val text = try {
                    Customize.export(cs())
                } catch (e: IllegalStateException) {
                    toast(e.message ?: "配置太大，导出失败"); null
                }
                if (text != null) { copy(text); toast("已复制到剪贴板，发给对方就行"); showText("导出结果（长按可全选）", text) }
            })
            addView(btn("从剪贴板导入") {
                val text = paste()
                if (text.isNullOrBlank()) { toast("剪贴板是空的"); return@btn }
                importFlow(text)
            })
            addView(btn("清空剪贴板里的 Ling 配置") { copy(""); toast("剪贴板已清空") })
        }
    }

    /**
     * 二级清单页：展开**全部**自定义项（跨分组，带小标题），点一项进编辑页。
     * [group] 非 null 时只看那一组（小球菜单「自定义小珠…」进来时就是它，省得在一堆项里找）。
     */
    private fun renderList(group: String?) {
        val groups = if (group == null || group == FIRST_GROUP_MARK) Customize.grouped()
        else listOf(group to Customize.CATALOG.filter { it.group == group })

        head(if (group == null || group == FIRST_GROUP_MARK) "全部自定义项" else group,
            " 点一项进去改。长按改也行 —— 每个编辑页都写着能从哪儿长按进来。")

        if (groups.isEmpty()) {
            card { addView(TextView(this@CustomizeActivity).apply { text = "没有这一组（分组名变了？）。"; textSize = 14f }) }
            return
        }

        for ((g, specs) in groups) {
            if (group == null || group == FIRST_GROUP_MARK) {
                card {
                    addView(TextView(this@CustomizeActivity).apply { text = g; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4f), 0, dp(2f)) })
                    for (s in specs) addView(specRow(s))
                }
            } else {
                card { for (s in specs) addView(specRow(s)) }
            }
        }

        card {
            addView(btn("‹ 回到汇总页") { curGroup = null; render() })
            addView(btn("全部恢复默认", danger = true) {
                confirm("全部恢复默认？", "把 ${Customize.CATALOG.size} 项自定义全部清掉，回到 Ling 出厂的样子。") {
                    Customize.restoreAll(cs()); toast("已全部恢复默认"); render()
                }
            })
        }
    }

    /** 清单里的一项：名字 + 当前值 + 「默认」标记（改过的项加粗当前值，不靠颜色区分） */
    private fun specRow(s: Customize.Spec): View {
        val isDef = s.isDefault(store.get(s.id))
        val name = TextView(this@CustomizeActivity).apply {
            text = s.label
            textSize = 14.5f
            typeface = if (isDef) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            setPadding(0, dp(8f), 0, 0)
        }
        val vtv = TextView(this@CustomizeActivity).apply {
            text = (if (isDef) "" else "✓ ") + s.display(store.get(s.id))
            textSize = 13.5f; alpha = if (isDef) 0.6f else 1f; gravity = Gravity.END
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            isClickable = true
            contentDescription = s.label + "，当前" + s.display(store.get(s.id)) + if (isDef) "，默认值，点按修改" else "，已改，点按修改"
            setOnClickListener { editPage(s) }
        }
        r.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(vtv)
        return r
    }

    // ================= 编辑页 =================

    private fun editPage(s: Customize.Spec) {
        body.removeAllViews()
        head(s.label, " " + s.group)
        card {
            addView(TextView(this@CustomizeActivity).apply { text = s.hint; textSize = 13f; alpha = 0.8f })
            val saved = TextView(this@CustomizeActivity).apply { textSize = 12.5f; setPadding(0, dp(6f), 0, 0) }

            fun commit(raw: String): Boolean {
                val over = Customize.overLimit(s, raw)
                val stored = Customize.set(s, cs(), raw)
                // set 返回的就是真正落盘的值：超限会被截，截了多少要告诉用户（不然他以为整段存进去了）
                saved.text = when {
                    over > 0 -> "已存（超长的 $over 字被截掉了，剩 ${stored.length} 字）"
                    stored == s.def -> "已恢复成默认值（这一项不再自定义）"
                    else -> "已存"
                }
                return true
            }

            when (s.kind) {
                Customize.Kind.TEXT, Customize.Kind.PROMPT -> {
                    val multi = s.kind == Customize.Kind.PROMPT || s.limit > Customize.MAX_TEXT_CHARS
                    val e = EditText(this@CustomizeActivity).apply {
                        setText(Customize.get(s, cs())); hint = "留空 = 默认（不填）"
                        if (multi) {
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            minLines = 4; maxLines = 12; gravity = Gravity.TOP or Gravity.START
                        } else setSingleLine()
                        // P2-4：失焦才 commit（2000 字的 PROMPT 逐键写 SharedPreferences 太重）；单行款回车即失焦
                        setOnFocusChangeListener { _, has -> if (!has) commit(text?.toString() ?: "") }
                    }
                    addView(e)
                    addView(TextView(this@CustomizeActivity).apply {
                        text = if (s.limit > 0) "上限 ${s.limit} 字${if (s.kind == Customize.Kind.PROMPT) "（超了自动截断，防 token 失控）" else ""}" else "不限长度"
                        textSize = 12f; alpha = 0.7f
                    })
                }

                Customize.Kind.NUMBER -> {
                    val label = TextView(this@CustomizeActivity).apply { textSize = 15f; setPadding(0, dp(8f), 0, 0) }
                    fun show() {
                        val v = Customize.get(s, cs()).toIntOrNull() ?: s.def.toIntOrNull() ?: 0
                        val unit = if (s.id.endsWith("Ms")) " ms" else if (s.id.endsWith("Sp")) " sp" else if (s.id.endsWith("Dp")) " dp" else if (s.id.endsWith("Pct")) "%" else ""
                        label.text = "${s.label}：$v$unit（${s.min}~${s.max}）"
                    }
                    val step = if (s.max - s.min > 200) 50 else if (s.max - s.min > 40) 5 else 1
                    addView(label)
                    addView(SeekBar(this@CustomizeActivity).apply {
                        max = (s.max - s.min) / step; progress = ((Customize.get(s, cs()).toIntOrNull() ?: 0) - s.min) / step
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                                if (!fromUser) return
                                val v = s.min + p * step
                                commit(v.toString()); show()
                                if (s.id.startsWith("orb.")) hintOrb()
                            }
                            override fun onStartTrackingTouch(sb: SeekBar?) {}
                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        })
                    })
                    show()
                    // 小球几项改完立刻看得见：菜单里也用同一份 Prefs，这里提示一句免得以为没生效
                    if (s.id.startsWith("orb.")) hintOrb()
                }

                Customize.Kind.ENUM -> {
                    val g = RadioGroup(this@CustomizeActivity).apply { orientation = RadioGroup.VERTICAL }
                    val cur = Customize.get(s, cs())
                    for (o in s.options) {
                        val rb = RadioButton(this@CustomizeActivity).apply {
                            text = s.optionLabel(o); tag = o; id = View.generateViewId()
                            if (o == cur) isChecked = true
                        }
                        g.addView(rb)
                    }
                    g.setOnCheckedChangeListener { grp, id -> (grp.findViewById<View>(id)?.tag as? String)?.let { commit(it) } }
                    addView(g)
                }

                Customize.Kind.BOOL -> addView(Switch(this@CustomizeActivity).apply {
                    text = s.label
                    isChecked = Customize.get(s, cs()) == "true"
                    setOnCheckedChangeListener { _, on -> commit(if (on) "true" else "false") }
                })

                Customize.Kind.COLOR -> {
                    val g = RadioGroup(this@CustomizeActivity).apply { orientation = RadioGroup.VERTICAL }
                    val cur = Customize.get(s, cs())
                    for ((name, rgb) in Theme.NAMED) {
                        val hex = Theme.hex(rgb)
                        val rb = RadioButton(this@CustomizeActivity).apply {
                            text = "$name  $hex"; tag = hex; id = View.generateViewId()
                            if (hex == cur) isChecked = true
                        }
                        g.addView(rb)
                    }
                    g.setOnCheckedChangeListener { grp, id -> (grp.findViewById<View>(id)?.tag as? String)?.let { commit(it) } }
                    addView(g)
                    addView(TextView(this@CustomizeActivity).apply { text = "也可以在编辑框里直接填 #RRGGBB"; textSize = 12f; alpha = 0.7f })
                }
            }

            addView(saved)
            addView(btn("恢复这一项的默认", danger = true) {
                Customize.restore(s, cs()); saved.text = "已恢复默认"; render()
            })
        }

        card {
            if (s.longPress.isNotBlank())
                addView(TextView(this@CustomizeActivity).apply { text = "也能从这里长按进：${s.longPress}"; textSize = 12.5f; alpha = 0.75f })
            addView(btn("‹ 返回清单") { render() })
            addView(btn("‹ 回到汇总页") { curGroup = null; render() })
        }

        // 自定义项改完，已经开着的小球/面板要立刻重画 —— 发广播让 OrbService 重读一次 Prefs
        refreshOrb()
        Brut.style(body)
    }

    /** 小球几项改完的提示：值已经落盘，小球下次重画就是新的 */
    private fun hintOrb() {
        body.addView(TextView(this@CustomizeActivity).apply {
            text = "小球立刻生效：按住它半秒就出菜单，关掉重开一次也行。"
            textSize = 12.5f; alpha = 0.75f; setPadding(0, dp(4f), 0, 0)
        })
    }

    private fun refreshOrb() {
        try { sendBroadcast(Intent(OrbService.ACTION_REFRESH).setPackage(packageName)) } catch (_: Exception) {}
    }

    // ================= 导入导出 =================

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Ling 自定义", text))
        } catch (e: Exception) { toast("写不进剪贴板") }
    }

    private fun paste(): String? = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Exception) { null }

    private fun importFlow(text: String) {
        val preview = Customize.MapStore()
        val r = try { Customize.import(text, preview) } catch (e: Exception) {
            toast("导入失败：${e.message ?: "配置读不懂"}"); return
        }
        val msg = buildString {
            append("对方改了 ${r.applied.size} 项")
            val names = r.applied.mapNotNull { Customize.byId(it)?.label }
            if (names.isNotEmpty()) append("：${names.joinToString("、")}")
            if (r.skipped.isNotEmpty()) append("\n跳过 ${r.skipped.size} 项：${r.skipped.joinToString("、")}（本机没这一项，或值非法）")
        }
        confirm("导入这份配置？", msg) {
            val real = Customize.import(text, cs())
            toast("已导入 ${real.applied.size} 项")
            render()
        }
    }

    private fun showText(title: String, text: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(14f); setPadding(p, dp(12f), p, dp(12f))
        }
        Brut.card(box)
        box.addView(TextView(this@CustomizeActivity).apply { setText(title); textSize = 15f; typeface = Typeface.DEFAULT_BOLD })
        box.addView(TextView(this@CustomizeActivity).apply {
            setText(text); textSize = 12f; setTextIsSelectable(true)
            setPadding(0, dp(6f), 0, 0)
        })
        Brut.style(box)
        body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
    }

    // ================= 小工具 =================

    /** 二级清单页的标记（用 group == null 表示「全部」，这里用一个不可能撞上分组名的哨兵值） */
    private val FIRST_GROUP_MARK = "__all__"

    private fun head(title: String, sub: String) {
        body.addView(TextView(this@CustomizeActivity).apply {
            text = title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2f), dp(10f), 0, 0)
        })
        if (sub.isNotBlank()) body.addView(TextView(this@CustomizeActivity).apply { text = sub; textSize = 12.5f; alpha = 0.78f; setPadding(dp(2f), dp(2f), 0, 0) })
    }

    /** 一个白卡：内容自己往里加（[LinearLayout] 竖排） */
    private fun card(build: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }
        }
        Brut.card(c)
        c.build()
        body.addView(c)
        return c
    }

    private fun btn(label: String, danger: Boolean = false, act: () -> Unit): Button =
        Button(this).apply {
            text = label; tag = if (danger) Brut.DANGER else null; isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { act() }
        }

    /** 汇总页里「名字 + 当前值」的一行，点进编辑页 */
    private fun rowBtn(name: String, value: String, act: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
            isClickable = true
            contentDescription = name + "，当前" + value + "，点按修改"
            setOnClickListener { act() }
        }
        r.addView(TextView(this@CustomizeActivity).apply { text = name; textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(this@CustomizeActivity).apply { text = "$value ›"; textSize = 13f; alpha = 0.75f })
        return r
    }

    /** 破坏性操作一律先确认（「恢复默认」会真删掉用户写的人格 / 提示词） */
    private fun confirm(title: String, msg: String, onYes: () -> Unit) {
        ThemeDialogs.builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onYes() }
            .setNegativeButton("算了", null).show()
    }
}