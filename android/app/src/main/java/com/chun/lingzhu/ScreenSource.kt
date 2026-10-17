package com.chun.lingzhu

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 截屏通道抽象。两条实现：
 *  - A11ySource：无障碍服务 takeScreenshot —— 在系统里**授权一次**后不再按次授权；但「已授权 ≠ 已连上」，
 *    只有服务此刻连着（[A11yService.available] → [ready]）才真能截，没连上时截不了、不假装可用。首选（连上时）。
 *  - ProjectionSource：MediaProjection 录屏 —— 每次启动都要弹框授权（Android 系统限制），仅作回退。
 * 调用方（OrbService）负责在 capture() 之前把悬浮球和面板藏好。
 * 例外是自动扫描：支持窗口截图的通道可走 [captureForScan]，不必先藏浮窗。
 */
interface ScreenSource {
    /** 现在能不能截（无障碍：服务已连；录屏：VirtualDisplay 还活着） */
    val ready: Boolean
    /** 给用户看的通道名 */
    val label: String
    /** 截一张 → JPEG(80) base64（短边 ≤ 1080）。回调可能在任意线程，null=失败。浮窗此时应已藏好 */
    fun capture(cb: (String?) -> Unit)

    /**
     * 自动扫描专用：尝试「只截目标 App 窗口」，**不用**先把浮窗藏起来。
     * 返回 true = 已经**开始**截（cb 一定会回调**恰好一次**，可能是 null）；
     * 返回 false = **没开始**（NOT STARTED）—— 调用方必须走原来的「藏浮窗 + 等 120ms + [capture]」全屏路径。
     * 默认 false：只有支持窗口截图的通道（[A11ySource]，API 34+）才覆盖它。回调可能在任意线程。
     */
    fun captureForScan(cb: (String?) -> Unit): Boolean = false

    /** 上次 capture 失败的原因（给人看的），成功或不知道时 null */
    val lastError: String? get() = null
    fun release()
}

/**
 * 位图 → JPEG base64（HARDWARE 位图先 copy 成软件位图再压）。
 * v1.63：按「短边 ≤ 1080、长边 ≤ 2400」缩 —— 以前按宽度 1080 缩，横屏 2400×1080 会被压成 1080×486，字只剩 6px 高，模型认不出。
 */
internal fun bitmapToJpegBase64(bmp: Bitmap?, quality: Int = 80, maxShort: Int = 1080, maxLong: Int = 2400): String? {
    if (bmp == null) return null
    var b = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bmp
    val (w, h) = scaledSize(b.width, b.height, maxShort, maxLong)
    if (w != b.width) {
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        if (scaled !== b) { if (b !== bmp) b.recycle(); b = scaled }
    }
    val bos = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
    if (b !== bmp) b.recycle()
    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}

/** 纯计算（单测用）：等比缩到短边 ≤ maxShort 且长边 ≤ maxLong，不放大 */
internal fun scaledSize(w: Int, h: Int, maxShort: Int = 1080, maxLong: Int = 2400): Pair<Int, Int> {
    val k = minOf(1.0, maxShort.toDouble() / minOf(w, h), maxLong.toDouble() / maxOf(w, h))
    return if (k >= 1.0) w to h else Math.round(w * k).toInt() to Math.round(h * k).toInt()
}
