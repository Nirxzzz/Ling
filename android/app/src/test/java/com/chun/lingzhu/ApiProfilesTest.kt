package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * 接口方案的纯 JVM 行为：字段完整性 / 迁移 / 真实 id / 同名冲突 / 变脏判断 / 推荐搜索配置 / 联网缺省真值优先级。
 * 这里只钉用户看得见的规则，不复制实现细节。
 */
class ApiProfilesTest {

    private fun profile(
        id: String = "p1",
        label: String = "主力",
        base: String = "https://api.deepseek.com",
        key: String = "sk-1",
        model: String = "deepseek-vl",
        thinking: String = "high",
        search: WebSearch.Config = WebSearch.Config(),
    ) = ApiProfiles.Profile(id, label, base, key, model, thinking, search)

    @Test fun parseAndRoundtripKeepsEveryField() {
        val search = WebSearch.Config(
            enabled = true,
            provider = WebSearch.TAVILY,
            deepseekKey = "",
            tavilyKey = "tvly-x",
            enhance = true,
            searxngBase = "",
            depth = "advanced",
        )
        val list = listOf(
            profile(id = "a1", key = "sk-1", search = search),
            profile(id = "a2", label = "备用", base = "https://b.com/v1", key = "", model = "qwen-vl",
                thinking = "off", search = WebSearch.Config()),
        )
        assertEquals(list, ApiProfiles.parse(ApiProfiles.toJson(list)))
    }

    /** 老版本的方案 JSON 没有思考强度 / 搜索配置：读出来必须是 high + 默认关，而不是崩掉或联网 */
    @Test fun legacyProfileWithoutNewFieldsFallsBackHighAndSearchOff() {
        val old = """[{"id":"a1","label":"主力","baseUrl":"https://a/v1","apiKey":"sk","model":"m"}]"""
        val p = ApiProfiles.parse(old).single()
        assertEquals("high", p.thinkingEffort)
        assertFalse(p.searchConfig.enabled)
        assertEquals(WebSearch.DEEPSEEK, p.searchConfig.provider)
    }

    @Test fun missingOrInvalidThinkingFallsBackHigh() {
        assertEquals("high", ApiProfiles.normThinking(null))
        assertEquals("high", ApiProfiles.normThinking(""))
        assertEquals("high", ApiProfiles.normThinking("ultra"))
        assertEquals("low", ApiProfiles.normThinking("low"))
        val parsed = ApiProfiles.parse("""[{"id":"a1","label":"x","baseUrl":"https://a/v1","thinkingEffort":"ultra"}]""").single()
        assertEquals("high", parsed.thinkingEffort)
    }

    @Test fun parseBadJsonReturnsEmpty() {
        assertTrue(ApiProfiles.parse(null).isEmpty())
        assertTrue(ApiProfiles.parse("").isEmpty())
        assertTrue(ApiProfiles.parse("不是json").isEmpty())
        assertTrue(ApiProfiles.parse("""[{"id":"","baseUrl":"https://x"}]""").isEmpty())
        assertTrue(ApiProfiles.parse("""[{"id":"a","baseUrl":""}]""").isEmpty())
    }

    /** 新方案 id 用 UUID，且不会撞到任何已有 id（老数据的 a1/a2 原样保留） */
    @Test fun newIdsAreUniqueUuids() {
        val old = listOf(profile(id = "a1"), profile(id = "a2"))
        val id = ApiProfiles.newId(old)
        assertEquals(id, UUID.fromString(id).toString())
        assertFalse(old.any { it.id == id })
    }

    @Test fun newNameSkipsExistingNames() {
        assertEquals("接口方案 1", ApiProfiles.newName(emptyList()))
        assertEquals("接口方案 2", ApiProfiles.newName(listOf(profile(label = "接口方案 1"))))
        assertEquals("接口方案 3",
            ApiProfiles.newName(listOf(profile(id = "a1", label = "接口方案 1"), profile(id = "a2", label = "接口方案 2"))))
    }

    /** 同名但不同 id 才算冲突；更新自己不算，且空白名字不参与冲突 */
    @Test fun sameNameConflictIgnoresSelfAndBlankNames() {
        val p = profile(id = "p1", label = "主力")
        assertEquals("p1", ApiProfiles.conflict(listOf(p), "主力")?.id)
        assertNull(ApiProfiles.conflict(listOf(p), "主力", selfId = "p1"))
        assertNull(ApiProfiles.conflict(listOf(p), "  "))
        assertNull(ApiProfiles.conflict(listOf(p), "备用"))
    }

