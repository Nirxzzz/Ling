#!/usr/bin/env node
/* Ling MCP stdio 桥（v1.64）
 *
 * 作用：给只支持 stdio 的 MCP 客户端（Claude Desktop 等）一个入口，
 * 把工具/资源/提示词**转发**到本机已经在跑的 Ling MCP HTTP 服务。
 *
 * 协议两端全部用官方 SDK：
 *   stdio 一侧：@modelcontextprotocol/server/stdio 的 serveStdio
 *   HTTP  一侧：@modelcontextprotocol/client 的 Client + StreamableHTTPClientTransport
 * 本文件只做「环境校验 / 上游探测 / 请求转发」，不自造任何 MCP 帧格式。
 *
 * 环境变量：
 *   LING_MCP_TOKEN  必需。与设置页「一次性令牌」相同；只放在 Authorization 头里，
 *                   永不打印、不写盘、不进 stdout。
 *   LING_MCP_URL    可选。默认 http://127.0.0.1:47321/mcp；只接受 loopback 的 http。
 *   LING_MCP_PORT   可选。没给 LING_MCP_URL 时用它拼默认地址。
 *
 * 失败策略（重要）：令牌缺失、地址非法、服务没开、令牌无效 —— 一律在**开始服务前**
 * 往 stderr 写清真实原因并退出 1。绝不「假装连上、返回空工具列表」，那样客户端
 * 会以为 Ling 没有能力而不是配置错了。
 *
 * stdout 只留给 MCP 帧；所有日志都走 stderr。
 */
'use strict'

const path = require('node:path')

const NAME = 'ling-mcp-bridge'
const CLIENT_INFO = { name: 'ling-mcp-bridge', version: require(path.join(__dirname, 'package.json')).version }
const DEFAULT_PORT = 47321
const CALL_TIMEOUT_MS = 120000

function log(msg) {
  try { process.stderr.write('[' + NAME + '] ' + msg + '\n') } catch {}
}

function die(message, code) {
  log(message)
  process.exit(typeof code === 'number' ? code : 1)
}

/** 令牌只允许发往本机：远端地址会把用户令牌送出去，直接拒绝。 */
function resolveUrl() {
  const raw = String(process.env.LING_MCP_URL || '').trim()
  if (!raw) {
    const port = Number(process.env.LING_MCP_PORT || DEFAULT_PORT)
    if (!Number.isInteger(port) || port < 1024 || port > 65535) {
      die('LING_MCP_PORT 不合法：' + process.env.LING_MCP_PORT + '（应为 1024..65535）')
    }
    return new URL('http://127.0.0.1:' + port + '/mcp')
  }
  let url
  try {
    url = new URL(raw)
  } catch {
    die('LING_MCP_URL 不是合法 URL：' + raw)
  }
  if (url.protocol !== 'http:') die('LING_MCP_URL 只支持 http://（本机明文回环）：' + raw)
  const host = url.hostname.toLowerCase()
  const loopback = host === '127.0.0.1' || host === 'localhost' || host === '[::1]' || host === '::1'
  if (!loopback) die('LING_MCP_URL 只能是本机回环地址（127.0.0.1 / localhost / [::1]），拒绝把令牌发往：' + host)
  if (url.pathname !== '/mcp') die('LING_MCP_URL 路径必须是 /mcp（当前 ' + url.pathname + '）')
  if (url.search || url.hash || url.username || url.password) die('LING_MCP_URL 不能带查询串、片段或账户信息')
  return url
}

function resolveToken() {
  const t = String(process.env.LING_MCP_TOKEN || '').trim()
  if (!t) {
    die('缺少环境变量 LING_MCP_TOKEN：请把 Ling 设置页里「只显示一次」的令牌贴进 MCP 客户端配置的 env。')
  }
  if (t.length < 24 || /\s/.test(t)) die('LING_MCP_TOKEN 格式不对（应为一次性令牌原文，不含空格也不含裁剪字符）')
  return t
}

function describeError(err, where) {
  if (!err) return '未知错误'
  const cause = err.cause || {}
  const code = err.code || cause.code || ''
  const status = Number(err.status || cause.status || 0)
  const msg = String(err.message || err)
  const at = where ? String(where) : '本机 MCP 服务'
  if (code === 'ECONNREFUSED') return '无法连接 ' + at + '：连接被拒绝（Ling 没启动，或 MCP 服务开关是关的）'
  if (code === 'ECONNRESET') return '与 ' + at + ' 的连接被重置（Ling 可能已退出）'
  if (code === 'ENOTFOUND' || code === 'EAI_AGAIN') return '地址解析失败（' + at + '）：' + msg
  if (code === 'ETIMEDOUT' || /timeout/i.test(msg)) return '连接 ' + at + ' 超时：Ling 没有响应'
  if (status === 401 || /\b401\b/.test(msg)) {
    return '令牌无效或已撤销（401，' + at + '）：请在 Ling 设置页重新签发令牌，并更新客户端配置里的 LING_MCP_TOKEN'
  }
  if (status === 403 || /\b403\b/.test(msg)) {
    return '请求被拒绝（403，' + at + '）：Host/Origin 校验未通过，请确认 LING_MCP_URL 是本机回环地址'
  }
  if (status === 429 || /\b429\b/.test(msg)) return '请求被限流（429，' + at + '）：请稍后重试'
  return msg + (code ? '（' + code + '）' : '')
}

