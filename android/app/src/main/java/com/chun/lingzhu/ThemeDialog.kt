package com.chun.lingzhu

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView

/**
 * 系统弹窗 / 菜单的统一入口（v2.7.3）。
 *
 * ## 为什么需要它
 * 以前 15+ 个文件各自 `AlertDialog.Builder(this)` / `PopupMenu(this, anchor)` ——
 * 系统弹窗永远是平台 DeviceDefault 的蓝 / 白底，跟本 App 的 18 套配色完全脱节。
 * 玻璃主题下弹一个白色系统框尤其刺眼。这里用一个 Builder 子类 + ContextThemeWrapper 把
 * 「窗口底 → 材质 SHEET、标题 / 正文 / 按钮 / 列表 / 自定义 View 字色」统一刷成当前主题。
 *
 * ## 约束
 *  - 不用反射（不碰 AlertController / ListPopupWindow 的隐藏字段）；
 *  - 不全树强制染色（只改已知 id 的壳控件 + 自定义 View 子树里的 EditText / TextView，
 *    `tag = Brut.KEEP` 的子树跳过 —— 保留调用方故意染色的危险文字 / 图标）；
 *  - 不引入系统窗口 blur（`setBackgroundBlurRadius` 跨窗口模糊在 overlay / 非 31+ 上不可靠）。
 *
 * ## 用法
 * ```
 * ThemeDialogs.builder(ctx)
 *     .setTitle("删除？").setMessage("不可撤销")
 *     .setPositiveButton("删除") { _, _ -> ... }
 *     .setNegativeButton("取消", null)
 *     .show()
 * ```
 * 链式调用、取消 / 确认 / 自定义 View / EditText 事件全部原样保留。
 */
object ThemeDialogs {

    /**
     * 统一入口：返回 [AlertDialog.Builder] 子类，调用方原样接 `.setTitle/.setMessage/...`。
     *
     * 内部用 [ContextThemeWrapper] 套一层深 / 浅 dialog 主题（保证列表项 / 按钮的**默认**字色
     * 在着色漏掉时也不至于黑底黑字），再在 dialog show 后把窗口底换成 [Ui.material] SHEET 角色，
     * 标题 / 正文 / 按钮 / 列表 / 自定义 View 子树按当前主题刷字色与输入框材质。
     */
    fun builder(ctx: Context): AlertDialog.Builder = ThemedBuilder(ctx)

    /**
     * 统一入口：返回 [PopupMenu]，调用方原样接 `setOnMenuItemClickListener` / `menu.add` / `show`。
     *
     * 内部用 [ContextThemeWrapper] 套一层深 / 浅 popup 主题，设置 `android:popupBackground`
     * 为中性卡片色。**不用反射** —— PopupMenu 的内部 ListPopupWindow 是隐藏字段，
     * 想给它塞一个 Drawable 材质只能走反射（被禁止），所以 popup 背景是**平面色**而非玻璃材质。
     * 这是已知限制：popup 菜单的底是中性卡色（浅主题白 / 深主题 #1C1C1C），不走玻璃透明层。
     */
    fun popup(ctx: Context, anchor: View, gravity: Int = Gravity.END): PopupMenu {
        val styleRes = if (dark) R.style.ThemePopupMenuDark else R.style.ThemePopupMenuLight
        return PopupMenu(ContextThemeWrapper(ctx, styleRes), anchor, gravity)
    }

    // ------------------------------------------------------------------ 内部

    /** 当前主题的纸底是不是深色（决定 dialog / popup 选深还是浅基底） */
    private val dark: Boolean get() = Theme.luminance(Brut.PAPER) < 0.4

    /** dialog 基底主题：深主题用 DeviceDefault Dark，浅主题用 Light（列表项 / 按钮默认字色的兜底） */
    private fun dialogBaseStyle(): Int =
        if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /**
     * Builder 子类：构造时套 [ContextThemeWrapper]（给 dialog 的列表 / 按钮一个正确的默认字色）。
     * [create] 挂一次 OnShowListener 着色，不重复 —— 调用方按契约只替换创建入口，
     * 不会 setOnShowListener 覆盖我们的 listener（ThemeSurfaceCallsites 已确认链式调用原样保留）。
     */
    private class ThemedBuilder(ctx: Context) :
        AlertDialog.Builder(ContextThemeWrapper(ctx, dialogBaseStyle())) {

        override fun create(): AlertDialog {
            val d = super.create()
            d.setOnShowListener { colorDialog(d) }
            return d
        }
    }

    /**
     * 把已经 show 出来的 [AlertDialog] 刷成当前主题。
     *
     * 顺序：窗口底材质 → 标题 → 正文 → 按钮 → 列表分隔线 → 自定义 View 子树。
     * 幂等：重复调用只是重设同样的值，不分配（材质 Drawable 每次新建一张，但只在 show 时调一次）。
     */
    private fun colorDialog(d: AlertDialog) {
        val ctx = d.context ?: return
        val w = d.window ?: return

        // 窗口底 = 材质 SHEET 角色（玻璃→轻透明 + 高光边 + 柔和阴影；普通→flat card + 发丝边）。
        // 替换系统 dialog 的默认白底 / 圆角 inset，用我们自己的材质。
        w.setBackgroundDrawable(Ui.material(ctx, Brut.CARD, Ui.SurfaceRole.SHEET, 28f, Ui.theme))
        w.setDimAmount(0.3f)

        // 标题：alertTitle 是 internal id，走 getIdentifier（资源 id 查找，不是反射）。
        val titleId = ctx.resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) d.findViewById<TextView>(titleId)?.let {
            it.setTextColor(Brut.INK); it.typeface = Typeface.DEFAULT_BOLD
        }
        // 正文
        d.findViewById<TextView>(android.R.id.message)?.setTextColor(Ui.TX1)

        // 按钮：统一墨色 + 加粗。危险按钮靠文字（⚠）表达，不靠红色 —— 和项目一贯「含义靠文字」一致。
        for (which in intArrayOf(
            AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            d.getButton(which)?.let { btn ->
                btn.setTextColor(Brut.INK)
                btn.typeface = Typeface.DEFAULT_BOLD
            }
        }

        // 列表：分隔线走统一发丝边
        @Suppress("DEPRECATION")
        d.listView?.let { lv ->
            val divW = maxOf(1, Ui.dp(ctx, if (Ui.theme.noShadowStyle) 1f else 0.6f))
            lv.divider = ColorDrawable(Brut.edge())
            lv.dividerHeight = divW
        }

        // 自定义 View 子树：只刷 EditText（→ Brut.field 统一材质）和 TextView 字色，
        // 不碰 Button（dialog 自己的按钮由 getButton 着色；自定义 View 里的按钮保留调用方设置）。
        // tag = Brut.KEEP 的子树跳过 —— 保留调用方故意染色的危险文字 / 图标。
        d.findViewById<View>(android.R.id.custom)?.let { styleCustom(it) }
    }

    /**
     * 定向刷自定义 View 子树：EditText → [Brut.field]（统一材质），TextView → 墨色。
     * 不递归进 Button（保留调用方设置），不碰 [Brut.KEEP] 子树。
     */
    private fun styleCustom(v: View) {
        if (v.tag == Brut.KEEP) return
        when (v) {
            is EditText -> Brut.field(v)
            is TextView -> if (v !is android.widget.Button) v.setTextColor(Brut.INK)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) styleCustom(v.getChildAt(i))
    }
}
