package com.aixiaohuadb.inject

/**
 * app_process 注入服务与 JVM 单测共用的多指回放计划。
 *
 * Android 的 MotionEvent 不允许“一帧里同时新增两根手指”：
 * 第一根必须是 ACTION_DOWN，后续每根必须各发一次 ACTION_POINTER_DOWN；
 * 抬起同理。这里把任意轨迹折成合法的 action 序列，服务端只负责按帧注入。
 */
data class PlaybackSample(val x: Float, val y: Float, val timeMs: Long)

data class PlaybackTrack(val id: Int, val samples: List<PlaybackSample>)

data class PlaybackPointer(val id: Int, val x: Float, val y: Float)

enum class PlaybackAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP }

data class PlaybackFrame(
    val timeMs: Long,
    val action: PlaybackAction,
    val pointers: List<PlaybackPointer>,
    /** POINTER_DOWN / POINTER_UP 时指向 [pointers] 中发生变化的那根手指。 */
    val actionPointerId: Int = -1,
)

object TouchPlaybackPlanner {
    const val MAX_POINTERS: Int = 10
    const val MAX_DURATION_MS: Long = 60_000L

    /**
     * @return 合法 MotionEvent 序列；空轨迹/超过 10 指/id 重复/时间非法时返回 null。
     */
    fun plan(tracks: List<PlaybackTrack>, durationMs: Int): List<PlaybackFrame>? {
        if (tracks.isEmpty() || tracks.size > MAX_POINTERS) return null
        if (tracks.map { it.id }.toSet().size != tracks.size) return null

        val limit = durationMs.toLong().coerceIn(0L, MAX_DURATION_MS)
        data class Normalized(val track: PlaybackTrack, val samples: List<PlaybackSample>)

        val normalized = tracks.map { track ->
            val samples = track.samples
                .sortedBy { it.timeMs }
                .map { PlaybackSample(it.x, it.y, it.timeMs.coerceIn(0L, limit)) }
            if (samples.isEmpty()) return null
            Normalized(track, samples)
        }
        val byId = normalized.associateBy { it.track.id }

        val frameTimes = sortedSetOf(0L, limit)
        normalized.forEach { n -> n.samples.forEach { frameTimes += it.timeMs } }

        val active = LinkedHashMap<Int, PlaybackPointer>()
        val frames = mutableListOf<PlaybackFrame>()
        val ended = mutableSetOf<Int>()

        frameTimes.forEach { timeMs ->
            val starts = normalized.filter { it.samples.first().timeMs == timeMs }
                .sortedBy { it.track.id }
            val existingBefore = LinkedHashMap(active)

            // 1. 已在场的手指推进到 t 时刻的采样位置；已经抬起的不再复活。
            active.keys.toList().forEach { id ->
                if (id in ended) return@forEach
                val sample = byId[id]?.samples?.lastOrNull { it.timeMs == timeMs } ?: return@forEach
                active[id] = PlaybackPointer(id, sample.x, sample.y)
            }

            // 2. 新增手指：每根一个 DOWN / POINTER_DOWN，禁止一帧同时新增两根。
            starts.forEach { n ->
                val sample = n.samples.first()
                active[n.track.id] = PlaybackPointer(n.track.id, sample.x, sample.y)
                val action = if (active.size == 1) PlaybackAction.DOWN else PlaybackAction.POINTER_DOWN
                frames += PlaybackFrame(
                    timeMs = timeMs,
                    action = action,
                    pointers = active.values.toList(),
                    actionPointerId = if (action == PlaybackAction.POINTER_DOWN) n.track.id else -1,
                )
            }

            // 3. 已存在的手指移动：一帧 MOVE 带上全部活动手指。
            val moved = active.any { (id, pointer) ->
                val before = existingBefore[id]
                before != null && (before.x != pointer.x || before.y != pointer.y)
            }
            if (moved && existingBefore.isNotEmpty()) {
                frames += PlaybackFrame(timeMs, PlaybackAction.MOVE, active.values.toList())
            }

            // 4. 最后一根手指抬起：POINTER_UP / UP，逐个发，避免多指同时消失。
            val ending = normalized.filter { it.samples.last().timeMs == timeMs }
                .sortedBy { it.track.id }
            ending.forEach { n ->
                if (n.track.id in ended) return@forEach
                val pointer = active[n.track.id] ?: return@forEach
                active[n.track.id] = pointer
                val action = if (active.size == 1) PlaybackAction.UP else PlaybackAction.POINTER_UP
                frames += PlaybackFrame(
                    timeMs = timeMs,
                    action = action,
                    pointers = active.values.toList(),
                    actionPointerId = if (action == PlaybackAction.POINTER_UP) n.track.id else -1,
                )
                active.remove(n.track.id)
                ended += n.track.id
            }
        }
        return frames
    }
}
