package com.chun.lingzhu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.69 自定义体系（[Customize]）：归一化 / 存取 / 导入导出 / 恢复默认 / 长度防呆。
 *
 * 全部用 [Customize.MapStore]（内存实现），不碰 Android —— [Customize] 本身是纯 JVM 的，
 * 所以这些用例在 `test` 源集里直接跑（org.json 用的是真实现，见 app/build.gradle.kts）。
 *
 * 这个文件守住三条承诺：
 *  - 层1 开箱：没存过 = 默认值，且 CATALOG 里每一项的 def 都自洽（落在自己的区间 / 枚举里）。
 *  - 脏数据进不来：任何写入路径（set / import）都先过 [Customize.norm]，非法值回落默认值。
 *  - 改坏了能回来：[Customize.restore] / [Customize.restoreAll] / [Customize.set] 存成默认值即删键。
 */
class CustomizeTest {

    private fun store() = Customize.MapStore()

    private fun spec(id: String): Customize.Spec = Customize.byId(id) ?: error("清单里没有 $id")

    // ---------------- 清单自洽（层1 的承诺写死在清单里，先证明它自己没矛盾）----------------

    @Test fun catalogIdsUniqueAndResolvable() {
        assertEquals("id 重复了（导出会互相覆盖）", Customize.CATALOG.size, Customize.CATALOG.map { it.id }.toSet().size)
        for (s in Customize.CATALOG) {
            assertEquals("byId 应能反查回 $s", s, Customize.byId(s.id))
            assertTrue("$s.id 缺说明", s.hint.isNotBlank())
            assertTrue("${s.id} 没写从哪儿长按进来", s.longPress.isNotBlank())
        }
    }

    /** 每一项的默认值本身必须合法 —— 否则「没改过」这条路径就会拿到一个非法值 */
    @Test fun everyDefaultIsAlreadyNormalized() {
        for (s in Customize.CATALOG) {
            assertEquals("${s.id} 的默认值没过自己的 norm", s.def, Customize.norm(s, s.def))
            assertTrue("${s.id} 默认值还在默认值上", s.isDefault(s.def))
        }
    }

    /** 数字项的默认值必须落在自己声明的区间里（否则 coerce 会把老用户的默认悄悄改掉） */
    @Test fun numberDefaultsInsideDeclaredRange() {
        for (s in Customize.CATALOG.filter { it.kind == Customize.Kind.NUMBER }) {
            val d = s.def.toIntOrNull() ?: error("${s.id} 默认值不是数字")
            assertTrue("${s.id} 默认 $d 不在 ${s.min}~${s.max}", d in s.min..s.max)
            assertTrue("${s.id} 上限应大于下限", s.max > s.min)
        }
    }

    /** 枚举项的默认值必须是 options 里的一个，否则 norm 会把它判成非法值回落、显示和存储不一致 */
    @Test fun enumDefaultsInsideOptions() {
        for (s in Customize.CATALOG.filter { it.kind == Customize.Kind.ENUM }) {
            assertTrue("${s.id} 没配选项", s.options.isNotEmpty())
            assertTrue("${s.id} 默认值 ${s.def} 不在选项里", s.def in s.options)
            assertEquals("${s.id} 有选项没中文名", s.options.size, s.optionLabels.size)
            for (o in s.options) assertTrue("${s.id} 的 $o 没有中文名", s.optionLabel(o).isNotBlank())
        }
    }

    // ---------------- 长度防呆 ----------------

    /** 短文本：trim + 夹到 MAX_TEXT_CHARS（称呼 / 口头禅这种「插一句话」的位置） */
    @Test fun textTrimsAndClipsTo40() {
        val s = spec("persona.name")
        assertEquals(Customize.MAX_TEXT_CHARS, s.limit)
        assertEquals(40, Customize.MAX_TEXT_CHARS)
        assertEquals("同学", Customize.norm(s, "  同学 \n"))
        assertEquals(40, Customize.norm(s, "啊".repeat(500)).length)
        assertEquals("", Customize.norm(s, "   "))
        assertEquals("", Customize.norm(s, null))
    }

