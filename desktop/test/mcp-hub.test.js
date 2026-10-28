// mcp-hub / mcp-auth / mcp-audit：永久行为回归（真实 HTTP + 真实文件，无 Electron）
// 只断言用户可见的安全与消费行为：令牌一次性、撤销即时、坏文件不吞、Host/Origin、scope 二次校验、
// 限流、64KiB 输出上限、审计脱敏。不做源码字符串断言，也不替换真实模块。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const http = require('node:http')

const { createAuth } = require('../mcp-auth')
const { createAudit } = require('../mcp-audit')
const { createHub } = require('../mcp-hub')

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-mcp-hub-'))
}

function cleanup(dir) {
  if (dir) fs.rmSync(dir, { recursive: true, force: true })
}

/** 与约定一致的假 tools：只用于驱动 Hub 的策略，不替代任何真实业务。 */
function fakeTools(overrides) {
  const raw = () => ({ type: 'object', properties: { q: { type: 'string' } }, required: ['q'], additionalProperties: false })
  const base = {
    definitions(client) {
      const all = [
        { name: 'ling_memory_search', description: '搜', inputSchema: raw(), annotations: { readOnlyHint: true }, level: 'L1' },
        { name: 'ling_memory_add', description: '写', inputSchema: raw(), annotations: { readOnlyHint: false }, level: 'L2' },
        { name: 'ling_phone_screen_read', description: '截屏', inputSchema: raw(), annotations: { readOnlyHint: false }, level: 'L3' },
        { name: 'ling_huge', description: '大结果', inputSchema: raw(), annotations: { readOnlyHint: true }, level: 'L1' }
      ]
      const need = { ling_memory_search: 'read', ling_memory_add: 'write', ling_phone_screen_read: 'act', ling_huge: 'read' }
      return all.filter((t) => client.scopes.includes(need[t.name]))
    },
    async call(name, args, client) {
      if (name === 'ling_huge') return { blob: 'x'.repeat(70000) }
      return { ok: true, tool: name, q: args.q, who: client.id, untrusted: true, source: 'memory', value: [] }
    },
    resources() {
      return [{ uri: 'ling://status', name: 'status', description: '状态', mimeType: 'application/json' }]
    },
    async readResource(uri) {
      if (uri === 'ling://status') return { hub: 'running' }
      const e = new Error('没有这个资源')
      e.code = 'NOT_FOUND'
      throw e
    },
    prompts() {
      return [{ name: 'ling_review_handout', description: '复习', arguments: [{ name: 'style', description: '风格', required: false }] }]
    },
    getPrompt(name, args) {
      return { description: '复习', messages: [{ role: 'user', content: { type: 'text', text: '复习 ' + String(args.style || '') } }] }
    },
    async rollback() { return { ok: true } }
  }
  return Object.assign(base, overrides || {})
}

async function withHub(opts, fn) {
  const dir = tmpDir()
  const auth = createAuth({ dir })
  const audit = createAudit({ dir })
  const tools = (opts && opts.tools) || fakeTools()
  const hub = createHub({ auth, audit, tools, port: 0, version: '1.64.0' })
  await hub.start()
  const port = hub.status().port
  try {
    return await fn({ dir, auth, audit, hub, port, tools })
  } finally {
    await hub.stop()
    cleanup(dir)
  }
}

/** 直接发 JSON-RPC；返回 {status, headers, body}（兼容 SSE 响应）。 */
function rpc(port, token, payload, headers) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(payload)
    const h = Object.assign({
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      'content-length': String(Buffer.byteLength(data)),
      host: '127.0.0.1:' + port
    }, token ? { authorization: 'Bearer ' + token } : {}, headers || {})
    const req = http.request({ host: '127.0.0.1', port, path: '/mcp', method: 'POST', headers: h }, (res) => {
      let text = ''
      res.on('data', (c) => { text += c })
      res.on('end', () => {
        let body = null
        const ct = String(res.headers['content-type'] || '')
        if (ct.includes('text/event-stream')) {
          const line = text.split('\n').find((l) => l.startsWith('data: '))
          body = line ? JSON.parse(line.slice(6)) : null
        } else {
          try { body = JSON.parse(text) } catch { body = text }
        }
        resolve({ status: res.statusCode, headers: res.headers, body })
      })
    })
    req.on('error', reject)
    req.end(data)
  })
}

