const { app, BrowserWindow, desktopCapturer, screen, systemPreferences, ipcMain, nativeImage, shell, clipboard, globalShortcut, dialog, safeStorage } = require('electron')
// 启动埋点：永远只保留最后一次启动，出问题时先看它
const DEBUG_DIR = (process.platform === 'win32' ? require('node:os').tmpdir() : '/tmp')   // v1.30：win 上没有 /tmp
const BOOT_LOG = DEBUG_DIR + '/orb-boot.log'
let bootLines = []
function bootLog(line) { try { bootLines.push(new Date().toISOString() + ' ' + line); stateFs0.writeFileSync(BOOT_LOG, bootLines.join('\n') + '\n') } catch {} }
const stateFs0 = require('node:fs')
bootLog('A 模块开始加载 pid=' + process.pid + ' argv=' + JSON.stringify(process.argv.slice(1)))
/* —— v1.43：改回**开启硬件加速**（不再 disableHardwareAcceleration）——
 * v1.21 关掉它是为了省内存（实测 225 → 255MB，+30MB），但代价是整机 UI 都跑在软件光栅上：
 * GPU 进程参数是 `--use-gl=angle --use-angle=swiftshader-webgl`，而灵珠的窗口全是「透明 + backdrop-filter 毛玻璃」，
 * 每次窗口 setBounds 都要 CPU 整块重合成 —— 用户实测感受就是「整体很卡」，尤其拖小球、开面板、流式输出时。
 * 这次按「流畅优先」改回来，前后对比数据见 README v1.43；要退回省内存模式，把下面这行打开即可。 */
// app.disableHardwareAcceleration()
/* —— 内存瘦身（v1.21）实测结论，留档 ——
 * 都试过了，全部回退，原因留档免得再踩：
 *   disable-gpu            → GPU 进程不会消失（变 SwiftShader），软件光栅化把内存挪进主/渲染进程，145→212MB；
 *   process-per-site       → 渲染进程合并了，但主进程暴涨，145→239MB 且持续爬；
 *   renderer-process-limit → 有把闲置小球渲染进程换出重载的风险（会闪），收益小；
 *   js-flags 封顶          → 对 RSS 无感。
 * 结论（实测）：闲置稳定后只有小球 ≈ 86MB（主 32 + GPU 18 + 网络 9 + 渲染 25）；
 *   识别/翻译瞬时会冲到 200~300MB，结束后 1~2 分钟回落。面板忘关会在 10 分钟后自动销毁。
 * Electron 的内存地板就在那里；真想降到 ~20MB 只能换原生实现（Swift/AppKit）。
 */
const path = require('path')
const X = require('./extras')   // v1.58：百度翻译 / 结果缓存 / 历史落盘
const P = require('./prompts')   // v1.60：识别关键词 + 追问 what / why（和答案面板共用）
const TH = require('./themes')   // v1.60：配色（默认护眼）
const U = require('./update')    // v1.60：桌面内置更新通道（manifest 校验 / 版本比较 / 平台资产，纯逻辑）
const { LanSync } = require('./lan-sync')   // v1.61：局域网同步服务（仅设置卡手动开启）
const https = require('node:https')   // v1.60：更新源与下载包都走 Node stream，不整包进内存
const http = require('node:http')
const crypto = require('node:crypto')
const { Transform } = require('node:stream')
const { pipeline } = require('node:stream/promises')   // v1.60：下载用 pipeline，结束即文件 close，避免半截 part / 悬挂 promise

// 开源构建没有内置接口：这个判定恒为 false，调用点照旧可用。
function usingBuiltinApi() { return false }
const { spawnSync, spawn } = require('node:child_process')
// v1.75：实时转写的窗口 / IPC / 会话控制都在 voice-main.js（协议在 voice-asr.js，密钥在 voice-secrets.js）。
// 主进程这里只做接线：给它窗口工具、日志、sender 判定，其余全在那个模块里。
const voiceMain = require('./voice-main')
/* v1.76：专注守护 / 陪伴 —— 状态机与排程在 focusguard-main.js / companion-main.js，
 * 这里只接线（气泡窗 / 前台探测 / 接口短句 / 设置持久化）。文件缺失只记日志停用那一个功能，不拖垮主进程。 */
let focusGuard = null
let companion = null
try { focusGuard = require('./focusguard-main') } catch (e) { bootLog('X 专注守护模块加载失败 ' + ((e && e.message) || e)) }
try { companion = require('./companion-main') } catch (e) { bootLog('X 陪伴模块加载失败 ' + ((e && e.message) || e)) }

process.on('uncaughtException', (e) => { bootLog('X uncaughtException ' + (e && e.stack || e)) ; console.error('[orb] uncaughtException', e && e.message) })
process.on('unhandledRejection', (e) => { bootLog('X unhandledRejection ' + (e && (e.stack || e.message) || String(e))) ; console.error('[orb] unhandledRejection', e && (e && e.message || String(e))) })

// v1.31：API 可在设置卡里自定义（OpenAI 兼容格式：地址 + Key + 模型名）。
// 未配置时回落到环境变量 / 本地网关默认值。
const API_DEFAULTS = {
  baseUrl: process.env.AI_BASE_URL || '',
  model: process.env.AI_MODEL || '',
  apiKey: process.env.AI_KEY || ''
}
function apiCfg() {
  const a = orbState.api || {}
  // v1.59 内测版：自己没填接口地址时整套用内置的（不和自己填的混用，免得把内置 Key 发到别人的服务器）
  return {
    baseUrl: ((typeof a.baseUrl === 'string' && a.baseUrl.trim()) || API_DEFAULTS.baseUrl).replace(/\/+$/, ''),
    model: (typeof a.model === 'string' && a.model.trim()) || API_DEFAULTS.model,
    apiKey: (typeof a.apiKey === 'string' && a.apiKey.trim()) || API_DEFAULTS.apiKey
  }
}
/* v1.61.1：新手卡的「试一次识别」只在接口真的可用时才开一次识别。
 * 内测版有内置接口；正式版必须自己填过地址（Key 可以留空，交给默认值 / 本地网关）。 */
function apiReady() {
  if (usingBuiltinApi()) return true
  const a = orbState.api || {}
  return !!(typeof a.baseUrl === 'string' && a.baseUrl.trim())
}

/* v1.41/v1.42：DeepSeek-V4.1 系列**默认开思考模式**（effort 默认 high），思维链算在输出 token 里。
 * v1.41 的坑：查词只给 300 输出预算 → 全被思维链吃掉，content 是空字符串（「查词失败 of：模型没返回内容」）。
 * v1.42（用户要求）：**默认思考强度 = 高**，并做成设置卡里的可选项（关 / 低 / 高 / 最高），
 * 屏幕识别（通用+做题）/ 划词翻译 / 追问都听这个设置；**悬停查词固定不走思考**（词典查询，
 * 思考只会吃预算、还慢好几秒 —— 真要它想，设置里改不动它）。
 * 只对 deepseek 端点带这些参数 —— 别的厂商不认（OpenAI 会直接 400）。 */
const THINK_EFFORTS = ['off', 'low', 'high', 'max']
// v1.49：做题解析强度（简略 / 标准 / 详细）+「纯小白」开关。写字面量：loadState 在模块初始化时就会用到它
const EXAM_DETAILS = ['brief', 'standard', 'detailed']
/* v1.74：去掉硬性字数封顶（brief 150 / standard 400 的老档位删掉）——字数上限会逼模型少写关键步骤，
 * 现在只按 brief / standard / detailed 控制深度（见 EXAM_DETAIL_RULES），不封顶。 */
const EXAM_DETAIL_LABEL = { brief: '简略', standard: '标准', detailed: '详细' }
// v1.59：默认解析强度 —— 内测版「详细」（用户要求），公测维持「标准」。PRESET 在文件头部已初始化，这里引用安全
const EXAM_DETAIL_DEFAULT = 'standard'
function examDetail() { return EXAM_DETAILS.includes(orbState.examDetail) ? orbState.examDetail : EXAM_DETAIL_DEFAULT }
function isDeepSeekCfg(cfg) {
  return /deepseek/i.test(String((cfg && cfg.baseUrl) || '')) || /^deepseek/i.test(String((cfg && cfg.model) || ''))
}
// v1.60：桌面端默认思考强度统一为「高」（内测 / 公测相同；DeepSeek 官方默认）
const THINK_DEFAULT = 'high'
function thinkEffort() { return THINK_EFFORTS.includes(orbState.thinkingEffort) ? orbState.thinkingEffort : THINK_DEFAULT }   // 默认：内测 / 公测均 high；显式已存 off/low/max 照旧生效
function thinkingOf(cfg, force) {
  if (!isDeepSeekCfg(cfg)) return {}
  const eff = force === 'off' ? 'off' : thinkEffort()
  if (eff === 'off') return { thinking: { type: 'disabled' } }
  return { thinking: { type: 'enabled' }, reasoning_effort: eff }
}
function thinkingOff(cfg) { return thinkingOf(cfg, 'off') }

const ORB_SIZE = 52          // 球体视觉尺寸（不变）
const ORB_PAD = 14           // 窗口四周留白，给光晕出界空间
const ORB_WIN = ORB_SIZE + ORB_PAD * 2
const MAX_IMAGE_WIDTH = 1600
const JPEG_QUALITY = 80
// v1.32：输出面板缩到约一半面积（宽 420→300，高 620→480），观感更轻
const ANSWER_W = 300
const ANSWER_MAX_H = 480

const stateFs = require('node:fs')
// v1.30：钉死 userData 目录为 orb-ai。改名「灵珠」后 productName/name 都会变，
// 不钉死的话 Electron 会把 userData 搬到新名字目录下，state.json（位置/热键/开关）全部丢。
// v1.59：内测版用自己的目录（orb-ai-internal），和正式版能同时开、互不改设置；第一次启动把正式版的设置抄一份过来
// v1.60 改名 Ling：productName 变了也不影响 —— 这里钉的是固定目录名，不跟 productName 走（appId 也没改）。
// LING_USER_DATA 只给测试 / 截图用（指到临时目录，不碰正在用的设置，也不和正在运行的实例抢单实例锁）
app.setPath('userData', process.env.LING_USER_DATA || path.join(app.getPath('appData'), 'ling-opensource'))
// v1.60：单实例锁挪到钉好 userData 之后 —— 锁文件在 userData 里，以前锁跟着 productName 走（改名 Ling 后会和旧内测版 Ling 抢同一把锁）
const gotSingleLock = app.requestSingleInstanceLock()
bootLog('B 单实例锁 gotSingleLock=' + gotSingleLock)
if (!gotSingleLock) { bootLog('C 已有实例在跑 → 主动退出'); app.quit() }
app.on('second-instance', () => { if (orbWin && !orbWin.isDestroyed()) { orbWin.show(); topMost(orbWin) } })
const statePath = path.join(app.getPath('userData'), 'state.json')
// 注意：这里的默认值必须写字面量。LANGS / DEFAULT_TRANS_LANG / MODES 都声明在文件后面，
// 模块级初始化里引用它们会踩 TDZ（ReferenceError: Cannot access ... before initialization）。
/* v1.64：mcp / link 两个开关默认关（新装一律关，不自动监听）。port / roots / enabled 都会落盘。 */
let orbState = { autoScan: false, x: null, y: null, hintShown: false, panelTitle: 'AI 屏幕解析', translateOnCopy: false, translateHotkey: '', mode: 'general', transLang: 'auto', api: null, apiOnboarded: false, scanIntervalMs: 20000, appearance: null, apiProfiles: [], activeProfile: null, thinkingEffort: THINK_DEFAULT, examDetail: EXAM_DETAIL_DEFAULT, novice: false, transEngine: 'ai', deepl: null, baidu: null, useCache: true, saveHistory: true, customModes: null, modeOpacity: {}, keywords: true, theme: TH.DEFAULT, panelSide: 'left', mcp: null, link: null, focusDurationMin: 25, focusAwaySec: 20, companionOn: true, companionFreq: 'med', companionAi: false }
let moveSaveTimer = null
// v1.59 翻译引擎：auto = DeepL → 百度 → 大模型（依次兜底）；也可以只用其中一个。写字面量（loadState 在模块初始化时就要用）
const TRANS_ENGINES = ['auto', 'deepl', 'baidu', 'ai']
function defaultEngine() { return 'ai' }
function deeplKey() { return (orbState.deepl && orbState.deepl.apiKey) || '' }
const ENGINE_LABEL = { deepl: 'DeepL', baidu: '百度', ai: 'AI' }
/* v1.61.1：坐标读盘只认 JSON 里的真数字（saveState 一直写 getBounds 的整数）。
 * 老写法 `Number(j.x) || null` 会把 0（合法的左上角坐标）当成「没存过」——
 * 而 Number(null) 又是 0，所以这里不做任何类型转换：null / undefined / 字符串 / 布尔都按「没存过」处理。 */
function numOrNull(v) {
  return (typeof v === 'number' && Number.isFinite(v)) ? v : null
}
/* v1.64：两个服务的落盘形状。只认白名单字段，端口越界 / 类型不对一律回默认值（不猜用户意图）。 */
function stateMcpAndLink(j) {
  const out = { mcp: null, link: null }
  const m = j && j.mcp
  if (m && typeof m === 'object') {
    out.mcp = {
      enabled: !!m.enabled,
      port: (Number.isInteger(m.port) && m.port >= 1024 && m.port <= 65535) ? m.port : 47321,
      roots: Array.isArray(m.roots) ? m.roots.filter((x) => typeof x === 'string' && x).slice(0, 20) : []
    }
  }
  const l = j && j.link
  if (l && typeof l === 'object') {
    out.link = {
      enabled: !!l.enabled,
      port: (Number.isInteger(l.port) && l.port >= 1024 && l.port <= 65535) ? l.port : 47322
    }
  }
  return out
}
/* v1.76：专注守护 / 陪伴的取值规则（loadState / saveState / settings:get/save 共用一份）。
 * 专注时长 1..240 分钟、分心阈值 5..300 秒，越界回默认（默认 25 分 / 20 秒，同 start 的钳制）。 */
const COMPANION_FREQS = ['low', 'med', 'high']
function focusDurationMin() { const v = Math.round(Number(orbState.focusDurationMin)); return Number.isFinite(v) ? Math.min(240, Math.max(1, v)) : 25 }
function focusAwaySec() { const v = Math.round(Number(orbState.focusAwaySec)); return Number.isFinite(v) ? Math.min(300, Math.max(5, v)) : 20 }
function companionFreq() { return COMPANION_FREQS.includes(orbState.companionFreq) ? orbState.companionFreq : 'med' }
function focusCompanionOf(j) {
  const s = (j && typeof j === 'object') ? j : {}
  return {
    focusDurationMin: Number.isFinite(Math.round(Number(s.focusDurationMin))) ? Math.min(240, Math.max(1, Math.round(Number(s.focusDurationMin)))) : 25,
    focusAwaySec: Number.isFinite(Math.round(Number(s.focusAwaySec))) ? Math.min(300, Math.max(5, Math.round(Number(s.focusAwaySec)))) : 20,
    companionOn: s.companionOn !== false,
    companionFreq: COMPANION_FREQS.includes(String(s.companionFreq)) ? String(s.companionFreq) : 'med',
    companionAi: s.companionAi === true
  }
}
function loadState() {
  try {
    const j = JSON.parse(stateFs.readFileSync(statePath, 'utf8'))
    if (j && typeof j === 'object') {
      orbState = { autoScan: !!j.autoScan, x: numOrNull(j.x), y: numOrNull(j.y), hintShown: !!j.hintShown, panelTitle: typeof j.panelTitle === 'string' ? j.panelTitle : 'AI 屏幕解析', translateOnCopy: !!j.translateOnCopy, translateHotkey: typeof j.translateHotkey === 'string' ? j.translateHotkey : '', translateHotkeySet: !!j.translateHotkeySet, thinkingEffort: THINK_EFFORTS.includes(j.thinkingEffort) ? j.thinkingEffort : THINK_DEFAULT, examDetail: EXAM_DETAILS.includes(j.examDetail) ? j.examDetail : EXAM_DETAIL_DEFAULT, novice: !!j.novice, mode: RETIRED_MODES[j.mode] || ((MODES[j.mode] || /^custom:/.test(String(j.mode || ''))) ? j.mode : 'general'), transLang: LANGS.some((l) => l.id === j.transLang) ? j.transLang : DEFAULT_TRANS_LANG,
        api: (j.api && typeof j.api === 'object') ? { baseUrl: String(j.api.baseUrl || ''), apiKey: String(j.api.apiKey || ''), model: String(j.api.model || '') } : null,
        apiOnboarded: !!j.apiOnboarded,
        scanIntervalMs: Math.min(3600000, Math.max(5000, Number(j.scanIntervalMs) || 20000)),
        apiProfiles: Array.isArray(j.apiProfiles) ? j.apiProfiles.filter((x) => x && typeof x === 'object').map((x) => ({ name: String(x.name || ''), baseUrl: String(x.baseUrl || ''), apiKey: String(x.apiKey || ''), model: String(x.model || '') })).filter((x) => x.name) : [],
        activeProfile: typeof j.activeProfile === 'string' ? j.activeProfile : null,
        // v1.58：翻译引擎（ai / baidu）+ 百度凭证（只存在本机 state.json，权限 600）+ 缓存 / 历史开关
        transEngine: TRANS_ENGINES.includes(j.transEngine) ? j.transEngine : defaultEngine(),
        deepl: (j.deepl && typeof j.deepl === 'object') ? { apiKey: String(j.deepl.apiKey || '').trim() } : null,
        baidu: (j.baidu && typeof j.baidu === 'object') ? { appId: String(j.baidu.appId || '').trim(), apiKey: String(j.baidu.apiKey || '').trim(), secret: String(j.baidu.secret || '').trim(), model: j.baidu.model === 'nmt' ? 'nmt' : 'llm' } : null,
        useCache: j.useCache !== false, saveHistory: j.saveHistory !== false,
        keywords: j.keywords !== false,   // v1.60：识别关键词，默认开
        theme: TH.normId(j.theme),        // v1.60：配色，没选过 = 护眼
        panelSide: PANEL_SIDES.includes(j.panelSide) ? j.panelSide : 'left',   // v1.60：结果面板默认从屏幕左侧滑出
        customModes: Array.isArray(j.customModes) ? j.customModes.filter((x) => x && x.id).map((x) => ({ id: String(x.id), name: String(x.name || '自定义').slice(0, 20), prompt: String(x.prompt || '') })) : null,
        appearance: (j.appearance && typeof j.appearance === 'object') ? {
          bgAlpha: Math.min(0.85, Math.max(0.25, Number(j.appearance.bgAlpha) || 0.50)),
          fontSize: Math.min(18, Math.max(12, Number(j.appearance.fontSize) || 14)),
          contrast: Math.min(1, Math.max(0.7, Number(j.appearance.contrast) || 0.92))
        } : null,
        // v1.64：MCP / 手机常连（默认 null = 都没开过 = 关）
        mcp: stateMcpAndLink(j).mcp,
        link: stateMcpAndLink(j).link,
        // v1.76：专注守护 / 陪伴（老 state.json 没有这些字段 = 用默认值）
        focusDurationMin: focusCompanionOf(j).focusDurationMin,
        focusAwaySec: focusCompanionOf(j).focusAwaySec,
        companionOn: focusCompanionOf(j).companionOn,
        companionFreq: focusCompanionOf(j).companionFreq,
        companionAi: focusCompanionOf(j).companionAi }
    }
  } catch {}
}
function saveState() {
  try {
    let x = null, y = null
    if (orbWin && !orbWin.isDestroyed()) { const b = orbWin.getBounds(); x = b.x; y = b.y }
    const out = { autoScan: autoScanning, x, y, hintShown: !!orbState.hintShown, panelTitle: orbState.panelTitle || 'AI 屏幕解析', translateOnCopy: !!translateOnCopy, mode: currentMode, transLang: transLang }
    if (orbState.translateHotkey) out.translateHotkey = orbState.translateHotkey
    if (orbState.translateHotkeySet) out.translateHotkeySet = true   // v1.38：用户自己改过键位（老默认值不再当自定义）
    out.scanIntervalMs = orbState.scanIntervalMs || 20000
    out.thinkingEffort = thinkEffort()   // v1.42：思考强度（默认 high）
    out.examDetail = examDetail()        // v1.49：解析强度 + 纯小白
    if (orbState.novice) out.novice = true
    if (orbState.appearance) out.appearance = orbState.appearance
    if (orbState.api) out.api = orbState.api
    if (orbState.apiProfiles && orbState.apiProfiles.length) out.apiProfiles = orbState.apiProfiles
    if (orbState.activeProfile) out.activeProfile = orbState.activeProfile
    if (orbState.apiOnboarded) out.apiOnboarded = true
    if (orbState.transEngine !== defaultEngine()) out.transEngine = orbState.transEngine
    if (orbState.deepl && orbState.deepl.apiKey) out.deepl = orbState.deepl
    if (orbState.baidu) out.baidu = orbState.baidu
    if (orbState.useCache === false) out.useCache = false
    if (orbState.saveHistory === false) out.saveHistory = false
    if (orbState.customModes) out.customModes = orbState.customModes
    if (orbState.keywords === false) out.keywords = false
    if (orbState.theme && orbState.theme !== TH.DEFAULT) out.theme = orbState.theme
    if (orbState.panelSide && orbState.panelSide !== 'left') out.panelSide = orbState.panelSide
    /* v1.64：两个开关的值要落盘 —— 用户明确要求「各开关持久保存，但新装默认关」。
     * enabled 记的是用户在设置卡里的选择；下次启动按它自动起（端口占用照样如实报错，不换端口）。 */
    if (orbState.mcp) out.mcp = { enabled: !!orbState.mcp.enabled, port: mcpPort(), roots: Array.isArray(orbState.mcp.roots) ? orbState.mcp.roots.slice(0, 20) : [] }
    if (orbState.link) out.link = { enabled: !!orbState.link.enabled, port: linkPort() }
    /* v1.76：专注守护 / 陪伴的偏好也落盘（白名单：不写这五个字段的旧版本读回来 = 全默认值）。 */
    const fc = focusCompanionOf(orbState)
    out.focusDurationMin = fc.focusDurationMin
    out.focusAwaySec = fc.focusAwaySec
    out.companionOn = fc.companionOn
    out.companionFreq = fc.companionFreq
    out.companionAi = fc.companionAi
    stateFs.writeFileSync(statePath, JSON.stringify(out), { mode: 0o600 })
  } catch (e) { console.error('[orb] saveState', e && e.message) }
}

let orbWin = null
let answerWin = null
let isSolving = false
/* v1.73 D1：识别进行中又来了请求时的「单一待办」——不再像老写法那样直接 return 静默丢掉。
 * 只留一个槽位（不是队列，不会无限排），手动请求优先：自动扫描的请求不覆盖已排队的手动请求。
 * 由 solve() 的 finally 在上一轮结束后取走并执行。 */
let pendingSolve = null
const history = []

/* ---------------- 模式（v1.19） ----------------
 * 右键小球切换。每个模式 = 一套提示词 + 一套面板排版。
 * 想加新模式：往 MODES 里加一条，再在 answer.html 的 renderExam 里按需加排版即可。
 */

/* v1.77（2026-10-27）：屏幕识别提示词精简 —— 只留任务和真正的输出契约。
 * 人格（PERSONA）、共享「讲法」（P.STYLE）、小白教学套路（NOVICE_STYLE）、「用中文 / 不要寒暄」这类收尾纪律
 * 一律不再拼进识别链路；通用模式只留最小任务，纯小白在通用里仅追加一句大白话要求（做题另有小白格式）。
 * 做题（含纯小白做题）的答案 / 分节 / LaTeX 契约、自定义模式的用户要求、关键词 TERMS_RULE 与追问开关原样保留。 */

/* v1.58（对齐安卓）：面板会排版 LaTeX 了（math.js）—— 高数题用纯文本写分式 / 积分挤成一行没法看。
 * 推导一步一行：面板窄，一行塞好几个等号会折得乱七八糟。只给做题模式用，通用 / 自定义不注入排版纪律。 */
const MATH_RULE = '只有确实出现公式时才用 LaTeX 写：行内用 $…$，单独成行的式子用 $$…$$（分式 \\frac、根号、极限、积分、求和、上下标都照常写，面板会排版）；没有公式就用普通文字，不要为了排版硬套 LaTeX。推导按需要分行，把必要的步骤和依据写清就行，不要机械地一个等号占一行'

/* v1.58：自定义模式（对齐安卓 v1.54 / v1.58）：可以建很多个，各有名字和解析要求。
 * 提示词 = 图片说明 + 用户自己写的要求（空着就用设置页里那个示例），不再拼人格 / 小白 / 数学格式。 */
const CUSTOM_EXAMPLE = '先用一句话说这是什么界面或什么题。\n如果有题目：先给答案，再分步讲解，最后列出考点。\n如果没有题目：列出画面里的关键信息（数字、文字、状态、报错），再给我一条建议。'
function customPrompt(userPrompt) {
  return '下面是当前屏幕的截图（可能有多张，对应多个显示器）。按下面的要求解析：\n' + (String(userPrompt || '').trim() || CUSTOM_EXAMPLE)
}

/* 通用：最小任务 —— 只要求识别并说明画面内容；纯小白在通用里也只追加一句大白话要求，不另设讲法 / 格式 */
function generalPrompt() {
  return '这是当前屏幕的截图（可能有多张，对应多个显示器）。识别并说明画面内容。'
    + (orbState.novice ? '用大白话解释，遇到术语先说明含义。' : '')
}

// v1.49：「## 解析」「## 考点」两段按解析强度换写法；三段式标题不变（answer.html 靠它排版）
const EXAM_DETAIL_RULES = {
  brief: {
    analysis: '只讲最关键的依据（或那一步必需的推导），说清为什么是它就够。选择题点明正确项为什么对；只有某个干扰项特别容易混时，才补一句它为什么不对。不需要的题不必套计算或多步推导。',
    point: '一句话点出考点；有典型易错点再补一句，没有就只说考点。'
  },
  standard: {
    analysis: '讲清必要的解题步骤和依据：推导 / 计算题写出关键过程，概念 / 记忆题直接给依据，不跳关键环节。选择题说明正确项为什么对，并点出最关键的一两个干扰项为什么不对 —— 不用逐个分析所有选项。不要求分步编号，也不必给所有科目套计算。',
    point: '一句话点出本题考点；有典型易错点再补一句，没有就只说考点。'
  },
  detailed: {
    analysis: '按题目类型展开：推导 / 计算题逐步写清依据和中间过程，选择题可以逐个分析每个选项对在哪、错在哪，概念 / 记忆题把相关知识点讲透。不跳步，但不强行为所有题套计算或逐项排除。',
    point: '写清本题考点和用到的公式或概念；列出易错点（有才列）；再用一两句话说明同类题的通用解法。'
  }
}

// v1.52：纯小白的做题格式。标题都在 answer.html 的 renderExam 里登记过，会分区排版
function noviceExamPrompt() {
  const d = examDetail()
  const secs = ['## 答案\n直接给结论。选择题：正确选项字母 + 选项内容（例：B. 鲸鱼）；其他题：最终结果。']
  if (d !== 'brief') {
    secs.push('## 这题在问什么\n用一两句大白话说出题目想让我们干什么（不要照抄题干）。')
    secs.push('## 先弄懂\n做这题必须懂的词或符号，1~3 个，每个一行：「词」= 大白话解释。都很简单就省掉这一段（连标题一起省）。')
  }
  secs.push('## 一步步做\n用 1. 2. 3. 编号，每步一行，写「做什么 —— 为什么这么做」；有计算就把算式完整写出来，该有的步骤一步都不能省' + (d === 'brief' ? '，只写必需的步骤，不必凑数' : '') + '。'
    + (d === 'brief' ? '做题必须懂的词，直接在步骤里用括号解释。' : ''))
  secs.push('## 其他选项错在哪\n只有选择题才写这一段（不是选择题就连标题一起省掉）。挑最容易混的一两个错项，每个一行：「A 错：……」，一句大白话。')
  secs.push('## 记住这句\n一句能背下来的规律；这题有典型易错点再补一句「最容易错在……」，没有就省掉。')
  if (d === 'detailed') secs.push('## 举一反三\n出一道同类的小题（只写题目和答案），再用一句话说这类题的通用做法。')
  return '用户把当前屏幕截图发给你（可能有多张，对应多个显示器，题目可能出现在任意一张里）。\n'
    + '找到画面里的题目并解答，先保证解对、解完整。严格按下面的格式输出，标题原样照抄（含 ##），顺序不变：\n\n'
    + secs.join('\n\n') + '\n\n'
    + '硬性要求：\n'
    + '- 不复述整道题干；\n'
    + '- 只写可核验的依据和步骤，不要写猜测、犹豫或试错过程；\n'
    + '- ' + MATH_RULE + '；\n'
    + '- 题干、符号或图形条件看不清时，不要自己猜一种理解就当条件往下做：说明缺了什么、只解能确定的部分，缺的条件决定答案时请用户补一张更清楚的图；不要给好几套可能的答案；\n'
    + '- 画面里确实没有题目时：第一行只输出「未发现题目」，另起一段用两三句大白话说明画面内容；\n'
    + '- 画面里有多道题时，每道题独立按上面的格式回答，并在「## 答案」前加一行 `### 第 N 题`（N 按题号从 1 开始）；\n'
    + '- 同一道题的（1）（2）小问不算多道题，但每个小问要分开写清：「## 答案」和「## 一步步做」里按（1）（2）分别作答，不要把几个小问揉成一段。'
}

function examPrompt() {
  if (orbState.novice) return noviceExamPrompt()
  return '用户把当前屏幕截图发给你（可能有多张，对应多个显示器，题目可能出现在任意一张里）。\n'
    + '找到画面里的题目并解答，先保证解对、解完整。输出必须严格用下面三段式，标题原样照抄（含 ##）：\n\n'
    + '## 答案\n'
    + '直接给结论。选择题：正确选项字母 + 选项内容（例：B. 鲸鱼）；多选/填空/简答/计算：给最终结果。\n\n'
    + '## 解析\n'
    + EXAM_DETAIL_RULES[examDetail()].analysis + '\n\n'
    + '## 考点\n'
    + EXAM_DETAIL_RULES[examDetail()].point + '\n\n'
    + '硬性要求：\n'
    + '- 不复述题干；\n'
    + '- 只写可核验的依据和解题步骤，不要写猜测、犹豫或试错过程；\n'
    + '- ' + MATH_RULE + '；\n'
    + '- 题干、符号或图形条件看不清时，不要自己猜一种理解就当条件往下做：说明缺了什么、只解能确定的部分，缺的条件决定答案时请用户补一张更清楚的图；不要给好几套可能的答案；\n'
    + '- 画面里确实没有题目时：第一行只输出「未发现题目」，另起一段用两三句话说明画面内容；\n'
    + '- 画面里有多道题时，每道题独立按上面的三段式回答，并在「## 答案」前加一行 `### 第 N 题`（N 按题号从 1 开始）；\n'
    + '- 同一道题的（1）（2）小问不算多道题，但每个小问要分开写清：「## 答案」和「## 解析」里按（1）（2）分别作答，不要把几个小问揉成一段。'
}

/* v1.65 的「通识」模式在 v1.60 取消（同安卓）：名词词块改成所有模式都有，见 prompts.js 的 TERMS_RULE +
 * 设置里的「识别关键词」开关（默认开）。老用户选的是通识 → 启动时迁移成通用（loadState）。 */
const RETIRED_MODES = { learn: 'general' }
function keywordsOn() { return orbState.keywords !== false }

const MODES = {
  // v1.32：面板宽度约为原来的一半（440→300 / 520→360）
  general: { id: 'general', label: '通用', hint: '识别整屏', width: 300, prompt: generalPrompt },
  exam: { id: 'exam', label: '做题', hint: '答案+解析', width: 360, prompt: examPrompt }
}
const MODE_LIST = Object.keys(MODES)
let currentMode = 'general'

function customModes() { return orbState.customModes || [] }
/** 模式 id：general / exam / custom:<id> */
function allModeIds() { return MODE_LIST.concat(customModes().map((m) => 'custom:' + m.id)) }
function modeInfo(id) {
  if (MODES[id]) return MODES[id]
  const c = String(id || '').startsWith('custom:') && customModes().find((m) => 'custom:' + m.id === id)
  if (c) return { id, label: c.name || '自定义', hint: '我的要求', width: 330, prompt: () => customPrompt(c.prompt) }
  return MODES.general
}
function sysPrompt() { return modeInfo(currentMode).prompt() + (keywordsOn() ? '\n\n' + P.TERMS_RULE : '') }   // v1.60：识别关键词拼在所有模式后面
function answerWidth() { return modeInfo(currentMode).width }

function modeLog(line) { try { stateFs.appendFileSync(DEBUG_DIR + '/orb-mode.log', new Date().toISOString() + ' ' + line + '\n') } catch {} }


/* v1.45：入参改成 NativeImage（不再 PNG 往返）。
 * 原来这里是 compressToBase64(pngBuffer)：nativeImage.createFromBuffer 把 grabWithDesktopCapturer 刚
 * toPNG() 出来的位图**解码回来**，再 resize + toJPEG —— 一编码一解码两次 CPU 白烧，而且都在主进程主线程上同步做。
 * 现在上游直接给 NativeImage，这里只编码一次 JPEG。 */
function imgToJpegBase64(img) {
  try {
    if (!img || img.isEmpty()) return null
    const { width } = img.getSize()
    const resized = width > MAX_IMAGE_WIDTH ? img.resize({ width: MAX_IMAGE_WIDTH }) : img
    return resized.toJPEG(JPEG_QUALITY).toString('base64')
  } catch { return null }
}

