package com.chun.lingzhu.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云「实时语音识别（ASR WebSocket）」与「流式语音合成（TTS stream_ws）」的协议层。
 * **纯 JVM，不碰 android.\***：麦克风采集、播放、界面、权限都在调用方；这里只负责
 * 「按官方线上格式签名 / 打包 / 拆包 / 收尾」，并且凭据只在本进程内存里参与一次签名。
 *
 * 线上格式依据（2026-10-28 核对：官方文档 + 官方 SDK 源码，不照抄二手博客）：
 *
 * **ASR**（<https://cloud.tencent.com/document/product/1093/48982>）
 *  - 地址 `wss://asr.cloud.tencent.com/asr/v2/<appid>?<参数>`；音频 16k / 16bit / 单声道 PCM，
 *    `voice_format=1`；建议每 200ms 送 6400 字节（1:1 实时率），1 秒内最多送 3 秒音频。
 *  - 签名：除 `signature` 外的参数按字典序拼 `k=v&k=v`，接在
 *    `asr.cloud.tencent.com/asr/v2/<appid>?` 之后（**不含 `wss://`**）作为原文，
 *    `signature = base64(HMAC-SHA1(SecretKey, 原文))`，拼进 URL 时**必须 URL 编码**。
 *  - 就绪 = 服务端回 `{"code":0,...}`；在此之前本类**不送任何音频**（官方硬性时序）。
 *  - 结束：发文本帧 `{"type":"end"}`，服务端识别完剩余音频后回 `final=1` 并断链。
 *  - 分句：`result.slice_type` 0=开始 / 1=中间（非稳态）/ 2=结束（稳态）；`result.index` 是句序号。
 *  - 交叉核对：tencentcloud-speech-sdk-nodejs `asr/speechRecognizer.js` +
 *    `common/speechcredential.js`；tencentcloud-speech-sdk-python `asr/speech_recognizer.py`。
 *
 * **TTS**（<https://cloud.tencent.com/document/product/1073/94308>）
 *  - 地址 `wss://tts.cloud.tencent.com/stream_ws?<参数>`，一次连接合成一段文本；
 *    文本上限：中文 600 字 / 英文 1800 字母。
 *  - 签名原文 = `GET` + `tts.cloud.tencent.com/stream_ws` + `?` + 参数字典序（参数值不编码）；
 *    最终 URL 里 Text 与 Signature 都编码。
 *  - `Codec=pcm` + `SampleRate=16000` → 下行的二进制就是 16k / 16bit / 单声道小端 PCM，原样交给播放层。
 *  - 下行文本帧：握手 `{"code":0,...}`、过程里的字幕时间戳、`final=1`（音频已全部发完）。
 *  - `Speed` 是官方**档位参数 [-2, 6]**（-2→0.6 倍、0→1.0 倍、6→2.5 倍，可带两位小数），不是倍率。
 *  - `VoiceType` 是官方音色 ID，例如 101001（智瑜，情感女声）、101016（智甜，女童声）、
 *    101004（智云，通用男声）、101050（WeJack，英文男声）；完整列表见
 *    <https://cloud.tencent.com/document/product/1073/92668>。
 *  - 交叉核对：tencentcloud-speech-sdk-go `tts/speechwssynthesizer.go`（buildURL + genWsSignature）、
 *    tencentcloud-speech-sdk-python `tts/speech_synthesizer_ws.py`、Java SDK `SpeechWsSynthesizer`。
 *
 * 硬规矩：
 *  1. 凭据（AppId / SecretId / SecretKey）只参与签名，**不落盘、不打日志、不进回调文本**；
 *     错误文本统一过 [TencentCredentials.scrub]（连 `signature=` / `secretid=` 参数片段一起抹掉）。
 *  2. 取消 = 立刻断链，**不重试、不换服务商**；取消之后迟到的包一律静默丢弃。
 *  3. 队列都有上限：就绪前最多攒 2 秒音频（64KB），发送队列超 1MB 明确失败并结束，不静默丢帧。
 *  4. 回调统一在同一条后台线程上按发生顺序投递；终态（[TencentSpeech.RecognitionListener.onFinished]
 *     / [TencentSpeech.RecognitionListener.onError]）一辈子只发一次。
 */
