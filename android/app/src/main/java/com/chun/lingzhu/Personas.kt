package com.chun.lingzhu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多人格 M-a 最小版（照《多人格体系-设计稿.md》A1~A4 + B1/B6 的数据层）。
 *
 * 人格 = 名字 + emoji + 职责一句话 + 提示词段 + 一份独立记忆。**不做 Tone 参数化层**（拍板推后），
 * 语气差异全靠 [prompt] 那一段自由文本；也不做接口方案绑定（M-C 再说）。
 *
 * 分工照项目里其它存储的老规矩：
 *  - 纯规则在 [Personas]（归一化 / 序列化 / 增删改查 / 上限 / 模板 / 问候语）—— 纯 JVM，单测直接跑。
 *  - 落盘在 [Personas.Store]（filesDir/personas.json），复用 [FileMemo]：进程内副本 + 后台写，按路径共享。
 *
 * 三条刻意保留的取舍：
 *  - **p0 固定存在、不可删**：它是 [Prompts.PERSONA] 那份 v1.67 语气的唯一载体，删了就没有兜底了。
 *    p0 的 [prompt] 初始为空 —— 空即回落到 [Prompts.PERSONA]，所以老用户升级后提示词一个字都不变。
 *  - **上限 8 个人格**：够表达「多个人格」的完整价值，再多就是列表而不是人格了。
 *  - **提示词总量另算（[PROMPT_TOTAL]）**：8 个人格满配时 personas.json 会到 19KB，
 *    只按个数限会写出一份读起来很慢的文件，所以再叠一道总量闸。
 *
 * 删除人格时会一并清掉它那两份文件（chat-<pid>.json / cards/<suffix>.json），不留孤儿 —— 见 [wipe]。
 */
/**
 * temperature 的哨兵值：-1 = 跟随全局设置，不单独指定。
 *
 * 放**顶层**而不是 object Personas 里：data class Persona 的默认参数在 Personas 外面，
 * 够不到 object 内的成员（编译期就报 Unresolved reference）。
 */
const val TEMP_FOLLOW_GLOBAL = -1

