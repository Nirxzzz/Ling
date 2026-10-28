/* voice-main.js — 实时转写的主进程控制器（窗口 / IPC / 会话生命周期 / 密钥存储接线）。
 *
 * 为什么要一个独立模块：main.js 已经 4300 行，语音这条线有自己的窗口、自己的 IPC 白名单和
 * 自己的会话状态机。这里只依赖 main.js 注入进来的几样东西（窗口工具 / 日志 / 设置页 sender 判定），
 * 不去复制一套淡入淡出与置顶逻辑。
 *
 * 安全边界（写死在代码里，不靠页面自觉）：
 *   - 只认语音窗口的**主 frame**（senderFrame 也核），设置窗口只能配置与打开，不能推音频；
 *   - 没有任何「传地址」的接口：域名 / 资源 ID / 模型名全部来自 voice-asr.js 的常量；
 *   - 音频帧在 IPC 边界校验：≤6400 字节、偶数长度（s16le）、按会话令牌桶限速，超了就丢帧计数；
 *   - Key 只在主进程里解密后交给协议层，回给渲染器的一律是 hasKey 布尔；
 *   - 关掉语音窗口 / 退出应用 → 立刻 cancel，不留下没人看得见的采集与连接。
 *   - 系统音频（会议对面的人）只走这个窗口 main frame + 用户手势的 getDisplayMedia，主进程指定 loopback；
 *     画面轨只是 ScreenCaptureKit 取系统音频的附带物，主进程不取缩略图 / 不落盘 / 不上传。
 */
'use strict'

const path = require('node:path')
const { pathToFileURL } = require('node:url')
const V = require('./voice-asr')
const { createSecretStore } = require('./voice-secrets')
const { createDialogue } = require('./voice-dialogue')
const { createPrefStore, normalizePrefs, VOICE_TYPES, RANGES, DEFAULTS: PREF_DEFAULTS } = require('./voice-prefs')

const WIN_W = 420
const WIN_H = 560
const MIC_STATUS = ['granted', 'denied', 'restricted', 'not-determined', 'unknown']
const SOURCE_IDS = ['mic', 'system', 'mixed']   // 音频来源：麦克风 / 系统回环 / 两路同时
/** 语音窗口专用会话：没有 persist: 前缀 = 非持久（退出即清），也不去改 defaultSession 的权限处理器。 */
const VOICE_PARTITION = 'voice-capture'

let deps = null
let voiceWin = null
let secrets = null
let prefs = null            // 对话模式的音色 / 语速 / 音量（明文偏好，不是秘密）
let dialogue = null         // 腾讯 ASR → chat LLM → 腾讯 TTS 的一轮编排
let active = null        // { id, session, gate, provider, source, dropped, notifiedDropped }
let seq = 0
/* TTS 音频在 IPC 上的在途字节：页面收到就回 ack 冲掉，超过上限就丢片（不无界排队）。
 * 8 秒 16k/16bit/mono ≈ 256KB —— 合成通常比播放快，这个余量够把一句整段送过去。 */
const MAX_TTS_INFLIGHT = 1 << 18
let ttsInflight = 0
let ttsDropped = 0
let dialogueGate = V.createRateGate()
let dialogueDropped = 0
let dialogueBadNotified = false

function log(line) { try { if (deps && typeof deps.modeLog === 'function') deps.modeLog(line) } catch {} }

function init(d) {
  if (deps) return
  deps = d
  voicePageHref = pageHrefOf(d.path)
  const userData = d.app.getPath('userData')
  secrets = createSecretStore({ dir: userData, safeStorage: d.safeStorage })
  prefs = createPrefStore({ dir: userData })
  dialogue = createDialogue({
    speech: d.speech || undefined,        // 测试可注入假协议层；正常就是 tencent-speech.js
    emit: dialogueEmit,
    sendAudio: dialogueSendAudio,
    getCredentials: () => secrets.getTencent(),
    getChat: () => (deps && deps.chat) || null,
    getPrefs: () => prefs.get(),
    fetchImpl: deps.fetchImpl
  })
  installIpc()
}

/* ---------------- 对话模式的事件出口 ----------------
 * 页面收到的永远是文本 / 状态 / PCM，凭据与 chat 配置一概不出主进程。 */
function dialogueEmit(ev) {
  if (!ev || typeof ev !== 'object') return
  if (ev.type === 'state') {
    send('voice:dialogue', { type: 'state', state: ev.state, message: ev.message || '' })
    if (ev.state === 'idle' || ev.state === 'error') ttsInflight = 0
    return
  }
  if (ev.type === 'text') {
    send('voice:dialogue', {
      type: 'text',
      role: ev.role === 'user' ? 'user' : 'assistant',
      text: String(ev.text || '').slice(0, 20000),
      delta: String(ev.delta || '').slice(0, 4000),
      final: ev.final === true
    })
    return
  }
  if (ev.type === 'notice') { send('voice:dialogue', { type: 'notice', kind: String(ev.kind || 'info'), text: String(ev.text || '').slice(0, 600) }); return }
  if (ev.type === 'barge-in') { ttsInflight = 0; send('voice:dialogue', { type: 'barge-in' }) }
}

