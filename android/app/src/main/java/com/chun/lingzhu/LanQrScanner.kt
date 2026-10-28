package com.chun.lingzhu

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 局域网同步的扫码覆盖层：Camera2 自写预览 + ZXing core 解码，**不用任何相机 / 扫码框架**。
 *
 * - 只盖在当前 [LanSyncActivity] 的根布局上（同页覆盖层），不新开 Activity，不申请后台服务；
 * - 解码只认 QR；结果通过 [onResult] 交给页面写进遮蔽输入框，本类不写文件、不发 Intent、不记日志；
 * - 关闭页面 / 取消 / 出错都会立即关相机并移除覆盖层；
 * - CAMERA 权限由页面懒申请，拒绝时页面保留粘贴方式，本类不会自己弹权限。
 *
 * 预览面与分析面共用同一个 YUV_420_888 尺寸：部分设备对预览输出尺寸有限制，取
 * SCALER_STREAM_CONFIGURATION_MAP 里最接近 1280×720 的可用尺寸，失败则退到最小可用尺寸。
 */
class LanQrScanner(
    private val activity: Activity,
    private val host: FrameLayout,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** 用户点「取消扫描」时回调；页面据此清掉 scanner 引用，否则「扫描」按钮再也打不开新的一次。 */
    private val onCancel: () -> Unit = {},
) {
    private var overlay: FrameLayout? = null
    private var preview: TextureView? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(640, 480)
    private var frameSize = Size(640, 480)
    /** 设备支持连续对焦时才设置；不支持就不写这个 key，避免会话配置失败。 */
    private var afMode: Int? = null
    private var rotation = 0
    private var stopped = false
    private var handled = false
    private var lastDecodeMs = 0L
    /** 解码用的亮度缓冲，按帧尺寸复用；解码只在这一个 HandlerThread 上跑，不用加锁。 */
    private var lumaBuf = ByteArray(0)
    private var rotatedBuf = ByteArray(0)

    fun start() {
        if (overlay != null) return
        stopped = false
        handled = false
        lastDecodeMs = 0L

        val ov = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val texture = TextureView(activity)
        ov.addView(texture, FrameLayout.LayoutParams(-1, -1))
        ov.addView(
            TextView(activity).apply {
                text = "把电脑上的二维码放进框内"
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP),
        )
        ov.addView(
            Button(activity).apply {
                text = "取消扫描"
                setOnClickListener { cancel() }
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(44)
            },
        )
        host.addView(ov, FrameLayout.LayoutParams(-1, -1))
        overlay = ov
        preview = texture

        thread = HandlerThread("lan-qr-scan").also { it.start() }
        handler = Handler(thread!!.looper)

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /** 幂等：取消 / 出错 / 页面 onStop 都可以直接调。 */
    fun stop() {
        if (stopped && overlay == null) return
        stopped = true
        closeCamera()
        val ov = overlay
        overlay = null
        preview = null
        if (ov != null) {
            val parent = ov.parent as? ViewGroup
            if (parent != null) parent.removeView(ov)
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /**
     * 用户主动取消：先关相机 / 移除覆盖层，再通知页面清掉引用。
     * 只调 [stop] 不回调的话，页面里的 scanner 仍非 null，下一次点「扫描」会被直接挡掉。
     */
    private fun cancel() {
        val wasOpen = overlay != null
        stop()
        if (wasOpen) onCancel()
    }

    // ---------------- 相机 ----------------

    private fun openCamera() {
        if (stopped) return
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return fail("这台设备没有相机，仍可粘贴配对串。")
        try {
            val ids = manager.cameraIdList
            if (ids.isEmpty()) return fail("没有可用相机，仍可粘贴配对串。")
            val id = ids.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: ids[0]

            val chars = manager.getCameraCharacteristics(id)
            rotation = rotationDegrees(chars)
            val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            afMode = if (modes != null && modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            } else {
                null
            }
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // 预览面与解码面分开取尺寸：部分设备不接受把 YUV 尺寸直接给 SurfaceTexture。
            previewSize = chooseSize(map?.getOutputSizes(SurfaceTexture::class.java))
            frameSize = chooseSize(map?.getOutputSizes(ImageFormat.YUV_420_888))
            val ir = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.YUV_420_888, 2)
            reader = ir
            ir.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (stopped) {
                        device.close()
                        return
                    }
                    camera = device
                    createSession(device, ir)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device) camera = null
                    fail("相机打开失败（$error），仍可粘贴配对串。")
                }
            }, handler)
        } catch (e: Exception) {
            fail("相机不可用（${e.message ?: e.javaClass.simpleName}），仍可粘贴配对串。")
        }
    }

    private fun createSession(device: CameraDevice, ir: ImageReader) {
        val texture = preview?.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        previewSurface = surface
        try {
            device.createCaptureSession(
                listOf(surface, ir.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (stopped) return
                        session = configured
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                addTarget(ir.surface)
                                afMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                            }.build()
                            configured.setRepeatingRequest(request, null, handler)
                        } catch (e: Exception) {
                            fail("相机预览启动失败（${e.message ?: e.javaClass.simpleName}）。")
                        }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        fail("相机预览启动失败，仍可粘贴配对串。")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            fail("相机预览启动失败（${e.message ?: e.javaClass.simpleName}）。")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { camera?.close() } catch (_: Exception) { }
        camera = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        try { previewSurface?.release() } catch (_: Exception) { }
        previewSurface = null
    }

    @Suppress("DEPRECATION")
    private fun rotationDegrees(chars: CameraCharacteristics): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val device = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensor - device + 360) % 360
    }

    private fun chooseSize(sizes: Array<Size>?): Size {
        if (sizes == null || sizes.isEmpty()) return Size(640, 480)
        val bounded = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val from = if (bounded.isNotEmpty()) bounded else sizes.toList()
        return from.minByOrNull { Math.abs(it.width - 1280) + Math.abs(it.height - 720) }
            ?: sizes[0]
    }

    // ---------------- 解码 ----------------

    private fun onFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            if (stopped || handled) return
            val now = System.currentTimeMillis()
            if (now - lastDecodeMs < DECODE_INTERVAL_MS) return
            lastDecodeMs = now
            val text = decode(image) ?: return
            handled = true
            // stop() 会移除 View / 关相机，必须在主线程。
            activity.runOnUiThread {
                if (stopped) return@runOnUiThread
                stop()
                onResult(text)
            }
        } finally {
            image.close()
        }
    }

    private fun decode(image: Image): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        if (lumaBuf.size != width * height) lumaBuf = ByteArray(width * height)
        val luma = lumaBuf
        if (!readLuma(plane, width, height, luma)) return null

        // ZXing 的 PlanarYUVLuminanceSource **不支持**旋转：调 rotateCounterClockwise 会直接抛
        // UnsupportedOperationException，而且是在 ImageReader 回调线程上，整个进程会崩。
        // 所以先把拷出来的 Y 平面按角度转好，PlanarYUVLuminanceSource 只用来喂二值化，
        // 不经过 Bitmap / Canvas，也不申请额外权限。
        val degrees = ((rotation % 360) + 360) % 360
        val bitmapSource = if (degrees == 0) {
            PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
        } else {
            val outWidth = LumaRotation.rotatedWidth(width, height, degrees)
            val outHeight = LumaRotation.rotatedHeight(width, height, degrees)
            if (rotatedBuf.size != outWidth * outHeight) rotatedBuf = ByteArray(outWidth * outHeight)
            LumaRotation.rotateLuma(luma, width, height, degrees, rotatedBuf)
            PlanarYUVLuminanceSource(rotatedBuf, outWidth, outHeight, 0, 0, outWidth, outHeight, false)
        }

        val hints = HashMap<DecodeHintType, Any>(2)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        val reader = MultiFormatReader()
        reader.setHints(hints)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(bitmapSource))).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * 把 Y 平面拷成紧密排列的 width×height 亮度图（`out[row * width + col]`）。
     *
     * `rowStride` 是行首到行首的字节数（≥ width，末尾可能有填充），`pixelStride` 是相邻像素的
     * 字节间隔（通常是 1，个别设备是 2）。两个都不能当常量：把整行照抄会把填充字节当像素，
     * 画面整体斜掉；pixelStride 大于 1 时逐像素取才不会横向错位。
     * 缓冲区不够（最后一帧被裁过）就放弃这一帧，宁可这次不解码，也不拿半帧垃圾去解。
     */
    private fun readLuma(plane: Image.Plane, width: Int, height: Int, out: ByteArray): Boolean {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride < width || pixelStride <= 0) return false
        if (pixelStride == 1) {
            if (rowStride == width) {
                buffer.position(0)
                if (buffer.remaining() < out.size) return false
                buffer.get(out, 0, out.size)
                return true
            }
            for (row in 0 until height) {
                val start = row * rowStride
                if (start + width > buffer.limit()) return false
                buffer.position(start)
                buffer.get(out, row * width, width)
            }
            return true
        }
        for (row in 0 until height) {
            var index = row * rowStride
            val outBase = row * width
            for (col in 0 until width) {
                if (index >= buffer.limit()) return false
                out[outBase + col] = buffer.get(index)
                index += pixelStride
            }
        }
        return true
    }

    private fun fail(message: String) {
        if (stopped) return
        activity.runOnUiThread {
            if (stopped) return@runOnUiThread
            stop()
            onError(message)
        }
    }

    private fun dp(value: Int): Int = (activity.resources.displayMetrics.density * value).toInt()

    private companion object {
        /** 解码节流：约每 250ms 尝试一帧，避免把预览缓冲吃满。 */
        const val DECODE_INTERVAL_MS = 250L
    }
}

