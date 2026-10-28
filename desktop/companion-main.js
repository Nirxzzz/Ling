/* companion-main.js — 「陪伴」短句的主进程调度器（定时抽句 → 球旁气泡）。
 *
 * 为什么独立成模块：文案池、频率、AI 增强和计时器都属于「盯着一个小状态机」的活，
 * 塞进 4300 行的 main.js 只会再多一处到处传递的全局变量。这里只依赖注入进来的
 * 计时器 / 气泡发送 / 日志，窗口与菜单位置全由 main.js 决定。
 *
 * 几条约束（写死在代码里，不靠调用方自觉）：
 *   - 内置句走洗牌袋：一轮内不重复，袋子空了才重洗，跨袋边界也不让上一句立刻重来；
 *   - AI 只在「每第 3 次」且开关打开时才问，14 秒不回来就当没有，绝不堵住展示队列；
 *   - AI 文本一律清洗：去首尾引号、压掉换行，超过 60 字或为空就整句作废，回退内置句；
 *   - 关掉开关立刻撤计时器，退出应用不留没人触发的定时器。
 */
'use strict'

const AI_TIMEOUT_MS = 14000        // AI 短句的竞速上限：超时就当这次没有
const AI_MAX_CHARS = 60            // AI 文本上限（超过就回退，避免半屏字）
const BUBBLE_TTL = 8000            // 气泡停留时长，交给主进程的淡出逻辑
const EVERY_N_AI = 3               // 每第 3 次展示才值得多花一次接口
/** 频率档位 → [最短, 最长] 分钟。 */
const FREQ_MINUTES = { low: [60, 180], med: [45, 120], high: [15, 60] }
const FREQ_KEYS = Object.keys(FREQ_MINUTES)

/** 内置短句池：鼓励 / 小知识 / 功能提示。每条不超过 28 字，无表情符号。 */
const POOL = [
  '先做最简单的一步，动起来就不难了',
  '你已经比昨天更熟练了',
  '慢一点也没关系，别停下来',
  '专注二十五分钟，就赢了一小局',
  '卡住时先写下来，再拆成小块',
  '今天的坚持，明天会替你说话',
  '累了就歇三十秒，再继续',
  '一次只做一件事，效率最高',
  '犯错是过程，不是结论',
  '你现在的努力都在积累',
  '把大任务切成三个小步',
  '完成比完美更重要',
  '先写完，再改好',
  '深呼吸，然后只做眼前这一步',
  '每完成一件，就离目标更近',
  '人一次只能专注约二十五分钟',
  '久坐四十五分钟最好起身走走',
  '休息时看看远处，眼睛会舒服些',
  '手写笔记比打字记得更牢',
  '睡前复述一遍，记忆更稳',
  '分散复习比一次猛背更有效',
  '咖啡因半衰期约五小时，下午少喝',
  '蓝色光会推后入睡时间',
  '每天二十分钟快走，脑子更清醒',
  '用自己的话复述，知识才算到手',
  '睡够七小时，记忆才会真正存档',
  '把手机放远一点，注意力会回来',
  '番茄钟来自一只番茄形状的计时器',
  '遗忘最快发生在学完的头一天',
  '多喝水，大脑对缺水很敏感',
  '选中文字按一下小球就能翻译',
  '截图识别后可以存成知识卡',
  '划词翻译会把原文一起保存',
  '知识卡攒够后可以拿出来复习',
  '长按小球能直接用语音提问',
  '设置里可以改翻译的目标语言',
  '答案面板能复制，也能朗读',
  '设置里能调小球的透明度和大小',
  '快捷键可以在设置里自己改',
  '复习卡片支持「模糊记得」这个选项',
  '截图识别的文字可以直接搜索',
  '常看的资料存成知识卡，随时翻',
  '语音转写支持麦克风和系统声音',
  '菜单里能快速开关自动识别',
  '设置页顶部的搜索能直接找选项'
]

