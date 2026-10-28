package com.chun.lingzhu

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.TreeMap
import java.util.UUID

/**
 * 局域网同步的业务存储：知识卡（全部）+ history（只同步收藏的）。
 *
 * ## 数据面
 * 本地副本 `filesDir/lan-sync.json`：`{deviceId, records, bindings, stamps, resolved, sides}`。
 *  - `records`：本机已知的 CRDT 记录 `{uid, clock:{deviceId: 1..9007199254740991}, value: object|null}`，uid 形如
 *    `k:<uuid>` / `h:<uuid>`，冲突副本是 `k:<uuid>~conflict~<24 hex>`（[LanMerge] 生成）。
 *    clock 的 key 接受 1..64 个 ASCII 字母 / 数字 / '-'（兼容本机旧数字与桌面 UUID），计数是 JS 安全整数。
 *  - `bindings`：`uid -> 本地 id`（知识卡 id / history id）。
 *  - `stamps`：`h:<uuid> -> createdAt`。history 的 payload 需要稳定的 createdAt，而本地 id 在投影时可能
 *    偏移（远端条目只能分到 max+1），且取消收藏后 record 变成 null 墓碑、值里没有 createdAt 了，
 *    所以首次绑定时快照一份；payload 的 createdAt 只认这个快照，**不按本地 id 反推**。
 *  - `resolved` / `sides`：后加的**本地可选元数据**，旧版本直接忽略；只存在 sidecar，
 *    绝不进 records / 同步 payload。`resolved` 记用户已处理过的副本（KEEP_BASE / KEEP_COPY / KEEP_BOTH），
 *    `sides` 记 apply 时判定的副本来源（localKept / peerKept）。
 *
 * sidecar 损坏时 [stateLocked] 直接抛错，**不会重建空状态**（否则下一次 export 会把远端记录全当删除）。
 *
 * ## 观察 / 应用
 * [export] 读取 Store 的当前内存（经 FileMemo），与 sidecar 的 binding + last value 逐条比对：
 *  - 新条目分新 UUID，clock 写本机 1；
 *  - 内容变了才把本机 clock +1；值相同不增；
 *  - 绑定消失（卡被删）或收藏被取消 → 写 `null` 墓碑并把本机 clock +1。history 只取消收藏，不删普通记录；
 *    知识卡墓碑会把本地卡移除。
 *
 * [apply] 先观察本地（保证并发期间本地改动也在本次合并里），再 `LanMerge.merge`，然后整体投影回两个 Store。
 * 投影按 uid 找 binding：能保留的本地 id 原样保留，不能保留的分配「max+1 / 本机 id 水位」之后的新 id
 * （不复用、不截断）；**同 dedupKey 但不同 uid 的卡全部保留**，所以冲突副本不会丢；副本的 front/deck
 * 绝不改写（改了会反复触发不一致）。
 *
 * ## 事务
 * 业务文件是 FileMemo 异步落盘的，所以写盘按「journal → 业务缓存/队列 → sidecar → 屏障 → 删 journal」：
 *  1. `lan-sync.txn.json` 用 temp + fsync + rename 原子写（含投影后的 knowledge/history/state）；
 *  2. 两个 Store 的 `putFromSync` 走 FileMemo 队列，sidecar 原子写；
 *  3. `FileMemo.flush()` 等所有写真正落盘；
 *  4. 落盘确认后才删 journal 并返回成功。任一步失败都抛异常、保留 journal，下次读取前幂等重放。
 *
 * journal 内容坏了（JSON / 版本 / 结构不合法）时，原文件被隔离为 `lan-sync.txn.json.corrupt`，
 * 本进程普通读不再反复崩溃，但 export / apply 会明确拒绝，且在人工恢复（移除隔离文件）前不会写新
 * journal / sidecar 覆盖证据。journal 正确但重放写盘失败则保留原文件，下次读取前重试。
 *
 * ## 锁与线程
 * 所有导出/应用/恢复，以及两个 Store 的读写，都锁同一个 `FileMemo` 监视器，避免「观察 → 写回」之间
 * 被别的 Store 改动插进来。`export()` / `apply()` 会做同步磁盘写（journal/sidecar/flush），**不要在
 * 主线程调用**；建议放在专用单线程或 `Dispatchers.IO`。持锁期间会短暂阻塞历史页/知识卡页的列表读取，
 * 但这是「同步期间不许本地改动插队」的代价。
 */
class LanSyncStore(ctx: Context) {

    private val app: Context = ctx.applicationContext
    private val sidecar: File = File(app.filesDir, FILE)
    private val journal: File = File(app.filesDir, JOURNAL)

    /** 观察当前本地状态（会分配新 uid/绑定），但不改业务数据。返回给对端发送的记录数组。 */
    fun export(): JSONArray = synchronized(FileMemo) {
        recoverLocked(app)
        ensureNotBlocked()
        val obs = observeLocked()
        // 本地记录也必须先过与远端同一套校验：宁可直接报错，也不截断 / 不把超限数据发出去。
        validateRecords(obs.json, "local")
        val cur = stateLocked()
        if (!sidecar.exists() || stateJson(obs.state).toString() != stateJson(cur).toString()) {
            writeAtomic(sidecar, stateJson(obs.state).toString())
            states[sidecar.path] = obs.state
        }
        obs.json
    }

    /**
     * 合并远端快照并落地。返回 `{records, conflicts, cards, history, cardsTotal, stars}`：
     *  - `cards` / `history`：各自 `{added, updated, deleted, restored, conflicts, before, after}`，
     *    统计口径是**本机同一次 apply 的 before / after**（before = 观察后的本机记录，after = 合并结果）；
     *  - `conflicts`：两种记录新增副本数之和（保留的扁平字段）；
     *  - `cardsTotal` / `stars`：投影后的知识卡张数 / 收藏条数（兼容旧界面的扁平字段）。
     *
     * 分类规则（见 [LanSyncStats]）：冲突副本只进 `conflicts`、不进 `added`；已 tombstone 的副本不计；
     * 其余 uid 按「新出现 / 墓碑复活 / 变墓碑 / 内容变化」分到 added / restored / deleted / updated。
     * 远端先合并、把副本随 records 一起返回时，本机 [LanMerge] 不再生成副本，但 after 里首次出现的
     * 活副本仍会计入；重复同步同一批数据为 0。
     *
     * 校验失败（字段类型/长度、uid 前缀、时钟）抛 [IllegalArgumentException]；知识卡合并后超过
     * [KnowledgeCard.MAX] 抛 [IllegalStateException] 且**整体不写**；落盘失败抛 [LanSyncException] 并保留 journal。
     */
    fun apply(remote: JSONArray): JSONObject = synchronized(FileMemo) {
        recoverLocked(app)
        ensureNotBlocked()
        validateRemote(remote)

        val obs = observeLocked()
        validateRecords(obs.json, "local")
        val merged = LanMerge.merge(obs.json, remote)
        val mergedRecords = merged.getJSONArray("records")
        if (mergedRecords.length() > LanMerge.MAX_RECORDS) {
            throw IllegalStateException(
                "LanSync: 合并后记录 ${mergedRecords.length()} 条，超过上限 ${LanMerge.MAX_RECORDS}"
            )
        }
        val recs = recordsFrom(mergedRecords)

        val sides = TreeMap<String, String>(obs.state.sides)
        collectSides(sides, obs.json, remote, mergedRecords)

        val txn = commitLocked(
            obs = obs,
            recs = recs,
            bindings = TreeMap(obs.state.bindings),
            stamps = TreeMap(obs.state.stamps),
            resolved = TreeMap(obs.state.resolved),
            sides = sides,
            dropHistory = emptySet(),
        )
        val stats = LanSyncStats.classify(obs.json, mergedRecords)

        JSONObject()
            .put("records", mergedRecords)
            .put("conflicts", stats.getInt("conflicts"))
            .put("cards", stats.getJSONObject("cards"))
            .put("history", stats.getJSONObject("history"))
            .put("cardsTotal", txn.cards.size)
            .put("stars", txn.hist.count { it.star })
    }

