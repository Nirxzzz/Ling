package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test

/** ChatBars：聊天页底栏 insets 的纯函数判断（v1.85.8 修底栏被导航条/键盘压住） */
class ChatBarsTest {

    @Test fun `no bars no keyboard - pad is zero`() {
        assertEquals(0, ChatBars.bottomPad(0, 0))
    }

    @Test fun `gesture nav only`() {
        assertEquals(63, ChatBars.bottomPad(63, 63))   // 手势条 24dp：IME inset 平时等于系统栏
    }

    @Test fun `keyboard open - ime includes nav bar, pad takes ime`() {
        assertEquals(1023, ChatBars.bottomPad(63, 1023))
    }

    @Test fun `ime smaller than system bar - never shrink below system bar`() {
        assertEquals(63, ChatBars.bottomPad(63, 40))
    }

    @Test fun `negative values treated as zero`() {
        assertEquals(0, ChatBars.bottomPad(-5, -3))
        assertEquals(10, ChatBars.bottomPad(10, -3))
    }

    @Test fun `three button nav taller bar`() {
        assertEquals(132, ChatBars.bottomPad(132, 132))
        assertEquals(1200, ChatBars.bottomPad(132, 1200))
    }

    @Test fun `topPad takes max of status bar and cutout`() {
        assertArrayEqualsEquals(intArrayOf(0, 100, 0), ChatBars.topPad(100, 0, 0, 0))
        assertArrayEqualsEquals(intArrayOf(0, 120, 0), ChatBars.topPad(100, 120, 0, 0))
        assertArrayEqualsEquals(intArrayOf(30, 100, 30), ChatBars.topPad(100, 0, 30, 30))
    }

    @Test fun `topPad drops negatives`() {
        assertArrayEqualsEquals(intArrayOf(0, 100, 0), ChatBars.topPad(100, -1, -8, -8))
    }

    private fun assertArrayEqualsEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
