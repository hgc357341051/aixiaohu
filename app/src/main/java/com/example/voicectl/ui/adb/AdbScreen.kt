package com.example.voicectl.ui.adb

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voicectl.adb.AdbPhase
import com.example.voicectl.adb.CommandResult
import com.example.voicectl.ui.common.DangerButton
import com.example.voicectl.ui.common.IconCircle
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PrimaryButton
import com.example.voicectl.ui.common.QuietButton
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.SecondaryButton
import com.example.voicectl.ui.common.SectionCard
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.StatusBadge
import com.example.voicectl.ui.common.StepRow
import com.example.voicectl.ui.common.Tone
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.BrandStrong
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.Info
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Ok
import com.example.voicectl.ui.theme.OkBg
import com.example.voicectl.ui.theme.Spacing

@Composable
fun AdbScreen(
    vm: AdbViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val s by vm.ui.collectAsState()

    LaunchedEffect(Unit) { vm.onResume() }

    ScreenFrame(
        title = "无线调试连接",
        subtitle = "应用内嵌 ADB · 强类型输入命令",
        showBack = true,
        onBack = onBack,
        activeTab = "settings",
        onTab = onTab,
    ) {
        StatusHero(phase = s.status.phase, reason = s.status.reason, onRecheck = vm::recheck)

        s.message?.let { msg ->
            Spacer(Modifier.height(Spacing.sm))
            InfoCard {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Spacing.xs))
                    QuietButton(text = "知道了", onClick = vm::dismissMessage)
                }
            }
        }

        SectionTitle(text = "连接步骤")
        SectionCard(icon = Icons.Filled.Security, title = "开启设备无线调试", tone = Tone.BRAND) {
            Text(
                "配对码由系统在「开发者选项 → 无线调试 → 使用配对码配对设备」中显示；应用不保存配对码，只保存本机的 ADB 身份密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            StepRow(1, "打开开发者选项", "关于手机 → 连续点按版本号 7 次")
            StepRow(2, "开启无线调试", "开发者选项 → 无线调试 → 打开开关")
            StepRow(3, "配对设备", "点「使用配对码配对设备」，记下端口与 6 位配对码")
            StepRow(4, "回到本页配对", "填入配对端口与配对码，点「发起配对」")
            StepRow(5, "连接设备", "在无线调试主页查看连接端口，填入后点「连接」")
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                QuietButton(text = "打开开发者选项", onClick = vm::openDeveloperOptions)
                QuietButton(text = "打开无线调试", onClick = vm::openWirelessDebugging)
                QuietButton(text = "官方说明", onClick = vm::openAdbDocs)
            }
        }

        SectionTitle(text = "① 配对新设备")
        SectionCard(icon = Icons.Filled.Security, title = "输入配对信息", tone = Tone.INFO) {
            OutlinedTextField(
                value = s.pairHost,
                onValueChange = vm::onPairHost,
                label = { Text("配对地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.pairPort,
                onValueChange = vm::onPairPort,
                label = { Text("配对端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.pairCode,
                onValueChange = vm::onPairCode,
                label = { Text("6 位配对码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            PrimaryButton(text = "发起配对", onClick = vm::pair, enabled = !s.busy)
        }

        SectionTitle(text = "② 连接设备")
        SectionCard(icon = Icons.Filled.Link, title = "连接已授权端点", tone = Tone.BRAND) {
            OutlinedTextField(
                value = s.connectHost,
                onValueChange = vm::onConnectHost,
                label = { Text("设备地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.connectPort,
                onValueChange = vm::onConnectPort,
                label = { Text("连接端口（无线调试主页显示）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            SecondaryButton(text = "连接", onClick = vm::connect, enabled = !s.busy)
            Spacer(Modifier.height(Spacing.sm))
            DangerButton(text = "断开连接", onClick = vm::disconnect, enabled = s.status.phase == AdbPhase.CONNECTED)
        }

        SectionTitle(text = "链路自检")
        SectionCard(icon = Icons.Filled.Terminal, title = "发送一次 HOME 按键", tone = Tone.BRAND) {
            Text(
                "用一个无害的 HOME 按键验证 shell 是否真的可用；不会操作你的应用数据。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(text = "自检", onClick = vm::probe, enabled = !s.busy)
            s.probeResult?.let { ProbeResult(it) }
        }

        SectionTitle(text = "真机环境")
        InfoCard {
            Column(modifier = Modifier.padding(Spacing.md)) {
                EnvRow("系统版本", "Android ${s.status.env.sdkInt}")
                EnvRow("支持无线配对", if (s.status.env.supportsWirelessPairing) "是（Android 11+）" else "否")
                EnvRow("屏幕分辨率", "${s.status.env.screenWidth} × ${s.status.env.screenHeight}")
                EnvRow("当前端点", s.status.host?.let { "$it:${s.status.port}" } ?: "未连接")
            }
        }

        WarningCard("仅限自有设备与测试环境使用。模拟点击可能违反应用或游戏条款，风险自担；本应用不内置任何提权组件，也不读取设备私有数据。")

        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun StatusHero(phase: AdbPhase, reason: String?, onRecheck: () -> Unit) {
    val (icon, tone, title, desc) = when (phase) {
        AdbPhase.CONNECTED -> Quad(
            Icons.Filled.CheckCircle, Tone.OK, "已连接", "ADB 链路就绪，可执行输入命令"
        )
        AdbPhase.PAIRED -> Quad(
            Icons.Filled.Security, Tone.BRAND, "已配对", "已保存身份，等待连接设备端点"
        )
        AdbPhase.DISCONNECTED -> Quad(
            Icons.Filled.LinkOff, Tone.NEUTRAL, "未连接", "按下方步骤开启无线调试并配对连接"
        )
        AdbPhase.ERROR -> Quad(
            Icons.Filled.LinkOff, Tone.DANGER, "连接异常", reason ?: "请检查地址、端口或重新配对"
        )
        AdbPhase.UNSUPPORTED -> Quad(
            Icons.Filled.LinkOff, Tone.WARN, "系统不支持", "无线调试配对需要 Android 11 及以上"
        )
        AdbPhase.NOT_READY -> Quad(
            Icons.Filled.Computer, Tone.WARN, "尚未就绪", "请先开启开发者选项与无线调试"
        )
    }
    InfoCard {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconTint = when (tone) {
                Tone.OK -> Ok
                Tone.DANGER -> Danger
                Tone.WARN -> com.example.voicectl.ui.theme.Warn
                Tone.BRAND -> BrandStrong
                else -> Info
            }
            val iconBg = if (tone == Tone.OK) OkBg else BrandSoft
            IconCircle(icon = icon, tint = iconTint, container = iconBg, size = 48.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(Spacing.sm))
                StatusBadge(text = phase.name, tone = tone)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            QuietButton(text = "重新检测", onClick = onRecheck)
        }
    }
}

@Composable
private fun ProbeResult(result: CommandResult) {
    Spacer(Modifier.height(Spacing.sm))
    StatusBadge(
        text = if (result.ok) "自检通过：shell 可用" else "自检未通过：${result.message}",
        tone = if (result.ok) Tone.OK else Tone.WARN,
    )
}

@Composable
private fun EnvRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
