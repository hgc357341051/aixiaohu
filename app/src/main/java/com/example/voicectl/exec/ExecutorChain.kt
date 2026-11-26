package com.example.voicectl.exec

/** 执行优先级：无障碍（主）→ ADB（备）→ 只读提示（禁止注入 / Hook） */
class ExecutorChain(private val executors: List<Executor>) : Executor {
    override val name: String = "执行链"

    override fun isAvailable(): Boolean = executors.any { it.isAvailable() }

    override suspend fun execute(action: CommandAction): ExecResult {
        val available = executors.filter { it.isAvailable() }
        val messages = mutableListOf<String>()
        for (executor in available) {
            val result = executor.execute(action)
            if (result.success) return result
            messages += "${executor.name}: ${result.message}"
        }
        return ReadOnlyExecutor.execute(action).copy(
            message = "${messages.joinToString("；")}；${ReadOnlyExecutor.execute(action).message}",
        )
    }
}
