// voice-dialogue：真实消费方边界回归 —— 只测「出问题就会真的出事」的三条：
//   1) 用户插话后，迟到的 LLM 增量与 TTS 音频都不得再播；
//   2) 同一句 final 重复上报不会跑两轮对话；
//   3) stop() 之后 ASR / LLM / TTS 全部释放，且不再回事件。
// 假协议对端只用来驱动回调时序，不模拟腾讯线上协议（那在 voice-asr / tencent-speech 各自的测试里）。
const test = require('node:test')
const assert = require('node:assert')
const { createDialogue } = require('../voice-dialogue')

/** 可控的假协议层：ASR 只会被我们手动触发回调；TTS 每次合成留一个句柄供断言。 */
function fakeSpeech() {
  const recs = []
  const synths = []
  return {
    recs: recs,
    synths: synths,
    createRecognition(opts) {
      const r = { opts: opts, sent: [], cancelled: false }
      recs.push(r)
      return {
        sendPcm: (b) => { r.sent.push(b); return true },
        finish: () => {},
        cancel: () => { r.cancelled = true }
      }
    },
    synthesize(opts) {
      // 记录与句柄是同一个对象：测试既能断言 cancelled，也能手动放行音频 / 结束
      const s = {
        opts: opts,
        cancelled: false,
        finished: false,
        cancel() { s.cancelled = true },
        _finish() { if (!s.cancelled) { s.finished = true; opts.onFinished() } },
        _pcm(buf) { if (!s.cancelled) opts.onPcm(buf) },
        _error(m) { if (!s.cancelled) opts.onError(m) }
      }
      synths.push(s)
      return s
    }
  }
}
/** 一次可以手动放行的 SSE 响应：让「模型中途吐字」的时序可控。 */
function controlledStream() {
  const state = { cancelled: false }
  state.body = {
    getReader() {
      let queue = []
      let waiter = null
      let done = false
      const flush = () => {
        if (!waiter) return
        if (queue.length) { const v = queue.shift(); const w = waiter; waiter = null; w({ done: false, value: v }) }
        else if (done) { const w = waiter; waiter = null; w({ done: true }) }
      }
      state.push = (text) => {
        const chunk = 'data: ' + JSON.stringify({ choices: [{ delta: { content: text } }] }) + '\n\n'
        queue.push(new TextEncoder().encode(chunk))
        flush()
      }
      state.end = () => { queue.push(new TextEncoder().encode('data: [DONE]\n\n')); done = true; flush() }
      return {
        read: () => new Promise((r) => { waiter = r; flush() }),
        cancel: () => { state.cancelled = true; done = true; flush() }
      }
    }
  }
  state.response = { ok: true, status: 200, body: state.body, text: async () => '' }

  return state
}

function boot(over) {
  const speech = fakeSpeech()
  const events = []
  const audio = []
  const streams = []
  const fetchCalls = []
  const d = createDialogue(Object.assign({
    speech: speech,
    emit: (ev) => events.push(ev),
    sendAudio: (buf) => { audio.push(buf); return true },
    getCredentials: () => ({ appId: '1', secretId: 's', secretKey: 'k' }),
    getChat: () => ({ ready: () => true, cfg: () => ({ baseUrl: 'http://llm/v1', apiKey: 'x', model: 'm' }), style: () => '' }),
    getPrefs: () => ({ voiceType: 101001, speed: 0, volume: 0 }),
    fetchImpl: async (url, init) => {
      const s = controlledStream()
      streams.push(s)
      fetchCalls.push({ url: url, init: init })
      return s.response
    }
  }, over || {}))
  return { d: d, speech: speech, events: events, audio: audio, streams: streams, fetchCalls: fetchCalls }
}

const settle = (ms) => new Promise((r) => setTimeout(r, ms || 0))

test('插话后：迟到的 LLM 增量与 TTS 音频都不再播，且旧合成被取消', async () => {
  const ctx = boot()
  try {
    assert.strictEqual(ctx.d.start({ consent: true }).ok, true)
    const rec = ctx.speech.recs[0]
    rec.opts.onReady()
    // 用户说完一句 → 静音去抖后发起一轮
    rec.opts.onText({ text: '介绍一下量子纠缠。', final: true, segmentId: '0' })
    await settle(600)
    assert.strictEqual(ctx.fetchCalls.length, 1, '一句话应该只发一次 LLM 请求')

    const stream = ctx.streams[0]
    stream.push('量子纠缠是')
    await settle(20)
    // 第一句凑够句号 → 起一次合成
    stream.push('两个粒子共享状态。')
    await settle(40)
    assert.strictEqual(ctx.speech.synths.length, 1, '第一句应该已经开合成')
    const first = ctx.speech.synths[0]

    // 用户插话：partial 有内容就打断
    rec.opts.onText({ text: '等一下', final: false, segmentId: '1' })
    await settle(20)
    assert.strictEqual(first.cancelled, true, '插话必须取消正在进行的合成')
    assert.ok(ctx.events.some((e) => e.type === 'barge-in'), '要通知页面掐掉播放队列')

    // 迟到的 TTS 音频 / LLM 增量都不许再落进播放
    const audioBefore = ctx.audio.length
    first._pcm(Buffer.alloc(320, 1))
    stream.push('这句话不该被念出来。')
    await settle(40)
    assert.strictEqual(ctx.audio.length, audioBefore, '插话后旧合成的音频一片都不能送出去')
    assert.strictEqual(ctx.speech.synths.length, 1, '插话后旧那一轮不许再起新合成')
  } finally { ctx.d.destroy() }
})

