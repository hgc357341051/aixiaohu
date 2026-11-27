package com.aixiaohuadb.adb

import android.os.SystemClock
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 注入服务 shell 的读事件；与 kadb 解耦，单测不需要加载 Android 侧类。 */
internal sealed interface InjectEvent {
    data class Output(val chunk: String) : InjectEvent
    data class Error(val text: String) : InjectEvent
    data object Exit : InjectEvent
}

/** 注入服务的 shell 传输层；单测可替换为内存实现。 */
internal interface InjectTransport {
    fun read(): InjectEvent
    fun write(text: String)
    fun close()
}

internal class KadbInjectTransport(private val stream: AdbShellStream) : InjectTransport {
    override fun read(): InjectEvent = when (val packet = stream.read()) {
        is AdbShellPacket.StdOut -> InjectEvent.Output(String(packet.payload, Charsets.UTF_8))
        is AdbShellPacket.StdError -> InjectEvent.Error(String(packet.payload, Charsets.UTF_8))
        is AdbShellPacket.Exit -> InjectEvent.Exit
    }

    override fun write(text: String) = stream.write(text)
    override fun close() = stream.close()
}

/**
 * 驻留的 app_process 多指注入会话（scrcpy 同款免 root 路径）。
 *
 * 首次使用时通过 ADB shell 启动：
 * `CLASSPATH=<base.apk> app_process /system/bin com.aixiaohuadb.inject.InjectServer`
 * 之后复用同一条 shell 的 stdin/stdout 发送手势并等待 OK/ERR。
 *
 * 与 sendevent 的分工：
 *  - 本会话可用时优先走它：不写 /dev/input、不需要 root、坐标就是显示坐标；
 *  - 启动失败（OEM 禁 app_process / 读不到 APK）返回 [InjectResult.Unavailable]，
 *    由调用方回退到 sendevent；
 *  - 已经发出帧后失败返回 [InjectResult.Failed]，禁止再回退，避免重复触摸。
 */
