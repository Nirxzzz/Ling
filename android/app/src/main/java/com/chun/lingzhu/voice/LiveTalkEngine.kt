package com.chun.lingzhu.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import com.chun.lingzhu.ApiClient
import com.chun.lingzhu.ApiConfig
import com.chun.lingzhu.ApiException
import com.chun.lingzhu.Bodies
import com.chun.lingzhu.Cancel
import com.chun.lingzhu.ChatStore
import com.chun.lingzhu.Personas
import com.chun.lingzhu.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 「语音对话」的整条链路：**麦克风 → 腾讯实时识别 → 现有模型（流式）→ 腾讯语音合成 → 扬声器**。
 *
 * 与「实时转写」是两条互不影响的链路：那边是 [VoiceService] 前台服务 + 豆包 / 阿里，只出文字；
 * 这里是本页持有的引擎，声音全在内存里流，什么都不落盘、不进聊天历史。
 *
 * ## 全双工与插话
 *
 * 麦克风**一直在送**（不因为自己在放声音就停），这是「随时插话」的前提。代价是麦克风会收到
 * 我们自己放出来的声音，所以：
 *  1. 录音源固定 `VOICE_COMMUNICATION` + `MODE_IN_COMMUNICATION`，并尽量挂上系统的
 *     [AcousticEchoCanceler]。**是否真的生效只能由系统报告**（[LiveTalkSnapshot.aec] 如实写），
 *     没有在真机上验证过就绝不能说「回声消除有效」。
 *  2. AEC 即使生成了也未必消干净，所以还有一层纯软件判断 [BargeInPolicy]：太短的 partial 不算、
 *     和「正在念的内容」重合的 partial 判为回声。这是兜底启发式，不是声学保证。
 *
 * ## 一轮对话的状态机
 *
 * `IDLE → CONNECTING → LISTENING ⇄ THINKING ⇄ SPEAKING → STOPPING → IDLE`
 *
 * 每一代回答有一个自增的 **generation**：所有异步回调（识别 / 模型 / 合成）都带着自己那一代进来，
 * 代次对不上就整段丢掉。插话时直接推进代次 —— 于是「上一轮迟到的模型回调把答案念出来」
 * 这种错在结构上不可能发生，而不是靠一堆布尔量去猜。
 *
 * ## 有界
 *
 * 待合成句子、待播放 PCM 两段队列都有上限：宁可少念一句 / 丢一块音频，也不让延迟越滚越大，
 * 更不让内存跟着对方语速涨。
 *
 * ## 线程
 *
 *  - 主线程：状态发布、AudioTrack 与 AudioManager 的开关、合成调度；
 *  - `live-mic`：阻塞读麦克风 → 送识别；
 *  - `live-llm`：一次请求一个线程（同一时刻只有一代在跑）；
 *  - `live-play`：从有界队列取 PCM → 写 AudioTrack（阻塞写）；
 *  - 识别 / 合成的回调线程由 [TencentSpeech] 自己给，这里只负责「代次 + 线程切换」。
 */
class LiveTalkEngine(ctx: Context, private val listener: Listener) {

    interface Listener {
        /** 状态变了（主线程回调）。 */
        fun onState(snap: LiveTalkSnapshot)

        /** 用户一句话定稿（主线程回调）。**只在本页内存里**，不写聊天记录、不落盘。 */
        fun onUserSaid(text: String)

        /** 本轮回答到目前为止的全文（主线程回调，流式增长）。 */
        fun onAnswerText(text: String)
    }

    enum class Phase {
        /** 没在对话 */
        IDLE,

        /** 正在连识别、开麦克风，**还没有送出一帧** */
        CONNECTING,

        /** 在听（可能同时还在生成 / 播放） */
        LISTENING,

        /** 模型在生成，还没有声音出来 */
        THINKING,

        /** 正在出声（同时可能还在生成后面的句子） */
        SPEAKING,

        /** 正在收尾 */
        STOPPING,
    }

    /** 回声消除的**实际**状态：只报系统说的，不猜。 */
    enum class Aec {
        /** 还没开始，不知道 */
        UNKNOWN,

        /** 系统提供了 AcousticEchoCanceler 且已启用 */
        ENABLED,

        /** 系统有这类效果器，但没能启用 */
        NOT_ENABLED,

        /** 这台设备 / 这个录音源没有回声消除 */
        UNAVAILABLE,
    }

    data class LiveTalkSnapshot(
        val phase: Phase = Phase.IDLE,
        val text: String = "未开始",
        val error: Boolean = false,
        val aec: Aec = Aec.UNKNOWN,
        /** 模型这一轮已经生成的全部文字（界面显示用；不落盘） */
        val answerSoFar: String = "",
        /** 听见过用户的声音（识别出过 partial 或 final） */
        val heard: Boolean = false,
    )

    private val app: Context = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val settings = LiveTalkSettings(app)
    private val speech = TencentSpeech()

    @Volatile private var running = false
    @Volatile private var stopping = false

    /**
     * 回答代次。每条模型 / 合成回调都带着自己那一代回来，不相等就丢。
     * 插话时推进一格 —— 上一代迟到的答案再也念不出来。
     */
    @Volatile private var gen = 0L

