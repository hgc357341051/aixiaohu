package com.aixiaohuadb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCommandQueueTest {

    @Test
    fun `submit 串行执行并通过回执返回结果`() = runTest {
        val executed = mutableListOf<Int>()
        val queue = AdbCommandQueue(
            executeTyped = { command ->
                executed += (command as InputCommand.KeyEvent).keyCode
                CommandResult(true, "ok", attempted = true)
            },
            executeRaw = { CommandResult(true, "raw") },
        )
        queue.start(this)

        val first = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(3)))
        val second = queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(4)))

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(listOf(3, 4), executed)
        assertEquals(0, queue.queuedCount)
        queue.close()
    }

    @Test
    fun `cancelAll 取消排队命令且不打断执行中的命令`() = runTest {
        val release = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val executed = mutableListOf<Int>()
        val queue = AdbCommandQueue(
            executeTyped = { command ->
                val code = (command as InputCommand.KeyEvent).keyCode
                if (code == 1) {
                    firstStarted.complete(Unit)
                    release.await()
                }
                executed += code
                CommandResult(true, "ok", attempted = true)
            },
            executeRaw = { CommandResult(true, "raw") },
        )
        queue.start(this)

        val first = async { queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(1))) }
        firstStarted.await()
        val second = async { queue.submit(AdbCommand.Typed(InputCommand.KeyEvent(2))) }
        yield()
        assertEquals(2, queue.queuedCount)

        queue.cancelAll("测试取消")
        release.complete(Unit)

        assertTrue(first.await().ok)
        val cancelled = second.await()
        assertFalse(cancelled.ok)
        assertTrue(cancelled.message.contains("测试取消"))
        assertEquals(listOf(1), executed)
        assertEquals(0, queue.queuedCount)
        queue.close()
    }
}
