// voice-main：主进程控制器的安全与生命周期回归（假 electron、假 socket，无真实窗口 / 无网络）。
// 只覆盖「真出过问题就会出事」的行为：访问控制、帧校验与限速、关窗即取消、凭据不出主进程。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { pathToFileURL } = require('node:url')

function fakeIpc() {
  const handlers = new Map()
  const listeners = new Map()
  return {
    handlers: handlers,
    listeners: listeners,
    handle: (ch, fn) => handlers.set(ch, fn),
    on: (ch, fn) => listeners.set(ch, fn)
  }
}

/** 假 socket：只记录送出，把 open 手动派发进来；terminate 记下来供断言。 */
function makeSocketClass() {
  const made = []
  class FakeWS {
    constructor(url, opts) {
      this.url = url
      this.opts = opts || {}
      this.readyState = 0
      this.bufferedAmount = 0
      this.sent = []
      this.handlers = new Map()
      made.push(this)
    }
    on(ev, fn) { this.handlers.set(ev, (this.handlers.get(ev) || []).concat([fn])); return this }
    emit(ev, a, b) { for (const fn of this.handlers.get(ev) || []) fn(a, b) }
    send(d) { if (this.readyState === 1) this.sent.push(Buffer.from(d)) }
    terminate() { this.readyState = 3; this.terminated = true }
    close() { this.readyState = 3 }
    open() { this.readyState = 1; this.emit('open') }
  }
  FakeWS.made = made
  FakeWS.last = () => made[made.length - 1]
  return FakeWS
}

/** 极简 BrowserWindow 替身：记录送出与关闭，webContents 自带主 frame。 */
function makeWindowClass() {
  const made = []
  class FakeWindow {
    constructor(opts) {
      this.opts = opts || {}
      this.events = new Map()
      this.destroyed = false
      this.sent = []
      // session 处理器要能被测试直接调：语音窗口的权限 / 回环授权都挂在这条 session 上
      const session = {}
      const wc = {
        id: made.length + 1,
        session: session,
        send: (ch, payload) => this.sent.push({ ch: ch, payload: payload }),
        mainFrame: { url: '' },
        getURL: () => wc.mainFrame.url
      }
      session.setPermissionRequestHandler = (fn) => { session.permissionRequest = fn }
      session.setPermissionCheckHandler = (fn) => { session.permissionCheck = fn }
      session.setDisplayMediaRequestHandler = (fn) => { session.displayRequest = fn }
      this.webContents = wc
      made.push(this)
    }
    on(ev, fn) { this.events.set(ev, (this.events.get(ev) || []).concat([fn])); return this }
    once(ev, fn) { return this.on(ev, fn) }
    emit(ev, a, b) { for (const fn of this.events.get(ev) || []) fn(a, b) }
    loadFile(p) {
      this.loaded = p
      // 真机 loadFile 之后主 frame 的 url 就是这个文件的 file:// 地址（含真实目录，不是裸 file:///voice.html）
      this.webContents.mainFrame.url = pathToFileURL(p).href
    }
    getBounds() { return { x: 100, y: 100, width: 420, height: 560 } }
    setBounds() {}
    setAlwaysOnTop() {}
    isDestroyed() { return this.destroyed }
    show() {}
    focus() {}
    close() { this.destroyed = true; this.emit('closed') }
  }
  FakeWindow.made = made
  return FakeWindow
}

