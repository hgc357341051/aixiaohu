package com.example.voicectl.exec

/** 一条指令的可执行步骤序列；Tap 坐标为 1080×2340 基准坐标 */
data class CommandAction(
    val commandId: Int,
    val name: String,
    val steps: List<ExecStep>,
)

sealed class ExecStep {
    data class Tap(val x: Int, val y: Int, val key: String = "") : ExecStep()
    data class Wait(val ms: Long) : ExecStep()
    data class Verify(val key: String) : ExecStep()
}

/**
 * [attempted] 表示本执行器是否已经真实派发过至少一条点击命令。
 * 一旦为 true，失败时执行链禁止降级重试，否则会造成同一 action 被重复点击。
 */
data class ExecResult(
    val success: Boolean,
    val message: String = "",
    val attempted: Boolean = false,
)

interface Executor {
    val name: String
    fun isAvailable(): Boolean
    suspend fun execute(action: CommandAction): ExecResult
}

/** 最后一道只读提示：绝不执行点击、注入或 Hook */
object ReadOnlyExecutor : Executor {
    override val name: String = "只读提示"
    override fun isAvailable(): Boolean = true
    override suspend fun execute(action: CommandAction): ExecResult =
        ExecResult(false, "当前无可用执行链路，请手动操作：${action.name}")
}
