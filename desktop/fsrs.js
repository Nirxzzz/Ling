/* v1.61（对齐安卓 v1.69 Fsrs.kt）：FSRS 间隔重复算法 · 纯 JS / 纯 Node 实现。
 *
 * ## 出处
 * 逐行对照安卓 [Fsrs.kt]（Kotlin），而安卓那份又是逐行对照
 * [ts-fsrs](https://github.com/open-spaced-repetition/ts-fsrs) `packages/fsrs/src`（MIT）。
 * KDoc/公式编号沿用安卓那边的标注，溯源时看 Kotlin 版即可。
 *
 * ## 与安卓的差异（有意为之，只有两条）
 * 1. **枚举换成普通常量。** Kotlin enum → JS 的 `{ New: 0, ... }` 冻结对象（`State` / `Rating`），
 *    编码与 ts-fsrs `models.ts` 一致，序列化后两端可直接互换。
 * 2. **Kotlin data class → 工厂函数 + 平面对象。** `Card(...)` 返回普通对象，字段名沿用
 *    安卓的驼峰；写盘时才转成 ts-fsrs 的 snake_case（`toJson`），跟安卓完全一样。
 *
 * 其它一律照搬，包括：日期用毫秒整数、参数固定 21 维、elapsedDays 做 max(0) 防御、
 * 学习步骤是构造期定下的分钟数组。**参数向量与安卓逐值相同** —— 这是两端卡片能互换的前提，
 * 改任何一个数都会让两端的间隔算不一致。
 *
 * 只做算法核心（状态机 / 参数 / 调度 / 到期判定 / 序列化）。
 * 不含 Anki 的 fuzz、埋点、撤销（rollback）、重算（reschedule）。
 *
 * 纯模块：不 require('electron')、不读文件、不读时钟（now 一律由调用方传进来），
 * 所以能直接 `node test/fsrs.test.js` 跑单测。
 */
