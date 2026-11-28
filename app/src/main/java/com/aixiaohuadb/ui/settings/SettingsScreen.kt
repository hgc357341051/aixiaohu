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
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onTab: (String) -> Unit,
    onVoiceprint: () -> Unit,
    onMapping: () -> Unit,
    onAdb: () -> Unit,
    onOverlay: () -> Unit,
    onDebug: () -> Unit,
) {
    val config by vm.config.collectAsState()
    val controlMode by vm.controlMode.collectAsState()
    val voiceEnabled by vm.voiceEnabled.collectAsState()
    val extraGamePackages by vm.extraGamePackages.collectAsState()
    var pendingVoice by remember { mutableStateOf(false) }
    var packageInput by remember { mutableStateOf("") }
    ScreenFrame(
        title = "设置",
        subtitle = "本地持久化 · ${BuildConfig.FLAVOR_NAME}",
        activeTab = "settings",
        onTab = onTab,
    ) {
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

        SectionCard(icon = Icons.Filled.SportsEsports, title = "游戏识别") {
            Text(
                "已内置王者荣耀正式服 / 体验服 / 国际服。渠道服或其它游戏在此登记包名，悬浮按钮才会只在该游戏前台显示。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            if (extraGamePackages.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                extraGamePackages.toList().sorted().forEach { pkg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            pkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.removeGamePackage(pkg) }) {
                            Text("移除", color = androidx.compose.ui.graphics.Color(0xFFFF6B6B))
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = packageInput,
                onValueChange = { packageInput = it },
                label = { Text("游戏包名") },
                placeholder = { Text("com.example.game") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "登记包名", onClick = {
                    vm.addGamePackage(packageInput)
                    packageInput = ""
                }, enabled = packageInput.isNotBlank())
            }
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
