package com.aixiaohuadb.domain

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.inference.api.SpeakerVerifier
import com.aixiaohuadb.inference.api.WakeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** L1 → L2 → 声纹门控；阈值来自运行时配置，包含灵敏度偏移 */
class WakeUseCase(
    private val wakeProvider: () -> WakeDetector,
    private val speakerProvider: () -> SpeakerVerifier,
    private val configProvider: () -> AppConfig,
) {
    data class WakeOutcome(
        val coarse: Float,
        val fine: Float,
        val coarseHit: Boolean,
        val fineHit: Boolean,
        val similarity: Float,
        val speakerOk: Boolean,
        val speakerEnabled: Boolean,
    )

    suspend fun evaluate(features: FloatArray): WakeOutcome = withContext(Dispatchers.Default) {
        val config = configProvider()
        val wake = wakeProvider()
        val coarse = wake.detectCoarse(features)
        val fine = wake.detectFine(features)
        val offset = config.thresholdOffset
        val coarseHit = coarse >= config.decode.tauCoarse + offset
        val fineHit = fine >= config.decode.tauFine + offset
        val speaker = speakerProvider()
        val useSpeaker = config.speaker.enabled && speaker.hasTemplate()
        val verify = if (useSpeaker) {
            speaker.verify(features, config.decode.tauSpkWake + offset)
        } else {
            null
        }
        WakeOutcome(
            coarse = coarse,
            fine = fine,
            coarseHit = coarseHit,
            fineHit = fineHit,
            similarity = verify?.similarity ?: 0f,
            speakerOk = verify?.passed ?: true,
            speakerEnabled = useSpeaker,
        )
    }
}
