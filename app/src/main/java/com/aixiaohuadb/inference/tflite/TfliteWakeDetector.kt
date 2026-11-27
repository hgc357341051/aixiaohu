package com.aixiaohuadb.inference.tflite

import android.content.res.AssetManager
import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.inference.api.EngineInfo
import com.aixiaohuadb.inference.api.WakeDetector

/** 两级唤醒模型接入骨架：L1 粗检、L2 精检，各自读独立 .tflite 文件 */
class TfliteWakeDetector(
    assets: AssetManager,
    l1Spec: ModelSpec,
    l2Spec: ModelSpec,
) : WakeDetector {
    private val l1 = TfliteEngine(assets, "WakeL1", l1Spec)
    private val l2 = TfliteEngine(assets, "WakeL2", l2Spec)
    private var preferred = Delegate.AUTO

    override val info: EngineInfo get() = l1.info
    override val isLoaded: Boolean get() = l1.isLoaded && l2.isLoaded

    override fun load(delegate: Delegate): Result<Unit> {
        preferred = delegate
        val first = l1.load(delegate)
        if (first.isFailure) return first
        val second = l2.load(delegate)
        if (second.isFailure) {
            l1.unload()
            return second
        }
        return Result.success(Unit)
    }

    override fun unload() {
        l1.unload()
        l2.unload()
    }

    override fun setDelegate(delegate: Delegate) {
        preferred = delegate
        l1.setDelegate(delegate)
        l2.setDelegate(delegate)
    }

    override fun detectCoarse(features: FloatArray): Float = positiveScore(l1.run(features))

    override fun detectFine(features: FloatArray): Float = positiveScore(l2.run(features))

    /** 输出前 6 维对应 §1.1 唤醒标签，正类为前两维 */
    private fun positiveScore(output: FloatArray): Float =
        if (output.size >= 2) maxOf(output[0], output[1]) else 0f

    fun preferredDelegate(): Delegate = preferred
}
