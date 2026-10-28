package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具执行所需的运行环境。[AgentTools.execute] 的每个 executor 都拿它。
 *
 * 为什么要包一层 ToolContext 而不是直接传 Context + pid：
 * AgentTools 的执行层可以注入环境，测试里给一个假实现就能验参数校验与失败话术，
 * 不必为了跑一条参数检查去起 Robolectric。
 *
 * [pid] 是人格 id：长期记忆按人格分文件（AgentMemory.Store），生活管家和学习教练不能共用一摊。
 */
interface ToolContext {
    val app: Context
    val pid: String
}

/** 真实运行时用的实现。上下文传 applicationContext，避免把页面 leak 进后台线程 */
class AppToolContext(override val app: Context, override val pid: String) : ToolContext

/**
 * 一个工具的定义。
 *
 * [label] 是给界面看的执行中文案（「正在建知识卡…」）。单独放在 ToolSpec 上而不是让 executor 自己回一句，
 * 是因为 UI 需要在**执行之前**就有话说：建卡要读一遍全库、算用量要读整个台账，
 * 真跑起来都要几十到上百毫秒，没有这句用户看到的就是转圈。
 *
 * [executor] 约定：**永远不抛异常**，失败也要回一句「（…失败：原因）」。
 * 理由见 [AgentTools.execute] 的注释 —— 工具结果要回填给模型当 tool 消息，
 * 抛出去等于整轮对话断在中途，模型连「工具坏了，我直接答」的机会都没有。
 */
data class ToolSpec(
    val name: String,
    val label: String,
    val description: String,
    val paramsJson: String,
    val executor: (ToolContext, JSONObject) -> String,
)

/**
 * 模型可调用的本机工具注册表。
 *
 * 和 WebSearch 的关系：那套是「一个开关打开才有」的联网工具，这里是**常驻**的本机能力 ——
 * 用户问「帮我记一张卡」「明天早上八点提醒我」时，模型该能直接落地，
 * 而不是只回一句「好的我记住了」（其实什么都没记）。
 *
 * 三条设计约束：
 *  1. **协议层与实现层分开**：toolsJson 只产 OpenAI tools 的 JSON，不碰 Context，纯 JVM 可测；
 *     execute 才需要 ToolContext。ApiClient 拼请求体和执行工具分别用这两处。
 *  2. **每个工具都容错**：参数缺了用默认值、类型不对当没给、名字不存在就回一份可用清单。
 *     模型给的 arguments 是它自由生成的一段 JSON 文本，永远会有意外，不能靠「它应该守规矩」。
 *  3. **结果是一句给模型看的话**，不是 JSON。模型读一句「已建卡《什么是导数》（牌组：默认）」，
 *     下一轮就能自然地告诉用户建好了；给它一段 JSON 反而容易让它复述字段名。
 */
object AgentTools {

    // ---------------- 协议层 ----------------

    /**
     * 全部可用工具。顺序就是 toolsJson 里给模型的顺序。
     *
     * 为什么按「类在不在」而不是写死六条：weak_points / study_plan 依赖 task-2 / task-3 的
     * WeakPoints 与 StudyCoach。如果先挂上去而类还没落地，模型看到 tools 里有就会去调，
     * 每次都拿到「执行失败」，用户感知就是「这 AI 老是说办不到」。少挂一个远比挂个坏的强。
     */
    fun registry(): List<ToolSpec> = listOf(
        knowledgeCard(),
        reminder(),
        searchMemory(),
        costReport(),
        weakPoints(),
        studyPlan(),
    )

    /** 工具名集合，给 hasTool 与「没有这个工具」的提示共用，避免两处各写一份名字漂移 */
    fun names(): List<String> = registry().map { it.name }

