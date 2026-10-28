package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Update.safeUrl]：中文文件名 URL 的编码（v1.85.2 真机「下载起不来」的修复） */
class UpdateSafeUrlTest {
    @Test
    fun encodesChinesePathKeepsAsciiUntouched() {
        val raw = "https://updates.example.com/Ling/main/Ling-1.85.2-公测版.apk"
        val safe = Update.safeUrl(raw)
        assertTrue("中文必须被百分号编码", "公测版" !in safe)
        assertTrue(safe.endsWith("Ling-1.85.2-%E5%85%AC%E6%B5%8B%E7%89%88.apk"))
        assertEquals("纯 ASCII 的 url 逐字不变（老通道零影响）", raw, safe.substring(0, raw.indexOf("update/")) + "update/" +
            raw.substringAfter("update/").let { java.net.URLDecoder.decode(it, "UTF-8") })
    }

    @Test
    fun asciiUrlIsReturnedVerbatim() {
        val raw = "https://example.com/update/Ling-1.85.2.apk?x=1"
        assertEquals(raw, Update.safeUrl(raw))
    }

    @Test
    fun slashNotEncodedSoPathShapeSurvives() {
        val safe = Update.safeUrl("https://updates.example.com/a/b/update/Ling-1.85.2-内测版.apk")
        assertTrue("路径形状（斜杠）不被编码", "/a/b/update/" in safe)
        assertTrue("内测版" !in safe)
    }
}
