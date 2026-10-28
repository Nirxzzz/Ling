package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4「每个 Agent 一套完整人设」：model / greeting / temperature 三个新字段。
 *
 * 这组用例的底线只有一条：**没配过的人一个字节都不能变**。
 * 上一轮 tools 字段就踩过「update 的白名单漏了新字段」，表现是对话框保存了但 JSON 没动。
 */
class PersonaProfileTest {

    private fun sample() = Personas.p0().copy(id = "p1", name = "测试")

    // ── 老数据兼容 ───────────────────────────────────────

    @Test fun oldDataWithoutNewFieldsFallsBackToGlobal() {
        // 老版本写出来的 personas.json 没有 model / greeting / temperature 三个键
        val old = "[{\"id\":\"p0\",\"name\":\"小Ling\",\"emoji\":\"A\",\"role\":\"r\",\"prompt\":\"\",\"memoryFile\":\"p0\",\"templateId\":\"custom\"}]"
        val p = Personas.parse(old).first()
        assertEquals("", p.model)
        assertEquals("", p.greeting)
        assertEquals(TEMP_FOLLOW_GLOBAL, p.temperature)
        assertTrue("没设过模型 = 跟随接口", p.usesDefaultModel)
        assertTrue("没设过温度 = 跟随全局", p.usesDefaultTemperature)
        assertNull("跟随全局时不该给出一个具体温度", p.temperatureValue)
    }

    // ── 往返 ────────────────────────────────────────────

    @Test fun jsonRoundTripKeepsAllThree() {
        val x = sample().copy(model = "deepseek-flash", greeting = "今天想学点什么？", temperature = 7)
        val back = Personas.parse(Personas.toJson(listOf(Personas.p0(), x))).last()
        assertEquals("deepseek-flash", back.model)
        assertEquals("今天想学点什么？", back.greeting)
        assertEquals(7, back.temperature)
        assertEquals(0.7f, back.temperatureValue!!, 0.0001f)
    }

    @Test fun updateMergesAllThree() {
        // 这条守的就是「白名单漏字段」那个老坑
        val before = listOf(Personas.p0(), sample())
        val after = Personas.update(before, sample().copy(model = "m1", greeting = "嗨", temperature = 3))
        val got = after.first { it.id == "p1" }
        assertEquals("m1", got.model)
        assertEquals("嗨", got.greeting)
        assertEquals(3, got.temperature)
    }

    // ── 温度 ────────────────────────────────────────────

    @Test fun temperatureIsClampedIntoRange() {
        assertEquals(20, Personas.norm(sample().copy(temperature = 99)).temperature)
        assertEquals(0, Personas.norm(sample().copy(temperature = -5)).temperature)
    }

    @Test fun followGlobalSentinelSurvivesNorm() {
        // -1 是哨兵，不能被 coerceIn(0,20) 夹成 0 —— 那等于把「跟随全局」变成「温度 0」
        assertEquals(TEMP_FOLLOW_GLOBAL, Personas.norm(sample().copy(temperature = -1)).temperature)
        assertTrue(Personas.norm(sample().copy(temperature = -1)).usesDefaultTemperature)
    }

    @Test fun temperatureValueMapsTenthsToFloat() {
        assertEquals(0.0f, sample().copy(temperature = 0).temperatureValue!!, 0.0001f)
        assertEquals(1.0f, sample().copy(temperature = 10).temperatureValue!!, 0.0001f)
        assertEquals(2.0f, sample().copy(temperature = 20).temperatureValue!!, 0.0001f)
    }

    // ── 开场白优先级 ─────────────────────────────────────

    @Test fun ownGreetingWinsOverTemplate() {
        val x = sample().copy(templateId = "study", greeting = "我是你专属的助教")
        assertEquals("我是你专属的助教", Personas.greeting(x))
    }

    @Test fun blankGreetingFallsBackToTemplate() {
        val x = sample().copy(templateId = "study", greeting = "")
        assertEquals(Personas.template("study").greeting, Personas.greeting(x))
    }

    @Test fun blankGreetingFallsBackForP0Too() {
        assertEquals("在。说什么？", Personas.greeting(Personas.p0().copy(greeting = "")))
    }

    @Test fun ownGreetingOverridesP0Default() {
        assertEquals("换了个说法", Personas.greeting(Personas.p0().copy(greeting = "换了个说法")))
    }

    // ── 模型 ────────────────────────────────────────────

    @Test fun modelIsTrimmedAndCapped() {
        assertEquals("m1", Personas.norm(sample().copy(model = "  m1  ")).model)
        val long = "x".repeat(Personas.MODEL_CHARS + 30)
        assertEquals(Personas.MODEL_CHARS, Personas.norm(sample().copy(model = long)).model.length)
    }

    @Test fun unknownModelNameIsKept() {
        val x = Personas.norm(sample().copy(model = "my-private-model-2027"))
        assertEquals("my-private-model-2027", x.model)
        assertFalse("写了自己模型 = 不再跟随接口", x.usesDefaultModel)
    }

    @Test fun greetingIsCapped() {
        val long = "啊".repeat(Personas.GREETING_CHARS + 50)
        assertEquals(Personas.GREETING_CHARS, Personas.norm(sample().copy(greeting = long)).greeting.length)
    }
}
