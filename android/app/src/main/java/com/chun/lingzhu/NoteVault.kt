package com.chun.lingzhu

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 记事本的 Markdown 库：文件是事实源，SQLite 只是可重建的查询索引。
 *
 * ## 分工
 * - [NoteMarkdown] 负责「一条笔记 ⇄ 一段 md 文本」的纯字符串换算；
 * - 这里负责**落盘**：目录布局、原子写、备份、迁移、重建、导入导出；
 * - [Notes] 在有 ctx 的情况下通过 [publishOrThrow] 把每次写入发布成 md。
 *
 * ## 三条硬规矩
 * 1. **先文件、后索引**：md 的 fsync + 同盘 rename 成功之后才提交 SQL。
 *    发布之后索引失败 -> 抛 [IndexException]，md 留着（它是事实源），绝不回删。
 * 2. **绝不因为索引出问题就清空文件**：打开时发现「库是空的、库里有文件」，
 *    走的是 [rebuild]（从文件重建索引），不是「把空库导出成空 vault」。
 * 3. **失败必须说出来**：迁移/重建/导入的失败写进 [lastError]，界面上能看到；
 *    失败时不写完成标记，下次打开重试，旧库继续可用。
 *
 * ## 目录
 * ```
 * filesDir/notes/vault/<安全标题>--<uid>.md   ← 笔记（平铺一层）
 * filesDir/notes/vault/trash/<uid>--<ts>.md   ← 彻底删除后的墓碑（不进索引、可人工翻回）
 * filesDir/notes/att/<yyyymm>/<uid>.jpg       ← 附件本体（沿用 NoteAttach，不搬家）
 * filesDir/notes/backup/<stamp>/              ← 迁移前的库快照 + 附件快照
 * ```
 * 附件之所以不搬进 vault：老库的附件路径全部相对 `NoteAttach.dir`，
 * 搬家等于把用户所有图片变成「找不到」，而搬迁本身没有任何收益。
 */
object NoteVault {

    /** 迁移完成标记（写在 meta 表）。只有迁移真正成功才写，失败一律不写 */
    private const val META_KEY = "vault_schema"

    /** 当前迁移方案版本。改目录布局或迁移逻辑时 +1 */
    private const val VAULT_SCHEMA = 1

    /** 单条 md 的上限。超过就不读（防止导入一个几 GB 的「md」把内存打爆） */
    private const val MAX_MD_BYTES = 4L * 1024 * 1024

    /** 单个附件导入/导出的上限 */
    private const val MAX_ATT_BYTES = 64L * 1024 * 1024

    /** SAF 递归深度与条目数上限：Tree Uri 可能是整个存储根 */
    private const val MAX_DEPTH = 6
    private const val MAX_ENTRIES = 20_000

    /**
     * 「md 已经落盘，但索引没跟上」。
     *
     * 只有这一个含义，所以调用方可以据此告诉用户「内容已保存，稍后重建索引」，
     * 而不是「保存失败」——后者会让人把已经存好的内容再抄一遍。
     *
     * 继承 [IOException]：老的 `catch (IOException)` 也能兜住，
     * 但需要区分时**必须先 catch 这个子类**。
     */
    class IndexException(
        val uid: String,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    // ---------------- 进程级状态 ----------------

    /** 注册进来的 applicationContext：Notes 的写入口只有 SQLiteDatabase，靠它落盘 */
    @Volatile
    private var appCtx: Context? = null

    /** 同一时刻只跑一次迁移；失败后下次打开会重试（标记没写） */
    private val migrating = AtomicBoolean(false)

    /** 最近一次后台操作的失败原因，给界面显示用 */
    @Volatile
    private var lastErrorText: String? = null

    /** 单线程：迁移/重建/导入/导出彼此不会踩在一起 */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "note-vault").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** 只保护「找旧文件 → 改名 → 覆盖」这一小段，不涉及 SQL */
    private val fileLock = Any()

    /**
     * 生产写入的总闸。
     *
     * [Notes] 每次落盘发布（[publishOrThrow]）和这里的迁移/重建/导入/墓碑都在它里面，
     * 保证「读旧文件 → 写新文件 → 改库」三件事不会被另一次写入插进中间。
     *
     * 锁顺序固定 mutationLock → [fileLock]，反向绝不出现：[fileLock] 只包住单个文件的
     * 改名/覆盖，永远不回头拿 mutationLock。synchronized 可重入，所以 publish 里再调
     * tombstone、导入里的 publish 都不会自己锁死自己。
     */
    internal val mutationLock = Any()

    /** vault 根目录 */
    fun path(ctx: Context): File = File(ctx.applicationContext?.filesDir ?: ctx.filesDir, "notes/vault")

    /** 最近一次后台失败的原因（成功后清空）。失败不是异常，是可见状态 */
    fun lastError(ctx: Context): String? = lastErrorText

    /** 已注册的 applicationContext（[ensure] 时写入） */
    internal fun registered(): Context? = appCtx

    /**
     * [NoteStore.onOpen] 的兜底：注册本身失败也不能让「打开数据库」失败，
     * 但失败必须是可见状态，所以记进 [lastErrorText] 而不是吞掉。
     */
    internal fun noteOpenFailure(t: Throwable) {
        lastErrorText = "笔记库初始化失败：" + (t.message ?: t.javaClass.simpleName)
    }

