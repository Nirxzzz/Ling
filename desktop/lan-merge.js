/* lan-merge.js — 双端局域网同步的合并内核。纯 Node、零依赖、CommonJS。
 *
 * ## 数据模型（本模块只做算法，不碰持久层、不猜业务字段）
 * record = {
 *   uid:   string             // 全局唯一；kind 由前缀决定：'k:' 知识卡 / 'h:' 收藏历史
 *   clock: { [deviceId]: n }  // 矢量时钟；deviceId 非空字符串，n 为正安全整数
 *   value: object | null      // null 表示删除；对象是业务 payload，本模块原样搬运
 * }
 * 知识卡 payload：{ deck, front, back, source, createdAt, fsrs, origin }（无本地 id）
 * 收藏历史 payload：{ kind, label, text, src, star:true, createdAt }
 * 冲突副本的 uid 形如 `<原 uid>~conflict~<sha256 前 24 位 hex>`（哈希种子是败方原 clock
 * 与败方 value），前缀仍是 k:/h:，因此副本的 kind 与原件一致，可被下游当成普通记录消费。
 *
 * ## 合并规则（同 uid）
 * 1. clock 逐点取 max。
 * 2. 一方 clock 严格支配另一方 → 支配者胜（删除支配编辑 = 真删除；编辑支配删除 = 复活）。
 * 3. 并发（clock 相同或互不支配）：
 *    - 两边 value 的 canonical 串相同 → 合一，只留一份。
 *    - 一边 null、一边非 null → 主 uid 保持 null，非 null 版本进冲突副本（删除不吞内容）。
 *    - 两边非 null 且不同 → 按 canonical(value) 字典序取大者为主版本，另一个进冲突副本。
 *      绝不按到达顺序做静默 LWW。
 * 4. 副本 uid = `<原 uid>~conflict~<sha256(canonical({uid, 败方原 clock, 败方 value})) 前 24 位>`，
 *    哈希用败方记录自己的 clock，不是合并后的 clock；副本记录本身的 clock 仍是合并后的 clock。
 * 5. 结果按 uid 升序（UTF-16 码元，非 locale）返回；交换两端得到逐字节相同的结果。
 * 6. 同一份合并结果再合并（哪怕再掺回旧的一端）不会生成新副本。
 * 7. conflicts 只统计本次**新创建**的冲突副本数；已存在的同名副本只合一、不计数。
 *
 * ## 复杂度
 * O(n log n + n·|value|)：排序 uid + 每条一次 canonical + 冲突时一次 SHA256，
 * 不做记录两两比较。单侧上限 10000 条；value 的字节大小限制属于 transport，本模块不管。
 *
 * ## 异常（err.code）
 * E_INPUT      入参不是数组 / record 不是对象 / uid 非法 / value 不是对象或 null
 * E_UID_DUP    同一侧 records 内 uid 重复
 * E_CLOCK      clock 不是对象、deviceId 非法、计数不是正安全整数
 * E_LIMIT      单侧超过 10000 条
 * E_JSON       value 含非 JSON 值（undefined / NaN / Infinity / bigint / 函数 / Date / Map 等）
 * E_CYCLE      value 存在循环引用
 */
'use strict'

const crypto = require('node:crypto')

const MAX_RECORDS = 10000
const CONFLICT_MARK = '~conflict~'
const HASH_HEX_LEN = 24

