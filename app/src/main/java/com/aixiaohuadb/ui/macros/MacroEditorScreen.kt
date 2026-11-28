package com.aixiaohuadb.ui.macros

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.ui.common.ConfirmDialog
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.OpsSectionLabel
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.StatusBadge
import com.aixiaohuadb.ui.common.Tone
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandStrong
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.DangerBg
import com.aixiaohuadb.ui.theme.FieldShape
import com.aixiaohuadb.ui.theme.Ink
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.LineStrong
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.OnBrand
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelFaint
import com.aixiaohuadb.ui.theme.SmShape
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Surface2
import com.aixiaohuadb.ui.theme.Violet
import com.aixiaohuadb.ui.theme.VioletBg
import com.aixiaohuadb.ui.theme.Warn
import com.aixiaohuadb.ui.theme.WarnBg
import kotlin.math.roundToInt

/**
 * 宏工作台：坐标预览与步骤时间轴同屏。
 * 状态流与旧版一致（MacroAdapter 转入/转出），只重写视觉与交互层级。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(
    macro: MacroConfig?,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
    onSave: (name: String, steps: List<ExecStep>) -> Unit,
    onTestStep: (MacroStep) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (macro == null) {
        MissingMacro(onBack = onBack)
        return
    }
    val source = macro

    var savedName by remember(source.macroId) { mutableStateOf(source.name) }
    var savedSteps by remember(source.macroId) { mutableStateOf(MacroAdapter.fromExecSteps(source.steps)) }
    val steps = remember(source.macroId) {
        mutableStateListOf<MacroStep>().apply { addAll(savedSteps) }
    }
    var name by remember(source.macroId) { mutableStateOf(source.name) }
    var expandedId by remember(source.macroId) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(source.macroId) { mutableStateOf(false) }
    var confirmExit by remember(source.macroId) { mutableStateOf(false) }

    val dirty = name != savedName || steps.toList() != savedSteps
    val totalMs = steps.sumOf { macroStepDurationMs(it) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        "GAME OPS",
                        color = Brand,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.Bold,
                    )
                    Text("${source.name} / EDIT", style = MaterialTheme.typography.titleMedium)
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Brand)
                }
            },
            actions = {
                StatusBadge(
                    text = if (dirty) "WIP" else "SYNCED",
                    tone = if (dirty) Tone.WARN else Tone.OK,
                )
                Spacer(Modifier.width(Spacing.sm))
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = Spacing.screenPadding,
        ) {
            item(key = "preview") {
                OpsCoordinatePreview(steps = steps)
                Spacer(Modifier.height(Spacing.md))
            }
            item(key = "header") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("宏名称") },
                    singleLine = true,
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge("${steps.size} STEP", Tone.BRAND)
                    Spacer(Modifier.width(Spacing.sm))
                    StatusBadge("约 ${formatMacroDuration(totalMs)}", Tone.NEUTRAL)
                }
            }
            item(key = "steps_title") {
                OpsSectionLabel("步骤时间轴", "${steps.size}")
            }
            if (steps.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有步骤。回到浮窗面板重新录一段，或删掉这个宏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(vertical = Spacing.md),
                    )
                }
            }
            itemsIndexed(items = steps, key = { _, step -> step.id }) { index, step ->
                StepEditorRow(
                    index = index,
                    step = step,
                    lastIndex = steps.lastIndex,
                    expanded = expandedId == step.id,
                    onToggle = { expandedId = if (expandedId == step.id) null else step.id },
                    onUpdate = { updated ->
                        val position = steps.indexOfFirst { it.id == step.id }
                        if (position >= 0) steps[position] = updated
                    },
                    onMoveUp = { moveStep(steps, index, index - 1) },
                    onMoveDown = { moveStep(steps, index, index + 1) },
                    onDelete = {
                        val position = steps.indexOfFirst { it.id == step.id }
                        if (position >= 0) steps.removeAt(position)
                    },
                    onTest = { onTestStep(step) },
                )
            }
            item(key = "run_hint") {
                Text(
                    "单步可试跑；整段播放请保存后回到列表点「运行」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.padding(vertical = Spacing.md),
                )
            }
        }

        EditorBottomBar(
            onSave = {
                val finalName = name.trim().ifBlank { source.name }
                val snapshot = steps.toList()
                savedName = finalName
                savedSteps = snapshot
                name = finalName
                onSave(finalName, MacroAdapter.toExecSteps(snapshot))
            },
            onDelete = { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除「${source.name}」？",
            text = "删除后无法恢复，该宏从悬浮按钮与语音绑定中一并移除。",
            confirmLabel = "删除",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete(source.macroId)
            },
        )
    }

    if (confirmExit) {
        ConfirmDialog(
            title = "放弃改动？",
            text = "还有未保存的改动，返回后这些调整会丢失。",
            confirmLabel = "放弃返回",
            onCancel = { confirmExit = false },
            onConfirm = {
                confirmExit = false
                onBack()
            },
        )
    }
}

/** 归一化坐标预览：网格 + 点击点 + 滑动路径，旋转/分辨率变化都不依赖像素缓存。 */
@Composable
private fun OpsCoordinatePreview(steps: List<MacroStep>) {
    Surface(shape = CardShape, color = Panel, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "归一化坐标预览",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    color = Muted,
                )
                Spacer(Modifier.weight(1f))
                Text("ROT 0", style = MaterialTheme.typography.labelSmall, color = PanelFaint)
            }
            Box(modifier = Modifier.fillMaxWidth().height(190.dp).clip(CardShape)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val grid = Line.copy(alpha = 0.35f)
                    for (i in 1..4) {
                        val x = w * i / 5f
                        val y = h * i / 5f
                        drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                        drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }
                    steps.filterIsInstance<MacroStep.Swipe>().forEach { swipe ->
                        if (swipe.points.size >= 2) {
                            val path = Path().apply {
                                moveTo(swipe.points[0].x * w, swipe.points[0].y * h)
                                swipe.points.drop(1).forEach { point -> lineTo(point.x * w, point.y * h) }
                            }
                            drawPath(
                                path = path,
                                color = Violet,
                                style = Stroke(
                                    width = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                                ),
                            )
                        }
                    }
                    steps.forEach { step ->
                        when (step) {
                            is MacroStep.Tap -> drawCircle(Brand, radius = 6f, center = Offset(step.x * w, step.y * h))
                            is MacroStep.LongPress -> drawCircle(Warn, radius = 6f, center = Offset(step.x * w, step.y * h))
                            is MacroStep.Swipe -> step.points.forEach { point ->
                                drawCircle(Violet, radius = 3.5f, center = Offset(point.x * w, point.y * h))
                            }
                            is MacroStep.Wait -> Unit
                        }
                    }
                }
                Text(
                    "NORMALIZED 0–1 · 点击 / 轨迹",
                    modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.sm),
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelFaint,
                )
            }
        }
    }
}

