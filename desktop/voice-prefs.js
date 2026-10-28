/* voice-prefs.js — 对话模式的语音偏好（音色 / 语速 / 音量）落盘。
 *
 * 这三项不是秘密：明文 JSON（0600）写在 userData/voice-dialogue.json，和凭据（voice-secrets.js）分开。
 * 取值严格按腾讯云 TTS 的官方参数语义，不自己发明倍率：
 *   - voiceType：官方音色 ID（正整数，例如 101001 智瑜 / 101016 智甜）；
 *   - speed：官方 Speed，范围 [-2, 6]，0 = 正常语速（**不是倍率**；-2≈0.6 倍，6≈2.5 倍）；
 *   - volume：官方 Volume，范围 [-10, 10]，0 = 正常音量。
 * 越界 / 类型不对一律回到默认值，不猜用户意图，也不把非法值发给服务端。
 * 不 require electron：目录由调用方注入。
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')

const FILE_NAME = 'voice-dialogue.json'
const DEFAULTS = { voiceType: 101001, speed: 0, volume: 0 }
const VOICE_TYPES = [
  { id: 101001, label: '智瑜 · 情感女声' },
  { id: 101016, label: '智甜 · 女童声' },
  { id: 101015, label: '智萌 · 男童声' },
  { id: 101004, label: '智云 · 通用男声' },
  { id: 101027, label: '智梅 · 通用女声' },
  { id: 101030, label: '智柯 · 通用男声' },
  { id: 101019, label: '智彤 · 粤语女声' }
]
const SPEED_MIN = -2
const SPEED_MAX = 6
const VOLUME_MIN = -10
const VOLUME_MAX = 10

function round2(v) { return Math.round(Number(v) * 100) / 100 }

/**
 * 归一化一份偏好。任何一项不合法就回该项默认值（整体仍是合法对象），
 * 这样界面上滑杆/下拉永远有一个可用的当前值。
 */
function normalizePrefs(raw) {
  const s = (raw && typeof raw === 'object') ? raw : {}
  const vt = Math.round(Number(s.voiceType))
  const speed = Number(s.speed)
  const volume = Number(s.volume)
  return {
    voiceType: (Number.isFinite(vt) && vt > 0 && vt <= 999999) ? vt : DEFAULTS.voiceType,
    // 语速允许两位小数（官方也是这个精度）；越界即回默认，不静默钳到边上
    speed: (Number.isFinite(speed) && speed >= SPEED_MIN && speed <= SPEED_MAX) ? round2(speed) : DEFAULTS.speed,
    volume: (Number.isFinite(volume) && volume >= VOLUME_MIN && volume <= VOLUME_MAX) ? Math.round(volume) : DEFAULTS.volume
  }
}

function createPrefStore(opts) {
  const dir = String((opts && opts.dir) || '')
  const file = path.join(dir, FILE_NAME)
  let cache = null

  function read() {
    let text = ''
    try { text = fs.readFileSync(file, 'utf8') } catch { return Object.assign({}, DEFAULTS) }
    let obj = null
    try { obj = JSON.parse(text) } catch { return Object.assign({}, DEFAULTS) }
    return normalizePrefs(obj)
  }

  function get() {
    if (!cache) cache = read()
    return Object.assign({}, cache)
  }

  function set(patch) {
    const next = normalizePrefs(Object.assign({}, get(), patch && typeof patch === 'object' ? patch : {}))
    try {
      fs.mkdirSync(dir, { recursive: true })
      const tmp = file + '.tmp-' + process.pid
      fs.writeFileSync(tmp, JSON.stringify(next), { mode: 0o600 })
      try { fs.chmodSync(tmp, 0o600) } catch {}
      fs.renameSync(tmp, file)
      try { fs.chmodSync(file, 0o600) } catch {}
    } catch (err) {
      return { ok: false, error: '写盘失败，语音偏好没有保存：' + ((err && err.message) || err), prefs: next }
    }
    cache = next
    return { ok: true, prefs: Object.assign({}, next) }
  }

  return { file: file, get: get, set: set, reset: () => { cache = null } }
}

module.exports = {
  createPrefStore: createPrefStore,
  normalizePrefs: normalizePrefs,
  DEFAULTS: DEFAULTS,
  VOICE_TYPES: VOICE_TYPES,
  FILE_NAME: FILE_NAME,
  RANGES: { speed: [SPEED_MIN, SPEED_MAX], volume: [VOLUME_MIN, VOLUME_MAX] }
}
