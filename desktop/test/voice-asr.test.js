// voice-asr：实时转写的协议层与生命周期回归（无 Electron、无网络）。
// 只断言真实行为：帧格式、句子替换身份、终态只发一次、停止的收尾与竞态。
const test = require('node:test')
const assert = require('node:assert')
const zlib = require('node:zlib')

const V = require('../voice-asr')

/* ---------------- 测试替身：一条假 WebSocket ---------------- */

/** 极简 EventEmitter + 送出记录，模拟 ws 的 open / message / close / error 与 send。 */
function fakeSocketClass() {
  const made = []
  class FakeWS {
    constructor(url, opts) {
      this.url = url
      this.opts = opts || {}
      this.readyState = 0             // CONNECTING
      this.bufferedAmount = 0
      this.sent = []
      this.handlers = new Map()
      made.push(this)
    }
    on(ev, fn) { this.handlers.set(ev, (this.handlers.get(ev) || []).concat([fn])); return this }
    emit(ev, a, b) { for (const fn of this.handlers.get(ev) || []) fn(a, b) }
    send(data) {
      if (this.readyState !== 1) throw new Error('socket not open')
      this.sent.push(Buffer.isBuffer(data) ? Buffer.from(data) : Buffer.from(String(data)))
    }
    terminate() { this.readyState = 3; this.terminated = true }
    close() { this.readyState = 3 }
    // 测试里模拟「服务器已接受握手」
    open() { this.readyState = 1; this.emit('open') }
    receive(buf) { this.emit('message', buf) }
  }
  FakeWS.made = made
  return FakeWS
}

/** 把一次会话的事件按到达顺序记下来。 */
function recorder() {
  const events = []
  return {
    events: events,
    onEvent: (ev) => events.push(ev),
    states: () => events.filter((e) => e.type === 'state').map((e) => e.state),
    transcripts: () => events.filter((e) => e.type === 'transcript')
  }
}

function doubaoServerFrame(json, opts) {
  const o = opts || {}
  const body = zlib.gzipSync(Buffer.from(JSON.stringify(json), 'utf8'))
  const head = Buffer.from([0x11, ((o.type == null ? 0x09 : o.type) << 4) | ((o.last ? 0x02 : 0x00) | (o.sequence != null ? 0x01 : 0x00)), 0x11, 0x00])
  const parts = [head]
  if (o.sequence != null) { const s = Buffer.alloc(4); s.writeInt32BE(o.sequence, 0); parts.push(s) }
  const len = Buffer.alloc(4)
  len.writeUInt32BE(body.length, 0)
  parts.push(len, body)
  return Buffer.concat(parts)
}

/* ---------------- 帧格式 ---------------- */

test('豆包首帧是 11 10 11 00 + 大端长度 + gzip JSON，字段按实测通过的组合', () => {
  const buf = V.doubaoFullRequest()
  assert.deepStrictEqual([...buf.subarray(0, 4)], [0x11, 0x10, 0x11, 0x00])
  const len = buf.readUInt32BE(4)
  assert.strictEqual(len, buf.length - 8, '声明长度必须等于实际 gzip 载荷长度')
  const json = JSON.parse(zlib.gunzipSync(buf.subarray(8)).toString('utf8'))
  assert.strictEqual(json.audio.rate, 16000)
  assert.strictEqual(json.audio.bits, 16)
  assert.strictEqual(json.audio.channel, 1)
  assert.strictEqual(json.audio.format, 'pcm')
  assert.strictEqual(json.request.model_name, 'bigmodel')
  assert.strictEqual(json.request.show_utterances, true)
  assert.strictEqual(json.request.result_type, 'full')
  assert.ok(json.user.uid)
})

