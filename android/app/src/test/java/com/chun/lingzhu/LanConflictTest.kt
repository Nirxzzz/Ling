package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 冲突三选一 / 来源判定 / 错误分类。
 *
 * 全部是纯 JVM 行为测试：只喂 [JSONArray] / 异常对象，不启动 Android、不跑 Gradle 构建。
 */
class LanConflictTest {

    private val base = "k:11111111-1111-1111-1111-111111111111"
    // 契约：副本 uid = 直接父 uid + "~conflict~" + hash（LanMerge.conflictUid），前缀必须就是 base。
    private val conflict = "$base~conflict~0123456789abcdef01234567"

    private fun clock(vararg pairs: Pair<String, Int>): JSONObject =
        JSONObject().apply { for ((k, v) in pairs) put(k, v) }

    private fun card(front: String): JSONObject =
        JSONObject()
            .put("deck", "d")
            .put("front", front)
            .put("back", "b")
            .put("source", "manual")
            .put("createdAt", 1L)
            .put("fsrs", JSONObject().put("state", 0).put("stability", 0.0).put("difficulty", 0.0).put("due", 0L))
            .put("origin", "")

    private fun hist(text: String): JSONObject =
        JSONObject()
            .put("kind", "solve")
            .put("label", "学习")
            .put("text", text)
            .put("src", "")
            .put("star", true)
            .put("createdAt", 1L)

    private fun rec(uid: String, clock: JSONObject, value: JSONObject?): JSONObject =
        JSONObject().put("uid", uid).put("clock", clock).put("value", value ?: JSONObject.NULL)

    private fun arr(vararg records: JSONObject): JSONArray =
        JSONArray().apply { for (r in records) put(r) }

    private fun byUid(records: JSONArray): Map<String, JSONObject> =
        (0 until records.length()).associate { i ->
            val o = records.getJSONObject(i)
            o.getString("uid") to o
        }

    private fun live(record: JSONObject): Boolean = record.optJSONObject("value") != null

    private fun clk(record: JSONObject): Map<String, Int> {
        val c = record.getJSONObject("clock")
        return c.keys().asSequence().associateWith { c.getInt(it) }
    }

    // ---------------- 三选一 ----------------

