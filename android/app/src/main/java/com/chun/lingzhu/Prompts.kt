package com.chun.lingzhu

/**
 * 提示词：结构对齐桌面版 prompts.js（generalPrompt / examPrompt / translatePrompt），
 * 手机版在此基础上做了 v1.76 的做题质量修复（见下）。改这里时记得同步桌面版。
 * v2.7.3：识图提示词收成最小任务 —— 不再拼人格 / 共用风格层 / 篇幅纪律（personaBlock 已删）。
 * 做题模式仍保留完整做题要求与格式（LaTeX / 多题 / 看不清不猜 / 按档解析强度）。
 * 聊天（ChatStore）与人格（Personas）仍用 [PERSONA]，翻译链仍用 [STYLE_SHARED]，两者正文一字未动。
 *
 * v1.76 做题质量修复（用户反馈「默认提示词差」）：
 *  - 正确完整优先：答案先行，再给**能核验**的解题步骤；不要求模型描写内心推理。
 *  - 按题型走：不硬套计算、不要求每道选择题都逐个排除选项、不硬塞「易错点」。
 *  - 篇幅按难度 / 题数调节：删掉「不超过 150 / 400 字」硬封顶（它会裁掉多题和证明），
 *    不是把上限放大，而是改成内容驱动。**只对「做题 / 遇到题目」**：
 *    自定义模式按用户自己写的要求，通用的「没题目」保持简短 —— 别拿做题型的内容长度去改这两类的既有口径。
 *  - 图里字/符号/图形看不清：不许猜一个当真（更要命的是给好几套虚构答案），说清缺哪项、只答可确定部分、请他补图（见 UNCLEAR_RULE）。
 *  - 保留契约不动：三段式标题「## 答案 / ## 解析 / ## 考点」、纯小白独立格式、多题 `### 第 N 题`、
 *    关键词清单 TERMS_RULE、人格 PERSONA、what/why、directive 覆盖层优先级、模式集合。
 *
 * v1.89 翻译重做（用户要「完整翻译 + 语言点解析」，不是一行直译）：
 *  - 按**粒度**输出：单词 / 短语 / 句子 / 段落各有一套内容（见 [translate]），译文永远在最前；
 *  - 单词给词性、多义项（结合上下文标最可能的那条）、词形变化与搭配区别、≥2 条双语例句；
 *    短语给结构 / 语域 / 搭配 / 例句；句子给关键词语的语境义 + 结构 + 语气 + 变体；段落给译文 + 要点，
 *    不逐个词查词典。音标只在英文且读音有把握时给，不编词源 / 读音 / 事实，不写用不上的段落；
 *  - 篇幅不再有「一行补充」「2-3 个搭配」这类硬性短上限（[translate] 里已删）；
 *  - 共用风格层 [STYLE_SHARED]（结论先行 / 能核验的事实推机制 / 短句高密度 / 积极换行但不机械断句）；
 *  - 台词：DeepL / 百度只会给直译，配了 AI 时由 [translateEnrich] 在**不改动所选引擎译文**的前提下补解析。
 *
 * v1.90 讲法约束补齐（识别与翻译共用 [STYLE_SHARED]，只加规则、不改任何格式契约）：
 *  - 由浅入深 + 第一性原理（从基本事实 / 定义 / 假设推出，约定与经验明说）；
 *  - 新术语先一句大白话定义，再有帮助时给贴切类比并点明边界，不为每个词硬造例子；
 *  - 证据不足直说「不确定 / 缺哪项」，不把猜测写成事实，不编引用；
 *  - 不寒暄开头、不总结收尾、不用陈词滥调；除模式已指定的固定标题外不另加机械标题、不设固定字数；
 *  - 「由浅入深」是用户指定的讲法取向，不署名任何作者、不假称来自 GitHub。
 *  - 同步改动：[TRANSLATE_PROMPT_VERSION] 2 → 3（识别与翻译共用这一段，缓存键随之失效）。
 *
 * v1.91 分段口径收口（识别与翻译共用 [STYLE_SHARED]，仍只改这一段，不动其他约束与格式契约）：
 *  - 删掉「不要在逗号、分号或破折号处断行 —— 断行只由段落语义决定」整条，不再单列标点断行禁令；
 *  - 并入原「句子和段落都短」那条，改成「句子短、段落短、信息密度高；一个观点、一层逻辑、一个转折或一个重要判断，
 *    都可以另起一段。按语义、节奏和视觉可读性分段，积极换行、避免文字墙，但不要机械地一句一段」；
 *  - 不是换成「允许在标点处断行」，也不保留反向硬禁令；其余讲法约束一字未动；
 *  - 同步改动：[TRANSLATE_PROMPT_VERSION] 3 → 4（识别与翻译共用这一段，缓存键随之失效）。
 *
 * v2.6.3 翻译来源分明（auto 不再 AI 优先，见 [Translate.plan]）：
 *  - [translate] / [translateEnrich] 都加「术语与延伸」段：专业术语按「一句定义 → 真正相关的背景 / 机制 → 边界」写；
 *    医学 / 专业表述必须分清「定义 / 关联背景 / 不等于对某个人的诊断」（用户举的例子是「高尿钙」），不编临床数据；
 *  - 「可以详，不是必须长」：讲清就收，不为了凑长写与原文无关的科普；
 *  - [translateEnrich] 的正文固定以「## AI 解析」开头 —— 卡片上机器译文在上、AI 补的解释在下，来源一眼分得开；
 *  - 同步改动：[TRANSLATE_PROMPT_VERSION] 4 → 5（结构变了，旧缓存不再命中）。
 *
 * v2.8 中英互译（用户要求「默认在现有完整解析之外追加中英互译」）：
 *  - 目标语言是自动 / 中文 / 英文时（[wantsBilingual]），[translate] 与 [translateEnrich] 都要求在全部解析之后
 *    追加一段 [BILINGUAL_HEAD]：英文一行、中文一行；
 *  - 原文和所选引擎的译文本来就一英一中时，App 直接拿两边原话拼（[bilingualPair]），零成本、没配 AI 也有，
 *    且**绝不改动**所选引擎给的译文；只有两者同语种时（如中文原文 + 「翻译成中文」）才让 AI 真翻出缺的那一门；
 *  - 该有却没有时如实说明（[missingPairNote]），不拿同一句话冒充互译；那一轮也不写进缓存，下次还会重试；
 *  - 缓存键版本 5 → 6：旧缓存里没有这一段，不能继续命中。
 *
 * 单词翻译（双击小球三态里的第三档，见 OrbMenu.TAPS）：
 *  - 新常量 [MODE_WORD] / `MODE_LABEL["word"] = "单词翻译"`，正文见 [word]：先在图里认出用户关注的那**一个**词，
 *    再按电脑版悬停小词典的口径出紧凑小卡（音标 / 词性 → 核心释义 → 原文 + 译文例句），默认中英互译；
 *  - 它**不是**可选的内置模式：[MODES] / Modes.BUILTIN 都不含它（设置页与面板抽屉不多这一项），
 *    只有双击那一路把这一轮强制成它；[Modes.normalize] 仍认它，不会把存进去的 word 改回通用；
 *  - 篇幅纪律写在 [word] 自己身上（一张小卡），且不拼 [STYLE_SHARED]（那一层会把卡片撑成讲解）；
 *  - 关键词清单行不拼（见 [wantsTerms]）—— 讲一个词还列 3~8 个关键词既跑题又撑长卡片，
 *    补问的兜底条件也改用同一个判据，否则每查一个词都会白发一次请求；
 *  - 词卡 / 剪贴板 / DeepL 那条链（[translate] / WordDict.kt）**一行未动**；不新增强制中英以外语言的策略。
 */
object Prompts {
    const val MODE_GENERAL = "general"
    const val MODE_EXAM = "exam"
    const val MODE_CUSTOM = "custom"
    /**
     * 单词翻译：双击小球三态里的第三档（见 OrbMenu.TAPS），不是设置里可选的内置模式 ——
     * 所以它**不**进 [MODES] / Modes.BUILTIN：设置页的「识别模式」与面板的模式抽屉不会多出一项，
     * 只能由双击把这一轮强制成它（OrbService 里 forceMode，不写回 Prefs.mode）。
     * Modes.normalize 照样认它：万一存进去了（手改配置 / 以后真开放），不会被悄悄改回「通用」。
     */
    const val MODE_WORD = "word"
    val MODES = listOf(MODE_GENERAL, MODE_EXAM, MODE_CUSTOM)
    val MODE_LABEL = mapOf(MODE_GENERAL to "通用", MODE_EXAM to "做题", MODE_CUSTOM to "自定义", MODE_WORD to "单词翻译")

