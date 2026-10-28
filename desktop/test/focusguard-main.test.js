/* focusguard-main：专注守护状态机回归 —— 假 now / 假计时器 / 假 runFrontmost，直接驱动 pollOnce。
 *
 * 这里只验「真出过问题就会出事」的行为：宽限期不误报、一轮离开只提醒一次、连续检测失败不刷提醒
 * 且能自愈、到时 / 手动结束 / 退出都把计时器清干净、光环与气泡的 payload 与调用顺序。
 * 不 require electron、不开窗口：主进程接线（lsappinfo / 气泡窗口）在别的切片里。
 */
'use strict'
const test = require('node:test')
const assert = require('node:assert')

const T0 = 1750000000000
const MIN = 60000
const SELF = 'com.ling.orb'
const TARGET = 'com.apple.Safari'
const OTHER = 'com.google.Chrome'
const GRACE_MS = 10000
const POLL_MS = 2000
const ERROR_PAUSE = 15

/**
 * 一套注入替身：clock 手动推进，计时器只登记不发火（要发就 fireDue），
 * 前台应用由 front.value / front.fail 控制，光环与气泡只记录不渲染。
 */
function makeCtx() {
  const clock = { t: T0 }
  const timers = new Map()
  let seq = 0
  const guards = []
  const bubbles = []
  const logs = []
  const front = { value: TARGET, fail: false, gate: null, calls: 0 }

  const deps = {
    modeLog: (l) => logs.push(String(l)),
    now: () => clock.t,
    setTimer: (fn, ms) => {
      const id = ++seq
      timers.set(id, { fn: fn, ms: ms, at: clock.t + ms })
      return id
    },
    clearTimer: (id) => { timers.delete(id) },
    runFrontmost: async () => {
      front.calls++
      if (front.gate) await front.gate
      if (front.fail) throw new Error('lsappinfo failed')
      return front.value
    },
    selfBundleId: SELF,
    orbGuard: (p) => { guards.push({ on: p.on, distract: p.distract }) },
    showBubble: (b) => { bubbles.push({ kind: b.kind, text: b.text, ttl: b.ttl }) }
  }

  const ctx = {
    clock: clock,
    timers: timers,
    guards: guards,
    bubbles: bubbles,
    logs: logs,
    front: front,
    deps: deps,
    mod: null,
    advance(ms) { clock.t += ms },
    /** 把到点的计时器按时间顺序跑完（回调里新排的那一个不会跑到） */
    fireDue() {
      const due = [...timers.entries()].filter((e) => e[1].at <= clock.t).sort((a, b) => a[1].at - b[1].at)
      for (const e of due) {
        if (!timers.has(e[0])) continue
        timers.delete(e[0])
        e[1].fn()
      }
    },
    /** 按 2 秒一拍推进 n 拍，每拍调一次 pollOnce（模拟真实轮询的节奏） */
    async tick(n) {
      for (let i = 0; i < (n || 1); i++) {
        clock.t += POLL_MS
        await ctx.mod.pollOnce()
      }
    }
  }
  return ctx
}

/** 每个用例一份全新模块实例（模块级状态不能跨用例残留）。 */
function load(ctx) {
  delete require.cache[require.resolve('../focusguard-main')]
  const mod = require('../focusguard-main')
  mod.init(ctx.deps)
  ctx.mod = mod
  return mod
}

function startSafari(ctx, extra) {
  const o = { minutes: 25, bundleId: TARGET, appName: 'Safari' }
  Object.assign(o, extra || {})
  const r = ctx.mod.start(o)
  assert.deepStrictEqual(r, { ok: true })
  return r
}

/* ─────────── 导出面 ─────────── */

test('① 导出：init / shutdown / start / stop / status / pollOnce 全是函数', () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  for (const k of ['init', 'shutdown', 'start', 'stop', 'status', 'pollOnce']) {
    assert.strictEqual(typeof mod[k], 'function', k + ' 必须是函数')
  }
})

/* ─────────── 参数校验与钳制 ─────────── */

