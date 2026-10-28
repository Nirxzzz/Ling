package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WrongLog：题干归一化、文本启发式抽取、同题去重保留最新、上限淘汰、坏 JSON 容错。 */
class WrongLogTest {

    private val now = 1_700_000_000_000L

    private fun q(
        question: String,
        knowledge: String = "导数",
        correct: Boolean = true,
        at: Long = now,
        id: Long = 0L,
    ) = WrongLog.Q(id, question, knowledge, "", correct, at, "answer")

    // ---------------- 归一化 ----------------

    @Test fun normIgnoresWidthCaseSpaceAndNumbering() {
        assertEquals(WrongLog.norm("1. 求 f(x)=x² 的导数"), WrongLog.norm("求 f(x)=x² 的导数"))
        assertEquals(WrongLog.norm("求 f(x)=x² 的导数"), WrongLog.norm("  求  F(X)=X²  的导数 "))
    }

    @Test fun normStripsOptionLetterButKeepsWordBody() {
        // 选项标记 A. 要剥掉；但正文里的「A股」必须留着，否则不同的题会被并成一条
        assertEquals(WrongLog.norm("股市场"), WrongLog.norm("A. 股市场"))
        assertTrue(WrongLog.norm("A股市场").contains("a股"))
    }

    @Test fun normCollapsesChinesePunctuation() {
        assertEquals(WrongLog.norm("因为x=1,所以y=2"), WrongLog.norm("因为x=1，所以y=2"))
    }

    // ---------------- 文本启发式 ----------------

    @Test fun parseTakesFirstContentLineAsQuestion() {
        val h = WrongLog.parse("# 题目\n求导：y=x³\n知识点：导数\n结果：错误")
        assertEquals("求导：y=x³", h.question)
        assertEquals("导数", h.knowledge)
        assertEquals(false, h.correct)
    }

    @Test fun parsePrefersBracketKnowledge() {
        val h = WrongLog.parse("单选：函数极限存在吗\n【极限】\n回答正确")
        assertEquals("极限", h.knowledge)
        assertEquals(true, h.correct)
    }

    @Test fun parseReturnsNullJudgeWhenNoMarker() {
        // 没有对错标记必须返回 null，不能默默当成「答错」——那会把正确率永久拉低
        assertNull(WrongLog.parse("求 x+1=2 的解").correct)
    }

    @Test fun parseTakesFirstJudgeWhenBothAppear() {
        // 结论在开头，后面的「错误」是解析正文里引用的选项，不能被它覆盖
        assertEquals(true, WrongLog.parse("题目：判断下列命题\n结果：正确\n解析：选项 B 错误").correct)
    }

    @Test fun judgeNeedsContextForTimesSign() {
        assertNull(WrongLog.judgeOf("2×3=6"))
        assertEquals(false, WrongLog.judgeOf("批改：×"))
        assertEquals(true, WrongLog.judgeOf("回答正确"))
        assertEquals(false, WrongLog.judgeOf("答错了，选 B"))
    }

    @Test fun parseFallsBackToWholeTextForQuestion() {
        val h = WrongLog.parse("\n\n   \n知识点：代数")
        assertTrue(h.question.isNotBlank())
        assertEquals("代数", h.knowledge)
    }

    // ---------------- 合并与去重 ----------------

    @Test fun mergeDedupsSameQuestionAndKeepsLatest() {
        val first = WrongLog.merge(emptyList(), q("求导 y=x³", correct = false, at = now))
        assertEquals(1, first.list.size)
        assertEquals(1L, first.q?.id)
        assertTrue(first.added)

        // 同一题（带序号和空格）再答一次并答对：应原地更新，而不是新增一条
        val second = WrongLog.merge(first.list, q(" 2、求导  y = x³ ", correct = true, at = now + 60_000))
        assertEquals(1, second.list.size)
        assertFalse(second.added)
        assertEquals(1L, second.q?.id)                 // id 沿用，界面不跳号
        assertTrue(second.list[0].correct)
        assertEquals(now + 60_000, second.list[0].at)
    }

