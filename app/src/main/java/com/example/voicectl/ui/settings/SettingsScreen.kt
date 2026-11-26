package com.example.voicectl.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicectl.BuildConfig
import com.example.voicectl.config.Sensitivity
import com.example.voicectl.config.WakeMode
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.Segment
import com.example.voicectl.ui.common.ToggleRow
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.Muted

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onTab: (String) -> Unit,
    onVoiceprint: () -> Unit,
    onMapping: () -> Unit,
    onAdb: () -> Unit,
    onDebug: () -> Unit,
) {
    val config by vm.config.collectAsState()
    ScreenFrame(
        title = "设置",
        subtitle = "本地持久化 · ${BuildConfig.FLAVOR_NAME}",
        activeTab = "settings",
        onTab = onTab,
    ) {
        SectionTitle("语音唤醒")
        InfoCard {
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                ToggleRow("语音唤醒开关", "默认 ON，即时生效", config.voiceWakeup.enabled) { vm.setWakeEnabled(it) }
                Text("运行模式", style = MaterialTheme.typography.bodySmall)
                Text("voice / manual / manual_silent", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                Segment(
                    options = listOf("voice", "manual", "silent"),
                    selectedIndex = config.voiceWakeup.mode.ordinal,
                    onSelect = { vm.setWakeMode(WakeMode.entries[it]) },
                )
                Spacer(Modifier.height(12.dp))
                Text("灵敏度档位", style = MaterialTheme.typography.bodySmall)
                Text("保守 / 标准 / 灵敏，控制 FAU 与召回", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(6.dp))
                Segment(
                    options = listOf("保守", "标准", "灵敏"),
                    selectedIndex = config.sensitivity.ordinal,
                    onSelect = { vm.setSensitivity(Sensitivity.entries[it]) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        SectionTitle("反馈与交互")
        InfoCard {
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
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
                Text("控制词：退出唤醒 / 退出识别 / 暂停 / 恢复", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(8.dp))
            }
        }

        SectionTitle("身份与安全")
        InfoCard {
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                ToggleRow("单人声纹门控", "唤醒与执行双重 1:1 校验", config.speaker.enabled) {
                    vm.setSpeakerEnabled(it)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    androidx.compose.material3.TextButton(onClick = onVoiceprint) {
                        Text("声纹注册 / 管理", color = Brand)
                    }
                    androidx.compose.material3.TextButton(onClick = onMapping) {
                        Text("指令映射 / 坐标库", color = Brand)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    androidx.compose.material3.TextButton(onClick = onAdb) { Text("ADB 连接", color = Brand) }
                }
            }
        }

        SectionTitle("高级")
        InfoCard {
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                Text("推理 delegate", style = MaterialTheme.typography.bodySmall)
                Text(
                    "当前实现：${if (vm.isDemo) "Demo" else "TFLite"} · 模型 ${vm.modelVersion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Spacer(Modifier.height(6.dp))
                Segment(
                    options = listOf("AUTO", "GPU", "NNAPI", "CPU"),
                    selectedIndex = config.preferredDelegate.ordinal,
                    onSelect = { vm.setDelegate(Delegate.entries[it]) },
                )
                Text(
                    "TFLite 按 GPU → NNAPI → CPU 自动降级；设置页显示实际生效档位。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (BuildConfig.DEBUG_PAGE_VISIBLE) {
                    androidx.compose.material3.TextButton(onClick = onDebug) {
                        Text("调试页（dev）", color = Brand)
                    }
                }
                Spacer(Modifier.height(6.dp))
                PillChip("voice-control v0.1.0 · ${vm.modelVersion}")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
