package com.chun.lingzhu

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 常连（ling-link v1）的**传输层**：hello 报到 → 25 秒长轮询拿指令 → 在**独立单线程**里串行执行 →
 * 把结果回给电脑。界面与前台服务只负责「开 / 关」和显示状态，协议细节全在这里。
 *
 * 三条硬约束，代码结构就是照着它们长的：
 *  1. **轮询线程绝不执行指令**：电脑下发的工具可能要等用户确认 20 秒、要截屏、要写库；
 *     放在轮询线程里会让心跳停摆，电脑那头立刻判「手机离线」。所以执行交给 [exec]（单线程 = 串行，
 *     不会两条指令同时动同一份数据），轮询线程把指令丢进队列就立刻回去继续长轮询。
 *  2. **停止要立刻**：`stop()` 置位、叫醒退避等待、断开在途连接，并让 [LinkDispatcher.cancel] 收掉
 *     待确认的弹窗。用户关掉开关后不可能再多跑一次指令。
 *  3. **认证失败不重试**：401 表示电脑那头已经不认识这条配对了，重试一万次也还是 401，
 *     只会把「配对已失效」伪装成「网络不好」。这里直接停，交给界面提示重新配对。
 *
 * 安全：请求 / 响应都是 `{v:1,pairId,iv,data}` 信封，AES-256-GCM（AAD 固定 `ling-link-v1`），
 * 每次请求与每次响应都用新的随机 IV；不记日志、不打印 URI 与请求体。
 */