    /**
     * 冲突面板的数据源：所有**还活着**的 `~conflict~` 副本（已 tombstone 的副本没有可比较内容，不列）。
     *
     * 每条：`{baseUid, copyUid, kind, baseRowId, copyRowId, baseValue, copyValue, side, resolved}`。
     *  - `kind` 是 `cards` / `history`；`*RowId` 是本机绑定 id，没有绑定为 null；
     *  - `side` 是 `localKept` / `peerKept` / `unknown`（apply 时按确定性 uid、canonical value、记录数组
     *    来源依次判定并记进 sidecar；判定不出就保持 unknown）；
     *  - `resolved` 表示本机用户已经处理过这条副本（KEEP_BOTH 时副本仍活着）。
     */
    fun listConflicts(): JSONArray = synchronized(FileMemo) {
        recoverLocked(app)
        val st = stateLocked()
        val out = JSONArray()
        for ((uid, rec) in st.records) {
            if (!LanConflict.isCopy(uid)) continue
            val kind = LanConflict.kindOf(uid) ?: continue
            val value = rec.value ?: continue
            val baseUid = LanConflict.baseUidOf(uid) ?: continue
            out.put(
                JSONObject()
                    .put("baseUid", baseUid)
                    .put("copyUid", uid)
                    .put("kind", kind)
                    .put("baseRowId", st.bindings[baseUid] ?: JSONObject.NULL)
                    .put("copyRowId", st.bindings[uid] ?: JSONObject.NULL)
                    .put("baseValue", st.records[baseUid]?.value ?: JSONObject.NULL)
                    .put("copyValue", value)
                    .put("side", st.sides[uid] ?: LanConflict.SIDE_UNKNOWN)
                    .put("resolved", st.resolved.containsKey(uid))
            )
        }
        out
    }

    /**
     * 三选一解决一条冲突副本，并在**同一把锁**里走与 apply 相同的 journal → 业务行 → sidecar 事务。
     *
     * 规则见 [LanConflict.resolve]：`N = bump(max(base.clock, copy.clock), deviceId)`，存活方
     * `clock=N value=己方 payload`，败方 `clock=N value=null`；KEEP_BOTH 两条都留且副本 uid 不变。
     * history 的败方在投影后**删掉 binding + stamp**：否则条目只是取消收藏，用户再点收藏会复用同一 uid
     * 把墓碑复活。解决结果写进 sidecar 的可选 `resolved` 字段（不进 wire / record / value）。
     *
     * 返回 `{baseUid, copyUid, pick, cardsTotal, stars, conflicts}`，其中 `conflicts` 是解决后仍待处理的副本数。
     */
    fun resolveConflict(copyUid: String, pick: ConflictPick): JSONObject = synchronized(FileMemo) {
        recoverLocked(app)
        ensureNotBlocked()
        if (!UID_RE.matches(copyUid) || !LanConflict.isCopy(copyUid)) {
            throw IllegalArgumentException("LanSync: 不是冲突副本 uid: $copyUid")
        }

        val obs = observeLocked()
        validateRecords(obs.json, "local")

        // 双击 / 重复提交：已经处理过、副本已是墓碑时按幂等返回，不重复写事务。
        val already = obs.state.records[copyUid]
        if (already != null && already.value == null && obs.state.resolved.containsKey(copyUid)) {
            return@synchronized JSONObject()
                .put("baseUid", LanConflict.baseUidOf(copyUid) ?: "")
                .put("copyUid", copyUid)
                .put("pick", obs.state.resolved[copyUid])
                .put("cardsTotal", obs.cards.size)
                .put("stars", obs.hist.count { it.star })
                .put("conflicts", pendingConflicts(obs.state.records, obs.state.resolved))
        }

        val current = JSONArray()
        for (r in obs.state.records.values) current.put(r.toJson())

        val resolvedRec = LanConflict.resolve(current, copyUid, pick, obs.state.deviceId)
        val recs = recordsFrom(resolvedRec.records)

        val resolved = TreeMap<String, String>(obs.state.resolved)
        resolved[copyUid] = pick.name
        val dropHistory = resolvedRec.dead.filterTo(HashSet()) { it.startsWith(H_PREFIX) }

        val txn = commitLocked(
            obs = obs,
            recs = recs,
            bindings = TreeMap(obs.state.bindings),
            stamps = TreeMap(obs.state.stamps),
            resolved = resolved,
            sides = TreeMap(obs.state.sides),
            dropHistory = dropHistory,
        )

        JSONObject()
            .put("baseUid", resolvedRec.baseUid)
            .put("copyUid", copyUid)
            .put("pick", pick.name)
            .put("cardsTotal", txn.cards.size)
            .put("stars", txn.hist.count { it.star })
            .put("conflicts", pendingConflicts(recs, resolved))
    }

    // ---------------- 事务 / 冲突辅助 ----------------

