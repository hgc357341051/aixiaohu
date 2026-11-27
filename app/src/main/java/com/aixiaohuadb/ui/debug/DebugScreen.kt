package com.aixiaohuadb.ui.debug

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.ui.AppViewModel
import com.aixiaohuadb.ui.common.CodeBlock
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.PillChip
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.Info
import com.aixiaohuadb.ui.theme.LgShape
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PillShape
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.StWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun DebugScreen(
    vm: DebugViewModel,
    appVm: AppViewModel,
    onTab: (String) -> Unit,
) {
    val logs by vm.logs.collectAsState()
    val uiState by appVm.ui.collectAsState()
    val metrics = uiState.metrics
    // lastUpdateMs==0 表示尚无真实判决快照，此时一律显示「—」，不编造数值
    val hasSnapshot = metrics.lastUpdateMs > 0L

    val transition = rememberInfiniteTransition(label = "scope")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Restart),
        label = "scope-phase",
    )

    ScreenFrame(title = "调试页", subtitle = "dev flavor · 仅自用", activeTab = "debug", onTab = onTab) {
        WarningCard("置信度与判决指标来自最近一次真实判决快照，尚无判决时显示「—」；下方波形为示意动画，不代表真实音频采集。")

        // 引擎 / 数据来源：真实字段
        SectionCard(icon = Icons.Filled.BugReport, title = "引擎与数据来源") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模型版本", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(metrics.modelVersion, style = MaterialTheme.typography.bodySmall)
                }
                StatusBadge(
                    text = if (uiState.isDemo) "Demo 引擎" else "TFLite 引擎",
                    tone = if (uiState.isDemo) Tone.WARN else Tone.OK,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatusBadge(
                    text = if (metrics.audioCapturing) "麦克风：采集中" else "麦克风：未采集",
                    tone = if (metrics.audioCapturing) Tone.OK else Tone.NEUTRAL,
                )
                Spacer(Modifier.width(Spacing.sm))
                StatusBadge(
                    text = if (metrics.adbConnected) "ADB：已连接" else "ADB：未连接",
                    tone = if (metrics.adbConnected) Tone.OK else Tone.NEUTRAL,
                )
            }
        }

        SectionCard(icon = Icons.Filled.BugReport, title = "实时波形 / 频谱（示意）") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("16kHz · 40 mel · 示意动画", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                PillChip(vm.engineInfo)
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Panel, LgShape)
                    .padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(40) { index ->
                    val color = if (index % 3 == 0) Info else StWindow
                    val barHeight = (6f + 42f * abs(sin(phase * 6.283f + index * 0.42f))).dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .width(4.dp)
                            .height(barHeight)
                            .background(color, PillShape),
                    )
                }
            }
        }

        SectionTitle("判决置信度（真实快照）")
        InfoCard {
            val values = listOf(
                metrics.coarse,
                metrics.fine,
                metrics.commandScore,
                metrics.controlScore,
                metrics.speakerSim,
            )
            vm.confidenceLabels.forEachIndexed { index, label ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                    if (hasSnapshot) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(7.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, PillShape),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(values[index].coerceIn(0f, 1f))
                                    .height(7.dp)
                                    .background(Brand, PillShape),
                            )
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            String.format(Locale.CHINA, "%.2f", values[index]),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text("—", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            if (!hasSnapshot) {
                Text(
                    "尚未产生真实判决快照，数值暂不可用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        SectionTitle("最近判决")
        InfoCard {
            MetricLine("最近指令", if (metrics.commandId == null) "—" else "#${metrics.commandId}")
            MetricLine(
                "指令得分",
                if (hasSnapshot) String.format(Locale.CHINA, "%.2f", metrics.commandScore) else "—",
            )
            MetricLine("控制词", metrics.control.name)
            MetricLine(
                "控制词得分",
                if (hasSnapshot) String.format(Locale.CHINA, "%.2f", metrics.controlScore) else "—",
            )
            MetricLine(
                "声纹相似度",
                if (hasSnapshot) String.format(Locale.CHINA, "%.2f", metrics.speakerSim) else "—",
            )
            MetricLine(
                "更新时间",
                if (hasSnapshot) {
                    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(metrics.lastUpdateMs))
                } else {
                    "—"
                },
            )
        }

        SectionCard(icon = Icons.Filled.BugReport, title = "状态机演示（注入事件）") {
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(
                    text = "注入唤醒 → 指令窗",
                    onClick = {
                        appVm.demoScores(0.85f, 0.95f, null, null, 0.9f)
                        appVm.demoWake()
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "注入指令：槽位 1", onClick = { appVm.runCommand(0) })
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                QuietButton(text = "C1", onClick = { appVm.control(ControlWord.C1_EXIT_WAKE) })
                QuietButton(text = "C2", onClick = { appVm.control(ControlWord.C2_EXIT_RECOG) })
                QuietButton(text = "C3", onClick = { appVm.control(ControlWord.C3_PAUSE) })
                QuietButton(text = "C4", onClick = { appVm.control(ControlWord.C4_RESUME) })
            }
        }

        SectionTitle("坐标拾取")
        InfoCard {
            StatusBadge(text = "等待真实拾取 · 当前未接入", tone = Tone.WARN)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "真实坐标拾取尚未接入，当前不展示任何模拟坐标。基准换算：1080×2340 → 当前分辨率。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        SectionTitle("事件日志（最近 6 条）")
        CodeBlock(
            title = "telemetry · 最近 6 条",
            code = if (logs.isEmpty()) {
                "暂无事件"
            } else {
                logs.takeLast(6).joinToString("\n")
            },
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