function fail(Ctor, code, message) {
  const err = new Ctor('lan-merge: ' + message + ' [' + code + ']')
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

/* ------------------------------------------------------------------ canonical */

/** 递归按 key 排序后 JSON 序列化；数组保序。只接受 JSON 合法值。 */
function canonical(value) {
  return encode(value, '$', new Set())
}

function encode(value, path, seen) {
  if (value === null) return 'null'
  const type = typeof value
  if (type === 'string') return JSON.stringify(value)
  if (type === 'boolean') return value ? 'true' : 'false'
  if (type === 'number') {
    if (!Number.isFinite(value)) {
      throw fail(TypeError, 'E_JSON', path + ' 不是合法 JSON 数字: ' + String(value))
    }
    return JSON.stringify(value)
  }
  if (type !== 'object') {
    // undefined / function / symbol / bigint
    throw fail(TypeError, 'E_JSON', path + ' 不是合法 JSON 值: ' + type)
  }
  if (seen.has(value)) throw fail(TypeError, 'E_CYCLE', path + ' 存在循环引用')
  seen.add(value)

  let text
  if (Array.isArray(value)) {
    const items = new Array(value.length)
    for (let i = 0; i < value.length; i += 1) {
      if (!(i in value)) throw fail(TypeError, 'E_JSON', path + '[' + i + '] 不是合法 JSON 值: 数组空位')
      items[i] = encode(value[i], path + '[' + i + ']', seen)
    }
    text = '[' + items.join(',') + ']'
  } else {
    const proto = Object.getPrototypeOf(value)
    if (proto !== Object.prototype && proto !== null) {
      const name = (value.constructor && value.constructor.name) || 'unknown'
      throw fail(TypeError, 'E_JSON', path + ' 不是纯对象: ' + name)
    }
    const keys = Object.keys(value).sort()
    const parts = new Array(keys.length)
    for (let i = 0; i < keys.length; i += 1) {
      const key = keys[i]
      parts[i] = JSON.stringify(key) + ':' + encode(value[key], path + '.' + key, seen)
    }
    text = '{' + parts.join(',') + '}'
  }

  seen.delete(value)
  return text
}

/* ------------------------------------------------------------------ 校验 */

function normalizeClock(clock, path) {
  if (!isPlainObject(clock)) {
    throw fail(TypeError, 'E_CLOCK', path + ' 必须是 { deviceId: 正整数 } 对象')
  }
  const out = {}
  const keys = Object.keys(clock).sort()
  for (let i = 0; i < keys.length; i += 1) {
    const key = keys[i]
    if (key.length === 0) throw fail(TypeError, 'E_CLOCK', path + ' 含空 deviceId')
    if (key === '__proto__') throw fail(TypeError, 'E_CLOCK', path + ' 的 deviceId 不能是 __proto__')
    const n = clock[key]
    if (!Number.isSafeInteger(n) || n <= 0) {
      throw fail(TypeError, 'E_CLOCK', path + '.' + key + ' 必须是正整数: ' + String(n))
    }
    out[key] = n
  }
  return out
}

function validateRecords(list, label) {
  if (!Array.isArray(list)) throw fail(TypeError, 'E_INPUT', label + ' 必须是数组')
  if (list.length > MAX_RECORDS) {
    throw fail(RangeError, 'E_LIMIT', label + ' 有 ' + list.length + ' 条，超过上限 ' + MAX_RECORDS)
  }
  const byUid = new Map()
  for (let i = 0; i < list.length; i += 1) {
    const item = list[i]
    const at = label + '[' + i + ']'
    if (!isPlainObject(item)) throw fail(TypeError, 'E_INPUT', at + ' 必须是 { uid, clock, value } 对象')
    const uid = item.uid
    if (typeof uid !== 'string' || uid.length === 0) {
      throw fail(TypeError, 'E_INPUT', at + '.uid 必须是非空字符串')
    }
    if (byUid.has(uid)) throw fail(TypeError, 'E_UID_DUP', label + ' 中 uid 重复: ' + uid)
    const clock = normalizeClock(item.clock, at + '.clock')
    const value = item.value
    if (value !== null && !isPlainObject(value)) {
      throw fail(TypeError, 'E_INPUT', at + '.value 必须是对象或 null')
    }
    byUid.set(uid, {
      uid: uid,
      clock: clock,
      value: value,
      canon: value === null ? null : canonical(value)
    })
  }
  return byUid
}

/* ------------------------------------------------------------------ 时钟 */

function mergeClock(a, b) {
  const keys = Object.keys(a)
  const bKeys = Object.keys(b)
  for (let i = 0; i < bKeys.length; i += 1) {
    if (!hasOwn(a, bKeys[i])) keys.push(bKeys[i])
  }
  keys.sort()
  const out = {}
  for (let i = 0; i < keys.length; i += 1) {
    const key = keys[i]
    const av = hasOwn(a, key) ? a[key] : 0
    const bv = hasOwn(b, key) ? b[key] : 0
    out[key] = av > bv ? av : bv
  }
  return out
}

/** a 是否严格支配 b：所有分量 >=，且至少一个 >。缺失分量按 0 计。 */
function dominates(a, b) {
  let strict = false
  const bKeys = Object.keys(b)
  for (let i = 0; i < bKeys.length; i += 1) {
    const key = bKeys[i]
    const av = hasOwn(a, key) ? a[key] : 0
    if (av < b[key]) return false
    if (av > b[key]) strict = true
  }
  const aKeys = Object.keys(a)
  for (let i = 0; i < aKeys.length; i += 1) {
    const key = aKeys[i]
    if (!hasOwn(b, key) && a[key] > 0) {
      strict = true
      break
    }
  }
  return strict
}

/* ------------------------------------------------------------------ merge */

function sha256Hex(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex')
}

/** 副本 uid 只由「原 uid + 败方原 clock + 败方 value」决定，重复同步不会漂移。 */
function conflictUid(uid, loser) {
  const seal = canonical({ uid: uid, clock: loser.clock, value: loser.value })
  return uid + CONFLICT_MARK + sha256Hex(seal).slice(0, HASH_HEX_LEN)
}

function withClock(rec, clock) {
  return { uid: rec.uid, clock: clock, canon: rec.canon, value: rec.value }
}

function tombstone(uid, clock) {
  return { uid: uid, clock: clock, canon: null, value: null }
}

/** 败方版本成副本：clock 取合并后的 clock，uid 哈希用败方自己的原 clock。 */
function conflictCopy(uid, loser, clock) {
  if (loser.canon === null) return null
  return {
    uid: conflictUid(uid, loser),
    clock: clock,
    canon: loser.canon,
    value: loser.value
  }
}

function mergeTwo(x, y) {
  const clock = mergeClock(x.clock, y.clock)
  if (dominates(x.clock, y.clock)) return { main: withClock(x, clock), copy: null }
  if (dominates(y.clock, x.clock)) return { main: withClock(y, clock), copy: null }
  if (x.canon === y.canon) return { main: withClock(x, clock), copy: null }
  // 并发且值不同：删除优先于编辑，非 null 之间 canonical 较大者当主版本
  if (x.canon === null) return { main: tombstone(x.uid, clock), copy: conflictCopy(x.uid, y, clock) }
  if (y.canon === null) return { main: tombstone(x.uid, clock), copy: conflictCopy(x.uid, x, clock) }
  if (x.canon > y.canon) {
    return { main: withClock(x, clock), copy: conflictCopy(x.uid, y, clock) }
  }
  return { main: withClock(y, clock), copy: conflictCopy(x.uid, x, clock) }
}

function insertUid(uids, uid) {
  let lo = 0
  let hi = uids.length
  while (lo < hi) {
    const mid = (lo + hi) >>> 1
    if (uids[mid] < uid) lo = mid + 1
    else hi = mid
  }
  uids.splice(lo, 0, uid)
}

/**
 * 合并两端记录。
 * @param {Array} aRecords
 * @param {Array} bRecords
 * @returns {{records: Array, conflicts: number}} conflicts 为本次新生成的冲突副本数
 */
function merge(aRecords, bRecords) {
  const a = validateRecords(aRecords, 'aRecords')
  const b = validateRecords(bRecords, 'bRecords')

  // uid -> 待合并记录。新副本 uid 以当前 uid 为前缀，恒大于当前 uid，
  // 只会在之后处理，不需要回头重算已产出的记录。
  const pending = new Map()
  const uids = []
  const enqueue = (rec) => {
    const existing = pending.get(rec.uid)
    if (existing !== undefined) {
      existing.push(rec)
      return
    }
    pending.set(rec.uid, [rec])
    insertUid(uids, rec.uid)
  }
  for (const rec of a.values()) enqueue(rec)
  for (const rec of b.values()) enqueue(rec)

  const merged = []
  let conflicts = 0

  while (uids.length > 0) {
    const uid = uids.shift()
    const list = pending.get(uid)
    pending.delete(uid)

    let acc = list[0]
    for (let i = 1; i < list.length; i += 1) {
      const step = mergeTwo(acc, list[i])
      acc = step.main
      const copy = step.copy
      if (copy === null) continue
      const existing = pending.get(copy.uid)
      if (existing !== undefined) {
        // 已存在的同名副本只合一，不算新创建
        existing.push(copy)
      } else {
        conflicts += 1
        pending.set(copy.uid, [copy])
        insertUid(uids, copy.uid)
      }
    }
    merged.push(acc)
  }

  const list = new Array(merged.length)
  for (let i = 0; i < merged.length; i += 1) {
    const rec = merged[i]
    list[i] = {
      uid: rec.uid,
      clock: rec.clock,
      // 用 canonical 串回填，保证输出的 key 顺序与字节内容两端一致
      value: rec.canon === null ? null : JSON.parse(rec.canon)
    }
  }
  return { records: list, conflicts: conflicts }
}

module.exports = { merge: merge, canonical: canonical }
