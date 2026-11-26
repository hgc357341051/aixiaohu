package com.example.voicectl.ui.mapping

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.voicectl.exec.CommandLibrary
import com.example.voicectl.exec.ExecStep
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.Segment
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Panel
import com.example.voicectl.ui.theme.PanelInk

@Composable
fun MappingScreen(vm: MappingViewModel, onBack: () -> Unit, onTab: (String) -> Unit) {
    ScreenFrame(title = "指令映射配置", subtitle = "库 v1.2 · 基准 1080×2340", showBack = true, onBack = onBack, onTab = onTab) {
        InfoCard {
            Text(
                "坐标库、步骤模板、指令编排三者分离；装备组指令引用独立配置的位置组坐标。运行时 x=x0×W/1080、y=y0×H/2340。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Segment(
            options = listOf("坐标库", "指令编排", "执行预览"),
            selectedIndex = vm.tab,
            onSelect = vm::selectTab,
        )
        Spacer(Modifier.height(10.dp))

        when (vm.tab) {
            0 -> {
                SectionTitle("公共坐标 · 6")
                InfoCard { CoordinateRows(vm, CommandLibrary.common) }
                SectionTitle("位置组坐标 · 6")
                InfoCard { CoordinateRows(vm, CommandLibrary.slots) }
                SectionTitle("装备格坐标 · 7")
                InfoCard { CoordinateRows(vm, CommandLibrary.equips) }
            }
            1 -> {
                val revision = vm.expandedRevision
                revision
                InfoCard {
                    Text(
                        "装备组默认包含「选中位置组槽位」步骤，槽位由 target_slot 参数指定（默认 1）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                CommandLibrary.commandActions.take(14).forEach { action ->
                    InfoCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { vm.toggleExpanded(action.commandId) },
                        ) {
                            Text(action.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            PillChip("${action.steps.size} 步")
                        }
                        if (action.commandId in vm.expandedIds) {
                            Spacer(Modifier.height(8.dp))
                            action.steps.forEachIndexed { index, step ->
                                val text = when (step) {
                                    is ExecStep.Tap -> "tap  ${step.key}  (${step.x},${step.y})"
                                    is ExecStep.Wait -> "wait ${step.ms}ms"
                                    is ExecStep.Verify -> "verify ${step.key}"
                                }
                                Text("${index + 1}. $text", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            else -> {
                InfoCard {
                    Text("换名刀 · CMD_HUAN_MINGDAO", style = MaterialTheme.typography.bodySmall)
                    PillChip("失败即停", com.example.voicectl.ui.theme.OkBg, com.example.voicectl.ui.theme.Ok)
                }
                Column(modifier = Modifier.fillMaxWidth().background(Panel, LgShape).padding(12.dp)) {
                    Text(vm.previewCode(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = PanelInk)
                }
                InfoCard {
                    Text(
                        "每步点击后校验 UI 状态，失败立即停止序列，重试 ≤1 次，避免半执行误换装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoordinateRows(vm: MappingViewModel, entries: List<CommandLibrary.CoordinateEntry>) {
    entries.forEach { entry ->
        val (x, y) = vm.coordinateOf(entry.key)
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(entry.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text(entry.label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = x.toString(),
                    onValueChange = { vm.updateCoordinate(entry.key, it.toIntOrNull() ?: 0, y) },
                    label = { Text("x") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = y.toString(),
                    onValueChange = { vm.updateCoordinate(entry.key, x, it.toIntOrNull() ?: 0) },
                    label = { Text("y") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
