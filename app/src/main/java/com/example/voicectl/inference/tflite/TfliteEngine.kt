package com.example.voicectl.inference.tflite

import android.content.res.AssetManager
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.DelegateChain
import com.example.voicectl.inference.api.EngineInfo
import com.example.voicectl.inference.api.InferenceEngine
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ModelSpec(val file: String, val input: Int, val output: Int, val dtype: String)

/**
 * TFLite 解释器生命周期与 delegate 降级链。
 * 注意：这是接入骨架，真实模型的推理算法由训练产物决定；本类只负责加载、执行与降级。
 */
class TfliteEngine(
    private val assets: AssetManager,
    private val name: String,
    private val spec: ModelSpec,
) : InferenceEngine {
    private var interpreter: Interpreter? = null
    private var currentInfo = EngineInfo(name, "tflite-runtime", Delegate.CPU)
    private var modelBytes: ByteArray? = null

    override val info: EngineInfo get() = currentInfo
    override val isLoaded: Boolean get() = interpreter != null

    override fun load(delegate: Delegate): Result<Unit> = try {
        val path = "models/${spec.file}"
        val bytes = runCatching { assets.open(path).use { it.readBytes() } }
            .getOrElse { return Result.failure(it) }
        modelBytes = bytes

        var lastError: Throwable? = null
        for (candidate in DelegateChain(delegate).candidates()) {
            try {
                val options = Interpreter.Options()
                if (candidate == Delegate.GPU) options.addDelegate(GpuDelegate())
                if (candidate == Delegate.NNAPI) options.setUseNNAPI(true)
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes)
                buffer.rewind()
                interpreter = Interpreter(buffer, options)
                currentInfo = currentInfo.copy(activeDelegate = candidate)
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (interpreter == null) {
            Result.failure(lastError ?: IllegalStateException("无法创建 TFLite Interpreter"))
        } else {
            Result.success(Unit)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun unload() {
        interpreter?.close()
        interpreter = null
    }

    override fun setDelegate(delegate: Delegate) {
        if (isLoaded) load(delegate)
    }

    /** 以 float32 输入输出运行模型；张量规格来自 manifest.json */
    fun run(input: FloatArray, outputSize: Int = spec.output): FloatArray {
        val engine = interpreter ?: throw IllegalStateException("$name 未加载")
        val size = spec.input
        require(input.size == size) { "$name 输入长度应为 $size，实际 ${input.size}" }
        val inBuffer = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
        input.forEach { inBuffer.putFloat(it) }
        val outBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
        engine.run(inBuffer, outBuffer)
        outBuffer.rewind()
        return FloatArray(outputSize) { outBuffer.float }
    }

    fun hasModelFile(): Boolean = modelBytes != null
}