    val DETAILS = listOf("brief", "standard", "detailed")
    val DETAIL_LABEL = mapOf("brief" to "简略", "standard" to "标准", "detailed" to "详细")

    val THINK_EFFORTS = listOf("off", "low", "high", "max")
    val THINK_LABEL = mapOf("off" to "关", "low" to "低", "high" to "高", "max" to "最高")

    data class Lang(val id: String, val label: String, val rule: String)

    val LANGS = listOf(
        Lang("auto", "自动", "判断原文语种：是中文就译成英文，不是中文就译成简体中文（中英互译）"),
        Lang("zh", "中文", "一律译成简体中文"),
        Lang("en", "英文", "一律译成地道的英文"),
        Lang("ja", "日文", "一律译成自然的日文（必要时保留汉字写法）"),
        Lang("ko", "韩文", "一律译成自然的韩文"),
        Lang("ru", "俄文", "一律译成俄文"),
        Lang("fr", "法文", "一律译成法文"),
        Lang("de", "德文", "一律译成德文"),
    )

    fun lang(id: String): Lang = LANGS.firstOrNull { it.id == id } ?: LANGS[0]

    private class DetailRule(val analysis: String, val point: String)

    /**
     * v1.76：解析档位不再按「固定步数 / 必须逐个排除选项」写死，改成按题型给必要步骤 ——
     * 用户反馈的毛病正是「所有题都硬套计算 / 全错项排除 / 易错点」。三段式标题由 exam() 提供，这里只管两段正文。
     */
    private val DETAIL_RULES = mapOf(
        "brief" to DetailRule(
            "给出答案所需的关键依据或那一步关键判断就够了，能核验就行，不必展开全过程。",
            "一句话点出本题考的考点。"
        ),
        "standard" to DetailRule(
            "按题型给必要步骤：该算的把关键算式写出来，该说理的把理由说清，用 1. 2. 3. 编号分步。选择题只说明真正容易混的干扰项为什么不对，没有明显干扰项就不必硬凑。",
            "一句话点出本题考的知识点；这题确实有典型易错的地方，再补一句。"
        ),
        "detailed" to DetailRule(
            "按题型把过程写完整：编号分步，该算的每一步都写出来，该论证的每一步都给出依据。选择题逐个分析选项（对在哪 / 错在哪），填空、解答、证明题给完整推导。",
            "写出本题考的知识点、要用到的公式或概念；有典型易错点就列出来，再用一两句话说明这类题的通用做法。"
        ),
    )

    /**
     * v1.58：面板会排版 LaTeX 了（MathText）—— 高数题用纯文本写分式/积分挤成一行没法看，改成让模型写 LaTeX。
     * v1.76：只在数学/理科题才要求 LaTeX；并去掉「一行只写一个等号」的死板要求（几何/证明/文科步骤不需要，也逼模型把本该连贯的推导拆碎）。
     */
    const val MATH_RULE = "只有数学、理科的公式才用 LaTeX：行内用 \$…\$，单独成行的式子用 \$\$…\$\$（分式 \\frac、根号、极限、积分、求和、上下标都照常写，面板会排版）；" +
        "推导该几步就几步，不必把每个等号都单独占一行"

    fun normDetail(d: String?): String = if (d in DETAILS) d!! else "standard"

    // ================= v1.67 陪伴 M1：人格与语气 =================

    /**
     * 小珠人格（v1.67，唯一一份）：所有模式共用这一段语气，替代以前逐处贴的「不要寒暄」补丁。
     * 三条取舍写在下面，改的时候别丢：
     *  - 「不要每次结尾问还需要什么」—— 每句结尾推销下一步，像客服，陪伴感最大的杀手。
     *  - 「记得但少提」—— 不加这条，模型会反复复述「你在准备考研」，两轮就油腻。
     *  - 不写「像人」—— 干练靠句式约束就够了，硬塞人格表演只会翻车。
     * 只给聊天 / 人格回落用：识图提示词（v2.7.3 起）不再拼这一段。
     */
    const val PERSONA = "你是小Ling，住在用户手机里的悬浮球。你在替用户解决他正对着屏幕遇到的题目和疑问。\n" +
        "- 身份边界：这个应用叫 Ling，你是它里面的助手小Ling。用户问「Ling 是什么」时说应用，自称永远是小Ling。\n" +
        "\n说话方式：\n" +
        "- 干练。给结论，再给依据。不要铺垫、不要过渡、不要总结。\n" +
        "- 句子短，一句一件事。手机上不用长段落。\n" +
        "- 不用敬语，不用「好的」「当然」「没问题」「希望对你有帮助」「让我来帮你分析一下」。\n" +
        "- 不说「显然」「易得」「不难看出」（小白档已有这条，别重复）。\n" +
        "- 不用颜文字、不用感叹号、不写「~」。\n" +
        "- 不要每次结尾都问「还需要我做什么」。只在用户确实有一个明确的下一步没做时，用一句话说出来。\n" +
        "\n分寸：\n" +
        "- 你记得用户的处境，但不每次都提。记忆里有他正在学的东西，只有当它和这次的画面真的有关时才提，一句话带过；无关就当没存。\n" +
        "- 不要寒暄、不撒娇、不表达情绪、不说自己像人。"

    /**
     * v1.89 表达风格（PERSONA 之外的共用风格层，移动端与桌面版共用同一份口径）：
     * 结论或大白话例子先行 → 再从**能核验**的事实 / 定义 / 假设 / 因果一步步展开机制 → 只留真正有用的进阶细节与边界。
     * 换行按语义、节奏和视觉可读性走，积极换行但不机械地一句一段；不泄露内心推理、不编造引用。翻译用这一段。
     * 写法守一条纪律：全部写成模型能照做的祈使句，别写「要更有洞见一点」这种没法执行的形容词。
     *
     * v1.90 补入四条讲法约束（由浅入深 / 类比带边界 / 证据不足直说 / 第一性原理），只取**约束**，
     * 不含任何作者文风模仿、警告口吻或强制标题 / 字数 / 思考链：
     *  - 允许「不知道」并先找证据（证据不足就说缺哪一项，不编引用）—— **不是**要求每问都先道歉或免责；
     *  - 新术语先一句大白话定义，再给有帮助且不误导的类比并点明类比边界；不为每个词硬造例子；
     *  - 讲法取向是用户指定的「由浅入深」，**不署名任何作者、不假称来自 GitHub 认证**；
     *  - 复用已有约束，不重复：能核验 / 分段按语义节奏 / 不输出思考过程本就在下面的条目里，这里只加新语义。
     *
     * v1.91 分段口径收口：删掉单列的「不要在逗号、分号或破折号处断行」那条（它属于标点停顿类规则），
     * 把「按语义、节奏和视觉可读性分段、积极换行」并进下面原「句子和段落都短」的那一条；
     * **不是**新增「允许在标点处断行」的规则，也**不保留**反向硬禁令。其余条目一字未动。
     */
    const val STYLE_SHARED = "表达风格（所有输出都照这个来）：\n" +
        "- 由浅入深：先给结论，或先用大白话举一个能立刻看懂的例子，再一步步展开背后的机制；每一步都落在能核验的事实、定义、假设或因果关系上，不跳步、不臆断。\n" +
        "- 用第一性原理：讲一个概念就从最基本的事实、定义或假设推起，一步步推出结论；某个前提只是约定或经验结论时，明说它是约定，别当成推出来的。\n" +
        "- 出现新术语时，先用一句大白话把它定义清楚；真有帮助再给一个贴切、不误导的类比，并点明这个类比对应到哪、在哪里就不成立；不为每个词硬造例子，用不上的例子不写。\n" +
        "- 证据不足就直说「这一点我不确定」或「缺的是哪一项」，不把猜测写成事实，不编引用、文献、数据或来源。\n" +
        "- 只补真正有用的进阶细节、边界和例外；可有可无的背景、术语堆砌和套话一律不写，不用陈词滥调，也不要有寒暄式开头和总结式收尾。\n" +
        "- 句子短、段落短、信息密度高；一个观点、一层逻辑、一个转折或一个重要判断，都可以另起一段。按语义、节奏和视觉可读性分段，积极换行、避免文字墙，但不要机械地一句一段。\n" +
        "- 不输出自己的思考过程或内心推理；除模式已明确要求的固定标题外，不另加机械小标题，也不设固定字数。"

