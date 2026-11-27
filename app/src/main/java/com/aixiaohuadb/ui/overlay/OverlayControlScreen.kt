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
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.service.OverlayWidget
import com.aixiaohuadb.service.OverlayWidget.Companion.MAX_ALPHA
import com.aixiaohuadb.service.OverlayWidget.Companion.MAX_SIZE_DP
import com.aixiaohuadb.service.OverlayWidget.Companion.MIN_ALPHA
import com.aixiaohuadb.service.OverlayWidget.Companion.MIN_SIZE_DP
import com.aixiaohuadb.service.ForegroundGameMonitor
import com.aixiaohuadb.service.OverlayControlService
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.SectionCard
import com.aixiaohuadb.ui.common.Segment
import com.aixiaohuadb.ui.common.ToggleRow
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 悬浮控制一页化设置：权限引导 → 待命名宏 → 球配置 → 已创建按钮/宏 → 添加指令。
 * 滑条实时写 OverlayStore（内存即时生效 + 落盘防抖），悬浮件即时变形预览。
 */
@Composable
fun OverlayControlScreen(
    vm: OverlayControlViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val context = LocalContext.current
    val widgets by vm.widgets.collectAsState()
    val ball by vm.ball.collectAsState()
    val macros by vm.macros.collectAsState()
    val pendingMacro by vm.pendingMacro.collectAsState()
    val controlMode by vm.controlMode.collectAsState()
    val granted = remember { OverlayControlService.isPermissionGranted(context) }
    val trackingReady = remember { ForegroundGameMonitor.hasForegroundTracking(context) }

    val voiceEnabled by vm.voiceEnabled.collectAsState()
    val bindings by vm.bindings.collectAsState()
    var pendingVoice by remember { mutableStateOf(false) }
    var bindingSlot by remember { mutableIntStateOf(0) }
    var bindingPhrase by remember { mutableStateOf("") }

    ScreenFrame(
        title = "悬浮控制",
        subtitle = "游戏内长按悬浮球可直接调节",
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
            WarningCard("无法检测王者荣耀是否在前台：请开启无障碍服务，或授予「使用情况访问」权限，否则指令按钮不会显示。")
            QuietButton(
                text = "去开启使用情况访问",
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                },
            )
            Spacer(Modifier.height(Spacing.md))
        }

        // 控制模式：三值并列，语音未开启时二次确认
        SectionCard(icon = Icons.Filled.VideogameAsset, title = "控制模式") {
            Text(
                "默认无障碍模式，不拾音。语音需显式开启；ADB 保持前台长连接。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
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

        InfoCard {
            Text(
                "悬浮球 = 模式入口（单击展开面板，面板内可切换模式、调节大小透明度、录制宏）；指令/宏按钮只在王者荣耀前台显示，单击直达执行，拖动调整位置、松手自动贴边。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 待命名宏（录制后命名窗被 ROM 拦截时的兜底）
        pendingMacro?.let { pm ->
            SectionCard(icon = Icons.Filled.VideogameAsset, title = "待命名宏") {
                var name by remember { mutableStateOf(pm.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
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
                Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    QuietButton(text = "保存宏", onClick = { vm.savePendingMacro(name) })
                    Spacer(Modifier.width(Spacing.sm))
                    QuietButton(text = "丢弃", onClick = { vm.discardPendingMacro() })
                }
            }
        }

        // 悬浮球配置
        SectionCard(icon = Icons.Filled.TouchApp, title = "悬浮球") {
            ToggleRow("显示悬浮球", "关闭后仅保留指令按钮", ball.visible) { vm.setBallVisible(it) }
            ToggleRow("锁定位置", "游戏内禁止拖动，防误触", ball.locked) { vm.setBallLocked(it) }
            BallSliders(sizeDp = ball.sizeDp, alphaPercent = ball.alphaPercent, onChange = vm::updateBallLive)
        }

        // 已创建按钮与宏
        if (widgets.isNotEmpty()) {
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

        // 宏管理
        if (macros.isNotEmpty()) {
            SectionCard(icon = Icons.Filled.VideogameAsset, title = "我的宏（${macros.size}）") {
                var renameTarget by remember { mutableStateOf<Long?>(null) }
                macros.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${m.steps.size} 步",
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                        TextButton(onClick = { renameTarget = m.macroId }) { Text("重命名") }
                        TextButton(onClick = { vm.removeMacro(m.macroId) }) { Text("删除", color = Danger) }
                    }
                }
                renameTarget?.let { macroId ->
                    val current = macros.firstOrNull { it.macroId == macroId }
                    if (current == null) {
                        renameTarget = null
                    } else {
                        var name by remember(macroId) { mutableStateOf(current.name) }
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            title = { Text("重命名宏") },
                            text = {
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.renameMacro(macroId, name)
                                    renameTarget = null
                                }) { Text("保存") }
                            },
                            dismissButton = {
                                TextButton(onClick = { renameTarget = null }) { Text("取消") }
                            },
                        )
                    }
                }
            }
        }

        // 语音槽位绑定：0..13（14 为拒识槽，不可绑定），说法 → 用户宏
        SectionCard(icon = Icons.Filled.AddCircle, title = "语音指令绑定") {
            Text(
                "内置固定指令已删除。把识别槽位绑定到你录制的宏，指令名只用于提示；槽位 15 为拒识槽不可绑定。",
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
                "录制自定义宏：进游戏后单击悬浮球展开面板 → 开始录制；📷 截屏后可在应用内标注。",
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
            title = { Text("开启语音识别？") },
            text = { Text("将启动麦克风拾音、加载语音模型并显示常驻通知。关闭后物理释放，不会后台监听。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingVoice = false
                    vm.requestVoiceEnable { ok -> if (ok) vm.setControlMode(ControlMode.VOICE) }
                }) { Text("确认开启") }
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
                Text(widget.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (widget.kind == com.aixiaohuadb.service.WidgetKind.MACRO) "宏" else "指令",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            TextButton(onClick = { onLockChange(widget.id, !widget.locked) }) {
                Text(if (widget.locked) "已锁定" else "未锁定")
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
