package com.chun.lingzhu.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.chun.lingzhu.Brut
import com.chun.lingzhu.Prefs
import com.chun.lingzhu.Preset
import com.chun.lingzhu.Ui
import java.util.concurrent.Executors

/**
 * 「语音对话」页：**麦克风 → 腾讯实时识别 → 现有模型 → 腾讯语音合成 → 扬声器**。
 *
 * 与「实时转写」页的关系：两条独立链路、两个页面。那边只把声音变成文字（豆包 / 阿里），
 * 这一页是**能说话的对话**（腾讯识别 + 腾讯合成 + 你现在配的那个模型）。互不影响：
 * 转写的服务商选择器里没有腾讯，这里的凭据也与那两家的 Key 分开加密存放。
 *
 * 这一页不做的事（写清楚，免得被当成「应该顺手有」）：不存音频、不存转写文字、不进聊天历史、
 * 不落盘、不参与同步；没有「保存这段对话」按钮 —— 想让某句话留下来，请自己复制。
 *
 * 关掉页面（返回键 / 划掉）会**立刻停掉一切**：麦克风、识别连接、模型请求、合成、扬声器、音频焦点。
 * 按 Home 切走同样停 —— 这一版没有为它配前台服务，所以不会在后台继续占麦克风。
 */
class LiveTalkActivity : Activity() {

    private lateinit var settings: LiveTalkSettings
    private lateinit var engine: LiveTalkEngine

    private lateinit var appIdInput: EditText
    private lateinit var secretIdInput: EditText
    private lateinit var secretKeyInput: EditText
    private lateinit var saveBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var credLine: TextView

    private lateinit var consentLine: TextView
    private lateinit var consentBtn: Button

    private lateinit var voiceInput: EditText
    private lateinit var voiceLine: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var volumeBar: SeekBar

    private lateinit var stateLine: TextView
    private lateinit var aecLine: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private lateinit var talkBox: LinearLayout
    private lateinit var emptyLine: TextView

    /** 这一页里已经发生的对话（只在内存；离开页面即丢）。 */
    private val bubbles = ArrayList<Pair<Boolean, TextView>>()
    private var lastAnswerView: TextView? = null

    /** 本轮回答的那条气泡还「开着」吗（新的一轮用户消息会把它关掉）。 */
    private var answerOpen = false

    /** 整页的滚动容器（新气泡进来时滚到底）。 */
    private var page: ScrollView? = null

    private var shownHasCred = false

