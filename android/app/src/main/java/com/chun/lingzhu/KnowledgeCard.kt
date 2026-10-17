package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v1.69 K2 知识卡：可复习的问答卡（区别于 M1 的「记忆卡」card.json——那是给小Ling看的人设记忆）。
 *
 * 存储 filesDir/knowledge.json（FileMemo 模式，内存副本 + 后台落盘），上限 [MAX] 张；
 * 新增卡保留 New 状态；评分后才进入 FSRS 到期复习队列。
 * 纯函数都在 companion，Android 那层只在 [Store]。
 */
object KnowledgeCard {
    const val MAX = 5000
    const val FILE = "knowledge.json"
    const val BACK_MAX = 500

    enum class Source(val code: String) {
        ANSWER("answer"), CHAT("chat"), CLIP("clip"), MANUAL("manual"), IMPORT("import");
        companion object { fun of(code: String?): Source = entries.firstOrNull { it.code == code } ?: MANUAL }
    }

    data class KCData(
        val id: Long,
        val deck: String,
        val front: String,
        val back: String,
        val source: String,
        val createdAt: Long,
        val fsrs: Fsrs.Card,
        /** 来源（导入时为「文件名 + 已核对的原文片段」）；JSON 里可选，老文件读出来是空串。 */
        val origin: String = "",
    ) {
        fun isDue(now: Long): Boolean = Fsrs.isDue(fsrs, now)
    }

    /**
     * 批量导入结果。只统计**真正新插入**的：
     *  - [added] 实际入库张数；[decks] 仅这些新卡用到的牌组（一张都没进过的牌组不算）；
     *  - [duplicates] 因同牌组同正面重复、或正面空白而被拒的张数；
     *  - [full] 因超出 [MAX] 被拒的张数。
     * 不变式：`added + duplicates + full == 传入张数`。
     */
    data class ImportResult(val added: Int, val duplicates: Int, val full: Int, val decks: Set<String>)

    /** [mergeBatch] 的结果：合并后的列表 + 统计。 */
    data class Batch(val list: List<KCData>, val result: ImportResult)

    // ---------------- 纯函数 ----------------

    /** 从面板对话原文里抽「正面」：答案段之前的问题行（识别场景 = 顶部场景描述 / 追问），抽不出就用前 60 字 */
    fun frontFromTranscript(raw: String): String {
        val head = raw.substringBefore("## 答案").trim()
        val q = head.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: Memo.clip(raw, 60)
        return Memo.clip(q, 80)
    }

    /** 答案正文：取「## 答案」段之后、下一个 ## 段之前，剥 markdown 标记行，截 [BACK_MAX] 字 */
    fun backFromTranscript(raw: String): String {
        val after = raw.substringAfter("## 答案\n", "").ifEmpty { raw.substringAfter("## 答案", raw) }
        val body = after.substringBefore("\n## ").trim()
        val plain = body.lineSequence()
            .filter { !it.startsWith("[") && !it.startsWith("关键词：") }
            .joinToString("\n")
        return Memo.clip(plain, BACK_MAX)
    }

    /** 去重键：同牌组同正面视为同一张 */
    fun dedupKey(deck: String, front: String): String = "$deck|${"${front.trim()}"}"

    fun dedup(list: List<KCData>, deck: String, front: String): Boolean =
        list.none { it.deck == deck && it.front.trim() == front.trim() }

    /** 按到期排前面（未到期的按创建时间倒序排后面） */
    fun dueFirst(list: List<KCData>, now: Long): List<KCData> =
        list.sortedWith(
            compareByDescending<KCData> { it.isDue(now) }
                .thenBy { if (it.isDue(now)) it.fsrs.due else -it.createdAt }
        )

    /** 超上限拒新增：满了返回 null */
    fun tryAdd(list: List<KCData>, t: KCData, max: Int = MAX): List<KCData>? {
        if (t.front.isBlank()) return list
        if (!dedup(list, t.deck, t.front)) return list
        if (list.size >= max) return null
        val id = (list.maxOfOrNull { it.id } ?: 0L) + 1
        return listOf(t.copy(id = id)) + list
    }