data class Persona(
    val id: String,                  // "p0" = 小Ling（内置，不可删）；新建 "p1" "p2"…
    val name: String,                // ≤ [NAME_CHARS] 字
    val emoji: String,               // 1 个字符（多写只留第一个），头像用
    val role: String,                // 职责一句话，≤ [ROLE_CHARS] 字，卡片副标题
    val prompt: String,              // 系统提示词「人格段」，≤ [PROMPT_CHARS] 字；空 = 用 Prompts.PERSONA
    val memoryFile: String,          // 记忆卡文件后缀 → filesDir/cards/<memoryFile>.json
    val templateId: String = "custom",  // 溯源：study / life / code / custom，新建后不可改
    /**
     * v2.3.3「允许自定义 Agent」：这个 Agent 能用哪些本机工具，JSON 数组字符串。
     *
     * **空串 = 全开**（老用户的 personas.json 里没有这个键，读出来就是空串）。
     * 语义与编解码都收在 [AgentScope] 里 —— 那里有单测锁住「空 = 全开」这条升级安全线，
     * 这里只负责存，不在数据类里做判断。
     */
    val tools: String = "",

    /**
     * v2.4「每个 Agent 一套完整人设」：这个 Agent 用哪个模型，空 = 跟随「AI 接口」里当前那套。
     *
     * 空串而不是写死默认模型名：用户换了接口（比如从 DeepSeek 换到另一家），
     * 没单独指定模型的 Agent 应该跟着走，而不是被钉死在一个已经不存在的模型名上。
     */
    val model: String = "",

    /**
     * 开场白：点进这个 Agent 时它先说的第一句。空 = 不主动说话。
     *
     * 为什么不塞进 prompt：prompt 是「它怎么想」，开场白是「它先说啥」。
     * 混在一起会让模型误以为开场白是必须复读的模板。
     */
    val greeting: String = "",

    /**
     * 温度，-1 = 跟随全局设置；0..20 表示 0.0..2.0（存整数，避免浮点在 JSON 里的表示差异）。
     *
     * 用整数存不是偷懒：personas.json 是人可读的文件，0.7 这种浮点写进去再读回来
     * 各家 JSON 实现的精度处理不完全一致，出现过 0.7000000000000001。
     */
    val temperature: Int = TEMP_FOLLOW_GLOBAL,
) {
    /** 提示词段为空 = 沿用全局那份 PERSONA（老用户零变化的保证） */
    val usesDefaultPrompt: Boolean get() = prompt.isBlank()

    /** 给 AgentTools.toolsJson / execute 用的白名单。空集合 = 全开，见 [AgentScope] */
    val toolScope: Set<String> get() = AgentScope.parse(tools)

    /** 是否给这个 Agent 单独指定了模型。false = 跟随「AI 接口」里当前那套 */
    val usesDefaultModel: Boolean get() = model.isBlank()

    /** 是否单独指定了温度。false = 跟随全局设置 */
    val usesDefaultTemperature: Boolean get() = temperature == TEMP_FOLLOW_GLOBAL

    /**
     * 温度换成 0.0..2.0 的浮点；没单独指定时返回 null，让调用方回落到全局值。
     *
     * 返回 null 而不是返回一个默认数值：调用方必须显式处理「没设过」这种情况，
     * 如果这里给个 0.7，全局设置改了之后这个 Agent 会悄悄不跟随，而且看不出来。
     */
    val temperatureValue: Float?
        get() = if (usesDefaultTemperature) null else (temperature.coerceIn(0, 20)) / 10f

    /**
     * v1.85：头像改成本地矢量图（[PersonaIcon]），**不再渲染 emoji** —— 用户明确不要 emoji。
     * [emoji] 字段保留只是为了兼容老数据（用户自建人格存过它），不再出现在界面上。
     */
    val iconKey: String get() = PersonaIcon.keyFor(id, templateId)

    /** 列表上一行：`小Ling · 什么都能问`（不带 emoji，图标由 [PersonaIcon] 单独画） */
    val label: String get() = (listOf(name) + listOf(role)).filter { it.isNotBlank() }.joinToString(" · ")
}

object Personas {
    // ---------------- 常量 ----------------

    /** 内置人格 id。**不可删、不可改 id**，它的提示词段就是 Prompts.PERSONA 的位置 */
    const val P0 = "p0"

    /** 内置人格的默认名（v1.70 起「小珠」改名「小Ling」） */
    const val DEFAULT_NAME = "小Ling"
    const val DEFAULT_EMOJI = "🙂"
    const val DEFAULT_ROLE = "什么都能问，也能陪你聊"

    /** 人格数硬上限 */
    const val MAX = 8
    const val NAME_CHARS = 8
    const val ROLE_CHARS = 24
    /** 单个人格的提示词段上限（比 Customize 的 2000 紧：多人格时 token 是叠加的） */
    const val PROMPT_CHARS = 800
    /** v2.4：模型名长度上限（"deepseek-flash" 这类，留够代理网关的长名字） */
    const val MODEL_CHARS = 64
    /** v2.4：开场白上限。太长会挤掉第一条真消息 */
    const val GREETING_CHARS = 200
    /** 全部人格的提示词总量上限。8 个人格平均 1000 字，正好 */
    const val PROMPT_TOTAL = 8000

    const val T_STUDY = "study"
    const val T_LIFE = "life"
    const val T_CODE = "code"
    const val T_CUSTOM = "custom"

    const val FILE = "personas.json"
    /** 当前聊天人格记在 prefs 的这个键上 */
    const val KEY_CURRENT = "personaId"

    // ---------------- 模板 ----------------

