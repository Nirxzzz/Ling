package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {
    private val list = listOf(Modes.Custom("c1", "错题本", "只要答案", 90), Modes.Custom("c2", "字幕", "", 0))

    @Test fun jsonRoundTrip() {
        assertEquals(list, Modes.parse(Modes.toJson(list)))
        assertEquals(emptyList<Modes.Custom>(), Modes.parse("坏的 json"))
        assertEquals(30, Modes.parse("""[{"id":"x","name":"a","prompt":"","opacity":5}]""")[0].opacity)   // 太透明夹到下限
    }

    @Test fun normalizeModeIds() {
        assertEquals("custom:c1", Modes.normalize("custom", list))              // 1.57 的「自定义」
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize("custom", emptyList()))
        assertEquals("custom:c2", Modes.normalize("custom:c2", list))
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize("custom:gone", list))  // 被删了
        assertEquals(Prompts.MODE_EXAM, Modes.normalize("exam", list))
        assertEquals(listOf("general", "exam", "custom:c1", "custom:c2"), Modes.all(list))   // v1.66 删了通识 learn
    }

    /** v1.66：通识模式删掉 —— 存着 learn 的老用户迁移到通用；抽屉 / 设置里不再列它 */
    @Test fun learnMigratesToGeneral() {
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize(Modes.LEGACY_LEARN, list))
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize("learn", emptyList()))
        assertFalse(Modes.all(list).contains("learn"))
        assertFalse(Modes.BUILTIN.contains("learn"))
        assertFalse(Prompts.MODES.contains("learn"))
        assertFalse(Prompts.MODE_LABEL.values.contains("通识"))
        assertEquals("通用", Modes.label(Modes.normalize("learn", list), list))
    }

    /**
     * 单词翻译：双击三态里的第三档。它只保证是**合法模式 id**（存得住、有标签），
     * 但**不进**可选列表 —— 设置页 / 面板抽屉不该凭空多出一个只能靠双击进入的项。
     */
    @Test fun wordModeIsValidButNotSelectable() {
        assertEquals(Prompts.MODE_WORD, Modes.normalize(Prompts.MODE_WORD, list))
        assertEquals(Prompts.MODE_WORD, Modes.normalize("word", emptyList()))   // 没有自定义模式也认得
        assertEquals("单词翻译", Modes.label(Prompts.MODE_WORD, list))
        assertFalse("不进可选列表", Modes.all(list).contains(Prompts.MODE_WORD))
        assertFalse("不是内置模式", Modes.BUILTIN.contains(Prompts.MODE_WORD))
        // 不认的 id 仍然回落到通用 —— 别因为加了 word 就把兜底放宽成「原样返回」
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize("words", list))
        assertEquals(Prompts.MODE_GENERAL, Modes.normalize(null, list))
    }

    @Test fun labelsAndNewIds() {
        assertEquals("错题本", Modes.label("custom:c1", list))
        assertEquals("做题", Modes.label("exam", list))
        assertEquals("c3", Modes.newId(list))
        assertEquals("自定义", Modes.newName(list))
        assertEquals("自定义 2", Modes.newName(list + Modes.Custom("c3", "自定义", "")))
        assertTrue(Modes.newId(listOf(Modes.Custom("c2", "a", ""))) != "c2")
    }
}
