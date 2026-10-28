package com.chun.lingzhu

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 编辑器里的原生高亮：标签、双链、代码，用 span 上色。
 *
 * ## 为什么用 span
 * 染色只走系统 API，正文一个字都不改，光标、选区、输入法候选串、撤销栈全部保持原样。
 * 换成自绘渲染器或 WebView 就得把这些推倒重来。
 *
 * ## 三条纪律
 * 1. **不碰正文**：只 [Spannable.setSpan] / [Spannable.removeSpan]。正文一变，
 *    [TextWatcher] 里就会触发保存，把半截输入写进库。
 * 2. **拼字期间不动**：输入法 compos 区间还在（拼音候选没上屏）时一个 span 都不加不删 ——
 *    在 compos 区间上 setSpan 会让部分输入法把候选串整段重置。
 * 3. **只染看得见的行**：滚动窗口上下各多留 [BLEED_LINES] 行。100 KB 的笔记滑到底
 *    也不会挂上万个 span；离屏部分的高亮在滚回来时补上。
 *
 * ## 解析不按每个字符跑
 * 结果按正文内容缓存：正文没变（滚动、重新布局）只重挂 span，不重解析；
 * 真变了才重解析，且 120ms 内的连击合并成一次。这里没有任何查库动作 ——
 * 反链、搜索各走自己的路径。
 */
object NoteHighlight {

    /**
     * 高亮配色。默认按当前主题取（[of]），换配色 / 深浅色时跟着走，不写死颜色。
     */
    data class Colors(val tag: Int, val link: Int, val code: Int, val codeBg: Int) {
        companion object {
            fun of(theme: Theme = Ui.theme): Colors =
                Colors(theme.answer, theme.palette?.primary ?: theme.heading, theme.tx3, theme.codeBg)
        }
    }

    /** 标签（`#数学`）：只改字色，不动字形 —— 加粗会让整行重排，编辑时跳动 */
    class TagSpan(color: Int) : ForegroundColorSpan(color)

