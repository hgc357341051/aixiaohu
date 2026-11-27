package com.aixiaohuadb.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** 40 维 log-Mel + 滑窗 CMVN，输出可直接送入 TFLite / Demo 引擎 */
class FeatureExtractor(
    private val nMels: Int = 40,
    private val sampleRate: Int = 16000,
    private val nFft: Int = 512,
    private val cmvnWindow: Int = 100,
) {
    private val hamming = FloatArray(nFft) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (nFft - 1))).toFloat()
    }
    private val melFilters: Array<FloatArray> = buildMelFilters()
    private val history = ArrayDeque<FloatArray>()

    fun extract(frame: FloatArray): FloatArray {
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        val count = minOf(frame.size, nFft)
        for (i in 0 until count) re[i] = frame[i] * hamming[i]
        fft(re, im)

        val power = FloatArray(nFft / 2 + 1) { i -> re[i] * re[i] + im[i] * im[i] }
        val features = FloatArray(nMels)
        for (m in 0 until nMels) {
            var sum = 0f
            val filter = melFilters[m]
            for (k in power.indices) sum += power[k] * filter.getOrElse(k) { 0f }
            features[m] = ln((sum + 1e-10f).toDouble()).toFloat()
        }
        return applyCmvn(features)
    }

    fun reset() = history.clear()

    private fun applyCmvn(features: FloatArray): FloatArray {
        history.addLast(features)
        while (history.size > cmvnWindow) history.removeFirst()
        val out = FloatArray(features.size)
        for (i in features.indices) {
            var mean = 0f
            for (row in history) mean += row[i] / history.size
            var variance = 0f
            for (row in history) {
                val d = row[i] - mean
                variance += d * d / history.size
            }
            val std = sqrt(variance)
            out[i] = if (std > 1e-6f) (features[i] - mean) / std else features[i] - mean
        }
        return out
    }

    private fun buildMelFilters(): Array<FloatArray> {
        val maxMel = hzToMel(sampleRate / 2.0)
        val points = DoubleArray(nMels + 2) { i -> melToHz(maxMel * i / (nMels + 1)) }
        val filters = Array(nMels) { FloatArray(nFft / 2 + 1) }
        for (m in 0 until nMels) {
            val left = points[m]
            val center = points[m + 1]
            val right = points[m + 2]
            for (k in 0..nFft / 2) {
                val hz = k * sampleRate.toDouble() / nFft
                val value = when {
                    hz < left || hz > right -> 0.0
                    hz <= center && center > left -> (hz - left) / (center - left)
                    hz > center && right > center -> (right - hz) / (right - center)
                    else -> 0.0
                }
                filters[m][k] = value.toFloat()
            }
        }
        return filters
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (exp(mel / 2595.0) - 1.0)

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wr = 1f
                var wi = 0f
                for (i in 0 until len / 2) {
                    val a = start + i
                    val b = a + len / 2
                    val vr = re[b] * wr - im[b] * wi
                    val vi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nextWr = wr * stepRe - wi * stepIm
                    wi = wr * stepIm + wi * stepRe
                    wr = nextWr
                }
                start += len
            }
            len *= 2
        }
    }
}
