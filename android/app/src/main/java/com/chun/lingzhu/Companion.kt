package com.chun.lingzhu

import kotlin.random.Random

/**
 * 陪伴气泡（v2.3.4 手机版）。
 *
 * ## 它是什么 / 不是什么
 * 学习过程中**不定时**轻轻冒一句话 —— 「还在这一页」「刚才那段挺长的」这种。
 * 它**不是** AI：全是一个固定的小句池，本地随机取，不联网、不读屏幕内容、不写历史。
 * 也不是番茄钟：没有会话、没有倒计时、不承诺「专注 25 分钟」这类目标，只是偶尔打个招呼。
 *
 * ## 三条纪律（和 [TryPop] / [FocusNotify] 一脉相承）
 *  - **不抢焦点**：气泡窗口是 FLAG_NOT_FOCUSABLE，点一下只关自己；
 *  - **会自己收**：[STAY_MS] 到点无条件消失，不需要用户处理；
 *  - **不叠**：已有气泡 / 待办试弹卡 / 用户面板在屏上时，这一次直接跳过（不顶掉别人的东西）。
 *
 * ## 洗牌袋：不在刚说完的那句上再说一遍
 * 纯随机会出现「同一句连着两次」，看着像坏了。所以整池洗成一副牌，一张张抽；
 * 抽空了重洗，并且**新牌堆的第一张不能是上一堆最后那张**（跨袋也不连着重复）。
 * 这一半是纯逻辑，JVM 单测直接跑（[FocusCompanionTest]）。
 *
 * ## 频率
 * 用户选的是「大概多久冒一次」，所以每次抽取在区间内均匀取值，不是固定间隔 ——
 * 固定间隔会让人开始数秒，那就不叫陪伴了。
 */
object FocusCompanion {

    /** 一条句子的最小信息单位：正文。抬头固定（「小珠」），不占池子 */
    const val HEAD = "小珠"

    /** 气泡停留时长：8 秒 —— 抬眼能读完，又不至于一直杵在屏幕上 */
    const val STAY_MS = 8_000L

    /** 气泡宽度（dp）：和试弹卡一个量级（[TryPop.CARD_W_DP]），不改用户的面板宽度 */
    const val WIDTH_DP = 248f

    private const val MIN = 60_000L

    /**
     * 频率档 → 两次气泡之间的时长区间。
     *
     * 三档都是**区间**而不是点：用户选「中」时，45–120 分钟都可能冒一次。
     * 认不出的频率按 [FocusSettings.FREQ_MED] 处理（[FocusSettings.normFreq] 同一口径）。
     */
    data class Range(val minMs: Long, val maxMs: Long) {
        init {
            require(minMs in 1..maxMs) { "区间不合法：$minMs..$maxMs" }
        }

        /** 区间内均匀取一个间隔。区间退化成一个点时直接给那个点 */
        fun pick(rnd: Random): Long =
            if (maxMs <= minMs) minMs else rnd.nextLong(minMs, maxMs + 1)
    }

    /** 低：60–180 分钟 */
    val LOW = Range(60 * MIN, 180 * MIN)

    /** 中（默认）：45–120 分钟 */
    val MED = Range(45 * MIN, 120 * MIN)

    /** 高：15–60 分钟 */
    val HIGH = Range(15 * MIN, 60 * MIN)

    fun range(freq: String): Range = when (FocusSettings.normFreq(freq)) {
        FocusSettings.FREQ_LOW -> LOW
        FocusSettings.FREQ_HIGH -> HIGH
        else -> MED
    }

    /** 下一次气泡隔多久（毫秒）。纯函数，[rnd] 由调用方给，测试可注入固定种子 */
    fun nextDelay(freq: String, rnd: Random): Long = range(freq).pick(rnd)

    /**
     * 句池。全部是**陈述 / 邀请**，没有一句是「你又在玩」「不该」「必须」——
     * 这是给学习者用的陪伴，不是监督。句子都很短：气泡最宽 248dp，一行放得下。
     */
    val LINES: List<String> = listOf(
        "还在这一页，慢慢来",
        "看到这儿了，歇一下眼睛也行",
        "这一段挺长的，接着看",
        "需要的话，双击小球问一句",
        "记不住的地方可以先标一下",
        "刚才那几页都翻过去了",
        "喝口水再继续",
        "累了就停，明天接着来",
        "这一节看完就可以收工了",
        "有卡住的地方吗",
        "慢慢看，不赶时间",
        "换个姿势坐坐",
    )

    /**
     * 洗牌袋。**有状态**（当前这副牌 + 上一张），调用方要自己持有实例。
     *
     * @param pool 句子池；空池时 [next] 返回 null（调用方不弹气泡，绝不编一句出来）
     */
    class Bag(private val pool: List<String> = LINES, private val rnd: Random = Random.Default) {
        private val left = ArrayList<String>()
        /** 上一张抽出去的；null = 还没抽过 */
        var last: String? = null
            private set

        /** 抽一张。抽空前会重洗；新牌堆的第一张保证不等于 [last] */
        fun next(): String? {
            if (pool.isEmpty()) return null
            if (left.isEmpty()) refill()
            val pick = left.removeAt(left.size - 1)
            last = pick
            return pick
        }

        private fun refill() {
            left.addAll(pool)
            left.shuffle(rnd)
            // 跨袋也不连着重复：万一重洗后第一张（末尾那张）正是上一张，就把它换到中间去
            val prev = last
            if (pool.size > 1 && prev != null && left.last() == prev) {
                val i = rnd.nextInt(left.size - 1)
                val tmp = left[i]
                left[i] = left.last()
                left[left.size - 1] = tmp
            }
        }
    }
}
