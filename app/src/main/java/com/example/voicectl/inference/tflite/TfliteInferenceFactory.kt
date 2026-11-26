package com.example.voicectl.inference.tflite

import android.content.Context
import com.example.voicectl.inference.api.CommandClassifier
import com.example.voicectl.inference.api.ControlClassifier
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.InferenceFactory
import com.example.voicectl.inference.api.SpeakerVerifier
import com.example.voicectl.inference.api.WakeDetector
import org.json.JSONObject

/** 从 assets/models/manifest.json 读取张量规格；模型缺失时 loadAll 明确失败，由容器回落 Demo */
class TfliteInferenceFactory(context: Context) : InferenceFactory {
    private val assets = context.assets
    private val manifestText = runCatching { assets.open("models/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) } }
        .getOrDefault("{}")
    private val manifest = runCatching { JSONObject(manifestText) }.getOrElse { JSONObject() }

    val demoFlag: Boolean get() = manifest.optBoolean("demo", true)
    val modelVersion: String get() = manifest.optString("version", "unknown")

    private fun spec(key: String): ModelSpec {
        val obj = manifest.optJSONObject("models")?.optJSONObject(key)
        return ModelSpec(
            file = obj?.optString("file") ?: "$key.tflite",
            input = obj?.optInt("input", 400) ?: 400,
            output = obj?.optInt("output", 6) ?: 6,
            dtype = obj?.optString("dtype", "float32") ?: "float32",
        )
    }

    private val wake: TfliteWakeDetector by lazy { TfliteWakeDetector(assets, spec("wake_l1"), spec("wake_l2")) }
    private val command: TfliteCommandClassifier by lazy { TfliteCommandClassifier(assets, spec("cmd")) }
    private val control: TfliteControlClassifier by lazy { TfliteControlClassifier(assets, spec("cmd")) }
    private val speaker: TfliteSpeakerVerifier by lazy { TfliteSpeakerVerifier(assets, spec("spk")) }

    /** 检查 assets/models 下模型文件是否齐全 */
    fun modelsAvailable(): Boolean {
        val files = runCatching { assets.list("models")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        val needed = listOf(spec("wake_l1").file, spec("wake_l2").file, spec("cmd").file, spec("spk").file)
        return !demoFlag && needed.all { it in files }
    }

    override fun wakeDetector(): WakeDetector = wake
    override fun commandClassifier(): CommandClassifier = command
    override fun controlClassifier(): ControlClassifier = control
    override fun speakerVerifier(): SpeakerVerifier = speaker

    override fun loadAll(delegate: Delegate): Result<Unit> {
        if (!modelsAvailable()) {
            return Result.failure(IllegalStateException("模型文件缺失或 manifest.demo=true，自动降级到 Demo 实现"))
        }
        val results = listOf(
            wake.load(delegate),
            command.load(delegate),
            control.load(delegate),
            speaker.load(delegate),
        )
        return results.firstOrNull { it.isFailure } ?: Result.success(Unit)
    }

    override fun unloadAll() {
        wake.unload()
        command.unload()
        control.unload()
        speaker.unload()
    }
}
