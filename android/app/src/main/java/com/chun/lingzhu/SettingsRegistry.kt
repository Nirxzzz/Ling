package com.chun.lingzhu

/**
 * 设置页「信息架构」的唯一登记表（2.2.x 微信式改版）。纯 Kotlin、不碰 android.*，单测直接跑。
 *
 * 三层：
 *  - [CATEGORIES]：一级分类（设置首页里一行一个，点进去是二级页）。
 *  - [SECTIONS]：二级页里的分组卡片（标题 + 白卡），每张卡归属一个分类。
 *    原来的折叠组（MainActivity.G_*）**原 id 保留**直接当卡片 id（跳转 / 旧存档键不变）；
 *    从旧组里拆出来的块用 `s.` 开头的新 id。
 *  - [ITEMS]：改版前设置页上的设置项 / 入口 / 说明，
 *    记录旧位置（[Item.oldGroup] = 旧组 id）→ 新卡片（[Item.section]）。
 *
 * 只改呈现和组织，**不改任何存储键和默认值**：[Item.keys] 只是给人核对的键名清单，读写仍在 MainActivity 原代码里。
 * 单测（SettingsRegistryTest）钉：项 id 不重复、每项恰好一个新位置、每个分类非空、每个旧折叠组都有归属。
 */
object SettingsRegistry {
    // ---------------- 一级分类（按用户心智排序）----------------
    const val C_AI = "AI 接口与翻译"
    const val C_ORB = "小球与识屏"
    const val C_POP = "弹出与提醒"
    const val C_STUDY = "学习与词库"
    const val C_LOOK = "外观"
    const val C_DATA = "数据与同步"
    const val C_PERM = "权限与后台"
    const val C_ABOUT = "关于与更新"

    val CATEGORIES = listOf(C_AI, C_ORB, C_POP, C_STUDY, C_LOOK, C_DATA, C_PERM, C_ABOUT)

    // ---------------- 旧折叠组 id（与 MainActivity.G_* 逐字相同；那边的常量由这里转出）----------------
    const val G_AUTH = "start.auth"
    const val G_KEEP = "start.keep"
    const val G_DATA = "start.data"
    const val G_SCAN = "orb.scan"
    const val G_API = "api.api"
    const val G_SEARCH = "api.search"
    const val G_TRANSLATE = "api.translate"
    const val G_MODES = "api.modes"
    const val G_COLOR = "look.color"
    const val G_PANEL = "look.panel"
    const val G_CUSTOM = "look.custom"
    const val G_WORDS_POP = "words.pop"
    const val G_WORDS_DECK = "words.deck"
    const val G_REMIND = "remind.all"

    /** 改版前的 14 个折叠组（id → 旧组名），盘点与单测用 */
    val OLD_GROUPS: Map<String, String> = linkedMapOf(
        G_AUTH to "上手与授权", G_MODES to "识别模式", G_PANEL to "面板与小球", G_SCAN to "自动扫描（高级）",
        G_WORDS_POP to "弹词设置", G_WORDS_DECK to "词库", G_REMIND to "提醒与知识卡",
        G_COLOR to "配色与字号", G_CUSTOM to "自定义中心", G_API to "接口与模型", G_SEARCH to "联网搜索",
        G_TRANSLATE to "翻译", G_KEEP to "后台保活", G_DATA to "数据与更新",
    )
    /** 旧首页（hub）上的块：不属于任何折叠组 */
    const val OLD_HUB = "hub"

    // ---------------- 新卡片（二级页里的分组）----------------
    const val S_ORB_START = "s.orb.start"
    const val S_ORB_HIDE = "s.orb.hide"
    const val S_POP_RULE = "s.pop.rule"
    const val S_POP_BRIEF = "s.pop.brief"
    const val S_POP_VIBE = "s.pop.vibe"
    const val S_STUDY = "s.study.cards"
    /** 专注守护（v2.3.4 手机版）：指定学习应用 / 屏幕内容两档 + 离开阈值 */
    const val S_FOCUS = "s.study.focus"
    /** 陪伴气泡：小球跑着时不定时冒一句本机短句，不抢焦点 */
    const val S_COMPANION = "s.study.companion"
    const val S_LOOK_TEXT = "s.look.text"
    const val S_DATA_CACHE = "s.data.cache"
    const val S_DATA_SYNC = "s.data.sync"
    const val S_VOICE = "s.voice.asr"
    /** 语音对话（v2.8）：麦克风 → 腾讯识别 → 现有模型 → 腾讯合成 → 扬声器 */
    const val S_VOICE_TALK = "s.voice.talk"
    const val S_ABOUT_VER = "s.about.ver"
    const val S_ABOUT_UPDATE = "s.about.update"
    const val S_ABOUT_SETUP = "s.about.setup"
    /** 新首页上的块（必要配置卡 / 常用入口 / 分类入口） */
    const val S_HUB = "s.hub"

