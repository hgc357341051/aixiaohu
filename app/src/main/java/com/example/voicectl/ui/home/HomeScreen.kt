package com.example.voicectl.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.voicectl.machine.VoiceState
import com.example.voicectl.machine.ui
import com.example.voicectl.ui.AppViewModel
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.DangerBg
import com.example.voicectl.ui.theme.Line
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Ok
import com.example.voicectl.ui.theme.OkBg
import com.example.voicectl.ui.theme.OnBrand

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
    val info = uiState.voiceState.ui()
    var gateExpanded by remember { mutableStateOf(false) }
    val logs = uiState.logs.ifEmpty {
        listOf(
            "19:42 换名刀 · 语音 · 声纹通过",
            "19:40 换 1 号位 · 语音 · 声纹通过",
            "19:38 未识别（拒识）· 他人语音已拒绝",
        )
    }

    ScreenFrame(
        title = "小胡",
        subtitle = "语音换装助手",
        activeTab = "home",
        onTab = onTab,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onNotification() }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillChip(text = info.mode)
            Spacer(Modifier.width(8.dp))
            Text(info.modeDesc, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
        }

        StateCard(info)

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenWindow,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ) { Text("开启 3s 指令窗", style = MaterialTheme.typography.bodySmall) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = onPause) { Text("暂停语音") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
            ) { Text("退出唤醒") }
        }

        if (uiState.voiceState == VoiceState.MANUAL_SILENT) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
            ) { Text("手动恢复语音（悬浮球 / 通知 / 设置均可）") }
        }

        SectionTitle("执行状态门控")
        InfoCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { gateExpanded = !gateExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(8.dp).height(8.dp).background(Ok, androidx.compose.foundation.shape.CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text("门控 4 项全部通过 · 坐标表 v1.2", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(if (gateExpanded) "收起" else "明细", color = Brand, style = MaterialTheme.typography.labelSmall)
            }
            if (gateExpanded) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillChip("对局中 OK", OkBg, Ok)
                    PillChip("英雄存活 OK", OkBg, Ok)
                    PillChip("持有装备 OK", OkBg, Ok)
                    PillChip("坐标 v1.2", Line, Muted)
                }
                Text(
                    "门控任一不满足则不执行，并给出文字 / 震动 / 悬浮窗反馈。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionTitle("最近指令", "全部 →")
        InfoCard {
            logs.take(4).forEach { line ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