@Composable
private fun StepEditorRow(
    index: Int,
    step: MacroStep,
    lastIndex: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUpdate: (MacroStep) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    val visual = stepVisual(step)
    Surface(
        shape = CardShape,
        color = Surface2,
        border = BorderStroke(
            1.dp,
            if (expanded) Brand.copy(alpha = 0.5f) else Line,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeqBadge(index + 1)
                Spacer(Modifier.width(Spacing.sm))
                Surface(shape = SmShape, color = visual.container, border = BorderStroke(1.dp, visual.content.copy(alpha = 0.3f)), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(visual.icon, contentDescription = null, tint = visual.content, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(visual.label, style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(
                        stepSubtitle(step),
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StepIconButton(Icons.Filled.KeyboardArrowUp, "上移", index > 0, onMoveUp)
                StepIconButton(Icons.Filled.KeyboardArrowDown, "下移", index < lastIndex, onMoveDown)
                StepIconButton(Icons.Filled.Delete, "删除", true, onDelete, Danger)
            }

            if (expanded) {
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    val isWait = step is MacroStep.Wait
                    val minMs = if (isWait) 0 else MacroStep.MIN_DURATION_MS
                    val maxMs = if (isWait) MacroStep.MAX_DURATION_MS else STEP_DURATION_CEILING_MS
                    val shownMs = currentDurationMs(step).coerceIn(minMs, maxMs)
                    SliderLabel(if (isWait) "等待时长" else "手势持续", "$shownMs ms")
                    Slider(
                        value = shownMs.toFloat(),
                        onValueChange = { onUpdate(step.withDuration(it.roundToInt())) },
                        valueRange = minMs.toFloat()..maxMs.toFloat(),
                    )
                    // 延迟可编辑：录制只能给出估算，游戏加载/动画需要用户自己调。
                    val delayMs = step.delayBeforeMs.coerceIn(0L, STEP_DELAY_CEILING_MS.toLong()).toInt()
                    SliderLabel("执行前等待", "$delayMs ms")
                    Slider(
                        value = delayMs.toFloat(),
                        onValueChange = { onUpdate(step.withDelayBefore(it.toLong())) },
                        valueRange = 0f..STEP_DELAY_CEILING_MS.toFloat(),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = onTest,
                        shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Brand),
                        border = BorderStroke(1.dp, LineStrong),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("测试这一步", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderLabel(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = Brand, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeqBadge(number: Int) {
    Surface(shape = SmShape, color = BrandSoft, modifier = Modifier.size(22.dp), border = BorderStroke(1.dp, Brand.copy(alpha = 0.3f))) {
        Box(contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelSmall, color = BrandStrong)
        }
    }
}

@Composable
private fun StepIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = Muted,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else LineStrong,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun EditorBottomBar(onSave: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Line),
    ) {
        Row(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = ButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text("保存宏", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(Spacing.sm))
            Button(
                onClick = onDelete,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = ButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
                border = BorderStroke(1.dp, Danger.copy(alpha = 0.4f)),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text("删除宏", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingMacro(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("宏工作台", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Brand)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            IconCircle(icon = Icons.Filled.SearchOff, tint = Danger, container = DangerBg, size = 56.dp)
            Spacer(Modifier.height(Spacing.md))
            Text("宏不存在", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "可能已被删除，返回列表重新选择一个宏。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            PrimaryButton(text = "返回列表", onClick = onBack)
        }
    }
}

// ---------------------------------------------------------------------------
// 步骤展示 / 变更辅助
// ---------------------------------------------------------------------------

private const val STEP_DURATION_CEILING_MS = 3_000
private const val STEP_DELAY_CEILING_MS = 5_000

private data class StepVisual(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

private fun stepVisual(step: MacroStep): StepVisual = when (step) {
    is MacroStep.Tap -> StepVisual("点击", Icons.Filled.TouchApp, BrandSoft, BrandStrong)
    is MacroStep.Swipe -> StepVisual("滑动", Icons.Filled.Directions, VioletBg, Violet)
    is MacroStep.LongPress -> StepVisual("长按", Icons.Filled.TouchApp, WarnBg, Warn)
    is MacroStep.Wait -> StepVisual("等待", Icons.Filled.Schedule, Surface2, Muted)
}

private fun stepSubtitle(step: MacroStep): String = when (step) {
    is MacroStep.Tap -> percentPoint(step.x, step.y)
    is MacroStep.Swipe -> "${step.points.size} 个轨迹点"
    is MacroStep.LongPress -> percentPoint(step.x, step.y)
    is MacroStep.Wait -> "${step.waitMs} 毫秒"
}

private fun percentPoint(x: Float, y: Float): String =
    "(${(x * 100).roundToInt()}%, ${(y * 100).roundToInt()}%)"

private fun currentDurationMs(step: MacroStep): Int = when (step) {
    is MacroStep.Tap -> step.durationMs
    is MacroStep.Swipe -> step.durationMs
    is MacroStep.LongPress -> step.durationMs
    is MacroStep.Wait -> step.waitMs.toInt()
}

/** 改写时长：copy 保留 step.id，列表 key 与展开态才不会丢 */
private fun MacroStep.withDuration(ms: Int): MacroStep = when (this) {
    is MacroStep.Tap -> copy(durationMs = ms)
    is MacroStep.Swipe -> copy(durationMs = ms)
    is MacroStep.LongPress -> copy(durationMs = ms)
    is MacroStep.Wait -> copy(waitMs = ms.toLong().coerceAtLeast(0L))
}

/** 改写步骤前等待：同样保留 id */
private fun MacroStep.withDelayBefore(ms: Long): MacroStep {
    val safe = ms.coerceIn(0L, STEP_DELAY_CEILING_MS.toLong())
    return when (this) {
        is MacroStep.Tap -> copy(delayBeforeMs = safe)
        is MacroStep.Swipe -> copy(delayBeforeMs = safe)
        is MacroStep.LongPress -> copy(delayBeforeMs = safe)
        is MacroStep.Wait -> copy(delayBeforeMs = safe)
    }
}

/** 相邻交换：先移除再插入，越界直接忽略 */
private fun moveStep(steps: MutableList<MacroStep>, from: Int, to: Int) {
    if (from == to || from !in steps.indices || to !in steps.indices) return
    val item = steps.removeAt(from)
    steps.add(to, item)
}
