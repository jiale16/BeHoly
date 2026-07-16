package com.example.beholy.ui

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.example.beholy.util.InAppLogger

/**
 * Device Admin / Device Owner 接收器。
 *
 * 仅作为 Device Owner 的承载组件：通过 `adb shell dpm set-device-owner
 * com.example.beholy/.BeHolyAdminReceiver` 一次性激活后，[DeviceOwnerHelper]
 * 方可执行 setApplicationHidden / lockNow / reboot 等管控能力。
 *
 * 本类保持极简：仅记录启用/停用日志，不拦截任何系统策略回调。
 */
class BeHolyAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        InAppLogger.i("设备管理员已启用（可作为 Device Owner 候选）")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        InAppLogger.i("设备管理员已停用")
    }
}
