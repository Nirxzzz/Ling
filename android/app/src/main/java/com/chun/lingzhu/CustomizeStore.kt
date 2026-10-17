package com.chun.lingzhu

/**
 * v1.69 自定义项的落盘层：把 [Customize.Spec] 的 id 映射到 [Prefs] 里真实的字段。
 *
 * 为什么要有这一层：清单（[Customize.CATALOG]）只描述「这一项是什么」，不描述「存哪儿」。
 * 好处是 orb.sizeDp 这一项直接住在 Prefs.orbSizeDp 上 —— 和设置页里那个滑块**共用同一个存储**，
 * 两边改的是同一个值，不会出现「设置页改了、自定义中心显示的是另一个值」这种双份真相。
 * 剩下的项（人格三项、模板…）没有对应具名字段，就按 `cus_<specId>` 存在 prefs 里。
 *
 * 读一律走 [Customize.norm]（这里再兜一层），写交给 [Customize.set] ——
 * 所以从任何入口改一个自定义项，截断 / 夹区间 / 枚举回落这些规则都只执行一次、在同一处。
 */
class CustomizeStore(private val prefs: Prefs) : Customize.Store {

    override fun get(id: String): String? = when (id) {
        // ---- 小球：映射到已有字段，和设置页共用 ----
        "orb.sizeDp" -> prefs.orbSizeDp.toString()
        "orb.edge" -> edgeFromFlags()
        "orb.idleScale" -> prefs.orbIdleScale.toString()
        "orb.holdMs" -> prefs.orbHoldMs.toString()
        // ---- 面板：映射到已有字段 ----
        "panel.pos" -> prefs.panelPos
        "panel.textSp" -> prefs.textSp.toString()
        // ---- 模式：映射到已有字段 ----
        "mode.detailDefault" -> prefs.detail
        "mode.customPrompt" -> prefs.customPromptTemplate
        // ---- 追问：快捷短语是列表，拼成换行分隔的一行文本（导出时也是一行，好分享）----
        "ask.phrases" -> prefs.askPhrases.joinToString("\n")
        // ---- 人格三项：各自的具名字段（读的时候自己截断，和写用同一条规则）----
        "persona.name" -> prefs.personaName
        "persona.tone" -> prefs.personaTone
        "persona.catch" -> prefs.personaCatch
        else -> null
    }

    override fun put(id: String, value: String) {
        val s = Customize.byId(id) ?: return
        val v = Customize.norm(s, value)   // 先归一化再写，别让调用方各写一遍夹区间
        when (id) {
            "orb.sizeDp" -> prefs.orbSizeDp = v.toInt()
            "orb.edge" -> applyEdge(v)
            "orb.idleScale" -> prefs.orbIdleScale = v.toInt()
            "orb.holdMs" -> prefs.orbHoldMs = v.toInt()
            "panel.pos" -> prefs.panelPos = v
            "panel.textSp" -> prefs.textSp = v.toInt()
            "mode.detailDefault" -> prefs.detail = v
            "mode.customPrompt" -> prefs.customPromptTemplate = v
            "ask.phrases" -> prefs.askPhrases = phrasesFrom(v)
            "persona.name" -> prefs.personaName = v
            "persona.tone" -> prefs.personaTone = v
            "persona.catch" -> prefs.personaCatch = v
        }
    }

    override fun remove(id: String) {
        // 恢复默认 = 把这一项的**真实字段**恢复成 Prefs 里的默认值，不是删键。
        // 具名字段删掉以后 getter 自然回落到默认值，行为和「从来没改过」完全一致。
        when (id) {
            "orb.sizeDp" -> prefs.orbSizeDp = 58
            "orb.edge" -> applyEdge(Customize.EDGE_HUG)
            "orb.idleScale" -> prefs.orbIdleScale = 60
            "orb.holdMs" -> prefs.orbHoldMs = 500
            "panel.pos" -> prefs.panelPos = PanelLayout.DEFAULT
            "panel.textSp" -> prefs.textSp = 15
            "mode.detailDefault" -> prefs.detail = defaultDetail()
            "mode.customPrompt" -> prefs.customPromptTemplate = ""
            "ask.phrases" -> prefs.askPhrases = emptyList()
            "persona.name" -> prefs.personaName = ""
            "persona.tone" -> prefs.personaTone = ""
            "persona.catch" -> prefs.personaCatch = ""
        }
    }

    /**
     * 解析强度默认档的默认值：开源副本统一「标准」（见 Prefs.detail）。
     * 这里必须复刻一遍 —— [Customize.CATALOG] 里的 def 是纯静态的（不能引用 Prefs，那要 Context），
     * 而恢复默认要回到「这一版本来是什么样」，不能一刀切回标准。
     */
    private fun defaultDetail(): String = "standard"

    /** 换行分隔 → 快捷短语列表（归一化在 Freeform 里，纯函数） */
    private fun phrasesFrom(v: String): List<String> =
        Freeform.normPhrases(v.split('\n').map { it.trim() }.filter { it.isNotEmpty() })

    /**
     * 贴边行为 ⇄ 两个布尔开关的双向映射。
     * 默认（hug）= 贴着边 + 闲置缩进去一半，也就是 orbFar=false / orbTuck=true，和 v1.66 以前一致。
     * 另两个值正好对应另外两种常见组合，没有第四种组合 —— 「离远 + 缩进去」是自相矛盾的（离远了没什么可缩）。
     */
    private fun edgeFromFlags(): String = when {
        prefs.orbFar -> Customize.EDGE_FAR
        prefs.orbTuck -> Customize.EDGE_HUG
        else -> Customize.EDGE_NEAR
    }

    private fun applyEdge(v: String) {
        when (v) {
            Customize.EDGE_FAR -> { prefs.orbFar = true; prefs.orbTuck = false }
            Customize.EDGE_NEAR -> { prefs.orbFar = false; prefs.orbTuck = false }
            else -> { prefs.orbFar = false; prefs.orbTuck = true }
        }
    }
}
