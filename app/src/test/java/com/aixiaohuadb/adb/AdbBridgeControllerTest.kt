package com.aixiaohuadb.adb

import com.aixiaohuadb.service.ControlMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(
    private var endpoint: AdbEndpoint? = AdbEndpoint("127.0.0.1", 5555),
) : AdbTransport {
    override val queuedCount: Int get() = 0

    var heartbeatOk: Boolean = true
    var reconnectOk: Boolean = true
    var heartbeatCalls = 0
    var reconnectCalls = 0
    var cancelReason: String? = null
    var disconnected = false
    /** 测试 seam：在 heartbeat 阻塞点注入模式切换，模拟 IO 期间用户切模式 */
    var onHeartbeat: (() -> Unit)? = null

    override fun heartbeat(): Boolean {
        heartbeatCalls++
        onHeartbeat?.invoke()
        return heartbeatOk
    }
    override suspend fun reconnect(): Result<Unit> {
        reconnectCalls++
        return if (reconnectOk) Result.success(Unit)
        else Result.failure(IllegalStateException("模拟重连失败"))
    }
    override fun pairedEndpoint(): AdbEndpoint? = endpoint
    override fun cancelAll(reason: String) { cancelReason = reason }
    override fun disconnect() { disconnected = true }
    override suspend fun execute(command: InputCommand): CommandResult = CommandResult(true, "ok")
    override suspend fun executeRaw(req: RawShellRequest): CommandResult = CommandResult(true, "ok")
}

