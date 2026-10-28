package com.chun.lingzhu

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机本机的 L3 确认框（T7 读屏 / T8 打开 App 用）：**这是「人真的点了」的唯一证据**。
 *
 * ## 为什么电脑弹框不够
 * 电脑那一端的确认只证明「谁在调」，证明不了「手机的主人此刻同意让这一屏被读」。所以 L3 指令必须在
 * **执行设备**上再问一次，并且框上写清工具名、请求方客户端名、以及**这条指令真正的结构化参数**
 * （读屏写用途 + 模式；打开 App 写别名 + 真实应用名 + 理由）。人看完点「允许一次」才继续。
 *
 * ## 硬规则（全部 fail closed）
 *  - [TIMEOUT_MS] 内没人点 → [Outcome.TIMEOUT]，**不执行**。没有「记住这个选择」的开关，也不读任何旧决定。
 *  - 触摸被别的窗口盖着（`FLAG_WINDOW_IS_OBSCURED` / 29+ 的部分遮挡）→ 立刻 [Outcome.DENIED]。
 *    这是点按劫持（tapjacking）的标准防线：一个透明浮层盖在按钮上，用户以为点的是别处。
 *  - 没有悬浮窗权限 / 建窗失败 → [Outcome.UNAVAILABLE]。调用方据此报**真实原因**（不是「用户拒绝」）。
 *  - [cancel] 无条件收掉框并按 DENIED 返回 —— 网络断 / 用户关常连 / 撤配对 / 服务停时，
 *    不能留一个还在等点击的框，也绝不能把它当「已批准」。
 *
 * ## 与截图的关系
 * 契约要求「确认前不截屏」。本类在返回 APPROVED **之前**就把窗口摘掉了，放行后再等 [HIDE_SETTLE_MS]
 * 给系统撤 surface —— 调用方拿到 APPROVED 后立刻截图，框不会进画面。
 *
 * ## 配色
 * 一律走 [Ui]（主题色板 + 形状语言），不写死任何颜色 —— 用户换到瑞士国际风格时这一页跟着变。
 */
class LinkConfirm(private val ctx: Context) {

    /** 确认框里的一条**结构化参数**（label 给人看的名字，value 这次实际的值） */
    data class Row(val label: String, val value: String)

    /**
     * @param title   动作标题（「读取手机屏幕文字」/「在这台手机上打开应用」）
     * @param client  请求方客户端名（来自配对连接的 client.name，不是模型自报的名字）
     * @param rows    结构化参数，逐条显示
     * @param privacy 隐私说明。**必须说真话**：读屏要写明截图会送给用户自己配置的识别模型。
     */
    data class Request(
        val title: String,
        val client: String,
        val rows: List<Row>,
        val privacy: String = "",
    )

    enum class Outcome { APPROVED, DENIED, TIMEOUT, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { ctx.getSystemService(WindowManager::class.java) }

    /** 当前这一趟（执行是串行的，但 [cancel] 可能来自别的线程） */
    @Volatile private var session: Session? = null

