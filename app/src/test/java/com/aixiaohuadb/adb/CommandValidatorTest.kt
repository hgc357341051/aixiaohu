package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandValidatorTest {
    private val validator = CommandValidator

    @Test
    fun `合法点击通过校验`() {
        val result = validator.validate(InputCommand.Tap(540, 1170), 1080, 2340)
        assertTrue(result is CommandValidator.ValidationResult.Valid)
    }

    @Test
    fun `越界坐标被拒绝`() {
        val result = validator.validate(InputCommand.Tap(1080, 1170), 1080, 2340)
        assertTrue(result is CommandValidator.ValidationResult.Invalid)
        assertEquals("x 越界：1080（屏幕宽 1080）", (result as CommandValidator.ValidationResult.Invalid).reason)
    }

    @Test
    fun `滑动时长超上限被拒绝`() {
        val result = validator.validate(
            InputCommand.Swipe(100, 100, 800, 900, CommandValidator.MAX_DURATION_MS + 1),
            1080,
            2340,
        )
        assertTrue(result is CommandValidator.ValidationResult.Invalid)
    }

    @Test
    fun `按键白名单之外的键码被拒绝`() {
        val result = validator.validate(InputCommand.KeyEvent(82))
        assertTrue(result is CommandValidator.ValidationResult.Invalid)
    }

    @Test
    fun `HOME键通过校验`() {
        val result = validator.validate(InputCommand.KeyEvent(3))
        assertTrue(result is CommandValidator.ValidationResult.Valid)
    }

    private fun multiTouch(
        pointers: List<InputCommand.MotionPointer> = listOf(
            InputCommand.MotionPointer(
                listOf(InputCommand.MotionPoint(100, 200, 0L), InputCommand.MotionPoint(100, 200, 80L)),
            ),
            InputCommand.MotionPointer(
                listOf(InputCommand.MotionPoint(800, 900, 20L), InputCommand.MotionPoint(800, 900, 80L)),
            ),
        ),
        durationMs: Int = 100,
        devicePath: String = "/dev/input/event5",
    ) = InputCommand.MultiTouch(pointers, durationMs, devicePath)

    @Test
    fun `合法双指命令通过校验`() {
        assertTrue(validator.validate(multiTouch(), 1080, 2340) is CommandValidator.ValidationResult.Valid)
    }

    @Test
    fun `单指和非法设备路径的多指命令被拒绝`() {
        val single = multiTouch(pointers = listOf(multiTouch().pointers.first()))
        assertTrue(validator.validate(single, 1080, 2340) is CommandValidator.ValidationResult.Invalid)

        val badPath = multiTouch(devicePath = "/dev/input/event5; rm -rf /")
        val result = validator.validate(badPath, 1080, 2340) as CommandValidator.ValidationResult.Invalid
        assertEquals("触摸设备路径非法", result.reason)
    }

    @Test
    fun `多指时长和轨迹点越界被拒绝`() {
        val tooLong = multiTouch(durationMs = CommandValidator.MAX_MULTITOUCH_DURATION_MS + 1)
        assertTrue(validator.validate(tooLong, 1080, 2340) is CommandValidator.ValidationResult.Invalid)

        val badPoint = multiTouch(
            pointers = listOf(
                InputCommand.MotionPointer(listOf(InputCommand.MotionPoint(1_080, 100, 0L))),
                InputCommand.MotionPointer(listOf(InputCommand.MotionPoint(10, 10, 0L))),
            ),
        )
        assertTrue(validator.validate(badPoint, 1080, 2340) is CommandValidator.ValidationResult.Invalid)
    }
}
