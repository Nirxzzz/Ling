/* v1.58（对齐安卓 v1.58）：主进程里新加的几样，独立成一个文件，main.js 只管接线。
 *  - 百度翻译开放平台（划词翻译可选引擎）
 *  - 结果缓存（同一段文字 / 同一个画面不重复调接口）
 *  - 历史记录落盘（以前只在内存里留 30 条，退出就没了）
 * 只用 Node 自带模块，不碰 electron —— 方便单独跑 node 测。 */
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')

/* ---------------- 百度翻译 ---------------- */
const BAIDU_ENDPOINT = 'https://fanyi-api.baidu.com/ait/api/aiTextTranslate'
const BAIDU_MODELS = { llm: '大模型翻译', nmt: '机器翻译' }
// 百度错误码 → [大白话, 值不值得自动重试一次]
const BAIDU_ERRORS = {
  52001: ['百度超时，稍后再试', true], 52002: ['百度系统错误，稍后再试', true],
  52003: ['未授权：APP ID 或 API Key 不对，或者还没开通这个服务', false],
  54000: ['必填参数为空（APP ID 没填，或 API Key 不对）', false], 54001: ['签名错误：检查 APP ID 和密钥', false],
  54003: ['请求太频繁（标准版每秒只能 1 次左右）', true], 54004: ['百度账户余额不足 / 免费额度用完', false],
  54005: ['长文本请求太频繁，等 3 秒再试', true], 58000: ['IP 不在百度控制台设置的白名单里', false],
  58001: ['不支持这个翻译方向', false], 58002: ['这个服务在百度控制台被关掉了', false], 90107: ['认证还没生效，稍后再试', false]
}
// 灵珠的语言 id → 百度语种代码
const BAIDU_CODE = { zh: 'zh', en: 'en', ja: 'jp', ko: 'kor', ru: 'ru', fr: 'fra', de: 'de' }

/** 目标语种：「自动」= 中英互译，原文里汉字多就译成英文，否则译成中文 */
function baiduTarget(langId, text) {
  if (BAIDU_CODE[langId]) return BAIDU_CODE[langId]
  const s = String(text || '')
  const han = (s.match(/\p{Script=Han}/gu) || []).length
  const letters = (s.match(/\p{L}/gu) || []).length
  return letters > 0 && han * 2 >= letters ? 'en' : 'zh'
}

const md5 = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex')

function baiduConfigured(b) { return !!(b && (b.apiKey || (b.appId && b.secret))) }

/** 请求体：填了 API Key 用 Bearer（此时不能带 salt/sign，否则 53001）；否则 APP ID + 密钥签名，salt 必须是数字 */
function baiduBody(b, text, to, salt) {
  const o = { q: text, from: 'auto', to, model_type: BAIDU_MODELS[b.model] ? b.model : 'llm' }
  if (b.apiKey) { if (b.appId) o.appid = b.appId }
  else {
    const s = salt != null ? salt : Math.floor(1e9 + Math.random() * 8.99e9)
    o.appid = b.appId; o.salt = s; o.sign = md5(b.appId + text + s + b.secret)
  }
  return o
}

function baiduParse(body) {
  let o
  try { o = JSON.parse(body) } catch { throw new Error('百度返回的不是 JSON：' + String(body).slice(0, 120)) }
  const code = o.error_code != null ? String(o.error_code) : ''
  if (code && code !== '0' && code !== '52000') {
    const e = BAIDU_ERRORS[code]
    const err = new Error('百度翻译出错（' + code + '）：' + (e ? e[0] : (o.error_msg || '未知错误')))
    err.retryable = !!(e && e[1])
    throw err
  }
  if (!Array.isArray(o.trans_result)) throw new Error('百度没返回译文：' + String(body).slice(0, 120))
  return o.trans_result.map((x) => (x && x.dst) || '').join('\n')
}

async function baiduTranslate(b, text, langId, opts) {
  opts = opts || {}
  if (!baiduConfigured(b)) throw new Error('还没填百度翻译的 API Key（或 APP ID + 密钥）：小球右键 → 设置 → 翻译引擎')
  const to = baiduTarget(langId, text)
  const endpoint = opts.endpoint || BAIDU_ENDPOINT
  let last = null
  for (let attempt = 0; attempt < 2; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 1100))
    const headers = { 'Content-Type': 'application/json; charset=utf-8' }
    if (b.apiKey) headers.Authorization = 'Bearer ' + b.apiKey
    const ac = new AbortController()
    const tm = setTimeout(() => ac.abort(), 30000)
    let res, t
    try {
      res = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(baiduBody(b, text, to)), signal: ac.signal })
      t = await res.text()
    } finally { clearTimeout(tm) }
    if (!res.ok && !/error_code/.test(t)) throw new Error('百度翻译 HTTP ' + res.status + ' ' + t.slice(0, 160))
    try { return baiduParse(t) } catch (e) { if (!e.retryable) throw e; last = e }
  }
  throw last
}

