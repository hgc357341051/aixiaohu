package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMultiTouchScriptTest {
    private val path = "/dev/input/event5"

    private fun device(
        maxSlot: Int = 9,
        pressureMax: Int = 0,
        touchMajorMax: Int = 0,
    ) = RawTouchDevice(
        path = path,
        maxX = 1079,
        maxY = 2339,
        maxSlot = maxSlot,
        hasSlot = true,
        hasTrackingId = true,
        pressureMax = pressureMax,
        touchMajorMax = touchMajorMax,
    )

    private fun pointer(vararg points: InputCommand.MotionPoint) =
        InputCommand.MotionPointer(points.toList())

    @Test
    fun `双指按下与抬起在同一时间轴内交错提交`() {
        val script = AdbMultiTouchScript.build(
            device(),
            listOf(
                pointer(
                    InputCommand.MotionPoint(100, 200, 0L),
                    InputCommand.MotionPoint(100, 200, 80L),
                ),
                pointer(
                    InputCommand.MotionPoint(800, 900, 20L),
                    InputCommand.MotionPoint(800, 900, 80L),
                ),
            ),
            durationMs = 100,
        )
        val lines = script.lines().filter { it.isNotEmpty() }

        assertEquals(
            listOf(
                "sendevent $path 1 330 1",
                "sendevent $path 1 325 1",
            ),
            lines.take(2),
        )
        assertTrue(lines.contains("sendevent $path 3 47 0"))
        assertTrue(lines.contains("sendevent $path 3 57 1"))
        assertTrue(lines.contains("sendevent $path 3 53 100"))
        assertTrue(lines.contains("sendevent $path 3 54 200"))
        assertTrue(lines.contains("sendevent $path 3 57 2"))
        assertTrue(lines.contains("sendevent $path 3 53 800"))
        assertEquals(2, lines.count { it == "sendevent $path 3 57 -1" })
        assertTrue(lines.contains("sleep 0.020"))
        assertTrue(lines.contains("sleep 0.060"))
        assertTrue(lines.contains("sendevent $path 1 330 0"))
        assertTrue(lines.last() == "sendevent $path 0 0 0")
    }

    @Test
    fun `移动帧只发变化轴且每帧一次SYN`() {
        val script = AdbMultiTouchScript.build(
            device(),
            listOf(
                pointer(
                    InputCommand.MotionPoint(100, 200, 0L),
                    InputCommand.MotionPoint(300, 200, 40L),
                    InputCommand.MotionPoint(300, 500, 80L),
                ),
                pointer(InputCommand.MotionPoint(800, 900, 0L)),
            ),
            durationMs = 100,
        )
        val lines = script.lines()

        assertEquals(1, lines.count { it == "sendevent $path 3 53 300" })
        assertEquals(1, lines.count { it == "sendevent $path 3 54 500" })
        assertTrue("中间采样帧不能被降采样吞掉", lines.contains("sleep 0.040"))
        assertTrue(lines.count { it == "sendevent $path 0 0 0" } >= 3)
    }

    @Test
    fun `第二根手指错峰按下不会被拉到0ms`() {
        val script = AdbMultiTouchScript.build(
            device(),
            listOf(
                pointer(InputCommand.MotionPoint(100, 200, 0L)),
                pointer(InputCommand.MotionPoint(800, 900, 60L)),
            ),
            durationMs = 100,
        )
        val lines = script.lines()
        val sleepIndex = lines.indexOf("sleep 0.060")
        val secondTrackingIndex = lines.indexOf("sendevent $path 3 57 2")

        assertTrue("应出现 60ms 间隔", sleepIndex >= 0)
        assertTrue("第二指必须在 sleep 之后才按下", secondTrackingIndex > sleepIndex)
        assertTrue("第一指应在 0ms 立即按下", lines.indexOf("sendevent $path 3 57 1") < sleepIndex)
    }

    @Test
    fun `每根手指在自身最后一个采样点抬起`() {
        val script = AdbMultiTouchScript.build(
            device(),
            listOf(
                pointer(
                    InputCommand.MotionPoint(100, 200, 0L),
                    InputCommand.MotionPoint(100, 200, 120L),
                ),
                pointer(
                    InputCommand.MotionPoint(800, 900, 0L),
                    InputCommand.MotionPoint(800, 900, 40L),
                ),
            ),
            durationMs = 150,
        )
        val lines = script.lines().filter { it.isNotEmpty() }
        val firstRelease = lines.indexOf("sendevent $path 3 57 -1")
        val sleepToSecondFrame = lines.indexOf("sleep 0.040")

        assertTrue(firstRelease >= 0)
        assertTrue("第二指应在 40ms 帧释放", firstRelease > sleepToSecondFrame)
        assertTrue("第一指继续到 120ms", lines.contains("sleep 0.080"))
        assertEquals(2, lines.count { it == "sendevent $path 3 57 -1" })
    }

    @Test
    fun `设备声明压力轴时补发压力值`() {
        val script = AdbMultiTouchScript.build(
            device(pressureMax = 255),
            listOf(
                pointer(InputCommand.MotionPoint(100, 200, 0L)),
                pointer(InputCommand.MotionPoint(800, 900, 0L)),
            ),
            durationMs = 100,
        )

        assertTrue(script.contains("sendevent $path 3 58 127"))
        assertFalse(script.contains("sendevent $path 3 48"))
    }

    @Test
    fun `超出设备槽位数时拒绝注入`() {
        val result = runCatching {
            AdbMultiTouchScript.build(
                device(maxSlot = 1),
                listOf(
                    pointer(InputCommand.MotionPoint(1, 1, 0L)),
                    pointer(InputCommand.MotionPoint(2, 2, 0L)),
                    pointer(InputCommand.MotionPoint(3, 3, 0L)),
                ),
                durationMs = 100,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("最多支持 2 指"))
    }

    @Test
    fun `Type A 设备拒绝生成脚本`() {
        val typeA = device().copy(hasSlot = false, hasTrackingId = false)
        val result = runCatching {
            AdbMultiTouchScript.build(
                typeA,
                listOf(
                    pointer(InputCommand.MotionPoint(1, 1, 0L)),
                    pointer(InputCommand.MotionPoint(2, 2, 0L)),
                ),
                durationMs = 100,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `长轨迹按最小帧间隔降采样并限制条数`() {
        val points = (0 until 2_000).map { i ->
            InputCommand.MotionPoint(100 + i / 10, 200 + i / 10, i.toLong())
        }
        val script = AdbMultiTouchScript.build(
            device(),
            listOf(
                InputCommand.MotionPointer(points),
                pointer(InputCommand.MotionPoint(800, 900, 0L)),
            ),
            durationMs = 2_000,
        )

        assertTrue(script.lines().count { it.startsWith("sleep ") } <= 210)
        assertTrue(script.contains("sendevent $path 3 57 -1"))
    }
}
