package com.example.voicectl.exec

/** 备用执行链路：通过 PC 桥接服务发送规范化点击请求；失败即停，重试不超过 1 次 */
class AdbExecutor(
    private val clientProvider: () -> AdbClient,
    private val mapper: CoordinateMapper = CoordinateMapper(),
    private val fallbackSize: () -> Pair<Int, Int> = { 1080 to 2340 },
    private val retryMax: Int = 1,
) : Executor {
    constructor(
        client: AdbClient,
        mapper: CoordinateMapper = CoordinateMapper(),
        fallbackSize: () -> Pair<Int, Int> = { 1080 to 2340 },
        retryMax: Int = 1,
    ) : this({ client }, mapper, fallbackSize, retryMax)

    override val name: String = "ADB 桥接"

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: CommandAction): ExecResult {
        val client = clientProvider()
        if (!client.heartbeat()) return ExecResult(false, "桥接服务未连接")
        val size = client.wmSize().getOrElse { AdbClient.Size(fallbackSize().first, fallbackSize().second) }
        var lastError = ""

        repeat(retryMax + 1) { attempt ->
            var failed = false
            for (step in action.steps) {
                when (step) {
                    is ExecStep.Tap -> {
                        val (x, y) = mapper.map(step.x, step.y, size.width, size.height)
                        val result = client.tap(x, y)
                        if (result.isFailure) {
                            lastError = "点击 ${step.key} 失败（第 ${attempt + 1} 次）"
                            failed = true
                        }
                    }
                    is ExecStep.Wait -> kotlinx.coroutines.delay(step.ms)
                    is ExecStep.Verify -> Unit // 校验由上层门控负责，此处不触碰屏幕内容
                }
                if (failed) break
            }
            if (!failed) return ExecResult(true, "ADB 执行完成：${action.name}（基准 ${size.width}×${size.height}）")
        }
        return ExecResult(false, lastError.ifBlank { "ADB 执行失败" })
    }
}
