package com.example.voicectl.ui.adb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicectl.di.AppContainer
import com.example.voicectl.exec.AdbClient
import kotlinx.coroutines.launch

class AdbViewModel(private val container: AppContainer) : ViewModel() {
    var ip by mutableStateOf("192.168.1.23")
        private set
    var pairPort by mutableStateOf("37157")
        private set
    var connectPort by mutableStateOf("5555")
        private set
    var pairCode by mutableStateOf("")
        private set
    var bridgeUrl by mutableStateOf(container.config.exec.adbBaseUrl)
        private set
    var status by mutableStateOf("未连接 · 等待配对")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun updateIp(value: String) { ip = value }
    fun updatePairPort(value: String) { pairPort = value }
    fun updateConnectPort(value: String) { connectPort = value }
    fun updatePairCode(value: String) { pairCode = value }
    fun updateBridgeUrl(value: String) { bridgeUrl = value }

    fun saveBridgeUrl() = viewModelScope.launch {
        container.settingsStore.setAdbBaseUrl(bridgeUrl)
        message = "桥接地址已保存到本机"
    }

    fun pair() = viewModelScope.launch {
        val client = AdbClient(bridgeUrl)
        val result = client.pair(ip, pairPort.toIntOrNull() ?: 37157, pairCode)
        message = if (result.isSuccess) "配对请求已发送（需 PC 端桥接服务响应）" else "配对失败：${result.exceptionOrNull()?.message}"
        status = if (result.isSuccess) "配对中 · 等待桥接确认" else "配对失败"
    }

    fun connect() = viewModelScope.launch {
        val client = AdbClient(bridgeUrl)
        val result = client.connect(ip, connectPort.toIntOrNull() ?: 5555)
        message = if (result.isSuccess) "连接请求已发送" else "连接失败：${result.exceptionOrNull()?.message}"
        status = if (result.isSuccess) "已连接 · 等待心跳确认" else "未连接"
    }

    fun heartbeat() = viewModelScope.launch {
        val online = AdbClient(bridgeUrl).heartbeat()
        status = if (online) "已连接 · 心跳正常" else "离线 · 请检查桥接服务"
        message = if (online) "心跳探测：device" else "心跳探测失败，执行时自动降级到只读提示"
    }

    fun dismissMessage() { message = null }
}