/* ---------------- 截屏 ----------------
 * v1.17 修复「出错：Failed to get sources.」：
 *   1) Electron 的 desktopCapturer 走 ScreenCaptureKit，权限不足时 Chromium 抛出的是一个
 *      **字符串**（不是 Error 对象），旧代码用 e.message 判断永远匹配不上，于是原始英文
 *      报错直接漏到面板上 → 现在统一用 errText() 归一化。
 *   2) App 为 adhoc 签名，macOS 的「屏幕录制」授权是按 cdhash 记忆的 —— 每次重新构建/重签名
 *      都会让已授权状态失效（getMediaAccessStatus('screen') 变成 denied）。
 *      因此新增系统自带 /usr/sbin/screencapture 兜底通道，它不依赖 App 自身的 TCC 授权，
 *      实测在授权失效时仍能拿到完整原生分辨率画面（含所有窗口）。
 *   两条通道任一成功即返回；都失败才按「权限未开 / 屏幕无画面」给出可操作的提示。
 */
const SCREEN_NO_PICTURE = 'SCREEN_NO_PICTURE'
const SCREEN_NO_PERM = 'SCREEN_NO_PERM'
const CAPTURE_TIMEOUT_MS = 6000
const CAPTURE_LOG = DEBUG_DIR + '/orb-capture.log'   // v1.50：Windows 没有 /tmp
// v1.17：不再做「画面里没题目就静默」的抑制 —— 用户要的是任何画面都给整体解析。
// 这里只负责把模型偶尔仍吐出的 [无题目] 标记剥掉，正文照常显示。
const NO_Q_HEAD = /^\s*[[【]?\s*无题目\s*[\]】]?\s*/

function errText(e) {
  if (e === null || e === undefined) return ''
  if (typeof e === 'string') return e
  if (typeof e === 'object' && typeof e.message === 'string' && e.message) return e.message
  try {
    const s = String(e)
    if (s && s !== '[object Object]' && s !== '[object DOMException]') return s
    return JSON.stringify(e)
  } catch { return '未知错误' }
}

function screenPermStatus() {
  // v1.50：屏幕录制授权只有 macOS 有。Windows 上 getMediaAccessStatus('screen') 不认这个类型，
  // 以前会落到 'unknown' → 跳过 desktopCapturer 去走 macOS 专属的 screencapture → 永远截不到屏
  if (process.platform !== 'darwin') return 'granted'
  try { return systemPreferences.getMediaAccessStatus('screen') } catch { return 'unknown' }
}

function logCapture(line) {
  try { require('node:fs').appendFileSync(CAPTURE_LOG, new Date().toISOString() + ' ' + line + '\n') } catch {}
}

/* v1.45：不再 PNG 往返。
 * 原来 grabWithDesktopCapturer 里 `s.thumbnail.toPNG()`（主线程把 1600×1000 编码成 PNG，~100~200ms），
 * 紧接着 compressToBase64 又 createFromBuffer 把它**解码回来**再 resize + toJPEG —— 白烧两次 CPU，而这两步
 * 都在主进程主线程上同步做，用户感受到的就是「点一下之后那几百毫秒整个应用不动」。
 * 现在直接把 NativeImage 传下去，只编码一次 JPEG。 */
async function grabWithDesktopCapturer() {
  const t0 = Date.now()
  const pending = desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: MAX_IMAGE_WIDTH, height: Math.round(MAX_IMAGE_WIDTH * 0.625) } })   // v1.27：直接按 1600×1000 要（原来是 1920×1200 再缩到 1600，白多一次重采样和一块大位图）
  pending.catch(() => {})  // 超时后原 promise 的拒绝不能变成 unhandledRejection
  let timer = null
  const timeout = new Promise((_, rej) => { timer = setTimeout(() => rej(new Error('CAPTURE_TIMEOUT')), CAPTURE_TIMEOUT_MS) })
  try {
    const sources = await Promise.race([pending, timeout])
    const tGot = Date.now()
    const out = []
    for (const s of sources) {
      const img = s.thumbnail
      if (!img || img.isEmpty()) continue
      out.push({ id: Number(s.display_id) || 0, img })
    }
    logCapture('desktopCapturer 取源 ' + (tGot - t0) + 'ms，' + sources.length + ' 屏 → 有效 ' + out.length + ' 屏')
    return out
  } finally { if (timer) clearTimeout(timer) }
}

/* v1.45：screencapture 改成**异步**（原来是 spawnSync —— 主进程在整个 screencapture 运行期间（实测 ~600ms）
 * 完全卡死，什么都干不了：小球拖不动、菜单打不开、面板不刷新。 */
function spawnAsync(cmd, args, timeoutMs) {
  return new Promise((resolve) => {
    let done = false
    const finish = (code, err) => { if (!done) { done = true; resolve({ code, err }) } }
    let child
    try { child = spawn(cmd, args, { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true }) } catch (e) { return finish(-1, errText(e)) }
    let err = ''
    if (child.stderr) child.stderr.on('data', (b) => { if (err.length < 200) err += String(b).slice(0, 200) })
    const timer = setTimeout(() => { try { child.kill('SIGKILL') } catch {} ; finish(-1, 'timeout') }, timeoutMs)
    child.on('error', (e) => { clearTimeout(timer); finish(-1, errText(e)) })
    child.on('close', (code) => { clearTimeout(timer); finish(code, err) })
  })
}

async function grabWithScreencapture() {
  // v1.30：这是 macOS 专属兜底（/usr/sbin/screencapture），Windows / Linux 上直接跳过
  if (process.platform !== 'darwin') return []
  const displays = screen.getAllDisplays()
  const cur = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()) || displays[0]
  const ordered = [cur].concat(displays.filter(d => d.id !== cur.id))
  const dir = app.getPath('temp')
  const out = []
  for (let i = 0; i < ordered.length; i++) {
    const d = ordered[i]
    const file = path.join(dir, 'orb-shot-' + process.pid + '-' + i + '.png')
    const t0 = Date.now()
    try {
      const args = ['-x', '-t', 'png']
      if (displays.length > 1) args.push('-D', String(displays.findIndex(x => x.id === d.id) + 1))
      args.push(file)
      const r = await spawnAsync('/usr/sbin/screencapture', args, 8000)
      if (r.code !== 0) { logCapture('screencapture exit=' + r.code + ' ' + r.err); continue }
      if (!stateFs.existsSync(file)) { logCapture('screencapture 未生成文件'); continue }
      const img = nativeImage.createFromPath(file)
      if (!img.isEmpty()) out.push({ id: d.id, img })
      logCapture('screencapture 第 ' + (i + 1) + ' 屏 ' + (Date.now() - t0) + 'ms（异步，不阻塞主进程）')
    } catch (e) {
      logCapture('screencapture 异常 ' + errText(e))
    } finally {
      try { stateFs.unlinkSync(file) } catch {}
    }
  }
  return out
}

function byCursorFirst(list, cursorId) {
  return list.slice().sort((a, b) => ((a.id === cursorId ? 0 : 1) - (b.id === cursorId ? 0 : 1)) || (a.id - b.id))
}

function toBase64List(list, cursorId) {
  const t0 = Date.now()
  const out = []
  for (const s of byCursorFirst(list, cursorId)) { const b = imgToJpegBase64(s.img); if (b) out.push(b) }
  if (out.length) logCapture('本次发送 ' + out.length + ' 张图，首张 ' + Math.round(out[0].length * 3 / 4 / 1024) + ' KB（JPEG 编码 ' + (Date.now() - t0) + 'ms）')
  // 排障开关：ORB_DEBUG_SHOT=1 时把真正发给 AI 的那张图落到 /tmp，方便核对是「图不对」还是「模型不对」
  if (out.length && process.env.ORB_DEBUG_SHOT === '1') {
    try { stateFs.writeFileSync(DEBUG_DIR + '/orb-debug-shot.jpg', Buffer.from(out[0], 'base64')) } catch {}
  }
  return out
}

/* v1.45：desktopCapturer 只在「有权限且上次没落空」时才试。
 * 实测日志里经常出现「desktopCapturer 返回空缩略图，改用 screencapture」—— 权限没给时这个分支会白等一次
 * （几十到几百毫秒），紧接着还要跑一遍 screencapture（~600ms）。所以：
 *   - 权限没 granted → 直接走 screencapture，别等；
 *   - 刚落空过（60 秒内）→ 也直接走 screencapture，别连续白等。
 * 60 秒后自动再试一次 desktopCapturer（用户可能刚去系统设置里授权了）。 */
let dcEmptyUntil = 0
// 排障开关：ORB_FORCE_FALLBACK=1 强制跳过 desktopCapturer、直接走 screencapture 兜底
// （用户日志里那条「desktopCapturer 返回空缩略图」走的就是这条路，没这个开关在能截屏的机器上验不到）
const FORCE_FALLBACK = process.env.ORB_FORCE_FALLBACK === '1'

async function takeScreensBase64() {
  const t0 = Date.now()
  const cur = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()) || screen.getPrimaryDisplay()
  const cursorId = cur.id
  let lastErr = null
  const perm = screenPermStatus()

  if (!FORCE_FALLBACK && perm === 'granted' && Date.now() > dcEmptyUntil) {
    try {
      const list = await grabWithDesktopCapturer()
      if (list.length) return toBase64List(list, cursorId)
      lastErr = new Error('EMPTY_THUMBNAIL')
      // v1.73 D5：退避只在「还有别的截屏路径」时才有意义。screencapture 兜底是 macOS 专属
      // （grabWithScreencapture 在非 darwin 直接返回 []），Windows 上 desktopCapturer 是唯一能用的路 ——
      // 这里退避 60 秒 = 整整一分钟所有截屏都失败（用户看到的就是「截屏失败」反复弹）。
      if (process.platform === 'darwin') dcEmptyUntil = Date.now() + 60000
      logCapture('desktopCapturer 返回空缩略图，改用 screencapture' + (process.platform === 'darwin' ? '（60 秒内不再试它）' : '（非 macOS：无兜底，不退避，下次继续试它）'))
    } catch (e) {
      lastErr = e
      logCapture('desktopCapturer 失败 typeof=' + typeof e + ' err=' + errText(e) + ' 权限=' + perm + ' → 改用 screencapture')
    }
  } else {
    logCapture('跳过 desktopCapturer（' + (FORCE_FALLBACK ? 'ORB_FORCE_FALLBACK=1' : '权限=' + perm + (perm === 'granted' ? '，上次落空未满 60 秒' : '')) + '）→ 直接用 screencapture')
  }

  try {
    const list = await grabWithScreencapture()
    if (list.length) {
      logCapture('screencapture 兜底成功，共 ' + list.length + ' 屏（截屏总耗时 ' + (Date.now() - t0) + 'ms）')
      return toBase64List(list, cursorId)
    }
    if (!lastErr) lastErr = new Error('SCREENCAPTURE_EMPTY')
  } catch (e) { if (!lastErr) lastErr = e }

  const m = errText(lastErr)
  if (perm !== 'granted') throw new Error(SCREEN_NO_PERM)
  if (m === 'CAPTURE_TIMEOUT' || /EMPTY_THUMBNAIL|SCREENCAPTURE_EMPTY/i.test(m) || /failed to get sources/i.test(m)) throw new Error(SCREEN_NO_PICTURE)
  throw new Error(m || '截屏失败')
}

/* v1.49：分层 —— 小球 / 答案 / 翻译面板在 1 层，菜单 / 追问 / 确认卡 / 设置在 2 层。
 * 以前全在同一层，谁后 show 谁在上：面板流式长高或自动扫描刷新时会压到右键菜单上面，
 * 面板里的字直接叠在菜单项上；设置卡用的是更低的 'floating'，会被面板整个盖住。 */
const LAYER_PANEL = 1, LAYER_POPUP = 2
// v1.50：setHiddenInMissionControl 只有 macOS 有，Windows 上直接调会抛 TypeError，建窗就崩
function hideFromMissionControl(w) { if (typeof w.setHiddenInMissionControl === 'function') w.setHiddenInMissionControl(true) }
function allSpaces(w) { try { w.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true }) } catch {} }
function topMost(window, layer = LAYER_PANEL) {
  window.setAlwaysOnTop(true, 'screen-saver', layer)
  allSpaces(window)
}
/* v1.61.1：小球默认在左侧中部，而结果面板默认也从屏幕左边滑出（x = wa.x + 12，宽 300）——
 * 两者在 x 轴上重叠，后建的面板会把球压在下面。不挪动球的初始位置（用户指定的就是左中），
 * 只把球在同层（都是 alwaysOnTop 第 1 层）里重新抬到最前：topMost 重设层级 + moveTop 提 z 序。 */
function orbToFront() {
  if (!orbWin || orbWin.isDestroyed()) return
  try { topMost(orbWin) } catch {}
  try { orbWin.moveTop() } catch {}
}

/* ---------------- 弹窗显隐过渡（v1.48） ----------------
 * 以前所有弹窗都是 show:true 直接建 —— 窗口先出现、内容 100~200ms 后才画上，关的时候一帧消失。
 * 现在：show:false 建窗，ready-to-show（首帧已画好）才显示，并用窗口级 alpha 淡入；关闭先淡出再 close/hide。
 * 窗口 alpha（NSWindow.alphaValue）由窗口服务器合成，不触发页面重排重绘，主进程每 16ms 只改一个数。
 * 淡出期间窗口引用保留；这期间又要用它（placeAnswer 等）就地反向淡回来，不会丢内容。 */
const FADE_IN_MS = 140, FADE_OUT_MS = 110
function fadeWin(win, to, ms, done) {
  if (!win || win.isDestroyed()) { if (done) done(); return }
  clearInterval(win.__fadeT)
  const from = win.getOpacity(), t0 = Date.now()
  win.__fadeT = setInterval(() => {
    if (win.isDestroyed()) { clearInterval(win.__fadeT); return }
    const k = Math.min(1, (Date.now() - t0) / ms)
    win.setOpacity(from + (to - from) * (1 - Math.pow(1 - k, 3)))   // ease-out
    if (k >= 1) { clearInterval(win.__fadeT); win.__fadeT = null; if (done) done() }
  }, 16)
}
// 首帧画好之后淡入。focus=true 给要打字/要失焦即关的卡片（菜单/追问/设置）；面板类不抢焦点。
function revealWin(win, focus) {
  if (!win || win.isDestroyed()) return
  win.__closing = false
  win.setOpacity(0)
  if (focus) { win.show(); win.focus() } else win.showInactive()
  fadeWin(win, 1, FADE_IN_MS)
}
function revealOnReady(win, focus) {
  win.once('ready-to-show', () => revealWin(win, focus))
}
// 自己报高度的窗口（设置卡 / 翻译面板）：等页面第一次 resize 落定再亮相，免得在眼前先跳一下；最多等 waitMs
function revealAfterFirstResize(win, focus, waitMs) {
  let done = false
  const go = () => { if (done || win.isDestroyed()) return; done = true; win.__onFirstResize = null; revealWin(win, focus) }
  win.__onFirstResize = go
  setTimeout(go, waitMs)
}
function firstResizeDone(win) { if (win && win.__onFirstResize) setTimeout(win.__onFirstResize, 16) }
function fadeClose(win, hide) {
  if (!win || win.isDestroyed() || win.__closing) return
  if (!win.isVisible()) { if (!hide) win.close(); return }
  win.__closing = true
  fadeWin(win, 0, FADE_OUT_MS, () => {
    if (win.isDestroyed() || !win.__closing) return
    win.__closing = false
    if (hide) win.hide(); else win.close()
  })
}
// 淡出途中又被要用：取消关闭、淡回来
function cancelClose(win) {
  if (!win || win.isDestroyed() || !win.__closing) return
  win.__closing = false
  fadeWin(win, 1, 80)
}

/* v1.73 D3：截屏遮挡期间把窗口上正在跑的淡入 / 滑入动画立即停掉。
 * 那些 16ms 定时器用的是「动画开始那一刻的 from」这个常量，拿 setOpacity(0) 藏不住它 ——
 * 下一帧它照样把透明度抬回去，灵珠自己的面板就被截进了画面；滑入定时器还会持续改 x。
 * 这里：停掉两个定时器；滑入定格到终点 x（否则面板会卡在离目标 64px 的半路）。
 * 返回「停之前它是不是正在关闭（__closing）」—— 关闭中的窗口恢复时绝不能复活成完全可见。 */
function freezeWin(w) {
  if (!w || w.isDestroyed()) return true
  const closing = !!w.__closing
  if (w.__fadeT) { clearInterval(w.__fadeT); w.__fadeT = null }
  if (w.__slideT) {
    clearInterval(w.__slideT); w.__slideT = null
    if (typeof w.__slideToX === 'number') {
      try { const b = w.getBounds(); w.setBounds({ x: Math.round(w.__slideToX), y: b.y, width: b.width, height: b.height }) } catch {}
    }
  }
  return closing
}

/* v1.61.1：默认落点改到主屏工作区**左侧中间**（用户指定的桌面布局）。
 * 左留 16px 是给球体外圈光晕的呼吸位；纵向按窗口高度居中 ——
 * 球体在窗口里居中，所以视觉中心正好落在工作区竖直中点。
 * 只影响「没存过位置」的首次启动和菜单里的「复位小球」；已存坐标照旧。 */
function orbDefaultPos() {
  const wa = screen.getPrimaryDisplay().workArea
  return {
    x: Math.round(wa.x + 16),
    y: Math.round(wa.y + (wa.height - ORB_WIN) / 2)
  }
}

/* v1.26：把球夹在「所有显示器的并集」里 —— 拖动时不再能把它推出屏外。
 * 老代码 orb:move 完全不夹取，实测能把球拖到 x=1381（屏宽 1440、窗口 80 → 右边 21px 在屏外）；
 * 更麻烦的是 placeOrb 的启动校验只认主屏 workArea，那种位置下次重启会被判废 → 球直接跳到右下角默认位，
 * 用户会以为「球自己跑了」。现在拖动和启动都走同一个夹取。
 */
function clampOrbPos(x, y) {
  const ds = screen.getAllDisplays()
  if (!ds.length) return { x: Math.round(x), y: Math.round(y) }
  const u = ds.reduce((a, d) => {
    const w = d.workArea
    return { x: Math.min(a.x, w.x), y: Math.min(a.y, w.y), r: Math.max(a.r, w.x + w.width), b: Math.max(a.b, w.y + w.height) }
  }, { x: Infinity, y: Infinity, r: -Infinity, b: -Infinity })
  return {
    x: Math.round(Math.min(Math.max(x, u.x), u.r - ORB_WIN)),
    y: Math.round(Math.min(Math.max(y, u.y), u.b - ORB_WIN))
  }
}

function placeOrb() {
  const def = orbDefaultPos()
  const has = Number.isFinite(orbState.x) && Number.isFinite(orbState.y)
  const pos = has ? clampOrbPos(orbState.x, orbState.y) : def
  orbWin = new BrowserWindow({
    width: ORB_WIN, height: ORB_WIN, x: pos.x, y: pos.y,
    frame: false, transparent: true, hasShadow: false, resizable: false,
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(orbWin)
  hideFromMissionControl(orbWin)
  /* v1.25.1：守住小球窗口的尺寸（不变量 = ORB_WIN × ORB_WIN）
   * 现象：每次启动后只要跑过一次识别（点击或自动扫描都算），macOS 会把小球窗口的框架**连带内容**
   *       撑高 28pt（80×80 → 80×108），小球在窗口里重新居中 → 视觉上往下挪 14pt 并且一直停在那儿。
   * 排查记录（README「已知怪相」有完整版）：v1.23 原版一样会变；右键弹菜单不会；鼠标悬停不点不会；
   *       用 AUTO_TRIGGER 不带任何真实点击也会变；30ms 轮询显示答案面板出现后约 40ms 跟着变。
   *       代码里所有 setBounds 都过了一遍，能碰小球的只有 placeOrb（写死 ORB_WIN）和 orb:move
   *       （原尺寸透传），没有任何一条算得出 108 —— 也就是系统干的，不是本程序的逻辑。
   * 所以这里不去猜系统为什么这么干，直接守不变量：一发现尺寸不对就当场掰回来（带 300ms 防互拉）。
   */
  let orbFixAt = 0
  orbWin.on('resize', () => {
    if (!orbWin || orbWin.isDestroyed()) return
    const b = orbWin.getBounds()
    if (b.width === ORB_WIN && b.height === ORB_WIN) return
    const t = Date.now()
    if (t - orbFixAt < 300) return
    orbFixAt = t
    orbWin.setBounds({ x: b.x, y: b.y, width: ORB_WIN, height: ORB_WIN })
    logCapture('ORB_RESIZE 小球窗口被系统改成 ' + b.width + 'x' + b.height + '，已掰回 ' + ORB_WIN + 'x' + ORB_WIN)
  })
  // v1.76：orb:guard 这类推送统一走 sendToWin —— 渲染层没就绪时先排队，加载完补发（不丢守护状态）
  orbWin.webContents.on('did-finish-load', () => { if (orbWin) winReady(orbWin) })
  orbWin.loadFile(path.join(__dirname, 'orb.html'))
  orbWin.on('closed', () => { orbWin = null })
}

/* v1.60：结果面板位置 —— left 屏幕左边（默认，从左侧滑出）/ orb 跟着小球（v1.59 及以前的位置）。设置 → 外观里切 */
const PANEL_SIDES = ['left', 'orb']
const PANEL_EDGE = 12
const SLIDE_MS = 220, SLIDE_DX = 64
// 从左往右滑进来 + 淡入（ease-out）。只滑一小段：整块移到屏外在多屏 / macOS 上会被系统夹回来
function slideIn(win, toX) {
  if (!win || win.isDestroyed()) return
  win.__closing = false
  win.__slideToX = toX   // v1.73 D3：记下终点 —— 截屏遮挡时要把动画定格到终点，不能把它卡在半路
  const b0 = win.getBounds()
  win.setOpacity(0)
  win.setBounds({ x: toX - SLIDE_DX, y: b0.y, width: b0.width, height: b0.height })
  win.showInactive()
  fadeWin(win, 1, SLIDE_MS)
  const t0 = Date.now()
  clearInterval(win.__slideT)
  win.__slideT = setInterval(() => {
    if (win.isDestroyed()) { clearInterval(win.__slideT); return }
    const k = Math.min(1, (Date.now() - t0) / SLIDE_MS)
    const b = win.getBounds()
    win.setBounds({ x: Math.round(toX - SLIDE_DX * Math.pow(1 - k, 3)), y: b.y, width: b.width, height: b.height })
    if (k >= 1) { clearInterval(win.__slideT); win.__slideT = null }
  }, 16)
}

function answerMaxH() {
  // v1.32：上限收紧到 ANSWER_MAX_H(480)，不再放到 80% 屏高
  try { return Math.max(320, Math.min(ANSWER_MAX_H, Math.round(screen.getPrimaryDisplay().workArea.height * 0.8))) } catch { return ANSWER_MAX_H }
}

function placeAnswer() {
  if (answerWin && !answerWin.isDestroyed()) { cancelClose(answerWin); return answerWin }
  const W = answerWidth()
  const side = PANEL_SIDES.includes(orbState.panelSide) ? orbState.panelSide : 'left'
  let wa = screen.getPrimaryDisplay().workArea
  let x, y
  if (side === 'left') {
    // v1.60（对齐安卓 v1.66）：默认贴着小球所在屏幕的左边，从左侧滑出；竖直方向按最高高度居中，长高时不出屏
    try { if (orbWin) wa = (screen.getDisplayMatching(orbWin.getBounds()) || screen.getPrimaryDisplay()).workArea } catch {}
    x = wa.x + PANEL_EDGE
    y = wa.y + Math.max(PANEL_EDGE, Math.round((wa.height - answerMaxH()) / 2))
  } else {
    const ox = orbWin ? orbWin.getBounds().x : wa.x + wa.width - ORB_SIZE - 20
    const oy = orbWin ? orbWin.getBounds().y : wa.y + Math.round(wa.height * 0.42)
    x = ox - W - 16
    y = oy
    if (x < wa.x) x = wa.x + 12
    if (y < wa.y) y = wa.y + 12
    if (y + 320 > wa.y + wa.height) y = wa.y + wa.height - 320
  }
  answerWin = new BrowserWindow({
    width: W, height: 220, x, y,
    frame: false, transparent: true, hasShadow: false, resizable: true,
    minWidth: 300, minHeight: 120, maxWidth: W, maxHeight: answerMaxH(),
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 答案',
    focusable: false, show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(answerWin)
  hideFromMissionControl(answerWin)
  answerWin.loadFile(path.join(__dirname, 'answer.html'))
  if (side === 'left') answerWin.once('ready-to-show', () => { slideIn(answerWin, x); orbToFront() })
  else answerWin.once('ready-to-show', () => { revealWin(answerWin, false); orbToFront() })
  const aw = answerWin
  aw.webContents.on('did-finish-load', () => { winReady(aw); sendMode() })
  answerWin.on('closed', () => { if (answerWin === aw) answerWin = null })
  return answerWin
}

/* ---------------- 渲染层就绪前先排队（v1.24） ----------------
 * 老写法是 loadFile 之后立刻 webContents.send —— 此时渲染层还没注册监听，消息直接被丢掉。
 * 表现：面板弹出来了，但一个字都没有（翻译面板是个纯空框；答案面板也要等第一段流才出现）。
 * 现在所有推送统一走 sendToWin：没 did-finish-load 先排队，就绪后按原顺序补发。
 */
const readyWins = new WeakSet()
const pendingMsgs = new WeakMap()
function sendToWin(win, channel, payload) {
  if (!win || win.isDestroyed()) return
  if (readyWins.has(win)) { try { win.webContents.send(channel, payload) } catch {} ; return }
  const q = pendingMsgs.get(win) || []
  q.push({ channel, payload })
  pendingMsgs.set(win, q)
}
function winReady(win) {
  if (!win || win.isDestroyed()) return
  readyWins.add(win)
  const q = pendingMsgs.get(win) || []
  pendingMsgs.delete(win)
  for (const m of q) { try { win.webContents.send(m.channel, m.payload) } catch {} }
}

function sendMode() {
  if (!answerWin || answerWin.isDestroyed()) return
  const m = modeInfo(currentMode)
  sendToWin(answerWin, 'answer:mode', { id: m.id, label: m.label })
}

// 切换模式：换提示词 + 换面板宽度/排版，并落盘
function setMode(id) {
  if (!allModeIds().includes(id) || id === currentMode) return
  currentMode = id
  orbState.mode = id
  saveState()
  modeLog('切到模式 ' + id + '（' + modeInfo(id).label + '）')
  if (answerWin && !answerWin.isDestroyed()) {
    const W = answerWidth()
    const b = answerWin.getBounds()
    const disp = screen.getDisplayMatching(b) || screen.getPrimaryDisplay()
    const wa = disp.workArea
    let x = b.x
    if (x + W > wa.x + wa.width) x = wa.x + wa.width - W - 12
    if (x < wa.x) x = wa.x + 12
    answerWin.setMaximumSize(W, answerMaxH())
    answerWin.setBounds({ x, y: b.y, width: W, height: b.height })
    sendMode()
  }
  if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:mode', modeInfo(id).label)
}

/* v1.47：答案日志改成攒一批再异步写 —— 原来每个 chunk 一次 appendFileSync，一次回答几百次同步磁盘调用都压在主线程上 */
let ansLogBuf = '', ansLogTimer = null
function ansLog(s) {
  ansLogBuf += s
  if (ansLogTimer) return
  ansLogTimer = setTimeout(() => {
    const b = ansLogBuf; ansLogBuf = ''; ansLogTimer = null
    require('fs').appendFile(DEBUG_DIR + '/orb-answer.log', b, () => {})
  }, 500)
}
/* v1.48：用户流式途中点了关闭 —— 这一轮剩下的块别再把面板拽回来（以前会新建一块只剩尾巴的空面板）。
 * 下一次由用户发起的识别 / 追问 / 单击小球时复位。翻译面板同理。 */
let answerDismissed = false, transDismissed = false
function pushAnswer(text, done = false) {
  ansLog(done ? text + '\n[DONE]' : text)
  if (answerDismissed) return
  sendToWin(placeAnswer(), 'answer:chunk', { text, done })
}

/* 思考模式下的静态「正在思考…」。流式里 reasoning_content 与 content 同级，
 * 思考阶段只吐 reasoning_content —— 不给一行静态提示，用户看到的就是一块静止的面板（他之前抱怨的「干等」）。
 * v1.7x：不再显示「已想 N 字」这类推理字数（桌面端没有真实 token 可显示，字数只是字符数）；
 * 字数只在后台留作计数（判断是不是思考吃光了预算）。只在还没有正文的时候显示（渲染层自己判断）。 */
function pushPhase(text) {
  if (!answerWin || answerWin.isDestroyed()) return
  sendToWin(answerWin, 'answer:phase', String(text || ''))
}
function pushTransPhase(text) {
  if (transDismissed) return
  try { sendToWin(placeTranslate(), 'trans:phase', String(text || '')) } catch {}
}

function showAnswerState(state) {
  if (answerDismissed) return
  sendToWin(placeAnswer(), 'answer:state', state)
}

/* ---------------- 划词翻译（v1.18） ----------------
 * 触发：全局热键（默认 ⌥⇧T）、右键菜单，或「复制即译」兜底模式。
 * 取词：模拟一次 ⌘C 读剪贴板（需要「辅助功能」权限），读完立刻把用户原剪贴板写回去 —— 不留痕。
 * 「复制即译」不需要任何权限（只读剪贴板），作为没有辅助功能权限时的替代方案。
 */
// 热键优先级：state.json 的 translateHotkey > 环境变量 TRANSLATE_HOTKEY > 默认键
// 放在 state.json 里是为了「换键位不用重新打包」——重签名会让系统授权失效，能免则免。
//
// v1.24：默认键从 ⌘/ 换成 ⌥⇧T，候选链里也不再留 ⌘/ 兜底。
// 原因：globalShortcut 是系统级注册，⌘/ 被抢走后别的应用再也收不到（典型：VS Code 的
// 「切换注释」）。用户在那个 App 里按 ⌘/ 得到的是「灵珠 弹一块面板」，看起来完全凭空出现。
//
// v1.38（用户要求）：**默认键改回 ⌘/**，并允许在设置卡里自己录一个键。
// 上面那段代价仍然成立 —— ⌘/ 被灵珠按住期间，编辑器/浏览器的「切换注释」就收不到了。
// 所以：设置卡里给了「改」「默认」两个按钮，随时能换；候选链保留 ⌥⇧T 兜底（注册不上时降级）。
const DEFAULT_TRANSLATE_HOTKEY = 'CommandOrControl+/'
const LEGACY_DEFAULT_HOTKEYS = ['Alt+Shift+T']   // v1.24~v1.37 的默认值：老 state.json 里存过它，那不算「自定义」
const FALLBACK_TRANSLATE_HOTKEYS = ['Alt+Shift+T', 'Alt+Shift+/']
const TRANS_W = 300
const TRANS_MAX_CHARS = 4000
const CLIP_WATCH_MS = 700
const TRANS_LOG = DEBUG_DIR + '/orb-translate.log'

let hotkeyLabel = '⌘/'
let hotkeyRaw = DEFAULT_TRANSLATE_HOTKEY

function hotkeyCandidates() {
  let fromState = (orbState && typeof orbState.translateHotkey === 'string' && orbState.translateHotkey.trim()) || ''
  // 老版本把「默认值」也写进了 state.json（Alt+Shift+T）。那不算用户自定义 ——
  // 不认这一条的话，新默认永远轮不到生效（用户会以为改了没反应）。
  // 用户真手动选了 ⌥⇧T 时 translateHotkeySet=true，那时就一律听 state 的。
  if (!orbState.translateHotkeySet && LEGACY_DEFAULT_HOTKEYS.includes(fromState)) fromState = ''
  const primary = fromState || process.env.TRANSLATE_HOTKEY || DEFAULT_TRANSLATE_HOTKEY
  return [...new Set([primary].concat(FALLBACK_TRANSLATE_HOTKEYS))]
}

/* 改热键：先注册新的，成功才写盘 + 解绑旧的 —— 顺序反了会出现「新旧都没了，按不出来」的空窗。
 * 返回 {ok,label,raw} 或 {ok:false,error}，设置卡把成功/失败都直接显示给用户。 */
function setTranslateHotkey(acc) {
  const want = String(acc || '').trim()
  try {
    if (want === '__default__') return setTranslateHotkey(DEFAULT_TRANSLATE_HOTKEY)
    if (!want) return { ok: false, error: '没有键位' }
    if (want === hotkeyRaw) return { ok: true, label: hotkeyLabel, raw: hotkeyRaw }
    let ok = false
    try { ok = globalShortcut.register(want, () => { translateSelection() }) } catch (e) { return { ok: false, error: errText(e) } }
    if (!ok) return { ok: false, error: '这个组合注册不上（多半被别的应用占着，或写法不被系统接受）' }
    if (hotkeyRaw && hotkeyRaw !== want) { try { globalShortcut.unregister(hotkeyRaw) } catch {} }
    hotkeyRaw = want
    hotkeyLabel = accelLabel(want)
    orbState.translateHotkey = want
    orbState.translateHotkeySet = true
    saveState()
    transLog('热键已改：' + want + '（显示 ' + hotkeyLabel + '）')
    if (menuWin && !menuWin.isDestroyed()) { try { menuWin.webContents.send('menu:update', { hotkey: hotkeyLabel }) } catch {} }
    return { ok: true, label: hotkeyLabel, raw: hotkeyRaw }
  } catch (e) { return { ok: false, error: errText(e) } }
}

let transWin = null
let isTranslating = false
let lastTransSrc = ''
let translateOnCopy = false
let clipWatchTimer = null
let clipIgnoreUntil = 0
let lastClipText = ''

function transLog(line) { try { stateFs.appendFileSync(TRANS_LOG, new Date().toISOString() + ' ' + line + '\n') } catch {} }
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)) }

function accessibilityOK(interactive) {
  try { return systemPreferences.isTrustedAccessibilityClient(!!interactive) } catch { return true }
}

function transMaxH() {
  // v1.32：上限收紧到 ANSWER_MAX_H(480)，不再放到 80% 屏高
  try { return Math.max(320, Math.min(ANSWER_MAX_H, Math.round(screen.getPrimaryDisplay().workArea.height * 0.8))) } catch { return ANSWER_MAX_H }
}

function placeTranslate() {
  if (transWin && !transWin.isDestroyed()) { cancelClose(transWin); return transWin }
  const cursor = screen.getCursorScreenPoint()
  const wa = (screen.getDisplayNearestPoint(cursor) || screen.getPrimaryDisplay()).workArea
  const h = 260
  let x = Math.round(cursor.x + 16)
  if (x + TRANS_W > wa.x + wa.width) x = Math.round(cursor.x - TRANS_W - 16)
  if (x < wa.x) x = wa.x + 12
  let y = Math.round(cursor.y - 24)
  if (y + h > wa.y + wa.height) y = wa.y + wa.height - h
  if (y < wa.y) y = wa.y + 12
  transWin = new BrowserWindow({
    width: TRANS_W, height: h, x, y,
    frame: false, transparent: true, hasShadow: false, resizable: true,
    minWidth: 300, minHeight: 140, maxWidth: TRANS_W, maxHeight: transMaxH(),
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 划词翻译',
    focusable: false, show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(transWin)
  hideFromMissionControl(transWin)
  transWin.loadFile(path.join(__dirname, 'translate.html'))
  transWin.once('ready-to-show', () => revealAfterFirstResize(tw, false, 120))
  const tw = transWin
  tw.webContents.on('did-finish-load', () => { winReady(tw); pushTransLang() })
  transWin.on('closed', () => { if (transWin === tw) transWin = null })
  return transWin
}

function transSend(channel, payload) {
  if (transDismissed) return
  try { sendToWin(placeTranslate(), channel, payload) } catch {}
}
// v1.24：面板没开着就什么都别做。以前「正在读取选区」也走 transSend → 会把面板就地建出来，
// 于是没选中任何文字时按热键，也会凭空冒出一块空玻璃框。
function transSendIfOpen(channel, payload) {
  if (transWin && !transWin.isDestroyed()) { try { sendToWin(transWin, channel, payload) } catch {} }
}
function pushTrans(payload) { transSend('trans:chunk', payload) }
function setTransState(s) { transSend('trans:state', s) }
function setTransSrc(t) { transSend('trans:src', t) }
/** 面板「清空重来」：上一个任务 / 引擎可能已经流式推过一半内容就失败 —— 不清掉会和下一个结果拼在一起。
 *  传 keep（最终文本）时清空后立刻把它重新打上，让面板显示的和返回 / 历史里存的完全一致。 */
function resetTransPanel(keep) {
  setTransState('translating')   // 渲染层收到 state 会清空正文与流式状态
  if (keep) pushTrans({ text: keep })
}

/* ---------------- 翻译语言（v1.20） ----------------
 * 默认「自动」= 中英互译（原文中文→英文，其余→中文）。
 * 也可以锁定目标语言；面板标题栏可点，右键菜单可循环切。
 */
const LANGS = [
  { id: 'auto', label: '自动', tip: '中英互译', rule: '判断原文语种：是中文就译成英文，不是中文就译成简体中文（中英互译）' },
  { id: 'zh', label: '中文', tip: '译成中文', rule: '一律译成简体中文' },
  { id: 'en', label: '英文', tip: '译成英文', rule: '一律译成地道的英文' },
  { id: 'ja', label: '日文', tip: '译成日文', rule: '一律译成自然的日文（必要时保留汉字写法）' },
  { id: 'ko', label: '韩文', tip: '译成韩文', rule: '一律译成自然的韩文' },
  { id: 'ru', label: '俄文', tip: '译成俄文', rule: '一律译成俄文' },
  { id: 'fr', label: '法文', tip: '译成法文', rule: '一律译成法文' },
  { id: 'de', label: '德文', tip: '译成德文', rule: '一律译成德文' }
]
const DEFAULT_TRANS_LANG = 'auto'
let transLang = DEFAULT_TRANS_LANG

function langInfo(id) { return LANGS.find((l) => l.id === id) || LANGS[0] }
/* v1.7x：富翻译 —— 按输入类型（单词 / 短语 / 句子段落）给够用的讲解，译文永远在最前。
 * 旧的「一行 / 一句话」上限删掉；提示词改到 prompts.js，主进程 / 渲染层 / test 共用一份。 */
function translatePrompt(text) { return P.translatePrompt(text, langInfo(transLang).rule) }
function enrichPrompt(text, mt, engineLabel) { return P.enrichPrompt(text, langInfo(transLang).rule, mt, engineLabel) }

/** 把一段提示词流式打进翻译面板；返回完整正文（不负责 push done —— 由上层统一收尾） */
async function streamTranslate(prompt) {
  const ac = new AbortController()
  const to = setTimeout(() => ac.abort(), 60000)
  let res
  try {
    const cfg = apiCfg()
    res = await fetch(cfg.baseUrl + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cfg.apiKey },
      // v1.75：富讲解 + 思考共用这份预算 —— 4096 在 high 思考下常被思维链吃光，调大到 8192（与移动端一致）。
      // 真遇到接口不支持这么大上限时，按现有错误路径如实报错即可，这里不加额外重试。
      body: JSON.stringify(Object.assign({ model: cfg.model, stream: true, max_tokens: 8192, messages: [{ role: 'user', content: prompt }] }, thinkingOf(cfg))),
      signal: ac.signal
    })
  } finally { clearTimeout(to) }
  if (!res.ok) {
    const t = await res.text().catch(() => '')
    throw new Error('接口 ' + res.status + ' ' + t.slice(0, 160))
  }
  const reader = res.body.getReader()
  const dec = new TextDecoder()
  let buf = ''
  let got = false, full = ''
  let thinkChars = 0, thinkAnnounced = false   // thinkChars 只留计数：失败时判断「是不是思考吃光了预算」；面板只显示静态「正在思考…」
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let nl
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).trim()
      buf = buf.slice(nl + 1)
      if (!line.startsWith('data:')) continue
      const d = line.slice(5).trim()
      if (d === '[DONE]') continue
      try {
        const j = JSON.parse(d)
        const dl = (j.choices && j.choices[0] && j.choices[0].delta) || {}
        const rc = dl.reasoning_content || ''
        if (rc) {                                  // 思考中也要有反馈（静态一行），别让面板干等
          thinkChars += rc.length
          if (!thinkAnnounced) { thinkAnnounced = true; pushTransPhase('正在思考…') }
        }
        const t = dl.content || ''
        if (t) { got = true; full += t; pushTrans({ text: t }) }
      } catch {}
    }
  }
  if (!got) throw new Error('模型未返回内容' + (thinkChars > 0 ? '（思考完没吐出正文 —— 输出预算被思考吃光，可在设置卡把「思考强度」调低或关掉）' : ''))
  return full
}

