/* Ling MCP 客户端令牌（v1.64）
 *
 * - 只落盘 SHA-256(token)，明文只在 create() 返回一次，之后任何接口都拿不到。
 * - 文件 0600、目录 0700、写盘走 tmp+rename（同 lan-store 的原子写法）。
 * - 文件损坏 = fail closed：抛错并指出路径，绝不「重置成空列表」把损坏静默吞掉。
 * - 撤销立即生效：verify() 只认内存里的活表，revoke() 同时清掉表和盘上的条目。
 *
 * createAuth({dir}) -> { list, create, revoke, verify, file }
 */
'use strict'

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')

const FILE = 'clients.json'
const SCOPES = ['read', 'write', 'act', 'personal']
const TOOL_RE = /^[a-z][a-z0-9_]{0,63}$/
const NAME_MAX = 64
const TOOLS_MAX = 50
const TOKEN_PREFIX = 'ling_'
const BEARER_RE = /^Bearer[ \t]+([A-Za-z0-9._~+/=-]{16,512})$/i

function corrupt(file, why) {
  const e = new Error('MCP 客户端配置损坏（' + why + '）：' + file + '。为避免丢失已签发的客户端，程序不会自动重置该文件，请人工检查或改名后重启。')
  e.code = 'AUTH_CORRUPT'
  e.path = file
  return e
}

function digest(token) {
  return crypto.createHash('sha256').update('ling-mcp-v1\0' + token).digest('hex')
}

function normName(name) {
  const s = typeof name === 'string' ? name.trim() : ''
  if (!s) return 'MCP 客户端'
  return s.length > NAME_MAX ? s.slice(0, NAME_MAX) : s
}

function normScopes(scopes) {
  if (scopes === undefined || scopes === null) return ['read']
  if (!Array.isArray(scopes)) {
    const e = new Error('scopes 必须是数组：' + SCOPES.join('/'))
    e.code = 'INVALID_SCOPES'
    throw e
  }
  for (const s of scopes) {
    if (!SCOPES.includes(s)) {
      const e = new Error('未知的 scope：' + String(s) + '（可用：' + SCOPES.join('/') + '）')
      e.code = 'INVALID_SCOPES'
      throw e
    }
  }
  const set = new Set(scopes)
  const out = SCOPES.filter((s) => set.has(s))
  return out.length ? out : ['read']
}

function normTools(tools) {
  if (tools === undefined || tools === null) return undefined
  if (!Array.isArray(tools)) {
    const e = new Error('tools 必须是工具名数组')
    e.code = 'INVALID_TOOLS'
    throw e
  }
  const out = []
  for (const t of tools) {
    const s = typeof t === 'string' ? t.trim() : ''
    if (!TOOL_RE.test(s)) {
      const e = new Error('工具名不合法：' + String(t))
      e.code = 'INVALID_TOOLS'
      throw e
    }
    if (!out.includes(s)) out.push(s)
    if (out.length >= TOOLS_MAX) break
  }
  return out.length ? out : undefined
}

function view(c) {
  const v = { id: c.id, name: c.name, scopes: c.scopes.slice(), createdAt: c.createdAt }
  if (c.tools) v.tools = c.tools.slice()
  if (c.lastUsedAt) v.lastUsedAt = c.lastUsedAt
  return v
}

function writeAtomic(file, text, mode) {
  const tmp = file + '.' + process.pid + '.' + crypto.randomBytes(4).toString('hex') + '.tmp'
  const fd = fs.openSync(tmp, 'w', mode)
  try {
    fs.writeFileSync(fd, text)
    fs.fsyncSync(fd)
  } finally {
    fs.closeSync(fd)
  }
  fs.renameSync(tmp, file)
  try { fs.chmodSync(file, mode) } catch {}
}

