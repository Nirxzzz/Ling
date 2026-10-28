package com.chun.lingzhu

/**
 * v2.4：长按小球弹出的菜单，**哪些项、什么顺序**交给用户决定。
 *
 * ## 为什么单独抽一张注册表
 * 原来这十个菜单项是写死在 OrbService.showMenu() 里的一串 item() 调用 —— 顺序就是代码顺序，
 * 想改只能改代码。现在把它们变成数据：每一项有一个稳定 id、一个标题、一个「要不要分隔线」。
 * showMenu() 只负责按 [resolve] 给出的顺序把它们画出来。
 *
 * ## 三个不能交给用户动的东西
 * 1. **「和小Ling聊聊」永远第一**：那是长按菜单存在的主要理由，藏起来等于把入口弄没了。
 * 2. **「设置」和「停止小球」永远留**：一个去改配置，一个关掉悬浮球；这两个要是能删，
 *    用户可能把自己关在门外（菜单里再也找不到设置，小球又关不掉）。
 * 3. **分隔线跟着项走**，不能单独拖动 —— 否则会出现「两条分隔线挨在一起」这种空砍。
 */
object OrbMenu {

    /**
     * 一个菜单项。
     * @param id 稳定标识，**不要改**：改了老用户存着的排列就对不上（会静默回落默认顺序）
     * @param label 菜单上显示的名字
     * @param pinned 是否强制常驻（用户不能隐藏，也不能拖走）
     * @param sepBefore 这一项前面是否加一条分隔线
     */
    data class Item(
        val id: String,
        val label: String,
        val pinned: Boolean = false,
        val sepBefore: Boolean = false,
    )

    const val ID_CHAT = "chat"

    /** v2.4 截图速记：截一张图 + 写一句话存成笔记，全程不跳 App */
    const val ID_NOTE = "note"

    /**
     * v2.7：双击小球做什么（长按菜单里就地切换）。取值见 [TAPS]，缺省 [TAP_ASK]。
     * 值存在 [Prefs.orbDoubleTap]，只影响「双击」这一条入口 —— 设置里的识别模式原样不动。
     */
    const val ID_TAPMODE = "tapmode"

    const val ID_REGION = "region"
    const val ID_AUTOSCAN = "autoscan"
    const val ID_WORD = "word"
    const val ID_HISTORY = "history"
    const val ID_TRANSLATE = "translate"
    const val ID_SETTINGS = "settings"
    const val ID_MUTE = "mute"
    const val ID_STOP = "stop"

    /** 全部菜单项，顺序即**默认**顺序（与改造前菜单自上而下逐项对应） */
    val ALL: List<Item> = listOf(
        Item(ID_CHAT, "和小Ling聊聊", pinned = true),
        Item(ID_NOTE, "截图速记"),
        Item(ID_TAPMODE, "双击模式"),
        Item(ID_REGION, "框选识别", sepBefore = true),
        Item(ID_AUTOSCAN, "自动扫描"),
        Item(ID_WORD, "弹单词"),
        Item(ID_HISTORY, "历史记录…"),
        Item(ID_TRANSLATE, "翻译刚复制的"),
        Item(ID_SETTINGS, "设置…", pinned = true, sepBefore = true),
        Item(ID_MUTE, "免打扰", sepBefore = true),
        // v2.4：「（保留授权）」是给第一次看到的人解释用的，但菜单里每个字都挤，
        // 而且这个说明在设置页和通知里都写过一遍了。菜单只留动作本身。
        Item(ID_STOP, "停止小球", pinned = true),
    )

    val DEFAULT_ORDER: List<String> get() = ALL.map { it.id }

    fun byId(id: String): Item? = ALL.firstOrNull { it.id == id }

