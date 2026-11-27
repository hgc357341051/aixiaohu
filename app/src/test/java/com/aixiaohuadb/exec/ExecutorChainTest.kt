package com.aixiaohuadb.exec

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行链降级语义。
 *
 * 当前默认顺序是 ADB → 无障碍，但顺序本身由 AppContainer 决定；
 * 这里守住的是"什么时候允许换下一条链路"：
 * - 未派发（attempted=false）→ 允许降级；
 * - 已派发但失败 → 禁止降级，否则会重复点击；
 * - 不可用 → 直接跳过。
 */
class ExecutorChainTest {

    private class Fake(
        override val name: String,
        private val available: Boolean = true,
        private val result: ExecResult,
        private val calls: MutableList<String>,
    ) : Executor {
        override fun isAvailable(): Boolean = available

        override suspend fun execute(action: CommandAction): ExecResult {
            calls += name
            return result
        }
    }

    private val action = CommandAction(1, "test", listOf(ExecStep.Tap(10, 10)))

    @Test
    fun `第一条失败且未派发时降级到第二条`() = runBlocking {
        val calls = mutableListOf<String>()
        val chain = ExecutorChain(
            listOf(
                Fake("adb", result = ExecResult(false, "未连接", attempted = false), calls = calls),
                Fake("a11y", result = ExecResult(true, "ok"), calls = calls),
            ),
        )
        val result = chain.execute(action)
        assertTrue(result.success)
        assertEquals(listOf("adb", "a11y"), calls)
    }

    @Test
    fun `已派发后失败不再降级`() = runBlocking {
        val calls = mutableListOf<String>()
        val chain = ExecutorChain(
            listOf(
                Fake("adb", result = ExecResult(false, "超时", attempted = true), calls = calls),
                Fake("a11y", result = ExecResult(true, "ok"), calls = calls),
            ),
        )
        val result = chain.execute(action)
        assertFalse(result.success)
        assertEquals(listOf("adb"), calls)
        assertTrue(result.message.contains("已部分执行"))
    }

    @Test
    fun `不可用的链路直接跳过`() = runBlocking {
        val calls = mutableListOf<String>()
        val chain = ExecutorChain(
            listOf(
                Fake("adb", available = false, result = ExecResult(false, "未连接"), calls = calls),
                Fake("a11y", result = ExecResult(true, "ok"), calls = calls),
            ),
        )
        assertTrue(chain.execute(action).success)
        assertEquals(listOf("a11y"), calls)
    }

    @Test
    fun `全部失败时返回只读提示而不是假装成功`() = runBlocking {
        val calls = mutableListOf<String>()
        val chain = ExecutorChain(
            listOf(
                Fake("adb", result = ExecResult(false, "未连接"), calls = calls),
                Fake("a11y", result = ExecResult(false, "无障碍未开启"), calls = calls),
            ),
        )
        val result = chain.execute(action)
        assertFalse(result.success)
        assertTrue(result.message.contains("adb"))
        assertTrue(result.message.contains("a11y"))
    }
}