function boot() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-voice-main-'))
  const ipc = fakeIpc()
  const FakeWindow = makeWindowClass()
  const Socket = makeSocketClass()
  const safeState = { available: true }
  const safeStorage = {
    isEncryptionAvailable: () => safeState.available,
    encryptString: (t) => Buffer.from('enc:' + String(t), 'utf8'),
    decryptString: (b) => {
      const s = Buffer.from(b).toString('utf8')
      if (!s.startsWith('enc:')) throw new Error('bad ciphertext')
      return s.slice(4)
    }
  }
  // gate 非空时 askForMediaAccess 会先等它：用来复现「系统权限弹窗还开着」的窗口期
  const micState = { status: 'granted', askGranted: true, asked: 0, gate: null }
  const screenState = { status: 'granted' }
  const systemPreferences = {
    // 麦克风与「屏幕与系统音频录制」是两套授权：替身按类型分开，别让一边的假状态掩盖另一边
    getMediaAccessStatus: (kind) => (kind === 'screen' ? screenState.status : micState.status),
    // 真机上 askForMediaAccess 之后状态会落成 granted / denied：替身照这个来
    askForMediaAccess: async () => {
      micState.asked++
      if (micState.gate) await micState.gate
      micState.status = micState.askGranted ? 'granted' : 'denied'
      return micState.askGranted
    }
  }
  // desktopCapturer 替身：sources 可控、getSources 可被 gate 拦住以复现「异步回来前窗口已关」
  const capture = { sources: [{ id: 'screen:1:0', name: 'Built-in', display_id: '77' }, { id: 'screen:2:0', name: 'External', display_id: '88' }], calls: [], gate: null }
  const desktopCapturer = {
    getSources: async (opts) => {
      capture.calls.push(opts)
      if (capture.gate) await capture.gate
      return capture.sources
    }
  }
  delete require.cache[require.resolve('../voice-main')]
  const vm = require('../voice-main')
  vm.init({
    app: { getPath: () => dir, focus: () => {} },
    path: __dirname,
    ipcMain: ipc,
    BrowserWindow: FakeWindow,
    screen: {
      getPrimaryDisplay: () => ({ id: 77, workArea: { x: 0, y: 0, width: 1440, height: 900 } })
    },
    desktopCapturer: desktopCapturer,
    shell: { openExternal: () => {} },
    safeStorage: safeStorage,
    systemPreferences: systemPreferences,
    APP_NAME: 'Ling',
    layer: 2,
    modeLog: () => {},
    hideFromMissionControl: () => {},
    revealWin: () => {},
    fadeClose: (w) => w.close(),
    showSettings: () => {},
    isSettingsSender: (e) => !!(e && e.isSettings),
    orbBounds: () => ({ x: 1000, y: 400, width: 80, height: 80 }),
    WebSocketImpl: Socket
  })
  return {
    vm: vm, ipc: ipc, FakeWindow: FakeWindow, Socket: Socket, dir: dir,
    safeState: safeState, micState: micState, screenState: screenState, safeStorage: safeStorage, capture: capture
  }
}

function cleanup(ctx) {
  try { ctx.vm.shutdown() } catch {}
  try { fs.rmSync(ctx.dir, { recursive: true, force: true }) } catch {}
}

const stranger = { isSettings: false, sender: { id: 901 }, senderFrame: { url: 'file:///answer.html' } }
function settingsSender() { return { isSettings: true, sender: { id: 900 }, senderFrame: { url: 'file:///settings.html' } } }

/** 配好豆包 Key 并打开转写窗口，返回「像页面那样」的 sender 与窗口。 */
async function openVoice(ctx) {
  await ctx.ipc.handlers.get('voice:select-provider')(settingsSender(), 'doubao')
  await ctx.ipc.handlers.get('voice:save-key')(settingsSender(), { provider: 'doubao', apiKey: 'db-secret' })
  await ctx.ipc.handlers.get('voice:open')(settingsSender())
  const win = ctx.FakeWindow.made[0]
  return { win: win, sender: { sender: win.webContents, senderFrame: win.webContents.mainFrame } }
}

test('加密不可用：保存被拒，且不会落一个明文/空壳文件', async () => {
  const ctx = boot()
  try {
    ctx.safeState.available = false
    const r = await ctx.ipc.handlers.get('voice:save-key')(settingsSender(), { provider: 'doubao', apiKey: 'db-secret' })
    assert.strictEqual(r.ok, false)
    assert.match(r.error, /加密不可用/)
    assert.strictEqual(fs.existsSync(path.join(ctx.dir, 'voice-asr.json')), false)
  } finally { cleanup(ctx) }
})

test('开始必须显式同意；没有 Key 一律拒绝', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    const noConsent = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: false, source: 'mic' })
    assert.strictEqual(noConsent.ok, false)
    assert.match(noConsent.error, /同意/)

    const ok = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    assert.strictEqual(ok.ok, true)
    // 不叠会话
    assert.match((await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })).error, /已经在转写中/)

    await ctx.ipc.handlers.get('voice:cancel')(v.sender)
    await ctx.ipc.handlers.get('voice:delete-key')(settingsSender(), 'doubao')
    const noKey = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    assert.strictEqual(noKey.ok, false)
    assert.match(noKey.error, /API Key/)
  } finally { cleanup(ctx) }
})

