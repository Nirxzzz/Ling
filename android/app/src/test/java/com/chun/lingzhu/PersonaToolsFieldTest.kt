package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personas 的 JSON 往返 + update 合并。
 *
 * 这组用例的来历是真机上踩到的一个 bug：
 * 「工具权限」对话框点保存后，personas.json 里的 tools 字段**纹丝不动**。
 * 根因是 Personas.update(list, p) 用 old.copy(name=..., emoji=..., role=..., prompt=...)
 * 逐字段白名单合并 —— 新加的 tools 没写进那一行，就被静默丢掉了。
 * 所以这里必须**按住**两条线：JSON 往返不丢字段，update 合并也不丢字段。
 */
class PersonaToolsFieldTest {

    private fun sample(tools: String = "") = Personas.p0().copy(id = "p1", name = "测试", tools = tools)

    @Test fun toJsonKeepsToolsField() {
        val enc = AgentScope.encode(setOf("add_reminder"))
        val json = Personas.toJson(listOf(Personas.p0(), sample(enc)))
        assertTrue("JSON 里必须有 tools 字段: $json", json.contains("\"tools\""));
        assertEquals("tools 原样写进 JSON", enc, Personas.parse(json).last().tools);
    }

    @Test fun oldDataWithoutToolsFieldReadsAsEmpty() {
        // 老版本写出来的文件没有 tools 键；读回来必须是空串（= 全开），不能炸也不能变成全关
        val old = "[{\"id\":\"p0\",\"name\":\"小Ling\",\"emoji\":\"A\",\"role\":\"r\",\"prompt\":\"\",\"memoryFile\":\"p0\",\"templateId\":\"custom\"}]"
        val list = Personas.parse(old)
        assertEquals(1, list.size)
        assertEquals("", list.first().tools)
        assertEquals("空串 = 全开", AgentScope.ALL, list.first().toolScope)
    }

    @Test fun updateMergesToolsField() {
        // 这条就是那个真机 bug 的回归测试：update 之后 tools 必须真的变了
        val before = listOf(Personas.p0(), sample(""))
        val enc = AgentScope.encode(setOf("add_reminder", "search_memory"))
        val after = Personas.update(before, sample(enc))
        val got = after.first { it.id == "p1" }
        assertEquals("update 必须把 tools 合并进去，不能被白名单漏掉", enc, got.tools)
        assertEquals(setOf("add_reminder", "search_memory"), got.toolScope)
    }

    @Test fun updateStillRefusesToChangeIdAndMemoryFile() {
        // 顺带守住反方向：白名单该挡的还得挡住
        val before = listOf(Personas.p0(), sample(""))
        val evil = sample("").copy(id = "p1", memoryFile = "../etc/passwd", templateId = "hacked")
        val got = Personas.update(before, evil).first { it.id == "p1" }
        assertEquals("memoryFile 由 id 决定，外部值必须被忽略", "p1", got.memoryFile)
        assertTrue("templateId 不在白名单里", got.templateId != "hacked")
    }

    @Test fun normKeepsBlankToolsBlank() {
        // 空串 = 没设置过 = 全开。归一化绝不能把空串写成 ["none"]（那会变成全关）
        assertEquals("", Personas.norm(sample("")).tools)
        assertTrue("归一化后仍是全开", Personas.norm(sample("")).toolScope.isEmpty())
    }

    @Test fun normCanonicalisesToolList() {
        val messy = "[\"add_reminder\",\"add_reminder\",\"ghost_tool\"]"
        val n = Personas.norm(sample(messy)).tools
        // 去重 + 顺序稳定；幽灵工具名留给 AgentScope.checked 过滤（这里不静默删，免得用户改回来的名字被吃掉）
        assertTrue(n.contains("add_reminder"))
        assertEquals("重复名要去重", 1, Regex("add_reminder").findAll(n).count())
    }
}