test('② start 参数：缺参数报错，分钟钳在 1..240，重复启动返回 busy', () => {
  const ctx = makeCtx()
  const mod = load(ctx)

  assert.deepStrictEqual(mod.start(), { ok: false, error: 'invalid-minutes' })
  assert.deepStrictEqual(mod.start({ minutes: 'abc', bundleId: TARGET }), { ok: false, error: 'invalid-minutes' })
  assert.deepStrictEqual(mod.start({ minutes: 25 }), { ok: false, error: 'invalid-bundle' })
  assert.deepStrictEqual(mod.start({ minutes: 25, bundleId: '   ' }), { ok: false, error: 'invalid-bundle' })
  // 报错的这几次不能留下任何痕迹
  assert.strictEqual(mod.status().active, false)
  assert.strictEqual(ctx.timers.size, 0)
  assert.strictEqual(ctx.guards.length, 0)

  ctx.advance(1000)
  startSafari(ctx, { minutes: 0 })
  assert.strictEqual(mod.status().endsAt - mod.status().startedAt, 1 * MIN)
  // 目标应用真的按传入的 bundleId 记下来了
  assert.deepStrictEqual(mod.status().target, { bundleId: TARGET, name: 'Safari' })
  mod.stop('user')
  assert.strictEqual(ctx.timers.size, 0)

  startSafari(ctx, { minutes: 999 })
  assert.strictEqual(mod.status().endsAt - mod.status().startedAt, 240 * MIN)

  // 已经 active 时再 start 一律 busy，且不覆盖当前会话
  const before = mod.status()
  assert.deepStrictEqual(mod.start({ minutes: 5, bundleId: OTHER }), { ok: false, error: 'busy' })
  assert.strictEqual(mod.status().startedAt, before.startedAt)
  assert.strictEqual(mod.status().target.bundleId, TARGET)
})

test('③ start 之后：光环先亮非分心态，两个计时器（轮询 + 到时）都在', () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx)
  assert.deepStrictEqual(ctx.guards, [{ on: true, distract: false }])
  assert.strictEqual(ctx.timers.size, 2)
  const spans = [...ctx.timers.values()].map((v) => v.ms).sort((a, b) => a - b)
  assert.deepStrictEqual(spans, [POLL_MS, 25 * MIN])
})

/* ─────────── 宽限期 ─────────── */

test('④ 宽限期内离开目标应用不算分心：不提醒、不累计 awayMs', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx)
  ctx.front.value = OTHER
  // 4 拍 = 8 秒，全程都还在 10 秒宽限里
  await ctx.tick(4)
  const s = mod.status()
  assert.strictEqual(s.awayMs, 0)
  assert.strictEqual(s.distracted, false)
  assert.strictEqual(s.remindedEpisodes, 0)
  assert.deepStrictEqual(ctx.bubbles, [])
  assert.deepStrictEqual(ctx.guards, [{ on: true, distract: false }])
})

/* ─────────── 一轮离开只提醒一次 ─────────── */

test('⑤ 离开累计到阈值提醒一次，同一轮里继续离开不再重复提醒', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 20 })
  ctx.front.value = OTHER
  ctx.advance(GRACE_MS)          // 直接跨过宽限
  await ctx.tick(9)              // 9 拍 = 18 秒，还不够
  assert.strictEqual(mod.status().remindedEpisodes, 0)
  assert.deepStrictEqual(ctx.bubbles, [])
  assert.strictEqual(mod.status().awayMs, 18 * 1000)

  await ctx.tick(1)              // 第 10 拍 = 20 秒，正好到阈值
  assert.strictEqual(mod.status().remindedEpisodes, 1)
  assert.strictEqual(mod.status().distracted, true)
  assert.strictEqual(ctx.bubbles.length, 1)
  const b = ctx.bubbles[0]
  assert.strictEqual(b.kind, 'focus-remind')
  assert.ok(/Safari/.test(b.text) && /秒/.test(b.text), b.text)
  assert.ok(Number.isFinite(b.ttl) && b.ttl > 0)
  assert.deepStrictEqual(ctx.guards, [{ on: true, distract: false }, { on: true, distract: true }])

  // 继续离开一分钟：仍然只有那一次提醒、光环不再重复下发
  await ctx.tick(30)
  assert.strictEqual(mod.status().remindedEpisodes, 1)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.guards.length, 2)
})

test('⑥ 回到目标应用结束这一轮：光环收回、awayMs 归零，再离开再提醒', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 10 })
  ctx.front.value = OTHER
  ctx.advance(GRACE_MS)
  await ctx.tick(5)              // 10 秒 → 第一次提醒
  assert.strictEqual(mod.status().remindedEpisodes, 1)

  ctx.front.value = TARGET
  await ctx.tick(1)
  let s = mod.status()
  assert.strictEqual(s.distracted, false)
  assert.strictEqual(s.awayMs, 0)
  assert.deepStrictEqual(ctx.guards[2], { on: true, distract: false })

  // 回到目标之后的这一拍不计离开时长
  await ctx.tick(1)
  assert.strictEqual(mod.status().awayMs, 0)

  ctx.front.value = OTHER
  await ctx.tick(5)              // 再来 10 秒
  s = mod.status()
  assert.strictEqual(s.remindedEpisodes, 2)
  assert.strictEqual(ctx.bubbles.length, 2)
  assert.strictEqual(s.distracted, true)
})

