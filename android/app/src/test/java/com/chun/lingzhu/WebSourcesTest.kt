package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索结果的展示逻辑（[WebSources]）。全部是纯函数，JVM 上直接跑，不用设备。
 *
 * 背景：v2.3.2 之前搜索结果只存网址，聊天里吐一整行「来源：a  b  c」，没法排版也没法折叠。
 * 现在每条结果要排成「标题 / 域名 / 摘要」三行并支持折叠，这批用例锁的就是那几条判断。
 */
class WebSourcesTest {

    private fun hit(title: String, url: String, snippet: String = "", content: String = "") =
        WebSearch.Hit(title, snippet, url, content)

    // ---------------- label：标题优先，空则域名 ----------------

    @Test fun labelPrefersTitle() {
        assertEquals("Rust 所有权详解", WebSources.label(hit("Rust 所有权详解", "https://example.com/a")))
    }

    @Test fun labelFallsBackToDomainWhenTitleBlank() {
        // 有些源就是不返回标题，这时候显示域名总比显示一片空白强
        assertEquals("example.com", WebSources.label(hit("", "https://www.example.com/a/b")))
        assertEquals("example.com", WebSources.label(hit("   ", "https://example.com/a")))
    }

    @Test fun labelIsEmptyWhenNothingUsable() {
        // 标题和网址都空 → 空串，界面据此跳过这行，而不是画一个「无标题」
        assertEquals("", WebSources.label(hit("", "")))
    }

    // ---------------- domain ----------------

    @Test fun domainStripsSchemeAndWww() {
        assertEquals("example.com", WebSources.domain("https://www.example.com/path?q=1"))
        assertEquals("example.com", WebSources.domain("http://example.com"))
    }

    @Test fun domainKeepsSubdomainAndDropsPort() {
        assertEquals("blog.example.com", WebSources.domain("https://blog.example.com:8443/x"))
    }

    @Test fun domainIsLowercased() {
        assertEquals("example.com", WebSources.domain("https://Example.COM/A"))
    }

    @Test fun domainEmptyOnGarbage() {
        // 解析失败一律空串——宁可少显示一行，也不要显示一个像域名的乱码
        assertEquals("", WebSources.domain(""))
        assertEquals("", WebSources.domain("   "))
        assertEquals("", WebSources.domain("不是网址"))
        assertEquals("", WebSources.domain("/relative/path"))
    }

    // ---------------- summary ----------------

    @Test fun summaryPrefersSnippet() {
        assertEquals("一段摘要", WebSources.summary(hit("t", "https://a.com", "一段摘要")))
    }

    @Test fun summaryFallsBackToContent() {
        // 自动补过正文的那几条没有 snippet，用正文顶上
        val h = hit("t", "https://a.com", "", "正文内容")
        assertEquals("正文内容", WebSources.summary(h))
    }

    @Test fun summaryEmptyWhenNothing() {
        // 绝不填「无摘要」这种假话——空串让界面直接不画这一行
        assertEquals("", WebSources.summary(hit("t", "https://a.com")))
        assertEquals("", WebSources.summary(hit("t", "https://a.com", "   ")))
    }

    @Test fun summaryCollapsesWhitespace() {
        val h = hit("t", "https://a.com", "第一行\n\n第二行　第三行\t尾巴")
        assertEquals("第一行 第二行 第三行 尾巴", WebSources.summary(h))
    }

    @Test fun summaryTruncatesWithEllipsis() {
        val long = "字".repeat(200)
        val s = WebSources.summary(hit("t", "https://a.com", long), max = 20)
        assertEquals(20, s.length)
        assertTrue("该以省略号结尾", s.endsWith("…"))
        assertEquals("字".repeat(19) + "…", s)
    }

    @Test fun summaryShortEnoughIsUntouched() {
        val s = WebSources.summary(hit("t", "https://a.com", "短"), max = 20)
        assertEquals("短", s)
        assertFalse(s.endsWith("…"))
    }

    // ---------------- 折叠 ----------------

    @Test fun previewShowsOnlyHead() {
        val hs = (1..5).map { hit("t$it", "https://a.com/$it") }
        assertEquals(listOf("t1", "t2"), WebSources.preview(hs, 2).map { it.title })
    }

    @Test fun previewToleratesFewerHitsThanMax() {
        val hs = listOf(hit("t1", "https://a.com"))
        assertEquals(1, WebSources.preview(hs, 5).size)
    }

    @Test fun previewNegativeMaxYieldsEmpty() {
        // 调用方传错（比如算出来是 -1）时不该把整列都吐出来
        val hs = (1..3).map { hit("t$it", "https://a.com/$it") }
        assertTrue(WebSources.preview(hs, -1).isEmpty())
    }

    @Test fun previewDefaultIsTwo() {
        val hs = (1..5).map { hit("t$it", "https://a.com/$it") }
        assertEquals(2, WebSources.preview(hs).size)
    }

    @Test fun restCountCountsTheHiddenOnes() {
        assertEquals(3, WebSources.restCount(5, 2))
        assertEquals(0, WebSources.restCount(2, 2))
    }

    @Test fun restCountNeverNegative() {
        // shown 比 total 大（理论上不会，但别让界面画出「还有 -1 条」）
        assertEquals(0, WebSources.restCount(2, 5))
    }

    // ---------------- 按钮与可折叠判断 ----------------

    @Test fun toggleTextSaysExpandWithTotal() {
        // 写总数而不是剩余数：用户关心「点开能看到多少」
        assertEquals("展开全部 5 条", WebSources.toggleText(false, 5, 2))
    }

    @Test fun toggleTextSaysCollapseWhenExpanded() {
        assertEquals("收起", WebSources.toggleText(true, 5, 2))
    }

    @Test fun toggleTextCollapseWhenNotEnoughToFold() {
        // 只有 1 条、折叠条数 2 → 没什么可折的，按钮应当是「收起」而不是「展开全部 1 条」
        assertEquals("收起", WebSources.toggleText(false, 1, 2))
    }

    @Test fun foldableOnlyWhenMoreThanShown() {
        assertTrue(WebSources.foldable(5, 2))
        assertFalse(WebSources.foldable(2, 2))
        assertFalse(WebSources.foldable(1, 2))
        assertFalse(WebSources.foldable(0, 2))
    }

    // ---------------- 端到端：真机那个场景的形状 ----------------

    @Test fun threeHitsRenderAsTwoThenOneMore() {
        // 「本次返回 3 条结果」→ 折叠时画 2 条，提示还有 1 条
        val hs = listOf(
            hit("甲", "https://a.com/1", "摘要甲"),
            hit("乙", "https://b.com/2", "摘要乙"),
            hit("丙", "https://c.com/3", "摘要丙"),
        )
        val shown = WebSources.preview(hs)
        assertEquals(listOf("甲", "乙"), shown.map { WebSources.label(it) })
        assertEquals(1, WebSources.restCount(hs.size, shown.size))
        assertEquals("展开全部 3 条", WebSources.toggleText(false, hs.size, shown.size))
        assertEquals(listOf("a.com", "b.com"), shown.map { WebSources.domain(it.url) })
        assertEquals(listOf("摘要甲", "摘要乙"), shown.map { WebSources.summary(it) })
        // 展开后是全 3 条，按钮变「收起」
        assertEquals(3, WebSources.preview(hs, hs.size).size)
        assertEquals("收起", WebSources.toggleText(true, hs.size, hs.size))
    }
}
