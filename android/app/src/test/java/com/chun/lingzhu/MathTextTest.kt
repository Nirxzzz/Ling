package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {
    private fun p(s: String) = MathText.toPlain(s)

    @Test fun symbolsAndScripts() {
        assertEquals("α ≠ β", p("\\alpha\\ne\\beta"))
        assertEquals("x² + y¹⁰", p("x^2+y^{10}"))
        assertEquals("a₁ + aₙ + a_(b+1)", p("a_1+a_n+a_{b+1}"))
        assertEquals("e^(−x²)", p("e^{-x^2}"))
        assertEquals("∫₀¹x\u2009dx", p("\\int_0^1 x\\,dx"))
        assertEquals("x = 1, sin x", p("x=1, \\sin x"))
        assertEquals("∑ₙ₌₁", p("\\sum_{n = 1}"))
        assertEquals("ℝ", p("\\mathbb{R}"))
        assertEquals("−1 − x = (−2)", p("-1-x=(-2)"))
    }

    @Test fun fractionsAndRoots() {
        assertEquals("1/2", p("\\frac{1}{2}"))
        assertEquals("(a+b)/(2c)", p("\\dfrac{a+b}{2c}"))
        assertEquals("√2 + √(x + 1)", p("\\sqrt{2}+\\sqrt{x+1}"))
        assertEquals("³√8", p("\\sqrt[3]{8}"))
        val f = MathText.parse("\\frac{\\sin x}{x}").single() as MathText.Frac
        assertEquals("sin x", MathText.plain(f.num))
        assertEquals("(sin x)/x", MathText.plain(listOf(f)))
    }

    @Test fun noBackslashLeftAndRowsSplit() {
        val tex = "\\begin{aligned} \\lim\\limits_{x\\to 0}\\frac{\\sin x}{x} &= 1 \\\\ \\left[ \\frac{x^3}{3} \\right]_0^1 &= \\frac{1}{3} \\end{aligned} \\foo \\sum_{n=1}^{\\infty}\\frac{1}{n^2}=\\frac{\\pi^2}{6}"
        val out = p(tex)
        assertFalse(out, out.contains('\\'))
        assertTrue(out, out.contains("lim_(x→0)"))
        assertEquals(2, out.split('\n').size)
        assertTrue(out, out.contains("∑"))
        assertTrue(out, out.contains("foo"))
    }

    @Test fun findsDelimiters() {
        val s = MathText.split("所以 \$x^2\$ 和 \$\$\\frac12\$\$ 以及 \\(a\\)")
        assertEquals(listOf(false, true, false, true, false, true), s.map { it.math })
        assertEquals(listOf(false, false, false, true, false, false), s.map { it.display })
        // 钱不是公式
        assertTrue(MathText.split("花了 \$5 和 \$10").none { it.math })
        // 没加 $ 的 \frac 也认
        assertTrue(MathText.split("结果是 \\frac{1}{2}").single().math)
        assertTrue(MathText.split("普通的一句话").none { it.math })
    }

    @Test fun looseTextScripts() {
        assertEquals("x³+a₁", MathText.plain(MathText.loose("x^3+a_1")))
        assertEquals("xⁿ⁺¹", MathText.plain(MathText.loose("x^{n+1}")))
        assertEquals("x^(k+1)", MathText.plain(MathText.loose("x^{k+1}")))
        assertEquals("snake_case 不动", MathText.plain(MathText.loose("snake_case 不动")))
        assertEquals("https://a.com/p_1", MathText.plain(MathText.loose("https://a.com/p_1")))
    }
}