internal data class TencentCredentials(
    val appId: String,
    val secretId: String,
    val secretKey: String,
) {
    init {
        require(appId.isNotBlank()) { "腾讯语音 AppId 是空的" }
        require(secretId.isNotBlank()) { "腾讯语音 SecretId 是空的" }
        require(secretKey.isNotBlank()) { "腾讯语音 SecretKey 是空的" }
    }

    /**
     * 把可能混进错误文本里的凭据抹掉。
     *
     * 服务端的错误消息、okhttp 的异常消息本来不该带凭据，但「本来不该」不等于「不会」——
     * 这些文本会显示在界面上、也可能写进崩溃日志，所以出口统一过一遍。
     */
    fun scrub(message: String): String {
        var out = message
        val id = secretId.trim()
        val key = secretKey.trim()
        if (id.length >= 6 && out.contains(id)) out = out.replace(id, "***")
        if (key.length >= 4 && out.contains(key)) out = out.replace(key, "***")
        return QUERY_SECRET.replace(out) { match -> match.groupValues[1] + "=***" }
    }

    /** 日志 / 崩溃报告里只会看到这个形态（不出现任何密钥材料）。 */
    override fun toString(): String = "TencentCredentials(appId=set, secretId=set, secretKey=set)"

    companion object {
        /** 错误文本里可能出现的敏感查询参数（值是签名或密钥本身）。 */
        private val QUERY_SECRET =
            Regex("(?i)(signature|secretid|secretkey|token)=[^&\\s\"']+")

        /**
         * 解析落盘的凭据。
         *
         * [raw] 是**调用方已经用 Keystore 解出来的明文 JSON**（落盘形态必须是密文）：
         * `{"appId":"1259...","secretId":"AKID...","secretKey":"..."}`。本层不负责解密、
         * 不接触 SharedPreferences，只校验字段齐全、AppId 是数字。
         *
         * 解析失败抛 [IllegalArgumentException]，其消息里不含任何密钥材料（可直接上屏）。
         */
        fun parse(raw: String): TencentCredentials {
            val text = raw.trim()
            require(text.isNotEmpty()) { "腾讯语音凭据是空的" }
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IllegalArgumentException("腾讯语音凭据不是合法的 JSON")
            }
            val appId = json.opt("appId")?.toString()?.trim().orEmpty()
            val secretId = json.opt("secretId")?.toString()?.trim().orEmpty()
            val secretKey = json.opt("secretKey")?.toString()?.trim().orEmpty()
            require(appId.isNotEmpty()) { "腾讯语音凭据缺少 appId" }
            require(secretId.isNotEmpty()) { "腾讯语音凭据缺少 secretId" }
            require(secretKey.isNotEmpty()) { "腾讯语音凭据缺少 secretKey" }
            // 官方要求 AppId 是整型；别的形态直接拒，免得签进 URL 才在服务端报 4002。
            require(appId.matches(Regex("[0-9]{1,20}"))) { "腾讯语音 AppId 必须是数字" }
            return TencentCredentials(appId, secretId, secretKey)
        }
    }
}

/** 两套协议的纯函数部分：拼签名原文、算签名、拼 URL、格式化参数。与网络无关，可单独验证。 */
internal object TencentProtocol {

    /** 16k / 16bit / 单声道：一帧 200ms = 6400 字节（官方建议的 1:1 实时率分包）。 */
    const val FRAME_BYTES = 6400

    const val SAMPLE_RATE = 16000

    /** 16k PCM 对应官方通用中文引擎。 */
    const val ASR_ENGINE = "16k_zh"

    const val ASR_HOST_PATH = "asr.cloud.tencent.com/asr/v2/"

    /** TTS 签名原文前缀 = 请求方法 + 域名 + 路径。 */
    const val TTS_SIGN_PREFIX = "GETtts.cloud.tencent.com/stream_ws"

    const val TTS_HOST_PATH = "tts.cloud.tencent.com/stream_ws"

    /** 一次识别握手 URL（签名原文不含 `wss://`，也不含 `signature` 本身）。 */
    fun asrUrl(credentials: TencentCredentials, voiceId: String, nowSec: Long): String {
        val params = sortedMapOf(
            "engine_model_type" to ASR_ENGINE,
            "expired" to (nowSec + SIGN_TTL_SEC).toString(),
            "needvad" to "1",
            "nonce" to nonce(),
            "secretid" to credentials.secretId.trim(),
            "timestamp" to nowSec.toString(),
            "voice_format" to "1", // 1 = pcm
            "voice_id" to voiceId,
        )
        val appId = credentials.appId.trim()
        val signStr = ASR_HOST_PATH + appId + "?" + sortedRaw(params)
        val signature = hmacSha1Base64(credentials.secretKey.trim(), signStr)
        return "wss://" + ASR_HOST_PATH + appId + "?" + sortedEncoded(params) +
            "&signature=" + percentEncode(signature)
    }

