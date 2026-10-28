// ling-link：手机常连通道的服务端行为测试（真实 HTTP + 真实 AES-256-GCM 信封，不打桩）。
//
// 覆盖：私网监听/URI 生成/0600 原子落盘、hello/poll/result/unpair 全链路、401/409/400/503、
// 重放窗口跨重启、单机 8 条上限、超时后不再投递、结果严格绑定配对与已投递命令、
// 撤销客户端 / 解除配对 / stop 时挂起请求的收尾、状态里绝不出现密钥。
'use strict'

const test = require('node:test')
const assert = require('node:assert')
const http = require('node:http')
const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const L = require('../ling-link')

const AAD = Buffer.from('ling-link-v1', 'utf8')
const PAIR_ID_RE = /^[0-9a-f]{32}$/

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-ling-link-'))
}

function cleanup(dir) {
  if (dir) fs.rmSync(dir, { recursive: true, force: true })
}

/** 手机侧封装（独立实现，不复用服务端 seal/openEnvelope，避免同错同对）。 */
function sealWith(key, ivHex, inner, outerPairId) {
  const iv = Buffer.from(ivHex, 'hex')
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  cipher.setAAD(AAD)
  const ct = Buffer.concat([cipher.update(Buffer.from(JSON.stringify(inner), 'utf8')), cipher.final()])
  return {
    v: 1,
    pairId: outerPairId || inner.pairId,
    iv: iv.toString('base64'),
    data: Buffer.concat([ct, cipher.getAuthTag()]).toString('base64')
  }
}

function openWith(key, envelope) {
  const sealed = Buffer.from(envelope.data, 'base64')
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64'))
  decipher.setAAD(AAD)
  decipher.setAuthTag(sealed.subarray(sealed.length - 16))
  const plain = Buffer.concat([decipher.update(sealed.subarray(0, sealed.length - 16)), decipher.final()])
  return JSON.parse(plain.toString('utf8'))
}

/** 每台「手机」一个序号 + 每请求新 IV（IV 必须唯一，重放保护靠它）。 */
function phone(pair, key) {
  let seq = 0
  return {
    id: pair.id,
    seq: () => { seq += 1; return seq },
    envelope(kind, extra, ivHex) {
      const inner = Object.assign({ v: 1, pairId: pair.id, kind: kind, seq: this.seq(), at: Date.now() }, extra || {})
      return sealWith(key, ivHex || crypto.randomBytes(12).toString('hex'), inner)
    }
  }
}

function post(port, kind, envelope, options) {
  const opts = options || {}
  return new Promise((resolve, reject) => {
    const body = Buffer.from(typeof envelope === 'string' ? envelope : JSON.stringify(envelope), 'utf8')
    const req = http.request({
      host: '127.0.0.1',
      port: port,
      path: '/ling-link/v1/' + kind,
      method: opts.method || 'POST',
      agent: false,
      headers: Object.assign({ 'Content-Type': 'application/json', 'Content-Length': body.length, Connection: 'close' }, opts.headers)
    }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8')
        let json = null
        try { json = JSON.parse(text) } catch {}
        resolve({ status: res.statusCode, text: text, json: json, headers: res.headers })
      })
    })
    req.on('error', reject)
    req.end(body)
  })
}

/** 起一个服务 + 一条配对，返回测试需要的全部句柄。 */
async function harness(options) {
  const opts = options || {}
  const dir = opts.dir || tmpDir()
  const link = L.createLink({ dir: dir, port: 0, now: opts.now, onEvent: opts.onEvent })
  await link.start()
  const created = link.createPair({ name: 'Mac' })
  const target = L.parseUri(created.uris[0])
  assert.strictEqual(target.port, link.status().port, 'URI 里的端口必须是实际监听端口')
  const dev = phone(created, target.key)
  return {
    dir: dir,
    link: link,
    pair: created,
    key: target.key,
    phone: dev,
    port: link.status().port,
    async hello(extra) {
      const res = await post(link.status().port, 'hello', dev.envelope('hello', Object.assign({
        device: { model: 'Pixel 8', app: 'lingzhu/2.7.2', caps: ['memory', 'todo'], apps: [{ alias: 'ling_review', name: 'Ling 复习' }] }
      }, extra)))
      return res
    },
    async stop() {
      await link.stop()
      cleanup(dir)
    }
  }
}

/**
 * 挂一次空 poll，100ms 后用一条新命令把它唤醒，返回这一批 cmds。
 * 用来验证「旧命令没有混进新的一批」——比干等 25s 让空 poll 超时快得多，断言也更硬。
 */
async function pollWoken(h) {
  const waiting = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
  await new Promise((r) => setTimeout(r, 120))
  const pending = h.link.call('ling_memory_search', { wake: 1 }, { id: 'wake', name: 'w', scopes: ['read'] }, { level: 'L1', timeoutMs: 4000 })
    .then((value) => ({ ok: true, value: value }), (err) => ({ ok: false, code: err.code }))
  const res = await waiting
  return { res: res, batch: openWith(h.key, res.json).cmds, pending: pending }
}

/* ---------------------------------------------------------------- URI / 落盘 */

test('配对 URI 严格解析：只认私网 IPv4、端口范围、规范 base64url', () => {
  const key = Buffer.alloc(32, 7).toString('base64url')
  const id = 'a'.repeat(32)
  const ok = L.parseUri('ling-link://192.168.1.5:47322/' + id + '.' + key)
  assert.strictEqual(ok.id, id)
  assert.strictEqual(ok.host, '192.168.1.5')
  assert.strictEqual(ok.port, 47322)
  assert.strictEqual(ok.key.length, 32)

  assert.deepStrictEqual(L.parseUri('ling-link://10.0.2.2:47322/' + id + '.' + key).host, '10.0.2.2')
  assert.deepStrictEqual(L.parseUri('ling-link://172.31.0.9:1024/' + id + '.' + key).port, 1024)
  assert.deepStrictEqual(L.parseUri('ling-link://127.0.0.1:65535/' + id + '.' + key).port, 65535)

  // 公网 / DNS / 端口越界 / query / fragment / 非法 id / 非规范 base64url 一律拒绝
  const bad = [
    'ling-link://8.8.8.8:47322/' + id + '.' + key,
    'ling-link://example.com:47322/' + id + '.' + key,
    'ling-link://192.168.1.5:80/' + id + '.' + key,
    'ling-link://192.168.1.5:70000/' + id + '.' + key,
    'ling-link://192.168.1.5:47322/' + id + '.' + key + '?x=1',
    'ling-link://192.168.1.5:47322/' + id + '.' + key + '#f',
    'ling-link://user:pass@192.168.1.5:47322/' + id + '.' + key,
    'ling-link://192.168.1.5:47322/' + 'A'.repeat(32) + '.' + key,
    'ling-link://192.168.1.5:47322/' + id + '.' + key.slice(0, 42),
    'http://192.168.1.5:47322/' + id + '.' + key,
    '',
    null
  ]
  for (const text of bad) {
    assert.throws(() => L.parseUri(text), (err) => err.code === 'INVALID_ARGS', '应拒绝：' + text)
  }
  // 非规范 base64url：32B 编码成 43 个字符，末位只有高 2 bit 是数据。
  // 把末位低 4 bit 改动、高 2 bit 保持不变，解出来还是同样的 32 字节 —— 必须按「非规范」拒绝。
  const zeros = Buffer.alloc(32, 0).toString('base64url')
  assert.strictEqual(zeros, 'A'.repeat(43))
  const nonCanonical = zeros.slice(0, 42) + 'B'            // 同一 32 字节，但末位有脏 bit
  assert.strictEqual(Buffer.from(nonCanonical, 'base64url').length, 32)
  assert.deepStrictEqual(Buffer.from(nonCanonical, 'base64url'), Buffer.alloc(32, 0))
  assert.throws(
    () => L.parseUri('ling-link://192.168.1.5:47322/' + id + '.' + nonCanonical),
    (err) => err.code === 'INVALID_ARGS' && /规范/.test(err.message)
  )
})

