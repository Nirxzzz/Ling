package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/** LanMerge：局域网同步合并只测消费者可见行为（并集、删除墓碑、幂等、对称、支配）。 */
class LanMergeTest {

    private fun clock(vararg pairs: Pair<String, Int>): JSONObject =
        JSONObject().apply { for ((k, v) in pairs) put(k, v) }

    /** 知识卡 / 收藏 history 都用普通 payload，合并内核不解释字段。 */
    private fun card(front: String, back: String = "b"): JSONObject =
        JSONObject().put("front", front).put("back", back)

    private fun rec(uid: String, clock: JSONObject, value: JSONObject?): JSONObject =
        JSONObject().put("uid", uid).put("clock", clock).put("value", value ?: JSONObject.NULL)

    private fun arr(vararg r: JSONObject): JSONArray = JSONArray().apply { for (x in r) put(x) }

    private fun records(out: JSONObject): JSONArray = out.getJSONArray("records")

    private fun recordAt(out: JSONObject, i: Int): JSONObject = records(out).getJSONObject(i)

    private fun byUid(out: JSONObject): Map<String, JSONObject> {
        val r = records(out)
        return (0 until r.length()).associate { i -> r.getJSONObject(i).getString("uid") to r.getJSONObject(i) }
    }

    private fun shape(out: JSONObject): String = LanMerge.canonical(records(out))

    // ---------------- 并集 ----------------

    @Test
    fun unionKeepsBothSidesAndSortsByUid() {
        val out = LanMerge.merge(
            arr(rec("b", clock("A" to 1), card("B"))),
            arr(rec("a", clock("B" to 2), card("A")))
        )
        assertEquals(2, records(out).length())
        assertEquals("a", recordAt(out, 0).getString("uid"))
        assertEquals("b", recordAt(out, 1).getString("uid"))
        assertEquals(2, recordAt(out, 0).getJSONObject("clock").getInt("B"))
        assertEquals(0, out.getInt("conflicts"))
    }

    @Test
    fun unionOfEmptySidesIsEmpty() {
        val out = LanMerge.merge(JSONArray(), JSONArray())
        assertEquals(0, records(out).length())
        assertEquals(0, out.getInt("conflicts"))
    }

    // ---------------- 支配 ----------------

