package com.chun.lingzhu

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Ling 2.7 Anki：导入包 → 本地集合（`filesDir/anki` 独占一个目录），以及之后的学习读写。
 *
 * 磁盘布局（每个集合一个目录，名字就是 [AnkiData.ImportResult.collection]）：
 * ```
 * filesDir/anki/
 *   <集合>/
 *     source.apkg      原包字节，原样保留（用户重导 / 排查用；导入后不再读）
 *     content.json     解析结果（模型 / 牌组 / 笔记 / 卡片），之后所有读都只读它
 *     import.json      来源信息（sha256 + 原文件名 + 数量 + 提示）
 *     progress.json    学习进度：Anki 卡 id → Fsrs.Card
 *     media/           素材，**全部是扁平的直接子文件**（渲染器按 https://anki.local/<名字> 取）
 * ```
 *
 * 三条硬约束：
 *  - **原子发布**：先在同级 `.stage-*` 目录里完整解包 + 校验 + 解析，全部成功才把目录改名成
 *    正式名字。任何一步失败都只删暂存目录，已有集合一个字节都不动。
 *  - **进度按 Anki 卡 id 记**，重导同一个包（字节相同）沿用原集合目录和进度；
 *    字节不同则新开一个 `-2` / `-3` 集合，绝不覆盖旧的。
 *  - **抛给上层的异常消息一律是给用户看的中文短句**（UI 直接 `e.message` 显示）。
 *
 * 线程：所有方法都是同步 IO，调用方在后台线程调用（见 [AnkiActivity] 的 `bg(...)`）。
 */
class AnkiDeckStore(private val ctx: Context) {

    private val root: File = File(ctx.filesDir, "anki")
    private val io = AnkiPackage.ZstdJni

    // 解析结果按集合缓存：一轮学习里 queue/decks 会被反复调用，重复解析几 MB JSON 没有意义。
    private val cache = HashMap<String, Cached>()

    private class Cached(val mtime: Long, val content: AnkiPackage.Content)

    // ───────────────────────────── 导入 ─────────────────────────────

    /**
     * 导入一个 `.apkg`。
     *
     * 流程：SAF 流 → 暂存 `source.apkg`（同时算 sha256）→ [AnkiPackage.extract]（解包 + 全部校验）
     * → [AnkiPackage.parse]（读库）→ 写 `content.json` / `import.json` → 目录改名发布。
     *
     * 同名集合已存在时：字节相同 → 直接沿用（进度保留，不重复写盘）；不同 → 名字加 `-2`、`-3`…
     *
     * @return 真实导入数量与提示；集合名以返回值里的 [AnkiData.ImportResult.collection] 为准
     */
    fun importPackage(uri: Uri): AnkiData.ImportResult {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("建不了 Anki 目录：${root.path}")
        val displayName = displayName(uri)
        val base = collectionName(displayName)
        val stage = File(root, ".stage-${UUID.randomUUID()}")
        if (!stage.mkdirs()) throw IOException("建不了暂存目录：${stage.path}")

        try {
            val apkg = File(stage, "source.apkg")
            val sha = copyAndHash(uri, apkg)

            // 字节完全相同：这个包已经导过了，沿用原集合（含学习进度），不再解一遍
            findSame(base, sha)?.let { existing ->
                val meta = readImportMeta(existing) ?: return@let null
                deleteTree(stage)
                return AnkiData.ImportResult(
                    collection = existing.name,
                    notes = meta.optInt("notes"),
                    cards = meta.optInt("cards"),
                    media = meta.optInt("media"),
                    warnings = emptyList(),
                )
            }

            val work = File(stage, "work")
            val source = AnkiPackage.extract(apkg, work, io)
            val content = try {
                val sql = SqliteSource(source.db)
                try {
                    AnkiPackage.parse(sql)
                } finally {
                    sql.close()
                }
            } finally {
                // 只留原包；解析结果在 content.json 里，这份解压出来的库没有留存价值
                source.db.delete()
            }
            AnkiPackage.writeContent(content, File(stage, "content.json"))

            val warnings = (source.warnings + content.warnings).distinct()
            JSONObject().apply {
                put("sha256", sha)
                put("name", displayName ?: base)
                put("at", System.currentTimeMillis())
                put("notes", content.notes.size)
                put("cards", content.cards.size)
                put("media", source.media.size)
                put("warnings", org.json.JSONArray().apply { for (w in warnings) put(w) })
            }.let { meta ->
                File(stage, "import.json").writeText(meta.toString(), Charsets.UTF_8)
            }
            // 素材/库都在 work/ 里，先挪到暂存根再改名，省得发布时多一层目录
            File(work, "media").let { m -> if (m.isDirectory) m.renameTo(File(stage, "media")) }
            deleteTree(work)

            val target = uniqueName(base)
            if (!stage.renameTo(target)) throw IOException("发布集合失败（目录改名失败）：${target.name}")
            cache.clear()
            return AnkiData.ImportResult(target.name, content.notes.size, content.cards.size, source.media.size, warnings)
        } finally {
            // 成功时 stage 已经改名，这里删的是不存在的路径（无副作用）
            deleteTree(stage)
        }
    }

