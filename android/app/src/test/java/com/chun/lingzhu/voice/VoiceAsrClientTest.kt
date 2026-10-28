package com.chun.lingzhu.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * 会话级行为：什么时候才允许送音频、结束有没有把尾巴发出去、坏包/报错/断线怎么报。
 *
 * 用假传输层（[FakeTransport]）代替真 socket —— 这些规则都是纯状态机，不需要网络也不需要真机。
 */
class VoiceAsrClientTest {

    /** 记录所有回调，带闩等到指定事件为止。 */
    private class Recorder : VoiceAsrClient.Listener {
        val events = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val transcripts = CopyOnWriteArrayList<VoiceTranscript>()

        override fun onReady() {
            events.add("ready")
            ready.countDown()
        }

        override fun onTranscript(update: VoiceTranscript) {
            events.add("t:" + update.text)
            transcripts.add(update)
        }

        override fun onFinished() {
            events.add("finished")
            finished.countDown()
        }

        override fun onError(message: String) {
            events.add("error:" + message)
            errors.add(message)
        }

        fun awaitReady() = assertTrue("onReady 没到", ready.await(3, TimeUnit.SECONDS))
        fun awaitFinished() = assertTrue("onFinished 没到", finished.await(3, TimeUnit.SECONDS))

        fun awaitError(): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (errors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("onError 没到（收到：$events）", errors.isNotEmpty())
            return errors.last()
        }
    }

    private class FakeTransport : VoiceTransport {
        var events: VoiceTransport.Events? = null
        lateinit var socket: FakeSocket
        var lastUrl = ""
        var lastHeaders: Map<String, String> = emptyMap()

        override fun connect(url: String, headers: Map<String, String>, events: VoiceTransport.Events): VoiceSocket {
            lastUrl = url
            lastHeaders = headers
            this.events = events
            socket = FakeSocket()
            return socket
        }
    }

    private class FakeSocket : VoiceSocket {
        val binary = CopyOnWriteArrayList<ByteArray>()
        val text = CopyOnWriteArrayList<String>()
        var closed = false
        var cancelled = false
        var queue = 0L

        override fun sendBinary(bytes: ByteArray): Boolean {
            binary.add(bytes)
            return true
        }

        override fun sendText(text: String): Boolean {
            this.text.add(text)
            return true
        }

        override fun queuedBytes(): Long = queue

        override fun closeQuietly() {
            closed = true
        }

        override fun cancelNow() {
            cancelled = true
        }
    }

    private fun client(
        provider: String = VoiceAsrConfig.DOUBAO,
        key: String = "test-key-value",
        transport: FakeTransport = FakeTransport(),
        rec: Recorder = Recorder(),
    ): Triple<VoiceAsrClient, FakeTransport, Recorder> =
        Triple(VoiceAsrClient(VoiceAsrConfig(provider, key), rec, transport), transport, rec)

    // ---------------- 就绪之前不许送音频 ----------------

    @Test fun noAudioBeforeServerConfirmsReadiness() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        // 初始化帧发出去了，但服务端还没回包
        assertEquals("应当只发了初始化帧", 1, transport.socket.binary.size)
        assertEquals(0, rec.events.count { it == "ready" })
        assertFalse("就绪前不得接收音频（false = 这一帧没被收下，界面不许显示已上传）", asr.sendPcm(ByteArray(DoubaoProtocol.FRAME_BYTES)))
        assertEquals("就绪前一个音频包都不许发出去", 1, transport.socket.binary.size)