/** 合成 PCM 送页面。在途字节超上限就返回 false（编排层会记丢帧数），绝不无限排队。 */
function dialogueSendAudio(buf) {
  if (!voiceWin || voiceWin.isDestroyed()) return false
  if (ttsInflight + buf.length > MAX_TTS_INFLIGHT) { ttsDropped++; return false }
  ttsInflight += buf.length
  return send('voice:tts-audio', buf)
}

/* ---------------- sender / frame 白名单 ---------------- */

function isVoiceContents(wc) {
  return !!(wc && voiceWin && !voiceWin.isDestroyed() && wc === voiceWin.webContents)
}

/** 语音窗口 loadFile 的那一个文件的 file:// 地址（init 时算一次：每帧音频都要过这道判断）。 */
let voicePageHref = ''
function pageHrefOf(dir) {
  try { return dir ? pathToFileURL(path.join(dir, 'voice.html')).href : '' } catch { return '' }
}

/**
 * 只认这个地址本身，或其后直接接 ?query / #hash。其它目录下的同名 voice.html（file:///tmp/voice.html）
 * 以及拼了路径段的地址（voice.html/../x.html）一律不算 —— 精确比对，不是按文件名匹配。
 */
function isVoicePageUrl(url) {
  const want = voicePageHref
  const got = String(url || '')
  if (!want || !got) return false
  if (got === want) return true
  if (got.slice(0, want.length) !== want) return false
  return /^[?#]/.test(got.slice(want.length))
}

/** 语音窗口的主 frame 才算数：iframe / 子 frame 一律不认。 */
function isVoiceSender(e) {
  if (!e || !isVoiceContents(e.sender)) return false
  const mainFrame = voiceWin.webContents.mainFrame
  if (e.senderFrame && mainFrame && e.senderFrame !== mainFrame) return false
  let url = ''
  try { url = (e.senderFrame && e.senderFrame.url) || e.sender.getURL() } catch { url = '' }
  return isVoicePageUrl(url)
}

/** 设置窗口（配置 / 打开语音窗口）与语音窗口自己都允许读状态与写 Key。 */
function isConfigSender(e) {
  if (!e || !e.sender) return false
  if (isVoiceSender(e)) return true
  try { return !!(deps && typeof deps.isSettingsSender === 'function' && deps.isSettingsSender(e)) } catch { return false }
}

/* ---------------- 麦克风权限 ---------------- */

function micStatus() {
  try { return String(deps.systemPreferences.getMediaAccessStatus('microphone') || 'unknown') } catch { return 'unknown' }
}

async function ensureMicPermission() {
  const st = micStatus()
  if (st === 'granted' || st === 'unknown') return { ok: true, status: st }
  if (st === 'not-determined' && typeof deps.systemPreferences.askForMediaAccess === 'function') {
    let granted = false
    try { granted = await deps.systemPreferences.askForMediaAccess('microphone') } catch (e) { log('请求麦克风权限失败：' + ((e && e.message) || e)) }
    const now = micStatus()
    if (granted || now === 'granted') return { ok: true, status: now === 'unknown' ? 'granted' : now }
    return { ok: false, status: now, error: '没有麦克风权限：在「系统设置 → 隐私与安全性 → 麦克风」里允许 ' + deps.APP_NAME + ' 后重试。' }
  }
  if (st === 'denied' || st === 'restricted') {
    return { ok: false, status: st, error: '麦克风权限被拒绝：在「系统设置 → 隐私与安全性 → 麦克风」里允许 ' + deps.APP_NAME + ' 后重试。' }
  }
  return { ok: true, status: st }
}

function openMicPane() { try { deps.shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone') } catch {} }

/* ---------------- 系统音频：getDisplayMedia → 回环 ---------------- */

/** 请求必须来自「当前这个」语音窗口的主 frame，且页面还是本机的 voice.html。 */
function isVoiceDisplayFrame(win, frame) {
  if (!win || typeof win.isDestroyed !== 'function' || win.isDestroyed()) return false
  if (!isVoiceContents(win.webContents)) return false
  let mainFrame = null
  try { mainFrame = win.webContents.mainFrame } catch { return false }
  if (!mainFrame || !frame || frame !== mainFrame) return false
  return isVoicePageUrl(frame.url)
}

/** 优先主显示器那块屏；取不到匹配就用第一块。来源 id 交给 Chromium 建 SCK 流。 */
function pickScreenSource(sources) {
  const list = Array.isArray(sources) ? sources.filter((s) => s && s.id) : []
  if (!list.length) return null
  let primaryId = ''
  try { primaryId = String(deps.screen.getPrimaryDisplay().id) } catch { primaryId = '' }
  if (primaryId) {
    const hit = list.find((s) => String(s.display_id) === primaryId)
    if (hit) return hit
  }
  return list[0]
}

/**
 * 屏幕与系统音频的授权状态。首次运行时 macOS 常常先报 denied（真正弹窗由 getSources 触发），
 * 所以这里只取状态供拒绝时说清楚，**不拿它提前拦请求** —— 否则用户永远看不到授权弹窗。
 */
function screenCaptureStatus() {
  try { return String(deps.systemPreferences.getMediaAccessStatus('screen') || 'unknown') } catch { return 'unknown' }
}

/** 拿不到屏幕源时给用户的下一句：权限那条必须明确指到系统设置，不能含糊成「没有源」。 */
function noScreenSourceReason(status) {
  if (status === 'denied' || status === 'restricted') {
    return '拿不到系统音频（屏幕与系统音频权限=' + status + '）：在「系统设置 → 隐私与安全性 → 屏幕与系统音频录制」里允许 '
      + deps.APP_NAME + '，然后关掉转写窗口重开再试'
  }
  return '拿不到可用的屏幕源（屏幕与系统音频权限=' + status + '）：确认系统允许 ' + deps.APP_NAME + ' 录制屏幕与系统音频后重试'
}

/**
 * display-capture 的唯一入口：只给这个窗口主 frame 的手势请求，audio 固定 loopback
 * （不能 loopbackWithMute —— 会议里对方的声音必须继续从扬声器出来）。
 * getSources 是异步的：回来时再核一次窗口 / 主 frame，旧请求绝不给新窗口授权。
 */
function handleDisplayRequest(win, request, callback) {
  let answered = false
  const reply = (streams, why) => {
    if (answered) return
    answered = true
    if (why) {
      log('拒绝系统音频采集：' + why)
      // 不能只在主进程日志里吞掉：页面拿到的只是 getDisplayMedia 的拒绝，必须同时看到「去系统设置授权」
      send('voice:notice', { kind: 'warn', text: why })
    }
    try { callback(streams) } catch {}
  }
  if (!isVoiceDisplayFrame(win, request && request.frame)) return reply({}, '不是语音窗口主 frame')
  if (!request.userGesture) return reply({}, '没有用户手势')
  if (!request.audioRequested) return reply({}, '没有请求系统音频')
  if (!deps.desktopCapturer || typeof deps.desktopCapturer.getSources !== 'function') return reply({}, '没有 desktopCapturer')
  const wantVideo = request.videoRequested === true
  const frame = request.frame
  // 不能因为「此刻 getMediaAccessStatus('screen') 还是 denied」就提前拒绝：这台机器第一次用时
  // 状态本来就是 denied，真正的授权弹窗由下面这次 getSources 触发。提前拦会让用户永远等不到弹窗。
  Promise.resolve(deps.desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 0, height: 0 } })).then((sources) => {
    if (!isVoiceDisplayFrame(win, frame)) return reply({}, '窗口已关闭或页面已换')
    const src = pickScreenSource(sources)
    if (!src) return reply({}, noScreenSourceReason(screenCaptureStatus()))
    // 画面轨只为让 SCK 的系统音频活着：渲染器拿到后不读帧、不上传；没要画面就不多给轨
    const streams = { audio: 'loopback' }
    if (wantVideo) streams.video = { id: src.id, name: src.name || src.id }
    reply(streams)
  }).catch((e) => reply({}, '取屏幕源失败（' + screenCaptureStatus() + '）：' + ((e && e.message) || e)))
}

