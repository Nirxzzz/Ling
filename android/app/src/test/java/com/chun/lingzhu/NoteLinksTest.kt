package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 双链落地规则：这条链接到底指向哪一篇。
 *
 * 这些判断一旦不确定，详情页、反链、导入导出就会各说各话 ——
 * 而「链接悄悄指向了另一篇」是用户最难发现、也最难原谅的一类错。
 * 所以每个歧义场景都钉死：要么唯一命中，要么明确报歧义，绝不猜。
 */
class NoteLinksTest {

    private fun doc(
        uid: String,
        title: String,
        path: String = "",
        aliases: List<String> = emptyList(),
        body: String = "",
    ) = NoteLinks.Doc(uid = uid, title = title, body = body, path = path, aliases = aliases)

    // ── 目标规范化 ───────────────────────────────────────

    @Test fun normalizeStripsSyntax() {
        assertEquals("导数的定义", NoteLinks.normalizeTarget("[[导数的定义|别名]]"))
        assertEquals("导数的定义", NoteLinks.normalizeTarget("导数的定义#标题"))
        assertEquals("notes/导数", NoteLinks.normalizeTarget("Notes\\导数.md"))
        assertEquals("a", NoteLinks.normalizeTarget("./a.md?x=1"))
        assertEquals("a/b", NoteLinks.normalizeTarget("a//b/"))
        assertEquals("带 空格", NoteLinks.normalizeTarget("<带 空格.md>"))
    }

    @Test fun normalizeDecodesPercentEscapes() {
        assertEquals("导", NoteLinks.normalizeTarget("%E5%AF%BC"))
        // 畸形转义按普通文字保留，不能整条丢掉
        assertEquals("a%zzb", NoteLinks.normalizeTarget("a%zzb"))
    }

    @Test fun normalizeIsCaseInsensitiveForComparison() {
        assertEquals(NoteLinks.normalizeTarget("Notes/A"), NoteLinks.normalizeTarget("notes/a"))
    }

    // ── 解析优先级 ───────────────────────────────────────

    @Test fun resolvesByUidFirst() {
        val docs = listOf(
            doc("u1", "甲", "notes/u1"),
            doc("u2", "乙", "notes/u2"),
        )
        val r = NoteLinks.resolve("u2", docs)
        assertTrue(r is NoteLinks.Resolution.Hit)
        r as NoteLinks.Resolution.Hit
        assertEquals("u2", r.doc.uid)
        assertEquals(NoteLinks.Via.UID, r.via)
    }

    @Test fun resolvesByExactPath() {
        val docs = listOf(doc("u1", "甲", "notes/导数"))
        val r = NoteLinks.resolve("Notes/导数.md", docs) as NoteLinks.Resolution.Hit
        assertEquals("u1", r.doc.uid)
        assertEquals(NoteLinks.Via.PATH, r.via)
    }

    @Test fun resolvesByPathSuffix() {
        // Obsidian 的短路径写法：[[导数]] 命中 notes/导数
        val docs = listOf(doc("u1", "甲", "notes/导数"))
        val r = NoteLinks.resolve("导数", docs) as NoteLinks.Resolution.Hit
        assertEquals("u1", r.doc.uid)
        assertEquals(NoteLinks.Via.PATH, r.via)
    }

    @Test fun resolvesByTitle() {
        val docs = listOf(doc("u1", "导数", "notes/x--u1"))
        val r = NoteLinks.resolve("导数", docs) as NoteLinks.Resolution.Hit
        assertEquals(NoteLinks.Via.TITLE, r.via)
    }

    @Test fun resolvesByAlias() {
        val docs = listOf(doc("u1", "导数的定义", "notes/x--u1", aliases = listOf("导数")))
        val r = NoteLinks.resolve("导数", docs) as NoteLinks.Resolution.Hit
        assertEquals(NoteLinks.Via.ALIAS, r.via)
    }
    @Test fun duplicateTitleIsAmbiguousNotGuessed() {
        val docs = listOf(
            doc("a", "笔记", "notes/a"),
            doc("b", "笔记", "notes/b"),
        )
        val r = NoteLinks.resolve("笔记", docs)
        assertTrue("两篇同名必须报歧义，不能静默选第一篇", r is NoteLinks.Resolution.Ambiguous)
        assertEquals(2, (r as NoteLinks.Resolution.Ambiguous).matches.size)
    }

