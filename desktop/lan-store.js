/* lan-store.js — 双端局域网同步的持久层（Node / CommonJS）。
 *
 * 只做三件事：
 *   1. 把本机的知识卡 + **收藏**历史观察成同步记录（uid / 矢量时钟 / 业务 payload）；
 *   2. 用 ./lan-merge 合并远端记录，再把合并结果写回本机三份 JSON；
 *   3. 用 sidecar（lan-sync.json）保存 uid → 本地 id/t 的绑定，保证同一行不会重复长出来。
 *
 * ## 数据边界
 * - 知识卡：**所有**本地卡都参与同步。payload 不带本地 id：
 *   `{ deck, front, back, source, createdAt, fsrs, origin }`，origin 缺省为 ''。
 * - 历史：**只有 star === true 的条目**参与同步。payload 不带本地 t：
 *   `{ kind, label, text, src, star: true, createdAt }`。
 *   createdAt 是该 uid 第一次绑定时的 t；之后本地 t 就算漂移，payload 时间也保持不变。
 * - 删除一律用 value: null 墓碑，绝不把「没观察到」当成删除。
 *
 * ## 事务
 * apply 先把三份目标（knowledge.json / history.json / lan-sync.json）写进 0600 journal，
 * 提交 journal 之后才去 replace 本地文件，最后删 journal。任一步写失败都抛错、不 ack；
 * 下一次构造 LanStore 会先 recover journal，把已提交但没写完的目标补齐。
 * knowledge 的写回只走 Store.replaceFromSync（原子写 + 刷新其内存副本），不碰私有缓存。
 *
 * ## 读取 / 恢复的硬约束
 * - 同步路径必须严格读：knowledge 用 Store.listStrict()，历史用 X.strictRead 包住
 *   historyRead（JsonStore.read 在这段里读失败会抛）。读失败绝不能被当成空库，
 *   否则下一次 export 会把「读失败」翻译成给对端的一整批墓碑。
 * - 文件明确不存在（ENOENT）才是合法空；空文件 / 语法坏 / 非法字段一律阻断。
 * - sidecar 已存在但损坏 → 显式报错，绝不重建（重建会丢掉 binding）。
 * - journal 必须先全量校验再重放；historyWrite 返回 false 必须抛，journal 保留。
 * - export / apply / 业务编辑前都会先恢复 pending journal，避免旧 journal 回滚新编辑。
 *
 * ## 冲突（v1.62）
 * 并发冲突由 lan-merge 生成 `<uid>~conflict~<hash>` 副本。LanStore 在此基础上提供：
 * - apply() 的 stats：按本机「同一次 apply 的 before / after」统计知识卡与收藏各自的
 *   added / updated / deleted / restored / conflicts + before / after 总数。冲突副本只进
 *   conflicts、不进 added；value 为 null 的副本不计。
 * - listConflicts()：列出 baseUid / copyUid / kind / 行 id / 两边 payload / side / resolved。
 * - resolveConflict(copyUid, pick)：KEEP_BASE / KEEP_COPY / KEEP_BOTH。三类选择都在
 *   journal → 业务行 → sidecar 的事务里完成，语义与 Android 端一致：
 *   KEEP_BASE = 主 uid 留 base 最新值 + 副本墓碑；KEEP_COPY = 主 uid 改写 copy 最新 payload
 *   + 副本墓碑（副本 uid 永远不会存活）；KEEP_BOTH = 两条 uid 都留、副本 uid 不变。
 *   所有选择都令两边 clock = N = bump(max(base.clock, copy.clock), 本机 deviceId)。
 *   KEEP_BASE / KEEP_COPY 的败方恒为副本 uid：history 投影成「取消收藏」后必须同时
 *   删 binding 与 star，否则用户重新收藏会把败方副本重新拉活。
 * - 解决结果写进 sidecar 的可选 `resolved`（旧版本忽略），side 标注写进可选 `sides`。
 *   两者都只在本机持久层，绝不进 wire / record / value。
 *
 * 所有操作同步执行（无 await），Node 事件循环天然保证 apply / resolveConflict 之间不会交错。
 */
'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const { merge, canonical } = require('./lan-merge')
const K = require('./knowledge')
const Fsrs = require('./fsrs')
const X = require('./extras')

/** 单侧/目标上限。超过直接拒绝，不截断、不写盘。 */
const MAX_CARDS = K.MAX || 5000
const MAX_RECORDS = 10000
/** 普通历史目标总数；收藏不参与裁剪。 */
const HISTORY_MAX = X.HISTORY_MAX || 200

/** sidecar / journal 文件名。 */
const SIDECAR = 'lan-sync.json'
const JOURNAL = 'lan-sync.journal.json'

/** 冲突副本标记与解决方式（与 lan-merge 的副本 uid 规则一致）。 */
const CONFLICT_MARK = '~conflict~'
const HASH_HEX_LEN = 24
const RESOLVE_PICKS = Object.freeze(['KEEP_BASE', 'KEEP_COPY', 'KEEP_BOTH'])
const SIDES = Object.freeze(['localKept', 'peerKept', 'unknown'])

/** payload 字段长度上限。宁可明确拒绝，也不把半截内容写进库。 */
const LIMITS = Object.freeze({
  uid: 512,
  deck: 500,
  front: 10000,
  back: 200000,
  source: 32,
  origin: 20000,
  kind: 32,
  label: 2000,
  text: 200000,
  src: 200000,
  fsrsJson: 20000
})

/** 矢量时钟计数的硬上限（= Number.MAX_SAFE_INTEGER）。 */
const MAX_CLOCK = 9007199254740991

/**
 * deviceId 口径：桌面 own 的 UUID 必须原样保留；安卓接受 1..64 位字母数字，
 * 并兼容旧版本的数字 id（纯数字同样落在这个字符集里）。
 * 首字符必须是字母或数字，这样 '__proto__' 之类的键天然被排除。
 */
const DEVICE_ID_RE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/

function isDeviceId(value) {
  return typeof value === 'string' && DEVICE_ID_RE.test(value)
}

function fail(code, message) {
  const err = new Error('lan-store: ' + message + ' [' + code + ']')
  err.code = code
  return err
}

function hasOwn(obj, key) {
  return Object.prototype.hasOwnProperty.call(obj, key)
}

function isPlainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const proto = Object.getPrototypeOf(value)
  return proto === Object.prototype || proto === null
}

function compareUid(a, b) {
  return a < b ? -1 : a > b ? 1 : 0
}

/** uid 前缀就是 kind；冲突副本（<uid>~conflict~<hash>）前缀不变。 */
function kindOf(uid) {
  if (typeof uid === 'string' && uid.length >= 3 && uid.startsWith('k:')) return 'k'
  if (typeof uid === 'string' && uid.length >= 3 && uid.startsWith('h:')) return 'h'
  throw fail('E_UID', 'uid 必须以 k: 或 h: 开头: ' + String(uid))
}

/** 冲突副本 uid：前缀仍是 k:/h:，直接父记录 = 最后一个 ~conflict~ 之前的部分。 */
function isConflictUid(uid) {
  return typeof uid === 'string' && uid.lastIndexOf(CONFLICT_MARK) > 0
}

function baseUidOf(uid) {
  if (!isConflictUid(uid)) return null
  const cut = uid.lastIndexOf(CONFLICT_MARK)
  const base = uid.slice(0, cut)
  if (!base) return null
  try { kindOf(base) } catch { return null }
  return base
}

/** 与 lan-merge 的副本 uid 算法逐字节一致（用于 side 的确定性重算）。 */
function conflictUidFor(uid, rec) {
  const seal = canonical({ uid: uid, clock: rec.clock, value: rec.value })
  return uid + CONFLICT_MARK + crypto.createHash('sha256').update(seal, 'utf8').digest('hex').slice(0, HASH_HEX_LEN)
}

function maxClock(a, b) {
  const out = {}
  for (const key of Object.keys(a || {})) out[key] = a[key]
  for (const key of Object.keys(b || {})) {
    const v = b[key]
    if (out[key] === undefined || v > out[key]) out[key] = v
  }
  return out
}

/* ------------------------------------------------------------------ JSON / 原子写 */

function writeAtomic(file, data, mode) {
  const tmp = file + '.tmp'
  const m = mode || 0o600
  let fd = null
  try {
    fd = fs.openSync(tmp, 'w', m)
    fs.writeFileSync(fd, data, 'utf8')
    try { fs.fsyncSync(fd) } catch {}
    fs.closeSync(fd)
    fd = null
    fs.renameSync(tmp, file)
    try { fs.chmodSync(file, m) } catch {}
  } catch (err) {
    if (fd !== null) { try { fs.closeSync(fd) } catch {} }
    try { fs.unlinkSync(tmp) } catch {}
    throw err
  }
}