    /**
     * 批量合入的**纯函数**（一次遍历 O(n)）：[list] 是现状，[cards] 是待导入。
     *
     * 与逐张 [tryAdd] 口径一致（空白正面拒 / 同牌组同正面去重 / 满 [max] 拒 / id 递增、新卡前插），
     * 只把去重键收进 Set 少走一遍。**修掉旧 [Store.addAll] 的虚高**：旧实现拿到 `tryAdd` 返回的
     * 同一个列表（空白 / 重复都返回入参本身）也 `ok++`，于是「成功数」把没进库的也算进去了。
     * 这里按真实结果分桶：`added + duplicates + full == cards.size`。
     */
    fun mergeBatch(list: List<KCData>, cards: List<KCData>, max: Int = MAX): Batch {
        val keys = HashSet<String>(list.size + cards.size)
        for (c in list) keys.add(dedupKey(c.deck, c.front))
        var maxId = list.maxOfOrNull { it.id } ?: 0L
        var size = list.size
        var added = 0; var duplicates = 0; var full = 0
        val inserted = ArrayList<KCData>()
        val decks = LinkedHashSet<String>()
        for (t in cards) {
            if (t.front.isBlank()) { duplicates++; continue }
            if (size >= max) { full++; continue }
            if (!keys.add(dedupKey(t.deck, t.front))) { duplicates++; continue }
            val card = t.copy(id = ++maxId)
            inserted += card; size++; added++; decks += t.deck
        }
        // 逐张 tryAdd 会把新卡依次前插，等价于「插入顺序倒过来 + 原有列表」
        return Batch(inserted.asReversed() + list, ImportResult(added, duplicates, full, decks))
    }

    // ---------------- 存储 ----------------

    class Store(ctx: Context) {
        private val file = File(ctx.applicationContext.filesDir, FILE)
        private val prefs = Prefs(ctx)

        @Synchronized fun list(): List<KCData> = FileMemo.get(file) { s -> parse(s) }
        @Synchronized fun add(front: String, back: String, source: Source, now: Long, deck: String = "默认"): Long {
            val cur = FileMemo.get(file) { s -> parse(s) }
            val added = tryAdd(cur, KCData(0, deck, front.trim(), back.trim(), source.code, now,
                Fsrs.Card())) ?: return -1L
            FileMemo.put(file, added) { l -> toJson(l) }
            return added.firstOrNull { it.deck == deck && it.front == front.trim() }?.id ?: -1L
        }
        /**
         * 批量导入（K4/apkg）：一次读一次写，避免逐张全量序列化（O(n²) → O(n)）。
         * 返回**实际新增**张数（旧实现把空白 / 重复也算进成功数，是虚高）。保留给既有调用方，内部走 [addBatch]。
         */
        @Synchronized fun addAll(cards: List<KnowledgeCard.KCData>): Int = addBatch(cards).added

        /**
         * 批量导入（K4/文档导入共用）：一次读一次写 O(n)，返回真实分桶结果（新增 / 重复 / 超限 + 真正入库的牌组）。
         * 没有任何新增时**不写盘**。
         */
        @Synchronized fun addBatch(cards: List<KnowledgeCard.KCData>): KnowledgeCard.ImportResult {
            if (cards.isEmpty()) return KnowledgeCard.ImportResult(0, 0, 0, emptySet())
            val cur = FileMemo.get(file) { s -> parse(s) }
            val batch = mergeBatch(cur, cards)
            if (batch.result.added > 0) FileMemo.put(file, batch.list) { l -> toJson(l) }
            return batch.result
        }
        @Synchronized fun remove(id: Long) = FileMemo.put(file, list().filter { it.id != id }) { l -> toJson(l) }
        /** 编辑内容保留 ID、来源和复习进度；同牌组同题面不允许重复。 */
        @Synchronized fun update(id: Long, deck: String, front: String, back: String): Boolean {
            val cur = list()
            if (front.isBlank() || cur.none { it.id == id } ||
                cur.any { it.id != id && it.deck == deck && it.front.trim() == front.trim() }) return false
            FileMemo.put(file, cur.map { if (it.id == id) it.copy(deck = deck, front = front.trim(), back = back.trim()) else it }) { toJson(it) }
            return true
        }
        @Synchronized fun answer(id: Long, rating: Fsrs.Rating, now: Long) =
            FileMemo.put(file, list().map { if (it.id == id) it.copy(fsrs = Fsrs.review(it.fsrs, rating, now, prefs.studyPlan.params())) else it }) { l -> toJson(l) }
        @Synchronized fun count() = list().size
        @Synchronized fun dueCards(now: Long) = list().filter { !it.fsrs.isNew && it.isDue(now) }.map { it.fsrs }

        private fun parse(s: String?): List<KCData> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    KCData(
                        id = o.optLong("id", 0L),
                        deck = o.optString("deck", "默认"),
                        front = o.optString("front"),
                        back = o.optString("back"),
                        source = Source.of(o.optString("source")).code,
                        createdAt = o.optLong("createdAt", 0L),
                        fsrs = Fsrs.cardFromJson(o.optJSONObject("fsrs")?.toString()) ?: Fsrs.Card(),
                        origin = o.optString("origin", ""),
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        private fun toJson(l: List<KCData>): String =
            JSONArray().apply { l.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("deck", c.deck); put("front", c.front); put("back", c.back)
                    put("source", c.source); put("createdAt", c.createdAt); put("fsrs", JSONObject(Fsrs.toJson(c.fsrs)))
                    put("origin", c.origin)
                })
            } }.toString()
    }
}