/* ---------------- 翻译引擎计划（v1.7x） ----------------
 * 富翻译（译文 + 讲解）只有在「配了 AI」时才可能拿到 —— DeepL / 百度是纯机器翻译，给不了讲解。
 *   · 自动：配了 AI → 优先 AI 出富讲解；AI 不行再退到 DeepL / 百度（只有译文，面板会说明没做讲解）。
 *   · 单选 DeepL / 百度：先机器翻译，配了 AI 再拿「原文 + 机器译文」让 AI 补讲解；AI 不行就保留真实译文并说明。
 *   · 单选 AI：只用 AI。失败就报错，绝不偷偷换成别的翻译引擎。 */
const TRANS_CACHE_V = 'tr-rich-1'          // 输出内容版本：旧的一行 / 一句话结果不再命中（换了键，自动失效）
const NOTE_NO_AI = '（只有机器译文，没有讲解：没有配置 AI 模型；可在设置里填 AI 接口）'
const NOTE_AI_FAIL = '（只有机器译文，没有讲解：AI 讲解没成功）'
/** AI 是否「配置过」——只认用户自己填的地址；开源构建没有内置接口。 */
function aiConfigured() {
  const a = orbState.api || {}
  return !!(typeof a.baseUrl === 'string' && a.baseUrl.trim())
}
function aiDesc() { const c = apiCfg(); return 'ai|' + c.baseUrl + '|' + c.model }
function mtDesc(id) { return id === 'baidu' ? 'baidu|' + ((orbState.baidu && orbState.baidu.model) || 'llm') : 'deepl|v2' }

/** AI 富输出：一次流式给「译文 + 讲解」 */
function aiRichJob() {
  return {
    base: ENGINE_LABEL.ai, label: ENGINE_LABEL.ai,
    desc: 'rich|' + aiDesc(),
    run: async () => { pushTransPhase('翻译中…'); return await streamTranslate(translatePrompt(lastTransSrc)) }
  }
}
/** 机器翻译（可选 AI 讲解）。enrich=true 时先落机器译文，再让 AI 基于「原文 + 机器译文」补讲解；
 *  AI 不可用 / 讲解失败则保留真实机器译文并附一行说明（不伪造讲解）。 */
function mtJob(id, opts) {
  opts = opts || {}
  const b = orbState.baidu || {}
  const enrich = !!opts.enrich
  const job = {
    base: ENGINE_LABEL[id],
    label: enrich ? ENGINE_LABEL[id] + ' · AI 讲解' : ENGINE_LABEL[id],
    // 说明也进缓存键：同一台机器翻译，「AI 没配」和「AI 失败」的说明不同，别互相命中（否则说明会串味）
    desc: (enrich ? 'rich|' : 'plain|') + mtDesc(id) + (enrich ? '+ai|' + apiCfg().baseUrl + '|' + apiCfg().model : (opts.note ? '|' + opts.note : '')),
    // 这次是不是「只拿到机器译文、没拿到讲解」—— 讲解失败时置位，上层据此不把结果按 rich 键写进缓存
    // （否则下次同样的翻译会命中这条降级结果，AI 讲解永远没机会重试成功）。
    noRich: false,
    run: async () => {
      pushTransPhase('翻译中…（' + ENGINE_LABEL[id] + '）')
      const mt = id === 'deepl' ? await X.deeplTranslate(deeplKey(), lastTransSrc, transLang) : await X.baiduTranslate(b, lastTransSrc, transLang)
      if (!String(mt).trim()) throw new Error(ENGINE_LABEL[id] + ' 返回了空译文')
      pushTrans({ text: mt })                                   // 译文永远在最前，先落地
      if (!enrich) {
        const note = opts.note ? '\n\n' + opts.note : ''
        if (note) pushTrans({ text: note })
        return String(mt) + note
      }
      try {
        pushTrans({ text: '\n\n' })
        const rich = await streamTranslate(enrichPrompt(lastTransSrc, mt, ENGINE_LABEL[id]))
        return String(mt) + '\n\n' + rich
      } catch (e) {
        transLog('AI 讲解失败：' + (e && e.message))
        job.noRich = true
        // AI 讲解写了一半就失败：面板上已推入的那段残稿要清掉，重推「机器译文 + 说明」——
        // 显示的和 return / 历史必须是同一份，不能出现「面板有半截讲解、历史里却没有」的错位。
        const out = String(mt) + '\n\n' + NOTE_AI_FAIL
        resetTransPanel(out)
        return out
      }
    }
  }
  return job
}
/** 引擎 → 依次尝试的任务列表 */
function transJobs(engine) {
  const has = { deepl: !!deeplKey(), baidu: X.baiduConfigured(orbState.baidu || {}) }
  const ai = aiConfigured()
  if (engine === 'ai') return [aiRichJob()]
  if (engine === 'deepl' || engine === 'baidu') return [mtJob(engine, { enrich: ai, note: ai ? null : NOTE_NO_AI })]
  const jobs = []
  if (ai) jobs.push(aiRichJob())
  // AI 配了但没成功（才会退到这两路）→ 明说这次没做讲解；没配 AI 就用既有的「没配 AI」说明
  const mtNote = ai ? NOTE_AI_FAIL : NOTE_NO_AI
  if (has.deepl) jobs.push(mtJob('deepl', { enrich: false, note: mtNote }))
  if (has.baidu) jobs.push(mtJob('baidu', { enrich: false, note: mtNote }))
  if (!jobs.length) jobs.push(aiRichJob())   // 什么也没配 → 仍试一次 AI（会给出明确报错）
  return jobs
}

async function translateText(text, fresh) {
  lastTransSrc = String(text || '').slice(0, TRANS_MAX_CHARS)
  if (!lastTransSrc.trim()) { setTransState('no_selection'); return }
  setTransSrc(lastTransSrc)
  setTransState('translating')
  const engine = TRANS_ENGINES.includes(orbState.transEngine) ? orbState.transEngine : defaultEngine()
  const jobs = transJobs(engine)
  // 缓存键带上「输出内容版本 + 提示词身份 + 实际产出的组合（引擎 + 模型，含讲解用的 AI）」：
  // 提示词内容一变（P.PROMPT_VERSION 递增），旧提示词产出的结果就不再命中
  const keyOf = (job) => X.cacheKey('translate', TRANS_CACHE_V, P.PROMPT_VERSION, job.desc, transLang, lastTransSrc)
  if (orbState.useCache !== false && !fresh) {
    // 只认「首选任务」的缓存 —— 备选任务的缓存（尤其是只有译文的机器翻译）不能顶替首选，
    // 否则 AI 优先时一旦 AI 的富讲解缓存没命中，就会拿一条旧的机器译文缓存把结果降级掉。
    const job = jobs[0]
    const hit = transCache().get(keyOf(job))
    if (hit) {
      transSend('trans:badge', job.label + ' · 缓存（' + X.ago(hit.c) + '）')
      transLog('翻译 ' + lastTransSrc.length + ' 字 → ' + langInfo(transLang).label + '（' + job.label + '）（缓存命中）')
      pushTrans({ text: hit.v }); pushTrans({ done: true })
      return
    }
  }
  const fails = []
  for (let i = 0; i < jobs.length; i++) {
    const job = jobs[i], last = i === jobs.length - 1
    const note = fails.length ? '（' + fails.join('；') + '）' : ''
    transSend('trans:badge', job.label + note)
    transLog('翻译 ' + lastTransSrc.length + ' 字 → ' + langInfo(transLang).label + '（' + job.label + '）' + note)
    let out
    try { out = await job.run() }
    catch (err) {
      if (last) throw fails.length ? new Error(err.message + '\n（前面也没成功：' + fails.join('；') + '）') : err
      const why = String(err && err.message || err).replace(/^(DeepL|百度翻译) ?出错（\d+）：/, '').slice(0, 40)
      fails.push(job.base + ' 没成功：' + why)
      transLog('  ' + job.base + ' 失败 → 换下一个：' + (err && err.message))
      // 这一路流式写了一半就失败（典型：AI 富讲解被思考预算吃光）：清空面板再换下一个引擎，
      // 否则上一路的残稿会和下一路的译文拼在一起，显示的和历史 / 返回值对不上。
      resetTransPanel()
      continue
    }
    pushTrans({ done: true })
    // noRich = 只有机器译文、没拿到讲解（rich 键的降级结果）—— 不写缓存，下次才会重试 AI 讲解
    if (orbState.useCache !== false && String(out).trim() && !job.noRich) transCache().put(keyOf(job), out)
    historyAdd({ kind: 'translate', label: job.label + ' → ' + langInfo(transLang).label, text: out, src: lastTransSrc })
    return
  }
}

/* v1.58：结果缓存 + 历史落盘（文件都在 userData 下，权限 600） */
let _transCache = null, _solveCache = null, _histStore = null
function transCache() { return _transCache || (_transCache = new X.Cache(app.getPath('userData'), 'translate', 300)) }
function solveCache() { return _solveCache || (_solveCache = new X.Cache(app.getPath('userData'), 'solve', 100)) }
function histStore() { return _histStore || (_histStore = new X.JsonStore(path.join(app.getPath('userData'), 'history.json'))) }
/**
 * 历史落盘走 tmp + rename：同步服务会同时读这份文件（lan-store 的 historyRead / historyWrite），
 * 直接 writeFileSync 可能让另一端读到半截 JSON。
 * strict = true 时写失败必须抛出：同步落盘不能返回 false 假装成功（那会把磁盘错误错报成网络错误）。
 */
function histWriteAtomic(list, strict) {
  const file = histStore().file
  const tmp = file + '.tmp'
  try {
    stateFs.writeFileSync(tmp, JSON.stringify(list), { mode: 0o600 })
    stateFs.renameSync(tmp, file)
    return true
  } catch (err) {
    try { stateFs.unlinkSync(tmp) } catch {}
    if (strict) {
      const why = String((err && err.message) || err || '未知错误')
      const wrapped = new Error('历史写入失败：' + why)
      wrapped.cause = err
      throw wrapped
    }
    return false
  }
}

/** 同步读历史：坏 JSON / 读失败必须抛，不能当成空库 —— 空库会让另一端把收藏全删掉。 */
function histReadStrict() {
  const store = histStore()
  if (typeof store.readStrict === 'function') return store.readStrict()
  if (typeof X.readStrict === 'function') return X.readStrict(store.file)
  throw new Error('历史严格读取不可用（extras.js 缺少 readStrict），已停止同步以保护本机历史')
}

/**
 * 历史最多 200 条（v1.58），但 ★ 收藏的条目不受这个上限裁剪：
 * 新记录插到最前，非收藏最多留 X.HISTORY_MAX 条，收藏原样保留。
 */
function historyAddKeepStars(list, entry) {
  const first = list[0]
  if (first && first.kind === entry.kind && first.text === entry.text && (first.src || '') === (entry.src || '')) return list
  const all = [entry].concat(list)
  const out = []
  let plain = 0
  for (const item of all) {
    if (item && item.star) { out.push(item); continue }
    if (plain < X.HISTORY_MAX) { out.push(item); plain += 1 }
  }
  return out
}

/** 返回这条的时间戳 id（关了保存时返回 0） */
function historyAdd(e) {
  if (orbState.saveHistory === false || !e || !String(e.text || '').trim()) return 0
  // v1.61.2：冷启动 pending journal 必须先恢复；守卫失败就不写，返回 0（不假成功）。
  try { X.runWriteGuard() } catch (err) { lanGuardNotice(err); return 0 }
  const l = histStore().read()
  const id = Math.max(Date.now(), (l[0] && l[0].t + 1) || 0)
  const nl = historyAddKeepStars(l, Object.assign({ t: id }, e))
  histWriteAtomic(nl)
  return nl[0].t
}
function historyUpdate(id, text) {
  if (!id || !String(text || '').trim()) return
  try { X.runWriteGuard() } catch (err) { lanGuardNotice(err); return }
  histWriteAtomic(histStore().read().map((x) => x.t === id ? Object.assign({}, x, { text }) : x))
}

/* ================= v1.61：局域网同步（手动开启，停即关闭） =================
 * 只有用户在设置卡里点「开始同步」才监听；设置卡一关 / App 退出立刻 stop：
 *   · socket 全部 destroy，session key 直接丢掉（下次开启是新 key、新连接串）
 *   · 不做后台常驻，也不在开机 / 启动时自动开
 * 数据层（记录形状 / 时钟 / 冲突副本 / 落盘）全部在 lan-store.js，本段只做接线：
 *   knowledge = 现有真实 Knowledge.Store 实例（严格读取走 listStrict）
 *   historyRead / historyWrite = main 的 histStore（严格读；原子写且失败必抛，保留 star）
 */
let _lanStore = null
let _lanSync = null
/** v1.61.2：恢复失败后一直保留，直到本进程里有一次恢复真正成功。 */
let _lanRecoverError = null

function lanStore() {
  if (_lanStore) return _lanStore
  let mod
  try { mod = require('./lan-store') }
  catch { throw new Error('同步数据层未安装（缺少 lan-store.js），暂时不能开启同步') }
  const Ctor = mod && (mod.LanStore || mod.default || mod)
  if (typeof Ctor !== 'function') throw new Error('lan-store.js 没有导出 LanStore，暂时不能开启同步')
  const cards = kcardStore()
  if (typeof cards.listStrict !== 'function') {
    throw new Error('知识库严格读取不可用（knowledge.listStrict 缺失），已停止同步以保护本机卡片')
  }
  _lanStore = new Ctor({
    dir: app.getPath('userData'),
    // 直接把真实 Store 交过去：lan-store 会用 listStrict / nextId / replaceFromSync，
    // 并把「编辑前先恢复 journal」的守卫装到实例上。再包一层会把这些能力挡掉。
    knowledge: cards,
    historyRead: histReadStrict,
    historyWrite: (list) => {
      // 写失败必须抛：lan-store 只有拿到异常才会回滚 / 不 ack。
      if (!Array.isArray(list)) throw new TypeError('同步写回历史需要数组')
      histWriteAtomic(list, true)
    }
  })
  _lanRecoverError = null
  return _lanStore
}

/**
 * v1.61.2：冷启动 pending journal 的惰性恢复守卫。
 *
 * 同步只在用户点「开始同步」时才建 LanStore，但上次同步中途崩溃留下的 journal 必须在
 * **下一次业务写盘之前**重放；否则历史 / 知识卡先落盘，随后恢复又用 journal 里的旧内容
 * 把它们覆盖掉（历史编辑的 X.runWriteGuard 以前就是这样空转到同步开启为止）。
 *
 * 这里不提前构造 LanStore（没有 journal 时不建 sidecar、更不监听端口），只在真的看到
 * journal 时才构造：
 *   · 恢复成功 → lan-store 会把自己的守卫装到 K/X 上，后续业务写正常；
 *   · 恢复失败 → 错误一直留在 _lanRecoverError，之后每次写都继续拒绝；
 *     journal 原样保留，不删除、不隔离后继续编辑。
 */
function lanJournalPending() {
  try { return stateFs.existsSync(path.join(app.getPath('userData'), 'lan-sync.journal.json')) }
  catch { return false }
}

function lanRecoveryGuard() {
  if (_lanRecoverError) throw _lanRecoverError
  if (!lanJournalPending()) return
  try { lanStore() }
  catch (err) {
    const wrapped = new Error('同步恢复失败，数据未写入：' + lanErrorText(err))
    wrapped.cause = err
    _lanRecoverError = wrapped
    modeLog(wrapped.message)
    throw wrapped
  }
}

/** app.whenReady 里、任何 UI 之前调用：历史与知识卡落盘前都先过这道守卫。 */
function installLanWriteGuards() {
  X.setWriteGuard(lanRecoveryGuard)
  if (K && typeof K.setWriteGuard === 'function') K.setWriteGuard(lanRecoveryGuard)
}

/** 守卫拒绝写时给用户看的话：明确「没写进去」，不退化成「抽不出内容」。 */
function lanGuardNotice(err) {
  const msg = '同步恢复失败，数据未写入'
  const why = lanErrorText(err)
  modeLog(msg + (why && why !== msg ? '：' + why : ''))
  pushNotice(msg)
  transSendIfOpen('trans:badge', msg)   // 翻译面板没开就什么都不做
  return msg
}

function lanSync() {
  if (!_lanSync) _lanSync = new LanSync({ store: lanStore() })
  return _lanSync
}

function lanSyncStop() {
  if (!_lanSync) return Promise.resolve({ ok: true })
  try {
    return Promise.resolve(_lanSync.stop()).catch((err) => {
      modeLog('局域网同步停止失败：' + lanErrorText(err))
      return { ok: false, error: lanErrorText(err) }
    })
  } catch (err) {
    modeLog('局域网同步停止失败：' + lanErrorText(err))
    return Promise.resolve({ ok: false, error: lanErrorText(err) })
  }
}

/** 敏感 IPC 只认设置窗口的 webContents，别的 renderer 一律拒绝。 */
function isSettingsSender(e) {
  return !!(e && e.sender && settingsWin && !settingsWin.isDestroyed() && e.sender === settingsWin.webContents)
}

function lanErrorText(err) {
  return String((err && err.message) || err || '未知错误').replace(/^lan-sync:\s*/, '')
}

ipcMain.handle('lan-sync:status', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try {
    if (!_lanSync) return { ok: true, running: false, port: 0, connectionStrings: [], hint: '', startedAt: 0, lastExchangeAt: 0, exchanges: 0, conflicts: 0, lastStats: null, lastError: '' }
    return Object.assign({ ok: true }, _lanSync.status())
  } catch (err) { return { ok: false, error: lanErrorText(err) } }
})

ipcMain.handle('lan-sync:start', async (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try {
    const r = await lanSync().start()
    // listen 是异步的：等它回来时设置卡可能已经关掉。绝不能让端口在看不见的界面里继续开着。
    if (!isSettingsSender(e)) {
      await lanSyncStop()
      return { ok: false, error: '设置窗口已关闭，同步已停止', running: false }
    }
    return Object.assign({ ok: true }, r)
  } catch (err) {
    // 失败也要把可能开了一半的端口收掉，再如实报错。
    await lanSyncStop()
    modeLog('局域网同步开启失败：' + lanErrorText(err))
    return { ok: false, error: lanErrorText(err), running: false }
  }
})

ipcMain.handle('lan-sync:stop', async (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const r = await lanSyncStop()
  return Object.assign({ ok: true }, r)
})

ipcMain.handle('lan-sync:copy', (e, index) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const idx = Number(index)
  if (!Number.isInteger(idx) || idx < 0) return { ok: false, error: '没有这条连接串' }
  let list = []
  try { list = _lanSync ? _lanSync.status().connectionStrings : [] } catch {}
  if (!list[idx]) return { ok: false, error: '连接串已失效，请重新开始同步' }
  clipboard.writeText(list[idx])
  return { ok: true }
})

/* v1.62：冲突查看 / 三选解决。两个入口都只接受设置窗口，且都在 lan-store 的锁内事务里落盘。 */
/** 没同步过（sidecar 与 journal 都不存在）就别为了「看冲突」新建 sidecar。 */
function lanConflictList() {
  if (_lanStore) return _lanStore.listConflicts()
  let hasState = false
  try { hasState = stateFs.existsSync(path.join(app.getPath('userData'), 'lan-sync.json')) } catch {}
  if (!hasState && !lanJournalPending()) return []
  return lanStore().listConflicts()
}

ipcMain.handle('lan-sync:conflicts', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try { return { ok: true, conflicts: lanConflictList() } }
  catch (err) { return { ok: false, error: lanErrorText(err) } }
})

ipcMain.handle('lan-sync:resolve', (e, copyUid, pick) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try {
    const store = lanStore()
    const r = store.resolveConflict(String(copyUid == null ? '' : copyUid), String(pick == null ? '' : pick))
    return Object.assign({ ok: true }, r, { conflicts: store.listConflicts() })
  } catch (err) {
    return { ok: false, error: lanErrorText(err) }
  }
})

/* ================= v1.64：MCP（供本机外部 Agent）+ 手机常连（持久配对） =================
 * 三条线彼此独立，别混：
 *   · 局域网同步（v1.61）：点「开始同步」才开端口，设置卡一关就停 —— 一次性对数据；
 *   · MCP（本段）：只绑 127.0.0.1:<mcpPort>，给本机 MCP 客户端调 Ling 的工具；
 *   · 手机常连（本段）：绑私网地址，手机扫码后持久配对、后台轮询。
 * 三条硬约束：① 两个开关默认关、新装一律关（开关值持久保存，下次启动按上次的选择起）；
 * ② 端口占用绝不自动换一个 —— 如实报错，让用户自己改；③ 只有当前设置窗口的 webContents
 * 能调这些 IPC（签发 token / 撤销 / 改端口 / 审计都算敏感），别处 renderer 一律「没有权限」。
 * 退出：before-quit 先发起 stop，will-quit 等它真的关完 socket 再退出（和 LAN 同步同一套写法）。
 */
const MCP_DEFAULT_PORT = 47321
const LINK_DEFAULT_PORT = 47322
const MCP_SCOPES = ['read', 'write', 'act', 'personal']
const DESKTOP_CONFIRM_MS = 30000   // T9 桌面打开文件：本机确认框 30 秒不选 = 拒绝

let _mcpAuth = null
let _mcpAudit = null
let _mcpTools = null
let _mcpHub = null
let _link = null

/** 模块缺失 / 载入失败时给用户看的话 —— 绝不伪造「已开启」。 */
function svcRequire(name, what) {
  try { return require(name) }
  catch (err) { throw new Error(what + '不可用（无法加载 ' + name + '.js）：' + svcErrorText(err)) }
}
function svcErrorText(err) {
  return String((err && err.message) || err || '未知错误').replace(/^(mcp-hub|mcp-auth|mcp-audit|mcp-tools|ling-link):\s*/, '')
}

/** token 哈希 / 审计 / 回滚日志都在 mcp/；Link 的配对密钥单独放 link/；讲义在 handouts/。 */
function mcpDir() { return path.join(app.getPath('userData'), 'mcp') }
function linkDir() { return path.join(app.getPath('userData'), 'link') }
function handoutsDir() { return path.join(app.getPath('userData'), 'handouts') }

function portOk(v) {
  const n = Number(v)
  return (Number.isInteger(n) && n >= 1024 && n <= 65535) ? n : 0
}
function mcpPort() { return portOk(orbState.mcp && orbState.mcp.port) || MCP_DEFAULT_PORT }
function linkPort() { return portOk(orbState.link && orbState.link.port) || LINK_DEFAULT_PORT }

/** 用户没配根目录时给三个默认位置（T9 只允许这些目录里的真实文件）。 */
function mcpRootsEffective() {
  const own = (orbState.mcp && Array.isArray(orbState.mcp.roots)) ? orbState.mcp.roots.filter((x) => typeof x === 'string' && x) : []
  if (own.length) return own.slice()
  const out = []
  for (const k of ['documents', 'downloads', 'desktop']) {
    try { const p = app.getPath(k); if (p) out.push(p) } catch {}
  }
  return out
}

function mcpAuthGet() {
  if (!_mcpAuth) {
    const mod = svcRequire('./mcp-auth', 'MCP 鉴权模块')
    if (typeof mod.createAuth !== 'function') throw new Error('mcp-auth.js 没有导出 createAuth')
    _mcpAuth = mod.createAuth({ dir: mcpDir() })
  }
  return _mcpAuth
}
function mcpAuditGet() {
  if (!_mcpAudit) {
    const mod = svcRequire('./mcp-audit', 'MCP 审计模块')
    if (typeof mod.createAudit !== 'function') throw new Error('mcp-audit.js 没有导出 createAudit')
    _mcpAudit = mod.createAudit({ dir: mcpDir() })
  }
  return _mcpAudit
}

/* tools 拿到的 link 是薄转发：Link 没开时抛 PHONE_OFFLINE 之类的错误码，由 tools 转成 MCP 错误。
 * 这里不在构造期就起监听，也不在没开 Link 时去建目录。 */
const linkFacade = {
  call: (...a) => lingLinkGet().call(...a),
  status: () => { try { return lingLinkGet().status() } catch (err) { return { running: false, port: 0, error: svcErrorText(err), devices: [] } } },
  cancelClient: (id) => { try { return lingLinkGet().cancelClient(id) } catch { return 0 } }
}

function mcpToolsGet() {
  if (!_mcpTools) {
    const mod = svcRequire('./mcp-tools', 'MCP 工具模块')
    if (typeof mod.createTools !== 'function') throw new Error('mcp-tools.js 没有导出 createTools')
    _mcpTools = mod.createTools({
      // 真实数据层：知识卡 Store（带 listStrict / nextId / remove）、历史严格读、LAN 恢复守卫
      store: () => kcardStore(),
      // 严格读：只有「文件不存在」算空；损坏 / 读失败必须抛，不能把坏库当空库交给 Agent
      history: () => histStore().readStrict(),
      guard: () => X.runWriteGuard(),
      afterWrite: () => { pushKcardMenuBadge() },
      link: linkFacade,
      audit: mcpAuditGet(),
      confirmFile: (o) => confirmDesktopFile(o),
      openFile: (p) => openDesktopFile(p),
      roots: () => mcpRootsEffective(),
      dir: mcpDir(),
      handoutsDir: handoutsDir(),
      version: app.getVersion()
    })
  }
  return _mcpTools
}

function mcpHubGet() {
  if (!_mcpHub) {
    const mod = svcRequire('./mcp-hub', 'MCP 服务模块')
    if (typeof mod.createHub !== 'function') throw new Error('mcp-hub.js 没有导出 createHub')
    _mcpHub = mod.createHub({ auth: mcpAuthGet(), audit: mcpAuditGet(), tools: mcpToolsGet(), port: mcpPort(), version: app.getVersion() })
  }
  return _mcpHub
}

