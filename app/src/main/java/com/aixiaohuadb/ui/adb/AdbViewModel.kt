package com.aixiaohuadb.ui.adb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aixiaohuadb.adb.AdbAuditEntry
import com.aixiaohuadb.adb.AdbAuditLog
import com.aixiaohuadb.adb.AdbBridgeState
import com.aixiaohuadb.adb.AdbClient
import com.aixiaohuadb.adb.AdbNetwork
import com.aixiaohuadb.adb.AdbStatus
import com.aixiaohuadb.adb.CommandResult
import com.aixiaohuadb.adb.CommandSource
import com.aixiaohuadb.adb.RawShellPolicy
import com.aixiaohuadb.adb.RawShellRequest
import com.aixiaohuadb.di.AppContainer
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
    val bridge: AdbBridgeState = AdbBridgeState(),
    val rawCommand: String = "",
    val pendingRaw: RawShellRequest? = null,
    val rawResult: String? = null,
    val audit: List<AdbAuditEntry> = emptyList(),
)

/** ADB 连接页状态：真机环境、阶段、配对/连接动作，全部来自真实检测。 */
class AdbViewModel(
    private val container: AppContainer,
    private val context: Context,
) : ViewModel() {
    private val client: AdbClient = container.adbClient
    private val detectedHost = AdbNetwork.detectLanIp(context)

    private val _ui = MutableStateFlow(
        AdbUiState(
            status = client.status.value,
            pairHost = detectedHost,
            connectHost = detectedHost,
            bridge = container.adbBridgeController.state.value,
            audit = AdbAuditLog.recent(context),
        ),
    )
    val ui: StateFlow<AdbUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            client.status.collect { status ->
                _ui.update {
                    it.copy(
                        status = status,
                        // 地址留给用户可改，但保留检测出来的局域网 IP 作为默认基线
                        pairHost = it.pairHost.ifBlank { detectedHost },
                        connectHost = it.connectHost.ifBlank { detectedHost },
                    )
                }
            }
        }
        viewModelScope.launch {
            container.adbBridgeController.state.collect { bridge ->
                _ui.update { it.copy(bridge = bridge) }
            }
        }
    }

    fun onResume() = viewModelScope.launch { client.refresh() }

    fun recheck() = viewModelScope.launch {
        client.refresh()
        _ui.update { it.copy(message = "已按当前真机状态重新检测") }
    }

    fun onPairHost(value: String) = _ui.update { it.copy(pairHost = value) }
    fun onPairPort(value: String) = _ui.update { it.copy(pairPort = value.filter { c -> c.isDigit() }) }
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
        if (s.pairCode.length != 6) {
            _ui.update { it.copy(message = "请填写 6 位配对码") }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null, probeResult = null) }
        val result = client.pair(s.pairHost, port, s.pairCode)
        _ui.update {
            it.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "配对成功。现在回到手机「无线调试」主页查看连接端口，填入下方“连接设备”后再连接。" },
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
        if (s.connectHost.isBlank()) {
            _ui.update { it.copy(message = "请填写设备地址") }
            return@launch
        }
        _ui.update { it.copy(busy = true, message = null, probeResult = null) }
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
        _ui.update { it.copy(message = "已断开连接", probeResult = null) }
    }

    fun probe() = viewModelScope.launch {
        _ui.update { it.copy(busy = true, probeResult = null) }
        val result = client.probe()
        _ui.update { it.copy(busy = false, probeResult = result) }
    }

    fun onRawCommand(value: String) = _ui.update { it.copy(rawCommand = value) }

    fun requestRaw() = viewModelScope.launch {
        val command = _ui.value.rawCommand.trim()
        val req = RawShellRequest(command, CommandSource.USER_CONSOLE)
        val verdict = RawShellPolicy.decide(req)
        if (verdict.decision == RawShellPolicy.Decision.DENY) {
            AdbAuditLog.append(context, req, verdict, CommandResult(false, verdict.reason))
            _ui.update {
                it.copy(
                    message = "命令被拒绝：${verdict.reason}",
                    pendingRaw = null,
                    rawResult = null,
                )
            }
            refreshAudit()
            return@launch
        }
        // 每条 raw 命令必须显式确认；不存在“本次会话不再提示”的跳过通道
        _ui.update { it.copy(pendingRaw = req, message = null) }
    }

    fun confirmRaw() = viewModelScope.launch {
        val req = _ui.value.pendingRaw ?: return@launch
        _ui.update { it.copy(pendingRaw = null) }
        executeRaw(req)
    }

    fun cancelRaw() = _ui.update { it.copy(pendingRaw = null) }

    private suspend fun executeRaw(req: RawShellRequest) {
        _ui.update { it.copy(busy = true, message = null, rawResult = null) }
        val result = container.adbBridgeController.submitRaw(req)
        _ui.update {
            it.copy(
                busy = false,
                rawResult = "${if (result.ok) "执行成功" else "执行失败"}：${result.message}",
                message = if (result.ok) null else result.message,
            )
        }
        refreshAudit()
    }

    fun refreshAudit() = _ui.update { it.copy(audit = AdbAuditLog.recent(context)) }

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
