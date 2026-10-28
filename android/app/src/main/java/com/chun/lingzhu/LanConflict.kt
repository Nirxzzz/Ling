package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/** 用户对一条冲突副本的三选一动作。名字就是落进 sidecar `resolved` 的本地元数据值。 */
enum class ConflictPick {
    KEEP_BASE,
    KEEP_COPY,
    KEEP_BOTH,
}

/**
 * 冲突副本的纯逻辑：uid 拆解、来源判定、三选一解决。
 *
 * 记录格式与 [LanMerge] 完全一致：`{uid, clock:{deviceId: 1..MAX_SAFE_INTEGER}, value: object|null}`。
 * 这里不碰 Android、不碰文件，只做「给定记录数组 → 新记录数组」，方便 JVM 单测直接驱动。
 *
 * ## 解决规则（与顶层契约一致）
 *  - `N = bump(max(base.clock, copy.clock), deviceId)`；
 *  - 存活方 `clock=N value=己方 payload`，败方 `clock=N value=null`；
 *  - `KEEP_BOTH` 两条都留，副本 uid 不变；
 *  - 嵌套副本的 base 是**最后一个** `~conflict~` 前的直接父。
 *
 * 删除 vs 编辑的冲突里，主版本是墓碑、副本才带着唯一活着的内容。此时 `KEEP_BASE`（保留删除）
 * 和 `KEEP_BOTH`（没有两条可留）都是不可能的选项：两者都会在 `bump` 之前抛
 * [IllegalArgumentException]，避免把唯一内容悄悄删掉，也避免「两条都留」名不副实。
 * 只有 `KEEP_COPY` 能把副本内容写回主版本、并把副本本身写成墓碑。
 *
 * 两边用同一个 N，且都在本机 deviceId 上 +1：下一次合并时，败方墓碑一定支配对端仍持有的旧活值，
 * 不会「解决完又复活」。
 */
internal object LanConflict {
    const val MARK = "~conflict~"

    const val KIND_CARDS = "cards"
    const val KIND_HISTORY = "history"

    const val SIDE_LOCAL = "localKept"
    const val SIDE_PEER = "peerKept"
    const val SIDE_UNKNOWN = "unknown"

    fun isCopy(uid: String): Boolean = uid.contains(MARK)

    /** 最后一个 `~conflict~` 前的直接父；不是副本返回 null。 */
    fun baseUidOf(uid: String): String? {
        val i = uid.lastIndexOf(MARK)
        return if (i <= 0) null else uid.substring(0, i)
    }

    fun kindOf(uid: String): String? = when {
        uid.startsWith("k:") -> KIND_CARDS
        uid.startsWith("h:") -> KIND_HISTORY
        else -> null
    }

    fun isKnownSide(side: String): Boolean =
        side == SIDE_LOCAL || side == SIDE_PEER || side == SIDE_UNKNOWN

    /** [resolve] 的结果：新记录、被解决的 uid、以及被写成墓碑的 uid（history 要据此清 binding）。 */
    class Resolved(
        val records: JSONArray,
        val baseUid: String,
        val copyUid: String,
        val dead: List<String>,
    )

    /**
     * 按 [pick] 改写 [records] 里的 base / copy 两条记录。输入不会被修改。
     *
     * 非法输入抛 [IllegalArgumentException]：uid 不是副本、找不到 base / copy、副本已经是墓碑、
     * clock 非法、deviceId 为空。主版本是墓碑时 `KEEP_BASE` / `KEEP_BOTH` 同样非法（先于 bump 抛错），
     * 因为那会把副本里唯一活着的内容一起删掉；这种情况只能用 `KEEP_COPY` 恢复。
     */
    fun resolve(
        records: JSONArray,
        copyUid: String,
        pick: ConflictPick,
        deviceId: String,
    ): Resolved {
        require(deviceId.isNotEmpty()) { "LanConflict: empty deviceId" }
        require(isCopy(copyUid)) { "LanConflict: not a conflict copy: $copyUid" }
        val baseUid = baseUidOf(copyUid)
            ?: throw IllegalArgumentException("LanConflict: no base uid in $copyUid")

        var base: JSONObject? = null
        var copy: JSONObject? = null
        for (i in 0 until records.length()) {
            val o = records.optJSONObject(i)
                ?: throw IllegalArgumentException("LanConflict: records[$i] is not an object")
            when (o.opt("uid") as? String) {
                baseUid -> base = o
                copyUid -> copy = o
            }
        }
        val b = base ?: throw IllegalArgumentException("LanConflict: base record not found: $baseUid")
        val c = copy ?: throw IllegalArgumentException("LanConflict: conflict copy not found: $copyUid")

        val baseValue = valueOf(b)
        val copyValue = valueOf(c)
            ?: throw IllegalArgumentException("LanConflict: conflict copy is already tombstoned: $copyUid")

        // 删除 vs 编辑：主版本是墓碑、副本是唯一活内容。保留删除（KEEP_BASE）或「两条都留」
        // （KEEP_BOTH）都会丢掉唯一内容，必须在 bump / 产出新记录之前拒绝，让上层保持原状。
        if (baseValue == null && pick != ConflictPick.KEEP_COPY) {
            throw IllegalArgumentException(
                "LanConflict: ${pick.name} impossible: base $baseUid is deleted; " +
                    "only KEEP_COPY can recover the copy"
            )
        }

        val n = LanMerge.bump(maxClock(clockOf(b), clockOf(c)), deviceId)

        val newBase: JSONObject?
        val newCopy: JSONObject?
        when (pick) {
            ConflictPick.KEEP_BASE -> {
                newBase = baseValue
                newCopy = null
            }
            ConflictPick.KEEP_COPY -> {
                newBase = copyValue
                newCopy = null
            }
            ConflictPick.KEEP_BOTH -> {
                newBase = baseValue
                newCopy = copyValue
            }
        }

        val out = JSONArray()
        for (i in 0 until records.length()) {
            val o = records.getJSONObject(i)
            when (o.opt("uid") as? String) {
                baseUid -> out.put(rewrite(o, n, newBase))
                copyUid -> out.put(rewrite(o, n, newCopy))
                else -> out.put(o)
            }
        }
        val dead = ArrayList<String>(2)
        if (newBase == null) dead += baseUid
        if (newCopy == null) dead += copyUid
        return Resolved(out, baseUid, copyUid, dead)
    }