function lingLinkGet() {
  if (!_link) {
    const mod = svcRequire('./ling-link', '手机常连模块')
    if (typeof mod.createLink !== 'function') throw new Error('ling-link.js 没有导出 createLink')
    _link = mod.createLink({
      dir: linkDir(),
      port: linkPort(),
      /* 事件里不含链接密钥 / 配对串 / 请求体（Link owner 保证）。
       * 审计只保留白名单字段（event/ts/client/tool/pair/reason/count/...），所以这里把
       * Link 的 type 与嵌套 entry 展平到白名单字段上，别指望 audit 会替你保留任意键。 */
      onEvent: (ev) => {
        const e2 = ev || {}
        const kind = String(e2.type || 'event')
        const row = { event: 'link_' + kind.replace(/[^a-z-]/g, ''), ts: Date.now() }
        if (e2.pairId) row.pair = String(e2.pairId)
        if (e2.name) row.note = String(e2.name)
        if (e2.model) row.device = String(e2.model)
        if (e2.caps != null) row.count = Number(e2.caps) || 0
        if (e2.count != null) row.count = Number(e2.count) || 0
        if (e2.reason) row.reason = String(e2.reason)
        if (e2.clientId) row.client = String(e2.clientId)
        if (e2.entry && typeof e2.entry === 'object') {
          if (e2.entry.id) row.target = String(e2.entry.id)
          if (e2.entry.tool) row.tool = String(e2.entry.tool)
          if (e2.entry.client) {
            const c = e2.entry.client
            row.client = String((c && c.id) || c)
            if (c && c.name) row.clientName = String(c.name)
          }
          if (e2.entry.state) row.reason = String(e2.entry.state)
          if (e2.entry.pairId) row.pair = String(e2.entry.pairId)
        }
        try { mcpAuditGet().record(row) } catch {}
      }
    })
  }
  return _link
}

async function mcpHubStart() {
  try {
    const hub = mcpHubGet()
    await hub.start()
    const s = hub.status()
    if (s && s.error) { modeLog('MCP 服务启动后报错：' + s.error); return { ok: false, error: String(s.error) } }
    modeLog('MCP 服务已开启 127.0.0.1:' + ((s && s.port) || mcpPort()))
    return { ok: true }
  } catch (err) {
    const msg = svcErrorText(err)
    modeLog('MCP 服务开启失败：' + msg)
    return { ok: false, error: msg }
  }
}
async function mcpHubStop() {
  if (!_mcpHub) return { ok: true }
  try { await _mcpHub.stop(); modeLog('MCP 服务已停止'); return { ok: true } }
  catch (err) { const msg = svcErrorText(err); modeLog('MCP 服务停止失败：' + msg); return { ok: false, error: msg } }
}
async function linkStart() {
  try {
    const l = lingLinkGet()
    await l.start()
    const s = l.status()
    if (s && s.error) { modeLog('手机常连启动后报错：' + s.error); return { ok: false, error: String(s.error) } }
    modeLog('手机常连已开启 端口 ' + ((s && s.port) || linkPort()))
    return { ok: true }
  } catch (err) {
    const msg = svcErrorText(err)
    modeLog('手机常连开启失败：' + msg)
    return { ok: false, error: msg }
  }
}
async function linkStop() {
  if (!_link) return { ok: true }
  try { await _link.stop(); modeLog('手机常连已停止'); return { ok: true } }
  catch (err) { const msg = svcErrorText(err); modeLog('手机常连停止失败：' + msg); return { ok: false, error: msg } }
}

/** 设置页要的形状：绝不带 token / 哈希 / 配对密钥。lastUsedAt 只是时间戳，用来显示「最近用过」。 */
function safeClient(c) {
  return {
    id: String((c && c.id) || ''), name: String((c && c.name) || ''),
    scopes: (c && Array.isArray(c.scopes)) ? c.scopes.slice() : [],
    tools: (c && Array.isArray(c.tools)) ? c.tools.slice() : null,
    createdAt: (c && c.createdAt) || 0,
    lastUsedAt: (c && c.lastUsedAt) || 0
  }
}
function hubStatusSafe() {
  const out = { running: false, port: mcpPort(), error: '' }
  try {
    if (_mcpHub) {
      const s = _mcpHub.status()
      if (s) {
        out.running = !!s.running
        if (s.port) out.port = s.port
        out.error = s.error ? String(s.error) : ''
      }
    }
  } catch (err) { out.error = svcErrorText(err) }
  return out
}
function linkStatusSafe() {
  const out = { running: false, port: linkPort(), error: '', devices: [], pairs: [], lateResults: 0 }
  try {
    if (_link) {
      // status() 会惰性加载 link-pairs.json：重启后不用先 start 也能列出已配对手机
      const s = _link.status() || {}
      out.running = !!s.running
      if (s.port) out.port = s.port
      out.error = s.error ? String(s.error) : ''
      out.devices = Array.isArray(s.devices) ? s.devices.map((d) => ({
        id: String((d && d.id) || ''), name: String((d && d.name) || ''),
        online: !!(d && d.online), lastSeen: (d && d.lastSeen) || 0,
        caps: (d && Array.isArray(d.caps)) ? d.caps.slice() : [],
        // apps 只有别名与显示名；手机上到底放开了哪些由手机端 LinkSettingsActivity 决定，桌面不猜
        apps: (d && Array.isArray(d.apps)) ? d.apps.map((a) => ({ alias: String((a && a.alias) || ''), name: String((a && a.name) || '') })) : [],
        model: (d && d.model) ? String(d.model) : '', queued: Number((d && d.queued) || 0)
      })) : []
      out.pairs = Array.isArray(s.pairs) ? s.pairs.map((p) => ({
        id: String((p && p.id) || ''), name: String((p && p.name) || ''), createdAt: (p && p.createdAt) || 0
      })) : []
      out.lateResults = Array.isArray(s.lateResults) ? s.lateResults.length : Number(s.lateResults || 0)
    }
  } catch (err) { out.error = svcErrorText(err) }
  return out
}

function mcpStatusPayload() {
  const hub = hubStatusSafe()
  let clients = [], clientsError = ''
  try { clients = mcpAuthGet().list().map(safeClient) } catch (err) { clientsError = svcErrorText(err) }
  return {
    ok: true, enabled: !!(orbState.mcp && orbState.mcp.enabled), running: hub.running, port: hub.port, error: hub.error,
    clients: clients, clientsError: clientsError, roots: mcpRootsEffective(),
    rootsCustom: !!(orbState.mcp && Array.isArray(orbState.mcp.roots) && orbState.mcp.roots.length),
    handoutsDir: handoutsDir(), version: app.getVersion(), scopes: MCP_SCOPES.slice(),
    link: linkStatusSafe()
  }
}

/** T9 本机确认框：真实路径与客户端名必须显示；模型给的理由只作附加说明（标明未核实）。 */
async function confirmDesktopFile(o) {
  const opt = o || {}
  const client = opt.client || {}
  const file = String(opt.path || '')
  const reason = String(opt.reason || '').slice(0, 400)
  const parent = (settingsWin && !settingsWin.isDestroyed()) ? settingsWin
    : (orbWin && !orbWin.isDestroyed()) ? orbWin : null
  if (!file) return false
  if (!parent) { modeLog('桌面打开文件被拒绝：没有可用的确认窗口'); return false }
  const ac = new AbortController()
  const timer = setTimeout(() => { try { ac.abort() } catch {} }, DESKTOP_CONFIRM_MS)
  const onAbort = () => { try { ac.abort() } catch {} }
  let detach = () => {}
  try {
    if (opt.signal) {
      if (opt.signal.aborted) onAbort()
      else {
        opt.signal.addEventListener('abort', onAbort, { once: true })
        detach = () => { try { opt.signal.removeEventListener('abort', onAbort) } catch {} }
      }
    }
  } catch {}
  let allowed = false
  try {
    const r = await dialog.showMessageBox(parent, {
      type: 'warning',
      title: '外部 Agent 请求打开文件',
      message: '「' + (client.name || client.id || '外部客户端') + '」请求用本机程序打开一个文件',
      detail: '客户端：' + (client.name || '未知') + (client.id ? '（' + client.id + '）' : '') +
        '\n路径：' + file +
        (reason ? '\n客户端给的理由（未核实，只作参考）：' + reason : '') +
        '\n\n30 秒内不选就按拒绝处理；只有你点「允许打开」才会打开。',
      buttons: ['拒绝', '允许打开'],
      defaultId: 0, cancelId: 0, noLink: true,
      signal: ac.signal
    })
    allowed = !!(r && r.response === 1)
  } catch (err) {
    // 超时 abort / 窗口被关：一律按拒绝处理，绝不猜「用户大概同意」
    allowed = false
    modeLog('桌面打开文件确认未完成（按拒绝处理）：' + svcErrorText(err))
  } finally {
    clearTimeout(timer)
    detach()
  }
  modeLog('桌面打开文件：' + (allowed ? '允许' : '拒绝') + ' client=' + (client.id || '?') + ' path=' + file)
  return allowed
}
/** 打开真实文件：返回错误字符串（空串 = 成功）。 */
async function openDesktopFile(p) {
  const file = String(p || '')
  if (!file) return '路径为空'
  try { return String((await shell.openPath(file)) || '') } catch (err) { return svcErrorText(err) }
}

/* ---- 配置片段：token 只放占位符 —— 真实 token 只在签发那一次让用户复制 ---- */
function mcpConfigSnippet(name) {
  const slug = String(name || 'ling').replace(/[^\w.-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 32) || 'ling'
  const port = mcpPort()
  const bridge = path.join(__dirname, 'ling-mcp-bridge.js')
  const httpCfg = { mcpServers: {} }
  httpCfg.mcpServers[slug] = { url: 'http://127.0.0.1:' + port + '/mcp', headers: { Authorization: 'Bearer ${LING_MCP_TOKEN}' } }
  const stdioCfg = { mcpServers: {} }
  stdioCfg.mcpServers[slug] = { command: process.execPath, args: [bridge], env: { ELECTRON_RUN_AS_NODE: '1', LING_MCP_TOKEN: '${LING_MCP_TOKEN}' } }
  return [
    '# 方式一：Streamable HTTP（推荐，客户端直接连本机端口）',
    JSON.stringify(httpCfg, null, 2),
    '',
    '# 方式二：stdio 桥接（客户端只能拉起子进程时用）',
    JSON.stringify(stdioCfg, null, 2),
    '',
    '# 环境变量：LING_MCP_TOKEN = 刚才签发的那串 token（只显示一次，丢了就撤销重建）',
    '# 端口不是 ' + port + ' 时再加 LING_MCP_URL=http://127.0.0.1:' + port + '/mcp'
  ].join('\n')
}

ipcMain.handle('mcp:status', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try { return mcpStatusPayload() } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('mcp:set-enabled', async (e, on) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const want = on === true
  orbState.mcp = Object.assign({ enabled: false, port: MCP_DEFAULT_PORT, roots: [] }, orbState.mcp || {}, { enabled: want })
  if (want) {
    const r = await mcpHubStart()
    if (!r.ok) {
      // 打不开就如实回到「关」，不留假的已开启状态
      orbState.mcp.enabled = false
      saveState()
      return Object.assign(mcpStatusPayload(), { ok: false, error: r.error })
    }
  } else {
    await mcpHubStop()
  }
  saveState()
  return mcpStatusPayload()
})

ipcMain.handle('mcp:set-port', async (e, p) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const next = portOk(p)
  if (!next) return { ok: false, error: '端口要在 1024–65535 之间' }
  const prevPort = mcpPort()
  if (next === prevPort) return mcpStatusPayload()
  const wasRunning = hubStatusSafe().running
  if (wasRunning) { await mcpHubStop(); _mcpHub = null }
  orbState.mcp = Object.assign({ enabled: false, roots: [] }, orbState.mcp || {}, { port: next })
  saveState()
  if (!wasRunning) return mcpStatusPayload()
  const r = await mcpHubStart()
  if (r.ok) return mcpStatusPayload()
  // 新端口用不了：改回原端口并如实说明，绝不悄悄换第三个端口
  await mcpHubStop(); _mcpHub = null
  orbState.mcp.port = prevPort
  saveState()
  const back = await mcpHubStart()
  return Object.assign(mcpStatusPayload(), {
    ok: false,
    error: '端口 ' + next + ' 用不了（' + r.error + '），已改回 ' + prevPort + (back.ok ? '' : '；原端口也没起来：' + back.error)
  })
})

ipcMain.handle('mcp:client-create', (e, o) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const opt = o || {}
  const name = String(opt.name || '').trim().slice(0, 40)
  if (!name) return { ok: false, error: '先给客户端起个名字（比如 claude-desktop）' }
  // 默认只有 read；write / act / personal 必须在界面上显式勾选后才进 scope
  const picked = Array.isArray(opt.scopes) ? opt.scopes.filter((s) => MCP_SCOPES.includes(s)) : []
  const scopes = Array.from(new Set(['read'].concat(picked)))
  const tools = Array.isArray(opt.tools) ? opt.tools.filter((t) => typeof t === 'string' && t).slice(0, 40) : undefined
  try {
    const r = mcpAuthGet().create({ name, scopes, tools })
    if (!r || !r.client || !r.token) return { ok: false, error: '签发失败（模块没有返回 token）' }
    // 审计只保留白名单字段：scope 是字符串，scopes 数组会被丢掉
    try { mcpAuditGet().record({ event: 'client_created', client: r.client.id, clientName: r.client.name, scope: scopes.join(',') }) } catch {}
    // token 只在这一刻回给设置页；之后任何 IPC 都不会再给
    return { ok: true, client: safeClient(r.client), token: String(r.token), snippet: mcpConfigSnippet(name), port: mcpPort() }
  } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('mcp:client-revoke', async (e, id) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const cid = String(id || '')
  if (!cid) return { ok: false, error: '缺少客户端 id' }
  let r = { ok: true }
  let existed = false
  try { existed = mcpAuthGet().revoke(cid) === true } catch (err) { r = { ok: false, error: svcErrorText(err) } }
  if (r.ok && !existed) return { ok: false, error: '没有这个客户端（可能已经撤销过了）' }
  // 撤销后立刻取消这个客户端还挂在手机上的指令（否则手机可能继续执行已撤销客户端的命令）
  let cancelled = 0
  try { cancelled = Number(linkFacade.cancelClient(cid)) || 0 } catch {}
  // 限流桶里还留着这个客户端的分桶计数：撤销后一并清掉，别让未来的同名 id 撞上旧桶
  try { if (_mcpHub && typeof _mcpHub.forget === 'function') _mcpHub.forget(cid) } catch {}
  try { mcpAuditGet().record({ event: 'client_revoked', client: cid, cancelled: cancelled }) } catch {}
  return Object.assign({ cancelled: cancelled }, r, r.ok ? mcpStatusPayload() : {})
})

ipcMain.handle('mcp:roots-add', async (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const parent = (settingsWin && !settingsWin.isDestroyed()) ? settingsWin : undefined
  try {
    const r = await dialog.showOpenDialog(parent, { title: '选择允许外部 Agent 打开的目录', properties: ['openDirectory', 'multiSelections', 'createDirectory'] })
    if (!r || r.canceled || !Array.isArray(r.filePaths) || !r.filePaths.length) return Object.assign(mcpStatusPayload(), { cancelled: true })
    const cur = (orbState.mcp && Array.isArray(orbState.mcp.roots)) ? orbState.mcp.roots.slice() : []
    const next = Array.from(new Set(cur.concat(r.filePaths.map(String))))
    orbState.mcp = Object.assign({ enabled: false, port: MCP_DEFAULT_PORT }, orbState.mcp || {}, { roots: next })
    saveState()
    return mcpStatusPayload()
  } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('mcp:roots-remove', (e, p) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const target = String(p || '')
  const cur = (orbState.mcp && Array.isArray(orbState.mcp.roots)) ? orbState.mcp.roots.slice() : []
  orbState.mcp = Object.assign({ enabled: false, port: MCP_DEFAULT_PORT }, orbState.mcp || {}, { roots: cur.filter((x) => x !== target) })
  saveState()
  return mcpStatusPayload()
})

ipcMain.handle('mcp:roots-reset', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  orbState.mcp = Object.assign({ enabled: false, port: MCP_DEFAULT_PORT }, orbState.mcp || {}, { roots: [] })
  saveState()
  return mcpStatusPayload()
})

ipcMain.handle('mcp:open-handouts', async (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const dir = handoutsDir()
  try { stateFs.mkdirSync(dir, { recursive: true, mode: 0o700 }) } catch {}
  const err = await openDesktopFile(dir)
  return err ? { ok: false, error: err } : { ok: true, path: dir }
})

ipcMain.handle('mcp:audit-list', (e, limit) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const n = Math.min(200, Math.max(1, Number(limit) || 20))
  try { return { ok: true, events: mcpAuditGet().list(n) } } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('mcp:audit-export', async (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const parent = (settingsWin && !settingsWin.isDestroyed()) ? settingsWin : undefined
  let text = ''
  try { text = String(mcpAuditGet().export() || '') } catch (err) { return { ok: false, error: svcErrorText(err) } }
  const d = new Date()
  const stamp = d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0') + '-' + String(d.getHours()).padStart(2, '0') + String(d.getMinutes()).padStart(2, '0')
  let def = path.join(app.getPath('userData'), 'ling-mcp-audit-' + stamp + '.jsonl')
  try { def = path.join(app.getPath('downloads'), 'ling-mcp-audit-' + stamp + '.jsonl') } catch {}
  try {
    const r = await dialog.showSaveDialog(parent, { title: '导出 MCP 调用审计', defaultPath: def, filters: [{ name: 'JSON Lines', extensions: ['jsonl'] }] })
    if (!r || r.canceled || !r.filePath) return { ok: false, cancelled: true }
    stateFs.writeFileSync(r.filePath, text, { mode: 0o600 })
    return { ok: true, path: r.filePath, bytes: Buffer.byteLength(text) }
  } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('mcp:audit-copy', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try { const t = String(mcpAuditGet().export() || ''); clipboard.writeText(t); return { ok: true, bytes: Buffer.byteLength(t) } }
  catch (err) { return { ok: false, error: svcErrorText(err) } }
})

/** 回滚：只撤销「带这个客户端来源、且用户没改过」的写入。先本机二次确认，措辞写清不会误删。 */
ipcMain.handle('mcp:rollback', async (e, clientId) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const cid = String(clientId || '')
  if (!cid) return { ok: false, error: '缺少客户端 id' }
  let client = null
  try { client = mcpAuthGet().list().find((c) => c && c.id === cid) || null } catch {}
  const label = client ? (client.name || cid) : cid
  const parent = (settingsWin && !settingsWin.isDestroyed()) ? settingsWin : null
  if (!parent) return { ok: false, error: '没有可用的确认窗口' }
  let allowed = false
  try {
    const r = await dialog.showMessageBox(parent, {
      type: 'warning',
      title: '撤销外部客户端的写入',
      message: '撤销「' + label + '」通过 MCP 写进来的内容？',
      detail: '只撤销带该客户端来源、并且之后你没改过的条目（知识卡 / 记忆 / 讲义）。\n你手动编辑过的条目、自己新建的条目都不会被删。\n手动开启的提醒不会自动取消。',
      buttons: ['取消', '确认撤销'],
      defaultId: 0, cancelId: 0, noLink: true
    })
    allowed = !!(r && r.response === 1)
  } catch (err) { modeLog('回滚确认失败（按取消处理）：' + svcErrorText(err)) }
  if (!allowed) return { ok: false, cancelled: true }
  try {
    const r = await mcpToolsGet().rollback(cid)
    pushKcardMenuBadge()
    try { mcpAuditGet().record({ event: 'rollback', client: cid, result: r || null }) } catch {}
    // tools 自己的 ok 才是真实结论（没有可撤销的写入时 ok=false），别用外层 true 盖掉
    return Object.assign({ ok: true }, r || {})
  } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

/* ---- 手机常连（持久配对） ---- */
ipcMain.handle('link:status', (e) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  try { return Object.assign({ ok: true, enabled: !!(orbState.link && orbState.link.enabled) }, linkStatusSafe()) }
  catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('link:set-enabled', async (e, on) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const want = on === true
  orbState.link = Object.assign({ enabled: false, port: LINK_DEFAULT_PORT }, orbState.link || {}, { enabled: want })
  if (want) {
    const r = await linkStart()
    if (!r.ok) {
      orbState.link.enabled = false
      saveState()
      return Object.assign({ ok: false, error: r.error, enabled: false }, linkStatusSafe())
    }
  } else await linkStop()
  saveState()
  return Object.assign({ ok: true, enabled: want }, linkStatusSafe())
})

ipcMain.handle('link:set-port', async (e, p) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const next = portOk(p)
  if (!next) return { ok: false, error: '端口要在 1024–65535 之间' }
  const prevPort = linkPort()
  if (next === prevPort) return Object.assign({ ok: true }, linkStatusSafe())
  const wasRunning = linkStatusSafe().running
  if (wasRunning) { await linkStop(); _link = null }
  orbState.link = Object.assign({ enabled: false }, orbState.link || {}, { port: next })
  saveState()
  if (!wasRunning) return Object.assign({ ok: true }, linkStatusSafe())
  const r = await linkStart()
  if (r.ok) return Object.assign({ ok: true }, linkStatusSafe())
  await linkStop(); _link = null
  orbState.link.port = prevPort
  saveState()
  const back = await linkStart()
  return Object.assign({ ok: false }, linkStatusSafe(), {
    error: '端口 ' + next + ' 用不了（' + r.error + '），已改回 ' + prevPort + (back.ok ? '' : '；原端口也没起来：' + back.error)
  })
})

ipcMain.handle('link:pair-create', (e, o) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  if (!linkStatusSafe().running) return { ok: false, error: '手机常连还没开启 —— 先点「开启」再配对' }
  try {
    const r = lingLinkGet().createPair({ name: String((o && o.name) || '').trim().slice(0, 24) })
    if (!r || !r.id || !Array.isArray(r.uris) || !r.uris.length) return { ok: false, error: '配对生成失败（没有可用的本机地址）' }
    // uris 只在这次回给设置页画二维码 / 复制，绝不写审计、也不留在任何状态里
    return { ok: true, pair: { id: String(r.id), name: String(r.name || ''), uris: r.uris.map(String) }, status: linkStatusSafe() }
  } catch (err) { return { ok: false, error: svcErrorText(err) } }
})

ipcMain.handle('link:pair-revoke', (e, id) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const pid = String(id || '')
  if (!pid) return { ok: false, error: '缺少配对 id' }
  try {
    const r = lingLinkGet().revoke(pid)
    // 落盘失败时 Link 返回 {ok:false}，配对**仍然有效** —— 必须如实报错，不能提示「已撤销」
    if (!r || r.ok !== true) {
      // 只透传 Link 自己的文案（它已写明「配对仍然有效」这类真实状态），不替换成固定句式
      const why = String((r && r.error) || '撤销失败（Link 没有给出原因）')
      try { mcpAuditGet().record({ event: 'pair_revoke_failed', pair: pid, error: why }) } catch {}
      return { ok: false, error: why, status: linkStatusSafe() }
    }
    try { mcpAuditGet().record({ event: 'pair_revoked', pair: pid }) } catch {}
    return { ok: true, status: linkStatusSafe() }
  } catch (err) { return { ok: false, error: svcErrorText(err), status: linkStatusSafe() } }
})

async function runTranslate(text, fresh) {
  if (isTranslating) return
  isTranslating = true
  transDismissed = false
  try { await translateText(text, fresh) }
  catch (e) { pushTrans({ text: '出错：' + errText(e), done: true }) }
  finally { isTranslating = false }
}

// v1.47：spawnSync → spawnAsync。osascript 走 System Events 暖态 20~50ms、冷启动能到几百 ms，同步等期间小球拖不动
// v1.50：Windows 用 WScript.Shell.SendKeys 发 Ctrl+C（wscript 起得比 PowerShell 快得多，暖态约 50~100ms）
let copyVbs = null
async function pressCopyKey() {
  if (process.platform === 'win32') {
    if (!copyVbs) { copyVbs = path.join(DEBUG_DIR, 'lingzhu-copy.vbs'); stateFs.writeFileSync(copyVbs, 'CreateObject("WScript.Shell").SendKeys "^c"\r\n') }
    const r = await spawnAsync('cscript', ['//nologo', '//B', copyVbs], 4000)
    return { ok: r.code === 0, err: errText(r.err || '') }
  }
  const r = await spawnAsync('osascript', ['-e', 'tell application "System Events" to keystroke "c" using command down'], 4000)
  return { ok: r.code === 0, err: errText(r.err || '') }
}

async function readSelection() {
  // v1.30：划词靠 osascript 模拟 ⌘C，只有 macOS 有这条路。其它平台给出可用的替代指引。
  if (process.platform !== 'darwin' && process.platform !== 'win32') {
    return { special: '# Linux 版暂不支持划词\n\n划词翻译靠模拟复制键，Linux 上还没有对应实现。\n\n替代方案：选中文字后按 Ctrl+C 复制，「复制即译」开着时会自动翻译。' }
  }
  const saved = clipboard.readText()
  if (!accessibilityOK(false)) return { needPerm: true }
  const { ok, err } = await pressCopyKey()
  if (!ok) {
    transLog('模拟 ⌘C 失败：' + err)
    return /assistive|not allowed|-1743|1002/i.test(err) ? { needPerm: true } : { failed: err }
  }
  let text = ''
  for (let i = 0; i < 12; i++) {
    await sleep(40)
    text = clipboard.readText()
    if (text && text !== saved) break
  }
  if (!text || text === saved) return { empty: true }
  clipIgnoreUntil = Date.now() + 1500
  try { clipboard.writeText(saved) } catch {}
  return { text }
}

async function translateSelection() {
  if (isTranslating) return
  isTranslating = true
  transDismissed = false
  let handed = false
  try {
    // v1.24：这一行改成「面板已开才更新」。老代码是 setTransState('reading')，
    // 而 setTransState 会 placeTranslate() —— 也就是还没确认选区就先把面板弹出来了。
    transSendIfOpen('trans:state', 'reading')
    const r = await readSelection()
    if (r.special) {
      // v1.30：非 macOS 平台的替代指引 —— 建面板显示这段话（用户主动按了热键，弹出是有预期的）
      handed = true
      setTransState('reading')
      pushTrans({ text: r.special, done: true })
    } else if (r.needPerm) {
      setTransState('need_perm')
      accessibilityOK(true)
      handed = true
      pushTrans({ text: '# 需要「辅助功能」权限\n\n划词翻译要模拟一次 ⌘C 才能读到你的选区，这需要「辅助功能」权限。\n\n1. 点上方那条提示打开系统设置\n2. 在「隐私与安全性 → 辅助功能」里打开 **' + APP_NAME + '**\n3. 重启' + APP_NAME + '后再试\n\n不想开权限也能用：右键小球 → 打开「复制即译」，之后选中文本按 ⌘C 就会自动翻译（不需要任何权限）。', done: true })
    } else if (r.empty) {
      // 没读到选区：不建面板、也不发空 chunk。
      // （老代码发的 pushTrans({text:'',done:true}) 会让渲染层把状态行 display:none，
      //   于是屏幕上只剩一个纯空框，还要等 10 分钟闲置回收才消失。）
      if (transWin && !transWin.isDestroyed()) transSendIfOpen('trans:state', 'no_selection')
      transLog('没读到选区，未弹面板')
    } else {
      handed = true
      await translateText(r.text)
    }
  } catch (e) {
    pushTrans({ text: '出错：' + errText(e), done: true })
  } finally {
    isTranslating = false
  }
  return handed
}

function looksTranslatable(s) {
  const t = String(s || '').trim()
  if (t.length < 2 || t.length > 2000) return false
  if (/:\/\/|^[/~]|@\w+\.\w+/.test(t)) return false            // URL / 路径 / 邮箱 → 不翻
  if (/[{}<>;]|^\s*(npm|git|cd|ls|curl|sudo|brew)\s/.test(t)) return false  // 代码 / 命令 → 不翻
  if (!/[A-Za-z\u00c0-\u024f\u0370-\u03ff\u0400-\u04ff\u3040-\u30ff\uac00-\ud7af]/.test(t)) return false
  const cjk = (t.match(/[\u4e00-\u9fff]/g) || []).length
  if (cjk / t.length >= 0.5) return false                       // 中文占一半以上 → 不翻
  if (!/\s/.test(t) && t.length < 4) return false               // 太短的单词不翻
  return true
}

function clipTick() {
  if (Date.now() < clipIgnoreUntil) return
  let t = ''
  try { t = clipboard.readText() } catch { return }
  if (t === lastClipText) return
  lastClipText = t
  if (!looksTranslatable(t)) return
  transLog('复制即译触发，' + t.length + ' 字')
  runTranslate(t)
}

function setCopyMode(on) {
  translateOnCopy = !!on
  if (translateOnCopy) {
    if (!clipWatchTimer) {
      try { lastClipText = clipboard.readText() } catch { lastClipText = '' }
      clipWatchTimer = setInterval(clipTick, CLIP_WATCH_MS)
      transLog('复制即译：开')
    }
  } else if (clipWatchTimer) {
    clearInterval(clipWatchTimer)
    clipWatchTimer = null
    transLog('复制即译：关')
  }
  saveState()
  if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('trans:copyMode', translateOnCopy)
}

function setTransLang(id) {
  if (!LANGS.some((l) => l.id === id) || id === transLang) return
  transLang = id
  orbState.transLang = id
  saveState()
  transLog('翻译语言 → ' + langInfo(id).label)
  pushTransLang()
  // v1.24：只有翻译面板还开着才就地重译。老代码是「本次会话翻译过东西就重译」，
  // 于是在右键菜单里点一下「翻译语言」（只想换个设置），已经关掉的面板会自己弹回来。
  if (lastTransSrc && transWin && !transWin.isDestroyed()) runTranslate(lastTransSrc)
}

function pushTransLang() {
  const info = { id: transLang, label: langInfo(transLang).label, langs: LANGS.map((l) => ({ id: l.id, label: l.label, tip: l.tip })) }
  if (transWin && !transWin.isDestroyed()) { try { transWin.webContents.send('trans:lang', info) } catch {} }
  if (menuWin && !menuWin.isDestroyed()) { try { menuWin.webContents.send('menu:lang', info) } catch {} }
  return info
}

/* 面板闲置 10 分钟自动销毁：渲染进程 (~30MB) 不常驻。识别/翻译进行中不回收。 */
const PANEL_IDLE_MS = 10 * 60 * 1000
let panelIdleTimer = null
function armPanelIdle() {
  clearTimeout(panelIdleTimer)
  panelIdleTimer = setTimeout(() => {
    try {
      if (!isSolving && !isTranslating) {
        if (answerWin && !answerWin.isDestroyed()) answerWin.close()
        if (transWin && !transWin.isDestroyed()) transWin.close()
      }
    } catch {}
    armPanelIdle()
  }, PANEL_IDLE_MS)
}

function openAccessibilityPane() {
  shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility')
}

function accelLabel(a) {
  // v1.50：Windows / Linux 上显示成 Ctrl+Alt+/ 这种写法，⌘⌥ 符号只给 macOS
  if (process.platform !== 'darwin') {
    return String(a).replace(/CommandOrControl|Control|Ctrl/g, 'Ctrl').replace(/Command|Cmd|Super|Meta/g, 'Win').replace(/Option/g, 'Alt')
  }
  return String(a)
    .replace(/CommandOrControl|Command|Cmd|Super/g, '⌘')
    .replace(/Control|Ctrl/g, '⌃')
    .replace(/Alt|Option/g, '⌥')
    .replace(/Shift/g, '⇧')
    .replace(/\+/g, '')
}

function registerTranslateHotkey() {
  const candidates = hotkeyCandidates()
  for (const acc of candidates) {
    try {
      const ok = globalShortcut.register(acc, () => { translateSelection() })
      if (ok) {
        hotkeyLabel = accelLabel(acc)
        hotkeyRaw = acc
        transLog('热键 ' + acc + ' 注册成功（显示为 ' + hotkeyLabel + '）' + (acc === candidates[0] ? '' : '，首选被占用已降级'))
        return true
      }
      transLog('热键 ' + acc + ' 注册失败（被其它应用占用或语法不支持），试下一个')
    } catch (e) {
      transLog('热键 ' + acc + ' 异常 ' + errText(e))
    }
  }
  // 全挂了就不要显示一个按了没反应的假键位
  hotkeyLabel = '未注册'
  hotkeyRaw = ''
  transLog('所有热键候选都注册失败，划词翻译仍可用右键菜单 / 复制即译')
  return false
}

/* ---------------- 右键弹出菜单（v1.18：改成独立窗口） ----------------
 * 旧写法把菜单 DOM 放在 52×52 的小球窗口里，body 又是 overflow:hidden，
 * 菜单被整体裁掉、永远看不见。现在改为一个独立的弹出窗口，失焦自动关闭。
 */
const MENU_W = 236
let menuWin = null
let menuFocused = false
let dimEnabled = true

/* v1.48：菜单窗口预热 + 复用
 * 以前每次右键都 closeMenu + new BrowserWindow：窗口 ~40ms 就出现，内容 ~150~250ms 后才画上，
 * 画上之后又按 214 → 403 → 550 连跳两次高度（先按空菜单 fit，再等 menu:info 回来重排）。
 * 现在：鼠标一悬到小球上就在后台建好（隐藏），右键时只发 menu:open 让页面刷新数据、量好高度，
 * 回 menu:ready 才按最终高度摆位、显示、淡入；关闭改成淡出后 hide，下次直接复用。
 * 闲置 10 分钟销毁（渲染进程不常驻，跟面板同一个原则）。 */
const MENU_IDLE_MS = 10 * 60 * 1000
let menuPending = false, menuPendingTimer = null, menuIdleTimer = null

