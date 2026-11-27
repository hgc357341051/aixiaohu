package com.aixiaohuadb.inference.demo

import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.inference.api.EngineInfo
import com.aixiaohuadb.inference.api.WakeDetector

/** 演示级唤醒器：默认低分不误触发；调试页可注入固定分数驱动完整流程 */
class DemoWakeDetector : WakeDetector {
    private var currentInfo = EngineInfo("DemoWake", "1.0.0-demo", Delegate.CPU)
    private var loaded = false

    /** 非空时强制返回该分数，供调试页演示 */
    var forcedCoarse: Float? = null
    var forcedFine: Float? = null

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
        forcedCoarse = null
        forcedFine = null
    }

    override fun setDelegate(delegate: Delegate) {
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
    }

    override fun detectCoarse(features: FloatArray): Float =
        forcedCoarse ?: DemoSupport.pseudoScore(features, 101, 0.04f, 0.48f)

    override fun detectFine(features: FloatArray): Float =
        forcedFine ?: DemoSupport.pseudoScore(features, 202, 0.06f, 0.62f)
}
