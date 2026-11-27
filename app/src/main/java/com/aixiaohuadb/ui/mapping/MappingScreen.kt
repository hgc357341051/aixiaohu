package com.aixiaohuadb.ui.mapping

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.ui.common.CodeBlock
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.Segment
import com.aixiaohuadb.ui.overlay.OverlayControlViewModel
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

@Composable
fun MappingScreen(vm: MappingViewModel, overlayVm: OverlayControlViewModel, onBack: () -> Unit, onTab: (String) -> Unit) {
    val macros by overlayVm.macros.collectAsState()
    ScreenFrame(title = "宏步骤预览", subtitle = "基准 1080×2340 · 兼容 A11y / ADB", showBack = true, onBack = onBack, onTab = onTab) {
        InfoCard {
            Text(
                "坐标统一以 1080×2340 为基准；运行时 x=x0×W/1080、y=y0×H/2340，无障碍与 ADB 共用同一换算。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Segment(
            options = listOf("宏列表", "执行预览"),
            selectedIndex = vm.tab,
            onSelect = vm::selectTab,
        )
        Spacer(Modifier.height(Spacing.md))
        if (vm.tab == 0) {
            SectionTitle("我的宏 · ${macros.size}")
            macros.forEach { macro ->
                SectionCard(title = macro.name) {
                    macro.steps.forEachIndexed { index, step ->
                        @Suppress("DEPRECATION")
                        val text = when (step) {
                            is com.aixiaohuadb.exec.ExecStep.Tap ->
                                "Tap (${step.x},${step.y}) 间隔 ${step.delayAfterMs}ms"
                            is com.aixiaohuadb.exec.ExecStep.Swipe ->
                                "Swipe (${step.fromX},${step.fromY})→(${step.toX},${step.toY}) ${step.durationMs}ms 间隔 ${step.delayAfterMs}ms"
                            is com.aixiaohuadb.exec.ExecStep.MultiGesture ->
                                "MultiGesture ${step.pointers.size}指 ${step.durationMs}ms 间隔 ${step.delayAfterMs}ms"
                            is com.aixiaohuadb.exec.ExecStep.Wait -> "Wait ${step.ms}ms（旧数据）"
                            is com.aixiaohuadb.exec.ExecStep.Verify -> "Verify ${step.key}"
                        }
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("${index + 1}.", style = MaterialTheme.typography.labelSmall, color = Muted)
                            Spacer(Modifier.padding(start = Spacing.xs))
                            Text(text, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (macro.steps.isEmpty()) {
                        Text("空宏", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
        } else {
            SectionCard(icon = Icons.Filled.Visibility, title = "强类型执行预览") {
                Text(
                    "端内不拼接任意 shell 字符串；只有 Tap/Swipe/KeyEvent 三种强类型命令。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            val first = macros.firstOrNull()
            CodeBlock(
                title = first?.name ?: "尚未创建宏",
                code = vm.previewCode(first?.steps.orEmpty()),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
    }
}
