package com.chun.lingzhu

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * 文件没有可提取文字时抛出（不可读 / 加密 / 扫描件 / 编码无法识别 / 超限）。
 *
 * [DocumentText.extract] 返回的是正文文本；凡是抽不出内容的情况都抛这个异常，而不是
 * 返回空串或二进制垃圾 —— 调用方捕获后把 [message] 原样展示给用户即可，信息本身就是给用户看的。
 */
class DocumentTextException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * v2.0.7 统一文档取文（纯逻辑 + 两个成熟库）：
 *
 *  - .txt/.md/.markdown/.text：自实现解码。带 BOM 的 UTF-8 / UTF-16LE / UTF-16BE 按 BOM 判定；
 *    无 BOM 先严格按 UTF-8 解，解不动再严格按 GB18030 解，两者都不成立就报错。
 *    不再有「把任意字节当 latin1 塞进去、能捞多少捞多少」的启发式 —— 那种做法会产出乱码当正文。
 *  - .docx：ZIP + OOXML。用 SAX 解析 `word/document.xml`（不是字符串切片），实体、命名空间、
 *    `<w:tab/>`/`<w:br/>` 都按规范处理；解压有上限，超限直接报错，不静默截断。
 *  - .doc：老式 OLE2 二进制。用 Apache POI 的 HWPF（`WordExtractor`）读 piece table 取正文，
 *    中文按文档自带编码还原。之前「抽连续可打印字符」的启发式已删除。
 *  - .pdf：用 PdfBox-Android 做真正的 PDF 解析 + `PDFTextStripper` 取文；加密件、扫描件（无文字层）
 *    都会被明确拒绝。不做 OCR。
 *
 * 所有上限都是「超出即报错」，不会悄悄截掉内容（见 [MAX_INPUT_BYTES]、[MAX_TEXT_CHARS]）。
 *
 * ## 初始化（PDF 必需）
 * PdfBox-Android 的字体/glyph/CMap 资源打包在 assets 里，取文前必须先初始化资源加载器，
 * 否则连拉丁文的 `PDFTextStripper` 都跑不起来。所以：
 *
 * ```
 * // 进程启动时（Application.onCreate 或任一入口 Activity.onCreate）调用一次即可，幂等：
 * DocumentText.init(this)
 * ```
 *
 * 不调用 [init] 时 txt/md/docx/doc 照常工作，只有 pdf 会抛出明确错误
 * 「PDF 解析器未初始化」。见 [isPdfReady]。
 */
object DocumentText {

    /** 输入文件字节上限（40 MB）。超过直接报错，不做截断。 */
    const val MAX_INPUT_BYTES: Int = 40 * 1024 * 1024

    /** 提取出的正文字符上限（800 万字）。超过直接报错，不做截断。 */
    const val MAX_TEXT_CHARS: Int = 8_000_000

