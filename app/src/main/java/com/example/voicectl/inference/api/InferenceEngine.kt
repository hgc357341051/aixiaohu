package com.example.voicectl.inference.api

/** 推理 delegate；AUTO 由降级链按 GPU → NNAPI → CPU 选择 */
enum class Delegate { AUTO, GPU, NNAPI, CPU }

data class EngineInfo(
    val name: String,
    val version: String,
    val activeDelegate: Delegate,
    val supportedDelegates: List<Delegate> = listOf(Delegate.GPU, Delegate.NNAPI, Delegate.CPU),
)

/** 所有推理引擎共用契约；C1 真退出时状态机会调用 unload() */
interface InferenceEngine {
    val info: EngineInfo
    val isLoaded: Boolean
    fun load(delegate: Delegate = Delegate.AUTO): Result<Unit>
    fun unload()
    fun setDelegate(delegate: Delegate)
}
