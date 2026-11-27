package com.aixiaohuadb.ui.voiceprint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
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
import com.aixiaohuadb.ui.common.ConfirmDialog
import com.aixiaohuadb.ui.common.DangerButton
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.MetricCard
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.common.ToggleRow
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

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
        // 模板状态卡：仅由 hasTemplate 驱动，不再展示任何虚构样本数
        SectionCard(icon = Icons.Filled.Fingerprint, title = "机主声纹模板") {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconCircle(
                    icon = Icons.Filled.Fingerprint,
                    tint = if (hasTemplate) Brand else Muted,
                    container = if (hasTemplate) BrandSoft else MaterialTheme.colorScheme.surfaceVariant,
                    size = 56.dp,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "取自训练集机主本人样本，不额外录音、不采他人",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Spacer(Modifier.height(Spacing.md))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模板状态", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    StatusBadge(
                        text = if (hasTemplate) "已注册" else "未注册",
                        tone = if (hasTemplate) Tone.OK else Tone.WARN,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (hasTemplate) 1f else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    color = Brand,
                )
            }
        }

        SectionTitle("环境子质心（未标定）")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            EnvCard("安静")
            EnvCard("外放")
            EnvCard("远场")
        }

        SectionTitle("自测指标（需真机真人标定）")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MetricCard(
                value = "待自测",
                label = "本人接受率",
                tone = Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                value = "待自测",
                label = "他人冒认率",
                tone = Tone.NEUTRAL,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton(text = "开始自测", onClick = { vm.selfTest() })

        Spacer(Modifier.height(Spacing.md))
        InfoCardSimple("模板仅存本机并加密，不上传；可一键删除退回非声纹路径。")

        Spacer(Modifier.height(Spacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = "重新注册",
                onClick = { vm.enroll() },
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        DangerButton(text = "删除模板", onClick = { showDeleteDialog = true })

        Spacer(Modifier.height(Spacing.md))
        InfoCardSimple("临时放行：连续 3 次声纹拒绝时提示开启，开启后他人语音也可能触发执行。")
        ToggleRow("临时放行", "当前为演示开关，状态由本机配置管理", false) { vm.setTempAllow(it) }

        if (!hasTemplate) {
            WarningCard("声纹模板不存在，当前自动降级为非声纹路径，他人语音也可能触发执行，请自行承担误触风险。")
        }
        // 只展示非示意消息；自测的示意数值不进 UI，避免伪造指标
        message?.let { raw ->
            val shown = if (raw.contains("示意") || raw.contains("%")) {
                "自测已启动：真实接受率 / 冒认率需在真机真人标定后填写，当前无实测数据。"
            } else {
                raw
            }
            WarningCard(shown)
        }

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
private fun RowScope.EnvCard(title: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusBadge(text = title, tone = Tone.BRAND)
        Spacer(Modifier.height(Spacing.xs))
        Text("未标定", style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
private fun InfoCardSimple(text: String) {
    androidx.compose.material3.Surface(
        shape = com.aixiaohuadb.ui.theme.CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(Spacing.md),
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}
