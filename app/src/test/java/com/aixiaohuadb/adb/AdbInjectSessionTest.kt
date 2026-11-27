package com.aixiaohuadb.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class AdbInjectSessionTest {
    private class FakeTransport(
        private val autoReady: Boolean = true,
        private val gestureReply: String = "OK 5",
    ) : InjectTransport {
        val writes = LinkedBlockingQueue<String>()
        private val packets = LinkedBlockingQueue<InjectEvent>()

        init {
            if (autoReady) feed("READY")
        }

        fun feed(line: String) {
            packets.put(InjectEvent.Output(line + "\n"))
        }

        fun exit() {
            packets.put(InjectEvent.Exit)
        }

        override fun read(): InjectEvent = packets.take()

        override fun write(text: String) {
            writes.put(text)
            if (text.startsWith("G ")) feed(gestureReply)
        }

        override fun close() {
            packets.offer(InjectEvent.Exit)
        }
    }

    private fun pointer(vararg points: InputCommand.MotionPoint) =
        InputCommand.MotionPointer(points.toList())

    private fun session(
        apkPath: String = "/data/app/base.apk",
        factory: (String) -> InjectTransport?,
    ) = AdbInjectSession(
        apkPathProvider = { apkPath },
        transportFactory = factory,
        elapsedRealtime = { 1_000L },
    )

    @Test
    fun `启动后发送手势协议并解析 OK`() = runBlocking {
        val transport = FakeTransport()
        val injectSession = session { transport }

        val result = withTimeout(5_000) {
            injectSession.inject(
                listOf(
                    pointer(InputCommand.MotionPoint(100, 200, 0), InputCommand.MotionPoint(150, 250, 100)),
                    pointer(InputCommand.MotionPoint(500, 600, 0), InputCommand.MotionPoint(550, 650, 100)),
                ),
                durationMs = 120,
            )
        }

        assertTrue(result is AdbInjectSession.InjectResult.Success)
        assertEquals(5, (result as AdbInjectSession.InjectResult.Success).frames)
        assertEquals(
            "G 120 2\n" +
                "P 0 2 100 200 0 150 250 100\n" +
                "P 1 2 500 600 0 550 650 100\n" +
                "E\n",
            transport.writes.poll(),
        )
    }

    @Test
    fun `启动输出 ERR 时返回 Unavailable 供回退`() = runBlocking {
        val transport = FakeTransport(autoReady = false).apply { feed("ERR init no permission") }
        val injectSession = session { transport }

        val result = withTimeout(5_000) {
            injectSession.inject(listOf(pointer(InputCommand.MotionPoint(1, 2, 0))), 100)
        }

        assertTrue(result is AdbInjectSession.InjectResult.Unavailable)
        assertTrue((result as AdbInjectSession.InjectResult.Unavailable).reason.contains("no permission"))
    }

    @Test
    fun `注入被拒绝返回 Failed 且不再回退`() = runBlocking {
        val transport = FakeTransport(gestureReply = "ERR inject rejected")
        val injectSession = session { transport }

        val result = withTimeout(5_000) {
            injectSession.inject(listOf(pointer(InputCommand.MotionPoint(1, 2, 0))), 100)
        }

        assertTrue(result is AdbInjectSession.InjectResult.Failed)
        assertTrue((result as AdbInjectSession.InjectResult.Failed).reason.contains("inject rejected"))
    }

    @Test
    fun `APK 路径不可用时直接 Unavailable 且不创建进程`() = runBlocking {
        val created = AtomicInteger(0)
        val injectSession = session(
            factory = { created.incrementAndGet(); null },
            apkPath = "",
        )

        val result = withTimeout(5_000) {
            injectSession.inject(listOf(pointer(InputCommand.MotionPoint(1, 2, 0))), 100)
        }

        assertTrue(result is AdbInjectSession.InjectResult.Unavailable)
        assertEquals(0, created.get())
    }

    @Test
    fun `进程退出后下一次注入会重新启动`() = runBlocking {
        val created = AtomicInteger(0)
        val first = FakeTransport()
        val second = FakeTransport()
        val injectSession = session {
            if (created.incrementAndGet() == 1) first else second
        }

        val firstResult = withTimeout(5_000) {
            injectSession.inject(listOf(pointer(InputCommand.MotionPoint(1, 2, 0))), 100)
        }
        assertTrue(firstResult is AdbInjectSession.InjectResult.Success)

        first.exit()
        Thread.sleep(200)

        val secondResult = withTimeout(5_000) {
            injectSession.inject(listOf(pointer(InputCommand.MotionPoint(3, 4, 0))), 100)
        }
        assertTrue(secondResult is AdbInjectSession.InjectResult.Success)
        assertEquals(2, created.get())
    }
}