    /** 链接（`[[目标]]` / `[文字](目标)`）：改色 + 下划线，和正文分开 */
    class LinkSpan(private val color: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.color = color
            tp.isUnderlineText = true
        }
    }

    /** 代码（围栏块 / 行内 / `%%注释%%`）：反色底 + 弱化字色 */
    class CodeSpan(private val bg: Int, private val fg: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            tp.bgColor = bg
            tp.color = fg
        }
    }

    /** 连击合并窗口。太小等于每字符挂一遍 span，太大高亮会滞后一步 */
    private const val THROTTLE_MS = 120L

    /** 可见行上下各多染 2 行：滚动时下一行已经带好色，不会先闪白再变色 */
    private const val BLEED_LINES = 2

    private val handler = Handler(Looper.getMainLooper())

    /** EditText → 会话。弱键：Activity 忘了 [detach] 也不会把整页钉在内存里 */
    private val sessions = WeakHashMap<EditText, Session>()

    /**
     * 装上高亮。[colors] 默认取当前主题色；重复调用会先拆掉旧的，
     * 同一个输入框不会挂两层 watcher。
     */
    fun attach(edit: EditText, colors: Colors = Colors.of()) {
        detach(edit)
        val session = Session(colors)
        val ref = WeakReference(edit)

        session.watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                session.invalidate()
                ref.get()?.let { session.schedule(it) }
            }
        }
        session.onScroll = ViewTreeObserver.OnScrollChangedListener {
            ref.get()?.let { session.schedule(it) }
        }
        session.onLayout = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (v as? EditText)?.let { session.schedule(it) }
        }

        session.watcher?.let { edit.addTextChangedListener(it) }
        session.onScroll?.let { edit.viewTreeObserver.addOnScrollChangedListener(it) }
        session.onLayout?.let { edit.addOnLayoutChangeListener(it) }
        sessions[edit] = session
        session.schedule(edit)
    }

    /** 拆掉高亮：监听、待跑的解析、自己那几类 span 一起清（别人的 span 不动） */
    fun detach(edit: EditText) {
        val session = sessions.remove(edit) ?: return
        session.watcher?.let { edit.removeTextChangedListener(it) }
        session.onScroll?.let { edit.viewTreeObserver.removeOnScrollChangedListener(it) }
        session.onLayout?.let { edit.removeOnLayoutChangeListener(it) }
        session.watcher = null
        session.onScroll = null
        session.onLayout = null
        (edit.text as? Spannable)?.let { clearOwn(it) }
    }

    /** 换配色（主题切换 / 深浅色变化时调用）。已装的话按新色重挂一遍 */
    fun setColors(edit: EditText, colors: Colors) {
        val session = sessions[edit] ?: return
        session.colors = colors
        session.schedule(edit)
    }

    /** 重新取一次主题色再重挂（外部改了主题、没显式调 [setColors] 时的兜底） */
    fun refresh(edit: EditText) {
        val session = sessions[edit] ?: return
        session.colors = Colors.of()
        session.schedule(edit)
    }

    /** 现在装着的配色；没装返回 null */
    fun colorsOf(edit: EditText): Colors? = sessions[edit]?.colors

    // ---------------- 内部 ----------------

    private class Session(var colors: Colors) {
        var watcher: TextWatcher? = null
        var onScroll: ViewTreeObserver.OnScrollChangedListener? = null
        var onLayout: View.OnLayoutChangeListener? = null

        /** 上次解析时的正文；null = 正文变了，下次要重解析 */
        private var parsedText: String? = null
        private var spans: List<NoteSyntax.Span> = emptyList()

        /** 已经挂上的窗口，用来跳过「窗口没变」的重复重挂 */
        private var lastFrom = -1
        private var lastTo = -1
        private var lastColors: Colors? = null
        private var lastSpans: List<NoteSyntax.Span>? = null

        private var pending = false
        private var awaitingLayout = false

        fun invalidate() {
            parsedText = null
        }

        fun schedule(edit: EditText) {
            if (pending) return
            pending = true
            val ref = WeakReference(edit)
            handler.postDelayed({
                pending = false
                ref.get()?.let { apply(it) }
            }, THROTTLE_MS)
        }

        fun apply(edit: EditText) {
            val sp = (edit.text as? Spannable) ?: return
            // 拼音还在候选框里：这一轮整段跳过，上屏后 watcher 会再来一次
            if (isComposing(sp)) return

            val layout = edit.layout
            if (layout == null || layout.lineCount <= 0) {
                // 还没排版，算不出可见行。等这一帧布局完成后补一次，不做全篇兜底染色
                if (!awaitingLayout) {
                    awaitingLayout = true
                    edit.post {
                        awaitingLayout = false
                        apply(edit)
                    }
                }
                return
            }

            val src = sp.toString()
            if (src != parsedText) {
                parsedText = src
                spans = NoteSyntax.spans(src)
            }

            val (from, to) = visibleRange(edit, layout, sp.length)
            paint(sp, from, to)
        }

        /** 重挂窗口 [from, to) 里的 span。窗口外的旧 span 一起清掉（滚回来会重挂） */
        private fun paint(sp: Spannable, from: Int, to: Int) {
            if (from == lastFrom && to == lastTo && colors == lastColors && spans === lastSpans) return
            clearOwn(sp)
            lastFrom = from
            lastTo = to
            lastColors = colors
            lastSpans = spans
            val c = colors
            for (s in spans) {
                if (s.start >= s.end || s.start < 0 || s.end > sp.length) continue
                if (s.end <= from || s.start >= to) continue
                val span: Any = when (s.kind) {
                    NoteSyntax.Kind.TAG -> TagSpan(c.tag)
                    NoteSyntax.Kind.LINK -> LinkSpan(c.link)
                    NoteSyntax.Kind.CODE -> CodeSpan(c.codeBg, c.code)
                }
                sp.setSpan(span, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** 可见字符区间：按 Layout 的滚动位置算，上下各留 [BLEED_LINES] 行 */
    private fun visibleRange(edit: EditText, layout: Layout, len: Int): Pair<Int, Int> {
        val top = Math.max(0, edit.scrollY - edit.paddingTop)
        val span = Math.max(0, edit.height - edit.paddingTop - edit.paddingBottom)
        val first = (layout.getLineForVertical(top) - BLEED_LINES).coerceAtLeast(0)
        val last = (layout.getLineForVertical(top + span) + BLEED_LINES).coerceAtMost(layout.lineCount - 1)
        val from = layout.getLineStart(first).coerceIn(0, len)
        val to = layout.getLineEnd(Math.max(first, last)).coerceIn(from, len)
        return Pair(from, to)
    }

    /** 输入法是否正在拼字（compos 区间存在）。取不到就当没在拼 */
    private fun isComposing(sp: Spannable): Boolean = try {
        val start = BaseInputConnection.getComposingSpanStart(sp)
        val end = BaseInputConnection.getComposingSpanEnd(sp)
        start in 0 until end
    } catch (_: Exception) {
        false
    }

    /** 只清自己挂的三类 span，别人的（用户 / 系统 / 库）一个都不动 */
    private fun clearOwn(sp: Spannable) {
        if (sp.isEmpty()) return
        for (s in sp.getSpans(0, sp.length, Any::class.java)) {
            if (s is TagSpan || s is LinkSpan || s is CodeSpan) sp.removeSpan(s)
        }
    }
}