let seq = 0
function call(port, token, method, params, headers) {
  return rpc(port, token, { jsonrpc: '2.0', id: ++seq, method, params }, headers)
}

test('令牌：只落盘哈希、明文只在 create 返回一次，list 不泄露', async () => {
  const dir = tmpDir()
  try {
    const auth = createAuth({ dir })
    const made = auth.create({ name: '读客户端' })
    assert.match(made.token, /^ling_[A-Za-z0-9_-]{40,}$/)
    assert.deepStrictEqual(made.client.scopes, ['read'], '默认只有 read')
    assert.strictEqual(made.client.token, undefined)
    assert.strictEqual(made.client.hash, undefined)

    const file = path.join(dir, 'clients.json')
    const mode = fs.statSync(file).mode & 0o777
    assert.strictEqual(mode, 0o600, '令牌文件必须是 0600')
    const disk = fs.readFileSync(file, 'utf8')
    assert.ok(!disk.includes(made.token), '盘上不能有令牌明文')
    assert.ok(JSON.stringify(auth.list()).indexOf(made.token) === -1)
    assert.ok(JSON.stringify(auth.list()).indexOf('"hash"') === -1, 'list 不能返回哈希')

    // 重启后仍能验证同一令牌（哈希可复现）
    const again = createAuth({ dir })
    assert.strictEqual(again.verify('Bearer ' + made.token).id, made.client.id)
    assert.strictEqual(again.verify('Bearer ling_' + 'A'.repeat(43)), null)
    assert.strictEqual(again.verify(made.token), null, '必须带 Bearer 前缀')
  } finally {
    cleanup(dir)
  }
})

test('scope 参数：未知名/非数组一律拒绝，不会静默放宽', () => {
  const dir = tmpDir()
  try {
    const auth = createAuth({ dir })
    assert.throws(() => auth.create({ name: 'x', scopes: ['admin'] }), /scope/)
    assert.throws(() => auth.create({ name: 'x', scopes: 'read' }), /scopes/)
    assert.deepStrictEqual(auth.create({ name: 'x', scopes: ['personal', 'read'] }).client.scopes, ['read', 'personal'])
  } finally {
    cleanup(dir)
  }
})

test('损坏的客户端文件 fail closed：报错且不重置', async () => {
  const dir = tmpDir()
  try {
    const auth = createAuth({ dir })
    auth.create({ name: 'x' })
    const file = path.join(dir, 'clients.json')
    const broken = '{"v":1,"clients":[{"id":"cl_zzz"}]}'
    fs.writeFileSync(file, broken)
    assert.throws(() => createAuth({ dir }), (e) => e.code === 'AUTH_CORRUPT' && /损坏/.test(e.message))
    assert.strictEqual(fs.readFileSync(file, 'utf8'), broken, '损坏文件必须原样保留，绝不自动重置')
  } finally {
    cleanup(dir)
  }
})

test('撤销即时生效：同一令牌下一个请求就被拒', async () => {
  await withHub({}, async ({ dir, auth, port }) => {
    const made = auth.create({ name: 'rw', scopes: ['read', 'write'] })
    const before = await call(port, made.token, 'tools/list', {})
    assert.strictEqual(before.status, 200)
    assert.strictEqual(auth.revoke(made.client.id), true)
    const after = await call(port, made.token, 'tools/list', {})
    assert.strictEqual(after.status, 401)
    assert.strictEqual(auth.list().length, 0)
    // 撤销要落盘：新建实例也认不出来
    assert.strictEqual(createAuth({ dir }).verify('Bearer ' + made.token), null)
  })
})

test('没有令牌一律 401；Host/Origin 反重绑定', async () => {
  await withHub({}, async ({ auth, port }) => {
    const made = auth.create({ name: 'ro', scopes: ['read'] })
    const noToken = await call(port, null, 'tools/list', {})
    assert.strictEqual(noToken.status, 401)
    assert.match(String(noToken.headers['www-authenticate'] || ''), /Bearer/)

    const evilHost = await call(port, made.token, 'tools/list', {}, { host: 'evil.example.com' })
    assert.strictEqual(evilHost.status, 403)

    const evilOrigin = await call(port, made.token, 'tools/list', {}, { origin: 'https://evil.example.com' })
    assert.strictEqual(evilOrigin.status, 403)

    const nullOrigin = await call(port, made.token, 'tools/list', {}, { origin: 'null' })
    assert.strictEqual(nullOrigin.status, 403)

    const goodOrigin = await call(port, made.token, 'tools/list', {}, { origin: 'http://127.0.0.1:' + port })
    assert.strictEqual(goodOrigin.status, 200)
    assert.strictEqual(goodOrigin.headers['access-control-allow-origin'], undefined, '不发 CORS 头')
  })
})

