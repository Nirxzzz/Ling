package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AnkiTemplate：模板渲染的验收测试，数据形状全部照 `/tmp/ling27-fixtures` 里的真实牌组
 * （Anki 26.9.3 导出的 legacy.apkg：2 notes / 4 cards / 2 media）。
 *
 * 断言的是**用户能看到的结果**（正面藏了什么、背面露出了什么、原文有没有被改坏），
 * 不是同义反复地对照实现内部。
 */
class AnkiTemplateTest {

    // ───────────────────────── 夹具（真实值） ─────────────────────────

    private val css = ".card { font-family: sans-serif; color: #214; }"

    private val model = AnkiData.Model(
        id = 1792724918924L,
        name = "Ling多字段双模板",
        cloze = false,
        css = css,
        fields = listOf(
            AnkiData.Field("Front", 0),
            AnkiData.Field("Empty", 1),
            AnkiData.Field("Back", 2),
        ),
        templates = listOf(
            AnkiData.Template(
                ord = 0,
                name = "正向",
                front = "{{#Front}}<b>{{Front}}</b>{{/Front}} {{Empty}}",
                back = "{{FrontSide}}<hr id=answer>{{Back}}",
            ),
            AnkiData.Template(ord = 1, name = "反向", front = "{{Back}}", back = "{{Front}}"),
        ),
    )

    private val clozeModel = AnkiData.Model(
        id = 1792724918921L,
        name = "Cloze",
        cloze = true,
        css = ".card { font-family: arial; }\n.cloze { font-weight: bold; color: blue; }\n",
        fields = listOf(AnkiData.Field("Text", 0), AnkiData.Field("Back Extra", 1)),
        templates = listOf(
            AnkiData.Template(ord = 0, name = "Cloze", front = "{{cloze:Text}}", back = "{{cloze:Text}}<br>\n{{Back Extra}}"),
        ),
    )

    private val note = AnkiData.Note(
        id = 1792724918928L,
        modelId = model.id,
        fields = listOf("真实正面 <img src=\"tiny.png\">", "", "真实背面 [sound:tiny.wav]"),
        tags = listOf("层级::子", "验收"),
    )

    private val clozeNote = AnkiData.Note(
        id = 1792724918929L,
        modelId = clozeModel.id,
        fields = listOf("前文 {{c1::钠::元素}} 和 {{c2::钾}}，再现 {{c1::Na}}", "挖空扩展"),
        tags = emptyList(),
    )

    private fun card(n: AnkiData.Note, ord: Int, id: Long = 1L) =
        AnkiData.Card(id = id, noteId = n.id, deckId = 1792724918924L, ord = ord)

    private fun studyCard(
        n: AnkiData.Note = note,
        m: AnkiData.Model = model,
        ord: Int = 0,
        deck: String = "Ling验收::基础",
    ) = AnkiData.StudyCard("ling", card(n, ord), n, m, deck)

    /** 只换字段值的笔记（保留真实 id/tags），用来测条件块、过滤器与畸形模板。 */
    private fun noteWith(vararg fields: String) = note.copy(fields = fields.toList())

    /** 用一张自定义模板的模型（字段名沿用 Front/Empty/Back）。 */
    private fun modelWith(front: String, back: String, css: String = "", cloze: Boolean = false) =
        model.copy(
            templates = listOf(AnkiData.Template(0, "自定义", front = front, back = back)),
            css = css,
            cloze = cloze,
        )

    // ───────────────────────── 正向 / 反向 两张模板 ─────────────────────────

