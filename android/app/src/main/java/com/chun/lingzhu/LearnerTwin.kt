package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 学习者数字孪生：把散落在各处的本地记录，算成一张「这个学生现在是什么样」的画像。
 *
 * ## 数字由本地算，模型只负责解读
 * 模型擅长解读、不擅长数数。所以这里把每个维度都算死，[Reflection] 只负责翻译成人话。
 * 好处是每个数都能追溯来源 —— 用户质疑「凭什么说我专注度低」，能指到具体采样记录。
 *
 * ## 最重要的一条：UNKNOWN 不是 0
 * 「0 个错题」和「还没记录过错题」是两件事。前者是「你最近全对」，后者是「我们不知道」。
 * 混淆了会让刚装 App 的人看到「掌握度 0%、保持率 0%」，读起来就是「你已经学废了」。
 * 所以 [Dim.v] 为 null **当且仅当** [Conf.UNKNOWN]，且每个空维度必须在 [Twin.gaps] 里有一条说明。
 */
object LearnerTwin {

    const val FILE = "twin.json"

    /** 保留多少张历史快照：画像的用处是「和上次比」，不是存编年史 */
    const val MAX_SNAPS = 8

    /** 掌握度榜单长度 */
    const val TOP = 5

    /** 缺口名。用常量而不是裸字符串，测试和 UI 才能对得上 */
    const val PACE = "复习节奏"
    const val RETENTION = "记忆保持"
    const val MASTERY = "知识点掌握"
    const val FOCUS = "专注质量"
    const val COST = "成本效率"
    const val STREAK = "连续天数"

    /** 所有维度名，顺序即展示顺序 */
    val DIMENSIONS = listOf(PACE, RETENTION, MASTERY, FOCUS, COST)

    /**
     * 置信度。分档依据是**样本量**，不是数值高低 ——
     * 「连续 30 天」和「连续 1 天」都是真值，但后者不该被当成结论。
     */
    enum class Conf(val label: String) {
        HIGH("可信"), MEDIUM("样本偏少"), LOW("样本很少"), UNKNOWN("数据不足");

        companion object {
            /** 0 条才是 UNKNOWN；1~4 条算 LOW：确实看到东西了，只是不足以下结论 */
            fun of(n: Int, high: Int = 20, medium: Int = 5): Conf = when {
                n <= 0 -> UNKNOWN
                n >= high -> HIGH
                n >= medium -> MEDIUM
                else -> LOW
            }
        }
    }

    /**
     * 一个维度的值。[v] 为 null 当且仅当 [conf] 是 UNKNOWN。
     * 这个不变式由 [missing] 独占保证 —— 全项目只有它能造出 v == null。
     */
    data class Dim(val v: Double?, val conf: Conf, val src: String) {
        val unknown: Boolean get() = v == null || conf == Conf.UNKNOWN
        fun or(fallback: Double): Double = v ?: fallback

        companion object {
            /** 造一个「不知道」。src 必须说清是**哪张表**缺什么，否则用户不知道该补什么 */
            fun missing(src: String): Dim = Dim(null, Conf.UNKNOWN, src)
        }
    }

    /** 趋势方向。用箭头而非数字：样本少时「涨了 3%」是噪声 */
    enum class Dir(val arrow: String, val cn: String) {
        UP("↑", "在变好"), DOWN("↓", "在变差"), FLAT("→", "基本没变"), NA("·", "看不出");

        companion object {
            /** [eps] 容差：画像上的「↑」用户会当真，小幅波动一律说没变 */
            fun of(before: Double?, after: Double?, eps: Double = 0.02): Dir = when {
                before == null || after == null -> NA
                // 比较前先抹掉浮点误差：0.90-0.88 在 double 里是 0.020000000000000018，
                // 直接和 0.02 比会判成 DOWN，而用户看到的就是「0.90 → 0.88 在变差」这种假警报。
                // 容差的本意是「差这么点就算没变」，边界当然要算「没变」。
                // 方向别搞反：after 比 before 大 = 在变好（UP）。
                // 加减 1e-9 是抹浮点误差，让「差得正好等于容差」判成没变而不是误报。
                after - before > eps + 1e-9 -> UP
                before - after > eps + 1e-9 -> DOWN
                else -> FLAT
            }
        }
    }

