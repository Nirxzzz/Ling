package com.chun.lingzhu

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 够用的 Markdown：## 标题加粗放大、**加粗**、`代码`、- 列表 → •、[文字](链接) 和裸网址/电话/邮箱可点（见 Links）。
 * v1.58：公式（$…$ / $$…$$ / \(…\) / \[…\]）交给 MathText 排版；$$ 单独成段的缩进显示，里面的 \\ 换行。
 * 流式刷新按当前完整文本重新排版；标题和列表正则跨行、跨次渲染复用。
 */
object Md {
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val CODE = Regex("`([^`]+)`")
    private val HEADING = Regex("^(#{1,4})\\s+")
    private val LIST = Regex("^(\\s*)[-*+]\\s+")

    /** headingColor = 0 表示标题跟正文同色；codeBg = 行内代码底色 */
    fun render(src: String, headingColor: Int = 0, codeBg: Int = 0x33FFFFFF): CharSequence {
        val out = SpannableStringBuilder()
        val lines = joinDisplayMath(src.replace("\r\n", "\n").split('\n'))
        for ((i, raw) in lines.withIndex()) {
            var line = raw
            val dm = DISPLAY.matchEntire(line.trim())
            if (dm != null) {
                val start = out.length
                MathText.append(out, MathText.parse(dm.groupValues[1].ifEmpty { dm.groupValues[2] }).let(::trimRows))
                out.setSpan(LeadingMarginSpan.Standard(displayIndent), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.06f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (i < lines.size - 1) out.append('\n')
                continue
            }
            var headerScale = 0f
            val h = HEADING.find(line)
            if (h != null) {
                headerScale = when (h.groupValues[1].length) { 1 -> 1.25f; 2 -> 1.12f; else -> 1.04f }
                line = line.substring(h.range.last + 1)
            } else {
                val li = LIST.find(line)
                if (li != null) line = li.groupValues[1] + "• " + line.substring(li.range.last + 1)
            }
            val start = out.length
            appendInline(out, line, codeBg)
            if (headerScale > 0f) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(headerScale), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (headingColor != 0) out.setSpan(ForegroundColorSpan(headingColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < lines.size - 1) out.append('\n')
        }
        Links.apply(out)
        return out
    }

    private val DISPLAY = Regex("""\$\$(.*?)\$\$|\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL)
    /** 单独成段公式的左缩进（px）；Md 不认识 Context，用字号算的近似值就够 */
    private const val displayIndent = 40

    /**
     * 把跨行的 $$ … $$ / \[ … \] 并成一行（再交给 DISPLAY 整行匹配）。
     * 流式时右边还没到：先当它到结尾了，照样排版，不露出 $$。
     */
    private fun joinDisplayMath(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            val open = when { t.startsWith("$$") -> "$$"; t.startsWith("\\[") -> "\\]"; else -> null }
            if (open == null || t.length > 2 && t.indexOf(open, 2) >= 0) { out.add(lines[i]); i++; continue }
            val sb = StringBuilder(t)
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val l = lines[j].trim()
                val k = l.indexOf(open)
                if (k >= 0) {
                    sb.append(' ').append(l, 0, k + open.length); closed = true
                    val rest = l.substring(k + open.length).trim()
                    out.add(sb.toString()); if (rest.isNotEmpty()) out.add(rest)
                    break
                }
                sb.append(' ').append(l); j++
            }
            if (!closed) out.add(sb.append(' ').append(open).toString())
            i = j + 1
        }
        return out
    }

    /** \\ 换行后面的空格去掉（每行对齐在缩进处） */
    private fun trimRows(nodes: List<MathText.Node>): List<MathText.Node> {
        val out = ArrayList<MathText.Node>()
        var afterBr = true
        for (n in nodes) {
            if (n is MathText.Txt && afterBr) { val s = n.s.trimStart(); if (s.isEmpty()) continue; out.add(MathText.Txt(s)); afterBr = false; continue }
            if (n === MathText.Br) { while (out.lastOrNull() is MathText.Txt && (out.last() as MathText.Txt).s.isBlank()) out.removeAt(out.size - 1); if (out.isEmpty() || out.last() === MathText.Br) continue }
            afterBr = n === MathText.Br
            out.add(n)
        }
        while (out.lastOrNull() === MathText.Br) out.removeAt(out.size - 1)
        return out
    }

    /** 单行（答案行用）：加粗、代码、链接、公式都处理，不分段 */
    fun inline(src: String, codeBg: Int = 0x33FFFFFF): CharSequence {
        val out = SpannableStringBuilder()
        appendInline(out, src, codeBg)
        Links.apply(out)
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String, codeBg: Int) {
        // 先切代码段（代码里的 ** 不当加粗），再在普通段里找加粗
        var pos = 0
        for (m in CODE.findAll(line)) {
            appendLinks(out, line.substring(pos, m.range.first))
            val s = out.length
            out.append(m.groupValues[1])
            out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(codeBg), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = m.range.last + 1
        }
        appendLinks(out, line.substring(pos))
    }

    /** [文字](链接) → 「文字 ↗」整段可点；地址不安全（javascript: 之类）就只留文字 */
    private fun appendLinks(out: SpannableStringBuilder, seg: String) {
        var pos = 0
        for (m in Links.MD.findAll(seg)) {
            appendBold(out, seg.substring(pos, m.range.first))
            val target = Links.normalize(m.groupValues[2])
            val s = out.length
            appendBold(out, m.groupValues[1])
            if (Links.safe(target)) {
                out.append(" ↗")
                out.setSpan(Links.LinkSpan(target), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            pos = m.range.last + 1
        }
        appendBold(out, seg.substring(pos))
    }

    private fun appendBold(out: SpannableStringBuilder, seg: String) {
        var pos = 0
        for (m in BOLD.findAll(seg)) {
            appendMath(out, seg.substring(pos, m.range.first))
            val s = out.length
            appendMath(out, m.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pos = m.range.last + 1
        }
        appendMath(out, seg.substring(pos))
    }

    private fun appendMath(out: SpannableStringBuilder, seg: String) {
        for (p in MathText.split(seg)) {
            if (!p.math) { MathText.append(out, MathText.loose(p.text)); continue }
            val s = out.length
            MathText.append(out, MathText.parse(p.text, keepSpaces = p.bare))
            if (p.display) out.setSpan(RelativeSizeSpan(1.06f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
