package com.aixiaohuadb.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayWidgetModelTest {

    @Test
    fun `nextMacroId starts at 1000 for empty list`() {
        assertEquals(1000L, nextMacroId(emptyList()))
    }

    @Test
    fun `nextMacroId increments from max`() {
        val macros = listOf(
            MacroConfig(1000, "a", emptyList()),
            MacroConfig(1003, "b", emptyList()),
        )
        assertEquals(1004L, nextMacroId(macros))
    }

    @Test
    fun `macro id space never collides with preset commands`() {
        val next = nextMacroId(emptyList())
        assertTrue(next >= MacroConfig.MACRO_ID_BASE)
        assertTrue(next !in 0..14)
    }

    @Test
    fun `widget id helpers parse correctly`() {
        assertEquals(13, OverlayWidget.commandIdOf("cmd:13"))
        assertEquals(1001L, OverlayWidget.macroIdOf("macro:1001"))
        assertEquals(null, OverlayWidget.commandIdOf("macro:1001"))
        assertEquals(null, OverlayWidget.macroIdOf("cmd:0"))
        assertEquals(null, OverlayWidget.commandIdOf("bad"))
    }

    @Test
    fun `clamp helpers bound values`() {
        assertEquals(36, clampSizeDp(10))
        assertEquals(96, clampSizeDp(999))
        assertEquals(40, clampAlpha(0))
        assertEquals(100, clampAlpha(150))
        assertEquals(0f, clampPos(-1f))
        assertEquals(1f, clampPos(2f))
    }
}
