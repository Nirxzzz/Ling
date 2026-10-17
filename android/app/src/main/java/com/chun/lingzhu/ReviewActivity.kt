package com.chun.lingzhu

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * K3 知识卡复习：全屏复习页 + 每日期满提醒。
 *
 * ## 为什么要单独一页
 * K2 的知识卡是「存进去」，但存进去就沉在 knowledge.json 里没人再看 —— 这是所有间隔重复工具的同一毛病。
 * 这一页是那个「捞回来」的口子：一次只处理一张卡，正面 → 显示答案 → 四档评分，评完自动下一张，
 * 队列清空就显示「今日复习完毕」。**不追求一次复习很多张**，追的是「今天到期的那几张今天过完」。
 *
 * ## 评分即调度
 * 四个按钮直接对应 [Fsrs.Rating] 的 Again / Hard / Good / Easy（显示成 1/2/3/4，和 FSRS 源码一致），
 * 写库走 [KnowledgeCard.Store.answer] → [Fsrs.review]。这一页**不重算间隔**，
 * 间隔是 FSRS 的事 —— 页面只负责把「记得怎么样」这个信号如实递进去。
 *
 * ## v1.85 两段结构：先单词、后知识卡
 * 词卡上那三档评分（滑出 / 点按钮）现在也写真正的间隔重复（[WordSrs]），到期的词就也出现在这一页：
 * 进来先过完「到期的单词」——正面单词、背面释义，三档 不认识 / 模糊 / 认识 写 [WordSrs.grade]；
 * 单词过完自动落回下面原来的四档知识卡流程，两段都清空才是「今日复习完毕」。
 * **没有到期单词时这一页和以前一模一样**（不排、不画任何新东西）。
 *
 * ## 分层
 * 纯函数（文案 / 到期数 / 时刻计算 / 按钮标签）全在 [ReviewNotify] 的 companion，单测直接测；
 * v1.85 的「现在该画哪一段」（[ReviewActivity.phaseOf]）和单词那半页的文案在 [ReviewActivity] 的 companion 里，
 * 同样是纯函数；Android 那层只有 [ReviewActivity]（画界面）和 [ReviewReceiver]（排闹钟、发通知）。
 *
 * 通知一天最多一条（默认 20:00，[ReviewNotify.DEFAULT_MIN]）：有到期的（知识卡 + 到期单词）才发，没有就一条都不发。
 * v1.85 起到期数把「到期单词」也一起算上（见 [ReviewReceiver]），通知上的数字和页里能看到的对得上。
 * 「没什么可说就不发」这条跟 [Briefing] 一致 —— 每天收到一句「今天没有卡到期」只会把人教会无视通知。
 */
class ReviewActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var prefs: Prefs
    private val store by lazy { KnowledgeCard.Store(this) }

    /** 进页面那一刻的到期快照。评分只是往后推 due，不会让这张卡再回到今天的队列里，所以快照不会失效。 */
    private var queue: List<KnowledgeCard.KCData> = emptyList()
    private var idx = 0
    /**
     * v1.85 到期单词的快照 + 走到第几个。[loadWords] 只在进页面时排一次，评完不重排（理由见 [answerWord]）。
     * 空 = 今天没有到期单词，这一页就是原来的样子（不出现空的那一段）。
     */
    private var wordQueue: List<WordItem> = emptyList()
    private var wIdx = 0
    private var flipped = false
    private var studyNew = false
    private lateinit var plan: StudyPlan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        plan = prefs.studyPlan
        studyNew = intent.getBooleanExtra("studyNew", false)
        Ui.theme = prefs.theme()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(root) }

        // v1.85.13（UX-7/U2）：内容加横向边距 + 上呼吸空间（UX 走查 UX-7）
        root.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@ReviewActivity, 16f), Ui.dp(this@ReviewActivity, 4f), Ui.dp(this@ReviewActivity, 16f), Ui.dp(this@ReviewActivity, 24f))
        }
        setContentView(scroll)
        Brut.page(this, scroll)
        // 从通知点进来也算「用户主动来复习了」：把下一次提醒重排到明天同一时刻
        ReviewNotify.schedule(this, prefs.reviewNotiOn, prefs.reviewEffectiveMin)
        load()
        loadWords()   // v1.85：到期单词排在前面先过（没有就走原来的知识卡队列）
        render()
    }

    /** 排一次到期队列（今天该过的卡，按到期排前面） */
    private fun load() {
        val now = System.currentTimeMillis()
        val limit = if (studyNew) plan.newLimit else plan.reviewLimit
        queue = KnowledgeCard.dueFirst(store.list(), now).filter {
            plan.acceptsCard(it.deck) && if (studyNew) it.fsrs.isNew else !it.fsrs.isNew && it.isDue(now)
        }.take(limit)
        idx = 0
        flipped = false
    }

    /**
     * v1.85：排一次「到期单词」队列（只在进页面时排一次）。
     *
     * 口径和词卡那边完全一致（见 [WordSrs.pickDue] 的说明）：**只认 [WordSrs] 里已经有记录、且被 FSRS 判到期的词**
     * —— 不能按 [WordSrs.isDue] 那种「没记录也当到期」来算，否则红宝书几千个新词在 FSRS 眼里全是 New，
     * 这一页会变成每天几千张新词。
     */
    private fun loadWords() {
        val now = System.currentTimeMillis()
        val srs = WordSrs.all(prefs)
        val remaining = ((if (studyNew) plan.newLimit else plan.reviewLimit) - queue.size).coerceAtLeast(0)
        val decks = prefs.deckList().filter { plan.acceptsWord(it.id) }
        wordQueue = if (studyNew) {
            decks.asSequence().flatMap { d -> Words.parseMemoized(d.text).asSequence().map { d to it } }
                .filter { (d, card) -> Words.key(d.id, card) !in srs }
                .take(remaining).map { (d, card) -> WordItem(Words.key(d.id, card), d.id, d.name, card.front, d.text) }.toList()
        } else {
            WordSrs.dueKeys(srs, now).asSequence().filter { plan.acceptsWord(it.substringBefore('\u0000')) }
                .sortedBy { srs[it]?.due ?: 0L }.take(remaining).map { key ->
                    val deck = key.substringBefore('\u0000')
                    val d = decks.firstOrNull { it.id == deck }
                    WordItem(key, deck, d?.name ?: deck, key.substringAfter('\u0000'), d?.text ?: "")
                }.toList()
        }
        wIdx = 0
        flipped = false
    }

    /**
     * v1.85：这一页分两段 —— 先把到期的单词过完，再落到原来的知识卡队列。
     * 「该画哪一段」交给纯函数 [phaseOf]（单测直接测），这里只照它画；两段都空才是「今日复习完毕」。
     */
    private fun render() {
        root.removeAllViews()
        val word = wordQueue.getOrNull(wIdx)
        when {
            word != null -> renderWord(word)
            idx < queue.size -> renderCard()
            else -> renderDone()
        }
        Brut.style(root)
        // v1.85：换卡 / 换段都回到顶部 —— 红宝书那种长背面一路滚到底，下一张会从半截开始（正面都看不见）
        scroll.scrollTo(0, 0)
    }

    /** 两段都空：今天没有到期的东西（文案与页面都和 v1.85 以前一致） */
    private fun renderDone() {
        root.addView(TextView(this).apply {
            text = if (studyNew) "本轮学习结束" else "本轮复习结束"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "已完成单词 $wIdx 个、知识卡 $idx 张。\n本轮只处理所选范围与数量；未学、未到期或超出限额的内容留到下一轮。"; textSize = 13.5f; setLineSpacing(0f, 1.35f)
            setPadding(0, Ui.dp(this@ReviewActivity, 8f), 0, 0)
        })
        root.addView(Button(this).apply { text = "返回"; tag = Brut.PRIMARY; setOnClickListener { finish() } })
    }

    /** 知识卡那一段：原来的四档流程，一字未动 */
    private fun renderCard() {
        val cur = queue[idx]
        // ---- 顶部：到期张数 + 进度 ----
        root.addView(TextView(this).apply {
            text = if (studyNew) "知识卡学习" else ReviewNotify.header(queue.size); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = ReviewNotify.progress(idx + 1, queue.size) + " · 牌组「${cur.deck}」"
            textSize = 12.5f; alpha = 0.75f; setPadding(0, Ui.dp(this@ReviewActivity, 4f), 0, Ui.dp(this@ReviewActivity, 10f))
        })

        // ---- 气泡式卡片：正面大字居中，翻面后追加答案 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ReviewActivity, 20f), Ui.dp(this@ReviewActivity, 26f),
                Ui.dp(this@ReviewActivity, 24f), Ui.dp(this@ReviewActivity, 30f))
        }
        card.addView(TextView(this).apply {
            text = cur.front; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f)
        })
        if (flipped) {
            card.addView(divider())
            card.addView(TextView(this).apply {
                text = cur.back; textSize = 15.5f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
            })
        }
        Brut.card(card)
        root.addView(card)

        if (!flipped) {
            root.addView(Button(this).apply {
                text = "显示答案"; tag = Brut.PRIMARY
                setOnClickListener { flipped = true; render() }
            })
        } else {
            root.addView(askLine())
            // 四档：2×2，手指按得中；括号里的 1/2/3/4 就是 FSRS 的 Rating 编码
            for (pair in ReviewNotify.RATINGS.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (r in pair) row.addView(ratingButton(r), LinearLayout.LayoutParams(0, -2, 1f))
                // 一行只放一个时（理论上不会发生）也得撑满，别让按钮缩成一小条
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                root.addView(row)
            }
        }
        root.addView(closeButton())
    }

    /**
     * v1.85 单词那一段：正面是单词、背面是释义 / 译文，流程和知识卡那一段一样
     * （显示答案 → 评分 → 下一张 → 走完自动接到知识卡那半页），只是档位换成词卡上的三档。
     * 抬头写「单词复习 · 到期 N 个」+「单词 3 / 12 · 牌组「…」」，一眼能看出现在过的是哪一半。
     */
    private fun renderWord(cur: WordItem) {
        val shown = shownWord(cur)
        root.addView(TextView(this).apply {
            text = if (studyNew) "单词学习" else wordHeader(wordQueue.size); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = wordProgress(wIdx + 1, wordQueue.size) +
                (if (cur.deckName.isEmpty()) "" else " · 牌组「${cur.deckName}」")
            textSize = 12.5f; alpha = 0.75f; setPadding(0, Ui.dp(this@ReviewActivity, 4f), 0, Ui.dp(this@ReviewActivity, 10f))
        })

        // ---- 气泡式卡片：和知识卡那一段同一套版式，正面单词居中，翻面后追加释义 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ReviewActivity, 20f), Ui.dp(this@ReviewActivity, 26f),
                Ui.dp(this@ReviewActivity, 24f), Ui.dp(this@ReviewActivity, 30f))
        }
        card.addView(TextView(this).apply {
            text = shown.front; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f)
        })
        if (flipped) {
            card.addView(divider())
            card.addView(TextView(this).apply {
                text = shown.back.ifBlank { NO_BACK_HINT }
                textSize = 15.5f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
            })
        }
        Brut.card(card)
        root.addView(card)

        if (!flipped) {
            root.addView(Button(this).apply {
                text = "显示答案"; tag = Brut.PRIMARY
                setOnClickListener { flipped = true; render() }
            })
        } else {
            root.addView(askLine())
            // 三档：2 + 1（沿用上面那套两列栅格，手指按得中）
            for (pair in WORD_GRADES.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (g in pair) row.addView(wordButton(g), LinearLayout.LayoutParams(0, -2, 1f))
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                root.addView(row)
            }
        }
        root.addView(closeButton())
    }

    /** 四档按钮。Good 是主按钮（最常按的那个），其余白底。 */
    private fun ratingButton(r: Fsrs.Rating): Button = Button(this).apply {
        text = ReviewNotify.ratingLabel(r)
        if (r == Fsrs.Rating.Good) tag = Brut.PRIMARY
        setOnClickListener { answer(r) }
    }

    /** v1.85 单词的三档按钮，文案就是 [SwipeJudge.Grade.label]（不认识 / 模糊 / 认识）。「认识」是主按钮，和知识卡那边 Good 同款。 */
    private fun wordButton(g: SwipeJudge.Grade): Button = Button(this).apply {
        text = g.label
        if (g == SwipeJudge.Grade.KNOWN) tag = Brut.PRIMARY
        setOnClickListener { answerWord(g) }
    }

    /** 评分 → 写库（FSRS 排下次的 due）→ 下一张；队列走完就落到「今日复习完毕」 */
    private fun answer(r: Fsrs.Rating) {
        val cur = queue.getOrNull(idx) ?: return
        store.answer(cur.id, r, System.currentTimeMillis())
        flipped = false
        idx++
        render()
    }

    /**
     * v1.85 单词评分 → 写 [WordSrs]（FSRS 排下次的 due，落它自己的 prefs 键）→ 下一张；
     * 单词走完 [phaseOf] 就把画面交给知识卡那一段，不用在这里跳转。
     *
     * 队列只在进页面时排一次、**评完不重排**：「不认识」（= FSRS 的 Again）在 1 分钟后就又会到期，
     * 边过边重排会让同一个词反复回来，把「今天到期的过一遍」变成走不完的循环。
     */
    private fun answerWord(g: SwipeJudge.Grade) {
        val cur = wordQueue.getOrNull(wIdx) ?: return
        WordSrs.grade(prefs, cur.key, g, System.currentTimeMillis())
        flipped = false
        wIdx++
        render()
    }

    // ---- 两段共用的几个小块（内容与 v1.85 以前完全一致，只是提出来给单词那半页复用） ----

    /** 翻面后的分隔线 */
    private fun divider() = TextView(this).apply {
        text = "──────────"; textSize = 13f; alpha = 0.4f
        gravity = Gravity.CENTER; setPadding(0, Ui.dp(this@ReviewActivity, 16f), 0, Ui.dp(this@ReviewActivity, 16f))
    }

    /** 「刚才记得怎么样？」那一行 */
    private fun askLine() = TextView(this).apply {
        text = "刚才记得怎么样？（这一档决定下次什么时候再来）"
        textSize = 12.5f; alpha = 0.75f; setPadding(0, Ui.dp(this@ReviewActivity, 14f), 0, Ui.dp(this@ReviewActivity, 4f))
    }

    /** 「关掉」这一页 */
    private fun closeButton() = Button(this).apply { text = "关掉"; setOnClickListener { finish() } }

    /**
     * v1.85 这条到期单词该显示什么（正面 / 背面），走的是和词卡同一条路：
     * 先按正面在词库里找原卡（[Words.parseMemoized]，有进程内缓存），背面非空就用 [Words.detail] 排版
     * （红宝书那种书页背面原样保留、不截断）；只有单词没有背面（内置 5 级那种）才补一份预翻译译文。
     * 词库被删 / 这个正面找不到时退回「只有单词」——照样能评分，评完就排下次。
     *
     * 到 render 时才现算：预翻译存在一份 JSON 里，每查一条都要整份解析一遍，提前给整队算好会白解析 N 次。
     */
    private fun shownWord(cur: WordItem): Words.Card {
        val card = Words.parseMemoized(cur.deckText).firstOrNull { it.front == cur.front }
            ?: Words.Card(cur.front, "")
        if (card.back.isNotBlank()) return Words.detail(card, null)
        return Words.detail(card, WordPretranslateStore(prefs).lookup(cur.deck, cur.front, System.currentTimeMillis()))
    }

    /**
     * v1.85 到期单词队列里的一项。
     * [key] = [Words.key]（`词库id\u0000单词`）—— 评分要原样写回 [WordSrs]；
     * [deckText] 是词库原文（[WordBank] 缓存里的同一个 String 引用），渲染时用它按正面找回原卡。
     */
    private class WordItem(
        val key: String,
        val deck: String,
        val deckName: String,
        val front: String,
        val deckText: String,
    )


    companion object {
        /** 通知 / 菜单进这一页 */
        fun intent(ctx: Context) = Intent(ctx, ReviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)


        /** 单词那半页的抬头（对齐 [ReviewNotify.header] 的写法） */
        const val WORD_HEADER = "单词复习"

        /** 「单词复习 · 到期 N 个」 */
        fun wordHeader(due: Int): String = if (due > 0) "$WORD_HEADER · 到期 $due 个" else WORD_HEADER

        /** 进度：「单词 3 / 12」（对齐 [ReviewNotify.progress] 的写法） */
        fun wordProgress(i: Int, total: Int): String = "单词 $i / $total"

        /** 单词那半页的三个按钮，顺序 = 从左到右：不认识 / 模糊 / 认识 —— 就是词卡上滑出的那三档 */
        val WORD_GRADES: List<SwipeJudge.Grade> =
            listOf(SwipeJudge.Grade.UNKNOWN, SwipeJudge.Grade.FUZZY, SwipeJudge.Grade.KNOWN)

        /** 背面取不到时的占位（词库删了 / 词库没勾选又没预翻译） */
        const val NO_BACK_HINT = "（没有释义 —— 这个词库可能已取消勾选）"
    }
}

