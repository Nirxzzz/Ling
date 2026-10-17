package com.chun.lingzhu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 人格头像 key 的映射（v1.85）。纯逻辑，不碰 Canvas。
 * 背景：用户不要 emoji 当头像 —— 改成 [PersonaIcon] 画的矢量图，这里只管「谁用哪张图」。
 */
class PersonaIconTest {

    @Test fun builtinPersonaUsesChatIcon() {
        // 内置「小Ling」不管模板是什么，都是对话泡泡
        assertEquals(PersonaIcon.K_CHAT, PersonaIcon.keyFor(Personas.P0, "custom"))
        assertEquals(PersonaIcon.K_CHAT, PersonaIcon.keyFor(Personas.P0, "study"))
    }

    @Test fun templatesMapToTheirIcon() {
        assertEquals(PersonaIcon.K_STUDY, PersonaIcon.keyFor("p1", Personas.T_STUDY))
        assertEquals(PersonaIcon.K_LIFE, PersonaIcon.keyFor("p2", Personas.T_LIFE))
        assertEquals(PersonaIcon.K_CODE, PersonaIcon.keyFor("p3", Personas.T_CODE))
    }

    @Test fun customAndUnknownFallBackToChat() {
        assertEquals(PersonaIcon.K_CHAT, PersonaIcon.keyFor("p4", Personas.T_CUSTOM))
        assertEquals(PersonaIcon.K_CHAT, PersonaIcon.keyFor("p5", "whatever"))
        // 模板那条也要能用（模板自己没有 id，传空串）
        assertEquals(PersonaIcon.K_STUDY, Personas.TEMPLATES.first { it.id == Personas.T_STUDY }.iconKey)
    }
}