    /** 提示词段：硬上限 2000 字（≈3000 token），超了截断而不是拒收 */
    @Test fun promptClipsTo2000Chars() {
        val s = spec("persona.tone")
        assertEquals(Customize.MAX_PROMPT_CHARS, s.limit)
        assertEquals(2000, Customize.MAX_PROMPT_CHARS)
        val long = "请用小学数学的方式讲清楚 ".repeat(200)
        assertTrue(long.length > Customize.MAX_PROMPT_CHARS)
        val v = Customize.norm(s, long)
        assertEquals(Customize.MAX_PROMPT_CHARS, v.length)
        // 截断后仍是合法值（截断不会把值变成非法值）
        assertEquals(v, Customize.norm(s, v))
    }

    /** 列表型文本自己给上限（快捷短语一行一条，40 字装不下） */
    @Test fun listTextUsesItsOwnLimit() {
        val s = spec("ask.phrases")
        assertEquals(300, s.limit)
        assertEquals(300, Customize.norm(s, "x".repeat(1000)).length)
    }

    /** overLimit：界面上提示「超了多少」用；没超 / 没上限（数字项）都报 0 */
    @Test fun overLimitReportsHowMuchWasCut() {
        val s = spec("persona.tone")
        assertEquals(0, Customize.overLimit(s, "短"))
        assertEquals(0, Customize.overLimit(s, null))
        assertEquals(500, Customize.overLimit(s, "字".repeat(2500)))
        // 数字项没有长度上限（limit = 0），不该报超长
        assertEquals(0, Customize.overLimit(spec("orb.sizeDp"), "9".repeat(999)))
    }

    /** clip 是给 Prefs 具名属性复用的同一条截断规则 */
    @Test fun clipIsIdempotentAndNeverThrows() {
        assertEquals("abc", Customize.clip("abc", 3))
        assertEquals("ab", Customize.clip("abc", 2))
        assertEquals("", Customize.clip("abc", 0))
    }

    // ---------------- 数字：夹区间 + 非数字回落 ----------------

    @Test fun numberClampsIntoRange() {
        val s = spec("orb.sizeDp")
        assertEquals("58", Customize.norm(s, "58"))
        assertEquals("40", Customize.norm(s, "1"))       // 低于下限 → 夹到下限
        assertEquals("84", Customize.norm(s, "9999"))    // 高于上限 → 夹到上限
        assertEquals("60", Customize.norm(s, " 60 "))
    }

    @Test fun numberNonNumericFallsBackToDefault() {
        val s = spec("orb.sizeDp")
        assertEquals("58", Customize.norm(s, "abc"))
        assertEquals("58", Customize.norm(s, "58.5"))    // 小数不认
        assertEquals("58", Customize.norm(s, ""))
        assertEquals("58", Customize.norm(s, null))
        assertEquals("500", Customize.norm(spec("orb.holdMs"), null))
    }

    // ---------------- 枚举：只认 options 里的 ----------------

    @Test fun enumKeepsLegalValue() {
        val s = spec("orb.edge")
        assertEquals(Customize.EDGE_FAR, Customize.norm(s, Customize.EDGE_FAR))
        assertEquals(Customize.EDGE_NEAR, Customize.norm(s, "  near  "))
    }

    /** 手改坏的 / 老版本留下的枚举值：回落默认值，不崩、不把非法值往下传 */
    @Test fun enumIllegalFallsBackToDefault() {
        val s = spec("orb.edge")
        assertEquals(Customize.EDGE_HUG, Customize.norm(s, "far-ish"))
        assertEquals(Customize.EDGE_HUG, Customize.norm(s, ""))
        assertEquals(Customize.EDGE_HUG, Customize.norm(s, null))
        assertEquals(Customize.DETAIL_STANDARD, Customize.norm(spec("mode.detailDefault"), "godmode"))
    }