/**
 * K3 到期提醒（对齐 [Briefing] 的做法）：每天一次 [AlarmManager]，响时看一眼有没有到期卡，
 * 有就发一条「复习 · N 项到期」（单词 + 知识卡一起算，v1.85），点通知直接进 [ReviewActivity]。
 *
 * 一天只发一条，且**没有到期卡就一条都不发**。
 * 文案 / 到期数 / 时刻计算都在 companion 里（纯函数，单测直接测）；
 * [schedule] / [post] 是Android 那层。
 */
object ReviewNotify {
    const val CHANNEL = "review"
    const val CHANNEL_NAME = "复习提醒"
    const val NOTI_ID = 68
    /** 默认 20:00 —— 晚饭后、睡前正好清一遍今天的卡 */
    const val DEFAULT_MIN = 20 * 60
    const val ACTION_CHECK = "com.chun.lingzhu.REVIEW_CHECK"

    /** 独立 REQ：和其它模块（简报 2401/2402、随手记）不撞，避免 PendingIntent 互相覆盖 */
    private const val REQ_ALARM = 2403
    const val REQ_OPEN = 68

    const val HEADER_EMPTY = "复习"

    /** 界面上那四个按钮，顺序 = FSRS 的 Again/Hard/Good/Easy（= 1/2/3/4） */
    val RATINGS: List<Fsrs.Rating> = listOf(Fsrs.Rating.Again, Fsrs.Rating.Hard, Fsrs.Rating.Good, Fsrs.Rating.Easy)