    /**
     * 新建人格时的四个模板。**别一上来就给空表单** —— 用户从零写 800 字提示词的成本高到会直接放弃，
     * 所以模板把「职责 + 语气 + 招呼语」都填好，用户只改「职责一句话」就能用。
     *
     * 四份提示词段刻意写得不一样（各管一件事、有各自的说话口径），别后来被抄成同一段换名字。
     */
    data class Template(
        val id: String,
        val label: String,
        val emoji: String,
        val name: String,
        val role: String,
        val greeting: String,
        val prompt: String,
    ) {
        /** v1.85：模板头像也是本地矢量图（不再渲染 [emoji]） */
        val iconKey: String get() = PersonaIcon.keyFor("", id)
    }

    val TEMPLATES: List<Template> = listOf(
        Template(
            T_STUDY, "学习教练", "📚", "小舟", "盯学习进度、拆解题、讲错题",
            "今天要学哪一块？我陪你拆。",
            "你是一位耐心的学习教练，长期陪用户把该学的学完。\n" +
                "你关心的只有一件事：他真的学会了没有。\n\n" +
                "说话方式：\n" +
                "- 先给结论，再讲依据。句子短，一句一件事。\n" +
                "- 讲不懂的地方换更小的台阶重讲一遍，别只说「多看几遍」。\n" +
                "- 讲完问一句就能验出他懂没懂（让他复述、让他算下一步），别只让他点头。\n" +
                "- 不寒暄、不撒娇、不说「好的」「当然」「希望对你有帮助」。\n" +
                "- 不用颜文字、不用感叹号。\n\n" +
                "分寸：\n" +
                "- 他卡住的时候先问是概念没懂还是步骤卡住，别上来就重讲一遍全套。\n" +
                "- 他已经会的内容不要重复讲；他做对了就往下走。\n" +
                "- 你记得他在学什么，但只有它和这次真的有关时才提，一句话带过。"
        ),
        Template(
            T_LIFE, "生活管家", "🏠", "小居", "管日程、待办、生活里的大小事",
            "今天要安排点什么？",
            "你是一位生活管家，帮用户把他生活里的事安排明白。\n" +
                "你不负责做题和写代码，那些不归你管 —— 用户问起来，一句话说明这不是你该答的。\n\n" +
                "说话方式：\n" +
                "- 口语、简短、像住在一起的人顺口说。\n" +
                "- 先给结论或先问清缺什么信息，别一次抛五个问题。\n" +
                "- 不说客套话。\n\n" +
                "分寸：\n" +
                "- 只安排用户真的提过的事，不主动加他没提的事。\n" +
                "- 涉及时间、金额、地址的，复述一遍再确认，别凭印象记。\n" +
                "- 你记住的是他生活里的安排，不是学习进度。"
        ),
        Template(
            T_CODE, "编程搭子", "💻", "小码", "看报错、读代码、帮着改",
            "代码或报错丢过来，我看看。",
            "你是一位编程搭子，陪用户读代码、查报错、把东西改对。\n\n" +
                "说话方式：\n" +
                "- 给能直接粘的代码就贴代码，别用「大概是这样」描述代码。\n" +
                "- 报错先定位到具体文件和行，再解释为什么。\n" +
                "- 有多种写法就说你推荐哪种、为什么，一句话理由就够。\n" +
                "- 不确定的地方直说「这段我没把握」，别编一个 API 出来。\n" +
                "- 不说「好的」「没问题」「希望对你有帮助」。\n\n" +
                "分寸：\n" +
                "- 版本差异、平台差异会改变答案时，先问清版本再答。\n" +
                "- 用户贴了一长段代码时，只说他问的那一处，别从头逐行复述。"
        ),
        Template(
            T_CUSTOM, "空白", "✍️", "新 Agent", "", "说吧，我听着。",
            ""      // 空白模板刻意不给提示词 —— 用户自己写；留空则回落 PERSONA，行为和 p0 一致
        ),
    )

