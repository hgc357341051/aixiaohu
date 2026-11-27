package com.aixiaohuadb.machine

import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.ExecResult
import com.aixiaohuadb.exec.ExecutionGuard
import com.aixiaohuadb.exec.Executor
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.ExecStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingExecutor : Executor {
    override val name: String = "统计执行器"
    var calls = 0

    override fun isAvailable(): Boolean = true
    override suspend fun execute(action: CommandAction): ExecResult {
        calls++
        return ExecResult(true, "ok", attempted = true)
    }
}

private class Counters {
    var load = 0
    var unload = 0
    var startCapture = 0
    var stopCapture = 0
    val feedback = mutableListOf<Pair<FeedbackType, String>>()
    val telemetry = mutableListOf<Pair<String, String>>()
    val execResults = mutableListOf<Boolean>()
}

/** S3：DISABLED 吸收副作用、执行前取消与未绑定槽位的 JVM 回归 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlActionHandlerTest {

    private fun handler(
        counters: Counters,
        executor: CountingExecutor,
        isAllowed: () -> Boolean = { true },
        guard: ExecutionGuard = ExecutionGuard.NONE,
        actionFor: (Int) -> CommandAction? = { null },
    ) = ControlActionHandler(
        onLoadModels = { counters.load++ },
        onUnloadModels = { counters.unload++ },
        onStartCapture = { counters.startCapture++ },
        onStopCapture = { counters.stopCapture++ },
        executor = ExecutorChain(listOf(executor)),
        onFeedback = { type, text -> counters.feedback += type to text },
        onTelemetry = { dim, name -> counters.telemetry += dim to name },
        onExecResult = { counters.execResults += it },
        actionFor = actionFor,
        isAllowed = isAllowed,
        guard = guard,
    )

    private val allEffects = listOf(
        SideEffect.LoadModels,
        SideEffect.UnloadModels,
        SideEffect.StartCapture,
        SideEffect.StopCapture,
        SideEffect.Execute(0),
        SideEffect.Feedback(FeedbackType.INFO, "提示"),
        SideEffect.Telemetry("dim", "name"),
        SideEffect.RequestConfirmExit,
    )

    @Test
    fun `isAllowed为false时吸收全部副作用`() = runTest {
        val counters = Counters()
        val executor = CountingExecutor()
        val handler = handler(counters, executor, isAllowed = { false })

        allEffects.forEach { handler.handle(it) }

        assertEquals(0, counters.load)
        assertEquals(0, counters.unload)
        assertEquals(0, counters.startCapture)
        assertEquals(0, counters.stopCapture)
        assertEquals(0, executor.calls)
        assertTrue(counters.feedback.isEmpty())
        assertTrue(counters.telemetry.isEmpty())
        assertTrue(counters.execResults.isEmpty())
    }

    @Test
    fun `执行令牌已取消时所有副作用立即停止`() = runTest {
        val counters = Counters()
        val executor = CountingExecutor()
        val handler = handler(counters, executor, guard = ExecutionGuard { true })

        allEffects.forEach { effect ->
            val outcome = runCatching { handler.handle(effect) }
            assertTrue("$effect 应被取消", outcome.exceptionOrNull() is CancellationException)
        }

        assertEquals(0, counters.load)
        assertEquals(0, counters.unload)
        assertEquals(0, counters.startCapture)
        assertEquals(0, counters.stopCapture)
        assertEquals(0, executor.calls)
        assertTrue(counters.execResults.isEmpty())
    }

    @Test
    fun `未绑定槽位只提示不执行`() = runTest {
        val counters = Counters()
        val executor = CountingExecutor()
        val handler = handler(counters, executor)

        handler.handle(SideEffect.Execute(14))

        assertEquals(0, executor.calls)
        assertEquals(listOf(false), counters.execResults)
        assertTrue(
            counters.feedback.any {
                it.first == FeedbackType.WARN && it.second.contains("未绑定")
            },
        )
        assertTrue(counters.telemetry.any { it.second == "unbound_14" })
    }

    @Test
    fun `已绑定槽位正常执行并回执成功`() = runTest {
        val counters = Counters()
        val executor = CountingExecutor()
        val action = CommandAction(2, "连招", listOf(ExecStep.Tap(1, 2)))
        val handler = handler(counters, executor, actionFor = { if (it == 2) action else null })

        handler.handle(SideEffect.Execute(2))

        assertEquals(1, executor.calls)
        assertEquals(listOf(true), counters.execResults)
        assertFalse(counters.feedback.any { it.first == FeedbackType.ERROR })
    }
}