test('豆包音频帧 flags 区分普通帧与最后一帧，载荷是原始 PCM（gzip 后）', () => {
  const pcm = Buffer.alloc(V.FRAME_BYTES, 0x22)
  const normal = V.doubaoAudioFrame(pcm, false)
  const last = V.doubaoAudioFrame(pcm, true)
  assert.deepStrictEqual([...normal.subarray(0, 4)], [0x11, 0x20, 0x11, 0x00])
  assert.deepStrictEqual([...last.subarray(0, 4)], [0x11, 0x22, 0x11, 0x00])
  const decoded = zlib.gunzipSync(normal.subarray(8))
  assert.strictEqual(decoded.length, V.FRAME_BYTES)
  assert.strictEqual(decoded[0], 0x22)
})

test('豆包响应拆包：带序号、压缩、last 标志、错误帧都认得', () => {
  const ok = V.parseDoubaoPacket(doubaoServerFrame({ result: { text: 'hi' } }, { sequence: 7, last: true }))
  assert.strictEqual(ok.kind, 'result')
  assert.strictEqual(ok.sequence, 7)
  assert.strictEqual(ok.last, true)
  assert.strictEqual(ok.json.result.text, 'hi')
  const noSeq = V.parseDoubaoPacket(doubaoServerFrame({ result: { text: 'x' } }, {}))
  assert.strictEqual(noSeq.sequence, null)
  assert.strictEqual(noSeq.last, false)

  const msg = Buffer.from('bad key', 'utf8')
  const head = Buffer.from([0x11, 0xf0, 0x11, 0x00])
  const code = Buffer.alloc(4); code.writeUInt32BE(45000001, 0)
  const len = Buffer.alloc(4); len.writeUInt32BE(msg.length, 0)
  const err = V.parseDoubaoPacket(Buffer.concat([head, code, len, msg]))
  assert.strictEqual(err.kind, 'error')
  assert.strictEqual(err.code, 45000001)
  assert.strictEqual(err.message, 'bad key')
})

test('截断 / 长度对不上的响应帧被当作坏帧，不会把会话弄崩', () => {
  const good = doubaoServerFrame({ result: { text: 'ok' } })
  assert.throws(() => V.parseDoubaoPacket(good.subarray(0, 6)))
  assert.throws(() => V.parseDoubaoPacket(Buffer.from([0x11])))
})

/* ---------------- 句子身份 ---------------- */

test('豆包按 utterances 归一：一句的身份是 start_time，缺失时退回 words[0].start_time', () => {
  const evs = V.doubaoTranscriptEvents({
    result: {
      text: '你好世界',
      utterances: [
        { text: '你好', start_time: 120, end_time: 600, definite: true },
        { text: '世界', words: [{ start_time: 700 }], end_time: 1100, definite: false }
      ]
    }
  })
  assert.strictEqual(evs.length, 2)
  assert.deepStrictEqual(evs[0], { type: 'transcript', startMs: 120, derived: false, endMs: 600, words: 0, text: '你好', isFinal: true })
  assert.strictEqual(evs[1].startMs, 700, '没有 start_time 时从词级起点推')
  assert.strictEqual(evs[1].derived, true, 'words 推出来的起点是 derived，不能当稳定 key')
  assert.strictEqual(evs[1].isFinal, false)

  // result_type=full 的累计全文只作为兜底，不能和 utterances 一起发出（否则页面会重复一遍）
  const onlyText = V.doubaoTranscriptEvents({ result: { text: '只有全文' } })
  assert.strictEqual(onlyText.length, 1)
  assert.strictEqual(onlyText[0].startMs, 0)
})

