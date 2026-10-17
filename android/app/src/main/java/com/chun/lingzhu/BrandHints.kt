package com.chun.lingzhu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 各家 ROM 的「后台活下来」设置入口总表（v1.70）。
 *
 * 为什么要这张表：国产 ROM 各自把「自启动 / 后台运行 / 省电策略」藏在自家管家里，
 * 同一个意思有五六个名字（自启动、后台启动、关联启动、后台高耗电、耗电保护、智能耗电管理），
 * 页面类名还随大版本改过。代码层（前台服务 + STICKY + 无障碍守护 + 闹钟看门狗）已经到顶，
 * 剩下的全在「把用户送到正确的那个页面」这一件事上。
 *
 * 用法只有两类，不要在别处再抄一份：
 *  - 想跳页面的：openAutostart(ctx) / openBatteryWhitelist(ctx) / KeepAlive 里那套系统 Intent；
 *  - 跳不动、只能靠文字的：overlayNav(v) / batteryNav(v) / a11yNav(v) 给出用户能照着点的导航文案。
 *
 * 设计约束：**品牌识别和导航文案是纯逻辑，不碰任何 android 类**，这样 JVM 单元测试能直接跑；
 * 只有 openAutostart / openBatteryWhitelist 这两个「真的 startActivity」的函数碰 Context。
 */
object BrandHints {

    /** ROM 家族。同一家族的不同品牌（红米 / 荣耀 / 一加 / realme / iQOO）行为一致，合并成一档。 */
    enum class Vendor(val label: String) {
        XIAOMI("小米 / 红米"),
        HUAWEI("华为"),
        HONOR("荣耀"),
        OPPO("OPPO"),
        ONEPLUS("一加"),
        REALME("realme"),
        VIVO("vivo"),
        IQOO("iQOO"),
        SAMSUNG("三星"),
        MEIZU("魅族"),
        GOOGLE("原生 / Google"),
        OTHER("其它"),
        ;

        companion object {
            fun of(name: String): Vendor = when (name) {
                XIAOMI.label, "小米", "红米", "Redmi", "Xiaomi" -> XIAOMI
                HUAWEI.label, "华为", "Huawei" -> HUAWEI
                HONOR.label, "荣耀", "Honor" -> HONOR
                OPPO.label, "OPPO", "Oppo" -> OPPO
                ONEPLUS.label, "一加", "OnePlus" -> ONEPLUS
                REALME.label, "realme" -> REALME
                VIVO.label, "vivo" -> VIVO
                IQOO.label, "iQOO" -> IQOO
                SAMSUNG.label, "三星", "Samsung" -> SAMSUNG
                MEIZU.label, "魅族", "Meizu" -> MEIZU
                GOOGLE.label, "Google", "Pixel", "原生" -> GOOGLE
                else -> OTHER
            }
        }
    }

    /** 厂商设置页的一个组件（包名 + 类名）。构造得出就构造 Intent，构造不出就只留文案。 */
    data class Target(val pkg: String, val cls: String)

    /**
     * 通知渠道的默认行为 —— 每家对第三方应用首次建渠道的处理不一样，
     * 直接决定「前台服务通知能不能被用户看见」（看不见 = 没法从通知里退出/翻译，用户会以为死了）。
     */
    enum class NotifyDefault { VISIBLE, SILENT, BLOCKED }

    /** 无障碍服务被清掉后系统的行为 —— 决定第三层保活（无障碍守护）还在不在。 */
    enum class A11yKill {
        /** 进程被杀后系统会重新绑定服务，守护能自己回来（最理想） */
        REBIND,

        /** 只停服务不杀进程，或重绑很慢，退化成靠别层兜底 */
        SLOW,

        /** 连无障碍一起清且不重绑，第三层直接塌掉 */
        AGGRESSIVE,
    }

    /** 一家 ROM 的完整画像。全部是数据，方便测试断言，也方便以后按版本再细分。 */
    data class Spec(
        val vendor: Vendor,
        /** 自启动 / 后台管理页的组件候选，按「最可能对」排序，第一个能 startActivity 的就用它 */
        val autostart: List<Target>,
        /** 电池优化白名单的厂商页组件候选；空 = 只能走系统那个一步确认弹框 */
        val battery: List<Target>,
        /** 这个牌子有没有自家管家里能直达的页面。没有 = 只能落到系统页 + 文字导航 */
        val vendorPage: Boolean,
        val notify: NotifyDefault,
        val a11yKill: A11yKill,
        /** 悬浮窗设置的文字导航（各家叫法不同：显示悬浮窗 / 界面显示权限 / 悬浮窗管理） */
        val overlayNav: String,
        /** 电池 / 省电策略设置的文字导航 */
        val batteryNav: String,
        /** 厂商后台管理的文字导航（跳不动时用这句告诉用户去哪找） */
        val autostartNav: String,
    )

