package com.chun.lingzhu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 录屏授权（MediaProjection）→ 常驻一个 VirtualDisplay，截图时取最新一帧。
 * Android 14 起一次授权只能 createVirtualDisplay 一次，所以不能「每次截图建一个」，只能常驻。
 * 分辨率按屏宽缩到 ≤ 1080 —— 省内存、省流量，题目文字仍然清楚。
 */
/** MediaProjection 录屏通道（回退用；每次启动都要弹框授权，Android 系统限制） */
class ProjectionSource(private val cap: Capture, private val runOnWorker: (Runnable) -> Unit) : ScreenSource {
    override val ready: Boolean get() = cap.alive
    override val label: String get() = "录屏"
    override fun capture(cb: (String?) -> Unit) {
        runOnWorker(Runnable {
            val seq = cap.frameSeq
            cap.awaitFrameAfter(seq, 350)   // 等浮窗藏好后的新帧
            cb(cap.grabJpegBase64())
        })
    }
    /** v2.4 速记：取最新一帧 → 缩放后的软件位图 */
    override fun captureBitmap(cb: (Bitmap?) -> Unit) {
        runOnWorker(Runnable {
            val seq = cap.frameSeq
            cap.awaitFrameAfter(seq, 350)
            cb(cap.grabBitmap()?.let { scaledSoftwareBitmap(it) })
        })
    }
    override fun release() = cap.release()
}

class Capture(ctx: Context, resultCode: Int, data: Intent, private val onStopped: () -> Unit) {
    private val thread = HandlerThread("lingzhu-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val projection: MediaProjection
    private var reader: ImageReader
    private val display: VirtualDisplay
    private var dims = Triple(0, 0, 0)
    private val lock = Object()
    private var latest: Image? = null
    @Volatile var frameSeq = 0L
        private set
    @Volatile var alive = true
        private set

    init {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data) ?: throw IllegalStateException("没拿到录屏授权")
        // Android 14 硬性要求：createVirtualDisplay 之前必须先注册回调
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { release(); onStopped() }
        }, handler)

        dims = screenDims(ctx)
        val (w, h, dpi) = dims
        reader = newReader(w, h)
        display = projection.createVirtualDisplay("lingzhu", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
    }

    /** 当前屏幕（横竖屏都算）按短边 ≤ 1080 等比缩放后的尺寸 */
    private fun screenDims(ctx: Context): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val short = Math.min(m.widthPixels, m.heightPixels)
        val scale = if (short > 1080) 1080f / short else 1f
        return Triple(Math.round(m.widthPixels * scale), Math.round(m.heightPixels * scale), Math.max(1, Math.round(m.densityDpi * scale)))
    }

    private fun newReader(w: Int, h: Int): ImageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                if (r !== reader) { img.close(); return@setOnImageAvailableListener }   // 转屏后旧 reader 的残帧
                latest?.close()
                latest = img
                frameSeq++
                lock.notifyAll()
            }
        }, handler)
    }

    /** 转屏：虚拟屏跟着换宽高（Android 14 不能重建 VirtualDisplay，只能 resize + 换 Surface） */
    fun resizeToScreen(ctx: Context) {
        if (!alive) return
        val nd = screenDims(ctx)
        if (nd == dims) return
        handler.post {
            if (!alive) return@post
            val (w, h, dpi) = nd
            val old = reader
            val nr = newReader(w, h)
            synchronized(lock) { reader = nr; latest?.close(); latest = null }
            display.resize(w, h, dpi)
            display.surface = nr.surface
            dims = nd
            try { old.close() } catch (_: Exception) {}
        }
    }

    /** 等一帧比 seq 更新的画面（悬浮球 / 面板藏起来之后，屏幕变了才会出新帧）。超时就用手上最新的 */
    fun awaitFrameAfter(seq: Long, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (frameSeq <= seq) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
    }

    /** 当前最新一帧 → JPEG(85, v2.1 起) → base64。没有帧返回 null */
    /**
     * 把最新一帧转成位图（不缩放）。
     *
     * v2.4 从 [grabJpegBase64] 里抽出来：速记要的是位图不是 base64，
     * 两条路共用这段解码逻辑，免得以后改一处忘另一处。
     */
    fun grabBitmap(): Bitmap? {
        synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = img.width
            val h = img.height
            val padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            return if (padded.width != w) Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() } else padded
        }
    }

    fun grabJpegBase64(): String? {
        val b = grabBitmap() ?: return null
        val out = bitmapToJpegBase64(b)
        b.recycle()
        return out
    }

    fun release() {
        if (!alive) return
        alive = false
        try { display.release() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
        synchronized(lock) { latest?.close(); latest = null; lock.notifyAll() }
        try { reader.close() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
