/* Ling MCP Hub（v1.64）—— 真正的 MCP HTTP 服务端
 *
 * createHub({auth, audit, tools, port, version}) -> { start, stop, status, url }
 *
 * 传输与协议全部交给官方 SDK（@modelcontextprotocol/server 2.4.0 +
 * @modelcontextprotocol/node 2.1.1）：
 *   createMcpHandler（现代 2026 信封 + 2025 无状态 initialize 两个年代）
 *   → toNodeHandler（web Request ↔ node:http）
 *   → 官方 hostHeaderValidation / originValidation（DNS 重绑定与跨站防护）
 * 本文件只做「Ling 自己的策略」：令牌认证、scope 过滤与二次校验、限流、
 * 输出上限、审计。
 *
 * 绑定与暴露：
 *   - 只监听 127.0.0.1（loopback），端口被占用直接报错，绝不自动换端口。
 *   - 路径固定 /mcp；Host/Origin 由官方校验器限制为 localhost 一族。
 *   - 每个请求都要 Bearer 令牌，令牌撤销即时生效（auth.verify 每请求查活表）。
 *   - 从不发 CORS 头，所以浏览器跨站读不到响应。
 */
'use strict'

const http = require('node:http')

let sdk = null
function loadSdk() {
  if (sdk) return sdk
  const S = '@modelcontextprotocol/server'
  const N = '@modelcontextprotocol/node'
  const server = require(S)
  const node = require(N)
  sdk = {
    McpServer: server.McpServer,
    fromJsonSchema: server.fromJsonSchema,
    createMcpHandler: server.createMcpHandler,
    ProtocolError: server.ProtocolError,
    INVALID_PARAMS: server.INVALID_PARAMS,
    INVALID_REQUEST: server.INVALID_REQUEST,
    toNodeHandler: node.toNodeHandler,
    hostHeaderValidation: node.hostHeaderValidation,
    originValidation: node.originValidation
  }
  return sdk
}

const PATH = '/mcp'
const MAX_BODY = 1024 * 1024 // 1 MiB 请求上限
const MAX_OUT = 64 * 1024 // 64 KiB 输出上限
const WINDOW_MS = 60000
const PER_MIN = 60
const PER_MIN_L3 = 6
const TOOL_RE = /^[a-z][a-z0-9_]{0,63}$/

/** 工具 → 必需 scope。'personal' 只放宽结果，从不是「必需 scope」。 */
const TOOL_SCOPE = {
  ling_memory_search: 'read',
  ling_cards_search: 'read',
  ling_weak_points: 'read',
  ling_memory_add: 'write',
  ling_cards_add: 'write',
  ling_reminder_create: 'write',
  ling_handout_save: 'write',
  ling_phone_screen_read: 'act',
  ling_phone_open_app: 'act',
  ling_desktop_open_file: 'act'
}
const LEVEL_SCOPE = { L1: 'read', L2: 'write', L3: 'act' }

const ANNOTATION_KEYS = ['title', 'readOnlyHint', 'destructiveHint', 'idempotentHint', 'openWorldHint']

function requiredScope(name, level) {
  if (TOOL_SCOPE[name]) return TOOL_SCOPE[name]
  if (LEVEL_SCOPE[level]) return LEVEL_SCOPE[level]
  return 'write'
}

function byteLen(text) {
  return Buffer.byteLength(text, 'utf8')
}

function jsonText(value) {
  try {
    const s = JSON.stringify(value)
    return typeof s === 'string' ? s : String(value)
  } catch {
    return '{"error":"结果无法序列化"}'
  }
}

function cleanAnnotations(a) {
  if (!a || typeof a !== 'object') return undefined
  const out = {}
  for (const k of ANNOTATION_KEYS) {
    const v = a[k]
    if (k === 'title') {
      if (typeof v === 'string' && v.trim()) out.title = v.trim().slice(0, 120)
    } else if (typeof v === 'boolean') out[k] = v
  }
  return Object.keys(out).length ? out : undefined
}

