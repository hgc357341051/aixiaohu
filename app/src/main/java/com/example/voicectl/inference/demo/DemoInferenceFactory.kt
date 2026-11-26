package com.example.voicectl.inference.demo

import com.example.voicectl.inference.api.CommandClassifier
import com.example.voicectl.inference.api.ControlClassifier
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.InferenceFactory
import com.example.voicectl.inference.api.SpeakerVerifier
import com.example.voicectl.inference.api.WakeDetector

/** 默认工厂：全部 load 恒成功，可驱动状态机走通完整流程，无需真实模型 */
class DemoInferenceFactory : InferenceFactory {
    val wake = DemoWakeDetector()
    val command = DemoCommandClassifier()
    val control = DemoControlClassifier()
    val speaker = DemoSpeakerVerifier()

    override fun wakeDetector(): WakeDetector = wake
    override fun commandClassifier(): CommandClassifier = command
    override fun controlClassifier(): ControlClassifier = control
    override fun speakerVerifier(): SpeakerVerifier = speaker

    override fun loadAll(delegate: Delegate): Result<Unit> {
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
