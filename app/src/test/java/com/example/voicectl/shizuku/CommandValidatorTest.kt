package com.example.voicectl.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CommandValidatorTest {
    private val width = 1080
    private val height = 2340

    @Test
    fun `tap 边界内坐标原样通过`() {
        assertEquals(InputCommand.Tap(0, 0), CommandValidator.validate(InputCommand.Tap(0, 0), width, height))
        assertEquals(
            InputCommand.Tap(width - 1, height - 1),
            CommandValidator.validate(InputCommand.Tap(width - 1, height - 1), width, height),
        )
        assertEquals(
            InputCommand.Tap(540, 1170),
            CommandValidator.validate(InputCommand.Tap(540, 1170), width, height),
        )
    }

    @Test
    fun `tap 拒绝越界与恶意整数`() {
        assertRejected(InputCommand.Tap(-1, 10))
        assertRejected(InputCommand.Tap(10, -1))
        assertRejected(InputCommand.Tap(width, 10))
        assertRejected(InputCommand.Tap(10, height))
        assertRejected(InputCommand.Tap(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `swipe 合法时长含边界`() {
        val swipe = InputCommand.Swipe(0, 0, width - 1, height - 1, CommandValidator.MAX_DURATION_MS)
        assertEquals(swipe, CommandValidator.validate(swipe, width, height))
        assertEquals(
            InputCommand.Swipe(100, 200, 300, 400, 0),
            CommandValidator.validate(InputCommand.Swipe(100, 200, 300, 400, 0), width, height),
        )
    }

    @Test
    fun `swipe 拒绝超长时长与越界坐标`() {
        assertRejected(InputCommand.Swipe(0, 0, 1, 1, CommandValidator.MAX_DURATION_MS + 1))
        assertRejected(InputCommand.Swipe(0, 0, 1, 1, -1))
        assertRejected(InputCommand.Swipe(-1, 0, 1, 1, 100))
        assertRejected(InputCommand.Swipe(0, 0, width, 1, 100))
        assertRejected(InputCommand.Swipe(0, 0, 1, Int.MAX_VALUE, 100))
    }

    @Test
    fun `keyevent 仅允许白名单三个按键`() {
        listOf(3, 4, 66).forEach { code ->
            assertEquals(
                InputCommand.KeyEvent(code),
                CommandValidator.validate(InputCommand.KeyEvent(code), width, height),
            )
        }
        listOf(0, 1, 5, 24, 187, -1, Int.MAX_VALUE).forEach {
            assertRejected(InputCommand.KeyEvent(it))
        }
    }

    @Test
    fun `非法屏幕尺寸一律拒绝`() {
        assertRejected(InputCommand.Tap(1, 1), width = 0)
        assertRejected(InputCommand.Tap(1, 1), height = 0)
        assertRejected(InputCommand.Tap(1, 1), width = -1080)
    }

    private fun assertRejected(command: InputCommand, width: Int = this.width, height: Int = this.height) {
        try {
            CommandValidator.validate(command, width, height)
            fail("命令应被拒绝：$command (screen=${width}×$height)")
        } catch (expected: IllegalArgumentException) {
            // 期望路径
        }
    }
}
