package com.aixiaohuadb.inference.demo

import com.aixiaohuadb.inference.api.ControlClassifier
import com.aixiaohuadb.inference.api.ControlResult
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.inference.api.EngineInfo

/** 演示级控制头：默认 C0；forcedControl 可注入 C1–C4 演示控制流程 */
class DemoControlClassifier : ControlClassifier {
    private var currentInfo = EngineInfo("DemoControl", "1.0.0-demo", Delegate.CPU)
    private var loaded = false
    var forcedControl: ControlWord? = null
    var forcedScore: Float? = null

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
        forcedControl = null
        forcedScore = null
    }

    override fun setDelegate(delegate: Delegate) {
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
    }

    override fun classify(features: FloatArray): ControlResult {
        val word = forcedControl ?: ControlWord.C0_NONE
        val score = forcedScore ?: if (word == ControlWord.C0_NONE) {
            DemoSupport.pseudoScore(features, 404, 0.01f, 0.30f)
        } else {
            DemoSupport.pseudoScore(features, 405, 0.86f, 0.99f)
        }
        return ControlResult(word, score)
    }
}
