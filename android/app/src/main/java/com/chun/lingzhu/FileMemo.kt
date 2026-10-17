package com.chun.lingzhu

import java.io.File
import java.util.concurrent.Executors

/**
 * v1.63：小 JSON 文件（历史、结果缓存）的进程内副本 + 后台写。
 * 以前每识别一次都在主线程把 200 条历史整文件读、解析、重写；历史页搜索每敲一个字全量重读。
 * 现在同一进程里只读一次，之后读内存；写交给单线程按顺序落盘（先写 .tmp 再改名，写一半被杀也不坏）。
 * 按文件路径共享：悬浮球服务、历史页、翻译页各自 new 的 Store 看到的是同一份。
 */
object FileMemo {
    // 目前只有 ~10 个固定文件，进程内有界；真多了要加 LRU
    private val mem = HashMap<String, Any>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lz-io").apply { isDaemon = true } }

    @Synchronized
    fun <T : Any> get(f: File, parse: (String?) -> T): T {
        @Suppress("UNCHECKED_CAST")
        return mem.getOrPut(f.path) { parse(try { if (f.exists()) f.readText() else null } catch (_: Exception) { null }) } as T
    }

    /** 最近一次写盘失败（P0 整改：以前 catch 全吞，数据丢了用户不知道）。key=文件路径 */
    @Volatile var lastWriteError: Map<String, String> = emptyMap()
        private set

    @Synchronized
    fun <T : Any> put(f: File, v: T, toJson: (T) -> String) {
        mem[f.path] = v
        io.execute {
            try {
                val tmp = File(f.path + ".tmp"); tmp.writeText(toJson(v))
                if (!tmp.renameTo(f)) { f.writeText(toJson(v)); tmp.delete() }
                lastWriteError = lastWriteError - f.path
            } catch (e: Exception) {
                android.util.Log.e("FileMemo", "写盘失败: " + f.name, e)
                lastWriteError = lastWriteError + (f.path to (e.message ?: "写入失败"))
            }
        }
    }

    @Synchronized
    fun <T : Any> clear(f: File, empty: T) { mem[f.path] = empty; io.execute { f.delete() } }   // 先放空副本：删文件前再读也不会读回旧内容
}
