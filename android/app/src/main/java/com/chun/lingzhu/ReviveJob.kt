package com.chun.lingzhu

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 保活第六层（v2.2.x 保活审计）：JobScheduler 周期巡检，15 分钟一轮，持久化（setPersisted）。
 *
 * 和闹钟看门狗（[WatchdogReceiver]）的区别 —— 为什么两层都要：
 *  - 持久化的 Job **由系统在开机后自己恢复**，不靠我们收 BOOT_COMPLETED。小米 / vivo 没开「自启动」时开机广播
 *    根本送不到，闹钟链也就断了；Job 这条不经过开机广播，能多兜一层（各家是否也拦 Job 不保证，尽力而为）；
 *  - 闹钟是我们自己续订的链，链中某一拍被系统吞掉就断；周期 Job 由系统维护，不会因为一拍没跑就断。
 * 醒来做的事与看门狗完全一样：[WatchdogReceiver.revive]（同一个门槛 [KeepAlivePlan.shouldRevive]，重复拉起无妨）。
 *
 * 只在小球想开着时挂（[OrbService] 建球成功后排上、建球失败 / 用户「停止小球」时撤），
 * 排了也只会空手看一眼，不做别的活。
 *
 * 周期由系统自己接着排，不需要我们自己续订 —— 所以**只在状态切换时**动它（别每一拍重排，重排会把周期起点往后推）。
 */
class ReviveJob : JobService() {
    companion object {
        private const val JOB_ID = 0x4C5A   // "LZ"：整个 App 只有这一个 Job
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return   // 已排着：别重排（重排会把周期起点往后推）
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(ctx, ReviveJob::class.java))
                        .setPeriodic(KeepAlivePlan.JOB_PERIOD_MS)
                        .setPersisted(true)   // 要 RECEIVE_BOOT_COMPLETED（已声明）：重启后系统自己恢复这条
                        .build()
                )
            } catch (_: Exception) {}
        }

        fun cancel(ctx: Context) {
            try { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } catch (_: Exception) {}
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        // 球掉了就拉回来（门槛与看门狗一致）；服务 onCreate 会顺手把闹钟看门狗链重新排上
        WatchdogReceiver.revive(this, "巡检任务")
        return false   // 同步做完，不占着 Job
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
