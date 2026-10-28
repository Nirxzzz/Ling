/* ling-link.js — 手机常连通道（Ling Link v1）· 桌面端 / 纯 Node。
 *
 * ## 这是什么
 * 「局域网同步」（lan-sync.js）传的是整库快照，要用户手点；这条通道是**常连 RPC**：
 * 手机主动长轮询电脑，电脑把 Agent 的工具调用派给手机执行，再把结果送回给 MCP 客户端。
 *
 * ## 线协议（与安卓 LingLink.kt 逐字节一致，改之前先改契约）
 * - 只监听 `0.0.0.0:<port>`（默认 47322），对端必须是 RFC1918 / loopback，否则 403。
 * - 只有 POST：`/ling-link/v1/{hello,poll,result,unpair}`，不接受 query / fragment。
 * - 外层 `{v:1, pairId, iv, data}`；`iv` 12B 标准 base64，`data` = AES-256-GCM 密文||tag16B
 *   标准 base64，AAD 固定 UTF-8 `ling-link-v1`，密钥是该配对 32B linkKey。
 * - 内层 `{v:1, pairId, kind, seq, at, ...}`；kind 必须等于路径名。
 * - 响应同密钥、每次新 IV 加密；`{v:1, kind, ok:true, ...}`。
 * - 请求体 1 MiB 上限；命令 args / 结果输出各 64 KiB；`at` 与电脑时钟偏差 ±90s。
 * - 状态码：认证失败 401、重放 409、非法 400、忙 503、体太大 413、非私网 403。
 *
 * ## 安全边界
 * - 密钥只在配对文件（0600，tmp+rename 原子写）与 createPair 的返回里出现；`status()`
 *   与任何日志、错误消息里都没有密钥，也不写请求体。
 * - 重放：IV 只能用一次。已用 IV 连同时间戳落盘（`link-nonces.json`，0600），180s 窗口，
 *   重启后仍然有效；表满时**拒绝新请求**（409），绝不清空复用。
 * - 命令最多执行一次：投递即出队，超时/撤销/取消后永不再投递；迟到结果只记事件，不重放。
 * - 只有一个在线手机时才会自动选中；0 台立刻 PHONE_OFFLINE，多台 DEVICE_AMBIGUOUS（不猜）。
 *
 * ## 归属
 * 契约见 local://ling-mcp-contract.md。本文件不 require('electron')，dir 由调用方给。
 */
'use strict'

const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')

// 复用局域网同步那套「只放行私网」的判定，避免两份实现漂移。
const { isPrivateIPv4, isAllowedPeer } = require('./lan-sync')

const VERSION = 1
const AAD = Buffer.from('ling-link-v1', 'utf8')
const BASE_PATH = '/ling-link/v1/'
const KINDS = ['hello', 'poll', 'result', 'unpair']

const MAX_BODY = 1024 * 1024                 // 请求体 1 MiB
const DRAIN_MAX = 1024 * 1024                // 超过 1 MiB 后最多再丢弃这么多，之后直接断
const DRAIN_TIMEOUT_MS = 10000               // 回完 413 后最多再陪对端读这么久，然后直接断
const MAX_PAYLOAD = 64 * 1024                // 单条 args / 结果 64 KiB
const MAX_POLL_BATCH_BYTES = 480 * 1024      // 一次 poll 回包的明文预算（base64 后仍 < 1 MiB）
const POLL_HOLD_MS = 25000                   // 长轮询挂起时长（hello 会告诉手机）
const OFFLINE_MS = 35000                     // 超过这个时间没有 poll 就算离线
const SKEW_MS = 90000                        // 时钟容差 ±90s
const NONCE_TTL_MS = 180000                  // 重放记录保留 180s
const NONCE_MAX = 4096                       // 表容量上限；满了拒绝，不清空
const QUEUE_MAX = 8                          // 每台设备最多排队 8 条
const CALL_TIMEOUT_MS = 15000                // 普通指令 15s
const CALL_TIMEOUT_L3_MS = 45000             // L3（含本机确认）45s
const REQUEST_TIMEOUT_MS = 40000             // 必须 > POLL_HOLD_MS
const DEFAULT_PORT = 47322
const PORT_MIN = 1024
const PORT_MAX = 65535
const PAIRS_FILE = 'link-pairs.json'
const NONCES_FILE = 'link-nonces.json'
const NAME_MAX = 40
const TOOL_MAX = 64
const LINEAR_LATE_MAX = 20

const PAIR_ID_RE = /^[0-9a-f]{32}$/
const B64_RE = /^[A-Za-z0-9+/]+={0,2}$/
const URI_RE = /^ling-link:\/\/([0-9]{1,3}(?:\.[0-9]{1,3}){3}):([0-9]{1,5})\/([0-9a-f]{32})\.([A-Za-z0-9_-]{43})$/
const CONFIRMS = new Set(['approved', 'denied', 'timeout', 'n/a'])
const LEVELS = new Set(['L1', 'L2', 'L3'])
const CAPS = new Set(['memory', 'todo', 'screen', 'open_app'])

/** 带 code 的错误：上层（mcp-tools）直接用 err.code 当 MCP 错误码。 */
function linkError(code, message) {
  const err = new Error(message)
  err.code = code
  return err
}

/** 严格标准 base64（长度 4 的倍数、只允许标准字母表）。 */
function decodeBase64(text) {
  if (typeof text !== 'string' || !text || text.length % 4 !== 0 || !B64_RE.test(text)) return null
  try { return Buffer.from(text, 'base64') } catch { return null }
}

/**
 * 解配对文件里的 key。写盘用规范 base64url，但读的时候要容忍旧文件里带 padding 的
 * base64url / 标准 base64：那两种解出来的仍是同一把 32B 密钥，按「坏条目」拒绝会让老用户
 * 突然起不来。解出不是 32B 的才真的是坏条目。
 */
function decodeKeyText(text) {
  if (typeof text !== 'string' || !text || !/^[A-Za-z0-9_+/=-]+$/.test(text)) return null
  const normalized = text.replace(/=+$/, '')
  if (!/^[A-Za-z0-9_+/-]+$/.test(normalized)) return null
  const standard = normalized.replace(/-/g, '+').replace(/_/g, '/')
  if (standard.length % 4 === 1) return null
  try { return Buffer.from(standard, 'base64') } catch { return null }
}