test('⑦ 前台是 null 算一次失败、本应用自己算成功：都不算分心，也都不算「回来了」', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 20 })
  ctx.front.value = null
  ctx.advance(GRACE_MS)
  const r1 = await ctx.mod.pollOnce()
  assert.deepStrictEqual(r1, { ok: true, paused: false, unknown: true })
  assert.strictEqual(mod.status().awayMs, 0)
  assert.strictEqual(mod.status().consecutiveErrors, 1)
  assert.deepStrictEqual(ctx.bubbles, [])

  // 认得出是本应用自己：算识别成功（连续错误清零），但同样不计分心
  ctx.front.value = SELF
  const r2 = await ctx.mod.pollOnce()
  assert.deepStrictEqual(r2, { ok: true, unknown: true })
  assert.strictEqual(mod.status().awayMs, 0)
  assert.strictEqual(mod.status().consecutiveErrors, 0)
  assert.deepStrictEqual(ctx.bubbles, [])
  assert.strictEqual(mod.status().remindedEpisodes, 0)
})

/* ─────────── 连续失败 → paused → 自愈 ─────────── */

test('⑧ 连续失败 15 次进入 paused：不提醒、不累计时长，光环只收回一次', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 10 })
  ctx.front.fail = true
  ctx.advance(GRACE_MS)
  await ctx.tick(ERROR_PAUSE - 1)
  assert.strictEqual(mod.status().paused, false, '14 次还不该暂停')
  assert.strictEqual(mod.status().consecutiveErrors, ERROR_PAUSE - 1)
  assert.strictEqual(ctx.guards.length, 1)

  await ctx.tick(1)
  assert.strictEqual(mod.status().paused, true)
  assert.deepStrictEqual(ctx.guards[1], { on: true, distract: false })
  assert.strictEqual(ctx.bubbles.length, 0)
  assert.ok(ctx.logs.some((l) => /暂停计分/.test(l)), '暂停要留一条日志：' + ctx.logs.join(' | '))

  // 暂停期间继续失败：不重复下发光环、不提醒、离开时长不涨
  await ctx.tick(40)
  assert.strictEqual(ctx.guards.length, 2)
  assert.strictEqual(ctx.bubbles.length, 0)
  assert.strictEqual(mod.status().awayMs, 0)
  assert.strictEqual(mod.status().remindedEpisodes, 0)
})

test('⑨ 识别成功一次即恢复：paused 清掉、暂停期的时间不计入离开时长', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 10 })
  ctx.front.fail = true
  ctx.advance(GRACE_MS)
  await ctx.tick(ERROR_PAUSE)
  assert.strictEqual(mod.status().paused, true)

  ctx.front.fail = false
  ctx.front.value = OTHER
  await ctx.tick(1)
  let s = mod.status()
  assert.strictEqual(s.paused, false)
  assert.strictEqual(s.consecutiveErrors, 0)
  assert.strictEqual(s.awayMs, 0, '恢复的这一拍只重新对齐时间，不算离开')
  assert.deepStrictEqual(ctx.guards[2], { on: true, distract: false })

  await ctx.tick(5)              // 恢复后重新攒够 10 秒
  s = mod.status()
  assert.strictEqual(s.remindedEpisodes, 1)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].kind, 'focus-remind')
})

/* ─────────── 到时 / 手动 / 退出 ─────────── */

test('⑩ 到 endsAt 自动完成：focus-done 气泡 + 光环全灭 + 计时器清干净', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { minutes: 5 })
  ctx.advance(5 * MIN)
  await ctx.mod.pollOnce()       // pollOnce 自己也要认到期（不依赖计时器先发火）
  assert.strictEqual(mod.status().active, false)
  assert.strictEqual(ctx.timers.size, 0)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].kind, 'focus-done')
  assert.deepStrictEqual(ctx.guards[ctx.guards.length - 1], { on: false, distract: false })
})

test('⑪ 到时计时器发火也走同一条收尾路径，且不会重复完成', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { minutes: 5 })
  ctx.advance(5 * MIN)
  ctx.fireDue()
  assert.strictEqual(mod.status().active, false)
  assert.strictEqual(ctx.timers.size, 0)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].kind, 'focus-done')

  // 完成之后再跑一次到期计时器 / 再 poll：什么都不改
  ctx.fireDue()
  await ctx.mod.pollOnce()
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.timers.size, 0)
})

test('⑫ stop("user")：focus-info 气泡写明本次时长，计时器清零', () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { minutes: 25, appName: 'Safari' })
  ctx.advance(7 * MIN)
  assert.deepStrictEqual(mod.stop('user'), { ok: true })
  assert.strictEqual(ctx.timers.size, 0)
  assert.strictEqual(mod.status().active, false)
  assert.strictEqual(mod.status().remainingMs, 0)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].kind, 'focus-info')
  assert.ok(/7/.test(ctx.bubbles[0].text), ctx.bubbles[0].text)
  assert.deepStrictEqual(ctx.guards[ctx.guards.length - 1], { on: false, distract: false })

  // 没在跑的时候再 stop：如实报 not-active，不再发气泡
  assert.deepStrictEqual(mod.stop('user'), { ok: false, error: 'not-active' })
  assert.strictEqual(ctx.bubbles.length, 1)
})

