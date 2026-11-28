package com.aixiaohuadb.domain.macro

import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.GesturePoint
import com.aixiaohuadb.exec.GesturePointer
import java.util.UUID

/** 录制时的物理屏幕参数；仅用于诊断与坐标兜底，回放不以它为准。 */
data class ScreenSpec(val width: Int, val height: Int, val rotation: Int)

/** 归一化坐标：0..1，左下/右上越界会被 [NormPoint] 构造时夹紧。 */
data class NormPoint(val x: Float, val y: Float) {
    fun clamped(): NormPoint = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    companion object {
        fun of(x: Float, y: Float): NormPoint = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}

/**
 * 宏步骤：强类型，不再用 Map 装参数。
 * 坐标一律归一化，回放时按当前显示尺寸还原，换分辨率/旋转不失效。
 */
sealed interface MacroStep {
    val id: String
    /** 本步开始前等待；录制时由两次操作的抬手间隔得到。 */
    val delayBeforeMs: Long

    data class Tap(
        override val id: String = newId(),
        override val delayBeforeMs: Long = 0L,
        val x: Float,
        val y: Float,
        val durationMs: Int = DEFAULT_TAP_MS,
    ) : MacroStep

    data class Swipe(
        override val id: String = newId(),
        override val delayBeforeMs: Long = 0L,
        val points: List<NormPoint>,
        val durationMs: Int = DEFAULT_SWIPE_MS,
    ) : MacroStep {
        val start: NormPoint get() = points.firstOrNull() ?: NormPoint(0.5f, 0.5f)
        val end: NormPoint get() = points.lastOrNull() ?: start
    }

    data class LongPress(
        override val id: String = newId(),
        override val delayBeforeMs: Long = 0L,
        val x: Float,
        val y: Float,
        val durationMs: Int = DEFAULT_LONG_PRESS_MS,
    ) : MacroStep

    data class Wait(
        override val id: String = newId(),
        override val delayBeforeMs: Long = 0L,
        val waitMs: Long = 500L,
    ) : MacroStep

