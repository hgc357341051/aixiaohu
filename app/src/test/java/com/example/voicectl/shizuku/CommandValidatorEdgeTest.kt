package com.example.voicectl.shizuku

import com.example.voicectl.exec.CommandLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** CommandValidator 恶意值 / 极端值补充用例，与原有 CommandValidatorTest 不重叠。 */
class CommandValidatorEdgeTest {

    private fun assertRejected(command: InputCommand, width: Int = 1080, height: Int = 2340) {
        try {
            CommandValidator.validate(command, width, height)
            fail("命令应被拒绝：$command (screen=${width}×$height)")
        } catch (expected: IllegalArgumentException) {
            // 期望路径
        }
    }

    @Test
    fun `P0 durationMs取Int极值一律拒绝`() {
        assertRejected(InputCommand.Swipe(0, 0, 1, 1, Int.MAX_VALUE))
        assertRejected(InputCommand.Swipe(0, 0, 1, 1, Int.MIN_VALUE))
    }

    @Test
    fun `P0 屏幕尺寸取Int极值一律拒绝且不伪造合法范围`() {
        assertRejected(InputCommand.Tap(0, 0), width = Int.MIN_VALUE, height = Int.MAX_VALUE)
        assertRejected(InputCommand.Tap(0, 0), width = 0, height = Int.MAX_VALUE)
        assertRejected(InputCommand.Swipe(0, 0, 1, 1, 100), width = Int.MAX_VALUE, height = 0)
        assertRejected(InputCommand.KeyEvent(CommandValidator.KEYCODE_HOME), width = -1, height = 2340)
    }

    @Test
    fun `P1 多分辨率下原点与bound减一均被接受`() {
        val screens = listOf(720 to 1600, 1080 to 2340, 1440 to 3200, 2160 to 4680)
        screens.forEach { (w, h) ->
            assertEquals(InputCommand.Tap(0, 0), CommandValidator.validate(InputCommand.Tap(0, 0), w, h))
            assertEquals(
                InputCommand.Tap(w - 1, h - 1),
                CommandValidator.validate(InputCommand.Tap(w - 1, h - 1), w, h),
            )
            assertRejected(InputCommand.Tap(w, h - 1), w, h)
            assertRejected(InputCommand.Tap(w - 1, h), w, h)
        }
    }

    @Test
    fun `P1 命令库全部基准坐标都在1080×2340内合法`() {
        assertEquals(19, CommandLibrary.allCoordinates.size)
        CommandLibrary.allCoordinates.forEach { entry ->
            val command = InputCommand.Tap(entry.x, entry.y)
            assertEquals("基准坐标 ${entry.key} 应合法", command, CommandValidator.validate(command, 1080, 2340))
        }
    }
}