    /**
     * v2.3.3「允许自定义 Agent」：每个 Agent 可以只开部分工具。
     *
     * [allowed] 是人格存下来的工具名集合。**空集合 = 全开**（沿用老行为：老用户升级后一个工具都不少），
     * 只有用户显式关掉某些工具时才会有非空集合。
     *
     * 这个名字故意不是 `filter` 之类的：它同时被 toolsJson（发什么）和 execute（收什么）用到，
     * 两边共用一个判断才不会出现「请求里没这个工具，但模型硬调却又能执行」的口子。
     */
    fun allows(allowed: Set<String>, name: String): Boolean =
        allowed.isEmpty() || name in allowed

    /**
     * 这个工具所属的功能**模块**是不是被用户关掉了（v2.4 模块门控）。
     *
     * [disabled] 是用户关掉的功能 id 集合（[Prefs.disabledFeatures] 的快照）。判定交给
     * [Features.isEnabled] → [FeatureToggles.isOn]：核心模块（[FeatureToggles.PINNED]）永远算开着，
     * 所以哪怕坏配置里存了 study 也不会把建卡 / 排计划的工具一起掐掉。
     *
     * 归属查不到的工具（还没登记成功能包的）**不受模块开关约束** —— 拿不到信息时不要藏东西。
     */
    fun moduleOff(disabled: Set<String>, name: String): Boolean {
        val owner = Features.ofTool(name) ?: return false
        return !Features.isEnabled(disabled, owner.id)
    }

    /**
     * Agent 白名单 + 模块开关都放行才算能用。
     *
     * 给只需要一个布尔值的调用方（比如「要不要先弹这句进展文案」）：
     * 两个开关分别判断的话，每个调用点都得自己记得与一次，漏一处就是文案说在建卡、实际什么也没发生。
     */
    fun enabled(allowed: Set<String>, disabled: Set<String>, name: String): Boolean =
        allows(allowed, name) && !moduleOff(disabled, name)

    /**
     * 按白名单与模块开关过滤后的工具列表。顺序与 [registry] 一致（模型看到的工具顺序稳定，命中缓存）。
     *
     * 两个开关是**与**关系：Agent 自己开了某个工具，但它属于用户关掉的模块，仍然不给。
     */
    fun registryFor(allowed: Set<String>, disabled: Set<String> = emptySet()): List<ToolSpec> =
        registry().filter { allows(allowed, it.name) && !moduleOff(disabled, it.name) }

