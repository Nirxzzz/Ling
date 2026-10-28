package com.chun.lingzhu

/**
 * v2.3.2 专注守护（第 9 条）：判断「这一屏是在学习还是在玩」，以及「离开学习多久了、要不要温和提醒一句」。
 *
 * ## 为什么不做 OCR
 * 这个工程里没有本地 OCR（没有 Ocr.kt、没有 mlkit / tesseract）。要把屏上的字读出来只有两条路：
 * 调模型（= 把屏幕内容上传，和「隐私」这条底线正面冲突），或者什么都不判。
 * 所以判定改成**图像结构**：题目页 / 课本页的版面是有形状的 —— 十几条横平竖直的墨色条带、
 * 左边界对得很齐、底下大部分是均匀的纸色；而短视频 / 游戏 / 聊天界面没有这种骨架。
 * 这些量从灰度图就能算出来，全在本机、全是纯函数。
 *
 * ## 三态，不是布尔
 * 判不出来要敢说判不出来。
 *  - [Verdict.STUDY] 版面和「一页有正文的纸」吻合，计入学习；
 *  - [Verdict.NOT_STUDY] 版面和「纸」明显不符（大面积色块 / 只有很少几条文字 / 边缘到处都是），计入离开；
 *  - [Verdict.UNSURE] 介于两者之间，或者图根本没解出来 —— **既不算学、也不算离开**。
 * 宁可少提醒，也不冤枉人：一次识屏失败不该被记成「你在玩手机」。
 *
 * ## 口径与隐私
 * 喂进来的是**像素**（灰度数组），不是文字；本文件不写盘、不发网络请求，
 * 连中间特征都不落盘。[FocusReport] 那一侧只存「时刻 + 这一屏算了多久 + 三态标签 + 画面指纹」，
 * 没有装正文的字段，也没有装像素的字段。
 *
 * ## 可替换的抽象
 * 判定分两段：[ScreenSense.sense] 负责「图 -> 版面特征」，[FocusGuard.classify] 负责「特征 -> 三档」。
 * 将来真接了 OCR / mlkit，只换 [ScreenSense] 的实现（或加一个同签名的文字实现），
 * 判定阈值和这一整套测试都不用动。
 */
object FocusGuard {

    /** 三态。故意不写成 Boolean：「不像学习」和「看不出是什么」是两件事（见文件头） */
    enum class Verdict(val code: String, val cn: String) {
        STUDY("study", "在学习"),
        NOT_STUDY("play", "不像学习"),
        UNSURE("unsure", "判不出来");

        companion object {
            /** 认不出的 code（老版本写的 / 文件被手改）兜到 UNSURE：读不回就当没看见，绝不默认成「在玩」 */
            fun of(code: String?): Verdict = entries.firstOrNull { it.code == code } ?: UNSURE
        }
    }

    const val ONE_MIN = 60_000L
    const val FIVE_MIN = 5 * ONE_MIN
    const val TEN_MIN = 10 * ONE_MIN

    // ---------------- 版面特征 ----------------

    /**
     * 一张灰度图算出来的版面特征。全部是归一化后的比值（0..1），和分辨率、机型无关。
     *
     * @param lineCount   横向切出来的「文字行」条数。课本 / 题目页通常十几条，视频 / 游戏一般 0~3 条
     * @param lineRounds  文字行里「宽度像一段话」的比例（窄的多数是按钮 / 图标 / 时间，不算正文）
     * @param leftAlign   文字行左边界的规整度：1 = 左边界几乎落在同一列（段落排版），0 = 各占各的
     * @param segRows     行内「墨-纸-墨」连续段数均值。正文行是一整段连续的墨，色块行会碎成很多段
     * @param flatRatio   大部分行列几乎同色的区域占比（视频画面、大色块、纯色背景）。纸面很低
     * @param edgeRatio   强边缘像素占比。视频帧高且均匀，文档页集中在文字行上、总量反而少
     * @param inkRatio    墨色（显著比纸色深）像素占比。白纸黑字大概 3%~20%
     * @param topBand     顶部 5% 里「非纸色」占比（状态栏 / 标题栏）
     * @param bottomBand  底部 8% 里「非纸色」占比（导航栏 / 输入框）
     * @param paper       估计的纸色（0..255 的灰度），给排查看
     */
    data class Features(
        val lineCount: Int,
        val lineRounds: Double,
        val leftAlign: Double,
        val leftRatio: Double,
        val centerRatio: Double,
        val solidRatio: Double,
        val flatRatio: Double,
        val edgeRatio: Double,
        val inkRatio: Double,
        val topBand: Double,
        val bottomBand: Double,
        val paper: Int,
        val w: Int,
        val h: Int,
    ) {
        /** 拿来当人看的排查串（只在日志 / 测试里用，不进报告、不进通知） */
        fun brief(): String =
            "行=" + lineCount + " 宽行=" + pct(lineRounds) + " 左齐=" + pct(leftAlign) +
                " 贴左=" + pct(leftRatio) + " 居中=" + pct(centerRatio) +
                " 实心=" + pct(solidRatio) + " 平=" + pct(flatRatio) +
                " 边=" + pct(edgeRatio) + " 墨=" + pct(inkRatio)

        private fun pct(v: Double): String = (v * 100).toInt().toString() + "%"
    }

