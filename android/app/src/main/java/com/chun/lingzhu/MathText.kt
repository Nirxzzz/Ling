package com.chun.lingzhu

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan

/**
 * 公式排版（v1.58）：把模型写的 LaTeX（$…$、$$…$$、\(…\)、\[…\]）变成手机上能看的样子。
 * 不是完整 TeX —— 只做高中/大学数学常见的那些：
 * 希腊字母和运算符 → Unicode；分式 → 上下叠起来（FracSpan 自己画）；上下标能用 ²₁ 就用，不能就缩小抬高；
 * √、\text、\left\right、aligned/cases 换行。认不出的命令去掉反斜杠照原样显示，不会出现一串 \xxx。
 * 解析部分是纯 Kotlin（单元测试测 plain()），Android span 只在 append* 里用。
 */
object MathText {
    sealed class Node
    data class Txt(val s: String) : Node()
    data class Sup(val c: List<Node>) : Node()
    data class Sub(val c: List<Node>) : Node()
    data class Frac(val num: List<Node>, val den: List<Node>, val line: Boolean = true) : Node()
    object Br : Node()

    private val SYM = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
        "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
        "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
        "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮", "sum" to "∑", "prod" to "∏", "coprod" to "∐",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞", "infin" to "∞", "prime" to "′", "degree" to "°", "circ" to "∘",
        "cdot" to "·", "cdots" to "⋯", "ldots" to "…", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱", "times" to "×", "div" to "÷",
        "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆", "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗",
        "le" to "≤", "leq" to "≤", "leqslant" to "≤", "ge" to "≥", "geq" to "≥", "geqslant" to "≥", "ne" to "≠", "neq" to "≠",
        "approx" to "≈", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "lt" to "<", "gt" to ">", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "implies" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "iff" to "⇔", "mapsto" to "↦",
        "longrightarrow" to "⟶", "Longrightarrow" to "⟹", "uparrow" to "↑", "downarrow" to "↓", "nearrow" to "↗", "searrow" to "↘",
        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆", "subsetneq" to "⊊", "supset" to "⊃",
        "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "bigcup" to "⋃", "bigcap" to "⋂", "setminus" to "∖", "emptyset" to "∅",
        "varnothing" to "∅", "forall" to "∀", "exists" to "∃", "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "land" to "∧", "wedge" to "∧",
        "lor" to "∨", "vee" to "∨", "therefore" to "∴", "because" to "∵", "angle" to "∠", "triangle" to "△", "perp" to "⊥",
        "parallel" to "∥", "mid" to "∣", "nmid" to "∤", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "vert" to "|", "Vert" to "‖",
        "lvert" to "|", "rvert" to "|", "lVert" to "‖", "rVert" to "‖", "backslash" to "\\", "percent" to "%",
        "{" to "{", "}" to "}", "%" to "%", "$" to "$", "&" to "&", "#" to "#", "_" to "_", "|" to "‖",
        "," to " ", ":" to " ", ";" to " ", ">" to " ", " " to " ", "!" to "", "quad" to "  ", "qquad" to "    ", "enspace" to " ",
        "square" to "□", "blacksquare" to "■", "checkmark" to "✓", "dagger" to "†",
    )
    /** 函数名：原样显示（正体），后面跟上下标照常处理 */
    private val FUNCS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "ln", "log", "lg", "exp", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "dim", "ker", "deg", "gcd", "arg", "Pr", "mod", "bmod")
    /** 只影响字体 / 间距 / 大小的命令：直接去掉，内容照常 */
    private val DROP = setOf("left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
        "middle", "limits", "nolimits", "displaystyle", "textstyle", "scriptstyle", "nonumber", "notag", "mathstrut", "strut", "boxed", "centering")
    private val FONT = setOf("text", "textrm", "textit", "textbf", "mathrm", "mathit", "mathbf", "mathsf", "mathtt", "boldsymbol", "bm",
        "operatorname", "mbox", "hbox", "emph", "textnormal", "mathnormal", "pmb", "underline", "fbox", "overbrace", "underbrace")
    private val ACCENT = mapOf("vec" to '⃗', "overrightarrow" to '⃗', "bar" to '̄', "overline" to '̅', "hat" to '̂',
        "widehat" to '̂', "tilde" to '̃', "widetilde" to '̃', "dot" to '̇', "ddot" to '̈', "check" to '̌')
    private val BB = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼")
    private val SUP = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', '′' to '′', '∘' to '°', '°' to '°')
    private val SUB = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ',
        'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'j' to 'ⱼ')

    // ---------------- 解析 ----------------

    /** keepSpaces：没加 $ 的裸公式（混着中文）保留原来的空格；正经公式里空格不算数，关系符号两边自动留空 */
    fun parse(tex: String, keepSpaces: Boolean = false): List<Node> = P(tex, keepSpaces).seq(null).let(::merge)

    private val REL = setOf("=", "<", ">", "≤", "≥", "≠", "≈", "≡", "→", "⇒", "⇔", "⟶", "⟹", "∈", "∉", "⊂", "⊆", "⊇", "⊃",
        "∼", "≃", "≅", "∝", "↔", "←", "⇐", "↦")

    private val BIN = setOf("+", "−", "±", "∓", "×", "÷", "·")

    private class P(val s: String, val keep: Boolean) {
        var i = 0
        val envs = ArrayList<String>()
        /** >0 = 在上下标 / 分式里：关系符号不加空格（否则 n=1 没法用 ₙ₌₁） */
        var tight = 0

        /** prev = 前一个节点：用来判断 − 是减号（两边留空）还是负号（紧贴） */
        fun sym(t: String, prev: Node? = null): Txt = Txt(when {
            keep || tight > 0 -> t
            t in REL -> " $t "
            t == "," || t == ";" -> "$t "
            t in BIN && prev != null && !(prev is Txt && (prev.s.isBlank() || prev.s.trimEnd().let { p -> p.endsWith("(") || p.endsWith("[") || p.endsWith("{") || REL.any { r -> p.endsWith(r) } || p.endsWith(",") })) -> " $t "
            else -> t
        })
        fun tightArg(): List<Node> { tight++; try { return arg() } finally { tight-- } }

        fun seq(until: Char?): MutableList<Node> {
            val out = ArrayList<Node>()
            while (i < s.length) {
                val c = s[i]
                when {
                    until != null && c == until -> { i++; return out }
                    c == '{' -> { i++; out.addAll(seq('}')) }
                    c == '}' -> i++                                   // 多出来的右括号
                    c == '^' || c == '_' -> {
                        i++
                        val a = tightArg()
                        out.add(if (c == '^') Sup(a) else Sub(a))
                    }
                    c == '\\' -> command(out)
                    c == '&' -> { i++; out.add(Txt(if (envs.lastOrNull()?.startsWith("align") == true || envs.lastOrNull() == "eqnarray") "" else "  ")) }
                    c == '~' -> { i++; out.add(Txt(" ")) }
                    c == '\'' -> { i++; out.add(Txt("′")) }
                    c == '-' -> { i++; out.add(sym("−", out.lastOrNull())) }
                    c == '*' -> { i++; out.add(sym("·", out.lastOrNull())) }
                    c == ' ' || c == '\n' || c == '\r' || c == '\t' -> { i++; if (keep && out.lastOrNull() != Txt(" ")) out.add(Txt(" ")) }
                    else -> { i++; out.add(sym(c.toString(), out.lastOrNull())) }
                }
            }
            return out
        }

        /** 一个参数：{…}、\命令 或单个字符 */
        fun arg(): List<Node> {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return emptyList()
            return when (s[i]) {
                '{' -> { i++; seq('}') }
                '\\' -> ArrayList<Node>().also { command(it) }
                else -> { val c = s[i++]; listOf(Txt(if (c == '-') "−" else c.toString())) }
            }
        }

        /** {…} 里的原文（环境名、\text 内容：空格要保留） */
        fun rawGroup(): String {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length || s[i] != '{') return ""
            var depth = 0; val st = i
            while (i < s.length) {
                when (s[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { i++; return s.substring(st + 1, i - 1) } } }
                i++
            }
            return s.substring(st + 1)
        }

        fun optArg(): List<Node>? {
            if (i < s.length && s[i] == '[') {
                val end = s.indexOf(']', i)
                if (end > 0) { val inner = s.substring(i + 1, end); i = end + 1; return P(inner, keep).seq(null) }
            }
            return null
        }

        fun command(out: MutableList<Node>) {
            i++   // 跳过 \
            if (i >= s.length) return
            val st = i
            if (s[i].isLetter()) { while (i < s.length && s[i].isLetter()) i++ } else i++
            val name = s.substring(st, i)
            // TeX 规矩：字母命令后面的空格只是分隔符
            if (name[0].isLetter()) while (i < s.length && s[i] == ' ') i++
            when {
                name == "\\" -> { out.add(Br); optArg() }            // \\ 换行（可能带 [2pt]）
                name == "frac" || name == "dfrac" || name == "tfrac" || name == "cfrac" -> { val a = tightArg(); val b = tightArg(); out.add(Frac(a, b)) }
                name == "binom" || name == "dbinom" || name == "tbinom" -> { val a = tightArg(); val b = tightArg(); out.add(Txt("(")); out.add(Frac(a, b, line = false)); out.add(Txt(")")) }
                name == "sqrt" -> {
                    val n = optArg(); val a = arg()
                    if (n != null) out.add(Sup(n))
                    out.add(Txt("√"))
                    if (a.size == 1 && a[0] is Txt && (a[0] as Txt).s.length == 1 || a.singleOrNull() is Frac) out.addAll(a)
                    else { out.add(Txt("(")); out.addAll(a); out.add(Txt(")")) }
                }
                name == "begin" -> {
                    val env = rawGroup().trimEnd('*')
                    if (env == "array" || env == "tabular") rawGroup()
                    envs.add(env)
                    when (env) { "cases", "dcases" -> out.add(Txt("{ ")); "pmatrix" -> out.add(Txt("( ")); "bmatrix" -> out.add(Txt("[ ")); "vmatrix" -> out.add(Txt("| ")) }
                }
                name == "end" -> {
                    val env = rawGroup().trimEnd('*')
                    if (envs.isNotEmpty()) envs.removeAt(envs.size - 1)
                    when (env) { "pmatrix" -> out.add(Txt(" )")); "bmatrix" -> out.add(Txt(" ]")); "vmatrix" -> out.add(Txt(" |")) }
                }
                name == "mathbb" || name == "Bbb" -> out.add(Txt(rawGroup().map { BB[it] ?: it.toString() }.joinToString("")))
                name == "mathcal" || name == "mathscr" || name == "mathfrak" -> out.addAll(arg())
                name == "text" || name == "textrm" || name == "mbox" || name == "hbox" || name == "textnormal" -> out.add(Txt(rawGroup()))
                name in FONT -> out.addAll(arg())
                name in ACCENT -> {
                    val a = arg(); val mark = ACCENT.getValue(name)
                    val t = a.singleOrNull() as? Txt
                    if (t != null) out.add(Txt(t.s.map { "$it$mark" }.joinToString(""))) else out.addAll(a)
                }
                name == "overset" || name == "stackrel" -> { val top = arg(); val base = arg(); out.addAll(base); out.add(Sup(top)) }
                name == "underset" -> { val bot = arg(); val base = arg(); out.addAll(base); out.add(Sub(bot)) }
                name == "xrightarrow" -> { optArg(); val a = arg(); out.add(Txt("→")); if (a.isNotEmpty()) out.add(Sup(a)) }
                name == "not" -> { val a = arg(); out.addAll(a); out.add(Txt("̸")) }
                name == "hspace" || name == "vspace" || name == "label" || name == "tag" -> rawGroup()
                name == "color" || name == "textcolor" -> { rawGroup(); if (name == "textcolor") out.addAll(arg()) }
                name in DROP -> { if ((name == "left" || name == "right") && i < s.length && s[i] == '.') i++ }
                name in FUNCS -> out.add(Txt(when {
                    name == "bmod" || name == "mod" -> " mod "
                    i < s.length && s[i].isLetterOrDigit() -> "$name "     // \sin x → sin x
                    else -> name
                }))
                name in SYM -> out.add(sym(SYM.getValue(name), out.lastOrNull()))
                else -> out.add(Txt(name))   // 认不出的命令：去掉反斜杠
            }
        }
    }

    /** 相邻文字合并（上下标能不能用 Unicode 取决于整段） */
    private fun merge(nodes: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        for (n in nodes) {
            val m = when (n) { is Sup -> Sup(merge(n.c)); is Sub -> Sub(merge(n.c)); is Frac -> Frac(merge(n.num), merge(n.den), n.line); else -> n }
            val last = out.lastOrNull()
            if (m is Txt && last is Txt) out[out.size - 1] = Txt(last.s + m.s) else out.add(m)
        }
        return out
    }

    private fun textOf(c: List<Node>): String? = if (c.all { it is Txt }) c.joinToString("") { (it as Txt).s } else null
    fun supText(c: List<Node>): String? = textOf(c)?.takeIf { it.isNotEmpty() && it.all { ch -> ch in SUP } }?.map { SUP.getValue(it) }?.joinToString("")
    fun subText(c: List<Node>): String? = textOf(c)?.takeIf { it.isNotEmpty() && it.all { ch -> ch in SUB } }?.map { SUB.getValue(it) }?.joinToString("")

    /** 线性文字版（复制、单元测试、放不下时的退路）：\frac{a+b}{2} → (a+b)/2，x^{10} → x¹⁰，x^{n+1} → x^(n+1) */
    fun plain(nodes: List<Node>): String = buildString {
        for (n in nodes) when (n) {
            is Txt -> append(n.s)
            is Br -> append('\n')
            is Sup -> append(supText(n.c) ?: wrap("^", plain(n.c)))
            is Sub -> append(subText(n.c) ?: wrap("_", plain(n.c)))
            is Frac -> if (n.line) append(atom(plain(n.num))).append('/').append(atom(plain(n.den)))
                       else append(plain(n.num)).append(", ").append(plain(n.den))
        }
    }
    private fun wrap(p: String, s: String) = if (s.length == 1) p + s else "$p($s)"
    private fun atom(s: String) = if (s.length <= 1 || s.all { it.isDigit() || it == '.' }) s else "($s)"

    fun toPlain(tex: String) = plain(parse(tex))

    // ---------------- 在正文里找公式 ----------------

    data class Seg(val math: Boolean, val text: String, val display: Boolean = false, val bare: Boolean = false)

    private val INLINE = Regex("""\$\$(.+?)\$\$|\\\((.+?)\\\)|\\\[(.+?)\\]|(?<![\\$\w])\$(?![\s$])((?:\\\$|[^$])+?)(?<![\s\\])\$(?!\d)""")
    /** 裸写的 LaTeX 命令（模型没加 $ 时也认） */
    private val BARE = Regex("""\\(frac|dfrac|tfrac|sqrt|int|iint|oint|sum|prod|lim|infty|alpha|beta|gamma|delta|theta|lambda|mu|pi|sigma|omega|varphi|phi|cdot|times|div|pm|le|leq|ge|geq|neq|ne|approx|to|rightarrow|Rightarrow|in|subseteq|cup|cap|partial|mathbb|vec|overline|left|right|begin)(?![a-zA-Z])""")
    /** 公式外面的 x^2、x^{n+1}、a_1 这种纯文本写法也排一下（只认数字或花括号，免得误伤英文） */
    private val LOOSE = Regex("""(?<=[\w)\]′])([\^_])(\{[^{}\n]{1,24}\}|-?\d{1,3})""")

    fun split(line: String): List<Seg> {
        val out = ArrayList<Seg>()
        var pos = 0
        for (m in INLINE.findAll(line)) {
            if (m.range.first > pos) out.add(Seg(false, line.substring(pos, m.range.first)))
            val g = m.groups
            val tex = (g[1] ?: g[2] ?: g[3] ?: g[4])!!.value
            out.add(Seg(true, tex, display = g[1] != null || g[3] != null))
            pos = m.range.last + 1
        }
        if (pos < line.length) out.add(Seg(false, line.substring(pos)))
        return out.map { if (!it.math && BARE.containsMatchIn(it.text)) Seg(true, it.text, bare = true) else it }
    }

    /** 公式外的纯文本：只把 ^2 / _{1} 这种排成上下标，其余原样 */
    fun loose(text: String): List<Node> {
        if (!LOOSE.containsMatchIn(text) || text.contains("://") || text.contains("www.")) return listOf(Txt(text))
        val out = ArrayList<Node>()
        var pos = 0
        for (m in LOOSE.findAll(text)) {
            if (m.range.first > pos) out.add(Txt(text.substring(pos, m.range.first)))
            val body = m.groupValues[2].removeSurrounding("{", "}")
            out.addAll(parse(m.groupValues[1] + "{" + body + "}"))   // 当成 ^{…} 解析：上下标里不加空格
            pos = m.range.last + 1
        }
        if (pos < text.length) out.add(Txt(text.substring(pos)))
        return out
    }

    // ---------------- 变成 Android 文字 ----------------

    fun append(out: SpannableStringBuilder, nodes: List<Node>) {
        for (n in nodes) when (n) {
            is Txt -> out.append(n.s)
            is Br -> out.append('\n')
            is Sup, is Sub -> {
                val c = if (n is Sup) n.c else (n as Sub).c
                val uni = if (n is Sup) supText(c) else subText(c)
                if (uni != null) out.append(uni)
                else {
                    val s = out.length
                    append(out, c.filter { it !is Br })
                    if (out.length > s) {
                        out.setSpan(if (n is Sup) SuperscriptSpan() else SubscriptSpan(), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(0.72f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            is Frac -> {
                val lin = plain(listOf(n))
                // 太长就别叠了（叠起来会比屏幕还宽）
                if (plain(n.num).length > 28 || plain(n.den).length > 28) { out.append(lin); continue }
                val s = out.length
                out.append(lin)
                out.setSpan(FracSpan(n), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /**
     * 分式：分子分母上下叠、中间一条线，自己量尺寸自己画（分子分母里还能套上下标和分式）。
     * 底下的文字是线性版 (a)/(b)，复制出来也看得懂。
     */
    class FracSpan(private val f: Frac) : ReplacementSpan() {
        private class Box(val w: Float, val asc: Float, val desc: Float)

        private fun measure(nodes: List<Node>, p: Paint, size: Float): Box {
            var w = 0f; var asc = size * 0.8f; var desc = size * 0.22f
            for (n in nodes) {
                val b = box(n, p, size)
                w += b.w; asc = maxOf(asc, b.asc); desc = maxOf(desc, b.desc)
            }
            return Box(w, asc, desc)
        }

        private fun box(n: Node, p: Paint, size: Float): Box = when (n) {
            is Txt -> { p.textSize = size; Box(p.measureText(n.s), size * 0.8f, size * 0.22f) }
            is Br -> Box(0f, 0f, 0f)
            is Sup -> supText(n.c)?.let { box(Txt(it), p, size) } ?: measure(n.c, p, size * 0.7f).let { Box(it.w, it.asc + size * 0.38f, maxOf(0f, it.desc - size * 0.38f)) }
            is Sub -> subText(n.c)?.let { box(Txt(it), p, size) } ?: measure(n.c, p, size * 0.7f).let { Box(it.w, maxOf(0f, it.asc - size * 0.2f), it.desc + size * 0.2f) }
            is Frac -> {
                val cs = maxOf(size * 0.86f, 9f)
                val a = measure(n.num, p, cs); val b = measure(n.den, p, cs)
                val axis = size * 0.3f; val gap = size * 0.1f
                Box(maxOf(a.w, b.w) + size * 0.3f, axis + gap + a.asc + a.desc, b.asc + b.desc + gap - axis)
            }
        }

        private fun draw(c: Canvas, nodes: List<Node>, p: Paint, size: Float, x0: Float, y: Float): Float {
            var x = x0
            for (n in nodes) x = drawOne(c, n, p, size, x, y)
            return x
        }

        private fun drawOne(c: Canvas, n: Node, p: Paint, size: Float, x: Float, y: Float): Float = when (n) {
            is Txt -> { p.textSize = size; c.drawText(n.s, x, y, p); x + p.measureText(n.s) }
            is Br -> x
            is Sup -> supText(n.c)?.let { drawOne(c, Txt(it), p, size, x, y) } ?: draw(c, n.c, p, size * 0.7f, x, y - size * 0.38f)
            is Sub -> subText(n.c)?.let { drawOne(c, Txt(it), p, size, x, y) } ?: draw(c, n.c, p, size * 0.7f, x, y + size * 0.2f)
            is Frac -> {
                val cs = maxOf(size * 0.86f, 9f)
                val a = measure(n.num, p, cs); val b = measure(n.den, p, cs)
                val axis = size * 0.3f; val gap = size * 0.1f
                val w = maxOf(a.w, b.w) + size * 0.3f
                val ly = y - axis
                draw(c, n.num, p, cs, x + (w - a.w) / 2, ly - gap - a.desc)
                draw(c, n.den, p, cs, x + (w - b.w) / 2, ly + gap + b.asc)
                if (n.line) {
                    val st = p.style; val sw = p.strokeWidth
                    p.style = Paint.Style.FILL
                    c.drawRect(x + size * 0.08f, ly - size * 0.03f, x + w - size * 0.08f, ly + size * 0.03f, p)
                    p.style = st; p.strokeWidth = sw
                }
                x + w
            }
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val size = paint.textSize
            val p = Paint(paint)
            val b = box(f, p, size)
            if (fm != null) {
                val base = paint.fontMetricsInt
                fm.ascent = minOf(base.ascent, -(b.asc + size * 0.08f).toInt())
                fm.top = minOf(base.top, fm.ascent)
                fm.descent = maxOf(base.descent, (b.desc + size * 0.08f).toInt())
                fm.bottom = maxOf(base.bottom, fm.descent)
                fm.leading = base.leading
            }
            return Math.ceil(b.w.toDouble() + size * 0.1).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint(paint)
            drawOne(canvas, f, p, paint.textSize, x + paint.textSize * 0.05f, y.toFloat())
        }
    }
}
