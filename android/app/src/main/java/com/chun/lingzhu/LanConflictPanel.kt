package com.chun.lingzhu

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 冲突面板：把 `listConflicts()` 的结果逐条画成「同屏对比 + 三选一」。
 *
 * - 知识卡只对比纯文本 [deck] / [front] / [back]；FSRS 只显示「复习进度」（新卡 / 已复习几次），
 *   不展开 stability / difficulty / due 之类内部数值；
 * - 收藏只对比 kind / label / text；
 * - 三选一直接回调 [ConflictPick]，由 [LanSyncActivity] 在 IO 线程调用 `resolveConflict`；
 * - 已处理的副本（KEEP_BOTH 时仍存活）会标注「已处理」，仍允许改选；
 * - 墓碑一栏显示「这一版已被删除」，并禁用不可能的选择：主版本已删除时只剩「只留副本」，
 *   副本已删除（含已处理成墓碑）时三个选项都不可用。
 *
 * 纯 View，不引入任何第三方控件。
 */
internal object LanConflictPanel {

    fun render(
        activity: Activity,
        host: LinearLayout,
        conflicts: JSONArray,
        onPick: (copyUid: String, pick: ConflictPick) -> Unit,
    ) {
        for (i in 0 until conflicts.length()) {
            val item = conflicts.optJSONObject(i) ?: continue
            host.addView(
                card(activity, item, onPick),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 10) },
            )
        }
    }

    private fun card(
        activity: Activity,
        item: JSONObject,
        onPick: (String, ConflictPick) -> Unit,
    ): View {
        val copyUid = item.optString("copyUid")
        val kind = item.optString("kind", LanConflict.KIND_CARDS)
        val base = item.optJSONObject("baseValue")
        val copy = item.optJSONObject("copyValue")
        val side = item.optString("side", LanConflict.SIDE_UNKNOWN)
        val resolved = item.optBoolean("resolved", false)

        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        Brut.card(box)

        val title = if (kind == LanConflict.KIND_HISTORY) "冲突 · 收藏" else "冲突 · 知识卡"
        box.addView(TextView(activity).apply {
            text = title
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Brut.INK)
            setPadding(0, 0, 0, dp(activity, 6))
        })
        box.addView(TextView(activity).apply {
            text = sideLabel(side) + if (resolved) " · 已处理（可改选）" else ""
            textSize = 12.5f
            alpha = 0.75f
            setTextColor(Brut.INK)
            setPadding(0, 0, 0, dp(activity, 6))
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(column(activity, baseTitle(side), base, kind),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(activity, 8) })
        row.addView(column(activity, copyTitle(side), copy, kind),
            LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(row)

        // 墓碑一栏已经标了「这一版已被删除」，这里再说明它限制了哪些选择：
        //  - 主版本被删、副本还活着：只能恢复副本，保留删除 / 两条都留都不成立；
        //  - 副本已被删（含已处理成墓碑）：这条冲突没有可保留的内容，三个选项都不可用。
        if (base == null || copy == null) {
            box.addView(TextView(activity).apply {
                text = if (copy == null) {
                    "副本已被删除，这条冲突无需再处理"
                } else {
                    "主版本已被删除，只能保留副本内容"
                }
                textSize = 12.5f
                alpha = 0.75f
                setTextColor(Brut.INK)
                setPadding(0, dp(activity, 6), 0, 0)
            })
        }

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 10), 0, 0)
        }
        val baseBtn = Button(activity).apply {
            text = "只留主版本"
            setOnClickListener { onPick(copyUid, ConflictPick.KEEP_BASE) }
        }
        val copyBtn = Button(activity).apply {
            text = "只留副本"
            setOnClickListener { onPick(copyUid, ConflictPick.KEEP_COPY) }
        }
        val bothBtn = Button(activity).apply {
            text = "两条都留"
            setOnClickListener { onPick(copyUid, ConflictPick.KEEP_BOTH) }
        }
        // 与 LanConflict.resolve 的守卫一致：主版本必须是活的才能「只留主版本 / 两条都留」，
        // 副本必须是活的才有东西可解决。两边都活着时三个选项保持原样。
        val enabled = listOf(
            base != null && copy != null,
            copy != null,
            base != null && copy != null,
        )
        for ((index, button) in listOf(baseBtn, copyBtn, bothBtn).withIndex()) {
            button.isEnabled = enabled[index]
            Brut.button(button, if (index == 2) Brut.MINT else Brut.CARD)
            buttons.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
                if (index < 2) bottomMargin = dp(activity, 6)
            })
        }
        box.addView(buttons)
        return box
    }

    private fun column(
        activity: Activity,
        title: String,
        value: JSONObject?,
        kind: String,
    ): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = title
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Brut.INK)
            setPadding(0, 0, 0, dp(activity, 4))
        })
        if (value == null) {
            col.addView(text(activity, "（这一版已被删除）"))
            return col
        }
        if (kind == LanConflict.KIND_HISTORY) {
            col.addView(text(activity, "类型：" + historyKind(value.optString("kind"))))
            col.addView(text(activity, "模式：" + clip(value.optString("label"), 80)))
            col.addView(text(activity, "内容：" + clip(value.optString("text"), 400)))
        } else {
            col.addView(text(activity, "牌组：" + clip(value.optString("deck"), 80)))
            col.addView(text(activity, "正面：" + clip(value.optString("front"), 200)))
            col.addView(text(activity, "背面：" + clip(value.optString("back", ""), 400)))
            col.addView(reviewProgress(activity, value))
        }
        return col
    }

    private fun text(activity: Activity, value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(Brut.INK)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(activity, 2), 0, 0)
    }

    /** FSRS 只标复习进度，不暴露 stability / difficulty / due 等内部数值。 */
    private fun reviewProgress(activity: Activity, value: JSONObject): TextView {
        val fsrs = value.optJSONObject("fsrs")
        val state = fsrs?.optInt("state", 0) ?: 0
        val reps = fsrs?.optInt("reps", 0) ?: 0
        val label = if (state == 0 && reps == 0) {
            "复习进度：新卡（未复习）"
        } else {
            "复习进度：已复习 $reps 次"
        }
        return text(activity, label)
    }

    private fun baseTitle(side: String): String = when (side) {
        LanConflict.SIDE_LOCAL -> "主版本（本机内容）"
        LanConflict.SIDE_PEER -> "主版本（电脑内容）"
        else -> "主版本"
    }

    private fun copyTitle(side: String): String = when (side) {
        LanConflict.SIDE_LOCAL -> "副本（电脑内容）"
        LanConflict.SIDE_PEER -> "副本（本机内容）"
        else -> "副本"
    }

    private fun sideLabel(side: String): String = when (side) {
        LanConflict.SIDE_LOCAL -> "电脑版本与本机版本冲突，主版本保留了本机内容"
        LanConflict.SIDE_PEER -> "电脑版本与本机版本冲突，主版本保留了电脑内容"
        else -> "两个版本并发修改，无法自动判断来源"
    }

    private fun historyKind(kind: String): String = when (kind) {
        History.KIND_TRANSLATE -> "翻译"
        History.KIND_SOLVE -> "识别"
        else -> kind.ifBlank { "记录" }
    }

    private fun clip(value: String?, max: Int): String {
        val s = value.orEmpty()
        if (s.isEmpty()) return "（空）"
        return if (s.length > max) s.take(max) + "…" else s
    }

    private fun dp(activity: Activity, value: Int): Int =
        (activity.resources.displayMetrics.density * value).toInt()
}