/**
 * 读 JSON 文件并区分「不存在」与「坏了 / 读不了」。
 * 同步的 sidecar 和 journal 只有 ENOENT 允许被当成空；其余必须显式报错。
 */
function readJsonFile(file) {
  let raw = ''
  try { raw = fs.readFileSync(file, 'utf8') } catch (err) {
    if (err && err.code === 'ENOENT') return { exists: false, value: null, error: null }
    return { exists: true, value: null, error: err }
  }
  if (!String(raw).trim()) return { exists: true, value: null, error: new Error(file + ' 是空文件') }
  try { return { exists: true, value: JSON.parse(raw), error: null } } catch (err) {
    return { exists: true, value: null, error: err }
  }
}

function jsonText(value, label) {
  try {
    return canonical(value)
  } catch (err) {
    throw fail('E_PAYLOAD', label + ' 不是合法 JSON: ' + (err && err.message))
  }
}

/* ------------------------------------------------------------------ payload 规范化 */

function hasSnakeFsrs(value) {
  return (
    hasOwn(value, 'last_review') ||
    hasOwn(value, 'learning_step') ||
    hasOwn(value, 'scheduled_days') ||
    hasOwn(value, 'elapsed_days')
  )
}

/**
 * 本地卡 / 远端卡都先收敛成 knowledge.json 里的 snake_case 结构。
 * 本机 Store 内部是 camelCase（lastReview / learningStep…），安卓与磁盘格式是 snake_case。
 */
function fsrsToWire(value) {
  if (!isPlainObject(value)) return Fsrs.toJson(Fsrs.card())
  let card
  if (hasSnakeFsrs(value)) {
    card = Fsrs.cardFromJson(value) || Fsrs.card()
  } else {
    card = Fsrs.card(value)
    card.state = Fsrs.stateOf(card.state)
  }
  return Fsrs.toJson(card)
}

function fsrsFromWire(value) {
  if (!isPlainObject(value)) return Fsrs.card()
  if (hasSnakeFsrs(value)) return Fsrs.cardFromJson(value) || Fsrs.card()
  const card = Fsrs.card(value)
  card.state = Fsrs.stateOf(card.state)
  return card
}

function finiteInt(value, field) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw fail('E_PAYLOAD', field + ' 必须是非负安全整数（不做截断/取整）')
  }
  return value
}

/**
 * 本地时间戳：缺失按 0（旧卡没有 createdAt 的兼容口径），
 * 但只要给了值就必须是合法时间戳，绝不 Number() 强行转换或 Math.trunc。
 */
function localTimestamp(value, field) {
  if (value === undefined || value === null) return 0
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw fail('E_PAYLOAD', field + ' 必须是非负安全整数（不做截断/取整）')
  }
  return value
}

/** fsrs 里的时间戳字段也必须是整数：due / last_review。 */
function checkFsrsTimestamps(fsrs, field) {
  for (const key of ['due', 'last_review', 'lastReview']) {
    if (!hasOwn(fsrs, key)) continue
    const v = fsrs[key]
    if (v === null || v === undefined) continue
    if (typeof v !== 'number' || !Number.isSafeInteger(v) || v < 0) {
      throw fail('E_PAYLOAD', field + '.' + key + ' 必须是非负安全整数（不做截断/取整）')
    }
  }
}

/** 矢量时钟：deviceId 1..64 字母数字，计数 1..9007199254740991。 */
function normalizeClock(clock, field) {
  if (!isPlainObject(clock)) throw fail('E_CLOCK', field + ' 必须是 { deviceId: 正整数 } 对象')
  const keys = Object.keys(clock).sort()
  const out = {}
  for (const key of keys) {
    if (!isDeviceId(key)) throw fail('E_CLOCK', field + ' 的 deviceId 非法: ' + key)
    const n = clock[key]
    if (!Number.isSafeInteger(n) || n <= 0 || n > MAX_CLOCK) {
      throw fail('E_CLOCK', field + '.' + key + ' 必须是 1..' + MAX_CLOCK + ' 的整数')
    }
    out[key] = n
  }
  return out
}

function field(value, name, max, allowEmpty) {
  if (typeof value !== 'string') throw fail('E_PAYLOAD', name + ' 必须是字符串')
  if (value.length > max) throw fail('E_PAYLOAD', name + ' 超过 ' + max + ' 字')
  if (!allowEmpty && !value.trim()) throw fail('E_PAYLOAD', name + ' 不能为空')
  return value
}

/** 历史行里的可选文本字段：旧文件缺字段按 '' 处理，有值则必须是字符串且不超限。 */
function textField(value, name, max) {
  if (value === undefined || value === null) return ''
  return field(value, name, max, true)
}

/** 本地卡 → 同步 payload。origin 缺省 ''。 */
function cardPayload(card) {
  return {
    deck: String(card && card.deck != null && card.deck !== '' ? card.deck : K.DEFAULT_DECK),
    front: String(card && card.front != null ? card.front : ''),
    back: String(card && card.back != null ? card.back : ''),
    source: K.sourceOf(card && card.source),
    createdAt: localTimestamp(card && card.createdAt, 'createdAt'),
    fsrs: fsrsToWire(card && card.fsrs),
    origin: card && typeof card.origin === 'string' ? card.origin : ''
  }
}

/** 远端卡 payload → 已校验的标准对象（没有 id）。 */
function normalizeCardPayload(value) {
  if (!isPlainObject(value)) throw fail('E_PAYLOAD', '知识卡 value 必须是对象')

  if (value.deck !== undefined && typeof value.deck !== 'string') {
    throw fail('E_PAYLOAD', 'deck 必须是字符串')
  }
  const deckRaw = value.deck
  const deck = (deckRaw && deckRaw.trim())
    ? field(deckRaw, 'deck', LIMITS.deck, true)
    : K.DEFAULT_DECK

  const front = field(value.front, 'front', LIMITS.front, false)
  const back = field(value.back, 'back', LIMITS.back, true)

  if (value.source !== undefined && typeof value.source !== 'string') {
    throw fail('E_PAYLOAD', 'source 必须是字符串')
  }
  const source = K.sourceOf(field(value.source == null ? '' : value.source, 'source', LIMITS.source, true))

  const createdAt = finiteInt(value.createdAt, 'createdAt')

  if (!isPlainObject(value.fsrs)) throw fail('E_PAYLOAD', 'fsrs 必须是对象')
  checkFsrsTimestamps(value.fsrs, 'fsrs')
  const fsrs = fsrsToWire(value.fsrs)
  if (jsonText(fsrs, 'fsrs').length > LIMITS.fsrsJson) throw fail('E_PAYLOAD', 'fsrs 过大')

  let origin = ''
  if (value.origin !== undefined) origin = field(value.origin, 'origin', LIMITS.origin, true)

  const out = { deck, front, back, source, createdAt, fsrs, origin }
  jsonText(out, '知识卡 payload')
  return out
}

/** 本地历史 → 同步 payload。createdAt 由调用方给「首次 t」，保证时间稳定。 */
function historyPayload(entry, createdAt) {
  return {
    kind: String(entry && entry.kind != null ? entry.kind : ''),
    label: String(entry && entry.label != null ? entry.label : ''),
    text: String(entry && entry.text != null ? entry.text : ''),
    src: String(entry && entry.src != null ? entry.src : ''),
    star: true,
    createdAt: localTimestamp(createdAt, 'createdAt')
  }
}

/** 远端历史 payload → 已校验的标准对象（没有 t；只接受 star:true）。 */
function normalizeHistoryPayload(value) {
  if (!isPlainObject(value)) throw fail('E_PAYLOAD', '收藏历史 value 必须是对象')
  const kind = field(value.kind, 'kind', LIMITS.kind, false)
  const label = field(value.label, 'label', LIMITS.label, true)
  const text = field(value.text, 'text', LIMITS.text, false)
  const src = field(value.src, 'src', LIMITS.src, true)
  if (value.star !== true) throw fail('E_PAYLOAD', '收藏历史 star 必须是 true（取消收藏用 value:null 墓碑）')
  const createdAt = finiteInt(value.createdAt, 'createdAt')
  const out = { kind, label, text, src, star: true, createdAt }
  jsonText(out, '收藏历史 payload')
  return out
}

function normalizeValue(kind, value) {
  if (value === null) return null
  return kind === 'k' ? normalizeCardPayload(value) : normalizeHistoryPayload(value)
}

