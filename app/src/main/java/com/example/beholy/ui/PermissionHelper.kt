package com.example.beholy.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.beholy.data.Constants
import com.example.beholy.util.InAppLogger

/**
 * 权限与屏幕捕获授权辅助类。
 *
 * 重点：
 * 1. 通知权限（POST_NOTIFICATIONS，API33+）需显式申请，否则前台服务通知可能不展示；
 * 2. 通过 MediaProjectionManager.createScreenCaptureIntent() 触发系统录屏授权；
 * 3. 本项目严禁联网，不申请任何网络权限（见 AndroidManifest）。
 */
class PermissionHelper(private val activity: AppCompatActivity) {

    /**
     * 是否拥有通知权限（API33+ 需运行时授予；低于该版本视为已拥有）。
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 发起系统录屏（MediaProjection）授权。
     * @param launcher Activity Result 启动器（在 MainActivity 中注册）
     */
    fun requestScreenCapture(launcher: ActivityResultLauncher<Intent>) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        InAppLogger.i("发起录屏授权请求…")
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        const val REQ_NOTIFICATION = 1001
    }
}