    /**
     * 会话代次：**只**在 start / stop 时变。
     *
     * 识别回调必须按它判活，不能按 [gen]：每问一句话 [gen] 就推进一格，识别若也按 [gen] 判，
     * 第一条回答之后识别就永远「代次不对」而被丢掉 —— 麦克风还在送、界面还写着「在听」，
     * 实际上再说什么都没人接。这两件事的生命周期本来就不一样：识别活整条会话，回答只活一轮。
     */
    @Volatile private var session = 0L

    private val lock = Any()

    // ---------------- 一代回答 ----------------

    /**
     * 一代回答。`cancelled` 一旦为真，这一代的任何回调都不许再改状态 / 说话。
     * 可变字段都在 [lock] 下读写。
     */
    private class Turn(val gen: Long) {
        val sentences = ArrayDeque<String>()
        /** 正在合成中的段落数（上限 [MAX_SYNTH_IN_FLIGHT]） */
        var inFlight = 0
        /** 模型已经吐完了 */
        var llmDone = false
        /** 这一轮已经送去合成 / 已念过的原文 —— 判断「这条 partial 是不是我们自己的声音」用 */
        val spoken = StringBuilder()
        val answer = StringBuilder()
        var cancelled = false
        /** 合成 / 模型已经报过错（只报一次，不刷屏） */
        var failed = false
    }

    @Volatile private var turn: Turn? = null

    // ---------------- 音频 ----------------

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var aecState = Aec.UNKNOWN

    @Volatile private var track: AudioTrack? = null
    /**
     * 待播放的 PCM。**每一块都带着它入队时那一代回答的编号 [Pcm.gen]**（合成回调闭包里的 `my`，不可变）：
     * 入队之后即使插进一次打断 / 新的一轮提问，这块音频也不可能被当成「新代次的」放出来 ——
     * 代次随块走，写线程一看不是当前代次就丢掉。
     * 不能在入队那一刻现读 `gen`：读的那一刻可能已经是下一代了，那正是旧音频漏出来的口子。
     */
    private class Pcm(val gen: Long, val bytes: ByteArray)

    private val playback = LinkedBlockingQueue<Pcm>(MAX_PLAY_QUEUE_CHUNKS)
    @Volatile private var playThread: Thread? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var llmThread: Thread? = null
    @Volatile private var playbackFailed = false

    private var audioManager: AudioManager? = null
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private var recognition: TencentSpeech.Recognition? = null
    private var credentials: TencentCredentials? = null
    private var cancel: Cancel? = null

    /** 正在合成中的句柄（插话 / 停止时要逐个取消） */
    private val synthHandles = ArrayList<TencentSpeech.Cancel>(4)

    // ---------------- 对话上下文（只在内存） ----------------

    private var turns: List<ChatStore.Msg> = emptyList()
    private var cfg: ApiConfig? = null

    // ---------------- 识别结果的去抖与去重 ----------------

    @Volatile private var ignoreBargeUntil = 0L
    private var pendingFinal = ""
    private var lastCommitted: Pair<String, Long> = "" to 0L
    private val committedSegments = LinkedHashSet<String>()

    private fun now() = System.currentTimeMillis()

    // ================================================================= 对外

    /**
     * 开始一次语音对话。**所有前提都在这里一次核清**（同意 / 凭据 / 接口 / 麦克风），
     * 任何一条不成立就返回 false 并把原因写进状态 —— 绝不半开。
     */
    fun start(): Boolean {
        if (running) return true
        stopping = false
        if (!settings.consent) {
            publish(LiveTalkSnapshot(text = "还没同意上传授权：先点「我已了解，开始对话」。", error = true))
            return false
        }
        val cred = settings.credentials()
        if (cred == null) {
            publish(LiveTalkSnapshot(text = "还没有保存腾讯语音的 AppId / SecretId / SecretKey（三项都要填）。", error = true))
            return false
        }
        val config = Prefs(app).api()
        if (config.baseUrl.isBlank()) {
            publish(LiveTalkSnapshot(text = "还没有配置大模型接口：先在设置里把接口配好。", error = true))
            return false
        }
        credentials = cred
        cfg = config
        synchronized(lock) {
            committedSegments.clear()
            lastCommitted = "" to 0L
            pendingFinal = ""
            turn = null
            turns = emptyList()
        }
        playbackFailed = false
        gen++
        session++
        running = true
        publish(LiveTalkSnapshot(phase = Phase.CONNECTING, text = "正在连接腾讯实时识别…"))
        if (!acquireFocus()) {
            running = false
            publish(LiveTalkSnapshot(text = "别的应用正占着音频（通话 / 录音），语音对话没有开始。", error = true))
            return false
        }
        if (!openMic()) {
            releaseAudio()
            running = false
            publish(LiveTalkSnapshot(text = "麦克风打不开：可能被别的应用占用，或没有录音权限。", error = true))
            return false
        }
        startPlayback()
        startRecognition(cred)
        return true
    }

