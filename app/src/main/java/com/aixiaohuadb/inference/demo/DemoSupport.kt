package com.aixiaohuadb.inference.demo

import kotlin.math.abs

/** 基于输入特征哈希的确定性伪随机分数，保证同样输入得到同样判决，便于调试复现 */
internal object DemoSupport {
    fun pseudoScore(features: FloatArray, salt: Int, min: Float, max: Float): Float {
        var hash = 17L
        for (value in features) {
            hash = hash * 31L + java.lang.Float.floatToIntBits(value).toLong()
        }
        val seed = abs((hash xor salt.toLong()) and Long.MAX_VALUE)
        val random = java.util.Random(seed)
        return min + random.nextFloat() * (max - min)
    }
}
