package com.chun.lingzhu.voice

import android.content.Context
import android.os.Build

/**
 * 语音转写的两家服务商，以及它们在本机上的**非密**状态（选中的是哪家、隐私同意过没有）。
 *
 * 服务商的接口地址 / 资源 id / 模型名都是固定常量，不暴露给用户改：这一版只做「选一家 + 填那条 Key」，
 * 少一个输入框就少一类「填错地址导致一句话都不出」的故障。协议细节在 [VoiceAsrClient] 那边。
 *
 * 明文 Key **不在这里**：一律走 [VoiceKeyStore]（AndroidKeyStore AES-GCM）。本类只存
 * 「哪家被选中」「隐私提示点过同意 —— 且是哪一家」，SharedPreferences 里能看到的值不含任何密钥材料。
 */
object VoiceProvider {
    const val DOUBAO = "doubao"
    const val ALIBABA = "alibaba"

    /**
     * 腾讯云（实时语音识别 + 语音合成）：**只给「语音对话」页用**，不进转写那一排服务商。
     *
     * 为什么分开放：[ALL] 是「实时转写」页的服务商选择器，它的每一项都必须能被 [VoiceAsrClient] 跑起来。
     * 腾讯这条路要三个凭据（AppId / SecretId / SecretKey），协议也是另一套；塞进 [ALL] 就等于在
     * 转写页放一个按了必然失败的服务商。所以它只登记在 [KEYED]（密钥槽位）里，
     * 转写页看到的选择器永远还是这两家。
     */
    const val TENCENT = "tencent"

    /** 转写页能选的服务商（**不含腾讯**） */
    val ALL: List<String> = listOf(DOUBAO, ALIBABA)

    /** 有独立密钥槽位的服务商：[ALL] + 腾讯。密钥存储按这一份校验，选择器按 [ALL]。 */
    val KEYED: List<String> = ALL + TENCENT

    /** 界面上显示的名字 */
    fun label(provider: String): String = when (provider) {
        DOUBAO -> "豆包（火山引擎）"
        ALIBABA -> "阿里云（Paraformer）"
        TENCENT -> "腾讯云（实时语音识别 / 语音合成）"
        else -> provider
    }

    /** 一句话说明用的是什么接口，写在选择行下面让人知道声音发去哪 */
    fun detail(provider: String): String = when (provider) {
        DOUBAO -> "wss://openspeech.bytedance.com · 双向流式 · 大模型识别"
        ALIBABA -> "wss://dashscope.aliyuncs.com · paraformer-realtime-v2"
        TENCENT -> "wss://asr.cloud.tencent.com（识别）+ wss://tts.cloud.tencent.com（合成）"
        else -> ""
    }

    /** 能不能当转写服务商用（腾讯不行） */
    fun isValid(provider: String): Boolean = provider in ALL

    /** 有没有独立的密钥槽位（腾讯有，但不能当转写服务商用） */
    fun isKeyed(provider: String): Boolean = provider in KEYED
}

/**
 * 声音来源：麦克风 / 系统播放的声音 / 两路混录。
 *
 * 三档最终都是 **16kHz 单声道 s16le** 的 PCM，走同一个 [VoiceAsrClient.sendPcm] 出口 ——
 * 服务商那边看到的永远是一种格式，换来源不需要换协议、不需要换 Key。
 *
 * 后两档要靠「播放捕获」([VoiceSystemAudio])，那是 Android 10（API 29）才有的能力；
 * 低版本**明确不可选**（[available] 为假、[unavailableReason] 给出原因），
 * 绝不悄悄退回麦克风——用户以为在录系统声音、实际录的是房间里的声音，是这一页最不能出的错。
 */
object VoiceSource {
    const val MIC = "mic"
    const val SYSTEM = "system"
    const val MIXED = "mixed"

    val ALL: List<String> = listOf(MIC, SYSTEM, MIXED)

    /** 系统播放捕获的最低 API（Android 10 / Q）。 */
    const val MIN_API = 29

    fun isValid(source: String): Boolean = source in ALL

    /** 需要 MediaProjection 授权的那两档。 */
    fun requiresProjection(source: String): Boolean = source == SYSTEM || source == MIXED

    /** 含麦克风的那两档（决定前台服务要不要 `microphone` 类型、要不要 RECORD_AUDIO）。 */
    fun usesMic(source: String): Boolean = source == MIC || source == MIXED

    fun label(source: String): String = when (source) {
        MIC -> "麦克风"
        SYSTEM -> "系统声音"
        MIXED -> "麦克风 + 系统声音"
        else -> source
    }