    @Test fun forwardTemplateKeepsOriginalHtmlAndMedia() {
        val r = AnkiTemplate.render(studyCard(ord = 0))
        // 字段原文（包括 <img>）原样出现，没有被转义成 &lt;img
        assertEquals("<b>真实正面 <img src=\"tiny.png\"></b> ", r.front)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun answerSideEmbedsFrontSideAndSound() {
        val r = AnkiTemplate.render(studyCard(ord = 0))
        assertEquals(
            "<b>真实正面 <img src=\"tiny.png\"></b> <hr id=answer>真实背面 " +
                "<audio controls preload=\"none\" src=\"tiny.wav\"></audio>",
            r.back,
        )
        // [sound:] 是唯一被改写的媒体标记：文件名保持裸相对名，由 UI 的虚拟源解析
        assertFalse(r.back.contains("[sound:"))
        assertFalse(r.back.contains("file://"))
    }

    @Test fun reverseTemplateComesFromCardOrd() {
        val r = AnkiTemplate.render(studyCard(ord = 1))
        assertEquals("真实背面 <audio controls preload=\"none\" src=\"tiny.wav\"></audio>", r.front)
        assertEquals("真实正面 <img src=\"tiny.png\">", r.back)
    }

    @Test fun cssIsReturnedVerbatimAndNotWrapped() {
        val r = AnkiTemplate.render(studyCard())
        assertEquals(css, r.css)
        assertFalse(r.front.contains("<style"))
        assertFalse(r.front.contains("<html"))
        assertFalse(r.back.contains("<body"))
    }

    // ───────────────────────── 空字段与条件块 ─────────────────────────

    @Test fun emptyMiddleFieldRendersEmptyAndDrivesConditions() {
        val r = AnkiTemplate.render(studyCard(ord = 0))
        // {{Empty}} 是空串：正向模板前半段 <b>…</b> 之后只剩一个空格
        assertFalse(r.front.contains("Empty"))
        assertTrue(r.front.endsWith("</b> "))

        val m = modelWith("{{#Empty}}有{{/Empty}}{{^Empty}}空{{/Empty}}", "{{#Empty}}有{{/Empty}}{{^Empty}}空{{/Empty}}")
        val empty = AnkiTemplate.render(studyCard(n = note, m = m))
        assertEquals("空", empty.front)
        assertEquals("空", empty.back)

        val filled = AnkiTemplate.render(
            studyCard(n = noteWith("f", "中间不是空的", "b"), m = m),
        )
        assertEquals("有", filled.front)
    }

    @Test fun nestedConditionalsRequireAllOuterSections() {
        val m = modelWith("{{#Front}}{{#Back}}AB{{/Back}}{{/Front}}", "{{#Front}}{{#Back}}AB{{/Back}}{{/Front}}")
        // 外层不成立：内层即使成立也不出
        assertEquals("", AnkiTemplate.render(studyCard(n = noteWith("f", "", ""), m = m)).front)
        // 两层都成立
        assertEquals("AB", AnkiTemplate.render(studyCard(n = noteWith("f", "", "b"), m = m)).front)

        val neg = modelWith("{{^Back}}没有背面{{/Back}}", "x")
        assertEquals("没有背面", AnkiTemplate.render(studyCard(n = noteWith("f", "", ""), m = neg)).front)
        assertEquals("", AnkiTemplate.render(studyCard(n = noteWith("f", "", "b"), m = neg)).front)
    }

    @Test fun mediaOnlyFieldCountsAsContent() {
        // 只有一张图的字段不是空字段：{{#Front}} 要成立（否则整张图的题面会消失）
        val m = modelWith("{{#Front}}[{{Front}}]{{/Front}}", "x")
        val r = AnkiTemplate.render(studyCard(n = noteWith("<img src=\"tiny.png\">", "", ""), m = m))
        assertEquals("[<img src=\"tiny.png\">]", r.front)
    }

    @Test fun soundOnlyFieldIsEmptyForConditions() {
        val m = modelWith("{{#Front}}有{{/Front}}{{^Front}}空{{/Front}}", "x")
        val r = AnkiTemplate.render(studyCard(n = noteWith("[sound:tiny.wav]", "", ""), m = m))
        assertEquals("空", r.front)
    }

    // ───────────────────────── 挖空 ─────────────────────────

    @Test fun clozeOrdZeroHidesOnlyFirstCloze() {
        val r = AnkiTemplate.render(studyCard(n = clozeNote, m = clozeModel, ord = 0))
        assertEquals(
            "前文 <span class=\"cloze\">[...]</span> 和 钾，再现 <span class=\"cloze\">[...]</span>",
            r.front,
        )
        // 提示 ::元素 只在背面出现
        assertFalse(r.front.contains("元素"))
        assertTrue(r.back.contains("前文 <span class=\"cloze\">钠</span>"))
        assertTrue(r.back.contains("<span class=\"cloze-hint\">元素</span>"))
        assertTrue(r.back.contains("和 钾，再现 <span class=\"cloze\">Na</span>"))
        // 背面的 Back Extra 段与正文之间是模板里的 <br>
        assertTrue(r.back.endsWith("<br>\n挖空扩展"))
    }

    @Test fun clozeOrdOneSwapsWhichPartIsHidden() {
        val r = AnkiTemplate.render(studyCard(n = clozeNote, m = clozeModel, ord = 1))
        assertEquals(
            "前文 钠 和 <span class=\"cloze\">[...]</span>，再现 Na",
            r.front,
        )
        assertTrue(r.back.contains("和 <span class=\"cloze\">钾</span>，"))
        // c2 没有提示，背面不应长出空提示块
        assertFalse(r.back.contains("cloze-hint"))
    }

    @Test fun nestedClozeHidesOuterAndKeepsInnerVisible() {
        val n = clozeNote.copy(fields = listOf("{{c1::外 {{c2::内}}}}", ""))
        // 模板只留 {{cloze:Text}}：断言只对准嵌套挖空的隐藏/揭示语义，
        // 不把模板作者写的分隔符（<br> + 空 Back Extra 的残留）一起钉进字符串。
        val clozeOnly = clozeModel.copy(
            templates = listOf(
                AnkiData.Template(0, "Cloze", front = "{{cloze:Text}}", back = "{{cloze:Text}}"),
            ),
        )

        // c1（ord 0）：正面整块隐藏；背面外层揭示，内层按普通内容展开
        val r1 = AnkiTemplate.render(studyCard(n = n, m = clozeOnly, ord = 0))
        assertEquals("<span class=\"cloze\">[...]</span>", r1.front)
        assertEquals("<span class=\"cloze\">外 内</span>", r1.back)

        // c2（ord 1）：只有内层挖空被隐藏/揭示，外层是普通内容
        val r2 = AnkiTemplate.render(studyCard(n = n, m = clozeOnly, ord = 1))
        assertEquals("外 <span class=\"cloze\">[...]</span>", r2.front)
        assertEquals("外 <span class=\"cloze\">内</span>", r2.back)
    }

    @Test fun clozeTemplateIgnoresCardOrdForTemplateSelection() {
        // 挖空模型的 card.ord 是挖空号，不是模板号：ord=1 的卡也必须用模板 0
        val r = AnkiTemplate.render(studyCard(n = clozeNote, m = clozeModel, ord = 1))
        assertTrue(r.warnings.isEmpty())
        assertNotEquals("", r.front)
    }

    // ───────────────────────── 特殊字段 ─────────────────────────

    @Test fun specialFieldsExposeDeckTagsTypeAndCardName() {
        val m = modelWith(
            "{{Deck}}|{{Subdeck}}|{{Type}}|{{Card}}|{{Tags}}",
            "{{FrontSide}}",
        )
        val r = AnkiTemplate.render(studyCard(m = m, ord = 0))
        assertEquals(
            "Ling验收::基础|基础|Ling多字段双模板|自定义|" +
                "<span class=\"tag\">层级::子</span> <span class=\"tag\">验收</span>",
            r.front,
        )
    }

    @Test fun tagsAreEscapedInsideSpan() {
        val n = note.copy(tags = listOf("a&b", "<x>"))
        val m = modelWith("{{Tags}}", "x")
        val r = AnkiTemplate.render(studyCard(n = n, m = m))
        assertEquals("<span class=\"tag\">a&amp;b</span> <span class=\"tag\">&lt;x&gt;</span>", r.front)
    }

    // ───────────────────────── 过滤器 ─────────────────────────

    @Test fun textFilterStripsMarkupButKeepsWords() {
        val m = modelWith("{{text:Front}}", "{{text:Back}}")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("真实正面", r.front)
        assertEquals("真实背面", r.back)
        assertFalse(r.front.contains("<img"))
    }

    @Test fun chainedFiltersApplyFromFieldOutwards() {
        // {{cloze:text:Text}}：贴着字段的 text 先跑（剥 HTML、解实体），再跑 cloze。
        // 反过来会先把 <b> 裹进 cloze 再被剥掉，挖空标记跟着烂掉 —— 顺序是行为契约。
        // 模板真的写成串联形式，否则这条测的就不是顺序
        val chained = clozeModel.copy(
            templates = listOf(
                AnkiData.Template(0, "Cloze", "{{cloze:text:Text}}", "{{cloze:text:Text}}"),
            ),
        )
        val n = clozeNote.copy(fields = listOf("<b>前</b> {{c1::中}} <i>后</i>", ""))
        val r = AnkiTemplate.render(studyCard(n = n, m = chained, ord = 0))
        assertFalse(r.front.contains("<b>"))
        assertTrue(r.front.contains("<span class=\"cloze\">[...]</span>"))
        assertTrue(r.back.contains("<span class=\"cloze\">中</span>"))
    }

    @Test fun hintFilterIsCollapsedButContentIsPresent() {
        val m = modelWith("{{Front}}", "{{FrontSide}}{{hint:Back}}")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertTrue(r.back.contains("<details class=\"hint\">"))
        assertTrue(r.back.contains("真实背面"))
    }

    @Test fun typeFilterComparesTypedAnswerWhenProvided() {
        val m = modelWith("{{type:Front}}", "{{type:Front}}")
        val c = studyCard(m = m)

        val question = AnkiTemplate.render(c)
        assertTrue(question.front.contains("id=\"typeans\""))

        // 没有用户输入：只给参考答案，并如实说明（不假装判对错）
        val noInput = AnkiTemplate.render(c)
        assertTrue(noInput.back.contains("typeGood"))
        assertTrue(noInput.warnings.any { it.contains("打字题") })

        val right = AnkiTemplate.render(c, "真实正面")
        assertTrue(right.back.contains("typeGood") && !right.back.contains("typeBad"))

        val wrong = AnkiTemplate.render(c, "错的")
        assertTrue(wrong.back.contains("typeBad") && wrong.back.contains("typeGood"))
    }

    // ───────────────────────── 不假装兼容：原样保留 + warning ─────────────────────────

    @Test fun unknownFilterKeepsRawTextAndWarns() {
        val m = modelWith("{{furigana:Front}}", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("{{furigana:Front}}", r.front)
        assertTrue(r.warnings.any { it.contains("furigana") })
    }

    @Test fun unknownFieldKeepsRawTextAndWarns() {
        val m = modelWith("{{NoSuchField}}", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("{{NoSuchField}}", r.front)
        assertTrue(r.warnings.any { it.contains("NoSuchField") })
    }

    @Test fun unclosedSectionKeepsWholeTailAndWarns() {
        val m = modelWith("前{{#Front}}后", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("前{{#Front}}后", r.front)
        assertTrue(r.warnings.any { it.contains("没有闭合") })
    }

    @Test fun extraClosingTagIsPreservedNotDropped() {
        val m = modelWith("前{{/Front}}后", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("前{{/Front}}后", r.front)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test fun scriptIsPreservedButCalledOut() {
        val m = modelWith("{{Front}}<script>var a=1;</script>", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertTrue(r.front.contains("<script>var a=1;</script>"))
        assertTrue(r.warnings.any { it.contains("<script>") })
    }

    @Test fun baseTagIsKeptButCalledOut() {
        // <base href> 能改掉所有素材的相对解析基准。原文不删（不偷偷改内容），
        // 但必须让用户知道这行在本 App 里不生效。
        val m = modelWith("{{Front}}<base href=\"https://evil.example/\">", "x")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertTrue(r.front.contains("<base href=\"https://evil.example/\">"))
        assertTrue(r.warnings.any { it.contains("<base>") })
    }

    @Test fun externalResourceIsCalledOutButKept() {
        val m = modelWith("{{Front}}", "x", css = "@import url(https://example.com/a.css);")
        val r = AnkiTemplate.render(studyCard(m = m))
        assertEquals("@import url(https://example.com/a.css);", r.css)
        assertTrue(r.warnings.any { it.contains("外部") })
    }

    @Test fun imageOcclusionModelWarnsInsteadOfFakingIt() {
        val occ = model.copy(
            name = "Image Occlusion Enhanced",
            fields = listOf(AnkiData.Field("Occlusion", 0)),
            templates = listOf(AnkiData.Template(0, "Card 1", "{{Occlusion}}", "{{Occlusion}}")),
        )
        val n = note.copy(fields = listOf("<img src=\"tiny.png\">"))
        val r = AnkiTemplate.render(studyCard(n = n, m = occ))
        assertTrue(r.warnings.any { it.contains("遮挡") })
        // 遮挡图形没画，但底图不能丢
        assertTrue(r.front.contains("<img src=\"tiny.png\">"))
    }

    @Test fun missingTemplateFallsBackToRawFields() {
        val noTpl = model.copy(templates = emptyList())
        val r = AnkiTemplate.render(studyCard(m = noTpl))
        assertTrue(r.warnings.any { it.contains("没有模板") })
        assertTrue(r.front.contains("真实正面"))
        assertTrue(r.front.contains("真实背面"))
    }

    @Test fun unknownTemplateOrdFallsBackToFirstTemplate() {
        val r = AnkiTemplate.render(studyCard(ord = 7))
        assertTrue(r.warnings.any { it.contains("ord=7") })
        assertEquals("<b>真实正面 <img src=\"tiny.png\"></b> ", r.front)
    }

    // ───────────────────────── 健壮性 ─────────────────────────

    @Test fun renderNeverThrowsOnMalformedTemplate() {
        val junk = listOf(
            "", "{{", "}}", "{{}}", "{{#}}{{/}}", "{{Front", "{{Front}}}}{{/}}",
            "{{cloze:}}", "{{:::}}", "{{#Front}}{{#Back}}", "{{c1::外 {{c2::内}}",
        )
        for (tpl in junk) {
            val m = modelWith(tpl, tpl)
            val r = AnkiTemplate.render(studyCard(m = m))
            // 1) 不抛异常，且同一输入稳定（没有依赖渲染顺序的隐式状态）
            assertEquals(r, AnkiTemplate.render(studyCard(m = m)))
            // 2) 用户模板里的 `{{` 留在输出里 = 没被静默吃掉，那就必须有一条 warning 说明原因，
            //    不能出现「原文还在、用户却不知道它没被展开」的静默失败。
            val keepsMustache = r.front.contains("{{") || r.back.contains("{{")
            if (keepsMustache) assertTrue("$tpl 保留了原样标签却没有 warning: ${r.warnings}", r.warnings.isNotEmpty())
        }
    }

    // ───────────────────────── 沙箱：素材解析 / MIME / 分段 ─────────────────────────

    @get:Rule val tmp = TemporaryFolder()

    /** 素材根：一个真文件 tiny.png，外加一个软链指向根**外面**的文件。 */
    private fun mediaRoot(): File {
        val root = tmp.newFolder("media")
        File(root, "tiny.png").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(root, "歌 名.mp3").writeBytes(ByteArray(0))
        File(root, "a+b.png").writeBytes(byteArrayOf(9))
        File(tmp.root, "secret.txt").writeText("不该被读到")
        try {
            java.nio.file.Files.createSymbolicLink(
                File(root, "link.png").toPath(), File(tmp.root, "secret.txt").toPath(),
            )
        } catch (e: Exception) {
            // 不支持软链的文件系统：这个子用例跳过（下面的断言用 exists 判断）
        }
        File(root, "sub").mkdirs()
        File(root, "sub/nested.png").writeBytes(byteArrayOf(7))
        return root
    }

    private fun resolve(url: String, root: File?): File? = AnkiCardWebView.resolve(root, url)

    @Test fun resolvesPlainMediaName() {
        val root = mediaRoot()
        assertEquals(File(root, "tiny.png").canonicalFile, resolve("https://anki.local/tiny.png", root))
    }

    @Test fun resolvesPercentEncodedName() {
        val root = mediaRoot()
        // 空格和中文都可能是真素材名：解一次码之后必须能命中
        assertEquals(
            File(root, "歌 名.mp3").canonicalFile,
            resolve("https://anki.local/%E6%AD%8C%20%E5%90%8D.mp3", root),
        )
    }

    @Test fun plusIsLiteralNotSpace() {
        // URLDecoder 默认把「+」当空格；文件名里的「+」是合法字符，不能被改写
        val root = mediaRoot()
        assertEquals(File(root, "a+b.png").canonicalFile, resolve("https://anki.local/a+b.png", root))
    }

    @Test fun rejectsTraversalInEveryEncoding() {
        val root = mediaRoot()
        val bad = listOf(
            "https://anki.local/../secret.txt",
            "https://anki.local/%2e%2e%2fsecret.txt",
            "https://anki.local/..%2fsecret.txt",
            "https://anki.local/%2e%2e/secret.txt",
            "https://anki.local/sub/nested.png",          // 子目录也不认
            "https://anki.local/a%2fb.png",               // 编码过的分隔符
            "https://anki.local/a%5cb.png",               // 反斜杠
            "https://anki.local/tiny.png%00.png",         // NUL 截断
            "https://anki.local/./tiny.png",
            "https://anki.local/.",
            "https://anki.local/..",
        )
        for (u in bad) assertNull("$u 不该解析出文件", resolve(u, root))
    }

    @Test fun decodesExactlyOnceAndKeepsLiteralPercentName() {
        // 素材根里真有一个名字带 %2e 的文件：**只解一次码**就会命中它；
        // 如果实现解了两次，%252e 会变成「.」→ 变成 ../secret.txt → 跑到根外面去。
        // 这条同时钉死「没有双重解码」和「%2e 这种字面名仍然可播」。
        val root = mediaRoot()
        val literal = File(root, "%2e%2e%2fsecret.txt")
        literal.writeBytes(byteArrayOf(4, 2))
        assertEquals(literal.canonicalFile, resolve("https://anki.local/%252e%252e%252fsecret.txt", root))
        // 直接写 ../secret.txt（未编码）当然还是拒
        assertNull(resolve("https://anki.local/../secret.txt", root))
    }

    @Test fun rejectsSymlinkEscapingTheRoot() {
        val root = mediaRoot()
        if (!File(root, "link.png").exists()) return   // 文件系统不支持软链
        assertNull(resolve("https://anki.local/link.png", root))
    }

    @Test fun rejectsForeignSchemesAndHosts() {
        val root = mediaRoot()
        val bad = listOf(
            "http://anki.local/tiny.png",                 // scheme 必须 https（底址就是 https）
            "file:///etc/hostname",
            "content://com.chun.lingzhu/tiny.png",
            "https://evil.example/tiny.png",
            "https://anki.local.evil.example/tiny.png",   // 前缀像不像没用，host 必须完全相等
            "https://anki.local@evil.example/tiny.png",   // userinfo 障眼法
            "https://anki.local/",                        // 空名
            "https://anki.local",                         // 无路径
            "javascript:alert(1)",
            "not a url",
            "",
        )
        for (u in bad) assertNull("$u 不该解析出文件", resolve(u, root))
    }

    @Test fun rejectsMissingFileAndNullInputs() {
        val root = mediaRoot()
        assertNull(resolve("https://anki.local/nope.png", root))
        assertNull(AnkiCardWebView.resolve(null, "https://anki.local/tiny.png"))
        assertNull(AnkiCardWebView.resolve(root, null))
        assertNull(AnkiCardWebView.resolve(null, null))
    }

    @Test fun mimeCoversAnkiMediaKinds() {
        assertEquals("image/png", AnkiCardWebView.mimeOf("tiny.png"))
        assertEquals("image/jpeg", AnkiCardWebView.mimeOf("a.JPG"))
        assertEquals("image/jpeg", AnkiCardWebView.mimeOf("a.jpeg"))
        assertEquals("image/gif", AnkiCardWebView.mimeOf("a.gif"))
        assertEquals("image/webp", AnkiCardWebView.mimeOf("a.webp"))
        assertEquals("image/svg+xml", AnkiCardWebView.mimeOf("a.svg"))
        assertEquals("audio/mpeg", AnkiCardWebView.mimeOf("tiny.mp3"))
        assertEquals("audio/wav", AnkiCardWebView.mimeOf("tiny.wav"))
        assertEquals("audio/ogg", AnkiCardWebView.mimeOf("a.oga"))
        assertEquals("audio/mp4", AnkiCardWebView.mimeOf("a.m4a"))
        assertEquals("video/mp4", AnkiCardWebView.mimeOf("a.mp4"))
        assertEquals("video/webm", AnkiCardWebView.mimeOf("a.webm"))
        // 认不出来的扩展名给通用二进制流（不是猜成图片）
        assertEquals("application/octet-stream", AnkiCardWebView.mimeOf("a.xyz"))
        assertEquals("application/octet-stream", AnkiCardWebView.mimeOf("noext"))
    }

    @Test fun textMediaGetsCharsetButBinaryDoesNot() {
        // 牌组自带 .css/.svg 里有中文时必须按 UTF-8 解，否则画面是乱码（不是缺内容）
        assertEquals("utf-8", AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("自定义.css")))
        assertEquals("utf-8", AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("a.js")))
        assertEquals("utf-8", AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("a.svg")))
        // 二进制素材不能声明字符集（声明了等于告诉引擎去做有损转换）
        assertNull(AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("tiny.png")))
        assertNull(AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("tiny.mp3")))
        assertNull(AnkiCardWebView.encodingFor(AnkiCardWebView.mimeOf("a.woff2")))
    }

    @Test fun rangeParsesTheThreeLegalForms() {
        assertEquals(0L to 99L, AnkiCardWebView.parseRange("bytes=0-99", 1000))
        assertEquals(900L to 999L, AnkiCardWebView.parseRange("bytes=-100", 1000))
        assertEquals(100L to 999L, AnkiCardWebView.parseRange("bytes=100-", 1000))
        // 结尾越界按文件末尾截断（HTTP 允许）
        assertEquals(0L to 999L, AnkiCardWebView.parseRange("bytes=0-99999", 1000))
        // 比文件还长的「最后 N 字节」= 整份
        assertEquals(0L to 999L, AnkiCardWebView.parseRange("bytes=-5000", 1000))
        // 大小写不敏感
        assertEquals(0L to 9L, AnkiCardWebView.parseRange("BYTES=0-9", 10))
    }

    @Test fun rangeFallsBackToWholeFileOnAnythingOdd() {
        val junk = listOf(
            null, "", "bytes", "bytes=", "bytes=abc", "bytes=-", "bytes=--1",
            "bytes=1-2,3-4",           // 多段不实现：当整份，别给半份错数据
            "items=0-1",               // 单位不对
            "bytes=1000-",             // 起点就越界
            "bytes=5-1",               // 倒序
            "bytes=-0",                // 零长度
        )
        for (h in junk) assertNull("$h 该退回整份", AnkiCardWebView.parseRange(h, 1000))
        // 空文件没有可给的段
        assertNull(AnkiCardWebView.parseRange("bytes=0-1", 0))
    }

    @Test fun cspForbidsBaseAndNetwork() {
        val csp = AnkiCardWebView.CSP
        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("frame-src 'none'"))
        assertTrue(csp.contains("worker-src 'none'"))
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("form-action 'none'"))
        // 除了自己的虚拟域名，不该出现任何别的 host
        assertFalse(csp.contains("http://"))
        assertTrue(csp.contains("img-src https://${AnkiCardWebView.HOST}"))
        // 内联 base64 图片/音频要能出（data: 不产生请求），否则真实牌组会静默丢图
        assertTrue(csp.contains("img-src https://${AnkiCardWebView.HOST} data:"))
        assertTrue(csp.contains("media-src https://${AnkiCardWebView.HOST} data:"))
    }
}
