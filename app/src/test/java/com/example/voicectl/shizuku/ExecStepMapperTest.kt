package com.example.voicectl.shizuku

import com.example.voicectl.config.BaseSize
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecStepMapperTest {
    private val mapper = CoordinateMapper(BaseSize(1080, 2340))

    @Test
    fun `tap 步骤映射为强类型 Tap 并按真实屏幕换算`() {
        assertEquals(
            InputCommand.Tap(1800, 4200),
            ExecStepMapper.map(ExecStep.Tap(900, 2100), mapper, 2160, 4680),
        )
        assertEquals(
            InputCommand.Tap(719, 1599),
            ExecStepMapper.map(ExecStep.Tap(1080, 2340), mapper, 720, 1600),
        )
    }

    @Test
    fun `wait 与 verify 不产生输入命令`() {
        assertNull(ExecStepMapper.map(ExecStep.Wait(500), mapper, 1080, 2340))
        assertNull(ExecStepMapper.map(ExecStep.Verify("装备栏"), mapper, 1080, 2340))
    }
}
