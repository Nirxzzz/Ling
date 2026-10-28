/* voice-secrets.js — 实时转写的 BYOK 密钥落盘（Electron safeStorage 加密，失败即拒绝，绝不落明文）。
 *
 * 设计边界：
 *   - 两家（豆包 / 阿里云）各存一把 Key，互不覆盖；provider 选择是明文（不是秘密），Key 是密文。
 *   - 任何写操作前都先问 safeStorage.isEncryptionAvailable()；不可用就**拒绝保存**并把状态如实返回，
 *     不回退成 base64 / 明文 / 环境变量伪装 —— 用户看得见「本机加密不可用」，而不是以为存上了。
 *   - 读失败 / 解密失败 / 文件损坏都当「没有 Key」，并保留 lastError 供界面显示；不抛、不带密文往外送。
 *   - 文件权限 0600，先写 .tmp 再 rename，避免半截文件把两家 Key 一起弄丢。
 *   - 这个模块不 require electron：safeStorage 由调用方注入，单元测试与冒烟（假 electron）都能用。
 */
'use strict'

const fs = require('node:fs')
const path = require('node:path')

const FILE_NAME = 'voice-asr.json'
const PROVIDERS = ['doubao', 'alibaba']
const VERSION = 1
/* 腾讯云语音（对话模式）的凭据是三元组，和上面两家的单 Key 不同：
 * 三项一起 JSON 序列化后加密成一整块密文，避免「只存了一部分」的中间态。 */
const TENCENT_MAX = { appId: 20, secretId: 128, secretKey: 256 }

function isProvider(p) { return PROVIDERS.indexOf(String(p)) >= 0 }

/**
 * @param {object} opts { dir: userData 目录, safeStorage: { isEncryptionAvailable, encryptString, decryptString } }
 */