    /** 已校验过的 `{uid, clock, value}` 数组 → 记录表。 */
    private fun recordsFrom(arr: JSONArray): TreeMap<String, Rec> {
        val recs = TreeMap<String, Rec>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val uid = o.getString("uid")
            val raw = o.opt("value")
            val value = if (raw == null || raw == JSONObject.NULL) null else raw as JSONObject
            recs[uid] = Rec(uid, parseClock(o.getJSONObject("clock"), "records[$i].clock"), value)
        }
        return recs
    }

    private fun indexByUid(arr: JSONArray): Map<String, JSONObject> {
        val m = HashMap<String, JSONObject>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uid = o.opt("uid") as? String ?: continue
            m[uid] = o
        }
        return m
    }

    /**
     * 为本次 after 里新出现的活副本补记来源侧。只记确定的结果：unknown 不落盘，下次同步还能再判。
     * 判定优先级由 [LanConflict.classifySide] 保证（确定性 uid → canonical value → 数组来源）。
     */
    private fun collectSides(
        sides: TreeMap<String, String>,
        before: JSONArray,
        remote: JSONArray,
        after: JSONArray,
    ) {
        val local = indexByUid(before)
        val peer = indexByUid(remote)
        val merged = indexByUid(after)
        for ((uid, rec) in merged) {
            if (!LanConflict.isCopy(uid) || sides.containsKey(uid)) continue
            val value = rec.optJSONObject("value") ?: continue
            val baseUid = LanConflict.baseUidOf(uid) ?: continue
            val side = LanConflict.classifySide(
                baseUid = baseUid,
                copyUid = uid,
                copyValue = value,
                localBase = local[baseUid],
                remoteBase = peer[baseUid],
                inLocal = local.containsKey(uid),
                inRemote = peer.containsKey(uid),
            )
            if (side != LanConflict.SIDE_UNKNOWN) sides[uid] = side
        }
    }

    private fun pendingConflicts(recs: Map<String, Rec>, resolved: Map<String, String>): Int {
        var n = 0
        for ((uid, r) in recs) {
            if (!LanConflict.isCopy(uid) || r.value == null) continue
            if (!resolved.containsKey(uid)) n++
        }
        return n
    }

    private class Commit(val cards: List<KnowledgeCard.KCData>, val hist: List<History.Entry>)

    /**
     * 投影 + 提交（apply / resolveConflict 共用）。
     *
     * 顺序固定为 journal → 业务缓存/队列 → sidecar → flush → 删 journal。调用方必须已持有
     * `synchronized(FileMemo)`，且传进来的 [recs] / [bindings] / [stamps] 是本事务独占的副本。
     * [dropHistory] 里的 uid 在投影**之后**清掉 binding + stamp（取消收藏不是删条目）。
     */
    private fun commitLocked(
        obs: Observed,
        recs: TreeMap<String, Rec>,
        bindings: TreeMap<String, Long>,
        stamps: TreeMap<String, Long>,
        resolved: TreeMap<String, String>,
        sides: TreeMap<String, String>,
        dropHistory: Set<String>,
    ): Commit {
        val ks = KnowledgeCard.Store(app)
        val hs = History.Store(app)

        val idFloor = maxOf(
            ks.idFloor(),
            obs.cards.maxOfOrNull { it.id } ?: 0L,
            bindings.filterKeys { it.startsWith(K_PREFIX) }.values.maxOrNull() ?: 0L,
        )
        val projCards = projectKnowledge(recs, bindings, stamps, obs.cards, idFloor)
        if (projCards.size > KnowledgeCard.MAX) {
            throw IllegalStateException(
                "LanSync: 知识卡合并后 ${projCards.size} 张，超过上限 ${KnowledgeCard.MAX}，已整体拒绝（未写入任何数据）"
            )
        }
        // 先抬高水位再落盘：即使后面写盘失败、由 journal 重放，同一批 id 也不会被本机新建复用。
        ks.noteIdUsed(maxOf(idFloor, projCards.maxOfOrNull { it.id } ?: 0L))
        val projHist = projectHistory(recs, bindings, stamps, obs.hist)
        for (uid in dropHistory) {
            bindings.remove(uid)
            stamps.remove(uid)
        }
        val newState = State(obs.state.deviceId, recs, bindings, stamps, resolved, sides)
        val stateObj = stateJson(newState)

        // 1) 先落 journal（原子），它是本事务的唯一恢复依据。
        val txn = JSONObject()
            .put("v", VERSION)
            .put("knowledge", JSONArray(ks.toJson(projCards)))
            .put("history", JSONArray(History.toJson(projHist)))
            .put("state", stateObj)
        writeAtomic(journal, txn.toString())

        // 2) 业务缓存 + 有序写队列、sidecar，3) flush 屏障；只有全部确认落盘才删 journal。
        try {
            ks.replaceFromSync(projCards)
            hs.replaceFromSync(projHist)
            writeAtomic(sidecar, stateObj.toString())
            FileMemo.flush()
        } catch (e: Exception) {
            markPending(sidecar.path)
            throw LanSyncException(
                "LanSync: 写盘失败，事务已保留在 ${JOURNAL}，下次读取前会自动重放", e
            )
        }
        if (!journal.delete()) {
            markPending(sidecar.path)   // 内容已一致，重放幂等；尽快把 journal 清掉
            Log.w(TAG, "journal 删除失败，下次读取会幂等重放: " + journal.name)
        }
        states[sidecar.path] = newState
        return Commit(projCards, projHist)
    }

    /** journal 已损坏并被隔离时，任何同步（包括覆盖写）都必须停下来等人工恢复。 */
    private fun ensureNotBlocked() {
        if (sidecar.path in blocked || corruptMarker(app.filesDir) != null) {
            throw LanSyncCorruptException(
                "LanSync: journal 已损坏并隔离为 $JOURNAL.corrupt，恢复完成前拒绝同步（不会覆盖现有数据）。"
            )
        }
    }

    /**
     * 本机数据严格读取：IO 失败按磁盘错误抛，JSON/内容失败按数据损坏抛。
     * 两者都让本次同步失败，而不是把空列表当成「用户删光了」发墓碑。
     */
    private fun <T> requireHealthy(name: String, r: FileMemo.Loaded<T>): T {
        r.error?.let { err ->
            Log.e(TAG, "本机 $name 不可用：$err")
            if (r.io) throw IOException("LanSync: 读取本机 $name 失败：$err")
            throw LanSyncCorruptException("LanSync: 本机 $name 损坏：$err")
        }
        return r.value ?: throw LanSyncCorruptException("LanSync: 本机 $name 未返回数据")
    }

    // ---------------- 观察 ----------------

    private class Observed(
        val json: JSONArray,
        val state: State,
        val cards: List<KnowledgeCard.KCData>,
        val hist: List<History.Entry>,
    )

    private fun observeLocked(): Observed {
        val st = stateLocked()
        val dev = st.deviceId

        val records = TreeMap<String, Rec>()
        for ((uid, r) in st.records) records[uid] = r.copy()
        val bindings = TreeMap<String, Long>(st.bindings)
        val stamps = TreeMap<String, Long>(st.stamps)

        // 严格读取：JSON/IO 失败必须阻断同步，绝不能把「读不出来」当成空库然后发墓碑。
        val cards = requireHealthy(KnowledgeCard.FILE, KnowledgeCard.Store(app).readForSync())
        val hist = requireHealthy(HISTORY_FILE, History.Store(app).readForSync())

        // 知识卡：先处理已有绑定（变更 / 墓碑），再给没绑定的卡分 uid。
        val cardById = HashMap<Long, KnowledgeCard.KCData>(cards.size * 2)
        for (c in cards) cardById[c.id] = c
        val claimedCards = HashSet<Long>()
        val kBind = bindings.entries.filter { it.key.startsWith(K_PREFIX) }
            .map { it.key to it.value }.sortedBy { it.first }
        for ((uid, id) in kBind) {
            val live = cardById[id]
            if (live == null) {
                tombstone(records, uid, dev)
                bindings.remove(uid)
                stamps.remove(uid)
                continue
            }
            if (!claimedCards.add(id)) {
                Log.w(TAG, "重复绑定 $uid -> $id，已解绑（保留第一条）")
                bindings.remove(uid)
                continue
            }
            putValue(records, uid, dev, cardPayload(live))
        }
        for (c in cards) {
            if (c.id in claimedCards) continue
            val uid = K_PREFIX + UUID.randomUUID().toString()
            bindings[uid] = c.id
            records[uid] = Rec(uid, clock1(dev), cardPayload(c))
        }

        // history：只同步收藏的。取消收藏 = 墓碑，但保留 binding + createdAt 快照，
        // 以后重新收藏还能复用同一个 uid；条目被删掉才解绑。
        val entryById = HashMap<Long, History.Entry>(hist.size * 2)
        for (e in hist) entryById[e.id] = e
        val claimedEntries = HashSet<Long>()
        val hBind = bindings.entries.filter { it.key.startsWith(H_PREFIX) }
            .map { it.key to it.value }.sortedBy { it.first }
        for ((uid, id) in hBind) {
            val live = entryById[id]
            if (live == null) {
                tombstone(records, uid, dev)
                bindings.remove(uid)
                stamps.remove(uid)
                continue
            }
            if (!claimedEntries.add(id)) {
                Log.w(TAG, "重复绑定 $uid -> $id，已解绑（保留第一条）")
                bindings.remove(uid)
                continue
            }
            if (!live.star) {
                tombstone(records, uid, dev)
                continue
            }
            val created = records[uid]?.value?.optLong("createdAt", -1L)?.takeIf { it >= 0L }
                ?: stamps[uid] ?: live.id
            stamps[uid] = created
            putValue(records, uid, dev, historyPayload(live, created))
        }
        for (e in hist) {
            if (!e.star || e.id in claimedEntries) continue
            val uid = H_PREFIX + UUID.randomUUID().toString()
            bindings[uid] = e.id
            stamps[uid] = e.id
            records[uid] = Rec(uid, clock1(dev), historyPayload(e, e.id))
        }

        if (records.size > LanMerge.MAX_RECORDS) {
            throw IllegalStateException(
                "LanSync: 本地记录 ${records.size} 条，超过上限 ${LanMerge.MAX_RECORDS}"
            )
        }

        val out = JSONArray()
        for (r in records.values) out.put(r.toJson())
        return Observed(
            out,
            State(st.deviceId, records, bindings, stamps, st.resolved, st.sides),
            cards,
            hist,
        )
    }

    /** 内容变了才 +1；没变保持原 clock，保证 export 幂等。 */
    private fun putValue(records: TreeMap<String, Rec>, uid: String, dev: String, payload: JSONObject) {
        val old = records[uid]
        if (old?.value != null && LanMerge.canonical(old.value) == LanMerge.canonical(payload)) return
        records[uid] = Rec(uid, bump(old?.clock, dev), payload)
    }

    /** 墓碑同样只在「上次不是墓碑」时 +1，重复 export 不会把 clock 越推越高。 */
    private fun tombstone(records: TreeMap<String, Rec>, uid: String, dev: String) {
        val old = records[uid]
        if (old != null && old.value == null) return
        records[uid] = Rec(uid, bump(old?.clock, dev), null)
    }

    /**
     * 本机计数 +1。计数达到 [LanMerge.MAX_SAFE_INTEGER] 时由 [LanMerge.bump] 抛错，
     * 不做静默封顶：否则本机后续改动会永远停在同一计数，对端无法判断新旧。
     */
    private fun bump(clock: TreeMap<String, Long>?, dev: String): TreeMap<String, Long> =
        LanMerge.bump(clock ?: TreeMap(), dev)

    private fun clock1(dev: String): TreeMap<String, Long> = TreeMap<String, Long>().apply { put(dev, 1L) }

    // ---------------- 投影 ----------------

    /**
     * 把合并后的记录投影成知识卡列表，同时维护 bindings/stamps（原地改传入的 map）。
     * 绑定还在且本地卡还在 → 保留本地 id；否则分配「现有最大 id / 本机水位」之后的新 id。
     * 不同 uid 的同 dedupKey 卡全部保留。
     */
    private fun projectKnowledge(
        recs: TreeMap<String, Rec>,
        bindings: TreeMap<String, Long>,
        stamps: TreeMap<String, Long>,
        cards: List<KnowledgeCard.KCData>,
        idFloor: Long,
    ): List<KnowledgeCard.KCData> {
        val byId = LinkedHashMap<Long, KnowledgeCard.KCData>(cards.size * 2)
        for (c in cards) byId[c.id] = c
        var next = maxOf(cards.maxOfOrNull { it.id } ?: 0L, idFloor)
        val claimed = HashSet<Long>()

        val bound = bindings.entries.filter { it.key.startsWith(K_PREFIX) }
            .map { it.key to it.value }.sortedBy { it.first }
        for ((uid, id) in bound) {
            val rec = recs[uid] ?: continue
            val v = rec.value
            if (v == null) {
                if (claimed.add(id)) byId.remove(id)
                bindings.remove(uid)
                stamps.remove(uid)
                continue
            }
            val useId: Long
            if (byId.containsKey(id) && claimed.add(id)) {
                useId = id
            } else {
                useId = ++next
                claimed.add(useId)
                bindings[uid] = useId
            }
            byId[useId] = card(v, useId)
        }

        for ((uid, rec) in recs) {
            if (!uid.startsWith(K_PREFIX) || rec.value == null || bindings.containsKey(uid)) continue
            val id = ++next
            claimed.add(id)
            bindings[uid] = id
            byId[id] = card(rec.value, id)
        }
        return byId.values.toList()
    }

    /**
     * 投影收藏 history。record 为 null 只取消收藏、不删条目；非收藏的普通 history 一律不动，
     * 超过 [History.MAX] 时按 [History.trim] 先删非收藏。
     *
     * 顺序按「创建时间 / 本地 id」倒序（最新在前）。远端新收藏的本地 id 是投影时分配的 max+1，
     * 不能拿它排序，否则旧收藏会跑到最前；这里用 payload 的 createdAt（首次绑定时快照进 stamps）。
     */
    private fun projectHistory(
        recs: TreeMap<String, Rec>,
        bindings: TreeMap<String, Long>,
        stamps: TreeMap<String, Long>,
        hist: List<History.Entry>,
    ): List<History.Entry> {
        val byId = LinkedHashMap<Long, History.Entry>(hist.size * 2)
        for (e in hist) byId[e.id] = e
        var next = maxOf(hist.maxOfOrNull { it.id } ?: 0L, System.currentTimeMillis())
        val claimed = HashSet<Long>()
        val createdById = HashMap<Long, Long>(hist.size * 2)

        val bound = bindings.entries.filter { it.key.startsWith(H_PREFIX) }
            .map { it.key to it.value }.sortedBy { it.first }
        for ((uid, id) in bound) {
            val rec = recs[uid] ?: continue
            val v = rec.value
            val live = byId[id]
            if (v == null) {
                if (live != null) {
                    if (claimed.add(id)) {
                        byId[id] = live.copy(star = false)
                        createdById[id] = stamps[uid] ?: live.id
                    }
                } else {
                    bindings.remove(uid)
                    stamps.remove(uid)
                }
                continue
            }
            val created = v.optLong("createdAt", -1L).takeIf { it >= 0L } ?: stamps[uid] ?: live?.id ?: 0L
            stamps[uid] = created
            val useId: Long
            if (live != null && claimed.add(id)) {
                useId = id
            } else {
                useId = ++next
                claimed.add(useId)
                bindings[uid] = useId
            }
            byId[useId] = entry(v, useId)
            createdById[useId] = created
        }

        for ((uid, rec) in recs) {
            if (!uid.startsWith(H_PREFIX) || rec.value == null || bindings.containsKey(uid)) continue
            val id = ++next
            claimed.add(id)
            bindings[uid] = id
            val created = rec.value.optLong("createdAt", -1L).takeIf { it >= 0L } ?: 0L
            stamps[uid] = created
            byId[id] = entry(rec.value, id)
            createdById[id] = created
        }
        val ordered = byId.values.sortedWith(
            compareByDescending<History.Entry> { createdById[it.id] ?: it.id }
                .thenByDescending { it.id }
        )
        return History.trim(ordered, History.MAX)
    }

    private fun card(v: JSONObject, id: Long): KnowledgeCard.KCData = KnowledgeCard.KCData(
        id = id,
        deck = v.getString("deck"),
        front = v.getString("front"),
        back = v.optString("back", ""),
        source = KnowledgeCard.Source.of(v.getString("source")).code,
        createdAt = v.getLong("createdAt"),
        fsrs = Fsrs.cardFromJson(v.getJSONObject("fsrs").toString()) ?: Fsrs.Card(),
        origin = v.optString("origin", ""),
    )

    private fun entry(v: JSONObject, id: Long): History.Entry = History.Entry(
        id = id,
        kind = v.getString("kind"),
        label = v.optString("label", ""),
        text = v.getString("text"),
        src = v.optString("src", ""),
        star = true,
    )

    // ---------------- payload ----------------

    private fun cardPayload(c: KnowledgeCard.KCData): JSONObject = JSONObject()
        .put("deck", c.deck)
        .put("front", c.front)
        .put("back", c.back)
        .put("source", c.source)
        .put("createdAt", c.createdAt)
        .put("fsrs", JSONObject(Fsrs.toJson(c.fsrs)))
        .put("origin", c.origin)

    private fun historyPayload(e: History.Entry, createdAt: Long): JSONObject = JSONObject()
        .put("kind", e.kind)
        .put("label", e.label)
        .put("text", e.text)
        .put("src", e.src)
        .put("star", true)
        .put("createdAt", createdAt)

    // ---------------- 侧车状态 ----------------

    private class Rec(val uid: String, val clock: TreeMap<String, Long>, val value: JSONObject?) {
        fun copy(): Rec = Rec(uid, TreeMap(clock), value)

        fun toJson(): JSONObject {
            val c = JSONObject()
            for ((k, v) in clock) c.put(k, v)
            return JSONObject().put("uid", uid).put("clock", c).put("value", value ?: JSONObject.NULL)
        }
    }

    private class State(
        val deviceId: String,
        val records: TreeMap<String, Rec>,
        val bindings: TreeMap<String, Long>,
        val stamps: TreeMap<String, Long>,
        /** 本机用户已处理过的副本：copyUid → KEEP_BASE / KEEP_COPY / KEEP_BOTH。只在 sidecar。 */
        val resolved: TreeMap<String, String> = TreeMap(),
        /** apply 时判定出的副本来源：copyUid → localKept / peerKept。只在 sidecar，不进 wire。 */
        val sides: TreeMap<String, String> = TreeMap(),
    )

    private fun stateLocked(): State {
        states[sidecar.path]?.let { return it }
        val st = if (sidecar.exists()) {
            try {
                parseState(JSONObject(sidecar.readText()))
            } catch (e: Exception) {
                // 不能重建空状态：那会把「本机已有绑定 / 墓碑」全部抹掉，下一次 export 会把
                // 远端记录当成删除。文件损坏必须让用户看见并停下来。
                Log.e(TAG, "$FILE 损坏，拒绝继续同步（不重建空状态）", e)
                throw LanSyncCorruptException(
                    "LanSync: 本机同步状态 $FILE 损坏，已拒绝继续（不重建空状态）。请保留文件并从备份恢复。", e
                )
            }
        } else {
            State(newDeviceId(), TreeMap(), TreeMap(), TreeMap())
        }
        states[sidecar.path] = st
        return st
    }

    private fun parseState(o: JSONObject): State {
        // 本机旧版本写数字，新版本可写字符串；桌面端 UUID 含 '-'，统一按字符串规则收。
        val dev = when (val raw = o.opt("deviceId")) {
            is String -> raw
            is Number -> integral(raw)?.takeIf { it > 0L }?.toString()
            else -> null
        } ?: throw IllegalArgumentException("LanSync: sidecar deviceId 非法")
        if (!DEVICE_RE.matches(dev)) throw IllegalArgumentException("LanSync: sidecar deviceId 非法: $dev")

        val records = TreeMap<String, Rec>()
        val ra = o.optJSONArray("records")
            ?: throw IllegalArgumentException("LanSync: sidecar 缺少 records，按损坏处理")
        for (i in 0 until ra.length()) {
            val r = ra.optJSONObject(i) ?: throw IllegalArgumentException("LanSync: sidecar records[$i] 不是对象")
            val uid = r.opt("uid") as? String ?: throw IllegalArgumentException("LanSync: sidecar records[$i] 缺少 uid")
            if (!UID_RE.matches(uid)) throw IllegalArgumentException("LanSync: sidecar records[$i] uid 非法: $uid")
            if (records.containsKey(uid)) throw IllegalArgumentException("LanSync: sidecar 重复 uid: $uid")
            val clock = parseClock(
                r.opt("clock") as? JSONObject ?: throw IllegalArgumentException("LanSync: sidecar records[$i] clock 非法"),
                "sidecar records[$i].clock",
            )
            val raw = r.opt("value")
            val value = when {
                raw == null || raw == JSONObject.NULL -> null
                raw is JSONObject -> raw
                else -> throw IllegalArgumentException("LanSync: sidecar records[$i] value 类型非法")
            }
            records[uid] = Rec(uid, clock, value)
        }

        val bindings = TreeMap<String, Long>()
        val bo = o.optJSONObject("bindings")
            ?: throw IllegalArgumentException("LanSync: sidecar 缺少 bindings，按损坏处理")
        val bk = bo.keys()
        while (bk.hasNext()) {
            val uid = bk.next()
            if (!UID_RE.matches(uid)) throw IllegalArgumentException("LanSync: sidecar binding uid 非法: $uid")
            val id = integral(bo.opt(uid))
            if (id == null || id <= 0L) throw IllegalArgumentException("LanSync: sidecar binding 非法: $uid")
            bindings[uid] = id
        }

        val stamps = TreeMap<String, Long>()
        // stamps 只影响 history 的 createdAt 快照；老 sidecar 缺它时按空处理不会删数据。
        val so = o.optJSONObject("stamps") ?: JSONObject()
        val sk = so.keys()
        while (sk.hasNext()) {
            val uid = sk.next()
            if (!UID_RE.matches(uid) || !uid.startsWith(H_PREFIX)) {
                throw IllegalArgumentException("LanSync: sidecar stamp uid 非法: $uid")
            }
            val t = integral(so.opt(uid))
            if (t == null || t < 0L || t > LanMerge.MAX_SAFE_INTEGER) {
                throw IllegalArgumentException("LanSync: sidecar stamp 非法: $uid")
            }
            stamps[uid] = t
        }
        // resolved / sides 都是后加的可选本地元数据：老 sidecar 没有就按空处理；有就必须结构正确。
        val resolved = parseMeta(o.optJSONObject("resolved"), "resolved", RESOLVED_VALUES)
        val sides = parseMeta(o.optJSONObject("sides"), "sides", SIDE_VALUES)
        return State(dev, records, bindings, stamps, resolved, sides)
    }

    /** 可选元数据表：key 必须是冲突副本 uid，值必须落在允许集合里。 */
    private fun parseMeta(raw: JSONObject?, field: String, allowed: Set<String>): TreeMap<String, String> {
        val out = TreeMap<String, String>()
        if (raw == null) return out
        val keys = raw.keys()
        while (keys.hasNext()) {
            val uid = keys.next()
            if (!UID_RE.matches(uid) || !LanConflict.isCopy(uid)) {
                throw IllegalArgumentException("LanSync: sidecar $field uid 非法: $uid")
            }
            val v = raw.opt(uid)
            if (v !is String || v !in allowed) {
                throw IllegalArgumentException("LanSync: sidecar $field 值非法: $uid")
            }
            out[uid] = v
        }
        return out
    }

    private fun stateJson(s: State): JSONObject = JSONObject().apply {
        // 纯数字 deviceId 仍写成数字，旧版本读得懂；UUID 等新格式写字符串。
        val dev: Any = s.deviceId.toLongOrNull()?.takeIf { it > 0L && it.toString() == s.deviceId } ?: s.deviceId
        put("deviceId", dev)
        put("records", JSONArray().apply { for (r in s.records.values) put(r.toJson()) })
        put("bindings", JSONObject().apply { for ((k, v) in s.bindings) put(k, v) })
        put("stamps", JSONObject().apply { for ((k, v) in s.stamps) put(k, v) })
        // 可选本地元数据：旧版本忽略；绝不进 records / 同步 payload。
        put("resolved", JSONObject().apply { for ((k, v) in s.resolved) put(k, v) })
        put("sides", JSONObject().apply { for ((k, v) in s.sides) put(k, v) })
    }

    // ---------------- 校验 ----------------

    private fun validateRemote(remote: JSONArray) = validateRecords(remote, "remote")

    private fun validateRecords(records: JSONArray, side: String) {
        if (records.length() > LanMerge.MAX_RECORDS) {
            throw IllegalArgumentException(
                "LanSync: $side ${records.length()} 条记录，超过上限 ${LanMerge.MAX_RECORDS}"
            )
        }
        val seen = HashSet<String>(records.length() * 2)
        for (i in 0 until records.length()) {
            val o = records.optJSONObject(i)
                ?: throw IllegalArgumentException("LanSync: $side[$i] 不是 JSON 对象")
            val uid = o.opt("uid") as? String
                ?: throw IllegalArgumentException("LanSync: $side[$i] 缺少 uid")
            if (!UID_RE.matches(uid)) throw IllegalArgumentException("LanSync: $side[$i] uid 非法: $uid")
            if (!seen.add(uid)) throw IllegalArgumentException("LanSync: $side 重复 uid: $uid")
            parseClock(
                o.opt("clock") as? JSONObject
                    ?: throw IllegalArgumentException("LanSync: $side[$i] clock 非法"),
                "$side[$i].clock",
            )
            if (!o.has("value")) {
                throw IllegalArgumentException("LanSync: $side[$i] 缺少 value（删除请显式写 null）")
            }
            val raw = o.opt("value")
            when {
                raw == null || raw == JSONObject.NULL -> Unit
                raw is JSONObject ->
                    if (uid.startsWith(K_PREFIX)) validateCard(raw, "$side[$i].value")
                    else validateHistory(raw, "$side[$i].value")
                else -> throw IllegalArgumentException("LanSync: $side[$i] value 既不是对象也不是 null")
            }
        }
    }

    private fun validateCard(v: JSONObject, where: String) {
        allowKeys(v, CARD_KEYS, where)
        requireText(v, "deck", 1, MAX_DECK, where)
        val front = requireText(v, "front", 1, MAX_FRONT, where)
        if (front.isBlank()) throw IllegalArgumentException("LanSync: $where.front 不能为空白")
        requireText(v, "back", 0, MAX_LONG_TEXT, where)
        val source = requireText(v, "source", 1, 16, where)
        if (SOURCES.none { it == source }) throw IllegalArgumentException("LanSync: $where.source 未知: $source")
        requireCreatedAt(v, where)
        val origin = v.opt("origin")
        if (origin != null && origin != JSONObject.NULL) {
            if (origin !is String || origin.length > MAX_ORIGIN) {
                throw IllegalArgumentException("LanSync: $where.origin 类型或长度非法")
            }
        }
        val fsrs = v.opt("fsrs") as? JSONObject
            ?: throw IllegalArgumentException("LanSync: $where.fsrs 必须是对象")
        validateFsrs(fsrs, "$where.fsrs")
    }

    private fun validateFsrs(o: JSONObject, where: String) {
        allowKeys(o, FSRS_KEYS, where)
        val state = integral(o.opt("state")) ?: throw IllegalArgumentException("LanSync: $where.state 非法")
        if (state < 0L || state > 3L) throw IllegalArgumentException("LanSync: $where.state 越界: $state")
        requireFinite(o, "stability", where)
        requireFinite(o, "difficulty", where)
        if (requireLong(o, "due", where) < 0L) throw IllegalArgumentException("LanSync: $where.due 不能为负")
        if (o.has("last_review") && o.opt("last_review") != JSONObject.NULL) {
            if (requireLong(o, "last_review", where) < 0L) {
                throw IllegalArgumentException("LanSync: $where.last_review 不能为负")
            }
        }
        for (k in FSRS_INT_KEYS) {
            if (requireLong(o, k, where) < 0L) throw IllegalArgumentException("LanSync: $where.$k 不能为负")
        }
    }

    private fun validateHistory(v: JSONObject, where: String) {
        allowKeys(v, HISTORY_KEYS, where)
        requireText(v, "kind", 1, MAX_KIND, where)
        val label = v.opt("label")
        if (label != null && label != JSONObject.NULL) requireText(v, "label", 0, MAX_LABEL, where)
        val text = requireText(v, "text", 1, MAX_LONG_TEXT, where)
        if (text.isBlank()) throw IllegalArgumentException("LanSync: $where.text 不能为空白")
        val src = v.opt("src")
        if (src != null && src != JSONObject.NULL) requireText(v, "src", 0, MAX_LONG_TEXT, where)
        if (v.opt("star") != true) {
            throw IllegalArgumentException("LanSync: $where.star 必须为 true（只同步收藏的 history）")
        }
        requireCreatedAt(v, where)
    }

    /** createdAt 是跨端安全整数：0..[LanMerge.MAX_SAFE_INTEGER]，超出就拒绝而不是截断。 */
    private fun requireCreatedAt(o: JSONObject, where: String): Long {
        val t = requireLong(o, "createdAt", where)
        if (t < 0L || t > LanMerge.MAX_SAFE_INTEGER) {
            throw IllegalArgumentException(
                "LanSync: $where.createdAt 必须在 0..${LanMerge.MAX_SAFE_INTEGER}（实际 $t）"
            )
        }
        return t
    }

    private fun allowKeys(o: JSONObject, allowed: Set<String>, where: String) {
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in allowed) throw IllegalArgumentException("LanSync: $where 含未知字段 $k")
        }
    }

    private fun requireText(o: JSONObject, key: String, min: Int, max: Int, where: String): String {
        val v = o.opt(key)
        if (v !is String) throw IllegalArgumentException("LanSync: $where.$key 必须是字符串")
        if (v.length < min || v.length > max) {
            throw IllegalArgumentException("LanSync: $where.$key 长度 ${v.length} 超出 [$min, $max]")
        }
        return v
    }

    private fun requireLong(o: JSONObject, key: String, where: String): Long {
        return integral(o.opt(key)) ?: throw IllegalArgumentException("LanSync: $where.$key 必须是整数")
    }

    private fun requireFinite(o: JSONObject, key: String, where: String) {
        val v = o.opt(key)
        if (v !is Number) throw IllegalArgumentException("LanSync: $where.$key 必须是数字")
        val d = v.toDouble()
        if (!d.isFinite() || d < 0.0) throw IllegalArgumentException("LanSync: $where.$key 非法: $d")
    }

    private fun integral(v: Any?): Long? {
        if (v !is Number) return null
        val d = v.toDouble()
        if (!d.isFinite()) return null
        val l = v.toLong()
        return if (l.toDouble() == d) l else null
    }

    private fun parseClock(o: JSONObject, where: String): TreeMap<String, Long> {
        if (o.length() == 0) throw IllegalArgumentException("LanSync: $where 时钟为空")
        val out = TreeMap<String, Long>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val dev = keys.next()
            if (!DEVICE_RE.matches(dev)) throw IllegalArgumentException("LanSync: $where 设备号非法: $dev")
            val v = integral(o.opt(dev))
            if (v == null || v <= 0L || v > LanMerge.MAX_SAFE_INTEGER) {
                throw IllegalArgumentException(
                    "LanSync: $where 时钟值非法: $dev（需在 1..${LanMerge.MAX_SAFE_INTEGER}）"
                )
            }
            out[dev] = v
        }
        return out
    }

    companion object {
        const val FILE = "lan-sync.json"
        const val JOURNAL = "lan-sync.txn.json"

        private const val TAG = "LanSync"
        private const val VERSION = 1
        private const val K_PREFIX = "k:"
        private const val H_PREFIX = "h:"
        private const val HISTORY_FILE = "history.json"

        private const val MAX_DECK = 500
        private const val MAX_FRONT = 10_000
        private const val MAX_LONG_TEXT = 200_000
        private const val MAX_ORIGIN = 20_000
        private const val MAX_LABEL = 2_000
        private const val MAX_KIND = 32

        // 冲突副本可能再冲突（LanMerge 以当前 uid 为前缀），因此后缀允许重复。
        private val UID_RE = Regex(
            "^[kh]:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(~conflict~[0-9a-f]{24})*$"
        )
        /** 1..64 个 ASCII 字母 / 数字 / '-'：兼容本机旧数字 deviceId 与桌面 UUID。 */
        private val DEVICE_RE = Regex("^[A-Za-z0-9-]{1,64}$")
        private val CARD_KEYS = setOf("deck", "front", "back", "source", "createdAt", "fsrs", "origin")
        private val FSRS_KEYS = setOf(
            "state", "stability", "difficulty", "due", "last_review",
            "reps", "lapses", "learning_step", "scheduled_days", "elapsed_days",
        )
        private val FSRS_INT_KEYS = setOf("reps", "lapses", "learning_step", "scheduled_days", "elapsed_days")
        private val HISTORY_KEYS = setOf("kind", "label", "text", "src", "star", "createdAt")
        private val SOURCES = KnowledgeCard.Source.entries.map { it.code }.toSet()
        private val RESOLVED_VALUES = ConflictPick.entries.map { it.name }.toSet()
        private val SIDE_VALUES = setOf(
            LanConflict.SIDE_LOCAL, LanConflict.SIDE_PEER, LanConflict.SIDE_UNKNOWN,
        )

        private val states = HashMap<String, State>()
        private val recovered = HashSet<String>()
        private val recovering = HashSet<String>()
        /** 本进程内写事务失败、journal 还在待重放的路径。 */
        private val pending = HashSet<String>()
        /** journal 已损坏并隔离：未人工恢复前，同步必须拒绝，也不能写新 journal / sidecar 覆盖证据。 */
        private val blocked = HashSet<String>()

        /**
         * 从 Store 的 list() 里调用：把上次没删掉的 journal 重放完再允许读，避免旧缓存把新状态回写。
         * 幂等；重放中再次进入直接返回（防递归）。调用方需持有 FileMemo 锁，未持有也可以（这里会补锁）。
         */
        @JvmStatic
        fun recover(ctx: Context) {
            synchronized(FileMemo) { recoverLocked(ctx.applicationContext) }
        }

        /**
         * 调用方必须已经持有 `synchronized(FileMemo)`。
         *
         * - journal 内容损坏：隔离成 `.corrupt` 并标记 [blocked]，普通读不再反复崩，同步明确拒绝；
         * - journal 正确但重放失败：**保留** journal 并抛错，下次读前重试，期间不允许 apply 覆盖；
         * - 本进程内一旦隔离/失败，后续 export / apply 在 `ensureNotBlocked` 处停下。
         */
        internal fun recoverLocked(app: Context) {
            val path = File(app.filesDir, FILE).path
            if (path in recovering) return
            if (path in blocked) {
                recovered.add(path)
                return
            }
            if (path in recovered && path !in pending) return
            if (corruptMarker(app.filesDir) != null) {
                // 上次进程隔离过 journal，人工恢复没完成：不允许同步覆盖。
                blocked.add(path)
                recovered.add(path)
                Log.e(TAG, "发现未处理的 journal 隔离文件，同步将被拒绝")
                return
            }
            val jf = File(app.filesDir, JOURNAL)
            if (!jf.exists()) {
                recovered.add(path)
                pending.remove(path)
                return
            }
            recovering.add(path)
            try {
                val text = try {
                    jf.readText()
                } catch (e: Exception) {
                    // 读不出来不等于内容坏：保留 journal 等下次重试，不隔离、不覆盖。
                    markPending(path)
                    throw LanSyncException("LanSync: 读取恢复日志失败，已保留待重试", e)
                }
                val txn = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    isolateAndBlock(app, jf, "journal 内容损坏", e)
                    return
                }
                if (txn.optInt("v", 0) != VERSION) {
                    isolateAndBlock(app, jf, "journal 版本不支持: " + txn.opt("v"), null)
                    return
                }
                val stateObj: JSONObject
                val state: State
                val kList: List<KnowledgeCard.KCData>
                val hList: List<History.Entry>
                try {
                    stateObj = txn.getJSONObject("state")
                    state = LanSyncStore(app).parseState(stateObj)
                    val ks = KnowledgeCard.Store(app)
                    kList = ks.parseStrict(txn.getJSONArray("knowledge").toString())
                    hList = History.parseStrict(txn.getJSONArray("history").toString())
                } catch (e: Exception) {
                    isolateAndBlock(app, jf, "journal 内容不合法", e)
                    return
                }
                try {
                    val ks = KnowledgeCard.Store(app)
                    val hs = History.Store(app)
                    ks.putFromSync(kList)
                    hs.putFromSync(hList)
                    writeAtomic(File(app.filesDir, FILE), stateObj.toString())
                    FileMemo.flush()
                    if (!jf.delete()) {
                        // journal 还在磁盘上：状态不能算「已恢复」，下次读取前继续幂等重放并重试删除。
                        markPending(path)
                        Log.w(TAG, "journal 删除失败: " + jf.name)
                    }
                } catch (e: Exception) {
                    markPending(path)
                    throw LanSyncException("LanSync: journal 重放失败，已保留待重试", e)
                }
                states[path] = state
                recovered.add(path)
                if (!jf.exists()) pending.remove(path)
            } finally {
                recovering.remove(path)
            }
        }

        /** 隔离坏 journal：保留原字节，换一个私有文件名，并让本进程的同步拒绝继续。 */
        private fun isolateAndBlock(app: Context, jf: File, why: String, cause: Throwable?) {
            Log.e(TAG, "LanSync: $why，隔离 journal 并拒绝后续同步", cause)
            var dest = File(app.filesDir, JOURNAL + ".corrupt")
            if (dest.exists()) dest = File(app.filesDir, JOURNAL + ".corrupt." + System.currentTimeMillis())
            val ok = try { jf.renameTo(dest) } catch (_: Exception) { false }
            if (!ok) Log.e(TAG, "journal 隔离失败，原文件仍保留: " + jf.name)
            val path = File(app.filesDir, FILE).path
            blocked.add(path)
            recovered.add(path)
            pending.remove(path)
        }

        private fun corruptMarker(dir: File): File? =
            try {
                dir.listFiles()?.firstOrNull { it.name.startsWith(JOURNAL + ".corrupt") }
            } catch (_: Exception) {
                null
            }

        /** 调用方必须已经持有 `synchronized(FileMemo)`。 */
        private fun markPending(path: String) {
            pending.add(path)
        }
    }
}

