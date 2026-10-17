// v1.61：FSRS 调度（对齐安卓 Fsrs.kt）—— node --test test/
const test = require('node:test')
const assert = require('node:assert')
const F = require('../fsrs')

const DAY = F.DAY_MS
// 固定基准时刻（2025-06-15 12:00 UTC 附近），不读时钟 —— 断言要可复现
const T0 = 1750000000000

test('参数向量：21 维 FSRS-6，与安卓逐值一致', () => {
  assert.strictEqual(F.DEFAULT_W.length, 21)
  assert.deepStrictEqual(F.DEFAULT_W, [
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
    0.001, 1.8722, 0.1666, 0.796, 1.4835,
    0.0614, 0.2629, 1.6483,
    0.6014, 1.8729,
    0.5425, 0.0912, 0.0658,
    0.1542
  ])
  const p = F.defaultParams()
  assert.strictEqual(p.w.length, 21)
  assert.strictEqual(p.decay, -0.1542)          // decay = -w₂₀
  assert.deepStrictEqual(p.learningSteps, [1, 10])
  assert.deepStrictEqual(p.relearningSteps, [10])
  assert.strictEqual(p.maximumInterval, 36500)
  // r=0.9 时 intervalModifier 恒等于 1，也就是「间隔 = S」
  assert.ok(Math.abs(p.intervalModifier - 1) < 1e-9, 'modifier=' + p.intervalModifier)
  assert.ok(Math.abs(p.factor - 0.98034649) < 1e-8, 'factor=' + p.factor)
})

test('参数迁移：17 → 19 → 21 都能补齐，坏输入退回默认', () => {
  const w19 = F.DEFAULT_W.slice(0, 19)
  const m19 = F.migrateParams(w19, 1, true)
  assert.strictEqual(m19.length, 21)
  assert.strictEqual(m19[19], 0.0)                       // w19 补 0
  assert.strictEqual(m19[20], F.FSRS5_DEFAULT_DECAY)      // w20 补 decay
  const m17 = F.migrateParams(F.DEFAULT_W.slice(0, 17), 1, true)
  assert.strictEqual(m17.length, 21)
  assert.strictEqual(m17[20], F.FSRS5_DEFAULT_DECAY)
  // FSRS-4.5 的等价变换：w₄ = w₅*2 + w₄，w₅ = ln(w₅*3+1)/3，w₆ = w₆+0.5
  // （w₄ 的新值用的是**变换前**的 w₅，跟安卓 clipParams 后再改的顺序一致）
  assert.ok(Math.abs(m17[4] - (0.8334 * 2 + 6.4133)) < 1e-6, 'w4=' + m17[4])
  assert.ok(Math.abs(m17[5] - Math.log(0.8334 * 3 + 1) / 3) < 1e-6, 'w5=' + m17[5])
  assert.ok(Math.abs(m17[6] - (3.0194 + 0.5)) < 1e-6)
  assert.deepStrictEqual(F.migrateParams([1, 2, 3], 1, true), F.DEFAULT_W.slice())   // 长度不认识 → 默认
  assert.deepStrictEqual(F.migrateParams(null, 1, true), F.DEFAULT_W.slice())
  // 越界 / NaN 全部被裁进合法域，不会产生 NaN
  const bad = F.Params.of({ w: [NaN, -5, 1e9, ...F.DEFAULT_W.slice(3)] })
  assert.ok(bad.w.every((v) => isFinite(v)))
  assert.ok(bad.w[1] >= F.S_MIN && bad.w[1] <= F.INIT_S_MAX)
  // decay 落在 [0.1, 0.8] → 绝不为 0，所有除法安全
  assert.ok(bad.decay <= -0.1 && bad.decay >= -0.8, 'decay=' + bad.decay)
})

test('学习步骤：只留正数分钟；重学步骤 >1 时收紧 w17/w18', () => {
  assert.deepStrictEqual(F.sanitizeSteps([1, 10]), [1, 10])
  assert.deepStrictEqual(F.sanitizeSteps([0, -5, 3]), [3])
  assert.deepStrictEqual(F.sanitizeSteps([-1, 0]), [])       // 全非正 → 空数组（交给 FSRS 自己算）
  const p1 = F.Params.of({ relearningSteps: [10] })
  const p3 = F.Params.of({ relearningSteps: [10, 10, 10] })
  assert.ok(p3.w[17] <= p1.w[17] + 1e-9, '重学步数多 → w17 天花板更低')
  assert.ok(p3.w[17] <= F.W17_W18_CEILING && p3.w[18] <= F.W17_W18_CEILING)
})