    /**
     * 「图 -> 版面特征」的接口。做成接口是为了以后接 OCR / mlkit 时只换实现，判定规则和测试都不动。
     * 输入是**缩过图的灰度数组**（行优先，w×h，0..255），和 [AutoScanPolicy.lightFingerprint] 吃的是同一份。
     */
    interface ScreenSense {
        fun sense(gray: IntArray, w: Int, h: Int): Features
    }

    /** 默认实现：纯像素统计，见 [LayoutSense] */
    val SENSE: ScreenSense = LayoutSense

    /**
     * 把 base64 JPEG 判成三档。**任何异常都不抛**（解不出来 = UNSURE）：
     * 这是自动扫描每 12 秒跑一次的热路径，一次异常把服务带崩比判错严重得多。
     */
    fun classifyScreen(b64: String): Verdict = try {
        val m = Pixels.decode(b64) ?: return Verdict.UNSURE
        val f = SENSE.sense(m.gray, m.w, m.h)
        classify(f).verdict
    } catch (_: Throwable) {
        Verdict.UNSURE
    }

    /** [classify] 的结果。带上特征是为了让报告 / 排查能说清「为什么判成这样」 */
    data class Classify(val verdict: Verdict, val f: Features?, val score: Int) {
        /** 这一屏算不算在学习：UNSURE 既不算学、也不算离开 */
        val learns: Boolean? get() = when (verdict) {
            Verdict.STUDY -> true
            Verdict.NOT_STUDY -> false
            Verdict.UNSURE -> null
        }
    }

    // ---------------- 判定 ----------------

    /** 分数到这条线以上算学习 */
    const val STUDY_AT = 4

    /** 分数在这条线以下算「不像学习」 */
    const val NOT_STUDY_BELOW = 1

    /**
     * 版面特征 -> 三档。**纯函数**，单测直接喂构造出来的特征，不需要真图。
     *
     * 六条依据，每条都是「版面形状」而不是「内容是什么」：
     *  ① 文字行数（>=8 +2，5~7 +1，<=4 -2）
     *  ② 铺得开的行占比（>=0.5 +1）③ 贴左行占比（>=0.5 +1）④ 左边界规整（>=0.6 +1）
     *  ⑤ 实心大色块占比（>0.30 -2）⑥ 高频边缘占比（>0.25 -1）
     * 分数 >= 4 判 STUD；<= 1 判 NOT_STUDY；2~3 判 UNSURE。
     *
     * 阈值的标定过程写在 [LayoutSense] 的文件注释里。这里只说结论：
     * 9 张真实截图（5 张学习页 / 4 张非学习页）全部落在正确的一侧，
     * 但**非学习侧只有 4 个样本**，真实娱乐 App 上仍需再核。
     *
     * 拿到 [Features] 为空（解码失败）一律 UNSURE。
     */
    fun classify(f: Features?): Classify {
        if (f == null || f.w <= 0 || f.h <= 0) return Classify(Verdict.UNSURE, f, 0)
        var s = 0
        // ① 行数：一页有正文的纸总有成片的文字行；短视频 / 游戏 / 看板通常 0~4 条
        s += when {
            f.lineCount >= 8 -> 2
            f.lineCount >= 5 -> 1
            // <= 4 才算「不像」：手机上 5 行以上的正文页已经很常见了，卡在 5 会把练习页误伤
            f.lineCount <= 4 -> -2
            else -> 0
        }
        // ② 有「铺得开」的行：正文行是一条长墨线，按钮 / 时间戳 / 图标都窄得多
        if (f.lineRounds >= 0.5) s += 1
        // ③ 贴左：正文页的行从左边距起头。聊天左右交替、按钮居中，这一项就是它们的照妖镜
        if (f.leftRatio >= 0.5) s += 1
        // ④ 左边界齐：段落排版的行起点几乎在同一条竖线上
        if (f.leftAlign >= 0.6) s += 1
        // ⑤ 实心大色块：视频画面 / 图片 / 大按钮。纸面上墨是被行距切碎的，没有大块实心
        if (f.solidRatio > 0.30) s -= 2
        // ⑥ 高频边缘：视频帧、满屏贴图。文档页的边缘集中在文字行上，总量反而低
        if (f.edgeRatio > 0.25) s -= 1
        val v = when {
            s >= STUDY_AT -> Verdict.STUDY
            s <= NOT_STUDY_BELOW -> Verdict.NOT_STUDY
            else -> Verdict.UNSURE
        }
        return Classify(v, f, s)
    }

