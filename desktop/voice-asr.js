/* voice-asr.js — 桌面实时转写的协议层（豆包 SAUC / 阿里云 paraformer-realtime-v2）。
 *
 * 这一层只做三件事：
 *   1) 按各家的线上格式打包 / 拆包（豆包 gzip + 4 字节头；阿里是 JSON 控制帧 + 裸 PCM）；
 *   2) 把服务器事件归一成 onEvent({ type: 'state' | 'transcript' | ... })；
 *   3) 会话生命周期：连接 / 就绪 / 转写 / 收尾 / 终态，迟到的回调按 generation 丢弃，终态只发一次。
 * 不碰 Electron、IPC、磁盘、麦克风 —— 那些在 voice-main.js（主进程）和 voice.html（页面）里。
 *
 * 线上格式的实测结论见 2026-10-26 的验证记录：豆包首帧 `11 10 11 00` + 四字节大端长度 + gzip(JSON)，
 * 音频帧 `11 20 11 00`，最后一帧 `11 22 11 00`（flags=2 = last），服务端 header 大小 = (byte0 & 15) * 4，
 * type = byte1 >> 4，flags = byte1 & 15，byte2 & 15 == 1 表示 gzip。flag&1 时先读一个有符号序号。
 * 阿里必须先收到 task-started 才开始发裸 PCM，收尾发 finish-task 并等到 task-finished。
 */
'use strict'

const zlib = require('node:zlib')
const crypto = require('node:crypto')
const WebSocket = require('ws')

const SAMPLE_RATE = 16000          // 两家都固定 16k；采集端负责重采样到这里
const FRAME_SAMPLES = 3200         // 200ms
const FRAME_BYTES = FRAME_SAMPLES * 2   // 6400 字节（IPC 与发送帧的上限）
const MAX_SOCKET_BUFFERED = 1 << 20     // 1MB：超过就丢帧，绝不排队等（采集在实时流，等就是无限内存）
const DRAIN_TIMEOUT_MS = 8000      // 「停止」后等最终结果的硬上限，到点就如实报错
const HANDSHAKE_TIMEOUT_MS = 8000   // 只管 HTTP 升级那一段（ws 的 handshakeTimeout）
// 「socket 通了但服务端一直不回话」的上限。豆包要等第一条合法 ack（无音频也会先回 init result），
// 阿里要等 task-started；到点就报协议失败，绝不让界面一直假 live 或把音频丢进黑洞。
const INIT_TIMEOUT_MS = 8000
const OPEN = 1                     // ws.OPEN：写常量，测试替身不用对齐枚举
/* 句子身份表的有界窗口：和页面（voice.html MAX_SEGMENTS=200）同一套语义 —— 只留最近这些句，
 * 挤出窗口的句子把水位抬起来，之后同起点 / 更早的旧句（全量模式会重发历史）不再插回来。 */
const MAX_UTTERANCES = 400

const PROVIDERS = {
  doubao: {
    id: 'doubao',
    label: '豆包（火山引擎）',
    url: 'wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async',
    resourceId: 'volc.seedasr.sauc.duration',
    model: 'bigmodel',
    keyHint: '新版控制台的 API Key（填在 X-Api-Key；不需要 App ID / Access Token）'
  },
  alibaba: {
    id: 'alibaba',
    label: '阿里云（Paraformer 实时）',
    url: 'wss://dashscope.aliyuncs.com/api-ws/v1/inference',
    model: 'paraformer-realtime-v2',
    keyHint: '百炼 / DashScope 的 API Key（北京地域；Bearer 鉴权）'
  }
}
const PROVIDER_IDS = ['doubao', 'alibaba']

/* ---------------- 通用校验：IPC 边界与测试共用 ---------------- */

/**
 * PCM 帧校验：只收 16k 单声道 s16le 的整字节（偶数长度），单帧 ≤ 6400 字节。
 * 返回 { ok: true, bytes: Buffer } 或 { ok: false, error }。不抛。
 */
function validatePcmFrame(value) {
  let view = null
  if (Buffer.isBuffer(value)) view = value
  else if (value instanceof ArrayBuffer) view = new Uint8Array(value)
  else if (ArrayBuffer.isView(value)) view = new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
  if (!view) return { ok: false, error: '音频数据格式不对' }
  if (!view.byteLength) return { ok: false, error: '收到空的音频帧' }
  if (view.byteLength > FRAME_BYTES) return { ok: false, error: '音频帧太大（上限 ' + FRAME_BYTES + ' 字节）' }
  if (view.byteLength % 2) return { ok: false, error: '音频帧不是 16 位对齐的整数个采样' }
  const bytes = Buffer.isBuffer(view) ? view : Buffer.from(view)
  return { ok: true, bytes: bytes }
}