/* ---------------- DeepL（v1.59）----------------
 * Key 以 ":fx" 结尾是免费版（api-free.deepl.com），否则是 Pro（api.deepl.com）。
 * 翻译只给译文（和百度一样，没有词性 / 要点）。 */
const DEEPL_CODE = { zh: 'ZH-HANS', en: 'EN-US', ja: 'JA', ko: 'KO', ru: 'RU', fr: 'FR', de: 'DE' }
const DEEPL_ERRORS = {
  400: ['请求有误（多半是语言不支持）', false], 403: ['DeepL Key 不对或已失效', false], 404: ['DeepL 接口地址不对', false],
  413: ['一次翻的字太多', false], 414: ['一次翻的字太多', false], 429: ['请求太频繁', true], 456: ['DeepL 本月字符额度用完了', false],
  500: ['DeepL 服务器出错', true], 502: ['DeepL 服务器出错', true], 503: ['DeepL 暂时不可用', true], 504: ['DeepL 超时', true], 529: ['请求太频繁', true]
}
function deeplEndpoint(key) { return /:fx$/.test(String(key || '').trim()) ? 'https://api-free.deepl.com' : 'https://api.deepl.com' }
/** 目标语种：「自动」和百度一样中英互译 */
function deeplTarget(langId, text) { return DEEPL_CODE[langId] || (baiduTarget('auto', text) === 'en' ? 'EN-US' : 'ZH-HANS') }
function deeplParse(status, body) {
  if (status !== 200) {
    const e = DEEPL_ERRORS[status]
    const err = new Error('DeepL 出错（' + status + '）：' + (e ? e[0] : String(body || '').slice(0, 120)))
    err.retryable = !!(e && e[1]) || status >= 500
    throw err
  }
  let o
  try { o = JSON.parse(body) } catch { throw new Error('DeepL 返回的不是 JSON：' + String(body).slice(0, 120)) }
  if (!o || !Array.isArray(o.translations) || !o.translations.length) throw new Error('DeepL 没返回译文')
  return o.translations.map((x) => (x && x.text) || '').join('\n')
}
async function deeplTranslate(key, text, langId, opts) {
  opts = opts || {}
  key = String(key || '').trim()
  if (!key) throw new Error('还没有 DeepL Key')
  const url = (opts.endpoint || deeplEndpoint(key)) + '/v2/translate'
  let last = null
  for (let attempt = 0; attempt < 2; attempt++) {
    if (attempt > 0) await new Promise((r) => setTimeout(r, 1200))
    const ac = new AbortController()
    const tm = setTimeout(() => ac.abort(), opts.timeoutMs || 20000)
    let res, t
    try {
      res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'DeepL-Auth-Key ' + key },
        body: JSON.stringify({ text: [text], target_lang: deeplTarget(langId, text) }), signal: ac.signal })
      t = await res.text()
    } catch (e) {
      last = new Error('连不上 DeepL（' + (e.name === 'AbortError' ? '超时' : (e.cause && e.cause.code) || e.message) + '）'); last.retryable = true
      continue
    } finally { clearTimeout(tm) }
    try { return deeplParse(res.status, t) } catch (e) { if (!e.retryable) throw e; last = e }
  }
  throw last
}

/* ---------------- 结果缓存 ---------------- */
const TTL_MS = 7 * 24 * 3600 * 1000
const sha256 = (s) => crypto.createHash('sha256').update(s, 'utf8').digest('hex')
/** 多段拼成一个键（段之间用不可能出现的字符隔开，免得 "ab"+"c" 和 "a"+"bc" 撞） */
function cacheKey(...parts) { return sha256(parts.map(String).join('\u0000')) }

/** 纯函数：命中 → [项, 把它挪到最前的新列表]；过期的顺手丢掉 */
function cacheGetList(list, k, now, ttl) {
  const alive = list.filter((x) => now - x.c < (ttl || TTL_MS))
  const hit = alive.find((x) => x.k === k)
  if (!hit) return [null, alive]
  const b = Object.assign({}, hit, { u: now })
  return [b, [b].concat(alive.filter((x) => x.k !== k))]
}
function cachePutList(list, k, v, now, max, ttl) {
  return [{ k, v, c: now, u: now }].concat(list.filter((x) => x.k !== k && now - x.c < (ttl || TTL_MS))).slice(0, max)
}

