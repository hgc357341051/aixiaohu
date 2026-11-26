package com.example.voicectl.ui.adb

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.voicectl.ui.common.InfoCard
import com.example.voicectl.ui.common.PillChip
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Panel
import com.example.voicectl.ui.theme.PanelInk
import com.example.voicectl.ui.theme.PanelMuted
import com.example.voicectl.ui.theme.Warn

@Composable
fun AdbScreen(vm: AdbViewModel, onBack: () -> Unit, onMapping: () -> Unit, onTab: (String) -> Unit) {
    ScreenFrame(title = "ADB 连接与映射", subtitle = "备用执行链路", showBack = true, onBack = onBack, onTab = onTab) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(Warn, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.status, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "执行优先级：无障碍（主）> ADB（备）> 只读提示 > 注入 Hook（禁止）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                PillChip("待配置")
            }
        }

        SectionTitle("首次配对步骤（荣耀 MagicOS）")
        InfoCard {
            Step(1, "开启开发者选项", "设置 → 关于手机 → 连续点「版本号」7 次")
            Step(2, "开启无线调试", "开发人员选项 → 无线调试 → 使用配对码配对")
            Step(3, "记录地址", "记下 6 位配对码、IP 与配对端口；连接端口通常不同")
            Step(4, "连接并校验", "需与 PC 同局域网；避免公共 Wi-Fi 的 AP 隔离")
        }

        SectionTitle("桥接配置")
        InfoCard {
            OutlinedTextField(vm.bridgeUrl, vm::updateBridgeUrl, label = { Text("桥接服务地址") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.ip, vm::updateIp, label = { Text("设备 IP") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.pairPort, vm::updatePairPort, label = { Text("配对端口") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.connectPort, vm::updateConnectPort, label = { Text("连接端口") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.pairCode, vm::updatePairCode, label = { Text("6 位配对码") }, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                TextButton(onClick = { vm.pair() }) { Text("配对", color = Brand) }
                TextButton(onClick = { vm.connect() }) { Text("连接", color = Brand) }
                TextButton(onClick = { vm.heartbeat() }) { Text("心跳", color = Brand) }
                TextButton(onClick = { vm.saveBridgeUrl() }) { Text("保存", color = Brand) }
            }
            if (vm.message != null) Text(vm.message ?: "", style = MaterialTheme.typography.labelSmall, color = Muted)
        }

        SectionTitle("命令示意（不在端内执行）")
        Column(
            modifier = Modifier.fillMaxWidth().background(Panel, LgShape).padding(12.dp),
        ) {
            Text(
                "adb pair 192.168.1.23:37157\nadb connect 192.168.1.23:5555\nadb shell wm size   # 1080x2340\nadb shell input tap 900 2100",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = PanelInk,
            )
        }

        SectionTitle("坐标映射（基准 1080×2340）")
        InfoCard {
            Text("换名刀：装备栏→名刀格→使用（已测）", style = MaterialTheme.typography.bodySmall)
            Text("换 1–6 号位：点对应槽位（已测）", style = MaterialTheme.typography.bodySmall)
            Text("切换位置：方案切换按钮（待实测）", style = MaterialTheme.typography.bodySmall)
            Text(
                "禁止硬编码绝对坐标：x=x0×W/1080，y=y0×H/2340；每步点击后校验，失败即停，重试≤1 次。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onMapping) { Text("打开指令映射 / 坐标库 →", color = Brand) }
        }

        WarningCard("安全红线：不读内存、不注入、不 Hook、不绕反作弊；不模拟透视/自瞄/走位；仅自有授权设备，风险自担。")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Step(num: Int, title: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 5.dp)) {
        Box(
            modifier = Modifier.size(22.dp).background(Brand, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(num.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall) }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = PanelMuted)
        }
    }
}
