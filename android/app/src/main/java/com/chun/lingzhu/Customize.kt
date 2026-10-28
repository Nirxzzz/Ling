package com.chun.lingzhu

import org.json.JSONObject

/**
 * 自定义体系（v1.69）：「开箱即用 × 极致自定义」两层体系的底座。
 *
 * 设计哲学落地成两条铁律：
 *  - **层1 开箱**：每一项都有深思熟虑的默认值（[Spec.def]）。没存过 = 默认值，第一次用不碰任何设置就完整可用。
 *  - **层2 自定义**：层1 的每个默认值都可改，改的入口是「长按即改」（长按谁改谁），不在设置页平铺开关。
 * 改坏了每处都能一键 [restore] 回默认值。
 *
 * 三层分工（照仓库里 History / Words / MemoryCard 的老规矩）：
 *  - 纯规则在 [Customize]（归一化、存取、导入导出、长度防呆、人格拼接）—— 纯 JVM，单元测试直接测，不碰 Android。
 *  - 落盘在 [CustomizeStore]（app/src/main/.../CustomizeStore.kt），把 spec.id 映射到 Prefs 已有字段或 JSON blob。
 *  - 界面在 CustomizeActivity（汇总页）+ 各处的长按入口。
 *
 * ## 为什么值统一存成字符串
 * [Store] 只有 get/put 字符串一种动作，[Kind] 决定怎么解释。这样导入导出、恢复默认、跨版本兼容
 * 全是同一套代码路径 —— 加一个新自定义项只要往 [CATALOG] 里加一行，不用再写一遍存取。
 * 值类型在这层被 [norm] 强转过（数字夹到区间、枚举落到合法值、提示词截到上限），所以脏数据进不来，
 * 也因此「用户手改坏了存的值」不会把识别流程搞挂。
 */
object Customize {

    // ================= 值类型 =================

    enum class Kind {
        /** 短文本（称呼、口头禅）：trim + 长度上限 */
        TEXT,

        /** 整数：夹到 [Spec.min]~[Spec.max]，非数字回落默认值 */
        NUMBER,

        /** 枚举：必须是 [Spec.options] 之一，否则回落默认值 */
        ENUM,

        /** 开关：认 "true" / "1"，其余当 false */
        BOOL,

        /** 颜色：#RRGGBB，借 [Theme.parseHex] 校验，非法回落默认值 */
        COLOR,

        /** 提示词段：trim + [MAX_PROMPT_CHARS] 硬上限（防 token 失控） */
        PROMPT,
    }

    /**
     * 一个可自定义项。这里只描述「这一项是什么」，不描述「存哪儿」—— 映射在 CustomizeStore。
     *
     * @param id 稳定标识，**不要改**：改了老用户存的 customization 就对不上（会静默回落默认值）。
     * @param group 分组（自定义中心按它分区）
     * @param label 人看的名字
     * @param def 默认值（层1 的全部内容 —— 这一项「不改的时候是什么样」）
     * @param hint 编辑页里的一行说明：这一项改了会发生什么、有什么副作用
     * @param longPress 提示用户可以从哪儿长按进来（写进编辑页，让「入口=长按」这条规则可见）
     * @param maxChars 这一项自己的长度上限（0 = 按 [Kind] 的默认上限）。
     *   提示词段默认 [MAX_PROMPT_CHARS]、短文本默认 [MAX_TEXT_CHARS]；
     *   列表型文本（快捷短语那种一行一条的）要自己给一个够用的值。
     */
    data class Spec(
        val id: String,
        val group: String,
        val label: String,
        val kind: Kind,
        val def: String,
        val min: Int = 0,
        val max: Int = 0,
        val options: List<String> = emptyList(),
        val optionLabels: Map<String, String> = emptyMap(),
        val hint: String = "",
        val longPress: String = "",
        val maxChars: Int = 0,
    ) {
        /** 这一项实际生效的长度上限 */
        val limit: Int get() = when {
            maxChars > 0 -> maxChars
            kind == Kind.PROMPT -> MAX_PROMPT_CHARS
            kind == Kind.TEXT -> MAX_TEXT_CHARS
            else -> 0
        }
        /** 这一项是不是还在默认值上（自定义中心打「默认」标记用） */
        fun isDefault(cur: String?): Boolean = norm(this, cur) == def

        /** 选项的中文名（没配就显示 id 本身） */
        fun optionLabel(v: String): String = optionLabels[v] ?: v

        /** 界面上显示的当前值（BOOL 翻成人话，其余原样） */
        fun display(cur: String?): String {
            val v = norm(this, cur)
            return when (kind) {
                Kind.BOOL -> if (v == "true") "开" else "关"
                Kind.COLOR -> Theme.hex(Theme.parseHex(v) ?: 0)
                else -> optionLabel(v)
            }
        }
    }

