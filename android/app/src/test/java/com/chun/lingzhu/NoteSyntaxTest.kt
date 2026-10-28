package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 记事本语法：标签 / 双链。
 *
 * 方案 3.3 列了必须钉住的边界，这里逐条覆盖。
 * 解析规则一旦不确定，同步两端会重建出不同的结果 —— 这是最难查的一类数据不一致。
 */
class NoteSyntaxTest {

    // ── 基本标签 ─────────────────────────────────────────

    @Test fun simpleTag() {
        assertEquals(listOf("数学"), NoteSyntax.tags("这题不会 #数学"))
    }

    @Test fun hierarchicalTag() {
        assertEquals(listOf("数学/导数"), NoteSyntax.tags("#数学/导数 不会"))
    }

    @Test fun multipleTags() {
        assertEquals(listOf("数学", "错题"), NoteSyntax.tags("#数学 这题 #错题"))
    }

    @Test fun duplicateTagsCollapse() {
        assertEquals(listOf("数学"), NoteSyntax.tags("#数学 和 #数学"))
    }

    // ── 不该算标签的（方案点名）─────────────────────────

    @Test fun csharpIsNotATag() {
        assertTrue("C# 是语言名，不是标签", NoteSyntax.tags("学 C# 记录").isEmpty())
    }

    @Test fun aHashBIsNotATag() {
        assertTrue(NoteSyntax.tags("a#b").isEmpty())
    }

    @Test fun pureNumberIsNotATag() {
        assertTrue("#1 不该算标签", NoteSyntax.tags("#1").isEmpty())
        assertTrue("#2026 不该算标签", NoteSyntax.tags("#2026").isEmpty())
    }

    @Test fun numberPlusTextIsATag() {
        // 「1班」有非数字字符，是标签 —— 这是区分 #1 和 #1班 的关键
        assertEquals(listOf("1班"), NoteSyntax.tags("#1班"))
    }

    @Test fun markdownHeadingIsNotATag() {
        assertTrue("# 标题 不该算标签", NoteSyntax.tags("# 这是标题").isEmpty())
    }

    @Test fun inlineCodeIsNotATag() {
        assertTrue("行内代码里的井号不算", NoteSyntax.tags("`#x`").isEmpty())
    }

    @Test fun escapedHashIsNotATag() {
        assertTrue(NoteSyntax.tags("\\#x").isEmpty())
    }

    @Test fun urlFragmentIsNotATag() {
        assertTrue("URL 锚点不算标签", NoteSyntax.tags("http://a.com/#b").isEmpty())
    }

    @Test fun fullWidthHashIsNotATag() {
        assertTrue("全角＃不算标签", NoteSyntax.tags("＃数学").isEmpty())
    }

    // ── 结束符与规范化 ───────────────────────────────────

    @Test fun endsAtChinesePunctuation() {
        assertEquals(listOf("数学"), NoteSyntax.tags("#数学，这题"))
    }

    @Test fun trailingSlashIsTrimmed() {
        assertEquals(listOf("a/b"), NoteSyntax.tags("#a//b/"))
    }

    @Test fun cppIsATag() {
        // + 是合法字符：C++ 是标签（和 C# 不同，因为没有 # 前缀问题）
        assertEquals(listOf("c++"), NoteSyntax.tags("#C++"))
    }

    @Test fun tagsAreLowercasedForComparison() {
        assertEquals(listOf("math"), NoteSyntax.tags("#Math"))
    }

    // ── 双链 ─────────────────────────────────────────────

    @Test fun simpleLink() {
        assertEquals(listOf("导数的定义"), NoteSyntax.links("见 [[导数的定义]]"))
    }

    @Test fun linkWithDisplayText() {
        // [[标题|显示文字]] 只取标题段
        assertEquals(listOf("导数的定义"), NoteSyntax.links("见 [[导数的定义|这一篇]]"))
    }

    @Test fun multipleLinks() {
        assertEquals(listOf("a", "b"), NoteSyntax.links("[[a]] 和 [[b]]"))
    }

    @Test fun brokenLinkIsIgnored() {
        assertTrue(NoteSyntax.links("[[没有闭合").isEmpty())
        assertTrue(NoteSyntax.links("空 [[]] 不算").isEmpty())
    }

    // ── appendTags ───────────────────────────────────────

    @Test fun appendAddsMissingTags() {
        assertEquals("这题不会 #数学", NoteSyntax.appendTags("这题不会", listOf("数学")))
    }