/** RFC1918 / loopback 的 IPv4 清单（internal、非 IPv4 跳过），按名字排序保证稳定。 */
function privateAddresses() {
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

/**
 * 读请求体。超过 max 时：立刻通知上层写 413（先写状态与正文，暂不结束响应），随后继续把对端
 * 的包读干净、不再缓存任何字节，由上层在读完后正常收尾。若在这里 req.destroy() 或立刻 res.end()，
 * 对端往往还没写完就收到 RST（ECONNRESET/EPIPE），根本看不到 413。剩余量超过 DRAIN_MAX、或者
 * DRAIN_TIMEOUT_MS 内还没发完，就强行断开（那时对端早已收到状态码，不必陪着耗）。
 * 超限返回 null，正常返回 Buffer。declared：Content-Length 声明的长度（未知为 NaN），
 * 用它可以在对方还没开始发就先回 413。
 */
function readBody(req, max, onTooLarge, declared) {
  return new Promise((resolve, reject) => {
    let size = 0
    let stopped = false
    let tooLarge = Number.isFinite(declared) && declared > max
    let drainTimer = null
    const chunks = []
    const clearDrain = () => {
      if (drainTimer) { clearTimeout(drainTimer); drainTimer = null }
    }
    const finish = (value) => {
      if (stopped) return
      stopped = true
      clearDrain()
      resolve(value)
    }
    const stop = (err) => {
      if (stopped) return
      stopped = true
      clearDrain()
      reject(err)
    }
    /** 已经回过 413：只陪着把剩下的包读掉；拖着不发或量太离谱就直接断。 */
    const armDrain = () => {
      if (drainTimer) return
      drainTimer = setTimeout(() => {
        drainTimer = null
        try { req.destroy() } catch {}
        finish(null)
      }, DRAIN_TIMEOUT_MS)
      if (typeof drainTimer.unref === 'function') drainTimer.unref()
    }
    req.on('data', (chunk) => {
      if (stopped) return
      size += chunk.length
      if (tooLarge) {
        if (size > max + DRAIN_MAX) {
          try { req.destroy() } catch {}
          finish(null)
        }
        return
      }
      if (size > max) {
        tooLarge = true
        chunks.length = 0
        armDrain()
        try { onTooLarge() } catch {}
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => finish(tooLarge ? null : Buffer.concat(chunks)))
    req.on('aborted', () => (tooLarge ? finish(null) : stop(linkError('E_ABORT', '客户端断开'))))
    req.on('error', (err) => (tooLarge ? finish(null) : stop(err)))
    if (tooLarge) {
      chunks.length = 0
      armDrain()
      try { onTooLarge() } catch {}
    }
  })
}

/** 结束一个已经写过状态与正文的响应（幂等；连接已断也不抛）。 */
function endResponse(res) {
  try { if (res && !res.writableEnded && !res.destroyed) res.end() } catch {}
}

/** 原子写 JSON：tmp(0600) → fsync → rename → chmod，失败不留半截文件。 */
function writeJsonAtomic(file, value) {
  const tmp = file + '.tmp'
  const data = JSON.stringify(value)
  let fd = null
  try {
    fd = fs.openSync(tmp, 'w', 0o600)
    fs.writeFileSync(fd, data, 'utf8')
    try { fs.fsyncSync(fd) } catch {}
    fs.closeSync(fd)
    fd = null
    fs.renameSync(tmp, file)
    try { fs.chmodSync(file, 0o600) } catch {}
  } catch (err) {
    if (fd !== null) { try { fs.closeSync(fd) } catch {} }
    try { fs.unlinkSync(tmp) } catch {}
    throw err
  }
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

/** 明文体积（字节），用于 64 KiB / 1 MiB 这类上限判断。 */
function byteLength(value) {
  try { return Buffer.byteLength(JSON.stringify(value === undefined ? null : value), 'utf8') }
  catch { return Infinity }
}

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

/** 用配对密钥封装内层对象；每次新 IV。外层必须带 pairId（手机按它选密钥、并校验回显）。 */
function seal(key, inner, pairId) {
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  cipher.setAAD(AAD)
  const plain = Buffer.from(JSON.stringify(inner), 'utf8')
  const ciphertext = Buffer.concat([cipher.update(plain), cipher.final()])
  const tag = cipher.getAuthTag()
  return {
    v: VERSION,
    pairId: pairId || (typeof inner.pairId === 'string' ? inner.pairId : ''),
    iv: iv.toString('base64'),
    data: Buffer.concat([ciphertext, tag]).toString('base64')
  }
}

/** 解封装；认证失败 / 结构不对都返回 null（调用方按 401 / 400 分开处理）。 */
function openEnvelope(key, iv, sealed) {
  const tag = sealed.subarray(sealed.length - 16)
  const ciphertext = sealed.subarray(0, sealed.length - 16)
  let plain
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
    decipher.setAAD(AAD)
    decipher.setAuthTag(tag)
    plain = Buffer.concat([decipher.update(ciphertext), decipher.final()])
  } catch {
    return null
  }
  let inner
  try { inner = JSON.parse(plain.toString('utf8')) } catch { return null }
  return isPlainObject(inner) ? inner : null
}

/**
 * 严格解析配对 URI。格式（与安卓 LinkPairStore.parse 一致）：
 *   ling-link://<私网IPv4>:<1024..65535>/<32位小写hex pairId>.<base64url(32B key)>
 * 不接受 DNS 名、公网 IP、userinfo、query、fragment、非规范 base64url。
 */
function parseUri(text) {
  if (typeof text !== 'string' || !text || text.length > 200) {
    throw linkError('INVALID_ARGS', '配对链接为空或过长')
  }
  const m = URI_RE.exec(text)
  if (!m) throw linkError('INVALID_ARGS', '配对链接格式不对（应形如 ling-link://192.168.1.5:47322/<id>.<key>）')
  const host = m[1]
  const port = Number(m[2])
  const id = m[3]
  const keyText = m[4]
  if (!isPrivateIPv4(host)) throw linkError('INVALID_ARGS', '配对链接只允许局域网 / 回环地址')
  if (!Number.isInteger(port) || port < PORT_MIN || port > PORT_MAX) {
    throw linkError('INVALID_ARGS', '配对链接端口必须在 1024–65535')
  }
  let key
  try { key = Buffer.from(keyText.replace(/-/g, '+').replace(/_/g, '/'), 'base64') } catch {
    throw linkError('INVALID_ARGS', '配对链接密钥不是合法 base64url')
  }
  if (key.length !== 32) throw linkError('INVALID_ARGS', '配对链接密钥不是 32 字节')
  // 拒绝非规范编码（末位多余 bit 之类的变体），保证同一串只有一种解释。
  if (key.toString('base64url') !== keyText) {
    throw linkError('INVALID_ARGS', '配对链接密钥不是规范的 base64url')
  }
  if (!PAIR_ID_RE.test(id)) throw linkError('INVALID_ARGS', '配对链接 pairId 不是 32 位小写十六进制')
  return { id: id, host: host, port: port, key: key }
}

class LingLink {
  /**
   * @param {{ dir: string, port?: number, now?: () => number, onEvent?: (event: object) => void }} options
   *   dir：配对与重放状态的落盘目录（一般是 userData/mcp）。onEvent 只给审计用，绝不带密钥。
   */
  constructor(options) {
    const opts = options || {}
    if (!opts.dir || typeof opts.dir !== 'string') throw new Error('ling-link: 需要提供 dir')
    this.dir = opts.dir
    // port 0 = 让系统给一个临时端口（只给测试用；URI 里写的仍是实际绑定的端口）。
    this.port = Number.isInteger(opts.port) && opts.port >= 0 && opts.port <= PORT_MAX ? opts.port : DEFAULT_PORT
    this.now = typeof opts.now === 'function' ? opts.now : Date.now
    this.onEvent = typeof opts.onEvent === 'function' ? opts.onEvent : null

    this.server = null
    this.boundPort = 0
    this.error = ''

    /** @type {Map<string, object>} pairId -> 配对（key 是 Buffer，绝不出现在 status / 日志里） */
    this.pairs = new Map()
    /** @type {Map<string, number>} 已用 IV（base64）-> 记录时间 */
    this.nonces = new Map()
    /** @type {Map<string, object>} 命令 id -> 等待中的调用 */
    this.pending = new Map()
    this.sockets = new Set()
    /** 迟到的结果（超时/撤销之后才回来）：只记事件，不重放。 */
    this.late = []

    this._loaded = false
    this._loadError = null
    /** 重放表是否真的从磁盘读成功过（含「文件不存在」）；没读成功就绝不写回去。 */
    this._noncesLoaded = false
    this._starting = null
    this._stopping = null
    this._epoch = 0
  }

  /* ------------------------------------------------------------------ 生命周期 */

  async start() {
    while (this._stopping) {
      try { await this._stopping } catch {}
    }
    if (this.server) return this.status()
    if (this._starting) return this._starting
    const running = this.startOnce()
    this._starting = running
    try { return await running }
    finally { if (this._starting === running) this._starting = null }
  }

  async startOnce() {
    if (this.server) return this.status()
    this.loadPairs()
    if (this._loadError) throw this._loadError
    this.loadNonces()
    // 重放表读不出来就不能服务：宁可不开，也不能在「不知道哪些 IV 已用过」的状态下接受请求。
    if (this._nonceError) throw this._nonceError
    const epoch = this._epoch
    try {
      await this.listen()
    } catch (err) {
      this.error = '手机常连监听失败：' + this.errorText(err) + '（端口 ' + this.port + ' 被占用就换端口，不会自动换）'
      throw err
    }
    if (epoch !== this._epoch) {
      const srv = this.server
      this.server = null
      this.boundPort = 0
        await closeServer(srv)
      throw linkError('E_STOPPED', '手机常连已停止')
    }
    this.error = ''
    return this.status()
  }

  stop() {
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
    const prevPort = this.boundPort
    this.boundPort = 0

    // 先结束所有挂起的长轮询与待办调用：手机端立刻收到 503 / 明确失败，不会卡到下一轮。
    for (const pair of this.pairs.values()) {
      this.finishPoll(pair, null)
    }
    for (const entry of Array.from(this.pending.values())) {
      this.fail(entry, 'PHONE_OFFLINE', '手机常连已停止')
    }
    this.pending.clear()

    for (const socket of this.sockets) {
      try { socket.destroy() } catch {}
    }
    this.sockets.clear()
    // 退出时的压缩写盘：只有重放表**成功读过**才写。读失败（损坏）或从未 start 时内存里是空表，
    // 写下去会把用户那份「请备份后自行删除」的原文件抹成空表，等于让 fail-closed 的防重放静默作废。
    if (this._noncesLoaded) {
      try { this.persistNonces() } catch (err) { this.error = '退出时保存重放记录失败：' + this.errorText(err) }
    }
    if (!srv) return { ok: true, port: prevPort }
    await closeServer(srv)
    return { ok: true, port: prevPort }
  }

  /**
   * 给设置页看的状态。**永不含 linkKey**：只有 pair id / 名字 / 时间 / 设备信息。
   * `pairs` 与 `devices` 的 id 是同一个值（pair id），撤销都用 revoke(id)。
   */
  status() {
    const running = !!this.server
    const now = this.now()
    // 没启动过也要能列出上次的配对（设置页要能撤销），所以这里惰性读盘；读坏了只记 error，不抛。
    // 注意 _loadError 是**粘住**的：设置页刷新一次状态不能把「配对文件坏了」这件事忘掉，
    // 否则接着 start() 就会拿空表覆盖掉用户原来的配对（那就真把密钥弄丢了）。
    try { this.loadPairs() } catch {}
    if (this._loadError) this.error = this.errorText(this._loadError)
    const pairs = []
    const devices = []
    for (const pair of this.pairs.values()) {
      pairs.push({ id: pair.id, name: pair.name, createdAt: pair.createdAt || 0 })
      devices.push({
        id: pair.id,
        name: pair.name,
        online: this.isOnline(pair, now),
        lastSeen: pair.lastSeen || 0,
        caps: pair.device && Array.isArray(pair.device.caps) ? pair.device.caps.slice() : [],
        apps: pair.device && Array.isArray(pair.device.apps)
          ? pair.device.apps.map((item) => ({ alias: item.alias, name: item.name }))
          : [],
        model: (pair.device && pair.device.model) || '',
        app: (pair.device && pair.device.app) || '',
        queued: this.queuedCount(pair)
      })
    }
    return {
      running: running,
      port: running ? this.boundPort : 0,
      error: this.error || '',
      pollTimeoutMs: POLL_HOLD_MS,
      pairs: pairs,
      devices: devices,
      lateResults: this.late.slice(-LINEAR_LATE_MAX)
    }
  }

  /* ------------------------------------------------------------------ 配对 */

  /** 新配对：随机 pairId(16B) + 随机 linkKey(32B)。URI 只在这里返回一次。 */
  createPair(options) {
    const opts = options || {}
    this.loadPairs()
    if (this._loadError) throw this._loadError
    let name = typeof opts.name === 'string' ? opts.name.trim().slice(0, NAME_MAX) : ''
    if (!name) name = '电脑'
    const pair = {
      id: crypto.randomBytes(16).toString('hex'),
      name: name,
      key: crypto.randomBytes(32),
      createdAt: this.now(),
      lastSeen: 0,
      lastPollAt: 0,
      device: null,
      queue: [],
      poll: null
    }
    this.pairs.set(pair.id, pair)
    try {
      this.persistPairs()
    } catch (err) {
      this.pairs.delete(pair.id)
      try { pair.key.fill(0) } catch {}
      throw err
    }
    this.emit({ type: 'pair-created', pairId: pair.id, name: name })
    return { id: pair.id, name: name, uris: this.urisFor(pair) }
  }

  /**
   * 解除配对（桌面端撤销）。立刻拒绝该配对的待办调用，之后它的请求一律 401。
   * 落盘失败时返回 {ok:false,error} 且**配对保持有效**（不能假装撤销成功）。
   */
  revoke(pairId) {
    this.loadPairs()
    const pair = this.pairs.get(String(pairId || ''))
    if (!pair) return { ok: false, error: '没有这个配对' }
    try {
      this.removePair(pair, 'revoked')
    } catch (err) {
      return { ok: false, error: this.errorText(err) }
    }
    return { ok: true }
  }

  /** MCP 客户端被撤销：它排队的、正在等结果的指令全部作废，绝不投递、绝不重放。 */
  cancelClient(clientId) {
    const id = String(clientId || '')
    if (!id) return 0
    let count = 0
    for (const entry of Array.from(this.pending.values())) {
      if (entry.pair && entry.cmd.client && entry.cmd.client.id === id) {
        // 已经投递给手机的命令无法撤回：撤销只能作废等待、如实说明可能已执行，
        // 并像超时那样留一条 late-result，未来的写不会在审计里凭空消失。
        const delivered = entry.state === 'delivered'
        const message = delivered ? '客户端授权已撤销（指令可能已执行，结果会被丢弃）' : '客户端授权已撤销'
        if (this.fail(entry, 'REVOKED', message)) {
          count += 1
          if (delivered) this.recordLate(entry, 'revoked')
        }
      }
    }
    if (count) this.emit({ type: 'client-cancelled', clientId: id, count: count })
    return count
  }

  /* ------------------------------------------------------------------ 调用 */

  /**
   * 派一条命令给手机。
   * @returns {Promise<object>} 手机返回的 result 对象
   * 失败时抛 err.code ∈ PHONE_OFFLINE | DEVICE_AMBIGUOUS | TIMEOUT | CANCELLED | REVOKED | BUSY | INVALID_ARGS
   */
  async call(tool, args, client, options) {
    const opts = options || {}
    const now = this.now()
    if (!this.server) throw linkError('PHONE_OFFLINE', '手机常连没有开启')
    if (typeof tool !== 'string' || !tool.trim()) throw linkError('INVALID_ARGS', '工具名不能为空')
    const toolName = tool.trim().slice(0, TOOL_MAX)
    const payload = args === undefined || args === null ? {} : args
    if (!isPlainObject(payload)) throw linkError('INVALID_ARGS', '工具参数必须是对象')
    const argsBytes = byteLength(payload)
    if (argsBytes > MAX_PAYLOAD) throw linkError('INVALID_ARGS', '工具参数超过 64 KiB')

    const caller = isPlainObject(client) ? client : {}
    const clientId = typeof caller.id === 'string' ? caller.id.slice(0, TOOL_MAX) : ''
    if (!clientId) throw linkError('INVALID_ARGS', '缺少调用方客户端')
    if (!Array.isArray(caller.scopes) || caller.scopes.some((s) => typeof s !== 'string')) {
      throw linkError('INVALID_ARGS', '调用方 scopes 不合法')
    }
    const level = LEVELS.has(opts.level) ? opts.level : 'L2'
    let timeoutMs = level === 'L3' ? CALL_TIMEOUT_L3_MS : CALL_TIMEOUT_MS
    if (Number.isFinite(opts.timeoutMs) && opts.timeoutMs > 0) {
      timeoutMs = Math.min(Math.max(Math.floor(opts.timeoutMs), 1000), 120000)
    }

    const pair = this.pickPair(now)
    if (this.queuedCount(pair) >= QUEUE_MAX) throw linkError('BUSY', '手机待办已满（8 条），稍后再试')

    const id = crypto.randomBytes(12).toString('hex')
    const cmd = {
      id: id,
      tool: toolName,
      args: payload,
      level: level,
      client: {
        id: clientId,
        name: typeof caller.name === 'string' ? caller.name.slice(0, NAME_MAX) : '',
        scopes: caller.scopes.slice()
      },
      deadline: now + timeoutMs
    }

    return new Promise((resolve, reject) => {
      const entry = {
        id: id,
        pair: pair,
        cmd: cmd,
        state: 'queued',
        resolve: resolve,
        reject: reject,
        timer: null,
        signal: null,
        onAbort: null
      }
      const timer = setTimeout(() => {
        const delivered = entry.state === 'delivered'
        if (this.fail(entry, 'TIMEOUT', delivered ? '手机超时未返回结果（指令可能已执行）' : '手机未取走指令')) {
          if (delivered) this.recordLate(entry, 'timeout')
        }
      }, timeoutMs)
      if (typeof timer.unref === 'function') timer.unref()
      entry.timer = timer

      const signal = opts.signal
      if (signal && typeof signal.addEventListener === 'function') {
        if (signal.aborted) {
          clearTimeout(timer)
          reject(linkError('CANCELLED', '调用已取消'))
          return
        }
        const onAbort = () => {
          if (this.fail(entry, 'CANCELLED', '调用已取消') && entry.state === 'delivered') {
            this.recordLate(entry, 'cancelled')
          }
        }
        entry.signal = signal
        entry.onAbort = onAbort
        signal.addEventListener('abort', onAbort, { once: true })
      }

      this.pending.set(id, entry)
      pair.queue.push(id)
      // 手机正在长轮询 -> 立刻唤醒；否则等它下一次 poll。
      this.wakePoll(pair)
    })
  }

  /* ------------------------------------------------------------------ 内部：设备选择 */

  isOnline(pair, now) {
    const at = pair.lastPollAt || 0
    return at > 0 && now - at <= OFFLINE_MS
  }

  /** 只有恰好一台在线手机才自动选中：0 台离线、2 台有歧义都要明确失败，绝不猜。 */
  pickPair(now) {
    const online = []
    for (const pair of this.pairs.values()) {
      if (this.isOnline(pair, now)) online.push(pair)
    }
    if (online.length === 0) throw linkError('PHONE_OFFLINE', '手机不在线（35 秒内没有心跳）')
    if (online.length > 1) {
      throw linkError('DEVICE_AMBIGUOUS', '有 ' + online.length + ' 台手机在线，无法确定用哪一台')
    }
    return online[0]
  }

  queuedCount(pair) {
    let n = 0
    for (const entry of this.pending.values()) {
      if (entry.pair === pair) n += 1
    }
    return n
  }

  /** 结束一条待办：从队列里摘掉、清定时器、摘 abort 监听、拒绝调用方。返回 false 表示它已经有结论了。 */
  fail(entry, code, message) {
    if (!entry || this.pending.get(entry.id) !== entry) return false
    this.pending.delete(entry.id)
    clearTimeout(entry.timer)
    if (entry.signal && entry.onAbort) {
      try { entry.signal.removeEventListener('abort', entry.onAbort) } catch {}
    }
    const pair = entry.pair
    if (pair && Array.isArray(pair.queue)) {
      const idx = pair.queue.indexOf(entry.id)
      if (idx >= 0) pair.queue.splice(idx, 1)
    }
    try { entry.reject(linkError(code, message)) } catch {}
    return true
  }

  /** 迟到的结果 / 超时后才执行完：只留痕，绝不再投递。 */
  recordLate(entry, state) {
    const item = {
      id: entry.id,
      tool: entry.cmd.tool,
      client: entry.cmd.client.id,
      pairId: entry.pair ? entry.pair.id : '',
      state: state,
      at: this.now()
    }
    this.late.push(item)
    if (this.late.length > LINEAR_LATE_MAX) this.late.splice(0, this.late.length - LINEAR_LATE_MAX)
    this.emit({ type: 'late-result', entry: item })
  }

  emit(event) {
    if (!this.onEvent) return
    try { this.onEvent(event) } catch {}
  }

  /* ------------------------------------------------------------------ 内部：命令投递 */

  /** 取走可投递的命令（投递即出队，永不重投）。 */
  takeReady(pair) {
    const now = this.now()
    const out = []
    let bytes = 0
    const keep = []
    for (const id of pair.queue.slice()) {
      const entry = this.pending.get(id)
      if (!entry || entry.pair !== pair || entry.state !== 'queued') continue
      if (entry.cmd.deadline <= now) { keep.push(id); continue }   // 定时器马上会拒掉它
      if (out.length >= QUEUE_MAX) { keep.push(id); continue }
      const size = byteLength(entry.cmd)
      if (bytes + size > MAX_POLL_BATCH_BYTES) { keep.push(id); continue }
      bytes += size
      entry.state = 'delivered'
      out.push(entry.cmd)
    }
    pair.queue = keep
    return out
  }

  wakePoll(pair) {
    if (!pair.poll) return
    const ready = this.takeReady(pair)
    if (ready.length) this.finishPoll(pair, { v: VERSION, kind: 'poll', ok: true, cmds: ready })
  }

  /** 结束（或接管）该配对唯一一个挂起的长轮询；payload 为 null 表示「停止」。 */
  finishPoll(pair, payload) {
    const waiter = pair.poll
    if (!waiter) return
    pair.poll = null
    clearTimeout(waiter.timer)
    waiter.settle(payload)
  }

  /* ------------------------------------------------------------------ 内部：请求处理 */

  handleHello(pair, inner) {
    const now = this.now()
    const device = inner.device
    if (!isPlainObject(device)) throw linkError('INVALID_ARGS', 'hello 缺少 device')
    const model = typeof device.model === 'string' ? device.model.trim().slice(0, 80) : ''
    const app = typeof device.app === 'string' ? device.app.trim().slice(0, 40) : ''
    const caps = Array.isArray(device.caps) ? device.caps.filter((c) => typeof c === 'string' && CAPS.has(c)) : []
    const apps = []
    if (Array.isArray(device.apps)) {
      for (const item of device.apps) {
        if (!isPlainObject(item)) continue
        const alias = typeof item.alias === 'string' ? item.alias.trim().slice(0, 32) : ''
        const name = typeof item.name === 'string' ? item.name.trim().slice(0, 60) : ''
        if (!alias || !/^[A-Za-z0-9_.-]{1,32}$/.test(alias)) continue
        apps.push({ alias: alias, name: name })
        if (apps.length >= 32) break
      }
    }
    pair.device = { model: model, app: app, caps: caps, apps: apps }
    // hello 也是「刚听到这台手机」的凭证：手机 hello 之后紧接着就 poll，
    // 这段窗口不该算离线，否则配对后第一次调用会莫名 PHONE_OFFLINE。
    pair.lastSeen = now
    pair.lastPollAt = now
    try { this.persistPairs() } catch {}
    this.emit({ type: 'hello', pairId: pair.id, model: model, caps: caps.length })
    return { v: VERSION, kind: 'hello', ok: true, serverTime: now, pollTimeoutMs: POLL_HOLD_MS }
  }

  async handlePoll(pair, inner) {
    const now = this.now()
    // poll 就是心跳：先更新 lastSeen/lastPollAt，别让这条还在等指令的长连接被判离线。
    pair.lastSeen = now
    pair.lastPollAt = now
    // acks 只是「结果已回执」的提示：结果本身处理过就不会再派发，这里不据此重放任何东西。
    if (Array.isArray(inner.acks)) {
      const acks = inner.acks.filter((id) => typeof id === 'string' && /^[0-9a-f]{24}$/.test(id))
      if (acks.length) this.emit({ type: 'poll-acks', pairId: pair.id, count: acks.length })
    }
    // 一次只允许一个挂起的长轮询；旧的那个（多半是断网残留）立刻用空结果收尾，新的接管。
    if (pair.poll) this.finishPoll(pair, { v: VERSION, kind: 'poll', ok: true, cmds: [] })

    const ready = this.takeReady(pair)
    if (ready.length) return { v: VERSION, kind: 'poll', ok: true, cmds: ready }

    const payload = await new Promise((resolve) => {
      const waiter = { timer: null, settle: resolve }
      // 25s 到点就用空批收尾：手机上不需要「心跳包」，空 poll 本身就是心跳。
      waiter.timer = setTimeout(() => {
        if (pair.poll === waiter) {
          pair.poll = null
          resolve({ v: VERSION, kind: 'poll', ok: true, cmds: [] })
        }
      }, POLL_HOLD_MS)
      if (typeof waiter.timer.unref === 'function') waiter.timer.unref()
      pair.poll = waiter
    })
    // null 只来自 stop()：这条长轮询必须立刻结束，不能让手机等到 25s 超时。
    if (!payload) throw linkError('E_STOPPED', '手机常连已停止')
    return payload
  }

  handleResult(pair, inner) {
    const id = typeof inner.id === 'string' ? inner.id : ''
    if (!/^[0-9a-f]{24}$/.test(id)) throw linkError('INVALID_ARGS', 'result 缺少合法命令 id')
    if (typeof inner.ok !== 'boolean') throw linkError('INVALID_ARGS', 'result 缺少布尔 ok')
    const confirm = typeof inner.confirm === 'string' ? inner.confirm : 'n/a'
    if (!CONFIRMS.has(confirm)) throw linkError('INVALID_ARGS', 'result 的 confirm 取值不合法')

    const entry = this.pending.get(id)
    // 只认「本配对、已投递、还没结论」的命令：未知 / 过期 / 未投递 / 已执行的结果一律忽略。
    if (!entry || entry.pair !== pair || entry.state !== 'delivered') {
      return { v: VERSION, kind: 'result', ok: true, accepted: false }
    }

    let value = inner.result
    if (inner.ok && value === undefined) value = {}
    if (!inner.ok) {
      const error = isPlainObject(inner.error) ? inner.error : {}
      const code = typeof error.code === 'string' && /^[A-Z_]{2,32}$/.test(error.code) ? error.code : 'INTERNAL'
      const message = typeof error.message === 'string' ? error.message.slice(0, 300) : '手机执行失败'
      this.settleResult(entry, { ok: false, error: { code: code, message: message, confirm: confirm } })
      return { v: VERSION, kind: 'result', ok: true, accepted: true }
    }

    const outBytes = byteLength(value)
    if (outBytes > MAX_PAYLOAD) {
      this.settleResult(entry, {
        ok: false,
        error: { code: 'INTERNAL', message: '手机返回结果超过 64 KiB，已丢弃', confirm: confirm }
      })
      return { v: VERSION, kind: 'result', ok: true, accepted: true }
    }
    this.settleResult(entry, { ok: true, result: value, confirm: confirm })
    return { v: VERSION, kind: 'result', ok: true, accepted: true }
  }

  /** 结算一条命令；无论成功失败都给调用方一个确定结论。 */
  settleResult(entry, outcome) {
    this.pending.delete(entry.id)
    clearTimeout(entry.timer)
    if (entry.signal && entry.onAbort) {
      try { entry.signal.removeEventListener('abort', entry.onAbort) } catch {}
    }
    if (outcome.ok) {
      try { entry.resolve(outcome.result) } catch {}
      return
    }
    const err = linkError(outcome.error.code, outcome.error.message)
    err.confirm = outcome.error.confirm
    try { entry.reject(err) } catch {}
  }

  /** 持久删除成功后才销毁密钥；失败保留有效配对，绝不回成功确认。 */
  removePair(pair, reason) {
    if (!this.pairs.has(pair.id)) return { ok: true }
    const snapshot = new Map(this.pairs)
    this.pairs.delete(pair.id)
    try {
      this.persistPairs()
    } catch (err) {
      this.pairs = snapshot
      this.error = '解除配对失败，配对仍然有效：' + this.errorText(err)
      throw linkError('INTERNAL', this.error)
    }
    this.detachPair(pair, reason)
    return { ok: true }
  }

  /** 让一个已经不在 this.pairs 里的配对彻底失效：结束挂起、拒绝待办、零化密钥。 */
  detachPair(pair, reason) {
    this.finishPoll(pair, null)
    for (const entry of Array.from(this.pending.values())) {
      if (entry.pair !== pair) continue
      const delivered = entry.state === 'delivered'
      const base = reason === 'revoked' ? '配对已撤销' : '手机已解除配对'
      // 已投递的命令撤不回来：如实说明可能已执行，并留一条 late-result（与超时路径一致）。
      const message = delivered ? base + '（指令可能已执行，结果会被丢弃）' : base
      if (this.fail(entry, 'PHONE_OFFLINE', message) && delivered) this.recordLate(entry, 'cancelled')
    }
    pair.queue = []
    try { pair.key.fill(0) } catch {}
    this.emit({ type: 'pair-removed', pairId: pair.id, reason: reason })
  }

  /* ------------------------------------------------------------------ 内部：网络 */

  listen() {
    return new Promise((resolve, reject) => {
      const server = http.createServer(
        { requestTimeout: REQUEST_TIMEOUT_MS, headersTimeout: 15000, keepAliveTimeout: 1000 },
        (req, res) => { this.handle(req, res) }
      )
      this.wire(server)
      const onError = (err) => {
        server.removeListener('listening', onListening)
        server.on('error', () => {})
        try { server.close() } catch {}
        reject(err)
      }
      const onListening = () => {
        server.removeListener('error', onError)
        server.on('error', (err) => {
          this.error = '手机常连服务出错：' + this.errorText(err)
        })
        const addr = server.address()
        this.server = server
        this.boundPort = addr && typeof addr === 'object' ? addr.port : this.port
        resolve()
      }
      server.once('error', onError)
      server.once('listening', onListening)
      // 只靠对端地址判定私网（isAllowedPeer）；监听 0.0.0.0 才能让手机连进来。
      server.listen(this.port, '0.0.0.0')
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

  /**
   * 纯文本回包。endNow === false 时只写状态与正文、先不结束响应：超大包必须先把对端的
   * 请求体读干净再收尾，否则对端会先看到 RST（ECONNRESET/EPIPE）而不是这个状态码。
   */
  text(res, status, body, endNow) {
    if (!res || res.writableEnded || res.destroyed) return
    const buf = Buffer.from(body + '\n', 'utf8')
    try {
      res.writeHead(status, {
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': buf.length,
        'Cache-Control': 'no-store',
        'Connection': 'close'
      })
      if (endNow === false) res.write(buf)
      else res.end(buf)
    } catch {}
  }

  sendEnvelope(res, envelope) {
    if (!res || res.writableEnded || res.destroyed) return
    const body = Buffer.from(JSON.stringify(envelope), 'utf8')
    try {
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': body.length,
        'Cache-Control': 'no-store',
        'Connection': 'close'
      })
      res.end(body)
    } catch {}
  }

  async handle(req, res) {
    const epoch = this._epoch
    try {
      if (req.method !== 'POST') { this.text(res, 405, 'method not allowed'); return }

      let url
      try { url = new URL(req.url || '', 'http://ling-link.invalid') } catch {
        this.text(res, 400, 'bad request'); return
      }
      if (url.search || url.hash) { this.text(res, 400, 'bad request'); return }
      const kind = url.pathname.startsWith(BASE_PATH) ? url.pathname.slice(BASE_PATH.length) : ''
      if (!KINDS.includes(kind)) { this.text(res, 404, 'not found'); return }
      if (!isAllowedPeer(req.socket && req.socket.remoteAddress)) { this.text(res, 403, 'forbidden'); return }
      if (!this.server) { this.text(res, 503, 'not running'); return }

      // 声明的 Content-Length 已经超限就先回 413，但**不能回完就 return**：对端还在写，
      // 直接销毁连接会让它看到 RST（ECONNRESET/EPIPE）而不是状态码。统一交给 readBody：
      // 它先写 413、再把剩余字节读掉不缓存，最后由这里正常收尾。分块上传没有 Content-Length 也走同一条路。
      const declared = Number(req.headers['content-length'])
      let raw
      try {
        raw = await readBody(req, MAX_BODY, () => { this.text(res, 413, 'payload too large', false) }, declared)
      } catch (err) {
        if (res.headersSent) endResponse(res)
        else if (err && err.code !== 'E_ABORT') this.text(res, 400, 'bad request')
        return
      }
      // readBody 回 null = 已经写过 413（含超大包中途被迫断开）：收尾即可。
      if (raw === null) { endResponse(res); return }

      let envelope
      try { envelope = JSON.parse(raw.toString('utf8')) } catch { this.text(res, 400, 'bad envelope'); return }
      if (!isPlainObject(envelope) || envelope.v !== VERSION) { this.text(res, 400, 'bad envelope'); return }
      if (typeof envelope.pairId !== 'string' || !PAIR_ID_RE.test(envelope.pairId)) { this.text(res, 400, 'bad envelope'); return }
      const iv = decodeBase64(envelope.iv)
      const sealed = decodeBase64(envelope.data)
      if (!iv || iv.length !== 12 || !sealed || sealed.length < 17) { this.text(res, 400, 'bad envelope'); return }

      const pair = this.pairs.get(envelope.pairId)
      if (!pair) { this.text(res, 401, 'unauthorized'); return }
      const inner = openEnvelope(pair.key, iv, sealed)
      if (!inner) { this.text(res, 401, 'unauthorized'); return }

      // 内层结构先验，再动重放表：畸形包不该把 nonce 表吃掉。
      if (inner.v !== VERSION || inner.pairId !== pair.id) { this.text(res, 400, 'bad payload'); return }
      if (inner.kind !== kind) { this.text(res, 400, 'kind mismatch'); return }
      if (!Number.isInteger(inner.seq) || inner.seq <= 0 || inner.seq > Number.MAX_SAFE_INTEGER) {
        this.text(res, 400, 'bad seq'); return
      }
      if (!Number.isInteger(inner.at)) { this.text(res, 400, 'bad at'); return }
      const now = this.now()
      if (Math.abs(now - inner.at) > SKEW_MS) { this.text(res, 400, 'clock skew'); return }

      // 认证通过后再查重放：没有密钥的人无法靠灌垃圾把表撑满。
      const nonceKey = envelope.iv
      this.pruneNonces(now)
      if (this.nonces.has(nonceKey)) { this.text(res, 409, 'replayed'); return }
      if (this.nonces.size >= NONCE_MAX) { this.text(res, 409, 'nonce cache full'); return }
      this.nonces.set(nonceKey, now)
      try {
        this.persistNonces()
      } catch (err) {
        this.nonces.delete(nonceKey)
        this.error = '保存重放记录失败：' + this.errorText(err)
        this.text(res, 500, 'storage error')
        return
      }

      let payload
      if (kind === 'hello') {
        payload = this.handleHello(pair, inner)
      } else if (kind === 'poll') {
        payload = await this.handlePoll(pair, inner)
      } else if (kind === 'result') {
        payload = this.handleResult(pair, inner)
      } else {
        // 先加密 ack，再持久删除及销毁密钥；落盘失败绝不发送成功 ack。
        const ack = seal(pair.key, { v: VERSION, kind: 'unpair', ok: true, serverTime: now }, pair.id)
        this.removePair(pair, 'unpair')
        this.sendEnvelope(res, ack)
        return
      }

      // 等长轮询期间可能已经 stop / 重启：那就不回包（socket 也已经被拆掉）。
      if (epoch !== this._epoch || !this.server) { this.text(res, 503, 'not running'); return }
      if (!payload) { this.text(res, 503, 'not running'); return }
      this.sendEnvelope(res, seal(pair.key, payload, pair.id))
    } catch (err) {
      if (err && err.code === 'INVALID_ARGS') { this.text(res, 400, 'bad payload'); return }
      if (err && err.code === 'E_STOPPED') { this.text(res, 503, 'not running'); return }
      this.error = '手机常连处理请求失败：' + this.errorText(err)
      if (!res.headersSent) this.text(res, 500, 'error')
    } finally {
      // 长轮询被接管/停止时 pair.poll 已经清掉，这里只需要保证没有残留的挂起项。
      if (epoch !== this._epoch && res && !res.writableEnded) {
        try { res.destroy() } catch {}
      }
    }
  }

  /* ------------------------------------------------------------------ 配对 URI */

  urisFor(pair) {
    const port = this.server ? this.boundPort : this.port
    const key = pair.key.toString('base64url')
    let list = privateAddresses().map((item) => 'ling-link://' + item.address + ':' + port + '/' + pair.id + '.' + key)
    if (!list.length) list = ['ling-link://127.0.0.1:' + port + '/' + pair.id + '.' + key]
    return list
  }

  /* ------------------------------------------------------------------ 持久化 */

  pairsFile() { return path.join(this.dir, PAIRS_FILE) }
  noncesFile() { return path.join(this.dir, NONCES_FILE) }

  ensureDir() {
    try { fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 }) } catch (err) {
      if (!err || err.code !== 'EEXIST') throw err
    }
  }

  loadPairs() {
    if (this._loaded) return
    this._loaded = true
    let raw
    try {
      raw = fs.readFileSync(this.pairsFile(), 'utf8')
    } catch (err) {
      if (err && err.code === 'ENOENT') return
      this._loadError = new Error('读取配对文件失败：' + this.errorText(err))
      return
    }
    let doc
    try { doc = JSON.parse(raw) } catch {
      this._loadError = new Error('配对文件损坏（' + PAIRS_FILE + '），为避免误删密钥，手机常连不会启动；请备份后重新配对')
      return
    }
    if (!isPlainObject(doc) || doc.v !== VERSION || !Array.isArray(doc.pairs)) {
      this._loadError = new Error('配对文件格式不对（' + PAIRS_FILE + '），手机常连不会启动')
      return
    }
    // 逐条严格校验：任何一条读不出来都算「文件坏了」整体拒绝。
    // 以前这里 continue 跳过坏条目，之后任一次 persistPairs（hello / createPair / revoke）
    // 就会把那条配对从磁盘上永久删掉——用户丢了一台手机的密钥，却得不到任何提示。
    const loaded = new Map()
    for (const item of doc.pairs) {
      if (!isPlainObject(item) || typeof item.id !== 'string' || !PAIR_ID_RE.test(item.id)) {
        this._loadError = new Error('配对文件里有无法解析的条目（' + PAIRS_FILE + '），为避免误删密钥，' +
          '手机常连不会启动；请备份后重新配对')
        return
      }
      const key = decodeKeyText(typeof item.key === 'string' ? item.key : '')
      if (!key || key.length !== 32) {
        this._loadError = new Error('配对文件里有密钥不完整的条目（' + PAIRS_FILE + '），为避免误删密钥，' +
          '手机常连不会启动；请备份后重新配对')
        return
      }
      if (loaded.has(item.id)) {
        this._loadError = new Error('配对文件里有重复的配对 id（' + PAIRS_FILE + '），为避免误删密钥，' +
          '手机常连不会启动；请备份后重新配对')
        return
      }
      loaded.set(item.id, {
        id: item.id,
        name: typeof item.name === 'string' && item.name ? item.name.slice(0, NAME_MAX) : '电脑',
        key: key,
        createdAt: Number.isFinite(item.createdAt) ? item.createdAt : 0,
        lastSeen: 0,
        lastPollAt: 0,
        device: isPlainObject(item.device) ? item.device : null,
        queue: [],
        poll: null
      })
    }
    // 全部条目都合法才替换内存表：报错时绝不留下「读到一半」的状态被写回磁盘。
    this.pairs = loaded
  }

  persistPairs() {
    this.ensureDir()
    const pairs = Array.from(this.pairs.values()).map((pair) => ({
      id: pair.id,
      name: pair.name,
      key: pair.key.toString('base64url'),
      createdAt: pair.createdAt,
      device: pair.device || null
    }))
    writeJsonAtomic(this.pairsFile(), { v: VERSION, pairs: pairs })
  }

  /**
   * 读重放表。**失败必须 fail closed**：读不出来 / 解析不了就意味着最近 180s 内用过的 IV
   * 已经丢了，继续服务等于允许重放，所以这里记一个致命错误，由 startOnce 拒绝启动。
   * 只有「文件不存在」（第一次跑）算正常的空表。
   */
  loadNonces() {
    this.nonces = new Map()
    this._nonceError = null
    this._noncesLoaded = false
    let raw
    try {
      raw = fs.readFileSync(this.noncesFile(), 'utf8')
    } catch (err) {
      // 文件不存在 = 第一次跑，空表是**真相**，可以正常回写。
      if (err && err.code === 'ENOENT') {
        this._noncesLoaded = true
        return
      }
      this._nonceError = new Error('读取重放记录失败（' + NONCES_FILE + '）：' + this.errorText(err) +
        '。为防重放，手机常连不会启动；请检查磁盘或删除该文件后重新配对')
      return
    }
    let doc
    try {
      doc = JSON.parse(raw)
    } catch {
      this._nonceError = new Error('重放记录损坏（' + NONCES_FILE + '），为防重放手机常连不会启动；' +
        '确认没有别的实例在跑之后，删除该文件即可（只会丢掉 180s 的重放记录）')
      return
    }
    if (!isPlainObject(doc) || !Array.isArray(doc.entries)) {
      this._nonceError = new Error('重放记录格式不对（' + NONCES_FILE + '），手机常连不会启动')
      return
    }
    const now = this.now()
    for (const item of doc.entries) {
      if (!Array.isArray(item) || item.length !== 2) continue
      const iv = item[0]
      const at = item[1]
      if (typeof iv !== 'string' || !decodeBase64(iv)) continue
      if (!Number.isFinite(at)) continue
      if (now - at > NONCE_TTL_MS) continue
      if (this.nonces.size >= NONCE_MAX) break
      this.nonces.set(iv, at)
    }
    this._noncesLoaded = true
  }

  persistNonces() {
    this.ensureDir()
    const entries = Array.from(this.nonces.entries()).map(([iv, at]) => [iv, at])
    writeJsonAtomic(this.noncesFile(), { v: VERSION, entries: entries })
  }

  /** 只保留 180s 内的记录；过期即删，绝不为了腾位置丢新的。 */
  pruneNonces(now) {
    for (const [iv, at] of this.nonces) {
      if (now - at > NONCE_TTL_MS) this.nonces.delete(iv)
    }
  }

  errorText(err) {
    return String((err && err.message) || err || '未知错误').replace(/^ling-link:\s*/, '')
  }
}

module.exports = {
  createLink: (options) => new LingLink(options),
  LingLink,
  parseUri,
  seal,
  openEnvelope,
  constants: {
    VERSION,
    BASE_PATH,
    KINDS,
    MAX_BODY,
    MAX_PAYLOAD,
    POLL_HOLD_MS,
    OFFLINE_MS,
    SKEW_MS,
    NONCE_TTL_MS,
    NONCE_MAX,
    QUEUE_MAX,
    CALL_TIMEOUT_MS,
    CALL_TIMEOUT_L3_MS,
    DEFAULT_PORT,
    AAD: AAD.toString('utf8')
  }
}
