package com.aixiaohuadb.ui.adb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aixiaohuadb.adb.AdbPhase
import com.aixiaohuadb.adb.CommandResult

// ---------------------------------------------------------------------------
// GAME OPS 视觉 token 统一取自 theme，避免连接页与任务台出现两套近似色。
// ---------------------------------------------------------------------------
private val OpsBg = com.aixiaohuadb.ui.theme.Bg
private val OpsPanel = com.aixiaohuadb.ui.theme.Panel
private val OpsPanel2 = com.aixiaohuadb.ui.theme.Panel2
private val OpsLine = com.aixiaohuadb.ui.theme.Line
private val OpsInk = com.aixiaohuadb.ui.theme.Ink
private val OpsMuted = com.aixiaohuadb.ui.theme.Muted
private val OpsFaint = com.aixiaohuadb.ui.theme.PanelFaint
private val OpsMint = com.aixiaohuadb.ui.theme.Brand
private val OpsViolet = com.aixiaohuadb.ui.theme.Violet
private val OpsRed = com.aixiaohuadb.ui.theme.Danger
private val OpsAmber = com.aixiaohuadb.ui.theme.Warn
private val OpsOnMint = com.aixiaohuadb.ui.theme.OnBrand
private val OpsShape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)

@Composable
fun AdbScreen(
    vm: AdbViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
    onStartAdbRecord: () -> Unit,
) {
    val s by vm.ui.collectAsState()
    var advanced by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.onResume() }

    Column(modifier = Modifier.fillMaxSize().background(OpsBg)) {
        OpsTopBar(phase = s.status.phase, onBack = onBack, onTab = onTab)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            OpsHero(
                phase = s.status.phase,
                endpoint = s.status.endpointLabel,
                paired = s.status.pairedLabel,
                heartbeatOk = s.bridge.heartbeatOk,
                onRecheck = vm::recheck,
            )

            s.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                OpsMessage(msg, vm::dismissMessage)
            }

            Spacer(Modifier.height(12.dp))
            OpsStepTrack(s.status.phase)

            Spacer(Modifier.height(18.dp))
            OpsSectionTitle("01", "准备设备", "系统侧设置")
            OpsPrepCard(vm = vm, busy = s.busy)

            Spacer(Modifier.height(18.dp))
            OpsSectionTitle("02", "配对设备", "一次性，配对端口仅当场有效")
            OpsPairCard(
                host = s.pairHost,
                port = s.pairPort,
                code = s.pairCode,
                busy = s.busy,
                onHost = vm::onPairHost,
                onPort = vm::onPairPort,
                onCode = vm::onPairCode,
                onPair = vm::pair,
            )

            Spacer(Modifier.height(18.dp))
            OpsSectionTitle("03", "连接设备", "用无线调试主页的连接端口")
            OpsConnectCard(
                host = s.connectHost,
                port = s.connectPort,
                phase = s.status.phase,
                busy = s.busy,
                endpoint = s.status.endpointLabel,
                onHost = vm::onConnectHost,
                onPort = vm::onConnectPort,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
            )

            Spacer(Modifier.height(18.dp))
            OpsSectionTitle("04", "验证与增强", "确认 shell 真的可用")
            OpsVerifyCard(
                phase = s.status.phase,
                busy = s.busy,
                probe = s.probeResult,
                bridge = s.bridge,
                onProbe = vm::probe,
                onStartAdbRecord = onStartAdbRecord,
            )

            Spacer(Modifier.height(18.dp))
            OpsAdvancedToggle(expanded = advanced, onToggle = { advanced = !advanced })

            if (advanced) {
                Spacer(Modifier.height(10.dp))
                OpsRawCard(
                    command = s.rawCommand,
                    busy = s.busy,
                    result = s.rawResult,
                    onCommand = vm::onRawCommand,
                    onRequest = vm::requestRaw,
                )
                Spacer(Modifier.height(10.dp))
                OpsAuditCard(audit = s.audit, onRefresh = vm::refreshAudit)
            }

            Spacer(Modifier.height(18.dp))
            OpsEnvCard(status = s.status)

            Spacer(Modifier.height(12.dp))
            OpsWarning()

            Spacer(Modifier.height(20.dp))
        }

        OpsBottomBar(onTab = onTab)
    }

    s.pendingRaw?.let { req ->
        AlertDialog(
            onDismissRequest = vm::cancelRaw,
            containerColor = OpsPanel,
            titleContentColor = OpsInk,
            textContentColor = OpsMuted,
            title = { Text("确认执行 raw shell", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("将向已连接设备发送以下命令：", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(req.command, color = OpsInk, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("来源：${req.source.name} · 超时 ${req.timeoutMs}ms", color = OpsFaint, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmRaw) { Text("确认执行", color = OpsMint) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelRaw) { Text("取消", color = OpsMuted) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部 / 阶段导航
// ---------------------------------------------------------------------------

@Composable
private fun OpsTopBar(phase: AdbPhase, onBack: () -> Unit, onTab: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpsPanel)
            .border(BorderStroke(1.dp, OpsLine.copy(alpha = 0.7f)))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OpsMuted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "GAME OPS",
                color = OpsMint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text("LINK / ADB 连接", color = OpsFaint, fontSize = 10.sp)
        }
        OpsBadge(text = phase.name, color = phaseColor(phase))
        IconButton(onClick = { onTab("settings") }) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = OpsMuted)
        }
    }
}

@Composable
private fun OpsHero(
    phase: AdbPhase,
    endpoint: String?,
    paired: String?,
    heartbeatOk: Boolean,
    onRecheck: () -> Unit,
) {
    val info = phaseInfo(phase)
    OpsCard(accent = info.color.copy(alpha = 0.6f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpsIcon(icon = info.icon, tint = info.color, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.title, color = OpsInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(info.desc, color = OpsMuted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OpsBadge("当前端点：${endpoint ?: "未连接"}", if (endpoint != null) OpsMint else OpsFaint)
            OpsBadge("已配对：${paired ?: "无"}", OpsViolet)
            OpsBadge("心跳：${if (heartbeatOk) "正常" else "未通过"}", if (heartbeatOk) OpsMint else OpsAmber)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            OpsSecondaryButton(text = "重新检测", icon = Icons.Filled.Refresh, onClick = onRecheck)
        }
    }
}

@Composable
private fun OpsStepTrack(phase: AdbPhase) {
    val current = when (phase) {
        AdbPhase.NOT_READY, AdbPhase.UNSUPPORTED -> 0
        AdbPhase.DISCONNECTED -> 1
        AdbPhase.ERROR -> 2
        AdbPhase.PAIRED -> 2
        AdbPhase.CONNECTED -> 3
    }
    val labels = listOf("准备", "配对", "连接", "验证")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OpsShape,
        color = OpsPanel,
        border = BorderStroke(1.dp, OpsLine),
    ) {
        Row(modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp)) {
            labels.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val done = index <= current
                    Surface(
                        shape = CircleShape,
                        color = if (done) OpsMint else OpsPanel2,
                        border = BorderStroke(1.dp, if (done) OpsMint else OpsLine),
                        modifier = Modifier.size(26.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "${index + 1}",
                                color = if (done) OpsOnMint else OpsFaint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(label, color = if (done) OpsInk else OpsFaint, fontSize = 10.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 四个步骤卡片
// ---------------------------------------------------------------------------

@Composable
private fun OpsPrepCard(vm: AdbViewModel, busy: Boolean) {
    OpsCard(accent = OpsLine) {
        OpsLead("无线调试需要 Android 11+。配对端口只在系统配对弹窗打开期间有效；连接端口在无线调试主页。")
        Spacer(Modifier.height(8.dp))
        OpsMiniRow("1", "开发者选项", "关于手机 → 连续点按版本号 7 次")
        OpsMiniRow("2", "无线调试", "开发者选项 → 无线调试 → 打开开关")
        OpsMiniRow("3", "配对设备", "点「使用配对码配对设备」，记下端口与 6 位码")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpsGhostButton("开发者选项", Icons.Filled.Settings, enabled = !busy, onClick = vm::openDeveloperOptions)
            OpsGhostButton("无线调试", Icons.Filled.Smartphone, enabled = !busy, onClick = vm::openWirelessDebugging)
            OpsGhostButton("官方说明", Icons.Filled.OpenInNew, enabled = !busy, onClick = vm::openAdbDocs)
        }
    }
}

@Composable
private fun OpsPairCard(
    host: String,
    port: String,
    code: String,
    busy: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onCode: (String) -> Unit,
    onPair: () -> Unit,
) {
    OpsCard(accent = OpsViolet.copy(alpha = 0.5f)) {
        OpsField(host, onHost, "配对地址", "默认取本机局域网 IP")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OpsField(port, onPort, "配对端口", "系统弹窗显示", Modifier.weight(1f))
            OpsField(code, onCode, "6 位配对码", "仅数字", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OpsPrimaryButton(text = "发起配对", icon = Icons.Filled.Security, busy = busy, onClick = onPair)
    }
}

@Composable
private fun OpsConnectCard(
    host: String,
    port: String,
    phase: AdbPhase,
    busy: Boolean,
    endpoint: String?,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    OpsCard(accent = OpsMint.copy(alpha = 0.5f)) {
        OpsField(host, onHost, "设备地址", "默认取本机局域网 IP")
        Spacer(Modifier.height(8.dp))
        OpsField(port, onPort, "连接端口", "无线调试主页显示，不是配对端口")
        Spacer(Modifier.height(10.dp))
        OpsBadge("当前端点：${endpoint ?: "未连接"}", if (endpoint != null) OpsMint else OpsFaint)
        Spacer(Modifier.height(12.dp))
        OpsPrimaryButton(text = "连接设备", icon = Icons.Filled.Link, busy = busy, onClick = onConnect)
        Spacer(Modifier.height(8.dp))
        OpsDangerButton(
            text = "断开连接",
            enabled = phase == AdbPhase.CONNECTED && !busy,
            onClick = onDisconnect,
        )
    }
}

@Composable
private fun OpsVerifyCard(
    phase: AdbPhase,
    busy: Boolean,
    probe: CommandResult?,
    bridge: com.aixiaohuadb.adb.AdbBridgeState,
    onProbe: () -> Unit,
    onStartAdbRecord: () -> Unit,
) {
    OpsCard(accent = OpsMint.copy(alpha = 0.5f)) {
        OpsLead("用一个无害的 HOME 按键验证 shell；不会操作你的应用数据。验证通过后可启用 ADB 高保真录制。")
        Spacer(Modifier.height(10.dp))
        OpsSecondaryButton(text = "链路自检", icon = Icons.Filled.Terminal, enabled = !busy, onClick = onProbe)
        probe?.let {
            Spacer(Modifier.height(8.dp))
            OpsBadge(
                text = if (it.ok) "自检通过 · shell 可用" else "自检未通过 · ${it.message}",
                color = if (it.ok) OpsMint else OpsAmber,
            )
        }
        Spacer(Modifier.height(12.dp))
        OpsEnvRow("桥接阶段", bridge.phase.name)
        OpsEnvRow("最近心跳", if (bridge.heartbeatOk) "正常" else "未通过")
        OpsEnvRow("连接端点", bridge.endpoint ?: "未连接")
        OpsEnvRow("排队命令", bridge.queued.toString())
        OpsEnvRow("重连次数", bridge.retryCount.toString())
        bridge.message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = OpsRed, fontSize = 10.sp)
        }
        Spacer(Modifier.height(14.dp))
        OpsPrimaryButton(
            text = "ADB 高保真录制",
            icon = Icons.Filled.Security,
            enabled = phase == AdbPhase.CONNECTED && !busy,
            onClick = onStartAdbRecord,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Type A 设备也能按帧录制；Type B 的多指语义更可靠。点完后会自动切回游戏，用顶部胶囊结束。",
            color = OpsFaint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// 高级区 / 环境 / 警示 / 底部导航
// ---------------------------------------------------------------------------

@Composable
private fun OpsAdvancedToggle(expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = OpsShape,
        color = OpsPanel,
        border = BorderStroke(1.dp, OpsLine),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = OpsViolet,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("高级 · raw shell 与审计", color = OpsInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OpsBadge("默认收起", OpsFaint)
        }
    }
}

@Composable
private fun OpsRawCard(
    command: String,
    busy: Boolean,
    result: String?,
    onCommand: (String) -> Unit,
    onRequest: () -> Unit,
) {
    OpsCard(accent = OpsAmber.copy(alpha = 0.6f)) {
        OpsLead("任意 shell 均需显式确认；常见不可逆命令会被拦截，审计不可关闭。风险自负。")
        Spacer(Modifier.height(10.dp))
        OpsField(command, onCommand, "shell 原文", "例如：input keyevent 3")
        Spacer(Modifier.height(10.dp))
        OpsPrimaryButton(text = "发送 raw shell", icon = Icons.Filled.Terminal, busy = busy, onClick = onRequest)
        result?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = OpsMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OpsAuditCard(
    audit: List<com.aixiaohuadb.adb.AdbAuditEntry>,
    onRefresh: () -> Unit,
) {
    OpsCard(accent = OpsLine) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("审计日志 · ${audit.size}", color = OpsInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OpsGhostButton("刷新", Icons.Filled.Refresh, onClick = onRefresh)
        }
        Spacer(Modifier.height(8.dp))
        if (audit.isEmpty()) {
            Text("暂无记录", color = OpsFaint, fontSize = 10.sp)
        } else {
            audit.take(6).forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        "${entry.time} · ${entry.decision} · ${if (entry.ok) "OK" else "FAIL"}",
                        color = if (entry.ok) OpsMint else OpsRed,
                        fontSize = 9.sp,
                    )
                }
                Text(entry.command, color = OpsMuted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun OpsEnvCard(status: com.aixiaohuadb.adb.AdbStatus) {
    OpsCard(accent = OpsLine) {
        Text("真机环境", color = OpsInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OpsEnvRow("系统版本", "Android ${status.env.sdkInt}")
        OpsEnvRow("无线配对", if (status.env.supportsWirelessPairing) "支持（Android 11+）" else "不支持")
        OpsEnvRow("屏幕分辨率", "${status.env.screenWidth} × ${status.env.screenHeight}")
        OpsEnvRow("当前端点", status.endpointLabel ?: "未连接")
        OpsEnvRow("已配对端点", status.pairedLabel ?: "尚未配对")
    }
}

@Composable
private fun OpsWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OpsShape,
        color = OpsAmber.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, OpsAmber.copy(alpha = 0.35f)),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            OpsIcon(icon = Icons.Filled.Security, tint = OpsAmber, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "仅限自有设备与测试环境。模拟点击可能违反应用或游戏条款，风险自担；本应用不内置提权组件，也不读取设备私有数据。",
                color = OpsAmber,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun OpsBottomBar(onTab: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpsPanel)
            .border(BorderStroke(1.dp, OpsLine.copy(alpha = 0.8f)))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(
            Triple("home", "作战台", Icons.Filled.Home),
            Triple("panel", "宏面板", Icons.Filled.GridView),
            Triple("settings", "设置", Icons.Filled.Settings),
        ).forEach { (route, label, icon) ->
            val active = route == "settings"
            Column(
                modifier = Modifier.clickable { onTab(route) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(if (active) OpsMint else Color.Transparent),
                )
                Spacer(Modifier.height(5.dp))
                Icon(icon, contentDescription = label, tint = if (active) OpsMint else OpsFaint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(2.dp))
                Text(label, color = if (active) OpsMint else OpsFaint, fontSize = 10.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用小组件
// ---------------------------------------------------------------------------

@Composable
private fun OpsCard(
    modifier: Modifier = Modifier,
    accent: Color = OpsLine,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = OpsShape,
        color = OpsPanel2,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun OpsSectionTitle(index: String, title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(index, color = OpsMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
        Text(title, color = OpsInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(hint, color = OpsFaint, fontSize = 9.sp)
    }
}

@Composable
private fun OpsLead(text: String) {
    Text(text, color = OpsMuted, fontSize = 10.sp, lineHeight = 15.sp)
}

@Composable
private fun OpsMiniRow(number: String, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(
            shape = CircleShape,
            color = OpsPanel,
            border = BorderStroke(1.dp, OpsLine),
            modifier = Modifier.size(20.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(number, color = OpsMint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = OpsInk, fontSize = 11.sp)
            Text(desc, color = OpsFaint, fontSize = 9.sp)
        }
    }
}

@Composable
private fun OpsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = OpsFaint) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp, 10.dp, 3.dp, 10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OpsInk,
            unfocusedTextColor = OpsInk,
            disabledTextColor = OpsFaint,
            focusedContainerColor = OpsPanel,
            unfocusedContainerColor = OpsPanel,
            disabledContainerColor = OpsPanel,
            cursorColor = OpsMint,
            focusedBorderColor = OpsMint,
            unfocusedBorderColor = OpsLine,
            disabledBorderColor = OpsLine,
            focusedLabelColor = OpsMint,
            unfocusedLabelColor = OpsMuted,
            disabledLabelColor = OpsFaint,
            focusedPlaceholderColor = OpsFaint,
            unfocusedPlaceholderColor = OpsFaint,
        ),
    )
}

@Composable
private fun OpsPrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(10.dp, 10.dp, 3.dp, 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = OpsMint,
            contentColor = OpsOnMint,
            disabledContainerColor = OpsPanel,
            disabledContentColor = OpsFaint,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(color = OpsOnMint, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OpsSecondaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(9.dp, 9.dp, 3.dp, 9.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OpsMint, disabledContentColor = OpsFaint),
        border = BorderStroke(1.dp, OpsLine),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp)
    }
}

@Composable
private fun OpsGhostButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(9.dp, 9.dp, 3.dp, 9.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OpsMuted, disabledContentColor = OpsFaint),
        border = BorderStroke(1.dp, OpsLine),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 10.sp)
    }
}

@Composable
private fun OpsDangerButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = RoundedCornerShape(10.dp, 10.dp, 3.dp, 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OpsRed, disabledContentColor = OpsFaint),
        border = BorderStroke(1.dp, OpsRed.copy(alpha = 0.5f)),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OpsIcon(icon: ImageVector, tint: Color = OpsMint, size: Dp = 38.dp) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = tint.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f)),
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun OpsBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OpsEnvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = OpsFaint, fontSize = 10.sp)
        Text(value, color = OpsInk, fontSize = 10.sp)
    }
}

@Composable
private fun OpsMessage(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OpsShape,
        color = OpsAmber.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, OpsAmber.copy(alpha = 0.4f)),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = OpsAmber, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) { Text("知道了", color = OpsAmber, fontSize = 10.sp) }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态映射
// ---------------------------------------------------------------------------

private data class PhaseInfo(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val desc: String,
)

private fun phaseInfo(phase: AdbPhase): PhaseInfo = when (phase) {
    AdbPhase.CONNECTED -> PhaseInfo(
        Icons.Filled.CheckCircle, OpsMint, "链路已就绪",
        "shell 探活通过，可执行输入命令与 ADB 高保真录制。",
    )
    AdbPhase.PAIRED -> PhaseInfo(
        Icons.Filled.Security, OpsViolet, "已配对，未连接",
        "身份已保存。回到无线调试主页读取连接端口，再完成连接。",
    )
    AdbPhase.DISCONNECTED -> PhaseInfo(
        Icons.Filled.LinkOff, OpsMuted, "尚未连接",
        "先开启无线调试并配对，再填入连接端口。配对端口不能用于连接。",
    )
    AdbPhase.ERROR -> PhaseInfo(
        Icons.Filled.LinkOff, OpsRed, "连接异常",
        "核对 IP 与连接端口；系统配对弹窗关闭后配对端口会失效。",
    )
    AdbPhase.NOT_READY -> PhaseInfo(
        Icons.Filled.Computer, OpsAmber, "尚未就绪",
        "请先开启开发者选项与无线调试。",
    )
    AdbPhase.UNSUPPORTED -> PhaseInfo(
        Icons.Filled.Computer, OpsAmber, "系统不支持",
        "无线调试配对需要 Android 11 及以上。",
    )
}

private fun phaseColor(phase: AdbPhase): Color = phaseInfo(phase).color