    /**
     * 停止并释放一切：识别连接、麦克风、扬声器、模型请求、合成、音频焦点、音频模式。
     * 可重复调用。
     */
    fun stop(reason: String = "") {
        if (stopping) return
        stopping = true
        val wasRunning = running
        running = false
        gen++
        session++
        val handles: List<TencentSpeech.Cancel>
        val pendingCancel: Cancel?
        synchronized(lock) {
            turn?.cancelled = true
            turn = null
            handles = synthHandles.toList()
            synthHandles.clear()
            pendingFinal = ""
            pendingCancel = cancel
            cancel = null
        }
        if (wasRunning && reason.isEmpty()) {
            publish(LiveTalkSnapshot(phase = Phase.STOPPING, text = "正在收尾…", aec = aecState))
        }
        runCatching { recognition?.cancel() }
        recognition = null
        runCatching { pendingCancel?.abort() }
        handles.forEach { runCatching { it.cancel() } }
        stopMic()
        stopPlayback()
        releaseAudio()
        joinBounded(llmThread)
        llmThread = null
        // 没在跑、也没给原因（用户只是切走了页面）→ 不改状态行：把「未开始」刷成「已停止」是噪音
        if (wasRunning || reason.isNotEmpty()) {
            publish(LiveTalkSnapshot(text = reason.ifEmpty { "已停止" }, aec = aecState))
        }
        stopping = false
    }

    /** 播放音量 `[0, 1]`（只影响本机播放，不发给腾讯）。可在运行中改。 */
    fun setVolume(v: Float) {
        runCatching { track?.setVolume(v.coerceIn(0f, 1f)) }
    }

    // ================================================================= 识别

    private fun startRecognition(cred: TencentCredentials) {
        val r = try {
            // 识别回调按**会话**代次判活（不是 [gen]）：一个会话里要问很多句话，[gen] 每句都推进。
            val mySession = session
            speech.recognize(cred, object : TencentSpeech.RecognitionListener {
                override fun onReady() {
                    if (!sessionAlive(mySession)) return

                    publish(LiveTalkSnapshot(phase = Phase.LISTENING, text = "在听，直接说话（不用按键）", aec = aecState))
                }

                override fun onText(text: String, final: Boolean, segmentId: String) {
                    if (!sessionAlive(mySession)) return
                    val t = text.trim()
                    if (t.isEmpty()) return
                    markHeard()
                    if (final) onFinal(t, segmentId) else onPartial(t)
                }

                override fun onError(message: String) {
                    if (!sessionAlive(mySession)) return
                    // 识别出错 = 这条链路断了：整条停掉，别留一个「看着在听、其实没在识别」的界面
                    failAndStop("语音识别中断：$message")
                }

                override fun onFinished() {
                    // 正常收尾只会在我们主动 finish 之后；文案交给 stop
                }
            })
        } catch (e: Exception) {
            running = false
            releaseAudio()
            stopPlayback()
            publish(LiveTalkSnapshot(text = "连不上腾讯语音识别：" + (e.message ?: e.javaClass.simpleName), error = true))
            return
        }
        recognition = r
    }

    private fun markHeard() {
        // 识别回调跑在腾讯那条回调线程上：状态与界面回调一律回主线程
        main.post {
            val cur = last
            if (cur.heard) return@post
            val next = cur.copy(heard = true)
            last = next
            listener.onState(next)
        }
    }

    /**
     * partial：只用来做**插话判断**，从不触发请求。
     * 空白 partial（识别器在清空重发）直接忽略 —— 那是「没说话」，不是「新的要求」。
     */
    private fun onPartial(text: String) {
        if (!BargeInPolicy.meaningful(text)) return
        if (now() < ignoreBargeUntil) return
        val cur = synchronized(lock) { turn } ?: return
        if (cur.cancelled) return
        val spoken = synchronized(lock) { cur.spoken.toString() }
        if (!BargeInPolicy.shouldInterrupt(text, spoken)) return
        // 打断要动 AudioTrack / AudioManager / 代次：一律回主线程做（识别回调不在这条线程上）
        main.post { if (running) bargeIn() }
    }

