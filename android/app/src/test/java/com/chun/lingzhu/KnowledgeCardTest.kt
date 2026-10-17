package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KnowledgeCard：知识卡存储、去重、上限、到期队列、转录抽取 */
class KnowledgeCardTest {

    private fun kc(id: Long, deck: String = "默认", front: String = "f$id", dueOffsetDays: Long = 3L): KnowledgeCard.KCData {
        val c = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L)
        return KnowledgeCard.KCData(id, deck, front, "b$id", "manual", 1_000_000L,
            c.copy(due = 1_000_000L + dueOffsetDays * Fsrs.DAY_MS))
    }

    // ---------------- 转录抽取 ----------------

    @Test fun frontTakesFirstContentLineBeforeAnswer() {
        val raw = "屏幕上是一道求导题\n## 答案\n导数是 3x²"
        assertEquals("屏幕上是一道求导题", KnowledgeCard.frontFromTranscript(raw))
    }

    @Test fun frontFallsBackToClipWhenNoAnswerMark() {
        assertEquals("纯文字内容没有分段", KnowledgeCard.frontFromTranscript("纯文字内容没有分段"))
    }

    @Test fun backExtractsAnswerSectionOnly() {
        val raw = "题目描述\n## 答案\n3x²\n[可点击名词] 导数\n## 追问：为什么"
        val back = KnowledgeCard.backFromTranscript(raw)
        assertTrue(back.contains("3x²"))
        assertTrue(!back.contains("追问：为什么"))
        assertTrue(!back.contains("可点击名词"))
    }

    @Test fun backClippedToLimit() {
        val raw = "## 答案\n" + "字".repeat(600)
        assertEquals(KnowledgeCard.BACK_MAX + 1, KnowledgeCard.backFromTranscript(raw).length)   // 截断 + 省略号
    }

    // ---------------- 去重与上限 ----------------

    @Test fun dedupSameDeckSameFront() {
        val l = listOf(kc(1, deck = "默认", front = "什么是导数"))
        assertTrue(!KnowledgeCard.dedup(l, "默认", "什么是导数"))
        assertTrue(KnowledgeCard.dedup(l, "默认", "什么是积分"))
        assertTrue(KnowledgeCard.dedup(l, "数学", "什么是导数"))   // 不同牌组不算重
    }

    @Test fun tryAddRejectsWhenFull() {
        val l = (1L..KnowledgeCard.MAX).map { kc(it, front = "f$it") }
        assertNull(KnowledgeCard.tryAdd(l, kc(9999, front = "新卡")))
    }

    @Test fun tryAddSkipsBlankFront() {
        val l = listOf(kc(1))
        assertEquals(l, KnowledgeCard.tryAdd(l, kc(2, front = "   ")))
    }

    @Test fun tryAddAssignsIncrementingId() {
        val added = KnowledgeCard.tryAdd(listOf(kc(3)), kc(0, front = "新卡"))
        assertNotNull(added)
        assertEquals(4L, added!![0].id)
        assertEquals(2, added.size)
    }

    // ---------------- 到期排序 ----------------

    @Test fun dueFirstPutsDueBeforeNotDue() {
        val notDue = kc(1, dueOffsetDays = 30)
        val due = kc(2, dueOffsetDays = -1)
        val sorted = KnowledgeCard.dueFirst(listOf(notDue, due), now = 1_000_000L + 5 * Fsrs.DAY_MS)
        assertEquals(2L, sorted[0].id)
    }

    @Test fun dueCardsReflectFsrsSchedule() {
        val due = Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1_000_000L - Fsrs.DAY_MS)
        assertTrue(Fsrs.isDue(due, 1_000_000L + 5 * Fsrs.DAY_MS))
    }

    // ---------------- 存储解析（纯 JSON 部分） ----------------

    @Test fun kcJsonFieldsRoundTripShape() {
        // Store 依赖 org.json 往返：这里直接构造同结构验证字段名稳定（Store.parse 读这些键）
        val o = org.json.JSONObject().apply {
            put("id", 1L); put("deck", "默认"); put("front", "f"); put("back", "b")
            put("source", "answer"); put("createdAt", 1L); put("fsrs", org.json.JSONObject(Fsrs.toJson(Fsrs.Card())))
        }
        assertEquals(Fsrs.Card(), Fsrs.cardFromJson(o.getJSONObject("fsrs").toString()))
        assertEquals("answer", o.optString("source"))
    }

    @Test fun sourceOfFallsBackToManual() {
        assertEquals(KnowledgeCard.Source.ANSWER, KnowledgeCard.Source.of("answer"))
        assertEquals(KnowledgeCard.Source.MANUAL, KnowledgeCard.Source.of("不认识"))
        assertEquals(KnowledgeCard.Source.MANUAL, KnowledgeCard.Source.of(null))
    }


    // ---------------- K4 批量导入（Store.addAll 的纯函数等价：循环 tryAdd） ----------------

    @Test fun batchImportLoopImportsAll() {
        var l = emptyList<KnowledgeCard.KCData>()
        val batch = (1L..50).map {
            KnowledgeCard.KCData(0, "默认", "f$it", "b$it", "import", 1L,
                Fsrs.review(Fsrs.Card(), Fsrs.Rating.Good, 1L))
        }
        for (t in batch) l = KnowledgeCard.tryAdd(l, t)!!
        assertEquals(50, l.size)
    }

    @Test fun batchImportSkipsDuplicates() {
        var l = listOf(kc(1, front = "重复"))
        val batch = listOf(
            KnowledgeCard.KCData(0, "默认", "重复", "答", "import", 1L, Fsrs.Card()),
            KnowledgeCard.KCData(0, "默认", "新卡", "答", "import", 1L, Fsrs.Card()),
        )
        for (t in batch) l = KnowledgeCard.tryAdd(l, t) ?: l
        assertEquals(2, l.size)
    }

    @Test fun batchImportStopsAtCap() {
        var l = emptyList<KnowledgeCard.KCData>()
        for (i in 1L..KnowledgeCard.MAX) l = KnowledgeCard.tryAdd(l, kc(i, front = "f$i")) ?: l
        val extra = KnowledgeCard.tryAdd(l, kc(9999, front = "超限卡"))
        assertNull(extra)   // 满了返回 null = 拒绝
    }

    // ---------------- 批量合入纯函数 mergeBatch（真实分桶 + 不再虚高）----------------

    private fun batchCard(front: String, deck: String = "默认") =
        KnowledgeCard.KCData(0L, deck, front, "答", "import", 1L, Fsrs.Card())

    @Test fun mergeBatchCountsOnlyRealInsertions() {
        val existing = listOf(kc(1, front = "已有"))
        val incoming = listOf(batchCard("已有"), batchCard("新一"), batchCard("新二"))
        val r = KnowledgeCard.mergeBatch(existing, incoming).result
        // 旧 addAll 会把「重复」也 ok++ 造成虚高；这里重复只算 duplicates
        assertEquals(2, r.added)
        assertEquals(1, r.duplicates)
        assertEquals(0, r.full)
    }

    @Test fun mergeBatchCountsInvariantHolds() {
        val existing = listOf(kc(1, front = "已有"))
        val incoming = listOf(batchCard("已有"), batchCard("新"), batchCard("   "))
        val r = KnowledgeCard.mergeBatch(existing, incoming).result
        // 不变式：三个桶加起来正好是传入张数
        assertEquals(incoming.size, r.added + r.duplicates + r.full)
        assertEquals(1, r.added)        // 只有「新」真进了库
        // 「已有」= 重复、「   」= 空白正面：都归 duplicates（同样没进库）
        assertEquals(2, r.duplicates)
        assertEquals(0, r.full)
    }

    @Test fun mergeBatchReportsOnlyActuallyInsertedDecks() {
        val existing = listOf(kc(1, deck = "甲", front = "已有"))
        val incoming = listOf(
            batchCard("已有", deck = "甲"),   // 重复 → 甲不该出现在 decks 里
            batchCard("新", deck = "乙"),
            batchCard("另一个", deck = "丙"),
        )
        val r = KnowledgeCard.mergeBatch(existing, incoming).result
        assertEquals(setOf("乙", "丙"), r.decks)
    }

    @Test fun mergeBatchStopsAtCapAndReportsFull() {
        val existing = (1L..3L).map { kc(it, front = "f$it") }
        val incoming = listOf(batchCard("新一"), batchCard("新二"), batchCard("新三"))
        val r = KnowledgeCard.mergeBatch(existing, incoming, max = 4).result
        assertEquals(1, r.added)     // 只剩 1 个空位
        assertEquals(2, r.full)      // 其余超限
        assertEquals(0, r.duplicates)
        assertEquals(existing.size + 1, KnowledgeCard.mergeBatch(existing, incoming, max = 4).list.size)
    }

    @Test fun mergeBatchMatchesLoopTryAdd() {
        // mergeBatch 必须与「逐张 tryAdd」得到同一份列表（顺序 / id 都一致）
        val existing = listOf(kc(2, front = "已有"))
        val incoming = listOf(batchCard("已有"), batchCard("新一"), batchCard("   "), batchCard("新二"))
        var looped = existing
        for (t in incoming) looped = KnowledgeCard.tryAdd(looped, t) ?: looped
        assertEquals(looped, KnowledgeCard.mergeBatch(existing, incoming).list)
    }

    @Test fun mergeBatchAssignsIdsAboveExistingMax() {
        val existing = listOf(kc(7, front = "已有"))
        val merged = KnowledgeCard.mergeBatch(existing, listOf(batchCard("新"))).list
        assertEquals(8L, merged.first().id)   // 新卡前插且 id = max+1
    }

    // ---------------- origin：JSON 可选字段 ----------------

    @Test fun originDefaultsEmptyAndSurvivesCopy() {
        // 老调用点不传 origin → 空串（尾参带默认值，源兼容）
        val base = KnowledgeCard.KCData(1L, "d", "f", "b", "import", 1L, Fsrs.Card())
        assertEquals("", base.origin)
        // 编辑 / 评分走的都是 copy(...)：origin 必须原样保留
        val tag = "笔记.md · 「导数的定义」"
        val withOrigin = base.copy(origin = tag)
        assertEquals(tag, withOrigin.copy(fsrs = Fsrs.Card()).origin)
        assertEquals(tag, withOrigin.copy(back = "改了背面").origin)
    }

    @Test fun originIsOptionalInJsonShape() {
        // Store.parse 读 origin 用 optString("origin", "")：老文件没有这个键也读成空串
        val o = org.json.JSONObject().apply {
            put("id", 1L); put("deck", "默认"); put("front", "f"); put("back", "b")
            put("source", "import"); put("createdAt", 1L); put("fsrs", org.json.JSONObject(Fsrs.toJson(Fsrs.Card())))
        }
        assertEquals("", o.optString("origin", ""))
        o.put("origin", "文件.pdf · 「原文」")
        assertEquals("文件.pdf · 「原文」", o.optString("origin", ""))
    }
}
