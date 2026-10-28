package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Ling 2.7 Anki：**纯 JVM** 的 `.apkg` 解包与内容解析（无 `android.*`，单元测试可直接跑）。
 *
 * 两步，各有明确输入输出：
 *  1. [extract]：`.apkg`（zip）→ 一个已经解压、已经校验过的 SQLite 文件 + 扁平素材文件。
 *     这里负责所有「恶意包」防线：路径穿越、重名条目、体积炸弹、超大条目、
 *     zstd 解压上限、素材 sha1 校验。**条目原名只当数据看**，落盘文件名一律由这里自己给
 *     （素材用索引里的名字，数据库固定叫 `collection.sqlite`），所以包里的路径根本碰不到文件系统。
 *  2. [parse]：通过 [Sql] 抽象读库 → [Content]。Android 侧用 `SQLiteDatabase` 实现 [Sql]；
 *     测试用内存假实现。**SQL 一律不带 `where name=?`**：新版 schema18 的
 *     `notetypes.name` / `fields.name` / `templates.name` / `decks.name` 带 `COLLATE unicase`，
 *     Android 的 SQLite 不认识这个 collation，凡是用到这些列上的索引都会 `no such collation sequence`。
 *     下面每条查询都只用 `order by id` / `order by ntid,ord`，已用真实导出包在无 unicase 的
 *     SQLite 上验证过。
 *
 * 不碰 `.colpkg` 语义、不写 `knowledge.json` / `wordSrs`、不改源包里的数据库
 * （只在私有副本上读；[extract] 也只写自己给的输出目录）。
 */
object AnkiPackage {

    /** 包本身不合格（不是 zip、没有牌组库、数据损坏、体积超限、素材校验失败……）。消息直接给用户看。 */
    class MalformedPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 可替换的 zstd 解压器。抽出来是为了两件事：
     *  - 单元测试跑在 JVM 上，zstd-jni 的 aar 只有 Android `.so`，本机没法真解压 → 测试注入假实现；
     *  - 真解压仍然只有一条路（[ZstdJni]），不散落。
     */
    interface Unzstd {
        fun decompress(input: InputStream, output: OutputStream)
    }

