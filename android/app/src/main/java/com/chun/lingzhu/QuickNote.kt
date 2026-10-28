package com.chun.lingzhu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v2.4 速记浮层：截完图直接在这上面写一句话、点个标签、保存，全程不跳 App。
 *
 * ## 为什么用浮层而不是开 Activity
 * 目标 ≤5 秒、不打断当前 App。开 Activity 会切走前台、清掉当前输入焦点，
 * 用户回到原 App 还得重新点一下输入框 —— 这一来一回就超过 5 秒了。
 *
 * ## 键盘是这个界面最大的坑
 * TYPE_APPLICATION_OVERLAY 窗口默认 FLAG_NOT_FOCUSABLE，叫不出键盘。
 * 照答问面板那套：平时不可获焦（底下的 App 照常收按键），
 * 要打字时临时改成可获焦，并**等窗口真拿到焦点再叫 IME** ——
 * 窗口刚改完标志还没拿到焦点时调 showSoftInput，系统会直接丢掉这次请求。
 *
 * ## 保存语义
 * 留言可以为空（截了图就算一条笔记）；取消时如果已经改过内容，要二次确认，
 * 否则误触一次就把刚截的图弄丢了。
 *
 * ## 图从哪来
 * 速记**只截一次**：浮窗藏好那一刻的完整原始帧。预览、落盘、失败重试都用这一张，
 * 保存时不再补截 —— 补截到的是浮层盖上去之后的画面，不是用户当时看到的那一屏。
 */
