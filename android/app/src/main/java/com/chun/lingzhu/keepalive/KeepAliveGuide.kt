package com.chun.lingzhu.keepalive

/**
 * 「权限体检 / 保活向导」的纯逻辑层：厂商识别、候选自启动页列表、状态汇总。
 *
 * 约束：**不碰任何 android 类**（连 ComponentName 都不用，只用字符串），JVM 单测直接跑。
 * 真正 startActivity 的事全在 [KeepAliveGuideActivity] 里做，这里只回答「是什么 / 该去哪」。
 */
object KeepAliveGuide {

    /** ROM 家族（同一家族的子品牌合并：红米/POCO → 小米，realme/一加 → OPPO，iQOO → vivo） */
    enum class Vendor(val label: String) {
        XIAOMI("小米 / 红米"),
        HUAWEI("华为"),
        HONOR("荣耀"),
        OPPO("OPPO / realme / 一加"),
        VIVO("vivo / iQOO"),
        SAMSUNG("三星"),
        OTHER("其它"),
    }

    /** 厂商设置页组件（包名 + 完整类名） */
    data class Target(val pkg: String, val cls: String)

    /** 按 Build.MANUFACTURER（必要时再看 Build.BRAND）识别 ROM 家族；大小写、空白不敏感 */
    fun vendorOf(manufacturer: String?, brand: String? = null): Vendor {
        val keys = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (k in keys) {
            val v = when {
                k.contains("xiaomi") || k.contains("redmi") || k.contains("poco") -> Vendor.XIAOMI
                k.contains("huawei") -> Vendor.HUAWEI
                k.contains("honor") -> Vendor.HONOR
                k.contains("oppo") || k.contains("realme") || k.contains("oneplus") -> Vendor.OPPO
                k.contains("vivo") || k.contains("iqoo") -> Vendor.VIVO
                k.contains("samsung") -> Vendor.SAMSUNG
                else -> null
            }
            if (v != null) return v
        }
        return Vendor.OTHER
    }

    /**
     * 自启动 / 后台运行设置页的候选组件，按优先级排列；调用方依次尝试，全部失败回退应用详情页。
     * 类名随 ROM 大版本变过，所以每家给多条；没有已知页面的（OTHER）返回空表。
     */
    fun autostartTargets(v: Vendor): List<Target> = when (v) {
        Vendor.XIAOMI -> listOf(
            Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Target("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
        )
        Vendor.HUAWEI -> listOf(
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Vendor.HONOR -> listOf(
            Target("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        )
        Vendor.OPPO -> listOf(
            Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Target("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Target("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        Vendor.VIVO -> listOf(
            Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        Vendor.SAMSUNG -> listOf(
            Target("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Target("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Vendor.OTHER -> emptyList()
    }

    /** 每家一段操作提示（第一句是最关键的一步） */
    fun tip(v: Vendor): String = when (v) {
        Vendor.XIAOMI -> "最近任务里下拉灵珠卡片点「锁定」；设置 → 应用设置 → 应用管理 → 灵珠：打开「自启动」，「省电策略」选「无限制」。"
        Vendor.HUAWEI -> "最近任务里下拉灵珠卡片加锁；手机管家 → 应用启动管理 → 灵珠：关掉「自动管理」，三个开关（自启动 / 关联启动 / 后台活动）全开。"
        Vendor.HONOR -> "最近任务里下拉灵珠卡片加锁；设置 → 应用和服务 → 应用启动管理 → 灵珠：改「手动管理」，三个开关全开。"
        Vendor.OPPO -> "最近任务里点灵珠卡片右上角「⋮」→「锁定」；设置 → 应用 → 自启动 → 打开灵珠；电池 → 灵珠 →「允许后台运行」。"
        Vendor.VIVO -> "最近任务里下拉灵珠卡片锁定；i 管家 → 应用管理 → 自启动管理 → 打开灵珠；电池 → 后台耗电管理 → 灵珠选「允许后台高耗电」。"
        Vendor.SAMSUNG -> "设置 → 电池 → 后台使用限制：把灵珠加入「永不休眠的应用」，并确认它不在「休眠应用 / 深度休眠应用」里。"
        Vendor.OTHER -> "最近任务里锁定灵珠；在系统设置 → 应用 → 灵珠 里打开「自启动 / 后台运行」，电池选「不限制」。"
    }

    // ---------------- 状态汇总 ----------------

    enum class Item(val title: String, val desc: String) {
        OVERLAY("悬浮窗", "悬浮球、划词翻译要显示在其它应用上方"),
        NOTIFICATION("通知", "前台服务通知、复习提醒要能弹出来"),
        BATTERY("电池优化白名单", "不被系统省电策略冻结或杀掉"),
        EXACT_ALARM("精确闹钟", "提醒、看门狗准点唤醒"),
        ACCESSIBILITY("无障碍服务", "截屏识题与第三层保活守护"),
        VENDOR_AUTOSTART("厂商自启动 / 后台运行", "国产 ROM 自家管家里的开关，系统不让 App 读取状态"),
    }

    /** ON 已开启（含「本系统版本不需要」）、OFF 未开启、MANUAL 无法检测需手动确认 */
    enum class Status { ON, OFF, MANUAL }

    /** 采集到的原始状态；a11yPresent=false（本机/本版本不支持无障碍服务）时该项隐藏 */
    data class Snapshot(
        val sdk: Int,
        val overlay: Boolean,
        val notifications: Boolean,
        val batteryIgnored: Boolean,
        val exactAlarm: Boolean,
        val a11yPresent: Boolean,
        val a11yOn: Boolean,
    )

    data class Row(val item: Item, val status: Status, val note: String)

    /** 生成要显示的清单（顺序固定；隐藏项不出现） */
    fun rows(s: Snapshot): List<Row> {
        val out = ArrayList<Row>()
        out += Row(Item.OVERLAY, on(s.overlay), "")
        out += Row(Item.NOTIFICATION, on(s.notifications), if (s.sdk >= 33) "Android 13+ 需单独授权" else "")
        out += Row(Item.BATTERY, on(s.batteryIgnored), "")
        if (s.sdk >= 31) out += Row(Item.EXACT_ALARM, on(s.exactAlarm), "Android 12+ 需单独授权")
        else out += Row(Item.EXACT_ALARM, Status.ON, "本系统版本无需单独授权")
        if (s.a11yPresent) out += Row(Item.ACCESSIBILITY, on(s.a11yOn), "")
        out += Row(Item.VENDOR_AUTOSTART, Status.MANUAL, "无法自动检测，请按下方说明手动确认")
        return out
    }

    data class Summary(val on: Int, val off: Int, val manual: Int) {
        val allDetectableOn: Boolean get() = off == 0
        fun text(): String =
            if (off == 0) "可检测的 $on 项都已开启，另有 $manual 项需手动确认"
            else "还有 $off 项未开启（已开启 $on 项，需手动确认 $manual 项）"
    }

    fun summarize(rows: List<Row>): Summary =
        Summary(rows.count { it.status == Status.ON }, rows.count { it.status == Status.OFF }, rows.count { it.status == Status.MANUAL })

    private fun on(b: Boolean) = if (b) Status.ON else Status.OFF
}
