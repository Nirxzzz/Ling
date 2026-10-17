package com.chun.lingzhu

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * v1.59：录屏授权中转页 —— 完全透明，用户只看到系统的「共享屏幕」框，点「开始」就回到原来的画面。
 * 以前：面板提示 → 点一下 → 打开设置页 → 再点按钮 → 才弹框；现在小球双击 / 点提示直接弹框。
 * Android 14 起用「只能共享整个屏幕」的配置 —— 框里不会再默认「单个应用」、还得手动切。
 * 授权完如果带着 solveAfter，悬浮球会接着把刚才那次识别做完。
 */
class CaptureActivity : Activity() {
    companion object {
        private const val REQ = 9
        fun captureIntent(a: Activity): Intent {
            val mpm = a.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                   else mpm.createScreenCaptureIntent()
        }
    }

    private var solveAfter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        solveAfter = intent?.getBooleanExtra(OrbService.EXTRA_SOLVE_AFTER, false) == true
        if (savedInstanceState != null) return   // 转屏重建：框还在，等结果
        if (!Settings.canDrawOverlays(this)) {
            // 悬浮窗权限只能去系统设置里开（系统没有弹框这种方式）
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            Toast.makeText(this, "先打开「显示在其他应用上层」，回来再双击小球", Toast.LENGTH_LONG).show()
            finish(); return
        }
        @Suppress("DEPRECATION")
        try { startActivityForResult(captureIntent(this), REQ) }
        catch (e: Exception) { Toast.makeText(this, "弹不出授权框：" + (e.message ?: ""), Toast.LENGTH_LONG).show(); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, OrbService::class.java).setAction(OrbService.ACTION_START)
                .putExtra(OrbService.EXTRA_CODE, resultCode).putExtra(OrbService.EXTRA_DATA, data)
                .putExtra(OrbService.EXTRA_SOLVE_AFTER, solveAfter))
        } else {
            Toast.makeText(this, "没授权就截不了屏。想长期免授权，可以在 " + Preset.appName + " 设置里开「无障碍截屏」", Toast.LENGTH_LONG).show()
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