    // ---------------- 采样与温和提醒（与判定解耦） ----------------

    /**
     * 一轮采样。
     *
     * @param atMs 墙上时钟（毫秒）。**必须是 System.currentTimeMillis()**，不是 elapsedRealtime ——
     *   报告要按「今天 / 本周」聚合，按开机时长算的钟没法归到日历日。
     * @param verdict 这一屏的判定（[classifyScreen] / [classify] 给的）
     * @param fp 画面轻量指纹，复用 [AutoScanPolicy.lightFingerprint] 那套。
     *   只用于报告里「同一屏只记一条」，不参与判定本身；拿不到就传 null。
     */
    data class Sample(val atMs: Long, val verdict: Verdict, val fp: String? = null)

    /**
     * 连续离开的判据。
     *
     * @param leaveMs      跨度超过这么久才算「离开了」：默认 5 分钟。短于此不提醒 ——
     *   翻到微信回一条消息、切出去看一眼课表，都是正常学习节奏的一部分。
     * @param leaveRounds  还要连续这么多轮 NOT_STUDY。**两个条件同时满足**才成立：
     *   只看时长，一个 30 分钟的广告页会被判成离开；只看轮数，3 轮 x 1 秒等于没离开。
     * @param cooldownMs   提醒之后的冷却：默认 10 分钟。冷却期内不叠加新提醒 ——
     *   否则每 12 秒一轮扫描会变成每分钟五条通知，那是骚扰不是守护。
     * @param roundMs      两轮采样之间的间隔（自动扫描周期）。只用来标注口径，判定本身只用真实时间戳。
     */
    data class Cfg(
        val leaveMs: Long = FIVE_MIN,
        val leaveRounds: Int = 3,
        val cooldownMs: Long = TEN_MIN,
        val roundMs: Long = 12_000L,
    ) {
        /** 夹到合理区间：阈值太小会误报，太大等于永远不提醒 */
        fun clamped(): Cfg = copy(
            leaveMs = leaveMs.coerceIn(ONE_MIN, 60 * ONE_MIN),
            leaveRounds = leaveRounds.coerceIn(2, 60),
            cooldownMs = cooldownMs.coerceIn(ONE_MIN, 120 * ONE_MIN),
            roundMs = roundMs.coerceIn(1_000L, 10 * ONE_MIN),
        )
    }

    /** 「离开学习」的进行态。 */
    data class Leave(
        /** 这一轮离开的开始时刻；0 = 当前没在离开 */
        val since: Long = 0L,
        /** 连续判成 NOT_STUDY 的轮数 */
        val rounds: Int = 0,
        /** 最后一次提醒的时刻；0 = 还没提醒过 */
        val lastAt: Long = 0L,
        /** 到今天提醒过几次（分级文案用：第一次最轻，之后才允许实一点） */
        val nudges: Int = 0,
    ) {
        val active: Boolean get() = since > 0L

        /** 当前这段离开持续了多久（没在离开就 0） */
        fun span(now: Long): Long = if (since <= 0L) 0L else (now - since).coerceAtLeast(0L)

        /** 离开结束：计数清零，但「今天提醒过几次 / 上次什么时候提醒」要留着（否则冷却和分档都失效） */
        fun reset(): Leave = Leave(lastAt = lastAt, nudges = nudges)
    }

