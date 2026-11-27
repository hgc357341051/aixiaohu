package com.aixiaohuadb.machine

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.ControlWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 10 个机态 + C1 真退出过渡态 */
enum class VoiceState {
    IDLE, LISTEN, VERIFY, CMD_WINDOW, EXECUTE, COOLDOWN,
    PAUSED, RECOG_OFF, MANUAL_SILENT, ERROR, UNLOAD_WAKE,
}

sealed class VoiceEvent {
    data object Start : VoiceEvent()
    data object CoarseHit : VoiceEvent()
    data object FineHit : VoiceEvent()
    data class WakeVerified(val sim: Float) : VoiceEvent()
    data object WakeRejected : VoiceEvent()
    data object SilenceTimeout : VoiceEvent()
    data class ControlDetected(val word: ControlWord, val sim: Float) : VoiceEvent()
    data object ExitConfirmed : VoiceEvent()
    data class CommandDetected(val result: CommandResult, val sim: Float) : VoiceEvent()
    data object Rejected : VoiceEvent()
    data object ExecSucceeded : VoiceEvent()
    data object ExecFailed : VoiceEvent()
    data object CooldownElapsed : VoiceEvent()
    data object PauseTimeout : VoiceEvent()
    data object ManualResume : VoiceEvent()
    data object ExitGame : VoiceEvent()
    data class Error(val cause: Throwable) : VoiceEvent()
}

enum class FeedbackType { SUCCESS, WARN, ERROR, INFO }

sealed class SideEffect {
    data object LoadModels : SideEffect()
    data object UnloadModels : SideEffect()
    data object StartCapture : SideEffect()
    data object StopCapture : SideEffect()
    data class Execute(val commandId: Int) : SideEffect()
    data class Feedback(val type: FeedbackType, val text: String) : SideEffect()
    data object RequestConfirmExit : SideEffect()
    data class Telemetry(val dim: String, val name: String) : SideEffect()
}

/**
 * §7 统一状态机。
 * 不变式：MANUAL_SILENT 下除 ManualResume / ExitGame 外忽略一切事件；
 * C1 执行时先 emit UnloadModels + StopCapture，再进入 MANUAL_SILENT。
 */
class VoiceStateMachine(private val configProvider: () -> AppConfig = { AppConfig.DEFAULT }) {
    constructor(config: AppConfig) : this({ config })

    private val config: AppConfig get() = configProvider()
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(extraBufferCapacity = 128)
    val effects: SharedFlow<SideEffect> = _effects.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var timer: Job? = null
    private var pendingC1 = false
    private var c1ConfirmedOnce = false
    private var errorCount = 0

    fun currentState(): VoiceState = _state.value

    fun send(event: VoiceEvent) = synchronized(lock) {
        val current = _state.value
        if (current == VoiceState.MANUAL_SILENT &&
            event !is VoiceEvent.ManualResume && event !is VoiceEvent.ExitGame
        ) {
            return
        }

        if (event is VoiceEvent.ExitConfirmed) {
            if (pendingC1) {
                pendingC1 = false
                performExitWake()
            }
            return
        }

        if (event is VoiceEvent.Error) {
            onError(event.cause)
            return
        }

        when (current) {
            VoiceState.IDLE -> handleIdle(event)
            VoiceState.LISTEN -> handleListen(event)
            VoiceState.VERIFY -> handleVerify(event)
            VoiceState.CMD_WINDOW -> handleWindow(event)
            VoiceState.EXECUTE -> handleExecute(event)
            VoiceState.COOLDOWN -> handleCooldown(event)
            VoiceState.PAUSED -> handlePaused(event)
            VoiceState.RECOG_OFF -> handleRecogOff(event)
            VoiceState.MANUAL_SILENT -> handleManualSilent(event)
            VoiceState.ERROR -> handleErrorState(event)
            VoiceState.UNLOAD_WAKE -> Unit
        }
    }

    fun close() {
        timer?.cancel()
        scope.cancel()
    }

    // ---------------- 各状态迁移 ----------------

