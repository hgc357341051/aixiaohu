package com.aixiaohuadb.ui.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.exec.CommandLibrary
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.MdShape
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

@Composable
fun PanelScreen(onTab: (String) -> Unit, onRunCommand: (Int) -> Unit) {
    ScreenFrame(title = "手动 / 无语音面板", subtitle = "备用链路 · FAU 归零", activeTab = "panel", onTab = onTab) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(text = "备用链路", tone = Tone.BRAND)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "manual：手动开窗后短时用麦；manual_silent：完全不采集。面板直选不经过唤醒模型。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionTitle("装备组 · 7")
        CommandGrid(CommandLibrary.panelItems.take(7), onRunCommand)
        SectionTitle("位置组 · 6 + 切换位置")
        CommandGrid(CommandLibrary.panelItems.drop(7), onRunCommand)
        SectionTitle("悬浮球交互")
        SectionCard(icon = Icons.Filled.Mic, title = "悬浮球可直接操作") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(icon = Icons.Filled.Mic, tint = Brand, container = BrandSoft, size = 48.dp)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    InteractionLine("单击", "打开 3s 指令窗（手动模式）")
                    InteractionLine("长按", "暂停识别")
                    InteractionLine("双击", "恢复识别")
                    InteractionLine("拖动", "调整位置 / 透明度")
                }
            }
        }
        InfoCard {
            Text(
                "面板不遮挡游戏 HUD，可拖动并调节透明度。C1 退出唤醒后，仅保留此面板与悬浮球作为恢复入口。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun InteractionLine(action: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(action, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Brand)
        Spacer(Modifier.width(Spacing.xs))
        Text(desc, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CommandGrid(items: List<Pair<String, Int>>, onRunCommand: (Int) -> Unit) {
    Column {
        items.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { (label, id) ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .clickable { onRunCommand(id) },
                        shape = MdShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, Line),
                        shadowElevation = 1.dp,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            IconCircle(
                                icon = iconFor(label),
                                tint = Brand,
                                container = BrandSoft,
                                size = 32.dp,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (row.size < 4) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

private fun iconFor(label: String): ImageVector = when {
    label.contains("切换") -> Icons.Filled.SwapHoriz
    label.any { it.isDigit() } -> Icons.Filled.FormatListNumbered
    else -> Icons.Filled.HealthAndSafety
}
