package com.example.voicectl.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 六态迁移优先级与 lastAuthorized 生命周期的补充验证（P0）。
 * 与 ShizukuStatusResolverTest 互补：这里专门压测「多条件同时成立」时的优先级，
 * 以及客户端「曾授权标记永不清零」的策略是否仍能把撤销识别为 AUTHORIZATION_LOST。
 */
class ShizukuStatusPriorityTest {

    private fun env(installed: Boolean = true, alive: Boolean = true) = ShizukuEnvironment(
        sdkInt = 34,
        shizukuInstalled = installed,
        binderAlive = alive,
        shizukuApiVersion = 13,
        uid = 2000,
        selinuxContext = "u:r:shell:s0",
        privilege = Privilege.SHELL,
        screenWidth = 1080,
        screenHeight = 2340,
    )

    private fun phaseOf(
        installed: Boolean = true,
        alive: Boolean = true,
        granted: Boolean = false,
        lastAuthorized: Boolean = false,
        unsupported: Boolean = false,
        current: ShizukuPhase? = null,
    ): ShizukuPhase = ShizukuStatusResolver.resolve(
        env = env(installed, alive),
        current = current,
        lastAuthorized = lastAuthorized,
        permissionGranted = granted,
        unsupported = unsupported,
        checkedAtMs = 42L,
    ).phase

    @Test
    fun `P0 未安装优先级最高即使版本不支持服务未运行且已授权`() {
        assertEquals(
            ShizukuPhase.NOT_INSTALLED,
            phaseOf(
                installed = false,
                alive = false,
                granted = true,
                lastAuthorized = true,
                unsupported = true,
            ),
        )
    }

    @Test
    fun `P0 不支持优先级高于服务未运行与已授权`() {
        assertEquals(
            ShizukuPhase.UNSUPPORTED,
            phaseOf(alive = false, granted = true, lastAuthorized = true, unsupported = true),
        )
    }

    @Test
    fun `P0 服务未运行优先级高于授权判断且不得误判为授权失效`() {
        assertEquals(
            ShizukuPhase.NOT_RUNNING,
            phaseOf(alive = false, granted = true, lastAuthorized = true),
        )
        assertEquals(
            ShizukuPhase.NOT_RUNNING,
            phaseOf(alive = false, granted = false, lastAuthorized = true),
        )
        assertEquals(
            ShizukuPhase.NOT_RUNNING,
            phaseOf(
                alive = false,
                granted = false,
                lastAuthorized = false,
                current = ShizukuPhase.AUTHORIZATION_LOST,
            ),
        )
    }

    @Test
    fun `P0 已授权优先级高于曾授权且能覆盖授权失效历史态`() {
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            phaseOf(
                granted = true,
                lastAuthorized = false,
                current = ShizukuPhase.AUTHORIZATION_LOST,
            ),
        )
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            phaseOf(granted = true, lastAuthorized = true),
        )
    }

    @Test
    fun `P0 无历史授权且未授权落到UNAUTHORIZED`() {
        assertEquals(ShizukuPhase.UNAUTHORIZED, phaseOf())
        assertEquals(
            ShizukuPhase.UNAUTHORIZED,
            phaseOf(current = ShizukuPhase.NOT_RUNNING),
        )
    }

    @Test
    fun `P0 current为授权失效即使lastAuthorized未置位也保持授权失效`() {
        assertEquals(
            ShizukuPhase.AUTHORIZATION_LOST,
            phaseOf(
                current = ShizukuPhase.AUTHORIZATION_LOST,
                granted = false,
                lastAuthorized = false,
            ),
        )
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            phaseOf(
                current = ShizukuPhase.AUTHORIZATION_LOST,
                granted = true,
                lastAuthorized = false,
            ),
        )
    }

    /**
     * 复刻 ShizukuClientImpl.refresh 的置位策略：
     * lastAuthorized 只在观察到 AUTHORIZED 时置 true，binder 死亡/撤销都不清零。
     */
    @Test
    fun `P0 授权撤销全链路_曾授权到服务死亡到恢复后权限被撤`() {
        var lastAuthorized = false
        var current: ShizukuPhase? = null

        fun step(installed: Boolean, alive: Boolean, granted: Boolean): ShizukuPhase {
            val status = ShizukuStatusResolver.resolve(
                env = env(installed, alive),
                current = current,
                lastAuthorized = lastAuthorized || current == ShizukuPhase.AUTHORIZED,
                permissionGranted = granted,
                unsupported = false,
                checkedAtMs = 42L,
            )
            if (status.phase == ShizukuPhase.AUTHORIZED) lastAuthorized = true
            current = status.phase
            return status.phase
        }

        assertEquals(ShizukuPhase.NOT_INSTALLED, step(false, false, false))
        assertEquals(ShizukuPhase.NOT_RUNNING, step(true, false, false))
        assertEquals(ShizukuPhase.UNAUTHORIZED, step(true, true, false))
        assertEquals(ShizukuPhase.AUTHORIZED, step(true, true, true))
        assertEquals(ShizukuPhase.NOT_RUNNING, step(true, false, false))
        assertEquals(ShizukuPhase.AUTHORIZATION_LOST, step(true, true, false))
        assertEquals(ShizukuPhase.AUTHORIZED, step(true, true, true))
    }

    @Test
    fun `P1 解析结果原样保留环境快照与时间戳`() {
        val environment = env()
        val status = ShizukuStatusResolver.resolve(
            env = environment,
            current = null,
            lastAuthorized = false,
            permissionGranted = true,
            unsupported = false,
            reason = "手动重查",
            checkedAtMs = 9_999L,
        )
        assertSame(environment, status.env)
        assertEquals("手动重查", status.reason)
        assertEquals(9_999L, status.checkedAtMs)
    }
}
