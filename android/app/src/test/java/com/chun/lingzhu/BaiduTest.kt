package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BaiduTest {
    @Test fun bearerBodyHasNoSaltOrSign() {
        val o = JSONObject(BaiduTranslate("123", "key-x", "sec", "nmt").body("你好", "en"))
        assertEquals("你好", o.getString("q")); assertEquals("auto", o.getString("from")); assertEquals("en", o.getString("to"))
        assertEquals("nmt", o.getString("model_type")); assertEquals("123", o.getString("appid"))
        assertFalse(o.has("salt")); assertFalse(o.has("sign"))
    }

    @Test fun signBodyUsesNumericSaltAndMd5() {
        val o = JSONObject(BaiduTranslate("2015063000000001", "", "12345678").body("apple", "zh", salt = 1435660288L))
        assertTrue(o.get("salt") is Number)
        // 百度文档里的官方示例
        assertEquals("f89f9594663708c1605f3d736d01d2d4", o.getString("sign"))
    }

    @Test fun targetLanguage() {
        assertEquals("en", BaiduTranslate.target("auto", "这个方案不太行。"))
        assertEquals("zh", BaiduTranslate.target("auto", "This plan won't work."))
        assertEquals("zh", BaiduTranslate.target("auto", "12345"))
        assertEquals("jp", BaiduTranslate.target("ja", "hello"))
        assertEquals("kor", BaiduTranslate.target("ko", "hello"))
        assertEquals("fra", BaiduTranslate.target("fr", "hello"))
        for (l in Prompts.LANGS) assertTrue(l.id, BaiduTranslate.target(l.id, "x").isNotEmpty())
    }

    @Test fun parsesJoinedResultAndErrors() {
        assertEquals("Hello\nWorld", BaiduTranslate.parse("""{"from":"zh","to":"en","trans_result":[{"src":"你好","dst":"Hello"},{"src":"世界","dst":"World"}]}"""))
        try { BaiduTranslate.parse("""{"error_code":"54004","error_msg":"Please recharge"}"""); fail() }
        catch (e: BaiduTranslate.BaiduException) { assertEquals("54004", e.code); assertTrue(e.message!!.contains("余额不足")); assertFalse(e.retryable) }
        try { BaiduTranslate.parse("""{"error_code":99999,"error_msg":"weird"}"""); fail() }
        catch (e: BaiduTranslate.BaiduException) { assertTrue(e.message!!.contains("weird")) }
    }

    @Test fun postsToServerAndRetriesRateLimit() {
        val bodies = mutableListOf<String>()
        val server = MiniHttp { _, body ->
            bodies.add(body)
            if (bodies.size == 1) 200 to """{"error_code":"54003","error_msg":"Invalid Access Limit"}"""
            else 200 to """{"trans_result":[{"dst":"This plan won't work."}]}"""
        }
        try {
            val r = BaiduTranslate("", "key-x", "", endpoint = "http://127.0.0.1:${server.port}/ait").translate("这个方案不太行。", "auto")
            assertEquals("This plan won't work.", r)
            assertEquals(2, bodies.size)
            assertEquals("en", JSONObject(bodies[0]).getString("to"))
        } finally { server.stop() }
    }

    @Test fun missingCredentialsExplained() {
        try { BaiduTranslate("123", "", "").translate("hi", "auto"); fail() }
        catch (e: ApiException) { assertTrue(e.message!!.contains("翻译引擎")) }
    }
}