;(function (root, factory) {
  const api = factory()
  if (typeof module !== 'undefined' && module.exports) module.exports = api
  if (typeof window !== 'undefined') window.LZFsrs = api
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict'

  // ───────────────────────── 常量（对应 ts-fsrs constant.ts） ─────────────────────────

  /** 稳定度下限。 */
  const S_MIN = 0.001
  /** 稳定度上限（100 年）。 */
  const S_MAX = 36500.0
  /** 首刷初始稳定度上限。 */
  const INIT_S_MAX = 100.0
  /** 默认请求留存率 0.9。 */
  const DEFAULT_REQUEST_RETENTION = 0.9
  /** 默认最大间隔 100 年（天）。 */
  const DEFAULT_MAXIMUM_INTERVAL = 36500
  /** 19 参数（FSRS-5）补齐时使用的 decay。 */
  const FSRS5_DEFAULT_DECAY = 0.5
  /** 遗忘曲线里 w₁₇ / w₁₈ 的天花板。多次重学时会被收得更紧（见 clipParams）。 */
  const W17_W18_CEILING = 2.0

  const DAY_MS = 24 * 60 * 60 * 1000
  const MINUTE_MS = 60 * 1000

  /** 默认学习步骤 [1m, 10m]（分钟）。 */
  const DEFAULT_LEARNING_STEPS = Object.freeze([1, 10])
  /** 默认重学步骤 [10m]（分钟）。 */
  const DEFAULT_RELEARNING_STEPS = Object.freeze([10])

  /**
   * FSRS-6 默认参数向量（21 维，w₂₀ = decay = 0.1542）。
   * 逐值抄自 ts-fsrs `constant.ts:default_w`，与安卓 Fsrs.kt 的 DEFAULT_W **逐值相同**，不做任何调参。
   */
  const DEFAULT_W = Object.freeze([
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,   // w0..w6  初始 S / 初始 D
    0.001, 1.8722, 0.1666, 0.796, 1.4835,                   // w7..w11 难度均值回归 / 记忆 S
    0.0614, 0.2629, 1.6483,                                 // w12..w14 遗忘 S
    0.6014, 1.8729,                                         // w15..w16 Hard / Easy 乘子
    0.5425, 0.0912, 0.0658,                                 // w17..w19 短期记忆 S
    0.1542                                                  // w20     decay
  ])

  /**
   * 参数裁剪上下界 [下界, 上界]。第 20 项（decay）落在 [0.1, 0.8] ——
   * 这保证了 params.decay 非零，除法永远安全。
   */
  const PARAM_BOUNDS = Object.freeze([
    [S_MIN, INIT_S_MAX],        // w0  初始稳定度 Again
    [S_MIN, INIT_S_MAX],        // w1  初始稳定度 Hard
    [S_MIN, INIT_S_MAX],        // w2  初始稳定度 Good
    [S_MIN, INIT_S_MAX],        // w3  初始稳定度 Easy
    [1.0, 10.0],                // w4  初始难度
    [0.001, 4.0],               // w5  初始难度乘子
    [0.001, 4.0],               // w6  难度乘子
    [0.001, 0.75],              // w7  难度均值回归系数
    [0.0, 4.5],                 // w8  S 指数
    [0.0, 0.8],                 // w9  S 负幂
    [0.001, 3.5],               // w10 S 指数
    [0.001, 5.0],               // w11 遗忘 S 乘子
    [0.001, 0.25],              // w12 遗忘 S 负幂
    [0.001, 0.9],               // w13 遗忘 S 幂
    [0.0, 4.0],                 // w14 遗忘 S 指数
    [0.0, 1.0],                 // w15 Hard 乘子
    [1.0, 6.0],                 // w16 Easy 乘子
    [0.0, W17_W18_CEILING],     // w17 短期 S 指数
    [0.0, W17_W18_CEILING],     // w18 短期 S 指数
    [0.0, 0.8],                 // w19 短期末次 S 指数
    [0.1, 0.8]                  // w20 decay
  ])

  // ───────────────────────── 枚举 → 冻结常量对象 ─────────────────────────

  /** 卡片状态。编码与 ts-fsrs `models.ts:State` 一致，可直接和 Anki 库互转。 */
  const State = Object.freeze({ New: 0, Learning: 1, Review: 2, Relearning: 3 })
  const STATE_NAMES = Object.freeze(['New', 'Learning', 'Review', 'Relearning'])

  /** 评分。编码与 ts-fsrs `models.ts:Rating` 一致（0=Manual 不用，故从 1 起）。 */
  const Rating = Object.freeze({ Again: 1, Hard: 2, Good: 3, Easy: 4 })
  const RATING_NAMES = Object.freeze(['Again', 'Hard', 'Good', 'Easy'])
  const RATING_ALL = Object.freeze([Rating.Again, Rating.Hard, Rating.Good, Rating.Easy])

  /** 容错解析状态：`0..3` 数字或 `"Review"` 这类名字；认不出返回 State.New。 */
  function stateOf(raw) {
    if (typeof raw === 'number') return STATE_NAMES[raw] != null ? raw : State.New
    if (typeof raw === 'string') {
      const n = raw.trim()
      for (let i = 0; i < STATE_NAMES.length; i++) if (STATE_NAMES[i].toLowerCase() === n.toLowerCase()) return i
      const k = Number(n)
      if (Number.isInteger(k)) return STATE_NAMES[k] != null ? k : State.New
      return State.New
    }
    return State.New
  }

  /** 容错解析评分：1..4 数字或 `"Good"` 这类名字；认不出返回 null。 */
  function ratingOf(raw) {
    if (typeof raw === 'number') return raw >= 1 && raw <= 4 ? raw : null
    if (typeof raw === 'string') {
      const n = raw.trim()
      for (let i = 0; i < RATING_NAMES.length; i++) if (RATING_NAMES[i].toLowerCase() === n.toLowerCase()) return i + 1
      const k = Number(n)
      if (Number.isInteger(k) && k >= 1 && k <= 4) return k
      return null
    }
    return null
  }

  /** 状态 → 名字（安卓 `State.name`）。 */
  function stateName(code) { return STATE_NAMES[code] || STATE_NAMES[State.New] }
  /** 评分 → 名字（安卓 `Rating.name`）。 */
  function ratingName(code) { return RATING_NAMES[(code || 0) - 1] || '' }

  // ───────────────────────── 卡片 ─────────────────────────

  /**
   * 一张记忆卡。时间戳一律毫秒；lastReview 为 0 表示「从未复习过」。
   * 字段对应 ts-fsrs `models.ts:Card`，与安卓 Fsrs.Card 同名同义。
   * elapsedDays 是调度时算出来的，不是持久状态 —— 写盘时仍然写出，仅为排查方便。
   */
  function card(init) {
    const o = init || {}
    return {
      state: o.state != null ? o.state : State.New,
      stability: num(o.stability, 0),          // 稳定度 S：记忆强度，R=90% 时的间隔天数
      difficulty: num(o.difficulty, 0),        // 难度 D ∈ [1,10]，越大越难记
      due: int(o.due, 0),                      // 下次复习时间（毫秒）
      lastReview: int(o.lastReview, 0),        // 上次复习时间（毫秒），0 = 从未
      reps: int(o.reps, 0),
      lapses: int(o.lapses, 0),                // 遗忘次数（Review 态被 Again 打回的次数）
      learningStep: int(o.learningStep, 0),    // 当前处在学习/重学步骤表的第几步
      scheduledDays: int(o.scheduledDays, 0),  // 本次排的间隔天数（学习步骤内为 0）
      elapsedDays: int(o.elapsedDays, 0)       // 上次复习距今多少天（调度时刷新）
    }
  }

  /** 是不是从未复习过。 */
  function isNew(c) { return c.state === State.New }
  /** lastReview ≤ 0 即「从未」。 */
  function neverReviewed(c) { return c.lastReview <= 0 }

  // ---- 小工具：容错取数（对齐安卓 JSONObject.optXxx 的语义：坏类型退回默认值） ----
  function num(v, d) { const x = typeof v === 'string' ? Number(v) : v; return typeof x === 'number' && isFinite(x) ? x : d }
  function int(v, d) { const x = typeof v === 'string' ? Number(v) : v; return typeof x === 'number' && isFinite(x) ? Math.trunc(x) : d }

  function roundTo(v, decimals) {
    const f = Math.pow(10, decimals)
    // Kotlin 的 roundToLong 是「四舍五入到偶数以外的正半数进位」= floor(x+0.5)，
    // 与 JS 的 Math.round 在正数上一致；负数半值上 Math.round 也朝 +∞ 进，跟 Kotlin 同。
    return Math.round(v * f) / f
  }

  // ───────────────────────── 参数 ─────────────────────────

  /**
   * 一套 FSRS 参数。w 在构造时就被迁移 + 裁剪到 21 维合法域，所以后续所有公式
   * 都不会拿到 NaN / 负数底数 —— 「参数外插不崩溃」靠的就是这一步。
   *
   * 派生量（与安卓 Params 同）：
   *  - decay = -w₂₀
   *  - factor = e^(ln0.9 / decay) - 1
   *  - intervalModifier = (r^(1/decay) - 1) / factor
   * r=0.9 时 modifier 恒等于 1，也就是「间隔 = S」。
   */
  class Params {
    constructor(w, requestRetention, maximumInterval, enableShortTerm, learningSteps, relearningSteps) {
      this.w = w
      this.requestRetention = requestRetention
      this.maximumInterval = maximumInterval
      this.enableShortTerm = enableShortTerm
      this.learningSteps = learningSteps
      this.relearningSteps = relearningSteps
      this.decay = -w[20]
      this.factor = roundTo(Math.exp(Math.log(0.9) / this.decay) - 1.0, 8)
      this.intervalModifier = roundTo((Math.pow(requestRetention, 1.0 / this.decay) - 1.0) / this.factor, 8)
    }

    /** 间隔换算：I(r, S) = (r^(1/decay) - 1) / factor · S —— ts-fsrs `next_interval` */
    interval(stability) {
      return Math.min(Math.max(1, Math.round(stability * this.intervalModifier)), this.maximumInterval)
    }

    toString() {
      return 'Params(r=' + this.requestRetention + ', maxIvl=' + this.maximumInterval +
        ', steps=' + this.learningSteps + '/' + this.relearningSteps + ', w.size=' + this.w.length + ')'
    }

    /**
     * 构造参数。任何长度（0 / 17 / 19 / 21 / 更长）都不会抛异常：
     * 不足 21 走 migrateParams 补齐，超长截断，再整体 clipParams 裁剪。
     * @param w 参数向量，允许 NaN / 负数 / 越界（会被裁掉）
     */
    static of(opts) {
      const o = opts || {}
      const w = Array.isArray(o.w) ? o.w : DEFAULT_W
      const rr0 = o.requestRetention != null ? o.requestRetention : DEFAULT_REQUEST_RETENTION
      const mi0 = o.maximumInterval != null ? o.maximumInterval : DEFAULT_MAXIMUM_INTERVAL
      const st = o.enableShortTerm != null ? !!o.enableShortTerm : true
      const safeSteps = sanitizeSteps(o.learningSteps != null ? o.learningSteps : DEFAULT_LEARNING_STEPS)
      const safeRelearning = sanitizeSteps(o.relearningSteps != null ? o.relearningSteps : DEFAULT_RELEARNING_STEPS)
      const safeW = clipParams(migrateParams(w, safeRelearning.length, st), safeRelearning.length, st)
      // requestRetention 裁进 (0,1]，否则 intervalModifier 会算出负数或 NaN
      const rr = !isFinite(rr0) ? DEFAULT_REQUEST_RETENTION : Math.min(1.0, Math.max(1e-6, rr0))
      const mi = mi0 < 1 ? DEFAULT_MAXIMUM_INTERVAL : mi0
      return new Params(safeW, rr, mi, st, safeSteps, safeRelearning)
    }
  }

  /** 默认参数（FSRS-6 官方默认值）。 */
  let _defaultParams = null
  function defaultParams() { return _defaultParams || (_defaultParams = Params.of()) }

  /** 学习步骤只保留正数分钟；全非正就退化成空数组（= 交给 FSRS 自己算间隔）。 */
  function sanitizeSteps(steps) {
    if (!Array.isArray(steps)) return []
    return steps.filter((s) => num(s, 0) > 0).map((s) => Math.trunc(num(s, 0)))
  }

  /**
   * 参数迁移：17 → 19 → 21。ts-fsrs `default.ts:migrateParameters`
   *  - 21 维：原样（去 NaN）
   *  - 19 维（FSRS-5）：补 w19 = 0、w20 = 0.5
   *  - 17 维（FSRS-4.5）：按论文的等价变换换算前三项，再补 4 个 0 + decay
   *  - 其它长度：退回 DEFAULT_W
   */
  function migrateParams(w, relearningStepCount, enableShortTerm) {
    const steps = relearningStepCount == null ? 1 : relearningStepCount
    const shortOn = enableShortTerm == null ? true : !!enableShortTerm
    if (!Array.isArray(w)) return DEFAULT_W.slice()
    const clean = w.map((v) => (isFinite(num(v, NaN)) ? num(v, 0) : 0))
    if (clean.length === 21) return clipParams(clean, steps, shortOn)
    if (clean.length === 19) return clipParams(clean, steps, shortOn).concat([0.0, FSRS5_DEFAULT_DECAY])
    if (clean.length === 17) {
      const c = clipParams(clean, steps, shortOn)
      c[4] = roundTo(c[5] * 2.0 + c[4], 8)
      c[5] = roundTo(Math.log(c[5] * 3.0 + 1.0) / 3.0, 8)
      c[6] = roundTo(c[6] + 0.5, 8)
      return c.concat([0.0, 0.0, 0.0, FSRS5_DEFAULT_DECAY])
    }
    return DEFAULT_W.slice()
  }

  /**
   * 参数裁剪。ts-fsrs `default.ts:clipParameters`
   *
   * 重学步骤 > 1 时额外收紧 w₁₇ / w₁₈ 的天花板：多次重学不能让稳定度不降反增。
   * 推导：`w17·w18 ≤ -[ln(w11) + ln(2^w13 - 1) + w14·0.3] / steps`，开方后作单参数上限。
   * 先裁 w11/w13/w14 再取对数，避免 log 收到 ≤0 产出 NaN。
   */
  function clipParams(params, relearningStepCount, enableShortTerm) {
    const steps = relearningStepCount == null ? 1 : relearningStepCount
    const shortOn = enableShortTerm == null ? true : !!enableShortTerm
    const n = Math.min(params.length, 21)
    const out = new Array(n)
    for (let i = 0; i < n; i++) {
      const lo = (i === 19 && shortOn) ? 0.01 : PARAM_BOUNDS[i][0]   // 关掉短期记忆则 w19 无意义
      const hi = PARAM_BOUNDS[i][1]
      const v = isFinite(num(params[i], NaN)) ? num(params[i], lo) : lo
      out[i] = Math.min(hi, Math.max(lo, v))
    }
    if (steps > 1 && n > 18) {
      const w11 = Math.min(PARAM_BOUNDS[11][1], Math.max(PARAM_BOUNDS[11][0], out[11]))
      const w13 = Math.min(PARAM_BOUNDS[13][1], Math.max(PARAM_BOUNDS[13][0], out[13]))
      const w14 = Math.min(PARAM_BOUNDS[14][1], Math.max(PARAM_BOUNDS[14][0], out[14]))
      const value = -(Math.log(w11) + Math.log(Math.pow(2.0, w13) - 1.0) + w14 * 0.3) / steps
      const ceiling = Math.min(W17_W18_CEILING, Math.max(0.01, roundTo(Math.sqrt(Math.max(value, 0.0)), 8)))
      out[17] = Math.min(ceiling, Math.max(PARAM_BOUNDS[17][0], out[17]))
      out[18] = Math.min(ceiling, Math.max(PARAM_BOUNDS[18][0], out[18]))
    }
    return out
  }

  // ───────────────────── 记忆曲线（algorithm.ts） ─────────────────────

  /**
   * 幂函数遗忘曲线（FSRS-5/6 论文版）：`R(t, S) = (1 + FACTOR · t / S)^DECAY`
   * DECAY = -w₂₀ ∈ [-0.8, -0.1]（负数），FACTOR = e^(ln0.9 / DECAY) - 1（正数，约 0.98）。
   * t = S 时 R = 0.9，所以「稳定度 = 记忆保持 90% 所需的间隔天数」。
   */
  function forgettingCurve(elapsedDays, stability, decay) {
    if (!(stability > 0.0) || !isFinite(stability)) return 0.0
    if (elapsedDays <= 0.0) return 1.0
    const factor = roundTo(Math.exp(Math.log(0.9) / decay) - 1.0, 8)
    return Math.min(1.0, Math.max(0.0, roundTo(Math.pow(1.0 + factor * elapsedDays / stability, decay), 8)))
  }

  /** 传 Params 的重载。 */
  function forgettingCurveP(elapsedDays, stability, p) { return forgettingCurve(elapsedDays, stability, p.decay) }

  /**
   * 指数遗忘曲线：`forget = exp(-elapsed / stability)`，可记忆性 `R = 1 - forget`。
   * 这是 FSRS ≤ 4.5 早期用的写法，当前调度**不用**它（走 forgettingCurve），
   * 保留用于对照说明、迁移按老公式算出来的历史 stability。
   * 注意量纲不同：这里 S 是 e 折时间，幂函数版的 S 是 90% 保留时间，两者不可互换。
   */
  function forgettingCurveExp(elapsedDays, stability) {
    if (!(stability > 0.0) || !isFinite(stability)) return 0.0
    if (elapsedDays <= 0.0) return 1.0
    return roundTo(Math.exp(-elapsedDays / stability), 8)
  }

  /** 可记忆性 R = 1 - forget（指数式）。 */
  function retrievabilityExp(elapsedDays, stability) {
    return roundTo(1.0 - forgettingCurveExp(elapsedDays, stability), 8)
  }

  /** 初始稳定度：`S₀(G) = max(w_{G-1}, 0.1)`。ts-fsrs `init_stability` */
  function initStability(g, p) { return Math.max(p.w[g - 1], 0.1) }

  /**
   * 初始难度：`D₀(G) = w₄ - e^((G-1)·w₅) + 1`。
   * 注意：**这里不钳制**，跟参考实现一致 —— 原始值要参与 meanReversion 的均值回归。
   * 对外暴露的难度都是经 nextDifficulty / nextState 夹进 [1,10] 的结果。
   */
  function initDifficulty(g, p) { return roundTo(p.w[4] - Math.exp((g - 1) * p.w[5]) + 1.0, 8) }

  /** 难度线性阻尼：`δd · (10 - D) / 9`。ts-fsrs `linear_damping` */
  function linearDamping(deltaD, oldD) { return roundTo(deltaD * (10.0 - oldD) / 9.0, 8) }

  /** 难度均值回归：`w₇ · init + (1 - w₇) · current`，把 D 往 `D₀(Easy)` 拉。 */
  function meanReversion(init, current, p) { return roundTo(p.w[7] * init + (1.0 - p.w[7]) * current, 8) }

  /**
   * 难度更新：`δd = -w₆ · (G - 3)` → `next_d = D + linear_damping(δd, D)`
   * → `D' = clamp(mean_reversion(D₀(Easy), next_d), 1, 10)`。ts-fsrs `next_difficulty`
   */
  function nextDifficulty(d, g, p) {
    const deltaD = -p.w[6] * (g - 3)
    const nextD = d + linearDamping(deltaD, d)
    return Math.min(10.0, Math.max(1.0, meanReversion(initDifficulty(Rating.Easy, p), nextD, p)))
  }

  /**
   * 记住后的稳定度：
   * `S'r = S · (1 + e^w₈ · (11 - D) · S^(-w₉) · (e^(w₁₀·(1-R)) - 1) · hard_penalty · easy_bound)`
   * 关键结构：`e^(w₁₀(1-R)) - 1` 在 R→1 时趋 0，所以**准时复习几乎不涨稳定性**；
   * R 越低（拖越久）涨得越多 —— 这是 FSRS 的自适应性来源。
   */
  function nextRecallStability(d, s, r, g, p) {
    const hardPenalty = (g === Rating.Hard) ? p.w[15] : 1.0
    const easyBound = (g === Rating.Easy) ? p.w[16] : 1.0
    const growth = 1.0 +
      Math.exp(p.w[8]) * (11.0 - d) * Math.pow(s, -p.w[9]) * (Math.exp((1.0 - r) * p.w[10]) - 1.0) * hardPenalty * easyBound
    return roundTo(Math.min(S_MAX, Math.max(S_MIN, s * growth)), 8)
  }

  /**
   * 遗忘后的稳定度：`S'f = w₁₁ · D^(-w₁₂) · ((S + 1)^w₁₃ - 1) · e^(w₁₄·(1-R))`
   * 三个因子的直觉：D 越大越难记 → 掉得越多；S 越大 remembered 越多 → 掉得越多；
   * R 越低（已经忘得差不多）→ 剩余可掉的越少。
   */
  function nextForgetStability(d, s, r, p) {
    const f = p.w[11] * Math.pow(d, -p.w[12]) * (Math.pow(s + 1.0, p.w[13]) - 1.0) * Math.exp((1.0 - r) * p.w[14])
    return roundTo(Math.min(S_MAX, Math.max(S_MIN, f)), 8)
  }

  /**
   * 短期记忆稳定度（同一分钟内连按按钮）：`S's = S · e^(w₁₇·(G - 3 + w₁₈))`
   * 完整式子还有 `S^(-w₁₉)` 因子，G ≥ Hard 时额外要求 `sinc ≥ 1`（不许按了「更好」反而变差）。
   */
  function nextShortTermStability(s, g, p) {
    const sinc = Math.pow(s, -p.w[19]) * Math.exp(p.w[17] * (g - 3 + p.w[18]))
    const masked = (g >= Rating.Hard) ? Math.max(sinc, 1.0) : sinc
    return roundTo(Math.min(S_MAX, Math.max(S_MIN, s * masked)), 8)
  }

  /**
   * 记忆状态转移的核心：(D, S) → (D', S')。ts-fsrs `algorithm.ts:next_state`
   * 返回 `{ d, s }`。分支顺序照抄源码，顺序本身有语义：
   * 1. d == 0 && s == 0 → 新卡，走 initDifficulty / initStability
   * 2. t == 0 && enableShortTerm → 短期记忆公式
   * 3. G == Again → 遗忘公式，且新 S 必须 ≤ s / e^(w₁₇·w₁₈)（不许不降反增）
   * 4. 其余 → 记住公式
   */
  function nextState(d, s, t, g, p, r) {
    if (d === 0.0 && s === 0.0) {
      return { d: Math.min(10.0, Math.max(1.0, initDifficulty(g, p))), s: initStability(g, p) }
    }
    const safeT = Math.max(0.0, isFinite(t) ? t : 0.0)
    const safeD = Math.min(10.0, Math.max(1.0, d))
    const safeS = Math.max(S_MIN, s)
    const recall = (r == null) ? forgettingCurveP(safeT, safeS, p) : r
    let newS
    if (safeT === 0.0 && p.enableShortTerm) newS = nextShortTermStability(safeS, g, p)
    else if (g === Rating.Again) {
      const afterFail = nextForgetStability(safeD, safeS, recall, p)
      const w17 = p.enableShortTerm ? p.w[17] : 0.0
      const w18 = p.enableShortTerm ? p.w[18] : 0.0
      const ceiling = roundTo(safeS / Math.exp(w17 * w18), 8)
      newS = Math.max(S_MIN, Math.min(Math.max(ceiling, S_MIN), afterFail))
    } else newS = nextRecallStability(safeD, safeS, recall, g, p)
    return { d: nextDifficulty(safeD, g, p), s: newS }
  }

  // ───────────────────── 学习步骤（learning_steps.ts） ─────────────────────

  /**
   * 学习步骤查表。返回 `{ minutes, nextStep }`；minutes 为 0 表示「这一步不排分钟，
   * 交给 FSRS 按稳定度算天数」。照抄 BasicLearningStepsStrategy：
   *  - steps 用完（curStep >= size）→ 空，卡片直接进 Review
   *  - Review 态只有 Again 有意义（回到第 0 步重学）
   *  - Hard 停在当前步，间隔 = `1.5 × 首步`（单步）或 `(首步 + 次步) / 2`（多步）
   *  - Good 前进一档；已到末档则不给分钟（走天数）
   */
  function stepInfo(p, state, curStep, rating) {
    const steps = (state === State.Relearning || state === State.Review) ? p.relearningSteps : p.learningSteps
    if (steps.length === 0 || curStep >= steps.length) return { minutes: 0, nextStep: 0 }
    const first = steps[0]
    if (state === State.Review) {
      return (rating === Rating.Again) ? { minutes: first, nextStep: 0 } : { minutes: 0, nextStep: 0 }
    }
    switch (rating) {
      case Rating.Again: return { minutes: first, nextStep: 0 }
      case Rating.Hard: {
        const hard = (steps.length === 1) ? Math.round(first * 1.5) : Math.round((first + steps[1]) / 2.0)
        return { minutes: hard, nextStep: curStep }
      }
      case Rating.Good: {
        const next = curStep + 1
        if (next < steps.length && steps[next] > 0) return { minutes: Math.round(steps[next]), nextStep: next }
        return { minutes: 0, nextStep: 0 }
      }
      case Rating.Easy: return { minutes: 0, nextStep: 0 }
    }
    return { minutes: 0, nextStep: 0 }
  }

  // ───────────────────────── 调度 ─────────────────────────

  /**
   * 调度器。一次 review 调用创建一个，负责算 elapsed / reps 并按状态分支。
   * 流程照抄 ts-fsrs `impl/basic_scheduler.ts`：
   * ```
   * init:    elapsed = 天数差(lastReview, now)；lastReview ← now；reps += 1
   * New:      (D,S) = init → 应用学习步骤 → Learning
   * Learning: (D,S) = next_state(t=elapsed) → 应用学习步骤 → 留在 Learning/Relearning
   * Review:   一次算出四档；Hard/Good/Easy 走天数（且强制 hard ≤ good < easy），
   *           Again 走重学步骤 → Relearning 且 lapses += 1
   * ```
   */
  class Scheduler {
    constructor(c, now, p) {
      this.card = c
      this.now = now
      this.p = p || defaultParams()
      // 距上次复习天数；新卡为 0。做了 max(0) 防御（时钟回拨不该让整次复习崩掉）
      this.elapsed = (c.state !== State.New && c.lastReview > 0)
        ? Math.max(0, daysBetween(c.lastReview, now)) : 0
      // 复习动作本身：lastReview 记为 now、reps +1、elapsedDays 刷新
      this.base = Object.assign({}, c, { lastReview: now, reps: c.reps + 1, elapsedDays: this.elapsed })
    }

    review(rating) {
      switch (this.card.state) {
        case State.New: return this.newState(rating)
        case State.Learning:
        case State.Relearning: return this.learningState(rating)
        case State.Review: return this.reviewState(rating)
      }
      return this.newState(rating)
    }

    newState(rating) { return this.applyLearningSteps(this.nextDs(this.elapsed, rating), rating, State.Learning) }
    learningState(rating) { return this.applyLearningSteps(this.nextDs(this.elapsed, rating), rating, this.card.state) }

    reviewState(rating) {
      const t = this.elapsed
      const r = forgettingCurveP(t, this.card.stability, this.p)
      const again = Object.assign(
        this.applyLearningSteps(this.nextDs(t, Rating.Again, r), Rating.Again, State.Relearning),
        { lapses: this.card.lapses + 1 })

      const hard = this.nextDs(t, Rating.Hard, r)
      const good = this.nextDs(t, Rating.Good, r)
      const easy = this.nextDs(t, Rating.Easy, r)
      const ivlHard = Math.min(this.p.interval(hard.stability), this.p.interval(good.stability))
      const ivlGood = Math.max(this.p.interval(good.stability), ivlHard + 1)
      const ivlEasy = Math.max(this.p.interval(easy.stability), ivlGood + 1)
      switch (rating) {
        case Rating.Again: return again
        case Rating.Hard: return this.toReview(hard, ivlHard)
        case Rating.Good: return this.toReview(good, ivlGood)
        case Rating.Easy: return this.toReview(easy, ivlEasy)
      }
      return again
    }

    toReview(c, ivl) {
      return Object.assign({}, c, {
        state: State.Review, learningStep: 0, scheduledDays: ivl, due: this.now + ivl * DAY_MS
      })
    }

    /** 按 (D,S) 转移出新卡的记忆状态，其余字段继承 base。 */
    nextDs(t, g, r) {
      const ns = nextState(this.card.difficulty, this.card.stability, t, g, this.p, r)
      return Object.assign({}, this.base, { difficulty: ns.d, stability: ns.s })
    }

    /**
     * 套学习步骤表。分钟数 < 1440 走 Learning/Relearning；≥ 1440 或 0 走 Review。
     * ts-fsrs `impl/basic_scheduler.ts:applyLearningSteps`
     */
    applyLearningSteps(next, grade, toState) {
      const si = stepInfo(this.p, this.card.state, this.card.learningStep, grade)
      if (si.minutes > 0 && si.minutes < 1440) {
        return Object.assign({}, next, {
          learningStep: si.nextStep, scheduledDays: 0, state: toState, due: this.now + si.minutes * MINUTE_MS
        })
      }
      if (si.minutes >= 1440) {
        return Object.assign({}, next, {
          learningStep: si.nextStep, scheduledDays: Math.floor(si.minutes / 1440.0), state: State.Review,
          due: this.now + si.minutes * MINUTE_MS
        })
      }
      const ivl = this.p.interval(next.stability)
      return Object.assign({}, next, {
        learningStep: 0, state: State.Review, scheduledDays: ivl, due: this.now + ivl * DAY_MS
      })
    }
  }

  /**
   * 复习一张卡，返回排好程的新卡。
   * @param now 当前时间（毫秒）
   */
  function review(c, rating, now, p) { return new Scheduler(c, now, p || defaultParams()).review(rating) }

  /** 一次算出四档结果（对应 ts-fsrs 的 `fsrs.repeat()`），键为评分名。 */
  function preview(c, now, p) {
    const out = {}
    for (let i = 0; i < RATING_ALL.length; i++) {
      const r = RATING_ALL[i]
      out[RATING_NAMES[i]] = review(c, r, now, p)
    }
    return out
  }

  /** 卡片的当前可记忆性 R ∈ [0,1]。新卡（stability=0）按 0 处理。 */
  function retrievability(c, now, p) {
    const pp = p || defaultParams()
    if (c.state === State.New || c.stability <= 0.0) return 0.0
    const elapsed = Math.max(0.0, daysBetween(c.lastReview, now))
    return forgettingCurveP(elapsed, c.stability, pp)
  }

  /** UTC 日历天差（不足一天算 0），与 ts-fsrs `help.ts:dateDiffInDays` 一致。 */
  function daysBetween(fromMs, toMs) {
    const a = utcDayStart(fromMs)
    const b = utcDayStart(toMs)
    return Math.floor((b - a) / DAY_MS)
  }

  function utcDayStart(ms) {
    const d = Math.trunc(ms / 1000)
    const day = (d >= 0) ? Math.trunc(d / 86400) : Math.trunc((d - 86399) / 86400)   // 向下取整，负时间戳也对
    return day * 86400 * 1000
  }

  // ───────────────────────── 到期判定 ─────────────────────────

  /**
   * 是否到期。新卡恒为 true；其余看 `due <= now`。
   * @param dayStartMs 当天 0 点（毫秒）。给了就按「日历日」判：`due < dayStartMs` 才算今日到期，
   *                    跨天误差（上午背的卡 due 落在下午）不会被误判成逾期。
   */
  function isDue(c, now, dayStartMs) {
    if (c.state === State.New) return true
    const limit = (dayStartMs == null) ? now : dayStartMs
    return c.due <= limit
  }

  /** 已到期的卡（按到期时间升序，新卡排最前）。纯函数，不改入参。 */
  function dueCards(cards, now, dayStartMs) {
    return (cards || []).filter((c) => isDue(c, now, dayStartMs))
      .sort((a, b) => {
        const na = (a.state !== State.New) ? 1 : 0, nb = (b.state !== State.New) ? 1 : 0
        return na !== nb ? na - nb : a.due - b.due
      })
  }

  /** 到期数（新卡算在内）。 */
  function dueCount(cards, now, dayStartMs) {
    return (cards || []).filter((c) => isDue(c, now, dayStartMs)).length
  }

  // ───────────────────────── 序列化 ─────────────────────────

  /**
   * 序列化成 JSON 对象（字段名与 ts-fsrs `Card` 对齐，与安卓 Fsrs.toJson 完全一致）。
   * 时间戳存毫秒整数；lastReview 为 0 时写 null。
   */
  function toJson(c) {
    return {
      state: c.state,
      stability: c.stability,
      difficulty: c.difficulty,
      due: c.due,
      last_review: (c.lastReview > 0) ? c.lastReview : null,
      reps: c.reps,
      lapses: c.lapses,
      learning_step: c.learningStep,
      scheduled_days: c.scheduledDays,
      elapsed_days: c.elapsedDays
    }
  }

  /** 列表转 JSON 数组（直接可 JSON.stringify）。 */
  function toJsonList(cards) { return (cards || []).map(toJson) }

  /**
   * 从 JSON 解析。缺字段 / 坏类型都退回默认值，不抛异常；
   * 只有 JSON 本身语法坏了才返回 null，由调用方决定兜底。
   */
  function cardFromJson(o) {
    if (o == null) return null
    if (typeof o === 'string') {
      const s = o.trim()
      if (!s) return null
      try { o = JSON.parse(s) } catch { return null }
    }
    if (typeof o !== 'object' || Array.isArray(o)) return null
    const lr = (o.last_review == null) ? 0 : int(o.last_review, 0)
    return card({
      state: stateOf(o.state),
      stability: num(o.stability, 0.0),
      difficulty: num(o.difficulty, 0.0),
      due: int(o.due, 0),
      lastReview: lr,
      reps: int(o.reps, 0),
      lapses: int(o.lapses, 0),
      learningStep: int(o.learning_step, 0),
      scheduledDays: int(o.scheduled_days, 0),
      elapsedDays: int(o.elapsed_days, 0)
    })
  }

  /** 列表解析，跳过坏元素。 */
  function cardsFromJson(arr) {
    if (!Array.isArray(arr)) return []
    const out = []
    for (const o of arr) { const c = cardFromJson(o); if (c) out.push(c) }
    return out
  }

  /** 参数向量的 JSON（21 维数组），便于把优化后的 w 存下来。 */
  function paramsToJson(p) { return ((p || defaultParams()).w).slice() }

  /** 读回参数；坏输入退回默认参数，不抛。 */
  function paramsFromJson(arr) {
    if (!Array.isArray(arr) || !arr.length) return defaultParams()
    try { return Params.of({ w: arr.map((v) => num(v, 0)) }) } catch { return defaultParams() }
  }

  return {
    // 常量
    S_MIN, S_MAX, INIT_S_MAX, DEFAULT_REQUEST_RETENTION, DEFAULT_MAXIMUM_INTERVAL, FSRS5_DEFAULT_DECAY,
    W17_W18_CEILING, DAY_MS, MINUTE_MS, DEFAULT_LEARNING_STEPS, DEFAULT_RELEARNING_STEPS,
    DEFAULT_W, PARAM_BOUNDS,
    // 枚举
    State, Rating, STATE_NAMES, RATING_NAMES, RATING_ALL, stateOf, ratingOf, stateName, ratingName,
    // 卡
    card, isNew, neverReviewed,
    // 参数
    Params, defaultParams, sanitizeSteps, migrateParams, clipParams,
    // 曲线
    forgettingCurve, forgettingCurveP, forgettingCurveExp, retrievabilityExp,
    // 状态转移
    initStability, initDifficulty, linearDamping, meanReversion, nextDifficulty,
    nextRecallStability, nextForgetStability, nextShortTermStability, nextState, stepInfo,
    // 调度
    Scheduler, review, preview, retrievability, daysBetween,
    // 到期
    isDue, dueCards, dueCount,
    // 序列化
    toJson, toJsonList, cardFromJson, cardsFromJson, paramsToJson, paramsFromJson
  }
})