    // ---------------- 开关 ----------------

    @Test fun boolAcceptsCommonTruthyAndFalsy() {
        val s = Customize.Spec("t", "g", "t", Customize.Kind.BOOL, "false")
        for (v in listOf("true", "TRUE", "1", " yes ", "on")) assertEquals("true", Customize.norm(s, v))
        for (v in listOf("false", "0", "no", "off", "", "   ")) assertEquals("false", Customize.norm(s, v))
        // 完全不认识的：回落默认值（这里 def = false），不许猜
        assertEquals("false", Customize.norm(s, "maybe"))
        assertEquals("false", Customize.norm(s, null))
    }

    // ---------------- 颜色：借 Theme.parseHex 校验 ----------------

    @Test fun colorAcceptsShortAndLongHex() {
        val s = Customize.Spec("c", "g", "c", Customize.Kind.COLOR, "#000000")
        assertEquals("#FF0000", Customize.norm(s, "#FF0000"))
        assertEquals("#FF0000", Customize.norm(s, "#ff0000"))   // 大小写统一成大写
        assertEquals("#FF0000", Customize.norm(s, "f00"))        // 三位缩写
        assertEquals("#FF0000", Customize.norm(s, "  #FF0000  "))
    }

    @Test fun colorIllegalFallsBackToDefault() {
        val s = Customize.Spec("c", "g", "c", Customize.Kind.COLOR, "#123456")
        for (bad in listOf("", "#12345", "#GGGGGG", "red", "#1234567", null)) {
            assertEquals("「$bad」应回落默认值", "#123456", Customize.norm(s, bad))
        }
        // display 把颜色翻成 #RRGGBB 给人看
        assertEquals("#FF0000", s.display("#f00"))
        assertEquals("#123456", s.display("坏了"))
    }

    // ---------------- 存取 ----------------

    @Test fun setAndGetRoundTrip() {
        val st = store()
        val s = spec("persona.name")
        assertEquals("", Customize.get(s, st))          // 没存过 = 默认值
        assertEquals("同学", Customize.set(s, st, " 同学 "))
        assertEquals("同学", Customize.get(s, st))
        assertTrue(st.all().containsKey("persona.name"))
    }

    /** 存成默认值 = 没自定义过：删键。这样「恢复默认」是真的回到零状态 */
    @Test fun setToDefaultRemovesKey() {
        val st = store()
        val s = spec("persona.name")
        Customize.set(s, st, "同学")
        assertTrue(st.all().containsKey("persona.name"))
        Customize.set(s, st, "")
        assertFalse("存成默认值应删键而不是留一份", st.all().containsKey("persona.name"))
        assertEquals("", Customize.get(s, st))
    }

    /** 存进去的一定是截断后的值，返回值就是真正落盘的那个（界面据此提示被截了多少） */
    @Test fun setReturnsTheValueActuallyStored() {
        val st = store()
        val s = spec("persona.tone")
        val stored = Customize.set(s, st, "啊".repeat(3000))
        assertEquals(Customize.MAX_PROMPT_CHARS, stored.length)
        assertEquals(stored, Customize.get(s, st))
        assertEquals(stored, st.all()["persona.tone"])
    }

    @Test fun setEnforcesEnumAndRange() {
        val st = store()
        assertEquals("40", Customize.set(spec("orb.sizeDp"), st, "-5"))
        assertEquals(Customize.EDGE_FAR, Customize.set(spec("orb.edge"), st, "far"))
        assertEquals(Customize.EDGE_HUG, Customize.set(spec("orb.edge"), st, "乱写"))
    }

    @Test fun getNormalizesDirtyStoredValue() {
        val st = store()
        st.put("orb.sizeDp", "9999")       // 模拟用户手改 prefs / 跨版本留下的脏值
        st.put("orb.edge", "nowhere")
        assertEquals("84", Customize.get(spec("orb.sizeDp"), st))
        assertEquals(Customize.EDGE_HUG, Customize.get(spec("orb.edge"), st))
        // 归一化后正好等于默认值 = 这一项在行为上等于没改过（面板照默认值显示，不该报「已改」）
        assertTrue(spec("orb.edge").isDefault(Customize.get(spec("orb.edge"), st)))
        assertFalse(spec("orb.sizeDp").isDefault(Customize.get(spec("orb.sizeDp"), st)))
    }