    /** 保存 / 更新走 upsert：更新必须保留原 id，不能先生成一个新 id 再拷字段 */
    @Test fun upsertUpdatesInPlaceAndKeepsRealId() {
        val a = profile(id = "p1", label = "主力", key = "old")
        val b = profile(id = "p2", label = "备用", base = "https://b/v1")
        val changed = a.copy(apiKey = "new", searchConfig = WebSearch.Config(enabled = true))
        val list = ApiProfiles.upsert(listOf(a, b), changed)
        assertEquals(listOf(changed, b), list)
        assertEquals("p1", list.first { it.label == "主力" }.id)
        assertEquals("p1", ApiProfiles.upsert(list, a.copy(label = "改名")).first { it.id == "p1" }.id)
    }

    /**
     * 空地址不能存成方案：存进去 parse 会丢掉，重读就变成「方案没了、激活 id 悬空」。
     * 合法地址照旧 trim 后存、原样读回。
     */
    @Test fun blankBaseUrlCannotBecomeAStoredProfile() {
        assertNull(ApiProfiles.saveableBaseUrl(null))
        assertNull(ApiProfiles.saveableBaseUrl(""))
        assertNull(ApiProfiles.saveableBaseUrl("   "))
        assertEquals("https://a/v1", ApiProfiles.saveableBaseUrl("  https://a/v1  "))

        // 被拒的保存不产出 profile（ok=false、有错误文案），所以不会写出读不回来的条目
        val rejected = ApiProfiles.SaveResult(error = ApiProfiles.ERR_BASE_BLANK)
        assertFalse(rejected.ok)
        assertNull(rejected.profile)
        assertNull(rejected.conflict)

        // 合法地址的行为不变：存什么读回什么
        val saved = profile(base = ApiProfiles.saveableBaseUrl("  https://api.deepseek.com  ")!!)
        assertEquals(listOf(saved), ApiProfiles.parse(ApiProfiles.toJson(listOf(saved))))
    }

    @Test fun applyingSnapshotDetectsEveryKindOfEdit() {
        val p = profile(search = WebSearch.Config(enabled = true, provider = WebSearch.DEEPSEEK))
        val snap = ApiProfiles.snapshotOf(p)
        fun dirty(base: String = p.baseUrl, key: String = p.apiKey, model: String = p.model,
                  thinking: String = p.thinkingEffort, search: WebSearch.Config = p.searchConfig) =
            ApiProfiles.isDirty(snap, base, key, model, thinking, search)

        assertFalse(dirty())
        assertTrue(dirty(key = "sk-2"))
        assertTrue(dirty(base = "https://other/v1"))
        assertTrue(dirty(model = "m2"))
        assertTrue(dirty(thinking = "off"))
        assertTrue(dirty(search = p.searchConfig.copy(depth = "advanced")))
        assertTrue(ApiProfiles.matches(p, p.baseUrl, p.apiKey, p.model, p.thinkingEffort, p.searchConfig))
        assertFalse(ApiProfiles.matches(p, p.baseUrl, "sk-2", p.model, p.thinkingEffort, p.searchConfig))
    }

    /** 迁移只搬用户显式存过的东西：开关 / 来源 / Key / 地址 / 深度保留，enhance 永远不擅自打开 */
    @Test fun legacySearchMigrationPreservesExplicitChoices() {
        val legacy = ApiProfiles.LegacySearch(
            enabled = true,
            provider = WebSearch.TAVILY,
            tavilyKey = "tvly-old",
            searxngBase = "",
            depth = "advanced",
        )
        val c = ApiProfiles.migrateSearchConfig(legacy)
        assertTrue(c.enabled)
        assertEquals(WebSearch.TAVILY, c.provider)
        assertEquals("tvly-old", c.tavilyKey)
        assertEquals("advanced", c.depth)
        assertFalse(c.enhance)
        assertEquals("", c.deepseekKey)

        // 来源没显式存过、但填了 Tavily Key：按老版本的实际用法迁到 Tavily，不把 Key 丢掉
        val keyOnly = ApiProfiles.migrateSearchConfig(ApiProfiles.LegacySearch(enabled = true, tavilyKey = "tvly-k"))
        assertEquals(WebSearch.TAVILY, keyOnly.provider)
        assertTrue(keyOnly.enabled)
    }