    private fun handleIdle(event: VoiceEvent) {
        when (event) {
            VoiceEvent.Start -> {
                errorCount = 0
                transition(
                    VoiceState.LISTEN,
                    SideEffect.LoadModels,
                    SideEffect.StartCapture,
                    SideEffect.Feedback(FeedbackType.INFO, "语音已开启 · 随时待命"),
                )
            }
            VoiceEvent.CoarseHit, VoiceEvent.FineHit -> transition(VoiceState.VERIFY)
            is VoiceEvent.WakeVerified -> openWindow()
            VoiceEvent.ManualResume -> transition(
                VoiceState.LISTEN, SideEffect.LoadModels, SideEffect.StartCapture,
            )
            VoiceEvent.ExitGame -> emit(SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleListen(event: VoiceEvent) {
        when (event) {
            VoiceEvent.CoarseHit, VoiceEvent.FineHit -> transition(VoiceState.VERIFY)
            is VoiceEvent.WakeVerified -> openWindow()
            VoiceEvent.WakeRejected -> emit(SideEffect.Telemetry("wake", "spk_reject"))
            VoiceEvent.SilenceTimeout -> toCooldown()
            is VoiceEvent.ControlDetected -> handleControl(event)
            is VoiceEvent.CommandDetected -> toExecute(event)
            VoiceEvent.Rejected -> emit(SideEffect.Feedback(FeedbackType.WARN, "未识别，请重试"))
            VoiceEvent.ExitGame -> {
                transition(VoiceState.IDLE, SideEffect.StopCapture)
            }
            VoiceEvent.ManualResume -> Unit
            else -> Unit
        }
    }

    private fun handleVerify(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.WakeVerified -> openWindow()
            VoiceEvent.WakeRejected -> {
                emit(SideEffect.Telemetry("wake", "spk_reject"))
                transition(VoiceState.LISTEN)
            }
            VoiceEvent.CoarseHit, VoiceEvent.FineHit -> Unit
            is VoiceEvent.ControlDetected -> handleControl(event)
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleWindow(event: VoiceEvent) {
        when (event) {
            VoiceEvent.SilenceTimeout -> toCooldown()
            is VoiceEvent.ControlDetected -> handleControl(event)
            is VoiceEvent.CommandDetected -> toExecute(event)
            VoiceEvent.Rejected -> emit(SideEffect.Feedback(FeedbackType.WARN, "未识别，请再说一次"))
            VoiceEvent.CoarseHit, VoiceEvent.FineHit -> Unit
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleExecute(event: VoiceEvent) {
        when (event) {
            VoiceEvent.ExecSucceeded -> {
                emit(SideEffect.Feedback(FeedbackType.SUCCESS, "执行成功"))
                toCooldown()
            }
            VoiceEvent.ExecFailed -> {
                emit(SideEffect.Feedback(FeedbackType.ERROR, "执行失败，已停止后续步骤"))
                toCooldown()
            }
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleCooldown(event: VoiceEvent) {
        when (event) {
            VoiceEvent.CooldownElapsed -> transition(VoiceState.IDLE)
            VoiceEvent.ManualResume -> transition(VoiceState.LISTEN)
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handlePaused(event: VoiceEvent) {
        when (event) {
            VoiceEvent.PauseTimeout -> transition(
                VoiceState.LISTEN,
                SideEffect.Feedback(FeedbackType.INFO, "已自动恢复识别"),
            )
            is VoiceEvent.ControlDetected -> handleControl(event)
            VoiceEvent.ManualResume -> transition(
                VoiceState.LISTEN,
                SideEffect.Feedback(FeedbackType.INFO, "已手动恢复识别"),
            )
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleRecogOff(event: VoiceEvent) {
        when (event) {
            VoiceEvent.CoarseHit, VoiceEvent.FineHit -> transition(VoiceState.VERIFY)
            is VoiceEvent.WakeVerified -> {
                transition(
                    VoiceState.CMD_WINDOW,
                    SideEffect.Feedback(FeedbackType.WARN, "识别已关闭，请说「恢复语音」"),
                )
            }
            is VoiceEvent.ControlDetected -> handleControl(event)
            VoiceEvent.ManualResume -> transition(VoiceState.LISTEN)
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    private fun handleManualSilent(event: VoiceEvent) {
        when (event) {
            VoiceEvent.ManualResume -> transition(
                VoiceState.LISTEN,
                SideEffect.LoadModels,
                SideEffect.StartCapture,
                SideEffect.Feedback(FeedbackType.SUCCESS, "已手动恢复语音"),
            )
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE)
            else -> Unit
        }
    }

    private fun handleErrorState(event: VoiceEvent) {
        when (event) {
            VoiceEvent.ManualResume, VoiceEvent.Start -> {
                errorCount = 0
                transition(
                    VoiceState.LISTEN,
                    SideEffect.LoadModels,
                    SideEffect.StartCapture,
                )
            }
            VoiceEvent.ExitGame -> transition(VoiceState.IDLE, SideEffect.StopCapture)
            else -> Unit
        }
    }

    // ---------------- 控制词 / 公共动作 ----------------

    private fun handleControl(event: VoiceEvent.ControlDetected) {
        when (event.word) {
            ControlWord.C0_NONE -> Unit
            ControlWord.C1_EXIT_WAKE -> {
                if (config.controlWords.c1ConfirmOnFirst && !c1ConfirmedOnce && !pendingC1) {
                    pendingC1 = true
                    emit(SideEffect.RequestConfirmExit)
                } else {
                    pendingC1 = false
                    performExitWake()
                }
            }
            ControlWord.C2_EXIT_RECOG -> {
                emit(SideEffect.Telemetry("control", "C2_exit_recog"))
                transition(
                    VoiceState.RECOG_OFF,
                    SideEffect.Feedback(FeedbackType.WARN, "识别已关闭 · 唤醒仍开启"),
                )
            }
            ControlWord.C3_PAUSE -> {
                emit(SideEffect.Telemetry("control", "C3_pause"))
                transition(
                    VoiceState.PAUSED,
                    SideEffect.Feedback(FeedbackType.INFO, "识别已暂停 · ${config.controlWords.c3TimeoutS}s 后自动恢复"),
                )
            }
            ControlWord.C4_RESUME -> {
                emit(SideEffect.Telemetry("control", "C4_resume"))
                transition(
                    VoiceState.LISTEN,
                    SideEffect.Feedback(FeedbackType.SUCCESS, "已恢复识别"),
                )
            }
        }
    }

    /** C1 真退出：卸载模型 + 停采集 + 仅手动恢复 */
    private fun performExitWake() {
        c1ConfirmedOnce = true
        emit(SideEffect.Telemetry("control", "C1_exit_wake"))
        transition(
            VoiceState.UNLOAD_WAKE,
            SideEffect.UnloadModels,
            SideEffect.StopCapture,
            SideEffect.Feedback(FeedbackType.WARN, "已退出：模型真实卸载，点悬浮球可恢复"),
        )
        transition(VoiceState.MANUAL_SILENT)
    }

    private fun openWindow() {
        emit(SideEffect.Telemetry("wake", "wake_ok"))
        transition(VoiceState.CMD_WINDOW)
    }

    private fun toExecute(event: VoiceEvent.CommandDetected) {
        emit(SideEffect.Telemetry("command", "id_${event.result.commandId}"))
        transition(
            VoiceState.EXECUTE,
            SideEffect.Execute(event.result.commandId),
            SideEffect.Feedback(FeedbackType.INFO, "识别「${commandName(event.result.commandId)}」· 执行中"),
        )
    }

    private fun toCooldown() = transition(VoiceState.COOLDOWN)

    private fun onError(cause: Throwable) {
        errorCount += 1
        emit(SideEffect.Telemetry("error", cause.javaClass.simpleName))
        if (errorCount >= 3) {
            emit(SideEffect.Feedback(FeedbackType.ERROR, "连续异常，语音已关闭"))
            performExitWake()
        } else {
            transition(
                VoiceState.ERROR,
                SideEffect.Feedback(FeedbackType.ERROR, "发生异常：${cause.message ?: "未知错误"}"),
            )
        }
    }

    private fun commandName(id: Int): String = when (id) {
        0 -> "换名刀"
        1 -> "换复活甲"
        2 -> "换辉月"
        3 -> "换吸血书"
        4 -> "换吸血刀"
        5 -> "换冰甲"
        6 -> "换血魔"
        in 7..12 -> "换${id - 6}号位"
        13 -> "切换位置"
        else -> "未识别"
    }

    // ---------------- 基础工具 ----------------

    private fun transition(next: VoiceState, vararg effects: SideEffect) {
        timer?.cancel()
        _state.value = next
        effects.forEach { emit(it) }
        scheduleTimer(next)
    }

    private fun emit(effect: SideEffect) {
        _effects.tryEmit(effect)
    }

    private fun scheduleTimer(state: VoiceState) {
        timer = when (state) {
            VoiceState.CMD_WINDOW -> launchTimer((config.decode.cmdWindowS * 1000f).toLong()) {
                if (_state.value == VoiceState.CMD_WINDOW) send(VoiceEvent.SilenceTimeout)
            }
            VoiceState.COOLDOWN -> launchTimer((config.decode.cooldownS * 1000f).toLong()) {
                if (_state.value == VoiceState.COOLDOWN) send(VoiceEvent.CooldownElapsed)
            }
            VoiceState.PAUSED -> if (config.controlWords.c3AutoResume) {
                launchTimer(config.controlWords.c3TimeoutS * 1000L) {
                    if (_state.value == VoiceState.PAUSED) send(VoiceEvent.PauseTimeout)
                }
            } else null
            else -> null
        }
    }

    private fun launchTimer(delayMs: Long, block: () -> Unit): Job = scope.launch {
        delay(delayMs)
        block()
    }
}