    /**
     * 这把 db 是不是真正的笔记库？是就返回注册进来的 context，否则 null。
     *
     * 给 `Notes.purge` 这类「只拿得到 db、却需要 ctx 去删文件」的入口用：
     * 自检跑在 `selftest.db` 上，拿到的永远是 null，于是只会清库，
     * 碰不到用户真实的附件文件。
     */
    internal fun registeredFor(db: SQLiteDatabase): Context? {
        val ctx = appCtx ?: return null
        val dbPath = db.path ?: return null
        return if (isProductionDb(ctx, dbPath)) ctx else null
    }

    /**
     * 真实 notes.db 才发布/迁移：按 canonical path 比，符号链接、`./` 这类写法都放过。
     * 只看「appCtx 非空」是不够的 —— 自检库和用户库在同一个进程里，两个都是真连接。
     */
    private fun isProductionDb(ctx: Context, dbPath: String): Boolean {
        val expected = runCatching { ctx.getDatabasePath(NoteStore.DB).canonicalPath }.getOrNull()
            ?: return false
        val actual = runCatching { File(dbPath).canonicalPath }.getOrNull() ?: return false
        return actual == expected
    }

    // ---------------- 注册 / 迁移 ----------------

    /**
     * 由 [NoteStore.onOpen] 调用：注册 context，并在需要时做一次迁移。
     *
     * **必须幂等**：5 个界面各自 `NoteStore(...)`，每次打开都会进来。
     * 已经迁移过（meta 有标记）时这里只做一次字符串比较，立即返回。
     *
     * 迁移本身在后台线程跑：几千条笔记逐个 fsync 是秒级操作，
     * 放在 `onCreate` 里就是 ANR。调用方不需要等它，也不需要知道它什么时候完成 ——
     * 失败会出现在 [lastError] 里，而且**每个写入口自己也会发 md**，
     * 所以在迁移完成之前新写入的笔记同样是安全的。
     */
    fun ensure(ctx: Context, db: SQLiteDatabase) {
        val app = ctx.applicationContext ?: ctx
        appCtx = app
        // 只有真正的 notes.db 才迁移：自检/临时库没有 vault，也不该被当成用户数据
        val dbPath = db.path ?: return
        if (!isProductionDb(app, dbPath)) return
        if (Notes.metaGet(db, META_KEY)?.toIntOrNull() == VAULT_SCHEMA) return
        if (!migrating.compareAndSet(false, true)) return
        worker.execute {
            var own: SQLiteDatabase? = null
            try {
                // 工作线程自己开一条连接：传进来的 db 属于调用方那个 helper，
                // 界面一 onDestroy 就可能把它关掉，拿着它跑几秒的迁移会中途炸掉。
                // 也正因为如此，这里绝不能再走 NoteStore(...).writableDatabase（会递归回 ensure）。
                own = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE)
                if (Notes.metaGet(own, META_KEY)?.toIntOrNull() != VAULT_SCHEMA) {
                    migrate(app, own)
                }
                lastErrorText = null
            } catch (t: Throwable) {
                lastErrorText = "笔记库迁移失败：" + (t.message ?: t.javaClass.simpleName)
            } finally {
                migrating.set(false)
                runCatching { own?.close() }
            }
        }
    }

    /**
     * 首次迁移。
     *
     * 顺序刻意写成「先判断库和文件的相对状态」，因为这里最容易犯的错是
     * 把「索引丢了」当成「用户没有笔记」，然后导出一个空 vault 覆盖掉真数据：
     *  - 库有笔记、vault 没文件 → 老版本升级：备份 → 逐条写 md → 校验 → 打标记；
     *  - 库空、vault 有文件     → 索引被删/换机恢复：**重建索引**，一个字节都不动；
     *  - 两边都空               → 全新安装：建目录、打标记；
     *  - 都有                   → 上次迁移中断：只补缺失的文件（幂等）。
     */
    private fun migrate(ctx: Context, db: SQLiteDatabase) {
        val vault = path(ctx)
        // 整段在锁里。两件事必须同时成立：
        //  1. 备份是「库文件快照」，正写到一半的库拷出来没法用；
        //  2. 逐条 publish 若在锁外，用户此刻的保存会和迁移交错，同一条笔记出现两份 md。
        // 锁可重入，publishLocked 内部再拿不会自锁；顺序始终 mutationLock → fileLock。
        synchronized(mutationLock) {
            val noteCount = countNotes(db)
            val mdCount = mdFiles(vault).size
            when {
                noteCount == 0L && mdCount > 0 -> {
                    // 索引没了但文件还在：文件才是事实源
                    rebuildLocked(ctx, db)
                    markDone(db)
                    return
                }
                noteCount == 0L -> {
                    ensureDir(vault)
                    markDone(db)
                    return
                }
                mdCount == 0 -> {
                    // 只有这一次升级才备份：既有 md 说明迁移已经跑过，不必反复拷库
                    backup(ctx, db)
                }
            }
            ensureDir(vault)
            scrubTemp(vault)
            var missing = 0
            Notes.ids(db).forEach { id ->
                val uid = Notes.uidOf(db, id) ?: return@forEach
                if (existingMd(vault, uid) != null) return@forEach
                try {
                    publishLocked(ctx, db, id)
                } catch (e: IOException) {
                    missing++
                }
            }
            // 校验：每条笔记都该有文件。缺一条就不打标记，下次打开重试，绝不谎报成功
            if (missing > 0) throw IOException("还有 $missing 条笔记没能写出 md")
            markDone(db)
        }
    }

    private fun markDone(db: SQLiteDatabase) {
        Notes.metaPut(db, META_KEY, VAULT_SCHEMA.toString())
    }

    /**
     * 迁移前备份：库快照 + 附件快照。
     *
     * 库：先 checkpoint（WAL 里没落盘的内容并回主库），再连同 `-wal`/`-shm` 一起复制。
     * 附件：同盘**硬链接**优先 —— 内容一模一样，但不占第二份空间。
     * 建不了硬链接（不同卷、内核限制）才退回真的拷贝。
     *
     * 备份失败不让迁移继续：没有退路的切库不能做。
     */
    private fun backup(ctx: Context, db: SQLiteDatabase) {
        val stamp = stampOf(System.currentTimeMillis())
        val dir = File(File(ctx.filesDir, "notes/backup"), stamp)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("建备份目录失败：" + dir)
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
        val dbFile = db.path?.let { File(it) }
        if (dbFile != null && dbFile.isFile) {
            if (!copyFile(dbFile, File(dir, dbFile.name))) throw IOException("备份 notes.db 失败")
            File(dbFile.path + "-wal").takeIf { it.isFile }?.let { copyFile(it, File(dir, it.name)) }
            File(dbFile.path + "-shm").takeIf { it.isFile }?.let { copyFile(it, File(dir, it.name)) }
        }
        val att = NoteAttach.dir(ctx)
        if (att.isDirectory) linkTree(att, File(dir, "att"))
    }

    // ---------------- 发布（Notes 的写入口调用） ----------------

    /**
     * 供 [Notes] 调用：**只有**真正的 notes.db 才发布成 md。
     *
     * 光看「appCtx 非空」不够：自检跑在自己的 `selftest.db` 上，那时 appCtx 早就被
     * 别的界面注册过了。按 db 的真实路径比对 [NoteStore.DB]，对不上就当作「这里没有 vault」，
     * 返回 false，自检写的数据一个字节都不会落进用户的 vault。
     */
    internal fun publishOrThrow(db: SQLiteDatabase, id: Long): Boolean {
        val ctx = registeredFor(db) ?: return false
        publish(ctx, db, id)
        return true
    }

    /**
     * 把一条笔记发布成 md。**不捕获异常**：调用方要拿它决定是回滚还是「md 已存、索引失败」。
     *
     * 标题变了要跟着改文件名（Obsidian 里看到的文件名应该是标题）：
     * 先 rename 旧文件到新名字，再原子覆盖内容 —— 这样任何瞬间 vault 里都只有一份该 uid 的文件。
     */
    internal fun publish(ctx: Context, db: SQLiteDatabase, id: Long) {
        synchronized(mutationLock) { publishLocked(ctx, db, id) }
    }

    private fun publishLocked(ctx: Context, db: SQLiteDatabase, id: Long) {
        val full = Notes.one(db, id) ?: throw IOException("笔记 " + id + " 不存在，无法写 md")
        val uid = full.item.uid
        val vault = path(ctx)
        ensureDir(vault)
        val atts = Notes.attachmentsOf(db, id).map {
            NoteMarkdown.AttRec(
                uid = it.uid, mime = it.mime, path = it.path, thumb = it.thumb,
                w = it.w, h = it.h, bytes = it.bytes, sha256 = it.sha256, createdAt = it.createdAt,
            )
        }
        val target = File(vault, NoteMarkdown.fileName(full.item.title, uid))
        synchronized(fileLock) {
            val old = existingMd(vault, uid)
            // 旧文件里的未知 frontmatter 字段要带过去：用户加的东西不能保存一次就消失
            val previous = old?.takeIf { it.length() <= MAX_MD_BYTES }
                ?.let { runCatching { it.readText() }.getOrNull() }
            val text = NoteMarkdown.encode(full.item, id, atts, previous)
            if (old != null && old.absolutePath != target.absolutePath) {
                if (!old.renameTo(target) && old.exists()) {
                    // rename 失败（个别 ROM 对已存在目标的行为不一致）：只能先写新再删旧
                    writeAtomic(target, text)
                    old.delete()
                    return@synchronized
                }
            }
            writeAtomic(target, text)
        }
    }

    /**
     * 彻底删除的落盘部分：把 md 挪进 `trash/`。
     *
     * 为什么不是直接删：md 是事实源，删掉就真的没了。
     * 挪进 trash 后它既不在索引里（[rebuild] 不扫 trash），也不在库外丢失，用户还能人工找回。
     * **这一步必须发生在删 DB 行之前**，否则删库成功、挪文件失败时，
     * 下一次重建会把这条笔记从 md 复活出来 —— 用户看到「彻底删掉的笔记又回来了」。
     *
     * @return 挪走了几个文件（0 = 本来就没有 md）
     */
    internal fun tombstone(ctx: Context, uid: String): Int =
        synchronized(mutationLock) { tombstoneLocked(ctx, uid) }

    private fun tombstoneLocked(ctx: Context, uid: String): Int {
        val vault = path(ctx)
        synchronized(fileLock) {
            val old = existingMd(vault, uid) ?: return 0
            val trash = File(vault, "trash")
            ensureDir(trash)
            val target = File(trash, NoteMarkdown.safeUid(uid) + "--" + System.currentTimeMillis() + ".md")
            if (old.renameTo(target)) {
                fsyncDir(trash)
                return 1
            }
            throw IOException("无法把 " + old.name + " 移到回收站目录")
        }
    }

    // ---------------- 重建 ----------------

    /**
     * 从 vault 全量重建索引（笔记、标签、检索、附件行）。**不删任何 vault 文件。**
     *
     * 以 uid 为身份：同 uid 的索引行就地更新（保留原 id，这样 Intent 里的 id 不变），
     * md 里 `ling.id` 给出的本地 id 优先复用；只有在目标 id 空闲时才用显式 id 插入。
     *
     * 库里存在、vault 里没有 md 的行**不删**：可能只是 md 文件被人删了，
     * 删行等于把用户内容彻底抹掉；显示出来比丢掉安全。
     *
     * 唯一会删的是 `trash/` 里有墓碑的 uid —— 那是明确的删除事实，
     * 不删就会在「彻底删除」失败之后复活。
     */
    fun rebuild(ctx: Context, db: SQLiteDatabase) {
        synchronized(mutationLock) { rebuildLocked(ctx, db) }
    }

    private fun rebuildLocked(ctx: Context, db: SQLiteDatabase) {
        val vault = path(ctx)
        ensureDir(vault)
        val files = mdFiles(vault)
        db.beginTransaction()
        try {
            files.forEach { f ->
                val text = runCatching { readText(f) }.getOrNull() ?: return@forEach
                // 文件名兜底 uid：手写的 md 常没有 frontmatter，文件名里的 uid 就是身份
                val fallback = uidOfName(f.name)
                val parsed = runCatching { NoteMarkdown.decode(text, fallback) }.getOrNull()
                    ?: return@forEach
                val id = Notes.putRow(db, parsed.item, parsed.localId)
                Notes.replaceAttRows(db, id, parsed.attachments.map {
                    Notes.Att(
                        id = 0L, uid = it.uid.ifBlank { parsed.uid + ":a" }, noteId = id,
                        mime = it.mime, path = it.path, thumb = it.thumb, w = it.w, h = it.h,
                        bytes = it.bytes, sha256 = it.sha256, createdAt = it.createdAt,
                    )
                })
            }
            // 标签与检索整体重算：逐个 md 同步的话，删掉的标签 use_count 会永远偏大
            Notes.reindexAll(db, resetTags = true)
            trashUids(vault).forEach { Notes.dropByUid(db, it) }
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            lastErrorText = "从 Markdown 重建索引失败：" + (t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            db.endTransaction()
        }
    }

    // ---------------- 文档列表（反链/链接扫描用） ----------------

    /**
     * 索引里所有未删除的笔记，供 [NoteLinks] 这类纯函数做链接与反链。
     *
     * `path` 取**磁盘上真实文件名**去掉 `.md`（标题改过之后文件名可能还带着旧标题），
     * 而不是按当前标题现算 —— 反链要能对上文件。
     */
    fun docs(ctx: Context, db: SQLiteDatabase): List<NoteMarkdown.DocMeta> {
        val vault = path(ctx)
        val byUid = HashMap<String, String>()
        mdFiles(vault).forEach { f ->
            byUid[uidOfName(f.name)] = f.relativeTo(vault).path.removeSuffix(".md")
        }
        val out = ArrayList<NoteMarkdown.DocMeta>()
        db.rawQuery(
            "SELECT uid, title, body FROM " + NoteStore.T_NOTES + " WHERE deleted_at=0",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val uid = c.getString(0) ?: continue
                val title = c.getString(1) ?: ""
                out.add(
                    NoteMarkdown.DocMeta(
                        uid = uid,
                        title = title,
                        body = c.getString(2) ?: "",
                        path = byUid[uid] ?: NoteMarkdown.fileName(title, uid).removeSuffix(".md"),
                    )
                )
            }
        }
        return out
    }

    // ---------------- 附件路径 ----------------

    /**
     * 把 md/导出目录里的附件引用解析成磁盘文件。
     *
     * 接受 `202610/x.jpg`、`attachments/202610/x.jpg`、`./attachments/...` 三种写法
     * （md 由人手写、由别的工具生成，写法一定不统一）。
     * 拒绝空的、绝对路径、`~`、盘符、任何含 `..` 的段；命中必须是**规范根目录内的普通文件**。
     * 这些都挡住之后仍然对不上，就返回 null，让界面显示「附件缺失」而不是抛异常。
     */
    fun resolveAttachment(ctx: Context, ref: String): File? {
        val rel = sanitizeRel(ref) ?: return null
        return try {
            val root = NoteAttach.dir(ctx).canonicalFile
            val f = File(root, rel).canonicalFile
            val prefix = root.path + File.separator
            if (!f.path.startsWith(prefix)) return null
            if (!f.isFile) return null
            f
        } catch (e: IOException) {
            null
        }
    }

    /** 相对附件根路径 → md/导出目录里的规范写法 `attachments/<rel>` */
    fun attachmentRef(rel: String): String {
        val clean = sanitizeRel(rel) ?: rel.trim().replace('\\', '/')
        return NoteMarkdown.ATTACH_DIR + "/" + clean
    }

    /** 去掉各种前缀并做安全校验；不安全返回 null */
    private fun sanitizeRel(raw: String): String? {
        var s = raw.trim().replace('\\', '/')
        if (s.isEmpty()) return null
        if (s.startsWith("/") || s.startsWith("~")) return null
        if (s.length > 1 && s[1] == ':') return null
        while (s.startsWith("./")) s = s.substring(2)
        val prefix = NoteMarkdown.ATTACH_DIR + "/"
        while (s.startsWith(prefix)) s = s.substring(prefix.length)
        s = s.trimStart('/')
        if (s.isEmpty()) return null
        val parts = s.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/")
    }

    // ---------------- 导出 ----------------

    /**
     * 导出到用户选的 SAF 目录：md 平铺 + `attachments/` 子目录放附件。
     *
     * **不清空目标目录**：那是用户的目录，里面可能有别的东西。
     * 同名且内容相同 → 跳过；同名但内容不同 → 写一份 `名字--2.md` 之类的冲突副本，
     * 绝不静默覆盖（覆盖一次，用户就永远不知道自己丢的是哪一版）。
     *
     * @return 实际写出去的文件数（跳过的不算）
     */
    fun exportDirectory(ctx: Context, tree: Uri): Int {
        val vault = path(ctx)
        // 先在锁里把「要导出的内容」拍成一致快照，放开锁再写 SAF：
        // 一边持锁一边写用户目录，会把编辑器的保存卡住好几分钟（导出可能上千个文件）。
        //
        // 自己开、自己关这个 helper：导出跑在后台线程，界面那个 helper 随时会被 close，
        // 借它的连接会在中途炸掉（而且 db 被关之后 path 也不能再用于读）。
        val store = NoteStore(ctx)
        val snap = try {
            val db = store.writableDatabase
            synchronized(mutationLock) {
                val mds = mdFiles(vault).mapNotNull { f ->
                    runCatching { readText(f) }.getOrNull()?.let { f.name to it }
                }
                // 附件：只导出真正被引用到的（附件行全部取一遍），不把整个目录倒出去
                val set = LinkedHashSet<String>()
                Notes.allAttachments(db).forEach { a ->
                    if (a.path.isNotEmpty()) set.add(a.path)
                    if (a.thumb.isNotEmpty()) set.add(a.thumb)
                }
                mds to ArrayList(set)
            }
        } finally {
            runCatching { store.close() }
        }
        val mdSnap = snap.first
        val refs = snap.second
        val dest = Dest(ctx.contentResolver, tree)
        var written = 0
        mdSnap.forEach { (name, text) -> if (dest.putText(name, text)) written++ }
        val root = NoteAttach.dir(ctx)
        refs.forEach { rel ->
            val safe = sanitizeRel(rel) ?: return@forEach
            val src = File(root, safe)
            if (!src.isFile) return@forEach
            if (dest.putFile(NoteMarkdown.ATTACH_DIR + "/" + safe, src)) written++
        }
        return written
    }

    // ---------------- 导入 ----------------

    /** 导入计数。[skipped] 含「已存在且一致」「超长被拒」「读不出来」三种 */
    data class ImportResult(val imported: Int, val conflicts: Int, val skipped: Int)

    /**
     * 从 SAF 目录导入。
     *
     * 规则（顺序也是安全顺序）：
     * 1. 校验在**落盘之前**：读不出来、超长一律 skipped，改名截断都不做 ——
     *    外来的 md 是用户的原文，静默截断比拒绝更糟。
     * 2. 同 uid 且标题正文都相同 → skipped（重复导入不产生第二份）。
     * 3. 同 uid 但内容不同 → 写成冲突副本：**新 uid**、文件名带 conflict 标记，
     *    原 uid 记在 `ling.conflict_of` 里。两条都留下，让用户自己决定留哪条；
     *    「新的覆盖旧的」在导入场景里等于静默丢数据。
     * 4. 附件按 `attachments/<path>` 找，复制到 `NoteAttach.dir` 同一相对路径，已存在且大小一致就复用。
     *
     * 每个文件单独一个事务：一条坏掉不影响已经导进来的。
     */
    fun importDirectory(ctx: Context, db: SQLiteDatabase, tree: Uri): ImportResult {
        val entries = ArrayList<Pair<String, Dest.Entry>>()
        Dest(ctx.contentResolver, tree).walk("", 0, entries)
        // 先收附件：md 里引用的文件必须在写索引之前就位，否则详情页一打开就是空图
        val attachments = entries.filter { it.first.startsWith(NoteMarkdown.ATTACH_DIR + "/") }
        val mds = entries.filter { it.first.endsWith(".md", true) }
        var imported = 0
        var conflicts = 0
        var skipped = 0
        // 每条笔记的「查重 → 写库 → 落 md」在锁里成对完成（见下面的 synchronized）
        mds.forEach { (rel, entry) ->
            // 读 SAF 放在锁外：用户目录可能很慢，不能让它把编辑器的保存一起拖住
            val text = runCatching { Dest.readText(ctx.contentResolver, tree, entry) }.getOrNull()
            if (text == null) { skipped++; return@forEach }
            val parsed = runCatching { NoteMarkdown.decode(text, uidOfName(rel.substringAfterLast('/'))) }
                .getOrNull()
            if (parsed == null) { skipped++; return@forEach }
            // 超长：整条拒绝，原文仍在用户选的那个目录里，不会被改坏
            if (parsed.body.length > Note.MAX_BODY) { skipped++; return@forEach }
            // 「查重 → 写库 → 落 md」是复合动作，必须整段在锁里，否则两条线程可能同时
            // 判定「这个 uid 还不存在」，各插一份。
            val outcome = synchronized(mutationLock) {
                val existingId = Notes.idOfUid(db, parsed.uid)
                if (existingId > 0L) {
                    val same = Notes.one(db, existingId)?.item?.let {
                        it.title == parsed.item.title && it.body == parsed.item.body
                    } ?: false
                    if (same) return@synchronized 2 // skipped
                    val copyItem = parsed.item.copy(uid = Note.newUid())
                    val copyId = saveImported(
                        ctx, db, copyItem, parsed, attachments, rel, tree, conflictOf = parsed.uid
                    )
                    if (copyId > 0L) 1 else 2 // conflict / skipped
                } else {
                    val id = saveImported(ctx, db, parsed.item, parsed, attachments, rel, tree, conflictOf = null)
                    if (id > 0L) 0 else 2 // imported / skipped
                }
            }
            when (outcome) {
                0 -> imported++
                1 -> conflicts++
                else -> skipped++
            }
        }
        return ImportResult(imported, conflicts, skipped)
    }

    /**
     * 导入单条：先把附件拷进 `NoteAttach.dir`，再写索引行，最后把 md 发布进 vault。
     * 返回 0 表示没导成（异常被吃掉一次，计数落在 skipped 上）。
     */
    private fun saveImported(
        ctx: Context,
        db: SQLiteDatabase,
        item: Note.Item,
        parsed: NoteMarkdown.Parsed,
        attachments: List<Pair<String, Dest.Entry>>,
        mdRel: String,
        tree: Uri,
        conflictOf: String?,
    ): Long {
        val attRoot = NoteAttach.dir(ctx)
        val atts = ArrayList<Notes.Att>()
        parsed.attachments.forEach { a ->
            val rel = sanitizeRel(a.path) ?: return@forEach
            val found = attachments.firstOrNull {
                it.first == NoteMarkdown.ATTACH_DIR + "/" + rel ||
                    it.first.endsWith("/" + rel)
            }
            if (found != null) {
                runCatching {
                    ensureDir(File(attRoot, rel).parentFile)
                    Dest.copyToFile(ctx.contentResolver, tree, found.second, File(attRoot, rel))
                }
            }
            atts.add(
                Notes.Att(
                    id = 0L, uid = a.uid.ifBlank { item.uid + ":a" }, noteId = 0L,
                    mime = a.mime, path = rel, thumb = a.thumb, w = a.w, h = a.h,
                    bytes = a.bytes, sha256 = a.sha256, createdAt = a.createdAt,
                )
            )
        }
        return try {
            synchronized(mutationLock) {
                var durable = false
                var newId = 0L
                var failure: Throwable? = null
                db.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val prepared = item.copy(
                        createdAt = item.createdAt.takeIf { it > 0 } ?: now,
                        updatedAt = item.updatedAt.takeIf { it > 0 } ?: now,
                    )
                    newId = Notes.putRow(db, prepared, 0L)
                    Notes.replaceAttRows(db, newId, atts)
                    Notes.reindexOne(db, newId)
                    // md **在提交之前**落盘：文件才是事实源。这里失败 -> 事务回滚，
                    // 旧库一个字节都不变，这次导入等于没发生（原文还在用户选的目录里）。
                    if (conflictOf == null) {
                        publishLocked(ctx, db, newId)
                    } else {
                        publishWithExtra(ctx, db, newId, mapOf<String, Any?>("conflict_of" to conflictOf))
                    }
                    durable = true
                    db.setTransactionSuccessful()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    try {
                        db.endTransaction()
                    } catch (t: Throwable) {
                        if (failure == null) failure = t
                    }
                }
                val f = failure
                if (f != null) {
                    // md 已经落盘却没能进索引：告诉调用方「内容在，索引缺」，别当成保存失败
                    if (durable) {
                        throw IndexException(
                            item.uid,
                            "md 已写入，索引失败：" + (f.message ?: f.javaClass.simpleName),
                            f,
                        )
                    }
                    throw f
                }
                newId
            }
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            lastErrorText = "导入 " + mdRel + " 失败：" + (t.message ?: t.javaClass.simpleName)
            0L
        }
    }

    /** 冲突副本：正文与附件同上，只是 `ling` 段多写一个 `conflict_of` */
    private fun publishWithExtra(ctx: Context, db: SQLiteDatabase, id: Long, extra: Map<String, Any?>) {
        val full = Notes.one(db, id) ?: throw IOException("导入后的笔记不见了：$id")
        val vault = path(ctx)
        ensureDir(vault)
        val atts = Notes.attachmentsOf(db, id).map {
            NoteMarkdown.AttRec(
                uid = it.uid, mime = it.mime, path = it.path, thumb = it.thumb,
                w = it.w, h = it.h, bytes = it.bytes, sha256 = it.sha256, createdAt = it.createdAt,
            )
        }
        val text = NoteMarkdown.encode(full.item, id, atts, previous = null, extraLing = extra)
        synchronized(fileLock) {
            writeAtomic(File(vault, NoteMarkdown.fileName(full.item.title, full.item.uid)), text)
        }
    }
    private fun countNotes(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM " + NoteStore.T_NOTES, null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** vault 根下（不含 trash/）的所有 md */
    private fun mdFiles(vault: File): List<File> {
        val list = vault.listFiles() ?: return emptyList()
        return list.filter { it.isFile && it.name.endsWith(".md", true) }.sortedBy { it.name }
    }

    /** 某个 uid 对应的现有 md（标题改过之后文件名可能不同，所以按 `--uid.md` 后缀找） */
    private fun existingMd(vault: File, uid: String): File? {
        val suffix = "--" + NoteMarkdown.safeUid(uid) + ".md"
        return vault.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(suffix) }
    }

    /** 从文件名取出 uid：`标题--uid.md` */
    private fun uidOfName(name: String): String {
        val base = name.removeSuffix(".md").removeSuffix(".MD")
        val cut = base.lastIndexOf("--")
        return if (cut >= 0) base.substring(cut + 2) else base
    }

    /** trash/ 里的墓碑 uid：这些笔记已经明确删除，重建索引时也不该回来 */
    private fun trashUids(vault: File): List<String> {
        val trash = File(vault, "trash")
        val list = trash.listFiles() ?: return emptyList()
        return list.filter { it.isFile && it.name.endsWith(".md") }
            .map { uidOfName(it.name).substringBeforeLast("--") }
            .filter { it.isNotEmpty() }
    }

    private fun ensureDir(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("建目录失败：" + dir)
    }

    /** 清掉上次崩溃留下的半截临时文件；它们不参与任何读取，只是占地方 */
    private fun scrubTemp(vault: File) {
        vault.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(".") && f.name.endsWith(".tmp")) f.delete()
        }
    }

    private fun readText(f: File): String {
        if (f.length() > MAX_MD_BYTES) throw IOException("md 太大：" + f.name)
        return f.readText()
    }

    /**
     * 原子写：临时文件 → fsync → 同目录 rename → fsync 目录。
     *
     * 少了 fsync，掉电后可能出现「文件在、内容是空的」—— 对事实源来说这是最坏的结局。
     * 临时文件名带随机后缀：并发写同一篇（保存与被重建同时发生）不会互相截断。
     */
    private fun writeAtomic(target: File, text: String) {
        ensureDir(target.parentFile)
        val tmp = File(target.parentFile, "." + target.name + "." + System.nanoTime() + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 有的文件系统不允许 rename 覆盖已存在文件
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("写入 " + target.name + " 失败")
            }
            fsyncDir(target.parentFile)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        try {
            val fd = android.system.Os.open(
                dir.absolutePath, android.system.OsConstants.O_RDONLY, 0
            )
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        } catch (ignored: Throwable) {
            // 目录 fsync 不是所有内核都允许；放不掉数据的问题也不该因此让保存失败
        }
    }

    private fun copyFile(src: File, dst: File): Boolean = try {
        ensureDir(dst.parentFile)
        src.inputStream().use { input -> dst.outputStream().use { output ->
            input.copyTo(output, 64 * 1024)
            (output as FileOutputStream).fd.sync()
        } }
        true
    } catch (e: IOException) {
        false
    }

    /** 目录快照：先试硬链接（零成本），失败再真拷 */
    private fun linkTree(src: File, dst: File) {
        if (!src.isDirectory) return
        ensureDir(dst)
        src.listFiles()?.forEach { f ->
            val t = File(dst, f.name)
            if (f.isDirectory) {
                linkTree(f, t)
            } else if (f.isFile) {
                val linked = runCatching { java.nio.file.Files.createLink(t.toPath(), f.toPath()) }.isSuccess
                if (!linked) copyFile(f, t)
            }
        }
    }

    private fun stampOf(at: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        fun two(v: Int) = if (v < 10) "0" + v else "" + v
        return "" + cal.get(java.util.Calendar.YEAR) + two(cal.get(java.util.Calendar.MONTH) + 1) +
            two(cal.get(java.util.Calendar.DAY_OF_MONTH)) + "-" +
            two(cal.get(java.util.Calendar.HOUR_OF_DAY)) + two(cal.get(java.util.Calendar.MINUTE)) +
            two(cal.get(java.util.Calendar.SECOND))
    }

    // ---------------- SAF ----------------

    /**
     * 一个 SAF 目标目录的薄封装。
     *
     * 没有 androidx.documentfile（工程不加依赖），所以直接用 framework 的
     * [DocumentsContract] + ContentResolver 手工递归。写入前先把目标目录列一遍并缓存，
     * 这样「同名不同内容 → 写冲突副本」和「同名同内容 → 跳过」都不需要额外往返。
     */
    private class Dest(private val resolver: ContentResolver, private val tree: Uri) {

        class Entry(val name: String, val docId: String, val dir: Boolean, val size: Long)

        /** 相对路径（`""` = 根） → 文档 Uri */
        private val dirUri = HashMap<String, Uri>()

        /** 相对路径 → 该目录下已有的条目 */
        private val listings = HashMap<String, Map<String, Entry>>()

        private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree)

        init {
            dirUri[""] = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId)
        }

        private fun children(rel: String): Map<String, Entry> {
            listings[rel]?.let { return it }
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docIdOf(rel))
            val map = LinkedHashMap<String, Entry>()
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    map[name] = Entry(name, id, mime == DocumentsContract.Document.MIME_TYPE_DIR, size)
                }
            }
            listings[rel] = map
            return map
        }

        private fun docIdOf(rel: String): String {
            if (rel.isEmpty()) return rootDocId
            return children(rel.substringBeforeLast('/', ""))[rel.substringAfterLast('/')]?.docId
                ?: throw IOException("目标目录里没有：" + rel)
        }

        /** 找或建一个子目录（已存在就复用，绝不建出 `attachments (1)` 这种重复目录） */
        private fun dir(rel: String): Uri {
            dirUri[rel]?.let { return it }
            val parentRel = rel.substringBeforeLast('/', "")
            val name = rel.substringAfterLast('/')
            // 父目录也可能还没建（第一次导出时 `attachments/202610` 整条链都不存在）：
            // 先递归把它建出来，再在它里面找自己 —— 先 children(parentRel) 的话，
            // 祖先缺一层就抛「目标目录里没有：…」，这正是在 device5598 上炸掉导出的那条路。
            val parent = dir(parentRel)
            val existing = children(parentRel)[name]
            if (existing != null && !existing.dir) {
                // 同名的是个文件：再 createDocument 只会让 provider 造出 `attachments (1)`，
                // 用户选的那个目录里反而没有附件。停下来把冲突说清，比悄悄换个名字好。
                throw IOException("目标目录里已有同名文件，无法建目录：" + rel)
            }
            val uri = if (existing != null) {
                DocumentsContract.buildDocumentUriUsingTree(tree, existing.docId)
            } else {
                val created = DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name
                ) ?: throw IOException("建目录失败：" + rel)
                // 新子目录还没进父目录的缓存列表，不清掉会让下一次 docIdOf 以为它不存在
                listings.remove(parentRel)
                created
            }
            dirUri[rel] = uri
            listings.remove(rel)
            return uri
        }

        /** 同名同内容 → 跳过（返 false）；同名不同内容 → 冲突副本（返 true） */
        fun putText(name: String, text: String): Boolean {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val existing = children("")[name]
            if (existing != null && !existing.dir && existing.size == bytes.size.toLong()) {
                val old = runCatching {
                    resolver.openInputStream(documentUri(existing))?.use { it.readBytes() }
                }.getOrNull()
                if (old != null && old.contentEquals(bytes)) return false
            }
            val target = if (existing == null) name else freeName("", name)
            write(dir(""), target, bytes)
            return true
        }

        fun putFile(rel: String, src: File): Boolean {
            if (src.length() > MAX_ATT_BYTES) return false
            val parentRel = rel.substringBeforeLast('/', "")
            val name = rel.substringAfterLast('/')
            // 先建父目录、再看里面有什么：第一次导出时 `attachments/202610` 根本不存在，
            // 先 children(parentRel) 会直接抛「目标目录里没有：attachments」，附件一个都写不出来。
            val parent = dir(parentRel)
            val existing = children(parentRel)[name]
            if (existing != null && !existing.dir && existing.size == src.length()) {
                val same = runCatching {
                    val a = resolver.openInputStream(documentUri(existing))?.use { it.readBytes() }
                    a != null && a.contentEquals(src.readBytes())
                }.getOrDefault(false)
                if (same) return false
            }
            val bytes = src.readBytes()
            // 冲突副本必须落在同一个目录里（`attachments/202610/x--2.jpg`），不是根目录
            val target = if (existing == null) name else freeName(parentRel, name)
            write(parent, target, bytes)
            return true
        }

        private fun documentUri(e: Entry): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)

        /** `笔记.md` 被占且内容不同 → `笔记--2.md`、`笔记--3.md`…（只看同一个目录里的名字） */
        private fun freeName(rel: String, name: String): String {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            val taken = children(rel).keys
            var i = 2
            while (i < 1000) {
                val candidate = base + "--" + i + ext
                if (!taken.contains(candidate)) return candidate
                i++
            }
            return base + "--" + System.currentTimeMillis() + ext
        }

        private fun write(parentUri: Uri, name: String, bytes: ByteArray) {
            val uri = DocumentsContract.createDocument(
                resolver, parentUri, mimeOf(name), name
            ) ?: throw IOException("无法创建文件：" + name)
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IOException("无法写入文件：" + name)
            listings.clear()
        }

        /** 递归收集相对路径 + 条目 */
        fun walk(prefix: String, depth: Int, out: MutableList<Pair<String, Entry>>) {
            if (depth > MAX_DEPTH || out.size > MAX_ENTRIES) return
            children(prefix).values.forEach { e ->
                val rel = if (prefix.isEmpty()) e.name else prefix + "/" + e.name
                out.add(rel to e)
                if (e.dir) walk(rel, depth + 1, out)
            }
        }

        companion object {
            private fun mimeOf(name: String): String = when {
                name.endsWith(".md", true) -> "text/markdown"
                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                name.endsWith(".png", true) -> "image/png"
                name.endsWith(".webp", true) -> "image/webp"
                name.endsWith(".gif", true) -> "image/gif"
                else -> "application/octet-stream"
            }

            fun readText(resolver: ContentResolver, tree: Uri, e: Entry): String? {
                if (e.dir || e.size > MAX_MD_BYTES) return null
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
                return resolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }

            fun copyToFile(resolver: ContentResolver, tree: Uri, e: Entry, target: File) {
                if (e.dir) return
                if (e.size > MAX_ATT_BYTES) return
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
                ensureDirStatic(target.parentFile)
                val input = resolver.openInputStream(uri) ?: return
                input.use { src ->
                    val out = FileOutputStream(target)
                    out.use { dst ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n <= 0) break
                            dst.write(buf, 0, n)
                        }
                        dst.flush()
                        // 附件也是事实源：不 fsync 就可能留下「文件在、内容是半截」
                        dst.fd.sync()
                    }
                }
            }

            private fun ensureDirStatic(dir: File?) {
                if (dir == null || dir.isDirectory) return
                dir.mkdirs()
            }
        }
    }
}
