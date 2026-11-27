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

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionBudgetTest {

    @Test
    fun `24步各10000毫秒预算封顶60000`() {
        val steps = List(24) { ExecStep.Tap(0, 0, delayAfterMs = 10_000L) }
        assertEquals(60_000L, executionBudgetMs(steps))
    }

    @Test
    fun `空步骤与短步骤都有10秒保底`() {
        assertEquals(10_000L, executionBudgetMs(emptyList()))
        assertEquals(10_000L, executionBudgetMs(listOf(ExecStep.Tap(0, 0, delayAfterMs = 100L))))
    }

    @Test
    fun `逐项夹紧后求和不会溢出`() {
        val steps = List(10) { ExecStep.Tap(0, 0, delayAfterMs = Long.MAX_VALUE) }
        assertEquals(60_000L, executionBudgetMs(steps))
        assertEquals(MAX_DELAY_MS, Long.MAX_VALUE.clampDelayMs())
        assertEquals(0L, (-1L).clampDelayMs())
    }

    @Test
    fun `delayAfterMs按虚拟时间真实等待`() = runTest {
        val client = object : InputCommandExecutor {
            override suspend fun execute(command: InputCommand): CommandResult =
                CommandResult(true, "ok", attempted = true)
        }
        val executor = AdbInputExecutor(
            client,
            CoordinateMapper(BaseSize(1080, 2340)),
            { 1080 to 2340 },
        )
        val action = CommandAction(
            commandId = 0,
            name = "虚拟延迟",
            steps = listOf(ExecStep.Tap(540, 1170, delayAfterMs = 5_000L)),
        )

        val execution = async { executor.execute(action) }
        runCurrent()
        assertFalse(execution.isCompleted)
        advanceTimeBy(4_999L)
        runCurrent()
        assertFalse(execution.isCompleted)
        advanceTimeBy(1L)
        runCurrent()
        assertTrue(execution.await().success)
    }
}