test('阿里控制帧：task-started / 心跳句 / sentence_end / task-failed 各自归一', () => {
  const started = V.alibabaEvents(JSON.stringify({ header: { event: 'task-started' } }))
  assert.deepStrictEqual(started, [{ type: 'started' }])
  assert.deepStrictEqual(V.alibabaEvents(JSON.stringify({ header: { event: 'task-finished' } })), [{ type: 'done' }])

  const hb = V.alibabaEvents(JSON.stringify({
    header: { event: 'result-generated' },
    payload: { output: { sentence: { begin_time: 0, end_time: 100, text: '你好', heartbeat: true, sentence_end: false } } }
  }))
  assert.deepStrictEqual(hb, [{ type: 'ignored' }], '心跳句是合法空帧：忽略，但不是协议失败')

  // 合法的 result-generated 里暂时还没有 sentence：同样是「合法空帧」
  const empty = V.alibabaEvents(JSON.stringify({ header: { event: 'result-generated' }, payload: { output: {} } }))
  assert.deepStrictEqual(empty, [{ type: 'ignored' }])
  // 坏 JSON / 缺 header / 未知事件才是协议失败（[]）
  assert.deepStrictEqual(V.alibabaEvents(JSON.stringify({ header: { event: 'mystery-event' } })), [])
  assert.deepStrictEqual(V.alibabaEvents(JSON.stringify({ payload: {} })), [])

  const partial = V.alibabaEvents(JSON.stringify({
    header: { event: 'result-generated' },
    payload: { output: { sentence: { begin_time: 200, end_time: 900, text: '你好世', sentence_end: false } } }
  }))
  assert.strictEqual(partial.length, 1)
  assert.strictEqual(partial[0].startMs, 200)
  assert.strictEqual(partial[0].isFinal, false)

  const fin = V.alibabaEvents(JSON.stringify({
    header: { event: 'result-generated' },
    payload: { output: { sentence: { begin_time: 200, end_time: 1200, text: '你好世界。', sentence_end: true } } }
  }))
  assert.strictEqual(fin[0].isFinal, true)

  const failed = V.alibabaEvents(JSON.stringify({ header: { event: 'task-failed', error_code: 'InvalidApiKey', error_message: 'bad key' } }))
  assert.strictEqual(failed[0].type, 'error')
  assert.match(failed[0].message, /InvalidApiKey/)
  assert.deepStrictEqual(V.alibabaEvents('not json'), [])
})

test('阿里开始 / 收尾帧：run-task 带固定模型与格式，finish-task 复用同一个 task_id', () => {
  const run = JSON.parse(V.alibabaRunTask('t-1'))
  assert.strictEqual(run.header.action, 'run-task')
  assert.strictEqual(run.header.task_id, 't-1')
  assert.strictEqual(run.header.streaming, 'duplex')
  assert.strictEqual(run.payload.model, 'paraformer-realtime-v2')
  assert.strictEqual(run.payload.parameters.sample_rate, 16000)
  assert.strictEqual(run.payload.parameters.format, 'pcm')
  const fin = JSON.parse(V.alibabaFinishTask('t-1'))
  assert.strictEqual(fin.header.action, 'finish-task')
  assert.strictEqual(fin.header.task_id, 't-1')
})

/* ---------------- IPC 边界的帧校验 ---------------- */

test('validatePcmFrame 只收 ≤6400 字节、偶数长度（s16le）的帧', () => {
  assert.strictEqual(V.validatePcmFrame(Buffer.alloc(V.FRAME_BYTES)).ok, true)
  assert.strictEqual(V.validatePcmFrame(Buffer.alloc(V.FRAME_BYTES + 2)).ok, false, '超过 200ms 的帧必须拒绝')
  assert.strictEqual(V.validatePcmFrame(Buffer.alloc(3)).ok, false, '奇数字节不是整数个采样')
  assert.strictEqual(V.validatePcmFrame(Buffer.alloc(0)).ok, false)
  assert.strictEqual(V.validatePcmFrame(null).ok, false)
  const u8 = new Uint8Array(320)
  const r = V.validatePcmFrame(u8)
  assert.strictEqual(r.ok, true)
  assert.ok(Buffer.isBuffer(r.bytes))
  // ArrayBuffer 视图的切片只取视图范围，不能把整个底层缓冲当成载荷
  const big = new Uint8Array(1000)
  const slice = V.validatePcmFrame(big.subarray(100, 500))
  assert.strictEqual(slice.bytes.length, 400)
})

test('令牌桶：正常 5 帧/秒能过，突发超速会被拒（不排队）', () => {
  let t = 0
  const gate = V.createRateGate({ now: () => t })
  let allowed = 0
  for (let i = 0; i < 50; i++) if (gate.take()) allowed++
  assert.strictEqual(allowed, 40, '容量 40 的桶一次最多放 40 帧')
  assert.strictEqual(gate.take(), false)
  t += 1000
  let after = 0
  for (let i = 0; i < 30; i++) if (gate.take()) after++
  assert.strictEqual(after, 25, '一秒补 25 个令牌（真实需求 5 帧/秒）')
})

