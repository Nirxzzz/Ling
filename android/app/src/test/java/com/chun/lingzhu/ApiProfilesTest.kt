package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.65 接口方案：存取 / 起名 / 切换（纯 JVM 部分；Prefs 的 saveApiProfile 走仪器或手测） */
class ApiProfilesTest {

    @Test fun parseAndRoundtrip() {
        val list = listOf(
            ApiProfiles.Profile("a1", "主力", "https://a.com/v1", "sk-1", "glm-4v"),
            ApiProfiles.Profile("a2", "备用", "https://b.com/v1", "", "qwen-vl"),
        )
        val back = ApiProfiles.parse(ApiProfiles.toJson(list))
        assertEquals(list, back)
    }

    @Test fun parseBadJsonReturnsEmpty() {
        assertTrue(ApiProfiles.parse(null).isEmpty())
        assertTrue(ApiProfiles.parse("").isEmpty())
        assertTrue(ApiProfiles.parse("不是json").isEmpty())
        assertTrue(ApiProfiles.parse("[{\"id\":\"\",\"baseUrl\":\"https://x\"}]").isEmpty())   // 没 id 丢
        assertTrue(ApiProfiles.parse("[{\"id\":\"a\",\"baseUrl\":\"\"}]").isEmpty())          // 没地址丢
    }

    @Test fun newIdSkipsExisting() {
        val list = listOf(ApiProfiles.Profile("a1", "x", "https://x"), ApiProfiles.Profile("a3", "y", "https://y"))
        assertEquals("a2", ApiProfiles.newId(list))
        assertEquals("a1", ApiProfiles.newId(emptyList()))
    }

    @Test fun newNameSequence() {
        assertEquals("接口方案 1", ApiProfiles.newName(emptyList()))
        val one = listOf(ApiProfiles.Profile("a1", "接口方案 1", "https://x"))
        assertEquals("接口方案 2", ApiProfiles.newName(one))
        val two = one + ApiProfiles.Profile("a2", "接口方案 2", "https://y")
        assertEquals("接口方案 3", ApiProfiles.newName(two))
    }

    @Test fun applyWritesAllThreeFields() {
        var base = ""; var key = ""; var model = ""
        ApiProfiles.apply(ApiProfiles.Profile("a1", "P", "https://p/v1", "sk-p", "m-p"),
            { base = it }, { key = it }, { model = it })
        assertEquals("https://p/v1", base); assertEquals("sk-p", key); assertEquals("m-p", model)
    }
}