test('⑫b 刚开就手动结束：文案说「不到 1 分钟」，不说「本次 0 分钟」', () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { minutes: 25, appName: 'Safari' })
  ctx.advance(GRACE_MS + 20 * 1000)   // 已过宽限期但还不到一分钟
  assert.deepStrictEqual(mod.stop('user'), { ok: true })
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].text, '已结束专注：本次不到 1 分钟')
})

test('⑬ shutdown：清计时器、不往可能已销毁的窗口发气泡，之后 pollOnce 直接不干活', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx)
  ctx.advance(GRACE_MS)
  mod.shutdown()
  assert.strictEqual(ctx.timers.size, 0)
  assert.deepStrictEqual(ctx.bubbles, [])
  assert.strictEqual(mod.status().active, false)

  ctx.front.value = OTHER
  const r = await ctx.mod.pollOnce()
  assert.deepStrictEqual(r, { ok: false, error: 'not-active' })
  assert.deepStrictEqual(ctx.bubbles, [])
  assert.strictEqual(ctx.front.calls, 0, '结束后不该再去查前台应用')
})

test('⑭ 收尾之后在途的检测结果作废：不会把已结束的会话写活', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  startSafari(ctx, { awaySec: 10 })
  ctx.advance(GRACE_MS)
  let release
  ctx.front.gate = new Promise((r) => { release = r })
  ctx.front.value = OTHER
  const inflight = ctx.mod.pollOnce()
  // 同一时刻的第二次 poll 不叠一次采样
  const dup = await ctx.mod.pollOnce()
  assert.deepStrictEqual(dup, { ok: true, skipped: true })
  mod.stop('user')
  release()
  await inflight
  ctx.front.gate = null
  const s = mod.status()
  assert.strictEqual(s.active, false)
  assert.strictEqual(s.awayMs, 0)
  assert.strictEqual(s.remindedEpisodes, 0)
  assert.strictEqual(ctx.bubbles.length, 1, '只该有 stop 的那一个气泡')
  assert.strictEqual(ctx.bubbles[0].kind, 'focus-info')
})

/* ─────────── status 字段 ─────────── */

test('⑮ status 字段齐全：active / paused / target / 起止 / 剩余 / 分心 / 轮次 / 连续错误', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  const idle = mod.status()
  assert.deepStrictEqual(Object.keys(idle).sort(), [
    'active', 'awayMs', 'consecutiveErrors', 'distracted', 'endsAt',
    'paused', 'remainingMs', 'remindedEpisodes', 'startedAt', 'target'
  ])
  assert.strictEqual(idle.active, false)
  assert.strictEqual(idle.remainingMs, 0)
  assert.deepStrictEqual(idle.target, { bundleId: '', name: '' })

  startSafari(ctx, { minutes: 25, awaySec: 10 })
  ctx.advance(5 * MIN)
  const s = mod.status()
  assert.strictEqual(s.active, true)
  assert.strictEqual(s.paused, false)
  assert.strictEqual(s.startedAt, T0)
  assert.strictEqual(s.endsAt, T0 + 25 * MIN)
  assert.strictEqual(s.remainingMs, 20 * MIN)
  assert.strictEqual(s.distracted, false)
  assert.strictEqual(s.awayMs, 0)
  assert.strictEqual(s.remindedEpisodes, 0)
  assert.strictEqual(s.consecutiveErrors, 0)
  assert.deepStrictEqual(s.target, { bundleId: TARGET, name: 'Safari' })

  // appName 允许空字符串（调用方没拿到应用名时）
  mod.stop('user')
  startSafari(ctx, { appName: '' })
  assert.deepStrictEqual(mod.status().target, { bundleId: TARGET, name: '' })
  assert.strictEqual(mod.status().remainingMs, 25 * MIN)
  mod.shutdown()
})

test('⑯ init 幂等：重复 init 不换掉已注入的实现', async () => {
  const ctx = makeCtx()
  const mod = load(ctx)
  let otherCalls = 0
  mod.init({ runFrontmost: async () => { otherCalls++; return OTHER } })
  startSafari(ctx)
  ctx.front.value = TARGET
  ctx.advance(GRACE_MS)
  const r = await mod.pollOnce()
  assert.deepStrictEqual(r, { ok: true, onTarget: true })
  assert.strictEqual(otherCalls, 0, '第二次 init 必须被忽略')
  assert.strictEqual(ctx.front.calls, 1, '必须还是第一次注入的实现')
  mod.shutdown()
})