    /** 读 / 写 Keystore 是 IO：别在主线程上碰。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "live-talk-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        settings = LiveTalkSettings(this)
        title = Preset.appName + " · 语音对话"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        page = scroll
        setContentView(scroll)
        Brut.page(this, scroll)

        col.addView(TextView(this).apply {
            text = "语音对话"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(10f))
        })

        // ---------------- 这一页把什么发给了谁 ----------------
        val introCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        introCard.addView(
            hint(
                "说话就能聊：你的声音实时上传到**腾讯云**转成文字，文字发给**你现在配的那个大模型**，"
                    + "它答的话再发给腾讯云合成语音，从手机扬声器放出来。", 13.5f,
            )
        )
        introCard.addView(
            hint(
                "三处都会产生费用：腾讯语音识别按音频时长、腾讯语音合成按字符、大模型按 token（都在你自己的账号里计）。\n"
                    + "本机不做的事：不存音频、不存对话文字、不进聊天历史、不写文件、不参与同步。关掉这一页就全丢。",
                12.5f,
            ).apply { setPadding(0, dp(8f), 0, 0) }
        )
        introCard.addView(
            hint(
                "全双工：回答还在说话的时候你也可以直接开口，它会停下来说的内容（见下面的说明）。",
                12.5f,
            ).apply { setPadding(0, dp(8f), 0, 0) }
        )
        col.addView(introCard)
        Brut.card(introCard)

        // ---------------- 凭据 ----------------
        col.addView(Brut.heading(this, "腾讯云凭据"))
        val credCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        credLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            tag = Brut.KEEP
        }
        credCard.addView(credLine)
        appIdInput = credField(credCard, "AppId", "腾讯云账号的 AppId（数字）")
        secretIdInput = credField(credCard, "SecretId", "访问管理的 API 密钥 SecretId")
        secretKeyInput = credField(credCard, "SecretKey", "与 SecretId 配对的那把 SecretKey")
        val credActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveBtn = Button(this).apply {
            text = "保存凭据"
            tag = Brut.PRIMARY
            setOnClickListener { saveCredentials() }
        }
        deleteBtn = Button(this).apply {
            text = "删除已保存"
            tag = Brut.DANGER
            setOnClickListener { askDeleteCredentials() }
        }
        credActions.addView(saveBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8f) })
        credActions.addView(deleteBtn, LinearLayout.LayoutParams(0, -2, 1f))
        credCard.addView(credActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        credCard.addView(
            hint(
                "三项一起用 AndroidKeyStore 的 AES-256-GCM 加密存在应用私有目录（单独的 tencent 槽位，"
                    + "与转写那两家的 Key 互不影响）。输入框始终遮蔽、已保存的从不回显；三项留空 = 沿用已保存的。",
                12.5f,
            ).apply { setPadding(0, dp(10f), 0, 0) }
        )
        col.addView(credCard)
        Brut.card(credCard)

        // ---------------- 同意 ----------------
        col.addView(Brut.heading(this, "隐私与授权"))
        val consentCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        consentLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.3f)
            tag = Brut.KEEP
        }
        consentCard.addView(consentLine)
        consentBtn = Button(this).apply {
            text = "我已了解，允许上传声音"
            setOnClickListener { toggleConsent() }
        }
        consentCard.addView(consentBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(consentCard)
        Brut.card(consentCard)

        // ---------------- 声音 ----------------
        col.addView(Brut.heading(this, "声音"))
        val voiceCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        voiceCard.addView(TextView(this).apply {
            text = "音色 id（Tencent VoiceType）"
            textSize = 13f
            setPadding(0, dp(2f), 0, 0)
            tag = Brut.KEEP
        })
        voiceInput = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(settings.voiceType.toString())
            setPadding(0, dp(8f), 0, dp(8f))
            setOnEditorActionListener { _, _, _ -> applyVoiceType(); false }
        }
        voiceCard.addView(voiceInput)
        voiceLine = hint("", 12.5f).apply { setPadding(0, dp(4f), 0, 0) }
        voiceCard.addView(voiceLine)
        val voiceApply = Button(this).apply {
            text = "应用音色"
            setOnClickListener { applyVoiceType() }
        }
        voiceCard.addView(voiceApply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        voiceCard.addView(
            hint(
                "音色 id 是腾讯云控制台里给你账号开通的那个数字（例如默认的 101001 亲和女声）。"
                    + "它属于你的套餐，这里不内置一张「人名 → id」的表，填错了腾讯会直接报错、不会悄悄换一个。",
                12f,
            ).apply { setPadding(0, dp(8f), 0, 0) }
        )

        speedLabel = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(14f), 0, 0)
            tag = Brut.KEEP
        }
        voiceCard.addView(speedLabel)
        speedBar = SeekBar(this).apply {
            max = ((TencentVoice.SPEED_MAX - TencentVoice.SPEED_MIN) / SPEED_STEP).toInt()
            progress = ((settings.speed - TencentVoice.SPEED_MIN) / SPEED_STEP).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = speedOf(p)
                    speedLabel.text = TencentVoice.speedLabel(v)
                    if (fromUser) runCatching { settings.speed = v }
                    refreshStartBtn()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        voiceCard.addView(speedBar)
        voiceCard.addView(
            hint(
                "这是腾讯的**语速参数**（范围 -2 ~ 6，0 = 正常），不是「1.0 倍速」那种倍率："
                    + "往右是更快。改动下一次合成就生效。",
                12f,
            )
        )

        volumeLabel = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(14f), 0, 0)
            tag = Brut.KEEP
        }
        voiceCard.addView(volumeLabel)
        volumeBar = SeekBar(this).apply {
            max = 20
            progress = Math.round(settings.volume * 20f).coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p / 20f
                    volumeLabel.text = "音量 " + TencentVoice.volumeLabel(v)
                    if (fromUser) runCatching { settings.volume = v }
                    engine.setVolume(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        voiceCard.addView(volumeBar)
        voiceCard.addView(
            hint(
                "音量只调**本机播放**的增益（0% = 静音），不是腾讯合成的音量参数 —— "
                    + "云端合成本身没有这一档，别指望它改服务器上的音色大小。",
                12f,
            ).apply { setPadding(0, dp(2f), 0, dp(6f)) }
        )
        col.addView(voiceCard)
        Brut.card(voiceCard)

        // ---------------- 开始 / 停止 ----------------
        col.addView(Brut.heading(this, "对话"))
        val runCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stateLine = TextView(this).apply {
            textSize = 13.5f
            setLineSpacing(0f, 1.35f)
            tag = Brut.KEEP
        }
        runCard.addView(stateLine)
        aecLine = hint("", 12.5f).apply { setPadding(0, dp(6f), 0, 0) }
        runCard.addView(aecLine)

        val runActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = Button(this).apply {
            text = "开始对话"
            tag = Brut.PRIMARY
            setOnClickListener { onStartClicked() }
        }
        stopBtn = Button(this).apply {
            text = "停止"
            // 停在引擎里做，状态由它的回调画 —— 这里再画一次默认快照会把「已停止」盖掉
            setOnClickListener { engine.stop() }
        }
        runActions.addView(startBtn, LinearLayout.LayoutParams(0, -2, 1f))
        runActions.addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f) })
        runCard.addView(runActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        col.addView(runCard)
        Brut.card(runCard)

        // ---------------- 对话内容 ----------------
        val talkCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyLine = hint("还没有对话。点「开始对话」直接说话就行。", 13f)
        talkCard.addView(emptyLine)
        talkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        talkCard.addView(talkBox)
        col.addView(talkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        Brut.card(talkCard)

        col.addView(
            hint(
                "回声消除：录音源用通话通路（VOICE_COMMUNICATION）并尽量打开系统的 AcousticEchoCanceler，"
                    + "但**没有在任何一台真机上验证过 AEC 的实际效果**。外放时如果它把自己说的话也识别成了你的话，"
                    + "就戴耳机 —— 这一版不敢承诺「外放也不会自己打断自己」。",
                12.5f,
            ).apply { setPadding(0, dp(12f), 0, 0) }
        )

        Brut.style(col)

        engine = LiveTalkEngine(this, object : LiveTalkEngine.Listener {
            override fun onState(snap: LiveTalkEngine.LiveTalkSnapshot) {
                if (isFinishing || isDestroyed) return
                render(snap)
            }

            override fun onUserSaid(text: String) {
                if (isFinishing || isDestroyed) return
                // 新的一轮：上一次那条回答已经结束，不能继续往里追加
                answerOpen = false
                addBubble(you = true, text)
            }

            override fun onAnswerText(text: String) {
                if (isFinishing || isDestroyed) return
                updateAnswer(text)
            }
        })

        refreshCredentials()
        refreshConsent()
        speedLabel.text = TencentVoice.speedLabel(settings.speed)
        volumeLabel.text = "音量 " + TencentVoice.volumeLabel(settings.volume)
        voiceLine.text = ""
        render(LiveTalkEngine.LiveTalkSnapshot())
    }

    // ---------------- 凭据 ----------------

    private fun credField(into: LinearLayout, label: String, hintText: String): EditText {
        into.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, dp(8f), 0, 0)
            tag = Brut.KEEP
        })
        val e = EditText(this).apply {
            hint = hintText
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            // 凭据视同密码：不参与系统保存 / 自动填充，也不显示明文
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(0, dp(8f), 0, dp(8f))
        }
        into.addView(e)
        return e
    }

    private fun refreshCredentials() {
        credLine.text = "正在读取已保存的凭据…"
        deleteBtn.isEnabled = false
        io.execute {
            val has = runCatching { settings.hasCredentials() }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                shownHasCred = has
                credLine.text =
                    if (has) "已保存腾讯云凭据（加密存放，不回显任何一项）。"
                    else "还没有保存腾讯云凭据：三项都要填（AppId / SecretId / SecretKey）。"
                deleteBtn.isEnabled = has
                refreshStartBtn()
            }
        }
    }

    private fun saveCredentials() {
        val a = appIdInput.text?.toString()?.trim().orEmpty()
        val i = secretIdInput.text?.toString()?.trim().orEmpty()
        val k = secretKeyInput.text?.toString()?.trim().orEmpty()
        if (a.isEmpty() && i.isEmpty() && k.isEmpty()) {
            credLine.text = if (shownHasCred) "输入框是空的 —— 保持已保存的凭据不变。"
            else "输入框是空的，还没保存任何凭据。"
            return
        }
        if (a.isEmpty() || i.isEmpty() || k.isEmpty()) {
            // 三项**整套**存：不做「只换 SecretKey」这种部分更新，否则很容易留下 AppId 与密钥不配对、
            // 每次连都报签名错误却不说是哪一项旧了。
            credLine.text = "三项要一起填（AppId / SecretId / SecretKey）——不保存这次的修改。"
            return
        }
        if (a.length > MAX_CRED_LEN || i.length > MAX_CRED_LEN || k.length > MAX_CRED_LEN) {
            credLine.text = "有一项超过了 $MAX_CRED_LEN 字，不像密钥，没有保存。"
            return
        }
        saveBtn.isEnabled = false
        io.execute {
            val err = runCatching { settings.saveCredentials(a, i, k) }.exceptionOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saveBtn.isEnabled = true
                // 明文不在 View 里多留一秒
                appIdInput.setText("")
                secretIdInput.setText("")
                secretKeyInput.setText("")
                if (err == null) {
                    shownHasCred = true
                    deleteBtn.isEnabled = true
                    credLine.text = "已保存腾讯云凭据（加密存放）。"
                } else {
                    credLine.text = "保存失败：" + (err.message ?: err.javaClass.simpleName)
                }
                refreshStartBtn()
            }
        }
    }

    /** 删除是破坏性动作：二次确认（4 秒内再点一次才删），删除前先停掉对话。 */
    private fun askDeleteCredentials() {
        if (deleteBtn.text != CONFIRM_DELETE) {
            deleteBtn.text = CONFIRM_DELETE
            credLine.text = "删除后语音对话就用不了；要恢复只能重新填三项。"
            main.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                if (deleteBtn.text == CONFIRM_DELETE) {
                    deleteBtn.text = "删除已保存"
                    credLine.text =
                        if (shownHasCred) "已保存腾讯云凭据（加密存放，不回显任何一项）。"
                        else "还没有保存腾讯云凭据：三项都要填（AppId / SecretId / SecretKey）。"
                }
            }, 4_000L)
            return
        }
        deleteBtn.text = "删除已保存"
        deleteBtn.isEnabled = false
        engine.stop()
        io.execute {
            runCatching { settings.clearCredentials() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                shownHasCred = false
                credLine.text = "已删除腾讯云凭据。"
                refreshStartBtn()
            }
        }
    }

    // ---------------- 同意 / 参数 ----------------

    private fun refreshConsent() {
        val given = settings.consent
        consentLine.text =
            if (given) {
                "已同意：麦克风声音实时上传到腾讯云识别，识别出的文字发给当前大模型，" +
                    "回答文字再发给腾讯云合成后在本机播放。"
            } else {
                "开始前需要你确认：麦克风声音会实时上传到**腾讯云**做语音识别，你说的话会发给**当前大模型**，" +
                    "它的回答会发给腾讯云做语音合成。三处都在你自己的账号里计费。本机不保存音频与文字。"
            }
        consentBtn.text = if (given) "撤回同意" else "我已了解，允许上传声音"
        consentBtn.tag = if (given) null else Brut.PRIMARY
        Brut.button(consentBtn, if (given) Brut.CARD else Brut.MINT)
        refreshStartBtn()
    }

    /** 撤回同意时**先停**：不让「界面说不同意」和「声音还在上传」同时成立。 */
    private fun toggleConsent() {
        if (settings.consent) {
            engine.stop()
            settings.consent = false
        } else {
            settings.consent = true
        }
        refreshConsent()
    }

    private fun applyVoiceType() {
        val raw = voiceInput.text?.toString()?.trim().orEmpty()
        val v = TencentVoice.parseVoiceType(raw)
        if (v == null) {
            voiceLine.text =
                "音色 id 只能填正整数（腾讯要求），例如 101001。范围 1 ~ ${TencentVoice.VOICE_TYPE_MAX}。"
            voiceLine.setTextColor(DANGER_INK)
            return
        }
        runCatching { settings.voiceType = v }
        voiceInput.setText(v.toString())
        voiceLine.text = "已记下音色 id $v（下一次合成生效）。"
        voiceLine.setTextColor(Ui.TX2)
        refreshStartBtn()
    }

    private fun speedOf(progress: Int): Double =
        Math.round((TencentVoice.SPEED_MIN + progress * SPEED_STEP) * 10.0) / 10.0

    // ---------------- 开始 / 停止 ----------------

    private fun onStartClicked() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (!settings.consent) {
            stateLine.text = "先在「隐私与授权」里点同意：声音要上传到腾讯云、文字要发给大模型才能对话。"
            return
        }
        if (!shownHasCred) {
            stateLine.text = "先保存腾讯云凭据（AppId / SecretId / SecretKey 三项）。"
            return
        }
        clearTalk()
        engine.setVolume(settings.volume)
        if (!engine.start()) {
            // 引擎已经把自己失败的原因写进状态（回调里画出来了），这里不重复覆盖
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            stateLine.text = "没有麦克风权限，无法语音对话。请在系统设置 → 应用 → 本应用 → 权限里允许录音。"
            refreshStartBtn()
            return
        }
        // 权限回调不是「用户点了开始」：中途可能撤了同意 / 删了凭据 / 关了页面，按当前真实状态复核
        if (isFinishing || isDestroyed) return
        if (!settings.consent || !shownHasCred) {
            stateLine.text = "设置已经改了，这次没有开始；请再点一次「开始对话」。"
            refreshStartBtn()
            return
        }
        onStartClicked()
    }

    // ---------------- 渲染 ----------------

    private var snapshot = LiveTalkEngine.LiveTalkSnapshot()

    private fun render(snap: LiveTalkEngine.LiveTalkSnapshot) {
        if (!::stateLine.isInitialized) return
        snapshot = snap
        stateLine.text = when {
            snap.error && snap.phase == LiveTalkEngine.Phase.IDLE -> "没在对话：" + snap.text
            else -> snap.text
        }
        stateLine.setTextColor(if (snap.error) DANGER_INK else Ui.TX1)
        aecLine.text = aecText(snap.aec)
        aecLine.visibility = if (snap.phase == LiveTalkEngine.Phase.IDLE && snap.aec == LiveTalkEngine.Aec.UNKNOWN) View.GONE else View.VISIBLE
        aecLine.setTextColor(if (snap.aec == LiveTalkEngine.Aec.ENABLED) Ui.TX2 else DANGER_INK)
        refreshStartBtn()
    }

    /** 回声消除只报系统说的；这里刻意把「真机没验过」一起写出来。 */
    private fun aecText(aec: LiveTalkEngine.Aec): String = when (aec) {
        LiveTalkEngine.Aec.UNKNOWN -> ""
        LiveTalkEngine.Aec.ENABLED ->
            "回声消除：系统报告已启用（AcousticEchoCanceler）。这**不等于**外放时一定不会自己打断自己 —— 真机没验过。"
        LiveTalkEngine.Aec.NOT_ENABLED ->
            "回声消除：这台设备有这类效果器但没能启用 —— 外放时很可能会把自己的声音识别成你的话。建议戴耳机。"
        LiveTalkEngine.Aec.UNAVAILABLE ->
            "回声消除：这台设备 / 这个录音源没有回声消除 —— 外放时可能把自己的声音识别成你的话。建议戴耳机。"
    }

    private fun refreshStartBtn() {
        if (!::startBtn.isInitialized) return
        val idle = snapshot.phase == LiveTalkEngine.Phase.IDLE
        startBtn.isEnabled = idle && shownHasCred
        startBtn.text = if (idle) "开始对话" else "正在对话…"
        Brut.button(startBtn, Brut.MINT)
        stopBtn.isEnabled = !idle
        stopBtn.tag = if (!idle) Brut.DANGER else null
        Brut.button(stopBtn, if (!idle) Brut.ROSE else Brut.CARD)
    }

    private fun addBubble(you: Boolean, text: String) {
        val tv = TextView(this).apply {
            this.text = (if (you) "你：" else "它：") + text
            textSize = 15f
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(4f), 0, dp(4f))
            tag = Brut.KEEP
            // 用户说的加粗，回答用常规 —— 一眼能分清谁在说
            if (you) typeface = Typeface.DEFAULT_BOLD
        }
        talkBox.addView(tv, LinearLayout.LayoutParams(-1, -2))
        bubbles.add(you to tv)
        if (!you) {
            lastAnswerView = tv
            answerOpen = true
        }
        emptyLine.visibility = View.GONE
        // 有界：这一页只是「刚才聊了什么」的临时展示，不无限长
        while (bubbles.size > MAX_BUBBLES) {
            val (_, old) = bubbles.removeAt(0)
            talkBox.removeView(old)
            if (old === lastAnswerView) {
                lastAnswerView = null
                answerOpen = false
            }
        }
        scrollDown()
    }

    /**
     * 同一轮回答是**原位增长**的：留「它：」前缀，只换后面的正文。
     * 只有当前这一条还「开着」（本轮还没被新一轮用户消息切断）时才改写它，否则新开一条。
     */
    private fun updateAnswer(text: String) {
        val v = lastAnswerView
        if (v == null || !answerOpen) {
            lastAnswerView = null
            addBubble(you = false, text)
            return
        }
        v.text = "它：" + text
        scrollDown()
    }

    private fun scrollDown() {
        page?.post { page?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearTalk() {
        talkBox.removeAllViews()
        bubbles.clear()
        lastAnswerView = null
        answerOpen = false
        emptyLine.visibility = View.VISIBLE
    }


    private fun hint(text: String, size: Float = 12.5f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Ui.TX2)
        tag = Brut.KEEP
        setLineSpacing(0f, 1.25f)
    }

    // ---------------- 生命周期 ----------------

    /**
     * 切走就停：这一版没有为语音对话配前台服务，所以**不能**在后台继续占麦克风 ——
     * 那既违反系统对前台采集的要求，也是最容易被误解成「偷偷在听」的状态。
     */
    override fun onStop() {
        // engine.stop 自己会发「已停止」；这里不另画一份默认快照（那会把状态行改回「未开始」）
        engine.stop()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        engine.stop()
        super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 0x4c73

        /** 凭据一项的长度上限（远超真实长度，纯粹挡住「把整段文件粘进来」） */
        private const val MAX_CRED_LEN = 512

        /** 语速滑杆一档 0.5（腾讯的范围 -2 ~ 6，共 16 档） */
        private const val SPEED_STEP = 0.5

        private const val CONFIRM_DELETE = "再点一次确认删除"

        /** 这一页最多显示多少条气泡 */
        private const val MAX_BUBBLES = 40

        /** 错误文字用的红 */
        private val DANGER_INK: Int = 0xFFB3261E.toInt()

        /** 从设置页进入：只走应用内显式 Intent（manifest 里 exported=false）。 */
        fun intent(ctx: Context): Intent = Intent(ctx, LiveTalkActivity::class.java)
    }
}
