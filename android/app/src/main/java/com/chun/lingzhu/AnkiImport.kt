package com.chun.lingzhu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/**
 * v1.70 K2.5：导入 Anki 牌组包（.apkg）为知识卡。
 *
 * ## .apkg 是什么
 * 一个 zip 包，里面躺着：
 *  - `collection.anki2`（老版）/ `collection.anki21`（新版）/ `collection.anki21b`（新版 + zstd 压缩）
 *    —— 里面是 SQLite 库，卡面存在 `notes` 表的 `flds` 列，各字段用 `\u001f`（U+001F）分隔；
 *    通常字段 0 = 正面、字段 1 = 背面。
 *  - `media` —— 一个 JSON，做「序号 → 文件名」的映射。本次**不导媒体**，忽略即可。
 *
 * ## 为什么不直接读 Anki 的调度
 * Anki 的 cards 表带着自己的复习历史和 interval/ease。我们不搬它 —— 入库即按自家 [Fsrs]
 * 重新排（存入就是 Good 首刷，跟 K2 手动添加同一条路径）。牌组名也不搬，统一由调用方给一个
 * deck 名（K2.5 里取文件名去掉 .apkg）。
 *
 * ## 结构约定
 * 本文件上半部 [AnkiImport] 全是**纯函数**（不碰任何 Android 类，可直接 JVM 单测）；
 * 下半部 [AnkiImporter] 才是 Android 壳，只做「解包 → 临时库 → 查询 → 逐条交给纯函数」。
 *
 * ## 已知不支持
 * `collection.anki21b`（Anki 21b，zstd 压缩的库）解不开。zip 层面能看见这个条目，
 * 但内容不是 SQLite 头，强行 openDatabase 只会得到一个看不懂的失败。
 * 所以 [AnkiImport.zipEntryName] 明确不选它，由壳层识别出来并如实报告原因，不假装成功。
 */
object AnkiImport {

    /** 正面截断上限（跟 Memo.clip 默认一致）。 */
    const val FRONT_MAX = 200

    /** 背面截断上限（跟 [KnowledgeCard.BACK_MAX] 一致）。 */
    const val BACK_MAX = KnowledgeCard.BACK_MAX

    /** flds 字段分隔符 U+001F（Anki 私用区，界面上看不见）。 */
    const val SEP = '\u001F'

    /** Anki 导出包里的库文件名，按「新 → 老」优先级排列。 */
    val DBNAMES = listOf("collection.anki21", "collection.anki2")

    /** zstd 压缩的新版库：能认出，但打不开。 */
    const val DB_REJECT_ZSTD = "collection.anki21b"

    // ---------------- 纯函数 ----------------

    /**
     * flds 按 U+001F 切段。
     * 空串 / 空段会被丢掉（Anki 里空字段很常见，比如只有正面的卡）。
     */
    fun splitFields(flds: String): List<String> =
        flds.split(SEP).map { it }.filter { it.isNotEmpty() }

    /**
     * 剥 HTML 标签 + 解实体 + 压缩空白。
     * Anki 卡面基本都是 HTML（`<b>`,`<div>`,`<br>`），不剥的话正面会是满屏尖括号。
     * 块级标签断开行处补空格，否则 `<div>a</div><div>b</div>` 会粘成 `ab`。
     */
    fun stripHtml(s: String): String {
        if (s.isEmpty()) return ""
        var t = s
        // 块级/换行类标签：换成空格保留词间分隔
        t = t.replace(Regex("(?i)</?(br|p|div|li|tr|td|th|h[1-6])[^>]*>"), " ")
        // 其余标签连内容一起保留，只删标签本身
        t = t.replace(Regex("(?s)<[^>]*>"), "")
        // 实体：先解数字实体再解具名，避免 &amp;lt; 被解两次
        t = t.replace(Regex("&#(\\d+);")) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        t = t.replace("&nbsp;", " ")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&amp;", "&")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * notes 行（Long = notes.id，仅用于调试/排序）→ 知识卡。
     *
     * 规则：字段少于 2 段（没有背面）→ 跳过；正面剥完是空白 → 跳过；
     * 正面在同一 deck 内重复 → 跳过；入库为未学卡，不伪造已学习记录。
     * 返回的都是 id=0 的裸卡，真正落 id 由 [KnowledgeCard.Store.add] 分配。
     */
    fun toKCData(notes: List<Pair<Long, String>>, deck: String, now: Long): List<KnowledgeCard.KCData> {
        val out = ArrayList<KnowledgeCard.KCData>(notes.size)
        for ((_, flds) in notes) {
            val f = splitFields(flds)
            if (f.size < 2) continue
            val front = Memo.clip(stripHtml(f[0]), FRONT_MAX)
            val back = Memo.clip(stripHtml(f[1]), BACK_MAX)
            if (front.isBlank()) continue
            if (!KnowledgeCard.dedup(out, deck, front)) continue
            out += KnowledgeCard.KCData(
                id = 0L, deck = deck, front = front, back = back,
                source = KnowledgeCard.Source.IMPORT.code, createdAt = now,
                fsrs = Fsrs.Card(),
            )
        }
        return out
    }

    /**
     * 从 zip 条目名里挑库文件：`collection.anki21` 优先，退 `collection.anki2`，都没有返回 null。
     *
     * 条目名可能带目录前缀（有的导出工具会把库塞进子目录），所以按**文件名**匹配而不是全名。
     * `collection.anki21b` 明确**不**选 —— 它是 zstd 压缩的 SQLite，本机解不开；
     * 宁可返回 null 让壳层如实报告，也不返回一个打不开的文件。
     */
    fun zipEntryName(entries: Set<String>): String? {
        val bases = entries.map { it.substringAfterLast('/') }.toSet()
        return DBNAMES.firstOrNull { bases.contains(it) }
    }

    /** 认不认识这个包里「有但打不开」的库（anki21b）—— 用来给用户一个明确原因。 */
    fun hasZstdOnly(entries: Set<String>): Boolean =
        zipEntryName(entries) == null &&
            entries.any { it.substringAfterLast('/') == DB_REJECT_ZSTD }

    /**
     * 还能导入几张：`min(进来的, max - 现有的)`，下限 0。
     * 满了就是 0，壳层据此直接跳过写盘。
     */
    fun importsLeft(existing: Int, incoming: Int, max: Int): Int =
        if (incoming <= 0 || existing >= max) 0 else minOf(incoming, max - existing)
}

/**
 * Android 壳：真正把 .apkg 读进来。
 *
 * 流程：[Uri] → ZipInputStream 扫条目名 → [AnkiImport.zipEntryName] 选库 →
 * 写到 cacheDir → `SQLiteDatabase.openDatabase(READ_ONLY)` → `SELECT id, flds FROM notes` →
 * [AnkiImport.toKCData] 映射去重 → [KnowledgeCard.Store.add] 逐条写（满了就停）。
 *
 * 全程**必须在子线程**调（解包 + SQL 查询都是 IO）。临时文件用完就删，不留缓存垃圾。
 */
class AnkiImporter(private val ctx: Context) {

