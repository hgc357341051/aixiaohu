package com.example.voicectl.inference.tflite

import android.content.res.AssetManager
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.EngineInfo
import com.example.voicectl.inference.api.SpeakerEnv
import com.example.voicectl.inference.api.SpeakerVerifier
import com.example.voicectl.inference.api.VerifyResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 声纹 1:1 接入骨架。
 * owner.spk 约定格式：int32 count + int32 dim + count*dim 个 float32（后续可替换为加密容器）。
 * 模板不存在时使用非声纹路径，绝不阻断功能。
 */
class TfliteSpeakerVerifier(
    private val assets: AssetManager,
    spec: ModelSpec,
) : SpeakerVerifier {
    private val engine = TfliteEngine(assets, "Speaker", spec)
    private val centroids = mutableMapOf<SpeakerEnv, FloatArray>()
    private var templateFileLoaded = false
    private var consecutiveRejects = 0

    override val info: EngineInfo get() = engine.info
    override val isLoaded: Boolean get() = engine.isLoaded

    override fun load(delegate: Delegate): Result<Unit> {
        val result = engine.load(delegate)
        if (result.isSuccess) loadTemplateIfExists()
        return result
    }

    override fun unload() = engine.unload()

    override fun setDelegate(delegate: Delegate) = engine.setDelegate(delegate)

    override fun hasTemplate(): Boolean = templateFileLoaded || centroids.isNotEmpty()

    override fun enroll(embeddings: List<FloatArray>, env: SpeakerEnv): Result<Unit> {
        if (embeddings.isEmpty()) return Result.failure(IllegalArgumentException("注册样本为空"))
        val dim = embeddings.first().size
        val mean = FloatArray(dim)
        embeddings.forEach { emb ->
            if (emb.size != dim) return Result.failure(IllegalArgumentException("声纹维度不一致"))
            for (i in 0 until dim) mean[i] += emb[i] / embeddings.size
        }
        centroids[env] = mean
        templateFileLoaded = true
        return Result.success(Unit)
    }

    override fun verify(features: FloatArray, threshold: Float): VerifyResult {
        if (!hasTemplate()) return VerifyResult(0f, true)
        val embedding = engine.run(features, 192)
        val reference = centroids[SpeakerEnv.QUIET] ?: FloatArray(192)
        val similarity = cosine(embedding, reference)
        val passed = similarity >= threshold
        consecutiveRejects = if (passed) 0 else consecutiveRejects + 1
        return VerifyResult(similarity, passed)
    }

    override fun deleteTemplate() {
        centroids.clear()
        templateFileLoaded = false
        consecutiveRejects = 0
    }

    override fun adapt(embedding: FloatArray, env: SpeakerEnv) {
        if (consecutiveRejects >= 3) return
        val old = centroids[env]
        if (old == null || old.size != embedding.size) {
            centroids[env] = embedding.copyOf()
            return
        }
        val alpha = 0.05f
        for (i in old.indices) old[i] = old[i] * (1f - alpha) + embedding[i] * alpha
    }

    private fun loadTemplateIfExists() {
        try {
            val bytes = assets.open("models/owner.spk").use { it.readBytes() }
            if (bytes.size < 8) return
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
            val count = buffer.int
            val dim = buffer.int
            if (count <= 0 || dim <= 0 || bytes.size < 8 + count * dim * 4) return
            val sum = FloatArray(dim)
            repeat(count) {
                for (i in 0 until dim) sum[i] += buffer.float
            }
            centroids[SpeakerEnv.QUIET] = FloatArray(dim) { sum[it] / count }
            templateFileLoaded = true
        } catch (e: Exception) {
            templateFileLoaded = false
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
