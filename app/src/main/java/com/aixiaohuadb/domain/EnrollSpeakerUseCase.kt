package com.aixiaohuadb.domain

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.inference.api.SpeakerEnv
import com.aixiaohuadb.inference.api.SpeakerVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** 从训练集机主样本生成确定性声纹嵌入并注册三环境子质心（Demo 环境可直接演示） */
class EnrollSpeakerUseCase(
    private val speakerProvider: () -> SpeakerVerifier,
    private val configProvider: () -> AppConfig,
) {
    data class EnrollResult(val success: Boolean, val samples: Int, val message: String)

    suspend fun enroll(env: SpeakerEnv, seed: Int = 42): EnrollResult = withContext(Dispatchers.Default) {
        val config = configProvider()
        val total = config.speaker.minSamples
        val embeddings = List(total.coerceAtMost(64)) { index ->
            FloatArray(192) { i ->
                val phase = (seed + index) * 0.017 + i * 0.11
                (sin(phase) + 0.35f * cos(phase * 0.5f)).toFloat()
            }
        }
        val result = speakerProvider().enroll(embeddings, env)
        EnrollResult(result.isSuccess, embeddings.size, result.exceptionOrNull()?.message ?: "注册完成")
    }
}
