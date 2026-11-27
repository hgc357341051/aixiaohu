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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.exec.CommandLibrary
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

        // 控制模式：语音识别 / 手动点触
        SectionCard(icon = Icons.Filled.VideogameAsset, title = "控制模式") {
            Text(
                "选择指令的触发方式，游戏中也可长按悬浮球随时切换。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            Segment(
                options = listOf("语音识别", "手动点触"),
                selectedIndex = if (controlMode == ControlMode.VOICE) 0 else 1,
                onSelect = { index ->
                    vm.setControlMode(if (index == 0) ControlMode.VOICE else ControlMode.MANUAL)
                },
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                when (controlMode) {
                    ControlMode.VOICE -> "语音模式：说「你好小胡」唤醒后下达指令，指令按钮作为辅助入口。"
                    ControlMode.MANUAL -> "手动模式：关闭拾音与模型，仅靠悬浮指令/宏按钮点击执行，无常驻监听。"
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

        // 添加指令
        val available = CommandLibrary.panelItems.filter { (_, id) -> !widgets.any { it.commandId == id } }
        SectionCard(icon = Icons.Filled.AddCircle, title = "添加指令按钮") {
            if (available.isEmpty()) {
                Text("全部指令都已创建", style = MaterialTheme.typography.bodySmall, color = Muted)
            } else {
                available.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (label, id) ->
                            QuietButton(text = label, onClick = { vm.addPreset(id, label) }, enabled = granted)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
            Text(
                "录制自定义宏：进游戏后单击悬浮球展开面板 → 开始录制。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
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
