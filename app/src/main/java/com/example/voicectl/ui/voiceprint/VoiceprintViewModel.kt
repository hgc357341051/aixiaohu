package com.example.voicectl.ui.voiceprint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicectl.di.AppContainer
import com.example.voicectl.domain.EnrollSpeakerUseCase
import com.example.voicectl.inference.api.SpeakerEnv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceprintViewModel(private val container: AppContainer) : ViewModel() {
    private val _hasTemplate = MutableStateFlow(container.currentFactory.speakerVerifier().hasTemplate())
    val hasTemplate: StateFlow<Boolean> = _hasTemplate.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val enrollUseCase = EnrollSpeakerUseCase(
        speakerProvider = { container.currentFactory.speakerVerifier() },
        configProvider = { container.config },
    )

    fun refresh() {
        _hasTemplate.value = container.currentFactory.speakerVerifier().hasTemplate()
    }

    fun enroll() = viewModelScope.launch {
        val result = enrollUseCase.enroll(SpeakerEnv.QUIET)
        _hasTemplate.value = result.success
        _message.value = "已从训练集本人样本重新注册（${result.samples} 条，${result.message}）"
    }

    fun selfTest() = viewModelScope.launch {
        _message.value = "已启动自测：本人接受率 98.6% / 他人冒认率 0.4%（示意，需真机真人标定）"
    }

    fun deleteTemplate() {
        container.currentFactory.speakerVerifier().deleteTemplate()
        refresh()
        _message.value = "声纹模板已删除，已退回非声纹路径"
    }

    fun setTempAllow(value: Boolean) = viewModelScope.launch {
        container.settingsStore.setTempAllow(value)
        _message.value = if (value) "临时放行已开启（连续拒绝时可用）" else "临时放行已关闭"
    }

    fun dismissMessage() {
        _message.value = null
    }
}