    /**
     * 判定副本的「来源侧」：主版本保住的是本机（localKept）还是对端（peerKept）的内容。
     *
     * 只有两种**可证实**的证据，其余一律 [SIDE_UNKNOWN]：
     *  1. 确定性 uid 重算——拿本地 / 远端各自的 base clock+value 重算副本 uid，恰好命中一侧时，
     *     该侧就是败方（命中两侧或都不命中则继续往下判）；
     *  2. canonical value 精确相等——副本保存的是**败方** payload，canonical 恰好等于本地 payload
     *     说明本机输了（对端留下 → [SIDE_PEER]），恰好等于对端 payload 说明对端输了
     *     （本机留下 → [SIDE_LOCAL]）。两边相等、或两边都不等（第三方旧副本）时无法确认，返回 unknown。
     *
     * 刻意不做的事：
     *  - 不用「本机存在墓碑」直接推断归属：墓碑只说明主版本是删除，不证明副本 payload 来自哪一侧；
     *  - 不看副本出现在哪一侧数组：数组存在性不能证明 payload 来源；
     *  - 不做「不等于本地就等于对端」的反向猜测。
     *
     * [inLocal] / [inRemote] 为兼容调用方保留，不参与判定。
     */
    @Suppress("UNUSED_PARAMETER")
    fun classifySide(
        baseUid: String,
        copyUid: String,
        copyValue: JSONObject?,
        localBase: JSONObject?,
        remoteBase: JSONObject?,
        inLocal: Boolean,
        inRemote: Boolean,
    ): String {
        val localLoses = localBase != null && recomputesTo(baseUid, copyUid, localBase)
        val peerLoses = remoteBase != null && recomputesTo(baseUid, copyUid, remoteBase)
        if (localLoses != peerLoses) return if (localLoses) SIDE_PEER else SIDE_LOCAL

        val cv = copyValue?.let(LanMerge::canonical) ?: return SIDE_UNKNOWN
        val lv = liveValue(localBase)?.let(LanMerge::canonical)
        val rv = liveValue(remoteBase)?.let(LanMerge::canonical)
        val matchesLocal = lv != null && lv == cv
        val matchesRemote = rv != null && rv == cv
        return when {
            matchesLocal && !matchesRemote -> SIDE_PEER
            matchesRemote && !matchesLocal -> SIDE_LOCAL
            else -> SIDE_UNKNOWN
        }
    }

    private fun liveValue(record: JSONObject?): JSONObject? =
        if (record == null) null else valueOf(record)

    private fun recomputesTo(baseUid: String, copyUid: String, rec: JSONObject): Boolean {
        val value = valueOf(rec) ?: return false
        val clock = try {
            clockOf(rec)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            LanMerge.conflictUid(baseUid, clock, value) == copyUid
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun rewrite(o: JSONObject, clock: TreeMap<String, Long>, value: JSONObject?): JSONObject {
        val c = JSONObject()
        for ((k, v) in clock) c.put(k, v)
        return JSONObject()
            .put("uid", o.getString("uid"))
            .put("clock", c)
            .put("value", value ?: JSONObject.NULL)
    }

    private fun valueOf(o: JSONObject): JSONObject? = when (val v = o.opt("value")) {
        null, JSONObject.NULL -> null
        is JSONObject -> v
        else -> throw IllegalArgumentException("LanConflict: value is not an object or null")
    }

    private fun clockOf(o: JSONObject): TreeMap<String, Long> {
        val c = o.opt("clock") as? JSONObject
            ?: throw IllegalArgumentException("LanConflict: clock is not an object")
        if (c.length() == 0) throw IllegalArgumentException("LanConflict: clock is empty")
        val out = TreeMap<String, Long>()
        val keys = c.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = c.opt(k)
            if (v !is Number) throw IllegalArgumentException("LanConflict: clock[$k] is not a number")
            val n = v.toLong()
            if (n < 1L || n > LanMerge.MAX_SAFE_INTEGER) {
                throw IllegalArgumentException("LanConflict: clock[$k] out of range: $n")
            }
            out[k] = n
        }
        return out
    }

    private fun maxClock(a: Map<String, Long>, b: Map<String, Long>): TreeMap<String, Long> {
        val m = TreeMap<String, Long>(a)
        for ((k, v) in b) {
            val old = m[k]
            if (old == null || v > old) m[k] = v
        }
        return m
    }
}