    @Test fun restoreBringsBackDefault() {
        val st = store()
        val s = spec("persona.catch")
        Customize.set(s, st, "emm")
        Customize.restore(s, st)
        assertEquals("", Customize.get(s, st))
        assertFalse(st.all().containsKey("persona.catch"))
    }

    @Test fun restoreAllClearsEveryItem() {
        val st = store()
        for (s in Customize.CATALOG) Customize.set(s, st, dirtyValueFor(s))
        assertTrue(st.all().isNotEmpty())
        Customize.restoreAll(st)
        assertEquals("全部恢复默认后不该剩任何键", 0, st.all().size)
        for (s in Customize.CATALOG) assertTrue("${s.id} 应回到默认值", s.isDefault(Customize.get(s, st)))
    }

    /** MapStore 自己的 remove / all 也要正确（导出会遍历它） */
    @Test fun mapStoreRemoveAndSnapshot() {
        val st = Customize.MapStore()
        st.put("a", "1"); st.put("b", "2")
        assertEquals(mapOf("a" to "1", "b" to "2"), st.all())
        st.remove("a")
        assertEquals(mapOf("b" to "2"), st.all())
        assertNull(st.get("a"))
        assertNull(st.get("根本没有这个键"))
    }

    // ---------------- 导入 / 导出 ----------------

    @Test fun exportOnlyIncludesChangedItems() {
        val st = store()
        Customize.set(spec("persona.name"), st, "同学")
        Customize.set(spec("orb.sizeDp"), st, "72")
        val o = JSONObject(Customize.export(st))
        assertEquals("lingzhu-custom", o.getString("format"))
        assertEquals(Customize.FORMAT_VERSION, o.getInt("version"))
        val items = o.getJSONObject("items")
        assertEquals(2, items.length())
        assertEquals("同学", items.getString("persona.name"))
        assertEquals("72", items.getString("orb.sizeDp"))
    }

    @Test fun exportOfFreshInstallIsEmptyButValid() {
        val o = JSONObject(Customize.export(store()))
        assertEquals(0, o.getJSONObject("items").length())
        assertEquals("lingzhu-custom", o.getString("format"))
    }

    @Test fun exportImportRoundTrip() {
        val a = store()
        Customize.set(spec("persona.name"), a, "同学")
        Customize.set(spec("persona.tone"), a, "少用比喻，多给例子")
        Customize.set(spec("panel.pos"), a, PanelLayout.RIGHT)
        Customize.set(spec("orb.holdMs"), a, "300")
        val b = store()
        val r = Customize.import(Customize.export(a), b)
        assertEquals(4, r.applied.size)
        assertTrue("不该有跳过的项：${r.skipped}", r.skipped.isEmpty())
        for (s in Customize.CATALOG) assertEquals("${s.id} 应对上", Customize.get(s, a), Customize.get(s, b))
    }

    /** replace = false：只覆盖对方改过的项，本机其他自定义留着 */
    @Test fun importMergeKeepsLocalCustomizations() {
        val st = store()
        Customize.set(spec("persona.name"), st, "本机叫法")
        Customize.set(spec("panel.pos"), st, PanelLayout.TOP)
        val r = Customize.import("""{"format":"lingzhu-custom","version":1,"items":{"panel.pos":"right"}}""", st, replace = false)
        assertEquals(listOf("panel.pos"), r.applied)
        assertEquals("本机叫法", Customize.get(spec("persona.name"), st))
        assertEquals(PanelLayout.RIGHT, Customize.get(spec("panel.pos"), st))
    }

