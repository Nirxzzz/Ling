package com.chun.lingzhu.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包二进制协议 + 分句 reducer 的纯 JVM 测试。
 *
 * 只测「用户真能看见」的东西：帧头字节对不对（发错了服务端直接不认）、同一句会不会重复上屏、
 * 定稿会不会被迟到的中间结果改回去、坏包/报错包能不能分开报。
 */
class DoubaoProtocolTest {

    // ---------------- 帧头字节：错了服务端就不认 ----------------

    @Test fun initFrameUsesVerifiedHeader() {
        val frame = DoubaoProtocol.fullClientRequest("{}")
        assertEquals("版本/头长", 0x11, frame[0].toInt() and 0xff)
        assertEquals("消息类型=完整请求, 标志=0", 0x10, frame[1].toInt() and 0xff)
        assertEquals("序列化=JSON, 压缩=gzip", 0x11, frame[2].toInt() and 0xff)
        assertEquals(0x00, frame[3].toInt() and 0xff)
    }

    @Test fun audioFrameHeaderAndLastPacketFlag() {
        val pcm = ByteArray(200)
        val normal = DoubaoProtocol.audioFrame(pcm, 0, pcm.size, last = false)
        assertEquals(0x20, normal[1].toInt() and 0xff)      // 纯音频、非最后一包
        val last = DoubaoProtocol.audioFrame(pcm, 0, pcm.size, last = true)
        assertEquals("最后一包必须是 0x22", 0x22, last[1].toInt() and 0xff)
    }

    @Test fun audioFrameCompressesExactlyTheGivenSlice() {
        val pcm = ByteArray(6400) { (it % 251).toByte() }
        val frame = DoubaoProtocol.audioFrame(pcm, 100, 300, last = false)
        val body = gunzip(payloadOf(frame))
        assertEquals(300, body.size)
        assertEquals(pcm[100], body[0])
        assertEquals(pcm[399], body[299])
    }

    @Test fun initPayloadAsksForUtterancesWithStableTimestamps() {
        val json = JSONObject(DoubaoProtocol.initPayload("uid-1"))
        assertEquals("pcm", json.getJSONObject("audio").getString("format"))
        assertEquals(16000, json.getJSONObject("audio").getInt("rate"))
        assertEquals("bigmodel", json.getJSONObject("request").getString("model_name"))
        assertTrue("要分句才能按句替换", json.getJSONObject("request").getBoolean("show_utterances"))
        assertEquals("full", json.getJSONObject("request").getString("result_type"))
    }

    // ---------------- 解析服务端包 ----------------

    @Test fun parsesPartialAndFinalUtterances() {
        val partial = parse(result(json = utteranceJson("你好", 0, 400, definite = false)))
        assertTrue(partial is DoubaoEvent.Result)
        val u = (partial as DoubaoEvent.Result).utterances.single()
        assertEquals("你好", u.text)
        assertEquals(0L, u.startMs)
        assertEquals(400L, u.endMs)
        assertFalse(u.definite)
        assertFalse("没有 0x2 标志就不是最后一包", partial.isLast)

        val final = parse(result(json = utteranceJson("你好。", 0, 700, definite = true), last = true))
        assertTrue((final as DoubaoEvent.Result).utterances.single().definite)
        assertTrue("标志 0x2 = 会话结束", final.isLast)
    }

    @Test fun utteranceWithoutStartTimeFallsBackToFirstWord() {
        val body = JSONObject()
            .put("result", JSONObject().put("text", "开会").put("utterances", org.json.JSONArray().put(
                JSONObject().put("text", "开会").put("definite", true)
                    .put("words", org.json.JSONArray()
                        .put(JSONObject().put("start_time", 1700).put("end_time", 1900).put("text", "开"))
                        .put(JSONObject().put("start_time", 1900).put("end_time", 2200).put("text", "会"))),
            )))
        val u = (parse(result(json = body.toString())) as DoubaoEvent.Result).utterances.single()
        assertEquals("缺 start_time 时用第一个字的开始时间", 1700L, u.startMs)
        assertEquals("缺 end_time 时用最后一个字的结束时间", 2200L, u.endMs)
    }

