package com.chun.lingzhu

import android.animation.ValueAnimator
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
    /**
     * 入场弹簧（持实例才有「可打断」：入场还没落定就 hide，卡片带着当时的速度直接拐出去）。
     * 系统关动画时为 null —— 那种情况既不摆起点、也不排帧。
     */
    private var motion: AnimGlue.SpringAnimation? = null
    /**
     * 正在退场的窗口：动画真跑完才 `removeView`。这段期间 [showing] 仍为 true（上层据此不会在
     * 退场中途又弹一张卡），但窗口已置 `FLAG_NOT_TOUCHABLE` —— 看得见、摸不到，不会挡住触摸。
     */
    private var exiting: View? = null
    /** 当前窗口参数：退场时要就地加 `FLAG_NOT_TOUCHABLE` */
    private var winLp: WindowManager.LayoutParams? = null

    val showing: Boolean get() = view != null || exiting != null

    fun show(card: KnowledgeCard.KCData, deckName: String) {
        if (view != null) return
        finishExit()   // 上一张还在淡出：立刻摘掉，绝不让两层窗口并存
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
        // 入场：先摆到起点（全透明 + 略缩），再加窗口 —— 反过来会先闪一帧全不透明。
        // 只动 alpha / scale，不动 translation：悬浮窗是 WRAP_CONTENT，视图位移会被窗口 surface 裁掉。
        val m = motionOrNull(root, Anim.Visual.entering(0, 0, 0f, scale = ENTER_SCALE))
        try { wm.addView(root, lp) } catch (_: Exception) { return }
        view = root
        winLp = lp
        motion = m
        m?.to(Anim.Visual.SHOW)
        bump()
    }

    /**
     * 建一个入场弹簧；系统「动画时长缩放」为 0 时返回 null —— 那种情况什么都不摆、也不排帧，
     * 卡片直接以终态出现（无动效意图就是无动效，不是「更快的动画」）。
     */
    private fun motionOrNull(root: View, start: Anim.Visual): AnimGlue.SpringAnimation? {
        if (!ValueAnimator.areAnimatorsEnabled()) return null
        return AnimGlue.SpringAnimation(root, Anim.Spring.popIn).also { it.settleAt(start) }
    }

    /** 上一张的退场立刻收场：停帧循环（settleAt 会清掉 settleAfter）后摘窗，恰好一次 */
    private fun finishExit() {
        val v = exiting ?: return
        motion?.settleAt(Anim.Visual.HIDDEN)
        detach(v)
    }

    /** 摘窗（幂等）：只有「仍是正在退场的那个视图」才动手，动画回调 / 兜底计时 / show 抢先都汇到这里 */
    private fun detach(v: View) {
        if (exiting !== v) return
        exiting = null
        motion = null
        winLp = null
        main.removeCallbacks(exitGuard)
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    /**
     * 退场兜底：帧回调如果没跑起来（进程要没了 / 窗口已被系统摘掉），到点也必须把窗口摘掉，
     * 绝不留一个「看不见但还在」的悬浮窗。走 [finishExit] 顺手停掉帧循环；[detach] 幂等，
     * 正常路径下这次调用是空操作。
     */
    private val exitGuard = Runnable { finishExit() }

    private fun rateButton(rating: Fsrs.Rating, label: String): TextView =
        Ui.button(ctx, label) {
            if (!revealed || view == null) return@button
            onRate(cardId, rating)
            hide()
        }

    /**
     * 点一下：揭示 / 收起背面。
     *
     * 揭示只给**新出现的正文**做一次短淡入，不做高度动画：卡片是 WRAP_CONTENT 悬浮窗，
     * 高度增长本来就由一次布局完成；逐帧改高度会让整棵树每帧重新测量，在悬浮窗上是纯浪费。
     */
    private fun toggleReveal() {
        if (view == null) return
        revealed = !revealed
        if (revealed) {
            hint.visibility = View.GONE
            back.visibility = View.VISIBLE
            ratingRow.visibility = View.VISIBLE
            fadeIn(back)
            fadeIn(ratingRow)
        } else {
            // 收起是「还原初始态」：立刻落终态，不留半透明、不排队
            val settled = Anim.Visual.SHOW
            with(AnimGlue) {
                back.animate().cancel()
                ratingRow.animate().cancel()
                back.applyVisual(settled)
                ratingRow.applyVisual(settled)
            }
            back.visibility = View.GONE
            ratingRow.visibility = View.GONE
            hint.visibility = View.VISIBLE
        }
        bump()
    }

    /**
     * 一次短淡入（[Anim.MICRO]）：只碰 alpha，不动高度 —— 正文由一次布局长出来，逐帧高度动画会让
     * 这棵悬浮窗每帧重新测量。被打断 / 系统关动画时终态直接写成 [Anim.Visual.SHOW]，绝不停在半透明。
     */
    private fun fadeIn(v: View) {
        v.animate().cancel()
        with(AnimGlue) {
            if (!ValueAnimator.areAnimatorsEnabled()) { v.applyVisual(Anim.Visual.SHOW); return }
            v.applyVisual(Anim.Visual.HIDDEN)
            v.animateVisual(Anim.Visual.SHOW, Anim.MICRO, AnimGlue.EASE_OUT)
        }
    }

    // 退场期间按钮一律失效：view 已为 null → 不会在关掉之后又补一次评分 / 关闭回调
    private fun close() { if (view == null) return; onClose(cardId); hide() }
    private fun snooze() { if (view == null) return; onSnooze(cardId); hide() }

    private fun bump() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, stayMs().coerceAtLeast(1_000L))
    }

    /**
     * 收起窗口（自动收起 / 关闭 / 稍后 都走这里）。不评分、不联网。
     *
     * 有动效时先淡出再摘窗：这段期间 [showing] 仍为 true（上层不会在退场中途又弹一张），
     * 窗口就地加上 `FLAG_NOT_TOUCHABLE` —— 看得见、摸不到，不会变成一块透明挡板。
     * 摘窗只由 [detach] 做（幂等），重复调用 [hide] 是空操作（view 已置 null）。
     */
    fun hide() {
        main.removeCallbacks(autoHide)
        val v = view ?: run {
            // 已经在退场：再说一次「收起」就等于「现在就没了」（服务销毁 / 转屏 / 换配色都这么调）
            finishExit()
            return
        }
        view = null
        revealed = false
        cardId = -1L
        exiting = v
        val m = motion
        if (m == null || !ValueAnimator.areAnimatorsEnabled()) {
            detach(v)
            return
        }
        // 退场期间不可触摸：看得见、摸不到（不能留一块透明挡板等动画跑完）
        val lp = winLp
        if (lp != null) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(v, lp) }
        }
        // 兜底：帧回调万一没跑起来（进程要没了 / 窗口已被系统摘掉），到点也必须摘窗
        main.postDelayed(exitGuard, EXIT_GUARD_MS)
        m.to(Anim.Visual.leaving(0, 0, 0f, scale = EXIT_SCALE), Anim.Spring.popOut) { detach(v) }
    }

    private companion object {
        /** 入场起点缩到 97%：再小就成了缩放动画，看不清是「冒出来」还是「弹出来」 */
        const val ENTER_SCALE = 0.97f
        /** 出场只收到 99%：出去的东西不用再交代那么多 */
        const val EXIT_SCALE = 0.99f
        /** 退场兜底延迟：popOut 约 115ms，给到 3 倍余量 */
        const val EXIT_GUARD_MS = 400L
    }
    private fun dp(n: Float) = Ui.dp(ctx, n)
}
