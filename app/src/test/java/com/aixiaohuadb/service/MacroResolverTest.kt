package com.aixiaohuadb.service

import com.aixiaohuadb.exec.ExecStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroResolverTest {

    @Test
    fun `build action with isolated id space`() {
        val macro = MacroConfig(
            macroId = 1001,
            name = "连招三",
            steps = listOf(ExecStep.Tap(900, 2100), ExecStep.Wait(200), ExecStep.Tap(760, 1750)),
        )
        val action = MacroResolver.buildCommandAction(macro)
        assertEquals(2001, action.commandId)
        assertEquals("连招三", action.name)
        assertEquals(macro.steps, action.steps)
    }

    @Test
    fun `empty steps macro yields empty action`() {
        val action = MacroResolver.buildCommandAction(MacroConfig(1000, "空宏", emptyList()))
        assertTrue(action.steps.isEmpty())
    }
}