    /**
     * 把用户存的排列清洗成一条能用的顺序。
     *
     * 规则：
     *  - 不认识的 id 丢掉（旧版本删掉的项）
     *  - 重复的只留第一次出现
     *  - **用户排的顺序原样保留** —— 只做两条最小保证：
     *      「和小Ling聊聊」永远第一；「设置」「停止小球」一定存在（但不强求在末尾）。
     *  - 用户没主动隐藏的可选项，缺了就插到它在默认顺序里的**前一个邻居后面**（= 新版本新增的功能）
     *  - 结果为空 → 回落到默认顺序
     *
     * [hidden] 是用户**主动隐藏**的项。没有它的话「用户删掉的项」和「新版本新增的项」分不开 ——
     * 前者该保持隐藏，后者该自动出现。第一版把它俩混在一起（一律补回末尾），结果用户删了的项
     * 一保存就自己长回来，等于隐藏功能根本没生效。
     *
     * 第二版又矫枉过正，把「设置 / 停止」强行钉死在最后两个位置 —— 用户把「停止小球」拖到
     * 「设置」前面再保存，顺序就被打乱了。现在只保证**存在**，不规定位置。
     */
    fun resolve(saved: List<String>?, hidden: Set<String> = emptySet()): List<String> {
        if (saved.isNullOrEmpty()) return DEFAULT_ORDER
        val seen = LinkedHashSet<String>()
        val kept = saved.filter { byId(it) != null && seen.add(it) }.toMutableList()
        if (kept.isEmpty()) return DEFAULT_ORDER

        // 「聊天」放第一：用户在页面里本来就拖不动它（它没有上移按钮），这里再兜一次底
        kept.remove(ID_CHAT)
        kept.add(0, ID_CHAT)

        // 「设置」「停止小球」不能在（用户旧数据里可能没有这两个 id —— 早期版本没存常驻项）
        if (ID_SETTINGS !in kept) kept.add(ID_SETTINGS)
        if (ID_STOP !in kept) kept.add(ID_STOP)

        // 可选项里「用户没见过且没主动隐藏」的补进来（新版本新增的功能）。
        //
        // v2.4 改法：不再一律追加到尾部，而是**插到它在默认顺序里的前一个邻居后面**。
        // 原因：v2.4 新增的「截图速记」是核心功能，追加到尾部等于排在一堆开关后面，
        // 用户得先滑过「弹单词」「历史记录」才点得到它 —— 新功能加了却没人用得上。
        // 按邻居插入后，它出现在「和小Ling聊聊」下面，正是设计意图里的位置。
        DEFAULT_ORDER.forEach { id ->
            if (byId(id)?.pinned == true || id in kept || id in hidden) return@forEach
            val idx = DEFAULT_ORDER.indexOf(id)
            // 往前找第一个已经在 kept 里的项，插到它后面
            var at = -1
            for (j in idx - 1 downTo 0) {
                val prev = DEFAULT_ORDER[j]
                val pos = kept.indexOf(prev)
                if (pos >= 0) { at = pos + 1; break }
            }
            if (at >= 0) kept.add(at, id) else kept.add(kept.indexOf(ID_CHAT) + 1, id)
        }

        return kept
    }

    /** 这一项能不能被用户隐藏（常驻项不能） */
    fun canHide(id: String): Boolean = byId(id)?.pinned != true

    // ---------------- v2.4 昵称 ----------------

    /** 昵称长度上限。菜单宽 236dp、字号 14sp —— 再长就会被截成「你设的昵称…」，不如一开始就拦住 */
    const val NICK_CHARS = 8

    /**
     * 菜单上这一项实际显示什么字：用户设了昵称就用昵称，否则用默认标题。
     *
     * 单独抽成函数是因为有三个地方要用（菜单渲染、菜单排列页、测试），
     * 而「空串 = 没设」这条规则散在三处迟早会写歪。
     */
    fun labelOf(id: String, nick: Map<String, String>): String {
        val own = nick[id]?.trim().orEmpty()
        return if (own.isEmpty()) byId(id)?.label ?: id else own
    }

    /** 清理用户填的昵称：去掉首尾空白、按显示宽度截断。空串表示「恢复默认」 */
    fun normNick(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        // 按 code point 截，避免把一个 emoji / 生僻字劈成半个（劈开会显示成方块）
        val n = t.codePointCount(0, t.length)
        if (n <= NICK_CHARS) return t
        return t.substring(0, t.offsetByCodePoints(0, NICK_CHARS)).trim()
    }

    // ---------------- v2.7 双击方式 ----------------

    /** 双击小球：先问问题（缺省）/ 直接做题 / 直接单词翻译 */
    const val TAP_ASK = "ask"

    /**
     * 直接做的那两档**值就是模式 id**：它们要做的事就是「按这个模式识别一遍、只是不问」，
     * 另起一套 token 再映射一遍只会在模式改名时留下一个对不上的地方。
     */
    const val TAP_EXAM = Prompts.MODE_EXAM
    const val TAP_WORD = Prompts.MODE_WORD

    /** 切换顺序：先问问题 → 做题 → 单词翻译 → 先问问题 */
    val TAPS = listOf(TAP_ASK, TAP_EXAM, TAP_WORD)

    /**
     * 把存着 / 传进来的双击方式清洗成三种之一。
     * 空、null、老版本没这个键、手改配置文件写成别的 —— 一律回落到「先问问题」：
     * 那是最不打扰人的一种（先问再发），不会因为一条坏数据就让小球开始自作主张。
     */
    fun normTap(raw: String?): String = if (raw != null && raw in TAPS) raw else TAP_ASK

    /**
     * 菜单右侧、Toast 里显示的说法。
     * 直接做的那两档，值就是模式 id（[TAP_EXAM] / [TAP_WORD]），标签跟模式表走 ——
     * 模式名改了这里不用跟着改，面板 / 历史里的标签也天然对得上。
     */
    fun tapLabel(v: String): String {
        val t = normTap(v)
        if (t == TAP_ASK) return "先问问题"
        return Prompts.MODE_LABEL[t] ?: t
    }

    /** 点一下切到下一档（菜单里就地切换用） */
    fun nextTap(v: String): String {
        val i = TAPS.indexOf(normTap(v))
        return TAPS[(i + 1) % TAPS.size]
    }
}