    /**
     * 喂一轮采样，返回新状态。**纯函数**。
     *
     * 规则：
     *  - 判成学习：离开结束、清零。
     *  - 判不出来（UNSURE）：**既不结束也不推进**。解码失败、锁屏、纯色广告页都长这样，
     *    拿它推进计数就是在冤枉人。轮数保留，跨度继续按真实时间走（时间确实过去了）。
     *    这里唯一要防的是「一整段全是 UNSURE 却因跨度够长而提醒」—— 所以 [due] 额外要求轮数达标，
     *    而轮数只在 NOT_STUDY 上增长。
     *  - 判成不像学习：正在离开就 +1 轮；没在离开就开一段新的（since = 这一轮的时刻）。
     */
    fun observe(prev: Leave, s: Sample): Leave = when (s.verdict) {
        Verdict.STUDY -> if (prev.active || prev.rounds > 0) prev.reset() else prev
        Verdict.UNSURE -> prev
        Verdict.NOT_STUDY -> if (prev.active) prev.copy(rounds = prev.rounds + 1)
        else prev.copy(since = s.atMs, rounds = 1)
    }

    /** 一段离开的时长上限：超过这么久一律按上限算 —— 手机在兜里、App 被冻结的那两小时不是「专注地玩」 */
    const val MAX_SPAN_MS = 2 * 60 * ONE_MIN

    /**
     * 现在该不该提醒。三个条件全都满足才算：
     *  1. 正在离开，且 [Leave.span] >= cfg.leaveMs；
     *  2. 连续 [Leave.rounds] >= cfg.leaveRounds（挡掉「一轮跨越 8 分钟的墓碑采样」）；
     *  3. 距上次提醒已经过了 cfg.cooldownMs。
     *
     * 开关关着时调用方根本不喂采样，所以这里没有「开关」参数 —— 少一个能被忘记判断的开关，就少一类 bug。
     */
    fun due(l: Leave, now: Long, cfg: Cfg): Boolean {
        if (!l.active) return false
        if (l.rounds < cfg.leaveRounds) return false
        if (l.span(now) < cfg.leaveMs) return false
        if (l.lastAt > 0L && now - l.lastAt < cfg.cooldownMs) return false
        return true
    }

    /** 这一轮提醒的强度档：1 = 第一次（最轻），2 = 第二次，3 = 第三次及以后 */
    const val MAX_TIER = 3

    /**
     * 该发的这条提醒是第几档。
     *
     * 分档看的是**已提醒次数**而不是「违规严重度」—— 第一次永远只说一句话，用户自己知道分寸；
     * 连着三次还在外面，语气才允许稍实一点。任何一档都不出现「你又在玩」「不许」「必须」这类词：
     * 这是给学习者用的工具，不是家长监控。
     */
    fun tier(l: Leave): Int = (l.nudges + 1).coerceIn(1, MAX_TIER)

    /** 提醒之后的新状态：记下时刻、次数 +1，离开计数保留（还在同一次离开里） */
    fun nudged(l: Leave, now: Long): Leave = l.copy(lastAt = now, nudges = l.nudges.coerceIn(0, 999) + 1)

    /**
     * 提醒文案。三档：
     *  - 第 1 档：一行字，说事实不说教
     *  - 第 2 档：把判据说出来（「刚才那几屏都不太像书页」），还留一条「我看错了就当没看见」
     *  - 第 3 档：说「今天第 N 次」，然后主动给一个出口（想歇会儿也行）
     *
     * 时长用 [fmtDur]，没有真数据就不写数字；超过 [MAX_SPAN_MS] 按上限说。
     */
    fun nudgeText(l: Leave, now: Long, cfg: Cfg): String {
        val span = l.span(now).coerceAtMost(MAX_SPAN_MS)
        val dur = if (span < ONE_MIN) "" else fmtDur(span)
        return when (tier(l)) {
            1 -> if (dur.isEmpty()) "离开学习一小会儿了，回来接着看吧" else "离开学习 " + dur + "，回来接着看吧"
            2 -> (if (dur.isEmpty()) "离开学习一小会儿了" else "离开学习 " + dur) +
                " · 刚才那几屏都不太像书页，是我看错了就当没看见"
            else -> (if (dur.isEmpty()) "离开有一阵了" else "离开 " + dur + " 了") +
                " · 这是今天的第 " + (l.nudges + 1).coerceAtMost(99) + " 次 · 想先歇会儿也行，点开看看今天学了多久"
        }
    }

