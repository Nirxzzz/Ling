package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * v2.7 Anki 牌组页：**导入 .apkg → 选牌组 → 按原模板复习**。
 *
 * ## 这一页只做四件事
 * 1. 用 SAF 挑一个 `.apkg` 交给 [AnkiDeckStore.importPackage]，把真实结果（多少张笔记 /
 *    多少张卡 / 多少素材 / 哪些不支持）原样报出来；
 * 2. 列出集合与牌组（带「共 N 张 · 到期 M 张」），让用户选一个开始；
 * 3. 一轮只取**一次快照**（[AnkiDeckStore.queue]），正面 → 翻面 → 4 档 FSRS 评分；
 * 4. 评分走 [AnkiDeckStore.answer] 落盘，**落盘成功才前进**；失败原地报错、卡片不前进。
 *
 * ## 为什么状态不放 Activity 里
 * 旋转、从后台回来、系统回收重画都会重建 Activity。如果队列下标、是否已翻面、本轮已评
 * 几张存在 Activity 字段里，转一下屏就会「从头再来」，更糟的是可能把同一张卡再评一次
 * —— 那等于给用户凭空记了一次复习。所以这一页的**全部可变状态**都在 [Session] 里，
 * Activity 只是它的一个观察者（弱引用，旋转后重新 attach）。
 *
 * ## 什么叫「真的做完了」
 * 只有一种：本轮快照里的卡**每一张都评过**。此时说「本轮 N 张都评完了」，并给一个「再来一轮」。
 * 跳过的不记评分，所以有一张被跳过时就如实说「看过了 M 张、跳过 K 张」，不能说成都评完了。
 * **点了「再来一轮」，新一轮快照真的空了**，才说「这个牌组现在没有到期的卡」。
 * 绝不因为「下标走到头」就宣布牌组清空 —— 快照是有上限的（一次 [QUEUE_LIMIT] 张）。
 *
 * ## 不做什么
 *  - 不搬 Anki 的复习历史（那套调度不通用），导入后一律按 New 起算，走自家 [Fsrs]；
 *  - 不把卡面拍平成纯文本（那会丢图片、音频、cloze、提示折叠），见 [AnkiCardWebView]；
 *  - 不联网。牌组资源用的是两个公开站点的**外部浏览器**链接，App 自己不抓。
 */
class AnkiActivity : Activity() {
    /** 当前卡面的 WebView。换页 / 换卡都要先 destroy 旧的，否则原生 WebView 会一直攥着渲染进程。 */
    private var web: AnkiCardWebView? = null

    /** 当前这张卡渲染好的两面 + 素材目录。翻面复用它，不用再渲染一次。 */
    private var faceRendered: AnkiTemplate.Rendered? = null
    private var mediaRoot: java.io.File? = null

    /** 底部动作区（显示答案 / 4 档评分 / 报错）。翻面时只换这里，不动 WebView。 */
    private var actionBar: LinearLayout? = null

    /** 缺素材 / 渲染警告那行，以及它要显示的内容 */
    private var warnView: TextView? = null
    private val warned = ArrayList<String>()

