/* voice-dialogue.js — 语音对话的主进程编排：腾讯实时识别（ASR）→ 既有 chat 模型 → 腾讯流式合成（TTS）。
 *
 * 这一层只做「一轮对话怎么走」，不碰 Electron / 窗口 / IPC 细节：
 *   - 音频从 voice-main.js 递进来（已校验的 16k/16bit/mono PCM 帧），一帧一帧喂给 ASR；
 *   - final 句按 segmentId 去重后攒成一轮用户话，静音 500ms 才发一次 LLM —— 同一句话不会跑两轮；
 *   - **用户一开口（partial 有内容）就插话**：取消正在流式的 LLM、取消当前合成、清空待合成句子，
 *     并让页面立刻停掉还在排队的播放；
 *   - 模型输出按句切开，串行合成（一次一句、一句一个 socket），每句合成完才起下一句；
 *     句子队列满了就**暂停读 LLM 流**（背压），绝不无界堆积；
 *   - 所有回调都带会话代号 / 轮次代号：旧的迟到回调既不能播声音，也不能改界面。
 *
 * 安全边界：
 *   - 凭据只在主进程内存里参与签名，绝不出现在事件、日志与错误文本里（文本由 tencent-speech.js 抹过一遍）；
 *   - 没有任何「换一家试试」的兜底：ASR / 模型 / TTS 谁失败就如实报错，不偷偷换 provider、不换模型；
 *   - chat 配置由 main.js 注入（用户已配置的接口 / 内置 preset），这里不写死地址，也不会把它回给渲染器。
 */
'use strict'

const MAX_SENTENCE_CHARS = 180      // 单句上限（腾讯 TTS 一次 ≤600 汉字；这里切得更碎，首句更快出声）
const MAX_SENTENCES = 8             // 待合成句子队列上限：满了就停读 LLM 流
const TURN_DEBOUNCE_MS = 500        // 最后一次 final 之后等这么久，把这一轮用户话发出去
const HISTORY_TURNS = 8             // 只带最近 8 轮上下文（长对话不能无界增长）
const MIN_BARGE_CHARS = 2           // partial 至少两个字才算「真的在说话」（单字毛刺不打断朗读）
const MAX_USER_CHARS = 2000
const LLM_TIMEOUT_MS = 90000
const AUDIO_CHUNK_BYTES = 3200      // 送渲染器的 PCM 分片（≤ 一帧；IPC 不搬大块）
const MAX_FINALS = 256              // segmentId 去重表上限（长会话只留最近这些句）
const MAX_SPOKEN = 64               // 「刚念过的句子」比对表上限
const ECHO_WINDOW_MS = 12000        // 念过之后这么久内的逐字相同识别结果按回声处理
/* 页面还在播多少：只按已送出去的字节折时长（16k / 16bit / mono = 32000 B/s）。
 * 渲染器的 ack（voice:tts-ack）只表示「收到了」，不表示「播完了」，不能拿它判播放结束。 */
const AUDIO_BYTES_PER_SEC = 32000
const PLAYBACK_TAIL_MS = 250         // 播放到底的保守余量：宁可多当一会儿「还在念」，也不和用户抢话
const DIALOGUE_SYSTEM = '你是用户电脑上的语音助手，正在和用户实时对话：用户说话，你回答，回答会被直接念出来。\n'
  + '硬性要求：\n'
  + '- 口语化，先给结论；默认 1~3 句，用户没要求展开就不要长篇大论；\n'
  + '- 写出来就能念：不要 Markdown、标题、列表符号、代码块、表格、表情符号、LaTeX；\n'
  + '- 数字、单位、英文缩写按日常口头说法写（例如「3.5 毫米」而不是「3.5mm」）；\n'
  + '- 证据不足就直说不知道、还缺什么，不编造；\n'
  + '- 不要复述用户的原话，也不要自报「作为语音助手」这类开场白。'

/** 句末：中文句号 / 感叹 / 问号 / 分号 / 换行。逗号不切（切碎了念起来会很碎）。 */
const SENTENCE_END = /[。！？!?；;\n]/

