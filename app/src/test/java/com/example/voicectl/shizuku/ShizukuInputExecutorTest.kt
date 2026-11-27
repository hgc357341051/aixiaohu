package com.example.voicectl.shizuku

import com.example.voicectl.config.BaseSize
import com.example.voicectl.exec.CommandAction
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testStatus(phase: ShizukuPhase) = ShizukuStatus(
    phase = phase,
    env = ShizukuEnvironment(
        sdkInt = 34,
        shizukuInstalled = phase != ShizukuPhase.NOT_INSTALLED,
        binderAlive = phase != ShizukuPhase.NOT_INSTALLED && phase != ShizukuPhase.NOT_RUNNING,
        shizukuApiVersion = 13,
        uid = 2000,
        selinuxContext = "u:r:shell:s0",
        privilege = Privilege.SHELL,
        screenWidth = 1080,
        screenHeight = 2340,
    ),
    checkedAtMs = 1L,
)

private class FakeShizukuClient(initial: ShizukuPhase, private val script: List<ShizukuPhase> = emptyList()) :
    ShizukuClient {
    override val status = MutableStateFlow(testStatus(initial))
    var refreshCount: Int = 0
        private set

    override fun refresh() {
        refreshCount++
        if (script.isNotEmpty()) {
            status.value = testStatus(script[minOf(refreshCount - 1, script.lastIndex)])
        }
    }

    fun forcePhase(phase: ShizukuPhase) {
        status.value = testStatus(phase)
    }

    override fun start() = Unit
    override fun stop() = Unit
    override fun close() = Unit
    override fun requestPermission() = Unit
    override fun activationGuide(): ActivationGuide = ActivationGuides.forSdk(34)
    override fun debugInfo(): ShizukuDebugInfo = ShizukuDebugInfo.from(status.value)
}

private class FakeInputBridge(var result: CommandResult = CommandResult(true, 0, 1L, "ok")) : InputBridge {
    val executed = mutableListOf<InputCommand>()

    override suspend fun execute(command: InputCommand): CommandResult {
        executed += command
        return result
    }

    override suspend fun probe(): CommandResult = result
    override fun close() = Unit
}

/**
 * 执行链路的可测部分：用假 Client / 假 Bridge 驱动真实 ShizukuInputExecutor，
 * 验证「每条命令前重查、授权被撤立即中止剩余步骤」这条安全红线。
 * 不依赖真机 Shizuku 服务。
 */
class ShizukuInputExecutorTest {

    private fun executor(
        client: FakeShizukuClient,
        bridge: InputBridge?,
        width: Int = 1080,
        height: Int = 2340,
    ) = ShizukuInputExecutor(
        client = client,
        bridgeProvider = { bridge },
        mapper = CoordinateMapper(BaseSize(1080, 2340)),
        screenSizeProvider = { width to height },
    )

    private fun threeTaps() = CommandAction(
        commandId = 0,
        name = "三步点击",
        steps = listOf(
            ExecStep.Tap(900, 2100, "step_1"),
            ExecStep.Tap(760, 1750, "step_2"),
            ExecStep.Tap(500, 1000, "step_3"),
        ),
    )

