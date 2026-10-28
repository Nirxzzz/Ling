package com.chun.lingzhu

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * v2.4 记事本：截图附件落盘。
 *
 * ## 三条硬规矩
 * 1. **先 tmp 再 rename**（照抄 FileMemo 的原子写）。写到一半断电，不会留下半张图；
 *    要么完整存在，要么完全不存在 —— 数据库里的记录也一样，文件写成功才插记录。
 * 2. **内容寻址**：sha256 既是文件名的一部分也是去重依据。同一张图截两次只占一份。
 * 3. **按月分目录**：一个月一个文件夹，避免单目录几万个文件拖慢读取。
 */
object NoteAttach {

    /** 原图 JPEG 质量。方案定的 82：1080×2400 约 200 KB，文字仍然清楚 */
    const val QUALITY = 82

    /** 缩略图短边。卡片流只解码它，占内存小 */
    const val THUMB_SHORT = 320f

    /** 缩略图质量 */
    const val THUMB_QUALITY = 70

    /** 附件根目录：filesDir/notes/att */
    fun dir(ctx: android.content.Context): File = File(ctx.filesDir, "notes/att")

    /** 按月子目录，形如 202610 */
    fun monthDir(ctx: android.content.Context, at: Long): File {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        // 名字交给 ThumbSize.monthName 算：那里自己补零、只输出 ASCII，
        // 避免 String.format 在某些 locale 下写出非 ASCII 数字的目录名
        val name = ThumbSize.monthName(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
        )
        return File(dir(ctx), name)
    }

    /**
     * 存一张图：原图 + 缩略图。返回相对路径（相对附件根目录），失败返回 null。
     *
     * 返路相对路径而不是绝对路径：用户换手机、恢复备份后 filesDir 会变，
     * 库里存绝对路径的话所有附件都会「找不到」。
     */
    fun save(bitmap: Bitmap, ctx: android.content.Context, uid: String, at: Long): Saved? {
        val folder = monthDir(ctx, at)
        if (!folder.exists() && !folder.mkdirs()) return null
        val rel = folder.name + "/" + uid + ".jpg"
        val relThumb = folder.name + "/" + uid + ".t.jpg"
        val full = File(dir(ctx), rel)
        val thumb = File(dir(ctx), relThumb)

        val bytes = writeJpeg(bitmap, full, QUALITY) ?: return null
        // 这张 uid 之前可能被缓存成「没有图」。刚写了新图，必须让旧结论失效，
        // 否则列表里这条会一直显示成无图（直到进程重启）。
        invalidateThumb(uid)

        // 缩略图失败不影响主图：卡片流会退化成解码原图，只是慢一点。
        // 尺寸换算走 ThumbSize（纯函数、有单测）：原来这里用 "320, 320*4" 两个魔法数，
        // 那个长边 1280 在 320 短边下永远不生效，等于没写。
        val (tw, th) = ThumbSize.of(bitmap.width, bitmap.height, THUMB_SHORT.toInt())
        val small = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        writeJpeg(small, thumb, THUMB_QUALITY)
        if (small !== bitmap) small.recycle()

        return Saved(
            path = rel,
            thumb = if (thumb.exists()) relThumb else "",
            w = bitmap.width,
            h = bitmap.height,
            bytes = bytes,
            sha256 = sha256(full),
        )
    }

    /** 存盘结果 */
    data class Saved(
        val path: String,
        val thumb: String,
        val w: Int,
        val h: Int,
        val bytes: Long,
        val sha256: String,
    )

    /**
     * 原子写一张 JPEG。先写 .tmp、fsync、再 rename。
     *
     * 返回写了多少字节；失败返回 null（并且清掉 tmp）。
     */
    private fun writeJpeg(bmp: Bitmap, target: File, quality: Int): Long? {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            var ok = false
            FileOutputStream(tmp).use { out ->
                ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (ok) {
                    out.flush()
                    out.fd.sync()
                }
            }
            if (!ok) {
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target.length()
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /** 文件 sha256（内容寻址 / 同步按它拉取） */
    /**
     * 按笔记 uid 找出它的缩略图文件；没有就返回 null（卡片流会退化成「无图」）。
     *
     * 缩略图路径是月份目录 + uid + ".t.jpg"。这里要扫目录，因为笔记只存了
     * 创建时间，没存月份字符串 —— 为了一个路径去多存一列不划算。
     * 月目录最多十几个，扫起来是毫秒级；真慢了再加索引列。
     */
    /**
     * uid → 缩略图路径的内存缓存。
     *
     * ## 为什么必须加
     *
     * 原来每次调用都 [File.listFiles] 一遍月目录、再对每个月做两次 exists()。
     * 单次确实是毫秒级，但列表是**全量重建**的：renderList() 每次都会把所有卡片
     * 重新建一遍，每条带图笔记就重扫一遍目录。从详情页返回一次
     * （onResume → renderList）就把整张表重扫一遍，全在主线程上。
     *
     * 缓存之后，同一批笔记反复重建只扫一次磁盘。
     * 用 uid 作键：uid 是新建时生成的，一条笔记的缩略图路径不会变。
     *
     * 负结果也缓存（null），否则「没有图的笔记」每次都要白扫一遍。
     */
    private val thumbCache =
        java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<File>>()

    fun thumbFile(ctx: android.content.Context, noteUid: String): File? {
        thumbCache[noteUid]?.let { return it.orElse(null) }
        val found = scanThumb(ctx, noteUid)
        thumbCache[noteUid] = java.util.Optional.ofNullable(found)
        return found
    }

    private fun scanThumb(ctx: android.content.Context, noteUid: String): File? {
        val root = dir(ctx)
        val months = root.listFiles() ?: return null
        // 新的月份在前：八成的笔记都在最近的月份里，命中就走
        for (m in months.sortedByDescending { it.name }) {
            if (!m.isDirectory) continue
            val f = File(m, noteUid + ".t.jpg")
            if (f.exists()) return f
            val full = File(m, noteUid + ".jpg")
            if (full.exists()) return full   // 缩略图丢了就退回原图，卡片流自己会 inSampleSize
        }
        return null
    }

    /** 新存了一张图之后调用，免得缓存里还留着「没有图」的旧结论 */
    fun invalidateThumb(noteUid: String) {
        thumbCache.remove(noteUid)
    }

    /**
     * 按 uid 找**原图**（不是缩略图）。
     *
     * thumbFile 优先返回 .t.jpg（320px），详情页要放大看就得用这个。
     * 找不到原图才退回缩略图 —— 总比什么都不显示强。
     */
    fun fullFile(ctx: android.content.Context, noteUid: String): File? {
        val root = dir(ctx)
        val months = root.listFiles() ?: return null
        for (m in months.sortedByDescending { it.name }) {
            if (!m.isDirectory) continue
            val full = File(m, noteUid + ".jpg")
            if (full.exists()) return full
        }
        return thumbFile(ctx, noteUid)
    }

    fun sha256(f: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) { "" }
    }

    /** 删除某条笔记的全部附件文件 */
    fun deleteFiles(ctx: android.content.Context, paths: List<String>) {
        paths.forEach { rel -> try { File(dir(ctx), rel).delete() } catch (_: Exception) {} }
    }
}
