package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private fun e(id: Long, text: String, kind: String = History.KIND_SOLVE, src: String = "") = History.Entry(id, kind, "做题·标准", text, src)

    @Test fun addNewestFirstDedupAndCap() {
        var l = History.add(emptyList(), e(1, "A"))
        l = History.add(l, e(2, "B"))
        assertEquals(listOf("B", "A"), l.map { it.text })
        assertEquals(l, History.add(l, e(3, "B")))                 // 和最新一条一样：不重复记
        assertEquals(3, History.add(l, e(4, "A")).size)            // 和更早的一样：照记
        assertEquals(3L, History.add(l, e(2, "C")).first().id)     // id 撞了往后挪
        var big = emptyList<History.Entry>()
        for (i in 1..250) big = History.add(big, e(i.toLong(), "t$i"))
        assertEquals(History.MAX, big.size)
        assertEquals("t250", big.first().text)
    }

    @Test fun jsonUpdateFilterPreview() {
        val l = listOf(e(2, "## 答案\n**B**  \$x^2\$", src = ""), e(1, "Hello", History.KIND_TRANSLATE, "你好"))
        assertEquals(l, History.parse(History.toJson(l)))
        assertEquals(emptyList<History.Entry>(), History.parse("{坏"))
        assertEquals("新的", History.update(l, 2, "新的").first().text)
        assertEquals(listOf(1L), History.filter(l, "你好").map { it.id })
        assertEquals(listOf(1L), History.filter(l, "hello").map { it.id })
        assertEquals(2, History.filter(l, " ").size)
        assertEquals("答案 B x²", History.preview(l[0]))
        assertEquals("答案：1/6", History.preview(e(9, "答案：\$\\dfrac{1}{6}\$")))
        assertTrue(History.preview(l[1]).startsWith("你好 → Hello"))
    }
}

class HistoryStarTest {
    private fun e(id: Long, star: Boolean = false) = History.Entry(id, History.KIND_SOLVE, "做题", "答$id", star = star)
    @org.junit.Test fun starredSurviveTrim() {
        var l = listOf(e(5), e(4), e(3, star = true), e(2), e(1, star = true))
        l = History.add(l, e(6), max = 4)
        org.junit.Assert.assertEquals(listOf(6L, 5L, 3L, 1L), l.map { it.id })   // 挤掉的是最老的两条没收藏的
    }
    @org.junit.Test fun starRoundTrip() {
        val l = History.setStar(listOf(e(1), e(2)), 2, true)
        val back = History.parse(History.toJson(l))
        org.junit.Assert.assertEquals(listOf(false, true), back.map { it.star })
        org.junit.Assert.assertEquals(false, History.parse("[{\"id\":1,\"text\":\"x\"}]")[0].star)   // 老文件没有 star 字段
    }
    @org.junit.Test fun markdownExport() {
        val md = History.toMarkdown(listOf(e(2, star = true), History.Entry(1, History.KIND_TRANSLATE, "→ 中文", "你好", "hello\nworld")), "Ling 历史") { "T$it" }
        org.junit.Assert.assertTrue(md, md.startsWith("# Ling 历史\n\n## ★ T2 · 识别 · 做题\n\n答2\n\n---"))
        org.junit.Assert.assertTrue(md, md.contains("## T1 · 翻译 · → 中文\n\n> hello\n> world\n\n你好"))
    }
}
