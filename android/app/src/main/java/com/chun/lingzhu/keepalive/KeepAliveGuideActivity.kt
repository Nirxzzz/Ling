package com.chun.lingzhu.keepalive

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chun.lingzhu.A11yService
import com.chun.lingzhu.RemindExact
import com.chun.lingzhu.Brut
import com.chun.lingzhu.Theme
import com.chun.lingzhu.Ui
import com.chun.lingzhu.Prefs
import com.chun.lingzhu.keepalive.KeepAliveGuide.Item
import com.chun.lingzhu.keepalive.KeepAliveGuide.Status

/**
 * 「权限体检 / 保活向导」：一屏列出各项状态（绿 = 已开启，红 = 未开启，橙 = 需手动确认）+ 每项「去开启」。
 * onResume 重采重画。判断与厂商候选页全在 [KeepAliveGuide]（纯逻辑）；这里只采状态、画界面、跳页面。
 * 所有 startActivity 都包 try/catch，跳不动就回退，绝不崩。
 */
class KeepAliveGuideActivity : Activity() {
    private lateinit var root: LinearLayout
    private val vendor by lazy { KeepAliveGuide.vendorOf(Build.MANUFACTURER, Build.BRAND) }

    private fun dp(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { Ui.applyTheme(Prefs(this)) } catch (_: Exception) {}   // v2.7.3：theme+shape 必须一起装，不再单独写 Ui.theme
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(root) }
        root.layoutParams = FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(16f), dp(4f), dp(16f), dp(24f)) }
        setContentView(sv)
        try { Brut.page(this, sv) } catch (_: Exception) {}
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
    }

    // ---------------- 采状态 ----------------

    private fun snapshot(): KeepAliveGuide.Snapshot {
        val sdk = Build.VERSION.SDK_INT
        return KeepAliveGuide.Snapshot(
            sdk = sdk,
            overlay = safe { Settings.canDrawOverlays(this) },
            notifications = safe { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled() },
            batteryIgnored = safe { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            exactAlarm = RemindExact.exactGranted(this),   // 与权限页 / 提醒链同一口径（31 之前恒 true）
            // 本 App 的无障碍服务只在 Android 11+ 启用（与 A11yService.supported 一致）
            a11yPresent = sdk >= Build.VERSION_CODES.R,
            a11yOn = safe { a11yEnabled() },
        )
    }

    /** 系统无障碍列表里有没有本 App 的服务（已授权口径，不看是否已连接）—— 统一走 [A11yService.enabledInSystem]，与权限页同源 */
    private fun a11yEnabled(): Boolean = A11yService.enabledInSystem(this)

    private inline fun safe(f: () -> Boolean): Boolean = try { f() } catch (_: Exception) { false }

    // ---------------- 画界面 ----------------

    private fun render() {
        root.removeAllViews()
        val rows = KeepAliveGuide.rows(snapshot())
        val sum = KeepAliveGuide.summarize(rows)

        root.addView(TextView(this).apply {
            text = "权限体检 · 保活向导"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12f), 0, dp(4f))
        })
        root.addView(TextView(this).apply {
            tag = Brut.KEEP
            text = sum.text(); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(stateInk(ON, if (sum.allDetectableOn) GREEN else RED))
            setPadding(0, dp(2f), 0, dp(8f))
        })
        root.addView(TextView(this).apply {
            text = "灵珠要常驻后台（悬浮球、提醒、看门狗），下面每项都开好才不容易被系统杀掉。从设置页回来会自动刷新。"
            textSize = 13f; setLineSpacing(0f, 1.3f); alpha = 0.85f
        })

        root.addView(Brut.heading(this, "体检清单（${rows.size} 项）"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, r) in rows.withIndex()) {
            box.addView(rowView(r))
            if (i != rows.lastIndex) box.addView(View(this).apply { setBackgroundColor(Brut.MUTED) }, LinearLayout.LayoutParams(-1, dp(1f)))
        }
        Brut.card(box)
        root.addView(box, LinearLayout.LayoutParams(-1, -2))

        // 厂商图文说明
        root.addView(Brut.heading(this, "本机：${vendor.label}"))
        val guide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8f), 0, dp(8f)) }
        guide.addView(TextView(this).apply {
            text = "📌 " + KeepAliveGuide.tip(vendor); textSize = 13.5f; setLineSpacing(0f, 1.35f)
        })
        guide.addView(TextView(this).apply {
            text = "  ┌────────────┐\n  │  最近任务   │\n  │ ┌────────┐ │\n  │ │  灵珠 🔒 │ │ ← 下拉 / 长按卡片锁定\n  │ └────────┘ │\n  └────────────┘"
            textSize = 12f; typeface = Typeface.MONOSPACE; alpha = 0.8f
            setPadding(0, dp(8f), 0, 0)
        })
        Brut.card(guide)
        root.addView(guide, LinearLayout.LayoutParams(-1, -2))

        root.addView(Button(this).apply {
            text = "返回"; isSingleLine = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })

        Brut.style(root)
    }

    private fun rowView(r: KeepAliveGuide.Row): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10f), 0, dp(10f)) }
        val head = Ui.row(this)
        head.addView(TextView(this).apply {
            text = r.item.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            tag = Brut.KEEP
            textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
            when (r.status) {
                Status.ON -> { text = "● 已开启"; setTextColor(stateInk(ON, GREEN)) }
                Status.OFF -> { text = "● 未开启"; setTextColor(stateInk(OFF, RED)) }
                Status.MANUAL -> { text = "● 需手动确认"; setTextColor(stateInk(MANUAL, ORANGE)) }
            }
        }, LinearLayout.LayoutParams(-2, -2))
        row.addView(head, LinearLayout.LayoutParams(-1, -2))
        row.addView(TextView(this).apply {
            text = if (r.note.isEmpty()) r.item.desc else "${r.item.desc}（${r.note}）"
            textSize = 12.5f; alpha = 0.85f; setLineSpacing(0f, 1.3f)
            setPadding(0, dp(4f), 0, 0)
        })
        // 12 以下没有精确闹钟授权页，不给按钮；其余项已开启时也能点进去管理
        if (r.item != Item.EXACT_ALARM || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            row.addView(Button(this).apply {
                text = when (r.status) { Status.ON -> "查看设置"; Status.OFF -> "去开启"; Status.MANUAL -> "去设置自启动 / 后台运行" }
                isSingleLine = false
                if (r.status == Status.OFF) tag = Brut.PRIMARY
                setOnClickListener { open(r.item) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        }
        return row
    }

    // ---------------- 跳页面（全部兜底） ----------------

    private fun pkgUri() = Uri.parse("package:$packageName")

    private fun open(item: Item) {
        val ok = when (item) {
            Item.OVERLAY -> tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri()))
            Item.NOTIFICATION -> tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            Item.BATTERY -> {
                val ignored = safe { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }
                (!ignored && tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()))) ||
                    tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Item.EXACT_ALARM -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()))
            Item.ACCESSIBILITY -> tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Item.VENDOR_AUTOSTART -> KeepAliveGuide.autostartTargets(vendor).any {
                tryStart(Intent().setComponent(ComponentName(it.pkg, it.cls)))
            }
        }
        if (!ok) {
            val fallback = tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri()))
            toast(if (fallback) "没找到专门的设置页，已打开应用详情页，请按说明手动开启" else "无法打开系统设置，请手动前往：设置 → 应用 → 灵珠")
        }
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (_: ActivityNotFoundException) { false
    } catch (_: SecurityException) { false
    } catch (_: Exception) { false }

    private fun toast(s: String) = try { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } catch (_: Exception) {}

    companion object {
        private const val GREEN = 0xFF2E7D32.toInt()
        private const val RED = 0xFFC62828.toInt()
        private const val ORANGE = 0xFFE65100.toInt()
        // 玻璃状态槽位（见 stateInk）：开=主色、关=蜜桃珊瑚、需确认=提示色
        private const val ON = 0; private const val OFF = 1; private const val MANUAL = 2

        /** 玻璃配色的状态色：从色板角色压深出来（浅色主色直接当状态字会糊）；其余配色用固定绿/红/橙 */
        fun stateInk(slot: Int, legacy: Int): Int {
            val pal = Ui.theme.palette ?: return legacy
            if (!Ui.theme.isGlassMaterial) return legacy
            return when (slot) {
                ON -> Theme.argb(0xFF, Theme.mix(pal.primary, pal.ink, 0.45f))
                OFF -> Theme.argb(0xFF, Theme.mix(pal.coral, pal.ink, 0.55f))
                else -> Theme.argb(0xFF, pal.hint)
            }
        }

        fun start(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, KeepAliveGuideActivity::class.java).apply {
                    if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }
}