    /** docx 里单个 zip 条目解压后的字节上限（64 MB），防 zip 炸弹。 */
    private const val MAX_ZIP_ENTRY_BYTES: Int = 64 * 1024 * 1024

    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )
    private val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)

    @Volatile private var pdfReady = false
    @Volatile private var pdfInitError: String? = null
    private val pdfLock = Any()

    /**
     * 初始化 PDF 解析所需资源（幂等）。**使用 pdf 前必须调用**，传 Application/Activity 均可，
     * 内部会取 applicationContext。失败不抛异常、不崩溃，只把原因记下来，
     * 之后解析 pdf 时会带着原因抛出明确的 [DocumentTextException]。
     */
    fun init(context: Context) {
        if (pdfReady) return
        synchronized(pdfLock) {
            if (pdfReady) return
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                pdfReady = true
                pdfInitError = null
            } catch (t: Throwable) {
                pdfReady = false
                pdfInitError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    /** PDF 资源是否已就绪（[init] 成功过）。 */
    val isPdfReady: Boolean get() = pdfReady

    /**
     * 按文件名后缀取正文。返回规范化（换行统一、去控制字符）后的文本。
     *
     * @throws DocumentTextException 空文件 / 超过大小上限 / 不支持的扩展名 / 编码无法识别 /
     *         加密 / 扫描件（无文字层）/ 解析失败 —— `message` 可直接给用户看。
     */
    fun extract(bytes: ByteArray, fileName: String): String {
        if (bytes.isEmpty()) throw DocumentTextException("文件是空的")
        if (bytes.size > MAX_INPUT_BYTES) {
            throw DocumentTextException("文件过大（${bytes.size / 1024 / 1024} MB，上限 ${MAX_INPUT_BYTES / 1024 / 1024} MB）")
        }

        val ext = extensionOf(fileName)
        val raw = when (ext) {
            "txt", "text", "md", "markdown" -> decodePlainText(bytes)
            "docx" -> extractDocx(bytes)
            "doc" -> extractDoc(bytes)
            "pdf" -> extractPdf(bytes)
            else -> throw DocumentTextException(
                if (fileName.isBlank()) "无法识别文件类型（文件名为空）"
                else "不支持的文件类型：$fileName（支持 .txt .md .docx .doc .pdf）"
            )
        }

        val text = normalize(raw)
        if (text.isBlank()) throw DocumentTextException("没从文件里读到文字")
        if (text.length > MAX_TEXT_CHARS) {
            throw DocumentTextException("文字过多（${text.length} 字，上限 $MAX_TEXT_CHARS），请拆分后再导入")
        }
        return text
    }

    // ---------------- 纯文本（txt/md） ----------------

    private fun decodePlainText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return decodeStrict(bytes, 3, StandardCharsets.UTF_8, "UTF-8")
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16LE, "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return decodeStrict(bytes, 2, StandardCharsets.UTF_16BE, "UTF-16BE")
        }
        // 没有 BOM 还夹着 NUL 字节：按文本处理只会产出垃圾，直接判为二进制
        if (bytes.any { it == 0.toByte() }) {
            throw DocumentTextException("不是文本文件（含二进制内容，且没有 BOM）；仅支持 UTF-8 / 带 BOM 的 UTF-16 / GB18030")
        }
        tryDecode(bytes, StandardCharsets.UTF_8)?.let { return it }
        tryDecode(bytes, Charset.forName("GB18030"))?.let { if (looksTextual(it)) return it }
        throw DocumentTextException("无法识别文字编码（仅支持 UTF-8 / 带 BOM 的 UTF-16 / GB18030）")
    }

    /** 严格解码，任何非法字节都抛 [DocumentTextException]（带 label 说明是哪种编码）。 */
    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset, label: String): String {
        return try {
            strictDecoder(charset).decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            throw DocumentTextException("文字不是有效的 $label 编码", e)
        }
    }

    /** 严格解码，失败返回 null（不抛）。 */
    private fun tryDecode(bytes: ByteArray, charset: Charset): String? = try {
        strictDecoder(charset).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun strictDecoder(charset: Charset) = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /**
     * GB18030 几乎能"解"任意字节，所以解出来还要像人话：不含替换字符、不含控制字符
     * （换行/回车/制表除外）。否则当垃圾丢掉。
     */
    private fun looksTextual(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) {
            if (c == '\n' || c == '\r' || c == '\t') continue
            if (c < ' ' || c == '\u007F') return false
            if (c == '\uFFFD') return false
        }
        return true
    }

    // ---------------- .docx（ZIP + OOXML） ----------------

    private fun extractDocx(bytes: ByteArray): String {
        if (startsWith(bytes, OLE2_MAGIC)) {
            // OOXML 加密后就是一个 OLE2 容器；老 .doc 改名成 .docx 也会落到这里
            throw DocumentTextException("无法读取这个 .docx：它是加密文档，或其实是老式 .doc（请改用未加密的文件或正确的扩展名）")
        }
        if (!isZip(bytes)) throw DocumentTextException("不是有效的 .docx（不是 ZIP 包）")
        val xml = readZipEntry(bytes, "word/document.xml", MAX_ZIP_ENTRY_BYTES)
            ?: throw DocumentTextException("不是有效的 .docx（缺少 word/document.xml）")
        return parseDocxXml(xml)
    }

    /**
     * 只认 `word/document.xml` 里的正文字段：`<w:p>` 段落、`<w:t>` 文本、`<w:tab/>`/`<w:br/>`
     * 转义；`<w:instrText>`、`<w:delText>` 等不取。XML 实体由解析器还原。
     */
    private fun parseDocxXml(xml: ByteArray): String {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try { factory.isXIncludeAware = false } catch (_: Exception) {}

        val handler = DocxHandler()
        try {
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
        } catch (e: DocumentTextException) {
            throw e
        } catch (e: Exception) {
            throw DocumentTextException("解析 .docx 内容失败：" + (e.message ?: "XML 损坏"), e)
        }
        return handler.text()
    }

    private fun trySetFeature(factory: SAXParserFactory, name: String, value: Boolean) {
        try { factory.setFeature(name, value) } catch (_: Exception) { /* 平台不支持就算了 */ }
    }

    private class DocxHandler : DefaultHandler() {
        private val out = StringBuilder()
        private val para = StringBuilder()
        private var capturing = false
        private var chars = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (local(localName, qName)) {
                "t" -> capturing = true
                "tab" -> para.append('\t')
                "br", "cr" -> para.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!capturing || length <= 0) return
            chars += length
            if (chars > DocumentText.MAX_TEXT_CHARS) {
                throw DocumentTextException("文字过多（上限 ${DocumentText.MAX_TEXT_CHARS}），请拆分后再导入")
            }
            para.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(localName, qName)) {
                "t" -> capturing = false
                "p" -> flushParagraph()
            }
        }

        private fun local(localName: String?, qName: String?): String {
            if (!localName.isNullOrEmpty()) return localName
            return qName?.substringAfterLast(':') ?: ""
        }

        private fun flushParagraph() {
            val line = para.toString().trim()
            if (line.isNotEmpty()) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(line)
            }
            para.setLength(0)
        }

        fun text(): String {
            flushParagraph()
            return out.toString()
        }
    }

    // ---------------- .doc（OLE2，POI HWPF） ----------------

    private fun extractDoc(bytes: ByteArray): String {
        if (!startsWith(bytes, OLE2_MAGIC)) {
            throw DocumentTextException("无法读取这个 .doc：不是 OLE2 文档（可能扩展名不对，或文件损坏）")
        }
        return try {
            val doc = HWPFDocument(ByteArrayInputStream(bytes))
            try {
                // WordExtractor.close() 会关掉 doc，这里不再单独 use(HWPFDocument)，避免重复关闭
                val paras = WordExtractor(doc).use { it.paragraphText }
                val sb = StringBuilder()
                for (p in paras) {
                    val line = p.trim()
                    if (line.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(line)
                    }
                }
                sb.toString()
            } finally {
                runCatching { doc.close() }
            }
        } catch (e: EncryptedDocumentException) {
            throw DocumentTextException("这个 .doc 已加密，无法读取文字", e)
        } catch (e: LinkageError) {
            throw DocumentTextException("读取 .doc 失败：解析库不可用（" + (e.message ?: e.javaClass.simpleName) + "）", e)
        } catch (e: Exception) {
            throw DocumentTextException("读取 .doc 失败：" + (e.message ?: "文件损坏"), e)
        }
    }

    // ---------------- .pdf（PdfBox-Android） ----------------

    private fun extractPdf(bytes: ByteArray): String {
        if (!looksLikePdf(bytes)) throw DocumentTextException("不是有效的 PDF（在前 1 KB 内找不到 %PDF- 文件头）")
        if (!pdfReady) {
            val why = pdfInitError?.let { "（初始化失败：$it）" } ?: ""
            throw DocumentTextException("PDF 解析器未初始化：请先调用 DocumentText.init(context)$why")
        }
        var doc: PDDocument? = null
        try {
            val loaded = PDDocument.load(bytes)
            doc = loaded
            val text = PDFTextStripper().getText(loaded)
            if (text.isBlank()) {
                throw DocumentTextException("这个 PDF 没有可提取的文字（可能是扫描图片版，需要 OCR，暂不支持）")
            }
            return text
        } catch (e: InvalidPasswordException) {
            throw DocumentTextException("这个 PDF 已加密，需要密码才能提取文字", e)
        } catch (e: DocumentTextException) {
            throw e
        } catch (e: LinkageError) {
            throw DocumentTextException("读取 PDF 失败：解析库不可用（" + (e.message ?: e.javaClass.simpleName) + "）", e)
        } catch (e: Exception) {
            throw DocumentTextException("读取 PDF 失败：" + (e.message ?: "文件损坏"), e)
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }

    /** PDF 允许文件头出现在前 1024 字节内的任意位置（前面可能有垃圾）。 */
    private fun looksLikePdf(bytes: ByteArray): Boolean {
        val last = minOf(bytes.size, 1024) - PDF_MAGIC.size
        if (last < 0) return false
        for (i in 0..last) if (matchesAt(bytes, i, PDF_MAGIC)) return true
        return false
    }

    // ---------------- 杂项 ----------------

    private fun extensionOf(name: String): String {
        val n = name.trim()
        val dot = n.lastIndexOf('.')
        if (dot < 0 || dot == n.length - 1) return ""
        return n.substring(dot + 1).lowercase()
    }

    private fun isZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val a = bytes[2].toInt() and 0xFF
        val b = bytes[3].toInt() and 0xFF
        return (a == 0x03 && b == 0x04) || (a == 0x05 && b == 0x06) || (a == 0x07 && b == 0x08)
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean = matchesAt(bytes, 0, magic)

    private fun matchesAt(bytes: ByteArray, at: Int, magic: ByteArray): Boolean {
        if (at < 0 || at + magic.size > bytes.size) return false
        for (j in magic.indices) if (bytes[at + j] != magic[j]) return false
        return true
    }

    /** 从 ZIP 里读指定条目；不存在返回 null；解压超过 [maxBytes] 直接报错（防 zip 炸弹）。 */
    private fun readZipEntry(zip: ByteArray, entryName: String, maxBytes: Int): ByteArray? {
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.name == entryName) return readBounded(zin, maxBytes)
                    e = zin.nextEntry
                }
            }
        } catch (e: DocumentTextException) {
            throw e
        } catch (e: Exception) {
            throw DocumentTextException("解压 .docx 失败：" + (e.message ?: "压缩包损坏"), e)
        }
        return null
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw DocumentTextException("文档解压后超过上限（${maxBytes / 1024 / 1024} MB），拒绝处理")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 统一换行为 \n、去掉控制字符和多余的 BOM/零宽字符，首尾去空白。不改动可见字符。 */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\u000B' || c == '\u000C' -> sb.append('\n')   // 竖向制表 / 分页
                c == '\u0007' -> sb.append('\t')                    // Word 单元格标记
                c == '\u0000' || c == '\uFEFF' || c == '\u007F' -> {}
                c < ' ' && c != '\n' && c != '\t' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().trim()
    }
}
