package com.example.voicectl.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引导链与调试快照的纯函数补充用例（P1）。 */
class ActivationGuideTest {

    @Test
    fun `P1 SDK分界决定唯一引导策略`() {
        assertTrue(ActivationGuides.forSdk(29) is ActivationGuide.InstallShizuku)
        assertTrue(ActivationGuides.forSdk(33) is ActivationGuide.InstallShizuku)
        assertTrue(ActivationGuides.forSdk(34) is ActivationGuide.WirelessDebug)
        assertTrue(ActivationGuides.forSdk(35) is ActivationGuide.WirelessDebug)
        assertEquals(34, ActivationGuides.WIRELESS_DEBUG_MIN_SDK)
    }

    @Test
    fun `P1 无线调试引导包含特有动作且两种引导都以重新检测收尾`() {
        val wireless = ActivationGuides.forSdk(34)
        val legacy = ActivationGuides.forSdk(33)

        assertTrue(wireless.steps.any { it.action is GuideAction.OpenWirelessDebugging })
        assertFalse(legacy.steps.any { it.action is GuideAction.OpenWirelessDebugging })
        assertEquals(GuideAction.Recheck, wireless.steps.last().action)
        assertEquals(GuideAction.Recheck, legacy.steps.last().action)
        assertTrue(wireless.steps.any { it.action is GuideAction.OpenShizukuApp })
        assertTrue(legacy.steps.any { it.action is GuideAction.OpenShizukuApp })
    }

    @Test
    fun `P1 调试信息完整派生自状态快照`() {
        val env = ShizukuEnvironment(
            sdkInt = 34,
            shizukuInstalled = true,
            binderAlive = true,
            shizukuApiVersion = 13,
            uid = 2000,
            selinuxContext = "u:r:shell:s0",
            privilege = Privilege.SHELL,
            screenWidth = 1440,
            screenHeight = 3200,
        )
        val status = ShizukuStatus(
            phase = ShizukuPhase.AUTHORIZATION_LOST,
            env = env,
            reason = "授权被撤销",
            checkedAtMs = 123L,
        )

        val info = ShizukuDebugInfo.from(status)

        assertEquals(ShizukuPhase.AUTHORIZATION_LOST, info.phase)
        assertEquals(34, info.sdkInt)
        assertTrue(info.shizukuInstalled)
        assertTrue(info.binderAlive)
        assertEquals(13, info.shizukuApiVersion)
        assertEquals(2000, info.uid)
        assertEquals("u:r:shell:s0", info.selinuxContext)
        assertEquals(Privilege.SHELL, info.privilege)
        assertEquals(1440, info.screenWidth)
        assertEquals(3200, info.screenHeight)
        assertEquals("授权被撤销", info.reason)
        assertEquals(123L, info.checkedAtMs)
    }
}
