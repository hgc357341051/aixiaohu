package com.aixiaohuadb.ui.debug

import androidx.lifecycle.ViewModel
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.inference.demo.DemoInferenceFactory

/** 调试页数据：仅 dev flavor 可见；只读展示置信度与脱敏事件日志 */
class DebugViewModel(private val container: AppContainer) : ViewModel() {
    val logs = container.telemetry.recent
    val engineInfo: String
        get() = "${container.currentFactory.javaClass.simpleName} / ${container.modelVersion} / " +
            container.currentFactory.wakeDetector().info.activeDelegate.name

    val confidenceLabels = listOf("L1 粗检 τ .60", "L2 精检 τ .90", "指令 τ .80", "控制头 τ .85", "声纹 τ .70")

    fun confidences(): List<Float> {
        val demo = container.currentFactory as? DemoInferenceFactory ?: return listOf(0f, 0f, 0f, 0f, 0f)
        return listOf(
            demo.wake.forcedCoarse ?: 0.12f,
            demo.wake.forcedFine ?: 0.18f,
            demo.command.forcedScore ?: 0.09f,
            demo.control.forcedScore ?: 0.04f,
            demo.speaker.forcedSimilarity ?: 0.86f,
        )
    }
}
