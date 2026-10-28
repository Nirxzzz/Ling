package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同步结果里的「冲突副本 N 个」：数的是本机合并前没有、合并后新出现的 `~conflict~` uid。
 *
 * 真实事故：电脑先合并并生成副本，手机拿到的 records 里已经带着副本，手机自己 merge 不再生成任何副本，
 * 界面于是显示 0。反过来也不能把远端 counts 相加 —— 重复同步会把旧副本每次都再报一遍。
 * 这里用 [LanMerge.merge] 的真实输出驱动 [countNewConflictCopies]，覆盖三种场景。
 */
class LanSyncConflictReportTest {

    private val uid = "k:11111111-1111-1111-1111-111111111111"

    private fun clock(vararg pairs: Pair<String, Int>): JSONObject =
        JSONObject().apply { for ((k, v) in pairs) put(k, v) }

    private fun card(front: String): JSONObject = JSONObject()
        .put("deck", "d")
        .put("front", front)
        .put("back", "b")
        .put("source", "manual")
        .put("createdAt", 1L)
        .put("fsrs", JSONObject()
            .put("state", 0)
            .put("stability", 0.0)
            .put("difficulty", 0.0)
            .put("due", 0L))
        .put("origin", "")

    private fun rec(uid: String, clock: JSONObject, front: String): JSONObject = JSONObject()
        .put("uid", uid)
        .put("clock", clock)
        .put("value", card(front))

    private fun tomb(uid: String, clock: JSONObject): JSONObject = JSONObject()
        .put("uid", uid)
        .put("clock", clock)
        .put("value", JSONObject.NULL)

    private fun historyRec(uid: String, clock: JSONObject, text: String): JSONObject = JSONObject()
        .put("uid", uid)
        .put("clock", clock)
        .put(
            "value",
            JSONObject()
                .put("kind", "solve")
                .put("label", "学习")
                .put("text", text)
                .put("src", "")
                .put("star", true)
                .put("createdAt", 1L),
        )

    private fun arr(vararg r: JSONObject): JSONArray = JSONArray().apply { for (x in r) put(x) }

    private fun uids(a: JSONArray): List<String> =
        (0 until a.length()).map { a.getJSONObject(it).getString("uid") }

    private fun cardsOf(before: JSONArray, after: JSONArray): JSONObject =
        LanSyncStats.classify(before, after).getJSONObject("cards")

    private fun historyOf(before: JSONArray, after: JSONArray): JSONObject =
        LanSyncStats.classify(before, after).getJSONObject("history")

    @Test
    fun countsNewConflictCopiesOnceAndNeverRepeatsOldOnes() {
        // 手机本机：apple；电脑本机：banana。两端并发改同一张卡。
        val local = arr(rec(uid, clock("A" to 2), "apple"))
        val pcLocal = arr(rec(uid, clock("B" to 2), "banana"))

        // 1) 电脑先合并：副本已经物化在返回的 records 里，手机自己 merge 不会再生成副本。
        val pcMerged = LanMerge.merge(pcLocal, local)
        assertEquals(1, pcMerged.getInt("conflicts"))
        val afterPcFirst = LanMerge.merge(local, pcMerged.getJSONArray("records"))
        assertEquals(0, afterPcFirst.getInt("conflicts"))              // merge 原语义：本机这一步没新生成
        val mergedOnce = afterPcFirst.getJSONArray("records")
        assertEquals(2, mergedOnce.length())
        assertEquals(1, uids(mergedOnce).count { it.contains("~conflict~") })
        assertEquals(1, countNewConflictCopies(local, mergedOnce))     // 用户仍应看到 1 个副本

        // 统计口径：远端已物化的副本计入 conflicts，但不计 added。
        val stat = cardsOf(local, mergedOnce)
        assertEquals(1, stat.getInt("conflicts"))
        assertEquals(0, stat.getInt("added"))
        assertEquals(2, stat.getInt("after"))

        // 2) 本机生成：电脑没合并过，本轮冲突副本由手机 merge 生成。
        val remotePlain = arr(rec(uid, clock("B" to 3), "cherry"))
        val afterLocal = LanMerge.merge(local, remotePlain)
        assertEquals(1, afterLocal.getInt("conflicts"))
        val mergedLocally = afterLocal.getJSONArray("records")
        assertEquals(2, mergedLocally.length())
        assertEquals(1, countNewConflictCopies(local, mergedLocally))
        assertEquals(1, cardsOf(local, mergedLocally).getInt("conflicts"))

        // 3) 重复同步：副本已在两端（电脑返回的就是这份 mergedOnce），本轮新增 0，旧副本不被重复上报。
        val afterRepeat = LanMerge.merge(mergedOnce, mergedOnce)
        assertEquals(0, afterRepeat.getInt("conflicts"))
        assertEquals(0, countNewConflictCopies(mergedOnce, afterRepeat.getJSONArray("records")))
        assertEquals(0, cardsOf(mergedOnce, afterRepeat.getJSONArray("records")).getInt("conflicts"))
    }

