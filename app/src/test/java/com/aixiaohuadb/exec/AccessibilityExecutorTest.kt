package com.aixiaohuadb.exec

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityExecutorTest {
    private val mapper = CoordinateMapper()

    private data class TapCall(val x: Int, val y: Int)
    private data class SwipeCall(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val duration: Int)
    private data class MultiCall(val pointers: List<GesturePointer>, val duration: Int)

    private class FakeSink(private val result: Boolean = true) : GestureSink {
        val taps = mutableListOf<TapCall>()
        val swipes = mutableListOf<SwipeCall>()
        val multis = mutableListOf<MultiCall>()

        override suspend fun tap(x: Int, y: Int): Boolean {
            taps += TapCall(x, y)
            return result
        }

        override suspend fun swipe(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Int,
        ): Boolean {
            swipes += SwipeCall(fromX, fromY, toX, toY, durationMs)
            return result
        }

        override suspend fun multiGesture(pointers: List<GesturePointer>, durationMs: Int): Boolean {
            multis += MultiCall(pointers, durationMs)
            return result
        }
    }

    private fun action(vararg steps: ExecStep) = CommandAction(1, "测试宏", steps.toList())

    @Test
    fun `点击与多指按顺序派发且坐标已映射`() = runTest {
        val sink = FakeSink()
        val executor = AccessibilityExecutor({ sink }, mapper, { 1080 to 2340 })

        val result = executor.execute(
            action(
                ExecStep.Tap(540, 1_170),
                ExecStep.MultiGesture(
                    pointers = listOf(
                        GesturePointer(listOf(GesturePoint(270, 585, 0))),
                        GesturePointer(listOf(GesturePoint(810, 1_755, 40))),
                    ),
                    durationMs = 200,
                ),
                ExecStep.Swipe(0, 0, 1080, 2340, durationMs = 300),
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf(TapCall(540, 1_170)), sink.taps)
        assertEquals(1, sink.multis.size)
        assertEquals(200, sink.multis.single().duration)
        assertEquals(270, sink.multis.single().pointers[0].points.single().x)
        assertEquals(40L, sink.multis.single().pointers[1].points.single().timeMs)
        assertEquals(1, sink.swipes.size)
        assertEquals(1079, sink.swipes.single().toX)
        assertEquals(2339, sink.swipes.single().toY)
    }

    @Test
    fun `多指不受支持时停止后续步骤且未派发时允许回退`() = runTest {
        val sink = FakeSink(result = false)
        val executor = AccessibilityExecutor({ sink }, mapper, { 1080 to 2340 })

        val result = executor.execute(
            action(
                ExecStep.MultiGesture(
                    pointers = listOf(
                        GesturePointer(listOf(GesturePoint(100, 100, 0))),
                        GesturePointer(listOf(GesturePoint(200, 200, 0))),
                    ),
                    durationMs = 100,
                ),
                ExecStep.Tap(10, 10),
            ),
        )

        assertFalse(result.success)
        assertFalse("多指前未成功派发，可安全回退", result.attempted)
        assertEquals(1, sink.multis.size)
        assertTrue("失败后不再派发后续步骤", sink.taps.isEmpty())
    }

    @Test
    fun `多指失败发生在已成功点击之后时标记 attempted`() = runTest {
        val sink = object : GestureSink {
            override suspend fun tap(x: Int, y: Int): Boolean = true
            override suspend fun swipe(
                fromX: Int,
                fromY: Int,
                toX: Int,
                toY: Int,
                durationMs: Int,
            ): Boolean = true

            override suspend fun multiGesture(
                pointers: List<GesturePointer>,
                durationMs: Int,
            ): Boolean = false
        }
        val executor = AccessibilityExecutor({ sink }, mapper, { 1080 to 2340 })

        val result = executor.execute(
            action(
                ExecStep.Tap(540, 1_170),
                ExecStep.MultiGesture(
                    pointers = listOf(
                        GesturePointer(listOf(GesturePoint(100, 100, 0))),
                        GesturePointer(listOf(GesturePoint(200, 200, 0))),
                    ),
                    durationMs = 100,
                ),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.attempted)
    }

    @Test
    fun `无障碍服务未开启时立即失败`() = runTest {
        val executor = AccessibilityExecutor({ null }, mapper, { 1080 to 2340 })

        val result = executor.execute(action(ExecStep.Tap(1, 1)))

        assertFalse(result.success)
        assertFalse(result.attempted)
        assertTrue(result.message.contains("未开启"))
    }
}