    /**
     * v1.76：正文的固定字数上限（原 CHARS_BRIEF / CHARS_STANDARD）已**删除** —— 固定字数会把
     * 「多道题只写一道」「证明写一半」直接裁断，正是用户反馈的差体验；删后再无消费点，留成死常量只会误导。
     * 追问的 CHARS_FOLLOWUP 仍在用（追问是接话，简短是合理的）。
     */
    const val CHARS_FOLLOWUP = 120

    /**
     * 记忆卡段（v1.67）：称呼 / 正在做 / 最近在看，进 system 而不是 messages（命中服务端前缀缓存）。
     * 顺序在模式正文之后、directive 与 TERMS_RULE 之前：输出格式 → 记忆。
     * 空卡不拼 —— 老用户零变化。没有 token 上限可言，写盘时卡本身就限死在 2KB / 400 汉字内。
     */
    fun cardBlock(card: Card?): String {
        if (card == null || card.isEmpty) return ""
        val lines = ArrayList<String>(4)
        lines.add("## 关于用户（这是你从本机记住的，可能已经过时）")
        if (card.name.isNotBlank()) lines.add("- 他叫：" + card.name)
        if (card.goal.isNotBlank()) lines.add("- 他正在做：" + card.goal)
        if (card.topics.isNotEmpty()) lines.add("- 最近在看：" + card.topics.joinToString("、") { it.text })
        lines.add("用到和这次画面相关的那一条时，一句话带过；无关就不要提。")
        return lines.joinToString("\n")
    }

    /**
     * 进程内的记忆卡快照（v1.67）：OrbService 建服务 / 设置页改动时刷新，system() 每次读它。
     * 做成字段而不是 system() 的参数 —— Solver 是降成本那条线在动的文件，加参数就得改它，不值当。
     */
    @Volatile var card: Card? = null

    /**
     * v1.75 解析透彻度 / v1.76 按题型走：三条约束各占一个常量，别合成一整段 ——
     * 合成一坨就没法按档裁，也没法在单测里单独断言某条还在。
     * 写法守一条纪律：全部写成模型能照做的祈使句，不写「要更详细一点」这种没法执行的形容词。
     *
     * v1.76 改动点：链式约束从「不许跳步的完整推理」改成「**能核验**的必要步骤」——
     * 用户要的是可核对的解题步骤，不是让模型把内心推理全过程写出来；「显然/易得」照旧禁（那不是依据）。
     */
    const val CHAIN_RULE = "解题步骤要能核验：先摆出已知条件和要用的依据（公式、定理、语法点或定义），再给出必要的中间步骤，最后落到答案；" +
        "关键步骤不能省，也不许用「显然」「易得」「代入计算得」这类话代替具体依据。步骤多少按题型来，不用把心里想的每一步都写出来"
    const val SUBQ_RULE = "题目有多个小问（(1)(2)、①②、a./b.）时按小问分开作答：每问独立成一段，先给这问的答案再给这问的推导，不要几问挤在一段里"
    /** v1.76：易错点不再硬塞 —— 没明显易错点的题（大部分概念题 / 判断题）硬凑一句反而像套模板，让人不信 */
    const val PITFALL_RULE = "「## 考点」这段：这题确实有常见易错点，就在最后补一句「易错点：……」，没有就不写，别硬凑"

    /**
     * 易错点按档给长度：短档就一句话（不然三段式里最容易肿起来的就是它），
     * detailed 那一档才允许展开 —— 写「（简略/标准档就一句话）」这种档位说明会漏进提示词正文，别那么写。
     */
    fun pitfallRule(detail: String): String = PITFALL_RULE + when (normDetail(detail)) {
        "brief", "standard" -> "（一句话，不展开）"
        else -> "（这一步说清错在哪、怎么避）"
    }

    /**
     * v1.76：图里字 / 符号 / 图形看不清时的统一纪律。用户反馈最伤信任的一类错，就是模型拿猜出来的条件
     * 当成题目照做、甚至一次给好几套「可能答案」。这里要求：说清缺哪项、只答可确定部分、必要时让他补图。
     * v1.88 加固：实测「不要同时给好几套可能的答案」这句不够硬 —— 模型照样把几种可能各写一套（「若是…则…」）。
     * 现在直接把「列分支 / 摆多套」这种写法点名禁掉，并把出口收敛成一句：只给一个能确定的答案 + 要清晰图。
     */
    const val UNCLEAR_RULE = "画面里的字、符号或图形看不清时，不要猜、也不要把猜出来的当成题目条件往下做：先一句话说清「哪一项看不清」，只对能确定的部分作答；" +
        "如果看不清的地方会改变答案，就直接说需要一张更清晰的照片。**只给一个能确定的答案，不给多套**：" +
        "不许用「若是……则……」「如果……就是……」「假如是 A 就答 X、是 B 就答 Y」这类条件分支句把几种可能的结果都列出来，" +
        "也不许分「情况一 / 情况二」摆好几套答案 —— 拿不准就直接说「这一项看不清」并请他补一张清晰的图，不要凑一套或几套猜的答案充数"

    /**
     * v1.76：链式约束按档加一句「省什么、不省什么」。短档最容易翻车 —— 模型会把每步写成一小段。
     * 注意这里只说「必要的依据不能省」，不再说「每一步」—— 步骤多少交给题型（见 CHAIN_RULE），别又变回死板要求。
     * detailed 不加这句：那一档本来就要求完整。
     */
    fun chainRule(detail: String): String = CHAIN_RULE + when (normDetail(detail)) {
        "brief", "standard" -> "。该省的是铺垫、复述题干和形容词，必要的依据不能省"
        else -> ""
    }

    /**
     * 通用模式（v2.7.3）：只留最小任务，不再贴人格 / 风格层 / 篇幅纪律。
     * detail 收着但不改正文 —— 保留参数只为不改 callsite（Solver / 面板 / 测试同一入口）。
     * 纯小白只多一句通俗要求，不另起一套风格块。
     */
    @Suppress("UNUSED_PARAMETER")
    fun general(detail: String, novice: Boolean): String =
        "这是当前手机屏幕截图。识别并说明画面内容。" +
            (if (novice) "\n用大白话解释，遇到术语先说明含义。" else "")

    fun noviceExam(detail: String): String {
        val d = normDetail(detail)
        val secs = mutableListOf("## 答案\n直接给结论。选择题：正确选项字母 + 选项内容（例：B. 鲸鱼）；其他题：最终结果。")
        if (d != "brief") {
            secs.add("## 这题在问什么\n用一两句大白话说出题目想让我们干什么（不要照抄题干）。")
            secs.add("## 先弄懂\n做这题必须懂的词或符号，1~3 个，每个一行：「词」= 大白话解释。都很简单就省掉这一段（连标题一起省）。")
        }
        secs.add("## 一步步做\n用 1. 2. 3. 编号" + (if (d == "brief") "，最多 3 步" else "") + "。每步一行，写「做什么 —— 为什么这么做」；有计算就把算式完整写出来。" +
            (if (d == "brief") "做题必须懂的词，直接在步骤里用括号解释。" else ""))
        secs.add("## 其他选项错在哪\n只有选择题才写这一段（不是选择题就连标题一起省掉）。每个错项一行：「A 错：……」，一句大白话。")
        secs.add("## 记住这句\n一句能背下来的规律，再加一句「最容易错在……」。")
        if (d == "detailed") secs.add("## 举一反三\n出一道同类的小题（只写题目和答案），再用一句话说这类题的通用做法。")
        return "用户把当前手机屏幕截图发给你，题目就在截图里。\n" +
            "找到画面里的题目并解答，严格按下面的格式输出，标题原样照抄（含 ##），顺序不变：\n\n" +
            secs.joinToString("\n\n") + "\n\n" +
            "硬性要求：\n" +
            "- 不复述整道题干；\n" +
            "- 正确、完整优先：拿不准的就说清哪里不确定，不要编；\n" +
            "- $UNCLEAR_RULE；\n" +
            "- $MATH_RULE；\n" +
            "- " + chainRule(d) + "；\n" +
            (if (d == "detailed") "- 中间计算不许省：算式里的每一步都要写出来，别只给结果；\n" else "") +
            "- 画面里确实没有题目时：第一行只输出「未发现题目」，另起一段用两三句大白话说明画面内容；\n" +
            "- 画面里有多道题时，每道题重复一次上面的格式，并在「## 答案」前加一行 `### 第 N 题`（N 从 1 开始），每道题都要答、不要漏；\n" +
            "- 同一道题的（1）（2）小问不算多道题：放在同一套格式里，「## 答案」和「## 一步步做」里分小问写。"
    }