// 密文还在（hasKey=true）、系统加密也可用，却解不开：此时报「还没有填」与设置里的「Key 已保存」直接矛盾。
test('已保存的 Key 解不开：start 说「读不出来 / 要重新保存」，不说「还没有填」', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)      // 真加密落盘一把豆包 Key
    // 换了渠道 / 别的版本共用 userData / 钥匙串被重置：密文原样还在，本机却解不开
    ctx.safeStorage.decryptString = () => { throw new Error('bad ciphertext') }
    const st = await ctx.ipc.handlers.get('voice:status')(settingsSender())
    assert.strictEqual(st.hasKey, true, '密文还在，hasKey 契约不变')
    assert.strictEqual(st.encryptionAvailable, true)

    const bad = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    assert.strictEqual(bad.ok, false)
    assert.match(bad.error, /读不出来/)
    assert.match(bad.error, /重新保存/)
    assert.doesNotMatch(bad.error, /还没有填/)
    assert.strictEqual(ctx.Socket.made.length, 0, '读不出 Key 就不能建连接')

    // 真的没存过：还是原来那句「还没有填」，不能混成解密失败
    await ctx.ipc.handlers.get('voice:delete-key')(settingsSender(), 'doubao')
    const none = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    assert.strictEqual(none.ok, false)
    assert.match(none.error, /还没有填/)
  } finally { cleanup(ctx) }
})

test('麦克风被拒时 start 明确失败，连 socket 都不该建', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    ctx.micState.status = 'not-determined'
    ctx.micState.askGranted = false
    const r = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    assert.strictEqual(r.ok, false)
    assert.match(r.error, /麦克风权限/)
    assert.strictEqual(ctx.Socket.made.length, 0)
  } finally { cleanup(ctx) }
})

test('关掉转写窗口＝立刻停止：会话归 idle、socket 收掉、之后推音频无效果', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, source: 'mic' })
    ctx.Socket.last().open()
    assert.notStrictEqual(ctx.vm._status().session.state, 'idle')
    const sock = ctx.Socket.last()
    v.win.close()
    assert.strictEqual(ctx.vm._status().session.state, 'idle')
    assert.strictEqual(sock.terminated, true, 'socket 不能留在后台')
    ctx.ipc.listeners.get('voice:audio')(v.sender, Buffer.alloc(3200, 1))
  } finally { cleanup(ctx) }
})

test('等待麦克风权限期间取消：那次启动作废，权限回来也不再建会话（不偷偷上传）', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    // 权限弹窗挂着：start 会真的停在 await 上
    ctx.micState.status = 'not-determined'
    let release = null
    ctx.micState.gate = new Promise((r) => { release = r })
    const startP = ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, provider: 'doubao', source: 'mic' })
    // 等一拍，确认它确实进了「等权限」的状态（此时还没建任何连接）
    await new Promise((r) => setTimeout(r, 10))
    assert.strictEqual(ctx.Socket.made.length, 0, '等权限期间不该已经建连接')

    const stopR = await ctx.ipc.handlers.get('voice:stop')(v.sender)
    assert.strictEqual(stopR.state, 'cancelled', '等待期间的停止要真正撤掉这次启动')

    ctx.micState.gate = null
    release()
    const r = await startP
    assert.strictEqual(r.ok, false, '权限回来时这次启动已经作废')
    assert.strictEqual(r.cancelled, true)
    assert.strictEqual(ctx.Socket.made.length, 0, '作废的启动绝不能连服务器')
    assert.strictEqual(ctx.vm._status().session.state, 'idle')
  } finally { cleanup(ctx) }
})

test('换服务商会把正在转写的会话停掉（界面显示 B 就绝不还在传 A）', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    await ctx.ipc.handlers.get('voice:save-key')(settingsSender(), { provider: 'alibaba', apiKey: 'ali-secret' })
    await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, provider: 'doubao', source: 'mic' })
    ctx.Socket.last().open()
    assert.notStrictEqual(ctx.vm._status().session.state, 'idle')
    const oldSock = ctx.Socket.last()
    await ctx.ipc.handlers.get('voice:select-provider')(settingsSender(), 'alibaba')
    assert.strictEqual(ctx.vm._status().session.state, 'idle', '换服务商必须立刻停掉旧会话')
    assert.strictEqual(oldSock.terminated, true)
    assert.strictEqual(ctx.vm._status().provider, 'alibaba')
  } finally { cleanup(ctx) }
})

/* ---------------- 系统音频（会议对面）的边界 ---------------- */

