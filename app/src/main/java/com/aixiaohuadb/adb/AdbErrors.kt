package com.aixiaohuadb.adb

/**
 * 把底层 socket 异常转换成用户能据此操作的提示。
 *
 * Android 11+ 无线调试的连接端口每次开关都会变化，且配对端口与连接端口是两个不同端口；
 * 「refused」几乎总是端口已失效或 IP 已变，不能笼统提示“连接失败”。
 */
internal object AdbErrors {
    fun connection(t: Throwable, host: String, port: Int): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        return when {
            raw.contains("refused", ignoreCase = true) ->
                "连接被拒绝：$host:$port 当前没有监听。请回手机「开发者选项 → 无线调试」主页查看最新连接端口" +
                    "（每次开关无线调试或重连 WiFi 后端口都会变），并确认 IP 与手机当前 WiFi 一致。原始错误：$raw"

            raw.contains("timed out", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ->
                "连接超时：请确认手机与本设备在同一 WiFi、无线调试仍开启，且防火墙未拦截。原始错误：$raw"

            raw.contains("EACCES", ignoreCase = true) || raw.contains("permission", ignoreCase = true) ->
                "连接被系统拒绝：请确认已用本页完成配对，且系统未撤销 ADB 调试授权。原始错误：$raw"

            else -> "连接失败：$raw"
        }
    }

    fun pairing(t: Throwable): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
        return when {
            raw.contains("refused", ignoreCase = true) ->
                "配对被拒绝：配对端口只在「使用配对码配对设备」弹窗打开期间有效，请重新打开该弹窗并填入新端口与新配对码。原始错误：$raw"

            else -> "配对失败：$raw"
        }
    }
}
