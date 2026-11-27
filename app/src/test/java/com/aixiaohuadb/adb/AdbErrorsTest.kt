package com.aixiaohuadb.adb

import org.junit.Assert.assertTrue
import org.junit.Test

class AdbErrorsTest {
    @Test
    fun `拒绝连接提示指向动态端口变化`() {
        val msg = AdbErrors.connection(IllegalStateException("failed to connect to /192.168.2.168 port 35122 (refused)"), "192.168.2.168", 35122)
        assertTrue(msg.contains("192.168.2.168:35122"))
        assertTrue(msg.contains("连接端口"))
        assertTrue(msg.contains("端口都会变"))
    }

    @Test
    fun `超时提示指向同网段检查`() {
        val msg = AdbErrors.connection(IllegalStateException("connect timed out"), "192.168.2.168", 35122)
        assertTrue(msg.contains("同一 WiFi"))
    }

    @Test
    fun `未知异常保留原始信息`() {
        val msg = AdbErrors.connection(IllegalStateException("socket closed"), "192.168.2.168", 35122)
        assertTrue(msg.contains("socket closed"))
    }

    @Test
    fun `配对被拒绝提示重新打开弹窗`() {
        val msg = AdbErrors.pairing(IllegalStateException("connection refused"))
        assertTrue(msg.contains("配对端口"))
        assertTrue(msg.contains("重新打开"))
    }
}
