package com.chun.lingzhu

/**
 * 答案面板放在哪、多大（v1.60）—— 纯计算，不碰 Android 类，单元测试直接测。
 *
 * 位置（Prefs.panelPos）：
 *  - left 左边（v1.66 起默认，从左侧滑出）/ bottom 底部（同 v1.52 的抽屉）/ top 顶部 / right 右边
 *  - orb   跟着小球：在小球旁边展开（小球在右就贴右、在左就贴左；小球在上半屏往下长，在下半屏往上长）
 *  - away  避开小球：小球在上半屏 → 面板放底部；在下半屏 → 放顶部
 *  - free  自由：按住面板的横条拖到哪就停在哪（位置按屏幕比例记，转屏也不跑出去）
 * 面板从贴着的那一边滑进来；横条放在「能长高的那一边」（贴顶的面板横条在下面，往下拖变高）。
 */
object PanelLayout {
    const val BOTTOM = "bottom"
    const val TOP = "top"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val ORB = "orb"
    const val AWAY = "away"
    const val FREE = "free"
    val POSITIONS = listOf(BOTTOM, TOP, LEFT, RIGHT, ORB, AWAY, FREE)
    /** 面板托盘里「位置」按钮轮流切的（自由位置靠拖，不放进轮换） */
    val CYCLE = listOf(BOTTOM, TOP, LEFT, RIGHT, ORB, AWAY)
    val LABEL = mapOf(
        BOTTOM to "底部", TOP to "顶部", LEFT to "左边", RIGHT to "右边",
        ORB to "跟着小球", AWAY to "避开小球", FREE to "自由位置",
    )
    fun norm(p: String?): String = if (p != null && p in POSITIONS) p else BOTTOM
    /** v1.66：没选过位置时，面板默认从屏幕左侧滑出（Prefs.panelPos 的默认值；设置页「恢复默认」也回到这里） */
    const val DEFAULT = LEFT

    /** 竖直方向贴哪：面板窗口 gravity 的上下部分 */
    enum class V { TOP, BOTTOM, CENTER }
    /** 水平方向贴哪 */
    enum class H { START, END, CENTER }

    const val ALIGN_CENTER = "center"
    const val ALIGN_START = "start"
    const val ALIGN_END = "end"
    val ALIGNS = listOf(ALIGN_CENTER, ALIGN_START, ALIGN_END)
    val ALIGN_LABEL = mapOf(ALIGN_CENTER to "居中", ALIGN_START to "靠左", ALIGN_END to "靠右")
    fun normAlign(a: String?): String = if (a != null && a in ALIGNS) a else ALIGN_CENTER

    /**
     * 屏幕和系统栏（都是 px）。bottomRef = 面板贴底（gravity BOTTOM）时 y 从哪条线往上量：
     * 悬浮窗的父框到导航条上沿为止（实测 Android 14：parent=[0,0][1080,2337]，屏幕 2400、导航条 63）。
     */
    data class Screen(val w: Int, val h: Int, val density: Float, val statusBar: Int, val navBar: Int, val bottomRef: Int = h - navBar)
    /** 小球在屏幕上的真实位置（px，左上角 + 边长；小球窗口从状态栏下面算，传进来前要换成屏幕坐标） */
    data class Orb(val x: Int, val y: Int, val size: Int)

    data class Options(
        val pos: String = BOTTOM,
        /** 顶部 / 底部面板宽度，占可用宽度的 % */
        val widthPct: Int = 100,
        /** 左 / 右 / 跟着小球 的面板宽度 % */
        val sideWidthPct: Int = 62,
        /** 自由位置的面板宽度 % */
        val freeWidthPct: Int = 80,
        /** 顶部 / 底部面板不满宽时靠哪边 */
        val align: String = ALIGN_CENTER,
        /** 离贴着的那条屏幕边再远多少 dp（底部 = 往上抬，顶部 = 往下放，左右 = 往里收） */
        val offsetDp: Int = 0,
        /** 自由位置：面板左上角在屏幕上的比例位置（0~1000 ‰） */
        val freeX: Int = 100,
        val freeY: Int = 300,
    )