    // ---------------- 纯函数 ----------------

    /** 页头：「复习 · 到期 N 项」（单词 + 知识卡一起算，见 v1.85）*/
    fun header(due: Int): String = if (due > 0) "$HEADER_EMPTY · 到期 $due 项" else HEADER_EMPTY

    /** 页头进度：「第 3 / 8 张」 */
    fun progress(i: Int, total: Int): String = "第 $i / $total 项"

    /** 按钮文案：「忘记(1)」这种 —— 括号里是 FSRS 的 Rating 编码，方便对照源码 */
    fun ratingLabel(r: Fsrs.Rating): String = "${ratingName(r)}(${r.code})"

    fun ratingName(r: Fsrs.Rating): String = when (r) {
        Fsrs.Rating.Again -> "忘记"
        Fsrs.Rating.Hard -> "困难"
        Fsrs.Rating.Good -> "良好"
        Fsrs.Rating.Easy -> "简单"
    }

    /** 通知标题：「复习 · N 项到期」（项 = 单词 + 知识卡，v1.85 起两样一起算） */
    fun title(due: Int): String = "$HEADER_EMPTY · $due 项到期"

    /** 通知正文 */
    fun body(due: Int): String = if (due <= 0) "" else "$due 项等着过一遍 · 答完自动排下次的间隔"

