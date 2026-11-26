package com.example.voicectl.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.voicectl.config.AppConfig
import com.example.voicectl.di.AppContainer
import com.example.voicectl.domain.VoiceMetrics
import com.example.voicectl.inference.api.ActionLabel
import com.example.voicectl.inference.api.CommandResult
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.inference.api.ObjectLabel
import com.example.voicectl.inference.demo.DemoInferenceFactory
import com.example.voicectl.machine.FeedbackType
import com.example.voicectl.machine.SideEffect
import com.example.voicectl.machine.VoiceEvent
import com.example.voicectl.machine.VoiceState
import com.example.voicectl.service.FloatingBallService
import com.example.voicectl.service.VoiceForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppViewModel(private val container: AppContainer) : ViewModel() {
    data class UiState(
        val voiceState: VoiceState = VoiceState.IDLE,
        val config: AppConfig = AppConfig.DEFAULT,
        val logs: List<String> = emptyList(),
        val toast: String? = null,
        val showExitConfirm: Boolean = false,
        val isDemo: Boolean = true,
        val metrics: VoiceMetrics = VoiceMetrics(),
    )

    private val _ui = MutableStateFlow(
        UiState(config = container.config, isDemo = container.isDemoMode, metrics = container.metrics.value),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val handler = container.createActionHandler { type, text ->
        addLog("${type.name} $text")
        _ui.update { it.copy(toast = text) }
    }

    init {
        viewModelScope.launch {
            container.stateMachine.state.collect { state ->
                _ui.update { it.copy(voiceState = state) }
                container.refreshNotification()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            container.stateMachine.effects.collect { effect ->
                if (effect is SideEffect.RequestConfirmExit) {
                    _ui.update { it.copy(showExitConfirm = true) }
                }
                handler.handle(effect)
            }
        }
        viewModelScope.launch {
            container.settingsStore.configFlow.collect { config ->
                _ui.update { it.copy(config = config, isDemo = container.isDemoMode) }
            }
        }
        viewModelScope.launch {
            container.metrics.collect { metrics ->
                _ui.update { it.copy(metrics = metrics) }
            }
        }
    }

    fun startVoice() {
        container.stateMachine.send(VoiceEvent.Start)
    }

    fun openWindow() {
        container.stateMachine.send(VoiceEvent.Start)
        container.stateMachine.send(VoiceEvent.WakeVerified(0.92f))
    }

    fun pause() = container.stateMachine.send(VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 0.95f))

    fun resume() = container.stateMachine.send(VoiceEvent.ManualResume)

    fun requestExit() = container.stateMachine.send(VoiceEvent.ControlDetected(ControlWord.C1_EXIT_WAKE, 0.95f))

    fun control(word: ControlWord) = container.stateMachine.send(VoiceEvent.ControlDetected(word, 0.95f))

    fun confirmExit() {
        _ui.update { it.copy(showExitConfirm = false) }
        container.stateMachine.send(VoiceEvent.ExitConfirmed)
    }

    fun cancelExit() = _ui.update { it.copy(showExitConfirm = false) }

    fun runCommand(commandId: Int) {
        container.stateMachine.send(
            VoiceEvent.CommandDetected(resultFor(commandId), 0.95f),
        )
    }

    /** 调试页：直接注入流程事件，便于验证 7 态与 C1–C4 */
    fun demoWake() {
        container.stateMachine.send(VoiceEvent.CoarseHit)
        container.stateMachine.send(VoiceEvent.FineHit)
        container.stateMachine.send(VoiceEvent.WakeVerified(0.92f))
    }

    fun demoReject() = container.stateMachine.send(VoiceEvent.WakeRejected)

    fun demoScores(coarse: Float?, fine: Float?, commandId: Int?, control: ControlWord?, speaker: Float?) {
        val factory = container.currentFactory
        if (factory is DemoInferenceFactory) {
            factory.wake.forcedCoarse = coarse
            factory.wake.forcedFine = fine
            factory.command.forcedCommandId = commandId
            factory.control.forcedControl = control
            factory.speaker.forcedSimilarity = speaker
            addLog("DEBUG 注入分数 coarse=$coarse fine=$fine cmd=$commandId ctrl=$control spk=$speaker")
        }
    }

    fun startServices(context: Context) {
        VoiceForegroundService.start(context.applicationContext)
        FloatingBallService.start(context.applicationContext)
    }

    fun dismissToast() = _ui.update { it.copy(toast = null) }

    fun lastDemoFactory(): DemoInferenceFactory? = container.currentFactory as? DemoInferenceFactory

    private fun resultFor(id: Int): CommandResult = when (id) {
        13 -> CommandResult(ActionLabel.A1_SWITCH, ObjectLabel.P6_POSITION, id, 0.95f)
        14 -> CommandResult(ActionLabel.A2_NONE, ObjectLabel.OTHER, id, 0.5f)
        in 7..12 -> CommandResult(
            ActionLabel.A0_SWAP,
            ObjectLabel.entries[ObjectLabel.P0.ordinal + id - 7],
            id,
            0.95f,
        )
        in 0..6 -> CommandResult(ActionLabel.A0_SWAP, ObjectLabel.entries[id], id, 0.95f)
        else -> CommandResult(ActionLabel.A2_NONE, ObjectLabel.OTHER, 14, 0.5f)
    }

    private fun addLog(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        val stamped = "[$time] $line"
        _ui.update { it.copy(logs = (it.logs + stamped).takeLast(40)) }
    }
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) return AppViewModel(container) as T
        throw IllegalArgumentException("未知 ViewModel: ${modelClass.name}")
    }
}
