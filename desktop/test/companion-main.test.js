// companion-main：陪伴短句调度器的回归（假计时器 / 假气泡 / 假 AI，无真实窗口与网络）。
// 只覆盖「真出过问题就会出事」的行为：首次延迟落在档位区间、洗牌袋不重句、
// 每第 3 次才问 AI 且失败/超时/超长都能回退、关掉开关立刻停摆。
'use strict'

const test = require('node:test')
const assert = require('node:assert')

function flush() {
  return new Promise((resolve) => setImmediate(resolve))
}

/** 假计时器：只排队不真跑，测试自己推进时间去触发；clearTimer 记下撤销过的句柄。 */
function makeTimers(start) {
  const t = { now: start, seq: 0, jobs: [], clearedIds: [] }
  t.setTimer = (fn, delay) => {
    const id = ++t.seq
    t.jobs.push({ id: id, fn: fn, at: t.now + (delay || 0), delay: delay || 0 })
    return id
  }
  t.clearTimer = (id) => {
    const i = t.jobs.findIndex((j) => j.id === id)
    if (i < 0) return
    t.clearedIds.push(id)
    t.jobs.splice(i, 1)
  }
  t.earliest = () => t.jobs.slice().sort((a, b) => a.at - b.at)[0] || null
  /** 推进到 now+ms，按时间顺序跑完所有到点的回调（回调里新排的也算，但有上限防空转）。 */
  t.advance = async (ms) => {
    const target = t.now + ms
    let guard = 0
    for (;;) {
      const due = t.jobs.filter((j) => j.at <= target).sort((a, b) => a.at - b.at)[0]
      if (!due) break
      guard++
      if (guard > 200) throw new Error('假计时器空转：回调在不停重排')
      t.jobs.splice(t.jobs.indexOf(due), 1)
      t.now = Math.max(t.now, due.at)
      due.fn()
      await flush()
    }
    t.now = target
  }
  return t
}

function boot(opts) {
  const o = opts || {}
  const timers = makeTimers(o.start === undefined ? 1000 : o.start)
  const bubbles = []
  const aiCalls = { n: 0 }
  const aiImpl = o.aiLine || (async () => null)
  delete require.cache[require.resolve('../companion-main')]
  const mod = require('../companion-main')
  mod.init({
    modeLog: () => {},
    now: () => timers.now,
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    showBubble: (b) => bubbles.push(b),
    aiLine: () => { aiCalls.n++; return aiImpl() },
    envNextOverride: o.envNextOverride === undefined ? null : o.envNextOverride,
    initial: o.initial
  })
  return { mod: mod, timers: timers, bubbles: bubbles, aiCalls: aiCalls }
}

test('短句池：至少 40 条、互不重复、每条不超过 28 字', () => {
  const ctx = boot({ initial: { on: false } })
  const pool = ctx.mod.POOL
  assert.ok(Array.isArray(pool) && pool.length >= 40, '池子条数不足：' + pool.length)
  assert.strictEqual(new Set(pool).size, pool.length, '池子里有重复短句')
  for (const s of pool) {
    assert.strictEqual(typeof s, 'string')
    assert.ok(s.trim() === s && s.length > 0)
    assert.ok(Array.from(s).length <= 28, '短句超过 28 字：' + s)
  }
})

test('首次延迟落在档位区间内（low / med / high）', () => {
  const realRandom = Math.random
  try {
    for (const freq of ['low', 'med', 'high']) {
      for (const rnd of [0, 0.5, 0.999999]) {
        Math.random = () => rnd
        const ctx = boot({ initial: { on: true, freq: freq } })
        const range = ctx.mod.FREQ_MINUTES[freq]
        const job = ctx.timers.earliest()
        assert.ok(job, freq + ' 没有排期')
        assert.ok(job.delay >= range[0] * 60000, freq + ' 早于下限：' + job.delay)
        assert.ok(job.delay <= range[1] * 60000, freq + ' 晚于上限：' + job.delay)
        assert.strictEqual(ctx.mod.status().nextAtMs, ctx.timers.now + job.delay)
        ctx.mod.shutdown()
      }
    }
  } finally {
    Math.random = realRandom
  }
})