    @Test fun serverErrorPacketIsAFailureNotMalformed() {
        val message = "invalid audio format"
        val event = DoubaoProtocol.parse(errorPacket(45_000_001, message))
        assertTrue(event is DoubaoEvent.Failure)
        assertEquals(45_000_001L, (event as DoubaoEvent.Failure).code)
        assertEquals(message, event.message)
    }

    @Test fun malformedPacketsAreReportedAsMalformed() {
        assertTrue(DoubaoProtocol.parse(ByteArray(2)) is DoubaoEvent.Malformed)
        // 头长度字段被改成 0：解析器不能越界，也不能当成正常结果
        assertTrue(DoubaoProtocol.parse(byteArrayOf(0x01, 0x91.toByte(), 0x11, 0x00)) is DoubaoEvent.Malformed)
        // gzip 段坏掉
        val broken = frame(0x9, 0x1, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertTrue(DoubaoProtocol.parse(broken) is DoubaoEvent.Malformed)
        // 未压缩的坏 JSON
        val notJson = ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x11, 0x90.toByte(), 0x10, 0x00))
            val body = "not json".toByteArray()
            out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
            out.write(body)
        }.toByteArray()
        assertTrue(DoubaoProtocol.parse(notJson) is DoubaoEvent.Malformed)
    }

    // ---------------- 分句 reducer：重复/倒退/旧句 ----------------

    @Test fun sameSlotIsReplacedNotAppended() {
        val slots = SentenceSlots()
        val a = slots.accept("你好", false, 0, 300)
        val b = slots.accept("你好，世", false, 0, 600)
        val c = slots.accept("你好，世界。", true, 0, 900)
        assertNotNull(a)
        assertEquals("你好，世", b!!.text)
        assertTrue(c!!.isFinal)
        assertNull("同样的内容不该再通知界面", slots.accept("你好，世界。", true, 0, 900))
    }

    @Test fun finalizedSentenceIsNeverRewrittenByLatePartial() {
        val slots = SentenceSlots()
        slots.accept("今天下午三点", true, 0, 900)
        assertNull("定稿后的中间结果必须丢弃", slots.accept("今天下午", false, 0, 500))
    }

    @Test fun cumulativeResendOfOldSentencesDoesNotReviveThem() {
        val slots = SentenceSlots()
        slots.accept("第一句。", true, 0, 1000)
        slots.accept("第二句。", true, 1500, 2500)
        // 豆包 result_type=full 每包重发全部历史：这里必须全被水位挡掉，不能每条都再刷一次界面
        assertNull(slots.accept("第一句。", true, 0, 1000))
        assertNull(slots.accept("第二句。", true, 1500, 2500))
        val fresh = slots.accept("第三句", false, 3000, 3400)
        assertNotNull("新句子照常上屏", fresh)
    }

    @Test fun adjacentShortFinalsAreNotMerged() {
        val slots = SentenceSlots()
        // 两个真实的相邻短句，起点只差 350ms：谁也不许并谁，不然其中一句就丢了
        assertNotNull(slots.accept("好。", true, 0, 300))
        assertNotNull(slots.accept("再见。", true, 350, 700))
        assertNotNull("第二句必须自己占一个槽位", slots.accept("走吧。", true, 750, 1000))
    }

    @Test fun sameStartAllowsRepeatedFinalUpdates() {
        val slots = SentenceSlots()
        slots.accept("今天下午三点", true, 0, 900)
        // 协议没有「每句只改写一次」的约定：同一 start 的后续最终结果必须能更新上去
        val itn = slots.accept("今天下午3点", true, 0, 900)
        assertNotNull("同一 start 的最终结果更新不该被丢掉", itn)
        assertEquals("今天下午3点", itn!!.text)
    }

    @Test fun correctedFinalStartKeepsOneRowOnScreen() {
        val slots = SentenceSlots()
        val window = TranscriptWindow()
        // 实测（同一句 17 次修订）：先说到的起点是 260，定稿那次报的真实起点是 80、结束时间一样是 6472。
        // 界面按 startMs 开槽位，所以修好后这句话必须仍然只有一行、并落成定稿。
        slots.accept("你好，这是测试。", false, 260, 6472)?.let { window.apply(it) }
        slots.accept("你好，这是测试。", true, 80, 6472)?.let { window.apply(it) }
        assertEquals("同一句的定稿只许占一行", 1, window.snapshot().size)
        val row = window.snapshot()[0]
        assertEquals("你好，这是测试。", row.text)
        assertTrue("定稿要落到原槽位上", row.isFinal)
        assertEquals("槽位沿用先出现的稳定起点", 260L, row.startMs)
        assertEquals(6472L, row.endMs)
        // 不是按文字去重：真的又说了一遍（自己的时间区间）仍然是一句新话
        slots.accept("你好，这是测试。", true, 9000, 12000)?.let { window.apply(it) }
        assertEquals("时间区间不同的同文句仍是独立一句", 2, window.snapshot().size)
    }

    @Test fun derivedStartMergesOnlyIntoOverlappingPendingSentence() {
        val slots = SentenceSlots()
        slots.accept("三点开会", false, 2000, 2400)
        // 服务商没给 start_time，客户端从 words[0] 推出 2040：与那句未定稿真实重叠 → 沿用槽位
        assertEquals("推导起点与唯一未定稿句重叠就并入", 2000L, slots.resolveDerived(2040, 2500))
        // 差得远、时间区间也不重叠 → 另一句
        assertEquals("不重叠就是新句", 5000L, slots.resolveDerived(5000, 5400))
    }

    @Test fun derivedStartNeverMergesIntoFinalizedSentence() {
        val slots = SentenceSlots()
        slots.accept("已经定稿", true, 0, 1000)
        assertEquals("定稿句不参与推导时间匹配", 1000L, slots.resolveDerived(1000, 1400))
    }

    @Test fun textOnlyCumulativeResultKeepsOneSlot() {
        val slots = SentenceSlots()
        // 没有分句时客户端挂在「当前游标」这一个槽位上反复替换（游标非负，界面不会丢）
        slots.accept("你好", false, slots.liveStart(), null)
        slots.accept("你好，世界", false, slots.liveStart(), null)
        val last = slots.accept("你好，世界。", true, slots.liveStart(), null)
        assertEquals("你好，世界。", last!!.text)
        assertTrue("累积文本的槽位必须非负，否则界面会按「无时间戳」丢掉它", last.startMs >= 0)
        assertNull("同一段累积文本重复到达不再刷新", slots.accept("你好，世界。", true, last.startMs, null))
    }

    // ---------------- 工具 ----------------

    private fun utteranceJson(text: String, start: Long, end: Long?, definite: Boolean): String =
        JSONObject().put("result", JSONObject().put("text", text).put(
            "utterances",
            org.json.JSONArray().put(
                JSONObject().put("text", text).put("definite", definite).put("start_time", start).apply {
                    if (end != null) put("end_time", end)
                },
            ),
        )).toString()

    /** 服务端结果包：类型 0x9，带序号段，gzip 压缩的 JSON 负载。 */
    private fun result(json: String, last: Boolean = false): ByteArray = frame(0x9, 0x1 or if (last) 0x2 else 0, gzip(json.toByteArray()))

    /** 服务端错误包：类型 0xf，`错误码 + 长度 + 消息`，没有序号段。 */
    private fun errorPacket(code: Long, message: String): ByteArray {
        val body = message.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0x00))
        out.write(byteArrayOf((code ushr 24).toByte(), (code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte()))
        out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
        out.write(body)
        return out.toByteArray()
    }

    private fun frame(type: Int, flags: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, ((type shl 4) or flags).toByte(), 0x11, 0x00))
        if (flags and 0x1 != 0) out.write(byteArrayOf(0, 0, 0, 1))          // 序号段
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload)
        return out.toByteArray()
    }

    /** 客户端帧的负载：4 字节头 + 4 字节长度 + 负载（这些帧不带序号段）。 */
    private fun payloadOf(frame: ByteArray): ByteArray {
        val n = ((frame[4].toInt() and 0xff) shl 24) or ((frame[5].toInt() and 0xff) shl 16) or
            ((frame[6].toInt() and 0xff) shl 8) or (frame[7].toInt() and 0xff)
        return frame.copyOfRange(8, 8 + n)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun parse(packet: ByteArray): DoubaoEvent = DoubaoProtocol.parse(packet)
}
