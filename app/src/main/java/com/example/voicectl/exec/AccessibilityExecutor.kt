package com.example.voicectl.exec

import com.example.voicectl.service.VoiceAccessibilityService
import kotlinx.coroutines.delay

/** 主执行链路：通过无障碍手势点击，坐标由 CoordinateMapper 换算 */
class AccessibilityExecutor(
    private val serviceProvider: () -> VoiceAccessibilityService?,
) : Executor {
    override val name: String = "无障碍服务"

    override fun isAvailable(): Boolean = serviceProvider() != null

    override suspend fun execute(action: CommandAction): ExecResult {
        val service = serviceProvider()
            ?: return ExecResult(false, "无障碍服务未开启")
        val executed = mutableListOf<String>()
        for (step in action.steps) {
            when (step) {
                is ExecStep.Tap -> {
                    val ok = service.tap(step.x, step.y)
                    if (!ok) return ExecResult(false, "点击失败：${step.key}，已停止后续步骤")
                    executed += step.key
                }
                is ExecStep.Wait -> delay(step.ms)
                is ExecStep.Verify -> executed += "verify:${step.key}"
            }
        }
        return ExecResult(true, "无障碍执行完成：${action.name}（${executed.size} 步）")
    }
}
