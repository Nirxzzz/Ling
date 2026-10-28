package com.chun.lingzhu

import java.util.UUID

/**
 * v2.4 记事本：笔记的纯数据模型与迁移映射。
 *
 * ## 为什么模型和存储分开
 * SQLite 那层（[NoteStore]）需要 Context，只能在模拟器上冒烟测。
 * 但「一条笔记长什么样」「旧随手记怎么变成新笔记」这些判断**不该**依赖数据库 ——
 * 它们全是纯函数，放这里就能用 JVM 单测钉死。
 *
 * 这也是方案 1.7 的要求：数据库这层要写得很薄，逻辑抽成纯函数测。
 */
object Note {

    /** 正文上限。100 KB 足够写长文，又不至于一条笔记把列表卡住 */
    const val MAX_BODY = 100_000

    /** 标题显示上限（列表里只显示一行） */
    const val MAX_TITLE = 120

    /** 回收站保留天数：30 天后才物理清除 */
    const val TRASH_DAYS = 30

    /**
     * 一条笔记。
     *
     * 字段和方案 3.1 的 `notes` 表一一对应，但这里不带任何存储细节 ——
     * `id` 是本地自增（-1 = 还没入库），`uid` 是全局标识（同步 / 双链 / MCP 一律用它）。
     */
    data class Item(
        val uid: String,
        val title: String = "",
        val body: String = "",
        val source: String = "manual",   // manual | memo | share | quick | mcp
        val origin: String = "",         // 例：legacy:memo:1729500000000
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val deletedAt: Long = 0L,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val ocrText: String = "",
        val summary: String = "",
        val todoId: Long = 0L,
        val cardIds: List<String> = emptyList(),
        val rev: Int = 1,
    ) {
        val deleted: Boolean get() = deletedAt > 0L

        /** 列表里显示的那一行：有标题用标题，没有就从正文第一行截 */
        val displayTitle: String get() = title.ifBlank { firstLine(body) }
    }

    /** 生成一个新的全局 uid。用 UUID 而不是时间戳：多设备同时记不会撞 */
    fun newUid(): String = UUID.randomUUID().toString()

    /** 取正文第一行做标题，太长就截断加省略号 */
    fun firstLine(body: String): String {
        val line = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return "（空笔记）"
        return if (line.length <= MAX_TITLE) line else line.substring(0, MAX_TITLE - 1) + "…"
    }

    /**
     * 建一条新笔记。正文超上限就截断 —— 截断比拒绝保存好：
     * 用户打了半天的字，宁可少存最后一点也不要提示「太长无法保存」。
     */
    fun create(body: String, now: Long, source: String = "manual", uid: String = newUid()): Item {
        val safe = if (body.length <= MAX_BODY) body else body.substring(0, MAX_BODY)
        return Item(uid = uid, body = safe, source = source, createdAt = now, updatedAt = now)
    }

    /**
     * 回收站到期判断：删除满 [TRASH_DAYS] 天。
     * 返回 true 表示**可以物理清除**了。
     */
    fun trashExpired(deletedAt: Long, now: Long): Boolean =
        deletedAt > 0L && now - deletedAt >= TRASH_DAYS * 24L * 3600L * 1000L

    // ---------------- 迁移：旧随手记 → 笔记 ----------------

    /** 旧随手记的迁移来源前缀，写在 `origin` 里，用来去重 */
    const val LEGACY_MEMO = "legacy:memo:"

    /**
     * 把一条旧随手记映射成一条笔记。
     *
     * 三条关键决定：
     * 1. **uid 由 memo id 决定**，不是随机 —— 迁移跑第二遍会得到同一个 uid，
     *    靠 `origin` 去重才不会重复导入。方案要求「迁移是幂等的」。
     * 2. 正文不截断：旧代码截到 200 字，但那是存储限制，不是用户意图。
     * 3. `todoId` 带过去：已经转成待办的随手记，转成笔记后还能跳回那条待办。
     */
    fun fromLegacyMemo(m: Memo.Item): Item = Item(
        uid = LEGACY_MEMO + m.id,
        body = m.text,
        source = "memo",
        origin = LEGACY_MEMO + m.id,
        createdAt = m.at,
        updatedAt = m.at,
        todoId = m.todoId,
    )

    /**
     * 迁移整个旧列表，并跳过已经导入过的。
     *
     * [existingOrigins] 是库里已有的 `origin` 集合。幂等的关键就是它：
     * 第二次运行迁移时这些 origin 都已在集合里，一条都不会重复插入。
     *
     * 空白内容的随手记会被跳过（旧数据里可能有）。
     */
    fun migrateMemos(memos: List<Memo.Item>, existingOrigins: Set<String>): List<Item> =
        memos.asSequence()
            .filter { it.text.isNotBlank() }
            .map { fromLegacyMemo(it) }
            .filter { it.origin !in existingOrigins }
            .distinctBy { it.uid }
            .toList()
}
