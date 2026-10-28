package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaoyanBack.numberSenses] + 句末边界软断行。
 *
 * 样本自撰，不含第三方词书原文。
 */
class KaoyanSenseNumberTest {

    // ---------------- numberSenses ----------------

    @Test fun `multiple sense blocks get numbered in order`() {
        val src = listOf(
            Block("音标", "['læntən] n."),
            Block("词义", "① 平整,齐整"),
            Block("词义", "② 提灯"),
            Block("助记", "lant+ern"),
        )
        val out = KaoyanBack.numberSenses(src)
        assertEquals("词义 1", out[1].tag)
        assertEquals("词义 2", out[2].tag)
        assertEquals("助记", out[3].tag)          // 非词义块不动
        assertEquals("① 平整,齐整", out[1].text)  // 正文一字不动
    }

    @Test fun `single sense block stays unnumbered`() {
        val src = listOf(Block("词性", "❶n."), Block("词义", "果园,果树园"))
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
            ['brɪk] n.
            【词性】❶n.
            【词义】① 砖块
            【词性】❷vt.
            【词义】② 堆叠,码放
        """.trimIndent()
        val tags = KaoyanBack.parse(back).map { it.tag }
        assertEquals(listOf("", "词义 1", "词义 2"), tags)
    }

    // ---------------- 句末边界断行（走 layout 的 R5） ----------------

    @Test fun `two english sentences break at sentence end not mid-sentence`() {
        // 源数据一行塞两句：超长回扫必须断在第一句句号后，而不是句中
        val line = "② 大批涌入,堆叠(u): Huge stacks of bricks were piled beside the barn wall. After the meeting a great shower of questions followed."
        val lines = KaoyanBack.layout(line)
        assertTrue("应切成多行: $lines", lines.size >= 2)
        val joined = lines.joinToString("") { it.text }
        assertEquals(line, joined)   // 硬不变量：字符不增不减
        // 没有任何一行是「英文单词中间断开」
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

    @Test fun `sense numbers survive wrap and layout`() {
        // ① 项带一行无序号续行，② 项没有：续行才该拿悬挂缩进
        val body = KaoyanBack.parse("【词义】① 平整,齐整\n砖块要码放得齐整\n② 有序,规整")[0].text
        assertTrue("序号行都在", body.split("\n").count { it.startsWith("①") || it.startsWith("②") } == 2)
        val ls = KaoyanBack.layout(body)
        assertEquals(body, ls.joinToString("") { it.text })
        assertTrue("续行悬挂缩进存在", ls.any { it.indent == 1 })
    }

    @Test fun `exam tag detection stays narrow`() {
        assertTrue(KaoyanBack.isExamTag("真题例句"))
        assertFalse("词组不折叠", KaoyanBack.isExamTag("词组"))
    }
}