    @Test
    fun keepBaseTombstonesCopyAtSameBumpedClock() {
        val records = arr(
            rec(base, clock("A" to 2, "B" to 1), card("apple")),
            rec(conflict, clock("A" to 1, "B" to 2), card("banana")),
        )
        val out = LanConflict.resolve(records, conflict, ConflictPick.KEEP_BASE, "C")
        val map = byUid(out.records)

        assertEquals("apple", map.getValue(base).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(conflict)))
        // N = bump(max({A:2,B:1},{A:1,B:2}), "C")
        for (uid in listOf(base, conflict)) {
            val c = clk(map.getValue(uid))
            assertEquals(2, c.getValue("A"))
            assertEquals(2, c.getValue("B"))
            assertEquals(1, c.getValue("C"))
        }
        assertEquals(listOf(conflict), out.dead)
    }

    @Test
    fun keepCopyMovesCopyPayloadToBaseAndTombstonesCopy() {
        val records = arr(
            rec(base, clock("A" to 2, "B" to 1), card("apple")),
            rec(conflict, clock("A" to 1, "B" to 2), card("banana")),
        )
        val out = LanConflict.resolve(records, conflict, ConflictPick.KEEP_COPY, "C")
        val map = byUid(out.records)

        assertEquals("banana", map.getValue(base).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(conflict)))
        assertEquals(listOf(conflict), out.dead)
        // 副本 uid 不变，解决不会改写它。
        assertTrue(map.containsKey(conflict))
    }

    @Test
    fun keepBothKeepsBothLiveAndCopyUidUnchanged() {
        val records = arr(
            rec(base, clock("A" to 2, "B" to 1), card("apple")),
            rec(conflict, clock("A" to 1, "B" to 2), card("banana")),
        )
        val out = LanConflict.resolve(records, conflict, ConflictPick.KEEP_BOTH, "C")
        val map = byUid(out.records)

        assertEquals("apple", map.getValue(base).getJSONObject("value").getString("front"))
        assertEquals("banana", map.getValue(conflict).getJSONObject("value").getString("front"))
        assertEquals(emptyList<String>(), out.dead)
        assertEquals(1, clk(map.getValue(base)).getValue("C"))
        assertEquals(1, clk(map.getValue(conflict)).getValue("C"))
    }

    @Test
    fun resolveDoesNotMutateInput() {
        val records = arr(
            rec(base, clock("A" to 2), card("apple")),
            rec(conflict, clock("B" to 2), card("banana")),
        )
        val before = LanMerge.canonical(records)
        LanConflict.resolve(records, conflict, ConflictPick.KEEP_COPY, "C")
        assertEquals(before, LanMerge.canonical(records))
    }

    @Test
    fun nestedCopyUsesDirectParentBeforeLastMarker() {
        val parent = "k:11111111-1111-1111-1111-111111111111~conflict~aaaaaaaaaaaaaaaaaaaaaaaa"
        val nested = parent + "~conflict~bbbbbbbbbbbbbbbbbbbbbbbb"
        assertEquals(parent, LanConflict.baseUidOf(nested))
        assertEquals(base, LanConflict.baseUidOf(parent))

        val records = arr(
            rec(base, clock("A" to 5), card("root")),
            rec(parent, clock("A" to 2), card("parent")),
            rec(nested, clock("B" to 3), card("nested")),
        )
        val out = LanConflict.resolve(records, nested, ConflictPick.KEEP_COPY, "C")
        val map = byUid(out.records)

        // 只动直接父与嵌套副本；根记录连 clock 都不碰。
        assertEquals(5, clk(map.getValue(base)).getValue("A"))
        assertEquals("nested", map.getValue(parent).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(nested)))
        assertEquals(listOf(nested), out.dead)
    }

    /** 解决后再跟还持有旧版本的对端同步：不能复活败方，也不再生成新副本。 */
    @Test
    fun resolvedConflictDoesNotReviveOnNextMerge() {
        val oldBase = rec(base, clock("A" to 1), card("apple"))
        val oldCopy = rec(conflict, clock("B" to 1), card("banana"))
        val resolved = LanConflict.resolve(arr(oldBase, oldCopy), conflict, ConflictPick.KEEP_COPY, "C")

        // 对端仍拿着解决前的两条旧记录。
        val peer = arr(oldBase, oldCopy)
        val merged = LanMerge.merge(resolved.records, peer)
        val map = byUid(merged.getJSONArray("records"))

        assertEquals("banana", map.getValue(base).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(conflict)))
        assertEquals(0, merged.getInt("conflicts"))
        assertEquals(2, merged.getJSONArray("records").length())     // 主版本活 + 副本墓碑
    }

    /** history 败方必须报 dead：调用方据此删 binding + stamp，用户重新收藏会拿到新 uid，不会把旧墓碑复活。 */
    @Test
    fun historyLoserIsReportedDeadSoBindingCanBeDropped() {
        val hBase = "h:11111111-1111-1111-1111-111111111111"
        val hCopy = "$hBase~conflict~0123456789abcdef01234567"
        val records = arr(
            rec(hBase, clock("A" to 1), hist("base")),
            rec(hCopy, clock("B" to 1), hist("copy")),
        )
        val out = LanConflict.resolve(records, hCopy, ConflictPick.KEEP_COPY, "C")
        // KEEP_COPY 里主版本存活、副本是败方：必须报 hCopy。
        assertTrue(out.dead.contains(hCopy))

        // 投影后删掉 hCopy 的 binding → 重新收藏会生成新 uid；旧 uid 的墓碑不会复活。
        val newUid = "h:22222222-2222-2222-2222-222222222222"
        val reStarred = arr(rec(newUid, clock("C" to 1), hist("copy")))
        val merged = LanMerge.merge(out.records, reStarred)
        val map = byUid(merged.getJSONArray("records"))

        assertFalse(live(map.getValue(hCopy)))       // 旧 uid 仍是墓碑
        assertTrue(live(map.getValue(newUid)))       // 重新收藏的是新记录
        assertEquals(0, merged.getInt("conflicts"))
    }

    // ---------------- 删除 vs 编辑 ----------------

    /** 主版本是墓碑时「只留主版本」会删掉唯一活内容：必须拒绝，且输入原样不动。 */
    @Test
    fun deleteEditRejectsKeepBaseWithoutTouchingInput() {
        val records = arr(
            rec(base, clock("A" to 2), null),
            rec(conflict, clock("B" to 2), card("banana")),
        )
        val before = LanMerge.canonical(records)

        val e = assertThrows(IllegalArgumentException::class.java) {
            LanConflict.resolve(records, conflict, ConflictPick.KEEP_BASE, "C")
        }
        assertTrue(e.message.orEmpty().contains("KEEP_BASE"))
        assertTrue(e.message.orEmpty().contains("KEEP_COPY"))
        assertEquals(before, LanMerge.canonical(records))
    }

    /** 主版本是墓碑时没有两条可留，「两条都留」同样必须拒绝，不能被当成恢复副本。 */
    @Test
    fun deleteEditRejectsKeepBothWithoutTouchingInput() {
        val records = arr(
            rec(base, clock("A" to 2), null),
            rec(conflict, clock("B" to 2), card("banana")),
        )
        val before = LanMerge.canonical(records)

        val e = assertThrows(IllegalArgumentException::class.java) {
            LanConflict.resolve(records, conflict, ConflictPick.KEEP_BOTH, "C")
        }
        assertTrue(e.message.orEmpty().contains("KEEP_BOTH"))
        assertTrue(e.message.orEmpty().contains("KEEP_COPY"))
        assertEquals(before, LanMerge.canonical(records))
    }

    /** 删除 vs 编辑只有 KEEP_COPY 可走：副本内容写回主版本，副本本身变墓碑，两侧共用新的 N。 */
    @Test
    fun deleteEditKeepCopyRecoversBaseAtDominantClock() {
        val records = arr(
            rec(base, clock("A" to 2), null),
            rec(conflict, clock("B" to 2), card("banana")),
        )
        val out = LanConflict.resolve(records, conflict, ConflictPick.KEEP_COPY, "C")
        val map = byUid(out.records)

        assertEquals("banana", map.getValue(base).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(conflict)))
        assertEquals(listOf(conflict), out.dead)
        // N = bump(max({A:2},{B:2}), "C")
        for (uid in listOf(base, conflict)) {
            val c = clk(map.getValue(uid))
            assertEquals(2, c.getValue("A"))
            assertEquals(2, c.getValue("B"))
            assertEquals(1, c.getValue("C"))
        }
    }

    /** 恢复后再跟仍持有「删除 + 旧副本」的对端合并：删除不复活，也不重新生成冲突。 */
    @Test
    fun deleteEditKeepCopySurvivesStalePeerMerge() {
        val oldBase = rec(base, clock("A" to 2), null)
        val oldCopy = rec(conflict, clock("B" to 2), card("banana"))
        val resolved = LanConflict.resolve(
            arr(oldBase, oldCopy), conflict, ConflictPick.KEEP_COPY, "C",
        )

        val merged = LanMerge.merge(resolved.records, arr(oldBase, oldCopy))
        val map = byUid(merged.getJSONArray("records"))

        assertEquals("banana", map.getValue(base).getJSONObject("value").getString("front"))
        assertFalse(live(map.getValue(conflict)))
        assertEquals(0, merged.getInt("conflicts"))
        assertEquals(2, merged.getJSONArray("records").length())
    }

    // ---------------- 来源判定 ----------------

    @Test
    fun sideUsesDeterministicUidRecomputeFirst() {
        // canonical("banana") > canonical("apple")，主版本保留电脑内容，副本是手机内容。
        val local = rec(base, clock("A" to 2), card("apple"))
        val peer = rec(base, clock("B" to 2), card("banana"))
        val merged = LanMerge.merge(arr(local), arr(peer)).getJSONArray("records")
        val copy = merged.let { byUid(it) }.entries.first { it.key.contains("~conflict~") }
        val copyValue = copy.value.getJSONObject("value")

        assertEquals(
            LanConflict.SIDE_PEER,
            LanConflict.classifySide(base, copy.key, copyValue, local, peer, false, false),
        )

        // 反过来：本机内容 canonical 更大 → 主版本保留本机内容。
        val local2 = rec(base, clock("A" to 2), card("zebra"))
        val peer2 = rec(base, clock("B" to 2), card("banana"))
        val merged2 = LanMerge.merge(arr(local2), arr(peer2)).getJSONArray("records")
        val copy2 = byUid(merged2).entries.first { it.key.contains("~conflict~") }
        assertEquals(
            LanConflict.SIDE_LOCAL,
            LanConflict.classifySide(base, copy2.key, copy2.value.getJSONObject("value"), local2, peer2, false, false),
        )
    }

    @Test
    fun sideFallsBackToCanonicalOnlyWhenExactlyOneSideMatches() {
        // uid 不是由两侧任何一方的 clock+value 算出来的（模拟历史副本被重新编号）。
        val fake = "$base~conflict~ffffffffffffffffffffffff"
        val local = rec(base, clock("A" to 3), card("apple"))
        val peer = rec(base, clock("B" to 3), card("banana"))

        // 副本内容精确等于本机 payload → 本机是败方 → 对端内容留在主版本。
        assertEquals(
            LanConflict.SIDE_PEER,
            LanConflict.classifySide(
                base, fake, card("apple"), local, peer, inLocal = false, inRemote = false,
            ),
        )
        // 副本内容精确等于对端 payload → 本机内容留在主版本。
        assertEquals(
            LanConflict.SIDE_LOCAL,
            LanConflict.classifySide(
                base, fake, card("banana"), local, peer, inLocal = false, inRemote = false,
            ),
        )

        // 两侧 payload 相同时副本跟谁都"相等"，无法说明是败方哪一侧 → 不猜。
        val same = rec(base, clock("B" to 3), card("apple"))
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(base, fake, card("apple"), local, same, inLocal = true, inRemote = true),
        )

        // 第三方旧副本：两侧都对不上，不能因为"不等于本机"就判成对端 → unknown。
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(
                base, fake, card("cherry"), local, peer, inLocal = false, inRemote = false,
            ),
        )
        // 数组存在性同样不能证明 payload 来源：副本只在对端数组里也不改变结论。
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(
                base, fake, card("cherry"), local, peer, inLocal = false, inRemote = true,
            ),
        )
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(
                base, fake, card("cherry"), local, peer, inLocal = true, inRemote = true,
            ),
        )

        // 连 base 都拿不到时，无论副本出现在哪一侧数组里都判不出来。
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(base, fake, card("x"), null, null, inLocal = false, inRemote = true),
        )
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(base, fake, card("x"), null, null, inLocal = true, inRemote = false),
        )
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(base, fake, card("x"), null, null, inLocal = true, inRemote = true),
        )
    }

    /** 本机仅有墓碑时不能直接判成本机保留；只有副本精确等于对端 payload 才可确认。 */
    @Test
    fun sideTombstoneAloneDoesNotDecideSource() {
        val fake = "$base~conflict~ffffffffffffffffffffffff"
        val localTomb = rec(base, clock("A" to 3), null)

        // 墓碑 + 拿不到对端 live：无法证明副本来自对端 → unknown（旧实现会误判 localKept）。
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(
                base, fake, card("peer-edit"), localTomb, null, inLocal = true, inRemote = true,
            ),
        )

        // 副本精确等于对端 payload：主版本留的是本机删除 → localKept。
        assertEquals(
            LanConflict.SIDE_LOCAL,
            LanConflict.classifySide(
                base, fake, card("peer-edit"), localTomb, rec(base, clock("B" to 3), card("peer-edit")),
                inLocal = true, inRemote = true,
            ),
        )

        // 墓碑 + 对端 payload 也对不上（第三方旧副本）：unknown。
        assertEquals(
            LanConflict.SIDE_UNKNOWN,
            LanConflict.classifySide(
                base, fake, card("stale"), localTomb, rec(base, clock("B" to 3), card("peer-edit")),
                inLocal = true, inRemote = true,
            ),
        )
    }

    // ---------------- 错误分类 ----------------

    @Test
    fun connectTimeoutAndNoRouteAreUnreachableNotClosed() {
        assertEquals(
            SyncErrorKind.UNREACHABLE,
            LanSyncErrors.classify(SyncStage.CONNECT, null, SocketTimeoutException("timeout")),
        )
        assertEquals(
            SyncErrorKind.UNREACHABLE,
            LanSyncErrors.classify(SyncStage.CONNECT, null, NoRouteToHostException("no route")),
        )
        assertEquals(
            SyncErrorKind.UNREACHABLE,
            LanSyncErrors.classify(SyncStage.CONNECT, null, UnknownHostException("bad host")),
        )
        val text = LanSyncErrors.message(SyncErrorKind.UNREACHABLE)
        assertFalse(text.contains("已关闭"))
    }

    @Test
    fun readTimeoutIsReportedAsReadTimeoutNotComputerOff() {
        val kind = LanSyncErrors.classify(SyncStage.READ, null, SocketTimeoutException("timeout"))
        assertEquals(SyncErrorKind.READ_TIMEOUT, kind)
        val text = LanSyncErrors.message(kind)
        assertTrue(text.contains("超时"))
        assertFalse(text.contains("已关闭"))
        assertFalse(text.contains("电脑关闭"))
    }

    @Test
    fun refusedConnectionBecomesNoService() {
        assertEquals(
            SyncErrorKind.NO_SERVICE,
            LanSyncErrors.classify(
                SyncStage.CONNECT, null,
                ConnectException("failed to connect: connect failed: ECONNREFUSED"),
            ),
        )
        // 有的 Android 版本把 errno 只放在错误页正文里。
        assertEquals(
            SyncErrorKind.NO_SERVICE,
            LanSyncErrors.classify(SyncStage.CONNECT, null, ConnectException("failed"), "ECONNREFUSED"),
        )
        assertEquals(
            SyncErrorKind.UNREACHABLE,
            LanSyncErrors.classify(SyncStage.CONNECT, null, ConnectException("failed")),
        )
    }

    @Test
    fun httpStatusesMapToPairRejectedBusyAndHttp() {
        assertEquals(SyncErrorKind.PAIR_REJECTED, LanSyncErrors.classify(SyncStage.READ, 401))
        assertEquals(SyncErrorKind.SERVER_BUSY, LanSyncErrors.classify(SyncStage.READ, 503))
        assertEquals(SyncErrorKind.SERVER_BUSY, LanSyncErrors.classify(SyncStage.READ, 408))
        assertEquals(SyncErrorKind.SERVER_BUSY, LanSyncErrors.classify(SyncStage.READ, 409))
        assertEquals(SyncErrorKind.HTTP, LanSyncErrors.classify(SyncStage.READ, 500))
        assertEquals(SyncErrorKind.HTTP, LanSyncErrors.classify(SyncStage.READ, 404))
        // 401 不区分「过期 / 密钥错误」，文案只让用户重新配对。
        val text = LanSyncErrors.message(SyncErrorKind.PAIR_REJECTED, 401)
        assertTrue(text.contains("401"))
        assertTrue(text.contains("重新"))
    }

    @Test
    fun localAndProtocolFailuresAreSeparated() {
        assertEquals(SyncErrorKind.LOCAL_IO, LanSyncErrors.classify(SyncStage.READ, null, IOException("disk")))
        assertEquals(
            SyncErrorKind.PROTOCOL,
            LanSyncErrors.classify(SyncStage.READ, null, IllegalArgumentException("bad json")),
        )
        assertEquals(
            SyncErrorKind.PROTOCOL,
            LanSyncErrors.classify(SyncStage.READ, null, LanSyncCorruptException("corrupt")),
        )
        assertEquals(
            SyncErrorKind.LOCAL_IO,
            LanSyncErrors.classify(SyncStage.READ, null, LanSyncException("write failed")),
        )
        assertEquals(SyncErrorKind.LOCAL_IO, LanSyncErrors.classifyLocal(IOException("disk")))
        assertEquals(SyncErrorKind.PROTOCOL, LanSyncErrors.classifyLocal(IllegalArgumentException("bad")))
    }
}
