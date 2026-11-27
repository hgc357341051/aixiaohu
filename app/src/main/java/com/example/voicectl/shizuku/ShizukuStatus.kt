package com.example.voicectl.shizuku

/** Shizuku 执行链路的六个稳定状态。 */
enum class ShizukuPhase {
    /** 未安装 Shizuku App */
    NOT_INSTALLED,

    /** App 已安装，但 Shizuku 服务端（binder）未运行 */
    NOT_RUNNING,

    /** 服务端在跑，但本 App 尚未获得授权 */
    UNAUTHORIZED,

    /** 服务端在跑且本 App 已授权，可安全执行输入 */
    AUTHORIZED,

    /** 服务端仍在跑，但本 App 的授权曾被撤销 */
    AUTHORIZATION_LOST,

    /** Shizuku 版本过低（isPreV11 或 API < 10），不支持 UserService */
    UNSUPPORTED,
}

/** 服务端特权身份；只按 uid 判定，不读取任何特权文件。 */
enum class Privilege { SHELL, ROOT, UNKNOWN }

/**
 * 真机环境快照。所有字段都来自本次真实探测，没有探测到就置 null / false，
 * 不允许用默认值伪装成「已连接」。
 */
data class ShizukuEnvironment(
    val sdkInt: Int,
    val shizukuInstalled: Boolean,
    val binderAlive: Boolean,
    val shizukuApiVersion: Int?,
    val uid: Int?,
    val selinuxContext: String?,
    val privilege: Privilege,
    val screenWidth: Int,
    val screenHeight: Int,
)

data class ShizukuStatus(
    val phase: ShizukuPhase,
    val env: ShizukuEnvironment,
    val reason: String? = null,
    val checkedAtMs: Long,
)

/**
 * 六态迁移的纯函数实现，供单测表驱动验证。
 *
 * 迁移优先级：未安装 > 版本不支持 > binder 不在 > 已授权 > 曾授权但被撤 > 未授权。
 * [lastAuthorized] 是区分 NOT_RUNNING→UNAUTHORIZED 与
 * NOT_RUNNING/AUTHORIZED→AUTHORIZATION_LOST 的唯一依据；
 * 由客户端在观察到 AUTHORIZED 时置位，binder 死亡不清零。
 */
object ShizukuStatusResolver {
    fun resolve(
        env: ShizukuEnvironment,
        current: ShizukuPhase?,
        lastAuthorized: Boolean,
        permissionGranted: Boolean,
        unsupported: Boolean,
        reason: String? = null,
        checkedAtMs: Long = System.currentTimeMillis(),
    ): ShizukuStatus {
        val phase = when {
            !env.shizukuInstalled -> ShizukuPhase.NOT_INSTALLED
            unsupported -> ShizukuPhase.UNSUPPORTED
            !env.binderAlive -> ShizukuPhase.NOT_RUNNING
            permissionGranted -> ShizukuPhase.AUTHORIZED
            lastAuthorized || current == ShizukuPhase.AUTHORIZATION_LOST -> ShizukuPhase.AUTHORIZATION_LOST
            else -> ShizukuPhase.UNAUTHORIZED
        }
        return ShizukuStatus(phase = phase, env = env, reason = reason, checkedAtMs = checkedAtMs)
    }
}
