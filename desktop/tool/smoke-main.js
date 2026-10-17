/* 装进 asar 之前先跑一遍：用假的 electron 模块加载 main.js，
 * 能拦住「模块级代码抛错」（TDZ / 拼错常量 / JSON 解析失败）这类一装就白屏的问题。
 * 用法: node tool/smoke-main.js main.js
 */
const path = require('path'), fs = require('fs'), os = require('os')
const target = path.resolve(process.argv[2] || 'main.js')
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'orb-smoke-'))
const noop = () => {}

// 万能假对象：任何属性/调用/构造都返回同类假对象
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

// app 必须是普通对象：getPath 要返回真字符串，不能被万能 Proxy 吃掉
const fakeApp = {
  getPath: () => tmp,
  setPath: noop,   // v1.30 起 main.js 会钉 userData 目录，假 app 也得认识这个方法
  getName: () => 'Ling',
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
  ipcMain: { on: noop, handle: noop },
  nativeImage: { createFromBuffer: () => ({ isEmpty: () => true, getSize: () => ({ width: 0 }) }) },
  shell: { openExternal: noop },
  clipboard: { readText: () => '', writeText: noop },
  globalShortcut: { register: () => true, unregisterAll: noop },
  // v1.72：知识卡到期检查会用系统通知；假模块给个能 show 的壳
  Notification: function () { return { on: noop, show: noop } }
}

const Module = require('module')
const origLoad = Module._load
Module._load = function (req) {
  if (req === 'electron') return fakeElectron
  return origLoad.apply(this, arguments)
}
process.argv = [process.argv[0], target]

try {
  require(target)
} catch (e) {
  console.error('✗ 模块加载失败：' + (e && e.stack || e))
  process.exit(1)
}
setTimeout(() => { console.log('✓ 模块加载通过（未发现模块级错误）'); process.exit(0) }, 300)
