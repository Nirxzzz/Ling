package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * FSRS（Free Spaced Repetition Scheduler）间隔重复算法 · 纯 Kotlin / 纯 JVM 实现。
 *
 * ## 出处
 * 逐行对照 [ts-fsrs](https://github.com/open-spaced-repetition/ts-fsrs) `main` 分支
 * `packages/fsrs/src`，MIT 协议。KDoc 里每处公式都标了对应源文件：
 *  - 参数向量 / 常量 → `constant.ts`（`default_w`、`S_MIN`、`CLAMP_PARAMETERS`）
 *  - 记忆曲线 / S-D 更新公式 → `algorithm.ts`（`forgetting_curve`、`next_state`、`next_recall_stability`…）
 *  - 参数迁移与裁剪 → `default.ts`（`migrateParameters`、`clipParameters`）
 *  - 学习步骤表 → `strategies/learning_steps.ts`（`BasicLearningStepsStrategy`）
 *  - 调度流程 → `impl/basic_scheduler.ts` + `abstract_scheduler.ts`
 *
 * ## 与 ts-fsrs 的差异（有意为之，共三处）
 * 1. **日期用 [Long] 毫秒**，不用 `Date`（本工程要求纯 JVM、无 Android 依赖）。
 * 2. **参数固定 21 维**（FSRS-6 形态）。ts-fsrs 的 Proxy 动态改参数、fuzz 随机化、
 *    策略插件（StrategyMode）全部没移植 —— 学习步骤是构造期定下的分钟数组，不支持运行时替换。
 * 3. `elapsedDays` 做了 `max(0, …)` 防御。ts-fsrs 传负数会抛 `FSRSValidationError`；
 *    这里时钟回拨（改系统时间、同步 NTP）不该让整次复习崩掉，负值按 0 天算。
 *
 * 另有一点**公式层面的补充**：任务书要求的 `forget = exp(-elapsed/stability)`
 * 是 FSRS ≤ 4.5 早期的指数遗忘曲线，FSRS-5 起论文改用幂函数
 * `R(t,S) = (1 + FACTOR · t/S)^DECAY`（见 [forgettingCurve]）。
 * 两个都实现了，默认调度走幂函数，[forgettingCurveExp] 保留指数式供对照/迁移老数据用。
 *
 * 范围：只做算法核心（状态机 / 参数 / 调度 / 到期判定 / 序列化）。
 * 不含 Anki 的 fuzz、埋点、撤销（rollback）、重算（reschedule）。
 */
object Fsrs {

    // ───────────────────────── 常量（constant.ts） ─────────────────────────

    /** 稳定度下限。[ts-fsrs `constant.ts:S_MIN`] */
    const val S_MIN = 0.001

    /** 稳定度上限（100 年）。[ts-fsrs `constant.ts:S_MAX`] */
    const val S_MAX = 36500.0

    /** 首刷初始稳定度上限。[ts-fsrs `constant.ts:INIT_S_MAX`] */
    const val INIT_S_MAX = 100.0

    /** 默认请求留存率 0.9。[ts-fsrs `constant.ts:default_request_retention`] */
    const val DEFAULT_REQUEST_RETENTION = 0.9

    /** 默认最大间隔 100 年。[ts-fsrs `constant.ts:default_maximum_interval`] */
    const val DEFAULT_MAXIMUM_INTERVAL = 36500

    /** 19 参数（FSRS-5）补齐时使用的 decay。[ts-fsrs `constant.ts:FSRS5_DEFAULT_DECAY`] */
    const val FSRS5_DEFAULT_DECAY = 0.5

    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MINUTE_MS = 60L * 1000

    /** 默认学习步骤 [1m, 10m]（分钟）。[ts-fsrs `constant.ts:default_learning_steps`] */
    val DEFAULT_LEARNING_STEPS = intArrayOf(1, 10)

    /** 默认重学步骤 [10m]（分钟）。[ts-fsrs `constant.ts:default_relearning_steps`] */
    val DEFAULT_RELEARNING_STEPS = intArrayOf(10)

    /**
     * FSRS-6 默认参数向量（21 维，w₂₀ = decay = 0.1542）。
     * 逐值抄自 [ts-fsrs `constant.ts:default_w`]，本工程不做任何调参。
     */
    val DEFAULT_W = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,   // w0..w6  初始 S / 初始 D
        0.001, 1.8722, 0.1666, 0.796, 1.4835,                   // w7..w11 难度均值回归 / 记忆 S
        0.0614, 0.2629, 1.6483,                                 // w12..w14 遗忘 S
        0.6014, 1.8729,                                         // w15..w16 Hard / Easy 乘子
        0.5425, 0.0912, 0.0658,                                 // w17..w19 短期记忆 S
        0.1542,                                                 // w20     decay
    )

    /**
     * 参数裁剪上下界。[ts-fsrs `constant.ts:CLAMP_PARAMETERS`] + `W17_W18_Ceiling`。
     * 第 20 项（decay）落在 [0.1, 0.8] —— 这保证了 [Params.decay] 非零，[Params] 的除法永远安全。
     */
    private val PARAM_BOUNDS: Array<ClosedFloatingPointRange<Double>> = arrayOf(
        S_MIN..INIT_S_MAX,   // w0  初始稳定度 Again
        S_MIN..INIT_S_MAX,   // w1  初始稳定度 Hard
        S_MIN..INIT_S_MAX,   // w2  初始稳定度 Good
        S_MIN..INIT_S_MAX,   // w3  初始稳定度 Easy
        1.0..10.0,           // w4  初始难度
        0.001..4.0,          // w5  初始难度乘子
        0.001..4.0,          // w6  难度乘子
        0.001..0.75,         // w7  难度均值回归系数
        0.0..4.5,            // w8  S 指数
        0.0..0.8,            // w9  S 负幂
        0.001..3.5,          // w10 S 指数
        0.001..5.0,          // w11 遗忘 S 乘子
        0.001..0.25,         // w12 遗忘 S 负幂
        0.001..0.9,          // w13 遗忘 S 幂
        0.0..4.0,            // w14 遗忘 S 指数
        0.0..1.0,            // w15 Hard 乘子
        1.0..6.0,            // w16 Easy 乘子
        0.0..W17_W18_CEILING,// w17 短期 S 指数
        0.0..W17_W18_CEILING,// w18 短期 S 指数
        0.0..0.8,            // w19 短期末次 S 指数
        0.1..0.8,            // w20 decay
    )

    /** 遗忘曲线整体是 (1 + ·)^decay，decay 落在 [0.1, 0.8]，结果恒在 (0, 1]。 */
    const val W17_W18_CEILING = 2.0

    // ───────────────────────── 枚举 ─────────────────────────

    /** 卡片状态。编码与 ts-fsrs `models.ts:State` 一致，可直接和 Anki 库互转。 */
    enum class State(val code: Int) {
        New(0), Learning(1), Review(2), Relearning(3);

        companion object {
            /** 容错解析：`0..3` 数字或 `"Review"` 这类名字；认不出返回 [New]。 */
            fun of(raw: Any?): State = when (raw) {
                is Int -> entries.getOrElse(raw) { New }
                is Number -> entries.getOrElse(raw.toInt()) { New }
                is String -> {
                    val n = raw.trim()
                    val byName = entries.firstOrNull { it.name.equals(n, true) }
                    byName ?: n.toIntOrNull()?.let { entries.getOrElse(it) { New } } ?: New
                }
                else -> New
            }
        }
    }

    /** 评分。编码与 ts-fsrs `models.ts:Rating` 一致（0=Manual 不用，故从 1 起）。 */
    enum class Rating(val code: Int) {
        Again(1), Hard(2), Good(3), Easy(4);

        companion object {
            val ALL: List<Rating> = listOf(Again, Hard, Good, Easy)

            /** 容错解析：1..4 数字或 `"Good"` 这类名字；认不出返回 null。 */
            fun of(raw: Any?): Rating? = when (raw) {
                is Int -> entries.getOrElse(raw - 1) { null }
                is Number -> entries.getOrElse(raw.toInt() - 1) { null }
                is String -> {
                    val n = raw.trim()
                    entries.firstOrNull { it.name.equals(n, true) } ?: n.toIntOrNull()?.let { entries.getOrElse(it - 1) { null } }
                }
                else -> null
            }
        }
    }

    // ───────────────────────── 卡片 ─────────────────────────

    /**
     * 一张记忆卡。时间戳一律毫秒；[lastReview] 为 0 表示「从未复习过」。
     *
     * 字段对应 ts-fsrs `models.ts:Card`，去掉了纯日志性质的 `elapsed_days` 之外都保留
     * （本类里的 `elapsedDays` 是调度时算出来的，不是持久状态，序列化时写出仅为排查方便）。
     */
    data class Card(
        val state: State = State.New,
        /** 稳定度 S：记忆强度，R=90% 时的间隔天数。 */
        val stability: Double = 0.0,
        /** 难度 D ∈ [1,10]，越大越难记。 */
        val difficulty: Double = 0.0,
        /** 下次复习时间（毫秒）。 */
        val due: Long = 0L,
        /** 上次复习时间（毫秒），0 = 从未。 */
        val lastReview: Long = 0L,
        val reps: Int = 0,
        /** 遗忘次数（Review 态被 Again 打回的次数）。 */
        val lapses: Int = 0,
        /** 当前处在学习/重学步骤表的第几步。 */
        val learningStep: Int = 0,
        /** 本次排的间隔天数（学习步骤内为 0）。 */
        val scheduledDays: Int = 0,
        /** 上次复习距今多少天（调度时刷新）。 */
        val elapsedDays: Int = 0,
    ) {
        val isNew: Boolean get() = state == State.New
        val neverReviewed: Boolean get() = lastReview <= 0L
    }

    // ───────────────────────── 参数 ─────────────────────────

    /**
     * 一套 FSRS 参数。[w] 在构造时就被迁移 + 裁剪到 21 维合法域，所以后续所有公式
     * 都不会拿到 NaN / 负数底数 —— 「参数外插不崩溃」靠的就是这一步。
     *
     * 派生量：
     *  - `decay = -w₂₀`
     *  - `factor = e^(ln0.9 / decay) - 1`（[ts-fsrs `algorithm.ts:computeDecayFactor`]）
     *  - `intervalModifier = (r^(1/decay) - 1) / factor`（`algorithm.ts:calculate_interval_modifier`）
     *
     * r=0.9 时 modifier 恒等于 1，也就是「间隔 = S」。
     */
    class Params private constructor(
        val w: DoubleArray,
        /** 请求留存率，(0, 1]。 */
        val requestRetention: Double,
        val maximumInterval: Int,
        /** 短期记忆建模：同一天内连续复习走 `next_short_term_stability`。[`algorithm.ts:next_state`] */
        val enableShortTerm: Boolean,
        /** 学习步骤（分钟），New/Learning 用。 */
        val learningSteps: IntArray,
        /** 重学步骤（分钟），Relearning/Review 被打回时用。 */
        val relearningSteps: IntArray,
    ) {
        val decay: Double = -w[20]
        val factor: Double = roundTo(exp(ln(0.9) / decay) - 1.0, 8)
        val intervalModifier: Double = roundTo((requestRetention.pow(1.0 / decay) - 1.0) / factor, 8)

        /** 间隔换算：I(r, S) = (r^(1/decay) - 1) / factor · S —— [algorithm.ts:next_interval] */
        fun interval(stability: Double): Int =
            min(max(1, round(stability * intervalModifier).toInt()), maximumInterval)

        override fun toString(): String =
            "Params(r=$requestRetention, maxIvl=$maximumInterval, steps=$learningSteps/$relearningSteps, w.size=${w.size})"

        companion object {
            /** 默认参数（FSRS-6 官方默认值）。 */
            val DEFAULT: Params by lazy { of() }

            /**
             * 构造参数。任何长度（0 / 17 / 19 / 21 / 更长）都不会抛异常：
             * 不足 21 走 [migrateParams] 补齐，超长截断，再整体 [clipParams] 裁剪。
             *
             * @param w 参数向量，允许 NaN / 负数 / 越界（会被裁掉）
             */
            fun of(
                w: DoubleArray = DEFAULT_W,
                requestRetention: Double = DEFAULT_REQUEST_RETENTION,
                maximumInterval: Int = DEFAULT_MAXIMUM_INTERVAL,
                enableShortTerm: Boolean = true,
                learningSteps: IntArray = DEFAULT_LEARNING_STEPS,
                relearningSteps: IntArray = DEFAULT_RELEARNING_STEPS,
            ): Params {
                val safeSteps = sanitizeSteps(learningSteps)
                val safeRelearning = sanitizeSteps(relearningSteps)
                val safeW = clipParams(migrateParams(w, safeRelearning.size, enableShortTerm), safeRelearning.size, enableShortTerm)
                // requestRetention 裁进 (0,1]，否则 intervalModifier 会算出负数或 NaN
                val rr = if (!requestRetention.isFinite()) DEFAULT_REQUEST_RETENTION
                else requestRetention.coerceIn(1e-6, 1.0)
                val mi = if (maximumInterval < 1) DEFAULT_MAXIMUM_INTERVAL else maximumInterval
                return Params(safeW, rr, mi, enableShortTerm, safeSteps, safeRelearning)
            }
        }
    }

    /** 学习步骤只保留正数分钟；全非正就退化成空数组（= 交给 FSRS 自己算间隔）。 */
    private fun sanitizeSteps(steps: IntArray): IntArray {
        val keep = steps.filter { it > 0 }
        return IntArray(keep.size) { keep[it] }
    }

    /**
     * 参数迁移：17 → 19 → 21。[ts-fsrs `default.ts:migrateParameters`]
     *
     *  - 21 维：原样（去 NaN）
     *  - 19 维（FSRS-5）：补 `w19 = 0`、`w20 = 0.5`
     *  - 17 维（FSRS-4.5）：按论文的等价变换换算前三项，再补 4 个 0 + decay
     *  - 其它长度：退回 [DEFAULT_W]
     */
    fun migrateParams(w: DoubleArray?, relearningStepCount: Int = 1, enableShortTerm: Boolean = true): DoubleArray {
        if (w == null) return DEFAULT_W.copyOf()
        val clean = DoubleArray(w.size) { if (w[it].isFinite()) w[it] else 0.0 }
        return when (clean.size) {
            21 -> clipParams(clean, relearningStepCount, enableShortTerm)
            19 -> clipParams(clean, relearningStepCount, enableShortTerm) + doubleArrayOf(0.0, FSRS5_DEFAULT_DECAY)
            17 -> {
                val c = clipParams(clean, relearningStepCount, enableShortTerm)
                c[4] = roundTo(c[5] * 2.0 + c[4], 8)
                c[5] = roundTo(ln(c[5] * 3.0 + 1.0) / 3.0, 8)
                c[6] = roundTo(c[6] + 0.5, 8)
                c + doubleArrayOf(0.0, 0.0, 0.0, FSRS5_DEFAULT_DECAY)
            }
            else -> DEFAULT_W.copyOf()
        }
    }

    /**
     * 参数裁剪。[ts-fsrs `default.ts:clipParameters`]
     *
     * 重学步骤 > 1 时额外收紧 w₁₇ / w₁₈ 的天花板：多次重学不能让稳定度不降反增。
     * 推导见源码注释：`w17·w18 ≤ -[ln(w11) + ln(2^w13 - 1) + w14·0.3] / steps`，
     * 开方后作为单参数上限（保证两者乘积满足约束）。先裁 w11/w13/w14 再取对数，
     * 避免 log 收到 ≤0 产出 NaN。
     */
    fun clipParams(params: DoubleArray, relearningStepCount: Int = 1, enableShortTerm: Boolean = true): DoubleArray {
        val n = min(params.size, 21)
        val out = DoubleArray(n)
        val bounds = PARAM_BOUNDS
        for (i in 0 until n) {
            val lo = if (i == 19 && enableShortTerm) 0.01 else bounds[i].start   // 关掉短期记忆则 w19 无意义
            val hi = bounds[i].endInclusive
            val v = if (params[i].isFinite()) params[i] else lo
            out[i] = v.coerceIn(lo, hi)
        }
        if (relearningStepCount > 1 && n > 18) {
            val w11 = out[11].coerceIn(bounds[11].start, bounds[11].endInclusive)
            val w13 = out[13].coerceIn(bounds[13].start, bounds[13].endInclusive)
            val w14 = out[14].coerceIn(bounds[14].start, bounds[14].endInclusive)
            val value = -(ln(w11) + ln(2.0.pow(w13) - 1.0) + w14 * 0.3) / relearningStepCount
            val ceiling = roundTo(sqrt(max(value, 0.0)), 8).coerceIn(0.01, W17_W18_CEILING)
            out[17] = out[17].coerceIn(bounds[17].start, ceiling)
            out[18] = out[18].coerceIn(bounds[18].start, ceiling)
        }
        return out
    }

    // ───────────────────── 记忆曲线（algorithm.ts） ─────────────────────

    /**
     * 幂函数遗忘曲线（FSRS-5/6 论文版）：`R(t, S) = (1 + FACTOR · t / S)^DECAY`
     *
     * 其中 `DECAY = -w₂₀ ∈ [-0.8, -0.1]`（负数），`FACTOR = e^(ln0.9 / DECAY) - 1`（正数，约 0.98）。
     * t = S 时 R = 0.9，所以「稳定度 = 记忆保持 90% 所需的间隔天数」。
     * 出处：[ts-fsrs `algorithm.ts:forgetting_curve` + `computeDecayFactor`]。
     *
     * @param elapsedDays 距上次复习的天数
     * @param stability 稳定度 S
     * @param decay [Params.decay]，即 -w₂₀（负数）
     */
    fun forgettingCurve(elapsedDays: Double, stability: Double, decay: Double): Double {
        if (stability <= 0.0 || !stability.isFinite()) return 0.0
        if (elapsedDays <= 0.0) return 1.0
        val factor = roundTo(exp(ln(0.9) / decay) - 1.0, 8)
        return roundTo((1.0 + factor * elapsedDays / stability).pow(decay), 8).coerceIn(0.0, 1.0)
    }

    fun forgettingCurve(elapsedDays: Double, stability: Double, p: Params): Double =
        forgettingCurve(elapsedDays, stability, p.decay)

    /**
     * 指数遗忘曲线：`forget = exp(-elapsed / stability)`，可记忆性 `R = 1 - forget`。
     *
     * 这是 FSRS ≤ 4.5 早期（以及很多简化实现 / 教材）用的写法，decay 固定为 1，
     * 无法拟合「遗忘速度随难度变化」。当前调度**不用**它（走 [forgettingCurve]），
     * 保留用于：对照说明、迁移按老公式算出来的历史 stability。
     *
     * 注意它跟幂函数版的量纲约定不同：这里 S 是 e 折时间（exp(-1) ≈ 36.8%），
     * 幂函数版的 S 是 90% 保留时间，两者不可直接互换。
     */
    fun forgettingCurveExp(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0 || !stability.isFinite()) return 0.0
        if (elapsedDays <= 0.0) return 1.0
        return roundTo(exp(-elapsedDays / stability), 8)
    }

    /** 可记忆性 R = 1 - forget（指数式）。 */
    fun retrievabilityExp(elapsedDays: Double, stability: Double): Double =
        roundTo(1.0 - forgettingCurveExp(elapsedDays, stability), 8)

    /** 初始稳定度：`S₀(G) = max(w_{G-1}, 0.1)`。[ts-fsrs `algorithm.ts:init_stability`] */
    fun initStability(g: Rating, p: Params): Double = max(p.w[g.code - 1], 0.1)

    /**
     * 初始难度：`D₀(G) = w₄ - e^((G-1)·w₅) + 1`。
     * G=3(Good) 时是 `w₄ - e^(2w₅) + 1`；G=4(Easy) 在默认参数下算出负数（≈ -2.89）。
     * 出处：[ts-fsrs `algorithm.ts:init_difficulty`]。
     *
     * 注意：**这里不钳制**，跟参考实现一致 —— 原始值要参与 [meanReversion] 的均值回归。
     * 对外暴露的难度都是经 [nextDifficulty] / [nextState] 夹进 [1,10] 的结果。
     */
    fun initDifficulty(g: Rating, p: Params): Double =
        roundTo(p.w[4] - exp((g.code - 1) * p.w[5]) + 1.0, 8)

    /**
     * 难度线性阻尼：`δd · (10 - D) / 9`。
     * 出处：[ts-fsrs `algorithm.ts:linear_damping`]（修 fsrs4anki#697：D 接近 10 时更新要收敛）。
     */
    fun linearDamping(deltaD: Double, oldD: Double): Double =
        roundTo(deltaD * (10.0 - oldD) / 9.0, 8)

    /**
     * 难度均值回归：`w₇ · init + (1 - w₇) · current`，把 D 往 `D₀(Easy)` 拉。
     * 出处：[ts-fsrs `algorithm.ts:mean_reversion`]。
     */
    fun meanReversion(init: Double, current: Double, p: Params): Double =
        roundTo(p.w[7] * init + (1.0 - p.w[7]) * current, 8)

    /**
     * 难度更新：
     * ```
     * δd = -w₆ · (G - 3)
     * next_d = D + linear_damping(δd, D)
     * D' = clamp(mean_reversion(D₀(Easy), next_d), 1, 10)
     * ```
     * 出处：[ts-fsrs `algorithm.ts:next_difficulty`]。
     *
     * 单调性：G 越大 → D 越小（越容易），再被均值回归往 `D₀(Easy)` 收，最后夹进 [1,10]。
     */
    fun nextDifficulty(d: Double, g: Rating, p: Params): Double {
        val deltaD = -p.w[6] * (g.code - 3)
        val nextD = d + linearDamping(deltaD, d)
        return meanReversion(initDifficulty(Rating.Easy, p), nextD, p).coerceIn(1.0, 10.0)
    }

    /**
     * 记住后的稳定度：
     * ```
     * S'r = S · (1 + e^w₈ · (11 - D) · S^(-w₉) · (e^(w₁₀·(1-R)) - 1) · hard_penalty · easy_bound)
     * hard_penalty = w₁₅ (仅 G=Hard)，easy_bound = w₁₆ (仅 G=Easy)
     * ```
     * 出处：[ts-fsrs `algorithm.ts:next_recall_stability`]。
     *
     * 关键结构：`e^(w₁₀(1-R)) - 1` 在 R→1 时趋 0，所以**准时复习几乎不涨稳定性**；
     * R 越低（拖越久）涨得越多 —— 这是 FSRS 的自适应性来源。
     */
    fun nextRecallStability(d: Double, s: Double, r: Double, g: Rating, p: Params): Double {
        val hardPenalty = if (g == Rating.Hard) p.w[15] else 1.0
        val easyBound = if (g == Rating.Easy) p.w[16] else 1.0
        val growth = 1.0 +
            exp(p.w[8]) * (11.0 - d) * s.pow(-p.w[9]) * (exp((1.0 - r) * p.w[10]) - 1.0) * hardPenalty * easyBound
        return roundTo((s * growth).coerceIn(S_MIN, S_MAX), 8)
    }

    /**
     * 遗忘后的稳定度：
     * ```
     * S'f = w₁₁ · D^(-w₁₂) · ((S + 1)^w₁₃ - 1) · e^(w₁₄·(1-R))
     * ```
     * 出处：[ts-fsrs `algorithm.ts:next_forget_stability`]。
     *
     * 三个因子的直觉：D 越大越难记 → 掉得越多（S'f 小）；S 越大 remembered 越多 → 掉得越多；
     * R 越低（已经忘得差不多）→ 剩余可掉的越少。
     */
    fun nextForgetStability(d: Double, s: Double, r: Double, p: Params): Double {
        val f = p.w[11] * d.pow(-p.w[12]) * ((s + 1.0).pow(p.w[13]) - 1.0) * exp((1.0 - r) * p.w[14])
        return roundTo(f.coerceIn(S_MIN, S_MAX), 8)
    }

    /**
     * 短期记忆稳定度（同一分钟内连按按钮）：`S's = S · e^(w₁₇·(G - 3 + w₁₈))`
     *
     * 完整式子还有 `S^(-w₁₉)` 因子（ts-fsrs `next_short_term_stability`），
     * G ≥ Hard 时额外要求 `sinc ≥ 1`（不许按了「更好」反而变差）。
     * 出处：[ts-fsrs `algorithm.ts:next_short_term_stability`]。
     */
    fun nextShortTermStability(s: Double, g: Rating, p: Params): Double {
        val sinc = s.pow(-p.w[19]) * exp(p.w[17] * (g.code - 3 + p.w[18]))
        val masked = if (g >= Rating.Hard) max(sinc, 1.0) else sinc
        return roundTo((s * masked).coerceIn(S_MIN, S_MAX), 8)
    }

    /**
     * 记忆状态转移的核心：(D, S) → (D', S')。
     * 出处：[ts-fsrs `algorithm.ts:next_state`]。
     *
     * 分支顺序（照抄源码，顺序本身有语义）：
     * 1. `d == 0 && s == 0` → 新卡，走 `init_difficulty` / `init_stability`
     * 2. `t == 0 && enableShortTerm` → 短期记忆公式
     * 3. `G == Again` → 遗忘公式，且新 S 必须 `≤ s / e^(w₁₇·w₁₈)`（不许不降反增）
     * 4. 其余 → 记住公式
     *
     * @param t 距上次复习天数
     * @param r 可记忆性，null 则用 [forgettingCurve] 现算
     */
    fun nextState(d: Double, s: Double, t: Double, g: Rating, p: Params, r: Double? = null): Pair<Double, Double> {
        if (d == 0.0 && s == 0.0) return initDifficulty(g, p).coerceIn(1.0, 10.0) to initStability(g, p)
        val safeT = max(0.0, if (t.isFinite()) t else 0.0)
        val safeD = d.coerceIn(1.0, 10.0)
        val safeS = s.coerceAtLeast(S_MIN)
        val recall = r ?: forgettingCurve(safeT, safeS, p)
        val newS = when {
            safeT == 0.0 && p.enableShortTerm -> nextShortTermStability(safeS, g, p)
            g == Rating.Again -> {
                val afterFail = nextForgetStability(safeD, safeS, recall, p)
                val w17 = if (p.enableShortTerm) p.w[17] else 0.0
                val w18 = if (p.enableShortTerm) p.w[18] else 0.0
                val ceiling = roundTo(safeS / exp(w17 * w18), 8)
                min(max(ceiling, S_MIN), afterFail).coerceAtLeast(S_MIN)
            }
            else -> nextRecallStability(safeD, safeS, recall, g, p)
        }
        return nextDifficulty(safeD, g, p) to newS
    }

    // ───────────────────── 学习步骤（strategies/learning_steps.ts） ─────────────────────

    /**
     * 学习步骤查表。返回 `(排程分钟数, 下一步序号)`；分钟数为 0 表示「这一步不排分钟，
     * 交给 FSRS 按稳定度算天数」。
     *
     * 照抄 [ts-fsrs `strategies/learning_steps.ts:BasicLearningStepsStrategy`]：
     *  - steps 用完（`curStep >= size`）→ 空，卡片直接进 Review
     *  - Review 态只有 Again 有意义（回到第 0 步重学）
     *  - Hard 停在当前步，间隔 = `1.5 × 首步`（单步）或 `(首步 + 次步) / 2`（多步）
     *  - Good 前进一档；已到末档则不给分钟（走天数）
     */
    fun stepInfo(p: Params, state: State, curStep: Int, rating: Rating): Pair<Int, Int> {
        val steps = if (state == State.Relearning || state == State.Review) p.relearningSteps else p.learningSteps
        if (steps.isEmpty() || curStep >= steps.size) return 0 to 0
        val first = steps[0]
        if (state == State.Review) return if (rating == Rating.Again) first to 0 else 0 to 0
        return when (rating) {
            Rating.Again -> first to 0
            Rating.Hard -> {
                val hard = if (steps.size == 1) round(first * 1.5).toInt() else round((first + steps[1]) / 2.0).toInt()
                hard to curStep
            }
            Rating.Good -> {
                val next = curStep + 1
                if (next < steps.size && steps[next] > 0) round(steps[next].toDouble()).toInt() to next else 0 to 0
            }
            Rating.Easy -> 0 to 0
        }
    }

    // ───────────────────────── 调度 ─────────────────────────

    /**
     * 复习一张卡，返回排好程的新卡。
     *
     * 流程照抄 [ts-fsrs `impl/basic_scheduler.ts`]：
     * ```
     * init:    elapsed = 天数差(lastReview, now)；lastReview ← now；reps += 1
     * New:      (D,S) = init → 应用学习步骤 → Learning
     * Learning: (D,S) = next_state(t=elapsed) → 应用学习步骤 → 留在 Learning/Relearning
     * Review:   一次算出四档；Hard/Good/Easy 走天数（且强制 hard ≤ good < easy），
     *           Again 走重学步骤 → Relearning 且 lapses += 1
     * ```
     *
     * @param now 当前时间（毫秒）
     */
    fun review(card: Card, rating: Rating, now: Long, p: Params = Params.DEFAULT): Card =
        Scheduler(card, now, p).review(rating)

    /** 一次算出四档结果（对应 ts-fsrs 的 `fsrs.repeat()`），键为 [Rating]。 */
    fun preview(card: Card, now: Long, p: Params = Params.DEFAULT): Map<Rating, Card> =
        Rating.ALL.associateWith { review(card, it, now, p) }

    /** 卡片的当前可记忆性 R ∈ [0,1]。新卡（stability=0）按 0 处理。 */
    fun retrievability(card: Card, now: Long, p: Params = Params.DEFAULT): Double {
        if (card.state == State.New || card.stability <= 0.0) return 0.0
        val elapsed = max(0.0, daysBetween(card.lastReview, now).toDouble())
        return forgettingCurve(elapsed, card.stability, p)
    }

    /** UTC 日历天差（不足一天算 0），与 ts-fsrs `help.ts:dateDiffInDays` 一致。 */
    fun daysBetween(fromMs: Long, toMs: Long): Int {
        val a = utcDayStart(fromMs)
        val b = utcDayStart(toMs)
        return floor((b - a).toDouble() / DAY_MS.toDouble()).toInt()
    }

    private fun utcDayStart(ms: Long): Long {
        val d = ms / 1000
        val day = if (d >= 0) d / 86400 else (d - 86399) / 86400   // 向下取整，负时间戳也对
        return day * 86400L * 1000L
    }

    /** 调度器。一次 [review] 调用创建一个，负责算 elapsed / reps 并按状态分支。 */
    private class Scheduler(val card: Card, val now: Long, val p: Params) {
        /** 距上次复习天数；新卡为 0。做了 max(0) 防御（见文件头差异说明第 3 条）。 */
        val elapsed: Int = if (card.state != State.New && card.lastReview > 0L)
            max(0, daysBetween(card.lastReview, now)) else 0

        /** 复习动作本身：lastReview 记为 now、reps +1、elapsedDays 刷新。 */
        val base: Card = card.copy(lastReview = now, reps = card.reps + 1, elapsedDays = elapsed)

        fun review(rating: Rating): Card = when (card.state) {
            State.New -> newState(rating)
            State.Learning, State.Relearning -> learningState(rating)
            State.Review -> reviewState(rating)
        }

        private fun newState(rating: Rating): Card {
            val next = nextDs(elapsed.toDouble(), rating)
            return applyLearningSteps(next, rating, State.Learning)
        }

        private fun learningState(rating: Rating): Card {
            val next = nextDs(elapsed.toDouble(), rating)
            return applyLearningSteps(next, rating, card.state)
        }

        private fun reviewState(rating: Rating): Card {
            val t = elapsed.toDouble()
            val r = forgettingCurve(t, card.stability, p)
            val again = applyLearningSteps(nextDs(t, Rating.Again, r), Rating.Again, State.Relearning)
                .copy(lapses = card.lapses + 1)

            val hard = nextDs(t, Rating.Hard, r)
            val good = nextDs(t, Rating.Good, r)
            val easy = nextDs(t, Rating.Easy, r)
            val ivlHard = min(p.interval(hard.stability), p.interval(good.stability))
            val ivlGood = min(p.maximumInterval, max(p.interval(good.stability), ivlHard + 1))
            val ivlEasy = min(p.maximumInterval, max(p.interval(easy.stability), ivlGood + 1))
            return when (rating) {
                Rating.Again -> again
                Rating.Hard -> hard.copy(state = State.Review, learningStep = 0, scheduledDays = ivlHard, due = now + ivlHard * DAY_MS)
                Rating.Good -> good.copy(state = State.Review, learningStep = 0, scheduledDays = ivlGood, due = now + ivlGood * DAY_MS)
                Rating.Easy -> easy.copy(state = State.Review, learningStep = 0, scheduledDays = ivlEasy, due = now + ivlEasy * DAY_MS)
            }
        }

        /** 按 (D,S) 转移出新卡的记忆状态，其余字段继承 [base]。 */
        private fun nextDs(t: Double, g: Rating, r: Double? = null): Card {
            val (d, s) = nextState(card.difficulty, card.stability, t, g, p, r)
            return base.copy(difficulty = d, stability = s)
        }

        /**
         * 套学习步骤表。分钟数 < 1440 走 Learning/Relearning；≥ 1440 或 0 走 Review。
         * 出处：[ts-fsrs `impl/basic_scheduler.ts:applyLearningSteps`]。
         */
        private fun applyLearningSteps(next: Card, grade: Rating, toState: State): Card {
            val (minutes, nextStep) = stepInfo(p, card.state, card.learningStep, grade)
            return if (minutes > 0 && minutes < 1440) {
                next.copy(
                    learningStep = nextStep,
                    scheduledDays = 0,
                    state = toState,
                    due = now + minutes * MINUTE_MS,
                )
            } else if (minutes >= 1440) {
                next.copy(
                    learningStep = nextStep,
                    scheduledDays = floor(minutes / 1440.0).toInt(),
                    state = State.Review,
                    due = now + minutes * MINUTE_MS,
                )
            } else {
                val ivl = p.interval(next.stability)
                next.copy(learningStep = 0, state = State.Review, scheduledDays = ivl, due = now + ivl * DAY_MS)
            }
        }
    }

    // ───────────────────────── 到期判定 ─────────────────────────

    /**
     * 是否到期。新卡（[State.New]）恒为 true；其余看 `due <= now`。
     *
     * @param now 当前时间（毫秒）
     * @param dayStartMs 当天 0 点（毫秒）。给了就按「日历日」判：`due < dayStartMs` 才算今日到期，
     *                    跨天误差（上午背的卡 due 落在下午）不会被误判成逾期。
     */
    fun isDue(card: Card, now: Long, dayStartMs: Long? = null): Boolean {
        if (card.state == State.New) return true
        val limit = dayStartMs ?: now
        return card.due <= limit
    }

    /** 已到期的卡（按到期时间升序，新卡排最前）。纯函数，不改入参。 */
    fun dueCards(cards: List<Card>, now: Long, dayStartMs: Long? = null): List<Card> =
        cards.filter { isDue(it, now, dayStartMs) }
            .sortedWith(compareBy({ it.state != State.New }, { it.due }))

    /** 今日到期数（新卡算在内）。 */
    fun dueCount(cards: List<Card>, now: Long, dayStartMs: Long? = null): Int =
        cards.count { isDue(it, now, dayStartMs) }

    // ───────────────────────── 序列化 ─────────────────────────

    /**
     * 序列化成 JSON（字段名与 ts-fsrs `Card` 对齐）。
     * 时间戳存毫秒整数；[Card.lastReview] 为 0 时写 `null`。
     */
    fun toJson(c: Card): String = JSONObject().apply {
        put("state", c.state.code)
        put("stability", c.stability)
        put("difficulty", c.difficulty)
        put("due", c.due)
        put("last_review", if (c.lastReview > 0L) c.lastReview else JSONObject.NULL)
        put("reps", c.reps)
        put("lapses", c.lapses)
        put("learning_step", c.learningStep)
        put("scheduled_days", c.scheduledDays)
        put("elapsed_days", c.elapsedDays)
    }.toString()

    /** 列表转 JSON 数组。 */
    fun toJson(cards: List<Card>): String =
        JSONArray().apply { cards.forEach { put(JSONObject(toJson(it))) } }.toString()

    /**
     * 从 JSON 解析。缺字段 / 坏类型都退回默认值（`optXxx` 语义），不抛异常；
     * 只有 JSON 本身语法坏了才返回 null，由调用方决定兜底。
     */
    fun cardFromJson(s: String?): Card? {
        if (s.isNullOrBlank()) return null
        return try {
            val o = JSONObject(s)
            val lr = if (o.isNull("last_review")) 0L else o.optLong("last_review", 0L)
            Card(
                state = State.of(o.opt("state")),
                stability = o.optDouble("stability", 0.0),
                difficulty = o.optDouble("difficulty", 0.0),
                due = o.optLong("due", 0L),
                lastReview = lr,
                reps = o.optInt("reps", 0),
                lapses = o.optInt("lapses", 0),
                learningStep = o.optInt("learning_step", 0),
                scheduledDays = o.optInt("scheduled_days", 0),
                elapsedDays = o.optInt("elapsed_days", 0),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 列表解析，跳过坏元素。 */
    fun cardsFromJson(s: String?): List<Card> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.toString()?.let { cardFromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 参数向量的 JSON（21 维数组），便于把优化后的 w 存下来。 */
    fun paramsToJson(p: Params = Params.DEFAULT): String =
        JSONArray().apply { p.w.forEach { put(it) } }.toString()

    /** 读回参数；坏输入退回默认参数，不抛。 */
    fun paramsFromJson(s: String?): Params {
        if (s.isNullOrBlank()) return Params.DEFAULT
        return try {
            val a = JSONArray(s)
            Params.of(w = DoubleArray(a.length()) { a.optDouble(it, 0.0) })
        } catch (_: Exception) {
            Params.DEFAULT
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun roundTo(v: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return (v * f).roundToLong() / f
    }
}