/**
 * 令牌桶：正常是每 200ms 一帧（5 帧/秒），这里给 25 帧/秒的余量。
 * 容量 40 让页面刚连上时能补发一小段积压，之后多出来的帧一律丢（丢多少计数上报，不排队）。
 */
function createRateGate(opts) {
  const capacity = (opts && opts.capacity) || 40
  const perSecond = (opts && opts.perSecond) || 25
  const now = (opts && opts.now) || Date.now
  let tokens = capacity
  let last = now()
  return {
    take() {
      const t = now()
      const dt = Math.max(0, t - last) / 1000
      last = t
      tokens = Math.min(capacity, tokens + dt * perSecond)
      if (tokens < 1) return false
      tokens -= 1
      return true
    },
    level() { return tokens },
    // 只给测试用：直接看当前容量
    capacity: capacity
  }
}

/* ---------------- 豆包（火山 SAUC）---------------- */

const DOUBAO_HEADER_SIZE = 4
const DOU_MSG_FULL_REQUEST = 0x01
const DOU_MSG_AUDIO = 0x02
const DOU_MSG_ERROR = 0x0f
const DOU_FLAG_SEQ = 0x01
const DOU_FLAG_LAST = 0x02

function doubaoHeader(type, flags) {
  const b = Buffer.alloc(DOUBAO_HEADER_SIZE)
  b[0] = 0x11              // protocol version 1 + header size 1（×4 = 4 字节）
  b[1] = ((type & 0x0f) << 4) | (flags & 0x0f)
  b[2] = 0x11              // serialization JSON(1) + compression gzip(1)
  b[3] = 0x00
  return b
}

function doubaoPacket(type, flags, payload) {
  const body = zlib.gzipSync(payload && payload.length ? payload : Buffer.alloc(0))
  const len = Buffer.alloc(4)
  len.writeUInt32BE(body.length, 0)
  return Buffer.concat([doubaoHeader(type, flags), len, body])
}

/** 首帧的完整请求 JSON（字段按实测通过的组合）。 */
function doubaoRequestJson() {
  return {
    user: { uid: 'ling-desktop' },
    audio: { format: 'pcm', rate: SAMPLE_RATE, bits: 16, channel: 1 },
    request: {
      model_name: PROVIDERS.doubao.model,
      show_utterances: true,
      enable_nonstream: true,
      result_type: 'full'
    }
  }
}

function doubaoFullRequest() {
  return doubaoPacket(DOU_MSG_FULL_REQUEST, 0, Buffer.from(JSON.stringify(doubaoRequestJson()), 'utf8'))
}

function doubaoAudioFrame(pcm, last) {
  return doubaoPacket(DOU_MSG_AUDIO, last ? DOU_FLAG_LAST : 0, pcm)
}

/**
 * 拆一帧服务端响应。返回
 *   { kind:'error', code, message } 或
 *   { kind:'result', type, flags, sequence, last, json, text }
 * 帧不完整 / 解压失败会抛 —— 调用方按「这一帧坏了」处理，不整条会话崩。
 */
function parseDoubaoPacket(raw) {
  const b = Buffer.isBuffer(raw) ? raw : Buffer.from(raw)
  if (b.length < DOUBAO_HEADER_SIZE) throw new Error('响应短于 4 字节的头')
  const headerBytes = (b[0] & 0x0f) * 4
  if (headerBytes < DOUBAO_HEADER_SIZE || b.length < headerBytes) throw new Error('响应头长度非法')
  const type = b[1] >> 4
  const flags = b[1] & 0x0f
  const compression = b[2] & 0x0f
  let off = headerBytes
  if (type === DOU_MSG_ERROR) {
    if (b.length < off + 8) throw new Error('错误帧缺 code / 长度')
    const code = b.readUInt32BE(off); off += 4
    const len = b.readUInt32BE(off); off += 4
    if (b.length < off + len) throw new Error('错误帧载荷不完整')
    return { kind: 'error', code: code, message: b.slice(off, off + len).toString('utf8') }
  }
  let sequence = null
  if (flags & DOU_FLAG_SEQ) {
    if (b.length < off + 4) throw new Error('响应缺序号')
    sequence = b.readInt32BE(off); off += 4
  }
  if (b.length < off + 4) throw new Error('响应缺载荷长度')
  const len = b.readUInt32BE(off); off += 4
  if (b.length < off + len) throw new Error('响应载荷不完整')
  let payload = b.slice(off, off + len)
  if (compression === 1 && payload.length) {
    try { payload = zlib.gunzipSync(payload) } catch { throw new Error('响应解压失败') }
  }
  let json = null
  const text = payload.toString('utf8')
  if (text) { try { json = JSON.parse(text) } catch { json = null } }
  return { kind: 'result', type: type, flags: flags, sequence: sequence, last: !!(flags & DOU_FLAG_LAST), json: json, text: text }
}

function numOr(v, dflt) { const n = Number(v); return Number.isFinite(n) ? n : dflt }

