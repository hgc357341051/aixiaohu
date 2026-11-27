package com.aixiaohuadb.voice

import android.content.Context
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceStateMachine
import com.aixiaohuadb.service.VoiceForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class VoiceLifecycleState { DISABLED, STOPPED, STARTING, RUNNING, PAUSED, DESTROYING }
enum class VoiceStartReason { SETTINGS_TOGGLE, OVERLAY_SWITCH }
enum class VoiceStopReason { SETTINGS_TOGGLE, MODE_LEAVE, C1_EXIT, ERROR_LIMIT, PROCESS_TEARDOWN }
enum class VoiceAction {
    WAKE, RECOGNIZE, PAUSE_RESUME_VIA_BALL, VOICE_NOTIFICATION_ACTION, QUICK_TILE,
    /** 常驻通知前台服务启动/重启守卫：只有 RUNNING/PAUSED 允许 */
    FOREGROUND_START,
}

/** 语音开关的持久化边界；默认 false 由实现方保证 */
interface VoiceSettings {
    val voiceEnabledFlow: Flow<Boolean>
    suspend fun setVoiceEnabled(value: Boolean)
}

/** 语音识别帧链路，只有生命周期控制器能启停 */
interface VoicePipeline {
    fun start(scope: CoroutineScope)
    fun stop()
    fun reset()
}

/** 麦克风采集边界；close 释放物理资源后可重新 start，dispose 为进程级销毁 */
interface VoiceAudio {
    val isCapturing: Boolean
    fun start(): Boolean
    fun stop()
    fun close()
    fun dispose()
}

/** 模型所有权：启用时加载，禁用时真实卸载 */
interface VoiceModelOwner {
    fun loadAll(): Result<Unit>
    fun unloadAll()
    fun reset()
}

/**
 * 语音子系统唯一生命周期入口。
 *
 * 红线：
 * 1. voiceEnabled(DataStore voice_enabled_v1, 默认 false) 是唯一真源；ControlMode.VOICE 只是用户选择。
 * 2. 冷启动 restorePersistedState() 只读取一次标志位并进入 STOPPED（显示“点此启动”），
 *    不常驻 collect，不 attach 麦克风、不加载模型、不启动常驻通知。
 * 3. 只有模型加载成功 + 麦克风采集成功后才落盘 voiceEnabled=true；任何失败都回滚并释放资源。
 * 4. disable 会立刻翻转 executionToken 打断正在执行的长宏，并用 epoch 拒绝排队中的陈旧 enable。
 */
