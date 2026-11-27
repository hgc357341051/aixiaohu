package com.aixiaohuadb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 用户/宏/调试发起的 raw shell 请求；所有执行必须经 AdbBridge */
data class RawShellRequest(
    val command: String,
    val source: CommandSource,
    val timeoutMs: Long = 5_000L,
)

enum class CommandSource { USER_CONSOLE, MACRO_STEP, DEBUG }

/** 队列内命令：强类型输入或审计后的 raw shell */
sealed class AdbCommand {
    data class Typed(val command: InputCommand) : AdbCommand()
    data class Raw(val request: RawShellRequest) : AdbCommand()
}

data class QueuedCommand(
    val command: AdbCommand,
    val receipt: CompletableDeferred<CommandResult>,
)

/**
 * 单消费者命令队列：所有下发串行化，避免长连接上的并发写交错；
 * 每条命令用 CompletableDeferred 回执，取消/重连时统一失败，不留悬挂等待。
 */
class AdbCommandQueue(
    private val executeTyped: suspend (InputCommand) -> CommandResult,
    private val executeRaw: suspend (RawShellRequest) -> CommandResult,
    private val capacity: Int = Channel.UNLIMITED,
) {
    private val channel = Channel<QueuedCommand>(capacity)
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var consumerJob: kotlinx.coroutines.Job? = null

    val queuedCount: Int get() = pending.get()

    fun start(scope: CoroutineScope) {
        if (consumerJob?.isActive == true) return
        consumerJob = scope.launch {
            for (item in channel) {
                // 每条命令独立兜底：单条取消/异常不能打死单消费者，否则后续回执永久悬挂
                val result = try {
                    when (val cmd = item.command) {
                        is AdbCommand.Typed -> executeTyped(cmd.command)
                        is AdbCommand.Raw -> executeRaw(cmd.request)
                    }
                } catch (t: CancellationException) {
                    CommandResult(false, "命令已取消：${t.message ?: "无原因"}")
                } catch (t: Throwable) {
                    CommandResult(false, "执行异常：${t.message ?: "未知错误"}")
                }
                pending.decrementAndGet()
                item.receipt.complete(result)
            }
        }
    }

    suspend fun submit(cmd: AdbCommand): CommandResult {
        val receipt = CompletableDeferred<CommandResult>()
        pending.incrementAndGet()
        try {
            channel.send(QueuedCommand(cmd, receipt))
        } catch (t: Throwable) {
            // 入队失败（通道关闭/协程取消）必须回滚计数，否则 queuedCount 永久虚高
            pending.decrementAndGet()
            throw t
        }
        return receipt.await()
    }

    fun cancelAll(reason: String) {
        while (true) {
            val item = channel.tryReceive().getOrNull() ?: break
            pending.decrementAndGet()
            item.receipt.complete(CommandResult(false, "命令已取消：$reason"))
        }
    }

    fun close() {
        consumerJob?.cancel()
        consumerJob = null
        cancelAll("队列已关闭")
        channel.close()
    }
}
