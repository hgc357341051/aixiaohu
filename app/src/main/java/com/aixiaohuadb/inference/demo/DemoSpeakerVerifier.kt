package com.aixiaohuadb.inference.demo

import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.inference.api.EngineInfo
import com.aixiaohuadb.inference.api.SpeakerEnv
import com.aixiaohuadb.inference.api.SpeakerVerifier
import com.aixiaohuadb.inference.api.VerifyResult

/** 演示级声纹：默认存在机主模板并给出高相似度；删除后 hasTemplate=false 触发非声纹降级 */
class DemoSpeakerVerifier : SpeakerVerifier {
    private var currentInfo = EngineInfo("DemoSpeaker", "1.0.0-demo", Delegate.CPU)
    private var loaded = false
    private var templatePresent = true
    private val centroids = mutableMapOf<SpeakerEnv, FloatArray>()
    private var consecutiveRejects = 0

    var forcedSimilarity: Float? = null

    override val info: EngineInfo get() = currentInfo
    override val isLoaded: Boolean get() = loaded

    override fun load(delegate: Delegate): Result<Unit> {
        loaded = true
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
        return Result.success(Unit)
    }

    override fun unload() {
        loaded = false
        forcedSimilarity = null
    }

    override fun setDelegate(delegate: Delegate) {
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
    }

    override fun hasTemplate(): Boolean = templatePresent

    override fun enroll(embeddings: List<FloatArray>, env: SpeakerEnv): Result<Unit> {
        if (embeddings.isEmpty()) return Result.failure(IllegalArgumentException("注册样本为空"))
        val dim = embeddings.first().size
        val mean = FloatArray(dim)
        embeddings.forEach { emb ->
            require(emb.size == dim) { "声纹向量维度不一致" }
            for (i in 0 until dim) mean[i] += emb[i] / embeddings.size
        }
        centroids[env] = mean
        templatePresent = true
        return Result.success(Unit)
    }

    override fun verify(features: FloatArray, threshold: Float): VerifyResult {
        if (!templatePresent) return VerifyResult(0f, true)
        val similarity = forcedSimilarity ?: DemoSupport.pseudoScore(features, 505, 0.80f, 0.95f)
        val passed = similarity >= threshold
        consecutiveRejects = if (passed) 0 else consecutiveRejects + 1
        return VerifyResult(similarity, passed)
    }

    override fun deleteTemplate() {
        centroids.clear()
        templatePresent = false
        forcedSimilarity = null
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

    fun rejectedTimes(): Int = consecutiveRejects
}
