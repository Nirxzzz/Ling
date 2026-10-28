package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.TreeMap

/**
 * 局域网同步合并内核：纯 Kotlin + org.json，与桌面端 JS 实现同一协议。
 *
 * 记录格式：`{uid: string, clock: {deviceId: 正整数}, value: JSONObject|null}`；value 为 null 表示删除墓碑。
 * 知识卡 / 收藏 history 都作为 value 原样搬运，合并内核不解释 payload。
 * clock 的计数是 JS 安全整数（1..[MAX_SAFE_INTEGER]），跨端按 Double 比较不会丢精度。
 *
 * 合并规则：
 *  - 只在一侧出现的 uid 原样保留。
 *  - 同 uid 先逐设备取 max 得到合并 clock；一方逐项 ≥ 另一方且至少一项更大即支配，支配者胜。
 *  - clock 相同且 value 相同 → 合一；clock 相同但 value 不同 → 仍是并发，不当成已同步。
 *  - 并发且 value 不同：按 [canonical] 字典序大的当主版本（删除优先：一方是 null 时主 uid 保持 null）；
 *    另一个非 null 版本复制成 `uid~conflict~<sha256(canonical({uid, 败方原clock, 败方value})) 前 24 hex>`。
 *    主版本与副本的 clock 都取双方逐设备 max。
 *  - 输出 records 按 uid 升序。冲突副本 uid 由败方内容唯一决定，重复同步只合一、不新增。
 *
 * [merge] 的 conflicts 是本次合并**新生成**的冲突副本数；已存在的同名副本不计数。
 * 输入不会被修改：clock 全部重新构造，value 只读引用。
 */
object LanMerge {
    /** 单侧输入上限，超过视为非法批次。 */
    const val MAX_RECORDS = 10_000

    /** JS `Number.MAX_SAFE_INTEGER`；clock 计数与 canonical 的整数协议都以此为上限。 */
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    private val SAFE_INTEGER_BOUND: BigInteger = BigInteger.valueOf(MAX_SAFE_INTEGER)

    private const val CONFLICT_MARK = "~conflict~"
    private const val HASH_HEX = 24
    private const val HEX = "0123456789abcdef"

    private const val DOM_LEFT = 1
    private const val DOM_RIGHT = -1
    private const val DOM_EQUAL = 0

    // ---------------- 对外 API ----------------

    /**
     * 与 JS `canonical` 一致的确定性 JSON 文本：object 键按 UTF-16 字典序升序，数组保序，
     * 数字按 ECMAScript `JSON.stringify` 的最短十进制文本输出，字符串按 `JSON.stringify`（well-formed）转义。
     * 非有限数字（NaN / ±Infinity）按 JS 规则视为非法 JSON 抛 [IllegalArgumentException]；其它类型同样抛。
     */
    fun canonical(value: Any?): String = StringBuilder().also { writeJson(it, value) }.toString()

    /**
     * 合并两侧记录快照，返回 `{records: [...], conflicts: Int}`。
     * 输入不合法（重复 uid、非法 clock、单侧超过 [MAX_RECORDS] 条、非 JSON 记录）时抛 [IllegalArgumentException]。
     */
    fun merge(a: JSONArray, b: JSONArray): JSONObject {
        val left = parseRecords(a, "a")
        val right = parseRecords(b, "b")

        // uid -> 待合并记录。TreeMap 按 uid 升序处理；新副本 uid 总以当前 uid 为前缀，
        // 字典序更大，因此只会在之后处理，不会需要回头重算已产出的记录。
        val pending = TreeMap<String, MutableList<Rec>>()
        for (r in left) pending.getOrPut(r.uid) { ArrayList<Rec>(2) }.add(r)
        for (r in right) pending.getOrPut(r.uid) { ArrayList<Rec>(2) }.add(r)

        val merged = ArrayList<Rec>(pending.size)
        var conflicts = 0
        while (pending.isNotEmpty()) {
            val uid = pending.firstKey()
            val list = pending.remove(uid)!!
            var acc = list[0]
            for (i in 1 until list.size) {
                val step = mergeTwo(acc, list[i])
                acc = step.main
                val copy = step.copy ?: continue
                val existing = pending[copy.uid]
                if (existing == null) {
                    conflicts++          // 真正新增的副本；已存在的同名副本只合一
                    pending[copy.uid] = arrayListOf(copy)
                } else {
                    existing.add(copy)
                }
            }
            merged.add(acc)
        }
        merged.sortBy { it.uid }

        val records = JSONArray()
        for (r in merged) records.put(r.toJson())
        return JSONObject().put("records", records).put("conflicts", conflicts)
    }

