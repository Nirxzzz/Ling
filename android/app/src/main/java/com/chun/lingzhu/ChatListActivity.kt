package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 会话列表（仿微信「聊天」页排版）：每个 Agent（含小Ling）一行 —— 圆角方形头像 + 名字 + 一行最后消息摘要 +
 * 右上角时间 + 未读角标；置顶行浅底；行间细分隔线从文字左缘开始。
 *
 * 顶栏：居中标题「聊天(总未读)」+ 右侧搜索 / ＋（新建 Agent，复用聊天页同一套模板对话框 [PersonaPicker]）。
 * 底部一条入口栏：聊天（本页）/ 记事 / 学习 / 设置 —— 都是既有页面的入口，没有新功能。
 *
 * 数据全是现成的：人格列表 [Personas.Store]、每个人格的会话文件 [ChatStore.Store]；新增的只有
 * 「已读位置 / 置顶」两样小记录（[Marks]，单独一个 SharedPreferences 文件，不碰 `lingzhu` 主设置）。
 * 排序 / 时间 / 未读 / 搜索的判断都在纯函数 [ChatList] 里。
 * 配色只取 [Brut] 的 PAPER / CARD / INK / HINT / MINT 及派生色，各套外观与深色变体自动跟随；
 * 未读角标只在非瑞士配色里用固定红（红底白字 + 数字，灰度屏上靠「数字 + 方形」也读得出来）。
 *
 * **v2.6 瑞士国际主义**：这一页的圆角 / 描边 / 未读角标都过 [corner] / [swiss] 两个闸门 ——
 * 瑞士下头像、置顶标签、未读角标全变直角，搜索框从「圆角描边框」变成**只有一条下划线**（[underlineInput]），
 * 未读角标改用主题自己的信号红（[badgeFill]，深色版更亮一档），不再有第三方的固定色 E5484D。
 * 头像底也不再掺主色（掺出来是粉的）：素纸底 + 1dp 细线。其它配色逐位不变。
 */
class ChatListActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var personas: Personas.Store
    private lateinit var marks: Marks
    private lateinit var titleView: TextView
    private lateinit var listCol: LinearLayout
    private lateinit var setupBar: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchInput: EditText
    private var query = ""
    private var shownTheme: Theme? = null
    /** 上次画出来的数据指纹：轮询时没变就不重画（列表在前台时后台回复落盘了也能跟上） */
    private var lastSig = ""
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { render(force = false); handler.postDelayed(this, POLL_MS) }
    }

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.applyTheme(prefs)
        shownTheme = Ui.theme
        personas = Personas.Store(this)
        marks = Marks(this)

        // root 只装页面主体（顶栏 / 搜索行 / 列表）。底部导航栏挂在**更外层**的 page 上，
        // 见 onCreate 结尾 —— 栏若是 root 的最后一个子项，root 比屏幕高时会被挤出屏幕外。
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- 顶栏：标题居中，右侧 搜索 / ＋ ----
        val top = FrameLayout(this).apply { setBackgroundColor(Brut.PAPER) }
        titleView = TextView(this).apply {
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Brut.INK)
            gravity = Gravity.CENTER; maxLines = 1
        }
        if (swiss) {
            // 瑞士：标题换成「信号红竖条 + 墨色粗体」（这套风格里唯一允许的红，与 Brut.heading 同一形态）。
            // 竖条只在这一套配色下出现，其它配色的居中标题逐位不变。
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(this).apply { setBackgroundColor(Brut.MINT) },
                LinearLayout.LayoutParams(dp(3f), dp(17f)).apply { marginEnd = dp(8f) })
            head.addView(titleView, LinearLayout.LayoutParams(-2, -2))
            top.addView(head, FrameLayout.LayoutParams(-2, dp(52f), Gravity.CENTER))
        } else top.addView(titleView, FrameLayout.LayoutParams(-2, dp(52f), Gravity.CENTER))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(glyphButton(Glyph.SEARCH, "搜索") { toggleSearch(true) })
        actions.addView(glyphButton(Glyph.PLUS, "新建 Agent") { newAgent() })
        top.addView(actions, FrameLayout.LayoutParams(-2, dp(52f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4f) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52f)))
        root.addView(hairline(), LinearLayout.LayoutParams(-1, hairlinePx()))

        // ---- 搜索行（点放大镜才出现）----
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
            visibility = View.GONE
        }
        searchInput = EditText(this).apply {
            hint = "搜索 Agent 名字或聊天内容"; textSize = 15f; maxLines = 1; isSingleLine = true
            setTextColor(Brut.INK); setHintTextColor(Brut.HINT)
            // v2.6 瑞士：只有一条下划线，没有整圈框（和聊天页 composer 的输入形态同一个语言）
            background = when {
                swiss -> underlineInput()
                glass -> Ui.material(this@ChatListActivity, Brut.CARD, Ui.SurfaceRole.BUTTON, 10f)
                else -> rounded(Brut.CARD, 10f).apply { setStroke(maxOf(1, dp(1f)), Theme.argb(0x26, Brut.INK)) }
            }
            if (swiss) setPadding(0, dp(8f), 0, dp(8f))
            else setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render(force = true) }
            })
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchRow.addView(textButton("取消") { toggleSearch(false) })
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        // ---- 提示条：缺接口 / 缺悬浮窗权限时一句话（文案与聊天页同一个 [ChatList.setupHint]）----
        setupBar = TextView(this).apply {
            textSize = 13.5f; setTextColor(Brut.HINT); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            // 瑞士：整条提示带不铺灰底，改成上下两条发丝线（这套风格用线分栏，不用色块）
            background = when {
                swiss -> bandBg()
                glass -> Ui.material(this@ChatListActivity, Brut.CARD, Ui.SurfaceRole.CHIP, 12f)
                else -> android.graphics.drawable.ColorDrawable(rowTint(0.05f))
            }
            isClickable = true; isFocusable = true
            visibility = View.GONE
            setOnClickListener { routeSetup() }
        }
        root.addView(setupBar, LinearLayout.LayoutParams(-1, -2))

        // ---- 列表 ----
        listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listCol); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- 底部入口栏 ----
        // 栏必须挂在**整页的根**（page）上，不能塞进 root：root 是包着列表那层，
        // 栏若是 root 的最后一个子项，而 root 又比屏幕高，栏就会被推到屏幕外。
        // 这也是「uiautomator 里四格 bounds 只有 y=329、屏幕底是空白」的原因。
        // 这里原来还垫了一条全宽 0.5dp 分隔线（在 10dp 内缩之外，栏又是方底）。底栏现在自带
        // 同圆角的发丝线描边，这条就成了「直线上方再加一条圆角条」的旧痕迹，已删除；
        // 另外三页从来没有这条线，删掉之后四页的底栏才是同一个长相。
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
        // 顺序与另三页对齐（NotesActivity.kt:75-78 / StudyActivity.kt:62-63）：先 setContentView，
        // 再让 Brut.page 处理 inset，最后挂底栏。
        // 曾经试过在这里加 clipChildren = false 想解决「只画出一格」，无效 —— 问题不在裁剪。
        setContentView(page)
        Brut.page(this, page)
        BottomNav.attach(page, this, BottomNav.CHAT)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页换了配色 / 深浅色回来：整页按新色板重建（Brut 的颜色是建控件那一刻取的）
        val t = prefs.theme()
        if (t != shownTheme) { recreate(); return }
        val hint = ChatList.setupHint(!Settings.canDrawOverlays(this), !prefs.hasApi())
        setupBar.text = hint.orEmpty()
        setupBar.visibility = if (hint == null) View.GONE else View.VISIBLE
        // 注意：回到前台时不能无条件 render(force = true)。本页从底部栏切回 / 从聊天页按「‹」回来都会走 onResume，
        // 而 render 会 listCol.removeAllViews() 全量重建 —— 搜索词还在（query 没动），但滚动位置跳回顶部、
        // 输入框焦点丢失。列表数据靠下面的 poll 跟上：它按指纹比对，数据没变就一个视图都不动。
        handler.postDelayed(poll, POLL_MS)
        // 顺手补一次 `force = false` 的 render：onCreate 那次跑在首帧之前，若行高在首次测量后才定下来
        // （名字 / 摘要换行导致），这里就补上。指纹比对，数据没变一个视图都不动，不丢滚动、不丢焦点。
        // 早先这是一次性的 `resumedOnce` + `force = true`：多引一个状态，而且指纹一样也照全量拆一遍。
        render(force = false)
    }

    /**
     * 从别的区域经底部栏切回来（FLAG_ACTIVITY_REORDER_TO_FRONT | SINGLE_TOP）不重建，走这里。
     *
     * 注意：这里只 setIntent，不 render。列表用的还是切走前那份数据，
     * 轮询会自己把它补上；要是这里重画一遍，滚动位置就跳回顶部了。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchRow.visibility == View.VISIBLE) toggleSearch(false) else super.onBackPressed()
    }

    // ---------------- 数据 ----------------

    private fun sessions(): Pair<List<ChatList.Session>, Map<String, List<ChatStore.Msg>>> {
        val pins = marks.pins()
        val msgs = LinkedHashMap<String, List<ChatStore.Msg>>()
        val rows = personas.list().mapIndexed { i, p ->
            val l = ChatStore.Store(this, p.id).list()
            msgs[p.id] = l
            val last = l.lastOrNull()
            ChatList.Session(
                pid = p.id, name = p.name,
                summary = ChatList.summary(last),
                lastAt = last?.at ?: 0L,
                unread = ChatList.unread(l, marks.readAt(p.id)),
                pinned = p.id in pins,
                order = i,
            )
        }
        return ChatList.sort(rows) to msgs
    }

    /** 重画列表。[force] = false 时（轮询）数据指纹没变就不动，避免每 1.5 秒白拆一遍视图 */
    private fun render(force: Boolean) {
        val (all, msgs) = sessions()
        val shown = ChatList.search(all, msgs, query)
        val sig = query + "|" + shown.joinToString(";") { "${it.pid},${it.lastAt},${it.unread},${it.pinned},${it.summary.hashCode()},${ChatFlights.busy(it.pid)}" }
        if (!force && sig == lastSig) return
        lastSig = sig
        titleView.text = ChatList.title(ChatList.totalUnread(all))
        listCol.removeAllViews()
        if (shown.isEmpty()) {
            listCol.addView(TextView(this).apply {
                text = "没有找到「${query.trim()}」"; textSize = 14f; setTextColor(Brut.HINT)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(48f), dp(16f), dp(48f))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val now = System.currentTimeMillis()
        for (s in shown) listCol.addView(row(s, personas.get(s.pid), now), rowLp())
    }

    // ---------------- 一行 ----------------

    private fun row(s: ChatList.Session, p: Persona, now: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, 0, 0)
            // 瑞士不铺置顶浅底（这套风格里纸底只有一种，置顶靠排序 + 那个 1dp 方框标签表达）
            val base = if (s.pinned && !swiss) rowTint(0.045f) else Brut.PAPER
            // 玻璃（v2.7.3）：每行是一张共享材质的小卡（10dp 左右边距 + 3dp 上下留白，见 render）；
            // 其余配色仍是通栏列表行，纸底 + 发丝分隔。
            background = when {
                glass -> RippleDrawable(ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)),
                                        Ui.material(this@ChatListActivity, Brut.CARD, Ui.SurfaceRole.CARD, 0f), null)
                else -> RippleDrawable(ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)),
                                       GradientDrawable().apply { setColor(base) }, null)
            }
            isClickable = true; isFocusable = true; isLongClickable = true
            contentDescription = buildString {
                append(s.name)
                if (s.pinned) append("，已置顶")
                if (s.unread > 0) append("，${s.unread} 条未读")
            }
            setOnClickListener { startActivity(ChatActivity.open(this@ChatListActivity, s.pid)) }
            setOnLongClickListener { rowMenu(it, s); true }
        }
        row.addView(avatar(p, s.unread), LinearLayout.LayoutParams(dp(54f), dp(54f)))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line1.addView(TextView(this).apply {
            text = s.name; textSize = 16.5f; setTextColor(Brut.INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line1.addView(TextView(this).apply {
            text = ChatList.timeLabel(s.lastAt, now); textSize = 12f; setTextColor(Brut.HINT)
            maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f); marginEnd = dp(16f) })
        right.addView(line1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13f) })

        val busy = ChatFlights.busy(s.pid)
        val sum = when {
            busy -> "正在回复…"
            s.lastAt == 0L -> p.role.ifBlank { "还没聊过" }        // 空会话：给一句职责，不留空行
            else -> s.summary
        }
        val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line2.addView(TextView(this).apply {
            text = sum; textSize = 13.5f; setTextColor(Brut.HINT)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (s.pinned) line2.addView(TextView(this).apply {
            text = "置顶"; textSize = 10.5f; setTextColor(if (swiss) Brut.INK else Brut.HINT)
            setPadding(dp(5f), dp(1f), dp(5f), dp(1f))
            // 瑞士：直角 + 1dp 墨色细线（hint 灰线在白纸上看不清，这套风格的线只有一档）；
            // 玻璃：共享材质的小 chip（淡底 + 高光边）。
            background = when {
                swiss -> rounded(0, 4f).apply { setStroke(maxOf(1, dp(Ui.shape.strokeDp)), Brut.INK) }
                glass -> Ui.material(this@ChatListActivity, Ui.theme.pillFill, Ui.SurfaceRole.CHIP, 4f)
                else -> rounded(0, 4f).apply { setStroke(maxOf(1, dp(0.8f)), Theme.argb(0x66, Brut.HINT)) }
            }
            })
        right.addView(line2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        // 分隔线：从文字左缘起，一直到屏幕右边（微信的样子）
        if (!glass) right.addView(hairline(), LinearLayout.LayoutParams(-1, hairlinePx()).apply { topMargin = dp(13f) })
        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10f) })
        return row
    }

    /** 圆角方形头像（48dp）+ 右上角未读角标 */
    private fun avatar(p: Persona, unread: Int): View {
        val box = FrameLayout(this)
        // 瑞士：头像底不掺主色（掺出来是粉的），改成素纸底 + 1dp 细线框 —— 头像里的矢量图自己带红
        val fill = if (swiss) Brut.PAPER else (0xFF000000.toInt() or Theme.mix(Brut.CARD, Brut.MINT, 0.38f))
        val face = FrameLayout(this).apply {
            background = when {
                glass -> Ui.material(this@ChatListActivity, fill, Ui.SurfaceRole.CHIP, 8f)
                else -> rounded(fill, 8f).apply {
                    if (swiss) setStroke(maxOf(1, dp(Ui.shape.strokeDp)), Brut.INK)
                }
            }
        }
        // 内置小Ling和三个模板有各自的矢量图；「空白」模板建的自定义 Agent 用名字首字，不然全长成小Ling的样子
        val custom = p.id != Personas.P0 && p.iconKey == PersonaIcon.K_CHAT
        if (custom) face.addView(TextView(this).apply {
            text = ChatList.initial(p.name); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Brut.on(fill)); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        else face.addView(PersonaIcon(this, p.iconKey, sizeDp = 38f), FrameLayout.LayoutParams(dp(38f), dp(38f), Gravity.CENTER))
        box.addView(face, FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.START or Gravity.BOTTOM))
        val b = ChatList.badge(unread)
        if (b.isNotEmpty()) box.addView(TextView(this).apply {
            text = b; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(badgeInk())
            gravity = Gravity.CENTER; includeFontPadding = false
            minWidth = dp(18f); minHeight = dp(18f)
            setPadding(dp(5f), 0, dp(5f), 0)
            background = when {
                glass -> Ui.material(this@ChatListActivity, badgeFill(), Ui.SurfaceRole.CHIP, 9f)
                else -> rounded(badgeFill(), 9f).apply { setStroke(maxOf(1, dp(1.2f)), Brut.PAPER) }
            }
        }, FrameLayout.LayoutParams(-2, dp(18f), Gravity.END or Gravity.TOP))
        return box
    }

    private fun rowMenu(anchor: View, s: ChatList.Session) {
        val pm = ThemeDialogs.popup(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, if (s.pinned) "取消置顶" else "置顶")
        if (s.unread > 0) pm.menu.add(0, 2, 1, "标为已读")
        // v2.4：编辑人设（名字 / 职责 / 开场白 / 提示词 / 模型 / 温度）
        pm.menu.add(0, 3, 2, "编辑人设")
        // v2.3.3：每个 Agent 能用的本机工具（建卡 / 提醒 / 查记忆…）在这里改
        pm.menu.add(0, 4, 3, "工具权限")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> marks.setPinned(s.pid, !s.pinned)
                2 -> marks.markRead(s.pid, ChatStore.Store(this, s.pid).list().lastOrNull()?.at ?: 0L)
                3 -> editPersona(s.pid)
                4 -> editScope(s.pid)
            }
            render(force = true); true
        }
        pm.show()
    }

    /** 改某个 Agent 的完整人设。保存后重绘，名字 / 职责变了能立刻在列表上看到 */
    private fun editPersona(pid: String) {
        PersonaEditor.open(this, pid, Personas.Store(this)) { render(force = true) }
    }

    /** 改某个 Agent 的工具权限。取消之后不刷新也行，但刷新能立刻看到副标题变化 */
    private fun editScope(pid: String) {
        val store = Personas.Store(this)
        val p = store.list().firstOrNull { it.id == pid } ?: return
        AgentScopeDialog.show(this, p.name + " 能用哪些工具", p.tools) { enc ->
            store.update(p.copy(tools = enc))
            render(force = true)
        }
    }

    // ---------------- 顶栏动作 ----------------

    private fun newAgent() {
        PersonaPicker.newFromTemplate(this, personas) { id ->
            try { startActivity(ChatActivity.open(this, id)) } catch (_: Exception) { toast("打不开聊天页") }
        }
    }

    private fun toggleSearch(on: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (on) {
            searchRow.visibility = View.VISIBLE
            searchInput.requestFocus()
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
            searchInput.setText("")
            searchRow.visibility = View.GONE
        }
    }

    /** 提示条点一下：缺接口去填接口；否则补权限（新用户走引导，老用户去一键开权限）—— 与聊天页同一套落点 */
    private fun routeSetup() {
        try {
            when {
                !prefs.hasApi() -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_API, true))
                Onboarding.shouldShow(prefs.onboarded, prefs.onboardStarted, prefs.keys()) -> startActivity(Intent(this, MainActivity::class.java))
                else -> startActivity(PermissionActivity.intent(this))
            }
        } catch (_: Exception) { toast("打不开设置页") }
    }

    // ---------------- 小部件 ----------------

    /** 纸底往墨色掺一点：置顶行 / 提示条的浅底（深色主题里自然变成略亮一档） */
    private fun rowTint(t: Float): Int = 0xFF000000.toInt() or Theme.mix(Brut.PAPER, Brut.INK, t)

    /** 分栏线：普通配色 0.5dp 的 12% 墨色（原样）；瑞士换成主题自己的发丝线 —— 这套风格里线是结构的一部分 */
    private fun hairline(): View = View(this).apply {
        setBackgroundColor(if (swiss) Ui.HAIRLINE else Theme.argb(0x1F, Brut.INK))
    }

    /** 分栏线的像素高：普通配色 0.5dp，瑞士 [Shape.strokeDp]（与卡片 / 头像框同粗细，1dp 才是这套的「细线」） */
    private fun hairlinePx(): Int = maxOf(1, dp(if (swiss) Ui.shape.strokeDp else 0.5f))

    /** 上下各一条发丝线的空底带（瑞士的提示条）：界线落在内边距里，高度不变 */
    private fun bandBg(): Drawable = LayerDrawable(arrayOf(
        GradientDrawable().apply { setColor(0) },
        GradientDrawable().apply { setColor(Ui.HAIRLINE) },
        GradientDrawable().apply { setColor(Ui.HAIRLINE) },
    )).apply {
        setLayerGravity(1, Gravity.TOP); setLayerHeight(1, hairlinePx())
        setLayerGravity(2, Gravity.BOTTOM); setLayerHeight(2, hairlinePx())
    }

    /**
     * v2.6 瑞士国际主义：这一页的形状是手画的，不走 [Brut.style]，形状语言得自己跟 [Ui.shape] 走。
     * 判据与 Brut 内部同一口径（投影为 0 = 「无投影」的那套），主题包显式设过形状也照样生效。
     */
    private val swiss: Boolean get() = Ui.shape.shadowDp <= 0f

    /** 当前配色是不是液态玻璃材质（v2.7.3）：只决定「谁来画底」，列表数据 / 触摸行为都不变 */
    private val glass: Boolean get() = Ui.theme.isGlassMaterial

    /** 圆角档位：平时用调用点给的，瑞士一律取 [Ui.shape] 的直角（角标、头像、标签、水波遮罩都跟着走） */
    private fun corner(radiusDp: Float): Float = if (swiss) Ui.shape.cornerDp else radiusDp

    /**
     * 列表行的布局参数：玻璃下每行是一张小卡，左右收 10dp、上下留 3dp（圆角才看得出来）；
     * 其余配色仍是通栏行，一个像素都不动。
     */
    private fun rowLp(): LinearLayout.LayoutParams =
        if (glass) LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(10f); marginEnd = dp(10f); topMargin = dp(3f); bottomMargin = dp(3f)
        } else LinearLayout.LayoutParams(-1, -2)

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(corner(radiusDp)).toFloat()
    }

    /**
     * 未读角标的底色：平时维持全页唯一的固定红（跨套外观都能一眼看到「有新消息」）；
     * 瑞士改用主题自己的信号红 —— 这套风格里红是色板的一部分，再叠一个外来红就成了第二个彩色。
     */
    private fun badgeFill(): Int = when {
        swiss -> Brut.MINT
        // 玻璃：主色往墨色压深一档（浅色主色在玻璃卡上挂不住「未读」的分量）；其余配色仍是原固定红
        glass -> Theme.argb(0xFF, Theme.mix(Brut.MINT, Brut.INK, 0.35f))
        else -> BADGE
    }

    /** 角标字色：固定红底上仍是原来的白；主题色底（瑞士 / 玻璃）按对比度挑黑 / 白 */
    private fun badgeInk(): Int = if (swiss || glass) Brut.on(badgeFill()) else 0xFFFFFFFF.toInt()

    /**
     * 瑞士的输入形态：**只有一条下划线**，没有整圈框。聚焦时线加粗到 [Shape.focusStrokeDp] 并变成实心墨色。
     * 用 [LayerDrawable] 贴底画一条实色条（`setStroke` 画的是四边，那不是下划线），与聊天页 composer 同一份写法。
     */
    private fun underlineInput(): Drawable = StateListDrawable().apply {
        fun line(wDp: Float, col: Int): Drawable =
            LayerDrawable(arrayOf(GradientDrawable().apply { setColor(col) })).apply {
                setLayerGravity(0, Gravity.BOTTOM)
                setLayerHeight(0, maxOf(1, dp(wDp)))
            }
        addState(intArrayOf(android.R.attr.state_focused), line(Ui.shape.focusStrokeDp, Brut.INK))
        addState(intArrayOf(), line(Ui.shape.strokeDp, Theme.argb(0x55, Brut.INK)))
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Brut.INK); gravity = Gravity.CENTER
        minimumWidth = dp(56f); minimumHeight = dp(48f)
        isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)), null, rounded(-1, 18f))
        setOnClickListener { onClick() }
    }

    private fun glyphButton(kind: Glyph, desc: String, onClick: () -> Unit): View = GlyphView(this, kind).apply {
        minimumWidth = dp(48f); minimumHeight = dp(48f)
        isClickable = true; isFocusable = true
        contentDescription = desc
        background = RippleDrawable(ColorStateList.valueOf(Theme.argb(0x22, Brut.INK)), null, rounded(-1, 24f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    enum class Glyph { SEARCH, PLUS }

    /** 顶栏两个线条图标（放大镜 / 圆圈加号），自己画，跟着 INK 走 */
    private class GlyphView(ctx: Context, private val kind: Glyph) : View(ctx) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Brut.INK; strokeCap = Paint.Cap.ROUND
            strokeWidth = Ui.dp(ctx, 1.8f).toFloat()
        }
        override fun onDraw(c: Canvas) {
            val s = Ui.dp(context, 22f).toFloat()
            val l = (width - s) / 2f; val t = (height - s) / 2f
            when (kind) {
                Glyph.SEARCH -> {
                    val r = s * 0.36f
                    val cx = l + r + s * 0.06f; val cy = t + r + s * 0.06f
                    c.drawCircle(cx, cy, r, pen)
                    val d = r * 0.7071f
                    c.drawLine(cx + d, cy + d, l + s * 0.94f, t + s * 0.94f, pen)
                }
                Glyph.PLUS -> {
                    val cx = l + s / 2f; val cy = t + s / 2f
                    c.drawCircle(cx, cy, s / 2f - pen.strokeWidth / 2f, pen)
                    val a = s * 0.22f
                    c.drawLine(cx - a, cy, cx + a, cy, pen); c.drawLine(cx, cy - a, cx, cy + a, pen)
                }
            }
        }
    }

    /**
     * 「已读位置 / 置顶」两样小记录。单独一个文件 `lingzhu_chatlist`：不进 `lingzhu` 主设置
     * （那边的键集合会参与老用户判定 [Onboarding.legacyConfigured]，别往里塞新键）。
     * 置顶从没设过 = 默认只置顶小Ling。
     */
    class Marks(ctx: Context) {
        private val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun readAt(pid: String): Long? = if (sp.contains(K_READ + pid)) sp.getLong(K_READ + pid, 0L) else null

        /** 只往前推，不往回退（同一毫秒反复画不会写盘） */
        fun markRead(pid: String, at: Long) {
            val cur = readAt(pid)
            if (cur != null && cur >= at) return
            sp.edit().putLong(K_READ + pid, at).apply()
        }

        fun pins(): Set<String> = sp.getStringSet(K_PINS, null)?.toSet() ?: setOf(Personas.P0)

        fun setPinned(pid: String, on: Boolean) {
            val s = pins().toMutableSet()
            if (on) s.add(pid) else s.remove(pid)
            sp.edit().putStringSet(K_PINS, s).apply()
        }

        companion object {
            const val FILE = "lingzhu_chatlist"
            private const val K_READ = "read_"
            private const val K_PINS = "pins"
        }
    }

    companion object {
        private const val POLL_MS = 1500L
        /** 未读角标底色：非瑞士配色下的固定红（各套外观里都要一眼看到「有新消息」）；瑞士改用主题信号红，见 [badgeFill] */
        private const val BADGE = 0xFFE5484D.toInt()
    }
}
