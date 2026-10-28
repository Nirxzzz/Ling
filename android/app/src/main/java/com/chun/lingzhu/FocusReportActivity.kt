package com.chun.lingzhu

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * v2.3.2 专注报告页（第 9 条「专注守护」的出口）。
 *
 * ## 这一页要回答的问题
 * 不是「你有效率吗」，而是三件学习者自己能对上号的事：
 *  1. 今天 / 这周大概学了多久（[FocusReport.studyMs]）；
 *  2. 中间断了几次、最长一次坐了多久（[FocusReport.leaves] / [FocusReport.longestMs]）；
 *  3. 一天里哪个小时最坐得住、哪个小时最容易走开（[FocusReport.busiestStudyHour] / [busiestAwayHour]）。
 *
 * ## 数字从哪来（这一页唯一重要的地方）
 * 全部来自 [FocusReport.Store] 里这台手机自己的采样：小球在跑、专注守护开着时按自己的 12 秒节拍记一条
 * （与自动扫描无关，关掉自动扫描照样采样）。页面**不重算、不估算、不补零**：算数在 [FocusReport.compute]
 * 这里只负责取数据、排版。没有记录就照实说「还没有记录」，绝不显示「今天专注 0 分钟」——
 * 那会让人以为「我学了但 App 没记上」。
 *
 * ## 隐私
 * 落盘的只有四样：时刻、这一屏算了多久、三态标签、画面指纹。
 * 不存文字、不存截图、没有网络请求 —— 页面底部把这句话原样写给用户看，
 * 因为「你怎么知道我离开过」是这类功能最需要先回答的问题。
 *
 * 入口：专注提醒通知（[FocusNotify]）、设置 › 学习与词库 › 专注守护卡里的「专注报告」按钮。
 */
class FocusReportActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val store by lazy { FocusReport.Store(this) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "专注报告"
        Ui.applyTheme(prefs)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@FocusReportActivity, 16f), Ui.dp(this@FocusReportActivity, 12f),
                Ui.dp(this@FocusReportActivity, 16f), Ui.dp(this@FocusReportActivity, 28f))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        Brut.page(this, scroll)
        draw()
        Brut.style(root)
    }

    override fun onResume() {
        super.onResume()
        // 从通知点进来时可能刚学完一轮，回来重画一次（draw 里会重新取数据）
        if (::root.isInitialized) draw()
    }

    private fun draw() {
        root.removeAllViews()
        val now = System.currentTimeMillis()
        val today = store.today(now)
        val week = store.window(now, 7)

        label("专注报告", 24f)
        guardBlock()
        heading("一、今天")
        todayBlock(today)
        heading("二、最近 7 天")
        weekBlock(week)
        heading("三、这些数字是怎么来的")
        methodBlock()
        button("返回") { finish() }
    }

    // ---------------- 一、今天 ----------------

    private fun todayBlock(r: FocusReport.Report) {
        if (!r.hasData) {
            label(FocusReport.noData(), 14f)
            return
        }
        label(FocusReport.dayLine(r), 16f)
        label("按小时看，学得最集中的是 " + (FocusReport.busiestStudyHour(r) ?: "还没有") +
            "；走开最集中的是 " + (FocusReport.busiestAwayHour(r) ?: "没有走开过") + "。", 13f)
        label(r.summary, 13f)
        hourBars(r)
    }

    /**
     * 24 格学习时长条形图。用 TextView 的方块字符拼，不引图表库（理由同 [LearningPanelActivity]）。
     * 每格宽度按「当天最大值」归一 —— 这一页是用来看形状（哪几个小时有东西），不是比绝对值的。
     */
    private fun hourBars(r: FocusReport.Report) {
        val max = r.studyHour.maxOrNull() ?: 0L
        if (max <= 0L) return
        val sb = StringBuilder()
        for (h in 0 until 24) {
            val v = r.studyHour[h]
            val n = if (max <= 0L) 0 else (v * BAR_MAX / max).toInt().coerceIn(0, BAR_MAX)
            // 只画有记录的小时：24 行空条幅比没有图更难看
            if (v <= 0L && h % 6 != 0) continue
            sb.append(FocusReport.hhmm(h * 60).padEnd(5, ' '))
            sb.append("█".repeat(n).padEnd(BAR_MAX, '·'))
            if (v > 0L) sb.append("  ").append(FocusGuard.fmtDur(v))
            sb.append('\n')
        }
        label(sb.toString().trimEnd(), 12f)
    }

    // ---------------- 二、最近 7 天 ----------------

    private fun weekBlock(r: FocusReport.Report) {
        if (!r.hasData) {
            label(focusDaysHint(), 14f)
            return
        }
        label(r.summary, 14f)
        val sb = StringBuilder()
        for (p in r.perDay) {
            sb.append(FocusReport.dayLabel(p.first)).append("　")
            sb.append(if (p.second <= 0L) "没有记录" else FocusGuard.fmtDur(p.second))
            sb.append('\n')
        }
        label(sb.toString().trimEnd(), 13f)
        label("一共 " + r.samples + " 次采样，离开 " + r.leaves + " 次（只统计「走开了又回来」的，没回来就不算）。", 12f)
    }

    // ---------------- 三、口径与隐私 ----------------

    private fun methodBlock() {
        label(
            "· 判定全部在这台手机上做：APP 方式只读前台应用包名，屏幕方式把这一屏缩成灰度图算版面形状" +
                "（文字行数、行是否贴左、有没有大块实心色），没有任何文字或图片会上传，也没有联网请求。\n" +
                "· 小球在运行时按自己的节拍记一条；判不出来（锁屏、纯色页、解码失败、没给授权）时既不算学习也不算离开。\n" +
                "· 学习时长 = 相邻两次「在学习」之间的间隔累加，单段最多算 3 分钟 —— " +
                "手机揣兜里两小时不会被算成学了两小时。\n" +
                "· 落盘内容只有四样：时刻、时长、三态标签、画面指纹。没有正文，没有截图。",
            12f
        )
        val n = store.all().size
        label("本机现有 " + n + " 条记录。", 12f)
        button("清空这份记录") {
            store.clear()
            draw()
        }
    }

    // ---------------- 开关状态 ----------------

    /**
     * 顶部的开关状态。**这里不提供开关本身**：设置项在「设置 › 学习与词库 › 专注守护」，
     * 从报告页改开关会让人误以为「关掉就等于删数据」，两件事必须分开说。
     * 文案只写这条真实导航路径。
     */
    private fun guardBlock() {
        val on = prefs.focusGuardOn
        label(
            if (on) "专注守护已开启：小球在跑的时候按你选的判定方式（指定学习应用 / 屏幕内容）记一段专注，离开久了会温和提醒一句。"
            else "专注守护现在是关着的。打开后才会开始记录；关着的时候这一页不会增加任何数据。",
            13f
        )
        button("去「设置 › 学习与词库 › 专注守护」改开关") {
            try {
                startActivity(
                    android.content.Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SECTION, SettingsRegistry.S_FOCUS)
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "打不开设置页", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun focusDaysHint(): String =
        "最近 7 天还没有记录。" + (if (prefs.focusGuardOn) "" else "（专注守护当前是关着的，见「设置 › 学习与词库 › 专注守护」。）")

    // ---------------- 小工具 ----------------

    private fun label(text: String, size: Float = 14f) = root.addView(TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, Ui.dp(this@FocusReportActivity, 6f), 0, Ui.dp(this@FocusReportActivity, 6f))
    })

    private fun heading(text: String) = root.addView(Brut.heading(this, text))

    private fun button(text: String, action: () -> Unit) = root.addView(android.widget.Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    })

    private companion object {
        /** 条形图最长多少个方块：再多换行就乱了 */
        const val BAR_MAX = 24
    }
}