/* ---------------- 会话生命周期 ---------------- */

test('豆包会话：连接后先发完整请求，音频按帧发出，final 到达即 finished 且只报一次终态', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS })
  const startP = s.start()
  assert.strictEqual(WS.made.length, 1)
  const ws = WS.made[0]
  assert.match(ws.url, /^wss:\/\/openspeech\.bytedance\.com\//)
  assert.strictEqual(ws.opts.headers['X-Api-Key'], 'k')
  assert.strictEqual(ws.opts.headers['X-Api-Resource-Id'], 'volc.seedasr.sauc.duration')
  assert.ok(ws.opts.headers['X-Api-Request-Id'])
  ws.open()
  const r = await startP
  assert.strictEqual(r.ok, true)
  assert.deepStrictEqual([...ws.sent[0].subarray(0, 4)], [0x11, 0x10, 0x11, 0x00], '第一帧必须是完整请求')
  assert.strictEqual(s.state, 'connecting', 'socket open 不算就绪：要等服务器第一条合法 ack')
  assert.strictEqual(s.sendPcm(Buffer.alloc(V.FRAME_BYTES, 1)), false, '就绪前推音频一律拒收（页面要等 ready 才开麦）')

  // 服务端先回一条 init result（没有音频也会回）→ 这才算 READY
  ws.receive(doubaoServerFrame({ result: { text: '' } }))
  assert.strictEqual(s.state, 'ready')

  // 先存一帧：第一次 sendPcm 不该立刻上线（留给 finish 当尾巴）
  assert.strictEqual(s.sendPcm(Buffer.alloc(V.FRAME_BYTES, 1)), true)
  assert.strictEqual(ws.sent.length, 1, '音频要先握在手上，不能立刻发')
  assert.strictEqual(s.sendPcm(Buffer.alloc(V.FRAME_BYTES, 2)), true)
  assert.strictEqual(ws.sent.length, 2, '第二帧到来才把第一帧发出去')
  assert.deepStrictEqual([...ws.sent[1].subarray(0, 4)], [0x11, 0x20, 0x11, 0x00])

  ws.receive(doubaoServerFrame({
    result: { text: '你好', utterances: [{ text: '你好', start_time: 0, end_time: 400, definite: false }] }
  }))
  ws.receive(doubaoServerFrame({
    result: { text: '你好。', utterances: [{ text: '你好。', start_time: 0, end_time: 500, definite: true }] }
  }, { sequence: 2 }))
  // 全量重发：内容没变的那句不该再推一遍（否则页面被同一句反复刷）
  ws.receive(doubaoServerFrame({ result: { text: '你好。', utterances: [{ text: '你好。', start_time: 0, end_time: 500, definite: true }] } }, { last: true }))

  const tr = rec.transcripts()
  assert.strictEqual(tr.length, 2, 'partial 一次 + final 一次；未变化的重复重发不再推出新事件')
  assert.strictEqual(tr[0].startMs, tr[1].startMs, '同一句的 partial 与 final 必须是同一个身份（页面按它替换）')
  assert.deepStrictEqual(tr.map((t) => t.isFinal), [false, true])
  const finals = rec.states().filter((x) => x === 'finished')
  assert.strictEqual(finals.length, 1, '终态只报一次')
  assert.strictEqual(ws.terminated, true, 'finished 之后 socket 必须收掉')

  // 终态之后的一切都被丢掉
  const before = rec.events.length
  ws.receive(doubaoServerFrame({ result: { text: '迟到', utterances: [{ text: '迟到', start_time: 9, end_time: 9, definite: true }] } }))
  s.cancel()
  assert.strictEqual(rec.events.length, before, '终态之后的回调不能再冒事件')
})

