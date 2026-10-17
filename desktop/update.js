'use strict'
/* 桌面更新通道 —— 纯逻辑，不依赖 electron。
 *
 * 主进程只负责「取 manifest / 下载 / 校验落盘」，版本比较、manifest 结构校验、平台资产挑选
 * 全部收在这个文件里，test/update.test.js 直接 require 它跑行为边界（不测 main.js 的接线文本）。
 *
 * 开源构建默认**没有更新源**：不指向原作者的仓库，也不访问任何维护者服务器。
 * 使用者要自建更新时显式设置环境变量 LING_UPDATE_URL（manifest 地址）。
 *
 * manifest 契约：
 *   { version: "1.60.0", notes: "...",
 *     assets: { "darwin-arm64": { url: "https://...zip", sha256: "<64 hex>" },
 *               "win32-x64":    { url: "https://...zip", sha256: "<64 hex>" } } }
 */

// 更新源：默认空。只有使用者显式设置 LING_UPDATE_URL 才启用；未知 / 空值一律表示「无源」。
function manifestUrl() {
  const u = process.env.LING_UPDATE_URL
  return typeof u === 'string' ? u.trim() : ''
}

// 本轮支持打包的桌面平台资产键（多出来的键一律忽略；缺失 = 该平台明确不支持）
const ASSET_KEYS = ['darwin-arm64', 'win32-x64']

/** '1.60.0' → [1,60,0]；非「三段数字」→ null（保证严格数值比较，不认 1.60 / 1.60.0-beta） */
function parseVersion(v) {
  if (typeof v !== 'string') return null
  const m = /^(\d{1,9})\.(\d{1,9})\.(\d{1,9})$/.exec(v.trim())
  if (!m) return null
  return [Number(m[1]), Number(m[2]), Number(m[3])]
}

/** 三段数值严格比较：a>b → 1，a<b → -1，相等 → 0；任一非法 → null */
function compareVersions(a, b) {
  const x = parseVersion(a), y = parseVersion(b)
  if (!x || !y) return null
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i] ? 1 : -1
  return 0
}

/** latest 是否严格大于 current（相等不算「新」） */
function isNewer(latest, current) { return compareVersions(latest, current) === 1 }

/** 版本比较 → 状态：严格更大才 new；相等 / 更小 / 无法比较都 latest（绝不虚构 new） */
function statusFor(latest, current) { return compareVersions(latest, current) === 1 ? 'new' : 'latest' }

/** 只接受 http(s) 绝对地址 */
function isHttpUrl(u) {
  if (typeof u !== 'string') return false
  let m
  try { m = new URL(u) } catch { return false }
  return m.protocol === 'http:' || m.protocol === 'https:'
}

/** 64 位十六进制 sha256（大小写都收） */
function isSha256(s) { return typeof s === 'string' && /^[0-9a-f]{64}$/i.test(s) }

/** 当前运行平台 → 资产键；不支持的平台返回 null */
function platformKey(platform, arch) {
  if (platform === 'darwin' && arch === 'arm64') return 'darwin-arm64'
  if (platform === 'win32' && arch === 'x64') return 'win32-x64'
  return null
}

/**
 * 校验 manifest 结构。返回 { ok: true, manifest }（manifest 已归一：version 去空白、notes 兜空串、
 * sha256 转小写、只保留认识的资产键）或 { ok: false, error: '说明' }。
 */
function validateManifest(obj) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return { ok: false, error: 'manifest 不是对象' }
  if (!parseVersion(obj.version)) return { ok: false, error: 'version 不是三段数字（如 1.60.0）' }
  if (obj.notes != null && typeof obj.notes !== 'string') return { ok: false, error: 'notes 不是字符串' }
  if (!obj.assets || typeof obj.assets !== 'object' || Array.isArray(obj.assets)) return { ok: false, error: 'assets 缺失或不是对象' }
  const assets = {}
  for (const k of ASSET_KEYS) {
    const a = obj.assets[k]
    if (a == null) continue
    if (typeof a !== 'object' || Array.isArray(a)) return { ok: false, error: k + ' 的资产不是对象' }
    if (!isHttpUrl(a.url)) return { ok: false, error: k + ' 的 url 不是 http(s) 地址' }
    if (!isSha256(a.sha256)) return { ok: false, error: k + ' 的 sha256 不是 64 位十六进制' }
    assets[k] = { url: a.url, sha256: a.sha256.toLowerCase() }
  }
  return { ok: true, manifest: { version: obj.version.trim(), notes: typeof obj.notes === 'string' ? obj.notes : '', assets } }
}

/** 按运行平台挑资产；平台不支持或该键缺失 → null（调用方据此给「暂不支持」） */
function pickAsset(manifest, platform, arch) {
  const key = platformKey(platform, arch)
  if (!key || !manifest || !manifest.assets) return null
  const a = manifest.assets[key]
  if (!a) return null
  return { key, url: a.url, sha256: a.sha256 }
}

/** 下载文件名：Ling-<version>-<key>.zip；非法字符（含路径分隔）一律替换成 _ */
function assetFileName(version, key) {
  const v = String(version == null ? '' : version).replace(/[^0-9A-Za-z._-]/g, '_')
  const k = String(key == null ? '' : key).replace(/[^0-9A-Za-z._-]/g, '_')
  return 'Ling-' + v + '-' + k + '.zip'
}

module.exports = {
  ASSET_KEYS,
  manifestUrl, parseVersion, compareVersions, isNewer, statusFor,
  isHttpUrl, isSha256, platformKey, validateManifest, pickAsset, assetFileName
}
