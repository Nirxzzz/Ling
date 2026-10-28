/* voice-preload.js — 实时转写窗口专用的最小 preload（只有这个窗口用它，别的面板仍用 preload.js）。
 *
 * 只暴露语音这一条线的动作：读状态 / 开始 / 停止 / 取消 / 推音频 / 关窗 / 打设置。
 * 没有任何「传地址 / 传 Key / 传模型」的口子：域名、模型、鉴权全在主进程。
 * 音频用 ipcRenderer.send 送 ArrayBuffer（structured clone），主进程再校验帧长与速率。
 *
 * 另外挂一个只有外观两项的 orbApi：themes.js 的 data-auto 靠 window.orbApi.getAppearance
 * 把配色写进 ui.css 的变量。只给这两个，不把语音通道挂到 orbApi 名下。
 */
'use strict'

const { contextBridge, ipcRenderer } = require('electron')

const getAppearance = () => ipcRenderer.invoke('appearance:get')
const onAppearanceChanged = (cb) => ipcRenderer.on('appearance:changed', (_e, a) => cb(a))

contextBridge.exposeInMainWorld('voiceApi', {
  // 配置与权限（主进程会把 Key 换成 hasKey 布尔，绝不回明文）
  status: () => ipcRenderer.invoke('voice:status'),
  requestMicPermission: () => ipcRenderer.invoke('voice:mic-permission'),
  openMicSettings: () => ipcRenderer.send('voice:open-mic-settings'),
  // 设置卡由主进程打开：Key 只在设置页的输入框里出现，绝不经过这个窗口
  openSettings: () => ipcRenderer.invoke('voice:open-settings'),

  // source 固定三值 mic | system | mixed：主进程按它决定要不要麦克风、系统音频由主进程挑回环源
  start: (consent, provider, source) => ipcRenderer.invoke('voice:start', {
    consent: consent === true,
    provider: String(provider || ''),
    source: String(source || '')
  }),
  stop: (tail) => ipcRenderer.invoke('voice:stop', tail || null),
  cancel: () => ipcRenderer.invoke('voice:cancel'),
  sendAudio: (buf) => ipcRenderer.send('voice:audio', buf),

  // 窗口（固定尺寸，页面自己滚动，不需要改高度）
  close: () => ipcRenderer.send('voice:close'),

  // 主进程 → 页面：只回传状态 / 转写 / 提示，不含任何凭据
  onState: (cb) => ipcRenderer.on('voice:state', (_e, s) => cb(s)),
  onTranscript: (cb) => ipcRenderer.on('voice:transcript', (_e, t) => cb(t)),
  onNotice: (cb) => ipcRenderer.on('voice:notice', (_e, n) => cb(n)),

  /* ---- 对话模式（腾讯 ASR → 既有对话模型 → 腾讯 TTS） ----
   * 凭据只在「保存」这一次从输入框经过 IPC 交给主进程加密落盘；任何读接口都只回 hasCredentials 布尔。
   * 合成音频由主进程推 voice:tts-audio（PCM），播完用 ttsAck 冲掉主进程的在途配额。 */
  dialogueStatus: () => ipcRenderer.invoke('voice:dialogue-status'),
  dialogueSaveCreds: (creds) => ipcRenderer.invoke('voice:dialogue-creds', {
    appId: String((creds && creds.appId) || ''),
    secretId: String((creds && creds.secretId) || ''),
    secretKey: String((creds && creds.secretKey) || '')
  }),
  dialogueDeleteCreds: () => ipcRenderer.invoke('voice:dialogue-creds-delete'),
  dialogueStart: (consent) => ipcRenderer.invoke('voice:dialogue-start', { consent: consent === true }),
  dialogueStop: () => ipcRenderer.invoke('voice:dialogue-stop'),
  dialogueAudio: (buf) => ipcRenderer.send('voice:dialogue-audio', buf),
  ttsAck: (bytes) => ipcRenderer.send('voice:tts-ack', Math.max(0, Math.round(Number(bytes) || 0))),
  savePrefs: (patch) => ipcRenderer.invoke('voice:prefs-save', patch || {}),
  onDialogue: (cb) => ipcRenderer.on('voice:dialogue', (_e, ev) => cb(ev)),
  onTtsAudio: (cb) => ipcRenderer.on('voice:tts-audio', (_e, pcm) => cb(pcm))
})

// themes.js（data-auto）只认 window.orbApi 的这两个方法
contextBridge.exposeInMainWorld('orbApi', {
  getAppearance: getAppearance,
  onAppearanceChanged: onAppearanceChanged
})
