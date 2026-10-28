package com.chun.lingzhu

import android.content.Context
import android.content.Intent

/**
 * 手机版专注守护的设置（v2.3.4）。
 *
 * ## 为什么单独一个类
 * 设置项从「一个开关 + 一个阈值」变成了「开关 + 两种模式 + 白名单 + 阈值 + 陪伴开关 / 频率」，
 * 再散在 [Prefs] 里各处 getBoolean / getInt，读取点一多就必然出现「一处读了默认值、另一处忘了夹区间」。
 * 这里把口径集中成一份：[enabled] / [leaveMinutes] **代理** [Prefs] 原有那两条（升级用户的值原样保留），
 * 其余几条独立存（老版本没有这些键，读不到就是契约里的默认）。
 *
 * ## 默认值为什么是这个
 * 全部保守：守护默认关、模式默认「指定学习应用」（不看屏幕内容）、白名单空（= 一个都不算，不冤枉人）、
 * 离开阈值沿用老版本的 5 分钟、陪伴默认关、频率默认中档（45–120 分钟）。用户不主动开，就什么都不发生。
 *
 * ## 写入一律归一
 * [mode] / [companionFrequency] 的 setter 会先走 [normMode] / [normFreq]：外部传了脏值（文件被手改、
 * 版本回滚）也不会让采样逻辑走进一个不存在的分支。读的时候同样归一，所以「读到的值」永远在枚举内。
 */
class FocusSettings(ctx: Context) {

    private val app: Context = ctx.applicationContext
    private val prefs = Prefs(app)
    private val sp = app.getSharedPreferences("lingzhu", Context.MODE_PRIVATE)

    /**
     * 守护总开关。默认关 —— 这是唯一一条「开了才会看屏幕 / 查前台应用」的闸门。
     * 代理 [Prefs.focusGuardOn]：报告页（[FocusReportActivity]）也读那一份，两边不会说法不一致。
     */
    var enabled: Boolean
        get() = prefs.focusGuardOn
        set(v) { prefs.focusGuardOn = v }

    /** 守护模式：[MODE_APP]（只看前台应用，不碰屏幕内容）/ [MODE_SCREEN]（本机像素三态） */
    var mode: String
        get() = normMode(sp.getString(KEY_MODE, MODE_APP))
        set(v) = sp.edit().putString(KEY_MODE, normMode(v)).apply()

    /** [MODE_APP] 下算作「在学习」的应用包名。空 = 一个都不算（判不出来，不提醒） */
    var apps: Set<String>
        get() = cleanApps(sp.getStringSet(KEY_APPS, null))
        set(v) = sp.edit().putStringSet(KEY_APPS, cleanApps(v)).apply()

    /**
     * 离开多久算「离开」（分钟）。默认 5，夹在 1..60。
     * 代理 [Prefs.focusLeaveMin]：老用户调过的值（默认也是 5）原样沿用，不在这里另起一套。
     */
    var leaveMinutes: Int
        get() = prefs.focusLeaveMin.coerceIn(MIN_LEAVE_MIN, MAX_LEAVE_MIN)
        set(v) { prefs.focusLeaveMin = v.coerceIn(MIN_LEAVE_MIN, MAX_LEAVE_MIN) }

    /** 陪伴气泡开关。默认关：不定时冒出来的话最容易变成打扰 */
    var companionEnabled: Boolean
        get() = sp.getBoolean(KEY_COMPANION_ON, false)
        set(v) = sp.edit().putBoolean(KEY_COMPANION_ON, v).apply()

    /** 陪伴频率：LOW（60–180 分钟）/ MED（45–120）/ HIGH（15–60）。默认中档 */
    var companionFrequency: String
        get() = normFreq(sp.getString(KEY_COMPANION_FREQ, FREQ_MED))
        set(v) = sp.edit().putString(KEY_COMPANION_FREQ, normFreq(v)).apply()

    companion object {
        /** 只看前台应用（UsageStats，需要系统「使用情况访问」授权） */
        const val MODE_APP = "app"

        /** 只看屏幕内容（本机灰度版面三态，不上传、不调模型） */
        const val MODE_SCREEN = "screen"

        const val FREQ_LOW = "low"
        const val FREQ_MED = "med"
        const val FREQ_HIGH = "high"

        /** 离开阈值允许区间（分钟） */
        const val MIN_LEAVE_MIN = 1
        const val MAX_LEAVE_MIN = 60

        /**
         * 设置页改完专注守护后发给 [OrbService] 的动作：让它**就地**应用新设置。
         *
         * 为什么不是普通广播：服务只在真正跑着的时候才需要重排采样，[refresh] 里已经判过一次
         * [OrbService.running]；显式 intent 直接落到服务的 onStartCommand，不惊动系统去冷启动一个服务
         * （后台拉服务在 Android 12+ 上会被拦，也违反「不偷偷启动」这条）。
         */
        const val ACTION_REFRESH = "com.chun.lingzhu.action.REFRESH_FOCUS_COMPANION"

        private const val KEY_MODE = "focusMode"
        private const val KEY_APPS = "focusApps"
        private const val KEY_COMPANION_ON = "focusCompanionOn"
        private const val KEY_COMPANION_FREQ = "focusCompanionFreq"

        /** 认不出的模式一律当「指定学习应用」：那个模式**不看屏幕内容**，是更保守的一侧 */
        fun normMode(raw: String?): String = if (raw == MODE_SCREEN) MODE_SCREEN else MODE_APP

        /** 认不出的频率兜到中档：低档太稀（等于没有）、高档太密（等于打扰），中档是唯一无害的默认 */
        fun normFreq(raw: String?): String = when (raw) {
            FREQ_LOW -> FREQ_LOW
            FREQ_HIGH -> FREQ_HIGH
            else -> FREQ_MED
        }

        /** 白名单清洗：去空白、丢空串、去重。脏包名留着只会在比较时永远不命中 */
        fun cleanApps(raw: Set<String>?): Set<String> =
            raw?.asSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        /**
         * 让正在跑的小球服务重读专注设置。**服务没在跑就什么都不做** ——
         * 不 startService（后台起服务会被系统拦，也等于偷偷把东西拉起来）、不弹截屏授权。
         */
        fun refresh(ctx: Context) {
            if (!OrbService.running) return
            try {
                ctx.startService(Intent(ctx, OrbService::class.java).setAction(ACTION_REFRESH))
            } catch (_: Throwable) {
                // 起不来（ROM 拦后台服务 / 服务刚被系统收回）：设置已经落盘，下一轮采样自然按新值走
            }
        }
    }
}