    /**
     * 弹框并**阻塞**等待人来点（调用方在 Link 执行线程上，不是主线程）。返回即保证窗口已摘掉。
     * 绝不从主线程调用 —— 会死在 latch 上。
     */
    fun ask(req: Request): Outcome {
        if (Looper.myLooper() == Looper.getMainLooper()) return Outcome.UNAVAILABLE
        if (!Settings.canDrawOverlays(ctx)) return Outcome.UNAVAILABLE

        val s = Session(req)
        session = s
        main.post { s.show() }
        val released = try {
            s.done.await(TIMEOUT_MS + 3_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            main.post { s.finish(Outcome.DENIED) }
            Thread.currentThread().interrupt()
            return Outcome.DENIED
        }
        if (!released) {
            // 极端情况（主线程长时间被占）：按超时收，窗口尽力摘掉
            main.post { s.finish(Outcome.TIMEOUT) }
            session = null
            return Outcome.TIMEOUT
        }
        session = null
        val out = s.outcome ?: Outcome.DENIED
        if (out == Outcome.APPROVED) {
            // removeViewImmediate 已经跑完，再给系统一点时间撤 surface —— 之后截的图里不会再有这个框
            try { Thread.sleep(HIDE_SETTLE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return out
    }

    /** 无条件收掉在等的框。没有在等的框时是空操作。可从任意线程调用。 */
    fun cancel() {
        val s = session ?: return
        main.post { s.finish(Outcome.DENIED) }
    }

    // ---------------- 一趟确认 ----------------

    private inner class Session(private val req: Request) {
        val done = CountDownLatch(1)
        val isFinished = AtomicBoolean(false)
        @Volatile var outcome: Outcome? = null

        private var root: View? = null
        private var countdown: TextView? = null
        private var secondsLeft = (TIMEOUT_MS / 1000).toInt()

        private val ticker = object : Runnable {
            override fun run() {
                val c = countdown ?: return
                secondsLeft--
                c.text = secondsText(secondsLeft)
                if (secondsLeft <= 0) finish(Outcome.TIMEOUT) else main.postDelayed(this, 1_000L)
            }
        }

        fun show() {
            if (isFinished.get()) return
            if (!Settings.canDrawOverlays(ctx)) { finish(Outcome.UNAVAILABLE); return }
            val v = build()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // 不加 FLAG_NOT_TOUCH_MODAL：框在时底下的 App 一个点都收不到（免得「确认框还开着，
                // 手指却点到了目标 App」这种错位）。也不加 FLAG_NOT_FOCUSABLE：框在时必须拿到窗口焦点，
                // 否则它只是一个可以被随便盖东西的透明窗口。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.CENTER }
            try {
                wm.addView(v, lp)
                root = v
            } catch (_: Exception) {
                finish(Outcome.UNAVAILABLE)
                return
            }
            countdown?.text = secondsText(secondsLeft)
            main.postDelayed(ticker, 1_000L)
            main.postDelayed({ finish(Outcome.TIMEOUT) }, TIMEOUT_MS)
        }

        /** 摘窗 + 记结论 + 放行。**幂等**：重复调用只有第一次有效 */
        fun finish(o: Outcome) {
            if (!isFinished.compareAndSet(false, true)) return
            outcome = o
            main.removeCallbacks(ticker)
            root?.let { v -> try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
            root = null
            countdown = null
            done.countDown()
        }

        private fun build(): View {
            val c = ctx
            val frame = object : FrameLayout(c) {
                /**
                 * 遮挡即拒绝：只要有别的窗口盖在这个框上面（半透明浮层 / 无障碍浮层 / 恶意 tapjacking 窗口），
                 * 系统就会给这一下触摸打上 OBSCURED 标志。在 dispatch 之前判 —— 子 View 根本拿不到这次点击。
                 */
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        val f = ev.flags
                        val obscured = (f and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
                            (Build.VERSION.SDK_INT >= 29 && (f and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)
                        if (obscured) {
                            finish(Outcome.DENIED)
                            return true
                        }
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }.apply {
                // 半透明遮罩：底下的 App 仍看得见（用户要知道自己在批准「读哪一屏」），但点不进去。
                // 颜色取当前主题的墨色 + 固定不透明度，不写死色值。
                setBackgroundColor(withAlpha(Brut.INK, SCRIM_ALPHA))
                isClickable = true   // 吃掉遮罩上的点击：既不穿透，也不算「允许」
                contentDescription = "本机确认：${req.title}"
            }

            val card = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.panelBg(c, Ui.theme, 16f)
                val p = Ui.dp(c, 16f)
                setPadding(p, Ui.dp(c, 14f), p, Ui.dp(c, 14f))
            }
            frame.addView(card, FrameLayout.LayoutParams(
                Math.min((c.resources.displayMetrics.widthPixels * 0.92f).toInt(), Ui.dp(c, 380f)),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER })

            // ---- 抬头：动作 + 请求方 ----
            card.addView(Ui.text(c, req.title, 16f, Ui.TX1).apply { typeface = Typeface.DEFAULT_BOLD })
            card.addView(Ui.text(c, "请求方：${req.client.ifBlank { "未知客户端" }}", 12.5f, Ui.TX2).apply {
                setPadding(0, Ui.dp(c, 4f), 0, 0)
            })

            // ---- 结构化参数 ----
            for (r in req.rows) {
                val row = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, Ui.dp(c, 9f), 0, 0)
                }
                row.addView(Ui.text(c, r.label, 11.5f, Ui.TX3))
                row.addView(Ui.text(c, clipValue(r.value), 13.5f, Ui.TX1).apply {
                    setPadding(0, Ui.dp(c, 1f), 0, 0)
                })
                card.addView(row)
            }

            // ---- 隐私说明（必须说真话）----
            if (req.privacy.isNotBlank()) {
                card.addView(Ui.text(c, req.privacy, 11.5f, Ui.TX2).apply {
                    setPadding(0, Ui.dp(c, 12f), 0, 0)
                    setLineSpacing(0f, 1.25f)
                })
            }

            // ---- 倒计时：让人看得见「不点就自动拒」----
            countdown = Ui.text(c, secondsText(secondsLeft), 11.5f, Ui.TX3).apply {
                setPadding(0, Ui.dp(c, 12f), 0, 0)
            }
            card.addView(countdown)

            // ---- 两个按钮：拒绝在左，允许在右 ----
            val buttons = Ui.row(c).apply { setPadding(0, Ui.dp(c, 12f), 0, 0) }
            val deny = Ui.button(c, "拒绝") { finish(Outcome.DENIED) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                minimumHeight = Ui.dp(c, 48f)
                contentDescription = "拒绝这次操作"
            }
            val allow = Ui.button(c, "允许一次") { finish(Outcome.APPROVED) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                minimumHeight = Ui.dp(c, 48f)
                contentDescription = "允许这一次操作（只这一次，不记住）"
            }
            Ui.emphasize(allow)
            buttons.addView(deny, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(c, 8f) })
            buttons.addView(allow, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(buttons)
            return frame
        }
    }

    companion object {
        /** 契约：手机侧 20 秒，不点就拒。**没有**「记住我的选择」 */
        const val TIMEOUT_MS = 20_000L
        /** 摘窗后等系统撤 surface 的时间：之后立刻截屏不会把这个框截进去 */
        const val HIDE_SETTLE_MS = 120L
        /** 遮罩不透明度（0x66 ≈ 40%）：看得见底下的画面，但看不清到能误认按钮 */
        const val SCRIM_ALPHA = 0x66
        /** [Row] 的值在框里最多显示这么长 —— 确认框是「看一眼」的地方，不是日志 */
        const val VALUE_MAX = 160

        /** 20 秒的秒数文字（纯函数，单测直接盯倒计时口径，含中文数字与负数边界） */
        fun secondsText(left: Int): String = "还有 ${if (left < 0) 0 else left} 秒自动拒绝"

        /** 值清洗（去换行 / 控制字符、截断）；空值给「（空）」。纯函数 */
        fun clipValue(v: String?): String = LinkAudit.clean(v, VALUE_MAX).ifBlank { "（空）" }

        /** 给颜色换一个 alpha（只动透明度，不动色相 —— 主题换到瑞士风时照样正确） */
        internal fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

        /** 遮罩颜色的 alpha 分量（单测：确认遮罩真的半透明、且不是全黑挡死画面） */
        fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF
    }
}