test('配对文件 0600 + 原子写；状态里没有密钥', async () => {
  const h = await harness()
  try {
    const file = path.join(h.dir, 'link-pairs.json')
    const stat = fs.statSync(file)
    assert.strictEqual(stat.mode & 0o777, 0o600)
    assert.ok(!fs.existsSync(file + '.tmp'), '不应留下 tmp 文件')

    // 配对文件必须真的存着密钥（否则重启后手机连不上），但只在它自己的 key 字段里
    const doc = JSON.parse(fs.readFileSync(file, 'utf8'))
    assert.strictEqual(doc.v, 1)
    assert.strictEqual(doc.pairs.length, 1)
    assert.strictEqual(doc.pairs[0].key, h.key.toString('base64url'))
    assert.deepStrictEqual(Object.keys(doc.pairs[0]).sort(), ['createdAt', 'device', 'id', 'key', 'name'])

    // status 绝不能泄密钥（URI、base64/base64url、hex 都不行）
    const statusText = JSON.stringify(h.link.status())
    assert.ok(!statusText.includes(h.key.toString('base64url')))
    assert.ok(!statusText.includes(h.key.toString('hex')))
    assert.ok(!statusText.includes(h.key.toString('base64')))
    assert.ok(!statusText.includes('ling-link://'))
    const status = h.link.status()
    assert.deepStrictEqual(status.pairs.map((p) => p.id), [h.pair.id])
    assert.strictEqual(status.devices[0].id, h.pair.id)
    assert.strictEqual(status.devices[0].online, false, '还没 hello/poll 就是离线')
  } finally {
    await h.stop()
  }
})

test('createPair 的 uris 只在这里出现，且每张网卡一条', async () => {
  const h = await harness()
  try {
    const second = h.link.createPair() // 默认名字
    assert.strictEqual(second.name, '电脑')
    assert.ok(PAIR_ID_RE.test(second.id))
    assert.notStrictEqual(second.id, h.pair.id)
    assert.ok(second.uris.length >= 1)
    for (const uri of second.uris) {
      const parsed = L.parseUri(uri)
      assert.strictEqual(parsed.id, second.id)
      assert.notStrictEqual(parsed.key.toString('base64url'), h.key.toString('base64url'))
    }
    assert.strictEqual(h.link.status().pairs.length, 2)
    assert.deepStrictEqual(h.link.revoke(second.id), { ok: true })
    assert.strictEqual(h.link.status().pairs.length, 1)
    assert.deepStrictEqual(h.link.revoke(second.id), { ok: false, error: '没有这个配对' })
  } finally {
    await h.stop()
  }
})

/* ---------------------------------------------------------------- 全链路 */

test('hello → poll → result 全链路（含在线状态与能力）', async () => {
  const h = await harness()
  try {
    let res = await h.hello()
    assert.strictEqual(res.status, 200)
    const hello = openWith(h.key, res.json)
    assert.strictEqual(hello.v, 1)
    assert.strictEqual(hello.kind, 'hello')
    assert.strictEqual(hello.ok, true)
    assert.strictEqual(hello.pollTimeoutMs, 25000)
    assert.ok(Number.isInteger(hello.serverTime) && Math.abs(hello.serverTime - Date.now()) < 5000)
    assert.strictEqual(hello.pairId, undefined, '内层不回 pairId')

    const device = h.link.status().devices[0]
    assert.strictEqual(device.online, true)
    assert.deepStrictEqual(device.caps, ['memory', 'todo'])
    assert.deepStrictEqual(device.apps, [{ alias: 'ling_review', name: 'Ling 复习' }])
    assert.strictEqual(device.model, 'Pixel 8')

    const pending = h.link.call('ling_memory_search', { query: '椭圆' }, { id: 'c1', name: 'claude', scopes: ['read'] }, { level: 'L1' })

    res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    assert.strictEqual(res.status, 200)
    const poll = openWith(h.key, res.json)
    assert.strictEqual(poll.cmds.length, 1)
    const cmd = poll.cmds[0]
    assert.match(cmd.id, /^[0-9a-f]{24}$/)
    assert.strictEqual(cmd.tool, 'ling_memory_search')
    assert.deepStrictEqual(cmd.args, { query: '椭圆' })
    assert.strictEqual(cmd.level, 'L1')
    assert.deepStrictEqual(cmd.client, { id: 'c1', name: 'claude', scopes: ['read'] })
    assert.ok(cmd.deadline > Date.now())

    res = await post(h.port, 'result', h.phone.envelope('result', {
      id: cmd.id, ok: true, result: { hits: 2 }, confirm: 'n/a'
    }))
    const ack = openWith(h.key, res.json)
    assert.deepStrictEqual(ack, { v: 1, kind: 'result', ok: true, accepted: true })
    assert.deepStrictEqual(await pending, { hits: 2 })
  } finally {
    await h.stop()
  }
})

test('poll 挂起时被新命令立刻唤醒，且不会延迟到 25s', async () => {
  const h = await harness()
  try {
    await h.hello()
    const t0 = Date.now()
    const longPoll = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    await new Promise((r) => setTimeout(r, 150))
    // 先挂上 then/catch，别让服务端先结算出 unhandled rejection
    const pending = h.link.call('ling_phone_open_app', { alias: 'calendar' }, { id: 'c9', name: 'x', scopes: ['act'] }, { level: 'L3' })
      .then((value) => ({ ok: true, value: value }), (err) => ({ ok: false, code: err.code, confirm: err.confirm }))
    const res = await longPoll
    const poll = openWith(h.key, res.json)
    assert.strictEqual(poll.cmds.length, 1)
    assert.strictEqual(poll.cmds[0].tool, 'ling_phone_open_app')
    assert.strictEqual(poll.cmds[0].level, 'L3')
    assert.ok(Date.now() - t0 < 3000, '应当在毫秒级唤醒而不是等 25s')
    await post(h.port, 'result', h.phone.envelope('result', { id: poll.cmds[0].id, ok: false, error: { code: 'DENIED', message: '用户拒绝' }, confirm: 'denied' }))
    assert.deepStrictEqual(await pending, { ok: false, code: 'DENIED', confirm: 'denied' })
  } finally {
    await h.stop()
  }
})