/* ---------------- 会话权限：只给这个窗口的麦克风与系统音频 ---------------- */

function voiceFrameOk(win, wc, details) {
  if (!isVoiceContents(wc)) return false
  if (details && details.isMainFrame === false) return false     // iframe / 子 frame 一律不认
  let mainFrame = null
  try { mainFrame = win.webContents.mainFrame } catch { return false }
  // IPC 与 display 用同一把尺：details.requestingUrl 优先，没有就看主 frame 当前地址
  const url = (details && details.requestingUrl) || (mainFrame && mainFrame.url) || ''
  return isVoicePageUrl(url)
}

function voicePermissionAllowed(win, wc, permission, mediaTypes, details) {
  if (!voiceFrameOk(win, wc, details)) return false
  if (permission === 'display-capture') return true              // 系统音频的回环来源（源由主进程挑）
  if (permission !== 'media') return false                       // 通知 / USB / 全屏… 全不给
  const types = Array.isArray(mediaTypes) && mediaTypes.length
    ? mediaTypes
    : (details && details.mediaType && details.mediaType !== 'unknown' ? [details.mediaType] : [])
  if (types.indexOf('video') >= 0) return false                  // 摄像头一律拒
  return types.length === 0 || types.indexOf('audio') >= 0
}

function installSessionHandlers(win) {
  const ses = win.webContents.session
  ses.setPermissionRequestHandler((wc, permission, callback, details) => {
    const ok = voicePermissionAllowed(win, wc, permission, details && details.mediaTypes, details)
    if (!ok) log('拒绝语音窗口权限：' + permission)
    callback(ok)
  })
  ses.setPermissionCheckHandler((wc, permission, requestingOrigin, details) => {
    return voicePermissionAllowed(win, wc, permission, null, details)
  })
  ses.setDisplayMediaRequestHandler((request, callback) => { handleDisplayRequest(win, request, callback) })
  return ses
}

