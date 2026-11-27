package com.aixiaohuadb.macro

import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.ObjectLabel
import com.aixiaohuadb.service.MacroConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroBindingStoreTest {

    private fun command(slot: Int) = CommandResult(
        ActionLabel.A0_SWAP,
        ObjectLabel.OTHER,
        slot,
        0.95f,
    )

    @Test
    fun `编解码保真 清洗说法分隔符并丢弃拒识槽`() {
        val bindings = listOf(
            VoiceMacroBinding(0, 1001L, "换,装;连招"),
            VoiceMacroBinding(14, 1002L, "收尾", enabled = false),
        )
        val decoded = MacroBindingCodec.decode(MacroBindingCodec.encode(bindings))
        assertEquals(1, decoded.size)
        assertEquals(0, decoded[0].slot)
        assertEquals(1001L, decoded[0].macroId)
        assertEquals("换 装 连招", decoded[0].phrase)
        assertTrue(decoded[0].enabled)
    }

    @Test
    fun `槽位越界与拒识槽记录被忽略`() {
        val decoded = MacroBindingCodec.decode("99,1001,坏槽位,1;14,1002,拒识,1;3,1003,好槽位,1")
        assertEquals(1, decoded.size)
        assertEquals(3, decoded.single().slot)
    }

    @Test
    fun `只有绑定存在 启用且宏存在时才解析成功`() {
        val macro = MacroConfig(1001L, "连招", listOf(ExecStep.Tap(100, 200)))
        val bindings = listOf(VoiceMacroBinding(2, 1001L, "连招"))

        assertEquals(macro, MacroBindingStore.resolve(command(2), bindings, listOf(macro)))
        assertNull(MacroBindingStore.resolve(command(1), bindings, listOf(macro)))
        assertNull(
            MacroBindingStore.resolve(
                command(2),
                listOf(bindings.single().copy(enabled = false)),
                listOf(macro),
            ),
        )
        assertNull(MacroBindingStore.resolve(command(2), bindings, emptyList()))
    }
}
