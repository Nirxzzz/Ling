package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.85.9：词义 chip 编号 + 句末边界软断行 */
class KaoyanSenseNumberTest {

    // ---------------- numberSenses ----------------

    @Test fun `multiple sense blocks get numbered in order`() {
        val src = listOf(
            Block("音标", "['dʒʌstɪs] n."),
            Block("词义", "① 公平,公正"),
            Block("词义", "② 审判,司法"),
            Block("助记", "just+ice"),
        )
        val out = KaoyanBack.numberSenses(src)
        assertEquals("词义 1", out[1].tag)
        assertEquals("词义 2", out[2].tag)
        assertEquals("助记", out[3].tag)          // 非词义块不动
        assertEquals("① 公平,公正", out[1].text)  // 正文一字不动
    }

    @Test fun `single sense block stays unnumbered`() {
        val src = listOf(Block("词性", "❶n."), Block("词义", "学会,协会"))
        assertEquals(src, KaoyanBack.numberSenses(src))
    }

    @Test fun `variant tags also numbered`() {
        val src = listOf(Block("释义", "a"), Block("词义变化", "b"))
        val out = KaoyanBack.numberSenses(src)
        assertEquals("释义 1", out[0].tag)
        assertEquals("词义变化 2", out[1].tag)
    }

    @Test fun `parse end to end numbers merged blocks`() {
        val back = """
            ['dʒʌstɪs] n.
            【词性】❶n.
            【词义】① 公平,公正
            【词性】❷vt.
            【词义】② 审判,司法
        """.trimIndent()
        val tags = KaoyanBack.parse(back).map { it.tag }
        assertEquals(listOf("", "词义 1", "词义 2"), tags)
    }

    // ---------------- 句末边界断行（走 layout 的 R5） ----------------

    @Test fun `two english sentences break at sentence end not mid-sentence`() {
        // 源数据一行塞两句：超长回扫必须断在第一句句号后，而不是句中
        val line = "② 大批涌入,倾注(u): Huge crowds of people poured into the streets of the capital city. After the meeting a great shower of questions followed."
        val lines = KaoyanBack.layout(line)
        assertTrue("应切成多行: $lines", lines.size >= 2)
        val joined = lines.joinToString("") { it.text }
        assertEquals(line, joined)   // 硬不变量：字符不增不减
        // 没有任何一行是「英文单词中间断开」：行尾字母 + 下一行小写字母开头 = 违规
        for (k in 0 until lines.size - 1) {
            val cur = lines[k].text.trimEnd(); val nxt = lines[k + 1].text.trimStart()
            val bad = cur.isNotEmpty() && nxt.isNotEmpty() &&
                cur.last().isLetter() && cur.last().code < 128 && !cur.endsWith("-") &&
                nxt[0].isLetter() && nxt[0].code < 128 && nxt[0].isLowerCase()
            assertTrue("行 $k 在句中被打断: '$cur' ⏎ '$nxt'", !bad)
        }
    }

    @Test fun `short line untouched`() {
        assertEquals(1, KaoyanBack.layout("一句话，很短。").size)
    }
}
