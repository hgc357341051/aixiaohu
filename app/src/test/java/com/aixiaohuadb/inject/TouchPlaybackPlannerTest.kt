package com.aixiaohuadb.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPlaybackPlannerTest {
    private fun track(id: Int, vararg points: Triple<Float, Float, Long>) =
        PlaybackTrack(id, points.map { PlaybackSample(it.first, it.second, it.third) })

    @Test
    fun `单指点击生成 DOWN 与 UP`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(track(0, Triple(100f, 200f, 0L), Triple(100f, 200f, 80L))),
            durationMs = 100,
        )!!

        assertEquals(listOf(PlaybackAction.DOWN, PlaybackAction.UP), frames.map { it.action })
        assertTrue(frames.all { it.pointers.size == 1 })
        assertEquals(0L, frames.first().timeMs)
        assertEquals(80L, frames.last().timeMs)
    }

    @Test
    fun `双指同按先 DOWN 再 POINTER_DOWN`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(
                track(0, Triple(100f, 200f, 0L), Triple(100f, 200f, 100L)),
                track(1, Triple(500f, 600f, 0L), Triple(500f, 600f, 100L)),
            ),
            durationMs = 120,
        )!!

        assertEquals(
            listOf(
                PlaybackAction.DOWN,
                PlaybackAction.POINTER_DOWN,
                PlaybackAction.POINTER_UP,
                PlaybackAction.UP,
            ),
            frames.map { it.action },
        )
        assertEquals(listOf(1), frames.filter { it.action == PlaybackAction.POINTER_DOWN }.map { it.actionPointerId })
        // 同时抬起时 Android 必须逐指 POINTER_UP；先抬 id=0，最后一根才是 UP
        assertEquals(listOf(0), frames.filter { it.action == PlaybackAction.POINTER_UP }.map { it.actionPointerId })
        assertEquals(2, frames[1].pointers.size)
    }

    @Test
    fun `第二指错峰按下保留时间`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(
                track(0, Triple(100f, 100f, 0L), Triple(100f, 100f, 200L)),
                track(1, Triple(500f, 500f, 80L), Triple(500f, 500f, 200L)),
            ),
            durationMs = 200,
        )!!

        val pointerDown = frames.first { it.action == PlaybackAction.POINTER_DOWN }
        assertEquals(80L, pointerDown.timeMs)
        // 第二指按下前，所有帧都只能有一根手指
        assertTrue(frames.filter { it.timeMs < 80L }.all { it.pointers.size == 1 })
    }

    @Test
    fun `一指提前抬起后 MOVE 只带剩余手指`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(
                track(0, Triple(100f, 100f, 0L), Triple(300f, 300f, 200L)),
                track(1, Triple(500f, 500f, 0L), Triple(500f, 500f, 60L)),
            ),
            durationMs = 200,
        )!!

        val pointerUp = frames.first { it.action == PlaybackAction.POINTER_UP }
        assertEquals(60L, pointerUp.timeMs)
        assertTrue(frames.filter { it.timeMs > 60L && it.action == PlaybackAction.MOVE }
            .all { it.pointers.size == 1 })
    }

    @Test
    fun `抬起后的手指不会在后续帧复活`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(
                track(0, Triple(100f, 100f, 0L), Triple(400f, 400f, 300L)),
                track(1, Triple(500f, 500f, 0L), Triple(500f, 500f, 40L)),
            ),
            durationMs = 300,
        )!!

        val afterUp = frames.filter { it.timeMs > 40L }
        assertTrue(afterUp.isNotEmpty())
        assertTrue(afterUp.all { frame -> frame.pointers.none { it.id == 1 } })
    }

    @Test
    fun `移动帧包含全部活动手指且位置为最新`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(
                track(0, Triple(0f, 0f, 0L), Triple(100f, 100f, 100L)),
                track(1, Triple(500f, 500f, 0L), Triple(400f, 400f, 100L)),
            ),
            durationMs = 120,
        )!!

        val move = frames.first { it.action == PlaybackAction.MOVE && it.timeMs == 100L }
        assertEquals(2, move.pointers.size)
        assertEquals(100f, move.pointers.first { it.id == 0 }.x)
        assertEquals(400f, move.pointers.first { it.id == 1 }.x)
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(TouchPlaybackPlanner.plan(emptyList(), 100))
        assertNull(
            TouchPlaybackPlanner.plan(
                listOf(track(0), track(0)),
                durationMs = 100,
            ),
        )
        assertNull(
            TouchPlaybackPlanner.plan(
                (0 until 11).map { track(it, Triple(0f, 0f, 0L)) },
                durationMs = 100,
            ),
        )
        assertNull(TouchPlaybackPlanner.plan(listOf(PlaybackTrack(0, emptyList())), 100))
    }

    @Test
    fun `超过 60s 的采样被收敛`() {
        val frames = TouchPlaybackPlanner.plan(
            listOf(track(0, Triple(0f, 0f, 0L), Triple(10f, 10f, 90_000L))),
            durationMs = 90_000,
        )

        assertNotNull(frames)
        assertTrue(frames!!.all { it.timeMs <= TouchPlaybackPlanner.MAX_DURATION_MS })
        assertEquals(PlaybackAction.UP, frames.last().action)
    }
}
