package com.example.voicectl.adb

import com.example.voicectl.config.BaseSize
import com.example.voicectl.exec.CommandAction
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecResult
import com.example.voicectl.exec.ExecStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCommandExecutor(
    private val result: CommandResult,
) : InputCommandExecutor {
    val commands = mutableListOf<InputCommand>()

    override suspend fun execute(command: InputCommand): CommandResult {
        commands += command
        return result
    }
}

class AdbInputExecutorTest {
    private val mapper = CoordinateMapper(BaseSize(1080, 2340))
    private val screen = 540 to 1170
    private val action = CommandAction(
        commandId = 0,
        name = "换名刀",
        steps = listOf(
            ExecStep.Tap(540, 1170, "step_1"),
            ExecStep.Verify("step_1"),
            ExecStep.Tap(270, 585, "step_2"),
        ),
    )

    @Test
    fun `基准坐标按真实屏幕换算后下发`() = runBlocking {
        val fake = FakeCommandExecutor(CommandResult(true, "ok", attempted = true))
        val executor = AdbInputExecutor(fake, mapper, { screen })

        val result = executor.execute(action)

        assertTrue(result.success)
        assertEquals(
            listOf(InputCommand.Tap(270, 585), InputCommand.Tap(135, 293)),
            fake.commands,
        )
    }

    @Test
    fun `真正派发后的失败标记attempted阻止降级重放`() = runBlocking {
        val fake = FakeCommandExecutor(CommandResult(false, "shell error", attempted = true))
        val executor = AdbInputExecutor(fake, mapper, { screen })

        val result = executor.execute(action)

        assertFalse(result.success)
        assertTrue(result.attempted)
        assertTrue(result.message.contains("可能未落地"))
        assertEquals(1, fake.commands.size)
    }

    @Test
    fun `前置校验失败不标记attempted`() = runBlocking {
        val fake = FakeCommandExecutor(CommandResult(false, "未连接"))
        val executor = AdbInputExecutor(fake, mapper, { screen })

        val result: ExecResult = executor.execute(action)

        assertFalse(result.success)
        assertFalse(result.attempted)
        assertEquals(1, fake.commands.size)
    }
}