test('挂起的 poll 到点返回空批（长轮询超时行为）', async () => {
  // 用可控时钟不行：这里真的等满 25s 会拖慢测试，所以只验证「没有命令时不会被立刻回包」。
  const h = await harness()
  try {
    await h.hello()
    const started = Date.now()
    const res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const elapsed = Date.now() - started
    assert.strictEqual(res.status, 200)
    const poll = openWith(h.key, res.json)
    assert.deepStrictEqual(poll.cmds, [])
    assert.ok(elapsed >= 24000, '空 poll 应当一直挂着（实测 ' + elapsed + 'ms）')
    assert.ok(elapsed < 32000, '不能挂过头（实测 ' + elapsed + 'ms）')
  } finally {
    await h.stop()
  }
})

test('结果只绑定本配对、已投递且未结束的命令', async () => {
  const h = await harness()
  try {
    await h.hello()
    // 未知 id：200 + accepted:false，绝不报错、也绝不让调用方以为成功
    let res = await post(h.port, 'result', h.phone.envelope('result', {
      id: 'a'.repeat(24), ok: true, result: {}, confirm: 'n/a'
    }))
    assert.strictEqual(res.status, 200)
    assert.deepStrictEqual(openWith(h.key, res.json), { v: 1, kind: 'result', ok: true, accepted: false })

    // 第二台配对（不 hello，不参与选机）拿 A 的命令 id 上报结果：必须 accepted:false
    const other = h.link.createPair()
    const otherPhone = phone(other, L.parseUri(other.uris[0]).key)
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 5000 })
    res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmd = openWith(h.key, res.json).cmds[0]
    assert.match(cmd.id, /^[0-9a-f]{24}$/)

    res = await post(h.port, 'result', otherPhone.envelope('result', { id: cmd.id, ok: true, result: { spoof: 1 }, confirm: 'n/a' }))
    assert.deepStrictEqual(openWith(L.parseUri(other.uris[0]).key, res.json), { v: 1, kind: 'result', ok: true, accepted: false },
      '别的配对不能替 A 回结果')

    // A 的命令仍然等着，A 回结果才结算
    res = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: { hits: 1 }, confirm: 'n/a' }))
    assert.deepStrictEqual(openWith(h.key, res.json), { v: 1, kind: 'result', ok: true, accepted: true })
    assert.deepStrictEqual(await pending, { hits: 1 })

    // 同一个结果再报一次：已经没有这条命令了，只回 accepted:false（绝不重复结算）
    res = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: { hits: 99 }, confirm: 'n/a' }))
    assert.deepStrictEqual(openWith(h.key, res.json), { v: 1, kind: 'result', ok: true, accepted: false })
  } finally {
    await h.stop()
  }
})

test('多台在线时调用被判歧义，绝不自动选一台', async () => {
  const h = await harness()
  try {
    await h.hello()
    const other = h.link.createPair()
    const otherTarget = L.parseUri(other.uris[0])
    await post(h.port, 'hello', phone(other, otherTarget.key).envelope('hello', {
      device: { model: 'X', app: 'a', caps: [], apps: [] }
    }))
    assert.strictEqual(h.link.status().devices.filter((d) => d.online).length, 2)
    await assert.rejects(
      h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }),
      (err) => err.code === 'DEVICE_AMBIGUOUS' && /2 台/.test(err.message)
    )
    // 撤掉第二台之后又能唯一选中
    h.link.revoke(other.id)
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 2000 })
    const res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmds = openWith(h.key, res.json).cmds
    assert.strictEqual(cmds.length, 1)
    await post(h.port, 'result', h.phone.envelope('result', { id: cmds[0].id, ok: true, result: { ok: 1 }, confirm: 'n/a' }))
    assert.deepStrictEqual(await pending, { ok: 1 })
  } finally {
    await h.stop()
  }
})

/* ---------------------------------------------------------------- 安全 */

test('错误密钥 401、kind 不符 400、结构非法 400、方法/路径 400/404/405', async () => {
  const h = await harness()
  try {
    await h.hello()
    const badKey = Buffer.alloc(32, 9)

    let res = await post(h.port, 'poll', sealWith(badKey, crypto.randomBytes(12).toString('hex'), {
      v: 1, pairId: h.pair.id, kind: 'poll', seq: 1, at: Date.now(), acks: []
    }))
    assert.strictEqual(res.status, 401)

    res = await post(h.port, 'poll', h.phone.envelope('hello', { device: { model: '', app: '', caps: [], apps: [] } }))
    assert.strictEqual(res.status, 400)

    // 内层 pairId 与配对不一致（外层仍是真 pairId，否则先撞 401）
    res = await post(h.port, 'poll', sealWith(h.key, crypto.randomBytes(12).toString('hex'), {
      v: 1, pairId: 'b'.repeat(32), kind: 'poll', seq: 1, at: Date.now(), acks: []
    }, h.pair.id))
    assert.strictEqual(res.status, 400)

    // seq 必须是正整数
    res = await post(h.port, 'poll', sealWith(h.key, crypto.randomBytes(12).toString('hex'), {
      v: 1, pairId: h.pair.id, kind: 'poll', seq: 0, at: Date.now(), acks: []
    }))
    assert.strictEqual(res.status, 400)

    // 时钟偏差超 90s
    res = await post(h.port, 'poll', sealWith(h.key, crypto.randomBytes(12).toString('hex'), {
      v: 1, pairId: h.pair.id, kind: 'poll', seq: 1, at: Date.now() - 120000, acks: []
    }))
    assert.strictEqual(res.status, 400)
    assert.match(res.text, /clock skew/)

    // 外层不是 JSON / 缺字段 / v 不对
    res = await post(h.port, 'poll', 'not json')
    assert.strictEqual(res.status, 400)
    res = await post(h.port, 'poll', { v: 1, pairId: h.pair.id, iv: 'AAAA', data: 'AAAA' })
    assert.strictEqual(res.status, 400)

    // 未知路径 / 非 POST / query
    res = await post(h.port, 'nope', h.phone.envelope('poll', { acks: [] }))
    assert.strictEqual(res.status, 404)
    res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }), { method: 'GET' })
    assert.strictEqual(res.status, 405)
    const withQuery = await new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port: h.port, path: '/ling-link/v1/poll?x=1', method: 'POST', agent: false }, (r) => {
        r.resume(); r.on('end', () => resolve(r.statusCode))
      })
      req.on('error', reject)
      req.end('{}')
    })
    assert.strictEqual(withQuery, 400)
  } finally {
    await h.stop()
  }
})

