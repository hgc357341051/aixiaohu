package com.aixiaohuadb.exec

/**
 * 执行优先级：无障碍（主）→ 内嵌 ADB（备）→ 只读提示（禁止注入 / Hook）。
 *
 * 降级只在「前一条链路尚未真正派发点击」时允许；
 * 一旦 result.attempted=true 仍失败，整条 action 重放会造成重复点击，必须停止降级。
 */
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
            if (result.attempted) {
                return ReadOnlyExecutor.execute(action).copy(
                    message = "已部分执行，为避免重复点击停止自动回退，请手动确认；" +
                        messages.joinToString("；"),
                )
            }
        }
        val readonly = ReadOnlyExecutor.execute(action)
        return readonly.copy(
            message = "${messages.joinToString("；")}；${readonly.message}".trimStart('；'),
        )
    }
}
