package com.aixiaohuadb.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePlannerTest {
    private fun pointer(vararg points: GesturePoint) = GesturePointer(points.toList())

    @Test
    fun `一根手指只生成一个 Stroke 且保留完整轨迹`() {
        val strokes = GesturePlanner.plan(
            listOf(
                pointer(
                    GesturePoint(100, 200, 0),
                    GesturePoint(300, 400, 50),
                    GesturePoint(500, 600, 100),
                ),
            ),
            declaredDurationMs = 100,
        )

        assertNotNull(strokes)
        assertEquals(1, strokes!!.size)
        assertEquals(3, strokes.first().points.size)
        assertEquals(0L, strokes.first().startMs)
        assertEquals(100L, strokes.first().durationMs)
    }

    @Test
    fun `多指错峰按下保留各自起始时间`() {
        val strokes = GesturePlanner.plan(
            listOf(
                pointer(
                    GesturePoint(100, 200, 0),
                    GesturePoint(120, 220, 200),
                ),
                pointer(
                    GesturePoint(800, 900, 60),
                    GesturePoint(780, 880, 200),
                ),
            ),
            declaredDurationMs = 200,
        )!!

        assertEquals(2, strokes.size)
        assertEquals(0L, strokes[0].startMs)
        assertEquals(60L, strokes[1].startMs)
        assertEquals(140L, strokes[1].durationMs)
        assertEquals(200L, strokes[1].endMs)
    }

    @Test
    fun `单点手指生成 60ms 点击而不是零时长`() {
        val strokes = GesturePlanner.plan(listOf(pointer(GesturePoint(540, 1170, 0))), 0)!!

        assertEquals(1, strokes.size)
        assertEquals(GesturePlanner.TAP_DURATION_MS, strokes.first().durationMs)
    }

    @Test
    fun `超过 10 指直接拒绝`() {
        val pointers = (0 until 11).map { index ->
            pointer(GesturePoint(index, index, 0))
        }

        assertNull(GesturePlanner.plan(pointers, 100))
    }

    @Test
    fun `空轨迹拒绝且不抛异常`() {
        assertNull(GesturePlanner.plan(emptyList(), 100))
        assertNull(GesturePlanner.plan(listOf(GesturePointer(emptyList())), 100))
    }

    @Test
    fun `采样时间超过 60s 时收敛到合法区间`() {
        val strokes = GesturePlanner.plan(
            listOf(
                pointer(
                    GesturePoint(10, 10, 0),
                    GesturePoint(20, 20, 90_000),
                ),
            ),
            declaredDurationMs = 90_000,
        )!!

        val stroke = strokes.single()
        assertTrue(stroke.startMs >= 0)
        assertTrue(stroke.endMs <= GesturePlanner.MAX_DURATION_MS)
        assertTrue(stroke.durationMs > 0)
    }

    @Test
    fun `未排序的采样点按时间重排`() {
        val strokes = GesturePlanner.plan(
            listOf(
                pointer(
                    GesturePoint(500, 500, 100),
                    GesturePoint(100, 100, 0),
                ),
            ),
            declaredDurationMs = 100,
        )!!

        assertEquals(listOf(0L, 100L), strokes.single().points.map { it.timeMs })
        assertEquals(100, strokes.single().points.first().x)
    }
}
