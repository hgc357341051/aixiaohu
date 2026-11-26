package com.example.voicectl.inference.api

/** 两级唤醒：L1 粗检（τ0.60）+ L2 精检（τ0.90 加 3 帧防抖） */
interface WakeDetector : InferenceEngine {
    fun detectCoarse(features: FloatArray): Float
    fun detectFine(features: FloatArray): Float
}
