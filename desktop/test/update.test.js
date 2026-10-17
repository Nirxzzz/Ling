'use strict'
/* update.js 单测：纯行为边界（版本比较 / manifest 校验 / 平台资产挑选 / 地址与 sha 白名单）。
 * 只测这个纯模块的真实输出，不测 main.js 的接线文本，也不联网。
 * 跑：node --test test/update.test.js
 */
const test = require('node:test')
const assert = require('node:assert')
const U = require('../update')

const HEX64 = 'a'.repeat(64)                       // 合法 64 位
const HEX64U = 'A1B2C3D4'.repeat(8)                // 合法且含大写（应归一成小写）

function goodManifest(extra) {
  return Object.assign({
    version: '1.60.0',
    notes: '修了几个小毛病',
    assets: {
      'darwin-arm64': { url: 'https://example.com/Ling-1.60.0-darwin-arm64.zip', sha256: HEX64 },
      'win32-x64': { url: 'http://example.com/Ling-1.60.0-win32-x64.zip', sha256: HEX64 }
    }
  }, extra || {})
}

/* ---------------- 版本：解析 / 比较 / 状态 ---------------- */

test('parseVersion：只认严格三段数字', () => {
  assert.deepStrictEqual(U.parseVersion('1.60.0'), [1, 60, 0])
  assert.deepStrictEqual(U.parseVersion(' 1.2.3 '), [1, 2, 3])   // 允许首尾空白
  assert.strictEqual(U.parseVersion('1.60'), null)               // 两段不认
  assert.strictEqual(U.parseVersion('1.60.0.1'), null)           // 四段不认
  assert.strictEqual(U.parseVersion('1.60.0-beta'), null)        // 预发布后缀不认
  assert.strictEqual(U.parseVersion('v1.60.0'), null)
  assert.strictEqual(U.parseVersion('1.x.0'), null)
  assert.strictEqual(U.parseVersion(''), null)
  assert.strictEqual(U.parseVersion(null), null)
  assert.strictEqual(U.parseVersion(1600), null)
})

test('compareVersions：段内按数值比，不按字典序', () => {
  assert.strictEqual(U.compareVersions('1.60.0', '1.60.0'), 0)
  assert.strictEqual(U.compareVersions('1.60.1', '1.60.0'), 1)
  assert.strictEqual(U.compareVersions('1.60.0', '1.60.1'), -1)
  assert.strictEqual(U.compareVersions('1.10.0', '1.9.0'), 1)    // 数值 10>9（字典序会给错）
  assert.strictEqual(U.compareVersions('2.0.0', '1.99.99'), 1)
  assert.strictEqual(U.compareVersions('1.60.0', '2.0.0'), -1)
  // 非法输入不给结论，返回 null（调用方不能据此当「更新」）
  assert.strictEqual(U.compareVersions('1.60', '1.60.0'), null)
  assert.strictEqual(U.compareVersions('1.60.0', undefined), null)
})

test('isNewer：只有严格更大才算新', () => {
  assert.strictEqual(U.isNewer('1.61.0', '1.60.0'), true)
  assert.strictEqual(U.isNewer('1.60.0', '1.60.0'), false)       // 相等 ≠ 新
  assert.strictEqual(U.isNewer('1.59.9', '1.60.0'), false)
  assert.strictEqual(U.isNewer('1.61', '1.60.0'), false)         // 非法 → 不当新
})

test('statusFor：相等 / 更小 / 无法比较都落 latest，不虚构 new', () => {
  assert.strictEqual(U.statusFor('1.61.0', '1.60.0'), 'new')
  assert.strictEqual(U.statusFor('1.60.0', '1.60.0'), 'latest')  // 相等 = 已是最新版，不提供下载
  assert.strictEqual(U.statusFor('1.59.0', '1.60.0'), 'latest')
  assert.strictEqual(U.statusFor('坏版本', '1.60.0'), 'latest')   // 解析不了 → 保守 latest
})

/* ---------------- 地址 / sha 白名单 ---------------- */

test('isHttpUrl：只放行 http(s) 绝对地址', () => {
  assert.strictEqual(U.isHttpUrl('https://example.com/a.zip'), true)
  assert.strictEqual(U.isHttpUrl('http://127.0.0.1/a.zip'), true)
  assert.strictEqual(U.isHttpUrl('ftp://x/a.zip'), false)
  assert.strictEqual(U.isHttpUrl('file:///etc/passwd'), false)
  assert.strictEqual(U.isHttpUrl('javascript:alert(1)'), false)
  assert.strictEqual(U.isHttpUrl('/a/b.zip'), false)             // 相对路径不算
  assert.strictEqual(U.isHttpUrl(''), false)
  assert.strictEqual(U.isHttpUrl(null), false)
})

test('isSha256：恰好 64 位十六进制', () => {
  assert.strictEqual(U.isSha256(HEX64), true)
  assert.strictEqual(U.isSha256(HEX64U), true)                   // 大写也认
  assert.strictEqual(U.isSha256('a'.repeat(63)), false)          // 少一位
  assert.strictEqual(U.isSha256('a'.repeat(65)), false)          // 多一位
  assert.strictEqual(U.isSha256('z'.repeat(64)), false)          // 非十六进制
  assert.strictEqual(U.isSha256(''), false)
  assert.strictEqual(U.isSha256(undefined), false)
})

