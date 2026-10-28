package com.chun.lingzhu

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 「使用权限」页（v1.86 起）：把装完 App 要开的系统权限做成一张**新手也能看懂的紧凑清单** ——
 * 顶部一句总说明 + 一个「一键配置」按钮，下面按「必需 / 可选」分区，每项一行：状态 + 一个按钮，操作原理收进「详情」折叠。
 *
 * 用户视角：点「一键配置」→ 先跳到「悬浮窗」那一页，拨一下开关按返回 → 再跳到「无障碍」页 → 通知的系统框点「允许」→
 * 电池的系统框点「允许」→ 回到本页，状态自动刷新。**中间不用自己在设置里翻任何菜单**，缺的项由 App 一页页递到手边。
 * 已经开过的项**不会再弹**（[PermissionFlow.next] 只挑没做的），精确闹钟不进一键流程、在可选里单独开。
 *
 * 判断层全在 [PermissionFlow]（纯函数、可单测）；这里只做三件事：采状态、画清单、按 [PermissionFlow.next]
 * 的顺序**自动推进**（跳出去 → 回来 → 重采 → 接着下一项）。本文件只碰界面，不改 [PermissionFlow] 的判断口径。
 *
 * ⚠️ **已授权 ≠ 已连接**：无障碍开关开着但服务没连上时，这里写「已授权 · 服务未连接」，并给一个能点的
 * 「查看系统开关」，绝不把它显示成禁用的「已开启」、也不停在等待里（[PermissionFlow.a11yAuthorizedNotConnected]）。
 * 回前台挂 [KeepAlive.observeA11y]（系统开关 / 连接状态一变就重画），离开时关掉 —— 不持有 Activity。
 *
 * ⚠️ 不死循环是这一页的命门：任何一项「跳出去又没开就回来」都记成「先不开」并继续下一项
 * （运行时权限被拒也一样），最多推进 [PermissionFlow.AUTO] 的项数趟就一定停。**必需项即使被记成先不开，
 * 也仍然算「没开」**（[PermissionFlow.blocked]），不会被当成已完成。
 */
