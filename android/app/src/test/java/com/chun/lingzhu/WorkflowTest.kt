package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4 D 档：工作流。
 *
 * 重点在 [Workflow.promptFor] —— 它决定「到底发了什么给模型」。
 * 这里拼错了，表现是「模型忽然答不到点上」，是最难查的一类 bug。
 */
class WorkflowTest {

    private fun flow(vararg steps: Workflow.Step) = Workflow.Flow("f1", "测试流", steps.toList())

    // ── 提示词拼接 ───────────────────────────────────────

    @Test fun placeholderIsReplaced() {
        val f = flow(Workflow.Step("第一步", "先看考点"), Workflow.Step("第二步", "按 " + Workflow.PREV + " 展开"))
        val got = Workflow.promptFor(f, 1, "这题考二次函数")
        assertEquals("按 这题考二次函数 展开", got)
    }

    @Test fun prevIsAppendedWhenNoPlaceholder() {
        val f = flow(Workflow.Step("a", "x"), Workflow.Step("b", "展开讲讲"))
        val got = Workflow.promptFor(f, 1, "考点是二次函数")
        assertTrue("要带上上一步", got.contains("考点是二次函数"))
        assertTrue("要有标记让模型分清哪段是上一步", got.contains("上一步"))
        assertTrue("自己的提示词要在", got.contains("展开讲讲"))
    }

    @Test fun usePrevFalseIgnoresPrevious() {
        val f = flow(Workflow.Step("a", "x"), Workflow.Step("b", "独立作答", usePrev = false))
        val got = Workflow.promptFor(f, 1, "上一步说了很多话")
        assertFalse("不该带上一步", got.contains("上一步说了很多话"))
        assertEquals("独立作答", got)
    }

    @Test fun firstStepHasNoPrev() {
        val f = flow(Workflow.Step("a", "看看这个"), Workflow.Step("b", "然后"))
        assertEquals("看看这个", Workflow.promptFor(f, 0, null))
    }

    @Test fun emptyPrevDoesNotLeaveDanglingMarker() {
        // 上一步失败 / 没输出时，不能拼出「上一步的结果：」后面空一片
        val f = flow(Workflow.Step("a", "x"), Workflow.Step("b", "展开"))
        val got = Workflow.promptFor(f, 1, "  ")
        assertFalse(got.contains("上一步"))
        assertEquals("展开", got)
    }

    @Test fun placeholderWithoutPrevIsRemoved() {
        // 第一步就写了 {{prev}}：没东西可填，要删掉而不是把占位符发给模型
        val f = flow(Workflow.Step("a", "根据 " + Workflow.PREV + " 作答"))
        val got = Workflow.promptFor(f, 0, null)
        assertFalse("不能留占位符给模型", got.contains(Workflow.PREV))
        assertTrue(got.contains("作答"))
    }

    @Test fun outOfRangeIndexGivesEmpty() {
        val f = flow(Workflow.Step("a", "x"))
        assertEquals("", Workflow.promptFor(f, 5, "prev"))
    }

    // ── 可用性判断 ───────────────────────────────────────

    @Test fun singleStepIsNotUsable() {
        // 一步就不叫工作流了，和「自定义模式」没区别
        assertFalse(flow(Workflow.Step("a", "x")).usable)
    }

    @Test fun twoValidStepsIsUsable() {
        assertTrue(flow(Workflow.Step("a", "x"), Workflow.Step("b", "y")).usable)
    }

    @Test fun blankPromptMakesItUnusable() {
        assertFalse(flow(Workflow.Step("a", "x"), Workflow.Step("b", "  ")).usable)
    }

    // ── 序列化 ───────────────────────────────────────────

    @Test fun roundTrip() {
        val orig = listOf(flow(Workflow.Step("看考点", "只说考点"), Workflow.Step("讲", "按 " + Workflow.PREV + " 讲")))
        val back = Workflow.decode(Workflow.encode(orig))
        assertEquals(1, back.size)
        assertEquals("测试流", back[0].name)
        assertEquals(2, back[0].steps.size)
        assertEquals("看考点", back[0].steps[0].title)
        assertEquals(true, back[0].steps[1].usePrev)
    }

    @Test fun decodeRejectsGarbage() {
        assertTrue(Workflow.decode(null).isEmpty())
        assertTrue(Workflow.decode("").isEmpty())
        assertTrue(Workflow.decode("随便一段").isEmpty())
        assertTrue(Workflow.decode(Workflow.PREFIX + "{not json").isEmpty())
    }

    @Test fun tooManyStepsIsCapped() {
        val many = (1..20).map { Workflow.Step("s" + it, "p" + it) }
        val f = Workflow.Flow("big", "大", many)
        val back = Workflow.decode(Workflow.encode(listOf(f)))
        assertTrue("步骤数要有上限", back[0].steps.size <= Workflow.MAX_STEPS)
    }

    @Test fun blankIdIsDropped() {
        val bad = Workflow.Flow("", "无 id", listOf(Workflow.Step("a", "x")))
        assertTrue("没有 id 的流无法持久化，应当丢弃", Workflow.decode(Workflow.encode(listOf(bad))).isEmpty())
    }
}