/**
 * Y 平面的纯字节旋转（90 / 180 / 270 度，顺时针），单测直接驱动，不碰相机。
 *
 * 角度取「顺时针」：`SENSOR_ORIENTATION` 正是「把传感器原始画面顺时针转到自然方向」的度数，
 * `LanQrScanner.rotationDegrees` 算出来的 `(sensor - 屏幕旋转 + 360) % 360` 也就直接顺时针用。
 * 设原图宽 w、高 h，`src[row * w + col]` 是第 row 行第 col 列，则：
 * ```
 *  90 : out[r][c] = src[h-1-c][r]        输出 h 列 × w 行
 * 180 : out[r][c] = src[h-1-r][w-1-c]    输出 w 列 × h 行
 * 270 : out[r][c] = src[c][w-1-r]        输出 h 列 × w 行
 * ```
 * 非方阵时行列互换 —— 90 和 270 弄反、或者 180 只反了一个轴（等于左右镜像，二维码永远解不出），
 * 都不会抛异常，只会默默扫不到码，所以这里必须单独测。
 */
internal object LumaRotation {
    fun rotatedWidth(width: Int, height: Int, degrees: Int): Int =
        if (normalize(degrees) % 180 == 90) height else width

    fun rotatedHeight(width: Int, height: Int, degrees: Int): Int =
        if (normalize(degrees) % 180 == 90) width else height

