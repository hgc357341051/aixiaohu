package com.example.voicectl.inference.tflite

import android.content.res.AssetManager
import com.example.voicectl.inference.api.ControlClassifier
import com.example.voicectl.inference.api.ControlResult
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.EngineInfo

/** 控制头接入骨架：共享编码器输出的第 17–21 维为 C0–C4 */
class TfliteControlClassifier(
    assets: AssetManager,
    spec: ModelSpec,
) : ControlClassifier {
    private val engine = TfliteEngine(assets, "Control", spec)
    private val controlStart = 17

    override val info: EngineInfo get() = engine.info
    override val isLoaded: Boolean get() = engine.isLoaded

    override fun load(delegate: Delegate): Result<Unit> = engine.load(delegate)
    override fun unload() = engine.unload()
    override fun setDelegate(delegate: Delegate) = engine.setDelegate(delegate)

    override fun classify(features: FloatArray): ControlResult {
        val output = engine.run(features)
        var best = ControlWord.C0_NONE
        var bestScore = 0f
        ControlWord.entries.forEachIndexed { index, word ->
            val score = output.getOrElse(controlStart + index) { 0f }
            if (score > bestScore) {
                bestScore = score
                best = word
            }
        }
        return ControlResult(best, bestScore)
    }
}
