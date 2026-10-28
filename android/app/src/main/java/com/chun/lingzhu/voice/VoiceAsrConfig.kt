package com.chun.lingzhu.voice

/**
 * 一次实时转写会话的配置快照（不可变）。
 *
 * 只支持两家，**没有第三家、没有自动切换**：
 *  - [DOUBAO]  豆包（火山引擎）流式 ASR。新版控制台 API Key，握手用 `X-Api-Key`，
 *              不再需要老的 app id / access token 那一套。
 *  - [ALIBABA] 阿里云百炼 paraformer 实时语音识别，`Authorization: Bearer <Key>`。
 *
 * 两家的 Key 是各自的、互不通用，所以 UI 侧必须分开保存（一个坏掉不能影响另一个）。
 * 地址 / 资源 / 模型都是固定的常量，**不接受外部覆盖**：Key 只会发给这两个域名。
 */
data class VoiceAsrConfig(val provider: String, val apiKey: String) {
    /** 归一化后的服务商；不认识的一律 null（不猜、不退回默认）。 */
    val providerId: String?
        get() = when (provider.trim().lowercase()) {
            DOUBAO -> DOUBAO
            ALIBABA -> ALIBABA
            else -> null
        }

    /**
     * 配置能不能开跑：能开跑返回 null，否则返回一句可以直接给用户看的话。
     * 里面绝不会出现 Key 本身。
     */
    fun issue(): String? = when {
        providerId == null -> "语音服务商无效（只支持 $DOUBAO / $ALIBABA）"
        apiKey.isBlank() -> "还没有填写${providerLabel()}的语音识别 Key"
        else -> null
    }

    fun providerLabel(): String = when (providerId) {
        DOUBAO -> "豆包"
        ALIBABA -> "阿里云百炼"
        else -> provider.trim().take(24)
    }

    /**
     * 把可能混进错误文本里的 Key 抹掉。
     *
     * 服务端的错误消息、异常消息本来不该带 Key，但「本来不该」不等于「不会」——
     * 上层会把错误文本显示在界面上、也可能写进崩溃日志，所以出口统一过一遍。
     */
    fun scrub(message: String): String {
        val key = apiKey.trim()
        return if (key.length >= 6 && message.contains(key)) message.replace(key, "***") else message
    }

    override fun toString(): String =
        "VoiceAsrConfig(provider=${providerId ?: provider.trim().take(24)}, apiKey=${if (apiKey.isBlank()) "none" else "set"})"

    companion object {
        const val DOUBAO = "doubao"
        const val ALIBABA = "alibaba"

        /** 两家都只能走固定地址；换域名等于把 Key 发给别人，所以不做参数化。 */
        const val DOUBAO_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val ALIBABA_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

        const val DOUBAO_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val DOUBAO_MODEL = "bigmodel"
        const val ALIBABA_MODEL = "paraformer-realtime-v2"
    }
}