/**
 * 一句的起点：优先 authoritative 的 start_time；没有才从词级起点推（derived）。
 * 返回 { startMs, derived } —— 调用方必须区分这两者：direct 是稳定身份，
 * derived 会随首词修订而漂移，只能拿去和「唯一未定稿那句」的区间比，不能当 key。
 */
function utteranceStart(u) {
  if (!u) return { startMs: 0, derived: true }
  const direct = Number(u.start_time)
  if (Number.isFinite(direct)) return { startMs: direct, derived: false }
  const words = Array.isArray(u.words) ? u.words : []
  for (const w of words) {
    const s = Number(w && w.start_time)
    if (Number.isFinite(s)) return { startMs: s, derived: true }
  }
  return { startMs: 0, derived: true }
}

/**
 * 把豆包的结果 JSON 归一成 transcript 事件。
 *
 * 身份规则（v1.75 实测修复）：
 *   - `start_time` 直给 = **稳定身份**（derived:false），按它 upsert，partial/final 同身份替换。
 *   - 只给 words[0].start_time = **derived**（derived:true）。首词会随修订变化，这个数不稳定，
 *     所以它不能当 key：交给会话层去和「唯一那句未定稿」的真实区间比对。
 *   - 两者都没有 = 无身份，同样按 derived 处理。
 * 一次响应里已经有 utterances 时绝不再拿 result.text 兜底拼一遍（否则同一段话会重复一份）。
 */
function doubaoTranscriptEvents(json) {
  const result = json && (json.result || json.Result)
  if (!result) return []
  const out = []
  const list = Array.isArray(result.utterances) ? result.utterances : null
  if (list) {
    for (const u of list) {
      if (!u || typeof u.text !== 'string' || !u.text) continue
      const st = utteranceStart(u)
      out.push({
        type: 'transcript',
        startMs: st.startMs,
        derived: st.derived,
        endMs: Math.max(st.startMs, numOr(u.end_time, st.startMs)),
        words: Array.isArray(u.words) ? u.words.length : 0,
        text: u.text,
        isFinal: u.definite === true
      })
    }
    return out   // result_type=full 时 utterances 就是全部句子，result.text 只是它们的拼接
  }
  if (typeof result.text === 'string' && result.text.trim()) {
    out.push({ type: 'transcript', startMs: 0, derived: true, endMs: 0, words: 0, text: result.text, isFinal: false })
  }
  return out
}

/* ---------------- 阿里云 paraformer-realtime-v2 ---------------- */

function alibabaRunTask(taskId) {
  return JSON.stringify({
    header: { action: 'run-task', task_id: taskId, streaming: 'duplex' },
    payload: {
      task_group: 'audio',
      task: 'asr',
      function: 'recognition',
      model: PROVIDERS.alibaba.model,
      parameters: { format: 'pcm', sample_rate: SAMPLE_RATE },
      input: {}
    }
  })
}

function alibabaFinishTask(taskId) {
  return JSON.stringify({ header: { action: 'finish-task', task_id: taskId, streaming: 'duplex' }, payload: { input: {} } })
}

/**
 * 服务端控制帧 → 归一事件（started / transcript / done / error / ignored）。
 * 返回 [] **只表示这一帧不合法**（坏 JSON / 认不出的事件 / 缺 header），调用方按协议失败处理。
 * 合法但暂时没内容的帧（result-generated 里没有 sentence、心跳句）回 [{type:'ignored'}]：
 * 阿里会持续发这种帧维持连接，绝不能当致命错误。
 */
function alibabaEvents(text) {
  let msg = null
  try { msg = JSON.parse(String(text == null ? '' : text)) } catch { return [] }
  if (!msg || typeof msg !== 'object' || !msg.header) return []
  const event = String(msg.header.event || '')
  if (event === 'task-started') return [{ type: 'started' }]
  if (event === 'task-finished') return [{ type: 'done' }]
  if (event === 'task-failed') {
    const code = msg.header.error_code ? String(msg.header.error_code) + ' ' : ''
    const detail = String(msg.header.error_message || (msg.payload && msg.payload.message) || '未知错误')
    return [{ type: 'error', message: code + detail }]
  }
  if (event === 'result-generated') {
    const sent = msg.payload && msg.payload.output && msg.payload.output.sentence
    if (!sent || typeof sent.text !== 'string' || sent.heartbeat === true) return [{ type: 'ignored' }]
    const start = numOr(sent.begin_time, 0)
    const end = Math.max(start, numOr(sent.end_time, start))
    return [{ type: 'transcript', startMs: start, derived: false, endMs: end, text: sent.text, isFinal: sent.sentence_end === true }]
  }
  return []      // 未知事件名：协议失败
}

/* ---------------- 会话 ---------------- */