function createAuth(opts) {
  const dir = path.resolve(String((opts && opts.dir) || ''))
  if (!dir || dir === path.resolve('.')) throw new Error('createAuth 需要 dir')
  const file = path.join(dir, FILE)
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 })

  /** hash -> 客户端（内存里是唯一权威，撤销后立刻消失） */
  const byHash = new Map()
  let clients = []

  function load() {
    let text
    try { text = fs.readFileSync(file, 'utf8') } catch (e) {
      if (e && e.code === 'ENOENT') { clients = []; return }
      throw corrupt(file, '读取失败 ' + (e && e.code ? e.code : 'IO'))
    }
    let doc
    try { doc = JSON.parse(text) } catch { throw corrupt(file, '不是合法 JSON') }
    if (!doc || doc.v !== 1 || !Array.isArray(doc.clients)) throw corrupt(file, '版本或结构不符')
    const out = []
    const seen = new Set()
    for (const c of doc.clients) {
      const ok = c && typeof c.id === 'string' && /^cl_[0-9a-f]{6,32}$/.test(c.id) &&
        typeof c.name === 'string' && typeof c.hash === 'string' && /^[0-9a-f]{64}$/.test(c.hash) &&
        Array.isArray(c.scopes) && c.scopes.every((s) => SCOPES.includes(s))
      if (!ok) throw corrupt(file, '客户端条目字段不合法')
      if (seen.has(c.hash) || seen.has(c.id)) throw corrupt(file, '客户端条目重复')
      seen.add(c.hash)
      seen.add(c.id)
      out.push({
        id: c.id,
        name: c.name.slice(0, NAME_MAX),
        scopes: SCOPES.filter((s) => c.scopes.includes(s)),
        tools: Array.isArray(c.tools) && c.tools.length ? c.tools.filter((t) => TOOL_RE.test(t)) : undefined,
        hash: c.hash,
        createdAt: Number(c.createdAt) || Date.now(),
        lastUsedAt: Number(c.lastUsedAt) || 0
      })
    }
    clients = out
  }

  function persist() {
    const doc = { v: 1, clients: clients.map((c) => {
      const o = { id: c.id, name: c.name, scopes: c.scopes, hash: c.hash, createdAt: c.createdAt }
      if (c.tools) o.tools = c.tools
      if (c.lastUsedAt) o.lastUsedAt = c.lastUsedAt
      return o
    }) }
    writeAtomic(file, JSON.stringify(doc, null, 2) + '\n', 0o600)
  }

  function reindex() {
    byHash.clear()
    for (const c of clients) byHash.set(c.hash, c)
  }

  load()
  reindex()

  function list() {
    return clients.slice().sort((a, b) => b.createdAt - a.createdAt).map(view)
  }

  function create(spec) {
    const s = spec || {}
    const id = 'cl_' + crypto.randomBytes(6).toString('hex')
    const token = TOKEN_PREFIX + crypto.randomBytes(32).toString('base64url')
    const c = {
      id,
      name: normName(s.name),
      scopes: normScopes(s.scopes),
      tools: normTools(s.tools),
      hash: digest(token),
      createdAt: Date.now(),
      lastUsedAt: 0
    }
    clients.push(c)
    try {
      persist()
    } catch (e) {
      clients = clients.filter((x) => x !== c)
      throw e
    }
    reindex()
    return { client: view(c), token }
  }

  function revoke(id) {
    const want = typeof id === 'string' ? id.trim() : ''
    if (!want) return false
    const before = clients.length
    const next = clients.filter((c) => c.id !== want)
    if (next.length === before) return false
    clients = next
    try {
      persist()
    } catch (e) {
      // 盘上没写成功就不能让内存先变：恢复原状并如实抛错
      load()
      reindex()
      throw e
    }
    reindex()
    return true
  }

  /** 只接受 `Bearer <token>`；无效/已撤销一律 null，绝不区分「不存在」与「令牌错」。 */
  function verify(header) {
    if (typeof header !== 'string') return null
    const m = BEARER_RE.exec(header.trim())
    if (!m) return null
    const c = byHash.get(digest(m[1]))
    if (!c) return null
    c.lastUsedAt = Date.now()
    return view(c)
  }

  return { list, create, revoke, verify, file }
}

module.exports = { createAuth, SCOPES }