    /**
     * final：**先判回声** → 去重 → 去抖 → 提交一轮。
     *
     * 回声判断放在最前面、且**这类结果连 pendingFinal 都不进**：只过滤 partial 是不够的 ——
     * 我们自己放出来的声音被识别成 final 时，同样会被拼成一句话交给模型，于是「它把自己的话当成用户的话」
     * 再答一遍，越滚越长。判据与插话共用 [BargeInPolicy.echoOf]：正在出声 / 还有句子在合成时，
     * 归一化后是我们已念内容的子串 → 判为回声，整条丢掉。
     *
     * 代价：我们在说话时用户正好复述了同一句，会被当成回声丢掉。这个窗口只限「我们真的在出声」的那几秒，
     * 比「每轮都把自己的话答一遍」好接受；戴耳机时根本不会出现。
     *
     * 其余三道闸门都是为了「同一句话不会变成两次请求」：
     *  1. 同一 `segmentId` 的 final 只认一次；
     *  2. 同一句文字在 [SAME_TEXT_MS] 内重复到达只认一次（重连重发 / 服务端重发）；
     *  3. 定稿后 [FINAL_DEBOUNCE_MS] 内的碎片合并成一次提交（腾讯会把一口气拆成几段）。
     */
    private fun onFinal(text: String, segmentId: String) {
        val norm = LiveSpeechSentences.normalize(text)
        if (norm.isEmpty()) return
        // ---- 回声：只在本端确实在出声 / 还有句子在合成时判 ----
        val soundTurn = synchronized(lock) { turn }
        if (soundTurn != null && !soundTurn.cancelled) {
            val speaking = playback.isNotEmpty() || synchronized(lock) { soundTurn.inFlight > 0 || soundTurn.sentences.isNotEmpty() }
            if (speaking) {
                val spoken = synchronized(lock) { soundTurn.spoken.toString() }
                if (BargeInPolicy.echoOf(text, spoken)) return
            }
        }
        synchronized(lock) {
            if (segmentId.isNotBlank() && !committedSegments.add(segmentId)) return
            if (committedSegments.size > MAX_REMEMBERED_SEGMENTS) {
                val it = committedSegments.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            val (lastText, at) = lastCommitted
            if (lastText == norm && now() - at < SAME_TEXT_MS) return
            pendingFinal = if (pendingFinal.isEmpty()) text else pendingFinal + " " + text
        }
        // 说话时来了一条「不像回声」的 final（有的设备只给 final 不给 partial）：这里同样要打断，
        // 否则回答会接着说，用户那句话却已经开始排队了。判断在这里读一次快照，动作回主线程。
        val cur = synchronized(lock) { turn }
        if (cur != null && !cur.cancelled && now() >= ignoreBargeUntil) {
            val spoken = synchronized(lock) { cur.spoken.toString() }
            val busyTurn = synchronized(lock) { cur.answer.isNotEmpty() || cur.inFlight > 0 }
            if (busyTurn && BargeInPolicy.shouldInterrupt(text, spoken)) main.post { if (running) bargeIn() }
        }
        main.removeCallbacks(commitRunnable)
        main.postDelayed(commitRunnable, FINAL_DEBOUNCE_MS)
    }

    private val commitRunnable = Runnable {
        val text = synchronized(lock) {
            val t = pendingFinal.trim()
            pendingFinal = ""
            t
        }
        commit(text)
    }

    /**
     * 把这句话当成**一轮用户输入**提交：进内存上下文 → 开始一次流式请求。
     * 空白 / 纯符号不提交（不产生空请求，也不产生一个空的气泡）。
     */
    private fun commit(text: String) {
        if (!running) return
        val t = text.trim()
        val norm = LiveSpeechSentences.normalize(t)
        if (norm.isEmpty()) return
        val (lastText, at) = lastCommitted
        if (lastText == norm && now() - at < SAME_TEXT_MS) return
        lastCommitted = norm to now()
        // 刚提交完的这一句，识别器还会继续吐它的尾巴：这段时间内不把它当插话
        ignoreBargeUntil = now() + GRACE_AFTER_COMMIT_MS
        synchronized(lock) {
            turns = ChatStore.add(turns, ChatStore.user(t, now())).takeLast(MAX_TURNS)
        }
        listener.onUserSaid(t)
        startTurn(t)
    }

    // ================================================================= 模型

    /**
     * 开始一代回答。请求体在主线程就拼好（上下文 / 人格只在这一层读），线程里只剩网络。
     *
     * 请求体自己拼而不是走 [ChatStore.body]：那条路径固定发 `stream: false`，而语音要边出边念。
     * **模型、思考档位、温度、人格提示词全部沿用现有配置** —— 语音对话不替用户改模型行为。
     * 思考开着会慢一些，那是用户自己选的档位，不是这里该悄悄覆盖的。
     */
    private fun startTurn(userText: String) {
        val config = cfg ?: return
        val body = buildBody(config)
        val my = ++gen
        val t = Turn(my)
        val myCancel = Cancel()
        synchronized(lock) {
            turn = t
            cancel = myCancel
        }
        publish(LiveTalkSnapshot(phase = Phase.THINKING, text = "正在想…", aec = aecState))
        val client = ApiClient(config)
        val seg = LiveSpeechSentences()
        val th = Thread(worker@ {
            try {
                client.stream(body, object : ApiClient.Listener {
                    override fun onText(text: String) {
                        if (!current(my)) return
                        onAnswerDelta(my, t, text, seg.feed(text))
                    }
                }, myCancel)
                // 取消 / 代次已推进 / 被 max_tokens 截断 → 都不算「回答完了」，半句不进上下文
                if (!current(my) || myCancel.get()) return@worker
                onLlmDone(my, t, listOfNotNull(seg.flush()), client.truncated)
            } catch (e: Exception) {
                if (!current(my)) return@worker
                if (myCancel.get()) return@worker
                val msg = if (e is ApiException) e.message ?: "请求失败" else (e.message ?: e.javaClass.simpleName)
                onLlmFailed(my, t, msg)
            }
        }, "live-llm").apply { isDaemon = true }
        llmThread = th
        th.start()
    }

    private fun buildBody(config: ApiConfig): String {
        val o = JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("max_tokens", MAX_ANSWER_TOKENS)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        val history = synchronized(lock) { turns }
        for (m in ChatStore.window(history, MAX_TURNS)) {
            if (m.content.isBlank()) continue
            msgs.put(JSONObject().put("role", m.wireRole).put("content", m.content))
        }
        o.put("messages", msgs)
        // 思考档位按设置里的原值：不替用户关掉，也不额外打开（见本函数上方的说明）。
        Bodies.thinking(config, false, o)
        Bodies.temp(config, o)
        return o.toString()
    }

    /** 现有的人格 / 语气沿用 [ChatStore.systemPrompt]，只追加一句「这是语音对话」的硬要求。 */
    private fun systemPrompt(): String {
        val store = Personas.Store(app)
        val me = store.list().firstOrNull { it.id == store.current() }
        return ChatStore.systemPrompt(me) + "\n\n" + VOICE_RULE
    }

    private fun onAnswerDelta(my: Long, t: Turn, delta: String, sentences: List<String>) {
        synchronized(lock) {
            if (t.cancelled) return
            t.answer.append(delta)
            if (t.answer.length > MAX_ANSWER_CHARS) t.answer.setLength(MAX_ANSWER_CHARS)
            for (s in sentences) {
                if (t.sentences.size >= MAX_PENDING_SENTENCES) break   // 有界：宁可少念一句
                t.sentences.add(s)
            }
        }
        val snapshotText = synchronized(lock) { t.answer.toString() }
        main.post {
            if (!current(my)) return@post
            // 界面的文字回调也在主线程：Activity / View 不是线程安全的
            listener.onAnswerText(snapshotText)
            publishAnswer(snapshotText)
            pumpSynth()
        }
    }

    /**
     * 模型这一轮**正常吐完**了。[rest] 是句子切分器里剩下的半句。
     *
     * 进上下文的条件是「真的回答完了」：没被打断（[Turn.cancelled]）、代次仍然是这一代、没报过错、
     * 也没被 `max_tokens` 截断 —— 只说了半句就被掐掉 / 断掉的回答不该被模型当成「我说过」。
     * 空答案同样不写（[ChatStore.add] 自己也会挡，这里先挡一次，省得白算时间戳）。
     *
     * 写进 [turns] 发生在**模型线程上、且就在完成的那一刻**，不走 main.post：
     * 下一轮请求体是在主线程拼的，若把追加排进主队列，「用户已经说完下一句、请求体却还没看到上一句回答」
     * 这个窗口就真实存在。加锁两行代码，换掉这个时序依赖。
     */
    private fun onLlmDone(my: Long, t: Turn, rest: List<String>, truncated: Boolean) {
        val full = synchronized(lock) {
            if (t.cancelled || t.failed) return
            t.llmDone = true
            for (s in rest) if (t.sentences.size < MAX_PENDING_SENTENCES) t.sentences.add(s)
            t.answer.toString().trim()
        }
        // 真正「说完」：正文非空、代次仍是这一代、没被 max_tokens 截断、也没在这几行之间被打断
        if (full.isNotEmpty() && !truncated && current(my)) {
            synchronized(lock) {
                if (!t.cancelled && !t.failed) {
                    turns = ChatStore.add(turns, ChatStore.orb(full, now())).takeLast(MAX_TURNS)
                }
            }
        }
        main.post {
            if (!current(my)) return@post
            listener.onAnswerText(full)
            publishAnswer(full)
            pumpSynth()
            maybeIdle(my)
        }
    }

    private fun onLlmFailed(my: Long, t: Turn, message: String) {
        synchronized(lock) {
            if (t.cancelled) return
            t.llmDone = true
            t.failed = true
        }
        if (current(my)) {
            publish(LiveTalkSnapshot(phase = Phase.LISTENING, text = "这次没答上来：$message", error = true, aec = aecState))
            main.post { maybeIdle(my) }
        }
    }

    // ================================================================= 合成与播放

    /**
     * 把排队的句子按「同时最多 [MAX_SYNTH_IN_FLIGHT] 条」发出去。
     *
     * 为什么要限制并发：一句话一条 WebSocket，几十句一起开就是几十条连接；而且合成是实时的，
     * 排在后面只会让积压越来越长。维持 2 条正好做到「下一句在上一句还没念完时已经开始准备」。
     */
    private fun pumpSynth() {
        if (!running) return
        val cred = credentials ?: return
        val jobs = ArrayList<Pair<Turn, String>>(MAX_SYNTH_IN_FLIGHT)
        synchronized(lock) {
            val t = turn ?: return
            if (t.cancelled) return
            while (t.inFlight < MAX_SYNTH_IN_FLIGHT && t.sentences.isNotEmpty()) {
                val s = t.sentences.poll() ?: break
                t.inFlight++
                t.spoken.append(s).append(' ')
                jobs.add(t to s)
            }
        }
        for ((tt, s) in jobs) startSynth(cred, tt, s)
    }

    /** 每一段合成自己那条连接的回调；[tt] 是它属于的那一代（代次不对就丢）。 */
    private fun startSynth(cred: TencentCredentials, tt: Turn, sentence: String) {
        val my = tt.gen
        val voice = settings.voiceType
        val speed = settings.speed
        // 持有者：句柄要等 synthesize 返回才有，而回调可能在返回前就到了 —— 所以用这个盒子
        val box = arrayOfNulls<TencentSpeech.Cancel>(1)
        val handle = speech.synthesize(cred, sentence, voice, speed, object : TencentSpeech.SynthesisListener {
            override fun onPcm(pcm: ByteArray) {
                if (!current(my) || !running) return
                // 有界：放不下就丢这一块（宁可声音断一下，也不让延迟无限涨）。
                // 块上带的是**这一代**的编号（闭包里的 my，不会变）：入队之后无论插进几次打断，
                // 写线程一看代次不对就丢 —— 旧回调永远不可能用新代次把声音送进扬声器。
                if (!playback.offer(Pcm(my, pcm))) return
                main.post { if (current(my)) markSpeaking(my) }
            }

            override fun onFinished() {
                synchronized(lock) {
                    if (tt.inFlight > 0) tt.inFlight--
                    box[0]?.let { synthHandles.remove(it) }
                }
                if (current(my)) main.post {
                    pumpSynth()
                    maybeIdle(my)
                }
            }

            override fun onError(message: String) {
                synchronized(lock) {
                    if (tt.inFlight > 0) tt.inFlight--
                    box[0]?.let { synthHandles.remove(it) }
                }
                if (current(my)) main.post {
                    // 合成失败不影响「继续听」：文字已经生成，只是这一段没念出来 —— 如实说一句
                    publish(
                        LiveTalkSnapshot(
                            phase = Phase.LISTENING, text = "这段没念出来（语音合成失败：$message）",
                            error = true, aec = aecState,
                        )
                    )
                    pumpSynth()
                    maybeIdle(my)
                }
            }
        })
        synchronized(lock) {
            box[0] = handle
            // 已经因为插话 / 停止取消的那一代：句柄刚到就已经过时，立刻撤掉
            if (tt.cancelled || !running) runCatching { handle.cancel() } else synthHandles.add(handle)
        }
    }

    private fun markSpeaking(my: Long) {
        if (!current(my)) return
        val cur = last
        if (cur.phase == Phase.SPEAKING) return
        publish(LiveTalkSnapshot(phase = Phase.SPEAKING, text = "正在说…（直接说话可以打断）", aec = aecState, heard = cur.heard))
    }

    /** 这一代彻底完事（模型吐完、句子念完、扬声器也空了）→ 回到「在听」。 */
    private fun maybeIdle(my: Long) {
        if (!current(my) || !running) return
        val done = synchronized(lock) {
            val t = turn ?: return@synchronized true
            t.cancelled || (t.llmDone && t.inFlight == 0 && t.sentences.isEmpty())
        }
        if (!done) return
        if (!playback.isEmpty()) return
        if (last.phase == Phase.LISTENING) return
        publish(LiveTalkSnapshot(phase = Phase.LISTENING, text = "在听，直接说话（不用按键）", aec = aecState, heard = true))
    }

    // ================================================================= 插话

    /**
     * 立刻打断：**模型请求 + 排队中的句子 + 正在合成 + 已经排在扬声器里的声音，全部撤掉**。
     *
     * 做法是把代次推进一格：从此上一代的任何回调都 `current()` 不过，于是「迟到的答案又被念出来」
     * 在结构上不可能。之后用户那句话的 final 会正常提交成新的一轮。
     */
    private fun bargeIn() {
        gen++
        val handles: List<TencentSpeech.Cancel>
        val pendingCancel: Cancel?
        synchronized(lock) {
            turn?.cancelled = true
            turn = null
            handles = synthHandles.toList()
            synthHandles.clear()
            pendingCancel = cancel
            cancel = null
        }
        runCatching { pendingCancel?.abort() }
        handles.forEach { runCatching { it.cancel() } }
        flushPlayback()
        // 被打断的这一轮，用户说的话仍然留在上下文里（他确实说了），
        // 但还没说完的回答不再进上下文 —— 没念出来的半句不该被当成「它说过」。
        ignoreBargeUntil = now() + GRACE_AFTER_BARGE_IN_MS
        if (running) {
            publish(
                LiveTalkSnapshot(
                    phase = Phase.LISTENING, text = "（你先说话了，已打断）你可以接着说。",
                    aec = aecState, heard = true,
                )
            )
        }
    }

    // ================================================================= 音频设备

    private fun acquireFocus(): Boolean {
        val am = app.getSystemService(AudioManager::class.java) ?: return false
        audioManager = am
        prevMode = am.mode
        prevSpeaker = runCatching { am.isSpeakerphoneOn }.getOrDefault(false)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    hasFocus = false
                    // 焦点丢了就别硬抢：通话 / 别的录音应用优先。如实停下，不偷偷继续占麦。
                    if (running) failAndStop("音频焦点被其他应用占用了，对话已停止")
                }
            }, main)
            .build()
        focusRequest = req
        val got = runCatching { am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
            .getOrDefault(false)
        hasFocus = got
        if (!got) return false
        // 通话模式：这是 AEC 能工作的前提之一（录音源 VOICE_COMMUNICATION 也要它配合）
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        // 对话要外放：这里只保证默认走扬声器而不是听筒；耳机 / 蓝牙仍按系统路由
        runCatching { am.isSpeakerphoneOn = true }
        return true
    }

    private fun releaseAudio() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { aec?.enabled = false }
        runCatching { aec?.release() }
        aec = null
        val am = audioManager
        if (am != null) {
            runCatching { am.isSpeakerphoneOn = prevSpeaker }
            runCatching { am.mode = prevMode }
        }
        audioManager = null
        val req = focusRequest
        if (req != null && hasFocus) runCatching { am?.abandonAudioFocusRequest(req) }
        focusRequest = null
        hasFocus = false
    }

    /**
     * 开麦克风。录音源固定 `VOICE_COMMUNICATION`（**不是** VOICE_RECOGNITION）：
     * 只有它才会走系统的通话音频通路，AEC 才有可能真的起作用。
     *
     * AEC 的结论**只报系统给的**：`isAvailable()` / `enabled` 是什么就写什么进快照。
     * 没有任何「真机上回声一定会被消掉」的暗示 —— 那需要硬件实测。
     */
    private fun openMic(): Boolean {
        val minBuf = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (e: Exception) {
            -1
        }
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_BYTES * 8),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }
        record = rec
        aecState = attachAec(rec)
        return try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { rec.release() }
                record = null
                false
            } else {
                startMicLoop(rec)
                true
            }
        } catch (e: Exception) {
            runCatching { rec.release() }
            record = null
            false
        }
    }

    /** 回声消除：有就开、没有就如实说没有。绝不假装「已消除回声」。 */
    private fun attachAec(rec: AudioRecord): Aec = try {
        if (!AcousticEchoCanceler.isAvailable()) {
            Aec.UNAVAILABLE
        } else {
            val e = AcousticEchoCanceler.create(rec.audioSessionId)
            if (e == null) {
                Aec.NOT_ENABLED
            } else {
                runCatching { e.enabled = true }
                if (e.enabled) {
                    aec = e
                    Aec.ENABLED
                } else {
                    runCatching { e.release() }
                    Aec.NOT_ENABLED
                }
            }
        }
    } catch (t: Throwable) {
        Aec.UNAVAILABLE
    }

    /** 麦克风一直送：不因为自己在放声音就停（停了就没法随时插话）。 */
    private fun startMicLoop(rec: AudioRecord) {
        val th = Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (running && !stopping) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    break
                }
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                // 只按整样本发：半个样本绝不拼进下一帧
                val len = n - (n % BYTES_PER_SAMPLE)
                if (len <= 0) continue
                val r = recognition ?: continue   // 还没就绪：这一帧直接丢（不排队，排队只会让延迟涨）
                val frame = if (len == buf.size) buf.copyOf() else buf.copyOf(len)
                runCatching { r.sendPcm(frame) }
            }
        }, "live-mic").apply { isDaemon = true }
        micThread = th
        th.start()
    }

    private fun stopMic() {
        val rec = record ?: return
        record = null
        runCatching { rec.stop() }        // 让阻塞中的 read() 立刻返回
        runCatching { rec.release() }
        joinBounded(micThread, 600)
        micThread = null
    }

    private fun startPlayback() {
        val th = Thread({
            while (running && !stopping) {
                val chunk = try {
                    playback.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                // 取出的这一块如果属于旧代次（入队之后发生过打断 / 新一轮提问），直接丢掉再取下一块
                if (chunk.gen != gen) continue
                writeChunk(chunk)
            }
        }, "live-play").apply { isDaemon = true }
        playThread = th
        th.start()
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val buf = if (min > 0) maxOf(min, PLAY_BUFFER_BYTES) else PLAY_BUFFER_BYTES
            val t = AudioTrack(attrs, fmt, buf, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t.release() }
                null
            } else {
                t.setVolume(settings.volume)
                t.play()
                track = t
                t
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeChunk(chunk: Pcm) {
        // 这一块自带入队时那一代的编号：与当前代次不符就直接丢，**连 AudioTrack 都不去建** ——
        // 打断之后再去 ensureTrack() 等于给一段已经作废的声音重新开扬声器。
        // 取出到写入之间还可能刚好发生一次打断，所以进了写循环还要再查一次。
        val myGen = chunk.gen
        val bytes = chunk.bytes
        if (!running || stopping || myGen != gen) return
        val t = ensureTrack()
        if (t == null) {
            if (!playbackFailed) {
                playbackFailed = true
                main.post {
                    publish(
                        LiveTalkSnapshot(
                            phase = Phase.LISTENING, text = "扬声器打不开，听不到回答（文字仍在屏幕上）。",
                            error = true, aec = aecState,
                        )
                    )
                }
            }
            return
        }
        try {
            var off = 0
            while (off < bytes.size) {
                if (myGen != gen || !running || stopping) return
                val n = t.write(bytes, off, bytes.size - off)
                // 负值 / 0 = 这次没写进去（pause+flush 打断、设备被拔）：只丢这一块，不重试 ——
                // 重试只会把已经过时的声音又塞回扬声器。
                if (n <= 0) return
                off += n
            }
        } catch (e: Exception) {
            // 同上：写入失败只丢这一块，不把整条对话判死
        }
    }

    /**
     * 丢进扬声器里的声音立刻作废（插话 / 停止时用）。
     *
     * 顺序要紧：**先 pause** —— 阻塞在 [AudioTrack.write] 里的播放线程只有 pause 能让它立刻返回
     * （interrupt 动不了 native 写）；接着 flush 清掉缓冲区里还没放出去的那几十到几百毫秒；
     * 队列里排着的块由调用方推进的 [gen] 作废（[bargeIn] / [stop] 都先 `gen++` 再调这里）。
     * 不做这两步，插话之后上一句还会把尾巴放完。
     */
    private fun flushPlayback() {
        playback.clear()
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
        if (running && !stopping) runCatching { t.play() }
    }

    private fun stopPlayback() {
        playback.clear()
        val t = track
        track = null
        if (t != null) {
            // 同上：pause 先把阻塞中的 write 叫醒，再 flush + release
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.release() }
        }
        playThread?.let { runCatching { it.interrupt() } }   // 让它从队列 poll 里醒来退出
        joinBounded(playThread)
        playThread = null
    }

    private fun joinBounded(th: Thread?, ms: Long = 800) {
        if (th == null) return
        runCatching { th.join(ms) }
    }

    // ================================================================= 状态

    /** 这一代回答还算数吗（模型 / 合成的回调按它判）。 */
    private fun current(my: Long): Boolean = running && my == gen

    /** 这条会话还算数吗（**识别**的回调按它判：识别活整条会话，不随每轮回答推进）。 */
    private fun sessionAlive(my: Long): Boolean = running && my == session

    @Volatile private var last = LiveTalkSnapshot()

    private fun publish(snap: LiveTalkSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            last = snap
            listener.onState(snap)
        } else {
            main.post { last = snap; listener.onState(snap) }
        }
    }

    /** 流式增长时只更新「回答全文」，不改阶段（避免把「正在说」刷回「正在想」）。 */
    private fun publishAnswer(text: String) {
        val cur = last
        if (cur.phase != Phase.THINKING && cur.phase != Phase.SPEAKING) return
        val next = cur.copy(answerSoFar = text)
        last = next
        listener.onState(next)
    }

    private fun failAndStop(message: String) {
        if (!running) {
            publish(LiveTalkSnapshot(text = message, error = true, aec = aecState))
            return
        }
        publish(LiveTalkSnapshot(text = message, error = true, aec = aecState))
        stop(message)
    }

    companion object {
        /** 与腾讯约定的音频格式：16k / 单声道 / signed 16-bit little endian，双向都是 */
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2

        /** 送给识别的一帧：100ms = 3200 字节。比 200ms 短，插话的判别能早一拍。 */
        private const val FRAME_SAMPLES = 1_600
        private const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE

        /** 同时最多几条合成连接在跑（见 [pumpSynth]） */
        private const val MAX_SYNTH_IN_FLIGHT = 2

        /** 待合成句子最多排几句：超过就少念几句，绝不无限攒 */
        private const val MAX_PENDING_SENTENCES = 4

        /** 扬声器队列最多排几块 PCM（一块通常 0.2~1 秒；32 块 = 最多几秒） */
        private const val MAX_PLAY_QUEUE_CHUNKS = 32

        /** AudioTrack 缓冲区下限（约 1 秒，够抗一次抖动） */
        private const val PLAY_BUFFER_BYTES = 32_000

        /** 上下文最多带几条消息（用户 + 回答各算一条） */
        private const val MAX_TURNS = 16

        /** 单轮回答字数上限（防止极端长回答把内存和合成都撑爆） */
        private const val MAX_ANSWER_CHARS = 4_000

        /** 一轮回答的 token 预算：语音回答本来就该短 */
        private const val MAX_ANSWER_TOKENS = 400

        /** final 去抖窗口：一口气被拆成几段时合并成一次提交 */
        private const val FINAL_DEBOUNCE_MS = 260L

        /** 同一句文字在这段时间内重复到达只算一次 */
        private const val SAME_TEXT_MS = 2_500L

        /** 记住多少个已提交的 segmentId（有界，防长会话涨内存） */
        private const val MAX_REMEMBERED_SEGMENTS = 64

        /** 刚提交完一轮后，这段时间内的 partial 不当插话（那是同一句的尾巴） */
        private const val GRACE_AFTER_COMMIT_MS = 700L

        /** 被打断之后，给用户把这句话说完的时间 */
        private const val GRACE_AFTER_BARGE_IN_MS = 400L

        /** 追加在现有人格提示词后面的一句硬要求 */
        const val VOICE_RULE: String =
            "现在是语音对话：你说的每一句都会被念出来。所以回答要短、口语化，" +
                "一般一到三句话；不要用 markdown、列表、表格、代码块或链接，不要念出符号。"
    }
}