    /** 这一档在给定系统版本上能不能用。**显式传 sdk 才能单测**（不在方法体里现读 [Build.VERSION]）。 */
    fun available(source: String, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        isValid(source) && (!requiresProjection(source) || sdk >= MIN_API)

    /** 不可用的原因（可用时为 null）。文案里带当前 API 号，用户/客服能对上机型。 */
    fun unavailableReason(source: String, sdk: Int = Build.VERSION.SDK_INT): String? {
        if (!isValid(source)) return "不认识的声音来源：$source"
        if (available(source, sdk)) return null
        return "系统声音要用 Android 10（API ${MIN_API}）起的播放捕获，这台设备是 API $sdk，这一档不可选；" +
            "只录麦克风不受影响。"
    }

    /** 选择行下面那句说明：写清这一档录什么、不录什么。 */
    fun detail(source: String, sdk: Int = Build.VERSION.SDK_INT): String = when (source) {
        MIC -> "只录手机麦克风（说话 + 周围环境声）"
        SYSTEM -> if (available(SYSTEM, sdk)) "只录手机正在播放的声音（媒体 / 游戏）；不含通话，部分应用可禁止被录"
        else "需要 Android 10 及以上，这台设备不可选"
        MIXED -> if (available(MIXED, sdk)) "麦克风与系统播放的声音混合成一路；不含通话"
        else "需要 Android 10 及以上，这台设备不可选"
        else -> ""
    }
}

/**
 * 本机语音设置（非密部分）。**没有明文 Key，没有历史转写**。
 *
 * [selectedProvider] 与 [VoiceKeyStore] 里两家各自的 Key 相互独立：换一家不会清掉另一家的 Key，
 * 也不会顺手把用户的 Key 发到别家去 —— 「用哪家」和「填哪条 Key」是两个显式动作。
 *
 * 隐私同意按 **服务商 × 声音来源** 两个维度各记一次：同意把麦克风声音发给豆包，
 * 不等于同意把「系统播放的声音」也发给它 —— 后者录的是别人说的话、视频里的声音。
 * 麦克风那一档沿用旧键（`consent.<provider>`），所以从旧版本升上来的「已同意」不会丢、不会重问。
 */
class VoiceSettings(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 上次显式选中的服务商；没选过就是豆包。 */
    var selectedProvider: String
        get() {
            val v = prefs.getString(KEY_PROVIDER, VoiceProvider.DOUBAO) ?: VoiceProvider.DOUBAO
            return if (VoiceProvider.isValid(v)) v else VoiceProvider.DOUBAO
        }
        set(v) {
            require(VoiceProvider.isValid(v)) { "不认识的语音服务商：$v" }
            prefs.edit().putString(KEY_PROVIDER, v).apply()
        }

    /** 上次显式选择的来源。设备不支持时仍保留选择，由界面与服务明确说明，不改录麦克风。 */
    var source: String
        get() {
            val v = prefs.getString(KEY_SOURCE, VoiceSource.MIC) ?: VoiceSource.MIC
            return if (VoiceSource.isValid(v)) v else VoiceSource.MIC
        }
        set(v) {
            require(VoiceSource.isValid(v)) { "不认识的声音来源：$v" }
            prefs.edit().putString(KEY_SOURCE, v).apply()
        }

    /**
     * 隐私提示是否同意过（这一档的声音会实时上传到所选服务商）。
     * 按「服务商 × 来源」各记一次；[source] 默认麦克风，兼容旧调用与旧数据。
     */
    fun consentGiven(provider: String, source: String = VoiceSource.MIC): Boolean =
        VoiceProvider.isValid(provider) && VoiceSource.isValid(source) && prefs.getBoolean(consentKey(provider, source), false)

    fun setConsent(provider: String, source: String, given: Boolean) {
        if (!VoiceProvider.isValid(provider) || !VoiceSource.isValid(source)) return
        prefs.edit().putBoolean(consentKey(provider, source), given).apply()
    }

    companion object {
        private const val PREFS = "ling_voice"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_SOURCE = "source"

        /**
         * 同意的存储键。麦克风沿用旧键 `consent.<provider>`（升级不丢已同意）；
         * 其余按 `consent.<source>.<provider>` 分开记。**纯映射，单测直接对键**。
         */
        internal fun consentKey(provider: String, source: String): String =
            if (source == VoiceSource.MIC) "consent.$provider" else "consent.$source.$provider"
    }
}
