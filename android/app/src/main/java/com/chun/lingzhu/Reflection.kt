package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2.3.1 画像反思：把 [LearnerTwin] 算出来的快照交给模型，换回一段「我该改什么」的话。
 *
 * ## 为什么反思必须带 source 标签
 * 这个功能唯一的失败模式不是「说错话」，而是「说了话但用户不知道是谁说的」。
 * 云端模型给的建议和本地规则给的建议，可信度、时效性、能不能复现都不一样：
 *   - CLOUD：模型读过具体数字，能指出维度间的矛盾，但可能顺着用户想听的编；
 *   - LOCAL：只是 [StudyCoach] 的调度结论换个说法，绝对保守，但说不出矛盾。
 * 不标来源，用户就没法判断这段话该信几分；而一旦某次云端超时静默降级成离线，
 * 用户会以为「模型真的这么看我的数据」—— 那是最坏的情况。所以 [Advice.from] 是必填字段，
 * 离线一律 LOCAL，没有任何「看起来像云端」的中间态。
 *
 * ## 为什么 prompt 里反复强调「数据不足就说数据不足」
 * 模型的默认行为是把给定的数字讲圆。快照里 UNKNOWN 的维度在 JSON 里是 null，
 * 模型很容易把它读成 0 然后讲出一句「你的保持率是 0%，建议加强复习」——
 * 这是把我们最想避免的假象重新引进来。所以 prompt 明确列出哪些维度为空，
 * 并要求逐条说明「数据不足」，不许补一个数。
 */
object Reflection {

    /** 建议来源。必须显式标注，见文件头 */
    enum class Source(val label: String) {
        CLOUD("云端分析"),
        LOCAL("本地规则"),
    }

    /** 一条反思结果 */
    data class Advice(
        /** 一段总述。不允许为空 —— 空字符串等于什么都没说 */
        val summary: String,
        /** 3 条可执行建议 */
        val actions: List<String>,
        /** 维度间的矛盾（例：成本在涨但保持率没动）。可以为空，但不能是编的 */
        val conflicts: List<String>,
        /** 明确列出「哪些维度数据不足」 */
        val insufficient: List<String>,
        val source: Source,
        /** 生成时刻 */
        val at: Long,
        /** 降级原因。source=LOCAL 且有值时必须让用户看到，否则等于隐瞒 */
        val degradedReason: String? = null,
    ) {
        val isDegraded: Boolean get() = source == Source.LOCAL
    }

    /**
     * 把快照压成给模型看的 JSON。
     *
     * 只放「已经算出来的结论」，不放原始记录：把几百条评分流水塞进 prompt，
     * 既贵又会让模型自己重新算一遍，算出跟本地不一样的口径 —— 那就不是反思而是第二套账。
     *
     * UNKNOWN 的维度写成 null 而不是省略：缺失也是一种信息，模型需要知道「这里没有数」，
     * 而不是「这里没有这一项」。
     */
    fun snapshot(t: LearnerTwin.Twin): JSONObject {
        val dims = JSONObject()
        // 掌握度是「多知识点平均」，Twin 里没有暴露单个 Dim，这里按 dims 里那一条取
        dims.put(LearnerTwin.MASTERY, dim(t.dims.first { it.name == LearnerTwin.MASTERY }.dim))
        dims.put(LearnerTwin.PACE, dim(t.pacePerDay))
        dims.put(LearnerTwin.FOCUS, dim(t.focusAvgMs))
        dims.put(LearnerTwin.RETENTION, dim(t.retentionActual))
        dims.put(LearnerTwin.COST, dim(t.costPerNode))

        val trend = JSONObject()
        for ((k, v) in t.trend) trend.put(k, v.cn)

        val gaps = JSONArray()
        for (g in t.gaps) {
            gaps.put(JSONObject().put("维度", g.dim).put("原因", g.why).put("还差", g.need))
        }

        val nodes = JSONArray()
        // masteryTop 是 Pair<知识点名, 掌握度>，没有答题数/错题数（那是 WeakPoints.KP 才有的）
        for (n in t.masteryTop) {
            nodes.put(JSONObject()
                .put("知识点", n.first)
                .put("掌握度", n.second))
        }

        return JSONObject()
            .put("维度", dims)
            .put("趋势", trend)
            .put("数据缺口", gaps)
            .put("薄弱知识点", nodes)
            .put("连续天数", t.streak.v ?: JSONObject.NULL)
            .put("时段偏好小时", if (t.bestHour < 0) JSONObject.NULL else t.bestHour)
            .put("最易分心小时", t.worstHour.v ?: JSONObject.NULL)
            .put("本月成本微元", t.monthMicro.v ?: JSONObject.NULL)
            .put("环比预测保持率", t.retentionPredicted.v ?: JSONObject.NULL)
    }

