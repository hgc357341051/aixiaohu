package com.aixiaohuadb.adb

import android.view.Surface
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.GesturePoint
import com.aixiaohuadb.exec.GesturePointer
import com.aixiaohuadb.exec.clampDelayMs
import com.aixiaohuadb.exec.clampMultiTouchDurationMs
import com.aixiaohuadb.exec.clampSwipeDurationMs
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToLong

private val DEVICE_LINE = Regex("""add\s+device\s+\d+\s*:\s*(/\S+)""")
private val DEVICE_NAME_LINE = Regex("""name\s*:\s*"([^"]+)"""")
private val AXIS_MAX_LINE = Regex("""\b(ABS_MT_POSITION_[XY]|ABS_MT_SLOT|ABS_MT_PRESSURE|ABS_MT_TOUCH_MAJOR)\b[^\n]*?\bmax\s+(\d+)""", RegexOption.IGNORE_CASE)
private val AXIS_HEX_LINE = Regex(
    """\b(ABS_MT_POSITION_[XY])\b[^\n]*?\b([0-9a-fA-F]{4,8})\s*$""",
    RegexOption.IGNORE_CASE,
)
private val AXIS_PRESENT_LINE = Regex(
    """\b(ABS_MT_SLOT|ABS_MT_TRACKING_ID|ABS_MT_PRESSURE|ABS_MT_TOUCH_MAJOR|ABS_MT_POSITION_[XY])\b""",
    RegexOption.IGNORE_CASE,
)
private val GETEVENT_LINE = Regex(
    """^\[\s*(\d+)\.(\d+)\]\s+(?:/\S+:\s+)?(\S+)\s+(\S+)\s+([0-9a-fA-F]+)\s*$""",
)
private val TOUCH_DEVICE_PATH = Regex("^/dev/input/event\\d+$")

/**
 * 触摸设备能力快照。
 *
 * Type B（现代手机）必须同时具备 ABS_MT_SLOT 与 ABS_MT_TRACKING_ID，才能按 slot 录制/注入多指；
 * 缺少二者属于 Type A 旧协议，被动录制无法区分手指，注入侧也不应冒险猜测。
 */
internal data class RawTouchDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    val maxSlot: Int = 0,
    val hasSlot: Boolean = true,
    val hasTrackingId: Boolean = true,
    val pressureMax: Int = 0,
    val touchMajorMax: Int = 0,
) {
    val supportsMultiTouchCapture: Boolean get() = hasSlot && hasTrackingId
}

internal data class ParsedEvent(val timeMs: Long?, val type: String, val name: String, val value: Int)

/** 解析 `getevent -pl` 输出，只保留同时声明了 X/Y 两个绝对轴且上限有效的设备。 */
internal fun parseGeteventDevices(output: String): List<RawTouchDevice> {
    data class Builder(
        val maxByAxis: MutableMap<String, Int> = mutableMapOf(),
        val present: MutableSet<String> = mutableSetOf(),
    )

    val builders = LinkedHashMap<String, Builder>()
    var currentPath: String? = null
    output.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val device = DEVICE_LINE.find(line)
        if (device != null) {
            currentPath = device.groupValues[1]
            builders.putIfAbsent(device.groupValues[1], Builder())
            return@forEach
        }
        val path = currentPath ?: return@forEach
        val builder = builders.getOrPut(path) { Builder() }
        AXIS_PRESENT_LINE.findAll(line).forEach { match ->
            builder.present += match.groupValues[1].uppercase()
        }
        val axis = AXIS_MAX_LINE.find(line)?.let { match ->
            match.groupValues[1].uppercase() to match.groupValues[2].toIntOrNull()
        } ?: AXIS_HEX_LINE.find(line)?.let { match ->
            val hexMax = runCatching { java.lang.Long.parseLong(match.groupValues[2], 16).toInt() }.getOrNull()
            match.groupValues[1].uppercase() to hexMax
        } ?: return@forEach
        val max = axis.second ?: return@forEach
        if (max > 0) builder.maxByAxis[axis.first] = max
    }
    return builders.mapNotNull { (path, builder) ->
        val maxX = builder.maxByAxis["ABS_MT_POSITION_X"] ?: return@mapNotNull null
        val maxY = builder.maxByAxis["ABS_MT_POSITION_Y"] ?: return@mapNotNull null
        if (maxX <= 0 || maxY <= 0) return@mapNotNull null
        val hasSlot = "ABS_MT_SLOT" in builder.present
        RawTouchDevice(
            path = path,
            maxX = maxX,
            maxY = maxY,
            // 个别内核 dump 不打印 max；Touchscreen 的 slot 上限通常是 9，缺省按 9 处理
            maxSlot = builder.maxByAxis["ABS_MT_SLOT"]?.takeIf { it > 0 } ?: if (hasSlot) 9 else 0,
            hasSlot = hasSlot,
            hasTrackingId = "ABS_MT_TRACKING_ID" in builder.present,
            pressureMax = builder.maxByAxis["ABS_MT_PRESSURE"] ?: 0,
            touchMajorMax = builder.maxByAxis["ABS_MT_TOUCH_MAJOR"] ?: 0,
        )
    }
}