test('重放（同一 IV）返回 409，且跨重启仍然拒绝', async () => {
  const dir = tmpDir()
  const link = L.createLink({ dir: dir, port: 0 })
  await link.start()
  const created = link.createPair({ name: 'Mac' })
  const target = L.parseUri(created.uris[0])
  const dev = phone(created, target.key)
  const port = link.status().port
  try {
    const envelope = dev.envelope('hello', { device: { model: 'P', app: 'a', caps: [], apps: [] } })
    let res = await post(port, 'hello', envelope)
    assert.strictEqual(res.status, 200)
    res = await post(port, 'hello', envelope)
    assert.strictEqual(res.status, 409)
    assert.match(res.text, /replayed/)
    await link.stop()

    // 重启（复用同一 dir）：180s 窗口内的同一个 IV 仍然拒绝
    const again = L.createLink({ dir: dir, port: 0 })
    await again.start()
    try {
      const port2 = again.status().port
      res = await post(port2, 'hello', envelope)
      assert.strictEqual(res.status, 409, '重启后同一个 IV 必须仍然被拒')
      // 换新 IV 就正常
      res = await post(port2, 'hello', dev.envelope('hello', { device: { model: 'P', app: 'a', caps: [], apps: [] } }))
      assert.strictEqual(res.status, 200)
      assert.strictEqual(openWith(target.key, res.json).kind, 'hello')
    } finally {
      await again.stop()
    }
  } finally {
    await link.stop().catch(() => {})
    cleanup(dir)
  }
})

test('重放表容量用尽时拒绝（不清空、不淘汰旧记录）', async () => {
  const dir = tmpDir()
  const nonceFile = path.join(dir, 'link-nonces.json')
  // 预置一张满表：180s 内的随机 IV
  const now = Date.now()
  const entries = []
  for (let i = 0; i < L.constants.NONCE_MAX; i += 1) {
    entries.push([crypto.randomBytes(12).toString('base64'), now - 1000])
  }
  fs.writeFileSync(nonceFile, JSON.stringify({ v: 1, entries: entries }), 'utf8')
  const link = L.createLink({ dir: dir, port: 0 })
  await link.start()
  const created = link.createPair({ name: 'Mac' })
  const target = L.parseUri(created.uris[0])
  try {
    const res = await post(link.status().port, 'hello', phone(created, target.key).envelope('hello', {
      device: { model: 'P', app: 'a', caps: [], apps: [] }
    }))
    assert.strictEqual(res.status, 409)
    assert.match(res.text, /nonce cache full/)
    // 旧记录没被清掉
    const doc = JSON.parse(fs.readFileSync(nonceFile, 'utf8'))
    assert.ok(doc.entries.length >= L.constants.NONCE_MAX)
  } finally {
    await link.stop()
    cleanup(dir)
  }
})

test('请求体超过 1 MiB 返回 413（先把状态码写回，再收尾连接）', async () => {
  const h = await harness()
  const huge = Buffer.alloc(1024 * 1024 + 64, 0x78)
  /** 发一个超大包，把「状态码」或「连接被提前拆掉时的错误码」都如实带回来。 */
  const send = (chunked) => new Promise((resolve) => {
    const headers = { 'Content-Type': 'application/json', Connection: 'close' }
    if (!chunked) headers['Content-Length'] = huge.length
    const req = http.request({
      host: '127.0.0.1', port: h.port, path: '/ling-link/v1/hello', method: 'POST', agent: false, headers: headers
    }, (res) => {
      const chunks = []
      res.on('data', (chunk) => chunks.push(chunk))
      res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }))
      res.on('error', (err) => resolve({ status: 'res:' + err.code, text: '' }))
    })
    // 服务端在客户端还没写完就 destroy 时，这里只会拿到 ECONNRESET/EPIPE——那不算 413，要暴露出来。
    req.on('error', (err) => resolve({ status: 'req:' + err.code, text: '' }))
    req.end(huge)
  })
  try {
    const one = await send(false)
    assert.strictEqual(one.status, 413, '超限必须回 413，拿到的是 ' + one.status)
    assert.match(one.text, /payload too large/)

    // 分块上传（没有 Content-Length）走同一条判定路径
    const chunked = await send(true)
    assert.strictEqual(chunked.status, 413, '分块上传超限同样要回 413，拿到的是 ' + chunked.status)

    // 并发多条：以前「回完 413 立刻 destroy」会和对端的写入抢跑，套件负载下偶发 ECONNRESET
    const many = await Promise.all([0, 1, 2, 3, 4, 5, 6, 7].map(() => send(false)))
    assert.deepStrictEqual(many.map((r) => r.status), [413, 413, 413, 413, 413, 413, 413, 413])

    // 413 之后服务本身照常工作
    const ok = await post(h.port, 'hello', h.phone.envelope('hello', { device: { model: 'P', app: 'a', caps: [], apps: [] } }))
    assert.strictEqual(ok.status, 200)
    assert.strictEqual(h.link.status().error, '')
  } finally {
    await h.stop()
  }
})

/* ---------------------------------------------------------------- 队列 / 超时 / 撤销 */

test('单机同时最多 8 条，第 9 条 BUSY（不排队等待）', async () => {
  const h = await harness()
  try {
    await h.hello()
    const results = []
    for (let i = 0; i < 9; i += 1) {
      results.push(h.link.call('ling_memory_search', { i: i }, { id: 'c' + i, name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 800 })
        .then((v) => ({ ok: true, v: v }), (err) => ({ ok: false, code: err.code })))
    }
    const settled = await Promise.all(results)
    assert.deepStrictEqual(settled.map((r) => r.code || 'ok'), [
      'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'TIMEOUT', 'BUSY'
    ])
  } finally {
    await h.stop()
  }
})

test('超时后命令不再投递；迟到结果只记事件、不影响任何调用', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 300 })
    await assert.rejects(pending, (err) => err.code === 'TIMEOUT')
    // 超时之后才来的 poll：不许再拿到这条过期的命令（用一条新命令唤醒这一批，断言更硬）
    const woken = await pollWoken(h)
    assert.deepStrictEqual(woken.batch.map((c) => c.args), [{ wake: 1 }], '过期命令绝不能重新投递')
    await post(h.port, 'result', h.phone.envelope('result', { id: woken.batch[0].id, ok: true, result: {}, confirm: 'n/a' }))
    assert.deepStrictEqual(await woken.pending, { ok: true, value: {} })
    // 结果也不会被接受
    const late = await post(h.port, 'result', h.phone.envelope('result', { id: 'c'.repeat(24), ok: true, result: {}, confirm: 'n/a' }))
    assert.deepStrictEqual(openWith(h.key, late.json), { v: 1, kind: 'result', ok: true, accepted: false })
  } finally {
    await h.stop()
  }
})