test('停止（finish）把手上的最后一帧按 last 发出去，并等到最终结果才算结束', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS, drainTimeoutMs: 60 })
  const p = s.start()
  WS.made[0].open()
  await p
  const ws = WS.made[0]
  ws.receive(doubaoServerFrame({ result: { text: '' } }))     // init ack → ready
  // 两帧：第一帧在第二帧到来时发出去（这一下才算 streaming），最后一帧留给 finish 当尾巴
  s.sendPcm(Buffer.alloc(V.FRAME_BYTES, 3))
  s.sendPcm(Buffer.alloc(V.FRAME_BYTES, 4))
  const r = s.finish()
  assert.strictEqual(r.state, 'draining')
  const lastFrame = ws.sent[ws.sent.length - 1]
  assert.deepStrictEqual([...lastFrame.subarray(0, 4)], [0x11, 0x22, 0x11, 0x00], '最后一帧必须带 last 标志')
  assert.strictEqual(zlib.gunzipSync(lastFrame.subarray(8))[0], 4, '最后一帧就是手上留的那一帧，不能变成空帧或重复上一帧')
  assert.deepStrictEqual(rec.states(), ['connecting', 'ready', 'streaming', 'draining'])

  ws.receive(doubaoServerFrame({ result: { text: '好的' } }, { last: true }))
  assert.strictEqual(rec.states().filter((x) => x === 'finished').length, 1)
  assert.ok(rec.states().includes('finished'))
})

test('停止后服务器一直不给最终结果：到点如实报错（不假报 finished）', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS, drainTimeoutMs: 40 })
  const p = s.start()
  WS.made[0].open()
  await p
  WS.made[0].receive(doubaoServerFrame({ result: { text: '' } }))   // init ack → ready
  // 只留一帧、没发过第二帧：这一帧直接当 last 发，所以状态里不会出现 streaming —— 这是对的
  s.sendPcm(Buffer.alloc(3200, 5))
  s.finish()
  await new Promise((r) => setTimeout(r, 80))
  assert.deepStrictEqual(rec.states(), ['connecting', 'ready', 'draining', 'error'])
  const err = rec.events[rec.events.length - 1]
  assert.match(err.message, /最终结果/)
})

test('cancel 立刻释放：终态 cancelled、socket 收掉、之后音频与非终态事件全部作废', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'alibaba', apiKey: 'bearer-k', onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  const ws = WS.made[0]
  assert.strictEqual(ws.opts.headers.Authorization, 'Bearer bearer-k', '阿里用 Bearer，且和豆包的 X-Api-Key 不混用')
  ws.open()
  await p
  assert.strictEqual(JSON.parse(ws.sent[0].toString('utf8')).header.action, 'run-task')
  ws.receive(JSON.stringify({ header: { event: 'task-started' } }))
  assert.ok(rec.states().includes('ready'), '阿里要等 task-started 才 ready')

  const n = rec.events.length
  s.cancel()
  assert.strictEqual(ws.terminated, true)
  assert.strictEqual(s.sendPcm(Buffer.alloc(3200, 1)), false, 'cancel 之后不再收音频')
  ws.receive(JSON.stringify({ header: { event: 'result-generated' }, payload: { output: { sentence: { begin_time: 0, end_time: 9, text: '迟到', sentence_end: true } } } }))
  assert.strictEqual(rec.transcripts().length, 0, 'cancel 之后到达的转写必须被丢掉')
  assert.strictEqual(rec.states().filter((x) => x === 'cancelled').length, 1)
  assert.strictEqual(rec.events.length, n + 1, 'cancel 只多一个终态事件')
})

test('连接阶段就 cancel：不会遗留 draining 计时器，也不会发 finished', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  const ws = WS.made[0]
  s.cancel()                       // 握手还没完成就取消
  assert.strictEqual(ws.terminated, true)
  ws.open()                        // 迟到的 open 不能把状态推回 ready
  await p
  assert.deepStrictEqual(rec.states(), ['connecting', 'cancelled'])
  assert.strictEqual(s.finish().state, 'cancelled')
})