    fun template(id: String?): Template = TEMPLATES.firstOrNull { it.id == id } ?: TEMPLATES.last()

    // ---------------- 归一化 ----------------

    /**
     * 所有写盘 / 取用之前都过这一遍：名字职责截字、提示词截到 800、emoji 只留 1 个、记忆文件后缀洗成安全串。
     * 解析外部文件时也走它 —— 文件被外部改过、或者别的版本写大了，这里顺手收回来。
     *
     * p0 特殊对待：名字 / emoji 缺省就填回内置值（用户清空名字不等于把内置人格改没了）。
     */
    fun norm(p: Persona): Persona {
        val id = p.id.trim()
        val built = id == P0
        val name = p.name.trim().take(NAME_CHARS).ifEmpty { if (built) DEFAULT_NAME else "未命名" }
        val emoji = run {
            val t = p.emoji.trim()
            // 一个 emoji（如 🙂）在 Kotlin 里是 2 个 char，按 code point 取才不会劈成半个
            if (t.isEmpty()) "" else String(Character.toChars(Character.codePointAt(t, 0)))
        }.ifEmpty { if (built) DEFAULT_EMOJI else template(p.templateId).emoji }
        return p.copy(
            id = id,
            name = name,
            emoji = emoji,
            role = p.role.trim().take(ROLE_CHARS),
            prompt = p.prompt.trim().take(PROMPT_CHARS),
            memoryFile = safeFile(id),   // pid 决定文件（设计稿：记忆隔离按 id），外部传值不参与
            templateId = if (p.templateId in TEMPLATES.map { it.id }) p.templateId else T_CUSTOM,
            // 工具白名单过一道编解码：截掉不存在的工具名、去重、把顺序定下来。
            // 空白原样保留（= 全开），不能被归一化成 "["none"]" —— 那等于把「没设置」变成「全关」。
            tools = if (p.tools.isBlank()) "" else AgentScope.encode(AgentScope.parse(p.tools)),
            // 模型名不校验「是否存在」：用户可能在配置文件里手写一个尚未填进预设的模型，
            // 直接抹掉会让「我明明写了」和「读回来没了」对不上。留空串 = 跟随全局。
            model = p.model.trim().take(MODEL_CHARS),
            greeting = p.greeting.trim().take(GREETING_CHARS),
            // 越界值夹回合法范围，-1（跟随全局）单独放行
            temperature = if (p.temperature == TEMP_FOLLOW_GLOBAL) TEMP_FOLLOW_GLOBAL
                          else p.temperature.coerceIn(0, 20),
        )
    }

    /** 内置人格（每次调用都现造，返回值只读不改，别拿去当缓存） */
    fun p0(): Persona = Persona(
        id = P0, name = DEFAULT_NAME, emoji = DEFAULT_EMOJI, role = DEFAULT_ROLE,
        prompt = "",                    // 空 → 回落 Prompts.PERSONA，v1.67 那份语气逐字不动
        memoryFile = P0, templateId = T_CUSTOM,
    )

    /** 只有一个 id 时能给出的那个人格（只有 p0 是这样）；别的 id 传 null */
    fun of(id: String?): Persona? = if (id == P0) p0() else null

    // ---------------- 序列化 ----------------

