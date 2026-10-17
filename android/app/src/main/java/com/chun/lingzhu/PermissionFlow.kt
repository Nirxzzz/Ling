package com.chun.lingzhu

/**
 * 「一键配置」的**判断层** —— 纯 Kotlin，不碰 android，单测直接测（[PermissionFlowTest]）。
 * 界面 + 真去开权限那层在 [PermissionActivity]。
 *
 * 「一键」能做到什么程度，得说实话（这是 Android 的硬边界，不是偷懒）：
 *  - **通知**：运行时权限，能把要的一批**一次弹完**（[runtimeBatch]），用户点一下「允许」就完事，不用去设置里找；
 *  - **电池优化**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会弹**一个**系统确认框，点「允许」即生效；
 *  - **悬浮窗 / 无障碍**：Android 6.0 起就**不允许 App 自己授予**，只能深链到系统那一页、由用户拨开关。
 *    所以「一键」= App 按顺序把这几页**一页页弹到用户面前**，用户回到本页时自动检查并接着弹下一页 ——
 *    不用自己在设置里翻三级菜单，但**开关那一下必须人来拨**。
 *    （市面上号称全自动的，是拿无障碍服务去模拟点击系统界面 —— 那属于恶意行为，不做。）
 *  - 某一页没拨就返回 → 记成「先不开」并继续下一项，绝不把人困在死循环里。
 *  - **精确闹钟不进「一键配置」**：它只是「提醒准不准点」的可选项，放在清单里单独去开即可，不该挡住启动。
 *
 * 顺序（[AUTO]）：缺什么先补什么，且**先补「要跳系统页」的悬浮窗 / 无障碍，再补「弹个系统框」的通知 / 后台运行**
 * —— 前者最容易漏、又是小球能显示出来的前提，趁用户在跟前先带他去。
 *
 * **已授权 ≠ 截屏可用**：无障碍开关开着（`a11yOn`）只是「授权了」，服务有没有连上（`a11yConnected`）是另一回事。
 * 判断层把两者分开暴露，界面据此显示「已授权 · 服务未连接」，不把已授权直接说成能用（[a11yAuthorizedNotConnected]）。
 */
object PermissionFlow {

    /** 怎么拿到它 */
    enum class How {
        /** 运行时权限：App 自己弹框就能要（可批量） */
        RUNTIME,
        /** 系统只弹一个确认框（点「允许」即生效），不用翻页面 */
        DIALOG,
        /** 只能深链到系统设置页，由用户拨开关 */
        PAGE,
    }

    enum class Item(val id: String, val title: String, val how: How, val required: Boolean) {
        /** 通知（13+ 才是运行时权限；更老的版本装上就有） */
        NOTIFY("notify", "通知", How.RUNTIME, false),
        /** 允许后台运行（忽略电池优化）—— 系统一个确认框 */
        BATTERY("battery", "允许后台运行", How.DIALOG, false),
        /** 悬浮窗（显示在其他应用上层）—— 小球存在的前提，没它一切都白搭 */
        OVERLAY("overlay", "悬浮窗（显示在其他应用上层）", How.PAGE, true),
        /** 无障碍截屏 —— 不开也能用（每次弹录屏框），所以不是必需 */
        CAPTURE("capture", "无障碍截屏（识别时不弹录屏框）", How.PAGE, false),
        /** 精确闹钟（12+ 要用户授权；13+ 若 USE_EXACT_ALARM 生效则自动就绪）—— 不进一键配置 */
        EXACT("exact", "精确闹钟（提醒准点）", How.PAGE, false),
    }

    /**
     * 清单展示顺序：先「必需 / 要跳系统页」的，再「弹系统框」的，最后是纯可选。
     */
    val ORDER = listOf(Item.OVERLAY, Item.CAPTURE, Item.NOTIFY, Item.BATTERY, Item.EXACT)