    /** 把 [src] 顺时针转 [degrees] 度写进 [out]；[out] 至少要有 `rotatedWidth * rotatedHeight` 字节。 */
    fun rotateLuma(src: ByteArray, width: Int, height: Int, degrees: Int, out: ByteArray) {
        val outWidth = rotatedWidth(width, height, degrees)
        val outHeight = rotatedHeight(width, height, degrees)
        when (normalize(degrees)) {
            90 -> for (row in 0 until outHeight) {
                val outBase = row * outWidth
                for (col in 0 until outWidth) {
                    // 原图的一列（自下而上）= 新图的一行（自左而右）。
                    out[outBase + col] = src[(height - 1 - col) * width + row]
                }
            }
            180 -> for (row in 0 until outHeight) {
                val srcBase = (height - 1 - row) * width
                val outBase = row * outWidth
                for (col in 0 until outWidth) {
                    out[outBase + col] = src[srcBase + (width - 1 - col)]
                }
            }
            270 -> for (row in 0 until outHeight) {
                val outBase = row * outWidth
                for (col in 0 until outWidth) {
                    // 原图的一列（自下而上）反序 = 新图的一行（自左而右）。
                    out[outBase + col] = src[col * width + (width - 1 - row)]
                }
            }
            else -> if (out !== src) src.copyInto(out, 0, 0, width * height)
        }
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
