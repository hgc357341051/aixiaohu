package com.aixiaohuadb.audio

import kotlin.math.sqrt

/**
 * 轻量音频前端：AEC（有参考时抵消）/ NS（能量门）/ AGC（峰值归一）/ VAD。
 * 真实多麦波束与厂商 SDK 可在后续版本替换本类实现，接口保持不变。
 */
class FrontEnd(
    private val aecEnabled: Boolean = true,
    private val nsEnabled: Boolean = true,
    private val agcEnabled: Boolean = true,
    private val energyGate: Float = 0.008f,
) {
    private var echoWeight = 0.15f

    fun process(frame: FloatArray, echoReference: FloatArray? = null): FloatArray {
        val out = frame.copyOf()
        if (aecEnabled && echoReference != null && echoReference.size == out.size) {
            for (i in out.indices) out[i] -= echoReference[i] * echoWeight
        }
        if (nsEnabled) {
            val energy = rms(out)
            if (energy < energyGate) {
                for (i in out.indices) out[i] *= 0.05f
            }
        }
        if (agcEnabled) {
            var peak = 0f
            for (value in out) peak = maxOf(peak, kotlin.math.abs(value))
            if (peak > 0.9f) {
                val gain = 0.9f / peak
                for (i in out.indices) out[i] *= gain
            }
        }
        return out
    }

    fun isVoice(frame: FloatArray): Boolean = rms(frame) >= energyGate

    private fun rms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0f
        for (value in frame) sum += value * value
        return sqrt(sum / frame.size)
    }
}
