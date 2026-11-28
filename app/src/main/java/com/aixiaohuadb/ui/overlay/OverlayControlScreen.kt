package com.aixiaohuadb.ui.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.service.OverlayWidget
import com.aixiaohuadb.service.OverlayWidget.Companion.MAX_ALPHA
import com.aixiaohuadb.service.OverlayWidget.Companion.MAX_SIZE_DP
import com.aixiaohuadb.service.OverlayWidget.Companion.MIN_ALPHA
import com.aixiaohuadb.service.OverlayWidget.Companion.MIN_SIZE_DP
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.OpsLinkChain
import com.aixiaohuadb.ui.common.OpsMatrixCard
import com.aixiaohuadb.ui.common.OpsSafeZone
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.Segment
import com.aixiaohuadb.ui.common.ToggleRow
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.util.PermissionState
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 链路与悬浮：把能力矩阵、执行优先级、权限与悬浮参数放在同一套 GAME OPS 语言里。
 * 权限、命名、绑定等真实流程全部保留，只替换组织形式与视觉。
 */
@Composable
fun OverlayControlScreen(
    vm: OverlayControlViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    // 从系统设置授予权限返回前台后刷新，避免 remember 缓存导致状态陈旧
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    permissionRevision
    val widgets by vm.widgets.collectAsState()
    val ball by vm.ball.collectAsState()
    val macros by vm.macros.collectAsState()
    val pendingMacro by vm.pendingMacro.collectAsState()
    val controlMode by vm.controlMode.collectAsState()
    val granted = PermissionState.overlayGranted(context)
    val trackingReady = PermissionState.accessibilityEnabled(context) || PermissionState.usageAccessGranted(context)

    val voiceEnabled by vm.voiceEnabled.collectAsState()
    val bindings by vm.bindings.collectAsState()
    var pendingVoice by remember { mutableStateOf(false) }
    var bindingSlot by remember { mutableIntStateOf(0) }
    var bindingPhrase by remember { mutableStateOf("") }

    ScreenFrame(
        title = "链路与悬浮",
        subtitle = "LINK MATRIX · 执行优先级与权限",
        showBack = true,
        onBack = onBack,
        activeTab = "settings",
        onTab = onTab,
    ) {
        if (!granted) {
            WarningCard("尚未授予悬浮窗权限，悬浮球与指令按钮都无法显示。")
            QuietButton(
                text = "去授权悬浮窗权限",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            )
            Spacer(Modifier.height(Spacing.md))
        }
        if (granted && !trackingReady) {
            WarningCard("无法检测游戏是否在前台：请开启无障碍服务，或授予「使用情况访问」权限，否则指令按钮不会显示。")
            QuietButton(
                text = "去开启使用情况访问",
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                },
            )
            Spacer(Modifier.height(Spacing.md))
        }

        OpsSectionLabel("LINK MATRIX", "执行链优先级 · 运行时状态")
        val adbPrimary = controlMode == ControlMode.ADB
        val a11yPrimary = controlMode == ControlMode.ACCESSIBILITY
        val voiceActive = voiceEnabled && controlMode == ControlMode.VOICE
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                OpsMatrixCard(
                    icon = Icons.Filled.Link,
                    title = "ADB 注入",
                    status = if (adbPrimary) "PRIMARY" else "STANDBY",
                    description = "宏回放优先走系统输入子系统，游戏识别为真实输入；链路页可开启 ADB 录制。",
                    primary = adbPrimary,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                OpsMatrixCard(
                    icon = Icons.Filled.TouchApp,
                    title = "无障碍转发",
                    status = if (a11yPrimary) "PRIMARY" else "READY",
                    description = "录制主路径，也是 ADB 不可用时的回放降级路径。",
                    primary = a11yPrimary,
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                OpsMatrixCard(
                    icon = if (voiceActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                    title = "语音识别",
                    status = if (voiceActive) "ACTIVE" else "OFF",
                    description = "仅在显式开启后拾音，并提示常驻通知与麦克风。",
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                OpsMatrixCard(
                    icon = Icons.Filled.Security,
                    title = "只读回执",
                    status = "FALLBACK",
                    description = "注入失败时显示失败原因，不再静默跳过。",
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        OpsLinkChain()
        Spacer(Modifier.height(Spacing.sm))
        InfoCard {
            Text(
                "执行链优先级固定为 ADB → 无障碍 → 只读回执；上方「控制模式」只决定手动面板使用哪条链路，不会禁用其余能力。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        OpsSectionLabel("INPUT MODE", "仅影响手动面板")
        SectionCard(icon = Icons.Filled.VideogameAsset, title = "控制模式") {
            Segment(
                options = listOf("语音识别", "无障碍", "ADB"),
                selectedIndex = ControlMode.entries.indexOf(controlMode),
                onSelect = { index ->
                    val target = ControlMode.entries[index]
                    if (target == ControlMode.VOICE && !voiceEnabled) pendingVoice = true
                    else vm.setControlMode(target)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                when (controlMode) {
                    ControlMode.VOICE -> "语音模式：说出绑定说法触发用户宏；无常驻拾音之外的旁路。"
                    ControlMode.ACCESSIBILITY -> "无障碍模式：不拾音，点击悬浮宏按钮由 A11y 手势执行。"
                    ControlMode.ADB -> "ADB 模式：不拾音，保留 A11y 事件，后台长连接随时下发命令。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        OpsSectionLabel("OVERLAY CONTROL", "实时生效")
        OpsSafeZone()
        Spacer(Modifier.height(Spacing.sm))
        SectionCard(icon = Icons.Filled.TouchApp, title = "悬浮球") {
            ToggleRow("显示悬浮球", "关闭后仅保留指令按钮", ball.visible) { vm.setBallVisible(it) }
            ToggleRow("锁定位置", "游戏内禁止拖动，防误触", ball.locked) { vm.setBallLocked(it) }
            BallSliders(sizeDp = ball.sizeDp, alphaPercent = ball.alphaPercent, onChange = vm::updateBallLive)
        }

        pendingMacro?.let { pm ->
            Spacer(Modifier.height(Spacing.sm))
            SectionCard(icon = Icons.Filled.VideogameAsset, title = "待命名宏") {
                var pendingName by remember { mutableStateOf(pm.name) }
                OutlinedTextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("宏名称") },
                    singleLine = true,
                )
                Text(
                    "共 ${pm.steps.size} 步（点击序列 + 自动间隔）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuietButton(text = "保存宏", onClick = { vm.savePendingMacro(pendingName) })
                    Spacer(Modifier.width(Spacing.sm))
                    QuietButton(text = "丢弃", onClick = { vm.discardPendingMacro() })
                }
            }
        }

        if (widgets.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            SectionCard(icon = Icons.Filled.TouchApp, title = "已创建（${widgets.size}）") {
                widgets.forEachIndexed { index, w ->
                    WidgetRow(
                        widget = w,
                        onUpdateLive = vm::updateWidgetLive,
                        onLockChange = vm::setWidgetLocked,
                        onRemove = { vm.removeWidget(w.id) },
                    )
                    if (index != widgets.lastIndex) Spacer(Modifier.height(Spacing.md))
                }
            }
        }

        if (macros.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            SectionCard(icon = Icons.Filled.VideogameAsset, title = "我的宏（${macros.size}）") {
                var renameTarget by remember { mutableStateOf<Long?>(null) }
                macros.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("${m.steps.size} 步", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        TextButton(onClick = { renameTarget = m.macroId }) { Text("重命名", color = Brand) }
                        TextButton(onClick = { vm.removeMacro(m.macroId) }) { Text("删除", color = Danger) }
                    }
                }
                renameTarget?.let { macroId ->
                    val current = macros.firstOrNull { it.macroId == macroId }
                    if (current == null) {
                        renameTarget = null
                    } else {
                        var renameText by remember(macroId) { mutableStateOf(current.name) }
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            containerColor = com.aixiaohuadb.ui.theme.Panel,
                            title = { Text("重命名宏") },
                            text = {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.renameMacro(macroId, renameText)
                                    renameTarget = null
                                }) { Text("保存", color = Brand) }
                            },
                            dismissButton = {
                                TextButton(onClick = { renameTarget = null }) { Text("取消") }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        SectionCard(icon = Icons.Filled.AddCircle, title = "语音指令绑定") {
            Text(
                "内置固定指令已删除。把识别槽位绑定到你录制的宏；槽位 15 为拒识槽不可绑定。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = bindingPhrase,
                onValueChange = { bindingPhrase = it },
                label = { Text("说法提示，如：换装连招") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text("槽位 $bindingSlot", style = MaterialTheme.typography.labelSmall, color = Muted)
            Slider(
                value = bindingSlot.toFloat(),
                onValueChange = { bindingSlot = it.toInt().coerceIn(0, 13) },
                valueRange = 0f..13f,
                steps = 12,
            )
            Spacer(Modifier.height(Spacing.sm))
            if (macros.isEmpty()) {
                Text("请先录制并保存一个宏", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                macros.forEach { macro ->
                    TextButton(onClick = {
                        vm.bind(bindingSlot, macro.macroId, bindingPhrase.ifBlank { macro.name })
                    }) {
                        Text("绑定槽位 $bindingSlot → ${macro.name}")
                    }
                }
            }
            bindings.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "槽位 ${b.slot + 1} · ${b.phrase} · 宏 ${b.macroId}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.unbind(b.slot) }) { Text("解绑", color = Danger) }
                }
            }
            Text(
                "录制自定义宏：进游戏后单击悬浮球展开面板 → 开始录制；录制中点「暂停」可让触摸透传给游戏。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
    }

    if (pendingVoice) {
        AlertDialog(
            onDismissRequest = { pendingVoice = false },
            containerColor = com.aixiaohuadb.ui.theme.Panel,
            title = { Text("开启语音识别？") },
            text = { Text("将启动麦克风拾音、加载语音模型并显示常驻通知。关闭后物理释放，不会后台监听。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingVoice = false
                    vm.requestVoiceEnable { ok -> if (ok) vm.setControlMode(ControlMode.VOICE) }
                }) { Text("确认开启", color = Brand) }
            },
            dismissButton = {
                TextButton(onClick = { pendingVoice = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BallSliders(
    sizeDp: Int,
    alphaPercent: Int,
    onChange: (Int, Int) -> Unit,
) {
    var size by remember { mutableFloatStateOf(sizeDp.toFloat()) }
    var alpha by remember { mutableFloatStateOf(alphaPercent.toFloat()) }
    Column {
        Text("大小 ${size.roundToInt()}dp", style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = size,
            onValueChange = {
                size = it
                onChange(it.roundToInt(), alpha.roundToInt())
            },
            valueRange = MIN_SIZE_DP.toFloat()..MAX_SIZE_DP.toFloat(),
        )
        Text("不透明度 ${alpha.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = alpha,
            onValueChange = {
                alpha = it
                onChange(size.roundToInt(), it.roundToInt())
            },
            valueRange = MIN_ALPHA.toFloat()..MAX_ALPHA.toFloat(),
        )
    }
}

@Composable
private fun WidgetRow(
    widget: OverlayWidget,
    onUpdateLive: (String, Int, Int) -> Unit,
    onLockChange: (String, Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var size by remember(widget.id) { mutableFloatStateOf(widget.sizeDp.toFloat()) }
    var alpha by remember(widget.id) { mutableFloatStateOf(widget.alphaPercent.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(widget.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (widget.kind == com.aixiaohuadb.service.WidgetKind.MACRO) "宏" else "指令",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            TextButton(onClick = { onLockChange(widget.id, !widget.locked) }) {
                Text(if (widget.locked) "已锁定" else "未锁定", color = if (widget.locked) Brand else Muted)
            }
            TextButton(onClick = onRemove) { Text("移除", color = Danger) }
        }
        Text("大小 ${size.roundToInt()}dp", style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = size,
            onValueChange = {
                size = it
                onUpdateLive(widget.id, it.roundToInt(), alpha.roundToInt())
            },
            valueRange = MIN_SIZE_DP.toFloat()..MAX_SIZE_DP.toFloat(),
        )
        Text("不透明度 ${alpha.roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = Muted)
        Slider(
            value = alpha,
            onValueChange = {
                alpha = it
                onUpdateLive(widget.id, size.roundToInt(), it.roundToInt())
            },
            valueRange = MIN_ALPHA.toFloat()..MAX_ALPHA.toFloat(),
        )
    }
}
