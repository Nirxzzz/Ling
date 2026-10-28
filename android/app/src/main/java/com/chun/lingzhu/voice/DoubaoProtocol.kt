package com.chun.lingzhu.voice

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包（火山引擎）流式语音识别的二进制协议。**纯 JVM，不碰 android.\***：单测可以直接
 * 用字节构造/解析，不需要真机也不需要网络。
 *
 * 报文格式（官方文档 + 主脑 2026-10-26 真实 Key 实测一致）：
 * ```
 * 第 0 字节  [协议版本 4bit=0001][头长度 4bit=0001]   → 0x11
 * 第 1 字节  [消息类型 4bit][标志位 4bit]
 * 第 2 字节  [序列化方式 4bit][压缩方式 4bit]
 * 第 3 字节  保留 0x00
 * ```
 *  - 消息类型：0x1 = 客户端完整请求（初始化）、0x2 = 纯音频、0x9 = 服务端结果、0xf = 服务端错误
 *  - 标志位：0x1 = 带序号（4 字节有符号）、0x2 = 这是最后一包
 *  - 压缩：0x1 = gzip
 *
 * 实测确认过的三种客户端帧头：初始化 `11 10 11 00`、音频 `11 20 11 00`、最后一包音频 `11 22 11 00`。
 * 官方文档把纯音频帧的序列化方式写作 `0000`（无序列化），实测用 `0001` 也能正常识别；
 * **这里按实测的字节走**，不按文档改 —— 改坏了就是真实用户听不到转写。
 *
 * 服务端错误包（类型 0xf）的负载是 ``uint32 错误码 + uint32 长度 + 消息``，没有序号段。
 */
internal sealed interface DoubaoEvent {
    /** 一条识别结果。[isLast] 为 true 表示服务端宣告本次会话结束（标志位 0x2）。 */
    data class Result(val text: String, val utterances: List<DoubaoProtocol.Utterance>, val isLast: Boolean) : DoubaoEvent

    /** 服务端明确报错（类型 0xf）。[code] 是服务端错误码，[message] 是服务端原文。 */
    data class Failure(val code: Long, val message: String) : DoubaoEvent

    /** 包结构本身就是坏的（长度/压缩/JSON 对不上）。和「服务端报错」是两回事，要分开报给用户。 */
    data class Malformed(val why: String) : DoubaoEvent
}

internal object DoubaoProtocol {

    /** 一帧音频 200ms：16000Hz × 16bit × 单声道 × 0.2s = 6400 字节（双向流式的最优分包）。 */
    const val FRAME_BYTES = 6400

    private const val TYPE_FULL_CLIENT = 0x1
    private const val TYPE_AUDIO_ONLY = 0x2
    private const val TYPE_FULL_SERVER = 0x9
    private const val TYPE_ERROR = 0xf

    private const val FLAG_HAS_SEQUENCE = 0x1
    private const val FLAG_LAST = 0x2

    private const val SERIALIZATION_JSON = 0x1
    private const val COMPRESSION_GZIP = 0x1

    /** 解压上限：正常一包几 KB，卡在这里说明对面发的东西不对，不该继续吃内存。 */
    private const val MAX_INFLATE = 1 shl 20

    /**
     * 一个分句。[startMs]/[endMs] 允许为 null（个别分句只给字级时间戳，甚至连字都没有）。
     *
     * [startDerived] 为 true 表示服务商**没给** `start_time`，这个值是客户端按 `words[0].start_time`
     * 推导出来的（协议文档允许这种退让）。推导值可能比真实分句起点晚几十毫秒，所以 reducer
     * 只对它做「真实区间重叠」的并入判断，对服务商直接给的时间不做任何近似。
     */
    data class Utterance(
        val text: String,
        val startMs: Long?,
        val endMs: Long?,
        val definite: Boolean,
        val startDerived: Boolean = false,
    )

    // ---------------- 客户端 → 服务端 ----------------

    /** 初始化 JSON。字段就是实测通过的那一套，不多加（多加一个未验证的开关等于拿线上赌）。 */
    fun initPayload(uid: String): String = JSONObject()
        .put("user", JSONObject().put("uid", uid))
        .put(
            "audio",
            JSONObject().put("format", "pcm").put("rate", 16_000).put("bits", 16).put("channel", 1),
        )
        .put(
            "request",
            JSONObject()
                .put("model_name", VoiceAsrConfig.DOUBAO_MODEL)
                .put("show_utterances", true)      // 要分句和分句时间，界面才能按句替换
                .put("enable_nonstream", true)     // 二遍识别：定稿那句更准
                .put("result_type", "full"),       // 全量返回：分句带稳定 start_time
        )
        .toString()

    fun fullClientRequest(json: String): ByteArray =
        frame(TYPE_FULL_CLIENT, 0, gzip(json.toByteArray(Charsets.UTF_8)))