/* ---------------- 同步协作钩子 ----------------
 * 局域网同步（lan-store）会在业务写盘前要求先恢复 pending journal，避免重启后
 * 旧 journal 把后来做的编辑回滚掉。这里提供两个模块级钩子：
 *   · setWriteGuard(fn)：JsonStore 每次写 / 清空前先跑 fn()（恢复失败就拒绝写）。
 *   · strictRead(fn)：在这段同步代码里，JsonStore.read() 读失败直接抛，
 *     不再静默返回 [] —— 同步绝不能把「读失败」当成「库空了」给对端发墓碑。
 * 两者都带重入保护：恢复流程自己触发的写 / 读不会再递归调用钩子。
 */
let writeGuard = null
let guardDepth = 0
let strictReadDepth = 0

function setWriteGuard(fn) {
  writeGuard = typeof fn === 'function' ? fn : null
}

function runWriteGuard() {
  if (typeof writeGuard !== 'function' || guardDepth > 0) return
  guardDepth += 1
  try { writeGuard() } finally { guardDepth -= 1 }
}

function strictRead(fn) {
  strictReadDepth += 1
  try { return fn() } finally { strictReadDepth -= 1 }
}

/** 按文件路径做一次严格读（main 的 histReadStrict 兼容入口）。 */
function readStrict(file) {
  return new JsonStore(file).readStrict()
}

class JsonStore {
  constructor(file) {
    this.file = file
    this.lastError = null
    this.missing = false
  }

  /** 读一次盘，返回 { ok, missing, list, error }；不缓存、不吞错。 */
  readResult() {
    let raw = ''
    try { raw = fs.readFileSync(this.file, 'utf8') } catch (err) {
      // 文件确实不存在 = 合法空（用户清空 / 首次运行）；其余读失败都不是空库。
      if (err && err.code === 'ENOENT') {
        this.lastError = null
        this.missing = true
        return { ok: true, missing: true, list: [], error: null }
      }
      this.lastError = err
      this.missing = false
      return { ok: false, missing: false, list: [], error: err }
    }
    this.missing = false
    if (!String(raw).trim()) {
      const err = new Error('JsonStore: ' + this.file + ' 是空文件')
      this.lastError = err
      return { ok: false, missing: false, list: [], error: err }
    }
    let arr
    try { arr = JSON.parse(raw) } catch (err) {
      this.lastError = err
      return { ok: false, missing: false, list: [], error: err }
    }
    if (!Array.isArray(arr)) {
      const err = new Error('JsonStore: ' + this.file + ' 不是数组')
      this.lastError = err
      return { ok: false, missing: false, list: [], error: err }
    }
    this.lastError = null
    return { ok: true, missing: false, list: arr, error: null }
  }

  /**
   * 普通读法：失败返回空数组（界面兼容），但**不缓存**这个空结果；
   * 在 strictRead() 包裹的同步路径里则直接抛，调用方必须阻断同步。
   */
  read() {
    const r = this.readResult()
    if (!r.ok) {
      if (strictReadDepth > 0) throw r.error
      return []
    }
    return r.list
  }

  /** 同步专用严格读法：只有 ENOENT 算合法空，其余都抛。 */
  readStrict() {
    const r = this.readResult()
    if (!r.ok) throw r.error
    return r.list
  }

  /** 只报告读状态，不缓存、不抛。 */
  readStatus() {
    const r = this.readResult()
    return { ok: r.ok, missing: r.missing, count: r.list.length, error: r.error }
  }

  write(l) {
    try { runWriteGuard() } catch (err) { this.lastError = err; return false }
    try {
      fs.writeFileSync(this.file, JSON.stringify(l), { mode: 0o600 })
      this.lastError = null
      this.missing = false
      return true
    } catch (err) {
      this.lastError = err
      return false
    }
  }

  clear() {
    try { runWriteGuard() } catch (err) { this.lastError = err; return false }
    try {
      fs.unlinkSync(this.file)
      this.lastError = null
      this.missing = true
      return true
    } catch (err) {
      if (err && err.code === 'ENOENT') {
        this.lastError = null
        this.missing = true
        return true
      }
      this.lastError = err
      return false
    }
  }

  size() { try { return fs.statSync(this.file).size } catch { return 0 } }
}

class Cache {
  constructor(dir, name, max) { this.store = new JsonStore(path.join(dir, 'cache-' + name + '.json')); this.max = max }
  get(k) { const [hit, l] = cacheGetList(this.store.read(), k, Date.now()); if (hit) this.store.write(l); return hit }
  put(k, v) { if (v && String(v).trim()) this.store.write(cachePutList(this.store.read(), k, v, Date.now(), this.max)) }
  count() { return this.store.read().length }
  bytes() { return this.store.size() }
  clear() { this.store.clear() }
}

/** 「刚刚」/「12 分钟前」/「3 小时前」/「2 天前」 */
function ago(t, now) {
  const m = Math.floor(((now || Date.now()) - t) / 60000)
  return m < 1 ? '刚刚' : m < 60 ? m + ' 分钟前' : m < 1440 ? Math.floor(m / 60) + ' 小时前' : Math.floor(m / 1440) + ' 天前'
}