    data class Section(val id: String, val title: String, val cat: String)

    /** 二级页卡片，按页内显示顺序排列。旧组 id 直接复用（新标题按新归属改了说法） */
    val SECTIONS: List<Section> = listOf(
        Section(G_API, "接口与模型", C_AI),
        Section(G_SEARCH, "联网搜索", C_AI),
        Section(G_TRANSLATE, "翻译", C_AI),
        Section(S_VOICE, "语音转写", C_AI),
        Section(S_VOICE_TALK, "语音对话", C_AI),

        Section(S_ORB_START, "启动与手势", C_ORB),
        Section(G_MODES, "识别模式", C_ORB),
        Section(G_PANEL, "面板与小球", C_ORB),
        Section(G_SCAN, "自动扫描", C_ORB),
        Section(S_ORB_HIDE, "打游戏时隐藏小球", C_ORB),

        Section(G_WORDS_POP, "单词卡弹出", C_POP),
        Section(G_REMIND, "提醒与待办", C_POP),
        Section(S_POP_RULE, "弹出节奏与免打扰", C_POP),
        Section(S_POP_BRIEF, "简报与复习提醒", C_POP),
        Section(S_POP_VIBE, "震动", C_POP),

        Section(S_STUDY, "学习与知识卡", C_STUDY),
        Section(S_FOCUS, "专注守护", C_STUDY),
        Section(S_COMPANION, "陪伴气泡", C_STUDY),
        Section(G_WORDS_DECK, "词库", C_STUDY),

        Section(G_COLOR, "配色", C_LOOK),
        Section(S_LOOK_TEXT, "面板文字大小", C_LOOK),
        Section(G_CUSTOM, "自定义中心", C_LOOK),

        Section(G_DATA, "历史记录", C_DATA),
        Section(S_DATA_CACHE, "缓存", C_DATA),
        Section(S_DATA_SYNC, "局域网同步", C_DATA),

        Section(G_AUTH, "权限", C_PERM),
        Section(G_KEEP, "后台保活", C_PERM),

        Section(S_ABOUT_VER, "版本", C_ABOUT),
        Section(S_ABOUT_UPDATE, "检查更新", C_ABOUT),
        Section(S_ABOUT_SETUP, "重走首次配置", C_ABOUT),
    )

    fun section(id: String): Section? = SECTIONS.firstOrNull { it.id == id }
    fun categoryOf(sectionId: String): String? = section(sectionId)?.cat

    /**
     * 一个旧设置项。[old] = 旧位置（旧分类 › 旧组，或 [OLD_HUB]）；[oldGroup] = 旧组 id（hub 上的为 [OLD_HUB]）；
     * [section] = 新卡片 id；[advanced] = 新位置在卡片里的「高级设置」盒内；[merged] 非空 = 与别处重复、
     * 已合并（写明合并到哪），这一项本身在新结构里不再单独出现。
     */
    data class Item(
        val id: String, val name: String, val oldGroup: String, val section: String,
        val keys: List<String> = emptyList(), val advanced: Boolean = false, val merged: String? = null,
    )

    private fun i(id: String, name: String, old: String, sec: String, vararg keys: String, adv: Boolean = false, merged: String? = null) =
        Item(id, name, old, sec, keys.toList(), adv, merged)

