package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 记事本：中文二元组切分。
 *
 * 这是全文搜索能不能用的地基 —— FTS4 不认识中文词边界，
 * 不先切二元组，「导数的定义」会被当成一个词，搜「导数」搜不到。
 */
class NoteBigramTest {

    @Test fun chineseIsSplitIntoBigrams() {
        // 「导数的定义」五个字 → 四个二元组：导数 数的 的定义? 不，是 导数/数的/的定义 里的 的定/定义
        val out = Notes.bigrams("导数的定义")
        assertEquals("导数 数的 的定 定义", out)
    }

    @Test fun simpleChinese() {
        // 「数学」→「数学」一个二元组
        assertEquals("数学", Notes.bigrams("数学"))
    }

    @Test fun threeChars() {
        // 「二次函数」→ 二次 次函 函数
        assertEquals("二次 次函 函数", Notes.bigrams("二次函数"))
    }

    @Test fun singleCharKept() {
        assertEquals("a", Notes.bigrams("a"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", Notes.bigrams(""))
        assertEquals("", Notes.bigrams("   "))
    }

    @Test fun punctuationBecomesSeparator() {
        // 标点当分隔符，不该出现在二元组里
        val out = Notes.bigrams("数学，导数")
        assertTrue("标点要被去掉", !out.contains("，"))
        assertTrue("两段都要在", out.contains("数学") && out.contains("导数"))
    }

    @Test fun wordsAreSeparated() {
        // 「数学 导数」两个词各自切，不跨空格切出「学导」
        val out = Notes.bigrams("数学 导数")
        assertTrue("不能跨词切", !out.contains("学导"))
    }

    @Test fun englishWordsBecomeBigrams() {
        // 英文也切二元组：搜 "deriv" 能命中 "derivative" 的片段
        val out = Notes.bigrams("derivative")
        assertTrue(out.contains("de"))
    }
}