let deps = null
/** 当前设置：on 开关 / freq 档位 / ai 是否允许 AI 短句。 */
const state = { on: false, freq: 'med', ai: false }
let timer = null          // 下一次抽句的计时器句柄（同一时刻最多一个）
let nextAtMs = 0          // 下一次展示的绝对时间（0 = 没有排期）
let shownCount = 0
let lastText = ''
let bag = []              // 洗牌袋：本轮还没抽到的句子
let epoch = 0             // 开关代际：关掉时作废还在等 AI 的那次展示
let pending = Promise.resolve()   // 展示流程串行链，避免 shownCount / lastText 交错

function log(line) { try { if (deps && typeof deps.modeLog === 'function') deps.modeLog(line) } catch {} }

function init(d) {
  if (deps) return
  const src = d || {}
  deps = {
    modeLog: typeof src.modeLog === 'function' ? src.modeLog : () => {},
    now: typeof src.now === 'function' ? src.now : Date.now,
    setTimer: typeof src.setTimer === 'function' ? src.setTimer : setTimeout,
    clearTimer: typeof src.clearTimer === 'function' ? src.clearTimer : clearTimeout,
    showBubble: typeof src.showBubble === 'function' ? src.showBubble : () => {},
    aiLine: typeof src.aiLine === 'function' ? src.aiLine : null,
    envNextOverride: src.envNextOverride === undefined ? null : src.envNextOverride
  }
  const initial = src.initial || {}
  state.on = initial.on !== false            // 缺省开：新装也先给点陪伴感
  state.freq = normalizeFreq(initial.freq, 'med')
  state.ai = !!initial.ai
  if (state.on) schedule()
}

/** 退出应用 / 关掉功能：撤掉计时器，作废在等 AI 的那次展示。 */
function shutdown() {
  epoch++
  state.on = false
  cancelTimer()
  nextAtMs = 0
}

/**
 * 设置页改完就调这里：开关立刻生效，频率立刻重排。
 * 只认认识的值，脏数据不会把档位带跑。
 */
function applySettings(s) {
  if (!deps) return { ok: false, error: '未初始化' }
  const next = s || {}
  let changed = false
  if (typeof next.on === 'boolean' && next.on !== state.on) { state.on = next.on; changed = true }
  if (next.freq !== undefined) {
    const f = normalizeFreq(next.freq, state.freq)
    if (f !== state.freq) { state.freq = f; changed = true }
  }
  if (typeof next.ai === 'boolean' && next.ai !== state.ai) { state.ai = next.ai; changed = true }
  if (!state.on) {
    epoch++
    cancelTimer()
    nextAtMs = 0
    return { ok: true, changed: changed }
  }
  // 开着就按当前设置重新排一次：改频率、重新打开都要立刻反映到下个时间点
  schedule()
  return { ok: true, changed: changed }
}

/** 手动触发一次展示（主脑自检 / 菜单点按）：立即抽句，照常重排下一次。 */
function fireOnce() {
  if (!deps) return Promise.resolve({ ok: false, error: '未初始化' })
  return runDisplay()
}

function status() {
  return {
    on: !!state.on,
    freq: state.freq,
    ai: !!state.ai,
    nextAtMs: state.on ? nextAtMs : 0,
    shownCount: shownCount,
    lastText: lastText
  }
}

/* ---------------- 调度 ---------------- */

function normalizeFreq(v, fallback) {
  return typeof v === 'string' && FREQ_MINUTES[v] ? v : fallback
}

/** envNextOverride 优先（0 也算合法：立刻展示），否则按档位在区间里取整分钟。 */
function nextDelayMs() {
  const raw = deps.envNextOverride
  if (raw !== null && raw !== undefined) {
    const n = Number(raw)
    if (Number.isFinite(n) && n >= 0) return Math.round(n)
  }
  const range = FREQ_MINUTES[state.freq] || FREQ_MINUTES.med
  const min = range[0] * 60000
  const max = range[1] * 60000
  return Math.round(min + Math.random() * (max - min))
}

