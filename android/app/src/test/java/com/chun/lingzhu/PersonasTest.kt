package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多人格 M-a（[Personas]）：CRUD、上限 8、p0 不可删、四个模板、归一化 / 序列化。
 *
 * 只测纯函数那一半 —— 不碰 Context、不碰文件。[Personas.Store] / [ChatActivity] 里的界面部分不在这里。
 * 迁移（chat.json → chat-p0.json、card.json → cards/p0.json）在各自 Store 的 init 里，依赖 File IO，
 * 也不在这里；这里测的是「解析时 p0 一定补回来」这条迁移的兜底语义。
 */
class PersonasTest {
    private fun p(id: String, name: String = "小$id", role: String = "干一件事", prompt: String = "") =
        Persona(id = id, name = name, emoji = "🙂", role = role, prompt = prompt, memoryFile = id)

    /** 从模板建一个（跳过 id 分配，直接给固定 id 便于断言） */
    private fun many(n: Int): List<Persona> = (1..n).map { p("p$it") }

    // ---------------- 1. p0：内置人格 ----------------

    @Test fun p0AlwaysExistsAndIsFirst() {
        val l = Personas.parse(null)
        assertEquals(1, l.size)
        assertEquals(Personas.P0, l.first().id)
    }

    @Test fun p0PromptIsEmptySoItFallsBackToGlobalPersona() {
        val p0 = Personas.p0()
        assertTrue("p0 的提示词段必须留空", p0.usesDefaultPrompt)
        assertEquals("空段必须逐字回落到 Prompts.PERSONA", Prompts.PERSONA, Personas.promptOf(p0))
    }

    @Test fun p0UsesDefaultNameAndEmoji() {
        assertEquals("小Ling", Personas.p0().name)
        assertEquals(Personas.P0, Personas.of("p0")?.id)
        assertEquals(null, Personas.of("p1"))
    }

    @Test fun p0CannotBeDeleted() {
        val l = Personas.remove(listOf(Personas.p0()) + many(2), Personas.P0)
        assertTrue("p0 删不掉", l.any { it.id == Personas.P0 })
        assertFalse(Personas.canRemove(Personas.P0))
        assertTrue(Personas.canRemove("p1"))
    }

    @Test fun p0CannotBeAddedTwice() {
        val l = Personas.add(listOf(Personas.p0()), Personas.p0())
        assertEquals(1, l.size)
    }

    @Test fun p0NameClearedFallsBackToDefault() {
        val l = Personas.parse("""[{"id":"p0","name":"","emoji":""}]""")
        assertEquals("小Ling", l.first().name)
        assertEquals(Personas.DEFAULT_EMOJI, l.first().emoji)
    }

    // ---------------- 2. 上限 8 ----------------

    @Test fun maxIsEight() {
        assertEquals(8, Personas.MAX)
    }

    @Test fun addRefusesTheNinth() {
        var l = listOf(Personas.p0()) + many(7)          // 已经 8 个
        assertFalse(Personas.canAdd(l))
        val out = Personas.add(l, p("p99"))
        assertEquals(8, out.size)
        assertTrue(out.none { it.id == "p99" })
    }

    @Test fun canAddUpToSeven() {
        val l = listOf(Personas.p0()) + many(6)          // 7 个
        assertTrue(Personas.canAdd(l))
        assertEquals(8, Personas.add(l, p("p99")).size)
    }

    @Test fun parseAlsoCapsAtEight() {
        val json = JSONArray().apply {
            for (i in 1..12) put(JSONObject().put("id", "p$i").put("name", "小$i"))
        }
        assertEquals(8, Personas.parse(json.toString()).size)
    }

    @Test fun promptTotalBudgetTracksUsage() {
        // 7 个满配人格 = 7×800 = 5600 字，还剩 2400（总量闸是硬保险，正常路径走不到 —— 上限 8 先拦住）
        val fat = (1..7).map { p("p$it", prompt = "字".repeat(2000)) }   // norm 会截到 800
        val l = Personas.add(listOf(Personas.p0()), fat[0])
        assertEquals(Personas.PROMPT_CHARS, l.last().prompt.length)
        val all = (1..7).fold(listOf(Personas.p0())) { acc, i -> Personas.add(acc, fat[i - 1]) }
        assertEquals(8, all.size)
        assertEquals(7 * Personas.PROMPT_CHARS, Personas.promptUsed(all))
        assertEquals(Personas.PROMPT_TOTAL - 7 * Personas.PROMPT_CHARS, Personas.promptLeft(all))
    }

    @Test fun promptLeftShrinksAsPromptsGrow() {
        val l = listOf(Personas.p0()) + listOf(p("p1", prompt = "字".repeat(500)))
        assertEquals(Personas.PROMPT_TOTAL - 500, Personas.promptLeft(l))
    }