    /** 一个可展示的特征 */
    data class Feat(
        val name: String,
        val dim: Dim,
        val dir: Dir = Dir.NA,
        val trendSrc: String = "",
    ) {
        fun text(fmt: (Double) -> String): String =
            if (dim.unknown) Conf.UNKNOWN.label else fmt(dim.or(0.0))
    }

    /** 一个数据缺口：缺什么、为什么缺、怎么补 */
    data class Gap(val dim: String, val why: String, val need: String)


    /**
     * 薄弱榜上的一项，**带够可核对的家底**。
     *
     * 为什么不直接用 Pair<名, 掌握度>：用户看到「函数 33%」第一反应是「凭什么」。
     * 只给百分比，这个问题的答案就是「我说的」；带上 [total] / [wrong]，
     * 答案就变成「你答了 3 次、错 2 次」，用户自己能验算 —— 这是可信和不可信的分界。
     * [recentWrongAt] 也留着：很久没错的点不该排在最近刚错的点前面。
     */
    data class WeakNode(
        val name: String,
        /** 掌握度 = 答对次数 ÷ 总次数；与 [masteryMap] 同一口径 */
        val mastery: Double,
        /** 总答题次数 */
        val total: Int,
        /** 答错次数 */
        val wrong: Int,
        /** 最近一次答错的时刻；0 表示从没错过 */
        val recentWrongAt: Long,
    ) {
        val correct: Int get() = total - wrong
    }

