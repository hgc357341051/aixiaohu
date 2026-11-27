package com.aixiaohuadb.adb

/**
 * ADB 长连接的最小可测边界。
 * AdbClient 实现它；AdbBridgeController 只依赖接口，
 * 让心跳失败、退避序列、超限 ERROR、stop 取消退避可在 JVM 单测覆盖。
 */
interface AdbTransport {
    val queuedCount: Int
    fun heartbeat(): Boolean
    suspend fun reconnect(): Result<Unit>
    fun pairedEndpoint(): AdbEndpoint?
    fun cancelAll(reason: String)
    fun disconnect()
    suspend fun execute(command: InputCommand): CommandResult
    suspend fun executeRaw(req: RawShellRequest): CommandResult
}
