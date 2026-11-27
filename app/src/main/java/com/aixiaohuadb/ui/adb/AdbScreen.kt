package com.aixiaohuadb.ui.adb

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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.adb.AdbPhase
import com.aixiaohuadb.adb.CommandResult
import com.aixiaohuadb.ui.common.DangerButton
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.StepRow
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandStrong
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.Info
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Ok
import com.aixiaohuadb.ui.theme.OkBg
import com.aixiaohuadb.ui.theme.Spacing

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
        StatusHero(phase = s.status.phase, onRecheck = vm::recheck)

        s.message?.let { msg ->
            Spacer(Modifier.height(Spacing.sm))
            InfoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
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
                "配对端口只在系统配对弹窗打开期间有效；配对成功后要回到「无线调试」主页读取另一个连接端口。应用不保存配对码，只保存本机的 ADB 身份密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            StepRow(1, "打开开发者选项", "关于手机 → 连续点按版本号 7 次")
            StepRow(2, "开启无线调试", "开发者选项 → 无线调试 → 打开开关")
            StepRow(3, "配对设备", "点「使用配对码配对设备」，记下端口与 6 位配对码")
            StepRow(4, "回到本页配对", "填入配对端口与配对码，点「发起配对」")
            StepRow(5, "回到无线调试主页", "记下「IP 地址:端口」，这是连接端口，不是配对端口")
            StepRow(6, "连接设备", "填入连接地址与连接端口，点「连接」后再自检")
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
                label = { Text("配对地址（默认取本机局域网 IP）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.pairPort,
                onValueChange = vm::onPairPort,
                label = { Text("配对端口（系统配对弹窗显示）") },
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
                label = { Text("设备地址（默认取本机局域网 IP）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.connectPort,
                onValueChange = vm::onConnectPort,
                label = { Text("连接端口（无线调试主页显示，非配对端口）") },
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

        SectionTitle(text = "前台长连接")
        SectionCard(icon = Icons.Filled.Link, title = "桥接状态", tone = Tone.INFO) {
            EnvRow("桥接阶段", s.bridge.phase.name)
            EnvRow("最近心跳", if (s.bridge.heartbeatOk) "正常" else "未通过")
            EnvRow("连接端点", s.bridge.endpoint ?: "未连接")
            EnvRow("排队命令", s.bridge.queued.toString())
            EnvRow("重连次数", s.bridge.retryCount.toString())
            s.bridge.message?.let { msg ->
                Text(msg, style = MaterialTheme.typography.labelSmall, color = Danger)
            }
        }

        SectionTitle(text = "raw shell（风险自负）")
        SectionCard(icon = Icons.Filled.Terminal, title = "自定义命令", tone = Tone.WARN) {
            Text(
                "所有 flavor 均可发送任意 shell，风险自负。每条命令都会展示原文并要求显式确认；" +
                    "常见不可逆命令拦截（可能不全），审计日志不可关闭。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = s.rawCommand,
                onValueChange = vm::onRawCommand,
                label = { Text("shell 原文，例如：input keyevent 3") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(text = "发送 raw shell", onClick = vm::requestRaw, enabled = !s.busy)
            s.rawResult?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(it, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }

        SectionTitle(text = "审计日志（最近 ${s.audit.size} 条）")
        InfoCard {
            if (s.audit.isEmpty()) {
                Text("暂无 raw shell 审计记录", style = MaterialTheme.typography.labelSmall, color = Muted)
            } else {
                s.audit.forEach { entry ->
                    Text(
                        "${entry.time} · ${entry.decision} · ${entry.source} · ${if (entry.ok) "OK" else "FAIL"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    Text(entry.command, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(Spacing.xs))
                }
                QuietButton(text = "刷新审计", onClick = vm::refreshAudit)
            }
        }

        SectionTitle(text = "真机环境")
        InfoCard {
            Column {
                EnvRow("系统版本", "Android ${s.status.env.sdkInt}")
                EnvRow("支持无线配对", if (s.status.env.supportsWirelessPairing) "是（Android 11+）" else "否")
                EnvRow("屏幕分辨率", "${s.status.env.screenWidth} × ${s.status.env.screenHeight}")
                EnvRow("当前连接端点", s.status.endpointLabel ?: "未连接")
                EnvRow("已配对端点", s.status.pairedLabel ?: "尚未配对")
            }
        }

        s.pendingRaw?.let { req ->
            AlertDialog(
                onDismissRequest = vm::cancelRaw,
                title = { Text("确认执行 raw shell") },
                text = {
                    Column {
                        Text("将向已连接的设备发送以下命令：", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(req.command, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text("来源：${req.source.name} · 超时 ${req.timeoutMs}ms", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                },
                confirmButton = { TextButton(onClick = vm::confirmRaw) { Text("确认执行") } },
                dismissButton = { TextButton(onClick = vm::cancelRaw) { Text("取消") } },
            )
        }

        WarningCard("仅限自有设备与测试环境使用。模拟点击可能违反应用或游戏条款，风险自担；本应用不内置任何提权组件，也不读取设备私有数据。")

        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun StatusHero(phase: AdbPhase, onRecheck: () -> Unit) {
    // ERROR 描述固定为可操作提示，绝不读 status.reason：
    // reason 可能来自上一次配对成功，和当前失败状态叠加会造成“连接异常 + 配对成功”的矛盾文案。
    val (icon, tone, title, desc) = when (phase) {
        AdbPhase.CONNECTED -> Quad(
            Icons.Filled.CheckCircle, Tone.OK, "已连接", "shell 探活通过，可执行输入命令"
        )
        AdbPhase.PAIRED -> Quad(
            Icons.Filled.Security, Tone.BRAND, "已配对", "身份已保存，仍需填入连接端口完成连接"
        )
        AdbPhase.DISCONNECTED -> Quad(
            Icons.Filled.LinkOff, Tone.NEUTRAL, "未连接", "按下方步骤开启无线调试并配对连接"
        )
        AdbPhase.ERROR -> Quad(
            Icons.Filled.LinkOff, Tone.DANGER, "连接异常", "请查看下方提示，核对连接端口与 IP 后重试"
        )
        AdbPhase.UNSUPPORTED -> Quad(
            Icons.Filled.LinkOff, Tone.WARN, "系统不支持", "无线调试配对需要 Android 11 及以上"
        )
        AdbPhase.NOT_READY -> Quad(
            Icons.Filled.Computer, Tone.WARN, "尚未就绪", "请先开启开发者选项与无线调试"
        )
    }
    InfoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconTint = when (tone) {
                Tone.OK -> Ok
                Tone.DANGER -> Danger
                Tone.WARN -> com.aixiaohuadb.ui.theme.Warn
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