    /** 通知标题：短，一行放得下，第一档不带任何数落 */
    fun nudgeTitle(l: Leave): String = when (tier(l)) {
        1 -> "小珠 · 回来接着看？"
        2 -> "小珠 · 还在学吗"
        else -> "小珠 · 今天第 " + (l.nudges + 1).coerceAtMost(99) + " 次提醒"
    }

    /**
     * 时长文案。**向下截断，绝不四舍五入**。
     *
     * 理由是这条功能唯一不能违反的规则：不能让用户看到比自己实际学习时间更长的数字。
     * 四舍五入会把 30 秒说成「1 分钟」、把 2 小时 59 分说成「3 小时」—— 报得比实际多，
     * 那这份报告就不可信了。宁可少报：59 秒就老老实实说「不到 1 分钟」。
     *
     *  - 不到 1 分 -> "不到 1 分钟"（不写「0 分钟」，那是没算出来的说法）
     *  - 不到 60 分 -> "N 分钟"
     *  - 更长 -> "H 小时 M 分"（M 为 0 只写小时）
     */
    fun fmtDur(ms: Long): String {
        val min = ms.coerceAtLeast(0L) / ONE_MIN
        if (min <= 0L) return "不到 1 分钟"
        if (min < 60) return min.toString() + " 分钟"
        val h = min / 60
        val m = min % 60
        return if (m == 0L) h.toString() + " 小时" else h.toString() + " 小时 " + m + " 分"
    }

    // ---------------- 息屏 / 亮屏 ----------------

    /**
     * 熄屏（或锁屏）了：这一段离开到此为止。
     *
     * 为什么必须断掉：息屏后屏幕是黑的，继续采样只会得到一片全黑图 ——
     * 若判成 NOT_STUDY，用户合上手机去睡觉就会被记成「离开学习 8 小时」，
     * 第二天早上收到一串提醒。那不是守护，是骚扰。所以熄屏 = 断掉这一段，回来是新的起点，
     * 息屏期间既不产生采样、也不计专注时长、也不计离开时长。
     *
     * 但 [Leave.nudges] 与 [Leave.lastAt] 必须保留：它们是「今天提醒过几次 / 上次几点提醒」，
     * 属于当天配额。跟着息屏一起清掉的话，用户每次锁屏再解锁就重置了冷却，
     * 12 秒一轮的扫描能立刻再弹一条 —— 那才是最烦人的行为。
     */
    fun onScreenOff(l: Leave): Leave = l.reset()

    /**
     * 亮屏了：从这一刻重新开始计，返回干净的离开状态。
     *
     * 为什么给默认值而不是原样返回：亮屏那一瞬间是锁屏壁纸 / 桌面，
     * 既不是书页也不是娱乐，下一轮采样大概率 UNSURE。带着熄屏前的残留轮数回来，
     * 一轮 UNSURE 接着一轮 NOT_STUDY 就可能凑够 [Cfg.leaveRounds]，
     * 把「刚解锁看一眼桌面」判成「已经离开 5 分钟」—— 那是最冤的误报。
     *
     * 但今日提醒计数与冷却同样保留（理由见 [onScreenOff]），否则锁屏解锁就能重置冷却。
     */
    fun onScreenOn(l: Leave): Leave = Leave(lastAt = l.lastAt, nudges = l.nudges)

    /** 这一轮该不该记进报告（UNSURE 不记、同一屏不重复记），见 [FocusReport] */
    fun keepForReport(s: Sample, lastFp: String?): Boolean =
        s.verdict != Verdict.UNSURE && (s.fp.isNullOrEmpty() || s.fp != lastFp)
}

