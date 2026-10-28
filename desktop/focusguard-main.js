/* focusguard-main.js — 「专注守护」的主进程状态机（前台应用检测 / 分心提醒 / 暂停计分 / 到时收尾）。
 *
 * 为什么单独一个模块：main.js 已经有 4300 行，专注守护自带一套计时与状态机，
 * 而它真正需要主进程提供的只有几样东西：日志、当前时间、计时器、查前台应用的实现、
 * 小球光环（orb:guard）与球旁气泡。这些一律走 init(deps) 注入，模块里不 require electron，
 * 因此可以像 voice-main.js 那样在纯 Node 下用假 now / 假计时器 / 假 runFrontmost 直接驱动。
 *
 * 计分口径（写死在代码里，页面不参与判定）：
 *   - 启动后 graceMs 内不计分心（用户刚点完菜单，切窗口的抖动不该算账）；
 *   - 查前台抛错或返回 null（解析不出就是失败的信号）→ 累计连续错误，不算分心；
 *     返回本应用自己 → 算识别成功但同样不算分心（用户点了小球 / 气泡）；
 *   - 连续错误 >= 15 次（约 30 秒）→ paused：不提醒、不累计离开时长，光环回到非分心态；
 *     只要识别成功一次就恢复（暂停期间的时间不计入 awayMs）；
 *   - 离开目标应用累计到 awaySec 阈值 → 一个「离开回合」只提醒一次；回到目标应用回合结束，
 *     awayMs 归零、光环回到非分心态；再离开再攒够一轮才会第二次提醒。
 *   - 到 endsAt 自动完成；手动 stop / 退出 shutdown 也把两个计时器清干净，不留幽灵循环。
 *   - 在途的 runFrontmost 结果带会话序号：stop 之后才回来的结果一律丢弃，不会把已经结束的会话写活。
 */
'use strict'

const POLL_MS = 2000            // 前台应用轮询间隔
const GRACE_MS = 10000          // 启动宽限：这段时间里的「离开」不累计
const ERROR_PAUSE = 15          // 连续失败次数（约 30 秒）→ 暂停计分
const REMIND_TTL = 6000
const INFO_TTL = 6000
const DONE_TTL = 8000
const MIN_MINUTES = 1
const MAX_MINUTES = 240
const AWAY_DEFAULT_SEC = 20

/** 注入项都给了兜底：模块被单独 require（例如测试漏传某项）也不会炸。 */
const DEFAULT_DEPS = {
  modeLog: () => {},
  now: () => Date.now(),
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: (id) => clearTimeout(id),
  runFrontmost: async () => null,
  orbGuard: () => {},
  showBubble: () => {}
}

let deps = null
let pollTimer = null
let endTimer = null
let inFlight = null             // 在途采样的令牌（上一次 runFrontmost 还没回来时不叠第二次）
let sessionSeq = 0              // 每次 start / 结束都自增，用来丢弃在途结果

const state = {
  active: false,
  paused: false,
  bundleId: '',
  appName: '',
  minutes: 0,
  startedAt: 0,
  endsAt: 0,
  awayThresholdMs: AWAY_DEFAULT_SEC * 1000,
  awayMs: 0,
  distracted: false,
  episodeReminded: false,       // 当前这一轮离开是否已经提醒过
  remindedEpisodes: 0,
  consecutiveErrors: 0,
  lastTickAt: 0                 // 上一次成功计分的时刻，用来算离开时长
}

function init(d) {
  if (deps) return
  const src = d || {}
  const out = {}
  for (const k of Object.keys(DEFAULT_DEPS)) out[k] = typeof src[k] === 'function' ? src[k] : DEFAULT_DEPS[k]
  out.selfBundleId = typeof src.selfBundleId === 'string' ? src.selfBundleId : ''
  deps = out
}

function D() { return deps || DEFAULT_DEPS }

function log(line) {
  try { D().modeLog(line) } catch {}
}

function nowMs() {
  try {
    const t = Number(D().now())
    return Number.isFinite(t) ? t : Date.now()
  } catch { return Date.now() }
}

function setT(fn, ms) {
  try { return D().setTimer(fn, ms) } catch { return null }
}

function clearT(id) {
  if (id === null || id === undefined) return
  try { D().clearTimer(id) } catch {}
}

/** 光环：payload 只暴露 on / distract 两个布尔，别把内部状态整包丢给渲染层。 */
function guard(p) {
  try { D().orbGuard({ on: !!(p && p.on), distract: !!(p && p.distract) }) } catch {}
}