test('投递之后超时：结果被忽略，只留一条 late-result 记录', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const pending = h.link.call('ling_phone_screen_read', { mode: 'text' }, { id: 'c1', name: 'x', scopes: ['act'] }, { level: 'L3', timeoutMs: 400 })
    const res = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmd = openWith(h.key, res.json).cmds[0]
    await assert.rejects(pending, (err) => err.code === 'TIMEOUT' && /可能已执行/.test(err.message))
    const lateRes = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: { text: 'x' }, confirm: 'approved' }))
    assert.deepStrictEqual(openWith(h.key, lateRes.json), { v: 1, kind: 'result', ok: true, accepted: false })
    const late = events.filter((e) => e.type === 'late-result')
    assert.strictEqual(late.length, 1)
    assert.strictEqual(late[0].entry.state, 'timeout')
    assert.strictEqual(late[0].entry.tool, 'ling_phone_screen_read')
    assert.strictEqual(h.link.status().lateResults.length, 1)
  } finally {
    await h.stop()
  }
})

test('AbortSignal 取消：立刻拒绝且命令不再投递（已投递的记为 cancelled）', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const controller = new AbortController()
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', signal: controller.signal, timeoutMs: 5000 })
    controller.abort()
    await assert.rejects(pending, (err) => err.code === 'CANCELLED')
    const woken = await pollWoken(h)
    assert.deepStrictEqual(woken.batch.map((c) => c.args), [{ wake: 1 }], '取消过的命令绝不能再投递')
    await post(h.port, 'result', h.phone.envelope('result', { id: woken.batch[0].id, ok: true, result: {}, confirm: 'n/a' }))
    assert.deepStrictEqual(await woken.pending, { ok: true, value: {} })

    // 已投递后取消：留 cancelled 记录，结果被忽略
    const c2 = new AbortController()
    const pending2 = h.link.call('ling_memory_search', { q: 2 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', signal: c2.signal, timeoutMs: 5000 })
    const poll2 = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmd = openWith(h.key, poll2.json).cmds[0]
    c2.abort()
    await assert.rejects(pending2, (err) => err.code === 'CANCELLED')
    const lateRes = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: {}, confirm: 'n/a' }))
    assert.strictEqual(openWith(h.key, lateRes.json).accepted, false)
    assert.strictEqual(events.filter((e) => e.type === 'late-result').length, 1)
    assert.strictEqual(events.filter((e) => e.type === 'late-result')[0].entry.state, 'cancelled')
  } finally {
    await h.stop()
  }
})

test('取消已预取消的 signal 直接拒绝，不排队', async () => {
  const h = await harness()
  try {
    await h.hello()
    const controller = new AbortController()
    controller.abort()
    await assert.rejects(
      h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { signal: controller.signal }),
      (err) => err.code === 'CANCELLED'
    )
    assert.strictEqual(h.link.status().devices[0].queued, 0)
  } finally {
    await h.stop()
  }
})

test('撤销 MCP 客户端会作废它排队的指令', async () => {
  const h = await harness()
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'client-a', name: 'claude', scopes: ['read'] }, { level: 'L1', timeoutMs: 5000 })
    assert.strictEqual(h.link.cancelClient('client-a'), 1)
    await assert.rejects(pending, (err) => err.code === 'REVOKED')
    const woken = await pollWoken(h)
    assert.deepStrictEqual(woken.batch.map((c) => c.args), [{ wake: 1 }], '撤销后绝不能再投递')
    await post(h.port, 'result', h.phone.envelope('result', { id: woken.batch[0].id, ok: true, result: {}, confirm: 'n/a' }))
    assert.deepStrictEqual(await woken.pending, { ok: true, value: {} })
    assert.strictEqual(h.link.cancelClient('client-a'), 0)
    assert.strictEqual(h.link.cancelClient(''), 0)
  } finally {
    await h.stop()
  }
})

test('撤销客户端时已投递的指令：如实说明可能已执行，并留一条 late-result(revoked)', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_add', { text: '写进去' }, { id: 'client-b', name: 'x', scopes: ['write'] }, { level: 'L2', timeoutMs: 10000 })
    // 让手机真的领走这条命令：此后手机上的副作用已经无法撤回
    const poll = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmd = openWith(h.key, poll.json).cmds[0]
    assert.ok(cmd, '命令必须先被投递出去，这条用例才有意义')

    assert.strictEqual(h.link.cancelClient('client-b'), 1)
    await assert.rejects(pending, (err) => err.code === 'REVOKED' && /可能已执行/.test(err.message))
    // 审计里必须留下这条：已投递 → 撤销，未来的写不会凭空消失
    const late = events.filter((e) => e.type === 'late-result')
    assert.strictEqual(late.length, 1)
    assert.strictEqual(late[0].entry.state, 'revoked')
    assert.deepStrictEqual(h.link.status().lateResults.map((r) => [r.id, r.state]), [[cmd.id, 'revoked']])
    // 迟到结果照旧被忽略，不会再影响任何调用
    const lateRes = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: {}, confirm: 'n/a' }))
    assert.strictEqual(openWith(h.key, lateRes.json).accepted, false)
  } finally {
    await h.stop()
  }
})

test('撤销配对时已投递的指令：留一条 late-result(cancelled)，且结果被忽略', async () => {
  const h = await harness()
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_add', { text: '写进去' }, { id: 'c1', name: 'x', scopes: ['write'] }, { level: 'L2', timeoutMs: 10000 })
    const poll = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    const cmd = openWith(h.key, poll.json).cmds[0]
    assert.ok(cmd)
    assert.deepStrictEqual(h.link.revoke(h.pair.id), { ok: true })
    await assert.rejects(pending, (err) => err.code === 'PHONE_OFFLINE' && /可能已执行/.test(err.message))
    assert.deepStrictEqual(h.link.status().lateResults.map((r) => [r.id, r.state]), [[cmd.id, 'cancelled']])
    // 配对已撤销：迟到结果连认证都过不了
    const lateRes = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: {}, confirm: 'n/a' }))
    assert.strictEqual(lateRes.status, 401)
  } finally {
    await h.stop()
  }
})

test('撤销客户端时还没投递的指令：立刻 REVOKED，且不留 late-result（没有副作用）', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_add', { text: '排队中' }, { id: 'client-c', name: 'x', scopes: ['write'] }, { level: 'L2', timeoutMs: 10000 })
    assert.strictEqual(h.link.cancelClient('client-c'), 1)
    await assert.rejects(pending, (err) => err.code === 'REVOKED' && !/可能已执行/.test(err.message))
    assert.strictEqual(events.filter((e) => e.type === 'late-result').length, 0)
    assert.deepStrictEqual(h.link.status().lateResults, [])
  } finally {
    await h.stop()
  }
})