function speechSafe(text) {
  return String(text == null ? '' : text)
    .replace(/```[\s\S]*?```/g, ' ')          // 代码块整段不要（念代码没有意义）
    .replace(/[*_`#>]+/g, '')                 // Markdown 记号
    .replace(/\[(.*?)\]\((.*?)\)/g, '$1')     // 链接只留文字
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * 从 text 的 from 位置起找句末。超过 MAX_SENTENCE_CHARS 还没句末，就在最近的逗号 / 空格处硬切，
 * 保证单句不会无限长（TTS 有 600 汉字上限，一次念一句也更像人说话）。
 * 返回切点下标（含标点）或 -1（还不够一句）。
 */
function nextSentenceEnd(text, from) {
  const start = from || 0
  const hard = start + MAX_SENTENCE_CHARS
  const limit = Math.min(text.length, hard)
  for (let i = start; i < limit; i++) {
    if (SENTENCE_END.test(text[i])) return i
  }
  if (text.length <= hard) return -1
  // 硬切：优先在逗号 / 顿号 / 空格处断开
  for (let i = hard - 1; i > start + 40; i--) {
    if (/[，,、\s]/.test(text[i])) return i
  }
  return hard - 1
}

/**
 * @param {object} options
 * @param {object} [options.speech] 腾讯协议层（默认 tencent-speech.js）：{ createRecognition, synthesize }
 * @param {Function} options.emit 主进程 → 页面事件。**绝不要往里塞凭据**
 * @param {Function} options.sendAudio (Buffer) => boolean 把合成 PCM 送给页面（队列满返回 false）
 * @param {Function} options.getCredentials () => { appId, secretId, secretKey } | null
 * @param {Function} options.getChat () => { ready: () => boolean, cfg: () => object, style: () => string, modelLabel: () => string } | null
 * @param {Function} [options.getPrefs] () => { voiceType, speed, volume }
 * @param {Function} [options.fetchImpl] 测试注入；默认用全局 fetch
 * @param {number} [options.llmTimeoutMs] 测试注入的一轮超时；默认 LLM_TIMEOUT_MS
 */
function createDialogue(options) {
  const o = options || {}
  const speech = o.speech || require('./tencent-speech')
  const emit = typeof o.emit === 'function' ? o.emit : function () {}
  const sendAudio = typeof o.sendAudio === 'function' ? o.sendAudio : function () { return false }
  const getCredentials = typeof o.getCredentials === 'function' ? o.getCredentials : function () { return null }
  const getChat = typeof o.getChat === 'function' ? o.getChat : function () { return null }
  const getPrefs = typeof o.getPrefs === 'function' ? o.getPrefs : function () { return { voiceType: 101001, speed: 0, volume: 0 } }
  const fetchImpl = typeof o.fetchImpl === 'function' ? o.fetchImpl : function (url, init) { return fetch(url, init) }
  const llmTimeoutMs = Number(o.llmTimeoutMs) > 0 ? Number(o.llmTimeoutMs) : LLM_TIMEOUT_MS

  let gen = 0                // 会话代号：start / stop / 硬失败都推进；旧会话的回调一律作废
  let active = false
  let rec = null             // 腾讯 ASR 句柄
  let asrReady = false
  let state = 'idle'
  let turnGen = 0            // 轮次代号：插话 / 新一轮 / 停止都推进；旧的 LLM / TTS 回调一律作废
  let llmAbort = null
  let llmTimer = null        // 这一轮的 LLM 超时计时器：取消 / 结束都要立刻清掉，不留 90s 空转定时器
  /* 页面播放队列还排到哪里（绝对时刻，ms）。渲染器的 tts-ack 只回「收到了」，不回「播完了」，
   * 主进程拿不到真正的播放结束信号；所以按与渲染器同一条时间轴累计：
   * 每片 PCM 接在「已有队列末尾 / 现在」中较晚的那个之后，再留一点余量。这是上界，只会多算不会少算。 */
  let playEndAt = 0
  const spoken = []          // 刚念过的句子（{ n: 归一化文本, at }）：把逐字相同的识别结果判成回声
  let ttsCtl = null
  let ttsResolver = null     // 当前这一句合成的 resolve（取消时要主动放行，否则 pump 永远等下去）
  let sentences = []
  let pumping = false
  let roomResolve = null     // 队列满时挂起的「等有空位」
  let pendingUser = ''
  let userTimer = null
  let history = []
  const finals = new Map()   // segmentId → 已处理过的终态文本（同一句只跑一轮）
  let lastError = ''
  const stats = { frames: 0, dropped: 0, utterances: 0, turns: 0 }

  function post(ev) { try { emit(ev) } catch {} }
  function setState(next, message) { state = next; post({ type: 'state', state: next, message: message || '' }) }
  function notice(kind, text) { post({ type: 'notice', kind: kind, text: String(text || '') }) }
  function alive(myGen) { return active && myGen === gen }

  /* ---------------- 轮次取消（插话 / 新一轮 / 停止共用） ---------------- */

  function cancelTurn(reason) {
    turnGen++
    const ab = llmAbort
    llmAbort = null
    if (ab) { try { ab.abort() } catch {} }
    const timer = llmTimer
    llmTimer = null
    clearTimeout(timer)
    const ctl = ttsCtl
    ttsCtl = null
    if (ctl) { try { ctl.cancel() } catch {} }
    const done = ttsResolver
    ttsResolver = null
    sentences = []
    const room = roomResolve
    roomResolve = null
    if (room) { try { room() } catch {} }
    if (done) { try { done() } catch {} }
    // 合成做完不等于页面播完：页面队列里可能还剩几秒旧音频。插话 / 新一轮都必须让页面清掉它，
    // 否则旧回答会和新一轮的声音叠在一起（静默重叠）。停止 / 拆会话由页面自己的停止流程清队列，
    // 不额外发插话事件（那条事件会改页面提示，停止时不该出现「听到你说话了」）。
    // 只有页面确实被要求清队列时才归零估计；否则留着，让下一次开口仍能看出还有没播完的旧音频。
    if (reason === 'barge-in' || (reason === 'new-turn' && playbackPending())) {
      post({ type: 'barge-in' })
      playEndAt = 0
    } else if (reason === 'teardown') {
      playEndAt = 0
    }
  }

  /** 页面可能还在播上一轮的 PCM（保守上界：多算一会儿，绝不和用户抢话）。 */
  function playbackPending() {
    return playEndAt > 0 && Date.now() < playEndAt + PLAYBACK_TAIL_MS
  }

  function turnBusy() {
    return !!(llmAbort || ttsCtl || pumping || sentences.length || playbackPending())
  }

  /* ---------------- ASR 回调 ---------------- */

  function onPartial(myGen, text) {
    if (!alive(myGen)) return
    if (String(text || '').replace(/\s+/g, '').length < MIN_BARGE_CHARS) return
    // 有用户声音在进来，而模型还在写 / 还在念：立刻让位（取消 LLM 流、当前合成与待合成队列）。
    // 边界：这里只靠浏览器声学回声消除（echoCancellation）把自家播报从输入里拿掉 ——
    // 没有做「识别文本 → 比对播报文本」的二次剔除。AEC 在不同机器 / 外放音量下效果不同，
    // 没有实测前不能保证扬声器自己的声音不会被当作插话（那会把回答打断成来回互怼）。
    if (!turnBusy()) return
    cancelTurn('barge-in')
    setState('listening', '听到你说话了，已停下刚才的回答。')
  }

  function onFinal(myGen, text, segmentId) {
    if (!alive(myGen)) return
    const clean = speechSafe(text)
    if (!clean) return
    const key = String(segmentId == null ? '' : segmentId)
    if (key && finals.get(key) === clean) return     // 同一句重复 final：不再算一轮
    if (key) {
      finals.set(key, clean)
      // 有界：长会话的 segmentId 会一直增长，只留最近 MAX_FINALS 个（Map 按插入序，删最旧的那个 key）
      while (finals.size > MAX_FINALS) finals.delete(finals.keys().next().value)
    }
    if (isOwnEcho(clean)) return                     // 与刚念过的句子逐字相同：当作自家播报的回声
    pendingUser = (pendingUser ? pendingUser + ' ' : '') + clean
    if (pendingUser.length > MAX_USER_CHARS) pendingUser = pendingUser.slice(-MAX_USER_CHARS)
    clearTimeout(userTimer)
    // 静音 500ms 才算这轮说完：连续几句话会攒成同一轮，不会一句一答
    userTimer = setTimeout(() => {
      userTimer = null
      const text2 = pendingUser.trim()
      pendingUser = ''
      if (!text2 || !alive(myGen)) return
      runTurn(myGen, text2).catch((e) => finishTurnError(myGen, e))
    }, TURN_DEBOUNCE_MS)
  }

  /* 回声辅助判据：**只认逐字相同**（去空白 / 标点后）。
   * 不做相似度、不做前缀 / 包含匹配 —— 那会把用户真正的短插话（「等一下」「为什么」）吞掉，
   * 比偶尔把一句回声当成新问题更糟。命中时只是不把它算成新一轮，界面上的识别文字照常显示。 */
  function normForEcho(text) {
    return String(text || '').replace(/[\s，。、！？；：,\.!\?;:'"“”‘’（）()《》【】\-—…]/g, '')
  }

  function rememberSpoken(text) {
    const n = normForEcho(text)
    if (!n) return
    spoken.push({ n: n, at: Date.now() })
    while (spoken.length > MAX_SPOKEN) spoken.shift()
  }

  function isOwnEcho(text) {
    const n = normForEcho(text)
    if (!n) return false
    const cut = Date.now() - ECHO_WINDOW_MS
    while (spoken.length && spoken[0].at < cut) spoken.shift()
    return spoken.some((s) => s.n === n)
  }

  /* ---------------- 句末切分 + TTS 串行队列 ---------------- */

  function enqueueSentence(myTurn, raw) {
    const text = speechSafe(raw)
    if (!text) return Promise.resolve()
    if (myTurn !== turnGen) return Promise.resolve()
    // 队列满：等合成腾出位置再塞（背压到 LLM 流，而不是把内存堆满）
    const put = () => {
      sentences.push(text)
      pump(myTurn)
      return Promise.resolve()
    }
    if (sentences.length < MAX_SENTENCES) return put()
    return new Promise((resolve) => {
      roomResolve = () => { roomResolve = null; resolve(put()) }
    })
  }

  /** 串行合成：一次只开一个 TTS socket；每句结束（或失败 / 取消）才起下一句。 */
  async function pump(myTurn) {
    if (pumping) return
    pumping = true
    try {
      while (myTurn === turnGen && sentences.length) {
        const text = sentences.shift()
        const room = roomResolve
        roomResolve = null
        if (room) { try { room() } catch {} }
        setState('speaking', '')
        await synthOne(text, myTurn)
      }
    } finally {
      pumping = false
      if (myTurn === turnGen && !sentences.length && !ttsCtl) setState('listening', '')
    }
  }

  function synthOne(text, myTurn) {
    return new Promise((resolve) => {
      let done = false
      const finish = (err) => {
        if (done) return
        done = true
        ttsResolver = null
        ttsCtl = null
        if (err && myTurn === turnGen) {
          lastError = err
          notice('error', '语音合成失败：' + err)
          // 合成失败就整轮停下（不换音色、不换服务重试），把已经说出的部分留在界面上
          sentences = []
          turnGen++
        }
        resolve()
      }
      ttsResolver = () => finish(null)
      let creds = null
      try {
        creds = getCredentials()
      } catch { creds = null }
      if (!creds) { finish('腾讯云语音凭据读不出来（可能钥匙串被重置）；请在对话设置里重新保存'); return }
      const prefs = (getPrefs && getPrefs()) || {}
      // 这句马上要被念出来：登记进回声比对表（只比对逐字相同的短窗口，不做相似度）
      if (myTurn === turnGen) rememberSpoken(text)
      let handle = null
      try {
        handle = speech.synthesize({
          credentials: creds,
          text: text,
          voiceType: prefs.voiceType,
          speed: prefs.speed,
          onPcm: (pcm) => {
            if (myTurn !== turnGen || !alive(gen)) return
            // 页面播放队列满了（在途配额打满）：这是「播不出去」，不是可以静默丢弃的拥塞 ——
            // 立刻掐掉这句合成并按失败报给用户，绝不一边丢片一边继续念。
            if (!writeAudio(pcm)) {
              if (handle) { try { handle.cancel() } catch {} }
              finish('播放跟不上（页面音频队列已满），这段回答已中断。检查系统输出设备后再试。')
            }
          },
          onFinished: () => finish(null),
          onError: (message) => finish(message || '未知错误')
        })
        // onPcm 若在返回前就触发了失败，这句已经结束：返回来的句柄必须当场取消，别留在 ttsCtl 里
        if (done) { try { handle.cancel() } catch {} ; return }
        ttsCtl = handle
        stats.utterances++
      } catch (e) {
        finish((e && e.message) || String(e))
      }
    })
  }

  /**
   * 合成 PCM 分片送给页面。返回 false = 有一片没能送出（在途配额满 / 窗口没了），调用方必须停止。
   * 同时累计「页面队列排到哪里」：渲染器把每片接在上一次的结束时刻之后，这里照同一条规则推，
   * 只用于判断插话时页面是否还有没播完的旧音频。
   */
  function writeAudio(pcm) {
    const buf = Buffer.isBuffer(pcm) ? pcm : Buffer.from(pcm || [])
    for (let off = 0; off < buf.length; off += AUDIO_CHUNK_BYTES) {
      const part = buf.subarray(off, Math.min(buf.length, off + AUDIO_CHUNK_BYTES))
      if (!sendAudio(Buffer.from(part))) { stats.dropped++; return false }
      trackPlayback(part.length)
    }
    return true
  }

  /** 按与渲染器相同的时间轴累计播放结束时刻（队尾已过去就从现在续上，否则接在队尾后）。 */
  function trackPlayback(bytes) {
    const now = Date.now()
    const start = Math.max(now, playEndAt)
    playEndAt = start + (bytes / AUDIO_BYTES_PER_SEC) * 1000
  }
  /* ---------------- 一轮 LLM ---------------- */

  async function runTurn(myGen, userText) {
    if (!alive(myGen)) return
    const chat = getChat && getChat()
    if (!chat || typeof chat.cfg !== 'function') {
      notice('error', '对话模型没有接上（主进程没注入 chat 配置），这次回答发不出去。')
      return
    }
    if (typeof chat.ready === 'function' && !chat.ready()) {
      notice('error', '还没有可用的对话模型：先到设置里填好接口地址 / Key（或指定模型），再来对话。')
      return
    }
    cancelTurn('new-turn')
    const myTurn = turnGen
    stats.turns++
    llmAbort = new AbortController()
    const ac = llmAbort
    /* 超时 = 看门狗：到点**自己**如实报超时并结束这一轮（cancelTurn 会 abort 掉 fetch）。
     * 不依赖 fetch 一定因 abort 抛 AbortError —— 那样一旦对流没反应，界面就永远停在「正在想…」。
     * unref 让这个定时器不拖住进程退出。 */
    const timer = setTimeout(() => {
      finishTurnError(myGen, new Error('对话模型 ' + Math.round(llmTimeoutMs / 1000) + ' 秒没有回应'))
    }, llmTimeoutMs)
    if (typeof timer.unref === 'function') timer.unref()
    llmTimer = timer
    setState('thinking', '')
    post({ type: 'text', role: 'user', text: userText, final: true })

    history.push({ role: 'user', content: userText })
    if (history.length > HISTORY_TURNS * 2) history = history.slice(-HISTORY_TURNS * 2)

    const cfg = chat.cfg() || {}
    const style = typeof chat.style === 'function' ? String(chat.style() || '') : ''
    const system = DIALOGUE_SYSTEM
      + (style ? '\n\n讲法（语音场景：忽略其中关于小标题 / 换行排版的要求，其余照做）：\n' + style : '')
    const body = {
      model: cfg.model,
      stream: true,
      max_tokens: 800,
      messages: [{ role: 'system', content: system }].concat(history)
    }
    // 思考强度等参数照抄用户既有设置；只并入不覆盖（cfg 由 main.js 组装）
    for (const key of Object.keys(cfg)) {
      if (key === 'baseUrl' || key === 'apiKey' || key === 'model') continue
      if (cfg[key] === undefined) continue
      body[key] = cfg[key]
    }

    let full = ''
    let rest = ''
    try {
      const res = await fetchImpl(String(cfg.baseUrl || '').replace(/\/+$/, '') + '/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + String(cfg.apiKey || '') },
        body: JSON.stringify(body),
        signal: ac.signal
      })
      if (!res || !res.ok) {
        const text = res && typeof res.text === 'function' ? await res.text().catch(() => '') : ''
        throw new Error('接口 ' + ((res && res.status) || '错误') + ' ' + String(text || '').slice(0, 160))
      }
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (myTurn !== turnGen) { try { reader.cancel() } catch {} break }
        buf += decoder.decode(value, { stream: true })
        let nl
        while ((nl = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, nl).trim()
          buf = buf.slice(nl + 1)
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (!data || data === '[DONE]') continue
          let delta = ''
          try {
            const j = JSON.parse(data)
            const d = j.choices && j.choices[0] && j.choices[0].delta
            delta = (d && d.content) || ''
          } catch {}
          if (!delta) continue
          full += delta
          rest += delta
          post({ type: 'text', role: 'assistant', text: full, delta: delta, final: false })
          let cut
          while ((cut = nextSentenceEnd(rest, 0)) >= 0) {
            const piece = rest.slice(0, cut + 1)
            rest = rest.slice(cut + 1)
            await enqueueSentence(myTurn, piece)
          }
        }
      }
      if (myTurn !== turnGen) return          // 插话 / 停止：这一轮的尾巴不要了
      if (rest.trim()) await enqueueSentence(myTurn, rest)
      rest = ''
      if (!full.trim()) throw new Error('模型没有返回内容')
      history.push({ role: 'assistant', content: full })
      post({ type: 'text', role: 'assistant', text: full, final: true })
      if (!sentences.length && !pumping) setState('listening', '')
    } catch (e) {
      // 插话 / 新一轮 / 停止 / 看门狗超时都已经推进过 turnGen：那一轮由发起方负责收尾，
      // 这里只做静默退场（同一个 abort 会在 await 处再抛一次，绝不能重复报错）。
      if (myTurn !== turnGen) return
      // 剩下走到这里的失败都是真失败（接口错 / 流断 / 被环境中断）：如实报，并回到「在听」，
      // 绝不让界面停在「正在想…」等一个永远不会来的回答。
      finishTurnError(myGen, e && e.name === 'AbortError' ? new Error('请求被中断') : e)
    } finally {
      clearTimeout(timer)
      if (llmTimer === timer) llmTimer = null
      if (llmAbort === ac) llmAbort = null
    }
  }

  /**
   * 一轮真的失败（接口错 / 超时）：如实报错、把这一轮的残留取消掉，并回到「在听」——
   * 绝不把界面永远留在「正在想…」。已经念出去的部分留在对话里，不再继续念剩下的。
   */
  function finishTurnError(myGen, err) {
    if (!alive(myGen)) return
    const message = (err && err.message) || String(err || '未知错误')
    lastError = message
    cancelTurn('error')
    setState('listening', '这一轮回答失败：' + message)
  }

  /* ---------------- 会话生命周期 ---------------- */

  function start(opts) {
    const args = opts || {}
    if (active) return { ok: false, error: '已经在对话中：先停止再开始' }
    if (args.consent !== true) return { ok: false, error: '需要先勾选「同意把语音上传给腾讯云并发送给对话模型」' }
    let creds = null
    try { creds = getCredentials() } catch { creds = null }
    if (!creds) {
      return { ok: false, error: '还没有可用的腾讯云语音凭据（AppId / SecretId / SecretKey）：先在对话设置里保存' }
    }
    const chat = getChat && getChat()
    if (!chat || typeof chat.cfg !== 'function' || (typeof chat.ready === 'function' && !chat.ready())) {
      return { ok: false, error: '还没有可用的对话模型：先到设置里配好chat接口，再开始对话' }
    }
    gen++
    const myGen = gen
    active = true
    finals.clear()
    spoken.length = 0       // 上一次会话念过的句子不参与这一轮的回声判定
    playEndAt = 0           // 新会话：页面在开始前已经清了自己的播放队列

    pendingUser = ''
    history = []
    lastError = ''
    stats.frames = 0
    stats.dropped = 0
    stats.utterances = 0
    stats.turns = 0
    setState('connecting', '')
    let handle = null
    try {
      handle = speech.createRecognition({
        credentials: creds,
        onReady: () => {
          if (!alive(myGen)) return
          asrReady = true
          setState('listening', '')
        },
        onText: (ev) => {
          if (!alive(myGen) || !ev) return
          if (ev.final) onFinal(myGen, ev.text, ev.segmentId)
          else onPartial(myGen, ev.text)
        },
        onError: (message) => {
          if (!alive(myGen)) return
          lastError = String(message || '语音识别失败')
          teardownSession()
          setState('error', lastError)
        },
        onFinished: () => {
          if (!alive(myGen)) return
          teardownSession()
          setState('idle', '')
        }
      })
    } catch (e) {
      active = false
      const message = (e && e.message) || String(e)
      lastError = message
      setState('error', message)
      return { ok: false, error: message }
    }
    rec = handle
    return { ok: true }
  }

  /** 只释放会话资源（不动事件状态）：stop / 硬失败 / 关窗都走这里。 */
  function teardownSession() {
    active = false
    asrReady = false
    clearTimeout(userTimer)
    userTimer = null
    pendingUser = ''
    cancelTurn('teardown')
    const handle = rec
    rec = null
    if (handle) { try { handle.cancel() } catch {} }
    history = []
    finals.clear()
  }

  function stop() {
    if (!active && !rec) return { ok: true, state: 'idle' }
    teardownSession()
    setState('idle', '')
    return { ok: true, state: 'idle' }
  }

  /** 一帧麦克风 PCM（已由 voice-main 校验：≤6400 字节、偶数字节）。就绪前一律不收。 */
  function sendPcm(frame) {
    if (!active || !rec || !asrReady) return false
    stats.frames++
    let ok = false
    try { ok = rec.sendPcm(frame) === true } catch { ok = false }
    if (!ok) stats.dropped++
    return ok
  }

  function status() {
    return {
      state: state,
      active: active,
      asrReady: asrReady,
      stats: Object.assign({}, stats),
      error: lastError
    }
  }

  function destroy() {
    teardownSession()
    state = 'idle'
  }

  return {
    start: start,
    stop: stop,
    sendPcm: sendPcm,
    status: status,
    destroy: destroy,
    // 诊断 / 测试用
    _hasTurn: turnBusy
  }
}

module.exports = {
  createDialogue: createDialogue,
  nextSentenceEnd: nextSentenceEnd,
  speechSafe: speechSafe,
  DIALOGUE_SYSTEM: DIALOGUE_SYSTEM,
  LIMITS: {
    MAX_SENTENCE_CHARS: MAX_SENTENCE_CHARS,
    MAX_SENTENCES: MAX_SENTENCES,
    TURN_DEBOUNCE_MS: TURN_DEBOUNCE_MS,
    HISTORY_TURNS: HISTORY_TURNS,
    AUDIO_CHUNK_BYTES: AUDIO_CHUNK_BYTES
  }
}
