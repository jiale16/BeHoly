package com.example.beholy.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.beholy.data.Constants
import com.example.beholy.data.DetectionResult
import com.example.beholy.ui.MainActivity
import com.example.beholy.ui.PermissionHelper
import com.example.beholy.util.DeviceOwnerHelper
import com.example.beholy.util.DisposalExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeFalse

/**
 * 分级处置链路仪表化测试（instrumented，需真机/模拟器）。
 *
 * 运行方式（本环境无法实跑，需在本地 Android SDK + 真机执行）：
 *   ./gradlew connectedAndroidTest
 *
 * 真机前置条件（见增量设计 §1.4 / §R3）：
 *   - 已开启 BeHoly 无障碍服务；
 *   - 已 `adb shell dpm set-device-owner com.example.beholy/.BeHolyAdminReceiver`（可选，解锁封禁/锁屏/重启）。
 *
 * 覆盖范围（见增量设计 §4 调用流程）：
 * 1. DeviceOwnerHelper 在**非 DO**真机上为 no-op（不锁屏/不重启/不封禁）；
 * 2. MonitoringService 服务指令 action 契约（与 DisposalExecutor 路由一致）；
 * 3. PermissionHelper 的 Device Owner adb 配置命令文案正确；
 * 4. 非 DO 下 Tier2 经 DisposalExecutor 降级为 Tier1（HOME + 悔改提示）且不抛异常（冒烟）。
 *
 * 说明：Tier1 命中 → GLOBAL_ACTION_HOME + 悔改页 的完整 UI 流程依赖无障碍服务上下文，
 * 难以在纯单测中触发；本测试以「契约 + 降级冒烟」覆盖，完整链路由真机手动回归（R3）。
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityDisposalTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ===== 1. DeviceOwnerHelper 在真机非 DO 下为 no-op（不锁屏/不重启/不封禁）=====
    @Test
    fun deviceOwnerHelper_nonDeviceOwner_isNoOp() {
        val isDo = DeviceOwnerHelper.isDeviceOwner(context)
        // 仅在非 DO 设备上严格断言 no-op，避免自动化测试误锁屏/重启设备
        assumeFalse("本机已配置 Device Owner，跳过 no-op 断言以免副作用", isDo)
        assertFalse(DeviceOwnerHelper.lockNow(context))
        assertFalse(DeviceOwnerHelper.reboot(context))
        assertFalse(DeviceOwnerHelper.hideApp(context, "com.android.chrome"))
        assertFalse(DeviceOwnerHelper.unhideApp(context, "com.android.chrome"))
        assertFalse(DeviceOwnerHelper.isAppHidden(context, "com.android.chrome"))
    }

    // ===== 2. MonitoringService 服务指令 action 契约（与 DisposalExecutor 路由一致）=====
    @Test
    fun monitoringService_actionConstants_areValidAndDistinct() {
        assertNotNull(MonitoringService.ACTION_SHOW_REPENTANCE)
        assertNotNull(MonitoringService.ACTION_DISPOSE)
        assertTrue(MonitoringService.ACTION_SHOW_REPENTANCE.isNotEmpty())
        assertTrue(MonitoringService.ACTION_DISPOSE.isNotEmpty())
        assertEquals("com.example.beholy.action.SHOW_REPENTANCE", MonitoringService.ACTION_SHOW_REPENTANCE)
        assertEquals("com.example.beholy.action.DISPOSE", MonitoringService.ACTION_DISPOSE)
        assertTrue(MonitoringService.ACTION_SHOW_REPENTANCE != MonitoringService.ACTION_DISPOSE)
    }

    // ===== 3. Device Owner 配置命令文案正确（引导用户 adb 一次性 set-device-owner）=====
    @Test
    fun permissionHelper_deviceOwnerAdbCommand_isCorrect() {
        var command = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                command = PermissionHelper(activity).deviceOwnerAdbCommand
            }
        }
        assertEquals(
            "adb shell dpm set-device-owner com.example.beholy/.BeHolyAdminReceiver",
            command
        )
    }

    // ===== 4. 非 DO 下 Tier2 降级为 Tier1 不抛异常（冒烟）=====
    @Test
    fun disposalExecutor_tier2DegradesWithoutCrash_onNonDoDevice() {
        assumeFalse(
            "已配置 DO 时该路径会真实封禁/锁屏，跳过冒烟",
            DeviceOwnerHelper.isDeviceOwner(context)
        )
        val fakeService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }
        val result = DetectionResult(
            tier = Constants.TIER2,
            packageName = "com.target.app",
            matchedWords = listOf("色情"),
            source = Constants.SOURCE_TEXT,
            reason = "敏感文字：色情"
        )
        // 非 DO：DisposalExecutor 应降级为 Tier1（HOME + 悔改提示），不应抛异常
        DisposalExecutor.execute(fakeService, context, result)
    }
}