/**
 * 默认的版面感知实现：只吃灰度像素，绝不看内容。
 *
 * ## 怎么把「一页纸」和「一块视频」分开
 * 纸面上字是**横平竖直**的：一行墨、一行空、再一行墨……每行的左边界落在同一条竖线附近，
 * 行内是一整段连续的墨（中间偶尔断一两个字的间隙）。视频 / 游戏 / 聊天界面没有这个骨架：
 * 要么整块都是颜色（纯色占比高），要么边缘到处都是（高频细节），要么文字少得可怜（只有按钮和时间）。
 *
 * ## 分辨率无关
 * 所有量都算成比值。行高门限按图像高度缩放（[LINE_MIN]），所以 720p 和 1440p 上量出来的「行数」是同一档。
 *
 * ## 这套阈值是拿真实截图核过的（诚实说明）
 * 用 06-实测录屏-20261017 目录里的 9 张真实截图逐张量过（脚本不入库，只是标定用）：
 *  - 学习侧 5 张（试卷放大页 2 张、题目列表页 2 张、整页讲义 1 张）：score 4~5，全判 STUDY；
 *  - 非学习侧 4 张（聊天 / 文件列表页 3 张、确认弹窗 2 张）：score -2~2，全判 UNSURE 或 NOT_STUDY。
 * 分档线（>=4 STUDY / <=1 NOT_STUDY / 2~3 UNSURE）就是照这个分布定的，
 * 但非学习侧只有 4 个样本，而且都不算典型的娱乐画面（没有视频、没有游戏）。
 * 真实短视频 / 游戏界面还需要拿真机截图再核一轮 —— 在那之前，非学习侧宁可靠 UNSURE 放过去，也不硬判。
 */
internal object LayoutSense : FocusGuard.ScreenSense {

    /** 一条文字行最少要有多少像素高（按图像高度的比例，随分辨率缩放） */
    const val LINE_MIN = 0.004

    /** 判定「这是墨」的门限：比纸色深多少算字（灰度差） */
    const val INK_DELTA = 40

    /** 一行里墨像素占比的下限（低于它是零星噪点） */
    const val INK_ON_LINE = 0.01

    /** 「像一段话」的行：墨要铺开占整宽的这个比例（按钮、图标、时间戳都到不了） */
    const val ROUND_LINE = 0.18

    /** 纯色判定：一行里灰度极差小于这个数，就算「这一行是同色的」 */
    const val FLAT_RANGE = 12

    /** 强边缘判定：和左邻像素的灰度差超过这个数算一次边缘 */
    const val EDGE_DELTA = 60

    /**
     * 「实心」判定：一行里墨的**最长连续段**占整宽高于这个数 —— 那是色块 / 图片，不是字。
     *
     * 为什么用最长连续段而不是整行墨占比：一行排满的字，字间距会把它切成几十段小墨块，
     * 整行占比照样能到 0.4（实测讲义页就是 0.38），用它判色块会把整页讲义冤枉成视频。
     * 真正的大色块 / 视频画面是一整条连续的墨，中间没有字间距那种空隙。
     */
    const val SOLID_RUN = 0.55

    /**
     * 「贴左」的容差：行首落在图宽这个比例以内算贴着左边界。
     *
     * 0.06 是拿真实截图量出来的：手机页面左边距通常在 16~24dp，占屏宽 4%~7%
     * （240 宽的缩略图上是 10~16px）。0.03 太紧 —— 16px 就被判成「不贴左」，
     * 一页正常排版的讲义会被算成 0% 贴左，整条判定直接反向。这类阈值必须留够余量。
     */
    const val LEFT_TOL = 0.06

    /** 「居中」的判定：行首在这个比例之后、行尾在对侧这个比例之前 —— 对话框和按钮长这样 */
    const val CENTER_FROM = 0.18
    const val CENTER_TO = 0.82

    /** 自适应门限：取正文区「行墨占比」的第 20 百分位当纸底，再加一个余量 */
    const val FLOOR_PCT = 0.2
    const val FLOOR_ADD = 0.05

    /** 间隙小于这个比例的行段合回同一行（字与字之间的空隙不该被算成新的一行） */
    const val MERGE_GAP = 0.05