class VoiceLifecycleController(
    private val context: Context?,
    private val settingsStore: VoiceSettings,
    private val pipeline: VoicePipeline,
    private val audioCapture: VoiceAudio,
    private val stateMachine: VoiceStateMachine,
    private val modelOwner: VoiceModelOwner,
    private val scope: CoroutineScope,
    private val onTelemetry: (String, String) -> Unit = { _, _ -> Unit },
    private val onStateChanged: (VoiceLifecycleState) -> Unit = {},
    /** 测试 seam：在 enable 捕获 epoch 后、抢锁前挂起，用于构造“陈旧 enable”竞态 */
    private val onBeforeEnable: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(VoiceLifecycleState.DISABLED)
    val state: StateFlow<VoiceLifecycleState> = _state.asStateFlow()

    private val _voiceEnabled = MutableStateFlow(false)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled.asStateFlow()

    private val mutex = Mutex()
    private var restoreJob: Job? = null

    /** 每次 disable/forceDisabled 递增；enable 捕获的 epoch 过期后一律拒绝 */
    private val epoch = AtomicInteger(0)

    /** 执行取消令牌：disable 时 cancel，执行器每步检查 */
    private val executionToken = AtomicReference<Job>(Job())
    val isExecutionCancelled: () -> Boolean get() = { executionToken.get().isCancelled }

    /** 冷启动只读一次落盘开关；即使残留 true 也仅进 STOPPED，绝不自动启动 */
    fun restorePersistedState() {
        restoreJob?.cancel()
        val observedEpoch = epoch.get()
        restoreJob = scope.launch {
            val enabled = runCatching { settingsStore.voiceEnabledFlow.first() }.getOrDefault(false)
            // DataStore 首帧若晚于 enable/disable，必须丢弃，不能反向覆盖 state/voiceEnabled
            if (observedEpoch != epoch.get()) return@launch
            if (_state.value != VoiceLifecycleState.DISABLED) return@launch
            _voiceEnabled.value = enabled
            setState(if (enabled) VoiceLifecycleState.STOPPED else VoiceLifecycleState.DISABLED)
        }
    }

    suspend fun enable(reason: VoiceStartReason): Result<Unit> {
        val observedEpoch = epoch.get()
        // 测试可在此挂起，模拟 enable 排队期间用户点了 disable
        onBeforeEnable()
        return mutex.withLock {
            runCatching {
                if (observedEpoch != epoch.get()) error("语音状态已变更，启动请求已过期")
                when (_state.value) {
                    VoiceLifecycleState.DESTROYING -> error("语音控制器已销毁，无法重新启用")
                    VoiceLifecycleState.STARTING -> return@runCatching
                    VoiceLifecycleState.RUNNING -> return@runCatching
                    VoiceLifecycleState.PAUSED -> {
                        if (!resumeLocked()) rollback(VoiceLifecycleState.PAUSED, IllegalStateException("麦克风恢复失败"))
                        return@runCatching
                    }
                    else -> Unit
                }
                val previous = _state.value
                setState(VoiceLifecycleState.STARTING)
                cancelExecution()

                // 先加载模型：失败时绝不允许 voiceEnabled 残留 true
                val loaded = modelOwner.loadAll()
                if (loaded.isFailure) {
                    val failure = loaded.exceptionOrNull() ?: IllegalStateException("模型加载失败")
                    rollback(previous, failure)
                    throw failure
                }
                stateMachine.enterVoiceMode()
                pipeline.start(scope)
                // 采集失败同样回滚：卸载模型、停 pipeline、不起通知
                if (!audioCapture.start()) {
                    val failure = IllegalStateException("麦克风启动失败")
                    rollback(previous, failure)
                    throw failure
                }

                // 所有物理资源就绪后才落盘 true
                settingsStore.setVoiceEnabled(true)
                _voiceEnabled.value = true
                resetExecutionToken()
                // 先置 RUNNING 再启动通知，避免服务入口守卫把自己停掉
                setState(VoiceLifecycleState.RUNNING)
                context?.let(VoiceForegroundService::start)
                onTelemetry("voice", "enable_${reason.name}")
            }.onFailure { t ->
                if (t is CancellationException) throw t
            }
        }
    }

    suspend fun disable(reason: VoiceStopReason): Result<Unit> {
        // 抢锁前先递增 epoch：排队中的 enable 立即失效，关掉后不会被再次拉起
        epoch.incrementAndGet()
        return mutex.withLock { runCatching { disableInternal(reason) } }
    }

    suspend fun pause() = mutex.withLock {
        if (_state.value != VoiceLifecycleState.RUNNING) return@withLock
        stateMachine.send(VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f))
        setState(VoiceLifecycleState.PAUSED)
        onTelemetry("voice", "pause")
    }

    suspend fun resume() {
        val observedEpoch = epoch.get()
        mutex.withLock {
            if (observedEpoch != epoch.get()) return
            when (_state.value) {
                VoiceLifecycleState.PAUSED -> {
                    if (!resumeLocked()) rollback(VoiceLifecycleState.PAUSED, IllegalStateException("麦克风恢复失败"))
                }
                VoiceLifecycleState.STOPPED -> {
                    cancelExecution()
                    val loaded = modelOwner.loadAll()
                    if (loaded.isFailure) {
                        rollback(VoiceLifecycleState.STOPPED,
                            loaded.exceptionOrNull() ?: IllegalStateException("模型加载失败"))
                        return@withLock
                    }
                    stateMachine.enterVoiceMode()
                    pipeline.start(scope)
                    if (!audioCapture.start()) {
                        rollback(VoiceLifecycleState.STOPPED, IllegalStateException("麦克风启动失败"))
                        return@withLock
                    }
                    settingsStore.setVoiceEnabled(true)
                    _voiceEnabled.value = true
                    resetExecutionToken()
                    setState(VoiceLifecycleState.RUNNING)
                    context?.let(VoiceForegroundService::start)
                    onTelemetry("voice", "resume_from_stopped")
                }
                else -> Unit
            }
        }
    }

    private fun resumeLocked(): Boolean {
        stateMachine.send(VoiceEvent.ManualResume)
        if (!audioCapture.start()) return false
        resetExecutionToken()
        setState(VoiceLifecycleState.RUNNING)
        return true
    }

    /**
     * 启动失败回滚：物理资源全部释放，voiceEnabled 落盘 false，
     * 机态回到启动前状态（DISABLED→DISABLED，其它→MANUAL_SILENT），不起常驻通知。
     */
    private suspend fun rollback(previous: VoiceLifecycleState, failure: Throwable) {
        onTelemetry("voice", "enable_failed_${failure::class.simpleName}")
        pipeline.stop()
        audioCapture.stop()
        audioCapture.close()
        modelOwner.unloadAll()
        cancelExecution()
        context?.let(VoiceForegroundService::stop)
        settingsStore.setVoiceEnabled(false)
        _voiceEnabled.value = false
        // PAUSED 恢复失败不能停在 PAUSED：那会继续放行 FOREGROUND_START / 通知动作。
        // 采集资源已全部释放、voiceEnabled 已落盘 false，机态必须同步收敛到 DISABLED。
        val target = when (previous) {
            VoiceLifecycleState.PAUSED,
            VoiceLifecycleState.STARTING,
            VoiceLifecycleState.DISABLED,
            VoiceLifecycleState.DESTROYING -> VoiceLifecycleState.DISABLED
            else -> previous
        }
        if (target == VoiceLifecycleState.DISABLED) stateMachine.enterDisabled()
        else stateMachine.enterManualMode()
        setState(target)
    }

    /** 关闭副作用严格顺序：取消执行 → 状态机 → pipeline → 麦克风 → 模型 → 通知 → 落盘 → 状态 */
    private suspend fun disableInternal(reason: VoiceStopReason) {
        if (_state.value == VoiceLifecycleState.DISABLED ||
            _state.value == VoiceLifecycleState.DESTROYING
        ) return
        onTelemetry("voice", "disable_${reason.name}")
        cancelExecution()
        setState(VoiceLifecycleState.DESTROYING)
        stateMachine.enterDisabled()
        pipeline.stop()
        audioCapture.stop()
        audioCapture.close()
        modelOwner.unloadAll()
        context?.let(VoiceForegroundService::stop)
        settingsStore.setVoiceEnabled(false)
        _voiceEnabled.value = false
        onTelemetry("voice", "disabled")
        setState(VoiceLifecycleState.DISABLED)
    }

    /** 同步强制关闭：进程/错误路径下不等待调度，仍然物理释放 */
    fun forceDisabled() {
        if (_state.value == VoiceLifecycleState.DISABLED ||
            _state.value == VoiceLifecycleState.DESTROYING
        ) return
        epoch.incrementAndGet()
        cancelExecution()
        setState(VoiceLifecycleState.DESTROYING)
        stateMachine.enterDisabled()
        pipeline.stop()
        audioCapture.stop()
        audioCapture.close()
        modelOwner.unloadAll()
        context?.let(VoiceForegroundService::stop)
        _voiceEnabled.value = false
        scope.launch { runCatching { settingsStore.setVoiceEnabled(false) } }
        setState(VoiceLifecycleState.DISABLED)
    }

    /** C4 自恢复与所有语音入口的总守卫：DISABLED 吸收一切事件（含 ManualResume） */
    fun isVoiceActionAllowed(action: VoiceAction): Boolean = when (_state.value) {
        VoiceLifecycleState.RUNNING -> true
        VoiceLifecycleState.PAUSED ->
            action == VoiceAction.PAUSE_RESUME_VIA_BALL ||
                action == VoiceAction.VOICE_NOTIFICATION_ACTION ||
                action == VoiceAction.FOREGROUND_START
        VoiceLifecycleState.STOPPED -> action == VoiceAction.QUICK_TILE
        VoiceLifecycleState.STARTING, VoiceLifecycleState.DISABLED, VoiceLifecycleState.DESTROYING -> false
    }

    fun close() {
        restoreJob?.cancel()
        epoch.incrementAndGet()
        forceDisabled()
        setState(VoiceLifecycleState.DESTROYING)
        audioCapture.dispose()
    }

    private fun cancelExecution() {
        executionToken.get().cancel()
    }

    private fun resetExecutionToken() {
        executionToken.set(Job())
    }

    private fun setState(next: VoiceLifecycleState) {
        if (_state.value == next) return
        _state.value = next
        onStateChanged(next)
    }
}