test('envNextOverride 优先于档位区间（0 也照用）', () => {
  const ctx = boot({ initial: { on: true, freq: 'low' }, envNextOverride: 1234 })
  const job = ctx.timers.earliest()
  assert.ok(job)
  assert.strictEqual(job.delay, 1234)
  assert.strictEqual(ctx.mod.status().nextAtMs, 1000 + 1234)
  ctx.mod.shutdown()

  const zero = boot({ initial: { on: true, freq: 'high' }, envNextOverride: 0 })
  const zjob = zero.timers.earliest()
  assert.ok(zjob)
  assert.strictEqual(zjob.delay, 0)
  zero.mod.shutdown()
})

test('到点自动展示一次并重排下一次', async () => {
  const ctx = boot({ initial: { on: true, freq: 'med' }, envNextOverride: 60000 })
  assert.strictEqual(ctx.bubbles.length, 0)
  await ctx.timers.advance(60000)
  assert.strictEqual(ctx.bubbles.length, 1)
  const b = ctx.bubbles[0]
  assert.strictEqual(b.kind, 'companion')
  assert.strictEqual(b.ttl, 8000)
  assert.ok(typeof b.text === 'string' && b.text.length > 0)
  const st = ctx.mod.status()
  assert.strictEqual(st.shownCount, 1)
  assert.strictEqual(st.lastText, b.text)
  // 展示完必须重新排上：下个时间点 = 当前 + 60000
  assert.strictEqual(st.nextAtMs, ctx.timers.now + 60000)
  ctx.mod.shutdown()
})

test('洗牌袋：一轮内不重复，袋空重洗且不接着上一句', async () => {
  const ctx = boot({ initial: { on: true, freq: 'med' } })
  const pool = ctx.mod.POOL.length
  const seen = []
  for (let i = 0; i < pool; i++) {
    const r = await ctx.mod.fireOnce()
    assert.strictEqual(r.ok, true)
    seen.push(r.text)
  }
  assert.strictEqual(new Set(seen).size, pool, '一轮内出现重复短句')
  const next = await ctx.mod.fireOnce()
  assert.notStrictEqual(next.text, seen[seen.length - 1], '重洗后第一句与上一句相同')
  assert.ok(ctx.mod.POOL.includes(next.text))
  ctx.mod.shutdown()
})

test('ai 关闭时永远不问接口；开着也只每第 3 次问', async () => {
  const off = boot({ initial: { on: true, freq: 'med', ai: false }, aiLine: async () => 'AI 的话' })
  for (let i = 0; i < 6; i++) await off.mod.fireOnce()
  assert.strictEqual(off.aiCalls.n, 0)
  assert.ok(off.mod.POOL.includes(off.mod.status().lastText))
  off.mod.shutdown()

  const on = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: async () => 'AI 的话' })
  const first = await on.mod.fireOnce()
  assert.strictEqual(first.ai, false)
  assert.strictEqual(on.aiCalls.n, 0)
  await on.mod.fireOnce()
  assert.strictEqual(on.aiCalls.n, 0)
  const third = await on.mod.fireOnce()
  assert.strictEqual(on.aiCalls.n, 1)
  assert.strictEqual(third.text, 'AI 的话')
  assert.strictEqual(third.ai, true)
  await on.mod.fireOnce()
  await on.mod.fireOnce()
  assert.strictEqual(on.aiCalls.n, 1, '第 4、5 次不该再问接口')
  await on.mod.fireOnce()
  assert.strictEqual(on.aiCalls.n, 2, '第 6 次应该再问一次')
  on.mod.shutdown()
})

test('AI 文本清洗：去引号、压空白，结果落进气泡', async () => {
  const ctx = boot({
    initial: { on: true, freq: 'med', ai: true },
    aiLine: async () => '  “专注\n  一会儿吧”  '
  })
  await ctx.mod.fireOnce()
  await ctx.mod.fireOnce()
  const r = await ctx.mod.fireOnce()
  assert.strictEqual(r.ai, true)
  assert.strictEqual(r.text, '专注 一会儿吧')
  assert.strictEqual(ctx.bubbles[ctx.bubbles.length - 1].text, '专注 一会儿吧')
  ctx.mod.shutdown()
})

