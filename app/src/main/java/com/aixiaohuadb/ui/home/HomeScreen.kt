package com.aixiaohuadb.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.machine.UiStateInfo
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.ui
import com.aixiaohuadb.ui.AppViewModel
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

@Composable
fun HomeScreen(
    uiState: AppViewModel.UiState,
    onTab: (String) -> Unit,
    onNotification: () -> Unit,
    onOpenWindow: () -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
    onResume: () -> Unit,
) {
    var gateExpanded by remember { mutableStateOf(false) }
    val logs = uiState.logs

    val rawInfo = uiState.voiceState.ui()
    // 执行态标题不得固定「换名刀」：UiState 无当前指令字段，统一改为中性安全文案
    val info: UiStateInfo = if (rawInfo.key == "executing") {
        rawInfo.copy(
            title = "指令执行中",
            desc = "点击序列执行中 · 具体指令以最近指令为准",
            notif = "● 执行中 · 指令序列",
        )
    } else {
        rawInfo
    }

    ScreenFrame(
        title = "小胡",
        subtitle = "语音换装助手",
        activeTab = "home",
        onTab = onTab,
    ) {
        // 模式条：点击进入通知 shade
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNotification() }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = Icons.Filled.Notifications, tint = Brand, container = BrandSoft, size = 30.dp)
            Spacer(Modifier.width(Spacing.sm))
            StatusBadge(text = info.mode, tone = Tone.BRAND)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                info.modeDesc,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        // 真实运行状态：全部来自 uiState.metrics，无数据显示「未采集 / 未接入」
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.xs),
        ) {
            StatusBadge(
                text = if (uiState.metrics.audioCapturing) "麦克风采集中" else "麦克风未采集",
                tone = if (uiState.metrics.audioCapturing) Tone.OK else Tone.NEUTRAL,
            )
            StatusBadge(
                text = if (uiState.isDemo) "Demo ${uiState.metrics.modelVersion}" else uiState.metrics.modelVersion,
                tone = if (uiState.isDemo) Tone.WARN else Tone.BRAND,
            )
            StatusBadge(
                text = if (uiState.metrics.adbConnected) "ADB 已连接" else "ADB 未连接",
                tone = if (uiState.metrics.adbConnected) Tone.OK else Tone.NEUTRAL,
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        StateCard(info)

        Spacer(Modifier.height(Spacing.md))
        PrimaryButton(text = "开启 3s 指令窗", onClick = onOpenWindow)

        Spacer(Modifier.height(Spacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            SecondaryAction(
                text = "暂停语音",
                icon = Icons.Filled.PauseCircle,
                modifier = Modifier.weight(1f),
                onClick = onPause,
            )
            Spacer(Modifier.width(Spacing.sm))
            SecondaryAction(
                text = "退出唤醒",
                icon = Icons.Filled.PowerOff,
                modifier = Modifier.weight(1f),
                tone = Tone.DANGER,
                onClick = onExit,
            )
        }

        if (uiState.voiceState == VoiceState.MANUAL_SILENT) {
            Spacer(Modifier.height(Spacing.sm))
            PrimaryButton(
                text = "手动恢复语音（悬浮球 / 通知 / 设置均可）",
                onClick = onResume,
            )
        }

        SectionCard(
            icon = Icons.Filled.CheckCircle,
            title = "执行状态门控",
            tone = Tone.WARN,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { gateExpanded = !gateExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBadge(text = "门控状态未接入 · 仅手动确认", tone = Tone.WARN)
                Spacer(Modifier.weight(1f))
                Text(
                    if (gateExpanded) "收起" else "说明",
                    color = Brand,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (gateExpanded) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "对局中 / 英雄存活 / 持有装备 / 坐标表版本的实时门控条件尚未接入，当前不做自动判定。" +
                        "执行仅依据手动开窗与已确认的指令，不会伪装为「门控全部通过」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }

        SectionTitle("最近指令")
        InfoCard {
            if (logs.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(
                        icon = Icons.Filled.Schedule,
                        tint = Muted,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        size = 32.dp,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text("暂无指令记录", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "语音指令 / 手动面板执行后会在此显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            } else {
                logs.takeLast(4).asReversed().forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Brand, androidx.compose.foundation.shape.CircleShape),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.BRAND,
    onClick: () -> Unit,
) {
    val fg = when (tone) {
        Tone.DANGER -> com.aixiaohuadb.ui.theme.Danger
        else -> Brand
    }
    val bg = when (tone) {
        Tone.DANGER -> com.aixiaohuadb.ui.theme.DangerBg
        else -> BrandSoft
    }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = fg,
        ),
        shape = com.aixiaohuadb.ui.theme.ButtonShape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            fg.copy(alpha = 0.3f),
        ),
        elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}