/** 远端整批先校验；任意一条不合法 → 整次拒绝。 */
function normalizeIncoming(records) {
  if (!Array.isArray(records)) throw fail('E_INPUT', 'remoteRecords 必须是数组')
  if (records.length > MAX_RECORDS) {
    throw fail('E_LIMIT', '远端记录 ' + records.length + ' 条，超过上限 ' + MAX_RECORDS)
  }
  const out = new Array(records.length)
  const seen = new Set()
  for (let i = 0; i < records.length; i += 1) {
    const rec = records[i]
    if (!isPlainObject(rec)) throw fail('E_INPUT', 'records[' + i + '] 必须是对象')
    const uid = rec.uid
    if (typeof uid !== 'string' || !uid.length || uid.length > LIMITS.uid) {
      throw fail('E_UID', 'records[' + i + '].uid 非法')
    }
    if (seen.has(uid)) throw fail('E_UID_DUP', 'records[' + i + '].uid 重复: ' + uid)
    seen.add(uid)
    const kind = kindOf(uid)
    out[i] = {
      uid: uid,
      clock: normalizeClock(rec.clock, 'records[' + i + '].clock'),
      value: normalizeValue(kind, rec.value)
    }
  }
  return out
}

/* ------------------------------------------------------------------ side / resolved 元数据 */

/**
 * sidecar 的可选 sides / resolved 字段（v1.62 新增）。
 * 旧版本只会写 deviceId / records / bindings，读到这两个字段也不会理；新版本缺字段时按空处理。
 */
function normalizeSides(raw, code) {
  if (raw === undefined) return {}
  if (!isPlainObject(raw)) throw fail(code, 'sidecar 的 sides 不是对象（拒绝重建）')
  const out = {}
  for (const uid of Object.keys(raw)) {
    if (!isConflictUid(uid)) throw fail(code, 'sidecar 的 sides uid 不是冲突副本: ' + uid)
    const side = raw[uid]
    if (side !== 'localKept' && side !== 'peerKept' && side !== 'unknown') {
      throw fail(code, 'sidecar 的 sides 取值非法: ' + String(side))
    }
    if (side !== 'unknown') out[uid] = side
  }
  return out
}

function normalizeResolved(raw, code) {
  if (raw === undefined) return {}
  if (!isPlainObject(raw)) throw fail(code, 'sidecar 的 resolved 不是对象（拒绝重建）')
  const out = {}
  for (const uid of Object.keys(raw)) {
    if (!isConflictUid(uid)) throw fail(code, 'sidecar 的 resolved uid 不是冲突副本: ' + uid)
    const item = raw[uid]
    if (!isPlainObject(item)) throw fail(code, 'sidecar 的 resolved 值不是对象: ' + uid)
    const pick = item.pick
    if (RESOLVE_PICKS.indexOf(pick) < 0) throw fail(code, 'sidecar 的 resolved.pick 非法: ' + String(pick))
    const at = item.at
    if (!Number.isSafeInteger(at) || at < 0) throw fail(code, 'sidecar 的 resolved.at 非法: ' + String(at))
    const baseUid = item.baseUid === undefined ? baseUidOf(uid) : item.baseUid
    if (typeof baseUid !== 'string') throw fail(code, 'sidecar 的 resolved.baseUid 非法: ' + String(baseUid))
    try { kindOf(baseUid) } catch { throw fail(code, 'sidecar 的 resolved.baseUid 非法: ' + String(baseUid)) }
    out[uid] = { pick: pick, at: at, baseUid: baseUid }
  }
  return out
}

/* ------------------------------------------------------------------ 同步统计 */

function rowKeyOf(kind, row) {
  const key = kind === 'k' ? row && row.id : row && row.t
  return Number.isSafeInteger(key) && key >= 0 ? key : null
}

/**
 * 行内容只比较业务 payload，不比较 id/t；知识卡统一收敛成 wire 形态（本地是 camelCase
 * FSRS 对象、落盘 journal 是 snake_case），否则同一条卡会被误判成 updated。
 */
function rowTextOf(kind, row) {
  if (kind === 'k') return canonical(cardPayload(row))
  return canonical({
    kind: row && row.kind != null ? String(row.kind) : '',
    label: row && row.label != null ? String(row.label) : '',
    text: row && row.text != null ? String(row.text) : '',
    src: row && row.src != null ? String(row.src) : '',
    star: !!(row && row.star === true)
  })
}

/**
 * 行 id/t → 记录。tombstone 也占着旧行号；同一行如果有两条记录，
 * 优先取「活记录 / 非冲突副本」，避免把冲突副本的墓碑算到普通行头上。
 */
function uidByRow(records, bindings) {
  const map = new Map()
  for (const rec of records) {
    const key = bindings[rec.uid]
    if (!Number.isSafeInteger(key) || key < 0) continue
    const prev = map.get(key)
    if (prev === undefined) { map.set(key, rec); continue }
    const better =
      (prev.value === null && rec.value !== null) ||
      (isConflictUid(prev.uid) && !isConflictUid(rec.uid))
    if (better) map.set(key, rec)
  }
  return map
}

/**
 * 单类（知识卡 / 收藏）的同一次 apply 前后差异。
 * - 冲突副本行只记 conflicts，永不进 added / updated / deleted。
 * - 墓碑副本（value:null）既没有行，也不计 conflicts。
 * - 同 id/t 内容变了算 updated；before 的 uid 是墓碑、after 又活过来算 restored。
 */
function statsForKind(kind, beforeRows, afterRows, beforeRecords, afterRecords, beforeBindings, afterBindings) {
  const stats = {
    before: beforeRows.length,
    after: afterRows.length,
    added: 0,
    updated: 0,
    deleted: 0,
    restored: 0,
    conflicts: 0
  }
  const beforeByKey = new Map()
  for (const row of beforeRows) {
    const key = rowKeyOf(kind, row)
    if (key !== null && !beforeByKey.has(key)) beforeByKey.set(key, row)
  }
  const afterByKey = new Map()
  for (const row of afterRows) {
    const key = rowKeyOf(kind, row)
    if (key !== null && !afterByKey.has(key)) afterByKey.set(key, row)
  }
  const beforeRecs = new Map(beforeRecords.map((r) => [r.uid, r]))
  const afterRecs = new Map(afterRecords.map((r) => [r.uid, r]))
  const beforeUidRow = uidByRow(beforeRecords, beforeBindings)
  const afterUidRow = uidByRow(afterRecords, afterBindings)

  for (const [key, row] of afterByKey) {
    const rec = afterUidRow.get(key) || null
    const uid = rec ? rec.uid : null
    const copy = uid !== null && isConflictUid(uid)
    const beforeRow = beforeByKey.get(key)

    if (beforeRow !== undefined) {
      if (rowTextOf(kind, row) === rowTextOf(kind, beforeRow)) continue
      if (copy) continue
      const prev = uid !== null ? beforeRecs.get(uid) : null
      // 收藏行还在、只是没 star：before 是墓碑，after 又 star 了 → 重新收藏。
      if (kind === 'h' && prev && prev.value === null && row.star === true) stats.restored += 1
      else stats.updated += 1
      continue
    }

    if (copy) {
      // 远端已物化的副本第一次到本机：算冲突，不算新增；已 tombstone 的副本不算。
      if (uid !== null && !beforeRecs.has(uid) && rec.value !== null) stats.conflicts += 1
      continue
    }

    const prev = uid !== null ? beforeRecs.get(uid) : null
    if (prev && prev.value === null) stats.restored += 1
    else stats.added += 1
  }

  for (const [key] of beforeByKey) {
    if (afterByKey.has(key)) continue
    const rec = beforeUidRow.get(key) || null
    if (rec && isConflictUid(rec.uid)) continue
    stats.deleted += 1
  }

  return stats
}

/**
 * 冲突副本来历：localKept = 本机版本留在主 uid；peerKept = 对端版本留在主 uid。
 * 与 Android LanConflict.classifySide 一致，只有两种**可证实**证据，其余一律 unknown：
 *  1. 确定性副本 uid 重算：拿本地 / 对端各自的 base clock+value 重算副本 uid，
 *     恰好只有一侧命中时，该侧是败方（两侧都命中则继续往下判）；
 *  2. canonical payload 精确相等：副本保存的是败方 payload，恰好只等于一侧时才可判定。
 * 刻意不做的猜测：
 *  - 不看副本出现在哪一侧的数组里：对端转发的第三方旧副本同样只在 peer 数组，
 *    数组存在性不能证明 payload 来源；
 *  - 不把「副本对不上本机」反向推成对端；两侧都命中 / 都不命中 / 都相等都给 unknown；
 *  - 不做先到先得：冲突证据必须是唯一的。
 */
