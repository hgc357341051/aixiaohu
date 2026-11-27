package com.aixiaohuadb.ui.macros

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.ui.common.ConfirmDialog
import com.aixiaohuadb.ui.common.DangerButton
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.PillChip
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.SectionTitle
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandStrong
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.DangerBg
import com.aixiaohuadb.ui.theme.FieldShape
import com.aixiaohuadb.ui.theme.Info
import com.aixiaohuadb.ui.theme.InfoBg
import com.aixiaohuadb.ui.theme.Ink2
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.LineStrong
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Surface2
import com.aixiaohuadb.ui.theme.Warn
import com.aixiaohuadb.ui.theme.WarnBg
import kotlin.math.roundToInt

/**
 * 宏编辑器：步骤增删排序 + 单步时长调节。
 *
 * 步骤状态用 [MacroAdapter.fromExecSteps] 从 [MacroConfig.steps] 转入，`remember(macroId)` 持有，
 * 保存时再 [MacroAdapter.toExecSteps] 转回执行链；页面本身不读写 OverlayStore。
 * 步骤列表需要 LazyColumn + 固定底部操作条，ScreenFrame 的 verticalScroll 内容区放不下，
 * 所以本页自带顶部栏/页脚，不渲染底部页签；[onTab] 仅与既有页面签名保持一致。
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

    // 已保存基线：用于判断"未保存改动"，保存成功后同步一次，避免调用方原地停留时误提示
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("编辑宏", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (dirty) "有未保存的改动" else "${steps.size} 步 · 约 ${formatMacroDuration(totalMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dirty) Warn else Muted,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (dirty) confirmExit = true else onBack() }) {
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

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = Spacing.screenPadding,
        ) {
            item(key = "header") {
                MacroHeader(name = name, onNameChange = { name = it }, stepCount = steps.size, totalMs = totalMs)
            }
            item(key = "steps_title") { SectionTitle("步骤") }
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
                // 名称留空时回退到原名，避免落盘空名导致列表无法辨认
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

@Composable
private fun MacroHeader(
    name: String,
    onNameChange: (String) -> Unit,
    stepCount: Int,
    totalMs: Long,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("宏名称") },
            singleLine = true,
            shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillChip("$stepCount 步")
            Spacer(Modifier.width(Spacing.sm))
            PillChip("总时长 约 ${formatMacroDuration(totalMs)}", container = Surface2, content = Ink2)
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
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (expanded) Brand.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeqBadge(index + 1)
                Spacer(Modifier.width(Spacing.sm))
                PillChip(text = visual.label, container = visual.container, content = visual.content)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stepSubtitle(step),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StepIconButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = "上移",
                    enabled = index > 0,
                    onClick = onMoveUp,
                )
                StepIconButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = "下移",
                    enabled = index < lastIndex,
                    onClick = onMoveDown,
                )
                StepIconButton(
                    icon = Icons.Filled.Delete,
                    description = "删除",
                    enabled = true,
                    tint = Danger,
                    onClick = onDelete,
                )
            }

            if (expanded) {
                Column(modifier = Modifier.padding(top = Spacing.sm)) {
                    val isWait = step is MacroStep.Wait
                    val minMs = if (isWait) 0 else MacroStep.MIN_DURATION_MS
                    val maxMs = if (isWait) MacroStep.MAX_DURATION_MS else STEP_DURATION_CEILING_MS
                    // 录制得到的时长可能超出滑条量程，落在滑条上时先夹紧，拖动后写入新值
                    val shownMs = currentDurationMs(step).coerceIn(minMs, maxMs)
                    Text(
                        if (isWait) "等待 $shownMs ms" else "时长 $shownMs ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink2,
                    )
                    Slider(
                        value = shownMs.toFloat(),
                        onValueChange = { onUpdate(step.withDuration(it.roundToInt())) },
                        valueRange = minMs.toFloat()..maxMs.toFloat(),
                    )
                    Text(
                        "执行前等待 ${step.delayBeforeMs} ms（录制自动生成）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryButton(
                        text = "测试这一步",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onTest,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeqBadge(number: Int) {
    Surface(shape = CircleShape, color = BrandSoft, modifier = Modifier.size(22.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = BrandStrong,
            )
        }
    }
}

@Composable
private fun StepIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color = Muted,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(30.dp),
    ) {
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
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PrimaryButton(text = "保存", onClick = onSave)
            Spacer(Modifier.height(Spacing.sm))
            DangerButton(text = "删除宏", onClick = onDelete)
            Text(
                "保存后在列表运行",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MissingMacro(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text("编辑宏", style = MaterialTheme.typography.titleMedium) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            IconCircle(
                icon = Icons.Filled.SearchOff,
                tint = Danger,
                container = DangerBg,
                size = 56.dp,
            )
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

/** 交互步骤时长滑条上限：与视觉规范 3s 对齐，超出量程的录制值夹紧显示 */
private const val STEP_DURATION_CEILING_MS = 3_000

private data class StepVisual(val label: String, val container: Color, val content: Color)

private fun stepVisual(step: MacroStep): StepVisual = when (step) {
    is MacroStep.Tap -> StepVisual("点击", BrandSoft, BrandStrong)
    is MacroStep.Swipe -> StepVisual("滑动", InfoBg, Info)
    is MacroStep.LongPress -> StepVisual("长按", WarnBg, Warn)
    is MacroStep.Wait -> StepVisual("等待", Surface2, Muted)
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

/** 相邻交换：先移除再插入，越界直接忽略 */
private fun moveStep(steps: MutableList<MacroStep>, from: Int, to: Int) {
    if (from == to || from !in steps.indices || to !in steps.indices) return
    val item = steps.removeAt(from)
    steps.add(to, item)
}
