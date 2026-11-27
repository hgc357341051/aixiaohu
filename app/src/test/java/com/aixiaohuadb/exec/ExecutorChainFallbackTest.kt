package com.aixiaohuadb.exec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeExecutor(
    override val name: String,
    private val result: ExecResult,
    private val available: Boolean = true,
) : Executor {
    var callCount: Int = 0
        private set

    override fun isAvailable(): Boolean = available

    override suspend fun execute(action: CommandAction): ExecResult {
        callCount++
        return result
    }
}

/** P1-3：部分执行后的降级会重复真实点击，必须被禁止。 */
class ExecutorChainFallbackTest {
    private val action = CommandAction(
        commandId = 0,
        name = "换名刀",
        steps = listOf(ExecStep.Tap(900, 2100, "step_1"), ExecStep.Tap(760, 1750, "step_2")),
    )

    @Test
    fun `无障碍部分执行失败后不降级到ADB`() = runBlocking {
        val accessibility = FakeExecutor(
            name = "无障碍服务",
            result = ExecResult(false, "点击失败：step_2", attempted = true),
        )
        val adb = FakeExecutor(name = "内嵌 ADB", result = ExecResult(true, "ADB 执行完成"))

        val result = ExecutorChain(listOf(accessibility, adb)).execute(action)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("已部分执行"))
        assertTrue(result.message, result.message.contains("停止自动回退"))
        assertEquals(1, accessibility.callCount)
        assertEquals(0, adb.callCount)
    }

    @Test
    fun `无障碍未实际派发时允许降级到ADB`() = runBlocking {
        val accessibility = FakeExecutor(
            name = "无障碍服务",
            result = ExecResult(false, "无障碍服务未开启", attempted = false),
        )
        val adb = FakeExecutor(name = "内嵌 ADB", result = ExecResult(true, "ADB 执行完成"))

        val result = ExecutorChain(listOf(accessibility, adb)).execute(action)

        assertTrue(result.message, result.success)
        assertEquals(1, accessibility.callCount)
        assertEquals(1, adb.callCount)
        assertEquals("ADB 执行完成", result.message)
    }

    @Test
    fun `ADB部分执行失败后结果被标记为已尝试`() = runBlocking {
        val adb = FakeExecutor(
            name = "内嵌 ADB",
            result = ExecResult(false, "点击 step_1 失败", attempted = true),
        )

        val result = ExecutorChain(listOf(adb)).execute(action)

        assertFalse(result.success)
        assertEquals(1, adb.callCount)
        assertTrue(result.message, result.message.contains("已部分执行"))
    }

    @Test
    fun `全部执行器都未尝试时返回只读提示`() = runBlocking {
        val accessibility = FakeExecutor("a11y", ExecResult(false, "未开启"), available = false)
        val adb = FakeExecutor("内嵌 ADB", ExecResult(false, "未连接"))

        val result = ExecutorChain(listOf(accessibility, adb)).execute(action)

        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("请手动操作"))
        assertEquals(0, accessibility.callCount)
        assertEquals(1, adb.callCount)
    }
}
