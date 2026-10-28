package com.chun.lingzhu.voice

import android.content.Context
import org.json.JSONObject

/**
 * 语音对话（腾讯实时语音识别 + 现有模型 + 腾讯语音合成）的本机设置。
 *
 * 三件事分得很开：
 *  1. **凭据**（AppId / SecretId / SecretKey）是密文：[saveCredentials] 把三个字段拼成一份 JSON，
 *     **整份**交给 [VoiceKeyStore] 的 `tencent` 槽位（AndroidKeyStore AES-256-GCM）。
 *     SharedPreferences 里只有密文，`toString` 里永远只有「set / none」—— 明文不进日志、不进界面回显。
 *     槽位独立于转写那两家：删豆包的 Key 不会碰到腾讯这条，反过来也一样。
 *  2. **非密参数**（音色 / 语速 / 音量 / 同意）走普通 SharedPreferences，和转写那套是同一个文件、不同的键。
 *  3. **参数边界只在这里判**：语速是腾讯文档的 `[-2, 6]`（**不是倍率**），音色是数字 id，
 *     音量是我们自己在本机播放时的增益 `[0, 1]` —— **腾讯合成的音量参数不在这一版里**，
 *     所以界面文案必须写清「音量只影响本机播放」，不能让人以为改的是云端合成音量。
 */
class LiveTalkSettings(ctx: Context) {

    private val app: Context = ctx.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keys = VoiceKeyStore(app)

    // ---------------- 凭据（密文） ----------------

    /** 三个字段存过没有（真解一次密：存了但解不开不算有，见 [VoiceKeyStore.isSaved]）。 */
    fun hasCredentials(): Boolean = keys.isSaved(VoiceProvider.TENCENT)

    /**
     * 读回可用的凭据。没有 / 解不开 / 字段不全一律 null —— 绝不返回一份「半个字段」的凭据去连。
     */
    internal fun credentials(): TencentCredentials? {
        val raw = keys.load(VoiceProvider.TENCENT) ?: return null
        val parsed = runCatching { TencentCredentials.parse(raw) }.getOrNull() ?: return null
        return if (parsed.appId.isBlank() || parsed.secretId.isBlank() || parsed.secretKey.isBlank()) null else parsed
    }

    /**
     * 保存（覆盖）凭据。三个字段里**留空 = 沿用已存的**由界面先合好再调这里，
     * 所以到这里必须是三个都齐；空值直接拒绝，不写一份残缺凭据进去。
     */
    fun saveCredentials(appId: String, secretId: String, secretKey: String) {
        val a = appId.trim()
        val i = secretId.trim()
        val k = secretKey.trim()
        require(a.isNotEmpty() && i.isNotEmpty() && k.isNotEmpty()) { "AppId / SecretId / SecretKey 三项都要有值" }
        val json = JSONObject().put("appId", a).put("secretId", i).put("secretKey", k).toString()
        keys.save(VoiceProvider.TENCENT, json)
    }

    fun clearCredentials() = keys.clear(VoiceProvider.TENCENT)

    // ---------------- 非密参数 ----------------

    /** 腾讯语音合成音色 id（数字）。存的就是要发出去的那个数，不做「名字 → id」的猜测。 */
    var voiceType: Int
        get() = prefs.getInt(KEY_VOICE_TYPE, DEFAULT_VOICE_TYPE)
        set(v) {
            require(TencentVoice.validVoiceType(v)) { "音色 id 不在允许范围：$v" }
            prefs.edit().putInt(KEY_VOICE_TYPE, v).apply()
        }

    /**
     * 腾讯文档的**语速参数** `[-2, 6]`，0 = 正常。
     * 不是「1.0 倍速」那种倍率：2 是「快一些」，6 是上限；按 0.5 一档存。
     */
    var speed: Double
        get() = prefs.getFloat(KEY_SPEED, 0f).toDouble().coerceIn(TencentVoice.SPEED_MIN, TencentVoice.SPEED_MAX)
        set(v) {
            require(v in TencentVoice.SPEED_MIN..TencentVoice.SPEED_MAX) { "语速参数超出 [-2, 6]：$v" }
            prefs.edit().putFloat(KEY_SPEED, v.toFloat()).apply()
        }

    /** 本机播放增益 `[0, 1]`（0 = 静音）。只作用于我们自己的 AudioTrack，不发给腾讯。 */
    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)
        set(v) {
            require(v in 0f..1f) { "音量必须在 [0, 1]：$v" }
            prefs.edit().putFloat(KEY_VOLUME, v).apply()
        }

    /**
     * 语音对话的上传同意。**单独一条**，与转写那套「服务商 × 来源」的同意不共用：
     * 对话要同时把麦克风声音发给腾讯、把你说的话发给现有大模型 —— 这是一份更宽的授权，
     * 不能因为「转写同意过豆包」就默认成立。
     */
    var consent: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSENT, v).apply()

    companion object {
        private const val PREFS = "ling_voice"
        private const val KEY_VOICE_TYPE = "live.voiceType"
        private const val KEY_SPEED = "live.speed"
        private const val KEY_VOLUME = "live.volume"
        private const val KEY_CONSENT = "live.consent"

        /**
         * 默认音色：腾讯语音合成文档示例里的 101001（亲和女声）。
         * 用文档出现过的 id 而不是自己挑一个「好听」的：别的 id 属于不同套餐 / 版本，
         * 写死一个就等于替用户选了一份他可能没买的服务。用户可以在界面上改成自己的 id。
         */
        const val DEFAULT_VOICE_TYPE = 101001
    }
}

/**
 * 腾讯语音合成的参数边界（纯函数，单测直接对）。
 *
 * 只做「这个值能不能发给腾讯」的判断，不猜哪个人声叫什么名字：腾讯的音色表随版本变，
 * 把一张人名 → id 的表写死在客户端，过一阵就会发一个已经不存在的 id 出去。
 */
object TencentVoice {
    /** 腾讯文档的语速参数范围（**不是倍率**） */
    const val SPEED_MIN = -2.0
    const val SPEED_MAX = 6.0

    /**
     * 音色 id 的上界：腾讯现在给的都是六位正整数，留够余量也只是挡住乱填。
     * 下界是 1 —— 协议层（[TencentSpeech.synthesize]）明确要求**正整数**，0 会被服务端拒。
     */
    const val VOICE_TYPE_MAX = 9_999_999

    fun validSpeed(v: Double): Boolean = v >= SPEED_MIN && v <= SPEED_MAX

    fun validVoiceType(v: Int): Boolean = v in 1..VOICE_TYPE_MAX

    /** 界面输入的解析：**只认十进制数字**，空串 / 0 / 负数 / 小数 / 带单位一律 null（不替用户猜）。 */
    fun parseVoiceType(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 8) return null
        if (s.any { it !in '0'..'9' }) return null
        val v = s.toIntOrNull() ?: return null
        return if (validVoiceType(v)) v else null
    }

    /** 语速的人话标签：参数值原样写出来，再补一句方向，避免被当成倍率。 */
    fun speedLabel(v: Double): String {
        val sign = if (v > 0) "+" else ""
        val shown = if (v == Math.floor(v)) v.toInt().toString() else String.format("%.1f", v)
        val tail = when {
            v == 0.0 -> "（正常）"
            v > 0 -> "（更快）"
            else -> "（更慢）"
        }
        return "语速 $sign$shown$tail"
    }

    fun volumeLabel(v: Float): String = Math.round(v * 100f).toString() + "%"
}