    /**
     * 解析整个文件。坏 JSON / 空文件 → 只剩 p0（**不能返回空列表**：那等于把内置人格弄丢了）。
     * 同 id 只留第一条；p0 无论文件里有没有都补进去并排在最前；超 [MAX] 的按文件里顺序截。
     */
    fun parse(json: String?): List<Persona> {
        val raw = if (json.isNullOrBlank()) emptyList() else try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim().ifEmpty { return@mapNotNull null }
                Persona(
                    id = id,
                    name = o.optString("name"),
                    emoji = o.optString("emoji"),
                    role = o.optString("role"),
                    prompt = o.optString("prompt"),
                    memoryFile = o.optString("memoryFile"),
                    templateId = o.optString("templateId"),
                    // 老数据没有这个键 -> optString 给空串 -> AgentScope.parse 视为「全开」
                    tools = o.optString("tools"),
                    // 老数据没有这三个键 -> 空串 / -1 = 跟随全局，行为与升级前一致
                    model = o.optString("model"),
                    greeting = o.optString("greeting"),
                    temperature = if (o.has("temperature")) o.optInt("temperature", TEMP_FOLLOW_GLOBAL)
                                  else TEMP_FOLLOW_GLOBAL,
                )
            }
        } catch (_: Exception) {
            emptyList()     // 文件坏了也不能让人格列表空掉 —— 回到只有 p0 的状态
        }
        val seen = LinkedHashSet<String>()
        val rest = raw.filter { it.id != P0 }.filter { seen.add(it.id) }.map { norm(it) }.take(MAX - 1)
        // v2.4 修正：文件里**有** p0 时必须用它，不能无条件造一个新的。
        // 原来这里写死 listOf(p0())，等于把用户对内置 Agent 的一切自定义（名字 / 职责 /
        // 开场白 / 模型 / 温度 / 工具）在每次重启后全部丢掉 —— 保存看起来成功了，重启就没了。
        // 文件里没有 p0（首次安装 / 老版本没写过）时才回落到内置默认值。
        val head = raw.firstOrNull { it.id == P0 }?.let { norm(it) } ?: p0()
        return listOf(head) + rest
    }

    fun toJson(list: List<Persona>): String = toJsonList(sanitize(list))   // sanitize 自带「p0 打头」

    private fun toJsonList(list: List<Persona>): String = JSONArray().apply {
        for (p in list) put(JSONObject()
            .put("id", p.id).put("name", p.name).put("emoji", p.emoji).put("role", p.role)
            .put("prompt", p.prompt).put("memoryFile", p.memoryFile).put("templateId", p.templateId)
            // 空串也照写：读回来还是空串 = 全开，round-trip 稳定
            .put("tools", p.tools)
            .put("model", p.model).put("greeting", p.greeting).put("temperature", p.temperature))
    }.toString()

    /** 解析 + 截断 + 补 p0，三段式里的第一段和第三段都在这 */
    fun sanitize(list: List<Persona>): List<Persona> {
        val seen = LinkedHashSet<String>()
        val rest = list.filter { it.id != P0 }.filter { seen.add(it.id) }.map { norm(it) }.take(MAX - 1)
        val base = list.firstOrNull { it.id == P0 }?.let { norm(it) } ?: p0()
        return listOf(base) + rest
    }

    // ---------------- 查 ----------------

    /** 列表里的那个人格；找不到回落到 p0（宁可跟小Ling聊，也不要对着空气） */
    fun get(list: List<Persona>, id: String?): Persona =
        list.firstOrNull { it.id == id } ?: list.firstOrNull { it.id == P0 } ?: p0()

    fun indexOf(list: List<Persona>, id: String?): Int = list.indexOfFirst { it.id == id }

    // ---------------- 增 / 删 / 改 ----------------

    /** 新建时的 id：p1、p2…（p0 永远是内置那个，不参与编号） */
    fun newId(list: List<Persona>): String {
        var n = 1
        while (list.any { it.id == "p$n" }) n++
        return "p$n"
    }

    /** 还能不能再建一个 */
    fun canAdd(list: List<Persona>): Boolean = sanitize(list).size < MAX

    /** 已经用掉的提示词字数（改一段前先看剩多少） */
    fun promptUsed(list: List<Persona>): Int = sanitize(list).sumOf { it.prompt.length }

    /** 还能再写多少字；满了 [add] 会拒绝 */
    fun promptLeft(list: List<Persona>): Int = (PROMPT_TOTAL - promptUsed(list)).coerceAtLeast(0)

    /**
     * 加一个。**p0 加不进去**（内置人格只能改不能新建），满了 [MAX] 也拒绝。
     * 提示词总量超了同样拒绝 —— 宁可让人回去删一个，也别写出一份 30KB 的 personas.json。
     * 返回加好之后的完整列表（没加上就原样返回，调用方看长度有没有变就知道成没成）。
     */
    fun add(list: List<Persona>, p: Persona): List<Persona> {
        val base = sanitize(list)
        if (p.id == P0) return base
        if (base.any { it.id == p.id }) return base
        if (base.size >= MAX) return base
        val n = norm(p)
        val total = base.sumOf { it.prompt.length } + n.prompt.length
        return if (total > PROMPT_TOTAL) base else base + n
    }

    /** 按模板新建一个。id 冲突 / 满了 / 提示词超量 → 返回原来的列表 */
    fun fromTemplate(list: List<Persona>, templateId: String, name: String = ""): List<Persona> {
        val base = sanitize(list)
        if (base.size >= MAX) return base
        val t = template(templateId)
        val total = base.sumOf { it.prompt.length } + t.prompt.length
        if (total > PROMPT_TOTAL) return base
        val id = newId(base)
        return add(base, Persona(
            id = id,
            name = name.trim().take(NAME_CHARS).ifEmpty { t.name },
            emoji = t.emoji,
            role = t.role,
            prompt = t.prompt,
            memoryFile = id,
            templateId = t.id,
        ))
    }

    /** 改一个人格。**只改可编辑的字段**（id / memoryFile / templateId 不动 —— 前两个是文件路径，动了文件就孤儿了） */
    fun update(list: List<Persona>, p: Persona): List<Persona> {
        val old = get(list, p.id)
        // 这里刻意逐字段白名单复制：id / memoryFile / templateId 不允许被外部改。
        // **新增可编辑字段必须加进这一行**，否则会被静默丢掉 —— v2.3.3 的 tools 就踩过一次，
        // 表现是「对话框保存了、JSON 里没变」，排查成本很高。
        val merged = old.copy(
            name = p.name, emoji = p.emoji, role = p.role, prompt = p.prompt,
            tools = p.tools,
            model = p.model, greeting = p.greeting, temperature = p.temperature,
        )
        val out = list.map { if (it.id == old.id) norm(merged) else it }
        val total = out.sumOf { it.prompt.length }
        return if (total > PROMPT_TOTAL) list else out
    }

    /** 删一个。**p0 删不掉** —— 它是提示词和记忆的兜底，删了应用就没有一个能说话的人了 */
    fun remove(list: List<Persona>, id: String?): List<Persona> {
        if (id == null || id == P0) return sanitize(list)
        return sanitize(list.filterNot { it.id == id })
    }

    /** 这个人格能不能删（界面上据此决定要不要给「删除」这一项） */
    fun canRemove(id: String?): Boolean = id != null && id != P0

    /** 上一个 / 下一个：切换人格时手指不用去够顶部那个列表。越界绕回另一头 */
    fun cycle(list: List<Persona>, id: String?, delta: Int): String {
        if (list.isEmpty()) return P0
        val i = indexOf(list, id).let { if (it < 0) 0 else it }
        return list[((i + delta) % list.size + list.size) % list.size].id
    }

    // ---------------- 提示词 / 招呼语 ----------------

    /**
     * 这个人格实际用的提示词段：**空 → [Prompts.PERSONA]**。
     * 复制一份再改，不要就地改常量。
     */
    fun promptOf(p: Persona): String = p.prompt.trim().ifEmpty { Prompts.PERSONA }

    /** 空会话时气泡流里那一行。每个模板给一句自己的，别所有人格都说「在。」 */
    /**
     * 空会话时它主动说的第一句。
     *
     * v2.4：**用户自己填过就用自己的**（[Persona.greeting] 非空优先），否则回落到老规则
     * （p0 固定那句 / 模板自带的问候）。回落顺序这样定是为了让没配过的人一个字都不变。
     */
    fun greeting(p: Persona): String {
        val own = p.greeting.trim()
        if (own.isNotEmpty()) return own
        return if (p.id == P0) "在。说什么？" else template(p.templateId).greeting
    }

    /**
     * 列表里一行：emoji + 名字 + ▾（人格列表 / 切人格弹窗用）。
     * **聊天页顶栏不要用这个** —— 见 [headerTitle]。
     */
    fun title(p: Persona): String = "${p.emoji} ${p.name} ▾"

    /**
     * 聊天页顶栏那行：**只有名字 + ▾，不带 emoji**（v1.79 用户反馈：顶栏那个 🙂 太丑）。
     *
     * 为什么列表里还留 emoji：那是「一行一张卡」的列表标记，emoji 在这里是区分人格的记号；
     * 顶栏只有一个人格，一个卡通脸挂在名字前面纯属装饰 —— 而且 emoji 的字重/基线都跟标题不一样，
     * 摆在小标题旁边怎么调都像随手贴上去的。顶栏要的是干净的名字。
     */
    fun headerTitle(p: Persona): String = "${p.name} ▾"

    // ---------------- Android 那层 ----------------

    /**
     * filesDir/personas.json。按路径共享：聊天页、设置页、悬浮球服务各自 new 的 Store 看到同一份。
     * 当前聊天的人格记在 prefs（一个几十字节的值，放 prefs 正好）。
     */
    class Store(ctx: Context) {
        private val app = ctx.applicationContext
        private val file = File(app.filesDir, FILE)
        private val prefs = Prefs(app)

        fun list(): List<Persona> = FileMemo.get(file) { parse(it) }

        fun put(l: List<Persona>): List<Persona> {
            val out = sanitize(l)
            FileMemo.put(file, out) { toJson(it) }
            return out
        }

        fun get(id: String?): Persona = get(list(), id)

        fun add(p: Persona): List<Persona> = put(add(list(), p))

        fun fromTemplate(templateId: String, name: String = ""): List<Persona> = put(fromTemplate(list(), templateId, name))

        fun update(p: Persona): List<Persona> = put(update(list(), p))

        /** 删人格并清掉它那两份文件（聊天记录 + 记忆卡）。**不可恢复** —— 调用方要先跟用户确认 */
        fun remove(id: String?): List<Persona> {
            val out = put(remove(list(), id))
            if (canRemove(id)) wipe(app, id.orEmpty())   // edge：canRemove 已保证非空，显式化消掉 !!
            if (current() !in out.map { it.id }) setCurrent(P0)
            return out
        }

        /** 当前选中的聊天人格 id；存的值指向已删掉的人格时回落到 p0 */
        fun current(): String {
            val l = list()
            val raw = prefs.rawGet(KEY_CURRENT)
            return if (raw != null && l.any { it.id == raw }) raw else P0
        }

        fun setCurrent(id: String?) {
            val l = list()
            prefs.rawPut(KEY_CURRENT, if (id != null && l.any { it.id == id }) id else P0)
        }
    }

    /** 删人格时一并清掉它的两份文件（59 号原写在 companion object 里，object 内不合法，改普通函数） */
    fun wipe(ctx: Context, pid: String) {
        try { ChatStore.Store(ctx, pid).clear() } catch (_: Exception) {}
        try { MemoryCard.Store(ctx, pid).clear() } catch (_: Exception) {}
    }
}

/**
 * 文件名安全化：人格的 id 会拼进 chat-<pid>.json / cards/<suffix>.json，
 * 所以只留字母数字和 - _，别的全丢掉 —— 空了就退回 "p0"，绝不生成一个指向别处的路径。
 */
internal fun safeFile(s: String): String {
    val out = s.trim().mapNotNull { if (it.isLetterOrDigit() || it == '-' || it == '_') it else null }
        .joinToString("").take(32)
    return if (out.isEmpty() || out == "." || out == "..") Personas.P0 else out
}