    /**
     * OpenAI tools 数组（字符串形式）。Bodies.tools 把这个和 WebSearch.toolsJson 拼在一起。
     * registry() 每次现算：工具表极小，缓存反而会因为漏失效出现「改了工具描述不生效」。
     *
     * [disabled] 与 [registryFor] 同一口径 —— 请求里不发的工具，execute 也不会执行。
     */
    fun toolsJson(allowed: Set<String> = emptySet(), disabled: Set<String> = emptySet()): String = JSONArray().apply {
        for (spec in registryFor(allowed, disabled)) {
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", spec.name)
                .put("description", spec.description)
                .put("parameters", runCatching { JSONObject(spec.paramsJson) }.getOrElse {
                    // 参数表写坏了也要退化成「无参工具」，而不是让整轮请求体崩掉
                    JSONObject().put("type", "object").put("properties", JSONObject())
                })))
        }
    }.toString()

    fun hasTool(name: String): Boolean = registry().any { it.name == name }

    /** 某个工具的执行中提示语；名字不认识返回空串（调用方据此不显示假进展） */
    fun labelOf(name: String): String =
        registry().firstOrNull { it.name == name }?.label.orEmpty()

    /**
     * 模型给了一个不存在的工具名时回填的话术。
     *
     * 老版本只会说「没有这个工具，可用：web_search / web_fetch」—— 用户没开搜索时这句话等于告诉模型
     * 「工具全都不存在」，它就只能放弃动手改口说「我记不下来」。这里把**当前真正挂着的**工具全列出来，
     * 模型下一轮就能改调对的那个。
     */
    fun unknownToolText(name: String, disabled: Set<String> = emptySet()): String {
        val all = registryFor(AgentScope.ALL, disabled).map { it.name }
        val pretty = cleanName(name, 40)
        if (all.isEmpty()) return "（没有名为「" + pretty + "」的工具，本轮也没有任何工具可用，请直接用你自己的知识回答。）"
        return "（没有名为「" + pretty + "」的工具。本轮可用的工具只有：" + all.joinToString("、") +
            "。请改用其中一个，或直接用你自己的知识回答。）"
    }

    // ---------------- 执行层 ----------------

    /**
     * 执行一次工具调用，返回要回填给模型的自然语言结果。
     *
     * **绝不抛异常**是这一层的硬约束：返回值会被直接塞进 tool 角色的消息里，
     * 一旦抛出，ApiClient 的工具循环就整轮失败，用户看到的是「出错」而不是「这个工具没办成」。
     * 模型其实很会兜底（「我没办法直接建卡，但卡内容我写给你」），前提是它得知道失败了。
     *
     * @param argsJson 模型给的 arguments 原文：可能是空串、不是合法 JSON、不是对象 —— 全部容忍。
     */
    fun execute(
        ctx: ToolContext,
        name: String,
        argsJson: String,
        allowed: Set<String> = emptySet(),
        disabled: Set<String> = emptySet(),
    ): String {
        // 白名单也要管执行：请求里没发的工具，模型仍可能凭上下文硬调 ——
        // 只在 toolsJson 过滤、execute 不过滤，等于「藏起来但还能用」，用户关掉的工具照样生效。
        if (!allows(allowed, name)) {
            return "（当前 Agent 没有开启「" + cleanName(name, 40) + "」这个工具。请直接用你自己的话回答。）"
        }
        // 模块门控同理：关掉的模块，工具连执行也不给。话术和「没这个工具」分开 ——
        // 「你没开这个工具」会让用户去 Agent 设置里翻，而真正的原因在「模块管理」那一页。
        if (moduleOff(disabled, name)) {
            val owner = Features.ofTool(name)
            return "（「" + cleanName(name, 40) + "」属于已关闭的模块「" + (owner?.title ?: "") +
                "」，现在不能执行。可以请用户去设置页的「模块管理」里打开它。）"
        }
        val spec = registry().firstOrNull { it.name == name } ?: return unknownToolText(name, disabled)
        val args = parseArgs(argsJson)
        return try {
            spec.executor(ctx, args).ifBlank {
                "（未执行「" + spec.name + "」：工具返回了空结果，请换个方式回答。）"
            }
        } catch (e: Throwable) {
            // 连 Error 都兜：工具里跑的是文件 IO 与 JSON 解析，除了内存耗尽没有值得往上抛的
            "（" + spec.name + " 执行失败：" + cleanName(e.message ?: e.javaClass.simpleName, 120) +
                "。请不要反复重试，直接用你自己的话回答。）"
        }
    }

    /** 只要 arguments 的解析结果；给上层做「要不要先显示进展」这类判断时复用，不重复解析 */
    fun parseArgs(argsJson: String): JSONObject {
        val t = argsJson.trim()
        if (t.isEmpty()) return JSONObject()
        return runCatching { JSONObject(t) }.getOrElse { JSONObject() }
    }

    // ---------------- 本轮可完全自洽的四个工具 ----------------

    /**
     * 建一张问答知识卡。
     *
     * 为什么需要：聊天里最有价值的那句话（「原来导数就是瞬时变化率」）现在只能留在聊天记录里，
     * 过两周就翻不到了。建卡是把它放进 FSRS 复习队列，才算真的「学到了」。
     * 卡面用 Source.MANUAL —— 这张卡不是从截图识别来的，是用户聊天时主动想记的，
     * 以后按来源筛卡时才能把它和识别卡分开。
     */
    private fun knowledgeCard(): ToolSpec = ToolSpec(
        name = NAME_ADD_CARD,
        label = "正在建知识卡…",
        description = "把一条值得长期记住的知识建成复习卡片，之后会按遗忘曲线提醒复习。" +
            "只在用户明确要求「记下来 / 建成卡片 / 帮我记住」，或对话里出现了值得留存的确定知识点时用；" +
            "寒暄与临时语境不要建卡。正面写一个问得出来的问题，反面写答案（自己看得懂即可，不要复述问题）。",
        paramsJson = "{\"type\":\"object\",\"properties\":{" +
            "\"front\":{\"type\":\"string\",\"description\":\"卡片正面：一个能追问自己的问题，例如「导数的几何意义是什么」\"}," +
            "\"back\":{\"type\":\"string\",\"description\":\"卡片反面：答案，不超过 " + KnowledgeCard.BACK_MAX + " 字\"}," +
            "\"deck\":{\"type\":\"string\",\"description\":\"牌组名，一般填科目名；不填进「默认」牌组\"}" +
            "},\"required\":[\"front\",\"back\"]}",
        executor = { ctx, a ->
            val front = str(a, "front")
            val back = str(a, "back")
            val deck = str(a, "deck").ifBlank { DEFAULT_DECK }
            when {
                front.isBlank() -> "（建卡失败：正面不能为空。请给出一句要复习的问题再建一次。）"
                back.isBlank() -> "（建卡失败：反面不能为空。复习时看到问题却没有答案，这张卡没有意义。）"
                else -> {
                    val id = KnowledgeCard.Store(ctx.app).add(
                        front, back, KnowledgeCard.Source.MANUAL, System.currentTimeMillis(), deck,
                    )
                    // add 返回 -1：同牌组里已经有正面一样的卡，或者库到上限了。
                    // 对用户来说都是「不用再建」，但话术必须分开 —— 否则用户会以为工具坏了。
                    if (id > 0) "已建卡《" + clip(front, 30) + "》（牌组：" + deck + "）。"
                    else duplicateOrFull(front, deck, ctx)
                }
            }
        },
    )

    /**
     * add() 回 -1 时区分「重复」和「库满」。这两件事对用户的意义完全不同：
     * 重复是好消息（你已经记过了），库满要用户自己动手清理（卡不是自动能删的）。
     */
    private fun duplicateOrFull(front: String, deck: String, ctx: ToolContext): String = try {
        val list = KnowledgeCard.Store(ctx.app).list()
        when {
            list.size >= KnowledgeCard.MAX ->
                "（建卡失败：知识卡库已满（上限 " + KnowledgeCard.MAX + " 张），先去知识卡页删掉一些旧卡。）"
            list.any { it.deck == deck && it.front == front } ->
                "（没有新建：牌组「" + deck + "」里已经有一张《" + clip(front, 30) + "」，内容一样的卡不重复建。）"
            else -> "（建卡失败：写库没有成功，可能是存储空间不足。）"
        }
    } catch (e: Exception) {
        "（建卡失败：" + cleanName(e.message ?: "写库异常", 100) + "）"
    }

    /**
     * 建一条到点会响的提醒。
     *
     * 照抄 ChatActivity 里 rememberPlan 的做法：Todo.Store.add **只落盘、不排闹钟**，
     * 必须自己补一次 TodoReceiver.schedule，否则条目躺在列表里永远不会响（老版本踩过这个坑）。
     * due_at 由模型算好绝对毫秒时间给过来 —— 让本工具解析「明天早上八点」等于在 App 里再造一个
     * NL 时间解析器，而模型本来就擅长这个。
     */
    private fun reminder(): ToolSpec = ToolSpec(
        name = NAME_ADD_REMINDER,
        label = "正在建提醒…",
        description = "建一条到点会响的待办提醒。用户说「提醒我 / X 点叫我 / 别忘了」时用。" +
            "due_at 必须是绝对毫秒时间戳（按用户说的日期时间自己换算好再传），不要传「明天」这类相对描述。",
        paramsJson = "{\"type\":\"object\",\"properties\":{" +
            "\"text\":{\"type\":\"string\",\"description\":\"提醒内容，一句话，例如「八点交作业」\"}," +
            "\"due_at\":{\"type\":\"integer\",\"description\":\"到点提醒的绝对时间，Unix 毫秒时间戳\"}," +
            "\"note\":{\"type\":\"string\",\"description\":\"补充备注，可以不填\"}" +
            "},\"required\":[\"text\",\"due_at\"]}",
        executor = { ctx, a ->
            val text = str(a, "text")
            val note = str(a, "note")
            val due = num(a, "due_at")
            val now = System.currentTimeMillis()
            when {
                text.isBlank() -> "（建提醒失败：提醒内容不能为空。）"
                due <= 0L -> "（建提醒失败：没拿到有效的到点时间，due_at 应为毫秒时间戳。请算好具体时间再建一次。）"
                // 已经过去的时间建出来就是一条永远不会响的待办，用户以为设好了其实没有。
                // 给 60 秒容差：模型换算偶尔差一两毫秒，不值得为这个报错。
                due < now - 60_000L -> "（建提醒失败：给的时间已经过去了（" + fmtTime(due) + "）。请重新确认时间。）"
                else -> {
                    val id = Todo.Store(ctx.app).add(text, due, note)
                    if (id <= 0L) {
                        "（建提醒失败：待办列表已满（上限 " + Todo.MAX + " 条）或这条内容重复，请先去待办页清理。）"
                    } else {
                        TodoReceiver.schedule(ctx.app, id)
                        "已建提醒「" + clip(text, 40) + "」，" + fmtTime(due) + " 提醒。"
                    }
                }
            }
        },
    )

    /**
     * 查长期记忆。
     *
     * 和 ChatActivity 发消息前那段检索同一个口径（AgentMemory.top），区别是这里是**模型主动翻**：
     * 用户问「我以前跟你说过什么」时，聊天前缀里那几条未必够，模型需要能换个关键词再搜一次。
     * 命中后把用量计数写回库（top 返回的 bumped），和聊天路径一致 ——
     * 否则「被工具搜中」的记忆永远不涨权重，越问越显得它不重要。
     */
    private fun searchMemory(): ToolSpec = ToolSpec(
        name = NAME_SEARCH_MEMORY,
        label = "正在翻长期记忆…",
        description = "在本机长期记忆里检索。用户问「你还记得…」「我以前说过…」，" +
            "或需要了解他的偏好、习惯、关系、既往经历时用；" +
            "问的是知识而不是关于用户本人的事时不要用这个工具。",
        paramsJson = "{\"type\":\"object\",\"properties\":{" +
            "\"query\":{\"type\":\"string\",\"description\":\"检索词，用名词短语，例如「猫的名字」「考研专业」\"}" +
            "},\"required\":[\"query\"]}",
        executor = { ctx, a ->
            val q = str(a, "query")
            if (q.isBlank()) {
                "（查记忆失败：检索词不能为空。请给一个具体的关键词再查。）"
            } else {
                val store = AgentMemory.Store(ctx.app, ctx.pid)
                val mems = store.get()
                if (mems.isEmpty()) {
                    "（这个人格的长期记忆还是空的，没有可查的内容。可以直接按常识回答，或先和用户聊聊相关的事。）"
                } else {
                    val (hits, bumped) = AgentMemory.top(q, mems, System.currentTimeMillis())
                    if (hits.isEmpty()) {
                        "（记忆库里有 " + mems.size + " 条，但没有和「" + clip(q, 20) +
                            "」沾边的。可以换个说法再查，或直接回答。）"
                    } else {
                        store.put(bumped)
                        AgentMemory.contextBlock(hits)
                    }
                }
            }
        },
    )

    /**
     * 花钱报告。直接复用 CostLedger.summary —— 设置页显示的是同一段话，
     * 用户在这里看到的数字必须和设置页一模一样，不能各算一份（那种差异很难再被信任）。
     */
    private fun costReport(): ToolSpec = ToolSpec(
        name = NAME_COST_REPORT,
        label = "正在算用量…",
        description = "报告本机记录的真实接口花费（今天 / 本周 / 本月 / 缓存省下多少）。" +
            "只在用户问「花了多少钱 / 用量多少」时用，其他时候不要主动报账。",
        paramsJson = "{\"type\":\"object\",\"properties\":{}}",
        executor = { ctx, _ ->
            val date = todayStr()
            val ledger = CostLedger.Store(ctx.app).load()
            CostLedger.summary(ledger, date, CostLedger.weekDates(date))
        },
    )

    // ---------------- 学习画像与计划（task-2 / task-3）----------------

    /**
     * 看错题台账的薄弱点画像。
     *
     * 直接返回 [WeakPoints.text]：那段摘要就是**为模型写的**——先总量与趋势，再薄弱榜，
     * 最后其余知识点。在这儿再套一层自己的措辞只会稀释信息，模型还要多读一遍。
     *
     * description 里特意写「用户问自己哪里薄弱时才用」：不然模型逢题就顺手调一次，
     * 每轮都往上下文里塞一段几百字的台账，既费钱又跑题。
     */
    private fun weakPoints(): ToolSpec = ToolSpec(
        name = NAME_WEAK_POINTS,
        label = "正在看错题台账…",
        description = "统计错题台账，说出用户哪些知识点最薄弱、正确率趋势如何。" +
            "只在用户问「我哪里弱 / 哪些知识点不行 / 复习得怎么样」时用；" +
            "答一道具体的题不需要它。台账是空的就如实说还没记录。",
        paramsJson = "{\"type\":\"object\",\"properties\":{}}",
        executor = { ctx, _ ->
            val log = WrongLog.Store(ctx.app).list()
            WeakPoints.text(WeakPoints.build(log, System.currentTimeMillis()))
        },
    )

    /**
     * 今天该复习多少、先补哪个牌组。
     *
     * 三个输入缺一不可：[KnowledgeCard.Store.list] 的知识卡、[WordSrs.all] 的词卡 FSRS 状态、
     * [Prefs.studyPlan] 里用户自己设的额度与牌组范围。少任何一样算出来的数字都会和
     * [StudyActivity] 显示的对不上 —— 用户看到聊天里说「今天 30 张」、学习页写「今天 12 张」，
     * 只会觉得整个 App 都不可信。
     *
     * 可选 [ARG_DAYS]（1..30，默认 [DEFAULT_PLAN_DAYS]）：用户问「这周怎么安排」时用它限定跨度。
     * 超出范围**收敛到边界**而不是报错 —— 这是模型的换算失误，不该让用户看到一次失败。
     */
    private fun studyPlan(): ToolSpec = ToolSpec(
        name = NAME_STUDY_PLAN,
        label = "正在排复习计划…",
        description = "根据到期的知识卡 / 单词和用户设置的学习计划，给出今天该复习多少、先补哪个牌组。" +
            "用户问「今天学什么 / 怎么安排复习 / 我这周该干什么」时用。",
        paramsJson = "{\"type\":\"object\",\"properties\":{" +
            "\"days\":{\"type\":\"integer\",\"description\":\"要看几天的安排，默认 " + DEFAULT_PLAN_DAYS +
            "，范围 1~30\"}},",
        executor = { ctx, a ->
            val prefs = Prefs(ctx.app)
            val cards = KnowledgeCard.Store(ctx.app).list()
            val words = WordSrs.all(prefs)
            val plan = prefs.studyPlan
            val advice = StudyCoach.suggest(cards, words, plan, System.currentTimeMillis())
            val days = num(a, ARG_DAYS).let { if (it <= 0L) DEFAULT_PLAN_DAYS.toLong() else it }
                .coerceIn(1L, MAX_PLAN_DAYS.toLong()).toInt()
            renderPlan(advice, days)
        },
    )

    /**
     * [StudyCoach.Advice] → 一段给模型看的话。
     *
     * [StudyCoach.Advice.summary] 已经是一句人话（"今天复习 12 张，另有 30 张积压"），
     * 这里补上**积压榜**：summary 说的是总量，模型要回答"先补哪个"必须知道牌组明细。
     * 积压为 0 时干脆不列牌组——空列表只会让模型以为数据没取到。
     */
    private fun renderPlan(advice: StudyCoach.Advice, days: Int): String {
        val sb = StringBuilder(advice.summary)
        if (days != DEFAULT_PLAN_DAYS) {
            sb.append("\n（按用户问的 ").append(days).append(" 天跨度看；今日额度以当前学习计划为准。）")
        }
        if (advice.decks.isEmpty()) {
            sb.append("\n目前没有积压的牌组。")
        } else {
            sb.append("\n积压最多的牌组：")
            sb.append(advice.decks.take(StudyCoach.TOP_DECKS).joinToString("；") { d ->
                val parts = ArrayList<String>(3)
                if (d.overdue > 0) parts.add("逾期 " + d.overdue)
                if (d.dueToday > 0) parts.add("今天到期 " + d.dueToday)
                if (d.newCards > 0) parts.add("新卡 " + d.newCards)
                d.name + "（" + (if (parts.isEmpty()) "共 " + d.total else parts.joinToString("，")) + "）"
            })
        }
        return sb.toString()
    }

    /** study_plan 的可选参数名与边界，集中一处免得 description 和代码里的数字漂开 */
    private const val ARG_DAYS = "days"
    private const val DEFAULT_PLAN_DAYS = 7
    private const val MAX_PLAN_DAYS = 30

    // ---------------- 小工具 ----------------

    /** 取字符串参数：不是字符串就当没给（模型偶尔会把数字直接塞进 text） */
    private fun str(o: JSONObject, key: String): String = try {
        if (o.isNull(key)) "" else o.optString(key, "").trim()
    } catch (e: Exception) { "" }

    /** 取整数参数：字符串形式的数字也认（模型常把毫秒时间戳写成带引号的），拿不到就是 0 */
    private fun num(o: JSONObject, key: String): Long = try {
        val v = if (o.isNull(key)) null else o.opt(key)
        when (v) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    } catch (e: Exception) { 0L }

    /** 本地时间「M月d日 HH:mm」。提醒话术是给用户看的，不写英文格式 */
    internal fun fmtTime(ms: Long): String = try {
        java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
    } catch (e: Exception) { ms.toString() }

    /** 今天的 yyyyMMdd（台账按这个键存），和 Prefs.todayInt 同一个口径 */
    internal fun todayStr(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** 截断补省略号：回填给模型的文本要有边界，否则一次失败就把几百字原文塞回去 */
    internal fun clip(s: String, n: Int): String {
        val t = s.replace('\n', ' ').trim()
        return if (t.length <= n) t else t.take(n) + "…"
    }

    /** 工具名/错误信息去噪：控制字符会把一行 SSE 日志断成两行 */
    private fun cleanName(s: String, n: Int): String =
        clip(s.filter { it.code >= 0x20 || it == '\t' }, n)

    const val NAME_ADD_CARD = "add_knowledge_card"
    const val NAME_ADD_REMINDER = "add_reminder"
    const val NAME_SEARCH_MEMORY = "search_memory"
    const val NAME_COST_REPORT = "cost_report"
    const val NAME_WEAK_POINTS = "weak_points"
    const val NAME_STUDY_PLAN = "study_plan"

    /** 和 KnowledgeCard.Store.add 的默认牌组一致；工具侧也留一份，不然两处容易改歪 */
    const val DEFAULT_DECK = "默认"
}
