package com.example.voicectl.ui.settings

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
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.QuietButton
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.SectionCard
import com.example.voicectl.ui.common.Segment
import com.example.voicectl.ui.common.ToggleRow
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Spacing

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
        SectionCard(icon = Icons.Filled.Mic, title = "语音唤醒") {
            ToggleRow("语音唤醒开关", "默认 ON，即时生效", config.voiceWakeup.enabled) { vm.setWakeEnabled(it) }
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
                QuietButton(text = "ADB 连接", onClick = onAdb)
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