/**
 * 画面指纹：只认一模一样的画面（宁可不命中也不拿错答案）。
 * bitmap = BGRA 像素（NativeImage.toBitmap），先由调用方缩到很小；去掉最上面 skipTop 比例（菜单栏的时钟会变），
 * 每像素只留灰度高 4 位（JPEG / 缩放的细微噪声不影响），再整体哈希。
 */
function fingerprint(bitmap, w, h, skipTop, masks) {
  // masks：灵珠自己的窗口（小球 / 答案面板 / 翻译面板）在这张缩略图上的矩形 —— 这些地方的内容每次都变，不算进指纹
  const inMask = (x, y) => (masks || []).some((m) => x >= m.x && x < m.x + m.w && y >= m.y && y < m.y + m.h)
  const top = Math.min(h, Math.floor(h * (skipTop == null ? 0.05 : skipTop)))
  const hash = crypto.createHash('sha256').update(w + 'x' + (h - top) + ':')
  const row = Buffer.alloc(w)
  for (let y = top; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      const g = (bitmap[i + 2] * 299 + bitmap[i + 1] * 587 + bitmap[i] * 114) / 1000
      row[x] = inMask(x, y) ? 42 : 97 + (Math.min(255, g) >> 4)
    }
    hash.update(row)
  }
  return hash.digest('hex')
}

/* ---------------- 历史记录（落盘） ---------------- */
const HISTORY_MAX = 200

/** 历史行只保留已知字段；star 只有 true 才写出（旧行不会被强行补 star:false）。 */
function historyToJson(e) {
  const rawT = e && typeof e.t === 'number' ? e.t : Number(e && e.t)
  const o = {
    t: Number.isFinite(rawT) ? rawT : 0,
    kind: String(e && e.kind != null ? e.kind : ''),
    label: String(e && e.label != null ? e.label : ''),
    text: String(e && e.text != null ? e.text : ''),
    src: String(e && e.src != null ? e.src : '')
  }
  if (e && e.star === true) o.star = true
  return o
}

function historyToJsonList(list) { return (list || []).map(historyToJson) }

/** 解析 history.json；坏 JSON / 非数组 → 空；坏元素跳过。star 只认 true。 */
function historyParse(json) {
  if (json == null || json === '') return []
  let arr = json
  if (typeof json === 'string') {
    const s = json.trim()
    if (!s) return []
    try { arr = JSON.parse(s) } catch { return [] }
  }
  if (!Array.isArray(arr)) return []
  const out = []
  for (const e of arr) {
    if (!e || typeof e !== 'object' || Array.isArray(e)) continue
    const t = typeof e.t === 'number' ? e.t : Number(e.t)
    if (!Number.isFinite(t)) continue
    const row = {
      t: t,
      kind: String(e.kind == null ? '' : e.kind),
      label: String(e.label == null ? '' : e.label),
      text: String(e.text == null ? '' : e.text),
      src: String(e.src == null ? '' : e.src)
    }
    if (e.star === true) row.star = true
    out.push(row)
  }
  return out
}

/**
 * 裁剪历史：目标总数 max，但**收藏永远不删**。
 * 先保全部 star，再用剩下的名额从新到旧保留普通行；star 超过 max 时总数可以超过 max。
 */
function historyTrim(list, max) {
  const src = list || []
  const limit = max == null ? HISTORY_MAX : max
  let stars = 0
  for (const e of src) if (e && e.star === true) stars += 1
  let room = limit - stars
  if (room < 0) room = 0
  const out = []
  for (const e of src) {
    if (e && e.star === true) out.push(e)
    else if (room > 0) { out.push(e); room -= 1 }
  }
  return out
}

/** 加到最前；和最新一条内容完全一样（且 star 状态相同）就不重复记。 */
function historyAddList(list, e, max) {
  const f = list[0]
  if (f && f.kind === e.kind && f.text === e.text && (f.src || '') === (e.src || '') &&
    (f.star === true) === (e.star === true)) return list
  return historyTrim([e].concat(list), max || HISTORY_MAX)
}

module.exports = {
  BAIDU_ENDPOINT, BAIDU_MODELS, BAIDU_CODE, baiduTarget, baiduBody, baiduParse, baiduTranslate, baiduConfigured, md5,
  DEEPL_CODE, deeplEndpoint, deeplTarget, deeplParse, deeplTranslate,
  cacheKey, cacheGetList, cachePutList, Cache, JsonStore, ago, fingerprint, TTL_MS, HISTORY_MAX,
  setWriteGuard, runWriteGuard, strictRead, readStrict,
  historyAddList, historyParse, historyToJson, historyToJsonList, historyTrim
}
