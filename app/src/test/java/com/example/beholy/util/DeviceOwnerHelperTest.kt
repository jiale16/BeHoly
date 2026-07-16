package com.example.beholy.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.example.beholy.ui.BeHolyAdminReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [DeviceOwnerHelper] 单元测试（Robolectric + Mockito）。
 *
 * 设计要点（见增量设计 §2.1 / §8.5）：所有**写操作**（lockNow/reboot/hideApp/unhideApp/isAppHidden）
 * 执行前都先判断 [DeviceOwnerHelper.isDeviceOwner]：
 * - 非 Device Owner → 降级为 no-op 并返回 false，且**绝不应**调用真正 DevicePolicyManager 方法；
 * - 是 Device Owner → 真实调用对应 DPM 方法并返回 true。
 *
 * 源码中 [DeviceOwnerHelper] 通过 `context.getSystemService(DEVICE_POLICY_SERVICE)` 取 DPM，
 * 本测试用 Robolectric 的 [shadowOf] 注入一个 Mockito mock DPM，避免改源码即可可控测试。
 *
 * 需要依赖（请在 build.gradle.kts 的 dependencies 增加，详见测试交付报告）：
 *   testImplementation("junit:junit:4.13.2")
 *   testImplementation("org.robolectric:robolectric:4.12.2") // 兼容 compileSdk 34
 *   testImplementation("org.mockito:mockito-core:5.11.0")
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], packageName = "com.example.beholy")
class DeviceOwnerHelperTest {

    @Mock
    private lateinit var dpm: DevicePolicyManager

    private lateinit var context: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        val app = RuntimeEnvironment.getApplication()
        context = app
        // 将 mock DPM 注入到 Application 的 system service 中
        shadowOf(app).setSystemService(Context.DEVICE_POLICY_SERVICE, dpm)
    }

    // ===================== isDeviceOwner =====================

    @Test
    fun isDeviceOwner_falseWhenDpmSaysNo() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.isDeviceOwner(context))
    }

    @Test
    fun isDeviceOwner_trueWhenDpmSaysYes() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        assertTrue(DeviceOwnerHelper.isDeviceOwner(context))
    }

    // ===================== 非 Device Owner：no-op =====================

    @Test
    fun nonDeviceOwner_lockNow_isNoOpAndDoesNotCallDpm() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.lockNow(context))
        verify(dpm, never()).lockNow()
    }

    @Test
    fun nonDeviceOwner_reboot_isNoOpAndDoesNotCallDpm() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.reboot(context))
        verify(dpm, never()).reboot(any())
    }

    @Test
    fun nonDeviceOwner_hideApp_isNoOpAndDoesNotCallDpm() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.hideApp(context, "com.target.app"))
        verify(dpm, never()).setApplicationHidden(any(), any(), any())
    }

    @Test
    fun nonDeviceOwner_unhideApp_isNoOp() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.unhideApp(context, "com.target.app"))
        verify(dpm, never()).setApplicationHidden(any(), any(), any())
    }

    @Test
    fun nonDeviceOwner_isAppHidden_returnsFalse() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(false)
        assertFalse(DeviceOwnerHelper.isAppHidden(context, "com.target.app"))
        verify(dpm, never()).isApplicationHidden(any(), any())
    }

    // ===================== 是 Device Owner：真实调用 =====================

    @Test
    fun deviceOwner_lockNow_callsDpmAndReturnsTrue() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        assertTrue(DeviceOwnerHelper.lockNow(context))
        verify(dpm).lockNow()
    }

    @Test
    fun deviceOwner_reboot_callsDpmWithNullAdminAndReturnsTrue() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        assertTrue(DeviceOwnerHelper.reboot(context))
        verify(dpm).reboot(isNull())
    }

    @Test
    fun deviceOwner_hideApp_callsSetApplicationHiddenTrue() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        `when`(dpm.setApplicationHidden(any(), any(), any())).thenReturn(true)
        assertTrue(DeviceOwnerHelper.hideApp(context, "com.target.app"))
        verify(dpm).setApplicationHidden(
            org.mockito.ArgumentMatchers.argThat { it is android.content.ComponentName },
            eq("com.target.app"),
            eq(true)
        )
    }

    @Test
    fun deviceOwner_unhideApp_callsSetApplicationHiddenFalse() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        `when`(dpm.setApplicationHidden(any(), any(), any())).thenReturn(true)
        assertTrue(DeviceOwnerHelper.unhideApp(context, "com.target.app"))
        verify(dpm).setApplicationHidden(any(), eq("com.target.app"), eq(false))
    }

    @Test
    fun deviceOwner_isAppHidden_delegatesToDpm() {
        `when`(dpm.isDeviceOwnerApp(context.packageName)).thenReturn(true)
        `when`(dpm.isApplicationHidden(any(), eq("com.target.app"))).thenReturn(true)
        assertTrue(DeviceOwnerHelper.isAppHidden(context, "com.target.app"))
        verify(dpm).isApplicationHidden(any(), eq("com.target.app"))
    }

    // ===================== 组件名 =====================

    @Test
    fun adminComponent_referencesBeHolyAdminReceiver() {
        val component = android.content.ComponentName(context, BeHolyAdminReceiver::class.java)
        assertNotNull(component)
        assertEquals("com.example.beholy", component.packageName)
        assertEquals(".BeHolyAdminReceiver", component.className)
    }
}
