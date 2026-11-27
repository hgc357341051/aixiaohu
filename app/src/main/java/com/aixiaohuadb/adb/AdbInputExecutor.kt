@file:Suppress("DEPRECATION")
package com.aixiaohuadb.adb

import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.ExecResult
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.ExecutionGuard
import com.aixiaohuadb.exec.Executor
import com.aixiaohuadb.exec.clampDelayMs
import com.aixiaohuadb.exec.effectiveDurationMs
import com.aixiaohuadb.exec.ensureActive
import kotlinx.coroutines.delay

/**
 * 基于内嵌 ADB 的执行器：把通用 [ExecStep] 映射为强类型 [InputCommand] 后交给
 * [AdbClient] 执行。基准坐标先映射到真实屏幕再下发。
 *
 * attempted 语义：只有链路真正派发/写入后才置 true（成功或超时/IO 异常），
 * 派发前的校验失败、未连接和非零 exitCode（设备拒绝、点击未落地）保持 false 允许降级。
 * [guard] 让语音 disable 能在每一步之前打断长宏，避免关掉语音后还在继续派发。
 */
class AdbInputExecutor(
    private val client: InputCommandExecutor,
    private val mapper: CoordinateMapper,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val isAvailableProvider: () -> Boolean = { true },
    private val guard: ExecutionGuard = ExecutionGuard.NONE,
) : Executor {
    override val name: String = "内嵌 ADB"

    override fun isAvailable(): Boolean = isAvailableProvider()

    override suspend fun execute(action: CommandAction): ExecResult {
        guard.ensureActive()
        var attempted = false
        val (sw, sh) = screenSizeProvider()
        if (sw <= 0 || sh <= 0) {
            return ExecResult(false, "无法获取有效屏幕尺寸")
        }

        for (step in action.steps) {
            guard.ensureActive()
            when (step) {
                is ExecStep.Wait -> delay(step.ms.clampDelayMs())
                is ExecStep.Verify -> Unit // 无真实校验通道，跳过（非点击命令）
                is ExecStep.Tap -> {
                    val (x, y) = mapper.map(step.x, step.y, sw, sh)
                    val result = client.execute(InputCommand.Tap(x, y))
                    if (result.attempted) attempted = true
                    if (!result.ok) {
                        val msg = if (attempted) {
                            "点击 ${step.key} 执行受阻，可能未落地，请手动确认：${result.message}"
                        } else {
                            result.message
                        }
                        return ExecResult(false, msg, attempted = attempted)
                    }
                    delay(step.delayAfterMs.clampDelayMs())
                }
                is ExecStep.MultiGesture -> {
                    val mapped = step.pointers.map { pointer ->
                        InputCommand.MotionPointer(
                            pointer.points.map { point ->
                                val (x, y) = mapper.map(point.x, point.y, sw, sh)
                                InputCommand.MotionPoint(x, y, point.timeMs)
                            },
                        )
                    }
                    val result = client.execute(
                        InputCommand.MultiTouch(
                            pointers = mapped,
                            durationMs = step.effectiveDurationMs,
                            // 设备路径不落盘、不信任录制值：注入前由 AdbClient 重新探测
                            devicePath = "",
                        ),
                    )
                    if (result.attempted) attempted = true
                    if (!result.ok) {
                        return ExecResult(false, "多指执行受阻：${result.message}", attempted = attempted)
                    }
                    delay(step.delayAfterMs.clampDelayMs())
                }
                is ExecStep.Swipe -> {
                    val (fx, fy) = mapper.map(step.fromX, step.fromY, sw, sh)
                    val (tx, ty) = mapper.map(step.toX, step.toY, sw, sh)
                    val result = client.execute(
                        InputCommand.Swipe(fx, fy, tx, ty, step.effectiveDurationMs),
                    )
                    if (result.attempted) attempted = true
                    if (!result.ok) {
                        return ExecResult(false, "滑动执行受阻：${result.message}", attempted = attempted)
                    }
                    delay(step.delayAfterMs.clampDelayMs())
                }
            }
        }
        return ExecResult(true, "内嵌 ADB 执行完成：${action.name}", attempted = attempted)
    }
}
