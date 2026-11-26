package com.example.voicectl.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.voicectl.service.VoiceAccessibilityService
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.PrimaryButton
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.OkBg
import com.example.voicectl.ui.theme.Ok
import com.example.voicectl.ui.theme.WarnBg
import com.example.voicectl.ui.theme.Warn

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
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        InfoCard {
            PermissionLine("麦克风", "语音唤醒与指令采集，端侧处理", micGranted) {
                launcher.launch(
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        arrayOf(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
            PermissionLine("无障碍服务", "执行换装点击（备用链路）", a11yGranted) {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            PermissionLine("悬浮窗", "悬浮球与状态提示，不遮挡 HUD", overlayGranted) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.packageName),
                )
                runCatching { context.startActivity(intent) }
            }
            PermissionLine("通知 / 前台服务", "常驻唤醒与状态通知", notifGranted) {
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
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = onManualOnly,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text("仅手动模式进入", color = Brand) }
        Spacer(Modifier.height(12.dp))
        WarningCard("拒绝麦克风后自动进入手动模式；拒绝无障碍后只走 ADB / 只读提示链路，不影响面板直选。")
    }
}

@Composable
private fun PermissionLine(title: String, desc: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Text(desc, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Muted)
        }
        androidx.compose.material3.TextButton(onClick = onClick) {
            PillChip(
                text = if (granted) "已授予" else "待申请",
                container = if (granted) OkBg else WarnBg,
                content = if (granted) Ok else Warn,
            )
        }
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:" + context.packageName)
    }
    runCatching { context.startActivity(intent) }
}
