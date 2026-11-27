package com.aixiaohuadb.adb

import java.util.Locale

/**
 * 把强类型多指命令展开为固定 `sendevent` 序列（Linux Type B 多点协议）。
 *
 * 设计约束：
 *  - 只用数字字面量拼参数，设备路径来自 [AdbClient] 的 `getevent -pl` 探测结果，不接收用户字符串；
 *  - 同一帧内的多指状态在一次 SYN_REPORT 内提交，保证是“同时按下”而不是串行点击；
 *  - 每根手指在自身最后一个采样点抬起，不因其它手指继续移动而提前释放；
 *  - 采样按 24ms 降采样并限制单指帧数，避免长手势展开出上万条 sendevent 拖垮 shell；
 *  - 设备不具备 ABS_MT_SLOT/TRACKING_ID（Type A 旧协议）时直接拒绝，不猜测注入格式。
 */
internal object AdbMultiTouchScript {
    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3

    private const val SYN_REPORT = 0
    private const val BTN_TOUCH = 330
    private const val BTN_TOOL_FINGER = 325

    private const val ABS_MT_SLOT = 47
    private const val ABS_MT_TRACKING_ID = 57
    private const val ABS_MT_POSITION_X = 53
    private const val ABS_MT_POSITION_Y = 54
    private const val ABS_MT_PRESSURE = 58
    private const val ABS_MT_TOUCH_MAJOR = 48

    private const val MIN_FRAME_INTERVAL_MS = 24L
    private const val MAX_POINTS_PER_POINTER = 200
    private const val MAX_TOTAL_FRAMES = 600

    fun build(
        device: RawTouchDevice,
        pointers: List<InputCommand.MotionPointer>,
        durationMs: Int,
    ): String {
        require(device.hasSlot && device.hasTrackingId) {
            "触摸设备不支持 Type B 多点协议（缺少 ABS_MT_SLOT/TRACKING_ID）"
        }
        require(pointers.isNotEmpty()) { "多指轨迹为空" }
        require(pointers.size <= device.maxSlot + 1) {
            "触摸设备最多支持 ${device.maxSlot + 1} 指，当前 ${pointers.size} 指"
        }

        val script = StringBuilder()
        fun emit(type: Int, code: Int, value: Int) {
            script.append("sendevent ").append(device.path).append(' ')
                .append(type).append(' ').append(code).append(' ').append(value).append('\n')
        }
        fun syn() = emit(EV_SYN, SYN_REPORT, 0)
        fun sleep(deltaMs: Long) {
            if (deltaMs <= 0L) return
            script.append("sleep ")
                .append(String.format(Locale.US, "%.3f", deltaMs / 1000.0))
                .append('\n')
        }

        val endMs = durationMs.coerceAtLeast(0).toLong()
        val states = pointers.mapIndexed { slot, pointer ->
            State(
                slot = slot,
                points = resample(pointer.points, endMs),
                pressure = (device.pressureMax / 2).coerceAtLeast(1),
                touchMajor = (device.touchMajorMax / 4).coerceAtLeast(1),
            )
        }
        if (states.any { it.points.isEmpty() }) {
            throw IllegalArgumentException("多指轨迹为空")
        }

        val frameSet = sortedSetOf(0L, endMs)
        states.forEach { state -> state.points.forEach { frameSet += it.timeMs } }
        val frames = capFrames(frameSet.toList(), states)

        var buttonsDown = false
        frames.forEachIndexed { index, timeMs ->
            var changed = false
            states.forEach { state ->
                if (state.released) return@forEach
                val point = state.lastPointAt(timeMs) ?: return@forEach
                if (!state.started) {
                    if (!buttonsDown) {
                        emit(EV_KEY, BTN_TOUCH, 1)
                        emit(EV_KEY, BTN_TOOL_FINGER, 1)
                        buttonsDown = true
                    }
                    emit(EV_ABS, ABS_MT_SLOT, state.slot)
                    emit(EV_ABS, ABS_MT_TRACKING_ID, state.slot + 1)
                    emit(EV_ABS, ABS_MT_POSITION_X, point.x)
                    emit(EV_ABS, ABS_MT_POSITION_Y, point.y)
                    if (device.pressureMax > 0) emit(EV_ABS, ABS_MT_PRESSURE, state.pressure)
                    if (device.touchMajorMax > 0) emit(EV_ABS, ABS_MT_TOUCH_MAJOR, state.touchMajor)
                    state.started = true
                    state.lastX = point.x
                    state.lastY = point.y
                    changed = true
                } else {
                    val xChanged = point.x != state.lastX
                    val yChanged = point.y != state.lastY
                    if (xChanged || yChanged) {
                        emit(EV_ABS, ABS_MT_SLOT, state.slot)
                        if (xChanged) emit(EV_ABS, ABS_MT_POSITION_X, point.x)
                        if (yChanged) emit(EV_ABS, ABS_MT_POSITION_Y, point.y)
                        if (device.pressureMax > 0) emit(EV_ABS, ABS_MT_PRESSURE, state.pressure)
                        state.lastX = point.x
                        state.lastY = point.y
                        changed = true
                    }
                }
                if (state.started && timeMs >= state.lastTimeMs) {
                    emit(EV_ABS, ABS_MT_SLOT, state.slot)
                    emit(EV_ABS, ABS_MT_TRACKING_ID, -1)
                    state.released = true
                    changed = true
                }
            }
            if (changed) syn()
            frames.getOrNull(index + 1)?.let { next -> sleep(next - timeMs) }
        }

        var changed = false
        states.forEach { state ->
            if (state.started && !state.released) {
                emit(EV_ABS, ABS_MT_SLOT, state.slot)
                emit(EV_ABS, ABS_MT_TRACKING_ID, -1)
                state.released = true
                changed = true
            }
        }
        if (changed) syn()
        if (buttonsDown) {
            emit(EV_KEY, BTN_TOUCH, 0)
            emit(EV_KEY, BTN_TOOL_FINGER, 0)
            syn()
        }
        return script.toString()
    }

