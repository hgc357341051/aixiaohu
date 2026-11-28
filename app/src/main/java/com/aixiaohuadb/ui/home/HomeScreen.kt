package com.aixiaohuadb.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.machine.UiStateInfo
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.ui
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.ui.AppViewModel
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.OpsHeroCard
import com.aixiaohuadb.ui.common.OpsMacroCard
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.OpsStatusTile
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.macros.formatMacroDuration
import com.aixiaohuadb.ui.macros.macroStepDurationMs
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.DangerBg
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Ok
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Warn
import com.aixiaohuadb.ui.theme.WarnBg
import com.aixiaohuadb.ui.theme.WarnBd
import com.aixiaohuadb.voice.VoiceLifecycleState

/**
 * 作战台：把原首页状态、手动面板入口与宏列表收进同一张任务台。
 * 所有状态值仍来自 [AppViewModel.UiState]，不伪造「门控通过 / 链路已连接」。
 */
@Composable
fun HomeScreen(
    uiState: AppViewModel.UiState,
    macros: List<MacroConfig>,
    onTab: (String) -> Unit,
    onNotification: () -> Unit,
    onOpenWindow: () -> Unit,
    onPause: () -> Unit,
    onExit: () -> Unit,
    onResume: () -> Unit,
    onRunMacro: (Long) -> Unit,
    onEditMacro: (Long) -> Unit,
    onNewMacro: () -> Unit,
    onManageMacros: () -> Unit,
) {
    var gateExpanded by remember { mutableStateOf(false) }
    val logs = uiState.logs
    val voiceRunning = uiState.voiceLifecycle == VoiceLifecycleState.RUNNING ||
        uiState.voiceLifecycle == VoiceLifecycleState.PAUSED

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

    val adbConnected = uiState.metrics.adbConnected
    val voiceLabel = when {
        !voiceRunning -> "未开启"
        uiState.voiceLifecycle == VoiceLifecycleState.PAUSED -> "已暂停"
        else -> "运行中"
    }

    ScreenFrame(
        title = "作战台",
        subtitle = "战术终端 / 宏与链路总览",
        activeTab = "home",
        onTab = onTab,
    ) {
        OpsSectionLabel("LINK STATUS", "运行时真实状态")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OpsStatusTile(
                label = "LINK / ADB",
                value = if (adbConnected) "已连接" else "未连接",
                icon = if (adbConnected) Icons.Filled.Link else Icons.Filled.LinkOff,
                tint = if (adbConnected) Ok else Muted,
                modifier = Modifier.weight(1f),
            )
            OpsStatusTile(
                label = "INPUT",
                value = "手动面板就绪",
                icon = Icons.Filled.TouchApp,
                tint = Brand,
                modifier = Modifier.weight(1f),
            )
            OpsStatusTile(
                label = "VOICE",
                value = voiceLabel,
                icon = if (voiceRunning) Icons.Filled.Mic else Icons.Filled.MicOff,
                tint = if (voiceRunning) Ok else Muted,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        OpsHeroCard(
            title = "开始录制",
            description = "免 root 转发录制 · 坐标自动归一化 · 暂停即透传。录完直接命名保存，不经过截图标注。",
            primaryLabel = "录制新宏",
            onPrimary = onNewMacro,
            secondaryLabel = "管理宏库",
            onSecondary = onManageMacros,
            badge = "L1 DECK",
        )

        Spacer(Modifier.height(Spacing.sm))
        InfoCard {
            Row(
                modifier = Modifier.clickable { onNotification() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircle(Icons.Filled.Notifications, Brand, BrandSoft, 30.dp)
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    StatusBadge(text = info.mode, tone = Tone.BRAND)
                    Text(
                        info.modeDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("通知", style = MaterialTheme.typography.labelSmall, color = Brand)
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusBadge(
                    text = if (uiState.metrics.audioCapturing) "麦克风采集中" else "麦克风未采集",
                    tone = if (uiState.metrics.audioCapturing) Tone.OK else Tone.NEUTRAL,
                )
                StatusBadge(
                    text = if (uiState.isDemo) "Demo ${uiState.metrics.modelVersion}" else uiState.metrics.modelVersion,
                    tone = if (uiState.isDemo) Tone.WARN else Tone.BRAND,
                )
            }

            Spacer(Modifier.height(Spacing.md))
            // STOPPED / DISABLED 不是第三个语音启动入口：只提示并跳设置页
            when (uiState.voiceLifecycle) {
                VoiceLifecycleState.STOPPED,
                VoiceLifecycleState.DISABLED,
                VoiceLifecycleState.DESTROYING,
                VoiceLifecycleState.STARTING,
                -> PrimaryButton(text = "语音未开启 · 去设置开启", onClick = { onTab("settings") })

                VoiceLifecycleState.RUNNING, VoiceLifecycleState.PAUSED ->
                    PrimaryButton(text = "开启 3s 指令窗", onClick = onOpenWindow)
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OpsTinyAction("指令窗", Icons.Filled.Notifications, enabled = voiceRunning, onClick = onOpenWindow)
                OpsTinyAction("暂停", Icons.Filled.PauseCircle, enabled = voiceRunning, onClick = onPause)
                OpsTinyAction("退出", Icons.Filled.PowerOff, tone = Tone.DANGER, enabled = voiceRunning, onClick = onExit)
            }
            if (uiState.voiceState == VoiceState.MANUAL_SILENT && voiceRunning) {
                Spacer(Modifier.height(Spacing.sm))
                OpsTinyAction("手动恢复语音", Icons.Filled.Mic, onClick = onResume)
            }
        }

        OpsSectionLabel("ACTIVE MISSIONS", "${macros.size} 个宏")
        if (macros.isEmpty()) {
            InfoCard {
                Text("还没有任务卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "进游戏后录制一段手势，或到宏库手动编排步骤。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Spacer(Modifier.height(Spacing.sm))
                QuietButton(text = "去宏库", onClick = onManageMacros)
            }
        } else {
            macros.take(6).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    pair.forEach { macro ->
                        val durationMs = remember(macro.steps) {
                            MacroAdapter.fromExecSteps(macro.steps).sumOf { macroStepDurationMs(it) }
                        }
                        OpsMacroCard(
                            name = macro.name,
                            summary = missionSummary(macro.steps),
                            footerLeft = "${macro.steps.size} STEP",
                            footerRight = formatMacroDuration(durationMs),
                            onRun = { onRunMacro(macro.macroId) },
                            onEdit = { onEditMacro(macro.macroId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(Spacing.sm))
            }
            QuietButton(text = "查看全部 ${macros.size} 个宏", onClick = onManageMacros)
        }

        Spacer(Modifier.height(Spacing.sm))
        Surface(
            shape = CardShape,
            color = WarnBg,
            border = BorderStroke(1.dp, WarnBd),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.md)) {
                Row(
                    modifier = Modifier.clickable { gateExpanded = !gateExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(text = "执行状态门控 · 手动确认", tone = Tone.WARN)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (gateExpanded) "收起" else "说明",
                        style = MaterialTheme.typography.labelSmall,
                        color = Warn,
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
        }

        OpsSectionLabel("RECENT FEEDBACK", "最近 ${logs.takeLast(3).size} 条")
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
                        Text("暂无回执", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "语音指令 / 宏回放执行后会在此显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            } else {
                logs.takeLast(3).asReversed().forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Brand, CircleShape),
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
private fun OpsTinyAction(
    text: String,
    icon: ImageVector,
    tone: Tone = Tone.BRAND,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = if (tone == Tone.DANGER) Danger else Brand
    val bg = if (tone == Tone.DANGER) DangerBg else BrandSoft
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(34.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Suppress("DEPRECATION")
private fun missionSummary(steps: List<ExecStep>): String =
    steps.take(4).joinToString(" · ") { step ->
        when (step) {
            is ExecStep.Tap -> "点击"
            is ExecStep.Swipe -> "滑动"
            is ExecStep.MultiGesture -> "多指"
            is ExecStep.Wait -> "等待"
            is ExecStep.Verify -> "校验"
        }
    } + if (steps.size > 4) " …" else ""
