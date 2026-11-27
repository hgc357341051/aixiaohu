package com.aixiaohuadb.di

import android.content.Context
import android.content.res.Resources
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
import com.aixiaohuadb.exec.AccessibilityExecutor
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.Executor
import com.aixiaohuadb.exec.ReadOnlyExecutor
import com.aixiaohuadb.inference.api.InferenceFactory
import com.aixiaohuadb.inference.demo.DemoInferenceFactory
import com.aixiaohuadb.inference.tflite.TfliteInferenceFactory
import com.aixiaohuadb.machine.ControlActionHandler
import com.aixiaohuadb.machine.FeedbackType
import com.aixiaohuadb.machine.SideEffect
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.VoiceStateMachine
import com.aixiaohuadb.monitor.Telemetry
import com.aixiaohuadb.service.VoiceAccessibilityService
import com.aixiaohuadb.service.VoiceForegroundService
import com.aixiaohuadb.adb.AdbClient
import com.aixiaohuadb.adb.AdbInputExecutor
import com.aixiaohuadb.adb.AdbPhase
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
    private var inferenceFactory: InferenceFactory = DemoInferenceFactory()

    val stateMachine = VoiceStateMachine({ config })
    val audioCapture = AudioCapture(context, config)
    val coordinateMapper = CoordinateMapper(config.exec.coordBase)

    /** Demo/TFLite 工厂对外只读暴露，调试页显示实际生效实现 */
    val currentFactory: InferenceFactory get() = inferenceFactory
    val isDemoMode: Boolean get() = inferenceFactory is DemoInferenceFactory
    val modelVersion: String get() = if (isDemoMode) "demo-1.0.0" else tfliteFactory.modelVersion

    /** 内嵌 ADB 客户端与强类型输入桥：唯一的备用执行链路，不依赖外部应用 */
    val adbClient: AdbClient = AdbClient(context.applicationContext, ::screenSize)

    private val adbExecutor = AdbInputExecutor(
        client = adbClient,
        mapper = coordinateMapper,
        screenSizeProvider = ::screenSize,
        isAvailableProvider = { adbClient.status.value.phase == AdbPhase.CONNECTED },
    )
    private val executors: List<Executor> = listOf(
        AccessibilityExecutor { VoiceAccessibilityService.instance },
        adbExecutor,
        ReadOnlyExecutor,
    )
    val executorChain = ExecutorChain(executors)

    /** 真实屏幕尺寸：Resources.getSystem() 会随横竖屏自然交换宽高 */
    private fun screenSize(): Pair<Int, Int> {
        val metrics = Resources.getSystem().displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

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
        // 语音主链路优先启动，绝不放在可选执行链的初始化中
        recognitionPipeline.start(scope)
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
    )

    fun close() {
        adbClient.close()
        recognitionPipeline.stop()
        audioCapture.close()
        stateMachine.close()
    }
}