/** 窗口关掉就把这条 session 上的三个处理器撤掉：不留引用，也不让旧闭包留在下个窗口。 */
function clearSessionHandlers(ses) {
  if (!ses) return
  try { ses.setPermissionRequestHandler(null) } catch {}
  try { ses.setPermissionCheckHandler(null) } catch {}
  try { ses.setDisplayMediaRequestHandler(null) } catch {}
}

/* ---------------- 状态快照（绝不含 Key） ---------------- */

function statusPayload() {
  const provider = secrets.provider()
  return {
    ok: true,
    provider: provider,
    providers: V.PROVIDER_IDS.map((id) => ({
      id: id,
      label: V.PROVIDERS[id].label,
      keyHint: V.PROVIDERS[id].keyHint,
      hasKey: secrets.hasKey(id)
    })),
    hasKey: provider ? secrets.hasKey(provider) : false,
    encryptionAvailable: secrets.encryptionAvailable(),
    micStatus: micStatus(),
    error: secrets.lastError() || '',
    session: active ? { state: active.session.state, provider: active.provider } : { state: V.STATES.IDLE, provider: '' },
    // 对话模式：只回「配没配好」，凭据与 chat 地址 / Key 一概不出主进程
    dialogue: dialogueStatus()
  }
}

/** 对话模式的状态快照：腾讯凭据是否已存、chat 模型是否可用、当前语音偏好。 */
function dialogueStatus() {
  const d = (dialogue && dialogue.status()) || { state: 'idle', active: false, asrReady: false, stats: {}, error: '' }
  let hasTencent = false
  try { hasTencent = secrets.hasTencent() } catch {}
  let chatReady = false
  try {
    const c = deps && deps.chat
    chatReady = !!(c && typeof c.ready === 'function' && c.ready())
  } catch { chatReady = false }
  return {
    hasCredentials: hasTencent,
    // 密文还在、系统加密也可用，却解不开：界面要能说「读不出来」而不是「没填」。
    credentialsReadable: hasTencent ? !!secrets.getTencent() : false,
    chatReady: chatReady,
    session: { state: d.state, active: !!d.active, asrReady: !!d.asrReady, stats: d.stats || {}, error: d.error || '' },
    prefs: prefs ? prefs.get() : Object.assign({}, PREF_DEFAULTS),
    voiceTypes: VOICE_TYPES.slice(),
    ranges: RANGES
  }
}

/** 失败也把当前状态一起回给页面：界面能同时显示「为什么失败」和「现在配到什么程度」。 */
function failWithStatus(error) { return Object.assign({}, statusPayload(), { ok: false, error: error }) }

/** 返回是否真的发出去了：TTS 的在途字节配额靠这个返回值判定。 */
function send(channel, payload) {
  if (!voiceWin || voiceWin.isDestroyed()) return false
  try { voiceWin.webContents.send(channel, payload); return true } catch { return false }
}

function sendState(ev) {
  send('voice:state', ev)
}

function endActive(reason) {
  if (!active) return
  const a = active
  active = null
  log('语音会话结束（' + reason + '）：' + a.session.state)
}

/* ---------------- 会话 ---------------- */

/**
 * 「正在启动」这一段的代号。启动要先等麦克风权限（可能弹系统窗），这段等待里用户点取消 /
 * 关窗 / 换服务商，都必须让那次启动作废 —— 否则权限一回来它照样连上一次服务器会话，
 * 而界面已经认为停了，用户看不到也在上传。所以：start 先领 token，取消类动作把 token 推进，
 * await 回来后 token 不匹配就直接放弃且**什么都不建**。
 */
let startSeq = 0
let pendingStart = null        // { token, provider }：已有一次启动在等权限时不许再发第二次

function invalidatePendingStart(reason) {
  if (!pendingStart) return
  log('取消未完成的启动（' + reason + '）')
  pendingStart = null
  startSeq++
}