    /**
     * 考试 / 做题模式（v2.7.3）：移除人设与寒暄套话，保留做题要求、三段式标题、多题标记、
     * 按档解析强度、LaTeX、看不清不猜。novice 走独立的纯小白格式。
     */
    fun exam(detail: String, novice: Boolean): String {
        if (novice) return noviceExam(detail)
        val r = DETAIL_RULES.getValue(normDetail(detail))
        return "用户把当前手机屏幕截图发给你，题目就在截图里。\n" +
            "把正确、完整放在第一位：答案要准、要给全给完，拿不准的宁可说清哪里不确定，也不要编。\n" +
            "找到画面里的题目并解答，输出必须严格用下面三段式，标题原样照抄（含 ##）：\n\n" +
            "## 答案\n" +
            "直接给结论。选择题：正确选项字母 + 选项内容（例：B. 鲸鱼）；多选/填空/简答/计算：给最终结果。\n\n" +
            "## 解析\n" +
            r.analysis + "\n\n" +
            "## 考点\n" +
            r.point + "\n\n" +
            "硬性要求：\n" +
            "- 不重复题干；\n" +
            "- " + chainRule(detail) + "；\n" +
            "- $SUBQ_RULE；\n" +
            "- " + pitfallRule(detail) + "；\n" +
            "- $MATH_RULE；\n" +
            "- $UNCLEAR_RULE；\n" +
            "- 篇幅跟着题目走：一道题写透，多道题每道都写全，别为压字数漏题或漏步骤；\n" +
            "- 画面里确实没有题目时：第一行只输出「未发现题目」，另起一段用两三句话说明画面内容；\n" +
            "- 画面里有多道题时，每道题重复一次上面的三段式，并在「## 答案」前加一行 `### 第 N 题`（N 从 1 开始），每道题都要答、不要漏；\n" +
            "- 同一道题的（1）（2）小问不算多道题：放在同一套三段式里，「## 答案」和「## 解析」里按小问分开写。"
    }

    /**
     * 单词翻译（双击小球三态里的第三档，见 OrbMenu.TAPS）：
     * 用户双击小球截屏时，画面里有他正盯着的一个词 —— 只要这个词的一张小卡，**不**把整屏当题目、
     * 也**不**把画面里的长句整段翻译（那是「翻译刚复制的」/[translate] 与通用模式的活）。
     *
     * 口径对齐电脑版悬停小词典（main.js 的 wordPrompt，见 [WordDict.PROMPT_HEAD] 那份逐字契约），
     * 但手机版给的是「图里认词」而不是「已知一个词」，所以多一条：先从画面里找出用户关注的那个词。
     * 输出结构是用户定的三项（音标 / 词性 → 核心释义 → 原文 + 译文例句），不是做题的三段式，也不是段落翻译。
     *
     * 与 [translate] 的分工：那条链（词卡 / 剪贴板 / DeepL）**不动**；这里只是识图后在面板里出同款小卡。
     * 不强制中英之外的语言：默认中英互译，原文不是中英文时按它最常用的中文意思给（沿用 [LANGS] 的 auto 口径）。
     */
    fun word(novice: Boolean = false): String =
        "你是双语小词典。用户截了一张手机屏幕，画面里有他正在看的一个词或短语。\n" +
            "先从画面里找出他关注的那一个词（他正指着 / 框着 / 悬停的那个词，或者是画面里唯一像生词的那个），只讲这一个词。" +
            "不要把整块屏幕当成题目，也不要照抄或整段翻译画面里的长句。\n\n" +
            "翻译方向：英文词 → 给中文释义；中文词 → 给英文说法（默认中英互译；别的语言的词按它最常用的中文意思给）。\n\n" +
            "输出一张紧凑的小词典卡片，就是下面这几行，不加小标题、不加序号、不复述这些要求：\n" +
            "- 第一行：这个词本身 + 音标（只有英文词、且读音有把握时才给，用 /.../ 包住；中文词或拿不准就不写）+ 词性缩写；\n" +
            "- 接着一到三行核心释义，一行一条，最常用的在前；这个词在画面语境里明显取某一条时，点明是这一条；可以带一两个最短的常见搭配；\n" +
            "- 最后一到两行例句：「原句 — 译文」。例句要自然常见，不要生造。\n\n" +
            "看不清时：画面里找不到一个明确的词，或者那个词糊得认不出字母 → 只输出一句「画面里没看到能确认的单词」或" +
            "「这个词看不清，请放大后重新截图」，然后停在这里；不许顺着模糊的轮廓猜一个词再替它编释义、音标或例句。\n\n" +
            "纪律：释义、词性、音标、例句都必须是这个词真实常见的用法，拿不准的那一项就不写；不编词源、不编读音、不编例句出处；" +
            "除上面几行外不写别的段落，不写寒暄，不复述屏幕上的其它内容。" +
            (if (novice) "\n释义和例句都用最常用、最简单的说法，别为了解释一个词再引入一串生词。" else "")

    /** 自定义模式没填提示词时用这个；设置页「填入示例」也填它。v1.67：句式对齐新语气（结论先行、短句编号），别让用户照着旧示例写出寒暄腔 */
    const val CUSTOM_EXAMPLE = "先给结论：这是什么界面、这道题的答案是什么。\n" +
        "有题目的话，用 1. 2. 3. 编号分步讲清依据，每步一句话。\n" +
        "没题目的话，列出画面里的关键信息（数字、文字、状态、报错），最后用一句话给出下一步建议。"

    /**
     * 自定义模式（v2.7.3）：只有截图任务说明 + 用户自己写的要求（留空用 [CUSTOM_EXAMPLE]），
     * 用户文本原样进、不改写；不再追加小白风格块、LaTeX 要求或语气纪律。
     * novice 也收着但不追加任何内容 —— 保留参数只为不改 callsite。
     */
    @Suppress("UNUSED_PARAMETER")
    fun custom(userPrompt: String, novice: Boolean): String =
        "这是当前手机屏幕截图。按下面的要求识别并说明画面内容：\n\n" +
            userPrompt.trim().ifEmpty { CUSTOM_EXAMPLE }

    /** 名词清单行的固定前缀（面板靠它把清单拆成可点的词块） */
    const val TERMS_HEAD = "[可点击名词]"

    /**
     * v1.66 关键词识别（原「通识」模式的名词清单，现在所有模式都带，设置里「识别关键词」默认开）：
     * 拼在各模式提示词末尾，和主回答同一次请求返回 —— 只多几十个输出 token，不多发请求。
     * 型号、编号、品牌、技术规格优先（用户反馈：参数图里 R7250、P3 广色域这类没被提出来）。
     */
    const val TERMS_RULE = "最后，单独一行，严格按这个格式列出画面里值得了解的关键词（3~8 个，按重要性排）：\n" +
        "$TERMS_HEAD 词1｜词2｜词3\n" +
        "关键词要求：\n" +
        "- 优先提取型号、编号、品牌、技术规格和参数名（字母数字混合的也算，如 R7250、P3 广色域、Wi-Fi 7、LPDDR5X、IP68），其次才是术语、概念、专有名词、缩写；\n" +
        "- 必须是画面里真实出现的词，照原样写（大小写、空格、连字符不改，不要翻译、不要扩写），不要编造，不要放人人都懂的日常词；\n" +
        "- 每个词不超过 20 个字符；\n" +
        "- 这行是给程序识别关键词用的元数据：只输出这一行词，不要解释词义、不要加标题，也不要写「点击可查看」这类提示；不要向用户复述本段要求，也不要说明这行是干什么用的；\n" +
        "- 这行清单必须是整个输出的最后一行，前面留一个空行，格式一个字都不能差。"

