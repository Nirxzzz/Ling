package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener

/**
 * 设置首页顶部的「AI 模糊搜索」—— **只做检索与协议，不联网、不读设置值**。
 *
 * 分工：
 *  - [local]：输入框每次变化就调，纯本地即时结果（中英文大小写无关、连续子串、同一子串里的
 *    汉字乱序，外加一张很小的同义词表）。不联网、不算巧。
 *  - 输入停手后默认问一次模型（点「搜索」/ 键盘搜索键立刻再发）：界面那边用 [prompt] + `Prefs.api`
 *    拿到的 [ApiConfig] + `Bodies.text(cfg, prompt(q), …, thinkingOff = true)` 发一次，再 [fromAi] 解析；
 *    没配接口或配置不全时界面静默只留本地结果。**本文件不发请求**，所以单测里不会出网，也不会碰到密钥。
 *  - [fromAi] 只认协议：整段是 JSON 字符串数组、元素是白名单里的精确 [SettingsRegistry.Item.id]；
 *    重复去重、超 [limit] 截断；未知 ID / 非法结构一律空结果，不猜、不从自由文本里捞 ID。
 *
 * 收录范围：[SettingsRegistry.ITEMS] + [NEW_ITEMS]，只要 `merged == null` 且卡片真实存在
 * （首页块 [SettingsRegistry.S_HUB] 可路由，但 hub 条目本身不收录；「版本」卡那条反而收录）。
 * 二级页的卡片标题也是可搜目标 —— 搜「打游戏时隐藏小球」这种卡片名也该点得进去。
 *
 * 改的时候请留住这两条：**候选描述里不能出现用户设置值**（[prompt] 只放名称 / 路径 / ID）；
 * **协议宁严勿宽**（[fromAi] 拒绝东西时返回空表，界面显示「没匹配到」，不许兜回自由文本）。
 */
object SettingsSearch {

    data class Hit(val itemId: String, val label: String, val sectionId: String, val cat: String, val advanced: Boolean)

    /** [prompt] 里「候选」的条数上限，给模型的结果预算也算这个数 */
    fun defaultLimit(limit: Int): Int = if (limit <= 0) 6 else limit.coerceAtMost(20)

    /** AI 一次最多回几个（[prompt] 里写死，[fromAi] 也按它截断） */
    private const val AI_MAX = 6

    // ---------------- 候选 ----------------

    /** 一条可搜的候选：一个设置项或一张卡片 */
    private class Entry(
        val id: String,
        val label: String,
        val sectionId: String,
        val cat: String,
        val advanced: Boolean,
        /** 名称及分组组成的标准化匹配文本，不含用户设置值。 */
        val hay: String,
        val path: String,
    )

    private val entries: List<Entry> by lazy { build() }

    private fun build(): List<Entry> {
        val out = ArrayList<Entry>()
        for (it in SettingsRegistry.ITEMS + SettingsRegistry.NEW_ITEMS) {
            if (it.merged != null) continue                       // 已合并的入口不再单独出现
            val sec = SettingsRegistry.section(it.section) ?: continue  // 首页块（s.hub）不收录
            out += Entry(
                id = it.id, label = it.name, sectionId = sec.id, cat = sec.cat, advanced = it.advanced,
                hay = normalize(it.name) + " " + normalize(sec.title),
                path = pathOf(sec),
            )
        }
        // 卡片标题本身也是入口（点进去就是那张卡）
        for (sec in SettingsRegistry.SECTIONS) {
            out += Entry(
                id = sec.id, label = sec.title, sectionId = sec.id, cat = sec.cat, advanced = false,
                hay = normalize(sec.title) + " " + normalize(sec.cat),
                path = pathOf(sec),
            )
        }
        return out
    }

    private fun pathOf(sec: SettingsRegistry.Section) = "${sec.cat} › ${sec.title}"

    /** 精确白名单：只有这些 ID 能被 AI 结果采纳 */
    private val legalIds: Set<String> by lazy { entries.map { it.id }.toSet() }
    private val byId: Map<String, Entry> by lazy { entries.associateBy { it.id } }

    // ---------------- 本地检索 ----------------