    /** 按最小帧间隔降采样，再按上限均匀抽稀；首尾采样必须保留。 */
    private fun resample(points: List<InputCommand.MotionPoint>, endMs: Long): List<InputCommand.MotionPoint> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.timeMs }
            .map { InputCommand.MotionPoint(it.x, it.y, it.timeMs.coerceIn(0L, endMs)) }

        val filtered = ArrayList<InputCommand.MotionPoint>(minOf(sorted.size, MAX_POINTS_PER_POINTER))
        var lastKeptMs: Long? = null
        sorted.forEachIndexed { index, point ->
            val keep = index == 0 ||
                index == sorted.lastIndex ||
                lastKeptMs == null ||
                point.timeMs - lastKeptMs >= MIN_FRAME_INTERVAL_MS
            if (keep) {
                filtered += point
                lastKeptMs = point.timeMs
            }
        }
        if (filtered.size <= MAX_POINTS_PER_POINTER) return filtered

        val reduced = ArrayList<InputCommand.MotionPoint>(MAX_POINTS_PER_POINTER)
        for (i in 0 until MAX_POINTS_PER_POINTER) {
            val index = (i.toLong() * (filtered.size - 1) / (MAX_POINTS_PER_POINTER - 1)).toInt()
            val point = filtered[index]
            if (reduced.lastOrNull() != point) reduced += point
        }
        if (reduced.last() != filtered.last()) reduced += filtered.last()
        return reduced
    }

    /** 总帧数上限：均匀抽稀但保留每根手指的起止帧，避免释放被无限推迟。 */
    private fun capFrames(frames: List<Long>, states: List<State>): List<Long> {
        if (frames.size <= MAX_TOTAL_FRAMES) return frames
        val reduced = sortedSetOf<Long>()
        reduced += frames.first()
        reduced += frames.last()
        states.forEach { state ->
            state.points.firstOrNull()?.let { reduced += it.timeMs }
            state.points.lastOrNull()?.let { reduced += it.timeMs }
        }
        for (i in 0 until MAX_TOTAL_FRAMES) {
            val index = (i.toLong() * (frames.size - 1) / (MAX_TOTAL_FRAMES - 1)).toInt()
            reduced += frames[index]
        }
        return reduced.toList()
    }

    private class State(
        val slot: Int,
        val points: List<InputCommand.MotionPoint>,
        val pressure: Int,
        val touchMajor: Int,
    ) {
        var started: Boolean = false
        var released: Boolean = false
        var lastX: Int = -1
        var lastY: Int = -1

        val lastTimeMs: Long get() = points.lastOrNull()?.timeMs ?: 0L

        fun lastPointAt(timeMs: Long): InputCommand.MotionPoint? =
            points.lastOrNull { it.timeMs <= timeMs }
    }
}
