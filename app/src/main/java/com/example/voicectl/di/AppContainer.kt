package com.example.voicectl.di

import android.content.Context
import com.example.voicectl.audio.AudioCapture
import com.example.voicectl.audio.FeatureExtractor
import com.example.voicectl.audio.FrontEnd
import com.example.voicectl.config.AppConfig
import com.example.voicectl.config.SettingsStore
import com.example.voicectl.config.WakeMode
import com.example.voicectl.domain.RecognitionPipeline
import com.example.voicectl.domain.RecognizeUseCase
import com.example.voicectl.domain.VoiceMetrics
import com.example.voicectl.domain.WakeUseCase
import com.example.voicectl.exec.AccessibilityExecutor
import com.example.voicectl.exec.AdbClient
import com.example.voicectl.exec.AdbExecutor
import com.example.voicectl.exec.CoordinateMapper
import com.example.voicectl.exec.ExecutorChain
import com.example.voicectl.exec.Executor
import com.example.voicectl.exec.ReadOnlyExecutor
import com.example.voicectl.inference.api.InferenceFactory
import com.example.voicectl.inference.demo.DemoInferenceFactory
import com.example.voicectl.inference.tflite.TfliteInferenceFactory
import com.example.voicectl.machine.ControlActionHandler
import com.example.voicectl.machine.FeedbackType
import com.example.voicectl.machine.SideEffect
import com.example.voicectl.machine.VoiceEvent
import com.example.voicectl.machine.VoiceState
import com.example.voicectl.machine.VoiceStateMachine
import com.example.voicectl.monitor.Telemetry
import com.example.voicectl.service.VoiceAccessibilityService
import com.example.voicectl.service.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 手写依赖容器：无 Hilt/Koin，App 单例持有；上层只依赖抽象接口 */
class AppContainer(private val context: Context) {
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
    private var adbClient: AdbClient = AdbClient(AppConfig.DEFAULT.exec.adbBaseUrl)

    @Volatile
    private var inferenceFactory: InferenceFactory = DemoInferenceFactory()

    val stateMachine = VoiceStateMachine({ config })
    val audioCapture = AudioCapture(context, config)
    val coordinateMapper = CoordinateMapper(config.exec.coordBase)

    /** Demo/TFLite 工厂对外只读暴露，调试页显示实际生效实现 */
    val currentFactory: InferenceFactory get() = inferenceFactory
    val isDemoMode: Boolean get() = inferenceFactory is DemoInferenceFactory
    val modelVersion: String get() = if (isDemoMode) "demo-1.0.0" else tfliteFactory.modelVersion

    private val adbExecutor = AdbExecutor(
        clientProvider = { adbClient },
        mapper = coordinateMapper,
        retryMax = config.exec.retryMax,
    )
    private val executors: List<Executor> = listOf(
        AccessibilityExecutor { VoiceAccessibilityService.instance },
        adbExecutor,
        ReadOnlyExecutor,
    )
    val executorChain = ExecutorChain(executors)

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
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun initialize() {
        recognitionPipeline.start(scope)
        scope.launch {
            settingsStore.configFlow.collect { updateConfig(it) }
        }
    }

    private fun updateConfig(next: AppConfig) {
        val previous = config
        val needReload = previous.preferredDelegate != next.preferredDelegate ||
            previous.useDemoModels != next.useDemoModels
        config = next
        adbClient = AdbClient(next.exec.adbBaseUrl)
        updateRuntimeMetrics()
        if (needReload) {
            inferenceFactory.unloadAll()
            inferenceFactory = selectFactory()
            if (stateMachine.currentState() != VoiceState.MANUAL_SILENT) {
                loadModels()
            }
        }
    }

    private fun selectFactory(): InferenceFactory =
        if (!config.useDemoModels && tfliteFactory.modelsAvailable()) tfliteFactory else DemoInferenceFactory()

    /** 加载模型；TFLite 加载失败时自动降级 Demo，保证业务不崩 */
    fun loadModels() {
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

    fun metricsFlow(): StateFlow<VoiceMetrics> = metrics

    private fun publishMetrics(snapshot: VoiceMetrics) {
        _metrics.value = snapshot.copy(
            modelVersion = modelVersion,
            adbReachable = adbExecutor.adbReachable,
        )
    }

    private fun updateRuntimeMetrics() {
        _metrics.value = _metrics.value.copy(
            audioCapturing = audioCapture.isCapturing,
            modelVersion = modelVersion,
            adbReachable = adbExecutor.adbReachable,
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
    )

    fun close() {
        recognitionPipeline.stop()
        audioCapture.close()
        stateMachine.close()
    }
}