function bubble(b) {
  const payload = { kind: String((b && b.kind) || 'focus-info'), text: String((b && b.text) || '') }
  const ttl = Number(b && b.ttl)
  if (Number.isFinite(ttl) && ttl > 0) payload.ttl = ttl
  try { D().showBubble(payload) } catch {}
}

function clearTimers() {
  if (pollTimer !== null) { clearT(pollTimer); pollTimer = null }
  if (endTimer !== null) { clearT(endTimer); endTimer = null }
}

function armPoll() {
  if (!state.active) return
  // 先排下一次再执行本次：即使 pollOnce 里 finish 掉了，clearTimers 也能把新排的这一个清掉
  pollTimer = setT(() => {
    pollTimer = null
    armPoll()
    pollOnce().catch(() => {})
  }, POLL_MS)
}

function resetSession() {
  state.active = false
  state.paused = false
  state.bundleId = ''
  state.appName = ''
  state.minutes = 0
  state.startedAt = 0
  state.endsAt = 0
  state.awayMs = 0
  state.distracted = false
  state.episodeReminded = false
  state.remindedEpisodes = 0
  state.consecutiveErrors = 0
  state.lastTickAt = 0
}

/**
 * 收尾：清计时器 → 作废在途结果 → 按原因发最后一次气泡 / 光环。
 * reason: 'done' 到时完成 / 'user' 用户主动结束 / 'shutdown' 退出（不再往可能已销毁的窗口发消息）。
 */
function finish(reason) {
  if (!state.active) {
    clearTimers()
    return { ok: false, error: 'not-active' }
  }
  const appName = state.appName
  const minutes = state.minutes
  const elapsedMin = Math.max(0, Math.floor((nowMs() - state.startedAt) / 60000))   // 向下取整：30 秒不说成 1 分钟
  sessionSeq++
  inFlight = null            // 作废在途令牌：新会话可以立刻采样，旧结果回来时按序号丢弃
  clearTimers()
  resetSession()
  if (reason === 'done') {
    bubble({
      kind: 'focus-done',
      text: (appName ? appName + '，' : '') + '专注完成：' + minutes + ' 分钟，起身活动一下',
      ttl: DONE_TTL
    })
    guard({ on: false, distract: false })
    log('专注守护到时完成：' + minutes + ' 分钟')
  } else if (reason === 'user') {
    // 刚开就结束（不足 1 分钟）不说「本次 0 分钟」：那读起来像没开始过
    bubble({ kind: 'focus-info', text: '已结束专注：' + (elapsedMin >= 1 ? '本次 ' + elapsedMin + ' 分钟' : '本次不到 1 分钟'), ttl: INFO_TTL })
    guard({ on: false, distract: false })
    log('专注守护手动结束：' + (elapsedMin >= 1 ? elapsedMin + ' 分钟' : '不到 1 分钟'))
  } else if (reason !== 'shutdown') {
    guard({ on: false, distract: false })
  }
  return { ok: true }
}

/* ---------------- 对外 ---------------- */

function start(opts) {
  const o = opts || {}
  if (state.active) return { ok: false, error: 'busy' }
  const minutes = Number(o.minutes)
  if (!Number.isFinite(minutes)) return { ok: false, error: 'invalid-minutes' }
  const m = Math.min(MAX_MINUTES, Math.max(MIN_MINUTES, Math.round(minutes)))
  const bundleId = typeof o.bundleId === 'string' ? o.bundleId.trim() : ''
  if (!bundleId) return { ok: false, error: 'invalid-bundle' }
  // 分心阈值：调用方没给 / 给的不是正数就用默认 20 秒，不擅自替调用方改数值
  const awaySecRaw = Number(o.awaySec)
  const awaySec = Number.isFinite(awaySecRaw) && awaySecRaw > 0 ? awaySecRaw : AWAY_DEFAULT_SEC

  const t = nowMs()
  sessionSeq++
  state.active = true
  state.paused = false
  state.bundleId = bundleId
  state.appName = typeof o.appName === 'string' ? o.appName : ''
  state.minutes = m
  state.startedAt = t
  state.endsAt = t + m * 60000
  state.awayThresholdMs = awaySec * 1000
  state.awayMs = 0
  state.distracted = false
  state.episodeReminded = false
  state.remindedEpisodes = 0
  state.consecutiveErrors = 0
  // 宽限期不从 0 算：离开时长从「宽限结束」那一刻开始累计，别把启动那 10 秒算进账
  state.lastTickAt = t + GRACE_MS

  guard({ on: true, distract: false })
  armPoll()
  endTimer = setT(() => {
    endTimer = null
    if (state.active) finish('done')
  }, m * 60000)
  log('专注守护开始：' + (state.appName || bundleId) + ' ' + m + ' 分钟，分心阈值 ' + awaySec + ' 秒')
  return { ok: true }
}