private fun newDeviceId(): String = (1L..Int.MAX_VALUE.toLong()).random().toString()

/**
 * 本机同一次 apply 的 before / after 分类统计（纯 JVM，只看记录数组，不碰 Android）。
 *
 * 两种记录各自返回 `{added, updated, deleted, restored, conflicts, before, after}`：
 *  - `added`：after 里新出现（before 完全没有这个 uid）的活记录；
 *  - `restored`：before 是墓碑、after 又活了的非副本记录；
 *  - `deleted`：before 活着、after 变墓碑的非副本记录；
 *  - `updated`：两侧都活、canonical value 变化的非副本记录；
 *  - `conflicts`：`~conflict~` 副本从「before 没有 / 是墓碑」变成 after 活着；**只进这里，不进 added**；
 *  - `before` / `after`：该类型活记录总数（含活副本）。
 *
 * 已 tombstone 的副本在 after 里直接跳过，不参与任何计数。
 */
internal object LanSyncStats {
    fun classify(before: JSONArray, after: JSONArray): JSONObject {
        val b = index(before)
        val a = index(after)
        val cards = bucket(b, a, "k:")
        val history = bucket(b, a, "h:")
        return JSONObject()
            .put("cards", cards)
            .put("history", history)
            .put("conflicts", cards.getInt("conflicts") + history.getInt("conflicts"))
    }

