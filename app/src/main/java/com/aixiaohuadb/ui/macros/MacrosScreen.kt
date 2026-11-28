package com.aixiaohuadb.ui.macros

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.ui.common.ConfirmDialog
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.InfoCard
import com.aixiaohuadb.ui.common.OpsHeroCard
import com.aixiaohuadb.ui.common.OpsMacroCard
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.DangerBg
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.OnBrand
import com.aixiaohuadb.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 宏库：以任务卡展示用户宏，所有动作通过回调上抛，不直接触碰 OverlayStore。
 * 步骤时长口径与编辑器共用下方两个函数。
 */
@Composable
fun MacrosScreen(
    macros: List<MacroConfig>,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
    onRun: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
    overlayEnabled: Boolean = true,
    onToggleOverlay: (Boolean) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<MacroConfig?>(null) }

    ScreenFrame(
        title = "宏库",
        subtitle = "ACTIVE MISSIONS · 任务卡",
        showBack = true,
        onBack = onBack,
        activeTab = "panel",
        onTab = onTab,
    ) {
        OpsHeroCard(
            title = "任务台就绪",
            description = "录制的宏以任务卡展示；点运行直接回放，点编辑进入工作台调延迟与坐标。",
            primaryLabel = "录制新宏",
            onPrimary = onNew,
            badge = "L1 DECK",
        )

        OpsSectionLabel("OVERLAY", "悬浮窗控制")
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(
                    icon = if (overlayEnabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    tint = if (overlayEnabled) Brand else Danger,
                    container = if (overlayEnabled) BrandSoft else DangerBg,
                    size = 30.dp,
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("悬浮窗", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (overlayEnabled) "游戏中显示悬浮球与宏按钮" else "已关闭：悬浮球与宏按钮全部隐藏",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
                Button(
                    onClick = { onToggleOverlay(!overlayEnabled) },
                    modifier = Modifier.height(34.dp),
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (overlayEnabled) DangerBg else Brand,
                        contentColor = if (overlayEnabled) Danger else OnBrand,
                    ),
                    border = if (overlayEnabled) BorderStroke(1.dp, Danger.copy(alpha = 0.4f)) else null,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                ) {
                    Text(
                        if (overlayEnabled) "关闭悬浮窗" else "重新开启",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        OpsSectionLabel("ACTIVE MISSIONS", "共 ${macros.size} 个")
        if (macros.isEmpty()) {
            InfoCard {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    IconCircle(icon = Icons.Filled.Link, tint = Brand, container = BrandSoft, size = 56.dp)
                    Spacer(Modifier.height(Spacing.md))
                    Text("还没有宏，先录一个", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "录制一段手势，之后用悬浮按钮或直接点任务卡回放",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                macros.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        pair.forEach { macro ->
                            val durationMs = remember(macro.steps) {
                                MacroAdapter.fromExecSteps(macro.steps).sumOf { macroStepDurationMs(it) }
                            }
                            OpsMacroCard(
                                name = macro.name,
                                summary = missionSummary(macro.steps),
                                footerLeft = "${macro.steps.size} STEP",
                                footerRight = formatMacroDuration(durationMs),
                                onRun = { onRun(macro.macroId) },
                                onEdit = { onEdit(macro.macroId) },
                                onDelete = { pendingDelete = macro },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge("录制口径", Tone.WARN)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    "录制时用手势操作游戏，抬手后才会生效；时长可在工作台逐项调整。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除「${target.name}」？",
            text = "删除后无法恢复，该宏从悬浮按钮与语音绑定中一并移除。",
            confirmLabel = "删除",
            onCancel = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(target.macroId)
            },
        )
    }
}

@Suppress("DEPRECATION")
private fun missionSummary(steps: List<com.aixiaohuadb.exec.ExecStep>): String =
    steps.take(4).joinToString(" · ") { step ->
        when (step) {
            is com.aixiaohuadb.exec.ExecStep.Tap -> "点击"
            is com.aixiaohuadb.exec.ExecStep.Swipe -> "滑动"
            is com.aixiaohuadb.exec.ExecStep.MultiGesture -> "多指"
            is com.aixiaohuadb.exec.ExecStep.Wait -> "等待"
            is com.aixiaohuadb.exec.ExecStep.Verify -> "校验"
        }
    } + if (steps.size > 4) " …" else ""

/** 单步占用时长：前置等待 + 手势/等待时长，与 MacroDocument.estimatedDurationMs 同口径 */
internal fun macroStepDurationMs(step: MacroStep): Long = step.delayBeforeMs + when (step) {
    is MacroStep.Tap -> step.durationMs.toLong()
    is MacroStep.Swipe -> step.durationMs.toLong()
    is MacroStep.LongPress -> step.durationMs.toLong()
    is MacroStep.Wait -> step.waitMs
}

/** 时长文案：毫秒 / 秒两档，避免列表里出现 5 位数字 */
internal fun formatMacroDuration(ms: Long): String = when {
    ms <= 0L -> "0 秒"
    ms < 1000L -> "$ms 毫秒"
    ms % 1000L == 0L -> "${ms / 1000L} 秒"
    ms < 10_000L -> "${ms / 1000L}.${(ms % 1000L) / 100L} 秒"
    else -> "${(ms / 1000.0).roundToInt()} 秒"
}

/** 创建时间相对文案；createdAt 缺失（旧数据为 0）时如实说明 */
private fun relativeDayLabel(createdAt: Long): String {
    if (createdAt <= 0L) return "创建时间未知"
    val zone = ZoneId.systemDefault()
    val created = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(created, LocalDate.now(zone))
    return when {
        days <= 0L -> "今天创建"
        days == 1L -> "昨天创建"
        else -> "$days 天前创建"
    }
}
