package com.chun.lingzhu

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 知识卡定时弹窗（宿主是 [OrbService]，与 [WordCard] 同型：`TYPE_APPLICATION_OVERLAY` 悬浮窗）。
 *
 * 交互：正面 → 点一下揭示背面 → （揭示之后）四档评分「忘记 / 困难 / 良好 / 简单」。
 *  - 四档评分直接交给上层写回**同一个** [KnowledgeCard.Store]（FSRS 与服务共一份），并标记「刚弹过」；
 *  - 「关闭 / 稍后」**不评分**（不写学习记录）：关闭只是收起，稍后把下一拍提前；
 *  - 没人碰满 [stayMs] 自动收起，同样不评分（与 [WordCard] 的自动收起一致）。
 *
 * 与单词卡的互斥由上层守（两边 `showing` 互相排除），这里只负责把一张卡画出来。纯 View，无 Compose。
 */
class KnowledgePopup(
    private val ctx: Context,
    private val wm: WindowManager,
    /** 四档评分（仅在揭示之后触发）：Again / Hard / Good / Easy → 上层写回 Store。 */
    private val onRate: (id: Long, rating: Fsrs.Rating) -> Unit,
    /** 关闭（不评分）。 */
    private val onClose: (id: Long) -> Unit,
    /** 稍后（不评分；上层把下一拍提前）。 */
    private val onSnooze: (id: Long) -> Unit,
    /** 无人操作时停留多少毫秒自动收起（不评分）。 */
    private val stayMs: () -> Long = { 20_000L },
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var cardId = -1L
    private var revealed = false
    private val autoHide = Runnable { hide() }
    private lateinit var back: TextView
    private lateinit var hint: TextView
    private lateinit var ratingRow: LinearLayout

    val showing: Boolean get() = view != null

    fun show(card: KnowledgeCard.KCData, deckName: String) {
        if (view != null) return
        cardId = card.id
        revealed = false
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.panelBg(ctx, radiusDp = 16f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setOnClickListener { toggleReveal() }
        }
        val deck = Ui.text(ctx, if (deckName.isBlank()) "知识卡" else deckName, 11.5f, Brut.HINT)
        val front = Ui.text(ctx, card.front, 19f, Ui.TX1).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        hint = Ui.text(ctx, "点一下看答案", 12f, Brut.HINT)
        back = Ui.text(ctx, card.back, 14f, Ui.TX2).apply {
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
            setPadding(0, dp(6f), 0, dp(6f))
        }
        ratingRow = Ui.row(ctx).apply {
            visibility = View.GONE
            val lp = { LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) } }
            addView(rateButton(Fsrs.Rating.Again, "忘记"), lp())
            addView(rateButton(Fsrs.Rating.Hard, "困难"), lp())
            addView(rateButton(Fsrs.Rating.Good, "良好"), lp())
            addView(rateButton(Fsrs.Rating.Easy, "简单"), lp())
        }
        val bottom = Ui.row(ctx).apply {
            setPadding(0, dp(10f), 0, 0)
            addView(Ui.button(ctx, "关闭") { close() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) })
            addView(Ui.button(ctx, "稍后") { snooze() })
        }
        root.addView(deck)
        root.addView(front)
        root.addView(hint)
        root.addView(back)
        root.addView(ratingRow)
        root.addView(bottom)

        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = Ui.statusBarHeight(ctx) + dp(8f)
        }
        try { wm.addView(root, lp) } catch (_: Exception) { return }
        view = root
        bump()
    }

    private fun rateButton(rating: Fsrs.Rating, label: String): TextView =
        Ui.button(ctx, label) {
            if (!revealed || view == null) return@button
            onRate(cardId, rating)
            hide()
        }

    private fun toggleReveal() {
        if (view == null) return
        revealed = !revealed
        back.visibility = if (revealed) View.VISIBLE else View.GONE
        ratingRow.visibility = if (revealed) View.VISIBLE else View.GONE
        hint.visibility = if (revealed) View.GONE else View.VISIBLE
        bump()
    }

    private fun close() { onClose(cardId); hide() }
    private fun snooze() { onSnooze(cardId); hide() }

    private fun bump() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, stayMs().coerceAtLeast(1_000L))
    }

    /** 收起窗口（自动收起 / 关闭 / 稍后 都走这里）。不评分、不联网。 */
    fun hide() {
        main.removeCallbacks(autoHide)
        val v = view ?: return
        view = null
        revealed = false
        cardId = -1L
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun dp(n: Float) = Ui.dp(ctx, n)
}