    /** 一次合成握手 URL（参数值在签名原文里不编码，拼到 URL 时才编码）。 */
    fun ttsUrl(
        credentials: TencentCredentials,
        text: String,
        voiceType: Int,
        speed: Double,
        sessionId: String,
        nowSec: Long,
    ): String {
        val params = sortedMapOf(
            "Action" to "TextToStreamAudioWS",
            "AppId" to credentials.appId.trim(),
            "Codec" to "pcm",
            "Expired" to (nowSec + SIGN_TTL_SEC).toString(),
            "SampleRate" to SAMPLE_RATE.toString(),
            "SecretId" to credentials.secretId.trim(),
            "SessionId" to sessionId,
            "Speed" to formatSpeed(speed),
            "Text" to text,
            "Timestamp" to nowSec.toString(),
            "VoiceType" to voiceType.toString(),
        )
        val signStr = TTS_SIGN_PREFIX + "?" + sortedRaw(params)
        val signature = hmacSha1Base64(credentials.secretKey.trim(), signStr)
        return "wss://" + TTS_HOST_PATH + "?" + sortedEncoded(params) +
            "&Signature=" + percentEncode(signature)
    }

    fun hmacSha1Base64(secretKey: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** Speed 是 [-2, 6] 的档位参数：整数不带小数尾巴，其余最多两位小数（`0.5`、`1.25`）。 */
    fun formatSpeed(speed: Double): String =
        BigDecimal.valueOf(Math.round(speed * 100) / 100.0).stripTrailingZeros().toPlainString()

    /** 真实时间轴上「已经允许送出去的音频时长」。官方限制 1 秒内最多 3 秒音频。 */
    fun pacingBudgetMs(elapsedMs: Long): Long = elapsedMs + 1000

    private fun sortedRaw(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun sortedEncoded(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${percentEncode(it.value)}" }

    /** 严格 RFC 3986 百分号编码（空格→%20，非保留字符按 UTF-8 逐字节编码）。 */
    fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size + 8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (
                c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            ) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[c ushr 4])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun nonce(): String = (100_000_000L + (Math.random() * 899_999_999L).toLong()).toString()

    /** 签名有效期：官方要求 `expired - timestamp < 90 天`；一次会话用 24 小时足够。 */
    private const val SIGN_TTL_SEC = 24 * 60 * 60L
    private const val HEX = "0123456789ABCDEF"
}

/**
 * 腾讯语音的会话工厂。一个实例可反复使用；每个 [recognize] / [synthesize] 返回一条独立会话。
 *
 * 生产路径不需要传参（默认共用一条 OkHttp 连接池）；[client] 只用于测试替身注入。
 */
internal class TencentSpeech(private val client: OkHttpClient) {

    constructor() : this(SHARED_CLIENT)

    /** 可取消的句柄。取消是幂等的；取消之后不再有任何回调。 */
    interface Cancel {
        fun cancel()
    }

    /** 一路实时识别。[sendPcm] / [finish] / [cancel] 任意线程可调。 */
    interface Recognition : Cancel {
        /**
         * 送一帧 PCM（16k / 16bit / 单声道小端；单帧 ≤ 6400 字节、偶数字节）。
         *
         * 返回 false 表示这一帧**没有被接受**：格式不对、还没就绪且已攒满 2 秒、发送队列积压，
         * 或者超过官方实时率（1 秒内最多 3 秒音频）。丢帧不会排队补齐 —— 补发只会让延迟越滚越大。
         */
        fun sendPcm(pcm: ByteArray): Boolean

        /** 说完了：补发尾巴、发结束帧，然后等服务端 `final=1`（有硬超时）。 */
        fun finish()
    }

    interface RecognitionListener {
        /** 服务端确认会话就绪（`code=0` 的第一包）。此前不会送任何音频。 */
        fun onReady()

        /** 一句识别结果的更新。[final] 为 true 表示该 [segmentId] 的稳态结果（同一句只回调一次）。 */
        fun onText(text: String, final: Boolean, segmentId: String)

        /** 出错 / 中断。文本可直接上屏，不含凭据；终态只发一次。 */
        fun onError(message: String)

        /** 正常收尾：服务端已回 `final=1`。终态只发一次。 */
        fun onFinished()
    }

    interface SynthesisListener {
        /** 合成出来的 PCM（16k / 16bit / 单声道小端），原样交给播放层排队。 */
        fun onPcm(pcm: ByteArray)

        /** 服务端已回 `final=1`：这一段的音频全部到齐。 */
        fun onFinished()

        /** 出错 / 中断。文本可直接上屏，不含凭据；终态只发一次。 */
        fun onError(message: String)
    }

    /** 开始一路识别：立即建连，服务端确认后回调 [RecognitionListener.onReady]。 */
    fun recognize(credentials: TencentCredentials, listener: RecognitionListener): Recognition =
        RecognitionSession(credentials, listener, client).also { it.start() }

    /**
     * 合成一段文本。
     *
     * [voiceType] 是官方音色 ID（正整数，如 101001）；[speed] 是官方档位参数 [-2, 6]（0 = 1.0 倍）。
     * 参数不合法时不会建连，直接异步回调一次 [SynthesisListener.onError]。
     */
    fun synthesize(
        credentials: TencentCredentials,
        text: String,
        voiceType: Int,
        speed: Double,
        listener: SynthesisListener,
    ): Cancel = SynthesisSession(credentials, text, voiceType, speed, listener, client).also { it.start() }

    // ---------------- 识别 ----------------

    private class RecognitionSession(
        private val credentials: TencentCredentials,
        private val listener: RecognitionListener,
        private val client: OkHttpClient,
    ) : Recognition {

        private val lock = Any()
        private val delivery = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tencent-asr-callbacks").apply { isDaemon = true }
        }
        private val voiceId = UUID.randomUUID().toString()
        private val finalTexts = HashMap<String, String>()
        private val carry = ByteArray(TencentProtocol.FRAME_BYTES)
        private var carryLen = 0
        private val pending = ArrayDeque<ByteArray>()
        private var pendingBytes = 0

        private var socket: WebSocket? = null
        private var ready = false
        private var readyEmitted = false
        private var finishing = false
        private var terminal = false

        @Volatile
        private var cancelled = false

        private var readyAtMs = 0L
        private var sentMs = 0L
        private var burstMs = 0L
        private var readyTimer: ScheduledFuture<*>? = null
        private var drainTimer: ScheduledFuture<*>? = null

        fun start() {
            // Request.Builder().url(...) 对非法 URL 会抛 IllegalArgumentException；放进同一个 try，
            // 否则异常会冒到调用方，终态回调永远不会来，上层一直以为会话还活着。
            val request = try {
                val url = TencentProtocol.asrUrl(credentials, voiceId, System.currentTimeMillis() / 1000)
                Request.Builder().url(url).build()
            } catch (_: Exception) {
                fail("腾讯语音识别连接参数生成失败")
                return
            }
            synchronized(lock) {
                readyTimer = SCHEDULER.schedule(
                    { fail("腾讯语音识别没有在限定时间内就绪") },
                    CONNECT_TIMEOUT_MS + READY_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                socket = client.newWebSocket(request, SocketListener())
            }
        }

        override fun sendPcm(pcm: ByteArray): Boolean {
            if (pcm.isEmpty()) return true // 没东西可送，不算失败
            if (pcm.size > TencentProtocol.FRAME_BYTES || pcm.size % 2 != 0) {
                // 调用方给错格式：宁可当场说清楚，也不悄悄吃掉半句话。
                fail("腾讯语音识别的音频帧不合法（需要 16 位对齐的 PCM，单帧 ≤ ${TencentProtocol.FRAME_BYTES} 字节）")
                return false
            }
            var problem: String? = null
            synchronized(lock) {
                if (terminal || finishing) return false
                problem = if (ready) {
                    push(pcm)
                } else if (enqueue(pcm.copyOf())) {
                    null
                } else {
                    PENDING_BACKPRESSURE
                }
            }
            if (problem != null) {
                fail(problem)
                return false
            }
            return true
        }

        override fun finish() {
            synchronized(lock) {
                if (terminal || finishing) return
                finishing = true
                // 还没就绪就点「停」：不是错误，等就绪那一刻把尾巴和 end 一起补上（有就绪超时兜底）。
                if (ready) sendEndLocked()
            }
        }

        private fun sendEndLocked() {
            val ws = socket ?: return failLocked("腾讯语音识别连接已断开")
            // 尾巴（不足一帧的几百毫秒）直接补发：丢掉它就是丢掉最后半句话。
            if (carryLen > 0) {
                ws.send(carry.copyOf(carryLen).toByteString())
                carryLen = 0
            }
            if (!ws.send(END_FRAME)) return failLocked("腾讯语音识别结束帧发送失败")
            drainTimer = SCHEDULER.schedule(
                { fail("腾讯语音识别没有在限定时间内结束，结果可能不完整") },
                DRAIN_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        override fun cancel() {
            synchronized(lock) {
                if (terminal) return
                cancelled = true
                terminal = true
                clearTimers()
                val ws = socket
                socket = null
                ws?.cancel()
                delivery.shutdown()
            }
        }

        // ---- 内部 ----

        private inner class SocketListener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (terminal) return
                    // socket 通了，但「就绪」仍以服务端第一条 code=0 的消息为准。
                    readyTimer?.cancel(false)
                    readyTimer = SCHEDULER.schedule(
                        { fail("腾讯语音识别没有在限定时间内就绪") },
                        READY_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // ASR 只回 JSON 文本帧；二进制是不该出现的，忽略而不是崩。
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message?.take(120)?.takeIf { it.isNotBlank() } ?: "网络错误"
                fail("腾讯语音识别连接出错：$detail")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (terminal) return
                    if (finishing) return failLocked("腾讯语音识别提前断开，识别结果可能不完整")
                    if (!ready) return failLocked("腾讯语音识别没有连上（连接被服务端关闭）")
                    failLocked("腾讯语音识别连接已断开")
                }
            }
        }

        private fun handleMessage(raw: String) {
            if (raw.length > MAX_JSON_CHARS) return fail("腾讯语音识别返回了过大的数据包")
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return fail("腾讯语音识别返回了无法解析的数据包")
            }
            synchronized(lock) {
                if (terminal) return
                val code = json.optInt("code", -1)
                if (code != 0) {
                    return failLocked(
                        "腾讯语音识别失败（$code）" + ASR_HINTS[code]?.let { "：$it" }.orEmpty() +
                            json.optString("message").takeIf { it.isNotBlank() }
                                ?.let { "（${credentials.scrub(it)}）" }.orEmpty(),
                    )
                }
                if (!readyEmitted) {
                    readyEmitted = true
                    ready = true
                    // 就绪前攒下的音频（上限 2 秒）在这一刻一次送完：那是「已经发生的声音」，
                    // 按实时率硬卡只会把它们整段丢掉；送出去的时长转为一次性额度余量。
                    readyAtMs = System.currentTimeMillis()
                    sentMs = 0
                    burstMs = flushPendingLocked()
                    // 补发阶段就背压失败了：已经按失败收尾，不必再报「就绪」。
                    if (terminal) return
                    readyTimer?.cancel(false)
                    readyTimer = null
                    post { listener.onReady() }
                    // 就绪前就点过「停」：这一刻把尾巴与 end 补上，并忽略这一包里的识别结果。
                    if (finishing) return sendEndLocked()
                }
                if (json.optInt("final", 0) == 1) return succeedLocked()
                val result = json.optJSONObject("result") ?: return
                val segmentId = result.optInt("index", -1)
                if (segmentId < 0) return
                val sliceType = result.optInt("slice_type", -1)
                val text = result.optString("voice_text_str")
                val isFinal = sliceType == 2
                val key = segmentId.toString()
                if (isFinal) {
                    // 同一句的稳态结果只回调一次：重复的 final 会让上层多跑一轮 LLM。
                    if (finalTexts[key] == text) return
                    finalTexts[key] = text
                } else if (text.isEmpty()) {
                    return // slice_type 0/1 的空中间结果没有信息量
                }
                post { listener.onText(text, isFinal, key) }
            }
        }

        private fun enqueue(chunk: ByteArray): Boolean {
            if (pendingBytes + chunk.size > MAX_PENDING_BYTES) return false
            pending.addLast(chunk)
            pendingBytes += chunk.size
            return true
        }

        /**
         * 就绪后把攒下的音频一次送完（**不受实时率约束**：这些声音已经发生过了，卡额度等于丢掉）。
         * 返回送出去的音频时长，作为之后发送额度的一次性余量；送不出去就当场按失败收尾。
         */
        private fun flushPendingLocked(): Long {
            var flushedMs = 0L
            while (pending.isNotEmpty()) {
                val chunk = pending.removeFirst()
                pendingBytes -= chunk.size
                var offset = 0
                while (offset < chunk.size) {
                    val take = minOf(carry.size - carryLen, chunk.size - offset)
                    System.arraycopy(chunk, offset, carry, carryLen, take)
                    carryLen += take
                    offset += take
                    if (carryLen == carry.size) {
                        val ws = socket
                        if (ws == null || !ws.send(carry.toByteString())) {
                            carryLen = 0
                            pending.clear()
                            pendingBytes = 0
                            failLocked(BACKPRESSURE)
                            return 0L
                        }
                        flushedMs += (carry.size / 2) * 1000L / TencentProtocol.SAMPLE_RATE
                        carryLen = 0
                    }
                }
            }
            pendingBytes = 0
            return flushedMs
        }

        /** 把一小段 PCM 塞进尾巴，凑满 6400 字节的整帧就发。返回 null = 全部送出去了。 */
        private fun push(chunk: ByteArray): String? {
            var offset = 0
            while (offset < chunk.size) {
                val take = minOf(carry.size - carryLen, chunk.size - offset)
                System.arraycopy(chunk, offset, carry, carryLen, take)
                carryLen += take
                offset += take
                if (carryLen == carry.size) {
                    // toByteString() 自己就复制一份不可变字节串：carry 下一帧复用也不会串。
                    val problem = transmit(carry.toByteString(), carry.size)
                    carryLen = 0
                    if (problem != null) return problem
                }
            }
            return null
        }

        /** 真正发一帧：发送队列上限与官方实时率两道闸；返回 null = 发出去了。 */
        private fun transmit(frame: okio.ByteString, byteCount: Int): String? {
            val ws = socket ?: return "腾讯语音识别连接已断开"
            if (ws.queueSize() > MAX_SOCKET_BUFFERED) return BACKPRESSURE
            val frameMs = (byteCount / 2) * 1000L / TencentProtocol.SAMPLE_RATE
            val budget = TencentProtocol.pacingBudgetMs(System.currentTimeMillis() - readyAtMs) + burstMs
            if (sentMs + frameMs > budget) {
                return "音频送得比实时还快（腾讯限制 1 秒内最多 3 秒音频），本次识别已停止"
            }
            if (!ws.send(frame)) return BACKPRESSURE
            sentMs += frameMs
            return null
        }

        private fun succeedLocked() {
            if (terminal) return
            terminal = true
            clearTimers()
            val ws = socket
            socket = null
            ws?.close(1000, null)
            deliver { if (!cancelled) listener.onFinished() }
        }

        private fun fail(message: String) {
            synchronized(lock) { failLocked(message) }
        }

        private fun failLocked(message: String) {
            if (terminal) return
            terminal = true
            clearTimers()
            val ws = socket
            socket = null
            ws?.cancel()
            val text = credentials.scrub(message)
            deliver { if (!cancelled) listener.onError(text) }
        }

        /** 回调在单线程上按发生顺序投递；取消之后一律静默（迟到的结果不该再上屏）。 */
        private fun post(block: () -> Unit) = deliver(block)

        /** 终态之后投递线程就关掉：再进来的（并发路径上的）迟到回调直接丢弃。 */
        private fun deliver(block: () -> Unit) {
            try {
                delivery.execute { if (!cancelled) block() }
            } catch (_: Exception) {
                // 线程已经关掉（会话早已终止）：迟到的回调不再有意义。
                return
            }
            if (terminal) delivery.shutdown()
        }

        private fun clearTimers() {
            readyTimer?.cancel(false)
            drainTimer?.cancel(false)
            readyTimer = null
            drainTimer = null
        }
    }

    // ---------------- 合成 ----------------

    private class SynthesisSession(
        private val credentials: TencentCredentials,
        private val text: String,
        private val voiceType: Int,
        private val speed: Double,
        private val listener: SynthesisListener,
        private val client: OkHttpClient,
    ) : Cancel {

        private val lock = Any()
        private val delivery = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tencent-tts-callbacks").apply { isDaemon = true }
        }
        private val sessionId = UUID.randomUUID().toString()

        private var socket: WebSocket? = null
        private var handshakeSeen = false
        private var audioBytes = 0L
        private var terminal = false

        @Volatile
        private var cancelled = false

        private var handshakeTimer: ScheduledFuture<*>? = null
        private var idleTimer: ScheduledFuture<*>? = null
        private var totalTimer: ScheduledFuture<*>? = null

        fun start() {
            val issue = validate()
            if (issue != null) {
                fail(issue)
                return
            }
            // 同识别侧：Request.Builder().url(...) 可能抛；必须和 URL 拼装共用一个 try，
            // 才不会把未抹洗的 URL 异常冒到上层、并把终态回调吞掉。
            val request = try {
                val url = TencentProtocol.ttsUrl(credentials, text, voiceType, speed, sessionId, System.currentTimeMillis() / 1000)
                Request.Builder().url(url).build()
            } catch (_: Exception) {
                fail("腾讯语音合成连接参数生成失败")
                return
            }
            synchronized(lock) {
                handshakeTimer = SCHEDULER.schedule(
                    { fail("腾讯语音合成没有在限定时间内握手成功") },
                    CONNECT_TIMEOUT_MS + READY_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                totalTimer = SCHEDULER.schedule(
                    { fail("腾讯语音合成超过总时长上限，已中断") },
                    TOTAL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                socket = client.newWebSocket(request, SocketListener())
            }
        }

        override fun cancel() {
            synchronized(lock) {
                if (terminal) return
                cancelled = true
                terminal = true
                clearTimers()
                val ws = socket
                socket = null
                ws?.cancel()
                delivery.shutdown()
            }
        }

        /** 参数校验：返回一句可直接上屏的话，或用 null 表示通过。 */
        private fun validate(): String? {
            if (text.isBlank()) return "要合成的文本是空的"
            if (text.length > MAX_TEXT_CHARS) return "合成文本超过 $MAX_TEXT_CHARS 个字符（腾讯官方上限）"
            if (countCjk(text) > MAX_TEXT_CJK) return "合成文本超过 $MAX_TEXT_CJK 个汉字（腾讯官方上限）"
            if (voiceType <= 0) return "音色 ID（voiceType）必须是正整数，例如 101001"
            if (speed < SPEED_MIN || speed > SPEED_MAX) {
                return "语速（speed）超出腾讯官方范围 [$SPEED_MIN, $SPEED_MAX]（0 = 正常语速）"
            }
            return null
        }

        private inner class SocketListener : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                synchronized(lock) {
                    if (terminal) return
                    // 二进制 = 原始 PCM（Codec=pcm、SampleRate=16000）。原样交给播放层，不转码。
                    handshakeSeen = true
                    handshakeTimer?.cancel(false)
                    handshakeTimer = null
                    resetIdleTimerLocked()
                    audioBytes += bytes.size
                    val pcm = bytes.toByteArray()
                    post { listener.onPcm(pcm) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_JSON_CHARS) return fail("腾讯语音合成返回了过大的数据包")
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return fail("腾讯语音合成返回了无法解析的数据包")
                }
                synchronized(lock) {
                    if (terminal) return
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        return failLocked(
                            "腾讯语音合成失败（$code）" + TTS_HINTS[code]?.let { "：$it" }.orEmpty() +
                                json.optString("message").takeIf { it.isNotBlank() }
                                    ?.let { "（${credentials.scrub(it)}）" }.orEmpty(),
                        )
                    }
                    handshakeSeen = true
                    handshakeTimer?.cancel(false)
                    handshakeTimer = null
                    resetIdleTimerLocked()
                    if (json.optInt("final", 0) == 1) return succeedLocked()
                    // 其余是字幕时间戳（result.subtitles）：本层不需要，不往上层传。
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message?.take(120)?.takeIf { it.isNotBlank() } ?: "网络错误"
                fail("腾讯语音合成连接出错：$detail")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    if (terminal) return
                    if (!handshakeSeen) return failLocked("腾讯语音合成没有握手成功（连接被服务端关闭）")
                    if (audioBytes == 0L) return failLocked("腾讯语音合成没有返回任何音频")
                    failLocked("腾讯语音合成提前断开，音频可能不完整")
                }
            }
        }

