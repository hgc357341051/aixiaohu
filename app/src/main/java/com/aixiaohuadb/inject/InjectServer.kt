package com.aixiaohuadb.inject

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.reflect.Method

/**
 * 通过 `app_process` 以 shell 身份运行的多指注入服务（scrcpy 同款路径）。
 *
 * 与 `sendevent` 的本质区别：
 *  - 不直接写 /dev/input，不需要 root；
 *  - 走 `IInputManager.injectInputEvent`，shell 自带 INJECT_EVENTS 权限；
 *  - 坐标就是当前显示坐标，不需要面板原生坐标/旋转反算。
 *
 * 协议（LF 分隔，stdin/stdout）：
 * ```
 * G <durationMs> <pointerCount>
 * P <id> <sampleCount> <x> <y> <t> <x> <y> <t> ...
 * ...
 * E
 * ```
 * 每条手势回 `OK <frames>` 或 `ERR <reason>`；启动成功先输出 `READY`。
 */
@Keep
object InjectServer {
    private const val MAX_POINTERS = 10
    private const val MAX_SAMPLES = 2_000
    private const val MAX_DURATION_MS = 60_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)), true)
        val injector = runCatching { BinderInjector() }.getOrElse { error ->
            out.println("ERR init ${error.message ?: error.javaClass.simpleName}")
            return
        }
        out.println("READY")
        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = reader.readLine()?.trim() ?: break
            when {
                line.isEmpty() -> Unit
                line == "PING" -> out.println("PONG")
                line == "QUIT" -> return
                line.startsWith("G ") -> {
                    val result = runCatching { playGesture(reader, line, injector) }
                    if (result.isFailure) {
                        // 解析/注入失败后 stdin 可能残留未消费的 P 行，直接退出由客户端重启，避免协议错位
                        out.println("ERR ${result.exceptionOrNull()?.message ?: "bad gesture"}")
                        return
                    }
                    out.println("OK ${result.getOrDefault(0)}")
                }
                else -> out.println("ERR unknown command")
            }
        }
    }

    private fun playGesture(reader: java.io.BufferedReader, header: String, injector: BinderInjector): Int {
        val head = header.split(' ')
        require(head.size == 3) { "bad header" }
        val durationMs = head[1].toLong().coerceIn(0L, MAX_DURATION_MS)
        val pointerCount = head[2].toInt()
        require(pointerCount in 1..MAX_POINTERS) { "bad pointer count" }

        val tracks = ArrayList<PlaybackTrack>(pointerCount)
        repeat(pointerCount) {
            val line = reader.readLine()?.trim() ?: throw IllegalArgumentException("unexpected end")
            val parts = line.split(' ')
            require(parts.size >= 4 && parts[0] == "P") { "bad pointer line" }
            val id = parts[1].toInt()
            val sampleCount = parts[2].toInt()
            require(sampleCount in 1..MAX_SAMPLES) { "bad sample count" }
            require(parts.size == 3 + sampleCount * 3) { "bad sample payload" }
            val samples = ArrayList<PlaybackSample>(sampleCount)
            var index = 3
            repeat(sampleCount) {
                val x = parts[index].toFloat()
                val y = parts[index + 1].toFloat()
                val t = parts[index + 2].toLong()
                require(x.isFinite() && y.isFinite()) { "bad coordinate" }
                samples += PlaybackSample(x, y, t.coerceIn(0L, durationMs))
                index += 3
            }
            tracks += PlaybackTrack(id, samples)
        }
        val end = reader.readLine()?.trim()
        require(end == "E") { "missing end marker" }

        val frames = TouchPlaybackPlanner.plan(tracks, durationMs.toInt())
            ?: throw IllegalArgumentException("invalid tracks")

        val base = SystemClock.uptimeMillis()
        var lastPointers: List<PlaybackPointer> = emptyList()
        try {
            frames.forEach { frame ->
                val target = base + frame.timeMs
                val wait = target - SystemClock.uptimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastPointers = frame.pointers
                if (!injector.inject(buildEvent(base, frame))) {
                    throw IllegalStateException("inject rejected at ${frame.timeMs}ms")
                }
            }
        } catch (t: Throwable) {
            // 中途失败必须取消，避免手指停在按下状态
            runCatching { injector.inject(cancelEvent(base, lastPointers)) }
            throw t
        }
        return frames.size
    }

    private fun buildEvent(downTime: Long, frame: PlaybackFrame): MotionEvent {
        val count = frame.pointers.size
        val props = Array(count) { index ->
            MotionEvent.PointerProperties().apply {
                id = frame.pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { index ->
            MotionEvent.PointerCoords().apply {
                x = frame.pointers[index].x
                y = frame.pointers[index].y
                pressure = 1f
                size = 1f
            }
        }
        val index = frame.pointers.indexOfFirst { it.id == frame.actionPointerId }
        val action = when (frame.action) {
            PlaybackAction.DOWN -> MotionEvent.ACTION_DOWN
            PlaybackAction.MOVE -> MotionEvent.ACTION_MOVE
            PlaybackAction.UP -> MotionEvent.ACTION_UP
            PlaybackAction.POINTER_DOWN ->
                MotionEvent.ACTION_POINTER_DOWN or (index.coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PlaybackAction.POINTER_UP ->
                MotionEvent.ACTION_POINTER_UP or (index.coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            count,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun cancelEvent(downTime: Long, pointers: List<PlaybackPointer>): MotionEvent {
        val count = pointers.size.coerceAtLeast(1)
        val safe = pointers.ifEmpty { listOf(PlaybackPointer(0, 0f, 0f)) }
        val props = Array(count) { index ->
            MotionEvent.PointerProperties().apply {
                id = safe.getOrElse(index) { safe.last() }.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { index ->
            val pointer = safe.getOrElse(index) { safe.last() }
            MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            count,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    /** 直接拿 IInputManager 的 Binder，不依赖 ActivityThread/Context（app_process 里都没有）。 */
    @Keep
    private class BinderInjector {
        private val manager: Any
        private val injectMethod: Method

        init {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "input")
                ?: throw IllegalStateException("input service unavailable")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            manager = asInterface.invoke(null, binder)
                ?: throw IllegalStateException("IInputManager unavailable")
            injectMethod = manager.javaClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Integer.TYPE,
            )
        }

        fun inject(event: MotionEvent): Boolean {
            val result = try {
                injectMethod.invoke(manager, event, 0)
            } catch (invocation: java.lang.reflect.InvocationTargetException) {
                // 还原 SecurityException 等真实原因，方便客户端给出可操作提示
                throw invocation.targetException
            } finally {
                event.recycle()
            }
            return result as? Boolean ?: false
        }
    }
}