    /** 一个维度 → {值, 可信度, 来源}。值缺失时给 null 并带上原因，让模型看得见「这里没有」 */
    private fun dim(d: LearnerTwin.Dim): JSONObject = JSONObject()
        .put("值", d.v ?: JSONObject.NULL)
        .put("可信度", d.conf.label)
        .put("来源", d.src)

    /**
     * 反思 prompt。三句话对应三个硬要求，缺一条这段反思就没有价值：
     *
     * 1. **只说数据支持的**：维度是 null 就必须明确说「数据不足」，绝不补一个数。
     *    模型看到 null 会本能地当成 0，这是这类功能最常见的翻车点。
     * 2. **指出矛盾**：单一维度的好话没有信息量，矛盾才有。例如「成本在涨、保持率没动」
     *    说明钱花在了不产生记忆的地方；「复习量在涨、掌握度没变」说明在低效重复。
     * 3. **三条可执行建议**：必须是下周就能做的具体动作（几点做什么、做多少），
     *    明令禁止「多复习」「保持专注」这类无法执行的话 —— 那种建议等于没建议。
     */
    fun prompt(t: LearnerTwin.Twin): String {
        val sb = StringBuilder()
        sb.append("下面是这位学习者本机统计出来的画像数据（JSON）。请只基于这些数据做复盘。\n\n")
        sb.append(snapshot(t).toString(2)).append("\n\n")
        sb.append("严格按以下规则回答：\n")
        sb.append("1. 只使用上面给出的数字。某个维度的「值」是 null 时，必须明确写「数据不足」，")
        sb.append("   绝对不能把它当成 0，也不要猜测。\n")
        sb.append("2. 指出维度之间的矛盾（例如成本上升但保持率没提升、复习量增加但掌握度不变），")
        sb.append("   并说明这个矛盾意味着什么。没有矛盾就直说没有矛盾，不要硬凑。\n")
        sb.append("3. 给出 3 条下周就能执行的具体建议，写清做什么、做多少、什么时间做。\n")
        sb.append("   禁止出现「多复习」「保持专注」「继续加油」这类无法执行的话。\n")
        sb.append("4. 不要复述数据，不要说客套话，直接给结论。\n\n")
        sb.append("按这个 JSON 结构输出，不要输出别的任何内容：\n")
        sb.append("{\"summary\":\"一段总述\",\"actions\":[\"建议1\",\"建议2\",\"建议3\"],")
        sb.append("\"conflicts\":[\"矛盾1\"],\"insufficient\":[\"哪个维度数据不足\"]}")
        return sb.toString()
    }

