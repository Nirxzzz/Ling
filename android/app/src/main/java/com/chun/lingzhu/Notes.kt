package com.chun.lingzhu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * v2.4 记事本：数据访问。
 *
 * ## 这一层刻意写得薄
 * 工程只有 JVM 单测、没有 androidTest，所以这里的代码**测不到**。
 * 应对办法：所有判断（正文怎么截、迁移怎么去重、回收站什么时候到期、标签怎么解析）
 * 都放进 [Note] / [NoteSyntax] 的纯函数里测；这里只做「把值搬进搬出」。
 *
 * ## 契约
 *  - **打不开就抛**，绝不返回空列表。FileMemo 定下的纪律：读失败不等于空库。
 *    把「打不开」当成「没有笔记」，下一步就是同步把远端全删了。
 *  - 所有写操作走事务；失败回滚，不留半条记录。
 */
object Notes {

    /** 列表一条：卡片流只需要这些字段，不把 body 全查出来（长笔记很占内存） */
    data class Summary(
        val id: Long,
        val uid: String,
        val title: String,
        val preview: String,
        val createdAt: Long,
        /** 最后修改时刻。列表按「最近修改」排时用它，行尾显示的时刻也跟着它 */
        val updatedAt: Long,
        val pinned: Boolean,
        val archived: Boolean,
        val deleted: Boolean,
        val hasImage: Boolean,
        val tags: List<String>,
    )

    /** 列表筛选条件 */
    enum class Filter { ALL, PINNED, ARCHIVED, TRASH }

    /**
     * 列表排序键。
     *
     * 三套键对应界面上的三项：最近修改 / 创建时间 / 标题。置顶永远在最前面，与是哪一套无关。
     * 游标翻页只对 [Sort.CREATED] 定义（游标字段就是创建时间那一套），
     * 另外两套的翻页由界面用「多取一条」判断还有没有更多 —— 这在任何排序下都成立。
     */
    enum class Sort { MODIFIED, CREATED, TITLE }

    /**
     * 翻页游标：上一页最后一条在排序键上的位置。
     *
     * 这三个字段正好是 [feed] 的 ORDER BY（pinned, created_at, id），
     * 界面把它从 [Summary] 里原样取出来再传回去就行，不需要另存状态。
     */
    data class Cursor(val pinned: Boolean, val createdAt: Long, val id: Long) {
        /**
         * 这一条是不是排在游标之后（即「更旧」）。
         *
         * feed() 的分页 SQL 用的是同一套比较；SQL 测不到，所以这里留一个纯函数，
         * 让「翻页会不会漏条 / 会不会重复」这件事在 JVM 上也有据可查。
         */
        fun isAfter(other: Cursor): Boolean = when {
            pinned != other.pinned -> !pinned && other.pinned
            createdAt != other.createdAt -> createdAt < other.createdAt
            else -> id < other.id
        }
    }

    // ---------------- 读写 ----------------

    /**
     * 插入一条笔记，返回自增 id。同时从 body 解析标签写进 tags / note_tags。
     *
     * **标签始终从 body 重建**，不接受外部传入 —— 这样「正文是唯一事实来源」才成立。
     * 如果允许外部传标签，正文和标签表迟早会不一致，而这种不一致查起来极难。
     */
    fun insert(db: SQLiteDatabase, n: Note.Item): Long = synchronized(NoteVault.mutationLock) {
        var durable = false
        db.beginTransaction()
        try {
            val id = db.insertOrThrow(NoteStore.T_NOTES, null, valuesOf(n))
            syncTags(db, id, n.body, n.updatedAt)
            syncFts(db, id, n.title, n.body, n.ocrText)
            // md 在事务里、提交之前落盘：publish 读的是本线程同一事务里的暂存行。
            // 非 notes.db（自检库）返回 false，不算失败 —— 那里没有 vault。
            durable = NoteVault.publishOrThrow(db, id)
            db.setTransactionSuccessful()
            return@synchronized id
        } finally {
            endTransaction(db, n.uid, durable)
        }
    }

    /**
     * 更新正文：重建标签、重建检索、rev + 1。
     *
     * 正文超上限时和 [Note.create] 一样截断 —— **两条写入路径必须同一口径**。
     * 原来这里不截断，而 create() 截断，于是：
     * 新建一条超长笔记会存成 10 万字，之后编辑它却能把库撑到任意长度。
     * 库和 FTS 索引都没有硬上限，最后是磁盘和检索先坏，而不是这里先拦。
     *
     * ## expectedRev：防止并发静默覆盖
     * 编辑页进页面时读到的 rev 原样传回来。UPDATE 带上 `rev=?` 条件，
     * 库里 rev 已经不是这个值就命中 0 行 —— 说明编辑期间这条被别处改过
     * （悬浮球速记、另一台设备同步、别的编辑页）。此时**整条回滚、什么都不写**，
     * 返回 [UpdateResult.Stale]，由调用方把冲突告诉用户并保留输入。
     * 不带这个条件的话，后保存的一方直接把先保存的内容盖掉，双方都不会察觉。
     *
     * @param expectedRev 期望的版本号，必须是调用方**实际读到的**那个 rev（[revOf] / [Note.Item.rev]）。
     *   以前这里默认 0 表示「不校验」：少传一个参数就把整个并发保护关掉了，
     *   编译期还不会有任何提示，等于给静默覆盖留了一条免检通道。
     *   现在没有默认值、也不认任何哨兵值 —— 想写就必须先读到。
     */
    fun updateBody(db: SQLiteDatabase, id: Long, title: String, body: String, now: Long,
                   expectedRev: Int): UpdateResult = synchronized(NoteVault.mutationLock) {
        val safe = if (body.length <= Note.MAX_BODY) body else body.substring(0, Note.MAX_BODY)
        var durable = false
        var uid = ""
        db.beginTransaction()
        try {
            // 先确认目标行还在。原来的写法直接 execSQL，**更新 0 行也不报错**：
            // 笔记若已被别处删掉（进回收站后又过了 30 天被 purge），
            // 这里 UPDATE 影响 0 行，syncTags/syncFts 跟着写空，
            // 调用方完全察觉不到，编辑页还会 setResult(RESULT_OK) 退出去 ——
            // 用户以为存上了，其实什么都没留下。
            if (!rowExists(db, id)) {
                db.setTransactionSuccessful()
                return@synchronized UpdateResult.Gone
            }
            uid = uidOf(db, id) ?: ""
            if (revOf(db, id) != expectedRev) {
                // 编辑期间被改过：不写任何东西，让调用方处理冲突
                db.setTransactionSuccessful()
                return@synchronized UpdateResult.Stale
            }
            // 版本条件再进一次 UPDATE：校验和写入之间仍可能被别处插一脚，
            // 那时这里影响 0 行 —— 事务不提交即回滚，一个字节都不留。
            db.execSQL(
                "UPDATE " + NoteStore.T_NOTES + " SET title=?, body=?, updated_at=?, rev=rev+1 " +
                    "WHERE id=? AND deleted_at=0 AND rev=?",
                arrayOf<Any>(title, safe, now, id, expectedRev)
            )
            if (changedRows(db) == 0) {
                // 校验和 UPDATE 之间又变了，或这条刚好进了回收站 —— 一律当作没写
                db.setTransactionSuccessful()
                return@synchronized UpdateResult.Gone
            }
            syncTags(db, id, safe, now)
            syncFts(db, id, title, safe, "")
            // 新正文先落 md，再提交索引：md 失败则整条回滚，旧库一个字节不变
            durable = NoteVault.publishOrThrow(db, id)
            db.setTransactionSuccessful()
            return@synchronized UpdateResult.Wrote(revOf(db, id))
        } finally {
            endTransaction(db, uid, durable)
        }
    }