test('鉴权被拒（unexpected-response）只报状态码与可读原因，不把响应内容带出去', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'wrong', onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  const ws = WS.made[0]
  ws.emit('unexpected-response', {}, { statusCode: 401, headers: { 'x-tt-logid': 'secret-ish' } })
  const r = await p
  assert.strictEqual(r.ok, false)
  assert.match(rec.events[rec.events.length - 1].message, /HTTP 401/)
  assert.match(rec.events[rec.events.length - 1].message, /鉴权失败/)
  assert.doesNotMatch(JSON.stringify(rec.events), /secret-ish/)
})

test('服务器中途关连接：明确 error，不静默变 finished', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'alibaba', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  WS.made[0].open()
  await p
  WS.made[0].emit('close', 1006, Buffer.from(''))
  assert.strictEqual(rec.states().filter((x) => x === 'error').length, 1)
  assert.strictEqual(rec.states().includes('finished'), false)
})

test('API Key 不进事件：onEvent 的任何字段都不会带出凭据', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const KEY = 'super-secret-key-value'
  const s = new V.VoiceAsrSession({ provider: 'alibaba', apiKey: KEY, onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  WS.made[0].open()
  await p
  WS.made[0].receive(JSON.stringify({ header: { event: 'task-started' } }))
  s.sendPcm(Buffer.alloc(3200, 1))
  s.finish()
  assert.strictEqual(JSON.stringify(rec.events).includes(KEY), false)
})

test('阿里：socket 通了但一直不回 task-started → 初始化超时后明确协议失败（不假 live）', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'alibaba', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS, initTimeoutMs: 40 })
  const p = s.start()
  WS.made[0].open()
  await p
  assert.strictEqual(s.state, 'connecting', 'task-started 之前不能算就绪')
  await new Promise((r) => setTimeout(r, 90))
  assert.strictEqual(s.state, 'error')
  const err = rec.events[rec.events.length - 1]
  assert.match(err.message, /task-started/)
  assert.match(err.message, /协议失败/)
  assert.strictEqual(WS.made[0].terminated, true, '超时后必须收掉连接，不能半开着')
})

test('豆包：一直不回 ack 也算协议失败；晚到的 ack 到不了 error 之后', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS, initTimeoutMs: 40 })
  const p = s.start()
  WS.made[0].open()
  await p
  await new Promise((r) => setTimeout(r, 90))
  assert.strictEqual(s.state, 'error')
  const before = rec.events.length
  WS.made[0].receive(doubaoServerFrame({ result: { text: '' } }))    // 迟到的 ack
  assert.strictEqual(s.state, 'error', '终态之后不能又被推成 ready')
  assert.strictEqual(rec.events.length, before)
})

test('收到不合法的帧 → 直接协议失败终止，不会 bad++ 吞掉后一直假 live', async () => {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession({ provider: 'doubao', apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS })
  const p = s.start()
  WS.made[0].open()
  await p
  WS.made[0].receive(doubaoServerFrame({ result: { text: '' } }))
  assert.strictEqual(s.state, 'ready')

  WS.made[0].receive(Buffer.from([0x11, 0x90, 0x11, 0x00, 0x00]))   // 截断帧
  assert.strictEqual(s.state, 'error', '坏帧必须终止会话')
  const err = rec.events[rec.events.length - 1]
  assert.match(err.message, /协议错误/)
  assert.strictEqual(s.stats.bad > 0, true)
  assert.strictEqual(WS.made[0].terminated, true)

  // 阿里侧：认不出的控制帧同样终止
  const WS2 = fakeSocketClass()
  const rec2 = recorder()
  const s2 = new V.VoiceAsrSession({ provider: 'alibaba', apiKey: 'k', onEvent: rec2.onEvent, WebSocketImpl: WS2 })
  const p2 = s2.start()
  WS2.made[0].open()
  await p2
  WS2.made[0].receive(JSON.stringify({ header: { event: 'task-started' } }))
  assert.strictEqual(s2.state, 'ready')
  WS2.made[0].receive(JSON.stringify({ payload: {} }))              // 没有 header.event
  assert.strictEqual(s2.state, 'error')
  assert.match(rec2.events[rec2.events.length - 1].message, /协议错误/)
})

