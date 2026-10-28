package com.chun.lingzhu

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * v1.63：小 JSON 文件（历史、结果缓存）的进程内副本 + 后台写。
 * 以前每识别一次都在主线程把 200 条历史整文件读、解析、重写；历史页搜索每敲一个字全量重读。
 * 现在同一进程里只读一次，之后读内存；写交给单线程按顺序落盘（先写 .tmp、fsync 再改名，写一半被杀也不坏）。
 * 按文件路径共享：悬浮球服务、历史页、翻译页各自 new 的 Store 看到的是同一份。
 *
 * 锁约定：内存副本由 FileMemo 的 monitor 保护；io 线程只碰磁盘和 [lastWriteError]，
 * **绝不进入 FileMemo monitor**。因此调用方可以持有 `synchronized(FileMemo)` 再调用
 * [putAndWait] / [flush] 做落盘屏障，不会出现「持锁等 io、io 等锁」的死锁。
 */
object FileMemo {
    // 目前只有 ~10 个固定文件，进程内有界；真多了要加 LRU
    private val mem = HashMap<String, Any>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lz-io").apply { isDaemon = true } }

    /** 首次加载失败的路径：文件读不出来或 JSON 坏了。普通写入不会清掉，避免「按空库」被掩盖。 */
    private val loadErrors = HashMap<String, String>()
    /** 与 [loadErrors] 对应：这次失败是磁盘读不出来（不是内容坏），缓存命中时也要如实返回。 */
    private val loadIo = HashSet<String>()

    /**
     * 严格读取结果。`error != null` 表示这份文件的健康状态不可信（IO 或 JSON 解析失败），
     * 同步必须拒绝；`io = true` 表示是磁盘读失败而不是内容损坏。
     * [value] 仍可能是按空库解析出来的，供已有 UI 继续显示。
     */
    class Loaded<T>(val value: T?, val error: String? = null, val io: Boolean = false) {
        val healthy: Boolean get() = error == null
    }

    @Synchronized
    fun <T : Any> get(f: File, parse: (String?) -> T): T {
        @Suppress("UNCHECKED_CAST")
        return mem.getOrPut(f.path) { parse(try { if (f.exists()) f.readText() else null } catch (_: Exception) { null }) } as T
    }

    /**
     * 带健康状态的读取：第一次加载时用 [strict] 校验内容，失败则把错误记在本进程的 [loadErrors] 里，
     * 再用 [lenient] 得到 UI 可用的值。之后即使 [put] / [putAndWait] 写入了新值，错误也不会被清掉
     * （只有显式的 [clear] 才视为用户主动清空）。
     * 文件明确不存在时 [strict] 收到 null，按空库处理，这是合法状态。
     */
    @Synchronized
    fun <T : Any> getChecked(f: File, lenient: (String?) -> T, strict: (String?) -> T): Loaded<T> {
        val cached = mem[f.path]
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            return Loaded(cached as T, loadErrors[f.path], f.path in loadIo)
        }

        val text = try {
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            val msg = "读取 " + f.name + " 失败：" + (e.message ?: e.javaClass.simpleName)
            android.util.Log.e("FileMemo", msg, e)
            loadErrors[f.path] = msg
            loadIo.add(f.path)
            val empty = lenient(null)
            mem[f.path] = empty
            return Loaded(empty, msg, io = true)
        }

