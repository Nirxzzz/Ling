// 冷启动 pending journal：真实 main.js 的守卫 + 真实 Store / JsonStore / LanStore。
// 子进程只用假的 electron 壳（沿用 tool/smoke-main.js 的方式），业务处理器跑的是 main.js 原件。
// 只断言落盘数据和 IPC 返回值，不做源码字符串断言。
'use strict'

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const T0 = 1750000000000
const CHILD_FLAG = 'ORB_MAIN_RECOVERY_CHILD'
const JOURNAL = 'lan-sync.journal.json'

/* ------------------------------------------------------------------ 子进程 */

function runChild() {
  const dir = process.env.ORB_RECOVERY_DIR
  const scenario = process.env.ORB_RECOVERY_SCENARIO || 'restore'
  const handlers = new Map()
  const noop = () => {}

  // 万能假对象：任何属性 / 调用 / 构造都返回同类假对象（同 tool/smoke-main.js）
  function anyObj() {
    const f = function () {}
    return new Proxy(f, {
      get: (t, k) => {
        if (k === 'then') return undefined
        if (k === Symbol.toPrimitive) return () => 0
        if (k === 'toJSON') return () => '[fake]'
        return anyObj()
      },
      apply: () => anyObj(),
      construct: () => anyObj(),
      set: () => true
    })
  }

  const fakeApp = {
    getPath: () => dir,
    setPath: noop,
    getName: () => 'ling-opensource',
    requestSingleInstanceLock: () => true,
    disableHardwareAcceleration: noop,
    whenReady: () => Promise.resolve(),
    on: noop, quit: noop, exit: noop,
    dock: { hide: noop, show: noop },
    commandLine: { appendSwitch: noop }
  }

  const fakeElectron = {
    app: fakeApp,
    BrowserWindow: anyObj(),
    desktopCapturer: { getSources: async () => [] },
    screen: anyObj(),
    systemPreferences: { getMediaAccessStatus: () => 'granted', isTrustedAccessibilityClient: () => true },
    ipcMain: {
      on: noop,
      handle: (channel, fn) => { handlers.set(channel, fn) }
    },
    nativeImage: { createFromBuffer: () => ({ isEmpty: () => true, getSize: () => ({ width: 0 }) }) },
    shell: { openExternal: noop },
    clipboard: { readText: () => '', writeText: noop },
    globalShortcut: { register: () => true, unregisterAll: noop },
    Notification: function () { return { on: noop, show: noop } }
  }

  const Module = require('node:module')
  const origLoad = Module._load
  Module._load = function (req) {
    if (req === 'electron') return fakeElectron
    return origLoad.apply(this, arguments)
  }

  process.env.LING_USER_DATA = dir
  const mainPath = path.join(__dirname, '..', 'main.js')
  process.argv = [process.argv[0], mainPath]
  require(mainPath)

  // whenReady().then(...) 是微任务，setImmediate 在微任务之后跑，此时守卫已经装好。
  setImmediate(() => {
    const out = {}
    try {
      if (scenario === 'restore') {
        // 冷启动：先由 kcard:add 触发 journal 恢复，再新增，最后收藏一行。
        out.add = handlers.get('kcard:add')({}, '新卡\n\n## 答案\n新背面', '默认')
        out.star = handlers.get('history:star')({}, T0, true)
      } else {
        // 坏 journal：写操作必须被拒绝，且不能碰原文件。
        out.star = handlers.get('history:star')({}, T0, true)
        out.add = handlers.get('kcard:add')({}, '新卡\n\n## 答案\n新背面', '默认')
      }
    } catch (err) {
      out.fatal = String((err && err.stack) || err)
    }
    fs.writeSync(1, '__RESULT__' + JSON.stringify(out) + '\n')
    process.exit(0)
  })
}

/* ------------------------------------------------------------------ 父进程 */