    @Test fun appendDoesNotDuplicate() {
        // 用户点了 chip，又在正文里打了同一个标签 —— 不能出现两次
        assertEquals("#数学", NoteSyntax.appendTags("#数学", listOf("数学")))
    }

    @Test fun appendToEmptyBody() {
        assertEquals("#数学 #错题", NoteSyntax.appendTags("", listOf("数学", "错题")))
    }

    @Test fun appendAcceptsTagsWithHash() {
        // 调用方可能带 # 也可能不带，两种都要认
        assertEquals("笔记 #数学", NoteSyntax.appendTags("笔记", listOf("#数学")))
    }

    @Test fun appendNothingIsNoop() {
        assertEquals("原文", NoteSyntax.appendTags("原文", emptyList()))
    }

    // ── 综合 ─────────────────────────────────────────────

    @Test fun mixedContentParsesBoth() {
        val body = "看 [[导数]] 那篇 #数学/导数 明天复习";
        assertEquals(listOf("数学/导数"), NoteSyntax.tags(body))
        assertEquals(listOf("导数"), NoteSyntax.links(body))
    }

    // ── 双链：目标 / 标题 / 别名 / 嵌入 ───────────────────

    @Test fun wikiHeadingAndAliasAreSeparateFields() {
        val w = NoteSyntax.wikiLinks("[[导数#定义|那一段]]").single()
        assertEquals("导数", w.target)
        assertEquals("定义", w.heading)
        assertEquals("那一段", w.alias)
        assertFalse(w.embed)
    }

    @Test fun wikiKeepsRawSliceAndOffsets() {
        val body = "前 [[导数]] 后"
        val w = NoteSyntax.wikiLinks(body).single()
        assertEquals("[[导数]]", w.raw)
        assertEquals(body.substring(w.start, w.end), w.raw)
    }

    @Test fun embedFlagAndSize() {
        val a = NoteSyntax.wikiLinks("![[图.png|300]]").single()
        assertTrue(a.embed)
        assertEquals(300, a.width)
        assertEquals(0, a.height)
        assertEquals("", a.alias)

        val b = NoteSyntax.wikiLinks("![[图.png|300x200]]").single()
        assertEquals(300, b.width)
        assertEquals(200, b.height)

        // 不是尺寸就还是别名，不能当成 0x0 图
        val c = NoteSyntax.wikiLinks("![[图.png|很宽的图]]").single()
        assertEquals(0, c.width)
        assertEquals("很宽的图", c.alias)
    }

    @Test fun embedIsNotALink() {
        // 嵌入是附件/整篇转写，不是双链：算进 links 会让图片变成指向别处的链接
        assertTrue(NoteSyntax.links("![[图.png]]").isEmpty())
    }

    @Test fun pdfTargetIsFlaggedAndPageParsed() {
        val w = NoteSyntax.wikiLinks("[[手册.pdf#page=3]]").single()
        assertTrue(w.isPdf)
        assertEquals(3, NoteSyntax.pdfPage(w.heading))
        assertEquals(0, NoteSyntax.pdfPage("第 3 页"))
        assertEquals(7, NoteSyntax.pdfPage("page=7"))
    }

    @Test fun linkTargetIsNormalized() {
        // 目标键：去 .md、反斜杠转正斜杠、折叠斜杠、小写
        assertEquals(listOf("notes/导数"), NoteSyntax.links("[[Notes\\导数.md]]"))
        assertEquals("notes/导数", NoteSyntax.targetKey("  notes//导数.md  "))
        assertEquals("导数的定义", NoteSyntax.targetKey("[[导数的定义|别名]]"))
        assertEquals("导数的定义", NoteSyntax.targetKey("导数的定义#标题"))
    }

    @Test fun wikiSkippedInsideCodeAndComments() {
        assertTrue(NoteSyntax.wikiLinks("`[[代码里]]`").isEmpty())
        assertTrue(NoteSyntax.wikiLinks("```\n[[围栏里]]\n```").isEmpty())
        assertTrue(NoteSyntax.wikiLinks("~~~\n[[波浪围栏里]]\n~~~").isEmpty())
        assertTrue(NoteSyntax.wikiLinks("%%[[注释里]]%%").isEmpty())
    }