    /** onCreate 后面紧跟的那次 onResume 不该再读一遍盘；只有「真的离开过」才重读 */
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        val s = session ?: Session(applicationContext).also { session = it }
        s.attach(this)
        draw()
        // 会话是跨页存活的：只在**从没读过**的时候去读，旋转回来不重复扫盘
        if (!s.loaded && !s.busy && !s.importing) loadCollections(s)
    }

    override fun onResume() {
        super.onResume()
        val s = session
        if (s == null) return
        s.attach(this)
        if (!resumed) {        // 首次 onResume：读不读盘交给 onCreate 定，不再重复扫一遍
            resumed = true
            return
        }
        // 从外部浏览器 / 文件选择器回来：清单可能变了（刚导入的包、刚评过的到期数），重读一次。
        // 复习中途不重读 —— 不能把用户正在看的这一轮打断。
        if (s.mode == MODE_LIST && !s.busy && !s.importing) {
            s.loaded = false
            loadCollections(s)
        }
    }

    override fun onDestroy() {
        destroyWeb()
        session?.detach(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val s = session
        if (s != null && s.mode == MODE_STUDY) {
            // 复习中途返回 = 回牌组列表。会话与进度**不丢**，再点进来接着看这一张。
            backToList(s)
            return
        }
        super.onBackPressed()
    }

    // ────────────────────────── 画 ──────────────────────────

    private fun draw() {
        val s = session ?: return
        if (s.mode == MODE_STUDY) drawStudy(s) else drawList(s)
    }

    /** 丢掉上一张卡面的 WebView。原生 WebView 不 destroy 会一直攥着渲染进程和定时器。 */
    private fun destroyWeb() {
        val w = web ?: return
        web = null
        try {
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.stopLoading()
            w.destroy()
        } catch (e: Exception) {
            // 已经 detach 过就算了：销毁失败不该打断页面切换
        }
    }

    /** 牌组列表页：导入 + 集合 + 牌组 + 外部资源 */
    private fun drawList(s: Session) {
        destroyWeb()
        // 上一页的底栏 / 警告行已经随旧视图一起被丢掉了：不清掉指针，后台回来的
        // fillActions / refreshWarn 还会往一个已经 detach 的 View 上写
        actionBar = null
        warnView = null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 必须**在** Brut.page 之前设边距：page 装的 inset 监听会按「调用那一刻的 padding」重算，
            // 之后再改就会被下一次系统栏回调整个覆盖掉（左右留白凭空消失）。
            setPadding(Ui.dp(this@AnkiActivity, 16f), Ui.dp(this@AnkiActivity, 12f),
                Ui.dp(this@AnkiActivity, 16f), Ui.dp(this@AnkiActivity, 28f))
        }
        val sv = ScrollView(this).apply { addView(col) }
        setContentView(sv)
        Brut.page(this, sv)
        col.addView(TextView(this).apply {
            text = "Anki 牌组"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(tip("导入你自己的 .apkg，卡面按原模板渲染（图片 / 音频 / cloze / 提示都在）。\n" +
            "复习进度用本机的 FSRS 重算 —— 不搬 Anki 的历史，导入即从「新卡」开始。"))

        // 导入
        val importRow = Ui.row(this)
        importRow.addView(Button(this).apply {
            text = if (s.importing) "正在导入…" else "导入 .apkg"
            tag = Brut.PRIMARY
            isEnabled = !s.importing
            setOnClickListener { if (!s.importing) pickPackage() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(importRow)
        s.importMsg?.let { msg ->
            col.addView(TextView(this).apply {
                text = msg
                textSize = 13f
                setLineSpacing(0f, 1.25f)
                setPadding(0, Ui.dp(this@AnkiActivity, 6f), 0, 0)
            })
        }
        s.error?.let { err -> col.addView(errLine(err)) }

        // 集合
        if (s.collections.size > 1) {
            col.addView(section("集合"))
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in s.collections) {
                val on = c == s.collection
                bar.addView(Button(this).apply {
                    text = (if (on) "▸ " else "") + c
                    if (on) tag = Brut.PRIMARY
                    setOnClickListener { if (!on) switchCollection(s, c) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dp(this@AnkiActivity, 6f) })
            }
            col.addView(bar)
        }

        // 牌组
        if (s.busy) {
            // busy 要先判：第一次进来盘还没扫完，「还没导入过」会把加载中谎报成没数据
            col.addView(tip("正在读牌组…"))
        } else if (s.collections.isEmpty() && s.error != null) {
            // 读盘失败 ≠ 没导入过：错误行已经说过原因，这里不再补一句「还没有导入过牌组」把人误导
            col.addView(tip("牌组清单没读出来，所以先不列 —— 上面那句是原因。"))
        } else if (s.collections.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "还没有导入过牌组。上面点「导入 .apkg」选一个文件即可（数据只存在本机）。"
                textSize = 14f
                setPadding(0, Ui.dp(this@AnkiActivity, 14f), 0, 0)
            })
        } else if (s.decks.isEmpty() && s.error != null) {
            // 同上：读盘失败时 decks 也是空的，但不能因此说「这个集合没有可复习的卡」
            col.addView(tip("这个集合的牌组没读出来 —— 上面那句是原因。"))
        } else if (s.decks.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "这个集合里没有可复习的卡（可能是空牌组或只有不支持的卡）。"
                textSize = 14f
                setPadding(0, Ui.dp(this@AnkiActivity, 14f), 0, 0)
            })
        } else {
            col.addView(section("牌组"))
            val total = s.decks.sumOf { it.cards }
            val due = s.decks.sumOf { it.due }
            col.addView(deckRow("全部牌组", total, due, null, s))
            for (d in s.decks) col.addView(deckRow(prettyDeck(d.name), d.cards, d.due, d.id, s))
            col.addView(tip("「到期」= 现在就该复习的（新卡 + 到点的旧卡）。一次最多取 $QUEUE_LIMIT 张，" +
                "评完可以再来一轮 —— 一轮走完不等于牌组就清了。"))
        }

        // 外部资源：只开外部浏览器，App 自己不去抓
        col.addView(section("去哪儿找牌组"))
        val links = Ui.row(this)
        links.addView(Button(this).apply {
            text = "AnkiWeb 共享牌组"
            setOnClickListener { Links.open(this@AnkiActivity, ANKIWEB) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = Ui.dp(this@AnkiActivity, 6f) })
        links.addView(Button(this).apply {
            text = "awesome-anki（GitHub）"
            setOnClickListener { Links.open(this@AnkiActivity, AWESOME) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(links)
        col.addView(tip("从上面两个站点下载 .apkg 再回来导入。官方共享牌组页需要注册 AnkiWeb 账号；" +
            "GitHub 那份是一张按科目 / 语言整理好的清单。"))

        col.addView(Button(this).apply {
            text = "关掉"
            setOnClickListener { finish() }
        })
        Brut.style(col)
    }

    /** 复习页：牌组 + 卡面 + 翻面 / 评分 */
    private fun drawStudy(s: Session) {
        destroyWeb()
        faceRendered = null
        mediaRoot = null
        // 本轮结束的分支会直接 return，不会再建底栏：先把旧指针丢掉
        actionBar = null
        warnView = null
        warned.clear()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 边距要在 Brut.page 之前落地：page 的 inset 监听按调用时的 padding 记基准，
            // 之后再 setPadding 会被下一次系统栏回调整个覆盖
            setPadding(Ui.dp(this@AnkiActivity, 14f), 0,
                Ui.dp(this@AnkiActivity, 14f), Ui.dp(this@AnkiActivity, 10f))
        }
        setContentView(col)
        Brut.page(this, col)

        val top = Ui.row(this)
        top.addView(Button(this).apply {
            text = "‹ 牌组"
            setOnClickListener { backToList(s) }
        })
        top.addView(TextView(this).apply {
            text = s.deckLabel
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            setPadding(Ui.dp(this@AnkiActivity, 10f), 0, Ui.dp(this@AnkiActivity, 10f), 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        top.addView(TextView(this).apply {
            text = when {
                // 队列是先清空再去后台取的：这时候「0 张」不是事实，别抢先下结论
                s.busy -> "正在取…"
                s.queue.isEmpty() -> "0 张"
                else -> "第 ${s.index + 1} / ${s.queue.size} 张 · 已评 ${s.rated}"
            }
            textSize = 12.5f
            alpha = 0.75f
        })
        col.addView(top)

        val card = s.queue.getOrNull(s.index)
        if (card == null) {
            drawRoundEnd(s, col)
            Brut.style(col)
            return
        }

        val rendered = try {
            AnkiTemplate.render(card)
        } catch (e: Exception) {
            // 渲染器承诺不抛；真抛了也不能当「没卡了」—— 如实报错并给「跳过这张」
            null
        }
        faceRendered = rendered
        rendered?.warnings?.let { warned.addAll(it) }

        if (rendered == null) {
            col.addView(TextView(this).apply {
                text = "这张卡渲染失败了（模板里可能有本页不支持的东西）。可以跳过它，别的卡不受影响。"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = Ui.dp(this@AnkiActivity, 10f)
                }
            })
        } else {
            mediaRoot = try { s.store.mediaDir(card.collection) } catch (e: Exception) { null }
            val w = AnkiCardWebView(this)
            w.onExternalLink = { url -> Links.open(this, url) }
            // 先认领再载入：装载过程中拦截线程就会回报缺失素材，那时 web 必须已经是它
            web = w
            // 自己是谁一并带回去：判别在主线程做，迟到的旧 WebView 回调不会记到眼下这张卡上
            w.onMissingMedia = { name -> noteMissing(w, name) }
            w.show(if (s.revealed) rendered.back else rendered.front, rendered.css, mediaRoot, Ui.TX1)
            // 卡面吃掉剩余高度：长卡自己滚，评分按钮始终露在下面
            col.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        col.addView(warnLine())
        actionBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actionBar)
        Brut.style(col)
        fillActions(s)
        refreshWarn()
    }

    /**
     * 翻面：**不重建页面**。WebView 只换一面内容，滚动位置和素材都留着，
     * 用户不会看到卡面闪一下，也不会被弹回顶部。
     */
    private fun reveal(s: Session) {
        val r = faceRendered
        val w = web
        if (r == null || w == null) {
            s.revealed = true
            draw()
            return
        }
        s.revealed = true
        w.show(r.back, r.css, mediaRoot, Ui.TX1)
        fillActions(s)
        refreshWarn()
    }

    /** 底栏：未翻面只有「显示答案」，翻面后是 4 档评分 */
    private fun fillActions(s: Session) {
        val bar = actionBar ?: return
        bar.removeAllViews()
        val card = s.queue.getOrNull(s.index)
        when {
            card == null -> Unit
            // 渲染失败的卡没有卡面：**只给「跳过（不记评分）」**。
            // 评分是对着看到的卡面做的判断；卡面都没出来就给 4 档按钮，等于让用户闭眼打分，
            // 还会把一次没看过的复习照着一档评分写进 FSRS —— 那是凭空造记忆数据。
            faceRendered == null -> {
                bar.addView(tip("这张卡渲染失败了，没有卡面可看，所以不给评分。可以跳过它（不记评分）。"))
                bar.addView(Button(this).apply {
                    text = "跳过这张（不记评分）"
                    setOnClickListener { skip(s) }
                })
            }
            !s.revealed -> bar.addView(Button(this).apply {
                text = "显示答案"
                tag = Brut.PRIMARY
                setOnClickListener { reveal(s) }
            })
            s.pending != null -> bar.addView(TextView(this).apply {
                text = "正在保存评分…"
                textSize = 13f
                setPadding(0, Ui.dp(this@AnkiActivity, 8f), 0, 0)
            })
            else -> {
                // 4 档评分：标签带「点了之后大概什么时候再见」，比只有 Again/Hard/Good/Easy 更好判断
                val now = System.currentTimeMillis()
                val previews: Map<Fsrs.Rating, Fsrs.Card> =
                    try { Fsrs.preview(card.card.state, now) } catch (e: Exception) { emptyMap() }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((r, label) in RATINGS) {
                    val eta = previews[r]?.due?.let { etaText(it, now) }
                    row.addView(Button(this).apply {
                        text = if (eta == null) label else "$label\n$eta"
                        textSize = 12.5f
                        gravity = Gravity.CENTER
                        if (r == Fsrs.Rating.Good) tag = Brut.PRIMARY
                        setOnClickListener { rate(s, r) }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        rightMargin = Ui.dp(this@AnkiActivity, 5f)
                    })
                }
                bar.addView(row)
                bar.addView(tip("评分立刻存盘；存失败会在这里报错，卡片不会前进。"))
            }
        }
        s.error?.let { err -> bar.addView(errLine(err)) }
        Brut.style(bar)
    }

    /** 缺素材 / 渲染警告那一行。文案必须说清「包里没有」，不能静默变白框 */
    private fun warnLine(): TextView = TextView(this).apply {
        textSize = 11.5f
        alpha = 0.80f
        setLineSpacing(0f, 1.2f)
        setPadding(0, Ui.dp(this@AnkiActivity, 4f), 0, 0)
        visibility = View.GONE
        warnView = this
    }

    /**
     * 素材缺失是 WebView 在拦截线程上回调的：改界面必须回主线程。
     *
     * [from] 是报这条的 WebView —— 换卡 / 翻面之后旧 WebView 的迟到回调不能算到眼下这张卡头上。
     * 比对放在主线程做（`web` 只在主线程写，在拦截线程上读它没有可见性保证）。
     */
    private fun noteMissing(from: AnkiCardWebView, name: String) {
        runOnUiThread {
            if (web !== from) return@runOnUiThread
            val line = "缺素材：$name"
            if (!warned.contains(line)) {
                warned.add(line)
                refreshWarn()
            }
        }
    }

    private fun refreshWarn() {
        val v = warnView ?: return
        // WebView 自己记的缺失也要收进来（模板里写了 src，包里就是没有那张图）
        web?.missingMedia()?.forEach { n ->
            val line = "缺素材：$n"
            if (!warned.contains(line)) warned.add(line)
        }
        if (warned.isEmpty()) {
            v.visibility = View.GONE
            return
        }
        // 一张卡缺几十个素材时不能把整行撑成一面墙：只列前几条，剩下的说清还有多少 ——
        // 数量照实给，不是把缺的东西吞掉。
        val head = warned.take(WARN_SHOWN)
        v.text = if (warned.size > WARN_SHOWN) {
            head.joinToString(" · ") + " · …还有 ${warned.size - WARN_SHOWN} 条"
        } else {
            head.joinToString(" · ")
        }
        v.visibility = View.VISIBLE
    }

    /** 本轮快照走完 / 快照本来就是空的 */
    private fun drawRoundEnd(s: Session, col: LinearLayout) {
        col.addView(TextView(this).apply {
            text = when {
                // 刚进复习页时队列是先清空、再去后台取的：这时候「没有到期的卡」只是还没读出来
                s.busy -> "正在取这一轮的卡…"
                // 取队列失败 ≠ 没有卡：错误行就在下面，别把读盘失败说成「到期 0 张」。
                // 只在队列确实空时才这么说 —— 有卡没看那是另一回事，下一支说得更准。
                s.queue.isEmpty() && s.error != null -> "这一轮的卡没取出来 —— 下面那句是原因。"
                s.queue.isEmpty() -> "这个牌组现在没有到期的卡。"
                // 快照里还有卡却没走完（防御：正常走完一定 exhausted）：说清还剩几张，别谎称评完
                !s.exhausted -> "这一轮还有 ${(s.queue.size - s.index).coerceAtLeast(0)} 张没看。"
                // 跳过的不记评分，所以「都评完了」只在每张都真的评过时才说
                s.rated >= s.queue.size -> "本轮 ${s.queue.size} 张都评完了（一轮最多 $QUEUE_LIMIT 张）。"
                else -> "本轮 ${s.queue.size} 张都看过了：评了 ${s.rated} 张，" +
                    "跳过 ${s.queue.size - s.rated} 张（跳过的不记评分，下次还会出现）。"
            }
            textSize = 15f
            setPadding(0, Ui.dp(this@AnkiActivity, 18f), 0, Ui.dp(this@AnkiActivity, 6f))
        })
        if (s.exhausted && s.queue.isNotEmpty()) {
            col.addView(tip("点「再来一轮」会按现在的到期状态重新取队列；真的没有卡了才说清空。"))
        }
        col.addView(Button(this).apply {
            text = "再来一轮"
            tag = Brut.PRIMARY
            isEnabled = !s.busy
            setOnClickListener { startRound(s, s.deckId, s.deckLabel) }
        })
        col.addView(Button(this).apply {
            text = "换一个牌组"
            setOnClickListener { backToList(s) }
        })
        s.error?.let { err -> col.addView(errLine(err)) }
    }

    // ──────────────── 数据动作：IO 在子线程，Session 状态一律回主线程写 ────────────────

    private fun loadCollections(s: Session) {
        val my = ++s.gen
        val prev = s.collection          // 主线程取一次：子线程不再回头读会被改的字段
        s.busy = true
        s.error = null
        s.notifyHost()
        bg("lz-anki-cols") {
            var cols: List<String> = emptyList()
            var err: String? = null
            var decks: List<AnkiData.Deck> = emptyList()
            var picked: String? = null
            try {
                cols = s.store.collections()
                picked = prev?.takeIf { it in cols } ?: cols.firstOrNull()
                if (picked != null) decks = s.store.decks(picked)
            } catch (e: Exception) {
                err = "读牌组失败：" + (e.message ?: e.javaClass.simpleName)
            }
            val c = cols; val d = decks; val p = picked; val bad = err
            // 这三个字段（集合清单 / 当前集合 / 牌组）一起生效才是一份能画的快照。
            // 在子线程逐个赋值，主线程可能画到「新集合 + 旧牌组」的半截状态；统一回主线程写。
            runOnUiThread {
                if (s.gen != my) return@runOnUiThread
                s.collections = c
                s.collection = p
                s.decks = d
                s.busy = false
                s.error = bad
                // 读成功才记「读过」：失败了下次进来还得再试一次，不能把错误钉成常态
                s.loaded = bad == null
                s.notifyHost()
            }
        }
    }

    /** 回到牌组列表。顺手重读牌组：刚评过的卡，到期数已经变了 */
    private fun backToList(s: Session) {
        s.mode = MODE_LIST
        s.loaded = false
        if (!s.busy && !s.importing) loadCollections(s) else draw()
    }

    private fun switchCollection(s: Session, name: String) {
        s.collection = name
        s.mode = MODE_LIST
        s.error = null
        s.decks = emptyList()
        val my = ++s.gen
        s.busy = true
        s.notifyHost()
        bg("lz-anki-decks") {
            var decks: List<AnkiData.Deck> = emptyList()
            var err: String? = null
            try { decks = s.store.decks(name) } catch (e: Exception) { err = "读牌组失败：" + (e.message ?: e.javaClass.simpleName) }
            val d = decks; val bad = err
            runOnUiThread {
                if (s.gen != my) return@runOnUiThread
                s.decks = d
                s.busy = false
                s.error = bad
                s.notifyHost()
            }
        }
    }

    /** 取一轮快照。**每次进复习页只取一次**，评完再取就是新的一轮。 */
    private fun startRound(s: Session, deckId: Long?, label: String) {
        val col = s.collection ?: return
        val my = ++s.gen
        s.deckId = deckId
        s.deckLabel = label
        s.mode = MODE_STUDY
        s.queue = emptyList()
        s.index = 0
        s.revealed = false
        s.rated = 0
        s.exhausted = false
        s.pending = null
        s.error = null
        s.busy = true
        s.notifyHost()
        bg("lz-anki-queue") {
            var q: List<AnkiData.StudyCard> = emptyList()
            var err: String? = null
            try {
                q = s.store.queue(col, deckId, System.currentTimeMillis(), QUEUE_LIMIT)
            } catch (e: Exception) {
                err = "取复习队列失败：" + (e.message ?: e.javaClass.simpleName)
            }
            val list = q; val bad = err
            runOnUiThread {
                if (s.gen != my) return@runOnUiThread
                s.queue = list
                s.busy = false
                s.error = bad
                s.exhausted = list.isEmpty()
                s.notifyHost()
            }
        }
    }

    /** 评分：落盘成功才前进；失败原地报错，卡片留在原位（绝不假装评过） */
    private fun rate(s: Session, rating: Fsrs.Rating) {
        if (!s.revealed || s.pending != null) return
        val col = s.collection ?: return
        val card = s.queue.getOrNull(s.index) ?: return
        // 这一枪属于哪一轮、哪张卡、第几次尝试：只在主线程取，随结果一起回来核对。
        // 只有 gen+cardId+rating 还不够 —— 同一张卡上失败重试同档位时，三者也完全一样，
        // 旧那次迟到的结果就会把新那次的 pending 清掉，按钮复活变成能连评。
        val my = s.gen
        val attempt = ++s.attempt
        val cardId = card.card.id
        s.pending = rating
        s.error = null
        fillActions(s)   // 立刻把按钮换成「正在保存…」防连点；卡面不动，免得闪一下
        bg("lz-anki-answer") {
            val err = try {
                s.store.answer(col, cardId, rating, System.currentTimeMillis())
                null
            } catch (e: Exception) {
                "评分没存上（" + (e.message ?: e.javaClass.simpleName) + "）—— 这张卡没有前进，可以重新点。"
            }
            // 后台只负责落盘 + 把结果带回来。Session 的状态**一律回主线程写**：
            // 在这里直接改 index/pending 会跟主线程的 startRound / skip 抢同一批字段。
            runOnUiThread { finishRate(s, my, attempt, cardId, rating, err) }
        }
    }

    /**
     * 评分结果落地。**必须在主线程调用。**
     *
     * 旧一次尝试 / 旧一轮的结果一律**不前进**（不涨下标、不写错误）：那一枪已经作废，
     * 不能让迟到的一次成功把眼下这张卡顶过去，也不能把上一轮的错误糊到新页面上。
     * 但若这只卡上的 pending 还挂着自己（前面两个闸门已确认没人接管），就要把它收回，
     * 否则页面会永远停在「正在保存评分…」、按钮全灰。
     */
    private fun finishRate(
        s: Session, gen: Int, attempt: Int, cardId: Long, rating: Fsrs.Rating, err: String?,
    ) {
        // attempt 每次点评分都自增且单调，所以「号对不上」= 这张卡上已经又点过一次，这一枪彻底作废
        if (s.attempt != attempt) return
        if (s.pending != rating) return                 // 已经被别的路径改过（跳过、重进）：同样不碰
        if (s.gen != gen) {
            // 换了轮次 / 换了牌组：不前进、不报错（错误属于上一轮，别糊到新页面上），
            // 但这一枪自己占着的 pending 要收回，免得那一轮回来时卡在「正在保存评分…」
            s.pending = null
            s.notifyHost()
            return
        }
        if (s.queue.getOrNull(s.index)?.card?.id != cardId) return   // 卡已经不是当初那张
        s.pending = null
        if (err == null) {
            s.rated++
            s.index++
            s.revealed = false
            s.exhausted = s.index >= s.queue.size
        } else {
            s.error = err
        }
        s.notifyHost()
    }

    /**
     * 跳过这张：只在渲染失败时提供。**不写评分**，所以这张卡下次还在队列里 ——
     * 页面上如实写着「不记评分」，不能让它看起来像评过了。
     */
    private fun skip(s: Session) {
        if (s.pending != null) return
        if (s.index >= s.queue.size) return
        s.index++
        s.revealed = false
        s.exhausted = s.index >= s.queue.size
        s.error = null
        draw()
    }

    // ────────────────────────── SAF 导入 ──────────────────────────

    private fun pickPackage() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pickerIntent(), REQ_PKG)
        } catch (e: Exception) {
            toast("打不开文件选择器：" + (e.message ?: ""))
        }
    }

    /**
     * 选 `.apkg`：标准 SAF `ACTION_OPEN_DOCUMENT`（系统文件选择器，不要存储权限）。
     *
     * 类型放开成任意 MIME（通配 `*`）再给 MIME 提示：各家文件管理器对 `.apkg` 报的 MIME 并不统一
     * （有的报 `application/zip`，有的报 `application/octet-stream`，有的干脆没有），
     * 只认具体 MIME 会让文件在列表里变灰、选都选不到。包里到底能不能读，
     * 由 [AnkiDeckStore.importPackage] 的校验说了算，不看扩展名。
     */
    private fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
            "application/zip", "application/octet-stream", "application/x-anki", "*/*"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PKG) return
        if (resultCode != RESULT_OK) return      // 用户取消：什么都不做（不碰已有牌组）
        val uri = data?.data ?: return
        val s = session ?: return
        if (s.importing) return
        val prevCol = s.collection       // 主线程取一次：导入期间用户可能切了集合
        s.importing = true
        s.error = null
        s.importMsg = "正在导入…（大包要等一会，素材是一张张校验的）"
        // 导入自己认这一轮：在跑的旧扫描（loadCollections 等）结果会被 gen 挡掉，也就永远不会
        // 把它置的 busy 收回，所以这里一并收回，否则列表页会一直停在「正在读牌组…」。
        val my = ++s.gen
        s.busy = false
        draw()
        bg("lz-anki-import") {
            val name = try { displayName(uri) } catch (e: Exception) { null }
            var msg = ""
            var err: String? = null
            var cols: List<String>? = null
            var pick: String? = null
            var decks: List<AnkiData.Deck> = emptyList()
            var imported: String? = null
            try {
                val r = s.store.importPackage(uri)
                imported = r.collection
                val sb = StringBuilder()
                sb.append(if (name != null) "已导入「$name」" else "已导入")
                sb.append("：${r.notes} 张笔记 · ${r.cards} 张卡 · ${r.media} 个素材（集合：${r.collection}）")
                if (r.warnings.isNotEmpty()) {
                    sb.append("\n有 ${r.warnings.size} 条提示：")
                    for (w in r.warnings.take(4)) sb.append("\n· ").append(w)
                    if (r.warnings.size > 4) sb.append("\n· …还有 ${r.warnings.size - 4} 条")
                }
                msg = sb.toString()
            } catch (e: Exception) {
                msg = "没导进来"
                err = "导入失败：" + (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            // 导入成功后刷新集合 / 牌组清单（导入可能新建了一个集合）。
            // 选哪个集合以 store 回来的名字为准 —— 它就是这次真正写进去的那个（重名时会去重成 -2/-3）。
            if (err == null) {
                try {
                    val fresh = s.store.collections()
                    val chosen = imported?.takeIf { it in fresh } ?: importCollection(fresh, prevCol, name)
                    cols = fresh
                    pick = chosen
                    if (chosen != null) decks = s.store.decks(chosen)
                } catch (e: Exception) {
                    err = "导入成功，但读牌组清单失败：" + (e.message ?: e.javaClass.simpleName)
                }
            }
            val m = msg; val bad = err; val c = cols; val p = pick; val d = decks
            runOnUiThread {
                // 同一时刻只会有一次导入在跑（onActivityResult 与按钮都挡着），所以这里可以直接收回标记。
                // 导入标记是这次动作自己的，跟「有没有换轮次」无关 —— 一定要收回，
                // 否则用户在导入期间切了牌组，这一页就会永远停在「正在导入…」、按钮也永远是灰的。
                s.importing = false
                // 结果本身（导了 / 没导进来 + 原因）是既成事实，一定要报出来
                s.importMsg = if (bad == null) m else "$m\n$bad"
                // 集合清单是全局的：导入新建了集合，回到列表就该看到
                if (c != null) s.collections = c
                if (s.gen == my) {
                    // 只有还停在这一轮，才顺手换掉「选中哪个集合」和牌组清单
                    if (c != null) {
                        s.collection = p
                        s.decks = d
                        s.loaded = true
                    }
                    s.error = bad
                }
                s.notifyHost()
            }
        }
    }

    /** 导入后尽量选中「新进来的那个集合」：名字带文件名，找不到就退回当前集合 / 第一个 */
    private fun importCollection(cols: List<String>, current: String?, fileName: String?): String? {
        val stem = fileName?.substringBeforeLast('.')?.lowercase()
        if (!stem.isNullOrBlank()) {
            cols.firstOrNull { it.lowercase().contains(stem) }?.let { return it }
        }
        return current?.takeIf { it in cols } ?: cols.firstOrNull()
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    // ────────────────────────── 小件 ──────────────────────────

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, Ui.dp(this@AnkiActivity, 16f), 0, Ui.dp(this@AnkiActivity, 4f))
    }

    private fun tip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        alpha = 0.75f
        setLineSpacing(0f, 1.3f)
        setPadding(0, Ui.dp(this@AnkiActivity, 6f), 0, 0)
    }

    private fun errLine(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 13f
        setTextColor(Brut.ROSE_DEEP)
        setLineSpacing(0f, 1.25f)
        setPadding(0, Ui.dp(this@AnkiActivity, 8f), 0, 0)
    }

    private fun deckRow(name: String, cards: Int, due: Int, deckId: Long?, s: Session): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(TextView(this).apply {
            text = name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(TextView(this).apply {
            text = "共 $cards 张 · 到期 $due 张"
            textSize = 12.5f
            alpha = 0.75f
            setPadding(0, Ui.dp(this@AnkiActivity, 2f), 0, 0)
        })
        // 只在**真到期**时给入口：队列只取「新卡 + 到点的旧卡」，到期 0 张时点进去必然是空的一轮。
        // 与其让人白点一下再被告知没有卡，不如这里就说清「都在排队」。
        if (due > 0) {
            row.addView(Button(this).apply {
                text = "开始复习（$due 张到期）"
                tag = Brut.PRIMARY
                setOnClickListener { startRound(s, deckId, name) }
            })
        } else if (cards > 0) {
            row.addView(TextView(this).apply {
                text = "现在没有到期的卡（都在排队中，到点后会在这里出现）"
                textSize = 12.5f
                alpha = 0.75f
            })
        }
        row.setPadding(Ui.dp(this@AnkiActivity, 10f), Ui.dp(this@AnkiActivity, 8f), Ui.dp(this@AnkiActivity, 10f), Ui.dp(this@AnkiActivity, 8f))
        Brut.card(row)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@AnkiActivity, 8f) })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun bg(tag: String, block: () -> Unit) = kotlin.concurrent.thread(name = tag, block = block)

    companion object {
        private const val REQ_PKG = 0x0A4B
        private const val QUEUE_LIMIT = 100
        /** 缺素材那一行最多列几条（多了就把整行变成一面墙，后面的用「还有 N 条」交代） */
        private const val WARN_SHOWN = 5
        const val MODE_LIST = 0
        const val MODE_STUDY = 1

        const val ANKIWEB = "https://ankiweb.net/shared/decks"
        const val AWESOME = "https://github.com/tianshanghong/awesome-anki"

        /** 评分四档的顺序与中文标签（Again/Hard/Good/Easy，与 [Fsrs.Rating] 一一对应） */
        private val RATINGS = listOf(
            Fsrs.Rating.Again to "重来",
            Fsrs.Rating.Hard to "困难",
            Fsrs.Rating.Good to "记得",
            Fsrs.Rating.Easy to "简单",
        )

        /** 牌组层级名 "A::B::C" → "A › B › C"（斜杠式写法在各家 Anki 里都能见到） */
        fun prettyDeck(name: String): String = name.replace("::", " › ").ifBlank { "（未命名牌组）" }

        /** 下次复习的间隔说法：60 秒内说「1 分」，一天内说小时，再长说天 */
        fun etaText(dueMs: Long, now: Long): String {
            val d = dueMs - now
            return when {
                d <= 0L -> "现在"
                d < 60_000L -> "<1 分"
                d < 3_600_000L -> "${d / 60_000L} 分"
                d < 86_400_000L -> "${d / 3_600_000L} 小时"
                else -> "${d / 86_400_000L} 天"
            }
        }

        fun intent(ctx: Context): Intent = Intent(ctx, AnkiActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * 会话：页面重建（旋转 / 从后台回来）时**唯一**的状态来源。
         *
         * 只攥 application context（[AnkiDeckStore] 用它是为了不泄漏 Activity），
         * 页面通过 [host] 弱引用回来认领 —— 旋转后旧 Activity 被回收，新 Activity 重新 attach，
         * 队列下标 / 已评数量 / 是否翻面都还在，于是**不会重复评分、也不会重复导入**。
         */
        @Volatile
        private var session: Session? = null

        private class Session(app: Context) {
            val store = AnkiDeckStore(app)

            @Volatile var host: WeakReference<AnkiActivity>? = null

            /** 每次换集合 / 换牌组 / 换一轮都自增；迟到的旧线程认号不认对象 */
            @Volatile var gen = 0

            /**
             * 第几次评分尝试（只在主线程读写）。同一张卡、同一个档位失败重试时，
             * gen / cardId / rating 三者完全一样，只有它能分辨「这一枪是不是最新那一枪」。
             */
            var attempt = 0

            @Volatile var busy = false
            /** 集合清单是不是已经读过一轮（读成功才算；失败下次进来重试，不能把错误钉成常态） */
            @Volatile var loaded = false
            @Volatile var error: String? = null
            @Volatile var collections: List<String> = emptyList()
            @Volatile var decks: List<AnkiData.Deck> = emptyList()
            @Volatile var collection: String? = null

            @Volatile var deckId: Long? = null
            @Volatile var deckLabel: String = "全部牌组"

            @Volatile var queue: List<AnkiData.StudyCard> = emptyList()
            @Volatile var index = 0
            @Volatile var revealed = false
            @Volatile var rated = 0
            @Volatile var exhausted = false
            @Volatile var pending: Fsrs.Rating? = null
            @Volatile var mode = MODE_LIST

            @Volatile var importing = false
            @Volatile var importMsg: String? = null

            fun attach(a: AnkiActivity) { host = WeakReference(a) }
            fun detach(a: AnkiActivity) { if (host?.get() === a) host = null }

            fun notifyHost() {
                val a = host?.get() ?: return
                a.runOnUiThread { if (host?.get() === a) a.draw() }
            }
        }
    }
}
