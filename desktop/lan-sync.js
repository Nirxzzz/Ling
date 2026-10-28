/* lan-sync.js — 桌面端局域网同步服务（v1.61）。
 *
 * 设计边界（主脑定案）：
 *   · 只有用户点「开始同步」才监听；停止即关闭 socket、丢弃本次 session key，不做后台常驻。
 *   · 监听 0.0.0.0 的随机端口（>=1024），只接受局域网 / loopback 客户端；
 *     连接串 ling-sync://IP:port/<base64url(32 字节随机)>，最后一段就是本次的 AES-256 key。
 *   · 请求体是 AES-256-GCM 信封：{ v:1, iv:base64(12B), data:base64(密文 + 16B tag) }，
 *     AAD 固定 UTF-8 'ling-sync-v1'，明文 { v:1, records:[...] }。
 *   · 认证失败 / 重放 nonce / 超出并发 → 不碰存储，也不回任何同步数据。
 *   · 请求体和响应体都限制 8 MiB：超限明确报错并在 lastError 说明「库过大无法同步」，
 *     不隐性截断，也不做分片。
 *   · 不写 CORS 头、不提供静态文件、不碰 Electron 的全局 webSecurity。
 *
 * 对外只暴露 LanSync：
 *   const sync = new LanSync({ store: new LanStore({...}) })
 *   await sync.start()   → { connectionStrings: string[], port: number, hint?: string }
 *   await sync.stop()    → { ok: true }
 *          sync.status() → { running, port, connectionStrings, hint, startedAt,
 *                            lastExchangeAt, exchanges, conflicts, lastStats, lastError }
 *
 * start / stop 串行：stop() 用 _stopping 覆盖整个关闭过程（等 in-flight start、关 socket、
 * 丢 session key），start() 先等它结束；监听期间若 stop 已发生（设置卡关闭 / 退出），
 * start 会把刚开好的端口立刻关掉并抛 E_STOPPED，绝不在没有界面的情况下把端口留在后台。
 *
 * store 契约（由 lan-store.js 提供）：
 *   export() → Array<{uid,clock,value}>                           // 裸 records 数组（允许 Promise）
 *   apply(remoteRecords) → { records, conflicts, cards, stars, stats }   // records 可直接用于响应
 *     stats（v1.62）= { cards:{before,after,added,updated,deleted,restored,conflicts}, history:{...} }
 *     只留在本进程的 status.lastStats，不进同步响应（协议字段不变）。
 * 本模块不猜业务字段，也不直接读写任何文件；合并 / 落盘全部交给 store。
 */
'use strict'

const http = require('node:http')
const os = require('node:os')
const crypto = require('node:crypto')

const PROTOCOL = 'ling-sync'
const EXCHANGE_PATH = '/ling-sync/v1/exchange'
const ENVELOPE_VERSION = 1
const AAD = Buffer.from('ling-sync-v1', 'utf8')
const MAX_BODY = 8 * 1024 * 1024          // 8 MiB
const REQUEST_TIMEOUT_MS = 30000          // 单个请求 30s
const NONCE_CACHE_MAX = 1024              // 有界；满了拒绝并要求重新开始，绝不清空复用
const PORT_MIN = 49152                    // 随机高位端口，必然 >= 1024
const PORT_MAX = 65535
const LISTEN_ATTEMPTS = 24

const B64_RE = /^[A-Za-z0-9+/]+={0,2}$/

function httpError(code, message) {
  const err = new Error('lan-sync: ' + message)
  err.code = code
  return err
}

/** 严格 base64（标准字母表，长度必须是 4 的倍数）。 */
function decodeBase64(text) {
  if (typeof text !== 'string' || !text || text.length % 4 !== 0 || !B64_RE.test(text)) return null
  return Buffer.from(text, 'base64')
}

/** 只放行 RFC1918 与 loopback。 */
function isPrivateIPv4(ip) {
  if (typeof ip !== 'string') return false
  const parts = ip.split('.')
  if (parts.length !== 4) return false
  const n = parts.map((p) => (/^\d{1,3}$/.test(p) ? Number(p) : NaN))
  if (n.some((x) => !Number.isInteger(x) || x < 0 || x > 255)) return false
  const [a, b] = n
  if (a === 10) return true                                  // 10.0.0.0/8（含模拟器 10.0.2.2 所在网段）
  if (a === 172 && b >= 16 && b <= 31) return true           // 172.16.0.0/12
  if (a === 192 && b === 168) return true                    // 192.168.0.0/16
  if (a === 127) return true                                 // loopback
  return false
}