function ensureMenuWin() {
  if (menuWin && !menuWin.isDestroyed()) return menuWin
  const mw = menuWin = new BrowserWindow({
    width: MENU_W, height: 214, x: 0, y: 0, show: false,
    frame: false, transparent: true, hasShadow: false, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 菜单',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(mw, LAYER_POPUP)
  hideFromMissionControl(mw)
  mw.loadFile(path.join(__dirname, 'menu.html'))
  mw.webContents.on('did-finish-load', () => winReady(mw))
  mw.on('focus', () => { if (menuWin === mw) menuFocused = true })
  mw.on('blur', () => { if (menuWin === mw && menuFocused) closeMenu() })
  mw.on('closed', () => { if (menuWin === mw) { menuWin = null; menuFocused = false; menuPending = false } })
  armMenuIdle()
  return mw
}
function armMenuIdle() {
  clearTimeout(menuIdleTimer)
  menuIdleTimer = setTimeout(() => {
    if (menuWin && !menuWin.isDestroyed() && !menuWin.isVisible() && !menuPending) menuWin.close()
    else if (menuWin) armMenuIdle()
  }, MENU_IDLE_MS)
}

function closeMenu() {
  menuPending = false
  clearTimeout(menuPendingTimer)
  menuFocused = false
  fadeClose(menuWin, true)
}

function showMenu() {
  if (!orbWin || orbWin.isDestroyed()) return
  const mw = ensureMenuWin()
  cancelClose(mw)
  if (mw.isVisible() && !mw.__closing) { mw.focus(); return }
  menuPending = true
  sendToWin(mw, 'menu:open')
  // 兜底：页面没回 ready（加载异常）也别让右键没反应
  clearTimeout(menuPendingTimer)
  menuPendingTimer = setTimeout(() => presentMenu(0), 400)
  armMenuIdle()
}

// 按最终高度摆位：小球下方放不下就翻到上方
function placeMenu(h) {
  const b = orbWin.getBounds()
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  const H = Math.max(120, Math.min(Number(h) || menuWin.getBounds().height, 620))
  let x = b.x + b.width - MENU_W + 6
  let y = b.y + b.height + 4
  if (x < wa.x + 8) x = wa.x + 8
  if (x + MENU_W > wa.x + wa.width - 8) x = wa.x + wa.width - MENU_W - 8
  if (y + H > wa.y + wa.height - 8) y = b.y - H - 4
  if (y < wa.y + 8) y = wa.y + 8
  menuWin.setBounds({ x, y, width: MENU_W, height: H })
}

function presentMenu(h) {
  if (!menuPending || !menuWin || menuWin.isDestroyed() || !orbWin || orbWin.isDestroyed()) return
  menuPending = false
  clearTimeout(menuPendingTimer)
  placeMenu(h)
  revealWin(menuWin, true)
  menuWin.moveTop()
}

/* ---------------- 自动扫描确认卡（v1.25） ----------------
 * 以前双击小球直接开关自动扫描。但小球只有 52px，「点了没反应又点一下」就会误开，
 * 而自动扫描会每 20 秒弹一次答案面板、状态还落盘 —— 事后根本想不到是它。
 * 现在：双击 → 小球旁边浮一张卡片，点「开启」才真的开；
 *      取消 / 失焦 / 10 秒不理它 → 自动关掉，什么都不改。
 * 关闭方向不设确认（安全方向不拦），右键菜单里的开关也不设确认（那是明确点选的）。
 */
const SCAN_CARD_W = 252
const SCAN_CARD_MAX_H = 200
const SCAN_CARD_TTL_MS = 10000
let scanWin = null
let scanFocused = false
let scanTimer = null

function closeScanConfirm() {
  if (scanTimer) { clearTimeout(scanTimer); scanTimer = null }
  fadeClose(scanWin)
  scanWin = null
  scanFocused = false
}

function showScanConfirm() {
  /* v1.46 起这个方法不再有调用方：自动扫描改由「三击小球」或右键菜单直接开关，
   * 都不再过确认卡。保留实现是为了万一想恢复「误触保护」时不用重写（scan.html 也还在包里）。 */
  if (scanWin && !scanWin.isDestroyed()) { scanWin.focus(); return }   // 已经开着就别重复弹
  if (!orbWin || orbWin.isDestroyed()) return
  const b = orbWin.getBounds()
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  const h = 130
  let x = b.x + b.width - SCAN_CARD_W + 6
  let y = b.y + b.height + 6
  if (x < wa.x + 8) x = wa.x + 8
  if (x + SCAN_CARD_W > wa.x + wa.width - 8) x = wa.x + wa.width - SCAN_CARD_W - 8
  if (y + h > wa.y + wa.height - 8) y = b.y - h - 6
  if (y < wa.y + 8) y = wa.y + 8
  scanWin = new BrowserWindow({
    width: SCAN_CARD_W, height: h, x, y,
    frame: false, transparent: true, hasShadow: false, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 自动扫描确认', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(scanWin, LAYER_POPUP)
  hideFromMissionControl(scanWin)
  scanWin.loadFile(path.join(__dirname, 'scan.html'))
  const sw = scanWin
  scanWin.on('focus', () => { scanFocused = true })
  scanWin.on('blur', () => { if (scanFocused && scanWin === sw) closeScanConfirm() })
  scanWin.on('closed', () => { if (scanWin === sw) { scanWin = null; scanFocused = false } })
  revealOnReady(scanWin, true)
  scanTimer = setTimeout(() => { modeLog('自动扫描确认卡：10 秒没响应，自动取消'); closeScanConfirm() }, SCAN_CARD_TTL_MS)
  modeLog('双击小球 → 弹自动扫描确认卡')
}

/* ---------------- 追问输入卡（v1.26） ----------------
 * 答案面板是 focusable:false 的纯 HUD（刻意不抢焦点），所以没法在面板里直接打字。
 * 这里沿用「菜单 / 确认卡」那一套：小卡浮出来、抢焦点、回车发送、Esc 或失焦就关。
 */
const ASK_CARD_W = 460
const ASK_CARD_H = 140   // v1.51：多了一行「附带新截图」勾选框（实测内容 137px）
let askWin = null
let askFocused = false

function closeAskCard() {
  fadeClose(askWin)
  askWin = null
  askFocused = false
}

function showAskCard() {
  if (askWin && !askWin.isDestroyed()) { askWin.focus(); return }
  const W = ASK_CARD_W, H = ASK_CARD_H
  let x, y, wa
  if (answerWin && !answerWin.isDestroyed()) {
    const b = answerWin.getBounds()
    wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
    // v1.60：追问卡盖在面板底部的追问栏上（看起来就是在那条栏里打字）
    x = b.x
    y = b.y + b.height - H
  } else {
    const cur = screen.getCursorScreenPoint()
    wa = (screen.getDisplayNearestPoint(cur) || screen.getPrimaryDisplay()).workArea
    x = Math.round(cur.x - W / 2)
    y = Math.round(cur.y + 16)
  }
  x = Math.min(Math.max(x, wa.x + 8), wa.x + wa.width - W - 8)
  y = Math.min(Math.max(y, wa.y + 8), wa.y + wa.height - H - 8)
  askWin = new BrowserWindow({
    width: W, height: H, x, y,
    frame: false, transparent: true, hasShadow: false, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 追问', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(askWin, LAYER_POPUP)
  hideFromMissionControl(askWin)
  askWin.loadFile(path.join(__dirname, 'ask.html'))
  const kw = askWin
  askWin.on('focus', () => { askFocused = true })
  askWin.on('blur', () => { if (askFocused && askWin === kw) closeAskCard() })
  askWin.on('closed', () => { if (askWin === kw) { askWin = null; askFocused = false } })
  revealOnReady(askWin, true)
  modeLog('打开追问输入卡')
}

function resetOrb() {
  if (!orbWin || orbWin.isDestroyed()) return
  const { x, y } = orbDefaultPos()
  orbWin.setBounds({ x, y, width: ORB_WIN, height: ORB_WIN })
  orbToFront()
  // 新手卡开着就跟着球一起挪；没开且从没看过引导才补弹（老用户复位不再被引导卡打扰）
  if (hintWin && !hintWin.isDestroyed()) placeHint(hintWin.getBounds().height)
  else if (!orbState.hintShown) showHint()
  saveState()
}

function imgParts(b64s) {
  return (b64s || []).map(b => ({ type: 'image_url', image_url: { url: 'data:image/jpeg;base64,' + b } }))
}

// v1.49：「详细」「纯小白」正文会长很多，思考又和正文共用预算 —— 放宽一半，免得写到一半被截断
function answerBudget(n) { return (examDetail() === 'detailed' || orbState.novice) ? Math.round(n * 1.5) : n }
function chatBody(model, b64s, noThinking) {
  return Object.assign({
    model,
    stream: true,
    max_tokens: answerBudget(4096),          // v1.43：思维链与正文共用这份预算 —— 1024 会被思考吃光（实测思考 1135~1215 字）
    messages: [{
      role: 'user',
      content: [ { type: 'text', text: sysPrompt() } ].concat(imgParts(b64s))
    }]
  }, noThinking ? thinkingOff(apiCfg()) : thinkingOf(apiCfg()))
}

/* ---------------- 追问（v1.26） ----------------
 * 面板上的「问」按钮 → 弹一个小输入卡 → 回车把问题发上来。
 * 关键：把**原截图**和**之前的问答**一起带上，模型才能接着看图回答「为什么不是 C」这种追问。
 * conv 只存文本轮次，图和 system 提示每轮现拼。
 */
let lastB64s = null
let conv = []

// v1.51：最后一轮 user 消息由调用方给（可能带新截图）
function askBodyWith(model, userTurn, noThinking) {
  const msgs = [{ role: 'user', content: [ { type: 'text', text: sysPrompt() } ].concat(imgParts(lastB64s)) }]
  for (const t of conv) msgs.push({ role: t.role, content: t.content })
  msgs.push(userTurn)
  return Object.assign({ model, stream: true, max_tokens: answerBudget(3072), messages: msgs }, noThinking ? thinkingOff(apiCfg()) : thinkingOf(apiCfg()))
}
function askBody(model, q, noThinking) {
  const msgs = [{ role: 'user', content: [ { type: 'text', text: sysPrompt() } ].concat(imgParts(lastB64s)) }]
  for (const t of conv) msgs.push({ role: t.role, content: t.content })
  // v1.77：识别提示词已不含人格 / 讲法，追问这里只需不带关键词清单，不再重复贴风格
  msgs.push({ role: 'user', content: '只回答这一次的追问，不要重复之前的解析，也不要复述题干。' + (keywordsOn() ? '这次不用再列关键词清单。' : '') + '\n追问：' + q })
  return Object.assign({ model, stream: true, max_tokens: answerBudget(3072), messages: msgs }, noThinking ? thinkingOff(apiCfg()) : thinkingOf(apiCfg()))   // v1.42：追问也听「思考强度」设置
}

/* 流式请求的公共部分：模型降级、SSE 解析、逐段 pushAnswer。
 * stripNoQ：只有「首次识别」需要把模型偶尔吐出的 [无题目] 标记剥掉。
 * remember：是否记进历史 + 触发自动扫描的 60 秒暂停（追问不算新一轮识别）。 */
async function streamChat(bodyFor, { stripNoQ = false, remember = true } = {}) {
  const cfg = apiCfg()
  // aigw-omni 兜底是本地网关专属逻辑，自定义接口不做模型切换
  const models = [cfg.model]
  if (cfg.baseUrl === API_DEFAULTS.baseUrl && cfg.model !== 'aigw-omni') models.push('aigw-omni')
  let lastErr = null
  // v1.43：同一模型可以让它试两遍 —— 第二遍显式关思考。
  // 起因：用户遇到「思考了好几次，然后报『模型 deepseek-flash 未返回内容』」——
  // 思考模式里思维链和正文共用输出预算，想得太多就没正文了。第二遍关思考基本都能出。
  const variants = isDeepSeekCfg(cfg) ? [false, true] : [false]
  for (const model of models) {
   for (const noThinking of variants) {
    try {
      const ac = new AbortController()
      const to = setTimeout(() => ac.abort(), 90000)
      let res
      try {
        res = await fetch(cfg.baseUrl + '/chat/completions', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cfg.apiKey },
          body: JSON.stringify(bodyFor(model, noThinking)),
          signal: ac.signal
        })
      } finally { clearTimeout(to) }
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        let msg = '接口 ' + res.status + ' ' + text.slice(0, 160)
        if (res.status === 400 && /image|multimodal|vision|content/i.test(text)) msg += '\n\n（提示：这个接口/模型可能不支持图片输入，屏幕识别需要一个能看图的模型 —— DeepSeek 4.1 起已支持多模态，旧版只支持文本。）'
        lastErr = new Error(msg)
        continue
      }
      const switched = model !== models[0]
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      let got = false
      let head = ''
      let decided = false
      let pushedText = ''
      let thinkChars = 0, thinkAnnounced = false   // thinkChars 只留计数：判断「是不是思考吃光了预算」；面板只显示静态「正在思考…」
      if (switched) pushAnswer('（' + models[0] + ' 繁忙，已自动切换 aigw-omni）\n\n')
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let nl
        while ((nl = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, nl).trim()
          buf = buf.slice(nl + 1)
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (data === '[DONE]') continue
          try {
            const j = JSON.parse(data)
            const delta = j.choices && j.choices[0] && j.choices[0].delta
            const rc = (delta && delta.reasoning_content) || ''
            if (rc) {
              thinkChars += rc.length
              if (!thinkAnnounced) { thinkAnnounced = true; pushPhase('正在思考…') }
            }
            const text = (delta && delta.content) || ''
            if (text) {
              got = true
              if (!decided) {
                head += text
                if (!stripNoQ || head.trim().length >= 5) {
                  decided = true
                  if (stripNoQ && NO_Q_HEAD.test(head)) head = head.replace(NO_Q_HEAD, '')
                  if (head) { pushAnswer(head); pushedText += head }
                }
              } else {
                pushAnswer(text); pushedText += text
              }
            }
          } catch {}
        }
      }
      if (!got) {
        if (thinkChars > 0) {
          lastErr = new Error('模型 ' + model + ' 思考完没吐出正文（输出预算被思考吃光）—— 可在设置卡把「思考强度」调低或关掉')
          if (!noThinking) { pushAnswer('（思考完却没吐出正文，已关掉思考重试一次）\n\n'); continue }
        } else lastErr = new Error('模型 ' + model + ' 未返回内容')
      }
      else {
        if (remember && pushedText.trim()) rememberAnswer(pushedText, '')
        // v1.49：去掉「识别到内容就暂停扫描 60 秒方便阅读」—— 用户要自动扫描一直按间隔跑，不停留。
        // v1.36：整段被 [无题目] 标记剥掉（pushedText 为空）时补一条提示，
        // 否则面板会一直停在「正在识别分析…」不出来（自动扫描每一轮都这样）。
        if (stripNoQ && !pushedText.trim()) showAnswerState('no_question')
        pushAnswer('', true)
        return pushedText
      }
    } catch (e) {
      lastErr = e
    }
   }
  }
  throw lastErr || new Error('识别请求失败')
}

function streamAnswer(b64s) {
  return streamChat((m, noThinking) => chatBody(m, b64s, noThinking), { stripNoQ: true })
}

/* 追问复用同一个面板：正文是累加的，直接 push「分隔线 + 问题 + 流式回答」即可。
 * 注意不能走 showAnswerState —— 渲染层收到 state 会清空正文。 */
/* v1.51：追问可附带一张新截图（情况变了、画面翻页了）。
 * 截图前先把答案面板调成透明（追问卡已经在关），免得把自己截进去；新图挂在这一轮追问的 user 消息里，
 * 并留在 conv 里 —— 之后的追问也能看到它。额外截图最多留 3 张，再多就把最早那张换成一句「已省略」。 */
let askWithShot = false
const ASK_EXTRA_SHOTS_MAX = 3
async function grabForFollowUp() {
  await sleep(160)   // 等追问卡淡出（110ms）
  const w = answerWin && !answerWin.isDestroyed() ? answerWin : null
  const op = w ? w.getOpacity() : 1
  try {
    if (w) { w.setOpacity(0); await sleep(60) }
    return await takeScreensBase64()
  } finally {
    if (w && !w.isDestroyed()) w.setOpacity(op)
  }
}
function trimConvShots(keep) {
  const withImg = conv.filter((t) => Array.isArray(t.content))
  for (let i = 0; i < withImg.length - keep; i++) {
    const t = withImg[i]
    t.content = t.content.filter((p) => p.type === 'text').map((p) => p.text).join('\n') + '\n（这一轮附带的截图已省略）'
  }
}

async function askFollowUp(q, withShot, shown) {   // v1.60：shown = 面板上显示的那句；q = 真正发给模型的（what / why 要求已拼进去）
  if (!q) return
  const sq = shown || q
  answerDismissed = false
  if (withShot) return askWithNewShot(q, sq)
  if (!lastB64s || !lastB64s.length) {
    pushAnswer('\n\n---\n\n**追问：** ' + sq + '\n\n还没有可追问的识别结果 —— 先点一下小球识别当前屏幕。\n', true)
    return
  }
  if (isSolving) {
    pushAnswer('\n\n---\n\n**追问：** ' + sq + '\n\n（上一个任务还在跑，稍等一下再问）\n', true)
    return
  }
  isSolving = true
  const wd = setTimeout(() => { isSolving = false }, 120000)
  try {
    if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:busy')
    pushAnswer('\n\n---\n\n**追问：** ' + sq + '\n\n')
    const a = await streamChat((m, noThinking) => askBody(m, q, noThinking), { remember: false })
    conv.push({ role: 'user', content: q }, { role: 'assistant', content: a })
    // v1.58：追问也写回这次识别的历史（关了再开还能看到整段）
    if (lastHistId && a) { lastHistText += '\n\n---\n\n**追问：** ' + sq + '\n\n' + a; historyUpdate(lastHistId, lastHistText) }
    modeLog('追问：' + sq.slice(0, 40) + ' → ' + (a || '').length + ' 字')
  } catch (e) {
    pushAnswer('出错：' + errText(e), true)
  } finally {
    clearTimeout(wd)
    isSolving = false
    endSolveRound()   // v1.73 D1：追问结束也收尾，别把排在后面的手动请求留住/误放
  }
}

async function askWithNewShot(q, shown) {
  const sq = shown || q
  if (isSolving) {
    pushAnswer('\n\n---\n\n**追问（附新截图）：** ' + sq + '\n\n（上一个任务还在跑，稍等一下再问）\n', true)
    return
  }
  isSolving = true
  const wd = setTimeout(() => { isSolving = false }, 120000)
  try {
    if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:busy')
    const shots = await grabForFollowUp()
    // 之前没识别过：这张就当「原截图」，直接开一段新对话
    if (!lastB64s || !lastB64s.length) { lastB64s = shots; conv = [] }
    const hasOld = conv.length > 0 || lastB64s !== shots
    const text = (hasOld ? '情况有更新：下面是刚截的最新屏幕（前面那张是原来的截图），请以最新截图为准。\n' : '')
      + '只回答这一次的追问，不要重复之前的解析，也不要复述题干。' + (keywordsOn() ? '这次不用再列关键词清单。' : '') + '\n追问：' + q
    const userTurn = lastB64s === shots ? { role: 'user', content: text } : { role: 'user', content: [{ type: 'text', text }].concat(imgParts(shots)) }
    // 发之前就裁：这次的新图 + 之前最多 ASK_EXTRA_SHOTS_MAX-1 张
    if (Array.isArray(userTurn.content)) trimConvShots(ASK_EXTRA_SHOTS_MAX - 1)
    pushAnswer('\n\n---\n\n**追问（附新截图）：** ' + sq + '\n\n')
    const a = await streamChat((m, noThinking) => askBodyWith(m, userTurn, noThinking), { remember: false })
    conv.push(userTurn, { role: 'assistant', content: a })
    if (lastHistId && a) { lastHistText += '\n\n---\n\n**追问（带新截图）：** ' + sq + '\n\n' + a; historyUpdate(lastHistId, lastHistText) }
    modeLog('追问（附新截图 ' + shots.length + ' 张）：' + sq.slice(0, 40) + ' → ' + (a || '').length + ' 字')
  } catch (e) {
    const m = errText(e)
    pushAnswer(m === SCREEN_NO_PERM ? '截不了新图：没有「屏幕录制」权限。' : m === SCREEN_NO_PICTURE ? '截不了新图：屏幕当前无画面。' : '出错：' + m, true)
  } finally {
    clearTimeout(wd)
    isSolving = false
    endSolveRound()   // v1.73 D1：追问结束也收尾，别把排在后面的手动请求留住/误放
  }
}

/* v1.58 识别缓存：画面指纹 + 会影响回答的所有设置（系统提示词里已含模式 / 强度 / 小白 / 自定义要求）。
 * 截图里有灵珠自己的小球和面板（内容每次都变），指纹里把这些矩形盖掉，只看你屏幕上的东西。 */
function ownRects(disp, imgW, imgH) {
  const sx = imgW / disp.bounds.width, sy = imgH / disp.bounds.height
  const out = []
  for (const w of [orbWin, answerWin, transWin]) {
    if (!w || w.isDestroyed() || !w.isVisible()) continue
    const b = w.getBounds()
    out.push({ x: Math.floor((b.x - disp.bounds.x) * sx) - 1, y: Math.floor((b.y - disp.bounds.y) * sy) - 1, w: Math.ceil(b.width * sx) + 2, h: Math.ceil(b.height * sy) + 2 })
  }
  return out
}
function solveKey(b64s) {
  try {
    const disp = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()) || screen.getPrimaryDisplay()
    const fps = b64s.map((b64, idx) => {
      const img = nativeImage.createFromBuffer(Buffer.from(b64, 'base64')).resize({ width: 240, quality: 'good' })   // 240 宽：一道题只改了一个数字也能区分开（96 宽时可能被缩没）
      const { width, height } = img.getSize()
      // 第一张是光标所在的屏（toBase64List 排好的），只有它上面有灵珠的窗口
      return X.fingerprint(img.toBitmap(), width, height, 0.05, idx === 0 ? ownRects(disp, width, height) : [])
    })
    const cfg = apiCfg()
    modeLog('画面指纹 ' + fps.map((f) => f.slice(0, 10)).join(',') + '（盖掉 Ling 自己的窗口 ' + ownRects(disp, 240, 150).length + ' 块）')
    return X.cacheKey('solve', fps.join(','), sysPrompt(), cfg.baseUrl, cfg.model, thinkEffort())
  } catch (e) { modeLog('算画面指纹失败：' + errText(e)); return null }
}
let lastHistId = 0, lastHistText = ''

/* v1.73 D1：一轮识别（追问 / 追问附新截图也占用 isSolving）结束时的统一收尾 ——
 * 把「单一待办」接上、按有没有排队决定保持 busy 还是回到 idle、重置面板闲置回收计时。
 * solve / askFollowUp / askWithNewShot 三条 finally 都走它，
 * 否则「双击排队在一个追问后面」就再也起不来了（而且那条待办还会在下一轮被误放）。 */
function endSolveRound() {
  const p = pendingSolve
  pendingSolve = null
  if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send(p ? 'orb:busy' : 'orb:idle')
  armPanelIdle()   // v1.73 D6：识别结束 = 面板「在用」，闲置回收的 10 分钟从这一刻重新计时
  if (p) setTimeout(() => solve(p.auto, p.fresh), 0)
}

async function solve(auto, fresh) {   // v1.49：auto = 自动扫描发起的一轮；v1.58：fresh = 「重新识别」按钮，不用缓存
  if (isSolving) {
    // v1.73 D1：识别进行中又来请求 —— 不再静默丢掉（老写法直接 return，双击时用户看到面板弹了却什么都没发生）。
    // 合并成「最多一个待办」：不是队列，不会无限排；手动优先 —— 自动扫描的请求不覆盖已排队的手动请求。
    if (!pendingSolve || !auto) pendingSolve = { auto: !!auto, fresh: !!fresh }
    return
  }
  isSolving = true
  answerDismissed = false
  // v1.73：busy 集中在这里发 —— 每一轮真正开跑就发一次；「重新识别」过去漏发，现在也盖上了。
  if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:busy')
  const solveWatchdog = setTimeout(() => { if (isSolving) { console.error('[orb] solve watchdog: force reset'); isSolving = false } }, 120000)
  try {
    showAnswerState(auto ? 'rescan' : 'capturing')
    let b64s = null
    // v1.58：截图前把灵珠自己的窗口（小球 / 答案面板 / 翻译面板）调成全透明一小会儿（同安卓）——
    // 模型看到的是干净的屏幕，识别缓存的画面指纹也不会被面板的大小和内容带偏。
    // v1.73 D3：藏之前先 freezeWin 停掉淡入/滑入定时器，否则它们会在「已经透明」的窗口上继续抬透明度 → 面板被截进去。
    const hidden = []
    for (const w of [orbWin, answerWin, transWin]) {
      if (w && !w.isDestroyed() && w.isVisible()) { hidden.push([w, freezeWin(w)]); w.setOpacity(0) }
    }
    if (hidden.length) await sleep(70)
    try {
      b64s = await takeScreensBase64()
    } catch (capErr) {
      const cm = errText(capErr)
      if (cm === SCREEN_NO_PICTURE) screenStreak++
      else screenStreak = 0
      throw capErr
    }
    finally {
      // v1.73 D3：只恢复「截屏这几十毫秒里没被用户关掉的」窗口。
      // 关掉中/正在关的窗口让它继续关（fadeClose 把定时器停了会断掉关闭回调，这里补回来），
      // 绝不 setOpacity(1) 把用户已经关掉的窗口复活；其余的淡回「完全可见」，不留在冻结的半透明值上。
      for (const [w, wasClosing] of hidden) {
        try {
          if (w.isDestroyed()) continue
          if (wasClosing || w.__closing) { w.__closing = false; fadeClose(w) }
          else fadeWin(w, 1, 80)
        } catch {}
      }
    }
    if (!b64s || !b64s.length) { screenStreak++; showAnswerState('capture_failed'); return }
    screenStreak = 0
    // v1.26：把这次的截图留一份给「追问」用；新一轮识别 = 开一段新对话
    lastB64s = b64s
    conv = []
    const key = orbState.useCache !== false ? solveKey(b64s) : null
    const hit = (!fresh && key) ? solveCache().get(key) : null
    if (hit) {
      modeLog('识别缓存命中（' + X.ago(hit.c) + '的结果）')
      pushAnswer(hit.v, true)
      pushNotice('↺ 缓存：这个画面' + X.ago(hit.c) + '识别过，直接用了上次的结果（没调接口）· 要重新问点 ↻')
      conv = [{ role: 'assistant', content: hit.v }]
      rememberAnswer(hit.v, ' · 缓存')
      return
    }
    if (!auto) showAnswerState('solving')
    const ans = await streamAnswer(b64s)
    conv = ans && ans.trim() ? [{ role: 'assistant', content: ans }] : []
    if (key && ans && ans.trim()) solveCache().put(key, ans)
  } catch (e) {
    const msg = errText(e)
    try {
      if (msg === SCREEN_NO_PERM) {
        showAnswerState('screen_denied')
        pushAnswer('# 截屏被系统拦截\n\n没有「屏幕录制」权限，任何应用都无法截屏（macOS 系统规则）。\n\n1. 点上方那条提示，直接打开系统设置\n2. 在「隐私与安全性 → 屏幕录制」里勾选 **' + APP_NAME + '**\n3. 重启' + APP_NAME + '（右键小球 → 退出，再重新打开）\n\n如果列表里找不到「' + APP_NAME + '」：退出后重新打开本应用，再点一次小球，它就会出现在列表里。\n\n> 实际上：程序已自动改用系统自带的 screencapture 命令截图，本次未生效说明它也一起被拦了，请按上面三步授权。', true)
      } else if (msg === SCREEN_NO_PICTURE) {
        pushAnswer('屏幕当前无画面（显示器休眠 / 锁屏），点亮屏幕后会自动恢复。', true)
      } else {
        pushAnswer('出错：' + msg, true)
      }
    } catch {}
  } finally {
    clearTimeout(solveWatchdog)
    isSolving = false
    // v1.73 D1：收尾统一走 endSolveRound —— 把排队的那一轮接上；有排队就保持 busy（避免 idle→busy 闪一下），没有才真正 idle。
    endSolveRound()
  }
}

function warmScreenPermission() {
  const st = screenPermStatus()
  if (st === 'not-determined') {
    desktopCapturer.getSources({ types: ['screen'] }).catch(() => {})
  } else if (st !== 'granted') {
    // adhoc 签名下每次重建都会让授权失效；这里只记录，实际截图会自动走 screencapture 兜底
    logCapture('启动时屏幕录制权限状态=' + st + '（将由 screencapture 兜底）')
  }
}

function openPrivacyPane() {
  shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture')
}

let hintWin = null
let hintTimer = null
// 从新手卡点「配置接口」进设置：保存 / 取消回球旁继续（只限本次进程，已看过的老用户不会被重新弹）
let hintResumeOnSettingsClose = false
/* v1.61.1：首次引导 = 真的能点的新手卡（复用 hint.html）
 *  - 贴着小球显示；球在左半边 → 卡片在右、箭头朝左；球在右半边 → 卡片在左、箭头朝右
 *  - 位置按**球所在显示器**的 workArea 夹取，拖动 / 换屏都跟着走
 *  - 首次（hintShown=false）显示；只有真正显示成功才把 hintShown 落盘
 *  - 不再有 12 秒自动消失的计时器，也不再鼠标穿透
 */
const HINT_W = 340
const HINT_GAP = 12
const HINT_PAD = 8
const HINT_DEFAULT_H = 260
function isHintSender(e) {
  return !!(e && e.sender && hintWin && !hintWin.isDestroyed() && e.sender === hintWin.webContents)
}
function placeHint(height) {
  if (!orbWin || orbWin.isDestroyed() || !hintWin || hintWin.isDestroyed()) return
  const b = orbWin.getBounds()
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  const H = Math.max(120, Math.min(560, Math.round(Number(height) || HINT_DEFAULT_H)))
  const need = HINT_W + HINT_GAP
  const rightSpace = (wa.x + wa.width) - (b.x + b.width)
  const leftSpace = b.x - wa.x
  // 优先放在球右边（左中默认位就是这种情况）；右边放不下再翻到左边；两边都放不下取更宽的一侧
  let side
  if (rightSpace >= need) side = 'right'
  else if (leftSpace >= need) side = 'left'
  else side = rightSpace >= leftSpace ? 'right' : 'left'
  let x = side === 'right' ? b.x + b.width + HINT_GAP : b.x - HINT_W - HINT_GAP
  x = Math.min(Math.max(x, wa.x + HINT_PAD), wa.x + wa.width - HINT_W - HINT_PAD)
  let y = Math.round(b.y + b.height / 2 - H / 2)
  y = Math.min(Math.max(y, wa.y + HINT_PAD), wa.y + wa.height - H - HINT_PAD)
  const nb = { x: Math.round(x), y, width: HINT_W, height: H }
  const cur = hintWin.getBounds()
  if (cur.x !== nb.x || cur.y !== nb.y || cur.width !== nb.width || cur.height !== nb.height) hintWin.setBounds(nb)
  // 拖动时 orb:move 很密，方向没变就别反复发 IPC
  if (hintWin.__side !== side) { hintWin.__side = side; try { hintWin.webContents.send('hint:side', side) } catch {} }
}
// 只在卡片真的显示出来之后才记「已看过」—— 建窗失败 / 页面没渲染就退出的话，下次还该再引导
function markHintShown() {
  if (orbState.hintShown) return
  orbState.hintShown = true
  saveState()
  modeLog('首次新手卡已显示 → hintShown=true')
}
function showHint() {
  if (hintTimer) { clearTimeout(hintTimer); hintTimer = null }
  if (!orbWin || orbWin.isDestroyed()) return
  if (hintWin && !hintWin.isDestroyed()) { placeHint(hintWin.getBounds().height); return }
  hintWin = new BrowserWindow({ show: false,
    width: HINT_W, height: HINT_DEFAULT_H, x: 0, y: 0, frame: false, transparent: true, hasShadow: false,
    resizable: false, movable: false, alwaysOnTop: true, skipTaskbar: true, title: APP_NAME + ' 新手引导',
    fullscreenable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  topMost(hintWin, LAYER_POPUP)
  hideFromMissionControl(hintWin)
  const hw = hintWin
  hw.on('closed', () => {
    if (hintWin !== hw) return
    hintWin = null
    hintResumeOnSettingsClose = false
    if (hintTimer) { clearTimeout(hintTimer); hintTimer = null }
  })
  // 建窗时那次 send 还在 loadFile 之前，渲染层收不到 —— 加载完成后再补一次方向
  hw.webContents.on('did-finish-load', () => {
    if (hintWin !== hw || hw.isDestroyed()) return
    hw.__side = null
    placeHint(hw.getBounds().height)
  })
  placeHint(HINT_DEFAULT_H)
  hw.loadFile(path.join(__dirname, 'hint.html'))
}
function hideHint(instant) {
  if (hintTimer) { clearTimeout(hintTimer); hintTimer = null }
  hintResumeOnSettingsClose = false
  const w = hintWin
  hintWin = null
  if (!w || w.isDestroyed()) return
  // 「试一次识别」要立刻截屏：淡出 (110ms) 可能被截进去，这里直接销毁（没有需要跑的卸载逻辑）
  if (instant) { try { w.destroy() } catch {} }
  else fadeClose(w)
}
/* 新手卡 IPC：只认 hintWin 自己的 webContents（和设置卡同一套 sender 校验）。
 * 卡片自己量完高度再报上来，主进程按真实高度贴球摆位；首帧就绪后再显示并落盘 hintShown。 */
ipcMain.on('hint:resize', (e, h) => {
  if (!isHintSender(e)) return
  const H = Math.max(120, Math.min(560, Math.round(Number(h) || HINT_DEFAULT_H)))
  placeHint(H)
  if (!hintWin.__revealed) {
    hintWin.__revealed = true
    revealWin(hintWin, false)
    markHintShown()
  }
})
ipcMain.on('hint:action', (e, action) => {
  if (!isHintSender(e)) return
  const a = String(action || '')
  if (a === 'settings' || a === 'try') {
    const ready = a === 'try' && apiReady()
    hideHint(ready)
    if (!ready) {
      // 还没配接口：先开设置，存 / 取消后回到球旁继续这张卡（本次进程内）
      hintResumeOnSettingsClose = true
      showSettings()
      return
    }
    questionPauseUntil = 0
    solve()   // 成功与否都走现有流程：没权限就是面板里的真实权限提示，不在这里假装成功
    return
  }
  if (a === 'dismiss') hideHint()
})

let autoScanTimer = null
let autoScanning = false
let screenStreak = 0
let standbyNotified = false
let questionPauseUntil = 0
let pauseNotified = false
// v1.31：扫描间隔可自定义（菜单档位 10/20/60 秒，设置卡里 5 秒~1 小时任意填）。
// whenReady 的 loadState 之后会用 state 里的值覆盖这里的环境变量默认值。
let scanIntervalMs = Number(process.env.SCAN_INTERVAL_MS) || 20000
const SCAN_INTERVAL_STEPS = [5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1800, 3600].map((s) => s * 1000)
function fmtInterval(ms) { return ms < 60000 ? Math.round(ms / 1000) + ' 秒' : (ms % 60000 === 0 ? Math.round(ms / 60000) + ' 分钟' : (ms / 60000).toFixed(1) + ' 分钟') }
function setExamDetail(id) {
  if (!EXAM_DETAILS.includes(id)) return
  orbState.examDetail = id
  saveState()
  modeLog('解析强度 → ' + EXAM_DETAIL_LABEL[id])
  if (menuWin && !menuWin.isDestroyed()) { try { menuWin.webContents.send('menu:update', { detailLabel: EXAM_DETAIL_LABEL[id] }) } catch {} }
}
function setNovice(on) {
  orbState.novice = !!on
  saveState()
  modeLog('纯小白 → ' + (on ? '开' : '关'))
}
function setIntervalMs(ms) {
  scanIntervalMs = Math.min(3600000, Math.max(5000, Number(ms) || 20000))
  orbState.scanIntervalMs = scanIntervalMs
  saveState()
  if (autoScanning) { if (autoScanTimer) { clearTimeout(autoScanTimer); autoScanTimer = null } scheduleScanTick() }
  modeLog('扫描间隔 → ' + fmtInterval(scanIntervalMs))
}

function sendScanState(on) {
  if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:scan', on)
  if (on) answerDismissed = false
  const w = answerWin || (on ? placeAnswer() : null)
  if (w && !w.isDestroyed()) w.webContents.send('answer:state', on ? 'autoscan_on' : 'autoscan_off')
}

function scheduleScanTick() {
  if (!autoScanning) return
  const delay = screenStreak >= 3 ? 60000 : scanIntervalMs
  autoScanTimer = setTimeout(() => { tickScan(); scheduleScanTick() }, delay)
}

function startAutoScan() {
  if (autoScanning) return
  autoScanning = true
  screenStreak = 0
  standbyNotified = false
  sendScanState(true)
  saveState()
  tickScan()
  scheduleScanTick()
}

function stopAutoScan() {
  if (!autoScanning) return
  autoScanning = false
  if (autoScanTimer) { clearTimeout(autoScanTimer); autoScanTimer = null }
  sendScanState(false)
  saveState()
}

function toggleAutoScan() {
  autoScanning ? stopAutoScan() : startAutoScan()
}

function tickScan() {
  if (isSolving) return
  if (Date.now() < questionPauseUntil) {
    if (!pauseNotified) { showAnswerState('question_paused'); pauseNotified = true }
    return
  }
  pauseNotified = false
  if (screenStreak >= 3) {
    if (!standbyNotified) { showAnswerState('autoscan_standby'); standbyNotified = true }
    return
  }
  standbyNotified = false
  solve(true)   // v1.73：busy 由 solve() 统一发（见 solve 开头），这里不再单独发
}

/* ---------------- 设置卡（v1.31）：自定义 API + 扫描间隔 ---------------- */
let settingsWin = null
function showSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) { settingsWin.focus(); return }
  const wa = screen.getPrimaryDisplay().workArea
  const width = 470
  const b = orbWin ? orbWin.getBounds() : { x: wa.x + wa.width - 100, y: wa.y, width: 80, height: 80 }
  const x = Math.max(wa.x + 8, Math.min(b.x - width + 90, wa.x + wa.width - width - 8))
  const y = Math.min(wa.y + wa.height - 480, b.y + b.height + 10)
  settingsWin = new BrowserWindow({
    width, height: 480, x, y, title: APP_NAME + ' 设置',
    frame: false, transparent: true, resizable: false, movable: true,
    alwaysOnTop: true, skipTaskbar: true, show: false,
    fullscreenable: false, minimizable: false, maximizable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false }
  })
  settingsWin.setAlwaysOnTop(true, 'screen-saver', LAYER_POPUP)
  settingsWin.loadFile(path.join(__dirname, 'settings.html'))
  const stw = settingsWin
  settingsWin.once('ready-to-show', () => {
    if (settingsWin !== stw) return
    // accessory 应用不会自动激活 —— 不 steal 焦点的话键盘输入全进了别的 App
    try { app.focus({ steal: true }) } catch {}
    // v1.48：等页面第一次报高度（settings:get 回来之后）再亮相，免得在眼前 480→516 跳一下
    revealAfterFirstResize(stw, true, 250)
  })
  settingsWin.on('closed', () => {
    if (settingsWin === stw) settingsWin = null
    // v1.63：设置卡一关，在飞的 AI 设置搜索立刻取消（不留在后台跑）
    try { settingsSearchCancel(stw.webContents.id) } catch {}
    // v1.61：设置卡一关就真停 —— 不能留一个没人看得见的开放端口。
    // 只在没有新设置卡接手时 stop，避免旧窗口延迟关闭时误停新窗口刚开的同步。
    if (!settingsWin) lanSyncStop()
  })
}
function closeSettings() {
  lanSyncStop()
  fadeClose(settingsWin)
  settingsWin = null
  /* v1.61.1：新手卡 →「配置接口」→ 设置。保存和取消都算这一轮结束，
   * 回到小球旁边继续那张卡（仅本进程；hintShown 已落盘，下次启动不会再弹）。 */
  if (hintResumeOnSettingsClose) { hintResumeOnSettingsClose = false; showHint() }
}

