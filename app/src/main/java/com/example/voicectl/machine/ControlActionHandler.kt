package com.example.voicectl.machine

import com.example.voicectl.exec.CommandLibrary
import com.example.voicectl.exec.ExecutorChain

/** 把状态机的副作用落地到模型卸载、采集停止、执行链与反馈 */
class ControlActionHandler(
    private val onLoadModels: () -> Unit,
    private val onUnloadModels: () -> Unit,
    private val onStartCapture: () -> Unit,
    private val onStopCapture: () -> Unit,
    private val executor: ExecutorChain,
    private val onFeedback: (FeedbackType, String) -> Unit,
    private val onTelemetry: (String, String) -> Unit,
) {
    suspend fun handle(effect: SideEffect) {
        when (effect) {
            SideEffect.LoadModels -> onLoadModels()
            SideEffect.UnloadModels -> onUnloadModels()
            SideEffect.StartCapture -> onStartCapture()
            SideEffect.StopCapture -> onStopCapture()
            is SideEffect.Execute -> {
                val action = CommandLibrary.actionFor(effect.commandId)
                val result = executor.execute(action)
                onFeedback(if (result.success) FeedbackType.SUCCESS else FeedbackType.ERROR, result.message)
                onTelemetry("exec", if (result.success) "ok_${effect.commandId}" else "fail_${effect.commandId}")
            }
            is SideEffect.Feedback -> onFeedback(effect.type, effect.text)
            is SideEffect.Telemetry -> onTelemetry(effect.dim, effect.name)
            SideEffect.RequestConfirmExit -> Unit
        }
    }
}