    @Test
    fun dominatingClockWins() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 3), card("new"))),
            arr(rec("k", clock("A" to 2), card("old")))
        )
        assertEquals(1, records(out).length())
        assertEquals("new", recordAt(out, 0).getJSONObject("value").getString("front"))
        assertEquals(3, recordAt(out, 0).getJSONObject("clock").getInt("A"))
        assertEquals(0, out.getInt("conflicts"))
    }

    @Test
    fun dominatingClockWinsAcrossDevices() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 2, "B" to 3), card("new"))),
            arr(rec("k", clock("A" to 1, "B" to 3), card("old")))
        )
        assertEquals(1, records(out).length())
        assertEquals("new", recordAt(out, 0).getJSONObject("value").getString("front"))
        assertEquals(2, recordAt(out, 0).getJSONObject("clock").getInt("A"))
        assertEquals(3, recordAt(out, 0).getJSONObject("clock").getInt("B"))
    }

    @Test
    fun deleteDominatingEditLeavesOnlyTombstone() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 5), null)),
            arr(rec("k", clock("A" to 4), card("edit")))
        )
        assertEquals(1, records(out).length())
        assertTrue(recordAt(out, 0).isNull("value"))
        assertEquals(0, out.getInt("conflicts"))
    }

    // ---------------- 并发 / 冲突副本 ----------------

    @Test
    fun concurrentDeleteKeepsTombstoneAndEditAsConflict() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 2), null)),
            arr(rec("k", clock("B" to 1), card("edit")))
        )
        assertEquals(2, records(out).length())
        val main = recordAt(out, 0)
        assertEquals("k", main.getString("uid"))
        assertTrue(main.isNull("value"))
        assertEquals(2, main.getJSONObject("clock").getInt("A"))
        assertEquals(1, main.getJSONObject("clock").getInt("B"))

        val copy = recordAt(out, 1)
        assertTrue(copy.getString("uid").startsWith("k~conflict~"))
        assertEquals("edit", copy.getJSONObject("value").getString("front"))
        assertEquals(2, copy.getJSONObject("clock").getInt("A"))
        assertEquals(1, copy.getJSONObject("clock").getInt("B"))
        assertEquals(1, out.getInt("conflicts"))
    }

    @Test
    fun concurrentEditsKeepLoserAsConflictCopy() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 2, "B" to 1), card("apple"))),
            arr(rec("k", clock("A" to 1, "B" to 2), card("banana")))
        )
        assertEquals(2, records(out).length())
        val main = byUid(out)["k"]!!
        val copy = records(out).getJSONObject(1)
        assertEquals("banana", main.getJSONObject("value").getString("front"))   // canonical 更大
        assertEquals("apple", copy.getJSONObject("value").getString("front"))
        assertEquals(2, main.getJSONObject("clock").getInt("A"))
        assertEquals(2, main.getJSONObject("clock").getInt("B"))
        assertEquals(2, copy.getJSONObject("clock").getInt("A"))
        assertEquals(2, copy.getJSONObject("clock").getInt("B"))
        assertEquals(1, out.getInt("conflicts"))

        val suffix = copy.getString("uid").removePrefix("k~conflict~")
        assertEquals(24, suffix.length)
        assertTrue(suffix.all { it in "0123456789abcdef" })
    }

    @Test
    fun sameClockSameValueCollapses() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 7), card("same"))),
            arr(rec("k", clock("A" to 7), card("same")))
        )
        assertEquals(1, records(out).length())
        assertEquals(0, out.getInt("conflicts"))
    }

    @Test
    fun sameClockDifferentValueIsKeptAsConcurrency() {
        val out = LanMerge.merge(
            arr(rec("k", clock("A" to 7), card("v1"))),
            arr(rec("k", clock("A" to 7), card("v2")))
        )
        assertEquals(2, records(out).length())          // 不当成已同步
        assertEquals(1, out.getInt("conflicts"))
        val main = byUid(out)["k"]!!
        val copy = records(out).getJSONObject(1)
        assertNotEquals(
            LanMerge.canonical(main.getJSONObject("value")),
            LanMerge.canonical(copy.getJSONObject("value"))
        )
        assertEquals(7, copy.getJSONObject("clock").getInt("A"))
    }

    // ---------------- 幂等 / 对称 ----------------

    @Test
    fun mergingResultAgainAddsNothing() {
        val a = arr(rec("k", clock("A" to 2, "B" to 1), card("apple")))
        val b = arr(rec("k", clock("A" to 1, "B" to 2), card("banana")))
        val first = LanMerge.merge(a, b)

        val again = LanMerge.merge(records(first), records(first))
        assertEquals(shape(first), shape(again))
        assertEquals(0, again.getInt("conflicts"))

        val withOld = LanMerge.merge(records(first), b)
        assertEquals(shape(first), shape(withOld))
        assertEquals(0, withOld.getInt("conflicts"))
    }

    @Test
    fun deleteConflictIsIdempotent() {
        val a = arr(rec("k", clock("A" to 2), null))
        val b = arr(rec("k", clock("B" to 1), card("edit")))
        val first = LanMerge.merge(a, b)
        val again = LanMerge.merge(records(first), records(first))
        assertEquals(shape(first), shape(again))
        assertEquals(2, records(again).length())
    }

    @Test
    fun mergeIsSymmetric() {
        val a = arr(
            rec("k", clock("A" to 2, "B" to 1), card("apple")),
            rec("d", clock("A" to 2), null),
            rec("x", clock("A" to 1), card("only-a"))
        )
        val b = arr(
            rec("k", clock("A" to 1, "B" to 2), card("banana")),
            rec("d", clock("B" to 1), card("edit")),
            rec("y", clock("B" to 1), card("only-b"))
        )
        val ab = LanMerge.merge(a, b)
        val ba = LanMerge.merge(b, a)
        assertEquals(shape(ab), shape(ba))
        assertEquals(ab.getInt("conflicts"), ba.getInt("conflicts"))
        assertEquals(2, ab.getInt("conflicts"))
    }

    @Test
    fun mergeDoesNotMutateInputs() {
        val a = arr(rec("k", clock("A" to 2, "B" to 1), card("apple")))
        val b = arr(rec("k", clock("A" to 1, "B" to 2), card("banana")))
        val before = LanMerge.canonical(a) + "|" + LanMerge.canonical(b)
        LanMerge.merge(a, b)
        assertEquals(before, LanMerge.canonical(a) + "|" + LanMerge.canonical(b))
    }

    // ---------------- validate ----------------

    @Test
    fun rejectsDuplicateUidInOneBatch() {
        val bad = arr(
            rec("k", clock("A" to 1), card("one")),
            rec("k", clock("A" to 2), card("two"))
        )
        assertThrows(IllegalArgumentException::class.java) { LanMerge.merge(bad, JSONArray()) }
    }

    @Test
    fun rejectsIllegalClocks() {
        val badClocks = listOf(
            clock("A" to 0),
            clock("A" to -1),
            JSONObject().put("A", 1.5),
            JSONObject().put("A", "2"),
            JSONObject().put("A", JSONObject.NULL),
            JSONObject()
        )
        for (bad in badClocks) {
            assertThrows(IllegalArgumentException::class.java) {
                LanMerge.merge(arr(rec("k", bad, card("x"))), JSONArray())
            }
        }
    }

    @Test
    fun rejectsNonJsonRecordsAndValues() {
        assertThrows(IllegalArgumentException::class.java) {
            LanMerge.merge(JSONArray().put("not-a-record"), JSONArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanMerge.merge(
                arr(JSONObject().put("uid", "k").put("clock", clock("A" to 1)).put("value", "text")),
                JSONArray()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanMerge.merge(arr(JSONObject().put("clock", clock("A" to 1))), JSONArray())
        }
    }

    @Test
    fun rejectsMoreThanTenThousandRecords() {
        val big = JSONArray()
        for (i in 0..LanMerge.MAX_RECORDS) big.put(rec("u$i", clock("A" to 1), card("x")))
        assertThrows(IllegalArgumentException::class.java) { LanMerge.merge(big, JSONArray()) }
    }

    @Test
    fun acceptsClockAtSafeIntegerLimitAndRejectsBeyond() {
        val atLimit = JSONObject().put("A", LanMerge.MAX_SAFE_INTEGER)
        val ok = LanMerge.merge(arr(rec("k", atLimit, card("x"))), JSONArray())
        assertEquals(LanMerge.MAX_SAFE_INTEGER, recordAt(ok, 0).getJSONObject("clock").getLong("A"))

        // 2^53 起 JS 侧已无法逐整数表示，协议直接拒绝，不做静默截断。
        val beyond = listOf(
            JSONObject().put("A", LanMerge.MAX_SAFE_INTEGER + 1),
            JSONObject().put("A", 9.007199254740992E15)
        )
        for (bad in beyond) {
            assertThrows(IllegalArgumentException::class.java) {
                LanMerge.merge(arr(rec("k", bad, card("x"))), JSONArray())
            }
        }
    }

    @Test
    fun bumpAdvancesClockAndRefusesOverflow() {
        val start = mapOf("A" to 7L)
        val next = LanMerge.bump(start, "A")
        assertEquals(8L, next["A"])
        assertEquals(7L, start["A"])                                  // 不改入参
        assertEquals(1L, LanMerge.bump(emptyMap<String, Long>(), "B")["B"])

        assertThrows(IllegalArgumentException::class.java) {
            LanMerge.bump(mapOf("A" to LanMerge.MAX_SAFE_INTEGER), "A")
        }
    }

    // ---------------- canonical ----------------

    @Test
    fun canonicalSortsKeysAndKeepsArrayOrder() {
        val v = JSONObject()
            .put("b", 1)
            .put("a", JSONArray().put(2).put(1))
            .put("c", JSONObject().put("z", true).put("y", JSONObject.NULL))
        assertEquals("{\"a\":[2,1],\"b\":1,\"c\":{\"y\":null,\"z\":true}}", LanMerge.canonical(v))
    }

    @Test
    fun canonicalMatchesJsonStringifyForScalars() {
        assertEquals("null", LanMerge.canonical(null))
        assertEquals("null", LanMerge.canonical(JSONObject.NULL))
        assertEquals("true", LanMerge.canonical(true))
        assertEquals("7", LanMerge.canonical(7))
        assertEquals("1.5", LanMerge.canonical(1.5))
        assertEquals("1", LanMerge.canonical(1.0))
        assertEquals("\"a\\\"b\\\\c\\nd\"", LanMerge.canonical("a\"b\\c\nd"))
        assertEquals("\"中文/保留\"", LanMerge.canonical("中文/保留"))
    }

    /** 数值文本与桌面端 `JSON.stringify` 逐字节一致；expected 由 JS 实跑得到。 */
    @Test
    fun canonicalNumberTextMatchesJsonStringify() {
        assertEquals("0", LanMerge.canonical(-0.0))                   // -0 统一成 0
        assertEquals("0", LanMerge.canonical(0.0))
        assertEquals("1", LanMerge.canonical(1.0))
        assertEquals("0.0001", LanMerge.canonical(1e-4))              // 不是 1.0E-4
        assertEquals("0.000001", LanMerge.canonical(1e-6))
        assertEquals("1e-7", LanMerge.canonical(1e-7))                // 区间外小写 e
        assertEquals("10000000", LanMerge.canonical(1e7))             // 不是 1.0E7
        assertEquals("100000000000000000000", LanMerge.canonical(1e20))
        assertEquals("1e+21", LanMerge.canonical(1e21))               // 正指数带 '+'
        assertEquals("123.456", LanMerge.canonical(123.456))
        assertEquals("9007199254740991", LanMerge.canonical(LanMerge.MAX_SAFE_INTEGER))
    }

    @Test
    fun canonicalNumbersInsidePayloadMatchJs() {
        val v = JSONObject()
            .put("z", 1e21)
            .put("a", -0.0)
            .put("m", 1e-4)
            .put("b", 1e7)
        assertEquals("{\"a\":0,\"b\":10000000,\"m\":0.0001,\"z\":1e+21}", LanMerge.canonical(v))
    }

    /** JVM 解析出来的 Long / BigInteger / BigDecimal 必须先对齐到设备端 Double。 */
    @Test
    fun canonicalJvmParsedNumbersAlignWithDeviceDouble() {
        assertEquals("0.0001", LanMerge.canonical(BigDecimal("0.0001")))
        assertEquals("1e+21", LanMerge.canonical(BigDecimal("1E+21")))
        assertEquals("10000000", LanMerge.canonical(BigInteger("10000000")))
        // 9007199254740993 在 JS 侧解析就是 ...992，canonical 也按 double 输出。
        assertEquals("9007199254740992", LanMerge.canonical(BigInteger("9007199254740993")))
    }

    @Test
    fun canonicalRejectsNonFiniteNumbers() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (v in bad) {
            assertThrows(IllegalArgumentException::class.java) { LanMerge.canonical(v) }
        }
    }

    /**
     * 跨端固定 hash：与桌面端 lan-merge.js 对同一输入实跑结果逐字节一致。
     * 数值故意都用过 JSONObject.numberToString 会漂的形态（1e7 / 1e-4 / -0 / 1e21）。
     */
    @Test
    fun conflictUidAndWinnerValueMatchJs() {
        val a = arr(
            rec(
                "k", clock("A" to 2),
                JSONObject().put("front", "apple")
                    .put("fsrs", JSONObject().put("difficulty", 1e7).put("stability", 1e-4))
            )
        )
        val b = arr(
            rec(
                "k", clock("B" to 2),
                JSONObject().put("front", "banana")
                    .put("fsrs", JSONObject().put("difficulty", -0.0).put("stability", 1e21))
            )
        )
        val out = LanMerge.merge(a, b)
        assertEquals(1, out.getInt("conflicts"))
        assertEquals(2, records(out).length())

        val main = byUid(out)["k"]!!
        assertEquals(
            "{\"front\":\"banana\",\"fsrs\":{\"difficulty\":0,\"stability\":1e+21}}",
            LanMerge.canonical(main.getJSONObject("value"))
        )
        assertEquals(
            "{\"front\":\"apple\",\"fsrs\":{\"difficulty\":10000000,\"stability\":0.0001}}",
            LanMerge.canonical(recordAt(out, 1).getJSONObject("value"))
        )
        assertEquals("k~conflict~ea0c140bec5e917b7acd0272", recordAt(out, 1).getString("uid"))
    }
}
