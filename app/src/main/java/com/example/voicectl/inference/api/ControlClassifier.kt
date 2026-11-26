package com.example.voicectl.inference.api

enum class ControlWord { C0_NONE, C1_EXIT_WAKE, C2_EXIT_RECOG, C3_PAUSE, C4_RESUME }

data class ControlResult(val control: ControlWord, val score: Float)

/** 控制头：指令窗内先判，得分高于 τ_ctrl 且 top1 ≠ C0 才执行控制动作 */
interface ControlClassifier : InferenceEngine {
    fun classify(features: FloatArray): ControlResult
}