const ST = {
  IDLE: 'idle',
  CONNECTING: 'connecting',
  READY: 'ready',
  STREAMING: 'streaming',
  DRAINING: 'draining',
  FINISHED: 'finished',
  CANCELLED: 'cancelled',
  ERROR: 'error'
}

function errText(e) {
  if (!e) return '未知错误'
  const m = e.message || String(e)
  return m.length > 200 ? m.slice(0, 200) + '…' : m
}

/**
 * 一次转写会话。音频一律**先存一帧**再发：新帧到来时把手上那帧发出去，
 * 于是「停止」时手上正好还留着最后一帧 —— 用 last 标志发它即可，既不丢尾巴也不重复。
 *
 * onEvent 只会收到（每个会话、每种终态最多一次）：
 *   { type:'state', state, message?, stats, provider }
 *   { type:'transcript', startMs, endMs, text, isFinal }
 */
class VoiceAsrSession {
  constructor(opts) {
    const o = opts || {}
    if (!PROVIDER_IDS.includes(o.provider)) throw new Error('未知的转写服务：' + String(o.provider))
    if (!o.apiKey) throw new Error('缺少 API Key')
    this.provider = o.provider
    this.apiKey = String(o.apiKey)
    this.onEvent = typeof o.onEvent === 'function' ? o.onEvent : function () {}
    this.WebSocketImpl = o.WebSocketImpl || WebSocket
    this.taskId = o.taskId || crypto.randomUUID()
    this.requestId = o.requestId || crypto.randomUUID()
    this.drainTimeoutMs = Number(o.drainTimeoutMs) > 0 ? Number(o.drainTimeoutMs) : DRAIN_TIMEOUT_MS
    this.handshakeTimeoutMs = Number(o.handshakeTimeoutMs) > 0 ? Number(o.handshakeTimeoutMs) : HANDSHAKE_TIMEOUT_MS
    // 「连上了但服务端一直不回话」也要有上限：8 秒的 ws 握手超时只管 HTTP 升级那一段，
    // socket 建好之后（阿里还没 task-started / 豆包还没第一条 ack）必须另有计时，否则界面会一直假 live。
    this.initTimeoutMs = Number(o.initTimeoutMs) > 0 ? Number(o.initTimeoutMs) : INIT_TIMEOUT_MS

    this.gen = 0
    this.state = ST.IDLE
    this.terminal = false
    this.firstFrameSent = false
    this.pending = null            // 手上留着的那一帧
    this.ws = null
    this.drainTimer = null
    this.initTimer = null
    /* 句子身份表（v1.75 实测修复，v1.76 补「定稿后起点再漂」）：
     *   utterances 是「全量累计」的，每包重发所有句子。start_time 直给的句子本来用它自己当 key（稳定）；
     *   但起点确实会漂：只给 words[0].start_time 的 derived 起点随首词修订漂（700→640），
     *   连 direct 也会在定稿**之后**被服务端改成更准的值（真实 IPC：final 260 → 停止尾包 final 80，
     *   end/text 完全不变），所以身份不能只看本次起点，要先认**既有那句的真实区间**。
     *   绝不按固定毫秒近邻乱并（会吞掉相邻短句），也绝不按文字去重（会吞掉真实重复的话语）。
     * 表和页面同语义**有界**：只留最近 MAX_UTTERANCES 句，挤出去的句子抬高水位，历史全量重发不再插回来
     * —— 长听写不会让这次会话的身份表无界长。
     */
    this.utterances = new Map()      // startMs -> { startMs, endMs, text, isFinal, derived }
    this.prunedBeforeMs = -1         // 已挤出窗口的句子上界（和页面的 prunedBeforeMs 一样，防历史复活）
    this.stats = { frames: 0, bytes: 0, dropped: 0, bad: 0 }
  }

  /**
   * 只认「唯一那句未定稿」：utterances 是累计全量，最多只有最后一句还没 definite。
   * 返回它的记录（没有则 null）。起点漂移的匹配先跟它比，定稿后的重发另按同结束+重叠认。
   */
  _pendingUtterance() {
    for (const rec of this.utterances.values()) if (!rec.isFinal) return rec
    return null
  }

