package com.aixiaohuadb.service

import com.aixiaohuadb.exec.ExecStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCodecTest {

    @Test
    fun `widgets v2 roundtrip preserves all fields`() {
        val list = listOf(
            OverlayWidget("cmd:0", WidgetKind.COMMAND, commandId = 0, label = "名刀", sizeDp = 56, alphaPercent = 88, fx = 0.92f, fy = 0.28f, enabled = true, locked = false),
            OverlayWidget("macro:1001", WidgetKind.MACRO, macroId = 1001, label = "连招三", sizeDp = 40, alphaPercent = 60, fx = 0.05f, fy = 0.85f, enabled = false, locked = true),
        )
        val decoded = OverlayCodec.decodeWidgets(OverlayCodec.encodeWidgets(list))
        assertEquals(list, decoded)
    }

    @Test
    fun `decode widgets ignores malformed records`() {
        val decoded = OverlayCodec.decodeWidgets("bad;;C0|56|88|9200|2800|1|0:名刀")
        assertEquals(1, decoded.size)
        assertEquals("名刀", decoded[0].label)
        assertEquals(0, decoded[0].commandId)
    }

    @Test
    fun `decode widgets clamps out of range values`() {
        val encoded = OverlayCodec.encodeWidgets(
            listOf(OverlayWidget("cmd:3", WidgetKind.COMMAND, commandId = 3, label = "吸血书", sizeDp = 200, alphaPercent = 5, fx = 1.7f, fy = -0.2f)),
        )
        val w = OverlayCodec.decodeWidgets(encoded).single()
        assertTrue(w.sizeDp in OverlayWidget.MIN_SIZE_DP..OverlayWidget.MAX_SIZE_DP)
        assertTrue(w.alphaPercent in OverlayWidget.MIN_ALPHA..OverlayWidget.MAX_ALPHA)
        assertTrue(w.fx in 0f..1f)
        assertTrue(w.fy in 0f..1f)
    }

    @Test
    fun `ball roundtrip`() {
        val cfg = BallConfig(sizeDp = 72, alphaPercent = 90, fx = 0.02f, fy = 0.09f, visible = false, locked = true)
        assertEquals(cfg, OverlayCodec.decodeBall(OverlayCodec.encodeBall(cfg)))
        assertEquals(null, OverlayCodec.decodeBall("bad"))
    }

    @Test
    fun `macros roundtrip preserves steps`() {
        val macro = MacroConfig(
            macroId = 1001,
            name = "连招三",
            steps = listOf(
                ExecStep.Tap(900, 2100),
                ExecStep.Wait(300),
                ExecStep.Tap(760, 1750),
                ExecStep.Verify("ignored"),
            ),
            createdAt = 123L,
        )
        val decoded = OverlayCodec.decodeMacros(OverlayCodec.encodeMacros(listOf(macro))).single()
        assertEquals(1001L, decoded.macroId)
        assertEquals("连招三", decoded.name)
        assertEquals(
            listOf(ExecStep.Tap(900, 2100), ExecStep.Wait(300), ExecStep.Tap(760, 1750)),
            decoded.steps,
        )
    }

    @Test
    fun `legacy migration preserves fields and maps to command kind`() {
        val legacy = "0,56,88,9200,2800,1:名刀;13,40,60,500,8500,0:切换位置"
        val migrated = OverlayCodec.migrateLegacy(legacy)
        assertEquals(2, migrated.size)
        assertEquals("cmd:0", migrated[0].id)
        assertEquals(WidgetKind.COMMAND, migrated[0].kind)
        assertEquals("名刀", migrated[0].label)
        assertEquals(56, migrated[0].sizeDp)
        assertEquals(88, migrated[0].alphaPercent)
        assertEquals(0.92f, migrated[0].fx, 0.0001f)
        assertEquals(true, migrated[0].enabled)
        assertEquals("cmd:13", migrated[1].id)
        assertEquals(false, migrated[1].enabled)
    }

    @Test
    fun `migrate ignores malformed records`() {
        assertEquals(0, OverlayCodec.migrateLegacy("").size)
        assertEquals(0, OverlayCodec.migrateLegacy("bad;;x,y:z").size)
    }

    @Test
    fun `sanitize label strips separators`() {
        assertEquals("名·刀", OverlayCodec.sanitizeLabel("名;刀"))
        assertEquals("切换：位置", OverlayCodec.sanitizeLabel("切换:位置"))
        assertEquals("a·b·c", OverlayCodec.sanitizeLabel("a|b~c"))
        assertEquals("按钮", OverlayCodec.sanitizeLabel("  "))
    }

    @Test
    fun `decode empty strings are empty`() {
        assertTrue(OverlayCodec.decodeWidgets("").isEmpty())
        assertTrue(OverlayCodec.decodeMacros("").isEmpty())
    }
}