ipcMain.handle('settings:get', () => {
  const cfg = apiCfg()
  // v1.59 内测版用着内置接口时：地址和 Key 框留空（内置 Key 不往界面上送），只显示模型名
  const builtin = usingBuiltinApi()   // 恒 false：开源构建没有内置接口
  return { baseUrl: builtin ? '' : cfg.baseUrl, apiKey: builtin ? '' : cfg.apiKey, model: cfg.model, usingBuiltin: builtin, builtinLabel: '',
    scanIntervalMs, defaults: API_DEFAULTS, firstRun: !orbState.apiOnboarded, appearance: appearanceOf(), profiles: orbState.apiProfiles || [], activeProfile: orbState.activeProfile || null, internal: false,
    hotkey: hotkeyLabel, hotkeyRaw: hotkeyRaw, hotkeyDefault: accelLabel(DEFAULT_TRANSLATE_HOTKEY), hotkeyDefaultRaw: DEFAULT_TRANSLATE_HOTKEY,
    thinkingEffort: thinkEffort(), thinkingOptions: [{ id: 'off', label: '关' }, { id: 'low', label: '低' }, { id: 'high', label: '高' }, { id: 'max', label: '最高' }],
    examDetail: examDetail(), examDetailOptions: EXAM_DETAILS.map((id) => ({ id, label: EXAM_DETAIL_LABEL[id] })), novice: !!orbState.novice,
    // v1.58
    transEngine: TRANS_ENGINES.includes(orbState.transEngine) ? orbState.transEngine : defaultEngine(), baidu: orbState.baidu || { appId: '', apiKey: '', secret: '', model: 'llm' },
    // v1.59：只回传自己填的 DeepL Key；内置的只告诉「有」，不回传
    deepl: { apiKey: (orbState.deepl && orbState.deepl.apiKey) || '' }, deeplBuiltIn: false, channel: CHANNEL_LABEL, appName: APP_NAME, version: app.getVersion(), aiBuiltIn: false,
    useCache: orbState.useCache !== false, saveHistory: orbState.saveHistory !== false, cacheInfo: cacheInfo(),
    customModes: customModes(), mode: currentMode,
    keywords: keywordsOn(), theme: TH.normId(orbState.theme), panelSide: orbState.panelSide || 'left',
    // v1.76：专注守护 / 陪伴（点一下就存的部分保存字段；陪伴默认开、AI 短句默认关）
    focusDurationMin: focusDurationMin(), focusAwaySec: focusAwaySec(), companionOn: orbState.companionOn !== false, companionFreq: companionFreq(), companionAi: !!orbState.companionAi,
    // v1.60：默认更新源显示在设置页 —— 地址只由主进程给，前端不硬编码，也不因此联网
    updateChannel: 'public', updateSource: U.manifestUrl() }
})
function cacheInfo() { return { solve: solveCache().count(), translate: transCache().count(), kb: Math.ceil((solveCache().bytes() + transCache().bytes()) / 1024), history: histStore().read().length } }
ipcMain.handle('settings:clearcache', () => { solveCache().clear(); transCache().clear(); return cacheInfo() })
ipcMain.handle('settings:clearhistory', () => { histStore().clear(); history.length = 0; return cacheInfo() })
ipcMain.on('settings:save', (_e, o) => {
  try {
    const st = o || {}
    // v1.42：允许「只改一项」的部分保存（比如设置卡里点一下思考强度就生效）——
    // 没带地址/模型时不要拿空串把现有 API 配置冲掉。
    const apiTouched = typeof st.baseUrl === 'string' || typeof st.model === 'string' || typeof st.apiKey === 'string'
    if (apiTouched) {
      orbState.api = { baseUrl: String(st.baseUrl || '').trim(), apiKey: String(st.apiKey || '').trim(), model: String(st.model || '').trim() }
    }
    if (st.scanIntervalSec != null) setIntervalMs(Number(st.scanIntervalSec) * 1000)
    if (st.thinkingEffort && THINK_EFFORTS.includes(String(st.thinkingEffort))) {
      orbState.thinkingEffort = String(st.thinkingEffort)
      modeLog('思考强度 → ' + orbState.thinkingEffort)
    }
    if (st.examDetail && EXAM_DETAILS.includes(String(st.examDetail))) setExamDetail(String(st.examDetail))
    if (typeof st.novice === 'boolean') setNovice(st.novice)
    // v1.76：专注守护 / 陪伴（同思考强度一样点一下就存；陪伴开关 / 频率 / AI 短句变化立即重排下一次）
    let companionTouched = false
    if (Number.isFinite(Math.round(Number(st.focusDurationMin)))) orbState.focusDurationMin = Math.min(240, Math.max(1, Math.round(Number(st.focusDurationMin))))
    if (Number.isFinite(Math.round(Number(st.focusAwaySec)))) orbState.focusAwaySec = Math.min(300, Math.max(5, Math.round(Number(st.focusAwaySec))))
    if (typeof st.companionOn === 'boolean') { orbState.companionOn = st.companionOn; companionTouched = true }
    if (COMPANION_FREQS.includes(String(st.companionFreq))) { orbState.companionFreq = String(st.companionFreq); companionTouched = true }
    if (typeof st.companionAi === 'boolean') { orbState.companionAi = st.companionAi; companionTouched = true }
    if (companionTouched) applyCompanionSettings()
    // v1.58：翻译引擎 / 百度凭证 / 缓存 / 历史（点一下就存，和思考强度一样是「部分保存」）
    if (TRANS_ENGINES.includes(st.transEngine)) orbState.transEngine = st.transEngine
    if (st.deepl && typeof st.deepl === 'object') orbState.deepl = { apiKey: String(st.deepl.apiKey || '').trim() }
    if (st.baidu && typeof st.baidu === 'object') orbState.baidu = { appId: String(st.baidu.appId || '').trim(), apiKey: String(st.baidu.apiKey || '').trim(), secret: String(st.baidu.secret || '').trim(), model: st.baidu.model === 'nmt' ? 'nmt' : 'llm' }
    if (typeof st.useCache === 'boolean') orbState.useCache = st.useCache
    if (typeof st.saveHistory === 'boolean') orbState.saveHistory = st.saveHistory
    if (Array.isArray(st.customModes)) {
      orbState.customModes = st.customModes.filter((x) => x && x.id).map((x) => ({ id: String(x.id), name: String(x.name || '自定义').slice(0, 20), prompt: String(x.prompt || '') }))
      if (!allModeIds().includes(currentMode)) setMode('general')   // 当前模式被删了：回到通用
      else if (answerWin && !answerWin.isDestroyed()) sendMode()   // 改了名字：面板上的模式标签跟着变
    }
    // v1.60：识别关键词开关 / 配色 / 结果面板位置（点一下就存）
    if (typeof st.keywords === 'boolean') { orbState.keywords = st.keywords; modeLog('识别关键词 → ' + (st.keywords ? '开' : '关')) }
    if (st.theme != null) { orbState.theme = TH.normId(String(st.theme)); pushAppearance() }
    if (PANEL_SIDES.includes(st.panelSide)) { orbState.panelSide = st.panelSide; pushAppearance() }
    if (st.appearance) {
      const a = st.appearance
      orbState.appearance = {
        bgAlpha: Math.min(0.85, Math.max(0.25, Number(a.bgAlpha) || 0.50)),
        fontSize: Math.min(18, Math.max(12, Number(a.fontSize) || 14)),
        contrast: Math.min(1, Math.max(0.7, Number(a.contrast) || 0.92))
      }
      pushAppearance()
    }
    // v1.33：多档案 —— saveAs 存为新档案；profile 指向已有档案则同步更新它
    // v1.49：部分保存（只点了思考强度 / 解析强度 / 纯小白）不碰档案 —— 以前会落到最后的 else 把当前档案清空
    if (!apiTouched) {}
    else if (st.saveAs && st.saveAs.trim()) {
      const name = st.saveAs.trim()
      const p = orbState.apiProfiles.find((x) => x.name === name)
      if (p) { p.baseUrl = orbState.api.baseUrl; p.apiKey = orbState.api.apiKey; p.model = orbState.api.model }
      else orbState.apiProfiles.push({ name, baseUrl: orbState.api.baseUrl, apiKey: orbState.api.apiKey, model: orbState.api.model })
      orbState.activeProfile = name
    } else if (st.profile && orbState.apiProfiles.some((x) => x.name === st.profile)) {
      const p = orbState.apiProfiles.find((x) => x.name === st.profile)
      p.baseUrl = orbState.api.baseUrl; p.apiKey = orbState.api.apiKey; p.model = orbState.api.model
      orbState.activeProfile = st.profile
    } else orbState.activeProfile = null
    if (!apiTouched) { saveState(); return }
    orbState.apiOnboarded = true
    saveState()
    modeLog('API 设置已保存：' + (orbState.api.baseUrl || '默认地址') + ' / ' + (orbState.api.model || '默认模型') + (orbState.activeProfile ? '（档案 ' + orbState.activeProfile + '）' : ''))
  } catch {}
  // v1.37：存为档案时别急着关卡片 —— 存完直接接着「检测连接」才顺手
  if (!(o && o.keepOpen)) closeSettings()
})
ipcMain.on('settings:cancel', () => { if (!orbState.apiOnboarded) { orbState.apiOnboarded = true; saveState() } closeSettings() })

/* ---------------- v1.63：设置搜索的 AI 语义匹配（只认设置窗口） ----------------
 * 边界（对齐 Android 2.6.1，桌面独立线）：
 *   - 只有当前 settingsWin 的 webContents 能调用；其他 renderer 一律「没有权限」。
 *   - 上行只有 query（限长）+ requestId；发给模型的是 query + settings-search.js 里固定的目录标题 / 路径 / ID，
 *     不带任何设置值、Key、历史、屏幕内容。
 *   - 每窗口同一时刻只有最新一条在飞：新请求先取消旧请求。取消 / 关窗 / 失焦都由渲染端调 cancel，
 *     主进程在窗口关闭时再兜一次。
 *   - 独立 AbortController，超时覆盖到读完 body；不复用 streamChat（它会写历史 / 改答案面板），
 *     不换模型、不自动重试。模型只准回白名单 id，parseIds 之后再返回。
 *   - 错误只给可读短句；日志只记状态码 / 固定错误类别，不记上游原始返回体与异常文本（可能回显 Key / 内网地址）。 */
const SS = require('./settings-search')   // 目录 / 本机匹配 / parseIds 的单一来源（主进程与页面共用）
const SETTINGS_SEARCH_TIMEOUT_MS = 20000
const MAX_SETTINGS_SEARCH_QUERY = 120   // 与 settings-search.js 的 limits.query 一致
const settingsSearchActive = new Map()   // webContents.id -> { requestId, ac }

const SETTINGS_SEARCH_ERR = {
  net: '连不上接口（地址不对 / 需要代理 / 服务没起来）',
  timeout: '接口 20 秒内没回完（已停止这次匹配）',
  cancelled: '已取消',
  denied: '接口拒绝了这次请求（请在「配置接口」里检查 Key 和模型）',
  rate: '接口限流或额度用完了，稍后再试',
  http: '接口返回了错误（请在「配置接口」里检查 Key 和模型）',
  bad: '模型没按格式给出结果，建议换个说法再试',
  empty: '模型没找到相关设置，建议换个说法再试',
  nokey: '还没有配置接口：先在「配置接口」里填好地址和 Key',
  badreq: '搜索词太长或请求重复，请修改后再试',
  unknown: '未知错误'
}
function settingsSearchErrText(code) {
  return SETTINGS_SEARCH_ERR[code] || SETTINGS_SEARCH_ERR.unknown
}

function settingsSearchPrompt(query) {
  const lines = SS.catalogForPrompt(true).map((e) => e.id + '\t' + e.title + '\t' + e.path + '\t' + e.aliases)
  return [
    '你在帮用户在他自己的软件「Ling」的桌面设置页里找设置项。',
    '下面是设置页里真实存在的全部条目：ID、标题、分组路径、用户可能的口头说法（制表符分隔）。只能从这些 ID 里选。',
    lines.join('\n'),
    '',
    '用户搜索：' + query,
    '',
    '要求：只能从上面的 ID 里挑最相关的 1~8 个，按相关度从高到低。',
    '只输出一行，ID 之间用逗号分隔，不要解释、不要编号、不要 markdown；',
    '确实没有相关的就输出 NONE。'
  ].join('\n')
}

function settingsSearchCancel(wcId) {
  const a = settingsSearchActive.get(wcId)
  if (!a) return
  settingsSearchActive.delete(wcId)
  try { a.ac.abort() } catch {}
}
/* 每窗口「最新单飞」的登记：外层先占一条 pending（{ requestId, ac: parent }），
 * 进入真正请求后换成实际请求（{ requestId, ac, parent }）。收尾只清自己那条，不误删后来的请求。 */
function settingsSearchTrack(wcId, requestId, ac, parent) {
  settingsSearchActive.set(wcId, { requestId, ac, parent })
}
function settingsSearchRelease(wcId, requestId) {
  const cur = settingsSearchActive.get(wcId)
  // 只清自己那条：新请求已经占位时不能把它的登记删掉
  if (cur && cur.requestId === requestId) settingsSearchActive.delete(wcId)
}

async function settingsSearchRun(wcId, query, requestId, parentAbort) {
  const cfg = apiCfg()
  // 和「试一次识别」同一套判断：正式版既没填地址、也没内置接口 → 明确提示先配置，不悄悄打默认网关
  if (!apiReady() && !usingBuiltinApi()) {
    // 没有请求在飞，登记必须清掉，否则这个窗口后续的搜索 / 取消都会认到一条死记录
    settingsSearchRelease(wcId, requestId)
    return { ok: false, error: settingsSearchErrText('nokey') }
  }
  const ac = new AbortController()
  const onParent = () => { try { ac.abort() } catch {} }
  if (parentAbort) parentAbort.addEventListener('abort', onParent)
  const timedOut = { hit: false }
  const to = setTimeout(() => { timedOut.hit = true; try { ac.abort() } catch {} }, SETTINGS_SEARCH_TIMEOUT_MS)
  settingsSearchTrack(wcId, requestId, ac, parentAbort)
  try {
    const body = Object.assign({
      model: cfg.model, stream: false, max_tokens: 200, temperature: 0,
      messages: [{ role: 'user', content: settingsSearchPrompt(query) }]
    }, thinkingOff(cfg))
    const res = await fetch(cfg.baseUrl + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + cfg.apiKey },
      body: JSON.stringify(body), signal: ac.signal
    })
    if (!res.ok) {
      await res.text().catch(() => '')
      modeLog('设置搜索：接口 ' + res.status)
      return { ok: false, error: settingsSearchErrText(res.status === 401 || res.status === 403 ? 'denied' : res.status === 429 ? 'rate' : res.status === 400 || res.status === 404 ? 'denied' : 'http') }
    }
    // 超时覆盖读 body：定时器一直活到 body 读完 / 出错，服务端只发头不发体也会被掐断
    const raw = await res.text()
    if (timedOut.hit) return { ok: false, error: settingsSearchErrText('timeout') }
    let content = ''
    try {
      const j = JSON.parse(raw)
      content = String((j && j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || '')
    } catch { content = '' }
    if (!content.trim()) return { ok: false, error: settingsSearchErrText('bad') }
    const ids = SS.parseIds(content)
    if (!ids.length) {
      // NONE = 模型明确说没有相关设置，是正常结果而不是失败
      if (/^\s*none\s*$/i.test(content)) {
        modeLog('设置搜索：NONE')
        return { ok: true, ids: [] }
      }
      modeLog('设置搜索：零个有效 ID')
      return { ok: false, error: settingsSearchErrText('bad') }
    }
    modeLog('设置搜索：命中 ' + ids.length + ' 条（' + ids.join(',') + '）')
    return { ok: true, ids }
  } catch (err) {
    if (timedOut.hit) return { ok: false, error: settingsSearchErrText('timeout') }
    if (ac.signal.aborted) return { ok: false, cancelled: true, error: settingsSearchErrText('cancelled') }
    const t = String((err && err.message) || '')
    const code = /fetch failed|ENOTFOUND|ECONNREFUSED|ECONNRESET|network|getaddrinfo/i.test(t) ? 'net' : 'unknown'
    modeLog('设置搜索：异常 · ' + code)   // 只记固定类别，不落上游原文
    return { ok: false, error: settingsSearchErrText(code) }
  } finally {
    clearTimeout(to)
    if (parentAbort) parentAbort.removeEventListener('abort', onParent)
    settingsSearchRelease(wcId, requestId)
  }
}

ipcMain.handle('settings:search', async (e, o) => {
  if (!isSettingsSender(e)) return { ok: false, error: '没有权限' }
  const src = o && typeof o === 'object' ? o : {}
  const query = typeof src.query === 'string' ? src.query.replace(/\s+/g, ' ').trim() : ''
  const requestId = typeof src.requestId === 'string' ? src.requestId.slice(0, 80) : ''
  if (!query || !requestId || query.length > MAX_SETTINGS_SEARCH_QUERY) return { ok: false, error: settingsSearchErrText('badreq') }
  const wcId = e.sender.id
  // 每窗口最新单飞：新请求到来先把上一条掐掉（渲染端迟到的回包按 requestId 自行丢弃）
  settingsSearchCancel(wcId)
  const parent = new AbortController()
  settingsSearchTrack(wcId, requestId, parent, null)
  const r = await settingsSearchRun(wcId, query, requestId, parent.signal)
  if (!isSettingsSender(e)) return { ok: false, cancelled: true, error: settingsSearchErrText('cancelled') }
  return r
})
ipcMain.on('settings:search-cancel', (e, requestId) => {
  if (!isSettingsSender(e)) return
  const wcId = e.sender.id
  const a = settingsSearchActive.get(wcId)
  const id = typeof requestId === 'string' ? requestId : ''
  // 单向取消：只取消正在飞的那一条，别的 requestId 不做任何事
  if (a && (!id || a.requestId === id)) settingsSearchCancel(wcId)
})

