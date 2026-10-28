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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * v2.4 C 档：模块管理 —— 用户决定 App 里有哪些功能。
 *
 * 只做「显隐」，不做「卸载」：关掉的模块数据一条不删，重新打开还在。
 * 开关只影响入口和菜单项，不动任何存储 —— 所以这个页面可以放心让人乱点。
 */
class ModulesActivity : Activity() {

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, ModulesActivity::class.java)
    }

    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    private fun dp(v: Float) = Ui.dp(this, v)

    /** 改动成功后的短提示：新一条替换上一条，连点不排队堆积。初始化/重建不调用 */
    private var lastToast: Toast? = null
    private fun saved(msg: String) {
        if (isFinishing) return
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
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
        top.addView(Ui.text(this, "模块管理", 17f, Brut.INK).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
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
        val disabled = prefs.disabledFeatures

        body.addView(Ui.text(this, "关掉的模块不显示入口，数据一条不删。", 12.5f, Brut.HINT).apply {
            setPadding(0, dp(4f), 0, dp(12f))
        })

        Features.sorted().forEach { f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            }
            Brut.card(row, Brut.CARD)
            row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(this, f.title, 14.5f, Brut.INK))
            val sub = f.subtitle.ifBlank { "" }
            if (sub.isNotEmpty()) col.addView(Ui.text(this, sub, 12f, Brut.HINT))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            if (FeatureToggles.canDisable(f.id)) {
                row.addView(Switch(this).apply {
                    isChecked = f.id !in disabled
                    contentDescription = "开关「" + f.title + "」"
                    setOnCheckedChangeListener { _, on ->
                        val cur = prefs.disabledFeatures.toMutableSet()
                        if (on) cur.remove(f.id) else cur.add(f.id)
                        prefs.disabledFeatures = cur
                        saved((if (on) "已开启：" else "已关闭：") + f.title)
                    }
                })
            } else {
                row.addView(Ui.text(this, "固定", 11.5f, Brut.HINT).apply { setPadding(dp(6f), 0, dp(6f), 0) })
            }
            body.addView(row)
        }

        body.addView(Ui.text(this, "「学习」固定开启：知识卡、复习、计划都挂在它下面，关掉等于把主用途卸掉。", 12f, Brut.HINT).apply {
            setPadding(0, dp(16f), 0, 0)
        })
    }
}
