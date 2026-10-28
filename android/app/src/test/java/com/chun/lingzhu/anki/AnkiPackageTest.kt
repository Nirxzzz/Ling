package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 假 [AnkiPackage.Sql] 共用：从 `... from <表>` 认出表名（小写）。 */
private val FROM_TABLE = Regex("\\bfrom\\s+([A-Za-z_][A-Za-z0-9_]*)", RegexOption.IGNORE_CASE)

/** `select a,b from ...` 的列清单（不含 `select` 本身）。 */
private val SELECT_LIST = Regex("\\bselect\\s+(.+?)\\s+from\\s", RegexOption.IGNORE_CASE)

private fun fromTable(sql: String): String? =
    FROM_TABLE.find(sql)?.groupValues?.get(1)?.lowercase()

/** [sql] 的 select 列表里是否选了 [column]（整列名比较，大小写不敏感）。 */
private fun selects(sql: String, column: String): Boolean =
    SELECT_LIST.find(sql)?.groupValues?.get(1)
        ?.split(',')?.any { it.trim().equals(column, ignoreCase = true) } == true

/**
 * [AnkiPackage] 的纯 JVM 单元测试。
 *
 * 全部用**构造出来的字节**跑，不依赖 /tmp 里的真实验收包 —— 那些包在 CI / 换机时不一定在。
 * zstd 用 [FakeZstd]（只看魔数、剥掉魔数当解压结果）代替：本模块的 zstd 逻辑是「认出魔数 →
 * 交给解压器 → 解压器写进有上限的流」，真正会错的是这条链路和上限，不是 zstd 算法本身；
 * 真算法在设备验收里用真实导出包跑。
 */
class AnkiPackageTest {

    // ─────────────────────────── protobuf ───────────────────────────

    @Test
    fun `meta version 从 varint 读出`() {
        assertEquals(3, AnkiProto.metaVersion(byteArrayOf(0x08, 0x03)))
        assertEquals(2, AnkiProto.metaVersion(byteArrayOf(0x08, 0x02)))
        assertEquals(0, AnkiProto.metaVersion(ByteArray(0)))
    }

    @Test
    fun `meta 被截断时抛`() {
        try {
            AnkiProto.metaVersion(byteArrayOf(0x08))
            fail("截断的 varint 应该抛")
        } catch (_: AnkiProto.MalformedProtoException) {
        }
    }

    @Test
    fun `Notetype Config 读出 cloze 与 css，kind 缺失算普通`() {
        // field1 varint 1 (08 01) + field3 "x" (1a 01 78)
        val cloze = AnkiProto.notetypeConfig(byteArrayOf(0x08, 0x01, 0x1A, 0x01, 0x78))
        assertTrue(cloze.cloze)
        assertEquals("x", cloze.css)

        val normal = AnkiProto.notetypeConfig(byteArrayOf(0x1A, 0x03, 0x2E, 0x63, 0x73))
        assertFalse(normal.cloze)
        assertEquals(".cs", normal.css)
    }

    @Test
    fun `Template Config 读出正反两面`() {
        val blob = byteArrayOf(
            0x0A, 0x02, 0x46, 0x31,   // q_format "F1"
            0x12, 0x02, 0x42, 0x31,   // a_format "B1"
            0x18, 0x05,               // 未用字段 3（varint）应被跳过
        )
        val t = AnkiProto.templateConfig(blob)
        assertEquals("F1", t.front)
        assertEquals("B1", t.back)
    }