function createUpstream(url, token, sdk) {
  let client = null
  let connecting = null
  let tools = []
  let resources = []
  let prompts = []

  async function connect() {
    const transport = new sdk.StreamableHTTPClientTransport(url, {
      requestInit: { headers: { authorization: 'Bearer ' + token } },
      redirectPolicy: 'same-origin'
    })
    const c = new sdk.Client(CLIENT_INFO, { capabilities: {} })
    c.onerror = (e) => log('上游错误：' + describeError(e))
    await c.connect(transport)
    return c
  }

  function drop() {
    const c = client
    client = null
    if (c) { try { c.close().catch(() => {}) } catch {} }
  }

  async function ensure() {
    if (client) return client
    if (connecting) return connecting
    connecting = (async () => {
      const c = await connect()
      client = c
      return c
    })().finally(() => { connecting = null })
    return connecting
  }

  /** 只读探测：任何一项列不出来就整体失败（fail closed），绝不把「列不出」当成「没有」。 */
  async function introspect() {
    const c = await ensure()
    const t = await c.listTools()
    const r = await c.listResources()
    const p = await c.listPrompts()
    tools = (t.tools || []).filter((x) => x && typeof x.name === 'string')
    resources = (r.resources || []).filter((x) => x && typeof x.uri === 'string')
    prompts = (p.prompts || []).filter((x) => x && typeof x.name === 'string')
  }

  /** 是不是「连接坏了」——只有这一类才丢连接；工具自己的业务错误不该打断并行请求。 */
  function isTransportError(err) {
    if (!err) return false
    const code = String(err.code || (err.cause && err.cause.code) || '')
    if (/^(ECONN|ENOTFOUND|EAI_AGAIN|ETIMEDOUT|EPIPE|EHOSTUNREACH|ENETUNREACH|UND_ERR)/.test(code)) return true
    if (err.name === 'SdkHttpError' || code === 'CLIENT_HTTP_NOT_IMPLEMENTED') return true
    return /fetch failed|Error POSTing|socket hang up|terminated|other side closed/i.test(String(err.message || ''))
  }

  /** 转发：只调用一次，绝不自动重放。
   *  callTool 可能是写操作（加卡片、建提醒、存讲义），断线重试会让已提交的写入再来一遍。
   *  所以失败时只做两件事：连接坏了就丢掉这条连接、把真实错误原样抛给客户端；下一条请求自然重连。
   */
  async function forward(fn) {
    const c = await ensure()
    try {
      return await fn(c)
    } catch (err) {
      if (isTransportError(err) && client === c) drop()
      throw err
    }
  }

  return {
    introspect,
    get tools() { return tools },
    get resources() { return resources },
    get prompts() { return prompts },
    callTool: (name, args, options) => forward((c) => c.callTool({ name, arguments: args }, options)),
    readResource: (uri, options) => forward((c) => c.readResource({ uri }, options)),
    getPrompt: (name, args, options) => forward((c) => c.getPrompt({ name, arguments: args }, options)),
    close: async () => { drop() }
  }
}

