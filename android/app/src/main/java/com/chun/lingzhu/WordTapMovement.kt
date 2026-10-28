package com.chun.lingzhu

import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView

/**
 * 译文里「每个英文词都能点」（v2.9，对齐电脑版 `translate.html:71-96` 的 `linkifyWords`）。
 *
 * 做法跟电脑版一致：用 [TranslationWord.WORD] 在**已经排好版**的文本上找词，套一层 [WordSpan]；
 * 电脑版是鼠标悬停（`span.w` + `#wtip`），手机没有 hover，改成点一下（[Tap]）。
 *
 * 几条边界（都按需求点名的来）：
 *  - **链接优先**：落在 [Links.LinkSpan] 上的点仍然跳链接，不查词；
 *  - 行内代码 / 公式 / [文字](链接) 里的词不套 span（它们不是自然语言）；
 *  - 长按选字照旧：有选区时一次都不触发；手指移动超过 touch slop（在滚动）也不触发；
 *  - 只认拉丁词（[TranslationWord.hits] 里那条线），中文译文不套 span —— 词典本来就是英汉的。
 *
 * 纯文本逻辑在 [TranslationWord]（可单测），这里只做 span / 命中测试 / 手势。
 */
object WordTap {

    /**
     * 可点的词。[start] 是它在正文里的字符 offset（取语境、二次取词都靠它，见 [TranslationWord.wordAt]）。
     * 只加下划线、不改颜色 —— 与 [Links.LinkSpan] 同一套克制做法（灰度屏上靠下划线认）。
     * 电脑版是点状下划线（`#body .w { border-bottom: 1px dotted }`）；Android 的 [CharacterStyle]
     * 只能整段实线下划线，这里就实线，颜色不动。
     */
    class WordSpan(val word: String, val start: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) { tp.isUnderlineText = true }
    }

    /** 把词 span 套到一份已经排好的文本上（幂等：已经套过的范围不再重复套） */
    fun mark(src: CharSequence): CharSequence {
        val sp: Spannable = when (src) {
            is SpannableStringBuilder -> src
            is Spannable -> src
            else -> SpannableString(src)
        }
        apply(sp)
        return sp
    }

    fun apply(sp: Spannable) {
        if (sp.isEmpty()) return
        val blocked = blockedRanges(sp)
        val have = sp.getSpans(0, sp.length, WordSpan::class.java).map { sp.getSpanStart(it) until sp.getSpanEnd(it) }
        for (h in TranslationWord.hits(sp.toString(), blocked)) {
            if (have.any { r -> h.start < r.last + 1 && r.first < h.end }) continue
            sp.setSpan(WordSpan(h.word, h.start), h.start, h.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * 不能再加词 span 的区间：链接、行内代码（[BackgroundColorSpan] / 等宽字体）、公式（[ReplacementSpan]）、
     * 以及公式那几行的缩进段（[LeadingMarginSpan]）。宁可少套一个词，也不要在公式里点出「frac」这种词。
     */
    private fun blockedRanges(sp: Spannable): List<IntRange> {
        val out = ArrayList<IntRange>()
        fun add(list: Array<out Any>) {
            for (s in list) {
                val a = sp.getSpanStart(s); val b = sp.getSpanEnd(s)
                if (a >= 0 && b > a) out.add(a until b)
            }
        }
        add(sp.getSpans(0, sp.length, Links.LinkSpan::class.java))
        add(sp.getSpans(0, sp.length, BackgroundColorSpan::class.java))
        add(sp.getSpans(0, sp.length, TypefaceSpan::class.java))
        add(sp.getSpans(0, sp.length, ReplacementSpan::class.java))
        add(sp.getSpans(0, sp.length, LeadingMarginSpan::class.java))
        return out
    }

    /**
     * 手指下的词（对齐 `Links.spanAt` 的算法：padding / scroll / 行左右边界都要算，否则点边缘会串行）。
     * 先看落点有没有已经套好的词 span；流式重排后 span 可能还没补上，就按 offset 现取一个 ——
     * 两种来源都带上真实 offset，绝不靠猜。
     */
    fun hitAt(w: TextView, buf: Spannable, e: MotionEvent): WordSpan? {
        val off = offsetAt(w, e) ?: return null
        // getSpans(off, off) 对零长区间是**两端都算**的：词 span [5,9) 在 off=9（后面那个空格）也会被取到。
        // 点词的逻辑要严格 `start <= off < end`，否则在词后的空白 / 标点上点一下也会去查词。
        buf.getSpans(off, off, WordSpan::class.java)
            .firstOrNull { off >= buf.getSpanStart(it) && off < buf.getSpanEnd(it) }
            ?.let { return it }
        if (off >= buf.length) return null
        val h = TranslationWord.wordAt(buf.toString(), off) ?: return null
        // 点中的位置是链接 / 代码 / 公式就不查词（那几块不是自然语言）
        if (blockedRanges(buf).any { b -> h.start <= b.last && b.first <= h.end - 1 }) return null
        return WordSpan(h.word, h.start)
    }

    /** 手指按在哪个字符上；在行外 / 组件外返回 null（与 Links 的命中测试同一口径） */
    private fun offsetAt(w: TextView, e: MotionEvent): Int? {
        val layout = w.layout ?: return null
        val x = e.x.toInt() - w.totalPaddingLeft + w.scrollX
        val y = e.y.toInt() - w.totalPaddingTop + w.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        return layout.getOffsetForHorizontal(line, x.toFloat())
    }

    /**
     * 可选中文本 + 点词 + 点链接（替代 [Links.SelectableTap] 用在翻译窗正文上）。
     *
     * 与 [Links.SelectableTap] 同构：只有真的按在链接 / 词上才接管这次触摸，其余一律交回
     * [ArrowKeyMovementMethod]（长按选字、键盘移动都还能用）。
     * 几条防误触：手指移动超过 slop（在滚动 / 拖选）不触发；有选区时不触发；
     * **按住超过 [ViewConfiguration.getLongPressTimeout] 也不触发** —— 那一下是长按选字，
     * 就算没选出字来，UP 也不能拿去查词。
     *
     * ACTION_DOWN 先交给基类、再自己记一笔「按在词 / 链接上」：长按选字要用的触摸状态
     * （[android.text.method.Touch] 的 DragState、父视图不拦截）由基类建立，不能让点词把它吞掉。
     */
    class Tap(private val onWord: (WordSpan) -> Unit) : ArrowKeyMovementMethod() {
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var pending: Any? = null     // Links.LinkSpan 或 WordSpan

        override fun onTouchEvent(w: TextView, buf: Spannable, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pending = null
                    downAt = e.eventTime
                    val base = super.onTouchEvent(w, buf, e)
                    if (w.hasSelection()) return base
                    val off = offsetAt(w, e) ?: return base
                    val link = buf.getSpans(off, off, Links.LinkSpan::class.java).firstOrNull()
                    val word = if (link == null) hitAt(w, buf, e) else null
                    if (link == null && word == null) return base
                    pending = link ?: word
                    downX = e.x; downY = e.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pending == null) return super.onTouchEvent(w, buf, e)
                    // 在滚动 / 拖选 / 已经按成长按：这次不算点词，后面的交给基类
                    if (moved(w, e) || heldLong(e)) { pending = null; return super.onTouchEvent(w, buf, e) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val p = pending ?: return super.onTouchEvent(w, buf, e)
                    pending = null
                    if (w.hasSelection() || moved(w, e) || heldLong(e)) {
                        // 长按（有选区或没选区）/ 拖动都不查词；收尾走基类，既有选区不动
                        super.onTouchEvent(w, buf, e)
                        return true
                    }
                    when (p) {
                        is Links.LinkSpan -> p.onClick(w)
                        is WordSpan -> onWord(p)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pending = null
                    return super.onTouchEvent(w, buf, e)
                }
            }
            return super.onTouchEvent(w, buf, e)
        }

        private fun moved(w: TextView, e: MotionEvent): Boolean {
            val slop = ViewConfiguration.get(w.context).scaledTouchSlop
            return Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop
        }

        /** 这一下已经按住够久了（与系统长按判定同一常量：UP 时也不能当点击） */
        private fun heldLong(e: MotionEvent): Boolean =
            e.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()
    }
}