class LingLink(
    context: Context,
    private val pair: LinkPairStore.Pair,
    private val dispatch: LinkDispatcher,
    private val onState: (String) -> Unit,
) {

    private val app: Context = context.applicationContext
    private val store = LinkPairStore(app)
    /** 执行指令的串行通道：**不是**轮询线程（见类注释第 1 条）。 */
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ling-link-exec").apply { isDaemon = true }
    }

    @Volatile private var stopped = false
    private val running = AtomicBoolean(false)
    @Volatile private var conn: HttpURLConnection? = null
    private val connLock = Any()
    @Volatile private var wake: CountDownLatch? = null

    /** 已执行完、等下一次 poll 一起带回去的指令 id。只放「电脑接受了结果」的 id。 */
    private val acks = LinkedHashSet<String>()

    /** hello 回给我们的长轮询时长；读超时在它之上再加余量。 */
    @Volatile private var pollTimeoutMs = DEFAULT_POLL_TIMEOUT_MS

    /** 界面用：401 之后不再自动重试，直到用户重新配对。 */
    @Volatile var authFailed = false
        private set

    /** `stop()` 之后迟到的 IO 异常不该被当成「断网重连」，用这个标记区分。 */
    private class StopSignal : Exception("已停止")

    private class AuthFailure(message: String) : Exception(message)

    private class WireFailure(message: String, val code: Int = 0) : Exception(message)

    /**
     * 阻塞运行：报到，然后一直长轮询到 [stop] 被调、或电脑返回 401。
     * 调用方负责放在后台线程（[LinkService] 的常连线程）。
     */
    fun run() {
        if (!running.compareAndSet(false, true)) return
        var backoffIdx = 0
        try {
            while (!stopped) {
                try {
                    hello()
                    backoffIdx = 0
                    publish("已连接 · 等电脑的指令")
                    pollLoop()
                    // pollLoop 只在 stopped / 401 时正常返回，回到这里再看循环条件
                } catch (_: StopSignal) {
                    return
                } catch (e: AuthFailure) {
                    authFailed = true
                    publish("配对已失效（电脑返回 401）· 需要重新配对")
                    return
                } catch (e: Exception) {
                    if (stopped) return
                    val waitMs = BACKOFF_MS[backoffIdx.coerceAtMost(BACKOFF_MS.lastIndex)]
                    backoffIdx++
                    publish("连接断开：${reason(e)}，${waitMs / 1000} 秒后重试")
                    if (!sleepInterruptible(waitMs)) return
                }
            }
        } finally {
            running.set(false)
        }
    }

    /** 立即停止：叫醒退避、断开在途连接、取消待确认的指令。幂等。 */
    fun stop() {
        stopped = true
        wake?.countDown()
        synchronized(connLock) {
            runCatching { conn?.disconnect() }
            conn = null
        }
        runCatching { dispatch.cancel() }
        exec.shutdownNow()
    }

    /**
     * 网络恢复 / 切网时由 [LinkService] 的网络回调调用：立刻打断当前长轮询和退避等待，
     * 马上重新报到。不做「等下一轮再说」—— 否则最长要等 30 秒退避。
     */
    fun reconnectNow() {
        if (stopped) return
        wake?.countDown()
        synchronized(connLock) {
            runCatching { conn?.disconnect() }
            conn = null
        }
    }

    /**
     * 解除配对：把 unpair 发给电脑后**立刻返回**，不因网络失败而改变「本机已解除」这个结果
     * （调用方随后 [LinkPairStore.clear]）。电脑若没收到，它那边的配对会在下次请求时 401 自行失效。
     */
    fun unpairBlocking() {
        try {
            post(KIND_UNPAIR, JSONObject(), readTimeoutMs = 10_000)
        } catch (_: Exception) {
            // 网络不通 / 已被电脑删掉都算「已解除」：本机的密钥无论如何都要清掉。
        }
    }

    // ---------------- 协议流程 ----------------

    private fun hello() {
        val device = JSONObject()
            .put("model", Build.MODEL ?: "")
            .put("app", appLabel())
            .put("caps", diffCapabilities())
            .put("apps", enabledApps())
        val resp = post(KIND_HELLO, JSONObject().put("device", device), readTimeoutMs = NORMAL_READ_TIMEOUT_MS)
        val pt = resp.opt("pollTimeoutMs")
        if (pt is Number) {
            // 电脑给的时长只要合理就照用；异常值退回默认（不因为对端一个怪数字把超时拉到几小时）。
            val v = pt.toLong()
            pollTimeoutMs = if (v in 5_000..120_000) v else DEFAULT_POLL_TIMEOUT_MS
        }
        // serverTime 目前不参与逻辑：手机不回拨系统时钟，只有请求里的 at 参与 ±90 秒校验。
    }

    private fun pollLoop() {
        while (!stopped) {
            val pending = synchronized(acks) { acks.toList() }
            val ackArr = JSONArray()
            pending.forEach { ackArr.put(it) }
            val resp = post(
                KIND_POLL,
                JSONObject().put("acks", ackArr),
                readTimeoutMs = (pollTimeoutMs + POLL_READ_SLACK_MS).toInt(),
            )
            // 只有这一轮真的送出去了才清 ack：失败时留着下一轮再捎（电脑对重复 ack 幂等）。
            synchronized(acks) { acks.removeAll(pending.toSet()) }
            val cmds = resp.optJSONArray("cmds") ?: JSONArray()
            for (i in 0 until cmds.length()) {
                val cmd = cmds.optJSONObject(i) ?: continue
                enqueue(cmd)
            }
        }
    }

    /** hello 里报的 app 名单：只带「本机装了 + 用户在本页打开」的别名。 */
    private fun enabledApps(): JSONArray {
        val declared = try { dispatch.apps() } catch (_: Exception) { null } ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until declared.length()) {
            val o = declared.optJSONObject(i) ?: continue
            if (o.optBoolean("on", false)) out.put(o)
        }
        return out
    }

    /** 丢给执行线程就回来继续长轮询 —— 绝不在这里等执行结果。 */
    private fun enqueue(cmd: JSONObject) {
        val id = cmd.optString("id", "")
        if (id.isEmpty()) return
        try {
            exec.execute {
                if (stopped) return@execute
                val result = try {
                    dispatch.execute(cmd)
                } catch (e: Throwable) {
                    JSONObject()
                        .put("id", id)
                        .put("ok", false)
                        .put("error", JSONObject().put("code", "INTERNAL").put("message", brief(e)))
                        .put("confirm", "n/a")
                }
                if (stopped) return@execute
                sendResult(result, id)
            }
        } catch (_: Exception) {
            // 线程池已关闭（正在停止）：这条指令不再执行，也不假装成功。
        }
    }

    /**
     * 回指令结果。只搬协议里的几个字段，不把 dispatcher 内部对象整个递出去。
     * 网络失败重试一次（每次都是新 IV，不会撞上电脑的重放检查）；仍失败就只更新状态，
     * 电脑那边看到的只是「结果没回来」，不会因此重发同一条指令。
     */
    private fun sendResult(out: JSONObject, cmdId: String) {
        val inner = JSONObject()
            .put("id", cmdId)
            .put("ok", out.optBoolean("ok", false))
            .put("confirm", normalizeConfirm(out.optString("confirm", "")))
        if (out.optBoolean("ok", false)) {
            val r = out.optJSONObject("result")
            inner.put("result", r ?: JSONObject())
        } else {
            val e = out.optJSONObject("error")
            inner.put(
                "error",
                JSONObject()
                    .put("code", e?.optString("code")?.ifBlank { "INTERNAL" } ?: "INTERNAL")
                    .put("message", e?.optString("message")?.take(MAX_ERROR_CHARS) ?: "执行失败"),
            )
        }
        var last: Exception? = null
        for (attempt in 0..1) {
            if (stopped) return
            try {
                val resp = post(KIND_RESULT, inner, readTimeoutMs = NORMAL_READ_TIMEOUT_MS)
                if (resp.optBoolean("accepted", false)) {
                    synchronized(acks) { acks.add(cmdId) }
                }
                return
            } catch (e: AuthFailure) {
                authFailed = true
                publish("配对已失效（电脑返回 401）· 需要重新配对")
                return
            } catch (e: Exception) {
                last = e
                if (attempt == 0) sleepInterruptible(700)
            }
        }
        if (!stopped) publish("结果没送达电脑：${reason(last ?: WireFailure("未知错误"))}")
    }

    // ---------------- HTTP + 加密信封 ----------------

    private fun post(kind: String, extra: JSONObject, readTimeoutMs: Int): JSONObject {
        if (stopped) throw StopSignal()
        if (pair.key.size != LinkPairStore.KEY_BYTES) throw WireFailure("配对密钥长度不对，请重新配对")
        val inner = JSONObject()
            .put("v", PROTOCOL_VERSION)
            .put("pairId", pair.id)
            .put("kind", kind)
            .put("seq", store.nextSeq())
            .put("at", System.currentTimeMillis())
        extra.keys().forEach { k -> inner.put(k, extra.opt(k)) }
        val body = LinkWire.seal(pair.key, inner).toByteArray(Charsets.UTF_8)
        if (body.size > LinkWire.MAX_ENVELOPE) throw WireFailure("请求过大，已拒绝发送")

        val url = URL("http://${pair.host}:${pair.port}/ling-link/v1/$kind")
        var c: HttpURLConnection? = null
        try {
            c = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                this.readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                setFixedLengthStreamingMode(body.size)
            }
            synchronized(connLock) {
                if (stopped) throw StopSignal()
                conn = c
            }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 非 200 是明文错误页（不含密文），只按状态码分类，不尝试解密。
                when (code) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> throw AuthFailure("配对凭据不被接受")
                    HttpURLConnection.HTTP_CONFLICT -> throw WireFailure("请求被电脑判为重放（会有新的 IV，稍后自动重试）", code)
                    HttpURLConnection.HTTP_BAD_REQUEST -> throw WireFailure("电脑拒绝了请求（格式或时钟）", code)
                    HTTP_TOO_LARGE -> throw WireFailure("请求超过 1 MiB，已被电脑拒绝", code)
                    HTTP_BUSY -> throw WireFailure("电脑忙（队列已满），稍后重试", code)
                    else -> throw WireFailure("电脑返回 HTTP $code", code)
                }
            }
            val raw = readCapped(c.inputStream)
            val resp = try {
                LinkWire.open(pair.key, String(raw, Charsets.UTF_8), kind, pair.id)
            } catch (e: LinkWire.Rejected) {
                throw WireFailure(e.message ?: "响应校验失败")
            }
            if (!resp.optBoolean("ok", false)) throw WireFailure("电脑回执 ok=false")
            return resp
        } catch (e: StopSignal) {
            throw e
        } catch (e: AuthFailure) {
            throw e
        } catch (e: WireFailure) {
            throw e
        } catch (e: Exception) {
            if (stopped) throw StopSignal()
            throw WireFailure(networkReason(e))
        } finally {
            synchronized(connLock) {
                if (conn === c) conn = null
            }
            runCatching { c?.disconnect() }
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > LinkWire.MAX_ENVELOPE) throw WireFailure("电脑返回的数据超过 1 MiB，已停止")
        }
        out.toByteArray()
    }

    /** 断网 / 超时 / 被中断各自说清楚，别把「网络断了」说成「电脑关了」。 */
    private fun networkReason(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "等待电脑回应超时"
        is java.net.ConnectException -> "连不上电脑（同网段？电脑上的常连开关开了？）"
        is java.net.NoRouteToHostException -> "到电脑的路由不通（可能换了 Wi-Fi）"
        is java.net.UnknownServiceException -> "连接被系统拦下"
        is java.io.IOException -> "网络中断"
        else -> "连接出错"
    }

    /** 退避等待，可被 [stop] / [reconnectNow] 立刻打断。返回 false = 已经停止。 */
    private fun sleepInterruptible(ms: Long): Boolean {
        if (stopped) return false
        val latch = CountDownLatch(1)
        wake = latch
        try {
            latch.await(ms, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            wake = null
        }
        return !stopped
    }

    private fun publish(text: String) {
        runCatching { onState(text) }
    }

    private fun appLabel(): String =
        try {
            val pm = app.packageManager
            val info = pm.getPackageInfo(app.packageName, 0)
            "${app.packageName}/${info.versionName ?: "?"}"
        } catch (_: Exception) {
            app.packageName
        }

    /** 真正能用的能力才报给电脑 —— 报了却做不了，等于让电脑点一个死按钮。 */
    private fun diffCapabilities(): JSONArray {
        val caps = JSONArray()
        val c = try { dispatch.capabilities() } catch (_: Exception) { null }
        val declared = c?.optJSONArray("caps")
        if (declared != null) {
            for (i in 0 until declared.length()) {
                val v = declared.opt(i)
                if (v is String && v.isNotBlank()) caps.put(v)
            }
        }
        if (caps.length() == 0) {
            caps.put("memory"); caps.put("todo")
        }
        return caps
    }

    companion object {
        const val KIND_HELLO = "hello"
        const val KIND_POLL = "poll"
        const val KIND_RESULT = "result"
        const val KIND_UNPAIR = "unpair"

        const val PROTOCOL_VERSION = 1
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val NORMAL_READ_TIMEOUT_MS = 15_000
        private const val DEFAULT_POLL_TIMEOUT_MS = 25_000L
        /** 长轮询读超时 = 电脑给的长轮询时长 + 这段余量（只为兜住网络延迟，不是新的等待窗口）。 */
        private const val POLL_READ_SLACK_MS = 8_000L
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_BUSY = 503
        private const val MAX_ERROR_CHARS = 300

        /** 契约规定的重连节奏：1 / 2 / 5 / 10 / 30 秒，到顶就 30 秒一直试。 */
        val BACKOFF_MS = longArrayOf(1000, 2000, 5000, 10_000, 30_000)

        fun normalizeConfirm(v: String): String = when (v) {
            "approved", "denied", "timeout" -> v
            else -> "n/a"
        }

        private fun brief(e: Throwable): String =
            (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(MAX_ERROR_CHARS)

        private fun reason(e: Exception): String = (e.message ?: e.javaClass.simpleName).take(120)
    }
}

/**
 * ling-link v1 的信封（纯 JVM，可在单测里直接验证）。
 *
 * 外层：`{"v":1,"pairId":"<32hex>","iv":"<B64>","data":"<B64 密文+tag>"}`
 * `iv` / `data` 都是**标准 base64 带 padding**（与桌面端逐位一致），解码时也做规范编码回验，
 * base64url 或省略 padding 的变体一律拒绝 —— 同一串密文只允许一种写法。
 */
internal object LinkWire {
    const val VERSION = 1
    private const val AAD_TEXT = "ling-link-v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    /** 请求体 / 响应体上限 1 MiB（契约）。 */
    const val MAX_ENVELOPE = 1 shl 20

    private val AAD = AAD_TEXT.toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    /** 校验失败都用这个，调用方按「协议错误」处理（不重试成刷屏）。 */
    class Rejected(message: String) : Exception(message)

    /** 把明文 inner 封成信封 JSON 字符串；每次调用都生成新的随机 IV。 */
    fun seal(key: ByteArray, inner: JSONObject): String {
        require(key.size == LinkPairStore.KEY_BYTES) { "密钥必须是 32 字节" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(inner.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("v", VERSION)
            .put("pairId", inner.optString("pairId", ""))
            .put("iv", base64(iv))
            .put("data", base64(ct))
            .toString()
    }

    /**
     * 解开信封并校验：外层版本 / pairId / IV 长度 / 严格 base64 / GCM 认证，再验内层 v 与 kind。
     *
     * 内层 pairId 与 kind 的严格程度**不同**，这是照着两端的真实格式定的：
     *  - 请求（手机 → 电脑）：内层必定带 pairId，对端会校验，我们发的时候也就必须带；
     *  - 回执（电脑 → 手机）：内层**不带** pairId（`{v,kind,ok,...}`）。所以这里只能「带了就必须一致」，
     *    不能要求它一定存在 —— 要求存在会把每一条真实回执都判成协议错误。
     *
     * [expectKind] / [expectedPairId] 传 null 表示调用方不校验（测试用）。
     */
    fun open(key: ByteArray, envelope: String, expectKind: String?, expectedPairId: String?): JSONObject {
        if (envelope.length > MAX_ENVELOPE) throw Rejected("响应过大")
        val o = try {
            JSONObject(envelope)
        } catch (_: Exception) {
            throw Rejected("响应不是合法 JSON")
        }
        val v = o.opt("v")
        if (v !is Number || v.toInt() != VERSION) throw Rejected("信封版本不符")
        val pairId = o.optString("pairId", "")
        if (pairId.isEmpty() || !LinkPairStore.isPairId(pairId)) throw Rejected("信封 pairId 不合法")
        if (expectedPairId != null && pairId != expectedPairId) throw Rejected("信封 pairId 与本地配对不符")
        val iv = strictBase64(o.optString("iv", "")) ?: throw Rejected("iv 不是规范 base64")
        if (iv.size != IV_BYTES) throw Rejected("iv 长度不是 12 字节")
        val data = strictBase64(o.optString("data", "")) ?: throw Rejected("data 不是规范 base64")
        if (data.size < TAG_BYTES) throw Rejected("data 太短")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(data)
        } catch (_: Exception) {
            // 认证失败 = 密钥不对 / 被篡改 / 不是这条配对回的话。不含任何对端内容。
            throw Rejected("响应认证失败（密钥不符或内容被改）")
        }
        val inner = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            throw Rejected("解密后不是合法 JSON")
        }
        val iv2 = inner.opt("v")
        if (iv2 !is Number || iv2.toInt() != VERSION) throw Rejected("内层版本不符")
        // 内层 pairId 是**可选**的：桌面端的回执就是 `{v,kind,ok,...}`，不带 pairId
        // （见契约 wire v1「Server response … {v:1,kind,ok:true,...}」、ling-link.js 的 handleHello/
        // handlePoll/handleResult 返回体）。要求它一定存在 = 每一条真实回执都被判成协议错误。
        //
        // 安全性没有因此变松：
        //  - 外层 pairId 已经严格校验并和本地配对比过（上面那道 expectedPairId）；
        //  - 整段密文只有本配对的 linkKey 能认证通过 —— 「谁能构造这条回执」这件事已经由密钥定死；
        //  - 如果对端**确实**带了内层 pairId，就必须与本地一致（见下），不放过张冠李戴的包。
        val innerPair = inner.optString("pairId", "")
        if (innerPair.isNotEmpty() && innerPair != pairId) throw Rejected("内层 pairId 与外层不一致")
        if (expectKind != null) {
            val kind = inner.optString("kind", "")
            if (kind != expectKind) throw Rejected("回执 kind 与请求不符")
        }
        return inner
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 严格解码：只认标准字母表 + 正确 padding，且回编码必须与原文逐字相同。 */
    fun strictBase64(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 4 != 0) return null
        if (!text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) return null
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (Base64.getEncoder().encodeToString(bytes) == text) bytes else null
    }
}
