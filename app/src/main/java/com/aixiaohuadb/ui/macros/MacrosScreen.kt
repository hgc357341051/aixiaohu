package com.aixiaohuadb.ui.macros

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.ui.common.ConfirmDialog
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.PillChip
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandStrong
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.Ink2
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.OnBrand
import com.aixiaohuadb.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 宏列表：只读展示 + 三个回调动作，不直接触碰 OverlayStore。
 *
 * 页面自带顶部栏与固定说明条（ScreenFrame 的内容区是 verticalScroll，放不下固定页脚），
 * 因此不渲染底部页签；[onTab] 与既有页面签名保持一致，由导航层注入备用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacrosScreen(
    macros: List<MacroConfig>,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
    onRun: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<MacroConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("我的宏", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (macros.isEmpty()) "尚未录制" else "共 ${macros.size} 个 · 抬手后生效",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Brand,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        if (macros.isEmpty()) {
            EmptyMacros(
                modifier = Modifier.weight(1f),
                onNew = onNew,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = Spacing.screenPadding,
            ) {
                items(items = macros, key = { it.macroId }) { macro ->
                    MacroRow(
                        macro = macro,
                        onRun = { onRun(macro.macroId) },
                        onEdit = { onEdit(macro.macroId) },
                        onDelete = { pendingDelete = macro },
                    )
                }
            }
        }

        FooterNote()
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

@Composable
private fun EmptyMacros(modifier: Modifier = Modifier, onNew: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconCircle(
            icon = Icons.Filled.VideogameAsset,
            tint = BrandStrong,
            container = BrandSoft,
            size = 64.dp,
        )
        Spacer(Modifier.height(Spacing.md))
        Text("还没有宏，先录一个", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "录制一段手势，之后用悬浮按钮或语音一句话回放",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        PrimaryButton(text = "开始录制", onClick = onNew)
    }
}

@Composable
private fun MacroRow(
    macro: MacroConfig,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 估算时长与 MacroDocument.estimatedDurationMs 同一口径：步上前置等待 + 手势/等待时长
    val durationMs = remember(macro.steps) {
        MacroAdapter.fromExecSteps(macro.steps).sumOf { macroStepDurationMs(it) }
    }
    val createdText = remember(macro.createdAt) { relativeDayLabel(macro.createdAt) }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    macro.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillChip("${macro.steps.size} 步")
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        "约 ${formatMacroDuration(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink2,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(createdText, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onRun,
                    modifier = Modifier.height(34.dp),
                    shape = ButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text("运行", style = MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onEdit) {
                        Text("编辑", style = MaterialTheme.typography.labelSmall, color = Brand)
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除", style = MaterialTheme.typography.labelSmall, color = Danger)
                    }
                }
            }
        }
    }
}

/** 固定在底部的录制口径说明：解释转发录制为什么会有一拍延迟 */
@Composable
private fun FooterNote() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Line),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = Muted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                "录制时用手势操作游戏，抬手后才会生效",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}

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
