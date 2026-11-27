package com.aixiaohuadb.audio

import kotlin.math.ceil

/**
 * 将逐帧 40 维 log-Mel 特征拼接为模型需要的固定长度窗口。
 * 不足时前部补 0，超出时保留最后 targetSize 个值。
 */
class FeatureWindow(
    private val targetSize: Int,
    private val nMels: Int = 40,
) {
    init {
        require(nMels > 0) { "nMels 必须为正数" }
    }

    private val maxFrames: Int = if (targetSize <= 0) 0 else ceil(targetSize.toDouble() / nMels).toInt()
    private val history = ArrayDeque<FloatArray>()

    fun push(frame: FloatArray) {
        if (targetSize <= 0 || maxFrames <= 0) return
        val normalized = FloatArray(nMels) { index -> frame.getOrElse(index) { 0f } }
        history.addLast(normalized)
        while (history.size > maxFrames) {
            history.removeFirst()
        }
    }

    fun snapshot(): FloatArray {
        val result = FloatArray(targetSize)
        if (targetSize <= 0 || history.isEmpty()) return result
        var offset = targetSize - history.sumOf { it.size }
        for (frame in history) {
            for (value in frame) {
                if (offset >= 0 && offset < targetSize) {
                    result[offset] = value
                }
                offset++
            }
        }
        return result
    }

    fun reset() {
        history.clear()
    }
}
