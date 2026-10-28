// voice-secrets：BYOK 密钥加密落盘的回归（无 Electron：safeStorage 用测试替身注入）。
// 断言的是真实文件与真实行为：加密不可用时拒绝保存、文件里没有明文、两家 Key 互不覆盖。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const { createSecretStore, FILE_NAME } = require('../voice-secrets')

const KEY_DOUBAO = 'db-key-1234567890'
const KEY_ALI = 'sk-ali-abcdefghij'

function tmpDir() { return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-voice-secrets-')) }
function cleanup(dir) { if (dir) fs.rmSync(dir, { recursive: true, force: true }) }

/** 可切换「加密能不能用」的 safeStorage 替身：真把明文换成带前缀的字节，反解也校验前缀。 */
function fakeSafe(opts) {
  const o = opts || {}
  const state = { available: o.available !== false }
  return {
    state: state,
    isEncryptionAvailable: () => state.available,
    encryptString: (text) => Buffer.from('enc:' + String(text), 'utf8'),
    decryptString: (buf) => {
      const s = Buffer.from(buf).toString('utf8')
      if (!s.startsWith('enc:')) throw new Error('bad ciphertext')
      return s.slice(4)
    }
  }
}

test('两家 Key 分开保存、互不覆盖，读回明文只经 getKey（不落明文文件）', () => {
  const dir = tmpDir()
  try {
    const safe = fakeSafe()
    const st = createSecretStore({ dir: dir, safeStorage: safe })
    assert.strictEqual(st.hasKey('doubao'), false)
    assert.strictEqual(st.getKey('doubao'), null)

    assert.strictEqual(st.setKey('doubao', KEY_DOUBAO).ok, true)
    assert.strictEqual(st.setKey('alibaba', KEY_ALI).ok, true)
    assert.strictEqual(st.getKey('doubao'), KEY_DOUBAO)
    assert.strictEqual(st.getKey('alibaba'), KEY_ALI)
    assert.strictEqual(st.hasKey('doubao'), true)
    assert.strictEqual(st.hasKey('alibaba'), true)

    const file = path.join(dir, FILE_NAME)
    const raw = fs.readFileSync(file, 'utf8')
    assert.strictEqual(raw.includes(KEY_DOUBAO), false, '磁盘上绝不能出现明文 Key')
    assert.strictEqual(raw.includes(KEY_ALI), false, '磁盘上绝不能出现明文 Key')
    const mode = fs.statSync(file).mode & 0o777
    assert.strictEqual(mode, 0o600, '密钥文件必须是 0600')

    // 覆盖其中一家，另一家不动
    assert.strictEqual(st.setKey('alibaba', 'sk-ali-rotated').ok, true)
    assert.strictEqual(st.getKey('doubao'), KEY_DOUBAO)
    assert.strictEqual(st.getKey('alibaba'), 'sk-ali-rotated')

    // 删除只删指定那一家
    assert.deepStrictEqual(st.deleteKey('doubao'), { ok: true, removed: true })
    assert.strictEqual(st.getKey('doubao'), null)
    assert.strictEqual(st.getKey('alibaba'), 'sk-ali-rotated')
    assert.deepStrictEqual(st.deleteKey('doubao'), { ok: true, removed: false })
  } finally { cleanup(dir) }
})

test('系统加密不可用 → 拒绝保存，绝不含糊地落明文', () => {
  const dir = tmpDir()
  try {
    const safe = fakeSafe({ available: false })
    const st = createSecretStore({ dir: dir, safeStorage: safe })
    const r = st.setKey('doubao', KEY_DOUBAO)
    assert.strictEqual(r.ok, false)
    assert.match(r.error, /加密不可用/)
    assert.strictEqual(st.hasKey('doubao'), false)
    assert.strictEqual(fs.existsSync(path.join(dir, FILE_NAME)), false, '拒绝时连文件都不该建')

    // 后来加密可用了：正常保存
    safe.state.available = true
    assert.strictEqual(st.setKey('doubao', KEY_DOUBAO).ok, true)
    // 再变不可用：读不出来，但文件还在（不毁掉用户的 Key），并给出原因
    safe.state.available = false
    assert.strictEqual(st.getKey('doubao'), null)
    assert.match(st.lastError(), /加密不可用/)
    assert.strictEqual(fs.existsSync(path.join(dir, FILE_NAME)), true)
    safe.state.available = true
    assert.strictEqual(st.getKey('doubao'), KEY_DOUBAO, '加密恢复后旧 Key 必须还能读出来')
  } finally { cleanup(dir) }
})

test('文件损坏 / 缺字段 / 未知服务商都不抛，按「未配置」处理并保留原因', () => {
  const dir = tmpDir()
  try {
    const file = path.join(dir, FILE_NAME)
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(file, '{ 不是 json')
    const st = createSecretStore({ dir: dir, safeStorage: fakeSafe() })
    assert.strictEqual(st.hasKey('doubao'), false)
    assert.match(st.lastError(), /不是合法 JSON/)
    assert.strictEqual(st.getKey('doubao'), null, '坏文件不能把明文吐出去')

    // 解密失败（换机器 / 钥匙串重置）：当没有 Key，并把原因留在 lastError
    fs.writeFileSync(file, JSON.stringify({ version: 1, provider: 'doubao', keys: { doubao: Buffer.from('garbage').toString('base64') } }))
    const st2 = createSecretStore({ dir: dir, safeStorage: fakeSafe() })
    assert.strictEqual(st2.hasKey('doubao'), true, 'hasKey 只看有没有存过（界面用它显示「已保存」）')
    assert.strictEqual(st2.getKey('doubao'), null)
    assert.match(st2.lastError(), /解密/)

    // 未知服务商一律拒绝，不写盘
    const st3 = createSecretStore({ dir: dir, safeStorage: fakeSafe() })
    assert.strictEqual(st3.setKey('nope', 'x').ok, false)
    assert.strictEqual(st3.setProvider('nope').ok, false)
    assert.strictEqual(st3.deleteKey('nope').ok, false)
    assert.strictEqual(st3.getKey('nope'), null)
  } finally { cleanup(dir) }
})

test('服务商选择是明文且可持久化；空 Key 不覆盖已有 Key', () => {
  const dir = tmpDir()
  try {
    const st = createSecretStore({ dir: dir, safeStorage: fakeSafe() })
    assert.strictEqual(st.provider(), '')
    assert.strictEqual(st.setProvider('alibaba').ok, true)
    assert.strictEqual(st.provider(), 'alibaba')
    assert.strictEqual(fs.readFileSync(path.join(dir, FILE_NAME), 'utf8').includes('"provider":"alibaba"'), true)

    assert.strictEqual(st.setKey('alibaba', KEY_ALI).ok, true)
    const r = st.setKey('alibaba', '   ')
    assert.strictEqual(r.ok, false, '空 Key 不是「删除」，不能悄悄把已存的覆盖成空')
    assert.strictEqual(st.getKey('alibaba'), KEY_ALI)

    // 新实例从盘上重读（选择与 Key 都要活过重启）
    const st2 = createSecretStore({ dir: dir, safeStorage: fakeSafe() })
    assert.strictEqual(st2.provider(), 'alibaba')
    assert.strictEqual(st2.getKey('alibaba'), KEY_ALI)
  } finally { cleanup(dir) }
})