    @Test
    fun tombstonedCopyIsNotCountedAndCopiesNeverBecomeAdded() {
        val copy = "$uid~conflict~0123456789abcdef01234567"
        val deadCopy = "$uid~conflict~fedcba9876543210fedcba98"

        val onlyCopy = cardsOf(JSONArray(), arr(rec(copy, clock("A" to 1), "local")))
        assertEquals(1, onlyCopy.getInt("conflicts"))
        assertEquals(0, onlyCopy.getInt("added"))
        assertEquals(1, onlyCopy.getInt("after"))

        val onlyTombstone = cardsOf(JSONArray(), arr(tomb(deadCopy, clock("A" to 1))))
        assertEquals(0, onlyTombstone.getInt("conflicts"))
        assertEquals(0, onlyTombstone.getInt("added"))
        assertEquals(0, onlyTombstone.getInt("deleted"))
        assertEquals(0, onlyTombstone.getInt("after"))
    }

    @Test
    fun statsSplitCardsAndHistoryByRealChange() {
        val updated = "k:11111111-1111-1111-1111-111111111111"
        val restored = "k:22222222-2222-2222-2222-222222222222"
        val deleted = "k:33333333-3333-3333-3333-333333333333"
        val added = "k:44444444-4444-4444-4444-444444444444"
        val cardCopy = "$updated~conflict~0123456789abcdef01234567"
        val deadCopy = "$updated~conflict~fedcba9876543210fedcba98"
        val hUid = "h:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val hCopy = "$hUid~conflict~00112233445566778899aabb"

        val before = arr(
            rec(updated, clock("A" to 2), "old"),
            tomb(restored, clock("A" to 1)),
            rec(deleted, clock("A" to 1), "gone"),
            historyRec(hUid, clock("A" to 1), "old-history"),
        )
        val after = arr(
            rec(updated, clock("A" to 3), "new"),
            rec(restored, clock("A" to 2), "back"),
            tomb(deleted, clock("A" to 2)),
            rec(added, clock("A" to 1), "new-card"),
            rec(cardCopy, clock("A" to 4), "copy-payload"),
            tomb(deadCopy, clock("A" to 4)),
            historyRec(hUid, clock("A" to 2), "new-history"),
            historyRec(hCopy, clock("A" to 2), "copy-history"),
        )

        val cards = cardsOf(before, after)
        assertEquals(1, cards.getInt("added"))
        assertEquals(1, cards.getInt("updated"))
        assertEquals(1, cards.getInt("deleted"))
        assertEquals(1, cards.getInt("restored"))
        assertEquals(1, cards.getInt("conflicts"))
        assertEquals(2, cards.getInt("before"))      // updated + deleted
        assertEquals(4, cards.getInt("after"))       // updated + restored + added + 活副本

        val history = historyOf(before, after)
        assertEquals(0, history.getInt("added"))
        assertEquals(1, history.getInt("updated"))
        assertEquals(0, history.getInt("deleted"))
        assertEquals(0, history.getInt("restored"))
        assertEquals(1, history.getInt("conflicts"))
        assertEquals(1, history.getInt("before"))
        assertEquals(2, history.getInt("after"))     // 主版本 + 活副本
    }
}
