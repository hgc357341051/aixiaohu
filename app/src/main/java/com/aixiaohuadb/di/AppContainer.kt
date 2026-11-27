package com.aixiaohuadb.di

import android.content.Context
import com.aixiaohuadb.adb.AdbBridgeController
import com.aixiaohuadb.adb.AdbClient
import com.aixiaohuadb.adb.AdbInputExecutor
import com.aixiaohuadb.adb.AdbPhase
import com.aixiaohuadb.audio.AudioCapture
import com.aixiaohuadb.audio.FeatureExtractor
import com.aixiaohuadb.audio.FrontEnd
import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.config.SettingsStore
import com.aixiaohuadb.config.WakeMode
import com.aixiaohuadb.domain.RecognitionPipeline
import com.aixiaohuadb.domain.RecognizeUseCase
import com.aixiaohuadb.domain.VoiceMetrics
import com.aixiaohuadb.domain.WakeUseCase
import com.aixiaohuadb.exec.DisplaySize
import com.aixiaohuadb.exec.AccessibilityExecutor
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.ExecResult
import com.aixiaohuadb.exec.ExecutionGuard
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.Executor
import com.aixiaohuadb.exec.ManualExecutionToken
import com.aixiaohuadb.exec.ReadOnlyExecutor
import com.aixiaohuadb.exec.executionBudgetMs
import com.aixiaohuadb.inference.api.InferenceFactory
import com.aixiaohuadb.inference.demo.DemoInferenceFactory
import com.aixiaohuadb.inference.tflite.TfliteInferenceFactory
import com.aixiaohuadb.machine.ControlActionHandler
import com.aixiaohuadb.machine.FeedbackType
import com.aixiaohuadb.machine.SideEffect
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.VoiceStateMachine
import com.aixiaohuadb.macro.MacroBindingStore
import com.aixiaohuadb.monitor.Telemetry
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.service.MacroResolver
import com.aixiaohuadb.service.OverlayStore
import com.aixiaohuadb.service.VoiceAccessibilityService
import com.aixiaohuadb.service.VoiceForegroundService
import com.aixiaohuadb.voice.VoiceLifecycleController
import com.aixiaohuadb.voice.VoiceLifecycleState
import com.aixiaohuadb.voice.VoiceModelOwner
import com.aixiaohuadb.voice.VoiceStartReason
import com.aixiaohuadb.voice.VoiceStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 手写依赖容器：无 Hilt/Koin，App 单例持有；上层只依赖抽象接口 */
class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 模式切换副作用串行化：来回切换时后到的过期请求不能与在途请求交错 */
    private val modeApplyMutex = Mutex()
    @Volatile
    private var lastAppliedMode: ControlMode? = null

    val settingsStore = SettingsStore(context)
    val telemetry = Telemetry()
    val frontEnd = FrontEnd()
    val featureExtractor = FeatureExtractor()
    private val tfliteFactory = TfliteInferenceFactory(context)

    private val _metrics = MutableStateFlow(VoiceMetrics())
    val metrics: StateFlow<VoiceMetrics> = _metrics.asStateFlow()

    @Volatile
    var config: AppConfig = AppConfig.DEFAULT
        private set

    @Volatile
    private var inferenceFactory: InferenceFactory = DemoInferenceFactory()

    /** 语音指令槽位 → 用户宏；推理层不感知宏内容 */
    val macroBindingStore = MacroBindingStore(context)

    val stateMachine = VoiceStateMachine(
        { config },
        { slot ->
            macroBindingStore.bindings.value
                .firstOrNull { it.slot == slot && it.enabled }?.phrase ?: "宏 ${slot + 1}"
        },
    )
    val audioCapture = AudioCapture(context, config)
    val coordinateMapper = CoordinateMapper(config.exec.coordBase)

    /** Demo/TFLite 工厂对外只读暴露，调试页显示实际生效实现 */
    val currentFactory: InferenceFactory get() = inferenceFactory
    val isDemoMode: Boolean get() = inferenceFactory is DemoInferenceFactory
    val modelVersion: String get() = if (isDemoMode) "demo-1.0.0" else tfliteFactory.modelVersion

    /** 内嵌 ADB 客户端与强类型输入桥：唯一的备用执行链路，不依赖外部应用 */
    val adbClient: AdbClient = AdbClient(context.applicationContext, ::screenSize)
    val adbBridgeController = AdbBridgeController(
        context.applicationContext,
        adbClient,
        scope,
        { config.exec.heartbeatS },
        { config.exec.reconnectMax },
    )

    /** 语音禁用时立即翻转，两个执行器每步检查；仅语音链使用，手动回放绝不共用 */
    private val voiceExecutionGuard = ExecutionGuard { voiceLifecycle.isExecutionCancelled() }

    /**
     * 按 guard 构造一条完整执行链。语音链与手动链各自独立构造，
     * 保证 disable 只能打断语音宏，不能影响用户从面板/设置发起的手动回放。
     */
    private fun buildExecutorChain(guard: ExecutionGuard): ExecutorChain = ExecutorChain(
        listOf(
            AccessibilityExecutor(
                { VoiceAccessibilityService.instance },
                coordinateMapper,
                ::screenSize,
                guard = guard,
            ),
            AdbInputExecutor(
                client = adbClient,
                mapper = coordinateMapper,
                screenSizeProvider = ::screenSize,
                isAvailableProvider = { adbClient.status.value.phase == AdbPhase.CONNECTED },
                guard = guard,
            ),
            ReadOnlyExecutor,
        ),
    )

    /** 语音链路：生命周期取消令牌可在 disable 时打断执行中的宏 */
    private val executorChain = buildExecutorChain(voiceExecutionGuard)

    /** 当前显示尺寸：以 WindowManager 为准，避免后台进程资源停留在旧方向 */
    private fun screenSize(): Pair<Int, Int> = DisplaySize.current(context)

    private val wakeUseCase = WakeUseCase(
        wakeProvider = { currentFactory.wakeDetector() },
        speakerProvider = { currentFactory.speakerVerifier() },
        configProvider = { config },
    )
    private val recognizeUseCase = RecognizeUseCase(
        controlProvider = { currentFactory.controlClassifier() },
        commandProvider = { currentFactory.commandClassifier() },
        speakerProvider = { currentFactory.speakerVerifier() },
        configProvider = { config },
    )
    private val recognitionPipeline = RecognitionPipeline(
        audioCapture = audioCapture,
        frontEnd = frontEnd,
        featureExtractor = featureExtractor,
        wakeUseCase = wakeUseCase,
        recognizeUseCase = recognizeUseCase,
        stateMachine = stateMachine,
        configProvider = { config },
        onMetrics = { publishMetrics(it) },
        bindingStore = macroBindingStore,
    )

    private val modelOwner = object : VoiceModelOwner {
        override fun loadAll(): Result<Unit> = inferenceFactory.loadAll(config.preferredDelegate)
        override fun unloadAll() = inferenceFactory.unloadAll()
        override fun reset() = recognitionPipeline.reset()
    }

    /** 语音子系统唯一生命周期入口；只有它能启停 pipeline/采集/模型/通知 */
    val voiceLifecycle = VoiceLifecycleController(
        context = context.applicationContext,
        settingsStore = settingsStore,
        pipeline = recognitionPipeline,
        audioCapture = audioCapture,
        stateMachine = stateMachine,
        modelOwner = modelOwner,
        scope = scope,
        onTelemetry = { dim, name -> telemetry.event(dim, name) },
        onStateChanged = { updateRuntimeMetrics() },
    )

    fun initialize() {
        // 冷启动只读取持久化开关，绝不 pipeline.start / 麦克风 attach / 模型加载
        voiceLifecycle.restorePersistedState()
        scope.launch {
            settingsStore.configFlow.collect { updateConfig(it) }
        }
        scope.launch {
            adbClient.status.collect { updateRuntimeMetrics() }
        }
    }

    private fun updateConfig(next: AppConfig) {
        val previous = config
        val needReload = previous.preferredDelegate != next.preferredDelegate ||
            previous.useDemoModels != next.useDemoModels
        config = next
        updateRuntimeMetrics()
        if (!needReload) return
        // 语音禁用时只更新 config 值，不重载模型（避免后台“隐身”加载）
        if (voiceLifecycle.state.value == VoiceLifecycleState.DISABLED ||
            voiceLifecycle.state.value == VoiceLifecycleState.DESTROYING
        ) return
        inferenceFactory.unloadAll()
        inferenceFactory = selectFactory()
        if (voiceLifecycle.state.value == VoiceLifecycleState.RUNNING) loadModels()
    }

    private fun selectFactory(): InferenceFactory =
        if (!config.useDemoModels && tfliteFactory.modelsAvailable()) tfliteFactory else DemoInferenceFactory()

    /** 加载模型；TFLite 加载失败时自动降级 Demo，保证业务不崩 */
    fun loadModels() {
        if (voiceLifecycle.state.value == VoiceLifecycleState.DISABLED ||
            voiceLifecycle.state.value == VoiceLifecycleState.DESTROYING
        ) return
        recognitionPipeline.reset()
        val result = inferenceFactory.loadAll(config.preferredDelegate)
        if (result.isFailure && inferenceFactory !is DemoInferenceFactory) {
            inferenceFactory.unloadAll()
            inferenceFactory = DemoInferenceFactory()
            inferenceFactory.loadAll(config.preferredDelegate)
            telemetry.event("model", "fallback_to_demo")
        }
        updateRuntimeMetrics()
    }

    /** C1 真退出：卸载模型并释放麦克风 */
    fun unloadModels() {
        recognitionPipeline.reset()
        inferenceFactory.unloadAll()
        stopCapture()
    }

    fun startCapture() {
        if (voiceLifecycle.state.value == VoiceLifecycleState.DISABLED ||
            voiceLifecycle.state.value == VoiceLifecycleState.DESTROYING
        ) return
        if (config.voiceWakeup.mode != WakeMode.MANUAL_SILENT) {
            audioCapture.start()
        }
        updateRuntimeMetrics()
    }

    fun stopCapture() {
        audioCapture.stop()
        updateRuntimeMetrics()
    }

    fun refreshNotification() {
        VoiceForegroundService.runningInstance?.refresh()
    }

    /**
     * 控制模式副作用收敛：VOICE 走生命周期 enable，ACCESSIBILITY/ADB 走 disable，
     * ADB 模式额外起前台长连接。[fromRestore] 为真时只做收敛，不启动任何服务/语音。
     */
    fun applyControlMode(mode: ControlMode, fromRestore: Boolean = false) {
        scope.launch {
            modeApplyMutex.withLock {
                if (fromRestore) {
                    // 冷启动只收敛内存状态：不启动语音、不落盘改写用户开关、不拉起 ADB 前台服务。
                    // 也不写 applied 记录：restore 从未应用副作用，不能覆盖用户已经应用的模式。
                    return@withLock
                }
                // 用户在切换过程中又切了模式：全局值已变，这次副作用已过期
                if (OverlayStore.controlMode.value != mode) return@withLock
                if (lastAppliedMode == mode) return@withLock
                val applied = when (mode) {
                    ControlMode.VOICE -> voiceLifecycle.enable(VoiceStartReason.OVERLAY_SWITCH).isSuccess
                    ControlMode.ACCESSIBILITY, ControlMode.ADB ->
                        voiceLifecycle.disable(VoiceStopReason.MODE_LEAVE).isSuccess
                }
                // 生命周期失败也不能跳过 ADB 桥收敛和通知刷新（例如 A11y 停用失败仍必须断 ADB）
                adbBridgeController.onModeChanged(mode)
                refreshNotification()
                if (applied) lastAppliedMode = mode
            }
        }
    }

    fun metricsFlow(): StateFlow<VoiceMetrics> = metrics

    /**
     * 面板/手动按钮直接回放用户宏；与语音状态无关。
     * 每次调用新建 [ManualExecutionToken] 与独立执行链，语音 disable 的取消令牌不参与。
     */
    suspend fun executeManual(action: CommandAction): ExecResult {
        if (action.steps.isEmpty()) return ExecResult(false, "宏为空")
        val token = ManualExecutionToken()
        val manualChain = buildExecutorChain(token.guard)
        val budget = executionBudgetMs(action.steps)
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(budget) { manualChain.execute(action) }
            } catch (e: TimeoutCancellationException) {
                ExecResult(false, "执行超时")
            } catch (e: CancellationException) {
                ExecResult(false, "手动回放已取消")
            } catch (e: Exception) {
                ExecResult(false, "执行异常：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 由 macroId 定位用户宏后走手动回放链 */
    suspend fun executeMacro(macroId: Long): ExecResult {
        val macro = OverlayStore.macros.value.firstOrNull { it.macroId == macroId }
            ?: return ExecResult(false, "宏不存在")
        return executeManual(MacroResolver.buildCommandAction(macro))
    }

    private fun publishMetrics(snapshot: VoiceMetrics) {
        _metrics.value = snapshot.copy(
            modelVersion = modelVersion,
            adbConnected = adbClient.status.value.phase == AdbPhase.CONNECTED,
        )
    }

    private fun updateRuntimeMetrics() {
        _metrics.value = _metrics.value.copy(
            audioCapturing = audioCapture.isCapturing,
            modelVersion = modelVersion,
            adbConnected = adbClient.status.value.phase == AdbPhase.CONNECTED,
        )
    }

    fun createActionHandler(
        onFeedback: (FeedbackType, String) -> Unit,
    ): ControlActionHandler = ControlActionHandler(
        onLoadModels = { withContext(Dispatchers.Default) { loadModels() } },
        onUnloadModels = { withContext(Dispatchers.Default) { unloadModels() } },
        onStartCapture = { withContext(Dispatchers.Default) { startCapture() } },
        onStopCapture = { withContext(Dispatchers.Default) { stopCapture() } },
        executor = executorChain,
        onFeedback = onFeedback,
        onTelemetry = { dim, name -> telemetry.event(dim, name, config.voiceWakeup.mode.name) },
        onExecResult = { ok ->
            stateMachine.send(if (ok) VoiceEvent.ExecSucceeded else VoiceEvent.ExecFailed)
        },
        actionFor = { slot ->
            macroBindingStore.bindings.value
                .firstOrNull { it.slot == slot && it.enabled }
                ?.let { binding -> OverlayStore.macros.value.firstOrNull { it.macroId == binding.macroId } }
                ?.let(MacroResolver::buildCommandAction)
        },
        isAllowed = {
            voiceLifecycle.state.value != VoiceLifecycleState.DISABLED &&
                voiceLifecycle.state.value != VoiceLifecycleState.DESTROYING
        },
        guard = voiceExecutionGuard,
    )

    fun close() {
        voiceLifecycle.close()
        adbBridgeController.stop()
        adbClient.close()
        stateMachine.close()
    }
}