internal class AdbInjectSession(
    private val apkPathProvider: () -> String,
    private val transportFactory: (String) -> InjectTransport?,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    sealed interface InjectResult {
        data class Success(val frames: Int) : InjectResult
        data class Unavailable(val reason: String) : InjectResult
        data class Failed(val reason: String) : InjectResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val lock = Any()

    @Volatile
    private var alive = false
    private var stream: InjectTransport? = null
    private var readerJob: Job? = null
    private var lines: Channel<String>? = null

    @Volatile
    private var lastError: String = ""
    private var nextRetryAtMs: Long = 0L

    suspend fun inject(
        pointers: List<InputCommand.MotionPointer>,
        durationMs: Int,
    ): InjectResult = mutex.withLock {
        if (pointers.isEmpty() || pointers.size > CommandValidator.MAX_POINTERS) {
            return@withLock InjectResult.Failed("多指数量非法：${pointers.size}")
        }
        if (pointers.any { it.points.isEmpty() }) {
            return@withLock InjectResult.Failed("多指轨迹为空")
        }
        if (!ensureStarted()) {
            return@withLock InjectResult.Unavailable(lastError.ifBlank { "注入服务不可用" })
        }

        val activeStream = synchronized(lock) { stream }
            ?: return@withLock InjectResult.Unavailable("注入服务已断开")
        val channel = synchronized(lock) { lines }
            ?: return@withLock InjectResult.Unavailable("注入服务已断开")

        val payload = buildPayload(pointers, durationMs)
        val response = try {
            activeStream.write(payload)
            withTimeoutOrNull(durationMs.coerceAtLeast(0).toLong() + RESPONSE_TIMEOUT_SLACK_MS) {
                channel.receive()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            closeSession()
            return@withLock InjectResult.Failed("注入服务写入失败：${error.message ?: "未知错误"}")
        }

        if (response == null) {
            closeSession()
            return@withLock InjectResult.Failed("注入服务无响应")
        }
        if (response.startsWith("OK")) {
            InjectResult.Success(response.removePrefix("OK").trim().toIntOrNull() ?: 0)
        } else {
            InjectResult.Failed(response.removePrefix("ERR").trim().ifBlank { "注入被拒绝" })
        }
    }

    private suspend fun ensureStarted(): Boolean {
        synchronized(lock) {
            if (alive && stream != null) return true
        }
        if (elapsedRealtime() < nextRetryAtMs) return false

        val apkPath = apkPathProvider()
        if (apkPath.isBlank() || apkPath.contains('\'')) {
            lastError = "应用 APK 路径不可用"
            nextRetryAtMs = elapsedRealtime() + RETRY_COOLDOWN_MS
            return false
        }
        val command = "CLASSPATH='$apkPath' app_process /system/bin $SERVER_CLASS"
        val opened = runCatching { transportFactory(command) }.getOrNull()
        if (opened == null) {
            lastError = "无法启动 app_process 注入服务"
            nextRetryAtMs = elapsedRealtime() + RETRY_COOLDOWN_MS
            return false
        }

        val channel = Channel<String>(Channel.UNLIMITED)
        synchronized(lock) {
            stream = opened
            lines = channel
            alive = true
            lastError = ""
        }
        readerJob = scope.launch { readLoop(opened, channel) }

        val ready = try {
            withTimeoutOrNull(START_TIMEOUT_MS) { channel.receive() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (ready != "READY") {
            lastError = when {
                ready == null -> lastError.ifBlank { "注入服务启动超时" }
                ready.startsWith("ERR") -> ready.removePrefix("ERR").trim()
                else -> "注入服务启动失败：${ready.take(160)}"
            }
            closeSession()
            nextRetryAtMs = elapsedRealtime() + RETRY_COOLDOWN_MS
            return false
        }
        return true
    }

    private fun readLoop(activeStream: InjectTransport, channel: Channel<String>) {
        val pending = StringBuilder()
        try {
            while (scope.isActive) {
                when (val event = activeStream.read()) {
                    is InjectEvent.Exit -> break
                    is InjectEvent.Error -> {
                        val error = event.text.trim()
                        if (error.isNotBlank()) lastError = error.take(200)
                    }
                    is InjectEvent.Output -> {
                        pending.append(event.chunk)
                        if (pending.length > MAX_PENDING_CHARS) pending.clear()
                        while (true) {
                            val newline = pending.indexOf('\n')
                            if (newline < 0) break
                            val line = pending.substring(0, newline).trim()
                            pending.delete(0, newline + 1)
                            if (line.isNotEmpty()) {
                                runCatching { channel.trySend(line) }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 连接关闭/进程退出都按会话结束处理
        } finally {
            synchronized(lock) {
                if (stream === activeStream) {
                    alive = false
                    stream = null
                    readerJob = null
                }
            }
            runCatching { activeStream.close() }
            channel.close()
        }
    }

    private fun closeSession() {
        val (activeStream, job) = synchronized(lock) {
            alive = false
            val s = stream
            val j = readerJob
            stream = null
            readerJob = null
            s to j
        }
        job?.cancel()
        runCatching { activeStream?.close() }
        synchronized(lock) {
            lines?.close()
            lines = null
        }
    }

    private fun buildPayload(
        pointers: List<InputCommand.MotionPointer>,
        durationMs: Int,
    ): String = buildString {
        append("G ").append(durationMs).append(' ').append(pointers.size).append('\n')
        pointers.forEachIndexed { index, pointer ->
            append("P ").append(index).append(' ').append(pointer.points.size)
            pointer.points.forEach { point ->
                append(' ').append(point.x).append(' ').append(point.y).append(' ').append(point.timeMs)
            }
            append('\n')
        }
        append("E\n")
    }

    private companion object {
        const val SERVER_CLASS = "com.aixiaohuadb.inject.InjectServer"
        const val START_TIMEOUT_MS = 8_000L
        const val RESPONSE_TIMEOUT_SLACK_MS = 10_000L
        const val RETRY_COOLDOWN_MS = 30_000L
        const val MAX_PENDING_CHARS = 1_000_000
    }
}