ipcMain.on('settings:resize', (_e, h) => {
  if (settingsWin && !settingsWin.isDestroyed()) {
    const b = settingsWin.getBounds()
    settingsWin.setBounds({ x: b.x, y: b.y, width: b.width, height: Math.max(320, Math.min(820, Number(h) || 480)) })
    firstResizeDone(settingsWin)
  }
})
ipcMain.on('link:open', (_e, url) => { const u = String(url || ''); if (/^(https?:\/\/|mailto:)/i.test(u)) shell.openExternal(u) })
ipcMain.on('settings:openexternal', (_e, url) => { if (/^https?:\/\//i.test(String(url || ''))) shell.openExternal(String(url)) })
ipcMain.on('settings:delprofile', (_e, name) => {
  const n = String(name || '')
  orbState.apiProfiles = (orbState.apiProfiles || []).filter((x) => x.name !== n)
  if (orbState.activeProfile === n) orbState.activeProfile = null
  saveState()
  modeLog('API 档案已删除：' + n)
})

/* ---------------- API 档案：一键切换 + 拉模型 + 检测连接（v1.37） ----------------
 * 参照同类客户端的做法（Cherry Studio「模型服务」/ Chatbox「模型提供方 + Fetch + Check」/
 * Open WebUI「Connections + Model IDs」）：
 *   1) 档案点一下**立刻生效**（老写法只是回填表单，还得再点一次「保存」）；
 *   2) 切换入口放到右键菜单里（不用每次开设置卡）；
 *   3) 「获取列表」直接拉 /models，不用手打模型名；
 *   4) 「检测连接」发一条最小请求，明确告诉你连得上吗、快不快、**这个模型能不能看图**
 *      —— 灵珠全靠截图识别，选了不支持图片的模型以前只能在识别时报错。
 */
/* 检测连接用的探针图：**用 block.png（128×128），不要用 1×1**。
 * 实测：1×1 的 PNG 被网关直接退回「图片输入格式/解析错误」(1210)，于是检测会误报「不支持图片」——
 * 而 block.png 各家都认（实测 glm-5.3-flash / deepseek-v4-flash 都能看图并描述出来），
 * 而且它本来就在 asar 里（第 11 个打包文件），不用额外塞 base64 常量。 */
function probeImageB64() {
  try { return require('fs').readFileSync(path.join(__dirname, 'block.png')).toString('base64') } catch { return '' }
}
function menuProfiles() {
  const act = orbState.activeProfile
  return (orbState.apiProfiles || []).map((p) => ({ name: p.name, model: p.model || '未填模型', active: p.name === act }))
}
function currentApiLabel() {
  const cfg = apiCfg()
  return (orbState.activeProfile ? orbState.activeProfile + ' · ' : '') + (cfg.model || '未填模型')
}
/* 面板上闪一行提示（走 answer:notice：只改状态行，不碰正文/进度条）。
 * 面板没开就**不弹** —— 不为一句「已切到 xx」凭空开一块玻璃，
 * 切换的确认由菜单里的 ✓ 和设置卡的「当前：…」负责。 */
function pushNotice(text) {
  if (!answerWin || answerWin.isDestroyed()) return
  sendToWin(answerWin, 'answer:notice', String(text || ''))
}
function activateProfile(name) {
  const p = (orbState.apiProfiles || []).find((x) => x.name === name)
  if (!p) return false
  orbState.api = { baseUrl: p.baseUrl, apiKey: p.apiKey, model: p.model }
  orbState.activeProfile = p.name
  orbState.apiOnboarded = true
  saveState()
  modeLog('切换 API 档案 → ' + p.name + '（' + (p.model || '未填模型') + ' @ ' + (p.baseUrl || '默认地址') + '）')
  pushNotice('已切到 ' + p.name + ' · ' + (p.model || '未填模型'))
  if (menuWin && !menuWin.isDestroyed()) { try { menuWin.webContents.send('menu:update', { profiles: menuProfiles() }) } catch {} }
  return true
}
function httpErrText(e) {
  const t = errText(e)
  if (/abort/i.test(String(e && e.name)) || /aborted/i.test(t)) return '超时（8~15 秒没响应）—— 地址不对 / 需要代理 / 服务没起来'
  return t
}
/* 「获取列表 / 检测连接」要能用**表单里还没保存**的地址和 Key —— 否则填完新 Key 点检测，
 * 测的还是上一次存盘的老配置，白折腾。传参优先，没传才回落到已保存的配置。 */
function cfgFrom(o) {
  const c = apiCfg()
  const s = o || {}
  const pick = (v, d) => (typeof v === 'string' && v.trim()) ? v.trim() : d
  // v1.59：内测版填了自己的地址却没填 Key 时，不能拿内置 Key 去补（会发到别人的服务器上）
  const ownUrl = typeof s.baseUrl === 'string' && s.baseUrl.trim()
  return { baseUrl: pick(s.baseUrl, c.baseUrl).replace(/\/+$/, ''), apiKey: pick(s.apiKey, (ownUrl && usingBuiltinApi()) ? '' : c.apiKey), model: pick(s.model, c.model) }
}
ipcMain.on('settings:switchprofile', (_e, name) => { activateProfile(String(name || '')) })
// v1.38：设置卡里录一个新热键（'__default__' = 恢复默认 ⌘/）
ipcMain.handle('settings:sethotkey', (_e, acc) => setTranslateHotkey(acc))
ipcMain.handle('settings:fetchmodels', async (_e, o) => {
  const cfg = cfgFrom(o)
  const url = cfg.baseUrl.replace(/\/+$/, '') + '/models'
  const t0 = Date.now()
  try {
    const ac = new AbortController()
    const to = setTimeout(() => ac.abort(), 8000)
    let res
    try {
      res = await fetch(url, { headers: { Authorization: 'Bearer ' + cfg.apiKey }, signal: ac.signal })
    } finally { clearTimeout(to) }
    const text = await res.text().catch(() => '')
    const ms = Date.now() - t0
    if (!res.ok) return { ok: false, ms, url, error: '接口 ' + res.status + '：' + text.slice(0, 200) }
    let ids = []
    try {
      const j = JSON.parse(text)
      ids = (j.data || j.models || []).map((m) => (m && (m.id || m.name)) || '').filter(Boolean).map(String)
    } catch {}
    ids = Array.from(new Set(ids)).slice(0, 200)
    if (!ids.length) return { ok: false, ms, url, error: '有响应但没解析出模型列表（不是 OpenAI 格式？）：' + text.slice(0, 160) }
    modeLog('拉取模型列表：' + ids.length + ' 个（' + url + '，' + ms + 'ms）')
    return { ok: true, ms, url, models: ids }
  } catch (e) {
    return { ok: false, ms: Date.now() - t0, url, error: httpErrText(e) }
  }
})
ipcMain.handle('settings:test', async (_e, o) => {
  const cfg = cfgFrom(o)
  const t0 = Date.now()
  const body = Object.assign({
    model: cfg.model, stream: false, max_tokens: 24,
    messages: [{ role: 'user', content: [
      { type: 'text', text: '用两个字说这张图主要是什么' },
      { type: 'image_url', image_url: { url: 'data:image/png;base64,' + probeImageB64() } }   // 真图：只为探「能不能收图」
    ] }]
  }, thinkingOff(cfg))
  try {
    const ac = new AbortController()
    const to = setTimeout(() => ac.abort(), 15000)
    let res
    try {
      res = await fetch(cfg.baseUrl + '/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + cfg.apiKey },
        body: JSON.stringify(body), signal: ac.signal
      })
    } finally { clearTimeout(to) }
    const ms = Date.now() - t0
    const text = await res.text().catch(() => '')
    if (!res.ok) {
      // 中英文都要认：「不支持图片」的报错有的写 image/vision，智谱系写「图片输入格式/解析错误」
      const noVision = res.status === 400 && /image|multimodal|vision|图片|图像|多模态|视觉|content/i.test(text)
      modeLog('检测连接：失败 ' + res.status + (noVision ? '（带图片的请求被拒）' : '') + ' · ' + cfg.baseUrl + ' / ' + cfg.model)
      return { ok: false, ms, model: cfg.model, baseUrl: cfg.baseUrl, vision: false, status: res.status,
        error: (noVision ? '这个接口不收图片（或不吃这张图）—— 屏幕识别会失败，换一个能看图的模型/接口。' : '') + '接口 ' + res.status + '：' + text.slice(0, 220) }
    }
    let j = null
    try { j = JSON.parse(text) } catch {}
    const ch = j && j.choices && j.choices[0]
    const reply = ch && ch.message ? String(ch.message.content || '') : ''
    const echoModel = (j && j.model) ? String(j.model) : cfg.model
    modeLog('检测连接：成功 ' + ms + 'ms · 回显模型 ' + echoModel + '（' + cfg.baseUrl + '）')
    return { ok: true, ms, model: echoModel, baseUrl: cfg.baseUrl, vision: true, reply: reply.slice(0, 80) }
  } catch (e) {
    modeLog('检测连接：异常 · ' + httpErrText(e))
    return { ok: false, ms: Date.now() - t0, model: cfg.model, baseUrl: cfg.baseUrl, vision: false, error: httpErrText(e) }
  }
})

// v1.32 外观自定义：背景浓度 / 字号 / 对比度（作用于 answer + translate 两个阅读面板）
function appearanceOf() {
  const a = orbState.appearance || {}
  return { bgAlpha: a.bgAlpha || 0.50, fontSize: a.fontSize || 14, contrast: a.contrast || 0.92, theme: TH.normId(orbState.theme), panelSide: orbState.panelSide || 'left' }
}
function pushAppearance() {
  const ap = appearanceOf()
  for (const w of [answerWin, transWin, askWin, menuWin, settingsWin]) {   // v1.60：换配色时卡片和设置页也跟着变
    if (w && !w.isDestroyed()) { try { w.webContents.send('appearance:changed', ap) } catch {} }
  }
}
ipcMain.handle('appearance:get', () => appearanceOf())
ipcMain.on('appearance:set', (_e, o) => {
  try {
    const a = o || {}
    orbState.appearance = {
      bgAlpha: Math.min(0.85, Math.max(0.25, Number(a.bgAlpha) || 0.50)),
      fontSize: Math.min(18, Math.max(12, Number(a.fontSize) || 14)),
      contrast: Math.min(1, Math.max(0.7, Number(a.contrast) || 0.92))
    }
    saveState()
    pushAppearance()
  } catch {}
})

/* v1.46：小球左键分三档（判定在 orb.html，靠 e.detail 拿系统连击序号）
 *   单击 → 开历史记录（只开面板，不截图、不请求模型）
 *   双击 → 立即识别一次（原来的单击行为）
 *   三击 → 自动扫描开关（跟右键菜单里那一项同一套 toggleAutoScan，直接生效）
 * 旧的「双击弹自动扫描确认卡」在新映射下再无入口，showScanConfirm() 保留但已不被调用。 */
ipcMain.on('orb:click', () => {
  hideHint()
  modeLog('单击小球 → 开历史记录')
  answerDismissed = false
  const w = placeAnswer()
  // v1.48：新建的面板由 ready-to-show 淡入；这里 show() 会让它在内容画好前就冒出来
  if (w && !w.isDestroyed() && w.isVisible()) { topMost(w); orbToFront() }   // 复用已开面板时 topMost 会把球压下去，再抬回来
  sendToWin(w, 'answer:open-history', true)
  armPanelIdle()   // 看历史也算「在用面板」，别 10 分钟后被回收
})
ipcMain.on('orb:doubleclick', () => {
  hideHint(true)   // 马上要截屏：新手卡不能淡出着被截进去
  questionPauseUntil = 0
  modeLog('双击小球 → 立即识别')
  solve()   // v1.73：busy 由 solve() 统一发；若此刻有自动扫描在跑，solve 会把这次手动请求排进「单一待办」，不再静默丢掉
})
ipcMain.on('orb:tripleclick', () => {
  hideHint()
  modeLog('三击小球 → 自动扫描开关')
  toggleAutoScan()
})
ipcMain.on('orb:reset', resetOrb)
ipcMain.on('orb:quit', () => { app.quit() })
ipcMain.on('orb:move', (_e, dx, dy) => {
  if (!orbWin || orbWin.isDestroyed()) return
  const mx = Number(dx) || 0, my = Number(dy) || 0
  if (!mx && !my) return                                  // v1.43：合并后可能出现全 0 的批次
  const b = orbWin.getBounds()
  const p = clampOrbPos(b.x + mx, b.y + my)
  if (p.x === b.x && p.y === b.y) return                  // 没动就别 setBounds（透明窗每次都要重合成）
  orbWin.setBounds({ x: p.x, y: p.y, width: b.width, height: b.height })
  if (hintWin && !hintWin.isDestroyed()) placeHint(hintWin.getBounds().height)   // 新手卡跟着球走（含换屏）
  if (bubbleWin && !bubbleWin.isDestroyed() && bubbleWin.isVisible()) placeBubble(bubbleWin.__bubbleH)   // v1.76：球旁气泡也跟着球走
  if (moveSaveTimer) clearTimeout(moveSaveTimer)
  moveSaveTimer = setTimeout(saveState, 600)
})
/* v1.58：面板里的历史列表读落盘的那份（最多 200 条，含翻译）；老的内存 30 条只作兜底 */
function rememberAnswer(text, tag) {
  history.unshift({ t: Date.now(), text }); if (history.length > 30) history.pop()
  lastHistText = text
  lastHistId = historyAdd({ kind: 'solve', label: modeInfo(currentMode).label + (tag || ''), text })
}
ipcMain.handle('history:get', () => {
  const l = histStore().read()
  if (!l.length) return history.map((e) => ({ t: e.t, star: !!e.star, text: e.text }))
  // v1.61：把 t / star 一并给面板 —— 面板用 t 切收藏，star 决定 ☆ / ★
  return l.map((e) => ({ t: e.t, star: !!e.star, text: e.kind === 'translate' ? '**翻译**（' + (e.label || '') + '）\n\n' + e.src + '\n\n→ ' + e.text : e.text }))
})
/** v1.61：收藏 / 取消收藏一条历史（身份用时间戳 t）。收藏条目不受 200 条裁剪。 */
ipcMain.handle('history:star', (_e, t, star) => {
  const id = Number(t)
  if (!Number.isFinite(id) || id <= 0) return { ok: false, error: '没有这条历史' }
  try { X.runWriteGuard() } catch (err) { return { ok: false, error: lanErrorText(err) } }
  const list = histStore().read()
  let found = false
  const next = list.map((item) => {
    if (item.t !== id) return item
    found = true
    const copy = Object.assign({}, item)
    if (star) copy.star = true
    else delete copy.star
    return copy
  })
  if (!found) return { ok: false, error: '没有这条历史' }
  if (!histWriteAtomic(next)) return { ok: false, error: '写入失败' }
  return { ok: true, t: id, star: !!star }
})
ipcMain.on('answer:retry', () => { modeLog('点「重新识别」→ 不用缓存'); solve(false, true) })
ipcMain.handle('get:panelTitle', () => orbState.panelTitle || 'AI 屏幕解析')
// v1.24：把刚写进去的内容记成 lastClipText，否则 700ms 后「复制即译」会把它当成一次新的复制，
// 把自己刚复制的译文再翻一遍 → 又弹一次面板。
ipcMain.on('clipboard:write', (_e, text) => {
  if (!text) return
  const t = String(text)
  clipboard.writeText(t)
  lastClipText = t
  clipIgnoreUntil = Date.now() + CLIP_WATCH_MS * 2
})
ipcMain.on('answer:close', () => {
  // v1.73 D4：用户明确点关闭 = 取消这一轮（不设「出过正文才取消」的门槛 —— scout 那版只在 gotChunk 后取消，
  // 出正文之前关掉，答案还会再弹回来）。同时丢掉排队中的那一轮、清掉 busy：
  // 关掉之后既不该留一个转动的球，也不该强制把答案再弹回来。
  if (isSolving) {
    answerDismissed = true
    pendingSolve = null
    if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:idle')
  }
  fadeClose(answerWin)
})
ipcMain.on('answer:resize', (_e, h) => {
  if (!answerWin || answerWin.isDestroyed()) return
  const wa = screen.getPrimaryDisplay().workArea
  const target = Math.max(120, Math.min(h, answerMaxH()))
  const b = answerWin.getBounds()
  let y = b.y
  if (y + target > wa.y + wa.height) y = wa.y + wa.height - target
  if (y < wa.y) y = wa.y
  if (y === b.y && target === b.height) return   // v1.47：已到顶高还在长时，别再空 setBounds（玻璃窗每次都整窗重合成）
  answerWin.setBounds({ x: b.x, y, width: b.width, height: target })
})
ipcMain.on('open-privacy-pane', openPrivacyPane)
ipcMain.on('open-accessibility-pane', openAccessibilityPane)

/* ---- 划词翻译 IPC ---- */
ipcMain.on('trans:trigger', () => { translateSelection() })
ipcMain.on('trans:retry', () => { if (lastTransSrc) runTranslate(lastTransSrc, true) })   // v1.58：「重译」不用缓存

/* ---------------- 悬停查词（v1.22，v1.39 修复） ----------------
 * 面板里的单词鼠标停上去，弹小卡片：音标 / 词性 / 释义 / 例句。
 * 请求走**当前生效的 API 档案**（apiCfg()），非流式；主进程缓存 300 条（LRU），渲染层再缓存一层。
 *
 * ⚠️ v1.31 引入「自定义 API / API 档案」时把这里的 GATEWAY / MODEL / API_KEY 三个常量删了改名成 apiCfg()，
 * 但这一处漏改 —— 每次查词都在抛 ReferenceError，又被渲染层的 catch {} 吞掉，
 * 于是**永远显示「没查到这个词」**（用户截图里那句就是这个）。改 API 后查词也要跟着走新配置。
 *
 * v1.39 还改了两件事：
 *   1) 返回结构化结果 { ok, text | error }，让气泡能区分「接口没响应」和「真没这个词」；
 *   2) 词典式提问没结果时，再用一句最短的「只要中文短译」兜一次（很多模型对长提示词不买账）。
 */
const wordCache = new Map()
const wordMissAt = new Map()          // 没取到的词 60 秒内不再重打（悬浮反复触发才不会一直转圈）
const WORD_MISS_TTL = 60000
function wordPrompt(w) {
  return '你是双语词典。对用户给的单词或短语，用中文输出，不要寒暄、不要重复原词：\n'
    + '第1行：音标（仅英文词给，用 /.../ 包住）+ 词性缩写\n'
    + '第2~4行：核心释义，每行一条（最重要的在前，可带简短搭配）\n'
    + '最后1行：一个简短例句（原语言 + 中文翻译，用 — 分隔）\n'
    + '总长不超过 90 字。查询：' + w
}
async function wordAsk(w, prompt, maxTokens) {
  const cfg = apiCfg()
  const ac = new AbortController()
  const to = setTimeout(() => ac.abort(), 15000)
  try {
    let res
    try {
      res = await fetch(cfg.baseUrl + '/chat/completions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + cfg.apiKey },
        body: JSON.stringify(Object.assign({ model: cfg.model, stream: false, max_tokens: maxTokens, messages: [{ role: 'user', content: prompt }] }, thinkingOff(cfg))),
        signal: ac.signal
      })
    } finally { clearTimeout(to) }
    if (!res.ok) {
      const t = await res.text().catch(() => '')
      return { ok: false, error: '接口 ' + res.status + ' ' + t.slice(0, 80) }
    }
    const j = await res.json()
    const msg = (((j.choices || [])[0] || {}).message) || {}
    const out = String(msg.content || '').trim()
    // 思考模式下思维链占满预算时 content 是空的 —— 说清楚，别只说「没返回内容」
    if (!out && msg.reasoning_content) return { ok: false, error: '模型只输出了思考内容（思考预算吃光了输出）' }
    return out ? { ok: true, text: out } : { ok: false, error: '模型没返回内容' }
  } catch (e) {
    return { ok: false, error: httpErrText(e) }
  }
}
ipcMain.handle('trans:word', async (_e, raw) => {
  const w = String(raw || '').trim().toLowerCase().slice(0, 40)
  if (!w || !/^[a-z\u00c0-\u024f][a-z\u00c0-\u024f'’\-]{1,39}$/.test(w)) return { ok: false, error: '不是一个词' }
  if (wordCache.has(w)) return { ok: true, text: wordCache.get(w), cached: true }
  const missedAt = wordMissAt.get(w)
  if (missedAt && Date.now() - missedAt < WORD_MISS_TTL) return { ok: false, error: '刚才没取到，稍后再停一下试试', cached: true }
  let r = await wordAsk(w, wordPrompt(w), 700)
  if (!r.ok) {                                   // 长提示词没结果 → 用最短的一句兜一次
    const r2 = await wordAsk(w, '把下面这个词译成中文，只输出中文译文，不要解释、不要标点：\n' + w, 220)
    if (r2.ok) r = r2
  }
  if (r.ok) {
    if (wordCache.size >= 300) wordCache.delete(wordCache.keys().next().value)
    wordCache.set(w, r.text)
    wordMissAt.delete(w)
    transLog('查词 ' + w + '（缓存 ' + wordCache.size + '）')
    return { ok: true, text: r.text }
  }
  if (wordMissAt.size >= 300) wordMissAt.delete(wordMissAt.keys().next().value)
  wordMissAt.set(w, Date.now())
  transLog('查词失败 ' + w + '：' + r.error)
  return { ok: false, error: r.error }
})
ipcMain.on('trans:close', () => { if (isTranslating) transDismissed = true; fadeClose(transWin) })
ipcMain.on('trans:toggle-copy', () => { setCopyMode(!translateOnCopy) })
ipcMain.on('trans:set-lang', (_e, id) => { setTransLang(String(id || '')) })
ipcMain.handle('trans:langs', () => ({ id: transLang, label: langInfo(transLang).label, langs: LANGS.map((l) => ({ id: l.id, label: l.label, tip: l.tip })) }))
ipcMain.handle('trans:info', () => ({ hotkey: hotkeyLabel, hotkeyRaw: hotkeyRaw, copyMode: translateOnCopy }))
ipcMain.on('trans:resize', (_e, h) => {
  if (!transWin || transWin.isDestroyed()) return
  const b = transWin.getBounds()
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  const target = Math.max(140, Math.min(Number(h) || 200, transMaxH()))
  let y = b.y
  if (y + target > wa.y + wa.height) y = wa.y + wa.height - target
  if (y < wa.y) y = wa.y
  if (y === b.y && target === b.height) return firstResizeDone(transWin)   // v1.47：同 answer:resize
  transWin.setBounds({ x: b.x, y, width: b.width, height: target })
  firstResizeDone(transWin)
})

/* ---- 弹出菜单 IPC ---- */
ipcMain.on('orb:menu', () => { hideHint(); showMenu() })
/* 光晕留白区穿透：鼠标不在球体上时，窗口其余部分不挡后面的应用 */
ipcMain.on('orb:mouse-through', (_e, on) => {
  if (!orbWin || orbWin.isDestroyed()) return
  try { orbWin.setIgnoreMouseEvents(!!on, { forward: true }) } catch {}
  // v1.48：鼠标一到球上就把菜单窗口在后台建好（右键前通常还有几百毫秒），右键时就只剩刷新数据
  if (!on && !menuWin) setImmediate(() => { try { if (!menuWin) ensureMenuWin() } catch {} })
})
ipcMain.on('menu:close', () => closeMenu())
ipcMain.on('orb:dim-state', (_e, on) => { dimEnabled = !!on })
ipcMain.handle('menu:info', () => { let kd = 0; try { kd = kcardStore().dueCount(kcardNow()) } catch {}   // [KCard] 复习角标
  return ({ hotkey: hotkeyLabel, copy: !!translateOnCopy, scan: !!autoScanning, dim: !!dimEnabled, focus: focusMenuItem(), mode: currentMode, modes: allModeIds().map((id) => ({ id, label: modeInfo(id).label, hint: modeInfo(id).hint })), lang: transLang, langLabel: langInfo(transLang).label, intervalLabel: fmtInterval(scanIntervalMs), detailLabel: EXAM_DETAIL_LABEL[examDetail()], novice: !!orbState.novice, profiles: menuProfiles(), currentLabel: currentApiLabel(), kcardDue: kd, kcardCount: (() => { try { return kcardStore().count() } catch { return 0 } })() }) })
ipcMain.on('menu:ready', (_e, h) => presentMenu(h))
ipcMain.on('menu:resize', (_e, h) => {
  if (!menuWin || menuWin.isDestroyed() || !menuWin.isVisible()) return   // v1.48：隐藏时的高度由 menu:ready 带过来，摆位一起做
  const b = menuWin.getBounds()
  // v1.37：菜单多了「API 档案」区块（每档一行），460 会把它连同底部两项一起裁掉 —— 上限提到 620，
  // 并且长高之后重新贴边（原来只有 showMenu 里按初始 214 高算过一次翻转）。
  const H = Math.max(120, Math.min(Number(h) || 210, 620))
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  let y = b.y
  if (y + H > wa.y + wa.height - 8) y = Math.max(wa.y + 8, wa.y + wa.height - H - 8)
  menuWin.setBounds({ x: b.x, y, width: b.width, height: H })
})
ipcMain.on('menu:action', (_e, action) => {
  const [act0] = String(action || '').split(':')
  if (act0 !== 'lang-cycle' && !act0.startsWith('interval') && act0 !== 'detail' && act0 !== 'novice') closeMenu()   // 切语言/扫描间隔/解析强度/纯小白保持菜单打开，方便连点
  // v1.58：自定义模式的 id 自己带冒号（custom:c1），参数取第一个冒号后面的全部
  const actStr = String(action || ''), ci = actStr.indexOf(':')
  const act = ci < 0 ? actStr : actStr.slice(0, ci), arg = ci < 0 ? undefined : actStr.slice(ci + 1)
  switch (act) {
    case 'mode': setMode(arg); break
    case 'lang-cycle': {
      const i = LANGS.findIndex((l) => l.id === transLang)
      setTransLang(LANGS[(i + 1) % LANGS.length].id)
      break
    }
    case 'translate': translateSelection(); break
    case 'copy': setCopyMode(!translateOnCopy); break
    case 'scan': toggleAutoScan(); break
    case 'interval-dec':
    case 'interval-inc': {
      // v1.49：菜单里 −/+ 步进（原来只能在 10 秒 / 20 秒 / 1 分钟三档间轮换）；设置卡里自填的值不在阶梯上时，按大小就近跳
      const up = act === 'interval-inc'
      const next = up ? SCAN_INTERVAL_STEPS.find((v) => v > scanIntervalMs) : SCAN_INTERVAL_STEPS.slice().reverse().find((v) => v < scanIntervalMs)
      if (next) setIntervalMs(next)
      if (menuWin && !menuWin.isDestroyed()) { try { menuWin.webContents.send('menu:update', { intervalLabel: fmtInterval(scanIntervalMs) }) } catch {} }
      break
    }
    case 'detail': setExamDetail(EXAM_DETAILS[(EXAM_DETAILS.indexOf(examDetail()) + 1) % EXAM_DETAILS.length]); break
    case 'novice': setNovice(!orbState.novice); break
    case 'api': showSettings(); break
    case 'review': showKcardWin(); break   // [KCard] 复习知识卡
    case 'focus': toggleFocusGuard(); break   // v1.76：专注守护 —— 进行中点一下结束，没在守护就抓前台应用开始
    case 'profile': activateProfile(String(action || '').slice('profile:'.length)); break
    case 'dim': dimEnabled = !dimEnabled; if (orbWin && !orbWin.isDestroyed()) orbWin.webContents.send('orb:dim-toggle'); break
    case 'reset': resetOrb(); break
    case 'quit': app.quit(); break
  }
})

/* ---- 自动扫描确认卡 IPC（v1.25） ---- */
ipcMain.on('scan:start', () => {
  modeLog('确认卡：开启自动扫描')
  closeScanConfirm()
  startAutoScan()
})
ipcMain.on('scan:cancel', () => {
  if (scanWin && !scanWin.isDestroyed()) modeLog('确认卡：取消（未开启）')
  closeScanConfirm()
})
ipcMain.on('scan:resize', (_e, h) => {
  if (!scanWin || scanWin.isDestroyed()) return
  const b = scanWin.getBounds()
  scanWin.setBounds({ x: b.x, y: b.y, width: b.width, height: Math.max(90, Math.min(Number(h) || 130, SCAN_CARD_MAX_H)) })
})

/* ---- 追问 IPC（v1.26） ---- */
ipcMain.on('answer:ask-open', () => showAskCard())
// v1.65 通识：点名词词块 = 带着同一次识别的截图追问（不重新截屏，同安卓 onAsk 链路）
ipcMain.on('answer:ask-term', (_e, t) => {
  const term = String(t || '').trim().slice(0, 40)
  if (!term) return
  // q = 真正发给模型的完整追问；shown 只给面板看 —— 别把内部长提示词（termQuestion）暴露成「追问：…」
  askFollowUp(P.termQuestion(term), false, '解释「' + term + '」')
})
ipcMain.on('ask:send', (_e, text, withShot) => {
  const raw = String(text || '').trim()
  askWithShot = !!withShot   // 记住这次的勾选，下次打开追问卡默认一样
  closeAskCard()
  // v1.60：what / why 开关（答案面板底部追问栏旁边固定两个）—— 输入框空着、开关开着也能发
  const q = P.styledAsk(raw, askWhat, askWhy)
  if (!q) return
  askFollowUp(q, askWithShot, P.askShown(raw, askWhat, askWhy))
})
ipcMain.handle('ask:info', () => ({ withShot: askWithShot, what: askWhat, why: askWhy }))
// v1.60 追问开关：面板和追问卡共用一份状态（本次运行有效，同安卓不落盘）
let askWhat = false, askWhy = false
function pushAskToggles() {
  const st = { what: askWhat, why: askWhy }
  if (answerWin && !answerWin.isDestroyed()) sendToWin(answerWin, 'ask:toggles', st)
  if (askWin && !askWin.isDestroyed()) { try { askWin.webContents.send('ask:toggles', st) } catch {} }
}
ipcMain.on('ask:toggle', (_e, which) => {
  if (which === 'what') askWhat = !askWhat
  else if (which === 'why') askWhy = !askWhy
  pushAskToggles()
})
ipcMain.handle('ask:toggles', () => ({ what: askWhat, why: askWhy }))
// v1.60 面板左上角的模式抽屉：只列模式（通用 / 做题 / 自定义…），点了就切
ipcMain.handle('answer:modes', () => ({ mode: currentMode, modes: allModeIds().map((id) => ({ id, label: modeInfo(id).label })) }))
ipcMain.on('answer:set-mode', (_e, id) => {
  const m = String(id || '')
  if (m === currentMode || !allModeIds().includes(m)) return
  setMode(m)
  pushNotice('已切到「' + modeInfo(m).label + '」· 双击小球按这个模式识别')
})
ipcMain.on('ask:cancel', () => closeAskCard())

/* ================= [KCard] 知识卡接线（v1.72）=================
 * 数据层和调度层在 knowledge.js / fsrs.js（纯 JS，已测）；这一段只做**接线**：
 *   ① 存卡   答案面板「存为复习卡」→ kcard:add
 *   ② 复习页 review.html（正面 → 翻面 → 四档评分）+ 托盘菜单入口
 *   ③ 卡库   同一个 review.html 的第二个 tab（浏览 / 搜索 / 删除）
 *   ④ 到期检查 启动时 + 每小时 dueCount>0 才发系统通知，点通知开复习页
 * 全部包在 [KCard] 标记里，回退 = 删掉这一段 + preload 里 kcard:* 那几行 + answer.html 的按钮。
 * 数据格式（knowledge.json 数组 + fsrs snake_case）由 knowledge.js 保证，这里不碰。
 */
const K = require('./knowledge')   // v1.72：知识卡（存卡 / 调度 / 文案）
const { Notification } = require('electron')

/** 复习页窗口句柄（复用式，跟设置卡一个套路）。 */
let kcardWin = null
/** 到期检查定时器。 */
let kcardDueTimer = null

/** 卡库 Store（userData/knowledge.json，跟安卓同名同格式）。 */
let _kcardStore = null
function kcardStore() {
  if (!_kcardStore) _kcardStore = K.storeIn(app.getPath('userData'))
  return _kcardStore
}
/** 现在的时间戳（毫秒）。抽一个函数是为了测试里能换掉。 */
const kcardNow = () => Date.now()

/* ---- ① 存卡：面板 → IPC → Knowledge.Store.add ---- */

/**
 * 从面板这一轮的原文抽出 {front, back} 并存进库。
 * 面板把原文整段丢过来（它手里才有这一轮最终的 rawText），抽正反面交给 knowledge.fromTranscript
 * —— 那是跟安卓逐行对齐的那份逻辑，main 不重复实现一遍。
 * 返回 {ok, id, reason}：reason 是 'empty' / 'dup' / 'full' / 'save'。
 */
function kcardAddFromTranscript(raw, deck) {
  const fb = K.fromTranscript(String(raw || ''))
  const r = kcardStore().add(fb.front, fb.back, K.Source.ANSWER, kcardNow(), deck)
  return r.ok ? { ok: true, id: r.id, reason: '', front: fb.front } : { ok: false, id: 0, reason: r.reason, front: fb.front }
}
ipcMain.handle('kcard:add', (_e, raw, deck) => {
  const r = kcardAddFromTranscript(raw, deck)
  modeLog('知识卡存卡：' + (r.ok ? '成功 id=' + r.id + ' front=' + r.front.slice(0, 20)
    : '失败 ' + r.reason + (r.reason === 'guard' && _lanRecoverError ? '（' + _lanRecoverError.message + '）' : '')))
  // 存完把到期数推给菜单，菜单上的角标立刻跟上
  pushKcardMenuBadge()
  // 面板上闪一行结果（面板只有 answer:notice 这一条提示通道）
  const tip = r.ok ? '✓ 已存进知识卡 · 之后按 FSRS 安排复习'
    : r.reason === 'dup' ? '这张已经存过了'
      : r.reason === 'full' ? '卡库满了（上限 ' + K.MAX + ' 张）'
        : r.reason === 'guard' ? '同步恢复失败，数据未写入'
          : '这一轮抽不出可存的内容'
  pushNotice(tip)
  return r
})
/** 面板问「这一轮是不是已经存过了」（按钮要显示 ✓ 而不是「存」）。 */
ipcMain.handle('kcard:has', (_e, raw) => {
  const fb = K.fromTranscript(String(raw || ''))
  if (!fb.front) return false
  return K.hasDup(kcardStore().all(), K.DEFAULT_DECK, fb.front)
})

/* ---- ② + ③ 复习页 / 卡库 ---- */

/** 复习页当前这一轮队列（渲染层不持有 FSRS 状态，主进程算好了给它）。 */
let kcardQueue = []
let kcardQueueAt = 0

/** 组一份「复习页要的队列」：到期的按到期排前面的那批。 */
function kcardQueuePayload() {
  const now = kcardNow()
  const all = kcardStore().all()
  const due = K.dueFirst(all, now)
  return { now: now, due: due.map((c) => kcardView(c, now)), total: due.length, count: all.length }
}
/** 一张卡 → 渲染层要的形状（fsrs 内部字段不外泄，只给展示用的）。 */
function kcardView(c, now) {
  return { id: c.id, deck: c.deck, front: c.front, back: c.back, source: c.source, createdAt: c.createdAt,
    due: c.fsrs.due, state: c.fsrs.state, reps: c.fsrs.reps, lapses: c.fsrs.lapses, daysLeft: K.daysLeft(c, now) }
}

ipcMain.handle('kcard:queue', () => {
  const p = kcardQueuePayload()
  kcardQueue = p.due
  kcardQueueAt = p.now
  return { ok: true, cards: kcardQueue, total: p.total, count: p.count, now: p.now, header: K.header(p.total), emptyHint: K.EMPTY_HINT }
})
/** 评分一张：走 FSRS 重排 due，然后回新的队列（这一张从队列里掉了，队列自然变短）。 */
ipcMain.handle('kcard:answer', (_e, id, rating) => {
  const ok = kcardStore().answer(Number(id), rating, kcardNow())
  if (!ok) return { ok: false, reason: 'answer' }
  const p = kcardQueuePayload()
  kcardQueue = p.due
  kcardQueueAt = p.now
  pushKcardMenuBadge()
  modeLog('知识卡评分 id=' + id + ' rating=' + rating + ' → 剩 ' + p.total + ' 张')
  return { ok: true, cards: p.due, total: p.total, count: p.count, now: p.now }
})
/** 卡库 tab：全部卡（搜索在渲染层做，量级 5000 一次性给过去比来回 IPC 划算）。 */
ipcMain.handle('kcard:all', () => {
  const now = kcardNow()
  const all = K.dueFirst(kcardStore().all(), now)
  return { cards: all.map((c) => kcardView(c, now)), count: all.length, now: now }
})
ipcMain.handle('kcard:remove', (_e, id) => {
  const ok = kcardStore().remove(Number(id))
  if (ok) { pushKcardMenuBadge(); modeLog('知识卡删除 id=' + id) }
  return { ok: ok }
})
ipcMain.handle('kcard:dueCount', () => ({ due: kcardStore().dueCount(kcardNow()), count: kcardStore().count() }))
ipcMain.on('kcard:close', () => closeKcardWin())
// 复习页高度跟着内容走（空队列提示只有两行，翻面后正文变高）—— 只调高度，不动 x/y
ipcMain.on('kcard:resize', (_e, h) => {
  if (!kcardWin || kcardWin.isDestroyed()) return
  const b = kcardWin.getBounds()
  kcardWin.setBounds({ x: b.x, y: b.y, width: b.width, height: Math.max(420, Math.min(Number(h) || 560, 900)) })
})

/** 打开复习页（已开着就聚焦）。托盘菜单和到期通知都走这里。 */
function showKcardWin() {
  if (kcardWin && !kcardWin.isDestroyed()) { kcardWin.focus(); return }
  const wa = screen.getPrimaryDisplay().workArea
  const width = 520, height = 560
  const b = orbWin && !orbWin.isDestroyed() ? orbWin.getBounds() : { x: wa.x + wa.width - 100, y: wa.y }
  const x = Math.max(wa.x + 8, Math.min(b.x - width + 90, wa.x + wa.width - width - 8))
  const y = Math.min(wa.y + wa.height - height - 40, Math.max(wa.y + 8, b.y))
  kcardWin = new BrowserWindow({
    width: width, height: height, x: x, y: y, title: APP_NAME + ' 知识卡',
    frame: false, transparent: true, resizable: false, movable: true,
    alwaysOnTop: true, skipTaskbar: true, show: false, minWidth: 380, minHeight: 420,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false }
  })
  kcardWin.setAlwaysOnTop(true, 'screen-saver', LAYER_POPUP)
  hideFromMissionControl(kcardWin)
  kcardWin.loadFile(path.join(__dirname, 'review.html'))
  const kw = kcardWin
  kcardWin.on('closed', () => { if (kcardWin === kw) kcardWin = null })
  // accessory 应用不显式抢焦点的话键盘输入全进了别的 App
  kcardWin.once('ready-to-show', () => { if (kcardWin !== kw || kw.isDestroyed()) return; try { app.focus({ steal: true }) } catch {}; revealWin(kw, true) })
  modeLog('打开知识卡复习页')
}
function closeKcardWin() { fadeClose(kcardWin); kcardWin = null }

/* ---- ④ 到期检查 → 系统通知 ---- */

/** 上次通知过的到期张数：同一批卡不每小时重复骚扰（评完分到期数变了才再提醒）。 */
let kcardNotifiedFor = -1

/** 该不该现在发通知（纯判断，测试直接喂这个）。 */
function kcardShouldNotify(due) { return K.shouldNotify(due) }

/** 检查一次：dueCount>0 才发通知；点了通知打开复习页。没到期一条都不发。 */
function checkKcardDue(force) {
  let due = 0
  try { due = kcardStore().dueCount(kcardNow()) } catch (e) { modeLog('知识卡到期检查失败：' + (e && e.message)); return }
  if (!kcardShouldNotify(due)) {
    if (due === 0) kcardNotifiedFor = -1   // 清零后允许下次重新提醒
    return
  }
  if (!force && due === kcardNotifiedFor) return   // 同一批到期，不重复弹
  kcardNotifiedFor = due
  try {
    const n = new Notification({ title: K.notiTitle(due), body: K.notiBody(due), silent: false })
    n.on('click', () => showKcardWin())
    n.show()
    modeLog('知识卡到期通知：' + due + ' 张')
  } catch (e) { modeLog('知识卡通知发不出去：' + (e && e.message)) }
}
/** 启动时查一次 + 每小时查一次（unref：不让这个定时器吊住进程）。 */
function armKcardDueCheck() {
  if (kcardDueTimer) clearInterval(kcardDueTimer)
  setTimeout(() => checkKcardDue(false), 8000)          // 启动给 8 秒：别跟一堆启动初始化抢主线程
  kcardDueTimer = setInterval(() => checkKcardDue(false), 3600000)
  if (kcardDueTimer && typeof kcardDueTimer.unref === 'function') kcardDueTimer.unref()
}

/** 菜单上的「复习知识卡 N」角标。 */
function pushKcardMenuBadge() {
  if (!menuWin || menuWin.isDestroyed()) return
  let due = 0
  try { due = kcardStore().dueCount(kcardNow()) } catch {}
  try { menuWin.webContents.send('menu:update', { kcardDue: due }) } catch {}
}

/* ---------------- v1.60：桌面内置更新通道（内测 / 公测） ----------------
 * 用户要求「都内置更新通道」：设置页版本那一行能「检查更新」和「下载最新版」。契约：
 *   - 源按渠道：公测 desktop-manifest.json / 内测 desktop-manifest-internal.json（CNB raw，public 仓库，不含密钥）；
 *   - 版本比较只在 update.js 里做：三段数值严格更大才算「新版本」，相等就是「已是最新版」，绝不虚构 new；
 *   - 只要本平台有资产且通道版本不低于本机，就允许下载：同版本重新下载当前通道最新版，更高版本升级；
 *     通道版本更低时明确提示「本机版本高于通道」并禁用下载（绝不诱导用「下载最新版」把用户拉回旧版本）；
 *   - 渲染进程拿不到任意 URL / 路径：只发「检查」「下载」两个无参调用，下载的目标由主进程用上次 check 通过的资产决定；
 *   - 只接受 http(s) + 64 位 sha256；平台资产缺失 = 明确「暂不支持当前平台」；
 *   - 下载走 http(s) stream 直接落盘（不整包进内存），跟随最多 4 次重定向（CNB raw 大 zip 常 302 到存储 URL），
 *     边下边算 sha256，校验的是真正落盘的最终字节；校验过才改名成最终 zip 并 showItemInFolder；
 *     失败只删自己的 .part，不动用户其它文件，也绝不打开未校验的 zip；同一时刻只允许一个下载，避免并发写同一个 .part。
 * 路径放在 userData/updates —— 不影响原 state.json 设置。
 */
let updateLastCheck = null    // 上次 check 通过、且本平台有资产的 manifest 结果（下载只认它）
let updateDownloading = false // 同一时刻只允许一个下载
function updatesDir() { return path.join(app.getPath('userData'), 'updates') }
function updateChannel() { return 'public' }

/** 取 manifest 文本：Node https/http，带大小上限与超时（JSON 本就小，上限只是兜底，避免异常源撑爆内存）。 */
function fetchManifest(url, redirects) {
  return new Promise((resolve, reject) => {
    let u
    try { u = new URL(url) } catch { return reject(new Error('源地址无效')) }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return reject(new Error('源必须是 http(s)'))
    const mod = u.protocol === 'https:' ? https : http
    // UA 必须固定 ASCII：APP_NAME 内测名「Ling 内测」含 CJK，Node 设 header 会抛 Invalid character in header content
    const req = mod.get(u, { headers: { 'User-Agent': 'Ling/' + app.getVersion(), Accept: 'application/json' } }, (res) => {
      const code = res.statusCode || 0
      if (code >= 300 && code < 400 && res.headers.location) {
        res.resume()
        if ((redirects || 0) >= 4) return reject(new Error('重定向次数过多'))
        return resolve(fetchManifest(new URL(res.headers.location, u).toString(), (redirects || 0) + 1))
      }
      if (code !== 200) { res.resume(); return reject(new Error('源返回 HTTP ' + code)) }
      let size = 0
      const chunks = []
      res.on('data', (d) => {
        size += d.length
        if (size > 256 * 1024) { req.destroy(new Error('manifest 异常大')); return }
        chunks.push(d)
      })
      res.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
      res.on('error', reject)
    })
    req.on('error', reject)
    req.setTimeout(15000, () => req.destroy(new Error('源请求超时')))
  })
}

ipcMain.handle('update:check', async () => {
  updateLastCheck = null   // 先清上一次的结果：这次检查失败 / manifest 非法时，下载绝不能拿上次缓存的资产
  const channel = updateChannel()
  const url = U.manifestUrl()
  const currentVersion = app.getVersion()
  const platformKey = U.platformKey(process.platform, process.arch)
  const base = { channel, sourceUrl: url, currentVersion, platformKey }
  try {
    const text = await fetchManifest(url, 0)
    let obj
    try { obj = JSON.parse(text) } catch { return Object.assign({}, base, { ok: false, status: 'error', error: '源文件不是合法 JSON' }) }
    const v = U.validateManifest(obj)
    if (!v.ok) return Object.assign({}, base, { ok: false, status: 'error', error: v.error })
    const manifest = v.manifest
    // 平台资产缺失 / 通道版本更低都不允许下载：Windows 在清单省略 win32-x64 时得到
    // 「当前平台暂无安装包」，绝不会退回去下旧 win 包。
    const plan = U.downloadPlan(manifest, process.platform, process.arch, currentVersion)
    const asset = plan.asset
    const canDownload = plan.canDownload
    updateLastCheck = canDownload ? { version: manifest.version, notes: manifest.notes, key: asset.key, url: asset.url, sha256: asset.sha256 } : null
    return Object.assign({}, base, {
      ok: true, status: U.statusFor(manifest.version, currentVersion),
      latestVersion: manifest.version, notes: manifest.notes,
      assetAvailable: !!asset, unsupported: !asset, canDownload: canDownload,
      message: plan.message
    })
  } catch (e) {
    return Object.assign({}, base, { ok: false, status: 'error', error: (e && e.message) || String(e) })
  }
})

/**
 * 打开最终 200 的 http(s) 响应流，跟随最多 maxRedirects 次重定向
 * （CNB raw 的大 zip 常 301/302/307 到存储 URL，绝不能把 3xx 当失败）。
 * 每一跳：res.resume() 丢弃这一跳的响应体、释放旧 stream / request，再递归下一跳；非 http(s) 直接拒。
 * 只有拿到 200 才 resolve 响应流，调用方此后才建落盘流。
 */
function openStream(url, maxRedirects) {
  return new Promise((resolve, reject) => {
    let u
    try { u = new URL(url) } catch { return reject(new Error('资产地址无效')) }
    if (u.protocol !== 'https:' && u.protocol !== 'http:') return reject(new Error('资产地址必须是 http(s)'))
    const mod = u.protocol === 'https:' ? https : http
    const req = mod.get(u, { headers: { 'User-Agent': 'Ling/' + app.getVersion() } }, (res) => {   // UA 固定 ASCII（同 fetchManifest，勿用含 CJK 的 APP_NAME）
      const code = res.statusCode || 0
      if (code >= 300 && code < 400 && res.headers.location) {
        res.resume()   // 丢弃这一跳的响应体，释放旧 stream / socket
        if (maxRedirects <= 0) return reject(new Error('重定向次数过多'))
        let next
        try { next = new URL(res.headers.location, u).toString() } catch { return reject(new Error('重定向地址无效')) }
        if (!U.isHttpUrl(next)) return reject(new Error('重定向到非 http(s) 地址'))
        return resolve(openStream(next, maxRedirects - 1))
      }
      if (code !== 200) { res.resume(); return reject(new Error('下载返回 HTTP ' + code)) }
      resolve(res)
    })
    req.on('error', reject)
    req.setTimeout(30000, () => req.destroy(new Error('下载超时')))
  })
}

/**
 * 下载 url 到 dest，边下边算 sha256，结束校验「真正落盘的最终字节」。
 * 用 stream/promises.pipeline：resolve 时目标文件已 close，不存在「finish 了但还没写完 / 半截 part / 悬挂 promise」。
 * 失败时销毁落盘流并等它 close，调用方才能安全清 .part；绝不把未校验的文件当成品。
 */
async function downloadToFile(url, dest, sha) {
  const res = await openStream(url, 4)   // 跟随最多 4 次重定向，拿到最终 200 才落盘
  const hash = crypto.createHash('sha256')
  const meter = new Transform({ transform(chunk, _enc, cb) { hash.update(chunk); cb(null, chunk) } })
  const out = stateFs.createWriteStream(dest, { flags: 'w', mode: 0o600 })
  try {
    await pipeline(res, meter, out)   // 结束即 out 已 close
  } catch (e) {
    try { out.destroy() } catch {}
    if (!out.closed) await new Promise((r) => out.once('close', r))   // 等落盘流真正关闭再抛，避免异步 close 竞态
    throw e
  }
  const got = hash.digest('hex')
  if (got !== sha) throw new Error('sha256 校验不通过')
}

/** 把校验通过的 .part 落到最终名。
 *  POSIX：rename 覆盖是原子的 —— 要么换成新包，要么维持旧包，不存在「先删旧包再失败」的空窗。
 *  Windows：rename 不能覆盖已存在文件，先把旧包移成备份再换名；任何一步失败都把旧包换回来，绝不先删旧包。 */
function commitDownload(partPath, finalPath) {
  if (process.platform !== 'win32') return stateFs.renameSync(partPath, finalPath)
  const backup = finalPath + '.old'
  let backedUp = false
  try {
    if (stateFs.existsSync(finalPath)) {
      try { stateFs.rmSync(backup, { force: true }) } catch {}
      stateFs.renameSync(finalPath, backup)
      backedUp = true
    }
    stateFs.renameSync(partPath, finalPath)
    if (backedUp) { try { stateFs.rmSync(backup, { force: true }) } catch {} }
  } catch (e) {
    if (backedUp) { try { stateFs.renameSync(backup, finalPath) } catch {} }   // 换名失败 → 旧包原样恢复
    throw e
  }
}

ipcMain.handle('update:download', async () => {
  const info = updateLastCheck
  if (!info) return { ok: false, error: '没有可下载的更新，请先「检查更新」' }
  if (!U.isHttpUrl(info.url) || !U.isSha256(info.sha256)) return { ok: false, error: '资产地址或校验值非法' }
  if (updateDownloading) return { ok: false, error: '已有下载在进行中' }
  updateDownloading = true
  const dir = updatesDir()
  const finalPath = path.join(dir, U.assetFileName(info.version, info.key))
  const partPath = finalPath + '.part'
  try {
    stateFs.mkdirSync(dir, { recursive: true })
    try { stateFs.rmSync(partPath, { force: true }) } catch {}
    await downloadToFile(info.url, partPath, info.sha256)
    commitDownload(partPath, finalPath)   // 不提前删旧包：POSIX 原子覆盖，Windows 备份换名
    try { shell.showItemInFolder(finalPath) } catch {}
    modeLog('更新包已下载并通过校验：' + finalPath)
    return { ok: true, path: finalPath, version: info.version }
  } catch (e) {
    try { stateFs.rmSync(partPath, { force: true }) } catch {}    // 失败只清自己的 .part（此时落盘流已关闭）
    modeLog('更新包下载失败：' + ((e && e.message) || e))
    return { ok: false, error: (e && e.message) || String(e) }
  } finally {
    updateDownloading = false
  }
})

/* ================= v1.76：专注守护 + 陪伴（接线层） =================
 * 状态机 / 排程在 focusguard-main.js 与 companion-main.js；这一段只补它们看不到的东西：
 *   · 球旁气泡窗口（showBubble 单例：内容替换 + 重置 TTL；focusable:false 绝不抢前台应用的焦点）
 *   · 前台应用探测（两步 lsappinfo；spawnAsync 不回传 stdout，所以这里是多收了 stdout 的兄弟实现）
 *   · 陪伴 AI 短句（复用 wordAsk 的接口配置 / 超时；失败给 null，由模块回退内置句）
 *   · 小球守护环（orb:guard，值没变不重发）+ 菜单那一行的状态
 * 模块缺失 / 调用出错只记日志停用对应功能，不影响识别 / 翻译 / 转写。 */

/** 专注守护模块的安全调用包装（模块没加载 / 出错都返回 undefined，不让主进程跟着挂） */
function fgCall(name, ...args) {
  if (!focusGuard || typeof focusGuard[name] !== 'function') return undefined
  try { return focusGuard[name].apply(focusGuard, args) } catch (e) { modeLog('专注守护 ' + name + ' 出错：' + ((e && e.message) || e)); return undefined }
}
/** 陪伴模块的同款包装 */
function cpCall(name, ...args) {
  if (!companion || typeof companion[name] !== 'function') return undefined
  try { return companion[name].apply(companion, args) } catch (e) { modeLog('陪伴 ' + name + ' 出错：' + ((e && e.message) || e)); return undefined }
}

/* spawnAsync 只留 stderr（截图那套不需要 stdout），lsappinfo 的结果在 stdout ——
 * 这里补一个同样异步、同样带超时、同样永不 reject 的版本，只多收 stdout。 */
function spawnOut(cmd, args, timeoutMs) {
  return new Promise((resolve) => {
    let done = false
    const finish = (code, out, err) => { if (!done) { done = true; resolve({ code, out, err }) } }
    let child
    try { child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true }) } catch (e) { return finish(-1, '', errText(e)) }
    let out = '', err = ''
    if (child.stdout) child.stdout.on('data', (b) => { if (out.length < 4000) out += String(b).slice(0, 4000) })
    if (child.stderr) child.stderr.on('data', (b) => { if (err.length < 400) err += String(b).slice(0, 400) })
    const timer = setTimeout(() => { try { child.kill('SIGKILL') } catch {} ; finish(-1, out, 'timeout') }, timeoutMs)
    child.on('error', (e) => { clearTimeout(timer); finish(-1, out, errText(e)) })
    child.on('close', (code) => { clearTimeout(timer); finish(code, out, err) })
  })
}