async function main() {
  const url = resolveUrl()
  const token = resolveToken()

  let serverSdk, clientSdk, stdioSdk, zod
  try {
    serverSdk = require('@modelcontextprotocol/server')
    clientSdk = require('@modelcontextprotocol/client')
    stdioSdk = require('@modelcontextprotocol/server/stdio')
    zod = require('zod')
  } catch (err) {
    die('依赖缺失，无法启动 stdio 桥：' + String((err && err.message) || err))
  }

  const upstream = createUpstream(url, token, clientSdk)

  // 先真连一次：连不上就带着真实原因退出，绝不进入「空服务」状态。
  try {
    await upstream.introspect()
  } catch (err) {
    die('无法连接 Ling MCP 服务 ' + url.origin + url.pathname + '：' + describeError(err, url.origin))
  }

  const rawFromSchema = (schema) => {
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return undefined
    const copy = JSON.parse(JSON.stringify(schema))
    if (copy.type !== 'object') return undefined
    return copy
  }

  function buildArgsSchema(args) {
    if (!Array.isArray(args) || !args.length) return undefined
    const shape = {}
    for (const a of args) {
      if (!a || typeof a.name !== 'string' || !/^[a-z][a-z0-9_]{0,63}$/.test(a.name)) continue
      let f = zod.string()
      if (typeof a.description === 'string' && a.description.trim()) f = f.describe(a.description.trim().slice(0, 400))
      shape[a.name] = a.required ? f : f.optional()
    }
    return Object.keys(shape).length ? zod.object(shape) : undefined
  }

  function toolError(err) {
    const code = (err && err.code) || 'INTERNAL'
    const message = describeError(err)
    return { content: [{ type: 'text', text: code + ': ' + message }], structuredContent: { code, message }, isError: true }
  }

  function factory() {
    const server = new serverSdk.McpServer({ name: 'ling', version: CLIENT_INFO.version }, {
      instructions: 'Ling 桌面侧的本地能力：记忆检索、卡片、提醒、手机屏幕读取与讲义保存。'
    })

    for (const t of upstream.tools) {
      const raw = rawFromSchema(t.inputSchema)
      let inputSchema
      if (raw) {
        try { inputSchema = serverSdk.fromJsonSchema(raw) } catch { inputSchema = undefined }
      }
      server.registerTool(t.name, {
        title: t.title,
        description: typeof t.description === 'string' ? t.description : undefined,
        inputSchema,
        annotations: t.annotations
      }, async (args, ctx) => {
        try {
          const r = await upstream.callTool(t.name, args || {}, {
            signal: ctx && ctx.mcpReq ? ctx.mcpReq.signal : undefined,
            maxTotalTimeout: CALL_TIMEOUT_MS
          })
          const out = { content: Array.isArray(r.content) ? r.content : [] }
          if (r.structuredContent !== undefined) out.structuredContent = r.structuredContent
          if (r.isError) out.isError = true
          if (!out.content.length && out.structuredContent === undefined) {
            out.content = [{ type: 'text', text: JSON.stringify({ ok: true }) }]
          }
          return out
        } catch (err) {
          return toolError(err)
        }
      })
    }

    for (const r of upstream.resources) {
      try {
        server.registerResource(r.name || r.uri, r.uri, {
          title: r.name,
          description: typeof r.description === 'string' ? r.description : undefined,
          mimeType: r.mimeType || 'application/json'
        }, async (uri, ctx) => {
          try {
            const out = await upstream.readResource(uri.href, {
              signal: ctx && ctx.mcpReq ? ctx.mcpReq.signal : undefined,
              maxTotalTimeout: CALL_TIMEOUT_MS
            })
            return { contents: Array.isArray(out.contents) ? out.contents : [] }
          } catch (err) {
            throw new serverSdk.ProtocolError(
              err && err.code === 'NOT_FOUND' ? serverSdk.INVALID_PARAMS : serverSdk.INTERNAL_ERROR,
              describeError(err)
            )
          }
        })
      } catch (err) {
        log('资源注册失败 ' + r.uri + '：' + String((err && err.message) || err))
      }
    }

    for (const p of upstream.prompts) {
      try {
        server.registerPrompt(p.name, {
          description: typeof p.description === 'string' ? p.description : undefined,
          argsSchema: buildArgsSchema(p.arguments)
        }, async (args, ctx) => {
          try {
            const out = await upstream.getPrompt(p.name, args || {}, {
              signal: ctx && ctx.mcpReq ? ctx.mcpReq.signal : undefined,
              maxTotalTimeout: CALL_TIMEOUT_MS
            })
            const result = {
              description: typeof out.description === 'string' ? out.description : undefined,
              messages: Array.isArray(out.messages) ? out.messages : []
            }
            if (!result.messages.length) {
              result.messages = [{ role: 'user', content: { type: 'text', text: '（没有可用的提示词内容）' } }]
            }
            return result
          } catch (err) {
            throw new serverSdk.ProtocolError(
              err && err.code === 'NOT_FOUND' ? serverSdk.INVALID_PARAMS : serverSdk.INTERNAL_ERROR,
              describeError(err)
            )
          }
        })
      } catch (err) {
        log('提示词注册失败 ' + p.name + '：' + String((err && err.message) || err))
      }
    }

    return server
  }

  const handle = stdioSdk.serveStdio(factory, {
    legacy: 'serve',
    onerror: (err) => log('协议错误：' + describeError(err))
  })

  log('已连接 ' + url.origin + url.pathname + '：工具 ' + upstream.tools.length +
    ' / 资源 ' + upstream.resources.length + ' / 提示词 ' + upstream.prompts.length)

  let shuttingDown = false
  async function shutdown(code) {
    if (shuttingDown) return
    shuttingDown = true
    try { await handle.close() } catch {}
    try { await upstream.close() } catch {}
    process.exit(typeof code === 'number' ? code : 0)
  }

  process.on('SIGINT', () => { shutdown(0) })
  process.on('SIGTERM', () => { shutdown(0) })
  process.stdin.on('close', () => { shutdown(0) })
  process.stdout.on('error', (err) => {
    // 客户端关掉了管道：正常退出，不刷一堆栈到 stderr
    if (err && (err.code === 'EPIPE' || err.code === 'ERR_STREAM_DESTROYED')) shutdown(0)
    else log('stdout 错误：' + describeError(err))
  })
}

main().catch((err) => {
  die('stdio 桥启动失败：' + describeError(err))
})