  /**
   * 这条事件说的是不是「已有那句」？返回既有记录，没有则 null。
   *
   * 顺序（先 pending 再定稿，因为 pending 才是这句的当前身份）：
   *   ① 唯一那句未定稿：真实区间重叠 / pending 起点落在新句区间内 / 起点一致 → 同一句继续改写。
   *      derived partial 升级成 direct final 也走这里：起点换了不会多一条。
   *   ② 起点已有记录 → 就是它（direct 起点是稳定身份）。
   *   ③ 已定稿的句子：**结束时间完全相同且真实区间重叠** → 同句起点被修订后重发
   *      （真实 IPC：final 260,end 6472 → 停止尾包 final 80,end 6472，文字一模一样）。
   *      同一轮累计时间轴里结束时间相同又重叠只可能是同一句；时间不相交的重复话语结束时间必然不同，仍是两句。
   * 没有任何毫秒近邻阈值，也不看文字：不靠「±N 毫秒」也不靠文本相似来猜身份。
   */
  _matchUtterance(ev, end) {
    const keyOf = (ms) => String(ms)
    const sameStart = this.utterances.get(keyOf(ev.startMs))
    const pending = this._pendingUtterance()
    if (pending) {
      const pendStart = pending.startMs
      const pendEnd = Math.max(pendStart, pending.endMs)
      if ((pendStart < end && ev.startMs < pendEnd)      // 真实区间重叠
        || (ev.startMs <= pendStart && pendStart < end)  // pending 起点落在新句区间内（起点左漂 / pending 还没 end）
        || ev.startMs === pendStart) return pending      // 同一起点继续改写
    }
    if (sameStart) return sameStart
    // 已定稿：同结束 + 真实重叠。只读一次线性比较 —— 记录数与本次会话说过的句子数同量级
    // （和页面窗口一样是几百条），比另养一套「结束时间索引 + 淘汰」状态更不容易错。
    let matched = null
    for (const rec of this.utterances.values()) {
      if (!rec.isFinal || rec.endMs !== end) continue
      if (ev.startMs === rec.startMs || (rec.startMs < end && ev.startMs < rec.endMs)) matched = rec
    }
    return matched
  }

  /**
   * 把一条归一事件落到身份表上，返回应该发给页面的 { startMs, endMs, text, isFinal }。
   *
   * 规则：
   *   1) 先问 _matchUtterance：是已有那句就**沿用它的 key**（页面按 key 替换，不会多一条）。
   *   2) 匹配不上才是新句子：用本次起点当 key（区间已交叠的既有记录都会先被 1) 认领，这里不会撞）。
   *   3) 已定稿的句子永不被后续 partial 覆写；内容完全没变的重复重发不再推事件。
   *   4) 窗口有界：超出 MAX_UTTERANCES 就丢最早那句并抬高水位；水位以下的句子不再插回来
   *      （和页面 pruneSegments / prunedBeforeMs 同一套语义）。
   */
  _resolveUtterance(ev) {
    const keyOf = (ms) => String(ms)
    const end = Math.max(ev.startMs, Number(ev.endMs) || ev.startMs)
    const prev = this._matchUtterance(ev, end)
    if (prev) {
      if (prev.isFinal && !ev.isFinal) return null     // 已定稿的不回退成 partial
      const recEnd = Math.max(prev.startMs, end)
      // 全量重发里没变的句子不再推一遍（否则页面被同一句反复刷）
      if (prev.text === ev.text && prev.isFinal === !!ev.isFinal && prev.endMs === recEnd) return null
      const rec = { startMs: prev.startMs, endMs: recEnd, text: ev.text, isFinal: !!ev.isFinal, derived: prev.derived && !!ev.derived }
      this.utterances.set(keyOf(rec.startMs), rec)
      return { startMs: rec.startMs, endMs: rec.endMs, text: rec.text, isFinal: rec.isFinal }
    }
    // 已挤出窗口的旧句（全量模式会重发历史）不再插回身份表，也不推给页面
    if (ev.startMs <= this.prunedBeforeMs) return null
    const rec = { startMs: ev.startMs, endMs: end, text: ev.text, isFinal: !!ev.isFinal, derived: !!ev.derived }
    this.utterances.set(keyOf(rec.startMs), rec)
    this._pruneUtterances()
    return { startMs: rec.startMs, endMs: rec.endMs, text: rec.text, isFinal: rec.isFinal }
  }

  /**
   * 只留最近 MAX_UTTERANCES 句：按起点丢最早的，被丢的最大起点抬成水位。
   * 未定稿那句（最多一句，就是最后一句）永远留着 —— 起点漂移要跟它比。
   */
  _pruneUtterances() {
    while (this.utterances.size > MAX_UTTERANCES) {
      let oldestKey = null
      let oldest = Infinity
      for (const [k, rec] of this.utterances) {
        if (!rec.isFinal) continue
        if (rec.startMs < oldest) { oldest = rec.startMs; oldestKey = k }
      }
      if (!oldestKey) break
      this.utterances.delete(oldestKey)
      if (oldest > this.prunedBeforeMs) this.prunedBeforeMs = oldest
    }
  }

  /**
   * 一包豆包结果里的全部句子 → 事件序列。只发**本包真正更新过**的句子：
   * 全量重发里没变的老句子不再重复推送，页面就不会被同一句反复刷。
   */
  _reduceDoubao(json) {
    const evs = doubaoTranscriptEvents(json)
    for (const ev of evs) {
      const out = this._resolveUtterance(ev)
      if (!out) continue
      this._emit(Object.assign({ type: 'transcript' }, out))
    }
  }