    @Test fun pathBeatsTitle() {
        // 路径和标题各命中一篇：路径优先，结果确定（不能随列表顺序变）
        val docs = listOf(
            doc("t", "导数", "notes/别的--t"),
            doc("p", "标题不一样", "notes/导数"),
        )
        val r = NoteLinks.resolve("导数", docs) as NoteLinks.Resolution.Hit
        assertEquals("p", r.doc.uid)
        assertEquals(NoteLinks.Via.PATH, r.via)
    }

    @Test fun pathSuffixOnlyMatchesAtSegmentBoundary() {
        // [[数]] 不能命中 notes/导数：短路径是「某个目录下的这个名字」，不是子串
        val docs = listOf(doc("u1", "甲", "notes/导数"))
        assertTrue(NoteLinks.resolve("数", docs) is NoteLinks.Resolution.Missing)
    }

    @Test fun duplicateAliasIsNotAmbiguity() {
        // frontmatter 里同一个别名写两遍，是同一篇笔记的重复，不是「两篇同名」
        val docs = listOf(doc("u1", "导数的定义", "notes/x--u1", aliases = listOf("导数", "导数")))
        val r = NoteLinks.resolve("导数", docs) as NoteLinks.Resolution.Hit
        assertEquals("u1", r.doc.uid)
        assertEquals(NoteLinks.Via.ALIAS, r.via)
    }
    @Test fun emptyTargetIsMissing() {
        // [[#标题]] 是本篇的标题链接，不是另一篇笔记
        val r = NoteLinks.resolve("#标题", emptyList())
        assertTrue(r is NoteLinks.Resolution.Missing)
    }

    @Test fun docOrNullOnlyForHit() {
        val docs = listOf(doc("a", "笔记"))
        assertNotNull(NoteLinks.resolve("笔记", docs).docOrNull)
        assertNull(NoteLinks.resolve("别的", docs).docOrNull)
    }

    // ── 引用与反链 ───────────────────────────────────────

    @Test fun referencesSkipEmbedsAndImages() {
        val body = "见 [[a]] 和 [文字](b.md) 还有 ![[c.png]] 与 ![d](e.png)"
        assertEquals(listOf("a", "b"), NoteLinks.references(body))
    }

    @Test fun referencesDedupe() {
        assertEquals(listOf("a"), NoteLinks.references("[[a]] 再说一次 [[A]]"))
    }

    @Test fun referencesSkipExternalUrls() {
        // 网页/邮件永远落不到库内某篇笔记上：算进引用只会让每篇反链多跑一遍注定不命中的解析
        val body = "见 [文档](https://example.com/a) 与 [[https://b.example/x]] 还有 <mailto:a@b.c>"
        assertTrue(NoteLinks.references(body).isEmpty())
        // 库内文件名允许带冒号，白名单不能把它当 scheme 丢掉
        assertEquals(listOf("a:b"), NoteLinks.references("[[a:b]]"))
    }

    @Test fun backlinksIgnoreExternalLinks() {
        val target = doc("t", "导数", "notes/导数--t")
        val docs = listOf(target, doc("a", "甲", body = "[外链](https://example.com/导数)"))
        assertTrue(NoteLinks.backlinks(docs, target).isEmpty())
    }

    @Test fun backlinksFindReferrers() {
        val target = doc("t", "导数", "notes/导数--t")
        val docs = listOf(
            target,
            doc("a", "甲", body = "看 [[导数]]"),
            doc("b", "乙", body = "无关内容"),
            doc("c", "丙", body = "![](attachments/x.png)"),
        )
        assertEquals(listOf("甲"), NoteLinks.backlinks(docs, target).map { it.title })
    }

    @Test fun backlinksViaAliasAndMarkdown() {
        val target = doc("t", "导数的定义", "notes/x--t", aliases = listOf("导数"))
        // 反链问的是「谁引用了 t」：t 自己必须先在本库里，否则
        // backlinks(docs, "t") 连它的路径/别名都不知道（与其它反链用例一致）
        val docs = listOf(
            target,
            doc("a", "b-别名", body = "[[导数|那篇]]"),
            doc("b", "a-路径", body = "[定义](notes/X--t.md)"),
        )
        assertEquals(listOf("a-路径", "b-别名"), NoteLinks.backlinks(docs, "t").map { it.title })
    }