    // ---------------- 记录 / 时钟 ----------------

    private class Rec(val uid: String, val clock: TreeMap<String, Long>, val value: JSONObject?) {
        fun withClock(c: TreeMap<String, Long>) = Rec(uid, c, value)

        fun toJson(): JSONObject = JSONObject()
            .put("uid", uid)
            .put("clock", LanMerge.clockJson(clock))
            .put("value", value ?: JSONObject.NULL)
    }

    private class Step(val main: Rec, val copy: Rec?)

    private fun parseRecords(arr: JSONArray, side: String): List<Rec> {
        if (arr.length() > MAX_RECORDS) {
            throw IllegalArgumentException("LanMerge: $side has ${arr.length()} records, max $MAX_RECORDS")
        }
        val out = ArrayList<Rec>(arr.length())
        val seen = HashSet<String>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: throw IllegalArgumentException("LanMerge: $side[$i] is not a JSON object")
            val rawUid = o.opt("uid")
            if (rawUid !is String || rawUid.isEmpty()) {
                throw IllegalArgumentException("LanMerge: $side[$i] has no valid uid")
            }
            if (!seen.add(rawUid)) {
                throw IllegalArgumentException("LanMerge: duplicate uid in $side: $rawUid")
            }
            val rawClock = o.opt("clock")
            if (rawClock !is JSONObject) {
                throw IllegalArgumentException("LanMerge: $side[$i] clock is not an object")
            }
            val rawValue = o.opt("value")
            val value = when {
                rawValue == null || rawValue == JSONObject.NULL -> null
                rawValue is JSONObject -> rawValue
                else -> throw IllegalArgumentException("LanMerge: $side[$i] value is not an object or null")
            }
            out.add(Rec(rawUid, parseClock(rawClock, "$side[$i]"), value))
        }
        return out
    }

    private fun parseClock(o: JSONObject, where: String): TreeMap<String, Long> {
        if (o.length() == 0) throw IllegalArgumentException("LanMerge: $where clock is empty")
        val clock = TreeMap<String, Long>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (id.isEmpty()) throw IllegalArgumentException("LanMerge: $where has an empty deviceId")
            val v = positiveLong(o.opt(id))
                ?: throw IllegalArgumentException(
                    "LanMerge: $where clock[$id] is not a positive safe integer (max $MAX_SAFE_INTEGER)"
                )
            clock[id] = v
        }
        return clock
    }

    private fun positiveLong(v: Any?): Long? {
        if (v !is Number) return null
        return try {
            val n = BigDecimal(v.toString()).stripTrailingZeros()
            if (n.scale() > 0) return null
            n.longValueExact().takeIf { it in 1L..MAX_SAFE_INTEGER }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 本机写入时把 [deviceId] 的 clock +1，返回新 clock（不改入参）；新设备从 1 开始。
     * 结果会超过 [MAX_SAFE_INTEGER] 时抛 [IllegalArgumentException]，不做静默封顶——否则本机后续
     * 写入会永远停在同一个计数上，另一端无法据此判定新旧。
     */
    fun bump(clock: Map<String, Long>, deviceId: String): TreeMap<String, Long> {
        require(deviceId.isNotEmpty()) { "LanMerge: empty deviceId" }
        val out = TreeMap<String, Long>(clock)
        val n = out[deviceId] ?: 0L
        if (n < 0 || n >= MAX_SAFE_INTEGER) {
            throw IllegalArgumentException(
                "LanMerge: clock[$deviceId] = $n cannot be bumped within 0..$MAX_SAFE_INTEGER"
            )
        }
        out[deviceId] = n + 1
        return out
    }

    private fun clockJson(clock: Map<String, Long>): JSONObject {
        val o = JSONObject()
        for ((k, v) in clock) o.put(k, v)
        return o
    }

    private fun maxClock(a: Map<String, Long>, b: Map<String, Long>): TreeMap<String, Long> {
        val m = TreeMap<String, Long>(a)
        for ((k, v) in b) {
            val old = m[k]
            if (old == null || v > old) m[k] = v
        }
        return m
    }

    /** 1 = a 支配，-1 = b 支配，0 = 完全相同，其它 = 并发（各自有更大的维度）。 */
    private fun dominance(a: Map<String, Long>, b: Map<String, Long>): Int {
        var aBigger = false
        var bBigger = false
        for (k in a.keys + b.keys) {
            val x = a[k] ?: 0L
            val y = b[k] ?: 0L
            if (x > y) aBigger = true else if (y > x) bBigger = true
        }
        return when {
            aBigger && bBigger -> 2
            aBigger -> DOM_LEFT
            bBigger -> DOM_RIGHT
            else -> DOM_EQUAL
        }
    }

    // ---------------- 冲突解决 ----------------

    private fun mergeTwo(x: Rec, y: Rec): Step {
        val clock = maxClock(x.clock, y.clock)
        return when (dominance(x.clock, y.clock)) {
            DOM_LEFT -> Step(x.withClock(clock), null)
            DOM_RIGHT -> Step(y.withClock(clock), null)
            else -> {
                if (sameValue(x.value, y.value)) Step(x.withClock(clock), null)
                else concurrent(x, y, clock)
            }
        }
    }

    private fun concurrent(x: Rec, y: Rec, clock: TreeMap<String, Long>): Step {
        val vx = x.value
        val vy = y.value
        if (vx == null && vy == null) return Step(Rec(x.uid, clock, null), null)
        // 删除 vs 编辑并发：主 uid 保持墓碑，编辑版本落成冲突副本。
        if (vx == null) return Step(Rec(x.uid, clock, null), conflictCopy(x.uid, y, clock))
        if (vy == null) return Step(Rec(x.uid, clock, null), conflictCopy(x.uid, x, clock))
        return if (canonical(vx) >= canonical(vy)) {
            Step(Rec(x.uid, clock, vx), conflictCopy(x.uid, y, clock))
        } else {
            Step(Rec(x.uid, clock, vy), conflictCopy(x.uid, x, clock))
        }
    }

    private fun conflictCopy(uid: String, loser: Rec, clock: TreeMap<String, Long>): Rec? {
        val v = loser.value ?: return null
        return Rec(conflictUid(uid, loser.clock, v), clock, v)
    }

    /**
     * 冲突副本的确定性 uid：`<baseUid>~conflict~<sha256(canonical({uid, clock, value})) 前 24 hex>`。
     * [clock] 必须是**败方自己**的 clock、[value] 是败方的 payload（与桌面端 `lan-merge.js` 一致）。
     *
     * 单独暴露出来给冲突面板做「确定性 uid 重算」：拿两侧各自的 clock+value 重算，谁重算出的
     * uid 等于现有副本，谁就是败方，另一侧即主版本来源。合并内核本身仍按同一实现生成副本。
     */
    fun conflictUid(baseUid: String, clock: Map<String, Long>, value: JSONObject): String {
        val seed = JSONObject()
            .put("uid", baseUid)
            .put("clock", clockJson(clock))
            .put("value", value)
        return baseUid + CONFLICT_MARK + sha256(canonical(seed)).take(HASH_HEX)
    }

    private fun sameValue(a: JSONObject?, b: JSONObject?): Boolean = canonical(a) == canonical(b)

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val n = b.toInt() and 0xFF
            sb.append(HEX[(n ushr 4) and 0xF]).append(HEX[(n and 0xF)])
        }
        return sb.toString()
    }

    // ---------------- canonical ----------------

    private fun writeJson(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> sb.append(numberText(v))
            is JSONObject -> {
                val keys = ArrayList<String>(v.length())
                val it = v.keys()
                while (it.hasNext()) keys.add(it.next())
                keys.sort()
                sb.append('{')
                for (i in keys.indices) {
                    if (i > 0) sb.append(',')
                    writeString(sb, keys[i])
                    sb.append(':')
                    writeJson(sb, v.opt(keys[i]))
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) {
                    if (i > 0) sb.append(',')
                    writeJson(sb, v.opt(i))
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("LanMerge.canonical: not a JSON value: " + v.javaClass.name)
        }
    }

    /**
     * 与 ECMAScript `JSON.stringify(number)` 相同的数字文本。
     *
     * 先对齐到「设备端 Double」：org.json 在 JVM 上可能把整数解析成 Long/BigInteger、把小数解析成
     * BigDecimal，而桌面端只见过 wire 文本解析出来的 Double。安全整数（|n| ≤ [MAX_SAFE_INTEGER]）直接
     * 输出十进制，避免多绕一次 double 丢位；其余一律转 double。非有限值抛错（JS 的 canonical 同样拒绝）。
     */
    private fun numberText(n: Number): String = when (n) {
        is Byte -> n.toString()
        is Short -> n.toString()
        is Int -> n.toString()
        is Long -> if (n in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) n.toString() else doubleText(n.toDouble())
        is BigInteger ->
            if (n.abs() <= SAFE_INTEGER_BOUND) n.toString() else doubleText(n.toDouble())
        // Float 的 wire 文本由 Float.toString 产生，桌面端解析的是这份文本，不是 double 的二进制展开。
        is Float -> doubleText(n.toString().toDouble())
        else -> doubleText(n.toDouble())
    }

    /**
     * Double → ECMAScript 数字文本：`[1e-6, 1e21)` 用普通十进制，区间外用小写 `e` 与带符号指数，
     * 尾数不带多余的 `.0`，0（含 -0）统一写成 `0`。
     *
     * 位数来自 [BigDecimal.valueOf]（即 `Double.toString` 的最短往返表示），不做 Ryu 级别的重写；
     * 业务只要求 FSRS 八位小数与安全整数。极少数 double 的最短文本可能与 JS 引擎差一位（例如次正规数），
     * 这是已知并接受的范围。
     */
    private fun doubleText(d: Double): String {
        if (!d.isFinite()) {
            throw IllegalArgumentException("LanMerge.canonical: not a JSON number: $d")
        }
        if (d == 0.0) return "0"

        val bd = BigDecimal.valueOf(d).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val k = digits.length
        val n = k - bd.scale()
        val sb = StringBuilder(k + 8)
        if (bd.signum() < 0) sb.append('-')
        when {
            // k ≤ n ≤ 21：整数，补零
            n in k..21 -> {
                sb.append(digits)
                repeat(n - k) { sb.append('0') }
            }
            // 0 < n ≤ 21：小数点落在数字中间
            n in 1..21 -> sb.append(digits, 0, n).append('.').append(digits, n, k)
            // -6 < n ≤ 0：0.000…
            n in -5..0 -> {
                sb.append("0.")
                repeat(-n) { sb.append('0') }
                sb.append(digits)
            }
            // 其余用指数；指数 = n - 1，正指数带 '+'
            else -> {
                sb.append(digits[0])
                if (k > 1) sb.append('.').append(digits, 1, k)
                sb.append('e')
                val exp = n - 1
                if (exp >= 0) sb.append('+')
                sb.append(exp)
            }
        }
        return sb.toString()
    }

    /** 与 `JSON.stringify` 相同的转义：不转义 `/`，不转义非 ASCII；孤立代理项转成 \uXXXX。 */
    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> {
                    sb.append("\\u")
                    hex4(sb, c.code)
                }
                Character.isHighSurrogate(c) -> {
                    val next = i + 1
                    if (next < s.length && Character.isLowSurrogate(s[next])) {
                        sb.append(c).append(s[next])
                        i++
                    } else {
                        sb.append("\\u")
                        hex4(sb, c.code)
                    }
                }
                Character.isLowSurrogate(c) -> {
                    sb.append("\\u")
                    hex4(sb, c.code)
                }
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun hex4(sb: StringBuilder, v: Int) {
        sb.append(HEX[(v ushr 12) and 0xF])
            .append(HEX[(v ushr 8) and 0xF])
            .append(HEX[(v ushr 4) and 0xF])
            .append(HEX[(v and 0xF)])
    }
}