/* ---------------- 真实重复 bug（live 实测：同句多条 partial/final 叠加） ---------------- */

/** 让会话进入 READY，返回假 socket。 */
async function readySession(opts) {
  const WS = fakeSocketClass()
  const rec = recorder()
  const s = new V.VoiceAsrSession(Object.assign({ apiKey: 'k', onEvent: rec.onEvent, WebSocketImpl: WS }, opts || {}))
  const p = s.start()
  WS.made[0].open()
  await p
  if (s.provider === 'alibaba') WS.made[0].receive(JSON.stringify({ header: { event: 'task-started' } }))
  else WS.made[0].receive(doubaoServerFrame({ result: { text: '' } }))
  return { s: s, ws: WS.made[0], rec: rec }
}

test('utterances 缺 start_time：derived 起点漂移也不产生第二条（同句只替换，不叠加）', async () => {
  const { s, ws, rec } = await readySession({ provider: 'doubao' })
  // 实测：只有 words[].start_time，且首词时间随修订变化（700 → 640 → 700）
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '今天下午', words: [{ start_time: 700 }], end_time: 1200, definite: false }] }
  }))
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '今天下午3点', words: [{ start_time: 640 }], end_time: 1500, definite: false }] }
  }))
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '今天下午3点开会。', words: [{ start_time: 700 }], end_time: 2100, definite: true }] }
  }))
  const tr = rec.transcripts()
  const keys = new Set(tr.map((t) => t.startMs))
  assert.strictEqual(keys.size, 1, '同一句必须始终一个身份，不能因为 derived 起点漂移多出几条')
  assert.strictEqual(tr[tr.length - 1].text, '今天下午3点开会。')
  assert.strictEqual(tr[tr.length - 1].isFinal, true)
  assert.strictEqual(s.utterances.size, 1, '身份表里也只能有一句')
})

test('定稿之后起点再漂的重发不新增第二条（derived / direct 都适用；时间不相交的重复话语仍是两句）', async () => {
  // 真实 IPC：同一句话 final(startMs 260,endMs 6472)，停止时尾包又 final(startMs 80,endMs 6472,text 相同)
  // —— 起点在定稿之后被服务端改成更准的值，页面按 startMs 做身份，多出就是两条同样的正文。
  const a = await readySession({ provider: 'doubao' })
  a.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午三点开会', start_time: 260, end_time: 6472, definite: true }] } }))
  a.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午三点开会', start_time: 80, end_time: 6472, definite: true }] } }))
  const aTr = a.rec.transcripts()
  assert.strictEqual(aTr.length, 1, '同句起点 260→80 的重发不能再冒一条（页面会显示两段一样的话）')
  assert.strictEqual(aTr[0].startMs, 260, '沿用已定稿的身份，页面才能覆盖而不是追加')
  assert.strictEqual(a.s.utterances.size, 1)

  // derived 句子：起点漂移定稿后，全量重发（此时没有 pending）仍必须是同一身份
  const b = await readySession({ provider: 'doubao' })
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午', words: [{ start_time: 700 }], end_time: 1000, definite: false }] } }))
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午3点', words: [{ start_time: 640 }], end_time: 2000, definite: false }] } }))
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午3点开会。', words: [{ start_time: 640 }], end_time: 3000, definite: true }] } }))
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '今天下午3点开会。', words: [{ start_time: 640 }], end_time: 3000, definite: true }] } }))
  assert.strictEqual(new Set(b.rec.transcripts().map((t) => t.startMs)).size, 1, 'drift 后 final 重发不能换身份')
  assert.strictEqual(b.rec.transcripts().length, 3, 'partial 两次 + final 一次；final 的重发不再冒事件')

  // 真正重复说的话（时间不相交、结束时间也不同）不能被当成同句合并
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '好的', start_time: 9000, end_time: 9300, definite: true }] } }))
  b.ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '好的', start_time: 12000, end_time: 12300, definite: true }] } }))
  const good = b.rec.transcripts().filter((t) => t.text === '好的')
  assert.deepStrictEqual(good.map((t) => t.startMs), [9000, 12000], '同文本但时间不相交必须是两句（不按文字去重）')
})

