package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateTest {
    @Test fun autoOrderSkipsWhatIsNotSetUp() {
        assertEquals(listOf("deepl", "baidu", "ai"), Translate.plan("auto", true, true, true))
        assertEquals(listOf("deepl", "ai"), Translate.plan("auto", true, false, true))
        assertEquals(listOf("baidu"), Translate.plan("auto", false, true, false))
        assertEquals(emptyList<String>(), Translate.plan("auto", false, false, false))
    }

    @Test fun singleEngineNeverFallsBack() {
        assertEquals(listOf("deepl"), Translate.plan("deepl", false, true, true))
        assertEquals(listOf("ai"), Translate.plan("ai", true, true, true))
    }

    @Test fun reasonDropsCodePrefix() {
        assertEquals("DeepL 本月字符额度用完了", Translate.reason("DeepL 出错（456）：DeepL 本月字符额度用完了"))
        assertEquals("签名错误：检查 APP ID 和密钥", Translate.reason("百度翻译出错（54001）：签名错误：检查 APP ID 和密钥"))
    }

    @Test fun deeplHostAndTarget() {
        assertEquals("https://api-free.deepl.com", DeepL.host("abc:fx"))
        assertEquals("https://api.deepl.com", DeepL.host("abc"))
        assertEquals("ZH-HANS", DeepL.target("auto", "The proposal is not workable."))
        assertEquals("EN-US", DeepL.target("auto", "这个方案还不太可行"))
        assertEquals("JA", DeepL.target("ja", "hello"))
    }

    @Test fun deeplParse() {
        assertEquals("你好", DeepL.parse(200, """{"translations":[{"detected_source_language":"EN","text":"你好"}]}"""))
        try { DeepL.parse(456, "{}"); throw AssertionError() } catch (e: DeepL.DeepLException) { assertTrue(e.message!!.contains("额度")); assertEquals(false, e.retryable) }
        try { DeepL.parse(429, ""); throw AssertionError() } catch (e: DeepL.DeepLException) { assertEquals(true, e.retryable) }
        try { DeepL.parse(503, ""); throw AssertionError() } catch (e: DeepL.DeepLException) { assertEquals(true, e.retryable) }
    }

    @Test fun deeplBody() {
        val o = org.json.JSONObject(DeepL.body("hi", "ZH-HANS"))   // 键的顺序不重要
        assertEquals("hi", o.getJSONArray("text").getString(0))
        assertEquals("ZH-HANS", o.getString("target_lang"))
    }

    // ---------------- 首选引擎缓存查找 ----------------

    private fun item(v: String) = Cache.Item("k", v, 1000, 1000)
    /** 假缓存：只有列出的引擎存过东西 */
    private fun probe(vararg hit: String): (String) -> Cache.Item? {
        val m = hit.associateWith { item("来自$it") }
        return { m[it] }
    }

    /** 命中首选范围里的引擎就复用它（按 plan 顺序取第一个命中的）；一个都没命中 → 返回 null，该真发请求了 */
    @Test fun cachesOnPlannedEngine() {
        val p = Translate.pickCached(listOf("deepl", "baidu"), probe = probe("deepl"))!!
        assertEquals("deepl", p.engine); assertEquals("来自deepl", p.item.v)
        assertEquals("baidu", Translate.pickCached(listOf("deepl", "baidu"), probe = probe("baidu"))!!.engine)
        assertNull(Translate.pickCached(listOf("deepl", "baidu"), probe = probe()))
    }

    /**
     * v2.6.3 auto 取消 AI 优先：配好 DeepL 时它必须是第一个被问的引擎，
     * 所以缓存查找直接按 plan 走 —— AI 档存过的旧富结果不能抢在 DeepL 前面命中（否则用户看不到 DeepL 译文）。
     */
    @Test fun autoCachePrefersMachineTranslationOverAiRichResult() {
        val auto = Translate.plan("auto", true, true, true)
        assertEquals(listOf("deepl", "baidu", "ai"), auto)
        val p = Translate.pickCached(auto, probe = probe("deepl", "ai"))!!
        assertEquals("deepl", p.engine)
        // DeepL 没缓存时仍然按顺序取下一个，AI 的富结果此时才轮到
        assertEquals("ai", Translate.pickCached(auto, probe = probe("ai"))!!.engine)
        // 显式钉 AI 时不许借 DeepL 的缓存
        assertEquals("ai", Translate.pickCached(Translate.plan("ai", true, true, true), probe = probe("deepl", "ai"))!!.engine)
    }


    /** 显式「重翻」：跳过缓存，一定发请求 —— 旧译文不能把用户的覆盖操作短路掉 */
    @Test fun forceRetranslateSkipsCache() {
        assertNull(Translate.pickCached(listOf("deepl", "baidu"), force = true, probe = probe("deepl", "baidu")))
        assertNull(Translate.pickCached(emptyList(), force = true, probe = probe("deepl")))
        // 不 force 时同一份数据是命中的（确认上面 null 是 force 造成的，不是数据问题）
        assertNotNull(Translate.pickCached(listOf("deepl"), probe = probe("deepl")))
    }
}
