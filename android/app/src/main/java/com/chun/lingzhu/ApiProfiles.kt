package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.65 接口方案（纯 JVM，可单测）：用户自己填的接口可以存成多套（主力 / 备用 / 便宜模型），
 * 设置页一点就整组切换。只管存取和起名；「切换」= 把方案值拷进 Prefs 的激活字段
 * （baseUrl / apiKey / model），下游 api() 及所有请求路径零改动。
 * 这里只管理用户自己填的接口方案；开源副本没有内置接口或 presetId。
 */
object ApiProfiles {
    data class Profile(val id: String, val label: String, val baseUrl: String, val apiKey: String = "", val model: String = "")

    fun parse(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Profile(id, o.optString("label").ifBlank { "方案" }, o.optString("baseUrl"),
                    o.optString("apiKey"), o.optString("model"))
            }.filter { it.baseUrl.isNotBlank() }.distinctBy { it.id }
        } catch (_: Exception) { emptyList() }
    }

    fun toJson(list: List<Profile>): String = JSONArray().apply {
        for (p in list) put(JSONObject().put("id", p.id).put("label", p.label).put("baseUrl", p.baseUrl)
            .put("apiKey", p.apiKey).put("model", p.model))
    }.toString()

    fun newId(list: List<Profile>): String {
        var n = 1
        while (list.any { it.id == "a$n" }) n++
        return "a$n"
    }

    /** 新方案默认名：接口方案 2、接口方案 3… */
    fun newName(list: List<Profile>): String {
        var n = 1
        while (list.any { it.label == "接口方案 $n" }) n++
        return if (n == 1) "接口方案 1" else "接口方案 $n"
    }

    /** 把方案值写回激活字段（prefs 的 baseUrl/apiKey/model）；找不到 id 就不动 */
    fun apply(p: Profile, setBase: (String) -> Unit, setKey: (String) -> Unit, setModel: (String) -> Unit) {
        setBase(p.baseUrl); setKey(p.apiKey); setModel(p.model)
    }
}