    /**
     * 首轮没带清单时的兜底（v1.65 起）：不带截图、纯文本补问一次（几百 token，比整屏重发便宜一个量级）。
     * 只准输出那一行清单。
     */
    fun termsOnly(answer: String): String =
        "下面是一段屏幕讲解：\n\n" + answer.take(1500) + "\n\n" +
            "从这段讲解里挑出 3~8 个值得了解的关键词，按重要性排；型号、编号、品牌、技术规格优先" +
            "（字母数字混合的也算，如 R7250、P3 广色域、Wi-Fi 7、LPDDR5X），照原样写，每个不超过 20 个字符。" +
            "只输出一行清单，严格按这个格式、一个字都不要多：\n" +
            "$TERMS_HEAD 词1｜词2｜词3"

    /**
     * terms = 设置里「识别关键词」开着：在任何模式的提示词末尾带上 TERMS_RULE。
     * v2.7.3：模式正文之后不再拼人格 / 风格 / 篇幅（personaBlock 已删），
     * 依次只剩 cardBlock（记忆卡，空卡不拼）→ directive → TERMS_RULE。
     * v1.68 回答自由度：directive = 用户这一轮自己打的招呼（Freeform 解析出来的覆盖层）。
     * 位置在 card **之后** —— 它是「覆盖层」，得压过内部格式约定；但仍留在 TERMS_RULE 之前，
     * 否则用户一句「别写清单」会把关键词行也砍掉，而面板靠它拆词块。
     */
    fun system(mode: String, detail: String, novice: Boolean, customPrompt: String = "", terms: Boolean = true, directive: String = ""): String {
        val base = when (mode) {
            MODE_EXAM -> exam(detail, novice)
            MODE_CUSTOM -> custom(customPrompt, novice)
            MODE_WORD -> word(novice)
            else -> general(detail, novice)
        }
        val sb = StringBuilder(base)
        cardBlock(card).takeIf { it.isNotEmpty() }?.let { sb.append("\n\n").append(it) }
        directive.trim().takeIf { it.isNotEmpty() }?.let { sb.append("\n\n").append(it) }
        if (wantsTerms(mode, terms)) sb.append("\n\n").append(TERMS_RULE)
        return sb.toString()
    }

    /**
     * 这一轮要不要关键词清单行。**唯一判据**：提示词末尾拼不拼 [TERMS_RULE] 和
     * 「首轮没清单行 → 纯文本补问一次」的兜底条件必须同一份 —— 分开写就会像单词模式这样：
     * 提示词永远不含清单行，兜底条件却恒真，每查一个词白发一次请求，还往卡片尾巴塞一行 `[可点击名词]`。
     * 单词翻译例外：那张卡讲的只是画面里那**一个**词，另列 3~8 个关键词既跑题又把卡片撑长。
     */
    fun wantsTerms(mode: String, terms: Boolean): Boolean = terms && mode != MODE_WORD

    /**
     * v1.66 追问栏的两个开关（面板底部输入框旁边，位置固定）：打开后作用于接下来的每一次追问，两个可以同时开。
     * what = 讲简单点 + 举个例子（万维钢式的解释：通俗、有洞见、类比贴切，但不硬套某个知识体系）；
     * why  = 问为什么，用第一性原理从最基本的事实 / 假设一步步推出来。
     */
    const val ASK_WHAT = "【what：讲简单点，再举个例子】换一种更好懂的讲法，像万维钢写专栏那样解释：" +
        "先用一两句大白话说清它到底是什么、关键在哪；再给一个贴切的类比或生活里的具体例子，让人一下子「看见」它；" +
        "最后点出一个有洞见的要点 —— 大多数人没想到、但一点就通的那一层。" +
        "要求：通俗但不失准确；类比要贴切，不牵强；就事论事，不要硬往某个理论框架、学科体系或「××思维」上靠；" +
        "不堆术语，非用不可的术语先用一句话解释。"
    const val ASK_WHY = "【why：问为什么，用第一性原理】先列出这件事依赖的最基本的事实、定义或假设（不能再拆的那几条），" +
        "再从它们出发一步一步推出结论，每一步都说清依据，不跳步，不用「规定如此」「大家都这么说」来代替推理；" +
        "最后用一句话串起整条因果链。如果某个前提本身只是约定或经验结论，要明说。"
    /** 开关开着、输入框空着直接点发送时，用来代替问题的默认问法 */
    const val ASK_WHAT_Q = "讲简单点，举个例子"
    const val ASK_WHY_Q = "为什么？"
    /** v1.68：整句都是指令（「只给答案」「英文回我」）时，顶上一句中性的问法 —— 指令部分由覆盖层交代 */
    const val ASK_FREE_Q = "按我刚才说的那样回答"

    /** v1.68 回答自由度：输入框升级成「随便说」—— 用户一句里既有问的是什么、也有希望怎么答 */
    fun styledAsk(parsed: Freeform.Parsed, what: Boolean, why: Boolean): String {
        // 抽掉指令词后没剩下问题（「只给答案」「英文回我」）→ 用一句中性的问法，指令本身当主体
        val q = parsed.question.ifBlank { if (parsed.hasDirective) ASK_FREE_Q else "" }
        return styledAsk(q, what, why)
    }

    /** 面板上显示的那句：自由输入优先显示用户的原话（他刚写的，认得出来） */
    fun askShown(parsed: Freeform.Parsed, what: Boolean, why: Boolean): String {
        val q = parsed.raw.ifBlank { parsed.question }
        return askShown(q, what, why)
    }

    /** 开关拼出来真正发给模型的追问（再交给 followUp 包一层）；没问题、开关也全关 → 空串（不发） */
    fun styledAsk(question: String, what: Boolean, why: Boolean): String {
        val q = question.trim().ifEmpty { listOfNotNull(if (what) ASK_WHAT_Q else null, if (why) ASK_WHY_Q else null).joinToString(" ") }
        if (q.isEmpty()) return ""
        val rules = listOfNotNull(if (what) ASK_WHAT else null, if (why) ASK_WHY else null)
        return if (rules.isEmpty()) q else q + "\n\n回答方式：\n" + rules.joinToString("\n")
    }

    /** 面板上「## 追问：…」显示的那句：开了开关就在前面标出来，长提示词不往面板上贴 */
    fun askShown(question: String, what: Boolean, why: Boolean): String {
        val q = question.trim().ifEmpty { listOfNotNull(if (what) ASK_WHAT_Q else null, if (why) ASK_WHY_Q else null).joinToString(" ") }
        val tags = listOfNotNull(if (what) "what" else null, if (why) "why" else null)
        return if (tags.isEmpty()) q else "［" + tags.joinToString("·") + "］" + q
    }

    /** 面板里每段追问的标题前缀（AnswerText 靠它把追问和第一轮回答分开） */
    const val FOLLOW_HEAD = "## 追问："
    /** v1.65 续图追问的标题前缀（带一张刚截的新图） */
    const val FOLLOW_HEAD_SHOT = "## 追问（带新截图）："

    /**
     * 追问（v1.57）：截图、第一轮提示词和之前的回答都在前面的消息里，这里只说这次问什么。
     * 不重复前面讲过的，篇幅按问题来 —— 面板小，追问的回答一般几行就够。
     * v1.68：directive = 用户自由输入里识别出的回答要求，作为覆盖层拼在最后（压过上面这些默认约定）；
     * lang 识别出来时把「用中文」换掉 —— 用户要别的语言，就别再硬按中文要求。
     */
    fun followUp(question: String, novice: Boolean, directive: String = "", lang: String? = null): String =
        "追问：" + question.trim() + "\n\n" +
            "请结合上面的截图和你之前的回答，直接回答这个追问：\n" +
            "- 不要把之前的回答整段重复一遍，只讲这次问的；\n" +
            (if (directive.isNotBlank()) "- 篇幅、格式、语气按下面「用户自己指定的要求」来，它压过这些默认约定；\n" else "- 这次可以更短：能一句说清就一句，不超过 $CHARS_FOLLOWUP 字；\n") +
            "- 不要再输出「$TERMS_HEAD」关键词清单行；\n" +
            (if (novice) "- 用大白话，像给完全没学过的人讲，遇到术语先用一句话解释；\n" else "") +
            "- $MATH_RULE；\n" +
            "- " + langRule(lang) + "，直接输出。" +
            directiveBlock(directive)