        transport.events!!.binary(doubaoResult(text = "", utterances = "", last = false))
        rec.awaitReady()
        assertTrue("就绪之后才收音频", asr.sendPcm(ByteArray(DoubaoProtocol.FRAME_BYTES)))
        assertEquals("这一帧这才真的进了连接", 2, transport.socket.binary.size)
        asr.cancel()
    }

    @Test fun alibabaWaitsForTaskStartedThenSendsRawPcm() {
        val (asr, transport, rec) = client(provider = VoiceAsrConfig.ALIBABA, key = "dash-key")
        asr.start()
        transport.events!!.opened()
        assertEquals(1, transport.socket.text.size)                 // run-task
        assertFalse("没有 task-started 之前不得接收 PCM", asr.sendPcm(ByteArray(DoubaoProtocol.FRAME_BYTES)))
        assertTrue("更不许发音频", transport.socket.binary.isEmpty())

        transport.events!!.text("""{"header":{"event":"task-started","task_id":"t"}}""")
        rec.awaitReady()
        assertTrue("就绪后接收", asr.sendPcm(ByteArray(DoubaoProtocol.FRAME_BYTES)))
        assertEquals("要以裸 PCM 发出（不加豆包帧头）", DoubaoProtocol.FRAME_BYTES, transport.socket.binary.single().size)
        asr.cancel()
    }

    // ---------------- 结束：尾巴不丢 ----------------

    @Test fun finishSendsLastPacketSoTheFinalSentenceIsNotLost() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        transport.events!!.binary(doubaoResult(text = "", utterances = "", last = false))
        rec.awaitReady()

        asr.sendPcm(ByteArray(1000))            // 不够一帧，留在尾巴里
        asr.finish()

        val last = transport.socket.binary.last()
        assertEquals("最后一包必须是 0x22 标志", 0x22, last[1].toInt() and 0xff)
        asr.cancel()
    }

    @Test fun alibabaFinishSendsTailThenFinishTask() {
        val (asr, transport, rec) = client(provider = VoiceAsrConfig.ALIBABA)
        asr.start()
        transport.events!!.opened()
        transport.events!!.text("""{"header":{"event":"task-started"}}""")
        rec.awaitReady()

        asr.sendPcm(ByteArray(500))
        asr.finish()

        assertEquals("尾巴要以裸 PCM 补发", 500, transport.socket.binary.last().size)
        assertTrue(
            "finish-task 必须发",
            transport.socket.text.any { it.contains("finish-task") },
        )
        asr.cancel()
    }

    @Test fun finishedIsDeliveredOnceWithoutFakingFinalization() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        transport.events!!.binary(doubaoResult(text = "你好", utterances = utterance("你好", 0, 300, definite = false), last = false))
        // 服务商直接宣告结束，但最后一句始终没有 definite=true：客户端不许替它定稿
        transport.events!!.binary(doubaoResult(text = "你好", utterances = "", last = true))
        rec.awaitFinished()
        assertEquals("终态只发一次", 1, rec.events.count { it == "finished" })
        assertEquals("不能有 error", 0, rec.errors.size)
        assertTrue(
            "收尾不许伪造 final：最后那句仍是未定稿",
            rec.transcripts.none { it.text == "你好" && it.isFinal },
        )
        asr.cancel()
    }

    // ---------------- 失败与坏包 ----------------

    @Test fun malformedPacketIsReportedDistinctlyFromProviderFailure() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        transport.events!!.binary(byteArrayOf(0x11, 0x91.toByte(), 0x11))     // 截断包
        val message = rec.awaitError()
        assertTrue("要说清是解析不了的数据包：$message", message.contains("无法解析"))
        asr.cancel()
    }

    @Test fun providerErrorTextIsSurfacedAndStops() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        transport.events!!.binary(errorPacket(45_000_001, "audio format invalid"))
        val message = rec.awaitError()
        assertTrue("要带服务端原因：$message", message.contains("audio format invalid"))
        assertTrue("要有错误码：$message", message.contains("45000001"))
        asr.cancel()
    }

    @Test fun closedBeforeFinalIsAnErrorNotASilentFinish() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        asr.finish()
        transport.events!!.closed(1006)
        val message = rec.awaitError()
        assertTrue("必须说清最后一句可能没转完：$message", message.contains("断开"))
        assertEquals(0, rec.events.count { it == "finished" })
        asr.cancel()
    }

    @Test fun http401SaysKeyIsWrongWithoutLeakingIt() {
        val secret = "super-secret-key-123"
        val (asr, transport, rec) = client(key = secret)
        asr.start()
        transport.events!!.failed(401, "Unauthorized")
        val message = rec.awaitError()
        assertTrue("要提示 Key 无效：$message", message.contains("Key 无效"))
        assertFalse("绝不能把 Key 带进错误文本", message.contains(secret))
        asr.cancel()
    }

    @Test fun cancelSuppressesLateCallbacks() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        val events = transport.events!!
        asr.cancel()
        events.binary(doubaoResult(text = "迟到", utterances = utterance("迟到", 0, 100, definite = true), last = true))
        Thread.sleep(120)
        assertFalse("取消之后不许再上屏", rec.events.any { it.contains("迟到") })
        assertFalse("取消之后不许再报完成", rec.events.contains("finished"))
        assertTrue(transport.socket.cancelled)
    }

    @Test fun sendPcmAfterFinishIsRejected() {
        val (asr, transport, rec) = client()
        asr.start()
        transport.events!!.opened()
        rec.awaitReadyAfter(transport)
        asr.finish()
        assertFalse("结束之后不能再送音频", asr.sendPcm(ByteArray(100)))
        asr.cancel()
    }

    // ---------------- 工具 ----------------

    private fun Recorder.awaitReadyAfter(transport: FakeTransport) {
        transport.events!!.binary(doubaoResult(text = "", utterances = "", last = false))
        awaitReady()
    }

    private fun utterance(text: String, start: Long, end: Long, definite: Boolean): String =
        """[{"text":"$text","start_time":$start,"end_time":$end,"definite":$definite}]"""

    private fun doubaoResult(text: String, utterances: String, last: Boolean): ByteArray {
        // 空串 = 这一包没有 utterances 字段（写死成 `"utterances":` 是非法 JSON，测的就不是这条路径）
        val utt = if (utterances.isBlank()) "" else ""","utterances":$utterances"""
        val json = """{"result":{"text":"$text"$utt}}"""
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, (0x91 or if (last) 0x2 else 0x0).toByte(), 0x11, 0x00))
        out.write(byteArrayOf(0, 0, 0, 1))                       // 序号段
        val payload = gzip(json.toByteArray())
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun errorPacket(code: Long, message: String): ByteArray {
        val body = message.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0x00))
        out.write(byteArrayOf((code ushr 24).toByte(), (code ushr 16).toByte(), (code ushr 8).toByte(), code.toByte()))
        out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
        out.write(body)
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
