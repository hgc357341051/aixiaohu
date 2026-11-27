package com.aixiaohuadb.exec

import com.aixiaohuadb.adb.AdbInputExecutor
import com.aixiaohuadb.adb.CommandResult
import com.aixiaohuadb.adb.InputCommand
import com.aixiaohuadb.adb.InputCommandExecutor
import com.aixiaohuadb.config.BaseSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingAdbClient : InputCommandExecutor {
    val commands = mutableListOf<InputCommand>()

    override suspend fun execute(command: InputCommand): CommandResult {
        commands += command
        return CommandResult(true, "ok", attempted = true)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExecStepDelayTest {

    @Test
    fun `maxDelayMs 汇总步上延迟`() {
        assertEquals(120L, ExecStep.Tap(1, 2, delayAfterMs = 120L).maxDelayMs)
        assertEquals(80L, ExecStep.Swipe(1, 2, 3, 4, delayAfterMs = 80L).maxDelayMs)
        @Suppress("DEPRECATION")
        assertEquals(300L, ExecStep.Wait(300L).maxDelayMs)
        assertEquals(0L, ExecStep.Verify("k").maxDelayMs)
    }

    @Test
    fun `Tap 与 Swipe 成功派发后才应用步上间隔`() = runTest {
        val mapper = CoordinateMapper(BaseSize(1080, 2340))
        val client = RecordingAdbClient()
        val executor = AdbInputExecutor(client, mapper, { 540 to 1170 })
        val action = CommandAction(
            commandId = 0,
            name = "延迟验证",
            steps = listOf(
                ExecStep.Tap(540, 1170, delayAfterMs = 40L),
                ExecStep.Swipe(0, 0, 540, 1170, durationMs = 300, delayAfterMs = 30L),
            ),
        )

        val execution = async { executor.execute(action) }
        runCurrent()
        // 第一步立即派发，随后进入 40ms 虚拟延迟
        assertEquals(listOf(InputCommand.Tap(270, 585)), client.commands)
        assertFalse(execution.isCompleted)

        advanceTimeBy(40L)
        runCurrent()
        // 第二步派发，再进入 30ms 虚拟延迟
        assertEquals(
            listOf(
                InputCommand.Tap(270, 585),
                InputCommand.Swipe(0, 0, 270, 585, 300),
            ),
            client.commands,
        )
        assertFalse(execution.isCompleted)

        advanceTimeBy(30L)
        runCurrent()
        assertTrue(execution.await().success)
    }
}
