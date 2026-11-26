package com.example.voicectl.ui.debug

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.ui.AppViewModel
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.Info
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Panel
import com.example.voicectl.ui.theme.PanelInk
import com.example.voicectl.ui.theme.PanelMuted
import com.example.voicectl.ui.theme.StWindow
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun DebugScreen(
    vm: DebugViewModel,
    appVm: AppViewModel,
    onTab: (String) -> Unit,
) {
    val logs by vm.logs.collectAsState()
    val transition = rememberInfiniteTransition(label = "scope")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Restart),
        label = "scope-phase",
    )

    ScreenFrame(title = "调试页", subtitle = "dev flavor · 仅自用", activeTab = "debug", onTab = onTab) {
        InfoCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("实时波形 / 频谱", style = MaterialTheme.typography.bodySmall)
                    Text("16kHz · 40 mel", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                PillChip(vm.engineInfo)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp).background(Panel, LgShape).padding(horizontal = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                repeat(46) { index ->
                    val color = if (index % 3 == 0) Info else StWindow
                    val barHeight = (6f + 42f * abs(sin(phase * 6.283f + index * 0.35f))).dp
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .width(4.dp).height(barHeight)
                            .background(color, com.example.voicectl.ui.theme.PillShape),
                    )
                }
            }
        }

        SectionTitle("判决置信度")
        InfoCard {
            val values = vm.confidences()
            vm.confidenceLabels.forEachIndexed { index, label ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(96.dp))
                    Box(
                        modifier = Modifier.weight(1f).height(7.dp)
                            .background(com.example.voicectl.ui.theme.Surface2, com.example.voicectl.ui.theme.PillShape),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(values[index].coerceIn(0f, 1f)).height(7.dp)
                                .background(Brand, com.example.voicectl.ui.theme.PillShape),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(String.format(java.util.Locale.CHINA, "%.2f", values[index]), style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        SectionTitle("状态机演示（注入事件）")
        InfoCard {
            androidx.compose.material3.TextButton(onClick = {
                appVm.demoScores(0.85f, 0.95f, null, null, 0.9f)
                appVm.demoWake()
            }) { Text("注入唤醒 → 指令窗") }
            androidx.compose.material3.TextButton(onClick = { appVm.runCommand(0) }) { Text("注入指令：换名刀") }
            Row {
                androidx.compose.material3.TextButton(onClick = { appVm.control(ControlWord.C1_EXIT_WAKE) }) { Text("C1") }
                androidx.compose.material3.TextButton(onClick = { appVm.control(ControlWord.C2_EXIT_RECOG) }) { Text("C2") }
                androidx.compose.material3.TextButton(onClick = { appVm.control(ControlWord.C3_PAUSE) }) { Text("C3") }
                androidx.compose.material3.TextButton(onClick = { appVm.control(ControlWord.C4_RESUME) }) { Text("C4") }
            }
        }

        SectionTitle("坐标拾取")
        InfoCard {
            Text("当前坐标：x=760, y=1750", style = MaterialTheme.typography.bodySmall)
            Text("基准换算：1080×2340 → 当前分辨率", style = MaterialTheme.typography.labelSmall, color = Muted)
        }

        SectionTitle("事件日志（最近 6 条）")
        Column(modifier = Modifier.fillMaxWidth().height(120.dp).background(Panel, LgShape).padding(10.dp)) {
            logs.takeLast(6).forEach { line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = PanelInk)
            }
            if (logs.isEmpty()) Text("暂无事件", style = MaterialTheme.typography.labelSmall, color = PanelMuted)
        }
        Spacer(Modifier.height(8.dp))
    }
}
