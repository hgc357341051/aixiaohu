package com.example.voicectl.ui.voiceprint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicectl.ui.common.ConfirmDialog
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.ToggleRow
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.DangerBg
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Ok

@Composable
fun VoiceprintScreen(
    vm: VoiceprintViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val hasTemplate by vm.hasTemplate.collectAsState()
    val message by vm.message.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    ScreenFrame(title = "声纹注册 / 管理", showBack = true, onBack = onBack, onTab = onTab) {
        InfoCard {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("机主声纹模板 · owner.spk", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("取自训练集机主本人样本，不额外录音、不采他人", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("注册样本 642 / 600", style = MaterialTheme.typography.labelSmall, color = Muted)
                    Spacer(Modifier.weight(1f))
                    PillChip(if (hasTemplate) "已完成" else "未注册")
                }
                LinearProgressIndicator(
                    progress = { if (hasTemplate) 1f else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = Brand,
                )
            }
        }

        SectionTitle("三环境子质心")
        InfoCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                EnvCard("安静", "已就绪")
                EnvCard("外放", "已就绪")
                EnvCard("远场", "已就绪")
            }
        }

        SectionTitle("自测指标（示意）")
        InfoCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MetricCard("98.6%", "本人接受率")
                MetricCard("0.4%", "他人冒认率")
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.selfTest() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandSoft, contentColor = Brand),
            ) { Text("开始自测") }
        }

        InfoCard {
            Text(
                "模板仅存本机并加密，不上传；可一键删除退回非声纹路径。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { vm.enroll() }, modifier = Modifier.weight(1f)) { Text("重新注册") }
            Spacer(Modifier.padding(8.dp))
            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
            ) { Text("删除模板") }
        }

        Spacer(Modifier.height(12.dp))
        InfoCard {
            ToggleRow("临时放行", "连续 3 次声纹拒绝时提示开启", false) { vm.setTempAllow(it) }
        }
        if (!hasTemplate) WarningCard("声纹模板不存在，当前自动降级为非声纹路径，他人语音也可能触发执行，请自行承担误触风险。")
        if (message != null) WarningCard(message ?: "")

        if (showDeleteDialog) {
            ConfirmDialog(
                title = "删除机主声纹模板？",
                text = "删除后门控将退回非声纹路径，他人语音也可能触发执行（需自行承担误触风险）。可随时重新注册。",
                confirmLabel = "确认删除",
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    vm.deleteTemplate()
                },
            )
        }
    }
}

@Composable
private fun EnvCard(title: String, status: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PillChip(title, BrandSoft, Brand)
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = Ok)
    }
}

@Composable
private fun MetricCard(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displayMedium, color = Brand)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