    /**
     * v1.67 追问省图（降成本核心）：这次请求里**没有截图**，所以不能说「结合上面的截图」——
     * 说了模型会去画面里找，找不到就开始编。改成「按你刚才那份回答接着答」，
     * 首轮回答已经把画面内容说清楚了，够接话；真离不开图的（要确认画面里的数字、位置）让它明说没看到。
     * 省下的是整张截图折成的上千 prompt token —— 追问是高频短问题，token 大头全在图上。
     * v1.68：directive / lang 与 [followUp] 同一套 —— 省图是默认路径，用户的回答要求不能在这条路上丢掉。
     */
    fun followUpNoShot(question: String, novice: Boolean, directive: String = "", lang: String? = null): String =
        "追问：" + question.trim() + "\n\n" +
            "按你刚才那份回答接着答（这次没有截图，只有你上一轮的文字）：\n" +
            "- 只讲这次问的，不要把之前的回答整段重复一遍；\n" +
            "- 如果这个问题必须看图才能答（比如要确认画面里的数字、位置、界面元素），直接说「这部分我没看到画面」，不要猜；\n" +
            (if (directive.isNotBlank()) "- 篇幅、格式、语气按最后「用户自己指定的要求」来，它压过这些默认约定；\n" else "- 这次可以更短：能一句说清就一句，不超过 $CHARS_FOLLOWUP 字；\n") +
            "- 不要再输出「$TERMS_HEAD」关键词清单行；\n" +
            (if (novice) "- 用大白话，像给完全没学过的人讲，遇到术语先用一句话解释；\n" else "") +
            "- $MATH_RULE；\n" +
            "- " + langRule(lang) + "，直接输出。" +
            directiveBlock(directive)

    /**
     * v1.65 续图追问（对齐桌面 askWithNewShot 的话术）：原始截图仍是第一张，新截的屏幕是最后一张，
     * 以最新截图为准回答 —— 之前所有轮次的上下文都还在。
     */
    fun followUpWithShot(question: String, novice: Boolean, directive: String = "", lang: String? = null): String =
        "情况有更新：最后一张图是刚截的最新屏幕（前面的截图是之前的画面），请以最新截图为准。\n" +
            "只回答这一次的追问，不要重复之前的解析，也不要复述题干。\n追问：" + question.trim() + "\n\n" +
            (if (directive.isNotBlank()) "- 篇幅、格式、语气按下面「用户自己指定的要求」来，它压过这些默认约定。\n" else "- 这次可以更短：能一句说清就一句，不超过 $CHARS_FOLLOWUP 字；\n") +
            "- 不要再输出「$TERMS_HEAD」关键词清单行；\n" +
            (if (novice) "- 用大白话，像给完全没学过的人讲，遇到术语先用一句话解释；\n" else "") +
            "- $MATH_RULE；\n" +
            "- " + langRule(lang) + "，直接输出。" +
            directiveBlock(directive)

    /** 用户指定了语言就换成那一句，否则照旧用中文 */
    private fun langRule(lang: String?): String =
        if (lang.isNullOrBlank()) "用中文" else "不管前面怎么写，这一轮用" + lang(lang).label

    private fun directiveBlock(directive: String): String =
        if (directive.isBlank()) "" else "\n\n" + directive.trim()

    /** 中英互译段的固定小标题：译文 / AI 解析之外再给一份中英对照（见 [bilingualPair]）。 */
    const val BILINGUAL_HEAD = "## 中英互译"