    /**
     * 真实现（`zstd-jni`）。**单独一个类**：只有调用到它时才会加载 zstd 的 native 库，
     * 所以不碰 zstd 的单元测试不会因为缺 `.so` 而挂。
     */
    object ZstdJni : Unzstd {
        override fun decompress(input: InputStream, output: OutputStream) {
            com.github.luben.zstd.ZstdInputStreamNoFinalizer(input).use { z ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buf, 0, n)
                }
            }
        }
    }

    /** 体积与规模上限。默认值按「正常牌组放得下、坏包撑不爆」取。 */
    data class Limits(
        val maxEntries: Int = 20_000,
        val maxMemberBytes: Long = 512L * 1024 * 1024,
        val maxTotalBytes: Long = 1024L * 1024 * 1024,
    )

    /** 一个已经落盘、可以直接给 WebView 用的素材。 */
    data class Media(val name: String, val file: File, val bytes: Long)

    /** [extract] 的结果。[db] 是解压后的 SQLite 文件，[entry] 记下它来自哪个成员（排查用）。 */
    data class Source(
        val db: File,
        val entry: String,
        val metaVersion: Int,
        val media: List<Media>,
        val warnings: List<String>,
    )

    /** 一行查询结果。列号就是 SQL 里的顺序，取值不改类型（DB 里是什么就给什么）。 */
    interface Row {
        fun num(column: Int): Long

        fun str(column: Int): String?

        fun blob(column: Int): ByteArray?
    }

    /** 只读查询抽象。实现必须**不写**库，且不能改 schema。 */
    interface Sql {
        fun hasTable(name: String): Boolean

        fun query(sql: String, args: List<Long> = emptyList()): List<Row>
    }

    /** [parse] 的结果：模型 / 牌组 / 笔记 / 卡片（`due` 由调用方按本地进度算，这里恒为 0）。 */
    data class Content(
        val models: List<AnkiData.Model>,
        val decks: List<AnkiData.Deck>,
        val notes: List<AnkiData.Note>,
        val cards: List<AnkiData.Card>,
        val warnings: List<String>,
    )

    // ───────────────────────────── extract ─────────────────────────────

    private const val META = "meta"
    private const val MEDIA_INDEX = "media"

    private val DB_CANDIDATES = listOf("collection.anki21b", "collection.anki21", "collection.anki2")

    /** 新版库里 `::` 被存成 U+001F。 */
    private const val DECK_SEP = '\u001F'

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    /**
     * 解包 [pkg] 到 [out]：库 → `collection.sqlite`，素材 → `media/<名字>`。
     *
     * 失败抛 [MalformedPackageException]，**已写入 [out] 的部分由调用方整个删掉**
     * （本方法不删，因为 [out] 是调用方的暂存目录，删不删由它决定）。
     */
    fun extract(pkg: File, out: File, unzstd: Unzstd, limits: Limits = Limits()): Source {
        if (!pkg.isFile) throw MalformedPackageException("找不到要导入的文件")
        if (pkg.length() <= 0L) throw MalformedPackageException("文件是空的")
        if (!out.isDirectory && !out.mkdirs()) throw IOException("建不了暂存目录：${out.path}")
        val mediaOut = File(out, "media")
        if (!mediaOut.isDirectory && !mediaOut.mkdirs()) throw IOException("建不了素材目录：${mediaOut.path}")

        val warnings = ArrayList<String>()
        val budget = Budget(limits.maxTotalBytes)

        ZipFile(pkg).use { zip ->
            val entries = Collections.list(zip.entries())
            if (entries.size > limits.maxEntries) {
                throw MalformedPackageException("包里条目太多（${entries.size} 个，上限 ${limits.maxEntries}）")
            }
            val seen = HashSet<String>()
            for (e in entries) {
                val name = e.name
                if (name.isEmpty()) throw MalformedPackageException("包里有名字为空的条目")
                if (e.isDirectory) continue
                checkEntryName(name)
                // 重名按**忽略大小写**判：Android / macOS 的媒体目录不分大小写，重名会互相覆盖
                if (!seen.add(name.lowercase())) throw MalformedPackageException("包里有重名条目：$name")
            }

            val metaVersion = zip.getEntry(META)?.let { readMeta(zip, it, unzstd) } ?: 0

            val dbName = DB_CANDIDATES.firstOrNull { zip.getEntry(it) != null }
                ?: throw MalformedPackageException("这不是 Anki 牌组包：里面没有 collection.anki2")
            val dbFile = File(out, "collection.sqlite")
            materialize(zip, zip.getEntry(dbName), dbFile, budget, limits.maxMemberBytes, unzstd)
            checkSqlite(dbFile)

            val mediaEntry = zip.getEntry(MEDIA_INDEX)
            val media = if (mediaEntry == null) {
                emptyList()
            } else {
                importMedia(zip, mediaEntry, out, mediaOut, budget, limits, unzstd, warnings)
            }

            return Source(dbFile, dbName, metaVersion, media, warnings)
        }
    }

    /**
     * 条目名体检：拒绝绝对路径、`..`、反斜杠、控制字符。
     *
     * 注意这里**不是**靠它防 zip-slip —— 落盘文件名根本不用条目名（见类注释）。
     * 它只是把明显是攻击 / 明显坏了的东西直接拒掉，顺便让「这个包不干净」这件事
     * 以明确错误暴露出来，而不是安静地当普通包导入。
     */
    private fun checkEntryName(name: String) {
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw MalformedPackageException("包里含绝对路径条目：$name")
        }
        if (name.contains('\\')) throw MalformedPackageException("包里含反斜杠路径条目：$name")
        for (ch in name) {
            if (ch < ' ' || ch == '\u007F') throw MalformedPackageException("包里有非法字符的条目名")
        }
        val parts = name.split('/')
        if (parts.any { it == ".." }) throw MalformedPackageException("包里含路径穿越条目：$name")
        if (name.length > 512) throw MalformedPackageException("包里有超长条目名（${name.length} 字符）")
    }

    /** `meta` 很小，直接读进内存；解析失败只当版本未知，不当坏包（老包可能没有 meta）。 */
    private fun readMeta(zip: ZipFile, entry: ZipEntry, unzstd: Unzstd): Int = try {
        val raw = readBytes(zip, entry, 64 * 1024, unzstd)
        AnkiProto.metaVersion(raw)
    } catch (_: Exception) {
        0
    }

    private fun checkSqlite(db: File) {
        if (!db.isFile || db.length() < 512) throw MalformedPackageException("牌组数据是空的")
        val head = ByteArray(SQLITE_MAGIC.length)
        db.inputStream().use { input ->
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < head.size || String(head, Charsets.ISO_8859_1) != SQLITE_MAGIC) {
                throw MalformedPackageException("牌组数据不是有效的 SQLite 数据库")
            }
        }
    }

    // ───────────────────────────── 素材 ─────────────────────────────

    /**
     * 老包：`media` 是 JSON `{zip 内条目名: 素材名}`，条目本身是裸字节。
     * 新包：`media` 是 zstd 压缩的 protobuf `MediaEntries`，条目是 zstd 压缩的单帧。
     * 两种都按「先解 zstd、再看内容像不像 JSON」来分辨 —— 比看 `meta` 版本可靠
     * （第三方工具能混着写）。
     */
    private fun importMedia(
        zip: ZipFile,
        indexEntry: ZipEntry,
        out: File,
        mediaOut: File,
        budget: Budget,
        limits: Limits,
        unzstd: Unzstd,
        warnings: MutableList<String>,
    ): List<Media> {
        val index = readBytes(zip, indexEntry, 32L * 1024 * 1024, unzstd)
        val text = String(index, Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("{")) {
            legacyMedia(zip, JSONObject(text), mediaOut, budget, limits, unzstd, warnings)
        } else {
            modernMedia(zip, index, out, mediaOut, budget, limits, unzstd, warnings)
        }
    }

    private fun legacyMedia(
        zip: ZipFile,
        mapping: JSONObject,
        mediaOut: File,
        budget: Budget,
        limits: Limits,
        unzstd: Unzstd,
        warnings: MutableList<String>,
    ): List<Media> {
        val out = ArrayList<Media>()
        val used = HashSet<String>()
        for (key in mapping.keys()) {
            val memberName = key
            val target = mapping.optString(memberName, "")
            val name = safeMediaName(target)
            if (name == null) {
                warnings += "素材名「$target」不能用作本地文件名，已跳过"
                continue
            }
            if (!used.add(name.lowercase())) throw MalformedPackageException("素材重名：$name")
            val entry = zip.getEntry(memberName)
            if (entry == null || entry.isDirectory) {
                warnings += "素材「$name」在包里找不到（条目 $memberName），已跳过"
                continue
            }
            val dst = File(mediaOut, name)
            val bytes = materialize(zip, entry, dst, budget, limits.maxMemberBytes, unzstd)
            out += Media(name, dst, bytes)
        }
        return out
    }

    /**
     * 新包：先按 sha1 把索引里的每一条精确对上 zip 里的某个成员，再按索引里的名字落盘。
     *
     * 为什么按 sha1 找而不是按成员名找：新版导出把成员写成 `0`/`1`…（也有的工具写成素材名），
     * 索引里另外有一个 `legacy_zip_filename` 字段但**并不总是写**（本仓验收包里就没有）。
     * 内容哈希是唯一不会说谎的对应关系，顺带就把「素材没被损坏」验证掉了。
     */
    private fun modernMedia(
        zip: ZipFile,
        index: ByteArray,
        out: File,
        mediaOut: File,
        budget: Budget,
        limits: Limits,
        unzstd: Unzstd,
        warnings: MutableList<String>,
    ): List<Media> {
        val entries = AnkiProto.mediaEntries(index)
        if (entries.isEmpty()) return emptyList()

        val scratch = File(out, ".members")
        if (!scratch.isDirectory && !scratch.mkdirs()) throw IOException("建不了暂存目录：${scratch.path}")
        try {
            // 成员 → (sha1, 大小)。整包成员数一般就等于素材数，不做无谓过滤。
            val bySha = HashMap<String, File>()
            var picked = 0
            for (e in Collections.list(zip.entries())) {
                if (e.isDirectory) continue
                val n = e.name
                if (n == META || n == MEDIA_INDEX || n in DB_CANDIDATES) continue
                val tmp = File(scratch, "m${picked++}.bin")
                materialize(zip, e, tmp, budget, limits.maxMemberBytes, unzstd)
                bySha[sha1Hex(tmp)] = tmp
            }

            val result = ArrayList<Media>()
            val used = HashSet<String>()
            for (e in entries) {
                val name = safeMediaName(e.name)
                if (name == null) {
                    warnings += "素材名「${e.name}」不能用作本地文件名，已跳过"
                    continue
                }
                if (!used.add(name.lowercase())) throw MalformedPackageException("素材重名：$name")
                val src = bySha[if (e.sha1.isEmpty()) "" else hex(e.sha1)]
                if (src == null) {
                    warnings += "素材「$name」在包里找不到对应数据，已跳过"
                    continue
                }
                if (e.size > 0L && src.length() != e.size) {
                    // sha1 对上了但大小对不上：包自相矛盾，宁可拒绝也不要导入半截素材
                    throw MalformedPackageException("素材「$name」大小与包里的记录不一致（记录 ${e.size}，实际 ${src.length()}）")
                }
                val dst = File(mediaOut, name)
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
                result += Media(name, dst, dst.length())
            }
            return result
        } finally {
            deleteTree(scratch)
        }
    }

    /** 素材名能不能当本地文件名。null = 不能（会给 warning，不静默吞掉内容）。 */
    private fun safeMediaName(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.length > 200) return null
        if (raw == "." || raw == "..") return null
        if (raw.contains('/') || raw.contains('\\')) return null
        if (raw.startsWith(".")) return null
        for (ch in raw) if (ch < ' ' || ch == '\u007F') return null
        return raw
    }

    // ───────────────────────────── 流 / 压缩 ─────────────────────────────

    /** 当前已解压总量。超出上限立刻抛（zip 炸弹解到一半就停，不会先写满磁盘）。 */
    private class Budget(private var left: Long) {
        fun take(n: Long) {
            if (n < 0 || n > left) throw MalformedPackageException("包解压后太大（超过上限），已中止")
            left -= n
        }
    }

    private class BoundedOutput(
        private val out: OutputStream,
        private val limit: Long,
        private val budget: Budget,
    ) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (count + len > limit) throw MalformedPackageException("单个条目解压后太大（超过上限）")
            budget.take(len.toLong())
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }

    /**
     * 把一个 zip 成员写到 [dst]。**入口自己判断是不是 zstd**（看魔数），是就解压，
     * 不是就原样复制 —— 老包成员是裸字节、新包是 zstd，同一个包里也可能混。
     */
    private fun materialize(
        zip: ZipFile,
        entry: ZipEntry,
        dst: File,
        budget: Budget,
        limit: Long,
        unzstd: Unzstd,
    ): Long {
        if (entry.isDirectory) throw MalformedPackageException("条目「${entry.name}」是目录，不能当文件读")
        var total = 0L
        zip.getInputStream(entry).use { raw ->
            val input = PushbackInputStream(raw, ZSTD_MAGIC.size)
            val head = ByteArray(ZSTD_MAGIC.size)
            var got = 0
            while (got < head.size) {
                val n = input.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            if (got > 0) input.unread(head, 0, got)
            val compressed = got == head.size && head.contentEquals(ZSTD_MAGIC)
            FileOutputStream(dst).use { fos ->
                val bounded = BoundedOutput(fos, limit, budget)
                if (compressed) {
                    try {
                        unzstd.decompress(input, bounded)
                    } catch (e: MalformedPackageException) {
                        throw e
                    } catch (e: Exception) {
                        throw MalformedPackageException(
                            "条目「${entry.name}」解压失败：${e.message ?: e.javaClass.simpleName}", e)
                    }
                } else {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        bounded.write(buf, 0, n)
                    }
                }
                bounded.flush()
                total = bounded.count
            }
        }
        return total
    }

    private fun readBytes(zip: ZipFile, entry: ZipEntry, limit: Long, unzstd: Unzstd): ByteArray {
        val buf = ByteArrayOutputStream()
        val bounded = BoundedOutput(buf, limit, Budget(limit))
        zip.getInputStream(entry).use { raw ->
            val input = PushbackInputStream(raw, ZSTD_MAGIC.size)
            val head = ByteArray(ZSTD_MAGIC.size)
            var got = 0
            while (got < head.size) {
                val n = input.read(head, got, head.size - got)
                if (n < 0) break
                got += n
            }
            if (got > 0) input.unread(head, 0, got)
            if (got == head.size && head.contentEquals(ZSTD_MAGIC)) {
                unzstd.decompress(input, bounded)
            } else {
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    if (n == 0) continue
                    bounded.write(b, 0, n)
                }
            }
        }
        return buf.toByteArray()
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun sha1Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    // ───────────────────────────── parse ─────────────────────────────

    /**
     * 从已经解出来的库读全部内容。
     *
     * 新版（有 `notetypes` 表）走 protobuf 列；老版走 `col.models` / `col.decks` 的 JSON。
     * 全部查询见 [Content] 上的说明，均避开了 `COLLATE unicase` 的索引。
     */
    fun parse(sql: Sql): Content {
        val warnings = ArrayList<String>()
        val modern = sql.hasTable("notetypes")
        val models = if (modern) modernModels(sql) else legacyModels(sql)
        if (models.isEmpty()) throw MalformedPackageException("包里没有任何笔记类型，无法导入")
        val decks = if (modern) modernDecks(sql) else legacyDecks(sql)
        val notes = readNotes(sql)
        if (notes.isEmpty()) throw MalformedPackageException("这个包里没有笔记")

        val modelsById = HashMap<Long, AnkiData.Model>(models.size * 2)
        for (m in models) modelsById[m.id] = m
        val notesById = HashMap<Long, AnkiData.Note>(notes.size * 2)
        for (n in notes) notesById[n.id] = n

        val cards = readCards(sql, modelsById, notesById, warnings)
        if (cards.isEmpty()) throw MalformedPackageException("这个包里没有可以学习的卡片")

        val perDeck = HashMap<Long, Int>()
        for (c in cards) perDeck[c.deckId] = (perDeck[c.deckId] ?: 0) + 1
        val known = HashSet<Long>()
        for (d in decks) known += d.id
        val withCounts = ArrayList<AnkiData.Deck>(decks.size)
        for (d in decks) withCounts += d.copy(cards = perDeck[d.id] ?: 0)
        val missing = perDeck.keys.filter { it !in known }.sorted()
        if (missing.isNotEmpty()) {
            for (id in missing) withCounts += AnkiData.Deck(id, "未知牌组 $id", perDeck[id] ?: 0)
            warnings += "有 ${missing.size} 个牌组在包里找不到名字，已按编号列出"
        }
        return Content(models, withCounts, notes, cards, warnings)
    }

    private fun modernModels(sql: Sql): List<AnkiData.Model> {
        val fieldsByNt = LinkedHashMap<Long, MutableList<AnkiData.Field>>()
        for (r in sql.query("select ntid,ord,name from fields order by ntid,ord")) {
            val ntid = r.num(0)
            fieldsByNt.getOrPut(ntid) { ArrayList() } += AnkiData.Field(r.str(2) ?: "", r.num(1).toInt())
        }
        val templatesByNt = LinkedHashMap<Long, MutableList<AnkiData.Template>>()
        for (r in sql.query("select ntid,ord,name,config from templates order by ntid,ord")) {
            val ntid = r.num(0)
            val blob = r.blob(3)
            val cfg = if (blob == null || blob.isEmpty()) {
                AnkiProto.TemplateConfig("", "")
            } else {
                AnkiProto.templateConfig(blob)
            }
            templatesByNt.getOrPut(ntid) { ArrayList() } +=
                AnkiData.Template(r.num(1).toInt(), r.str(2) ?: "", cfg.front, cfg.back)
        }
        val out = ArrayList<AnkiData.Model>()
        for (r in sql.query("select id,name,config from notetypes order by id")) {
            val id = r.num(0)
            val blob = r.blob(2)
            val cfg = if (blob == null || blob.isEmpty()) {
                AnkiProto.NotetypeConfig(AnkiProto.NotetypeConfig.KIND_NORMAL, "")
            } else {
                AnkiProto.notetypeConfig(blob)
            }
            out += AnkiData.Model(
                id = id,
                name = r.str(1) ?: "",
                cloze = cfg.cloze,
                css = cfg.css,
                fields = fieldsByNt[id] ?: emptyList(),
                templates = templatesByNt[id] ?: emptyList(),
            )
        }
        return out
    }

    private fun legacyModels(sql: Sql): List<AnkiData.Model> {
        val json = sql.query("select models from col limit 1").firstOrNull()?.str(0).orEmpty()
        if (json.isBlank()) return emptyList()
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw MalformedPackageException("笔记类型数据损坏（不是合法 JSON）", e)
        }
        val out = ArrayList<AnkiData.Model>()
        for (key in obj.keys()) {
            val m = obj.optJSONObject(key) ?: continue
            val id = m.optLong("id", key.toLongOrNull() ?: 0L)
            if (id == 0L) continue
            val fArr = m.optJSONArray("flds") ?: JSONArray()
            val fields = ArrayList<AnkiData.Field>(fArr.length())
            for (i in 0 until fArr.length()) {
                val f = fArr.optJSONObject(i) ?: continue
                fields += AnkiData.Field(f.optString("name"), f.optInt("ord", i))
            }
            fields.sortBy { it.ord }
            val tArr = m.optJSONArray("tmpls") ?: JSONArray()
            val templates = ArrayList<AnkiData.Template>(tArr.length())
            for (i in 0 until tArr.length()) {
                val t = tArr.optJSONObject(i) ?: continue
                templates += AnkiData.Template(
                    ord = t.optInt("ord", i),
                    name = t.optString("name"),
                    front = t.optString("qfmt"),
                    back = t.optString("afmt"),
                )
            }
            templates.sortBy { it.ord }
            out += AnkiData.Model(
                id = id,
                name = m.optString("name"),
                cloze = m.optInt("type", 0) == 1,
                css = m.optString("css"),
                fields = fields,
                templates = templates,
            )
        }
        out.sortBy { it.id }
        return out
    }

    private fun modernDecks(sql: Sql): List<AnkiData.Deck> {
        val out = ArrayList<AnkiData.Deck>()
        for (r in sql.query("select id,name from decks order by id")) {
            // 新版把层级分隔符 `::` 存成 U+001F，拆回来才是给人看的名字
            out += AnkiData.Deck(r.num(0), (r.str(1) ?: "").replace("\u001F", "::"))
        }
        return out
    }

    private fun legacyDecks(sql: Sql): List<AnkiData.Deck> {
        val json = sql.query("select decks from col limit 1").firstOrNull()?.str(0).orEmpty()
        if (json.isBlank()) return emptyList()
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw MalformedPackageException("牌组数据损坏（不是合法 JSON）", e)
        }
        val out = ArrayList<AnkiData.Deck>()
        for (key in obj.keys()) {
            val d = obj.optJSONObject(key) ?: continue
            // dyn != 0 是「筛选牌组」（保存的搜索），卡片本身在别的牌组里，导入它没有意义
            if (d.optInt("dyn", 0) != 0) continue
            val id = d.optLong("id", key.toLongOrNull() ?: 0L)
            if (id == 0L) continue
            out += AnkiData.Deck(id, d.optString("name"))
        }
        out.sortBy { it.id }
        return out
    }

    private fun readNotes(sql: Sql): List<AnkiData.Note> {
        val out = ArrayList<AnkiData.Note>()
        for (r in sql.query("select id,mid,tags,flds from notes")) {
            // Kotlin 的 split(char) 保留空片段：字段中间的空白必须留着，否则字段下标全部错位
            val fields = (r.str(3) ?: "").split(DECK_SEP)
            val tags = (r.str(2) ?: "").trim().split(TAG_SPLIT).filter { it.isNotEmpty() }
            out += AnkiData.Note(r.num(0), r.num(1), fields, tags)
        }
        return out
    }

    private val TAG_SPLIT = Regex("\\s+")

    private fun readCards(
        sql: Sql,
        models: Map<Long, AnkiData.Model>,
        notes: Map<Long, AnkiData.Note>,
        warnings: MutableList<String>,
    ): List<AnkiData.Card> {
        var orphan = 0
        var noModel = 0
        val out = ArrayList<AnkiData.Card>()
        for (r in sql.query("select id,nid,did,ord from cards")) {
            val nid = r.num(1)
            val note = notes[nid]
            if (note == null) {
                orphan++
                continue
            }
            if (!models.containsKey(note.modelId)) {
                noModel++
                continue
            }
            out += AnkiData.Card(r.num(0), nid, r.num(2), r.num(3).toInt())
        }
        if (orphan > 0) warnings += "有 $orphan 张卡找不到对应笔记，已跳过"
        if (noModel > 0) warnings += "有 $noModel 张卡的笔记类型缺失，已跳过"
        out.sortBy { it.id }
        return out
    }

    // ───────────────────────────── 持久化 ─────────────────────────────

    /**
     * 把 [Content] 写成 JSON。导入时写一次，之后 [AnkiDeckStore] 的每次读都不再碰 SQLite ——
     * 库文件只作原样备份。用 org.json：Android 自带，单元测试用 JVM 实现，两边同一份代码。
     */
    fun writeContent(content: Content, file: File) {
        val root = JSONObject()
        root.put("models", JSONArray().apply {
            for (m in content.models) put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("cloze", m.cloze)
                put("css", m.css)
                put("fields", JSONArray().apply {
                    for (f in m.fields) put(JSONObject().apply { put("name", f.name); put("ord", f.ord) })
                })
                put("templates", JSONArray().apply {
                    for (t in m.templates) put(JSONObject().apply {
                        put("ord", t.ord)
                        put("name", t.name)
                        put("front", t.front)
                        put("back", t.back)
                    })
                })
            })
        })
        root.put("decks", JSONArray().apply {
            for (d in content.decks) put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("cards", d.cards)
            })
        })
        root.put("notes", JSONArray().apply {
            for (n in content.notes) put(JSONObject().apply {
                put("id", n.id)
                put("modelId", n.modelId)
                put("fields", JSONArray().apply { for (f in n.fields) put(f) })
                put("tags", JSONArray().apply { for (t in n.tags) put(t) })
            })
        })
        root.put("cards", JSONArray().apply {
            for (c in content.cards) put(JSONObject().apply {
                put("id", c.id)
                put("noteId", c.noteId)
                put("deckId", c.deckId)
                put("ord", c.ord)
            })
        })
        root.put("warnings", JSONArray().apply { for (w in content.warnings) put(w) })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun readContent(file: File): Content {
        if (!file.isFile) throw MalformedPackageException("这个集合的数据文件不见了：${file.name}")
        val root = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw MalformedPackageException("这个集合的数据文件损坏了", e)
        }
        val models = ArrayList<AnkiData.Model>()
        val mArr = root.optJSONArray("models") ?: JSONArray()
        for (i in 0 until mArr.length()) {
            val o = mArr.optJSONObject(i) ?: continue
            val fArr = o.optJSONArray("fields") ?: JSONArray()
            val fields = ArrayList<AnkiData.Field>(fArr.length())
            for (j in 0 until fArr.length()) {
                val f = fArr.optJSONObject(j) ?: continue
                fields += AnkiData.Field(f.optString("name"), f.optInt("ord", j))
            }
            val tArr = o.optJSONArray("templates") ?: JSONArray()
            val templates = ArrayList<AnkiData.Template>(tArr.length())
            for (j in 0 until tArr.length()) {
                val t = tArr.optJSONObject(j) ?: continue
                templates += AnkiData.Template(
                    ord = t.optInt("ord", j),
                    name = t.optString("name"),
                    front = t.optString("front"),
                    back = t.optString("back"),
                )
            }
            models += AnkiData.Model(
                id = o.optLong("id"),
                name = o.optString("name"),
                cloze = o.optBoolean("cloze", false),
                css = o.optString("css"),
                fields = fields,
                templates = templates,
            )
        }
        val decks = ArrayList<AnkiData.Deck>()
        val dArr = root.optJSONArray("decks") ?: JSONArray()
        for (i in 0 until dArr.length()) {
            val o = dArr.optJSONObject(i) ?: continue
            decks += AnkiData.Deck(o.optLong("id"), o.optString("name"), o.optInt("cards", 0))
        }
        val notes = ArrayList<AnkiData.Note>()
        val nArr = root.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until nArr.length()) {
            val o = nArr.optJSONObject(i) ?: continue
            notes += AnkiData.Note(
                id = o.optLong("id"),
                modelId = o.optLong("modelId"),
                fields = strings(o.optJSONArray("fields")),
                tags = strings(o.optJSONArray("tags")),
            )
        }
        val cards = ArrayList<AnkiData.Card>()
        val cArr = root.optJSONArray("cards") ?: JSONArray()
        for (i in 0 until cArr.length()) {
            val o = cArr.optJSONObject(i) ?: continue
            cards += AnkiData.Card(o.optLong("id"), o.optLong("noteId"), o.optLong("deckId"), o.optInt("ord", 0))
        }
        return Content(models, decks, notes, cards, strings(root.optJSONArray("warnings")))
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out += arr.optString(i, "")
        return out
    }
}
