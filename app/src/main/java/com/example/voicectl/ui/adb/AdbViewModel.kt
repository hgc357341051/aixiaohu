package com.example.voicectl.ui.adb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicectl.adb.AdbClient
import com.example.voicectl.adb.AdbPhase
import com.example.voicectl.adb.AdbStatus
import com.example.voicectl.adb.CommandResult
import com.example.voicectl.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdbUiState(
    val status: AdbStatus,
    val message: String? = null,
    val busy: Boolean = false,
    val probeResult: CommandResult? = null,
    val pairHost: String = "127.0.0.1",
    val pairPort: String = "",
    val pairCode: String = "",
    val connectHost: String = "127.0.0.1",
    val connectPort: String = "",
)

/** ADB 连接页状态：真机环境、阶段、配对/连接动作，全部来自真实检测。 */
class AdbViewModel(
    private val container: AppContainer,
    private val context: Context,
) : ViewModel() {
    private val client: AdbClient = container.adbClient

    private val _ui = MutableStateFlow(AdbUiState(status = client.status.value))
    val ui: StateFlow<AdbUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            client.status.collect { status ->
                _ui.update { it.copy(status = status) }
            }
        }
    }

    fun onResume() = viewModelScope.launch { client.refresh() }

    fun recheck() = viewModelScope.launch {
        client.refresh()
        _ui.update { it.copy(message = "已按当前真机状态重新检测") }
    }

    fun onPairHost(value: String) = _ui.update { it.copy(pairHost = value) }
    fun onPairPort(value: String) = _ui.update { it.copy(pairPort = value) }
    fun onPairCode(value: String) = _ui.update { it.copy(pairCode = value.filter { c -> c.isDigit() }.take(6)) }
    fun onConnectHost(value: String) = _ui.update { it.copy(connectHost = value) }
    fun onConnectPort(value: String) = _ui.update { it.copy(connectPort = value.filter { c -> c.isDigit() }) }

    fun pair() = viewModelScope.launch {
        val s = _ui.value
        val port = s.pairPort.toIntOrNull()
        if (port == null) {
            _ui.update { it.copy(message = "请填写有效的配对端口") }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null) }
        val result = client.pair(s.pairHost, port, s.pairCode)
        _ui.update {
            it.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "配对成功" },
                    onFailure = { t -> t.message ?: "配对失败" },
                ),
            )
        }
    }

    fun connect() = viewModelScope.launch {
        val s = _ui.value
        val port = s.connectPort.toIntOrNull()
        if (port == null) {
            _ui.update { it.copy(message = "请填写有效的连接端口") }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null) }
        val result = client.connect(s.connectHost, port)
        _ui.update {
            it.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "连接成功，shell 已就绪" },
                    onFailure = { t -> t.message ?: "连接失败" },
                ),
            )
        }
    }

    fun disconnect() {
        client.disconnect()
        _ui.update { it.copy(message = "已断开连接") }
    }

    fun probe() = viewModelScope.launch {
        _ui.update { it.copy(busy = true) }
        val result = client.probe()
        _ui.update { it.copy(busy = false, probeResult = result) }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun openDeveloperOptions() = open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    fun openWirelessDebugging() {
        if (!open(ACTION_WIRELESS_DEBUGGING)) open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    }

    fun openAdbDocs() = openUri(ADB_WIRELESS_URL)

    private fun open(action: String): Boolean = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun openUri(url: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private companion object {
        const val ACTION_WIRELESS_DEBUGGING = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        const val ADB_WIRELESS_URL = "https://developer.android.com/tools/adb#wireless"
    }
}