function annotateSide(copyUid, baseUid, localBase, peerBase, copyRec) {
  try {
    if (!copyRec || copyRec.value === null) return 'unknown'

    const localLoses = !!(localBase && localBase.value !== null && conflictUidFor(baseUid, localBase) === copyUid)
    const peerLoses = !!(peerBase && peerBase.value !== null && conflictUidFor(baseUid, peerBase) === copyUid)
    if (localLoses !== peerLoses) return localLoses ? 'peerKept' : 'localKept'

    const copy = canonical(copyRec.value)
    const localValue = localBase && localBase.value !== null ? canonical(localBase.value) : null
    const peerValue = peerBase && peerBase.value !== null ? canonical(peerBase.value) : null
    const matchesLocal = localValue !== null && localValue === copy
    const matchesPeer = peerValue !== null && peerValue === copy
    if (matchesLocal !== matchesPeer) return matchesLocal ? 'peerKept' : 'localKept'
  } catch {
    // payload 里出现非 JSON 值：交给上层校验去报错，这里只放弃标注。
  }
  return 'unknown'
}

/* ------------------------------------------------------------------ 历史 / 卡片本地形态 */

function cardFromPayload(id, value) {
  return {
    id: id,
    deck: value.deck,
    front: value.front,
    back: value.back,
    source: value.source,
    createdAt: value.createdAt,
    fsrs: fsrsFromWire(value.fsrs),
    origin: value.origin
  }
}

function historyFromPayload(t, value) {
  return {
    t: t,
    kind: value.kind,
    label: value.label,
    text: value.text,
    src: value.src,
    star: true
  }
}

function withoutStar(entry) {
  const row = Object.assign({}, entry)
  delete row.star
  return row
}

/* ------------------------------------------------------------------ LanStore */

