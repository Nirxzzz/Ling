package com.chun.lingzhu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 双击小球截屏后的「先问一句」小浮窗（宿主 [OrbService]，独立 `TYPE_APPLICATION_OVERLAY` 窗口）。
 * 只负责问：不截屏、不读图、不请求模型，也不持有 bitmap —— 只把一句问题交给 [onSubmit]。
 *
 * 不变量：
 *  - [onSubmit] / [onCancel] 最多触发一次且互斥（[finished] 一处守）；[dismiss] 静默摘窗不回调；
 *    只有「取消」/ 返回键 / 弹窗失败才走 [onCancel]；点面板外面只收键盘，不取消。
 *  - 平时 `FLAG_NOT_FOCUSABLE`；点输入框才临时可获焦，并**等窗口真拿到焦点再叫 IME**
 *    （刚改完标志就 showSoftInput 会被系统丢掉）；键盘弹出后按 IME inset 把整窗抬到键盘顶边
 *    （[syncIme]：出帧后再挪、抬升量只增不减，键盘收起自动落回 [baseY]）。
 */
class CaptureQuestionPanel(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {

    companion object {
        /** 快捷问法：都是**完整**的中文问题，服务原样当用户提问用 */
        const val Q_WHY = "截图里的内容为什么会是现在这样？请用第一性原理解释：先列出它依赖的最基本的事实、定义或假设，再一步步推出结论。"
        const val Q_WHAT = "截图里的内容到底是什么？先用一两句大白话说清它的关键在哪，再给一个贴切的类比或生活里的具体例子。"
        const val Q_HOW = "截图里这件事具体要怎么做？给出可以照着执行的步骤，并说明每一步为什么这么做、有哪些坑要注意。"

        /** 键盘展开期间补读 inset 的时刻（ms）；一次性，不循环 */
        private val PROBE_MS = longArrayOf(0L, 60L, 140L, 260L, 420L, 640L)
        /** 判「键盘真收起了」的确认延迟（ms）：收起动画途中 inset 会先归零，隔这么久再确认 */
        private const val RESTORE_MS = 180L
    }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    /**
     * 根布局就地重写返回键：可获焦时系统先把返回送进窗口，不接管的话它会先被输入框吃掉、
     * 面板还留在屏幕上（用户以为已经关了）。
     */
    private val root = object : LinearLayout(ctx) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                userCancel()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply { orientation = LinearLayout.VERTICAL }

    /** 底边位置：屏幕上移一个导航条 + 一点余量；键盘弹出时在它之上再加抬升量（见 [syncIme]） */
    private val baseY = Ui.navBarHeight(ctx) + dp(12f)

    private val lp: WindowManager.LayoutParams = WindowManager.LayoutParams(
        Math.min((ctx.resources.displayMetrics.widthPixels * 0.92f).toInt(), dp(352f)),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = baseY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    private var attached = false
    /** 被上层让路中（见 [setHidden]）：窗口透明 + 不可触摸，草稿照样留着 */
    private var hidden = false
    /** 这一轮已经收过尾（提交 / 取消 / 静默摘窗）：回调最多一次，重复调用全部变成空操作 */
    private var finished = false
    private var inputOpen = false
    private var draft: EditText? = null
    private var focusWaiter: ViewTreeObserver.OnWindowFocusChangeListener? = null
    /** 本次输入会话已经抬升的像素（**只增**，见 [syncIme]）；0 = 坐在 [baseY] 上 */
    private var imeLift = 0
    /** 上一帧读到的 IME inset（px）；-1 = 本会话还没读到过 */
    private var imeOver = -1
    /** 上一次 syncIme 真的把窗口挪了多少 px（0 = 没挪）：用来判定 inset 口径，见 [syncIme] */
    private var lastDelta = 0
    /** 作废还没跑完的 [probeIme]：收起 / 摘窗 / 重新开键盘时 +1 */
    private var imeProbeToken = 0
    /**
     * 入场弹簧。**只有入场**：提交 / 取消 / 静默摘窗都必须当场摘窗口并把回调打出去
     * （上层拿到问题就发请求、拿到取消就还原状态行），退场动画会把这两件事推迟上百毫秒，
     * 也会在那段时间里留一个盖着屏幕的窗口 —— 所以退场一律瞬时。
     */
    private var motion: AnimGlue.SpringAnimation? = null
    private fun dp(v: Float) = Ui.dp(ctx, v)

    fun show() {
        if (attached) return
        finished = false
        // 上一轮可能被让路过：标志与不透明度都回到初始态（root / lp 是复用的，不清会「一开就是透明的」）
        hidden = false
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        inputOpen = false
        lp.y = baseY
        build()
        // 入场起点先写好：全透明。**不动位移 / 缩放** —— 这个窗口是 BOTTOM|WRAP_CONTENT，
        // 视图位移会被窗口 surface 裁掉，缩放又会在键盘弹出时和 inset 抬升抢注意力。
        val m = if (ValueAnimator.areAnimatorsEnabled())
            AnimGlue.SpringAnimation(root, Anim.Spring.popIn).also { it.settleAt(Anim.Visual.HIDDEN) }
        else {
            with(AnimGlue) { root.applyVisual(Anim.Visual.SHOW) }   // root 复用：直接落终态，不复用上一轮姿态
            null
        }
        val ok = runCatching { wm.addView(root, lp) }.isSuccess
        if (ok) {
            attached = true
            motion = m
            m?.to(Anim.Visual.SHOW)
            return
        }
        motion = null
        // root 是复用的：失败路径别把「全透明」留给下一轮
        with(AnimGlue) { root.applyVisual(Anim.Visual.SHOW) }
        attached = false
        root.removeAllViews()
        draft = null
        Toast.makeText(ctx, "小浮窗没弹出来（悬浮窗权限可能被收回），直接点小球再试一次", Toast.LENGTH_LONG).show()
        userCancel()
    }

    /** 静默摘窗口：不触发 [onCancel]（服务自己收尾、或提交后清理时用） */
    fun dismiss() {
        finished = true
        remove()
    }

    /**
     * 被上层（速记浮层 / 前台卡片页 / 截屏）临时压住：窗口变透明且**不可触摸**，
     * 但用户已经打了一半的问题、键盘会话的抬升量全部留着。
     *
     * 为什么不 dismiss：那会把用户正在写的问题丢掉。为什么不整个窗口摘掉再加回来：
     * 摘掉就得重建 EditText，草稿一样会丢。这里只改 alpha + 触摸标志 + 收键盘。
     * 窗口若本来就不可获焦（没在输入），连 updateViewLayout 都不发。
     */
    fun setHidden(h: Boolean) {
        if (hidden == h) return
        hidden = h
        if (h) closeInput()   // 看不见的窗口不能还挂着输入焦点 / 键盘
        // 入场弹簧正在跑的话先就地收场：它每帧都写 root.alpha，不收场会立刻把 0 覆盖回去
        motion?.settleAt(Anim.Visual.SHOW)
        motion = null
        root.alpha = if (h) 0f else 1f
        val nf = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = if (h) lp.flags or nf else lp.flags and nf.inv()
        if (attached) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun build() {
        root.removeAllViews()
        root.background = Ui.panelBg(ctx, prefs.baseTheme(), 16f)
        root.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        val head = Ui.row(ctx)
        head.addView(TextView(ctx).apply {
            text = "想了解什么"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TX1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Ui.button(ctx, "取消") { userCancel() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(12f), 0, dp(12f), 0)
            minimumWidth = dp(48f)
            minimumHeight = dp(48f)
            contentDescription = "取消这次提问"
        })
        root.addView(head)

        // 三个快捷问法：等宽三列（对称排版），每个 48dp 触控区；标签中英各一行，窄屏也放得下
        val opts = Ui.row(ctx).apply { setPadding(0, dp(8f), 0, 0) }
        fun opt(label: String, desc: String, q: String): TextView = Ui.button(ctx, label) { submit(q) }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            maxLines = 2
            contentDescription = desc
        }
        opts.addView(opt("What\n是什么", "What：截图里是什么", Q_WHAT), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        opts.addView(opt("Why\n为什么", "Why：解释为什么会这样", Q_WHY), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        opts.addView(opt("How\n怎么做", "How：具体怎么做", Q_HOW), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(opts)
        val inputRow = Ui.row(ctx).apply { setPadding(0, dp(10f), 0, 0) }
        val edit = EditText(ctx).apply {
            hint = "问点什么…"
            setHintTextColor(Ui.TX3)
            setTextColor(Ui.TX1)
            textSize = 15f
            minimumHeight = dp(48f)
            maxLines = 3
            minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // 多行框默认把回车当换行；不设 NO_ENTER_ACTION，键盘才有发送键、回车走 ACTION_SEND
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            contentDescription = "输入自己的问题"
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
            }
            // 平时窗口不可获焦：点输入框才临时可获焦、弹键盘
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_UP) openInput()
                false
            }
        }
        Brut.field(edit)
        val send = Ui.buttonEmph(ctx, "发送") { submitTyped() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), 0, dp(12f), 0)
            minimumWidth = dp(56f)
            minimumHeight = dp(48f)
            contentDescription = "发送问题（空白不提交）"
            isEnabled = false   // 没字时置灰：空白不提交
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { send.isEnabled = s?.toString()?.isNotBlank() == true }
        })
        draft = edit
        inputRow.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        root.addView(inputRow)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, ins ->
                // 以前在这里**同步** updateViewLayout：等于在 insets 派发中途改窗口几何，键盘完全展开后
                // 那次派发会被连带掐掉，窗口停在动画中间值。现在只记账，出帧后再挪（[syncIme]）。
                if (inputOpen) root.post { syncIme() }
                // 原样返回 = 不消费：编辑框仍要拿到 IME inset 才能把光标顶到可见处
                ins
            }
        }
        // 点到面板外面只收键盘，不取消
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) closeInput()
            false
        }
    }
    private fun setFocusable(f: Boolean) {
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val watch = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.flags = if (f) (lp.flags and nf.inv()) or watch else (lp.flags or nf) and watch.inv()
        if (attached) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun imm(): InputMethodManager? = ctx.getSystemService(InputMethodManager::class.java)

    private fun openInput() {
        // 已经开着、但键盘被系统 / 用户收起过：再点输入框要能重新叫出来（字原样还在）
        if (inputOpen) {
            if (!imeVisible()) showIme()
            return
        }
        inputOpen = true
        imeLift = 0   // 新会话从「坐在底部」起算；键盘若已在，第一次 syncIme 会立刻补上缺口
        imeOver = -1
        lastDelta = 0
        setFocusable(true)
        val e = draft ?: return
        e.requestFocus()
        // 刚改成可获焦时窗口还没拿到焦点，此刻叫键盘会被丢掉 → 等真拿到焦点再叫
        if (root.hasWindowFocus()) {
            showIme()
        } else {
            val l = object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(has: Boolean) {
                    if (!has) return
                    root.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    if (focusWaiter === this) focusWaiter = null
                    if (attached && inputOpen) showIme()
                }
            }
            focusWaiter = l
            root.viewTreeObserver.addOnWindowFocusChangeListener(l)
        }
    }

    private fun closeInput() {
        if (!inputOpen) return
        inputOpen = false
        imeProbeToken++   // 还没跑的探测全部作废
        imeLift = 0
        imeOver = -1
        lastDelta = 0
        hideIme()
        // 先落回原位再交还焦点：窗口改回不可获焦后系统不一定再派发一次 inset，这里主动归位一次
        if (attached && lp.y != baseY) {
            lp.y = baseY
            runCatching { wm.updateViewLayout(root, lp) }
        }
        setFocusable(false)
    }

    private fun showIme() {
        val e = draft ?: return
        e.requestFocus()
        if (Build.VERSION.SDK_INT >= 30) e.windowInsetsController?.show(WindowInsets.Type.ime())
        else imm()?.showSoftInput(e, 0)
        probeIme()
    }

    private fun hideIme() {
        val e = draft ?: return
        imm()?.hideSoftInputFromWindow(e.windowToken, 0)
    }

    /**
     * 让浮窗**底边**停在键盘顶边上方：`lp.y = baseY + 抬升量`（gravity 是 BOTTOM，y 越大越往上）。
     *
     * 根因（本次 bug）：旧写法每一步都做 `lp.y = baseY + imeInset`，把 inset 当成「从底部起算的
     * 总高度」。但悬浮窗拿到的 ime inset 是「键盘**还压住本窗口**多少」—— 窗口一往上挪它就变小，
     * 等于把结果当输入喂回去，形成自激振荡。实测稳定 2-周期，和两张截图的像素完全对得上：
     *   面板 [78,1226][1002,1753]（y=584）读到 238 → y=331 → [78,1479][1002,2006] 读到 489 → 又回 584 …
     * 键盘 top≈1515，两个状态下输入框（y1557..1721）和「发送」都压在键盘底下，点不到 →
     * 发不出去、history 不变。
     *
     * 现在按「缺口」收敛，不再做自我指涉的赋值：读数是「窗口底边还插进键盘多少」（inset 相对
     * 本窗口 frame 计算，AOSP 一直是这个口径 —— 上面那组像素也精确对上：抬 489 时读 238、
     * 抬 236 时读 489），所以 `抬升量 += 读数` 一步就把底边顶到键盘顶；键盘还在继续升起时，
     * 下一帧读数更新再补一次。抬升量只增不减 → 不会来回抖；每一步都只补缺口 → 不会双倍抬升。
     * 最后按 [maxLift] 封顶：宁可贴近状态栏，也不会把面板顶出屏幕；键盘真收起才归零回 [baseY]。
     */
    private fun syncIme() {
        if (!attached || Build.VERSION.SDK_INT < 30) return   // WindowInsets.Type 是 API 30 起
        if (!inputOpen) { restoreBottom(); return }
        val ins = root.rootWindowInsets ?: return
        val over = ins.getInsets(WindowInsets.Type.ime()).bottom
        // 键盘在不在：个别机型给悬浮窗的 isVisible 与 inset 不同步，两处任一成立就当键盘在
        if (!(ins.isVisible(WindowInsets.Type.ime()) || over > 0)) {
            // 收起动画途中 isVisible / inset 会先归零。此刻直接落回底部，会和下一帧「键盘还在」
            // 撞成一次上下跳 —— 所以只延后**一次**确认：确认帧仍不见键盘才归位，绝不再排下一轮。
            val from = imeLift
            if (from == 0) { restoreBottom(); return }
            root.postDelayed({
                if (!attached || !inputOpen || imeLift != from) return@postDelayed   // 期间又开了键盘
                val now = root.rootWindowInsets
                val gone = now == null || !(now.isVisible(WindowInsets.Type.ime()) ||
                    now.getInsets(WindowInsets.Type.ime()).bottom > 0)
                if (gone) restoreBottom()
            }, RESTORE_MS)
            return
        }
        // 键盘在、但已经不再压住本窗口：位置已对，保持不动（别再落回底部）
        if (over <= 0) { imeOver = 0; lastDelta = 0; return }
        // 上一轮真把窗口挪了、读数却一寸没变 → 这个 inset 与窗口位置无关（键盘整高口径），
        // 直接对齐即可；否则按剩余重叠累加缺口。两种情况都只取「不低于当前」的值：
        // 只往上走，绝不会落下来抖一下，也不会双倍抬。
        val absolute = lastDelta > 0 && over == imeOver
        val target = Math.min(Math.max(imeLift, if (absolute) over else imeLift + over), maxLift())
        lastDelta = target - imeLift
        imeOver = over
        imeLift = target
        applyY(liftY(imeLift))
    }

    /** 归零并落回 [baseY]：键盘确实收起 / 输入已关掉时走这里 */
    private fun restoreBottom() {
        imeOver = -1
        lastDelta = 0
        imeLift = 0
        applyY(baseY)
    }

    /**
     * 本窗口所在 frame 的底边（屏幕坐标）。不猜「导航条是否已从 frame 里扣掉」—— 直接由视图反推：
     * `getLocationOnScreen` 给出的是面板顶边（含窗口位移），面板底边 = 顶边 + 高度 = frame 底边 - y，
     * 所以再加上当前 `lp.y` 就还原出 frame 底边。这个量与 y 无关，抬到哪一帧读都一致。
     * 还没布局完（读不到位置）才退回按「屏幕高 - 导航条」估算。
     */
    private fun frameBottom(): Int {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return if (root.height > 0 && loc[1] > 0) loc[1] + root.height + lp.y
        else ctx.resources.displayMetrics.heightPixels - Ui.navBarHeight(ctx)
    }

    /**
     * 还能往上抬多少：抬到这个量时，面板顶正好落在状态栏下沿再留 8dp。
     * 公式里已经把 [liftY] 会额外加的那 6dp 余量一起扣掉，两处口径保持一致。
     */
    private fun maxLift(): Int {
        val h = if (root.height > 0) root.height else dp(180f)
        val top = frameBottom() - Ui.statusBarHeight(ctx) - dp(8f)
        return Math.max(0, top - baseY - dp(6f) - h)
    }

    /** 抬升量 → 窗口 y：baseY 之上再留 6dp 余量（不贴着键盘边），上限见 [maxLift] */
    private fun liftY(lift: Int): Int {
        val eff = Math.min(lift, maxLift())
        if (eff <= 0) return baseY   // 一点都抬不动（极窄屏）：老实待在底部，不为 6dp 余量往上挪
        return baseY + eff + dp(6f)
    }

    /** 键盘此刻是否真的在屏幕上（API 30 以下无从查询，按「不在」处理 → 再叫一次也无害） */
    private fun imeVisible(): Boolean =
        Build.VERSION.SDK_INT >= 30 && root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /** 只在 y 真变了才改窗口：位置不变就不发 updateViewLayout，不会自己喂自己 */
    private fun applyY(y: Int) {
        if (!attached || lp.y == y) return
        lp.y = y
        runCatching { wm.updateViewLayout(root, lp) }
    }

    /**
     * 键盘展开约 200~300ms，这里在固定几个时刻补读几帧（一次性，不轮询、不循环）：
     * 万一某次 inset 派发迟到或丢了，也不会停在半路；值没变时 [syncIme] 直接返回。
     */
    private fun probeIme() {
        val token = ++imeProbeToken
        for (d in PROBE_MS) root.postDelayed({
            if (token == imeProbeToken && attached && inputOpen) syncIme()
        }, d)
    }
    private fun submitTyped() {
        val text = draft?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return   // 空白不提交，浮窗留着
        submit(text)
    }

    private fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty() || finished) return
        finished = true   // 先定终态再摘窗口：回调里再 dismiss()/show() 都不会重复触发
        remove()
        onSubmit(text)
    }

    private fun userCancel() {
        if (finished) return
        finished = true
        remove()
        onCancel()
    }

    /** 摘窗口（幂等）：收键盘、摘监听、清子 View 引用；不回调、重复调用无副作用 */
    private fun remove() {
        inputOpen = false
        imeProbeToken++
        imeLift = 0
        imeOver = -1
        lastDelta = 0
        hideIme()
        focusWaiter?.let { runCatching { root.viewTreeObserver.removeOnWindowFocusChangeListener(it) } }
        focusWaiter = null
        // 入场弹簧就地收场（停帧循环、属性写回终态）：root 下一轮会被复用，
        // 半路的 alpha 必须清掉，否则「动画中途被摘窗 + 之后系统动画关掉」会让下一轮整块隐形。
        motion?.settleAt(Anim.Visual.SHOW)
        motion = null
        // 只改标志、不 updateViewLayout：视图马上要摘，回到不可获焦只为下一轮 show() 的初始态
        lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        if (attached) {
            try { wm.removeView(root) } catch (_: Exception) {}
            attached = false
        }
        lp.y = baseY
        root.removeAllViews()   // 松开对编辑框 / 按钮的引用（复用时要重新 build）
        draft = null
    }
}