    @Test fun backlinksExcludeSelf() {
        val target = doc("t", "导数", "notes/导数--t", body = "自己引用自己 [[导数]]")
        val docs = listOf(target)
        assertTrue(NoteLinks.backlinks(docs, target).isEmpty())
    }

    @Test fun backlinksSurviveDuplicateTitles() {
        // 两篇同名时 resolve 报歧义；反链不能因此整片消失（宁可多算一条）
        val a = doc("a", "笔记", "notes/a", body = "原文")
        val b = doc("b", "笔记", "notes/b", body = "另一份")
        val c = doc("c", "丙", body = "[[笔记]]")
        val out = NoteLinks.backlinks(listOf(a, b, c), a).map { it.uid }
        assertTrue("同名重复那篇被引用时，两篇都应看到这条反链", out.contains("c"))
    }

    @Test fun backlinksSorted() {
        val target = doc("t", "目标", "notes/t")
        val docs = listOf(
            target,
            doc("z", "b", body = "[[目标]]"),
            doc("y", "a", body = "[[目标]]"),
        )
        // 按标题小写升序，与 docs 里的先后无关
        assertEquals(listOf("a", "b"), NoteLinks.backlinks(docs, target).map { it.title })
    }

    @Test fun backlinksUnknownUidIsEmpty() {
        assertTrue(NoteLinks.backlinks(listOf(doc("a", "甲")), "nope").isEmpty())
    }

    // ── 标题定位 ─────────────────────────────────────────

    @Test fun findHeadingExact() {
        val body = "开头\n\n## 定义\n正文"
        val at = NoteLinks.findHeading(body, "定义")
        assertEquals(body.indexOf("## 定义"), at)
    }

    @Test fun findHeadingAcceptsAnchorAndSubpath() {
        val body = "## 导数的定义\n正文"
        assertEquals(body.indexOf("##"), NoteLinks.findHeading(body, "导数的定义"))
        // [[A#父#子]] 多级写法取最后一级
        assertEquals(body.indexOf("##"), NoteLinks.findHeading(body, "父#导数的定义"))
        // 锚点形态（空格转 -）
        val b2 = "## A  B\n正文"
        assertEquals(0, NoteLinks.findHeading(b2, "a-b"))
    }

    @Test fun findHeadingMissing() {
        assertEquals(-1, NoteLinks.findHeading("## 定义", "不存在"))
    }

    @Test fun findHeadingEmptyMeansTop() {
        assertEquals(0, NoteLinks.findHeading("## 定义", ""))
    }

    @Test fun findHeadingIgnoresHeadingInsideComment() {
        // %%…%% 里被注释掉的标题不是锚点：跳过去会落在看不见的地方
        val body = "%%\n## 定义\n%%\n## 正文标题"
        assertEquals(-1, NoteLinks.findHeading(body, "定义"))
        assertEquals(body.indexOf("## 正文标题"), NoteLinks.findHeading(body, "正文标题"))
    }

    @Test fun findHeadingIgnoresHeadingInsideCode() {
        assertEquals(-1, NoteLinks.findHeading("```\n# 定义\n```", "定义"))
    }

    // ── 附件：安全路径 ───────────────────────────────────

    @Test fun safeRelativeAcceptsInsidePaths() {
        assertEquals("202610/x.jpg", NoteLinks.safeRelative("202610/x.jpg"))
        assertEquals("202610/x.jpg", NoteLinks.safeRelative("./202610//x.jpg"))
        assertEquals("a.png", NoteLinks.safeRelative("a.png#frag"))
        assertEquals("附件 名.png", NoteLinks.safeRelative("附件%20名.png"))
    }

