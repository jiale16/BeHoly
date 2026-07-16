package com.example.beholy.util

import android.content.ComponentName
import android.content.Context
import android.app.admin.DevicePolicyManager
import com.example.beholy.ui.BeHolyAdminReceiver

/**
 * Device Owner 能力封装（object）。
 *
 * 封装 setApplicationHidden / lockNow / reboot 等仅 Device Owner（或 Profile Owner）
 * 可用的管控能力。所有**写操作**在执行前都会先判断 [isDeviceOwner]：
 * - 是 Device Owner → 真实执行；
 * - 不是 Device Owner → 降级为 no-op 并返回 false，由调用方（[DisposalExecutor]）
 *   决定是否退化为 Tier1 行为（仅 HOME + 悔改提示），并在日志标注「未配置 Device Owner」。
 *
 * 离线铁律：仅使用本地 DevicePolicyManager API，无任何网络调用。
 */
object DeviceOwnerHelper {

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context, BeHolyAdminReceiver::class.java)

    /** 当前应用是否为 Device Owner */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        dpm(context).isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /** 立即锁屏。非 DO 时降级跳过并返回 false。 */
    fun lockNow(context: Context): Boolean {
        if (!isDeviceOwner(context)) {
            InAppLogger.w("非设备所有者，lockNow 降级跳过")
            return false
        }
        return runCatching { dpm(context).lockNow(); true }.getOrDefault(false)
    }

    /** 重启设备。非 DO 时降级跳过并返回 false。 */
    fun reboot(context: Context): Boolean {
        if (!isDeviceOwner(context)) {
            InAppLogger.w("非设备所有者，reboot 降级跳过")
            return false
        }
        return runCatching {
            // admin 传本应用组件：本应用即 Device Owner，传它完全正确
            dpm(context).reboot(adminComponent(context))
            true
        }.getOrDefault(false)
    }

    /** 封禁（隐藏）指定包：进程被杀、桌面不可见、无法打开。非 DO 时返回 false。 */
    fun hideApp(context: Context, pkg: String): Boolean {
        if (!isDeviceOwner(context)) {
            InAppLogger.w("非设备所有者，hideApp 降级跳过：$pkg")
            return false
        }
        return runCatching {
            dpm(context).setApplicationHidden(adminComponent(context), pkg, true)
            InAppLogger.i("已封禁应用：$pkg")
            true
        }.getOrDefault(false)
    }

    /** 解封（取消隐藏）指定包。非 DO 时返回 false。 */
    fun unhideApp(context: Context, pkg: String): Boolean {
        if (!isDeviceOwner(context)) return false
        return runCatching {
            dpm(context).setApplicationHidden(adminComponent(context), pkg, false)
            true
        }.getOrDefault(false)
    }

    /** 查询指定包是否被封禁。非 DO 时返回 false。 */
    fun isAppHidden(context: Context, pkg: String): Boolean {
        if (!isDeviceOwner(context)) return false
        return runCatching {
            dpm(context).isApplicationHidden(adminComponent(context), pkg)
        }.getOrDefault(false)
    }
}
