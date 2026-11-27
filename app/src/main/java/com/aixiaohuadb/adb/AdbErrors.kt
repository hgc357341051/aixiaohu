package com.aixiaohuadb.adb

/**
 * 把底层 socket / TLS 异常转换成用户能据此操作的提示。
 *
 * Android 11+ 无线调试的配对端口与连接端口是两个不同端口，且连接端口每次开关
 * 无线调试都会变化；配对阶段的 TLS 握手会校验客户端证书，若用错端口（连到只接受
 * 已授权证书的连接端点）或换包名/重装后本机身份变了，就会抛
 * SSLV3_ALERT_CERTIFICATE_UNKNOWN / AdbPairAuthException("Pairing required or trust missing")。
 * 这类错误不能笼统显示“失败”，必须告诉用户下一步怎么做。
 */
internal object AdbErrors {

    private fun collectMessage(t: Throwable): String = buildString {
        generateSequence(t) { it.cause }.forEach { append(it.message.orEmpty()).append('\n') }
    }.ifBlank { "未知错误" }

    private fun isTrustError(raw: String): Boolean = listOf(
        "certificate_unknown",
        "certificate unknown",
        "certificate_required",
        "unknown_ca",
        "trust missing",
        "pairing required",
        "sslv3_alert",
    ).any { raw.contains(it, ignoreCase = true) }

    fun connection(t: Throwable, host: String, port: Int): String {
        val raw = collectMessage(t)
        return when {
            isTrustError(raw) ->
                "设备未信任本机 ADB 身份（证书握手被拒）。请回到上方「① 配对新设备」重新发起配对，" +
                    "配对会把本机公钥注册到设备；成功后回无线调试主页读取最新的连接端口再连接。" +
                    "注意：卸载重装或换包名后本机身份会变，必须重新配对。原始错误：$raw"

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
        val raw = collectMessage(t)
        return when {
            isTrustError(raw) ->
                "配对握手被设备拒绝。请确认填的是「使用配对码配对设备」弹窗里显示的配对端口，" +
                    "不是无线调试主页的连接端口；弹窗关闭或超时后端口即失效，请重新打开弹窗取新端口与新配对码。" +
                    "若刚卸载重装或换过包名，也请重新配对一次。原始错误：$raw"

            raw.contains("refused", ignoreCase = true) ->
                "配对被拒绝：配对端口只在「使用配对码配对设备」弹窗打开期间有效，请重新打开该弹窗并填入新端口与新配对码。原始错误：$raw"

            raw.contains("timed out", ignoreCase = true) || raw.contains("timeout", ignoreCase = true) ->
                "配对超时：请确保配对弹窗仍在倒计时，并尽快填入端口与配对码。原始错误：$raw"

            else -> "配对失败：$raw"
        }
    }
}