/** 像 voice.html 那样发一次 getDisplayMedia：主进程的 display 处理器回什么都记下来。 */
function askDisplay(win, over) {
  const req = Object.assign({
    frame: win.webContents.mainFrame,
    securityOrigin: 'file://',
    videoRequested: true,
    audioRequested: true,
    userGesture: true
  }, over || {})
  return new Promise((resolve) => {
    let done = false
    win.webContents.session.displayRequest(req, (streams) => {
      if (done) return
      done = true
      resolve(streams)
    })
  })
}

test('system 不要求麦克风：麦克风被拒也能开始转写', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    ctx.micState.status = 'denied'
    const r = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, provider: 'doubao', source: 'system' })
    assert.strictEqual(r.ok, true, '只采系统音频时不该因为麦克风被拒而失败')
    assert.strictEqual(ctx.micState.asked, 0, 'system 连权限弹窗都不该发')
    assert.strictEqual(ctx.Socket.made.length, 1)
  } finally { cleanup(ctx) }
})

test('mixed 需要麦克风：麦克风被拒就明确失败，不连服务器', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    ctx.micState.status = 'denied'
    const r = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, provider: 'doubao', source: 'mixed' })
    assert.strictEqual(r.ok, false)
    assert.match(r.error, /麦克风权限/)
    assert.strictEqual(ctx.Socket.made.length, 0)
  } finally { cleanup(ctx) }
})

test('非法 / 缺省 source 一律不开始：不连 socket，也不当 mic 处理', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    for (const source of [undefined, '', 'microphone', 'MIC', 'system+mic', 7]) {
      const r = await ctx.ipc.handlers.get('voice:start')(v.sender, { consent: true, provider: 'doubao', source: source })
      assert.strictEqual(r.ok, false, 'source=' + String(source) + ' 应被拒')
      assert.match(r.error, /来源/)
    }
    assert.strictEqual(ctx.Socket.made.length, 0)
    assert.strictEqual(ctx.vm._status().session.state, 'idle')
  } finally { cleanup(ctx) }
})

test('display 捕获只给语音窗口主 frame + 手势，audio 固定 loopback 且不静音', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    const streams = await askDisplay(v.win)
    assert.strictEqual(streams.audio, 'loopback', '不能 loopbackWithMute：会议里对方的声音要能继续外放')
    assert.ok(streams.video && streams.video.id, '画面轨只为让系统音频活着')
    // 只按 0 缩略图取屏：不搬位图，也就不可能把屏幕内容读进来
    assert.deepStrictEqual(ctx.capture.calls[0], { types: ['screen'], thumbnailSize: { width: 0, height: 0 } })
    assert.strictEqual(ctx.capture.calls[0].types.indexOf('window'), -1, '不给窗口源：只采整屏回环')
    assert.strictEqual(streams.video.id, 'screen:1:0', '优先主显示器那块屏')
  } finally { cleanup(ctx) }
})

test('display 捕获拒绝：别的目录同名 voice.html / 非语音页面 / 子 frame / 无手势 / 外部窗口', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    const realUrl = v.win.webContents.mainFrame.url
    // 同一目录之外的同名文件（file:///tmp/voice.html）以前靠 basename 就能骗到回环：必须拒
    v.win.webContents.mainFrame.url = 'file:///tmp/voice.html'
    assert.deepStrictEqual(await askDisplay(v.win), {}, '只认 loadFile 的那一个路径，别处的同名 voice.html 不算')

    v.win.webContents.mainFrame.url = 'file:///answer.html'
    assert.deepStrictEqual(await askDisplay(v.win), {}, '非语音页面不给')

    v.win.webContents.mainFrame.url = realUrl
    // 地址对但 frame 不是主 frame（iframe）：对象不同就拒
    const iframe = await askDisplay(v.win, { frame: { url: realUrl } })
    assert.deepStrictEqual(iframe, {}, 'iframe / 子 frame 不给')

    const noGesture = await askDisplay(v.win, { userGesture: false })
    assert.deepStrictEqual(noGesture, {}, '没有用户手势不给：系统录音标记必须对应用户动作')

    const noAudio = await askDisplay(v.win, { audioRequested: false })
    assert.deepStrictEqual(noAudio, {}, '没要系统音频就不该拿到回环')

    // 别的窗口（哪怕它也在同一进程里）用自己的 session 请求：不能借语音窗口的处理器
    const other = new ctx.FakeWindow({ webPreferences: {} })
    other.loadFile(path.join(__dirname, 'voice.html'))
    const strangerAsk = await askDisplay(v.win, { frame: other.webContents.mainFrame })
    assert.deepStrictEqual(strangerAsk, {}, '外部窗口的 frame 不给授权')

    assert.strictEqual(ctx.capture.calls.length, 0, '全都被拒时连 getSources 都不该调')
  } finally { cleanup(ctx) }
})