    // ---------------- 3. 增删改查 ----------------

    @Test fun addAppendsAndKeepsP0() {
        val out = Personas.add(listOf(Personas.p0()), p("p1", "小舟"))
        assertEquals(2, out.size)
        assertEquals(Personas.P0, out.first().id)
        assertEquals("p1", out.last().id)
    }

    @Test fun addRejectsDuplicateId() {
        val l = listOf(Personas.p0(), p("p1"))
        assertEquals(2, Personas.add(l, p("p1", "另一个")).size)
    }

    @Test fun removeDropsTheRightOne() {
        val l = listOf(Personas.p0(), p("p1"), p("p2"))
        val out = Personas.remove(l, "p1")
        assertEquals(2, out.size)
        assertTrue(out.none { it.id == "p1" })
    }

    @Test fun getFallsBackToP0WhenMissing() {
        val l = listOf(Personas.p0(), p("p1"))
        assertEquals("p1", Personas.get(l, "p1").id)
        assertEquals(Personas.P0, Personas.get(l, "p9").id)
        assertEquals(Personas.P0, Personas.get(l, null).id)
    }

    @Test fun updateChangesEditableFieldsOnly() {
        val l = listOf(Personas.p0(), p("p1", "小舟", role = "旧职责"))
        val out = Personas.update(l, p("p1").copy(name = "小船", role = "新职责"))
        val got = out.first { it.id == "p1" }
        assertEquals("小船", got.name)
        assertEquals("新职责", got.role)
        assertEquals("memoryFile 跟 id 走，不是可编辑字段", "p1", got.memoryFile)
    }

    @Test fun newIdSkipsTakenSlots() {
        val l = listOf(Personas.p0(), p("p1"), p("p3"))
        assertEquals("p2", Personas.newId(l))
    }

    @Test fun cycleWalksTheListAndWraps() {
        val l = listOf(Personas.p0(), p("p1"), p("p2"))
        assertEquals("p2", Personas.cycle(l, "p1", 1))
        assertEquals(Personas.P0, Personas.cycle(l, "p2", 1))
        assertEquals("p2", Personas.cycle(l, Personas.P0, -1))
    }

    // ---------------- 4. 归一化 ----------------

    @Test fun normTruncatesEveryLengthBound() {
        val n = Personas.norm(p("p1", "字".repeat(30), role = "职".repeat(60), prompt = "词".repeat(2000)))
        assertEquals(Personas.NAME_CHARS, n.name.length)
        assertEquals(Personas.ROLE_CHARS, n.role.length)
        assertEquals(Personas.PROMPT_CHARS, n.prompt.length)
    }

    @Test fun normKeepsOnlyFirstEmoji() {
        assertEquals("🙂", Personas.norm(p("p1").copy(emoji = "🙂🙂")).emoji)
    }

    @Test fun normKeepsEmojiThatIsAstralPlane() {
        // 一个汉字/emoji 在 Kotlin 里是 2 个 char，截断不能把它劈成半个（半个会变成乱码）
        val n = Personas.norm(p("p1").copy(emoji = "🙂"))
        assertEquals("🙂", n.emoji)
    }

    @Test fun normDerivesMemoryFileFromId() {
        // 口径定版（v1.71）：memoryFile 不是可编辑字段，一律由 id 派生（safeFile 兜底）——
        // 外部传什么（含 ../ 路径穿越）都不影响文件位置
        assertEquals("p1", Personas.norm(p("p1").copy(memoryFile = "../../etc/passwd")).memoryFile)
        assertEquals("p1", Personas.norm(p("p1").copy(memoryFile = "..")).memoryFile)
    }

    @Test fun normFillsBlankFieldsForNonBuiltin() {
        val n = Personas.norm(Persona("p1", "", "", "", "", ""))
        assertEquals("未命名", n.name)
        assertEquals("空白模板的 emoji", Personas.template(Personas.T_CUSTOM).emoji, n.emoji)
        assertEquals(Personas.T_CUSTOM, n.templateId)
    }

    @Test fun normTrimsWhitespace() {
        assertEquals("小舟", Personas.norm(p("p1", "  小舟  ").copy(role = "  学习  ")).name)
        assertEquals("学习", Personas.norm(p("p1").copy(role = "  学习  ")).role)
    }

    // ---------------- 5. 坏数据 ----------------

    @Test fun brokenJsonFallsBackToP0Only() {
        for (bad in listOf("{坏", "[", "")) {
            assertEquals(listOf("p0"), Personas.parse(bad).map { it.id })
        }
    }

    @Test fun entriesWithoutIdAreDropped() {
        val l = Personas.parse("""[{"name":"没 id"},{"id":"p1","name":"小舟"},null,3]""")
        assertEquals(2, l.size)
        assertEquals("小舟", l[1].name)
    }

