package com.example.voicectl.domain

import com.example.voicectl.inference.api.ControlWord

/** 最近一次真实判决的指标快照，供调试页与诊断使用。 */
data class VoiceMetrics(
    val coarse: Float = 0f,
    val fine: Float = 0f,
    val commandId: Int? = null,
    val commandScore: Float = 0f,
    val control: ControlWord = ControlWord.C0_NONE,
    val controlScore: Float = 0f,
    val speakerSim: Float = 0f,
    val lastUpdateMs: Long = 0L,
    val audioCapturing: Boolean = false,
    val modelVersion: String = "demo-1.0.0",
    val adbConnected: Boolean = false,
)