/** F6 心跳/退避 + S6 前台启动失败可见的 JVM 回归 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.ExperimentalStdlibApi::class)
class AdbBridgeControllerTest {

    private var lastStartSession = -1

    private fun TestScope.controller(
        transport: FakeTransport,
        max: Int = 6,
        starter: Boolean = true,
        confirmTimeoutMs: Long = Long.MAX_VALUE,
    ) = AdbBridgeController(
        context = null,
        client = transport,
        scope = this,
        heartbeatSecondsProvider = { 5 },
        reconnectMaxProvider = { max },
        foregroundStarter = { _, sessionId -> lastStartSession = sessionId; starter },
        foregroundConfirmTimeoutMs = confirmTimeoutMs,
        ioDispatcher = coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default,
    )

    private suspend fun TestScope.connect(controller: AdbBridgeController) {
        controller.start()
        runCurrent()
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
    }

    private suspend fun TestScope.enterBackoff(
        controller: AdbBridgeController,
        transport: FakeTransport,
    ) {
        transport.heartbeatOk = false
        // 第一次失败只记数，第二次才进入退避
        controller.heartbeatTick()
        runCurrent()
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        controller.heartbeatTick()
        runCurrent()
        assertEquals(AdbBridgePhase.RECONNECTING, controller.state.value.phase)
    }

    @Test
    fun `连续两次心跳失败才触发重连`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        val baseline = transport.reconnectCalls

        transport.reconnectOk = false
        transport.heartbeatOk = false
        controller.heartbeatTick()
        runCurrent()
        assertEquals(baseline, transport.reconnectCalls)
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        assertFalse(controller.state.value.heartbeatOk)

        controller.heartbeatTick()
        runCurrent()
        assertEquals(AdbBridgePhase.RECONNECTING, controller.state.value.phase)
        assertEquals(1, controller.state.value.retryCount)
        controller.stop()
    }

    @Test
    fun `退避序列为1 2 4 8 16 30秒`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, max = 6)
        connect(controller)
        val baseline = transport.reconnectCalls
        transport.reconnectOk = false
        enterBackoff(controller, transport)
        assertEquals(1, controller.state.value.retryCount)

        val expectedRetryCounts = listOf(2, 3, 4, 5, 6)
        val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
        delays.zip(expectedRetryCounts).forEach { (delayMs, retry) ->
            advanceTimeBy(delayMs)
            runCurrent()
            assertEquals(retry, controller.state.value.retryCount)
        }

        // 第 6 次（30s）仍失败后转 ERROR
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertEquals(baseline + 6, transport.reconnectCalls)
        assertTrue(controller.state.value.message!!.contains("重连超过 6 次"))
        controller.stop()
    }

    @Test
    fun `重连超过上限转可见 ERROR`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, max = 2)
        connect(controller)
        transport.reconnectOk = false
        enterBackoff(controller, transport)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, controller.state.value.retryCount)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertTrue(controller.state.value.message!!.contains("重连超过 2 次"))
        controller.stop()
    }

    @Test
    fun `stop 取消退避且状态不再复活`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        transport.reconnectOk = false
        enterBackoff(controller, transport)

        controller.stop()
        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertTrue(transport.disconnected)
        assertNotNull(transport.cancelReason)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertEquals(0, controller.state.value.retryCount)
    }

    @Test
    fun `过期FGS失败回调不覆盖stop后的OFF`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        val staleSession = lastStartSession

        controller.stop()
        assertFalse(controller.isActive())
        controller.reportForegroundStartFailed("过期失败", staleSession)

        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertNull(controller.state.value.message)
    }

    @Test
    fun `当前session的FGS失败回调置可见ERROR`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)

        controller.reportForegroundStartFailed("当前失败", lastStartSession)

        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertEquals("当前失败", controller.state.value.message)
        assertFalse(controller.isActive())
        controller.stop()
    }

    @Test
    fun `stop后立即start旧退避不破坏新session`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        transport.reconnectOk = false
        enterBackoff(controller, transport)
        assertEquals(AdbBridgePhase.RECONNECTING, controller.state.value.phase)

        controller.stop()
        transport.reconnectOk = true
        controller.start()
        runCurrent()
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        assertTrue(controller.isActive())

        // 推进虚拟时间，让旧 job 的 finally 有机会执行；新 session 不得被反向清理
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        assertEquals(0, controller.state.value.retryCount)
        assertTrue(controller.isActive())
        controller.stop()
    }

    @Test
    fun `前台启动失败置可见 ERROR 而非静默成功`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, starter = false)

        controller.start()
        runCurrent()

        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertNotNull(controller.state.value.message)
        assertTrue(transport.cancelReason!!.contains("启动失败"))
        controller.stop()
    }

    @Test
    fun `无配对端点置可见 ERROR`() = runTest {
        val transport = FakeTransport(endpoint = null)
        val controller = controller(transport)

        controller.start()
        runCurrent()

        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertTrue(controller.state.value.message!!.contains("配对"))
        assertNull(controller.state.value.endpoint)
        controller.stop()
    }

    @Test
    fun `切无障碍模式立即断开`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)

        controller.onModeChanged(ControlMode.ACCESSIBILITY)
        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertTrue(transport.disconnected)
    }

    @Test
    fun `重复start不新建session也不重复连接`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        val firstSession = lastStartSession
        val baseline = transport.reconnectCalls

        controller.start()
        controller.start()
        runCurrent()

        assertEquals(firstSession, lastStartSession)
        assertEquals(baseline, transport.reconnectCalls)
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        assertTrue(controller.isActive())
        controller.stop()
    }

    @Test
    fun `心跳IO阻塞期间切无障碍不复活OFF状态`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        connect(controller)
        // 在探活阻塞点模拟用户模式切换，stop 先于心跳回写发生
        transport.onHeartbeat = { controller.onModeChanged(ControlMode.ACCESSIBILITY) }

        controller.heartbeatTick()
        runCurrent()

        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertFalse(controller.state.value.heartbeatOk)
        assertEquals(0L, controller.state.value.lastHeartbeatAt)
        assertFalse(controller.isActive())
        assertTrue(transport.disconnected)

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
    }

    @Test
    fun `快速反复切换ADB与无障碍最终收敛OFF`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport)
        repeat(6) {
            controller.onModeChanged(ControlMode.ADB)
            controller.onModeChanged(ControlMode.ACCESSIBILITY)
        }
        runCurrent()

        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertFalse(controller.isActive())
        // 旧 session 的 connectLast / 超时回调都不得把状态拉回来
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(AdbBridgePhase.OFF, controller.state.value.phase)
        assertNull(controller.state.value.message)
    }

    @Test
    fun `FGS确认超时置可见ERROR`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, confirmTimeoutMs = 1_000L)

        controller.start()
        runCurrent()
        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(AdbBridgePhase.ERROR, controller.state.value.phase)
        assertTrue(controller.state.value.message!!.contains("超时"))
        assertFalse(controller.isActive())
        assertTrue(transport.cancelReason!!.contains("启动失败"))
        controller.stop()
    }

    @Test
    fun `FGS确认回调阻止超时ERROR`() = runTest {
        val transport = FakeTransport()
        val controller = controller(transport, confirmTimeoutMs = 1_000L)

        controller.start()
        runCurrent()
        controller.reportForegroundStarted(lastStartSession)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(AdbBridgePhase.CONNECTED, controller.state.value.phase)
        assertNull(controller.state.value.message)
        assertTrue(controller.isActive())
        controller.stop()
    }
}