    @Test fun duplicateIdsKeepTheFirst() {
        val l = Personas.parse("""[{"id":"p1","name":"先"},{"id":"p1","name":"后"}]""")
        assertEquals(2, l.size)
        assertEquals("先", l[1].name)
    }

    @Test fun unknownTemplateIdBecomesCustom() {
        assertEquals(Personas.T_CUSTOM, Personas.parse("""[{"id":"p1","templateId":"外星模板"}]""")[1].templateId)
    }

    @Test fun oversizedFileOnDiskGetsTrimmed() {
        val long = "长".repeat(5000)
        val n = Personas.parse("""[{"id":"p1","prompt":"$long"}]""")[1]
        assertEquals(Personas.PROMPT_CHARS, n.prompt.length)
    }

    // ---------------- 6. 序列化 ----------------

    @Test fun roundTripKeepsEveryField() {
        val l = listOf(Personas.p0(), p("p1", "小舟", role = "盯学习", prompt = "你是学习教练"))
        assertEquals(l, Personas.parse(Personas.toJson(l)))
    }

    @Test fun jsonShapeHasTheSixFields() {
        val arr = JSONArray(Personas.toJson(listOf(p("p1", "小舟"))))
        assertEquals(2, arr.length())                       // sanitize 自带 p0 打头
        val o = arr.getJSONObject(1)
        for (k in listOf("id", "name", "emoji", "role", "prompt", "memoryFile")) {
            assertTrue("缺字段 $k", o.has(k))
        }
        assertEquals("p1", o.getString("id"))
    }

    @Test fun serializeAlwaysKeepsP0First() {
        val out = JSONArray(Personas.toJson(listOf(p("p1"))))
        assertEquals(2, out.length())
        assertEquals(Personas.P0, out.getJSONObject(0).getString("id"))
    }

    // ---------------- 7. 模板 ----------------

    @Test fun thereAreFourTemplates() {
        assertEquals(4, Personas.TEMPLATES.size)
        assertEquals(listOf("study", "life", "code", "custom"), Personas.TEMPLATES.map { it.id })
    }

    @Test fun eachTemplatePromptIsDistinctAndUnder800() {
        val prompts = Personas.TEMPLATES.map { it.prompt }
        assertEquals("四份提示词不能是同一段换名字", prompts.size, prompts.distinct().size)
        for (t in Personas.TEMPLATES) {
            if (t.id == Personas.T_CUSTOM) continue
            assertTrue("${t.id} 提示词不该超上限", t.prompt.length <= Personas.PROMPT_CHARS)
            assertTrue("${t.id} 提示词太空", t.prompt.length > 100)
        }
    }

    @Test fun eachTemplateHasItsOwnGreeting() {
        val g = Personas.TEMPLATES.map { it.greeting }
        assertEquals("招呼语也要各有各的", g.size, g.distinct().size)
    }

    @Test fun templateLookupFallsBackToBlank() {
        assertEquals("学习教练", Personas.template("study").label)
        assertEquals(Personas.T_CUSTOM, Personas.template("不存在").id)
        assertEquals(Personas.T_CUSTOM, Personas.template(null).id)
    }

    @Test fun fromTemplateCreatesUsablePersona() {
        val out = Personas.fromTemplate(listOf(Personas.p0()), Personas.T_STUDY)
        assertEquals(2, out.size)
        val q = out[1]
        assertEquals("p1", q.id)
        assertEquals("小舟", q.name)
        assertEquals("📚", q.emoji)
        assertEquals(Personas.T_STUDY, q.templateId)
        assertTrue("模板提示词要预填", q.prompt.isNotBlank())
    }

    @Test fun fromTemplateSkipsSlotsAndRefillsWhenFull() {
        var l = listOf(Personas.p0(), p("p1"), p("p2"))
        l = Personas.fromTemplate(l, Personas.T_LIFE)
        assertEquals("p3", l.last().id)
        assertTrue(Personas.canAdd(l) && Personas.fromTemplate(l, Personas.T_CODE).size > l.size)   // 没满就还能加
    }

    @Test fun fromTemplateRejectsWhenFull() {
        val l = listOf(Personas.p0()) + many(7)
        assertEquals(8, Personas.fromTemplate(l, Personas.T_CODE).size)
    }

    @Test fun blankTemplateGivesEmptyPromptSoItFallsBack() {
        val q = Personas.fromTemplate(listOf(Personas.p0()), Personas.T_CUSTOM)[1]
        assertTrue(q.usesDefaultPrompt)
        assertEquals(Prompts.PERSONA, Personas.promptOf(q))
    }

    // ---------------- 8. 提示词 / 招呼语 ----------------

    @Test fun promptOfUsesPersonaSegmentWhenPresent() {
        val q = Personas.fromTemplate(listOf(Personas.p0()), Personas.T_CODE)[1]
        assertTrue(Personas.promptOf(q).contains("编程搭子"))
        assertFalse(Personas.promptOf(q) == Prompts.PERSONA)
    }