    /** 今天的到期数（[KnowledgeCard.KCData] 列表 → 张数） */
    fun dueCount(cards: List<KnowledgeCard.KCData>, now: Long): Int = cards.count { it.isDue(now) }

    /** 该不该发这条通知：开关开着且确实有到期卡。**没到期卡一律不发。** */
    fun shouldNotify(on: Boolean, due: Int): Boolean = on && due > 0

    /** 离下一个 [targetMin]（当天第几分钟）还有多少分钟（刚好等于按「今天已经过了、等明天」算） */
    fun delayTo(nowMin: Int, targetMin: Int): Int = Briefing.delayTo(nowMin, targetMin)

    fun normMin(v: Int): Int = PopupPolicy.normMin(v)

    /** 「20:00」 */
    fun fmtMin(minute: Int): String = PopupPolicy.fmtMin(minute)

    /** 下一次该检查的时刻（毫秒）：今天该点还没到就今天，已过点就明天 */
    fun nextCheckAt(now: Long, nowMin: Int, targetMin: Int): Long =
        now + delayTo(nowMin, targetMin) * 60_000L

    // ---------------- Android 那层 ----------------

    /**
     * 排下一次检查。开关关了就把已有闹钟撤掉（不是排了不响，是真的cancel）。
     * [AlarmManager.set] 是一次性的，所以响完由 [ReviewReceiver] 自我续订 —— 和简报同一套做法。
     */
    fun schedule(ctx: Context, on: Boolean, targetMin: Int = DEFAULT_MIN, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (am == null) return
        val pi = alarmPi(ctx)
        try {
            if (!on) { am.cancel(pi); return }
            am.set(AlarmManager.RTC_WAKEUP, nextCheckAt(now, PopupPolicy.minuteOfDay(now), normMin(targetMin)), pi)
        } catch (_: Exception) {}
    }