    /**
     * 一帧音频。[last] = true 时用 `11 22 11 00`（最后一包/负包）；
     * 不管还有没有剩余音频，**结束都必须发这一帧**，不然服务端不知道说完了（尾句会丢）。
     */
    fun audioFrame(pcm: ByteArray, offset: Int, count: Int, last: Boolean): ByteArray {
        val slice = ByteArray(count)
        if (count > 0) System.arraycopy(pcm, offset, slice, 0, count)
        return frame(TYPE_AUDIO_ONLY, if (last) FLAG_LAST else 0, gzip(slice))
    }

    private fun frame(type: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write(0x11)                                   // 版本 1 + 头长度 1（=4 字节）
        out.write((type shl 4) or flags)
        out.write((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP)
        out.write(0x00)                                   // 保留
        writeU32(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    // ---------------- 服务端 → 客户端 ----------------

    fun parse(packet: ByteArray): DoubaoEvent {
        if (packet.size < 4) return DoubaoEvent.Malformed("包太短（${packet.size} 字节）")
        val headerBytes = (packet[0].toInt() and 0x0f) * 4
        if (headerBytes < 4) return DoubaoEvent.Malformed("头长度字段无效")
        if (packet.size < headerBytes) return DoubaoEvent.Malformed("头长度超过包长")

        val type = (packet[1].toInt() shr 4) and 0x0f
        val flags = packet[1].toInt() and 0x0f
        val serialization = (packet[2].toInt() shr 4) and 0x0f
        val compression = packet[2].toInt() and 0x0f
        var p = headerBytes

        if (type == TYPE_ERROR) {
            if (packet.size < p + 8) return DoubaoEvent.Malformed("错误包太短")
            val code = readU32(packet, p)
            val len = readU32(packet, p + 4)
            p += 8
            val body = payloadOf(packet, p, len, compression) ?: return DoubaoEvent.Malformed("错误包负载无效")
            val message = body.toString(Charsets.UTF_8).trim()
            return DoubaoEvent.Failure(code, message.ifEmpty { "服务端没有给出原因" })
        }
        if (type != TYPE_FULL_SERVER) return DoubaoEvent.Malformed("不认识的消息类型 $type")

        if (flags and FLAG_HAS_SEQUENCE != 0) {
            if (packet.size < p + 4) return DoubaoEvent.Malformed("缺少序号段")
            p += 4
        }
        if (packet.size < p + 4) return DoubaoEvent.Malformed("缺少负载长度")
        val len = readU32(packet, p)
        p += 4
        if (serialization != SERIALIZATION_JSON) return DoubaoEvent.Malformed("序列化方式不是 JSON")
        val body = payloadOf(packet, p, len, compression) ?: return DoubaoEvent.Malformed("结果负载无效")

        val json = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return DoubaoEvent.Malformed("结果不是合法 JSON")
        }
        return DoubaoEvent.Result(
            text = json.optJSONObject("result")?.optString("text", "").orEmpty(),
            utterances = utterancesOf(json),
            isLast = flags and FLAG_LAST != 0,
        )
    }

    private fun utterancesOf(json: JSONObject): List<Utterance> {
        val result = json.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("utterances") ?: return emptyList()
        val list = ArrayList<Utterance>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text", "")
            if (text.isBlank()) continue
            list.add(
                Utterance(
                    text = text,
                    startMs = timeOf(o, "start_time", first = true),
                    endMs = timeOf(o, "end_time", first = false),
                    definite = o.optBoolean("definite", false),
                    startDerived = !hasTime(o, "start_time"),
                ),
            )
        }
        return list
    }

    /** 服务商到底给没给这个时间字段（`null` 也算没给）。 */
    private fun hasTime(o: JSONObject, key: String): Boolean = o.has(key) && !o.isNull(key)

    /**
     * 取分句的毫秒时间。个别分句不给 `start_time`（实测确有），退一步用第一个字的
     * `start_time`、最后一个字的 `end_time` —— 只为了给界面一个稳定的槽位 id。
     */
    private fun timeOf(o: JSONObject, key: String, first: Boolean): Long? {
        if (hasTime(o, key)) {
            val v = o.optLong(key, -1)
            if (v >= 0) return v
        }
        val words = o.optJSONArray("words") ?: return null
        if (words.length() == 0) return null
        val w = if (first) words.optJSONObject(0) else words.optJSONObject(words.length() - 1)
        val v = w?.optLong(key, -1) ?: -1
        return if (v >= 0) v else null
    }

    private fun payloadOf(packet: ByteArray, offset: Int, len: Long, compression: Int): ByteArray? {
        if (len < 0 || len > Int.MAX_VALUE) return null
        val n = len.toInt()
        if (offset + n > packet.size) return null
        val raw = packet.copyOfRange(offset, offset + n)
        if (compression != COMPRESSION_GZIP) return raw
        return gunzip(raw)
    }

    // ---------------- 字节工具 ----------------

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or
            ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or
            (b[at + 3].toLong() and 0xff)

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream(minOf(data.size * 4, MAX_INFLATE))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_INFLATE) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}
