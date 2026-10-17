package com.chun.lingzhu

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * v1.73 弹出时刻的纯逻辑（不碰 [SharedPreferences]，单测直接测 [effective]）：
 * 统一弹出时刻开着 → 三类主动弹出（早简报 / 晚简报 / 知识卡复习提醒）共用 [unifiedMin]；
 * 关着 → 各自用独立时刻。[Prefs] 的三个 effective 属性就是这里的包装。
 */
object PopupTimes {
    /** 单独设时各用各的；统一开着时一律取统一时刻 */
    fun effective(unifiedOn: Boolean, unifiedMin: Int, ownMin: Int): Int =
        if (unifiedOn) unifiedMin else ownMin
}

/** 所有设置都在这里（SharedPreferences，应用私有目录，别的应用读不到） */
class Prefs(ctx: Context) {
    private val app: Context = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("lingzhu", Context.MODE_PRIVATE)

    var studyPlan: StudyPlan
        get() = StudyPlan.fromJson(sp.getString("studyPlan", null))
        set(v) = sp.edit().putString("studyPlan", v.toJson()).apply()

    var baseUrl: String
        get() = sp.getString("baseUrl", "") ?: ""
        set(v) = sp.edit().putString("baseUrl", v.trim()).apply()
    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v.trim()).apply()

    // ---- 接口方案（v1.65）：自己填的接口存成多套，设置页一键整组切换 ----
    var customApis: List<ApiProfiles.Profile>
        get() = ApiProfiles.parse(sp.getString("customApis", null))
        set(v) = sp.edit().putString("customApis", ApiProfiles.toJson(v)).apply()

    /** 把当前激活的接口（baseUrl/apiKey/model）存成一个方案；同名就覆盖更新 */
    fun saveApiProfile(label: String): ApiProfiles.Profile {
        val cur = ApiProfiles.Profile(ApiProfiles.newId(customApis), label.ifBlank { ApiProfiles.newName(customApis) }, baseUrl.trim(), apiKey.trim(), model.trim())
        val same = customApis.firstOrNull { it.label == cur.label }
        customApis = if (same != null) customApis.map { if (it.id == same.id) cur.copy(id = same.id) else it } else customApis + cur
        return cur
    }

    fun deleteApiProfile(id: String) { customApis = customApis.filter { it.id != id } }

    // ---- 用量核算（v1.65）：识别/追问/翻译按天记 token 与字数（真实 usage 优先，没有则估算输出）----
    fun noteUsage(pin: Int, pout: Int, chars: Int) {
        val m = Usage.parse(sp.getString("usage", null))
        Usage.add(m, todayInt().toString(), pin, pout, chars)
        sp.edit().putString("usage", Usage.toJson(m)).apply()
    }
    fun usageToday(): Usage.Day = Usage.dayTotal(Usage.parse(sp.getString("usage", null)), todayInt().toString())
    fun usageTotal(): Usage.Day = Usage.total(Usage.parse(sp.getString("usage", null)))

    /** 缓存命中次数（v1.65）：设置页「已省 N 次调用」 */
    var cacheHits: Int
        get() = sp.getInt("cacheHits", 0)
        set(v) = sp.edit().putInt("cacheHits", v).apply()
    fun noteCacheHit() = sp.edit().putInt("cacheHits", sp.getInt("cacheHits", 0) + 1).apply()

    // ---- 更新通道（v1.65）：清单地址 + 上次自动检查时刻 ----
    /**
     * 更新清单地址。**留空 = 回落到 [Update.DEFAULT_MANIFEST_URL]**；本开源副本该默认值为空，
     * 因此留空等价于「不检查更新」。
     *
     * 关键：SP 里存了空串也要当「没设置」，否则用户清空输入框后会永久留在空地址上
     * （这里读不到非空白值就回默认值；默认值本身为空时更新通道保持关闭）。
     * （setter 仍保留 trim 语义；这里不用 trim，只判空白，避免把全空格的值当成手填地址。）
     */
    var updateUrl: String
        get() = sp.getString("updateUrl", null)?.takeIf { it.isNotBlank() } ?: Update.DEFAULT_MANIFEST_URL
        set(v) = sp.edit().putString("updateUrl", v.trim()).apply()
    var updateLastCheck: Long
        get() = sp.getLong("updateLastCheck", 0L)
        set(v) = sp.edit().putLong("updateLastCheck", v).apply()
    /** v1.77：正在下的更新包在系统 DownloadManager 里的 id（0 = 没在下）。
     *  记在这儿，App 被杀掉 / 杀掉重进设置页时还能接着看进度、接着弹安装（见 MainActivity.restoreUpdateDownload） */
    var updateDlId: Long
        get() = sp.getLong("updateDlId", 0L)
        set(v) = sp.edit().putLong("updateDlId", v).apply()

    // ---- 设置页折叠组（v1.79，见 MainActivity.Sections）----
    /**
     * 当前展开着的折叠组 id 全集。
     * 存「展开的全集」而不是「手动展开过的」，这样默认展开的组（词库 / 提醒）被用户收起后不会被弹回来。
     * null = 从来没点过（首次进设置页），这时用 [MainActivity.Sections.defaults] 那份默认清单。
     */
    var openGroups: Set<String>?
        get() = if (sp.contains("openGroups")) sp.getStringSet("openGroups", null)?.toSet() else null
        set(v) = sp.edit().putStringSet("openGroups", v ?: emptySet<String>()).apply()

    // ---- 首次配置引导（v1.66，见 Onboarding）----
    /** 引导走完了（或老用户升级时判定已配置过，直接记成走完） */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
    /** 引导开始过（中途去系统设置开权限、进程被杀再回来，也接着走引导，不被当成老用户跳过） */
    var onboardStarted: Boolean
        get() = sp.getBoolean("onboardStarted", false)
        set(v) = sp.edit().putBoolean("onboardStarted", v).apply()
    /** 引导里「这一步先不开」的可选步骤 id（无障碍 / 通知 / 电池优化） */
    var onboardSkipped: Set<String>
        get() = sp.getStringSet("onboardSkipped", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("onboardSkipped", v).apply()
    /** 现在存着的全部键名（判断老用户配置过没有） */
    fun keys(): Set<String> = sp.all.keys.toSet()

    // ---- 原始键值读写（v1.69 CustomizeStore 用）----
    // 自定义项的值不都住在具名字段里：多数项直接映射到上面那些字段，
    // 剩下的（人格三项、模板、缩放比例…）按 "cus_<specId>" 存在这儿，键名规则只有这一处。
    fun rawGet(key: String): String? = sp.getString(key, null)
    fun rawPut(key: String, v: String) = sp.edit().putString(key, v).apply()
    fun rawRemove(key: String) = sp.edit().remove(key).apply()
    fun rawKeys(prefix: String): Set<String> = sp.all.keys.filter { it.startsWith(prefix) }.toSet()

    /** 当前模式：general / exam / custom:<id>（见 Modes） */
    var mode: String
        get() {
            val raw = sp.getString("mode", Prompts.MODE_GENERAL)
            val m = Modes.normalize(raw, customModes)
            // v1.66：删掉的「通识」第一次读到时就写回通用（之后不用每次都迁）
            if (raw == Modes.LEGACY_LEARN) sp.edit().putString("mode", m).apply()
            return m
        }
        set(v) = sp.edit().putString("mode", v).apply()
    /** 自定义模式列表（v1.58）；老版本的单个「自定义」第一次读时迁移成 c1 */
    var customModes: List<Modes.Custom>
        get() = if (sp.contains("customModes")) Modes.parse(sp.getString("customModes", null))
                else listOf(Modes.Custom("c1", "自定义", customPrompt))
        set(v) = sp.edit().putString("customModes", Modes.toJson(v)).apply()
    fun activeCustom(): Modes.Custom? = Modes.customId(mode)?.let { id -> customModes.firstOrNull { it.id == id } }
    fun updateCustom(id: String, f: (Modes.Custom) -> Modes.Custom) { customModes = customModes.map { if (it.id == id) f(it) else it } }
    /** 面板上的模式小标签：「做题·标准·小白」「错题本」 */
    fun modeSummary(): String {
        val m = mode
        val parts = mutableListOf(Modes.label(m, customModes))
        if (m == Prompts.MODE_EXAM) parts.add(Prompts.DETAIL_LABEL.getValue(detail))
        if (novice) parts.add("小白")
        return parts.joinToString("·")
    }
    /** 这个模式自己的面板不透明度（%），0 = 跟随配色 */
    fun modeOpacity(m: String): Int = Modes.customId(m)?.let { id -> customModes.firstOrNull { it.id == id }?.opacity ?: 0 }
        ?: Modes.normOpacity(sp.getInt("modeOpacity_$m", 0))
    fun setModeOpacity(m: String, v: Int) {
        val o = Modes.normOpacity(v)
        val id = Modes.customId(m)
        if (id != null) updateCustom(id) { it.copy(opacity = o) } else sp.edit().putInt("modeOpacity_$m", o).apply()
    }
    /** 开源副本只有一个渠道：默认「标准」（不跳步、逐个分析选项的档由用户自己改） */
    var detail: String
        get() = Prompts.normDetail(sp.getString("detail", "standard"))
        set(v) = sp.edit().putString("detail", Prompts.normDetail(v)).apply()
    var novice: Boolean
        get() = sp.getBoolean("novice", false)
        set(v) = sp.edit().putBoolean("novice", v).apply()
    /** v1.54~1.57 唯一的自定义提示词；v1.58 起只用来迁移成第一个自定义模式 */
    var customPrompt: String
        get() = sp.getString("customPrompt", "") ?: ""
        set(v) = sp.edit().putString("customPrompt", v).apply()
    /** 无障碍截屏曾经开过（用来识别「被系统关掉了」：强行停止 / 一键清理会把它从已开列表里移除） */
    var a11yEverOn: Boolean
        get() = sp.getBoolean("a11yEverOn", false)
        set(v) = sp.edit().putBoolean("a11yEverOn", v).apply()
    /** 默认「高」；已存在且合法的 off/low/high/max 一律保留，不迁移不覆盖 */
    var thinking: String
        get() = sp.getString("thinking", "high").takeIf { it in Prompts.THINK_EFFORTS } ?: "high"
        set(v) = sp.edit().putString("thinking", v).apply()
    var lang: String
        get() = Prompts.lang(sp.getString("lang", "auto") ?: "auto").id
        set(v) = sp.edit().putString("lang", v).apply()
    /**
     * 翻译引擎（v1.61 起四选一，见 Translate.plan）：
     * auto = DeepL → 百度 → 大模型 依次兜底；deepl / baidu / ai = 只用这一个。
     * 开源副本不含任何内置凭据，默认只用用户自己的 AI 接口（ai）。
     */
    var translateEngine: String
        get() = sp.getString("translateEngine", null)?.takeIf { it in Translate.ENGINES } ?: Translate.AI
        set(v) = sp.edit().putString("translateEngine", v).apply()
    /** 自己填的 DeepL Key（本副本不内置） */
    var deeplApiKey: String
        get() = sp.getString("deeplApiKey", "") ?: ""
        set(v) = sp.edit().putString("deeplApiKey", v.trim()).apply()
    fun deepl() = DeepL(deeplApiKey)
    var baiduAppId: String
        get() = sp.getString("baiduAppId", "") ?: ""
        set(v) = sp.edit().putString("baiduAppId", v.trim()).apply()
    var baiduApiKey: String
        get() = sp.getString("baiduApiKey", "") ?: ""
        set(v) = sp.edit().putString("baiduApiKey", v.trim()).apply()
    var baiduSecret: String
        get() = sp.getString("baiduSecret", "") ?: ""
        set(v) = sp.edit().putString("baiduSecret", v.trim()).apply()
    /** "llm" = 大模型翻译（默认），"nmt" = 机器翻译 */
    var baiduModel: String
        get() = sp.getString("baiduModel", "llm").takeIf { it in BaiduTranslate.MODELS } ?: "llm"
        set(v) = sp.edit().putString("baiduModel", v).apply()
    fun baidu() = BaiduTranslate(baiduAppId, baiduApiKey, baiduSecret, baiduModel)

    /** [AgentMemory] Agent 长期记忆总开关（默认关：要多花一点 token 做提炼，让用户自己开） */
    var agentMemOn: Boolean
        get() = sp.getBoolean("agentMemOn", false)
        set(v) = sp.edit().putBoolean("agentMemOn", v).apply()
    /** 自动扫描间隔（秒），5 秒 ~ 1 小时 */
    var intervalSec: Int
        get() = sp.getInt("intervalSec", 20).coerceIn(5, 3600)
        set(v) = sp.edit().putInt("intervalSec", v.coerceIn(5, 3600)).apply()
    // ---- 自动扫描省钱（v1.70）----
    /** 画面连续很多轮都没变化就自动暂停（默认开）—— 忘了关、题目停在一页不动时别整晚空烧 */
    var scanPauseOnStill: Boolean
        get() = sp.getBoolean("scanPauseOnStill", true)
        set(v) = sp.edit().putBoolean("scanPauseOnStill", v).apply()
    // ---- 弹出纪律（M2，主脑整合期补齐：PopupCenter/Todo/Memo/Briefing 引用的字段）----
    /**
     * v1.73 统一弹出时刻：开 = 早简报 / 晚简报 / 知识卡复习提醒 三类主动弹出共用同一个时刻 [popupUnifiedMin]；
     * 关 = 各自用原来的独立时刻（[briefMorningMin] / [briefNightMin] / [reviewNotiMin]），向后兼容。
     * 目的：用户只要设一个时间就能管住"什么时候集中弹"，不用记三处。
     */
    var popupUnifiedOn: Boolean
        get() = sp.getBoolean("popupUnifiedOn", false)
        set(v) = sp.edit().putBoolean("popupUnifiedOn", v).apply()
    /** 统一弹出时刻（分钟 0~1439，默认 20:00）。开着 [popupUnifiedOn] 时三类主动弹出都用它。 */
    var popupUnifiedMin: Int
        get() = sp.getInt("popupUnifiedMin", 20 * 60).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("popupUnifiedMin", v.coerceIn(0, 1439)).apply()
    /** 早简报实际弹出时刻：统一开着就用统一时刻，否则用自己那套 */
    val briefMorningEffectiveMin: Int get() = PopupTimes.effective(popupUnifiedOn, popupUnifiedMin, briefMorningMin)
    /** 晚简报实际弹出时刻 */
    val briefNightEffectiveMin: Int get() = PopupTimes.effective(popupUnifiedOn, popupUnifiedMin, briefNightMin)
    /** 知识卡复习提醒实际弹出时刻 */
    val reviewEffectiveMin: Int get() = PopupTimes.effective(popupUnifiedOn, popupUnifiedMin, reviewNotiMin)

    /** 弹出总开关（默认开；关了全部只记 History 不弹） */
    var popupOn: Boolean
        get() = sp.getBoolean("popupOn", true)
        set(v) = sp.edit().putBoolean("popupOn", v).apply()
    /** 每日全局弹上限（0~20，默认 8） */
    var popupDailyCap: Int
        get() = sp.getInt("popupDailyCap", PopupPolicy.DAILY_CAP_DEFAULT).coerceIn(PopupPolicy.DAILY_CAP_MIN, PopupPolicy.DAILY_CAP_MAX)
        set(v) = sp.edit().putInt("popupDailyCap", v.coerceIn(PopupPolicy.DAILY_CAP_MIN, PopupPolicy.DAILY_CAP_MAX)).apply()
    /** 每类别软上限（默认 4） */
    var popupCatCap: Int
        get() = sp.getInt("popupCatCap", PopupPolicy.CAT_CAP_DEFAULT).coerceIn(0, PopupPolicy.DAILY_CAP_MAX)
        set(v) = sp.edit().putInt("popupCatCap", v.coerceIn(0, PopupPolicy.DAILY_CAP_MAX)).apply()
    /** 免打扰时段开关与起止（分钟数 0~1439，默认 22:30~7:30） */
    var popupQuietOn: Boolean
        get() = sp.getBoolean("popupQuietOn", true)
        set(v) = sp.edit().putBoolean("popupQuietOn", v).apply()
    var popupQuietStart: Int
        get() = sp.getInt("popupQuietStart", PopupPolicy.QUIET_START_DEFAULT).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("popupQuietStart", v.coerceIn(0, 1439)).apply()
    var popupQuietEnd: Int
        get() = sp.getInt("popupQuietEnd", PopupPolicy.QUIET_END_DEFAULT).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("popupQuietEnd", v.coerceIn(0, 1439)).apply()
    /** 「稍后」默认延迟分钟 */
    var popupSnoozeMin: Int
        get() = sp.getInt("popupSnoozeMin", PopupPolicy.SNOOZE_DEFAULT_MIN).coerceIn(5, 120)
        set(v) = sp.edit().putInt("popupSnoozeMin", v.coerceIn(5, 120)).apply()
    /** 弹出纪律状态机的持久化快照（PopupPolicy.toJson / parse） */
    var popupState: String
        get() = sp.getString("popupState", "") ?: ""
        set(v) = sp.edit().putString("popupState", v).apply()
    // ---- 早晚简报（M3）----
    var briefMorningOn: Boolean
        get() = sp.getBoolean("briefMorningOn", true)
        set(v) = sp.edit().putBoolean("briefMorningOn", v).apply()
    /** 早简报时间（分钟 0~1439，默认 7:30） */
    var briefMorningMin: Int
        get() = sp.getInt("briefMorningMin", 7 * 60 + 30).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("briefMorningMin", v.coerceIn(0, 1439)).apply()
    var briefNightOn: Boolean
        get() = sp.getBoolean("briefNightOn", true)
        set(v) = sp.edit().putBoolean("briefNightOn", v).apply()
    /** 晚简报时间（默认 22:00） */
    var briefNightMin: Int
        get() = sp.getInt("briefNightMin", 22 * 60).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("briefNightMin", v.coerceIn(0, 1439)).apply()
    // ---- K3 知识卡复习提醒（见 ReviewNotify）----
    /** 到期复习提醒开关（默认开）。关掉后连闹钟一起撤，不是排了不响。 */
    var reviewNotiOn: Boolean
        get() = sp.getBoolean("reviewNotiOn", true)
        set(v) = sp.edit().putBoolean("reviewNotiOn", v).apply()
    /** 每天几点检查到期卡（分钟 0~1439，默认 20:00） */
    var reviewNotiMin: Int
        get() = sp.getInt("reviewNotiMin", ReviewNotify.DEFAULT_MIN).coerceIn(0, 1439)
        set(v) = sp.edit().putInt("reviewNotiMin", v.coerceIn(0, 1439)).apply()
    /** 连续多少轮没变化就暂停（夹在 AutoScanPolicy 的范围内），默认 10 */
    var scanStillRounds: Int
        get() = AutoScanPolicy.clampRounds(sp.getInt("scanStillRounds", AutoScanPolicy.DEFAULT_STILL_ROUNDS))
        set(v) = sp.edit().putInt("scanStillRounds", AutoScanPolicy.clampRounds(v)).apply()
    /** 答案面板档位：0 收起 / 1 半屏 / 2 大屏（v1.52） */
    var panelState: Int
        get() = sp.getInt("panelState", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("panelState", v.coerceIn(0, 2)).apply()
    /** 正文字号 sp（12~22，v1.52） */
    var textSp: Int
        get() = sp.getInt("textSp", 15).coerceIn(12, 22)
        set(v) = sp.edit().putInt("textSp", v.coerceIn(12, 22)).apply()
    // ---- 面板位置和大小（v1.60，见 PanelLayout）----
    var panelPos: String
        get() = PanelLayout.norm(sp.getString("panelPos", PanelLayout.DEFAULT))   // v1.66：默认从左边滑出
        set(v) = sp.edit().putString("panelPos", PanelLayout.norm(v)).apply()
    var panelWidthPct: Int
        get() = sp.getInt("panelWidthPct", 100).coerceIn(40, 100)
        set(v) = sp.edit().putInt("panelWidthPct", v.coerceIn(40, 100)).apply()
    var panelSideWidthPct: Int
        get() = sp.getInt("panelSideWidthPct", 62).coerceIn(40, 100)
        set(v) = sp.edit().putInt("panelSideWidthPct", v.coerceIn(40, 100)).apply()
    var panelFreeWidthPct: Int
        get() = sp.getInt("panelFreeWidthPct", 80).coerceIn(40, 100)
        set(v) = sp.edit().putInt("panelFreeWidthPct", v.coerceIn(40, 100)).apply()
    var panelAlign: String
        get() = PanelLayout.normAlign(sp.getString("panelAlign", PanelLayout.ALIGN_CENTER))
        set(v) = sp.edit().putString("panelAlign", PanelLayout.normAlign(v)).apply()
    /** 离贴着的屏幕边多远（dp，0~200） */
    var panelOffsetDp: Int
        get() = sp.getInt("panelOffsetDp", 0).coerceIn(0, 200)
        set(v) = sp.edit().putInt("panelOffsetDp", v.coerceIn(0, 200)).apply()
    var panelHalfPct: Int
        get() = sp.getInt("panelHalfPct", 42).coerceIn(PanelLayout.HALF_MIN, PanelLayout.HALF_MAX)
        set(v) = sp.edit().putInt("panelHalfPct", v.coerceIn(PanelLayout.HALF_MIN, PanelLayout.HALF_MAX)).apply()
    var panelFullPct: Int
        get() = sp.getInt("panelFullPct", 80).coerceIn(PanelLayout.FULL_MIN, PanelLayout.FULL_MAX)
        set(v) = sp.edit().putInt("panelFullPct", v.coerceIn(PanelLayout.FULL_MIN, PanelLayout.FULL_MAX)).apply()
    /** 自由位置：面板左上角的屏幕比例（‰） */
    fun setPanelFree(x: Int, y: Int) = sp.edit().putInt("panelFreeX", x).putInt("panelFreeY", y).putString("panelPos", PanelLayout.FREE).apply()
    fun panelOptions() = PanelLayout.Options(panelPos, panelWidthPct, panelSideWidthPct, panelFreeWidthPct, panelAlign, panelOffsetDp,
        sp.getInt("panelFreeX", 100), sp.getInt("panelFreeY", 300))
    /** 当前位置用的是哪一个宽度设置（设置页 / 托盘的「宽度」调它） */
    var panelCurWidthPct: Int
        get() = when (PanelLayout.norm(panelPos)) {
            PanelLayout.TOP, PanelLayout.BOTTOM, PanelLayout.AWAY -> panelWidthPct
            PanelLayout.FREE -> panelFreeWidthPct
            else -> panelSideWidthPct
        }
        set(v) = when (PanelLayout.norm(panelPos)) {
            PanelLayout.TOP, PanelLayout.BOTTOM, PanelLayout.AWAY -> panelWidthPct = v
            PanelLayout.FREE -> panelFreeWidthPct = v
            else -> panelSideWidthPct = v
        }
    fun resetPanelLayout() = sp.edit().remove("panelPos").remove("panelWidthPct").remove("panelSideWidthPct").remove("panelFreeWidthPct")
        .remove("panelAlign").remove("panelOffsetDp").remove("panelHalfPct").remove("panelFullPct").remove("panelFreeX").remove("panelFreeY")
        .remove("orbSizeDp").apply()
    /** 小球大小（dp，40~84，默认 58） */
    var orbSizeDp: Int
        get() = sp.getInt("orbSizeDp", 58).coerceIn(40, 84)
        set(v) = sp.edit().putInt("orbSizeDp", v.coerceIn(40, 84)).apply()

    var orbX: Int
        get() = sp.getInt("orbX", -1)
        set(v) = sp.edit().putInt("orbX", v).apply()
    var orbY: Int
        get() = sp.getInt("orbY", -1)
        set(v) = sp.edit().putInt("orbY", v).apply()
    /**
     * v1.58：false（默认）= 小球贴近屏幕边（只留 4dp，小球那块向系统申请手势豁免）；true = 离远一点，让开系统返回手势区。
     * v1.57 的 orbHugEdge 废弃：缩边另由 orbTuck 管。
     */
    var orbFar: Boolean
        get() = sp.getBoolean("orbFar", false)
        set(v) = sp.edit().putBoolean("orbFar", v).apply()
    /** 闲置时缩进屏幕边一半（默认开；缩小变淡之外再藏进去半个） */
    var orbTuck: Boolean
        get() = sp.getBoolean("orbTuck", true)
        set(v) = sp.edit().putBoolean("orbTuck", v).apply()
    /** 闲置时小球的不透明度（%）：越小越透明；默认 80（几乎不淡，只靠缩小来让位） */
    var orbIdleAlpha: Int
        get() = sp.getInt("orbIdleAlpha", 80).coerceIn(10, 80)
        set(v) = sp.edit().putInt("orbIdleAlpha", v.coerceIn(10, 80)).apply()

    // ---- v1.69 自定义体系：下面几项是「长按即改」收拢进来的，都能在 CustomizeCenter 里恢复默认 ----

    /**
     * v1.69：闲置时缩到原来的百分之多少（默认 60 = 缩到六成，和 v1.66 以前硬编码的 0.6f 一样）。
     * 单独拎出来是为了让「闲置缩放比例」可调 —— 改坏了会看不出问题，但有人就是喜欢缩更小 / 更大。
     */
    var orbIdleScale: Int
        get() = sp.getInt("orbIdleScale", 60).coerceIn(30, 100)
        set(v) = sp.edit().putInt("orbIdleScale", v.coerceIn(30, 100)).apply()

    /**
     * v1.69：按住小球多少毫秒弹菜单（默认 500）。原先是 HOLD_DELAY 120 + HOLD_FILL 380 写死的 500。
     * 拆成可调是为了照顾两类人：手快的想更灵敏，手指粗 / 手上湿的想更慢一点免得误弹。
     */
    var orbHoldMs: Int
        get() = sp.getInt("orbHoldMs", 500).coerceIn(250, 1500)
        set(v) = sp.edit().putInt("orbHoldMs", v.coerceIn(250, 1500)).apply()

    /** v1.69 快捷短语：面板上长按「发送」弹出的常用说法。归一化交给 [Freeform.normPhrases]（纯函数） */
    var askPhrases: List<String>
        get() = Freeform.parsePhrases(sp.getString("askPhrases", null))
        set(v) = sp.edit().putString("askPhrases", Freeform.toJson(v)).apply()

    /** v1.69：新建自定义模式时预填的模板（留空 = 用 Prompts.CUSTOM_EXAMPLE 内置示例） */
    var customPromptTemplate: String
        get() = sp.getString("customPromptTemplate", "") ?: ""
        set(v) = sp.edit().putString("customPromptTemplate", Customize.clip(v.trim(), Customize.MAX_PROMPT_CHARS)).apply()

    /** v1.69 自定义：人格三项（称呼 / 语气补充 / 口头禅）。空串 = 不用，走 Prompts.PERSONA 默认 */
    var personaName: String
        get() = Customize.clip(sp.getString("cus_persona.name", "")?.trim().orEmpty(), Customize.MAX_TEXT_CHARS)
        set(v) = sp.edit().putString("cus_persona.name", Customize.clip(v.trim(), Customize.MAX_TEXT_CHARS)).apply()
    var personaTone: String
        get() = Customize.clip(sp.getString("cus_persona.tone", "")?.trim().orEmpty(), Customize.MAX_PROMPT_CHARS)
        set(v) = sp.edit().putString("cus_persona.tone", Customize.clip(v.trim(), Customize.MAX_PROMPT_CHARS)).apply()
    var personaCatch: String
        get() = Customize.clip(sp.getString("cus_persona.catch", "")?.trim().orEmpty(), Customize.MAX_TEXT_CHARS)
        set(v) = sp.edit().putString("cus_persona.catch", Customize.clip(v.trim(), Customize.MAX_TEXT_CHARS)).apply()
    /** 结果缓存（v1.58，默认开）：同一画面 / 同一段文字不重复调接口，见 Cache */
    var useCache: Boolean
        get() = sp.getBoolean("useCache", true)
        set(v) = sp.edit().putBoolean("useCache", v).apply()
    /** v1.66 识别关键词（默认开）：所有模式的回答末尾带一行可点的关键词块（型号、规格、术语），和主回答同一次请求返回 */
    var keywords: Boolean
        get() = sp.getBoolean("keywords", KEYWORDS_DEFAULT)
        set(v) = sp.edit().putBoolean("keywords", v).apply()
    /**
     * v1.67 追问省图（默认开）：追问时不重发首轮截图，只带「首轮回答文本 + 裁剪后的对话」。
     * 省的是整张截图折成的上千 prompt token；首轮截图本身照发，只有追问那几轮省。
     * 关掉 = 回到 v1.66 行为（每轮追问都带图），画面里有东西、模型不问图就答错时才用得着。
     */
    var noFollowShot: Boolean
        get() = sp.getBoolean("noFollowShot", NO_FOLLOW_SHOT_DEFAULT)
        set(v) = sp.edit().putBoolean("noFollowShot", v).apply()
    /**
     * v1.67 小活走便宜接口（默认关）：关键词补问、翻译这两类不用看图、输出又短的活，
     * 走 [liteProfileId] 指定的那套接口。**默认关** —— 便宜模型讲不清楚复杂题，得用户自己愿意换。
     * 主接口（做题、追问）永远走 [api]，不受这个开关影响。
     */
    var liteApi: Boolean
        get() = sp.getBoolean("liteApi", CostPolicy.LITE_DEFAULT)
        set(v) = sp.edit().putBoolean("liteApi", v).apply()
    /** 小活用哪套接口方案（[customApis] 里的 id）；空 = 没选 = 小活也走主接口 */
    var liteProfileId: String
        get() = sp.getString("liteProfileId", "") ?: ""
        set(v) = sp.edit().putString("liteProfileId", v).apply()

    /**
     * 把「小活走哪套接口」这件事同步给 [CostPolicy]（请求路径上只认 CostPolicy 那两个字段，不回头读 SharedPreferences）。
     * 服务起来、设置页改完开关 / 改完方案列表都要调一次。
     */
    fun syncCostRouting() {
        CostPolicy.liteEnabled = liteApi
        val p = customApis.firstOrNull { it.id == liteProfileId }
        CostPolicy.liteApi = p?.let { ApiConfig(it.baseUrl, it.apiKey, it.model, thinking) }
    }

    // ---- 联网搜索（v1.68，见 WebSearch）----
    /**
     * 「联网搜索」开关，**默认关**。开着也不是每轮都搜 —— 还要过 [WebSearch.intentOf] 的问法判定（见 [Solver.withToolsNow]）。
     * 关着时请求体里连 tools 字段都没有，和 v1.67 一个字节不差。
     */
    var webSearch: Boolean
        get() = sp.getBoolean("webSearch", WEB_SEARCH_DEFAULT)
        set(v) = sp.edit().putBoolean("webSearch", v).apply()
    /** 用哪个搜索源（[WebSearch.PROVIDERS] 里选一个） */
    var searchProvider: String
        get() = sp.getString("searchProvider", WebSearch.TAVILY)?.takeIf { it in WebSearch.PROVIDERS } ?: WebSearch.TAVILY
        set(v) = sp.edit().putString("searchProvider", v).apply()
    var tavilyKey: String
        get() = sp.getString("tavilyKey", "") ?: ""
        set(v) = sp.edit().putString("tavilyKey", v.trim()).apply()
    /** SearXNG 实例地址（要带 http:// 或 https://） */
    var searxngBase: String
        get() = sp.getString("searxngBase", "") ?: ""
        set(v) = sp.edit().putString("searxngBase", v.trim()).apply()
    /** Tavily 的搜索深度（basic / advanced） */
    var searchDepth: String
        get() = sp.getString("searchDepth", WebSearch.TAVILY_DEPTH_DEFAULT)?.takeIf { it in WebSearch.TAVILY_DEPTHS } ?: WebSearch.TAVILY_DEPTH_DEFAULT
        set(v) = sp.edit().putString("searchDepth", v).apply()

    /**
     * 把联网搜索的设置同步给 [WebSearch]（同 [syncCostRouting] 的理由：请求路径上只认 WebSearch 那两个字段，不回头读 SharedPreferences）。
     * 开关关着也同步 —— 让 WebSearch.enabled 跟着设置走，请求侧就不会误带 tools。
     */
    fun syncSearch() {
        WebSearch.enabled = webSearch
        WebSearch.engine = WebSearch.provider(searchProvider, tavilyKey, searxngBase, searchDepth)
    }
    /** 识别 / 翻译结果存进历史记录（v1.58，默认开；屏幕内容比较私密，可以关） */
    var saveHistory: Boolean
        get() = sp.getBoolean("saveHistory", true)
        set(v) = sp.edit().putBoolean("saveHistory", v).apply()
    /**
     * v1.62：用户想让小球开着（点了「启动」= true；点「退出」/「彻底退出」= false）。
     * 进程被系统杀掉后，服务重启或无障碍重新连上时看它决定要不要把小球带回来。
     */
    var orbWanted: Boolean
        get() = sp.getBoolean("orbWanted", false)
        set(v) = sp.edit().putBoolean("orbWanted", v).commit().let { }
    // ---- 随机弹词（v1.62，见 Words / WordCard）----
    var wordOn: Boolean
        get() = sp.getBoolean("wordOn", false)
        set(v) = sp.edit().putBoolean("wordOn", v).apply()
    /**
     * 多久弹一次（秒，[WORD_EVERY_SEC_MIN] ~ [WORD_EVERY_SEC_MAX]）。
     * v1.82：单位从「分钟」改「秒」，可以精确到秒。老键 wordEveryMin（分钟）只在**新键不存在**时读一次做迁移
     * （默认 15 分钟 × 60 = 900 秒），这样已经调过间隔的老用户不会被重置回默认。
     */
    var wordEverySec: Int
        get() = (if (sp.contains("wordEverySec")) sp.getInt("wordEverySec", WORD_EVERY_SEC_DEF)
                 else sp.getInt("wordEveryMin", 15) * 60).coerceIn(WORD_EVERY_SEC_MIN, WORD_EVERY_SEC_MAX)
        set(v) = sp.edit().putInt("wordEverySec", v.coerceIn(WORD_EVERY_SEC_MIN, WORD_EVERY_SEC_MAX)).apply()
    /**
     * 卡片弹出来后多久没人碰就自动收起（秒，[WORD_STAY_SEC_MIN] ~ [WORD_STAY_SEC_MAX]）。
     * 只管「自然停留」那一档；点「不认识」之后那一下是写死的短停顿（见 WordCard.bumpShort），不受这里影响。
     */
    var wordCardStaySec: Int
        get() = sp.getInt("wordCardStaySec", WORD_STAY_SEC_DEF).coerceIn(WORD_STAY_SEC_MIN, WORD_STAY_SEC_MAX)
        set(v) = sp.edit().putInt("wordCardStaySec", v.coerceIn(WORD_STAY_SEC_MIN, WORD_STAY_SEC_MAX)).apply()
    /** 夜里不弹（23:00~7:00） */
    var wordQuiet: Boolean
        get() = sp.getBoolean("wordQuiet", true)
        set(v) = sp.edit().putBoolean("wordQuiet", v).apply()

    // ---- 知识卡定时弹窗（独立于弹单词；默认关，用户显式开启后才弹）----
    /**
     * 知识卡定时弹窗总开关（默认 **false**）。与 [wordOn] 完全独立 —— 弹单词关着也能只弹知识卡，反之亦然。
     */
    var knowledgePopupOn: Boolean
        get() = sp.getBoolean("knowledgePopupOn", false)
        set(v) = sp.edit().putBoolean("knowledgePopupOn", v).apply()
    /**
     * 知识卡弹窗间隔（秒，[KNOWLEDGE_EVERY_SEC_MIN] ~ [KNOWLEDGE_EVERY_SEC_MAX]，默认 300 = 5 分钟）。
     * 单位与 [wordEverySec] 一致，但**各存各的键**，互不影响。
     */
    var knowledgePopupEverySec: Int
        get() = sp.getInt("knowledgePopupEverySec", KNOWLEDGE_EVERY_SEC_DEF)
            .coerceIn(KNOWLEDGE_EVERY_SEC_MIN, KNOWLEDGE_EVERY_SEC_MAX)
        set(v) = sp.edit().putInt("knowledgePopupEverySec", v.coerceIn(KNOWLEDGE_EVERY_SEC_MIN, KNOWLEDGE_EVERY_SEC_MAX)).apply()
    /**
     * 参与定时弹窗的知识卡牌组（**显式选择**：空集 = 一个都不弹，不是「全部」）。
     * 由导入后的「开启弹窗」按实际入库牌组并集写入，也可在设置页手动勾选。
     */
    var knowledgePopupDecks: Set<String>
        get() = sp.getStringSet("knowledgePopupDecks", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("knowledgePopupDecks", v).apply()
    /**
     * 选中的词库 id（内置分级 wb_* / 内置 en / 自定义的 id）。
     * 没设过时用 [WordBank.DEFAULT_ON]（本开源副本默认为第一级内置词表）。
     */
    var wordDecksOn: Set<String>
        get() = sp.getStringSet("wordDecksOn", null)?.toSet() ?: WordBank.DEFAULT_ON
        set(v) = sp.edit().putStringSet("wordDecksOn", v).apply()
    var customDecks: List<Words.Deck>
        get() = Words.decksMemoized(sp.getString("customDecks", null))
        set(v) = sp.edit().putString("customDecks", Words.decksToJson(v)).apply()
    var wordStats: Map<String, Words.Stat>
        get() = Words.statsFromJson(sp.getString("wordStats", null))
        set(v) = sp.edit().putString("wordStats", Words.statsToJson(v)).apply()
    /**
     * 所有词库（内置在前）→ (词库 id, 名字, 卡片)。
     * v1.77：5 级内置词库开箱可选，排在 [Words.BUILTIN_EN] 之后、自定义词库之前（顺序以 [WordBank.assemble] 为准）；
     * 正文取自 assets 但走进程内缓存（[WordBank.levelDecks]），解析走 [Words.parseMemoized]。
     */
    fun allDecks(): List<Triple<String, String, List<Words.Card>>> = deckList().map { Triple(it.id, it.name, Words.parseMemoized(it.text)) }
    /**
     * 词库清单（内置在前 → 5 级 → 自定义），只要 id/名字/全文：设置页渲染摘要行用。
     * 不解析正文，且正文本身来自 [WordBank] 的进程内缓存 —— 渲染一次不再有任何 IO。
     */
    fun deckList(): List<Words.Deck> = WordBank.assemble(
        Words.Deck(Words.BUILTIN_EN, "英语常用词（内置）", Words.BUILTIN_EN_TEXT), WordBank.levelDecks(app), myDecks())
    /**
     * 自定义词库 = [customDecks] 里真正由用户自己建的：老版本导入过的内置分级副本（id 仍是 wb_xxx）剔除掉。
     * 它们现在由 [WordBank.levelDecks] 直接供给，留着会让同一 id 在清单里出现两行、勾选与进度也重复统计。
     * 只是从清单里跳过，存着的正文与进度一概不动（不做迁移清理）。
     */
    private fun myDecks(): List<Words.Deck> = customDecks.filterNot { WordBank.isLevelDeck(it.id) }
    /** 当前参与抽卡的全部卡片；勾了「不认识的词」就把所有词库里不认识的也加进来（记进度仍记在原词库名下） */
    fun activeCards(): List<Pair<String, Words.Card>> {
        val on = wordDecksOn
        val all = allDecks()
        val base = all.filter { it.first in on }.flatMap { d -> d.third.map { d.first to it } }
        if (Words.MISTAKES !in on) return base
        val st = wordStats
        val miss = all.flatMap { d -> d.third.filter { Words.unknown(st[Words.key(d.first, it)]) }.map { d.first to it } }
        return (base + miss).distinctBy { Words.key(it.first, it.second) }
    }
    fun mistakeCount(): Int { val st = wordStats; return allDecks().sumOf { d -> d.third.count { Words.unknown(st[Words.key(d.first, it)]) } } }
    /** 弹出时自动读一遍（英文） */
    var wordSpeak: Boolean
        get() = sp.getBoolean("wordSpeak", false)
        set(v) = sp.edit().putBoolean("wordSpeak", v).apply()
    var wordToday: Words.Today
        get() = Words.Today(sp.getInt("wtDay", 0), sp.getInt("wtDone", 0), sp.getInt("wtKnown", 0))
        set(v) = sp.edit().putInt("wtDay", v.day).putInt("wtDone", v.done).putInt("wtKnown", v.known).apply()
    fun todayInt(): Int = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()).toInt()

    // ---- 保活可见（v1.63）：被系统清理后自动恢复的次数 ----
    fun noteRevive(how: String) = sp.edit().putInt("reviveCount", sp.getInt("reviveCount", 0) + 1).putLong("reviveLast", System.currentTimeMillis()).putString("reviveHow", how).apply()
    val reviveCount: Int get() = sp.getInt("reviveCount", 0)
    val reviveLast: Long get() = sp.getLong("reviveLast", 0)
    val reviveHow: String get() = sp.getString("reviveHow", "") ?: ""

    // ---- 看门狗（v1.65 第四层）：排上几次 / 被系统拦几次，设置页可见 —— 别让兜底层失效了没人知道 ----
    fun noteWatchdogKick() = sp.edit().putInt("wdKicks", sp.getInt("wdKicks", 0) + 1).apply()
    fun noteWatchdogFail() = sp.edit().putInt("wdFails", sp.getInt("wdFails", 0) + 1).apply()
    val watchdogKicks: Int get() = sp.getInt("wdKicks", 0)
    val watchdogFails: Int get() = sp.getInt("wdFails", 0)

    /** 小球暂时隐藏（打游戏时）：从状态栏通知或快捷开关恢复 */
    var orbHidden: Boolean
        get() = sp.getBoolean("orbHidden", false)
        set(v) = sp.edit().putBoolean("orbHidden", v).apply()

    /**
     * v1.85 免打扰：结束时刻（毫秒；0 = 没开过）。
     * 菜单「免打扰 30 分」按下即写 [Mute.until]，到点自己失效（不需要定时器）。
     * 判定统一走 [Mute.active]，别在这里算。
     */
    var muteUntil: Long
        get() = sp.getLong("muteUntil", 0L)
        set(v) = sp.edit().putLong("muteUntil", v).apply()

    /** 现在是不是静音着（菜单/弹词/提醒/面板都问这一句）。 */
    fun mutedNow(): Boolean = Mute.active(System.currentTimeMillis(), muteUntil)

    /**
     * v1.86 震动强度（[Vibrate.OFF] / LIGHT / MID / STRONG，默认 [Vibrate.DEFAULT]）。
     *
     * 全 App 唯一一处震动强度：UI 反馈（菜单/面板弹出、划词过临界）和提醒到点的试弹卡都读它；
     * 单条记期还能再各自覆盖（见 [Schedule.Rule.strength]，-1 = 跟这里）。
     */
    var vibrateLevel: Int
        get() = Vibrate.norm(sp.getInt("vibrateLevel", Vibrate.DEFAULT))
        set(v) = sp.edit().putInt("vibrateLevel", Vibrate.norm(v)).apply()

    /** 配色方案 id（v1.55）：预设 id 或 Theme.CUSTOM；v1.66 起没选过 = 护眼（Theme.DEFAULT） */
    var themeId: String
        get() = Theme.normId(sp.getString("themeId", null))
        set(v) = sp.edit().putString("themeId", v).apply()

    /**
     * v1.67：跟随系统深浅色（默认开）。开着时系统切深色，浅色预设自动换成它自己的伴生深色
     * （见 Theme.DARK_COMPANION；没配的落到 Theme.DARK_FALLBACK）。存的是 themeId 本身，不存两套 ——
     * 关掉开关立刻回到用户原来选的那套，老用户升级后配色不变。
     * v2.0.3：默认「护眼」仍换到原 night_mint；六套青春配色各自换到同色相的深色变体（保色相）。
     */
    var followSystemDark: Boolean
        get() = sp.getBoolean("followSystemDark", true)
        set(v) = sp.edit().putBoolean("followSystemDark", v).apply()

    /** 系统现在是不是深色（读系统配置；读不到当浅色） */
    fun systemDark(): Boolean = try {
        val cm = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        cm == android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (e: Exception) { false }

    // ---- v1.67 陪伴 M1：记忆卡 + 每日一句 ----
    /**
     * 每日一句（默认开）：面板每天第一次打开，在答案行下面插一句「昨天在搞 X，今天要不要接着来？」。
     * 只在**用户主动打开面板**时插（不主动弹通知 —— 几点弹、弹几次、烦了怎么办那一整套纪律是 M2 的）。
     * X 取记忆卡里最近记的那条话题；卡是空的就不插（硬凑一句反而假）。
     * 想彻底不要这一句就关这个开关，记忆卡照常记。
     */
    var dailyLineEnabled: Boolean
        get() = sp.getBoolean("dailyLineEnabled", DAILY_LINE_DEFAULT)
        set(v) = sp.edit().putBoolean("dailyLineEnabled", v).apply()
    /**
     * 上次插过每日一句的日期（yyyyMMdd，用 [todayInt] 的格式）。
     * 只记「哪一天」不记时刻 —— 同一天连识别 5 次、开 8 次面板都只出现一次；跨零点自动又来一次。
     * 用 commit 不用 apply：插进去的同一帧可能紧接着又开面板，apply 的异步落盘会漏掉这一次判定。
     */
    var dailyLineDay: Int
        get() = sp.getInt("dailyLineDay", 0)
        set(v) = sp.edit().putInt("dailyLineDay", v).commit().let { }

    /** 记忆卡（称呼 / 正在做 / 最近在看）：由 [MemoryCard.Store] 落到 filesDir/card.json，这里只管开关以外的杂事 */
    /** 面板打开那一刻把当前这张卡同步给 [Prompts.card]（system() 每次读它，命中服务端前缀缓存） */
    fun syncCard() { Prompts.card = MemoryCard.Store(app).get().takeIf { !it.isEmpty } }

    /**
     * 面板实际用的配色：配色方案 + 当前模式自己的不透明度（v1.58）。
     * 「跟随系统深浅色」开着时先按系统把预设 id 换成深色变体（themeId 本身不动）。
     */
    fun theme(): Theme {
        val t = baseTheme()
        val withOpacity = modeOpacity(mode).takeIf { it > 0 }?.let { t.copy(opacity = it) } ?: t
        // 自定义没有伴生深色，系统深色时按「自定义面板底是不是深色」原样用（用户自己配的，别改它）
        if (!followSystemDark || themeId == Theme.CUSTOM) return withOpacity
        val eff = Theme.effectiveId(themeId, systemDark(), true)
        return Theme.preset(eff)?.let { if (withOpacity.opacity == t.opacity) it else it.copy(opacity = withOpacity.opacity) } ?: withOpacity
    }

    /**
     * 配色方案本身：预设直接取（六套青春配色带 palette）；自定义读 themeC_* 各项（缺的按深色默认补）。
     * 设置页改颜色时用这个 —— 拿到的是用户选的那套原色，不含系统深色的替换。
     *
     * v2.0.3：自定义一律 palette = null（沿用老的逐项派生渲染）—— 从带 palette 的预设「抄一份改」时
     * 只抄那 8 个字段、不落 themeC_palette，所以不会留下「有 palette 的半新半旧」状态；
     * 也不为新配色做额外持久化迁移（老的自定义存的就是这 8 个字段，读到什么就是什么）。
     */
    fun baseTheme(): Theme {
        Theme.preset(themeId)?.let { return it }
        val d = Theme.DARK
        fun c(k: String, def: Int) = sp.getInt("themeC_$k", def)
        return Theme(c("bg", d.bg), c("opacity", d.opacity).coerceIn(50, 100), c("text", d.text), c("answer", d.answer), c("heading", d.heading), c("orbA", d.orbA), c("orbB", d.orbB),
            bg2 = if (sp.contains("themeC_bg2")) sp.getInt("themeC_bg2", d.bg) else null,
            palette = null)
    }

    /** 现在生效的那套预设 id（跟随系统时可能是伴生深色；设置页用它打勾） */
    fun effectiveThemeId(): String = Theme.effectiveId(themeId, systemDark(), followSystemDark)

    /**
     * 改自定义配色：当前还是预设的话，先把预设整套抄成自定义再改这一项。
     * 只抄那 8 个字段（含 bg2），**不抄 palette** —— 自定义始终是老的逐项配色（见 [baseTheme]）。
     */
    fun setThemeColor(key: String, value: Int) {
        val cur = baseTheme()
        val e = sp.edit()
        if (themeId != Theme.CUSTOM) {
            e.putInt("themeC_bg", cur.bg).putInt("themeC_opacity", cur.opacity).putInt("themeC_text", cur.text).putInt("themeC_answer", cur.answer)
                .putInt("themeC_heading", cur.heading).putInt("themeC_orbA", cur.orbA).putInt("themeC_orbB", cur.orbB)
            // v1.66 渐变底：预设带渐变就一起抄，不带就清掉旧的（不然换过别的预设再改，会冒出上一套的渐变）
            if (cur.bg2 != null) e.putInt("themeC_bg2", cur.bg2) else e.remove("themeC_bg2")
        }
        e.putInt("themeC_$key", value).putString("themeId", Theme.CUSTOM).apply()
    }

    fun onChange(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun offChange(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    /**
     * 实际用的接口：全部来自用户自己的设置。本副本不内置任何接口、Key 或模型，
     * 所以没有「手填地址只覆盖一部分、其余仍用内置」的混用路径。
     */
    fun api(): ApiConfig {
        syncCostRouting()   // v1.67：每条请求路径都先经过这里，顺手把小活路由的最新设置同步给 CostPolicy
        syncSearch()        // v1.68：同理，把联网搜索的开关 / 引擎同步给 WebSearch
        return ApiConfig(baseUrl, apiKey, model, thinking)
    }
    /** 有没有能用的接口（没填地址 = 没有） */
    fun hasApi(): Boolean = api().baseUrl.isNotBlank()
    /** 自定义模式交给 Prompts 时统一叫 MODE_CUSTOM，带上这个模式自己的提示词 */
    fun solveOptions(): Solver.Options {
        val c = activeCustom()
        val o = if (c != null) Solver.Options(Prompts.MODE_CUSTOM, detail, novice, c.prompt, keywords) else Solver.Options(mode, detail, novice, "", keywords)
        // v1.67：省图开关跟着这一轮走（默认开）；v1.68：联网搜索开关也跟着一轮走（默认关）
        return o.copy(noShot = noFollowShot, search = webSearch)
    }

    companion object {
        /** v1.69 自定义项的原始键前缀（见 rawGet）：不在具名字段里的项都按 cus_<specId> 存 */
        const val CUS_PREFIX = "cus_"
        /** 「识别关键词」开关的默认值（v1.66：默认开） */
        const val KEYWORDS_DEFAULT = true
        /** 「追问省图」开关的默认值（v1.67：默认开） */
        const val NO_FOLLOW_SHOT_DEFAULT = true
        /** 「每日一句」开关的默认值（v1.67：默认开） */
        const val DAILY_LINE_DEFAULT = true
        /** 「联网搜索」开关的默认值（v1.68：**默认关** —— 发到第三方这件事得用户自己点头） */
        const val WEB_SEARCH_DEFAULT = WebSearch.ENABLED_DEFAULT
        /** 弹词间隔（秒）：5 秒 ~ 24 小时，默认 900 秒 = 15 分钟（见 [wordEverySec]） */
        const val WORD_EVERY_SEC_MIN = 5
        const val WORD_EVERY_SEC_MAX = 86_400
        const val WORD_EVERY_SEC_DEF = 900
        /** 卡片自动收起（秒）：3 秒 ~ 10 分钟，默认 20 秒（见 [wordCardStaySec]） */
        const val WORD_STAY_SEC_MIN = 3
        const val WORD_STAY_SEC_MAX = 600
        const val WORD_STAY_SEC_DEF = 20
        /** 知识卡弹窗间隔（秒）：30 秒 ~ 24 小时，默认 300 秒 = 5 分钟（见 [knowledgePopupEverySec]） */
        const val KNOWLEDGE_EVERY_SEC_MIN = 30
        const val KNOWLEDGE_EVERY_SEC_MAX = 86_400
        const val KNOWLEDGE_EVERY_SEC_DEF = 300
        val INTERVAL_STEPS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1800, 3600)
        fun fmtInterval(s: Int): String = when {
            s < 60 -> "$s 秒"
            s % 60 == 0 -> "${s / 60} 分钟"
            else -> String.format("%.1f 分钟", s / 60f)
        }
    }
}
