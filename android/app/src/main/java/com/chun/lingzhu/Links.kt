package com.chun.lingzhu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.method.BaseMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * 跳转（v1.56）：回答 / 翻译里的网址、电话、邮箱、[文字](链接) 点一下就打开。
 * 找链接是纯字符串运算（单测直接跑）；打开只认 http(s) / tel / mailto —— 模型写出 javascript: / intent: / file: 一律不当链接。
 * 链接不改颜色、只加下划线（灰度屏上靠下划线认），[文字](链接) 在文字后加「↗」。
 */
object Links {
    data class Hit(val start: Int, val end: Int, val target: String)

    /** Markdown 链接 [文字](地址) */
    val MD = Regex("\\[([^\\]\\n]+)]\\(\\s*<?([^)\\s>]+)>?\\s*\\)")
    private val URL = Regex("(?i)\\b(?:https?://|www\\.)[a-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
    private val EMAIL = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9])")
    /** 手机号（可带 +86、可用空格/横线分段）、带区号的座机、400/800 —— 不认短号，免得把算式里的数当电话 */
    private val PHONE = Regex("(?<![0-9A-Za-z.+])(?:(?:\\+?86[- ]?)?1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}|0\\d{2,3}-\\d{7,8}|[48]00-?\\d{3}-?\\d{4})(?![0-9A-Za-z])")

    /** 纯文本里的网址 / 邮箱 / 电话，按位置排好、互不重叠（网址优先：网址里的数字不再当电话） */
    fun find(s: CharSequence): List<Hit> {
        val out = ArrayList<Hit>()
        fun add(start: Int, end: Int, target: String) {
            if (end > start && out.none { start < it.end && it.start < end }) out.add(Hit(start, end, target))
        }
        for (m in URL.findAll(s)) {
            val end = m.range.first + trimUrl(m.value).length
            add(m.range.first, end, normalize(s.substring(m.range.first, end)))
        }
        for (m in EMAIL.findAll(s)) add(m.range.first, m.range.last + 1, "mailto:" + m.value)
        for (m in PHONE.findAll(s)) add(m.range.first, m.range.last + 1, "tel:" + m.value.filter { it.isDigit() || it == '+' })
        return out.filter { safe(it.target) }.sortedBy { it.start }
    }

    /** 去掉网址末尾粘上的标点、以及没配对的右括号（「见 https://a.com/x)。」） */
    fun trimUrl(u: String): String {
        var s = u
        while (s.isNotEmpty()) {
            val c = s.last()
            val drop = c in ".,;:!?'\"" ||
                (c == ')' && s.count { it == '(' } < s.count { it == ')' }) ||
                (c == ']' && s.count { it == '[' } < s.count { it == ']' })
            if (!drop) break
            s = s.dropLast(1)
        }
        return s
    }

    /** www. 补 https://，裸邮箱补 mailto:，其余原样 */
    fun normalize(u: String): String {
        val t = u.trim()
        return when {
            t.startsWith("www.", true) -> "https://$t"
            !t.contains(':') && EMAIL.matches(t) -> "mailto:$t"
            else -> t
        }
    }

    fun safe(target: String): Boolean {
        val i = target.indexOf(':')
        if (i <= 0) return false
        return when (target.substring(0, i).lowercase()) {
            "http", "https" -> target.length > i + 3 && target.substring(i).startsWith("://") && target.length > i + 3 + 1
            "tel" -> target.length > i + 1 && target.substring(i + 1).all { it.isDigit() || it == '+' || it == '-' }
            "mailto" -> EMAIL.matches(target.substring(i + 1))
            else -> false
        }
    }

    /** 给人看的：网址只报域名（防止链接文字写一个、实际去另一个），电话/邮箱报原文 */
    fun label(target: String): String = when {
        target.startsWith("tel:", true) -> target.substring(4)
        target.startsWith("mailto:", true) -> target.substring(7)
        else -> target.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    }

    // ---------------- Android 部分 ----------------

    /** 打开链接成功后回调（面板用它自动收起，给打开的网页 / 拨号盘让位置） */
    @Volatile var onOpened: (() -> Unit)? = null

    class LinkSpan(val target: String) : ClickableSpan() {
        override fun onClick(widget: View) = open(widget.context, target)
        override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }   // 不换颜色，灰度下靠下划线认
    }

    /** 已有 LinkSpan 的地方（[文字](链接)）不重复加 */
    fun apply(sp: Spannable) {
        val have = sp.getSpans(0, sp.length, LinkSpan::class.java).map { sp.getSpanStart(it) until sp.getSpanEnd(it) }
        for (h in find(sp.toString())) {
            if (have.any { h.start < it.last + 1 && it.first < h.end }) continue
            sp.setSpan(LinkSpan(h.target), h.start, h.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun linkify(s: CharSequence): CharSequence = SpannableString(s).also { apply(it) }

    fun open(ctx: Context, target: String) {
        if (!safe(target)) return
        val uri = Uri.parse(target)
        val tel = uri.scheme.equals("tel", true)
        val intent = when {
            tel -> Intent(Intent.ACTION_DIAL, uri)                     // 只打开拨号盘，不会直接拨出去
            uri.scheme.equals("mailto", true) -> Intent(Intent.ACTION_SENDTO, uri)
            else -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            Toast.makeText(ctx, (if (tel) "打开拨号盘（不会直接拨出）：" else "正在打开：") + label(target), Toast.LENGTH_SHORT).show()
            onOpened?.invoke()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "手机上没有能打开它的应用：" + label(target), Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(ctx, "系统不让打开：" + label(target), Toast.LENGTH_LONG).show()
        }
    }

    /** 手指下是不是链接 */
    private fun spanAt(w: TextView, buf: Spannable, e: MotionEvent): LinkSpan? {
        val layout = w.layout ?: return null
        val x = e.x.toInt() - w.totalPaddingLeft + w.scrollX
        val y = e.y.toInt() - w.totalPaddingTop + w.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        return buf.getSpans(off, off, LinkSpan::class.java).firstOrNull()
    }

    /** 只管「点链接」：不像 LinkMovementMethod 那样让 TextView 自己滚动（面板正文由外层 ScrollView 滚） */
    object Tap : BaseMovementMethod() {
        override fun onTouchEvent(w: TextView, buf: Spannable, e: MotionEvent): Boolean {
            val a = e.actionMasked
            if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP) return false
            val span = spanAt(w, buf, e) ?: return false
            if (a == MotionEvent.ACTION_UP) span.onClick(w)
            return true
        }
    }

    /** 可选中文字的 TextView（翻译窗）：点链接跳转，其余照常选字 */
    object SelectableTap : ArrowKeyMovementMethod() {
        override fun onTouchEvent(w: TextView, buf: Spannable, e: MotionEvent): Boolean {
            val a = e.actionMasked
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) {
                val span = spanAt(w, buf, e)
                if (span != null && !w.hasSelection()) { if (a == MotionEvent.ACTION_UP) span.onClick(w); return true }
            }
            return super.onTouchEvent(w, buf, e)
        }
    }
}