    @Test
    fun `MediaEntries 读出名字 大小 sha1 并忽略未知字段`() {
        val sha = ByteArray(20) { it.toByte() }
        val entry = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, 0x08)); write("tiny.wav".toByteArray())     // name
            write(byteArrayOf(0x10, 0xEC.toByte(), 0x0C))                       // size 1644
            write(byteArrayOf(0x1A, 0x14)); write(sha)                          // sha1
            write(byteArrayOf(0x8A.toByte(), 0x0F, 0x00))                         // legacy_zip_filename=0 → field 255, 子消息
        }.toByteArray()
        val msg = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, entry.size.toByte())); write(entry)
        }.toByteArray()

        val list = AnkiProto.mediaEntries(msg)
        assertEquals(1, list.size)
        assertEquals("tiny.wav", list[0].name)
        assertEquals(1644L, list[0].size)
        assertTrue(sha.contentEquals(list[0].sha1))
    }

    @Test
    fun `非法 wire type 直接抛而不是瞎读`() {
        try {
            // tag 的 wire type = 7
            AnkiProto.notetypeConfig(byteArrayOf(0x0F))
            fail("wire type 7 应该抛")
        } catch (_: AnkiProto.MalformedProtoException) {
        }
    }

    // ─────────────────────────── extract ───────────────────────────

    /** 只看魔数：stream 以 zstd 魔数开头就把魔数剥掉当正文，否则原样。 */
    private object FakeZstd : AnkiPackage.Unzstd {
        private val magic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

        override fun decompress(input: InputStream, output: OutputStream) {
            val head = ByteArray(magic.size)
            var got = 0
            while (got < head.size) {
                val n = input.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            if (got == head.size && !head.contentEquals(magic)) throw IllegalStateException("不是 zstd")
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
            }
        }
    }

    private fun zstdStub(payload: ByteArray): ByteArray =
        byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + payload

    private fun sqliteBytes(): ByteArray {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(1024)
        System.arraycopy(magic, 0, out, 0, magic.size)
        return out
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): File {
        val f = File.createTempFile("anki-test-", ".apkg")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        f.deleteOnExit()
        return f
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { f ->
            f.delete()
            f.mkdirs()
            f.deleteOnExit()
            f
        }

    private fun extract(entries: List<Pair<String, ByteArray>>): AnkiPackage.Source {
        val pkg = zip(entries)
        val out = tempDir("anki-out-")
        return AnkiPackage.extract(pkg, out, FakeZstd)
    }

    private fun expectMalformed(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what 应该被拒绝")
        } catch (_: AnkiPackage.MalformedPackageException) {
        }
    }

    @Test
    fun `老包 从 JSON media 索引导入裸素材`() {
        val png = byteArrayOf(1, 2, 3, 4, 5)
        val src = extract(listOf(
            "meta" to byteArrayOf(0x08, 0x02),
            "collection.anki2" to sqliteBytes(),
            "media" to """{"0":"tiny.png"}""".toByteArray(),
            "0" to png,
        ))
        assertEquals(2, src.metaVersion)
        assertEquals("collection.anki2", src.entry)
        assertEquals(1, src.media.size)
        assertEquals("tiny.png", src.media[0].name)
        assertTrue(png.contentEquals(src.media[0].file.readBytes()))
        assertTrue(src.db.isFile && src.db.length() >= 512)
    }

    @Test
    fun `新包 优先取 anki21b 并按 sha1 对上素材`() {
        val wav = byteArrayOf(9, 8, 7, 6)
        val sha = MessageDigest.getInstance("SHA-1").digest(wav)
        val entry = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, 0x08)); write("tiny.wav".toByteArray())
            write(byteArrayOf(0x10, wav.size.toByte()))
            write(byteArrayOf(0x1A, 0x14)); write(sha)
        }.toByteArray()
        val index = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, entry.size.toByte())); write(entry)
        }.toByteArray()

        val src = extract(listOf(
            "meta" to byteArrayOf(0x08, 0x03),
            "collection.anki2" to sqliteBytes(),                                  // 假库：绝不能选中
            "collection.anki21b" to sqliteBytes(),
            "media" to zstdStub(index),
            "0" to zstdStub(wav),
        ))
        assertEquals(3, src.metaVersion)
        assertEquals("collection.anki21b", src.entry)
        val media = src.media.single()
        assertEquals("tiny.wav", media.name)
        // 素材成员名是 "0"（不含真实文件名）也能对上：靠内容哈希
        assertTrue(wav.contentEquals(media.file.readBytes()))
        assertTrue(src.warnings.isEmpty())
    }

    @Test
    fun `素材大小与索引不符时拒包`() {
        val wav = byteArrayOf(1, 1, 1)
        val sha = MessageDigest.getInstance("SHA-1").digest(wav)
        val entry = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, 0x05)); write("a.wav".toByteArray())   // name，长度必须写成实际 5 字节
            write(byteArrayOf(0x10, 0x63))                 // 声明 99 字节，实际 3
            write(byteArrayOf(0x1A, 0x14)); write(sha)
        }.toByteArray()
        val index = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x0A, entry.size.toByte())); write(entry)
        }.toByteArray()
        expectMalformed("大小对不上") {
            extract(listOf(
                "collection.anki2" to sqliteBytes(),
                "media" to zstdStub(index),
                "0" to zstdStub(wav),
            ))
        }
    }

    @Test
    fun `路径穿越条目被拒绝`() {
        expectMalformed("路径穿越") {
            extract(listOf("collection.anki2" to sqliteBytes(), "../evil.png" to byteArrayOf(1)))
        }
    }

    @Test
    fun `绝对路径条目被拒绝`() {
        expectMalformed("绝对路径") {
            extract(listOf("collection.anki2" to sqliteBytes(), "/data/evil.png" to byteArrayOf(1)))
        }
        expectMalformed("反斜杠") {
            extract(listOf("collection.anki2" to sqliteBytes(), "a\\b.png" to byteArrayOf(1)))
        }
    }

    @Test
    fun `忽略大小写的重名条目被拒绝`() {
        expectMalformed("重名") {
            // 必须是**不同字符串、只有大小写不同**：完全相同的名字在 ZipOutputStream 那一层
            // 就会被拒，测不到本模块自己「按忽略大小写判重」的防线。
            extract(listOf(
                "collection.anki2" to sqliteBytes(),
                "Cover.png" to byteArrayOf(1),
                "cover.PNG" to byteArrayOf(2),
            ))
        }
    }

    @Test
    fun `没有牌组库的包被拒绝`() {
        expectMalformed("缺库") {
            extract(listOf("meta" to byteArrayOf(0x08, 0x03), "media" to byteArrayOf(0)))
        }
    }

    @Test
    fun `库不是 SQLite 时被拒绝`() {
        expectMalformed("假库") {
            extract(listOf("collection.anki2" to ByteArray(1024) { 'x'.code.toByte() }))
        }
    }

    @Test
    fun `单条目超过上限被拒绝`() {
        val pkg = zip(listOf("collection.anki2" to sqliteBytes()))
        val out = tempDir("anki-limit-")
        expectMalformed("超上限") {
            AnkiPackage.extract(pkg, out, FakeZstd, AnkiPackage.Limits(maxMemberBytes = 16))
        }
    }

    // ─────────────────────────── parse ───────────────────────────

    private class Row(private val cells: List<Any?>) : AnkiPackage.Row {
        override fun num(column: Int): Long = (cells[column] as? Number)?.toLong() ?: 0L

        override fun str(column: Int): String? = cells[column] as? String

        override fun blob(column: Int): ByteArray? = cells[column] as? ByteArray
    }

    /** 按 SQL 里 `from <表名>` 分发；表不在 [tables] 里就当空结果（模拟老库没有这些表）。 */
    private class FakeSql(
        private val tables: Map<String, List<List<Any?>>>,
        private val present: Set<String>,
    ) : AnkiPackage.Sql {
        override fun hasTable(name: String): Boolean = name in present

        override fun query(sql: String, args: List<Long>): List<AnkiPackage.Row> {
            val rows = tables[fromTable(sql)] ?: return emptyList()
            return rows.map { Row(it) }
        }
    }

    private fun notetypeConfig(kind: Int, css: String): ByteArray {
        val cssBytes = css.toByteArray()
        return byteArrayOf(0x08, kind.toByte()) + byteArrayOf(0x1A, cssBytes.size.toByte()) + cssBytes
    }

    private fun templateConfig(front: String, back: String): ByteArray {
        val f = front.toByteArray()
        val b = back.toByteArray()
        return byteArrayOf(0x0A, f.size.toByte()) + f + byteArrayOf(0x12, b.size.toByte()) + b
    }

    private fun modernSql(): FakeSql {
        val clozeCfg = notetypeConfig(1, ".card{}")
        val normalCfg = notetypeConfig(0, ".card{color:#214}")
        val tables = mapOf(
            "notetypes" to listOf(
                listOf(921L, "Cloze", clozeCfg),
                listOf(924L, "双模板", normalCfg),
                listOf(925L, "空模板", notetypeConfig(0, "")),
            ),
            "fields" to listOf(
                listOf(921L, 0L, "Text"),
                listOf(921L, 1L, "Back Extra"),
                listOf(924L, 0L, "Front"),
                listOf(924L, 1L, "Empty"),
                listOf(924L, 2L, "Back"),
            ),
            "templates" to listOf(
                listOf(921L, 0L, "Cloze", templateConfig("{{cloze:Text}}", "{{cloze:Text}}<br>{{Back Extra}}")),
                listOf(924L, 0L, "正向", templateConfig("{{#Front}}<b>{{Front}}</b>{{/Front}} {{Empty}}", "{{FrontSide}}<hr id=answer>{{Back}}")),
                listOf(924L, 1L, "反向", templateConfig("{{Back}}", "{{Front}}")),
            ),
            "decks" to listOf(
                listOf(1L, "Default"),
                listOf(2L, "Ling验收\u001F基础"),
            ),
            "notes" to listOf(
                listOf(10L, 924L, " 层级::子 验收 ", "真实正面\u001F\u001F真实背面"),
                listOf(11L, 921L, "", "前文 {{c1::钠}} 后"),
            ),
            "cards" to listOf(
                listOf(100L, 10L, 2L, 1L),
                listOf(101L, 10L, 2L, 0L),
                listOf(102L, 11L, 1L, 0L),
                listOf(103L, 999L, 1L, 0L),   // 孤儿卡：笔记不存在
            ),
        )
        return FakeSql(tables, setOf("notetypes", "fields", "templates", "decks", "notes", "cards"))
    }

    @Test
    fun `新版 读模型 模板 牌组 笔记 卡片`() {
        val c = AnkiPackage.parse(modernSql())

        assertEquals(3, c.models.size)          // 没有笔记的模型也必须保留
        val cloze = c.models.first { it.id == 921L }
        assertTrue(cloze.cloze)
        assertEquals(".card{}", cloze.css)
        assertEquals(listOf("Text", "Back Extra"), cloze.fields.map { it.name })
        assertEquals("{{cloze:Text}}", cloze.templates.single().front)

        val normal = c.models.first { it.id == 924L }
        assertFalse(normal.cloze)
        assertEquals(listOf(0, 1, 2), normal.fields.map { it.ord })
        assertEquals("反向", normal.templates[1].name)
        assertEquals("{{Front}}", normal.templates[1].back)

        val base = c.decks.first { it.id == 2L }
        assertEquals("Ling验收::基础", base.name)   // \u001F → ::
        assertEquals(2, base.cards)                // 两张卡落在该牌组
        assertEquals(1, c.decks.first { it.id == 1L }.cards)

        val note = c.notes.first { it.id == 10L }
        assertEquals(listOf("真实正面", "", "真实背面"), note.fields)   // 中间空字段保留
        assertEquals(listOf("层级::子", "验收"), note.tags)

        assertEquals(listOf(100L, 101L, 102L), c.cards.map { it.id })
        assertEquals(1, c.cards.first { it.id == 100L }.ord)
        assertTrue(c.cards.all { it.state.isNew })
        assertTrue(c.warnings.any { it.contains("找不到对应笔记") })
    }

    @Test
    fun `老包 从 col JSON 读模型与牌组`() {
        val models = JSONObject().apply {
            put("924", JSONObject().apply {
                put("id", 924L)
                put("name", "双模板")
                put("type", 0)
                put("css", ".card{color:#214}")
                put("flds", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("name", "Front"); put("ord", 0) })
                    put(JSONObject().apply { put("name", "Back"); put("ord", 1) })
                })
                put("tmpls", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("name", "正向"); put("ord", 0); put("qfmt", "{{Front}}"); put("afmt", "{{FrontSide}}{{Back}}") })
                })
            })
            put("921", JSONObject().apply {
                put("id", 921L)
                put("name", "Cloze")
                put("type", 1)
                put("css", "")
                put("flds", org.json.JSONArray().apply { put(JSONObject().apply { put("name", "Text"); put("ord", 0) }) })
                put("tmpls", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("name", "Cloze"); put("ord", 0); put("qfmt", "{{cloze:Text}}"); put("afmt", "{{cloze:Text}}") })
                })
            })
        }
        val decks = JSONObject().apply {
            put("1", JSONObject().apply { put("id", 1L); put("name", "Default") })
            put("2", JSONObject().apply { put("id", 2L); put("name", "Ling验收::基础") })
            // 筛选牌组（dyn=1）不该被当成真牌组导进来
            put("3", JSONObject().apply { put("id", 3L); put("name", "筛选"); put("dyn", 1) })
        }
        val notes = listOf(listOf<Any?>(10L, 924L, " 层级::子 ", "A\u001F\u001FC"))
        val cards = listOf(listOf<Any?>(100L, 10L, 2L, 0L))
        // col 的查询是 `select models from col` / `select decks from col`，同一张表两种列 ——
        // 按生产 SQL 里选中的列分发（不能按表名，也不能靠 ` models ` 这种带空格的子串：
        // 末尾没有空格的 `select id,nid,did,ord from cards` 会被漏掉，笔记就变成空的）。
        val sql = object : AnkiPackage.Sql {
            override fun hasTable(name: String): Boolean = false

            override fun query(sql: String, args: List<Long>): List<AnkiPackage.Row> = when {
                selects(sql, "models") -> listOf(Row(listOf<Any?>(models.toString())))
                selects(sql, "decks") -> listOf(Row(listOf<Any?>(decks.toString())))
                fromTable(sql) == "notes" -> notes.map { Row(it) }
                fromTable(sql) == "cards" -> cards.map { Row(it) }
                else -> emptyList()
            }
        }

        val c = AnkiPackage.parse(sql)
        assertEquals(listOf(921L, 924L), c.models.map { it.id })
        assertTrue(c.models.first { it.id == 921L }.cloze)
        assertEquals("双模板", c.models.first { it.id == 924L }.name)
        assertEquals("{{Front}}", c.models.first { it.id == 924L }.templates.single().front)
        assertEquals(listOf(1L, 2L), c.decks.map { it.id })          // dyn 被丢掉
        assertEquals("Ling验收::基础", c.decks[1].name)
        assertEquals(1, c.decks[1].cards)
        assertEquals(listOf("A", "", "C"), c.notes.single().fields)
        assertEquals(listOf("层级::子"), c.notes.single().tags)
        assertEquals(1, c.cards.size)
    }

    @Test
    fun `没有笔记或没有卡片就算不合格`() {
        expectMalformed("没有笔记") {
            AnkiPackage.parse(FakeSql(mapOf(
                "notetypes" to listOf(listOf<Any?>(1L, "m", notetypeConfig(0, ""))),
                "fields" to listOf(listOf<Any?>(1L, 0L, "F")),
                "templates" to listOf(listOf<Any?>(1L, 0L, "t", templateConfig("", ""))),
                "decks" to listOf(listOf<Any?>(1L, "Default")),
                "notes" to emptyList(),
                "cards" to emptyList(),
            ), setOf("notetypes", "fields", "templates", "decks", "notes", "cards")))
        }
        expectMalformed("没有卡片") {
            AnkiPackage.parse(FakeSql(mapOf(
                "notetypes" to listOf(listOf<Any?>(1L, "m", notetypeConfig(0, ""))),
                "fields" to listOf(listOf<Any?>(1L, 0L, "F")),
                "templates" to listOf(listOf<Any?>(1L, 0L, "t", templateConfig("", ""))),
                "decks" to listOf(listOf<Any?>(1L, "Default")),
                "notes" to listOf(listOf<Any?>(7L, 1L, "", "x")),
                "cards" to emptyList(),
            ), setOf("notetypes", "fields", "templates", "decks", "notes", "cards")))
        }
    }

    // ─────────────────────────── 持久化 ───────────────────────────

    @Test
    fun `content 写盘再读回一致`() {
        val content = AnkiPackage.parse(modernSql())
        val dir = tempDir("anki-content-")
        val f = File(dir, "content.json")
        AnkiPackage.writeContent(content, f)
        assertTrue(f.isFile)

        val back = AnkiPackage.readContent(f)
        assertEquals(content.models.map { it.id }, back.models.map { it.id })
        assertEquals(content.models[0].css, back.models[0].css)
        assertTrue(back.models[0].cloze)
        assertEquals(content.models[1].templates.map { it.front }, back.models[1].templates.map { it.front })
        assertEquals(content.decks.map { it.name }, back.decks.map { it.name })
        assertEquals(content.decks.map { it.cards }, back.decks.map { it.cards })
        assertEquals(content.notes.map { it.fields }, back.notes.map { it.fields })
        assertEquals(content.notes.map { it.tags }, back.notes.map { it.tags })
        assertEquals(content.cards.map { it.deckId to it.ord }, back.cards.map { it.deckId to it.ord })
        assertEquals(content.warnings, back.warnings)
        assertTrue(back.cards.all { it.state.isNew })
    }

    @Test
    fun `内容文件损坏时抛而不是当空集合`() {
        val dir = tempDir("anki-bad-")
        val f = File(dir, "content.json")
        f.writeText("{not json")
        expectMalformed("损坏") { AnkiPackage.readContent(f) }
    }

    @Test
    fun `内容文件不存在时抛`() {
        val dir = tempDir("anki-none-")
        val e = try {
            AnkiPackage.readContent(File(dir, "content.json"))
            null
        } catch (ex: AnkiPackage.MalformedPackageException) {
            ex
        }
        assertNotNull(e)
        assertNull(null)
    }
}