    @Test fun mergeAssignsIncrementingIdAndPutsNewFirst() {
        var list = emptyList<WrongLog.Q>()
        list = WrongLog.merge(list, q("题一")).list
        list = WrongLog.merge(list, q("题二")).list
        assertEquals(listOf(2L, 1L), list.map { it.id })
    }

    @Test fun mergeDropsOldestBeyondCap() {
        val full = (1..5).map { q("题$it", id = it.toLong()) }
        val res = WrongLog.merge(full, q("新题"), max = 5)
        assertEquals(5, res.list.size)
        assertEquals("新题", res.list[0].question)
        assertTrue(res.list.none { it.question == "题5" })   // 被挤掉的是最旧的
    }

    @Test fun mergeIgnoresBlankQuestion() {
        val res = WrongLog.merge(emptyList(), q("   "))
        assertNull(res.q)
        assertTrue(res.list.isEmpty())
    }

    // ---------------- JSON ----------------

    @Test fun jsonRoundTrip() {
        val list = listOf(q("求导 y=x", "导数", false, now, 7L), q("极限定义", "极限", true, now + 1, 8L))
        assertEquals(list, WrongLog.fromJson(WrongLog.toJson(list)))
    }

    @Test fun fromJsonToleratesGarbage() {
        assertTrue(WrongLog.fromJson(null).isEmpty())
        assertTrue(WrongLog.fromJson("not json").isEmpty())
        assertTrue(WrongLog.fromJson("[1,2,3]").isEmpty())
        val mixed = "[{\"id\":1,\"q\":\"好题\",\"k\":\"导数\",\"a\":\"\",\"ok\":true,\"at\":1,\"src\":\"answer\"},{\"id\":2,\"q\":\"  \"}]"
        assertEquals(1, WrongLog.fromJson(mixed).size)
    }


    // ---------------- recordText 的口径（识屏接线的入口） ----------------
    // OrbService 识屏出答案后调 Store.recordText。它内部是 parse() + merge() 两步，
    // 而 Store 要 Context（本机单测拿不到），所以这里直接测那两步的**组合语义**：
    // 判得出对错才写、判不出来就当没发生。这条语义错了，整个薄弱点画像就是假的。

    /** 有明确批改痕迹 → 记一条，且对错按痕迹走 */
    @Test fun recordTextStoresWhenJudgeIsClear() {
        val h = WrongLog.parse("求导 y=x^2\n知识点：导数\n批改：错误")
        assertEquals("判得出对错就必须记下来", false, h.correct)
        assertEquals("导数", h.knowledge)
        val rec = WrongLog.merge(emptyList(), q(h.question, h.knowledge, false, now, 1L))
        assertTrue(rec.added)
        assertEquals(false, rec.q?.correct)
    }

    /** 没有批改痕迹（普通题目截图）→ 一条都不记，否则每张截图都会被当成错题 */
    @Test fun recordTextSkipsWhenJudgeUnknown() {
        val h = WrongLog.parse("求导 y=x^2 的导数是什么\nA. 2x  B. x  C. x^2")
        assertNull("判不出对错时调用方不能写盘", h.correct)
    }

    /** 同一道题重复识屏（换排版、带序号）只应留一条，且以最新一次为准 */
    @Test fun recordTextDedupsAcrossReprints() {
        val h1 = WrongLog.parse("1. 求导 y=x^2\n批改：错误")
        val first = WrongLog.merge(emptyList(), q(h1.question, "", false, now, 1L))
        val h2 = WrongLog.parse("求导 y=x^2\n批改：正确")
        val second = WrongLog.merge(first.list, q(h2.question, "", true, now + 1000, 2L))
        assertEquals("同题不同编号应并成一条", 1, second.list.size)
        assertEquals("以最新一次为准", true, second.q?.correct)
    }

    @Test fun fromJsonCapsHugeFiles() {
        val big = "[" + (1..(WrongLog.MAX + 50)).joinToString(",") {
            "{\"id\":$it,\"q\":\"题$it\",\"k\":\"\",\"a\":\"\",\"ok\":true,\"at\":0}"
        } + "]"
        assertEquals(WrongLog.MAX, WrongLog.fromJson(big).size)
    }
}
