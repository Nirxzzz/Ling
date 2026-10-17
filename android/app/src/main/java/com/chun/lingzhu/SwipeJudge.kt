package com.chun.lingzhu

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 词卡「滑出判词」的纯逻辑（v1.85）。
 *
 * 用户要的手感（原话）：「增加滑出的阻尼与反馈就是加一个临界……它会震一下；过了某个临界后，
 * 它会震一下。震一震之后，如果再出现某个界限，然后松手，它就会滑掉，它的键程加长」。
 * 方向语义（原话）：「默认左滑出是不认识，下滑是模糊，右滑是认识」；上滑 = 跳过、不评分。
 *
 * 于是这里定三样东西，全部纯函数、可直测：
 *  1. **方向 → 评分**（[gradeOf]）；上滑是 [Grade.CLOSE]（不评）。
 *  2. **阻尼**（[appliedX]/[appliedY]）：第一段（到 [Thresholds.t1]）跟手 1:1，
 *     之后按 [DAMP_AFTER] 打折 —— 拉过临界以后「推不动」，但还能继续走。
 *  3. **两段临界**（[stage]）：过 t1 = 进入判词区（震一下 + 亮提示）；过 t2 = 「松手即生效」（再震一下）。
 *     松手时按**投影终点**（当前位移 + 速度 × [PROJECT_S]）判，够到 t2 才真的滑掉并提交评分，
 *     只过 t1 则弹回、不评 —— 这样「甩一下」也算数，但慢慢拖需要更长的键程。
 *
 * 为什么纯逻辑放在这里：`WordCard` 只管把手指位移喂进来、把结果画出来；阈值/阻尼/判定这三件事
 * 一旦混进 View 就没法回归测试了（见 [SwipeJudgeTest]）。
 */
object SwipeJudge {

    /** 这一划代表的记忆评分。上滑 = 跳过，不评分。 */
    enum class Grade(val label: String) {
        UNKNOWN("不认识"),   // 左滑
        FUZZY("模糊"),       // 下滑
        KNOWN("认识"),       // 右滑
        CLOSE("跳过"),       // 上滑：跳过、不评，只把这张卡收掉
    }

    /** 两段临界（像素）：t1 = 进判词区，t2 = 松手即生效。t2 一定大于 t1。 */
    data class Thresholds(val t1: Float, val t2: Float)

    /** 第一段临界占「跨度」的比例（横用卡宽、竖用卡高）。 */
    const val T1_FRAC = 0.15f
    /** 第二段临界占跨度的比例 —— 比第一版（0.3）大，就是用户要的「键程加长」。 */
    const val T2_FRAC = 0.34f
    /** 过 t1 之后的阻尼系数：位移只按这个比例走（越小越「拉不动」）。 */
    const val DAMP_AFTER = 0.62f
    /** 投影终点 = 当前位移 + 速度 × 这个秒数（用来判「甩」）。和 `Anim.dragDismissAny` 同一口径。 */
    const val PROJECT_S = 0.12f

    /**
     * 两段临界。`span` = 卡片的宽（横向）或高（纵向），`floor` = 最小像素（一般传 44dp）。
     * t2 至少是 t1 的 2.2 倍，保证两段之间有明确的中间态。
     */
    fun thresholds(span: Int, floor: Float): Thresholds {
        val s = span.coerceAtLeast(1).toFloat()
        val t1 = Math.max(floor, s * T1_FRAC)
        val t2 = Math.max(t1 * 2.2f, Math.max(floor * 2.2f, s * T2_FRAC))
        return Thresholds(t1, t2)
    }

    /**
     * 阻尼后的位移：到 t1 之前 1:1 跟手；过了 t1 只按 [DAMP_AFTER] 走（还有一点余量能到 t2）。
     * 传进来的 [d] 是手指位移（已带符号），返回的是**卡片应该走的位移**。
     */
    fun damp(d: Float, t1: Float): Float {
        val a = abs(d)
        if (a <= t1) return d
        val extra = (a - t1) * DAMP_AFTER
        return (t1 + extra) * (if (d < 0) -1f else 1f)
    }

    /** 当前「阶段」：0 = 还没到 t1；1 = 过了 t1（进判词区）；2 = 过了 t2（松手即生效）。 */
    fun stage(dx: Float, dy: Float, th: Thresholds): Int {
        val r = reach(dx, dy)
        return if (r >= th.t2) 2 else if (r >= th.t1) 1 else 0
    }

    /** 方向：[Grade.CLOSE] = 上滑（不评）。主方向按 |dx| vs |dy| 分，正好各占 90°。 */
    fun gradeOf(dx: Float, dy: Float): Grade {
        if (abs(dx) >= abs(dy)) return if (dx < 0) Grade.UNKNOWN else Grade.KNOWN
        return if (dy > 0) Grade.FUZZY else Grade.CLOSE
    }

    /**
     * 松手时判定：够到 t2（按投影终点）就返回该提交的 [Grade]；否则返回 null = 弹回、不评。
     * 速度只放大**主方向**的分量（横着甩不会把纵向判定带过去）。
     */
    fun judge(dx: Float, dy: Float, vx: Float, vy: Float, th: Thresholds): Grade? {
        val g = gradeOf(dx, dy)
        // 主方向是哪一条，就只看那一条的投影 —— 横着甩不会把纵向判定带过去。
        // [project] 将速度投到当前位移方向，左/上与右/下同样加成，回拉不加成。
        val p = if (abs(dx) >= abs(dy)) project(dx, vx) else project(dy, vy)
        return if (p >= th.t2) g else null
    }

    /** 判词区要显示哪一行提示（阶段 0 不显示）。 */
    fun hint(stage: Int, grade: Grade): String =
        when {
            stage <= 0 -> ""
            stage == 1 -> grade.label
            else -> grade.label + " · 松手生效"
        }

    // ---------------------------------------------------------------- 内部

    /** 到原点的距离（判「过没过临界」用；斜着划也算，方向和距离分开看）。 */
    private fun reach(dx: Float, dy: Float): Float = hypot(dx.toDouble(), dy.toDouble()).toFloat()

    /**
     * 投影终点（像素，非负）：慢拖按当前距离，快甩再加上速度那一段。
     * 反向速度（往回滑）不加成 —— 否则「拖出去又拖回来」会被判成生效。
     */
    private fun project(d: Float, v: Float): Float {
        val outward = if (d < 0f) -v else if (d > 0f) v else 0f
        return abs(d) + Math.max(0f, outward) * PROJECT_S
    }
}
