package com.example.beholy.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.beholy.util.DeviceOwnerHelper
import com.example.beholy.util.InAppLogger

/**
 * 权限与能力引导辅助类（新版）。
 *
 * 相对旧版的变化：
 * - **移除** 旧版录屏授权相关方法（requestScreenCapture），改为无障碍服务引导；
 * - **新增** isAccessibilityEnabled / openAccessibilitySettings（无障碍服务自检与跳转）；
 * - **新增** isDeviceOwner / deviceOwnerAdbCommand（Device Owner 自检与配置命令展示）。
 *
 * 离线铁律：本项目严禁联网，不申请任何网络权限（见 AndroidManifest）。
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
     * 无障碍服务是否已启用（本应用对应的 BeHolyAccessibilityService）。
     *
     * 双保险判断：
     * 1) 优先用 AccessibilityManager 列表；
     * 2) 部分国产 ROM / Android 版本上 getEnabledAccessibilityServiceList
     *    会返回空列表（即使服务已开启），此时回退读系统设置
     *    ENABLED_ACCESSIBILITY_SERVICES 字符串（系统设置页自身也用此值），
     *    避免「明明已开启却显示未开启」的误判。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return runCatching {
            val expectedId =
                "${context.packageName}/com.example.beholy.service.BeHolyAccessibilityService"

            // 方法1：AccessibilityManager 列表
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.id == expectedId }
            ) {
                return@runCatching true
            }

            // 方法2（回退）：直接读系统设置里已启用无障碍服务的组件串
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return@runCatching false
            enabled.split(":").any { it.equals(expectedId, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /**
     * 跳转系统无障碍设置页，引导用户开启本服务。
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            ?: InAppLogger.w("无法跳转无障碍设置")
    }

    /**
     * 当前应用是否已被设为 Device Owner（封禁/锁屏/重启能力的前提）。
     */
    fun isDeviceOwner(context: Context): Boolean = DeviceOwnerHelper.isDeviceOwner(context)

    /**
     * 一次性配置 Device Owner 的 adb 命令（用户需在电脑端执行）。
     */
    val deviceOwnerAdbCommand: String =
        "adb shell dpm set-device-owner com.example.beholy/.BeHolyAdminReceiver"

    companion object {
        const val REQ_NOTIFICATION = 1001
    }
}