test('AI 抛异常 / 返回 null / 返回空白 → 回退内置句，不抛出去', async () => {
  for (const impl of [
    async () => { throw new Error('接口炸了') },
    async () => null,
    async () => '   '
  ]) {
    const ctx = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: impl })
    await ctx.mod.fireOnce()
    await ctx.mod.fireOnce()
    const r = await ctx.mod.fireOnce()
    assert.strictEqual(r.ok, true)
    assert.strictEqual(r.ai, false)
    assert.ok(ctx.mod.POOL.includes(r.text), '失败时没用内置句：' + r.text)
    assert.strictEqual(ctx.mod.status().shownCount, 3)
    ctx.mod.shutdown()
  }
})

test('AI 超过 14 秒没回来就回退，且不会卡住后续展示', async () => {
  let release = null
  const gate = new Promise((resolve) => { release = resolve })
  const ctx = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: () => gate })
  await ctx.mod.fireOnce()
  await ctx.mod.fireOnce()
  const waiting = ctx.mod.fireOnce()
  await flush()
  assert.strictEqual(ctx.aiCalls.n, 1)
  await ctx.timers.advance(13999)
  let settled = false
  waiting.then(() => { settled = true })
  await flush()
  assert.strictEqual(settled, false, '14 秒前就放弃了')
  await ctx.timers.advance(1)
  const r = await waiting
  assert.strictEqual(r.ai, false)
  assert.ok(ctx.mod.POOL.includes(r.text))
  release('迟到的 AI 文本')      // 迟到的结果不能再改已经展示过的内容
  await flush()
  assert.strictEqual(ctx.mod.status().lastText, r.text)
  ctx.mod.shutdown()
})

test('AI 文本超过 60 字回退内置句（60 字边界保留）', async () => {
  const long = '好'.repeat(61)
  const edge = '边'.repeat(60)
  const ctx = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: async () => long })
  await ctx.mod.fireOnce()
  await ctx.mod.fireOnce()
  const r = await ctx.mod.fireOnce()
  assert.strictEqual(r.ai, false)
  assert.ok(ctx.mod.POOL.includes(r.text))
  ctx.mod.shutdown()

  const ctx2 = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: async () => edge })
  await ctx2.mod.fireOnce()
  await ctx2.mod.fireOnce()
  const r2 = await ctx2.mod.fireOnce()
  assert.strictEqual(r2.ai, true)
  assert.strictEqual(r2.text, edge)
  ctx2.mod.shutdown()
})

test('applySettings({on:false}) 清计时器停摆，再开立刻重排', () => {
  const ctx = boot({ initial: { on: true, freq: 'med' }, envNextOverride: 60000 })
  assert.strictEqual(ctx.timers.jobs.length, 1)
  const firstId = ctx.timers.jobs[0].id
  const r = ctx.mod.applySettings({ on: false })
  assert.strictEqual(r.ok, true)
  assert.strictEqual(ctx.timers.jobs.length, 0, '关掉后还留着计时器')
  assert.ok(ctx.timers.clearedIds.includes(firstId), '没撤掉原来那个句柄')
  const st = ctx.mod.status()
  assert.strictEqual(st.on, false)
  assert.strictEqual(st.nextAtMs, 0)

  ctx.timers.now = 5000
  ctx.mod.applySettings({ on: true })
  assert.strictEqual(ctx.mod.status().on, true)
  assert.strictEqual(ctx.timers.jobs.length, 1)
  assert.strictEqual(ctx.mod.status().nextAtMs, 5000 + 60000)
  ctx.mod.shutdown()
})