    /** 迁移函数本身对「没存过」永远返回关；开源副本没有内置接口，[Prefs.resolveSearchConfig] 的缺省也是关。 */
    @Test fun newUsersDefaultToOffAndDeepSeek() {
        val c = ApiProfiles.migrateSearchConfig(null)
        assertFalse(c.enabled)
        assertEquals(WebSearch.DEEPSEEK, c.provider)
        assertFalse(c.enhance)
        assertFalse(WebSearch.Config().enabled)
    }

    /** 推荐一键：开 + DeepSeek + 不打开增强，也不把已有 Key 换成任何内置密钥 */
    @Test fun recommendedSearchConfigTurnsOnDeepSeekWithoutSecretsOrEnhance() {
        val base = WebSearch.Config(enabled = true, provider = WebSearch.TAVILY, tavilyKey = "tv", enhance = true)
        val c = ApiProfiles.recommendedSearchConfig(base)
        assertTrue(c.enabled)
        assertEquals(WebSearch.DEEPSEEK, c.provider)
        assertFalse(c.enhance)
        assertEquals("", c.deepseekKey)
        assertEquals("tv", c.tavilyKey)
    }

    /** 复用模型 Key 只认官方 HTTPS 主机；任何「像 deepseek」的地址 / 模型名都不算 */
    @Test fun onlyExactOfficialDeepSeekBaseCanReuseModelKey() {
        assertTrue(ApiProfiles.isOfficialDeepSeekBase("https://api.deepseek.com"))
        assertTrue(ApiProfiles.isOfficialDeepSeekBase("https://api.deepseek.com/v1"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("http://api.deepseek.com"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("https://api.deepseek.com.evil.com"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("https://evil.com/api.deepseek.com"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("https://api.deepseek.com:8443"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("https://user:pass@api.deepseek.com"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase("https://api.deepseek.com.cn"))
        assertFalse(ApiProfiles.isOfficialDeepSeekBase(""))
    }

    // —— 联网缺省的真值优先级（开源副本没有内置接口）：显式存过的 > 老键迁移 > 缺省关 ——

    /** 开源副本没有内置接口：缺省一律关，只有显式存过的配置才会被采用。 */
    @Test fun searchDefaultsOffWithoutBuiltIn() {
        assertFalse(Prefs.resolveSearchConfig(null, null).enabled)
        assertFalse(Prefs.resolveSearchConfig(null, ApiProfiles.LegacySearch(enabled = false)).enabled)
        val storedOff = WebSearch.Config()
        assertSame(storedOff, Prefs.resolveSearchConfig(storedOff, null))
    }

    /** 显式选择永远压过缺省：存过的配置（含明确关掉）原样用；老键明确关也保持关，不被缺省翻成开 */
    @Test fun explicitSearchChoicesWinOverTheDefault() {
        val storedOff = WebSearch.Config(enabled = false, provider = WebSearch.TAVILY, tavilyKey = "tv")
        assertSame("显式关掉的配置原样返回", storedOff, Prefs.resolveSearchConfig(storedOff, null))
        assertFalse(Prefs.resolveSearchConfig(null, ApiProfiles.LegacySearch(enabled = false)).enabled)
        // 存过的「开」也照原样用，缺省不参与改写
        val storedOn = WebSearch.Config(enabled = true, provider = WebSearch.SEARXNG, searxngBase = "https://searx.example")
        assertSame("显式开着的配置也原样返回", storedOn, Prefs.resolveSearchConfig(storedOn, ApiProfiles.LegacySearch(enabled = false)))
    }

    /** host-key 隔离边界：只有官方 HTTPS host + 非空 Key 才复用模型 Key，自定义 / 仿冒地址一律拿不到 */
    @Test fun searchKeyReuseOnlyForOfficialHostWithKey() {
        val key = "sk-model"
        assertTrue(ApiProfiles.reusableSearchKey(WebSearch.DEEPSEEK_BASE, key))
        assertTrue(ApiProfiles.reusableSearchKey(WebSearch.DEEPSEEK_BASE + "/v1", key))
        assertFalse("自定义地址拿不到模型 Key", ApiProfiles.reusableSearchKey("https://proxy.example/v1", key))
        assertFalse("仿冒 host 不可复用", ApiProfiles.reusableSearchKey("https://api.deepseek.com.evil.com", key))
        assertFalse("Key 空就没有可复用的", ApiProfiles.reusableSearchKey(WebSearch.DEEPSEEK_BASE, ""))
        assertFalse(ApiProfiles.reusableSearchKey(WebSearch.DEEPSEEK_BASE, "   "))
    }
}