class LanStore {
  /**
   * @param {object} options
   * @param {string} options.dir            sidecar / journal 所在目录
   * @param {object} options.knowledge      真实 Knowledge.Store 实例
   * @param {Function} options.historyRead  () => history 数组（兼容旧接线）
   * @param {Function} [options.historyReadStrict] 严格读；读失败必须抛
   * @param {Function} options.historyWrite (history 数组) => boolean（false = 写失败）
   */
  constructor(options) {
    const o = options || {}
    if (typeof o.dir !== 'string' || !o.dir) throw new TypeError('LanStore: dir 必须是路径字符串')
    if (!o.knowledge || typeof o.knowledge.list !== 'function' || typeof o.knowledge.replaceFromSync !== 'function') {
      throw new TypeError('LanStore: knowledge 必须是 Knowledge.Store 实例（需要 list / replaceFromSync）')
    }
    if (typeof o.historyRead !== 'function') throw new TypeError('LanStore: historyRead 必须是函数')
    if (typeof o.historyWrite !== 'function') throw new TypeError('LanStore: historyWrite 必须是函数')

    this.dir = path.resolve(o.dir)
    this.knowledge = o.knowledge
    this.historyRead = o.historyRead
    this.historyReadStrict = typeof o.historyReadStrict === 'function'
      ? o.historyReadStrict
      : (o.history && typeof o.history.readStrict === 'function'
          ? () => o.history.readStrict()
          : null)
    this.historyWrite = o.historyWrite
    this.file = path.join(this.dir, SIDECAR)
    this.journalFile = path.join(this.dir, JOURNAL)
    this._recovering = false
    this._applying = false

    fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 })

    // 先补上次没写完的事务，再读 sidecar —— 否则会拿旧绑定去覆盖刚恢复的库。
    if (fs.existsSync(this.journalFile)) this._recover()

    const loaded = this._loadState()
    if (loaded) {
      this.state = loaded
    } else {
      this.state = this._freshState()
      this._writeState(this.state)
    }

    // 业务编辑前恢复 pending journal，避免重启后旧 journal 回滚后来的编辑。
    this._installGuards()
  }

  /** 本机设备 id（sidecar 持久化；同机重启不变，桌面端是 UUID）。 */
  get deviceId() { return this.state.deviceId }

  /* ---------------- sidecar ---------------- */

  /**
   * 读 sidecar。文件不存在 = 首次运行，返回 null；
   * 文件存在但读失败 / JSON 坏 / deviceId 非法 / records 或 bindings 非法 → 抛，
   * **绝不重建**（重建会把所有 binding 丢掉，下一次同步就会长出重复卡）。
   */
  _loadState() {
    const r = readJsonFile(this.file)
    if (!r.exists) return null
    if (r.error) throw fail('E_STATE', 'lan-sync.json 读取失败（拒绝重建）: ' + r.error.message)
    const raw = r.value
    if (!isPlainObject(raw)) throw fail('E_STATE', 'lan-sync.json 不是对象（拒绝重建）')

    let deviceId = raw.deviceId
    // 兼容更早的纯数字 deviceId（JSON 里可能是 number）。桌面 UUID 原样保留。
    if (typeof deviceId === 'number' && Number.isSafeInteger(deviceId) && deviceId >= 0) {
      deviceId = String(deviceId)
    }
    if (!isDeviceId(deviceId)) {
      throw fail('E_STATE', 'lan-sync.json 的 deviceId 非法（拒绝重建）: ' + String(raw.deviceId))
    }

    let records = []
    if (raw.records !== undefined) {
      if (!Array.isArray(raw.records)) throw fail('E_STATE', 'lan-sync.json 的 records 不是数组（拒绝重建）')
      records = normalizeIncoming(raw.records)
      records.sort((a, b) => compareUid(a.uid, b.uid))
    }

    const bindings = {}
    if (raw.bindings !== undefined) {
      if (!isPlainObject(raw.bindings)) throw fail('E_STATE', 'lan-sync.json 的 bindings 不是对象（拒绝重建）')
      for (const uid of Object.keys(raw.bindings)) {
        try { kindOf(uid) } catch {
          throw fail('E_STATE', 'lan-sync.json 的 binding uid 非法（拒绝重建）: ' + uid)
        }
        const id = raw.bindings[uid]
        // 旧版本用 null 表示「没有绑定」，这是合法旧格式，不是损坏。
        if (id === null) continue
        if (!Number.isSafeInteger(id) || id < 0) {
          throw fail('E_STATE', 'lan-sync.json 的 binding id 非法（拒绝重建）: ' + String(id))
        }
        bindings[uid] = id
      }
    }

    return {
      deviceId: deviceId,
      records: records,
      bindings: bindings,
      sides: normalizeSides(raw.sides, 'E_STATE'),
      resolved: normalizeResolved(raw.resolved, 'E_STATE')
    }
  }

  _freshState() {
    return { deviceId: crypto.randomUUID(), records: [], bindings: {}, sides: {}, resolved: {} }
  }

  _writeState(state) {
    writeAtomic(this.file, JSON.stringify({
      deviceId: state.deviceId,
      records: state.records,
      bindings: state.bindings,
      // v1.62 可选元数据；旧版本读到时只认前三个字段，不会因此失败。
      sides: state.sides || {},
      resolved: state.resolved || {}
    }), 0o600)
  }

  /** 同步层把自己挂到业务 Store 上：业务编辑前先恢复 pending journal。 */
  _installGuards() {
    const guard = () => { this._ensureRecovered() }
    if (typeof this.knowledge.setWriteGuard === 'function') {
      try { this.knowledge.setWriteGuard(guard) } catch { /* 老 Store 不支持就算了 */ }
    }
    // main 可能把真实 Store 包一层再传进来；knowledge.js 同时提供模块级守卫。
    if (K && typeof K.setWriteGuard === 'function') {
      try { K.setWriteGuard(guard) } catch { /* 同上 */ }
    }
    if (X && typeof X.setWriteGuard === 'function') {
      try { X.setWriteGuard(guard) } catch { /* 同上 */ }
    }
  }

  /**
   * 可选元数据的存活裁剪：sides 只留仍活着的冲突副本，resolved 只留仍存在的副本记录
   * （KEEP_BASE 之后副本是墓碑，仍保留解决记录，方便以后审计；记录被清掉才丢）。
   */
  _metaFor(records) {
    const live = new Set()
    const existing = new Set()
    for (const rec of records) {
      existing.add(rec.uid)
      if (rec.value !== null) live.add(rec.uid)
    }
    const sides = {}
    const curSides = this.state.sides || {}
    for (const uid of Object.keys(curSides)) if (live.has(uid)) sides[uid] = curSides[uid]
    const resolved = {}
    const curResolved = this.state.resolved || {}
    for (const uid of Object.keys(curResolved)) if (existing.has(uid)) resolved[uid] = curResolved[uid]
    return { sides: sides, resolved: resolved }
  }

  /**
   * 有任何 pending journal 就先恢复；恢复后必须重新读 sidecar，不能用内存里的旧 state。
   * 恢复 / 事务进行中直接返回，避免递归。
   */
  _ensureRecovered() {
    if (this._recovering || this._applying) return
    if (!fs.existsSync(this.journalFile)) return
    this._recover()
    const loaded = this._loadState()
    if (!loaded) throw fail('E_STATE', 'journal 恢复后 sidecar 缺失')
    this.state = loaded
  }

  /**
   * journal 里存的是三份**目标文件**的内容。重放前先全量校验；任何一项不合法都抛，
   * journal 原样保留（不删、不拿半截内容覆盖业务文件）。
   * 恢复时 knowledge 走 replaceFromSync(internal)，保证 Store 的内存副本一起刷新。
   */
  _recover() {
    if (this._recovering) return
    const r = readJsonFile(this.journalFile)
    if (!r.exists) return
    if (r.error) throw fail('E_JOURNAL', 'journal 读取失败（保留待处理）: ' + r.error.message)

    const journal = this._validateJournal(r.value)

    this._recovering = true
    try {
      this.knowledge.replaceFromSync(journal.knowledge, { internal: true })
      const ok = this.historyWrite(journal.history)
      if (ok === false) throw fail('E_WRITE', 'historyWrite 返回 false，journal 保留待下次重放')
      this._writeState(journal.state)
      this.state = journal.state
      try { fs.unlinkSync(this.journalFile) } catch {}
    } finally {
      this._recovering = false
    }
  }

  /**
   * journal 全量校验：知识卡 / 历史 / state（records + bindings）逐项验。
   * 返回规范化后的 journal；校验失败抛错，调用方不得重放。
   */
  _validateJournal(journal) {
    if (!isPlainObject(journal)) throw fail('E_JOURNAL', 'journal 不是对象')
    if (!Array.isArray(journal.knowledge)) throw fail('E_JOURNAL', 'journal.knowledge 不是数组')
    if (journal.knowledge.length > MAX_CARDS) throw fail('E_JOURNAL', 'journal 知识卡超过上限')

    const cardIds = new Set()
    const knowledge = []
    for (let i = 0; i < journal.knowledge.length; i += 1) {
      const c = journal.knowledge[i]
      if (!isPlainObject(c)) throw fail('E_JOURNAL', 'journal.knowledge[' + i + '] 不是对象')
      if (!Number.isSafeInteger(c.id) || c.id < 0) {
        throw fail('E_JOURNAL', 'journal.knowledge[' + i + '].id 非法')
      }
      if (cardIds.has(c.id)) throw fail('E_JOURNAL', 'journal 知识卡 id 重复: ' + c.id)
      cardIds.add(c.id)
      normalizeCardPayload(c)      // 字段宽度 / 时间戳 / fsrs 全量校验
      knowledge.push(c)
    }

    if (!Array.isArray(journal.history)) throw fail('E_JOURNAL', 'journal.history 不是数组')
    if (journal.history.length > MAX_RECORDS) throw fail('E_JOURNAL', 'journal 历史超过上限')
    const historyTs = new Set()
    const history = []
    for (let i = 0; i < journal.history.length; i += 1) {
      const e = journal.history[i]
      if (!isPlainObject(e)) throw fail('E_JOURNAL', 'journal.history[' + i + '] 不是对象')
      const t = finiteInt(e.t, 'journal.history[' + i + '].t')
      if (historyTs.has(t)) throw fail('E_JOURNAL', 'journal 历史 t 重复: ' + t)
      historyTs.add(t)
      // 旧 history.json 可能缺 kind / label / src：按''补齐（与 extras.historyToJson 同口径），
      // 但绝不悄悄丢掉 text 之类真正的内容字段。
      const row = {
        t: t,
        kind: textField(e.kind, 'journal.history[' + i + '].kind', LIMITS.kind),
        label: textField(e.label, 'journal.history[' + i + '].label', LIMITS.label),
        text: textField(e.text, 'journal.history[' + i + '].text', LIMITS.text),
        src: textField(e.src, 'journal.history[' + i + '].src', LIMITS.src)
      }
      if (e.star === true) row.star = true
      else if (e.star !== undefined && typeof e.star !== 'boolean') {
        throw fail('E_JOURNAL', 'journal.history[' + i + '].star 非法')
      }
      history.push(row)
    }

    if (!isPlainObject(journal.state)) throw fail('E_JOURNAL', 'journal.state 不是对象')
    if (!isDeviceId(journal.state.deviceId)) throw fail('E_JOURNAL', 'journal.state.deviceId 非法')
    const records = normalizeIncoming(journal.state.records)
    if (!isPlainObject(journal.state.bindings)) throw fail('E_JOURNAL', 'journal.state.bindings 不是对象')

    const bindings = {}
    for (const uid of Object.keys(journal.state.bindings)) {
      try { kindOf(uid) } catch { throw fail('E_JOURNAL', 'journal.state.bindings 的 uid 非法: ' + uid) }
      const id = journal.state.bindings[uid]
      if (id === null) continue
      if (!Number.isSafeInteger(id) || id < 0) {
        throw fail('E_JOURNAL', 'journal.state.bindings 的 id 非法: ' + String(id))
      }
      bindings[uid] = id
    }

    return {
      knowledge: knowledge,
      history: history,
      state: {
        deviceId: journal.state.deviceId,
        records: records,
        bindings: bindings,
        sides: normalizeSides(journal.state.sides, 'E_JOURNAL'),
        resolved: normalizeResolved(journal.state.resolved, 'E_JOURNAL')
      }
    }
  }

  /* ---------------- 观察本机 ---------------- */

  /**
   * 读本机历史。优先用集成方给的 historyReadStrict；
   * 否则用 X.strictRead 包住旧的 historyRead —— JsonStore.read() 会在这段里
   * 把读失败改成抛异常（同步必须阻断），而普通界面仍保留「失败给空数组」的兼容。
   */
  _readHistory() {
    if (typeof this.historyReadStrict === 'function') return this.historyReadStrict()
    return X.strictRead(this.historyRead)
  }

  /**
   * 把本地卡 / 收藏历史同步进 records（纯内存，不写盘）。
   * 这是 export 的核心；apply 先调用它，再和远端合并，保证本地改动不丢。
   */
  _observe() {
    const state = this.state
    const device = state.deviceId
    const records = state.records.map((r) => ({
      uid: r.uid,
      clock: Object.assign({}, r.clock),
      value: r.value
    }))
    const byUid = new Map()
    for (const rec of records) byUid.set(rec.uid, rec)

    const bindings = Object.assign({}, state.bindings)
    const initial = Object.keys(bindings).sort(compareUid)

    // 同步必须用严格读法：读失败不能被当成空库，否则所有卡都会被发成墓碑。
    const cards = typeof this.knowledge.listStrict === 'function'
      ? this.knowledge.listStrict()
      : this.knowledge.list()
    if (!Array.isArray(cards)) throw fail('E_STATE', 'knowledge 严格读取没有返回数组')
    if (cards.length > MAX_CARDS) {
      throw fail('E_LIMIT', '本机知识卡 ' + cards.length + ' 张，超过上限 ' + MAX_CARDS)
    }

    const cardById = new Map()
    for (const c of cards) if (c && !cardById.has(c.id)) cardById.set(c.id, c)

    // 一张本地卡只认一个 uid；重复绑定后面的按孤儿处理。
    const boundCard = new Map()
    for (const uid of initial) {
      if (!uid.startsWith('k:')) continue
      const id = bindings[uid]
      if (!cardById.has(id) || boundCard.has(id)) continue
      boundCard.set(id, uid)
    }

    const used = new Set()
    for (const card of cards) {
      let uid = boundCard.get(card.id)
      if (uid) {
        const rec = byUid.get(uid)
        if (!rec || rec.value === null) {
          // 绑定还在但记录已是墓碑（本地又新增了同 id）：换新 uid，别复活旧墓碑。
          delete bindings[uid]
          uid = null
        } else {
          const payload = cardPayload(card)
          if (jsonText(payload, '知识卡 payload') !== jsonText(rec.value, '知识卡 payload')) {
            rec.clock = bumpClock(rec.clock, device)
            rec.value = payload
          }
          used.add(uid)
          continue
        }
      }

      uid = newUid('k', byUid)
      const rec = { uid: uid, clock: clockOne(device), value: cardPayload(card) }
      records.push(rec)
      byUid.set(uid, rec)
      bindings[uid] = card.id
      used.add(uid)
    }

    // 绑定值在本地找不到行 → 墓碑（本地删卡）。
    for (const uid of initial) {
      if (!uid.startsWith('k:') || used.has(uid)) continue
      const rec = byUid.get(uid)
      if (rec && rec.value !== null) {
        rec.clock = bumpClock(rec.clock, device)
        rec.value = null
      }
      delete bindings[uid]
    }

    // historyRead / JsonStore.read 在读失败时会静默返回 []；同步路径必须切到严格模式，
    // 否则「读失败」会被当成「收藏全没了」，给对端发一整批墓碑。
    const history = this._readHistory()
    if (!Array.isArray(history)) throw fail('E_STATE', 'historyRead() 没有返回数组')
    const list = history
    // 严格读只保证「JSON 是个数组」；内容坏同样不能当空库，否则收藏会被整批误删。
    for (let i = 0; i < list.length; i += 1) {
      const e = list[i]
      if (!e || typeof e !== 'object' || Array.isArray(e)) {
        throw fail('E_STATE', '历史记录[' + i + '] 不是对象')
      }
      if (!Number.isSafeInteger(e.t) || e.t < 0) {
        throw fail('E_STATE', '历史记录[' + i + '].t 不是非负安全整数')
      }
      if (e.star !== undefined && typeof e.star !== 'boolean') {
        throw fail('E_STATE', '历史记录[' + i + '].star 非法')
      }
    }

    const histByT = new Map()
    for (const e of list) {
      if (!e || !Number.isSafeInteger(e.t) || e.t < 0) continue
      if (!histByT.has(e.t)) histByT.set(e.t, e)
    }

    const boundHist = new Map()
    for (const uid of initial) {
      if (!uid.startsWith('h:')) continue
      const t = bindings[uid]
      if (!histByT.has(t) || boundHist.has(t)) continue
      boundHist.set(t, uid)
    }

    const seenT = new Set()
    for (const entry of list) {
      if (!entry || !Number.isSafeInteger(entry.t) || entry.t < 0) continue
      if (seenT.has(entry.t)) continue
      seenT.add(entry.t)

      const starred = entry.star === true
      const uid = boundHist.get(entry.t)

      if (!starred) {
        // 没收藏的普通历史永远不导出；已有绑定交给下面的孤儿扫描做取消收藏墓碑。
        continue
      }

      if (uid) {
        const rec = byUid.get(uid)
        if (rec && rec.value !== null) {
          const createdAt = Number.isSafeInteger(rec.value.createdAt) ? rec.value.createdAt : entry.t
          const payload = historyPayload(entry, createdAt)
          if (jsonText(payload, '收藏历史 payload') !== jsonText(rec.value, '收藏历史 payload')) {
            rec.clock = bumpClock(rec.clock, device)
            rec.value = payload
          }
          used.add(uid)
          continue
        }

        // 取消收藏后又重新收藏：行还在，墓碑也在 —— 用原 uid 复活（clock++），
        // 不要长出一条新收藏。createdAt 从这次重新收藏算。
        const payload = historyPayload(entry, entry.t)
        if (rec) {
          rec.clock = bumpClock(rec.clock, device)
          rec.value = payload
        } else {
          const revived = { uid: uid, clock: clockOne(device), value: payload }
          records.push(revived)
          byUid.set(uid, revived)
        }
        used.add(uid)
        continue
      }

      const fresh = newUid('h', byUid)
      const rec = { uid: fresh, clock: clockOne(device), value: historyPayload(entry, entry.t) }
      records.push(rec)
      byUid.set(fresh, rec)
      bindings[fresh] = entry.t
      used.add(fresh)
    }

    // 取消收藏 / 行被删 → 取消收藏墓碑（不删普通历史；apply 只把 star 拿掉）。
    for (const uid of initial) {
      if (!uid.startsWith('h:') || used.has(uid)) continue
      const rec = byUid.get(uid)
      if (rec && rec.value !== null) {
        rec.clock = bumpClock(rec.clock, device)
        rec.value = null
      }
      // 行还在（只是没 star）就保留 binding：以后重新收藏可以复用同一个 t / uid。
      if (!histByT.has(bindings[uid])) delete bindings[uid]
    }

    if (records.length > MAX_RECORDS) {
      throw fail('E_LIMIT', '同步记录 ' + records.length + ' 条，超过上限 ' + MAX_RECORDS)
    }
    records.sort((a, b) => compareUid(a.uid, b.uid))

    return {
      deviceId: device,
      records: records,
      bindings: bindings,
      cards: cards,
      history: list
    }
  }

  /* ---------------- 对外：导出 ---------------- */

  /**
   * 观察本机并写 sidecar，返回 records（同步）。写失败抛出。
   * 发出前再跑一遍 normalizeIncoming：本机 payload 超限 / 时间戳非法时直接失败，
   * 绝不把截断或半截内容发给对端。
   */
  export() {
    this._ensureRecovered()
    const observed = this._observe()
    const records = normalizeIncoming(observed.records)
    const meta = this._metaFor(records)
    const state = {
      deviceId: observed.deviceId,
      records: records,
      bindings: observed.bindings,
      sides: meta.sides,
      resolved: meta.resolved
    }
    this._writeState(state)
    this.state = state
    return records
  }

  /* ---------------- 对外：合并 ---------------- */

  /**
   * 合并远端记录并落盘。
   * @param {Array} remoteRecords
   * @returns {{records: Array, conflicts: number, cards: number, stars: number, stats: object}}
   *   stats = { cards: {...}, history: {...} }，按本机这次 apply 的 before / after 统计；
   *   conflicts 只统计本轮新物化到本机的副本（value:null 的副本不计），修掉 merge 只报
   *   「本轮新创建」导致远端已物化副本漏报的问题。
   */
  apply(remoteRecords) {
    // 有 pending journal 必须先恢复完，不能让旧 state 覆盖恢复结果。
    this._ensureRecovered()

    // 先把远端整批校验完：任意一条不合法，整次拒绝，什么都不写。
    const incoming = normalizeIncoming(remoteRecords)

    // 「先重新 export」：本机改动先进 records，再和远端合并。
    const observed = this._observe()
    const merged = merge(observed.records, incoming)

    if (merged.records.length > MAX_RECORDS) {
      throw fail('E_LIMIT', '合并后记录 ' + merged.records.length + ' 条，超过上限 ' + MAX_RECORDS)
    }

    // 合并输出再规范一遍标准 payload（含冲突副本），保证写进本机的是同一套字段。
    const records = merged.records.map((rec) => {
      const kind = kindOf(rec.uid)
      return {
        uid: rec.uid,
        clock: normalizeClock(rec.clock, 'merged.clock'),
        value: normalizeValue(kind, rec.value)
      }
    })

    const target = this._materialize(observed, records)

    if (target.cards.length > MAX_CARDS) {
      throw fail('E_LIMIT', '合并后知识卡 ' + target.cards.length + ' 张，超过上限 ' + MAX_CARDS)
    }

    // side 标注：只有确定性副本 uid 重算或 canonical payload 唯一匹配才写；数组存在性
    // 不能证明来源（对端转发旧副本不算证据），unknown 不写盘（listConflicts 会补 unknown）。
    const localByUid = new Map(observed.records.map((r) => [r.uid, r]))
    const peerByUid = new Map(incoming.map((r) => [r.uid, r]))
    const sides = Object.assign({}, this.state.sides || {})
    for (const rec of records) {
      if (rec.value === null || !isConflictUid(rec.uid)) continue
      const baseUid = baseUidOf(rec.uid)
      if (!baseUid) continue
      const side = annotateSide(
        rec.uid, baseUid,
        localByUid.get(baseUid), peerByUid.get(baseUid),
        rec
      )
      if (side !== 'unknown') sides[rec.uid] = side
    }

    const meta = this._metaFor(records)
    // 只保留仍活着的副本 side；已 tombstone 的副本连同标注一起没意义。
    const sidesOut = {}
    for (const rec of records) {
      if (rec.value !== null && isConflictUid(rec.uid) && sides[rec.uid]) sidesOut[rec.uid] = sides[rec.uid]
    }
    meta.sides = sidesOut

    const state = {
      deviceId: observed.deviceId,
      records: records,
      bindings: target.bindings,
      sides: meta.sides,
      resolved: meta.resolved
    }
    const journal = {
      knowledge: target.cards.map((c) => K.toJson(c)),
      history: target.history,
      state: state
    }

    // journal 提交前也全量校验；不合法的目标绝不落盘。
    const commit = this._validateJournal(journal)

    // 1) journal 先落盘（0600）。journal 成功即视为事务已提交。
    writeAtomic(this.journalFile, JSON.stringify(commit), 0o600)

    // 2) 提交三份目标：卡（走 Store，刷新内存副本）→ 历史（集成方回调）→ sidecar。
    //    historyWrite 返回 false 必须抛：journal 保留，下次 recover 重放，绝不能 ack。
    this._applying = true
    try {
      this.knowledge.replaceFromSync(commit.knowledge, { internal: true })
      const ok = this.historyWrite(commit.history)
      if (ok === false) throw fail('E_WRITE', 'historyWrite 返回 false（journal 已保留）')
      this._writeState(commit.state)
    } finally {
      this._applying = false
    }

    // 3) 事务完成才删 journal；删失败不抛 —— 下次 recover 幂等重放。
    try { fs.unlinkSync(this.journalFile) } catch {}

    this.state = commit.state

    const stats = {
      cards: statsForKind(
        'k', observed.cards, commit.knowledge,
        observed.records, commit.state.records, observed.bindings, commit.state.bindings
      ),
      // 收藏的同步范围就是 ★：未收藏的普通历史不进统计（取消收藏 = deleted）。
      history: statsForKind(
        'h', observed.history.filter((e) => e && e.star === true),
        commit.history.filter((e) => e && e.star === true),
        observed.records, commit.state.records, observed.bindings, commit.state.bindings
      )
    }

    const stars = target.history.reduce((n, e) => n + (e && e.star === true ? 1 : 0), 0)
    return {
      records: commit.state.records,
      conflicts: stats.cards.conflicts + stats.history.conflicts,
      cards: commit.knowledge.length,
      stars: stars,
      stats: stats
    }
  }

  /* ---------------- 对外：冲突 ---------------- */

  /**
   * 列出冲突副本（只读）。每项：
   *   { baseUid, copyUid, kind, baseRowId, copyRowId, baseValue, copyValue, side, resolved }
   * - baseUid 是最后一个 ~conflict~ 前的直接父（嵌套副本逐层向上）。
   * - 只要副本还活着就一定列出：主记录被删（baseValue 为 null）时副本仍可经 KEEP_COPY
   *   恢复，不能因为「主墓碑」把用户可见的未解决冲突藏掉。副本墓碑没有 resolved
   *   记录时不列（那只是已了结的败方），写过 resolved 的已解决副本照旧列出。
   * - side：localKept / peerKept / unknown（旧数据没有标注时诚实给 unknown）。
   * - resolved：{ pick, at, baseUid } 或 null。
   */
  listConflicts() {
    this._ensureRecovered()
    const records = this.state.records
    const byUid = new Map(records.map((r) => [r.uid, r]))
    const bindings = this.state.bindings || {}
    const sides = this.state.sides || {}
    const resolved = this.state.resolved || {}
    const out = []
    for (const rec of records) {
      if (!isConflictUid(rec.uid)) continue
      const baseUid = baseUidOf(rec.uid)
      if (!baseUid) continue
      const base = byUid.get(baseUid) || null
      const meta = resolved[rec.uid] || null
      const copyLive = rec.value !== null
      if (!meta && !copyLive) continue
      out.push({
        baseUid: baseUid,
        copyUid: rec.uid,
        kind: kindOf(rec.uid),
        baseRowId: hasOwn(bindings, baseUid) ? bindings[baseUid] : null,
        copyRowId: hasOwn(bindings, rec.uid) ? bindings[rec.uid] : null,
        baseValue: base ? base.value : null,
        copyValue: rec.value,
        side: sides[rec.uid] || 'unknown',
        resolved: meta
      })
    }
    out.sort((a, b) => compareUid(a.copyUid, b.copyUid))
    return out
  }

  /**
   * 解决一个冲突副本。pick（与 Android 端同一套语义）：
   *   KEEP_BASE  主 uid 留 base 的最新 payload，副本 tombstone
   *   KEEP_COPY  主 uid 改写 copy 的最新 payload（业务行同步成 copy 的值），副本 tombstone
   *   KEEP_BOTH  两边都留（副本 uid 不变），只是把两边 clock 抬到同一个 N
   * 三种选择都不让副本 uid 存活：KEEP_COPY 只是把副本内容搬到主 uid，避免「同一冲突在两端
   * 各自选保留副本」时一端留副本 uid、一端留主 uid 而合并出重复。
   * N = bump(max(base.clock, copy.clock), 本机 deviceId)：本机后续再同步不会被旧值翻盘。
   * 事务顺序与 apply 完全一致：journal → knowledge → history → sidecar；任一步失败都不 ack，
   * journal 保留待下次恢复。history 的败方（副本 uid）投影成「取消收藏」，同时删 binding，
   * 防止重新收藏把败方副本复活。
   */
  resolveConflict(copyUid, pick) {
    if (typeof copyUid !== 'string' || !isConflictUid(copyUid)) {
      throw fail('E_INPUT', 'copyUid 必须是冲突副本 uid')
    }
    if (RESOLVE_PICKS.indexOf(pick) < 0) {
      throw fail('E_INPUT', 'pick 必须是 KEEP_BASE / KEEP_COPY / KEEP_BOTH')
    }
    const baseUid = baseUidOf(copyUid)
    if (!baseUid) throw fail('E_INPUT', 'copyUid 没有有效的父记录: ' + copyUid)

    this._ensureRecovered()

    // 先 observe：把本地最新的业务行并进 records，存活方拿到的就是「己方最新 payload」。
    const observed = this._observe()
    const records = observed.records.map((rec) => {
      const kind = kindOf(rec.uid)
      return {
        uid: rec.uid,
        clock: normalizeClock(rec.clock, 'resolve.clock'),
        value: normalizeValue(kind, rec.value)
      }
    })
    const byUid = new Map(records.map((r) => [r.uid, r]))
    const base = byUid.get(baseUid) || null
    const copy = byUid.get(copyUid) || null
    if (!base) throw fail('E_CONFLICT', '找不到冲突父记录: ' + baseUid)
    if (!copy) throw fail('E_CONFLICT', '找不到冲突副本: ' + copyUid)
    if (copy.value === null) throw fail('E_CONFLICT', '冲突副本已经是墓碑: ' + copyUid)

    const clock = bumpClock(maxClock(base.clock, copy.clock), observed.deviceId)
    const dropBindings = new Set()

    if (pick === 'KEEP_BOTH') {
      if (base.value === null || copy.value === null) {
        throw fail('E_CONFLICT', 'KEEP_BOTH 需要主记录和副本都有内容')
      }
      base.clock = clock
      copy.clock = clock
    } else {
      const kept = pick === 'KEEP_BASE' ? base.value : copy.value
      if (kept === null) throw fail('E_CONFLICT', '存活方没有可保留的内容')
      // 跨端统一：主 uid 恒为存活方，副本 uid 恒墓碑。KEEP_COPY 只是把副本最新 payload
      // 搬到主 uid；_materialize 会把主 uid 绑定的同一行业务行改写成这份 payload。
      base.value = kept
      base.clock = clock
      copy.value = null
      copy.clock = clock
      // history 副本：投影后删 binding + star（star 由 _materialize 去），避免重新收藏复活。
      if (kindOf(copy.uid) === 'h') dropBindings.add(copy.uid)
    }

    const target = this._materialize(observed, records, { dropBindings: dropBindings })
    if (target.cards.length > MAX_CARDS) {
      throw fail('E_LIMIT', '解决冲突后知识卡 ' + target.cards.length + ' 张，超过上限 ' + MAX_CARDS)
    }

    const meta = this._metaFor(records)
    meta.resolved[copyUid] = { pick: pick, at: Date.now(), baseUid: baseUid }
    const state = {
      deviceId: observed.deviceId,
      records: records,
      bindings: target.bindings,
      sides: meta.sides,
      resolved: meta.resolved
    }
    const journal = {
      knowledge: target.cards.map((c) => K.toJson(c)),
      history: target.history,
      state: state
    }
    const commit = this._validateJournal(journal)

    writeAtomic(this.journalFile, JSON.stringify(commit), 0o600)
    this._applying = true
    try {
      this.knowledge.replaceFromSync(commit.knowledge, { internal: true })
      const ok = this.historyWrite(commit.history)
      if (ok === false) throw fail('E_WRITE', 'historyWrite 返回 false（journal 已保留）')
      this._writeState(commit.state)
    } finally {
      this._applying = false
    }
    try { fs.unlinkSync(this.journalFile) } catch {}

    this.state = commit.state
    return {
      pick: pick,
      baseUid: baseUid,
      copyUid: copyUid,
      conflicts: this.listConflicts().length
    }
  }

  /**
   * 把合并后的 records 落成本地行：
   * - 卡墓碑 → 删卡；历史墓碑 → 只去掉 star，普通历史保留。
   * - 老 uid 复用原 binding；新 uid（含冲突副本）分配无冲突的本地 id / t。
   * - 不同 uid 即使 front 相同也各留一行，不在这里做业务去重。
   */
  _materialize(observed, records, options) {
    const cards = observed.cards
    const history = observed.history
    const oldBindings = observed.bindings
    const dropBindings = (options && options.dropBindings) || null

    const cardById = new Map()
    for (const c of cards) if (c && !cardById.has(c.id)) cardById.set(c.id, c)
    const histByT = new Map()
    for (const e of history) {
      if (!e || !Number.isSafeInteger(e.t) || e.t < 0) continue
      if (!histByT.has(e.t)) histByT.set(e.t, e)
    }

    // 先登记墓碑，避免同一轮里新记录误用正在删掉的本地 id/t。
    const removedCardIds = new Set()
    const unstarTs = new Set()
    const tombstoneBindings = {}
    for (const rec of records) {
      if (rec.value !== null) continue
      const old = oldBindings[rec.uid]
      if (!Number.isSafeInteger(old) || old < 0) continue
      if (kindOf(rec.uid) === 'k' && cardById.has(old)) removedCardIds.add(old)
      if (kindOf(rec.uid) === 'h' && histByT.has(old)) {
        unstarTs.add(old)
        // 普通历史行还在；保留 uid → t，重新收藏时还能对上同一行。
        // 冲突解决里的败方例外：binding 必须删掉，否则用户重新收藏同一行会复活败方副本。
        if (!(dropBindings && dropBindings.has(rec.uid))) tombstoneBindings[rec.uid] = old
      }
    }

    const bindings = Object.assign({}, tombstoneBindings)
    const claimedCardIds = new Set()
    const claimedTs = new Set()
    const cardLive = new Map()
    const histLive = new Map()
    const newCards = []
    const newHistory = []

    let maxCardId = 0
    for (const c of cards) {
      if (c && Number.isSafeInteger(c.id) && c.id > maxCardId) maxCardId = c.id
    }
    // sidecar 里仍然存在的旧 binding 也算「这个 id 用过」：即使水位文件缺失、
    // 或卡已经被删掉，也不能把同一个 id 重新发给新 uid。
    for (const uid of Object.keys(oldBindings)) {
      if (!uid.startsWith('k:')) continue
      const id = oldBindings[uid]
      if (Number.isSafeInteger(id) && id > maxCardId) maxCardId = id
    }
    // 删除最大 id 后不能从「现有最大 id + 1」重新分配：旧 binding 会把新卡当成老卡的编辑。
    // 优先用真实 Store 的 nextId()；main 把 Store 包了一层时（没有 nextId），直接读
    // knowledge.json.ids.json 这份持久水位文件，语义相同。
    const next = typeof this.knowledge.nextId === 'function'
      ? this.knowledge.nextId()
      : readCardWatermark(this.dir, cards)
    if (!Number.isSafeInteger(next) || next < 1) {
      throw fail('E_STATE', '知识卡 id 水位非法: ' + String(next))
    }
    if (next - 1 > maxCardId) maxCardId = next - 1
    let maxT = 0
    for (const e of history) {
      if (e && Number.isSafeInteger(e.t) && e.t > maxT) maxT = e.t
    }
    for (const uid of Object.keys(oldBindings)) {
      if (!uid.startsWith('h:')) continue
      const t = oldBindings[uid]
      if (Number.isSafeInteger(t) && t > maxT) maxT = t
    }
    const now = Date.now()

    for (const rec of records) {
      if (rec.value === null) continue
      const kind = kindOf(rec.uid)
      const old = oldBindings[rec.uid]

      if (kind === 'k') {
        let id = null
        if (
          Number.isSafeInteger(old) && old >= 0 &&
          cardById.has(old) && !claimedCardIds.has(old) && !removedCardIds.has(old)
        ) {
          id = old
        }
        if (id === null) id = (maxCardId += 1)
        claimedCardIds.add(id)
        bindings[rec.uid] = id

        const card = cardFromPayload(id, rec.value)
        if (cardById.has(id)) cardLive.set(id, card)
        else newCards.push({ uid: rec.uid, card: card })
        continue
      }

      let t = null
      if (
        Number.isSafeInteger(old) && old > 0 &&
        histByT.has(old) && !claimedTs.has(old) && !unstarTs.has(old)
      ) {
        t = old
      }
      if (t === null) {
        t = pickT(rec.value.createdAt, claimedTs, histByT, maxT, now)
        if (t > maxT) maxT = t
      }
      claimedTs.add(t)
      bindings[rec.uid] = t

      const row = historyFromPayload(t, rec.value)
      if (histByT.has(t)) histLive.set(t, row)
      else newHistory.push({ uid: rec.uid, row: row, createdAt: rec.value.createdAt })
    }

    // 卡：原有顺序保留；本轮新增的按 createdAt 倒序放最前面。
    const existingCards = []
    for (const c of cards) {
      if (!c) continue
      if (removedCardIds.has(c.id)) continue
      existingCards.push(cardLive.has(c.id) ? cardLive.get(c.id) : c)
    }
    newCards.sort((a, b) =>
      (b.card.createdAt - a.card.createdAt) || compareUid(a.uid, b.uid)
    )
    const cardsOut = newCards.map((x) => x.card).concat(existingCards)

    // 历史：原有顺序保留；墓碑只去掉 star；新记录按 createdAt 倒序放最前面。
    const existingHistory = []
    for (const e of history) {
      if (!e) continue
      if (!Number.isSafeInteger(e.t) || e.t < 0) continue
      if (histLive.has(e.t)) existingHistory.push(histLive.get(e.t))
      else if (unstarTs.has(e.t)) existingHistory.push(withoutStar(e))
      else existingHistory.push(e)
    }
    newHistory.sort((a, b) =>
      (b.createdAt - a.createdAt) || compareUid(a.uid, b.uid)
    )
    const mergedHistory = newHistory.map((x) => x.row).concat(existingHistory)
    const historyOut = X.historyTrim(mergedHistory, HISTORY_MAX)

    return { cards: cardsOut, history: historyOut, bindings: bindings }
  }
}

