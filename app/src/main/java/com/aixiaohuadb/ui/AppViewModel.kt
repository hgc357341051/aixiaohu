package com.aixiaohuadb.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.domain.VoiceMetrics
import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.inference.api.ObjectLabel
import com.aixiaohuadb.inference.demo.DemoInferenceFactory
import com.aixiaohuadb.machine.FeedbackType
import com.aixiaohuadb.machine.SideEffect
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.service.OverlayControlService
import com.aixiaohuadb.voice.VoiceLifecycleState
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
        val voiceLifecycle: VoiceLifecycleState = VoiceLifecycleState.DISABLED,
    )

    private val _ui = MutableStateFlow(
        UiState(config = container.config, isDemo = container.isDemoMode, metrics = container.metrics.value),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val handler = container.createActionHandler { type, text ->
        addLog("${type.name} $text")
        // 三个反馈开关的真实消费：TTS/震动/浮层提示由 feedbackDispatcher 按 config 分流。
        // 浮层提示（toast）是否显示也交给它决定，关闭 feedbackOverlay 后不再遮挡游戏 HUD。
        container.feedbackDispatcher.dispatch(type, text) { hint ->
            _ui.update { it.copy(toast = hint) }
        }
    }

    init {
        viewModelScope.launch {
            container.stateMachine.state.collect { state ->
                _ui.update { it.copy(voiceState = state) }
                container.refreshNotification()
            }
        }
        viewModelScope.launch {
            container.voiceLifecycle.state.collect { lifecycle ->
                _ui.update { it.copy(voiceLifecycle = lifecycle) }
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

    fun openWindow() {
        // 语音只能由设置开关 / 悬浮窗语音模式显式启动；这里仅在已运行时打开指令窗。
        if (container.voiceLifecycle.state.value != VoiceLifecycleState.RUNNING) return
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
            VoiceEvent.CommandDetected(resultForSlot(commandId), 0.95f),
        )
    }

    /** 手动面板 / 悬浮宏按钮：直接回放用户宏，不经过语音状态机 */
    fun runMacro(macroId: Long) = viewModelScope.launch {
        val result = container.executeMacro(macroId)
        addLog("MACRO $macroId ${if (result.success) "ok" else "fail"}: ${result.message}")
        _ui.update { it.copy(toast = if (result.success) "宏执行成功" else "宏执行失败：${result.message}") }
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
        // 语音通知由 VoiceLifecycleController.enable() 按需拉起，这里只启动悬浮控制服务。
        OverlayControlService.start(context.applicationContext)
    }

    fun dismissToast() = _ui.update { it.copy(toast = null) }

    fun lastDemoFactory(): DemoInferenceFactory? = container.currentFactory as? DemoInferenceFactory

    /** 调试注入：commandId 只代表语音槽位，不再携带任何内置固定指令语义 */
    private fun resultForSlot(slot: Int): CommandResult = CommandResult(
        ActionLabel.A0_SWAP,
        ObjectLabel.OTHER,
        slot.coerceIn(0, 14),
        0.95f,
    )

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
