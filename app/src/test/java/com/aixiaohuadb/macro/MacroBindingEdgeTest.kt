package com.aixiaohuadb.macro

import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.ObjectLabel
import com.aixiaohuadb.service.MacroConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 槽位 14 拒识语义与重复绑定的 JVM 回归 */
class MacroBindingEdgeTest {

    private fun command(slot: Int) = CommandResult(
        ActionLabel.A0_SWAP,
        ObjectLabel.OTHER,
        slot,
        0.95f,
    )

    @Test
    fun `拒识槽14永不绑定永不解析`() {
        assertFalse(VoiceMacroBinding.isBindable(14))
        assertFalse(VoiceMacroBinding.isBindable(99))
        assertTrue(VoiceMacroBinding.isBindable(0))
        assertTrue(VoiceMacroBinding.isBindable(13))

        val macro = MacroConfig(1002L, "收尾", listOf(ExecStep.Tap(1, 2)))
        val binding = VoiceMacroBinding(14, 1002L, "收尾")
        assertNull(MacroBindingStore.resolve(command(14), listOf(binding), listOf(macro)))
        assertNull(MacroBindingCodec.decode("14,1002,收尾,1").firstOrNull())
    }

    @Test
    fun `同槽位保留最新绑定旧宏不再解析`() {
        val oldBinding = VoiceMacroBinding(3, 100L, "旧")
        val newBinding = VoiceMacroBinding(3, 200L, "新")
        // 复现 MacroBindingStore.bind 的覆写规则：先移除同槽位再追加
        val afterBind = listOf(oldBinding).filterNot { it.slot == 3 } + newBinding
        val decoded = MacroBindingCodec.decode(MacroBindingCodec.encode(afterBind))

        val oldMacro = MacroConfig(100L, "旧宏", listOf(ExecStep.Tap(1, 2)))
        val newMacro = MacroConfig(200L, "新宏", listOf(ExecStep.Tap(3, 4)))

        assertEquals(
            newMacro,
            MacroBindingStore.resolve(command(3), decoded, listOf(oldMacro, newMacro)),
        )
        assertEquals(1, decoded.size)
        assertEquals(200L, decoded.single().macroId)
    }

    @Test
    fun `宏被删除或未启用时不产生可执行命令`() {
        val binding = VoiceMacroBinding(5, 300L, "闭合")
        val macro = MacroConfig(300L, "闭合", listOf(ExecStep.Tap(9, 9)))
        assertNull(MacroBindingStore.resolve(command(5), listOf(binding), emptyList()))
        assertNull(
            MacroBindingStore.resolve(
                command(5),
                listOf(binding.copy(enabled = false)),
                listOf(macro),
            ),
        )
    }
}