/* ------------------------------------------------------------------ 小工具 */

/**
 * main 有时会把 Knowledge.Store 包一层再交给 LanStore（只暴露 list / replaceFromSync），
 * 这时拿不到实例的 nextId()。水位文件与 knowledge.json 同目录，直接读同一份文件。
 * 水位文件不存在 / 损坏时退回「现有最大 id + 1」，与旧版本行为兼容。
 */
function readCardWatermark(dir, cards) {
  let next = 1
  const suffix = K.IDS_SUFFIX || '.ids.json'
  const meta = readJsonFile(path.join(dir, K.FILE + suffix))
  if (meta.exists && !meta.error && isPlainObject(meta.value)) {
    const n = meta.value.nextId
    if (Number.isSafeInteger(n) && n > 0) next = n
  }
  let max = 0
  for (const c of (cards || [])) {
    if (c && Number.isSafeInteger(c.id) && c.id > max) max = c.id
  }
  return Math.max(next, max + 1)
}

function clockOne(device) {
  const clock = {}
  clock[device] = 1
  return clock
}

function bumpClock(clock, device) {
  const out = Object.assign({}, clock)
  const cur = out[device]
  if (cur === undefined) out[device] = 1
  else {
    if (!Number.isSafeInteger(cur) || cur <= 0 || cur >= MAX_CLOCK) {
      throw fail('E_CLOCK', '本机时钟不是 1..' + MAX_CLOCK + ' 的整数: ' + String(cur))
    }
    out[device] = cur + 1
  }
  return out
}

