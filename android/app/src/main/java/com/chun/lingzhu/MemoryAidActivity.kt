package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 第 15 条「AI 生成记忆辅助」的页面：把一张卡的四种助记画出来。
 *
 * 三条界面规矩（需求里写死的）：
 *  1. 先说清楚卡面会发到云端：隐私提示放最上面。卡片的正面背面就是资料本身，
 *     用户有权在点「生成」之前看见这句话；不想发的可以勾「只用本地生成」，整页一个请求都不发。
 *  2. 每条都标来源：云端写的标「云端」，本机规则拼的标「本地」，失败时那句提示必须说出来。
 *     这是「绝不假装成功」在界面上的落点 —— 四条本地助记照样显示，但绝不冒充模型生成的。
 *  3. 花了多少钱要说：真发了请求就按微元口径显示金额；缓存命中或纯本地时明说这次没花钱。
 *
 * 线程：生成走后台线程（网络请求不能在主线程）。取消用一个 AtomicBoolean 交给 ApiClient，
 * 关页面时置位，请求会在下一次读流时收手，不会白等 90 秒、也不会替用户把钱花完。
 */
class MemoryAidActivity : Activity() {

    companion object {
        /** 卡片正面（必填）。空的话页面直接说「这张卡没有正面」，不发请求 */
        const val EXTRA_FRONT = "com.chun.lingzhu.memoryaid.FRONT"

        /** 卡片背面（可空：正面自带答案的卡也允许） */
        const val EXTRA_BACK = "com.chun.lingzhu.memoryaid.BACK"

        /** 牌组名。只影响缓存键与标题显示，空则按「默认」 */
        const val EXTRA_DECK = "com.chun.lingzhu.memoryaid.DECK"

        /** 本次强制只用本地（一次性覆盖，不动持久设置）。缺省 false */
        const val EXTRA_LOCAL_ONLY = "com.chun.lingzhu.memoryaid.LOCAL_ONLY"

        /** 复习页 / 卡片详情页照抄这一句就能跳过来 */
        /** 造一个跳转 Intent。**不能叫 intent** —— 会和 Activity.intent 属性撞名，
         *  类内所有 intent 引用都会变成「需要传参的函数」，编译报一堆 Context 类型不匹配 */
        fun of(ctx: Context, deck: String, front: String, back: String): Intent =
            Intent(ctx, MemoryAidActivity::class.java)
                .putExtra(EXTRA_DECK, deck)
                .putExtra(EXTRA_FRONT, front)
                .putExtra(EXTRA_BACK, back)
    }

    private lateinit var prefs: Prefs
    private lateinit var store: MemoryAid.Store
    private lateinit var ledger: CostLedger.Store

    private var deck: String = "默认"
    private var front: String = ""
    private var back: String = ""

    /** 这一轮请求的取消标记。每次开始生成都换一个新的（上一轮作废） */
    @Volatile private var cancel = AtomicBoolean(false)

    private lateinit var column: LinearLayout
    private lateinit var costLine: TextView
    private lateinit var goBtn: TextView
    private lateinit var againBtn: TextView
    private lateinit var localOnlyBox: CheckBox

    /** 只重建结果区：隐私提示与按钮不重建，否则每画一次都要重新挂监听 */
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = MemoryAid.Store(this)
        ledger = CostLedger.Store(this)

        deck = intent.getStringExtra(EXTRA_DECK)?.trim().orEmpty().ifEmpty { "默认" }
        front = intent.getStringExtra(EXTRA_FRONT)?.trim().orEmpty()
        back = intent.getStringExtra(EXTRA_BACK)?.trim().orEmpty()
        val forceLocal = intent.getBooleanExtra(EXTRA_LOCAL_ONLY, false)

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = ScrollView(this).apply { addView(column) }
        // Brut.page 只配主题（状态栏、背景色），它不会把视图挂上去。
        // 漏了这行整页就是一片空白：Activity 正常启动、焦点也有、日志无异常，
        // 但屏幕上一个字都没有 —— 只有装到真机点开才看得见。
        setContentView(page)
        Brut.page(this, page)
        buildChrome(forceLocal)

