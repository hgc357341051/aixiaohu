package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbStatusTest {
    private val env = AdbEnvironment(sdkInt = 33, wirelessDebuggingEnabled = 1, screenWidth = 1080, screenHeight = 2400)

    @Test
    fun `仅配对时连接端点为空避免误显示已连接`() {
        val status = AdbStatus(
            phase = AdbPhase.PAIRED,
            env = env,
            pairedHost = "192.168.2.168",
            pairedPort = 42261,
        )
        assertNull(status.endpointLabel)
        assertEquals("192.168.2.168:42261", status.pairedLabel)
    }

    @Test
    fun `已连接时连接端点与配对端点分别展示`() {
        val status = AdbStatus(
            phase = AdbPhase.CONNECTED,
            env = env,
            host = "192.168.2.168",
            port = 35122,
            pairedHost = "192.168.2.168",
            pairedPort = 42261,
        )
        assertEquals("192.168.2.168:35122", status.endpointLabel)
        assertEquals("192.168.2.168:42261", status.pairedLabel)
    }

    @Test
    fun `连接失败后不残留端点`() {
        val status = AdbStatus(phase = AdbPhase.ERROR, env = env, reason = "连接被拒绝")
        assertNull(status.endpointLabel)
        assertEquals("连接被拒绝", status.reason)
    }
}
