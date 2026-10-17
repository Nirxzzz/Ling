package com.chun.lingzhu

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DeepL（v1.61，对齐电脑版 Ling v1.59）：POST /v2/translate，一次性返回。
 * Key 以 ":fx" 结尾是免费版（api-free.deepl.com），否则是 Pro（api.deepl.com）。只给译文，没有词性 / 要点。
 * 本开源副本不内置任何 Key；用户在设置页填自己的 DeepL API Key 后才可用。
 */
class DeepL(private val key: String, private val endpoint: String? = null) {
    companion object {
        private val CODE = mapOf("zh" to "ZH-HANS", "en" to "EN-US", "ja" to "JA", "ko" to "KO", "ru" to "RU", "fr" to "FR", "de" to "DE")
        /** HTTP 状态 → (大白话, 值不值得自动重试一次) */
        private val ERRORS = mapOf(
            400 to ("请求有误（多半是语言不支持）" to false), 403 to ("DeepL Key 不对或已失效" to false),
            404 to ("DeepL 接口地址不对" to false), 413 to ("一次翻的字太多" to false), 414 to ("一次翻的字太多" to false),
            429 to ("请求太频繁" to true), 456 to ("DeepL 本月字符额度用完了" to false),
            500 to ("DeepL 服务器出错" to true), 502 to ("DeepL 服务器出错" to true), 503 to ("DeepL 暂时不可用" to true),
            504 to ("DeepL 超时" to true), 529 to ("请求太频繁" to true),
        )
        fun host(key: String): String = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        /** 目标语种：「自动」和百度一样中英互译 */
        fun target(langId: String, text: String): String = CODE[langId] ?: if (BaiduTranslate.target("auto", text) == "en") "EN-US" else "ZH-HANS"
        fun body(text: String, target: String): String = JSONObject().put("text", JSONArray().put(text)).put("target_lang", target).toString()
        /** 返回 → 译文；出错抛 DeepLException */
        fun parse(status: Int, body: String): String {
            if (status != 200) {
                val e = ERRORS[status]
                throw DeepLException(status, "DeepL 出错（$status）：" + (e?.first ?: body.take(120)), e?.second ?: (status >= 500))
            }
            val o = try { JSONObject(body) } catch (_: Exception) { throw ApiException("DeepL 返回的不是 JSON：" + body.take(120)) }
            val arr = o.optJSONArray("translations")
            if (arr == null || arr.length() == 0) throw ApiException("DeepL 没返回译文")
            return (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).optString("text") }
        }
    }

    class DeepLException(val status: Int, message: String, retryable: Boolean) : ApiException(message, retryable)

    val configured: Boolean get() = key.isNotBlank()

    fun translate(text: String, langId: String, cancel: AtomicBoolean = AtomicBoolean(false)): String {
        if (!configured) throw ApiException("还没有 DeepL Key")
        val json = body(text, target(langId, text))
        var last: Exception? = null
        repeat(2) { attempt ->
            if (cancel.get()) return ""
            if (attempt > 0) Thread.sleep(1200)
            try { return parse(200, postRaw(json)) }
            catch (e: DeepLException) { if (!e.retryable) throw e; last = e }
            catch (e: java.io.IOException) { last = ApiException("连不上 DeepL（" + (e.message ?: e.javaClass.simpleName) + "）") }
        }
        throw last!!
    }

    /** 批量（v1.67 背单词预翻译用）：一次请求带多段文本，按顺序返回译文；失败抛 DeepLException */
    fun translateAll(texts: List<String>, langId: String, cancel: AtomicBoolean = AtomicBoolean(false)): List<String> {
        if (!configured) throw ApiException("还没有 DeepL Key")
        if (texts.isEmpty()) return emptyList()
        val target = target(langId, texts.first())
        val json = JSONObject().put("text", JSONArray().apply { texts.forEach { put(it) } }).put("target_lang", target).toString()
        var last: Exception? = null
        repeat(2) { attempt ->
            if (cancel.get()) return emptyList()
            if (attempt > 0) Thread.sleep(1200)
            try { return parseList(200, postRaw(json)) }
            catch (e: DeepLException) { if (!e.retryable) throw e; last = e }
            catch (e: java.io.IOException) { last = ApiException("连不上 DeepL（" + (e.message ?: e.javaClass.simpleName) + "）") }
        }
        throw last!!
    }

    /** /v2/translate 的应答 → 译文列表（和请求文本一一对应）；非 200 交给 parse 抛统一的错 */
    fun parseList(status: Int, body: String): List<String> {
        if (status != 200) parse(status, body)
        val o = try { JSONObject(body) } catch (_: Exception) { throw ApiException("DeepL 返回的不是 JSON：" + body.take(120)) }
        val arr = o.optJSONArray("translations") ?: throw ApiException("DeepL 没返回译文")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("text") }
    }

    private fun postRaw(json: String): String {
        val c = URL((endpoint ?: host(key)) + "/v2/translate").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "DeepL-Auth-Key " + key.trim())
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code != 200) parse(code, body)
            return body
        } finally { c.disconnect() }
    }
}
