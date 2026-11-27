package com.aixiaohuadb.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1：单条命令被取消不得打死单消费者，否则后续命令的 CompletableDeferred 永久悬挂 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdbCommandQueueEdgeTest {

    @Test
    fun `首条命令取消后第二条仍正常回执`() = runTest {
        val executed = mutableListOf<Int>()
        val queue = AdbCommandQueue(
            executeTyped = { command ->
                val code = (command as InputCommand.KeyEvent).keyCode
                if (code == 1) throw CancellationException("模拟指令取消")
                executed += code
                CommandResult(true, "ok", attempted = true)
            },
            executeRaw = { CommandResult(true, "raw") },
        )
        queue.start(this)

        val first = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(1)))
        val second = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(2)))

        assertFalse(first.ok)
        assertTrue(first.message.contains("已取消"))
        assertTrue(first.message.contains("模拟指令取消"))
        assertTrue(second.ok)
        assertEquals(listOf(2), executed)
        assertEquals(0, queue.queuedCount)
        queue.close()
    }

    @Test
    fun `首条命令抛异常后第二条仍正常回执`() = runTest {
        val executed = mutableListOf<Int>()
        val queue = AdbCommandQueue(
            executeTyped = { command ->
                val code = (command as InputCommand.KeyEvent).keyCode
                if (code == 1) throw IllegalStateException("模拟执行异常")
                executed += code
                CommandResult(true, "ok", attempted = true)
            },
            executeRaw = { CommandResult(true, "raw") },
        )
        queue.start(this)

        val first = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(1)))
        val second = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(2)))

        assertFalse(first.ok)
        assertTrue(first.message.contains("执行异常"))
        assertTrue(second.ok)
        assertEquals(listOf(2), executed)
        queue.close()
    }
}