/** 从 `getevent -pl` 文本里挑出最像主触摸屏的设备路径。 */
internal fun pickTouchDevice(listing: String, devices: List<RawTouchDevice>): RawTouchDevice {
    val names = HashMap<String, String>()
    var currentPath: String? = null
    listing.lineSequence().forEach { line ->
        val device = DEVICE_LINE.find(line)
        if (device != null) {
            currentPath = device.groupValues[1]
            return@forEach
        }
        val path = currentPath ?: return@forEach
        DEVICE_NAME_LINE.find(line)?.let { names[path] = it.groupValues[1] }
    }
    val touchLike = devices.filter { device ->
        val name = names[device.path].orEmpty()
        name.contains("touch", ignoreCase = true) ||
            name.contains("ts", ignoreCase = true) ||
            device.path.contains("touch", ignoreCase = true)
    }
    return touchLike.firstOrNull { it.supportsMultiTouchCapture }
        ?: touchLike.firstOrNull()
        ?: devices.firstOrNull { it.supportsMultiTouchCapture }
        ?: devices.first()
}

/** 解析 `getevent -lt` 单行；毫秒只取到小数点后三位，避免亚毫秒抖动。 */
internal fun parseGeteventLine(line: String): ParsedEvent? {
    val match = GETEVENT_LINE.matchEntire(line.trim()) ?: return null
    val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
    val millis = match.groupValues[2].take(3).padEnd(3, '0').toIntOrNull() ?: return null
    val timeMs = (seconds * 1000.0 + millis.toDouble()).roundToLong()
    val value = runCatching { java.lang.Long.parseLong(match.groupValues[5], 16).toInt() }.getOrNull()
        ?: return null
    return ParsedEvent(timeMs, match.groupValues[3], match.groupValues[4], value)
}

/**
 * getevent 上报的是面板原生方向坐标，而宏基准坐标对应当前显示方向。
 * ROTATION_90 为设备逆时针旋转（面板原点落到显示左下），270 为顺时针。
 */
internal fun mapPanelToDisplay(
    rawX: Int,
    rawY: Int,
    maxX: Int,
    maxY: Int,
    rotation: Int,
): Pair<Int, Int> = when (rotation) {
    Surface.ROTATION_90 -> rawY to (maxX - rawX)
    Surface.ROTATION_180 -> (maxX - rawX) to (maxY - rawY)
    Surface.ROTATION_270 -> (maxY - rawY) to rawX
    else -> rawX to rawY
}

/** [mapPanelToDisplay] 的逆变换：回放时把当前显示坐标换成面板原生注入坐标。 */
internal fun mapDisplayToPanel(
    displayX: Int,
    displayY: Int,
    maxX: Int,
    maxY: Int,
    rotation: Int,
): Pair<Int, Int> = when (rotation) {
    Surface.ROTATION_90 -> (maxX - displayY) to displayX
    Surface.ROTATION_180 -> (maxX - displayX) to (maxY - displayY)
    Surface.ROTATION_270 -> displayY to (maxY - displayX)
    else -> displayX to displayY
}

/**
 * 把一次完整手势内的所有触点按时间轴累积：
 *  - 单指仍生成 Tap/Swipe，保持旧宏可读、可编辑；
 *  - 两指及以上重叠时生成一个 [ExecStep.MultiGesture]，每根手指独立保留轨迹。
 * X/Y 可先于 TRACKING_ID 到达，因此每个 slot 的坐标先缓存再在 SYN 帧提交。
 * 所有可变状态由 [lock] 保护，stop() 可与读取线程并发。
 */