    // ---- 组件表 ----------------------------------------------------------------
    // 构造得出来的都在这儿。改版本了先在这里加一条新的，老的留着（很多老机型还在用）。

    private val MIUI_AUTOSTART = Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    private val MIUI_POWER = Target("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")            // 省电策略（老名字叫「隐藏应用」）
    private val MIUI_NOTIFY = Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

    private val HW_AUTOSTART = Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    private val HW_PROTECT = Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
    private val HONOR_AUTOSTART = Target("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
    private val HONOR_PROTECT = Target("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")

    private val OP_AUTOSTART = Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
    private val OP_AUTOSTART_ALT = Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
    private val OP_BACKGROUND = Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
    private val OPPLUS_AUTOSTART = Target("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity")
    private val OPPLUS_BACKGROUND = Target("com.oplus.safecenter", "com.oplus.safecenter.background.manage.BackgroundActivity")

    private val VIVO_AUTOSTART = Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
    private val IQOO_AUTOSTART = Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")

    private val MEIZU_AUTOSTART = Target("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")

    // ---- 各家画像 --------------------------------------------------------------

    private val SPECS: Map<Vendor, Spec> = mapOf(
        Vendor.XIAOMI to Spec(
            Vendor.XIAOMI, listOf(MIUI_AUTOSTART), listOf(MIUI_POWER, MIUI_NOTIFY), true,
            NotifyDefault.VISIBLE, A11yKill.SLOW,
            overlayNav = "设置 → 应用设置 → 应用管理 → ${Preset.appName} → 权限管理 → 显示悬浮窗（老系统叫「界面显示权限」）",
            batteryNav = "设置 → 应用设置 → 应用管理 → ${Preset.appName} → 省电策略 → 选「无限制」",
            autostartNav = "设置 → 应用设置 → 应用管理 → ${Preset.appName} → 自启动 → 打开",
        ),
        Vendor.HUAWEI to Spec(
            Vendor.HUAWEI, listOf(HW_AUTOSTART), listOf(HW_PROTECT), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用和权限 → 应用 → ${Preset.appName} → 权限 → 悬浮窗",
            batteryNav = "设置 → 电池 → 应用启动管理 → ${Preset.appName} → 手动管理 → 关掉「自动管理」",
            autostartNav = "设置 → 电池 → 应用启动管理 → ${Preset.appName} → 手动管理，三个开关（自启动/关联启动/后台活动）全开",
        ),
        Vendor.HONOR to Spec(
            Vendor.HONOR, listOf(HONOR_AUTOSTART), listOf(HONOR_PROTECT), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用和权限 → 应用 → ${Preset.appName} → 权限 → 悬浮窗",
            batteryNav = "设置 → 电池 → 应用启动管理 → ${Preset.appName} → 手动管理 → 关掉「自动管理」",
            autostartNav = "设置 → 电池 → 应用启动管理 → ${Preset.appName} → 手动管理，三个开关全开",
        ),
        Vendor.OPPO to Spec(
            Vendor.OPPO, listOf(OP_AUTOSTART, OP_AUTOSTART_ALT), listOf(OP_BACKGROUND), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用管理 → ${Preset.appName} → 权限管理 → 悬浮窗（有的版本叫「后台弹出界面」）",
            batteryNav = "设置 → 电池 → 耗电管理 → ${Preset.appName} → 允许后台运行",
            autostartNav = "设置 → 应用管理 → 自启动管理 → ${Preset.appName} → 允许自启动",
        ),
        Vendor.ONEPLUS to Spec(
            Vendor.ONEPLUS, listOf(OPPLUS_AUTOSTART), listOf(OPPLUS_BACKGROUND), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用管理 → ${Preset.appName} → 权限管理 → 悬浮窗",
            batteryNav = "设置 → 电池 → 耗电管理 → ${Preset.appName} → 允许后台运行",
            autostartNav = "设置 → 应用管理 → 自启动管理 → ${Preset.appName} → 允许自启动",
        ),
        Vendor.REALME to Spec(
            Vendor.REALME, listOf(OP_AUTOSTART, OP_AUTOSTART_ALT), listOf(OP_BACKGROUND), true,
            NotifyDefault.VISIBLE, A11yKill.SLOW,
            overlayNav = "设置 → 应用管理 → ${Preset.appName} → 权限管理 → 悬浮窗",
            batteryNav = "设置 → 电池 → 电池管理 → ${Preset.appName} → 允许后台运行",
            autostartNav = "设置 → 应用管理 → 自启动管理 → ${Preset.appName} → 允许自启动",
        ),
        Vendor.VIVO to Spec(
            Vendor.VIVO, listOf(VIVO_AUTOSTART), emptyList(), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用与权限 → 权限管理 → ${Preset.appName} → 悬浮窗",
            batteryNav = "设置 → 电池 → 后台耗电管理 → ${Preset.appName} → 允许后台高耗电",
            autostartNav = "设置 → 应用与权限 → 权限管理 → ${Preset.appName} → 自启动 → 允许",
        ),
        Vendor.IQOO to Spec(
            Vendor.IQOO, listOf(IQOO_AUTOSTART, VIVO_AUTOSTART), emptyList(), true,
            NotifyDefault.VISIBLE, A11yKill.AGGRESSIVE,
            overlayNav = "设置 → 应用与权限 → 权限管理 → ${Preset.appName} → 悬浮窗",
            batteryNav = "设置 → 电池 → 后台耗电管理 → ${Preset.appName} → 允许后台高耗电",
            autostartNav = "i 管家 → 应用管理 → 权限管理 → ${Preset.appName} → 自启动 → 允许",
        ),
        Vendor.SAMSUNG to Spec(
            Vendor.SAMSUNG, emptyList(), emptyList(), false,
            NotifyDefault.SILENT, A11yKill.SLOW,
            overlayNav = "设置 → 应用 → ${Preset.appName} → 权限 → 允许显示在其他应用上层",
            batteryNav = "设置 → 电池和设备保养 → 电池 → 后台使用限制 → ${Preset.appName} → 永不睡眠的应用",
            autostartNav = "设置 → 电池 → 后台使用限制 → 「永不休眠的应用」里把 ${Preset.appName} 加进去",
        ),
        Vendor.MEIZU to Spec(
            Vendor.MEIZU, listOf(MEIZU_AUTOSTART), emptyList(), true,
            NotifyDefault.VISIBLE, A11yKill.SLOW,
            overlayNav = "设置 → 应用管理 → ${Preset.appName} → 权限管理 → 悬浮窗",
            batteryNav = "设置 → 电池 → ${Preset.appName} → 允许后台运行",
            autostartNav = "设置 → 应用管理 → ${Preset.appName} → 后台管理 → 允许后台运行",
        ),
        Vendor.GOOGLE to Spec(
            Vendor.GOOGLE, emptyList(), emptyList(), false,
            NotifyDefault.VISIBLE, A11yKill.REBIND,
            overlayNav = "设置 → 应用 → ${Preset.appName} → 权限 → 允许显示在其他应用上层",
            batteryNav = "设置 → 应用 → ${Preset.appName} → 电池 → 不受限制",
            autostartNav = "没有自启动开关这一说 —— 靠「电池 → 将 ${Preset.appName} 设为不受限制」和最近任务锁住 ${Preset.appName} 卡片就够了",
        ),
        Vendor.OTHER to Spec(
            Vendor.OTHER, emptyList(), emptyList(), false,
            NotifyDefault.VISIBLE, A11yKill.SLOW,
            overlayNav = "设置 → 应用 → ${Preset.appName} → 权限 → 显示在其他应用上层（各家叫法不同，往「权限」里找）",
            batteryNav = "设置 → 电池 → ${Preset.appName} → 无限制 / 允许后台运行",
            autostartNav = "设置里搜「自启动」或「后台管理」，把 ${Preset.appName} 放行",
        ),
    )

    // ---- 品牌识别（纯逻辑，测试直接喂字符串）------------------------------------

    /**
     * 认厂商。manufacturer 和 brand 都要看：
     * 红米的 Build.MANUFACTURER 是「Xiaomi」而 BRAND 是「Redmi」，一加是「OnePlus」、realme 是「realme」——
     * 只看 manufacturer 会把一加 / realme 漏到 OTHER 去了。
     */
    fun detect(manufacturer: String, brand: String = ""): Vendor {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        if (m in XIAOMI_KEYS || b in XIAOMI_KEYS) return Vendor.XIAOMI
        if (m in HUAWEI_KEYS) return Vendor.HUAWEI
        if (m in HONOR_KEYS) return Vendor.HONOR
        if (m in ONEPLUS_KEYS) return Vendor.ONEPLUS
        if (m in REALME_KEYS) return Vendor.REALME
        if (m in OPPO_KEYS) return Vendor.OPPO
        if (m in IQOO_KEYS) return Vendor.IQOO
        if (m in VIVO_KEYS) return Vendor.VIVO
        if (m in SAMSUNG_KEYS) return Vendor.SAMSUNG
        if (m in MEIZU_KEYS) return Vendor.MEIZU
        if (m in GOOGLE_KEYS) return Vendor.GOOGLE
        return Vendor.OTHER
    }

    /** 读真机 Build 值。单独拆出来是为了让 detect(manufacturer, brand) 能被 JVM 测试直接调。 */
    fun detect(): Vendor = detect(Build.MANUFACTURER ?: "", Build.BRAND ?: "")

    private val XIAOMI_KEYS = setOf("xiaomi", "redmi", "poco")
    private val HUAWEI_KEYS = setOf("huawei")
    private val HONOR_KEYS = setOf("honor", "honor device")
    private val OPPO_KEYS = setOf("oppo")
    private val ONEPLUS_KEYS = setOf("oneplus")
    private val REALME_KEYS = setOf("realme")
    private val VIVO_KEYS = setOf("vivo")
    private val IQOO_KEYS = setOf("iqoo")
    private val SAMSUNG_KEYS = setOf("samsung")
    private val MEIZU_KEYS = setOf("meizu")
    private val GOOGLE_KEYS = setOf("google")

    fun spec(v: Vendor): Spec = SPECS.getValue(v)

    /** 这台手机上「后台被杀」这件事有多狠 —— 设置页可以拿它决定要不要多说两句。 */
    fun riskLevel(v: Vendor): String = when (spec(v).a11yKill) {
        A11yKill.AGGRESSIVE -> "高（这套 ROM 会连无障碍服务一起清）"
        A11yKill.SLOW -> "中（会清进程，但通常会重新拉起）"
        A11yKill.REBIND -> "低（基本只靠系统回收内存）"
    }

    /**
     * 设置页那段「你这台手机要做什么」的提示文案。
     * 顺序有讲究：锁卡片放最前（各品牌通用、最容易漏、代码替代不了），再是这家特有的两三项。
     */
    fun tips(v: Vendor): String {
        val name = Preset.appName
        val lock = "先做这一步（各品牌都一样、最容易漏）：最近任务里按住 $name 的卡片 → 「锁定」—— 锁了卡片，一键清理 / 划掉别的卡才不会带上它（不少手机上不锁就等于「强行停止」，闹钟和自动恢复会全部失效）。\n"
        val s = spec(v)
        val body = buildString {
            append(s.autostartNav)
            append("\n")
            append(s.batteryNav)
            append("\n")
            append("再把 $name 的「省电策略 / 电池」设成无限制，锁卡片就稳了")
            if (s.a11yKill == A11yKill.AGGRESSIVE) append("\n注意：这套系统清理得比较狠，无障碍开关被关掉后保活会少一层，掉线了 $name 会提醒你")
            if (s.notify == NotifyDefault.SILENT) append("\n注意：三星默认把第三方通知设成「静默」，前台通知可能不弹出来，去 电池 → 通知 里设成「常规」")
        }
        return lock + body
    }

    // ---- 下面三个是需要 Context 的部分（单测不覆盖）------------------------------

    /** 逐个试厂商自启动页组件，第一个能打开的就用它；都不行返回 false（调用方去开应用信息页 + 文字导航）。 */
    fun openAutostart(ctx: Context): Boolean {
        for (t in spec(detect()).autostart) if (go(ctx, Intent().setComponent(ComponentName(t.pkg, t.cls)))) return true
        return false
    }

    /** 电池优化白名单：先试厂商自己的「应用保护」页（华为/荣耀这一步比系统弹框管用），不行用系统那个一步确认框。 */
    fun openBatteryWhitelist(ctx: Context): Boolean {
        for (t in spec(detect()).battery) if (go(ctx, Intent().setComponent(ComponentName(t.pkg, t.cls)))) return true
        return go(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
    }

    /** 悬浮窗权限：系统 Intent 是标准件（各家都能用），跳不动才退回应用信息页。 */
    fun openOverlay(ctx: Context): Boolean {
        if (go(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))) return true
        return go(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }

    private fun go(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** 没跳成页时，把该品牌的导航文案念给用户听 */
    fun sayNav(ctx: Context) {
        val s = spec(detect())
        Toast.makeText(ctx, s.autostartNav, Toast.LENGTH_LONG).show()
    }
}