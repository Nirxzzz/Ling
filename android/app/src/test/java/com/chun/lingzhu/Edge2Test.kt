package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** edge 轮 2（v1.85.12）：数据层与解析层的极端输入 —— CRLF/BOM/超长行/数值溢出/URL 边界 */
class Edge2Test {

    // ---------------- 词库文本边界 ----------------

    @Test fun `crlf line endings parse same as lf`() {
        val lf = Words.parse("apple n. 苹果\nbanana n. 香蕉")
        val crlf = Words.parse("apple n. 苹果\r\nbanana n. 香蕉")
        assertEquals(lf.map { it.front }, crlf.map { it.front })
        // \r 不许混进内容
        assertTrue(crlf.none { it.back.contains('\r') || it.front.contains('\r') })
    }

    @Test fun `bom at file start does not corrupt first word`() {
        val bom = "\uFEFF"
        val cards = Words.parse(bom + "apple n. 苹果")
        assertTrue(cards.isNotEmpty())
        val first = cards.first().front.lowercase()
        assertTrue("BOM 不许粘在词头上: $first", !first.startsWith("\uFEFF") && first.contains("apple"))
    }

    @Test fun `one megabyte single line does not hang`() {
        val s = "word n. " + "很长的释义".repeat(100_000)   // ~1MB 单行
        val cards = Words.parse(s)
        assertTrue(cards.isNotEmpty())
    }

    @Test fun `marker only lines produce no phantom cards`() {
        val out = Words.parse("【词义】\n【助记】\n①②③\n——\n")
        // 全是标签/序号行，没有词头 → 不该崩；有卡也必须有非空词头
        assertTrue(out.all { it.front.isNotBlank() })
    }

    // ---------------- wrap()（parse 仍在用的旧断行） ----------------

    @Test fun `wrap extremes match layout invariants`() {
        for (s in listOf("", "【未闭合", "①", "a".repeat(3000), "汉".repeat(3000), "】.【【】")) {
            val w = KaoyanBack.wrap(s)
            assertEquals("字符不增不减: '$s'", s.replace("\n", ""), w.replace("\n", ""))
        }
    }

    // ---------------- 提醒时间解析溢出 ----------------

    @Test fun `huge relative minutes do not overflow`() {
        // 千亿分钟：任何实现都不该崩；溢出成负数是 bug
        val now = 1_700_000_000_000L
        val r = Todo.parseWhen("一千亿分钟后提醒我", now = now)
        assertTrue("溢出成负数: ${r.dueAt}", r.dueAt > now || r.dueAt == 0L)
    }

    @Test fun `zero and nonsense relative words return sane result`() {
        val now = 1_700_000_000_000L
        val r = Todo.parseWhen("零分钟后提醒我", now = now)
        assertTrue("返回过去时间: ${r.dueAt}", r.dueAt >= now || r.dueAt == 0L)
    }

    // ---------------- Update.safeUrl 边界 ----------------

    @Test fun `safe url boundaries`() {
        // 纯 ASCII 原样；空串不崩；已编码的百分号不二次编码
        assertEquals("", Update.safeUrl(""))
        val ascii = "https://example.com/update/Ling-1.0.apk"
        assertEquals(ascii, Update.safeUrl(ascii))
        val encoded = "https://example.com/update/%E5%85%AC%E6%B5%8B.apk"
        assertEquals(encoded, Update.safeUrl(encoded))
    }
}
