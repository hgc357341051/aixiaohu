package com.example.voicectl.adb

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
}