if (process.env[CHILD_FLAG] === '1') {
  runChild()
} else {
  const test = require('node:test')
  const assert = require('node:assert')
  const K = require('../knowledge')
  const X = require('../extras')
  const LanStore = require('../lan-store')

  function tmpDir() {
    return fs.mkdtempSync(path.join(os.tmpdir(), 'orb-main-recovery-'))
  }

  function writeJson(file, value) {
    fs.writeFileSync(file, JSON.stringify(value), { mode: 0o600 })
  }

  function baseline(dir) {
    writeJson(path.join(dir, 'knowledge.json'), [K.toJson(K.card({
      id: 1, deck: '默认', front: '旧卡', back: '旧背面', source: 'manual', createdAt: T0
    }))])
    writeJson(path.join(dir, 'history.json'), [
      { t: T0, kind: 'clip', label: '老记录', text: '老内容', src: 'answer' }
    ])
  }

  function runMain(dir, scenario) {
    const { spawnSync } = require('node:child_process')
    const r = spawnSync(process.execPath, [__filename], {
      encoding: 'utf8',
      timeout: 30000,
      env: Object.assign({}, process.env, {
        [CHILD_FLAG]: '1',
        ORB_RECOVERY_DIR: dir,
        ORB_RECOVERY_SCENARIO: scenario
      })
    })
    assert.strictEqual(r.status, 0, '子进程失败：' + (r.stderr || '') + (r.stdout || ''))
    const line = String(r.stdout || '').split('\n').find((x) => x.startsWith('__RESULT__'))
    assert.ok(line, '子进程没有返回结果：' + (r.stderr || '') + (r.stdout || ''))
    const out = JSON.parse(line.slice('__RESULT__'.length))
    assert.ok(!out.fatal, out.fatal)
    return out
  }

  test('冷启动 pending journal：先恢复再新增/收藏，稍后同步不回滚', () => {
    const dir = tmpDir()
    try {
      baseline(dir)
      writeJson(path.join(dir, JOURNAL), {
        knowledge: [
          K.toJson(K.card({ id: 1, deck: '默认', front: '旧卡', back: '旧背面', source: 'manual', createdAt: T0 })),
          K.toJson(K.card({ id: 2, deck: '默认', front: '恢复卡', back: '恢复背面', source: 'manual', createdAt: T0 + 1 }))
        ],
        history: [
          { t: T0, kind: 'clip', label: '老记录', text: '老内容', src: 'answer' },
          { t: T0 + 100, kind: 'clip', label: '恢复收藏', text: '恢复内容', src: 'answer', star: true }
        ],
        state: { deviceId: 'mac-test', records: [], bindings: {} }
      })

      const out = runMain(dir, 'restore')
      assert.strictEqual(out.add.ok, true, '恢复后新增应成功')
      // 恢复的卡占 id 2，新卡必须是 id 3：若先新增再恢复，新卡会被 journal 覆盖掉。
      assert.strictEqual(out.add.id, 3, '新卡必须排在恢复后的库后面')
      assert.strictEqual(out.star.ok, true, '恢复后收藏应成功')
      assert.ok(!fs.existsSync(path.join(dir, JOURNAL)), '恢复成功后 journal 应被删除')

      const cards = JSON.parse(fs.readFileSync(path.join(dir, 'knowledge.json'), 'utf8'))
      assert.deepStrictEqual(cards.map((c) => c.front).sort(), ['恢复卡', '新卡', '旧卡'].sort())

      const hist = JSON.parse(fs.readFileSync(path.join(dir, 'history.json'), 'utf8'))
      assert.strictEqual(hist.filter((e) => e.t === T0)[0].star, true, '恢复后才做的收藏必须保留')
      assert.strictEqual(hist.filter((e) => e.t === T0 + 100)[0].star, true, 'journal 里的收藏必须保留')

      // 稍后同步：真实 LanStore 再 export + apply 一轮，不得回滚恢复后的编辑。
      K.setWriteGuard(null)
      X.setWriteGuard(null)
      const store = K.storeIn(dir)
      const histStore = new X.JsonStore(path.join(dir, 'history.json'))
      const lan = new LanStore({
        dir: dir,
        knowledge: store,
        historyRead: () => histStore.read(),
        historyWrite: (list) => histStore.write(list)
      })
      lan.export()
      lan.apply([])

      assert.deepStrictEqual(store.listStrict().map((c) => c.front).sort(), ['恢复卡', '新卡', '旧卡'].sort())
      const after = histStore.readStrict()
      assert.strictEqual(after.filter((e) => e.t === T0)[0].star, true)
      assert.strictEqual(after.filter((e) => e.t === T0 + 100)[0].star, true)
      assert.ok(!fs.existsSync(path.join(dir, JOURNAL)))
    } finally {
      K.setWriteGuard(null)
      X.setWriteGuard(null)
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  test('坏 journal：历史收藏与新增卡都拒绝写入，原文件与 journal 字节不变', () => {
    const dir = tmpDir()
    try {
      baseline(dir)
      const badJournal = '{"knowledge":['
      fs.writeFileSync(path.join(dir, JOURNAL), badJournal)

      const knowledgeBefore = fs.readFileSync(path.join(dir, 'knowledge.json'))
      const historyBefore = fs.readFileSync(path.join(dir, 'history.json'))

      const out = runMain(dir, 'bad')
      assert.strictEqual(out.add.ok, false)
      assert.strictEqual(out.add.reason, 'guard', '坏 journal 必须被守卫拦下')
      assert.strictEqual(out.star.ok, false)
      assert.match(String(out.star.error || ''), /同步恢复失败，数据未写入/)
      assert.doesNotMatch(String(out.star.error || ''), /抽不出内容/)

      assert.deepStrictEqual(fs.readFileSync(path.join(dir, 'knowledge.json')), knowledgeBefore, '知识卡原字节不能变')
      assert.deepStrictEqual(fs.readFileSync(path.join(dir, 'history.json')), historyBefore, '历史原字节不能变')
      assert.strictEqual(fs.readFileSync(path.join(dir, JOURNAL), 'utf8'), badJournal, '坏 journal 必须原样保留')
    } finally {
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })
}
