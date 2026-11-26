package com.example.voicectl.ui.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicectl.exec.CommandLibrary
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.Line
import com.example.voicectl.ui.theme.MdShape
import com.example.voicectl.ui.theme.Muted

@Composable
fun PanelScreen(onTab: (String) -> Unit, onRunCommand: (Int) -> Unit) {
    ScreenFrame(title = "手动 / 无语音面板", subtitle = "备用链路 · FAU 归零", activeTab = "panel", onTab = onTab) {
        InfoCard {
            Text("manual：手动开窗后短时用麦；manual_silent：完全不采集。面板直选不经过唤醒模型。", style = MaterialTheme.typography.bodySmall)
        }
        SectionTitle("装备组 · 7")
        CommandGrid(CommandLibrary.panelItems.take(7), onRunCommand)
        SectionTitle("位置组 · 6 + 切换位置")
        CommandGrid(CommandLibrary.panelItems.drop(7), onRunCommand)
        SectionTitle("悬浮球交互")
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(58.dp).background(Brand, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("小胡", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("单击：打开 3s 指令窗", style = MaterialTheme.typography.bodySmall)
                    Text("长按：暂停识别", style = MaterialTheme.typography.bodySmall)
                    Text("双击：恢复识别", style = MaterialTheme.typography.bodySmall)
                    Text("拖动：调整位置 / 透明度", style = MaterialTheme.typography.bodySmall)
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
private fun CommandGrid(items: List<Pair<String, Int>>, onRunCommand: (Int) -> Unit) {
    Column {
        items.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, id) ->
                    Surface(
                        modifier = Modifier.weight(1f).height(66.dp).clickable { onRunCommand(id) },
                        shape = MdShape,
                        color = androidx.compose.ui.graphics.Color.White,
                        border = BorderStroke(1.dp, Line),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier.size(30.dp).background(BrandSoft, MdShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label.take(1), color = Brand, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (row.size < 4) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