  _emit(ev) {
    if (this.terminal) return
    try { this.onEvent(ev) } catch {}
  }

  _state(state, message) {
    this.state = state
    this._emit({ type: 'state', state: state, message: message || '', provider: this.provider, stats: this._stats() })
    // 握手期间（阿里还要等 task-started）攒下的那一帧，一就绪立刻补发，开头不丢。
    // 只认 READY：STREAMING 是由「刚发出去一帧」触发的，在那里补发会把手上那帧提前送走。
    if (state === ST.READY) this._flushPending()
  }

  _stats() { return { frames: this.stats.frames, bytes: this.stats.bytes, dropped: this.stats.dropped, bad: this.stats.bad } }

  /** 进入初始化窗口：socket 已通但要等服务端第一句话。到点没等到就是协议失败。 */
  _armInitWatch() {
    this._clearInitWatch()
    this.initTimer = setTimeout(() => {
      this.initTimer = null
      if (this.terminal || this.state !== ST.CONNECTING) return
      this._terminal(ST.ERROR, this.provider === 'alibaba'
        ? '连接建好了但服务器 ' + Math.round(this.initTimeoutMs / 1000) + ' 秒内没回 task-started（协议失败）'
        : '连接建好了但服务器 ' + Math.round(this.initTimeoutMs / 1000) + ' 秒内没回识别确认（协议失败）')
    }, this.initTimeoutMs)
    if (this.initTimer && typeof this.initTimer.unref === 'function') this.initTimer.unref()
  }

  _clearInitWatch() {
    if (!this.initTimer) return
    clearTimeout(this.initTimer)
    this.initTimer = null
  }

  /**
   * 服务端的「我准备好了」：豆包的第一条合法 ack、阿里的 task-started。
   * 只有它能把 CONNECTING 推到 READY —— 单纯 socket open 不算就绪，
   * 否则页面会在还没被确认的情况下开麦，第一段音频直接丢。
   */
  _ackReady() {
    if (this.terminal) return
    this._clearInitWatch()
    if (this.state === ST.CONNECTING) this._state(ST.READY)
  }

  /** 收到解不开 / 结构不对的帧：不能只计数吞掉，否则会永远停在假 live。 */
  _protocolFailure(why) {
    this.stats.bad++
    this._terminal(ST.ERROR, '协议错误：' + why)
  }

  /** 把手上那帧发出去（就绪后补发 / 新帧到来时推进一格）。发不出去只计丢帧，不排队。 */
  _flushPending() {
    const prev = this.pending
    if (!prev) return true
    this.pending = null
    if (this._writeAudio(prev, false)) return true
    this.pending = prev
    return false
  }

  /**
   * start() 的放行闸：握手成功或终态各放行一次，重复调用无副作用。
   * 必须能放行终态 —— 否则握手阶段 cancel / 鉴权失败时调用方会一直挂着。
   */
  _settleStart(result) {
    const fn = this._startSettled
    if (!fn) return
    this._startSettled = null
    fn(result)
  }

  /** 终态：只发一次，顺手清定时器、收掉 socket、放行还在等的 start()。 */
  _terminal(state, message) {
    if (this.terminal) return
    this.terminal = true
    clearTimeout(this.drainTimer)
    this.drainTimer = null
    this._clearInitWatch()
    this.pending = null
    const from = this.state
    this.state = state
    this._settleStart({ ok: false, state: state, error: message || '' })
    try {
      this.onEvent({ type: 'state', state: state, message: message || '', provider: this.provider, stats: this._stats(), from: from })
    } catch {}
    this._closeSocket()
  }

  _closeSocket() {
    const ws = this.ws
    this.ws = null
    this.gen++                       // 之后所有迟到的 socket 回调都认不出自己，直接丢
    if (!ws) return
    try { if (typeof ws.terminate === 'function') ws.terminate(); else ws.close() } catch {}
  }

  _headers() {
    if (this.provider === 'doubao') {
      return {
        'X-Api-Key': this.apiKey,
        'X-Api-Resource-Id': PROVIDERS.doubao.resourceId,
        'X-Api-Request-Id': this.requestId
      }
    }
    return { Authorization: 'Bearer ' + this.apiKey }
  }