/* 前台应用：`lsappinfo front` 拿 ASN，再 `info -only bundleid,name` 拿包名和显示名。
 * 任何一步失败都回 null —— focusguard-main 把 null / 自己都当「不算分心」，不会误报。 */
const FRONTMOST_TIMEOUT_MS = 1500
async function frontAppInfo() {
  if (process.platform !== 'darwin') return null
  const front = await spawnOut('lsappinfo', ['front'], FRONTMOST_TIMEOUT_MS)
  const asn = String((front && front.out) || '').trim().split(/\r?\n/)[0].trim()
  if (!front || front.code !== 0 || !/^ASN:/i.test(asn)) return null
  const info = await spawnOut('lsappinfo', ['info', '-only', 'bundleid,name', '-app', asn], FRONTMOST_TIMEOUT_MS)
  if (!info || info.code !== 0) return null
  const txt = String(info.out || '')
  const bid = /"CFBundleIdentifier"\s*=\s*"([^"]*)"/.exec(txt)
  const nm = /"LSDisplayName"\s*=\s*"([^"]*)"/.exec(txt)
  const bundleId = bid ? bid[1].trim() : ''
  return bundleId ? { bundleId: bundleId, appName: nm ? nm[1].trim() : '' } : null
}
async function runFrontmost() {
  const a = await frontAppInfo()
  return (a && a.bundleId) || null
}

/* 自己的 bundleId：小球 / 设置卡被点成前台时，专注守护不该把这算成分心。
 * 从可执行文件旁边的 Info.plist 读（打包后 / 开发态都成立），读不到回 appId 兜底。 */
let selfBundleIdCache = null
function selfBundleId() {
  if (selfBundleIdCache != null) return selfBundleIdCache
  let id = ''
  try {
    const plist = path.join(path.dirname(path.dirname(app.getPath('exe'))), 'Info.plist')
    const xml = stateFs.readFileSync(plist, 'utf8')
    const m = /<key>CFBundleIdentifier<\/key>\s*<string>([^<]+)<\/string>/.exec(xml)
    if (m && m[1]) id = m[1].trim()
  } catch {}
  if (!id) id = app.isPackaged ? 'com.ling.opensource' : 'com.github.Electron'
  selfBundleIdCache = id
  modeLog('专注守护：自己的 bundleId = ' + id)
  return id
}

/* 陪伴 AI 短句：只要一句 <=20 字的中文短句；失败 / 没配接口都给 null，
 * 模块里还有 14 秒竞速和长度兜底，这里不再叠加超时。 */
async function companionAiLine() {
  if (!apiReady()) return null
  try {
    const r = await wordAsk('', '你是桌面上的小助手，安静地陪用户用电脑。只输出一句不超过 20 个字的中文短句：可以是鼓励、一句小知识、或一个 Ling 的功能提示（划词翻译 / 知识卡 / 追问都行）。不要引号、不要表情符号、不要解释、不要重复用户名。', 120)
    return (r && r.ok) ? String(r.text || '') : null
  } catch { return null }
}

/* 小球守护环：模块每 2 秒报一次状态 —— 值没变不重发（这就是防抖），存态给 menu 用。
 * on 从有到无（或反过来）= 会话起止，菜单开着就顺手刷那一行。 */
let orbGuardState = { on: false, distract: false }
function pushOrbGuard(payload) {
  const p = { on: !!(payload && payload.on), distract: !!(payload && payload.distract) }
  if (p.on === orbGuardState.on && p.distract === orbGuardState.distract) return
  const wasOn = orbGuardState.on
  orbGuardState = p
  sendToWin(orbWin, 'orb:guard', p)
  if (p.on !== wasOn) pushFocusMenuUpdate()
}

/* ---- 球旁气泡（单例窗口） ---- */
const BUBBLE_W = 320
const BUBBLE_GAP = 12
const BUBBLE_PAD = 8
const BUBBLE_DEFAULT_H = 64
const BUBBLE_MAX_H = 240
const BUBBLE_TTL_DEFAULT = 4200
let bubbleWin = null
let bubbleTTL = null
function isBubbleSender(e) {
  return !!(e && e.sender && bubbleWin && !bubbleWin.isDestroyed() && e.sender === bubbleWin.webContents)
}
/* 贴球摆位，夹取规则同 placeHint：右边放不下翻左边，两边都放不下取更宽的一侧 */
function placeBubble(height) {
  if (!orbWin || orbWin.isDestroyed() || !bubbleWin || bubbleWin.isDestroyed()) return
  const b = orbWin.getBounds()
  const wa = (screen.getDisplayMatching(b) || screen.getPrimaryDisplay()).workArea
  const H = Math.max(36, Math.min(BUBBLE_MAX_H, Math.round(Number(height) || BUBBLE_DEFAULT_H)))
  const need = BUBBLE_W + BUBBLE_GAP
  const rightSpace = (wa.x + wa.width) - (b.x + b.width)
  const leftSpace = b.x - wa.x
  let side
  if (rightSpace >= need) side = 'right'
  else if (leftSpace >= need) side = 'left'
  else side = rightSpace >= leftSpace ? 'right' : 'left'
  let x = side === 'right' ? b.x + b.width + BUBBLE_GAP : b.x - BUBBLE_W - BUBBLE_GAP
  x = Math.min(Math.max(x, wa.x + BUBBLE_PAD), wa.x + wa.width - BUBBLE_W - BUBBLE_PAD)
  let y = Math.round(b.y + b.height / 2 - H / 2)
  y = Math.min(Math.max(y, wa.y + BUBBLE_PAD), wa.y + wa.height - H - BUBBLE_PAD)
  const nb = { x: Math.round(x), y, width: BUBBLE_W, height: H }
  const cur = bubbleWin.getBounds()
  if (cur.x !== nb.x || cur.y !== nb.y || cur.width !== nb.width || cur.height !== nb.height) bubbleWin.setBounds(nb)
}
function ensureBubbleWin() {
  if (bubbleWin && !bubbleWin.isDestroyed()) return bubbleWin
  const w = bubbleWin = new BrowserWindow({
    width: BUBBLE_W, height: BUBBLE_DEFAULT_H, x: 0, y: 0,
    frame: false, transparent: true, hasShadow: false, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, focusable: false, show: false,
    title: APP_NAME + ' 提示', fullscreenable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: false }
  })
  w.__bubbleH = BUBBLE_DEFAULT_H
  topMost(w, LAYER_POPUP)
  hideFromMissionControl(w)
  w.webContents.on('did-finish-load', () => { if (bubbleWin === w) winReady(w) })
  w.on('closed', () => {
    if (bubbleWin !== w) return
    bubbleWin = null
    clearTimeout(bubbleTTL)
    bubbleTTL = null
  })
  w.loadFile(path.join(__dirname, 'bubble.html'))
  revealAfterFirstResize(w, false, 320)   // 首帧内容画好（第一次 bubble:resize）再显示；320ms 兜底，免得空壳气泡闪一下
  return w
}
/* 模块唯一的可见出口：showBubble({kind,text,ttl})。
 * 单例复用 —— 已开着就只替换内容、重置 TTL；正在淡出就取消淡出淡回来。 */
function showBubble(src) {
  const b = src || {}
  const text = String(b.text == null ? '' : b.text).trim().slice(0, 240)
  if (!text) return
  if (!orbWin || orbWin.isDestroyed()) return   // 没有球就没有「球旁」，别在屏幕中间留孤零零的气泡
  const w = ensureBubbleWin()
  if (!w || w.isDestroyed()) return
  const ttl = Math.min(60000, Math.max(1200, Math.round(Number(b.ttl) || BUBBLE_TTL_DEFAULT)))
  sendToWin(w, 'bubble:show', { kind: String(b.kind || 'focus-info'), text })
  placeBubble(w.__bubbleH)
  if (w.isVisible() && !w.__closing) {
    if (w.getOpacity() < 1) w.setOpacity(1)   // 上一次淡出中途被复用：直接亮回来
  } else if (readyWins.has(w)) {
    cancelClose(w)
    revealWin(w, false)   // showInactive + 淡入，不抢前台应用的键盘焦点
  }
  // 第一次建窗（页面还没就绪）不在这里显示：等首帧 bubble:resize 由 revealAfterFirstResize 接管
  clearTimeout(bubbleTTL)
  bubbleTTL = setTimeout(() => { bubbleTTL = null; if (bubbleWin && !bubbleWin.isDestroyed()) fadeClose(bubbleWin, true) }, ttl)
}
ipcMain.on('bubble:resize', (e, h) => {
  if (!isBubbleSender(e)) return
  const H = Math.max(36, Math.min(BUBBLE_MAX_H, Math.round(Number(h) || BUBBLE_DEFAULT_H)))
  bubbleWin.__bubbleH = H
  placeBubble(H)
  firstResizeDone(bubbleWin)   // 首帧内容已画好 → 立刻显示（revealAfterFirstResize 在等这个）
})
ipcMain.on('bubble:close', (e) => {
  if (!isBubbleSender(e)) return
  clearTimeout(bubbleTTL)
  bubbleTTL = null
  if (bubbleWin && !bubbleWin.isDestroyed()) fadeClose(bubbleWin, true)
})

/* ---- 菜单那一行：进行中「剩 x 分·点按结束」，等目标「等待中·点按取消」，没在守护「x 分·守护前台应用」 ---- */
function focusMenuItem() {
  const st = fgCall('status') || {}
  if (st.active) return { active: true, waiting: false, label: '剩 ' + Math.max(0, Math.ceil((Number(st.remainingMs) || 0) / 60000)) + ' 分·点按结束' + (st.paused ? '（暂停）' : '') }
  if (focusStartWaiting) return { active: false, waiting: true, label: '等待目标应用·点按取消' }
  return { active: false, waiting: false, label: focusDurationMin() + ' 分·守护前台应用' }
}
function pushFocusMenuUpdate() {
  if (!menuWin || menuWin.isDestroyed() || !menuWin.isVisible()) return
  try { menuWin.webContents.send('menu:update', { focus: focusMenuItem() }) } catch {}
}
/* 菜单「专注守护」：进行中 → 结束；等待中 → 取消；没在守护 → 等用户点回真正的应用再开始。
 * 菜单窗口一关，macOS 可能把焦点还给上一个应用，也可能（accessory 应用）暂时留在 Ling 自己 ——
 * 单发抓一次太脆：这里每 500ms 抓一次前台，最多 20 秒，第一个「非 Ling」的前台就是守护目标；
 * 2 秒还没等到就弹一句引导（点一下你要专注的那个应用），超时如实报「没等到」，不开假会话。 */
let focusStartTimer = null
let focusStartWaiting = false
function stopFocusStartWait(byUser) {
  if (focusStartTimer) { clearTimeout(focusStartTimer); focusStartTimer = null }
  const was = focusStartWaiting
  focusStartWaiting = false
  if (was && byUser) modeLog('专注守护：开始前又点了一次，已取消等待')
  return was
}
function toggleFocusGuard() {
  const st = fgCall('status') || {}
  if (st.active) {
    stopFocusStartWait(false)
    fgCall('stop', 'user')
    modeLog('专注守护：菜单里手动结束（还剩 ' + Math.ceil((Number(st.remainingMs) || 0) / 60000) + ' 分）')
    pushFocusMenuUpdate()
    return
  }
  if (focusStartWaiting) { stopFocusStartWait(true); pushFocusMenuUpdate(); return }
  focusStartWaiting = true
  pushFocusMenuUpdate()
  const t0 = Date.now()
  let guided = false
  const step = async () => {
    if (!focusStartWaiting) return
    let front = null
    try { front = await frontAppInfo() } catch { front = null }
    if (!focusStartWaiting) return            // 等待期间被取消 / 被结束
    const waited = Date.now() - t0
    if (front && front.bundleId && front.bundleId !== selfBundleId()) {
      focusStartWaiting = false
      focusStartTimer = null
      const r = fgCall('start', { minutes: focusDurationMin(), bundleId: front.bundleId, appName: front.appName || '', awaySec: focusAwaySec() })
      if (!r || r.ok === false) showBubble({ kind: 'focus-info', text: '专注守护没开起来：' + ((r && r.error) || '未知原因'), ttl: 6000 })
      else modeLog('专注守护开始：' + (front.appName || front.bundleId) + ' ' + focusDurationMin() + ' 分钟（分心阈值 ' + focusAwaySec() + ' 秒）')
      pushFocusMenuUpdate()
      return
    }
    if (!guided && waited >= 2000) {
      guided = true
      showBubble({ kind: 'focus-info', text: '点一下你要专注的那个应用，守护就从它开始（最长等 20 秒）。', ttl: 6000 })
    }
    if (waited >= 20000) {
      focusStartWaiting = false
      focusStartTimer = null
      showBubble({ kind: 'focus-info', text: '没等到你切到要专注的应用 —— 先点一下那个 App，再从菜单点「专注守护」。', ttl: 6000 })
      pushFocusMenuUpdate()
      return
    }
    focusStartTimer = setTimeout(step, 500)
  }
  focusStartTimer = setTimeout(step, 1200)    // 首拍先等菜单窗口收掉
}

/* 陪伴设置变了（设置卡里点一下）就立刻重排下一次 —— 关 = 停表清计时器 */
function applyCompanionSettings() {
  const r = cpCall('applySettings', { on: orbState.companionOn !== false, freq: companionFreq(), ai: !!orbState.companionAi })
  if (r && r.ok && r.changed) modeLog('陪伴设置已生效：' + (orbState.companionOn !== false ? '开 ' + companionFreq() : '关') + (orbState.companionAi ? ' +AI' : ''))
}

app.commandLine.appendSwitch('enable-features', 'MacLoopbackAudioForScreenShare,MacSckSystemAudioLoopbackOverride')

// v1.29：小球是 alwaysOnTop 的 accessory 窗口，一旦被 Chromium 判成「被遮挡/后台」，
// 渲染进程的定时器会被节流到分钟级 —— 小球的 4 秒防呆变暗、拖拽合帧都靠渲染进程定时器，
// 节流一发生就表现为「点了没动静 / 球不跟手」。这三个开关是这类悬浮助手的常规配置。
// （v1.46 起单击/双击/三击的判定改走 e.detail，不再依赖渲染进程的 setTimeout，这一条更稳了。）
app.commandLine.appendSwitch('disable-background-timer-throttling')
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows')
app.commandLine.appendSwitch('disable-renderer-backgrounding')

app.whenReady().then(() => {
  bootLog('D whenReady 触发')
  if (app.dock) app.dock.hide()
  // v1.61.2：任何业务写盘 / 建窗之前先挂惰性恢复守卫（历史 + 知识卡共用）。
  installLanWriteGuards()
  loadState()
  currentMode = allModeIds().includes(orbState.mode) ? orbState.mode : 'general'
  transLang = LANGS.some((l) => l.id === orbState.transLang) ? orbState.transLang : DEFAULT_TRANS_LANG
  scanIntervalMs = Math.min(3600000, Math.max(5000, Number(orbState.scanIntervalMs) || 20000))
  bootLog('E loadState 完成 mode=' + currentMode + ' transLang=' + transLang + ' autoScan=' + orbState.autoScan + ' onCopy=' + orbState.translateOnCopy + ' scanInterval=' + fmtInterval(scanIntervalMs))
  modeLog('启动，当前模式 ' + currentMode + '（' + modeInfo(currentMode).label + '）')
  placeOrb()
  warmScreenPermission()
  bootLog('F 准备注册热键')
  registerTranslateHotkey()
  bootLog('G 热键注册完成 label=' + hotkeyLabel)
  if (orbState.translateOnCopy) setCopyMode(true)
  armPanelIdle()
  armKcardDueCheck()   // [KCard] 知识卡到期检查：启动查一次 + 每小时一次，没到期不发通知
  bootLog('H 启动流程结束')
  if (!orbState.hintShown) {
    // v1.61.1：首次启动只排这一个延时；卡片真正显示出来后由 hint:resize 里的 markHintShown 落盘。
    // 注意这里不再预设 hintShown=true —— 建窗失败 / 页面没起来时下次还得继续引导。
    hintTimer = setTimeout(() => { hintTimer = null; showHint() }, 900)
  } else if (!orbState.apiOnboarded) {
    // 已经看过引导 / 老用户，且没配接口才自动弹设置。新手卡还开着时绝不叠一个设置在它上面。
    setTimeout(showSettings, 1800)
  }
  // v1.25：不再自动续扫。以前只要上次开着，崩溃拉起/重启后就继续每 20 秒识别一次 ——
  // 忘了关就变成「幽灵自动扫描」，而且看起来像凭空弹面板。现在一律从关开始。
  if (orbState.autoScan) { orbState.autoScan = false; saveState(); modeLog('启动时清掉上次遗留的自动扫描开关（v1.25 起不再自动续扫）') }
  if (process.env.AUTO_SCAN === '1') setTimeout(startAutoScan, 1500)   // 留给冒烟/自动化
  if (process.env.AUTO_TRANSLATE === '1') setTimeout(() => { translateSelection() }, 1500)
  if (process.env.AUTO_TRIGGER === '1') {
    setTimeout(() => { if (orbWin && !orbWin.isDestroyed()) { orbWin.webContents.send('orb:busy'); orbWin.webContents.executeJavaScript("(() => { const el = document.getElementById('block'); el.dispatchEvent(new MouseEvent('mousedown', {bubbles:true})); el.dispatchEvent(new MouseEvent('mouseup', {bubbles:true})) })()") } }, 1500)
  }
  /* v1.64：MCP / 手机常连按上次落盘的选择起。默认关（新装 state.json 里没有这两项 → 全关）。
   * 起失败只记日志 / 落回「关」，绝不偷偷换端口；设置页打开时能看到真实错误。 */
  armServiceAutostart()
  /* v1.75：实时转写 —— 在 ready 之后初始化（safeStorage 要用系统钥匙串）。
   * 交给它的只有窗口工具 / 日志 / sender 判定；协议、凭据、会话状态都在 voice-main.js，
   * 渲染器拿到的永远是 hasKey 布尔，不是 Key。 */
  try {
    voiceMain.init({
      app: app,
      path: __dirname,
      ipcMain: ipcMain,
      BrowserWindow: BrowserWindow,
      screen: screen,
      desktopCapturer: desktopCapturer,   // 系统音频：语音窗口的 getDisplayMedia 由 voice-main.js 挑回环源
      shell: shell,
      safeStorage: safeStorage,
      systemPreferences: systemPreferences,
      APP_NAME: APP_NAME,
      layer: LAYER_POPUP,
      modeLog: modeLog,
      hideFromMissionControl: hideFromMissionControl,
      revealWin: revealWin,
      fadeClose: fadeClose,
      showSettings: showSettings,
      isSettingsSender: isSettingsSender,
      orbBounds: () => (orbWin && !orbWin.isDestroyed() ? orbWin.getBounds() : null),
      /* v1.78 语音对话：把**用户已配置的**对话接口交给 voice-main.js 的对话编排。
       * 这里只给「能不能用 / 用哪套配置 / 共享讲法」三件事：地址与 Key 留在主进程，
       * 渲染器永远拿不到。没有配置时不注入假配置 —— 对话按钮会如实显示不可用。
       * thinkingOf 跟随用户「思考强度」既有设置（对话首句延迟受它影响）。 */
      chat: {
        ready: () => apiReady(),
        cfg: () => Object.assign({}, apiCfg(), thinkingOf(apiCfg())),
        style: () => P.STYLE
      }
    })
  } catch (e) {
    bootLog('X 实时转写初始化失败 ' + (e && (e.stack || e.message) || e))
    modeLog('实时转写初始化失败：' + ((e && e.message) || e))
  }
  /* v1.76：专注守护 + 陪伴 —— 也是只接线（窗口 / 前台探测 / 气泡 / AI 短句 / 初始设置）。
   * 陪伴的初始设置来自 state.json（companionOn / companionFreq / companionAi，默认 开 / med / 关）；
   * LING_COMPANION_NEXT_MS 是给自检用的：设了就把下一次展示压到这个毫秒数。
   * fgCall / cpCall 自己兜模块缺失与异常（只停那一个功能），这里不用再包一层。 */
  fgCall('init', {
    modeLog: modeLog,
    runFrontmost: runFrontmost,
    selfBundleId: selfBundleId(),
    orbGuard: pushOrbGuard,
    showBubble: showBubble
  })
  cpCall('init', {
    modeLog: modeLog,
    showBubble: showBubble,
    aiLine: companionAiLine,
    envNextOverride: Number(process.env.LING_COMPANION_NEXT_MS) || null,
    initial: { on: orbState.companionOn !== false, freq: companionFreq(), ai: !!orbState.companionAi }
  })
})

function armServiceAutostart() {
  if (orbState.mcp && orbState.mcp.enabled) {
    mcpHubStart().then((r) => {
      if (r.ok) return
      // 起不来就把开关落回关 —— 否则界面上写着「开」但没有任何监听
      orbState.mcp.enabled = false
      saveState()
      modeLog('启动时自动开 MCP 失败，已回到关闭：' + r.error)
    }).catch(() => {})
  }
  if (orbState.link && orbState.link.enabled) {
    linkStart().then((r) => {
      if (r.ok) return
      orbState.link.enabled = false
      saveState()
      modeLog('启动时自动开手机常连失败，已回到关闭：' + r.error)
    }).catch(() => {})
  }
}

// v1.61.1：退出时把新手卡的延时和「设置关闭后回卡」标记一起清掉，别让旧进程状态留到下一次
app.on('before-quit', () => {
  if (hintTimer) { clearTimeout(hintTimer); hintTimer = null }
  hintResumeOnSettingsClose = false
  saveState()
})
// 退出也要真停：先发起 stop（before-quit），再在 will-quit 等这些 socket 都关完才真正退出。
// v1.64：局域网同步 / MCP / 手机常连三条线各自独立，谁开过就停谁；没开过的不会因此建实例。
app.on('before-quit', () => { lanSyncStop(); mcpHubStop(); linkStop(); voiceMain.shutdown() })
// v1.76：专注守护 / 陪伴也要真停 —— 清掉轮询与展示计时器，不残留后台定时器
app.on('before-quit', () => { fgCall('shutdown'); cpCall('shutdown') })
let servicesQuitDone = false
app.on('will-quit', (ev) => {
  try { globalShortcut.unregisterAll() } catch {}
  if (servicesQuitDone) return
  const pending = []
  if (_lanSync) pending.push(lanSyncStop())
  if (_mcpHub) pending.push(mcpHubStop())
  if (_link) pending.push(linkStop())
  if (!pending.length) { servicesQuitDone = true; return }
  ev.preventDefault()
  Promise.allSettled(pending).then(() => { servicesQuitDone = true; app.quit() })
})
app.on('window-all-closed', () => {})
app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) placeOrb() })
