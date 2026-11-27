package com.aixiaohuadb.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aixiaohuadb.service.VoiceAccessibilityService
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    onGranted: () -> Unit,
    onManualOnly: () -> Unit,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { revision++ }

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val notifGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
    val a11yGranted = VoiceAccessibilityService.instance != null
    val overlayGranted = Settings.canDrawOverlays(context)
    revision // 读取以触发重组

    ScreenFrame(
        title = "权限申请",
        subtitle = "按需申请 · 可随时撤回",
        showBack = true,
        onBack = onBack,
        activeTab = "",
    ) {
        InfoCard {
            Text(
                "权限只在对应模式下使用，可随时到系统设置撤回。拒绝后自动降级到手动 / 无语音面板。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        InfoCard {
            PermissionLine(
                icon = Icons.Filled.Mic,
                title = "麦克风",
                desc = "语音唤醒与指令采集，端侧处理",
                granted = micGranted,
            ) {
                launcher.launch(
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
            PermissionLine(
                icon = Icons.Filled.AccessibilityNew,
                title = "无障碍服务",
                desc = "执行换装点击（备用链路）",
                granted = a11yGranted,
            ) {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            PermissionLine(
                icon = Icons.Filled.Layers,
                title = "悬浮窗",
                desc = "悬浮球与状态提示，不遮挡 HUD",
                granted = overlayGranted,
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName),
                )
                runCatching { context.startActivity(intent) }
            }
            PermissionLine(
                icon = Icons.Filled.Notifications,
                title = "通知 / 前台服务",
                desc = "常驻唤醒与状态通知",
                granted = notifGranted,
            ) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    openAppDetails(context)
                }
            }
        }
        PrimaryButton("全部允许并进入主界面", onClick = {
            if (micGranted) onGranted() else {
                launcher.launch(
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        })
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(text = "仅手动模式进入", onClick = onManualOnly)
        Spacer(Modifier.height(Spacing.md))
        WarningCard("拒绝麦克风后自动进入手动模式；拒绝无障碍后只走内嵌 ADB / 只读提示链路，不影响面板直选。")
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
            .padding(vertical = Spacing.sm)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(
            icon = icon,
            tint = if (granted) com.aixiaohuadb.ui.theme.Ok else Brand,
            container = if (granted) com.aixiaohuadb.ui.theme.OkBg else BrandSoft,
            size = 38.dp,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.width(Spacing.sm))
        StatusBadge(
            text = if (granted) "已授予" else "待申请",
            tone = if (granted) Tone.OK else Tone.WARN,
            icon = if (granted) Icons.Filled.CheckCircle else null,
        )
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:" + context.packageName)
    }
    runCatching { context.startActivity(intent) }
}
