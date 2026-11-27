package com.aixiaohuadb.machine

import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.ExecResult
import com.aixiaohuadb.exec.ExecutionGuard
import com.aixiaohuadb.exec.ensureActive
import com.aixiaohuadb.exec.executionBudgetMs
import kotlinx.coroutines.CancellationException
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
    /** slot → 用户宏；不存在绑定时命令不执行 */
    private val actionFor: (Int) -> CommandAction? = { null },
    /** 语音 DISABLED 后所有副作用（含执行）一律吸收 */
    private val isAllowed: () -> Boolean = { true },
    /** 生命周期取消令牌；disable 时翻转，执行器每步检查 */
    private val guard: ExecutionGuard = ExecutionGuard.NONE,
) {
    suspend fun handle(effect: SideEffect) {
        if (!isAllowed()) return
        guard.ensureActive()
        when (effect) {
            SideEffect.LoadModels -> onLoadModels()
            SideEffect.UnloadModels -> onUnloadModels()
            SideEffect.StartCapture -> onStartCapture()
            SideEffect.StopCapture -> onStopCapture()
            is SideEffect.Execute -> {
                if (!isAllowed()) return
                guard.ensureActive()
                val action = actionFor(effect.commandId)
                if (action == null || action.steps.isEmpty()) {
                    onFeedback(FeedbackType.WARN, "槽位 ${effect.commandId + 1} 未绑定宏")
                    onTelemetry("exec", "unbound_${effect.commandId}")
                    onExecResult(false)
                    return
                }
                val budget = executionBudgetMs(action.steps)
                val result = try {
                    withContext(Dispatchers.IO) {
                        withTimeout(budget) { executor.execute(action) }
                    }
                } catch (e: TimeoutCancellationException) {
                    ExecResult(false, "执行超时")
                } catch (e: CancellationException) {
                    // 语音 disable 触发的取消：立即停止后续派发，不产生“执行失败”误报
                    onTelemetry("exec", "cancelled_${effect.commandId}")
                    throw e
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