    // ================= 长度防呆 =================

    /**
     * 提示词段 / 文本的硬上限。用户的话会原样进 system prompt，不限长就是白烧 token；
     * 2000 字 ≈ 3000 token，够写一份很细的人设了。
     */
    const val MAX_PROMPT_CHARS = 2000

    /** 短文本（称呼、口头禅）上限 —— 这两个是「插一句话」的位置，占不了太多 */
    const val MAX_TEXT_CHARS = 40

    /** 整套自定义序列化后的大小上限（存 SharedPreferences 一个键，4KB 足够放几十项） */
    const val MAX_BLOB_BYTES = 4096

    /**
     * 归一化 + 长度防呆。**所有写入路径都必须过它** —— 这是「脏数据进不来」的唯一入口。
     * 纯函数：任何输入都不抛异常（不信任外部 JSON，也不信任用户手改的 prefs）。
     */
    fun norm(s: Spec, raw: String?): String = when (s.kind) {
        Kind.TEXT -> clip(raw?.trim().orEmpty(), s.limit)
        Kind.PROMPT -> clip(raw?.trim().orEmpty(), s.limit)
        Kind.NUMBER -> {
            val n = raw?.trim()?.toIntOrNull()
            if (n == null) s.def else n.coerceIn(minOf(s.min, s.max), maxOf(s.min, s.max)).toString()
        }
        Kind.ENUM -> {
            val v = raw?.trim().orEmpty()
            // 老的 / 手改坏的值不认：回落默认值，而不是崩或者留个非法值往下传
            if (s.options.isEmpty() || v in s.options) v else s.def
        }
        Kind.BOOL -> when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> "true"
            "false", "0", "no", "off", "" -> "false"
            else -> s.def
        }
        Kind.COLOR -> {
            val v = raw?.trim().orEmpty()
            if (Theme.parseHex(v) != null) Theme.hex(Theme.parseHex(v)!!) else s.def
        }
    }

    /** 超限就截断；刚好不超就原样返回（不产生无谓的拷贝差异）。给 Prefs 的具名属性复用（同一条截断规则） */
    internal fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n)

    /** 界面上提示「超了多少」用（编辑页据此显示警告，用户才知道自己写的话被截了） */
    fun overLimit(s: Spec, raw: String?): Int {
        val v = raw ?: return 0
        val l = s.limit
        return if (l > 0) (v.trim().length - l).coerceAtLeast(0) else 0
    }

    // ================= 存取 =================

    /**
     * 存储接口。只有「按 id 读 / 按 id 写 / 按 id 删」三件事 ——
     * 不管底下是 SharedPreferences 的某个字段还是一个 JSON blob，对上层都一样。
     * MapStore 给单元测试用（也方便以后迁移）。
     */
    interface Store {
        fun get(id: String): String?
        fun put(id: String, value: String)
        fun remove(id: String)
    }

    /** 内存实现（单元测试 / 预览用） */
    class MapStore(private val m: MutableMap<String, String> = LinkedHashMap()) : Store {
        override fun get(id: String): String? = m[id]
        override fun put(id: String, value: String) { m[id] = value }
        override fun remove(id: String) { m.remove(id) }
        fun all(): Map<String, String> = m.toMap()
    }

    /** 当前值（永远经过 [norm]，所以调用方拿到的都是合法值） */
    fun get(s: Spec, store: Store): String = norm(s, store.get(s.id))

    /** 存一个值。返回**真正存进去的**那个（截断后的）—— 界面据此提示用户被截了多少 */
    fun set(s: Spec, store: Store, raw: String): String {
        val v = norm(s, raw)
        // 存成默认值 = 没自定义过：删键而不是存一份和默认一样的值。
        // 这样「恢复默认」是真的回到零状态，也省得以后改了默认值老用户还卡在旧值上。
        if (v == s.def) store.remove(s.id) else store.put(s.id, v)
        return v
    }

    /** 这一项恢复默认：删键即回到层1 的默认值 */
    fun restore(s: Spec, store: Store) = store.remove(s.id)

    /** 全部恢复默认（设置页的「全部恢复」用；逐项恢复在各自的编辑页底部） */
    fun restoreAll(store: Store) = CATALOG.forEach { restore(it, store) }

    /** 自定义中心列清单：分组 → 组内各项（组内保持 CATALOG 的声明顺序） */
    fun grouped(): List<Pair<String, List<Spec>>> {
        val out = LinkedHashMap<String, MutableList<Spec>>()
        for (s in CATALOG) out.getOrPut(s.group) { ArrayList() }.add(s)
        return out.toList()
    }

    /** 搜索（自定义中心顶上的输入框）：匹配名字、分组、说明、id —— 用户记得住的任何一点都能搜到 */
    fun search(q: String): List<Spec> {
        val k = q.trim().lowercase()
        if (k.isEmpty()) return CATALOG
        return CATALOG.filter {
            it.label.lowercase().contains(k) || it.group.lowercase().contains(k) ||
                it.hint.lowercase().contains(k) || it.id.lowercase().contains(k) ||
                it.optionLabels.values.any { l -> l.lowercase().contains(k) }
        }
    }

    fun byId(id: String): Spec? = CATALOG.firstOrNull { it.id == id }

    // ================= 导入 / 导出（JSON 文本，≤4KB）=================

    /** 导出用的格式版本。以后字段变了靠它判断怎么读 */
    const val FORMAT_VERSION = 1

    /**
     * 导出成一段 JSON 文本，用户可以复制发给别人，对方粘贴即导入。
     *
     * 只导出**和默认值不同**的项 —— 别人导入时拿到的是「他改了什么」，不是「他的全部配置」，
     * 这样两边默认值不一样（比如内测版 / 公测版）也不会互相污染。
     * 超 [MAX_BLOB_BYTES] 就抛 IllegalStateException（由调用方提示「配置太大」）——
     * 正常情况远到不了：几十项文本，最多几 KB。
     */
    fun export(store: Store, specs: List<Spec> = CATALOG): String {
        val o = JSONObject()
        o.put("format", "lingzhu-custom")
        o.put("version", FORMAT_VERSION)
        val items = JSONObject()
        for (s in specs) {
            val cur = get(s, store)
            if (cur != s.def) items.put(s.id, cur)
        }
        o.put("items", items)
        val text = o.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BLOB_BYTES) throw IllegalStateException("自定义配置太大（超过 $MAX_BLOB_BYTES 字节），导出失败")
        return text
    }

    /** 导入结果。[applied] 是真的写进去了的 id，[skipped] 是「配置里提到了但本机没这一项 / 非法值」。给用户一句人话汇报。 */
    data class ImportResult(val applied: List<String>, val skipped: List<String>, val truncated: Boolean = false)

    /**
     * 导入。**只认 [CATALOG] 里有的 id** —— 别人发来的配置里塞了本机不存在的 id 就忽略（跳过，不报错）。
     * 每一项的值都过一遍 [norm]，非法值回落默认值而不是原样写进去。
     * @param replace true = 先全部恢复默认再导入（干净贴合对方）；false = 只覆盖对方改过的项，保留本机其他自定义
     */
    fun import(text: String?, store: Store, replace: Boolean = false): ImportResult {
        if (text.isNullOrBlank()) return ImportResult(emptyList(), emptyList())
        val o = try { JSONObject(text) } catch (_: Exception) { return ImportResult(emptyList(), listOf("不是有效的配置文本")) }
        if (o.optString("format") != "lingzhu-custom") return ImportResult(emptyList(), listOf("不是 Ling 的自定义配置"))
        val v = o.optInt("version", 0)
        if (v > FORMAT_VERSION) return ImportResult(emptyList(), listOf("配置版本更新（v$v），请先升级 Ling"))
        val items = o.optJSONObject("items") ?: return ImportResult(emptyList(), emptyList())

        // 先全部算好再落盘：这样中途失败不会留下写了一半的状态
        val planned = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()
        for (key in items.keys()) {
            val s = byId(key)
            // 别人发来的配置里塞了本机不存在的 id → 忽略，不报错（对方的版本可能更新）
            if (s == null) { skipped.add("$key（本机没有这一项）"); continue }
            val nv = norm(s, items.optString(key, ""))
            // 归一化后正好等于默认值 = 这一项不用改。两种情况：
            //  - 对方就填的默认值 → 无事可做，不算问题
            //  - 对方的值非法（枚举不认 / 颜色坏了 / 数字超界）→ 如实报出来，别让人以为导进去了
            if (nv == s.def) skipped.add(s.label)
            else planned[s.id] = nv
        }
        if (replace) restoreAll(store)
        for ((id, v) in planned) store.put(id, v)
        return ImportResult(planned.keys.toList(), skipped)
    }

    // ================= 人格拼接（小珠人格三项在这里合成一段）=================

    /**
     * 小珠人格的三项（称呼 / 语气补充 / 口头禅）合成一段，插在 [Prompts.PERSONA] 之后。
     *
     * 三条设计取舍：
     *  - **用户话优先**：这一段声明自己是「风格覆盖」，排在 PERSONA 之后 —— 用户明确写的话该压过默认语气。
     *  - **格式标记行保留**：明确告诉模型「## 标题、末尾关键词清单行、LaTeX 写法照旧」。
     *    用户改的是「怎么说」，不是「面板怎么解析」—— 格式标记被砍掉面板就崩了（词块拆不出来、公式排不了）。
     *    这句是防呆的核心，改拼接顺序时别丢。
     *  - 三项都空 → 返回空串，一个字都不拼（零配置的老用户提示词一个字都不变）。
     */
    fun personaSegment(name: String, tone: String, catchphrase: String): String {
        val parts = ArrayList<String>(3)
        name.trim().takeIf { it.isNotEmpty() }?.let { parts.add("称呼用户为「$it」。") }
        tone.trim().takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        catchphrase.trim().takeIf { it.isNotEmpty() }?.let { parts.add("你爱说的一句口头禅是「$it」，偶尔自然带出来，别每句都甩。") }
        if (parts.isEmpty()) return ""
        return "【用户自己写的 Agent —— 风格上覆盖上面这些默认设定，听用户的；" +
            "但 ## / ### 标题行、末尾的关键词清单行、LaTeX 公式写法照旧，格式一个字都不能改】\n" +
            parts.joinToString("\n")
    }

    // ================= 清单 =================
    // 本期接入的项。**每加一项只改这里**，自定义中心、长按入口、导入导出自动就有了。
    //
    // 「默认值」这一列就是层1 的承诺 —— 写错了整层都跟着错，改之前先想清楚「不开这个功能时该是什么样」。
    // 和已有 Prefs 字段对应的项（orbSizeDp / textSp / orbFar / orbTuck / panelPos / detail）：
    // 默认值必须和 Prefs 里写的一致，否则老用户升级后会突然「变了」。

    const val G_PERSONA = "Agent"
    const val G_MODE = "模式"
    const val G_ORB = "小球"
    const val G_PANEL = "面板"
    const val G_ASK = "追问"

    /** 贴边行为：三个值合成原先 orbFar + orbTuck 两个开关（用户只想选一种行为，不用理解两个开关的关系） */
    val EDGE_NEAR = "near"
    val EDGE_HUG = "hug"
    val EDGE_FAR = "far"

    /** 解析强度默认档：各模式第一次进来用哪一档（之后用户在面板上临时改的不受影响） */
    val DETAIL_BRIEF = "brief"
    val DETAIL_STANDARD = "standard"
    val DETAIL_DETAILED = "detailed"

    val CATALOG: List<Spec> = listOf(

        // ---------- 小珠人格（拼进 Prompts.PERSONA 之后，见 personaSegment）----------
        Spec(
            "persona.name", G_PERSONA, "它怎么称呼你", Kind.TEXT, "",
            hint = "填了以后它会这么叫你。留空 = 不称呼它。",
            longPress = "面板上长按模式标签旁的「Agent」",
        ),
        Spec(
            "persona.tone", G_PERSONA, "语气补充指令", Kind.PROMPT, "",
            hint = "你自己的说话要求，排在默认语气之后（你的话优先）。只改「怎么说」，不改面板需要的格式标记。最多 2000 字。",
            longPress = "面板上长按模式标签旁的「Agent」",
        ),
        Spec(
            "persona.catch", G_PERSONA, "口头禅", Kind.TEXT, "",
            hint = "它爱说的一句话，填了它会偶尔带出来。留空 = 没有。",
            longPress = "面板上长按模式标签旁的「Agent」",
        ),

        // ---------- 模式 ----------
        Spec(
            "mode.detailDefault", G_MODE, "解析强度默认档", Kind.ENUM, DETAIL_STANDARD,
            options = listOf(DETAIL_BRIEF, DETAIL_STANDARD, DETAIL_DETAILED),
            optionLabels = mapOf(DETAIL_BRIEF to "简略（短、只讲关键）", DETAIL_STANDARD to "标准", DETAIL_DETAILED to "详细（不跳步）"),
            hint = "识别时默认用哪一档。面板上临时改过一次不影响这里 —— 这里只管「默认」。",
            longPress = "小球菜单 → 更多调节 → 解析强度",
        ),
        Spec(
            "mode.customPrompt", G_MODE, "自定义模式提示词模板", Kind.PROMPT, "",
            hint = "新建自定义模式时预填的内容。留空 = 用内置示例。自定义模式自己的提示词在设置页「接口 → 模式」里改。",
            longPress = "面板上长按模式标签",
        ),

        // ---------- 小球 ----------
        Spec(
            "orb.sizeDp", G_ORB, "大小", Kind.NUMBER, "58", min = 40, max = 84,
            hint = "直径，40~84 dp。默认 58。",
            longPress = "小球菜单 → 更多调节 → 自定义小珠…",
        ),
        Spec(
            "orb.idleScale", G_ORB, "闲置时缩到", Kind.NUMBER, "60", min = 30, max = 100,
            hint = "闲置几秒后缩小到原来的百分之多少。默认 60（缩到六成）。",
            longPress = "小球菜单 → 更多调节 → 自定义小珠…",
        ),
        Spec(
            "orb.edge", G_ORB, "贴边行为", Kind.ENUM, EDGE_HUG,
            options = listOf(EDGE_NEAR, EDGE_HUG, EDGE_FAR),
            optionLabels = mapOf(
                EDGE_NEAR to "贴边但不缩进去",
                EDGE_HUG to "贴边，闲置缩进去一半（默认）",
                EDGE_FAR to "离远一点（让开返回手势）",
            ),
            hint = "贴着屏幕边放还是离远一点。「让开返回手势」选离远，其余两项贴边。",
            longPress = "小球菜单 → 更多调节 → 自定义小珠…",
        ),
        Spec(
            "orb.holdMs", G_ORB, "长按多久弹菜单", Kind.NUMBER, "500", min = 250, max = 1500,
            hint = "按住小球多少毫秒后弹菜单。默认 500（半秒）。调小更灵敏，调大手不抖。",
            longPress = "小球菜单 → 更多调节 → 自定义小珠…",
        ),

        // ---------- 面板 ----------
        Spec(
            "panel.pos", G_PANEL, "出场方向", Kind.ENUM, PanelLayout.DEFAULT,
            options = PanelLayout.POSITIONS,
            optionLabels = PanelLayout.LABEL,
            hint = "面板从哪一边滑进来。默认左边。",
            longPress = "面板 → 长按横条拖动可临时挪位，松手记成自由位置",
        ),
        Spec(
            "panel.textSp", G_PANEL, "正文字号", Kind.NUMBER, "15", min = 12, max = 22,
            hint = "面板正文的字号 sp，12~22。默认 15。",
            longPress = "面板 → 长按横条",
        ),
        Spec(
            "panel.askChips", G_PANEL, "追问快捷开关", Kind.ENUM, "both",
            options = listOf("both", "what", "why", "none"),
            optionLabels = mapOf(
                "both" to "两个都要（what + why，默认）",
                "what" to "只要「讲简单点并举例」",
                "why" to "只要「用第一性原理问为什么」",
                "none" to "都不要（只用输入框里写的要求）",
            ),
            hint = "追问栏上摆哪几个开关。选「都不要」= 每次追问都得自己写要求。",
            longPress = "面板 → 长按追问栏的标签",
        ),

        // ---------- 追问 ----------
        Spec(
            "ask.phrases", G_ASK, "快捷短语", Kind.TEXT, "",
            maxChars = 300,
            hint = "面板上长按「发送」弹出的常用说法，一行一条，最多 ${Freeform.MAX_PHRASES} 条、每条 ${Freeform.PHRASE_MAX} 字。留空 = 不弹。",
            longPress = "面板 → 长按「发送」",
        ),
    )
}
