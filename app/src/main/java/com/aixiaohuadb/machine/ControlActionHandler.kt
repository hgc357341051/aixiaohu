package com.aixiaohuadb.machine

import com.aixiaohuadb.exec.CommandLibrary
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.ExecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 把状态机的副作用落地到模型卸载、采集停止、执行链与反馈 */
class ControlActionHandler(
    private val onLoadModels: suspend () -> Unit,
    private val onUnloadModels: suspend () -> Unit,
    private val onStartCapture: suspend () -> Unit,
    private val onStopCapture: suspend () -> Unit,
    private val executor: ExecutorChain,
    private val onFeedback: (FeedbackType, String) -> Unit,
    private val onTelemetry: (String, String) -> Unit,
    private val onExecResult: (Boolean) -> Unit = {},
) {
    suspend fun handle(effect: SideEffect) {
        when (effect) {
            SideEffect.LoadModels -> onLoadModels()
            SideEffect.UnloadModels -> onUnloadModels()
            SideEffect.StartCapture -> onStartCapture()
            SideEffect.StopCapture -> onStopCapture()
            is SideEffect.Execute -> {
                val action = CommandLibrary.actionFor(effect.commandId)
                val result = try {
                    withContext(Dispatchers.IO) {
                        withTimeout(10_000L) { executor.execute(action) }
                    }
                } catch (e: TimeoutCancellationException) {
                    ExecResult(false, "执行超时")
                } catch (e: Exception) {
                    ExecResult(false, "执行异常：${e.message ?: "未知错误"}")
                }
                onExecResult(result.success)
                onFeedback(
                    if (result.success) FeedbackType.SUCCESS else FeedbackType.ERROR,
                    if (result.success) "执行成功" else "执行失败：${result.message}",
                )
                onTelemetry("exec", if (result.success) "ok_${effect.commandId}" else "fail_${effect.commandId}")
            }
            is SideEffect.Feedback -> onFeedback(effect.type, effect.text)
            is SideEffect.Telemetry -> onTelemetry(effect.dim, effect.name)
            SideEffect.RequestConfirmExit -> Unit
        }
    }
}
