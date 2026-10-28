package com.chun.lingzhu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * v2.4 记事本：SQLite 存储层（建表 / 版本）。
 *
 * ## 这一层刻意写得薄
 * 工程只有 JVM 单测、没有 androidTest，所以这里的代码**测不到**。
 * 应对办法：所有判断（正文怎么截、迁移怎么去重、回收站什么时候到期）都放进 [Note] / [NoteSyntax]
 * 的纯函数里测；这里只做「把值搬进搬出」，一行 if 都不写。
 *
 * ## 用系统自带 SQLite，不是第三方
 * `android.database.sqlite` 是系统 API，不算依赖。工程零第三方库这条线不能破。
 * 全文检索用 FTS4（而不是 FTS5）：FTS5 要额外的编译开关，老设备不一定有。
 */
class NoteStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB, null, VERSION) {

    /**
     * 建这个 helper 的 applicationContext。
     *
     * 用 applicationContext 而不是构造参数：helper 常被 Activity 建、被后台线程用，
     * 持有 Activity 就是泄漏。onOpen 拿它把 [NoteVault] 注册上，路径比对也用它。
     */
    private val app: Context = ctx.applicationContext ?: ctx

    companion object {
        /** 库文件名。放私有目录，不进相册、不参与备份 */
        const val DB = "notes.db"

        /** 版本号。改表结构必须 +1 并在 [onUpgrade] 里写清楚怎么升 */
        const val VERSION = 1

        const val T_NOTES = "notes"
        const val T_TAGS = "tags"
        const val T_NOTE_TAGS = "note_tags"
        const val T_ATT = "attachments"
        const val T_META = "meta"
        const val T_FTS = "notes_fts"

        /** 空串常量：SQL 里到处要用，单独提出来免得转义层数数错 */
        private const val E = "\u0027\u0027"

        /** 双引号。FTS4 的 content= 要用双引号空串，用常量拼避免转义层数数错 */
        private const val DQ = "\u0022"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // notes：正文是唯一事实来源；标签和双链在保存时从 body 解析出来重建
        db.execSQL(
            "CREATE TABLE " + T_NOTES + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uid TEXT NOT NULL UNIQUE," +
                "title TEXT NOT NULL DEFAULT " + E + "," +
                "body TEXT NOT NULL DEFAULT " + E + "," +
                "source TEXT NOT NULL DEFAULT \u0027manual\u0027," +
                "origin TEXT NOT NULL DEFAULT " + E + "," +
                "pinned INTEGER NOT NULL DEFAULT 0," +
                "archived INTEGER NOT NULL DEFAULT 0," +
                "deleted_at INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "ocr_text TEXT NOT NULL DEFAULT " + E + "," +
                "summary TEXT NOT NULL DEFAULT " + E + "," +
                "todo_id INTEGER NOT NULL DEFAULT 0," +
                "card_ids TEXT NOT NULL DEFAULT " + E + "," +
                "rev INTEGER NOT NULL DEFAULT 1)"
        )
        // 卡片流的默认查询就靠这个索引：先滤掉已删/已归档，再按置顶 + 时间排
        db.execSQL("CREATE INDEX notes_feed ON " + T_NOTES + "(deleted_at, archived, pinned DESC, created_at DESC)")
        // origin 索引：迁移去重每次都要查它
        db.execSQL("CREATE INDEX notes_origin ON " + T_NOTES + "(origin)")

        db.execSQL(
            "CREATE TABLE " + T_TAGS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                "use_count INTEGER NOT NULL DEFAULT 0," +
                "last_used INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE " + T_NOTE_TAGS + " (note_id INTEGER NOT NULL, tag_id INTEGER NOT NULL, PRIMARY KEY(note_id, tag_id))")
        db.execSQL("CREATE INDEX note_tags_tag ON " + T_NOTE_TAGS + "(tag_id)")

        db.execSQL(
            "CREATE TABLE " + T_ATT + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "uid TEXT NOT NULL UNIQUE," +
                "note_id INTEGER NOT NULL," +
                "mime TEXT NOT NULL DEFAULT \u0027image/jpeg\u0027," +
                "path TEXT NOT NULL," +
                "thumb TEXT NOT NULL DEFAULT " + E + "," +
                "w INTEGER NOT NULL DEFAULT 0," +
                "h INTEGER NOT NULL DEFAULT 0," +
                "bytes INTEGER NOT NULL DEFAULT 0," +
                "sha256 TEXT NOT NULL DEFAULT " + E + "," +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX att_note ON " + T_ATT + "(note_id)")

        db.execSQL("CREATE TABLE " + T_META + " (k TEXT PRIMARY KEY, v TEXT NOT NULL)")

        // 外部内容 FTS4：rowid = notes.id，内容由应用层写入（中文先切二元组）
        // FTS4 自包含表（不是外部内容表）。
        //
        // 本来想用外部内容表（content= 空串指向 notes），实测在 Android 的 SQLite 上
        // 建表能过、写入必炸：报 no such table: main.。
        // 在桌面 sqlite3 上复现了三种写法，只有「自包含」和「content 指向真实表名」能用。
        // 自包含最简单：索引内容由 syncFts 自己维护，反正也要先切二元组才写得进去。
        // 代价是文本存两份，但笔记正文远没到要省这点空间的量级。
        db.execSQL("CREATE VIRTUAL TABLE " + T_FTS + " USING fts4(t_title, t_body, t_ocr, t_tags)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 是第一版，还没有升级路径。以后加字段写在这里，**不要** drop 表。
        // 这条注释是给未来的自己：用户的数据比表结构的整洁重要。
    }

    /**
     * 每次库真正打开时把 [NoteVault] 注册上（幂等，5 个界面重复进也没事）。
     *
     * 这里**只**注册真正的 notes.db：自检用的是自己的 `selftest.db`（见 [NoteSelfTest]，
     * 单独一个 SQLiteOpenHelper，从不经过本类），所以自检的写入永远进不了用户的 vault。
     * [NoteVault.ensure] 内部还会再比一次路径，双保险。
     */
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.setForeignKeyConstraintsEnabled(true)
        try {
            NoteVault.ensure(app, db)
        } catch (t: Throwable) {
            // 注册/迁移失败绝不能让「打开数据库」失败：界面还要用它读列表
            NoteVault.noteOpenFailure(t)
        }
    }
}