function createSecretStore(opts) {
  const o = opts || {}
  const dir = String(o.dir || '')
  const file = path.join(dir, FILE_NAME)
  const safe = o.safeStorage || null
  let cache = null              // { provider, keys: { provider: base64 } }
  let lastError = ''

  function encryptionAvailable() {
    try { return !!(safe && typeof safe.isEncryptionAvailable === 'function' && safe.isEncryptionAvailable()) } catch { return false }
  }

  function emptyState() { return { provider: '', keys: {}, tencent: '' } }

  function readRaw() {
    let text = ''
    try { text = fs.readFileSync(file, 'utf8') } catch (err) {
      if (!err || err.code !== 'ENOENT') { lastError = '读取转写密钥文件失败：' + ((err && err.message) || err) }
      return emptyState()
    }
    let obj = null
    try { obj = JSON.parse(text) } catch { lastError = '转写密钥文件不是合法 JSON，已按「未配置」处理'; return emptyState() }
    if (!obj || typeof obj !== 'object') { lastError = '转写密钥文件结构不对，已按「未配置」处理'; return emptyState() }
    const keys = {}
    const src = (obj.keys && typeof obj.keys === 'object') ? obj.keys : {}
    for (const p of PROVIDERS) {
      const v = src[p]
      if (typeof v === 'string' && v) keys[p] = v
    }
    // 腾讯三元组的密文：只认字符串；解不开的情况留给 getTencent() 报 lastError
    const tencent = typeof obj.tencent === 'string' && obj.tencent ? obj.tencent : ''
    return { provider: isProvider(obj.provider) ? String(obj.provider) : '', keys: keys, tencent: tencent }
  }

  function state() {
    if (!cache) cache = readRaw()
    return cache
  }

  function writeState(next) {
    fs.mkdirSync(dir, { recursive: true })
    const payload = JSON.stringify({ version: VERSION, provider: next.provider || '', keys: next.keys || {}, tencent: next.tencent || '' })
    const tmp = file + '.tmp-' + process.pid
    fs.writeFileSync(tmp, payload, { mode: 0o600 })
    try { fs.chmodSync(tmp, 0o600) } catch {}
    fs.renameSync(tmp, file)
    try { fs.chmodSync(file, 0o600) } catch {}
    cache = { provider: next.provider || '', keys: Object.assign({}, next.keys || {}), tencent: next.tencent || '' }
    lastError = ''
  }

  /** 解密一把 Key。不可用 / 解不开一律 null（界面只会知道 hasKey=false）。 */
  function getKey(provider) {
    if (!isProvider(provider)) return null
    const enc = state().keys[provider]
    if (!enc) return null
    if (!encryptionAvailable()) { lastError = '本机系统加密不可用，已保存的 Key 现在读不出来'; return null }
    try {
      const buf = Buffer.from(enc, 'base64')
      if (!buf.length) return null
      const out = safe.decryptString(buf)
      return out ? String(out) : null
    } catch (err) {
      lastError = '解密 ' + provider + ' 的 Key 失败（可能换了系统账户 / 钥匙串被重置）：' + ((err && err.message) || err)
      return null
    }
  }

  /** 保存一把 Key（覆盖同 provider 的旧值）。加密不可用 → 明确失败，宁可不存。 */
  function setKey(provider, apiKey) {
    if (!isProvider(provider)) return { ok: false, error: '未知的转写服务' }
    const key = String(apiKey == null ? '' : apiKey).trim()
    if (!key) return { ok: false, error: 'Key 是空的' }
    if (!encryptionAvailable()) {
      return { ok: false, error: '本机系统加密不可用（钥匙串被锁 / 无安全存储），为安全起见不保存明文 Key。请先解锁钥匙串后重试。' }
    }
    let enc = ''
    try {
      enc = safe.encryptString(key).toString('base64')
    } catch (err) {
      return { ok: false, error: '加密失败，Key 没有保存：' + ((err && err.message) || err) }
    }
    if (!enc) return { ok: false, error: '加密结果为空，Key 没有保存' }
    const cur = state()
    const keys = Object.assign({}, cur.keys)
    keys[provider] = enc
    try { writeState({ provider: cur.provider, keys: keys, tencent: cur.tencent }) } catch (err) {
      return { ok: false, error: '写盘失败，Key 没有保存：' + ((err && err.message) || err) }
    }
    return { ok: true, provider: provider }
  }

  /** 删除一把 Key（换账号 / 不想再用这个服务商）。删不存在的也算成功。 */
  function deleteKey(provider) {
    if (!isProvider(provider)) return { ok: false, error: '未知的转写服务' }
    const cur = state()
    if (!cur.keys[provider]) return { ok: true, removed: false }
    const keys = Object.assign({}, cur.keys)
    delete keys[provider]
    try { writeState({ provider: cur.provider, keys: keys, tencent: cur.tencent }) } catch (err) {
      return { ok: false, error: '写盘失败，Key 没有删除：' + ((err && err.message) || err) }
    }
    return { ok: true, removed: true }
  }

  function setProvider(provider) {
    if (!isProvider(provider)) return { ok: false, error: '未知的转写服务' }
    const cur = state()
    try { writeState({ provider: String(provider), keys: cur.keys, tencent: cur.tencent }) } catch (err) {
      return { ok: false, error: '写盘失败，选择没有保存：' + ((err && err.message) || err) }
    }
    return { ok: true, provider: String(provider) }
  }

  function provider() {
    const p = state().provider
    return isProvider(p) ? p : ''
  }

  /* ---------------- 腾讯三元组（对话模式的 ASR + TTS） ----------------
   * 校验规则与 tencent-speech.js 的 normalizeCredentials 一致（AppId 必须是纯数字），
   * 但不 require 那个模块 —— 密钥落盘不该依赖网络协议层。
   * 三项一起 JSON 序列化后加密成一整块密文：不会出现「AppId 存上了、SecretKey 没存上」的中间态。 */
  function normalizeTencent(v) {
    if (!v || typeof v !== 'object') return { ok: false, error: '腾讯语音凭据不完整（需要 AppId / SecretId / SecretKey）' }
    const appId = String(v.appId == null ? '' : v.appId).trim()
    const secretId = String(v.secretId == null ? '' : v.secretId).trim()
    const secretKey = String(v.secretKey == null ? '' : v.secretKey).trim()
    if (!appId || !secretId || !secretKey) return { ok: false, error: '腾讯语音凭据不完整（AppId / SecretId / SecretKey 三项都要填）' }
    if (!/^[0-9]{1,20}$/.test(appId)) return { ok: false, error: '腾讯云 AppId 必须是纯数字（控制台「账号信息」里那串）' }
    if (secretId.length > TENCENT_MAX.secretId) return { ok: false, error: '腾讯云 SecretId 太长，检查一下是不是粘错了' }
    if (secretKey.length > TENCENT_MAX.secretKey) return { ok: false, error: '腾讯云 SecretKey 太长，检查一下是不是粘错了' }
    return { ok: true, value: { appId: appId, secretId: secretId, secretKey: secretKey } }
  }

  /** 保存腾讯凭据（覆盖旧值）。加密不可用 → 明确失败，绝不落明文。 */
  function setTencent(creds) {
    const n = normalizeTencent(creds)
    if (!n.ok) return n
    if (!encryptionAvailable()) {
      return { ok: false, error: '本机系统加密不可用（钥匙串被锁 / 无安全存储），为安全起见不保存明文凭据。请先解锁钥匙串后重试。' }
    }
    let enc = ''
    try {
      enc = safe.encryptString(JSON.stringify(n.value)).toString('base64')
    } catch (err) {
      return { ok: false, error: '加密失败，腾讯凭据没有保存：' + ((err && err.message) || err) }
    }
    if (!enc) return { ok: false, error: '加密结果为空，腾讯凭据没有保存' }
    const cur = state()
    try { writeState({ provider: cur.provider, keys: cur.keys, tencent: enc }) } catch (err) {
      return { ok: false, error: '写盘失败，腾讯凭据没有保存：' + ((err && err.message) || err) }
    }
    return { ok: true }
  }

  /** 解出腾讯凭据（只给主进程内部用）。解不开一律 null 并留 lastError —— 绝不回半截凭据。 */
  function getTencent() {
    const enc = state().tencent
    if (!enc) return null
    if (!encryptionAvailable()) { lastError = '本机系统加密不可用，已保存的腾讯凭据现在读不出来'; return null }
    let raw = ''
    try {
      raw = safe.decryptString(Buffer.from(enc, 'base64'))
    } catch (err) {
      lastError = '解密腾讯凭据失败（可能换了系统账户 / 钥匙串被重置）：' + ((err && err.message) || err)
      return null
    }
    let obj = null
    try { obj = JSON.parse(String(raw || '')) } catch { lastError = '腾讯凭据密文内容不是合法 JSON，已按「未配置」处理'; return null }
    const n = normalizeTencent(obj)
    if (!n.ok) { lastError = n.error; return null }
    return n.value
  }

  /** 删除腾讯凭据。删不存在的也算成功。 */
  function deleteTencent() {
    const cur = state()
    if (!cur.tencent) return { ok: true, removed: false }
    try { writeState({ provider: cur.provider, keys: cur.keys, tencent: '' }) } catch (err) {
      return { ok: false, error: '写盘失败，腾讯凭据没有删除：' + ((err && err.message) || err) }
    }
    return { ok: true, removed: true }
  }

  return {
    file: file,
    encryptionAvailable: encryptionAvailable,
    provider: provider,
    setProvider: setProvider,
    deleteKey: deleteKey,
    getKey: getKey,
    setKey: setKey,
    setTencent: setTencent,
    getTencent: getTencent,
    deleteTencent: deleteTencent,
    // 界面只读这个布尔：三元组从不回渲染器
    hasTencent: () => !!state().tencent,
    // 只回「有没有」，密文与明文都不出这个模块
    hasKey: (p) => (isProvider(p) ? !!state().keys[p] : false),
    lastError: () => lastError,
    /** 测试 / 诊断用：丢掉内存缓存，下次从盘上重读。 */
    reset: () => { cache = null; lastError = '' }
  }
}

module.exports = { createSecretStore: createSecretStore, FILE_NAME: FILE_NAME, PROVIDERS: PROVIDERS }