    companion object {
        const val DEFAULT_TAP_MS = 60
        const val DEFAULT_SWIPE_MS = 300
        const val DEFAULT_LONG_PRESS_MS = 600
        const val MIN_DURATION_MS = 30
        const val MAX_DURATION_MS = 10_000

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * 宏文档：录制、编辑、存储的唯一模型。
 * [schemaVersion] 必须显式存在，迁移按版本链执行；禁止用"默认值猜"处理语义变更。
 */
data class MacroDocument(
    val schemaVersion: Int = VERSION,
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val screen: ScreenSpec,
    val steps: List<MacroStep>,
) {
    val stepCount: Int get() = steps.size

    /** 估算总时长：步骤等待 + 手势时长，用于列表展示。 */
    val estimatedDurationMs: Long
        get() = steps.sumOf { step ->
            step.delayBeforeMs + when (step) {
                is MacroStep.Tap -> step.durationMs.toLong()
                is MacroStep.Swipe -> step.durationMs.toLong()
                is MacroStep.LongPress -> step.durationMs.toLong()
                is MacroStep.Wait -> step.waitMs
            }
        }

    fun withSteps(steps: List<MacroStep>): MacroDocument =
        copy(steps = steps, updatedAt = System.currentTimeMillis())

    companion object {
        const val VERSION = 1

        fun empty(name: String, screen: ScreenSpec): MacroDocument {
            val now = System.currentTimeMillis()
            return MacroDocument(
                id = MacroStep.newId(),
                name = name,
                createdAt = now,
                updatedAt = now,
                screen = screen,
                steps = emptyList(),
            )
        }
    }
}

/**
 * 归一化坐标 → 执行链的基准坐标（1080×2340）。
 * 现有 ADB / 无障碍执行器都以这个基准为输入，适配层集中在这里，
 * 避免每条执行链各写一份换算。
 */
object MacroAdapter {
    const val BASE_WIDTH = 1080
    const val BASE_HEIGHT = 2340

    fun toExecSteps(doc: MacroDocument): List<ExecStep> = toExecSteps(doc.steps)

    fun toExecSteps(steps: List<MacroStep>): List<ExecStep> {
        val out = ArrayList<ExecStep>(steps.size)
        steps.forEachIndexed { index, step ->
            // 首步的 delayBeforeMs 来自"点录制 → 切到游戏 → 第一次操作"的准备间隔，
            // 不是玩家想重现的操作节奏。过去把它前置成显式 ExecStep.Wait，
            // 回填编辑器后变成"一次点击 = Wait + Tap 两步"，即用户报告的重复步骤。
            // 这里首步延迟直接丢弃；后续步骤照常映射为步后延迟。
            // 注意：玩家在编辑器里显式插入的 MacroStep.Wait 仍走下面的 Wait 分支，不受影响。
            val delayAfter = if (index == 0) 0L else step.delayBeforeMs
            out += when (step) {
                is MacroStep.Tap -> ExecStep.Tap(
                    x = baseX(step.x),
                    y = baseY(step.y),
                    key = "tap",
                    delayAfterMs = delayAfter,
                )

                is MacroStep.Swipe -> {
                    val from = basePoint(step.start)
                    val to = basePoint(step.end)
                    ExecStep.Swipe(
                        fromX = from.first,
                        fromY = from.second,
                        toX = to.first,
                        toY = to.second,
                        durationMs = step.durationMs.coerceIn(
                            MacroStep.MIN_DURATION_MS,
                            MacroStep.MAX_DURATION_MS,
                        ),
                        delayAfterMs = delayAfter,
                    )
                }

                is MacroStep.LongPress -> {
                    // 执行链没有独立长按动作：用 1px、长时长的 swipe 表达，
                    // ADB 与无障碍两条链路都能落到同一个物理行为。
                    val (x, y) = basePoint(NormPoint.of(step.x, step.y))
                    ExecStep.Swipe(
                        fromX = x,
                        fromY = y,
                        toX = (x + 1).coerceAtMost(BASE_WIDTH - 1),
                        toY = y,
                        durationMs = step.durationMs.coerceIn(
                            MacroStep.MIN_DURATION_MS,
                            MacroStep.MAX_DURATION_MS,
                        ),
                        delayAfterMs = delayAfter,
                    )
                }

                is MacroStep.Wait -> ExecStep.Wait(step.waitMs)
            }
        }
        return out
    }

    /** 录制/导入的基准坐标步骤 → 强类型文档步骤，保证旧宏能进入新编辑器。 */
    fun fromExecSteps(steps: List<ExecStep>): List<MacroStep> = steps.mapNotNull { step ->
        when (step) {
            is ExecStep.Tap -> MacroStep.Tap(
                x = unitX(step.x),
                y = unitY(step.y),
                delayBeforeMs = step.delayAfterMs,
            )

            is ExecStep.Swipe -> MacroStep.Swipe(
                points = listOf(
                    NormPoint.of(unitX(step.fromX), unitY(step.fromY)),
                    NormPoint.of(unitX(step.toX), unitY(step.toY)),
                ),
                durationMs = step.durationMs.coerceIn(
                    MacroStep.MIN_DURATION_MS,
                    MacroStep.MAX_DURATION_MS,
                ),
                delayBeforeMs = step.delayAfterMs,
            )

            is ExecStep.MultiGesture -> step.pointers.firstOrNull()?.let { pointer ->
                MacroStep.Swipe(
                    points = pointer.points.map {
                        NormPoint.of(unitX(it.x), unitY(it.y))
                    }.ifEmpty { listOf(NormPoint(0.5f, 0.5f)) },
                    durationMs = step.durationMs.coerceIn(
                        MacroStep.MIN_DURATION_MS,
                        MacroStep.MAX_DURATION_MS,
                    ),
                    delayBeforeMs = step.delayAfterMs,
                )
            }

            is ExecStep.Wait -> MacroStep.Wait(waitMs = step.ms)

            is ExecStep.Verify -> null
        }
    }

    /** 无障碍录制器产出的多指轨迹 → 单指可编辑步骤（P0 只保留第一根手指）。 */
    fun fromPointer(pointer: List<GesturePoint>): MacroStep? {
        if (pointer.isEmpty()) return null
        val points = pointer.map { NormPoint.of(unitX(it.x), unitY(it.y)) }
        val duration = (pointer.last().timeMs - pointer.first().timeMs).toInt()
        return if (points.size == 1 || duration < MacroStep.MIN_DURATION_MS) {
            MacroStep.Tap(x = points.first().x, y = points.first().y)
        } else {
            MacroStep.Swipe(
                points = points,
                durationMs = duration.coerceIn(MacroStep.MIN_DURATION_MS, MacroStep.MAX_DURATION_MS),
            )
        }
    }

    fun toGesturePointer(step: MacroStep.Swipe): GesturePointer {
        val total = step.durationMs.coerceAtLeast(1)
        val size = step.points.size.coerceAtLeast(1)
        val points = step.points.mapIndexed { index, point ->
            val (x, y) = basePoint(point)
            GesturePoint(
                x = x,
                y = y,
                timeMs = if (size == 1) 0L else total.toLong() * index / (size - 1),
            )
        }
        return GesturePointer(points.ifEmpty { listOf(GesturePoint(0, 0, 0L)) })
    }

    private fun baseX(unit: Float): Int = (unit.coerceIn(0f, 1f) * BASE_WIDTH).toInt().coerceIn(0, BASE_WIDTH - 1)
    private fun baseY(unit: Float): Int = (unit.coerceIn(0f, 1f) * BASE_HEIGHT).toInt().coerceIn(0, BASE_HEIGHT - 1)
    private fun unitX(base: Int): Float = (base.toFloat() / BASE_WIDTH).coerceIn(0f, 1f)
    private fun unitY(base: Int): Float = (base.toFloat() / BASE_HEIGHT).coerceIn(0f, 1f)
    private fun basePoint(point: NormPoint): Pair<Int, Int> = baseX(point.x) to baseY(point.y)
}