    /** [updateBody] 的结果。只回答「写没写、现在是什么版本」，不携带异常 */
    sealed class UpdateResult {
        /** 写成功了，rev 已变成新值 */
        data class Wrote(val rev: Int) : UpdateResult()
        /** 编辑期间被别处改过：什么都没写，调用方必须保留用户输入 */
        object Stale : UpdateResult()
        /** 这条笔记已经不在了（被删或被清） */
        object Gone : UpdateResult()
    }

    /**
     * 当前 rev；行不存在返回 -1。
     *
     * 编辑页进页面时用它读到 rev，保存时原样交回 [updateBody]。
     * 自检里也走这条读，不从别处猜版本号。
     */
    fun revOf(db: SQLiteDatabase, id: Long): Int {
        db.rawQuery(
            "SELECT rev FROM " + NoteStore.T_NOTES + " WHERE id=?",
            arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else -1 }
    }

    /**
     * 这条笔记是否还在（未软删、未 purge）。
     *
     * 单独抽出来是为了让「更新前先确认」这件事有个名字 ——
     * 以前它根本不存在，UPDATE 语句里的 WHERE 条件默默承担了这个职责，
     * 但它影响 0 行不会告诉任何人。
     */
    private fun rowExists(db: SQLiteDatabase, id: Long): Boolean {
        db.rawQuery(
            "SELECT 1 FROM " + NoteStore.T_NOTES + " WHERE id=? AND deleted_at=0 LIMIT 1",
            arrayOf(id.toString())
        ).use { c -> return c.moveToFirst() }
    }

    /**
     * 软删除：只记时间，附件和数据全留着，30 天后才物理清除。
     *
     * 返回受影响行数。**0 = 这条笔记根本不存在。**
     *
     * 原本返回 Unit，而 execSQL 影响 0 行不抛异常 —— 详情页的删除按钮
     * 外面虽然包着 try/catch，但那个 catch 永远等不到异常。
     * 用户点「删除」，看到页面关闭，以为删掉了，实际什么都没发生。
     */
    fun softDelete(db: SQLiteDatabase, id: Long, now: Long): Int = synchronized(NoteVault.mutationLock) {
        val uid = uidOf(db, id) ?: return@synchronized 0
        var durable = false
        db.beginTransaction()
        try {
            db.execSQL("UPDATE " + NoteStore.T_NOTES + " SET deleted_at=? WHERE id=?", arrayOf<Any>(now, id))
            val n = changedRows(db)
            // deleted_at 也是元数据：md 里跟着写，重建索引时才不会把回收站的笔记当成活的
            if (n > 0) durable = NoteVault.publishOrThrow(db, id)
            db.setTransactionSuccessful()
            return@synchronized n
        } finally {
            endTransaction(db, uid, durable)
        }
    }

    /** 从回收站恢复。返回受影响行数，0 = 不存在。 */
    fun restore(db: SQLiteDatabase, id: Long): Int = synchronized(NoteVault.mutationLock) {
        val uid = uidOf(db, id) ?: return@synchronized 0
        var durable = false
        db.beginTransaction()
        try {
            db.execSQL("UPDATE " + NoteStore.T_NOTES + " SET deleted_at=0 WHERE id=?", arrayOf<Any>(id))
            val n = changedRows(db)
            if (n > 0) durable = NoteVault.publishOrThrow(db, id)
            db.setTransactionSuccessful()
            return@synchronized n
        } finally {
            endTransaction(db, uid, durable)
        }
    }