    /** replace = true：先全部恢复默认再导入，干净贴合对方 */
    @Test fun importReplaceWipesLocalFirst() {
        val st = store()
        Customize.set(spec("persona.name"), st, "本机叫法")
        Customize.set(spec("orb.sizeDp"), st, "72")
        Customize.import("""{"format":"lingzhu-custom","version":1,"items":{"persona.tone":"简短点"}}""", st, replace = true)
        assertEquals("", Customize.get(spec("persona.name"), st))
        assertEquals("58", Customize.get(spec("orb.sizeDp"), st))
        assertEquals("简短点", Customize.get(spec("persona.tone"), st))
    }

    /** 对方配置里有本机没有的 id（对方版本更新）→ 跳过并如实报出来，不报错、不崩 */
    @Test fun importSkipsUnknownIds() {
        val st = store()
        val r = Customize.import(
            """{"format":"lingzhu-custom","version":1,"items":{"orb.hologram":"on","persona.name":"同学"}}""", st)
        assertEquals(listOf("persona.name"), r.applied)
        assertEquals(1, r.skipped.size)
        assertTrue("跳过原因要说清是本机没这一项：${r.skipped}", r.skipped[0].contains("本机没有这一项"))
    }

    /**
     * 归一化后**正好等于默认值**的项要如实报进 skipped，不能让人以为导进去了。
     * 两种情况都算：对方就填的默认值（无事可做）、对方的值非法（枚举不认 / 颜色坏了 / 非数字）。
     * 注意区分：超界但夹得住的数字（9999 → 84）是合法的新值，算导入成功，不算跳过。
     */
    @Test fun importReportsDefaultValuedItemsAsSkipped() {
        val r = Customize.import("""{"format":"lingzhu-custom","version":1,"items":{"orb.sizeDp":"58"}}""", store())
        assertTrue(r.applied.isEmpty())
        assertEquals(1, r.skipped.size)
    }

    @Test fun importReportsIllegalValuesAsSkipped() {
        val st = store()
        val r = Customize.import(
            """{"format":"lingzhu-custom","version":1,"items":{"orb.edge":"乱写","panel.textSp":"abc"}}""", st)
        assertTrue("非法值不该算导入成功：${r.applied}", r.applied.isEmpty())
        assertEquals(2, r.skipped.size)
        assertEquals(Customize.EDGE_HUG, Customize.get(spec("orb.edge"), st))
        assertEquals("15", Customize.get(spec("panel.textSp"), st))
    }

    /** 夹得住的超界值 = 合法的新值：导入成功并已夹回区间内 */
    @Test fun importClampsOutOfRangeButAppliesIt() {
        val st = store()
        val r = Customize.import("""{"format":"lingzhu-custom","version":1,"items":{"orb.sizeDp":"9999"}}""", st)
        assertEquals(listOf("orb.sizeDp"), r.applied)
        assertEquals("84", Customize.get(spec("orb.sizeDp"), st))
    }

    /** 导入的值也过 norm：超长 / 超界的一律夹回去，不许原样写进存储 */
    @Test fun importNormalizesValues() {
        val st = store()
        val r = Customize.import(
            """{"format":"lingzhu-custom","version":1,"items":{"persona.tone":"${"长".repeat(2500)}","orb.sizeDp":"20"}}""", st)
        assertEquals(2, r.applied.size)
        assertEquals(Customize.MAX_PROMPT_CHARS, Customize.get(spec("persona.tone"), st).length)
        assertEquals("40", Customize.get(spec("orb.sizeDp"), st))
    }

    @Test fun importRejectsGarbageWithoutThrowing() {
        val st = store()
        Customize.set(spec("persona.name"), st, "我还在")
        for (bad in listOf(null, "", "   ", "不是 json", "[1,2,3]", """{"format":"别人的配置"}""")) {
            val r = Customize.import(bad, st)
            assertTrue("「$bad」不该导入任何项", r.applied.isEmpty())
            assertEquals("我的配置不该被动", "我还在", Customize.get(spec("persona.name"), st))
        }
    }

