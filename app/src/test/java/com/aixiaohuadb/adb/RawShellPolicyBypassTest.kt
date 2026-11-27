package com.aixiaohuadb.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/** S5：token 级解析覆盖粘连/分隔符/重定向绕过，同时避免子串误杀 */
class RawShellPolicyBypassTest {

    private fun decide(command: String, source: CommandSource = CommandSource.USER_CONSOLE) =
        RawShellPolicy.decide(RawShellRequest(command, source))

    @Test
    fun `删除根目录的各种变形都被拦截`() {
        val dangerous = listOf(
            "rm -rf /",
            "rm -fr /",
            "rm -r -f /",
            "rm -rf/",
            "rm -rf\n/",
            "rm  -rf  /",
            "rm -rf /system",
            "rm -rf /data/local/tmp",
            "echo x; rm -rf /",
            "echo x && rm -rf /",
            "echo x || rm -rf /",
            "echo x | rm -rf /",
            "rm -rf / ; reboot",
            "rm -fr /sdcard",
        )
        dangerous.forEach { command ->
            assertEquals(command, RawShellPolicy.Decision.DENY, decide(command).decision)
        }
    }

    @Test
    fun `重定向写系统节点与危险动词都被拦截`() {
        val dangerous = listOf(
            "echo b > /proc/sysrq-trigger",
            "echo b>/proc/sysrq-trigger",
            "echo b >> /sys/kernel/debug/x",
            "reboot",
            "REBOOT",
            "su -c id",
            "setenforce 0",
            "dd if=/dev/zero of=/dev/block",
            "pm uninstall com.foo",
            "pm clear com.foo",
            "wipe data",
            "mkfs.ext4 /dev/block",
        )
        dangerous.forEach { command ->
            assertEquals(command, RawShellPolicy.Decision.DENY, decide(command).decision)
        }
    }

    @Test
    fun `包含su或rm子串的正常命令不被误杀`() {
        val benign = listOf(
            "input text summary",
            "dumpsys package com.msu",
            "dumpsys activity",
            "input keyevent 3",
            "pm list packages",
            "echo hello",
            "am start -n com.foo/.Bar",
        )
        benign.forEach { command ->
            assertEquals(
                command,
                RawShellPolicy.Decision.ALLOW_WITH_CONFIRM,
                decide(command).decision,
            )
        }
    }

    @Test
    fun `所有来源都必须显式确认且含风险提示`() {
        CommandSource.entries.forEach { source ->
            val verdict = decide("dumpsys activity", source)
            assertEquals(source.name, RawShellPolicy.Decision.ALLOW_WITH_CONFIRM, verdict.decision)
            assertEquals(source.name, true, RawShellPolicy.isAllowed(RawShellRequest("dumpsys activity", source)))
            assertEquals(source.name, true, verdict.reason.contains("确认"))
        }
    }

    @Test
    fun `空命令一律拒绝`() {
        assertEquals(RawShellPolicy.Decision.DENY, decide("").decision)
        assertEquals(RawShellPolicy.Decision.DENY, decide("   \n ").decision)
    }

    @Test
    fun `进程替换与命令替换一律拒绝`() {
        val substitutions = listOf(
            "cat <(rm -rf /)",
            "diff <(ls) <(ls -l)",
            "\$(rm -rf /)",
            "echo \$(id)",
            "echo \"\$(rm -rf /)\"",
            "cat <(echo b > /proc/sysrq-trigger)",
        )
        substitutions.forEach { command ->
            val verdict = decide(command)
            assertEquals(command, RawShellPolicy.Decision.DENY, verdict.decision)
            assertEquals(command, true, verdict.reason.contains("进程替换"))
        }
    }
}
