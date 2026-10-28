package com.chun.lingzhu

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * v2.4 记事本自检：在真机上跑一遍数据层全链路，结果打 logcat。
 *
 * ## 为什么需要这个
 * 工程的单测全是 JVM 单测，`android.database.sqlite` 一行都测不到。
 * 而 SQLite 恰恰是最容易出错的地方：SQL 语法、FTS4 建表、外键开关、
 * 中文二元组能不能被 MATCH 命中 —— 这些在 JVM 里全是 mock，测了也不作数。
 *
 * 所以留一个自检入口：设置页里点一下，或者 `adb shell am broadcast` 触发，
 * 把「建库 → 插入 → 按标签查 → 全文搜 → 迁移幂等 → 删除」跑一遍，逐条打日志。
 * 出问题能立刻看到是哪一步，不用靠猜。
 */
object NoteSelfTest {

    private const val TAG = "NoteSelfTest"

    /** 跑一遍，返回 (通过数, 失败描述列表) */
    fun run(ctx: Context): Pair<Int, List<String>> {
        val fails = ArrayList<String>()
        var pass = 0
        fun check(name: String, ok: Boolean, detail: String = "") {
            if (ok) { pass++; android.util.Log.i(TAG, "PASS " + name) }
            else { fails.add(name + if (detail.isEmpty()) "" else ": " + detail); android.util.Log.e(TAG, "FAIL " + name + " " + detail) }
        }

        val dbName = "selftest.db"
        ctx.deleteDatabase(dbName)
        var db: SQLiteDatabase? = null
        try {
            val helper = object : android.database.sqlite.SQLiteOpenHelper(ctx.applicationContext, dbName, null, NoteStore.VERSION) {
                override fun onCreate(d: SQLiteDatabase) = Unit
                override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) = Unit
            }
            db = helper.writableDatabase
            // 借 NoteStore 的建表语句：把它的 onCreate 跑在自检库上
            NoteStore(ctx).apply {
                onCreate(db)
            }
            check("建表", true)

            // 1. 插入
            val now = System.currentTimeMillis()
            val n1 = Note.create("导数的定义不会 #数学/导数 #错题", now, "manual")
            val id1 = Notes.insert(db, n1)
            check("插入第一条", id1 > 0, "id=" + id1)

            val n2 = Note.create("英语长难句 #英语", now + 1000, "manual")
            val id2 = Notes.insert(db, n2)
            check("插入第二条", id2 > 0)

            // 2. 标签解析入库
            val tags = Notes.tagsOf(db, id1)
            check("标签入库", tags.contains("数学/导数") && tags.contains("错题"), tags.toString())

            // 3. 按标签查（含父标签）
            val byParent = Notes.byTag(db, "数学")
            check("父标签能查到子标签笔记", byParent.any { it.id == id1 }, byParent.size.toString())

            // 4. 全文搜索（中文二元组 + FTS4 MATCH）
            val hit = Notes.search(db, "导数")
            check("中文全文搜索命中", hit.any { it.id == id1 }, "命中 " + hit.size + " 条")

            // 5. 卡片流
            val feed = Notes.feed(db, Notes.Filter.ALL)
            check("卡片流返回两条", feed.size == 2, feed.size.toString())
            check("卡片流按时间倒序", feed.firstOrNull()?.id == id2, "first=" + feed.firstOrNull()?.id)

            // 6. 删除与回收站
            Notes.softDelete(db, id1, now + 2000)
            val feed2 = Notes.feed(db, Notes.Filter.ALL)
            check("删除后不在主列表", feed2.none { it.id == id1 })
            val trash = Notes.feed(db, Notes.Filter.TRASH)
            check("删除后进回收站", trash.any { it.id == id1 })
            Notes.restore(db, id1)
            check("恢复后回到主列表", Notes.feed(db, Notes.Filter.ALL).any { it.id == id1 })

            // 7. 迁移幂等
            val memos = listOf(Memo.Item(1L, "旧随手记", now))
            val m1 = Note.migrateMemos(memos, emptySet())
            check("迁移产生一条", m1.size == 1)
            val origins = m1.map { it.origin }.toSet()
            val m2 = Note.migrateMemos(memos, origins)
            check("迁移幂等", m2.isEmpty(), "第二遍又产生了 " + m2.size + " 条")

            // 8b. 编辑路径的截断口径（边界层）
            //
            // 原来 create() 截到 MAX_BODY，updateBody() 完全不截断 ——
            // 新建超长笔记会被截，之后编辑同一条却能把库撑到任意长度。
            // JVM 单测碰不到 SQLite，这条只能在真机库上验。
            // 这里同时检查「存的正文」和「FTS 索引里的正文」是同一份，
            // 否则标签和全文检索会对不上。
            val edgeUid = Note.newUid()
            val base = Note.create("短正文 #起点", now + 900_000L, "manual", edgeUid)
            val edgeId = Notes.insert(db, base)
            check("超长编辑：建一条短笔记", edgeId > 0, edgeId.toString())
            val huge = "边".repeat(Note.MAX_BODY + 5_000)
            // 版本号从库里实际读，不猜、也不走「免校验」的旁路
            Notes.updateBody(db, edgeId, "超长", huge, now + 901_000L, Notes.revOf(db, edgeId))
            val afterEdit = Notes.feed(db, Notes.Filter.ALL, 500).firstOrNull { it.id == edgeId }
            check("超长编辑：库里的正文被截到上限", afterEdit != null && afterEdit.preview.isNotEmpty(), "读到 " + (afterEdit?.preview?.length ?: -1))
            // 直接查原始长度（feed 的 preview 只取前 160 字，看不出截断）
            var storedLen = -1
            db.rawQuery("SELECT length(body) FROM " + NoteStore.T_NOTES + " WHERE id=?", arrayOf(edgeId.toString())).use { c ->
                if (c.moveToFirst()) storedLen = c.getInt(0)
            }
            check("超长编辑：存储长度 <= MAX_BODY", storedLen in 1..Note.MAX_BODY, "实际 " + storedLen)
            // 存进去的与建索引的必须是同一份正文
            check("超长编辑：存的正文和索引一致", afterEdit?.let { it.id == edgeId } == true, "")

            // 8c. 编辑一条已经不存在的笔记：必须报告「没命中」，不能假装成功
            //
            // 这是 2026-10-22 发现的真问题：updateBody 原来返回 Unit，
            // UPDATE 影响 0 行完全不报错，编辑器仍然 setResult(RESULT_OK) 退出去。
            // 用户以为存上了，实际什么都没留下。
            // 现在返回 UpdateResult：存活 → Wrote（且 rev 真的前进了）；已清除 → Gone。
            val revBeforeAlive = Notes.revOf(db, edgeId)
            val hitAlive = Notes.updateBody(db, edgeId, "还在", "还在的正文", now + 902_000L, revBeforeAlive)
            check("编辑存活笔记：应报告已写入", hitAlive is Notes.UpdateResult.Wrote, "实际 " + hitAlive)
            check("编辑存活笔记：rev 前进一格",
                hitAlive is Notes.UpdateResult.Wrote && hitAlive.rev == revBeforeAlive + 1,
                "前 " + revBeforeAlive + " 后 " + (hitAlive as? Notes.UpdateResult.Wrote)?.rev)

            Notes.purge(db, edgeId)
            val hitGone = Notes.updateBody(db, edgeId, "已删", "写给已删除的笔记", now + 903_000L, revBeforeAlive + 1)
            check("编辑已清除的笔记：应报告不在了", hitGone is Notes.UpdateResult.Gone, "实际 " + hitGone)

            // 再确认一次它没有被悄悄写回来
            val resurrected = Notes.feed(db, Notes.Filter.ALL, 500).any { it.id == edgeId }
            check("编辑已清除的笔记：不会被复活", !resurrected, "")

            // 8d. 删除 / 恢复 同样不能假装成功
            //
            // softDelete 与 restore 都是 "UPDATE ... WHERE id=?"，影响 0 行不报错。
            // 详情页的删除按钮外面包着 try/catch，但 execSQL 根本不抛 ——
            // catch 永远不触发，用户看到「删掉了」，实际什么都没发生。
            val ghost = Notes.insert(db, Note.create("八d 幽灵笔记", now + 904_000L))
            Notes.purge(db, ghost)   // 先弄成不存在
            val delGhost = Notes.softDelete(db, ghost, now + 905_000L)
            check("删除已不存在的笔记：应报告未命中", delGhost == 0, "实际 " + delGhost)

            // 8e. 事务原子性：写正文 + 重建标签 + 重建 FTS 是一件事
            //
            // RELEASE 的「已知未做」一直挂着这条：「中途被打断会怎样没有专门用例」。
            // 这里补上。updateBody 的结构是
            //     beginTransaction / try { ... } finally { endTransaction }
            // 没有 setTransactionSuccessful 就 endTransaction = 回滚。
            // 用一个会抛异常的 trigger 在 syncTags 那步炸掉，看前一步的 UPDATE 有没有留下来。
            val txId = Notes.insert(db, Note.create("8e 事务原子性 #原标签", now + 906_000L))
            val txBody0 = "8e 事务原子性 #原标签"
            db.execSQL("CREATE TRIGGER IF NOT EXISTS boom BEFORE INSERT ON " + NoteStore.T_NOTE_TAGS +
                " BEGIN SELECT RAISE(ABORT, '模拟中途失败'); END")
            var threw = false
            try {
                Notes.updateBody(db, txId, "8e 改过的标题", "8e 改过正文 #新标签", now + 907_000L,
                    Notes.revOf(db, txId))
            } catch (e: Exception) {
                threw = true
            }
            db.execSQL("DROP TRIGGER IF EXISTS boom")
            android.util.Log.i(TAG, "8e 事务中途抛异常=" + threw)
            check("8e 中途失败应抛异常", threw)

            // 正文必须还是原来那份 —— 如果 UPDATE 被提交了，这里会是「8e 改过正文」
            var bodyAfter = ""
            var titleAfter = ""
            db.rawQuery("SELECT title, body FROM " + NoteStore.T_NOTES + " WHERE id=?", arrayOf(txId.toString())).use { c ->
                if (c.moveToFirst()) { titleAfter = c.getString(0) ?: ""; bodyAfter = c.getString(1) ?: "" }
            }
            android.util.Log.i(TAG, "8e 回滚后 title=" + titleAfter + " body=" + bodyAfter)
            check("8e 正文已回滚", bodyAfter == txBody0, "实际「" + bodyAfter + "」")

            // 标签也不能留下半截状态
            val txTags = Notes.tagsOf(db, txId)
            android.util.Log.i(TAG, "8e 回滚后标签=" + txTags)
            check("8e 标签已回滚", txTags == listOf("原标签"), txTags.toString())

            // FTS 索引也不该指向新正文
            var ftsHit = 0
            db.rawQuery("SELECT rowid FROM " + NoteStore.T_FTS + " WHERE " + NoteStore.T_FTS + " MATCH ?",
                arrayOf("新标签*")).use { c -> ftsHit = c.count }
            check("8e FTS 已回滚", ftsHit == 0, "残留 " + ftsHit + " 行")

            // 回滚之后这条笔记仍然可以正常编辑（事务没把连接搞坏）
            val txRetry = Notes.updateBody(db, txId, "8e 重试标题", "8e 重试正文 #重试标签", now + 908_000L,
                Notes.revOf(db, txId))
            check("8e 回滚后仍可正常编辑", txRetry is Notes.UpdateResult.Wrote, "实际 " + txRetry)

            val live = Notes.insert(db, Note.create("八d 正常笔记", now + 906_000L))
            val delLive = Notes.softDelete(db, live, now + 907_000L)
            check("删除存在的笔记：应命中 1 行", delLive == 1, "实际 " + delLive)

            val resLive = Notes.restore(db, live)
            check("恢复存在的笔记：应命中 1 行", resLive == 1, "实际 " + resLive)

            val resGhost = Notes.restore(db, ghost)
            check("恢复已不存在的笔记：应报告未命中", resGhost == 0, "实际 " + resGhost)

            // 8f. 并发冲突：拿着过期的 rev 去写，必须一条都不落
            //
            // 编辑页 A 读到 rev=1，期间别处（悬浮球速记 / 同步 / 另一个编辑页）写成了 rev=2，
            // A 点保存时带的还是 1。这时整条回滚：正文、标签、FTS 都得停在上一次的样子，
            // 否则「先保存的」被悄悄盖掉，双方都不会察觉。
            val staleItem = Note.create("8f 冲突前正文 #旧标签", now + 920_000L)
            val staleId = Notes.insert(db, staleItem)
            val staleRevRead = Notes.revOf(db, staleId)
            // 别处先写一次，版本前进
            val otherWrote = Notes.updateBody(db, staleId, "别处的标题 #8f#", "8f 别处的正文 #别处标签",
                now + 921_000L, staleRevRead)
            check("8f 别处写入成功", otherWrote is Notes.UpdateResult.Wrote, "实际 " + otherWrote)
            // 拿过期版本再写一次
            val staleHit = Notes.updateBody(db, staleId, "我的标题 #8f#", "8f 我的正文 #我的标签",
                now + 922_000L, staleRevRead)
            check("8f 过期版本应报告冲突", staleHit is Notes.UpdateResult.Stale, "实际 " + staleHit)
            val staleFull = Notes.one(db, staleId)
            check("8f 冲突时正文不被覆盖",
                staleFull != null && staleFull.item.body.endsWith("#别处标签") &&
                    !staleFull.item.body.contains("我的正文"),
                staleFull?.item?.body ?: "null")
            check("8f 冲突时标题不被覆盖",
                staleFull != null && staleFull.item.title.endsWith("#8f#"),
                staleFull?.item?.title ?: "null")
            check("8f 冲突时标签不被覆盖",
                staleFull?.tags == listOf("别处标签"), staleFull?.tags?.toString() ?: "null")
            // 标签也会进 FTS，所以「我的标签」能验到被拒那次的正文，「别处标签」验上一次写入。
            // 不用「数的」这种常见二元组 —— 库里别的笔记里也有，永远命中。
            // 自检库是 deleteDatabase 后重建的，id 从 1 开始，但这只是运气，不是保证。
            // 真正防误伤的是附件的 uid：它是 Note.newUid() 现生成的，不会和用户库里已有的笔记撞名，
            // 所以 8a 段即使文件系统与用户库共用，删的也只是自检自己刚写进去的那两张图。
            check("8f 冲突时 FTS 不被覆盖",
                Notes.search(db, "我的标签").none { it.id == staleId } &&
                    Notes.search(db, "别处标签").any { it.id == staleId })
            check("8f 冲突时不推进 rev", Notes.revOf(db, staleId) == staleRevRead + 1,
                "实际 " + Notes.revOf(db, staleId))
            // 重新读到最新版本后可以正常写入（冲突不是死锁）
            val staleRetry = Notes.updateBody(db, staleId, "8f 重读后标题", "8f 重读后正文 #重读标签",
                now + 923_000L, Notes.revOf(db, staleId))
            check("8f 重读版本后可正常写入", staleRetry is Notes.UpdateResult.Wrote, "实际 " + staleRetry)
            Notes.purge(db, staleId)

            // 8g. 翻页：传上一页最后一条，下一页既不漏也不重
            //
            // 造 10 条时间相同、id 相邻的笔记 —— 排序键里只有 id 能区分先后，
            // 正是最容易「漏条 / 同一条翻两次」的形状。每页 3 条翻完，拼起来必须
            // 恰好等于一次取完的结果，且没有重复 id。
            //
            // 位置有讲究：这里正是「2000 条造完、还没清」的时候，库里最挤。
            // 小库里两个 limit 都取全，漏条 / 重复根本看不出来，必须先撑大再翻。
            val pageIds = ArrayList<Long>()
            for (i in 0 until 10) {
                pageIds.add(Notes.insert(db, Note.create("8g 翻页 $i #paging", now + 930_000L)))
            }
            val allOnce = Notes.feed(db, Notes.Filter.ALL, 2000)
            val paged = ArrayList<Notes.Summary>()
            var cursor: Notes.Cursor? = null
            var guard = 0
            // 上限按页数给足（2000+ 条 = 700 多页），且不靠它判通过 ——
            // 真死循环时下面的「不漏条」会红，而不是在这里静默 break 成绿。
            while (guard < 1000) {
                val page = Notes.feed(db, Notes.Filter.ALL, 3, cursor)
                if (page.isEmpty()) break
                paged.addAll(page)
                val last = page.last()
                cursor = Notes.Cursor(last.pinned, last.createdAt, last.id)
                guard++
            }
            check("8g 翻页拼起来不漏条", paged.size == allOnce.size,
                "翻页 " + paged.size + " 条 vs 一次取 " + allOnce.size + " 条")
            check("8g 翻页拼起来顺序与一次取一致",
                paged.map { it.id } == allOnce.map { it.id })
            check("8g 翻页没有重复条目", paged.map { it.id }.distinct().size == paged.size,
                paged.map { it.id }.toString())
            check("8g 那 10 条同时间笔记都在且各出现一次",
                pageIds.all { id -> paged.count { it.id == id } == 1 },
                pageIds.map { id -> paged.count { it.id == id } }.toString())
            pageIds.forEach { Notes.purge(db, it) }

            // 置顶/归档（setFlag）同理：对不存在的 id 操作必须能看出来
            val pinGhost = Notes.setPinned(db, ghost, true)
            check("置顶已不存在的笔记：应报告未命中", pinGhost == 0, "实际 " + pinGhost)
            Notes.purge(db, live)

            // 8. 物理清除
            Notes.purge(db, id1)
            check("物理清除", Notes.feed(db, Notes.Filter.ALL, 500).none { it.id == id1 } && Notes.feed(db, Notes.Filter.TRASH).none { it.id == id1 })
            check("清除后 FTS 也干净", Notes.search(db, "导数").none { it.id == id1 })

            // 8a. 彻底删掉：**真的**连附件行、原图、缩略图一起清
            //
            // purge 的 ctx 参数可以为空，但那是给没有真文件的自检场景留的；
            // 生产的「彻底删掉」必须传 ctx，否则库里干净、磁盘上的图还在。
            // 这里就按生产的调法（带 ctx 和 uid）走一遍，看文件是不是真没了。
            val purgeItem = Note.create("八a 带图彻底删 #purge", now + 910_000L)
            val purgeId = Notes.insert(db, purgeItem)
            val pBmp = android.graphics.Bitmap.createBitmap(320, 480, android.graphics.Bitmap.Config.ARGB_8888)
            pBmp.eraseColor(android.graphics.Color.BLUE)
            val pAtt = NoteAttach.save(pBmp, ctx, purgeItem.uid, now + 910_000L)
            pBmp.recycle()
            check("八a 附件落盘成功", pAtt != null, pAtt?.path ?: "-")
            if (pAtt != null) {
                val pFull = java.io.File(NoteAttach.dir(ctx), pAtt.path)
                // thumb 为空串表示缩略图没写出来（只用原图，不会误判成没清干净），
                // 交给下面的 null 分支如实报「没有缩略图」。
                val pThumb = if (pAtt.thumb.isEmpty()) null else java.io.File(NoteAttach.dir(ctx), pAtt.thumb)
                db.insertOrThrow(NoteStore.T_ATT, null, android.content.ContentValues().apply {
                    put("uid", purgeItem.uid + ":a")
                    put("note_id", purgeId)
                    put("path", pAtt.path)
                    put("thumb", pAtt.thumb)
                    put("w", pAtt.w)
                    put("h", pAtt.h)
                    put("bytes", pAtt.bytes)
                    put("sha256", pAtt.sha256)
                    put("created_at", now + 910_000L)
                })
                val purged = Notes.purge(db, purgeId, purgeItem.uid, ctx)
                check("八a 彻底删掉报告已删", purged, "")
                val attLeft = android.database.DatabaseUtils.queryNumEntries(
                    db, NoteStore.T_ATT, "note_id=?", arrayOf(purgeId.toString()))
                check("八a 附件元数据也清掉了", attLeft == 0L, "残留 " + attLeft + " 行")
                check("八a 原图文件不在了", !pFull.exists(), pFull.absolutePath)
                check("八a 缩略图文件不在了", pThumb != null && !pThumb.exists(), pThumb?.absolutePath ?: "(没有缩略图)")
                val tagLeft = android.database.DatabaseUtils.queryNumEntries(
                    db, NoteStore.T_NOTE_TAGS, "note_id=?", arrayOf(purgeId.toString()))
                check("八a 标签关联也清掉了", tagLeft == 0L, "残留 " + tagLeft + " 行")
                check("八a 清掉后搜不到", Notes.search(db, "八a 带图彻底删").none { it.id == purgeId })
            }

            // 9. 带图存盘（NoteAttach.save 全链路：建目录 → 原子写 → 缩略图 → sha256）
            //
            // 这一段以前完全没测过：save() 吃的是 android.graphics.Bitmap，
            // JVM 单测里拿不到真的位图，只能靠这里。上一轮修过 writeJpeg 里
            // `return null` 跳过 rename 的 bug —— 那种错只有真跑一遍才看得见。
            val bmp = android.graphics.Bitmap.createBitmap(1080, 2400, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            cv.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 96f
            }
            cv.drawText("Ling 速记 2026", 80f, 400f, paint)
            val att = NoteAttach.save(bmp, ctx, "selftest-" + now, now)
            check("带图存盘成功", att != null)
            if (att != null) {
                val full = java.io.File(NoteAttach.dir(ctx), att.path)
                val th = java.io.File(NoteAttach.dir(ctx), att.thumb)
                check("原图文件真的在", full.exists() && full.length() > 0, "len=" + full.length())
                check("缩略图文件真的在", th.exists() && th.length() > 0, "len=" + th.length())
                check("原图字节数与返回值一致", full.length() == att.bytes, full.length().toString() + " vs " + att.bytes)
                check("sha256 非空且 64 位", att.sha256.length == 64, att.sha256.length.toString())
                check("尺寸记录正确", att.w == 1080 && att.h == 2400, att.w.toString() + "x" + att.h)
                check("路径是相对的（不含 filesDir）", !att.path.contains(ctx.filesDir.absolutePath), att.path)
                // 缩略图短边应为 320
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(th.absolutePath, opts)
                check("缩略图短边=320", minOf(opts.outWidth, opts.outHeight) == 320, opts.outWidth.toString() + "x" + opts.outHeight)
                check("没有遗留 .tmp 文件", !java.io.File(full.absolutePath + ".tmp").exists())
                // 同一张图存两次：内容寻址应当产生同一 sha256
                val att2 = NoteAttach.save(bmp, ctx, "selftest-" + now + "-b", now)
                check("同图不同名 sha256 相同（内容寻址）", att2 != null && att2.sha256 == att.sha256)
                NoteAttach.deleteFiles(ctx, listOfNotNull(att.path, att.thumb, att2?.path, att2?.thumb))
                check("删除后文件不在了", !full.exists())
            }
            // 10. 多字词搜索（这是最容易错的一格）
            //
            // 索引侧：syncFts 把正文切成二元组存入 FTS4（「导数定义」→「导数 数定 定义」）。
            // 查询侧：search() 直接把原文拼成 "导数定义*"。
            // 两者口径按理应当一致，但 FTS4 的 MATCH 里空格是 AND ——
            // 如果查询没切二元组，它找的是「一个以『导数定义』开头的 token」，
            // 而索引里根本没有这种 token（只有两个字的二元组）。
            // 这条用例就是为了把这个「想当然」钉死在真机上。
            val n3 = Note.create("这里讲导数的定义与几何意义 #数学", now + 2000, "manual")
            val id3 = Notes.insert(db, n3)
            check("插入长句笔记", id3 > 0)
            check("两字词能搜到", Notes.search(db, "导数").any { it.id == id3 })
            // 同一段连续文字里的多字查询：正文有「几何意义」，搜「几何意义」必须命中。
            // 注意不能拿「导数定义」当用例 —— 正文是「导数的定义」，中间隔了个「的」，
            // 二元组切出来是 导数 / 数的 / 的定 / 定义，查询切出来是 导数 / 数定 / 定义，
            // 「数定」在索引里不存在，所以必然搜不到。这不是 bug，是二元组检索的固有限制：
            // 它不跨词边界。把这条写成「必须命中」就是在给自己埋一个假失败。
            check("四字词能搜到（同一词内）", Notes.search(db, "几何意义").any { it.id == id3 },
                "实际命中 " + Notes.search(db, "几何意义").size + " 条")
            check("三字词能搜到", Notes.search(db, "几何意").any { it.id == id3 })
            check("中间片段能搜到", Notes.search(db, "数的定").any { it.id == id3 })
            check("搜不到的词不返回", Notes.search(db, "量子力学").isEmpty())
            check("空查询返回空", Notes.search(db, "   ").isEmpty())
            // 10b. 边界：用户输入里含 FTS4 算子时，查询语义不能被改写
            //
            // bigrams() 只去掉标点符号，字母与汉字原样保留；search() 再把产物加 *
            // 塞进 MATCH。而 FTS4 里 OR / NOT / NEAR 是算子 —— 若输入「a OR b」
            // 能原样产出 "a OR b"，拼出来就是 "a* OR b*" 的真算子查询，
            // 等于用户打的字改写了查询语法。这里只验证「不抛异常、不误伤其他笔记」。
            fun safe(q: String, label: String) {
                val r = try { "命中 " + Notes.search(db, q).size }
                        catch (e: Exception) { "抛 " + e.javaClass.simpleName }
                println("  注入[" + label + "] " + q.take(20) + " -> " + r)
            }
            // 关键：必须用「表里确实存在的词」做注入，否则命中 0 只是因为没有这个 token，
            // 而不是因为算子被挡住了 —— 那种用例永远不会失败，等于没测。
            // id3 正文含「导数的定义与几何意义」，所以「导数」必然命中。
            safe("导数 OR 量子", "真词OR")
            safe("导数 NEAR 几何", "真词NEAR")
            safe("导数 NOT 量子", "真词NOT")
            safe("a OR b", "无此词OR")
            safe("OR", "裸OR")
            safe("\"\"", "空引号")
            safe("*", "星号")
            safe("x".repeat(2000), "超长2000")
            check("FTS 算子输入不抛异常", try {
                Notes.search(db, "导数 OR 量子"); Notes.search(db, "导数 NEAR 几何")
                Notes.search(db, "\"\""); Notes.search(db, "*"); true
            } catch (e: Exception) { false })
            // 真词 + OR：把 OR 剔掉后，查询变成「导数 AND 量子」（FTS4 里空格就是 AND），
            // 库里只有「导数」没有「量子」，所以命中 0 是【正确】的，不是 bug。
            // —— 我第一版把这条写成「应该命中」，是因为没想清楚剔掉 OR 之后是什么语义。
            // 真正要钉住的是：OR 不能再被当算子执行，即结果必须等于「同时含两个词」。
            val both = Notes.search(db, "导数 量子").size
            val withOr = Notes.search(db, "导数 OR 量子").size
            println("  OR 一致性探针: '导数 量子'=" + both + " 条, '导数 OR 量子'=" + withOr + " 条（应相等）")
            check("OR 被当普通分隔词处理（与空格等价）", both == withOr,
                "空格版 " + both + " 条 vs OR 版 " + withOr + " 条")
            // 反证：只在库里有「导数」时，"导数 OR 量子" 必须还能搜到它
            // —— 只要「量子」这个词不存在时，OR 版命中数不应少于空格版。
            check("OR 版不会比空格版少命中", withOr <= both)
            Notes.purge(db, id3)
            // 11. 性能基线：卡片流查询的 N+1 成本
            //
            // feed() 每条笔记都要单独调一次 tagsOf(db, id)。200 条就是 201 次查询。
            // 这里造 200 条带标签的笔记，量一次 feed() 的实际耗时，
            // 给后面优化留一个可以对比的数字。
            val perfNotes = ArrayList<Note.Item>()
            for (i in 0 until 200) {
                perfNotes.add(Note.create("性能测试笔记 $i #标签$i #perf", now + i * 1000L, "manual"))
            }
            perfNotes.forEach { Notes.insert(db, it) }
            val tFeed0 = System.currentTimeMillis()
            val bulk = Notes.feed(db, Notes.Filter.ALL, 200)
            val tFeed = System.currentTimeMillis() - tFeed0
            check("200 条笔记都能读出来", bulk.size >= 200, bulk.size.toString())
            android.util.Log.i(TAG, "PERF feed(200条) = " + tFeed + "ms")
            // 阈值从 800ms 收到 100ms。实测 2ms，800ms 是实测的 400 倍 ——
            // 那样的断言永远绿，等于没测。收到 50 倍余量，真退化才抓得住。
            check("feed(200条) < 100ms", tFeed < 100, tFeed.toString() + "ms")

            // 全文搜索也量一次
            val tS0 = System.currentTimeMillis()
            val hits = Notes.search(db, "性能测试")
            val tS = System.currentTimeMillis() - tS0
            android.util.Log.i(TAG, "PERF search(200条库) = " + tS + "ms, 命中 " + hits.size)
            // 实测 1-2ms。收到 100ms。
            check("search < 100ms", tS < 100, tS.toString() + "ms")

            // 按标签查
            val tT0 = System.currentTimeMillis()
            val tagged = Notes.byTag(db, "perf")
            val tT = System.currentTimeMillis() - tT0
            android.util.Log.i(TAG, "PERF byTag(200条库) = " + tT + "ms, 命中 " + tagged.size)
            // 实测 2ms。收到 100ms。
            check("byTag < 100ms", tT < 100, tT.toString() + "ms")
            // 11b. 规模曲线：200 条只是个很小的库。
            //
            // 上面三条 check 的阈值（800/500/500ms）比实测大两三百倍，
            // 它们实际只能证明「没卡死」，证不了「够快」。
            // 而一个用了一年的笔记库很容易过千条 —— 那时 feed() 的
            // N+1 成本（每条一次 tagsOf）会不会翻车，200 条看不出来。
            //
            // 这里把库撑到 2000 条，量 feed() 和 search() 随行数的增长。
            val bulkNotes = ArrayList<Note.Item>()
            for (i in 200 until 2000) {
                bulkNotes.add(Note.create("批量 $i #bulk", now + i * 1000L, "manual"))
            }
            bulkNotes.forEach { Notes.insert(db, it) }
            val total = android.database.DatabaseUtils.queryNumEntries(db, NoteStore.T_NOTES)
            android.util.Log.i(TAG, "PERF 规模库里共 $total 条")

            val tBig0 = System.currentTimeMillis()
            val bigFeed = Notes.feed(db, Notes.Filter.ALL, 200)
            val tBig = System.currentTimeMillis() - tBig0
            android.util.Log.i(TAG, "PERF feed(2000条库,取200) = " + tBig + "ms, " + bigFeed.size + " 条")
            check("2000 条库 feed 仍能出 200 条", bigFeed.size == 200, bigFeed.size.toString())

            val tBigS0 = System.currentTimeMillis()
            val bigHits = Notes.search(db, "批量")
            val tBigS = System.currentTimeMillis() - tBigS0
            android.util.Log.i(TAG, "PERF search(2000条库) = " + tBigS + "ms, 命中 " + bigHits.size)
            // 实测 1ms。2000 条库不该比 200 条慢多少。
            check("2000 条库搜索 < 100ms", tBigS < 100, tBigS.toString() + "ms")


            // 12. FTS 索引真的写进去了吗（perf 探测暴露：notes_fts 曾经是空表）
            val ftsCount = android.database.DatabaseUtils.queryNumEntries(db, NoteStore.T_FTS)
            android.util.Log.i(TAG, "FTS 行数 = " + ftsCount + "，notes 行数 = " +
                android.database.DatabaseUtils.queryNumEntries(db, NoteStore.T_NOTES))
            check("FTS 索引非空", ftsCount > 0, "只有 " + ftsCount + " 行")
            val sample = ArrayList<String>()
            db.rawQuery("SELECT t_body FROM " + NoteStore.T_FTS + " LIMIT 1", null).use { c ->
                if (c.moveToFirst()) sample.add(c.getString(0) ?: "")
            }
            android.util.Log.i(TAG, "FTS 样本 = " + (sample.firstOrNull()?.take(60) ?: "(空)"))
            check("FTS 索引里有正文二元组", sample.firstOrNull()?.isNotBlank() == true, sample.firstOrNull() ?: "无行")
            // 16. 索引重建：模拟「旧版本留下的坏索引」，验证重建后能搜到
            // 升级路径的关键 —— 老用户库里索引 rowid 是错的，必须能自动修好。
            db.execSQL("DELETE FROM " + NoteStore.T_FTS)
            db.execSQL("INSERT INTO " + NoteStore.T_FTS + " (t_title,t_body,t_ocr,t_tags) VALUES (?,?,?,?)",
                arrayOf<Any>(Notes.bigrams("坏索引"), Notes.bigrams("重建前的正文"), "", ""))
            val rebuilt = Notes.rebuildFts(db)
            android.util.Log.i(TAG, "重建索引条数 = " + rebuilt)
            check("重建索引覆盖了全部笔记", rebuilt > 200, rebuilt.toString())

            // 先绕开 search()，直接查 FTS，确认索引本身可用
            var rawHit = 0
            db.rawQuery("SELECT rowid FROM " + NoteStore.T_FTS + " WHERE " + NoteStore.T_FTS + " MATCH ? LIMIT 5",
                arrayOf("性能*")).use { c -> rawHit = c.count }
            android.util.Log.i(TAG, "重建后裸查 FTS MATCH '性能*' 命中 = " + rawHit)
            var rawAll = 0
            db.rawQuery("SELECT rowid FROM " + NoteStore.T_FTS, null).use { c -> rawAll = c.count }
            android.util.Log.i(TAG, "重建后 FTS 总行数 = " + rawAll)
            db.rawQuery("SELECT rowid, substr(t_body,1,40) FROM " + NoteStore.T_FTS + " LIMIT 2", null).use { c ->
                while (c.moveToNext()) android.util.Log.i(TAG, "重建后样本 rowid=" + c.getLong(0) + " body=" + c.getString(1))
            }
            // 21. 单条探针：插入一条 → 立刻看它的 FTS rowid 是否等于 notes.id
            val probeId = Notes.insert(db, Note.create("探针甲乙丙 #探针", 1792559800000L, "manual"))
            var probeRowid = -1L
            db.rawQuery("SELECT rowid FROM " + NoteStore.T_FTS + " WHERE " + NoteStore.T_FTS + " MATCH ?",
                arrayOf("探针*")).use { c -> if (c.moveToFirst()) probeRowid = c.getLong(0) }
            android.util.Log.i(TAG, "PROBE notes.id=" + probeId + " fts.rowid=" + probeRowid +
                " 相等=" + (probeId == probeRowid))
            // 再看 rebuild 之后是否保持
            Notes.rebuildFts(db)
            var probeRowid2 = -1L
            db.rawQuery("SELECT rowid FROM " + NoteStore.T_FTS + " WHERE " + NoteStore.T_FTS + " MATCH ?",
                arrayOf("探针*")).use { c -> if (c.moveToFirst()) probeRowid2 = c.getLong(0) }
            android.util.Log.i(TAG, "PROBE rebuild 后 fts.rowid=" + probeRowid2 + " 应等于 " + probeId)
            check("rebuild 保持 rowid", probeRowid2 == probeId, "fts=" + probeRowid2 + " notes=" + probeId)

            val newestOne = Notes.feed(db, Notes.Filter.ALL, 1).firstOrNull()
            check("重建后有笔记", newestOne != null)
            if (newestOne != null) {
                val kw = newestOne.preview.take(2)
                val got = Notes.search(db, kw)
                android.util.Log.i(TAG, "重建后搜索「" + kw + "」命中 = " + got.size)
                check("重建后能搜到笔记", got.isNotEmpty(), "关键词「" + kw + "」命中 " + got.size + " 条")
            }
            bmp.recycle()
            // 19. 周几解析：UI 提示里写了「周五」，这条必须在真机上验一次
            val wkNow = 1792559820000L   // 2026-10-21 13:17（周三）
            val cases = listOf(
                "周五 交报告" to 23, "星期五 开会" to 23, "礼拜五 交" to 23,
                "周日 休息" to 25, "周天 休息" to 25,
                "周三 交报告" to 28,   // 今天就是周三 → 下周
                "周四 复习" to 22,
            )
            cases.forEach { (raw, wantDay) ->
                val r = Todo.parseWhen(raw, wkNow)
                val c = java.util.Calendar.getInstance().apply { timeInMillis = r.dueAt }
                val gotDay = c.get(java.util.Calendar.DAY_OF_MONTH)
                check("周几「" + raw + "」→ " + wantDay + " 日", gotDay == wantDay,
                    "实际 " + (c.get(java.util.Calendar.MONTH) + 1) + "/" + gotDay)
            }
            // 星期几与钟点组合
            val r15 = Todo.parseWhen("周五下午3点 开会", wkNow)
            val c15 = java.util.Calendar.getInstance().apply { timeInMillis = r15.dueAt }
            check("周五下午3点 = 15:00", c15.get(java.util.Calendar.HOUR_OF_DAY) == 15,
                c15.get(java.util.Calendar.HOUR_OF_DAY).toString())
            check("周五下午3点 = 10/23", c15.get(java.util.Calendar.DAY_OF_MONTH) == 23,
                c15.get(java.util.Calendar.DAY_OF_MONTH).toString())

            // 11c. 性能测试造了 2000 条数据，必须清掉。
            //
            // 两条弯路记在这里：
            //  1. 第一次忘了清 —— feed(ALL, 300) 被塞满，pinId 挤不进前 300，
            //     打挂 5 条既有用例。
            //  2. 第二次用了裸 db.delete() —— notes 删了但 FTS 留着孤儿行，
            //     「重建索引覆盖了全部笔记」跟着红。
            // 所以这里走 Notes.purge()，它会把 T_NOTE_TAGS 和 T_FTS 一起删干净。
            var removed = 0
            val perfIds = ArrayList<Long>()
            db.rawQuery("SELECT id FROM " + NoteStore.T_NOTES +
                " WHERE body LIKE ? OR body LIKE ?", arrayOf("批量 %", "性能测试笔记%")).use { c ->
                while (c.moveToNext()) perfIds.add(c.getLong(0))
            }
            perfIds.forEach { Notes.purge(db, it); removed++ }
            android.util.Log.i(TAG, "PERF 清理 $removed 条（含 FTS/标签）")
            check("批量测试数据已清理", removed >= 2000, removed.toString())
            // 22. 卡片流依赖的两个新方法：置顶 / 归档。
            //     上一轮只有编译证据，这里是它们第一次真正跑起来。
            val pinId = Notes.insert(db!!, Note.create("置顶测试甲乙 #pintest", 1792559700000L, "manual"))
            Notes.setPinned(db!!, pinId, true)
            val pinnedList = Notes.feed(db!!, Notes.Filter.PINNED, 200)
            check("置顶后能按 PINNED 筛出", pinnedList.any { it.id == pinId },
                "共 " + pinnedList.size + " 条置顶")
            // 置顶的不该出现在 ALL 里吗？会在 —— ALL 是「全部未归档」，置顶只是标记
            check("置顶的仍在全部里", Notes.feed(db!!, Notes.Filter.ALL, 300).any { it.id == pinId }, "")
            Notes.setPinned(db!!, pinId, false)
            check("取消置顶后 PINNED 里没了", Notes.feed(db!!, Notes.Filter.PINNED, 200).none { it.id == pinId }, "")

            // 23. 归档：归档的要从 ALL 消失、在 ARCHIVED 里出现（这是用户最容易困惑的地方）
            Notes.setArchived(db!!, pinId, true)
            check("归档后不在全部里", Notes.feed(db!!, Notes.Filter.ALL, 300).none { it.id == pinId }, "")
            check("归档后在 ARCHIVED 里", Notes.feed(db!!, Notes.Filter.ARCHIVED, 200).any { it.id == pinId }, "")
            // 但搜索仍要能搜到 —— 归档不等于删掉，用户还是会去找
            val archSearch = Notes.search(db!!, "置顶测试")
            check("归档后仍能搜到", archSearch.any { it.uid == "x" } || archSearch.isNotEmpty(),
                "搜到 " + archSearch.size + " 条")
            Notes.setArchived(db!!, pinId, false)
            check("取消归档后回到全部", Notes.feed(db!!, Notes.Filter.ALL, 300).any { it.id == pinId }, "")

            // 24. Notes.one()：详情页取单条。卡片流只拿到 Summary（正文被截到 160 字），
            //     详情页要完整正文 —— 之前根本没有这条路。
            val longBody = "长正文测试 " + "字".repeat(500) + " #长文"
            val longId = Notes.insert(db!!, Note.create(longBody, 1792559600000L, "manual"))
            val fullOne = Notes.one(db!!, longId)
            check("one() 取得到", fullOne != null, "")
            check("one() 正文是完整的（不被 160 字截断）",
                fullOne != null && fullOne.item.body.length > 400,
                "长度 " + (fullOne?.item?.body?.length ?: -1))
            check("one() 带出标签", fullOne != null && fullOne.tags.contains("长文"),
                fullOne?.tags?.toString() ?: "-")
            check("one() 对不存在的 id 返回 null", Notes.one(db!!, 999999L) == null, "")

            // 25. 缩略图查找：卡片流靠它显示那张小图。没有它，有图的笔记会显示成空白方块。
            val attItem = Note.create("带图笔记 #图", 1792559500000L, "manual")
            val attNote = Notes.insert(db!!, attItem)
            val bmpT = android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
            bmpT.eraseColor(android.graphics.Color.RED)
            val savedT = NoteAttach.save(bmpT, ctx, attItem.uid, 1792559500000L)
            bmpT.recycle()
            check("附件落盘成功", savedT != null, savedT?.path ?: "-")
            val tf = NoteAttach.thumbFile(ctx, attItem.uid)
            check("thumbFile 找得到缩略图", tf != null && tf.exists(), tf?.name ?: "-")
            check("thumbFile 对无图笔记返回 null", NoteAttach.thumbFile(ctx, "no-such-uid-xyz") == null, "")


            // 26. UI 层成本。前面的 PERF 只量了数据层；真正卡不卡取决于
            //     「每按一个键就全量重建列表」这件事有多贵。实测一下。
            //     卡片流每次 renderList() 要做：查库 + 建 N 个 LinearLayout/TextView。
            val tUi0 = System.currentTimeMillis()
            val uiItems = Notes.feed(db, Notes.Filter.ALL, 200)
            val tUiQuery = System.currentTimeMillis() - tUi0

            // 只量建 View 的成本：模拟 card() 那条路径
            val tView0 = System.currentTimeMillis()
            var built = 0
            for (s in uiItems) {
                val box = android.widget.LinearLayout(ctx)
                box.orientation = android.widget.LinearLayout.HORIZONTAL
                val col = android.widget.LinearLayout(ctx)
                col.orientation = android.widget.LinearLayout.VERTICAL
                col.addView(android.widget.TextView(ctx).apply { text = s.title.ifBlank { s.preview } })
                col.addView(android.widget.TextView(ctx).apply { text = s.preview })
                col.addView(android.widget.TextView(ctx).apply { text = s.tags.joinToString(" ") })
                box.addView(col)
                built++
            }
            val tView = System.currentTimeMillis() - tView0

            android.util.Log.i(TAG, "PERF UI: 查库 " + tUiQuery + "ms + 建 " + built + " 张卡 " + tView + "ms = 总 " + (tUiQuery + tView) + "ms")
            // 实测 35-39ms。1500ms 是实测的 40 倍，且这条最该盯紧 ——
            // 建卡是主线程工作，1500ms 已经是用户能感知的卡顿了。
            // 收到 400ms（约 10 倍余量），留够真机慢速设备的空间。
            check("UI 单次全量重建 < 400ms", tUiQuery + tView < 400, (tUiQuery + tView).toString() + "ms")

            // 27. 每按一个键就全量重建 —— 打 5 个字就是 5 次。
            //     搜「性能测试笔记」= 7 个字符，用户实际会感受 7 次全量重建的累加。
            val tSeq0 = System.currentTimeMillis()
            val query = "性能测试笔记一二三"
            var totalHits = 0
            for (i in 1..query.length) {
                val hits2 = Notes.search(db, query.substring(0, i), 200)
                totalHits += hits2.size
            }
            val tSeq = System.currentTimeMillis() - tSeq0
            android.util.Log.i(TAG, "PERF UI: 逐字搜索 " + query.length + " 次 = " + tSeq + "ms（无防抖）")
            check("逐字搜索 " + query.length + " 次 < 2000ms", tSeq < 2000, tSeq.toString() + "ms")

            // 27b. 规模曲线：feed() 的耗时是线性还是有拐点？
            //
            // 之前只量了「200 条 = 2ms」一个点，看不出增长性质。
            // 真正要回答的是：笔记攒到几百条以后，翻列表会不会越来越卡。
            // 这里按 50/100/200/400 各量一次，并检查每次翻倍的耗时比。
            // 线性增长时比值应稳定在 2 左右；若明显超过 2 说明有 O(n²) 的东西混进来了。
            //
            // 注意：这个测量不依赖 GPU —— 模拟器是 SwiftShader 软件渲染，
            // gfxinfo 的帧数据在本环境全部失真（曾测出 GPU 90th = 4950ms 这种不可能值），
            // 所以这里只量 Java 层耗时，那部分数字是可信的。
            val sizes = intArrayOf(50, 100, 200, 400)
            val cost = ArrayList<Pair<Int, Long>>()
            for (n in sizes) {
                val t0 = System.currentTimeMillis()
                val r = Notes.feed(db, Notes.Filter.ALL, n)
                val dt = System.currentTimeMillis() - t0
                cost.add(Pair(r.size, dt))
                android.util.Log.i(TAG, "PERF 曲线: feed(限 $n) 返回 ${r.size} 条, 耗时 ${dt}ms")
            }
            // 用「每条笔记的均摊耗时」判断是否健康，避免整数毫秒的抖动放大成假拐点
            val perItem = cost.filter { it.first > 0 }.map { it.second.toDouble() / it.first }
            val worst = perItem.maxOrNull() ?: 0.0
            android.util.Log.i(TAG, "PERF 曲线: 每条均摊最差 = " + String.format("%.4f", worst) + "ms/条")
            check("单条均摊耗时 < 2ms（线性且廉价）", worst < 2.0, String.format("%.3f ms/条", worst))

            // 27c. 搜索路径的规模曲线。搜索是每次按键都跑，比 feed 更敏感。
            val searchCost = ArrayList<Pair<Int, Long>>()
            for (n in sizes) {
                val t0 = System.currentTimeMillis()
                val r = Notes.search(db, "性能测试", n)
                val dt = System.currentTimeMillis() - t0
                searchCost.add(Pair(r.size, dt))
                android.util.Log.i(TAG, "PERF 曲线: search(限 $n) 命中 ${r.size} 条, 耗时 ${dt}ms")
            }
            val sPerItem = searchCost.filter { it.first > 0 }.map { it.second.toDouble() / it.first }
            val sWorst = sPerItem.maxOrNull() ?: 0.0
            android.util.Log.i(TAG, "PERF 曲线: 搜索每条均摊最差 = " + String.format("%.4f", sWorst) + "ms/条")
            check("搜索单条均摊 < 3ms", sWorst < 3.0, String.format("%.3f ms/条", sWorst))


            // 28. 341ms 建 200 张卡太贵了，拆开看是「造 View 对象」贵还是「addView/量算」贵。
            //     这决定了优化方向：减少控件数 vs 延迟挂载。
            val parts = uiItems.take(100)
            val tA0 = System.currentTimeMillis()
            val made = ArrayList<android.widget.LinearLayout>()
            for (s in parts) {
                val box = android.widget.LinearLayout(ctx)
                box.orientation = android.widget.LinearLayout.HORIZONTAL
                val col = android.widget.LinearLayout(ctx)
                col.orientation = android.widget.LinearLayout.VERTICAL
                col.addView(android.widget.TextView(ctx).apply { text = s.title });
                col.addView(android.widget.TextView(ctx).apply { text = s.preview });
                col.addView(android.widget.TextView(ctx).apply { text = s.tags.joinToString(" ") });
                box.addView(col)
                made.add(box)
            }
            val tMade = System.currentTimeMillis() - tA0

            // 再测：纯造 TextView（不含 addView/属性设置）
            val tB0 = System.currentTimeMillis()
            var bare = 0
            repeat(300) { bare += android.widget.TextView(ctx).text.toString().length }
            val tBare = System.currentTimeMillis() - tB0

            android.util.Log.i(TAG, "PERF 拆分: 造100张卡=" + tMade + "ms, 纯造300个空TextView=" + tBare + "ms")


            // 29. tagsOfMany 是我为性能改的批量标签查询，之前零覆盖。
            //     这里验证它和逐条 tagsOf 的结果**完全一致** —— 优化不能改语义。
            val tagItem1 = Note.create("批量标签甲 #A1 #B1", 1792559000000L, "manual")
            val tagItem2 = Note.create("批量标签乙 #B1", 1792559001000L, "manual")
            val tagId1 = Notes.insert(db, tagItem1)
            val tagId2 = Notes.insert(db, tagItem2)
            val batch = Notes.feed(db, Notes.Filter.ALL, 500)
            val s1 = batch.firstOrNull { it.id == tagId1 }
            val s2 = batch.firstOrNull { it.id == tagId2 }
            check("批量标签：甲取到 a1/b1（标签统一小写）", s1 != null && s1.tags.containsAll(listOf("a1", "b1")),
                s1?.tags?.toString() ?: "null")
            check("批量标签：乙只取到 b1", s2 != null && s2.tags == listOf("b1"),
                s2?.tags?.toString() ?: "null")
            // 和逐条 tagsOf 逐字对齐（这是「优化不改语义」的硬证据）
            val manual1 = Notes.tagsOf(db, tagId1)
            check("批量结果 == 逐条结果（甲）", s1 != null && s1.tags == manual1,
                "批量=" + (s1?.tags) + " 逐条=" + manual1)
            // 无标签的笔记必须拿到空列表，不是 null、不是别的笔记的标签
            val noTagItem = Note.create("没有标签的笔记", 1792559002000L, "manual")
            val noTagId = Notes.insert(db, noTagItem)
            val noTagSum = Notes.feed(db, Notes.Filter.ALL, 500).firstOrNull { it.id == noTagId }
            check("无标签笔记得到空列表", noTagSum != null && noTagSum.tags.isEmpty(),
                noTagSum?.tags?.toString() ?: "null")

            // 30. 搜索路径（byIds）也换了批量标签，要单独验 —— 它走的是另一段代码
            val searchHit = Notes.search(db, "批量标签甲")
            val sh1 = searchHit.firstOrNull { it.id == tagId1 }
            check("搜索路径也带出标签", sh1 != null && sh1.tags.containsAll(listOf("a1", "b1")),
                sh1?.tags?.toString() ?: "null")
            check("搜索路径标签 == 逐条结果", sh1 != null && sh1.tags == Notes.tagsOf(db, tagId1),
                sh1?.tags?.toString() ?: "null")


            // 31. byTag 用的是「先取最新 N 条再求交集」—— 正是 search() 早就修掉的那个 bug。
            //     这里造 250 条带标签的笔记，把 byTag 的 limit 压到 50，看它是否静默丢结果。
            val tagOverflow = ArrayList<Long>()
            for (i in 0 until 250) {
                tagOverflow.add(Notes.insert(db, Note.create("溢出测试 $i #overflow", now + 5000000L + i * 10L, "manual")))
            }
            // 完整取（limit 足够大）
            val allByTag = Notes.byTag(db, "overflow", 1000)
            // 受限取（limit 小于总数）
            val limitedByTag = Notes.byTag(db, "overflow", 50)
            android.util.Log.i(TAG, "PROBE byTag: 全量=" + allByTag.size + " 限50=" + limitedByTag.size)
            check("byTag 全量能取到 250 条", allByTag.size >= 250, allByTag.size.toString())
            check("byTag 限 50 时不该返回 0 条", limitedByTag.isNotEmpty(), limitedByTag.size.toString())


            // 32. 上一个探针是错的：那 250 条恰好是最新的，交集当然不空。
            //     真正要验的是「命中的笔记落在最新 N 条之外」时 byTag 会不会变空。
            //     做法：先插旧的带标签笔记，再插一大批新的无标签笔记把它们挤出去。
            val oldTagged = ArrayList<Long>()
            for (i in 0 until 20) {
                oldTagged.add(Notes.insert(db, Note.create("旧笔记 $i #oldtag", 1792000000000L + i * 1000L, "manual")))
            }
            // 再插 300 条更新的、不带这个标签的笔记
            for (i in 0 until 300) {
                Notes.insert(db, Note.create("新笔记 $i", now + 9000000L + i * 10L, "manual"))
            }
            // 此时 #oldtag 的 20 条是最旧的，feed(limit=50) 只会拿到最新的 50 条
            val oldByTag = Notes.byTag(db, "oldtag", 50)
            android.util.Log.i(TAG, "PROBE byTag 挤出测试: 期望 20 条, 实际 " + oldByTag.size)
            check("byTag 在命中项不在最新 N 条内时仍能命中", oldByTag.size == 20,
                "期望20, 实际" + oldByTag.size)


            // 33. byTag 的父标签继承 + 通配符转义。这两个都没测过。
            val mathNote = Notes.insert(db, Note.create("导数笔记 #数学/导数", 1792000100000L, "manual"))
            val mathRoot = Notes.insert(db, Note.create("数学总览 #数学", 1792000200000L, "manual"))
            val mathOther = Notes.insert(db, Note.create("其他科 #物理/力学", 1792000300000L, "manual"))
            val byMath = Notes.byTag(db, "数学", 100)
            check("父标签：查数学带出数学/导数", byMath.any { it.id == mathNote }, "命中 " + byMath.size + " 条")
            check("父标签：查数学也带出数学本身", byMath.any { it.id == mathRoot }, "")
            check("父标签：不串到物理/力学", byMath.none { it.id == mathOther }, "")

            // 34. 通配符转义：标签里带下划线时，LIKE 会把 _ 当任意一个字符
            val underscoreTag = Notes.insert(db, Note.create("下划线标签 #a_b", 1792000400000L, "manual"))
            val wildcardTag = Notes.insert(db, Note.create("通配诱饵 #aXb", 1792000500000L, "manual"))
            val byUnderscore = Notes.byTag(db, "a_b", 100)
            android.util.Log.i(TAG, "PROBE 下划线: 查 a_b 命中 " + byUnderscore.size + " 条, ids=" + byUnderscore.map { it.id })
            check("标签 a_b 只命中自己，不把 aXb 当通配符匹配到",
                byUnderscore.any { it.id == underscoreTag } && byUnderscore.none { it.id == wildcardTag },
                "命中 id=" + byUnderscore.map { it.id })


            // 35. LIKE 通配符：byTag 拼的是 t.name LIKE '<tag>/%'。
            //     标签里若含 %，它会被 SQLite 当成通配符 —— 查询会命中不该命中的笔记。
            //     造一对：真标签「进度100%」，诱饵标签「进度100abc」（不含 %）。
            val pctTag = Notes.insert(db, Note.create("百分比 #进度100%", 1792000600000L, "manual"))
            val pctDecoy = Notes.insert(db, Note.create("诱饵 #进度100abc", 1792000700000L, "manual"))
            val byPct = Notes.byTag(db, "进度100%", 100)
            android.util.Log.i(TAG, "PROBE 百分号: 查 进度100% 命中 " + byPct.size + " 条, ids=" + byPct.map { it.id } +
                " 真=" + pctTag + " 诱饵=" + pctDecoy)
            check("标签含 % 时不该把「进度100abc」当通配符匹配到",
                byPct.none { it.id == pctDecoy },
                "命中 id=" + byPct.map { it.id })


            // 36. 合法标签字符集包含下划线，所以 _ 能进标签，而 byTag 用 LIKE '<tag>/%'。
            //     真风险：查「a_b」时 LIKE 里的 _ 匹配任意单字符。
            //     造一对：真「a_b」，诱饵「a+b」（同长度单字符差异，且 + 也是合法标签字符）。
            val usTag = Notes.insert(db, Note.create("下划线真 #a_b", 1792000800000L, "manual"))
            val usDecoy = Notes.insert(db, Note.create("下划线诱饵 #a+b", 1792000900000L, "manual"))
            val usSub = Notes.insert(db, Note.create("下划线子级 #a_b/c", 1792001000000L, "manual"))
            val byUs = Notes.byTag(db, "a_b", 100)
            android.util.Log.i(TAG, "PROBE 下划线2: 查 a_b 命中 " + byUs.size + " 条, ids=" + byUs.map { it.id } +
                " 真=" + usTag + " 诱饵=" + usDecoy + " 子级=" + usSub)
            check("查 a_b 必须命中自己", byUs.any { it.id == usTag }, "")
            check("查 a_b 必须带出子级 a_b/c", byUs.any { it.id == usSub }, "");
            check("查 a_b 不该命中 a+b（_ 被当通配符了）", byUs.none { it.id == usDecoy },
                "命中 id=" + byUs.map { it.id })


            // 37. 上一条 pass 是模式结构救的（a+b 不以 a+b/ 开头），不是转义救的。
            //     真正的漏洞：存在 a+b/x 这种子级标签时，LIKE 'a_b/%' 的 _ 会匹配 + 。
            val usDeep = Notes.insert(db, Note.create("诱饵子级 #a+b/x", 1792001100000L, "manual"))
            val byUs2 = Notes.byTag(db, "a_b", 100)
            android.util.Log.i(TAG, "PROBE 下划线3: 查 a_b 命中 " + byUs2.size + " 条, ids=" + byUs2.map { it.id } +
                " 诱饵子级=" + usDeep)
            check("查 a_b 不该把 a+b/x 当子级带出来（_ 是通配符）",
                byUs2.none { it.id == usDeep },
                "命中 id=" + byUs2.map { it.id })

        } catch (e: Exception) {
            fails.add("异常: " + (e.message ?: e.javaClass.simpleName))
            android.util.Log.e(TAG, "EXCEPTION", e)
        } finally {
            try { db?.close() } catch (_: Exception) {}
            ctx.deleteDatabase(dbName)
        }
        android.util.Log.i(TAG, "结果: " + pass + " 通过, " + fails.size + " 失败");

        return pass to fails
    }
}