    @Test fun greetingOfP0KeepsTheOldLine() {
        assertEquals("在。说什么？", Personas.greeting(Personas.p0()))
        assertEquals("在。说什么？", ChatStore.greeting())
    }

    @Test fun greetingFollowsThePersona() {
        val l = Personas.fromTemplate(listOf(Personas.p0()), Personas.T_STUDY)
        assertNotNull(Personas.greeting(l[1]))
        assertFalse(Personas.greeting(l[1]) == Personas.greeting(Personas.p0()))
    }

    @Test fun titleHasEmojiNameAndChevron() {
        val t = Personas.title(Personas.p0())
        assertTrue(t.contains("小Ling") && t.contains("▾") && t.contains(Personas.DEFAULT_EMOJI))
    }

    /**
     * 聊天页顶栏那行**不带 emoji**（v1.78 用户反馈：顶栏挂个 🙂 太丑）。列表里那份 [Personas.title]
     * 还留着 emoji —— 两个函数必须分开，别哪天顺手把 headerTitle 改回 title。
     */
    @Test fun headerTitleHasNoEmoji() {
        val h = Personas.headerTitle(Personas.p0())
        assertTrue(h.startsWith("小Ling"))
        assertTrue(h.endsWith("▾"))
        assertFalse("顶栏不该带 emoji：$h", h.contains(Personas.DEFAULT_EMOJI))
        assertFalse("顶栏不该带 emoji：$h", h.contains("🙂"))
    }

    @Test fun labelIsOneLineForTheList() {
        assertTrue(Personas.p0().label.contains("小Ling"))
    }

    // ---------------- 9. 与聊天链路接上 ----------------

    @Test fun chatSystemPromptForP0IsUnchanged() {
        // p0 提示词段为空 → 语气仍是只有 PERSONA 一处来源；聊天场景两句 + M4 提醒标记段照序拼上
        assertEquals(
            Prompts.PERSONA + "\n\n现在是聊天，不是识别屏幕 —— 你手上没有截图，用户在跟你说话。" +
                "\n用中文，直接回他说的这件事。" +
                "\n\n" + ChatStore.REMIND_RULE,
            ChatStore.systemPrompt()
        )
    }

    @Test fun chatSystemPromptSwitchesWithPersona() {
        val q = Personas.fromTemplate(listOf(Personas.p0()), Personas.T_CODE)[1]
        val s = ChatStore.systemPrompt(q, null)
        assertTrue(s.contains("编程搭子"))
        assertTrue("聊天场景那两句还得在", s.contains("现在是聊天"))
    }

    @Test fun chatSystemPromptCarriesOnlyThatPersonasMemory() {
        val card = Card(name = "阿准", goal = "考研")
        val s = ChatStore.systemPrompt(Personas.p0(), card)
        assertTrue(s.contains("阿准"))
        assertTrue(s.contains("考研"))
    }

    @Test fun chatSystemPromptSkipsEmptyMemory() {
        assertFalse(ChatStore.systemPrompt(Personas.p0(), Card()).contains("关于用户"))
        assertFalse(ChatStore.systemPrompt(Personas.p0(), null).contains("关于用户"))
    }

    @Test fun chatFilesAreOnePerPersona() {
        assertEquals("chat-p0.json", ChatStore.file(Personas.P0))
        assertEquals("chat-p1.json", ChatStore.file("p1"))
        assertFalse(ChatStore.file("p1") == ChatStore.file("p2"))
    }

    @Test fun cardFilesAreOnePerPersona() {
        assertEquals("cards/p0.json", MemoryCard.file(Personas.P0))
        assertEquals("cards/p1.json", MemoryCard.file("p1"))
    }

    @Test fun pathSuffixIsSanitizedBeforeItReachesTheFilename() {
        assertEquals("chat-p0.json", ChatStore.file("../../p0"))
        assertEquals("cards/p0.json", MemoryCard.file("../../p0"))
    }

    @Test fun legacySinglePersonaFilesAreKeptForMigration() {
        // 旧路径必须还在常量里 —— Store.init 靠它把老用户的数据搬过来
        assertEquals("chat.json", ChatStore.FILE)
        assertEquals("card.json", MemoryCard.FILE)
    }

    @Test fun sanitizeAlwaysKeepsP0AndCaps() {
        val out = Personas.sanitize(many(10) + listOf(Personas.p0()))
        assertEquals(Personas.P0, out.first().id)
        assertEquals(8, out.size)
    }

    @Test fun sanitizeRebuildsP0FromScratchWhenAbsent() {
        val out = Personas.sanitize(many(3))
        assertEquals(Personas.P0, out.first().id)
        assertEquals(4, out.size)
    }
}