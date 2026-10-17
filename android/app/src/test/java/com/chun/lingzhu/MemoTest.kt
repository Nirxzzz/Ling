package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 随手记：落盘列表 / 去重 / 转待办标记 / 摘要文案。
 * 只测 [Memo] 里的纯函数；[Memo.Store]、[MemoReceiver]、[MemoNotify] 碰 Android，不在这里。
 */
class MemoTest {
    private val now = 1_760_000_000_000L
    private val day = 24L * 3600 * 1000

    private fun it(id: Long, text: String = "m$id", at: Long = now, todoId: Long = 0L) = Memo.Item(id, text, at, todoId)

    // ---------------- 增删 ----------------

    @Test fun addNewestFirstDedupAgainstLatestOnly() {
        var l = Memo.add(emptyList(), it(1, "买菜"))
        l = Memo.add(l, it(2, "交周报"))
        assertEquals(listOf("交周报", "买菜"), l.map { it.text })
        assertEquals(l, Memo.add(l, it(3, "交周报")))     // 和最新一条一样（手滑）→ 不重复记
        assertEquals(3, Memo.add(l, it(4, "买菜")).size)   // 和更早的一样 → 照记
        assertEquals(3L, Memo.add(l, it(2, "买菜")).first().id)   // id 撞了往后挪
        assertEquals(l, Memo.add(l, it(9, "   ")))          // 空文本不进列表
    }

    @Test fun addCapsAtMax() {
        var l = emptyList<Memo.Item>()
        for (n in 1..350) l = Memo.add(l, it(n.toLong(), "m$n"))
        assertEquals(Memo.MAX, l.size)
        assertEquals("m350", l.first().text)
        assertEquals("m51", l.last().text)                  // 丢的是最老的
    }

    @Test fun trimKeepsNewest() {
        val l = (1..5).map { n -> it(n.toLong()) }
        assertEquals(listOf(1L, 2L, 3L), Memo.trim(l, 3).map { it.id })
        assertEquals(l, Memo.trim(l, 5))
        assertEquals(l, Memo.trim(l, 99))
    }

    @Test fun removeById() {
        val l = listOf(it(1), it(2), it(3))
        assertEquals(listOf(1L, 3L), Memo.remove(l, 2L).map { it.id })
        assertEquals(l, Memo.remove(l, 99L))
    }

    // ---------------- 转待办 ----------------

    @Test fun linkedMarksTodoId() {
        val l = listOf(it(1), it(2))
        assertEquals(listOf(0L, 55L), Memo.linked(l, 2L, 55L).map { it.todoId })
        assertEquals("m1", Memo.linked(l, 2L, 55L)[0].text)    // 只改目标那条的字段
        assertEquals(listOf(1L), Memo.loose(Memo.linked(l, 2L, 55L)).map { it.id })
        assertEquals(l, Memo.linked(l, 99L, 55L))               // id 不在列表里 → 不动
    }

    @Test fun relinkReplacesNotAppends() {
        // 改一次只会有一条记录着新 id，不会生出两条
        var l = Memo.linked(listOf(it(1), it(2)), 2L, 55L)
        l = Memo.linked(l, 2L, 66L)
        assertEquals(listOf(0L, 66L), l.map { it.todoId })
        assertEquals(listOf(1L), Memo.loose(l).map { it.id })
        assertEquals(1, Memo.loose(listOf(it(1), it(2, todoId = 1))).size)
    }

    // ---------------- 按天筛选 ----------------

    @Test fun onDayUsesHalfOpenRange() {
        val (s, e) = Todo.dayRange(now)
        val l = listOf(
            it(1, "当天早上", s + 3600_000),
            it(2, "当天 0 点整", s),                          // 含
            it(3, "次日 0 点整", e),                          // 不含
            it(4, "前一天", s - 1000),
        )
        assertEquals(listOf(1L, 2L), Memo.onDay(l, s, e).map { it.id })
    }

    // ---------------- 摘要文案 ----------------

    @Test fun digestEmpty() {
        assertEquals("没有随手记", Memo.digest(emptyList()))
    }