  /** 建连接。resolve 只表示「传输层通了」，音频要等 state=ready（阿里还要等 task-started）。 */
  start() {
    if (this.state !== ST.IDLE) return Promise.resolve({ ok: false, error: '会话已经开始了' })
    const gen = ++this.gen
    this._state(ST.CONNECTING)
    let ws = null
    try {
      ws = new this.WebSocketImpl(PROVIDERS[this.provider].url, {
        headers: this._headers(),
        handshakeTimeout: this.handshakeTimeoutMs,
        perMessageDeflate: false,
        maxPayload: 4 << 20
      })
    } catch (e) {
      this._terminal(ST.ERROR, '连不上转写服务：' + errText(e))
      return Promise.resolve({ ok: false, error: '连不上转写服务：' + errText(e) })
    }
    this.ws = ws
    return new Promise((resolve) => {
      // 唯一的放行闸：握手成功时放行；终态（cancel / 鉴权失败 / 连不上）也会放行，
      // 否则握手阶段取消会让调用方一直挂在这个 promise 上。
      this._startSettled = resolve
      ws.on('open', () => {
        if (gen !== this.gen) return
        try {
          if (this.provider === 'doubao') {
            // 只发首帧请求；READY 要等服务器第一条合法 ack（无音频也会先回 init result）
            this.ws.send(doubaoFullRequest())
          } else {
            // 同理：READY 等 task-started
            this.ws.send(alibabaRunTask(this.taskId))
          }
        } catch (e) {
          this._terminal(ST.ERROR, '发送开始请求失败：' + errText(e))
          return
        }
        this._armInitWatch()
        this._settleStart({ ok: true, provider: this.provider })
      })
      ws.on('message', (data) => {
        if (gen !== this.gen) return
        try {
          this._onMessage(data)
        } catch (e) {
          // 解不开的帧不能只计数：那会让会话停在假 live，页面一直以为在转写。直接协议失败收尾。
          this._protocolFailure(errText(e))
        }
      })
      ws.on('unexpected-response', (_req, res) => {
        if (gen !== this.gen) return
        const code = res && res.statusCode
        // 只报状态码：响应头里的鉴权细节（可能回显 Key 片段）不往外带
        const hint = code === 401 || code === 403 ? '鉴权失败：Key 不对、没开通、或与所选服务商不匹配'
          : code === 404 ? '接口地址不对或该地域不支持'
            : code === 429 ? '请求被限流 / 额度用完'
              : '服务端拒绝了 WebSocket 握手'
        this._terminal(ST.ERROR, '连接被拒绝（HTTP ' + (code || '?') + '）：' + hint)
      })
      ws.on('error', (e) => {
        if (gen !== this.gen) return
        this._terminal(ST.ERROR, '连接出错：' + errText(e))
      })
      ws.on('close', (code, reason) => {
        if (gen !== this.gen) return
        if (this.terminal) return
        const why = reason ? String(reason).slice(0, 120) : ''
        this._terminal(ST.ERROR, '连接被服务器关闭（' + (code || '?') + '）' + (why ? '：' + why : ''))
      })
    })
  }