    @Test
    fun `P0 执行前重查发现权限被撤立即中止剩余点击`() = runBlocking {
        // 第 1 次 refresh（Tap1 前）仍授权，第 2 次（Tap2 前）权限已被撤。
        val client = FakeShizukuClient(
            ShizukuPhase.AUTHORIZED,
            listOf(ShizukuPhase.AUTHORIZED, ShizukuPhase.AUTHORIZATION_LOST),
        )
        val bridge = FakeInputBridge()

        val result = executor(client, bridge).execute(threeTaps())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("执行中授权失效"))
        assertEquals(listOf(InputCommand.Tap(900, 2100)), bridge.executed)
        assertEquals(2, client.refreshCount)
    }

    @Test
    fun `P0 授权正常时全部点击执行且Verify不产命令`() = runBlocking {
        val client = FakeShizukuClient(
            ShizukuPhase.AUTHORIZED,
            listOf(ShizukuPhase.AUTHORIZED),
        )
        val bridge = FakeInputBridge()
        val action = CommandAction(
            commandId = 1,
            name = "含等待与校验",
            steps = listOf(
                ExecStep.Tap(900, 2100, "step_1"),
                ExecStep.Wait(0),
                ExecStep.Verify("equip_owned"),
                ExecStep.Tap(760, 1750, "step_2"),
            ),
        )

        val result = executor(client, bridge).execute(action)

        assertTrue(result.message, result.success)
        assertEquals(
            listOf(InputCommand.Tap(900, 2100), InputCommand.Tap(760, 1750)),
            bridge.executed,
        )
        assertTrue(result.message, result.message.contains("校验项 1 个"))
        assertEquals(2, client.refreshCount)
    }

    @Test
    fun `P0 非授权态直接失败且不触碰输入桥`() = runBlocking {
        val client = FakeShizukuClient(ShizukuPhase.AUTHORIZATION_LOST)
        val bridge = FakeInputBridge()

        val result = executor(client, bridge).execute(threeTaps())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("Shizuku 不可用"))
        assertTrue(bridge.executed.isEmpty())
        assertEquals(0, client.refreshCount)
    }

    @Test
    fun `P1 输入桥未初始化时直接失败`() = runBlocking {
        val client = FakeShizukuClient(ShizukuPhase.AUTHORIZED)

        val result = executor(client, null).execute(threeTaps())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("输入桥未初始化"))
        assertEquals(0, client.refreshCount)
    }

    @Test
    fun `P0 输入桥返回失败时中止后续步骤且不谎称已部分执行`() = runBlocking {
        val client = FakeShizukuClient(ShizukuPhase.AUTHORIZED, listOf(ShizukuPhase.AUTHORIZED))
        val bridge = FakeInputBridge(CommandResult(false, 125, 3L, "执行失败"))

        val result = executor(client, bridge).execute(threeTaps())

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("点击 step_1 执行受阻"))
        assertTrue(result.message, result.message.contains("可能未落地，请手动确认"))
        assertFalse("首个点击未确认落地，不应标记 attempted", result.attempted)
        assertEquals(1, bridge.executed.size)
        assertEquals(1, client.refreshCount)
    }

    @Test
    fun `P1 已有点击落地后桥失败仍标记attempted阻止降级重放`() = runBlocking {
        val client = FakeShizukuClient(
            ShizukuPhase.AUTHORIZED,
            listOf(ShizukuPhase.AUTHORIZED, ShizukuPhase.AUTHORIZED),
        )
        val bridge = object : InputBridge {
            var calls = 0
            val commands = mutableListOf<InputCommand>()
            override suspend fun execute(command: InputCommand): CommandResult {
                calls++
                commands += command
                // 第 1 次真实落地，第 2 次授权被撤导致桥失败
                return if (calls == 1) CommandResult(true, 0, 1L, "ok")
                else CommandResult(false, 125, 2L, "授权被撤")
            }
            override suspend fun probe(): CommandResult = CommandResult(true, 0, 1L, "ok")
            override fun close() = Unit
        }
        val action = CommandAction(
            commandId = 3,
            name = "两步点击",
            steps = listOf(
                ExecStep.Tap(900, 2100, "step_1"),
                ExecStep.Tap(760, 1750, "step_2"),
            ),
        )

        val result = executor(client, bridge).execute(action)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("点击 step_2 执行受阻"))
        assertTrue("已有点击落地，必须标记 attempted 以阻止降级重放", result.attempted)
        assertEquals(2, bridge.calls)
    }

    @Test
    fun `P1 映射后的坐标落入强类型Tap命令`() = runBlocking {
        val client = FakeShizukuClient(ShizukuPhase.AUTHORIZED, listOf(ShizukuPhase.AUTHORIZED))
        val bridge = FakeInputBridge()
        val action = CommandAction(
            commandId = 2,
            name = "边界点击",
            steps = listOf(ExecStep.Tap(1080, 2340, "corner")),
        )

        val result = executor(client, bridge, width = 720, height = 1600).execute(action)

        assertTrue(result.message, result.success)
        assertEquals(listOf(InputCommand.Tap(719, 1599)), bridge.executed)
    }
}
