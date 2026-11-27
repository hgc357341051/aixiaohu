package com.aixiaohuadb.inference.api

enum class SpeakerEnv { QUIET, GAME_LOUD, FAR_FIELD }

data class VerifyResult(val similarity: Float, val passed: Boolean)

/** 单人声纹 1:1 验证；模板缺失自动降级为非声纹路径，不得成为单点故障 */
interface SpeakerVerifier : InferenceEngine {
    fun hasTemplate(): Boolean
    fun enroll(embeddings: List<FloatArray>, env: SpeakerEnv): Result<Unit>
    fun verify(features: FloatArray, threshold: Float): VerifyResult
    fun deleteTemplate()
    fun adapt(embedding: FloatArray, env: SpeakerEnv)
}