function cancelTimer() {
  if (timer === null) return
  const t = timer
  timer = null
  try { deps.clearTimer(t) } catch (e) { log('撤陪伴计时器失败：' + ((e && e.message) || e)) }
}

function schedule() {
  cancelTimer()
  if (!state.on) { nextAtMs = 0; return }
  const delay = nextDelayMs()
  nextAtMs = deps.now() + delay
  timer = deps.setTimer(() => {
    timer = null
    nextAtMs = 0
    runDisplay().catch((e) => log('陪伴展示失败：' + ((e && e.message) || e)))
  }, delay)
}

/** 展示流程串行：上一次还在等 AI 时，这一次排队等着，计数与文案不会错位。 */
function runDisplay() {
  const job = pending.then(() => displayOnce())
  pending = job.then(() => {}, () => {})   // 一次失败不堵死后续
  return job
}

async function displayOnce() {
  cancelTimer()                 // 到点 / 手动触发都先撤掉旧排期，结束后统一重排
  nextAtMs = 0
  const startEpoch = epoch
  const count = shownCount + 1
  let text = ''
  let fromAi = false
  if (state.ai && count % EVERY_N_AI === 0) {
    const asked = cleanAiText(await askAiFixed())
    if (asked) { text = asked; fromAi = true }
  }
  // 等 AI 的过程中被关掉：这一次整个作废，不出气泡、不计数
  if (epoch !== startEpoch) return { ok: false, skipped: true }
  if (!text) text = pickSentence()
  shownCount = count
  lastText = text
  try {
    deps.showBubble({ kind: 'companion', text: text, ttl: BUBBLE_TTL })
  } catch (e) {
    log('陪伴气泡发送失败：' + ((e && e.message) || e))
  }
  if (state.on) schedule()
  return { ok: true, text: text, ai: fromAi }
}

/* ---------------- AI 短句 ---------------- */

/** aiLine 与 14 秒超时竞速：谁先回来算谁，异常 / null 一律当没有。 */
function askAiFixed() {
  return new Promise((resolve) => {
    const box = { timer: null, done: false }
    const finish = (v) => {
      if (box.done) return
      box.done = true
      if (box.timer !== null) {
        const t = box.timer
        box.timer = null
        try { deps.clearTimer(t) } catch {}
      }
      resolve(v)
    }
    box.timer = deps.setTimer(() => finish(null), AI_TIMEOUT_MS)
    if (box.timer === undefined) box.timer = null
    Promise.resolve()
      .then(() => (typeof deps.aiLine === 'function' ? deps.aiLine() : null))
      .then((v) => finish(v))
      .catch(() => finish(null))
  })
}

/** 清洗 AI 文本：去首尾引号、压掉换行与多余空白；空 / 超长一律作废（回退内置句）。 */
function cleanAiText(v) {
  if (typeof v !== 'string') return ''
  let s = v.replace(/\s+/g, ' ').trim()
  s = s.replace(/^["'“”‘’「」『』]+/, '').replace(/["'“”‘’「」『』]+$/, '').trim()
  if (!s) return ''
  if (Array.from(s).length > AI_MAX_CHARS) return ''
  return s
}

/* ---------------- 洗牌袋 ---------------- */

function shuffle(arr) {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    const t = arr[i]
    arr[i] = arr[j]
    arr[j] = t
  }
  return arr
}

/** 抽一句：袋子空了才重洗，且新袋第一句不会和上一句撞车。 */
function pickSentence() {
  if (!bag.length) {
    bag = shuffle(POOL.slice())
    if (bag.length > 1 && bag[0] === lastText) {
      const t = bag[0]
      bag[0] = bag[bag.length - 1]
      bag[bag.length - 1] = t
    }
  }
  return bag.shift()
}

module.exports = {
  init: init,
  shutdown: shutdown,
  applySettings: applySettings,
  fireOnce: fireOnce,
  status: status,
  // 主进程测试 / 诊断用（不进渲染器）
  FREQ_MINUTES: FREQ_MINUTES,
  FREQ_KEYS: FREQ_KEYS,
  POOL: POOL
}
