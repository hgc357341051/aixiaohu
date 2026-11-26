package com.example.voicectl.domain

import com.example.voicectl.audio.AudioCapture
import com.example.voicectl.audio.FeatureExtractor
import com.example.voicectl.audio.FeatureWindow
import com.example.voicectl.audio.FrontEnd
import com.example.voicectl.config.AppConfig
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.machine.VoiceEvent
import com.example.voicectl.machine.VoiceState
import com.example.voicectl.machine.VoiceStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音频帧到状态机事件的真实闭环：前端、特征、窗口、判决、事件上报。 */
class RecognitionPipeline(
    private val audioCapture: AudioCapture,
    private val frontEnd: FrontEnd,
    private val featureExtractor: FeatureExtractor,
    private val wakeUseCase: WakeUseCase,
    private val recognizeUseCase: RecognizeUseCase,
    private val stateMachine: VoiceStateMachine,
    private val configProvider: () -> AppConfig,
    private val onMetrics: (VoiceMetrics) -> Unit,
) {
    private val wakeWindow = FeatureWindow(400)
    private val cmdWindow = FeatureWindow(1280)
    private val spkWindow = FeatureWindow(192)

    private var job: Job? = null
    private var frameCounter = 0L
    private var wakeHits = 0
    private var lastRejectMs = 0L

    /** AppContainer.initialize 调用一次，避免重复收集同一条 SharedFlow。 */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            audioCapture.frames.collect { frame ->
                try {
                    processFrame(frame)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    stateMachine.send(VoiceEvent.Error(t))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        wakeWindow.reset()
        cmdWindow.reset()
        spkWindow.reset()
        wakeHits = 0
        frameCounter = 0
        lastRejectMs = 0
    }

    private suspend fun processFrame(frame: FloatArray) = withContext(Dispatchers.Default) {
        val config = configProvider()
        val processed = frontEnd.process(frame)
        val mel = featureExtractor.extract(processed)

        wakeWindow.push(mel)
        cmdWindow.push(mel)
        spkWindow.push(mel)

        frameCounter++
        val strideFrames = (config.feature.wakeStrideMs / config.feature.winMs).coerceAtLeast(1)
        if (frameCounter % strideFrames.toLong() != 0L) return@withContext

        when (stateMachine.currentState()) {
            VoiceState.IDLE, VoiceState.LISTEN -> evaluateWake(config, verifyOnly = false)
            VoiceState.VERIFY -> evaluateWake(config, verifyOnly = true)
            VoiceState.CMD_WINDOW -> evaluateCommand(config)
            else -> wakeHits = 0
        }
    }

    private suspend fun evaluateWake(config: AppConfig, verifyOnly: Boolean) {
        val outcome = wakeUseCase.evaluate(wakeWindow.snapshot())
        val now = System.currentTimeMillis()

        if (!verifyOnly && outcome.coarseHit) {
            stateMachine.send(VoiceEvent.CoarseHit)
        }

        when {
            outcome.fineHit && outcome.speakerEnabled && !outcome.speakerOk -> {
                wakeHits = 0
                stateMachine.send(VoiceEvent.WakeRejected)
            }
            outcome.fineHit && outcome.speakerOk -> {
                wakeHits++
                val required = config.decode.debounceFrames.coerceAtLeast(1)
                if (wakeHits >= required) {
                    wakeHits = 0
                    stateMachine.send(VoiceEvent.WakeVerified(outcome.similarity))
                }
            }
            else -> wakeHits = 0
        }

        onMetrics(
            VoiceMetrics(
                coarse = outcome.coarse,
                fine = outcome.fine,
                speakerSim = outcome.similarity,
                lastUpdateMs = now,
                audioCapturing = audioCapture.isCapturing,
            ),
        )
    }

    private suspend fun evaluateCommand(config: AppConfig) {
        val outcome = recognizeUseCase.evaluate(cmdWindow.snapshot())
        val now = System.currentTimeMillis()
        val threshold = config.decode.tauCmd + config.thresholdOffset
        val command = outcome.command
        val commandHit = command != null && command.commandId != 14 && command.score >= threshold
        val speakerRejected = outcome.speakerEnabled && !outcome.speakerOk

        when {
            speakerRejected -> sendRejected(now)
            outcome.control != ControlWord.C0_NONE ->
                stateMachine.send(VoiceEvent.ControlDetected(outcome.control, outcome.controlScore))
            commandHit -> stateMachine.send(VoiceEvent.CommandDetected(command!!, command.score))
            else -> sendRejected(now)
        }

        onMetrics(
            VoiceMetrics(
                commandId = command?.commandId,
                commandScore = command?.score ?: 0f,
                control = outcome.control,
                controlScore = outcome.controlScore,
                speakerSim = outcome.similarity,
                lastUpdateMs = now,
                audioCapturing = audioCapture.isCapturing,
            ),
        )
    }

    private fun sendRejected(now: Long) {
        if (now - lastRejectMs < 1000L) return
        lastRejectMs = now
        stateMachine.send(VoiceEvent.Rejected)
    }
}
