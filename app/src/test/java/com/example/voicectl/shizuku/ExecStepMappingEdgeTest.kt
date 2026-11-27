package com.example.voicectl.shizuku

import com.example.voicectl.config.BaseSize
import com.example.voicectl.exec.CommandLibrary
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ExecStepMapper 边界与命令库不变量补充用例。 */
class ExecStepMappingEdgeTest {
    private val mapper = CoordinateMapper(BaseSize(1080, 2340))
    private val screens = listOf(720 to 1600, 1080 to 2340, 1440 to 3200, 2160 to 4680)

    @Test
    fun `P0 原点在所有分辨率下均映射为零点`() {
        screens.forEach { (w, h) ->
            assertEquals(
                "screen=${w}×$h",
                InputCommand.Tap(0, 0),
                ExecStepMapper.map(ExecStep.Tap(0, 0), mapper, w, h),
            )
        }
    }

    @Test
    fun `P0 基准最大坐标被夹到屏幕内且不会等于屏幕宽高`() {
        screens.forEach { (w, h) ->
            assertEquals(
                "screen=${w}×$h",
                InputCommand.Tap(w - 1, h - 1),
                ExecStepMapper.map(ExecStep.Tap(1080, 2340), mapper, w, h),
            )
        }
    }

    @Test
    fun `P0 命令库全部步骤映射后只产生Tap或null且坐标均通过校验`() {
        var mappedCount = 0
        CommandLibrary.commandActions.forEach { action ->
            action.steps.forEach { step ->
                screens.forEach { (w, h) ->
                    when (val mapped = ExecStepMapper.map(step, mapper, w, h)) {
                        null -> assertTrue(
                            "Wait/Verify 不应产生命令，实际步骤：$step",
                            step is ExecStep.Wait || step is ExecStep.Verify,
                        )
                        is InputCommand.Tap -> {
                            mappedCount++
                            CommandValidator.validate(mapped, w, h)
                            assertTrue("x 越界：${mapped.x}", mapped.x in 0 until w)
                            assertTrue("y 越界：${mapped.y}", mapped.y in 0 until h)
                        }
                        else -> org.junit.Assert.fail("命令库不应产生 $mapped")
                    }
                }
            }
        }
        assertTrue("命令库应至少映射出一个点击命令", mappedCount > 0)
    }

    @Test
    fun `P1 混合步骤只保留Tap数量`() {
        val steps = listOf(
            ExecStep.Wait(0),
            ExecStep.Tap(900, 2100, "a"),
            ExecStep.Verify("v1"),
            ExecStep.Tap(760, 1750, "b"),
            ExecStep.Verify("v2"),
        )
        val mapped = steps.mapNotNull { ExecStepMapper.map(it, mapper, 1080, 2340) }

        assertEquals(listOf(InputCommand.Tap(900, 2100), InputCommand.Tap(760, 1750)), mapped)
        assertNull(ExecStepMapper.map(ExecStep.Wait(1), mapper, 1080, 2340))
        assertNull(ExecStepMapper.map(ExecStep.Verify("x"), mapper, 1080, 2340))
    }

    @Test
    fun `P1 调试命令文本与shell实际参数一致`() {
        assertEquals(
            "/system/bin/input tap 540 1170",
            describeCommand(InputCommand.Tap(540, 1170)),
        )
        assertEquals(
            "/system/bin/input swipe 0 0 100 200 500",
            describeCommand(InputCommand.Swipe(0, 0, 100, 200, 500)),
        )
        assertEquals(
            "/system/bin/input keyevent 66",
            describeCommand(InputCommand.KeyEvent(66)),
        )
    }
}