  _onMessage(data) {
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data)
    if (this.provider === 'doubao') this._onDoubao(buf)
    else this._onAlibaba(buf.toString('utf8'))
  }

  _onDoubao(buf) {
    let pkt
    try {
      pkt = parseDoubaoPacket(buf)
    } catch (e) {
      // 帧坏了（截断 / 长度对不上 / 解压失败）说明连接已不可信：报协议失败并收尾。
      // 只 bad++ 继续等会让页面一直显示「转写中」，实际一个结果都收不到。
      this._protocolFailure('服务器响应无法解析（' + errText(e) + '）')
      return
    }
    if (pkt.kind === 'error') {
      this._terminal(ST.ERROR, '服务器返回错误 ' + pkt.code + '：' + String(pkt.message || '').slice(0, 160))
      return
    }
    const json = pkt.json
    if (json && typeof json.code === 'number' && json.code !== 0) {
      this._terminal(ST.ERROR, '服务器返回错误 ' + json.code + '：' + String(json.message || '').slice(0, 160))
      return
    }
    // 结构不认识（既没有 result 也不是已知错误）= 协议失败，不猜、不假装在转写
    if (!(json && (json.result || json.Result))) {
      this._protocolFailure('服务器响应缺少 result 字段')
      return
    }
    // 第一条合法 ack（没有音频时也会先回一条 init result）才把会话推到 READY
    this._ackReady()
    this._reduceDoubao(json)
    if (pkt.last) this._terminal(ST.FINISHED)
  }

  _onAlibaba(text) {
    const evs = alibabaEvents(text)
    if (!evs.length) {
      // 只有「坏 JSON / 缺 header / 未知 event」才算协议失败；合法的空帧不在这里
      this._protocolFailure('服务器发来无法识别的控制帧')
      return
    }
    for (const ev of evs) {
      if (ev.type === 'started') {
        this._ackReady()
      } else if (ev.type === 'transcript') {
        const out = this._resolveUtterance(ev)
        if (out) this._emit(Object.assign({ type: 'transcript' }, out))
      } else if (ev.type === 'done') {
        this._terminal(ST.FINISHED)
        return
      } else if (ev.type === 'error') {
        this._terminal(ST.ERROR, '服务器返回错误：' + String(ev.message || '').slice(0, 160))
        return
      }
      // 'ignored'：合法的空帧 / 心跳句 —— 什么都不做，连接继续
    }
  }

  /** 音频只有「连接已就绪」之后才收；还没就绪就返回 false（页面自己决定要不要留一点积压）。 */
  _writable() {
    if (this.terminal || !this.ws) return false
    return this.state === ST.READY || this.state === ST.STREAMING
  }

  /** 供控制器判断「现在推音频会不会被收」：连接就绪（阿里还要等 task-started）之后才为真。 */
  get writable() { return this._writable() }

  _writeAudio(pcm, last) {
    if (!this._writable()) return false
    const ws = this.ws
    if (!ws || ws.readyState !== OPEN) return false
    if (ws.bufferedAmount > MAX_SOCKET_BUFFERED) return false
    const payload = this.provider === 'doubao' ? doubaoAudioFrame(pcm, last) : pcm
    try { ws.send(payload) } catch { return false }
    this.stats.frames++
    this.stats.bytes += pcm.length
    if (!this.firstFrameSent) {
      this.firstFrameSent = true
      if (!last) this._state(ST.STREAMING)   // 第一帧音频真的发出去了，才算「正在转写」
    }
    return true
  }

  /**
   * 收一帧 6400 字节以内的 PCM16。返回 false 表示会话当前不收（还没 ack / 已结束）。
   * 就绪前一律不收、也不攒：页面现在要等 ready 才开麦，所以「连接期积压」这条路径不该存在；
   * 真来了只丢一帧并计数，绝不排队。
   */
  sendPcm(pcm) {
    if (this.terminal || !this.ws) return false
    if (this.state !== ST.READY && this.state !== ST.STREAMING) {
      this.stats.dropped++
      return false
    }
    const view = Buffer.isBuffer(pcm) ? pcm : Buffer.from(pcm)
    if (!view.length) return false
    const prev = this.pending
    this.pending = view
    if (prev && !this._writeAudio(prev, false)) this.stats.dropped++
    return true
  }

  /** 优雅收尾：把手上的最后一帧带 last 标志发出去（阿里是先发完再 finish-task），然后等最终结果。 */
  finish() {
    if (this.terminal) return { ok: true, state: this.state }
    if (this.state === ST.IDLE) return { ok: true, state: ST.IDLE }
    if (this.state === ST.CONNECTING) { this.cancel(); return { ok: true, state: ST.CANCELLED } }
    if (this.state === ST.DRAINING) return { ok: true, state: ST.DRAINING }
    const tail = this.pending
    this.pending = null
    if (this.provider === 'doubao') {
      this._writeAudio(tail || Buffer.alloc(0), true)
    } else {
      if (tail) this._writeAudio(tail, false)
      try { if (this.ws && this.ws.readyState === OPEN) this.ws.send(alibabaFinishTask(this.taskId)) } catch {}
    }
    if (this.terminal) return { ok: true, state: this.state }
    this._state(ST.DRAINING)
    this.drainTimer = setTimeout(() => {
      this.drainTimer = null
      this._terminal(ST.ERROR, '服务器 ' + Math.round(this.drainTimeoutMs / 1000) + ' 秒内没给出最终结果（已停止等待，已收到的部分保留）')
    }, this.drainTimeoutMs)
    if (this.drainTimer && typeof this.drainTimer.unref === 'function') this.drainTimer.unref()
    return { ok: true, state: ST.DRAINING }
  }

  /** 立即停止：丢掉没发的那帧、掐掉 socket、终态 cancelled；之后再来的回调全部作废。 */
  cancel() {
    if (this.terminal) return { ok: true, state: this.state }
    this._terminal(ST.CANCELLED, '已停止（不等最终结果）')
    return { ok: true, state: ST.CANCELLED }
  }
}

module.exports = {
  PROVIDERS: PROVIDERS,
  PROVIDER_IDS: PROVIDER_IDS,
  STATES: ST,
  SAMPLE_RATE: SAMPLE_RATE,
  FRAME_SAMPLES: FRAME_SAMPLES,
  FRAME_BYTES: FRAME_BYTES,
  DRAIN_TIMEOUT_MS: DRAIN_TIMEOUT_MS,
  MAX_SOCKET_BUFFERED: MAX_SOCKET_BUFFERED,
  validatePcmFrame: validatePcmFrame,
  createRateGate: createRateGate,
  doubaoHeader: doubaoHeader,
  doubaoPacket: doubaoPacket,
  doubaoRequestJson: doubaoRequestJson,
  doubaoFullRequest: doubaoFullRequest,
  doubaoAudioFrame: doubaoAudioFrame,
  parseDoubaoPacket: parseDoubaoPacket,
  doubaoTranscriptEvents: doubaoTranscriptEvents,
  utteranceStart: utteranceStart,
  alibabaRunTask: alibabaRunTask,
  alibabaFinishTask: alibabaFinishTask,
  alibabaEvents: alibabaEvents,
  VoiceAsrSession: VoiceAsrSession
}