test('相邻短句不被合并：derived 起点不重叠就是两句（不做固定毫秒近邻 merge）', async () => {
  const { s, ws, rec } = await readySession({ provider: 'doubao' })
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '你好', words: [{ start_time: 0 }], end_time: 300, definite: true }] }
  }))
  ws.receive(doubaoServerFrame({
    result: {
      utterances: [
        { text: '你好', words: [{ start_time: 0 }], end_time: 300, definite: true },
        { text: '谢谢', words: [{ start_time: 350 }], end_time: 650, definite: false }
      ]
    }
  }))
  const texts = rec.transcripts().map((t) => t.text)
  assert.deepStrictEqual(texts, ['你好', '谢谢'], '只差 50ms 的两句也必须是两句')
  assert.strictEqual(s.utterances.size, 2)
})

test('已定稿的句子不会被后到的 partial 改回未定稿（同身份替换方向单向）', async () => {
  const { s, ws, rec } = await readySession({ provider: 'doubao' })
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '第一句。', start_time: 100, end_time: 800, definite: true }] }
  }))
  const before = rec.transcripts().length
  // 服务端偶发补发的旧 partial：不能把已定稿那句改回 partial
  ws.receive(doubaoServerFrame({
    result: { utterances: [{ text: '第一句', start_time: 100, end_time: 700, definite: false }] }
  }))
  assert.strictEqual(rec.transcripts().length, before, '已定稿之后迟到的 partial 不再冒事件')
  assert.strictEqual(s.utterances.get('100').isFinal, true)
})

test('direct 起点稳定：同一 start_time 的 partial → final 只有一条记录', async () => {
  const { s, ws, rec } = await readySession({ provider: 'doubao' })
  ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '你好', start_time: 500, end_time: 900, definite: false }] } }))
  ws.receive(doubaoServerFrame({ result: { utterances: [{ text: '你好。', start_time: 500, end_time: 1000, definite: true }] } }))
  const tr = rec.transcripts()
  assert.strictEqual(tr.length, 2, 'partial 一次 + final 一次')
  assert.strictEqual(tr[0].startMs, 500)
  assert.strictEqual(tr[1].startMs, 500)
  assert.strictEqual(s.utterances.size, 1)
})

/* ---------------- 阿里合法空帧不能致命（ReviewDesktopProtocol 证据） ---------------- */

test('阿里 result-generated 合法空帧 / 心跳句：会话继续，不报错（只有未知 event 与坏 JSON 才 fatal）', async () => {
  const { s, ws, rec } = await readySession({ provider: 'alibaba' })
  const statesBefore = rec.states().length
  // 心跳句
  ws.receive(JSON.stringify({
    header: { event: 'result-generated' },
    payload: { output: { sentence: { begin_time: 0, end_time: 100, text: '', heartbeat: true, sentence_end: false } } }
  }))
  // 没有 sentence 的空帧
  ws.receive(JSON.stringify({ header: { event: 'result-generated' }, payload: { output: {} } }))
  assert.strictEqual(s.state, 'ready', '合法空帧后仍然是 ready')
  assert.strictEqual(rec.states().length, statesBefore, '空帧不该产生任何状态变化')

  // 之后正常句子照常出声
  ws.receive(JSON.stringify({
    header: { event: 'result-generated' },
    payload: { output: { sentence: { begin_time: 200, end_time: 900, text: '你好', sentence_end: true } } }
  }))
  assert.strictEqual(rec.transcripts().length, 1)
  assert.strictEqual(s.state, 'ready')

  // 未知 event 仍然 fatal
  ws.receive(JSON.stringify({ header: { event: 'totally-unknown' } }))
  assert.strictEqual(s.state, 'error')
  assert.match(rec.events[rec.events.length - 1].message, /协议错误/)
})