    /**
     * 顶部「一键配置」真正会推进的项 —— **只包含【缺】的这几项**，且**不含** EXACT。
     * 缺项按此顺序一页页递到用户面前：先悬浮窗 / 无障碍（要拨开关、最容易漏），再通知 / 后台运行（弹框点允许）。
     */
    val AUTO = listOf(Item.OVERLAY, Item.CAPTURE, Item.NOTIFY, Item.BATTERY)

    /** 这台机器、这一刻的权限快照（[PermissionActivity.state] 每次回前台重采一遍） */
    data class State(
        val sdk: Int,
        /** 系统太老（< Android 11）没有无障碍截屏这条路 */
        val a11ySupported: Boolean,
        val overlay: Boolean,
        /** 系统「无障碍」里本组件是不是开着的 = **已授权**（用户能控制的就是这个） */
        val a11yOn: Boolean,
        /** 无障碍服务此刻是否已连上 = **可用**（[A11yService.available]）。已授权但没连上时 UI 要写「服务未连接」。 */
        val a11yConnected: Boolean = false,
        val notify: Boolean,
        val battery: Boolean,
        val exact: Boolean,
        /** 用户点了「先不开」/ 跳过去没拨就回来的项（[Item.id]） */
        val skipped: Set<String> = emptySet(),
    )

    /** 这一项在这台机器上要不要管（版本不够 / 系统没这条路的，直接算完成，不拿它烦用户） */
    fun applicable(item: Item, s: State): Boolean = when (item) {
        Item.NOTIFY -> s.sdk >= RemindExact.NOTI_MIN_SDK
        Item.EXACT -> s.sdk >= RemindExact.EXACT_MIN_SDK
        Item.CAPTURE -> s.a11ySupported
        else -> true
    }

    /** 已经拿到了吗（无障碍看的是「已授权」，不是「已连接」—— 连接由系统管，不是用户开关的事） */
    fun granted(item: Item, s: State): Boolean = when (item) {
        Item.OVERLAY -> s.overlay
        Item.CAPTURE -> s.a11yOn
        Item.NOTIFY -> s.notify
        Item.BATTERY -> s.battery
        Item.EXACT -> s.exact
    }

    /**
     * 无障碍这一项：系统里**已授权**，但服务此刻**没连上** —— 界面要显示「已授权 · 服务未连接」，
     * 并给一个能点去系统开关的入口，而不是把它当成「已开启」锁死。
     */
    fun a11yAuthorizedNotConnected(s: State): Boolean = s.a11ySupported && s.a11yOn && !s.a11yConnected

    /** 这一项算「过了」：拿到了 / 本机不适用 / 用户明确先不开 */
    fun done(item: Item, s: State): Boolean =
        !applicable(item, s) || granted(item, s) || item.id in s.skipped

    /** 清单上要显示的项（本机不适用的不显示 —— 显示一行「本机没有这一项」只会添乱） */
    fun visible(s: State): List<Item> = ORDER.filter { applicable(it, s) }

    /** 一键配置里本机要管的项（EXACT 不在其中） */
    fun autoVisible(s: State): List<Item> = AUTO.filter { applicable(it, s) }

    /** 下一项该做什么（只在一键配置的范围内找）；null = 一键配置该做的都过了 */
    fun next(s: State): Item? = AUTO.filter { applicable(it, s) }.firstOrNull { !done(it, s) }

    /** 一次能自己弹框要到的运行时权限（能批就批，别一项弹一次） */
    fun runtimeBatch(s: State): List<Item> =
        autoVisible(s).filter { it.how == How.RUNTIME && !granted(it, s) && it.id !in s.skipped }

    /** 一键配置全都过了（含「先不开」的） */
    fun ready(s: State): Boolean = next(s) == null

    /** 一键配置真正拿到了几项（不含「先不开」），和 [autoVisible] 的总数一起给进度 */
    fun grantedCount(s: State): Int = autoVisible(s).count { granted(it, s) }

