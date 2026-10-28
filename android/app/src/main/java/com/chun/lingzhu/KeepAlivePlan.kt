package com.chun.lingzhu

/**
 * 保活「复活调度」的判断层（v2.2.x 保活审计）—— 纯 Kotlin，不碰 android，JVM 直接单测（[KeepAlivePlanTest]）。
 * 碰系统 API 的那层在 [WatchdogReceiver]（闹钟）/ [ReviveJob]（JobScheduler）/ [OrbService.onTaskRemoved]。
 *
 * 为什么要单拎出来：
 *  - 复活门槛（[shouldRevive]）以前在 BootReceiver / WatchdogReceiver / A11yService 各抄一份，再加一个 JobService 就是第四份；
 *  - 闹钟用哪一档（[alarmMode]）决定了 Android 12+ 上「闹钟醒来能不能起前台服务」：
 *    只有**精确闹钟**（setExact*，且 App 持有精确闹钟授权）到点时，系统才临时放行后台起前台服务；
 *    普通 set() 醒来再 startForegroundService，在没进电池白名单的机器上会抛 ForegroundServiceStartNotAllowedException
 *    —— 这正是设置页「看门狗被拦 N 次」的来源。
 */
object KeepAlivePlan {

    /** 闹钟档位 */
    enum class AlarmMode {
        /** setExactAndAllowWhileIdle：准点 + Doze 里也醒 + 12+ 到点可后台起前台服务（要精确闹钟授权） */
        EXACT,
        /** setAndAllowWhileIdle：不要授权，Doze 里也能醒（被合并、会晚），12+ 到点起前台要靠电池白名单 / 悬浮窗豁免 */
        IDLE,
    }

    /** Android 12（API 31）起才有「后台不许起前台服务」与精确闹钟授权这两件事 */
    const val FGS_LIMIT_SDK = 31

    /** 从最近任务划掉之后，隔多久回来看一眼（MIUI / ColorOS 划卡会连进程一起清，原生只调 onTaskRemoved 不杀） */
    const val TASK_REMOVED_DELAY_MS = 3_000L

    /** JobScheduler 巡检周期：系统允许的最短周期就是 15 分钟（更短会被系统抬到 15 分钟） */
    const val JOB_PERIOD_MS = 15 * 60 * 1000L

    /**
     * 该不该把小球拉回来 —— 全工程唯一的复活门槛：
     * 用户没点「退出」（orbWanted）+ 悬浮窗权限还在（没它拉起来也只能 overlayLost）+ 现在没在跑。
     */
    fun shouldRevive(orbWanted: Boolean, overlay: Boolean, running: Boolean): Boolean =
        orbWanted && overlay && !running

    /**
     * 复活用的闹钟走哪一档。[exactGranted] 在 31 之前恒为 true（[RemindExact.exactGranted] 的口径），
     * 那时也没有后台起前台的限制，精确与否对复活无所谓，统一走 EXACT（准点更好排查）。
     */
    fun alarmMode(sdk: Int, exactGranted: Boolean): AlarmMode =
        if (sdk < FGS_LIMIT_SDK || exactGranted) AlarmMode.EXACT else AlarmMode.IDLE

    /**
     * 这台机器上，**闹钟醒来那一刻**起前台服务大概率能不能成（给保活体检页说人话用，不影响真去起）：
     * 31 之前不限；31+ 有精确闹钟授权（到点临时豁免）或进了电池白名单（长期豁免）就能成。
     */
    fun alarmCanStartFgs(sdk: Int, exactGranted: Boolean, batteryExempt: Boolean): Boolean =
        sdk < FGS_LIMIT_SDK || exactGranted || batteryExempt
}
