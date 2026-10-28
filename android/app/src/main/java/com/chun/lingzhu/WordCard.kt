package com.chun.lingzhu

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 折叠行提示文案（v1.83）：真题块默认收起，点一下展开；展开后再点收起 */
private const val HINT_EXPAND = "点一下展开"
private const val HINT_COLLAPSE = "收起"

/**
 * 随机弹词卡（v1.64）：单词、释义（默认展开，点单词收/展）、认识/不认识；长按卡片关闭。
 * v1.78：「查」改成**原地展开**——先读预翻译缓存，命中就即时排版上屏；没命中就在卡片里先写「预翻译中…」，
 * 后台补翻这一个词，回来了就地填上。不再启动 TranslateActivity（那是一张固定贴在屏幕底部的翻译卡，
 * 点一下卡片内容会从屏幕上方跳到下方，这就是「漂移」的手感）。
 *
 * v1.78 还加了**拖拽甩出**：按住上下拖，卡片跟手并淡出；松手按「投影终点」决定甩出去（朝手指方向飞走）
 * 还是弹回来（带一点过冲）。判定放在 [Anim.dragDismiss]，纯函数、单测覆盖。
 *
 * v1.85：拖拽从「任意方向都划掉」改成**滑出判词**——左=不认识 / 下=模糊 / 右=认识 / 上=跳过、不评分。
 * 两段临界各震一下（[Ui.popTick]），过临界后位移按 [SwipeJudge.damp] 打折（手感变沉），
 * 卡片顶部多一行提示（[SwipeJudge.hint]）。临界/阻尼/判定全在 [SwipeJudge]，这里只管喂位移、画反馈。
 *
 * 词卡修复：上滑（[SwipeJudge.Grade.CLOSE]）也走 [onJudge]，但只是「跳过」——由上层收到 CLOSE 时**不落评分**，
 * 于是既不当认识、也不消耗复习，之后还能再遇到。另外退场改成「窗口真正 remove 前 showing 一直为 true」，
 * 且一张卡最多提交一次（[judgeOnce]）。
 */