test('遗忘曲线：幂函数版 t=S 时 R=0.9；指数版保留供迁移老数据', () => {
  const p = F.defaultParams()
  const S = 10
  assert.strictEqual(F.forgettingCurve(0, S, p.decay), 1.0)          // 刚复习完记得牢
  assert.ok(Math.abs(F.forgettingCurve(S, S, p.decay) - 0.9) < 1e-6, 'R(S)=' + F.forgettingCurve(S, S, p.decay))
  assert.ok(F.forgettingCurve(2 * S, S, p.decay) < 0.9)             // 拖越久越低
  assert.strictEqual(F.forgettingCurve(5, 0, p.decay), 0.0)          // S=0（新卡）→ 0
  assert.strictEqual(F.forgettingCurve(5, -1, p.decay), 0.0)         // 非法 S 不炸
  // 指数式：exp(-t/S)，与幂函数量纲不同
  assert.ok(Math.abs(F.forgettingCurveExp(S, S) - Math.exp(-1)) < 1e-6)
  assert.ok(Math.abs(F.retrievabilityExp(S, S) - (1 - Math.exp(-1))) < 1e-6)
})

test('难度更新：Again > Hard > Good > Easy，且恒在 [1,10]', () => {
  const p = F.defaultParams()
  const d = F.nextDifficulty(5, F.Rating.Again, p)
  const h = F.nextDifficulty(5, F.Rating.Hard, p)
  const g = F.nextDifficulty(5, F.Rating.Good, p)
  const e = F.nextDifficulty(5, F.Rating.Easy, p)
  assert.ok(d > h && h > g && g > e, [d, h, g, e].join(' '))
  for (const x of [1, 3, 5, 8, 10]) {
    for (const r of F.RATING_ALL) {
      const v = F.nextDifficulty(x, r, p)
      assert.ok(v >= 1 && v <= 10, 'D=' + v)
    }
  }
  // 均值回归把 D 往 D₀(Easy) 拉：连续 Again 也涨不破 10
  let dd = 9.9
  for (let i = 0; i < 50; i++) dd = F.nextDifficulty(dd, F.Rating.Again, p)
  assert.ok(dd <= 10 && dd >= 1)
})

test('稳定度更新：准时复习几乎不涨；忘得越狠涨得越多；Again 必降', () => {
  const p = F.defaultParams()
  const d = 5, s = 20
  const onTime = F.nextRecallStability(d, s, 0.9, F.Rating.Good, p)
  const late = F.nextRecallStability(d, s, 0.5, F.Rating.Good, p)
  assert.ok(onTime > s && late > onTime, '准时 ' + onTime + ' / 迟到 ' + late)
  // Hard 有惩罚、Easy 有加成：同条件下 Easy ≥ Good ≥ Hard
  const h = F.nextRecallStability(d, s, 0.8, F.Rating.Hard, p)
  const g = F.nextRecallStability(d, s, 0.8, F.Rating.Good, p)
  const e = F.nextRecallStability(d, s, 0.8, F.Rating.Easy, p)
  assert.ok(h < g && g < e, [h, g, e].join(' '))
  // 遗忘公式：S'f 必须比原 S 小（不许不降反增）
  for (const S of [1, 5, 50, 500]) {
    const f = F.nextForgetStability(6, S, 0.7, p)
    assert.ok(f < S, 'S=' + S + ' → S\'=' + f)
    assert.ok(f >= F.S_MIN)
  }
})

test('nextState：d=0&s=0 走新卡；t=0 走短期记忆；Again 走遗忘', () => {
  const p = F.defaultParams()
  const ns = F.nextState(0, 0, 0, F.Rating.Good, p)
  assert.ok(ns.d >= 1 && ns.d <= 10)
  assert.strictEqual(ns.s, F.initStability(F.Rating.Good, p))
  // 同一天内连按走短期记忆公式（不该等于 0 也不该爆）
  const st = F.nextState(5, 10, 0, F.Rating.Good, p)
  assert.ok(st.s > 0 && isFinite(st.s))
  // 负 elapsed（改系统时间）按 0 天算，不抛
  const neg = F.nextState(5, 10, -3, F.Rating.Good, p)
  assert.ok(isFinite(neg.s) && neg.d >= 1)
})