test('未知路径 404，超 1MiB 请求 413，端口占用直接报错', async () => {
  await withHub({}, async ({ auth, port, hub }) => {
    const made = auth.create({ name: 'ro', scopes: ['read'] })
    const wrongPath = await new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port, path: '/other', method: 'POST', headers: { 'content-type': 'application/json', 'content-length': 2, authorization: 'Bearer ' + made.token } }, (res) => {
        let out = ''
        res.on('data', (c) => { out += c })
        res.on('end', () => resolve({ status: res.statusCode, body: out }))
      })
      req.on('error', reject)
      req.end('{}')
    })
    assert.strictEqual(wrongPath.status, 404)

    const big = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: { pad: 'x'.repeat(1100000) } })
    const tooBig = await new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port, path: '/mcp', method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(big), authorization: 'Bearer ' + made.token } }, (res) => {
        let out = ''
        res.on('data', (c) => { out += c })
        res.on('end', () => resolve({ status: res.statusCode }))
      })
      req.on('error', reject)
      req.end(big)
    })
    assert.strictEqual(tooBig.status, 413)

    // 同一端口再开一个：必须失败并说清原因，绝不自动换端口
    const clash = createHub({ auth, audit: null, tools: fakeTools(), port, version: '1.64.0' })
    await assert.rejects(() => clash.start(), /端口 .* 已被占用/)
    assert.strictEqual(clash.status().running, false)
    assert.ok(hub.status().running)
  })
})

test('工具按 scope 过滤，调用时再校验一次', async () => {
  await withHub({}, async ({ auth, port }) => {
    const ro = auth.create({ name: 'ro', scopes: ['read'] })
    const rw = auth.create({ name: 'rw', scopes: ['read', 'write'] })
    const act = auth.create({ name: 'act', scopes: ['read', 'act'] })

    const readList = await call(port, ro.token, 'tools/list', {})
    assert.deepStrictEqual(readList.body.result.tools.map((t) => t.name), ['ling_memory_search', 'ling_huge'])
    const writeList = await call(port, rw.token, 'tools/list', {})
    assert.deepStrictEqual(writeList.body.result.tools.map((t) => t.name), ['ling_memory_search', 'ling_memory_add', 'ling_huge'])
    const actList = await call(port, act.token, 'tools/list', {})
    assert.deepStrictEqual(actList.body.result.tools.map((t) => t.name), ['ling_memory_search', 'ling_phone_screen_read', 'ling_huge'])

    // 明文 schema 原样暴露，且标注 readOnlyHint 不变
    const search = readList.body.result.tools.find((t) => t.name === 'ling_memory_search')
    assert.deepStrictEqual(search.inputSchema, {
      type: 'object', properties: { q: { type: 'string' } }, required: ['q'], additionalProperties: false
    })
    assert.strictEqual(search.annotations.readOnlyHint, true)

    // 越权直呼：未注册的工具直接拒绝；参数必须严格匹配
    const forbidden = await call(port, ro.token, 'tools/call', { name: 'ling_memory_add', arguments: { q: 'x' } })
    assert.ok(forbidden.body.error, '只读客户端不能调用写工具')
    const extraArg = await call(port, ro.token, 'tools/call', { name: 'ling_memory_search', arguments: { q: 'x', zzz: 1 } })
    assert.strictEqual(extraArg.body.result.isError, true)
    assert.match(extraArg.body.result.content[0].text, /additional properties/)

    const ok = await call(port, rw.token, 'tools/call', { name: 'ling_memory_add', arguments: { q: 'hi' } })
    assert.strictEqual(ok.body.result.isError, undefined)
    assert.strictEqual(ok.body.result.structuredContent.q, 'hi')
    assert.match(ok.body.result.content[0].text, /"untrusted":true/)
  })
})