    /**
     * 这段文字算中文还是英文（只给「中英互译」判方向用，不做完整语种识别）：
     * 汉字占多数 → "zh"，拉丁字母占多数 → "en"，其它（日文假名、韩文、俄文、纯符号）→ ""。
     * 判不出来就不硬凑对照 —— 宁可没有这一段，也不编一份假的另一种语言。
     */
    fun script(s: String): String {
        var han = 0; var latin = 0; var other = 0
        for (ch in s) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> latin++
                // 假名 / 谚文 / 西里尔：一眼就不是中英，别把「勉強」这类汉字写成中文
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.CYRILLIC -> other++
                else -> Unit
            }
        }
        if (other > 0) return ""
        if (han == 0 && latin == 0) return ""
        return if (han > 0 && han * 2 >= s.count { it.isLetter() }) "zh" else "en"
    }

    /**
     * 中英互译段（纯函数）：原文和这份译文正好一中一英时，用**两边原样的文字**给出对照。
     * 两边都是现成的（一边是原文、一边是所选引擎 / 模型的译文），所以这里一个字都不改、不新译、不编。
     *
     * 返回 null = 这一对不构成中英对照（同语种，或本来就不是中英文）—— 缺的那一半只能由模型真翻一次
     * （见 [translateEnrich]），绝不在 App 里拿同一句话冒充互译。
     */
    fun bilingualPair(text: String, reference: String): String? {
        val a = text.trim()
        val b = reference.trim()
        if (a.isEmpty() || b.isEmpty() || a == b) return null
        val en = when {
            script(a) == "en" && script(b) == "zh" -> a
            script(a) == "zh" && script(b) == "en" -> b
            else -> return null
        }
        val zh = if (en == a) b else a
        return BILINGUAL_HEAD + "\n英文：" + en + "\n中文：" + zh
    }

    /**
     * 第一段正文（到第一个空行为止）。AI 整段翻译时译文就在最前面 —— 模型漏写互译段时，
     * 拿「原文 + 它自己给的第一段译文」拼对照，两份都是现成原话，不新译也不改译文。
     */
    fun firstBlock(text: String): String =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).firstOrNull()?.trim().orEmpty()

    /**
     * 把模型给的互译段收成「## 中英互译」+ 英文 / 中文两行。
     * 缺任何一条、或两条其实是同一句话、或语言对不上（英文那行没英文 / 中文那行没汉字）就返回 null ——
     * 拿一句话顶两条的「互译」是假的，宁可如实说缺。
     */
    fun bilingualSection(body: String?): String? {
        val b = body?.trim().orEmpty()
        if (b.isEmpty()) return null
        // 模型常给「- 英文：…」「**英文**：…」这类装饰；只认「英文 / 中文」这两个开头，其余行丢掉
        val lines = b.split('\n').map { it.replace("**", "").trim().trimStart('-', '*', '+', '•', '·', '#', ' ').trim() }
        fun pick(tag: String) = lines.firstOrNull { it.startsWith(tag) }
            ?.removePrefix(tag)?.trimStart('：', ':', '，', ',', ' ')?.trim()?.ifEmpty { null }
        val en = pick("英文") ?: return null
        val zh = pick("中文") ?: return null
        // 两行其实是同一句话（大小写 / 空格 / 标点不同也算）→ 这不是互译，是模型把一句抄了两遍
        if (squash(en) == squash(zh)) return null
        // 英文那行以拉丁字母为主、中文那行至少有一个汉字 —— 两行语言对不上就是假对照
        if (script(en) != "en") return null
        if (!zh.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
        return BILINGUAL_HEAD + "\n英文：" + en + "\n中文：" + zh
    }

    /** 比「两句是不是同一句」用：去掉空白、大小写差异和常见中英标点，只留实义字符。 */
    private fun squash(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 从模型输出里取出一段 `#` 标题小节（标题行到下一个任意级标题 / 结尾），返回去掉标题的正文。
     * 没这一段就返回 null。取出来之后由调用方决定放哪儿（中英互译段要固定在最后，见 [withoutSection]）。
     */
    fun section(text: String, head: String): String? {
        val lines = text.replace("\r\n", "\n").split('\n')
        val at = lines.indexOfFirst { sameHead(it, head) }
        if (at < 0) return null
        return lines.subList(at + 1, sectionEnd(lines, at)).joinToString("\n").trim().ifEmpty { null }
    }

    /** 去掉模型自己写的那段 `# <head>` 小节（含标题行与段后多余空行）；没有就原样返回。 */
    fun withoutSection(text: String, head: String): String {
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        val at = lines.indexOfFirst { sameHead(it, head) }
        if (at < 0) return text
        lines.subList(at, sectionEnd(lines, at)).clear()
        return lines.joinToString("\n").trim()
    }

    /**
     * 这段到哪一行为止（不含）：**任何**一级 Markdown 标题都是边界，不只 `## `。
     * 只认 `## ` 时会一路吞掉后面的 `### 第 N 题` / `### 解析` —— 摘「中英互译」段就把真正的解析删了。
     */
    private fun sectionEnd(lines: List<String>, at: Int): Int {
        var end = at + 1
        while (end < lines.size && !isHeading(lines[end])) end++
        return end
    }

    /** 这一行是不是 Markdown 标题行（`#` 开头，1~6 级都算；`**` 加粗包着的也算）。 */
    private fun isHeading(line: String): Boolean = line.trim().replace("**", "").trimStart().startsWith("#")

    /**
     * 这一行是不是那一段的标题：必须是真标题行（`#` 开头），容忍模型加的 `**`、行尾冒号 / 句号
     * （`## **中英互译**：`）。只放宽装饰，不放宽内容 —— 换个说法的标题不算，宁可当作没给。
     *
     * 要求 `#` 开头是刻意的：模型偶尔把「**中英互译**」当普通加粗行写在正文中间，那时摘段会一路吞到
     * 下一个标题，把后面真正的解析删掉。这种行留在正文里照常显示，不当作可摘的段。
     */
    private fun sameHead(line: String, head: String): Boolean {
        val t0 = line.trim()
        if (!t0.startsWith("#")) return false
        val t = t0.trimEnd('：', ':', '。').replace("**", "").trim().trimStart('#').trim()
        return t == head.removePrefix("## ").trim()
    }

    /**
     * 这一轮要不要「中英互译」段：目标语言是自动 / 中文 / 英文时才有意义。
     * 用户把目标语言钉成日文 / 德文等（明确要别的语言），再追加中英对照是跑题 —— 不追加。
     * 提示词层面用它（[translate] / [translateEnrich] 里还会再让模型对非中英原文自行省略）。
     */
    fun wantsBilingual(langId: String): Boolean = langId == "auto" || langId == "zh" || langId == "en"

    /**
     * 这一轮结果里**真的**该有中英互译段没有：方向对得上（[wantsBilingual]）且原文确实是中文或英文。
     * 日文 / 韩文 / 俄文原文既没有中英对照可言，也不该因为「缺段」而不写缓存、白重试一轮。
     */
    fun needsPair(text: String, langId: String): Boolean =
        wantsBilingual(langId) && script(text.trim()).let { it == "zh" || it == "en" }

    /**
     * 提示词里的「中英互译」要求。放在全部解析之后、单独一段 —— 用户要的是**现有完整解析之外再追加**，
     * 不是用一句直译替掉解析。两种情形分开写清，模型才不会拿同一句话顶替另一种语言：
     *  - 原文和译文正好一英一中：两边都用现成的原话照抄（不重译）；
     *  - 原文和译文同一门语言（目标语言就是原文语言时）：真翻出缺的那一门。
     */
    private fun bilingualRule(): String =
        "中英互译（默认追加，固定放在最末尾）：\n" +
            "- 在所有解析之后另起一段，以「" + BILINGUAL_HEAD + "」这一行开头；段内只写「英文：…」「中文：…」各一行，两句话对齐，不解释、不加序号、不写别的段落；\n" +
            "- 原文与译文正好一英一中时，直接用现成的原文和译文照抄，不要重译、不要润色；\n" +
            "- 原文与译文同为一门语言时，补出缺的那门语言的真译文（中文原文给英文，英文原文给中文），不能把同一句话重复一遍充当互译；\n" +
            "- 只给中英这两种语言：原文或译文不是中英文（日文、韩文、俄文等）时整段省略。\n\n"

    /**
     * 这一轮没有中英互译段时的诚实说明（绝不拿同一句话冒充对照，也不假装有）。
     * [aiOn] 决定缺的那一半补不出来是「AI 没给」还是「没配 AI」，两种原因分开写。
     * 原文不是中英文（日文 / 韩文 / 俄文等）时返回空串 —— 本来就没有中英对照可言，不写说明。
     */
    fun missingPairNote(text: String, aiOn: Boolean): String {
        val s = script(text.trim())
        if (s != "zh" && s != "en") return ""
        val other = if (s == "en") "中文" else "英文"
        return if (aiOn) "（这一轮没有中英互译：缺" + other + "那一半，AI 这一轮没给出）"
        else "（这一轮没有中英互译：原文和译文同为" + (if (s == "en") "英文" else "中文") + "，未配置 AI，补不出" + other + "）"
    }

    /**
     * v1.89 完整翻译：先判断原文粒度（单词 / 短语 / 句子 / 段落），按对应那一套输出，译文永远在最前。
     * 原文只有一种粒度就只写那一套，不硬加用不上的段落；没有固定字数上限，够把该讲的讲清即可。
     * 保留的契约：[lang] 规则（含「中英互译」）与结尾固定为「原文：\n…」（PromptsTest 锁着，别改成别的结尾）。
     * v2.6.3：AI 自己整段翻译时（auto 里机器翻译都没配好 / 都失败）也可以讲透术语，但「可以详」不等于「必须长」——
     * 医学词按「定义 / 相关背景 / 不等于个体诊断」分开写，不编临床数据。
     * v2.8：目标语言是自动 / 中文 / 英文时，在全部解析之后**追加**一段 [BILINGUAL_HEAD] 中英对照（见 [bilingualRule]）——
     * 用户要的是现有完整解析之外多一段，不是拿直译替掉解析。
     */
    fun translate(text: String, langId: String): String =
        "你是专业翻译，同时是语言学习助手。翻译方向：" + lang(langId).rule + "。\n\n" +
            STYLE_SHARED + "\n\n" +
            "先判断原文的粒度是单词、短语、句子还是段落，只写这一类对应的内容，别为了凑格式硬加不适用的段落。\n\n" +
            "1）单词：\n" +
            "- 先给这个词在当前语言方向下最贴切的意思，再列常见义项；不止一个义项时，结合上下文点明这里最可能取哪一个；\n" +
            "- 标词性；只在原文是英文、且读音有把握时给音标，拿不准就不给，绝不编；\n" +
            "- 需要时给出词形变化（名词复数、动词三态、比较级等）和常见搭配，并说清易混 / 近义词的区别；\n" +
            "- 必给 2 个或更多「原文 + 译文」的双语例句（不能省），例句要自然常见，不要生造。\n\n" +
            "2）短语：\n" +
            "- 先给整条短语的译文；\n" +
            "- 说明它的结构（由哪些词构成、整体算哪一类表达）和语域（正式 / 口语 / 书面 / 俚语等）；\n" +
            "- 给常见搭配和用法要点，合适就给双语例句。\n\n" +
            "3）句子：\n" +
            "- 先给完整译文；\n" +
            "- 再拆关键词语：标词性，说明它在这句里取的具体意思（不是把词典义项照搬一遍）；\n" +
            "- 讲清句子结构、语气和言外之意或易错点；\n" +
            "- 值得时给一个可替换的同义表达或换个说法的例句。\n\n" +
            "4）段落：\n" +
            "- 先给完整译文；\n" +
            "- 再用「## 要点」写段落主旨和关键信息；\n" +
            "- 不要逐个词查词典，只挑真正影响理解的词或表达讲。\n\n" +
            "术语与延伸（v2.6.3）：\n" +
            "- 专业术语（医学、法律、技术等）可以比一般义项讲得更透：先一句定义，再给真正有助于理解的背景或机制，最后点明边界（什么情况下不适用）。只在真能帮上理解时才写，不为了显得完整而堆背景。\n" +
            "- 医学 / 专业表述必须分清三件事：这个词的**定义**、它通常**关联的背景或机制**、以及**它不等于对某个人的诊断** —— 例如「高尿钙」只说明这个指标本身的含义，不能据此推断某人患病，也不能替代就医。\n" +
            "- 这类详解是「可以详」，不是「必须长」：定义和关键依据讲清就收，不为了凑长展开，不写与原文无关的科普。\n\n" +
            "通用纪律：译文放在最前面，不要加「译文：」这类前缀；所有义项、搭配、例句都必须是真实常见的用法，拿不准就不写；不编词源、读音、数据和事实（含临床数据、指南、剂量）；不写和翻译无关的段落。" +
            (if (!wantsBilingual(langId)) "" else "\n\n" + bilingualRule()) +
            "\n\n原文：\n" + text

    /**
     * v1.89 AI 解析（配合机器翻译用）：译文已经由 DeepL / 百度给出，
     * 这里让 AI 在**不改动那份译文**的前提下只补解析 —— 重译会变成「偷偷换了引擎」，必须避免。
     * 结尾把参考译文和原文都给出来：原文是唯一依据，参考译文只是对齐基准。
     *
     * v2.6.3：开头固定输出「## AI 解析」小标题 —— 上面是机器译文、下面是 AI 补的解释，来源一眼分得开；
     * 术语允许比义项讲得更透（定义 / 相关背景 / 不等于个体诊断），但「可以详」不等于「必须长」。
     *
     * v2.8：目标语言是中英味的方向、而**原文与参考译文同属一门语言**时（典型：中文原文 + 「翻译成中文」），
     * 另要一段 [BILINGUAL_HEAD] 补出缺的那门语言 —— 这是全流程里唯一允许新译的地方，参考译文本身照旧不改。
     * 原文和参考译文本来就一英一中时不要它：那一对由 App 直接拿两边原话拼（见 [bilingualPair]），免得模型借机改译文。
     */
    fun translateEnrich(text: String, langId: String, reference: String): String =
        "你是专业翻译，同时是语言学习助手。翻译方向：" + lang(langId).rule + "。\n" +
            "下面是原文，以及一份参考译文（由用户选定的翻译引擎给出，已经显示在回答最前面）。\n\n" +
            STYLE_SHARED + "\n\n" +
            "参考译文是唯一的译文：不要重译、不要改写、不要另给别的译文，也不要否定它；只有在它和原文明显不一致时，用一句话点明。\n" +
            "你的正文以「## AI 解析」这一行开头，然后只补解析，不重复整段译文。先判断原文的粒度是单词、短语、句子还是段落，只写这一类对应的内容：\n" +
            "1）单词：标词性；列常见义项并结合上下文点明这里最可能取哪一个；原文是英文且有把握时给音标（拿不准就不给）；需要时给词形变化与常见搭配，说清易混 / 近义词的区别；必给 2 个或更多双语例句（不能省）。\n" +
            "2）短语：说明结构与语域，给常见搭配、用法要点和合适的双语例句。\n" +
            "3）句子：拆关键词语（标词性 + 在这句里的具体含义），讲清句子结构、语气、言外之意或易错点，值得时给同义替换或变体例句。\n" +
            "4）段落：用「## 要点」写主旨与关键信息，不要逐个词查词典，只挑真正影响理解的词或表达讲。\n\n" +
            "术语与延伸（v2.6.3）：\n" +
            "- 专业术语（医学、法律、技术等）可以比普通义项讲得更透：先一句定义，再给真正有助于理解的背景或机制，最后点明边界（什么情况下不适用）。只在真能帮上理解时才写。\n" +
            "- 医学 / 专业表述必须分清三件事：这个词的**定义**、它通常**关联的背景或机制**、以及**它不等于对某个人的诊断** —— 例如「高尿钙」只说明这个指标本身的含义，不能据此推断某人患病，也不能替代就医。\n" +
            "- 可以给少量真正相关的延伸（同族表达、常见误译、该术语在原文语境里的地位），一条两句讲完就收；不要发散成科普文章。\n" +
            "- 这类详解是「可以详」，不是「必须长」：定义和关键依据讲清就收，不为了凑长展开。\n\n" +
            "纪律：不编词源、读音、数据和事实（含临床数据、指南、剂量）；不写与理解无关的段落。\n\n" +
            (if (!needsPair(text, langId) || script(text.trim()) != script(reference.trim())) "" else pairRule(text)) +
            "参考译文：\n" + reference + "\n\n原文：\n" + text

    /**
     * 原文和参考译文同语种时的「补另一半」要求：这时缺的那门语言谁都没给过，只能真翻一次。
     * 单独写一段放在最后，并明说**只**补这一段、参考译文一个字不改，免得模型顺手把上面译文换了。
     */
    private fun pairRule(text: String): String {
        val en = script(text.trim()) == "en"
        val to = if (en) "中文" else "英文"
        val from = if (en) "英文" else "中文"
        return "中英互译（默认追加，固定放在最末尾）：\n" +
            "- 原文和参考译文都是" + from + "，缺的是" + to + "那一半：在全部解析之后另起一段，以「" + BILINGUAL_HEAD + "」这一行开头，把**原文**改写成自然、忠实的" + to + "译文；\n" +
            "- 段内只写「英文：…」「中文：…」各一行（" + from + "那句照抄原文，" + to + "那句写你的译文），不加解释、不加序号；\n" +
            "- 除此以外什么都不变：上面那句参考译文照旧，不要改写、不要替换、不要另给第三份译文。\n\n"
    }

    /**
     * 识别的输出预算。思考（DeepSeek 等）和正文共用 max_tokens —— v1.53 以前 4096，
     * 思考开「高」时常常想完只剩几百字给正文，答案显示一半就断了。只按实际用量计费，放宽不多花钱。
     */
    const val SOLVE_BUDGET = 8192

    /**
     * v1.89 翻译输出预算。完整翻译 + 语言点解析（尤其段落和带例句的单词）比一行直译长得多，
     * 4096 容易把解析拦腰截断 —— 和 [SOLVE_BUDGET] 一样放宽到 8192；max_tokens 只是上限、按实际用量计费。
     * 接口上限更小时 400 会带 max_tokens 报错，Solver 里退 4096 再试一次。
     */
    const val TRANSLATE_BUDGET = 8192

    /**
     * v1.89 翻译提示词版本。翻译缓存键要带上它：提示词一改，旧版本存下来的译文 / 解析不再命中，
     * 否则会拿旧的简单译文冒充新的完整翻译。改 [translate] / [translateEnrich] 就把它 +1。
     * v1.90：STYLE_SHARED 加了四条讲法约束（由浅入深 / 类比带边界 / 证据不足直说 / 第一性原理），
     * 识别与翻译共用这一段 —— 译文与解析的组织方式变了，故 2 → 3，旧缓存不再命中。
     * v1.91：STYLE_SHARED 删掉「不要在逗号、分号或破折号处断行」那条、并把分段口径并进短句短段规则，
     * 译文与解析的换行/分段随之变化，故 3 → 4，旧缓存不再命中。
     * v2.6.3：[translate] / [translateEnrich] 都加了「术语与延伸」段（定义 / 相关背景 / 不等于个体诊断，
     * 医学词按此区分），[translateEnrich] 还固定以「## AI 解析」开头 —— 解析的结构变了，
     * 故 4 → 5，v4 及更早存下的解析不再被当作新格式命中。
     * v2.8：两条提示词都加了「## 中英互译」段（[bilingualRule] / [pairRule]）—— 输出结构又变了，
     * 且旧缓存里根本没有这一段，故 5 → 6，否则同样的原文会一直命中缺互译段的旧结果。
     */
    const val TRANSLATE_PROMPT_VERSION = 6

    fun isMaxTokensError(msg: String?): Boolean =
        msg != null && msg.startsWith("HTTP 400") && Regex("max_?tokens|max_new_tokens|max_completion_tokens", RegexOption.IGNORE_CASE).containsMatchIn(msg)

    /**
     * 这个 400 是不是「接口 / 模型不认 tools（function calling）」（v2.2.1）。
     * 很多 OpenAI 兼容的视觉模型、网关根本不支持 function calling，带了 tools 直接 400；
     * 以前会把它显示成「模型名对吗」，整题失败。命中这里就去掉工具再答一次。
     */
    fun isToolsUnsupported(msg: String?): Boolean =
        msg != null && msg.startsWith("HTTP 400") &&
            Regex("\\btools?\\b|tool_choice|function[_ ]?call", RegexOption.IGNORE_CASE).containsMatchIn(msg)

    /** 「详细」「纯小白」正文会长很多，思考又和正文共用预算 —— 放宽一半（同桌面 answerBudget） */
    fun budget(base: Int, detail: String, novice: Boolean): Int =
        if (normDetail(detail) == "detailed" || novice) Math.round(base * 1.5f) else base

    /** 模型偶尔仍会吐出「[无题目]」标记 —— 剥掉（同桌面 NO_Q_HEAD） */
    val NO_Q_HEAD = Regex("^\\s*[\\[【]?\\s*无题目\\s*[\\]】]?\\s*")
}