    /**
     * 本地即时匹配，相关性降序，最多 [limit] 条（< 1 视为不限制，方便内部复用）。
     * 一条都不沾边就返回空表 —— 输入框里没有结果，比列一排无关项干净。
     */
    fun local(q: String, limit: Int = 6): List<Hit> {
        val query = normalize(q)
        if (query.isEmpty()) return emptyList()
        val expanded = expand(query)
        val compactQuery = compact(expanded.joinToString(""))
        val parts = partsOf(expanded)
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in entries) {
            val s = score(e.hay, query, expanded, compactQuery, parts)
            if (s > 0) scored += e to s
        }
        if (scored.isEmpty() && limit < 1) return emptyList()
        scored.sortWith(compareByDescending<Pair<Entry, Int>> { it.second }
            .thenBy { it.first.advanced }            // 同分时常用项排前面
            .thenBy { it.first.id })
        val n = if (limit < 1) scored.size else limit
        return scored.take(n).map { it.first.toHit() }
    }

    private fun Entry.toHit() = Hit(id, label, sectionId, cat, advanced)

    /**
     * 一行候选文本的打分。0 = 不相关。[hay] / [query] 都已 [normalize]。
     *
     * 命中层级（高 → 低）：
     *  1. 原文包含整串（越长越靠前）；
     *  2. 去虚词后包含（「字太小」→「字号」靠同义词，见 [expand]）；
     *  3. 同义词表展开后的任一词包含；
     *  4. 查询里的汉字 / 英文词逐个包含（至少一半才算，否则「查看」这种会命中一片）。
     * 英文按小写查，中文要求连续子串 —— 单独一个字（「震」）不算命中，免得整表糊上来。
     */
    private fun score(hay: String, query: String, q: List<String>, compactQ: String, parts: List<String>): Int {
        if (compactQ.length < 2) return 0

        var best = 0
        // 1) 整串
        if (hay.contains(query)) best = 100 + query.length * 3
        // 2) 去虚词
        if (best == 0 && compactQ.length >= 2 && compact(hay).contains(compactQ)) best = 80 + compactQ.length * 2
        // 3) 同义词 / 原词逐个
        if (best == 0) {
            for (term in q) {
                if (term.length < 2) continue
                if (compact(hay).contains(compact(term))) {
                    val v = 40 + term.length * 2 + (if (term == query) 4 else 0)
                    if (v > best) best = v
                }
            }
        }
        // 4) 拆碎匹配（只吸一半以上的碎片，挡掉泛词）
        if (best == 0) {
            if (parts.size >= 2) {
                val hit = parts.count { hay.contains(it) }
                if (hit * 2 >= parts.size) best = 10 + hit * 2
            }
        }
        if (best == 0) return 0
        // 路径（分类 / 卡片名）整串也命中，同分时靠前
        if (best < 40 && hay.contains(compactQ)) best += 2
        return best
    }

    // ---------------- 同义词（小而明确，别长成框架） ----------------

    /**
     * 输入 → 要额外尝试的说法。只处理这些明确句式，不做通用分词 / 词性。
     * 左列是用户会说的话，右列是设置项里实际用的词（与 SettingsRegistry 的名称对齐）：
     *   不想震动 / 别震 → 震动（S_POP_VIBE 那张卡 + R3）
     *   字太小 → 字号（L5 面板正文字号）
     *   游戏不想看见球 → 游戏隐藏（S_ORB_HIDE 那张卡）
     *   晚上别弹 → 免打扰（R7）与夜里不弹（W3）
     */
    private val SYNONYMS: List<Pair<String, List<String>>> = listOf(
        "震动" to listOf("震动"), "振动" to listOf("震动"), "震" to listOf("震动"), "vibrate" to listOf("震动"),
        "字太小" to listOf("字号"), "字小" to listOf("字号"), "字大" to listOf("字号"), "字体" to listOf("字号"),
        "看清" to listOf("字号"), "大小" to listOf("字号", "大小"),
        "游戏" to listOf("打游戏", "隐藏小球", "游戏时", "隐藏"), "隐藏" to listOf("隐藏"),
        "球" to listOf("小球", "悬浮球"),
        "晚上" to listOf("夜里", "免打扰", "晚简报"), "夜里" to listOf("夜里"),
        "别弹" to listOf("不弹", "免打扰", "弹出"), "不弹" to listOf("不弹", "免打扰"),
        "打扰" to listOf("免打扰"), "安静" to listOf("免打扰", "静音"),
        "弹" to listOf("弹出", "弹词"), "提醒" to listOf("提醒", "简报"),
        "接口" to listOf("接口"), "地址" to listOf("地址"), "url" to listOf("地址"), "模型" to listOf("模型"),
        "密钥" to listOf("密钥"), "key" to listOf("密钥", "key"), "apikey" to listOf("密钥"),
        "搜索" to listOf("搜索"), "联网" to listOf("联网"), "翻译" to listOf("翻译"),
        "缓存" to listOf("缓存"), "历史" to listOf("历史"), "更新" to listOf("更新"),
        "权限" to listOf("权限"), "后台" to listOf("后台", "保活"), "保活" to listOf("保活"),
        "悬浮球" to listOf("悬浮球", "小球"), "小球" to listOf("小球"),
        "词库" to listOf("词库"), "背单词" to listOf("单词", "词库"), "背词" to listOf("背词", "词库"),
        "配色" to listOf("配色", "颜色"), "主题" to listOf("配色", "主题"), "颜色" to listOf("颜色"),
        "透明度" to listOf("不透明度"), "不透明" to listOf("不透明度"),
        "护眼" to listOf("护眼", "配色"),
        "同步" to listOf("同步"), "局域网" to listOf("局域网"),
        "版本" to listOf("版本"), "识别模式" to listOf("识别模式", "模式"), "模式" to listOf("模式"),
        "自动扫描" to listOf("自动扫描", "扫描"), "屏幕" to listOf("屏幕", "识屏"),
        "尺寸" to listOf("大小", "宽度", "字号"), "宽度" to listOf("宽度"),
        "位置" to listOf("位置"), "靠边" to listOf("靠哪边", "离屏幕边"),
        // 语音转写（新增卡片 + 入口）：搜「语音」「转写」「麦克风」「豆包」「阿里」都要能找到这一页
        "语音" to listOf("语音转写", "转写"), "转写" to listOf("转写", "语音转写"),
        "麦克风" to listOf("麦克风", "语音转写"), "录音" to listOf("麦克风", "语音转写"),
        "豆包" to listOf("豆包", "语音转写"), "阿里" to listOf("阿里", "语音转写"),
        "出字" to listOf("语音转写"), "说话" to listOf("语音转写", "麦克风"),
        // 专注守护 / 陪伴气泡（新增两张卡）：搜「专注」「走神」「学习应用」「使用情况」「陪伴」「气泡」都要能找到
        "专注" to listOf("专注守护", "专注"), "走神" to listOf("专注守护", "离开"),
        "分心" to listOf("专注守护", "离开"), "离开" to listOf("专注守护", "离开"),
        "学习应用" to listOf("指定学习应用", "学习应用", "专注守护"),
        "使用情况" to listOf("使用情况访问", "指定学习应用", "专注守护"),
        "前台" to listOf("指定学习应用", "前台"), "白名单" to listOf("学习应用", "指定学习应用"),
        "陪伴" to listOf("陪伴气泡", "气泡"), "气泡" to listOf("陪伴气泡", "气泡"),
        "频率" to listOf("陪伴气泡", "频率"), "多久" to listOf("陪伴气泡", "频率", "离开多少分钟"),
    )

    /** 语气 / 否定 / 代词这类虚词：从查询里剔掉，剩下的再去查同义词 */
    private val NOISE = listOf(
        "不想", "不要", "不用", "不太", "别让", "不想让", "不想再", "不能", "可不可以", "能不能", "怎么办", "怎么",
        "怎么样", "想要", "想让", "我要", "我想", "我想让", "一下", "一点", "一些", "那个", "这个", "哪里", "哪儿",
        "在哪儿", "怎么改", "在哪", "请问", "帮我", "设置", "我", "你", "想", "要", "把", "让", "给", "的", "了",
        "吗", "呢", "啊", "吧", "很", "太", "有点", "些", "还", "就", "也", "都", "个", "一下下",
    ).sortedByDescending { it.length }

    /** 查询 → 原串 + 去虚词串 + 同义词串（去重、去太短的） */
    private fun expand(query: String): List<String> {
        val out = LinkedHashSet<String>()
        if (query.length >= 2) out += query
        var core = query
        for (n in NOISE) core = core.replace(n, " ")
        core = compact(core)
        if (core.length >= 2) out += core
        for ((k, vs) in SYNONYMS) {
            val kk = normalize(k)
            if (query.contains(kk) || core.contains(kk)) for (v in vs) out += normalize(v)
        }
        return out.filter { it.isNotEmpty() }.toList()
    }

    /** 拆成中文单字之外的小片：汉字按单字，英文/数字按词 */
    private fun partsOf(q: List<String>): List<String> {
        val out = ArrayList<String>()
        for (t in q) {
            val sb = StringBuilder()
            fun flush() { if (sb.length >= 2) out += sb.toString(); sb.setLength(0) }
            for (c in t) {
                if (c.isAsciiAlnum()) sb.append(c) else { flush(); if (isCjk(c)) out += c.toString() }
            }
            flush()
        }
        return out.distinct()
    }

    // ---------------- AI 协议 ----------------

    /**
     * 发给模型的提示词：只有查询 + 候选（名称 / 路径 / ID），**不含任何用户设置值**。
     * 职责边界也写在里面：只让它在候选里挑 ID，不许它回答问题或解释。
     */
    fun prompt(q: String): String {
        val sb = StringBuilder()
        sb.append("你在帮用户找 App 里的设置项。用户想找：\n")
        sb.append("「").append(q.trim().take(120)).append("」\n\n")
        sb.append("只能在下面候选里挑，最多 ").append(AI_MAX).append(" 个，按相关度从高到低。\n")
        sb.append("候选格式：ID | 名称 | 路径\n")
        sb.append("——\n")
        for (e in entries) sb.append(e.id).append(" | ").append(e.label).append(" | ").append(e.path).append('\n')
        sb.append("——\n")
        sb.append("只输出一个 JSON 字符串数组，元素是候选的 ID，例如 [\"P4\",\"P3\"]。\n")
        sb.append("不要输出任何其它文字、解释或代码块；没有相关的就输出 []。")
        return sb.toString()
    }

    /**
     * 解析模型回复。协议：整段（允许被一对 markdown 代码块包住）是 JSON 字符串数组。
     *
     * 拒绝：非数组、含非字符串元素、整段还有别的文字、未知 ID。整份结果作废返回空表 ——
     * 半信半疑地挑几个出来，反而会把用户带到错的地方。
     * 接受：重复 ID 去重（保留首次出现的顺序）、超 [limit]（上限 [AI_MAX]）截断。
     */
    fun fromAi(raw: String, limit: Int = 6): List<Hit> {
        val body = stripFence(raw) ?: return emptyList()
        val arr = try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue() as? JSONArray ?: return emptyList()
            if (tokener.nextClean() != '\u0000') return emptyList()
            value
        } catch (_: JSONException) {
            return emptyList()
        }
        val cap = minOf(defaultLimit(limit), AI_MAX)
        val out = ArrayList<Hit>(cap)
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val id = arr.opt(i) as? String ?: return emptyList()      // JSONObject.NULL / 数字 / 嵌套都不认
            val e = byId[id] ?: return emptyList()                     // 精确白名单，不做 trim/大小写宽容
            if (seen.add(id) && out.size < cap) out += e.toHit()
        }
        return out
    }

    /** 只有「整段」被一对 ``` 包住才剥；剥完还必须整段是个 JSON 数组 */
    private fun stripFence(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl < 0) return null
            val fence = s.substring(0, firstNl).removePrefix("```").trim()
            if (fence.isNotEmpty() && !fence.equals("json", true)) return null
            if (!s.endsWith("```")) return null
            s = s.substring(firstNl + 1, s.length - 3).trim()
        }
        if (!s.startsWith("[") || !s.endsWith("]")) return null
        return s
    }

    // ---------------- 文本处理 ----------------

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c0 in s.lowercase()) {
            val c = when (c0) { '　' -> ' '; else -> c0 }
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    /** 只留字母数字（匹配时挡住中间的空格和标点） */
    private fun compact(s: String): String = s.filter { it.isLetterOrDigit() }

    private fun isCjk(c: Char) = c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'
}
