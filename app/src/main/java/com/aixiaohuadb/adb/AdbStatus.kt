package com.aixiaohuadb.adb

/** 内嵌 ADB 链路的连接阶段。 */
enum class AdbPhase {
    /** 开发者选项未开启无线调试 / 系统版本过低 */
    NOT_READY,

    /** 尚未配对也未连接 */
    DISCONNECTED,

    /** 已保存配对身份，但当前未连接 */
    PAIRED,

    /** adbd 已连通，可执行 shell */
    CONNECTED,

    /** 配对或连接过程中失败 */
    ERROR,

    /** 系统版本过低，不支持无线调试（Android < 11） */
    UNSUPPORTED,
}

/** 真机环境快照：供页面展示真实检测结果，不缓存"看起来正常"的状态。 */
data class AdbEnvironment(
    val sdkInt: Int,
    val wirelessDebuggingEnabled: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    val supportsWirelessPairing: Boolean get() = sdkInt >= 30 // Android 11
}

/**
 * 一次检测的完整状态。
 *
 * host/port 表示「当前已探活成功的连接端点」，只有 phase 为 CONNECTED 时才应有值；
 * pairedHost/pairedPort 表示「最近一次成功配对的端点」，仅作信息展示，绝不代表链路可用。
 * 两者分离是为了避免配对成功后页面误显示“端点已连接”，而自检却报未连接。
 */
data class AdbStatus(
    val phase: AdbPhase,
    val env: AdbEnvironment,
    val host: String? = null,
    val port: Int? = null,
    val pairedHost: String? = null,
    val pairedPort: Int? = null,
    val reason: String? = null,
    val checkedAtMs: Long = System.currentTimeMillis(),
) {
    val endpointLabel: String? = host?.let { h -> port?.let { p -> "$h:$p" } ?: h }
    val pairedLabel: String? = pairedHost?.let { h -> pairedPort?.let { p -> "$h:$p" } ?: h }
}

/** 已保存的连接端点，用于一键重连。 */
data class AdbEndpoint(val host: String, val port: Int)
