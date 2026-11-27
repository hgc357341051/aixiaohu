package com.aixiaohuadb.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlModeTest {
    @Test
    fun `fromKey 识别三值模式`() {
        assertEquals(ControlMode.VOICE, ControlMode.fromKey("VOICE"))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey("ACCESSIBILITY"))
        assertEquals(ControlMode.ADB, ControlMode.fromKey("ADB"))
    }

    @Test
    fun `旧 MANUAL 与非法值迁移到无障碍模式`() {
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey("MANUAL"))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey("manual"))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey(null))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey(""))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.fromKey("UNKNOWN"))
        assertEquals(ControlMode.ACCESSIBILITY, ControlMode.DEFAULT)
    }

    @Test
    fun `标签与说明非空且区分模式`() {
        assertEquals("语音识别", ControlMode.VOICE.label)
        assertEquals("无障碍模式", ControlMode.ACCESSIBILITY.label)
        assertEquals("ADB 命令模式", ControlMode.ADB.label)
        assert(ControlMode.entries.all { it.description.isNotBlank() })
        assertEquals(3, ControlMode.entries.size)
    }
}