        val value = try {
            strict(text)
        } catch (e: Exception) {
            val msg = "解析 " + f.name + " 失败：" + (e.message ?: e.javaClass.simpleName)
            android.util.Log.e("FileMemo", msg, e)
            loadErrors[f.path] = msg
            lenient(text)
        }
        mem[f.path] = value
        return Loaded(value, loadErrors[f.path])
    }

    /** 最近一次写盘失败（P0 整改：以前 catch 全吞，数据丢了用户不知道）。key=文件路径 */
    @Volatile var lastWriteError: Map<String, String> = emptyMap()
        private set

    /** 更新内存副本并进单 io 队列，不等待落盘；写失败记录到 [lastWriteError]。 */
    @Synchronized
    fun <T : Any> put(f: File, v: T, toJson: (T) -> String) {
        mem[f.path] = v
        io.execute { writeNow(f, v, toJson) }
    }

    /**
     * 按文件原子读改写：整个「读当前值 → transform 算出新值 → 换内存副本 → 入写队列」都在同一个 monitor 里完成。
     *
     * 为什么必须走这里：各 Store 都是「每次调用 new 一个」，`@Synchronized` 锁在**各自实例**上，保护不了同一个文件；
     * 先 list() 再 put() 这种读改写会被别的调用方塞进来的写覆盖成旧快照（Todo 新增 / 完成 / 删除被 sync 抹掉）。
     * 这里锁的是 [FileMemo] 这个进程内唯一对象，所有文件的读改写共用此锁；实际写盘仍由 io 队列执行。
     *
     * [transform] 收到当前内存副本（来自 [parse] 的结果，调用方不要原地修改），返回新的业务值。
     * 写失败照旧记入 [lastWriteError]，由 [flush] / 下次读盘报给用户。
     */
    @Synchronized
    fun <T : Any> update(f: File, parse: (String?) -> T, toJson: (T) -> String, transform: (T) -> T) {
        if (mem[f.path] == null) get(f, parse)   // 首次访问仍走一遍严格/宽松的加载约定
        @Suppress("UNCHECKED_CAST")
        val next = transform(mem[f.path] as T)
        put(f, next, toJson)
    }

    /**
     * 与 [put] 同一队列，但等本次写真正落盘后才返回。
     * 只抛**本次写**的错误（不把队列里其他文件的历史错误算到这次头上）；失败已同步记入 [lastWriteError]。
     * 调用方通常已持有 `synchronized(FileMemo)`，io 线程不会回头抢该锁，所以等待是安全的。
     */
    @Synchronized
    fun <T : Any> putAndWait(f: File, v: T, toJson: (T) -> String) {
        mem[f.path] = v
        val err = try {
            io.submit(Callable { writeNow(f, v, toJson) }).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("等待写盘被中断: " + f.name, e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw if (cause is IOException) cause else IOException("写入 " + f.name + " 失败", cause ?: e)
        }
        if (err != null) throw err
    }

    /**
     * 落盘屏障：在每个已入队任务之后放一个 barrier 并等它完成，随后检查 [lastWriteError]。
     * 任一写盘失败即抛 IOException（错误对用户可见），不再静默吞掉。
     */
    fun flush() {
        try {
            io.submit(Runnable { }).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("等待写盘被中断", e)
        } catch (e: ExecutionException) {
            throw IOException("等待写盘失败", e.cause ?: e)
        }
        val errs = lastWriteError
        if (errs.isNotEmpty()) {
            val detail = errs.entries.joinToString("; ") { (p, msg) -> File(p).name + ": " + msg }
            throw IOException("写盘失败（$detail）")
        }
    }

    @Synchronized
    fun <T : Any> clear(f: File, empty: T) {   // 先放空副本：删文件前再读也不会读回旧内容
        mem[f.path] = empty
        // 用户主动清空（删文件）是合法删除，旧加载错误不再有意义。
        loadErrors.remove(f.path)
        loadIo.remove(f.path)
        io.execute { if (f.delete()) lastWriteError = lastWriteError - f.path }
    }

    /**
     * 只给「权威恢复」用（同步 journal 重放成功）：内容已被完整重写，旧的加载错误不再适用。
     * 普通 [put] / [putAndWait] 不调用它，所以日常写入不会把加载失败掩盖掉。
     */
    @Synchronized
    fun clearLoadError(f: File) {
        loadErrors.remove(f.path)
        loadIo.remove(f.path)
    }

    /**
     * 仅由 io 线程调用：写 .tmp、fsync、rename。rename 失败即报错，**不回退为直接覆盖原文件**
     * （覆盖到一半被杀会留下坏文件，且用户看不到失败）。
     */
    private fun <T> writeNow(f: File, v: T, toJson: (T) -> String): IOException? {
        try {
            val tmp = File(f.path + ".tmp")
            f.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(toJson(v).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw IOException("改名失败: " + f.name)
            }
            lastWriteError = lastWriteError - f.path
            return null
        } catch (e: Exception) {
            android.util.Log.e("FileMemo", "写盘失败: " + f.name, e)
            lastWriteError = lastWriteError + (f.path to (e.message ?: "写入失败"))
            return e as? IOException ?: IOException("写入 " + f.name + " 失败", e)
        }
    }
}
