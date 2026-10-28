/* Ling MCP 审计日志（v1.64）
 *
 * createAudit({dir, onerror}) -> { record, list, export, file }
 *
 * - 每行一条 JSON（JSONL），按月份分文件：audit-YYYYMM.jsonl，权限 0600，保留最近 6 个月。
 * - 只记「谁 / 调了什么 / 结果 / 耗时 / 是否确认」这类可审计事实：
 *   参数只存 键名 + 字节数 + 加盐哈希，绝不存正文、截图、令牌、密钥。
 * - 字段走白名单，未知字段直接丢弃；字符串截断，避免把整段正文写进日志。
 * - 写盘失败不打断用户操作（审计不该让功能失败），但一定通过 onerror/stderr 如实报告。
 */
'use strict'

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const KEEP_MONTHS = 6
const FILE_RE = /^audit-(\d{6})\.jsonl$/
const KEY_FILE = 'audit.key'
const STR_MAX = 240

/** 允许落盘的字段（其它一律丢弃）。 */
const FIELDS = [
  'event', 'ts', 'client', 'clientId', 'clientName', 'tool', 'level', 'target',
  'args', 'outcome', 'ok', 'confirm', 'ms', 'error', 'code', 'phase', 'note',
  'removed', 'skipped_edited', 'handouts_removed', 'phone', 'port', 'pair',
  'device', 'reason', 'count', 'revoked', 'scope', 'method'
]

const NUM_FIELDS = new Set(['ts', 'ok', 'ms', 'removed', 'skipped_edited', 'handouts_removed', 'handouts', 'port', 'count', 'revoked'])
const MAX_NUM = 1e12

function str(v, max) {
  const s = typeof v === 'string' ? v : (v === undefined || v === null ? '' : String(v))
  const one = s.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]+/g, ' ')
  return one.length > (max || STR_MAX) ? one.slice(0, max || STR_MAX) : one
}

function monthKey(d) {
  const dt = d || new Date()
  return String(dt.getUTCFullYear()) + String(dt.getUTCMonth() + 1).padStart(2, '0')
}

function readPepper(dir, onerror) {
  const file = path.join(dir, KEY_FILE)
  try {
    const buf = fs.readFileSync(file)
    if (buf.length === 32) return buf
    throw new Error('长度不符')
  } catch (e) {
    if (!e || e.code !== 'ENOENT') {
      // 已有但坏了：不静默重建（那会让新旧哈希不可比），改为每次运行用临时盐并如实告警。
      report(onerror, new Error('审计加盐密钥不可用（' + file + '），本次运行使用临时盐：' + (e.message || e)))
      return crypto.randomBytes(32)
    }
  }
  const key = crypto.randomBytes(32)
  try {
    const tmp = file + '.' + process.pid + '.tmp'
    const fd = fs.openSync(tmp, 'w', 0o600)
    try { fs.writeFileSync(fd, key); fs.fsyncSync(fd) } finally { fs.closeSync(fd) }
    fs.renameSync(tmp, file)
  } catch (e) {
    report(onerror, new Error('审计加盐密钥写入失败：' + (e.message || e)))
  }
  return key
}

function report(onerror, err) {
  try {
    if (typeof onerror === 'function') onerror(err)
    else process.stderr.write('[mcp-audit] ' + (err && err.message ? err.message : String(err)) + '\n')
  } catch {}
}

/** 参数只留形状：键名、字节数、加盐哈希。 */
function summarizeArgs(value, pepper) {
  if (value === undefined || value === null) return undefined
  let json
  try { json = JSON.stringify(value) } catch { json = String(value) }
  if (typeof json !== 'string') json = String(json)
  const keys = value && typeof value === 'object' && !Array.isArray(value)
    ? Object.keys(value).slice(0, 24).map((k) => str(k, 48))
    : []
  return {
    keys,
    bytes: Buffer.byteLength(json, 'utf8'),
    hash: crypto.createHmac('sha256', pepper).update(json).digest('hex').slice(0, 32)
  }
}

function createAudit(opts) {
  const dir = path.resolve(String((opts && opts.dir) || ''))
  if (!dir || dir === path.resolve('.')) throw new Error('createAudit 需要 dir')
  const onerror = opts && opts.onerror
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })

  const pepper = readPepper(dir, onerror)
  let prunedMonth = ''

  function fileFor(month) {
    return path.join(dir, 'audit-' + month + '.jsonl')
  }

  function prune(nowMonth) {
    if (prunedMonth === nowMonth) return
    prunedMonth = nowMonth
    let names
    try { names = fs.readdirSync(dir) } catch { return }
    const keep = new Set()
    const d = new Date()
    for (let i = 0; i < KEEP_MONTHS; i++) {
      const dt = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - i, 1))
      keep.add(monthKey(dt))
    }
    for (const n of names) {
      const m = FILE_RE.exec(n)
      if (!m || keep.has(m[1])) continue
      try { fs.unlinkSync(path.join(dir, n)) } catch {}
    }
  }

  function sanitize(event) {
    if (!event || typeof event !== 'object' || Array.isArray(event)) {
      throw new Error('audit.record 需要对象事件')
    }
    const out = {}
    for (const k of FIELDS) {
      const v = event[k]
      if (v === undefined || v === null) continue
      if (k === 'args') {
        const s = summarizeArgs(v, pepper)
        if (s) out.args = s
        continue
      }
      if (NUM_FIELDS.has(k) && typeof v !== 'object') {
        const n = Number(v)
        if (Number.isFinite(n)) out[k] = Math.max(-MAX_NUM, Math.min(MAX_NUM, n))
        continue
      }
      if (typeof v === 'object') continue
      out[k] = str(v)
    }
    if (!out.event) throw new Error('audit.record 缺少 event')
    if (!out.ts) out.ts = Date.now()
    return out
  }

  function record(event) {
    const line = sanitize(event)
    const text = JSON.stringify(line) + '\n'
    const month = monthKey(new Date(line.ts))
    try {
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 })
      fs.appendFileSync(fileFor(month), text, { mode: 0o600 })
      try { fs.chmodSync(fileFor(month), 0o600) } catch {}
      prune(monthKey())
    } catch (e) {
      report(onerror, new Error('审计写入失败：' + (e.message || e)))
    }
    return line
  }

  function monthFiles() {
    let names
    try { names = fs.readdirSync(dir) } catch { return [] }
    return names.filter((n) => FILE_RE.test(n)).sort()
  }

  function readLines() {
    const out = []
    for (const name of monthFiles()) {
      let text
      try { text = fs.readFileSync(path.join(dir, name), 'utf8') } catch (e) {
        report(onerror, new Error('审计读取失败 ' + name + '：' + (e.message || e)))
        continue
      }
      for (const line of text.split('\n')) {
        const s = line.trim()
        if (!s) continue
        try { out.push(JSON.parse(s)) } catch {}
      }
    }
    return out
  }

  /** 最近的在前；limit 默认 100，上限 5000。 */
  function list(limit) {
    const n = Math.max(1, Math.min(5000, Number(limit) || 100))
    const all = readLines()
    return all.slice(-n).reverse()
  }

  /** 导出为 JSONL 文本（时间正序，最多 5 万条），供用户自己保存。 */
  function exportText() {
    return readLines().slice(-50000).map((e) => JSON.stringify(e)).join('\n')
  }

  return { record, list, export: exportText, file: dir }
}

module.exports = { createAudit, KEEP_MONTHS }