test('屏幕与系统音频没授权：不提前拦（否则永远等不到弹窗），拿不到源时明确指向系统设置', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    ctx.screenState.status = 'denied'
    ctx.capture.sources = []          // 系统没给源：getSources 返回空
    assert.deepStrictEqual(await askDisplay(v.win), {}, '拿不到源就不能给一条永远静音的流')
    assert.strictEqual(ctx.capture.calls.length, 1, '首次 denied 也要求这次 getSources 去触发授权弹窗')
    const notice = v.win.sent.filter((s) => s.ch === 'voice:notice').pop()
    assert.ok(notice, '拒绝原因要送到页面，不能只写主进程日志')
    assert.match(notice.payload.text, /系统设置/)
    assert.match(notice.payload.text, /屏幕与系统音频/)
  } finally { cleanup(ctx) }
})

test('取源回来前窗口已关：那次 display 请求作废，绝不给新窗口授权', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    let release = null
    ctx.capture.gate = new Promise((r) => { release = r })
    const pending = askDisplay(v.win)
    await new Promise((r) => setTimeout(r, 10))
    v.win.close()                 // 用户关窗：这一轮的采集授权必须作废
    ctx.capture.gate = null
    release()
    assert.deepStrictEqual(await pending, {}, '关掉的窗口不能拿到回环')
  } finally { cleanup(ctx) }
})

test('取源回来前页面已换走：旧请求不给授权（防旧请求喂给新页面）', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    let release = null
    ctx.capture.gate = new Promise((r) => { release = r })
    const pending = askDisplay(v.win)
    await new Promise((r) => setTimeout(r, 10))
    v.win.webContents.mainFrame.url = 'file:///answer.html'   // 同一 frame，但已经不是 voice.html
    ctx.capture.gate = null
    release()
    assert.deepStrictEqual(await pending, {})
  } finally { cleanup(ctx) }
})

test('语音窗口权限：只放行主 frame 的麦克风与 display，摄像头和其它权限一律拒', async () => {
  const ctx = boot()
  try {
    const v = await openVoice(ctx)
    const ses = v.win.webContents.session
    const ask = (permission, details) => new Promise((resolve) => {
      ses.permissionRequest(v.win.webContents, permission, resolve, details || {})
    })
    assert.strictEqual(await ask('media', { mediaTypes: ['audio'], isMainFrame: true }), true)
    assert.strictEqual(await ask('media', { mediaTypes: ['video'], isMainFrame: true }), false, '摄像头不给')
    assert.strictEqual(await ask('media', { mediaTypes: ['audio', 'video'], isMainFrame: true }), false, '夹带摄像头也不给')
    assert.strictEqual(await ask('media', { mediaTypes: ['audio'], isMainFrame: false }), false, 'iframe 不给')
    assert.strictEqual(await ask('display-capture', { isMainFrame: true }), true, '回环来源由主进程挑，放行请求再看 frame')
    assert.strictEqual(await ask('notifications', { isMainFrame: true }), false)
    assert.strictEqual(await ask('openExternal', { isMainFrame: true }), false)

    const other = new ctx.FakeWindow({ webPreferences: {} })
    const strangerAsk = await new Promise((resolve) => {
      ses.permissionRequest(other.webContents, 'media', resolve, { mediaTypes: ['audio'], isMainFrame: true })
    })
    assert.strictEqual(strangerAsk, false, '别的窗口借不到这条 session 的权限')

    assert.strictEqual(ses.permissionCheck(v.win.webContents, 'media', 'file://', { mediaType: 'audio', isMainFrame: true }), true)
    assert.strictEqual(ses.permissionCheck(v.win.webContents, 'media', 'file://', { mediaType: 'video', isMainFrame: true }), false)
    assert.strictEqual(ses.permissionCheck(other.webContents, 'media', 'file://', { mediaType: 'audio', isMainFrame: true }), false)
  } finally { cleanup(ctx) }
})