    @Test fun safeRelativeRejectsEscapes() {
        assertNull("绝对路径", NoteLinks.safeRelative("/etc/passwd"))
        assertNull("上一级", NoteLinks.safeRelative("../x.png"))
        assertNull("中间上一级", NoteLinks.safeRelative("a/../../b.png"))
        assertNull("家目录", NoteLinks.safeRelative("~/a.png"))
        assertNull("盘符", NoteLinks.safeRelative("C:/a.png"))
        assertNull("NUL", NoteLinks.safeRelative("a\u0000.png"))
        assertNull("反斜杠上一级", NoteLinks.safeRelative("..\\x.png"))
        assertNull("反斜杠中间上一级", NoteLinks.safeRelative("a\\..\\..\\b.png"))
        assertNull("空串", NoteLinks.safeRelative(""))
        assertNull("只有点", NoteLinks.safeRelative("./"))
    }

    @Test fun attachmentRefUsesLogicalPrefix() {
        assertEquals("attachments/202610/x.jpg", NoteLinks.attachmentRef("202610/x.jpg"))
        // 已经带前缀时不重复叠加
        assertEquals("attachments/202610/x.jpg", NoteLinks.attachmentRef("attachments/202610/x.jpg"))
    }

    @Test fun attachmentResolvesInsideRoot() {
        val root = Files.createTempDirectory("ling27-att").toFile()
        try {
            val f = File(root, "202610/x.jpg")
            f.parentFile!!.mkdirs()
            f.writeText("x")

            assertEquals(f.canonicalFile, NoteLinks.attachment(root, "202610/x.jpg"))
            // md 里的规范形态带 attachments/ 逻辑前缀，落盘要去掉
            assertEquals(f.canonicalFile, NoteLinks.attachment(root, "attachments/202610/x.jpg"))
            assertEquals(f.canonicalFile, NoteLinks.attachment(root, "./attachments/202610/x.jpg"))
            assertNull("不存在的附件", NoteLinks.attachment(root, "202610/没有.jpg"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun attachmentRejectsTraversal() {
        val root = Files.createTempDirectory("ling27-att").toFile()
        try {
            File(root, "a.png").writeText("a")
            File(root.parentFile, "ling27-secret.png").writeText("s")
            assertNull(NoteLinks.attachment(root, "../ling27-secret.png"))
            assertNull(NoteLinks.attachment(root, "/etc/passwd"))
            assertNull(NoteLinks.attachment(root, "~/a.png"))
            assertNull(NoteLinks.attachment(root, "C:/a.png"))
        } finally {
            root.deleteRecursively()
            File(root.parentFile, "ling27-secret.png").delete()
        }
    }

    @Test fun attachmentRejectsTraversalInReferrerPath() {
        val root = Files.createTempDirectory("ling27-att").toFile()
        try {
            val f = File(root, "202610/a.png")
            f.parentFile!!.mkdirs()
            f.writeText("a")
            // 引用方路径里塞 ../：只是不能借它把「相对引用方」那一级候选带出根目录，
            // 根内那条直连候选仍然照常生效
            assertEquals(f.canonicalFile, NoteLinks.attachment(root, "202610/a.png", "../../x/笔记.md"))
            assertNull(NoteLinks.attachment(root, "a.png", "../../202610/笔记.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun attachmentRelativeToReferrer() {
        val root = Files.createTempDirectory("ling27-att").toFile()
        try {
            val f = File(root, "202610/a.png")
            f.parentFile!!.mkdirs()
            f.writeText("a")
            // 引用方 md 在同一层子目录：![](a.png) 也该找到
            assertEquals(f.canonicalFile, NoteLinks.attachment(root, "a.png", "202610/笔记--u1.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun attachmentRejectsMissingRoot() {
        assertNull(NoteLinks.attachment(File("/tmp/ling27-not-a-real-root-xyz"), "a.png"))
    }

    @Test fun attachmentRejectsSymlinkEscape() {
        val root = Files.createTempDirectory("ling27-att").toFile()
        val outside = Files.createTempDirectory("ling27-out").toFile()
        try {
            File(outside, "secret.png").writeText("s")
            val link = File(root, "link.png")
            try {
                Files.createSymbolicLink(link.toPath(), File(outside, "secret.png").toPath())
            } catch (_: Exception) {
                return  // 平台不支持软链接：这条安全性质由路径检查保证
            }
            assertNull("软链接不能把读取带出附件根", NoteLinks.attachment(root, "link.png"))
            assertFalse(link.canonicalFile.path.startsWith(root.canonicalFile.path + File.separator))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