        private fun resetIdleTimerLocked() {
            idleTimer?.cancel(false)
            idleTimer = SCHEDULER.schedule(
                { fail("腾讯语音合成长时间没有回包，已中断") },
                IDLE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun succeedLocked() {
            if (terminal) return
            terminal = true
            clearTimers()
            val ws = socket
            socket = null
            ws?.close(1000, null)
            deliver { if (!cancelled) listener.onFinished() }
        }

        private fun fail(message: String) {
            synchronized(lock) { failLocked(message) }
        }

        private fun failLocked(message: String) {
            if (terminal) return
            terminal = true
            clearTimers()
            val ws = socket
            socket = null
            ws?.cancel()
            val text = credentials.scrub(message)
            deliver { if (!cancelled) listener.onError(text) }
        }

        /** 回调在单线程上按发生顺序投递；取消之后一律静默（迟到的音频不该再被播放）。 */
        private fun post(block: () -> Unit) = deliver(block)

        private fun deliver(block: () -> Unit) {
            try {
                delivery.execute { if (!cancelled) block() }
            } catch (_: Exception) {
                // 投递线程已关闭（会话早已终止）：迟到回调不再有意义。
                return
            }
            if (terminal) delivery.shutdown()
        }

        private fun clearTimers() {
            handshakeTimer?.cancel(false)
            idleTimer?.cancel(false)
            totalTimer?.cancel(false)
            handshakeTimer = null
            idleTimer = null
            totalTimer = null
        }

        private fun countCjk(value: String): Int {
            var count = 0
            var i = 0
            while (i < value.length) {
                val code = value.codePointAt(i)
                if (code in 0x3400..0x9FFF) count += 1
                i += Character.charCount(code)
            }
            return count
        }
    }

    private companion object {
        /** 官方文档：中文 600 汉字 / 英文 1800 字母。 */
        const val MAX_TEXT_CJK = 600
        const val MAX_TEXT_CHARS = 1800

        /** 官方 Speed 档位区间（不是倍率）。 */
        const val SPEED_MIN = -2.0
        const val SPEED_MAX = 6.0

        /** 就绪前最多攒 2 秒音频（2 × 6400 × 5 = 64000 字节）。 */
        const val MAX_PENDING_BYTES = 64_000

        /**
         * okhttp 已收下但还没发出去的字节上限（约 32 秒 16k PCM）。
         *
         * 超过就说明网络已经跟不上实时流：**立刻按失败收尾**（[sendPcm] 返回 false 且回调 onError），
         * 绝不静默丢帧继续「看着在识别」—— 那会把用户的半句话吃掉还显示正常。
         */
        const val MAX_SOCKET_BUFFERED = 1L shl 20

        /** 背压失败文案：和 [VoiceAsrClient] 同一套说法，界面不需要为两家写两套提示。 */
        const val BACKPRESSURE = "网络太慢，录音已经积压，本次识别已停止（不会补发，请重新开始）"

        /** 还没就绪就攒满 2 秒：同样是背压，不是「悄悄丢帧」。 */
        const val PENDING_BACKPRESSURE =
            "语音还没就绪就已经积压超过 2 秒，本次识别已停止（不会补发，请重新开始）"

        /** 单个 JSON 帧的字符上限：正常几 KB，超了说明对面发的东西不对。 */
        const val MAX_JSON_CHARS = 1 shl 20

        const val CONNECT_TIMEOUT_MS = 8_000L
        const val READY_TIMEOUT_MS = 8_000L
        const val DRAIN_TIMEOUT_MS = 8_000L

        /** 官方：音频包间隔超过 6 秒会断；服务端自己也会在 15 秒没音频时报 4008。 */
        const val IDLE_TIMEOUT_MS = 15_000L

        /** 一段最长文本（600 汉字）合成也就两分多钟音频，给三分钟硬上限。 */
        const val TOTAL_TIMEOUT_MS = 180_000L

        private const val END_FRAME = "{\"type\":\"end\"}"

        private val ASR_HINTS = mapOf(
            4000 to "音频发送太快（1 秒内最多发 3 秒音频）",
            4002 to "鉴权失败：检查 AppId / SecretId / SecretKey",
            4003 to "AppID 未开通实时语音识别",
            4004 to "识别资源包已用完",
            4005 to "账号欠费",
            4006 to "并发超限",
            4007 to "音频格式不对（需要 16k/16bit/单声道 PCM）",
            4008 to "客户端超过 15 秒未发送音频数据",
            4009 to "客户端连接断开",
            4010 to "客户端上传未知文本消息",
            5000 to "服务端繁忙，稍后重试",
            5001 to "服务端繁忙，稍后重试",
            5002 to "服务端繁忙，稍后重试",
            6001 to "境外调用未开通（国内站账号请关闭境外代理）",
        )

        private val TTS_HINTS = mapOf(
            10001 to "参数不合法（检查音色 ID / 语速）",
            10002 to "并发超限",
            10003 to "鉴权失败：检查 AppId / SecretId / SecretKey",
            10004 to "客户端上传超时",
            10005 to "连接被断开",
            20000 to "后台错误",
            20001 to "后台合成失败",
            20002 to "引擎合成失败",
            20003 to "引擎合成超时",
        )

        /** 所有超时共用一个调度线程：只是取消/投递，不做 IO。 */
        val SCHEDULER: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "tencent-speech-timers").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /** 连接默认值：不设读超时（WebSocket 自己心跳），只管握手那一段。 */
        val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // 取消/失败不重试：换一条连接等于把音频重放一遍
            .build()
    }
}
