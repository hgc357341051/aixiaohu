package com.aixiaohuadb.ui.macro

import androidx.lifecycle.ViewModel
import com.aixiaohuadb.adb.CommandValidator
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.clampDelayMs
import com.aixiaohuadb.service.OverlayCodec
import com.aixiaohuadb.service.OverlayStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 快照标注页状态：点选 Tap / 拖拽 Swipe / 步上间隔 / 排序 / 命名保存 */
class SnapshotAnnotationViewModel : ViewModel() {
    private val mapper = CoordinateMapper()
    private val _draft = MutableStateFlow(MacroDraft())
    val draft: StateFlow<MacroDraft> = _draft

    fun setSnapshotUri(uri: String?) {
        _draft.value = _draft.value.copy(snapshotUri = uri, updatedAt = System.currentTimeMillis())
    }

    fun setName(name: String) {
        _draft.value = _draft.value.copy(name = OverlayCodec.sanitizeLabel(name))
    }

    fun addTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val (bx, by) = base(x, y, viewWidth, viewHeight)
        update { it.copy(steps = it.steps + RecordStepUi.TapUi(bx, by)) }
    }

    fun addSwipe(
        fromX: Float, fromY: Float, toX: Float, toY: Float,
        viewWidth: Int, viewHeight: Int,
    ) {
        val (fx, fy) = base(fromX, fromY, viewWidth, viewHeight)
        val (tx, ty) = base(toX, toY, viewWidth, viewHeight)
        update { it.copy(steps = it.steps + RecordStepUi.SwipeUi(fx, fy, tx, ty)) }
    }

    fun undo() = update { it.copy(steps = it.steps.dropLast(1)) }

    fun removeAt(index: Int) = update { it.copy(steps = it.steps.filterIndexed { i, _ -> i != index }) }

    fun move(from: Int, to: Int) = update { draft ->
        if (from == to || from !in draft.steps.indices || to !in draft.steps.indices) return@update draft
        val list = draft.steps.toMutableList()
        list.add(to, list.removeAt(from))
        draft.copy(steps = list)
    }

    fun setDelay(index: Int, ms: Long) = update { draft ->
        val safe = ms.clampDelayMs()
        val changed = draft.steps.getOrNull(index)?.let { step ->
            when (step) {
                is RecordStepUi.TapUi -> step.copy(delayAfterMs = safe)
                is RecordStepUi.SwipeUi -> step.copy(delayAfterMs = safe)
                is RecordStepUi.MultiGestureUi -> step.copy(delayAfterMs = safe)
            }
        } ?: return@update draft
        draft.copy(steps = draft.steps.mapIndexed { i, s -> if (i == index) changed else s })
    }

    fun setDuration(index: Int, ms: Int) = update { draft ->
        val changed = (draft.steps.getOrNull(index) as? RecordStepUi.SwipeUi)
            ?.copy(durationMs = CommandValidator.clampSwipeDurationMs(ms)) ?: return@update draft
        draft.copy(steps = draft.steps.mapIndexed { i, s -> if (i == index) changed else s })
    }

    fun seed(steps: List<ExecStep>) = update { draft ->
        draft.copy(steps = steps.mapNotNull { step ->
            when (step) {
                is ExecStep.Tap -> RecordStepUi.TapUi(step.x, step.y, step.delayAfterMs)
                is ExecStep.Swipe -> RecordStepUi.SwipeUi(
                    step.fromX, step.fromY, step.toX, step.toY, step.durationMs, step.delayAfterMs,
                )
                is ExecStep.MultiGesture -> RecordStepUi.MultiGestureUi(
                    step.pointers, step.durationMs, step.delayAfterMs,
                )
                else -> null
            }
        })
    }

    fun execSteps(): List<ExecStep> = _draft.value.steps.map { step ->
        when (step) {
            is RecordStepUi.TapUi -> ExecStep.Tap(step.x, step.y, delayAfterMs = step.delayAfterMs)
            is RecordStepUi.SwipeUi -> ExecStep.Swipe(
                step.fromX, step.fromY, step.toX, step.toY, step.durationMs, step.delayAfterMs,
            )
            is RecordStepUi.MultiGestureUi -> ExecStep.MultiGesture(
                pointers = step.pointers,
                durationMs = step.durationMs,
                delayAfterMs = step.delayAfterMs,
            )
        }
    }

    fun save(): Long {
        val steps = execSteps()
        return OverlayStore.saveMacro(_draft.value.name, steps)
    }

    private fun base(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Pair<Int, Int> =
        if (viewWidth <= 0 || viewHeight <= 0) 0 to 0
        else mapper.mapToBase(x.toInt(), y.toInt(), viewWidth, viewHeight)

    private fun update(op: (MacroDraft) -> MacroDraft) {
        _draft.value = op(_draft.value).copy(updatedAt = System.currentTimeMillis())
    }
}