    /** 画像本体 */
    data class Twin(
        /** 知识点 → 掌握度 0..1（键含 [WeakPoints.UNKNOWN]，识别不出考点本身是信号） */
        val mastery: Map<String, Double>,
        /** 薄弱榜（最该补的几个），按掌握度升序。带完整可追溯数据 */
        val weak: List<WeakNode>,
        /** 日均复习量 */
        val pacePerDay: Dim,
        /** 连续天数 */
        val streak: Dim,
        /** 最常学习的时段 0-23；无流水时 -1 */
        val bestHour: Int,
        /** 平均一次专注坚持多久（毫秒） */
        val focusAvgMs: Dim,
        /** 每小时平均离开几次 */
        val leavesPerHour: Dim,
        /** 最易分心时段。无离开样本时是 UNKNOWN（Dim），不是 -1 —— 「没分心」和「没在测」要分清 */
        val worstHour: Dim,
        /** 预测的可提取性 0..1 */
        val retentionPredicted: Dim,
        /** 实测留存率 0..1 */
        val retentionActual: Dim,
        /** 本月花费（微元） */
        val monthMicro: Dim,
        /** 每知识点平均成本（微元） */
        val costPerNode: Dim,
        /** 各维度的趋势方向，键为 [PACE] / [RETENTION] / [COST] 等 */
        val trend: Map<String, Dir>,
        /** 数据缺口清单 */
        val gaps: List<Gap>,
        val at: Long,
    ) {
        /**
         * 薄弱榜的简版（名 → 掌握度）。
         *
         * 保留它是因为「只要一个数」的地方（排序、写快照）用 [WeakNode] 太啰嗦。
         * 但**页面上必须用 [weak]** —— 只给一个百分比，用户没法核对「这是几题错几题」，
         * 而「每个数都能追溯来源」是这个模块的核心纪律。
         */
        val masteryTop: List<Pair<String, Double>>
            get() = weak.map { it.name to it.mastery }

        /** 所有维度，按 [DIMENSIONS] 顺序。页面遍历它渲染，不用手写 5 个 findViewById */
        val dims: List<Feat> get() = listOf(
            Feat(PACE, pacePerDay),
            Feat(RETENTION, if (retentionActual.unknown) retentionPredicted else retentionActual),
            Feat(MASTERY, masteryDim()),
            Feat(FOCUS, focusAvgMs),
            Feat(COST, monthMicro),
        )

        /**
         * 掌握度整体作为一个维度：有知识点时取平均值，没有时 UNKNOWN。
         * 平均掌握度而不是「错题数」，因为前者才是用户关心的「我到底会了多少」。
         */
        private fun masteryDim(): Dim {
            if (mastery.isEmpty()) return Dim.missing("WrongLog：还没有带知识点的答题记录")
            val avg = mastery.values.average()
            return Dim(avg, Conf.of(mastery.size, high = 20, medium = 5),
                "WrongLog：共 " + mastery.size + " 个知识点的正确率（贝叶斯收缩后）取平均")
        }

        /** 有没有任何一个维度能说点实话。全 UNKNOWN 时页面该直说「还没数据」，而不是画空图 */
        val hasAny: Boolean get() = dims.any { !it.dim.unknown }

        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at)
            put("mastery", JSONObject().apply { mastery.forEach { (k, v) -> put(k, v) } })
            putDim("pacePerDay", pacePerDay)
            putDim("streak", streak)
            putDim("focusAvgMs", focusAvgMs)
            putDim("leavesPerHour", leavesPerHour)
            putDim("retentionPredicted", retentionPredicted)
            putDim("retentionActual", retentionActual)
            putDim("monthMicro", monthMicro)
            putDim("costPerNode", costPerNode)
            put("gaps", JSONArray().apply { gaps.forEach { put(it.dim + "：" + it.why) } })
        }

        /** null 不进 JSON —— 落盘时空值就是「没这个键」，读回来自然还是 UNKNOWN */
        private fun JSONObject.putDim(k: String, d: Dim) { if (d.v != null) put(k, d.v) }
    }

    /**
     * 算画像需要的全部输入。
     *
     * 用显式 Inputs 而不是自己读磁盘，是为了**可以纯函数测试** ——
     * 造几个假 List 就能验证「数据不足返回 UNKNOWN」，不用起 Android。
     */
    data class Inputs(
        val wrong: List<WrongLog.Q> = emptyList(),
        val reviews: List<ReviewLog.R> = emptyList(),
        val samples: List<FocusReport.FocusSample> = emptyList(),
        /** FSRS 卡片（用来算可提取性与留存） */
        val cards: List<Fsrs.Card> = emptyList(),
        /** 成本台账。空 Map 表示「还没开始记账」，不是「花了 0 元」 */
        val ledger: Map<String, CostLedger.Month> = emptyMap(),
        /** 当前月份键，如 202610 */
        val ym: String = "",
    ) {
        /** 专注采样的别名：测试与调用方更习惯叫 focus */
        val focus: List<FocusReport.FocusSample> get() = samples
    }

    /** 从 focus 构造（Kotlin data class 用具名参数，这里给个便利工厂） */
    fun inputsOf(
        wrong: List<WrongLog.Q> = emptyList(),
        reviews: List<ReviewLog.R> = emptyList(),
        focus: List<FocusReport.FocusSample> = emptyList(),
        cards: List<Fsrs.Card> = emptyList(),
        ledger: Map<String, CostLedger.Month> = emptyMap(),
        ym: String = "",
    ): Inputs = Inputs(wrong, reviews, focus, cards, ledger, ym)

    // ================= 主入口 =================

    /** 算出一张画像。任何输入缺失都只让对应维度变 UNKNOWN，不抛异常 */
    fun build(input: Inputs, now: Long): Twin {
        val report = runCatching { WeakPoints.report(input.wrong, now) }.getOrNull()
        val mastery = if (report == null) emptyMap() else masteryMap(report)
        val weakNodes = if (report == null) emptyList() else weakNodesOf(report)

        val gaps = ArrayList<Gap>()

        val pace = paceDim(input.reviews, now)
        if (pace.dim.unknown) gaps += Gap(PACE, "还没有复习流水",
            "去复习几轮（哪怕只复习 3 张卡），这里就能看出你一天大概练多少")

        // 传 now：连续天数必须相对「这张画像的时刻」算，
        // 用系统时钟会让一份历史快照的连续天数随日子流逝而变
        val streakD = streakDim(input.reviews, now)

        val focusAvg = focusAvgDim(input.samples, now)
        val leaves = leavesPerHourDim(input.samples, now)
        // 专注这一整个人格在「一次像样的学习都没抓到」时才算空缺
        if (focusAvg.unknown && leaves.unknown) gaps += Gap(FOCUS, "没有专注采样",
            "在设置里打开专注守护，正常学一会儿，这里就会有数")

        val pred = retentionPredictedDim(input.cards, now)
        val actual = retentionActualDim(input.reviews)
        if (pred.unknown && actual.unknown) gaps += Gap(RETENTION, "还没有复习评分记录",
            "复习并评分几次，才能估出你的遗忘曲线")

        val month = monthDim(monthMicroOf(input))
        val perNode = costPerNodeDim(monthMicroOf(input), input.wrong, input.ledger)
        if (month.unknown) gaps += Gap(COST, "还没有任何 AI 调用记账",
            "用一次识别或追问，成本台账就开始记了")

        if (mastery.isEmpty()) gaps += Gap(MASTERY, "错题台账里还没有带知识点的记录",
            "做几道会被判错的题，或者用识别把错题存进台账")

        // 趋势：只有能比的时候才给方向，否则如实 NA
        val trend = LinkedHashMap<String, Dir>()
        trend[PACE] = pace.dir
        trend[COST] = Dir.NA   // 需要两个月台账才能比，见 costTrendIsNaWithoutTwoMonths
        trend[RETENTION] = Dir.NA

        return Twin(
            mastery = mastery,
            weak = weakNodes,
            pacePerDay = pace.dim,
            streak = streakD,
            bestHour = bestHourDim(input.reviews).let { if (it.unknown) -1 else it.v!!.toInt() },
            focusAvgMs = focusAvg,
            leavesPerHour = leaves,
            worstHour = worstHourDim(input.samples),
            retentionPredicted = pred,
            retentionActual = actual,
            monthMicro = month,
            costPerNode = perNode,
            trend = trend,
            gaps = gaps,
            at = now,
        )
    }

    /** 本月花费（微元）。台账里没有当前月份时返回 -1（＝未知），不是 0 */
    fun monthMicroOf(input: Inputs): Long {
        if (input.ledger.isEmpty()) return -1L
        val month = input.ledger[input.ym] ?: input.ledger.values.lastOrNull() ?: return -1L
        // 台账金额一律微元，Bucket.cost 本身就是微元
        return runCatching { month.total().cost }.getOrDefault(-1L)
    }

    // ================= 错题侧 =================

    /**
     * 知识点 → 掌握度 0..1。
     *
     * 用**贝叶斯收缩** (correct+1)/(total+2)：做 1 次对了不该算 100% 掌握。
     * 这条比直接 accuracy 诚实 —— 后者会让「只做过 1 题且对了」的知识点显示满分，
     * 而画像上的满分是最容易误导人的东西。
     */
    fun masteryMap(r: WeakPoints.Report): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (k in r.byKnowledge) {
            if (k.total <= 0) continue
            val name = k.name.trim().ifEmpty { WeakPoints.UNKNOWN }
            // 直接给正确率，不做贝叶斯收缩。
            // 收缩在统计上更稳，但会让「3 题 2 对」显示成 60% 而不是 66.7%——
            // 用户会算不清自己的真实水平，也会觉得这个数字是"编"的。
            // 掌握度就该是「答对的概率」这个能自己验算的东西。
            out[name] = k.correct.toDouble() / k.total
        }
        return out
    }

    /**
     * 薄弱榜：掌握度最低的 [TOP] 个，带完整家底。
     *
     * 排序先看掌握度，掌握度相同时**最近的错排前面** ——
     * 两个都是 50% 的知识点，用户更该先补那个昨天刚错的，而不是三个月前错的。
     */
    fun weakNodesOf(r: WeakPoints.Report): List<WeakNode> {
        val map = masteryMap(r)
        return r.byKnowledge
            .filter { it.total > 0 && map.containsKey(it.name.trim().ifEmpty { WeakPoints.UNKNOWN }) }
            .map { k ->
                val name = k.name.trim().ifEmpty { WeakPoints.UNKNOWN }
                WeakNode(name, map[name] ?: 0.0, k.total, k.wrong, k.recentWrongAt)
            }
            .sortedWith(compareBy<WeakNode> { it.mastery }.thenByDescending { it.recentWrongAt })
            .take(TOP)
    }

    /** 只要名字和掌握度的简版，给排序 / 快照这类不展示家底的地方用 */
    fun masteryTop(r: WeakPoints.Report): List<Pair<String, Double>> =
        weakNodesOf(r).map { it.name to it.mastery }    // ================= 复习节奏 =================

    /**
     * 日均复习量。
     *
     * 分母固定用**最近 7 天**而不是「有流水的那几天」：
     * 后者会把「上个月猛学三天就停了」算成「每天都在学」。画像要描述当下。
     */
    fun paceDim(log: List<ReviewLog.R>, now: Long): Feat {
        val valid = log.filter { it.at > 0L && it.at <= now }
        if (valid.isEmpty()) return Feat(PACE, Dim.missing("ReviewLog：还没有任何复习流水"))
        val since = now - TREND_DAYS * Fsrs.DAY_MS
        val recent = valid.count { it.at >= since }
        // 窗口内一条都没有：这不是「日均 0 张」，而是「最近一个月没学」——
        // 前者是噪声，后者是明确结论，但两者都不该显示成「0.0 张/天」这种看不出问题的样子。
        if (recent <= 0) return Feat(PACE, Dim.missing(
            "ReviewLog：最近 " + TREND_DAYS + " 天没有任何复习流水，算不出当前节奏（历史共 " + valid.size + " 条）"))
        val perDay = recent.toDouble() / TREND_DAYS
        val prev = valid.count { it.at >= since - TREND_DAYS * Fsrs.DAY_MS && it.at < since }
        val dir = if (prev <= 0) Dir.NA else Dir.of(prev.toDouble() / TREND_DAYS, perDay, eps = 0.3)
        return Feat(PACE, Dim(perDay, Conf.of(valid.size),
            "ReviewLog：最近 " + TREND_DAYS + " 天共 " + recent + " 条流水 ÷ " + TREND_DAYS + " 天"),
            dir, "对比前一周期（" + prev + " 条 → " + recent + " 条）")
    }

    /** 趋势窗口 */
    /**
     * 日均复习量的分母（天）。
     *
     * 用 30 天而不是 7 天：7 天窗口下「今天猛学 30 张」会算出 4.3 张/天，
     * 而用户真实的长期节奏可能只是 1 张/天。画像描述的是习惯，习惯要长窗口才看得见。
     */
    const val TREND_DAYS = 30

    /**
     * 连续天数。这里的 0 是**真值**（有流水但断了），
     * 必须和「一条流水都没有」的 UNKNOWN 分开。
     */
    fun streakDim(log: List<ReviewLog.R>, now: Long = System.currentTimeMillis()): Dim {
        val valid = log.count { it.at > 0L }
        if (valid <= 0) return Dim.missing("ReviewLog：还没有任何复习流水，连续天数无从谈起")
        val days = countStreak(log, now)
        return Dim(days.toDouble(), Conf.of(valid),
            "ReviewLog：按本地日期去重后，从今天（或昨天）往前数连续 " + days + " 天")
    }

    /**
     * 按本地日期去重后数「一直到今天」的连续段。
     *
     * 关键在 [now]：不传它就只能数「记录里最长的那段」，于是三个月前学过一天也会显示
     * 「连续 1 天」——那不是连续，那是断了。今天没学但昨天学了，仍算连着（用户还有救）；
     * 前天就是最后一天，则连续天数归 0。
     */
    fun countStreak(log: List<ReviewLog.R>, now: Long = System.currentTimeMillis()): Int {
        val days = log.filter { it.at > 0L }.map { ReviewLog.dateOf(it.at) }.toSortedSet().toList()
        if (days.isEmpty()) return 0
        // 最后一天必须落在今天或昨天，否则这一段早就断了
        val today = ReviewLog.dateOf(now)
        val last = days.last()
        if (last != today && last != prevDay(today)) return 0
        var n = 1
        var i = days.size - 1
        while (i > 0) {
            if (nextDay(days[i - 1]) == days[i]) { n++; i-- } else break
        }
        return n
    }

    /** "20261020" → 前一天 "20261019"。只用于连续天数判断 */
    fun prevDay(d: String): String = shiftDay(d, -1)

    /** "20261020" → 后一天 "20261021"。只用于连续天数判断 */
    fun nextDay(d: String): String = shiftDay(d, 1)

    /**
     * 日期串前后挪几天。
     *
     * 格式跟 [ReviewLog.dateOf] 走 **yyyyMMdd 八位无分隔**，不是 yyyy-MM-dd ——
     * 按 10 位去解析会一律返回空串，于是「连续天数」永远算出 0。
     * 这个错不会崩，只会静默给错数，正是最该用单测钉住的那类。
     */
    private fun shiftDay(d: String, delta: Int): String {
        if (d.length != 8 || !d.all { it.isDigit() }) return ""
        val y = d.substring(0, 4).toIntOrNull() ?: return ""
        val m = d.substring(4, 6).toIntOrNull() ?: return ""
        val day = d.substring(6, 8).toIntOrNull() ?: return ""
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear(); cal.set(y, m - 1, day)
        cal.add(java.util.Calendar.DAY_OF_MONTH, delta)
        return String.format(java.util.Locale.US, "%04d%02d%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** 最常学习的时段；没有流水时 UNKNOWN（不是 0 点） */
    fun bestHourDim(log: List<ReviewLog.R>): Dim {
        val valid = log.filter { it.at > 0L }
        if (valid.isEmpty()) return Dim.missing("ReviewLog：还没有复习流水，看不出你习惯什么时候学")
        val hist = IntArray(24)
        for (r in valid) hist[hourOf(r.at)]++
        val max = hist.maxOrNull() ?: 0
        if (max <= 0) return Dim.missing("ReviewLog：流水时间戳异常，算不出时段")
        val h = hist.indexOf(max)
        return Dim(h.toDouble(), Conf.of(valid.size),
            "ReviewLog：按小时统计 " + valid.size + " 条流水，" + h + " 点最多（" + max + " 次）")
    }

    fun hourOf(ms: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }    // ================= 专注侧 =================

    /**
     * 平均一次专注坚持多久。
     *
     * 只算「离开之后回来了」的段：没回来的那段还在进行中，
     * 现在算进去会让均值随采样时刻漂移。
     */
    fun focusAvgDim(samples: List<FocusReport.FocusSample>, now: Long): Dim {
        val valid = samples.filter { it.at > 0L }
        if (valid.isEmpty()) return Dim.missing("FocusReport：还没有专注采样（专注守护默认关闭）")
        val studyMs = valid.filter { it.study }
            .sumOf { s -> valid.filter { it.at > s.at }.minOfOrNull { it.at - s.at } ?: 0L }
            .coerceAtLeast(0L)
        // 少于 1 分钟不给数：这个量级下「专注时长」没有意义，只会误导
        if (studyMs < 60_000L) return Dim.missing("FocusReport：有采样但累计学习不足 1 分钟，算不出有意义的专注时长")
        val leaves = valid.count { it.play }
        val segs = (leaves + 1).coerceAtLeast(1)
        return Dim(studyMs.toDouble() / segs, Conf.of(valid.size, high = 60, medium = 15),
            "FocusReport：" + valid.size + " 次采样中累计学习 " + FocusGuard.fmtDur(studyMs) + " ÷ 约 " + segs + " 段")
    }

    /**
     * 每小时平均离开几次。
     *
     * 分母用**有采样的时长**而不是 24 小时：一天只学 20 分钟却离开 3 次，
     * 按 24 小时算是 0.125 次/时（正常），按 0.33 小时算是 9 次/时（才是真相）。
     */
    fun leavesPerHourDim(samples: List<FocusReport.FocusSample>, now: Long): Dim {
        val valid = samples.filter { it.at > 0L }
        if (valid.isEmpty()) return Dim.missing("FocusReport：还没有专注采样")
        val leaves = valid.count { it.play }
        if (leaves <= 0) return Dim(0.0, Conf.of(valid.size, high = 60, medium = 15),
            "FocusReport：" + valid.size + " 次采样里没有一次被判为「不像学习」")
        val span = (valid.maxOf { it.at } - valid.minOf { it.at }).coerceAtLeast(0L)
        val hours = (span.toDouble() / 3_600_000.0).coerceAtLeast(1.0)
        return Dim(leaves / hours, Conf.of(valid.size, high = 60, medium = 15),
            "FocusReport：" + leaves + " 次离开 ÷ " + String.format(java.util.Locale.US, "%.1f", hours) + " 小时采样跨度")
    }

    /** 最容易被拉走的时段；没有离开样本时 -1 */
    fun worstHourDim(samples: List<FocusReport.FocusSample>): Dim {
        val valid = samples.filter { it.at > 0L }
        if (valid.isEmpty()) return Dim.missing("FocusReport：没有采样，看不出你什么时候最容易分心")
        val hist = IntArray(24)
        for (s in valid) if (s.play) hist[hourOf(s.at)]++
        val max = hist.maxOrNull() ?: 0
        // 一次离开都没抓到 → UNKNOWN，不是 0。
        // 「没分心」和「没测到分心」是两件事：专注守护默认关闭，很多用户根本没有离开样本，
        // 给他们显示「0 点最容易分心」是编的。真要表达「没分心」，那是另一个维度的事。
        if (max <= 0) return Dim.missing(
            "FocusReport：" + valid.size + " 次采样中没有一次被判为「不像学习」，看不出分心时段")
        val h = hist.indexOf(max)
        return Dim(h.toDouble(), Conf.of(valid.size, high = 60, medium = 15),
            "FocusReport：" + max + " 次离开集中在 " + h + " 点前后")
    }

    // ================= 记忆侧 =================

    /** 预测的可提取性。没卡片就 UNKNOWN（不是 0 —— 0 会被读成「全忘了」） */
    fun retentionPredictedDim(cards: List<Fsrs.Card>, now: Long): Dim {
        if (cards.isEmpty()) return Dim.missing("LearningStats：还没有 FSRS 卡片，估不出可提取性")
        val r = runCatching { LearningStats.meanRetrievability(cards, now) }.getOrNull()
            ?: return Dim.missing("LearningStats：可提取性计算失败，不猜")
        return Dim(r, Conf.of(cards.size),
            "LearningStats：基于 " + cards.size + " 张有 FSRS 记录的卡片求平均")
    }

    /** 实测留存率。评分次数太少时置信度低 —— 一次评分算不出留存 */
    fun retentionActualDim(log: List<ReviewLog.R>): Dim {
        val valid = log.filter { it.at > 0L }
        if (valid.isEmpty()) return Dim.missing("ReviewLog：还没有任何评分，算不出实测留存（不是 0%）")
        val r = runCatching { LearningStats.recallRate(valid) }.getOrNull()
            ?: return Dim.missing("ReviewLog：评分数据不足以算出留存率")
        return Dim(r, Conf.of(valid.size, high = 30, medium = 8),
            "ReviewLog/ReviewLog：由 " + valid.size + " 次评分中「记住了」的比例算出")
    }

    // ================= 成本侧 =================

    /**
     * 本月花费（微元）。
     *
     * -1 表示「台账里根本没有这个月」→ UNKNOWN。
     * 花 0 元是有效信息，必须和「没开始记账」区分开。
     */
    fun monthDim(micro: Long): Dim {
        if (micro < 0L) return Dim.missing("CostLedger：台账里还没有任何记账，本月花费未知（不等于 0 元）")
        return Dim(micro.toDouble(), Conf.HIGH, "CostLedger：本月本地记账合计（微元）")
    }

    /**
     * 每知识点平均成本。
     *
     * 分母用「错题台账里出现过的不同知识点数」而不是卡片数：这张表要回答的是
     * 「为了搞懂一块知识，AI 花了多少钱」，知识点才是学习的最小单位。
     * 没识别出考点的记录归到未分类，但**仍然算进分母** —— 那也是一次真实的花钱。
     */
    fun costPerNodeDim(micro: Long, wrong: List<WrongLog.Q>, ledger: Map<String, CostLedger.Month>): Dim {
        if (ledger.isEmpty()) return Dim.missing("CostLedger：还没有任何记账，算不出单知识点成本")
        val nodes = wrong.map { it.knowledge.trim().ifEmpty { WeakPoints.UNKNOWN } }.distinct().size
        if (nodes <= 0) return Dim.missing("WrongLog：还没有带知识点的答题记录，没有分摊的对象")
        if (micro < 0L) return Dim.missing("CostLedger：台账读数异常（负数），不展示")
        return Dim(micro.toDouble() / nodes, Conf.of(nodes, high = 5, medium = 2),
            "CostLedger 本月 " + micro + " 微元 ÷ WrongLog 里 " + nodes + " 个不同知识点")
    }
}