        if (front.isEmpty() && back.isEmpty()) {
            showNothingToDo()
        } else {
            // 进来先看缓存：有云端结果就直接画，一个请求都不发
            val hit = cachedAids()
            if (hit.isNotEmpty()) render(MemoryAid.Answer(hit, contacted = false, cached = true, warning = ""))
            // 没缓存且没勾「只用本地」时自动生成一次，用户不用先点按钮
            if (hit.isEmpty() && !localOnlyBox.isChecked) generate(force = false)
        }
    }

    // ---------------- 固定部分 ----------------

    private fun buildChrome(forceLocal: Boolean) {
        column.addView(TextView(this).apply {
            text = "记忆辅助 · " + deck
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
        })

        // 1) 隐私提示：放最前面，且写清「发什么」与「发到哪」
        column.addView(note(
            "生成时会把这张卡的正面和背面发给你配置的接口（设置 → 接口地址）。" +
                "不想发就勾下面的「只用本地生成」，整页一个请求都不会发。"
        ))

        // 2) 卡面预览：让用户确认在给哪张卡生成（防止从复习页点错卡）
        column.addView(note("正面：" + front.ifEmpty { "（空）" }))
        column.addView(note("背面：" + back.ifEmpty { "（空）" }))

        // 3) 「只用本地生成」：持久设置与本次 extra 取或
        localOnlyBox = CheckBox(this).apply {
            text = "只用本地生成（不发任何请求，质量有限）"
            isChecked = forceLocal || prefs.memoryAidLocalOnly
            setTextColor(Brut.INK)
            setOnCheckedChangeListener { _, checked ->
                prefs.memoryAidLocalOnly = checked
                // 勾上的当下就换成本地结果，否则用户以为开关没生效
                if (checked) render(MemoryAid.localOnly(deck, front, back))
            }
        }
        column.addView(localOnlyBox)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        goBtn = Ui.button(this, "生成助记") { generate(force = false) }.also {
            Ui.emphasize(it)
            row.addView(it, rowParams())
        }
        againBtn = Ui.button(this, "再生成一次") { generate(force = true) }.also { row.addView(it, rowParams()) }
        column.addView(row)

        costLine = note("")
        column.addView(costLine)

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(resultBox)
    }

    private fun rowParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { rightMargin = Ui.dp(this@MemoryAidActivity, 8f) }

    private fun note(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(Brut.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, Ui.dp(this@MemoryAidActivity, 4f), 0, Ui.dp(this@MemoryAidActivity, 4f))
    }

    // ---------------- 数据 ----------------

    private fun key(): String = MemoryAid.cardKeyOf(deck, front, back)

    private fun cachedAids(): List<MemoryAid.Aid> = MemoryAid.cached(store.book().entries, key()) ?: emptyList()

    // ---------------- 生成 ----------------

    /**
     * 跑一轮生成。所有结论都经 render 上屏，页面自己不做第二套成功判断
     * （「四条都在就算成功」是 MemoryAid.complete 的事）—— 两处判断必然走偏。
     */
    private fun generate(force: Boolean) {
        if (front.isEmpty() && back.isEmpty()) { showNothingToDo(); return }
        if (localOnlyBox.isChecked) {
            render(MemoryAid.localOnly(deck, front, back))
            return
        }
        if (!prefs.hasApi()) {
            // 没配接口时不假装在生成：说清原因，并把本地结果直接给出来
            render(MemoryAid.Answer(
                aids = MemoryAid.localAids(front, back, key(), System.currentTimeMillis()),
                contacted = false, cached = false,
                warning = "还没配置接口（设置 → 接口地址），下面四条是本机规则拼的。",
            ))
            return
        }

        cancel = AtomicBoolean(false)
        setBusy(true)
        val my = cancel
        val cfg = prefs.api()
        val book = store.book()
        Thread {
            val answer = MemoryAid.run(
                deck = deck, front = front, back = back,
                book = book, cfg = cfg, force = force, cancel = my,
                cloud = { prompt, c -> callCloud(cfg, prompt, c) },
            )
            // 落盘 + 记账只在「真拿到了云端结果」时做：本地 / 缓存 / 失败一律不记
            if (answer.contacted && answer.micro > 0L) {
                val k = key()
                val old = store.book().find(k)
                store.put((old ?: MemoryAid.Entry(k, deck, front, emptyList())).push(answer.aids))
                val today = prefs.todayInt().toString()   // yyyyMMdd，和设置页账单同一口径
                ledger.record(today, today.substring(0, 6), answer.model,
                    CostLedger.Feature.MEMORY_AID, answer.pin, answer.pout)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (my.get() && answer.aids.isEmpty()) return@runOnUiThread   // 取消且无结果：什么都不画
                render(answer)
            }
        }.start()
    }

    /**
     * 真调接口。用流式接口是为了拿到真实 usage：只有它能把这一笔准确记进台账；
     * 拿不到就交给 MemoryAid.run 按长度估（宁高勿低）。
     */
    private fun callCloud(cfg: ApiConfig, prompt: String, cancel: AtomicBoolean): Triple<String, Long, Long> {
        val client = ApiClient(cfg)
        var pin = 0L
        var pout = 0L
        val body = Bodies.text(cfg, prompt, MemoryAid.BUDGET, thinkingOff = true)
        val text = client.stream(body, object : ApiClient.Listener {
            override fun onText(text: String) {}   // 助记要整片解析，不逐字上屏
            override fun onUsage(prompt: Int, completion: Int) { pin = prompt.toLong(); pout = completion.toLong() }
        }, cancel).first
        if (text.isBlank()) throw ApiException("接口没有返回内容")
        return Triple(text, pin, pout)
    }

    private fun setBusy(busy: Boolean) {
        goBtn.isEnabled = !busy
        againBtn.isEnabled = !busy
        if (busy) costLine.text = "正在生成…"
    }

    // ---------------- 上屏 ----------------

    private fun render(answer: MemoryAid.Answer) {
        resultBox.removeAllViews()
        if (answer.warning.isNotEmpty()) resultBox.addView(warnCard(answer.warning))
        if (answer.aids.isEmpty()) {
            resultBox.addView(note("这张卡没有可生成的内容。"))
            costLine.text = ""
            return
        }
        for (a in answer.aids) resultBox.addView(aidCard(a))
        costLine.text = answer.sourceLine + " " + costText(answer)
    }

    /** 钱怎么说：花了就报金额，没花就明说没花（不用「小额」这类含糊话糊过去） */
    private fun costText(a: MemoryAid.Answer): String = when {
        a.micro > 0L -> "本次花费 " + a.microText + "。"
        a.cached -> "本次没有花钱（用的是上次的结果）。"
        else -> "本次没有花钱。"
    }

    /** 一条助记：类型 + 来源徽标 + 标题 + 正文 */
    private fun aidCard(a: MemoryAid.Aid): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MemoryAidActivity, 10f), Ui.dp(this@MemoryAidActivity, 8f),
                Ui.dp(this@MemoryAidActivity, 10f), Ui.dp(this@MemoryAidActivity, 8f))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = a.kind.label
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
        })
        head.addView(TextView(this).apply {
            text = "  " + a.source.label + "  "
            setTextColor(if (a.source == MemoryAid.AidSource.CLOUD) Brut.MINT else Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = Ui.pill(this@MemoryAidActivity)
        })
        box.addView(head)
        if (a.title.isNotEmpty() && a.title != a.kind.label) box.addView(note(a.title))
        box.addView(TextView(this).apply {
            text = a.body
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        })
        Brut.card(box)
        return box
    }

    /** 失败 / 降级提示：单独一块，不能被误看成一条助记 */
    private fun warnCard(msg: String): View {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Brut.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(Ui.dp(this@MemoryAidActivity, 10f), Ui.dp(this@MemoryAidActivity, 8f),
                Ui.dp(this@MemoryAidActivity, 10f), Ui.dp(this@MemoryAidActivity, 8f))
        }
        Brut.card(tv)
        return tv
    }

    private fun showNothingToDo() {
        resultBox.removeAllViews()
        resultBox.addView(note("这张卡正面和背面都是空的，没有可生成的内容。"))
        costLine.text = ""
        goBtn.isEnabled = false
        againBtn.isEnabled = false
    }

    // ---------------- 生命周期 ----------------

    override fun onDestroy() {
        cancel.set(true)   // 关页面就掐断请求：别让用户走了还在为一次生成付钱
        super.onDestroy()
    }
}