class PermissionActivity : Activity() {
    private lateinit var root: LinearLayout
    /** 自动推进中（false = 用户只是单独点某一项，回来不接着推） */
    private var running = false
    /** 刚跳出去等哪一项回来（null = 没在等） */
    private var waitingFor: PermissionFlow.Item? = null
    /** 本轮已经「先不开」的项 */
    private val skipped = LinkedHashSet<String>()
    /** 详情折叠：点标题行才展开/收起（只给标题行点击，正文区不外冒） */
    private val expanded = LinkedHashSet<String>()
    /** 首次进入只铺一次默认展开，之后跟随用户点击 */
    private var expandedSeeded = false
    /** 顶部「操作说明」折叠，默认收起 —— 不把大段解释堆在开页 */
    private var howToOpen = false
    /** 无障碍授权 / 连接状态的订阅（onResume 挂、onPause 关，不持有 Activity） */
    private var a11yWatch: java.io.Closeable? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(Prefs(this))
        // 旋转 / 进程重建：把上面这几个实例字段接回来。存 Item.id 不存下标 —— 项数、顺序随版本变，
        // 用 id 显式对齐 PermissionFlow.ORDER 里的真实项，认不出的 id 自然丢掉（不会还原成错的项）。
        savedInstanceState?.let { s ->
            running = s.getBoolean(STATE_RUNNING, false)
            waitingFor = s.getString(STATE_WAITING)?.let { id -> PermissionFlow.ORDER.firstOrNull { it.id == id } }
            // 只有「当时正等某一项系统页」才续自动流程；停在两项之间时没有可续的目标，
            // 硬续会无目标自动弹页（旋转后重复打开系统页），所以收掉自动态。
            if (waitingFor == null) running = false
            s.getStringArrayList(STATE_SKIPPED)?.let { skipped.addAll(it) }
            s.getStringArrayList(STATE_EXPANDED)?.let { expanded.addAll(it) }
            expandedSeeded = s.getBoolean(STATE_SEEDED, false)
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(root) }
        // 内容加横向边距 + 上呼吸空间
        root.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(this@PermissionActivity, 16f), Ui.dp(this@PermissionActivity, 4f), Ui.dp(this@PermissionActivity, 16f), Ui.dp(this@PermissionActivity, 24f))
        }
        setContentView(sv)
        Brut.page(this, sv)
        render()
    }

    /** 旋转 / 重建前把这几个实例字段存下来（见 [onCreate] 的还原；枚举存 [PermissionFlow.Item.id]） */
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(STATE_RUNNING, running)
        out.putString(STATE_WAITING, waitingFor?.id)
        out.putStringArrayList(STATE_SKIPPED, ArrayList(skipped))
        out.putStringArrayList(STATE_EXPANDED, ArrayList(expanded))
        out.putBoolean(STATE_SEEDED, expandedSeeded)
    }

    /** 从系统页回来：重采状态，接着推进（或把没开的记成「先不开」再推进） */
    override fun onResume() {
        super.onResume()
        // 无障碍「已授权 / 已连接」会随系统开关变化 —— 回前台开始盯着，onPause 关掉
        startA11yWatch()
        if (!::root.isInitialized) return
        val w = waitingFor
        waitingFor = null
        render()
        if (w == null || !running) return
        if (!PermissionFlow.granted(w, state())) {
            // 跳出去了但没拨开关就回来了 —— 不当成失败卡住，记一笔继续下一项（只跳过一次，不会循环）
            skipped.add(w.id)
            render()
        }
        root.postDelayed({ if (running) advance() }, 220)
    }

    override fun onPause() {
        super.onPause()
        stopA11yWatch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RUNTIME) return
        // 被拒的（含「不再询问」）一律记成先不开，否则下一趟又会 requestPermissions，系统立刻回拒 → 死循环
        val st = state()
        for (it in PermissionFlow.AUTO) {
            if (it.how == PermissionFlow.How.RUNTIME && PermissionFlow.applicable(it, st) && !PermissionFlow.granted(it, st)) skipped.add(it.id)
        }
        render()
        if (running) root.postDelayed({ advance() }, 220)
    }

    // ---------------- 状态 ----------------

    private fun state() = KeepAlive.flowState(this, skipped.toSet())

    /** 回前台开始盯着无障碍授权 / 连接状态；系统开关或连接一变就重画清单（连接变化不打断自动推进的判断，只更新显示） */
    private fun startA11yWatch() {
        if (a11yWatch != null) return
        a11yWatch = try {
            KeepAlive.observeA11y(this) { if (::root.isInitialized) render() }
        } catch (_: Exception) { null }
    }

    private fun stopA11yWatch() {
        try { a11yWatch?.close() } catch (_: Exception) {}
        a11yWatch = null
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val st = state()
        // 首次进入：只把「还没开到的必需项」详情默认摊开 —— 先完成必要配置
        if (!expandedSeeded) {
            expandedSeeded = true
            for (it in PermissionFlow.visible(st)) if (it.required && !PermissionFlow.granted(it, st)) expanded.add(it.id)
        }
        root.removeAllViews()

        root.addView(TextView(this).apply { text = "使用权限"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "装完要给几项系统权限。必须开的只有「悬浮窗」一项，其余都是可选的。"
            textSize = 14f; setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8f), 0, dp(4f))
        })

        // 摘要：当前值（几个已就绪）+ 待办
        root.addView(TextView(this).apply {
            text = PermissionFlow.summary(st); textSize = 13.5f
            setPadding(0, dp(4f), 0, dp(2f))
        })

        // 一键配置：按 [PermissionFlow.next] 只挑「缺的」项，一页页递过去；已开过的不再弹
        val next = PermissionFlow.next(st)
        val disconnected = PermissionFlow.a11yAuthorizedNotConnected(st)
        root.addView(Button(this).apply {
            text = when {
                running && next != null -> "正在配置：${plainName(next)}…"
                next != null -> "一键配置（点一下，剩下的我带你去）"
                PermissionFlow.blocked(st).isNotEmpty() -> "再去打开「悬浮窗」（必需）"
                disconnected -> "已完成 · 去系统开关确认无障碍"
                else -> "都配好了 · 再检查一遍"
            }
            isSingleLine = false
            tag = Brut.PRIMARY
            setOnClickListener {
                val s = state()
                val blk = PermissionFlow.blocked(s)
                when {
                    // 必需项还差（悬浮窗被先不开）：单独把它再递到用户面前
                    PermissionFlow.ready(s) && blk.isNotEmpty() -> { skipped.removeAll(blk.map { it.id }.toSet()); goOne(blk.first()) }
                    // 都配好了但无障碍「已授权 · 服务未连接」：给个能点的入口去看系统开关，不锁在等待里
                    PermissionFlow.ready(s) && PermissionFlow.a11yAuthorizedNotConnected(s) -> goOne(PermissionFlow.Item.CAPTURE)
                    // 全过了：再按就是「重采 + 报个数」，不重复弹框打扰
                    PermissionFlow.ready(s) -> { skipped.clear(); render(); toast("都开好了") }
                    else -> { running = true; advance() }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = PermissionFlow.finishLine(st); textSize = 13f; alpha = 0.85f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8f), 0, 0)
        })

        // 总说明之外，原理收进折叠（点开才展开）
        val gRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, dp(10f), 0, dp(2f))
        }
        gRow.addView(TextView(this).apply {
            text = "操作说明：哪些能自动、哪些要你自己拨开关"
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        gRow.addView(TextView(this).apply {
            text = if (howToOpen) "收起 ▴" else "点开 ▾"; textSize = 12.5f; alpha = 0.8f
            setPadding(dp(10f), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
        gRow.setOnClickListener { howToOpen = !howToOpen; render() }
        root.addView(gRow)
        if (howToOpen) tip(GENERAL_HOWTO)

        val items = PermissionFlow.visible(st)
        section("必需", st, items.filter { it.required })
        section("可选", st, items.filter { !it.required })

        if (skipped.isNotEmpty()) {
            root.addView(Button(this).apply {
                text = "把所有「先不开」的恢复（重走一遍）"
                isSingleLine = false
                setOnClickListener { skipped.clear(); render() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        }
        root.addView(Button(this).apply {
            text = "返回设置页"; isSingleLine = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        Brut.style(root)
    }

    /** 一个分区：标题 + 一个卡片容器，里面是若干紧凑行（不再一项一张大卡） */
    private fun section(title: String, st: PermissionFlow.State, items: List<PermissionFlow.Item>) {
        if (items.isEmpty()) return
        root.addView(Brut.heading(this, "$title（${items.size} 项）"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, item) in items.withIndex()) {
            box.addView(itemRow(item, st))
            if (i != items.lastIndex) box.addView(View(this).apply { setBackgroundColor(Brut.MUTED) }, LinearLayout.LayoutParams(-1, dp(1f)))
        }
        Brut.card(box)
        root.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    /** 一项一行：符号 + 白话名 + 详情开关 / 状态短句 / 一个按钮；详情折叠（用途、系统名、怎么做、先不开、受限设置入口） */
    private fun itemRow(item: PermissionFlow.Item, st: PermissionFlow.State): View {
        val open = item.id in expanded
        val disconnected = item == PermissionFlow.Item.CAPTURE && PermissionFlow.a11yAuthorizedNotConnected(st)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }

        // 抬头：点它才展开/收起详情（正文区不设点击，不外冒把行收回）
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        head.addView(TextView(this).apply {
            text = mark(item, st); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8f), 0)
        }, LinearLayout.LayoutParams(-2, -2))
        head.addView(TextView(this).apply {
            text = plainName(item); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = if (open) "收起 ▴" else "详情 ▾"; textSize = 12.5f; alpha = 0.8f
        }, LinearLayout.LayoutParams(-2, -2))
        head.setOnClickListener { toggleDetail(item.id) }
        row.addView(head, LinearLayout.LayoutParams(-1, -2))

        // 状态短句
        row.addView(TextView(this).apply {
            text = statusLine(item, st); textSize = 13f
            setLineSpacing(0f, 1.3f)
            setPadding(dp(23f), dp(4f), 0, 0)
        })

        // 一个主操作按钮：断连时不禁用「已开启」，而是「查看系统开关」；已开启的也能点进去管理
        row.addView(Button(this).apply {
            text = actionLabel(item, st)
            isSingleLine = false
            if (disconnected) tag = Brut.PRIMARY      // 「服务未连接」时显眼、可点
            setOnClickListener { goOne(item) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })

        if (open) {
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            // 白话名在标题，技术名放详情
            detail.addView(TextView(this).apply {
                text = "系统里叫：${item.title}"; textSize = 12.5f; alpha = 0.85f
                setLineSpacing(0f, 1.3f)
            })
            // 一句用途
            detail.addView(TextView(this).apply {
                text = purpose(item); textSize = 12.5f; alpha = 0.85f
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(4f), 0, 0)
            })
            // 怎么做（PermissionFlow.howTo 原文）
            detail.addView(TextView(this).apply {
                text = PermissionFlow.howTo(item); textSize = 12.5f; alpha = 0.85f
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(4f), 0, 0)
            })
            // 先不开 / 恢复这一项（保留手动跳过语义；必需项也能点，但它始终算「没开」）
            detail.addView(TextView(this).apply {
                text = if (item.id in st.skipped) "· 恢复这一项（重新走一遍）" else "· 先不开这一项"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10f), 0, 0)
                setOnClickListener {
                    if (item.id in skipped) skipped.remove(item.id) else skipped.add(item.id)
                    render()
                }
            })
            // 「允许受限制的设置」的应用信息入口：只在无障碍详情里出现，不铺到清单上
            if (item == PermissionFlow.Item.CAPTURE && Build.VERSION.SDK_INT >= 33) {
                detail.addView(TextView(this).apply {
                    text = "· 无障碍开关是灰的 / 点不开？点这里去应用信息解锁「允许受限制的设置」"
                    textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10f), 0, 0)
                    setOnClickListener { appInfo() }
                })
            }
            row.addView(detail, LinearLayout.LayoutParams(-1, -2))
        }

        return row
    }

    /** 状态符号（灰度屏靠符号）：✓ 已开 / ◇ 已授权未连接 / — 先不开 / ▸ 下一个 / ✗ 还没开 */
    private fun mark(item: PermissionFlow.Item, st: PermissionFlow.State): String = when {
        item == PermissionFlow.Item.CAPTURE && PermissionFlow.a11yAuthorizedNotConnected(st) -> "◇"
        PermissionFlow.granted(item, st) -> "✓"
        item.id in st.skipped -> "—"
        PermissionFlow.next(st) == item -> "▸"
        else -> "✗"
    }

    /** 白话名（标题行）：技术名放详情里 */
    private fun plainName(i: PermissionFlow.Item) = when (i) {
        PermissionFlow.Item.NOTIFY -> "提醒能弹出通知"
        PermissionFlow.Item.BATTERY -> "允许后台运行（减少被清理）"
        PermissionFlow.Item.OVERLAY -> "让小球浮在别的应用上面"
        PermissionFlow.Item.CAPTURE -> "识别屏幕时不再弹录屏框"
        PermissionFlow.Item.EXACT -> "提醒到点能准点响"
    }

    /** 一句用途（短说明） */
    private fun purpose(i: PermissionFlow.Item) = when (i) {
        PermissionFlow.Item.NOTIFY -> "待办、复习到点了能弹出来提醒你。"
        PermissionFlow.Item.BATTERY -> "允许后减少后台被系统清理、更不容易被清掉，但不保证系统一定不杀它。"
        PermissionFlow.Item.OVERLAY -> "不开这一项，小球根本显示不出来 —— 这是唯一必须开的一项。"
        PermissionFlow.Item.CAPTURE -> "不开也能用，只是每次识别屏幕会多弹一个录屏确认框。"
        PermissionFlow.Item.EXACT -> "不开的话，提醒可能晚几分钟到几十分钟才响。"
    }

    /** 状态行：已开启 / 已授权但未连接 / 未开启，并写明必需还是可选 */
    private fun statusLine(item: PermissionFlow.Item, st: PermissionFlow.State): String {
        val tag = if (item.required) "必需" else "可选"
        return when {
            item == PermissionFlow.Item.CAPTURE && PermissionFlow.a11yAuthorizedNotConnected(st) ->
                "◇ 已授权 · 服务未连接 —— 系统通常会自己重连；一直连不上就点下面的「查看系统开关」确认它是开着的 · $tag"
            PermissionFlow.granted(item, st) -> "✓ 已开启 · $tag"
            item.required -> "— 未开启 · $tag（必须开，先不开也算没开）"
            item.id in st.skipped -> "— 未开启 · $tag（你选了先不开，随时能回来补）"
            else -> "· 未开启 · $tag（可跳过）"
        }
    }

    /** 按钮文案：断连 → 查看系统开关；已开 → 可管理；没开 → 去开启 */
    private fun actionLabel(item: PermissionFlow.Item, st: PermissionFlow.State): String = when {
        item == PermissionFlow.Item.CAPTURE && PermissionFlow.a11yAuthorizedNotConnected(st) -> "查看系统开关"
        PermissionFlow.granted(item, st) -> "已开启 · 点此可管理"
        item.required -> "去开启（必需）"
        else -> "去开启"
    }

    private fun toggleDetail(id: String) {
        if (id in expanded) expanded.remove(id) else expanded.add(id)
        render()
    }

    // v1.85.13：说明文案走 Md 渲染 —— 文案里写了 **粗体**，裸 TextView 原样露出星号（UX-6）
    private fun tip(s: String) = root.addView(TextView(this).apply {
        text = Md.render(s, codeBg = 0x148A817B.toInt()); textSize = 12.5f; alpha = 0.78f
        setPadding(0, dp(6f), 0, 0); setLineSpacing(0f, 1.3f)
    })

    // ---------------- 推进 ----------------

    /** 单独开某一项（不自动往下走）。已开过的项也能点（去系统里管理 / 查看开关） */
    private fun goOne(item: PermissionFlow.Item) {
        skipped.remove(item.id)
        running = false
        waitingFor = item
        if (!launch(item)) {
            waitingFor = null
            toast("这台手机打不开这一页 —— 手动去 设置 → 应用 → ${Preset.appName} 里找")
        }
        render()
    }

    /** 自动推进：按 [PermissionFlow.next] 一项项来（只挑缺的），全过完就停 */
    private fun advance() {
        val st = state()
        val next = PermissionFlow.next(st)
        if (next == null) {
            running = false
            render()
            toast(if (PermissionFlow.blocked(st).isEmpty()) "该配的都配过了" else "还差「悬浮窗」")
            return
        }
        if (next.how == PermissionFlow.How.RUNTIME) {
            val batch = PermissionFlow.runtimeBatch(st)
            val perms = batch.flatMap { permissionsOf(it) }.distinct().toTypedArray()
            if (perms.isEmpty()) { skipped.add(next.id); advance(); return }   // 理论上不会（runtimeBatch 非空）
            waitingFor = null
            render()
            requestPermissions(perms, REQ_RUNTIME)
            return
        }
        waitingFor = next
        render()
        if (!launch(next)) {
            waitingFor = null
            skipped.add(next.id)     // 打不开就跳过，别卡在这一项
            render()
            root.postDelayed({ advance() }, 120)
        }
    }

    /** 这一项对应的运行时权限（能一次要完就一次要完） */
    private fun permissionsOf(item: PermissionFlow.Item): List<String> = when (item) {
        PermissionFlow.Item.NOTIFY -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        else -> emptyList()
    }

    /** 真去开：跳系统页 / 弹系统框。返回 false = 这台手机没这个入口 */
    private fun launch(item: PermissionFlow.Item): Boolean = when (item) {
        PermissionFlow.Item.NOTIFY -> {
            // 已授权：去系统通知设置里管理，不再 requestPermissions（已授权还去请求本身就是错的）。
            // 未授权才走原来的 request 路径；[advance] 自动流程只挑缺项，不会命中「已授权」这一支。
            if (PermissionFlow.granted(item, state())) { KeepAlive.openNotifications(this); true }
            else {
                // 运行时权限不走这里（[advance] 直接 requestPermissions）；单独点这一项时也走批量那条路
                val perms = permissionsOf(item)
                if (perms.isEmpty()) { KeepAlive.openNotifications(this); true }
                else { requestPermissions(perms.toTypedArray(), REQ_RUNTIME); true }
            }
        }
        PermissionFlow.Item.BATTERY -> if (PermissionFlow.granted(item, state())) {
            // 已授权：去系统「电池优化」设置入口管理，不再弹「请求忽略电池优化」框
            tryGo(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) || appInfo()
        } else try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            catch (_: Exception) { false }
        }
        PermissionFlow.Item.OVERLAY -> try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { appInfo() }
        PermissionFlow.Item.CAPTURE -> {
            // 先试「直达自己的无障碍开关页」，不行再开无障碍列表
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, A11yService::class.java).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryGo(detail)) true
            else tryGo(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        PermissionFlow.Item.EXACT -> tryGo(RemindExact.settingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun tryGo(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }
    private fun appInfo(): Boolean = tryGo(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_RUNTIME = 4471   // 别和 MainActivity 的录屏 7 / 导入 4712 / 通知 8 撞
        // 旋转 / 重建用的状态键（[onSaveInstanceState] / [onCreate] 成对）
        private const val STATE_RUNNING = "perm.running"
        private const val STATE_WAITING = "perm.waiting"
        private const val STATE_SKIPPED = "perm.skipped"
        private const val STATE_EXPANDED = "perm.expanded"
        private const val STATE_SEEDED = "perm.seeded"
        fun intent(ctx: android.content.Context) = Intent(ctx, PermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 总说明之下的原理（原文保留）：能自动的自动，不能自授的系统页按顺序递到手边；精确闹钟不进一键流程 */
        private const val GENERAL_HOWTO = "能自动的都自动：通知和后台运行是系统弹一个框、你点「允许」就完事。" +
            "悬浮窗、无障碍这两样，Android 从 6.0 起就不许 App 自己授予 —— " +
            "所以由这一页**按顺序把系统页面一页页递到你面前**，你只要拨开关再按返回，回来自动接着下一页，" +
            "不用自己在设置里翻菜单。已经开过的项不会再弹。精确闹钟不影响启动，想更准点就在「可选」里单独去开它。" +
            "哪一页不想开，直接按返回就行，它会跳过继续下一项。"
    }
}