    /**
     * 上一条 UPDATE/DELETE 影响了几行。
     *
     * SQLite 的 changes() 返回最近一次写入语句影响的行数，
     * 紧跟在 execSQL 之后调用就能拿到 —— 这是 execSQL 之外唯一能知道
     * 「到底改到东西没有」的办法。
     */
    private fun changedRows(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT changes()", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * 事务的收尾：正常就提交；md 已经落盘之后索引才失败，就抛 [NoteVault.IndexException]。
     *
     * 顺序契约（见 [NoteVault]）：md 的 fsync + rename 成功之后才提交 SQL。
     * 反过来「事务成功、文件失败」= 库里有、盘上没有，重建时这段内容就真没了；
     * 而 md 成功、提交失败则相反 —— 内容还在，只是索引要补，所以抛的是 IndexException。
     */
    private fun endTransaction(db: SQLiteDatabase, uid: String, durable: Boolean) {
        try {
            db.endTransaction()
        } catch (t: Throwable) {
            if (!durable) throw t
            throw NoteVault.IndexException(
                uid, "内容已写入 Markdown，但索引提交失败：" + (t.message ?: t.javaClass.simpleName), t
            )
        }
    }

    /** 一条笔记的全部可写列（不含 id；插入时要不要给显式 id 由 [putRow] 决定） */
    private fun valuesOf(n: Note.Item) = android.content.ContentValues().apply {
        put("uid", n.uid)
        put("title", n.title)
        put("body", n.body)
        put("source", n.source)
        put("origin", n.origin)
        put("pinned", if (n.pinned) 1 else 0)
        put("archived", if (n.archived) 1 else 0)
        put("deleted_at", n.deletedAt)
        put("created_at", n.createdAt)
        put("updated_at", n.updatedAt)
        put("ocr_text", n.ocrText)
        put("summary", n.summary)
        put("todo_id", n.todoId)
        put("card_ids", n.cardIds.joinToString(","))
        put("rev", n.rev)
    }

    /**
     * 物理清除（回收站里「彻底删掉」）。
     *
     * 原来只删 notes / note_tags / fts 三张表：attachments 里的行留成孤儿，
     * 磁盘上的原图和缩略图也一份没少 —— 用户「彻底删掉」之后，
     * 卡片流里看不到那条笔记，但图片还占着空间、还能从文件管理器翻出来。
     *
     * ## 顺序：墓碑 → 删索引 → 删文件
     * md 先挪进 `trash/`（那是 durable 的删除事实），索引行再删；
     * 反过来的话删库成功、挪文件失败，下一次重建会把这条笔记从 md 复活出来。
     * 附件路径只能在删行**之前**读出来，文件删除放在事务提交之后
     * （事务失败时库里不该留下指向不存在文件的记录）。
     *
     * vault 只在真正的 notes.db 上变更；非生产库默认只清索引。
     * 自检显式传入 ctx 时仅清除这条已核对 uid 的附件文件，不访问真实 vault。
     *
     * @param noteUid 不为 null 时，只有库里那条笔记的 uid 与它一致才删。
     *   调用方拿到的是渲染时那一秒的列表，期间数据库可能被换掉（恢复备份、外部写入）；
     *   不带 uid 校验的话，会按过期的 id 删掉另一条笔记的附件。
     *   **uid 校验先于墓碑**：对不上就一个文件都不动。
     * @return 是否真的删掉了一条笔记（false = 这条已经不在了，什么都没删）
     */
    fun purge(db: SQLiteDatabase, id: Long, noteUid: String? = null, ctx: Context? = null): Boolean =
        synchronized(NoteVault.mutationLock) {
            // uid 校验先于任何落盘动作：对不上就一个字节都不动
            val uid = uidOf(db, id) ?: return@synchronized false
            if (noteUid != null && uid != noteUid) return@synchronized false
            // 附件路径只能在删行之前读出来，事后再落盘删
            val files = ArrayList<String>(2)
            db.rawQuery(
                "SELECT path, thumb FROM " + NoteStore.T_ATT + " WHERE note_id=?",
                arrayOf(id.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.isNotEmpty() }?.let { files.add(it) }
                    c.getString(1)?.takeIf { it.isNotEmpty() }?.let { files.add(it) }
                }
            }
            // 只有真实 notes.db 才产生 Markdown 墓碑；自检不访问真实 vault。
            val vaultCtx = NoteVault.registeredFor(db)
            val filesCtx = vaultCtx ?: ctx
            val tombstoned = vaultCtx != null && NoteVault.tombstone(vaultCtx, uid) > 0
            var removed = 0
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM " + NoteStore.T_ATT + " WHERE note_id=?", arrayOf<Any>(id))
                db.execSQL("DELETE FROM " + NoteStore.T_NOTE_TAGS + " WHERE note_id=?", arrayOf<Any>(id))
                db.execSQL("DELETE FROM " + NoteStore.T_FTS + " WHERE rowid=?", arrayOf<Any>(id))
                db.execSQL("DELETE FROM " + NoteStore.T_NOTES + " WHERE id=? AND uid=?", arrayOf<Any>(id, uid))
                removed = changedRows(db)
                db.setTransactionSuccessful()
            } finally {
                endTransaction(db, uid, tombstoned)
            }
            if (removed == 0) return@synchronized false
            // 事务已经提交成功，这里的行确实没了。文件删除放在这之后：
            // 先删文件再删库，事务失败就会留下「库里有记录、文件已不见」的笔记。
            if (filesCtx != null) {
                NoteAttach.deleteFiles(filesCtx, files)
                // 缩略图按 uid 缓存过路径，删完必须让缓存失效，
                // 否则同 uid 的笔记（恢复备份后重建）会拿到已删的文件
                NoteAttach.invalidateThumb(uid)
            }
            true
        }

    // ---------------- 查询 ----------------

    /**
     * 卡片流。
     *
     * 置顶永远最前，之后才是排序键（[Sort]），最后用 id 收尾：
     * 同一毫秒的两条也有确定先后，否则翻页会漏条或重复。
     *
     * ## 为什么带游标
     * 默认 limit=200 是硬截断，而界面原样用它 —— 第 201 条更旧的笔记
     * **在任何地方都看不到**，也没有任何提示说「还有更多」。
     * 现在多给一个 [after]：传上一页最后一条的位置，就接着往后取。
     * 用 (pinned, created_at, id) 三元组而不是 OFFSET：
     * 取页期间如有新笔记插进来，OFFSET 会把同一页内容整体后移，用户看到重复条目；
     * 游标锚定在「上一页最后一条之后」，新插入的内容只会出现在第一页。
     * **游标只对 [Sort.CREATED] 成立**（游标的三个字段就是创建时间那一套）；
     * 另外两套由界面「多取一条」判断还有没有更多，这在任何排序下都成立。
     */
    fun feed(db: SQLiteDatabase, filter: Filter, limit: Int = 200,
             after: Cursor? = null, sort: Sort = Sort.CREATED): List<Summary> {
        val where = when (filter) {
            Filter.ALL -> "deleted_at=0 AND archived=0"
            Filter.PINNED -> "deleted_at=0 AND archived=0 AND pinned=1"
            Filter.ARCHIVED -> "deleted_at=0 AND archived=1"
            Filter.TRASH -> "deleted_at>0"
        }
        // 置顶永远最前；排序键之后一律用 id 收尾 —— 同一毫秒的两条也有确定先后，
        // 少了这一步翻页会漏条或重复。
        val order = when (sort) {
            Sort.MODIFIED -> "n.pinned DESC, n.updated_at DESC, n.id DESC"
            Sort.CREATED -> "n.pinned DESC, n.created_at DESC, n.id DESC"
            // 空标题在列表里显示的是正文首行（与 Note.Item.displayTitle 同口径），
            // 所以按正文排：排序键要和用户看到的标题一致
            Sort.TITLE -> "n.pinned DESC, " +
                "CASE WHEN TRIM(n.title)='' THEN n.body ELSE n.title END COLLATE NOCASE ASC, n.id ASC"
        }
        val args = ArrayList<String>(4)
        var pageCond = ""
        // 游标只对 [Sort.CREATED] 定义（三个字段就是创建时间那一套）。
        // 另外两套的翻页由界面「多取一条」判断，传了 after 也不参与 SQL ——
        // 拿创建时间游标去截修改时间排序，只会漏条。
        if (after != null && sort == Sort.CREATED) {
            // 升序比较直接对应 ORDER BY n.pinned DESC, n.created_at DESC, n.id DESC。
            // 用 positional ?1/?2/?3：pageCond 在下，limit 在最后，匿名 ? 会按出现顺序错位。
            pageCond = " AND (n.pinned<?1 OR (n.pinned=?1 AND (n.created_at<?2 OR (n.created_at=?2 AND n.id<?3))))"
            args.add(if (after.pinned) "1" else "0")
            args.add(after.createdAt.toString())
            args.add(after.id.toString())
        }
        args.add(limit.toString())
        val out = ArrayList<Summary>()
        db.rawQuery(
            "SELECT n.id, n.uid, n.title, n.body, n.created_at, n.updated_at, n.pinned, n.archived, n.deleted_at, " +
                "(SELECT COUNT(*) FROM " + NoteStore.T_ATT + " a WHERE a.note_id=n.id) AS att " +
                "FROM " + NoteStore.T_NOTES + " n WHERE " + where + pageCond + " " +
                "ORDER BY " + order + " LIMIT ?",
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    Summary(
                        id = id,
                        uid = c.getString(1),
                        title = c.getString(2),
                        preview = c.getString(3).take(160),
                        createdAt = c.getLong(4),
                        updatedAt = c.getLong(5),
                        pinned = c.getInt(6) != 0,
                        archived = c.getInt(7) != 0,
                        deleted = c.getInt(8) > 0,
                        hasImage = c.getInt(9) > 0,
                        tags = emptyList(),   // 下面批量补
                    )
                )
            }
        }
        // 标签一次查完，不再在循环里逐条查
        val tagMap = tagsOfMany(db, out.map { it.id })
        for (i in out.indices) {
            val t = tagMap[out[i].id] ?: continue
            out[i] = out[i].copy(tags = t)
        }
        return out
    }

    /** 某条笔记的标签 */
    fun tagsOf(db: SQLiteDatabase, noteId: Long): List<String> {
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT t.name FROM " + NoteStore.T_TAGS + " t JOIN " + NoteStore.T_NOTE_TAGS + " nt ON nt.tag_id=t.id " +
                "WHERE nt.note_id=? ORDER BY t.name",
            arrayOf(noteId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /**
     * 批量取标签：一次查完，按 note_id 分发。
     *
     * 原来 feed() / byIds() 都在循环里逐条调 tagsOf()，200 条笔记就是 200 次查询。
     * 这是列表页首屏慢的主因（实测 9 条笔记 renderList 要 83ms）。
     * 结果完全一致，只是少 199 次往返。
     */
    private fun tagsOfMany(db: SQLiteDatabase, ids: List<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val byId = HashMap<Long, ArrayList<String>>()
        db.rawQuery(
            "SELECT nt.note_id, t.name FROM " + NoteStore.T_TAGS + " t " +
                "JOIN " + NoteStore.T_NOTE_TAGS + " nt ON nt.tag_id=t.id " +
                "WHERE nt.note_id IN (" + ids.joinToString(",") { it.toString() } + ") ORDER BY t.name",
            null
        ).use { c ->
            while (c.moveToNext()) byId.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getString(1))
        }
        return byId
    }

    /** 按标签筛选（含父标签：查「数学」也要带出「数学/导数」） */
    fun byTag(db: SQLiteDatabase, tag: String, limit: Int = 200): List<Summary> {
        val ids = ArrayList<Long>()
        // 标签里允许出现 _，而 _ 在 LIKE 里是通配符。
        // 真机实测：库里同时有 a_b 和 a+b/x 时，查 a_b 会把 a+b/x 当成子标签带出来。
        // 用 ESCAPE 把 _ / % 转义掉，让 LIKE 只做字面匹配。
        val base = NoteSyntax.normTag(tag)
        val escaped = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        db.rawQuery(
            "SELECT DISTINCT nt.note_id FROM " + NoteStore.T_NOTE_TAGS + " nt JOIN " + NoteStore.T_TAGS + " t ON t.id=nt.tag_id " +
                "WHERE t.name=? OR t.name LIKE ? ESCAPE '\\'",
            arrayOf(base, escaped + "/%")
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        if (ids.isEmpty()) return emptyList()
        // 曾经是 feed(db, ALL, limit).filter { it.id in ids } ——
        // 和 search() 当年那个 bug 一模一样：feed 只给最新的 N 条，
        // 一旦带这个标签的笔记比 N 条更旧，交集就是空，而且不报错。
        // 真机实测：20 条旧标签笔记被 300 条新笔记挤出后，byTag 返回 0。
        return byIds(db, ids, null).take(limit)
    }

    /**
     * 全文搜索：走 FTS4。中文先切二元组，和索引侧同一口径。
     *
     * ## 不能「先取最新 N 条再和命中集合求交集」
     * 曾经的写法是：
     *     feed(db, Filter.ALL, maxOf(limit, ids.size)).filter { it.id in ids }
     * 看起来没问题，实际有一条致命的隐式前提：**命中的笔记必须落在最新 N 条里**。
     * 一旦库里笔记多起来，这个前提就不成立了 ——
     * 真机实测 200 条笔记时：MATCH 返回最旧的 100 条（id 4..103），
     * feed 返回最新的 100 条（id 104..203），两者**范围完全不重叠，交集恒为 0**，
     * 于是搜索永远返回空，而且不报任何错。
     *
     * 这也解释了为什么「裸查 FTS 能命中 5 条，走 search() 却 0 条」——
     * 索引一直是好的，坏的是合并方式。
     *
     * 现在按 rowid 直接查 notes，命中几条返回几条，与「最新多少条」无关。
     */
    fun search(db: SQLiteDatabase, query: String, limit: Int = 100): List<Summary> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val terms = bigrams(q)
        if (terms.isEmpty()) return emptyList()
        // 每个二元组加 * 做前缀匹配（用户打「函」也能命中「函数」这类索引项）
        //
        // 但要注意：FTS4 的 MATCH 里 OR / AND / NOT / NEAR 是算子，而 bigrams()
        // 只去标点、不改字母，所以用户输入「导数 OR 量子」会产出 "导数 OR 量子"，
        // 拼成 "导数* OR 量子*" 就成了真布尔查询 —— 查询语义被用户打的字改写。
        // 真机实测：库里明明有含「导数」的笔记，搜「导数 OR 量子」却命中 0。
        // 这里把这四个保留字剔掉；它们对中文检索没有意义（中文二元组不会是 OR）。
        // 用户真想搜含 "or" 的英文单词时，前缀匹配仍然覆盖（"or*" 匹配 "order" 等），
        // 因为剔除后该词直接消失，而不是变成算子。
        val ops = setOf("OR", "AND", "NOT", "NEAR")
        val match = terms.split(" ")
            .filter { it.isNotEmpty() && it.uppercase() !in ops }
            .joinToString(" ") { it + "*" }
        if (match.isEmpty()) return emptyList()
        val ids = ArrayList<Long>()
        db.rawQuery(
            "SELECT rowid FROM " + NoteStore.T_FTS + " WHERE " + NoteStore.T_FTS + " MATCH ? LIMIT ?",
            arrayOf(match, limit.toString())
        ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        if (ids.isEmpty()) return emptyList()
        // null = 归档的也搜（归档不是删除），但仍然排除回收站里的
        return byIds(db, ids, null)
    }

    /**
     * 按 id 批量取摘要，保持传入顺序（即 FTS 的相关性顺序）。
     *
     * 用一条 IN (...) 查，不是逐条查 —— 100 条命中就是 100 次往返，低端机上很肉。
     */
    fun byIds(db: SQLiteDatabase, ids: List<Long>, filter: Filter? = Filter.ALL): List<Summary> {
        if (ids.isEmpty()) return emptyList()
        // filter = null 表示「不按筛选条件过滤，只排除已删除的」。
        // 搜索用这个：归档不等于删掉，用户搜东西时不该被「这条归档了」挡住 ——
        // 真机自检抓到过这个：归档一条笔记后，搜它返回 0 条，看起来像丢了。
        val where = when (filter) {
            null -> " AND n.deleted_at=0"
            Filter.ALL -> " AND n.deleted_at=0 AND n.archived=0"
            Filter.PINNED -> " AND n.deleted_at=0 AND n.archived=0 AND n.pinned=1"
            Filter.ARCHIVED -> " AND n.deleted_at=0 AND n.archived=1"
            Filter.TRASH -> " AND n.deleted_at>0"
        }
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        val found = HashMap<Long, Summary>()
        db.rawQuery(
            "SELECT n.id, n.uid, n.title, n.body, n.created_at, n.updated_at, n.pinned, n.archived, n.deleted_at, " +
                "(SELECT COUNT(*) FROM " + NoteStore.T_ATT + " a WHERE a.note_id=n.id) AS att " +
                "FROM " + NoteStore.T_NOTES + " n WHERE n.id IN (" + placeholders + ")" + where,
            args
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                found[id] = Summary(
                    id = id,
                    uid = c.getString(1),
                    title = c.getString(2),
                    preview = c.getString(3).take(160),
                    createdAt = c.getLong(4),
                    updatedAt = c.getLong(5),
                    pinned = c.getInt(6) != 0,
                    archived = c.getInt(7) != 0,
                    deleted = c.getInt(8) > 0,
                    hasImage = c.getInt(9) > 0,
                    tags = emptyList(),
                )
            }
        }
        // 标签一次查完（原来这里是逐条 tagsOf，搜索结果多时会很慢）
        if (found.isNotEmpty()) {
            val tagMap = tagsOfMany(db, found.keys.toList())
            // 用 replaceAll 而不是边迭代边 put —— 后者依赖 HashMap 的实现细节，
            // 换个 Map 实现就会 ConcurrentModificationException。
            found.replaceAll { k, v -> tagMap[k]?.let { v.copy(tags = it) } ?: v }
        }
        // 保持 FTS 给的顺序（相关性），而不是按 id 或时间重排
        return ids.mapNotNull { found[it] }
    }

    /** meta 表的读 */
    fun metaGet(db: SQLiteDatabase, key: String): String? {
        db.rawQuery("SELECT v FROM " + NoteStore.T_META + " WHERE k=?", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** meta 表的写（存在就覆盖） */
    fun metaPut(db: SQLiteDatabase, key: String, value: String) {
        val cv = android.content.ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        db.insertWithOnConflict(NoteStore.T_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 索引方案版本：改动切词或 rowid 规则时 +1，老库会自动重建一次 */
    const val FTS_SCHEMA = 2

    /**
     * 需要时重建全文索引（幂等：同一版本只重建一次）。
     *
     * 版本 2 对应「修好 rowid 写入」那次改动。版本 1 的库里索引 rowid 是 FTS 自增的，
     * 和 notes.id 对不上，那些笔记永远搜不到 —— 必须重建。
     *
     * @return 本次是否真的重建了
     */
    fun ensureFtsSchema(db: SQLiteDatabase): Boolean {
        val cur = metaGet(db, "fts_schema")?.toIntOrNull() ?: 1
        if (cur >= FTS_SCHEMA) return false
        rebuildFts(db)
        metaPut(db, "fts_schema", FTS_SCHEMA.toString())
        return true
    }
    /**
     * 重建全文索引。
     *
     * ## 什么时候需要
     * 早期版本的 syncFts 用 ContentValues 写 rowid，那个 rowid 会被 FTS4 忽略，
     * 于是索引里的 rowid 是 1,2,3…（FTS 自己的自增），和 notes.id 完全错开。
     * 搜索最后一步是 feed(...).filter { it.id in ftsIds }，两个集合无交集 → 恒返回 0 条。
     *
     * 修好写入之后，**旧数据仍然是坏的** —— 用户升级后翻自己的历史笔记会一条都搜不到，
     * 而新记的能搜到。这种「一半好一半坏」最难排查，所以启动时无条件重建一次。
     *
     * 成本：每条笔记一次 DELETE + INSERT。几千条笔记在几百毫秒量级，放后台线程跑。
     *
     * @return 重建了多少条
     */
    /** 一条完整笔记 + 有没有图。详情页用；找不到返回 null */
    data class Full(val item: Note.Item, val hasImage: Boolean, val tags: List<String>)

    fun one(db: SQLiteDatabase, id: Long): Full? {
        db.rawQuery(
            "SELECT n.uid, n.title, n.body, n.source, n.origin, n.pinned, n.archived, " +
                "n.deleted_at, n.created_at, n.updated_at, n.ocr_text, n.summary, n.todo_id, n.card_ids, n.rev, " +
                "(SELECT COUNT(*) FROM " + NoteStore.T_ATT + " a WHERE a.note_id=n.id) AS att " +
                "FROM " + NoteStore.T_NOTES + " n WHERE n.id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val item = Note.Item(
                uid = c.getString(0),
                title = c.getString(1) ?: "",
                body = c.getString(2) ?: "",
                source = c.getString(3) ?: "manual",
                origin = c.getString(4) ?: "",
                pinned = c.getInt(5) != 0,
                archived = c.getInt(6) != 0,
                deletedAt = c.getLong(7),
                createdAt = c.getLong(8),
                updatedAt = c.getLong(9),
                ocrText = c.getString(10) ?: "",
                summary = c.getString(11) ?: "",
                todoId = c.getLong(12),
                cardIds = (c.getString(13) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                rev = c.getInt(14),
            )
            return Full(item, c.getInt(15) > 0, tagsOf(db, id))
        }
    }

    // ---------------- 带附件的写入 ----------------

    /**
     * 新建一条带附件的笔记：笔记行、附件元数据、md 三件事要么全成、要么全不成。
     *
     * 附件文件本身由 [NoteAttach] 提前写好（它的返回值就是 [NoteAttach.Saved]）：
     * 这里只负责把「盘上已经有这些文件」这件事写进索引，并在提交前发布 md。
     * md 失败 -> 事务回滚，附件行不会留在库里；索引提交失败 -> [NoteVault.IndexException]，
     * 正文与附件已经持久化，调用方不该把它当成「没存上」重试。
     */
    fun saveWithAttachments(db: SQLiteDatabase, item: Note.Item,
                            atts: List<NoteAttach.Saved>): Long =
        synchronized(NoteVault.mutationLock) {
            var durable = false
            db.beginTransaction()
            try {
                val id = db.insertOrThrow(NoteStore.T_NOTES, null, valuesOf(item))
                syncTags(db, id, item.body, item.updatedAt)
                syncFts(db, id, item.title, item.body, item.ocrText)
                atts.forEachIndexed { i, a ->
                    val cv = android.content.ContentValues().apply {
                        // 附件 uid 由笔记 uid + 序号派生：重建索引时同一条笔记的附件身份稳定
                        put("uid", item.uid + ":a" + (if (i == 0) "" else (i + 1).toString()))
                        put("note_id", id)
                        put("mime", "image/jpeg")
                        put("path", a.path)
                        put("thumb", a.thumb)
                        put("w", a.w)
                        put("h", a.h)
                        put("bytes", a.bytes)
                        put("sha256", a.sha256)
                        put("created_at", item.createdAt)
                    }
                    db.insertOrThrow(NoteStore.T_ATT, null, cv)
                }
                durable = NoteVault.publishOrThrow(db, id)
                db.setTransactionSuccessful()
                return@synchronized id
            } finally {
                endTransaction(db, item.uid, durable)
            }
        }

    // ---------------- 身份 / 附件 / 索引重建（NoteVault 的读写口） ----------------

    /** attachments 表的一行。[id] 只用于读；写入时的身份是 [uid] */
    data class Att(
        val id: Long,
        val uid: String,
        val noteId: Long,
        val mime: String,
        val path: String,
        val thumb: String,
        val w: Int,
        val h: Int,
        val bytes: Long,
        val sha256: String,
        val createdAt: Long,
    )

    /** 某条笔记的全部附件 */
    fun attachmentsOf(db: SQLiteDatabase, noteId: Long): List<Att> =
        attRows(db, " WHERE note_id=?", arrayOf(noteId.toString()))

    /** 全部附件行（目录导出用；按笔记分组由调用方自己算） */
    fun allAttachments(db: SQLiteDatabase): List<Att> = attRows(db, "", null)

    private fun attRows(db: SQLiteDatabase, where: String, args: Array<String>?): List<Att> {
        val out = ArrayList<Att>()
        db.rawQuery(
            "SELECT id, uid, note_id, mime, path, thumb, w, h, bytes, sha256, created_at " +
                "FROM " + NoteStore.T_ATT + where + " ORDER BY created_at, id",
            args
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Att(
                        id = c.getLong(0),
                        uid = c.getString(1),
                        noteId = c.getLong(2),
                        mime = c.getString(3) ?: "image/jpeg",
                        path = c.getString(4) ?: "",
                        thumb = c.getString(5) ?: "",
                        w = c.getInt(6),
                        h = c.getInt(7),
                        bytes = c.getLong(8),
                        sha256 = c.getString(9) ?: "",
                        createdAt = c.getLong(10),
                    )
                )
            }
        }
        return out
    }

    /** 这个附件 uid 现在挂在哪条笔记上；没有返回 null */
    private fun attOwner(db: SQLiteDatabase, attUid: String): Long? {
        db.rawQuery("SELECT note_id FROM " + NoteStore.T_ATT + " WHERE uid=?", arrayOf(attUid)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /** 索引里全部笔记 id（含归档与回收站：它们同样要有 md） */
    fun ids(db: SQLiteDatabase): List<Long> {
        val out = ArrayList<Long>()
        db.rawQuery("SELECT id FROM " + NoteStore.T_NOTES + " ORDER BY id", null).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** id → uid；行不存在返回 null（不拿空串去写文件） */
    fun uidOf(db: SQLiteDatabase, id: Long): String? {
        db.rawQuery("SELECT uid FROM " + NoteStore.T_NOTES + " WHERE id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** uid → id；不存在返回 -1。uid 是跨设备身份，id 只是本机索引号 */
    fun idOfUid(db: SQLiteDatabase, uid: String): Long {
        db.rawQuery("SELECT id FROM " + NoteStore.T_NOTES + " WHERE uid=?", arrayOf(uid)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else -1L
        }
    }

    /** uid → 完整笔记；不存在返回 null */
    fun byUid(db: SQLiteDatabase, uid: String): Full? {
        val id = idOfUid(db, uid)
        return if (id > 0L) one(db, id) else null
    }

    /**
     * 按 md 的内容写 / 更新一条索引行，返回本地 id。**不发布 md。**
     *
     * 这是 [NoteVault.rebuild] 的写入口：md 是事实源，重建时反过来写库，
     * 所以这里绝不能走 [insert] —— 那会在重建途中把每条笔记重新 publish 一遍，
     * 边读边写同一个 vault。
     *
     * 身份是 uid：已存在就就地更新、保留原 id，桌面快捷方式和 Intent 里的 id 才不会失效。
     *
     * @param localId md 里 `ling.id` 记下的本地 id，只在它空闲时复用；
     *   被别的 uid 占着就退回自增新 id —— 强行占用等于把另一条笔记的 id 抢走。
     */
    fun putRow(db: SQLiteDatabase, item: Note.Item, localId: Long): Long {
        val existing = idOfUid(db, item.uid)
        val id = if (existing > 0L) {
            db.update(NoteStore.T_NOTES, valuesOf(item), "id=?", arrayOf(existing.toString()))
            existing
        } else {
            val wanted = if (localId > 0L && !idTaken(db, localId)) localId else 0L
            val cv = valuesOf(item).apply { if (wanted > 0L) put("id", wanted) }
            db.insertOrThrow(NoteStore.T_NOTES, null, cv)
        }
        syncTags(db, id, item.body, item.updatedAt)
        syncFts(db, id, item.title, item.body, item.ocrText)
        return id
    }

    private fun idTaken(db: SQLiteDatabase, id: Long): Boolean {
        db.rawQuery("SELECT 1 FROM " + NoteStore.T_NOTES + " WHERE id=? LIMIT 1", arrayOf(id.toString())).use { c ->
            return c.moveToFirst()
        }
    }

    /** 整条替换某条笔记的附件行（重建 / 导入用）。身份是附件 uid，id 不复用 */
    fun replaceAttRows(db: SQLiteDatabase, noteId: Long, atts: List<Att>) {
        db.execSQL("DELETE FROM " + NoteStore.T_ATT + " WHERE note_id=?", arrayOf<Any>(noteId))
        atts.forEach { a ->
            val cv = android.content.ContentValues().apply {
                put("uid", a.uid)
                put("note_id", noteId)
                put("mime", a.mime)
                put("path", a.path)
                put("thumb", a.thumb)
                put("w", a.w)
                put("h", a.h)
                put("bytes", a.bytes)
                put("sha256", a.sha256)
                put("created_at", a.createdAt)
            }
            // 同一附件 uid 可能已经在别的笔记名下（导入的冲突副本沿用了原文件的附件身份）。
            // 直接 REPLACE 会把那条笔记的附件行挪走 —— 归一成带笔记 id 的派生 uid，
            // 两条笔记各自指向同一份文件，谁的附件都不会少。
            val owner = attOwner(db, a.uid)
            val uid = if (owner == null || owner == noteId) a.uid else a.uid + ":n" + noteId
            cv.put("uid", uid)
            db.insertWithOnConflict(NoteStore.T_ATT, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 只重建一条笔记的标签与检索（导入用；不发布 md，不开事务） */
    fun reindexOne(db: SQLiteDatabase, id: Long) {
        db.rawQuery(
            "SELECT title, body, ocr_text, updated_at FROM " + NoteStore.T_NOTES + " WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return
            val title = c.getString(0) ?: ""
            val body = c.getString(1) ?: ""
            val ocr = c.getString(2) ?: ""
            val at = c.getLong(3)
            syncTags(db, id, body, at)
            syncFts(db, id, title, body, ocr)
        }
    }

    /**
     * 全量重算标签关联与检索索引（重建索引用）。不发布 md、不开事务 —— 调用方已有事务。
     *
     * @param resetTags true 时先把标签的 use_count / last_used 归零再累计：
     *   笔记删掉之后它的标签计数不会自己减，只做增量的话那条会永远算在头上。
     */
    fun reindexAll(db: SQLiteDatabase, resetTags: Boolean) {
        if (resetTags) db.execSQL("UPDATE " + NoteStore.T_TAGS + " SET use_count=0, last_used=0")
        db.execSQL("DELETE FROM " + NoteStore.T_NOTE_TAGS)
        db.execSQL("DELETE FROM " + NoteStore.T_FTS)
        db.rawQuery("SELECT id, title, body, ocr_text, updated_at FROM " + NoteStore.T_NOTES, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val title = c.getString(1) ?: ""
                val body = c.getString(2) ?: ""
                val ocr = c.getString(3) ?: ""
                syncTags(db, id, body, c.getLong(4))
                syncFts(db, id, title, body, ocr)
            }
        }
    }

    /**
     * 按 uid 直接删索引行，**不做墓碑**。返回是否真的删掉了。
     *
     * 只在 `trash/` 里已经有墓碑时用：md 早就不在库里了，再挪一次只会多出一个重复文件。
     */
    fun dropByUid(db: SQLiteDatabase, uid: String): Boolean {
        val id = idOfUid(db, uid)
        if (id <= 0L) return false
        db.execSQL("DELETE FROM " + NoteStore.T_ATT + " WHERE note_id=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM " + NoteStore.T_NOTE_TAGS + " WHERE note_id=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM " + NoteStore.T_FTS + " WHERE rowid=?", arrayOf<Any>(id))
        db.execSQL("DELETE FROM " + NoteStore.T_NOTES + " WHERE id=? AND uid=?", arrayOf<Any>(id, uid))
        return changedRows(db) > 0
    }

    /** 置顶 / 取消置顶。卡片流长按菜单用 */
    fun setPinned(db: SQLiteDatabase, id: Long, pinned: Boolean) = setFlag(db, id, "pinned", pinned)

    /** 归档 / 取消归档。归档的不进默认列表，但搜索仍能搜到 */
    fun setArchived(db: SQLiteDatabase, id: Long, archived: Boolean) = setFlag(db, id, "archived", archived)

    /**
     * 改一个布尔列。列名只有 pinned / archived 两种可能 —— 调用方是本文件，
     * 不接外部字符串，所以直接拼进 SQL 没有注入面。
     */
    private fun setFlag(db: SQLiteDatabase, id: Long, col: String, on: Boolean): Int =
        synchronized(NoteVault.mutationLock) {
            val uid = uidOf(db, id) ?: return@synchronized 0
            var durable = false
            db.beginTransaction()
            try {
                db.execSQL(
                    "UPDATE " + NoteStore.T_NOTES + " SET " + col + "=?, updated_at=? WHERE id=?",
                    arrayOf<Any>(if (on) 1 else 0, System.currentTimeMillis(), id)
                )
                val n = changedRows(db)
                if (n > 0) durable = NoteVault.publishOrThrow(db, id)
                db.setTransactionSuccessful()
                return@synchronized n
            } finally {
                endTransaction(db, uid, durable)
            }
        }

    fun rebuildFts(db: SQLiteDatabase): Int {
        var n = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM " + NoteStore.T_FTS)
            db.rawQuery(
                "SELECT id, title, body, ocr_text FROM " + NoteStore.T_NOTES, null
            ).use { c ->
                while (c.moveToNext()) {
                    syncFts(db, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
                    n++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }
    // ---------------- 内部：标签与检索同步 ----------------

    /**
     * 从 body 重建这条笔记的标签关联。
     *
     * 先删后插（而不是增量 diff）：正文一变，标签集合可能整个换掉，
     * diff 反而容易漏。这里量很小，全删重插最简单也最不会错。
     */
    private fun syncTags(db: SQLiteDatabase, noteId: Long, body: String, now: Long) {
        db.execSQL("DELETE FROM " + NoteStore.T_NOTE_TAGS + " WHERE note_id=?", arrayOf<Any>(noteId))
        NoteSyntax.tags(body).forEach { name ->
            val tagId = tagId(db, name)
            db.execSQL(
                "INSERT OR IGNORE INTO " + NoteStore.T_NOTE_TAGS + " (note_id, tag_id) VALUES (?, ?)",
                arrayOf<Any>(noteId, tagId)
            )
            db.execSQL(
                "UPDATE " + NoteStore.T_TAGS + " SET use_count=use_count+1, last_used=? WHERE id=?",
                arrayOf<Any>(now, tagId)
            )
        }
    }

    /** 取标签 id，没有就建 */
    private fun tagId(db: SQLiteDatabase, name: String): Long {
        db.rawQuery("SELECT id FROM " + NoteStore.T_TAGS + " WHERE name=?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        val cv = android.content.ContentValues().apply {
            put("name", name)
            put("use_count", 0)
            put("last_used", 0L)
        }
        return db.insertOrThrow(NoteStore.T_TAGS, null, cv)
    }

    /**
     * 同步全文索引。
     *
     * 中文先切二元组再写，否则 FTS4 会把整句当一个词，搜不到。
     *
     * ## rowid 必须用显式 SQL 指定
     * 曾经写成 `ContentValues().put("rowid", noteId)` 再 db.insert —— 那个 rowid **不生效**：
     * FTS4 是虚拟表，没有真实的 rowid 列，insert() 会忽略它并自己分配编号。
     * 结果是 FTS 里是 4,5,6…，notes 里是 199,200,201…，两套编号完全错开。
     * 而 search() 最后一步是 `feed(...).filter { it.id in ftsIds }`，
     * 两个集合永远没有交集，于是**搜索恒返回 0 条**，且不报任何错。
     *
     * 真机实测：MATCH 本身能命中 100 条（索引没坏），但合并后是 0 ——
     * 这种「每一段都对、合起来全错」的问题，只有把中间值打出来才看得见。
     */
    private fun syncFts(db: SQLiteDatabase, noteId: Long, title: String, body: String, ocr: String) {
        db.execSQL("DELETE FROM " + NoteStore.T_FTS + " WHERE rowid=?", arrayOf<Any>(noteId))
        // 显式 INSERT 带 rowid。注意 rowid 必须**直接拼进 SQL**，不能走绑定参数：
        // 实测 "VALUES (?,?,?,?,?)" 里第一个 ? 会被 FTS4 的虚拟表接口忽略，
        // 它会自己分配一个自增 rowid，于是编号又错开了（4,5,6 vs 199,200,201）。
        // noteId 是 Long，拼进去没有注入面。
        db.execSQL(
            "INSERT INTO " + NoteStore.T_FTS + " (rowid, t_title, t_body, t_ocr, t_tags) VALUES (" +
                noteId + ",?,?,?,?)",
            arrayOf<Any>(
                bigrams(title),
                bigrams(body),
                bigrams(ocr),
                bigrams(NoteSyntax.tags(body).joinToString(" ")),
            )
        )
    }

    /**
     * 中文切二元组：把「导数的定义」切成「导数 数的 的定义」。
     *
     * 和 KnowledgeSearch.tokenize 同一个口径 —— 两处检索行为必须一致，
     * 否则「知识卡搜得到、笔记搜不到」这种问题查起来非常费劲。
     */
    fun bigrams(s: String): String {
        val clean = s.replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        val parts = ArrayList<String>()
        clean.split(" ").forEach { word ->
            if (word.isEmpty()) return@forEach
            if (word.length == 1) { parts.add(word); return@forEach }
            for (i in 0 until word.length - 1) parts.add(word.substring(i, i + 2))
        }
        return parts.joinToString(" ")
    }
}
