package com.aixiaohuadb.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlModeTest {
    @Test
    fun `fromKey 识别合法值`() {
        assertEquals(ControlMode.VOICE, ControlMode.fromKey("VOICE"))
        assertEquals(ControlMode.MANUAL, ControlMode.fromKey("MANUAL"))
    }

    @Test
    fun `fromKey 对空值与非法值回退语音模式`() {
        assertEquals(ControlMode.VOICE, ControlMode.fromKey(null))
        assertEquals(ControlMode.VOICE, ControlMode.fromKey(""))
        assertEquals(ControlMode.VOICE, ControlMode.fromKey("UNKNOWN"))
    }

    @Test
    fun `标签与说明非空且区分模式`() {
        assertEquals("语音识别", ControlMode.VOICE.label)
        assertEquals("手动点触", ControlMode.MANUAL.label)
        assert(ControlMode.VOICE.description.isNotBlank())
        assert(ControlMode.MANUAL.description.isNotBlank())
        assert(ControlMode.VOICE.label != ControlMode.MANUAL.label)
    }
}
