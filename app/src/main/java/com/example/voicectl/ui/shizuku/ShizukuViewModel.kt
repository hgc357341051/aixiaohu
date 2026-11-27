package com.example.voicectl.ui.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicectl.di.AppContainer
import com.example.voicectl.shizuku.ActivationGuide
import com.example.voicectl.shizuku.CommandResult
import com.example.voicectl.shizuku.GuideAction
import com.example.voicectl.shizuku.InputCommand
import com.example.voicectl.shizuku.ShizukuDebugInfo
import com.example.voicectl.shizuku.ShizukuStatus
import com.example.voicectl.shizuku.describeCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShizukuUiState(
    val status: ShizukuStatus,
    val guide: ActivationGuide,
    val debugInfo: ShizukuDebugInfo,
    val message: String? = null,
    val probeResult: CommandResult? = null,
    val selfTestResult: CommandResult? = null,
    val selfTestCommand: String? = null,
)

/** 页面 onResume / 每个按钮前都会触发客户端重查，保证六态不靠缓存「看起来正常」。 */
class ShizukuViewModel(
    private val container: AppContainer,
    private val context: Context,
) : ViewModel() {
    private val client = container.shizukuClient
    private val bridge get() = container.shizukuBridge

    private val _ui = MutableStateFlow(
        ShizukuUiState(
            status = client.status.value,
            guide = client.activationGuide(),
            debugInfo = ShizukuDebugInfo.from(client.status.value),
        ),
    )
    val ui: StateFlow<ShizukuUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            client.status.collect { status ->
                _ui.update {
                    it.copy(
                        status = status,
                        guide = client.activationGuide(),
                        // 从同一份 status 快照派生，避免与 uiState.status 不一致
                        debugInfo = ShizukuDebugInfo.from(status),
                    )
                }
            }
        }
    }

    fun onResume() = client.refresh()

    fun recheck() {
        client.refresh()
        _ui.update { it.copy(message = "已按当前真机状态重新检测") }
    }

    fun requestPermission() {
        client.requestPermission()
        _ui.update { it.copy(message = "已发起授权请求，请在 Shizuku 弹窗中确认") }
    }

    fun handleGuideAction(action: GuideAction) {
        when (action) {
            GuideAction.OpenDeveloperOptions -> open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            GuideAction.OpenWirelessDebugging -> {
                // SDK 未公开常量，使用官方 action 字符串；无该设置页的设备回退开发者选项。
                if (!open(ACTION_WIRELESS_DEBUGGING)) {
                    open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
            }
            GuideAction.OpenShizukuApp -> {
                val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (intent == null) {
                    _ui.update { it.copy(message = "未检测到 Shizuku，请先按步骤安装") }
                } else {
                    runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { _ui.update { ui -> ui.copy(message = "打开 Shizuku 失败：${it.message}") } }
                }
            }
            GuideAction.OpenDownload -> openUri(DOWNLOAD_URL)
            GuideAction.Recheck -> recheck()
        }
    }

    fun probe() = viewModelScope.launch {
        val result = bridge.probe()
        _ui.update { it.copy(probeResult = result) }
    }

    fun selfTest(command: InputCommand) = viewModelScope.launch {
        val result = bridge.execute(command)
        _ui.update {
            it.copy(selfTestResult = result, selfTestCommand = describeCommand(command))
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun open(action: String): Boolean = runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun openUri(url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val ACTION_WIRELESS_DEBUGGING = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }
}