    fun reschedule(ctx: Context) {
        val p = Prefs(ctx)
        // v1.73：排闹钟一律走 effective 时刻 —— 统一弹出时刻开着时要跟统一时刻走
        schedule(ctx, p.reviewNotiOn, p.reviewEffectiveMin)
    }

    /** 提醒开关 / 时刻改完、或刚复习完，调一下把下一次对齐 */
    fun kick(ctx: Context) = reschedule(ctx)

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM, Intent(ctx, ReviewReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun post(app: Context, due: Int) {
        if (due <= 0) { cancel(app); return }
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
            val text = body(due)
            nm.notify(NOTI_ID, Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_orb)
                .setContentTitle(title(due))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(app, REQ_OPEN, ReviewActivity.intent(app),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }

    fun cancel(app: Context) {
        try { app.getSystemService(NotificationManager::class.java)?.cancel(NOTI_ID) } catch (_: Exception) {}
    }
}

/** 闹钟响 → 看有没有到期卡 → 有就发一条；同时把自己续上（[AlarmManager.set] 是一次性的） */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val now = System.currentTimeMillis()
        if (p.reviewNotiOn) {
            // v1.85：到期数把「到期单词」（WordSrs）也算进去 —— 复习页现在先过单词再过知识卡，徽标得和页里能看到的对上。
            // 口径同 ReviewActivity.loadWords：只算 WordSrs 里已有记录、被 FSRS 判到期的词（新词不算，否则几千个新词天天「到期」）。
            val due = KnowledgeCard.Store(app).dueCards(now).size + WordSrs.dueCount(p, now)
            if (ReviewNotify.shouldNotify(true, due)) ReviewNotify.post(app, due) else ReviewNotify.cancel(app)
        } else ReviewNotify.cancel(app)
        // 续订：开关已经关掉的由 schedule() 里cancel，不排下一次
        ReviewNotify.schedule(app, p.reviewNotiOn, p.reviewEffectiveMin, now)
    }

    companion object {
        /** 开关改完 / 复习完 / 服务起来后，把下一次对齐 */
        fun kick(ctx: Context) = ReviewNotify.reschedule(ctx)
    }
}