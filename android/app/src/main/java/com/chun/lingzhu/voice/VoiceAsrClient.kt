package com.chun.lingzhu.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 一次实时转写会话。**纯 JVM，不碰 android.\***：麦克风采集、权限、界面都在调用方，
 * 这里只负责「把 PCM 按服务商协议送出去，把识别结果按句回吐」。
 *
 * 两个服务商各走各的协议，**互相之间没有兜底**：豆包失败就报豆包失败，绝不偷偷改走阿里；
 * Key 是各自的，也不互相借用（[VoiceAsrConfig] 里就是分开的两把）。
 *
 * 一个实例只跑一次会话（[start] 只认第一次）。要再录就新建一个实例 ——
 * 这样「上一次的回调跑进这一次的界面」这种串台从设计上就不成立。
 *
 * 线程模型：
 *  - [start] / [finish] / [cancel] / [sendPcm] 任意线程可调（采集线程调 [sendPcm]，界面线程调其余）。
 *  - 所有回调都在**同一条后台线程**上按发生顺序投递（`voice-asr-callbacks`），界面自己再 post 到主线程。
 *  - 每次会话一个 generation；[cancel] 让它立刻作废 —— 收尾途中迟到的包不会再冒出
 *    「已经停止却还在上屏」的回调。
 *
 * 就绪 = **服务端确认**，两家都一样：[Listener.onReady] 只在服务端回话之后发一次 ——
 * 豆包是初始化包返回的空结果（实测无需音频即返回），阿里是 `task-started`。
 * 在此之前 **一律不送音频**（阿里文档硬性要求必须先 `task-started`；豆包同样等确认，
 * 这样「界面显示正在录音」和「音频真的在上传」不会脱节）。
 *
 * 边界行为：
 *  - 音频攒够一帧（200ms / 6400 字节）才发；结束把最后一小段补发出去，**尾巴不丢**。
 *  - 建连 + 等服务端就绪有明确时限（[CONNECT_TIMEOUT_MS]）；音频缓冲只有 2 秒上限兜底，
 *    不用「音频积压」当超时手段。
 *  - 收尾有超时：等最终结果最多 [DRAIN_TIMEOUT_MS]，超了明确报「可能不完整」，不假装成功。
 *  - 终态（onFinished / onError）一辈子只发一次。
 */
