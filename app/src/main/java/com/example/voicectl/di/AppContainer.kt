package com.example.voicectl.di

import android.content.Context
import com.example.voicectl.audio.AudioCapture
import com.example.voicectl.audio.FeatureExtractor
import com.example.voicectl.audio.FrontEnd
import com.example.voicectl.config.AppConfig
import com.example.voicectl.config.SettingsStore
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
import com.example.voicectl.machine.VoiceStateMachine
import com.example.voicectl.monitor.Telemetry
import com.example.voicectl.service.VoiceAccessibilityService
import com.example.voicectl.service.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手写依赖容器：无 Hilt/Koin，App 单例持有；上层只依赖抽象接口 */
class AppContainer(private val context: Context) {
    val settingsStore = SettingsStore(context)
    val telemetry = Telemetry()
    val frontEnd = FrontEnd()
    val featureExtractor = FeatureExtractor()
    private val tfliteFactory = TfliteInferenceFactory(context)

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

    private val executors: List<Executor> = listOf(
        AccessibilityExecutor { VoiceAccessibilityService.instance },
        AdbExecutor(
            clientProvider = { adbClient },
            mapper = coordinateMapper,
            retryMax = config.exec.retryMax,
        ),
        ReadOnlyExecutor,
    )
    val executorChain = ExecutorChain(executors)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Demo/TFLite 工厂对外只读暴露，调试页显示实际生效实现 */
    val currentFactory: InferenceFactory get() = inferenceFactory
    val isDemoMode: Boolean get() = inferenceFactory is DemoInferenceFactory
    val modelVersion: String get() = if (isDemoMode) "demo-1.0.0" else tfliteFactory.modelVersion

    fun initialize() {
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
        if (needReload) {
            inferenceFactory.unloadAll()
            inferenceFactory = selectFactory()
            if (stateMachine.currentState() != com.example.voicectl.machine.VoiceState.MANUAL_SILENT) {
                loadModels()
            }
        }
    }

    private fun selectFactory(): InferenceFactory =
        if (!config.useDemoModels && tfliteFactory.modelsAvailable()) tfliteFactory else DemoInferenceFactory()

    /** 加载模型；TFLite 加载失败时自动降级 Demo，保证业务不崩 */
    fun loadModels() {
        val result = inferenceFactory.loadAll(config.preferredDelegate)
        if (result.isFailure && inferenceFactory !is DemoInferenceFactory) {
            inferenceFactory.unloadAll()
            inferenceFactory = DemoInferenceFactory()
            inferenceFactory.loadAll(config.preferredDelegate)
            telemetry.event("model", "fallback_to_demo")
        }
    }

    /** C1 真退出：卸载模型并释放麦克风 */
    fun unloadModels() {
        inferenceFactory.unloadAll()
        audioCapture.stop()
    }

    fun startCapture() {
        if (config.voiceWakeup.mode != com.example.voicectl.config.WakeMode.MANUAL_SILENT) {
            audioCapture.start()
        }
    }

    fun stopCapture() = audioCapture.stop()

    fun refreshNotification() {
        VoiceForegroundService.runningInstance?.refresh()
    }

    fun createActionHandler(
        onFeedback: (FeedbackType, String) -> Unit,
    ): ControlActionHandler = ControlActionHandler(
        onLoadModels = { loadModels() },
        onUnloadModels = { unloadModels() },
        onStartCapture = { startCapture() },
        onStopCapture = { stopCapture() },
        executor = executorChain,
        onFeedback = onFeedback,
        onTelemetry = { dim, name -> telemetry.event(dim, name, config.voiceWakeup.mode.name) },
    )

    fun close() {
        audioCapture.close()
        stateMachine.close()
    }
}
