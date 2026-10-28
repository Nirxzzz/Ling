package com.chun.lingzhu

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 本地崩溃日志（v2.2.x 保活审计）。以前进程一崩什么都不留，现场演示翻车了都说不清是哪一行。
 *
 * 规则：
 *  - [install] 在 [LingApp.onCreate] 里挂全局未捕获异常处理器：**先同步写一份日志，再原样交还系统默认处理器**
 *    （让进程照常崩、系统照常弹「已停止」/ 走 START_STICKY 重启）—— 绝不吞异常（吞了就是假死：界面卡住、服务半死不活）；
 *  - 日志放 filesDir/crash/，一次崩溃一个文件，文件名 = crash-<毫秒时间戳>.txt（按名字排序 = 按时间排序）；
 *  - 最多留 [KEEP] 份，每份最多 [MAX_CHARS] 字符（栈太深就截断尾部），总占用有上限，不会越积越多；
 *  - 只写本地，不上传、不联网。保活体检页能看最近一条 / 清空。
 *
 * 判断（文件名、轮转留哪些、正文格式）是纯函数，JVM 单测（[CrashLogTest]）；碰文件的只有 [write] / [list] / [clear]。
 */
object CrashLog {
    const val DIR = "crash"
    const val KEEP = 10
    const val MAX_CHARS = 32_000
    private const val PREFIX = "crash-"
    private const val SUFFIX = ".txt"

    fun fileName(ts: Long): String = PREFIX + ts + SUFFIX

    /** 是不是本模块写出的日志文件（目录里别的东西不碰） */
    fun isLog(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX) &&
        name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() != null

    private fun tsOf(name: String): Long = name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull() ?: 0L

    /** 轮转：在已有日志里挑出要删的（留最新的 [keep] 份）。非日志文件不在候选里 */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter(::isLog).sortedByDescending(::tsOf).drop(keep.coerceAtLeast(0))

    /** 超长截断（保留开头 —— 异常类型和最靠上的几帧最有用） */
    fun clip(s: String, max: Int = MAX_CHARS): String =
        if (s.length <= max) s else s.substring(0, max) + "\n…（已截断，原长 ${s.length} 字符）"

    /** 正文：时间 / 版本 / 机型 / 线程 / 栈 */
    fun format(ts: Long, version: String, device: String, thread: String, stack: String): String = clip(buildString {
        append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ts))).append('\n')
        append("version: ").append(version).append('\n')
        append("device: ").append(device).append('\n')
        append("thread: ").append(thread).append('\n')
        append('\n').append(stack)
    })

    fun stackOf(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    // ---------------- 下面碰 android / 文件 ----------------

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(app, t.name, e) } catch (_: Throwable) {}   // 写日志本身出任何错都不能挡住下面交还系统
            if (prev != null) prev.uncaughtException(t, e)
            else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR)

    /** 同步写（进程马上就要没了，不能丢给别的线程），写完顺手轮转 */
    fun write(ctx: Context, thread: String, e: Throwable) {
        val d = dir(ctx).apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val ver = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") " + BuildConfig.CHANNEL
        val dev = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        File(d, fileName(ts)).writeText(format(ts, ver, dev, thread, stackOf(e)))
        for (n in toDelete(d.list()?.toList() ?: emptyList())) File(d, n).delete()
    }

    /** 已有日志，新的在前 */
    fun list(ctx: Context): List<File> =
        (dir(ctx).listFiles()?.filter { isLog(it.name) } ?: emptyList()).sortedByDescending { tsOf(it.name) }

    fun latestTime(ctx: Context): Long = list(ctx).firstOrNull()?.let { tsOf(it.name) } ?: 0L

    fun clear(ctx: Context) { list(ctx).forEach { it.delete() } }
}
