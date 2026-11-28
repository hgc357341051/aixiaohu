package com.aixiaohuadb.ui.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.Ink
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Ok
import com.aixiaohuadb.ui.theme.OkBg
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Warn
import com.aixiaohuadb.ui.theme.WarnBg
import com.aixiaohuadb.util.PermissionState

/**
 * GAME OPS 权限中心：六项权限集中呈现，状态取自 [PermissionState] 单一真源。
 *
 * 分两组：
 * - 核心链路（悬浮窗 / 无障碍 / 后台运行）—— 不授予则悬浮控制与长连接不可用；
 * - 增强能力（麦克风 / 通知 / 使用情况）—— 语音与前台兜底，可按需授予。
 *
 * 返回前台自动刷新状态：系统设置页授予后回到本页即时更新，无需手动重启。
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    onGranted: () -> Unit,
    onManualOnly: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { revision++ }

    // 从系统设置返回前台时刷新所有权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 读取 revision 触发重组
    revision
    val overlay = PermissionState.overlayGranted(context)
    val a11y = PermissionState.accessibilityEnabled(context)
    val battery = PermissionState.batteryOptimizationIgnored(context)
    val mic = PermissionState.micGranted(context)
    val notif = PermissionState.notificationGranted(context)
    val usage = PermissionState.usageAccessGranted(context)

    val core = listOf(overlay, a11y, battery)
    val enhanced = listOf(mic, notif, usage)
    val grantedCount = core.count { it } + enhanced.count { it }
    val total = core.size + enhanced.size

    ScreenFrame(
        title = "权限中心",
        subtitle = "LINK ACCESS · 逐项授权，可随时撤回",
        showBack = true,
        onBack = onBack,
        activeTab = "",
    ) {
        // 概览
        PermissionOverview(granted = grantedCount, total = total)

        InfoCard {
            Text(
                "核心权限决定悬浮控制与后台链路能否工作；增强权限仅在对应模式下启用。" +
                    "逐项点击开启后返回本页，状态会自动刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }

        OpsSectionLabel(title = "核心链路 · 必配", trailing = "${core.count { it }}/${core.size}")

        PermissionLine(
            icon = Icons.Filled.Layers,
            title = "悬浮窗",
            desc = "显示悬浮球与宏指令按钮；不授予则无法在游戏上操作",
            granted = overlay,
            onClick = { openSafely(context, PermissionState.overlaySettingsIntent(context)) },
        )
        PermissionLine(
            icon = Icons.Filled.AccessibilityNew,
            title = "无障碍服务",
            desc = "手势转发与前台跟踪；宏回放的备用执行链路",
            granted = a11y,
            onClick = { openSafely(context, PermissionState.accessibilitySettingsIntent()) },
        )
        PermissionLine(
            icon = Icons.Filled.BatterySaver,
            title = "后台运行（电池优化白名单）",
            desc = "避免系统休眠切断 ADB 长连接与常驻通知",
            granted = battery,
            onClick = { openSafely(context, PermissionState.batteryOptimizationIntent(context)) },
        )

        Spacer(Modifier.height(Spacing.sm))
        OpsSectionLabel(title = "增强能力 · 按需", trailing = "${enhanced.count { it }}/${enhanced.size}")

        PermissionLine(
            icon = Icons.Filled.Mic,
            title = "麦克风",
            desc = "语音唤醒与指令采集，端侧处理不上传",
            granted = mic,
            onClick = {
                runtimeLauncher.launch(
                    if (Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    },
                )
            },
        )
        PermissionLine(
            icon = Icons.Filled.Notifications,
            title = "通知 / 前台服务",
            desc = "常驻状态通知，保持后台链路可见",
            granted = notif,
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    openSafely(context, PermissionState.appDetailsIntent(context))
                }
            },
        )
        PermissionLine(
            icon = Icons.Filled.QueryStats,
            title = "使用情况访问",
            desc = "前台游戏检测的兜底通道；无障碍已开启时可不授",
            granted = usage,
            onClick = { openSafely(context, PermissionState.usageAccessSettingsIntent()) },
        )

        Spacer(Modifier.height(Spacing.md))
        if (!overlay || !a11y) {
            WarningCard("悬浮窗或无障碍未授予时，悬浮球与宏指令按钮无法工作；仍可进入主界面手动查看。")
            Spacer(Modifier.height(Spacing.sm))
        }
        PrimaryButton("进入主界面", onClick = onGranted)
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(text = "仅手动模式进入", onClick = onManualOnly)
    }
}

@Composable
private fun PermissionOverview(granted: Int, total: Int) {
    val ratio = if (total == 0) 0f else granted.toFloat() / total.toFloat()
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("权限就绪度", style = MaterialTheme.typography.titleSmall, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$granted / $total 项已授予",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted == total) Ok else Warn,
                )
            }
            StatusBadge(
                text = if (granted == total) "全部就绪" else "待配置",
                tone = if (granted == total) Tone.OK else Tone.WARN,
                icon = if (granted == total) Icons.Filled.CheckCircle else null,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Line, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(6.dp)
                    .background(if (granted == total) Ok else Brand, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun PermissionLine(
    icon: ImageVector,
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(if (granted) OkBg else WarnBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (granted) Ok else Warn,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Ink)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.width(Spacing.sm))
        StatusBadge(
            text = if (granted) "已授予" else "去开启",
            tone = if (granted) Tone.OK else Tone.WARN,
            icon = if (granted) Icons.Filled.CheckCircle else null,
        )
    }
}

private fun openSafely(context: android.content.Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
}