function startSession(consent, provider, source) {
  if (active) return { ok: false, error: '已经在转写中：先停止再开始' }
  if (consent !== true) return { ok: false, error: '需要先勾选「同意临时上传语音」' }
  const cur = secrets.provider()
  if (!cur) return { ok: false, error: '还没选转写服务：先在设置里选豆包或阿里云' }
  // 同意是对「某一家」给的：页面报上来的服务商必须就是当前这家，
  // 否则会出现「界面显示 B、实际把语音传给 A」。
  if (provider && String(provider) !== cur) {
    return { ok: false, error: '服务商已经换成「' + V.PROVIDERS[cur].label + '」：请重新勾选同意再开始', providerChanged: true, provider: cur }
  }
  const apiKey = secrets.getKey(cur)
  if (!apiKey) {
    const label = V.PROVIDERS[cur].label
    if (!secrets.encryptionAvailable()) {
      return { ok: false, error: '本机系统加密不可用，读不出已保存的 Key：请先解锁钥匙串，再在设置里重新保存 Key' }
    }
    // 密文还在、系统加密也可用，却就是解不开（共享了别的渠道 / 别的版本的 userData，或钥匙串被重置）：
    // 这时报「还没有填」是在骗人 —— 设置里明明还显示「Key 已保存」。
    if (secrets.hasKey(cur)) {
      return {
        ok: false,
        error: '已保存的「' + label + '」Key 读不出来（密文还在，但本机解不开：可能换了系统账户 / 钥匙串被重置）：请在设置里重新保存这把 Key'
      }
    }
    return { ok: false, error: '还没有填「' + label + '」的 API Key：先在设置里填好再开始' }
  }
  seq++
  const id = 'vs-' + seq
  const session = new V.VoiceAsrSession({
    provider: cur,
    apiKey: apiKey,
    // 传输层可由调用方替换（自动化测试用假 socket；正常运行时就是 ws）
    WebSocketImpl: deps && deps.WebSocketImpl ? deps.WebSocketImpl : undefined,
    onEvent: (ev) => {
      if (!active || active.id !== id) return      // 迟到的回调：会话已经被替换 / 结束
      if (ev.type === 'state') {
        sendState({ state: ev.state, message: ev.message || '', provider: ev.provider, stats: ev.stats })
        if (ev.state === V.STATES.FINISHED || ev.state === V.STATES.ERROR || ev.state === V.STATES.CANCELLED) endActive(ev.state)
      } else if (ev.type === 'transcript') {
        send('voice:transcript', { startMs: ev.startMs, endMs: ev.endMs, text: ev.text, isFinal: ev.isFinal })
      }
    }
  })
  active = { id: id, session: session, gate: V.createRateGate(), provider: cur, source: source, dropped: 0, notifiedDropped: false }
  log('语音会话开始：' + cur + '（来源 ' + source + '）')
  // 连接失败 / 异常都由 session 自己发 state=error，这里不再补发第二个终态（页面只该看到一次）
  session.start().catch((e) => {
    if (active && active.id === id) {
      session.cancel()
      endActive('start-threw')
      log('语音会话启动异常：' + ((e && e.message) || e))
    }
  })
  return { ok: true, sessionId: id, provider: cur }
}

function stopSession(immediate) {
  if (immediate) invalidatePendingStart('stop')
  if (!active) return { ok: true, state: V.STATES.IDLE }
  const session = active.session
  const r = immediate ? session.cancel() : session.finish()
  if (immediate) endActive('cancel')
  return r
}

/* ---------------- 窗口 ---------------- */