    data class Spec(
        val v: V, val h: H,
        /** 按 gravity 的偏移：x 离贴着的左 / 右边多远；y 离贴着的上 / 下边多远（CENTER 时是离中线的偏移） */
        val x: Int, val y: Int,
        val width: Int,
        /** 整个面板最多多高（再高就只缩正文） */
        val maxHeight: Int,
        /** 横条放在面板底部（贴顶的面板往下长） */
        val gripBottom: Boolean,
        /** 进场从哪边滑进来：-1 / 0 / 1（x 方向 +1 = 从右边进，y 方向 +1 = 从下面进） */
        val enterX: Int, val enterY: Int,
    )

    private fun dp(s: Screen, v: Float) = (v * s.density + 0.5f).toInt()

    /** 顶部 / 底部面板满宽时的宽度：手机左右各留 6dp；平板（≥600dp 宽）最多 480dp，和以前一样 */
    fun fullWidth(s: Screen): Int {
        val screenDp = s.w / s.density
        return if (screenDp < 600) s.w - dp(s, 12f) else Math.min(s.w - dp(s, 24f), dp(s, 480f))
    }

    /** 左右 / 自由面板按「屏幕宽 − 两边各 6dp」算百分比；最窄 220dp，不然一行放不下几个字 */
    private fun pctWidth(s: Screen, pct: Int): Int {
        val avail = s.w - dp(s, 12f)
        return (avail * pct.coerceIn(30, 100) / 100f).toInt().coerceIn(Math.min(dp(s, 220f), avail), avail)
    }

    /** 小球在上半屏？（用小球中心算） */
    private fun orbUpper(s: Screen, orb: Orb?): Boolean = orb == null || orb.y + orb.size / 2 < s.h / 2

    private fun orbLeft(s: Screen, orb: Orb?): Boolean = orb != null && orb.x + orb.size / 2 < s.w / 2

    /** 实际生效的位置：避开小球 → 顶部 / 底部 */
    fun resolve(pos: String, s: Screen, orb: Orb?): String = when (norm(pos)) {
        AWAY -> if (orbUpper(s, orb)) BOTTOM else TOP
        else -> norm(pos)
    }

