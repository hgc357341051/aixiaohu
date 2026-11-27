package com.example.voicectl.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

/** 六态迁移表驱动测试：把架构文档中的每一行变成可执行断言。 */
class ShizukuStatusResolverTest {

    private fun env(
        installed: Boolean = true,
        alive: Boolean = true,
        version: Int = 13,
        sdkInt: Int = 34,
    ) = ShizukuEnvironment(
        sdkInt = sdkInt,
        shizukuInstalled = installed,
        binderAlive = alive,
        shizukuApiVersion = version,
        uid = 2000,
        selinuxContext = "u:r:shell:s0",
        privilege = Privilege.SHELL,
        screenWidth = 1080,
        screenHeight = 2340,
    )

    private fun resolve(
        environment: ShizukuEnvironment = env(),
        current: ShizukuPhase? = null,
        lastAuthorized: Boolean = false,
        granted: Boolean = false,
        unsupported: Boolean = false,
        reason: String? = null,
    ) = ShizukuStatusResolver.resolve(
        env = environment,
        current = current,
        lastAuthorized = lastAuthorized,
        permissionGranted = granted,
        unsupported = unsupported,
        reason = reason,
        checkedAtMs = 1_000L,
    )

    @Test
    fun `初始化未安装`() {
        assertEquals(ShizukuPhase.NOT_INSTALLED, resolve(env(installed = false)).phase)
    }

    @Test
    fun `初始化版本不支持`() {
        assertEquals(ShizukuPhase.UNSUPPORTED, resolve(unsupported = true).phase)
        assertEquals(ShizukuPhase.UNSUPPORTED, resolve(unsupported = true, granted = true).phase)
    }

    @Test
    fun `初始化已安装但服务未运行`() {
        assertEquals(ShizukuPhase.NOT_RUNNING, resolve(env(alive = false)).phase)
    }

    @Test
    fun `初始化已授权与未授权`() {
        assertEquals(ShizukuPhase.AUTHORIZED, resolve(granted = true).phase)
        assertEquals(ShizukuPhase.UNAUTHORIZED, resolve().phase)
    }

    @Test
    fun `未安装检测到安装后转未运行`() {
        val next = resolve(
            environment = env(alive = false),
            current = ShizukuPhase.NOT_INSTALLED,
        )
        assertEquals(ShizukuPhase.NOT_RUNNING, next.phase)
    }

    @Test
    fun `未运行时 binder 到达按授权结果分流`() {
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            resolve(current = ShizukuPhase.NOT_RUNNING, granted = true).phase,
        )
        assertEquals(
            ShizukuPhase.UNAUTHORIZED,
            resolve(current = ShizukuPhase.NOT_RUNNING, granted = false).phase,
        )
    }

    @Test
    fun `未授权申请结果分流并保留拒绝原因`() {
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            resolve(current = ShizukuPhase.UNAUTHORIZED, granted = true).phase,
        )
        val denied = resolve(
            current = ShizukuPhase.UNAUTHORIZED,
            granted = false,
            reason = "授权被拒绝",
        )
        assertEquals(ShizukuPhase.UNAUTHORIZED, denied.phase)
        assertEquals("授权被拒绝", denied.reason)
    }

    @Test
    fun `已授权遇 binder 死亡转未运行并保留曾授权标记语义`() {
        val dead = resolve(
            environment = env(alive = false),
            current = ShizukuPhase.AUTHORIZED,
            lastAuthorized = true,
        )
        assertEquals(ShizukuPhase.NOT_RUNNING, dead.phase)
    }

    @Test
    fun `服务在跑但授权被撤转授权失效`() {
        val revoked = resolve(
            current = ShizukuPhase.AUTHORIZED,
            lastAuthorized = true,
            granted = false,
        )
        assertEquals(ShizukuPhase.AUTHORIZATION_LOST, revoked.phase)

        val afterRestart = resolve(
            current = ShizukuPhase.NOT_RUNNING,
            lastAuthorized = true,
            granted = false,
        )
        assertEquals(ShizukuPhase.AUTHORIZATION_LOST, afterRestart.phase)
    }

    @Test
    fun `授权失效态在重新授权成功后回到已授权`() {
        assertEquals(
            ShizukuPhase.AUTHORIZED,
            resolve(current = ShizukuPhase.AUTHORIZATION_LOST, granted = true).phase,
        )
        assertEquals(
            ShizukuPhase.AUTHORIZATION_LOST,
            resolve(current = ShizukuPhase.AUTHORIZATION_LOST, granted = false).phase,
        )
    }

    @Test
    fun `任意态执行前重查发现授权被撤都落到授权失效`() {
        val phases = listOf(
            ShizukuPhase.NOT_RUNNING,
            ShizukuPhase.UNAUTHORIZED,
            ShizukuPhase.AUTHORIZED,
            ShizukuPhase.AUTHORIZATION_LOST,
        )
        phases.forEach { current ->
            val next = resolve(current = current, lastAuthorized = true, granted = false)
            assertEquals("当前态 $current 应转为 AUTHORIZATION_LOST", ShizukuPhase.AUTHORIZATION_LOST, next.phase)
        }
    }

    @Test
    fun `refresh 对任意态按当前环境重算且时间戳可预测`() {
        val result = resolve(current = ShizukuPhase.AUTHORIZATION_LOST, granted = true)
        assertEquals(ShizukuPhase.AUTHORIZED, result.phase)
        assertEquals(1_000L, result.checkedAtMs)
        assertEquals(13, result.env.shizukuApiVersion)
    }
}
