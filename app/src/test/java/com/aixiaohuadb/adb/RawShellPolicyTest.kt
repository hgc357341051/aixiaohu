package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawShellPolicyTest {

    @Test
    fun `空命令与硬黑名单一律拒绝`() {
        val blank = RawShellPolicy.decide(RawShellRequest("  ", CommandSource.USER_CONSOLE))
        assertEquals(RawShellPolicy.Decision.DENY, blank.decision)

        val dangerous = listOf(
            "rm -rf /", "rm  -rf  /", "reboot", "su -c id", "setenforce 0",
            "dd if=/dev/zero of=/dev/block", "pm uninstall com.foo", "pm clear com.foo",
            "wipe data", "mkfs.ext4 /dev/block",
        )
        dangerous.forEach { command ->
            val verdict = RawShellPolicy.decide(RawShellRequest(command, CommandSource.USER_CONSOLE))
            assertEquals(command, RawShellPolicy.Decision.DENY, verdict.decision)
            assertFalse(RawShellPolicy.isAllowed(RawShellRequest(command, CommandSource.USER_CONSOLE)))
        }
    }

    @Test
    fun `普通 shell 允许但用户来源必须确认`() {
        val verdict = RawShellPolicy.decide(RawShellRequest("input keyevent 3", CommandSource.USER_CONSOLE))
        assertEquals(RawShellPolicy.Decision.ALLOW_WITH_CONFIRM, verdict.decision)
        assertTrue(RawShellPolicy.isAllowed(RawShellRequest("input keyevent 3", CommandSource.USER_CONSOLE)))
    }

    @Test
    fun `调试来源也必须显式确认`() {
        val verdict = RawShellPolicy.decide(RawShellRequest("dumpsys activity", CommandSource.DEBUG))
        assertEquals(RawShellPolicy.Decision.ALLOW_WITH_CONFIRM, verdict.decision)
        assertTrue(verdict.reason.contains("确认"))
    }
}