function newUid(kind, byUid) {
  let uid = ''
  do {
    uid = kind + ':' + crypto.randomUUID()
  } while (byUid.has(uid))
  return uid
}

/** 新 uid 的本地 t：优先用 payload.createdAt，撞了就往后找；再不行用当前时间。 */
function pickT(preferred, claimed, existing, maxT, now) {
  let t = Number.isSafeInteger(preferred) && preferred > 0 ? preferred : 0
  if (t <= 0) t = Math.max(now, maxT + 1, 1)
  while (claimed.has(t) || existing.has(t)) {
    t += 1
    if (!Number.isSafeInteger(t)) {
      t = Math.max(now, maxT + 1, 1)
      while (claimed.has(t) || existing.has(t)) t += 1
      break
    }
  }
  return t
}

module.exports = LanStore
module.exports.LanStore = LanStore
module.exports.MAX_CARDS = MAX_CARDS
module.exports.MAX_RECORDS = MAX_RECORDS
module.exports.HISTORY_MAX = HISTORY_MAX
module.exports.LIMITS = LIMITS
module.exports.MAX_CLOCK = MAX_CLOCK
module.exports.SIDECAR = SIDECAR
module.exports.JOURNAL = JOURNAL
module.exports.CONFLICT_MARK = CONFLICT_MARK
module.exports.RESOLVE_PICKS = RESOLVE_PICKS
module.exports.isConflictUid = isConflictUid
module.exports.baseUidOf = baseUidOf