internal class TouchAccumulator(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val maxX: Int,
    private val maxY: Int,
    private val mapper: CoordinateMapper = CoordinateMapper(),
    private val rotation: Int = Surface.ROTATION_0,
    private val devicePath: String = "",
) {
    private val lock = Any()
    private val steps = mutableListOf<ExecStep>()
    private val active = LinkedHashMap<Int, ActivePointer>()
    private val gesturePointers = mutableListOf<ActivePointer>()
    private val slotPositions = HashMap<Int, Pair<Int, Int>>()

    private var currentSlot = 0
    private var gestureStartMs: Long? = null
    private var previousEndMs: Long? = null
    private var lastEventMs: Long = 0L

    fun accept(ev: ParsedEvent): ExecStep? = synchronized(lock) {
        when (ev.name) {
            "ABS_MT_SLOT" -> {
                if (ev.value >= 0) currentSlot = ev.value
                null
            }

            "ABS_MT_TRACKING_ID" -> when {
                ev.value >= 0 -> onDown(currentSlot, ev.value, ev.timeMs)
                else -> onUp(currentSlot, ev.timeMs)
            }

            "ABS_MT_POSITION_X" -> {
                val (slotX, slotY) = slotPositions[currentSlot] ?: (0 to 0)
                val x = ev.value.coerceIn(0, maxX)
                slotPositions[currentSlot] = x to slotY
                active[currentSlot]?.let { pointer ->
                    pointer.lastRawX = x
                    ev.timeMs?.let {
                        pointer.lastMs = it
                        lastEventMs = maxOf(lastEventMs, it)
                    }
                }
                null
            }

            "ABS_MT_POSITION_Y" -> {
                val (slotX, slotY) = slotPositions[currentSlot] ?: (0 to 0)
                val y = ev.value.coerceIn(0, maxY)
                slotPositions[currentSlot] = slotX to y
                active[currentSlot]?.let { pointer ->
                    pointer.lastRawY = y
                    ev.timeMs?.let {
                        pointer.lastMs = it
                        lastEventMs = maxOf(lastEventMs, it)
                    }
                }
                null
            }

            "SYN_REPORT" -> {
                val frameMs = ev.timeMs ?: lastEventMs
                gesturePointers.forEach { pointer ->
                    if (active.containsKey(pointer.slot)) commitSample(pointer, frameMs)
                }
                null
            }

            else -> null
        }
    }

    /** 被动录制被中止时，把仍在进行的触摸按最后一次已知时间收尾。 */
    fun finish(): ExecStep? = synchronized(lock) {
        if (active.isEmpty()) return@synchronized null
        val endMs = active.values.maxOf { it.lastMs }
        active.values.forEach { pointer ->
            commitSample(pointer, endMs, force = true)
            pointer.endMs = endMs
        }
        active.clear()
        finalizeGesture(endMs)
    }

    fun steps(): List<ExecStep> = synchronized(lock) { steps.toList() }

    private fun onDown(slot: Int, trackingId: Int, timeMs: Long?): ExecStep? {
        if (active.containsKey(slot) || active.size >= MAX_POINTERS) return null
        val downMs = timeMs ?: lastEventMs
        if (gesturePointers.isEmpty()) {
            gestureStartMs = downMs
            // delayAfterMs 的语义是“本步派发完成后等多久”，
            // 因此两次手势之间的空档必须回写到上一步，而不是挂到即将录制的新步骤上。
            val gap = previousEndMs?.let { (downMs - it).coerceAtLeast(0L).clampDelayMs() } ?: 0L
            applyDelayToLastStep(gap)
        }
        val (seedX, seedY) = slotPositions[slot] ?: (0 to 0)
        val pointer = ActivePointer(slot, downMs, seedX, seedY)
        active[slot] = pointer
        gesturePointers += pointer
        lastEventMs = maxOf(lastEventMs, downMs)
        return null
    }

    /** 调用方需持有 [lock]。 */
    private fun applyDelayToLastStep(delayMs: Long) {
        if (delayMs <= 0L) return
        val index = steps.lastIndex
        if (index < 0) return
        steps[index] = when (val last = steps[index]) {
            is ExecStep.Tap -> last.copy(delayAfterMs = delayMs)
            is ExecStep.Swipe -> last.copy(delayAfterMs = delayMs)
            is ExecStep.MultiGesture -> last.copy(delayAfterMs = delayMs)
            else -> last
        }
    }

    private fun onUp(slot: Int, timeMs: Long?): ExecStep? {
        val pointer = active[slot] ?: return null
        val upMs = timeMs ?: pointer.lastMs
        commitSample(pointer, upMs, force = true)
        pointer.endMs = upMs
        active.remove(slot)
        lastEventMs = maxOf(lastEventMs, upMs)
        if (active.isEmpty()) return finalizeGesture(upMs)
        return null
    }

    /**
     * 调用方需持有 [lock]。
     * [force] 用于 down/up 边界：即使坐标没变也必须落一个点，
     * 否则“按住不动”的手指在多指轨迹里只剩起点，回放时会提前抬起。
     */
    private fun commitSample(pointer: ActivePointer, frameMs: Long, force: Boolean = false) {
        if (pointer.samples.size >= MAX_POINTS_PER_POINTER) return
        val sampleTime = if (force) frameMs else pointer.lastMs.coerceAtMost(frameMs)
        if (force ||
            pointer.samples.isEmpty() ||
            pointer.committedX != pointer.lastRawX ||
            pointer.committedY != pointer.lastRawY
        ) {
            pointer.samples += Triple(pointer.lastRawX, pointer.lastRawY, sampleTime)
            pointer.committedX = pointer.lastRawX
            pointer.committedY = pointer.lastRawY
        }
    }

    /** 调用方需持有 [lock]。 */
    private fun finalizeGesture(endMs: Long): ExecStep? {
        val sources = gesturePointers.toList()
        gesturePointers.clear()
        if (sources.isEmpty()) return null

        val startMs = gestureStartMs ?: sources.first().startMs
        val rawDuration = (endMs - startMs).coerceAtLeast(0L)
        val duration = rawDuration.toInt().clampMultiTouchDurationMs()
        val mapped = sources.map { pointer ->
            val samples = pointer.samples.ifEmpty {
                listOf(Triple(pointer.initialX, pointer.initialY, pointer.startMs))
            }
            GesturePointer(
                samples.map { (rawX, rawY, atMs) ->
                    val (baseX, baseY) = mapToBase(rawX, rawY)
                    GesturePoint(
                        x = baseX,
                        y = baseY,
                        timeMs = (atMs - startMs).coerceIn(0L, duration.toLong()),
                    )
                },
            )
        }

        val step = if (mapped.size == 1) {
            val points = mapped.first().points
            val first = points.first()
            val last = points.last()
            val displacement = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble())
            if (displacement < 16.0) {
                ExecStep.Tap(first.x, first.y)
            } else {
                ExecStep.Swipe(
                    fromX = first.x,
                    fromY = first.y,
                    toX = last.x,
                    toY = last.y,
                    // 用整段按下→抬起的时长，而不是首末采样点之差；
                    // 否则首个 X/Y 晚于 down 的 10ms 会让每次滑动都短一截。
                    durationMs = rawDuration.toInt().clampSwipeDurationMs(),
                )
            }
        } else {
            ExecStep.MultiGesture(
                pointers = mapped,
                durationMs = duration,
                devicePath = devicePath,
                panelMaxX = maxX,
                panelMaxY = maxY,
                rotation = rotation,
            )
        }
        steps += step
        previousEndMs = endMs
        gestureStartMs = null
        return step
    }

    private fun mapToBase(rawX: Int, rawY: Int): Pair<Int, Int> {
        val safeWidth = screenWidth.coerceAtLeast(1)
        val safeHeight = screenHeight.coerceAtLeast(1)
        val (displayX, displayY) = mapPanelToDisplay(
            rawX.coerceIn(0, maxX), rawY.coerceIn(0, maxY), maxX, maxY, rotation,
        )
        return mapper.mapToBase(displayX, displayY, safeWidth, safeHeight)
    }

    private class ActivePointer(
        val slot: Int,
        val startMs: Long,
        val initialX: Int,
        val initialY: Int,
    ) {
        var lastRawX: Int = initialX
        var lastRawY: Int = initialY
        var committedX: Int = initialX
        var committedY: Int = initialY
        var lastMs: Long = startMs
        var endMs: Long = startMs
        val samples = mutableListOf<Triple<Int, Int, Long>>()
    }

    companion object {
        private const val MAX_POINTERS = 10
        private const val MAX_POINTS_PER_POINTER = 256
    }
}

