package com.chun.lingzhu

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * v1.63：识别图片 —— 两个入口，都落到悬浮球的面板里显示答案（缓存、历史、追问照常）：
 *  - 系统分享：相册 / 微信里的图点「分享」→「Ling 识题」；
 *  - 小球菜单「从相册选图识别」：本页自己弹系统图片选择器。
 * 透明中转页：读图 → 压成和截屏一样的规格（短边 ≤ 1080 的 JPEG）→ 存文件 → 交给 OrbService → 关掉自己。
 */
class ShareActivity : Activity() {
    companion object {
        const val EXTRA_PICK = "pick"
        private const val REQ_PICK = 7
        fun pickIntent(ctx: android.content.Context) = Intent(ctx, ShareActivity::class.java).putExtra(EXTRA_PICK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // 转屏重建：选择器的结果还会回到 onActivityResult
        val uri = sharedUri(intent)
        when {
            uri != null -> handle(uri)
            // [K4] v1.70：纯文本分享 = 剪藏存知识卡（系统分享菜单里选 Ling）
            intent.action == Intent.ACTION_SEND && intent.getStringExtra(Intent.EXTRA_TEXT) != null -> {
                val clip = KnowledgeImport.clipToCard(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                if (clip == null) { toast("没有可存的内容"); finish() }
                else {
                    val n = KnowledgeCard.Store(applicationContext).add(clip.first, clip.second, KnowledgeCard.Source.CLIP, System.currentTimeMillis())
                    toast(if (n > 0) "已存入知识卡" else "卡片满了"); finish()
                }
            }
            intent.getBooleanExtra(EXTRA_PICK, false) -> pick()
            else -> finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(i: Intent?): Uri? {
        if (i?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    private fun pick() {
        // Android 13+ 的系统照片选择器不用存储权限；老系统用 GET_CONTENT（同样不用权限）
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) { toast("打不开相册：" + (e.message ?: "")); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || uri == null) { finish(); return }
        handle(uri)
    }

    private fun handle(uri: Uri) {
        if (!Settings.canDrawOverlays(this)) {
            toast("先在设置里打开「显示在其他应用上层」权限，答案要显示在悬浮面板里")
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        toast("正在识别这张图…")
        thread {
            val b64 = try { bitmapToJpegBase64(decode(uri)) } catch (e: Throwable) { null }
            runOnUiThread {
                if (b64 == null) { toast("这张图打不开（格式不支持或文件损坏）"); finish(); return@runOnUiThread }
                val f = File(cacheDir, "share-image.b64")
                try { f.writeText(b64) } catch (e: Exception) { toast("存图失败：" + (e.message ?: "")); finish(); return@runOnUiThread }
                // 本页在前台：可以直接起服务（小球没开着也会顺带启动）
                try { startService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_START).putExtra(OrbService.EXTRA_IMAGE_FILE, f.path)) }
                catch (e: Exception) { toast("启动悬浮球失败：" + (e.message ?: "")) }
                finish()
            }
        }
    }

    /** 大图先按比例缩着解码（相机原图 4000 万像素直接解会 OOM），再交给 bitmapToJpegBase64 精确缩到短边 1080 */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                val (w, h) = scaledSize(info.size.width, info.size.height)
                dec.setTargetSize(w, h)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1080) sample *= 2
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
