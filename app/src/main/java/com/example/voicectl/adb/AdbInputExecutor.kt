package com.example.voicectl.adb

import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.CommandAction
import com.example.voicectl.exec.ExecResult
import com.example.voicectl.exec.ExecStep
import com.example.voicectl.exec.Executor
import kotlinx.coroutines.delay

/**
 * 基于内嵌 ADB 的执行器：把通用 [ExecStep] 映射为强类型 [InputCommand] 后交给
 * [AdbClient] 执行。基准坐标先映射到真实屏幕再下发。
 *
 * attempted 语义：只要真正派发过点击命令，失败就置 true，阻止上层降级重放，
 * 避免同一条 action 被重复点击。前置校验 / 未连接失败保持 false，允许降级。
 */
class AdbInputExecutor(
    private val client: InputCommandExecutor,
    private val mapper: CoordinateMapper,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val isAvailableProvider: () -> Boolean = { true },
) : Executor {
    override val name: String = "内嵌 ADB"

    override fun isAvailable(): Boolean = isAvailableProvider()

    override suspend fun execute(action: CommandAction): ExecResult {
        var attempted = false
        val (sw, sh) = screenSizeProvider()
        if (sw <= 0 || sh <= 0) {
            return ExecResult(false, "无法获取有效屏幕尺寸")
        }

        for (step in action.steps) {
            when (step) {
                is ExecStep.Wait -> delay(step.ms.coerceAtLeast(0))
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
                }
            }
        }
        return ExecResult(true, "内嵌 ADB 执行完成：${action.name}", attempted = attempted)
    }
}
