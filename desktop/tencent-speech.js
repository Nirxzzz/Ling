'use strict'

/* tencent-speech.js — 腾讯云「实时语音识别（ASR WebSocket v2）」与「流式语音合成（TTS stream_ws）」的协议层。
 *
 * 这一层只做四件事：
 *   1) 按官方线上格式拼签名 URL（HMAC-SHA1 + base64，参数排序后拼签名原文）；
 *   2) 收发协议帧：ASR 是裸 PCM 二进制上行 + JSON 下行；TTS 是握手一条 GET URL，之后二进制音频 + JSON 下行；
 *   3) 结束/取消语义：ASR 发 {"type":"end"} 等 final=1；TTS 收到 final=1 才算完成；
 *   4) 把服务端事件归一成 onReady / onText / onPcm / onFinished / onError。
 * 不碰 Electron、IPC、磁盘、麦克风、播放 —— 那些在 voice-main.js（主进程）与页面里。
 *
 * 线上格式依据（2026-10-28 核对，均为官方文档 / 官方 SDK 源码，不靠二手博客）：
 *  - ASR: https://cloud.tencent.com/document/product/1093/48982
 *         签名原文 = `asr.cloud.tencent.com/asr/v2/<appid>?<参数按字典序 & 拼接>`（不含 wss://、不含 signature），
 *         签名 = base64(HMAC-SHA1(SecretKey, 原文))，拼进 URL 时必须 urlencode。
 *         交叉核对 tencentcloud-speech-sdk-nodejs/asr/speechRecognizer.js + common/speechcredential.js。
 *  - TTS: https://cloud.tencent.com/document/product/1073/94308
 *         签名原文 = `GET` + `tts.cloud.tencent.com/stream_ws` + `?<参数按字典序 & 拼接>`，
 *         签名同一算法；最终 URL 里 Text 与 Signature 都要 urlencode。
 *         交叉核对 tencentcloud-speech-sdk-go/tts/speechwssynthesizer.go（buildURL + genWsSignature）
 *         与 tencentcloud-speech-sdk-python/tts/speech_synthesizer_ws.py。
 *
 * 音频：两个方向都是 16k / 16bit / 单声道 / 小端 PCM；TTS 明确请求 SampleRate=16000 & Codec=pcm。
 * 速度：TTS 的 Speed 是官方 [-2, 6] 参数（-2→0.6 倍，0→1.0 倍，6→2.5 倍），**不是倍率**。
 * 凭据：appId / secretId / secretKey 只在本模块内存里参与签名；绝不落盘、绝不进日志、绝不进错误文本。
 * 只在主进程使用：渲染进程拿到的应该是 PCM 与文本，不是凭据。
 */

const crypto = require('node:crypto')
const WebSocket = require('ws')

const SAMPLE_RATE = 16000
const FRAME_BYTES = 6400            // 200ms @16k/16bit/mono：ASR 官方建议的 1:1 实时率分包
const MAX_PENDING_BYTES = 64000     // 未就绪时最多攒 2 秒：攒满就是背压，按失败收尾（绝不静默丢帧）
const MAX_SOCKET_BUFFERED = 1 << 20 // ws 缓冲上限（约 32 秒 16k PCM）：超了按失败收尾，不丢帧继续
const MAX_JSON_BYTES = 1 << 20      // 单个 JSON 帧上限：正常几 KB，超了说明对面发的东西不对
const BACKPRESSURE = '网络太慢，录音已经积压，本次识别已停止（不会补发，请重新开始）'
const PENDING_BACKPRESSURE = '语音还没就绪就已经积压超过 2 秒，本次识别已停止（不会补发，请重新开始）'
const CONNECT_TIMEOUT_MS = 8000     // TCP+TLS+HTTP 升级
const READY_TIMEOUT_MS = 8000       // 连上了但服务端一直不回话（ASR 首包 / TTS 握手包）
const DRAIN_TIMEOUT_MS = 8000       // ASR 发出 end 之后等 final=1 的硬上限
const TTS_IDLE_TIMEOUT_MS = 15000   // TTS 合成中两包之间最长间隔
const TTS_TOTAL_TIMEOUT_MS = 180000 // TTS 整场硬上限（600 汉字最长也就两分多钟音频）
const ASR_ENGINE = '16k_zh'         // 官方通用中文引擎（16k PCM 对应 16k 采样率引擎）

