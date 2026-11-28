package com.aixiaohuadb.util

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aixiaohuadb.service.VoiceAccessibilityService

/**
 * 权限状态单一真源。集中所有"系统权限是否已授予"的检测与跳转 Intent，
 * 供引导权限页与悬浮链路页共用，避免各处各写一套导致误判。
 */
object PermissionState {

    /** 无障碍服务的组件名，与 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 中的格式一致 */
    private fun accessibilityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, VoiceAccessibilityService::class.java.name)

    /**
     * 无障碍是否已开启。
     *
     * 不能只看 VoiceAccessibilityService.instance != null —— 那只代表服务当前已绑定，
     * 用户在系统设置里开启了但进程刚启动 / 服务未拉起时会误报"未授予"。
     * 以系统启用列表为准。
     */
    fun accessibilityEnabled(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = accessibilityComponent(context)
        enabled.split(':').any { raw ->
            val entry = raw.trim()
            entry.equals(component.flattenToString(), ignoreCase = true) ||
                entry.equals(component.flattenToString(), ignoreCase = false) ||
                ComponentName.unflattenFromString(entry) == component
        }
    }.getOrDefault(false)

    /** 悬浮窗权限 */
    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 麦克风（录音）权限 */
    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 通知权限：API 33 以下随系统默认授予 */
    fun notificationGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 使用情况访问：前台游戏检测兜底通道（无障碍之外二选一） */
    fun usageAccessGranted(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * 后台运行（电池优化白名单）：已加入白名单 = 不受 Doze 限制。
     * 不支持该能力的 ROM 视为已豁免，避免永远显示"待申请"。
     */
    fun batteryOptimizationIgnored(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    // ---- 跳转 Intent ----

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** 电池优化白名单：优先直接跳到本应用的忽略弹窗，失败回退到通用设置 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
