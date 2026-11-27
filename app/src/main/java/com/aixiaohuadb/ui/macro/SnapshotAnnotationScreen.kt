package com.aixiaohuadb.ui.macro

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.QuietButton
import com.aixiaohuadb.ui.common.ScreenFrame
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Spacing

/**
 * 阶段 B：应用内静态标注。点选生成 Tap、拖拽生成 Swipe，每步可调间隔与排序。
 * 截屏失败的降级路径：从相册导入一张截图。
 */
@Composable
fun SnapshotAnnotationScreen(
    uri: String?,
    vm: SnapshotAnnotationViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val context = LocalContext.current
    val draft by vm.draft.collectAsState()
    var saveError by remember { mutableStateOf<String?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        vm.setSnapshotUri(picked?.toString())
    }

    LaunchedEffect(uri) { vm.setSnapshotUri(uri) }

    val bitmap = remember(draft.snapshotUri) {
        draft.snapshotUri?.let { raw ->
            runCatching {
                val parsed = Uri.parse(raw)
                val source = if (parsed.scheme == "file") {
                    java.io.File(parsed.path ?: "").inputStream()
                } else {
                    context.contentResolver.openInputStream(parsed)
                }
                source?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            }.getOrNull()
        }
    }

    ScreenFrame(
        title = "快照标注",
        subtitle = "点选 = 点击 · 拖拽 = 滑动 · 间隔挂在步上",
        showBack = true,
        onBack = onBack,
        activeTab = "settings",
        onTab = onTab,
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = vm::setName,
            label = { Text("宏名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))

        if (bitmap == null) {
            QuietButton(text = "从相册导入截图（系统截屏授权失败时）", onClick = { galleryLauncher.launch("image/*") })
            Text(
                "没有快照也可以继续：先保存空宏，游戏内用底部录制条标记落点。",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        } else {
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragEnd by remember { mutableStateOf<Offset?>(null) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    // Tap 与 Drag 必须分属独立 pointerInput：同一个块里串联会让
                    // detectTapGestures 抢占手势，拖拽同时触发额外 Tap，Drag 永远收不到。
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            vm.addTap(offset.x, offset.y, size.width, size.height)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragStart = it },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragEnd
                                if (start != null && end != null) {
                                    vm.addSwipe(start.x, start.y, end.x, end.y, size.width, size.height)
                                }
                                dragStart = null
                                dragEnd = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragEnd = null
                            },
                        ) { change, _ -> dragEnd = change.position }
                    },
            ) {
                Image(bitmap, contentDescription = "游戏快照", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawSteps(draft.steps, size.width, size.height)
                    val start = dragStart
                    val end = dragEnd
                    if (start != null && end != null) {
                        drawLine(Brand, start, end, strokeWidth = 4f)
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton(text = "+ 点选", onClick = {
                // 点选直接在截图上手势完成；按钮用于提示当前交互模式
            })
            Spacer(Modifier.width(Spacing.sm))
            QuietButton(text = "+ 滑动", onClick = {})
            Spacer(Modifier.width(Spacing.sm))
            QuietButton(text = "撤销", onClick = vm::undo)
        }
        Text(
            "提示：在截图上单击记录 Tap，按住拖动记录 Swipe；下方调整每步间隔。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )

        Spacer(Modifier.height(Spacing.sm))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            draft.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (step) {
                            is RecordStepUi.TapUi -> "① Tap(${step.x},${step.y})"
                            is RecordStepUi.SwipeUi -> "② Swipe→(${step.toX},${step.toY})"
                            is RecordStepUi.MultiGestureUi ->
                                "③ 多指${step.pointers.size}指(${step.durationMs}ms)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = when (step) {
                            is RecordStepUi.TapUi -> step.delayAfterMs.toString()
                            is RecordStepUi.SwipeUi -> step.delayAfterMs.toString()
                            is RecordStepUi.MultiGestureUi -> step.delayAfterMs.toString()
                        },
                        onValueChange = { text -> vm.setDelay(index, text.toLongOrNull() ?: 0L) },
                        label = { Text("ms") },
                        singleLine = true,
                        modifier = Modifier.width(88.dp),
                    )
                    TextButton(onClick = { vm.move(index, (index - 1).coerceAtLeast(0)) }) { Text("↑") }
                    TextButton(onClick = { vm.move(index, (index + 1).coerceAtMost(draft.steps.lastIndex)) }) { Text("↓") }
                    TextButton(onClick = { vm.removeAt(index) }) { Text("✕") }
                }
                if (step is RecordStepUi.SwipeUi) {
                    OutlinedTextField(
                        value = step.durationMs.toString(),
                        onValueChange = { text -> vm.setDuration(index, text.toIntOrNull() ?: 300) },
                        label = { Text("滑动时长 ms") },
                        singleLine = true,
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            saveError?.let { msg ->
                Text("保存失败：$msg，草稿已保留，请重试。", color = MaterialTheme.colorScheme.error)
            }
            PrimaryButton(
                text = "保存宏",
                onClick = {
                    val outcome = runCatching { vm.save() }
                    if (outcome.isSuccess) {
                        saveError = null
                        onBack()
                    } else {
                        saveError = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                            ?: "未知错误，请重试"
                    }
                },
            )
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

private fun DrawScope.drawSteps(steps: List<RecordStepUi>, viewWidth: Float, viewHeight: Float) {
    steps.forEach { step ->
        when (step) {
            is RecordStepUi.TapUi -> {
                val x = step.x * viewWidth / 1080f
                val y = step.y * viewHeight / 2340f
                drawCircle(Color(0xFF22D3EE), radius = 14f, center = Offset(x, y), alpha = 0.85f)
                drawCircle(Color(0xFF0E7490), radius = 5f, center = Offset(x, y))
            }
            is RecordStepUi.SwipeUi -> {
                val fx = step.fromX * viewWidth / 1080f
                val fy = step.fromY * viewHeight / 2340f
                val tx = step.toX * viewWidth / 1080f
                val ty = step.toY * viewHeight / 2340f
                drawLine(Color(0xFF5EEAD4), Offset(fx, fy), Offset(tx, ty), strokeWidth = 5f)
                drawCircle(Color(0xFF22D3EE), radius = 12f, center = Offset(fx, fy))
                drawCircle(Color(0xFF34D399), radius = 12f, center = Offset(tx, ty))
            }
            is RecordStepUi.MultiGestureUi -> {
                val colors = listOf(Color(0xFF22D3EE), Color(0xFFF472B6), Color(0xFFFBBF24), Color(0xFFA78BFA))
                step.pointers.forEachIndexed { finger, pointer ->
                    val color = colors[finger % colors.size]
                    val points = pointer.points.map { point ->
                        Offset(point.x * viewWidth / 1080f, point.y * viewHeight / 2340f)
                    }
                    points.zipWithNext().forEach { (from, to) ->
                        drawLine(color, from, to, strokeWidth = 5f, alpha = 0.8f)
                    }
                    points.forEach { point ->
                        drawCircle(color, radius = 11f, center = point, alpha = 0.85f)
                    }
                }
            }
        }
    }
}