    private fun bucket(
        before: Map<String, JSONObject>,
        after: Map<String, JSONObject>,
        prefix: String,
    ): JSONObject {
        var added = 0
        var updated = 0
        var deleted = 0
        var restored = 0
        var conflicts = 0
        var beforeTotal = 0
        var afterTotal = 0

        for ((uid, rec) in before) {
            if (uid.startsWith(prefix) && live(rec)) beforeTotal++
        }
        for ((uid, rec) in after) {
            if (!uid.startsWith(prefix)) continue
            val nowLive = live(rec)
            if (nowLive) afterTotal++
            val was = before[uid]
            val wasLive = was != null && live(was)

            if (LanConflict.isCopy(uid)) {
                if (nowLive && !wasLive) conflicts++
                continue                    // 副本永远不落进 added / updated / deleted / restored
            }
            when {
                nowLive && was == null -> added++
                nowLive && !wasLive -> restored++
                nowLive && !same(was!!, rec) -> updated++
                !nowLive && wasLive -> deleted++
            }
        }
        return JSONObject()
            .put("added", added)
            .put("updated", updated)
            .put("deleted", deleted)
            .put("restored", restored)
            .put("conflicts", conflicts)
            .put("before", beforeTotal)
            .put("after", afterTotal)
    }

    private fun live(rec: JSONObject): Boolean = rec.optJSONObject("value") != null