/** IPv4-mapped IPv6（::ffff:a.b.c.d）先还原；::1 视为 loopback。 */
function isAllowedPeer(address) {
  if (typeof address !== 'string') return false
  let ip = address
  if (ip.startsWith('::ffff:')) ip = ip.slice(7)
  if (ip === '::1') return true
  return isPrivateIPv4(ip)
}

/** RFC1918 IPv4 的界面清单（internal / 非 IPv4 一律跳过）。 */
function lanAddresses() {
  let table
  try { table = os.networkInterfaces() } catch { return [] }
  const out = []
  for (const name of Object.keys(table || {})) {
    for (const info of table[name] || []) {
      if (!info || info.internal) continue
      const family = info.family
      if (family !== 'IPv4' && family !== 4) continue
      if (!isPrivateIPv4(info.address)) continue
      out.push({ name: name, address: info.address })
    }
  }
  out.sort((a, b) => (a.name === b.name ? (a.address < b.address ? -1 : 1) : (a.name < b.name ? -1 : 1)))
  return out
}

/** 读请求体，超过 max 立刻 413 并断开（不把大包读进内存）。 */
function readBody(req, max, onTooLarge) {
  return new Promise((resolve, reject) => {
    let size = 0
    let stopped = false
    const chunks = []
    const stop = (err) => {
      if (stopped) return
      stopped = true
      reject(err)
    }
    req.on('data', (chunk) => {
      if (stopped) return
      size += chunk.length
      if (size > max) {
        try { onTooLarge() } catch {}
        try { req.destroy() } catch {}
        stop(httpError('E_BIG', '请求体超过 8 MiB'))
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => { if (!stopped) resolve(Buffer.concat(chunks)) })
    req.on('aborted', () => stop(httpError('E_ABORT', '客户端断开')))
    req.on('error', (err) => stop(err))
  })
}

function numberOrZero(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

/** 响应正文只放一行，错误信息按原样保留（不把本机磁盘错误改写成网络错误）。 */
function oneLine(value, max) {
  return String(value == null ? '' : value).replace(/\s+/g, ' ').trim().slice(0, max || 300)
}

/** 关掉一个具体的 server：先断连接再 close，1s 还没回调就强行收尾（不拖住 stop / quit）。 */
function closeServer(srv) {
  if (!srv) return Promise.resolve()
  try { if (typeof srv.closeAllConnections === 'function') srv.closeAllConnections() } catch {}
  return new Promise((resolve) => {
    let done = false
    let timer = null
    const finish = () => {
      if (done) return
      done = true
      if (timer) clearTimeout(timer)
      resolve()
    }
    try { srv.close(finish) } catch { finish() }
    if (!done) timer = setTimeout(finish, 1000)
  })
}

class LanSync {
  /**
   * @param {{ store: { export: Function, apply: Function } }} options
   */
  constructor(options) {
    const store = options && options.store
    if (!store || typeof store.export !== 'function' || typeof store.apply !== 'function') {
      throw new Error('lan-sync: 需要提供 export() / apply() 的 store（见 lan-store.js）')
    }
    this.store = store
    this.server = null
    this.port = 0
    this.key = null
    this.token = null
    this.usedNonces = new Set()
    this.sockets = new Set()
    this.busy = false
    this.startedAt = 0
    this.lastExchangeAt = 0
    this.lastError = ''
    this.exchanges = 0
    this.conflicts = 0
    // v1.62：最近一次 apply 的 before/after 统计（设置卡「上次同步」那一行）。
    this.lastStats = null
    this._starting = null
    this._stopping = null
    this._epoch = 0
  }

  /** 开服务。已开着就返回当前连接串（不重置 nonce 缓存）。 */
  async start() {
    // 关闭是串行的：必须等上一次 stop 真正关完，否则刚起的监听会被半路拆掉。
    while (this._stopping) {
      try { await this._stopping } catch {}
    }
    if (this.server) return this.report()
    if (this._starting) return this._starting
    const epoch = this._epoch
    const running = this.startOnce(epoch)
    this._starting = running
    try { return await running }
    finally { if (this._starting === running) this._starting = null }
  }

  async startOnce(epoch) {
    if (this.server) return this.report()
    if (!this.key || !this.token) {
      this.token = crypto.randomBytes(32)
      this.key = Buffer.from(this.token)
    }
    this.usedNonces = new Set()
    try {
      await this.listen()
    } catch (err) {
      this.key = null
      this.token = null
      throw err
    }
    // listen 期间 stop 已经发生（设置卡关闭 / 退出）：关掉刚开的端口，不留下任何后台监听。
    if (epoch !== this._epoch) {
      const srv = this.server
      this.server = null
      this.port = 0
      this.key = null
      this.token = null
      this.usedNonces = new Set()
      this.startedAt = 0
      await closeServer(srv)
      throw httpError('E_STOPPED', '同步已停止')
    }
    this.startedAt = Date.now()
    this.lastError = ''
    this.exchanges = 0
    this.conflicts = 0
    this.lastStats = null
    return this.report()
  }

  /** 停止：先 destroy 所有连接，再关 server，最后丢 key。重复调用共用同一个 _stopping。 */
  stop() {
    // 同步自增 epoch：任何正在 listen / 准备回包的请求都作废，不能 stop 之后再把端口开起来。
    this._epoch += 1
    if (this._stopping) return this._stopping
    const stopping = this.stopOnce().finally(() => {
      if (this._stopping === stopping) this._stopping = null
    })
    this._stopping = stopping
    return stopping
  }

  async stopOnce() {
    if (this._starting) {
      try { await this._starting } catch {}
    }
    const srv = this.server
    this.server = null
    this.port = 0
    this.key = null
    this.token = null
    this.usedNonces = new Set()
    this.startedAt = 0
    for (const socket of this.sockets) {
      try { socket.destroy() } catch {}
    }
    this.sockets.clear()
    if (!srv) return { ok: true }
    await closeServer(srv)
    return { ok: true }
  }

  status() {
    const running = !!this.server
    const strings = running ? this.connectionStrings() : []
    return {
      running: running,
      port: running ? this.port : 0,
      connectionStrings: strings,
      hint: running ? this.hintFor(strings) : '',
      startedAt: this.startedAt,
      lastExchangeAt: this.lastExchangeAt,
      exchanges: this.exchanges,
      conflicts: this.conflicts,
      lastStats: this.lastStats,
      lastError: this.lastError
    }
  }

  /* ------------------------------------------------------------ 网络 */

  listen() {
    return new Promise((resolve, reject) => {
      let attempt = 0
      const tryOnce = () => {
        const port = PORT_MIN + crypto.randomInt(PORT_MAX - PORT_MIN + 1)
        const server = http.createServer(
          { requestTimeout: REQUEST_TIMEOUT_MS, headersTimeout: 15000, keepAliveTimeout: 1000 },
          (req, res) => { this.handle(req, res) }
        )
        this.wire(server)
        const onError = (err) => {
          server.removeListener('listening', onListening)
          server.on('error', () => {})
          try { server.close() } catch {}
          if ((err.code === 'EADDRINUSE' || err.code === 'EACCES') && attempt < LISTEN_ATTEMPTS) {
            attempt += 1
            tryOnce()
            return
          }
          reject(err)
        }
        const onListening = () => {
          server.removeListener('error', onError)
          server.on('error', (err) => {
            this.lastError = '同步服务出错：' + String((err && err.message) || err)
          })
          const addr = server.address()
          this.server = server
          this.port = addr && typeof addr === 'object' ? addr.port : port
          resolve()
        }
        server.once('error', onError)
        server.once('listening', onListening)
        server.listen(port, '0.0.0.0')
      }
      tryOnce()
    })
  }

  wire(server) {
    server.on('connection', (socket) => {
      this.sockets.add(socket)
      socket.on('close', () => { this.sockets.delete(socket) })
      socket.on('error', () => {})
    })
    server.on('clientError', (_err, socket) => {
      try { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n') } catch {}
    })
  }

  report() {
    const strings = this.connectionStrings()
    return { connectionStrings: strings, port: this.port, hint: this.hintFor(strings) }
  }

  connectionStrings() {
    if (!this.server || !this.token) return []
    const token = this.token.toString('base64url')
    return lanAddresses().map((item) => PROTOCOL + '://' + item.address + ':' + this.port + '/' + token)
  }

  hintFor(strings) {
    if (strings.length) return ''
    return '没找到局域网 IPv4 地址（10 / 172.16–31 / 192.168 网段）。先让电脑和手机连同一个 Wi-Fi，再重新开始同步。'
  }

  /* ------------------------------------------------------------ 请求 */

  text(res, status, body) {
    if (!res || res.writableEnded) return
    const buf = Buffer.from(body + '\n', 'utf8')
    try {
      res.writeHead(status, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': buf.length,
        'Cache-Control': 'no-store',
        'Connection': 'close'
      })
      res.end(buf)
    } catch {}
  }

  async handle(req, res) {
    let ownedBusy = false
    let timer = null
    const onTimer = () => {
      if (res.writableEnded) return
      this.text(res, 408, 'timeout')
      try { req.destroy() } catch {}
    }
    try {
      if (req.method !== 'POST') { this.text(res, 405, 'method not allowed'); return }

      let url
      try { url = new URL(req.url, 'http://ling-sync.invalid') } catch { this.text(res, 400, 'bad request'); return }
      if (url.pathname !== EXCHANGE_PATH || url.search) { this.text(res, 404, 'not found'); return }
      if (!isAllowedPeer(req.socket && req.socket.remoteAddress)) { this.text(res, 403, 'forbidden'); return }
      if (!this.server || !this.key) { this.text(res, 503, 'not running'); return }
      // 本次请求属于哪一代服务：stop() 自增 _epoch，之后到达的异步回包一律作废。
      const epoch = this._epoch
      if (this.busy) { res.setHeader('Retry-After', '1'); this.text(res, 503, 'busy'); return }

      const declared = Number(req.headers['content-length'])
      if (Number.isFinite(declared) && declared > MAX_BODY) { this.text(res, 413, 'payload too large'); return }

      this.busy = true
      ownedBusy = true
      timer = setTimeout(onTimer, REQUEST_TIMEOUT_MS)
      if (typeof timer.unref === 'function') timer.unref()

      let body
      try {
        body = await readBody(req, MAX_BODY, () => { this.text(res, 413, 'payload too large') })
      } catch (err) {
        if (!res.headersSent && err && err.code !== 'E_BIG' && err.code !== 'E_ABORT') this.text(res, 400, 'bad request')
        return
      }

      let envelope
      try { envelope = JSON.parse(body.toString('utf8')) } catch { this.text(res, 400, 'bad request'); return }
      if (!envelope || envelope.v !== ENVELOPE_VERSION) { this.text(res, 400, 'bad envelope'); return }

      const iv = decodeBase64(envelope.iv)
      const sealed = decodeBase64(envelope.data)
      if (!iv || iv.length !== 12 || !sealed || sealed.length < 17) { this.text(res, 400, 'bad envelope'); return }

      let plain
      try {
        const tag = sealed.subarray(sealed.length - 16)
        const ciphertext = sealed.subarray(0, sealed.length - 16)
        const decipher = crypto.createDecipheriv('aes-256-gcm', this.key, iv)
        decipher.setAAD(AAD)
        decipher.setAuthTag(tag)
        plain = Buffer.concat([decipher.update(ciphertext), decipher.final()])
      } catch {
        // 认证失败：没有解析 records、没有调用 apply —— 文件不会变，也不会回任何同步数据
        this.text(res, 401, 'unauthorized')
        return
      }

      // 认证通过后才记 nonce：没有 key 的扫描者无法靠灌垃圾把缓存撑满
      const nonceKey = iv.toString('base64')
      if (this.usedNonces.has(nonceKey)) { this.text(res, 409, 'nonce replayed'); return }
      if (this.usedNonces.size + 2 > NONCE_CACHE_MAX) {
        this.text(res, 409, 'nonce cache full; stop and start sync again')
        return
      }
      this.usedNonces.add(nonceKey)

      let payload
      try { payload = JSON.parse(plain.toString('utf8')) } catch { this.text(res, 400, 'bad payload'); return }
      if (!payload || payload.v !== ENVELOPE_VERSION || !Array.isArray(payload.records)) {
        this.text(res, 400, 'bad payload')
        return
      }

      // 读包期间 stop 可能已经发生：绝不能在端口关闭后再写本机库。
      if (epoch !== this._epoch || !this.server || !this.key) { this.text(res, 503, 'not running'); return }

      let result
      try {
        result = await this.store.apply(payload.records)
      } catch (err) {
        // 本机写盘失败要原样透出（保留 message），不能笼统标成网络错误，更不能假装成功。
        const message = oneLine((err && err.message) || err) || '未知错误'
        this.lastError = '同步合并失败：' + message
        this.text(res, 500, 'apply failed: ' + message)
        return
      }
      if (!result || !Array.isArray(result.records)) {
        this.lastError = '同步合并没有返回记录'
        this.text(res, 500, 'apply failed: no records')
        return
      }

      // apply 返回的 records 就是合并后的完整快照，直接用于响应；不再二次 export（失败会被吞成假成功）。
      const conflicts = numberOrZero(result.conflicts)
      const out = {
        v: ENVELOPE_VERSION,
        records: result.records,
        conflicts: conflicts,
        cards: numberOrZero(result.cards),
        stars: numberOrZero(result.stars)
      }

      // 响应同样受 8 MiB 限制：库太大就明确报错，不隐性截断，也不做分片。
      const plainOut = Buffer.from(JSON.stringify(out), 'utf8')
      if (plainOut.length > MAX_BODY) {
        this.lastError = '本机知识库过大，超过 8 MiB，无法通过局域网同步；请减少知识卡或收藏后再试（不会截断数据）'
        this.text(res, 413, 'payload too large')
        return
      }

      if (epoch !== this._epoch || !this.server || !this.key) { this.text(res, 503, 'not running'); return }

      let responseIv
      try { responseIv = this.freshNonce() } catch { this.text(res, 500, 'nonce exhausted'); return }

      let envelopeOut
      try {
        const cipher = crypto.createCipheriv('aes-256-gcm', this.key, responseIv)
        cipher.setAAD(AAD)
        const ciphertext = Buffer.concat([cipher.update(plainOut), cipher.final()])
        const tag = cipher.getAuthTag()
        envelopeOut = {
          v: ENVELOPE_VERSION,
          iv: responseIv.toString('base64'),
          data: Buffer.concat([ciphertext, tag]).toString('base64')
        }
      } catch {
        this.text(res, 500, 'encrypt failed')
        return
      }

      const bodyOut = Buffer.from(JSON.stringify(envelopeOut), 'utf8')
      if (bodyOut.length > MAX_BODY) {
        // base64 / 信封开销让密文超过上限：同样明确失败，不截断、不发明分片协议。
        this.lastError = '本机知识库过大，加密后的响应超过 8 MiB，无法通过局域网同步；请减少知识卡或收藏后再试（不会截断数据）'
        this.text(res, 413, 'payload too large')
        return
      }
      this.exchanges += 1
      this.conflicts += conflicts
      this.lastStats = result.stats && typeof result.stats === 'object'
        ? Object.assign({ at: Date.now() }, result.stats)
        : null
      this.lastExchangeAt = Date.now()
      this.lastError = ''
      if (res.writableEnded) return
      try {
        res.writeHead(200, {
          'Content-Type': 'application/json; charset=utf-8',
          'Content-Length': bodyOut.length,
          'Cache-Control': 'no-store',
          'Connection': 'close'
        })
        res.end(bodyOut)
      } catch {}
    } catch {
      this.lastError = '同步服务处理请求失败'
      if (!res.headersSent) this.text(res, 500, 'error')
    } finally {
      if (timer) clearTimeout(timer)
      if (ownedBusy) this.busy = false
    }
  }

  /** 每次响应都用新的 12 字节 nonce；重复就重抽（不覆盖、不清空缓存）。 */
  freshNonce() {
    for (let i = 0; i < 8; i += 1) {
      const iv = crypto.randomBytes(12)
      const key = iv.toString('base64')
      if (this.usedNonces.has(key)) continue
      this.usedNonces.add(key)
      return iv
    }
    throw httpError('E_NONCE', 'nonce 碰撞')
  }
}

module.exports = { LanSync, EXCHANGE_PATH, MAX_BODY, isAllowedPeer, isPrivateIPv4 }
