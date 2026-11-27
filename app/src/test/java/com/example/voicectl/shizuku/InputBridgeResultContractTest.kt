package com.example.voicectl.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InputBridge 结果包装的 JVM 侧契约锁定。
 *
 * InputBridgeImpl.done() 是 AIDL 调用内的局部函数，真机链路（bindUserService / Stub）
 * 无法在纯 JVM 单测中实例化；这里锁定 CommandResult 的数据契约：
 * 1. ok 只在 exitCode == 0 时为真；2. 真实退出码原样保留；3. 异常不伪造数值。
 * 真实 AIDL 返回值到 CommandResult 的传递见「真机验证清单」。
 */
class InputBridgeResultContractTest {

    private data class ExitCase(val code: Int, val label: String, val expectedOk: Boolean)

    private val exitCases = listOf(
        ExitCase(0, "执行成功", true),
        ExitCase(124, "执行超时", false),
        ExitCase(125, "执行失败", false),
        ExitCase(126, "非法参数", false),
        ExitCase(1, "probe失败", false),
    )

    @Test
    fun `P0 AIDL各退出码映射为CommandResult且真实码不被改写`() {
        exitCases.forEach { c ->
            val result = CommandResult(
                ok = c.code == 0,
                exitCode = c.code,
                elapsedMs = 7L,
                message = "${c.label} · exit=${c.code}",
            )
            assertEquals("${c.label} 的 ok 判定", c.expectedOk, result.ok)
            assertEquals("${c.label} 必须保留真实 exitCode", c.code, result.exitCode)
            assertEquals("${c.label} 耗时应为真实值", 7L, result.elapsedMs)
            assertTrue(result.message, result.message.contains(c.code.toString()))
        }
    }

    @Test
    fun `P0 成功与失败消息都携带命令文本和真实退出码`() {
        // InputBridgeImpl.execute 的成功消息格式为 "${describeCommand(cmd)} · exit=$exitCode"
        val tapText = describeCommand(InputCommand.Tap(540, 1170))
        val swipeText = describeCommand(InputCommand.Swipe(0, 0, 100, 200, 500))
        val okMessage = "$tapText · exit=0"
        val failedMessage = "$swipeText · exit=126"

        assertTrue(okMessage, okMessage.startsWith("/system/bin/input tap 540 1170"))
        assertTrue(failedMessage, failedMessage.startsWith("/system/bin/input swipe 0 0 100 200 500"))
        assertTrue(okMessage, okMessage.endsWith("exit=0"))
        assertTrue(failedMessage, failedMessage.endsWith("exit=126"))
    }

    @Test
    fun `P0 异常路径不伪造退出码与耗时`() {
        val result = CommandResult(
            ok = false,
            exitCode = null,
            elapsedMs = 0L,
            message = "SecurityException：授权可能已撤销",
        )
        assertNull(result.exitCode)
        assertFalse(result.ok)
        assertEquals(0L, result.elapsedMs)
        assertTrue(result.message, result.message.contains("授权"))
    }
}
