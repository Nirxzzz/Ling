package com.chun.lingzhu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 百度翻译开放平台（v1.58）：POST /ait/api/aiTextTranslate，一次性返回（不流式）。
 * 鉴权二选一：填了 API Key 就用 Bearer（此时不能带 salt/sign，否则 53001）；
 * 否则用 APP ID + 密钥签名（sign = MD5(appid+q+salt+密钥)，salt 必须是 JSON 数字）。
 * 凭证只来自用户在设置页填的值，不打进安装包。
 */
class BaiduTranslate(
    private val appId: String,
    private val apiKey: String,
    private val secret: String,
    private val model: String = "llm",
    private val endpoint: String = ENDPOINT,
) {
    companion object {
        const val ENDPOINT = "https://fanyi-api.baidu.com/ait/api/aiTextTranslate"
        val MODELS = listOf("llm", "nmt")
        val MODEL_LABEL = mapOf("llm" to "大模型翻译", "nmt" to "机器翻译")

        /** 百度错误码 → (大白话, 值不值得自动重试一次) */
        private val ERRORS = mapOf(
            "52001" to ("百度超时，稍后再试" to true),
            "52002" to ("百度系统错误，稍后再试" to true),
            "52003" to ("未授权：APP ID 或 API Key 不对，或者还没开通这个服务" to false),
            "54000" to ("必填参数为空（APP ID 没填，或 API Key 不对）" to false),
            "54001" to ("签名错误：检查 APP ID 和密钥" to false),
            "54003" to ("请求太频繁（标准版每秒只能 1 次左右）" to true),
            "54004" to ("百度账户余额不足 / 免费额度用完" to false),
            "54005" to ("长文本请求太频繁，等 3 秒再试" to true),
            "58000" to ("IP 不在百度控制台设置的白名单里" to false),
            "58001" to ("不支持这个翻译方向" to false),
            "58002" to ("这个服务在百度控制台被关掉了" to false),
            "90107" to ("认证还没生效，稍后再试" to false),
        )

        /** Ling 的语言 id → 百度语种代码 */
        private val CODE = mapOf("zh" to "zh", "en" to "en", "ja" to "jp", "ko" to "kor", "ru" to "ru", "fr" to "fra", "de" to "de")

        /** 目标语种：「自动」= 中英互译，原文里汉字多就译成英文，否则译成中文 */
        fun target(langId: String, text: String): String {
            CODE[langId]?.let { return it }
            val letters = text.count { it.isLetter() }
            val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            return if (letters > 0 && han * 2 >= letters) "en" else "zh"
        }

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** 返回体 → 译文；出错抛 ApiException（带百度错误码） */
        fun parse(body: String): String {
            val o = try { JSONObject(body) } catch (e: Exception) { throw ApiException("百度返回的不是 JSON：" + body.take(120)) }
            val code = o.optString("error_code")
            if (code.isNotEmpty() && code != "0" && code != "52000") {
                val msg = ERRORS[code]?.first ?: o.optString("error_msg", "未知错误")
                throw BaiduException(code, "百度翻译出错（$code）：$msg", ERRORS[code]?.second ?: false)
            }
            val arr = o.optJSONArray("trans_result") ?: throw ApiException("百度没返回译文：" + body.take(120))
            return (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).optString("dst") }
        }
    }

    class BaiduException(val code: String, message: String, retryable: Boolean) : ApiException(message, retryable)

    val configured: Boolean get() = apiKey.isNotBlank() || (appId.isNotBlank() && secret.isNotBlank())

    /** 请求体：Bearer 模式不带 salt/sign；签名模式 salt 用数字 */
    fun body(text: String, to: String, salt: Long = Random.nextLong(1_000_000_000L, 9_999_999_999L)): String {
        val o = JSONObject().put("q", text).put("from", "auto").put("to", to).put("model_type", model)
        if (apiKey.isNotBlank()) { if (appId.isNotBlank()) o.put("appid", appId) }
        else o.put("appid", appId).put("salt", salt).put("sign", md5(appId + text + salt + secret))
        return o.toString()
    }

    fun translate(text: String, langId: String, cancel: AtomicBoolean = AtomicBoolean(false)): String {
        if (!configured) throw ApiException("还没填百度翻译的 API Key（或 APP ID + 密钥）：打开 " + Preset.appName + " → 翻译引擎")
        val to = target(langId, text)
        var last: BaiduException? = null
        repeat(2) { attempt ->
            if (cancel.get()) return ""
            if (attempt > 0) Thread.sleep(1100)
            try { return post(body(text, to)) } catch (e: BaiduException) { if (!e.retryable) throw e; last = e }
        }
        throw last!!
    }

    private fun post(json: String): String {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + apiKey)
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299 && !text.contains("error_code")) throw ApiException("百度翻译 HTTP $code " + text.take(160))
            return parse(text)
        } finally { c.disconnect() }
    }
}