test('手机离线立即 PHONE_OFFLINE，不排队等待', async () => {
  // 1) 配对后手机还从没说过话
  const h = await harness()
  try {
    await assert.rejects(
      h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }),
      (err) => err.code === 'PHONE_OFFLINE'
    )
    // hello 之后就在线了（第一个 poll 还没发出去也不算离线）
    await h.hello()
    assert.strictEqual(h.link.status().devices[0].online, true)
  } finally {
    await h.stop()
  }

  // 2) 时钟越过 35s 心跳窗口之后立刻离线，不等超时
  const dir = tmpDir()
  let clock = Date.now()
  const link = L.createLink({ dir: dir, port: 0, now: () => clock })
  await link.start()
  const created = link.createPair({ name: 'Mac' })
  const dev = phone(created, L.parseUri(created.uris[0]).key)
  try {
    let res = await post(link.status().port, 'hello', dev.envelope('hello', {
      device: { model: 'P', app: 'a', caps: [], apps: [] }
    }))
    // 手机时间与电脑时间都源自同一个 clock，所以这里用真实服务器时间也要对齐
    assert.strictEqual(res.status, 200)
    clock += 40000
    await assert.rejects(
      link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }),
      (err) => err.code === 'PHONE_OFFLINE' && /35 秒/.test(err.message)
    )
    assert.strictEqual(link.status().devices[0].online, false)
  } finally {
    await link.stop()
    cleanup(dir)
  }
})

test('参数校验：缺客户端 / 非对象参数 / 超 64 KiB 都拒绝', async () => {
  const h = await harness()
  try {
    await h.hello()
    await assert.rejects(h.link.call('', {}, { id: 'c1', name: 'x', scopes: [] }), (err) => err.code === 'INVALID_ARGS')
    await assert.rejects(h.link.call('ling_memory_search', [], { id: 'c1', name: 'x', scopes: [] }), (err) => err.code === 'INVALID_ARGS')
    await assert.rejects(h.link.call('ling_memory_search', {}, { name: 'x', scopes: [] }), (err) => err.code === 'INVALID_ARGS')
    await assert.rejects(h.link.call('ling_memory_search', {}, { id: 'c1', scopes: 'read' }), (err) => err.code === 'INVALID_ARGS')
    await assert.rejects(
      h.link.call('ling_memory_search', { blob: 'x'.repeat(70 * 1024) }, { id: 'c1', name: 'x', scopes: [] }),
      (err) => err.code === 'INVALID_ARGS' && /64 KiB/.test(err.message)
    )
  } finally {
    await h.stop()
  }
})

/* ---------------------------------------------------------------- 解除配对 / 停止 */

test('unpair：先回加密 ack，再删配对；之后再请求 401', async () => {
  const events = []
  const h = await harness({ onEvent: (e) => events.push(e) })
  try {
    await h.hello()
    const res = await post(h.port, 'unpair', h.phone.envelope('unpair'))
    assert.strictEqual(res.status, 200)
    const ack = openWith(h.key, res.json)
    assert.strictEqual(ack.kind, 'unpair')
    assert.strictEqual(ack.ok, true)
    assert.strictEqual(h.link.status().pairs.length, 0)
    assert.strictEqual(h.link.status().devices.length, 0)

    const after = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    assert.strictEqual(after.status, 401)
    assert.ok(events.some((e) => e.type === 'pair-removed' && e.reason === 'unpair'))
    // 配对文件同步删掉
    const doc = JSON.parse(fs.readFileSync(path.join(h.dir, 'link-pairs.json'), 'utf8'))
    assert.deepStrictEqual(doc.pairs, [])
  } finally {
    await h.stop()
  }
})

test('桌面撤销配对：挂起的调用立刻失败，长轮询立即结束', async () => {
  const h = await harness()
  try {
    await h.hello()
    // 先挂一个空 poll（没有待办命令 -> 它应该一直挂着），再撤销配对
    const started = Date.now()
    const longPoll = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    await new Promise((r) => setTimeout(r, 100))
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 20000 })
    // 新命令会唤醒它（这是正常投递），先把结果领走
    const first = await longPoll
    assert.strictEqual(first.status, 200)
    const cmd = openWith(h.key, first.json).cmds[0]
    assert.ok(cmd)
    // 命令还没回结果时撤销配对；再挂一个空 poll 也要被立刻结束
    const secondPoll = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    await new Promise((r) => setTimeout(r, 100))
    h.link.revoke(h.pair.id)
    await assert.rejects(pending, (err) => err.code === 'PHONE_OFFLINE' && /撤销/.test(err.message))
    const res = await secondPoll
    assert.strictEqual(res.status, 503, '撤销后挂起的长轮询必须立刻结束')
    assert.ok(Date.now() - started < 5000)
    assert.strictEqual(h.link.status().running, true, '撤销配对不影响服务本身')
    // 已经投递出去的命令结果也不认了（配对已经没了 -> 401）
    const late = await post(h.port, 'result', h.phone.envelope('result', { id: cmd.id, ok: true, result: {}, confirm: 'n/a' }))
    assert.strictEqual(late.status, 401)
  } finally {
    await h.stop()
  }
})

test('stop：立刻结束挂起的长轮询与待办调用，且可重复调用', async () => {
  const h = await harness()
  try {
    await h.hello()
    const started = Date.now()
    // 先挂一个空 poll，再放一条命令 / 或者先放命令都不能让 poll 卡住；这里只挂空 poll。
    const longPoll = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] })).catch((err) => ({ error: err }))
    await new Promise((r) => setTimeout(r, 100))
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 20000 })
    // 命令唤醒了那条 poll，领走之后 pending 仍然在等结果
    const first = await longPoll
    assert.strictEqual(first.status, 200)
    assert.strictEqual(openWith(h.key, first.json).cmds.length, 1)
    // 再挂一个空 poll，然后 stop
    const held = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] })).catch((err) => ({ error: err }))
    await new Promise((r) => setTimeout(r, 100))
    await h.link.stop()
    await assert.rejects(pending, (err) => err.code === 'PHONE_OFFLINE')
    const res = await held
    assert.ok(Date.now() - started < 5000, 'stop 不能让 poll 卡到 25s')
    // 长轮询要么拿到 503，要么连接被拆（两种都算及时结束）
    if (res.status !== undefined) assert.strictEqual(res.status, 503)
    await h.link.stop() // 幂等
    assert.strictEqual(h.link.status().running, false)
    assert.strictEqual(h.link.status().port, 0)
    // 停掉之后不再接受调用
    await assert.rejects(
      h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }),
      (err) => err.code === 'PHONE_OFFLINE' && /没有开启/.test(err.message)
    )
  } finally {
    await h.stop()
  }
})