    @Test fun malformedWikiIsDroppedNotTruncated() {
        // 跨行 / 目标里有嵌套括号 / 目标超长：整条丢掉，绝不截一半当成合法目标
        assertTrue(NoteSyntax.wikiLinks("[[第一行\n第二行]]").isEmpty())
        assertTrue(NoteSyntax.wikiLinks("[[a[b]]").isEmpty())
        assertTrue(NoteSyntax.wikiLinks("[[" + "长".repeat(NoteSyntax.MAX_LINK + 10) + "]]").isEmpty())
    }

    @Test fun wikipediaInLinkIsNotATag() {
        // [[目标#标题]] 里的 # 属于双链，不是正文里的标签
        assertTrue(NoteSyntax.tags("[[导数#定义]]").isEmpty())
        assertTrue(NoteSyntax.spans("[[导数#定义]]").none { it.kind == NoteSyntax.Kind.TAG })
    }

    // ── Markdown 链接 ────────────────────────────────────

    @Test fun markdownLinkWithTitle() {
        val l = NoteSyntax.markdownLinks("[导数](notes/导数.md \"标题\")").single()
        assertEquals("导数", l.text)
        assertEquals("notes/导数.md", l.dest)
        assertEquals("标题", l.title)
        assertFalse(l.image)
    }

    @Test fun markdownAngleDestWithSpaces() {
        val l = NoteSyntax.markdownLinks("[带空格](<a b.md>)").single()
        assertEquals("a b.md", l.dest)
    }

    @Test fun markdownImageIsFlagged() {
        val l = NoteSyntax.markdownLinks("![](attachments/a.png)").single()
        assertTrue(l.image)
        assertEquals("attachments/a.png", l.dest)
    }

    @Test fun markdownUnclosedIsDropped() {
        assertTrue(NoteSyntax.markdownLinks("[文字](没有右括号").isEmpty())
        assertTrue(NoteSyntax.markdownLinks("[文字] (空格隔着括号)").isEmpty())
    }

    @Test fun wikiIsNotReparsedAsMarkdown() {
        assertTrue(NoteSyntax.markdownLinks("[[导数]]").isEmpty())
    }

    // ── 标题 / 锚点 ──────────────────────────────────────

    @Test fun headingsParseLevelsAndClosingHashes() {
        val hs = NoteSyntax.headings("# 一\n### 三\n## 二 ##\n####### 七级不算")
        assertEquals(listOf("一", "三", "二"), hs.map { it.text })
        assertEquals(listOf(1, 3, 2), hs.map { it.level })
    }

    @Test fun csharpHeadingIsNotStripped() {
        // 结尾 # 只有前面是空白才算收尾标记
        assertEquals("C#", NoteSyntax.headings("## C#").single().text)
    }

    @Test fun headingInsideFenceIsNotAHeading() {
        assertTrue(NoteSyntax.headings("```\n# 不是标题\n```").isEmpty())
    }

    @Test fun anchorNormalization() {
        assertEquals("导数的定义", NoteSyntax.anchorOf("导数的定义"))
        assertEquals("a-b", NoteSyntax.anchorOf("A  B"))
        assertEquals("c-1", NoteSyntax.anchorOf("C++ 1"))
    }

    // ── 高亮区间 ─────────────────────────────────────────

    @Test fun spansDoNotOverlapAndAreSorted() {
        val body = "#数学 [[导数#定义]] `#代码` 还有 ====="
        val spans = NoteSyntax.spans(body)
        assertTrue(spans.isNotEmpty())
        var last = -1
        for (s in spans) {
            assertTrue("区间必须有序不重叠：" + spans, s.start >= last)
            assertTrue("区间必须非空：" + s, s.end > s.start)
            assertTrue("区间不能越界：" + s, s.end <= body.length)
            last = s.end
        }
    }

    @Test fun codeSpanCoversWholeFence() {
        val body = "前\n```\n#x\n```\n后 #标签"
        val spans = NoteSyntax.spans(body)
        val code = spans.filter { it.kind == NoteSyntax.Kind.CODE }
        assertTrue("围栏块应有代码区间", code.isNotEmpty())
        assertTrue("围栏里的 #x 不该出现在标签区间", spans.none { it.kind == NoteSyntax.Kind.TAG && body.startsWith("#x", it.start) })
        assertTrue("围栏外的标签要保留", spans.any { it.kind == NoteSyntax.Kind.TAG })
    }

    @Test fun spansOnEmptyBody() {
        assertTrue(NoteSyntax.spans("").isEmpty())
    }
}
