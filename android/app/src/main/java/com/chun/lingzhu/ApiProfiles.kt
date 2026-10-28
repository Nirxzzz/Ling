package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 接口方案（纯 JVM，可单测）：每一套方案 = 地址 / Key / 模型 + 思考强度 + 联网搜索配置。
 *
 * 这轮把「方案」和「当前临时填写」彻底分开：
 *  - [Profile] 是存下来的整套配置，id 用 UUID（老数据里的 a1/a2 原样保留）；
 *  - [Snapshot] 是上一次「应用方案」时落到激活字段的那一份快照，用来判断当前填写有没有被改脏；
 *  - 开关方案 = [Prefs.applyApiProfile] 一次 editor 原子写入，不会出现「地址换了 Key 没换」的中间态。
 *
 * 搜索配置的 JSON 形状也在这里（[configJson]/[configFromJson]）：它是方案的一部分，跟方案一起存、
 * 一起迁移、一起切换；本文件不打印任何 Key。
 */
object ApiProfiles {

    /** 思考强度默认 high；缺字段 / 非法值一律回落 high（不猜测用户意图） */
    const val THINKING_DEFAULT = "high"

    /**
     * 一套接口方案。
     *
     * 末尾两项是这轮新增的：思考强度与联网搜索配置都跟着方案走，切方案时一起换。
     * 旧数据没有这两项时，解析成 high + 搜索默认关（隐私优先）。
     */
    data class Profile(
        val id: String,
        val label: String,
        val baseUrl: String,
        val apiKey: String = "",
        val model: String = "",
        val thinkingEffort: String = THINKING_DEFAULT,
        val searchConfig: WebSearch.Config = WebSearch.Config(),
    )

    /** 上一次应用方案时的完整快照（含搜索配置）；只用于判断临时填写是否变脏 */
    data class Snapshot(
        val profileId: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val thinkingEffort: String,
        val searchConfig: WebSearch.Config,
    )

    /** 保存被拒的原因：接口地址空白 —— 存进去也会被 [parse] 丢掉，只留下一个悬空的激活 id */
    const val ERR_BASE_BLANK = "接口地址是空的：先填上接口地址再存为方案（留空 = 用内置接口）"

    /**
     * 保存结果：写入成功（profile 非空）/ 撞上同名方案需要用户确认（conflict 非空）/
     * 校验没过、一个字节都没写（error 非空，见 [ERR_BASE_BLANK]）。
     */
    data class SaveResult(val profile: Profile? = null, val conflict: Profile? = null, val error: String? = null) {
        val ok: Boolean get() = profile != null
    }

    /** 老版本散在 SharedPreferences 里的搜索设置；只在迁移时读一次 */
    data class LegacySearch(
        val enabled: Boolean = false,
        val provider: String = "",
        val tavilyKey: String = "",
        val searxngBase: String = "",
        val depth: String = "",
    )

    // ---------------- 存取 ----------------