/* 服务端错误码 → 一句用户能看懂的话。消息文本仍以服务端原文为主（会先抹掉凭据）。 */
const ASR_CODE_HINT = {
  4000: '音频发送太快（1 秒内最多发 3 秒音频）',
  4002: '鉴权失败：检查 AppId / SecretId / SecretKey',
  4003: 'AppID 未开通实时语音识别',
  4004: '识别资源包已用完',
  4005: '账号欠费',
  4006: '并发超限',
  4007: '音频格式不对（需要 16k/16bit/单声道 PCM）',
  4008: '客户端超过 15 秒未发送音频数据',
  4009: '客户端连接断开',
  4010: '客户端上传未知文本消息',
  5000: '服务端繁忙，稍后重试',
  5001: '服务端繁忙，稍后重试',
  5002: '服务端繁忙，稍后重试',
  6001: '境外调用未开通（国内站账号请关闭境外代理）'
}
const TTS_CODE_HINT = {
  10001: '参数不合法（检查音色 ID / 语速）',
  10002: '并发超限',
  10003: '鉴权失败：检查 AppId / SecretId / SecretKey',
  10004: '客户端上传超时',
  10005: '连接被断开',
  20000: '后台错误',
  20001: '后台合成失败',
  20002: '引擎合成失败',
  20003: '引擎合成超时'
}

/* ---------------- 通用校验 ---------------- */

/** 归一化并校验凭据；返回新的普通对象（不持有调用方对象引用）。校验失败抛 TypeError。 */
function normalizeCredentials(credentials) {
  if (!credentials || typeof credentials !== 'object') {
    throw new TypeError('腾讯语音凭据缺失（需要 appId / secretId / secretKey）')
  }
  const appId = String(credentials.appId == null ? '' : credentials.appId).trim()
  const secretId = String(credentials.secretId == null ? '' : credentials.secretId).trim()
  const secretKey = String(credentials.secretKey == null ? '' : credentials.secretKey).trim()
  if (!appId || !secretId || !secretKey) {
    throw new TypeError('腾讯语音凭据不完整（需要 appId / secretId / secretKey 三项）')
  }
  // 官方要求 AppId 是整型；不接受 "1259xxx " 之外的形态，避免把错值签进 URL。
  if (!/^[0-9]{1,20}$/.test(appId)) throw new TypeError('腾讯语音 AppId 必须是数字')
  return { appId, secretId, secretKey }
}