function stop(reason) {
  return finish(typeof reason === 'string' && reason ? reason : 'user')
}

function shutdown() {
  return finish('shutdown')
}

function status() {
  const active = state.active
  return {
    active: active,
    paused: state.paused,
    target: { bundleId: state.bundleId, name: state.appName },
    startedAt: state.startedAt,
    endsAt: state.endsAt,
    remainingMs: active ? Math.max(0, state.endsAt - nowMs()) : 0,
    distracted: state.distracted,
    awayMs: state.awayMs,
    remindedEpisodes: state.remindedEpisodes,
    consecutiveErrors: state.consecutiveErrors
  }
}

/** 单次采样。计时器每 2 秒调一次；测试/手动也可直接 await 调它推进状态机。 */
async function pollOnce() {
  if (!state.active) return { ok: false, error: 'not-active' }
  const sid = sessionSeq
  if (nowMs() >= state.endsAt) { finish('done'); return { ok: true, done: true } }
  if (inFlight) return { ok: true, skipped: true }
  const token = {}
  inFlight = token

  let front = null
  let failed = false
  try {
    front = await D().runFrontmost()
  } catch (e) {
    failed = true
    log('前台应用检测失败：' + ((e && e.message) || e))
  }

  const done = () => { if (inFlight === token) inFlight = null }
  // 等待期间可能被 stop / 到时完成 / 重启：这一拍的结果作废（令牌只由自己收回，不误清别人的）
  if (!state.active || sid !== sessionSeq) { done(); return { ok: true, stale: true } }
  const t = nowMs()
  if (t >= state.endsAt) { done(); finish('done'); return { ok: true, done: true } }
  done()

  // 「查不到」和「抛错」都算一次失败：lsappinfo 认不出前台应用就是失败的信号（C 切片实现返回 null）
  const frontId = typeof front === 'string' ? front.trim() : ''
  if (failed || !frontId) {
    state.consecutiveErrors++
    state.lastTickAt = t
    if (!state.paused && state.consecutiveErrors >= ERROR_PAUSE) {
      state.paused = true
      state.distracted = false
      state.episodeReminded = false
      state.awayMs = 0
      guard({ on: true, distract: false })
      log('前台应用连续检测失败 ' + state.consecutiveErrors + ' 次，专注守护暂停计分')
    }
    return { ok: true, paused: state.paused, unknown: true }
  }

  // 识别成功一次就算恢复；暂停期间的时间不计入离开时长
  state.consecutiveErrors = 0
  if (state.paused) {
    state.paused = false
    state.lastTickAt = t
    guard({ on: true, distract: false })
    log('前台应用重新识别成功，专注守护恢复计分')
  }

  if (t - state.startedAt < GRACE_MS) {
    // 宽限期内不累计：游标钉在宽限结束点，离开时长只从那一刻之后开始算
    state.lastTickAt = Math.max(t, state.startedAt + GRACE_MS)
    return { ok: true, grace: true }
  }

  if (frontId === state.bundleId) {
    // 回到目标应用：这一轮离开结束（提醒过的话把光环收回非分心态）
    if (state.distracted || state.episodeReminded) guard({ on: true, distract: false })
    state.distracted = false
    state.episodeReminded = false
    state.awayMs = 0
    state.lastTickAt = t
    return { ok: true, onTarget: true }
  }
  if (D().selfBundleId && frontId === D().selfBundleId) {
    // 前台就是我们自己（点了小球 / 气泡）：不算分心，也不结束这一轮离开
    state.lastTickAt = t
    return { ok: true, unknown: true }
  }

  state.awayMs += Math.max(0, t - state.lastTickAt)
  state.lastTickAt = t
  if (!state.episodeReminded && state.awayMs >= state.awayThresholdMs) {
    const sec = Math.round(state.awayMs / 1000)
    state.episodeReminded = true
    state.distracted = true
    state.remindedEpisodes++
    guard({ on: true, distract: true })
    bubble({
      kind: 'focus-remind',
      text: (state.appName ? '离开 ' + state.appName + ' 已经 ' : '离开目标应用已经 ') + sec + ' 秒，回来继续专注',
      ttl: REMIND_TTL
    })
    return { ok: true, awayMs: state.awayMs, reminded: true }
  }
  return { ok: true, awayMs: state.awayMs, reminded: false }
}

module.exports = {
  init: init,
  shutdown: shutdown,
  start: start,
  stop: stop,
  status: status,
  pollOnce: pollOnce
}
