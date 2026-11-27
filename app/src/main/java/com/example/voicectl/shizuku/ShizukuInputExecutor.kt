package com.example.voicectl.shizuku

import com.example.voicectl.exec.CommandAction
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecResult
import com.example.voicectl.exec.ExecStep
import com.example.voicectl.exec.Executor
import kotlinx.coroutines.delay

/** ExecStep → InputCommand 的纯映射；Wait / Verify 不产生输入命令。 */
internal object ExecStepMapper {
    fun map(
        step: ExecStep,
        mapper: CoordinateMapper,
        width: Int,
        height: Int,
    ): InputCommand? = when (step) {
        is ExecStep.Tap -> {
            val (x, y) = mapper.map(step.x, step.y, width, height)
            InputCommand.Tap(x, y)
        }
        is ExecStep.Wait -> null
        is ExecStep.Verify -> null
    }
}

/**
 * 对接现有 Executor 接口，不改语音状态机。
 * Tap 经基准坐标换算后发强类型命令；Wait 延时；Verify 只累计不发命令（沿用现有语义）。
 * Swipe / KeyEvent 只在调试自测区使用，不进 CommandLibrary。
 */
class ShizukuInputExecutor(
    private val client: ShizukuClient,
    private val bridgeProvider: () -> InputBridge?,
    private val mapper: CoordinateMapper,
    private val screenSizeProvider: () -> Pair<Int, Int>,
) : Executor {
    override val name: String = "Shizuku"

    override fun isAvailable(): Boolean = client.status.value.phase == ShizukuPhase.AUTHORIZED

    override suspend fun execute(action: CommandAction): ExecResult {
        if (!isAvailable()) {
            val state = client.status.value
            return ExecResult(false, "Shizuku 不可用（${state.phase}）：${state.reason ?: "尚未授权"}")
        }
        val bridge = bridgeProvider()
            ?: return ExecResult(false, "Shizuku 输入桥未初始化")
        val (width, height) = screenSizeProvider()
        var verifyCount = 0
        var attempted = false

        for (step in action.steps) {
            when (step) {
                is ExecStep.Wait -> {
                    delay(step.ms)
                    continue
                }
                is ExecStep.Verify -> {
                    // 校验由上层门控负责，此处只累计，不触碰屏幕内容
                    verifyCount++
                    continue
                }
                is ExecStep.Tap -> Unit
            }

            // 每条命令前重查：binder 死亡或授权被撤立即中止剩余步骤
            client.refresh()
            if (client.status.value.phase != ShizukuPhase.AUTHORIZED) {
                return ExecResult(
                    false,
                    "执行中授权失效，已中断剩余步骤（${client.status.value.phase}）",
                    attempted = attempted,
                )
            }

            val command = ExecStepMapper.map(step, mapper, width, height) ?: continue
            val result = bridge.execute(command)
            if (!result.ok) {
                // 桥返回失败时输入可能根本未落地（如授权在派发瞬间被撤），不能宣称"已部分执行"。
                // attempted 仅在此前确有点击成功落地时才置位；成功后立即置位，
                // 保证后续任何失败都不会触发降级重放。
                return ExecResult(
                    false,
                    "点击 ${step.key} 执行受阻，可能未落地，请手动确认：${result.message}",
                    attempted = attempted,
                )
            }
            attempted = true
        }

        val verifySuffix = if (verifyCount > 0) " · 校验项 $verifyCount 个（不发送输入）" else ""
        return ExecResult(
            true,
            "Shizuku 执行完成：${action.name}（屏幕 ${width}×${height}$verifySuffix）",
            attempted = attempted,
        )
    }
}
