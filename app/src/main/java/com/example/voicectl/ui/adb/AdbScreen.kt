package com.example.voicectl.ui.adb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.voicectl.ui.common.CodeBlock
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.QuietButton
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.SectionCard
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.StatusBadge
import com.example.voicectl.ui.common.StepRow
import com.example.voicectl.ui.common.Tone
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Spacing

@Composable
fun AdbScreen(vm: AdbViewModel, onBack: () -> Unit, onMapping: () -> Unit, onTab: (String) -> Unit) {
    ScreenFrame(title = "ADB 连接与映射", subtitle = "备用执行链路", showBack = true, onBack = onBack, onTab = onTab) {
        SectionCard(icon = Icons.Filled.Link, title = "连接状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = vm.status,
                    tone = if (vm.status.contains("已连接") || vm.status.contains("心跳正常")) {
                        Tone.OK
                    } else if (vm.status.contains("失败") || vm.status.contains("离线")) {
                        Tone.DANGER
                    } else {
                        Tone.WARN
                    },
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "执行优先级：无障碍（主）> ADB（备）> 只读提示 > 注入 Hook（禁止）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(icon = Icons.Filled.Wifi, title = "首次配对步骤（荣耀 MagicOS）") {
            StepRow(1, "开启开发者选项", "设置 → 关于手机 → 连续点「版本号」7 次")
            StepRow(2, "开启无线调试", "开发人员选项 → 无线调试 → 使用配对码配对")
            StepRow(3, "记录地址", "记下 6 位配对码、IP 与配对端口；连接端口通常不同")
            StepRow(4, "连接并校验", "需与 PC 同局域网；避免公共 Wi-Fi 的 AP 隔离")
        }

        SectionTitle("桥接配置")
        InfoCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(vm.bridgeUrl, vm::updateBridgeUrl, label = { Text("桥接服务地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.ip, vm::updateIp, label = { Text("设备 IP") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.pairPort, vm::updatePairPort, label = { Text("配对端口") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.connectPort, vm::updateConnectPort, label = { Text("连接端口") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.pairCode, vm::updatePairCode, label = { Text("6 位配对码") }, modifier = Modifier.fillMaxWidth())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                QuietButton(onClick = { vm.pair() }, text = "配对")
                QuietButton(onClick = { vm.connect() }, text = "连接")
                QuietButton(onClick = { vm.heartbeat() }, text = "心跳")
                QuietButton(onClick = { vm.saveBridgeUrl() }, text = "保存")
            }
            if (vm.message != null) {
                Text(
                    vm.message ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }

        SectionTitle("命令示意（不在端内执行）")
        CodeBlock(
            title = "仅作示意 · App 内不执行 adb shell",
            code = "adb pair 192.168.1.23:37157\nadb connect 192.168.1.23:5555\nadb shell wm size   # 1080x2340\nadb shell input tap 900 2100",
        )

        SectionCard(title = "坐标映射（基准 1080×2340）") {
            Column {
                Text("换名刀：装备栏→名刀格→使用（待真机验证）", style = MaterialTheme.typography.bodySmall)
                Text("换 1–6 号位：点对应槽位（待真机验证）", style = MaterialTheme.typography.bodySmall)
                Text("切换位置：方案切换按钮（待真机验证）", style = MaterialTheme.typography.bodySmall)
                Text(
                    "禁止硬编码绝对坐标：x=x0×W/1080，y=y0×H/2340；每步点击后校验，失败即停，重试≤1 次。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Spacer(Modifier.height(Spacing.xs))
                QuietButton(text = "打开指令映射 / 坐标库 →", onClick = onMapping)
            }
        }

        WarningCard("安全红线：不读内存、不注入、不 Hook、不绕反作弊；不模拟透视/自瞄/走位；仅自有授权设备，风险自担。")
        Spacer(Modifier.height(Spacing.sm))
    }
}
