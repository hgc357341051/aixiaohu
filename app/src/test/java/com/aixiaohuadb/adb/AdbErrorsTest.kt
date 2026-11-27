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

    @Test
    fun `SSL证书未知映射为可操作提示`() {
        val raw = "Read error: ssl=0xb400007777030c18: Failure in SSL library, " +
            "error:100000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN"
        val msg = AdbErrors.pairing(IllegalStateException(raw))
        assertTrue(msg.contains("证书握手被设备拒绝") || msg.contains("配对握手被设备拒绝"))
        assertTrue(msg.contains("配对端口"))
        assertTrue(msg.contains("连接端口"))
        assertTrue(msg.contains("重新打开"))
    }

    @Test
    fun `连接时信任缺失提示重新配对`() {
        val msg = AdbErrors.connection(IllegalStateException("Pairing required or trust missing"), "192.168.2.168", 35122)
        assertTrue(msg.contains("未信任本机 ADB 身份"))
        assertTrue(msg.contains("重新发起配对"))
        assertTrue(msg.contains("换包名"))
    }

    @Test
    fun `配对trust缺失提示换包名需重新配对`() {
        val msg = AdbErrors.pairing(IllegalStateException("Pairing required or trust missing"))
        assertTrue(msg.contains("配对握手被设备拒绝"))
        assertTrue(msg.contains("换过包名"))
    }

    @Test
    fun `异常链中的证书错误也能识别`() {
        val cause = IllegalStateException("SSLV3_ALERT_CERTIFICATE_UNKNOWN (tls_record.cc:486)")
        val wrapper = java.io.IOException("handshake failed", cause)
        val msg = AdbErrors.connection(wrapper, "192.168.2.168", 35122)
        assertTrue(msg.contains("未信任本机 ADB 身份"))
    }
}
