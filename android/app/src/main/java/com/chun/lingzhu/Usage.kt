package com.chun.lingzhu

import org.json.JSONObject

/**
 * v1.65 用量核算（纯 JVM，可单测）：识别 / 追问 / 翻译每次请求花掉多少，按天累计。
 * token 数优先取接口流里真实上报的 usage（OpenAI 兼容的都带；截图会折进 prompt_tokens，
 * 所以这个数就是真实成本）；接口不给时按中文字符数估算兜底（中文 ≈ 0.62 token/字，混排略偏高——宁高勿低）。
 * 存储格式：{"20261005":{"req":3,"pin":4200,"pout":1800,"ch":2600}, …}，保留最近 30 天。
 */
object Usage {
    const val KEEP_DAYS = 30
    /** 中文为主的估算系数（DeepSeek/Qwen 实测 0.58~0.66） */
    const val ZH_TOKEN_PER_CHAR = 0.62f

    /** 无真实 usage 时按回答字数估 */
    fun estimate(chars: Int): Int = Math.round(chars * ZH_TOKEN_PER_CHAR)

    data class Day(var req: Int = 0, var pin: Int = 0, var pout: Int = 0, var ch: Int = 0)

    fun parse(json: String?): MutableMap<String, Day> {
        val m = LinkedHashMap<String, Day>()
        if (json.isNullOrBlank()) return m
        try {
            val o = JSONObject(json)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val d = o.optJSONObject(k) ?: continue
                m[k] = Day(d.optInt("req"), d.optInt("pin"), d.optInt("pout"), d.optInt("ch"))
            }
        } catch (_: Exception) {}
        return m
    }

    fun toJson(m: Map<String, Day>): String = JSONObject().apply {
        for ((k, d) in m) put(k, JSONObject().put("req", d.req).put("pin", d.pin).put("pout", d.pout).put("ch", d.ch))
    }.toString()

    /** 记一笔（date 形如 20261005，由调用方传入便于单测）；自动裁掉 KEEP_DAYS 天之前 */
    fun add(m: MutableMap<String, Day>, date: String, pin: Int, pout: Int, chars: Int, req: Int = 1): MutableMap<String, Day> {
        val d = m[date] ?: Day().also { m[date] = it }
        d.req += req; d.pin += pin; d.pout += pout; d.ch += chars
        while (m.size > KEEP_DAYS) m.remove(m.keys.minByOrNull { it }!!)
        return m
    }

    fun dayTotal(m: Map<String, Day>, date: String): Day = m[date] ?: Day()

    fun total(m: Map<String, Day>): Day {
        val t = Day()
        for (d in m.values) { t.req += d.req; t.pin += d.pin; t.pout += d.pout; t.ch += d.ch }
        return t
    }

    /** 给人看的：1.2k / 38.5k 这样截短（锁 Locale：小数点恒为点，不随手机语言变逗号） */
    fun fmt(n: Int): String = when {
        n >= 100_000 -> String.format(java.util.Locale.US, "%.1f万", n / 10000.0)
        n >= 1000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
        else -> n.toString()
    }

    fun line(label: String, d: Day): String =
        "$label：${d.req} 次 · ↑${fmt(d.pin)} ↓${fmt(d.pout)} tokens（约 ${fmt(d.ch)} 字回答）"
}