test('重启后配对仍在，换新 IV 即可继续用；旧口令（不同 key）不行', async () => {
  const dir = tmpDir()
  const first = L.createLink({ dir: dir, port: 0 })
  await first.start()
  const created = first.createPair({ name: 'Mac' })
  const target = L.parseUri(created.uris[0])
  await first.stop()

  const second = L.createLink({ dir: dir, port: 0 })
  await second.start()
  try {
    assert.deepStrictEqual(second.status().pairs.map((p) => p.id), [created.id])
    const dev = phone(created, target.key)
    let res = await post(second.status().port, 'hello', dev.envelope('hello', {
      device: { model: 'Pixel', app: 'lingzhu/2.7.2', caps: ['todo'], apps: [] }
    }))
    assert.strictEqual(res.status, 200)
    // 换成一把不同的 32B key：认证必须失败
    const wrong = phone(created, crypto.randomBytes(32))
    res = await post(second.status().port, 'hello', wrong.envelope('hello', { device: { model: '', app: '', caps: [], apps: [] } }))
    assert.strictEqual(res.status, 401)
  } finally {
    await second.stop()
    cleanup(dir)
  }
})

test('配对文件损坏时拒绝启动，绝不覆盖（否则密钥就没了）', async () => {
  const dir = tmpDir()
  fs.writeFileSync(path.join(dir, 'link-pairs.json'), '{ 坏掉的 JSON', 'utf8')
  const link = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(link.start(), /配对文件损坏/)
  assert.strictEqual(link.status().running, false)
  assert.match(link.status().error, /配对文件损坏/)
  // 文件原样保留
  assert.strictEqual(fs.readFileSync(path.join(dir, 'link-pairs.json'), 'utf8'), '{ 坏掉的 JSON')
  await link.stop()
  cleanup(dir)
})

test('配对文件里有坏条目：拒绝启动并保留原字节（不许静默丢条目）', async () => {
  const dir = tmpDir()
  const seed = L.createLink({ dir: dir, port: 0 })
  await seed.start()
  seed.createPair({ name: 'Mac' })
  await seed.stop()
  const pairsFile = path.join(dir, 'link-pairs.json')
  const doc = JSON.parse(fs.readFileSync(pairsFile, 'utf8'))
  const goodId = doc.pairs[0].id
  // 一条好的 + 一条密钥残缺的：以前这条坏条目会被跳过，之后任一次写盘就把它从磁盘上删掉
  doc.pairs.push({ id: 'a'.repeat(32), name: '坏条目', key: 'short' })
  const original = JSON.stringify(doc)
  fs.writeFileSync(pairsFile, original, 'utf8')

  const link = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(link.start(), /密钥不完整/, '坏条目必须让 start 明确失败')
  assert.match(link.status().error, /密钥不完整/, '设置页要能看到这件事')
  // 不接受新配对，也不写盘
  assert.throws(() => link.createPair({ name: 'x' }), /密钥不完整/)
  assert.strictEqual(fs.readFileSync(pairsFile, 'utf8'), original, '原字节必须一字不差')
  await link.stop()
  assert.strictEqual(fs.readFileSync(pairsFile, 'utf8'), original, 'stop 也不许改写')

  // 把坏条目去掉之后，好条目照常加载（没有把内存表留成半截状态）
  const cleaned = { v: doc.v, pairs: [{ id: goodId, name: 'Mac', key: doc.pairs[0].key, createdAt: doc.pairs[0].createdAt }] }
  fs.writeFileSync(pairsFile, JSON.stringify(cleaned), 'utf8')
  const fixed = L.createLink({ dir: dir, port: 0 })
  await fixed.start()
  try {
    assert.deepStrictEqual(fixed.status().pairs.map((p) => p.id), [goodId])
    assert.strictEqual(fixed.status().error, '')
  } finally {
    await fixed.stop()
    cleanup(dir)
  }
})

test('重放记录损坏时拒绝启动（fail closed，不当作空表）', async () => {
  const dir = tmpDir()
  // 有合法配对，但重放表坏了：必须在 listen 之前就拒绝，而不是带着「不知道哪些 IV 用过」上线。
  const seed = L.createLink({ dir: dir, port: 0 })
  await seed.start()
  seed.createPair({ name: 'Mac' })
  await seed.stop()
  fs.writeFileSync(path.join(dir, 'link-nonces.json'), '{ 坏掉的 JSON', 'utf8')

  const link = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(link.start(), /重放记录损坏/)
  assert.strictEqual(link.status().running, false)
  // 文件原样保留，交给用户决定删不删
  assert.strictEqual(fs.readFileSync(path.join(dir, 'link-nonces.json'), 'utf8'), '{ 坏掉的 JSON')
  await link.stop()
  cleanup(dir)
})

test('重放记录读不出来时 stop 也不写盘：损坏的文件与「从未启动」都要原样保留', async () => {
  // 1) 文件损坏：start 已按设计拒绝，之后 stop（退出应用必然调它）不能拿空表覆盖
  const dir = tmpDir()
  const seed = L.createLink({ dir: dir, port: 0 })
  await seed.start()
  seed.createPair({ name: 'Mac' })
  await seed.stop()
  const nonceFile = path.join(dir, 'link-nonces.json')
  fs.writeFileSync(nonceFile, '{ 坏掉的 JSON', 'utf8')
  const link = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(link.start(), /重放记录损坏/)
  await link.stop()
  assert.strictEqual(fs.readFileSync(nonceFile, 'utf8'), '{ 坏掉的 JSON', 'stop 不许把损坏的重放表改成空表')
  // 再启动仍然拒绝：文件没被抹掉，fail closed 依然成立
  const again = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(again.start(), /重放记录损坏/)
  await again.stop()
  assert.strictEqual(fs.readFileSync(nonceFile, 'utf8'), '{ 坏掉的 JSON')
  cleanup(dir)

  // 2) 实例从没 start 过：内存里的空表不是磁盘的真相，stop 不能写
  const dir2 = tmpDir()
  const iv = crypto.randomBytes(12).toString('base64')
  const original = JSON.stringify({ v: 1, entries: [[iv, Date.now()]] })
  fs.writeFileSync(path.join(dir2, 'link-nonces.json'), original, 'utf8')
  const idle = L.createLink({ dir: dir2, port: 0 })
  await idle.stop()
  assert.strictEqual(fs.readFileSync(path.join(dir2, 'link-nonces.json'), 'utf8'), original, '没读过的重放表绝不回写')
  cleanup(dir2)
})