/** 被动宏录制：通过已有 ADB 长连接观察内核 `getevent` 事件，不注入、不拦截触摸。 */
class AdbTouchRecorder(
    private val adbClient: AdbClient,
    private val scope: CoroutineScope,
    private val mapper: CoordinateMapper = CoordinateMapper(),
) {
    @Volatile
    private var recording = false
    @Volatile
    private var starting = false
    @Volatile
    private var stream: AdbShellStream? = null
    private var job: Job? = null
    private var accumulator: TouchAccumulator? = null
    private val stateLock = Any()

    fun start(onUpdate: (List<ExecStep>) -> Unit, onError: (String) -> Unit): Boolean {
        val busy = synchronized(stateLock) {
            if (recording || starting) true else {
                starting = true
                false
            }
        }
        if (busy) return false

        fun fail(message: String): Boolean {
            synchronized(stateLock) { starting = false }
            runCatching { onError(message) }
            return false
        }

        val (screenWidth, screenHeight) = runCatching { adbClient.screenSizePx() }
            .getOrElse { return fail("读取屏幕尺寸失败：${it.message ?: "未知错误"}") }
        if (screenWidth <= 0 || screenHeight <= 0) return fail("屏幕尺寸无效，无法录制")
        val rotation = runCatching { adbClient.displayRotation() }.getOrDefault(Surface.ROTATION_0)

        val listing = runCatching { adbClient.readShellOutput("getevent -pl") }.getOrNull()
            ?: return fail("未找到可监听的触摸设备（getevent 不可用）")
        val devices = runCatching { parseGeteventDevices(listing) }.getOrDefault(emptyList())
        if (devices.isEmpty()) return fail("未找到可监听的触摸设备（getevent 不可用）")
        val device = pickTouchDevice(listing, devices)
        if (!TOUCH_DEVICE_PATH.matches(device.path)) {
            return fail("触摸设备路径异常：${device.path}")
        }
        if (!device.supportsMultiTouchCapture) {
            return fail("该触摸设备使用旧版 Type A 协议（缺少 ABS_MT_SLOT/TRACKING_ID），无法被动录制；请改用截图标注")
        }

        val opened = runCatching { adbClient.openShellStream("getevent -lt ${device.path}") }.getOrNull()
            ?: return fail("无法监听触摸设备（getevent 权限不足或不支持）")

        // 局部 acc 独立于可变字段：stop() 清空字段后迟到回调也不会读到 null/空列表
        val acc = TouchAccumulator(
            screenWidth, screenHeight, device.maxX, device.maxY, mapper, rotation, device.path,
        )
        val conflict = synchronized(stateLock) {
            if (recording) {
                true
            } else {
                accumulator = acc
                stream = opened
                recording = true
                starting = false
                false
            }
        }
        if (conflict) {
            synchronized(stateLock) { starting = false }
            runCatching { opened.close() }
            return false
        }

        job = scope.launch(Dispatchers.IO) {
            val pending = StringBuilder()
            var errorReported = false

            fun report(message: String) {
                if (errorReported) return
                errorReported = true
                runCatching { onError(message) }
            }

            try {
                val activeStream = stream ?: return@launch
                activeStream.use { s ->
                    while (recording && isActive) {
                        when (val packet = s.read()) {
                            is AdbShellPacket.Exit -> {
                                if (recording) report("getevent 监听已结束，改用截图标注录制")
                                break
                            }

                            is AdbShellPacket.StdError -> {
                                val err = String(packet.payload, Charsets.UTF_8).trim()
                                if (recording && err.isNotBlank()) report("getevent 错误：${err.take(200)}")
                            }

                            is AdbShellPacket.StdOut -> {
                                pending.append(String(packet.payload, Charsets.UTF_8))
                                if (pending.length > 1_000_000) pending.clear()
                                while (true) {
                                    val newline = pending.indexOf('\n')
                                    if (newline < 0) break
                                    val line = pending.substring(0, newline).trim()
                                    pending.delete(0, newline + 1)
                                    val event = parseGeteventLine(line) ?: continue
                                    val emitted = acc.accept(event)
                                    if (recording && isActive && emitted != null) {
                                        val steps = acc.steps()
                                        if (steps.isNotEmpty()) runCatching { onUpdate(steps) }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                if (recording && t !is CancellationException) {
                    report("getevent 监听失败：${t.message ?: "未知错误"}")
                }
            } finally {
                synchronized(stateLock) {
                    if (recording) {
                        recording = false
                        stream = null
                        job = null
                    }
                }
            }
        }
        return true
    }

    /** 幂等：可从任意线程调用；返回当前已累积的全部步骤。 */
    fun stop(): List<ExecStep> {
        val (runningJob, activeStream, acc) = synchronized(stateLock) {
            recording = false
            starting = false
            val snapshot = Triple(job, stream, accumulator)
            job = null
            stream = null
            accumulator = null
            snapshot
        }
        runningJob?.cancel()
        activeStream?.let { runCatching { it.close() } }
        if (acc == null) return emptyList()
        acc.finish()
        return acc.steps()
    }

    fun isRecording(): Boolean = recording
}
