package com.chun.lingzhu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DocumentText 取文行为测试（纯 JVM，跑在桌面 JDK 上）。
 *
 * 说明：PDF 的**成功路径**依赖 PdfBox-Android 的资源加载器（字体/glyph/CMap 资源打包在 aar 的
 * assets 里，必须 `DocumentText.init(context)` 才能读），纯 JVM 单测没有 AssetManager，
 * 所以这里只覆盖 PDF 的失败边界；成功取文由真机运行时验证。
 */
class DocumentTextTest {

    // ---------------- txt / md：编码识别 ----------------

    @Test fun txtUtf8WithBomIsDecodedAndBomRemoved() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "中文段落一\n段落二".toByteArray(Charsets.UTF_8)
        assertEquals("中文段落一\n段落二", DocumentText.extract(bytes, "a.txt"))
    }

    @Test fun txtUtf8WithoutBom() {
        val text = "光合作用是植物制造养分的过程。\n它需要阳光。"
        assertEquals(text, DocumentText.extract(text.toByteArray(Charsets.UTF_8), "A.TXT"))
    }

    @Test fun txtUtf16LeWithBom() {
        val text = "第一行\n第二行"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, DocumentText.extract(bytes, "a.txt"))
    }

    @Test fun txtUtf16BeWithBom() {
        val text = "第一行\n第二行"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, DocumentText.extract(bytes, "a.txt"))
    }

    @Test fun txtGb18030IsDecoded() {
        val text = "光合作用是植物制造养分的过程。"
        val bytes = text.toByteArray(charset("GB18030"))
        assertFalse("前提：这串字节不是合法 UTF-8，否则没测到 GB18030 分支", isValidUtf8(bytes))
        assertEquals(text, DocumentText.extract(bytes, "a.txt"))
    }

    @Test fun mdKeepsMarkdownParagraphs() {
        val md = "# 标题\n\n正文一句。\n\n- 列表项"
        assertEquals(md, DocumentText.extract(md.toByteArray(Charsets.UTF_8), "note.markdown"))
    }

    @Test fun crlfIsNormalizedToLf() {
        assertEquals("a\nb", DocumentText.extract("a\r\nb".toByteArray(Charsets.UTF_8), "a.txt"))
    }

    // ---------------- txt / md：失败边界（必须报错，不能返回空串或乱码） ----------------

    @Test fun emptyFileIsRejected() {
        assertTrue(expectFail(ByteArray(0), "a.txt").message!!.isNotBlank())
    }

    @Test fun binaryWithNulIsRejected() {
        assertTrue(expectFail(byteArrayOf(0x41, 0x00, 0x42), "a.txt").message!!.isNotBlank())
    }

    @Test fun undecodableBytesAreRejected() {
        // PNG 文件头：不是 UTF-8；即便 GB18030 硬解也会解出控制字符 -> 必须报错，不能当乱码文本返回
        assertTrue(expectFail(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "a.txt").message!!.isNotBlank())
    }

    @Test fun unsupportedExtensionIsRejected() {
        assertTrue(expectFail("hello".toByteArray(Charsets.UTF_8), "a.xlsx").message!!.isNotBlank())
    }

    @Test fun oversizedInputIsRejected() {
        val tooBig = ByteArray(DocumentText.MAX_INPUT_BYTES + 1)
        assertTrue(expectFail(tooBig, "a.txt").message!!.isNotBlank())
    }

    // ---------------- docx ----------------

    @Test fun docxExtractsParagraphsTabsBreaksAndEntities() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
          <w:p><w:r><w:t>光合作用是植物制造养分的过程。</w:t></w:r></w:p>
          <w:p><w:r><w:t xml:space="preserve">A&amp;B &lt;C&gt;</w:t><w:br/><w:t>第二句</w:t></w:r></w:p>
          <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>标签</w:t><w:tab/><w:t>后面</w:t></w:r></w:p>
        </w:body></w:document>"""
        val text = DocumentText.extract(docxOf(xml), "a.docx")
        assertTrue(text.contains("光合作用是植物制造养分的过程。"))
        assertTrue(text.contains("A&B <C>"))          // 实体被还原
        assertTrue(text.contains("第二句"))            // <w:br/> 之后的文本仍在
        assertTrue(text.contains("标签\t后面"))        // <w:tab/> 变成制表符
    }

    @Test fun docxSkipsFieldCodesAndDeletedText() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
          <w:p><w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:delText>删除的</w:delText></w:r><w:r><w:t>保留的</w:t></w:r></w:p>
        </w:body></w:document>"""
        val text = DocumentText.extract(docxOf(xml), "a.docx")
        assertEquals("保留的", text)
    }

    @Test fun docxMissingDocumentXmlIsRejected() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<x/>".toByteArray()); z.closeEntry()
        }
        assertTrue(expectFail(bos.toByteArray(), "a.docx").message!!.isNotBlank())
    }

    @Test fun docxThatIsNotAZipIsRejected() {
        assertTrue(expectFail("plain text not zip".toByteArray(Charsets.UTF_8), "a.docx").message!!.isNotBlank())
    }

    @Test fun ole2ContainerNamedDocxIsRejectedAsEncryptedOrOldDoc() {
        // OOXML 加密后就是 OLE2 容器；老 .doc 改名成 .docx 也长这样 —— 都必须明确报错，不能当成 zip 乱解
        assertTrue(expectFail(ole2MagicBytes(), "a.docx").message!!.isNotBlank())
    }

    @Test fun realWordDocxIsExtracted() {
        val docx = gunzip(WORD_DOCX_GZ_B64)
        assertTrue("前提：fixture 是 ZIP 包", docx.size > 4 && docx[0] == 'P'.code.toByte() && docx[1] == 'K'.code.toByte())
        val text = DocumentText.extract(docx, "sample.docx")
        assertTrue(text.contains("光合作用是植物制造养分的过程。"))
        assertTrue(text.contains("它需要阳光、水和二氧化碳。"))
        assertTrue(text.contains("产物是葡萄糖和氧气。"))
    }

    // ---------------- doc（老式 OLE2，POI HWPF） ----------------

    @Test fun realOle2DocChineseIsExtracted() {
        val doc = gunzip(WORD_DOC_GZ_B64)
        assertEquals(0xD0, doc[0].toInt() and 0xFF)
        assertEquals(0xCF, doc[1].toInt() and 0xFF)
        val text = DocumentText.extract(doc, "sample.doc")
        assertTrue("中文正文要被还原（不是抽字节垃圾）", text.contains("光合作用"))
        assertTrue(text.contains("阳光、水和二氧化碳"))
        assertTrue(text.contains("葡萄糖和氧气"))
        assertFalse(text.contains("\uFFFD"))
    }

    @Test fun docThatIsNotOle2IsRejected() {
        assertTrue(expectFail("just text".toByteArray(Charsets.UTF_8), "a.doc").message!!.isNotBlank())
    }

    // ---------------- pdf（纯 JVM 只能覆盖失败边界） ----------------

    @Test fun pdfWithoutHeaderIsRejected() {
        assertTrue(expectFail("这不是 PDF 文件".toByteArray(Charsets.UTF_8), "a.pdf").message!!.isNotBlank())
    }

    @Test fun pdfWithHeaderButNoUsableContentIsRejected() {
        // 没有初始化（或解析失败）都必须以明确异常结束，绝不能返回空串当成功
        assertTrue(expectFail("%PDF-1.4\n1 0 obj\n%%EOF\n".toByteArray(Charsets.US_ASCII), "a.pdf").message!!.isNotBlank())
    }

    // ---------------- helpers ----------------

    private fun docxOf(documentXml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(documentXml.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun ole2MagicBytes(): ByteArray =
        byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        ) + ByteArray(64)

    private fun expectFail(bytes: ByteArray, name: String): DocumentTextException {
        try {
            DocumentText.extract(bytes, name)
        } catch (e: DocumentTextException) {
            return e
        }
        throw AssertionError("应当抛出 DocumentTextException 却成功了：$name")
    }

    private fun gunzip(b64: String): ByteArray =
        GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(b64))).use { it.readBytes() }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        /** textutil 导出的真 Word 97（.doc）文件，gzip+base64；正文含三句中文。 */
        const val WORD_DOC_GZ_B64 = "H4sICEsG0moCA3MuZG9jAO2cTUwTQRSA325LKYXSLX9W8Kei4ScaqQGNB2MgIgKaQmkTD3qQf5pswVRAUA8YMdGERIyJXEwMJMSDidFIvIoe9KRywUROeCTRiNw4yPpmdgrlT1pihNr3NW9ndmZn3vTNzO7OZt9OfLJPD7/I/gorOAkGWNCSwBSWJqHsC+0oAPtF2oKmaSzJiaIRMcW30TeiQ2fTXuu9bMdOlVjH16GkQhMC65DFDgBvjy6F8Gc0zbZhPITKtyNibwQ2hh2TAZHzCiUF5a0IQ/xgIxq2ju84pZzy0v4USiYQ252PK8YRQWx/qjxm38Wagq6iFqvfHzxW5xy87jHV9Xe/v5d8TXZZ4dYFGLp5t/hhlUdylagDvgF3vprlfdnD8p64/cGilvp+Y//nGwO+fDVDZalETCGu8/NkiTghpabaAB6UU9WOpfu8GTLMfwLek1vf5UGKS75kHDSNm2ctTmutrc/+PH06U3GUZi87eLH/ibhEIhPEN2lgAwsOAz+GMhj4rwSg9KcmsxBPD27ogCAEoJ6vyWW+ymaXDnYZqRWXkkBlknF13SegrHROG8aQnZXKoRlasJYurKcTnFCL8SBKK99ehjZMq0Bd7Zi7kkLxnIA9Z+JbcdYyht3BJITF2QlNVpYV1p9ksQJnTIMRDXsfGiWAjb6CDXNjeBXDOmwgM0U75h/FeuQI6vFCL5ZpwJLMgMURai9Ds/iF0SNtcSW2UoVu3HZi2UYs7YRzGGtF43byB3RGcJnvOGB2AhvexuucOqvmMVl9NpD4vhEMirQqNZTiNMCsQrNok4yaxhPHYAz6IM3192tnYzN3aVpiP5nJ5ARBEARBEAQRzyzgitpoWb26ZCnTtx/Pzde0KU/vm+Fg/ssvbI3iEHlMFLHImPlFdiQIgiAIgiAIgiCIWF3/y5MfJh8dzlEeDOH6/9D8M1r/EwRBEARBEARBEERsIok1vAH0l+PZW/TsTflE0L/rkIShBSUZdO9m5t+ZimIT+XZgPgQA6aB73zNP9SyUHSJ/J4bM5SgHZRfKbpQ9KHtFPpPcsPgCfZLhn8IcGjq4P8Zp7nkRhN6oxk8WJEihutgYMiXpLhHjenbFWmUOoPSJ+BHwQT00gArNmxq/qTh6w/9PJGX4a/DCa+E8925pgnIMG6GLO3ys5YGyHtkgS2wORaMfwvQngJdrDXBvmF6oQu0tix43zI2jgzubrEcB6pfF3I1Uv7JM/8p/Hl17jqP+aO3vCNNPEARBbA2/AURuge4ATAAA"

        /** textutil 导出的真 .docx 文件，gzip+base64；正文含三句中文。 */
        const val WORD_DOCX_GZ_B64 = "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"
    }
}