test('重放记录格式不对 / 读不了也拒绝启动', async () => {
  const dir = tmpDir()
  fs.writeFileSync(path.join(dir, 'link-nonces.json'), JSON.stringify({ v: 1, entries: 'nope' }), 'utf8')
  const link = L.createLink({ dir: dir, port: 0 })
  await assert.rejects(link.start(), /重放记录格式不对/)
  await link.stop()
  // 格式不对同样属于「读不出来」，stop 之后文件必须还是那份格式不对的内容
  assert.strictEqual(fs.readFileSync(path.join(dir, 'link-nonces.json'), 'utf8'), JSON.stringify({ v: 1, entries: 'nope' }))
  cleanup(dir)
})

test('撤销配对时落盘失败：配对仍然有效，返回 ok:false（绝不假装撤销成功）', async () => {
  const dir = tmpDir()
  const link = L.createLink({ dir: dir, port: 0 })
  await link.start()
  const created = link.createPair({ name: 'Mac' })
  const target = L.parseUri(created.uris[0])
  try {
    // 把目录换成一个只读文件：persistPairs 的 tmp 写不进去
    const pairsFile = path.join(dir, 'link-pairs.json')
    const backup = fs.readFileSync(pairsFile, 'utf8')
    fs.rmSync(pairsFile)
    fs.mkdirSync(pairsFile)   // 同名目录 -> rename/write 必失败
    const result = link.revoke(created.id)
    assert.strictEqual(result.ok, false)
    // 配对必须还在（内存里），手机也仍然能认证
    assert.strictEqual(link.status().pairs.length, 1)
    const res = await post(link.status().port, 'hello', phone(created, target.key).envelope('hello', {
      device: { model: 'P', app: 'a', caps: [], apps: [] }
    }))
    assert.strictEqual(res.status, 200)
    fs.rmdirSync(pairsFile)
    fs.writeFileSync(pairsFile, backup, 'utf8')
  } finally {
    await link.stop()
    cleanup(dir)
  }
})

test('手机解绑落盘失败不回成功确认，恢复存储后才能持久解除', async () => {
  const h = await harness()
  const file = path.join(h.dir, 'link-pairs.json')
  const backup = fs.readFileSync(file)
  try {
    await h.hello()
    fs.unlinkSync(file)
    fs.mkdirSync(file)
    const failed = await post(h.port, 'unpair', h.phone.envelope('unpair'))
    assert.strictEqual(failed.status, 500)
    assert.strictEqual(h.link.status().pairs[0].id, h.pair.id)
    fs.rmdirSync(file)
    fs.writeFileSync(file, backup)
    const retry = await post(h.port, 'unpair', h.phone.envelope('unpair'))
    assert.strictEqual(retry.status, 200)
    assert.strictEqual(openWith(h.key, retry.json).ok, true)
    assert.deepStrictEqual(JSON.parse(fs.readFileSync(file, 'utf8')).pairs, [])
    const stale = await post(h.port, 'hello', h.phone.envelope('hello', {
      device: { model: 'P', app: 'a', caps: [], apps: [] }
    }))
    assert.strictEqual(stale.status, 401)
  } finally {
    await h.stop()
  }
})

test('已投递的命令只投一次：重复 poll 不会重发', async () => {
  const h = await harness()
  try {
    await h.hello()
    const pending = h.link.call('ling_memory_search', { q: 1 }, { id: 'c1', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 8000 })
    const first = await post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    assert.strictEqual(openWith(h.key, first.json).cmds.length, 1)
    // 手机还没回结果就再 poll：必须是空批（不能重发）
    const second = post(h.port, 'poll', h.phone.envelope('poll', { acks: [] }))
    // 第二轮会挂起，用一条新命令把它唤醒，验证唤醒的只有新命令
    await new Promise((r) => setTimeout(r, 100))
    const other = h.link.call('ling_memory_search', { q: 2 }, { id: 'c2', name: 'x', scopes: ['read'] }, { level: 'L1', timeoutMs: 8000 })
    const res = await second
    const cmds = openWith(h.key, res.json).cmds
    assert.strictEqual(cmds.length, 1)
    assert.deepStrictEqual(cmds[0].args, { q: 2 })
    await post(h.port, 'result', h.phone.envelope('result', { id: cmds[0].id, ok: true, result: { n: 2 }, confirm: 'n/a' }))
    assert.deepStrictEqual(await other, { n: 2 })
    await pending.catch(() => {})
  } finally {
    await h.stop()
  }
})

test('非私网来源被拒（403）：用 127.0.0.1 之外的地址无法命中，这里验证判定函数', () => {
  // isAllowedPeer 来自 lan-sync，只放行 RFC1918 + loopback；公网地址必须为 false
  const lanSync = require('../lan-sync')
  assert.strictEqual(lanSync.isAllowedPeer('192.168.1.9'), true)
  assert.strictEqual(lanSync.isAllowedPeer('10.1.2.3'), true)
  assert.strictEqual(lanSync.isAllowedPeer('::ffff:172.20.0.4'), true)
  assert.strictEqual(lanSync.isAllowedPeer('8.8.8.8'), false)
  assert.strictEqual(lanSync.isAllowedPeer('2001:db8::1'), false)
})

test('共享测试向量：AES-256-GCM 信封与服务端实现逐字节一致', () => {
  const key = Buffer.alloc(32, 0)
  const iv = Buffer.alloc(12, 1).toString('hex')
  const inner = { v: 1, pairId: '0'.repeat(32), kind: 'unpair', seq: 1, at: 1700000000000 }
  const envelope = sealWith(key, iv, inner)
  // 与 Android 端（python cryptography AESGCM / java Cipher）算出的常量一致
  assert.strictEqual(envelope.iv, 'AQEBAQEBAQEBAQEB')
  assert.strictEqual(
    envelope.data,
    'DmK1SEC4VN8S14308kteU55V3Gs/1gUGP4UhgM/fl9ywGJdtykvc03ii3E6sWPBI0nbMHNnXE+tHHloIIKvbZ7oJyZtS56XeTneWA7s81VopWFJ8dhrcv5VtcplY+Dle18WEbbKF3Pp3EU/HgnE='
  )
  assert.deepStrictEqual(openWith(key, envelope), inner)

  // 服务端 seal 出来的东西也能用同一把 key 打开（往返一致），外层必须带 pairId
  const made = L.seal(key, inner)
  assert.strictEqual(made.v, 1)
  assert.strictEqual(made.pairId, inner.pairId)
  assert.deepStrictEqual(openWith(key, made), inner)
  // 显式给的 pairId 优先（响应内层不带 pairId 时也要有）
  assert.strictEqual(L.seal(key, { v: 1, kind: 'hello', ok: true }, 'f'.repeat(32)).pairId, 'f'.repeat(32))

  // 篡改一个字节必须解不开
  const bytes = Buffer.from(envelope.data, 'base64')
  bytes[0] ^= 0x01
  assert.throws(() => openWith(key, { iv: envelope.iv, data: bytes.toString('base64') }))
})