function showVoiceWindow() {
  if (voiceWin && !voiceWin.isDestroyed()) { voiceWin.show(); try { voiceWin.focus() } catch {}; return { ok: true, already: true } }
  const wa = deps.screen.getPrimaryDisplay().workArea
  const width = WIN_W
  const height = WIN_H
  const b = deps.orbBounds ? deps.orbBounds() : null
  const x = b ? Math.max(wa.x + 8, Math.min(b.x - width + 90, wa.x + wa.width - width - 8)) : Math.round(wa.x + (wa.width - width) / 2)
  const y = b ? Math.min(wa.y + wa.height - height - 20, Math.max(wa.y + 8, b.y)) : Math.round(wa.y + (wa.height - height) / 2)
  voiceWin = new deps.BrowserWindow({
    width: width, height: height, x: x, y: y, title: deps.APP_NAME + ' 实时转写',
    frame: false, transparent: true, resizable: false, movable: true,
    alwaysOnTop: true, skipTaskbar: true, show: false,
    fullscreenable: false, minimizable: false, maximizable: false,
    webPreferences: {
      // 独立非持久会话：这个窗口的权限 / 回环处理器只挂在自己这条 session 上，
      // 不碰 defaultSession（设置页 / 面板 / 截图那条线一点不受影响），退出即清。
      partition: VOICE_PARTITION,
      preload: path.join(deps.path, 'voice-preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: false   // 采集靠渲染进程的音频线程 + 计时器：窗口被盖住时也不能省电降频
    }
  })
  const w = voiceWin
  try { w.setAlwaysOnTop(true, 'screen-saver', deps.layer) } catch {}
  try { deps.hideFromMissionControl(w) } catch {}
  // 只给这个窗口开麦克风与系统音频（回环来源由主进程挑）：别的页面（含设置页 / 面板）拿不到
  let ses = null
  try { ses = installSessionHandlers(w) } catch (e) { log('设置语音窗口权限处理器失败：' + ((e && e.message) || e)) }

  w.loadFile(path.join(deps.path, 'voice.html'))
  w.once('ready-to-show', () => { if (voiceWin !== w || w.isDestroyed()) return; try { deps.app.focus({ steal: true }) } catch {}; deps.revealWin(w, true) })
  w.on('closed', () => {
    // 已经有新窗口接手同一条 session 的话，绝不能把它的处理器撤掉
    if (voiceWin !== w) return
    voiceWin = null
    clearSessionHandlers(ses)
    // 窗口一关就等于用户按了停止：撤掉在等的启动、立刻释放麦克风与连接，绝不后台继续采集
    invalidatePendingStart('window-closed')
    stopSession(true)
    // 对话模式：ASR / LLM 流 / TTS 与播放队列一起释放（页面卸载时也会自己清播放）
    try { if (dialogue) dialogue.stop() } catch {}
    ttsInflight = 0
  })
  log('打开实时转写窗口')
  return { ok: true }
}

function closeVoiceWindow() {
  if (!voiceWin || voiceWin.isDestroyed()) return
  try { deps.fadeClose(voiceWin) } catch { try { voiceWin.close() } catch {} }
}

/* ---------------- IPC ---------------- */

function installIpc() {
  const ipc = deps.ipcMain

  ipc.handle('voice:status', (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    return statusPayload()
  })

  ipc.handle('voice:open', (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    /* v1.78：打开窗口不再要求先配好任何凭据 —— 新用户必须能在窗口里填腾讯三元组与语音偏好，
     * 否则「要先有 Key 才能开窗、Key 又只能在窗口里填」会死锁。开始按钮各自 gate，
     * 没配好时窗口如实显示「未配置」，绝不假装可用。 */
    return showVoiceWindow()
  })

  ipc.handle('voice:select-provider', (e, provider) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const id = String(provider || '')
    const before = secrets.provider()
    const r = secrets.setProvider(id)
    if (!r.ok) return r
    // 换服务商 = 换掉凭据与目的地：正在跑的会话、还在等权限的那次启动都必须撤掉，
    // 否则界面显示 B、实际还在往 A 传语音。
    if (before !== id) {
      invalidatePendingStart('provider-changed')
      if (active) stopSession(true)
      send('voice:notice', { kind: 'info', text: '已选择「' + V.PROVIDERS[id].label + '」：当前转写已停止，需要重新勾选同意再开始。' })
    }
    return Object.assign({ ok: true }, statusPayload())
  })

  ipc.handle('voice:save-key', (e, payload) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const p = payload && typeof payload === 'object' ? payload : {}
    const provider = String(p.provider || '')
    const apiKey = typeof p.apiKey === 'string' ? p.apiKey.slice(0, 4096) : ''
    const r = secrets.setKey(provider, apiKey)
    if (!r.ok) return failWithStatus(r.error)
    log('已保存 ' + provider + ' 的转写 Key（加密落盘）')
    return statusPayload()
  })

  ipc.handle('voice:delete-key', (e, provider) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const id = String(provider || '')
    const r = secrets.deleteKey(id)
    if (!r.ok) return failWithStatus(r.error)
    log('已删除 ' + id + ' 的转写 Key')
    // 正在用这把 Key 的会话必须立刻停：不能让它在后台继续用已经删掉的凭据
    if (active && active.provider === id) stopSession(true)
    return Object.assign(statusPayload(), { removed: !!r.removed })
  })

  ipc.handle('voice:mic-permission', async (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const r = await ensureMicPermission()
    return Object.assign({ micStatus: micStatus() }, r)
  })

  ipc.on('voice:open-mic-settings', (e) => {
    if (!isConfigSender(e)) return
    openMicPane()
  })

  ipc.handle('voice:open-settings', (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    try { deps.showSettings() } catch (err) { return { ok: false, error: '打不开设置：' + ((err && err.message) || err) } }
    return { ok: true }
  })

  ipc.handle('voice:start', async (e, opts) => {
    if (!isVoiceSender(e)) return { ok: false, error: '没有权限' }
    const consent = !!(opts && opts.consent === true)
    const requested = opts && typeof opts.provider === 'string' ? opts.provider : ''
    const source = opts && typeof opts.source === 'string' ? opts.source : ''
    // 来源是页面报上来的：只认这三个词，不认识的一律不连（免得「以为录的是会议、其实只开了麦克风」）
    if (SOURCE_IDS.indexOf(source) < 0) return { ok: false, error: '未知的音频来源，不开始转写' }
    if (active) return { ok: false, error: '已经在转写中：先停止再开始' }
    // 对话与转写共用麦克风，也在往腾讯传语音：对话在跑时不能再起一条转写
    // （对话那边 voice:dialogue-start 同样拒绝正在进行的转写，两边对称）
    if (dialogue && dialogue.status().active) return { ok: false, error: '对话正在进行：先停止对话再开始转写' }
    if (pendingStart) return { ok: false, error: '正在请求麦克风权限：等它回来再试（或点取消）' }
    if (source === 'system') {
      const r0 = startSession(consent, requested, source)
      return r0.ok ? Object.assign({ ok: true }, r0, { micStatus: micStatus() }) : r0
    }
    // 领号：等权限期间被取消 / 关窗 / 换服务商都会让这个号作废
    const token = ++startSeq
    pendingStart = { token: token, provider: requested || secrets.provider() }
    let mic = null
    try {
      mic = await ensureMicPermission()
    } finally {
      // 这一段「正在启动」结束就放掉：失败也别让「正在请求麦克风权限」永远挡住下一次（换号了就不动）
      if (pendingStart && pendingStart.token === token) pendingStart = null
    }
    if (token !== startSeq) {
      // 等待期间已经被取消：绝不建会话，也不假装开始了
      return { ok: false, cancelled: true, error: '启动已取消（麦克风权限回来得太晚）' }
    }
    if (!isVoiceSender(e)) return { ok: false, error: '语音窗口已关闭' }
    if (!mic || !mic.ok) return { ok: false, error: (mic && mic.error) || '没有麦克风权限', micStatus: mic && mic.status }
    const r = startSession(consent, requested, source)
    if (!r.ok) return r
    return Object.assign({ ok: true }, r, { micStatus: mic.status })
  })

  ipc.handle('voice:stop', (e, tail) => {
    if (!isVoiceSender(e)) return { ok: false, error: '没有权限' }
    // 还在等麦克风权限就点停止：把那次启动作废，别等权限回来又连一次
    if (pendingStart) { invalidatePendingStart('stop'); return { ok: true, state: V.STATES.CANCELLED } }
    // 页面停采后会把不足一帧的尾巴捎过来：先按普通帧验一次再当最后一帧发，绝不跳过校验
    if (tail != null && active) {
      const v = V.validatePcmFrame(tail)
      if (v.ok) active.session.sendPcm(v.bytes)
      else send('voice:notice', { kind: 'warn', text: '最后一小段音频无效，已忽略：' + v.error })
    }
    return stopSession(false)
  })

  ipc.handle('voice:cancel', (e) => {
    if (!isVoiceSender(e)) return { ok: false, error: '没有权限' }
    return stopSession(true)
  })

  ipc.on('voice:audio', (e, data) => {
    if (!isVoiceSender(e)) return
    if (!active) return
    const a = active
    if (!a.gate.take()) {
      a.dropped++
      if (!a.notifiedDropped) {
        a.notifiedDropped = true
        send('voice:notice', { kind: 'warn', text: '音频发送超速，超出的帧已丢弃（不会排队占用内存）' })
      }
      return
    }
    const v = V.validatePcmFrame(data)
    if (!v.ok) {
      a.session.stats.bad++
      send('voice:notice', { kind: 'warn', text: '丢弃一帧无效音频：' + v.error })
      return
    }
    if (!a.session.sendPcm(v.bytes)) a.dropped++
  })

  /* ---------------- 对话模式（腾讯 ASR → 既有 chat → 腾讯 TTS） ---------------- */

  /** 腾讯三元组：只在设置卡与语音窗口里填；回给页面的只有 hasCredentials 布尔。 */
  ipc.handle('voice:dialogue-creds', (e, payload) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const p = payload && typeof payload === 'object' ? payload : {}
    const r = secrets.setTencent({
      appId: typeof p.appId === 'string' ? p.appId.slice(0, 64) : '',
      secretId: typeof p.secretId === 'string' ? p.secretId.slice(0, 256) : '',
      secretKey: typeof p.secretKey === 'string' ? p.secretKey.slice(0, 512) : ''
    })
    if (!r.ok) return failWithStatus(r.error)
    log('已保存腾讯云语音凭据（加密落盘）')
    // 凭据换了：正在跑的对话必须停，否则旧 socket 还在用已经作废的账号身份
    try { dialogue.stop() } catch {}
    ttsInflight = 0
    return statusPayload()
  })

  ipc.handle('voice:dialogue-creds-delete', (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const r = secrets.deleteTencent()
    if (!r.ok) return failWithStatus(r.error)
    try { dialogue.stop() } catch {}
    ttsInflight = 0
    log('已删除腾讯云语音凭据')
    return Object.assign(statusPayload(), { removed: !!r.removed })
  })

  /** 音色 / 语速 / 音量：只认白名单字段；越界值由 normalizePrefs 拉回默认，不猜用户意图。 */
  ipc.handle('voice:prefs-save', (e, patch) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    const p = patch && typeof patch === 'object' ? patch : {}
    const clean = {}
    if (p.voiceType !== undefined) clean.voiceType = p.voiceType
    if (p.speed !== undefined) clean.speed = p.speed
    if (p.volume !== undefined) clean.volume = p.volume
    const r = prefs.set(clean)
    if (!r.ok) return failWithStatus(r.error)
    return Object.assign(statusPayload(), { saved: r.prefs })
  })

  ipc.handle('voice:dialogue-status', (e) => {
    if (!isConfigSender(e)) return { ok: false, error: '没有权限' }
    return statusPayload()
  })

  /**
   * 开始对话：同意（语音上传腾讯 + 文本发送给对话模型）必须显式给。
   * 麦克风一定要有 —— 对话只把麦克风当用户输入；系统声音那条线仍然只做转写。
   */
  ipc.handle('voice:dialogue-start', async (e, opts) => {
    if (!isVoiceSender(e)) return { ok: false, error: '没有权限' }
    const consent = !!(opts && opts.consent === true)
    if (!consent) return { ok: false, error: '需要先勾选「同意把语音上传给腾讯云并发送给对话模型」' }
    if (active) return { ok: false, error: '转写正在进行：先停止转写再开始对话' }
    const token = ++startSeq
    pendingStart = { token: token, provider: 'tencent' }
    let mic = null
    try {
      mic = await ensureMicPermission()
    } finally {
      if (pendingStart && pendingStart.token === token) pendingStart = null
    }
    if (token !== startSeq) return { ok: false, cancelled: true, error: '启动已取消（麦克风权限回来得太晚）' }
    if (!isVoiceSender(e)) return { ok: false, error: '语音窗口已关闭' }
    if (!mic || !mic.ok) return { ok: false, error: (mic && mic.error) || '没有麦克风权限', micStatus: mic && mic.status }
    dialogueGate = V.createRateGate()
    dialogueDropped = 0
    dialogueBadNotified = false
    ttsInflight = 0
    ttsDropped = 0
    const r = dialogue.start({ consent: true })
    if (!r.ok) return Object.assign({ ok: false }, r, { micStatus: mic.status })
    log('语音对话开始（腾讯实时识别 + 既有对话模型）')
    return Object.assign({}, statusPayload(), { ok: true, micStatus: mic.status })
  })

  ipc.handle('voice:dialogue-stop', (e) => {
    if (!isVoiceSender(e)) return { ok: false, error: '没有权限' }
    // 还在等麦克风权限：把那次启动作废（否则权限回来又会自己连上去）
    if (pendingStart && pendingStart.provider === 'tencent') invalidatePendingStart('stop')
    const r = dialogue.stop()
    ttsInflight = 0
    return Object.assign({ ok: true, state: r.state, status: dialogueStatus() })
  })

  /** 用户对着麦克风说话：与转写共用同一套帧校验 + 令牌桶，最后喂给腾讯 ASR。 */
  ipc.on('voice:dialogue-audio', (e, data) => {
    if (!isVoiceSender(e)) return
    if (!dialogue || !dialogue.status().active) return
    if (!dialogueGate.take()) { dialogueDropped++; return }
    const v = V.validatePcmFrame(data)
    if (!v.ok) {
      if (!dialogueBadNotified) {
        dialogueBadNotified = true
        send('voice:dialogue', { type: 'notice', kind: 'warn', text: '丢弃一帧无效音频：' + v.error })
      }
      return
    }
    if (!dialogue.sendPcm(v.bytes)) dialogueDropped++
  })

  /** 页面播完一片 TTS：冲掉在途配额（页面只报它收到的字节数，主进程自己夹住上限）。 */
  ipc.on('voice:tts-ack', (e, bytes) => {
    if (!isVoiceSender(e)) return
    const n = Math.round(Number(bytes))
    if (!Number.isFinite(n) || n <= 0) return
    ttsInflight = Math.max(0, ttsInflight - Math.min(n, MAX_TTS_INFLIGHT))
  })

  ipc.on('voice:close', (e) => {
    if (!isVoiceSender(e)) return
    closeVoiceWindow()
  })
}

/** 退出应用时调用：立刻停会话 / 撤掉在等的启动、解绑，别留 socket 与采集。 */
function shutdown() {
  invalidatePendingStart('shutdown')
  try { stopSession(true) } catch {}
  try { if (dialogue) dialogue.destroy() } catch {}
  ttsInflight = 0
  voiceWin = null
}

module.exports = {
  init: init,
  shutdown: shutdown,
  showVoiceWindow: showVoiceWindow,
  window: () => voiceWin,
  // 主进程测试 / 诊断用（不进渲染器）
  _status: () => (secrets ? statusPayload() : { ok: false, error: '未初始化' }),
  MIC_STATUS: MIC_STATUS,
  SOURCE_IDS: SOURCE_IDS
}
