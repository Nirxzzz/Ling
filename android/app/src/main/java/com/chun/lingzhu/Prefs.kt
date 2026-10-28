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
    // v1.92：方案里额外保存思考强度与联网搜索配置；激活的是哪一套用 [activeProfileId] 明确记下来，
    // 不再靠「地址 / 模型碰巧相同」来猜（猜法会把两套不同 Key 的方案误认成同一套）。
    var customApis: List<ApiProfiles.Profile>
        get() = ApiProfiles.parse(sp.getString("customApis", null))
        set(v) = sp.edit().putString("customApis", ApiProfiles.toJson(v)).apply()

    /** 当前激活的是哪套方案；空 = 内置接口或未保存的临时填写 */
    var activeProfileId: String
        get() {
            val stored = sp.getString("activeProfileId", "") ?: ""
            if (stored.isNotBlank()) return stored
            // 用户明确删掉激活方案 / 切了内置接口之后，不再按旧规则猜回来
            if (sp.getBoolean("activeProfileCleared", false)) return ""
            // 老版本没有 activeProfileId：按当时的规则（地址 + 模型一致）认一次并写回，之后一律以显式 id 为准。
            if (sp.contains("apiApplied") || baseUrl.isBlank()) return ""
            val guess = customApis.firstOrNull { it.baseUrl.trim() == baseUrl.trim() && it.model.trim() == model.trim() }?.id ?: ""
            if (guess.isNotBlank()) sp.edit().putString("activeProfileId", guess).apply()
            return guess
        }
        set(v) = sp.edit().putString("activeProfileId", v.trim()).apply()

    fun activeProfile(): ApiProfiles.Profile? = activeProfileId.takeIf { it.isNotBlank() }
        ?.let { id -> customApis.firstOrNull { it.id == id } }

    /** 上一次「应用方案」落下的完整快照；不存在 = 从没应用过方案（或删掉了激活方案） */
    fun appliedSnapshot(): ApiProfiles.Snapshot? = ApiProfiles.parseSnapshot(sp.getString("apiApplied", null))

    /**
     * 把一套方案原子地应用成当前配置：地址 / Key / 模型 / 思考强度 / 搜索配置 / 激活 id / 快照
     * 在**同一个 editor** 里写。中途进程被杀也只会是「旧方案整套」或「新方案整套」，不会地址换了 Key 没换。
     */
    fun applyApiProfile(p: ApiProfiles.Profile) {
        val snap = ApiProfiles.snapshotOf(p)
        sp.edit()
            .putString("baseUrl", p.baseUrl.trim())
            .putString("apiKey", p.apiKey.trim())
            .putString("model", p.model.trim())
            .putString("thinking", ApiProfiles.normThinking(p.thinkingEffort))
            .putString("searchConfig", ApiProfiles.configJson(p.searchConfig))
            .putString("activeProfileId", p.id)
            .putString("apiApplied", ApiProfiles.snapshotJson(snap))
            .remove("activeProfileCleared")
            .apply()
    }

    /**
     * 把当前填写存成方案。[overwriteId] 非空 = 明确更新那一套（保留它的真实 id）。
     * 同名（不同 id）不静默覆盖：返回 [ApiProfiles.SaveResult.conflict]，由设置页问过用户后再带 overwriteId 调一次。
     * 地址空白 = 直接返回 [ApiProfiles.SaveResult.error]（见 [ApiProfiles.ERR_BASE_BLANK]），一处都不写。
     */
    fun saveApiProfile(label: String, overwriteId: String = ""): ApiProfiles.SaveResult {
        // 先校验、后写入：地址空白存不成方案（重读时会被 parse 丢掉），不能报成功还留个悬空的激活 id
        val saveBase = ApiProfiles.saveableBaseUrl(baseUrl)
            ?: return ApiProfiles.SaveResult(error = ApiProfiles.ERR_BASE_BLANK)
        val list = customApis
        val name = label.trim().ifBlank { ApiProfiles.newName(list) }
        val target = overwriteId.trim().takeIf { it.isNotEmpty() }?.let { id -> list.firstOrNull { it.id == id } }
        // 更新自己不算同名；撞到别的方案一律先问用户，绝不静默覆盖 / 造出两套同名方案
        ApiProfiles.conflict(list, name, target?.id ?: "")?.let { return ApiProfiles.SaveResult(conflict = it) }
        val p = ApiProfiles.Profile(
            id = target?.id ?: ApiProfiles.newId(list),
            label = name,
            baseUrl = saveBase,
            apiKey = apiKey.trim(),
            model = model.trim(),
            thinkingEffort = thinking,
            searchConfig = searchConfig,
        )
        val next = ApiProfiles.upsert(list, p)
        sp.edit()
            .putString("customApis", ApiProfiles.toJson(next))
            .putString("activeProfileId", p.id)
            .putString("apiApplied", ApiProfiles.snapshotJson(ApiProfiles.snapshotOf(p)))
            .remove("activeProfileCleared")
            .apply()
        return ApiProfiles.SaveResult(profile = p)
    }

    /**
     * 删掉一套方案。删的是当前激活方案时：清掉激活 id / 快照、以及指向它的小活路由，
     * **当前填写的地址 / Key / 模型原样保留**（变成未保存的临时配置，用户看得见、还能再存）。
     * @return 删掉的是不是当前激活方案
     */
    fun deleteApiProfile(id: String): Boolean {
        val list = customApis
        if (list.none { it.id == id }) return false
        val wasActive = activeProfileId == id
        val e = sp.edit().putString("customApis", ApiProfiles.toJson(list.filter { it.id != id }))
        if (wasActive) e.remove("activeProfileId").remove("apiApplied").putBoolean("activeProfileCleared", true)
        if (liteProfileId == id) e.putString("liteProfileId", "")
        e.apply()
        return wasActive
    }

    /** 当前填写和激活方案 / 上次应用快照不一致（切换方案前要问「保存 / 丢弃」） */
    fun tempDirty(): Boolean {
        val snap = appliedSnapshot()
        if (snap != null) return ApiProfiles.isDirty(snap, baseUrl, apiKey, model, thinking, searchConfig)
        val p = activeProfile()
        if (p != null) return !ApiProfiles.matches(p, baseUrl, apiKey, model, thinking, searchConfig)
        return baseUrl.isNotBlank() || apiKey.isNotBlank() || model.isNotBlank()
    }

    /** 当前填写是否和某套方案完全一致（一致就不必弹「保存 / 丢弃」） */
    fun currentMatches(p: ApiProfiles.Profile): Boolean =
        ApiProfiles.matches(p, baseUrl, apiKey, model, thinking, searchConfig)

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
     * 更新清单地址。**留空 = 回落内置源**（[Update.DEFAULT_MANIFEST_URL]，由 flavor 的 UPDATE_MANIFEST_URL 注入）。
     *
     * 关键：SP 里存了空串也要当「没设置」。原先写的是 `getString(key, DEFAULT)`，但它对「存过空串」照样返回空串 ——
     * 用户在设置页把输入框清空后，SP 里留下 ""，检查更新永远卡在「还没填更新清单地址」，再也不回内置源，
     * 等于把用户的更新通道永久掐断（真实反馈）。所以只认「读到非空白值」才算用户手填，否则一律回内置源。
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
    /**
     * launcher（[ChatActivity]）已经自动把新用户送进过一次首次配置引导。
     * 只用于防循环：转屏 / 从引导返回 / 进程重建都不再自动跳；老用户本来就不会被写这个键。
     * 用 commit：要先落盘再 startActivity，否则进程在跳转瞬间被杀会重复弹。
     */
    var onboardAutoOpened: Boolean
        get() = sp.getBoolean("onboardAutoOpened", false)
        set(v) = sp.edit().putBoolean("onboardAutoOpened", v).commit().let { }
    /**
     * 本次**真正的新用户**还欠一张悬浮球新手卡：引导启动时写一次，等小球窗口真正加好、可见后才消费。
     * 不能给所有升级用户写（老用户 [Onboarding.legacyConfigured] 直接不写），否则升级即弹。
     */
    var orbGuidePending: Boolean
        get() = sp.getBoolean("orbGuidePending", false)
        set(v) = sp.edit().putBoolean("orbGuidePending", v).commit().let { }
    /** 新手卡已经真正显示过（addView 成功才算），保证一辈子只弹一次 */
    var orbGuideSeen: Boolean
        get() = sp.getBoolean("orbGuideSeen", false)
        set(v) = sp.edit().putBoolean("orbGuideSeen", v).commit().let { }
    /** 卡片 addView 成功后一次写两个标记：seen = true、pending = false（同一次落盘，进程立刻死也不会再弹） */
    fun markOrbGuideSeen() {
        sp.edit().putBoolean("orbGuideSeen", true).putBoolean("orbGuidePending", false).commit()
    }
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
    /** v1.61：内测版默认「详细」（不跳步、逐个分析选项）；公测版仍是「标准」 */
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
    /** 默认「高」（内测/公测统一）；已存在且合法的 off/low/high/max 一律保留，不迁移不覆盖 */
    var thinking: String
        get() = sp.getString("thinking", "high").takeIf { it in Prompts.THINK_EFFORTS } ?: "high"
        set(v) = sp.edit().putString("thinking", v).apply()
    var lang: String
        get() = Prompts.lang(sp.getString("lang", "auto") ?: "auto").id
        set(v) = sp.edit().putString("lang", v).apply()
    /**
     * 翻译引擎（v1.61 起四选一，见 Translate.plan）：
     * auto = DeepL → 百度 → 大模型 依次兜底（内测版默认）；deepl / baidu / ai = 只用这一个（公测版默认 ai）
     */
    var translateEngine: String
        get() = sp.getString("translateEngine", null)?.takeIf { it in Translate.ENGINES } ?: Translate.AI
        set(v) = sp.edit().putString("translateEngine", v).apply()
    /** 自己填的 DeepL Key；内测版不填就用内置的 */
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
    /**
     * v2.3.1 第 15 条「记忆辅助」：只用本地生成，不把卡面发到云端。
     * 默认 **false**（云优先）—— 这个功能的价值就在模型写的口诀 / 类比 / 故事，默认关掉等于默认用残次品。
     * 但开关必须在页面上第一屏就能看到（MemoryAidActivity 里那个勾选框），勾了整页一个请求都不发。
     */
    var memoryAidLocalOnly: Boolean
        get() = sp.getBoolean("memoryAidLocalOnly", false)
        set(v) = sp.edit().putBoolean("memoryAidLocalOnly", v).apply()
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
    /**
     * 专注守护（v2.3.2）：自动扫描时在**本机**判断这一屏是不是学习内容，连续离开太久就温和提醒一次。
     *
     * 默认关。理由跟其它功能不一样：这一项要一直看着屏幕，等于把"这台手机在学什么"交给 App 判断。
     * 用户不主动开，我们就不看 —— 一个默认开启的"专注监控"在隐私上说不清。
     * 判定全在本地完成（[FocusGuard.classify] 是纯文本规则），屏幕内容不上传，
     * 落盘的只有"这一刻在不在学习"和一个画面指纹（见 [FocusReport.FILE]）。
     */
    var focusGuardOn: Boolean
        get() = sp.getBoolean("focusGuardOn", false)
        set(v) = sp.edit().putBoolean("focusGuardOn", v).apply()

    /** 连续离开多久才提醒（分钟）。0 = 用默认 5 分钟 */
    var focusLeaveMin: Int
        get() = sp.getInt("focusLeaveMin", 5)
        set(v) = sp.edit().putInt("focusLeaveMin", v.coerceIn(1, 60)).apply()

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
    /**
     * v2.4：用户**关掉**的功能 id 集合（C 档「功能可组装」）。
     *
     * 存「关掉的」而不是「开着的」：新版本加了一个功能，老用户的配置里没有它，
     * 按「开着的」解释就会默认不出现 —— 加了新功能却没人看得到。存关掉的则天然默认开启。
     *
     * 读的时候走 [FeatureToggles.normalize] 兜底：核心功能即便存进来了也会被移出。
     */
    var disabledFeatures: Set<String>
        get() = FeatureToggles.normalize(
            Freeform.parsePhrases(sp.getString("disabledFeatures", null)).toSet()
        )
        set(v) = sp.edit().putString("disabledFeatures", Freeform.toJson(FeatureToggles.normalize(v).toList())).apply()

    /**
     * v2.4 记事本：速记浮层里显示的「最近常用标签」。
     *
     * 存成一行行的 `name\tcount\tlastUsed`，用 Freeform 落盘。
     * 为什么不用数据库的 tags 表：浮层弹出时数据库可能还没打开（第一次用），
     * 而这里只要求「大致对」的推荐顺序 —— 快了比准了重要，5 秒目标不等人。
     */
    fun noteRecentTags(): List<String> {
        val raw = sp.getString("noteTagStats", null) ?: return emptyList()
        val now = System.currentTimeMillis()
        val usages = raw.lineSequence()
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null
                else TagSuggest.Usage(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L)
            }
            .toList()
        return TagSuggest.recent(usages, now)
    }

    /** 记一次标签使用（保存笔记后调）。同一个标签在同一批里只加一次 */
    fun noteTagsUsed(names: List<String>, now: Long = System.currentTimeMillis()) {
        if (names.isEmpty()) return
        val cur = HashMap<String, TagSuggest.Usage>()
        (sp.getString("noteTagStats", null) ?: "").lineSequence().forEach { line ->
            val p = line.split('\t')
            if (p.size >= 3) {
                cur[p[0]] = TagSuggest.Usage(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L)
            }
        }
        names.distinct().forEach { n ->
            val old = cur[n]
            cur[n] = TagSuggest.Usage(n, (old?.count ?: 0) + 1, now)
        }
        val text = cur.values.sortedByDescending { it.count }.take(40)
            .joinToString("\n") { it.name + "\t" + it.count + "\t" + it.lastUsed }
        sp.edit().putString("noteTagStats", text).apply()
    }

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
     * 存着的 [orbX] 属于多宽的那块屏幕（px；0 = 老数据没存过）。
     * 转屏后不能拿新宽度去解释旧 x（竖屏右侧的 x 到了更宽的横屏会落进左半边），
     * 所以要按当时宽度判靠哪边；没有就退回当前宽度解释一次。
     */
    val orbW: Int
        get() = sp.getInt("orbW", 0)

    /** 小球贴边位置与「当时屏幕宽度」一次落盘，避免坐标和宽度不同步（转屏判边靠它） */
    fun saveOrbPos(x: Int, y: Int, screenW: Int) {
        sp.edit().putInt("orbX", x).putInt("orbY", y).putInt("orbW", screenW).apply()
    }

    /**
     * 用户是不是真的摆过位置（两个坐标都写过）。
     * 哨兵只在这里判断一次：调用方不要各自写 `>= 0` 的魔法值；没摆过时小球保持「左边垂直中间」，
     * 转屏也重新居中，摆过就一概照存的原样。
     */
    val orbPosSaved: Boolean get() = orbX >= 0 && orbY >= 0
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

    /**
     * v2.4：长按菜单的排列（有序 id 列表）。空 = 用默认顺序。
     *
     * 读的时候走 [OrbMenu.resolve] 清洗，所以这里存的是什么都不至于把菜单弄空或弄乱：
     * 未知 id 会被丢掉、常驻项会被补回固定位置。写入前不做清洗（保持用户意图原样落盘）。
     */
    var orbMenuOrder: List<String>
        get() = Freeform.parsePhrases(sp.getString("orbMenuOrder", null))
        set(v) = sp.edit().putString("orbMenuOrder", Freeform.toJson(v)).apply()

    /**
     * v2.4：用户**主动隐藏**的菜单项 id。
     *
     * 和 [orbMenuOrder] 分开存是必须的：光看顺序列表，「用户删掉了翻译」和「这个版本还没加翻译」
     * 长得一模一样。前者要保持隐藏，后者要自动出现 —— 没有这个集合就只能二选一，
     * 而无论选哪个都会有一半用户觉得是 bug（删了一保存又回来 / 新功能永远不出现）。
     */
    var orbMenuHidden: Set<String>
        get() = Freeform.parsePhrases(sp.getString("orbMenuHidden", null)).toSet()
        set(v) = sp.edit().putString("orbMenuHidden", Freeform.toJson(v.toList())).apply()

    /**
     * v2.4：菜单项的**昵称**（id → 用户起的名字）。没设过的项不在表里 = 用默认标题。
     *
     * 存成 "id=昵称" 一行一条，而不是 JSON：这一列本来就要能出现在导出的自定义文本里，
     * 也让「手动改配置文件」这件事对人友好。id 里不会出现 '='，昵称里出现 '=' 也只会被当内容。
     */
    var orbMenuNicks: Map<String, String>
        get() = Freeform.parsePhrases(sp.getString("orbMenuNicks", null))
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to OrbMenu.normNick(line.substring(at + 1))
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
        set(v) = sp.edit()
            .putString("orbMenuNicks", Freeform.toJson(v.filterValues { it.isNotBlank() }.map { (k, x) -> "$k=$x" }))
            .apply()

    /**
     * v2.7：双击小球做什么（见 [OrbMenu.TAPS]）。
     *
     * 和 [mode] 分开存是必须的：设置里的「识别模式」决定菜单/快捷开关/自动扫描用哪种模式，
     * 而这里只改**双击这一条入口** —— 想把双击设成做题、同时让自动扫描继续走通用，是合理需求。
     *
     * 读时归一化（缺省 / 非法 → 先问问题），所以手改配置文件写坏了也不会让双击变成别的行为。
     */
    var orbDoubleTap: String
        get() = OrbMenu.normTap(sp.getString("orbDoubleTap", null))
        set(v) = sp.edit().putString("orbDoubleTap", OrbMenu.normTap(v)).apply()

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
        // 小活（关键词补问 / 翻译）不走联网搜索：这里传默认关闭的 Config，避免把主接口的搜索设置带进小活
        CostPolicy.liteApi = p?.let { ApiConfig(it.baseUrl, it.apiKey, it.model, thinking, WebSearch.Config()) }
    }

    // ---- 联网搜索（v1.68 起；v1.92 改成每个接口方案各自保存的不可变配置）----
    /**
     * 搜索配置（开关 / 来源 / 深度 / 增强 / 各家 Key）跟着**当前接口方案**走。
     *
     * 缺省（一个搜索键都没存过时，v2.7.x）：公测 + 没填自填接口 + 正在用内置官方 DeepSeek → 开、来源 DeepSeek，
     * 这就是「联网缺省即用 DeepSeek」；Key 不写进配置 / SP，运行时由 [WebSearch.deepseekKey] 沿官方 host
     * 校验复用主接口 Key。其余（内测、自填接口、没内置）缺省仍是关 —— 不因为升级就替用户开搜索。
     * 缺省不落盘，跟着当前有效接口动态变（换了接口方案它自己跟着收敛回关）。
     *
     * 老版本散装的 webSearch / searchProvider / tavilyKey / searxngBase / searchDepth 只在**新键不存在**
     * 时迁移一次：显式存过的来源 / Key / 地址 / 深度原样保留，来源没存过则用推荐的 DeepSeek，
     * 增强永远迁移成关（不能替用户点头）。迁移后写回新键，之后不再读老键。
     */
    var searchConfig: WebSearch.Config
        get() {
            val raw = sp.getString("searchConfig", null)
            val stored = ApiProfiles.parseConfig(raw)
            if (stored != null) return stored
            val legacy = legacySearch()
            val cfg = resolveSearchConfig(stored, legacy)
            if (raw != null || legacy != null) sp.edit().putString("searchConfig", ApiProfiles.configJson(cfg)).apply()
            return cfg
        }
        set(v) = sp.edit().putString("searchConfig", ApiProfiles.configJson(v)).apply()

    /** 只在老键确实存在时返回；全新安装 / 已经迁移过的用户返回 null */
    private fun legacySearch(): ApiProfiles.LegacySearch? {
        val keys = listOf("webSearch", "searchProvider", "tavilyKey", "searxngBase", "searchDepth")
        if (keys.none { sp.contains(it) }) return null
        return ApiProfiles.LegacySearch(
            enabled = sp.getBoolean("webSearch", false),
            provider = sp.getString("searchProvider", "") ?: "",
            tavilyKey = sp.getString("tavilyKey", "") ?: "",
            searxngBase = sp.getString("searxngBase", "") ?: "",
            depth = sp.getString("searchDepth", "") ?: "",
        )
    }

    /**
     * 当前接口是官方 DeepSeek 且填了模型 Key —— 搜索可以复用，不必另填搜索 Key。
     * v2.2.1：改成一率和运行时同口径（用 [api] 解析后的地址 / Key，用内置预设时就是它的 Key），
     * 否则明明能搜，设置页却一直说「还差搜索 Key」。
     */
    fun searchKeyReusable(): Boolean {
        val a = api()
        return ApiProfiles.reusableSearchKey(a.baseUrl, a.apiKey)
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
     * v2.4：**复习模式**的总开关（默认 **true** = 保留）。
     *
     * 和 [wordOn] 是两件事，别混：
     *  - [wordOn]：要不要**隔一阵自动弹**一张卡到屏幕上（打扰型）
     *  - 这一个：要不要在**复习页**里排单词（主动型）。关掉后复习页只剩知识卡，
     *    单词的 FSRS 记录一条不删 —— 关开关不是删数据，重新打开能接着复习。
     *
     * 用户点名要这个：「单词复习模式可以关掉」。有些人的用法是只拿它当知识卡工具，
     * 不想被几千个词追着复习。
     */
    var wordReviewOn: Boolean
        get() = sp.getBoolean("wordReviewOn", true)
        set(v) = sp.edit().putBoolean("wordReviewOn", v).apply()
    /**
     * 多久弹一次（秒，[WORD_EVERY_SEC_MIN] ~ [WORD_EVERY_SEC_MAX]，默认 120 秒 = 2 分钟）。
     * v1.82：单位从「分钟」改「秒」，可以精确到秒。老键 wordEveryMin（分钟）只在**新键不存在**时读一次做迁移；
     * 两个键都没有的新用户才落到 [WORD_EVERY_SEC_DEF] —— 已经调过间隔的老用户不会被重置回默认。
     */
    var wordEverySec: Int
        get() = (if (sp.contains("wordEverySec")) sp.getInt("wordEverySec", WORD_EVERY_SEC_DEF)
                 else if (sp.contains("wordEveryMin")) sp.getInt("wordEveryMin", 15) * 60
                 else WORD_EVERY_SEC_DEF).coerceIn(WORD_EVERY_SEC_MIN, WORD_EVERY_SEC_MAX)
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
     * 没设过时用 [WordBank.DEFAULT_ON]（四级）；已有选择保持原样，包括显式清空。
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
        return base + miss
    }

    /**
     * 「不认识的词」有多少条。设置页每次重新渲染都要问一次，所以这里**一条 key 都不拼**：
     * [Words.key] 是 deck + "\u0000" + front，3 万多张卡逐卡拼 key 就是 3 万多条 String，
     * 全落在主线程 — 老机型上这就是一次明显的卡顿（v2.5 实测）。
     *
     * 两件事一起做：
     *  1. **一次遍历成型**（[countMistakes] 纯函数）：只把 stat 里**真正判为不认识**的牌面按词库收进
     *     HashSet（判据就是 [Words.unknown]），再对每个词库遍历它的卡，命中的那一遍直接数掉。
     *     没出现过 stat 的词库 / 牌面一律不算 —— 没见过 ≠ 不认识（用户明确：从没背过的词不算错词），
     *     所以也用不着「没有 known 集合就整本算」的兜底。
     *  2. **结果按输入指纹缓存**（此处）：词库清单 + wordStats 内容都没变就直接回答案，
     *     一次渲染里设置页问几遍只有第一次真正遍历。
     *
     * 口径与「逐卡 `Words.unknown(stats[Words.key(id, card)])` 数一遍」逐条等价 —
     * 由 PrefsMistakeCountTest 对真实 [Words.unknown] 逐卡比对钉住。
     */
    fun mistakeCount(): Int {
        val all = allDecks()
        if (all.isEmpty()) return 0
        val st = wordStats
        val fp = fingerprintOf(all)
        val cached = memo
        if (cached != null && cached.first == fp && (cached.second === st || cached.second == st)) return cached.third
        val n = countMistakes(all.map { it.first to it.third }, st, ::decodeKey)
        memo = Triple(fp, st, n)
        return n
    }

    // ---- mistakeCount 的结果缓存 ----
    // wordStats 没有变更通知（它是 SharedPreferences 里的 JSON 整体替换），失效只能靠「输入指纹」：
    // 每次算完记下 (词库指纹, wordStats, 结果)，下次进来先比指纹。
    // 比指纹是 O(词库数)=9 次身份比较 + O(1) 的 size 求和，比重新遍历 15k 卡便宜两个数量级。
    // 指纹变了（勾了别的词库 / 导入 / 删词库）就重算，口径不会因为缓存变味。
    @Volatile private var memo: Triple<Any, Map<String, Words.Stat>, Int>? = null

    /** 词库清单指纹：id + 卡数 + 解析结果实例身份（[Words.parseMemoized] 同一份正文只解析一次，实例稳定） */
    private fun fingerprintOf(all: List<Triple<String, String, List<Words.Card>>>): Any =
        all.map { Triple(it.first, it.third.size, System.identityHashCode(it.third)) }

    // ---- 错词统计的纯函数放在文件末尾的 companion object 里（Kotlin 一个类只能有一个 companion）
    // countMistakes / countMistakesInDeck / decodeKey 定义见文件尾部 companion object。
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

    /**
     * 配色方案 id（v1.55）：预设 id 或 Theme.CUSTOM。
     *
     * v1.66 起「没选过」= 护眼（Theme.DEFAULT）；**v2.5 起新装用户默认「薄荷玫瑰」**。
     * 用一个新的布尔键 atelierSeen 区分「从没存过」与「老用户存的就是护眼」：
     *  - 从没存过 → 薄荷玫瑰（v2.5 的新默认观感）；
     *  - 存过（哪怕存的是 eye）→ 尊重用户的选择，绝不悄悄换皮肤。
     * 这样升级上来的老用户外观零变化，只有真正的新装用户看到新设计。
     */
    var themeId: String
        get() {
            val raw = sp.getString("themeId", null)
            if (raw == null && !sp.contains("atelierSeen")) return "mint_rose"
            return Theme.normId(raw)
        }
        set(v) = sp.edit().putString("themeId", v).putBoolean("atelierSeen", true).apply()

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
    /**
     * v2.4：这套配色该用哪种形状语言。瑞士风格是 0dp 圆角 + 1dp 细线 + 无投影，其余一律粗野形状。
     *
     * 注意用的是**生效后**的 id（[theme] 里已经处理了跟随系统深浅色），
     * 所以系统切深色时 swiss → swiss_dark，形状仍然是瑞士的，不会变成圆角。
     */
    fun shape(): Shape = Shape.BRUT   // 默认；瑞士风格的判定在 effectiveShape() 里

    /**
     * 与 [theme] 严格配对：用同一个 effId 判形状，保证颜色和边角永远是一套。
     *
     * v2.4：用户显式设过形状（从主题包导入）就用他设的，否则按配色推。
     * 「显式」用一个独立的键记，不是拿 shape() 和 BRUT 比 —— 用户完全可能就想要 BRUT 那套，
     * 那样比出来是「没设过」，导入的主题包一重启就变回去了。
     */
    fun effectiveShape(): Shape {
        if (sp.contains("shapeSet")) {
            return Shape(
                cornerDp = sp.getFloat("shapeCorner", Shape.BRUT.cornerDp),
                strokeDp = sp.getFloat("shapeStroke", Shape.BRUT.strokeDp),
                shadowDp = sp.getFloat("shapeShadow", Shape.BRUT.shadowDp),
                focusStrokeDp = sp.getFloat("shapeFocus", Shape.BRUT.focusStrokeDp),
            )
        }
        val eff = if (followSystemDark && themeId != Theme.CUSTOM) Theme.effectiveId(themeId, systemDark(), true) else themeId
        // v2.5：薄荷玫瑰用分层圆角那套（卡片 20 / 按钮 14 / 药丸 / 弹层 24）。
        // 两套 id 不相交，顺序无关，但「新配色优先」读起来更直观。
        // v2.7.3：液态玻璃用玻璃那族（卡片 24 / 按钮 16 / 药丸 / 弹层 28 + 2dp 柔和阴影）。
        // 必须排在 atelier 前面：玻璃色板的 surfaceAlt != paper，色板层会被归进「分层一族」，
        // 但形状得拿玻璃自己的档位，而不是 Atelier 的 20/14。
        if (Theme.isGlass(eff)) return Shape.LIQUID_GLASS
        if (Theme.isAtelier(eff)) return Shape.ATELIER
        return if (Theme.isSwiss(eff)) Shape.SWISS else Shape.BRUT
    }

    /** 显式设一套形状（主题包导入用）。设过之后就固定用它，不再跟着配色走 */
    fun setShape(cornerDp: Float, strokeDp: Float, shadowDp: Float) {
        sp.edit()
            .putBoolean("shapeSet", true)
            .putFloat("shapeCorner", cornerDp.coerceIn(0f, 40f))
            .putFloat("shapeStroke", strokeDp.coerceIn(0f, 8f))
            .putFloat("shapeShadow", shadowDp.coerceIn(0f, 12f))
            // 焦点描边跟主描边走：它只是「聚焦时加粗一点」，不该独立于主描边存在
            .putFloat("shapeFocus", (strokeDp.coerceIn(0f, 8f) * 2f).coerceIn(1f, 8f))
            .apply()
    }

    /** 清除显式形状，回到「跟着配色走」（设置页「恢复默认形状」用） */
    fun clearShape() {
        sp.edit().remove("shapeSet").remove("shapeCorner").remove("shapeStroke")
            .remove("shapeShadow").remove("shapeFocus").apply()
    }

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
     * 实际用的接口：自己填了接口地址就整套用自己的（Key / 模型也以自己填的为准）；没填地址就整套用内置的（模型可以自己改）。
     * 不混用 —— 否则填了自己的地址却没填 Key，会把内置 Key 发到别人的服务器上。
     *
     * v1.92：搜索配置作为 [ApiConfig] 的一部分**按请求快照**返回，不再往 [WebSearch] 里写全局状态。
     * 同一轮请求拿到的是一份不可变配置，切方案 / 改设置不会把飞到一半的请求换掉。
     */
    fun api(): ApiConfig {
        syncCostRouting()   // v1.67：每条请求路径都先经过这里，顺手把小活路由的最新设置同步给 CostPolicy
        val search = searchConfig
        // v2.3.3：这一轮按**当前 Agent** 的工具白名单走。读一次就固定下来，
        // 请求飞到一半用户切了 Agent 也不会半路换权限（和 searchConfig 同一套快照思路）。
        val store = Personas.Store(app)
        val me = store.list().firstOrNull { it.id == store.current() }
        val scope = me?.toolScope ?: AgentScope.ALL
        // v2.4 模块门控：同样读一次固定下来 —— 请求体发哪些工具、执行收不收，
        // 两边看到的必须是同一份「用户关掉了什么」。
        val off = disabledFeatures
        // v2.4：Agent 可以各自指定模型与温度；没指定（空 / -1）就走全局，与升级前完全一致。
        // 模型优先用 Agent 自己的：同一个 App 里「编程搭子」用强模型、「随手记」用便宜模型才有意义。
        val ownModel = me?.model?.takeIf { it.isNotBlank() }
        val temp = me?.temperatureValue
        return ApiConfig(baseUrl = baseUrl, apiKey = apiKey, model = ownModel ?: model,
            thinkingEffort = thinking, searchConfig = search, agentScope = scope,
            disabledFeatures = off, temperature = temp)
    }
    /** 有没有能用的接口（没内置又没填地址 = 没有） */
    fun hasApi(): Boolean = api().baseUrl.isNotBlank()
    /** 自定义模式交给 Prompts 时统一叫 MODE_CUSTOM，带上这个模式自己的提示词 */
    fun solveOptions(): Solver.Options {
        val c = activeCustom()
        val o = if (c != null) Solver.Options(Prompts.MODE_CUSTOM, detail, novice, c.prompt, keywords) else Solver.Options(mode, detail, novice, "", keywords)
        // v1.67：省图开关跟着这一轮走（默认开）。
        // 联网搜索不在这里：它由 [api] 快照里不可变的 [ApiConfig.searchConfig] 决定（默认关），
        // [Solver.Options] 不再保留第二份开关，避免请求路径上出现两个真值来源。
        return o.copy(noShot = noFollowShot)
    }

    companion object {

        /**
         * 纯函数：[searchConfig] 的真值优先级 —— 显式存过的 > 老键迁移 > 缺省。
         * 缺省只有一种情况开搜索（v2.7.x「联网缺省即用 DeepSeek」）：没填自填接口 + 内置官方 DeepSeek
         * （官方 host 且 Key 非空，与 [ApiProfiles.reusableSearchKey] 同口径）+ 公测；内测缺省保持一直以来的关。
         * Key 永不进配置 / SP：运行时由 [WebSearch.deepseekKey] 沿官方 host 校验复用主接口 Key。
         */
        fun resolveSearchConfig(
            stored: WebSearch.Config?,
            legacy: ApiProfiles.LegacySearch?,
        ): WebSearch.Config {
            if (stored != null) return stored
            if (legacy != null) return ApiProfiles.migrateSearchConfig(legacy)
            // No built-in endpoint in this fork, so there is nothing to default the
            // search provider to: web search stays off until the user turns it on.
            return WebSearch.Config()
        }
        /**
         * 纯函数：数「不认识的词」条数，与逐卡 `Words.unknown(stats[Words.key(deck, card)])` 等价。
         * [decks] 是 (词库 id, 卡片) 序列；[decode] 把 stat 的 key 切成 (deck, front)，切不出返回 null。
         * 放在 companion 里是为了 JVM 单测能直接喂假数据、不碰 Context / SharedPreferences。
         */
        fun countMistakes(
            decks: List<Pair<String, List<Words.Card>>>
            , stats: Map<String, Words.Stat>
            , decode: (String) -> Pair<String, String>?,
        ): Int {
            if (stats.isEmpty()) return 0
            // 判为「不认识」的牌面按词库分组：只对 [Words.unknown] 为真的 stat 切一次 key，卡片一条都不用拼。
            // 没 stat / seen=0 的牌面不进集合 —— 从没背过的不算错词，[Words.unknown] 是唯一判据。
            val badIn = HashMap<String, HashSet<String>>()
            for ((k, v) in stats) {
                if (!Words.unknown(v)) continue
                val (deck, front) = decode(k) ?: continue   // 切不出词库前缀的老脏数据既不算错词，也不污染别的词库
                badIn.getOrPut(deck) { HashSet() }.add(front)
            }
            var total = 0
            for ((id, cards) in decks) total += countMistakesInDeck(cards, badIn[id])
            return total
        }

        /** 单个词库的错词数：[bad] 是 [Words.unknown] 为真的牌面集合；null / 空 = 这本一个错词都没有。 */
        fun countMistakesInDeck(cards: List<Words.Card>, bad: Set<String>?): Int {
            if (bad.isNullOrEmpty()) return 0
            var n = 0
            for (c in cards) if (c.front in bad) n++
            return n
        }

        /** stat key = deck + '\u0000' + front；切不出词库前缀的（老脏数据）返回 null */
        fun decodeKey(key: String): Pair<String, String>? {
            val cut = key.indexOf('\u0000')
            if (cut <= 0) return null
            return key.substring(0, cut) to key.substring(cut + 1)
        }

        /** v1.69 自定义项的原始键前缀（见 rawGet）：不在具名字段里的项都按 cus_<specId> 存 */
        const val CUS_PREFIX = "cus_"
        /** 「识别关键词」开关的默认值（v1.66：默认开） */
        const val KEYWORDS_DEFAULT = true
        /** 「追问省图」开关的默认值（v1.67：默认开） */
        const val NO_FOLLOW_SHOT_DEFAULT = true
        /** 「每日一句」开关的默认值（v1.67：默认开） */
        const val DAILY_LINE_DEFAULT = true
        /** 弹词间隔（秒）：5 秒 ~ 24 小时，默认 120 秒 = 2 分钟（见 [wordEverySec]） */
        const val WORD_EVERY_SEC_MIN = 5
        const val WORD_EVERY_SEC_MAX = 86_400
        const val WORD_EVERY_SEC_DEF = 120
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
