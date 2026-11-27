package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbNetworkTest {
    @Test
    fun `局域网IPv4被接受`() {
        assertTrue(AdbNetwork.isUsableLanIp("192.168.2.168"))
        assertTrue(AdbNetwork.isUsableLanIp("10.0.0.7"))
    }

    @Test
    fun `回环地址被拒绝`() {
        assertFalse(AdbNetwork.isUsableLanIp(AdbNetwork.FALLBACK_HOST))
        assertFalse(AdbNetwork.isUsableLanIp("127.0.0.2"))
    }

    @Test
    fun `链路本地与非IPv4被拒绝`() {
        assertFalse(AdbNetwork.isUsableLanIp("169.254.1.2"))
        assertFalse(AdbNetwork.isUsableLanIp("fe80::1"))
        assertFalse(AdbNetwork.isUsableLanIp("192.168.1.1.5"))
    }

    @Test
    fun `空值与乱码被拒绝`() {
        assertFalse(AdbNetwork.isUsableLanIp(null))
        assertFalse(AdbNetwork.isUsableLanIp(""))
        assertFalse(AdbNetwork.isUsableLanIp("abc.def.ghi.jkl"))
    }

    @Test
    fun `降级常量为回环地址`() {
        assertEquals("127.0.0.1", AdbNetwork.FALLBACK_HOST)
    }
}
