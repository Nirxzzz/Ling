package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模式（v1.58）：通用、做题两个内置（v1.65 加过「通识」，v1.66 删了） + 任意多个自定义模式（各有名字、提示词、面板不透明度）。
 * 模式 id：内置就是 "general" / "exam"；自定义是 "custom:<id>"。
 * 存在 prefs 的 customModes（JSON 数组）；v1.54~1.57 只有一个「自定义」+ customPrompt，第一次读时迁移成 c1。
 * 纯 Kotlin + org.json，方便单元测试。
 */
object Modes {
    const val PREFIX = "custom:"
    /** 面板不透明度可调范围（%）；0 = 跟随配色 */
    const val OPACITY_MIN = 30
    const val OPACITY_MAX = 100
    val BUILTIN = listOf(Prompts.MODE_GENERAL, Prompts.MODE_EXAM)
    /** v1.65 的「通识」模式 id；v1.66 删了（名词块并进所有模式），存着它的人迁移到通用 */
    const val LEGACY_LEARN = "learn"

    data class Custom(val id: String, val name: String, val prompt: String, val opacity: Int = 0)

    fun parse(json: String?): List<Custom> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Custom(id, o.optString("name").ifBlank { "自定义" }, o.optString("prompt"), normOpacity(o.optInt("opacity", 0)))
            }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Custom>): String = JSONArray().apply {
        for (c in list) put(JSONObject().put("id", c.id).put("name", c.name).put("prompt", c.prompt).put("opacity", c.opacity))
    }.toString()

    fun normOpacity(v: Int): Int = if (v <= 0) 0 else v.coerceIn(OPACITY_MIN, OPACITY_MAX)

    fun newId(list: List<Custom>): String {
        var n = list.size + 1
        while (list.any { it.id == "c$n" }) n++
        return "c$n"
    }

    /** 新建时的默认名字：自定义 2、自定义 3…（不和已有的重名） */
    fun newName(list: List<Custom>): String {
        if (list.none { it.name == "自定义" }) return "自定义"
        var n = 2
        while (list.any { it.name == "自定义 $n" }) n++
        return "自定义 $n"
    }

    fun customId(mode: String): String? = if (mode.startsWith(PREFIX)) mode.removePrefix(PREFIX) else null

    /** 存的模式 id → 合法的模式 id（旧的 "custom" 指向第一个自定义；自定义被删了、或是 v1.66 删掉的通识，都回到通用） */
    fun normalize(raw: String?, list: List<Custom>): String = when {
        raw == LEGACY_LEARN -> Prompts.MODE_GENERAL
        raw == Prompts.MODE_CUSTOM -> list.firstOrNull()?.let { PREFIX + it.id } ?: Prompts.MODE_GENERAL
        raw != null && raw in BUILTIN -> raw
        raw != null && customId(raw)?.let { id -> list.any { it.id == id } } == true -> raw
        else -> Prompts.MODE_GENERAL
    }

    fun all(list: List<Custom>): List<String> = BUILTIN + list.map { PREFIX + it.id }

    fun label(mode: String, list: List<Custom>): String =
        Prompts.MODE_LABEL[mode] ?: customId(mode)?.let { id -> list.firstOrNull { it.id == id }?.name } ?: Prompts.MODE_LABEL.getValue(Prompts.MODE_GENERAL)
}