test('输出超 64KiB 截断为错误，不把大对象塞进上下文', async () => {
  await withHub({}, async ({ auth, port }) => {
    const ro = auth.create({ name: 'ro', scopes: ['read'] })
    const big = await call(port, ro.token, 'tools/call', { name: 'ling_huge', arguments: { q: 'x' } })
    assert.strictEqual(big.body.result.isError, true)
    assert.strictEqual(big.body.result.structuredContent.code, 'PAYLOAD_TOO_LARGE')
    assert.ok(Buffer.byteLength(JSON.stringify(big.body.result)) < 64 * 1024)
  })
})

test('L3 每分钟最多 6 次；普通请求每分钟最多 60 次', async () => {
  await withHub({}, async ({ auth, port }) => {
    const act = auth.create({ name: 'act', scopes: ['read', 'act'] })
    let blocked = 0
    for (let i = 0; i < 8; i++) {
      const r = await call(port, act.token, 'tools/call', { name: 'ling_phone_screen_read', arguments: { q: 'x' } })
      if (r.body.result && r.body.result.isError) blocked++
    }
    assert.strictEqual(blocked, 2, '第 7、8 次应被 L3 限流拦下')

    const ro = auth.create({ name: 'ro', scopes: ['read'] })
    let seen429 = 0
    for (let i = 0; i < 62; i++) {
      const r = await call(port, ro.token, 'tools/list', {})
      if (r.status === 429) seen429++
    }
    assert.strictEqual(seen429, 2, '第 61、62 次应被普通限流拦下')
  })
})

test('资源与提示词走真实协议；未知资源是协议错误', async () => {
  await withHub({}, async ({ auth, port }) => {
    const ro = auth.create({ name: 'ro', scopes: ['read'] })
    const list = await call(port, ro.token, 'resources/list', {})
    assert.deepStrictEqual(list.body.result.resources.map((r) => r.uri), ['ling://status'])
    const read = await call(port, ro.token, 'resources/read', { uri: 'ling://status' })
    assert.strictEqual(JSON.parse(read.body.result.contents[0].text).hub, 'running')
    const miss = await call(port, ro.token, 'resources/read', { uri: 'ling://nope' })
    assert.ok(miss.body.error, '未知资源必须是协议错误而不是空内容')

    const prompts = await call(port, ro.token, 'prompts/list', {})
    assert.deepStrictEqual(prompts.body.result.prompts.map((p) => p.name), ['ling_review_handout'])
    const got = await call(port, ro.token, 'prompts/get', { name: 'ling_review_handout', arguments: { style: '短' } })
    assert.strictEqual(got.body.result.messages[0].role, 'user')
    assert.match(got.body.result.messages[0].content.text, /短/)
  })
})

test('audit：只记形状与哈希，不落参数正文/令牌；导出是 JSONL', async () => {
  const dir = tmpDir()
  try {
    const audit = createAudit({ dir })
    audit.record({ event: 'call', client: 'cl_1', clientName: 'n', tool: 'ling_memory_add', args: { q: '机密正文内容' }, outcome: 'ok', ok: 1, ms: 3, token: 'ling_secret' })
    audit.record({ event: 'confirm', client: 'cl_1', tool: 'ling_phone_screen_read', confirm: 'denied', ok: 0 })
    const rows = audit.list(10)
    assert.strictEqual(rows.length, 2)
    const call = rows.find((r) => r.event === 'call')
    assert.deepStrictEqual(call.args.keys, ['q'])
    assert.strictEqual(typeof call.args.hash, 'string')
    assert.ok(!JSON.stringify(rows).includes('机密正文内容'), '参数正文不能进审计')
    assert.ok(!JSON.stringify(rows).includes('ling_secret'), '未知字段（含令牌）必须被丢弃')
    assert.ok(call.ts > 0)
    assert.strictEqual(audit.list(10)[0].event, 'confirm', '最近的在前')

    const files = fs.readdirSync(dir).filter((n) => /^audit-\d{6}\.jsonl$/.test(n))
    // 按 UTC 月份分文件；两条记录只在跨月零点这一瞬间才可能落到两个文件里
    assert.ok(files.length >= 1 && files.length <= 2)
    for (const line of audit.export().split('\n')) assert.doesNotThrow(() => JSON.parse(line))
  } finally {
    cleanup(dir)
  }
})
