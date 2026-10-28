package com.chun.lingzhu

import android.app.Application
import java.io.File

/**
 * 进程入口（v2.2.x 保活审计）。只做一件事：最早时机挂上 [CrashLog]，
 * 让服务 / 广播 / 无障碍 / Job 任何一个入口拉起的进程崩了都有本地记录。别在这里加重活 —— 每次被系统拉起都要跑。
 *
 * v2.4：加一个记事本数据层自检的触发器。
 * 为什么放在启动路径上而不是设置页按钮：真机验证时点按钮要经过层层页面导航，一次点错就得重来；
 * 而 App 启动每次验证都会发生，写在这里等于「开 App 就顺手体检一遍」。
 * 只在存在标记文件时运行，跑完删标记 —— 正常用户永远碰不到。
 */
class LingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)

        // 存在 filesDir/NOTE_SELFTEST 标记时跑一次自检，结果写 NOTE_SELFTEST_OUT
        try {
            val flag = File(filesDir, "NOTE_SELFTEST")
            if (flag.exists()) {
                flag.delete()
                val (pass, fails) = NoteSelfTest.run(this)
                File(filesDir, "NOTE_SELFTEST_OUT").writeText(
                    "pass=" + pass + "\nfails=" + fails.size + "\n" + fails.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            try {
                File(filesDir, "NOTE_SELFTEST_OUT").writeText("EXCEPTION " + (e.message ?: ""))
            } catch (_: Exception) {}
        }

        // 存在 filesDir/NOTE_QUICK_FLAG 时，启动 9 秒后弹一次速记浮层。
        // 为什么要等 9 秒：onCreate 时窗口还没画出来，截图通道拿不到画面；
        // 而且服务也是稍后才起来的。等这段时间是给「界面就绪」留的。
        try {
            val qf = File(filesDir, "NOTE_QUICK_FLAG")
            if (qf.exists()) {
                qf.delete()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val i = android.content.Intent(this, OrbService::class.java)
                            .setAction(OrbService.ACTION_NOTE)
                        startForegroundService(i)
                    } catch (_: Exception) {}
                }, 9000L)
            }
        } catch (_: Exception) {}
    }
}