test('调度：新卡 Good → 学习步骤 10 分钟；出学习步 → Review', () => {
  const p = F.defaultParams()
  const c1 = F.review(F.card(), F.Rating.Good, T0, p)
  assert.strictEqual(c1.state, F.State.Learning)
  assert.strictEqual(c1.reps, 1)
  assert.strictEqual(c1.learningStep, 1)             // [1,10] 的第 2 档 = 10 分钟
  assert.strictEqual(c1.due, T0 + 10 * 60 * 1000)
  assert.strictEqual(c1.lastReview, T0)
  // 走完学习步 → 进 Review，间隔按稳定度算天数
  const c2 = F.review(c1, F.Rating.Good, T0 + 11 * 60 * 1000, p)
  assert.strictEqual(c2.state, F.State.Review)
  assert.ok(c2.scheduledDays >= 1, 'scheduledDays=' + c2.scheduledDays)
  assert.strictEqual(c2.due, T0 + 11 * 60 * 1000 + c2.scheduledDays * DAY)
  // Review 态四档：Again 打回 Relearning 且 lapses +1，其余留在 Review 且间隔递增
  const base = F.review(c1, F.Rating.Good, T0 + 11 * 60 * 1000, p)
  const a = F.review(base, F.Rating.Again, base.due, p)
  assert.strictEqual(a.state, F.State.Relearning)
  assert.strictEqual(a.lapses, base.lapses + 1)
  const pv = F.preview(base, base.due, p)
  assert.ok(pv.Hard.scheduledDays <= pv.Good.scheduledDays, 'Hard ≤ Good')
  assert.ok(pv.Good.scheduledDays < pv.Easy.scheduledDays, 'Good < Easy')
  assert.strictEqual(pv.Easy.state, F.State.Review)
})

test('调度：连续 Good 间隔单调递增（FSRS 的核心承诺）', () => {
  const p = F.defaultParams()
  let c = F.card()
  let now = T0
  const days = []
  for (let i = 0; i < 6; i++) {
    c = F.review(c, F.Rating.Good, now, p)
    now = c.due                          // 每次都在到期那天复习（准时）
    if (c.state === F.State.Review) days.push(c.scheduledDays)
  }
  assert.ok(days.length >= 3, '只跑到 ' + days.length + ' 轮 Review')
  for (let i = 1; i < days.length; i++) {
    assert.ok(days[i] > days[i - 1], days.join(' → '))
  }
})

test('到期判定：新卡恒到期；due <= now 才算；按日历日判不误伤', () => {
  const p = F.defaultParams()
  let c = F.review(F.card(), F.Rating.Good, T0, p)
  while (c.state !== F.State.Review) c = F.review(c, F.Rating.Good, c.due, p)
  assert.strictEqual(F.isDue(c, c.due), true)
  assert.strictEqual(F.isDue(c, c.due - 1), false)
  assert.strictEqual(F.isDue(F.card(), 0), true)                 // 新卡永远到期
  // 给了当天 0 点就只拿 due 和「当天起点」比（安卓同款语义）：
  // due 落在今天之后的卡不算今日到期 —— 上午背的卡 due 在下午，不该被当成逾期。
  const dayStart = T0 - (T0 % DAY)
  assert.strictEqual(F.isDue(c, T0, dayStart), false)            // due 在 dayStart 之后
  assert.strictEqual(F.isDue(c, c.due + DAY, c.due), true)       // due <= 传入的界 → 到期
  assert.strictEqual(F.isDue(c, c.due, c.due - 1), false)
  // daysBetween 走 UTC 日历天，不足一天算 0
  assert.strictEqual(F.daysBetween(T0, T0 + 3600 * 1000), 0)
  assert.strictEqual(F.daysBetween(T0, T0 + DAY), 1)
  assert.strictEqual(F.daysBetween(T0 + DAY, T0), -1)
  // 时钟回拨（负数）不炸
  assert.ok(F.daysBetween(T0, T0 - DAY) < 0)
})

