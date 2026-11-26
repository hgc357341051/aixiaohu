package com.example.voicectl.domain

import com.example.voicectl.config.AppConfig
import com.example.voicectl.inference.api.CommandClassifier
import com.example.voicectl.inference.api.CommandResult
import com.example.voicectl.inference.api.ControlClassifier
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.inference.api.SpeakerVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 指令窗内控制头优先判决，其次 ACTION×OBJECT 指令分类，最后声纹复核 */
class RecognizeUseCase(
    private val controlProvider: () -> ControlClassifier,
    private val commandProvider: () -> CommandClassifier,
    private val speakerProvider: () -> SpeakerVerifier,
    private val configProvider: () -> AppConfig,
) {
    data class RecognizeOutcome(
        val control: ControlWord,
        val controlScore: Float,
        val command: CommandResult?,
        val similarity: Float,
        val speakerOk: Boolean,
        val speakerEnabled: Boolean,
    )

    suspend fun evaluate(features: FloatArray): RecognizeOutcome = withContext(Dispatchers.Default) {
        val config = configProvider()
        val offset = config.thresholdOffset
        val controlResult = controlProvider().classify(features)
        val controlHit = controlResult.score >= config.decode.tauCtrl + offset &&
            controlResult.control != ControlWord.C0_NONE
        val commandResult = if (controlHit) null else commandProvider().classify(features)
        val speaker = speakerProvider()
        val useSpeaker = config.speaker.enabled && speaker.hasTemplate()
        val verify = if (useSpeaker) {
            speaker.verify(features, config.decode.tauSpkCmd + offset)
        } else {
            null
        }
        RecognizeOutcome(
            control = if (controlHit) controlResult.control else ControlWord.C0_NONE,
            controlScore = controlResult.score,
            command = commandResult,
            similarity = verify?.similarity ?: 0f,
            speakerOk = verify?.passed ?: true,
            speakerEnabled = useSpeaker,
        )
    }
}
