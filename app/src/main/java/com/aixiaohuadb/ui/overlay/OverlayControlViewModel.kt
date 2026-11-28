package com.aixiaohuadb.ui.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.service.BallConfig
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.service.OverlayStore
import com.aixiaohuadb.service.OverlayWidget
import com.aixiaohuadb.voice.VoiceStartReason
import com.aixiaohuadb.voice.VoiceStopReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 悬浮控制设置页：球配置 + 按钮列表 + 宏列表 + 语音槽位绑定，与悬浮服务共享 OverlayStore */
class OverlayControlViewModel(private val container: AppContainer) : ViewModel() {
    val widgets: StateFlow<List<OverlayWidget>> = OverlayStore.widgets
    val ball: StateFlow<BallConfig> = OverlayStore.ball
    val macros: StateFlow<List<MacroConfig>> = OverlayStore.macros
    val pendingMacro: StateFlow<MacroConfig?> = OverlayStore.pendingMacro
    val controlMode: StateFlow<ControlMode> = OverlayStore.controlMode
    val overlayEnabled: StateFlow<Boolean> = OverlayStore.overlayEnabled
    val voiceEnabled: StateFlow<Boolean> = container.voiceLifecycle.voiceEnabled
    val voiceState = container.voiceLifecycle.state
    val bindings = container.macroBindingStore.bindings

    fun setControlMode(mode: ControlMode) = OverlayStore.setControlMode(mode)

    /** 宏库里的「关闭悬浮窗」：停服务 + 持久化，重开时自动拉起服务。 */
    fun setOverlayEnabled(enabled: Boolean) = OverlayStore.setOverlayEnabled(enabled)

    fun requestVoiceEnable(onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val result = container.voiceLifecycle.enable(VoiceStartReason.OVERLAY_SWITCH)
        onResult(result.isSuccess)
    }

    fun toggleVoice(enabled: Boolean) = viewModelScope.launch {
        if (enabled) container.voiceLifecycle.enable(VoiceStartReason.SETTINGS_TOGGLE)
        else {
            container.voiceLifecycle.disable(VoiceStopReason.SETTINGS_TOGGLE)
            if (OverlayStore.controlMode.value == ControlMode.VOICE) {
                OverlayStore.setControlMode(ControlMode.ACCESSIBILITY)
            }
        }
    }

    fun bind(slot: Int, macroId: Long, phrase: String) = viewModelScope.launch {
        container.macroBindingStore.bind(slot, macroId, phrase)
    }

    fun unbind(slot: Int) = viewModelScope.launch { container.macroBindingStore.unbind(slot) }

    fun removeWidget(id: String) = OverlayStore.removeWidget(id)

    fun updateWidgetLive(id: String, sizeDp: Int, alphaPercent: Int) =
        OverlayStore.updateWidgetLive(id, sizeDp, alphaPercent)

    fun setWidgetLocked(id: String, locked: Boolean) = OverlayStore.setWidgetLocked(id, locked)

    fun updateBallLive(sizeDp: Int, alphaPercent: Int) = OverlayStore.updateBallLive(sizeDp, alphaPercent)

    fun setBallVisible(visible: Boolean) = OverlayStore.setBallVisible(visible)

    fun setBallLocked(locked: Boolean) = OverlayStore.setBallLocked(locked)

    fun removeMacro(macroId: Long) = OverlayStore.removeMacro(macroId)

    fun renameMacro(macroId: Long, name: String) = OverlayStore.renameMacro(macroId, name)

    fun savePendingMacro(name: String) {
        val steps = OverlayStore.pendingMacro.value?.steps ?: return
        OverlayStore.saveMacro(name, steps)
    }

    fun discardPendingMacro() = OverlayStore.discardPendingMacro()
}