test('同一句 final 重复上报只跑一轮对话', async () => {
  const ctx = boot()
  try {
    ctx.d.start({ consent: true })
    const rec = ctx.speech.recs[0]
    rec.opts.onReady()
    for (let i = 0; i < 3; i++) rec.opts.onText({ text: '今天几号？', final: true, segmentId: '0' })
    await settle(600)
    assert.strictEqual(ctx.fetchCalls.length, 1, '重复 final 只能拼成一轮')
    // 用户消息也只该出现一次
    const userTurns = ctx.events.filter((e) => e.type === 'text' && e.role === 'user')
    assert.strictEqual(userTurns.length, 1)
  } finally { ctx.d.destroy() }
})

test('stop() 释放 ASR / LLM / TTS 三个方向的资源，之后不再回事件', async () => {
  const ctx = boot()
  try {
    ctx.d.start({ consent: true })
    const rec = ctx.speech.recs[0]
    rec.opts.onReady()
    rec.opts.onText({ text: '讲个笑话。', final: true, segmentId: '0' })
    await settle(600)
    const stream = ctx.streams[0]
    stream.push('有只鸭子。')
    await settle(40)
    assert.strictEqual(ctx.speech.synths.length, 1, '第二句之前应该已经起了一句合成')

    const r = ctx.d.stop()
    assert.strictEqual(r.ok, true)
    assert.strictEqual(rec.cancelled, true, 'ASR 连接必须关掉')
    assert.strictEqual(ctx.speech.synths[0].cancelled, true, '当前合成必须取消')
    // 真实 fetch 的取消就是 abort：这里断言交给 fetch 的 signal 已中止
    assert.strictEqual(ctx.fetchCalls[0].init.signal.aborted, true, 'LLM 请求必须被 abort')
    // 停止那一刻之后：TTS 迟到音频与新的 final 都不许再引起播放或事件
    const eventsBefore = ctx.events.length
    const audioBefore = ctx.audio.length

    ctx.speech.synths[0]._pcm(Buffer.alloc(320, 1))
    rec.opts.onText({ text: '不该再有反应', final: true, segmentId: '2' })
    await settle(600)
    assert.strictEqual(ctx.audio.length, audioBefore, '停止后不许再播')
    assert.strictEqual(ctx.events.length, eventsBefore, '停止后不许再回事件')
    assert.strictEqual(ctx.fetchCalls.length, 1, '停止后不许再发新请求')
    assert.strictEqual(ctx.d.status().active, false)
  } finally { ctx.d.destroy() }
})

test('缺少腾讯凭据时不开始（不伪造可用）', () => {
  const ctx = boot({ getCredentials: () => null })
  const r = ctx.d.start({ consent: true })
  assert.strictEqual(r.ok, false)
  assert.match(r.error, /腾讯云语音凭据/)
  assert.strictEqual(ctx.speech.recs.length, 0, '没凭据绝不能建连接')
})

test('没有可用对话模型时不开始（不偷偷用默认服务）', () => {
  const ctx = boot({ getChat: () => ({ ready: () => false, cfg: () => ({}) }) })
  const r = ctx.d.start({ consent: true })
  assert.strictEqual(r.ok, false)
  assert.match(r.error, /对话模型/)
  assert.strictEqual(ctx.speech.recs.length, 0)
})

test('合成 PCM 送不出去（播放队列满）就报错停下，不边丢边念', async () => {
  const ctx = boot({ sendAudio: () => false })
  try {
    ctx.d.start({ consent: true })
    const rec = ctx.speech.recs[0]
    rec.opts.onReady()
    rec.opts.onText({ text: '说点什么。', final: true, segmentId: '0' })
    await settle(600)
    const stream = ctx.streams[0]
    stream.push('第一句话。')
    await settle(40)
    const synth = ctx.speech.synths[0]
    synth._pcm(Buffer.alloc(320, 1))
    await settle(20)
    const errs = ctx.events.filter((e) => e.type === 'notice' && e.kind === 'error')
    assert.ok(errs.some((e) => /播放跟不上/.test(e.text)), '要明确报「播不出去」')
    assert.strictEqual(synth.cancelled, true, '这句合成必须掐掉')
  } finally { ctx.d.destroy() }
})
