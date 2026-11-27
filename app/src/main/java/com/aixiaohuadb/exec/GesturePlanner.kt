package com.aixiaohuadb.exec

/**
 * 无障碍手势的最终计划：一帧 [GestureDescription] 中，每根手指对应且仅对应一个 Stroke。
 *
 * [startMs] 是相对整套手势开始的绝对时间，因此多指可以错峰按下；
 * [durationMs] 只覆盖该指自身，不同手指互不影响。
 */
data class GestureStroke(
    val points: List<GesturePoint>,
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * 把录制得到的多指轨迹折成 Android 无障碍手势可直接消费的 Stroke 列表。
 *
 * 旧实现把同一根手指拆成多个 `continueStroke` 段再塞进同一个 GestureDescription，
 * 既重复派发同一根手指，也丢失第二根手指的错峰起始时间。这里统一为：
 * 一根手指 = 一条完整 Path + 一个起始时间。
 *
 * 纯 Kotlin，不依赖 Android，可被 JVM 单测覆盖。
 */
object GesturePlanner {
    /** GestureDescription.getMaxStrokeCount() 在 API 29 上为 10，作为硬上限。 */
    const val MAX_STROKES: Int = 10

    /** GestureDescription.getMaxGestureDuration() 为 60s。 */
    const val MAX_DURATION_MS: Long = 60_000L

    /** 单点按压时长：太短会被部分 ROM 判为无效手势。 */
    const val TAP_DURATION_MS: Long = 60L

    /** 有位移的轨迹最短时长，保证 StrokeDescription 的 duration > 0。 */
    private const val MIN_MOVE_DURATION_MS: Long = 16L

    /**
     * @param pointers 每根手指的采样点，坐标已是当前屏幕物理像素，timeMs 相对手势开始。
     * @param declaredDurationMs 宏步骤声明的总时长；仅用于上限收敛，不改变各指相对时序。
     * @return null 表示轨迹非法（空、超过 10 指、时间越界无法收敛）。
     */
    fun plan(
        pointers: List<GesturePointer>,
        declaredDurationMs: Int = 0,
    ): List<GestureStroke>? {
        if (pointers.isEmpty() || pointers.size > MAX_STROKES) return null

        val declared = declaredDurationMs.toLong().coerceIn(0L, MAX_DURATION_MS)
        val lastPointMs = pointers.maxOfOrNull { pointer ->
            pointer.points.maxOfOrNull { it.timeMs } ?: 0L
        } ?: 0L
        // 录制数据可能比步骤声明更长（例如 stop 时刚冲刷完），取二者较大值；
        // 收敛到 59999 保证每根手指至少能分到 1ms，不产生 start+duration > 60s 的非法 Stroke。
        val limit = maxOf(declared, lastPointMs.coerceIn(0L, MAX_DURATION_MS))
            .coerceAtMost(MAX_DURATION_MS - 1L)

        val strokes = pointers.map { pointer ->
            val points = pointer.points
                .sortedBy { it.timeMs }
                .map { point ->
                    GesturePoint(
                        x = point.x,
                        y = point.y,
                        timeMs = point.timeMs.coerceIn(0L, limit),
                    )
                }
            if (points.isEmpty()) return null

            val firstMs = points.first().timeMs
            if (points.size == 1) {
                val start = firstMs.coerceAtMost(MAX_DURATION_MS - TAP_DURATION_MS)
                GestureStroke(points, start, TAP_DURATION_MS)
            } else {
                val rawDuration = (points.last().timeMs - firstMs).coerceAtLeast(0L)
                val duration = rawDuration
                    .coerceAtLeast(MIN_MOVE_DURATION_MS)
                    .coerceAtMost(MAX_DURATION_MS - firstMs)
                if (duration <= 0L) return null
                GestureStroke(points, firstMs, duration)
            }
        }
        return strokes
    }
}