class QuickNote(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    /**
     * 落盘这条笔记（含附件）。**返回是否真的存上了** —— 以前这里是 Unit，调用方没存上也会
     * 当成成功，浮层照样关、输入照样清，用户写的字和截的图就一起没了。
     * 返回 false 时由本类保留文字 / 截图、不关浮层，让用户能重试。
     *
     * ## 位图所有权（契约）
     * 传进来的是本类持有的那张**原图**，只是**借用**给回调：服务只读它（写盘 / 压缩），
     * **不得 recycle**，也不能留着。回调返回后本类不再保证它还活着 —— 成功即关浮层回收，
     * 失败则留到下一次重试或用户收起。
     */
    private val onSaved: (Note.Item, Bitmap?) -> Boolean,
    private val onClosed: () -> Unit,
) {

    /**
     * 已关闭。防重复收尾：原图和预览图只由 [releaseShot] 回收，close() 与 show() 的失败路径各走一次；
     * 若回调里又回到 close()，不能对着已回收的图再 recycle 一遍，也不能把 onClosed 发两次。
     * 所有 SAVED 事件都在主线程，所以这个标志不需要加锁。
     */
    private var closed = false

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val lp: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM or Gravity.START }

    private var attached = false
    /** 被上层让路中（见 [setHidden]）：窗口透明 + 不可触摸，草稿照样留着 */
    private var hidden = false
    /**
     * 打开浮层那一刻截到的**完整原始帧**，落盘用的就是它，生命周期归本类（见 [releaseShot]）。
     *
     * 以前这里只存浮层用的预览小图，保存时再重新截一张全尺寸的 —— 那时候浮层已经盖在上面，
     * 底下的画面早就变了，存下的不是用户当时看到的那一屏。现在只截一次，原图留到这一轮结束。
     */
    private var shot: Bitmap? = null
    /** 只给浮层里那张 84×110dp 缩略图用的缩小版；原图本来就够小时它就是 [shot] 本身 */
    private var preview: Bitmap? = null
    /**
     * 把 [preview] 画出来的那张 ImageView。回收前必须先把它的图摘掉：
     * 窗口摘除失败时视图还挂在窗口里，下一帧就会拿一张已回收的位图去画，主线程直接崩
     * （"trying to use a recycled bitmap"）。只在主线程写它。
     */
    private var thumbRef: ImageView? = null
    private var draft: EditText? = null
    private val picked = LinkedHashSet<String>()
    private var dirty = false
    private var tagRowRef: LinearLayout? = null
    /**
     * 入场弹簧。**只有入场**：关浮层（保存成功 / 收起 / 弹层失败）必须当场摘窗、当场把图还给
     * [releaseShot] 并回调 [onClosed] —— 退场动画会把这三件事推迟百余毫秒，那段时间里既有一块
     * 盖着屏幕的窗口，也会让上一轮的截图和 `quickNote` 单例门闩一起多活一会儿。
     */
    private var motion: AnimGlue.SpringAnimation? = null

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 弹出浮层。[bmp] 是打开这一刻截到的完整原始帧，**所有权随之交给本类**（[releaseShot] 回收）；
     * 为 null = 没截到图，退化成纯文字速记。
     */
    fun show(bmp: Bitmap?) {
        releaseShot()
        closed = false
        // 上一轮若被让路过：把触摸标志清回来（alpha 由下面的入场 / applyVisual 负责）。不重建窗口，只改标志。
        hidden = false
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        shot = bmp
        preview = previewOf(bmp)
        try {
            build()
            // 入场：**只淡入**。窗口是 MATCH_PARENT × WRAP_CONTENT 贴在底部，视图位移会被窗口
            // surface 裁掉，缩放又会让截图缩略图走形；alpha 淡入是唯一不改变版式的克制做法。
            // 只有「这次真的要加窗口」才摆起点：已经在窗口里时再淡一次是白闪。
            val adding = !attached
            val m = if (adding && ValueAnimator.areAnimatorsEnabled())
                AnimGlue.SpringAnimation(root, Anim.Spring.enter).also { it.settleAt(Anim.Visual.HIDDEN) }
            else {
                with(AnimGlue) { root.applyVisual(Anim.Visual.SHOW) }   // root 复用：先清掉上一轮姿态
                null
            }
            if (adding) {
                wm.addView(root, lp)
                attached = true
            }
            motion = m
            m?.to(Anim.Visual.SHOW)
            if (!hidden) openInput()   // 正在让路：别对着一个看不见的窗口叫键盘
        } catch (e: Exception) {
            // 没弹出来 / 弹到一半崩了：图是本类持有的，得还回去，否则再没人回收它；
            // 半挂上的窗口也要摘掉，不能留一个没人管的浮层
            android.util.Log.w("QuickNote", "浮层打不开", e)
            motion?.settleAt(Anim.Visual.SHOW)
            motion = null
            with(AnimGlue) { root.applyVisual(Anim.Visual.SHOW) }
            if (attached) {
                try { wm.removeView(root) } catch (ignored: Exception) {}
                attached = false
            }
            closed = true
            releaseShot()
            onClosed()
            throw e
        }
    }

    fun close() {
        // 已经收过尾（保存成功时）就不再回收第二次
        if (closed) return
        // 入场淡入就地收场：root 里那张缩略图马上要被回收，半路的 alpha 也不能留给下一轮 show()
        motion?.settleAt(Anim.Visual.SHOW)
        motion = null
        if (attached) {
            hideIme()
            try { wm.removeView(root) } catch (e: Exception) {
                // 摘不掉也要记一笔：这条路径下视图仍在窗口里，回收位图前必须先断引用（见 releaseShot）
                android.util.Log.w("QuickNote", "摘浮层失败", e)
            }
            attached = false
        }
        closed = true
        releaseShot()
        onClosed()
    }


    /**
     * 被上层浮窗 / 截屏 / 前台卡片页临时盖住：窗口变透明且**不可触摸**，但草稿与已选标签全部留着。
     *
     * 为什么不是 close()：关掉就把用户正在写的内容丢了（[releaseShot] 还会回收截图）。
     * 浮层是长期复用的同一个视图，隐藏 - 恢复只是改 alpha + 触摸标志，不重建、不丢字。
     * 键盘在隐藏前先收掉 —— 一个看不见却还挂着输入焦点的窗口比什么都糟（会吞掉别的页面的返回键）。
     */
    fun setHidden(h: Boolean) {
        if (hidden == h) return
        hidden = h
        if (h) hideIme()
        // 入场淡入正在跑的话先就地收场（它每帧写 root.alpha，不收场会把 0 覆盖回去）
        if (h) { motion?.settleAt(Anim.Visual.SHOW); motion = null }
        val a = if (h) 0f else 1f
        if (root.alpha != a) root.alpha = a
        val nf = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val want = if (h) lp.flags or nf else lp.flags and nf.inv()
        if (want != lp.flags) {
            lp.flags = want
            if (attached) runCatching { wm.updateViewLayout(root, lp) }
        }
    }

    // ---------------- 布局 ----------------

    private fun build() {
        root.removeAllViews()
        content.removeAllViews()

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = Ui.panelBg(ctx, prefs.baseTheme(), 16f)
        }
        sheet.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))

        // 标题行
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            text = "截图速记"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TX1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Brut.label(ctx, "收起", 12f, Ui.TX2).apply {
            setPadding(dp(10f), dp(6f), dp(6f), dp(6f))
            contentDescription = "关闭速记"
            setOnClickListener { confirmClose() }
        })
        sheet.addView(head)

        // 缩略图 + 状态
        val media = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(8f))
        }
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = if (shot == null) "没有截图" else "截图预览"
        }
        val pv = preview
        if (pv != null) {
            thumb.setImageBitmap(pv)
        } else {
            thumb.setBackgroundColor(Color.LTGRAY)
        }
        media.addView(thumb, LinearLayout.LayoutParams(dp(84f), dp(110f)).apply { marginEnd = dp(12f) })
        thumbRef = thumb

        // 尺寸报的是**要落盘的那张**（原图），不是缩略图：缩略图只是显示用的
        val b = shot
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(ctx).apply {
            text = if (b == null) "没有图片（纯文字）" else b.width.toString() + " × " + b.height
            textSize = 12f
            setTextColor(Ui.TX3)
        })
        // 保存失败的回执：默认不占行高，save() 失败时才亮
        saveStatus = TextView(ctx).apply {
            text = ""
            textSize = 11.5f
            setTextColor(Ui.TX2)
            setPadding(0, dp(4f), 0, 0)
            visibility = View.GONE
        }
        info.addView(saveStatus)
        media.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        sheet.addView(media)

        // 标签行
        val tagHead = TextView(ctx).apply {
            text = "标签"
            textSize = 12f
            setTextColor(Ui.TX3)
            setPadding(0, dp(2f), 0, dp(6f))
        }
        sheet.addView(tagHead)

        val tagRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tagRowRef = tagRow
        val hScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tagRow)
        }
        sheet.addView(hScroll)
        buildTagChips(tagRow)

        // 留言框
        val input = EditText(ctx).apply {
            hint = "留一句话…（可直接打 #标签）"
            textSize = 15f
            setTextColor(Ui.TX1)
            setHintTextColor(Ui.TX3)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            minLines = 1
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                save()
                true
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    dirty = true
                }
            })
        }
        Brut.field(input)
        draft = input
        sheet.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        // 保存
        val saveBtn = TextView(ctx).apply {
            text = "保存"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
            setTextColor(Brut.on(Brut.MINT))
            contentDescription = "保存这条笔记"
            setOnClickListener { save() }
        }
        Brut.button(saveBtn, Brut.MINT)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10f), 0, 0)
        }
        row.addView(saveBtn)
        sheet.addView(row)

        content.addView(sheet)
        root.addView(content)
    }

    /**
     * 保存失败的回执（只有一行字，不弹新窗口 —— 浮层本来就盖在上面）。
     * 保存成后清空；save() 失败时它原样留着，用户能看见为什么没关浮层。
     */
    private var saveStatus: TextView? = null

    private fun setStatus(msg: String) {
        val v = saveStatus ?: return
        v.text = msg
        v.visibility = View.VISIBLE
    }

    /** 画标签 chip：最近常用在前，选中的高亮 */
    private fun buildTagChips(row: LinearLayout) {
        row.removeAllViews()
        val names = prefs.noteRecentTags()
        if (names.isEmpty()) {
            row.addView(Brut.label(ctx, "还没有标签，直接打字加 #", 11f, Ui.TX3).apply {
                setPadding(0, dp(6f), 0, dp(6f))
            })
            return
        }
        names.forEach { name ->
            val on = name in picked
            row.addView(TextView(ctx).apply {
                text = (if (on) "✓ " else "#") + name
                textSize = 12.5f
                setTextColor(if (on) Brut.on(Brut.MINT) else Ui.TX1)
                background = if (on) Brut.pressable(ctx, Brut.MINT, 0f) else Brut.pressable(ctx, Brut.CARD, 0f)
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                contentDescription = "标签 " + name + if (on) "，已选" else "，未选"
                setOnClickListener {
                    if (on) picked.remove(name) else picked.add(name)
                    dirty = true
                    tagRowRef?.let { buildTagChips(it) }
                }
                tag = "chip"
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6f) })
        }
    }

    // ---------------- 焦点与键盘 ----------------

    private fun setFocusable(f: Boolean) {
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.flags = if (f) lp.flags and nf.inv() else lp.flags or nf
        if (attached) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    private fun imm() = ctx.getSystemService(InputMethodManager::class.java)

    private fun openInput() {
        setFocusable(true)
        val e = draft ?: return
        e.requestFocus()
        if (root.hasWindowFocus()) showIme()
        else root.viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(has: Boolean) {
                if (!has) return
                root.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                showIme()
            }
        })
    }

    private fun showIme() {
        val e = draft ?: return
        e.requestFocus()
        if (android.os.Build.VERSION.SDK_INT >= 30) e.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
        else imm()?.showSoftInput(e, 0)
    }

    private fun hideIme() {
        val e = draft ?: return
        imm()?.hideSoftInputFromWindow(e.windowToken, 0)
    }

    // ---------------- 动作 ----------------

    private fun confirmClose() {
        if (!dirty) { close(); return }
        val dialog = ThemeDialogs.builder(ctx)
            .setMessage("这条还没保存，丢掉？")
            .setPositiveButton("丢掉") { _, _ -> close() }
            .setNegativeButton("继续写", null)
            .create()
        // ctx 是悬浮球 Service，不存在 Activity token；确认框同速记使用悬浮窗口。
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /**
     * 回收这一轮的两张图。原图和（独立的）预览图**都**归本类：onSaved 只是借用，
     * 存上了也不许它 recycle。
     *
     * 顺序不能反：先断 ImageView 对图的引用，再回收。否则 [close] 的 removeView 失败时
     * ImageView 还捏着同一个 BitmapDrawable，下一帧画的就是一张废图 —— 线上
     * "trying to use a recycled bitmap" 的来源。调用点全在主线程（show / close / save 的点击回调），
     * 无需再 post。
     */
    private fun releaseShot() {
        thumbRef?.setImageDrawable(null)
        thumbRef = null
        val pv = preview
        preview = null
        val b = shot
        shot = null
        // 原图本来就够小时预览就是它自己，别回收两遍
        if (pv != null && pv !== b) runCatching { pv.recycle() }
        if (b != null) runCatching { b.recycle() }
    }

    /** 缩略图用的小图：短边照旧的 360。落盘那张不受影响，仍是 [shot] 原图 */
    private fun previewOf(b: Bitmap?): Bitmap? {
        if (b == null) return null
        val short = minOf(b.width, b.height)
        if (short <= PREVIEW_SHORT) return b
        val k = PREVIEW_SHORT.toFloat() / short
        val w = (b.width * k + 0.5f).toInt().coerceAtLeast(1)
        val h = (b.height * k + 0.5f).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(b, w, h, true) }.getOrNull() ?: b
    }

    private fun save() {
        val text = draft?.text?.toString().orEmpty().trim()
        val now = System.currentTimeMillis()
        val body = NoteSyntax.appendTags(text, picked.toList())
        val item = Note.create(body, now, source = "quick")
        hideIme()
        setStatus("")   // 上一次失败的回执先收掉：重试结果出来前不挂着旧结论
        // 落盘用的就是打开浮层那一刻冻结下来的原图，**不再在这里重新截一张**：
        // 浮层盖上去以后底下早就变了，补截回来的不是用户看到的那一屏。
        // 位图只是借给 onSaved（写盘 / 压缩），本类不在这里回收它。
        // 只有真存上了才收浮层：失败时文字、标签、截图全部原样留着，dirty 也保持 true，
        // 用户要么重试、要么点「收起」二次确认 —— 不会白写。
        // 回调抛异常也按失败处理：草稿不能因为服务里一个 bug 就跟着浮层一起没了。
        val ok = try {
            onSaved(item, shot)
        } catch (e: Exception) {
            android.util.Log.w("QuickNote", "保存回调出错", e)
            false
        }
        if (!ok) {
            setStatus("没保存上 · 点「保存」重试，文字和截图都还在")
            return
        }
        dirty = false
        // 成功即收尾：摘窗口 + 断开 ImageView + 回收原图 / 预览 + 发一次 onClosed，统一走 close()
        saveStatus = null
        close()
    }
}

/** 浮层缩略图的短边。只影响显示，落盘一律用原图 */
private const val PREVIEW_SHORT = 360