    /** 「3/4 项已就绪」；有先不开的就补一句。无障碍已授权但服务没连上时不说「都就绪」（授权数 ≠ 能截屏） */
    fun summary(s: State): String {
        val all = autoVisible(s)
        val got = grantedCount(s)
        val skip = all.count { !granted(it, s) && it.id in s.skipped }
        val base = "$got/${all.size} 项已就绪"
        return when {
            // 无障碍已授权、服务没连上：这里的数目只是「拿到几项授权」，不代表真能截屏，不能算都就绪
            a11yAuthorizedNotConnected(s) ->
                "权限已配置（$got/${all.size} 项已授权，授权 ≠ 能截屏）· 无障碍服务未连接" +
                    if (skip > 0) " · $skip 项你先不开" else ""
            all.isNotEmpty() && got == all.size -> "都开好了（$base）"
            skip > 0 -> "$base · $skip 项你先不开（可随时回来补）"
            else -> base
        }
    }

    /** 有没有「必需但还没拿到」的（悬浮窗）—— 有就不能算能用 */
    fun blocked(s: State): List<Item> = visible(s).filter { it.required && !granted(it, s) }

    /** 清单里那一行的文案。灰度屏上靠符号不靠颜色：✓ 已开 / ◇ 已授权未连接 / ✗ 没开 / — 先不开 / · 待开 */
    fun rowLabel(item: Item, s: State): String {
        val t = item.title
        return when {
            item == Item.CAPTURE && a11yAuthorizedNotConnected(s) -> "◇ $t：已授权 · 服务未连接"
            granted(item, s) -> "✓ $t：已开"
            item.id in s.skipped -> "— $t：先不开了（点这里再开一次）"
            next(s) == item -> "▸ $t：下一个就开它" + if (item.required) "（必需）" else ""
            else -> "✗ $t：还没开"
        }
    }

    /** 这一项在系统里叫什么、要用户做什么（跳过去之前先说清楚，别让人到了那一页发懵） */
    fun howTo(item: Item): String = when (item) {
        Item.NOTIFY -> "弹一个系统框，点「允许」就行（不用去设置里找）"
        Item.BATTERY -> "弹一个系统框，点「允许」就行 —— 允许后减少后台被系统清理（允许后台运行），但不保证系统一定不杀"
        Item.OVERLAY -> "打开的这一页里，把「允许显示在其他应用上层」的开关拨到开，然后按返回键回来"
        Item.CAPTURE -> "打开的这一页里，找到「" + Preset.appName + "」→ 点开 → 把开关拨到开，然后按返回键回来。" +
            "开关开着但显示「服务未连接」时，多半是系统还没重连上 —— 等一两秒，或点「查看系统开关」确认它是开着的。" +
            "（Android 13 起不是从应用商店装的 App 这一项默认被锁，" +
            "若列表里是灰的：先在应用信息页点右上角的「三个点」菜单 →「允许受限制的设置」）"
        Item.EXACT -> "打开的这一页里，把「闹钟和提醒」的开关拨到开，然后按返回键回来"
    }

    /** 全过完时的那句话。断连与 required blocked 优先；ready 但有可选项被「先不开」时只说必需已齐、不声称全部都开 */
    fun finishLine(s: State): String {
        val missed = autoVisible(s).count { !granted(it, s) && it.id !in s.skipped }
        val skippedAuto = autoVisible(s).count { !granted(it, s) && it.id in s.skipped }
        return when {
            blocked(s).isNotEmpty() -> "还差「悬浮窗」这一项 —— 没它小球显示不出来。点下面按钮再去开一次。"
            ready(s) && a11yAuthorizedNotConnected(s) ->
                "必需项都开了。无障碍已授权、服务暂时没连上 —— 通常一会儿会自动连上；一直连不上就点上面那项里的「查看系统开关」确认开关是开着的。"
            ready(s) && skippedAuto > 0 ->
                "必需项都齐了，可以直接启动小球。（$skippedAuto 项可选的你选了先不开，想开随时回来补；精确闹钟想更准点单独去开它。）"
            ready(s) -> "启动要用的都齐了，可以直接启动小球了。精确闹钟是可选，想更准点就单独去开它。"
            else -> "还有 $missed 项没开，点上面「一键配置」我带你去。"
        }
    }
}