    override fun sense(gray: IntArray, w: Int, h: Int): FocusGuard.Features {
        if (w <= 0 || h <= 0 || gray.size < w * h) return empty(w, h)

        // 1) 纸色 = 直方图众数。白纸黑字的页面众数几乎是白的；深色模式众数是深灰，
        //    所以「墨」的方向由纸色决定，不写死成「深色才是字」。
        val hist = IntArray(256)
        for (i in 0 until w * h) hist[gray[i].coerceIn(0, 255)]++
        var paper = 0
        for (v in 1..255) if (hist[v] > hist[paper]) paper = v
        val inkIsDarker = paper >= 96

        // 2) 顶部 / 底部条先量出来：状态栏和导航栏是整条纯色，不该被算成正文行
        val topEnd = (h * 0.05).toInt().coerceAtLeast(1)
        val botStart = h - (h * 0.08).toInt().coerceAtLeast(1)
        val topBand = bandInk(gray, w, 0, topEnd, paper, inkIsDarker)
        val bottomBand = bandInk(gray, w, botStart, h, paper, inkIsDarker)

        // 3) 逐行的墨占比
        val ratios = DoubleArray(h) { y -> bandInk(gray, w, y, y + 1, paper, inkIsDarker) }

        // 4) 自适应门限：正文区的第 20 百分位就是「纸底的行」，比它多出 5% 才算这一行有字。
        //    固定门限在灰底 / 深色模式 / 截图被压缩时会整片误判，用相对量能把三种情况都吃掉。
        val floor = percentile(ratios, topEnd, botStart, FLOOR_PCT)
        val thr = maxOf(INK_ON_LINE, floor + FLOOR_ADD)

        // 5) 切成行段，再把贴得太近的段并回同一行
        val bands = ArrayList<IntArray>(64)
        var y = 0
        while (y < h) {
            if (ratios[y] >= thr) {
                var y1 = y
                while (y1 < h && ratios[y1] >= thr) y1++
                bands.add(intArrayOf(y, y1))
                y = y1
            } else y++
        }
        val lineMin = (h * LINE_MIN).toInt().coerceAtLeast(1)
        val mergeGap = (lineMin * MERGE_GAP).toInt().coerceAtLeast(1)
        val merged = ArrayList<IntArray>(bands.size)
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b[0] - last[1] <= mergeGap) last[1] = b[1] else merged.add(intArrayOf(b[0], b[1]))
        }

        // 6) 每段的左边界、右边界、墨占比
        var lineCount = 0
        var roundLines = 0
        var leftRows = 0
        var centerRows = 0
        var inkPixels = 0L
        var solidPixels = 0L
        val lefts = ArrayList<Int>(64)
        for (band in merged) {
            val y0 = band[0]
            val y1 = band[1]
            var ink = 0
            var first = -1
            var last = -1
            var maxRun = 0          // 这一行里最长的一段连续墨（判大色块要用它，不能用整行占比）
            var run = 0
            for (yy in y0 until y1) {
                val base = yy * w
                run = 0
                for (x in 0 until w) {
                    if (isInk(gray[base + x], paper, inkIsDarker)) {
                        ink++
                        run++
                        if (run > maxRun) maxRun = run
                        if (first < 0) first = x
                        last = x
                    } else run = 0
                }
            }
            val ratio = ink.toDouble() / ((y1 - y0) * w).coerceAtLeast(1)
            inkPixels += ink
            if (y1 - y0 < lineMin || first < 0 || ratio < INK_ON_LINE) continue
            lineCount++
            if (ratio >= ROUND_LINE) roundLines++
            if (first <= w * LEFT_TOL) leftRows++
            if (first > w * CENTER_FROM && last < w * CENTER_TO) centerRows++
            // 大色块：有一大段连续不断的墨（占整宽 SOLID_RUN 以上）。一整行排满的字不算 ——
            // 字间距会把它切成很多小段，最长段远到不了这个比例。
            if (maxRun.toDouble() / w >= SOLID_RUN) solidPixels += ink
            lefts.add(first)
        }

        // 7) 左边界规整度：起点标准差越小越齐（按图宽归一）
        val leftAlign = if (lefts.size < 3) 0.0 else {
            val mean = lefts.average()
            val sd = Math.sqrt(lefts.sumOf { (it - mean) * (it - mean) } / lefts.size)
            (1.0 - sd / (w * 0.12)).coerceIn(0.0, 1.0)
        }

        // 8) 整行纯色占比与高频边缘占比：视频 / 图片的特征
        var flatRows = 0
        for (ry in 0 until h) {
            var mn = 255
            var mx = 0
            val base = ry * w
            for (x in 0 until w) {
                val v = gray[base + x]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mx - mn < FLAT_RANGE) flatRows++
        }
        var edges = 0
        for (ry in 0 until h) {
            val base = ry * w
            for (x in 1 until w) {
                val d = gray[base + x] - gray[base + x - 1]
                if (d > EDGE_DELTA || -d > EDGE_DELTA) edges++
            }
        }

        val total = w.toLong() * h
        return FocusGuard.Features(
            lineCount = lineCount,
            lineRounds = if (lineCount == 0) 0.0 else roundLines.toDouble() / lineCount,
            leftAlign = leftAlign,
            leftRatio = if (lineCount == 0) 0.0 else leftRows.toDouble() / lineCount,
            centerRatio = if (lineCount == 0) 0.0 else centerRows.toDouble() / lineCount,
            solidRatio = solidPixels.toDouble() / total,
            flatRatio = flatRows.toDouble() / h,
            edgeRatio = edges.toDouble() / (h * (w - 1).coerceAtLeast(1)),
            inkRatio = inkPixels.toDouble() / total,
            topBand = topBand,
            bottomBand = bottomBand,
            paper = paper,
            w = w,
            h = h,
        )
    }

    /** [from, to) 这几行里墨像素占全部像素的比例 */
    private fun bandInk(gray: IntArray, w: Int, from: Int, to: Int, paper: Int, inkIsDarker: Boolean): Double {
        var n = 0
        for (y in from until to) {
            val base = y * w
            for (x in 0 until w) if (isInk(gray[base + x], paper, inkIsDarker)) n++
        }
        val total = (to - from) * w
        return if (total <= 0) 0.0 else n.toDouble() / total
    }

    /** [from, to) 区间的墨占比取第 [pct] 百分位（排序后取下标；区间空就用 0） */
    private fun percentile(v: DoubleArray, from: Int, to: Int, pct: Double): Double {
        val a = from.coerceIn(0, v.size)
        val b = to.coerceIn(a, v.size)
        if (b <= a) return 0.0
        val part = v.copyOfRange(a, b)
        part.sort()
        return part[((b - a - 1) * pct).toInt().coerceIn(0, b - a - 1)]
    }

    /** 这个像素算不算「墨」：浅底要比纸色深、深色模式要比纸色亮，幅度都要够 [INK_DELTA] */
    private fun isInk(v: Int, paper: Int, inkIsDarker: Boolean): Boolean =
        if (inkIsDarker) v <= paper - INK_DELTA else v >= paper + INK_DELTA

    /** 尺寸不合法时给一份空特征（[FocusGuard.classify] 会把它判成 UNSURE） */
    internal fun empty(w: Int, h: Int) =
        FocusGuard.Features(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, w, h)
}