    val ITEMS: List<Item> = listOf(
        // ---- 旧首页 ----
        i("H1", "必要配置卡（悬浮窗 / 接口 + 去做按钮）", OLD_HUB, S_HUB),
        i("H2", "常用：启动悬浮球", OLD_HUB, S_HUB),
        i("H3", "常用：待办与随手记", OLD_HUB, S_HUB),
        i("H4", "常用：学习与复习", OLD_HUB, S_HUB),
        i("H5", "常用：查看历史记录", OLD_HUB, S_HUB),
        i("H6", "按用途分类入口（含当前值摘要）", OLD_HUB, S_HUB),

        // ---- 开始使用 › 上手与授权 ----
        i("A1", "悬浮球手势说明", G_AUTH, S_ORB_START),
        i("A2", "追问说明（what / why 开关）", G_AUTH, S_ORB_START),
        i("A3", "权限入口行（一键开权限）", G_AUTH, G_AUTH),
        i("A4", "接口状态行（点去填接口）", G_AUTH, G_AUTH, merged = "首页必要配置卡 + AI 接口与翻译 › 接口与模型（同一个跳转，合并）"),
        i("A5", "无障碍截屏状态", G_AUTH, G_AUTH),
        i("A6", "启动悬浮球按钮", G_AUTH, S_ORB_START),
        i("A7", "录屏备用说明", G_AUTH, G_AUTH),

        // ---- 高级设置 › 后台保活 ----
        i("K1", "后台保活说明", G_KEEP, G_KEEP),
        i("K2", "厂商自启动 / 后台管理入口", G_KEEP, G_KEEP),
        i("K3", "被清理后自动恢复次数", G_KEEP, G_KEEP, "reviveCount", "reviveLast", "reviveHow"),
        i("K4", "看门狗闹钟状态（点按重排）", G_KEEP, G_KEEP, "orbWanted", "watchdogFails", "watchdogKicks"),
        i("K5", "各厂商入口说明", G_KEEP, G_KEEP),
        i("K6", "打游戏时隐藏小球说明", G_KEEP, S_ORB_HIDE),

        // ---- 高级设置 › 数据与更新 ----
        i("D1", "保存历史记录", G_DATA, G_DATA, "saveHistory"),
        i("D2", "查看历史记录", G_DATA, G_DATA),
        i("D3", "历史记录说明", G_DATA, G_DATA),
        i("D4", "使用结果缓存", G_DATA, S_DATA_CACHE, "useCache"),
        i("D5", "缓存统计行", G_DATA, S_DATA_CACHE, "cacheHits"),
        i("D6", "清空缓存", G_DATA, S_DATA_CACHE),
        i("D7", "缓存说明", G_DATA, S_DATA_CACHE),
        i("D8", "更新清单地址", G_DATA, S_ABOUT_UPDATE, "updateUrl", adv = true),
        i("D9", "检查更新 / 下载更新 / 下载进度", G_DATA, S_ABOUT_UPDATE, "updateLastCheck"),
        i("D10", "更新说明", G_DATA, S_ABOUT_UPDATE),
        i("D11", "再走一遍首次配置引导", G_DATA, S_ABOUT_SETUP, "onboarded", "onboardStarted", "onboardSkipped"),

        // ---- 接口与模型 › 接口 ----
        i("P1", "接口状态", G_API, G_API),
        i("P3", "接口地址", G_API, G_API, "baseUrl"),
        i("P4", "密钥", G_API, G_API, "apiKey"),
        i("P5", "模型", G_API, G_API, "model"),
        i("P6", "接口方案（存 / 切 / 长按删）", G_API, G_API, "customApis", "activeProfileId"),
        i("P7", "检测连接", G_API, G_API),
        i("P8", "接口格式说明", G_API, G_API),
        i("P9", "让 Agent 记住聊过的事", G_API, G_API, "agentMemOn", adv = true),
        i("P10", "查看 / 清空当前 Agent 的记忆", G_API, G_API, adv = true),
        i("P11", "思考强度", G_API, G_API, "thinking", adv = true),

        // ---- 接口与模型 › 联网搜索 ----
        i("S1", "联网搜索状态", G_SEARCH, G_SEARCH),
        i("S2", "开启联网搜索", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S3", "一键启用推荐（DeepSeek 官方）", G_SEARCH, G_SEARCH),
        i("S4", "搜索来源", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S5", "DeepSeek 搜索 Key", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S6", "Tavily API Key", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S7", "Tavily 增强搜索", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S8", "Tavily 搜索深度", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S9", "SearXNG 实例地址", G_SEARCH, G_SEARCH, "searchConfig"),
        i("S10", "搜索 Key 隐私说明", G_SEARCH, G_SEARCH),

        // ---- 接口与模型 › 翻译 ----
        i("T1", "翻译语言", G_TRANSLATE, G_TRANSLATE, "lang"),
        i("T2", "翻译引擎", G_TRANSLATE, G_TRANSLATE, "translateEngine"),
        i("T3", "DeepL 密钥", G_TRANSLATE, G_TRANSLATE, "deeplApiKey", adv = true),
        i("T4", "百度应用 ID", G_TRANSLATE, G_TRANSLATE, "baiduAppId", adv = true),
        i("T5", "百度密钥", G_TRANSLATE, G_TRANSLATE, "baiduApiKey", adv = true),
        i("T6", "百度签名密钥", G_TRANSLATE, G_TRANSLATE, "baiduSecret", adv = true),
        i("T7", "百度翻译模型", G_TRANSLATE, G_TRANSLATE, "baiduModel", adv = true),

        // ---- 小球与识别 › 识别模式 ----
        i("M1", "当前识别模式", G_MODES, G_MODES, "mode"),
        i("M2", "内置模式的面板不透明度", G_MODES, G_MODES, "modeOpacity_*"),
        i("M3", "自定义模式（名字 / 提示词 / 不透明度 / 删除 / 填入示例）", G_MODES, G_MODES, "customModes"),
        i("M4", "新建自定义模式", G_MODES, G_MODES, "customModes"),
        i("M5", "做题解析强度", G_MODES, G_MODES, "detail"),
        i("M6", "纯小白讲解", G_MODES, G_MODES, "novice"),
        i("M7", "识别关键词", G_MODES, G_MODES, "keywords"),

        // ---- 小球与识别 › 面板与小球 ----
        i("N1", "面板出现位置", G_PANEL, G_PANEL, "panelPos"),
        i("N2", "面板宽度", G_PANEL, G_PANEL, "panelWidth*"),
        i("N3", "不满宽时靠哪边", G_PANEL, G_PANEL, "panelAlign"),
        i("N4", "面板离屏幕边", G_PANEL, G_PANEL, "panelOffsetDp", adv = true),
        i("N5", "半屏档正文最高", G_PANEL, G_PANEL, "panelHalfPct", adv = true),
        i("N6", "大屏档正文最高", G_PANEL, G_PANEL, "panelFullPct", adv = true),
        i("N7", "面板和小球恢复默认", G_PANEL, G_PANEL),
        i("N8", "小球大小", G_PANEL, G_PANEL, "orbSizeDp"),
        i("N9", "小球离屏幕边远一点", G_PANEL, G_PANEL, "orbFar", adv = true),
        i("N10", "闲置时缩进屏幕边一半", G_PANEL, G_PANEL, "orbTuck", adv = true),
        i("N11", "闲置时不透明度", G_PANEL, G_PANEL, "orbIdleAlpha", adv = true),

        // ---- 小球与识别 › 自动扫描 ----
        i("Q1", "自动扫描说明", G_SCAN, G_SCAN),
        i("Q2", "自动扫描间隔（秒）", G_SCAN, G_SCAN, "intervalSec"),
        i("Q3", "画面一直不变时自动暂停", G_SCAN, G_SCAN, "scanPauseOnStill"),
        i("Q4", "连续多少轮没变化就暂停", G_SCAN, G_SCAN, "scanStillRounds"),

        // ---- 外观与阅读 ----
        i("L1", "配色画廊", G_COLOR, G_COLOR, "themeId"),
        i("L2", "自定义配色（选用）", G_COLOR, G_COLOR, "themeId"),
        i("L3", "配色预览 + 对比度", G_COLOR, G_COLOR),
        i("L4", "自定义颜色逐项调（底色 / 渐变 / 不透明度 / 文字 / 小球）", G_COLOR, G_COLOR, "themeC_*", adv = true),
        i("L5", "面板正文字号", G_COLOR, S_LOOK_TEXT, "textSp"),
        i("C1", "自定义中心入口", G_CUSTOM, G_CUSTOM),

        // ---- 背单词 › 弹词设置 ----
        i("W1", "隔一段时间弹一张卡片", G_WORDS_POP, G_WORDS_POP, "wordOn"),
        i("W2", "弹词间隔（秒）", G_WORDS_POP, G_WORDS_POP, "wordEverySec"),
        i("W3", "夜里不弹", G_WORDS_POP, G_WORDS_POP, "wordQuiet"),
        i("W4", "卡片自动收起（秒）", G_WORDS_POP, G_WORDS_POP, "wordCardStaySec", adv = true),
        i("W5", "英文词弹出时自动读一遍", G_WORDS_POP, G_WORDS_POP, "wordSpeak", adv = true),
        i("W6", "弹词卡片说明", G_WORDS_POP, G_WORDS_POP),

        // ---- 背单词 › 词库 ----
        i("V1", "今天背了几个", G_WORDS_DECK, G_WORDS_DECK, "wordToday"),
        i("V2", "勾选词库", G_WORDS_DECK, G_WORDS_DECK, "wordDecksOn"),
        i("V3", "自定义词库编辑 / 保存 / 删除", G_WORDS_DECK, G_WORDS_DECK, "customDecks"),
        i("V4", "不认识的词（自动错词本）", G_WORDS_DECK, G_WORDS_DECK, "wordDecksOn"),
        i("V5", "从剪贴板导入为新词库", G_WORDS_DECK, G_WORDS_DECK, "customDecks"),
        i("V6", "新建词库", G_WORDS_DECK, G_WORDS_DECK, "customDecks"),
        i("V7", "重置背词进度", G_WORDS_DECK, G_WORDS_DECK, "wordStats"),
        i("V8", "词库来源 / 格式说明", G_WORDS_DECK, G_WORDS_DECK),

        // ---- 提醒与免打扰 › 提醒与知识卡 ----
        i("R1", "精确提醒授权", G_REMIND, G_REMIND),
        i("R2", "测一条提醒（10 秒后）", G_REMIND, G_REMIND),
        i("R3", "震动强度", G_REMIND, S_POP_VIBE, "vibrateLevel"),
        i("R4", "记期列表", G_REMIND, G_REMIND),
        i("R5", "主动提醒（弹出纪律）", G_REMIND, S_POP_RULE, "popupOn"),
        i("R6", "试弹一条", G_REMIND, S_POP_RULE),
        i("R7", "免打扰时段（开关 / 开始 / 结束）", G_REMIND, S_POP_RULE, "popupQuietOn", "popupQuietStart", "popupQuietEnd"),
        i("R8", "待办与随手记", G_REMIND, G_REMIND),
        i("R9", "局域网同步入口", G_REMIND, S_DATA_SYNC),
        i("R10", "统一弹出时刻", G_REMIND, S_POP_BRIEF, "popupUnifiedOn", "popupUnifiedMin", adv = true),
        i("R11", "早简报 / 晚简报 / 复习提醒时刻", G_REMIND, S_POP_BRIEF, "briefMorningMin", "briefNightMin", "reviewNotiMin", adv = true),
        i("R12", "早简报", G_REMIND, S_POP_BRIEF, "briefMorningOn"),
        i("R13", "晚简报", G_REMIND, S_POP_BRIEF, "briefNightOn"),
        i("R14", "学习与复习 · 自定义计划", G_REMIND, S_STUDY),
        i("R15", "知识卡到期提醒", G_REMIND, S_POP_BRIEF, "reviewNotiOn"),
        i("R16", "导入知识卡（AI 生成）", G_REMIND, S_STUDY),
        i("R17", "导入 Anki 牌组", G_REMIND, S_STUDY),
        i("R18", "知识卡库", G_REMIND, S_STUDY),
    )

    /**
     * 改版新增、不来自旧设置页的项：版本行 + 语音转写入口 + 专注守护 / 陪伴气泡两张卡。
     *
     * 这些都不是从旧设置页搬迁过来的（旧版没有语音、没有专注守护），所以按新项登记 —— 不塞进 [ITEMS]，
     * 那张表只收旧设置页上的项。
     */
    const val OLD_NEW = "new"
    val NEW_ITEMS: List<Item> = listOf(
        i("NEW1", "版本号 / versionCode / 渠道", OLD_NEW, S_ABOUT_VER),
        i("NEW2", "语音转写（豆包 / 阿里云 · 麦克风 / 系统声音 / 混录）", OLD_NEW, S_VOICE),
        i("NEW3", "专注守护（指定学习应用 / 屏幕内容 · 离开多久算走开 · 使用情况访问授权）", OLD_NEW, S_FOCUS),
        i("NEW4", "陪伴气泡（多久露一次 · 本机短句 · 免打扰不冒泡）", OLD_NEW, S_COMPANION),
        i("NEW5", "语音对话（腾讯云识别与合成 · 当前大模型 · 音色 / 语速 / 音量 · 插话打断）", OLD_NEW, S_VOICE_TALK),
    )
}
