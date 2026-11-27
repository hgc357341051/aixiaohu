package com.aixiaohuadb.ui.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.overlay.OverlayControlViewModel
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

/** 手动面板：只显示用户录制的宏，内置固定指令已删除；点击宏即真实回放 */
@Composable
fun PanelScreen(
    vm: OverlayControlViewModel,
    onTab: (String) -> Unit,
    onRunMacro: (Long) -> Unit,
    onNewMacro: () -> Unit,
) {
    val macros by vm.macros.collectAsState()
    ScreenFrame(
        title = "手动 / 无语音面板",
        subtitle = "用户宏 · 无常驻拾音",
        activeTab = "panel",
        onTab = onTab,
    ) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = "备用链路", tone = Tone.BRAND)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "面板直接回放你录制的宏，点击由无障碍手势或 ADB 落下；顶部 25% 不遮挡。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionTitle("我的宏 · ${macros.size}")
        if (macros.isEmpty()) {
            SectionCard(icon = Icons.Filled.VideogameAsset, title = "还没有宏") {
                Text(
                    "进游戏后单击悬浮球展开面板 → 开始录制；也可点下方入口查看录制方式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                macros.forEach { macro ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRunMacro(macro.macroId) }
                            .padding(vertical = Spacing.sm),
                    ) {
                        Text(macro.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            macro.steps.joinToString(" · ") { step ->
                                @Suppress("DEPRECATION")
                                when (step) {
                                    is ExecStep.Tap -> "点(${step.x},${step.y})/${step.delayAfterMs}ms"
                                    is ExecStep.Swipe -> "滑→(${step.toX},${step.toY})/${step.delayAfterMs}ms"
                                    is ExecStep.MultiGesture ->
                                        "多指${step.pointers.size}指/${step.durationMs}ms/${step.delayAfterMs}ms"
                                    is ExecStep.Wait -> "等${step.ms}ms"
                                    is ExecStep.Verify -> "校验"
                                }
                            }.ifEmpty { "空宏" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        PrimaryButton(text = "新建宏 / 查看录制方式", onClick = onNewMacro)
        Spacer(Modifier.height(Spacing.sm))
    }
}