    fun parse(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Profile(
                    id = id,
                    label = o.optString("label").ifBlank { "方案" },
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    model = o.optString("model"),
                    thinkingEffort = normThinking(o.optString("thinkingEffort")),
                    searchConfig = o.optJSONObject("searchConfig")?.let { configFromJson(it) } ?: WebSearch.Config(),
                )
            }.filter { it.baseUrl.isNotBlank() }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Profile>): String = JSONArray().apply {
        for (p in list) put(JSONObject()
            .put("id", p.id)
            .put("label", p.label)
            .put("baseUrl", p.baseUrl)
            .put("apiKey", p.apiKey)
            .put("model", p.model)
            .put("thinkingEffort", normThinking(p.thinkingEffort))
            .put("searchConfig", configJsonObject(p.searchConfig)))
    }.toString()

    /** 新方案 id：UUID。老数据的 a1/a2 不动，只保证新 id 不和现有重复 */
    fun newId(list: List<Profile>): String {
        var id = UUID.randomUUID().toString()
        while (list.any { it.id == id }) id = UUID.randomUUID().toString()
        return id
    }

    /** 新方案默认名：接口方案 1、接口方案 2…（不覆盖任何已有名字） */
    fun newName(list: List<Profile>): String {
        var n = 1
        while (list.any { it.label == "接口方案 $n" }) n++
        return "接口方案 $n"
    }

    /**
     * 同名冲突：名字相同但 id 不同的那套方案。
     * [selfId] 是「正在更新的方案」—— 更新自己不算冲突。调用方拿到非空时必须先问用户，不能静默覆盖。
     */
    fun conflict(list: List<Profile>, label: String, selfId: String = ""): Profile? {
        val name = label.trim()
        if (name.isEmpty()) return null
        return list.firstOrNull { it.id != selfId && it.label.trim() == name }
    }

    /** 按 id 更新（保留真实 id），没有就追加。保存 / 更新走同一条路，避免「更新完换了个新 id」 */
    fun upsert(list: List<Profile>, p: Profile): List<Profile> {
        val i = list.indexOfFirst { it.id == p.id }
        return if (i >= 0) list.toMutableList().also { it[i] = p } else list + p
    }

    /**
     * 能存进方案的接口地址（trim 后）。null = 存不了。
     *
     * 空白地址不能存：[parse] 会把空地址的条目丢掉，真写下去就成了「保存成功 → 重读时方案消失 →
     * 激活 id 指向不存在的方案」。所以保存前先过这一关，合法地址照旧 trim 后存。
     */
    fun saveableBaseUrl(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------- 思考强度 / 搜索配置 ----------------

    fun normThinking(v: String?): String = v?.takeIf { it in Prompts.THINK_EFFORTS } ?: THINKING_DEFAULT

    fun normProvider(v: String?): String = when {
        v == null -> WebSearch.DEEPSEEK
        v == WebSearch.DEEPSEEK || v == WebSearch.TAVILY || v == WebSearch.SEARXNG -> v
        else -> WebSearch.DEEPSEEK
    }

    fun normDepth(v: String?): String = v?.takeIf { it in WebSearch.TAVILY_DEPTHS } ?: WebSearch.TAVILY_DEPTH_DEFAULT

    /** 搜索配置 → JSON（键名稳定，用于方案存档 / 迁移 / 快照）；不写日志、不打印 */
    fun configJsonObject(c: WebSearch.Config): JSONObject {
        val provider = normProvider(c.provider)
        return JSONObject()
            .put("enabled", c.enabled)
            .put("provider", provider)
            .put("deepseekKey", c.deepseekKey)
            .put("tavilyKey", c.tavilyKey)
            .put("enhance", c.enhance && provider == WebSearch.TAVILY)
            .put("searxngBase", c.searxngBase)
            .put("depth", normDepth(c.depth))
    }

    fun configJson(c: WebSearch.Config): String = configJsonObject(c).toString()

    fun configFromJson(o: JSONObject?): WebSearch.Config {
        if (o == null) return WebSearch.Config()
        val provider = normProvider(o.optString("provider", ""))
        return WebSearch.Config(
            enabled = o.optBoolean("enabled", false),
            provider = provider,
            deepseekKey = o.optString("deepseekKey", ""),
            tavilyKey = o.optString("tavilyKey", ""),
            enhance = provider == WebSearch.TAVILY && o.optBoolean("enhance", false),
            searxngBase = o.optString("searxngBase", ""),
            depth = normDepth(o.optString("depth", "")),
        )
    }

    /** 解析存着的搜索配置；坏 JSON 返回 null（交给迁移兜底），不抛 */
    fun parseConfig(json: String?): WebSearch.Config? = try {
        if (json.isNullOrBlank()) null else configFromJson(JSONObject(json))
    } catch (_: Exception) { null }

    /**
     * 老版本的散装搜索设置 → 新配置。
     * 原则：用户显式存过的选项照原样保留（开关 / 来源 / Tavily Key / SearXNG 地址 / 深度）；
     * 来源没显式存过、但填了某家的 Key / 地址，就按那家迁移（老版本默认 Tavily）；否则用推荐的 DeepSeek。
     * enhance 永远不迁移成开（不能替用户点头）。
     */
    fun migrateSearchConfig(legacy: LegacySearch?): WebSearch.Config {
        if (legacy == null) return WebSearch.Config()
        // 来源显式存过就用存的；没存过但填了某家的 Key / 地址，就按那家迁移（老版本默认 Tavily）；
        // 都没有才落到推荐的 DeepSeek。enhance 永远关。
        val provider = when {
            legacy.provider == WebSearch.DEEPSEEK || legacy.provider == WebSearch.TAVILY || legacy.provider == WebSearch.SEARXNG -> legacy.provider
            legacy.tavilyKey.isNotBlank() -> WebSearch.TAVILY
            legacy.searxngBase.isNotBlank() -> WebSearch.SEARXNG
            else -> WebSearch.DEEPSEEK
        }
        return WebSearch.Config(
            enabled = legacy.enabled,
            provider = provider,
            deepseekKey = "",
            tavilyKey = legacy.tavilyKey,
            enhance = false,
            searxngBase = legacy.searxngBase,
            depth = normDepth(legacy.depth),
        )
    }

    /** 一键推荐：开启 DeepSeek 官方搜索；不注入任何密钥，也不替用户打开 Tavily 增强 */
    fun recommendedSearchConfig(base: WebSearch.Config = WebSearch.Config()): WebSearch.Config =
        base.copy(enabled = true, provider = WebSearch.DEEPSEEK, enhance = false)

    // ---------------- 应用快照 / 变脏判断 ----------------

    fun snapshotOf(p: Profile): Snapshot = Snapshot(
        profileId = p.id,
        baseUrl = p.baseUrl.trim(),
        apiKey = p.apiKey.trim(),
        model = p.model.trim(),
        thinkingEffort = normThinking(p.thinkingEffort),
        searchConfig = p.searchConfig,
    )

    fun snapshotJson(s: Snapshot): String = JSONObject()
        .put("profileId", s.profileId)
        .put("baseUrl", s.baseUrl)
        .put("apiKey", s.apiKey)
        .put("model", s.model)
        .put("thinkingEffort", normThinking(s.thinkingEffort))
        .put("searchConfig", configJsonObject(s.searchConfig))
        .toString()

    fun parseSnapshot(json: String?): Snapshot? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            Snapshot(
                profileId = o.optString("profileId", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", ""),
                thinkingEffort = normThinking(o.optString("thinkingEffort", "")),
                searchConfig = o.optJSONObject("searchConfig")?.let { configFromJson(it) } ?: WebSearch.Config(),
            )
        } catch (_: Exception) { null }
    }

    /** 当前临时填写是否和这份快照不同（地址 / Key / 模型 / 思考强度 / 搜索配置任一项变了都算脏） */
    fun isDirty(s: Snapshot, baseUrl: String, apiKey: String, model: String, thinking: String, search: WebSearch.Config): Boolean =
        s.baseUrl != baseUrl.trim() || s.apiKey != apiKey.trim() || s.model != model.trim() ||
            normThinking(s.thinkingEffort) != normThinking(thinking) || s.searchConfig != search

    /** 当前临时填写是否和某套方案完全一致（切换前用：一致就不用问「要不要保存」） */
    fun matches(p: Profile, baseUrl: String, apiKey: String, model: String, thinking: String, search: WebSearch.Config): Boolean =
        !isDirty(snapshotOf(p), baseUrl, apiKey, model, thinking, search)

    /**
     * 是否官方 DeepSeek 接口（搜索复用模型 Key 的唯一条件）。
     * 规则与搜索结果保持一致（[WebSearch.isOfficialDeepSeek]）：只认 HTTPS、精确主机 api.deepseek.com、无 userinfo。
     * 绝不用「网址里包含 deepseek」或「模型名叫 deepseek」来猜 —— 那会把 Key 发给别人的服务器。
     */
    fun isOfficialDeepSeekBase(raw: String): Boolean = WebSearch.isOfficialDeepSeek(raw)

    /**
     * 搜索能不能复用模型 Key（v2.2.1）。**纯函数**，口径必须和运行时 [WebSearch.deepseekKey] 完全一致：
     * 判断的是「实际请求会用的地址和 Key」——内测版留空 baseUrl 时那就是内置 Preset 的地址和 Key，
     * 而不是用户输入框里那个空串。以前设置页读用户填的 baseUrl，于是内测版永远显示「还差搜索 Key」，
     * 但真正识别时搜索是通的，两句文案互相打脸。
     */
    fun reusableSearchKey(baseUrl: String, apiKey: String): Boolean =
        isOfficialDeepSeekBase(baseUrl) && apiKey.isNotBlank()
}
