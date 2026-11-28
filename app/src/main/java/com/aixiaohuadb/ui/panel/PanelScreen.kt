package com.aixiaohuadb.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.OpsHeroCard
import com.aixiaohuadb.ui.common.OpsMacroCard
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.macros.formatMacroDuration
import com.aixiaohuadb.ui.macros.macroStepDurationMs
import com.aixiaohuadb.ui.overlay.OverlayControlViewModel
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

/** 宏面板：宏是任务卡，点击即真实回放；无常驻拾音。 */
@Composable
fun PanelScreen(
    vm: OverlayControlViewModel,
    onTab: (String) -> Unit,
    onRunMacro: (Long) -> Unit,
    onNewMacro: () -> Unit,
    onManageMacros: () -> Unit = {},
) {
    val macros by vm.macros.collectAsState()
    ScreenFrame(
        title = "宏面板",
        subtitle = "任务卡 · 点击直接回放",
        activeTab = "panel",
        onTab = onTab,
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = "备用链路", tone = Tone.BRAND)
                Spacer(Modifier.width(Spacing.sm))
                StatusBadge(text = "无常驻拾音", tone = Tone.NEUTRAL)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "面板直接回放你录制的宏，点击由无障碍手势或 ADB 落下；顶部 25% 不遮挡。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        OpsHeroCard(
            title = "录制新宏",
            description = "进游戏后单击悬浮球展开面板 → 开始录制；录制中点「暂停」可让触摸透传给游戏。",
            primaryLabel = "开始录制",
            onPrimary = onNewMacro,
            secondaryLabel = "管理 / 编辑宏",
            onSecondary = onManageMacros,
            badge = "L1 PANEL",
        )

        OpsSectionLabel("ACTIVE MISSIONS", "${macros.size} 个宏")
        if (macros.isEmpty()) {
            InfoCard {
                Text("还没有任务卡", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "进游戏后单击悬浮球展开面板 → 开始录制；也可点上方入口查看录制方式。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                macros.chunked(2).forEach { pair ->
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
                                onEdit = onManageMacros,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            QuietButton(text = "进入宏库编辑", onClick = onManageMacros)
        }

        Spacer(Modifier.height(Spacing.md))
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = "录制口径", tone = Tone.WARN)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "录制时用手势操作游戏，抬手后才会生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
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