class VoiceAsrClient internal constructor(
    config: VoiceAsrConfig,
    private val listener: Listener,
    private val transport: VoiceTransport,
) {
    /** 生产路径：OkHttp。 */
    constructor(config: VoiceAsrConfig, listener: Listener) : this(config, listener, OkHttpTransport)

    interface Listener {
        /** 服务端确认这次会话已经就绪。豆包：第一包回包时；阿里：`task-started`。 */
        fun onReady()

        /** 一句识别结果的更新；同一 [VoiceTranscript.startMs] 反复替换，不要往后追加。 */
        fun onTranscript(update: VoiceTranscript)

        /** 正常收尾：服务端已经给出最终结果。 */
        fun onFinished()

        /** 出错/中断。文字可以直接给用户看，里面不会有 Key。 */
        fun onError(message: String)
    }

    /** 会话快照：构造时就定死，中途换设置只影响下一次会话。 */
    private val snapshot = VoiceAsrConfig(config.provider, config.apiKey)
    private val provider = config.providerId
    private val key = config.apiKey.trim()

    private val lock = Any()
    private val slots = SentenceSlots()
    private val carry = ByteArray(DoubaoProtocol.FRAME_BYTES)  // 不够一帧的尾巴
    private var carryLen = 0
    private val delivery = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-asr-callbacks").apply { isDaemon = true }
    }

    private var gen = 0
    private var socket: VoiceSocket? = null
    private var taskId = ""
    private var startCalled = false
    private var connected = false     // socket 已打开且初始化帧已发出
    private var ready = false         // 服务端已确认就绪（onReady 的依据；就绪前不送任何音频）
    private var finishing = false
    private var cancelled = false
    private var done = false
    private var finalSent = false
    private var drain: ScheduledFuture<*>? = null
    private var sawUtterance = false   // 这场对话是否出现过分句（决定还能不能用累积全文兜底）
    private var connectDeadline: ScheduledFuture<*>? = null
    private var openedPending = false    // 传输层在 connect() 内同步回调了 opened()（socket 尚未赋值）

    // ---------------- 对外 ----------------

    fun start() {
        val issue = snapshot.issue()
        val g: Int
        val id: String
        synchronized(lock) {
            if (startCalled) return
            startCalled = true
            gen += 1
            g = gen
            taskId = UUID.randomUUID().toString()
            id = taskId
        }
        if (issue != null) {
            claimTerminal(g)
            post(g) { listener.onError(issue) }
            delivery.shutdown()
            return
        }
        synchronized(lock) {
            connectDeadline = SCHEDULER.schedule(
                { if (alive(g)) fail(g, CONNECT_TIMEOUT_MESSAGE) },
                CONNECT_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        val opened = try {
            transport.connect(
                url = if (provider == VoiceAsrConfig.DOUBAO) VoiceAsrConfig.DOUBAO_URL else VoiceAsrConfig.ALIBABA_URL,
                headers = headersFor(id),
                events = Session(g),
            )
        } catch (e: Exception) {
            fail(g, "打不开语音连接：${friendly(e, 0)}")
            return
        }
        val replay: Boolean
        synchronized(lock) {
            if (g != gen || cancelled || done) {
                replay = false
                socket = null
            } else {
                socket = opened
                // 传输层如果在 connect() 里同步回调了 opened()，这时 socket 还没赋值，先记下来补跑。
                replay = openedPending
                openedPending = false
            }
        }
        if (!replay) {
            if (!alive(g)) opened.cancelNow()
        } else {
            onOpened(g)
        }
    }

    /**
     * 送入一段 16k / 16bit / 单声道 PCM（长度随意，内部按 200ms 切帧）。
     *
     * 返回 false = 这次没送进去（已积压 / 已结束 / 出错）。调用方应当停下采集并提示用户重新开始 ——
     * 本类不会自己补发，也不会丢掉之后继续默默工作。
     *
     * 不保留、不修改调用方的数组：要么交给传输层（内部拷贝），要么复制进自己的缓冲。
     */
    fun sendPcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return true
        var problem: String? = null
        var g = 0
        synchronized(lock) {
            g = gen
            if (!startCalled || finishing || cancelled || done) return false
            // 服务端还没确认就绪就一律拒收：`true` 只能表示「这一帧真的进连接了」，
            // 否则界面会把「攒在本地」误当成「已经上传」。调用方（[VoiceService]）本来也是
            // 等 onReady 才开麦，走到这里只可能是迟到的帧。
            if (!canSendLocked()) return false
            var off = 0
            while (off < pcm.size) {
                val take = minOf(DoubaoProtocol.FRAME_BYTES - carryLen, pcm.size - off)
                System.arraycopy(pcm, off, carry, carryLen, take)
                carryLen += take
                off += take
                if (carryLen == DoubaoProtocol.FRAME_BYTES) {
                    if (!transmitLocked(carry, 0, carryLen)) {
                        problem = BACKPRESSURE
                        break
                    }
                    carryLen = 0
                }
            }
        }
        if (problem != null) {
            fail(g, problem!!)
            return false
        }
        return true
    }

    /**
     * 结束录音。会把最后一小段音频补成「最后一包」发出去，然后等服务端最终结果（有超时）。
     * 之后 [sendPcm] 一律返回 false。
     */
    fun finish() {
        var problem: String? = null
        var g = 0
        synchronized(lock) {
            g = gen
            if (!startCalled || finishing || cancelled || done) return
            finishing = true
            if (canSendLocked() && !flushLocked()) problem = BACKPRESSURE
            scheduleDrainLocked(g)
        }
        if (problem != null) fail(g, problem!!)
    }

    /**
     * 立刻停：关连接、清缓冲、作废当前 generation。之后不会再有回调（已排队的也会被丢掉），
     * 也不会再补发任何东西。可以重复调用。
     */
    fun cancel() {
        val live: VoiceSocket?
        synchronized(lock) {
            cancelled = true
            done = true
            gen += 1
            drain?.cancel(false)
            drain = null
            connectDeadline?.cancel(false)
            connectDeadline = null
            live = socket
            socket = null
            carryLen = 0
        }
        live?.cancelNow()
        delivery.shutdown()
    }

    // ---------------- 发送 ----------------

    private fun headersFor(id: String): Map<String, String> = if (provider == VoiceAsrConfig.DOUBAO) {
        // 新版控制台的 X-Api-Key；老的 app id / access token 那一套不再需要。
        mapOf(
            "X-Api-Key" to key,
            "X-Api-Resource-Id" to VoiceAsrConfig.DOUBAO_RESOURCE_ID,
            "X-Api-Request-Id" to id,
        )
    } else {
        mapOf("Authorization" to "Bearer $key")
    }

    /**
     * 现在能不能往连接里送音频：**必须服务端确认就绪**（两家一致，见类注释）。
     * 就绪之前不缓存、不假装收下 —— 见 [sendPcm]。
     */
    private fun canSendLocked(): Boolean = socket != null && connected && ready

    /** 就绪时如果已经在收尾（用户在连上之前就点了停止），把「最后一包」补上。 */
    private fun flushLocked(): Boolean {
        if (!canSendLocked()) return true
        if (finishing && !finalSent) return sendFinalLocked()
        return true
    }

    private fun transmitLocked(pcm: ByteArray, off: Int, count: Int): Boolean {
        val live = socket ?: return false
        if (count <= 0) return true
        if (live.queuedBytes() + DoubaoProtocol.FRAME_BYTES > MAX_SOCKET_PENDING) return false
        return if (provider == VoiceAsrConfig.DOUBAO) {
            live.sendBinary(DoubaoProtocol.audioFrame(pcm, off, count, false))
        } else {
            live.sendBinary(pcm.copyOfRange(off, off + count))   // 阿里：裸 PCM，不加任何 framing
        }
    }

    private fun sendFinalLocked(): Boolean {
        val live = socket ?: return false
        finalSent = true
        return if (provider == VoiceAsrConfig.DOUBAO) {
            // 最后一包音频必须用 11 22 11 00 发出去（carryLen 可以是 0）。不发这一帧
            // 服务端就不知道说完了，最后半句永远不出结果。
            val tail = DoubaoProtocol.audioFrame(carry, 0, carryLen, true)
            carryLen = 0
            live.sendBinary(tail)
        } else {
            // 阿里：先把不够一帧的尾巴当普通 PCM 发掉，再发 finish-task。
            // 顺序不能反 —— 反了服务端先收尾，这段尾巴就被丢了。
            val tailOk = carryLen <= 0 || live.sendBinary(carry.copyOfRange(0, carryLen))
            carryLen = 0
            tailOk && live.sendText(AlibabaProtocol.finishTask(taskId))
        }
    }

    // ---------------- 接收 ----------------

    /** 传输层事件 → 会话状态。所有事件都带 generation，旧会话的事件直接丢。 */
    private inner class Session(private val g: Int) : VoiceTransport.Events {
        override fun opened() {
            synchronized(lock) {
                if (g != gen || cancelled || done) return
                // 传输层可能在 transport.connect() 里同步回调：那时 socket 还没赋值，先记一笔由 [start] 补跑。
                if (socket == null) {
                    openedPending = true
                    return
                }
            }
            onOpened(g)
        }

        override fun binary(bytes: ByteArray) {
            // 豆包是二进制协议；阿里只回文本，二进制包一律忽略（不是错误，只是不该出现）。
            if (provider != VoiceAsrConfig.DOUBAO) return
            if (!alive(g)) return
            when (val event = DoubaoProtocol.parse(bytes)) {
                is DoubaoEvent.Malformed -> fail(g, "语音服务返回了无法解析的数据包（${event.why}）")
                is DoubaoEvent.Failure -> fail(g, providerFailure(event.code, event.message))
                is DoubaoEvent.Result -> onDoubaoResult(g, event)
            }
        }

        override fun text(text: String) {
            if (provider != VoiceAsrConfig.ALIBABA) return
            if (!alive(g)) return
            when (val event = AlibabaProtocol.parse(text)) {
                AlibabaEvent.Started -> markReady(g)
                AlibabaEvent.Finished -> finishOk(g)
                AlibabaEvent.Ignored -> Unit
                is AlibabaEvent.Malformed -> fail(g, "语音服务返回了无法解析的数据包（${event.why}）")
                is AlibabaEvent.Failed -> fail(g, providerFailure(0, event.describe()))
                is AlibabaEvent.Sentence -> {
                    // 能拿到句子就说明任务早就开始了（task-started 万一丢包/被合并，界面不至于卡在「连接中」）。
                    markReady(g)
                    onAlibabaSentence(g, event)
                }
            }
        }

        override fun closed(code: Int) {
            if (!alive(g)) return
            fail(
                g,
                if (finishing) {
                    "语音服务在给出最终结果前断开了连接（代码 $code），最后一句可能没转完"
                } else {
                    "语音连接被服务端关闭了（代码 $code）"
                },
            )
        }

        override fun failed(httpCode: Int, reason: String) {
            fail(g, friendly(reason, httpCode))
        }
    }

    /** 连接真的开了：发初始化帧（豆包）/ run-task（阿里），然后等**服务端**确认就绪。 */
    private fun onOpened(g: Int) {
        val live: VoiceSocket
        val id: String
        synchronized(lock) {
            if (g != gen || cancelled || done) return
            live = socket ?: return
            id = taskId
            // 先标 connected，再发帧：发送失败要能走到 fail()，而不是留下「半连上」状态。
            connected = true
        }
        val sent = if (provider == VoiceAsrConfig.DOUBAO) {
            live.sendBinary(DoubaoProtocol.fullClientRequest(DoubaoProtocol.initPayload(id)))
        } else {
            live.sendText(AlibabaProtocol.runTask(id))
        }
        if (!sent) fail(g, "语音连接刚建立就发不出请求，请重新开始")
    }
    /**
     * 服务端确认就绪：发一次 onReady、撤掉建连计时器、把攒着的音频倒出去。
     *
     * 两家都走这里 —— 豆包是初始化包返回的那个（可能文本为空的）结果，阿里是 `task-started`。
     * onReady 排进投递队列后才会送音频，所以界面「看到开始录音」和「音频真的在上传」是同一时刻。
     */
    private fun markReady(g: Int) {
        var problem: String? = null
        synchronized(lock) {
            if (g != gen || cancelled || done || ready) return
            ready = true
            connectDeadline?.cancel(false)
            connectDeadline = null
            post(g) { listener.onReady() }
            if (!flushLocked()) problem = BACKPRESSURE
        }
        if (problem != null) fail(g, problem!!)
    }

    private fun onAlibabaSentence(g: Int, e: AlibabaEvent.Sentence) {
        if (e.heartbeat) return                       // 心跳包不含新文本
        var update: VoiceTranscript? = null
        synchronized(lock) {
            if (g != gen || cancelled || done) return
            // 服务商直接给 begin_time 就是这句的身份，不做任何近似；
            // 连字级时间都没有时才挂到当前还没定稿的那一句（绝不新开一段，否则重复上屏）。
            val start = e.beginMs ?: slots.lastUnfinalized()?.startMs ?: slots.liveStart()
            update = slots.accept(e.text, e.sentenceEnd, start, e.endMs)
        }
        val u = update ?: return
        post(g) { listener.onTranscript(u) }
    }

    private fun onDoubaoResult(g: Int, e: DoubaoEvent.Result) {
        if (!alive(g)) return
        // 回包了 = 服务端接上了（初始化包那一次 text 为空、没有分句，只有 result 字段也算）。
        markReady(g)
        val updates = ArrayList<VoiceTranscript>(e.utterances.size)
        synchronized(lock) {
            if (g != gen || cancelled || done) return
            if (e.utterances.isNotEmpty()) {
                // 分句是**累积**列表（result_type=full），每个包都把历史全重发一遍，绝不能逐条 append；
                // 每条都过 SentenceSlots（按稳定 start upsert + 定稿水位），只收集真正变了的槽位。
                sawUtterance = true
                for (u in e.utterances) {
                    val start = when {
                        u.startMs == null ->
                            // 服务商连字级时间都没给：挂到当前未定稿那句上，绝不新开一段
                            slots.lastUnfinalized()?.startMs ?: slots.liveStart()
                        u.startDerived ->
                            // 开始时间由 words 推导（服务商没给 start_time）：只有与唯一一句未定稿
                            // 句子真实重叠才并入，其余当新句 —— 相邻短句起点可能只差几十毫秒。
                            slots.resolveDerived(u.startMs, u.endMs)
                        else -> u.startMs          // 服务商直接给的时间就是这句的身份，不近似
                    }
                    slots.accept(u.text, u.definite, start, u.endMs)?.let(updates::add)
                }
            } else if (e.text.isNotBlank() && !sawUtterance) {
                // 只有累积 result.text、没有分句（防御分支：豆包正常都会给 utterances）：
                // 挂在「当前游标」这一个槽位上反复替换，不追加就不会出重复。
                // isFinal 一律 false：没有分句就没有 `definite`，**收尾包不算定稿依据**。
                //
                // 只在**整场都没见过分句**时才走这条：一旦按句上过屏，再拿累积全文当一句插进来
                // 就是把前面所有句子重抄一遍。
                slots.accept(e.text, false, slots.liveStart(), null)?.let(updates::add)
            }
        }
        updates.forEach { update -> post(g) { listener.onTranscript(update) } }
        if (e.isLast) finishOk(g)
    }

    // ---------------- 终态 ----------------

    /**
     * 正常收尾：服务商说这次会话结束了。
     *
     * **不替服务商定稿**：`definite` / `sentence_end` 只有服务商能说，把 partial 升成 final
     * 是在假装「这句已经准了」。还没定稿的句子原样留在界面（界面本来就有「识别中」的样子）。
     */
    private fun finishOk(g: Int) {
        if (!claimTerminal(g)) return
        val live = synchronized(lock) { socket.also { socket = null } }
        live?.closeQuietly()
        post(g) { listener.onFinished() }
        delivery.shutdown()
    }

    private fun fail(g: Int, message: String) {
        if (!claimTerminal(g)) return
        val live = synchronized(lock) { socket.also { socket = null } }
        live?.cancelNow()
        val text = snapshot.scrub(message)
        post(g) { listener.onError(text) }
        delivery.shutdown()
    }

    /** 抢占终态。抢不到（已结束 / 已取消 / 换代）返回 false —— 保证终态只发一次。 */
    private fun claimTerminal(g: Int): Boolean = synchronized(lock) {
        if (g != gen || cancelled || done) {
            false
        } else {
            done = true
            drain?.cancel(false)
            drain = null
            connectDeadline?.cancel(false)
            connectDeadline = null
            true
        }
    }

    private fun alive(g: Int): Boolean = synchronized(lock) { g == gen && !cancelled && !done }

    private fun scheduleDrainLocked(g: Int) {
        if (drain != null) return
        drain = SCHEDULER.schedule(
            {
                val message = synchronized(lock) {
                    // 一次都没就绪 = 服务端根本没接上（这时说「结果不完整」会让人以为已经录到了东西）。
                    if (ready) {
                        "收尾超时：语音服务没有在 ${DRAIN_TIMEOUT_MS / 1000} 秒内给出最终结果，本次转写可能不完整"
                    } else {
                        "语音服务一直没有就绪（没收到开始确认），本次没有转写结果"
                    }
                }
                if (alive(g)) fail(g, message)
            },
            DRAIN_TIMEOUT_MS.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 回调统一排队投递：一条后台线程、按发生顺序。取消之后已排队的直接作废 ——
     * 用户点了「停止」，屏幕上就不该再冒出新的一句话。
     */
    private fun post(g: Int, action: () -> Unit) {
        try {
            delivery.execute {
                val ok = synchronized(lock) { g == gen && !cancelled }
                if (ok) action()
            }
        } catch (_: Exception) {
            // 线程池已关 = 本次会话已收尾，丢掉即可
        }
    }

    // ---------------- 错误文案 ----------------

    private fun providerFailure(code: Long, message: String): String {
        val reason = message.trim().ifEmpty { "服务端没有给出原因" }
        return if (code > 0) "语音服务返回错误 $code：$reason" else "语音服务报错：$reason"
    }

    private fun friendly(t: Throwable, httpCode: Int): String = when {
        httpCode == 401 || httpCode == 403 ->
            "语音服务拒绝了这次连接（HTTP $httpCode）：Key 无效、过期，或没有开通这项语音服务"
        httpCode > 0 -> "连不上语音服务（HTTP $httpCode）"
        t is UnknownHostException -> "连不上网：找不到语音服务的地址"
        t is SocketTimeoutException -> "语音服务太久没回应（超时）"
        t is SSLException -> "加密握手失败 —— 检查手机时间是否准确"
        t is IOException -> "网络中断了（${t.message ?: t.javaClass.simpleName}）"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun friendly(reason: String, httpCode: Int): String = when {
        httpCode == 401 || httpCode == 403 ->
            "语音服务拒绝了这次连接（HTTP $httpCode）：Key 无效、过期，或没有开通这项语音服务"
        httpCode > 0 -> "连不上语音服务（HTTP $httpCode）"
        reason.isNotBlank() -> "语音连接失败：$reason"
        else -> "语音连接失败"
    }

    companion object {
        /** 已交给传输层还没发出去的字节上限：约 3 秒音频（豆包 gzip 之后比裸 PCM 小）——
         *  超了就是网络跟不上，返回 false 让采集停下，不补发、不排队。 */
        private const val MAX_SOCKET_PENDING = 96_000L

        /** 等最终结果的时限。正常都在 1 秒内，这里留足余量；超了如实报「可能不完整」。 */
        private const val DRAIN_TIMEOUT_MS = 8_000L

        /**
         * 建连 + 等服务端就绪的时限。网络正常时远小于这个数（实测豆包初始化回包在 1 秒内），
         * 设 12 秒是给「刚切网 / 弱网握手」留余量；超了就明确失败让用户重开，
         * 不靠音频积压凑数、也不静默等待。
         */
        private const val CONNECT_TIMEOUT_MS = 12_000L

        private const val CONNECT_TIMEOUT_MESSAGE =
            "语音服务 12 秒内没有就绪（连接或认证没完成），本次转写已停止（不会补发，请重新开始）"

        private const val BACKPRESSURE =
            "网络太慢，录音已经积压超过 3 秒，本次转写已停止（不会补发，请重新开始）"

        private val SCHEDULER: java.util.concurrent.ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "voice-asr-deadline").apply { isDaemon = true }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 传输层：只有这一小块知道 OkHttp。会话逻辑（重发/背压/终态/去重）与它无关，才测得了。
// ---------------------------------------------------------------------------

/** 一条已建立（或将建立）的语音连接。实现方负责线程安全。 */
internal interface VoiceSocket {
    fun sendBinary(bytes: ByteArray): Boolean
    fun sendText(text: String): Boolean

    /** 还没发出去的字节数，用来判断积压。 */
    fun queuedBytes(): Long

    /** 正常关闭（发关闭帧，让对方把剩余结果发完）。 */
    fun closeQuietly()

    /** 立刻断，丢掉未发送的数据。 */
    fun cancelNow()
}

internal interface VoiceTransport {
    interface Events {
        fun opened()
        fun binary(bytes: ByteArray)
        fun text(text: String)

        /** 对端/本端关闭。[code] 是 WebSocket 关闭码。 */
        fun closed(code: Int)

        /** 建连失败或连接断开。[httpCode] 为 0 表示不是 HTTP 层的问题（DNS、超时、断线）。 */
        fun failed(httpCode: Int, reason: String)
    }

    /** 异步建连：返回的连接会稍后通过 [events] 通知 opened/failed。 */
    fun connect(url: String, headers: Map<String, String>, events: Events): VoiceSocket
}

/**
 * OkHttp 实现。用它的理由只有一条：握手要带自定义请求头（豆包 `X-Api-Key` / 阿里 `Authorization`），
 * 而 Android 上能用的 JDK WebSocket 既加不了头、minSdk 26 也没有。
 *
 * 读超时设 0：长连接空闲不等于坏了；收尾时限由会话自己的计时器管。
 */
internal object OkHttpTransport : VoiceTransport {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun connect(url: String, headers: Map<String, String>, events: VoiceTransport.Events): VoiceSocket {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return Adapter(client.newWebSocket(request, Bridge(events)))
    }

    private class Adapter(private val ws: WebSocket) : VoiceSocket {
        override fun sendBinary(bytes: ByteArray): Boolean = ws.send(bytes.toByteString())
        override fun sendText(text: String): Boolean = ws.send(text)
        override fun queuedBytes(): Long = ws.queueSize()
        override fun closeQuietly() {
            try { ws.close(1000, null) } catch (_: Exception) {}
        }

        override fun cancelNow() {
            try { ws.cancel() } catch (_: Exception) {}
        }
    }

    private class Bridge(private val events: VoiceTransport.Events) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = events.opened()

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = events.binary(bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = events.text(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = events.closed(code)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.closed(code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code ?: 0
            // 握手失败时响应体归调用方关（OkHttp 文档要求），不关就是连接泄漏。
            try { response?.close() } catch (_: Exception) {}
            events.failed(code, t.message ?: t.javaClass.simpleName)
        }
    }
}

// ---------------------------------------------------------------------------
// 阿里云百炼 Paraformer 实时识别：文本 JSON 事件 + 裸 PCM
// ---------------------------------------------------------------------------

internal sealed interface AlibabaEvent {
    data object Started : AlibabaEvent
    data object Finished : AlibabaEvent
    data object Ignored : AlibabaEvent

    data class Sentence(
        val text: String,
        val beginMs: Long?,
        val endMs: Long?,
        val heartbeat: Boolean,
        val sentenceEnd: Boolean,
    ) : AlibabaEvent

    data class Failed(val code: String, val message: String) : AlibabaEvent {
        fun describe(): String = if (code.isBlank()) message else "$message（$code）"
    }

    data class Malformed(val why: String) : AlibabaEvent
}

/**
 * Paraformer 实时识别的 WebSocket 事件协议。纯 JVM，单测可直接用字符串构造/解析。
 *
 * 顺序是硬的：`run-task` → 等 `task-started` → **之后**才发裸 PCM（不能 gzip、不能加豆包那种帧头）
 * → 要结束时发 `finish-task` → 等 `task-finished`。中途 `task-failed` 就是真失败，不换服务商。
 */
internal object AlibabaProtocol {

    fun runTask(taskId: String): String = JSONObject()
        .put("header", JSONObject().put("action", "run-task").put("task_id", taskId).put("streaming", "duplex"))
        .put(
            "payload",
            JSONObject()
                .put("task_group", "audio")
                .put("task", "asr")
                .put("function", "recognition")
                .put("model", VoiceAsrConfig.ALIBABA_MODEL)
                .put("parameters", JSONObject().put("format", "pcm").put("sample_rate", 16_000))
                .put("input", JSONObject()),
        )
        .toString()

    fun finishTask(taskId: String): String = JSONObject()
        .put("header", JSONObject().put("action", "finish-task").put("task_id", taskId).put("streaming", "duplex"))
        .put("payload", JSONObject().put("input", JSONObject()))
        .toString()

    fun parse(text: String): AlibabaEvent {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return AlibabaEvent.Malformed("不是合法 JSON")
        }
        val header = json.optJSONObject("header") ?: return AlibabaEvent.Malformed("缺少 header")
        return when (header.optString("event", "")) {
            "task-started" -> AlibabaEvent.Started
            "task-finished" -> AlibabaEvent.Finished
            "task-failed" -> AlibabaEvent.Failed(
                code = header.optString("error_code", ""),
                message = header.optString("error_message", "").trim().ifEmpty { "服务端没有给出原因" },
            )
            "result-generated" -> {
                val sentence = json.optJSONObject("payload")
                    ?.optJSONObject("output")
                    ?.optJSONObject("sentence")
                    ?: return AlibabaEvent.Ignored          // 只有 usage、没有 sentence 的包
                AlibabaEvent.Sentence(
                    text = sentence.optString("text", ""),
                    beginMs = ms(sentence, "begin_time"),
                    endMs = ms(sentence, "end_time"),
                    heartbeat = sentence.optBoolean("heartbeat", false),
                    sentenceEnd = sentence.optBoolean("sentence_end", false),
                )
            }
            "" -> AlibabaEvent.Malformed("事件类型为空")
            else -> AlibabaEvent.Ignored                  // 不认识的新事件：忽略，不当成协议错误
        }
    }

    /** `begin_time` / `end_time` 可能是 null（中间结果常见）。 */
    private fun ms(o: JSONObject, name: String): Long? {
        if (!o.has(name) || o.isNull(name)) return null
        val v = o.optLong(name, -1)
        return if (v >= 0) v else null
    }
}