    /** 对方版本比本机新：拒收并说清要升级，别半懂不懂地导进去 */
    @Test fun importRejectsFutureVersion() {
        val r = Customize.import("""{"format":"lingzhu-custom","version":${Customize.FORMAT_VERSION + 1},"items":{}}""", store())
        assertTrue(r.applied.isEmpty())
        assertTrue("要说清是版本问题：${r.skipped}", r.skipped[0].contains("升级"))
    }

    /** items 整个缺掉（旧格式 / 手删了）→ 当成「对方什么都没改」，不是错误 */
    @Test fun importWithoutItemsIsNoop() {
        val r = Customize.import("""{"format":"lingzhu-custom","version":1}""", store())
        assertTrue(r.applied.isEmpty()); assertTrue(r.skipped.isEmpty())
    }

    /** 超大配置（提示词段写满）→ 导出时明确报错，不静默截断出半份配置 */
    @Test fun exportRefusesOversizedConfig() {
        val st = store()
        Customize.set(spec("persona.tone"), st, "长".repeat(Customize.MAX_PROMPT_CHARS))
        Customize.set(spec("mode.customPrompt"), st, "长".repeat(Customize.MAX_PROMPT_CHARS))
        try {
            Customize.export(st)
            assertTrue("超过 4KB 应该导出失败", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("太大"))
        }
    }

    /** 4KB 上限之内、几十项文本的真实规模不会误触发 */
    @Test fun exportStaysUnderBlobLimitForRealisticConfig() {
        val st = store()
        Customize.set(spec("persona.name"), st, "同学")
        Customize.set(spec("persona.tone"), st, "少用比喻")
        Customize.set(spec("persona.catch"), st, "emm")
        Customize.set(spec("ask.phrases"), st, "讲简单点\n为什么")
        val text = Customize.export(st)
        assertTrue("实际配置不该超上限：${text.toByteArray().size}", text.toByteArray(Charsets.UTF_8).size <= Customize.MAX_BLOB_BYTES)
    }

    // ---------------- 汇总页用到的纯函数 ----------------

    @Test fun groupedKeepsCatalogOrder() {
        val g = Customize.grouped()
        assertEquals(Customize.CATALOG.size, g.sumOf { it.second.size })
        assertEquals(Customize.CATALOG.map { it.group }.distinct(), g.map { it.first })
        // 组内顺序 = 声明顺序（自定义中心的清单顺序稳定，用户不会看到条目乱跳）
        assertEquals(listOf("persona.name", "persona.tone", "persona.catch"), g.first { it.first == Customize.G_PERSONA }.second.map { it.id })
    }

    @Test fun searchMatchesNameGroupHintIdAndOptionLabel() {
        assertEquals(Customize.CATALOG.size, Customize.search("").size)
        assertEquals(Customize.CATALOG.size, Customize.search("   ").size)
        assertTrue(Customize.search("口头禅").any { it.id == "persona.catch" })      // 名字
        assertTrue(Customize.search("小球").any { it.group == Customize.G_ORB })      // 分组
        assertTrue(Customize.search("半秒").any { it.id == "orb.holdMs" })            // 说明（hint）
        assertTrue(Customize.search("idleScale").any { it.id == "orb.idleScale" })    // id
        assertTrue(Customize.search("让开返回手势").any { it.id == "orb.edge" })       // 选项中文名
        assertTrue("大小写不敏感", Customize.search("ORB.EDGE").any { it.id == "orb.edge" })
        assertTrue("搜不到东西该给空清单", Customize.search("量子纠缠").isEmpty())
    }

    @Test fun displaySpeaksHuman() {
        assertEquals("简略（短、只讲关键）", spec("mode.detailDefault").display(Customize.DETAIL_BRIEF))
        assertEquals("左边", spec("panel.pos").display(PanelLayout.LEFT))
        // 没配中文名的值原样显示（不编一个假名）
        val raw = Customize.Spec("x", "g", "x", Customize.Kind.ENUM, "a", options = listOf("a", "b"))
        assertEquals("b", raw.display("b"))
        val b = Customize.Spec("b", "g", "b", Customize.Kind.BOOL, "false")
        assertEquals("开", b.display("true")); assertEquals("关", b.display("false"))
    }

