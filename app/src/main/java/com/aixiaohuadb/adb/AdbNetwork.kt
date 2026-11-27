package com.aixiaohuadb.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 解析当前设备可用于无线 ADB 的局域网 IPv4 地址。
 *
 * 优先读取系统活动网络的 LinkProperties（Android 10+ 同样适用），
 * 失败时回退到遍历网卡，最后才降级到 127.0.0.1。
 * 这里只做本地只读解析，不发起任何网络请求。
 */
object AdbNetwork {
    const val FALLBACK_HOST = "127.0.0.1"

    fun detectLanIp(context: Context): String {
        val fromConnectivity = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching null
            val network = cm.activeNetwork ?: return@runCatching null
            val links: LinkProperties = cm.getLinkProperties(network) ?: return@runCatching null
            links.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull(::isUsableLanIp)
        }.getOrNull()
        if (!fromConnectivity.isNullOrBlank()) return fromConnectivity

        val fromInterfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull(::isUsableLanIp)
        }.getOrNull()
        if (!fromInterfaces.isNullOrBlank()) return fromInterfaces

        return FALLBACK_HOST
    }

    /** 排除回环、链路本地和非 IPv4 地址。internal 便于单元测试覆盖降级路径。 */
    internal fun isUsableLanIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip == FALLBACK_HOST || ip.startsWith("127.")) return false
        if (ip.startsWith("169.254.")) return false
        return ip.count { it == '.' } == 3 && ip.all { it.isDigit() || it == '.' }
    }
}