    @Test fun digestCountsAllAndTakesFirstThree() {
        val l = (1..5).map { n -> it(n.toLong(), "m$n") }
        val s = Memo.digest(l)
        assertTrue(s, s.startsWith("记了 5 条：m1、m2、m3"))
        assertTrue(s, s.endsWith(" 等"))                      // 超 3 条才加「等」
        assertTrue(s, !s.contains("m4"))
    }

    @Test fun digestWithoutOverflowHasNoEtc() {
        assertEquals("记了 2 条：买菜、交周报", Memo.digest(listOf(it(1, "买菜"), it(2, "交周报"))))
        assertEquals("记了 1 条：x", Memo.digest(listOf(it(1, "x")), max = 3))
    }

    @Test fun digestUsesFirstLineAndTruncates() {
        val l = listOf(it(1, "  第一行\n第二行  "), it(2, "y".repeat(40)))
        val s = Memo.digest(l)
        assertTrue(s, s.contains("第一行"))                    // 多行只取第一行
        assertTrue(s, !s.contains("第二行"))
        assertTrue(s, s.endsWith("y".repeat(20)))                   // 第二条截到 20 字
        assertTrue(s, !s.contains("y".repeat(21)))
    }

    @Test fun digestRespectsMaxParam() {
        val l = (1..5).map { n -> it(n.toLong(), "m$n") }
        assertEquals("记了 5 条：m1、m2 等", Memo.digest(l, max = 2))
        assertEquals("记了 5 条：m1、m2、m3 等", Memo.digest(l, max = 3))
    }

    // ---------------- clip ----------------

    @Test fun clipTrimsAndKeepsShortText() {
        assertEquals("交周报", Memo.clip("  交周报  "))
        assertEquals("", Memo.clip("   "))
        assertEquals("abc", Memo.clip("abc", 10))
        assertEquals("abcd…", Memo.clip("abcdefgh", 4))        // 恰好超 1 位才加省略号
        assertEquals("abc", Memo.clip("abc", 3))               // 正好等于 → 不动
    }

    @Test fun clipDefaultIs200() {
        assertEquals(200, Memo.clip("x".repeat(200)).length)
        assertEquals(201, Memo.clip("x".repeat(201)).length)
        assertEquals(201, Memo.clip("x".repeat(5000)).length)
    }

    // ---------------- 存取 ----------------

    @Test fun jsonRoundTrip() {
        val l = listOf(it(1, "买菜", now), it(2, "交周报", now - 1000, todoId = 88))
        assertEquals(l, Memo.parse(Memo.toJson(l)))
    }

    @Test fun parseSkipsBlankTextAndToleratesGarbage() {
        assertTrue(Memo.parse(null).isEmpty())
        assertTrue(Memo.parse("").isEmpty())
        assertTrue(Memo.parse("   ").isEmpty())
        assertTrue(Memo.parse("[坏").isEmpty())
        assertTrue(Memo.parse("""[{"id":1,"text":"  "},{"id":2}]""").isEmpty())
        assertEquals(1, Memo.parse("""[{"id":1,"text":"好的","at":5},7]""").size)
    }

    @Test fun parseFillsTodoIdDefault() {
        assertEquals(Memo.Item(1L, "老格式", 9L, 0L), Memo.parse("""[{"id":1,"text":"老格式","at":9}]""").single())
    }

    @Test fun constantsAreStable() {
        assertEquals(300, Memo.MAX)
        assertEquals("memo.json", Memo.FILE)
        assertEquals(40, Memo.NOTI_ID)
        assertEquals("memo", Memo.CHANNEL)
        assertEquals("memo_text", Memo.EXTRA_TEXT)
    }

    @Test fun memoFeedsTodoAsRecord() {
        // 随手记 → 待办的反向：文字过一遍 clip，不会把超长内容带过去
        val m = it(1, "  买牛奶和鸡蛋  ")
        assertEquals("买牛奶和鸡蛋", Todo.memoText(Todo.Item(m.id, m.text, m.at)))
        assertNull(Memo.parse(Memo.toJson(listOf(it(1, " ")))).firstOrNull())
    }
}