    private fun same(a: JSONObject, b: JSONObject): Boolean =
        LanMerge.canonical(a.optJSONObject("value")) == LanMerge.canonical(b.optJSONObject("value"))

    private fun index(arr: JSONArray): Map<String, JSONObject> {
        val m = HashMap<String, JSONObject>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uid = o.opt("uid") as? String ?: continue
            m[uid] = o
        }
        return m
    }
}

/**
 * 本轮「用户可见」的冲突副本数：after 里首次变成活着的 `~conflict~` uid 个数（墓碑副本不计）。
 *
 * 不能直接用 [LanMerge.merge] 的 `conflicts`：它数的是**本机这一步**新生成的副本，电脑先合并、
 * 把副本随 `records` 一起返回时它是 0，手机界面就会错误地显示 0 个冲突。
 * 也不能把远端 `conflicts` 相加：那是电脑上一阶段的统计，重发旧副本时会重复计。
 * 以本机合并前记录（`before`）为基线，本机生成的和远端带来的副本都只在首次出现时计入，
 * 重复同步同一批数据自然是 0。
 */
internal fun countNewConflictCopies(before: JSONArray, after: JSONArray): Int =
    LanSyncStats.classify(before, after).getInt("conflicts")

/**
 * 原子写：temp 写完 fsync 再 rename。journal / sidecar 都走这里，保证读到的永远是完整 JSON。
 * （Java 层拿不到目录 fd，无法 fsync 目录；rename 在同目录内是原子的，断电最坏是回到旧文件。）
 */
private fun writeAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
        out.fd.sync()
    }
    if (!tmp.renameTo(f)) {
        if (f.exists() && !f.delete()) throw IOException("LanSync: 无法替换 " + f.name)
        if (!tmp.renameTo(f)) throw IOException("LanSync: 无法落盘 " + f.name)
    }
}

/** 同步事务的落盘失败。journal 仍在，下一次 Store 读取前会重放；调用方不得当成成功。 */
class LanSyncException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * 本机同步数据/元数据损坏（sidecar、journal、knowledge.json、history.json）。
 * 不重建、不清空、不截断：直接让本次同步失败，由用户看见。
 */
class LanSyncCorruptException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