/**
 * base64 图片 -> 缩略灰度图。
 *
 * 和 [Cache] 里「缩到 32 宽再算指纹」是同一个思路：版面特征不需要原分辨率，
 * 缩到宽 [MAX_W] 就够分辨「十几条文字行」和「一整块视频」，而这张灰度图只有几十 KB 的数组，
 * 12 秒一轮也不心疼。解码出来就立刻拿灰度走人，**不保留任何位图、不落盘**。
 */
internal object Pixels {

    /** 送进 [LayoutSense] 的宽度上限。再大只会变慢，不会更准（行数按比例算） */
    const val MAX_W = 160

    /** 高度上限：超长截图的底部通常只是空白，留着只会把分母撑大 */
    const val MAX_H = 400

    /** 解码结果。gray 是行优先的灰度，0..255 */
    class Gray(val gray: IntArray, val w: Int, val h: Int)

    /** 解不出来返回 null。**不抛异常**：调用方（[FocusGuard.classifyScreen]）拿 null 当 UNSURE */
    fun decode(b64: String): Gray? = try {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val opt = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
        val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: return null
        val w = src.width.coerceAtMost(MAX_W).coerceAtLeast(1)
        val h = (src.height.toFloat() * w / src.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_H)
        val small = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        if (src !== small) src.recycle()
        small.recycle()
        Gray(gray, w, h)
    } catch (_: Throwable) {
        null
    }
}