    /** 导入结果。[error] 非空表示没导进去任何一张，msg 是可以直接给用户看的原因。 */
    data class Result(val added: Int, val skipped: Int, val error: String? = null) {
        val ok: Boolean get() = error == null
    }

    private val store by lazy { KnowledgeCard.Store(ctx) }

    fun import(uri: Uri, now: Long = System.currentTimeMillis()): Result {
        val names = HashSet<String>()
        var payload = ByteArray(0)

        // 第一趟：扫一遍 zip，把库文件的内容留下来（media 直接 drain 掉，别让图片拖慢）
        val ins = try { ctx.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: return Result(0, 0, "打不开这个文件（系统可能已经把授权收回了）")
        try {
            ZipInputStream(ins).use { zin ->
                val buf = ByteArray(8192)
                var e = zin.nextEntry
                while (e != null) {
                    names += e.name
                    if (e.name.substringAfterLast('/') in AnkiImport.DBNAMES) {
                        val bos = java.io.ByteArrayOutputStream()
                        while (true) { val n = zin.read(buf); if (n <= 0) break; bos.write(buf, 0, n) }
                        payload = bos.toByteArray()
                    } else {
                        while (zin.read(buf) > 0) { /* drain：跳过 media */ }
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            return Result(0, 0, "这不是一个能解开的 .apkg 包：${e.message?.take(60) ?: e.javaClass.simpleName}")
        }

        val entry = AnkiImport.zipEntryName(names)
            ?: return Result(
                0, 0,
                if (AnkiImport.hasZstdOnly(names))
                    "这个牌组是 Anki 21b（zstd 压缩）格式，本机暂时解不开。用 Anki 导出成旧版 .apkg 再试。"
                else "包里没找到 Anki 数据库（collection.anki2 / anki21），可能不是 .apkg 文件。",
            )

        val tmp = File(ctx.cacheDir, "anki-import.db")
        return try {
            tmp.writeBytes(payload)
            readNotes(tmp, entry, now)
        } catch (e: Exception) {
            Result(0, 0, "Anki 库读不出来：${e.message?.take(60) ?: e.javaClass.simpleName}")
        } finally {
            tmp.delete()
            File(ctx.cacheDir, "anki-import.db-journal").delete()
        }
    }

    private fun readNotes(db: File, entry: String, now: Long): Result {
        val notes = ArrayList<Pair<Long, String>>()
        val d = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            d.rawQuery("SELECT id, flds FROM notes", null).use { c ->
                while (c.moveToNext()) notes += c.getLong(0) to c.getString(1).orEmpty()
            }
        } finally {
            d.close()
        }
        if (notes.isEmpty()) return Result(0, 0, "这个牌组里一条卡都没有。")

        val deck = deckFromEntry(entry)
        val cards = AnkiImport.toKCData(notes, deck, now)
        if (cards.isEmpty()) return Result(0, 0, "牌组里没有能用的卡（每条至少要有正面和背面两栏）。")

        val room = AnkiImport.importsLeft(store.count(), cards.size, KnowledgeCard.MAX)
        if (room <= 0)
            return Result(0, notes.size, "知识卡已经满 ${KnowledgeCard.MAX} 张了，先删一些再导。")

        val batch = cards.take(room)   // K4 体验整改：一次读一次写（原来逐张全量序列化，大牌组导入要等几分钟）
        val added = store.addAll(batch.map { it.copy(source = KnowledgeCard.Source.IMPORT.code) })
        return Result(added, notes.size - added)
    }

    /** 牌组名从库里来不了（col/decks 是 base64 JSON），这里用库文件名去掉扩展名当牌组名。 */
    private fun deckFromEntry(entry: String): String =
        entry.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Anki" }

    companion object {
        /** 选文件用的 Intent：只认 zip 和二进制流（各家文件管理器给的 MIME 不一，放开 octet-stream）。 */
        fun pickerIntent(): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    android.content.Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/octet-stream", "application/x-anki", "*/*"),
                )
            }
    }
}