    fun compute(o: Options, s: Screen, orb: Orb?): Spec {
        val gap = dp(s, 6f)
        val off = dp(s, o.offsetDp.coerceIn(0, 400).toFloat())
        val topLimit = s.statusBar + gap          // 面板上沿最高到这
        val bottomLimit = s.h - s.navBar - gap    // 面板下沿最低到这
        val usable = (bottomLimit - topLimit).coerceAtLeast(dp(s, 120f))
        return when (resolve(o.pos, s, orb)) {
            TOP -> {
                val w = if (o.widthPct >= 100) fullWidth(s) else Math.min(fullWidth(s), pctWidth(s, o.widthPct))
                val y = (topLimit + off).coerceAtMost(bottomLimit - dp(s, 120f))
                Spec(V.TOP, hFor(o.align), if (o.align == ALIGN_CENTER) 0 else gap, y, w, bottomLimit - y, gripBottom = true, enterX = 0, enterY = -1)
            }
            LEFT, RIGHT -> {
                val left = norm(o.pos) == LEFT
                val w = pctWidth(s, o.sideWidthPct)
                val x = (gap + off).coerceAtMost(Math.max(gap, s.w - w - gap))
                Spec(V.CENTER, if (left) H.START else H.END, x, 0, w, usable, gripBottom = false, enterX = if (left) -1 else 1, enterY = 0)
            }
            ORB -> {
                val left = orbLeft(s, orb)
                val size = orb?.size ?: dp(s, 58f)
                // 小球边上留出小球的位置，面板紧挨着它展开，不把小球盖住
                val beside = gap + size + dp(s, 4f)
                val w = Math.min(pctWidth(s, o.sideWidthPct), s.w - beside - gap)
                val x = beside.coerceAtMost(Math.max(gap, s.w - w - gap))
                if (orbUpper(s, orb)) {
                    val y = (orb?.y ?: topLimit).coerceIn(topLimit, bottomLimit - dp(s, 160f))
                    Spec(V.TOP, if (left) H.START else H.END, x, y, w, bottomLimit - y, gripBottom = true, enterX = if (left) -1 else 1, enterY = 0)
                } else {
                    val orbBottom = (orb?.let { it.y + it.size } ?: bottomLimit)
                    val y = (s.bottomRef - orbBottom).coerceIn(Math.max(0, s.bottomRef - bottomLimit), Math.max(0, s.bottomRef - topLimit - dp(s, 160f)))
                    Spec(V.BOTTOM, if (left) H.START else H.END, x, y, w, s.bottomRef - y - topLimit, gripBottom = false, enterX = if (left) -1 else 1, enterY = 0)
                }
            }
            FREE -> {
                val w = pctWidth(s, o.freeWidthPct)
                val x = (s.w * o.freeX.coerceIn(0, 1000) / 1000f).toInt().coerceIn(0, Math.max(0, s.w - w))
                val y = (s.h * o.freeY.coerceIn(0, 1000) / 1000f).toInt().coerceIn(topLimit, bottomLimit - dp(s, 120f))
                Spec(V.TOP, H.START, x, y, w, bottomLimit - y, gripBottom = false, enterX = 0, enterY = 1)
            }
            else -> {   // BOTTOM
                val w = if (o.widthPct >= 100) fullWidth(s) else Math.min(fullWidth(s), pctWidth(s, o.widthPct))
                val y = (s.navBar + gap + off).coerceAtMost(s.h - topLimit - dp(s, 120f))
                Spec(V.BOTTOM, hFor(o.align), if (o.align == ALIGN_CENTER) 0 else gap, y, w, s.h - y - s.statusBar - dp(s, 8f), gripBottom = false, enterX = 0, enterY = 1)
            }
        }
    }

    private fun hFor(align: String) = when (normAlign(align)) { ALIGN_START -> H.START; ALIGN_END -> H.END; else -> H.CENTER }

    /** 拖完松手：面板左上角 → 自由位置的 ‰ */
    fun toFree(s: Screen, left: Int, top: Int): Pair<Int, Int> =
        (left * 1000f / s.w).toInt().coerceIn(0, 1000) to (top * 1000f / s.h).toInt().coerceIn(0, 1000)

    /** 半屏 / 大屏两档正文高度占可用高度的比例（%），大屏至少比半屏高 10 个点 */
    fun heights(halfPct: Int, fullPct: Int): Pair<Int, Int> {
        val h = halfPct.coerceIn(HALF_MIN, HALF_MAX)
        val f = fullPct.coerceIn(FULL_MIN, FULL_MAX).coerceAtLeast(h + 10)
        return h to f
    }
    const val HALF_MIN = 20
    const val HALF_MAX = 70
    const val FULL_MIN = 40
    const val FULL_MAX = 95

    // ---------------- 档位循环（v1.85）----------------
    // 用户能摸到的伸缩只剩两档：半屏 ↔ 大屏。收起档（AnswerPanel.COLLAPSED = 0）仍然存在，
    // 但只留给内部用（被动模式 / 点链接临时收起），用户的点按和拖动永远落不到它上面。
    // 这两个常量必须和 AnswerPanel.HALF / FULL 对齐 —— 循环做成纯函数，单测直接测。
    const val STATE_HALF = 1
    const val STATE_FULL = 2

    /** 点一下横条：半屏 ↔ 大屏两档来回切。若传进来的是收起档（内部用过之后留下的残值），先回到半屏。 */
    fun toggle(cur: Int): Int = when (cur) {
        STATE_FULL -> STATE_HALF
        STATE_HALF -> STATE_FULL
        else -> STATE_HALF
    }

    /** 拖动换档：往「更高 / 更矮」各挪一档，结果一律夹在 [STATE_HALF, STATE_FULL] 之间（不再掉回收起档）。 */
    fun nextState(cur: Int, delta: Int): Int = (cur + delta).coerceIn(STATE_HALF, STATE_FULL)
}
