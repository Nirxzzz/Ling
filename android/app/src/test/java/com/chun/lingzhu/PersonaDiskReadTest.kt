package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模拟「磁盘上真实存在的一份 personas.json」→ 解析 → 取开场白 的整条读取链路。
 *
 * 为什么单独写这个：在模拟器上用 adb 改文件再重启，界面始终显示旧值（FileMemo 进程内缓存没失效），
 * 那条路验证不下去。但读取链路本身是纯 JVM 的 —— 直接喂一段真实格式的 JSON 进来，
 * 就能确定「存下来的东西到底能不能被正确读回」。这比在设备上点按钮可靠得多。
 */
class PersonaDiskReadTest {

    /** 与真机上 files/personas.json 完全同构的一段内容（含新字段） */
    private val onDisk = """
        [{"id":"p0","name":"小Ling","emoji":"A","role":"什么都能问",
          "prompt":"","memoryFile":"p0","templateId":"custom","tools":"",
          "model":"deepseek-flash","greeting":"我是自定义开场白","temperature":3}]
    """.trimIndent()

    @Test fun diskJsonIsReadBackWithCustomGreeting() {
        val list = Personas.parse(onDisk)
        assertEquals(1, list.size)
        val me = list.first()
        assertEquals("我是自定义开场白", me.greeting)
        assertEquals("deepseek-flash", me.model)
        assertEquals(3, me.temperature)
    }

    @Test fun greetingShownIsTheCustomOne() {
        // 这一条就是「界面上到底显示什么」的答案：ChatStore.greeting(p) 最终落到 Personas.greeting
        val me = Personas.parse(onDisk).first()
        assertEquals("我是自定义开场白", ChatStore.greeting(me))
    }

    @Test fun nameAndRoleAlsoReadBack() {
        // 顺带守住：如果这一段读不出「小Ling」，说明解析根本没走到文件内容
        val me = Personas.parse(onDisk).first()
        assertEquals("小Ling", me.name)
        assertEquals("什么都能问", me.role)
    }

    @Test fun emptyGreetingGivesTemplateDefault() {
        // 对照组：没写 greeting 的老数据，必须回落到模板默认，不能变成空字符串
        val old = """
            [{"id":"p0","name":"小Ling","role":"r","prompt":"","memoryFile":"p0","templateId":"custom"}]
        """.trimIndent()
        assertEquals("在。说什么？", ChatStore.greeting(Personas.parse(old).first()))
    }
}
