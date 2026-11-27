package com.aixiaohuadb.inference.api

/** 抽象工厂：Demo 与 TFLite 两套实现平级，由 AppContainer 二选一注入 */
interface InferenceFactory {
    fun wakeDetector(): WakeDetector
    fun commandClassifier(): CommandClassifier
    fun controlClassifier(): ControlClassifier
    fun speakerVerifier(): SpeakerVerifier
    fun loadAll(delegate: Delegate): Result<Unit>
    fun unloadAll()
}