class WordCard(
    private val ctx: Context,
    private val wm: WindowManager,
    /** v1.85：滑出 / 按钮的评分回调（[SwipeJudge.Grade] = 不认识 / 模糊 / 认识 / 跳过） */
    private val onJudge: (deck: String, card: Words.Card, grade: SwipeJudge.Grade) -> Unit,
    /** 预翻译缓存直读（同步、不联网）：命中返回译文，未命中 null */
    private val cachedDetail: (deck: String, word: String) -> String? = { _, _ -> null },
    /** 没命中时后台补翻一个词，done 在主线程回调译文（翻不到 / 离线 / 冷却期给 null） */
    private val fetchDetail: (deck: String, word: String, done: (String?) -> Unit) -> Unit = { _, _, d -> d(null) },
    /** 读出来（英文）；返回 false = 这台手机没有可用的朗读引擎 */
    private val onSpeak: (String) -> Boolean = { false },
    /**
     * 这张卡「没人碰」时停留多少毫秒就自动收起（v1.82：可调，默认 20 秒）。
     * 传函数而不是常量：WordCard 不自己去读 [Prefs]，由调用方把这次该停多久算好传进来。
     */
    private val stayMs: () -> Long = { 20_000L },
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    /**
     * 当前这张卡的弹簧动画器（v1.78）。持有实例才有「可打断」：
     * 入场还在飞的时候点了「不认识」/ 长按关闭，[hide] 走的是同一个实例 ——
     * 位置和速度原样接管，卡片是「就地拐个弯出去」，而不是「先停住、再从头播一遍出场动画」。
     */
    private var motion: AnimGlue.SpringAnimation? = null
    private val autoHide = Runnable { hide() }

    /**
     * 词卡修复：手指是否还按在卡上。按住期间**不排**自动收起 —— 长卡 / 滚动读的时候不能被收走，
     * 抬手 / 取消后由 UP/CANCEL 恢复倒计时。停留秒数和超时机制都没变，只是「按住那几秒」不计入。
     */
    private var touchHeld = false

    /** 富卡背面「错峰淡入」里还没轮到的延迟任务 —— 卡片被收走 / 换下一张时要撤掉 */
    private val staggerJobs = ArrayList<Runnable>()

    /** 富卡背面的块（按顺序）。v1.81：背面前改成「两步揭示」，这些块的错峰淡入改在**点开时**才播 */
    private val staggers = ArrayList<AnimGlue.SpringAnimation>()

    /** 收起态的入场姿态：全透明 + 下方 6dp（scale 保持 1，别让文字缩放） */
    private fun staggerStart() = Anim.Visual.entering(0, 1, dp(KaoyanBack.STAGGER_SHIFT_DP).toFloat(), scale = 1f)

    private fun cancelStagger() {
        staggerJobs.forEach { main.removeCallbacks(it) }
        staggerJobs.clear()
    }

    /** 点开背面：把还没冒出来的块按节奏依次弹上来 */
    private fun startStagger() {
        cancelStagger()
        for ((i, m) in staggers.withIndex()) {
            val job = Runnable { m.to(Anim.Visual.SHOW, Anim.Spring.popIn) }
            staggerJobs += job
            main.postDelayed(job, KaoyanBack.staggerDelayMs(i))
        }
    }

    /** 收起背面：复位到入场姿态（下次点开重新播一遍错峰，而不是直接"啪"地出现） */
    private fun resetStagger() {
        cancelStagger()
        staggers.forEach { it.settleAt(staggerStart()) }
    }

    // ---- v1.81 两步揭示：默认只露单词，点一下才展开背面 ----
    /** 背面视图（点开/收起就是改它的 visibility） */
    private var backView: View? = null
    /** 收起态那句提示（点开后背面的内容顶上来，提示让位） */
    private var hintView: View? = null
    /** 当前卡是哪一本、哪一条 —— 背面为空时要按这个去要一份解读 */
    private var revealDeck: String? = null
    private var revealCard: Words.Card? = null
    private var revealHasBack = false

    /** 点一下：展开 / 收起背面。有词书背面直接展开（富卡顺便播一遍块错峰）；空背面走去要一份解读 */
    private fun toggleReveal() {
        val b = backView ?: return
        if (b.visibility == View.VISIBLE) { setBackShown(false); return }
        val deck = revealDeck
        val card = revealCard
        if (revealHasBack) setBackShown(true)
        else if (deck != null && card != null && b is TextView) reveal(b, deck, card)
    }

    private fun setBackShown(shown: Boolean) {
        val b = backView ?: return
        b.visibility = if (shown) View.VISIBLE else View.GONE
        hintView?.visibility = if (shown) View.GONE else View.VISIBLE
        if (shown) startStagger() else resetStagger()
        bump()
    }

    // 拖拽甩出（v1.78 竖向；v1.81 四个方向）：位移、是否已经接管、以及平台标准的速度跟踪器
    private var dragDx = 0f
    private var dragDy = 0f
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private val slop by lazy { android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat() }

    /**
     * 手指速度采样。用**屏幕坐标** rawX/rawY 自己算，不用平台 VelocityTracker ——
     * 它按视图内坐标算，而窗口是跟着手指一起挪的，手指相对窗口几乎不动，算出来恒为 0。
     * 见 [Anim.DragSamples] 的注释（v1.78 实测踩过这个坑）。横向现在也要判方向，所以两个轴各一个采样器。
     */
    private val samples = Anim.DragSamples()
    private val samplesX = Anim.DragSamples()

    /**
     * 这张卡的窗口参数与它的静止位置 —— 拖动时改的是**窗口 x/y**，不是视图位移。
     *
     * 词卡修复：参数不再放共享字段。每个窗口的 [WindowShift] 在创建时**捕获自己那一份** params，
     * 退场动画因此读不到「下一张卡」的 params（旧窗口动画写到新窗口上就是错位 / 闪跳的根源）。
     */
    private var baseY = 0

    /** 当前窗口的落点适配器（改 params + 统一 commit）。退场期间还指着这一张，用来设 NOT_TOUCHABLE */
    private var winShift: WindowShift? = null

    /**
     * 正在退场（`motion.to(exit) { remove }` 已发起，窗口还没 remove）。此时 `showing` 仍为 true ——
     * 对外就代表「这张卡还占着屏幕」，上层据此不会在退场中途又弹下一张（旧的 `view=null` 会漏掉这段窗口）。
     */
    private var closing = false

    // ---- v1.85 滑出判词（纯逻辑见 [SwipeJudge]）：临界 / 阻尼 / 两段反馈 ----

    /** 卡片**最顶部**那一行判词提示（词卡修复后是**常驻小提示槽**：INVISIBLE 占位、不 GONE，改卡高只在 alpha） */
    private var judgeHint: TextView? = null

    /** 提示当前的**目标** alpha。`animate()` 是异步的，不能拿 view.alpha 当「已设值」去比，否则每帧都重启动画 */
    private var hintAlpha = 0f

    /** 玻璃提示行当前材质的填色：只有变色才换材质（拖动中逐事件回调，不能每次都新建 drawable） */
    private var lastHintFill = 0

    /**
     * 上一帧命中的阶段。**只增不减**：这样才能保证「过一个临界只震一下」——
     * 在临界点上来回抖动时 stage 会 0/1/0/1 跳，若按差值判就会反复震。
     */
    private var lastStage = 0

    /** 这次手势的两段临界。在**开始拖的那一刻**按卡片尺寸算好 —— 手势中途卡高不变（提示槽常驻），阈值也不该抖 */
    private var dragThX = SwipeJudge.Thresholds(0f, 0f)
    private var dragThY = SwipeJudge.Thresholds(0f, 0f)

    /** 当前这张卡是谁：松手提交评分时要拿（跟手阶段拿不到 deck/card 参数） */
    private var judgeDeck: String? = null
    private var judgeCard: Words.Card? = null

    /** 这张卡**是否已经提交过评分**（含 CLOSE）。按钮和手势都经 [judgeOnce]，保证每卡最多提交一次 */
    private var judged = false

    /** 有窗口（含正在退场的窗口）就算「占着屏」——退场动画没跑完、窗口没 remove 之前不能放下一张 */
    val showing: Boolean get() = view != null
    private fun dp(v: Float) = Ui.dp(ctx, v)

    /**
     * 直接画在面板底上的正文 / 次要文字色（词卡对比度修复）。
     *
     * 卡片底是 [Ui.panelBg]，深浅随当前配色走；而 [Brut.INK] / [Brut.HINT] 是**给浅底准备的固定色** ——
     * 只要配色没有 palette（默认夜间的 night_mint、深色自定义、旧的 [Theme.DARK]），Brut 就退回成近黑
     * `0xFF141414` / 中灰 `0xFF8A817B`，压在近黑的深底上就成了「看不见」：正文 ≈ 1.1:1、次要 ≈ 4.4:1。
     * （六套带 palette 的深色变体不受此影响：那里 Brut 按色板取到的是浅色墨，本来就是对的。）
     *
     * 所以只修「深底 + 无 palette」这一档：改用**主题正文色** [Ui.TX1]（不透明，night_mint ≈ 14:1）
     * 与 [Ui.TX2]（不透明度 80%，叠回深底 ≈ 10:1）—— 与 [Ui.panelBg] 同源、跟同一套配色走，仍是浅字压深底。
     * 浅底（日间护眼 / 六套浅色）与带 palette 的深色变体一律保持原样，逐位不变。
     */
    private val panelInk: Int get() = if (Ui.theme.isDark && Ui.theme.palette == null) Ui.TX1 else Brut.INK
    private val panelHint: Int get() = if (Ui.theme.isDark && Ui.theme.palette == null) Ui.TX2 else Brut.HINT

    /**
     * 直角语言（[Shape.cornerDp] <= 0）：判据与 [Ui.glass] / [Ui.smallPill] / WxSkin 的直角分支同一个，
     * 不只认瑞士配色 —— 用户显式设过形状（主题包导入）时也照他的来。
     */
    private val swiss get() = Ui.shape.cornerDp <= 0f

    /** 这套形状声明了**无投影**（[Shape.shadowDp] <= 0，与 [Brut] 的判据同源） */
    private val flat get() = Ui.shape.shadowDp <= 0f

    /** 当前配色是不是液态玻璃材质（v2.7.3）：只决定「谁来画底」，卡片手势 / 按钮行为都不变 */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 圆角档位：直角语言一律归零，其余形状保持调用点传入的原值 */
    private fun corner(base: Float): Float = if (swiss) 0f else base

    /** 描边粗细：直角语言取 [Shape.strokeDp]（瑞士 1dp 细线），其余形状保持原来的 1dp 硬描边 */
    private fun strokeW(): Float = if (swiss) Ui.shape.strokeDp else 1f

    /**
     * 卡片根布局（v1.78）：纵向拖动超过 slop 就把触摸从子视图手里**接管**过来 ——
     * 这样在「读 / 认识」这些按钮上起手也能拖卡片，而不是被按钮吃掉。
     * 没动就不接管：点按钮、点单词收展、长按关闭全都照旧。
     */
    private inner class CardRoot(context: Context) : LinearLayout(context) {
        /**
         * 富卡背面的滚动容器（普通卡没有 → null）。v1.81：背面能滚之后，拖动关闭得给竖向滚动让路 ——
         * 手指从滚动区里起手、且那个方向还有内容可滚时，不接管；等滚到顶/底，这里再把手势收回来当「拖一下关掉」。
         */
        var scrollChild: View? = null
        private var downInScroll = false
        private val loc = IntArray(2)

        /**
         * 词卡修复：所有触摸都从这里过 —— 挂在 root 的 OnTouchListener 收不到被背面 ScrollView 消费掉的那段
         * 手势（按住不放也会照旧 20s 收走），所以停留计时的「按住停表 / 抬手续时」放这里。
         * 用 dispatchTouchEvent 而不是 onInterceptTouchEvent：后者只在拖动超 slop 时才接管，纯滚动读长卡
         * 根本不经过它，会漏掉整段手势。DOWN 停表、UP/CANCEL 续时（滚动抛出的「因惯性滚动而刷新」由
         * [bump] 里的 [touchHeld] 判断），返回 super 结果，子视图的点按 / 滚动行为一点不动。
         */
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchHeld = true
                    main.removeCallbacks(autoHide)   // 手指按住：先停表（不排，等抬手再排）
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> touchHeld = false
            }
            val handled = super.dispatchTouchEvent(ev)
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> bump()   // 手指离开：恢复正常倒计时
            }
            return handled
        }

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragDx = 0f; dragDy = 0f; dragging = false
                    downInScroll = insideScroll(ev)
                    // 滚动区里起手时 DOWN 会被 ScrollView 吃掉、外面 onCardTouch 收不到，速度采样在这里先起头
                    if (scrollChild != null) {
                        samples.clear(); samples.add(ev.eventTime, ev.rawY)
                        samplesX.clear(); samplesX.add(ev.eventTime, ev.rawX)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        val sv = scrollChild
                        // 竖向优先让 ScrollView 滚（起手在滚动区里、且那个方向还有内容）；
                        // 横向 / 斜向里横向占优时 ScrollView 本来也滚不动 → 直接接管成拖动，四个方向都能划掉。
                        if (downInScroll && sv != null && Math.abs(dy) >= Math.abs(dx)) {
                            // 手指下滑 = 想看上面的内容 = ScrollView 往「上」滚（-1）；上滑反之
                            val canScroll = if (dy > 0) sv.canScrollVertically(-1) else sv.canScrollVertically(1)
                            if (canScroll) return false   // 让 ScrollView 自己滚；滚不动了下一帧就走下面的 return true 接管
                        }
                        return true
                    }
                }
            }
            return false
        }

        /** 这次 DOWN 是不是落在滚动容器里（窗口在 DOWN 时静止，屏幕坐标可信） */
        private fun insideScroll(ev: android.view.MotionEvent): Boolean {
            val v = scrollChild ?: return false
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return false
            v.getLocationOnScreen(loc)
            return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
        }
    }

    /**
     * 把动画器的「纵向位移」接到**窗口 y** 上（v1.78）。为什么不能直接写视图的 translationY：
     * 悬浮窗的 surface 只有窗口框那么大，视图变换超出窗口就**被裁掉**（入场只挪 12dp，正好被顶部
     * 那点 padding 吃掉，所以一直没人发现）。拖动要挪一整张卡的高度，必须挪窗口。
     * 好处是整套弹簧（含速度继承、精确收敛）照样用得上，只是每帧多一次 updateViewLayout。
     *
     * 词卡修复：params 由构造时**捕获**（不再读共享字段），并且 [commit] 才真正 `updateViewLayout` ——
     * 一批属性（tx + ty + flags）只推一次，且坐标 / 标志没变就跳过。旧实现是 tx / ty 两个 setter
     * 各推一次，一帧两次窗口布局，拖动和划出都会掉帧。
     */
    private inner class WindowShift(
        private val r: View,
        private val p: WindowManager.LayoutParams,
        private val base: Int,
    ) : Anim.VisualTarget {
        // 上一次**真正推给窗口**的值（含 flags）。坐标没变就不重复布局；flags 一起比是为了让
        // 「退场只改 FLAG_NOT_TOUCHABLE、坐标不变」这种更新也能落下去（否则会被坐标缓存吞掉）。
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        private var lastFlags = Int.MIN_VALUE

        override var alpha: Float
            get() = r.alpha
            set(v) { r.alpha = v }
        override var scaleX: Float
            get() = r.scaleX
            set(v) { r.scaleX = v }
        override var scaleY: Float
            get() = r.scaleY
            set(v) { r.scaleY = v }
        override var translationX: Float
            get() = p.x.toFloat()
            // 只更新参数，不在这里 updateViewLayout —— 等 [commit] 统一推一次
            set(v) { p.x = v.toInt() }
        override var translationY: Float
            get() = (p.y - base).toFloat()
            set(v) { p.y = base + v.toInt() }

        /** 一批属性写完统一提交：坐标 / flags 与上次相同就跳过。初始附着时缓存是 MIN_VALUE，第一次一定推 */
        override fun commit() {
            if (!r.isAttachedToWindow) return
            if (p.x == lastX && p.y == lastY && p.flags == lastFlags) return
            try {
                wm.updateViewLayout(r, p)
                lastX = p.x; lastY = p.y; lastFlags = p.flags
            } catch (_: Exception) {}
        }

        /**
         * 退场期间让窗口不可触摸（按钮 / 手势全失效）。置上标志后统一走 [commit] 推一次 ——
         * 提交失败（未附着 / updateViewLayout 抛异常）时**不写 last 缓存**，下一次 commit 会重推。
         */
        fun makeUntouchable() {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            commit()
        }
    }

    fun show(deckName: String, deck: String, card: Words.Card, stat: Words.Stat?, todayLine: String = "", autoSpeak: Boolean = false) {
        // 上一张还在屏上 / 正在退场：不覆盖它的状态（它的窗口和动画都还没交接完，硬塞会把
        // 退场中的窗口搅乱）。上层 OrbService 用 showing 守着同一件事，这里再兜一道。
        if (view != null || closing) return
        judged = false
        touchHeld = false
        val hasBack = card.back.isNotBlank()
        val root = CardRoot(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 玻璃（v2.7.3）：整张卡走共享材质（轻透明层 + 高光边 + 柔和阴影），卡片档圆角；
            // 其余配色仍是 [Ui.panelBg]（它自己认形状：直角语言下是 0dp + 墨色细线，永不叠渐变）。
            background = if (glass) Ui.material(ctx, Brut.CARD, Ui.SurfaceRole.CARD, 16f)
                         else Ui.panelBg(ctx, radiusDp = 16f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            setOnLongClickListener { hide(); true }
            setOnTouchListener { _, ev -> onCardTouch(ev) }
        }
        // 背面（v1.80 默认展开；v1.81 富卡改走结构化排版）：
        //  - 普通卡：一个 TextView、WordSense.cardBack（一字不改，行为与老版本完全一致）
        //  - 富卡（书页式词库背面）：标签 chip + 正文，塞进限高的 ScrollView
        val rich = KaoyanBack.isRich(card.back)
        val backTv = if (rich) null
        else Ui.text(ctx, WordSense.cardBack(card.back, card.example), 13f, Ui.TX2).apply { setLineSpacing(0f, 1.3f) }
        val back: View = backTv ?: richBack(card.back)
        // v1.81 两步揭示：默认只露单词，点一下才展开背面（原来是一弹卡背面就全铺出来）
        back.visibility = View.GONE
        backView = back
        revealDeck = deck
        revealCard = card
        revealHasBack = hasBack
        judgeDeck = deck
        judgeCard = card
        val hint = Ui.text(ctx, if (hasBack) "点一下看释义" else "点「查」看释义", 12f, panelHint)
        hintView = hint
        // v1.85 滑出判词提示行：卡片最顶部一行。词卡修复：改成**常驻小提示槽** ——
        // INVISIBLE（不是 GONE）占位 + maxLines = 1（钉死一行，文案再长也不换行），
        // 卡片高度从入场到退场恒定；拖进判词区只是 alpha 淡入。
        // 旧实现用 GONE↔VISIBLE，拖动时窗口 WRAP_CONTENT 会跟着改高 → 每帧一次 resize，这是掉帧的主因之一。
        // 文字与配色见 [updateHint]。
        val judge = Ui.text(ctx, "", 11.5f, Brut.INK).apply {
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            visibility = View.INVISIBLE
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = if (glass) Ui.material(ctx, Brut.CARD, Ui.SurfaceRole.BUTTON, 8f)
                         else android.graphics.drawable.GradientDrawable().apply {
                             // 瑞士：直角 + 1dp 细线（无圆角、无投影、无渐变）；其余风格保持 8dp 圆角原样
                             cornerRadius = dp(corner(8f)).toFloat()
                             setStroke(Math.max(1, dp(strokeW())), Brut.INK)
                             setColor(Brut.CARD)
                         }
            // 空文本与中文的字体回退高度不同，固定像素高度才能避免首次提示微幅 resize。
            height = kotlin.math.ceil((paint.fontMetrics.bottom - paint.fontMetrics.top).toDouble()).toInt() + paddingTop + paddingBottom
        }
        judgeHint = judge
        hintAlpha = 0f
        lastHintFill = 0
        lastStage = 0
        val word = Ui.text(ctx, card.front, 22f).apply {
            setTypeface(typeface, Typeface.BOLD); setTextIsSelectable(false)
            setOnClickListener { toggleReveal() }
        }
        root.addView(judge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6f) })
        root.addView(word)
        root.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        root.addView(back)
        // 点卡片空白处（不是按钮、不是背面里的滚动区）也能展开/收起
        root.setOnClickListener { toggleReveal() }
        if (rich) {
            root.scrollChild = back   // 拖动关闭要让路给背面的竖向滚动（见 CardRoot）
            // 词卡修复：背面内容滚动也算「有人碰」→ 刷新停留计时（手指离开后的惯性甩动同样触发）。
            // bump 内部在按住期间只停表、不重排（见 touchHeld），所以滚到一半不会被自动收起。
            back.setOnScrollChangeListener { _, _, _, _, _ -> bump() }
        }
        val row = Ui.row(ctx).apply { setPadding(0, dp(10f), 0, 0) }
        val lp = { LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) } }
        if (Words.speakable(card.front)) row.addView(small("读") { if (!onSpeak(card.front)) android.widget.Toast.makeText(ctx, "没有可用的朗读引擎", android.widget.Toast.LENGTH_SHORT).show(); bump() }, lp())
        // v1.80：「查」两种情况都给 —— 没有词书背面的，点了展开一份词典解读；
        // 有词书背面的，点了往上补音标 + 例句（不覆盖词书自己的释义）
        // v1.81：富卡不给「查」—— 书里已经给了音标和例句，再追加一份就是重复。
        if (!rich) row.addView(small("查") { reveal(backTv!!, deck, card) }, lp())
        // v1.78：不再用 weight = 1 撑满整行 —— 按钮一律按字排，宽度就是文字自己的宽度
        // v1.85：三颗 = 不认识 / 模糊 / 认识（顺序固定），走和滑出**同一条**评分回调。
        // 「不认识」保留原来的短停顿 bumpShort()，另两颗即时收起。文案直接取 [SwipeJudge.Grade.label]
        row.addView(small(SwipeJudge.Grade.UNKNOWN.label) { judgeOnce(deck, card, SwipeJudge.Grade.UNKNOWN); bumpShort() }, lp())
        row.addView(small(SwipeJudge.Grade.FUZZY.label) { judgeOnce(deck, card, SwipeJudge.Grade.FUZZY); hide() }, lp())
        row.addView(small(SwipeJudge.Grade.KNOWN.label) { judgeOnce(deck, card, SwipeJudge.Grade.KNOWN); hide() })
        root.addView(row)
        val dm = ctx.resources.displayMetrics
        // 转屏修复：横屏不再占满整块屏，压到 360dp 紧凑宽；竖屏保持原来的整屏宽。
        // 旧实现恒取 dm.widthPixels，横屏时窗口就是 2400px 一类的横屏宽度 —— 转回竖屏后窗口不重新排版，
        // 屏幕上就剩一张按旧宽度铺开的巨大浮层。横屏本身也不需要那么宽（读一列词而已）。
        val landscape = dm.widthPixels > dm.heightPixels
        val windowW = if (landscape) Math.min(dm.widthPixels, dp(360f)) else dm.widthPixels
        // 360dp 内要完整放下最多 5 颗小按钮（读 / 查 / 不认识 / 模糊 / 认识）：按实测收紧间距与内边距，
        // 字号（12sp）不动；最紧仍超出才等分宽度让文字换行，不裁字、不省略。
        if (landscape) fitButtons(row, windowW - dp(32f))
        baseY = Ui.statusBarHeight(ctx) + dp(8f)
        val params = WindowManager.LayoutParams(
            // v1.83：横向占满整屏（原来 75% 宽，长条目换行太多显挤）；内容区 = 屏宽 - 左右各 16dp padding
            // 转屏修复：横屏取 min(屏宽, 360dp)，竖屏仍是整屏宽
            windowW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // v1.81 加 FLAG_LAYOUT_NO_LIMITS：不加的话窗口被**钳制在屏幕内**（实测右边缘死死贴在 1080），
            // 横向最多挪 135px 就贴边，甩出去看着像"滑到边上消失"而不是"飞出去"。悬浮球一直是这么用的。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = baseY }
        // 纵向位移接到**窗口 y** 上（视图位移会被窗口裁掉，见 WindowShift）；入场起点 = 窗口先摆到静止位置上方 12dp。
        // params 由这一张的 WindowShift **捕获**（不再共享字段）：退场动画读不到下一张的 params。
        val shift = WindowShift(root, params, baseY)
        val m = AnimGlue.SpringAnimation(root, Anim.Spring.popIn, target = shift)
        winShift = shift
        motion = m
        m.settleAt(Anim.Visual.entering(0, -1, dp(Anim.ENTER_SHIFT_DP / 2).toFloat()))
        try { wm.addView(root, params) } catch (_: Exception) { return }
        view = root
        m.to(Anim.Visual.SHOW)   // 弹簧入场：被 hide 打断时接着当前速度走
        bump()
        // 延迟发音前先确认这张卡还在屏上：已退场（view 已换人 / 正在 closing）就别再发，免得替下一张念出声
        if (autoSpeak && Words.speakable(card.front)) main.postDelayed({
            if (view === root && !closing) onSpeak(card.front)
        }, 250)
    }

    /**
     * 「查」/ 长按单词：词书**没有**背面时，就地展开一份词典解读（先看缓存，再问模型）；
     * 词书**有**背面时（v1.80），只往上补「音标/词性 + 例句」，绝不覆盖词书主人写的释义。
     *
     * 两条路都不动卡片位置、不切 Activity —— 这就是「不再漂到屏幕下面」的关键。
     */
    private fun reveal(back: TextView, deck: String, card: Words.Card) {
        // 两步揭示下背面默认是 GONE 的：这里先亮出来（命中缓存那条捷径也要走这一步），提示让位
        back.visibility = View.VISIBLE
        hintView?.visibility = View.GONE
        val deckText = WordSense.cardBack(card.back, card.example)
        val hasDeckSide = deckText.isNotBlank()
        // 词书那一侧已经有了，就不用去看预翻译缓存（那是给「光秃秃一个词」准备的）
        val hit = if (hasDeckSide) null else cachedDetail(deck, card.front)
        if (!hit.isNullOrBlank()) { fillDetail(back, card, deckText, hit); bump(); return }
        back.text = if (hasDeckSide) "$deckText\n查词中…" else "查词中…"
        back.visibility = View.VISIBLE
        bump()
        fetchDetail(deck, card.front) { v ->
            main.post {
                // 词卡修复：这张卡可能已经被收走 / 换成下一张（[backView] 已清或指向新卡）——
                // 迟到的译文别写到新卡上，更别 bump() 去改新卡的自动收起计时。
                if (backView !== back) return@post
                back.text = when {
                    !v.isNullOrBlank() -> textFor(card, deckText, v)
                    hasDeckSide -> "$deckText\n没查到补充（离线 / 没配接口）"
                    else -> "没查到这个词（离线 / 没配接口 / 额度用完）"
                }
                back.visibility = View.VISIBLE
                bump()
            }
        }
    }

    /**
     * 词典解读 / 译文 → 卡片背面要怎么显示（v1.80）：
     *  - 词书有释义 → **只补音标 + 例句**（[WordSense.appendDict]，重复点也不会叠两遍）
     *  - 没词书释义、拿到的是排好的词典 → 原样上屏
     *  - 没词书释义、拿到的是一句裸译文 → 套 [WordSense.formatTranslation] 排（不再编例句）
     */
    private fun textFor(card: Words.Card, deckText: String, text: String): String = when {
        deckText.isNotBlank() -> WordSense.appendDict(deckText, text)
        WordDict.looksFormatted(text) -> text
        else -> WordSense.formatTranslation(card.front, text).ifBlank { text }
    }

    private fun fillDetail(back: TextView, card: Words.Card, deckText: String, text: String) {
        back.text = textFor(card, deckText, text)
        back.visibility = View.VISIBLE
    }

    // v1.82：停留多少由调用方给的 stayMs 决定（读 / 展开释义 / 弹回来都算「有人碰」，重置计时）
    private fun bump() { reschedule(stayMs().coerceAtLeast(1_000L)) }

    private fun bumpShort() { reschedule(3500) }

    /**
     * 词卡修复：重排自动收起。**手指还按着（[touchHeld]）或正在退场（[closing]）时不排** ——
     * 手动滚动读长卡时不能被收走，等 UP / CANCEL 再恢复倒计时。停留秒数与超时机制不变，
     * 只是「按住那几秒」不计入 [stayMs]。
     */
    private fun reschedule(ms: Long) {
        main.removeCallbacks(autoHide)
        if (touchHeld || closing) return
        main.postDelayed(autoHide, ms)
    }

    /** 卡上统一小按钮：默认配色白底黑描边（逐位不变）；新配色用主题卡底色 + 主题描边，字按填色选可读色。
     *  瑞士风格：直角 + [Shape.strokeDp] 细线（没有圆角、没有投影），仍是白底墨字。
     *  12sp、约 32dp 高；按下缩到 0.96 + 背景加深（60ms），抬起重置 */
    private fun small(label: String, onClick: () -> Unit): TextView {
        val fill = Brut.CARD
        val stroke = Brut.INK   // v2.7.3：去掉 palette == null 时的写死 0xFF222222，描边与全库同一支墨
        val bg: android.graphics.drawable.Drawable = if (Ui.theme.isGlassMaterial)
            Ui.material(ctx, fill, Ui.SurfaceRole.BUTTON, 8f)
        else android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(corner(8f)).toFloat()
            setStroke(Math.max(1, dp(strokeW())), stroke)
        }
        return Ui.button(ctx, label, onClick).apply {
            textSize = 12f; minHeight = dp(32f); background = bg
            setTextColor(Brut.on(fill))
            // 玻璃的底不是 GradientDrawable，按下加深给不了就按原色还原（缩放 / 下沉反馈仍在）
            AnimGlue.attachPress(this, haptic = true, fillOf = { fill })
        }
    }

    /**
     * 横屏紧凑宽下的按钮行适配（转屏修复）：横屏卡片只有 360dp，5 颗按钮按原间距 + 原内边距实测会超出，
     * 超出部分被窗口裁掉（「认识」看不见）。这里按实测宽度从宽到紧试几组「内边距 × 间距」，取第一组放得下的；
     * **只动间距和内边距，12sp 字号不动**。极端大字号下最紧仍放不下时改为等分宽度，让文字在按钮内换行 ——
     * 每颗按钮仍然完整可见（不省略、不缩字）。
     */
    private fun fitButtons(row: LinearLayout, avail: Int) {
        val gaps = intArrayOf(dp(8f), dp(6f), dp(4f), dp(2f))
        val pads = intArrayOf(dp(9f), dp(7f), dp(5f), dp(3f))
        for (pad in pads) for (gap in gaps) {
            var total = 0
            for (i in 0 until row.childCount) {
                val b = row.getChildAt(i) as TextView
                b.setPadding(pad, b.paddingTop, pad, b.paddingBottom)
                b.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                total += b.measuredWidth
                if (i < row.childCount - 1) total += gap
            }
            if (total <= avail) {
                for (i in 0 until row.childCount) {
                    val lp = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
                    lp.marginEnd = if (i < row.childCount - 1) gap else 0
                    row.getChildAt(i).layoutParams = lp
                }
                return
            }
        }
        // 兜底：等分整行（TextView 默认会换行），宽度不再溢出卡片
        for (i in 0 until row.childCount) {
            row.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(1f)
            }
        }
    }

    // ---------------- 富卡背面排版（v1.81：书页式词库背面）----------------

    /**
     * 书页式背面 → 可滚动视图。结构：竖排的「（可选）标签 chip + 正文行」，
     * 音标行加粗、`·` 残句淡化、①~⑩ 序号行给一点悬挂缩进。整块塞进限高 ScrollView，
     * 内容不超过就不滚（[MaxHeightScrollView] 只在超过 45% 屏高时才封顶）。
     * 配色全部取自 [Brut] 现有色（缺「助记」淡黄才补了 [Brut.SAND]），不引魔法值。
     */
    private fun richBack(back: String): View {
        cancelStagger()   // 上一张卡没走完的错峰任务先撤掉（换卡时不留尾巴）
        staggers.clear()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((i, b) in KaoyanBack.parseMemoized(back).withIndex()) {
            val group = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            when {
                b.frag -> group.addView(
                    Ui.text(ctx, b.text, 11f, panelHint).apply { setLineSpacing(0f, 1.25f) },
                    LinearLayout.LayoutParams(-1, -2)
                )
                b.tag.isEmpty() -> group.addView(
                    // 音标 / 词性行：加粗、用面板正文色、字号比正文略小
                    Ui.text(ctx, b.text, 12.5f, panelInk).apply { setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(-1, -2)
                )
                else -> {
                    if (KaoyanBack.isExamTag(b.tag)) {
                        addCollapsedBlock(group, b)   // 真题块：排到最后（parse 已做）+ 默认折叠
                    } else {
                        group.addView(chip(b.tag), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4f) })
                        addBody(group, b.text)
                    }
                }
            }
            // v1.81 错峰淡入：每块先摆到「下方 6dp + 全透明」，等**点开背面**时才依次弹上来
            // （见 startStagger / resetStagger）。不是一弹卡就全出来 —— 先看词、点一下再看释义。
            val motion = AnimGlue.SpringAnimation(group, Anim.Spring.popIn)
            motion.settleAt(staggerStart())
            staggers += motion
            col.addView(group, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8f) })
        }
        return MaxHeightScrollView(ctx, (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            // 内容超了才会被裁：上下边缘做渐隐，别让文字被生生切断（看着像坏了）
            isVerticalFadingEdgeEnabled = true
            // 纵/横各自的长度没有公开 setter，只有这个（已废弃但仍在用的）通用 setter
            @Suppress("DEPRECATION")
            setFadingEdgeLength(dp(18f).toInt())
            addView(col, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 「真题」块的折叠排版（v1.83，用户要求「真题例子放在最后，折叠起来」）。第一行只放标签 chip +
     * 一行小字提示，正文塞进子容器里、初始 [View.GONE]；点这一行（或整组）切换正文显隐，并把提示在
     * 「点一下展开 / 收起」之间切换。
     *
     * 和「两步揭示」的关系：这一行/整组自带 OnClickListener，点击会被它自己吃掉、**不会冒泡**到
     * [show] 里那个 `root.setOnClickListener`（那是「点空白 = 展开/收起整个背面」），所以点折叠行
     * 既不会把整张卡收起、也不会误触背面的开合。拖动关闭由 [CardRoot.onInterceptTouchEvent] 判定，
     * 它只在位移超过 slop 时接管；纯点击（无位移）不满足条件 —— 两条路互不干扰。
     */
    private fun addCollapsedBlock(group: LinearLayout, b: Block) {
        val tip = Ui.text(ctx, HINT_EXPAND, 11f, panelHint)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL   // chip 比小字高，提示与 chip 垂直居中对齐
        }
        head.addView(chip(b.tag), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6f) })
        head.addView(tip, LinearLayout.LayoutParams(-2, -2))
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addBody(body, b.text)
        group.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4f) })
        group.addView(body, LinearLayout.LayoutParams(-1, -2))
        val toggle = View.OnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            tip.text = if (show) HINT_COLLAPSE else HINT_EXPAND
            bump()   // 展开 / 收起也算「有人碰」，重置自动收起计时
        }
        head.setOnClickListener(toggle)
        // 正文区**只吞掉点击、不做动作**：卡片 root 上挂着「点一下收回」（两步揭示），
        // 不吞的话，在展开的真题正文上点一下会把整张卡收回去。只有标签那行才切换展开/收起。
        group.setOnClickListener { }
    }

    /**
     * 正文：走 [KaoyanBack.layout] 分行 —— ①~⑩ / ❶~❿ 序号行加左内边距（悬挂缩进），
     * 序号项的**续行**（[KaoyanBack.Line.indent] == 1）也给同样的左缩进，别让续行顶格贴着标签
     * （修「换行后悬挂缩进失去归属」）。其余渲染（错峰淡入 / 折叠 / 限高 / 拖拽）一律不动。
     *
     * v1.85：词性行（`❶n.`）加粗 —— 词性与词义合并成一块之后它就是正文第一行，不加粗的话
     * 读起来仍和后面的义项平级，「同一件事的两半」这个观感只去掉了一半。判据见 [KaoyanBack.isPosLine]。
     */
    private fun addBody(col: LinearLayout, text: String) {
        for (line in KaoyanBack.layout(text)) {
            // layout 为维持「字符不增不减」会保留行尾的换行符/空白，显示前统一 trim 掉
            val display = line.text.trim()
            if (display.isEmpty()) continue
            val tv = Ui.text(ctx, display, 13f, Ui.TX2).apply { setLineSpacing(0f, 1.3f) }
            if (KaoyanBack.isPosLine(display)) {
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                tv.setTextColor(panelInk)
            }
            val hanging = line.indent > 0 ||
                display.first() in '\u2460'..'\u2469' || display.first() in '\u2776'..'\u277F'
            if (hanging) tv.setPadding(dp(12f), 0, 0, 0)
            col.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 标签 chip：2dp 黑描边 + 淡色底 + 硬投影，风格照 [Brut.box] 那套新粗野主义；字按底色选可读色。
     *
     * 瑞士风格下 [Brut.box] 自己会切成「直角 + 1dp 细线、无投影」（投影为 0 时它不再叠影层），
     * 所以这里只跟着把**给投影留的右下内边距**收平 —— 没有投影还留着那截就成了偏心的字。
     */
    private fun chip(tag: String): TextView = Ui.text(ctx, tag, 11f, Brut.on(tagFill(tag))).apply {
        setTypeface(typeface, Typeface.BOLD)
        // 投影跟着形状走：声明了无投影（瑞士）就让 [Brut.box] 取形状默认值，显式传数值会把它顶掉。
        // 玻璃：共享材质的小 chip（淡色底交给色板，高光边跟着来），右下那截投影留白也一并收平。
        background = if (glass) Ui.material(ctx, tagFill(tag), Ui.SurfaceRole.CHIP, 8f)
                     else Brut.box(ctx, tagFill(tag), if (flat) null else 2f)
        if (glass || flat) setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        else setPadding(dp(8f), dp(3f), dp(10f), dp(5f))
    }

    /**
     * 不同标签给不同淡色底：词义=薄荷、辨析/反义=玫瑰金、助记=米黄、词组/派生/同义=纸白。
     *
     * 瑞士风格**不给 chip 上色**：这套语言里颜色不承担分类（分类是一眼能读到的字），
     * 而且 MINT / ROSE 在瑞士色板里是「正红 + 暗红」两块 —— 一排 chip 会变成第二、第三处信号红。
     * 于是全部走纸底 + 1dp 墨线（见 [chip]），跟标题竖条 / 主按钮的红互不打架。
     */
    private fun tagFill(tag: String): Int = when {
        Ui.theme.noShadowStyle -> Brut.PAPER
        tag.contains("词义") || tag.contains("释义") -> Brut.MINT
        tag.contains("辨") || tag.contains("反义") -> Brut.ROSE
        tag.contains("助记") || tag.contains("记忆") -> Brut.SAND
        else -> Brut.PAPER   // 词组 / 派生 / 同义 / 例句 / 真题例句 …
    }

    // ---------------- 拖拽甩出（v1.78）----------------

    /**
     * 卡片上的触摸。返回 true = 这次手势已经被我们接管（[CardRoot.onInterceptTouchEvent] 在纵向移动
     * 超过 slop 时会把事件从子视图手里抢过来），返回 false = 让子视图照常处理（点击 / 长按）。
     */
    private fun onCardTouch(ev: android.view.MotionEvent): Boolean {
        // 已在退场：把排队的触摸整个吞掉，不再处理任何手势（否则会在退场动画上再叠一次拖动 / 判词提交）
        if (closing) return true
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                dragDx = 0f; dragDy = 0f; dragging = false
                samples.clear(); samples.add(ev.eventTime, ev.rawY)
                samplesX.clear(); samplesX.add(ev.eventTime, ev.rawX)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                samples.add(ev.eventTime, ev.rawY)
                samplesX.add(ev.eventTime, ev.rawX)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging) {
                    // 四个方向都认：用总位移判「开始拖」，横向斜向都算数
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) < slop) return false
                    dragging = true
                    // v1.85：两段临界在**这一刻**定死（横向按卡宽、纵向按卡高，floor = 44dp）。
                    // 词卡修复：卡高在拖动中已恒定（判词提示是常驻槽位），这里定死只是让整段手势口径一致。
                    val floor = dp(44f).toFloat()
                    dragThX = SwipeJudge.thresholds(view?.width ?: 0, floor)
                    dragThY = SwipeJudge.thresholds(view?.height ?: 0, floor)
                    lastStage = 0
                    main.removeCallbacks(autoHide)   // 手动拖着的时候别让自动收起把卡片收走
                }
                dragDx = dx; dragDy = dy
                applyDrag()
                // v1.85：按方向取阈值（左/右看卡宽、下/上看卡高），再求当前阶段。
                // 只把**主方向**的位移喂给 stage —— 让「松手生效」这一行提示和 judge 的判定口径一致
                // （judge 内部也只放大主方向那一个分量），免得提示说「松手生效」结果松手却弹回来。
                val grade = SwipeJudge.gradeOf(dragDx, dragDy)
                val horizontal = grade == SwipeJudge.Grade.UNKNOWN || grade == SwipeJudge.Grade.KNOWN
                val stage = if (horizontal) SwipeJudge.stage(dragDx, 0f, dragThX) else SwipeJudge.stage(0f, dragDy, dragThY)
                // 「过一个临界震一下」：只在**上跨**时震（0→1、1→2 各一次）。lastStage 只增不减，
                // 所以同一阶段不会重复震，临界点抖动也不会连着震。
                if (stage > lastStage) {
                    Ui.popTick(ctx)
                    lastStage = stage
                }
                updateHint(stage, grade)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                samples.add(ev.eventTime, ev.rawY)
                samplesX.add(ev.eventTime, ev.rawX)
                dragging = false
                releaseDrag(samplesX.velocity(), samples.velocity())
                return true
            }
            // 词卡修复：系统 CANCEL（被别的窗口接管 / 多指切换）**只回弹、不评分** ——
            // 以前和 UP 合并成一支走 releaseDrag，取消也会被判词提交，是误评分的一处来源。
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                cancelDrag()
                return true
            }
        }
        return false
    }

    /**
     * 跟手：把当前位移**写死**（经 [WindowShift] 落到窗口 x/y，速度清零），不走弹簧 ——
     * 手指在哪卡片在哪，任何插值都是延迟感。拖得越远越淡（最多淡到 15%），给「正在把它扔掉」的反馈。
     *
     * v1.85：写进窗口的位移不是手指位移，而是过了 [SwipeJudge.damp] 的**阻尼位移** ——
     * 过第一临界之后越拉越沉（按 0.62 打折），但还有余量能走到第二临界。「键程加长」那半靠的是
     * 阈值本身变大（[SwipeJudge.thresholds]），这半靠阻尼把手感变「推不动」。
     */
    private fun applyDrag() {
        val m = motion ?: return
        val w = (view?.width ?: 1).coerceAtLeast(1)
        val h = (view?.height ?: 1).coerceAtLeast(1)
        val tx = SwipeJudge.damp(dragDx, dragThX.t1)
        val ty = SwipeJudge.damp(dragDy, dragThY.t1)
        // 淡出按「相对自己尺寸的最大偏移」算，这样横着拖和竖着拖的手感一样。
        // v1.81：拖动过程中只轻淡一点（最多 15%）—— 之前淡掉 60%，手还在拖卡片就已经半透明了，
        // 松手时再快速淡掉，合起来就是「闪一下」。跟手阶段必须实着，渐隐交给退场。
        val rel = Math.max(Math.abs(tx) / w, Math.abs(ty) / h).coerceIn(0f, 1f)
        m.settleAt(Anim.Visual(1f - rel * 0.15f, 1f, tx, ty))
    }

    /**
     * 松手（v1.85）：判定交给 [SwipeJudge.judge]（只看**主方向**的投影终点，够到 t2 才算数）。
     *  - null（没过第二临界）→ 弹回：[Anim.Spring.release] 带一点过冲，并把手指速度喂给弹簧；
     *  - 一个 [SwipeJudge.Grade] → 沿判词方向继续甩出去（[Anim.Spring.throwOut]：从**当前位置**再走 0.55 张卡、
     *    全程渐隐、**不喂**手指速度）。四个方向都先经 [judgeOnce] 提交这次评分
     *    （[SwipeJudge.Grade.CLOSE] 只是「跳过」——上层收到后不落评分）。
     *
     * 退场起点取**当前拖到的位置**（[WindowShift] 里的 translationX/Y）：目标不再是「绝对 nx × 0.55span」，
     * 而是「当前位置 + 0.55span」。这样「拖动已经越过 0.55span 才松手」时卡片会**继续朝判词方向飞**，
     * 不会先反向退回到那个绝对点再出去。垂直退场只加 Y（保留当前 X）、水平退场只加 X（保留当前 Y），
     * 斜着拖松手也不会被突然拉正。
     *
     * v1.81 的「甩出方向由 [Anim.flingDir] 给出」换成「由判词方向给出」：评分已经定了方向，
     * 再按手指瞬时向量飞会出现「判了认识却斜着飞」的错位。位移仍按投影口径判，所以「甩一下」照样算数。
     */
    private fun releaseDrag(vx: Float, vy: Float) {
        val m = motion ?: return
        val w = (view?.width ?: 1).coerceAtLeast(1)
        val h = (view?.height ?: 1).coerceAtLeast(1)
        val grade = SwipeJudge.gradeOf(dragDx, dragDy)
        val horizontal = grade == SwipeJudge.Grade.UNKNOWN || grade == SwipeJudge.Grade.KNOWN
        val verdict = if (horizontal) SwipeJudge.judge(dragDx, dragDy, vx, vy, dragThX)
        else SwipeJudge.judge(dragDx, dragDy, vx, vy, dragThY)
        hideHint()   // 手指离屏：提示行先收掉（弹回那一支卡片还在，别让「松手生效」留在卡上）
        lastStage = 0
        if (verdict == null) {
            // 没过第二临界：弹回、不评分
            m.to(Anim.Visual.SHOW, Anim.Spring.release, Anim.Visual(0f, 0f, vx, vy))
            bump()
        } else {
            // 够到第二临界：沿判词方向甩出去。
            // 词卡修复：CLOSE（上滑 = 跳过）也走 [judgeOnce] —— OrbService 收到 CLOSE 只 mark recent、
            // 不落评分，正好是「不当认识、不消耗复习、后续再来」。每卡最多提交一次（[judgeOnce] 守住）。
            val d = judgeDeck
            val c = judgeCard
            if (d != null && c != null) judgeOnce(d, c, verdict)
            val (nx, ny) = dirOf(verdict)
            val dm = ctx.resources.displayMetrics
            // 退场起点 = 卡片**此刻**拖到的位置（窗口的 translationX/Y，已含过阻尼的位移），
            // 目标 = 起点沿判词方向再走 0.55 张卡。0.55 这一档的含义没变（不是一倍身量：卡片比屏幕窄得多，
            // 飞 1.25×身量只会一头撞在屏幕边上被切掉，看着像闪一下），变的是**从当前位置接着走**、
            // 而不是飞回一个绝对点 —— 拖过 0.55span 才松手也不会反向退回。
            val curX = winShift?.translationX ?: 0f
            val curY = winShift?.translationY ?: 0f
            val spanX = Math.min(w, dm.widthPixels) * 0.55f
            val spanY = Math.min(h, dm.heightPixels) * 0.55f
            // nx / ny 一次只有一个非零：水平判词只加 X、垂直判词只加 Y，正交那一轴原样保留（斜拖不被拉正）
            hide(
                Anim.Visual(0f, 1f, curX + nx * spanX, curY + ny * spanY),
                // 不喂手指速度：甩出的瞬时速度（好几千 px/s）配上 33ms 的帧上限，第一帧就能瞬移 200px，
                // 那正是「闪」的来源。让它按 [Anim.Spring.throwOut] 稳稳走完，慢下来才读得出方向。
                null,
                Anim.Spring.throwOut,
            )
        }
        dragDx = 0f
        dragDy = 0f
    }

    /**
     * 系统 CANCEL 的回弹（词卡修复）：**不评分、不喂速度**，只把卡片弹回原位（[Anim.Spring.release]）。
     * 和 [releaseDrag] 分开，避免「取消」被当成一次判词提交。
     */
    private fun cancelDrag() {
        val m = motion ?: return
        hideHint()
        lastStage = 0
        m.to(Anim.Visual.SHOW, Anim.Spring.release)
        bump()
        dragDx = 0f
        dragDy = 0f
    }

    /**
     * 提交一次评分（含 [SwipeJudge.Grade.CLOSE]）。**每张卡最多一次** —— 按钮和手势都经这里，
     * 连点两颗按钮、或先点了按钮再划卡，都不会重复提交（[judged] 在 [show] 里按新卡复位）。
     * 已经在退场（[closing]）也直接丢弃：这张卡的去向已定，迟到的按钮 / 手势不该再补一次评分。
     */
    private fun judgeOnce(deck: String, card: Words.Card, grade: SwipeJudge.Grade) {
        if (judged || closing) return
        judged = true
        onJudge(deck, card, grade)
    }

    /** 判词方向 → 甩出去的单位向量（和 [SwipeJudge.gradeOf] 的语义一一对应） */
    private fun dirOf(g: SwipeJudge.Grade): Pair<Float, Float> = when (g) {
        SwipeJudge.Grade.UNKNOWN -> -1f to 0f   // 左 = 不认识
        SwipeJudge.Grade.KNOWN -> 1f to 0f      // 右 = 认识
        SwipeJudge.Grade.FUZZY -> 0f to 1f      // 下 = 模糊
        SwipeJudge.Grade.CLOSE -> 0f to -1f     // 上 = 跳过（不评分）
    }

    /**
     * 拖动中的判词提示行（v1.85）：文字由 [SwipeJudge.hint] 给，颜色按方向取。
     * 阶段 0 文字为空 → [View.INVISIBLE]（**不是 GONE**：常驻槽位不变高，避免拖动中窗口 resize）；
     * 阶段 1 淡入（alpha 0.62）；阶段 2 更醒目（满 alpha、文案后缀「松手生效」）。
     */
    private fun updateHint(stage: Int, grade: SwipeJudge.Grade) {
        val v = judgeHint ?: return
        val text = SwipeJudge.hint(stage, grade)
        if (text.isEmpty()) {
            v.animate().cancel()
            v.visibility = View.INVISIBLE   // 常驻槽位：不 GONE，卡片高度不变
            v.alpha = 0f
            hintAlpha = 0f
            return
        }
        v.text = text
        val fill = hintFill(grade, stage)
        // 玻璃的底不是 GradientDrawable，setColor 会静默失效 —— 直接按新填色换一张材质，
        // 阶段 / grade 的强调色才跟得上；非玻璃仍是原地的 setColor（零分配）。
        if (glass) {
            if (fill != lastHintFill) {
                v.background = Ui.material(ctx, fill, Ui.SurfaceRole.BUTTON, 8f)
                lastHintFill = fill
            }
        } else (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(fill)
        v.setTextColor(Brut.on(fill))   // 默认配色 = 原来的墨色；新配色按填色选可读色
        val target = if (stage >= 2) 1f else 0.62f   // 阶段 1 淡、阶段 2 满
        val first = v.visibility != View.VISIBLE
        // INVISIBLE → VISIBLE 占位相同（maxLines = 1），只淡入不改卡高
        if (first) { v.alpha = 0f; v.visibility = View.VISIBLE }
        // animate() 是异步的：拿自己记的目标值 [hintAlpha] 去比，别拿 view.alpha（还没写回去）
        if (first || hintAlpha != target) {
            hintAlpha = target
            v.animate().alpha(target).setDuration(Anim.MICRO).start()
        }
    }

    /** 收起判词提示行（松手 / 取消 / 换卡）：[View.INVISIBLE] 掉，常驻槽位不动卡片高度 */
    private fun hideHint() {
        val v = judgeHint ?: return
        v.animate().cancel()
        v.visibility = View.INVISIBLE
        v.alpha = 0f
        hintAlpha = 0f
        lastHintFill = 0
    }

    /**
     * 提示行底色：按判词方向取色（认识 = 薄荷、不认识 = 玫瑰、模糊 = 米黄、跳过 = 中性灰）。
     * 字一律按填色选可读色。阶段 2 用更深的玫瑰，让「松手生效」最扎眼。
     *
     * 瑞士风格只有**一个**信号红：认识 = 信号红（这套语言里「确认」配得上红），不认识 = 墨块反白，
     * 模糊 / 跳过落在两档灰上。否则玫瑰与暗玫瑰在瑞士色板里就是「红 + 暗红」，
     * 两个方向看着差不多 —— 四个方向得靠文字分辨，颜色只做强调。阶段 2 不再加深，
     * 它已经有满 alpha 和「· 松手生效」后缀。
     */
    private fun hintFill(grade: SwipeJudge.Grade, stage: Int): Int = when {
        Ui.theme.noShadowStyle -> when (grade) {
            SwipeJudge.Grade.KNOWN -> Brut.MINT
            SwipeJudge.Grade.UNKNOWN -> Brut.INK
            SwipeJudge.Grade.FUZZY -> Brut.SAND
            SwipeJudge.Grade.CLOSE -> Brut.MUTED
        }
        else -> when (grade) {
            SwipeJudge.Grade.KNOWN -> Brut.MINT
            SwipeJudge.Grade.UNKNOWN -> if (stage >= 2) Brut.ROSE_DEEP else Brut.ROSE
            SwipeJudge.Grade.FUZZY -> Brut.SAND
            SwipeJudge.Grade.CLOSE -> Brut.MUTED   // 中性：上滑只是跳过，别看着像一次评分
        }
    }

    /**
     * 收卡片。默认向上淡出；拖拽甩出时由调用方给出 [exit]（朝手指方向飞走）和 [velocity]（手指速度）。
     * 走 to() 而不是「先把属性写死」—— 入场还没落定就叫停时，卡片会带着当时的速度拐出去。
     */
    fun hide(exit: Anim.Visual? = null, velocity: Anim.Visual? = null, spring: Anim.Spring = Anim.Spring.popOut) {
        main.removeCallbacks(autoHide)
        touchHeld = false        // 退场时若手指还按着，别把这面「停表」状态带到下一张
        cancelStagger()          // 背面还没冒出来的块别再往已移除的视图上排动画
        samples.clear()          // 拖到一半被收走（服务被杀 / 截屏前藏面板）时别留着上一段手势的采样
        samplesX.clear()
        dragDx = 0f; dragDy = 0f   // 复位窗口偏移：下一张卡必须回到屏幕中线上，别继承上一张被拖走的 x
        backView = null; hintView = null                  // 两步揭示的状态别带到下一张
        revealDeck = null; revealCard = null; revealHasBack = false
        // v1.85：判词提示行 / 阶段记忆 / 当前卡归属都别带到下一张
        judgeHint = null; hintAlpha = 0f; lastStage = 0
        judgeDeck = null; judgeCard = null
        dragThX = SwipeJudge.Thresholds(0f, 0f); dragThY = SwipeJudge.Thresholds(0f, 0f)
        staggers.clear()
        val v = view ?: return
        if (closing) return      // 已经在退场：别再起一段（服务停 / 截屏会重复调 hide）
        closing = true
        dragging = false
        val m = motion
        motion = null
        // 退场期间窗口不可触摸：按钮 / 手势全失效，「每卡最多提交一次」在手势这一侧也守住
        winShift?.makeUntouchable()
        val remove = {
            try { wm.removeView(v) } catch (_: Exception) {}
            // 只有「还是当前这一张」才清状态：晚到的回调不会把下一张卡的状态清掉
            if (view === v) { view = null; closing = false; winShift = null }
        }
        // 词卡修复：**不**在这里把 view 置空 —— 退场动画跑完、窗口真正 remove 之前，showing 一直是 true，
        // 上层据此不会在退场中途又弹下一张（旧实现 view=null 会让 showing 立刻变 false，下一张提前叠上来）。
        if (m == null) { remove(); return }
        m.to(exit ?: Anim.Visual.leaving(0, -1, dp(Anim.EXIT_SHIFT_DP / 2).toFloat()), spring, velocity) { remove() }
    }
}
