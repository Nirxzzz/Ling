package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Update.safeUrl]：中文文件名 URL 的编码（v1.85.2 真机「下载起不来」的修复） */
class UpdateSafeUrlTest {
    @Test
    fun encodesChinesePathKeepsAsciiUntouched() {
        val raw = "https://updates.example.com/Ling/main/update/Ling-1.85.2-公测版.apk"
        val safe = Update.safeUrl(raw)
        assertTrue("中文必须被百分号编码", "公测版" !in safe)
        assertTrue(safe.endsWith("Ling-1.85.2-%E5%85%AC%E6%B5%8B%E7%89%88.apk"))
        val head = "https://updates.example.com/Ling/main/update/"
        assertEquals("纯 ASCII 的路径前缀逐字不变（老通道零影响）", head, safe.substring(0, head.length))
        assertEquals("编码后的文件名解回来就是原名",
            raw.removePrefix(head), java.net.URLDecoder.decode(safe.removePrefix(head), "UTF-8"))
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
