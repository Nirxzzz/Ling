package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边界层：标签解析在极端输入下的行为。
 *
 * NoteSyntax.tags 是纯函数，可以直接在 JVM 上跑 —— 不受 SQLite / Android 限制。
 * 它每次保存都要走一遍（syncTags + syncFts 都调用），所以畸形输入不能崩。
 */
class NoteSyntaxEdgeTest {

    @Test fun emptyBodyHasNoTags() {
        assertTrue(NoteSyntax.tags("").isEmpty())
    }

    @Test fun bodyOfOnlyHashHasNoTags() {
        assertTrue(NoteSyntax.tags("#").isEmpty())
        assertTrue(NoteSyntax.tags("###").isEmpty())
    }

    @Test fun unterminatedBacktickDoesNotHang() {
        // 反引号没闭合：indexOf 返回 -1，代码走 n（结尾）。必须能退出，不能死循环。
        assertEquals(emptyList<String>(), NoteSyntax.tags("`#后面全是代码"))
    }

    @Test fun backtickInMiddleSkipsOnlyToClose() {
        val t = NoteSyntax.tags("前 `#不是标签` 后 #是标签")
        assertEquals(listOf("是标签"), t)
    }

    @Test fun tagAtVeryEndOfBody() {
        assertEquals(listOf("末尾"), NoteSyntax.tags("正文 #末尾"))
    }

    @Test fun hashFollowedByNothingButWhitespace() {
        assertTrue(NoteSyntax.tags("# #").isEmpty())
        assertTrue(NoteSyntax.tags("#   ").isEmpty())
    }

    @Test fun allDigitTagRejected() {
        // #1、#2026 是编号不是标签
        assertTrue(NoteSyntax.tags("#1 #2026 #000").isEmpty())
    }

    @Test fun escapedHashIsNotATag() {
        val backslash = '\\'.toString()
        assertTrue(NoteSyntax.tags(backslash + "#不是标签").isEmpty())
    }

    @Test fun urlFragmentIsNotATag() {
        // http://a.com/#b —— # 紧跟在 / 后面
        assertTrue(NoteSyntax.tags("见 http://a.com/#b").isEmpty())
    }

    @Test fun hierarchyTagKeepsSlashes() {
        assertEquals(listOf("数学/导数"), NoteSyntax.tags("#数学/导数 不会做"))
    }

    @Test fun cSharpIsNotATag() {
        // # 前一个字符是字母数字 → 不是标签
        assertTrue(NoteSyntax.tags("用 C# 写").isEmpty())
    }

    @Test fun veryLongBodyDoesNotBlowUp() {
        // 10 万字符里塞满标签起手符：不能超时、不能栈溢出
        val body = "#a ".repeat(30_000)
        val t = NoteSyntax.tags(body)
        assertEquals(listOf("a"), t)
    }

    @Test fun veryLongSingleTagIsCapped() {
        val t = NoteSyntax.tags("#" + "字".repeat(5000))
        assertEquals(1, t.size)
        assertTrue("超长标签应被 MAX_TAG 截断，实际 " + t[0].length, t[0].length <= 64)
    }

    @Test fun emojiTagIsHandledWithoutCrash() {
        // 记录真实行为，不作非黑即白的断言：emoji 是 OTHER_SYMBOL，
        // isTagChar 认它作为标签字符，但 normTag 之后的下游（bigrams）会把 \p{S} 剥掉。
        // 这里只保证「不抛异常、不产生空标签」。
        val t = NoteSyntax.tags("#🔥 今天很燃")
        t.forEach { assertTrue("标签不能为空串", it.isNotEmpty()) }
    }

    @Test fun fullWidthHashIsNotATag() {
        // 全角 ＃ 是中文标点，不是井号
        assertTrue(NoteSyntax.tags("＃不是标签").isEmpty())
    }

    @Test fun nullByteInBodyDoesNotCrash() {
        // 有些输入法/粘贴会带进 \u0000
        val t = NoteSyntax.tags("前\u0000后 #正常")
        assertEquals(listOf("正常"), t)
    }

    @Test fun bigramsOnEmptyAndSingleChar() {
        // bigrams 是 FTS 索引的分词基础。空串返回空；单字符原样保留
        // （长度为 1 的词不足二元组，直接保留它 —— 否则单个汉字永远搜不到）。
        assertEquals("", Notes.bigrams(""))
        assertEquals("单", Notes.bigrams("单"))
    }

    @Test fun bigramsIsStableForLongInput() {
        val s = "性能测试笔记".repeat(2000)
        val b = Notes.bigrams(s)
        assertTrue("bigrams 不该为空", b.isNotEmpty())
    }
}
