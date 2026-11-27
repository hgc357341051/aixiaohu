package com.aixiaohuadb.config

import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.inference.api.ObjectLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelBookTest {
    @Test
    fun `唤醒标签六类齐全`() {
        assertEquals(6, LabelBook.wakeLabels.size)
        assertEquals("WK_WAKE_NIHAO_XIAOHU", LabelBook.wakeLabels[0])
        assertEquals("WK_NEG_HARD_NEAR", LabelBook.wakeLabels[5])
    }

    @Test
    fun `十五条指令标签齐全`() {
        assertEquals(15, LabelBook.commandLabels.size)
        assertEquals("换名刀", LabelBook.labelOf(0))
        assertEquals("换6号位", LabelBook.labelOf(12))
        assertEquals("切换位置", LabelBook.labelOf(13))
        assertEquals("CMD_OTHER_UNKNOWN", LabelBook.labelOf(14))
    }

    @Test
    fun `ACTION 与 OBJECT 组合映射正确`() {
        assertEquals(0, LabelBook.commandIdOf(ActionLabel.A0_SWAP, ObjectLabel.E0_MINGDAO))
        assertEquals(6, LabelBook.commandIdOf(ActionLabel.A0_SWAP, ObjectLabel.E6_XUEMO))
        assertEquals(7, LabelBook.commandIdOf(ActionLabel.A0_SWAP, ObjectLabel.P0))
        assertEquals(12, LabelBook.commandIdOf(ActionLabel.A0_SWAP, ObjectLabel.P5))
        assertEquals(13, LabelBook.commandIdOf(ActionLabel.A1_SWITCH, ObjectLabel.P6_POSITION))
        assertEquals(14, LabelBook.commandIdOf(ActionLabel.A2_NONE, ObjectLabel.E0_MINGDAO))
    }

    @Test
    fun `控制头 C0 到 C4 映射`() {
        assertEquals(5, LabelBook.controlLabels.size)
        assertEquals("CTRL_EXIT_WAKE", LabelBook.controlLabelOf(ControlWord.C1_EXIT_WAKE))
        assertEquals("CTRL_RESUME", LabelBook.controlLabelOf(ControlWord.C4_RESUME))
        assertTrue(LabelBook.controlPhrases[ControlWord.C1_EXIT_WAKE]!!.contains("退出唤醒"))
    }

    @Test
    fun `拒识判定`() {
        assertTrue(LabelBook.isReject(14))
        assertTrue(LabelBook.isReject(99))
        assertFalse(LabelBook.isReject(0))
    }
}