    /**
     * 解析模型返回的 JSON。
     *
     * 模型经常在 JSON 外面裹一层 ```json 或者一段解释，所以先粗暴地截取第一个 { 到最后一个 }。
     * 解析失败返回 null —— 由调用方降级到 [local]，而不是抛给界面。
     *
     * 建议不补到 3 条：模型只给 2 条就展示 2 条。硬补一条「多复习」进来，
     * 等于用假建议填空，违背这个模块的全部初衷。
     */
    fun parse(raw: String?, now: Long): Advice? {
        if (raw.isNullOrBlank()) return null
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val o = JSONObject(raw.substring(s, e + 1))
            val summary = o.optString("summary").trim()
            if (summary.isEmpty()) return null
            Advice(
                summary = summary,
                actions = strList(o.optJSONArray("actions")),
                conflicts = strList(o.optJSONArray("conflicts")),
                insufficient = strList(o.optJSONArray("insufficient")),
                source = Source.CLOUD,
                at = now,
            )
        } catch (ex: Exception) {
            null
        }
    }

    private fun strList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val v = a.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }


    /**
     * 离线降级：把 [StudyCoach] 的调度结论翻译成人话。
     *
     * 为什么降级不用模板编一段像模像样的复盘：降级时我们手上只有调度队列，
     * 没有任何跨维度的判断依据。这时候生成「你的专注质量在下降」这种句子，
     * 就是纯粹的编造 —— 而这正是我们在 [LearnerTwin] 里花全部力气避免的事。
     * 所以降级只做一件事：如实说明「现在给不出画像解读，但今天该干什么我算得出来」，
     * 并把 StudyCoach 的真实结论摆出来。
     *
     * @param reason 为什么降级（没配 API / 网络失败 / 解析失败）。必须展示给用户
     */
    fun local(advice: StudyCoach.Advice, twin: LearnerTwin.Twin, reason: String, now: Long): Advice {
        val actions = ArrayList<String>()
        // StudyCoach 给的都是可执行动作（今天复习几张、新学几张），正好满足「具体」这一条
        if (advice.todayReview > 0) actions.add("今天先把到期的 " + advice.todayReview + " 张复习完")
        if (advice.backlog > 0) actions.add("有 " + advice.backlog + " 张已逾期，先清逾期再学新卡")
        if (advice.newDue > 0) actions.add("今天可以新学 " + advice.newPerDay + " 张，池里还有 " + advice.newDue + " 张待学")
        if (actions.isEmpty()) actions.add("今天没有到期任务，可以只做一次 10 分钟回顾")

        val insufficient = twin.gaps.map { it.dim }
        return Advice(
            summary = "这次只用本地规则给结论：" + reason + "。" + StudyCoach.summary(advice),
            actions = actions,
            // 降级时我们确实没有能力做跨维度判断，如实留空，不编矛盾
            conflicts = emptyList(),
            insufficient = insufficient,
            source = Source.LOCAL,
            at = now,
            degradedReason = reason,
        )
    }

    /**
     * 落盘。只留最近 [KEEP] 条：反思是「这段时间我怎么学」的对照物，
     * 留太多反而看不出变化，而且每条都是完整一段话，体积不小。
     */
    class Store(context: android.content.Context) {
        private val file = java.io.File(context.filesDir, FILE)

        fun load(): List<Advice> {
            if (!file.exists()) return emptyList()
            return try {
                val a = JSONArray(file.readText())
                (0 until a.length()).mapNotNull { i -> from(a.optJSONObject(i)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun save(list: List<Advice>) {
            val a = JSONArray()
            for (x in list.take(KEEP)) a.put(to(x))
            try {
                file.writeText(a.toString())
            } catch (e: Exception) {
                // 写盘失败不该影响「已经算出来的这段反思」，用户至少还能看到内容
            }
        }

        /** 追加一条并返回新的完整历史（新的在前） */
        fun append(x: Advice): List<Advice> {
            val list = ArrayList<Advice>()
            list.add(x)
            list.addAll(load())
            val out = list.take(KEEP)
            save(out)
            return out
        }
    }

    /** 落盘文件名 */
    const val FILE = "reflection.json"

    /** 留最近多少条 */
    const val KEEP = 20

    // ---------------- 云端入口 ----------------

    /**
     * 跑一次反思。**这个函数永远不抛异常，也永远不会假装云端成功**。
     *
     * 三种结局各自有明确标签：
     *   - 云端拿到可用结果 → source=CLOUD；
     *   - 没配 API / 网络失败 / 模型输出无法解析 → source=LOCAL，且 degradedReason 写明原因；
     *   - 云端说了一段话但解析不出结构 → 同样 LOCAL（宁可不用，也不把半截话当结论）。
     *
     * @param cfg       当前 API 配置；null 表示没配
     * @param coach     StudyCoach 的调度结论，降级时用它
     * @param record    是否记到 [CostLedger]（用 [CostLedger.Feature.REFLECT]）。
     *                  **不记账就等于白送**，而这个功能的成本最容易失控（每次都发整份画像），
     *                  所以默认开启，只在测试里关掉。
     */
    fun run(
        cfg: ApiConfig?,
        twin: LearnerTwin.Twin,
        coach: StudyCoach.Advice,
        now: Long,
        maxTokens: Int = 1200,
        /** 成本台账。真实调用必传；null 表示不记（单测场景） */
        ledger: CostLedger.Store? = null,
        record: Boolean = true,
    ): Advice {
        if (cfg == null || cfg.baseUrl.isBlank() || cfg.apiKey.isBlank()) {
            return local(coach, twin, "未配置模型接口", now)
        }
        val promptText = prompt(twin)
        return try {
            val body = Bodies.text(cfg, promptText, maxTokens, thinkingOff = false, stream = false)
            val text = StringBuilder()
            ApiClient(cfg).stream(body, object : ApiClient.Listener {
                override fun onText(text2: String) { text.append(text2) }
            })
            val raw = text.toString()
            val parsed = parse(raw, now)
            // 只有真的调了模型才记账：降级没花钱，记上去就是虚账
            if (record) bill(ledger, cfg, promptText.length, raw.length)
            parsed ?: local(coach, twin, "模型返回的内容无法解析成结论", now)
        } catch (e: Exception) {
            // 异常分支也可能已经产生了 token（流到一半断），但拿不到用量，保守不记 ——
            // 宁少记一笔，也不要按估数写进台账，账目必须可核对。
            // 任何异常都降级，并且把真实原因带给用户：静默降级比报错更伤人
            local(coach, twin, NetErrors.friendly(e, cfg.baseUrl), now)
        }
    }

    /**
     * 记一笔反思成本。用微元，和 [CostLedger] 同口径。
     *
     * [ledger] 为 null 表示调用方没给台账（单测 / 纯函数场景），此时不记 ——
     * 但真实调用路径必须传，否则这一次反思的钱就没进账。
     */
    private fun bill(ledger: CostLedger.Store?, cfg: ApiConfig, promptChars: Int, answerChars: Int) {
        if (ledger == null) return
        runCatching {
            val date = ReviewLog.dateOf(System.currentTimeMillis())
            val pin = CostLedger.estimatePin(promptChars)
            val pout = CostLedger.estimatePout(answerChars)
            ledger.record(date, model = cfg.model, f = CostLedger.Feature.REFLECT, pin = pin, pout = pout)
        }
    }

    fun to(x: Advice): JSONObject = JSONObject()
        .put("summary", x.summary)
        .put("actions", JSONArray(x.actions))
        .put("conflicts", JSONArray(x.conflicts))
        .put("insufficient", JSONArray(x.insufficient))
        .put("source", x.source.name)
        .put("at", x.at)
        .apply { if (x.degradedReason != null) put("degradedReason", x.degradedReason) }

    fun from(o: JSONObject?): Advice? {
        if (o == null) return null
        val summary = o.optString("summary")
        if (summary.isEmpty()) return null
        val at = o.optLong("at", 0L)
        if (at <= 0L) return null
        return Advice(
            summary = summary,
            actions = strList(o.optJSONArray("actions")),
            conflicts = strList(o.optJSONArray("conflicts")),
            insufficient = strList(o.optJSONArray("insufficient")),
            // 读盘时来源认不出就退回 LOCAL：宁可低估可信度，也不能把本地结果伪装成云端
            source = if (o.optString("source") == Source.CLOUD.name) Source.CLOUD else Source.LOCAL,
            at = at,
            degradedReason = o.optString("degradedReason").ifEmpty { null },
        )
    }
}
