package com.aixiaohuadb.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aixiaohuadb.config.Sensitivity
import com.aixiaohuadb.config.WakeMode
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.inference.api.Delegate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val config = container.settingsStore.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = container.config,
    )
    val isDemo: Boolean get() = container.isDemoMode
    val modelVersion: String get() = container.modelVersion
    val activeDelegate = container.stateMachine.state // 供页面观察机态时使用

    fun setWakeEnabled(value: Boolean) = viewModelScope.launch { container.settingsStore.setWakeEnabled(value) }
    fun setWakeMode(value: WakeMode) = viewModelScope.launch { container.settingsStore.setWakeMode(value) }
    fun setSensitivity(value: Sensitivity) = viewModelScope.launch { container.settingsStore.setSensitivity(value) }
    fun setDelegate(value: Delegate) = viewModelScope.launch { container.settingsStore.setDelegate(value) }
    fun setSpeakerEnabled(value: Boolean) = viewModelScope.launch { container.settingsStore.setSpeakerEnabled(value) }
    fun setExitConfirm(value: Boolean) = viewModelScope.launch { container.settingsStore.setExitConfirm(value) }
    fun setTempAllow(value: Boolean) = viewModelScope.launch { container.settingsStore.setTempAllow(value) }
    fun setFeedback(kind: FeedbackKind, value: Boolean) = viewModelScope.launch {
        when (kind) {
            FeedbackKind.TTS -> container.settingsStore.setFeedbackTts(value)
            FeedbackKind.VIBRATION -> container.settingsStore.setFeedbackVibration(value)
            FeedbackKind.OVERLAY -> container.settingsStore.setFeedbackOverlay(value)
        }
    }

    enum class FeedbackKind { TTS, VIBRATION, OVERLAY }
}