test('dueCards：新卡排最前，其余按 due 升序；入参不被改', () => {
  const p = F.defaultParams()
  const old = Object.assign(F.card({ state: F.State.Review, due: T0 - 5 * DAY, stability: 30, difficulty: 5, lastReview: T0 - 10 * DAY }))
  const newer = Object.assign(F.card({ state: F.State.Review, due: T0 - DAY, stability: 10, difficulty: 5, lastReview: T0 - 5 * DAY }))
  const fresh = F.card()
  const list = [old, newer, fresh]
  const due = F.dueCards(list, T0)
  assert.strictEqual(due.length, 3)
  assert.strictEqual(due[0].state, F.State.New)      // 新卡第一
  assert.strictEqual(due[1].due, T0 - 5 * DAY)
  assert.strictEqual(due[2].due, T0 - DAY)
  assert.strictEqual(F.dueCount(list, T0), 3)
  // 纯函数：入参顺序不变
  assert.deepStrictEqual(list.map((c) => c.due), [T0 - 5 * DAY, T0 - DAY, 0])
})

test('序列化：字段名 snake_case，last_review 为 0 写 null，往返一致', () => {
  const p = F.defaultParams()
  let c = F.review(F.card(), F.Rating.Good, T0, p)
  c = F.review(c, F.Rating.Good, c.due, p)
  const j = F.toJson(c)
  assert.deepStrictEqual(Object.keys(j),
    ['state', 'stability', 'difficulty', 'due', 'last_review', 'reps', 'lapses', 'learning_step', 'scheduled_days', 'elapsed_days'])
  const fresh = F.toJson(F.card())
  assert.strictEqual(fresh.last_review, null)        // 从未复习 → null（与安卓一致）
  // 往返：序列化 → 解析，卡片不变
  const back = F.cardFromJson(JSON.stringify(j))
  assert.deepStrictEqual(F.toJson(back), j)
  // 列表往返 + 坏元素跳过
  const arr = F.toJsonList([c, F.card()])
  assert.strictEqual(arr.length, 2)
  assert.strictEqual(F.cardsFromJson(arr).length, 2)
  assert.strictEqual(F.cardsFromJson([null, 'x', 3]).length, 0)
  assert.strictEqual(F.cardFromJson('{坏 json'), null)
  assert.deepStrictEqual(F.cardsFromJson(null), [])
  // 状态容错：数字 / 名字 / 垃圾
  assert.strictEqual(F.stateOf(2), F.State.Review)
  assert.strictEqual(F.stateOf('Review'), F.State.Review)
  assert.strictEqual(F.stateOf('review'), F.State.Review)
  assert.strictEqual(F.stateOf('乱写'), F.State.New)
  assert.strictEqual(F.ratingOf(3), F.Rating.Good)
  assert.strictEqual(F.ratingOf('Easy'), F.Rating.Easy)
  assert.strictEqual(F.ratingOf('9'), null)
  assert.strictEqual(F.ratingName(F.Rating.Hard), 'Hard')
  assert.strictEqual(F.stateName(F.State.Relearning), 'Relearning')
})

test('参数序列化：21 维往返，坏输入退回默认不抛', () => {
  const w = F.paramsToJson()
  assert.strictEqual(w.length, 21)
  assert.deepStrictEqual(F.paramsFromJson(w).w, F.defaultParams().w)
  assert.deepStrictEqual(F.paramsFromJson(null).w, F.defaultParams().w)
  assert.deepStrictEqual(F.paramsFromJson('nonsense').w, F.defaultParams().w)
})

test('可记忆性：新卡按 0；到 due 那天正好回到 0.9 附近', () => {
  const p = F.defaultParams()
  assert.strictEqual(F.retrievability(F.card(), T0, p), 0.0)
  let c = F.review(F.card(), F.Rating.Good, T0, p)
  while (c.state !== F.State.Review) c = F.review(c, F.Rating.Good, c.due, p)
  // 稳定度 S 就是「记忆保持 90% 所需的天数」，所以到期那天的 R ≈ 0.9
  const atDue = F.retrievability(c, c.due, p)
  assert.ok(Math.abs(atDue - 0.9) < 0.02, 'R(due)=' + atDue)
  assert.ok(F.retrievability(c, c.due + 30 * DAY, p) < atDue)   // 拖久了更忘
})