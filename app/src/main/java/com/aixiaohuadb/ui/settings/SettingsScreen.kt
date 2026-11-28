package com.aixiaohuadb.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.BuildConfig
import com.aixiaohuadb.config.Sensitivity
import com.aixiaohuadb.config.WakeMode
import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.ui.common.PillChip
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.Segment
import com.aixiaohuadb.ui.common.ToggleRow
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Warn

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onTab: (String) -> Unit,
    onVoiceprint: () -> Unit,
    onMapping: () -> Unit,
    onAdb: () -> Unit,
    onOverlay: () -> Unit,
    onPermissions: () -> Unit,
    onDebug: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRevision++
                // 从系统设置授权返回后立即让服务重建悬浮件，避免"授权了但球不回来"
                com.aixiaohuadb.service.OverlayControlService.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 读取 revision 触发重组，实时反映权限状态
    permissionRevision
    val config by vm.config.collectAsState()
    val controlMode by vm.controlMode.collectAsState()
    val voiceEnabled by vm.voiceEnabled.collectAsState()
    var pendingVoice by remember { mutableStateOf(false) }
    ScreenFrame(
        title = "设置",
        subtitle = "本地持久化 · ${BuildConfig.FLAVOR_NAME}",
        activeTab = "settings",
        onTab = onTab,
    ) {
        val corePerms = listOf(
            "悬浮窗" to com.aixiaohuadb.util.PermissionState.overlayGranted(context),
            "无障碍" to com.aixiaohuadb.util.PermissionState.accessibilityEnabled(context),
            "后台运行" to com.aixiaohuadb.util.PermissionState.batteryOptimizationIgnored(context),
        )
        val coreGranted = corePerms.count { it.second }
        SectionCard(icon = Icons.Filled.Security, title = "权限中心") {
            Text(
                "核心权限 $coreGranted/${corePerms.size} 项就绪。悬浮窗/无障碍被系统回收后，悬浮按钮会失效。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.xs))
            corePerms.forEach { (name, ok) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "${if (ok) "●" else "○"} $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) androidx.compose.ui.graphics.Color(0xFF3DF5C8) else Warn,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (ok) "已授予" else "待授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) Muted else Warn,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            QuietButton(text = if (coreGranted < corePerms.size) "前往授权 / 重新申请" else "查看全部权限", onClick = onPermissions)
        }

        SectionCard(icon = Icons.Filled.VideogameAsset, title = "控制模式") {
            Text(
                "默认无障碍模式，不拾音；语音需显式确认；ADB 保持后台长连接。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Segment(
                options = ControlMode.entries.map { it.label },
                selectedIndex = ControlMode.entries.indexOf(controlMode),
                onSelect = { index ->
                    val target = ControlMode.entries[index]
                    if (target == ControlMode.VOICE && !voiceEnabled) pendingVoice = true
                    else vm.setControlMode(target)
                },
            )
        }

        SectionCard(icon = Icons.Filled.Mic, title = "语音识别") {
            ToggleRow(
                "语音识别总开关",
                "默认关闭；开启才会启动麦克风、模型与常驻通知",
                voiceEnabled,
            ) { enabled ->
                if (enabled && !voiceEnabled) pendingVoice = true else vm.setVoiceEnabled(enabled)
            }
            ToggleRow("语音唤醒开关", "总开关开启后才可能生效", config.voiceWakeup.enabled) { vm.setWakeEnabled(it) }
            SubLabel("运行模式", "voice / manual / manual_silent")
            Segment(
                options = listOf("voice", "manual", "silent"),
                selectedIndex = config.voiceWakeup.mode.ordinal,
                onSelect = { vm.setWakeMode(WakeMode.entries[it]) },
            )
            Spacer(Modifier.height(Spacing.md))
            SubLabel("灵敏度档位", "保守 / 标准 / 灵敏，控制 FAU 与召回")
            Segment(
                options = listOf("保守", "标准", "灵敏"),
                selectedIndex = config.sensitivity.ordinal,
                onSelect = { vm.setSensitivity(Sensitivity.entries[it]) },
            )
        }

        SectionCard(icon = Icons.Filled.NotificationsActive, title = "反馈与交互") {
            ToggleRow("TTS 反馈", "执行结果语音提示", config.feedbackTts) {
                vm.setFeedback(SettingsViewModel.FeedbackKind.TTS, it)
            }
            ToggleRow("震动反馈", "执行成功 / 失败震动", config.feedbackVibration) {
                vm.setFeedback(SettingsViewModel.FeedbackKind.VIBRATION, it)
            }
            ToggleRow("悬浮窗反馈", "不遮挡游戏 HUD", config.feedbackOverlay) {
                vm.setFeedback(SettingsViewModel.FeedbackKind.OVERLAY, it)
            }
            ToggleRow("首次退出二次确认", "防止误说导致不可用", config.controlWords.c1ConfirmOnFirst) {
                vm.setExitConfirm(it)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "悬浮控制", onClick = onOverlay)
            }
            Text(
                "控制词：退出唤醒 / 退出识别 / 暂停 / 恢复",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        SectionCard(icon = Icons.Filled.Security, title = "身份与安全") {
            ToggleRow("单人声纹门控", "唤醒与执行双重 1:1 校验", config.speaker.enabled) {
                vm.setSpeakerEnabled(it)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "声纹注册 / 管理", onClick = onVoiceprint)
                Spacer(Modifier.width(Spacing.sm))
                QuietButton(text = "指令映射 / 坐标库", onClick = onMapping)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "无线 ADB 连接", onClick = onAdb)
            }
        }

        SectionCard(icon = Icons.Filled.Tune, title = "高级") {
            SubLabel(
                "推理 delegate",
                "当前实现：${if (vm.isDemo) "Demo" else "TFLite"} · 模型 ${vm.modelVersion}",
            )
            Segment(
                options = listOf("AUTO", "GPU", "NNAPI", "CPU"),
                selectedIndex = config.preferredDelegate.ordinal,
                onSelect = { vm.setDelegate(Delegate.entries[it]) },
            )
            Text(
                "TFLite 按 GPU → NNAPI → CPU 自动降级；设置页显示实际生效档位。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            if (BuildConfig.DEBUG_PAGE_VISIBLE) {
                Spacer(Modifier.height(Spacing.sm))
                QuietButton(text = "调试页（dev）", onClick = onDebug)
            }
            Spacer(Modifier.height(Spacing.sm))
            PillChip("voice-control v0.1.0 · ${vm.modelVersion}")
        }
        if (pendingVoice) {
            AlertDialog(
                onDismissRequest = { pendingVoice = false },
                title = { Text("开启语音识别？") },
                text = { Text("将启动麦克风拾音、加载语音模型并显示常驻通知。关闭后物理释放，不会后台监听。") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingVoice = false
                        vm.setVoiceEnabled(true)
                    }) { Text("确认开启") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingVoice = false }) { Text("取消") }
                },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun SubLabel(title: String, desc: String) {
    Column(modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