    /** isDefault：自定义中心给改过的项打「默认」标记用；脏值不算改过 */
    @Test fun isDefaultTracksRealValue() {
        val st = store()
        val s = spec("orb.edge")
        assertTrue(s.isDefault(Customize.get(s, st)))
        Customize.set(s, st, Customize.EDGE_FAR)
        assertFalse(s.isDefault(Customize.get(s, st)))
        Customize.restore(s, st)
        assertTrue(s.isDefault(Customize.get(s, st)))
    }

    // ---------------- 人格拼接 ----------------

    /** 三项都空 → 一个字都不拼：零配置的老用户提示词必须一模一样 */
    @Test fun personaSegmentEmptyWhenNothingSet() {
        assertEquals("", Customize.personaSegment("", "", ""))
        assertEquals("", Customize.personaSegment("   ", "\n", "  "))
    }

    @Test fun personaSegmentCarriesUserWords() {
        val seg = Customize.personaSegment("同学", "别用比喻", "emm")
        assertTrue(seg.contains("称呼用户为「同学」。"))
        assertTrue(seg.contains("别用比喻"))
        assertTrue(seg.contains("口头禅是「emm」"))
    }

    /** 防呆的核心：格式标记行必须留着 —— 砍掉面板就崩了（词块拆不出来、公式排不了） */
    @Test fun personaSegmentProtectsFormatMarkers() {
        val seg = Customize.personaSegment("", "少废话", "")
        assertTrue("要声明风格覆盖默认：$seg", seg.contains("覆盖上面这些默认设定"))
        assertTrue("要保住标题行：$seg", seg.contains("##"))
        assertTrue("要保住关键词清单行：$seg", seg.contains("关键词清单行"))
        assertTrue("要保住 LaTeX：$seg", seg.contains("LaTeX"))
    }

    /** 三项分别单填时各自那句都要在；口头禅那句要写明「偶尔」而不是每句都甩 */
    @Test fun personaSegmentEachSlotIndependent() {
        assertTrue(Customize.personaSegment("小春", "", "").contains("称呼用户为「小春」。"))
        val seg = Customize.personaSegment("", "", "emm")
        assertTrue(seg.contains("偶尔自然带出来"))
        assertFalse(seg.contains("称呼用户为"))
    }

    /** 拼接只做 trim，不截断 —— 长度防呆在写入时就做完了，这里不重复切 */
    @Test fun personaSegmentDoesNotClip() {
        val long = "词".repeat(Customize.MAX_PROMPT_CHARS)
        assertTrue(Customize.personaSegment("", long, "").contains(long))
    }

    // ---------------- 辅助 ----------------

    /** 给每一项造一个「肯定不等于默认值」的脏值，用来验证恢复默认 */
    private fun dirtyValueFor(s: Customize.Spec): String = when (s.kind) {
        Customize.Kind.TEXT -> "脏值"
        Customize.Kind.PROMPT -> "脏提示词"
        Customize.Kind.NUMBER -> (s.max + 1).toString()      // 超上限 → norm 夹到 max，仍 ≠ 默认值
        Customize.Kind.ENUM -> s.options.filter { it != s.def }.lastOrNull()
            ?: error("${s.id} 没有第二个选项，测不出恢复默认")
        Customize.Kind.BOOL -> "true"
        Customize.Kind.COLOR -> "#ABCDEF"
    }

    @Test fun dirtyValueHelperActuallyDiffersFromDefault() {
        for (s in Customize.CATALOG) {
            val d = dirtyValueFor(s)
            assertNotNull(s.id, d)
            assertFalse("${s.id} 的脏值和默认值一样，测不出东西", Customize.norm(s, d) == s.def)
        }
    }
}