test('applySettings 改频率即时重排，脏值不动档位', () => {
  const ctx = boot({ initial: { on: true, freq: 'low' }, envNextOverride: null })
  const before = ctx.timers.earliest()
  ctx.mod.applySettings({ freq: 'high' })
  const after = ctx.timers.earliest()
  assert.strictEqual(ctx.mod.status().freq, 'high')
  assert.strictEqual(ctx.timers.jobs.length, 1)
  assert.ok(after.id !== before.id)
  assert.ok(after.delay >= 15 * 60000 && after.delay <= 60 * 60000)
  ctx.mod.applySettings({ freq: '疯狂' })
  assert.strictEqual(ctx.mod.status().freq, 'high')
  ctx.mod.applySettings({ ai: true })
  assert.strictEqual(ctx.mod.status().ai, true)
  ctx.mod.shutdown()
})

test('fireOnce 立即展示并重排，status 字段完整', async () => {
  const ctx = boot({ initial: { on: false } })
  assert.strictEqual(ctx.timers.jobs.length, 0, '关着不该有排期')
  const st0 = ctx.mod.status()
  assert.strictEqual(st0.on, false)
  assert.strictEqual(st0.freq, 'med')
  assert.strictEqual(st0.ai, false)
  assert.strictEqual(st0.nextAtMs, 0)
  assert.strictEqual(st0.shownCount, 0)
  assert.strictEqual(st0.lastText, '')

  const r = await ctx.mod.fireOnce()
  assert.strictEqual(r.ok, true)
  assert.strictEqual(ctx.bubbles.length, 1)
  assert.strictEqual(ctx.bubbles[0].kind, 'companion')
  assert.strictEqual(ctx.bubbles[0].ttl, 8000)
  const st1 = ctx.mod.status()
  assert.strictEqual(st1.shownCount, 1)
  assert.strictEqual(st1.lastText, ctx.bubbles[0].text)
  assert.strictEqual(st1.nextAtMs, 0, '关着的时候手动触发不该留下排期')
  assert.strictEqual(ctx.timers.jobs.length, 0)
  ctx.timers.now = 10000
  ctx.mod.applySettings({ on: true })
  const st2 = ctx.mod.status()
  assert.strictEqual(st2.on, true)
  assert.ok(st2.nextAtMs > 10000)
  assert.strictEqual(ctx.timers.jobs.length, 1)
  ctx.mod.shutdown()
})

test('shutdown 撤干净计时器，之后手动触发也不排期', async () => {
  const ctx = boot({ initial: { on: true, freq: 'med' }, envNextOverride: 60000 })
  ctx.mod.shutdown()
  assert.strictEqual(ctx.timers.jobs.length, 0)
  assert.strictEqual(ctx.mod.status().on, false)
  assert.strictEqual(ctx.mod.status().nextAtMs, 0)
  await ctx.mod.fireOnce()
  assert.strictEqual(ctx.timers.jobs.length, 0)
})

test('关掉开关时正在等 AI 的那次不展示、不计数', async () => {
  let release = null
  const gate = new Promise((resolve) => { release = resolve })
  const ctx = boot({ initial: { on: true, freq: 'med', ai: true }, aiLine: () => gate })
  await ctx.mod.fireOnce()
  await ctx.mod.fireOnce()
  const waiting = ctx.mod.fireOnce()
  await flush()
  ctx.mod.applySettings({ on: false })
  release('这一句不该出现')
  const r = await waiting
  assert.strictEqual(r.skipped, true)
  assert.strictEqual(ctx.bubbles.length, 2, '关掉后还冒了气泡')
  assert.strictEqual(ctx.mod.status().shownCount, 2)
  assert.strictEqual(ctx.mod.status().nextAtMs, 0)
})

test('init 幂等：重复 init 不会多排一份', () => {
  const ctx = boot({ initial: { on: true, freq: 'med' }, envNextOverride: 60000 })
  assert.strictEqual(ctx.timers.jobs.length, 1)
  ctx.mod.init({ initial: { on: true, freq: 'high' }, setTimer: ctx.timers.setTimer })
  assert.strictEqual(ctx.timers.jobs.length, 1, '第二次 init 又排了一次')
  assert.strictEqual(ctx.mod.status().freq, 'med')
  ctx.mod.shutdown()
})
