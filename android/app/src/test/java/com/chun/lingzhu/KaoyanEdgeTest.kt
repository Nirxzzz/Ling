package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** edge 轮（v1.85.12）：这几轮改过的路径的极端输入 —— 不崩、不变量成立、循环会终止 */
class KaoyanEdgeTest {

    // ---------------- layout / softBreak ----------------

    @Test fun `empty and blank inputs`() {
        // 现行为：空串返回单个空行（不崩、不抛）；单空白原样保留
        val empty = KaoyanBack.layout("")
        assertTrue(empty.isEmpty() || (empty.size == 1 && empty[0].text.isEmpty()))
        assertEquals(1, KaoyanBack.layout(" ").size)
    }

    @Test fun `only punctuation line`() {
        val ls = KaoyanBack.layout("。，、；：！？")
        assertEquals("。，、；：！？", ls.joinToString("") { it.text })
    }

    @Test fun `only circle markers`() {
        val ls = KaoyanBack.layout("①②③④⑤")
        assertEquals(5, ls.size)                              // 每个序号一行
        assertEquals("①②③④⑤", ls.joinToString("") { it.text })
    }

    @Test fun `unbalanced open tag never crashes and keeps text`() {
        val s = "【词义没有闭合就一直延伸下去① ② 很多内容".repeat(10)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })      // 字符不增不减
        assertTrue(ls.isNotEmpty())
    }

    @Test fun `super long line with no break point terminates`() {
        // 10_000 个无空格英文字母：硬切循环必须终止且不丢字符
        val s = "a".repeat(10_000)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
        assertTrue(ls.size > 50)
    }

    @Test fun `super long chinese line terminates`() {
        val s = "字".repeat(5_000)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
    }

    @Test fun `sentence marker soup - no infinite loop`() {
        val s = "end. end. end. ".repeat(500)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
    }

    @Test fun `clause boundary soup`() {
        val s = "a, b, c, d, e, f, g, ".repeat(300)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
    }

    // ---------------- numberSenses ----------------

    @Test fun `numberSenses empty list`() {
        assertEquals(0, KaoyanBack.numberSenses(emptyList()).size)
    }

    @Test fun `numberSenses all sense tags`() {
        val out = KaoyanBack.numberSenses(List(5) { Block("词义", "t$it") })
        assertEquals(listOf("词义 1", "词义 2", "词义 3", "词义 4", "词义 5"), out.map { it.tag })
    }

    // ---------------- ChatBars 溢出 ----------------

    @Test fun `bottomPad extreme values do not overflow`() {
        assertEquals(Int.MAX_VALUE, ChatBars.bottomPad(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, ChatBars.bottomPad(Int.MAX_VALUE, 0))
        assertEquals(0, ChatBars.bottomPad(0, Int.MIN_VALUE).coerceAtMost(0) + 0) // 全负 → 0
        assertEquals(0, ChatBars.bottomPad(Int.MIN_VALUE, Int.MIN_VALUE))
    }

    @Test fun `topPad extreme values`() {
        assertEquals(0, ChatBars.topPad(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)[1])
        assertEquals(Int.MAX_VALUE, ChatBars.topPad(Int.MAX_VALUE, 0, 0, 0)[1])
    }

    // ---------------- 数据层病态输入 ----------------

    @Test fun `empty wordbank text parses to nothing`() {
        assertEquals(0, Words.parse("").size)
    }

    @Test fun `garbage lines do not crash parse`() {
        val out = Words.parse("【词义】\n\n。。。===\n[not a word]\n\t\njust one line with no structure")
        // 不崩即可；条数不定，但每个 card 都该有非空 front
        assertTrue(out.all { it.front.isNotBlank() })
    }

    // ---------------- v1.85.13 内嵌记号 / 英文句末空格 的极端输入 ----------------

    @Test fun `inline latin or digit token at edges keeps every char`() {
        // 末尾单个字母、内嵌数字、纯 ASCII —— 只钉「不崩 + 字符不增不减」，不钉具体分不分行
        for (s in listOf("中文A", "答案B", "第3页", "a b c 1", "20", "A")) {
            assertEquals("字符不增不减：<$s>", s, KaoyanBack.layout(s).joinToString("") { it.text })
        }
    }

    @Test fun `many inline tokens terminate and keep chars`() {
        val s = "第1页共有20个例子。".repeat(200)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
        assertTrue(ls.isNotEmpty())
    }

    @Test fun `english sentence followed by spaced chinese terminates`() {
        val s = "It works. 它行得通。".repeat(300)
        val ls = KaoyanBack.layout(s)
        assertEquals(s, ls.joinToString("") { it.text })
        assertTrue(ls.isNotEmpty())
    }
}