/** 滑动窗口限流：每个 client 一个时间戳数组。 */
function makeLimiter() {
  const buckets = new Map()
  function hit(key, limit) {
    const now = Date.now()
    const arr = (buckets.get(key) || []).filter((t) => now - t < WINDOW_MS)
    if (arr.length >= limit) {
      buckets.set(key, arr)
      return Math.max(1, Math.ceil((WINDOW_MS - (now - arr[0])) / 1000))
    }
    arr.push(now)
    buckets.set(key, arr)
    return 0
  }
  function drop(key) { buckets.delete(key) }
  function clear() { buckets.clear() }
  return { hit, drop, clear }
}

function createHub(opts) {
  const o = opts || {}
  const auth = o.auth
  const audit = o.audit
  const tools = o.tools
  const port = o.port === undefined || o.port === null ? 47321 : Number(o.port)
  const version = String(o.version || '0.0.0')
  if (!auth || typeof auth.verify !== 'function') throw new Error('createHub 需要 auth')
  if (!tools || typeof tools.definitions !== 'function' || typeof tools.call !== 'function') {
    throw new Error('createHub 需要 tools')
  }
  // 0 = 让内核分配空闲端口，只给自动测试用；设置页永远传 1024..65535 的固定端口。
  if (!Number.isInteger(port) || port < 0 || port > 65535 || (port > 0 && port < 1024)) {
    throw new Error('MCP 端口不合法（1024..65535）：' + o.port)
  }

  const general = makeLimiter()
  const sensitive = makeLimiter()
  let server = null
  let handler = null
  let nodeHandler = null
  let guardHost = null
  let guardOrigin = null
  let running = false
  let lastError = null
  let boundPort = port
  let closing = null

  function auditCall(ev) {
    if (!audit || typeof audit.record !== 'function') return
    try { audit.record(ev) } catch {}
  }

  /** 客户端身份：令牌只用于认证，绝不进入 handler/审计。 */
  function clientFromRequest(req) {
    const c = auth.verify(req.headers.authorization)
    if (!c) return null
    return c
  }

  function buildServer(ctx) {
    const s = loadSdk()
    const info = (ctx && ctx.authInfo) || null
    const client = info && info.extra && info.extra.client ? info.extra.client : null
    if (!client) throw new Error('缺少已认证的 MCP 客户端')
    const scopes = Array.isArray(client.scopes) ? client.scopes : []

    const mcp = new s.McpServer({ name: 'ling', version }, { instructions: 'Ling 桌面侧的本地 MCP 服务：记忆检索、卡片、提醒、手机屏幕读取与讲义保存。' })

    // ---- tools ----
    // 枚举/注册失败一律向上抛（→ 客户端看到 INTERNAL 与真实原因），
    // 绝不 catch 成空列表：那会让客户端以为「Ling 没有这个能力」而不是「出错了」。
    let defs
    try {
      defs = tools.definitions(client) || []
    } catch (e) {
      auditCall({ event: 'definitions_error', client: client.id, clientName: client.name, error: String(e && e.message || e) })
      throw e
    }

    for (const d of defs) {
      if (!d || typeof d.name !== 'string' || !TOOL_RE.test(d.name)) {
        auditCall({ event: 'tool_name_error', client: client.id, clientName: client.name, error: '工具名不合法' })
        throw new Error('工具定义不合法：name 必须是 [a-z0-9_] 组成')
      }
      const need = requiredScope(d.name, d.level)
      if (!scopes.includes(need)) continue
      const raw = d.inputSchema && typeof d.inputSchema === 'object' ? d.inputSchema : { type: 'object', properties: {}, additionalProperties: false }
      const level = d.level || 'L2'
      let inputSchema
      try {
        inputSchema = s.fromJsonSchema(raw)
      } catch (e) {
        auditCall({ event: 'schema_error', client: client.id, clientName: client.name, tool: d.name, error: String(e && e.message || e) })
        throw new Error('工具 ' + d.name + ' 的 inputSchema 无法注册：' + String((e && e.message) || e))
      }
      mcp.registerTool(d.name, {
        title: d.title,
        description: typeof d.description === 'string' ? d.description.slice(0, 4000) : undefined,
        inputSchema,
        annotations: cleanAnnotations(d.annotations)
      }, async (args, hctx) => {
        const started = Date.now()
        const signal = hctx && hctx.mcpReq ? hctx.mcpReq.signal : undefined
        const fail = (code, message) => {
          auditCall({
            event: 'call', client: client.id, clientName: client.name, tool: d.name, level,
            args, outcome: 'error', ok: 0, code, ms: Date.now() - started
          })
          return { content: [{ type: 'text', text: String(message) }], structuredContent: { code, message: String(message) }, isError: true }
        }
        // 二次校验：definitions 里可能因配置变化放宽，调用时以当前 scope 表为准
        if (!scopes.includes(need)) return fail('DENIED', '当前 MCP 客户端没有 ' + need + ' 权限（' + d.name + '）')
        if (signal && signal.aborted) return fail('CANCELLED', '请求已取消')
        if (level === 'L3') {
          const wait = sensitive.hit(client.id, PER_MIN_L3)
          if (wait) return fail('RATE_LIMITED', '高风险操作限流：每分钟最多 ' + PER_MIN_L3 + ' 次，请 ' + wait + ' 秒后重试')
        }
        let value
        try {
          value = await tools.call(d.name, args || {}, client, { signal })
        } catch (err) {
          const code = (err && err.code) || 'INTERNAL'
          const message = (err && err.message) || '工具执行失败'
          return fail(code, message)
        }
        const text = jsonText(value === undefined ? {} : value)
        if (byteLen(text) > MAX_OUT) {
          return fail('PAYLOAD_TOO_LARGE', '结果超过 64KiB 上限，请缩小范围后重试')
        }
        auditCall({
          event: 'call', client: client.id, clientName: client.name, tool: d.name, level,
          args, outcome: 'ok', ok: 1, ms: Date.now() - started
        })
        return { content: [{ type: 'text', text }], structuredContent: (value && typeof value === 'object' && !Array.isArray(value)) ? value : { value: value === undefined ? null : value } }
      })
    }

    // ---- resources ----
    let resList
    try {
      resList = tools.resources(client) || []
    } catch (e) {
      auditCall({ event: 'resources_error', client: client.id, clientName: client.name, error: String(e && e.message || e) })
      throw e
    }
    for (const r of resList) {
      if (!r || typeof r.uri !== 'string') throw new Error('资源定义不合法：uri 必须是字符串')
      if (!scopes.includes('read')) continue
      try {
        mcp.registerResource(r.name || r.uri, r.uri, {
          title: r.name,
          description: typeof r.description === 'string' ? r.description.slice(0, 1000) : undefined,
          mimeType: r.mimeType || 'application/json'
        }, async (uri) => {
          const started = Date.now()
          let value
          try {
            value = await tools.readResource(uri.href, client)
          } catch (err) {
            const code = (err && err.code) || 'INTERNAL'
            auditCall({ event: 'resource', client: client.id, clientName: client.name, target: uri.href, outcome: 'error', ok: 0, code, ms: Date.now() - started })
            throw new s.ProtocolError(code === 'NOT_FOUND' ? s.INVALID_PARAMS : s.INTERNAL_ERROR, String((err && err.message) || '资源读取失败'))
          }
          const text = jsonText(value === undefined ? {} : value)
          if (byteLen(text) > MAX_OUT) {
            auditCall({ event: 'resource', client: client.id, clientName: client.name, target: uri.href, outcome: 'error', ok: 0, code: 'PAYLOAD_TOO_LARGE', ms: Date.now() - started })
            throw new s.ProtocolError(s.INTERNAL_ERROR, '资源内容超过 64KiB 上限')
          }
          auditCall({ event: 'resource', client: client.id, clientName: client.name, target: uri.href, outcome: 'ok', ok: 1, ms: Date.now() - started })
          return { contents: [{ uri: uri.href, mimeType: r.mimeType || 'application/json', text }] }
        })
      } catch (e) {
        auditCall({ event: 'resource_register_error', client: client.id, clientName: client.name, target: r.uri, error: String(e && e.message || e) })
        throw new Error('资源 ' + r.uri + ' 注册失败：' + String((e && e.message) || e))
      }
    }

    // ---- prompts ----
    let promptList
    try {
      promptList = tools.prompts(client) || []
    } catch (e) {
      auditCall({ event: 'prompts_error', client: client.id, clientName: client.name, error: String(e && e.message || e) })
      throw e
    }
    for (const p of promptList) {
      if (!p || typeof p.name !== 'string') throw new Error('提示词定义不合法：name 必须是字符串')
      if (!scopes.includes('read')) continue
      const argsSchema = Array.isArray(p.arguments) && p.arguments.length
        ? (() => {
            const shape = {}
            const z = require('zod')
            for (const a of p.arguments) {
              if (!a || typeof a.name !== 'string' || !TOOL_RE.test(a.name)) continue
              let f = z.string()
              if (typeof a.description === 'string') f = f.describe(a.description.slice(0, 400))
              shape[a.name] = a.required ? f : f.optional()
            }
            return Object.keys(shape).length ? z.object(shape) : undefined
          })()
        : undefined
      try {
        mcp.registerPrompt(p.name, {
          title: p.title,
          description: typeof p.description === 'string' ? p.description.slice(0, 1000) : undefined,
          argsSchema
        }, async (args) => {
          const started = Date.now()
          let value
          try {
            value = await tools.getPrompt(p.name, args || {}, client)
          } catch (err) {
            const code = (err && err.code) || 'INTERNAL'
            auditCall({ event: 'prompt', client: client.id, clientName: client.name, target: p.name, outcome: 'error', ok: 0, code, ms: Date.now() - started })
            throw new s.ProtocolError(code === 'NOT_FOUND' ? s.INVALID_PARAMS : s.INTERNAL_ERROR, String((err && err.message) || '提示词不可用'))
          }
          const result = normalizePrompt(value, p)
          const text = jsonText(result)
          if (byteLen(text) > MAX_OUT) throw new s.ProtocolError(s.INTERNAL_ERROR, '提示词内容超过 64KiB 上限')
          auditCall({ event: 'prompt', client: client.id, clientName: client.name, target: p.name, outcome: 'ok', ok: 1, ms: Date.now() - started })
          return result
        })
      } catch (e) {
        auditCall({ event: 'prompt_register_error', client: client.id, clientName: client.name, target: p.name, error: String(e && e.message || e) })
        throw new Error('提示词 ' + p.name + ' 注册失败：' + String((e && e.message) || e))
      }
    }

    return mcp
  }

  function normalizePrompt(value, meta) {
    const v = value && typeof value === 'object' ? value : {}
    const messages = Array.isArray(v.messages) ? v.messages : []
    const out = messages
      .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && m.content && m.content.type === 'text' && typeof m.content.text === 'string')
      .map((m) => ({ role: m.role, content: { type: 'text', text: m.content.text } }))
    return {
      description: typeof v.description === 'string' ? v.description : (meta && meta.description) || undefined,
      messages: out.length ? out : [{ role: 'user', content: { type: 'text', text: '（没有可用的提示词内容）' } }]
    }
  }

  function sendJson(res, status, obj, extraHeaders) {
    const body = JSON.stringify(obj)
    res.writeHead(status, Object.assign({
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(Buffer.byteLength(body)),
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff'
    }, extraHeaders || {}))
    res.end(body)
  }

  function rpcError(res, status, code, message, extraHeaders) {
    sendJson(res, status, { jsonrpc: '2.0', id: null, error: { code, message } }, extraHeaders)
  }

  function build() {
    const s = loadSdk()
    handler = s.createMcpHandler(buildServer, {
      legacy: 'stateless',
      maxRequestBodySize: MAX_BODY,
      onerror: (err) => {
        lastError = String((err && err.message) || err)
        try { process.stderr.write('[mcp-hub] ' + lastError + '\n') } catch {}
      }
    })
    nodeHandler = s.toNodeHandler(handler, {
      maxRequestBodySize: MAX_BODY,
      onerror: (err) => {
        lastError = String((err && err.message) || err)
        try { process.stderr.write('[mcp-hub] ' + lastError + '\n') } catch {}
      }
    })
    // 官方 DNS 重绑定 / 跨站防护（不手写 Host/Origin 解析）
    guardHost = s.hostHeaderValidation(['127.0.0.1', 'localhost', '[::1]'])
    guardOrigin = s.originValidation(['127.0.0.1', 'localhost', '[::1]'])
  }

  function onRequest(req, res) {
    let pathname = '/'
    try { pathname = new URL(req.url || '/', 'http://127.0.0.1').pathname } catch {
      return rpcError(res, 400, -32600, '请求路径不合法')
    }
    if (pathname !== PATH) return rpcError(res, 404, -32601, '未知路径（仅支持 ' + PATH + '）')
    // 1) Host（DNS 重绑定）与 Origin（跨站）：官方校验器直接回 403
    if (!guardHost(req, res)) return
    if (!guardOrigin(req, res)) return
    // 2) 认证：每个请求都查活表，撤销即时生效
    const client = clientFromRequest(req)
    if (!client) {
      return rpcError(res, 401, -32000, '未授权：缺少或无效的 Bearer 令牌', {
        'www-authenticate': 'Bearer realm="ling-mcp", error="invalid_token"'
      })
    }
    // 3) 每客户端 60 次/分钟
    const wait = general.hit(client.id, PER_MIN)
    if (wait) {
      auditCall({ event: 'rate_limited', client: client.id, clientName: client.name, wait, reason: 'requests' })
      return rpcError(res, 429, -32000, '请求过于频繁：每分钟最多 ' + PER_MIN + ' 次，请 ' + wait + ' 秒后重试', { 'retry-after': String(wait) })
    }
    req.auth = { token: '(redacted)', clientId: client.id, scopes: client.scopes.slice(), extra: { client } }
    nodeHandler(req, res).catch((err) => {
      lastError = String((err && err.message) || err)
      try { process.stderr.write('[mcp-hub] ' + lastError + '\n') } catch {}
      if (!res.headersSent) rpcError(res, 500, -32603, '内部错误')
      else try { res.end() } catch {}
    })
  }

  function start() {
    if (running) return Promise.resolve({ port: boundPort })
    if (closing) return closing.then(start)
    build()
    lastError = null
    general.clear()
    sensitive.clear()
    server = http.createServer(onRequest)
    server.keepAliveTimeout = 30000
    server.headersTimeout = 35000
    server.requestTimeout = 120000
    server.maxHeadersCount = 64
    return new Promise((resolve, reject) => {
      const onError = (err) => {
        server.removeListener('listening', onListening)
        running = false
        lastError = err && err.code === 'EADDRINUSE'
          ? '端口 ' + port + ' 已被占用（127.0.0.1）。请关闭占用该端口的程序或换一个端口。'
          : 'MCP 服务启动失败：' + ((err && err.message) || err)
        try { server.close() } catch {}
        server = null
        reject(new Error(lastError))
      }
      const onListening = () => {
        server.removeListener('error', onError)
        server.on('error', (err) => {
          lastError = String((err && err.message) || err)
          try { process.stderr.write('[mcp-hub] ' + lastError + '\n') } catch {}
        })
        running = true
        boundPort = server.address().port
        auditCall({ event: 'hub_start', port: boundPort, version })
        resolve({ port: boundPort })
      }
      server.once('error', onError)
      server.once('listening', onListening)
      try {
        server.listen(port, '127.0.0.1')
      } catch (err) {
        onError(err)
      }
    })
  }

  function stop() {
    if (closing) return closing
    running = false
    general.clear()
    sensitive.clear()
    const srv = server
    const h = handler
    server = null
    handler = null
    nodeHandler = null
    closing = (async () => {
      if (srv) {
        await new Promise((resolve) => {
          let done = false
          const finish = () => { if (!done) { done = true; resolve() } }
          try { srv.close(finish) } catch { finish() }
          setTimeout(finish, 3000).unref?.()
        })
      }
      if (h) { try { await h.close() } catch {} }
      auditCall({ event: 'hub_stop', port: boundPort })
    })().finally(() => { closing = null })
    return closing
  }

  function status() {
    return { running, port: boundPort, error: lastError, url: running ? 'http://127.0.0.1:' + boundPort + PATH : null }
  }

  return {
    start,
    stop,
    status,
    url: () => 'http://127.0.0.1:' + boundPort + PATH,
    /** 撤销后清掉该 client 的限流桶（避免撤销后计数残留）。 */
    forget: (clientId) => { general.drop(clientId); sensitive.drop(clientId) }
  }
}

module.exports = { createHub, TOOL_SCOPE, MAX_OUT, MAX_BODY, PATH }