/** 可能出现在错误文本里的敏感查询参数（值是签名或密钥本身）。 */
const QUERY_SECRET = /(signature|secretid|secretkey|token)=[^&\s"']+/gi

/** 把可能混进文本里的凭据抹掉（密钥本身 + 整条查询串里的签名）。服务端消息本来不该带，出口统一过一遍。 */
function makeScrubber(credentials) {
  const secrets = [credentials.secretKey, credentials.secretId].filter((s) => s && s.length >= 6)
  return function scrub(message) {
    let out = String(message == null ? '' : message)
    for (const secret of secrets) {
      if (out.includes(secret)) out = out.split(secret).join('***')
    }
    return out.replace(QUERY_SECRET, (match, name) => name + '=***')
  }
}

function optionalCallback(options, name) {
  const fn = options[name]
  if (fn == null) return function noop() {}
  if (typeof fn !== 'function') throw new TypeError('腾讯语音回调 ' + name + ' 必须是函数')
  return fn
}

function toBufferView(value) {
  if (Buffer.isBuffer(value)) return value
  if (value instanceof ArrayBuffer) return Buffer.from(value)
  if (ArrayBuffer.isView(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength)
  return null
}

function hmacSha1Base64(secretKey, text) {
  return crypto.createHmac('sha1', secretKey).update(text, 'utf8').digest('base64')
}

/** 随机正整数 nonce：官方要求「随机正整数，最长 10 位」。 */
function nonce9() {
  return String(crypto.randomInt(100000000, 999999999))
}

/** TTS 的 Speed 是 [-2, 6] 的档位参数（可带 2 位小数），序列化时去掉无意义的尾零。 */
function formatSpeed(speed) {
  const rounded = Math.round(speed * 100) / 100
  return rounded === 0 ? '0' : String(rounded)
}

/** 参数按字典序拼 & 连接（签名原文与查询串共用同一顺序，避免两处不一致）。 */
function sortedQuery(params) {
  return Object.keys(params)
    .sort()
    .map((key) => key + '=' + params[key])
    .join('&')
}

/** ASR 握手 URL。签名原文不含协议头、不含 signature 本身。 */
function buildAsrUrl(credentials, voiceId, nowSec) {
  const params = {
    engine_model_type: ASR_ENGINE,
    expired: String(nowSec + 86400),
    needvad: '1',
    nonce: nonce9(),
    secretid: credentials.secretId,
    timestamp: String(nowSec),
    voice_format: '1', // 1 = pcm
    voice_id: voiceId
  }
  const signStr = 'asr.cloud.tencent.com/asr/v2/' + credentials.appId + '?' + sortedQuery(params)
  const signature = hmacSha1Base64(credentials.secretKey, signStr)
  const query = Object.keys(params)
    .sort()
    .map((key) => key + '=' + encodeURIComponent(params[key]))
    .join('&')
  return (
    'wss://asr.cloud.tencent.com/asr/v2/' +
    credentials.appId +
    '?' +
    query +
    '&signature=' +
    encodeURIComponent(signature)
  )
}

/** TTS 握手 URL。签名原文 = GET + host + path + ?参数（参数值不编码）；最终 URL 里 Text/Signature 编码。 */
function buildTtsUrl(credentials, text, voiceType, speed, sessionId, nowSec) {
  const params = {
    Action: 'TextToStreamAudioWS',
    AppId: credentials.appId,
    Codec: 'pcm',
    Expired: String(nowSec + 86400),
    SampleRate: String(SAMPLE_RATE),
    SecretId: credentials.secretId,
    SessionId: sessionId,
    Speed: formatSpeed(speed),
    Text: text,
    Timestamp: String(nowSec),
    VoiceType: String(voiceType)
  }
  const signStr = 'GETtts.cloud.tencent.com/stream_ws?' + sortedQuery(params)
  const signature = hmacSha1Base64(credentials.secretKey, signStr)
  const query = Object.keys(params)
    .sort()
    .map((key) => key + '=' + encodeURIComponent(params[key]))
    .join('&')
  return 'wss://tts.cloud.tencent.com/stream_ws?' + query + '&Signature=' + encodeURIComponent(signature)
}

/* ---------------- ASR ---------------- */

/**
 * 开始一路实时识别。
 * @param {object} options
 * @param {{appId:string,secretId:string,secretKey:string}} options.credentials
 * @param {Function} [options.onReady] 服务端握手确认（此后才接受音频）
 * @param {Function} [options.onText] ({text, final, segmentId})
 * @param {Function} [options.onError] (message: string) —— 终态，一辈子一次；文本里没有凭据
 * @param {Function} [options.onFinished] 正常收尾（已收到 final=1）
 * @returns {{sendPcm:function(Buffer):boolean, finish:function():void, cancel:function():void}}
 */
function createRecognition(options) {
  const opts = options || {}
  const credentials = normalizeCredentials(opts.credentials)
  const onReady = optionalCallback(opts, 'onReady')
  const onText = optionalCallback(opts, 'onText')
  const onError = optionalCallback(opts, 'onError')
  const onFinished = optionalCallback(opts, 'onFinished')
  const scrub = makeScrubber(credentials)

  const voiceId = crypto.randomUUID()

  let socket = null
  let ready = false
  let readyEmitted = false
  let finishing = false
  let settled = false // onFinished / onError 只能有一个，且只发一次
  let cancelled = false
  let pending = [] // 就绪前先攒着的小段音频（有上限）
  let pendingBytes = 0
  let carry = Buffer.alloc(0) // 不足一帧的尾巴，凑够 6400 再发
  let readyAt = 0
  let sentMs = 0
  let burstMs = 0 // 就绪时一次性补发出去的量，作为之后发送额度的一次性余量
  const finalText = new Map() // segmentId → 已发过的终态文本（同一句不重复回调）
  const timers = { ready: null, drain: null }

  function clearTimers() {
    for (const key of Object.keys(timers)) {
      clearTimeout(timers[key])
      timers[key] = null
    }
  }

  function destroySocket() {
    const ws = socket
    socket = null
    if (!ws) return
    // 先摘掉业务监听，但必须立刻补一个哑 error 监听：CONNECTING 状态下 terminate() 会异步 emit('error')，
    // 那时没有监听器的 'error' 事件是未捕获异常，会直接崩掉主进程。
    ws.removeAllListeners()
    ws.on('error', function ignoreTerminateError() {})
    try {
      ws.terminate()
    } catch (_) {
      /* 连接早已断掉：没什么可做的 */
    }
  }

  function settleFinished() {
    if (settled) return
    settled = true
    clearTimers()
    destroySocket()
    onFinished()
  }

  function fail(message) {
    if (settled) return
    settled = true
    clearTimers()
    destroySocket()
    if (cancelled) return // 取消之后一律静默，不把「用户自己停的」报成错误
    onError(scrub(message))
  }

  function serverFailure(code, message) {
    const hint = ASR_CODE_HINT[code]
    const detail = message ? String(message) : ''
    const tail = hint ? (detail ? hint + '（' + detail + '）' : hint) : detail
    return '腾讯语音识别失败（' + code + '）' + (tail ? '：' + tail : '')
  }
  /**
   * 往 socket 上送一帧。
   *
   * ws@8 的 `send()` **不返回任何值**（返回值恒为 undefined），所以「发不出去」只能靠两类证据判断：
   *  - 同步：`readyState` 不是 OPEN，或 `bufferedAmount` 已经超过上限（网络跟不上实时流）；
   *  - 异步：`send(payload, cb)` 的完成回调里带回 error（真正写失败时才给）。
   * 绝不能写成 `if (!socket.send(...))` —— 那会把每一次正常发送都误判成背压。
   *
   * 返回 null = 已经交给 ws；否则返回一句可直接上屏的原因。
   */
  function dispatch(payload) {
    if (!socket || socket.readyState !== WebSocket.OPEN) return '腾讯语音识别连接已断开'
    // bufferedAmount 是「已交给内核但还没发出去」的字节：网络跟不上时它会一直涨。
    if (socket.bufferedAmount > MAX_SOCKET_BUFFERED) return BACKPRESSURE
    try {
      socket.send(payload, (err) => {
        // 这一帧没有真的发出去：按失败收尾，不假装还在识别。
        if (err) fail(BACKPRESSURE + '（' + (err.message || '写入失败') + '）')
      })
    } catch (err) {
      // CONNECTING 状态下 send 会直接抛；其余异常同样如实上报。
      return '腾讯语音识别发送失败：' + (err && err.message ? err.message : '未知错误')
    }
    return null
  }

  /** 发一帧 PCM。返回 null = 发出去了；否则返回一句可直接上屏的原因。 */
  function sendFrame(frame) {
    const frameMs = (frame.length / 2 / SAMPLE_RATE) * 1000
    // 官方限制：1 秒内最多发 3 秒音频。额度 = 真实流逝时间 + 1 秒余量 + 就绪时补发的余量。
    if (sentMs + frameMs > Date.now() - readyAt + 1000 + burstMs) {
      return '音频送得比实时还快（腾讯限制 1 秒内最多 3 秒音频），本次识别已停止'
    }
    const problem = dispatch(frame)
    if (problem) return problem
    sentMs += frameMs
    return null
  }

  /** 把一小段 PCM 塞进尾巴，凑满 6400 字节的整帧就发。返回 null = 全部送出去了。 */
  function pushAudio(chunk) {
    carry = carry.length ? Buffer.concat([carry, chunk]) : chunk
    while (carry.length >= FRAME_BYTES) {
      const frame = carry.subarray(0, FRAME_BYTES)
      carry = carry.subarray(FRAME_BYTES)
      const problem = sendFrame(Buffer.from(frame))
      if (problem) return problem
    }
    return null
  }

  function enqueuePending(chunk) {
    if (pendingBytes + chunk.length > MAX_PENDING_BYTES) return false
    pending.push(chunk)
    pendingBytes += chunk.length
    return true
  }

  /**
   * 就绪后把攒下的音频一次送完（**不受实时率约束**：这些声音已经发生过了，卡额度等于整段丢掉）。
   * 返回补发出去的音频时长（作为之后发送额度的一次性余量）；送不出去就当场按失败收尾。
   */
  function flushPending() {
    const queued = pending
    pending = []
    pendingBytes = 0
    let flushedMs = 0
    for (const chunk of queued) {
      carry = carry.length ? Buffer.concat([carry, chunk]) : chunk
      while (carry.length >= FRAME_BYTES) {
        const frame = carry.subarray(0, FRAME_BYTES)
        carry = carry.subarray(FRAME_BYTES)
        const problem = dispatch(Buffer.from(frame))
        if (problem) {
          carry = Buffer.alloc(0)
          fail(problem)
          return 0
        }
        flushedMs += (FRAME_BYTES / 2 / SAMPLE_RATE) * 1000
      }
    }
    return flushedMs
  }

  function armReadyTimer(ms, why) {
    clearTimeout(timers.ready)
    timers.ready = setTimeout(() => fail(why), ms)
  }

  /** 补发尾巴 + 发 {"type":"end"}，然后只等 final=1。就绪后才允许调用。 */
  function sendEnd() {
    // 尾巴（不足一帧的几百毫秒）直接补发：丢掉它就是丢掉最后半句话。
    if (carry.length) {
      const tail = carry
      carry = Buffer.alloc(0)
      const problem = dispatch(tail)
      if (problem) return fail(problem)
    }
    const problem = dispatch('{"type":"end"}')
    if (problem) return fail('腾讯语音识别结束帧发送失败：' + problem)
    // 之后只等 final=1：识别过程中服务端长时间不回包（静音）不是故障，所以不另设 idle 判死。
    timers.drain = setTimeout(() => fail('腾讯语音识别没有在限定时间内结束，结果可能不完整'), DRAIN_TIMEOUT_MS)
  }


  function onServerMessage(raw) {
    if (settled) return
    if (raw.length > MAX_JSON_BYTES) return fail('腾讯语音识别返回了过大的数据包')
    let message = null
    try {
      message = JSON.parse(raw.toString('utf8'))
    } catch (_) {
      return fail('腾讯语音识别返回了无法解析的数据包')
    }
    if (!message || typeof message !== 'object') return fail('腾讯语音识别返回了无法解析的数据包')
    const code = Number(message.code)
    if (code !== 0) return fail(serverFailure(code, message.message))

    if (!readyEmitted) {
      // 握手确认：官方回 {"code":0,"message":"success","voice_id":"..."}。此后才允许上行音频。
      readyEmitted = true
      ready = true
      readyAt = Date.now()
      clearTimeout(timers.ready)
      timers.ready = null
      burstMs = flushPending()
      // 补发阶段就背压失败了：已经按失败收尾，不必再报「就绪」。
      if (settled) return
      onReady()
      // 就绪前就点过「停」：这一刻把尾巴与 end 补上，并忽略这一包里的识别结果。
      if (finishing) return sendEnd()
    }

    if (Number(message.final) === 1) return settleFinished()

    const result = message.result
    if (!result || typeof result !== 'object') return
    const segmentId = String(result.index == null ? '' : result.index)
    const sliceType = Number(result.slice_type)
    const text = typeof result.voice_text_str === 'string' ? result.voice_text_str : ''
    const isFinal = sliceType === 2
    if (isFinal) {
      // 同一句只认一次终态：重复的 final 回调会让上层多跑一轮 LLM。
      if (finalText.get(segmentId) === text) return
      finalText.set(segmentId, text)
    } else if (!text) {
      return // slice_type 0/1 的空中间结果没有信息量
    }
    onText({ text, final: isFinal, segmentId })
  }

  // ---- 建连 ----
  let url
  try {
    url = buildAsrUrl(credentials, voiceId, Math.floor(Date.now() / 1000))
  } catch (err) {
    // 极端情况下（例如系统随机数不可用）也别把凭据塞进错误文本。
    process.nextTick(() => fail('腾讯语音识别连接参数生成失败'))
    return { sendPcm: () => false, finish: () => {}, cancel: () => {} }
  }

  const ws = new WebSocket(url, { handshakeTimeout: CONNECT_TIMEOUT_MS, perMessageDeflate: false })
  socket = ws
  armReadyTimer(CONNECT_TIMEOUT_MS + READY_TIMEOUT_MS, '腾讯语音识别没有在限定时间内就绪')

  ws.on('open', () => {
    if (settled) return
    // socket 通了，但「就绪」仍以服务端第一条 code=0 的消息为准（官方握手确认）。
    armReadyTimer(READY_TIMEOUT_MS, '腾讯语音识别没有在限定时间内就绪')
  })

  ws.on('message', (data, isBinary) => {
    if (settled) return
    if (isBinary) return // ASR 只回 JSON 文本帧；二进制是不该出现的，忽略而不是崩
    onServerMessage(Buffer.isBuffer(data) ? data : Buffer.from(data))
  })

  ws.on('unexpected-response', (_req, res) => {
    fail('腾讯语音识别连接被拒绝（HTTP ' + res.statusCode + '）')
  })

  ws.on('error', (err) => {
    fail('腾讯语音识别连接出错：' + (err && err.message ? err.message : '未知网络错误'))
  })

  ws.on('close', () => {
    if (settled) return
    if (finishing) return fail('腾讯语音识别提前断开，识别结果可能不完整')
    if (!ready) return fail('腾讯语音识别没有连上（连接被服务端关闭）')
    fail('腾讯语音识别连接已断开')
  })

  const api = {
    /**
     * 上行一帧 PCM（16k/16bit/mono，单帧 ≤ 6400 字节、偶数字节）。
     *
     * 返回 false 时上层必须停下采集并提示重新开始 —— 本层不会静默丢帧继续假装在识别。
     * 就绪前先攒（上限 2 秒）；攒满、发不出去（背压）、送得比实时还快，都会当场按失败收尾。
     */
    sendPcm(value) {
      if (settled || cancelled || finishing) return false
      const chunk = toBufferView(value)
      if (!chunk) {
        fail('腾讯语音识别的音频帧不是 Buffer / ArrayBuffer')
        return false
      }
      if (!chunk.length) return true // 没东西可送，不算失败
      if (chunk.length > FRAME_BYTES || chunk.length % 2) {
        fail('腾讯语音识别的音频帧不合法（需要 16 位对齐的 PCM，单帧 ≤ ' + FRAME_BYTES + ' 字节）')
        return false
      }
      if (!ready) {
        if (enqueuePending(Buffer.from(chunk))) return true
        fail(PENDING_BACKPRESSURE)
        return false
      }
      const problem = pushAudio(Buffer.from(chunk))
      if (!problem) return true
      fail(problem)
      return false
    },
    /** 说完了：补发尾巴 + 发结束帧，然后等 final=1（有硬超时）。没就绪时等就绪那一刻再补。 */
    finish() {
      if (settled || cancelled || finishing) return
      finishing = true
      if (ready) sendEnd()
    },
    /** 取消：立刻释放连接，之后迟到的包一律静默。 */
    cancel() {
      if (settled) return
      cancelled = true
      settled = true
      clearTimers()
      destroySocket()
    }
  }
  return api
}

/* ---------------- TTS ---------------- */

function normalizeText(text) {
  const value = typeof text === 'string' ? text : ''
  const trimmed = value.trim()
  if (!trimmed) throw new TypeError('要合成的文本是空的')
  let cjk = 0
  for (const ch of value) {
    const code = ch.codePointAt(0)
    if (code >= 0x3400 && code <= 0x9fff) cjk += 1
  }
  if (cjk > 600) throw new RangeError('合成文本超过 600 个汉字（官方上限）')
  if (value.length > 1800) throw new RangeError('合成文本超过 1800 个字符（官方上限）')
  return value
}

function normalizeVoiceType(voiceType) {
  const value = Number(voiceType)
  if (!Number.isInteger(value) || value <= 0) throw new TypeError('音色 ID（voiceType）必须是正整数，例如 101001')
  return value
}

/** 官方 Speed 是 [-2, 6] 的档位参数（可带两位小数），不是倍率。 */
function normalizeSpeed(speed) {
  const value = Number(speed)
  if (!Number.isFinite(value)) throw new TypeError('语速（speed）必须是 [-2, 6] 之间的数字')
  if (value < -2 || value > 6) throw new RangeError('语速（speed）超出腾讯官方范围 [-2, 6]（0 = 正常语速）')
  return value
}

/**
 * 合成一段文本。
 * @param {object} options
 * @param {{appId:string,secretId:string,secretKey:string}} options.credentials
 * @param {string} options.text 要合成的文本（中文 ≤600 字 / 英文 ≤1800 字母）
 * @param {number} options.voiceType 官方音色 ID（如 101001 智瑜、101016 智甜）
 * @param {number} options.speed 官方语速参数 [-2, 6]（0 = 1.0 倍）
 * @param {Function} [options.onPcm] (Buffer) 16k/16bit/mono 小端 PCM
 * @param {Function} [options.onFinished] 收到 final=1（音频已完整）
 * @param {Function} [options.onError] (message) 终态；文本里没有凭据
 * @returns {{cancel:function():void}}
 */
function synthesize(options) {
  const opts = options || {}
  const credentials = normalizeCredentials(opts.credentials)
  const text = normalizeText(opts.text)
  const voiceType = normalizeVoiceType(opts.voiceType)
  const speed = normalizeSpeed(opts.speed)
  const onPcm = optionalCallback(opts, 'onPcm')
  const onFinished = optionalCallback(opts, 'onFinished')
  const onError = optionalCallback(opts, 'onError')
  const scrub = makeScrubber(credentials)

  const sessionId = crypto.randomUUID()
  let socket = null
  let settled = false
  let cancelled = false
  let gotHandshake = false
  let audioBytes = 0
  const timers = { handshake: null, idle: null, total: null }

  function clearTimers() {
    for (const key of Object.keys(timers)) {
      clearTimeout(timers[key])
      timers[key] = null
    }
  }

  function destroySocket() {
    const ws = socket
    socket = null
    if (!ws) return
    // 同 ASR：terminate() 在 CONNECTING 阶段会异步 emit('error')，摘完监听必须补一个哑 error 监听，
    // 否则未捕获的 'error' 事件会崩掉主进程。
    ws.removeAllListeners()
    ws.on('error', function ignoreTerminateError() {})
    try {
      ws.terminate()
    } catch (_) {
      /* 已断开 */
    }
  }

  function settleFinished() {
    if (settled) return
    settled = true
    clearTimers()
    destroySocket()
    onFinished()
  }

  function fail(message) {
    if (settled) return
    settled = true
    clearTimers()
    destroySocket()
    if (cancelled) return
    onError(scrub(message))
  }

  function bumpIdle() {
    clearTimeout(timers.idle)
    timers.idle = setTimeout(() => fail('腾讯语音合成长时间没有回包，已中断'), TTS_IDLE_TIMEOUT_MS)
  }

  /** 首包到了（无论二进制音频还是 code=0 文本）就不再有「握手超时」。 */
  function markHandshake() {
    gotHandshake = true
    clearTimeout(timers.handshake)
    timers.handshake = null
  }

  // 拼 URL 会做 encodeURIComponent：文本里若有孤立代理项（lone surrogate）会抛 URIError。
  // 这一步必须在 try 里，否则异常直接冒到上层，onError 永远不回调，调用方一直挂在「合成中」。
  let url
  try {
    url = buildTtsUrl(credentials, text, voiceType, speed, sessionId, Math.floor(Date.now() / 1000))
  } catch (_) {
    process.nextTick(() => fail('腾讯语音合成连接参数生成失败'))
    // 与 ASR 一致：此时还没有 socket，cancel 只负责把这次「参数失败」静默掉。
    return {
      cancel() {
        cancelled = true
        settled = true
      }
    }
  }

  const ws = new WebSocket(url, { handshakeTimeout: CONNECT_TIMEOUT_MS, perMessageDeflate: false })
  socket = ws
  timers.handshake = setTimeout(() => fail('腾讯语音合成没有在限定时间内握手成功'), CONNECT_TIMEOUT_MS + READY_TIMEOUT_MS)
  timers.total = setTimeout(() => fail('腾讯语音合成超过总时长上限，已中断'), TTS_TOTAL_TIMEOUT_MS)

  ws.on('message', (data, isBinary) => {
    if (settled) return
    const chunk = Buffer.isBuffer(data) ? data : Buffer.from(data)
    if (isBinary) {
      // 二进制 = 原始 PCM（Codec=pcm/SampleRate=16000）。原样交给上层播放，不做任何转码。
      markHandshake()
      bumpIdle()
      audioBytes += chunk.length
      onPcm(chunk)
      return
    }
    if (chunk.length > MAX_JSON_BYTES) return fail('腾讯语音合成返回了过大的数据包')
    let message = null
    try {
      message = JSON.parse(chunk.toString('utf8'))
    } catch (_) {
      return fail('腾讯语音合成返回了无法解析的数据包')
    }
    if (!message || typeof message !== 'object') return fail('腾讯语音合成返回了无法解析的数据包')
    const code = Number(message.code)
    if (code !== 0) {
      const hint = TTS_CODE_HINT[code]
      const detail = message.message ? String(message.message) : ''
      const tail = hint ? (detail ? hint + '（' + detail + '）' : hint) : detail
      return fail('腾讯语音合成失败（' + code + '）' + (tail ? '：' + tail : ''))
    }
    markHandshake()
    bumpIdle()
    if (Number(message.final) === 1) return settleFinished()
    // 其余是字幕时间戳（result.subtitles）：本层不需要，交给上层不必要的信息没有价值。
  })

  ws.on('unexpected-response', (_req, res) => {
    fail('腾讯语音合成连接被拒绝（HTTP ' + res.statusCode + '）')
  })

  ws.on('error', (err) => {
    fail('腾讯语音合成连接出错：' + (err && err.message ? err.message : '未知网络错误'))
  })

  ws.on('close', () => {
    if (settled) return
    if (!gotHandshake) return fail('腾讯语音合成没有握手成功（连接被服务端关闭）')
    if (!audioBytes) return fail('腾讯语音合成没有返回任何音频')
    fail('腾讯语音合成提前断开，音频可能不完整')
  })

  return {
    /** 取消：立刻断链，之后迟到的音频/回调一律静默（上层据此丢掉已排队的播放）。 */
    cancel() {
      if (settled) return
      cancelled = true
      settled = true
      clearTimers()
      destroySocket()
    }
  }
}

module.exports = { createRecognition, synthesize }