    /** 有没有一个同名族（`base` / `base-2`…）的集合，且来自同一份字节。 */
    private fun findSame(base: String, sha: String): File? {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return null
        for (d in dirs) {
            if (d.name != base && !d.name.startsWith("$base-")) continue
            val meta = readImportMeta(d) ?: continue
            if (meta.optString("sha256") == sha) return d
        }
        return null
    }

    private fun readImportMeta(dir: File): JSONObject? = try {
        val f = File(dir, "import.json")
        if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else null
    } catch (_: Exception) {
        null
    }

    /** 第一个没被占用的名字：`n` / `n-2` / `n-3`… */
    private fun uniqueName(base: String): File {
        var candidate = File(root, base)
        var i = 1
        while (candidate.exists()) {
            i++
            candidate = File(root, "$base-$i")
        }
        return candidate
    }

    /**
     * 集合名取文件名（不含扩展名），过滤掉不能做目录名的字符。
     * 名字里必须留得住文件名主干 —— 导入后界面靠它认出「刚导进来的是哪个」。
     */
    private fun collectionName(displayName: String?): String {
        val stem = displayName?.substringBeforeLast('.', displayName)?.trim().orEmpty()
        val cleaned = buildString {
            for (ch in stem) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == ' ' || ch == '(' || ch == ')' ||
                    ch.code > 0x7F) ch else '_')
            }
        }.trim().trim('.').take(48)
        return cleaned.ifBlank { "anki" }
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun copyAndHash(uri: Uri, dst: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input: InputStream = ctx.contentResolver.openInputStream(uri)
            ?: throw AnkiPackage.MalformedPackageException("打不开这个文件（系统没给读权限）")
        input.use { src ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        if (dst.length() <= 0L) throw AnkiPackage.MalformedPackageException("文件是空的")
        return hex(md.digest())
    }

    // ───────────────────────────── 读 ─────────────────────────────

    /** 所有已发布的集合名（按名字排序）。暂存目录、半成品不会被列出来。 */
    fun collections(): List<String> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") && File(it, "content.json").isFile }
            .map { it.name }
            .sorted()

    /**
     * 一个集合里的牌组（含层级名里的 `::`）。[AnkiData.Deck.cards] 是卡数源数据，
     * [AnkiData.Deck.due] 是「现在就该复习」的数量（新卡算在内），按当前时间算。
     */
    fun decks(collection: String): List<AnkiData.Deck> {
        val content = content(collection)
        val progress = progress(collection)
        val now = System.currentTimeMillis()
        val due = HashMap<Long, Int>()
        for (c in content.cards) {
            val state = progress[c.id]
            if (state == null || Fsrs.isDue(state, now)) due[c.deckId] = (due[c.deckId] ?: 0) + 1
        }
        return content.decks.map { it.copy(due = due[it.id] ?: 0) }
    }

    /**
     * 取一轮要学的卡（确定性快照）：新卡优先，其后按 FSRS 到期时间升序，同到期时间按卡 id。
     *
     * @param deckId 只取这个牌组（含其子牌组）；null = 整个集合
     * @param limit 最多几张；`<= 0` 表示不限
     */
    fun queue(collection: String, deckId: Long?, now: Long, limit: Int = 100): List<AnkiData.StudyCard> {
        val content = content(collection)
        val progress = progress(collection)
        val models = HashMap<Long, AnkiData.Model>(content.models.size * 2)
        for (m in content.models) models[m.id] = m
        val notes = HashMap<Long, AnkiData.Note>(content.notes.size * 2)
        for (n in content.notes) notes[n.id] = n
        val deckNames = HashMap<Long, String>(content.decks.size * 2)
        for (d in content.decks) deckNames[d.id] = d.name

        val deckFilter = deckScope(content.decks, deckId)
        val picked = ArrayList<AnkiData.Card>()
        for (c in content.cards) {
            if (deckFilter != null && c.deckId !in deckFilter) continue
            val state = progress[c.id]
            if (state != null && !Fsrs.isDue(state, now)) continue
            picked += c
        }
        picked.sortWith(compareBy({ (progress[it.id] ?: Fsrs.Card()).state != Fsrs.State.New },
            { progress[it.id]?.due ?: 0L }, { it.id }))
        val capped = if (limit > 0 && picked.size > limit) picked.subList(0, limit).toList() else picked

        val out = ArrayList<AnkiData.StudyCard>(capped.size)
        for (c in capped) {
            val note = notes[c.noteId] ?: continue
            val model = models[note.modelId] ?: continue
            out += AnkiData.StudyCard(
                collection = collection,
                card = c.copy(state = progress[c.id] ?: Fsrs.Card()),
                note = note,
                model = model,
                deck = deckNames[c.deckId] ?: "未知牌组",
            )
        }
        return out
    }

    /** 牌组范围：自身 + 名字在它下面（`名::…`）的所有牌组。null = 整个集合。 */
    private fun deckScope(decks: List<AnkiData.Deck>, deckId: Long?): Set<Long>? {
        if (deckId == null) return null
        val self = decks.firstOrNull { it.id == deckId }
            ?: throw IllegalArgumentException("找不到这个牌组（可能刚被重新导入）")
        val prefix = self.name + "::"
        val out = HashSet<Long>()
        out += self.id
        for (d in decks) if (d.name.startsWith(prefix)) out += d.id
        return out
    }

    // ───────────────────────────── 写 ─────────────────────────────

    /**
     * 给一张卡评分并**立刻落盘**。落盘失败抛异常（上层据此不前进），不会假装成功。
     *
     * @return 评分后的新状态（调用方一般不需要，保留给调试 / 提示用）
     */
    fun answer(collection: String, cardId: Long, rating: Fsrs.Rating, now: Long): Fsrs.Card {
        val content = content(collection)
        val card = content.cards.firstOrNull { it.id == cardId }
            ?: throw IllegalArgumentException("找不到这张卡（可能刚被重新导入）")
        val dir = collectionDir(collection)
        val progress = progress(collection)
        val before = progress[cardId] ?: card.state
        val after = Fsrs.review(before, rating, now)

        val map = JSONObject()
        for ((id, state) in progress) map.put(id.toString(), JSONObject(Fsrs.toJson(state)))
        map.put(cardId.toString(), JSONObject(Fsrs.toJson(after)))
        writeAtomic(File(dir, PROGRESS), map.toString())
        cacheProgress(collection, map)
        return after
    }

    /**
     * 素材目录：集合下的 `media/`，里面的文件**都是扁平的直接子文件**
     * （[AnkiCardWebView] 只按 `https://anki.local/<名字>` 取，且要求是它的直接子节点）。
     * 集合里没有素材时也返回一个存在（可能为空）的目录，调用方不用判空。
     */
    fun mediaDir(collection: String): File {
        val dir = File(collectionDir(collection), "media")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("建不了素材目录：${dir.path}")
        return dir
    }

    private fun collectionDir(collection: String): File {
        if (collection.isBlank()) throw IllegalArgumentException("没有选中集合")
        val dir = File(root, collection)
        if (!dir.isDirectory) throw IllegalArgumentException("集合不存在：$collection")
        return dir
    }

    private fun content(collection: String): AnkiPackage.Content {
        val file = File(collectionDir(collection), CONTENT)
        if (!file.isFile) throw AnkiPackage.MalformedPackageException("集合「$collection」的数据文件不见了，请重新导入")
        val mtime = file.lastModified()
        synchronized(cache) {
            val hit = cache[collection]
            if (hit != null && hit.mtime == mtime) return hit.content
        }
        val parsed = AnkiPackage.readContent(file)
        synchronized(cache) { cache[collection] = Cached(mtime, parsed) }
        return parsed
    }

    // 进度读得比内容频繁（每答一张都要），单独留一份内存副本，写盘成功才更新
    private val progressCache = HashMap<String, MutableMap<Long, Fsrs.Card>>()

    private fun progress(collection: String): Map<Long, Fsrs.Card> {
        synchronized(progressCache) { progressCache[collection]?.let { return it.toMap() } }
        val file = File(collectionDir(collection), PROGRESS)
        val out = HashMap<Long, Fsrs.Card>()
        if (file.isFile) {
            val obj = try {
                JSONObject(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                // 进度坏了不能装作没有（那样会让用户丢掉复习记录毫无提示）
                throw AnkiPackage.MalformedPackageException("集合「$collection」的学习进度文件损坏", e)
            }
            for (key in obj.keys()) {
                val id = key.toLongOrNull() ?: continue
                Fsrs.cardFromJson(obj.optJSONObject(key)?.toString())?.let { out[id] = it }
            }
        }
        synchronized(progressCache) { progressCache[collection] = HashMap(out) }
        return out
    }

    private fun cacheProgress(collection: String, obj: JSONObject) {
        val map = HashMap<Long, Fsrs.Card>(obj.length() * 2)
        for (key in obj.keys()) {
            val id = key.toLongOrNull() ?: continue
            Fsrs.cardFromJson(obj.optJSONObject(key)?.toString())?.let { map[id] = it }
        }
        synchronized(progressCache) { progressCache[collection] = map }
    }

    /** 先写 `.tmp` 再改名：断电 / 进程被杀不会留下半个 JSON。 */
    private fun writeAtomic(dst: File, text: String) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(dst)) {
                throw IOException("无法原子替换学习进度文件")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IOException("保存学习进度失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    // ───────────────────────────── 小件 ─────────────────────────────

    /** 用 Android 的 `SQLiteDatabase` 实现 [AnkiPackage.Sql]。只读语义，但以读写方式打开私有副本（见下）。 */
    private class SqliteSource(private val file: File) : AnkiPackage.Sql {

        private var db: SQLiteDatabase? = null

        /**
         * **必须用读写方式打开私有副本**：新版导出的库声明 `journal_mode=wal`，
         * 只读打开时 SQLite 需要恢复 `-wal` 才能读，而恢复要写 → `attempt to write a readonly database`。
         * 这个文件是解包出来的临时副本（导入后立刻删除），改它不涉及源包，也不注册到系统。
         */
        private fun open(): SQLiteDatabase =
            db ?: SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).also { db = it }


        /** 用完就关：句柄留着会占着 `-wal` / `-shm`，删目录时的行为也不干净。 */
        fun close() {
            db?.close()
            db = null
        }

        /**
         * 表在不在。**必须按名字绑参数**：`query` 只接受数字参数（[AnkiPackage.Sql] 的接口如此），
         * 拿 0 去比 `name` 永远比不中 —— 新版库会被当成老库去读 `col` 表。
         */
        override fun hasTable(name: String): Boolean {
            open().rawQuery("select name from sqlite_master where type='table' and name=?", arrayOf(name))
                .use { c -> return c.moveToFirst() }
        }

        override fun query(sql: String, args: List<Long>): List<AnkiPackage.Row> {
            val d = open()
            val rows = ArrayList<AnkiPackage.Row>()
            val cursor = if (args.isEmpty()) {
                d.rawQuery(sql, null)
            } else {
                d.rawQuery(sql, args.map { it.toString() }.toTypedArray())
            }
            // 值必须在 cursor 还开着的时候抄出来：Row 在 query 返回之后才被读，
            // 而 cursor 那时已经关了（读已关闭的 Cursor 会抛）。
            cursor.use { c ->
                while (c.moveToNext()) {
                    val cells = ArrayList<Any?>(c.columnCount)
                    for (i in 0 until c.columnCount) {
                        cells += when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> null
                        }
                    }
                    rows += SnapshotRow(cells)
                }
            }
            return rows
        }

        /** 一行的快照，脱离 Cursor 之后照样能读。 */
        private class SnapshotRow(private val cells: List<Any?>) : AnkiPackage.Row {
            override fun num(column: Int): Long = when (val v = cells.getOrNull(column)) {
                is Long -> v
                is Double -> v.toLong()
                is String -> v.trim().toLongOrNull() ?: 0L
                else -> 0L
            }

            override fun str(column: Int): String? = cells.getOrNull(column) as? String

            override fun blob(column: Int): ByteArray? = cells.getOrNull(column) as? ByteArray
        }
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

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    private companion object {
        const val CONTENT = "content.json"
        const val PROGRESS = "progress.json"
    }
}