test('manifestUrl：默认没有更新源，只有显式设置 LING_UPDATE_URL 才启用', () => {
  const old = process.env.LING_UPDATE_URL
  try {
    delete process.env.LING_UPDATE_URL
    assert.strictEqual(U.manifestUrl(), '', '默认必须为空：不指向任何维护者服务器')
    process.env.LING_UPDATE_URL = '  https://example.com/manifest.json  '
    assert.strictEqual(U.manifestUrl(), 'https://example.com/manifest.json')
    process.env.LING_UPDATE_URL = ''
    assert.strictEqual(U.manifestUrl(), '')
  } finally {
    if (old === undefined) delete process.env.LING_UPDATE_URL
    else process.env.LING_UPDATE_URL = old
  }
})

/* ---------------- manifest 校验 ---------------- */

test('validateManifest：合法 manifest 归一通过', () => {
  const r = U.validateManifest(goodManifest())
  assert.strictEqual(r.ok, true)
  assert.strictEqual(r.manifest.version, '1.60.0')
  assert.strictEqual(r.manifest.notes, '修了几个小毛病')
  assert.deepStrictEqual(Object.keys(r.manifest.assets).sort(), ['darwin-arm64', 'win32-x64'])
})

test('validateManifest：sha256 归一成小写', () => {
  const m = goodManifest()
  m.assets['darwin-arm64'].sha256 = HEX64U
  const r = U.validateManifest(m)
  assert.strictEqual(r.ok, true)
  assert.strictEqual(r.manifest.assets['darwin-arm64'].sha256, HEX64U.toLowerCase())
})

test('validateManifest：非对象 / 数组直接拒', () => {
  assert.strictEqual(U.validateManifest(null).ok, false)
  assert.strictEqual(U.validateManifest(undefined).ok, false)
  assert.strictEqual(U.validateManifest('x').ok, false)
  assert.strictEqual(U.validateManifest([goodManifest()]).ok, false)
})

test('validateManifest：version 缺失 / 非三段数字拒', () => {
  assert.strictEqual(U.validateManifest(goodManifest({ version: undefined })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ version: '1.60' })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ version: '1.60.0-rc1' })).ok, false)
})

test('validateManifest：notes 非字符串拒（数字 / 对象都不行）', () => {
  assert.strictEqual(U.validateManifest(goodManifest({ notes: 123 })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ notes: {} })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ notes: undefined })).ok, true)   // 不给 notes = 空说明，合法
})

test('validateManifest：assets 缺失 / 非对象拒', () => {
  assert.strictEqual(U.validateManifest(goodManifest({ assets: undefined })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ assets: [] })).ok, false)
  assert.strictEqual(U.validateManifest(goodManifest({ assets: 'x' })).ok, false)
})

test('validateManifest：资产 url 非 http(s) 或 sha 非法拒', () => {
  const bad1 = goodManifest(); bad1.assets['win32-x64'].url = 'file:///tmp/x.zip'
  assert.strictEqual(U.validateManifest(bad1).ok, false)
  const bad2 = goodManifest(); bad2.assets['darwin-arm64'].sha256 = 'short'
  assert.strictEqual(U.validateManifest(bad2).ok, false)
  const bad3 = goodManifest(); bad3.assets['darwin-arm64'] = 'not-an-object'
  assert.strictEqual(U.validateManifest(bad3).ok, false)
})

test('validateManifest：只保留认识的资产键，多余键忽略且不因此失败', () => {
  const m = goodManifest()
  m.assets['linux-x64'] = { url: 'not-a-url', sha256: 'nope' }   // 不认识的键：完全不看
  const r = U.validateManifest(m)
  assert.strictEqual(r.ok, true)
  assert.strictEqual(r.manifest.assets['linux-x64'], undefined)
})

test('validateManifest：空 assets 对象结构合法（平台缺失留给 pickAsset 判）', () => {
  const r = U.validateManifest({ version: '1.60.0', notes: '', assets: {} })
  assert.strictEqual(r.ok, true)
  assert.deepStrictEqual(r.manifest.assets, {})
})

/* ---------------- 平台键 / 挑资产 ---------------- */

test('platformKey：只认 darwin-arm64 与 win32-x64', () => {
  assert.strictEqual(U.platformKey('darwin', 'arm64'), 'darwin-arm64')
  assert.strictEqual(U.platformKey('win32', 'x64'), 'win32-x64')
  assert.strictEqual(U.platformKey('darwin', 'x64'), null)       // Intel mac 本轮不支持
  assert.strictEqual(U.platformKey('win32', 'arm64'), null)
  assert.strictEqual(U.platformKey('linux', 'x64'), null)
})

test('pickAsset：按平台选到 / 缺失给 null', () => {
  const m = U.validateManifest(goodManifest()).manifest
  const mac = U.pickAsset(m, 'darwin', 'arm64')
  assert.deepStrictEqual(mac, { key: 'darwin-arm64', url: m.assets['darwin-arm64'].url, sha256: HEX64 })
  const win = U.pickAsset(m, 'win32', 'x64')
  assert.strictEqual(win.key, 'win32-x64')
  assert.strictEqual(U.pickAsset(m, 'linux', 'x64'), null)       // 平台不支持
  const only = U.validateManifest({ version: '1.60.0', notes: '', assets: { 'win32-x64': { url: 'http://x/a.zip', sha256: HEX64 } } }).manifest
  assert.strictEqual(U.pickAsset(only, 'darwin', 'arm64'), null) // 该平台资产缺失
})

test('assetFileName：去掉路径分隔等危险字符', () => {
  assert.strictEqual(U.assetFileName('1.60.0', 'darwin-arm64'), 'Ling-1.60.0-darwin-arm64.zip')
  assert.strictEqual(U.assetFileName('../1.60.0', 'win32-x64'), 'Ling-.._1.60.0-win32-x64.zip')
  assert.strictEqual(U.assetFileName('1.60.0', 'a/b'), 'Ling-1.60.0-a_b.zip')
})
